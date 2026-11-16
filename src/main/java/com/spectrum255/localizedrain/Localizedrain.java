package com.spectrum255.localizedrain;

import com.mojang.logging.LogUtils;
import com.spectrum255.localizedrain.api.StormProfile;
import com.spectrum255.localizedrain.api.StormProfiles;
import com.spectrum255.localizedrain.api.WeatherMode;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.network.LocalizedrainNetwork;
import com.spectrum255.localizedrain.network.StormSyncBroadcaster;
import com.spectrum255.localizedrain.network.StormSyncPayload;
import com.spectrum255.localizedrain.network.WeatherStatePayload;
import com.spectrum255.localizedrain.storm.StormManager;
import com.spectrum255.localizedrain.weather.LoadedChunks;
import com.spectrum255.localizedrain.weather.WeatherBiomeFilter;
import com.spectrum255.localizedrain.weather.WeatherManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;

@Mod(Localizedrain.MODID)
public final class Localizedrain {
    public static final String MODID = "localizedrain";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Localizedrain(IEventBus modEventBus, ModContainer modContainer) {
        // 配置统一放在 config/localizedrain/ 下：common.toml 是调试开关，weather.toml 是天气/风暴参数，
        // filters.toml 里用 whitelist / blacklist / overrides 三个 section 存放过滤器。
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("localizedrain");
        try {
            Files.createDirectories(configDir);
        } catch (IOException exception) {
            LOGGER.warn("[localizedrain] could not create config directory {}", configDir, exception);
        }
        // 旧版单文件配置：按新文件拆分迁移（保留旧文件作为备份，不删除）
        ConfigFiles.migrateLegacyConfigs();
        // common.toml 是所有存档共用的调试开关；weather.toml / filters.toml 是 SERVER 配置，
        // 每个存档在 <world>/serverconfig/localizedrain/ 下独立，
        // config/localizedrain/ 里的同名文件则作为新建存档的默认值来源。
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC, "localizedrain/common.toml");
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.WEATHER_SPEC, "localizedrain/weather.toml");
        modContainer.registerConfig(ModConfig.Type.SERVER, FilterConfig.FILTERS_SPEC, "localizedrain/filters.toml");
        // 领域层不直接构造网络包：安装网络层实现
        StormManager.setSyncSink(new StormSyncBroadcaster());
        // 自定义注册表必须先创建，DeferredRegister 只会在 NewRegistryEvent 里注册已经创建好的表；
        // 漏掉 makeRegistry 时编译能过，但运行期 localizedrain:weather_type 不存在。
        WeatherTypes.REGISTRY.makeRegistry(builder -> {});
        // 天气类型注册表（附属模组可以往 localizedrain:weather_type 里追加类型）
        WeatherTypes.REGISTRY.register(modEventBus);
        // 风暴档案是数据包注册表：内容来自 data/<ns>/storm_profile/<id>.json
        modEventBus.addListener((net.neoforged.neoforge.registries.DataPackRegistryEvent.NewRegistry event) ->
                event.dataPackRegistry(StormProfiles.REGISTRY_KEY, StormProfile.CODEC, StormProfile.CODEC));
        // 目标天气包：给 Xaero 天气行这类 HUD 提供即时值（客户端只拿平滑值）
        modEventBus.addListener(LocalizedrainNetwork::register);
        // 天气黑白名单依赖群系标签，配置重载和服务器启动（标签已绑定）时重建解析结果
        modEventBus.addListener((ModConfigEvent.Loading event) -> invalidateWeatherCaches());
        modEventBus.addListener((ModConfigEvent.Reloading event) -> invalidateWeatherCaches());
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            invalidateWeatherCaches();
            LOGGER.info("[localizedrain] weather mode = {}", WeatherMode.current());
            StormProfiles.logLoaded(event.getServer());
            // HEAD 注入时全局 weather.toml 可能还没生成（首次启动）；这里补一次，供下次启动使用
            ConfigFiles.seedWorldConfigs(event.getServer(), false);
            LOGGER.info("[localizedrain] per-world configs: {}", ConfigFiles.worldConfigStatus(event.getServer()));
        });
        // 服务器停止/换存档：清空风暴相关的静态缓存（LAST_HUD、LAST_SYNC、维度缓存等）
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> StormManager.resetCaches());
        // 数据包 /reload 后档案对象会重建，清掉每片风暴缓存的档案引用
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.OnDatapackSyncEvent event) -> {
            // 该事件在 /reload 时 player 为 null；玩家登录也会触发一次，登录不需要重建档案缓存
            if (event.getPlayer() == null) {
                StormManager.invalidateProfiles();
            }
        });
        NeoForge.EVENT_BUS.addListener(LocalizedWeatherCommand::register);
        // 天气扫描按「已加载区块」推进：维护每个维度的已加载区块集合
        NeoForge.EVENT_BUS.addListener(LoadedChunks::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(LoadedChunks::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(LoadedChunks::onLevelUnload);
        // 客户端天气状态会随登录/跨维度/重生重建，清掉同步缓存以便重新推送一次完整天气
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            forget(event.getEntity().getUUID());
            // 登录时补发一次风暴快照，客户端不必等第一次周期推送
            if (event.getEntity() instanceof ServerPlayer player) {
                // optional 通道在版本不一致时会静默不注册：先检查，避免补发的包直接丢掉
                boolean channels = NetworkRegistry.hasChannel(player.connection, StormSyncPayload.TYPE.id())
                        && NetworkRegistry.hasChannel(player.connection, WeatherStatePayload.TYPE.id());
                if (channels) {
                    // 登录时补发一次风暴快照，客户端不必等第一次周期推送
                    StormManager.sendTo(player);
                } else {
                    LOGGER.warn("Player {} connected without localizedrain network channels; weather/storm sync will be silent",
                            player.getGameProfile().getName());
                    player.sendSystemMessage(Component.translatable("message.localizedrain.protocol_mismatch"));
                }
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            forget(event.getEntity().getUUID());
            StormManager.forget(event.getEntity().getUUID());
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            forget(event.getEntity().getUUID());
            // 新维度有独立的风暴列表，登录式的补发同样必要；通道缺失时跳过，避免包被静默丢弃
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                    && NetworkRegistry.hasChannel(player.connection, StormSyncPayload.TYPE.id())
                    && NetworkRegistry.hasChannel(player.connection, WeatherStatePayload.TYPE.id())) {
                StormManager.sendTo(player);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent event) -> forget(event.getEntity().getUUID()));
    }

    private static void forget(UUID playerId) {
        WeatherManager.forget(playerId);
    }

    private static void invalidateWeatherCaches() {
        WeatherBiomeFilter.invalidate();
        WeatherTypes.invalidate();
        com.spectrum255.localizedrain.api.WeatherMode.invalidate();
        StormManager.invalidateProfiles();
    }
}

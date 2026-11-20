package com.spectrum255.localizedrain;

import com.mojang.logging.LogUtils;
import com.spectrum255.localizedrain.api.StormProfile;
import com.spectrum255.localizedrain.api.StormProfiles;
import com.spectrum255.localizedrain.api.WeatherMode;
import com.spectrum255.localizedrain.api.WeatherType;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
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

    /**
     * 服务器当前加载的天气类型定义指纹（{@link WeatherTypes#definitionDigest}）。
     * 用于在 {@code /reload} 后检测定义是否变化：天气类型注册表是登录阶段同步的，
     * 已在线客户端不会自动拿到新定义，只能提示重连。
     */
    private static long lastDefinitionDigest;

    public Localizedrain(IEventBus modEventBus, ModContainer modContainer) {
        // 配置统一放在 config/localizedrain/ 下：common.toml 是调试开关，global-weather.toml 是新建存档的
        // 天气默认值（主菜单可编辑），weather.toml 是每个存档独立的 SERVER 配置；三者都按节索引。
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("localizedrain");
        try {
            Files.createDirectories(configDir);
        } catch (IOException exception) {
            LOGGER.warn("[localizedrain] could not create config directory {}", configDir, exception);
        }
        // 旧版单文件配置：按新文件拆分迁移（保留旧文件作为备份，不删除）
        ConfigFiles.migrateLegacyConfigs();
        // 旧版全局默认（SERVER 的 weather.toml）迁移成主菜单可编辑的 global-weather.toml
        ConfigFiles.migrateGlobalDefaults();
        // 旧版平铺写法先改成按节索引（备份 .flat.bak），再把旧 filters 文件并进 [filters] 节；
        // 顺序不能反：先插入 [filters] 会让「是否已分节」的检测误判，跳过其余键的迁移。
        ConfigFiles.migrateFlatConfigsToSections();
        ConfigFiles.mergeLegacyFilters(configDir, configDir.resolve("global-weather.toml"));
        // common.toml / global-weather.toml 是 COMMON 配置：主菜单即可编辑，
        // 其中后者是新建存档的默认值。weather.toml 是 SERVER 配置：
        // 每个存档在 <world>/serverconfig/localizedrain/ 下有独立副本（由 ConfigFiles 在启动前复制）。
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC, "localizedrain/common.toml");
        modContainer.registerConfig(ModConfig.Type.COMMON, GlobalWeatherConfig.SPEC,
                "localizedrain/global-weather.toml");
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.WEATHER_SPEC, "localizedrain/weather.toml");
        // 分节结构自检：规范里登记的键数必须与 Config/GlobalWeatherConfig 实际定义一致
        ConfigFiles.verifySectionCoverage();
        // 配置目录数据包开关：config/localizedrain/datapacks.toml（由 DatapackConfig 自己读写，支持逐包开关）
        DatapackConfig.load();
        // 配置目录里的数据包：config/localizedrain/datapacks/<name>/data/...
        try {
            Files.createDirectories(ConfigDatapacks.root());
        } catch (IOException exception) {
            LOGGER.warn("[localizedrain] could not create config datapack directory {}", ConfigDatapacks.root(),
                    exception);
        }
        modEventBus.addListener(ConfigDatapacks::onAddPackFinders);
        modEventBus.addListener(BuiltinPresetDatapack::onAddPackFinders);
        // 领域层不直接构造网络包：安装网络层实现
        StormManager.setSyncSink(new StormSyncBroadcaster());
        // 天气类型与风暴档案都是数据包注册表：内容来自
        // data/<ns>/localizedrain/weather_type/<id>.json 与 data/<ns>/localizedrain/storm_profile/<id>.json
        modEventBus.addListener((net.neoforged.neoforge.registries.DataPackRegistryEvent.NewRegistry event) -> {
            event.dataPackRegistry(WeatherTypes.REGISTRY_KEY, WeatherType.CODEC, WeatherType.CODEC);
            event.dataPackRegistry(StormProfiles.REGISTRY_KEY, StormProfile.CODEC, StormProfile.CODEC);
        });
        // 目标天气包：给 Xaero 天气行这类 HUD 提供即时值（客户端只拿平滑值）
        modEventBus.addListener(LocalizedrainNetwork::register);
        // 天气黑白名单依赖群系标签，配置重载和服务器启动（标签已绑定）时重建解析结果
        modEventBus.addListener((ModConfigEvent.Loading event) -> invalidateWeatherCaches());
        modEventBus.addListener((ModConfigEvent.Reloading event) -> invalidateWeatherCaches());
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            invalidateWeatherCaches();
            lastDefinitionDigest = WeatherTypes.definitionDigest(event.getServer().overworld());
            LOGGER.info("[localizedrain] weather mode = {}", WeatherMode.current());
            StormProfiles.logLoaded(event.getServer());
            // HEAD 注入时全局 weather.toml 可能还没生成（首次启动）；这里补一次，供下次启动使用
            ConfigFiles.seedWorldConfigs(event.getServer(), false);
            LOGGER.info("[localizedrain] per-world configs: {}", ConfigFiles.worldConfigStatus(event.getServer()));
            if (WeatherMode.current() == WeatherMode.MOBILE) {
                // 移动模式的降水只来自风暴：强制到「会降水」的天气类型只过滤生成点，不会产生常驻降水
                ServerLevel overworld = event.getServer().overworld();
                boolean forcedPrecipitation = Config.BIOME_WEATHER_OVERRIDES.get().stream()
                        .map(Localizedrain::overrideTypeToken)
                        .filter(java.util.Objects::nonNull)
                        .map(token -> switch (token) {
                            case "clear" -> WeatherTypes.CLEAR_ID;
                            case "rain" -> WeatherTypes.RAIN_ID;
                            case "thunder" -> WeatherTypes.THUNDER_ID;
                            default -> ResourceLocation.tryParse(token);
                        })
                        .filter(java.util.Objects::nonNull)
                        .map(id -> WeatherTypes.resolve(overworld, id))
                        .anyMatch(type -> type != null && type.hasPrecipitation());
                if (forcedPrecipitation) {
                    LOGGER.warn("[localizedrain] mobile mode: weatherBiomeOverrides only filters natural storm "
                            + "spawn points; forced precipitation weather does not create rain/snow. "
                            + "Spawn storms manually, or use weatherMode=patches for persistent biome weather.");
                }
            }
        });
        // 服务器停止/换存档：清空风暴/天气的静态缓存（LAST_HUD、维度缓存、dayFactorLevel 强引用等）
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            StormManager.resetCaches();
            WeatherManager.resetCaches();
            StormSyncBroadcaster.resetWarnings();   // 截断告警在换存档后仍应重新出现
            com.spectrum255.localizedrain.api.StormProfiles.resetWarnings();
            lastDefinitionDigest = 0L;
        });
        // 数据包注册表/标签更新（登录同步、/reload）：清掉天气类型解析缓存。
        // 客户端否则会一直用登录时缓存的旧定义，数据包改粒子/雾/声音后不会生效。
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.TagsUpdatedEvent event) -> {
            WeatherTypes.invalidate();
            WeatherBiomeFilter.invalidate();
        });
        // 数据包 /reload 后档案对象会重建，清掉每片风暴缓存的档案引用
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.OnDatapackSyncEvent event) -> {
            // 该事件在 /reload 时 player 为 null；玩家登录也会触发一次，登录不需要重建档案缓存
            if (event.getPlayer() == null) {
                // 天气类型与风暴档案都是数据包注册表：/reload 后内容会更换，缓存必须重建
                WeatherTypes.invalidate();
                WeatherBiomeFilter.invalidate();
                StormManager.invalidateProfiles();
                com.spectrum255.localizedrain.api.StormProfiles.resetWarnings();
                // 「只警告一次」的标记同样要复位：否则同一会话里「删类型 → 修复 → 再删」只会在第一次提示
                StormManager.resetWarnings();
                com.spectrum255.localizedrain.weather.CompatWeatherQueries.resetWarnings();
                StormSyncBroadcaster.resetWarnings();
                // 已有片状态与风暴按新定义重算派生能力位（不改变类型 ID、计时器与归属）
                WeatherManager.refreshAfterDatapackReload(event.getPlayerList().getServer());
                // 天气类型注册表在登录阶段同步，已在线客户端拿不到新定义：检测到变化时明确提示重连
                long digest = WeatherTypes.definitionDigest(event.getPlayerList().getServer().overworld());
                if (lastDefinitionDigest != 0L && digest != lastDefinitionDigest) {
                    for (ServerPlayer player : event.getPlayerList().getPlayers()) {
                        player.sendSystemMessage(Component.translatable("message.localizedrain.definitions_changed"));
                    }
                }
                lastDefinitionDigest = digest;
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
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                resendStormSnapshot(player);
            }
        });
        // 重生（含跨维度重生）后客户端风暴缓存是旧的：不补发就要等下一次周期推送（40 tick），
        // 期间会渲染旧维度的风暴。与跨维度分支走同一套通道检查。
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent event) -> {
            forget(event.getEntity().getUUID());
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                resendStormSnapshot(player);
            }
        });
    }

    /** 通道存在时补发一次风暴快照；optional 通道在版本不一致时不会注册，直接发会被静默丢弃。 */
    private static void resendStormSnapshot(ServerPlayer player) {
        if (NetworkRegistry.hasChannel(player.connection, StormSyncPayload.TYPE.id())
                && NetworkRegistry.hasChannel(player.connection, WeatherStatePayload.TYPE.id())) {
            StormManager.sendTo(player);
        }
    }

    private static void forget(UUID playerId) {
        WeatherManager.forget(playerId);
    }

    /**
     * 从 {@code <群系选择器>=<天气>} 条目里取出天气部分。
     * 配置定义处已经校验过格式，这里只是防御：没有 {@code =} 或空值时返回 null，避免把整条误当天气 id。
     */
    private static String overrideTypeToken(String entry) {
        int separator = entry.lastIndexOf('=');
        if (separator < 0 || separator + 1 >= entry.length()) {
            return null;
        }
        String token = entry.substring(separator + 1).trim();
        return token.isEmpty() ? null : token;
    }

    private static void invalidateWeatherCaches() {
        WeatherBiomeFilter.invalidate();
        WeatherTypes.invalidate();
        com.spectrum255.localizedrain.api.WeatherMode.invalidate();
        StormManager.invalidateProfiles();
        // 新配置下的风暴上限/截断问题应重新提示，而不是沿用上次进程的「已警告」状态
        StormManager.resetWarnings();
        StormManager.resetHudCache();
        StormSyncBroadcaster.resetWarnings();
        com.spectrum255.localizedrain.api.StormProfiles.resetWarnings();
        com.spectrum255.localizedrain.weather.CompatWeatherQueries.refresh();
    }
}

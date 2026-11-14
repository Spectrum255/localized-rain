package com.spectrum255.localizedrain;

import com.mojang.logging.LogUtils;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.network.LocalizedrainNetwork;
import com.spectrum255.localizedrain.weather.LoadedChunks;
import com.spectrum255.localizedrain.weather.WeatherBiomeFilter;
import com.spectrum255.localizedrain.weather.WeatherManager;
import java.util.UUID;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

@Mod(Localizedrain.MODID)
public final class Localizedrain {
    public static final String MODID = "localizedrain";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Localizedrain(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // 自定义注册表必须先创建，DeferredRegister 只会在 NewRegistryEvent 里注册已经创建好的表；
        // 漏掉 makeRegistry 时编译能过，但运行期 localizedrain:weather_type 不存在。
        WeatherTypes.REGISTRY.makeRegistry(builder -> {});
        // 天气类型注册表（附属模组可以往 localizedrain:weather_type 里追加类型）
        WeatherTypes.REGISTRY.register(modEventBus);
        // 目标天气包：给 Xaero 天气行这类 HUD 提供即时值（客户端只拿平滑值）
        modEventBus.addListener(LocalizedrainNetwork::register);
        // 天气黑白名单依赖群系标签，配置重载和服务器启动（标签已绑定）时重建解析结果
        modEventBus.addListener((ModConfigEvent.Loading event) -> invalidateWeatherCaches());
        modEventBus.addListener((ModConfigEvent.Reloading event) -> invalidateWeatherCaches());
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> invalidateWeatherCaches());
        NeoForge.EVENT_BUS.addListener(LocalizedWeatherCommand::register);
        // 天气扫描按「已加载区块」推进：维护每个维度的已加载区块集合
        NeoForge.EVENT_BUS.addListener(LoadedChunks::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(LoadedChunks::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(LoadedChunks::onLevelUnload);
        // 客户端天气状态会随登录/跨维度/重生重建，清掉同步缓存以便重新推送一次完整天气
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> forget(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> forget(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> forget(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent event) -> forget(event.getEntity().getUUID()));
    }

    private static void forget(UUID playerId) {
        WeatherManager.forget(playerId);
    }

    private static void invalidateWeatherCaches() {
        WeatherBiomeFilter.invalidate();
        WeatherTypes.invalidate();
    }
}

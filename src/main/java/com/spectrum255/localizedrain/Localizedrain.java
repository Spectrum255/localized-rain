package com.spectrum255.localizedrain;

import com.mojang.logging.LogUtils;
import com.spectrum255.localizedrain.network.LocalizedrainNetwork;
import com.spectrum255.localizedrain.weather.WeatherManager;
import java.util.UUID;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

@Mod(Localizedrain.MODID)
public final class Localizedrain {
    public static final String MODID = "localizedrain";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Localizedrain(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // 目标天气包：给 Xaero 天气行这类 HUD 提供即时值（客户端只拿平滑值）
        modEventBus.addListener(LocalizedrainNetwork::register);
        NeoForge.EVENT_BUS.addListener(LocalizedWeatherCommand::register);
        // 客户端天气状态会随登录/跨维度/重生重建，清掉同步缓存以便重新推送一次完整天气
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> forget(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> forget(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> forget(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent event) -> forget(event.getEntity().getUUID()));
    }

    private static void forget(UUID playerId) {
        WeatherManager.forget(playerId);
    }
}

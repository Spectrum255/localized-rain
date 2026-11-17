package com.spectrum255.localizedrain;

import com.spectrum255.localizedrain.client.ClientWeatherHooks;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
// 监听器在构造函数里手动注册到 NeoForge.EVENT_BUS，因此这里不能加 @EventBusSubscriber：
// 自动订阅要求类里至少有一个 @SubscribeEvent 方法，否则构造阶段会直接抛异常导致模组加载失败。
@Mod(value = Localizedrain.MODID, dist = Dist.CLIENT)
public class LocalizedrainClient {
    public LocalizedrainClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > click on config.
        // Do not forget to add translations for your config options in the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // 可选兼容：Xaero 天气行（HUD 即时切换），没装 Xaero 时这里不会加载任何 Xaero 类
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            ClientWeatherHooks.onClientTick();
            // 风暴位置每 tick 外推，再按距离/高度算目标雨雷量写回原版通道
            com.spectrum255.localizedrain.client.ClientStormCache.tick();
            com.spectrum255.localizedrain.client.ClientStormWeather.tick(Minecraft.getInstance());
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            ClientWeatherHooks.onLoggingOut();
            com.spectrum255.localizedrain.client.ClientStormCache.clear();
            // 换服务器/重连后数据包注册表内容可能不同，清掉天气类型解析缓存
            com.spectrum255.localizedrain.api.WeatherTypes.invalidate();
        });
        ClientWeatherHooks.tryInstallXaero();
    }
}

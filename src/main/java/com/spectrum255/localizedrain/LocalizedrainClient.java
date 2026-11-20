package com.spectrum255.localizedrain;

import com.mojang.blaze3d.platform.InputConstants;
import com.spectrum255.localizedrain.client.ClientWeatherHooks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
// 监听器在构造函数里手动注册到 NeoForge.EVENT_BUS，因此这里不能加 @EventBusSubscriber：
// 自动订阅要求类里至少有一个 @SubscribeEvent 方法，否则构造阶段会直接抛异常导致模组加载失败。
@Mod(value = Localizedrain.MODID, dist = Dist.CLIENT)
public class LocalizedrainClient {
    /** 打开天气地图（云图）的按键，默认 K。 */
    public static final KeyMapping WEATHER_MAP = new KeyMapping(
            "key.localizedrain.weather_map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K,
            "key.categories.localizedrain");

    public LocalizedrainClient(ModContainer container, IEventBus modEventBus) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > click on config.
        // Do not forget to add translations for your config options in the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // 客户端表现配置（渐变时长、室内视觉/声音比例、Xaero 天气行开关）
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT,
                com.spectrum255.localizedrain.client.ClientWeatherConfig.SPEC, "localizedrain/client.toml");
        modEventBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(WEATHER_MAP));
        // 可选兼容：Xaero 天气行（HUD 即时切换），没装 Xaero 时这里不会加载任何 Xaero 类
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            Minecraft minecraft = Minecraft.getInstance();
            // 云图：按键打开（没有其他界面时）
            while (WEATHER_MAP.consumeClick()) {
                if (minecraft.screen == null) {
                    minecraft.setScreen(new com.spectrum255.localizedrain.client.gui.WeatherMapScreen());
                }
            }
            ClientWeatherHooks.onClientTick();
            // 风暴位置每 tick 外推，再按距离/高度算目标雨雷量写回原版通道
            com.spectrum255.localizedrain.client.ClientStormCache.tick();
            com.spectrum255.localizedrain.client.ClientStormWeather.tick(minecraft);
            // 自定义天气的粒子/环境音/雾效
            com.spectrum255.localizedrain.client.ClientWeatherVisuals.tick(minecraft);
        });
        // 自定义天气的雾效：只覆盖写了 visuals 字段的类型，内置类型保持原版
        NeoForge.EVENT_BUS.addListener(com.spectrum255.localizedrain.client.ClientWeatherVisuals::applyFogColor);
        NeoForge.EVENT_BUS.addListener(com.spectrum255.localizedrain.client.ClientWeatherVisuals::applyFogPlanes);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            ClientWeatherHooks.onLoggingOut();
            com.spectrum255.localizedrain.client.ClientStormCache.clear();
            com.spectrum255.localizedrain.client.ClientWeatherLayers.clear();
            com.spectrum255.localizedrain.client.ClientWeatherMap.clear();
            // 换服务器/重连后数据包注册表内容可能不同，清掉天气类型解析缓存
            com.spectrum255.localizedrain.api.WeatherTypes.invalidate();
        });
        ClientWeatherHooks.tryInstallXaero();
        // 可选兼容：Xaero 世界地图上的天气叠加（反射读取相机/缩放，失败自动禁用）
        if (net.neoforged.fml.ModList.get().isLoaded("xaeroworldmap")) {
            com.spectrum255.localizedrain.client.XaeroWorldMapOverlay.register(NeoForge.EVENT_BUS);
        }
        // 原版 getRainLevel/getThunderLevel 的客户端值不额外代理：片模式由服务端的
        // RainLevelChange 包、移动模式由 ClientStormWeather 逐玩家写入，再叠一层维度最大值会覆盖逐玩家结果
    }
}

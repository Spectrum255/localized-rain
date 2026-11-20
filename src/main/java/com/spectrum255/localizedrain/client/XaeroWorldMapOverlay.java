package com.spectrum255.localizedrain.client;

import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.network.WeatherMapPayload;
import java.lang.reflect.Field;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 可选兼容：在 Xaero 世界地图（{@code xaero.map.gui.GuiMap}）上叠加本模组的天气片与风暴。
 *
 * <p>Xaero 闭源且没有公开的「世界地图已打开」查询 API，这里用反射读取 GuiMap 的
 * {@code cameraX/cameraZ/scale} 三个字段，按「屏幕中心 = 相机位置」把方块坐标换算成 GUI 坐标。
 * 字段名或投影方式随 Xaero 版本变化时，捕获异常并永久禁用叠加，不影响地图本身。</p>
 *
 * <p>实验性功能，由 {@code client.toml} 的 {@code xaeroWeatherOverlay} 控制。</p>
 */
public final class XaeroWorldMapOverlay {
    private static final String GUI_MAP_CLASS = "xaero.map.gui.GuiMap";
    private static volatile boolean disabled;
    private static volatile boolean warned;

    private XaeroWorldMapOverlay() {}

    /** 注册到游戏总线（NeoForge.EVENT_BUS）；Xaero 未安装时监听器只做一次类名比较。 */
    public static void register(net.neoforged.bus.api.IEventBus gameBus) {
        gameBus.addListener(ScreenEvent.Render.Post.class, XaeroWorldMapOverlay::onRender);
    }

    private static void onRender(ScreenEvent.Render.Post event) {
        if (disabled || !ClientWeatherConfig.xaeroWeatherOverlay()) {
            return;
        }
        Screen screen = event.getScreen();
        if (screen == null || !GUI_MAP_CLASS.equals(screen.getClass().getName())) {
            return;
        }
        WeatherMapPayload payload = ClientWeatherMap.current();
        if (payload == null) {
            // 地图打开后还没有数据：请求一次，下一帧再画
            ClientWeatherMap.requestRefresh();
            return;
        }
        try {
            draw(screen, event.getGuiGraphics(), payload);
        } catch (Throwable throwable) {
            disabled = true;
            Localizedrain.LOGGER.warn("[localizedrain] Xaero world map overlay disabled after an error; "
                    + "set xaeroWeatherOverlay=false to silence this", throwable);
        }
    }

    private static void draw(Screen screen, GuiGraphics graphics, WeatherMapPayload payload) throws Exception {
        double cameraX = readDouble(screen, "cameraX");
        double cameraZ = readDouble(screen, "cameraZ");
        double scale = readDouble(screen, "scale");
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraZ) || !Double.isFinite(scale)
                || scale < 0.001D || scale > 4096.0D) {
            warnOnce("unexpected Xaero map camera/scale: " + cameraX + "," + cameraZ + "," + scale);
            return;
        }
        int centerX = screen.width / 2;
        int centerY = screen.height / 2;
        int cellSize = (int) Math.round(16.0D * scale);

        for (WeatherMapPayload.PatchView patch : payload.patches()) {
            int x0 = (int) Math.round(centerX + (patch.x() - cameraX) * scale - cellSize / 2.0D);
            int z0 = (int) Math.round(centerY + (patch.z() - cameraZ) * scale - cellSize / 2.0D);
            int x1 = x0 + Math.max(1, cellSize);
            int z1 = z0 + Math.max(1, cellSize);
            if (x1 < 0 || x0 > screen.width || z1 < 0 || z0 > screen.height) {
                continue;
            }
            int alpha = 40 + (int) (patch.rain() * 110.0F);
            graphics.fill(Math.max(0, x0), Math.max(0, z0),
                    Math.min(screen.width, x1), Math.min(screen.height, z1),
                    (alpha << 24) | (colorFor(patch.typeId()) & 0xFFFFFF));
        }

        for (WeatherMapPayload.StormView storm : payload.storms()) {
            double sx = centerX + (storm.x() - cameraX) * scale;
            double sz = centerY + (storm.z() - cameraZ) * scale;
            double radius = Math.max(2.0D, storm.radius() * scale);
            if (sx + radius < 0 || sx - radius > screen.width || sz + radius < 0 || sz - radius > screen.height) {
                continue;
            }
            int color = colorFor(storm.typeId());
            int centerXi = (int) Math.round(sx);
            int centerZi = (int) Math.round(sz);
            int radiusI = (int) Math.round(radius);
            for (int dy = -radiusI; dy <= radiusI; dy++) {
                int half = (int) Math.sqrt(Math.max(0, radiusI * radiusI - dy * dy));
                int y = centerZi + dy;
                if (y < 0 || y >= screen.height) {
                    continue;
                }
                int x0 = Math.max(0, centerXi - half);
                int x1 = Math.min(screen.width, centerXi + half);
                if (x1 > x0) {
                    graphics.fill(x0, y, x1, y + 1, 0x55_000000 | (color & 0xFFFFFF));
                }
            }
        }
    }

    private static double readDouble(Screen screen, String name) throws Exception {
        Field field = screen.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getDouble(screen);
    }

    private static void warnOnce(String message) {
        if (!warned) {
            warned = true;
            Localizedrain.LOGGER.warn("[localizedrain] Xaero world map overlay skipped: {}", message);
        }
    }

    private static int colorFor(net.minecraft.resources.ResourceLocation typeId) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.level != null && typeId != null) {
            var type = com.spectrum255.localizedrain.api.WeatherTypes.resolve(minecraft.level, typeId);
            if (type != null) {
                return type.visuals().skyColor().orElse(0x4477AA);
            }
        }
        return 0x4477AA;
    }
}

package com.spectrum255.localizedrain.client.gui;

import com.spectrum255.localizedrain.api.WeatherType;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.client.ClientWeatherMap;
import com.spectrum255.localizedrain.network.WeatherMapPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * 天气地图（云图）：片着色 + 风暴圆 + 风矢量 + 预报面板。
 *
 * <p>数据全部来自 {@link ClientWeatherMap}（服务端 10 tick 限流推送），界面本身不访问服务端世界，
 * 因此可以在任意客户端线程安全绘制。按 K 打开，ESC 关闭。</p>
 */
public class WeatherMapScreen extends Screen {
    private static final Component TITLE = Component.translatable("screen.localizedrain.weather_map");
    private static final Component EMPTY = Component.translatable("screen.localizedrain.weather_map.empty");
    private static final int REFRESH_INTERVAL_TICKS = 20;
    /** 地图面板横向占屏比例。 */
    private static final float MAP_WIDTH_RATIO = 0.64F;
    /** 地图显示半径（方块）：屏幕内最多显示 512×512 方块。 */
    private static final int VIEW_RADIUS = 256;
    private static final int FALLBACK_COLOR = 0x4477AA;

    private int refreshTimer;
    private int mapLeft;
    private int mapTop;
    private int mapRight;
    private int mapBottom;
    private double blocksToPixels = 1.0D;

    public WeatherMapScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        this.mapLeft = 8;
        this.mapTop = 32;
        // 窄窗口时给右侧面板留至少 48 px，避免侧栏宽度为负、图例画出地图区
        this.mapRight = Math.min(Math.max(this.mapLeft + 80, (int) (this.width * MAP_WIDTH_RATIO)),
                Math.max(this.mapLeft + 48, this.width - 48));
        this.mapBottom = this.height - 8;
        this.blocksToPixels = Math.min((this.mapRight - this.mapLeft) / (VIEW_RADIUS * 2.0D),
                (this.mapBottom - this.mapTop) / (VIEW_RADIUS * 2.0D));
        ClientWeatherMap.requestRefresh();
    }

    @Override
    public void tick() {
        if (++this.refreshTimer >= REFRESH_INTERVAL_TICKS) {
            this.refreshTimer = 0;
            ClientWeatherMap.requestRefresh();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, TITLE, this.width / 2, 10, 0xFFFFFF);
        graphics.fill(this.mapLeft, this.mapTop, this.mapRight, this.mapBottom, 0x66000000);
        graphics.renderOutline(this.mapLeft, this.mapTop, this.mapRight - this.mapLeft,
                this.mapBottom - this.mapTop, 0x66FFFFFF);

        WeatherMapPayload payload = ClientWeatherMap.current();
        if (payload == null) {
            graphics.drawCenteredString(this.font, EMPTY, (this.mapLeft + this.mapRight) / 2,
                    (this.mapTop + this.mapBottom) / 2, 0xAAAAAA);
            return;
        }

        double centerX = (this.mapLeft + this.mapRight) / 2.0D;
        double centerY = (this.mapTop + this.mapBottom) / 2.0D;
        double playerX = this.minecraft != null && this.minecraft.player != null
                ? this.minecraft.player.getX() : 0.0D;
        double playerZ = this.minecraft != null && this.minecraft.player != null
                ? this.minecraft.player.getZ() : 0.0D;

        renderPatches(graphics, payload, centerX, centerY, playerX, playerZ);
        renderStorms(graphics, payload, centerX, centerY, playerX, playerZ);
        renderPlayerArrow(graphics, centerX, centerY);
        renderWind(graphics, payload);
        renderSidePanel(graphics, payload, mouseX, mouseY);
    }

    private void renderPatches(GuiGraphics graphics, WeatherMapPayload payload,
                               double centerX, double centerY, double playerX, double playerZ) {
        float cellPixels = (float) (16.0D * this.blocksToPixels);
        for (WeatherMapPayload.PatchView patch : payload.patches()) {
            int x0 = (int) Math.round(centerX + (patch.x() - playerX) * this.blocksToPixels - cellPixels / 2.0D);
            int z0 = (int) Math.round(centerY + (patch.z() - playerZ) * this.blocksToPixels - cellPixels / 2.0D);
            int size = Math.max(1, Math.round(cellPixels));
            if (x0 + size < this.mapLeft || x0 > this.mapRight || z0 + size < this.mapTop || z0 > this.mapBottom) {
                continue;
            }
            int alpha = Mth.clamp(48 + (int) (patch.rain() * 150.0F), 0, 255);
            int color = (alpha << 24) | (colorFor(patch.typeId()) & 0xFFFFFF);
            int clipX0 = Math.max(x0, this.mapLeft);
            int clipZ0 = Math.max(z0, this.mapTop);
            int clipX1 = Math.min(x0 + size, this.mapRight);
            int clipZ1 = Math.min(z0 + size, this.mapBottom);
            if (clipX1 > clipX0 && clipZ1 > clipZ0) {
                graphics.fill(clipX0, clipZ0, clipX1, clipZ1, color);
            }
        }
    }

    private void renderStorms(GuiGraphics graphics, WeatherMapPayload payload,
                              double centerX, double centerY, double playerX, double playerZ) {
        for (WeatherMapPayload.StormView storm : payload.storms()) {
            double sx = centerX + (storm.x() - playerX) * this.blocksToPixels;
            double sz = centerY + (storm.z() - playerZ) * this.blocksToPixels;
            double radius = Math.max(2.0D, storm.radius() * this.blocksToPixels);
            if (sx + radius < this.mapLeft || sx - radius > this.mapRight
                    || sz + radius < this.mapTop || sz - radius > this.mapBottom) {
                continue;
            }
            int color = colorFor(storm.typeId());
            int fill = 0x33_000000 | (color & 0xFFFFFF);
            int edge = 0xCC_000000 | (color & 0xFFFFFF);
            int centerXi = (int) Math.round(sx);
            int centerZi = (int) Math.round(sz);
            int radiusI = (int) Math.round(radius);
            // 逐行填充在大半径下可达每帧数千次 fill：按行块合并，视觉无差别但调用数降到 1/64
            int step = Math.max(1, radiusI / 64);
            for (int dy = -radiusI; dy <= radiusI; dy += step) {
                int half = (int) Math.sqrt(Math.max(0, radiusI * radiusI - dy * dy));
                int y = centerZi + dy;
                if (y < this.mapTop || y >= this.mapBottom) {
                    continue;
                }
                int x0 = Math.max(this.mapLeft, centerXi - half);
                int x1 = Math.min(this.mapRight, centerXi + half);
                if (x1 <= x0) {
                    continue;
                }
                boolean edgeRow = dy == -radiusI || dy + step > radiusI;
                graphics.fill(x0, y, x1, Math.min(y + step, this.mapBottom), edgeRow ? edge : fill);
            }
        }
    }

    private void renderPlayerArrow(GuiGraphics graphics, double centerX, double centerY) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        int cx = (int) Math.round(centerX);
        int cy = (int) Math.round(centerY);
        float yaw = this.minecraft.player.getYRot();
        double radians = Math.toRadians(yaw);
        int tipX = (int) Math.round(cx - Math.sin(radians) * 8.0D);
        int tipY = (int) Math.round(cy - Math.cos(radians) * 8.0D);
        graphics.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFFFFFFFF);
        graphics.fill(Math.min(cx, tipX), Math.min(cy, tipY), Math.max(cx, tipX) + 1,
                Math.max(cy, tipY) + 1, 0xFF7FDF7F);
    }

    private void renderWind(GuiGraphics graphics, WeatherMapPayload payload) {
        float speed = (float) Math.hypot(payload.windX(), payload.windZ());
        if (speed <= 0.001F) {
            // 片/原版模式服务端不发风；没有风就不显示「0.00」，避免误导
            return;
        }
        int x = this.mapLeft + 8;
        int y = this.mapBottom - 26;
        String text = Component.translatable("screen.localizedrain.weather_map.wind",
                String.format(java.util.Locale.ROOT, "%.2f", speed)).getString();
        graphics.drawString(this.font, text, x, y, 0xDDDDDD, false);
        int len = Mth.clamp((int) (speed * 12.0F), 8, 40);
        int dx = (int) Math.round(payload.windX() / speed * len);
        int dy = (int) Math.round(payload.windZ() / speed * len);
        int ax = x + 4;
        int ay = y - 6;
        graphics.fill(Math.min(ax, ax + dx), Math.min(ay, ay + dy),
                Math.max(ax, ax + dx) + 1, Math.max(ay, ay + dy) + 1, 0xFF88CCFF);
    }

    private void renderSidePanel(GuiGraphics graphics, WeatherMapPayload payload, int mouseX, int mouseY) {
        int x = this.mapRight + 8;
        int y = 36;
        int width = Math.max(40, this.width - x - 8);
        graphics.drawString(this.font, Component.translatable("screen.localizedrain.weather_map.forecast"),
                x, y, 0xFFFFFF, false);
        y += 14;
        if (payload.forecast().isEmpty()) {
            graphics.drawString(this.font, Component.translatable("screen.localizedrain.weather_map.no_forecast"),
                    x, y, 0x999999, false);
            y += 14;
        } else {
            for (WeatherMapPayload.ForecastView step : payload.forecast()) {
                if (y > this.mapBottom - 80) {
                    break;
                }
                int seconds = Math.max(0, step.inTicks() / 20);
                String line = Component.translatable("screen.localizedrain.weather_map.forecast_line",
                        seconds, displayName(step.typeId()), Math.round(step.intensity() * 100.0F)).getString();
                graphics.drawString(this.font, this.font.plainSubstrByWidth(line, width), x, y, 0xDDDDDD, false);
                y += 12;
            }
        }

        y += 6;
        graphics.drawString(this.font, Component.translatable("screen.localizedrain.weather_map.storms"),
                x, y, 0xFFFFFF, false);
        y += 14;
        if (payload.storms().isEmpty()) {
            graphics.drawString(this.font, Component.translatable("screen.localizedrain.weather_map.no_storms"),
                    x, y, 0x999999, false);
        } else {
            for (WeatherMapPayload.StormView storm : payload.storms()) {
                if (y > this.mapBottom - 12) {
                    break;
                }
                String line = Component.translatable("screen.localizedrain.weather_map.storm_line",
                        displayName(storm.typeId()), storm.stageIndex() + 1, storm.stageCount(),
                        Math.round(storm.radius()), Math.round(storm.rain() * 100.0F),
                        Math.round(storm.thunder() * 100.0F)).getString();
                graphics.drawString(this.font, this.font.plainSubstrByWidth(line, width), x, y, 0xDDDDDD, false);
                y += 12;
            }
        }

        renderLegend(graphics, payload, x);
        renderHoverTooltip(graphics, payload, mouseX, mouseY);
    }

    private void renderLegend(GuiGraphics graphics, WeatherMapPayload payload, int x) {
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        for (WeatherMapPayload.PatchView patch : payload.patches()) {
            counts.merge(patch.typeId(), 1, Integer::sum);
        }
        for (WeatherMapPayload.StormView storm : payload.storms()) {
            counts.merge(storm.typeId(), 1, Integer::sum);
        }
        int y = this.mapBottom - 12 - Math.min(6, counts.size()) * 11;
        int shown = 0;
        for (Map.Entry<ResourceLocation, Integer> entry : counts.entrySet()) {
            if (shown++ >= 6) {
                break;
            }
            graphics.fill(x, y + 1, x + 8, y + 9, 0xFF000000 | colorFor(entry.getKey()));
            graphics.drawString(this.font, displayName(entry.getKey()), x + 12, y, 0xCCCCCC, false);
            y += 11;
        }
    }

    private void renderHoverTooltip(GuiGraphics graphics, WeatherMapPayload payload, int mouseX, int mouseY) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        if (mouseX < this.mapLeft || mouseX > this.mapRight || mouseY < this.mapTop || mouseY > this.mapBottom) {
            return;
        }
        double centerX = (this.mapLeft + this.mapRight) / 2.0D;
        double centerY = (this.mapTop + this.mapBottom) / 2.0D;
        double playerX = this.minecraft.player.getX();
        double playerZ = this.minecraft.player.getZ();
        for (WeatherMapPayload.PatchView patch : payload.patches()) {
            int x = (int) Math.round(centerX + (patch.x() - playerX) * this.blocksToPixels);
            int z = (int) Math.round(centerY + (patch.z() - playerZ) * this.blocksToPixels);
            int half = Math.max(2, (int) (8.0D * this.blocksToPixels));
            if (Math.abs(mouseX - x) <= half && Math.abs(mouseY - z) <= half) {
                List<Component> lines = new ArrayList<>(4);
                lines.add(displayName(patch.typeId()));
                lines.add(Component.translatable("screen.localizedrain.weather_map.patch_line",
                        Math.round(patch.x()), Math.round(patch.z()),
                        Math.round(patch.rain() * 100.0F), Math.round(patch.thunder() * 100.0F)));
                lines.add(Component.translatable("screen.localizedrain.weather_map.ticks_left",
                        patch.rainTicksLeft() / 20, patch.thunderTicksLeft() / 20));
                if (patch.forced()) {
                    lines.add(Component.translatable("screen.localizedrain.weather_map.forced"));
                }
                if (patch.locked()) {
                    lines.add(Component.translatable("screen.localizedrain.weather_map.locked"));
                }
                graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
                return;
            }
        }
    }

    private Component displayName(ResourceLocation typeId) {
        if (this.minecraft != null && this.minecraft.level != null) {
            WeatherType type = WeatherTypes.resolve(this.minecraft.level, typeId);
            if (type != null) {
                return type.display().map(Component::literal).orElseGet(
                        () -> Component.literal(typeId == null ? "?" : typeId.toString()));
            }
        }
        return Component.literal(typeId == null ? "?" : typeId.toString());
    }

    private int colorFor(ResourceLocation typeId) {
        if (this.minecraft != null && this.minecraft.level != null && typeId != null) {
            WeatherType type = WeatherTypes.resolve(this.minecraft.level, typeId);
            if (type != null) {
                return type.visuals().skyColor().orElse(FALLBACK_COLOR);
            }
        }
        return FALLBACK_COLOR;
    }
}

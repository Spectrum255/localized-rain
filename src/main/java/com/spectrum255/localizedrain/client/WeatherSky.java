package com.spectrum255.localizedrain.client;

import com.spectrum255.localizedrain.api.WeatherType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * 数据包天空盒覆盖层：把当前天气类型的 {@code visuals} 天空字段叠加到原版天空渲染上。
 *
 * <p>这里只改「取值」，不接管 {@code LevelRenderer.renderSky}：天空盘几何、日出扇形、日月方片、
 * 星星点云与雾序都保持原版，覆盖结果通过 mixin 注入回原版方法。</p>
 *
 * <ul>
 *   <li>{@code sky_color}：替换群系的天空基色，按强度插值；昼夜系数、雨雷变暗、闪电提亮仍由原版叠加。</li>
 *   <li>{@code cloud_color}：作为云颜色乘数（{@code #ffffff} 等于原版），按强度从白色插值到目标色调。</li>
 *   <li>{@code sunrise_color}：只在原版本来就有日出/日落扇形时替换其颜色，alpha 保留原版。</li>
 *   <li>{@code star_brightness}：星星亮度乘数，按强度插值；0 表示看不见星星。</li>
 *   <li>{@code sun_visible} / {@code moon_visible}：为 false 时把日月贴图换成全透明贴图。</li>
 * </ul>
 */
public final class WeatherSky {
    /** 1×1 全透明贴图：日月隐藏时用它替换原版贴图（混合函数 SRC_ALPHA/ONE 下完全不可见）。 */
    private static final ResourceLocation INVISIBLE =
            ResourceLocation.fromNamespaceAndPath("localizedrain", "textures/environment/invisible.png");

    private WeatherSky() {}

    /** 天空基色：把原版群系采样色按强度拉向 {@code sky_color}。 */
    public static Vec3 skyBaseColor(Vec3 vanilla) {
        if (!normalSky()) {
            // 只有 NORMAL 天空才画天空盘；在末地/下界染色只会把默认雾色一起染了
            return vanilla;
        }
        WeatherType.Visuals visuals = visuals();
        if (visuals == null) {
            return vanilla;
        }
        Optional<Integer> color = visuals.skyColor();
        if (color.isEmpty()) {
            return vanilla;
        }
        int rgb = color.get();
        float intensity = ClientWeatherVisuals.currentIntensity();
        return new Vec3(
                Mth.lerp(intensity, vanilla.x, red(rgb)),
                Mth.lerp(intensity, vanilla.y, green(rgb)),
                Mth.lerp(intensity, vanilla.z, blue(rgb)));
    }

    /**
     * 云颜色：原版结果（已含昼夜与雨雷调制）乘以目标色调。
     * 色相越暗云越暗，{@code #ffffff} 表示保持原版；不能用来提亮。
     */
    public static Vec3 cloudColor(Vec3 vanilla) {
        WeatherType.Visuals visuals = visuals();
        if (visuals == null) {
            return vanilla;
        }
        Optional<Integer> color = visuals.cloudColor();
        if (color.isEmpty()) {
            return vanilla;
        }
        int rgb = color.get();
        float intensity = ClientWeatherVisuals.currentIntensity();
        return new Vec3(
                vanilla.x * Mth.lerp(intensity, 1.0F, red(rgb)),
                vanilla.y * Mth.lerp(intensity, 1.0F, green(rgb)),
                vanilla.z * Mth.lerp(intensity, 1.0F, blue(rgb)));
    }

    /**
     * 日出/日落颜色：只改 RGB，保留原版 alpha 与「是否该画」的判断。
     * 原版返回 {@code null}（太阳不在地平线附近）时原样返回，不会凭空生成扇形。
     */
    public static float[] sunriseColor(float[] vanilla) {
        if (vanilla == null) {
            return null;
        }
        WeatherType.Visuals visuals = visuals();
        if (visuals == null) {
            return vanilla;
        }
        Optional<Integer> color = visuals.sunriseColor();
        if (color.isEmpty()) {
            return vanilla;
        }
        int rgb = color.get();
        float intensity = ClientWeatherVisuals.currentIntensity();
        return new float[]{
                Mth.lerp(intensity, vanilla[0], red(rgb)),
                Mth.lerp(intensity, vanilla[1], green(rgb)),
                Mth.lerp(intensity, vanilla[2], blue(rgb)),
                vanilla[3]};
    }

    /** 星星亮度：在原版值上乘 {@code star_brightness}，按强度插值。 */
    public static float starBrightness(float vanilla) {
        WeatherType.Visuals visuals = visuals();
        if (visuals == null) {
            return vanilla;
        }
        Optional<Float> factor = visuals.starBrightness();
        if (factor.isEmpty()) {
            return vanilla;
        }
        return Mth.lerp(ClientWeatherVisuals.currentIntensity(), vanilla, vanilla * factor.get());
    }

    /** 太阳贴图：类型要求隐藏时换成全透明贴图。 */
    public static ResourceLocation sunTexture(ResourceLocation vanilla) {
        WeatherType.Visuals visuals = visuals();
        return visuals != null && !visuals.sunVisible() ? INVISIBLE : vanilla;
    }

    /** 月亮贴图：类型要求隐藏时换成全透明贴图。 */
    public static ResourceLocation moonTexture(ResourceLocation vanilla) {
        WeatherType.Visuals visuals = visuals();
        return visuals != null && !visuals.moonVisible() ? INVISIBLE : vanilla;
    }

    /** 当前维度是否走原版 {@code NORMAL} 天空（末地/下界是独立实现，不画天空盘与日月）。 */
    private static boolean normalSky() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null
                && minecraft.level.effects().skyType() == DimensionSpecialEffects.SkyType.NORMAL;
    }

    /** 当前生效的 visuals；没有自定义天气（或强度可忽略）时返回 {@code null}。 */
    private static WeatherType.Visuals visuals() {
        if (!ClientWeatherVisuals.active()) {
            return null;
        }
        WeatherType type = ClientWeatherVisuals.currentType();
        return type == null ? null : type.visuals();
    }

    private static float red(int rgb) {
        return ((rgb >> 16) & 0xFF) / 255.0F;
    }

    private static float green(int rgb) {
        return ((rgb >> 8) & 0xFF) / 255.0F;
    }

    private static float blue(int rgb) {
        return (rgb & 0xFF) / 255.0F;
    }
}

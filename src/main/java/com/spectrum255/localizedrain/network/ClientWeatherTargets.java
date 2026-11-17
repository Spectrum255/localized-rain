package com.spectrum255.localizedrain.network;

/**
 * 客户端保存的「目标」天气（服务端当前所在群系片的状态），供 HUD 与客户端视觉层读取。
 *
 * <p>只保存两个布尔值、显示键、类型 id 与展示强度，不引用任何客户端专用类，
 * 因此在两端都能安全加载。{@code typeKey} 非空时表示自定义天气类型，HUD 应优先显示它；
 * {@code typeId} 供视觉层解析 {@code particle}/{@code sound}。</p>
 */
public final class ClientWeatherTargets {
    private static volatile boolean raining;
    private static volatile boolean thundering;
    private static volatile String typeKey = "";
    private static volatile String typeId = "";
    private static volatile float intensity;

    private ClientWeatherTargets() {}

    public static void set(boolean raining, boolean thundering, String typeKey, String typeId, float intensity) {
        ClientWeatherTargets.raining = raining;
        ClientWeatherTargets.thundering = thundering;
        ClientWeatherTargets.typeKey = typeKey == null ? "" : typeKey;
        ClientWeatherTargets.typeId = typeId == null ? "" : typeId;
        ClientWeatherTargets.intensity = intensity;
    }

    /** 断开连接时清空，避免下次进入世界的第一帧显示上一个存档的天气。 */
    public static void clear() {
        set(false, false, "", "", 0.0F);
    }

    public static boolean isRaining() {
        return raining;
    }

    public static boolean isThundering() {
        return thundering;
    }

    /** 自定义天气类型的显示键；内置类型与晴天为空串。 */
    public static String typeKey() {
        return typeKey;
    }

    /** 自定义天气类型的完整 id；内置类型与晴天为空串。 */
    public static String typeId() {
        return typeId;
    }

    /** 天气展示强度 0–1；无降水类型也可能为非零。 */
    public static float intensity() {
        return intensity;
    }
}

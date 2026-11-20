package com.spectrum255.localizedrain.storm;

import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * 领域层（{@link StormManager}）向网络层提供的只读投影。
 * 领域对象不引用任何网络包类型；由 {@code StormSyncBroadcaster} 负责编码与发送。
 */
public record StormSyncData(List<Entry> storms, String isolatedMode, double falloffRatio, boolean active) {
    /** 客户端渲染只需要这些字段，不发档案与阶段计时。 */
    public record Entry(UUID id, double x, double z, double radius,
                        double velocityX, double velocityZ,
                        double minY, double maxY,
                        ResourceLocation typeId,
                        boolean hasPrecipitation, boolean allowsThunder,
                        float rain, float thunder,
                        boolean manual, boolean dying, int dyingTicksLeft) {}

    /**
     * HUD/视觉目标值：只在变化时发送。
     *
     * <p>顶层 {@code typeKey/typeId/intensity} 是主导层（强度最大的那一层），供旧客户端字段语义与
     * Xaero 单行回退使用；{@code layers} 是完整叠加层列表（移动模式最多
     * {@code maxStackedWeatherTypes} 层，片模式恒为 0 或 1 层）。</p>
     */
    public record Hud(boolean raining, boolean thundering, String typeKey, String typeId, float intensity,
                      List<HudLayer> layers) {}

    /** 单层 HUD：typeKey 为空表示原版内置类型（与顶层语义一致），typeId 始终是真实 id。 */
    public record HudLayer(String typeKey, String typeId, float intensity) {}
}

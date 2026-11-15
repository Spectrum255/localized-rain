package com.spectrum255.localizedrain.api;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** 不可变风暴快照，事件与 API 都用它。 */
public record StormSnapshot(
        UUID id,
        double x, double z, double radius,
        double minY, double maxY,
        ResourceLocation profileId, ResourceLocation weatherType,
        int stageIndex, int stageCount,
        float rainIntensity, float thunderIntensity,
        double velocityX, double velocityZ,
        float temperature, float moisture,
        long stageTicksRemaining,
        boolean manual, boolean dying) {

    /** 该时刻是否会覆盖给定坐标（含竖直区间，不含群系隔离判定）。 */
    public boolean covers(double px, double py, double pz) {
        double dx = px - this.x;
        double dz = pz - this.z;
        return dx * dx + dz * dz < this.radius * this.radius
                && py >= this.minY && py <= this.maxY;
    }
}

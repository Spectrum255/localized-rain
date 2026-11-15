package com.spectrum255.localizedrain.api;

/** 维度风场只读视图。速度单位是方块/tick。 */
public record WindState(double velocityX, double velocityZ) {
    public float speed() {
        return (float) Math.hypot(this.velocityX, this.velocityZ);
    }

    /** 风向角度（度），0 = +X，逆时针增大，范围 [0, 360)。 */
    public float directionDegrees() {
        float degrees = (float) Math.toDegrees(Math.atan2(this.velocityZ, this.velocityX));
        return (degrees + 360.0F) % 360.0F;
    }
}

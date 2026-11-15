package com.spectrum255.localizedrain.storm;

/** 风暴系统的共享常量：领域层单一来源，网络包只做引用。 */
public final class StormTuning {
    private StormTuning() {}

    /** 风暴消亡淡出固定 tick 数；服务端计时与客户端平滑共用。 */
    public static final int FADE_TICKS = 100;
}

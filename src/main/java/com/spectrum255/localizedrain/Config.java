package com.spectrum255.localizedrain;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MIN_CLEAR_TICKS = BUILDER.defineInRange("minClearTicks", 12000, 20, 720000);
    public static final ModConfigSpec.IntValue MAX_CLEAR_TICKS = BUILDER.defineInRange("maxClearTicks", 168000, 20, 720000);
    public static final ModConfigSpec.IntValue MIN_RAIN_TICKS = BUILDER.defineInRange("minRainTicks", 12000, 20, 720000);
    public static final ModConfigSpec.IntValue MAX_RAIN_TICKS = BUILDER.defineInRange("maxRainTicks", 24000, 20, 720000);
    public static final ModConfigSpec.IntValue MIN_THUNDER_DELAY_TICKS = BUILDER.defineInRange("minThunderDelayTicks", 12000, 20, 720000);
    public static final ModConfigSpec.IntValue MAX_THUNDER_DELAY_TICKS = BUILDER.defineInRange("maxThunderDelayTicks", 180000, 20, 720000);
    public static final ModConfigSpec.IntValue MIN_THUNDER_TICKS = BUILDER.defineInRange("minThunderTicks", 3600, 20, 720000);
    public static final ModConfigSpec.IntValue MAX_THUNDER_TICKS = BUILDER.defineInRange("maxThunderTicks", 15600, 20, 720000);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}

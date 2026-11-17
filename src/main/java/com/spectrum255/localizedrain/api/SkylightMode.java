package com.spectrum255.localizedrain.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.spectrum255.localizedrain.Localizedrain;
import net.minecraft.util.StringRepresentable;

/**
 * Dimension sky-light requirement shared by weather types and storm profiles.
 *
 * <p>It is combined with the {@code dimensions} selectors: the selectors decide <em>which</em>
 * dimensions are allowed, this enum decides whether the dimension must have sky light.</p>
 */
public enum SkylightMode implements StringRepresentable {
    ANY("any"),
    REQUIRED("required"),
    FORBIDDEN("forbidden");

    /** Tolerant codec: an unknown value falls back to {@link #ANY} instead of aborting world loading. */
    public static final Codec<SkylightMode> CODEC = Codec.STRING.comapFlatMap(
            value -> {
                for (SkylightMode mode : values()) {
                    if (mode.name.equalsIgnoreCase(value)) {
                        return DataResult.success(mode);
                    }
                }
                Localizedrain.LOGGER.warn("[localizedrain] unknown skylight mode '{}', using any", value);
                return DataResult.success(ANY);
            },
            SkylightMode::getSerializedName);

    private final String name;

    SkylightMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    /** Whether a dimension with the given sky-light flag satisfies this requirement. */
    public boolean allows(boolean hasSkyLight) {
        return switch (this) {
            case REQUIRED -> hasSkyLight;
            case FORBIDDEN -> !hasSkyLight;
            case ANY -> true;
        };
    }
}

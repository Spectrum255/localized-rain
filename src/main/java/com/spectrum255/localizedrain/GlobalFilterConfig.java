package com.spectrum255.localizedrain;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 全局天气过滤器默认值（COMMON）。
 *
 * <p>与 {@link FilterConfig} 相同的三个列表，但在主菜单/标题页面即可编辑；
 * 新建存档时复制成 {@code <世界>/serverconfig/localizedrain/filters.toml}。
 * 键名与默认值必须与 {@link FilterConfig} 保持一致。</p>
 */
public final class GlobalFilterConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static final ModConfigSpec SPEC;

    static {
        BUILDER.defineListAllowEmpty("weatherBiomeWhitelist", List.of(), () -> "",
                FilterConfig::validBiomeWeatherEntry);
        BUILDER.defineListAllowEmpty("weatherBiomeBlacklist", List.of(), () -> "",
                FilterConfig::validBiomeWeatherEntry);
        BUILDER.defineListAllowEmpty("weatherBiomeOverrides", List.of(), () -> "",
                FilterConfig::validBiomeOverride);

        SPEC = BUILDER.build();
    }

    private GlobalFilterConfig() {}
}

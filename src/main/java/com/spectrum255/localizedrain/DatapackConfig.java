package com.spectrum255.localizedrain;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code config/localizedrain/datapacks.toml}：控制是否加载配置目录里的数据包。
 *
 * <p>该目录本身总是会被创建，开关只决定是否把它们注册进数据包仓库；改动需要重启游戏。</p>
 */
public final class DatapackConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue LOAD_CONFIG_DATAPACKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment(
                "Loads datapacks from config/localizedrain/datapacks/.",
                "Each sub-directory is treated as a datapack: <name>/data/<namespace>/<path>...",
                "pack.mcmeta is optional. Changes require a game restart.")
                .push("datapacks");
        LOAD_CONFIG_DATAPACKS = builder
                .comment("Set to false to ignore the whole config/localizedrain/datapacks folder.")
                .define("loadConfigDatapacks", true);
        builder.pop();
        SPEC = builder.build();
    }

    private DatapackConfig() {
    }

    public static boolean enabled() {
        try {
            return LOAD_CONFIG_DATAPACKS.get();
        } catch (IllegalStateException exception) {
            // 配置还没加载（早期事件）：按默认值放行
            return true;
        }
    }
}

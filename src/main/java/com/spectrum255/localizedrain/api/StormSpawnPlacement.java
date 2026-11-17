package com.spectrum255.localizedrain.api;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.neoforged.neoforge.common.TranslatableEnum;

/** 自然风暴的生成位置策略（移动模式）。配置界面按枚举渲染成按钮。 */
public enum StormSpawnPlacement implements StringRepresentable, TranslatableEnum {
    /** 在随机玩家附近，按距离与上风向生成，遵守每个玩家上限。 */
    NEAR_PLAYER("near_player"),
    /** 在任意已加载区块随机生成，不检查每个玩家上限。 */
    ANYWHERE_LOADED("anywhere_loaded");

    private final String id;

    StormSpawnPlacement(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return this.id;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable("localizedrain.configuration.stormSpawnPlacement." + name());
    }

    /** 同步包与旧配置文件使用的字符串形式。 */
    public String id() {
        return this.id;
    }
}

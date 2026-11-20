package com.spectrum255.localizedrain;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/** Registers the lightweight preset pack only when Weather+ is absent. */
public final class BuiltinPresetDatapack {
    /**
     * 兼容的目标模组 id。Weather+ 自带同名天气类型与档案，两个包同时加载会重复注册，
     * 因此它存在时跳过内置预设包。
     */
    private static final String WEATHERPLUS_MOD_ID = "weatherplus";
    private static final ResourceLocation PACK_LOCATION = ResourceLocation.fromNamespaceAndPath(
            Localizedrain.MODID, "builtin_datapacks/weather_presets");

    private BuiltinPresetDatapack() {
    }

    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        if (ModList.get().isLoaded(WEATHERPLUS_MOD_ID)) {
            Localizedrain.LOGGER.info("Weather+ detected; built-in weather presets are disabled");
            return;
        }
        // isRequired=false：玩家可以在「数据包」界面禁用预设，也可以用自己的数据包覆盖同名条目；
        // BOTTOM：默认启用但优先级低于玩家/世界数据包，避免内置包悄悄压过整合包的自定义天气
        event.addPackFinders(PACK_LOCATION, PackType.SERVER_DATA,
                Component.literal("Localized Rain Weather Presets"),
                PackSource.BUILT_IN, false, Pack.Position.BOTTOM);
    }
}

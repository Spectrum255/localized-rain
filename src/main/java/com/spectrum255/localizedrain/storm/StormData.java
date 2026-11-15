package com.spectrum255.localizedrain.storm;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** 每个维度一份的风暴存档，与 localizedrain_weather 完全独立。 */
public final class StormData extends SavedData {
    public static final String FILE_ID = "localizedrain_storms";
    private static final int DATA_VERSION = 1;

    private final List<StormObject> storms = new ArrayList<>();
    private WindField wind = new WindField();
    private long nextSpawnTick;

    public static SavedData.Factory<StormData> factory() {
        return new SavedData.Factory<>(StormData::new, StormData::load);
    }

    public static StormData load(CompoundTag tag, HolderLookup.Provider registries) {
        StormData data = new StormData();
        int version = tag.contains("version") ? tag.getInt("version") : 1;
        if (version > DATA_VERSION) {
            // 新版本存档：字段含义可能已经变化，宁可丢弃也不要按旧格式误读
            com.spectrum255.localizedrain.Localizedrain.LOGGER.warn(
                    "[localizedrain] storm data version {} is newer than supported {}; ignoring saved storms",
                    version, DATA_VERSION);
            return data;
        }
        ListTag entries = tag.getList("storms", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            StormObject storm = StormObject.load(entries.getCompound(index));
            if (storm.profileId != null) {
                data.storms.add(storm);
            }
        }
        if (tag.contains("wind", Tag.TAG_COMPOUND)) {
            data.wind = WindField.load(tag.getCompound("wind"), 0L);
        }
        data.nextSpawnTick = tag.getLong("next_spawn");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (StormObject storm : this.storms) {
            entries.add(storm.save());
        }
        tag.put("storms", entries);
        tag.put("wind", this.wind.save());
        tag.putLong("next_spawn", this.nextSpawnTick);
        tag.putInt("version", DATA_VERSION);
        return tag;
    }

    List<StormObject> storms() {
        return this.storms;
    }

    WindField wind() {
        return this.wind;
    }

    long nextSpawnTick() {
        return this.nextSpawnTick;
    }

    void setNextSpawnTick(long tick) {
        this.nextSpawnTick = tick;
    }
}

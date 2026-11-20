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
    /** 存档版本比当前程序新时原样保留：旧版本回退一次自动保存不能把新存档覆盖成空列表。 */
    private CompoundTag preserved;

    public static SavedData.Factory<StormData> factory() {
        return new SavedData.Factory<>(StormData::new, StormData::load);
    }

    public static StormData load(CompoundTag tag, HolderLookup.Provider registries) {
        StormData data = new StormData();
        int version = tag.contains("version") ? tag.getInt("version") : 1;
        if (version > DATA_VERSION) {
            // 新版本存档：字段含义可能已经变化，宁可丢弃也不要按旧格式误读；
            // 但要把原始 NBT 留住，save 时原样写回，避免旧版本回退后把新存档覆盖成空列表
            com.spectrum255.localizedrain.Localizedrain.LOGGER.warn(
                    "[localizedrain] storm data version {} is newer than supported {}; "
                            + "ignoring saved storms in memory (file preserved on save)",
                    version, DATA_VERSION);
            data.preserved = tag.copy();
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
        if (this.preserved != null) {
            return this.preserved.copy();   // 新版本存档原样保留
        }
        ListTag entries = new ListTag();
        for (StormObject storm : this.storms) {
            if (storm.profileId == null) {
                continue;       // 档案 id 损坏的条目写不回去，直接跳过
            }
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

    /**
     * 存档版本比当前程序新时，本次会话的内存数据是空的、save 会原样写回旧 NBT。
     * 此时不应再生成新风暴：它们既不会落盘，也无法在下次启动时恢复。
     */
    public boolean readOnly() {
        return this.preserved != null;
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

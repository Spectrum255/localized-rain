package com.spectrum255.localizedrain.weather;

import com.spectrum255.localizedrain.Config;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 每个维度一份的天气存档。天气按「一片生物群系」保存（见 {@link BiomePatches}），
 * 同一个生物群系的不同区域各算各的天气。
 */
public final class WeatherData extends SavedData {
    public static final String FILE_ID = "localizedrain_weather";
    private static final int DATA_VERSION = 3;

    private final Map<String, WeatherState> weatherByPatch = new HashMap<>();

    public static SavedData.Factory<WeatherData> factory() {
        return new SavedData.Factory<>(WeatherData::new, WeatherData::load);
    }

    public static WeatherData load(CompoundTag tag, HolderLookup.Provider registries) {
        WeatherData data = new WeatherData();
        ListTag entries = tag.getList("patches", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            String patch = entry.getString("patch");
            if (patch.isEmpty()) {
                continue;
            }
            data.weatherByPatch.put(patch, WeatherState.load(entry));
        }
        return data;
    }

    /** 只读查询，没有记录返回 null。 */
    public WeatherState get(String patch) {
        return this.weatherByPatch.get(patch);
    }

    public WeatherState getOrCreate(String patch, RandomSource random) {
        WeatherState state = this.weatherByPatch.get(patch);
        if (state == null) {
            state = WeatherState.create(random);
            this.weatherByPatch.put(patch, state);
            this.setDirty();
        }
        return state;
    }

    public Set<String> patches() {
        return this.weatherByPatch.keySet();
    }

    public void setWeather(Collection<String> patches, WeatherType weather, int requestedDuration, RandomSource random) {
        boolean varyDuration = patches.size() > 1 && requestedDuration > 1;
        for (String patch : patches) {
            int duration = requestedDuration;
            if (varyDuration) {
                int variance = Math.max(1, requestedDuration / 10);
                duration = Math.max(1, requestedDuration + random.nextInt(variance * 2 + 1) - variance);
            }
            this.getOrCreate(patch, random).apply(weather, duration, random);
        }
        this.setDirty();
    }

    /** 只推进当前有玩家的群系范围，其余范围冻结，避免 tick 和存档开销无上限增长。 */
    public void tick(RandomSource random, boolean cycleWeather, Collection<String> patches) {
        boolean changed = false;
        for (String patch : patches) {
            changed |= this.getOrCreate(patch, random).tick(random, cycleWeather);
        }
        if (changed) {
            this.setDirty();
        }
    }

    /** 跳过夜晚时清空本维度所有范围的降雨与雷暴。 */
    public void clearAll(RandomSource random) {
        boolean changed = false;
        for (WeatherState state : this.weatherByPatch.values()) {
            changed |= state.clear(random);
        }
        if (changed) {
            this.setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", DATA_VERSION);
        ListTag entries = new ListTag();
        this.weatherByPatch.forEach((patch, state) -> {
            CompoundTag entry = state.save();
            entry.putString("patch", patch);
            entries.add(entry);
        });
        tag.put("patches", entries);
        return tag;
    }

    public static final class WeatherState {
        /** 没有记录的范围共用这个晴天状态，查询天气不会创建存档条目。 */
        private static final WeatherState CLEAR = new WeatherState();

        private int rainTime;
        private int thunderTime;
        private boolean raining;
        private boolean thundering;
        private float rainLevel;
        private float thunderLevel;

        private WeatherState() {}

        static WeatherState clearDefault() {
            return CLEAR;
        }

        private static WeatherState create(RandomSource random) {
            WeatherState state = new WeatherState();
            state.rainTime = range(random, Config.MIN_CLEAR_TICKS.get(), Config.MAX_CLEAR_TICKS.get());
            state.thunderTime = range(random, Config.MIN_THUNDER_DELAY_TICKS.get(), Config.MAX_THUNDER_DELAY_TICKS.get());
            return state;
        }

        private static WeatherState load(CompoundTag tag) {
            WeatherState state = new WeatherState();
            state.rainTime = tag.getInt("rain_time");
            state.thunderTime = tag.getInt("thunder_time");
            state.raining = tag.getBoolean("raining");
            state.thundering = tag.getBoolean("thundering");
            state.rainLevel = tag.getFloat("rain_level");
            state.thunderLevel = tag.getFloat("thunder_level");
            return state;
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("rain_time", this.rainTime);
            tag.putInt("thunder_time", this.thunderTime);
            tag.putBoolean("raining", this.raining);
            tag.putBoolean("thundering", this.thundering);
            tag.putFloat("rain_level", this.rainLevel);
            tag.putFloat("thunder_level", this.thunderLevel);
            return tag;
        }

        private boolean tick(RandomSource random, boolean cycleWeather) {
            boolean changed = false;
            if (cycleWeather) {
                if (--this.rainTime <= 0) {
                    this.raining = !this.raining;
                    this.rainTime = this.raining
                            ? range(random, Config.MIN_RAIN_TICKS.get(), Config.MAX_RAIN_TICKS.get())
                            : range(random, Config.MIN_CLEAR_TICKS.get(), Config.MAX_CLEAR_TICKS.get());
                    changed = true;
                }
                if (--this.thunderTime <= 0) {
                    this.thundering = !this.thundering;
                    this.thunderTime = this.thundering
                            ? range(random, Config.MIN_THUNDER_TICKS.get(), Config.MAX_THUNDER_TICKS.get())
                            : range(random, Config.MIN_THUNDER_DELAY_TICKS.get(), Config.MAX_THUNDER_DELAY_TICKS.get());
                    changed = true;
                }
            }
            float oldRain = this.rainLevel;
            float oldThunder = this.thunderLevel;
            this.rainLevel = Mth.clamp(this.rainLevel + (this.raining ? 0.01F : -0.01F), 0.0F, 1.0F);
            this.thunderLevel = Mth.clamp(this.thunderLevel + (this.thundering && this.raining ? 0.01F : -0.01F), 0.0F, 1.0F);
            return changed || oldRain != this.rainLevel || oldThunder != this.thunderLevel;
        }

        private void apply(WeatherType weather, int requestedDuration, RandomSource random) {
            switch (weather) {
                case CLEAR -> {
                    this.raining = false;
                    this.thundering = false;
                    this.rainTime = durationOrRandom(requestedDuration, random, Config.MIN_CLEAR_TICKS.get(), Config.MAX_CLEAR_TICKS.get());
                    this.thunderTime = range(random, Config.MIN_THUNDER_DELAY_TICKS.get(), Config.MAX_THUNDER_DELAY_TICKS.get());
                }
                case RAIN -> {
                    this.raining = true;
                    this.thundering = false;
                    this.rainTime = durationOrRandom(requestedDuration, random, Config.MIN_RAIN_TICKS.get(), Config.MAX_RAIN_TICKS.get());
                    this.thunderTime = range(random, Config.MIN_THUNDER_DELAY_TICKS.get(), Config.MAX_THUNDER_DELAY_TICKS.get());
                }
                case THUNDER -> {
                    this.raining = true;
                    this.thundering = true;
                    int duration = durationOrRandom(requestedDuration, random, Config.MIN_THUNDER_TICKS.get(), Config.MAX_THUNDER_TICKS.get());
                    this.rainTime = duration;
                    this.thunderTime = duration;
                }
            }
        }

        /** 关掉降雨与雷暴，让雨量/雷量在后续 tick 里自然淡出（与原版一致）。 */
        private boolean clear(RandomSource random) {
            boolean changed = this.raining || this.thundering;
            this.raining = false;
            this.thundering = false;
            this.rainTime = range(random, Config.MIN_CLEAR_TICKS.get(), Config.MAX_CLEAR_TICKS.get());
            this.thunderTime = range(random, Config.MIN_THUNDER_DELAY_TICKS.get(), Config.MAX_THUNDER_DELAY_TICKS.get());
            return changed;
        }

        public float rainLevel() {
            return this.rainLevel;
        }

        public float thunderLevel() {
            return this.thunderLevel;
        }

        public boolean isRaining() {
            return this.rainLevel > 0.2F;
        }

        public boolean isThundering() {
            return this.thunderLevel > 0.9F && this.isRaining();
        }

        private static int range(RandomSource random, int first, int second) {
            int low = Math.min(first, second);
            int high = Math.max(first, second);
            return low + random.nextInt(high - low + 1);
        }

        private static int durationOrRandom(int requested, RandomSource random, int minimum, int maximum) {
            return requested < 0 ? range(random, minimum, maximum) : Math.max(1, requested);
        }
    }

    public enum WeatherType {
        CLEAR,
        RAIN,
        THUNDER
    }
}

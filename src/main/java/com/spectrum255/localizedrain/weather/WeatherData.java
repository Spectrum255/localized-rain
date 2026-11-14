package com.spectrum255.localizedrain.weather;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.api.WeatherTypes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 每个维度一份的天气存档。天气按「一片生物群系」保存（见 {@link BiomePatches}），
 * 同一个生物群系的不同区域各算各的天气。
 */
public final class WeatherData extends SavedData {
    private static final Logger LOGGER = LoggerFactory.getLogger(WeatherData.class);
    public static final String FILE_ID = "localizedrain_weather";
    /** 0.4.11 起不再保存退化方格（{@code g:}）父表；旧存档里的该字段会被忽略。 */
    private static final int DATA_VERSION = 11;

    /** 当前存档 key 的格式前缀（3D 段 key）。 */
    private static final String PATCH_KEY_PREFIX = "p3:";

    private final Map<String, WeatherState> weatherByPatch = new HashMap<>();
    /**
     * 3D 片（{@code p3:}）的并查集父指针：段节点 -> parent。
     * 相邻列的同群系段在 Y 方向相接时合并，片 = 3D 连通分量。持久化保证跨重启 key 稳定。
     */
    private final Map<String, String> segmentParents = new HashMap<>();

    // 清理父表时复用的临时集合：父表可能十几万条，每轮重新分配会制造数 MB 垃圾。
    // 清理结束后若父表已明显缩小，会整体替换成小集合，释放上一轮留下的容量。
    private Set<String> referencedScratch = new HashSet<>();
    private Map<String, String> rootScratch = new HashMap<>();
    private List<String> removableScratch = new ArrayList<>();
    private Set<String> seenScratch = new HashSet<>();
    private List<String> pathScratch = new ArrayList<>();
    /** 父链超过上限只警告一次，避免损坏存档每 tick 刷屏。 */
    private boolean warnedChainGuard;

    public static SavedData.Factory<WeatherData> factory() {
        return new SavedData.Factory<>(WeatherData::new, WeatherData::load);
    }

    public static WeatherData load(CompoundTag tag, HolderLookup.Provider registries) {
        WeatherData data = new WeatherData();
        int version = tag.contains("version", Tag.TAG_INT) ? tag.getInt("version") : 0;
        if (version > DATA_VERSION) {
            LOGGER.warn("Weather save was written by a newer data version ({} > {}); unknown fields will be ignored",
                    version, DATA_VERSION);
        }
        // 只保留当前格式（p3: 段 key）的条目。旧版本的 p:/g:/层化 key 已经没有任何分量能解析，
        // 读进来只会变成永远不被推进的孤儿（连保留天数清理都删不到），因此直接丢弃。
        ListTag entries = tag.getList("patches", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            String patch = entry.getString("patch");
            if (patch.isEmpty() || !patch.startsWith(PATCH_KEY_PREFIX)) {
                continue;
            }
            data.weatherByPatch.put(patch, WeatherState.load(entry));
        }
        ListTag segments = tag.getList("segment_parents", Tag.TAG_COMPOUND);
        for (int index = 0; index < segments.size(); index++) {
            CompoundTag entry = segments.getCompound(index);
            String node = entry.getString("node");
            String parent = entry.getString("parent");
            if (!node.isEmpty() && !parent.isEmpty()) {
                data.segmentParents.put(node, parent);
            }
        }
        data.sanitizeParents();
        return data;
    }

    /** 3D 段并查集的父指针（可变，由 {@link BiomePatches} 维护并持久化）。 */
    Map<String, String> segmentParents() {
        return this.segmentParents;
    }

    /** 只读查询，没有记录返回 null。3D 段会沿并查集解析到所在分量的规范 key。 */
    public WeatherState get(String patch) {
        WeatherState state = this.weatherByPatch.get(patch);
        if (state != null) {
            return state;
        }
        String canonical = this.canonical(patch);
        return canonical.equals(patch) ? null : this.weatherByPatch.get(canonical);
    }

    public WeatherState getOrCreate(String patch, RandomSource random) {
        String canonical = this.canonical(patch);
        WeatherState state = this.weatherByPatch.get(canonical);
        if (state != null) {
            return state;
        }
        state = WeatherState.create(random);
        this.weatherByPatch.put(canonical, state);
        this.setDirty();
        return state;
    }

    /**
     * 两个段分量合并时，把被吸收方的天气状态迁移到新的规范 key。
     * 双方都有状态时保留规范 key 那份（结果确定，便于跨重启复现）。
     */
    void transferState(String from, String to) {
        if (from.equals(to)) {
            return;
        }
        WeatherState moved = this.weatherByPatch.remove(from);
        if (moved == null) {
            return;
        }
        this.weatherByPatch.putIfAbsent(to, moved);
        this.setDirty();
    }

    /** 把 {@code p3:} key 解析成所在分量的规范 key；其它 key 原样返回。 */
    private String canonical(String patch) {
        if (patch == null) {
            return null;
        }
        String segmentRoot = this.canonicalSegment(patch);
        return segmentRoot == null ? patch : segmentRoot;
    }

    /** 沿 3D 段并查集解析到规范 key；不是段 key 或没有记录时返回 null。 */
    private String canonicalSegment(String patch) {
        if (!patch.startsWith("p3:") || this.segmentParents.isEmpty()) {
            return null;
        }
        String parent = this.segmentParents.get(patch);
        if (parent == null) {
            return null;
        }
        String root = patch;
        int guard = 0;
        int limit = this.segmentParents.size() + 1;
        String next = this.segmentParents.get(root);
        while (next != null && !next.equals(root)) {
            if (guard++ >= limit) {
                // 父指针成环（超长合法链不受限制）：把该节点隔离成自映射根，
                // 这样下一次解析只需一步，不会每次都重走整条坏链。
                this.segmentParents.put(patch, patch);
                this.setDirty();
                if (!this.warnedChainGuard) {
                    this.warnedChainGuard = true;
                    LOGGER.warn("Segment parent chain did not terminate within {} steps while resolving {}; "
                            + "isolating the node. The parent map may be corrupted",
                            limit, patch);
                }
                return patch;
            }
            root = next;
            next = this.segmentParents.get(root);
        }
        return root;
    }

    public Set<String> patches() {
        return this.weatherByPatch.keySet();
    }

    /** 按注册表里的天气类型设置一批群系片；{@code typeId} 决定类型，标志位由类型派生。 */
    public void setWeather(Collection<String> patches, String typeId, boolean raining, boolean thundering,
                           int requestedDuration, RandomSource random) {
        boolean varyDuration = patches.size() > 1 && requestedDuration > 1;
        for (String patch : patches) {
            int duration = requestedDuration;
            if (varyDuration) {
                int variance = Math.max(1, requestedDuration / 10);
                duration = Math.max(1, requestedDuration + random.nextInt(variance * 2 + 1) - variance);
            }
            this.getOrCreate(patch, random).apply(typeId, raining, thundering, duration, random);
        }
        this.setDirty();
    }

    /**
     * 强制某片保持指定天气：布尔值立即切换，雨量/雷量仍按原版速度（0.01/tick）渐变。
     * 强制期间冻结计时器；移除强制规则后从一份新的随机时长继续随机循环。
     * 每 tick 在 {@link #tick} 之后调用一次即可。
     */
    public void forceWeather(String patch, ResourceLocation typeId,
                             com.spectrum255.localizedrain.api.WeatherType type, RandomSource random) {
        if (this.getOrCreate(patch, random).force(typeId, type, random)) {
            this.setDirty();
        }
    }

    /** 强制某片放晴（用于把被强制晴天/被排除的片残留的雨量淡出）。 */
    public void forceClear(String patch, RandomSource random) {
        WeatherState state = this.weatherByPatch.get(patch);
        if (state != null && state.forceClear(random)) {
            this.setDirty();
        }
    }

    /**
     * 推进给定的一组群系片。调用方决定推进哪些范围（当前是「已加载区块 + 玩家所在片」），
     * 未加载区域的片不会被传入，因此保持冻结、状态保留。
     *
     * @param gameTime 当前游戏时间，记录到每片的 {@code lastTick}，供存档清理判断新旧
     */
    public void tick(RandomSource random, boolean cycleWeather, Collection<String> patches, long gameTime,
                     Map<String, List<WeatherBiomeFilter.Candidate>> candidates) {
        boolean changed = false;
        for (String patch : patches) {
            WeatherState state = this.getOrCreate(patch, random);
            state.lastTick = gameTime;
            changed |= state.tick(random, cycleWeather, candidates.get(patch));
        }
        if (changed) {
            this.setDirty();
        }
    }

    /**
     * 与上面相同，但每片一次推进 {@code steps} 个 tick（扫描间隔大于 1 时使用）。
     * 计时器在 {@code steps} 内不翻转时走闭式快路径，避免每轮都做 {@code steps} 次完整逻辑。
     */
    public void tick(RandomSource random, boolean cycleWeather, Collection<String> patches, long gameTime,
                     Map<String, List<WeatherBiomeFilter.Candidate>> candidates, int steps) {
        if (steps <= 0 || patches.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String patch : patches) {
            WeatherState state = this.getOrCreate(patch, random);
            state.lastTick = gameTime;
            changed |= state.tick(random, cycleWeather, candidates.get(patch), steps);
        }
        if (changed) {
            this.setDirty();
        }
    }

    /**
     * 存档清理：
     * <ul>
     *   <li>{@code retentionTicks > 0} 时删除「超过 retentionTicks 没有被推进」的片；</li>
     *   <li>超过 {@code maxEntries} 时按 {@code lastTick} 从旧到新删除，直到回到上限。</li>
     * </ul>
     * 从未被推进过（{@code lastTick == 0}）的旧存档条目只受条数上限约束，不会被时间清理误删。
     */
    public void prune(long gameTime, int maxEntries, long retentionTicks,
                      int maxParentEntries, java.util.function.Predicate<String> columnStillCached,
                      java.util.function.Predicate<String> evictColumn) {
        int before = this.weatherByPatch.size();
        if (retentionTicks > 0L) {
            this.weatherByPatch.entrySet().removeIf(entry -> {
                long lastTick = entry.getValue().lastTick;
                return lastTick > 0L && gameTime - lastTick > retentionTicks;
            });
        }
        if (this.weatherByPatch.size() > maxEntries) {
            // 超限时优先淘汰「列已不在缓存」的片（多半在未加载区域，重新扫描会重建晴天周期），
            // 让仍加载、每轮都会被推进的片尽量留在存档里。
            List<String> keys = new ArrayList<>(this.weatherByPatch.keySet());
            keys.sort(Comparator
                    .comparingInt((String key) -> columnStillCached.test(key) ? 1 : 0)
                    .thenComparingLong(key -> this.weatherByPatch.get(key).lastTick));
            int excess = this.weatherByPatch.size() - maxEntries;
            for (int index = 0; index < excess; index++) {
                this.weatherByPatch.remove(keys.get(index));
            }
        }
        boolean parentsChanged = this.pruneParents(maxParentEntries, columnStillCached, evictColumn);
        if (this.weatherByPatch.size() != before || parentsChanged) {
            this.setDirty();
        }
    }

    /**
     * 清理并查集父表：先删损坏链，再删「列已淘汰且分量根没有天气状态」的节点；
     * 仍然超过硬上限时，从「未被引用、没有状态」的节点开始收缩，并把它们所在的列逐出缓存，
     * 让这些列在下一次扫描时重新注册/合并。硬上限保证父表不会随探索无限增长。
     */
    private boolean pruneParents(int maxParentEntries,
                                 java.util.function.Predicate<String> columnStillCached,
                                 java.util.function.Predicate<String> evictColumn) {
        int limit = Math.max(4096, maxParentEntries);
        if (this.segmentParents.size() <= limit) {
            return false;
        }
        Map<String, String> roots = this.rootScratch;
        List<String> removable = this.removableScratch;
        Set<String> referenced = this.referencedScratch;
        roots.clear();
        removable.clear();
        referenced.clear();
        boolean changed = false;

        // 第一遍：成环/悬空损坏链整链删除。损坏节点即使被别的条目引用也要删，
        // 否则「被引用就保留」会让环永远清不掉。
        for (String node : this.segmentParents.keySet()) {
            if (this.resolveRootMemo(node, roots) == null) {
                removable.add(node);
            }
        }
        changed |= this.removeNodes(removable, evictColumn);
        roots.clear();

        // 第二遍：重建「被引用」集合，被别的条目当作父节点的节点一律保留。
        // 否则仍缓存、不会重新注册的列会指向悬空父项，canonical 在中间节点停住，天气被读成晴天。
        this.rebuildReferenced(referenced);
        for (Map.Entry<String, String> entry : this.segmentParents.entrySet()) {
            String node = entry.getKey();
            if (referenced.contains(node)) {
                continue;
            }
            if (node.equals(entry.getValue())) {
                // 自映射根：没有状态、没有子条目指向、列已淘汰才删
                if (!this.weatherByPatch.containsKey(node) && !columnStillCached.test(node)) {
                    removable.add(node);
                }
                continue;
            }
            if (columnStillCached.test(node)) {
                continue;
            }
            String root = this.resolveRootMemo(node, roots);
            if (root == null || !this.weatherByPatch.containsKey(root)) {
                removable.add(node);
            }
        }
        changed |= this.removeNodes(removable, evictColumn);
        roots.clear();

        // 第三遍：仍超过硬上限时收缩。只删「没有子条目、没有天气状态」的节点，并逐出它们所在的列——
        // 列缓存命中时这些节点不会重新注册，正是父表被撑大的原因。
        int passes = 0;
        while (this.segmentParents.size() > limit && passes++ < 4) {
            this.rebuildReferenced(referenced);
            for (Map.Entry<String, String> entry : this.segmentParents.entrySet()) {
                String node = entry.getKey();
                if (referenced.contains(node) || this.weatherByPatch.containsKey(node)) {
                    continue;
                }
                removable.add(node);
            }
            if (removable.isEmpty()) {
                break;
            }
            changed |= this.removeNodes(removable, evictColumn);
        }
        if (this.segmentParents.size() > limit) {
            if (!this.warnedChainGuard) {
                this.warnedChainGuard = true;
                LOGGER.warn("Segment parent map is still above its hard limit ({} > {}) after pruning; "
                        + "keeping the remaining structure to avoid dropping live weather states",
                        this.segmentParents.size(), limit);
            }
        }

        // 本轮结束：清空引用并释放临时集合的容量（父表已缩到阈值一半以下时）
        roots.clear();
        referenced.clear();
        removable.clear();
        this.seenScratch.clear();
        this.pathScratch.clear();
        if (this.segmentParents.size() <= limit / 2) {
            this.referencedScratch = new HashSet<>();
            this.rootScratch = new HashMap<>();
            this.removableScratch = new ArrayList<>();
            this.seenScratch = new HashSet<>();
            this.pathScratch = new ArrayList<>();
        }
        return changed;
    }

    /** 从父表删除给定节点，并把它们所在的列逐出列缓存（让下一次扫描重新注册）。 */
    private boolean removeNodes(List<String> nodes, java.util.function.Predicate<String> evictColumn) {
        boolean changed = false;
        for (String node : nodes) {
            if (this.segmentParents.remove(node) != null) {
                changed = true;
                evictColumn.test(node);
            }
        }
        nodes.clear();
        return changed;
    }

    /** 重建「被别的条目当作父节点」的节点集合。 */
    private void rebuildReferenced(Set<String> referenced) {
        referenced.clear();
        for (Map.Entry<String, String> entry : this.segmentParents.entrySet()) {
            if (!entry.getKey().equals(entry.getValue())) {
                referenced.add(entry.getValue());
            }
        }
    }

    /**
     * 沿父表解析到根，并在一次清理/读档范围内记忆化 + 路径压缩。
     * 父项缺失时返回当前节点（悬空），由调用方结合「根是否有状态」决定去留；
     * 成环时返回 {@code null}（损坏），调用方据此整链丢弃，避免把中间节点误当成根、
     * 反而把环「修复」成合法结构。环检测依赖 seen 集合，不再使用固定长度上限。
     */
    private String resolveRootMemo(String node, Map<String, String> memo) {
        String known = memo.get(node);
        if (known != null) {
            return known.isEmpty() ? null : known;
        }
        Set<String> seen = this.seenScratch;
        seen.clear();
        List<String> path = this.pathScratch;
        path.clear();
        String current = node;
        String root = null;
        while (true) {
            if (!seen.add(current)) {
                // 成环：seen 保证最多访问父表大小的不同节点，无需再做固定长度限制
                break;
            }
            String parent = this.segmentParents.get(current);
            if (parent == null || parent.equals(current)) {
                // 父项缺失（悬空）时以当前节点为临时根；自映射是正常根。
                root = current;
                break;
            }
            path.add(current);
            current = parent;
        }
        if (root == null) {
            for (String visited : path) {
                memo.put(visited, "");
            }
            memo.put(node, "");
            return null;
        }
        for (String visited : path) {
            memo.put(visited, root);
            this.segmentParents.put(visited, root);
        }
        memo.put(root, root);
        return root;
    }

    /**
     * 读档时校验段父表：丢弃成环、超长或悬空的条目。
     * 读档时列缓存是空的，后续扫描会重新 register/merge 重建链接，丢弃是安全的。
     */
    private void sanitizeParents() {
        if (this.segmentParents.isEmpty()) {
            return;
        }
        Map<String, String> roots = new HashMap<>();
        List<String> broken = new ArrayList<>();
        for (String node : new ArrayList<>(this.segmentParents.keySet())) {
            String root = this.resolveRootMemo(node, roots);
            String rootParent = root == null ? null : this.segmentParents.get(root);
            if (rootParent == null || !rootParent.equals(root)) {
                broken.add(node);
            }
        }
        for (String node : broken) {
            this.segmentParents.remove(node);
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
        ListTag segments = new ListTag();
        this.segmentParents.forEach((node, parent) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("node", node);
            entry.putString("parent", parent);
            segments.add(entry);
        });
        tag.put("segment_parents", segments);
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
        /** 该片当前天气类型（注册表 id）；旧存档没有该字段时按 raining/thundering 推导。 */
        private ResourceLocation type = WeatherTypes.CLEAR_ID;
        /** 最近一次被推进的游戏时间；0 = 旧存档未记录（只受条数上限约束）。 */
        private long lastTick;
        /** 计时器是否被冻结（强制天气/名单排除期间）；解除冻结时重新随机计时，避免沿用旧计时器立刻翻转。 */
        private boolean frozen;
        /** 是否被强制天气锁定；锁定期间睡觉清空天气不会生效，解除后自动恢复随机循环。 */
        private boolean forced;
        /** 当前类型是否会真的降水（决定 rainLevel 目标）；非降水自定义类型为 false。 */
        private boolean precipitating;
        /** 当前类型是否允许雷暴；候选天气里非雷暴类型为 false。 */
        private boolean thunderAllowed;

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
            state.lastTick = tag.getLong("last_tick");
            state.frozen = tag.getBoolean("frozen");
            state.forced = tag.getBoolean("forced");
            state.precipitating = tag.getBoolean("precipitating");
            state.thunderAllowed = tag.getBoolean("thunder_allowed");
            String rawType = tag.getString("weather_type");
            state.type = rawType.isEmpty() ? state.builtinType() : parseType(rawType);
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
            tag.putLong("last_tick", this.lastTick);
            tag.putBoolean("frozen", this.frozen);
            tag.putBoolean("forced", this.forced);
            tag.putBoolean("precipitating", this.precipitating);
            tag.putBoolean("thunder_allowed", this.thunderAllowed);
            tag.putString("weather_type", this.type.toString());
            return tag;
        }

        private boolean tick(RandomSource random, boolean cycleWeather, List<WeatherBiomeFilter.Candidate> candidates) {
            boolean changed = false;
            boolean flagsChanged = false;
            boolean candidateMode = candidates != null && !candidates.isEmpty();
            if (cycleWeather) {
                if (this.frozen) {
                    // 强制天气/名单排除解除：从一份新的随机时长继续，避免沿用冻结前的旧计时器立刻翻转
                    this.frozen = false;
                    this.forced = false;
                    this.refreshTimers(random);
                    changed = true;
                }
                if (--this.rainTime <= 0) {
                    if (candidateMode) {
                        if (this.raining) {
                            // 天气阶段结束：回到晴天
                            this.raining = false;
                            this.thundering = false;
                            this.precipitating = false;
                            this.thunderAllowed = false;
                            this.type = WeatherTypes.CLEAR_ID;
                            this.rainTime = range(random, Config.MIN_CLEAR_TICKS.get(), Config.MAX_CLEAR_TICKS.get());
                        } else {
                            // 从候选集中随机抽取一种天气
                            WeatherBiomeFilter.Candidate chosen = candidates.get(random.nextInt(candidates.size()));
                            this.type = chosen.id();
                            this.raining = true;
                            this.precipitating = chosen.precipitation();
                            this.thunderAllowed = chosen.thunder();
                            this.thundering = false;
                            this.rainTime = range(random, Config.MIN_RAIN_TICKS.get(), Config.MAX_RAIN_TICKS.get());
                            this.thunderTime = range(random, Config.MIN_THUNDER_DELAY_TICKS.get(),
                                    Config.MAX_THUNDER_DELAY_TICKS.get());
                        }
                    } else if (WeatherTypes.isBuiltin(this.type)) {
                        this.raining = !this.raining;
                    } else {
                        // 自定义天气只持续它自己的计时器；到期回到晴天，而不是被内置循环切换成雨
                        this.raining = false;
                        this.thundering = false;
                        this.thunderTime = range(random, Config.MIN_THUNDER_DELAY_TICKS.get(),
                                Config.MAX_THUNDER_DELAY_TICKS.get());
                    }
                    if (!candidateMode) {
                        this.rainTime = this.raining
                                ? range(random, Config.MIN_RAIN_TICKS.get(), Config.MAX_RAIN_TICKS.get())
                                : range(random, Config.MIN_CLEAR_TICKS.get(), Config.MAX_CLEAR_TICKS.get());
                    }
                    changed = true;
                    flagsChanged = true;
                }
                if (--this.thunderTime <= 0) {
                    if (candidateMode) {
                        // 候选模式：只有当前类型允许雷时才会打雷
                        this.thundering = this.raining && this.thunderAllowed && !this.thundering;
                        this.thunderTime = this.thundering
                                ? range(random, Config.MIN_THUNDER_TICKS.get(), Config.MAX_THUNDER_TICKS.get())
                                : range(random, Config.MIN_THUNDER_DELAY_TICKS.get(), Config.MAX_THUNDER_DELAY_TICKS.get());
                    } else {
                        this.thundering = !this.thundering;
                        this.thunderTime = this.thundering
                                ? range(random, Config.MIN_THUNDER_TICKS.get(), Config.MAX_THUNDER_TICKS.get())
                                : range(random, Config.MIN_THUNDER_DELAY_TICKS.get(), Config.MAX_THUNDER_DELAY_TICKS.get());
                    }
                    changed = true;
                    flagsChanged = true;
                }
            }
            if (candidateMode) {
                // 候选模式的类型由抽取决定，不在这里重置；阶段结束时已显式设回晴天
            } else {
                if (flagsChanged) {
                    // 随机循环只在晴天/下雨/雷暴之间切换；自定义类型到期后这里会把类型重置回内置循环
                    this.type = this.builtinType();
                }
                this.precipitating = this.raining;
                this.thunderAllowed = true;
            }
            float oldRain = this.rainLevel;
            float oldThunder = this.thunderLevel;
            this.rainLevel = Mth.clamp(this.rainLevel
                    + (this.raining && this.precipitating ? 0.01F : -0.01F), 0.0F, 1.0F);
            this.thunderLevel = Mth.clamp(this.thunderLevel
                    + (this.thundering && this.raining && this.precipitating ? 0.01F : -0.01F), 0.0F, 1.0F);
            return changed || oldRain != this.rainLevel || oldThunder != this.thunderLevel;
        }

        /**
         * 一次推进多个 tick：计时器在未来 {@code steps} 内不会翻转时用闭式推进，
         * 避免每轮扫描都对每个片做 {@code steps} 次完整逻辑；会翻转时退回逐 tick 循环。
         */
        private boolean tick(RandomSource random, boolean cycleWeather,
                             List<WeatherBiomeFilter.Candidate> candidates, int steps) {
            if (steps <= 1) {
                boolean changed = tick(random, cycleWeather, candidates);
                // 计时器每轮都会推进，必须置脏，否则卸载后进度会回退
                return changed || cycleWeather;
            }
            boolean candidateMode = candidates != null && !candidates.isEmpty();
            boolean fastForward = !this.frozen
                    && (!cycleWeather || (this.rainTime > steps && this.thunderTime > steps));
            if (fastForward) {
                if (cycleWeather) {
                    this.rainTime -= steps;
                    this.thunderTime -= steps;
                }
                if (!candidateMode) {
                    this.precipitating = this.raining;
                    this.thunderAllowed = true;
                }
                float delta = 0.01F * steps;
                float oldRain = this.rainLevel;
                float oldThunder = this.thunderLevel;
                this.rainLevel = Mth.clamp(this.rainLevel
                        + (this.raining && this.precipitating ? delta : -delta), 0.0F, 1.0F);
                this.thunderLevel = Mth.clamp(this.thunderLevel
                        + (this.thundering && this.raining && this.precipitating ? delta : -delta), 0.0F, 1.0F);
                // 计时器随本轮推进，必须置脏，否则卸载后进度会回退（prune 也会误判为过期）
                return cycleWeather || oldRain != this.rainLevel || oldThunder != this.thunderLevel;
            }
            boolean changed = false;
            for (int step = 0; step < steps; step++) {
                changed |= tick(random, cycleWeather, candidates);
            }
            return changed || cycleWeather;
        }

        private void apply(String typeId, boolean raining, boolean thundering, int requestedDuration, RandomSource random) {
            this.type = parseType(typeId);
            // 手动/API 设置会接管计时器；解除此前的强制/排除冻结状态
            this.frozen = false;
            this.forced = false;
            this.raining = raining;
            this.thundering = thundering && raining;
            this.precipitating = this.raining;
            this.thunderAllowed = this.thundering;
            if (!this.raining) {
                this.rainTime = durationOrRandom(requestedDuration, random, Config.MIN_CLEAR_TICKS.get(), Config.MAX_CLEAR_TICKS.get());
                this.thunderTime = range(random, Config.MIN_THUNDER_DELAY_TICKS.get(), Config.MAX_THUNDER_DELAY_TICKS.get());
            } else if (this.thundering) {
                int duration = durationOrRandom(requestedDuration, random, Config.MIN_THUNDER_TICKS.get(), Config.MAX_THUNDER_TICKS.get());
                this.rainTime = duration;
                this.thunderTime = duration;
            } else {
                this.rainTime = durationOrRandom(requestedDuration, random, Config.MIN_RAIN_TICKS.get(), Config.MAX_RAIN_TICKS.get());
                this.thunderTime = range(random, Config.MIN_THUNDER_DELAY_TICKS.get(), Config.MAX_THUNDER_DELAY_TICKS.get());
            }
        }

        /**
         * 把状态锁到指定天气类型；首次进入强制状态时把雨/雷量直接拉到目标值（瞬时生效），
         * 之后冻结计时器，解除强制后不会沿用旧计时器立刻翻转。
         */
        boolean force(ResourceLocation typeId, com.spectrum255.localizedrain.api.WeatherType type, RandomSource random) {
            boolean raining = type.hasPrecipitation();
            boolean thundering = raining && type.allowsThunder();
            boolean first = !this.forced;
            boolean typeChanged = !this.type.equals(typeId);
            boolean changed = first || typeChanged || this.raining != raining || this.thundering != thundering;
            this.type = typeId;
            this.raining = raining;
            this.thundering = thundering;
            this.precipitating = raining;
            this.thunderAllowed = thundering;
            this.forced = true;
            if (!this.frozen) {
                this.frozen = true;
                this.refreshTimers(random);
            }
            if (first || typeChanged) {
                // 强制天气不渐变：直接给到目标强度
                this.rainLevel = raining ? 1.0F : 0.0F;
                this.thunderLevel = thundering ? 1.0F : 0.0F;
                changed = true;
            }
            return changed;
        }

        /** 强制放晴：只关目标布尔，让雨量/雷量自然淡出；计时器同样冻结并在解除时重新随机。 */
        boolean forceClear(RandomSource random) {
            boolean changed = this.forced || !this.frozen || this.raining || this.thundering
                    || !WeatherTypes.CLEAR_ID.equals(this.type);
            this.forced = false;
            this.raining = false;
            this.thundering = false;
            this.precipitating = false;
            this.thunderAllowed = false;
            this.type = WeatherTypes.CLEAR_ID;
            if (!this.frozen) {
                this.frozen = true;
                this.refreshTimers(random);
            }
            return changed;
        }

        /** 按当前标志位重新随机雨/雷计时器。 */
        private void refreshTimers(RandomSource random) {
            if (!this.raining) {
                this.rainTime = range(random, Config.MIN_CLEAR_TICKS.get(), Config.MAX_CLEAR_TICKS.get());
            } else if (this.thundering) {
                this.rainTime = range(random, Config.MIN_THUNDER_TICKS.get(), Config.MAX_THUNDER_TICKS.get());
            } else {
                this.rainTime = range(random, Config.MIN_RAIN_TICKS.get(), Config.MAX_RAIN_TICKS.get());
            }
            this.thunderTime = this.thundering
                    ? range(random, Config.MIN_THUNDER_TICKS.get(), Config.MAX_THUNDER_TICKS.get())
                    : range(random, Config.MIN_THUNDER_DELAY_TICKS.get(), Config.MAX_THUNDER_DELAY_TICKS.get());
        }

        /** 关掉降雨与雷暴，让雨量/雷量在后续 tick 里自然淡出（与原版一致）；强制天气不受影响。 */
        private boolean clear(RandomSource random) {
            if (this.forced) {
                return false;
            }
            boolean changed = this.raining || this.thundering;
            this.raining = false;
            this.thundering = false;
            this.precipitating = false;
            this.thunderAllowed = false;
            this.type = WeatherTypes.CLEAR_ID;
            this.rainTime = range(random, Config.MIN_CLEAR_TICKS.get(), Config.MAX_CLEAR_TICKS.get());
            this.thunderTime = range(random, Config.MIN_THUNDER_DELAY_TICKS.get(), Config.MAX_THUNDER_DELAY_TICKS.get());
            return changed;
        }

        /** 当前天气类型（注册表 id）。 */
        public ResourceLocation typeId() {
            return this.type;
        }

        /** 旧存档没有类型字段时，按布尔推导内置类型。 */
        private ResourceLocation builtinType() {
            if (this.thundering) {
                return WeatherTypes.THUNDER_ID;
            }
            if (this.raining) {
                return WeatherTypes.RAIN_ID;
            }
            return WeatherTypes.CLEAR_ID;
        }

        private static ResourceLocation parseType(String raw) {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            return id == null ? WeatherTypes.CLEAR_ID : id;
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

        private static int range(RandomSource random, int min, int max) {
            return min >= max ? min : min + random.nextInt(max - min + 1);
        }

        private static int durationOrRandom(int requestedDuration, RandomSource random, int min, int max) {
            return requestedDuration < 0 ? range(random, min, max) : Math.max(1, requestedDuration);
        }
    }
}

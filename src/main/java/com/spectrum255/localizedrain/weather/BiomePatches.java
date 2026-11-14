package com.spectrum255.localizedrain.weather;

import com.mojang.logging.LogUtils;
import com.spectrum255.localizedrain.Config;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

/**
 * 3D 群系片：竖直列 → 群系段 → 连通分量。
 *
 * <p>每个 16×16 方块的水平列按 section（16 方块）采样出一条竖直的群系段列表
 * {@code [y0, y1, biome]}；相邻列的同群系段只要 Y 区间相接就视为连通，
 * 因此洞穴群系、浮岛群系、下界的立体群系都能得到正确的连通区域，不再依赖
 * “水平轮廓 + |biome 子状态”的近似模型。</p>
 *
 * <p>连通分量用并查集维护，节点 id 是 {@code p3:<colX>,<colZ>,<segStartY>}；
 * 父指针持久化在 {@link WeatherData#segmentParents()} 中，保证跨重启 key 稳定。
 * 合并时规范 key 取列/段坐标字典序最小者，并把天气状态迁移过去。</p>
 *
 * <p>扫描有每 tick 预算（列数 + 耗时），优先保证玩家所在列；已扫描的列有缓存，
 * 缓存按访问顺序做 LRU 淘汰（上限 {@link #MAX_COLUMN_CACHE}）。</p>
 */
public final class BiomePatches {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation UNKNOWN = ResourceLocation.fromNamespaceAndPath("localizedrain", "unknown");
    private static final int SECTION_BLOCKS = 16;
    /** 无法从列段解析群系时的兜底采样高度（主世界地表附近）。 */
    private static final int SAMPLE_BLOCK_Y = 128;
    private static final int MAX_COLUMN_CACHE = 200_000;
    private static final int MAX_PATCH_BIOMES = 200_000;

    /** 父链损坏警告只打一次，避免每 tick 刷屏。 */
    private static boolean warnedChainGuard;
    private static final int DEFAULT_COLUMN_SCANS_PER_TICK = 4;
    private static final long COLUMN_BUDGET_NANOS = 2_000_000L;
    /** 兼容旧代码：子状态分隔符（3D 模型不再使用，仅用于解析旧 key）。 */
    public static final char SUB_STATE_SEPARATOR = '|';
    private static final String PATCH_PREFIX = "p3:";

    private static final Map<ServerLevel, Map<Long, Column>> COLUMNS = new WeakHashMap<>();
    private static final Map<ServerLevel, TickBudget> BUDGETS = new WeakHashMap<>();
    /** 片 key -> 群系，供黑白名单/强制天气匹配，避免反复扫描列。 */
    private static final Map<ServerLevel, Map<String, ResourceLocation>> PATCH_BIOMES = new WeakHashMap<>();

    private BiomePatches() {}

    /** 重置该维度本 tick 的列扫描预算；由 {@link WeatherManager#tick(ServerLevel)} 调用。 */
    public static void beginTick(ServerLevel level) {
        TickBudget budget = BUDGETS.computeIfAbsent(level, ignored -> new TickBudget());
        budget.scanLimit = maxColumnScansPerTick();
        budget.nanoLimit = COLUMN_BUDGET_NANOS;
        budget.scans = 0;
        budget.nanos = 0L;
    }

    /**
     * 为一次扫描周期重置列扫描预算：结算间隔是 {@code ticks} 个 tick，
     * 因此预算按间隔放大，否则扫描间隔越长、冷启动补列越慢（默认 20 tick 时每轮可补 80 列）。
     */
    public static void beginSweep(ServerLevel level, int ticks) {
        TickBudget budget = BUDGETS.computeIfAbsent(level, ignored -> new TickBudget());
        int span = Math.max(1, ticks);
        budget.scanLimit = maxColumnScansPerTick() * span;
        budget.nanoLimit = Math.min(COLUMN_BUDGET_NANOS * span, 8_000_000L);
        budget.scans = 0;
        budget.nanos = 0L;
    }

    /** 取该位置所在的 3D 群系片；会扫描所在列（无预算限制）。 */
    public static String patchAt(ServerLevel level, BlockPos pos) {
        return resolve(level, pos, false);
    }

    /** 与 {@link #patchAt} 相同，但受每 tick 列扫描预算限制；预算用尽返回 null。 */
    public static String patchAtBudgeted(ServerLevel level, BlockPos pos) {
        return resolve(level, pos, true);
    }

    /** 只查缓存，不扫描列；没有该列或该高度没有段时返回 null。 */
    public static String cachedPatchAt(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return null;
        }
        Column column = cachedColumn(level, pos.getX() >> 4, pos.getZ() >> 4);
        if (column == null) {
            return null;
        }
        Segment segment = column.segmentAt(pos.getY());
        if (segment == null) {
            return null;
        }
        return canonical(level, nodeId(column.x(), column.z(), segment.startY()));
    }

    /**
     * 按 quart 采样格取片：参数是 quart 坐标（4 方块），内部换算到 16×16 列，
     * 用该列的采样高度（主世界 y=128 / 无天光维度中部）解析所在段。
     */
    public static String patchAtQuart(ServerLevel level, int quartX, int quartZ) {
        return resolveColumn(level, Math.floorDiv(quartX, 4), Math.floorDiv(quartZ, 4), sampleBlockY(level), false);
    }

    /** 与 {@link #patchAtQuart} 相同，但一次显式操作最多触发 {@code budget} 次列扫描（缓存命中不计）。 */
    public static String patchAtQuart(ServerLevel level, int quartX, int quartZ, FillBudget budget) {
        int columnX = Math.floorDiv(quartX, 4);
        int columnZ = Math.floorDiv(quartZ, 4);
        if (cachedColumn(level, columnX, columnZ) == null && !budget.consume()) {
            return null;
        }
        return resolveColumn(level, columnX, columnZ, sampleBlockY(level), false);
    }

    /**
     * 旧版 API（0.3.x 语义）：参数是 16×16 列/区块坐标，不是 quart 坐标。
     * 0.4.0 内部改按 quart 采样后，这里显式换算，避免附属模组沿用区块坐标时得到放大的片。
     */
    public static String patchAtCell(ServerLevel level, int cellX, int cellZ) {
        return patchAtQuart(level, cellX << 2, cellZ << 2);
    }

    /** 旧版 API 的带预算版本，参数同样按 16×16 列坐标解释。 */
    public static String patchAtCell(ServerLevel level, int cellX, int cellZ, FillBudget budget) {
        return patchAtQuart(level, cellX << 2, cellZ << 2, budget);
    }

    /** 片对应的群系：{@code p3:} 从段/缓存取，旧 {@code p:/g:/|biome} key 回退采样。 */
    public static ResourceLocation patchBiome(ServerLevel level, String patch) {
        if (patch == null) {
            return null;
        }
        int separator = patch.indexOf(SUB_STATE_SEPARATOR);
        if (separator >= 0) {
            ResourceLocation suffix = ResourceLocation.tryParse(patch.substring(separator + 1));
            if (suffix != null) {
                return suffix;
            }
        }
        Map<String, ResourceLocation> known = PATCH_BIOMES.get(level);
        if (known != null) {
            ResourceLocation biome = known.get(patch);
            if (biome != null) {
                return biome;
            }
        }
        if (patch.startsWith(PATCH_PREFIX)) {
            int[] parsed = parse3(patch);
            if (parsed == null) {
                return null;
            }
            Column column = cachedColumn(level, parsed[0], parsed[1]);
            if (column != null) {
                Segment segment = column.segmentAt(parsed[2]);
                if (segment != null) {
                    return segment.biome();
                }
            }
            return sampleBiomeAt(level, parsed[0], parsed[1], parsed[2] + SECTION_BLOCKS / 2);
        }
        // 旧 p:/g:/|biome key 从 0.4.11 起不再支持：载入时会被丢弃，扫描只会产生 p3: key。
        return null;
    }

    /**
     * 旧 API：3D 模型下片大小不再按水平格统计，固定返回 0（保留签名供附属模组兼容）。
     *
     * @deprecated 3D 段模型没有「水平格数」的概念；片的大小取决于段在竖直方向的连通范围。
     *             这里固定返回 0，调用方不应再依赖它做范围/密度估算。
     */
    @Deprecated
    public static int patchSize(ServerLevel level, String patch) {
        return 0;
    }

    /** 该列所有段对应的规范 key（去重）；列未扫描时返回空集合。 */
    public static Set<String> columnPatchKeys(ServerLevel level, int columnX, int columnZ) {
        Column column = cachedColumn(level, columnX, columnZ);
        if (column == null) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        forEachColumnPatch(level, columnX, columnZ, keys::add);
        return keys;
    }

    /**
     * 遍历该列所有段对应的规范 key，不创建临时集合；列未扫描时什么都不做。
     *
     * <p>整列扫描（{@link #ensureColumn}）之后，这一列的所有段（含洞穴/浮岛）都已缓存，
     * 扫描调度可以直接用本方法分类，不需要再逐 section 采样。</p>
     */
    public static void forEachColumnPatch(ServerLevel level, int columnX, int columnZ, Consumer<String> consumer) {
        Column column = cachedColumn(level, columnX, columnZ);
        if (column == null) {
            return;
        }
        for (Segment segment : column.segments()) {
            consumer.accept(canonical(level, nodeId(column.x(), column.z(), segment.startY())));
        }
    }

    /**
     * 确保该列已扫描（受每 tick 列扫描预算限制）。
     *
     * @return 列已缓存（本次扫描成功或此前已扫描）时返回 true
     */
    public static boolean ensureColumn(ServerLevel level, int columnX, int columnZ) {
        return columnFor(level, columnX, columnZ, true) != null;
    }

    /** 列缓存：按访问顺序淘汰，避免超限时整体清空造成的集中重算。 */
    private static Map<Long, Column> newColumnCache() {
        return new LinkedHashMap<>(256, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Column> eldest) {
                return size() > MAX_COLUMN_CACHE;
            }
        };
    }

    /** 片 key -> 群系缓存：同样按访问顺序 LRU，避免清空后已淘汰 key 的群系只能靠采样猜。 */
    private static Map<String, ResourceLocation> newBiomeCache() {
        return new LinkedHashMap<>(256, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ResourceLocation> eldest) {
                return size() > MAX_PATCH_BIOMES;
            }
        };
    }

    /** 去掉旧子状态后缀，得到基础片 key；3D key 原样返回。 */
    public static String basePatch(String patch) {
        if (patch == null) {
            return null;
        }
        int separator = patch.indexOf(SUB_STATE_SEPARATOR);
        return separator < 0 ? patch : patch.substring(0, separator);
    }

    /** 调试日志是否开启。 */
    public static boolean debugEnabled() {
        try {
            return Config.DEBUG_LOGGING.get();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static String resolve(ServerLevel level, BlockPos pos, boolean budgeted) {
        if (level.isOutsideBuildHeight(pos)) {
            return null;
        }
        return resolveColumn(level, pos.getX() >> 4, pos.getZ() >> 4, pos.getY(), budgeted);
    }

    private static String resolveColumn(ServerLevel level, int columnX, int columnZ, int y, boolean budgeted) {
        Column column = columnFor(level, columnX, columnZ, budgeted);
        if (column == null) {
            return null;
        }
        Segment segment = column.segmentAt(y);
        if (segment == null) {
            return null;
        }
        String node = nodeId(column.x(), column.z(), segment.startY());
        return canonical(level, node);
    }

    /** 取列（必要时扫描）；预算模式下预算用尽返回 null。 */
    private static Column columnFor(ServerLevel level, int columnX, int columnZ, boolean budgeted) {
        Map<Long, Column> cache = COLUMNS.computeIfAbsent(level, ignored -> newColumnCache());
        long key = ChunkPos.asLong(columnX, columnZ);
        Column column = cache.get(key);
        if (column != null) {
            return column;
        }
        TickBudget budget = BUDGETS.computeIfAbsent(level, ignored -> new TickBudget());
        if (budgeted) {
            if (budget.scans >= budget.scanLimit || budget.nanos >= budget.nanoLimit) {
                return null;
            }
        }
        long started = System.nanoTime();
        column = scanColumn(level, columnX, columnZ);
        if (budgeted) {
            budget.scans++;
            budget.nanos += System.nanoTime() - started;
        }
        cache.put(key, column);
        registerColumn(level, column, cache);
        if (debugEnabled()) {
            LOGGER.info("[LRDBG] scan dim={} col={},{} segs={} costUs={}",
                    level.dimension().location(), columnX, columnZ, column.segments().size(),
                    (System.nanoTime() - started) / 1000L);
        }
        return column;
    }

    /**
     * 扫描一列的群系段：按 quart（4 方块，群系数据原生分辨率）逐格采样，
     * 段边界落在 quart 边界上，因此天气范围与 {@code level.getBiome(pos)} 的量化完全一致。
     * 优先读已加载区块的群系调色板，未加载时退回噪声采样。
     */
    private static Column scanColumn(ServerLevel level, int columnX, int columnZ) {
        int blockX = (columnX << 4) + 8;
        int blockZ = (columnZ << 4) + 8;
        int quartX = QuartPos.fromBlock(blockX);
        int quartZ = QuartPos.fromBlock(blockZ);
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int minQuart = QuartPos.fromBlock(minY);
        int maxQuart = QuartPos.fromBlock(maxY - 1);
        LevelChunk chunk = level.getChunkSource().getChunkNow(columnX, columnZ);
        List<Segment> segments = new ArrayList<>();
        ResourceLocation current = null;
        int startY = minY;
        for (int quartY = minQuart; quartY <= maxQuart; quartY++) {
            ResourceLocation biome = chunk != null
                    ? biomeOf(chunk.getNoiseBiome(quartX, quartY, quartZ))
                    : biomeOf(level.getUncachedNoiseBiome(quartX, quartY, quartZ));
            int segmentY = Math.max(minY, QuartPos.toBlock(quartY));
            if (current == null) {
                current = biome;
                startY = segmentY;
            } else if (!current.equals(biome)) {
                segments.add(new Segment(startY, segmentY, current));
                current = biome;
                startY = segmentY;
            }
        }
        if (current != null) {
            segments.add(new Segment(startY, maxY, current));
        }
        return new Column(columnX, columnZ, List.copyOf(segments));
    }

    private static ResourceLocation biomeOf(net.minecraft.core.Holder<Biome> holder) {
        return holder.unwrapKey().map(ResourceKey::location).orElse(UNKNOWN);
    }

    private static ResourceLocation sampleBiomeAt(ServerLevel level, int columnX, int columnZ, int blockY) {
        int blockX = (columnX << 4) + 8;
        int blockZ = (columnZ << 4) + 8;
        LevelChunk chunk = level.getChunkSource().getChunkNow(columnX, columnZ);
        if (chunk != null) {
            return biomeOf(chunk.getNoiseBiome(QuartPos.fromBlock(blockX), QuartPos.fromBlock(blockY),
                    QuartPos.fromBlock(blockZ)));
        }
        return biomeOf(level.getUncachedNoiseBiome(QuartPos.fromBlock(blockX), QuartPos.fromBlock(blockY),
                QuartPos.fromBlock(blockZ)));
    }

    /** 注册该列所有段，并与四个相邻列的同群系段合并。 */
    private static void registerColumn(ServerLevel level, Column column, Map<Long, Column> cache) {
        for (Segment segment : column.segments()) {
            register(level, nodeId(column.x(), column.z(), segment.startY()), segment.biome());
        }
        mergeWith(level, column, cache, column.x() + 1, column.z());
        mergeWith(level, column, cache, column.x() - 1, column.z());
        mergeWith(level, column, cache, column.x(), column.z() + 1);
        mergeWith(level, column, cache, column.x(), column.z() - 1);
    }

    private static void mergeWith(ServerLevel level, Column column, Map<Long, Column> cache,
                                  int neighbourX, int neighbourZ) {
        Column neighbour = cache.get(ChunkPos.asLong(neighbourX, neighbourZ));
        if (neighbour == null) {
            return;
        }
        for (Segment self : column.segments()) {
            for (Segment other : neighbour.segments()) {
                if (!self.biome().equals(other.biome()) || !overlaps(self, other)) {
                    continue;
                }
                union(level, nodeId(column.x(), column.z(), self.startY()),
                        nodeId(neighbour.x(), neighbour.z(), other.startY()), self.biome());
            }
        }
    }

    private static boolean overlaps(Segment a, Segment b) {
        return a.startY() < b.endY() && b.startY() < a.endY();
    }

    private static void register(ServerLevel level, String node, ResourceLocation biome) {
        WeatherData data = WeatherManager.data(level);
        Map<String, String> parents = data.segmentParents();
        if (!parents.containsKey(node)) {
            parents.put(node, node);
            data.setDirty();
        }
        Map<String, ResourceLocation> known = PATCH_BIOMES.computeIfAbsent(level, ignored -> newBiomeCache());
        known.put(node, biome);
    }

    /** 沿并查集解析到规范 key，并做路径压缩。 */
    private static String canonical(ServerLevel level, String node) {
        Map<String, String> parents = WeatherManager.data(level).segmentParents();
        String root = node;
        int guard = 0;
        int limit = parents.size() + 1;
        String next = parents.get(root);
        while (next != null && !next.equals(root) && guard++ < limit) {
            root = next;
            next = parents.get(root);
        }
        if (next != null && !next.equals(root)) {
            // 父表损坏（成环）：退化为按原节点处理，并只警告一次，避免静默把中间节点当根。
            // 把该节点写成自映射（隔离），否则每次查询都要重走一整条坏链。
            WeatherData parentData = WeatherManager.data(level);
            parentData.segmentParents().put(node, node);
            parentData.setDirty();
            if (!warnedChainGuard) {
                warnedChainGuard = true;
                LOGGER.warn("Segment parent chain did not terminate within {} steps while resolving {}; "
                        + "treating the node as its own root. The parent map may be corrupted", limit, node);
            }
            return node;
        }
        if (root.equals(node)) {
            return node;
        }
        WeatherData data = WeatherManager.data(level);
        String cursor = node;
        int compressGuard = 0;
        while (!cursor.equals(root) && compressGuard++ < limit) {
            String parent = parents.get(cursor);
            if (parent == null || parent.equals(cursor)) {
                break;
            }
            parents.put(cursor, root);
            cursor = parent;
        }
        data.setDirty();
        return root;
    }

    /** 合并两个段节点：规范 key 取列/段坐标字典序较小者，并迁移天气状态。 */
    private static void union(ServerLevel level, String a, String b, ResourceLocation biome) {
        WeatherData data = WeatherManager.data(level);
        Map<String, String> parents = data.segmentParents();
        String rootA = canonical(level, a);
        String rootB = canonical(level, b);
        if (rootA.equals(rootB)) {
            return;
        }
        String newRoot = compareNodes(rootA, rootB) <= 0 ? rootA : rootB;
        String oldRoot = newRoot.equals(rootA) ? rootB : rootA;
        parents.put(oldRoot, newRoot);
        data.transferState(oldRoot, newRoot);
        PATCH_BIOMES.computeIfAbsent(level, ignored -> newBiomeCache()).put(newRoot, biome);
        data.setDirty();
        if (debugEnabled()) {
            LOGGER.info("[LRDBG] union {} + {} -> {} biome={}", rootA, rootB, newRoot, biome);
        }
    }

    private static int compareNodes(String a, String b) {
        int[] pa = parse3(a);
        int[] pb = parse3(b);
        if (pa == null || pb == null) {
            return a.compareTo(b);
        }
        if (pa[0] != pb[0]) {
            return Integer.compare(pa[0], pb[0]);
        }
        if (pa[1] != pb[1]) {
            return Integer.compare(pa[1], pb[1]);
        }
        return Integer.compare(pa[2], pb[2]);
    }

    private static String nodeId(int columnX, int columnZ, int startY) {
        return PATCH_PREFIX + columnX + "," + columnZ + "," + startY;
    }

    /** 解析 {@code p3:<x>,<z>,<y>}。 */
    private static int[] parse3(String patch) {
        if (patch == null || !patch.startsWith(PATCH_PREFIX)) {
            return null;
        }
        String[] parts = patch.substring(PATCH_PREFIX.length()).split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())
            };
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Column cachedColumn(ServerLevel level, int columnX, int columnZ) {
        Map<Long, Column> cache = COLUMNS.get(level);
        return cache == null ? null : cache.get(ChunkPos.asLong(columnX, columnZ));
    }

    /**
     * 该片 key 对应的列是否仍在列缓存中。
     *
     * <p>清理父表时用它区分「列已淘汰、下次扫描会重新注册合并」与「列仍在缓存、但父链被删」：
     * 后者不会自动重建，会把同一段群系切成多个片，必须跳过。</p>
     */
    public static boolean isColumnCached(ServerLevel level, String patch) {
        int[] parsed = parse3(patch);
        if (parsed == null) {
            return false;
        }
        // 用 containsKey 而不是 get：COLUMNS 是 accessOrder 的 LRU，清理时的大量检查不能刷新访问顺序
        Map<Long, Column> cache = COLUMNS.get(level);
        return cache != null && cache.containsKey(ChunkPos.asLong(parsed[0], parsed[1]));
    }

    /**
     * 从列缓存里移除该片所在的列，使其在下一次扫描时重新注册/合并。
     * 用于父表硬上限收缩：列缓存命中时对应节点不会重新写出，只能先逐出列。
     */
    public static boolean evictColumn(ServerLevel level, String patch) {
        int[] parsed = parse3(patch);
        if (parsed == null) {
            return false;
        }
        Map<Long, Column> cache = COLUMNS.get(level);
        return cache != null && cache.remove(ChunkPos.asLong(parsed[0], parsed[1])) != null;
    }

    private static int sampleBlockY(ServerLevel level) {
        int minBuild = level.getMinBuildHeight();
        int maxBuild = level.getMaxBuildHeight();
        return level.dimensionType().hasSkyLight()
                ? Math.max(minBuild, Math.min(maxBuild - 1, SAMPLE_BLOCK_Y))
                : (minBuild + maxBuild) / 2;
    }

    private static int maxColumnScansPerTick() {
        try {
            return Math.max(1, Config.MAX_COLUMN_SCANS_PER_TICK.get());
        } catch (IllegalStateException ignored) {
            return DEFAULT_COLUMN_SCANS_PER_TICK;
        }
    }

    private record Segment(int startY, int endY, ResourceLocation biome) {
        boolean contains(int y) {
            return y >= this.startY && y < this.endY;
        }
    }

    private record Column(int x, int z, List<Segment> segments) {
        Segment segmentAt(int y) {
            for (Segment segment : this.segments) {
                if (segment.contains(y)) {
                    return segment;
                }
            }
            return null;
        }
    }

    private static final class TickBudget {
        /** 本预算窗口内允许的列扫描次数；{@link #beginTick}/{@link #beginSweep} 会重置。 */
        private int scanLimit = DEFAULT_COLUMN_SCANS_PER_TICK;
        /** 本预算窗口内允许的扫描耗时上限（纳秒）。 */
        private long nanoLimit = COLUMN_BUDGET_NANOS;
        private int scans;
        private long nanos;
    }

    /** 一次显式操作（例如 /weather area）允许触发的列扫描次数。 */
    public static final class FillBudget {
        private int remaining;

        public FillBudget(int maxFills) {
            this.remaining = maxFills;
        }

        private boolean consume() {
            if (this.remaining <= 0) {
                return false;
            }
            this.remaining--;
            return true;
        }
    }
}

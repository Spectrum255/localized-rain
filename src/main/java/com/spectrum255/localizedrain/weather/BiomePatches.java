package com.spectrum255.localizedrain.weather;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

/**
 * 把「同一生物群系连成的一整片」当作天气范围。
 * <p>
 * 用生物群系源直接采样（{@code ServerLevel#getUncachedNoiseBiome}，不需要加载区块），
 * 从起点做洪水填充得到这片群系的轮廓；返回的 key 就是这片范围的标识。
 * 超大的群系（海洋之类）会退化成对齐的方格，避免一次扫描几十万个格子。
 */
public final class BiomePatches {
    private static final ResourceLocation UNKNOWN = ResourceLocation.fromNamespaceAndPath("localizedrain", "unknown");
    /** 采样格边长：16 方块（一个区块），大致对应地形上能看到的一个点。 */
    private static final int CELL_BLOCKS = 16;
    /** 洪水填充的格子上限，超过就退化。 */
    private static final int MAX_CELLS = 4096;
    /** 退化方格边长（区块）：64 区块 = 1024 方块。 */
    private static final int FALLBACK_SHIFT = 6;
    /** 采样生物群系用的固定高度（y=128）：地表群系在这一层基本稳定，也不会碰到洞穴群系。 */
    private static final int SAMPLE_QUART_Y = QuartPos.fromBlock(128);
    /** 缓存上限，超过就整体清空（结果可以确定性地重新算出来）。 */
    private static final int MAX_CACHE_SIZE = 200_000;

    private static final Map<ServerLevel, Map<Long, String>> CACHE = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<String, Integer>> SIZES = new WeakHashMap<>();
    /** 出现过超大群系的退化方格，记下来避免在整片海洋上反复做洪水填充。 */
    private static final Map<ServerLevel, Set<Long>> LARGE_BUCKETS = new WeakHashMap<>();

    private BiomePatches() {}

    /** 取（必要时计算）该位置所在群系范围的标识。 */
    public static String patchAt(ServerLevel level, BlockPos pos) {
        return patchAtCell(level, SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public static String patchAtCell(ServerLevel level, int cellX, int cellZ) {
        Map<Long, String> cache = cacheFor(level);
        String known = cache.get(ChunkPos.asLong(cellX, cellZ));
        if (known != null) {
            return known;
        }
        if (largeBucketsFor(level).contains(bucketKey(cellX, cellZ))) {
            return fallbackKey(cellX, cellZ);
        }
        return compute(level, cache, cellX, cellZ);
    }

    /** 只查缓存，不触发洪水填充——用于 isRainingAt 这类每个 tick 都会走的路径。 */
    public static String cachedPatchAt(ServerLevel level, BlockPos pos) {
        Map<Long, String> cache = CACHE.get(level);
        if (cache == null) {
            return null;
        }
        int cellX = SectionPos.blockToSectionCoord(pos.getX());
        int cellZ = SectionPos.blockToSectionCoord(pos.getZ());
        return cache.get(ChunkPos.asLong(cellX, cellZ));
    }

    /** 这片范围覆盖多少个采样格；非轮廓范围（退化方格）返回 0。 */
    public static int patchSize(ServerLevel level, String patch) {
        if (patch == null) {
            return 0;
        }
        Map<String, Integer> sizes = SIZES.get(level);
        if (sizes == null) {
            return 0;
        }
        Integer size = sizes.get(patch);
        return size == null ? 0 : size;
    }

    private static Map<Long, String> cacheFor(ServerLevel level) {
        Map<Long, String> cache = CACHE.get(level);
        if (cache == null) {
            cache = new HashMap<>();
            CACHE.put(level, cache);
        }
        return cache;
    }

    private static Map<String, Integer> sizesFor(ServerLevel level) {
        Map<String, Integer> sizes = SIZES.get(level);
        if (sizes == null) {
            sizes = new HashMap<>();
            SIZES.put(level, sizes);
        }
        return sizes;
    }

    private static Set<Long> largeBucketsFor(ServerLevel level) {
        Set<Long> buckets = LARGE_BUCKETS.get(level);
        if (buckets == null) {
            buckets = new HashSet<>();
            LARGE_BUCKETS.put(level, buckets);
        }
        return buckets;
    }

    private static long bucketKey(int cellX, int cellZ) {
        return ChunkPos.asLong(cellX >> FALLBACK_SHIFT, cellZ >> FALLBACK_SHIFT);
    }

    private static String compute(ServerLevel level, Map<Long, String> cache, int startX, int startZ) {
        ResourceLocation biome = biomeAt(level, startX, startZ);
        Set<Long> visited = new HashSet<>();
        Map<Long, ResourceLocation> biomeByCell = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        long smallest = Long.MAX_VALUE;

        long start = ChunkPos.asLong(startX, startZ);
        queue.add(start);
        biomeByCell.put(start, biome);
        boolean tooLarge = false;

        while (!queue.isEmpty()) {
            long cell = queue.poll();
            if (!visited.add(cell)) {
                continue;
            }
            if (visited.size() > MAX_CELLS) {
                tooLarge = true;
                break;
            }
            smallest = Math.min(smallest, cell);
            int x = ChunkPos.getX(cell);
            int z = ChunkPos.getZ(cell);
            visitNeighbour(level, queue, visited, biomeByCell, biome, x + 1, z);
            visitNeighbour(level, queue, visited, biomeByCell, biome, x - 1, z);
            visitNeighbour(level, queue, visited, biomeByCell, biome, x, z + 1);
            visitNeighbour(level, queue, visited, biomeByCell, biome, x, z - 1);
        }

        if (tooLarge) {
            // 超大群系：用对齐的方格，保证从任何起点算出的 key 都一致
            Set<Long> buckets = largeBucketsFor(level);
            for (long cell : visited) {
                int cellX = ChunkPos.getX(cell);
                int cellZ = ChunkPos.getZ(cell);
                cache.put(cell, fallbackKey(cellX, cellZ));
                buckets.add(bucketKey(cellX, cellZ));
            }
            buckets.add(bucketKey(startX, startZ));
            return fallbackKey(startX, startZ);
        }

        if (cache.size() > MAX_CACHE_SIZE) {
            cache.clear();
            sizesFor(level).clear();
        }
        String key = "p:" + ChunkPos.getX(smallest) + "," + ChunkPos.getZ(smallest);
        for (long cell : visited) {
            cache.put(cell, key);
        }
        sizesFor(level).put(key, visited.size());
        return key;
    }

    private static void visitNeighbour(ServerLevel level, ArrayDeque<Long> queue, Set<Long> visited,
                                       Map<Long, ResourceLocation> biomeByCell, ResourceLocation biome, int x, int z) {
        long cell = ChunkPos.asLong(x, z);
        if (visited.contains(cell)) {
            return;
        }
        ResourceLocation neighbour = biomeByCell.get(cell);
        if (neighbour == null) {
            neighbour = biomeAt(level, x, z);
            biomeByCell.put(cell, neighbour);
        }
        if (neighbour.equals(biome)) {
            queue.add(cell);
        }
    }

    private static ResourceLocation biomeAt(ServerLevel level, int cellX, int cellZ) {
        int blockX = cellX * CELL_BLOCKS + CELL_BLOCKS / 2;
        int blockZ = cellZ * CELL_BLOCKS + CELL_BLOCKS / 2;
        return level.getUncachedNoiseBiome(QuartPos.fromBlock(blockX), SAMPLE_QUART_Y, QuartPos.fromBlock(blockZ))
                .unwrapKey()
                .map(ResourceKey::location)
                .orElse(UNKNOWN);
    }

    private static String fallbackKey(int cellX, int cellZ) {
        return "g:" + (cellX >> FALLBACK_SHIFT) + "," + (cellZ >> FALLBACK_SHIFT);
    }
}

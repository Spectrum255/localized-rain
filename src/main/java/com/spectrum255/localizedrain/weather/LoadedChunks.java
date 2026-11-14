package com.spectrum255.localizedrain.weather;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * 每个维度当前已加载的区块集合。
 *
 * <p>天气扫描需要遍历「已加载区块」而不是「玩家所在区块」。{@code ChunkMap#getChunks()} 是
 * {@code protected}，直接枚举要么加 accessor、要么混入；这里改用 NeoForge 的
 * {@link ChunkEvent.Load}/{@link ChunkEvent.Unload} 维护一份集合，不依赖任何反射或 minecraft 内部方法。</p>
 *
 * <p>集合按 {@link ServerLevel} 弱引用保存，维度卸载时清空。所有方法都只在主线程调用。</p>
 */
public final class LoadedChunks {
    private static final Map<ServerLevel, LongOpenHashSet> LOADED = new WeakHashMap<>();

    private LoadedChunks() {}

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            chunks(level).add(event.getChunk().getPos().toLong());
        }
    }

    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            chunks(level).remove(event.getChunk().getPos().toLong());
        }
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LOADED.remove(level);
        }
    }

    /** 当前已加载区块坐标的快照；调用方不应修改返回值。 */
    public static long[] snapshot(ServerLevel level) {
        return chunks(level).toLongArray();
    }

    public static int count(ServerLevel level) {
        return chunks(level).size();
    }

    private static LongOpenHashSet chunks(ServerLevel level) {
        return LOADED.computeIfAbsent(level, ignored -> new LongOpenHashSet());
    }
}

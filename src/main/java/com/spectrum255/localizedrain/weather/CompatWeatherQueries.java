package com.spectrum255.localizedrain.weather;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.Localizedrain;
import java.util.Arrays;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 原版天气查询的兼容代理值（仅服务端）。
 *
 * <p>原版的 {@link Level#getRainLevel(float)} / {@link Level#getThunderLevel(float)} 没有坐标参数，
 * 是维度级的“当前雨强/雷强”；{@code Level#isRaining()} / {@code Level#isThundering()} 也由它们推导。
 * 本模组把天气局部化以后不再写回维度级状态，于是第三方模组通过这四个接口看到的永远是晴天。
 * 这个类提供代理值，由 {@code LevelWeatherMixin} 在 {@code getRainLevel} / {@code getThunderLevel}
 * 的 RETURN 处取 {@code max(原版, 本值)}。</p>
 *
 * <p>代理只覆盖服务端。客户端原版通道已经由逐玩家数据驱动（片模式的服务端
 * {@code ClientboundGameEventPacket.RAIN_LEVEL_CHANGE}、移动模式的 {@code ClientStormWeather}），
 * 再叠一层“维度内最大风暴”会覆盖逐玩家结果，因此客户端分支固定返回 0。</p>
 *
 * <p>服务端分两层：</p>
 * <ol>
 *   <li>位置精确：随机刻、计划刻（方块/流体）、方块实体、实体 tick、降水刻、区块 tick
 *       （{@code tickChunk}，用区块中心）这些“已知坐标”的帧里，查询返回该坐标所在片/风暴的强度。
 *       位置通过 {@link #runAt} / {@link #begin} 压入线程本地栈，栈按线程隔离、深度上限 {@value #MAX_DEPTH}，
 *       每个服务器 tick 开头清空。</li>
 *   <li>维度级近似：其余没有坐标的调用返回“该维度里某处有天气”的最大强度。</li>
 * </ol>
 *
 * <p>数值与原版不变量对齐：雨量低于 {@value #RAIN_VISIBLE_THRESHOLD} 吸附为 0（与原版
 * {@code isRaining()} 的 0.2 阈值一致，避免“有粒子但显示晴天”）；雷量先按雨量封顶
 * （原版 {@code getThunderLevel = 雷因子 * 雨量}），低于 {@value #THUNDER_VISIBLE_THRESHOLD} 吸附为 0。</p>
 *
 * <p>开销受控：开关关闭（默认）时 {@link #active()} 只是一次 volatile 布尔读取，不压栈、不查询；
 * 维度缓存也只在真正被查询时才按 tick 懒计算。</p>
 *
 * <p>{@link #runAt} / {@link #begin} 只允许在服务器主线程调用；在其他线程调用会忽略位置作用域并告警一次。</p>
 */
public final class CompatWeatherQueries {
    /** 位置栈深度上限：随机刻/计划刻/实体 tick 之间可能嵌套，但不会很深。 */
    private static final int MAX_DEPTH = 8;
    /** 待配对 token 栈上限：每次 {@link #begin} 都占一个槽位（未压栈记 -1），{@link #end} 按槽位出栈。 */
    private static final int MAX_PENDING = 32;
    /** 与原版 {@code Level#isRaining()} 的可见阈值一致。 */
    private static final float RAIN_VISIBLE_THRESHOLD = 0.2F;
    /** 与原版 {@code Level#isThundering()} 的判定阈值一致。 */
    private static final float THUNDER_VISIBLE_THRESHOLD = 0.9F;

    private static final class Frames {
        private final long[] positions = new long[MAX_DEPTH];
        private final ResourceKey<?>[] dimensions = new ResourceKey<?>[MAX_DEPTH];
        private final Entity[] entities = new Entity[MAX_DEPTH];
        private final int[] tokens = new int[MAX_PENDING];
        private int depth;
        private int tokenDepth;
        private int suppressDepth;
        /** 邻居更新链的嵌套层数：只有最外层压栈，避免深层红石/观察者链吃光帧栈与 token 栈。 */
        private int neighborDepth;
    }

    private static final ThreadLocal<Frames> FRAMES = ThreadLocal.withInitial(Frames::new);

    /** {@link Config#compatVanillaWeatherQueries()} 的缓存，服务器 tick 里定期刷新。 */
    private static volatile boolean enabledCache;

    private static volatile boolean warnedOffThread;

    /** token 栈溢出只告警一次，避免刷屏。 */
    private static volatile boolean warnedTokenOverflow;

    private CompatWeatherQueries() {}

    /** 重新读取 SERVER 配置；服务器 tick 与启停时调用。 */
    public static void refresh() {
        try {
            enabledCache = Config.compatVanillaWeatherQueries();
        } catch (RuntimeException e) {
            enabledCache = false;
        }
    }

    /** 服务器停止时重置一次性告警标志，重开存档后仍能提示。 */
    public static void resetWarnings() {
        warnedOffThread = false;
        warnedTokenOverflow = false;
    }

    /** 服务端是否启用代理（已缓存的配置值）。 */
    public static boolean serverEnabled() {
        return enabledCache;
    }

    /** 是否应该做位置/维度解析：开关打开且不是原版模式。 */
    public static boolean active() {
        return enabledCache && !WeatherManager.vanillaMode();
    }

    /** 当前线程位置栈深度（调试用）。 */
    public static int depth() {
        return FRAMES.get().depth;
    }

    /** 清空当前线程的位置栈与 token 栈；服务器 tick 开头调用，兜底异常路径留下的脏帧。 */
    public static void clear() {
        Frames frames = FRAMES.get();
        frames.depth = 0;
        frames.tokenDepth = 0;
        frames.suppressDepth = 0;
        frames.neighborDepth = 0;
        // beginEntity 会在帧里保存实体强引用；异常路径没走到 RETURN 时在这里兜底清掉，避免钉住 Level/chunk
        Arrays.fill(frames.entities, null);
    }

    /** 在指定位置执行一段逻辑，期间的无坐标天气查询按该位置解析。 */
    public static void runAt(Level level, BlockPos pos, Runnable action) {
        if (!active() || offThread(level)) {
            action.run();
            return;
        }
        int depth = push(level, pos, null);
        try {
            action.run();
        } finally {
            restore(depth);
        }
    }

    /** 同 {@link #runAt(Level, BlockPos, Runnable)}，有返回值。 */
    public static <T> T runAt(Level level, BlockPos pos, Supplier<T> action) {
        if (!active() || offThread(level)) {
            return action.get();
        }
        int depth = push(level, pos, null);
        try {
            return action.get();
        } finally {
            restore(depth);
        }
    }

    /**
     * 帧式注入的 HEAD 侧：压入位置。与 {@link #end()} 成对调用。
     *
     * <p>{@code pos} 为 null 表示“本帧不压栈”（调用方在开关关闭时省掉了坐标计算），
     * 但仍在 token 栈里登记一个 -1，保证 {@link #end()} 的配对不会错位。</p>
     */
    public static void begin(Level level, BlockPos pos) {
        beginPacked(level, pos == null ? null : pos.asLong());
    }

    /** 同 {@link #begin(Level, BlockPos)}，用拆开的坐标避免调用方分配 {@link BlockPos}。 */
    public static void begin(Level level, int x, int y, int z) {
        beginPacked(level, BlockPos.asLong(x, y, z));
    }

    private static void beginPacked(Level level, Long packedPos) {
        Frames frames = FRAMES.get();
        int token = -1;
        if (packedPos != null && active() && frames.depth < MAX_DEPTH) {
            frames.positions[frames.depth] = packedPos;
            frames.dimensions[frames.depth] = level.dimension();
            frames.entities[frames.depth] = null;
            token = frames.depth;
            frames.depth++;
        }
        registerToken(frames, token);
    }

    /**
     * 实体 tick 的 HEAD 侧：帧里先存实体引用，{@link #positionFor} 第一次查询时才读
     * {@code entity.blockPosition()} 并缓存到帧里；同一帧内后续查询复用这个坐标（实体在一 tick 内
     * 跨过片边界时仍按首次查询的位置解析，一 tick 内的偏差不超过该实体的移动距离）。
     */
    public static void beginEntity(Level level, Entity entity) {
        Frames frames = FRAMES.get();
        int token = -1;
        if (entity != null && active() && frames.depth < MAX_DEPTH) {
            frames.positions[frames.depth] = Long.MIN_VALUE;   // 首次查询时再解析
            frames.dimensions[frames.depth] = level.dimension();
            frames.entities[frames.depth] = entity;
            token = frames.depth;
            frames.depth++;
        }
        registerToken(frames, token);
    }

    private static void registerToken(Frames frames, int token) {
        if (frames.tokenDepth < MAX_PENDING) {
            frames.tokens[frames.tokenDepth++] = token;
        } else if (!warnedTokenOverflow) {
            warnedTokenOverflow = true;
            Localizedrain.LOGGER.warn("[localizedrain] CompatWeatherQueries frame stack is saturated ({} pending); "
                    + "the position scope of this frame was dropped and queries fall back to the dimension maximum",
                    MAX_PENDING);
        }
    }

    /** 帧式注入的 RETURN 侧：按 token 出栈；HEAD 未压栈的帧不会误弹外层。 */
    public static void end() {
        Frames frames = FRAMES.get();
        if (frames.tokenDepth <= 0) {
            return;
        }
        int token = frames.tokens[--frames.tokenDepth];
        if (token >= 0 && frames.depth > 0) {
            frames.depth--;
            frames.entities[frames.depth] = null;
        }
    }

    /**
     * 邻居更新帧的 HEAD 侧：只有最外层压栈，内层只登记一个 -1 token。
     *
     * <p>方块更新、红石、观察者链、爆炸连锁会在一 tick 内嵌套很多层，但同一次扩散的坐标差只有
     * 几个方块；用最外层的位置已经足够精确，同时把帧栈/token 栈的消耗从 O(嵌套层数) 降为 O(1)，
     * 不会在第 8 层之后突然回落到维度最大值。</p>
     */
    public static void beginNeighbor(Level level, BlockPos pos) {
        Frames frames = FRAMES.get();
        boolean outermost = frames.neighborDepth == 0;
        frames.neighborDepth++;
        begin(level, outermost ? pos : null);
    }

    /** 与 {@link #beginNeighbor} 成对：退回邻居链嵌套计数并消费 token。 */
    public static void endNeighbor() {
        Frames frames = FRAMES.get();
        if (frames.neighborDepth > 0) {
            frames.neighborDepth--;
        }
        end();
    }

    /**
     * 临时屏蔽代理（{@code Level#updateSkyBrightness} 用）：期间服务端代理值返回 0，
     * 让全局天空亮度保持原版值，逐位置的黑暗由本模组 {@code WeatherManager#skyDarkenAt} 负责。
     */
    public static void suppressBegin() {
        if (!active()) {
            return;
        }
        Frames frames = FRAMES.get();
        if (frames.suppressDepth < MAX_DEPTH) {
            frames.suppressDepth++;
        }
    }

    /** 与 {@link #suppressBegin()} 成对。 */
    public static void suppressEnd() {
        Frames frames = FRAMES.get();
        if (frames.suppressDepth > 0) {
            frames.suppressDepth--;
        }
    }

    /** {@code getRainLevel} 的代理值；返回 0 表示不覆盖原版。 */
    public static float localRainLevel(Level level) {
        if (!(level instanceof ServerLevel server)) {
            return 0.0F;
        }
        // 异步线程（并行区块生成、第三方 worker）不能访问 LOCAL_QUERY/LOOKUPS 这些非同步缓存：
        // 直接放弃代理，让原版值生效
        if (!active() || suppressed() || offThread(level)) {
            return 0.0F;
        }
        BlockPos pos = positionFor(level);
        float value = pos == null
                ? WeatherManager.localRainLevel(server)
                : WeatherManager.rainLevelAt(server, pos);
        return visibleRain(value);
    }

    /** {@code getThunderLevel} 的代理值；返回 0 表示不覆盖原版。 */
    public static float localThunderLevel(Level level) {
        if (!(level instanceof ServerLevel server)) {
            return 0.0F;
        }
        if (!active() || suppressed() || offThread(level)) {
            return 0.0F;
        }
        BlockPos pos = positionFor(level);
        float rain = visibleRain(pos == null
                ? WeatherManager.localRainLevel(server)
                : WeatherManager.rainLevelAt(server, pos));
        float thunder = pos == null
                ? WeatherManager.localThunderLevel(server)
                : WeatherManager.thunderLevelAt(server, pos);
        return visibleThunder(thunder, rain);
    }

    /**
     * 当前栈顶帧的位置（实体帧按实体的当前坐标）；栈顶维度与查询维度不一致时返回 null，
     * 不再向下层借位置（跨维度帧内不借用其他维度的作用域）。
     */
    private static BlockPos positionFor(Level level) {
        Frames frames = FRAMES.get();
        if (frames.depth <= 0) {
            return null;
        }
        int top = frames.depth - 1;
        if (!level.dimension().equals(frames.dimensions[top])) {
            return null;
        }
        Entity entity = frames.entities[top];
        if (entity == null) {
            return BlockPos.of(frames.positions[top]);
        }
        long cached = frames.positions[top];
        if (cached == Long.MIN_VALUE) {
            cached = entity.blockPosition().asLong();
            frames.positions[top] = cached;
        }
        return BlockPos.of(cached);
    }

    private static int push(Level level, BlockPos pos, Entity entity) {
        Frames frames = FRAMES.get();
        if (pos == null || frames.depth >= MAX_DEPTH) {
            return -1;
        }
        frames.positions[frames.depth] = pos.asLong();
        frames.dimensions[frames.depth] = level.dimension();
        frames.entities[frames.depth] = entity;
        frames.depth++;
        return frames.depth;
    }

    private static void restore(int depth) {
        if (depth >= 0) {
            Frames frames = FRAMES.get();
            frames.depth = depth - 1;
            frames.entities[frames.depth] = null;
        }
    }

    private static boolean suppressed() {
        return FRAMES.get().suppressDepth > 0;
    }

    /** 非服务端主线程调用时不做位置作用域（缓存不是线程安全的），并告警一次。 */
    private static boolean offThread(Level level) {
        if (!(level instanceof ServerLevel server)) {
            return false;
        }
        try {
            if (server.getServer().isSameThread()) {
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        if (!warnedOffThread) {
            warnedOffThread = true;
            Localizedrain.LOGGER.warn("[localizedrain] CompatWeatherQueries position scope was called off the "
                    + "server thread; ignoring the position context (queries fall back to the dimension maximum)");
        }
        return true;
    }

    private static float visibleRain(float value) {
        float rain = sanitize(value);
        return rain < RAIN_VISIBLE_THRESHOLD ? 0.0F : rain;
    }

    private static float visibleThunder(float thunder, float rain) {
        // 原版 getThunderLevel = 雷因子 * 雨量，确保雷量不超过雨量，isThundering() 不会在无雨时为真
        float value = Math.min(sanitize(thunder), rain);
        return value < THUNDER_VISIBLE_THRESHOLD ? 0.0F : value;
    }

    private static float sanitize(float value) {
        return Float.isFinite(value) ? Mth.clamp(value, 0.0F, 1.0F) : 0.0F;
    }
}

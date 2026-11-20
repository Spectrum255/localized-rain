package com.spectrum255.localizedrain.api.event;

import com.spectrum255.localizedrain.api.StormSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * 风暴即将进入淡出前发布；取消会让它继续存活，并在 20 tick 内不再重复触发同一个事件。
 *
 * <p>这是「pre」事件：监听器可以在风暴真正开始淡出（{@link StormRemoveEvent} 之前的阶段）前
 * 决定延长它的寿命、或者把它转成别的状态。</p>
 */
public class StormDyingEvent extends Event implements ICancellableEvent {
    /** 这次淡出的触发来源。 */
    public enum Cause { LIFETIME, STAGE_END, FAR_AWAY, EVOLUTION }

    private final ServerLevel level;
    private final StormSnapshot snapshot;
    private final Cause cause;

    public StormDyingEvent(ServerLevel level, StormSnapshot snapshot, Cause cause) {
        this.level = level;
        this.snapshot = snapshot;
        this.cause = cause;
    }

    public ServerLevel level() { return this.level; }

    public StormSnapshot snapshot() { return this.snapshot; }

    public Cause cause() { return this.cause; }
}

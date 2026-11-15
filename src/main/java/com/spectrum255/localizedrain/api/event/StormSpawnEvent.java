package com.spectrum255.localizedrain.api.event;

import com.spectrum255.localizedrain.api.StormSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/** 生成风暴前发布；取消即拒绝生成（自然生成与手动生成都会发）。 */
public class StormSpawnEvent extends Event implements ICancellableEvent {
    private final ServerLevel level;
    private final StormSnapshot snapshot;

    public StormSpawnEvent(ServerLevel level, StormSnapshot snapshot) {
        this.level = level;
        this.snapshot = snapshot;
    }

    public ServerLevel level() { return this.level; }

    public StormSnapshot snapshot() { return this.snapshot; }
}

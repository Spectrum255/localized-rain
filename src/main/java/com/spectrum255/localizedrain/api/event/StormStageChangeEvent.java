package com.spectrum255.localizedrain.api.event;

import com.spectrum255.localizedrain.api.StormSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/** 阶段切换后发布。 */
public class StormStageChangeEvent extends Event {
    private final ServerLevel level;
    private final StormSnapshot snapshot;
    private final int fromStage;

    public StormStageChangeEvent(ServerLevel level, StormSnapshot snapshot, int fromStage) {
        this.level = level;
        this.snapshot = snapshot;
        this.fromStage = fromStage;
    }

    public ServerLevel level() { return this.level; }

    public StormSnapshot snapshot() { return this.snapshot; }

    public int fromStage() { return this.fromStage; }
}

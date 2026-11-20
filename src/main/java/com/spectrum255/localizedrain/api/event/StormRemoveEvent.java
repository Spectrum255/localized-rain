package com.spectrum255.localizedrain.api.event;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/** 风暴被移除后发布。reason 用于区分手动、自然消亡、档案缺失。 */
public class StormRemoveEvent extends Event {
    public enum Reason { MANUAL, FINISHED, FAR_AWAY, MISSING_PROFILE, CLEARED, MERGED, SPLIT }

    private final ServerLevel level;
    private final UUID id;
    private final Reason reason;

    public StormRemoveEvent(ServerLevel level, UUID id, Reason reason) {
        this.level = level;
        this.id = id;
        this.reason = reason;
    }

    public ServerLevel level() { return this.level; }

    public UUID id() { return this.id; }

    public Reason reason() { return this.reason; }
}

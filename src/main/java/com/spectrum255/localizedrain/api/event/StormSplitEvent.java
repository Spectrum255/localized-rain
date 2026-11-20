package com.spectrum255.localizedrain.api.event;

import com.spectrum255.localizedrain.api.StormSnapshot;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * 风暴分裂成多个子风暴前发布；取消即拒绝本次分裂（父风暴保持原样）。
 *
 * <p>{@link #parent()} 是分裂前的快照，{@link #children()} 是按档案/类型参数预先算好的子风暴快照，
 * 监听器可以取消或（后续版本）替换参数。</p>
 */
public class StormSplitEvent extends Event implements ICancellableEvent {
    private final ServerLevel level;
    private final StormSnapshot parent;
    private final List<StormSnapshot> children;

    public StormSplitEvent(ServerLevel level, StormSnapshot parent, List<StormSnapshot> children) {
        this.level = level;
        this.parent = parent;
        this.children = List.copyOf(children);
    }

    public ServerLevel level() { return this.level; }

    public StormSnapshot parent() { return this.parent; }

    public List<StormSnapshot> children() { return this.children; }
}

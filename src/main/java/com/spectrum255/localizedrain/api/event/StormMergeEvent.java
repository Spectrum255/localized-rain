package com.spectrum255.localizedrain.api.event;

import com.spectrum255.localizedrain.api.StormSnapshot;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * 两个风暴合并前发布；取消即拒绝本次合并（双方都保持原样）。
 *
 * <p>存活方是强度更高（并列时更早生成）的那个风暴，被吸收方的快照放在 {@link #absorbed()} 里。
 * 合并只发生在自然风暴之间，手动风暴不参与。</p>
 */
public class StormMergeEvent extends Event implements ICancellableEvent {
    private final ServerLevel level;
    private final StormSnapshot survivor;
    private final List<StormSnapshot> absorbed;

    public StormMergeEvent(ServerLevel level, StormSnapshot survivor, List<StormSnapshot> absorbed) {
        this.level = level;
        this.survivor = survivor;
        this.absorbed = List.copyOf(absorbed);
    }

    public ServerLevel level() { return this.level; }

    public StormSnapshot survivor() { return this.survivor; }

    public List<StormSnapshot> absorbed() { return this.absorbed; }
}

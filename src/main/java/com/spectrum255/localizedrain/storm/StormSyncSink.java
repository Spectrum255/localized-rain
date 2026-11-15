package com.spectrum255.localizedrain.storm;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 领域层向网络层发送同步数据的出口。
 * 由网络层在启动时安装实现（{@code StormSyncBroadcaster}），
 * 这样 {@link StormManager} 不需要 import 任何 payload / 网络类。
 */
public interface StormSyncSink {
    void broadcast(ServerLevel level, StormSyncData data);

    void sendTo(ServerPlayer player, StormSyncData data);

    void sendHud(ServerPlayer player, StormSyncData.Hud hud);
}

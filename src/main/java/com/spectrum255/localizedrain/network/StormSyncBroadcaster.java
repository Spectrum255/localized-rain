package com.spectrum255.localizedrain.network;

import com.spectrum255.localizedrain.storm.StormSyncData;
import com.spectrum255.localizedrain.storm.StormSyncSink;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** 把领域层的 {@link StormSyncData} 编码成网络包并发送；StormManager 只依赖 {@link StormSyncSink} 接口。 */
public final class StormSyncBroadcaster implements StormSyncSink {
    public StormSyncBroadcaster() {
    }

    @Override
    public void broadcast(ServerLevel level, StormSyncData data) {
        StormSyncPayload payload = payload(data);
        for (ServerPlayer player : level.players()) {
            player.connection.send(payload);
        }
    }

    @Override
    public void sendTo(ServerPlayer player, StormSyncData data) {
        player.connection.send(payload(data));
    }

    @Override
    public void sendHud(ServerPlayer player, StormSyncData.Hud hud) {
        player.connection.send(new WeatherStatePayload(hud.raining(), hud.thundering(), hud.typeKey()));
    }

    private static StormSyncPayload payload(StormSyncData data) {
        List<StormSyncPayload.StormEntry> entries = new ArrayList<>(data.storms().size());
        for (StormSyncData.Entry entry : data.storms()) {
            entries.add(new StormSyncPayload.StormEntry(entry.id(), entry.x(), entry.z(), entry.radius(),
                    entry.velocityX(), entry.velocityZ(), entry.minY(), entry.maxY(),
                    entry.typeId(), entry.hasPrecipitation(), entry.allowsThunder(),
                    entry.rain(), entry.thunder(),
                    entry.manual(), entry.dying(), entry.dyingTicksLeft()));
        }
        return new StormSyncPayload(entries, data.isolatedMode(), data.falloffRatio(), data.active());
    }
}

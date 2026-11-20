package com.spectrum255.localizedrain.network;

import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.storm.StormSyncData;
import com.spectrum255.localizedrain.storm.StormSyncSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** 把领域层的 {@link StormSyncData} 编码成网络包并发送；StormManager 只依赖 {@link StormSyncSink} 接口。 */
public final class StormSyncBroadcaster implements StormSyncSink {
    /** 解码端按 MAX_STORMS 拒绝；编码端必须同样截断，否则超限时会直接把客户端踢下线。 */
    private static final AtomicBoolean WARNED_OVERFLOW = new AtomicBoolean();

    /** 服务器停止/换存档后复位「只警告一次」标志，新存档里再次超限时仍能被记录。 */
    public static void resetWarnings() {
        WARNED_OVERFLOW.set(false);
    }

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
        List<StormSyncData.HudLayer> layers = hud.layers();
        List<WeatherStatePayload.Layer> payloadLayers = new ArrayList<>(Math.min(layers.size(),
                WeatherStatePayload.MAX_LAYERS));
        for (int index = 0; index < layers.size() && index < WeatherStatePayload.MAX_LAYERS; index++) {
            StormSyncData.HudLayer layer = layers.get(index);
            payloadLayers.add(new WeatherStatePayload.Layer(layer.typeKey(), layer.typeId(), layer.intensity()));
        }
        player.connection.send(new WeatherStatePayload(hud.raining(), hud.thundering(),
                hud.typeKey(), hud.typeId(), hud.intensity(), payloadLayers));
    }

    private static StormSyncPayload payload(StormSyncData data) {
        List<StormSyncData.Entry> storms = data.storms();
        int size = storms.size();
        int max = StormSyncPayload.MAX_STORMS;
        if (size > max) {
            if (WARNED_OVERFLOW.compareAndSet(false, true)) {
                Localizedrain.LOGGER.warn("[localizedrain] storm sync payload has {} storms but the client only accepts {}; "
                        + "truncating (reduce naturally spawned / manual storms per dimension)", size, max);
            }
            size = max;
        }
        List<StormSyncPayload.StormEntry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            StormSyncData.Entry entry = storms.get(index);
            entries.add(new StormSyncPayload.StormEntry(entry.id(), entry.x(), entry.z(), entry.radius(),
                    entry.velocityX(), entry.velocityZ(), entry.minY(), entry.maxY(),
                    entry.typeId(), entry.hasPrecipitation(), entry.allowsThunder(),
                    entry.rain(), entry.thunder(),
                    entry.manual(), entry.dying(), entry.dyingTicksLeft()));
        }
        return new StormSyncPayload(entries, data.isolatedMode(), data.falloffRatio(), data.active());
    }
}

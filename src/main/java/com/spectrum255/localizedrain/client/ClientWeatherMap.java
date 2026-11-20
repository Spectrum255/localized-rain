package com.spectrum255.localizedrain.client;

import com.spectrum255.localizedrain.network.WeatherMapPayload;
import com.spectrum255.localizedrain.network.WeatherMapRequestPayload;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/** 客户端天气地图缓存：只保存服务端最近一次推送的快照。 */
public final class ClientWeatherMap {
    private static volatile WeatherMapPayload current;
    private static volatile long receivedAt;

    private ClientWeatherMap() {}

    public static void accept(WeatherMapPayload payload) {
        if (payload == null) {
            return;
        }
        current = payload;
        receivedAt = Util.getMillis();
    }

    public static WeatherMapPayload current() {
        return current;
    }

    /** 距上次收到快照的毫秒数；从未收到时返回 Long.MAX_VALUE。 */
    public static long ageMillis() {
        WeatherMapPayload payload = current;
        return payload == null ? Long.MAX_VALUE : Util.getMillis() - receivedAt;
    }

    public static void clear() {
        current = null;
        receivedAt = 0L;
    }

    /** 请求一份新快照（只在打开地图时调用，服务端限流 10 tick）。 */
    public static void requestRefresh() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            PacketDistributor.sendToServer(WeatherMapRequestPayload.INSTANCE);
        }
    }
}

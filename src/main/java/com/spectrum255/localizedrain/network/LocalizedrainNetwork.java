package com.spectrum255.localizedrain.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class LocalizedrainNetwork {
    private LocalizedrainNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        // optional()：没装本模组的原版客户端也可以连，只是收不到天气包；
        // 版本号随 payload 结构变化（"2" 起增加 typeKey 第三字段，"3" 起增加风暴快照包，
        // "4" 起风暴快照带消亡剩余 tick 以平滑淡出，"5" 起快照尾部带服务端的隔离群系/衰减配置，
        // "6" 起风暴条目带类型能力位 hasPrecipitation/allowsThunder）
        event.registrar("6")
                .optional()
                .playToClient(
                        WeatherStatePayload.TYPE,
                        WeatherStatePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(
                                () -> ClientWeatherTargets.set(payload.raining(), payload.thundering(), payload.typeKey())))
                .playToClient(
                        StormSyncPayload.TYPE,
                        StormSyncPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(
                                () -> com.spectrum255.localizedrain.client.ClientStormCache.accept(payload)));
    }
}

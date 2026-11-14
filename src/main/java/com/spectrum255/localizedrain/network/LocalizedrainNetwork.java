package com.spectrum255.localizedrain.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class LocalizedrainNetwork {
    private LocalizedrainNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        // optional()：没装本模组的原版客户端也可以连，只是收不到天气包；
        // 版本号随 payload 结构变化（0.2.1 起增加 typeKey 第三字段）
        event.registrar("2")
                .optional()
                .playToClient(
                        WeatherStatePayload.TYPE,
                        WeatherStatePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(
                                () -> ClientWeatherTargets.set(payload.raining(), payload.thundering(), payload.typeKey())));
    }
}

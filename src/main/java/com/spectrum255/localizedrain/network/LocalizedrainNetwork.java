package com.spectrum255.localizedrain.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class LocalizedrainNetwork {
    private LocalizedrainNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        // optional()：没装本模组的原版客户端也可以连，只是收不到天气包；
        // 版本号随 payload 结构变化（"2" 起增加 typeKey 第三字段，"3" 起增加风暴快照包，
        // "4" 起风暴快照带消亡剩余 tick 以平滑淡出，"5" 起快照尾部带服务端的隔离群系/衰减配置，
        // "6" 起风暴条目带类型能力位 hasPrecipitation/allowsThunder，
        // "7" 起快照带 active 标志：服务端切回 patches/vanilla 时客户端停止用风暴缓存接管雨量，
        // "8" 起天气包带 typeId/intensity：客户端据类型定义渲染自定义粒子与音效。
        // "9" 起天气包尾部带叠加层列表（移动模式最多 8 层），客户端按层渲染粒子/雾并显示多行 HUD。
        // "10" 起新增云图：客户端请求 + 服务端快照（片/风暴/风/预报）。
        //
        // 版本号不是提示信息：NeoForge 1.21.1 的 NetworkComponentNegotiator 会对同 id 通道做
        // version.equals 校验，不一致会让整次协商失败（连接被拒）。optional() 只影响「对端完全没有该通道」
        // 的情况，不能用来兼容字段变化——任何 payload 结构改动都必须递增这里的版本号。
        event.registrar("10")
                .optional()
                .playToClient(
                        WeatherStatePayload.TYPE,
                        WeatherStatePayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            ClientWeatherTargets.set(payload.raining(), payload.thundering(),
                                    payload.typeKey(), payload.typeId(), payload.intensity());
                            com.spectrum255.localizedrain.client.ClientWeatherLayers.set(payload.layers());
                            // 立即刷新，避免本帧渲染仍用旧类型（切到自定义天气后第一帧还下原版雨）
                            com.spectrum255.localizedrain.client.ClientWeatherVisuals
                                    .refresh(net.minecraft.client.Minecraft.getInstance());
                        }))
                .playToClient(
                        StormSyncPayload.TYPE,
                        StormSyncPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(
                                () -> com.spectrum255.localizedrain.client.ClientStormCache.accept(payload)))
                .playToClient(
                        WeatherMapPayload.TYPE,
                        WeatherMapPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(
                                () -> com.spectrum255.localizedrain.client.ClientWeatherMap.accept(payload)))
                // 云图请求：客户端只在打开地图界面时发送，服务端 10 tick 限流并只回附近数据
                .playToServer(
                        WeatherMapRequestPayload.TYPE,
                        WeatherMapRequestPayload.STREAM_CODEC,
                        (payload, context) -> context.enqueueWork(
                                () -> com.spectrum255.localizedrain.weather.WeatherMapService
                                        .handleRequest((net.minecraft.server.level.ServerPlayer) context.player())));
    }
}

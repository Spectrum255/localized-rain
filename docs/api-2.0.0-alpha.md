# Localized Rain API（2.0.0-alpha）

面向附属模组的公开接口，全部位于 `com.spectrum255.localizedrain.api`。
2.0.0-alpha 在 1.x 的基础上新增了移动模式的层视图、近似预报与云图快照；原有方法签名不变。

## 线程与调用位置

| 接口 | 允许的线程 | 说明 |
| --- | --- | --- |
| `typeAt` / `patchAt` / `isRainingAt` / `isPrecipitatingAt` / `isThunderingAt` / `isThunderingRegionAt` / `thunderLevelAt` / `skyDarkenAt` / `rainLevelAt` / `layersAt` | 任意线程 | 读取已缓存的状态；缓存未命中时按需解析一列（不受每 tick 列扫描预算限制），不要在非主线程对大量坐标循环调用 |
| `forecast` / `mapSnapshot` | 主线程 | 可能触发列扫描或读取服务端风暴列表；`mapSnapshot` 建议 GUI 打开时每秒一次 |
| `setWeather` / `setWeatherAt` | 主线程 | 写服务端状态并广播；会跳过强制天气锁定的片 |
| `patches` / `patchAt`（批量遍历） | 任意线程 | 返回快照集合，不保证顺序 |

`WeatherApi.withPosition(level, pos, supplier)` 可以把一次位置查询包进服务端的位置帧，让代理层在
`neighborChanged`、方块实体、实体 tick 等回调里读到该位置的局部强度；非主线程调用会被忽略。

## 模式差异

- **PATCHES（片模式）**：天气按 3D 群系段的连通分量划分。`layersAt` 返回单层；`forecast` 按当前天气类型的
  `next` 预测下一次变化；`mapSnapshot` 按 16 方块步长采样各片的类型与强度。
- **MOBILE（移动风暴）**：同一点可能被多个风暴覆盖。`layersAt` 按兼容规则返回叠加的各层（强度降序，
  上限 `maxStackedWeatherTypes`）；`forecast` 按风暴阶段边界预测下一阶段；`mapSnapshot` 取主导层。
- **VANILLA（原版）**：不做局部化。`layersAt` 把原版雨/雷合成一层，`forecast` 返回空列表，
  `mapSnapshot` 仍可用来画“全维度同一天气”。

所有方法在传入 `null` 或查询不到状态时返回空值（`null` / `0` / `List.of()`），不会抛异常。

## 查询方法

```java
WeatherType type = WeatherApi.typeAt(level, pos);          // 该位置的天气类型（晴天返回 clear 类型）
float rain = WeatherApi.rainLevelAt(level, pos);            // 0..1，服务端局部值
List<WeatherApi.WeatherLayerView> layers = WeatherApi.layersAt(level, pos);

for (WeatherApi.WeatherLayerView layer : layers) {
    // layer.typeId() / layer.type() / layer.rain() / layer.thunder() / layer.intensity()
    // layer.canPrecipitateHere() / layer.affectsPlayers()
}
```

`WeatherLayerView#intensity` 是该层的展示强度（降水类型等于雨量，非降水类型按天空变暗程度归一化）；
`canPrecipitateHere` 表示该位置在当前层下是否真的会下雨雪（考虑维度天光与群系降水）。

## 预报

```java
for (WeatherApi.ForecastStep step : WeatherApi.forecast(level, pos, 600)) {
    // step.inTicks()：距离变化的 tick 数
    // step.typeId()：变化后的类型
    // step.intensity()：变化后的强度（晴天为 0）
}
```

预报是**近似**：片模式只看当前天气类型的 `next` 里权重最高的已注册条目（不做温湿度过滤），
移动模式只看风暴的下一阶段；原版模式没有预报。返回按时间升序，最多 8 条；空列表表示
`horizonTicks` 内没有可预期的变化。

## 云图快照

```java
List<WeatherApi.MapCell> cells = WeatherApi.mapSnapshot(level, center, 256);
// MapCell(x, z, typeId, rain, thunder, intensity)
```

半径会被夹到 16–512，步长固定 16 方块，只返回非晴天格。用于自绘 GUI 或世界地图叠加；
不要每 tick 调用。

## 事件

新增事件都在 `com.spectrum255.localizedrain.api.event`，实现 `ICancellableEvent`：

| 事件 | 时机 | 取消效果 |
| --- | --- | --- |
| `StormDyingEvent` | 风暴因寿命、阶段结束、离开玩家或演化而准备淡出前 | 本次不淡出，20 tick 内不再尝试 |
| `StormMergeEvent` | 两个风暴即将合并前 | 跳过这一对，下一轮再判断 |
| `StormSplitEvent` | 风暴即将分裂前 | 不分裂，也不消耗本次分裂冷却 |
| `StormRemoveEvent` | 风暴已从列表移除后 | 不可取消（用于清理/统计） |

`StormDyingEvent` 的 `cause()` 为 `LIFETIME` / `STAGE_END` / `FAR_AWAY` / `EVOLUTION`；
`StormMergeEvent` 带保留者与被吸收者快照；`StormSplitEvent` 带父风暴与新子风暴快照。

## 协议

网络协议版本 `"10"`（`LocalizedrainNetwork` 的 registrar）。客户端与服务端必须一致，
不一致时登录会被拒绝并提示。相对 1.9.0-alpha（`"9"`）新增两个云图 payload：

- `localizedrain:weather_map_request`（C2S，空包）：客户端打开天气地图 / 每 20 tick 请求一次；
  服务端按玩家 10 tick 限流，且只在主线程汇总。
- `localizedrain:weather_map`（S2C）：`gameTime`、`radius`、风矢量、`patches`（≤512）、`storms`（≤64）、
  `forecast`（≤8）。片与风暴都做了数量上限与 NaN/范围净化，解码超限直接抛 `DecoderException`。

`WeatherMapService` 只发送玩家 512 格内、`isWeatherActive()` 的片与未消亡的风暴；移动模式没有片，
只发风暴。地图界面本身在客户端读缓存绘制，不访问服务端世界。

## 数据包字段

天气类型新增两个可选字段（详见 README 的 `weather_type` 小节）：

- `lifecycle`：`{ "ramp_in_ticks": 200, "ramp_out_ticks": 200 }`，片模式强度渐变。
- `next`：`[{ "type": "minecraft:rain", "weight": 3, "min_biome_temperature": 0.4, "biome_tag": "..." }]`，
  片模式下一次天气的加权候选。

风暴档案的阶段新增 `transitions`：`[{ "stage": 2, "weight": 1, "min_moisture": 0.3 }]`，
满足条件时按权重跳到指定阶段，否则退回线性推进。

## 兼容性说明

- `effects.attributes` 写入的是 `BuiltInRegistries.ATTRIBUTE` 中的原始属性；Apothic Attributes 等模组的
  派生属性（攻击伤害、暴击率等）由对方计算，本模组不直接写派生值。
- 未知属性 id 会被跳过并只告警一次；每个玩家最多 16 组修饰符，超出部分忽略。
- `[compat] effectsApplyAttributes=false` 时只禁用属性部分，伤害/药水/点燃/冰冻/移速不受影响。

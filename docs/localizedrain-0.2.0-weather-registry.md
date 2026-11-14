# 0.2.0：天气类型注册表与 lib 型判定层

## 背景

0.1.1 及之前，`/weather` 与存档里的天气只有三种写死的状态（晴天 / 降雨 / 雷暴），
判定也直接写死在 `WeatherManager.isRainingAt` 等方法里。

0.2.0 把「天气类型」抽成一个**独立的 NeoForge 注册表** `localizedrain:weather_type`，
并把本模组服务端的天气判定层改成按类型工作。目标是：本模组可以作为一个 lib 型前置，
让附属模组注册新的天气类型，并由本模组统一处理「某个群系片现在是什么天气」的判定；
客户端降水渲染保持原样（仍然是原版雨/雷通道）。

## 注册表

- 注册表 key：`localizedrain:weather_type`
- 条目类型：`com.spectrum255.localizedrain.api.WeatherType`

```java
public record WeatherType(
        String displayKey,          // 名称翻译键，例如 weather.localizedrain.rain
        boolean hasPrecipitation,   // 是否产生降水（影响积雪、炼药锅、粒子判定）
        Biome.Precipitation precipitation,
        boolean allowsThunder       // 是否允许雷暴与闪电
)
```

内置三个类型：

| id | displayKey | 降水 | 雷暴 |
| --- | --- | --- | --- |
| `localizedrain:clear` | `weather.localizedrain.clear` | `NONE` | 否 |
| `localizedrain:rain` | `weather.localizedrain.rain` | `RAIN` | 否 |
| `localizedrain:thunder` | `weather.localizedrain.thunder` | `RAIN` | 是 |

天气的「强度」继续复用原版 `rainLevel` / `thunderLevel`（0–1）通道，因此客户端渲染、
雨声、天空亮度、亡灵燃烧、刷怪这些原版逻辑不需要新的网络协议。

## 附属模组如何注册类型

```java
public static final DeferredHolder<WeatherType, WeatherType> ASH =
        WeatherTypes.REGISTRY.register("ash",
                () -> new WeatherType("weather.example.ash", false, Biome.Precipitation.NONE, true));
```

然后把某个（或若干个）群系片设成该类型：

```java
WeatherApi.setWeather(level, List.of(patch), ASH.getId(), 6000);
```

- `WeatherApi` 是本模组对外的只读查询与设置入口（`typeAt`、`isRainingAt`、
  `isPrecipitatingAt`、`isThunderingAt`、`isThunderingRegionAt`、`thunderLevelAt`、
  `skyDarkenAt`、`patches`、`setWeather`）。
- 注册必须在 `Localizedrain` 的 `makeRegistry` 之后、`NewRegistryEvent` 触发之前完成；
  本模组已经替所有 `WeatherTypes.REGISTRY` 的注册项创建好注册表，附属模组直接 `register` 即可。
- 附属模组也可以直接调用 `WeatherApi.setWeather(...)`，不需要自己处理名单、强制天气与存档。

## 判定层的变化（服务端）

- `WeatherManager.weatherTypeAt(level, pos)` 取「该位置所在群系片」的天气类型；
  取不到时回退晴天。
- `isRainingAt`：需要状态在下雨 **且** 类型 `hasPrecipitation()` **且** 降水形态是 `RAIN`。
- `isPrecipitatingAt`：需要类型 `hasPrecipitation()`。
- `isThunderingAt` / `isThunderingRegionAt`：需要类型 `allowsThunder()`。
- `spawnLightning`：只对 `allowsThunder()` 的类型生成闪电。
- `/weather type <id>`：把片设成注册表里的任意类型；内置的
  `/weather clear|rain|thunder` 仍然保留（等价于对应内置 id）。
- 存档 `version=6`：每个天气状态额外保存 `weather_type`；旧存档没有该字段时按内置类型反推，
  未知 id 回退晴天。

## 命令

```
/weather type <id> [duration]
/weather type <id> at <pos>
/weather type <id> area <from> <to>
/weather type <id> global
/weather clear|rain|thunder ...
```

`<id>` 有 Tab 补全，来源是 `localizedrain:weather_type` 注册表。

## 已知限制

- 客户端原版仍只有「雨/雪」两种渲染通道；`hasPrecipitation=false` 的自定义类型
  在客户端看起来就是晴天（可以用 `allowsThunder` 借用雷声/闪电；其他粒子需要附属模组自己做渲染）。
- 原版大量硬编码的 `Level.isRaining()` / `isThundering()` 仍然由本模组按玩家所在片的
  最大雨量/雷量驱动（这是兼容层），真正按位置的判定走 `WeatherApi`。
- 存档里旧类型的 id 在附属模组被移除后会回退晴天。
- 未做客户端 / 专用服务器运行期验证，只做了构建与产物检查。

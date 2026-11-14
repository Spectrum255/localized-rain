# 0.2.1 — 覆盖配置支持任意天气类型 + 失效判定审计

## 1. `weatherBiomeOverrides` 支持任意注册 id

之前只接受 `clear|rain|thunder`。现在格式是：

```toml
[biome_rules]
weatherBiomeOverrides = [
    "minecraft:*=rain",                       # 旧短名，等价 localizedrain:rain
    "minecraft:desert=clear",
    "#minecraft:is_ocean=thunder",
    "minecraft:desert=examplemod:sandstorm",  # 任意已注册类型
]
```

- 短名 `clear`/`rain`/`thunder` 映射到内置类型。
- 其余按 `ResourceLocation` 解析，**惰性**：配置加载时不查注册表，只在判定时通过
  `WeatherTypes.resolve(level, id)` 解析；因此附属模组的类型先注册、配置后加载也不会错位。
- 类型未注册（附属模组被移除）时该条覆盖被忽略，并**只警告一次**；该群系回落到白名单/黑名单的常规判定。
- 解析到的类型若是「晴天类型」（`hasPrecipitation == false && allowsThunder == false`），
  等价于该群系永远晴天。

实现位置：`Config#validBiomeOverride`、`WeatherBiomeFilter` 的
`parseWeatherId` / `Snapshot.forcedWeather` / `Decision` / `Forced`。

## 2. 审计：禁用原版简单天气判定后，哪些功能失效、如何重接

0.2.0 把服务端判定切到「天气类型」后，仍有几处按旧假设工作。本次逐项重接：

| 位置 | 旧假设的问题 | 0.2.1 的处理 |
| --- | --- | --- |
| `WeatherManager.tick` | 先无条件按群系 `hasPrecipitation` 跳过，导致「沙漠=沙尘暴」这类干燥群系上的自定义类型永远不 tick | 先判强制规则：有强制类型就进入 tick 并每秒压目标旗标，不再看降水；只有**未被强制**且群系不产生降水时才按晴天处理 |
| `WeatherManager.stateOrNull` | 查询缓存里带 `precipitates` 门控，干燥群系里的自定义类型查不到状态 | 去掉该门控，恢复 `LookupCell(patch, decision)`；「能不能下雨」改由各查询方法按类型判断 |
| `WeatherManager.isRainingAt` | — | 已按 `hasPrecipitation && precipitation()==RAIN` 门控（0.2.0 完成，本次确认） |
| `WeatherManager.isPrecipitatingAt` | — | 已按 `hasPrecipitation` 门控（0.2.0 完成） |
| `WeatherManager.isThunderingAt` / `isThunderingRegionAt` | — | 已按 `allowsThunder` 门控（0.2.0 完成） |
| `WeatherManager.spawnLightning` | 只看 `state.isThundering()` | 追加 `allowsThunder`，自定义类型不会意外落雷 |
| `WeatherManager.thunderLevelAt` | 返回存储的雷量，不区分类型 | 类型 `allowsThunder()==false` 时返回 0（与 `isThunderingRegionAt` 一致）；内部仍继承 0.1.1 的只读语义 |
| `WeatherManager.send` | 直接发 `state.isThundering()`，且 payload 只有两个布尔 | 雷布尔追加 `allowsThunder` 门控；payload 增加第三字段 `typeKey`（自定义类型的 `displayKey`，内置/晴天为空串） |
| Xaero 天气行 | 只根据两布尔显示「下雨/雷暴」，自定义类型显示为晴 | `XaeroWeatherCompat` 优先读 `ClientWeatherTargets.typeKey()`，非空时直接显示该类型的翻译键 |
| `/weather` 命令 | 过滤条件已按 `forcedWeather == null`，签名兼容 | 无需改动；命中强制/名单排除的片仍跳过并提示数量 |

### 有意保持原样的部分

- **客户端渲染不要动**：雨滴、雨声、雾、日月变暗仍沿原版 `rainLevel`/`thunderLevel` 通道按玩家平滑。
  自定义类型如果用 `hasPrecipitation=false`，就不会触发原版雨滴与天空变暗；沙尘暴的粒子/雾/音效由附属模组自己做。
- **原版维度级副作用**：`level.setRainLevel/setThunderLevel` 仍取当前玩家片的峰值，供原版粒子/雾/亮度读取；
  这是既定设计（见 1.0.x 记录）。
- **3D 子状态**：无降水群系（洞穴、浮岛、Meteoros 平流层）继续按晴天处理；强制类型现在可以覆盖这一规则
  （这正是「沙漠=沙尘暴」需要的）。

## 3. 协议与存档

- `localizedrain:weather_state` payload 从 `(boolean raining, boolean thundering)` 变为
  `(boolean, boolean, String typeKey)`；注册版本号 `1` → `2`。旧版客户端/服务端与本版互不兼容
  （payload 仍是 `optional()`，纯原版客户端不受影响）。
- 存档格式不变（`WeatherData` 仍是 v5，`WeatherState.type` 已在 0.2.0 写入）。
- 覆盖配置没有新增配置项，旧配置无需迁移。

## 4. 构建

- 版本：`0.2.1`
- 构建：`clean build --offline`（JDK 21）
- 产物：`build/libs/localizedrain-0.2.1.jar`，83,163 字节，
  SHA256 `8B5C150087E4BB92AF9B44C9FF08CC2C20E70BFC2EC3B34A645624398E314948`
- jar 内 `version="0.2.1"`、`license="MIT"`；中英语言各 29 键。

## 5. 未验证

按既有约束没有启动客户端/服务器，也没有提交：注册表填充、`/weather type`、自定义类型在干燥群系上的强制、
Xaero 自定义行显示都只做了编译与产物检查，待用户实测。

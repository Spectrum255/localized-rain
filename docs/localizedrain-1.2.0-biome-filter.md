# localizedrain 1.2.0 — 生物群系天气黑白名单与强制天气

## 背景

天气单位是「群系片」（见 `docs/idea.md` 与 `docs/localizedrain-1.0.1-changes.md`）。
1.2.0 增加了按生物群系调节天气的机制：可以排除特定群系（例如沙漠、海洋、自定义维度里的特殊群系）、
只让少数群系参与局地天气，也可以把某些群系固定在指定天气。

## 配置

`config/localizedrain-common.toml` 里新增三项字符串列表：

```toml
weatherBiomeWhitelist = []
weatherBiomeBlacklist = []
weatherBiomeOverrides = []
maxPatchCells = 1024
```

前两项的条目支持三种写法：

| 写法 | 含义 |
| --- | --- |
| `minecraft:desert` | 精确匹配一个群系 id（含其他模组注册的群系） |
| `minecraft:*` | 匹配整个命名空间 |
| `#minecraft:is_ocean` | 匹配一个群系标签（原版或数据包定义的标签） |

`weatherBiomeOverrides` 的条目是 `<群系选择器>=clear|rain|thunder`，选择器同样支持上面三种写法，例如：

```toml
weatherBiomeOverrides = [
  "minecraft:*=rain",              # 兜底：所有群系常驻降雨
  "minecraft:desert=clear",        # 沙漠强制晴天
  "#minecraft:is_ocean=thunder"    # 海洋强制雷暴
]
```

语义与优先级：

- **强制天气 > 黑名单 > 白名单**。强制规则优先于两个名单。
- **白名单为空 = 全部允许**；白名单非空时，只有白名单内的群系参与局地天气。
- **黑名单**命中的群系永远保持晴天：不推进天气、不计入维度雨量、不落雷、不积雪。
- **强制规则**命中的群系片不再随机切换天气，而是保持指定类型；`clear` 等同永远晴天。
  多条强制规则命中同一群系时，**按配置顺序后者覆盖前者**（可以先写 `minecraft:*=rain` 再单独覆盖个别群系）。
- 被排除或被强制天气的群系片里已有的天气状态不再生效（但仍留在存档里，把规则移除后会恢复）。
- 三组规则都为空时，行为与 1.1.0 完全一致。

## 实现要点

- `weather/WeatherBiomeFilter.java`：解析三组配置，按「群系片 -> Decision（是否允许、强制天气）」缓存判定结果；
  标签在服务器启动和配置重载时解析一次（`ModConfigEvent` / `ServerStartedEvent` 里 `invalidate()`）。
- `BiomePatches` 记录每个片对应的生物群系 id（`patchBiome`），缓存被清理时按片 key 里的坐标补采样一次，
  `getUncachedNoiseBiome` 不加载区块。
- `WeatherManager`：`tick` 只推进允许的片；强制雨/雷的片在 `data.tick` 之后由 `WeatherData.forceWeather`
  把旗标压回目标值（雨量/雷量仍以 0.01/tick 渐变）；强制晴天的片不参与随机循环，只让残留的雨量淡出；
  `stateOrNull` 对不允许的片返回晴天；`setWeather` 跳过不允许或被强制的片；`spawnLightning` 只在允许的雷暴片里抽签。
- `/weather` 命令：命中的片全部被跳过（名单排除或强制天气）时报错；部分跳过时在成功消息后追加跳过数量。

## 性能（P0/P1 优化）

- 轮廓扫描上限改为配置 `maxPatchCells`（默认 1024，范围 256–4096）；超过上限的群系片退化为 1024 方块对齐的方格。
- 服务端 tick 里的扫描有预算：每 tick 最多 2 次填充 / 4ms，超出的位置先返回退化方格键，后续 tick 继续补算
  （`BiomePatches.beginTick` 重置预算、`patchAtBudgeted` 按预算执行）。
- 填充内部改用原始 long 的开放寻址集合与环形队列，去掉了 `HashSet<Long>` / `HashMap<Long, ...>` 的装箱开销。
- 天气热查询（`isRainingAt`、`skyDarkenAt`、刷怪判定等）按 16×16 方块做 cell 缓存：
  片 key、名单判定与 `WeatherData` 一次查表拿到；配置重载时整体失效。
- `/weather area` 单次最多触发 8 次轮廓扫描，超出会报错并提示缩小范围（避免命令卡住主线程）。
- 仍存在的一次性开销：进入从未访问过的群系片时要做一次填充；缓存超过 20 万条会整体清空后重算。

## 已知限制

- 群系判定沿用 `BiomePatches` 的采样近似（1.3.0 起按层高在层中心采样；1.2.0 时固定 y=128），
  层高范围内的洞穴/地表过渡会被归到同一片。
- 名单和强制规则是在服务器启动/配置重载时解析的；用数据包热加载标签后需要 `/reload`（会触发配置重载事件）或重启。
- 强制雷暴沿用原版语义：只有降水类型为 `RAIN` 的群系才会真正落雷；雪原会打雷、天空变暗，但不会生成闪电。
- 强制天气只在有玩家的群系片被应用（和天气 tick 的范围一致）：玩家离开后该片冻结，回来时继续保持目标天气。
- 没有客户端/服务器运行期验证，只做了构建验证（`clean build --offline` 通过，产物 `build/libs/localizedrain-1.2.0.jar`）。

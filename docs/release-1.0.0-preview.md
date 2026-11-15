# Localized Rain 1.0.0-preview（局部有雨）

- 发布日期：2026-11-15（第二次预览构建）
- 目标平台：Minecraft 1.21.1 / NeoForge 21.1.265+
- 许可：MIT
- 定位：在首个 `1.0.0-preview` 的基础上，加入**移动天气模式**（可漂移、可演化的风暴系统）并重做过滤器语义。
  内部迭代曾用 `1.1.0-preview` / `1.2.0-preview` 标记，现已全部回落到 `1.0.0-preview`，功能集合以本文件为准。

> ⚠️ **客户端与服务端必须使用同一版本**（网络协议 `"5"`）。`optional()` 只保证版本不一致时不断开连接，
> 但风暴同步会静默失效，表现为「服务端有风暴、客户端没雨」。服务端更换本模组版本后请同步更新客户端。

## 三种天气模式

`weatherMode` 三选一，默认 `patches`（切换需重启游戏）：

| 模式 | 说明 |
| --- | --- |
| `patches` | 3D 群系片天气：每个连通群系片独立循环晴/雨/雷或自定义天气 |
| `mobile` | 风暴系统：天气由「水平圆 + 竖直区间」的风暴承载，随维度风场漂移、按档案阶段演化 |
| `vanilla` | 完全不接管原版天气 |

三种模式严格互斥，存档各自保留，切换不迁移状态。

## 移动天气模式

- 风暴档案是数据包注册表 `localizedrain:storm_profile`，内置 `drizzle` / `shower` / `squall` / `thunderstorm` 四个档案；
  档案描述权重、自然生成开关、移动速度倍率、半径、竖直模式（`SURFACE` / `BAND` / `COLUMN`）、阶段与环境演化参数。
- 每个维度有一个风场（`windMinSpeed` / `windMaxSpeed` / `windChangeIntervalTicks`），每 1200 tick 做一次
  有界随机游走（方向 ±30°、速度 ±0.01），不会出现 180° 突变。
- 风暴按阶段推进：阶段决定天气类型、雨/雷强度、半径缩放；水分与温度决定推进速度、是否提前推进或消亡。
- 自然生成遵守天气过滤器：强制晴天、白名单池里没有该类型、黑名单剔除该类型时都不会生成；
  被强制成某一种天气的群系只生成该类型的风暴。隔离群系标签 `#localizedrain:storm_isolated` 是绝对生成禁区。
- **自然风暴只在「有天光且无天花板」的维度生成**（下界/末地不会长风暴）。其他维度仍可手动生成，但不会有雨雪可视效果。
- 自然风暴上限只统计自然风暴，手动风暴（命令/API）有独立上限 `maxManualStormsPerDimension`（默认 16）。
- 客户端由完整快照（`localizedrain:storm_sync`，每 40 tick 校正一次，生成/移除/换阶段立即推送）驱动，
  按「玩家距离 + 高度区间 + 隔离群系」计算目标雨/雷量并 0.01/tick 平滑写回原版通道；`isolatedBiomeWeather`
  与 `falloffRatio` 随快照下发，联机时以服务端为准。
- 消亡淡出固定 100 tick（`storm.StormTuning.FADE_TICKS`），快照携带剩余 tick，客户端自行平滑。
- 落雷：每片风暴每 tick 只抽一次签，密度不随玩家数放大；落点跟随一名被风暴完整覆盖（含 Y 区间）的玩家，
  不再在整个风暴盘内均匀取点；洞穴/柱状风暴统一走 `isThunderingAt`（含隔离群系判定）。
- 命令：`/weather storm list|spawn|remove|stage|advance|intensity|wind`；API 见 `WeatherApi` 的 mobile 段与
  `StormSpawnEvent`（可取消）/`StormStageChangeEvent`/`StormRemoveEvent`。

## 过滤器语义

三条名单统一写作 `<群系选择器>=<天气>`，选择器支持具体群系、`命名空间:*`、`#群系标签`，天气支持类型 id、
短名 `clear`/`rain`/`thunder` 与白/黑名单里的天气标签：

- **强制天气** `weatherBiomeOverrides`：命中群系永远只有这一种天气，多条命中取第一条。
  **强制晴天是硬锁定**：自然循环、命令、API、自然风暴都不能在该群系制造天气。
- **白名单** `weatherBiomeWhitelist`：命中的群系自然循环只在列出的天气里抽取；为空时使用默认自然池
  （`#localizedrain:natural` 标签，标签不存在则内置晴/雨/雷）。
- **黑名单** `weatherBiomeBlacklist`：命中的群系永远不会出现列出的天气，但仍在剩余天气里循环。
- 白名单/黑名单只约束自然循环：若某群系只是「自然池里只剩晴天」而恒晴，命令与 API 仍可手动制造天气。

默认四组名单均为空。下界、末地的原版群系 `Biome#hasPrecipitation()` 为 false，片模式会自动归为 `pinnedClear`，
不需要强制天气；移动模式下由「自然风暴维度守卫」保证不生成。

## 本版修复（相对首个 1.0.0-preview 构建）

1. 自然风暴生成点改用完整过滤判定（`WeatherBiomeFilter.stormAllowed`），不再无视白名单/黑名单与锁定晴天。
2. 命令/API 生成的手动风暴默认固定速度 0，不再被风场吹走；`StormSpawnSpec` 未显式给速度时统一在 `spawn` 内固定。
3. `data/` 下的风暴档案与群系标签已纳入仓库跟踪；clean clone 构建的 jar 包含四个 `storm_profile` 与
   `storm_isolated` 标签。`.trae/` 过程文档与 spark 采样不进入产物仓库。
4. 洞穴风暴的竖直区间夹到地表以下（`maxY = min(y + 24, surfaceY - 1)`），不会漏到地表下雨。
5. 手动风暴上限在 `StormManager.spawn` 内统一强制；`/weather storm global` 先预判「已有手动数 + 玩家数」。
6. 消亡淡出改为快照携带剩余 tick，客户端按 `remaining / FADE_TICKS` 平滑；协议版本 `"4"` → `"5"`
   （`"4"` 增加剩余 tick，`"5"` 增加配置同步）。
7. `dominantAt` 的坐标键改用 `BlockPos.asLong` 的 26/12/26 位布局，覆盖世界边界。
8. 区分硬锁定（强制晴天）与软锁定（自然池只剩晴天）：命令/API 可以覆盖软锁定，只有硬锁定完全禁止。
9. 文档补充两套 `natural` 概念的区别（档案的 `natural` vs `#localizedrain:natural` 标签）。
10. 客户端不再读取本地 `isolatedBiomeWeather` / `falloffRatio`：两个值随风暴快照下发，联机时以服务端为准。
11. `mobile` 模式下 `/weather clear` 清空该维度全部风暴（自然 + 手动）并只广播一次；睡觉跳夜仍只清自然风暴。
    服务器停止/换存档时清空风暴静态缓存，配置重载后复位「只警告一次」标志。
12. 落雷改为「每片风暴每 tick 抽一次签」，且落点跟随被覆盖玩家（视距内），可见雷击密度接近原版。
13. 自然风暴生成增加维度守卫（`hasSkyLight() && !hasCeiling()`），下界/末地不再依赖过滤器兜底。
14. 删除死代码 `StormManager.clearNaturalNearPlayers` / `nearAnyPlayer`。
15. 淡出常量下沉到 `storm.StormTuning.FADE_TICKS`，`StormObject` 不再反向引用 `network.StormSyncPayload`。
16. 无天光/带天花板维度里手动生成风暴时给出明确提示（无雨雪可视效果，但仍占名额并抬高维度雨量）。
17. 文档修正协议版本表述（`"4"` → `"5"`），并写明维度级 `isRaining()` 会带动任何依赖它的逻辑（含第三方附属模组）。

## 从旧版本升级

- 配置键名保持不变；新的 mobile 配置与旧 `patches` 配置可以共存，切换模式需重启。
- 存档：片天气存档版本不变；风暴存档为独立文件 `localizedrain_storms.dat`（`DATA_VERSION=1`，读到更高版本会忽略并告警）。
- 网络协议版本升到 `"5"`，客户端与服务端需同一版本；`optional()` 仍允许原版客户端进入，只是看不到天气同步。

## 已知限制

- 客户端雨雪粒子仍按玩家所在片/风暴的总体雨量渲染，不做逐方块局部化。
- 移动模式下按片 key 的 `WeatherApi.setWeather(level, patches, ...)` 不生效，请使用坐标入口或 `spawnStorm`。
- 自然风暴的生成点只检查锚点区块是否加载；移动路径上的未加载区块不会预加载。
- 地下 `BAND` 风暴的雨雪粒子是否被原版 `canSeeSky` 挡住仍待游戏内验证（服务端判定已支持洞穴下雨/落雷）。
- 移动模式仍把该维度所有风暴的最大雨/雷量写回 `level.setRainLevel/setThunderLevel`，所以 `ServerLevel#isRaining()`
  是维度级布尔。这是为原版维度级逻辑（天气循环、天空亮度、雨声/粒子通道）保留的设计取舍：任何依赖维度级
  `isRaining()`/`getRainLevel()` 的逻辑（包括第三方附属模组）都会被带动；`isRainingAt()` 已按位置重定向，不受影响。
- 维度守卫的副作用：自定义的「露天但无天光」维度（太空/虚空类模组）与带天花板的地下维度不会自然生成风暴，
  需要时只能手动生成。
- `BAND` 风暴的落点只在风暴区间内找空气，不保证正上方有开口；封闭洞穴中的落雷属于无效计算（无害）。
- 分层：`StormManager` 仍直接构造 `StormSyncPayload` 并发包；领域层与网络层尚未完全解耦（功能无影响，后续迭代处理）。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

产物：`build/libs/localizedrain-1.0.0-preview.jar`。

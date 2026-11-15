# Localized Rain 1.0.0-preview（局部有雨）

- 发布日期：2026-11-15（第二次预览构建）
- 目标平台：Minecraft 1.21.1 / NeoForge 21.1.265+
- 许可：MIT
- 定位：在首个 `1.0.0-preview` 的基础上，加入**移动天气模式**（可漂移、可演化的风暴系统）并重做过滤器语义。
  内部迭代曾用 `1.1.0-preview` / `1.2.0-preview` 标记，现已全部回落到 `1.0.0-preview`，功能集合以本文件为准。

> ⚠️ **客户端与服务端必须使用同一版本**（网络协议 `"6"`）。`optional()` 只保证版本不一致时不断开连接；
> 登录时服务端会检查 `localizedrain` 通道是否注册，缺失时向玩家发提示、并在服务端日志记录警告
> （修复了此前完全静默、只表现为「服务端有风暴、客户端没雨」的问题）。

## 三种天气模式

`weatherMode` 三选一，默认 `patches`（切换需重启游戏）：

| 模式 | 说明 |
| --- | --- |
| `patches` | 3D 群系片天气：每个连通群系片独立循环晴/雨/雷或自定义天气 |
| `mobile` | 风暴系统：天气由「水平圆 + 竖直区间」的风暴承载，随维度风场漂移、按档案阶段演化 |
| `vanilla` | 完全不接管原版天气 |

三种模式严格互斥，存档各自保留，切换不迁移状态。

## 移动天气模式

- 风暴档案是数据包注册表 `localizedrain:storm_profile`，内置五个档案：`drizzle` / `shower` / `squall` / `thunderstorm`（自然、`surface`）与 `cave_rain`（`band`、非自然，供无天光/带天花板维度的 `/weather rain` 使用）；
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
3. `data/` 下的风暴档案与群系标签已纳入仓库跟踪；clean clone 构建的 jar 包含五个 `storm_profile` 与
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
12. 落雷改为「每片风暴每 tick 抽一次签」，且落点跟随被覆盖玩家（视距内），可见雷击密度接近原版；
    落点先校验（区块已加载、可见天、隔离判定）再消耗抽签，无效落点不再白耗概率；覆盖玩家统计不再每 tick 分配列表。
13. 自然风暴生成增加维度守卫（`hasSkyLight() && !hasCeiling()`），下界/末地不再依赖过滤器兜底。
14. 删除死代码 `StormManager.clearNaturalNearPlayers` / `nearAnyPlayer`。
15. 淡出常量下沉到 `storm.StormTuning.FADE_TICKS`，`StormObject` 不再反向引用 `network.StormSyncPayload`。
16. 无天光/带天花板维度不再允许手动风暴：`StormManager.spawn` 直接拒绝，命令返回明确失败提示。
17. 不再把局部天气的最大值写回维度级 `rainLevel/thunderLevel`，`Level#isRaining()` 保持原版晴天，不再带动第三方全维度逻辑。
18. 落雷回到「先便宜掷骰、命中后才搜索落点」的成本模型：无效落点最多重试 3 轮，期望落雷率接近配置值，
    常态下每 tick 不再执行高度图/群系/隔离判定。
19. `StormEvolution` 的温度/水分改为每 4 tick 更新一次（增益与插值按 4 倍补偿），并复用同一 `Biome` 引用；
    风暴档案解析结果缓存在 `StormObject` 上，`/reload`、配置重载与服务器停止时清空。
20. 无天光/带天花板维度的提示改为「先成功消息、后提示」，单点与 `global` 两条命令路径一致。
21. `/reload` 时才清空风暴档案缓存；玩家登录不再触发重建（`OnDatapackSyncEvent` 只处理 `player == null`）。
22. 档案解析失败也记录为「已解析」（`profileResolved`），不再每 tick 回源；仍由 `/reload`、配置重载、停服清空。
23. 落雷的「外层最多 3 轮重掷、内层最多 4 次取点」两层结构写入注释与文档；`SURFACE` 落点增加 `minY` 剪枝。
24. 移动模式补回骷髅马陷阱：按原版难度概率生成骷髅马，并用 `visualOnly` 闪电代替伤害。
25. 维度级雨量彻底移除：片模式与移动模式都不再写 `level.setRainLevel/setThunderLevel`，`Level#isRaining()` 恒为原版晴天；
    依赖维度级判定的第三方逻辑不会再被局部天气带动（请改用 `WeatherApi` 的按位置查询）。
26. 片模式落雷同步补回骷髅马陷阱（维度级雷暴不再触发原版路径，陷阱由本模组按相同概率生成）。
27. 无天光/带天花板维度拒绝手动风暴（API 与命令一致），不再出现「只占名额、无视觉」的风暴。
28. 登录时显式检查 `localizedrain` 网络通道；版本不一致导致 `optional()` 静默失效时，玩家会收到提示、服务端记录警告。
29. 洞穴/`BAND` 落雷要求落点正上方有开口（`canSeeSky`），封闭洞穴不再产生无效落雷。
30. 领域层与网络层解耦：`StormManager` 只产出 `storm.StormSyncData`，通过 `storm.StormSyncSink` 发包；
    实现由 `network.StormSyncBroadcaster` 在启动时安装，领域层不再 import 任何网络类。
31. 修复维度级雨量移除带来的回归：`ServerLevel#tickPrecipitation` 内的 `isRaining()` 调用点改为
    `@Redirect` → `WeatherManager.isPrecipitatingAt(level, pos)`，积雪（`shouldSnow`）与炼药锅集水按区域恢复；
    `Level#isRaining()` 对第三方仍保持晴天。
32. 无天光维度的维度守卫只约束 `SURFACE` 风暴；`BAND`/`COLUMN` 洞穴风暴在带天花板维度仍可手动生成。
33. `/weather rain` 在无天光维度不再静默失败：`/weather` 转发链路会给维度内玩家发一条失败提示。
34. 客户端按天气类型能力位渲染：风暴快照增加 `hasPrecipitation`/`allowsThunder`（协议 `"5"` → `"6"`），
    非降水类型不再渲染雨，禁止雷暴的类型不再渲染雷。
35. 落雷逻辑抽成 `weather.WeatherLightning.strike`，片模式与移动模式共用（含骷髅马陷阱）；纯地下 `BAND` 风暴
    因要求 `canSeeSky` 不落雷，属于「洞穴风暴只下雨、不落雷」的既定语义。
36. 新增内置 `BAND` 档案 `cave_rain`（`natural:false`，半径 48–96），
    使 `/weather rain` 在无天光/带天花板维度也能生成本地洞穴风暴，而不是只提示后放弃；
    不提供洞穴雷暴档案（`cave_thunder` 已移除）：`BAND` 落雷要求 `canSeeSky`，下界/带天花板维度恒不落雷，
    保留雷暴档案只会让 HUD 与雷声显示一场永远不会打雷的「雷暴」；`/weather thunder` 在该类维度直接提示不支持。
37. `BAND` 档案未显式给竖直区间时，以生成点 Y 为中心补默认带（半高 48，夹到世界高度），
    不再退化成「基岩到建造上限」的整列雨。
38. 手动风暴达到上限被截断时，`/weather` 转发路径会给维度内玩家发 `partial_spawned` 提示，不再静默少生成。
39. `tickPrecipitation` 的原始坐标兜底只在移动模式启用：片模式下洞片段降水不会再让地表积雪或炼药锅集水。
40. 睡觉清天气恢复：`ServerLevel.tick` 睡眠门控的维度级 `isRaining()` 重定向为「原版雨 || 本地任意降水」，
    `resetWeatherCycle` 注入因此可达；`patches` 用 `WeatherData.patches()`、`mobile` 用风暴强度。
41. 跨维度补发风暴快照前检查网络通道；`allowsManualStorms` 注释纠正为「只约束 SURFACE」。
42. 自然洞穴风暴只从「第一阶段类型为降雨」的档案中抽取（`StormProfiles.randomNatural(..., filter)`），
    开启 `naturalCaveStorms` 后不会再出现「有雷声、永不落雷」的洞穴雷暴。
43. `/weather rain|thunder` 生成的手动风暴不再强制 512 半径：改用档案自己的 `radius` 区间（`cave_rain` 为 48–96）；
    `vanillaCommandStormRadius` 配置保留为 `/weather storm global` 未指定半径时的默认值。
44. 文档与发布说明的档案数量改为 5（新增 `cave_rain`），README 命令段补齐该档案。
45. 无天光/带天花板维度的非 `SURFACE` 雷暴直接拒绝：`StormManager.spawn` 与命令一致返回失败
    （`thunder_unavailable`），`/weather storm spawn thunderstorm ... band` 不再能生成「有雷声、永不落雷」的风暴。
36. 修正 `tickPrecipitation` 重定向的坐标：原版入参是 `tickChunk` 的随机方块坐标（y 恒为 0–15），
    重定向内先换算 `getHeightmapPos(MOTION_BLOCKING, pos)` 再判定，原始坐标仅作 `BAND` 洞穴风暴的兜底；
    积雪与炼药锅集水恢复（此前直接用原始坐标，`SURFACE` 风暴与地表片都恒为 false）。
37. 无天光/带天花板维度的 `/weather rain` 优先选用 `BAND` 档案（`cave_rain`）；`/weather thunder` 没有可用档案，
    直接提示并放弃（洞穴不落雷，见第 36 条）。
38. HUD 不再把「非降水 / 禁止雷暴」的类型显示为下雨/打雷：`StormSyncData.Hud` 的 `raining`/`thundering`
    按天气类型能力位计算。
39. 登录处理改为「先检查网络通道、再补发风暴快照」；版本不一致时不会再先发包再提示。

## 从旧版本升级

- 配置键名保持不变；新的 mobile 配置与旧 `patches` 配置可以共存，切换模式需重启。
- 存档：片天气存档版本不变；风暴存档为独立文件 `localizedrain_storms.dat`（`DATA_VERSION=1`，读到更高版本会忽略并告警）。
- 网络协议版本升到 `"6"`，客户端与服务端需同一版本；`optional()` 仍允许原版客户端进入，登录时会提示未安装同版本模组。
- 本模组不写回维度级 `rainLevel/thunderLevel`：依赖 `Level#isRaining()`/`getRainLevel()` 的第三方逻辑会看到原版晴天，
  需要局地天气请改用 `WeatherApi` 的按位置查询。原版的积雪与炼药锅集水已通过 `tickPrecipitation` 内的重定向按区域恢复。

## 已知限制

- 客户端雨雪粒子仍按玩家所在片/风暴的总体雨量渲染，不做逐方块局部化。
- 移动模式下按片 key 的 `WeatherApi.setWeather(level, patches, ...)` 不生效，请使用坐标入口或 `spawnStorm`。
- 自然风暴的生成点只检查锚点区块是否加载；移动路径上的未加载区块不会预加载。
- `ServerLevel#isRaining()/getRainLevel()` 不再反映局部天气（保持原版晴天）。这是有意的设计取舍：避免第三方逻辑被
  局地天气全维度带动；需要局地雨雪请用 `WeatherApi` 的按位置查询（`isRainingAt` / `isPrecipitatingAt` / `typeAt` /
  `thunderLevelAt` 等）。原版「下雨才触发」的全局效果（含部分第三方模组）因此不会触发。
- 维度守卫：自定义的「露天但无天光」维度（太空/虚空类模组）与带天花板的地下维度不会**自然**生成风暴；
  `SURFACE` 手动风暴也会被拒绝，但 `BAND`/`COLUMN` 洞穴风暴仍可手动生成：
  `/weather storm spawn <profile> <radius> band <minY> <maxY>`（该子命令会显式设置竖直区间）；
  无天光维度的 `/weather rain|thunder` 也会优先选用 `BAND`/`COLUMN` 档案。
- 非 `SURFACE` 的雷暴类型在无天光/带天花板维度被直接拒绝（命令返回 `thunder_unavailable`）：
  洞穴风暴永远不会落雷，`/weather storm spawn thunderstorm ... band` 也不例外。
- 地下 `BAND`/`COLUMN` 风暴只下雨、不落雷：落雷要求落点正上方有开口（`canSeeSky`），完全封闭的洞穴不会落雷
  （与「洞穴风暴不经过原版见天检查」的设计一致，避免无效计算）。
- 片模式与移动模式的落雷都按原版难度概率补回了骷髅马陷阱（`visualOnly` 闪电代替伤害）。
- 停服/换存档会清空风暴静态缓存；配置重载会复位一次性警告标志。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

产物：`build/libs/localizedrain-1.0.0-preview.jar`。

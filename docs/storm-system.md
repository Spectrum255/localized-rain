# 移动天气系统（mobile 模式）

> 内部实现参考。对外发布说明见 `docs/release-1.2.0-preview.md`。

本文描述 `weatherMode = "mobile"` 下的风暴系统：数据模型、推进流程、客户端同步与已知限制。
它和「3D 群系片」（`patches`）、「完全原版」（`vanilla`）互斥，同一时刻只有一套系统决定天气。

## 1. 数据与注册

| 内容 | 位置 |
|---|---|
| 维度风暴存档 | `StormData`，`<world>/data/localizedrain_storms.dat`（`DATA_VERSION = 1`） |
| 单个风暴 | `StormObject`（包私有），随存档序列化 |
| 维度风场 | `WindField`（所有自然风暴共享），随 `StormData` 保存 |
| 风暴档案 | 数据包注册表 `localizedrain:storm_profile`，`data/<namespace>/storm_profile/<name>.json` |
| 群系隔离标签 | 群系标签 `#localizedrain:storm_isolated`（空标签默认不隔离任何群系） |

内置档案：`drizzle` / `shower` / `squall` / `thunderstorm`。

档案字段：`weight`、`natural`、`speed_multiplier`、`radius`（`[min,max]`）、`vertical`
（`SURFACE` / `BAND` / `COLUMN`）、`stages[]`、`environment`。每个阶段有类型、持续 tick 区间、
雨/雷强度区间、半径缩放、`advance_when_moisture` / `die_when_moisture` 阈值。

## 2. 空间覆盖

一个风暴 = 水平圆（`x/z/radius`）+ 竖直区间（`minY/maxY`）。

- `SURFACE`：生成时取地表高度 −16 到最高建筑高度；查询时还要求见天、高度图不高于查询点、群系降水类型为雨。
- `BAND`：显式竖直区间，不看天，用于洞穴/高空。
- `COLUMN`：从最低到最高建筑高度。

群系在 `#localizedrain:storm_isolated` 里时，该风暴不产生雨/雷/闪电，也不压暗天空。
`isolatedBiomeWeather` 决定这些群系用什么天气：`clear`（默认）/ `patch` / `manual`。

## 3. 推进流程

`ServerLevel.advanceWeatherCycle` 被取消，改由 `WeatherManager.tick` 按 `weatherMode` 分派：

```
WeatherManager.tick
  └─ mobile: tickMobile
       ├─ 可选 tickIsolatedPatches（isolatedBiomeWeather = patch）
       └─ StormManager.tick(level)
            ├─ data.wind().tick(level)            风场有界随机游走
            ├─ 逐风暴：远离淡出 / StormEvolution / 阶段切换 / StormObject.tick（移动、强度、寿命）
            ├─ 变更或到达同步间隔 → broadcast(全量快照)
            ├─ 维度级 rain/thunder 取所有风暴最大值写回 level
            ├─ spawnLightning（按玩家 × 风暴抽签，BAND 也支持）
            ├─ sendHud（HUD 用 WeatherStatePayload）
            └─ StormSpawner.tick（自然生成，受维度/玩家上限约束）
```

手动风暴（命令/API 生成）速度固定为 0，不随风漂移；自然风暴由风场推动。
自然风暴离所有玩家超过 `despawnDistance` 并持续 `despawnGraceTicks` 后淡出；阶段走完也会淡出。

## 4. 客户端同步

- `StormSyncPayload`（`localizedrain:storm_sync`）是全量快照：生成/移除/换阶段立即推送，
  另外每 `stormSyncIntervalTicks`（默认 40）校正一次。
- `ClientStormCache` 保存快照，位置向权威值 lerp 0.5，tick 之间按速度外推。
- `ClientStormWeather` 按玩家到风暴的距离/高度/隔离计算目标雨雷量，0.01/tick 逼近后写回
  `setRainLevel` / `setThunderLevel`；**只要收到过快照就工作，不再依赖本地 `weatherMode`**，
  本地配置与服务端不一致时会记一次警告并以服务端为准。
- HUD / Xaero 行仍走 `WeatherStatePayload`（`raining` / `thundering` / `typeKey`）。

## 5. 配置（仅 mobile 有效）

见 README 的「移动天气」表。1.2.0-preview 新增：

- `maxManualStormsPerDimension`（默认 16，范围 0–128）：手动风暴按维度计数，0 表示禁用；
  对同一片区域重复设置同类型天气会替换旧风暴。
- `maxStormsPerDimension` / `maxStormsPerPlayer` 现在只统计自然风暴。

## 6. 命令与 API

命令：`/weather storm list|spawn|remove|stage|advance|intensity|wind|at|area|global`（权限 2）。
`/weather clear|rain|thunder` 在 mobile 下也会路由到风暴系统。

API：`WeatherApi.spawnStorm` / `removeStorms` / `storms` / `dominantStormAt` / `wind` 等；
事件 `StormSpawnEvent`（可取消）、`StormRemoveEvent`（`MANUAL` / `FINISHED` / `FAR_AWAY` /
`MISSING_PROFILE` / `CLEARED`）、`StormStageChangeEvent`。

## 7. 1.2.0-preview 修复内容

1. 原版 `setWeatherParameters` / 手动天气不再无限堆积风暴：新增手动上限，并按「玩家附近同类型替换」去重。
2. `/weather clear`、睡觉跳夜、原版清天气统一走 `removeWhere(..., CLEARED, true)`：发事件并立即广播，
   不再留下客户端幽灵风暴。
3. 客户端不再依赖本地 `weatherMode` 判断是否渲染风暴：收到快照即按服务端模式工作，避免配置不一致时无雨。
4. `typeOverride` 在广播前写入，客户端 HUD/渲染第一帧就是正确类型。
5. 手动风暴固定速度 0，不再漂走。
6. 落雷补上 `hasChunk` 守卫；`BAND`/`COLUMN` 风暴在竖直区间内取点并做空气检查，洞穴风暴可以落雷。
7. 自然风暴上限只统计自然风暴；`StormData` 载入时检查版本，版本过新则忽略旧数据而不是误读。
8. 风场改为有界随机游走（±30°、±0.01），不再每次重新掷 180°。
9. 生成时显式指定半径的手动风暴，阶段切换不再被重置为档案半径。
10. `dominantAt` 增加 quart 级缓存（每 tick 清空，上限 65536），降低 `tickPrecipitation` 注入点的重复遍历。
11. 自然风暴生成点改用 `WeatherBiomeFilter.stormAllowed`：完整遵守强制天气、锁定晴天与白/黑名单。
12. 手动风暴默认固定速度 0（在 `spawn` 内统一处理，命令/API 未显式给速度时不会被风场吹走）。
13. 手动上限在 `spawn` 内统一强制；`/weather storm global` 预判「已有手动数 + 玩家数」，超限给出明确提示。
14. 洞穴风暴竖直区间夹到 `surfaceY - 1`，不会漏到地表。
15. 消亡淡出改为快照携带剩余 tick（协议 `"4"`），客户端按 `remaining / 100` 平滑。
16. `dominantAt` 的坐标键改用 `BlockPos.asLong`（26/12/26 位），覆盖世界边界。
17. `Decision` 区分硬锁定（强制晴天）与软锁定（自然池只剩晴天）：命令/API 可覆盖软锁定，硬锁定完全禁止。
18. 快照尾部携带服务端的 `isolatedBiomeWeather` 与 `falloffRatio`（协议 `"5"`），客户端不再读本地 COMMON 配置。
19. `mobile` 模式下 `/weather clear` 清空该维度全部风暴（自然 + 手动，`CLEARED`，只广播一次）；睡觉跳夜仍只清自然风暴。
20. `ServerStoppedEvent` 清空 `CACHE`/`LAST_SYNC`/`LAST_HUD`/`DOMINANT_CACHE` 与警告标志；配置重载复位手动上限警告。
21. `spawn` 在生成时即设置阶段 `stageScale`，首帧快照的 `radiusAt()` 不再比后续 tick 小一个阶段比例。

## 8. 已知限制

- 覆盖判定是「水平圆 + Y 区间」，不是真实体积；风暴边缘的雨量按 `falloffRatio` 线性衰减。
- 客户端只能看到已同步的风暴：超出同步范围（例如刚登录、跨维度）会有最多一个同步间隔的延迟。
- `SURFACE` 风暴的降水判定依赖见天与高度图；`BAND`/`COLUMN` 不做见天判定，洞穴内也会下雨。
- 自然风暴的生成点只检查锚点区块是否加载；风暴移动路径上的未加载区块不会预先加载。
- 地下 `BAND` 风暴的雨雪**粒子**是否被原版 `canSeeSky` 挡住仍需游戏内验证（服务端判定已支持洞穴下雨/落雷）。
- 风暴系统与片天气是两套存档；切换模式不迁移状态，切回时各自继续。

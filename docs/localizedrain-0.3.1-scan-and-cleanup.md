# 0.3.1：洞穴/浮岛 3D 子状态扫描与存档清理

## 背景

0.3.0 把天气推进范围从「玩家所在片」扩展到「所有已加载区块对应的片」，但每个区块只按中心列的地表位置
判定一次，因此只存在于洞穴、浮岛、平流层等高度的 3D 子状态不会被主动推进。同时，已加载区块越多、探索越广，
`localizedrain_weather` 里的条目只增不减。0.3.1 处理这两点，并补上扫描间隔配置的说明。

## 配置

| 配置项 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- |
| `weatherSimulationIntervalTicks` | 20 | 1–200 | 已加载区块的扫描间隔（0.3.0 已有）。每次扫描把每片推进「间隔」个 tick，总速率不变。 |
| `scan3dSubstates` | true | 布尔 | 是否扫描每个非空 section 的 3D 群系子状态（洞穴/浮岛等）。 |
| `stateRetentionDays` | 7 | 0–365 | 超过这么多**游戏内天**没有被推进的片会被删除；0 = 不按时间清理（条数上限仍生效）。 |
| `maxStoredStates` | 4096 | 256–65536 | 每个维度的天气状态条数上限；超出时按最近推进时间从旧到新淘汰。 |

游戏内一天 = 24000 tick（约 20 分钟现实时间）。

## 3D 子状态扫描

在 `WeatherManager.sweep` 的逐区块循环里，先按中心列地表位置判定一次所属片，然后（`scan3dSubstates` 为真时）：

1. 取该区块已加载的 `LevelChunk`（`getChunkNow`，不会加载区块）；
2. 遍历 `LevelChunkSection[]`，跳过空 section（`hasOnlyAir()`）；
3. 跳过已经按地表判过的那个 section；
4. 采样点取 `((chunkX << 4) + 8, (sectionY << 4) + 8, (chunkZ << 4) + 8)`，即 section 中心；
5. 用 `BiomePatches.patchAtBudgeted` 得到「地表轮廓片 + 实际群系」子状态，再用 `cachedPatchAt` 复核，
   预算用尽时返回的临时回退键会被跳过，下一轮重试；
6. 按同一套分类（强制天气 / 排除 / 正常循环）并入本次扫描的片集合，同一片只推进一次。

因此 `stateOrNull` 与扫描推进对「同一列不同高度可能是不同群系」的判定是一致的：实际群系读已加载区块的
群系调色板，模组在区块生成后改写的群系（例如 Meteoros Protocol 的平流层）也能识别。

### 性能

视距 10 时约 441 个区块；普通地形每个区块非空 section 约 10–15 个，因此每轮新增约 4k–6k 次 section 采样。
每次采样主要是区块调色板查询 + 一次群系 id 比较，外加一次 patch 字符串比较；同一片只推进一次。
估算每轮 1–4 ms（默认 1 秒一次），可以接受。关闭 `scan3dSubstates` 则回到 0.3.0 的水平（只扫地表列）。
把 `weatherSimulationIntervalTicks` 调大（例如 40–100）可以进一步降低开销，代价只是天气切换的响应粒度变粗。

## 存档清理

- 存档版本升到 **7**：每个状态新增 `last_tick`（最近一次被推进的游戏时间）。
- `WeatherManager` 每 1200 tick 调用一次 `WeatherData.prune(gameTime, maxStoredStates, retentionTicks)`：
  1. `retentionTicks > 0` 时删除 `gameTime - lastTick > retentionTicks` 的片；
  2. 仍然超过 `maxStoredStates` 时，按 `lastTick` 升序删除最旧的条目；
  3. 只有真的删除了条目才 `setDirty()`。
- 旧存档（v6 及之前）没有 `last_tick`，读出来是 `0`：这些条目**不会**被时间清理误删，只受条数上限约束；
  一旦被推进就会写入新的 `last_tick`。
- 被清理的片没有持久天气记忆：再次加载时会以 `WeatherState.create` 的新晴天周期开始。
- 清理只影响存档条目，不影响已加载区块的当前推进；下一次扫描会按需重建状态。

## 兼容性

- 存档 v6 → v7 自动兼容：旧条目保留，`last_tick` 缺省 0。
- 配置默认值保持 0.3.0 的行为，除了新增的 3D 扫描（默认开启）与清理（默认 7 天 / 4096 条）。
- 命令、天气类型注册表、`WeatherApi` 均未变。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

- 构建：`BUILD SUCCESSFUL in 29s`。
- 产物：`build/libs/localizedrain-0.3.1.jar`，87,466 字节，
  SHA256 `A8B2E2D7184589C3B2227BDC48DBF241F7DFE50D17FA18C0969C7E2C50C58572`。
- jar 内 `version="0.3.1"`、`modId="localizedrain"`、`license="MIT"`；语言文件各 33 键；
  `weather/LoadedChunks.class`、`weather/WeatherData$WeatherState.class` 均在包内。

## 未验证

- 没有启动客户端/专用服务器：洞穴/浮岛扫描的实际归属、清理触发时机、存档 v7 读写都只做了构建验证。
- 性能数字是按区块/section 数量估算的，未在真实存档上测量。
- 清理阈值（7 天 / 4096 条）是保守默认值，长期探索大型整合包时可以按需调整。

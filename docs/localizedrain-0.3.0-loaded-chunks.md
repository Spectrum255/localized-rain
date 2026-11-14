# Localized rain 0.3.0：已加载区块的群系片同时推进

## 背景

0.2.x 的天气循环只推进「玩家所在的那一片」：`WeatherManager.tick` 遍历 `level.players()`，
用玩家脚下位置算出群系片，只对这些片调用 `WeatherData.tick`。好处是开销 O(玩家数)，
坏处是玩家看不到的地方天气不会变化——远处已加载区块里不会下雨、不会积雪，雷暴也不会落雷。

0.3.0 把推进范围扩大到**当前维度所有已加载区块**，判定单位仍然是「连通群系轮廓片」（同类型但不相连的群系各自有天气）。
未加载 / 已卸载的片不推进，状态保留，重新加载后继续，而不是卸载即恢复晴天。

## 设计

### 1. 已加载区块集合

`weather/LoadedChunks.java` 用 NeoForge 的 `ChunkEvent.Load` / `ChunkEvent.Unload` 维护
`Map<ServerLevel, LongOpenHashSet>`（弱引用，维度卸载时清空）。

没有用 `ChunkMap#getChunks()`：它在 1.21.1 是 `protected`，直接调用要么加 accessor、要么混入；
事件维护的集合不需要任何反射或内部 API，且只在主线程读写。

### 2. 扫描间隔与批量推进

新增配置 `weatherSimulationIntervalTicks`（默认 20，即 1 秒；范围 1–200）。

每个维度有一个 tick 计数器，累计到间隔时执行一次 `sweep`。`sweep` 对每个片调用
`WeatherData.WeatherState.tick` **间隔次**，所以计时器与 0.01/tick 的雨量/雷量渐变总速率和「每 tick 推进」完全一致，
只是把推进粒度放粗到 1 秒。天气切换最多晚 1 秒生效；客户端仍然每 tick 按 0.01 平滑，所以画面上的渐变不变。

### 3. 每区块的归属判定

每个已加载区块取中心列 `(chunkX*16+8, chunkZ*16+8)`，用 `MOTION_BLOCKING` 高度图取地表位置，
再 `BiomePatches.patchAtBudgeted` 得到该位置所属的群系片（含 3D 子状态）。同一片只推进一次。

- 轮廓填充仍受 `BiomePatches` 的预算限制（每 tick 最多 2 片 / 4ms）。预算用尽时
  `patchAtBudgeted` 返回临时的 `g:` 回退键，`sweep` 会跳过它（用 `cachedPatchAt` 核对），
  等下一次扫描再算，避免把错误归属推进到状态里。
- 扫描前会再用 `level.getChunkSource().hasChunk` 确认区块仍在加载状态。
- 玩家所在片始终加入，即使对应区块还没有被扫描到。

### 4. 分类

对每个片做与原来相同的分类：

- 被强制天气（`weatherBiomeOverrides`）命中 → 记为 `forced`，每轮扫描先把旗标压到目标类型，
  再按 `cycleWeather = false` 推进，避免随机循环覆盖配置；
- 名单排除，或群系本身 `has_precipitation = false` → 记为 `pinnedClear`，只让已经存在的状态淡出；
- 其余 → `cycling`，按天气规则随机循环。

### 5. 维度级雨量

扫描后对所有被推进的片取 `maximumRain` / `maximumThunder`，写回 `level.setRainLevel` / `setThunderLevel`，
供原版刷怪、天空亮度等维度级判定使用。每玩家同步（`send`）仍按玩家自己所在片，每 tick 平滑。

### 6. 闪电（v1 不变）

`spawnLightning` 仍只围绕玩家抽签：远处雷暴片不会在玩家视野外落雷。这是 v1 的取舍；
后续可以按雷暴片自身覆盖的已加载区块密度补充远处落雷。

## 配置

```toml
weatherSimulationIntervalTicks = 20
```

- 20 = 每秒一次（默认）
- 1 = 每 tick 扫描（最细腻，扫描次数 ×20）
- 200 = 每 10 秒一次（最省，但天气切换最多晚 10 秒）

## 行为与兼容

- 天气单位、存档 key、`weatherBiomeOverrides` 语义、`WeatherApi`、payload 协议均不变；
- 存档仍是 v6，不需要迁移；
- 被扫描到的片会创建存档条目，存档条目会随探索比 0.2.x 增长更快。v1 还没有清理机制，
  后续可给 `WeatherState` 加 `lastTick` 并在加载时清理长期未推进的片（存档 v7）；
- 只在洞穴/浮岛等 3D 子状态里出现的片不会被扫描推进（扫描按地表位置归属），
  这些子状态仍在玩家/实体查询时懒创建。

## 性能估算

视距 10、单个维度约 441 个已加载区块时：

- 每轮扫描：441 次 `hasChunk` + 高度图 + 群系片缓存查询，约 0.1–0.3ms；
- 群系片通常 20–80 个，每片推进 20 个 tick，纯浮点/计时器运算，微秒级；
- 真正的主线程风险是世界刚加载时新片轮廓填充集中：仍由 2 片 / 4ms 的预算摊到多个 tick。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

产物：`build/libs/localizedrain-0.3.0.jar`（86,440 字节，
SHA256 `94076E7CB630BAAB7B5C36613705B08D80EB1B77E6B741C4DF6BE0C68C123F36`）。

## 未验证

没有启动客户端/专用服务器做运行期验证。以下项目需要在游戏内实测：

- 远处已加载区块是否正常下雨/积雪，卸载后重新加载是否延续状态；
- 扫描间隔 1 / 20 / 200 下的观感与卡顿；
- 大量区块加载（出生点、强制加载区块）时的主线程占用；
- 存档增长的速度。

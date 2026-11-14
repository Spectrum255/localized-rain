# 0.4.5 优化：数据正确性、性能与一致性

0.4.4 引入了 3D 列段并查集、候选天气与无天光维度参与。本轮针对其审计出的问题做优化，
不改变「一个 3D 连通群系段 = 一个天气单位」的模型，也不改客户端雨雪粒子/雨声/雾的渲染路径。

## 数据正确性

### 旧 key 载入即丢弃

`WeatherData.load` 现在只接受 `p3:<colX>,<colZ>,<startY>` 形式的 key；`p:`、`g:`、`|biome`
以及 1.3.0 的层化 key 直接跳过。此前 `load` 不读 `version`，旧 key 会作为孤儿条目永久留在存档里，
而按 `lastTick` 的清理又永远碰不到它们。

### 并查集父表治理

- `load` 结束后调用 `sanitizeParents()`：沿父链走不到终点的节点（损坏/成环）整条删除。
- `canonicalSegment` 在 128 步保护触发时返回 `null`，不再把错误节点当成根。
- `prune(...)` 同时调用 `pruneParents(maxEntries)`：
  - `segmentParents` 超过 `max(1024, maxEntries * 4)` 时，删除「自身与父节点都没有天气状态」的条目；
  - `fallbackParents` 已经没有任何 `g:` 状态时整体清空。

### 删除死配置

`maxPatchCells` 在 3D 模型下没有任何使用点（`patchSize()` 已固定返回 0），本轮删除配置项与语言键。
旧 TOML 里残留的 `maxPatchCells` 会被 NeoForge 忽略。

### 坐标 API 兼容

0.4.0 起内部改用 quart 采样，`patchAtCell` 的参数也变成了 quart，附属模组按 16×16 列/区块坐标传参会
得到放大的片（水平偏 4 倍、下界只解析一个段）。本轮：

- 原 quart 版本改名 `patchAtQuart(level, quartX, quartZ[, budget])`（0.4.6 起；`patchAtQuartCell` 不再保留）；
- `patchAtCell(level, cellX, cellZ[, budget])` 恢复为 16×16 列语义，内部 `cell << 2` 换算，并加注释；
- `/weather area` 改用 `patchAtQuart`（步长 32 quart = 128 方块，与旧版一致）；
- `WeatherApi.setWeatherAt(level, pos, typeId, duration)` 新增坐标版写入入口；
- `WeatherManager.resolveState`（`typeAt` 的冷路径）改为单列按需解析、不受每 tick 扫描预算限制，
  避免附属模组在预算耗尽时短时读到晴天。

## 性能

### 扫描调度

- 整列扫描一次即可得到该列所有段（含洞穴/浮岛），删除了 sweep 里按 section 逐点采样 3D 子状态的循环，
  它与 `columnPatchKeys` 的工作完全重复。
- `scan3dSubstates = true` 时走 `ensureColumn` + `forEachColumnPatch`：不再计算地表高度图，
  也不创建列段 key 的临时 `HashSet`；关闭时只按地表位置分类一段。
- 扫描期间的分类集合改为复用字段（`SWEEP_CYCLING` / `SWEEP_FORCED` / `SWEEP_PINNED_CLEAR` /
  `SWEEP_CANDIDATES` / `SWEEP_HELD`），每轮只 `clear()`。
- `classify` 只取一次 `WeatherBiomeFilter.decision(...)`，不再分别调用 `forcedWeather`/`candidates`/`allows`
  重复查三次缓存。

### 状态推进

- `WeatherData.sweep` 不再对 cycling/held 各循环 `ticks` 次；`WeatherData.tick(..., steps)` 一次推进 N tick。
- `WeatherState` 在「计时器在未来 N tick 内不会翻转且未冻结」时使用闭式快进：
  计时器直接减去 N，雨/雷量按 `0.01 × N` 一次钳制到位；否则退回逐 tick 循环。
  候选模式的类型抽取仍按原来的时机发生（只在会翻转时走慢路径）。

### 缓存

- 列缓存 `COLUMNS` 改为按访问顺序的 LRU（上限仍为 200,000），删除超过上限时整体 `clear()` 的集中重算。
- 名单判定缓存 `DECISIONS` 改为 LRU（上限 200,000）。
- 天气查询缓存 `LOOKUPS.cells` 改为 LRU（上限 400,000 quart 格），删除达到上限时整体清空。
- `PATCH_BIOMES` 增加 200,000 条上限，超过时清空（缺失时可按列段/采样重建）。

### 其它

- `resolveColumn` 不再在每次查询时重复 `register`（列扫描时 `registerColumn` 已经注册过全部段）；
  注册仍然只发生在扫描/合并时。

## 一致性

- 补齐 3 个配置项的英文/中文语言键：`maxColumnScansPerTick`、`debugLogging`、`weatherBiomeCandidates`。
- README 重写为 3D 列段模型，补充候选天气、无天光维度、清理、坐标 API 与已知限制。
- `WeatherApi` 类 javadoc 与 `typeAt` 说明同步为「缓存 + 受预算的现场解析」。

## 兼容性与未验证项

- 存档：旧 key 丢弃、段父表清理；`DATA_VERSION` 保持 9（没有新增持久化字段）。
- 旧 TOML：`maxPatchCells` 被忽略。
- 依赖既有 0.4.x 代码：`WeatherState` 字段与存档格式未变。
- **本轮未做运行期验证**：没有启动客户端/专用服务器，只做了编译与构建验证。
  需要实测：旧存档载入（旧 key 清理）、洞穴/浮岛 3D 归属、候选天气切换、清理触发、
  扫描间隔 1/20/200 的 CPU 占用与手感、附属模组按区块坐标调用 `patchAtCell`。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

产物：`build/libs/localizedrain-0.4.5.jar`。

# Localized rain 0.4.12：父表背压、逐出配额与扫描预算上限

0.4.12 处理 2026-11-14 复核里提出的 8 点问题（1–7 改代码，8 补文档）。

## 1. 巨型连通分量：父表背压（点 1）

0.4.11 的第三遍只删「没有被别的条目引用、也没有天气状态」的节点。如果整个分量是一条长链或大网
（超大海洋、连续洞穴系统），除根以外每个节点都被引用，`removable` 为空，循环立刻 `break`，
之后**每次** `prune`（每 1200 tick 一次）仍会把父表全量解析一遍。

现在：

- 收缩失败后记录当时的规模 `parentPruneBlockedAt`。
- 只有父表继续增长 `max(2048, limit / 8)` 条以上（或回落到上限内）才重新尝试。
- 背压期间 `pruneParents` 直接返回，不做任何遍历。

这不会让父表无限增长（增长到阈值后仍会重试），但把「每轮全表重扫」降为「每增长 1/8 才扫一次」。

## 2. 逐出配额（点 2）

第三遍批量 `evictColumn` 可能一次逐出上千列，下一轮扫描只有几百列的预算，恢复期出现短期卡顿。

- 单轮最多逐出 `MAX_EVICTIONS_PER_PRUNE = 256` 列（含多轮收缩累计），达到配额就停止，
  剩余部分留到下一次清理。被逐出的列在下一次扫描时重新注册/合并。

## 3. 低于上限也清理坏链（点 3）

0.4.11 在 `size <= limit` 时直接返回，坏链/悬空链清理被跳过。

- 第二遍（常规清理 + 删除损坏链的未引用节点）现在**每次都跑**。
- 第一遍（对全表逐个做环检测）仍只在超上限时跑：低于上限时父表至多 `limit` 条，
  第二遍的 `resolveRootMemo` 已能发现并删除「没有被引用」的损坏节点；
  只有「损坏且被引用」的节点会保留到下一次超限清理。

## 4. 读档时清理无状态父表（点 4）

- `load` 读完 `segment_parents` 后，如果没有任何 `p3:` 天气状态（旧存档、状态全部过期），
  直接清空父表，避免无状态的父表一直留到硬上限。

## 5. 扫描预算封顶（点 5）

`beginSweep` 按扫描间隔放大预算（`maxColumnScansPerTick * ticks`），默认 20 tick 时是 80 列 / 40ms，
冷启动单轮补列可能造成 MSPT 尖峰。

- 列数封顶 `MAX_SWEEP_SCANS = 64`，耗时封顶 `MAX_SWEEP_NANOS = 4ms`。
- 代价：冷启动补满同样的列数需要更多轮扫描（默认 441 列时约 7 轮扫描周期），
  但每轮最多冻结主线程约 4ms，不再出现单轮数十毫秒的停顿。

## 6. 父表上限与状态上限的比例（点 6）

- `maxParentEntries` 默认从 32768 降到 **16384**，与 `maxStoredStates`（4096）保持约 4× 的比例，
  减少大世界探索后触发第三遍批量收缩的频率。
- **已存在的配置文件不会被改写**：老存档的 `config/localizedrain-common.toml` 里显式写着的
  `maxParentEntries = 32768` 会保留，只有新建的配置文件使用新默认值。需要生效请手动改这一行。

## 7. 警告标志拆分（点 7）

- `warnedChainGuard`：父链成环/损坏的警告（`canonicalSegment` 隔离坏链时）。
- `warnedParentLimit`：父表收缩后仍超硬上限的警告。
- 两者不再互相吞掉。

## 8. 兼容性说明（点 8）

- `BiomePatches.patchSize` 已标记 `@Deprecated`，固定返回 0（3D 段模型没有「水平格数」概念）。
- 旧存档的 `p:` / `g:` / `p:<layer>:` / `|biome` 子状态 key 从 0.4.5 起载入即丢弃；
  `BiomePatches.patchBiome` 对非 `p3:` key 返回 `null`。
- 附属模组请改用坐标 API：`patchAtCell` / `patchAtQuartCell` / `WeatherApi.patches` /
  `WeatherApi.setWeatherAt`，不要自己拼 key 字符串。
- 0.4.12 不改变存档格式（`DATA_VERSION` 仍为 11），旧存档可直接读取。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

- 构建：`BUILD SUCCESSFUL in 12s`（无 javac 警告）
- 产物：`build/libs/localizedrain-0.4.12.jar`（104,518 字节）
- SHA256：`C93E9F5B59ADA7B48D29C237A0D3EC2F0521742B939620D50D3950B8B6063208`
- jar 内 `neoforge.mods.toml`：`version="0.4.12"`、`license="MIT"`

## 未验证

- 没有客户端/专用服务器运行期验证。
- 背压路径（长链分量触发一次后进入等待）、逐出配额后的分批补扫、旧配置文件保留 32768
  都只经过编译与静态检查，需要在真实存档里观察日志与 MSPT。

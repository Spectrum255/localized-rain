# Localized rain 0.4.11：父表硬上限、快照清理与死代码移除

0.4.11 处理 0.4.10 复核里仍然存在的结构性问题（A/C/D/E/B/G），并顺带把 `g:` 退化方格父表整段删除。

## 1. 父表硬上限（A）

`segmentParents` 之前只受列缓存（200k LRU）间接约束：列仍缓存时对应父项不会被清理，
每 1200 tick 的清理只在父表 > `max(1024, maxEntries*4)`（默认 16384）时扫描，且只删「列已淘汰」的条目。
稳态下父表可达数十万条，清理变成周期性全表遍历 + 路径压缩。

现在：

- 新增配置 `maxParentEntries`（默认 32768，范围 4096–1 000 000），是每个维度父表的**硬上限**。
- `WeatherData.pruneParents` 三遍处理：
  1. 删除成环/悬空链（不论是否被引用）。
  2. 常规清理：被引用节点一律保留；根无状态且列已淘汰的节点删除。
  3. 仍超过硬上限时，反复（最多 4 轮）删除「未被引用、无天气状态」的节点，
     并对每个被删节点调用 `BiomePatches.evictColumn` 把其所在列逐出列缓存，
     让这些列在下一次扫描时重新注册/合并。若最终仍超限只警告一次，不再反复扫描。
- 清理不再依赖 `maxEntries*4` 的阈值，超限即触发。

> 副作用：父表收缩时被逐出的列会重新扫描一次；天气状态本身不删除，
> 重扫后同一段会以新的规范 key 继续（状态通过 `transferState` 迁移）。

## 2. 快照清理与批量推进（C）

- `BiomePatches.beginSweep(level, ticks)`：按扫描间隔放大列扫描预算
  （`maxColumnScansPerTick * ticks`，耗时上限 `min(2ms * ticks, 8ms)`）。
  默认 20 tick 间隔时每轮可补 80 列，冷启动不再被「4 列/tick」的每 tick 预算限制拖到约 110 秒。
- `beginTick` 仍保留每 tick 的默认预算，供扫描周期之外的 `patchAtBudgeted` 使用。

## 3. 降级行为统一（E）

- `canonicalSegment` 命中环保护时现在会**把该节点写成自映射根**并 `setDirty()`，只警告一次。
  这样下一次解析只需一步，不会每次查询都重走坏链。
- `CanonicalFallback` 的 `g:` 分支整段删除，`canonical()` 只剩 3D 段解析。

## 4. 删除死代码（D）

- 删除 `fallbackParents` 字段、`fallbackParents()` 访问器、`load` 里的 `fallback_parents` 读取、
  `save` 里的写入，以及 `WeatherData.transferFallbackState`（改名 `transferState`，只迁移段状态）。
- 删除 `BiomePatches.parseLegacyCells` 与 `patchBiome` 对旧 `p:/g:` key 的回退分支；
  非 `p3:` key 现在返回 `null`。载入存档时本来就会丢弃这些 key。
- `patchSize` 标记 `@Deprecated`，固定返回 0（3D 段模型没有水平格数概念）。

## 5. 存档版本

- `DATA_VERSION` 10 → 11：不再写入 `fallback_parents`。
  旧存档里的该字段会被忽略，读档时只在有 `p3:` 状态时保留 `segment_parents`。

## 6. 状态淘汰顺序（B）

- `prune` 超过 `maxStoredStates` 时，优先淘汰「列已不在缓存」的片（多半在未加载区域），
  再按 `lastTick` 从旧到新淘汰加载中的片，减少「仍在推进的片被淘汰→重建→天气重置」。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

- 构建：`BUILD SUCCESSFUL in 15s`
- 产物：`build/libs/localizedrain-0.4.11.jar`（104,031 字节）
- SHA256：`9F0FED82FFE85346DC5626AFB58EAEEF588822F219E41198D9E5878608FF08EE`

## 未验证

- 没有客户端/专用服务器运行期验证；父表收缩后的重新扫描、自映射隔离、
  批量推进手感、旧存档（v10）读取都只经过编译与静态检查。
- 附属模组如果仍按旧的 `p:/g:` key 或 `patchSize` 估算范围，需要改用坐标 API。

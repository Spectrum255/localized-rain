# Localized rain 0.4.8：父表清理的被引用保护、线性清理与 LRU 顺序

0.4.7 的修复又收到一份复核（trae），指出清理第一遍会删掉「被其他条目引用」的中间节点、
清理/读档的遍历成本是 O(n × 链长)、`isColumnCached` 会刷新列缓存的 LRU 顺序。逐条核对后本轮修复，
`maxStoredStates` 的容量问题仍按运行期观察处理。

## 1. 清理先算「被引用」，不再删中间节点

0.4.7 的第一遍只判断「这条目自己的列是否还在缓存」，没看是否有别的条目把它当父节点：

```
A(列仍缓存) → B(列已淘汰、中间节点) → C(根，无状态、列已淘汰)
```

B 被删、A 保留 → `canonical(A)` 在 B 处停住 → 查不到状态 → 天气读成晴天，而且 A 的列在缓存里，
`columnFor` 命中后不会重新 `register`/`merge`，断链会一直存在到重启。

现在的顺序：

1. 先扫描全表建立 `referenced` 集合（所有被当作父节点的 key）。
2. 第一遍删除时，**被引用的节点一律保留**；其余条目再按「列已淘汰 + 分量根无状态」判断。
3. 第二遍删自映射根时仍要求「无状态、无子条目指向、列已淘汰」。

`referenced` 在删除前构建，因此不会再出现「A 保留、B 被删」的悬空链。先删根造成悬空的场景也被
「无子条目指向」挡住。

## 2. 清理与读档改为线性

`MAX_PARENT_CHAIN` 提到 32768 后，原来「每条目各自沿链走到根」在深层存档里是 O(n × d)。

- `resolveRootMemo(node, memo)`：沿链走时把路径上每个节点记进 memo，并顺手把父指针压缩到根；
  同一轮清理里每个节点最多被访问一次，整体 O(n)。
- `pruneParents` 用一份 `roots` memo 供所有条目复用，删除阶段只做集合删除。
- `sanitizeParents`（读档时）同样用 memo + 压缩；顺带丢弃悬空链（父项缺失）和成环链。
- 移除不再使用的 `resolveSegmentRoot` / `hasTerminatingRoot`（旧实现每个条目重新走链）。

副作用：清理时会把父指针压缩成指向根的扁平结构，之后正常查询的链更短；这属于期望行为。

## 3. `isColumnCached` 不再刷新 LRU

0.4.7 用 `cachedColumn()`（内部 `LinkedHashMap#get`，accessOrder=true）判断列是否在缓存，
清理每分钟遍历父表会把大量列标成「刚用过」，真正冷门的列反而不被淘汰。现在改用：

```java
Map<Long, Column> cache = COLUMNS.get(level);
return cache != null && cache.containsKey(ChunkPos.asLong(parsed[0], parsed[1]));
```

`containsKey` 不改变访问顺序，列缓存与群系缓存（同为 LRU）的淘汰顺序恢复准确。

## 5. 小点处理

- `MAX_PARENT_CHAIN` 收敛为一个公共常量 `BiomePatches.MAX_PARENT_CHAIN`，`WeatherData` 不再单独定义。
- `BiomePatches.canonical` 超过上限时**打一次警告**并退化为按原节点处理（不返回 `null`，
  避免调用方把 `null` 当成正常片继续用；32768 步正常合并不会触达）。压缩循环也加了步数保护。
- `WeatherData.load` 现在读取 `version`：比当前 `DATA_VERSION` 新时打一条 warning（仍尽量读取已知字段），
  为后续格式迁移留下门控位置。
- `resolveSegmentRoot` 删除后，悬空条目在 `pruneParents` 里按「列已淘汰 + 根无状态」删除，
  与复核建议一致；列仍缓存的悬空条目保留。
- `patchAtQuart` 的命名歧义属于文档问题，维持现状（javadoc 已说明入参 quart、返回 16×16 列 key）。
- 快进路径每轮标脏维持现状：只影响 autosave 写盘频率，可忽略。

## 4. `maxStoredStates` 仍需实测（未改默认值）

0.4.5 起 `sweep` 按列推进**所有段**（含洞穴/浮岛），441 个已加载区块 × 每列多段就是上千个状态，
再叠加已探索但未加载的区域，默认 `maxStoredStates = 4096` 可能被频繁触发，导致远处状态被淘汰、
重访时天气从头开始。

本轮不改默认值，建议实测：

- Spark/MSPT：`prune`（每 1200 tick）触发时是否有卡顿；
- 存档里 `segment_parents` 与 `patches` 的条目数、`localizedrain_weather.dat` 大小；
- 重访远处时天气是否被重置（对比 `lastTick`）。

若确认频繁淘汰，下一步可把默认值提到 16384，或只推进「玩家附近 + 已加载地表」的段。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

- 产物：`build/libs/localizedrain-0.4.8.jar`（103,406 字节，
  SHA256 `A0917799A6091ACB1378B592EB1D3E11D28F56BA12D62D9C047336FE27EB8AF5`）
- `META-INF/neoforge.mods.toml`：`version="0.4.8"`、`license="MIT"`。

## 未验证

- 运行期：清理后已扫描区域的天气完整性、深层链的清理耗时、LRU 淘汰顺序、读档时悬空/成环链的丢弃。

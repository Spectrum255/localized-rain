# Localized rain 0.4.9：父表环检测、清理降分配与降级行为统一

0.4.9 处理了第三轮复核指出的 8 个点。其中 1、2、4、6、8 直接改代码；3、5、7 是运行期/可接受项，写进文档。

## 1. 成环链现在会被丢弃，而不是被「修复」（`WeatherData.resolveRootMemo`）

旧实现没有显式环检测：`A→B→A` 会一直走到 `MAX_PARENT_CHAIN`（32768）步，把 32768 个节点塞进 `path`，
然后取 `path` 末尾当作根、把整条链压成星形，还把该根写成自映射——`sanitizeParents` 随后会认为它是合法结构。
这等于把损坏数据「修复」成一个看似正常的错误分量。

现在 `resolveRootMemo` 用 `Set<String>`（`seenScratch`，复用）在沿链时检测重复节点；
成环或超过 `MAX_PARENT_CHAIN` 时返回 `null`，并在 memo 里写 `""` 哨兵（同一轮内的其他节点不再重走坏链）。
`pruneParents` 第一遍先删除所有「根为 null」的损坏条目——**不经过 referenced 判断**，
否则环上的节点都被互相引用，会永久保留。之后才重建 `referenced` 做常规清理。

## 2. `pruneParents` 不再每轮全量分配临时集合

`referenced` / `roots` / `removable` 改为 `WeatherData` 实例字段（`referencedScratch` / `rootScratch` / `removableScratch`），
每轮 `clear()` 复用；`referenced` 的初容量不再按 `size * 2` 预分配。父表十几万条时（例如长时间跑图）避免数 MB 的瞬时垃圾。

## 3. 两轮清理（保持现状）

深分量第一轮删掉叶子后，引用关系要下一轮才更新，因此可能需要两轮 `prune` 才完全清干净。
这属于可接受的行为；如果之后确实观察到清理滞后，可以在路径压缩后重建 `referenced` 再删一遍。

## 4. 损坏链的降级行为统一

0.4.8 中 `BiomePatches.canonical` 超限返回原节点并警告，`WeatherData.canonicalSegment` 返回 `null`，
同一份损坏数据可能得到不同的 key。现在两者都返回**原 key**并只警告一次：
- `canonicalFallback` 的 `g:` 链增加 `terminated` 标志，guard 耗尽时返回原 patch，不再返回中间节点；
- `canonicalSegment` 超限返回原 patch；
- `BiomePatches.canonical` 超限除警告外把该节点写成自映射（隔离），避免每次查询重走整条坏链。

## 5. `maxStoredStates` 仍需实测

默认 4096 是保守值；`pruneParents` 的父表上限是 `max(1024, maxStoredStates * 4)`。
如果长时间跑图后状态/父表明显增长，建议先实测再提高到 16384，或只推进玩家附近与地表段。

## 6. 读档时清理无用的 `g:` 父表

0.4.5 起不再读取 `g:` key，但 `fallbackParents` 只有在父表超过阈值时才会被清空，小存档里旧父表会一直留在 NBT。
现在 `load` 结束后：若 `weatherByPatch` 里没有任何 `g:` key，直接 `fallbackParents.clear()`。

## 7. 悬空链删除后的预期行为（文档）

`sanitizeParents` / `pruneParents` 删除带状态的悬空链后，这些状态只能靠各自的 key 访问，等区块重新扫描并合并前，
天气会表现为按段/列分片。这是预期的恢复行为，不是数据丢失；下一次扫描会重建链接。

## 8. 损坏节点不再重复走长链

`BiomePatches.canonical` 超限时把 `node` 重写为自映射（`parents.put(node, node)`）并 `setDirty()`；
配合 `canonicalFallback`/`canonicalSegment` 的「原 key」降级，查询层面不再每次付出 32768 步的代价。
注意这只隔离被检测到的节点；如果父表上游还有别的环，下一次合并仍会重新检测。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

- 产物：`build/libs/localizedrain-0.4.9.jar`
- 0.4.9：104,070 字节，SHA256 `D92B8991CBF8B6B4024DACDDCE29EAE76DC2E973DBDC95103EDBFBBC97993743`
- `META-INF/neoforge.mods.toml`：`modId="localizedrain"`、`version="0.4.9"`、`license="MIT"`

## 未验证项

- 没有启动客户端/专用服务器；损坏存档（环/超长链）的清理、`g:` 父表清理、`maxStoredStates` 与性能都需要运行期实测。

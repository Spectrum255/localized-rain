# Localized rain 0.4.10：动态父链上限、路径复用与清理后收缩

0.4.9 的复核指出：固定 `MAX_PARENT_CHAIN = 32768` 在已有环检测后不再必要，反而可能误删
合法深链；`resolveRootMemo` 仍每次分配 `path`；清理用的临时集合只 `clear()` 不收缩。
本轮修复这三项，不改变存档格式。

## 1. 父链上限改为按父表大小动态计算

- `WeatherData.resolveRootMemo`：完全去掉长度上限，依赖 `seenScratch` 的重复节点检测；
  环一定终止，合法链深度受父表条目数天然约束。
- `WeatherData.canonicalSegment`：上限改为 `segmentParents.size() + 1`。
- `WeatherData.canonicalFallback`（旧 `g:` 父链）：上限改为 `fallbackParents.size() + 1`。
- `BiomePatches.canonical` 及其路径压缩循环：上限改为 `parents.size() + 1`。
- 删除 `BiomePatches.MAX_PARENT_CHAIN` 常量。

这样只有真正的环才会被判定为损坏；沿一个方向长期探索形成的合法深链不会再被整条删除。

## 2. `path` 改为复用字段

`resolveRootMemo` 不再每次 `new ArrayList<>(8)`，改用实例字段 `pathScratch`，每次调用 `clear()`。
深链场景下不再反复分配大列表。

## 3. 清理后收缩临时集合

`pruneParents` 结束时：

- 清空 `roots` / `referenced` / `removable` / `seenScratch` / `pathScratch`，不再持有字符串引用；
- 若父表已缩到本轮阈值的一半以下，整体替换为新的小集合，释放上一轮留下的容量。

## 构建

```powershell
.\gradlew.bat clean build --offline --console=plain
```

- 产物：`build/libs/localizedrain-0.4.10.jar`

## 未验证

- 运行期：超深链（>32768 次合并）的解析与清理、清理后内存回落、`maxStoredStates` 的容量表现。
- 仍保留的已知项：`prune` 可能需两轮才完全清干净；损坏节点隔离按单节点进行；
  `maxStoredStates = 4096` 需要实机数据决定是否提高。

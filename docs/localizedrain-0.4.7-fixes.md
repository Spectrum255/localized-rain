# Localized rain 0.4.7：父表清理、父链上限与群系缓存修复

0.4.6 之后又收到一份复核（trae），指出父表清理的副作用与父链深度问题。逐条核对后：

- **1（清理误伤已扫描但未加载的区域）**：成立，本轮修复。
- **2（128 步保护误伤合法深链）**：成立，本轮修复。
- **3（`PATCH_BIOMES` 满 200k 整体清空）**：成立，本轮改为 LRU。
- **4（状态膨胀/性能）**：属于需要运行期采样的观察，本轮不改代码，给出验证方法。
- **5（小点）**：`DATA_VERSION` 升到 10；`typeAt` 文档注明低频查询；其余保持。

## 1. 清理不再拆散仍会被查询的分量

`pruneParents` 原先按「根有没有天气状态」决定去留，但状态只在片被推进时创建：
扫描过、当前未加载的列只有父表，没有状态。清理一旦触发（父表 > `maxEntries * 4`），这些列的
`A -> B` 链接被删；而列缓存（20 万，LRU）里这些列还在，`columnFor` 命中缓存时**不会**重新
`register`/`merge`，于是同一段群系被切成多个片，天气不一致，直到重启（缓存清空后重扫）才恢复。

现在的规则：

- 新增 `BiomePatches.isColumnCached(level, key)`：该片 key 的列是否仍在列缓存中。
- 第一遍：只删「根没有状态 **且** 列已不在列缓存」的非根条目；列仍在缓存的链接必须保留。
- 第二遍：删除根条目时额外要求「没有子条目指向它、没有状态、列已被淘汰」，避免先删根造成悬空。
  `referenced` 集合在删完第一遍后计算，保证被保留的子条目仍能解析到根。
- 列真正被淘汰后，下次扫描会重新 `registerColumn` 并重新合并，因此删掉旧链接是安全的。

`WeatherData.prune(...)` 增加 `Predicate<String> columnStillCached` 参数，由
`WeatherManager.tick` 传入 `patch -> BiomePatches.isColumnCached(level, patch)`。

## 2. 父链上限从 128 提高到 32768

合并规则固定取字典序最小的规范 key：从东向西/向北探索时，每遇到更小的列，整个分量会挂到新根下，
未被查询过的旧节点链深度会随合并次数线性增长。128 步的上限在大型群系里会被合法链触发：

- `BiomePatches.canonical` 超限会把中间节点当根返回 → 同一分量被拆成多份天气；
- `WeatherData.canonicalSegment` / `resolveSegmentRoot` / `hasTerminatingRoot` 超限会丢弃合法链。

现在统一使用 `MAX_PARENT_CHAIN = 32768`（与父表容量同数量级，正常合并不会触达），
`canonicalFallback` 的旧 `g:` 父链条也从 64 提到同一上限。路径压缩仍在每次查询时执行，
热点节点深度会迅速降到 1；清理/读档时的全量遍历成本可以忽略。

## 3. `PATCH_BIOMES` 改为 LRU

原先满 200k 直接 `clear()`，已淘汰列的 `p3:` key 只能靠段中心重新采样猜群系；若群系被其他模组改写
（如 Meteoros 平流层），可能判错黑白名单/候选。现在与 `COLUMNS` 一致使用访问顺序
`LinkedHashMap` + `removeEldestEntry`，只淘汰最久未用的条目。

## 5. 小改动

- `DATA_VERSION` 9 → 10。`load` 仍然只接受 `p3:` key（0.4.5 起），版本号只用于标记存档代际；
  从 10 降级到 0.4.5 之前的版本会丢弃非 `p3:` 状态，属于预期行为。
- `WeatherApi` 类文档与 `typeAt` javadoc 明确：冷路径解析不受扫描预算限制，适合低频查询，
  不要每 tick 对大量不同坐标调用。

## 4. 需要运行期确认的部分（未改代码）

扫描推进所有已加载列的**全部段**（含洞穴/浮岛）后，状态数增长比 0.3.x 快。建议实测：

- Spark / MSPT：观察 `sweep` 每轮耗时与 GC 频率；
- 存档大小与 `segment_parents` 条目数（`debugLogging=true` 时每 20 tick 打 `[LRDBG]`；
  或直接读 `localizedrain_weather.dat` 的 NBT 大小）；
- 观察 `prune`（每 1200 tick）触发时是否有明显卡顿；
- 若确实偏高，下一步优先：按玩家距离限制扫描范围、或把 `maxStoredStates` 调低。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

- 产物：`build/libs/localizedrain-0.4.7.jar`（102,521 字节，
  SHA256 `4303C606A00E2B225C4DBD28F580B9249F0CC306F009710FA95610403BD46434`）
- `META-INF/neoforge.mods.toml`：`version="0.4.7"`、`license="MIT"`。

## 未验证

- 运行期：清理触发后已扫描区域的天气完整性、深链（>128 次合并）解析、LRU 淘汰后的群系匹配。

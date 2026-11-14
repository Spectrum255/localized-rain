# Localized rain 0.4.6：并查集父表与快进路径修复

0.4.5 的优化审计由另一个 agent 复核后提出 6 条问题，其中 5 条客观存在（第 3 条不成立），
本轮修复这 5 条，并把坐标 API 的命名改准确。

## 修复内容

1. **`pruneParents` 不再截断父链**（`WeatherData`）
   - 原实现只检查「节点本身或直接父节点」是否有状态，`A -> B -> C`（状态在根 `C`）会被整条删掉。
   - 现在分两遍：先删「根没有状态」的非根条目，再删无状态的自映射根。顺序保证不会先删根、
     留下悬空的子条目；新增 `resolveSegmentRoot`（≤128 步，环返回 `null`）。

2. **`sanitizeParents` 拒绝悬空终点**
   - `hasTerminatingRoot` 结束时必须找到自映射的根（`next != null && next.equals(root)`）；
     父项缺失的悬空链按损坏处理并丢弃。

3. **快进路径置脏**（`WeatherState.tick(..., steps)`）
   - 计时器每轮都会推进，但此前只有雨/雷量变化才返回 `changed`，计时器进度可能不落盘，
     重载后回退，`prune` 也会因为 `lastTick` 不更新而误判过期。
   - 现在 `cycleWeather && steps > 0` 时直接返回 `true`（`steps <= 1` 的逐 tick 路径同样处理）。

4. **`typeAt` 冷路径不再受扫描预算限制**（`WeatherManager.resolveState`）
   - 0.4.5 把它改成了 `patchAtBudgeted`，预算耗尽时返回 `null`，附属模组会在那一 tick 读到晴天。
   - 现在改用单列按需解析 `BiomePatches.patchAt`，与玩家 tick 路径一致；查询本身仍优先走缓存。

5. **坐标 API 改名**（`BiomePatches`）
   - `patchAtQuartCell` 实际按 16×16 列返回片，名字误导，改为 `patchAtQuart(level, quartX, quartZ[, budget])`；
     `patchAtCell(level, cellX, cellZ[, budget])` 仍是 16×16 列语义（内部 `<< 2` 换算）。
   - `/weather area`、README 与本文档同步。

## 兼容性

- 存档格式未变（`DATA_VERSION=9`），本次只修正父表清理与落盘时机。
- 公开 API：`patchAtQuartCell` → `patchAtQuart`（0.4.5 未发布，不保留旧名）；其余签名不变。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

- 产物：`build/libs/localizedrain-0.4.6.jar`（101,176 字节，
  SHA256 `2394D9DB57D9822771A8FC5E117F45C686E5E1A83357A1AE16E7E260AE8BA3D8`）
- `META-INF/neoforge.mods.toml`：`version="0.4.6"`、`license="MIT"`。

## 未验证

- 运行期行为（父表清理后的天气保持、快进落盘、`typeAt` 冷启动解析）仍需实机确认。

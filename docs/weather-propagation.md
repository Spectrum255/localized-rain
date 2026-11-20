# 天气传播设计（2.0.0-alpha 设计稿，尚未实现）

片模式（PATCHES）的天气目前是「每片独立随机循环」：每个 3D 群系段各自抽晴天/下雨/雷暴，片与片之间
没有因果关系。结果是同一条锋面不会从西边压过来，也不会在越过山脉后减弱——天气看起来像一片片孤岛。

本文件给出一套**可选的**传播模型，让天气在相邻片之间沿风向迁移，并在迁移中衰减、消亡或演化。
文档先行，代码未实现：是否落地、以什么默认值落地，等这份设计评审通过后再做。

## 1. 目标

1. 天气有「来源」：新天气先在少数片上生成，再沿风向扩散，而不是全图同时随机。
2. 可解释：玩家能看到一片雨从远处飘过来，而不是原地凭空出现。
3. 可控：服务端可以按维度关闭、限速、限制最大存活时间，避免存档膨胀与性能问题。
4. 与现有系统不冲突：手动设置、强制天气、锁定晴天、移动风暴模式的行为保持不变。

## 2. 数据模型

天气传播不新增持久化结构，只在现有 `WeatherState` 上增加三个字段（`DATA_VERSION` +1）：

```text
origin: 可选 key    —— 这片天气的来源片（根），用于统计与调试；根片自己指向自己
age: int            —— 已经传播过多少步；到达 0 表示刚生成的本地天气
```

`age` 与 M3 的 `ageTicks`（类型持续时间）是两回事：`ageTicks` 决定 lifecycle 淡入淡出，
`age` 决定传播衰减。二者可以同时存在。

不保存「传播图」本身：每轮传播只依赖当前片的邻居状态，历史不参与计算，因此读档后行为一致，
也不会出现父子链越滚越长的问题。

## 3. 片图与邻居

片的 key 是 `p3:<colX>,<colZ>,<startY>`（16×16 列 + 竖直段）。传播只在**同一竖直段层级**的相邻列之间进行：

- 水平：按列坐标相邻的 4 个方向（±X、±Z）；
- 竖直：默认不跨段传播（跨段意味着跨群系/跨高度，等价于另一片天气），只有显式开启 `verticalPropagation`
  时才允许向上/向下各一段。

邻居解析复用 `BiomePatches` 的并查集：先 `canonicalKey(neighbor)`，得到实际承载状态的片；找不到状态就跳过。
**只有已加载的片参与传播**：未加载片保持冻结，等重新加载后由下一次扫描决定是否被邻居感染。

## 4. 风向驱动

`WindField` 已经给出每个风暴的局部风向，片模式没有风暴对象，因此用维度级风向：

```text
windAngle = WindField.direction(level)   // 已有：全局风向角
```

把风向离散成 8 个方向（每 45° 一档），每档对应一组「上风向 → 下风向」的列偏移。
每个传播步只从**上风向邻居**向下风向片传递，避免双向抖动。

可选的 `propagationBias`：在 8 向基础上给主风向更高权重（例如主方向 1.0、斜向 0.5、其余 0），
让天气整体呈条带而不是圆形扩散。

## 5. 算法（每次 sweep，最多执行一次传播）

```text
if (!propagationEnabled || windSpeed < minimumWindSpeed) return
budget = maxPropagationSteps            // 本轮最多处理多少个片
for patch in loadedPatches (按 lastTick 从旧到新，保证公平):
    state = data.get(patch)
    if state == null || state.manual() || state.forced() || state.locked() continue
    if state.isRaining() || state.age >= maxPropagationAge continue
    for neighbor in upwindNeighbors(patch, windAngle):
        source = data.get(neighbor)
        if source == null || !source.isRaining() continue
        if random.nextFloat() >= propagationChance * decay(source.age) continue
        // 感染：把源片的类型/强度复制过来，但强度降一档、age+1
        data.spreadFrom(patch, source)
        budget--
        break        // 一个片一轮最多被一个源感染
    if (budget <= 0) break
```

`spreadFrom` 的语义：

- 类型 = 源类型（若该类型的 `next` 列表非空且随机命中演化，则用演化结果，规则同 M3）；
- `rainLevel/thunderLevel` 初始为源片当前强度 × `propagationStrength`（默认 0.85），随后由普通 tick 渐变；
- `rainTime` 使用该类型的最短雨天区间，避免传播来的天气立刻结束；
- `age = source.age + 1`，`origin = source.origin`；
- 不覆盖 `manual/forced/locked` 状态，也不在干燥群系里生成降水类型（复用 `WeatherBiomeFilter` 的池判断）。

衰减：`decay(age) = propagationDecay ^ age`，默认 0.9。到 `maxPropagationAge`（默认 8）后不再继续传播，
但已生成的天气按正常计时器结束。

## 6. 配置（尚未加入 `Config`）

拟加入 `[patches]`（SERVER）：

| 键 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- |
| `propagationEnabled` | `false` | 布尔 | 总开关；默认关闭，先观察 |
| `propagationChance` | `0.05` | 0–1 | 单个上风向邻居每轮感染下风向片的概率 |
| `propagationDecay` | `0.9` | 0.1–1 | 每传播一步的概率衰减 |
| `maxPropagationAge` | `8` | 0–64 | 最多传播多少步 |
| `maxPropagationSteps` | `64` | 1–512 | 每次 sweep 最多处理多少片 |
| `minimumWindSpeed` | `0.5` | 0–10 | 风速低于该值时不传播（格/秒） |
| `propagationStrength` | `0.85` | 0.1–1 | 传播到新片的初始强度比例 |
| `verticalPropagation` | `false` | 布尔 | 是否允许跨竖直段 |

这些键在实现时同步 `GlobalWeatherConfig` 镜像、`ConfigFiles.weatherSectionKeys()`、lang 中英标题+tooltip。
默认 `propagationEnabled=false` 保证升级后行为与 1.x 完全一致。

## 7. 与现有系统的交互

- **手动/API 设置**：`manual=true` 的片不参与传播（既不传播也不被感染），避免玩家指令被邻居覆盖。
- **强制天气/锁定晴天**：`forced` 与 `locked` 片同样跳过；`hardLock` 的干燥群系不会被感染。
- **移动风暴模式**：不参与片模式传播；两种模式互斥（`WeatherMode`）。
- **数据包重载**：类型被删除时，传播源按晴天处理（`WeatherTypes.resolve == null` 直接跳过）。
- **清理**：传播来的状态与普通状态一样受 `stateRetentionDays`/`maxStoredStates` 约束；`origin` 不构成引用，
  源片被清理后目标片继续按自己的计时器运行（不追溯删除）。

## 8. 性能

- 每轮最多 `maxPropagationSteps` 个片，每个片最多 4 个邻居查询（8 向但只取上风向的一半）；
- 只处理本轮已加载、已分类的片，复用 `sweep` 已有的集合，不额外扫描区块；
- 邻居查询走 `canonicalKey`（并查集路径压缩后接近 O(1)），不触发新的列扫描；
- 在最坏情况下（64 片 × 4 邻居）每轮约 256 次 map 查询，远低于 4ms 预算。

## 9. 调试

`debugLogging=true` 时，每轮传播最多输出一行摘要：

```text
[LRDBG] propagation: 12 spread, 3 skipped(manual/forced), wind=1.4 angle=92°
```

新增命令 `/weather propagation status`（只读）与 `/weather propagation trigger`（立即跑一轮，权限 2），
用于运行期验证；`/weather propagation` 参数不会持久化。

## 10. 测试矩阵

| 场景 | 预期 |
| --- | --- |
| 默认配置 | 与 1.x 行为完全一致（不传播） |
| 开启传播、无风 | 不扩散 |
| 单向风、平原 | 天气沿下风向连成条带，源头强度最高 |
| 越过山脉 | 高山片按 `StormTerrain` 的干旱/海拔因子衰减，传播强度降低 |
| 干燥群系 | 不被降水类型感染 |
| 手动设置 | 邻片不再覆盖，手动状态按时结束 |
| 睡觉/清空 | 传播状态一并清除 |
| 读档 | `age/origin` 恢复，不重复传播已过期的源 |
| 性能 | 1000 片、开启传播，MSPT 增量 < 0.1ms |

## 11. 被否决的方案

- **全局气压场/锋面模拟**：需要额外的连续场与随时间演化的数据，存档与调试成本高，收益不明显。
- **每片独立马尔可夫链**（当前）：简单但无空间相关性，正是本设计要解决的问题。
- **按群系模板批量生成**：无法表达「同一群系内部也有天气差异」，与模组核心卖点冲突。

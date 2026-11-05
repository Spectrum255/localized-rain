# Localized rain 1.0.1 变更说明

面向 `Meteoros Protocol` 的 `docs/localizedrain-1.0.1-thunderlevel-api.md` 请求。
本文件同时是本模组 1.0.1 的交付记录。

## 1. 新增公开 API

```java
public static float WeatherManager.thunderLevelAt(ServerLevel level, BlockPos pos)
```

返回该坐标所在群系片的雷暴强度，范围 `[0.0, 1.0]`。

| 情况 | 返回值 |
| --- | --- |
| 该坐标所在的群系片已有天气状态 | 该片 `thunderLevel`，钳到 `[0,1]` |
| 该坐标未被访问（片未记录，或维度无天光） | `0.0F` |
| 计算中出现 NaN / 无穷 | `0.0F`（`Float.isFinite` 校验） |

保证：

- **只读、无副作用**：只查 `BiomePatches` 的既有缓存，不创建片、不加载区块、不触发 tick、不写存档状态。
- **不做见天/降水门控**：未下雨时也返回当前存储值（按需求文档要求）。
- **O(1) 热路径**：一次缓存查表 + 一次 `WeatherData` map 查询；每 tick 数百次调用可接受。
- **仅服务端主线程**：与其余 `WeatherManager` 方法一致，非线程安全。
- **与 `isThunderingRegionAt` 一致**：后者的判据是 `thundering && thunderLevel > 0.9`，所以它为 `true` 时 `thunderLevelAt > 0.9`（同一份状态）。
- **远处片可能停在上次 tick 的值**（Javadoc 已写明）：只有玩家附近/显式设置的片会被 tick，远处片不会自行衰减。

## 2. 对需求文档第 9 节问题的答复

1. `BiomePatches.cachedPatchAt(level, pos)` 就是只读查询：只查缓存，未命中返回 `null`，不会触发洪水填充。
2. 状态可通过 `WeatherData#get(String patch)` 拿到 `WeatherState`，其 `thunderLevel()` / `isThundering()` 都是公开方法；本次没有为了该需求改动这两个方法。
3. 远处片保留上次 tick 的值可以接受；已在 Javadoc 标注，调用方不应把它当作“实时值”。
4. 未下雨时 `thunderLevel` 会继续按 `-0.01/tick` 衰减（`WeatherState.tick` 中只有 `thundering && raining` 才 +0.01）；按文档要求没有加门控。
5. 没有拆出独立的 `WeatherApi` 类：API 面只增一个静态方法，继续放在 `WeatherManager` 上，避免调用方多一个类要引用；后续若 API 面扩大再考虑拆分。

## 3. 修复：雷暴群系没有闪电

原实现完全依赖原版 `ServerLevel.tickChunk` 里的 `random.nextInt(100000) == 0` 抽签。
这个密度是按“整个维度都在雷暴”设计的：局地雷暴只覆盖一部分群系片，能通过 `isThunderingAt` 过滤的抽签太少，
并且抽签还受区块是否被玩家跟踪/强制加载的限制，实际表现就是“雷暴了但基本不落雷”。

1.0.1 改为由模组自己按原版密度显式落雷：

- `WeatherManager.tick` 末尾调用 `spawnLightning(level, patchByPlayer, data)`。
- 只有存在雷暴片时才继续；每 tick 每玩家以 `chance = min(1.0, (2*view+1)^2 / 100000)` 的概率抽一次
  （视距 10 时约 0.0044/玩家/tick，等价于原版每 tick 441 个区块 × 1/100000 的期望）。
- 命中后在该玩家视距内随机取一个**已加载**区块，取高度图落点，再用 `isThunderingAt` 过滤，
  最后生成原版 `LIGHTNING_BOLT`（`setVisualOnly(false)`），因此避雷针、引雷、伤害、火情等原版行为都保留。
- 同时删除了原先对 `tickChunk` 中 `nextInt(100000)` 的放大 `@Redirect`（密度不再依赖原版抽签）。

保留的 mixin 只有 4 个 `@Inject` + 1 个 `@Redirect`（`tickChunk` 里的 `isRainingAt` →
`WeatherManager.isThunderingAt`），用于阻止原版在非雷暴区域落雷。

## 4. 构建产物

- `build/libs/localizedrain-1.0.1.jar`
- 大小：36,943 字节
- SHA256：`CD764B34685AAB21E2B934BB88AE9DC593F3C9CE5832F534B518414E0A0B78D3`
- 已复制到 `D:\minecraft mod\Meteoros Protocol\libs\localizedrain-1.0.1.jar`
- manifest 保留 `Automatic-Module-Name: com.spectrum255.localizedrain`；`neoforge.mods.toml` 版本为 `1.0.1`。
- 构建命令：JDK 21 + `gradlew clean build`（在线），`BUILD SUCCESSFUL in 49s`。

## 5. 验证情况（如实说明）

已完成：

- `gradlew clean build` 通过（在线，JDK 21）。
- `javap` 确认 `thunderLevelAt`、`isThunderingAt` 在产物中。
- 无头开发服务器探针（已删除）在隔离环境下确认：
  - 雷暴片状态能正确升到 `rain=1.0/thunder=1.0`；
  - 显式落雷路径确实会生成 `LightningBolt` 实体（探针日志出现过 `boltsAdded+=2`）。

**未完成（需要客户端/真实服务器实测）**：

- 客户端画面中的落雷视觉、雷声与亮度过渡；
- 从 0 平滑升到 1、`/weather clear` 平滑降到 0 的客户端观感；
- “1 万次调用无异常”“极端 Y 不崩溃”等运行期项；
- 与 Meteoros 联调的整包运行。

原因：本机没有用户安装该模组的游戏日志；开发服务器只能手工 BootstrapLauncher 拉起，
且用户明确要求“先不要直接启动服务器测试”，因此不再做运行期验证。
上表功能项按“静态实现 + 隔离探针”交付，请在客户端复测后再对外发布。

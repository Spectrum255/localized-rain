# Localized rain 1.0.1 需求：雷暴强度连续值 API

> **提出方**：Meteoros Protocol 工作区（`D:\minecraft mod\Meteoros Protocol`）  
> **目标工程**：Localized rain（`D:\minecraft mod\Localized rain`）  
> **日期**：2026-11-05  
> **性质**：增量 API，不改变现有天气行为；版本建议 1.0.1  
> **关联**：Meteoros v2 四储库模型需要 0–1 的 `storm` 强度

---

## 0. 给目标工作区 AI 的启动说明

这是一份跨工程接口需求，不是对 Localized rain 内部实现的强制重写。请先：

1. 阅读 Localized rain 工程的 `AGENTS.md` / 项目记忆 / 构建说明；
2. 定位 `WeatherManager`、`WeatherData.WeatherState`、天气 patch 的只读查询路径；
3. 只做本文第 3 节的增量 API 与第 6 节的验收，不顺手改天气模拟、不新增 mixin、不改 Meteoros 工程；
4. 完成后把 jar 放到本文第 7 节给出的路径，并回报改动文件、构建结果、验收记录。

---

## 1. 背景

Meteoros Protocol 的 v2 粒子场把雷暴强度作为连续输入，出现在两个公式里：

```text
D_ch = clamp((0.5 + 1.0*wet) * dim_ch + 8.0*E_art + 12.0*storm, 0, 30)
q    = clamp(0.25*h + 0.65*storm*h + q_art, -0.9, 0.9)
```

- `storm` 是 0–1 的雷暴强度，不是布尔值；
- 它既影响电荷储库总量 `D_ch`，也放大高度偏置 `q`；
- 用布尔值会让 `D_ch`、`q` 在 0.9 附近跳变，破坏场模型的连续性；
- Meteoros 的机器每 tick 采样，需要便宜、只读、无副作用的取值。

Localized rain 内部已经有平滑的 `thunderLevel`（0–1，每 tick ±0.01），但当前公开 API 只有布尔的 `isThunderingRegionAt`，Meteoros 无法取得连续值。这是本需求的直接原因。

---

## 2. 现状（已在 Localized rain 1.0.0 源码确认）

### 2.1 内部状态

- `WeatherData.WeatherState.thunderLevel()`：0–1 的平滑雷暴强度；
- 每 tick 在“正在雷暴且正在降水”时 `+0.01`，否则 `-0.01`（`WeatherData.java` 约 177–178 行，具体行号以当前源码为准）；
- `WeatherState.isThundering()`：`thunderLevel > 0.9 && isRaining()`；
- 天气按“群系连通片（patch）”管理，不是维度全局。

### 2.2 公开 API（`WeatherManager`）

现有只读方法（`WeatherManager`）：

```text
isRainingAt / isPrecipitatingAt / isThunderingAt / isThunderingRegionAt
skyDarkenAt / patchAt / allPatches
```

另有 `BiomePatches.cachedPatchAt`（只读、不触发洪水填充），是热路径推荐入口；本节所说的“只读查询”指它或 `WeatherManager` 中等价的只读方法。

写入方法：

```text
setWeather / setAll / clearAll
```

其中：

- `isThunderingRegionAt(ServerLevel, BlockPos)` 返回布尔，内部用 `> 0.9` 判定；
- `patchAt` 可能创建/补建 patch，不适合热路径；
- `cachedPatchAt` 是只读查询，适合热路径（具体签名以当前源码为准）。

### 2.3 已知行为（需要在实现时保留）

- `WeatherManager.tick` 只推进玩家所在/附近的 patch；远处 patch 的 `thunderLevel` 可能停在最后一次 tick 的值；
- 因此本 API 只能承诺“返回当前存储值”，不承诺“为远处区域实时推进雷暴”；
- 现有 `isThunderingRegionAt`、`isThunderingAt` 的语义与返回值不得改变。

---

## 3. 需求：新增只读 API

### 3.1 方法签名

在 `WeatherManager`（或现有天气 API 的等价入口）新增：

```java
public static float thunderLevelAt(ServerLevel level, BlockPos pos)
```

包名、类名沿用工程现状；只要求调用方可以静态调用，参数为服务端世界与方块坐标。

### 3.2 返回值语义

| 情况 | 返回值 |
|---|---|
| 坐标落在某个天气 patch 内 | 该 patch 当前的 `thunderLevel`，钳制到 `[0.0, 1.0]` |
| 坐标没有 patch（未记录、未加载、该维度无天气） | `0.0F` |
| 值为 `NaN` / 非有限值 | `0.0F` |
| 正在下雨但未打雷 | 返回当前平滑值（可能 >0，表示雷暴在积累/衰减） |
| 未下雨但 `thunderLevel` 尚未衰减到 0 | 仍返回当前值（**不做降水/见天判断**，由调用方决定是否使用） |

明确约定：

1. **单位是 0–1 的 float，不是 0–100 的百分比**；不要返回 `int` 或 `double`。
2. **不做见天判断**：不检查 `canSeeSky`、不检查降水类型；调用方需要时自己组合 `isPrecipitatingAt`。
3. **不做降水门控**：即使当前未下雨，也返回存储的强度值；这与 `isThundering()` 的“必须正在下雨”语义不同，是本 API 的刻意设计。
4. **无副作用**：不得创建 patch、不得加载区块、不得改天气、不得触发 tick、不得写缓存之外的状态。
5. **只读热路径**：单次调用应是 O(1) 的 patch 查找 + 字段读取；不分配对象、不打日志；每 tick 被调用数百次也应可接受。
6. **线程**：只在服务端主线程调用；不要求从渲染线程/异步线程安全。
7. **一致性**：`isThunderingRegionAt(level, pos) == true` 时，`thunderLevelAt(level, pos)` 必须 `> 0.9`（允许 1e-6 误差）；反向不要求——未下雨时可能仍 `> 0.9`，但 `isThunderingRegionAt` 为 false。

### 3.3 与现有方法的关系

- 不替换、不修改 `isThunderingAt` / `isThunderingRegionAt`；
- 不暴露整个 `WeatherState`，只暴露一个 float；
- 不要求新增命令、不要求新增网络包、不要求客户端改动；
- 不要求改 `WeatherManager.tick` 的推进范围；远处 patch 的“最后值”语义在 Javadoc 中写明即可。

---

## 4. 建议实现（可按工程实际调整）

```java
/**
 * 返回该位置当前天气 patch 的平滑雷暴强度（0–1）。
 * 只读：不创建 patch、不加载区块、不推进天气。
 * 若该位置没有 patch，返回 0。
 */
public static float thunderLevelAt(ServerLevel level, BlockPos pos) {
    WeatherPatch patch = BiomePatches.cachedPatchAt(level, pos); // 只读查询；不要用会创建 patch 的 patchAt
    if (patch == null) return 0.0F;

    WeatherData.WeatherState state = patch.state(); // 或工程现有的等价 getter
    if (state == null) return 0.0F;

    float value = state.thunderLevel();
    if (!Float.isFinite(value)) return 0.0F;
    return Mth.clamp(value, 0.0F, 1.0F);
}
```

注意事项：

- 如果 `cachedPatchAt` 的实际名称/签名不同，用工程现有的“只读、不创建”查询；**不要**为了省事调用 `patchAt`；
- 如果 patch 不直接暴露 `state()`，用现有 getter，或在 patch 上增加一个只读 `thunderLevel()` 转发；
- 若 `thunderLevel` 已在别处钳制，仍建议在 API 边界再钳一次，避免未来改动漏掉；
- 不要把该方法实现成“先 tick 再读”，那会改变远处 patch 行为并带来副作用。

---

## 5. 明确不做

- 不改天气模拟、patch 合并/拆分、雷暴判定阈值；
- 不改 `isThundering*` 的布尔语义；
- 不新增 mixin（Meteoros 侧已遵守“不重复 mixin 前置已占用的 8 个方法”的约定，本需求也不需要）；
- 不新增依赖、不新增命令、不改客户端渲染；
- 不动 Meteoros Protocol 工程（由 Meteoros 侧自行升级 jar）。

---

## 6. 验收标准

构建：

1. `gradlew build` 通过；产物版本为 1.0.1；jar 内 `MANIFEST.MF` 保留 `Automatic-Module-Name`（jarJar 嵌入需要）。
2. 现有功能回归：`/weather rain`、`/weather thunder`、`/weather clear`、patch 查询命令的行为不变。

功能（专用服务器 + 客户端各跑一次）：

3. 玩家站在某群系，执行 `/weather thunder`：每 tick 采样 `thunderLevelAt`，数值从 0 平滑上升到 1（约 0.01/tick），无跳变；`> 0.9` 后 `isThunderingRegionAt` 为 true。
4. 执行 `/weather clear`：数值从当前值平滑下降（约 0.01/tick）到 0；期间 `isThunderingRegionAt` 立即为 false（因为它要求正在下雨），这是预期差异。
5. 传送到远处未记录区域（或新维度）：`thunderLevelAt` 返回 0；前后对比 `level.getChunkSource().getLoadedChunksCount()` 与 `WeatherManager.allPatches().size()` 不变，确认没有加载区块/创建 patch。
6. 在同一 tick 内连续调用 10,000 次：无异常、无明显分配、TPS 无可感知下降（可用简单计时）。
7. 边界：`pos` 为极端 Y（世界外）不崩溃，返回 0 或当前 patch 值；`level` 为 null 时由调用方保证非 null，方法可不做额外保护。

---

## 7. 交付物

1. 源码改动 + 变更说明（改了哪个类、用了哪个只读查询、Javadoc 写明“远处 patch 可能为最后值”）。
2. `build/libs/localizedrain-1.0.1.jar`。
3. 把 jar 复制到：

   ```text
   D:\minecraft mod\Meteoros Protocol\libs\localizedrain-1.0.1.jar
   ```

   并给出 SHA256；如果路径不可写，给出 jar 的实际路径，由 Meteoros 侧复制。
4. 构建日志摘要（成功/失败、警告数）与第 6 节验收记录（哪几项做了、哪几项没做）。

版本说明：这是加法 API，建议 1.0.1；若工程坚持语义化版本用 1.1.0，也可以接受，但 jar 文件名必须与实际版本一致，并同步告知 Meteoros 侧。

---

## 8. Meteoros 侧对接（供参考，不需要 Localized rain 实现）

Meteoros 会做三件事：

1. 在 `LocalizedRainCompat` 增加转发：

   ```java
   public static float thunderLevelAt(ServerLevel level, BlockPos pos) {
       return WeatherManager.thunderLevelAt(level, pos);
   }
   ```

2. 在 `WeatherAdjustedField` 中把 `storm` 从 0 改为 `LocalizedRainCompat.thunderLevelAt(level, pos)`，再代入：

   ```text
   D_ch = clamp((0.5 + wet) * dim_ch + 8*E_art + 12*storm, 0, 30)
   q    = clamp(0.25*h + 0.65*storm*h + q_art, -0.9, 0.9)
   ```

3. jar 到位后：替换 `libs/localizedrain-1.0.0.jar` 为 `1.0.1`，更新 `build.gradle` 的 `files(...)`，重新构建并跑 `/meteoros selftest`。

Meteoros 不会调用 `setWeather`/`setAll`/`clearAll`，也不会为采样创建 patch。

---

## 9. 待确认问题（实现前请回报结论）

1. 工程中“只读、不创建 patch”的查询方法实际叫什么？`cachedPatchAt` 是否就是它？
2. patch 对象是否直接暴露 `WeatherState` / `thunderLevel()`？若没有，最小改法是加一个只读 getter 还是新增方法？
3. 远处 patch 的“最后值”是否可接受作为返回值？（本文提议：返回最后值，不主动 tick；调用方自行判断新鲜度。）
4. `thunderLevel` 在未下雨时是否继续衰减？如果不会衰减，是否需要在 API 内做降水门控？本文提议不门控，保持纯强度值。
5. 包名/类名沿用现状即可，是否有更合适的公共 API 类（如 `WeatherApi`）？

---

## 附录：为什么需要连续值（给实现者的直觉）

布尔雷暴在 0.9 附近翻转，会让 Meteoros 的 `D_ch` 在雷暴开始/结束时瞬间跳 12 个浓度单位、`q` 跳 0.65 倍高度项，机器采样会出现阶跃。连续值让雷暴有“积累—峰值—衰减”，也允许 Meteoros 以后做“雷暴前预充能”之类的玩法，而不需要再改前置 API。

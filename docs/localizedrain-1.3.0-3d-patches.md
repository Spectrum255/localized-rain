# 1.3.0：垂直分层的群系片（3D patch）

> **已被 1.4.0 取代。** 分层模型会把同一露天群系的竖直方向切成互不相干的天气单位：
> 玩家越过 128 格后进入新的层，新片从晴天计时器重新开始，表现为「128 格以上一直是晴天」。
> 1.4.0 改回「地表群列 + 3D 群系子状态」，见 `localizedrain-1.4.0-3d-substates.md`。

## 背景

1.2.x 的天气单位是 **2D 列**：`patch` 只由 x/z 决定，群系采样固定在 y=128。
同一 XZ 上不同高度的生物群系（地形模组叠出来的平流层、洞穴群系等）共享同一份天气：
上层下雨时下层也下雨、`/weather` 无法只改上层，黑白名单也不能表达「只在上层出现的群系」。

本版本把天气单位改成 **3D**：patch key 带层号，每层按自己的高度中心采样群系、维护独立的天气状态。

## 设计

- 配置 `patchLayerHeight`（默认 64，范围 16–256）。
- 层号 `layer = Math.floorDiv(y, patchLayerHeight)`；世界外（`level.isOutsideBuildHeight(pos)`）没有天气。
- 每层的群系采样高度是「层中心」，并夹在 `minBuildHeight`/`maxBuildHeight` 之间：
  `sampleY = min(maxBuildHeight - 1, max(minBuildHeight, layer * H + H / 2))`。
  - 默认 H=64、主世界 -64..320 时，层 0 采样 y=32、层 1 采样 y=96……
  - 把高度扩到 1024 的维度（例如 Meteoros 的主世界，`min_y=-64`、`height=1024`）层 12 采样 y=736，
    正好落在平流层（y=721 起）内，所以 64 是能在该模组里区分平流层/地表的常用值。
- patch key：
  - 正常轮廓：`p:<layer>:<minCellX>,<minCellZ>`
  - 超大群系退化方格：`g:<layer>:<cellX>,<cellZ>`（已按 `>> FALLBACK_SHIFT` 展开）
  - 缓存键：`SectionPos.asLong(cellX, layer, cellZ)`（同时包含层号和 cell 坐标）
- `BiomePatches` 的公开 API 变化：
  - 新增 `layerHeight()`、`layerOf(int y)`、`layerOfPatch(String patch)`；
  - `patchAt(ServerLevel, BlockPos)` / `patchAtBudgeted` 现在按 `pos.getY()` 选层，世界外返回 `null`；
  - 新增 `patchAtCell(level, layer, cellX, cellZ[, FillBudget])`；
  - 旧的 `patchAtCell(level, cellX, cellZ)` 保留，按 `layerOf(128)` 解释（兼容旧调用）。
- 存档格式升到 v4（`WeatherData.DATA_VERSION = 4`）：
  - 新存档写 `version=4` 与 `layerHeight`；
  - 读档时若存的 `layerHeight` 与当前配置不同，**丢弃所有层化 key**（旧 2D 条目与层高无关，保留）；
  - 旧 v3 的 2D key 作为 legacy 回退：`WeatherData.get(patch)` 在层化 key 未命中时回退到 2D key；
    `getOrCreate` 首次访问某层时把 2D 状态「搬」到该层（其余层从晴天重新开始）。
- 天气热查询（`stateOrNull`）的 cell 缓存键加入层号，并把「世界外」直接当晴天。

## 命令

```
/weather clear|rain|thunder at <pos>             # 该点所在层的那一片
/weather clear|rain|thunder area <from> <to>      # 按 from/to 的 Y 范围逐层采样
/weather clear|rain|thunder layer <layer> [duration]
/weather clear|rain|thunder global                # 已记录的所有层、所有片
```

- `at` 不再配合旧版的「y=128 列」，直接使用命令坐标的 y。
- `area` 的采样上限仍是 512 个位置，但位置数 = 每层采样数 × 层数，所以跨层范围要相应缩小。
- 新增 `layer <n>`：作用于该维度里已经记录过的、层号等于 n 的所有片；没有记录时返回
  `commands.localizedrain.weather.layer.empty`。

## 兼容与已知限制

- 客户端渲染没有改：原版仍只有一条围绕相机的雨柱，雾色与日月亮度是维度级；
  玩家跨层时靠 `send()` 的 0.01/tick 雨量渐变过渡，所以看不到「同列两层同时下雨」的分层画面。
  服务端归属、`isRainingAt`、刷怪/燃烧判定、`/weather`、黑白名单与强制天气现在都按层工作了。
- Xaero's Minimap 天气行仍由 `WeatherStatePayload` 提供目标状态，随玩家所在层切换。
- 层高改小会让同一条雨柱被切成多层，改大则分辨不出立体群系；已存天气状态在修改后作废（见上）。
- 层化轮廓扫描的每 tick 预算（最多 2 片 / 4ms）和 `maxPatchCells` 上限不变。
- 未做客户端/服务器运行期验证：本版本只做了 `clean build --offline` 构建验证。

## 构建产物

- `build/libs/localizedrain-1.3.0.jar`，74,854 字节
- SHA256 `F650AD1F3086CC365BA09F8DA28790E396143629F9501BA768C1802D6A1B95EC`

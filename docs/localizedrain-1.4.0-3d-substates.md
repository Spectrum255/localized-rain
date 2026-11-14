# 1.4.0：地表群列 + 3D 群系子状态

## 背景

1.3.0 按 `patchLayerHeight`（默认 64）把世界切成很多「天气层」，每层是独立的群系片与独立的天气循环。
这带来一个用户可感知的回归：**玩家越过 128 格（层 2）后会进入新的层，新片从晴天计时器开始计时**
（`minClearTicks`–`maxClearTicks`，默认 10 分钟–2.3 小时），于是「128 格以上一直是晴天、天空不变暗」。
同时分层也把同一片露天群系的竖直方向切成了互不相干的天气单位，和「天气跟随生物群系轮廓」的直觉不一致。

1.4.0 改回 **2D 轮廓 + 3D 子状态**：

- 露天部分以「地表附近采样的群系连通区」为区域（列模型），同一列共享天气，**不再有高度阈值**；
- 位置的实际 3D 群系与轮廓群系不同时（洞穴群系、浮岛群系等），用 `轮廓|实际群系` 作为独立子状态；
- 不产生降水的群系（`Biome#hasPrecipitation() == false`）始终按晴天处理，不参与天气循环、不落雷。

## 设计

### 群系片 key

| 形态 | 含义 |
| --- | --- |
| `p:<minX>,<minZ>` | 同一群系的连通轮廓（16 方块采样格，起点格坐标） |
| `g:<x>,<z>` | 超过 `maxPatchCells` 的超大群系退化成的 1024 方块对齐方格 |
| `<base>\|<biome>` | 轮廓内某个实际 3D 群系不同的子状态，例如 `p:12,34\|minecraft:lush_caves` |

轮廓的群系仍在 y=128 采样（`BiomePatches.SAMPLE_BLOCK_Y`），与 1.2.x 一致；
子状态按位置的 3D 坐标取「实际群系」：已加载区块优先读**群系调色板**
（`BiomePatches#placedBiomeAt`，能看到模组在区块生成后改写的群系，例如 Meteoros 的平流层），
区块未加载时才退回噪声采样（`getUncachedNoiseBiome`），不为查天气而加载区块。

### 状态推进

- `WeatherManager.tick` 每 tick 只处理玩家所在的 patch（含子状态）。
- 轮廓片与子状态共用同一套 `WeatherState`（计时器 + 0.01/tick 渐变）。
- 子状态在 `pinnedClear` 里按「永远晴天」处理：不参与维度级 `setRainLevel`，已有状态只做淡出；
  这样洞穴群系不会因为地表下雨而变暗或积雪。
- `stateOrNull` 的查询缓存键 = 水平 cell + section Y（16 方块高），避免把地表的天气误用到洞穴里。

### 存档

`WeatherData` 版本升到 **v5**：

- `save` 写 `version=5`，不再写 `layerHeight`；
- `load` 丢弃 1.3.0 的层化 key（`p:<layer>:<x>,<z>` / `g:<layer>:<x>,<z>`），
  保留 1.2.x 及更早的 2D key 与 1.4.0 的子状态 key；
- 旧 2D 状态与新的 2D key 完全同形，因此不需要迁移，直接继续使用。

### 命令

- `/weather ... at <pos>` / 无参：按该点所在高度解析实际群系，命中子状态时只改这一子状态；
- `area`：只按水平方向采样（不再按 Y 分层），上限 512 个采样点 / 64 片 / 8 次轮廓扫描；
- `global`：当前维度记录过的所有片（含子状态）；
- 移除了 `/weather ... layer <n>`。

### 移除的配置

- `patchLayerHeight` 已删除（1.3.0 的层高设置不再有意义）。

## 兼容性

- 存档：v5 读取 v1–v4 的数据；1.3.0 的层化条目会被丢弃（它们的层号语义已不存在）。
- API：`BiomePatches.patchAt` / `patchAtCell` / `cachedPatchAt` / `patchBiome` / `patchSize` 的签名与 1.2.x 相同；
  新增 `basePatch(String)`（去掉 `|biome` 后缀）。
- 客户端渲染没有改动：仍按玩家所在片的雨量做 5 秒渐变；vanilla 只有一条围绕相机的雨柱，
  因此「同列两层同时下雨」不会在画面上同时出现。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

产物：`build/libs/localizedrain-1.4.0.jar`。

## 未验证

- 没有启动客户端/专用服务器做运行期验证（构建与静态检查通过）。
- 待实测：玩家从地表升到 128 格以上（同一群系）时天气保持；洞穴内不下雨、不落雷；
  两个露天群系交界处的切换；旧存档加载。

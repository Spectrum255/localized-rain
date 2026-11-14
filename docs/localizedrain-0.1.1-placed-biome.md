# 0.1.1：子状态改用「区块已放置群系」

## 背景

Meteoros Protocol 用 `StratosphereBiomeMixin` 在区块生成完成后，把平流层高度（默认 y > 720）
的群系改写进 `LevelChunkSection` 的群系调色板（`meteorosprotocol:stratosphere`）。
1.4.0 判断「实际 3D 群系」用的是 `ServerLevel#getUncachedNoiseBiome`——生物群系**噪声源**，
它看不到任何区块生成后的改写。结果：

- 平流层里的位置解析出的实际群系仍等于地表群系 → 不拆子状态；
- 玩家在平流层复用下方 2D 轮廓的天气状态，`send` 继续把雨/雷量发给客户端；
- 天空变暗、雨声、Xaero HUD 都跟着下方群系走（雨滴粒子不出现，因为平流层群系不降水）。

## 改动

`BiomePatches` 新增 `placedBiomeAt(ServerLevel, BlockPos)`：

1. `level.getChunkSource().getChunkNow(cx, cz)` 拿**已加载**的区块（不加载区块）；
2. 非空时读该区块的群系调色板：`ChunkAccess#getNoiseBiome(QuartPos.fromBlock(x/y/z))`；
3. 区块未加载时退回原来的噪声采样 `biomeAt(...)`，因此不会因为查天气而加载区块。

`withBiomeSubState` 从 `biomeAt` 改用 `placedBiomeAt`。于是平流层位置会得到
`<下方轮廓>|meteorosprotocol:stratosphere` 子状态；该群系 `has_precipitation = false`，
`WeatherManager.hasPrecipitation` 判定失败 → 走 `pinnedClear`，恒晴天（天空不变暗、无雨雷、HUD 也不显示雨）。

## 行为变化

- 有「生成后改写群系」的模组（Meteoros 平流层、部分地形模组的洞穴改写）现在能被正确识别。
- 普通原版地块：已加载区块的调色板与噪声采样在绝大多数情况下一致，不产生多余子状态。
- 仅影响「实际群系」判定；轮廓本身仍在 y=128 采样（`SAMPLE_BLOCK_Y`），没有变。

## 使用提醒

- Meteoros 侧要把 `libs/` 里的前置 jar 换成 `localizedrain-0.1.1.jar`，并同步 `build.gradle` 的
  `implementation files(...)` / `jarJar files(...)` 两行，再重建。
- 旧存档里已经生成过的区块若没被 Meteoros 的 `ChunkEvent.Load` 补写平流层群系，需要先执行
  `/meteoros stratofix`（Meteoros 自带命令）。

## 构建与验证

- `clean build --offline`（JDK 21）通过，产物 `build/libs/localizedrain-0.1.1.jar`。
- 只做了编译与产物检查；按用户要求没有启动客户端/专用服务器，平流层行为待用户实测。

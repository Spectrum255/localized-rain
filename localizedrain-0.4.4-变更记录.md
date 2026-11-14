# Localized rain 修改记录（0.3.2 → 0.4.4）

本文件由 Localized rain 工作区的 agent 导出，供 Weather+ 工作区 agent 核对接口与行为契约。
构建产物与测试安装在 `D:\mc\.minecraft\versions\Meteoros Test\mods`，当前启用 `localizedrain-0.4.4.jar`。

## 一、版本时间线

| 版本 | 变更 |
|---|---|
| 0.3.2 | 自定义天气到期回到晴天（不再翻转成雨） |
| 0.3.3 | 刷怪光照判定局部化（`MonsterSpawnMixin` 使用 `skyDarkenAt`） |
| 0.3.4 | 强制天气冻结计时器；解除强制后重新随机时长；`frozen` 持久化 |
| 0.3.5 | 支持无天光维度：去掉 tick/命令/API 的 `hasSkyLight` 门槛；采样高度钳制；雷暴维度判断调整 |
| 0.3.6 | `WeatherApi.typeAt` 缓存未命中时现场解析（与 HUD 路径一致）；`setAll` 在无记录维度回退到玩家所在片 |
| 0.3.7 | 无天光维度一律使用 `基础片|实际群系` 子状态，天气跟随玩家实际 3D 群系 |
| 0.3.8 | 采样格 16 方块 → 4 方块（quart）；两级填充（粗格预筛 + quart 精填）；查询缓存水平粒度改 quart |
| 0.3.9 | 退化方格空白缓存修复；同群系相邻退化方格并查集分组、共享天气；`fallback_parents` 持久化（数据版本 8） |
| 0.4.0 | 3D 列式连通分量：每 16×16 列按 section 竖直采样为段；相邻列同群系且 Y 区间相接即合并；`p3:<x>,<z>,<startY>` key；`segment_parents` 持久化（数据版本 9）；旧 `p:`/`g:`/子状态 key 失效 |
| 0.4.1 | 段采样细化到 quart（4 方块）；查询缓存竖直粒度改 quart；繁茂洞穴等边界偏移 ≤3 格 |
| 0.4.2 | 整列所有段参与分类；强制天气 `forced` 持久化、首次强制瞬时拉满雨/雷量、睡觉不清除强制状态；白名单改为“可能发生”并绕过无降水限制 |
| 0.4.3 | 新增 `weatherBiomeCandidates`（候选天气）：自然循环从候选集中抽取类型；候选即允许，不受白名单与降水限制 |
| 0.4.4 | 候选列表改为 `选择器={类型1, 类型2}` 写法（兼容旧 `a|b`） |

## 二、对 Weather+ 的接口/语义影响（重要）

1. **片 key 不可解析**：0.4.0 起为 `p3:<colX>,<colZ>,<startY>`，并做并查集规范化。请只使用基于坐标的 API（`typeAt`/`isRainingAt`/`setWeatherAt` 等），不要解析或拼装 key。
2. **`WeatherApi.typeAt` 已改为可计算路径**：0.3.6 起缓存未命中会现场解析列，不再出现“HUD 有天气、addon 收到 CLEAR”的偶发不一致。
3. **无天光维度**：
   - 0.3.5 起命令/API 允许在下界/末地设置天气；
   - 原版雨雪粒子仍不会渲染（群系降水类型为 NONE），必须由 Weather+ 粒子/雾负责视觉；
   - 下界有天花板，Weather+ 粒子生成里的 `canSeeSky` 检查会失败（见 `weatherplus-下界与粒子渲染修复要求.md`）。
4. **天气锁语义**：`weatherBiomeOverrides` 仍可强制常驻；0.4.2 起首次强制瞬时生效、睡觉不打断；但实例配置已清空覆写，改为“可能发生”。
5. **白名单/黑名单**：非空白名单 = 只有列出的群系可能发生天气；被显式白名单点名的无降水群系也会参与自然循环（0.4.2）。
6. **候选天气（0.4.3+）**：
   - 配置 `weatherBiomeCandidates`，格式 `选择器={类型1, 类型2}`；
   - 匹配候选的片自动视为允许天气，不受白名单与降水限制；
   - 候选替换该群系的内置雨/雷选择；想保留原版雨/雷需显式加入 `localizedrain:rain` / `localizedrain:thunder`；
   - 非降水候选（沙尘暴/雾/灰烬/灵魂雾/虚空）在天气阶段 `rainLevel` 保持 0，客户端只应依赖 Weather+ 视觉与 HUD；
   - 雷暴只在抽中的候选类型 `allowsThunder()` 为真时出现。
7. **`setWeather` 会过滤强制片**：被 `weatherBiomeOverrides` 锁定的片，命令/API 设置会被忽略。
8. **`/weather area` 坐标语义**：Localized rain 按 quart 列处理；Weather+ 目前按区块坐标传入，采样会粗 4 倍（不影响主要用途，但精确区域命令建议按 4 倍换算）。
9. **数据版本 9**：旧 `p:`/`g:`/`|biome` 状态不再命中，会作为孤儿被清理；升级后首次进入区域天气会重建。

## 三、实例配置现状（`config/localizedrain-common.toml`）

- `weatherBiomeWhitelist`：`#minecraft:is_nether`、`#minecraft:is_end`、`#minecraft:is_jungle`、`#minecraft:is_badlands`、`#minecraft:is_taiga`、`snowy_plains`、`frozen_peaks`
- `weatherBiomeBlacklist`：空
- `weatherBiomeOverrides`：空
- `weatherBiomeCandidates`：
  - 沙漠/恶地 → 沙尘暴
  - 下界 → 灰烬风暴 / 灵魂雾
  - 末地 → 虚空风暴 / 虚空薄雾
  - 丛林 → 雾 / 原版雨
  - 针叶林 → 阴天 / 原版雨
  - 雪原 → 暴风雪 / 原版雨
  - 冰封山峰 → 暴风雪
- `debugLogging = true`（正式发布应关闭）

## 四、实现位置（本仓库 `d:\minecraft mod\Localized rain`）

- 3D 列/段/并查集：`src/main/java/com/spectrum255/localizedrain/weather/BiomePatches.java`
- 调度、分类、发包、闪电：`weather/WeatherManager.java`
- 状态与计时器：`weather/WeatherData.java`
- 名单/覆写/候选：`weather/WeatherBiomeFilter.java`
- 配置定义：`Config.java`（`weatherBiomeCandidates`、`debugLogging`）
- 依赖方修复要求：`d:\minecraft mod\weather+\docs\weatherplus-下界与粒子渲染修复要求.md`

## 五、测试状态

- 0.3.2–0.4.4 逐步回归通过（河流连通、巨大平原/海洋退化分组、下界/末地天气、洞穴高度边界）。
- 已知限制：
  - 主世界未列入白名单/候选的群系（平原、森林、海洋、河流、沙漠以外的干燥群系等）当前永远晴天；
  - 低高度维度（下界）采样列较浅，天气覆盖以实际列段为准；
  - `segmentParents` 会随探索增长，长期存档需要后续清理策略。

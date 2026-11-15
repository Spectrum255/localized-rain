# 移动风暴系统复核处理说明（跨 agent）

- 日期：2026-11-15
- 版本：`1.0.0-preview`（内部曾用 `1.1.0-preview` / `1.2.0-preview` 标记，已统一回落）
- 来源：trae 第二轮 7 条 + 第三轮 8 条问题
- 用途：逐条给出结论与处理，供后续 agent 接手；提交与发布状态见文末。

## 第二轮（7 条）

| # | 问题 | 结论 | 处理 |
| --- | --- | --- | --- |
| 1 | P0：`data/` 未进 git，clean clone 缺档案/标签 | **已解决** | 本轮提交已包含 `src/main/resources/data/`；clean clone 构建出的 jar 含四个 `storm_profile` 与 `storm_isolated` |
| 2 | P2：维度级雨量让整维度 `isRaining()` | **部分成立** | 维度级布尔属设计取舍；炼药锅/营火/幻翼/睡觉并不读取它。文档已改为「任何依赖维度级 `isRaining()` 的逻辑（含第三方附属模组）都会被带动」 |
| 3 | P2：落雷按玩家×风暴抽签；BAND 绕过隔离判定 | **成立** | 改为每片风暴每 tick 一次抽签；洞穴/柱状分支统一走 `isThunderingAt` |
| 4 | P3：自然生成无维度守卫 | **成立** | `StormSpawner.tick` 增加 `hasSkyLight() && !hasCeiling()` 守卫，并记录副作用 |
| 5 | P3：`clearNaturalNearPlayers` 死代码 | **成立** | 已删除（含 `nearAnyPlayer`） |
| 6 | P3：`StormObject` 反向依赖网络包常量 | **成立** | 常量下沉到 `storm.StormTuning.FADE_TICKS` |
| 7 | P3：`optional()` 协议不一致静默失效 | **部分成立** | 断连保护保留；发布说明顶部已加醒目警告 |

## 第三轮（8 条）

| # | 问题 | 结论 | 处理 |
| --- | --- | --- | --- |
| 1 | P0：`data/` 仍未进 git | **成立，已解决** | 本轮提交包含 `data/`；`git ls-files src/main/resources/data` 非空 |
| 2 | P2：维度级 `isRaining()` 影响面被低估 | **成立** | 已知限制改为「任何依赖维度级 `isRaining()` 的逻辑（含第三方附属模组）都会被带动」，不再替第三方保证无影响 |
| 3 | P2：落雷落点均匀取盘面、`covered` 不看 Y | **成立** | 落点改为跟随一名被 `storm.covers(...)`（含 Y 区间）覆盖的玩家，范围 `min(radius, 128)`；每片风暴每 tick 仍只抽一次 |
| 4 | P2：维度守卫副作用未记录 | **成立** | 文档写明：自定义「露天无天光」维度与带天花板地下维度不会自然生成风暴；手动生成时新增 `no_skylight` 提示 |
| 5 | P3：洞穴落点可能落在封闭空腔，属无效计算 | **部分成立** | 落点收窄到玩家 128 格内后大幅减少；仍不校验正上方开口，作为低优先级记录在已知限制 |
| 6 | P3：工程状态全部未跟踪 | **已解决** | 本轮把源码、`data/`、文档、lang 全部纳入提交（`.trae/` 与 spark 按 `.gitignore` 不提交） |
| 7 | P3：`StormManager` 仍直接构造并发网络包 | **接受为技术债** | 常量依赖已解；完全解耦需要事件总线/监听器，留待后续迭代。已在发布说明「已知限制」登记 |
| 8 | P3：协议版本描述写成 `"3"→"5"` | **成立** | 改为 `"4"` → `"5"`（`"4"` 增加 `dyingTicksLeft`） |

## 验证

- `clean build --offline`（JDK 21）：**BUILD SUCCESSFUL in 21s**。
- 产物 `build/libs/localizedrain-1.0.0-preview.jar`：**195,440 字节**，SHA256
  `92BB9668F8B3587F48DFBFD569AD021E26223F6A33FF41F6D7F651D2BEC93D50`。
- jar 内确认含：`version="1.0.0-preview"`、`displayName="Localized Rain"`、`license="MIT"`，
  以及四个 `storm_profile` 与 `storm_isolated` 标签。
- 未做运行期验证（落雷新逻辑、维度守卫、手动提示、联机协议一致性均待实机/联机测试）。

## 发布状态

- 版本号：`1.0.0-preview`（`gradle.properties` 的 `mod_version`）。
- 提交内容：移动风暴系统全部源码、`src/main/resources/data/`、README/文档、lang 键。
- `.trae/` 过程文档与 `docs/spark-*` 采样保留在本机，不进入仓库。
- 发布说明：`docs/release-1.0.0-preview.md`（旧的 1.1.0/1.2.0 发布说明已合并删除）。

## 仍需实机确认

- 多玩家同处一片雷暴：落雷频率应接近单人（不随玩家数放大），且闪电落在玩家 128 格内。
- 隔离群系内的地下 `BAND` 风暴：不应再出现闪电。
- 下界/末地：无自然风暴；手动生成有提示。
- 客户端为旧协议版本时：能进服、无风暴、无报错（静默失效属预期）。

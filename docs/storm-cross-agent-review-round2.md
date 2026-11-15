# 移动风暴系统复核处理说明（跨 agent）

- 日期：2026-11-15
- 版本：`1.0.0-preview`（内部曾用 `1.1.0-preview` / `1.2.0-preview` 标记，已统一回落）
- 来源：trae 第二轮 7 条 + 第三轮 8 条问题
- 用途：逐条给出结论与处理，供后续 agent 接手；提交与发布状态见文末。

## 第二轮（7 条）

| # | 问题 | 结论 | 处理 |
| --- | --- | --- | --- |
| 1 | P0：`data/` 未进 git，clean clone 缺档案/标签 | **已解决** | 本轮提交已包含 `src/main/resources/data/`；clean clone 构建出的 jar 含五个 `storm_profile` 与 `storm_isolated` |
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

## 第三轮补充（6 条）

| # | 问题 | 结论 | 处理 |
| --- | --- | --- | --- |
| 1 | 落雷「先掷骰、后校验」，无效落点白耗概率 | **成立** | 改为先找有效落点（最多 4 次尝试）再掷骰；全部无效则不消耗抽签 |
| 2 | `covered` 每 tick 每片风暴 `new ArrayList<>()` | **成立** | 改为两遍遍历按序号取玩家（`pickCoveredPlayer`），无列表分配 |
| 3 | 无天光维度只提示不阻止，且提示早于生成 | **部分成立** | 保留生成（API 需要），提示改到生成成功之后，顺序不再错乱 |
| 4 | `docs/storm-system.md` 引用已删除的 1.2.0 发布说明 | **成立** | 改为引用 `release-1.0.0-preview.md`，章节名去掉过时版本号 |
| 5 | `docs/idea.md` 是 0 字节空文件 | **不成立** | 实际 351 字节、9 行设计目标，无需修改 |
| 6 | spark 与旧会话导出（5.3MB + 1.4MB）留在源码树 | **成立** | 已移到 `D:\minecraft mod\_local-artifacts\`，源码树只保留仓库内容 |

## 第四轮（2026-11-15 18:32，本轮）

| # | 结论 | 改动 |
|---|---|---|
| 1 | 维度级 `isRaining()` 带动第三方 → 改为不写回 | `WeatherManager.sweep`、`StormManager.tick` 删除 `setRainLevel/setThunderLevel`；原版路径的骷髅马陷阱在 `WeatherManager.spawnLightning` 补回 |
| 2 | 无天光维度手动风暴只提示 → 改为拒绝 | `StormManager.allowsManualStorms`（`hasSkyLight && !hasCeiling`），`spawn` 与命令两处提前失败；删除 `warnIfNoSkylight` |
| 3 | 协议版本不一致静默失效 → 登录时提示 | `Localizedrain` 登录用 `NetworkRegistry.hasChannel` 检查两个 payload，缺失时日志警告 + 玩家聊天提示 |
| 4 | 洞穴落雷不校验正上方开口 → 加限 | `findStrikePos` 非 `SURFACE` 分支要求 `level.canSeeSky(candidate)` |
| 5 | 领域层直接构造网络包 → 分层 | 新增 `storm/StormSyncData` 与 `network/StormSyncBroadcaster`；`StormManager` 只产出投影数据 |

- 构建：`clean build --offline`（JDK 21）**BUILD SUCCESSFUL in 18s**；最新产物 **203,529 字节**，
  SHA256 `4BC81386AC3EBA8B4EF9F2A952FEF11F0188C6E49E1C7725EE06DAC6342514E1`。
- 该构建尚未提交、未更新 GitHub Release。
- 仍需实机确认：无维度级雨量后客户端雨声/粒子和 HUD 是否正常；片模式骷髅马陷阱实际触发；联机旧版本提示文案。

## 第五轮（2026-11-15 19:0x，最新构建复核-20261115.md）

| # | 结论 | 改动 |
|---|---|---|
| 1 | P1 回归：移除维度级雨量后积雪/炼药锅失效 | `ServerLevelWeatherMixin` 的 `tickPrecipitation` 注入改为 `@Redirect` `ServerLevel;isRaining()Z` → `WeatherManager.isPrecipitatingAt(level, pos)`；`Level#isRaining()` 对第三方仍为晴天 |
| 2 | P2 `allowsManualStorms` 一刀切误伤 BAND | `StormManager.spawn`、`spawnChecked`、`spawnStormGlobal` 只对 `SURFACE` 拒绝；`BAND`/`COLUMN` 放行 |
| 3 | P2 `/weather rain` 无天光维度静默失败 | `StormManager.setAllFromVanilla` 开头检查并给维度内玩家发 `no_skylight_blocked` |
| 4 | P2 客户端忽略天气类型 | 快照增加 `hasPrecipitation`/`allowsThunder`（协议 `"5"`→`"6"`）；`ClientStormWeather` 按能力位过滤雨雷 |
| 5 | P3 落雷逻辑重复 | 抽出 `weather/WeatherLightning.strike`，片模式与移动模式共用 |
| 6 | P3 领域层仍依赖网络门面 | 新增 `storm/StormSyncSink` 接口，`StormManager` 通过接口发包；`StormSyncBroadcaster` 在 `Localizedrain` 启动时安装 |
| 7 | P3 洞穴风暴零落雷 | 明确为「洞穴风暴只下雨、不落雷」（`canSeeSky` 限制），写入发布说明与 README |

- 构建：`clean build --offline`（JDK 21）**BUILD SUCCESSFUL in 17s**。
- 未提交、未更新 Release。

## 第六轮（2026-11-15 19:0x，本轮复核）

| # | 结论 | 改动 |
|---|---|---|
| 1 | P1 `tickPrecipitation` redirect 用错坐标（入参 y 0–15） | 重定向内先 `getHeightmapPos(MOTION_BLOCKING, pos)`，原始坐标兜底 BAND 洞穴风暴；积雪/炼药锅恢复 |
| 2 | P2 `/weather rain` 在无天光维度整体 return | 改为先找匹配类型且非 `SURFACE` 的档案，找不到才提示 |
| 3 | P2 HUD 对非降水类型仍显示下雨 | `sendHud` 按 `hasPrecipitation`/`allowsThunder` 计算 raining/thundering |
| 4 | P3 登录先发包、后检查通道 | 改为先 `hasChannel` 检查，成功才 `sendTo` |
| 5 | P3 `StormManager` 类声明与 javadoc 同行 | 拆行 |
| 6 | P3 `WeatherManager` 残留 import | 删除 `DifficultyInstance`/`EntityType`/`LightningBolt`/`SkeletonHorse`/`Blocks` |
| 7 | 命令 BAND 入口 | 已存在 `/weather storm spawn <profile> <radius> band <minY> <maxY>`，写入 README/发布说明 |

- 构建：`clean build --offline`（JDK 21）**BUILD SUCCESSFUL in 15s**。
- 未提交、未更新 Release。

## 第七轮（2026-11-15 19:54，本轮）

| # | 结论 | 改动 |
|---|---|---|
| 1 | 出厂数据无 BAND/COLUMN 档案，`/weather rain` 在无天光维度仍只提示 | 新增 `cave_rain.json`（`band`、`natural:false`、半径 48–96）；`cave_thunder.json` 随后移除：洞穴不落雷，雷暴档案只会让 HUD/雷声误导 |
| 2 | BAND 档案经 `/weather` 退化成整列雨 | `StormManager.spawn` 为无显式 band 的 BAND 档案补默认带（中心 = 生成点 Y，半高 48，夹世界高度） |
| 3 | 手动上限截断静默 | `setAllFromVanilla` 统计 `spawned`，少于玩家数时发 `partial_spawned` 提示 |
| 4 | redirect 原始坐标兜底在片模式误读地下片 | 兜底仅 `mobileMode()` 启用；片模式只看高度图顶端 |
| 5 | `resetWeatherCycle` 注入实际不可达 | `ServerLevel.tick` 睡眠门控的 `isRaining()` 重定向为「原版雨 \|\| 本地任意降水」（`WeatherData.patches()` / `StormManager.anyPrecipitation`） |
| 6 | `allowsManualStorms` 注释旧、跨维度 `sendTo` 无通道检查 | 注释改为「只约束 SURFACE」；`PlayerChangedDimensionEvent` 先检查两个通道 |

- 构建：`clean build --offline`（JDK 21）**BUILD SUCCESSFUL in 26s**；jar **207,900 字节**，
  SHA256 `B658932BF1F22CC36C1CA8C85A035BA64DBF46EA9B7D14EE1215E42A96AF35AB`，含 6 个 `storm_profile`。
- 未提交、未更新 Release。

## 第八轮（2026-11-15 20:40）

| # | 结论 | 改动 |
|---|---|---|
| 1 | 自然洞穴风暴可能选到雷暴档案（BAND 永不落雷） | `StormProfiles.randomNatural(level, random, filter)` 重载；`StormSpawner` 洞穴分支只接受 `stage(0).type() == rain` |
| 2 | `/weather` 固定 512 半径覆盖 `cave_rain` 的 48–96 | `setAllFromVanilla` 传 0；`spawnManual` 只在 `radius > 0` 时覆盖档案区间；`vanillaCommandStormRadius` 改为 `storm global` 的默认值 |
| 3 | 文档仍写「四个档案」 | README 中英、release、本表改为 5 个（含 `cave_rain`） |
| 4 | 显式 `band` 雷暴在无天光维度仍可生成 | `StormManager.spawn` 对「无天光/天花板 + 非 SURFACE + `allowsThunder`」直接返回 null；命令两处提前报 `thunder_unavailable` |

- 构建：`clean build --offline`（JDK 21）**BUILD SUCCESSFUL in 19s**；未提交、未更新 Release。

## 验证

- `clean build --offline`（JDK 21）：**BUILD SUCCESSFUL in 19s**（第三轮补充修复后）。
- 最新产物 `build/libs/localizedrain-1.0.0-preview.jar`：**195,705 字节**，SHA256
  `24060F3562B18C4485BCDD28572DD5B22954A7CD9F54014E3A86B9D382F73871`。
- 已发布 GitHub Release `v1.0.0-preview` 的资产是上一轮构建：195,440 字节、SHA256
  `92BB9668F8B3587F48DFBFD569AD021E26223F6A33FF41F6D7F651D2BEC93D50`（对应提交 `bb46011`），
  尚未包含第三轮补充的 6 条修复。
- jar 内确认含：`version="1.0.0-preview"`、`displayName="Localized Rain"`、`license="MIT"`，
  以及五个 `storm_profile` 与 `storm_isolated` 标签。
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

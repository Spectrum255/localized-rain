# Localized Rain（局部有雨）

[English](README.en.md) | 简体中文

把原版 Minecraft 的「整个维度一起变天」改成**按群系独立判定**的天气模组。

天气单位是「3D 群系片」：每个 16×16 方块的水平列按 quart（4 方块）采样出一条竖直的群系段列表，
相邻列的同群系段只要 Y 区间相接就合并成同一个连通分量（并查集）。因此洞穴群系、浮岛群系、
下界/末地的立体群系都各自拥有独立的天气，不再有「地表高度」或「|子状态」的近似。
服务端会推进所有已加载区块对应的片；未加载区域的天气冻结、状态保留。

玩家跨片时天空、雨声、雨雪粒子约有 5 秒的渐变，而 Xaero's Minimap 的天气信息行会立即切换。

- Minecraft **1.21.1**
- NeoForge **21.1.265+**
- Mod ID `localizedrain`，包名 `com.spectrum255.localizedrain`

## 功能

- 天气单位是「3D 群系片」：16×16 方块的列按 quart 逐格采样群系，得到竖直的群系段；
  相邻列的同群系段 Y 区间重叠时并入同一连通分量。规范 key 取行列/段坐标字典序最小者，
  合并时天气状态随之迁移，父指针随存档持久化，所以跨重启稳定。
- 同类型但不相连的群系各自有天气；同一个 3D 连通分量内的所有位置共享天气。
  洞穴、浮岛、下界/末地的立体群系都能正确归属，不再依赖采样高度或 3D 子状态后缀。
- 服务端推进**所有已加载区块**对应的群系片（按 `weatherSimulationIntervalTicks` 的间隔批量推进，
  每次把每片推进同等数量的 tick，总速率不变）。默认按整列段推进（含洞穴/浮岛等）；
  关闭 `scan3dSubstates` 时只推进每列地表所在的那一段。未加载/已卸载的片冻结、状态保留。
- 无天光维度（下界、末地）也参与天气判定，默认晴天；用命令/API 设置的天气会按自己的时长走完。
- 长期不活跃的天气状态会自动清理（`stateRetentionDays` + `maxStoredStates`），并顺带清理
  没有状态引用的并查集父节点，避免存档无限增长。
- 原版的雨/雷量、天空亮度、亡灵燃烧、雷暴刷怪都按「实体所在片」局部化。
- 雷暴片会按原版密度显式生成闪电（1/100000 每区块每 tick 的等价概率），不再依赖原版只对全局雷暴生效的抽签。
- 客户端渲染与雨声沿用服务端按玩家做的 0.01/tick 平滑，跨片约 5 秒渐变。
- 支持按生物群系配置天气白名单、黑名单、强制天气，以及**候选天气**（每个群系可配置一组可能出现的类型，
  随机阶段结束时抽签）；内置 `clear`/`rain`/`thunder`，也可以是附属模组注册的任意类型（见下方「配置」）。
- 性能：列扫描有每 tick 预算（`maxColumnScansPerTick` + 2ms/tick，超预算的位置下次再算）；
  扫描按「整列一次、分类全部段」执行；天气热查询按 quart 格做 LRU 缓存；
  状态推进支持一次前进 N tick（计时器不翻转时用闭式快进），扫描间隔不再带来额外 CPU 开销。
- 天气类型是一个独立的 NeoForge 注册表（`localizedrain:weather_type`），附属模组可以注册自己的类型，
  并通过 `WeatherApi` 查询/设置；服务端判定（`isRainingAt`、`isPrecipitatingAt`、`isThunderingAt` 等）
  全部按类型工作。详见下方「天气类型（lib API）」。

## 配置

`config/localizedrain-common.toml`：

```toml
weatherBiomeWhitelist = []
weatherBiomeBlacklist = []
weatherBiomeOverrides = []
weatherBiomeCandidates = []
weatherSimulationIntervalTicks = 20
scan3dSubstates = true
stateRetentionDays = 7
maxStoredStates = 4096
maxParentEntries = 16384
maxColumnScansPerTick = 4
debugLogging = false
```

条目支持 `minecraft:desert`（精确）、`minecraft:*`（整个命名空间）、`#minecraft:is_ocean`（群系标签）。

`weatherSimulationIntervalTicks` 控制已加载区块的扫描间隔（默认 20 = 每秒一次，范围 1–200）。
扫描间隔只改变推进粒度：每次扫描会把每片推进「间隔」个 tick，所以天气总时长/渐变速率不变；
调到 1 表示每 tick 扫描，代价是扫描次数线性增加。

`scan3dSubstates`（默认 true）控制是否把整列的所有段都纳入天气推进：开启时一次列扫描就能覆盖
洞穴/浮岛/平流层等所有层；关闭后只推进每列地表所在的那一段，适合极端卡顿时减负
（3D 群系会退化为按地表天气处理）。

`stateRetentionDays`（默认 7，0 = 不按时间清理）与 `maxStoredStates`（默认 4096，范围 256–65536）
控制存档清理：每 1200 tick 检查一次，先删除超过保留天数没有被推进的片，再按最近推进时间淘汰到条数上限。
被清理的片下次重新加载时会以新的晴天周期开始。

`maxParentEntries`（默认 16384，范围 4096–1000000）是持久化并查集父表的硬上限：超过时会优先淘汰
没有状态引用的孤立父节点，防止父表随探索无限增长。它大致按 `maxStoredStates` 的 4 倍设定。

`maxColumnScansPerTick`（默认 4，范围 1–64）限制每 tick 最多扫描多少列；每 tick 另有 2ms 的时间预算。
玩家所在列不受预算限制，因此进新区域时不会出现「暂时按晴天算」的空档。

`debugLogging`（默认 false）打开后每 20 tick 输出 `[LRDBG]` 日志（玩家片、列扫描耗时、合并数量），
用于排查归属问题。

`weatherBiomeCandidates` 为群系配置「可能出现的天气」：`<群系选择器>=<类型>|<类型>`，也可以写成
`<选择器>={类型1, 类型2}`。阶段结束时随机抽取一种；候选类型自动视为允许，优先于内置晴/雨/雷循环；
不降水的候选不会抬高雨量，雷只在类型允许时出现。

- **白名单**：为空表示全部允许；非空时只有名单内的群系参与局地天气。
- **黑名单**：永远晴天（不推进天气、不落雷、不积雪）。
- **强制天气**：`<群系选择器>=<天气类型 id>`，例如 `minecraft:desert=clear`、`minecraft:*=rain`、
  `#minecraft:is_ocean=thunder`，也可以写任意已注册的类型，例如
  `minecraft:desert=examplemod:sandstorm`。短名 `clear`/`rain`/`thunder` 等价于内置类型。
  命中的群系片保持对应天气、不再随机切换；多条匹配时按配置顺序后者覆盖前者
  （所以可以先用 `minecraft:*` 兜底，再对个别群系单独指定）。类型 id 是**惰性解析**的：
  配置加载时不查注册表，判定时才解析，所以附属模组注册的类型可以直接写进配置。
  强制天气在服务端会瞬时把雨/雷强度拉满并冻结计时器，客户端仍按 0.01/tick 平滑显示。

优先级为 **强制天气 > 候选天气 > 黑名单 > 白名单**。被名单排除或被强制天气的群系会跳过 `/weather`
并提示跳过数量。规则都为空时行为与之前一致。

## 命令

```
/weather clear|rain|thunder [duration]
/weather clear|rain|thunder at <pos>
/weather clear|rain|thunder area <from> <to>
/weather clear|rain|thunder global
/weather type <id> [duration|at <pos>|area <from> <to>|global]
```

需要权限等级 2。`type` 后面是 `localizedrain:weather_type` 注册表里的 id（有 Tab 补全）；
内置的 `clear`/`rain`/`thunder` 是三个内置 id 的快捷写法。
`at` / 无参数作用于该点所在的 3D 群系片（按该点的实际高度判断群系）；
`area` 按水平方向以 128 方块步长采样 `from`/`to` 覆盖到的群系片（上限 512 个采样点 / 64 片，
单次最多触发 8 次列扫描，超出会提示缩小范围或先在该区域执行 `at`）；
`global` 作用于当前维度里已经记录过的所有片。

## 天气类型（lib API）

天气类型注册在 `localizedrain:weather_type`（`com.spectrum255.localizedrain.api.WeatherType`）：
`displayKey`、`hasPrecipitation`、`Biome.Precipitation`、`allowsThunder`。内置 `clear` / `rain` / `thunder`。

附属模组注册新类型（注意用自己 mod 的命名空间创建 `DeferredRegister`，id 才会是 `examplemod:sandstorm`）：

```java
private static final DeferredRegister<WeatherType> WEATHER_TYPES =
        DeferredRegister.create(WeatherTypes.REGISTRY_KEY, "examplemod");

public static final DeferredHolder<WeatherType, WeatherType> SANDSTORM =
        WEATHER_TYPES.register("sandstorm",
                () -> new WeatherType("weather.example.sandstorm", false, Biome.Precipitation.NONE, false));
```

在模组构造函数里 `WEATHER_TYPES.register(modEventBus);`，然后设置天气：

```java
WeatherApi.setWeatherAt(level, pos, ResourceLocation.fromNamespaceAndPath("examplemod", "sandstorm"), 6000);
// 或按已解析的片批量设置：
WeatherApi.setWeather(level, patches, typeId, 6000);
```

查询入口：`WeatherApi.typeAt` / `patchAt` / `isRainingAt` / `isPrecipitatingAt` / `isThunderingAt` /
`isThunderingRegionAt` / `thunderLevelAt` / `skyDarkenAt` / `patches`。
`patchAtCell(level, cellX, cellZ)` 仍按 16×16 列/区块坐标接收参数（内部换算到 quart）；
需要 quart 坐标时用 `patchAtQuart(level, quartX, quartZ[, budget])`（片的分辨率仍是 16×16 列）。强度复用原版 `rainLevel`/`thunderLevel` 通道，客户端渲染保持原样。

## 可选兼容

**Xaero's Minimap**：装了才会启用，未装时不加载任何 Xaero 类，也不需要它参与编译。
兼容通过「目标天气」payload + 替换内置 `weather` 信息行实现，HUD 立即切换，渲染仍保留渐变。

## 构建

需要 JDK 21（Gradle 9.2.1 不支持更新的 JDK）。首次构建需要联网下载 NeoForge 依赖；
依赖缓存完整后可以离线构建：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

产物在 `build/libs/localizedrain-<version>.jar`。

## 已知限制

- 客户端雨雪粒子按玩家所在片的雨量渲染，不做逐方块局部化（这是有意设计）：原版只有一条围绕相机的雨柱，
  跨片时靠 0.01/tick 的雨量渐变过渡。
- 水平分辨率是 16 方块的列、垂直分辨率是 quart（4 方块）；更薄或更窄的群系夹层可能被量化到相邻段。
- 列扫描按区块中心采样，一列内的群系变化以中心为准。
- 玩家所在列不受扫描预算限制：首次进入完全未探索的区域时仍会有一次完整的列扫描开销。
- 未加载的群系片不推进、状态保留；从未被扫描到的片按晴天处理。
- 被清理机制删除的片没有持久天气记忆：再次加载时会从新的晴天周期开始。
- 默认 1 秒的扫描间隔意味着天气切换（下雨/放晴）最多晚 1 秒生效；雨量渐变仍按 0.01/tick 输出。
- 旧版（`p:`/`g:` 以及 `|biome` 子状态）存档 key 在载入时会被丢弃，不迁移旧天气状态。
- 候选/自定义天气类型只有服务端的天气语义；粒子、音效、雾效需要附属模组自己渲染。

## 文档

- `docs/idea.md`：设计目标
- `docs/release-1.0.0-preview.md`：1.0.0-preview 发布说明（默认过滤器清空、手动天气覆盖名单）
- `README.en.md`：README 的英文版

## 许可

MIT License，全文见仓库根目录的 `LICENSE`。
`gradle.properties` 的 `mod_license` 同为 `MIT`，会写入构建产物的 `neoforge.mods.toml`。

本仓库不包含 Xaero's Minimap / XaeroLib 的 jar，也不包含任何第三方模组的二进制文件。

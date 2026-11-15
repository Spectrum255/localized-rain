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
- 支持按生物群系配置天气白名单、黑名单与强制天气，格式统一为「群系=天气」：白名单限定该群系的天气池，
  黑名单从池中剔除指定天气，强制天气让该群系固定为一种天气。天气类型可以是内置 `clear`/`rain`/`thunder`、
  附属模组注册的任意类型，或用标签 `#localizedrain:dry` 引用一组类型（见下方「配置」）。
- 性能：列扫描有每 tick 预算（`maxColumnScansPerTick` + 2ms/tick，超预算的位置下次再算）；
  扫描按「整列一次、分类全部段」执行；天气热查询按 quart 格做 LRU 缓存；
  状态推进支持一次前进 N tick（计时器不翻转时用闭式快进），扫描间隔不再带来额外 CPU 开销。
- 天气类型是一个独立的 NeoForge 注册表（`localizedrain:weather_type`），附属模组可以注册自己的类型，
  并通过 `WeatherApi` 查询/设置；服务端判定（`isRainingAt`、`isPrecipitatingAt`、`isThunderingAt` 等）
  全部按类型工作。详见下方「天气类型（lib API）」。
- 新增**移动天气模式**（`weatherMode`）：风暴是「水平圆 + 竖直区间」，可随风漂移、按档案阶段演化；
  与原有的群系片模式、纯原版模式严格互斥。详见「三种天气模式」。

## 三种天气模式

`weatherMode` 三选一，默认 `patches`；三种模式严格互斥，同一时刻只有一套系统在推进天气：

| 模式 | 行为 | 备注 |
|---|---|---|
| `patches` | 默认。3D 群系片天气：每个 16×16 列的群系段独立判定、按片推进。 | 原有行为，完全不变 |
| `mobile` | 独立的风暴系统：风暴是水平圆 + 竖直区间，随风移动、按档案阶段演化；客户端按玩家到风暴的距离与高度计算目标雨雷量，平滑复用原版雨雪/雷通道。 | 没有风暴（自然生成或命令/API）时全晴 |
| `vanilla` | 完全交还原版：模组所有天气注入直通（Mixin 直接放行），不再接管降水、天空亮度与落雷。 | 适合与其它天气模组共存 |

模式只能改配置文件里的 `weatherMode`，**切换必须重启游戏**；各模式的存档数据互相保留，切回后继续生效。

## 配置

`config/localizedrain-common.toml`：

```toml
weatherMode = "patches"
weatherBiomeWhitelist = []
weatherBiomeBlacklist = []
weatherBiomeOverrides = []
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

`weatherBiomeCandidates` 已删除（旧配置项会被忽略）。三条名单统一使用「`<群系选择器>=<天气>`」：

- **白名单** `weatherBiomeWhitelist`：命中的群系自然循环只会在列出的天气里抽取（同一群系可以写多条）。
  例如 `minecraft:plains=clear` 加 `minecraft:plains=localizedrain:rain` 让平原只有晴天和雨。
  为空时使用默认自然池：先取数据包里的 `#localizedrain:natural` 标签，标签不存在则用内置晴/雨/雷。
- **黑名单** `weatherBiomeBlacklist`：命中的群系永远不会出现列出的天气，但仍会在剩余天气里循环。
  例如 `minecraft:desert=localizedrain:rain` 让沙漠不下雨，晴天/雷暴仍可能出现。
- **强制天气** `weatherBiomeOverrides`：命中的群系永远只有这一种天气；同一群系命中多条规则时按配置顺序
  **第一条**生效（可以先用 `minecraft:*` 兜底，再对个别群系单独指定）。类型 id 惰性解析，
  附属模组注册的类型可以直接写。强制晴天是真正的锁定：命令与 API 也无法在该群系制造天气。

白名单/黑名单只约束自然循环：如果某个群系只是因为「自然池里只剩晴天」（例如白名单只写了 `clear`，
或干燥群系默认剔除降水类型）而恒晴，命令与 API 仍然可以手动制造天气；只有**强制晴天**才是硬锁定。

天气部分可以写类型 id（如 `weatherplus:blizzard`）、短名 `clear`/`rain`/`thunder`，
或天气类型标签 `#localizedrain:dry`（标签只在白名单/黑名单里有效，强制天气必须写一种具体类型）。

没有显式白名单的群系，如果本身不降水（`Biome#hasPrecipitation() == false`，例如原版下界/末地群系），
会自动把「会降水或会打雷」的类型从池里剔除，所以默认永远是晴天，不需要再写强制规则。

白名单与黑名单同时命中时黑名单优先（把对应天气从白名单池里剔除）。三条名单都为空时行为与之前一致。

默认自然池 `#localizedrain:natural`：本模组**不内置**标签文件；数据包可以在
`data/<namespace>/tags/localizedrain/weather_type/natural.json` 里定义它。定义后，所有没有显式白名单的
群系都从该标签抽取天气（写成空列表表示默认不再自然变天）；标签不存在时回退到内置晴/雨/雷。

### 移动天气（`weatherMode = "mobile"`）

除 `weatherMode` 外，以下键只在 `mobile` 模式下有意义：

```toml
weatherMode = "patches"
isolatedBiomeWeather = "clear"
naturalCaveStorms = false
caveStormRadius = 64
maxStormsPerDimension = 8
maxStormsPerPlayer = 2
maxManualStormsPerDimension = 16
spawnIntervalTicks = 200
spawnMinDistance = 300
spawnMaxDistance = 700
windChangeIntervalTicks = 1200
windMinSpeed = 0.02
windMaxSpeed = 0.08
despawnDistance = 1000
despawnGraceTicks = 600
manualStormRadius = 128
vanillaCommandStormRadius = 512
stormSyncIntervalTicks = 40
falloffRatio = 0.3
lightningMultiplier = 1.0
```

- `weatherMode`：`patches` / `mobile` / `vanilla`，默认 `patches`，未知取值回退 `patches` 并记警告；改动后必须重启游戏。
- `isolatedBiomeWeather`：`#localizedrain:storm_isolated` 群系里的天气，默认 `clear`；`patch` 表示这些群系继续由片天气机独立推进，`manual` 表示平时晴天、只接受命令/API 设置。
- `naturalCaveStorms`（默认 false）与 `caveStormRadius`（默认 64，范围 8–512）：是否允许地下自然生成风暴及其半径。
- `maxStormsPerDimension`（默认 8，范围 0–64）：单个维度的自然风暴上限（手动风暴不占名额）；`maxStormsPerPlayer`（默认 2，范围 0–16）：归属单个玩家的自然风暴上限。
- `maxManualStormsPerDimension`（默认 16，范围 0–128）：命令/API 手动风暴的维度上限，0 表示禁用；重复对同一片区域设置同类型天气会替换旧风暴而不是叠加。
- `spawnIntervalTicks`（默认 200，范围 20–24000）：每多少 tick 尝试生成一个自然风暴。
- `spawnMinDistance` / `spawnMaxDistance`（默认 300 / 700）：自然风暴与选中玩家的水平距离区间。
- `windChangeIntervalTicks`（默认 1200）：维度风场做一次有界随机游走的间隔——风向在当前方向 ±30° 内摆动、风速在 ±0.01 内抖动；`windMinSpeed` / `windMaxSpeed`（默认 0.02 / 0.08 方块每 tick）把风速夹在区间内。
- `despawnDistance`（默认 1000）与 `despawnGraceTicks`（默认 600）：自然风暴离所有玩家超过该距离并持续该时长后淡出。
- `manualStormRadius`（默认 128）：`setWeatherAt` 生成手动风暴的默认半径；`/weather storm area` 也把它加在区域覆盖半径上。
- `vanillaCommandStormRadius`（默认 512）：原版 `/weather rain|thunder` 为每位玩家生成手动风暴的半径。
- 命令/API 生成的手动风暴速度固定为 0，不会随风漂移；生成时显式指定半径的手动风暴在阶段切换时也不会被重置为档案半径。
- 自然风暴的生成点同样遵守天气过滤器：强制晴天、白名单池里没有该类型、或黑名单剔除了该类型时都不会生成；
  被强制成某一种天气的群系只会生成该类型的风暴。
- `/weather clear`、睡觉跳夜或原版清天气会发出 `StormRemoveEvent`（`Reason.CLEARED`）并立即同步给客户端。
  `mobile` 模式下 `/weather clear` 清空该维度**全部**风暴（自然 + 手动）；睡觉跳夜只清自然风暴，保留手动风暴。
- `stormSyncIntervalTicks`（默认 40）：完整风暴列表重新同步给客户端的间隔。
- `falloffRatio`（默认 0.3）：风暴边缘渐变带宽占半径的比例。
- 联机时客户端使用服务端下发的 `isolatedBiomeWeather` 与 `falloffRatio`（随风暴快照同步），
  本地配置只在单人游戏或还没收到快照时生效。
- `lightningMultiplier`（默认 1.0）：风暴落雷频率倍率。

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

### `/weather storm`（`mobile` 模式）

```
/weather storm list
/weather storm spawn <profile> [radius]
/weather storm spawn <profile> <radius> band <minY> <maxY>
/weather storm remove <id>|all
/weather storm stage <id> <index>
/weather storm advance <id>
/weather storm intensity <id> <rain> <thunder>
/weather storm wind <x> <z>
/weather storm at <pos> <profile> [radius] [duration]
/weather storm area <from> <to> <profile> [duration]
/weather storm global <profile> [radius] [duration]
```

`profile` 是 `localizedrain:storm_profile` 注册表里的 id（有 Tab 补全；内置 `drizzle` / `shower` / `squall` / `thunderstorm` / `cave_rain`，其中 `cave_rain` 是 `band` 档案，供无天光/带天花板维度的 `/weather rain` 使用），
`id` 是 `list` 输出里的风暴 UUID。

- `spawn` 以执行者为圆心生成手动风暴；省略 `radius` 时使用档案的 `radius` 区间。`band <minY> <maxY>` 生成带竖直区间的风暴（洞穴/高空）。
- `at` / `area` / `global`：`area` 以两角中点为中心、半径为水平对角距离的一半再加 `manualStormRadius`；`global` 为每位玩家各生成一个（半径默认 `vanillaCommandStormRadius`）；`duration` 省略时按档案阶段走完。
- `remove all` 清空该维度的全部风暴（含手动风暴）；`remove <id>` 只删一个。
- `intensity` 覆盖当前阶段的雨/雷强度（0–1）；`advance` 进入下一阶段；`stage` 跳到指定阶段（从 0 开始）；`wind` 直接改写维度风场。

在 `mobile` 模式下，原有的 `/weather clear|rain|thunder` 也会走风暴系统：`clear` 清空该维度全部风暴（自然 + 手动），
`rain` 以每位玩家为圆心生成一个半径 `vanillaCommandStormRadius` 的手动风暴，`thunder` 相同但类型是雷暴。
`storm` 子树只在 `mobile` 模式下影响天气；其它模式下可以增删风暴数据，但天气仍由当前模式的系统决定。

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

### 移动天气（`mobile` 模式）

风暴档案是数据包注册表 `localizedrain:storm_profile`，JSON 放在 `data/<namespace>/storm_profile/<name>.json`：

```json
{
  "weight": 20,
  "natural": true,
  "speed_multiplier": 1.0,
  "radius": [150, 300],
  "vertical": "surface",
  "stages": [
    {
      "type": "localizedrain:rain",
      "min_ticks": 4000,
      "max_ticks": 8000,
      "rain": [0.4, 0.8],
      "thunder": [0.0, 0.0],
      "radius_scale": [1.0, 1.0]
    }
  ],
  "environment": {
    "stage_speed_from_moisture": true,
    "moisture_gain": 0.002,
    "moisture_decay": 0.001
  }
}
```

- `weight`（默认 10）是自然抽取权重，`natural`（默认 true）决定该档案是否进入自然池。
- `speed_multiplier`（默认 1.0）是按风场移动的速度倍率。
- `radius` 是 `[min, max]` 两元数组（默认 `[150, 300]`）。
- `vertical` 取 `surface` / `band` / `column`：三者的水平圆与 `minY`–`maxY` 竖直范围判定相同，只有 `surface` 额外要求见天，`band` / `column` 用于洞穴与高空风暴。
- `stages` 至少一项：`type`（天气类型 id）、`min_ticks` / `max_ticks`（默认 4000 / 8000）、`rain` / `thunder`（强度区间，默认 `[0.4, 0.8]` / `[0, 0]`）、`radius_scale`（半径倍率区间，默认 `[1, 1]`），可选 `advance_when_moisture` / `die_when_moisture`。
- `environment`：`stage_speed_from_moisture`（默认 true）、`moisture_gain`（默认 0.002）、`moisture_decay`（默认 0.001）。

内置档案：`drizzle`、`shower`、`squall`、`thunderstorm`。

查询：`WeatherApi.weatherMode` / `stormsAt` / `listStorms` / `storm` / `wind` / `isStormIsolatedAt`。

写入：`WeatherApi.spawnStorm` / `removeStorm` / `removeStorms` / `advanceStage` / `setStage` / `setVelocity` / `setIntensity` / `setWind`。

```java
StormSnapshot storm = WeatherApi.spawnStorm(level, StormSpawnSpec
        .builder(ResourceLocation.fromNamespaceAndPath("localizedrain", "thunderstorm"), Vec3.atCenterOf(pos))
        .radius(256)
        .stage(0)
        .manual(true)
        .lifetime(6000)
        .build());
```

事件：`StormSpawnEvent`（可取消）、`StormRemoveEvent`（`Reason` 为 `MANUAL` / `FINISHED` / `FAR_AWAY` / `MISSING_PROFILE` / `CLEARED`）、
`StormStageChangeEvent`。

无天气区：把群系加入群系标签 `#localizedrain:storm_isolated`
（`data/<namespace>/tags/worldgen/biome/storm_isolated.json`）后，该群系内的风暴不产生雨、雷、闪电，天空也不变暗。
`isolatedBiomeWeather` 控制这些群系的天气来源：`clear` 永远晴天，`patch` 继续用群系片天气机推进，`manual` 只在命令/API 设置时才有天气。

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
- 自定义天气类型只有服务端的天气语义；粒子、音效、雾效需要附属模组自己渲染。
- `mobile` 模式下 `WeatherApi.setWeather(level, patches, ...)`（按片 key 的旧入口）不生效，只会记录一次警告；
  带坐标的 `setWeatherAt` 正常工作（生成一个单阶段手动风暴）。批量控制请改用 `spawnStorm` / `removeStorms`。
- 本模组不写回维度级 `rainLevel/thunderLevel`：`Level#isRaining()`/`getRainLevel()` 保持原版晴天，
  依赖它们的第三方逻辑（包括附属模组）不会感知局地雨雪；请改用 `WeatherApi` 的按位置查询。
  原版的积雪与炼药锅集水已通过 `tickPrecipitation` 内的重定向按区域恢复。
- 维度守卫：露天但无天光、或带天花板的维度不自然生成风暴；`SURFACE` 手动风暴会被拒绝，
  但内置 `cave_rain`（`BAND`）档案让 `/weather rain` 在该类维度自动生成本地洞穴风暴，
  也可以显式使用 `band` 子命令；未显式给区间的 `BAND` 档案以生成点为中心取半高 48 的带。
- 非 `SURFACE` 的雷暴类型在上述维度被直接拒绝（命令返回 `thunder_unavailable`）：洞穴风暴永远不会落雷。
  洞穴不提供雷暴档案（`/weather thunder` 在该类维度直接提示）：`BAND` 落雷要求见天，下界恒不落雷。
- 地下 `BAND`/`COLUMN` 风暴只下雨、不落雷：落雷要求落点正上方有开口（`canSeeSky`）；
  片模式与移动模式都按原版难度概率补回了骷髅马陷阱（`visualOnly` 闪电代替伤害）。
- 客户端与服务端版本不一致时，登录会收到网络协议提示（此前为静默失效）；协议版本 `"6"`。

## 文档

- `docs/idea.md`：设计目标
- `docs/storm-system.md`：移动天气系统（内部实现参考：架构、数据模型、配置、命令、API、同步与已知限制）
- `docs/release-1.0.0-preview.md`：1.0.0-preview 发布说明（移动天气系统、过滤规则、风暴一致性修复）
- `README.en.md`：README 的英文版

## 许可

MIT License，全文见仓库根目录的 `LICENSE`。
`gradle.properties` 的 `mod_license` 同为 `MIT`，会写入构建产物的 `neoforge.mods.toml`。

本仓库不包含 Xaero's Minimap / XaeroLib 的 jar，也不包含任何第三方模组的二进制文件。

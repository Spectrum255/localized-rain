# Localized Rain 局部有雨

简体中文 | [English](README.en.md)

Localized Rain 是一个面向 Minecraft 1.21.1 / NeoForge 的天气模组。

<br />

## 它做了什么?

Minecraft 原版的天气总是随机触发且全局生效的，这不合理。因此本模组完全重构了 MC 的天气系统，将其改为范围生效的形式。

<br />

## 三种天气模式

`weatherMode` 三选一，默认为 `patches`，可在配置文件中切换。

| 模式        | 说明                                                       |
| --------- | -------------------------------------------------------- |
| `patches` | 3D 群系片天气：每一块连通的同群系区域（含洞穴、浮岛等立体结构）都有独立的天气状态                |
| `mobile`  | 移动天气系统：天气由可漂移的「水平圆 + 竖直区间」风暴承载，根据风暴自身存储的状态进行有规律的演化         |
| `vanilla` | 关闭本模组此功能，保持原版天气形式                                         |

三种模式互斥，各自的存档独立保留，切换时不做状态迁移。

## 片模式（`patches`）

- 已加载区块的群系片会按 `weatherSimulationIntervalTicks`（默认 20 tick）成批推进，未加载区域的天气状态将被冻结。
- 每个片有自己的晴天计时器、降雨计时器、雷暴延迟与雷暴计时，数值取自配置的 tick 区间。
- 雨量与雷量按 0.01/tick 渐变，跨片切换时天空与雨声约 5 秒过渡，不会瞬间跳变。
- 支持自定义天气类型：只有加入自然池标签 `#localizedrain:natural`，或被写进对应群系的白名单后，注册在 `localizedrain:weather_type` 里的类型才会参与自然循环（详见 API 部分）。
- 不降水的群系（下界、末地原版群系，或 `Biome#hasPrecipitation()==false` 的群系）默认持续处于晴天状态。

## 移动模式（`mobile`）

天气由**风暴**承载：每个风暴是一个水平圆 + 竖直区间，会随风场漂移，并按档案阶段演化。

- 风暴档案是数据包注册表 `localizedrain:storm_profile`。内置五个风暴档案：
  `drizzle` / `shower` / `squall` / `thunderstorm`（自然，`surface`）与
  `cave_rain`（`band`，非自然，供无天空光照/有天花板的维度使用）。
  自定义档案放在 `data/<数据包命名空间>/localizedrain/storm_profile/<id>.json`：NeoForge 把注册表的命名空间
  也作为目录前缀，所以路径里会出现两次 `localizedrain`；文件的命名空间就是档案 id 的命名空间。
- 这些参数分别描述生成权重、是否自然生成、移动速度倍率、半径、竖直模式（`SURFACE` / `BAND` / `COLUMN`），
  以及阶段类型、雨/雷强度、阶段时长与环境演化参数。
- 每个维度有一套风场，风场方向每隔 `windChangeIntervalTicks` 做一次有界随机浮动。
- 水分与温度决定风暴推进速度、是否提前进入下一阶段或消亡；自然生成遵守天气过滤器：不会产生某类天气的群系不会生成该类型的风暴，但已经生成的风暴飘过被过滤的群系时不受影响。
- 客户端由完整快照驱动（每 `stormSyncIntervalTicks` 校正一次，生成/移除/换阶段立即推送）；
  `isolatedBiomeWeather` 与 `falloffRatio` 随快照下发，联机时以服务端为准。
- 落雷按风暴的雷量与 `lightningMultiplier` 概率生成；原版难度只影响骷髅马陷阱，陷阱会以 `visualOnly` 闪电代替伤害。
- 强制天气在移动模式下的含义不同：`weatherBiomeOverrides` 只过滤自然风暴的生成点，不会为强制雨/雷的群系生成常驻降水（详见下方「群系天气过滤器」）。

## 群系天气过滤器：白名单 / 黑名单 / 强制天气

三条名单统一写作 `<群系选择器>=<天气>`，例如：

```toml
weatherBiomeOverrides = ["minecraft:desert=clear"]
weatherBiomeWhitelist = ["minecraft:plains=clear", "minecraft:plains=rain"]
weatherBiomeBlacklist = ["#minecraft:is_ocean=thunder"]
```

选择器支持具体群系、`命名空间:*` 与 `#群系标签`；天气支持类型 id、短名 `clear`/`rain`/`thunder`，
白名单/黑名单还支持 `#天气标签`（默认自然池是 `#localizedrain:natural`，标签不存在时回退内置晴/雨/雷）。

| 名单                      | 语义                                              |
| ----------------------- | ----------------------------------------------- |
| `weatherBiomeOverrides` | 天气锁：对应群系锁定为该天气；多条规则命中同一群系时按配置顺序取第一条            |
| `weatherBiomeWhitelist` | 白名单：对应群系的自然循环只从天气白名单列表里抽取                       |
| `weatherBiomeBlacklist` | 黑名单：对应群系永远不会出现列出的天气，在剩余天气里继续循环                  |

强制天气的作用分模式：
- **片模式（`patches`）**：锁定该群系的天气，自然循环、命令、API 都无法改写；自然风暴也不会在该群系生成（已生成的风暴飘过时仍会降雨）。
- **移动模式（`mobile`）**：降水只来自风暴。强制到具体天气（雨 / 雷 / 自定义类型）只用于**过滤自然风暴的生成点**，不会在该群系产生常驻降水；只有强制 `clear` 会阻止命令/API 的手动设置，强制雨/雷不再拦截手动设置。若希望在移动模式下让强制群系下雨，请手动生成风暴（`/weather rain`、`/weather storm ...` 或风暴 API）。
- 白名单/黑名单按风暴档案的**初始阶段类型**判定（多阶段档案不能用后续阶段绕过过滤）；强制天气则按档案的**任意阶段**匹配（雷暴档案的初始阶段也是雨）。
- 手动风暴的半径：`/weather storm at|spawn|global` 与 API `setWeatherAt` 未指定半径时使用档案自身的 `radius` 区间（例如 `drizzle` 180–320）；`/weather storm area` 用「中心到区域最远角的距离 + 固定 64 格」作为半径，只生成一个圆形风暴近似覆盖整片区域。`band <minY> <maxY>` 子命令只指定竖直区间，不改变半径。

白名单/黑名单只约束自然循环，使用指令仍然可以手动更改被排除的天气。

## 天气类型与扩展 API

- 天气类型注册表：`localizedrain:weather_type`，内置 `clear` / `rain` / `thunder`，
  其他模组可以注册自己的类型（`WeatherType` record）。
- 判定与查询入口：`api.WeatherApi`，包括 `typeAt` / `patchAt` / `isRainingAt` / `isPrecipitatingAt` /
  `isThunderingAt` / `thunderLevelAt` / `skyDarkenAt` / `patches` / `setWeather` / `setWeatherAt`。
- 风暴事件：`StormSpawnEvent`（可取消）、`StormStageChangeEvent`、`StormRemoveEvent`；
  生成入口 `StormManager` / `StormSpawnSpec`。

## 安装

1. 安装 Minecraft 1.21.1 对应的 NeoForge（21.1.265 或更高）。
2. 服务器和客户端都需要安装本模组。

> 客户端与服务端必须使用同一版本（网络协议 `"6"`）。版本不一致时连接不会断开，
> 但登录时会收到提示、服务端日志会记录警告，风暴/天气同步会静默失效。

## 快速上手

```
# 查询/设置局部天气
/weather rain at ~ ~ ~ 6000
/weather type localizedrain:thunder area ~-64 ~ ~-64 ~64 ~ ~64 12000
/weather clear global

# 移动模式的风暴
/weather storm list
/weather storm at ~ ~ ~ shower 200 6000
/weather storm spawn thunderstorm 300 band -40 -5
/weather storm advance <id>
/weather storm remove <id>
```

## 配置

配置文件分为全局与存档两级：

| 文件 | 位置 | 内容 |
| --- | --- | --- |
| `common.toml` | `config/localizedrain/`（全局） | 调试开关（`debugLogging` / `logWeatherChanges` / `logStormSpawns`），所有存档共用 |
| `weather.toml` | 全局：`config/localizedrain/weather.toml`（新存档默认值）；存档：`<存档>/serverconfig/localizedrain/weather.toml` | 天气模式、天气模拟区间、移动风暴参数；每个存档独立 |
| `filters.toml` | 全局：`config/localizedrain/filters.toml`（新存档默认值）；存档：`<存档>/serverconfig/localizedrain/filters.toml` | 天气过滤器（群系=天气），三项列表直接位于文件根级；每个存档独立 |

每个存档首次启动时，模组会把全局 `config/localizedrain/weather.toml` 与 `filters.toml` 复制为该存档的 `<存档>/serverconfig/localizedrain/` 下的独立副本（已存在则不覆盖）；之后的修改互不影响。全新安装的第一个存档可能还没有全局文件，此时本次启动使用内置/全局默认，并从下一次启动起使用存档副本。改动已存在的存档请编辑该存档下的文件。旧版 `config/localizedrain-common.toml` 会在启动时自动拆分迁移：天气/风暴键 → `weather.toml`，三个过滤器列表 → `filters.toml`，调试键 → `common.toml`（目标文件已存在则跳过；旧文件保留作备份）。已有存档中仍为默认 `PATCHES` 的 `weatherMode` 会跟随旧文件迁移一次（迁移后在存档目录留下 `.weatherMode-migrated` 标记，之后手动改回 `PATCHES` 不会被再次覆盖），每个存档自己选过的模式不会被覆盖。

以下为主要配置项（默认值）。

### 通用与片模式

| 键                                | 默认        | 说明                                     |
| -------------------------------- | --------- | -------------------------------------- |
| `weatherMode`                    | `patches` | `patches` / `mobile` / `vanilla`，切换需重启 |
| `weatherSimulationIntervalTicks` | `20`      | 已加载群系片的推进间隔，越大越省性能                       |
| `scan3dSubstates`                | `true`    | 是否扫描洞穴/浮岛等 3D 子状态                      |
| `stateRetentionDays`             | `7`       | 片状态保留天数，`0` 关闭时间清理                     |
| `maxStoredStates`                | `4096`    | 存档中片状态上限                               |
| `maxParentEntries`               | `16384`   | 并查集父表硬上限                               |
| `maxColumnScansPerTick`          | `4`       | 每 tick 最多新扫描的列数                        |
| `debugLogging`                   | `false`   | 输出片扫描/合并的调试日志                                 |
| `logWeatherChanges`              | `false`   | 群系片天气变化时输出位置与类型                           |
| `logStormSpawns`                 | `true`    | 风暴生成时输出详细参数                                |

### 天气节奏（tick）

| 键                                               | 默认                 |
| ----------------------------------------------- | ------------------ |
| `minClearTicks` / `maxClearTicks`               | `12000` / `168000` |
| `minRainTicks` / `maxRainTicks`                 | `12000` / `24000`  |
| `minThunderDelayTicks` / `maxThunderDelayTicks` | `12000` / `180000` |
| `minThunderTicks` / `maxThunderTicks`           | `3600` / `15600`   |

### 移动模式

| 键                                       | 默认              | 说明                                    |
| --------------------------------------- | --------------- | ------------------------------------- |
| `isolatedBiomeWeather`                  | `clear`         | 隔离群系里的行为：`clear` / `patch` / `manual` |
| `naturalCaveStorms`                     | `false`         | 是否自然生成洞穴风暴                            |
| `caveStormRadius`                       | `64`            | 自然洞穴风暴半径                              |
| `maxStormsPerDimension`                 | `8`             | 自然风暴上限（手动风暴不占名额）                      |
| `maxStormsPerPlayer`                    | `2`             | 每位玩家附近自然风暴上限                          |
| `maxManualStormsPerDimension`           | `16`            | 手动风暴上限（命令/API）                        |
| `spawnIntervalTicks`                    | `200`           | 自然生成检查间隔                              |
| `spawnMinDistance` / `spawnMaxDistance` | `300` / `700`   | 生成点与玩家的距离范围                           |
| `windChangeIntervalTicks`               | `1200`          | 风场变化间隔                                |
| `windMinSpeed` / `windMaxSpeed`         | `0.02` / `0.08` | 风速范围（格/tick）                          |
| `despawnDistance`                       | `1000`          | 自然风暴远离玩家的消亡距离                         |
| `despawnGraceTicks`                     | `600`           | 远离多久后开始淡出                             |
| `stormSyncIntervalTicks`                | `40`            | 风暴快照校正间隔                              |
| `falloffRatio`                          | `0.3`           | 风暴边缘的衰减宽度比例                           |
| `lightningMultiplier`                   | `1.0`           | 落雷概率倍率，`0` 关闭                         |

## 兼容性

- **读取维度级天气的模组**：本模组不写回 `rainLevel` / `thunderLevel`，`Level#isRaining()` 保持原版晴天值。
  依赖维度级判定的第三方逻辑不会感知局部天气；需要局地天气请调用 `WeatherApi` 的按位置查询。
  原版的积雪与炼药锅集水已通过 `tickPrecipitation` 内的重定向按区域恢复。
- **Xaero's Minimap**：装了本模组与 Xaero 小地图时，天气信息行会自动替换为局地天气，
  切换片/风暴时立即更新；桥接为纯反射实现，不打包 Xaero 的类，未装 Xaero 时自动跳过。
- **附属模组 / 移动天气**：移动模式下 `WeatherApi.setWeather(level, patches, ...)` 会把每个片中心映射为一个手动风暴
  （`CLEAR_ID` 则映射为按列清除）；单次调用最多处理 `maxManualStormsPerDimension` 个片，避免对全维度片逐个扫描，
  超过部分会静默截断。需要精确范围时请使用坐标入口或 `spawnStorm`。

## 已知限制

- 客户端雨雪粒子按玩家所在片/风暴的总体雨量渲染，不做逐方块局部化；站在雨区边缘时，
  相邻晴天区域也可能看到雨丝。
- 群系片按水平 16 方块、垂直 4 方块量化；未加载区域冻结，查询时按晴天处理。
- 自然风暴只在「有天光且无天花板」的维度生成；`SURFACE` 手动风暴在无天光维度会被拒绝，
  但 `BAND` / `COLUMN` 洞穴风暴仍可手动生成。
- 地下 `BAND` / `COLUMN` 风暴只下雨、不落雷（落雷要求落点正上方有开口 `canSeeSky`）；
  无天光/带天花板维度里的非 `SURFACE` 雷暴会被直接拒绝。
- 服务器停止/换存档会清空风暴静态缓存；配置重载会复位一次性警告。
- 移动模式下 `weatherBiomeOverrides` 强制到具体天气（雨/雷/自定义类型）不会自动产生降水：该设置只过滤自然风暴的生成点，需要降水时请手动生成风暴，或改用片模式。

## 调试日志

- `debugLogging`（默认关闭）开启后，天气模拟会输出 `[LRDBG]` 前缀的片扫描与并查集合并细节。
- `logWeatherChanges`（默认关闭）开启后，任何群系片天气发生变化（类型 / 是否下雨 / 是否打雷）会输出一行：

  ```
  [LRDBG] weather change dim=minecraft:overworld pos=8,64,8 patch=p3:0,4,0 biome=minecraft:plains type=localizedrain:rain raining=true thundering=false rain=0.42 thunder=0.00 previous=localizedrain:clear|false|false
  ```

  `pos` 是片中心（水平按 16 方块量化），`patch` 是内部片标识，`previous` 是上一次的 `类型|下雨|打雷`。
- `logStormSpawns`（默认开启）控制风暴生成时的详细参数日志（维度、id、档案、坐标、半径、竖直区间、阶段、类型、强度、速度、是否手动、寿命）：

  ```
  [localizedrain] storm spawned dim=minecraft:overworld id=... profile=localizedrain:thunderstorm x=120.5 z=-88.0 radius=269.0 vertical=SURFACE minY=48.0 maxY=320.0 stage=0/3 type=localizedrain:rain rain=0.52 thunder=0.0 velocity=0.0,0.0 manual=true lifetimeTicks=-1 stageTicks=5400.0
  ```

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

产物：`build/libs/localizedrain-<版本号>.jar`。

## 许可

MIT License，详见仓库根目录的 `LICENSE`。

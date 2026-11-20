# Localized Rain 局部有雨

简体中文 | [English](README.en.md)

Localized Rain 是一个面向 Minecraft 1.21.1 / NeoForge 的天气模组。

<br />

## 它做了什么?

Minecraft 原版的天气总是随机触发且全局生效的，这不合理。因此本模组完全重构了 MC 的天气系统，将其改为范围生效的形式。

<br />

## 三种天气模式

本模组在原版天气系统之外增加了两种全新的天气模式，在配置文件中的`weatherMode` 中进行切换。

| 模式        | 说明                                                   |
| --------- | ---------------------------------------------------- |
| `patches` | 3D 群系轮廓天气系统：每一块连通的同群系区域（含洞穴、浮岛等立体结构）都有独立的天气状态        |
| `mobile`  | 移动云团天气系统：天气由可漂移的「水平圆 + 竖直区间」风暴承载，根据风暴自身存储的状态进行有规律的演化 |
| `vanilla` | 关闭本模组的新天气模式，天气状态全维度统一                                |

模组默认配置为移动云团模式。游戏进行时可用 `/weather mode <patches|mobile|vanilla>` 切换天气模式（约 1 秒内生效），也可直接编辑当前存档的 `weather.toml`。修改天气模式后，属于原本模式的天气状态将被存档，直到下一次切换回该模式后重新加载。

## 片模式（`patches`）

- 已加载区块的群系片会按 `weatherSimulationIntervalTicks`（默认 20 tick）成批推进，未加载区域的天气状态将被冻结。
- 每个片有自己的晴天计时器、降雨计时器、雷暴延迟与雷暴计时，数值取自配置的 tick 区间。
- 天气的渐变可以调节，默认配置下雨量与雷量按 0.01/tick 渐变，跨群系切换时，天空与雨声约有 5 秒过渡时间。
- 该模式支持自定义天气类型：只有加入自然池标签 `#localizedrain:natural`，或被写进对应群系的白名单后，该类型才会参与自然循环（定义方式见「自定义数据包」）。
- 不降水的群系（下界、末地原版群系，或 `Biome#hasPrecipitation()==false` 的群系）默认持续处于晴天状态。

## 移动模式（`mobile`）

- 该模式下，天气由**风暴**承载：每个风暴是一个水平圆 + 竖直区间，会随风场漂移，并按档案阶段演化。

- 风暴档案以数据包注册表 `localizedrain:storm_profile` 的形式注册（定义方式见「自定义数据包」）。本模组内置了一些基本档案类型，包括
  `drizzle` / `shower` / `squall` / `thunderstorm`（自然，`surface`）、
  `cave_rain`（`band`，非自然，供无天空光照/有天花板的维度使用），以及「内置天气预设」一节里的 9 个档案。

- `biomes` 参数将限定风暴的生成群系，留空时将使用默认过滤器；有降水标签的 `SURFACE` 档案只落在会降水的群系。当明确指定风暴生成的生物群系列表时，其生成位置则完全以列表为准（例如沙尘暴可以写沙漠）。洞穴/整列档案不受默认降水限制。\
  不会产生某类天气的群系不会生成该类型的风暴，但已经生成的风暴飘过被过滤的群系时不受影响。

- `/weather thunder` 也会匹配「自身不是 `thunder`、但 `allows_thunder=true`」的自定义类型
  （例如其他数据包定义的雷暴类型）；`/weather rain` 同理匹配任何降水类型为雨的阶段，且不会覆盖档案自己的外观。

- 每个维度有一套风场，每隔 `windChangeIntervalTicks` 调整一次：风向首次随机生成，之后在当前方向上做 ± 浮动；
  风速首次取初始值，之后在当前速度上浮动；当前速度偏离初始值超过 `windSpeedDeviationLimit` 时，暂停随机浮动、
  按 `windReturnRate` 的比例逐步回到初始值，再重新开始浮动。默认初始速度 10 格/秒（再乘档案 `speed_multiplier`）。

- 温湿度参数决定风暴推进速度、是否提前进入下一阶段或消亡。

- 落雷按风暴的雷量与 `lightningMultiplier` 概率生成；此配置同样会影响骷髅马陷阱。

- 风暴的自然生成只会在**已加载区块**内选择坐标（实际半径 = 模拟距离 − 2 区块；模拟距离 12 时约 96–160 格），并沿上风向漂向玩家；每次生成检查时按 `naturalStormSpawnChance`（默认 `0.035`，约 4.8 分钟一个）决定是否真正生成。风暴默认为在玩家一定距离内生成，配置为`stormSpawnPlacement=anywhere_loaded` 时改为在任意已加载区块生成。

## 群系天气过滤器：白名单 / 黑名单 / 强制天气

三条名单统一写作 `<群系选择器>=<天气>`，例如：

```toml
weatherBiomeOverrides = ["minecraft:desert=clear"]
weatherBiomeWhitelist = ["minecraft:plains=clear", "minecraft:plains=rain"]
weatherBiomeBlacklist = ["#minecraft:is_ocean=thunder"]
```

选择器支持具体群系、`命名空间:*` 与 `#群系标签`；天气支持类型 id、短名 `clear`/`rain`/`thunder`，
白名单/黑名单还支持 `#天气标签`（默认自然池是 `#localizedrain:natural`，标签不存在时回退内置晴/雨/雷）。

| 名单                      | 语义                                  |
| ----------------------- | ----------------------------------- |
| `weatherBiomeOverrides` | 天气锁：对应群系锁定为该天气；多条规则命中同一群系时按配置顺序取第一条 |
| `weatherBiomeWhitelist` | 白名单：对应群系的自然循环只从天气白名单列表里抽取           |
| `weatherBiomeBlacklist` | 黑名单：对应群系永远不会出现列出的天气，在剩余天气里继续循环      |

天气锁的作用在两种模式下有所不同：

- **片模式（`patches`）**：锁定该群系的天气，自然循环、命令、API 都无法改写；自然风暴也不会在该群系生成（已生成的风暴飘过时仍会降雨）。
- **移动模式（`mobile`）**：降水只来自风暴。强制到具体天气（雨 / 雷 / 自定义类型）只用于**过滤自然风暴的生成点**，不会在该群系产生常驻降水；只有强制 `clear` 会阻止命令/API 的手动设置。若希望在移动模式下让强制群系下雨，请手动生成风暴（`/weather rain`、`/weather storm ...` 或风暴相关的 API）。
- 白名单/黑名单按风暴档案的**初始阶段类型**判定（多阶段档案不能用后续阶段绕过过滤）；强制天气则按档案的**任意阶段**匹配（雷暴档案的初始阶段也是雨）。
- 手动风暴的半径：`/weather storm at|spawn|global` 与 API `setWeatherAt` 未指定半径时使用档案自身的 `radius` 区间（例如 `drizzle` 180–320）；`/weather storm area` 用「中心到区域最远角的距离 + 固定 64 格」作为半径，只生成一个圆形风暴近似覆盖整片区域。`band <minY> <maxY>` 子命令只指定竖直区间，不改变半径。

白名单/黑名单只约束自然循环，使用指令仍然可以手动更改被排除的天气。

## 内置天气预设

除了 `clear` / `rain` / `thunder`，独立内置扩展包还提供了 9 个额外天气类型和各自的风暴档案。它们与数据包内容走同一套注册表，可以用 `/weather type`、风暴档案、黑白名单和 `WeatherApi` 直接引用。

| 天气类型                      | 表现                           | 维度要求 | 自然风暴档案（权重）          |
| ------------------------- | ---------------------------- | ---- | ------------------- |
| `localizedrain:snow`      | 降雪（雪片粒子）                     | 有天光  | `snow`（14）          |
| `localizedrain:blizzard`  | 暴风雪：密集雪片 + 冷雾 + 冻结伤害 + 减速    | 有天光  | `blizzard`（8，3 阶段）  |
| `localizedrain:sandstorm` | 沙尘暴：白灰粒子 + 沙尘雾 + 伤害 + 减速     | 有天光  | `sandstorm`（8，3 阶段） |
| `localizedrain:fog`       | 浓雾：低密度云粒子 + 灰雾               | 有天光  | `fog`（12）           |
| `localizedrain:overcast`  | 阴天：云粒子 + 暗灰雾（不降水）            | 有天光  | `overcast`（18）      |
| `localizedrain:ashstorm`  | 灰烬风暴：灰烬粒子 + 深灰雾 + 伤害 + 减速    | 下界   | `ashstorm`（6，2 阶段）  |
| `localizedrain:soulmist`  | 灵魂薄雾：灵魂粒子 + 青雾 + 减速          | 下界   | `soulmist`（8）       |
| `localizedrain:voidstorm` | 虚空风暴：传送门粒子 + 紫黑雾 + 魔法伤害       | 末地   | `voidstorm`（6，2 阶段） |
| `localizedrain:voidhaze`  | 虚空霾：传送门粒子 + 暗雾 + 减速          | 末地   | `voidhaze`（8）       |

<br />

- 这些扩展预设位于独立内置包 `mod/localizedrain:builtin_datapacks/weather_presets`。未安装 Weather+ 时该包自动注册；安装 Weather+ 后该包完全不注册，由 Weather+ 提供完整升级版，核心晴雨雷与核心风暴档案不受影响。添加/移除 Weather+ 后需要重启，`/reload` 不会切换该包。
- 这些预设天气**默认不参与主世界等有天光维度的自然生成**；下界/末地的四个预设档案（灰烬风暴、灵魂薄雾、虚空风暴、虚空霾）始终参与这两个维度的自然风暴，否则关掉开关会让下界/末地完全没有自然风暴。要开启全部预设的自然刷新，将`weather.toml`（或全局默认文件 `global-weather.toml`）里的 `enableBuiltinPresets` 设为true。
- `enableBuiltinPresets` 只控制预设的自然生成，不影响注册和手动命令；`storm_profile` 的 `natural` 字段始终优先于 `storm_profile/natural` 标签（写 `false` 的档案不会被标签强行拉入自然池）。装载 Weather+ 时低配包已禁用，该开关不再作用于这些预设。
- 每个预设天气都有同名的风暴档案。开启自然生成后，移动模式的自然风暴会抽取这些档案，群系片模式的天气类型则直接参与生物群系的天气循环。
- 预设类型本身带 `skylight`：降雪/暴风雪/沙尘暴/浓雾/阴天只在有天光维度参与自然循环，灰烬风暴/灵魂薄雾/虚空风暴/虚空霾只在无天光维度参与，因此它们不会串到主世界的群系片天气里。
- 预设与数据包的关系（覆盖、移出自然池）见「自定义数据包 → 预设与数据包」。

## 自定义数据包

天气类型（`localizedrain:weather_type`）与风暴档案（`localizedrain:storm_profile`）都是数据包注册表：jar 内置内容、附属模组与外部数据包共用同一套注册表，同 id 的定义会互相覆盖。本节汇总所有与数据包相关的内容。

### 数据包的位置与启用

除了模组自带的 `data/`，也可以把数据包直接放进配置目录，或者作为普通数据包安装：

```text
config/localizedrain/datapacks/<name>/pack.mcmeta
config/localizedrain/datapacks/<name>/data/<namespace>/localizedrain/storm_profile/<id>.json
config/localizedrain/datapacks/<name>/data/<namespace>/localizedrain/weather_type/<id>.json
```

- 每个子目录就是一个数据包，**必须带 `pack.mcmeta`**；缺少或损坏会被忽略并打警告。显示名取自 `pack.mcmeta` 的 `description`。

- 目录名只允许 `a-z`、`0-9`、`_`、`.`、`-`，其他名字会被忽略并打警告。

- 启动时会扫描目录，把每个包登记到 `config/localizedrain/datapacks.toml`（新包默认启用），可以按目录名逐个关闭：

  ```toml
  enabled = true          # 关闭后忽略整个 datapacks 目录

  [packs]
  # <pack.mcmeta 里的显示名>
  "my-pack" = true
  ```

- 这些包只注册为**服务端数据包**（`SERVER_DATA`）：粒子、声音、语言文件等客户端资源必须放在资源包；自定义类型的 `display_key` 没有翻译时会显示原始键名。

- 数据包仓库只在启动时组装，新增、删除、改名或修改开关后需要重启游戏；`/reload` 只会重读已经选中的包。

- 这些包在列表里始终启用并排在内置内容之上，因此同 id 的档案/类型会覆盖内置定义；内置 `clear` 的能力位会被强制压回晴天并提示一次。

### 天气类型

天气类型是数据包注册表 `localizedrain:weather_type`，内置 `clear` / `rain` / `thunder` 三个类型
（`data/localizedrain/localizedrain/weather_type/*.json`）。任何数据包都可以在
`data/<命名空间>/localizedrain/weather_type/<id>.json` 新增类型：

```json
{
  "display": "沙尘暴",
  "display_key": "weather.mypack.sandstorm",
  "has_precipitation": false,
  "precipitation": "none",
  "allows_thunder": false,
  "particle": "minecraft:white_ash",
  "sound": "minecraft:block.sand.break",
  "particle_count": 140,
  "sound_interval_ticks": 45,
  "visuals": {
    "fog_color": "#8a7050", "fog_near": 8, "fog_far": 64,
    "particle_motion": [0.0, -0.05, 0.0], "sky_darken": 0.25,
    "sky_color": "#c9a45c", "cloud_color": "#9a7a4a",
    "sunrise_color": "#e0a060", "star_brightness": 0.0,
    "sun_visible": false, "moon_visible": false
  },
  "effects": {
    "damage": { "amount": 0.5, "interval_ticks": 60, "only_when_exposed": true, "type": "minecraft:generic" },
    "movement_speed": 0.9,
    "ignite": { "seconds": 3, "only_when_exposed": true },
    "freeze": { "ticks": 20, "only_when_exposed": true },
    "potions": [
      { "effect": "minecraft:slowness", "duration_ticks": 100, "amplifier": 0, "ambient": true, "visible": true }
    ]
  },
  "extra": { "color": "#d8c080" }
}
```

- `display` / `display_key`：显示名的纯文本或翻译键（`display` 优先；都没有时按 id 推导 `weather.<命名空间>.<路径>`）。
- `has_precipitation` / `precipitation`（`none` / `rain` / `snow`）/ `allows_thunder`：判定用的能力位。
- `particle` / `sound` / `particle_count`（默认 80，**每 tick** 的基准数量）/ `sound_interval_ticks`（默认 40，0 = 不播）：
  内置客户端视觉层会消费它们——`particle` 需是**简单粒子**（`SimpleParticleType`），按强度在玩家周围生成；
  实际数量 = `particle_count × 强度`，每 tick 每名玩家最多 160；`sound` 按间隔播放环境音。
  想让自定义类型完全自绘，也可以只写 `extra`、让资源包自己读。
- `visuals`：`fog_color`（`#rrggbb` 或 `0xrrggbb`）/ `fog_near` / `fog_far`，客户端按强度插值雾气。
  `particle_motion` 是内置视觉层生成粒子时用的初速度（三分量数组，默认 `[0.0, -0.05, 0.0]`；雨雪写
  `[0.0, -0.6, 0.0]` 能看见明显下落，沙尘写 `[0.03, 0.0, 0.0]` 横向飘）。
  `sky_darken`（0–1，默认 0）在天气激活时压低该位置的天空亮度：映射到原版 0–11 的天空变暗值
  （`1.0` 即原版满雷暴的 11），与雨/雷本身造成的变暗取较大者；片模式与移动模式都生效。
  它改的是天空亮度，因此会像雷暴一样影响刷怪与实体天光，即使该天气完全不降水。
  天空盒覆盖：`sky_color` 替换群系的天空基色（按强度插值；昼夜变暗、雨雷变灰、闪电提亮仍由原版叠加，
  同时会改变默认雾色，显式 `fog_color` 仍优先）；`cloud_color` 是云色乘数（`#ffffff` 等于原版，
  颜色越暗云越暗，只能压暗不能提亮）；`sunrise_color` 只在原版本来就有日出/日落扇形时替换其颜色
  （alpha 保留原版，不会凭空生成扇形）；`star_brightness`（0–1）是星星亮度乘数，`0` 表示看不见星星；
  `sun_visible` / `moon_visible`（默认 `true`）为 `false` 时把日月换成全透明贴图。天空覆盖只作用于主世界
  这类 `NORMAL` 天空（末地天空走独立实现，日月与星空本就未绘制），颜色与星星按强度从原版平滑过渡，
  日月开关是离散的（强度超过 0.01 即生效）。
- `effects`：`damage`（`amount` / `interval_ticks` / `only_when_exposed` / `type` 伤害类型 id，
  默认 `minecraft:generic`；`interval_ticks` 小于 10 会按 10 处理，因为原版受伤有 10 tick 无敌帧）、
  `movement_speed`（移动速度乘数）、`ignite`（`seconds` 秒着火，只延长不缩短）、
  `freeze`（每 tick 增加的细雪冻结值 `ticks`）与 `potions`（药水效果数组：
  `effect` / `duration_ticks`（默认 100）/ `amplifier`（默认 0）/ `ambient`（默认 true）/ `visible`（默认 true））。
  子项里的 `only_when_exposed`（默认 true）都要求玩家头顶能看到天空；药水在天气激活期间持续刷新，
  持续时间建议大于刷新间隔，离开天气后自然到期。
  片模式对位于该天气片内的玩家生效；移动模式对**被主导风暴覆盖**的玩家生效；
  离开范围后移速与伤害计时自动复位，模式切换时不会残留叠加。
- `effects.attributes`（可选，2.0.0-alpha）：属性修饰符数组
  `[{ "attribute": "minecraft:generic.movement_speed", "amount": 0.2, "operation": "add_multiplied_base", "only_when_exposed": true }]`。
  `operation` 为 `add_value` / `add_multiplied_base` / `add_multiplied_total`（默认 `add_value`）：可引用原版与任何已注册模组的属性，
  运行期按 id 解析，未知 id 跳过并只告警一次；同一属性、同一运算的条目按层求和，每个玩家最多 16 组修饰符，
  天气结束、离开范围、死亡或切换模式时全部移除。`only_when_exposed=true` 时要求玩家头顶能看到天空。
  `movement_speed` 仍是便捷字段，两者同时存在时更强的生效。需要 `[compat]` 的 `effectsApplyAttributes=true`（默认），
  关闭后只禁用属性部分。配合 Apothic Attributes 等属性模组时，派生属性（如攻击伤害/暴击）由对方计算，
  本模组只写入 `BuiltInRegistries.ATTRIBUTE` 里的原始属性。
- `requires_skylight`（默认 `false`）：为 `true` 时该类型只出现在**有天光**的维度（自然池与强制天气都会跳过它）；
  无天光维度里仍可用 `/weather type` 显式设置。旧字段，等价于 `skylight: required`。
- `skylight`（默认 `any`）：`any` / `required`（只有天光维度）/ `forbidden`（只有无天光维度）。
  自然池、白名单和强制天气都会按当前维度过滤；本模组的预设用它把下界/末地天气挡在主世界之外。
- `extra`：自由 JSON，模组原样保留、不解释。
- `lifecycle`（可选）：`{ "ramp_in_ticks": 200, "ramp_out_ticks": 200 }`，让片模式的雨/雷强度在进入/离开时
  线性升降，而不是瞬间切换；`0` 表示该侧不渐变。只有 `[patches]` 的 `patchLifecycle=true` 时生效，
  手动设置、强制天气与锁定晴天的片不受影响。
- `next`（可选）：`[ { "type": "minecraft:rain", "weight": 3, "min_biome_temperature": 0.4 }, ... ]`，
  片模式本次天气结束时按权重从未列出的目标里抽一个作为下一种天气；支持 `min/max_biome_temperature`、
  `biome_tag` 条件。没有满足条件的目标时回退到该片的候选池随机；只有 `patchWeatherEvolution=true` 时生效，
  且目标必须仍在候选池内（白名单/黑名单/强制天气已先行过滤）。

自定义类型可以直接用于风暴阶段、黑白名单、强制天气、`/weather type` 与 `WeatherApi`。
若还想让它参与**自然循环**，需要把它加入自然池标签 `data/<命名空间>/tags/localizedrain/weather_type/natural.json`
（标签存在时以内为准），或写进对应群系的白名单。

### 风暴档案

风暴档案放在 `data/<数据包命名空间>/localizedrain/storm_profile/<id>.json`：NeoForge 把注册表的命名空间
也作为目录前缀，所以路径里会出现两次 `localizedrain`；文件的命名空间就是档案 id 的命名空间。

- 这些参数分别描述生成权重、是否自然生成、移动速度倍率、半径、竖直模式（`SURFACE` / `BAND` / `COLUMN`），
  以及阶段类型、雨/雷强度、阶段时长与环境演化参数。
- 风暴档案可以写 `"biomes": []` 限定生成群系，条目支持生物群系标签（`#minecraft:is_taiga`）、单个群系
  （`minecraft:desert`）与整个命名空间（`minecraft:*`）。不写时走默认筛选：会下雨的 `SURFACE` 档案只落在
  会降水的群系；显式写列表则完全以列表为准（例如沙尘暴可以写沙漠）。洞穴/整列档案不受默认降水限制。
- 风暴档案也可以打标签：`data/<命名空间>/tags/localizedrain/storm_profile/<tag>.json`。模组内置
  `#localizedrain:natural` 标签作为自然生成池：标签存在（且非空）时以标签成员为准，不存在时回退到档案里的
  `"natural": true`。API 可读取：`StormProfiles.idsInTag(level, tag)`、`StormProfiles.tagIds(level, profileId)`。

### 维度与天空光照

**维度兼容**：风暴档案的 `dimensions` 字段支持维度 id、维度类型 id、`命名空间:*` 和 `#维度类型标签`
（如 `"dimensions": ["#minecraft:is_overworld", "mypack:aether"]`），空列表表示不限维度。
因此**任何注册表维度（包括数据包新增的维度）只要维度类型有天光、且档案/群系规则允许，就会正常生成天气与风暴；
本模组不再硬编码主世界**。
写错的 `biomes` / `dimensions` 选择器会被丢弃并打警告；如果**整个列表都写错，档案会保留「有限制」标记，
因此它在任何群系/维度都不会生成（而不是因为列表变空变成不限维度）**。

档案还可以写 `skylight`：`any`（默认）/ `required`（只在天光维度）/ `forbidden`（只在无天光维度）。
内置的暴风雪、沙尘暴、雾、阴天为 `required`，灰烬风暴、灵魂薄雾、虚空风暴、虚空霾为 `forbidden`；
其中灰烬风暴与灵魂薄雾用 `"dimensions": ["minecraft:the_nether"]` 限定下界，虚空风暴与虚空霾用 `"dimensions": ["minecraft:the_end"]` 限定末地。

无天空光照或有天花板的维度（下界、自定义洞穴维度）：片模式天气照常推进（降水判定不再要求「见天」），
移动模式不生成 `SURFACE` 风暴，但允许 **BAND 风暴**——在玩家高度附近取一条竖直带，档案需满足 `skylight`/`requires_skylight` 约束。

### 预设与数据包

自定义数据包可以覆盖预设中同 id 的类型/档案，也可以把预设从 `#localizedrain:natural` 标签里移除，不再参与自然生成。扩展预设只在未安装 Weather+ 时注册；安装 Weather+ 后应改用其 `weatherplus:*` 类型与档案。

### 无法用数据包表达的部分

配置界面（来自代码里的 `ModConfigSpec`/自管理 TOML，数据包内容不会自动出现在界面，
改完用 `/reload`）、schema 外的任意玩法逻辑、自定义粒子的复杂行为、天空盒/shader 等自定义渲染——
这些仍需附属模组或客户端代码。

## 天气类型与扩展 API

`/reload` 后会重新解析天气类型与风暴档案，并按新定义刷新已有片状态与风暴的能力位（类型 ID、计时器、手动标记与空间归属都不变）；非法或已删除的类型会清空能力位并记录一次警告，手动设置的类型被删除后会立即回到晴天。

天气类型与风暴档案的 JSON 定义、标签、维度与天光规则见「自定义数据包」。

判定与查询入口：

- `api.WeatherApi`：`typeAt` / `patchAt` / `isRainingAt` / `isPrecipitatingAt` / `isThunderingAt` /
  `thunderLevelAt` / `skyDarkenAt` / `patches` / `setWeather` / `setWeatherAt`。
- `api.WeatherTypes`：`resolve(level, id)` / `ids(level)` / `naturalIds(level)` / `tagIds(level, tag)`；
  客户端同样可用（数据包注册表登录时同步）。
- `api.WeatherType`：`displayName()` / `particle()` / `sound()` / `extra()` 等读取接口。
- 客户端读取当前状态：`network.ClientWeatherTargets.typeKey()`（HUD 目标类型）、
  `client.ClientStormCache.storms()`（当前风暴快照，含类型、位置、半径与强度）。
- 风暴事件：`StormSpawnEvent`（可取消）、`StormDyingEvent`（可取消，延迟淡出并给 20 tick 免疫期）、`StormStageChangeEvent`、`StormRemoveEvent`；2.0 追加 `StormMergeEvent` / `StormSplitEvent`（可取消）；
  生成入口 `StormManager` / `StormSpawnSpec`。
- 风暴合并/分裂（移动模式）：每 5 秒检查一次；类型相同（或 `typesCompatible` 兼容）且中心距离小于 `(r1+r2)×0.6`
  的风暴合并，保留更老的一个，位置/温度/湿度/强度按面积加权，半径按平方和开方；半径超过档案上限 `1.4` 倍的风暴
  按 `25%` 概率分出子风暴（父风暴半径 ×0.62、强度 ×0.9，子风暴落在垂直风向一侧）。两者均可被对应事件取消，
  并受 `[storm.lifecycle]` 的 `merge*` / `split*` 键控制。分裂后父子风暴会获得 `mergeImmunityTicks`
  （默认 1200 tick = 60 秒）的合并免疫，避免刚分开就被下一次检查合回去。

## 安装

1. 安装 Minecraft 1.21.1 对应的 NeoForge（21.1.265 或更高）。
2. 服务器和客户端都需要安装本模组。

> 客户端与服务端必须使用同一版本（网络协议 `"10"`）。版本不一致时连接不会断开，
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

> 命令语义提醒：
> - `/weather clear|rain|thunder` 与 `/weather type` 默认**只作用于命令源所在的那一片群系**（可用 `at` / `area` / `global` 改范围），因此同名子命令的行为与原版「整维度天气」不同；需要整维度请加 `global`。
> - `/weather storm ...` 只在 `weatherMode=mobile` 下有效；其它模式会直接报错，而不是静默生成一个不会渲染的风暴。
> - `/weather type <id>` 遇到未注册的 id 会失败并提示，不会再把该片静默改成晴天。

## 配置

配置文件分为全局与存档两级，全部放在 `config/localizedrain/` 或 `<存档>/serverconfig/localizedrain/` 下，并按节（section）索引：

| 文件                    | 位置                                 | 主菜单可编辑    | 内容                                                                  |
| --------------------- | ---------------------------------- | --------- | ------------------------------------------------------------------- |
| `common.toml`         | `config/localizedrain/`（全局）        | 是         | `[logging]` 调试开关，所有存档共用                                             |
| `datapacks.toml`      | `config/localizedrain/`（全局）        | 否（手动编辑文件） | 配置目录数据包的总开关与逐包开关（说明见「自定义数据包」）；启动时自动登记新包                             |
| `global-weather.toml` | `config/localizedrain/`（全局）        | 是         | **新存档**的天气默认值（节结构与 `weather.toml` 完全相同）                              |
| `weather.toml`        | `<存档>/serverconfig/localizedrain/` | 否（进游戏后编辑） | 当前存档的天气设置；创建存档时从 `global-weather.toml` 复制                            |
| `client.toml`         | `config/localizedrain/`（全局）        | 是         | `[visual]` 客户端渐变、室内外视觉/声音、Xaero 开关（每个客户端各自生效）                           |

`weather.toml` / `global-weather.toml` 的节结构（也是文件里的书写顺序）：

```text
[general]          通用：天气模式、预设开关、隔离群系行为
[patches]          片模式天气循环与存档清理
[storm.spawn]      风暴生成
[storm.wind]       风场
[storm.lifecycle]  风暴生命周期
[storm.terrain]    风暴与地形/群系交互
[storm.sync]       同步与强度
[storm.stacking]   天气叠加
[compat]           原版查询兼容
[filters]          天气过滤器（白名单 / 黑名单 / 强制天气）
```

`[storm]` 只是分组的索引节，本身不挂键。旧版本写在文件顶层的平铺键会在启动时自动搬进对应节（原文件备份为 `*.flat.bak`）；
旧的 `filters.toml` / `global-filters.toml` 会并入 `[filters]` 节后改名为 `*.merged.bak`。旧版 `config/localizedrain-common.toml`
仍会按新结构拆分迁移（旧文件保留作备份）。已有存档中仍为默认 `PATCHES` 的 `weatherMode` 会跟随旧文件迁移一次
（存档目录留下 `.weatherMode-migrated` 标记，之后手动改回 `PATCHES` 不会被再次覆盖）。

以下为主要配置项（默认值）。

### [general] 通用

| 键                          | 默认       | 说明                                                                                     |
| -------------------------- | -------- | -------------------------------------------------------------------------------------- |
| `weatherMode`              | `mobile` | `patches` / `mobile` / `vanilla`；新世界默认 `mobile`，运行期热切换（约 1 秒生效，也可用 `/weather mode` 命令） |
| `enableBuiltinPresets`     | `false`  | 是否让模组自带的 9 个新增天气预设参与自然天气/自然风暴；关闭时主世界等有天光维度不生成预设，下界/末地预设仍参与自然风暴；档案的 `natural` 字段优先于标签 |
| `isolatedBiomeWeather`     | `clear`  | 隔离群系里的行为：`clear` / `patch` / `manual`                                                  |

### [patches] 片模式

| 键                                | 默认       | 说明                                                        |
| -------------------------------- | -------- | --------------------------------------------------------- |
| `minClearTicks` / `maxClearTicks` | `12000` / `168000` | 晴天时长区间（tick）                                     |
| `minRainTicks` / `maxRainTicks`   | `12000` / `24000`  | 降雨时长区间（tick）                                     |
| `minThunderDelayTicks` / `maxThunderDelayTicks` | `12000` / `180000` | 距上次雷暴的等待区间（tick）                |
| `minThunderTicks` / `maxThunderTicks` | `3600` / `15600` | 雷暴时长区间（tick）                                    |
| `weatherSimulationIntervalTicks` | `20`     | 已加载群系片的推进间隔，越大越省性能                                        |
| `scan3dSubstates`                | `true`   | 是否扫描洞穴/浮岛等 3D 子状态                                         |
| `stateRetentionDays`             | `7`      | 片状态保留天数，`0` 关闭时间清理                                        |
| `maxStoredStates`                | `4096`   | 存档中片状态上限                                                  |
| `maxParentEntries`               | `16384`  | 并查集父表硬上限                                                  |
| `maxColumnScansPerTick`          | `4`      | 每 tick 最多新扫描的列数                                           |
| `patchWeatherEvolution`          | `true`   | 是否让天气类型按自身 `next` 列表演化（关闭后只在候选池随机）                     |
| `patchLifecycle`                 | `true`   | 是否让天气强度的 `lifecycle` 淡入淡出生效                            |

### [storm.spawn] 风暴生成

| 键                                       | 默认            | 说明                                                                          |
| --------------------------------------- | ------------- | --------------------------------------------------------------------------- |
| `naturalCaveStorms`                     | `false`       | 是否自然生成洞穴风暴                                                                  |
| `caveStormRadius`                       | `64`          | 自然洞穴风暴半径                                                                    |
| `maxStormsPerDimension`                 | `8`           | 自然风暴上限（手动风暴不占名额）                                                            |
| `maxStormsPerPlayer`                    | `2`           | 每位玩家附近自然风暴上限                                                                |
| `maxManualStormsPerDimension`           | `16`          | 手动风暴上限（命令/API）                                                              |
| `spawnIntervalTicks`                    | `200`         | 自然生成检查间隔                                                                    |
| `spawnMinDistance` / `spawnMaxDistance` | `300` / `700` | 生成点与玩家的距离范围（超出模拟距离时自动收敛）                                                    |
| `naturalStormSpawnChance`               | `0.035`       | 每次检查真正生成自然风暴的概率（约 4.8 分钟一个；0 = 关闭）                                          |
| `stormSpawnPlacement`                   | `near_player` | 自然风暴生成位置：`near_player`（玩家附近，受每玩家上限约束）/ `anywhere_loaded`（任意已加载区块，不受每玩家上限约束） |

### [storm.wind] 风场

| 键                                       | 默认              | 说明                                     |
| --------------------------------------- | --------------- | -------------------------------------- |
| `windChangeIntervalTicks`               | `1200`          | 风场调整间隔（tick）                           |
| `windInitialSpeed`                      | `10.0`          | 初始风速（格/秒），也是偏离过大时的回归目标                 |
| `windSpeedVariationMin` / `windSpeedVariationMax` | `1.0` / `3.0` | 每次风速浮动的幅度区间（格/秒），区间内随机取值、正负号随机 |
| `windSpeedDeviationLimit`               | `5.0`           | 当前风速与初始值相差超过该值时强制回归（格/秒）               |
| `windReturnRate`                        | `0.25`          | 强制回归时每次靠拢初始值的比例（0–1）                   |
| `windDirectionVariationMin` / `windDirectionVariationMax` | `10.0` / `30.0` | 每次风向浮动的幅度区间（度），区间内随机取值、正负号随机 |

### [storm.lifecycle] 生命周期 / [storm.sync] 同步与强度

| 键                                       | 默认            | 说明                                                    |
| --------------------------------------- | ------------- | ----------------------------------------------------- |
| `despawnDistance`                       | `1000`        | 自然风暴远离玩家的消亡距离                                         |
| `despawnGraceTicks`                     | `600`         | 远离多久后开始淡出                                             |
| `stormRadiusBase`                       | `0.0`         | 新生成风暴的基准半径（方块）；`0` = 使用档案自带的 `radius` 区间             |
| `stormRadiusVariance`                   | `0.3`         | 基准半径的浮动率（`0.3` = ±30%），仅在 `stormRadiusBase > 0` 时生效   |
| `mergeEnabled`                          | `true`        | 类型相同（或兼容）的相邻风暴是否自动合并                              |
| `mergeRadiusFactor`                     | `0.6`         | 中心距离小于（半径之和 × 该值）才合并；`0.6` = 只有重叠的风暴合并           |
| `mergeCooldownTicks`                    | `200`         | 同一维度两次合并的最小间隔                                         |
| `mergeManual`                           | `false`       | 手动生成的风暴是否参与合并                                         |
| `mergeImmunityTicks`                    | `1200`        | 分裂后父子风暴互相不参与合并的时长（tick），`0` = 关闭                     |
| `splitEnabled`                          | `true`        | 超大风暴是否分裂出子风暴                                          |
| `splitRadiusFactor`                     | `1.4`         | 半径超过「档案最大半径 × 该值」才可能分裂                               |
| `splitChance`                           | `0.25`        | 每次检查时分裂的概率                                             |
| `splitCooldownTicks`                    | `400`         | 同一维度两次分裂的最小间隔                                         |
| `stormSyncIntervalTicks`                | `40`          | 风暴快照校正间隔                                              |
| `falloffRatio`                          | `0.3`         | 风暴边缘的衰减宽度比例                                           |
| `lightningMultiplier`                   | `1.0`         | 落雷概率倍率，`0` 关闭                                         |

### [storm.terrain] 地形与群系交互

| 键 | 默认 | 说明 |
| ------------------------------ | ------------- | --------------------------------------------------------------------------- |
| `terrainInfluence`             | `true`        | 总开关；关闭后风暴不再受地表高度与群系降水影响，全部系数保持 `1.0`                                        |
| `altitudeRainFalloff`          | `0.5`         | 地表从海平面（63）到「63 + `altitudeRange`」时雨量的线性衰减比例；`0.5` = 最高处雨量减半               |
| `highlandThunderBoost`         | `0.25`        | 同一高度区间内雷量的相对增益（`0.25` = 最高处 +25%，最终钳制到 `1.0`）                            |
| `highlandSpeedBoost`           | `0.2`         | 同一高度区间内移动速度的相对增益（`0.2` = 最高处快 20%）                                     |
| `dryBiomeRainFactor`           | `0.35`        | 沙漠、恶地等无降水群系的雨量乘数（0–1）                                                  |
| `dryBiomeThunderFactor`        | `0.5`         | 无降水群系的雷量乘数（0–1）                                                         |
| `altitudeRange`                | `128`         | 海拔系数的参考高度（方块，16–512）                                                        |
| `sampleIntervalTicks`          | `20`          | 每个风暴重新采样地表高度/群系的间隔（tick）                                                  |

### [storm.stacking] 天气叠加（移动模式）

| 键                          | 默认   | 说明                                                          |
| -------------------------- | ---- | ----------------------------------------------------------- |
| `compatibleWeather`        | `[]` | 允许叠加的天气（成对声明：`<天气>=<id或#标签>,...`；空 = 全部天气互相兼容，测试期默认）        |
| `blockedWeather`           | `[]` | 定向互斥：`<先出现的天气>=<不再叠加的天气,...>`（单向，反向仍可叠加；空 = 不额外限制）                    |
| `maxStackedWeatherTypes`   | `3`  | 同一点最多叠加的天气层数（1–8）                                           |

### [compat] 原版查询兼容

| 键                             | 默认      | 说明                                                                                   |
| ----------------------------- | ------- | ------------------------------------------------------------------------------------ |
| `compatVanillaWeatherQueries` | `false` | 让不带坐标的原版查询（`isRaining()` / `getRainLevel()` 等）返回维度级的局部最大强度；关闭时保持原版值，完全零影响 |
| `effectsApplyAttributes`      | `true`  | 应用天气类型 `effects.attributes` 里声明的任意属性修饰符（含其它模组的属性）；关闭只禁用属性部分，其它效果不受影响 |

### [filters] 天气过滤器

`filters.toml` / `global-filters.toml` 已取消，三张名单并入天气配置的 `[filters]` 节（旧文件启动时自动合并）。

| 键                          | 默认   | 说明                                                     |
| -------------------------- | ---- | ------------------------------------------------------ |
| `weatherBiomeWhitelist`    | `[]` | 白名单：`<群系选择器>=<天气>`；该群系只会出现列出的天气                       |
| `weatherBiomeBlacklist`    | `[]` | 黑名单：`<群系选择器>=<天气>`；从该群系剔除这些天气                          |
| `weatherBiomeOverrides`    | `[]` | 强制天气：`<群系选择器>=<天气>`；该群系永远只有这一种（多条按首位优先，晴天则锁定晴天）        |

### [logging] 日志（`common.toml`）

| 键                          | 默认      | 说明                          |
| -------------------------- | ------- | --------------------------- |
| `debugLogging`             | `false` | 输出片扫描/合并的调试日志               |
| `logWeatherChanges`        | `false` | 群系片天气变化时输出位置与类型             |
| `logStormSpawns`           | `false` | 风暴生成时输出详细参数（调试用，默认关闭）       |
| `logStormSpawnsToChat`     | `false` | 风暴生成时在聊天栏报告中心坐标与类型（调试用，默认关闭） |

### [visual] 客户端视觉（`client.toml`）

| 键 | 默认 | 说明 |
| ---------------------- | ---- | ------------------------------------------------------------ |
| `weatherTransitionTicks` | `40` | 层叠天气切换时雾/粒子/声音/天空的渐变时长（tick）；`0` 关闭渐变，立即切换 |
| `indoorVisualFactor`   | `0.0` | 站在室内（头顶看不到天空）时保留的视觉强度比例：雾、粒子数量、天空覆盖都乘它 |
| `indoorSoundFactor`    | `0.35` | 站在室内时保留的环境音音量比例；`exposure < 0.5` 时音高再乘 `0.6`，模拟闷在屋里的雨声 |
| `xaeroWeatherOverlay`  | `true` | 装了 Xaero 时：小地图天气信息行 + 世界地图上的天气叠加（实验性，失败自动禁用） |

室内检测每帧最多做 6 次 `canSeeSky`（眼睛、上方、四个水平方向各 1 格），得到 1.0 / 0.5 / 0 三档
曝光值：头顶见天为室外，只有侧面窗口为半室外，完全封闭为室内。视觉与声音分开取系数，因此可以
「室外大雨、室内几乎不起雾但仍能听到闷雨声」。粒子数量按曝光总量缩放，不做逐粒子的遮挡判定
（逐粒子 `canSeeSky` 的代价远高于收益）；渐变只作用于层叠/切换，同一类型内部强度变化仍按服务端强度直接插值。

### 天气叠加（移动模式）

移动风暴模式下，多个风暴覆盖同一点时，默认**所有天气类型互相兼容**（测试期默认），按强度叠加，上限
`maxStackedWeatherTypes`（默认 3）：

- 也可以只在 `compatibleWeather` 里声明允许的组合：声明后只有列出的配对可以叠加。
  兼容是成对的：`A=...B` 或 `B=...A` 任一方向成立即可叠加；右侧支持 `#天气类型标签`。
- 视觉：每层按各自强度喷粒子（总量封顶）、雾色按强度加权混合、天空/云/日月取最强层，雾距离取各层最小值
  （雾最浓的那层，近/远平面来自同一层）；同时最多两层播放环境音，避免叠满时声音糊成一片。
- 效果：移动速度取各层最慢值；点燃/冰冻取最长；药水按效果取最强；周期伤害取最大 amount（共用同一计时器）。
- Xaero 小地图的天气行会把各层用 ` / ` 连成一行显示（最多 `maxStackedWeatherTypes` 层）。
- 片模式（`weatherMode=patch`）不叠加，仍按当前位置的单一群系天气。

## 天气地图（云图）

按 `K`（可在「按键绑定 → 局部有雨」里改）打开天气地图：

- 中间是 512×512 方块范围的地图：每个 16×16 群系片按主导天气类型着色，透明度随雨量增加；
  风暴画成圆（半径按真实风暴半径缩放），颜色取该天气类型的 `sky_color`（没写则用默认蓝）。
- 右侧是预报与风暴列表：预报来自服务端 `WeatherManager.forecastAt`（片模式看当前天气还剩多久、
  下一类型；移动模式看风暴阶段切换），最多 8 步；风暴列表显示类型、阶段、半径、雨/雷强度。
- 鼠标悬停在片格上会显示坐标、雨/雷百分比、剩余时间，以及是否被配置强制/群系规则锁定。
- 左下角显示当前风矢量。地图数据由服务端按需推送（同一玩家 10 tick 最多一次），界面关闭后不再请求。
- 地图只发送附近的、确实存在天气的片；移动模式没有「片」，因此只显示风暴。
- 装了 Xaero 世界地图时，打开 Xaero 地图会尝试把同样的片/风暴叠加到地图上：实现为反射读取
  Xaero 的相机与缩放字段，属于**实验性**功能，版本不匹配时自动禁用（日志里有一行 warn），
  不影响 Xaero 本身；可用 `client.toml` 的 `xaeroWeatherOverlay` 关闭。
- 协议版本升到 `"10"`：客户端与服务端必须同时更新，否则会在登录协商时被拒绝（`optional()` 只处理对端
  完全没有该通道的情况）。

## 兼容性

- **读取天气的模组**：按位置的 `Level#isRainingAt(pos)` 已被本模组接管，第三方调用会拿到局部天气。
  维度级、无坐标的查询（`Level#isRaining()`、`getRainLevel()`、`isThundering()`、`getThunderLevel()`）
  默认仍返回原版值（本模组不写回 `rainLevel` / `thunderLevel`）；在 `weather.toml` 打开
  `compatVanillaWeatherQueries` 后，服务端会按位置返回局部强度：
  - 已知坐标的帧：方块随机刻、方块/流体计划刻、方块实体 tick、实体 tick、降水刻、方块邻居更新、区块 tick（区块中心）。
  - 其余调用回退到该维度任意位置的最大值（维度级近似）。
  - 数值与原版口径对齐：雨量 < 0.2 吸附为 0（与原版 `isRaining()` 阈值一致），雷量不超过雨量、< 0.9 吸附为 0。
  - 只检查这些接口的模组（容器集水、作物生长、刷怪判定等）因此也能感知局部天气。
  - 关闭开关时这些帧只多一次布尔判断，不扫描、不分配。
  - 非原版模式下维度级 `rainLevel` / `oRainLevel` / `thunderLevel` / `oThunderLevel` 由本模组独占
    （每个 tick 清零）；其他模组用 `setRainLevel` / `setThunderLevel` 写入的值不会保留。
  - 异步线程（并行区块生成、第三方 worker）调用无坐标查询时不启用代理，返回原版值：
    位置缓存不是线程安全的，只在服务端主线程解析。
  - 代理只在**服务端**生效：客户端原版查询已由逐玩家数据驱动（片模式的雨量包、移动模式的
    `ClientStormWeather`），不再叠加客户端代理。`Level#updateSkyBrightness()` 期间代理被临时屏蔽，
    全局天空亮度保持原版值，逐位置的变暗仍由本模组自己的天光/刷怪路径处理。
  - 需要按位置判断时用 `WeatherApi.isRainingAt` / `WeatherApi.rainLevelAt`；自定义坐标流程可用
    `WeatherApi.withPosition(pos, action)`（仅限服务端主线程），`/weather compat` 可查看当前实际取值。
  - 热重载限制：`/reload` 后服务端立刻使用新定义；远程客户端只有在数据包注册表同步时才会更新同一 id 的视觉定义。
    若客户端保持在线时修改同一 id 的粒子/雾/声音后 `/reload` 没有变化，需要重连（或单人游戏重新加载资源）才能生效。
    服务端检测到定义变化时会向在线玩家发送提示；类型被热移除的风暴会在日志里按「维度 + 类型」告警一次，
    不再占用生成上限，类型恢复后自动恢复显示。
  - 未覆盖的坐标上下文（方块放置时的 `onPlace`/`updateShape`、实体生成与移除、结构生成等）仍回落维度最大值；
    方块邻居更新（`neighborChanged` 及其触发的红石/比较器更新）已按坐标覆盖：整条更新链只压最外层位置，
    深层嵌套不会吃光帧栈，坐标偏差不超过几个方块。
   原版的积雪与炼药锅集水已通过 `tickPrecipitation` 内的重定向按区域恢复。
- **区块 tick 也按位置**：`ServerLevel#tickChunk` 开头用区块中心压帧，`isRaining()` / `isThundering()`
  的区块级门控与落雷抽签按该区块所在位置解析；落雷位置本身仍走 `isRainingAt`，只落在局部雨区。
- **Xaero's Minimap**：装了本模组与 Xaero 小地图时，天气信息行会自动替换为局地天气，
  切换片/风暴时立即更新；桥接为纯反射实现，不打包 Xaero 的类，未装 Xaero 时自动跳过。
- **附属模组 / 移动天气**：移动模式下 `WeatherApi.setWeather(level, patches, ...)` 会把每个片中心映射为一个手动风暴
  （`CLEAR_ID` 则映射为按列清除）；单次调用最多处理 `maxManualStormsPerDimension` 个片，避免对全维度片逐个扫描，
  超过部分会静默截断。需要精确范围时请使用坐标入口或 `spawnStorm`。

## 已知限制

- 客户端雨雪粒子按玩家所在片/风暴的总体雨量渲染，不做逐方块局部化；站在雨区边缘时，
  相邻晴天区域也可能看到雨丝。
- 群系片按水平 16 方块、垂直 4 方块量化；未加载区域冻结，查询时按晴天处理。
- 自然风暴：有天空光照的维度允许生成 `SURFACE` 风暴；无天空光照/带天花板的维度只生成 `BAND` 风暴，
  可用档案由 `skylight` 与阶段类型的 `requires_skylight` 决定。
- 无天光维度的 `BAND` / `COLUMN` 风暴可以落雷；有天光维度仍要求落点正上方有开口（`canSeeSky`）。
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
- `logStormSpawnsToChat`（默认开启）在风暴生成时向该维度所有玩家发送一条聊天消息，包含中心坐标 (x, z)、Y 区间、半径与天气类型；嫌打扰时关闭即可。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

产物：`build/libs/localizedrain-<版本号>.jar`。

## 许可

MIT License，详见仓库根目录的 `LICENSE`。

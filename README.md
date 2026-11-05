# Localized rain

把原版 Minecraft 的「整个维度一起变天」改成**按连通的生物群系轮廓独立判定**的天气模组。

同一个维度里，不同群系片可以各自晴天、下雨或雷暴；玩家跨片时天空、雨声、雨雪粒子约有 5 秒的渐变，
而 Xaero's Minimap 的天气信息行会立即切换。

- Minecraft **1.21.1**
- NeoForge **21.1.265+**
- Mod ID `localizedrain`，包名 `com.spectrum255.localizedrain`

## 功能

- 天气单位是「群系片」：从玩家所在位置出发，用 16 方块网格采样生物群系并洪水填充，得到同一群系的连通区域。
  同类型但不相连的群系（例如两块分隔的森林）各自有各自的天气；超大群系（海洋等）退化为 1024 方块对齐的方格。
- 服务端只为玩家附近的群系片推进天气，远处片子停在上次 tick 的状态。
- 原版的雨/雷量、天空亮度、亡灵燃烧、雷暴刷怪都按「实体所在片」局部化。
- 雷暴片会按原版密度显式生成闪电（1/100000 每区块每 tick 的等价概率），不再依赖原版只对全局雷暴生效的抽签。
- 客户端渲染与雨声沿用服务端按玩家做的 0.01/tick 平滑，跨片约 5 秒渐变。

## 命令

```
/weather clear|rain|thunder [duration]
/weather clear|rain|thunder at <pos>
/weather clear|rain|thunder area <from> <to>
/weather clear|rain|thunder global
```

需要权限等级 2。`at` / 无参数作用于该点所在的群系片；`area` 是矩形范围内的采样（上限 512 个采样点 / 64 片）；
`global` 作用于当前维度里已经记录过的所有片。

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

- 客户端雨雪粒子按玩家所在片的雨量渲染，不做逐方块局部化（这是有意设计）。
- 群系片采样高度固定 y=128，极高山区可能采到洞穴群系。
- 超大群系退化为方格而不是轮廓。
- 没有访问过的群系片一律按晴天处理。

## 文档

- `docs/idea.md`：设计目标
- `docs/localizedrain-1.0.1-changes.md`：按群系片天气、thunderLevelAt API、显式落雷
- `docs/localizedrain-1.1.0-xaero-compat.md`：Xaero's Minimap 天气行兼容

## 许可

All Rights Reserved（见 `gradle.properties` 的 `mod_license`）。
本仓库不包含 Xaero's Minimap / XaeroLib 的 jar，也不包含任何第三方模组的二进制文件。

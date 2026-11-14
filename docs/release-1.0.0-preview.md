# Localized Rain 1.0.0-preview

- 发布日期：2026-11-14
- 目标平台：Minecraft 1.21.1 / NeoForge 21.1.265+
- 许可：MIT
- 定位：0.4.x 开发线冻结后对外发布的第一个预览版（版本号重定为 `1.0.0-preview`，mod id 与包名不变）

## 默认过滤器清空

`Config.java` 中四组过滤器默认值全部为空列表，新建实例的配置即为：

```toml
weatherBiomeWhitelist = []
weatherBiomeBlacklist = []
weatherBiomeOverrides = []
weatherBiomeCandidates = []
```

因此所有群系默认都参与自然天气循环，不再有内置的白名单范围。

下界、末地「默认没有原版天气」不再依赖强制天气，而是由代码判定：这两个维度的原版群系
`Biome#hasPrecipitation()` 为 false，`WeatherManager.classify` 会把对应的片归为 `pinnedClear`，
不参与循环、不推进计时器、不计入维度雨量、不落雷、不积雪。只有以下方式能在那两个维度出天气：

- 附属模组通过 `WeatherApi` 手动设置（按计时结束回晴）；
- 玩家执行 `/weather`（同理）；
- 服务端重新配置 `weatherBiomeCandidates` 或 `weatherBiomeOverrides`。

## 手动天气与名单的关系

- `/weather clear|rain|thunder|type <id>` 与 `WeatherApi.setWeather` 在计时内**临时覆盖**白名单/黑名单：
  被名单排除的群系也能被手动设置，计时结束后恢复按名单判定。
- 被 `weatherBiomeOverrides` 强制的片仍然会被跳过（强制天气优先于手动设置），命令只会提示跳过数量。
- 手动状态使用独立计时器（新增 `manual` / `manual_time` 字段），到期后自动回到晴天，
  不参与随机翻转；随后由名单/候选规则决定是否重新进入自然循环。

## 存档

`WeatherData.DATA_VERSION = 12`。旧存档缺少 `manual` 字段时按「非手动」读取；
0.4.5 起就不再接受的旧版 `p:` / `g:` / `|biome` key 仍然在载入时丢弃。

## 兼容性

- mod id `localizedrain`、包名 `com.spectrum255.localizedrain`、`WeatherApi`、`/weather` 语法均未变。
- 附属模组（如 Meteoros Protocol）通过 jarJar 内置时无需改代码；只需把 `libs/localizedrain-*.jar` 换成本版本。
- 未安装 Xaero's Minimap 时不会加载任何 Xaero 类；安装后 HUD 天气行立即切换，渲染仍保持 5 秒平滑。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

产物 `build/libs/localizedrain-1.0.0-preview.jar`（105,590 字节，
SHA256 `61CC049697B6C0BA6FFCCD07CB75AC14C73056ACFE9987D6579372CCE0292E3D`），
jar 内 `neoforge.mods.toml`：`version="1.0.0-preview"`、`displayName="Localized Rain"`、`license="MIT"`。

## 已知限制

- 客户端雨雪粒子、雨声、雾、天空亮度仍按玩家所在片的雨量做 5 秒平滑，不逐方块局部化（有意为之）。
- 水平 16 方块、垂直 4 方块量化；未加载区域的天气冻结、状态保留。
- 候选/自定义天气类型只有服务端语义，粒子和音效需要附属模组自行渲染。

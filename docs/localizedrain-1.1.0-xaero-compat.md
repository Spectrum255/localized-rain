# Localized rain 1.1.0 — Xaero's Minimap 天气行兼容

## 问题

Xaero's Minimap 自带的 `weather` 信息行每帧读取 `Minecraft.getInstance().level` 的雨/雷量
（`Level.isRaining()` = `getRainLevel(1.0F) > 0.2`，`Level.isThundering()` = `getThunderLevel(1.0F) > 0.9`）。
本模组为了保留跨群系片时约 5 秒的天空/雨声渐变，服务端按玩家把雨量/雷量以 0.01/tick 平滑后同步给客户端，
因此 Xaero 读到的永远是平滑值：玩家跨片后要等 1～5 秒 HUD 才切换，且与天空、雨声脱节。

## 方案

保留客户端渲染用的平滑值不变，另开一条只给 HUD 用的「目标天气」通道：

1. 服务端在 `WeatherManager.send` 里检测目标布尔（`state.isRaining()` / `state.isThundering()`）变化，
   变化时发送自定义 payload `localizedrain:weather_state`（`WeatherStatePayload`，两个布尔）。
2. 客户端收到后写入 `ClientWeatherTargets`（volatile 静态字段），断线或跨维度时清空。
3. 客户端在 Xaero 存在时，用 `InfoDisplay.Builder` 以**同 id `weather`** 重建内置天气行，
   编译器读 `ClientWeatherTargets` 而不是 `ClientLevel` 的雨量，于是 HUD 立即切换。
   替换后调用 `InfoDisplay.setManager(manager)`（`InfoDisplayManager.add` 替换已有 id 时不会设置 manager）。
4. Xaero 重建内置行时安装逻辑会自动重装（每 20 tick 检查一次 `manager.get("weather")` 是否为我们的实例）。

渲染路径（雨粒子、雨声、天空亮度）完全没动，仍是 1.0.1 的 5 秒渐变。

## 改动文件

- 新增 `src/main/java/com/spectrum255/localizedrain/network/WeatherStatePayload.java`（payload record + 编解码）
- 新增 `src/main/java/com/spectrum255/localizedrain/network/ClientWeatherTargets.java`（客户端目标状态，双端安全）
- 新增 `src/main/java/com/spectrum255/localizedrain/network/LocalizedrainNetwork.java`（`optional()` playToClient 注册）
- 新增 `src/main/java/com/spectrum255/localizedrain/client/ClientWeatherHooks.java`（客户端 tick 钩子 + 反射安装）
- 新增 `src/main/java/com/spectrum255/localizedrain/client/compat/XaeroWeatherCompat.java`（Xaero 桥接，**纯反射**，不引用任何 Xaero 类）
- 修改 `Localizedrain.java`：注册 payload handler
- 修改 `LocalizedrainClient.java`：注册客户端 tick / 登出监听，构造时尝试安装
- 修改 `WeatherManager.java`：`send` 发送目标状态 payload；`SentState` 增加 `thundering` 字段
- 修改 `gradle.properties`：`mod_version=1.1.0`
- `build.gradle` 不需要任何 Xaero 依赖；仓库不包含 Xaero 的 jar（第三方再分发）

## 兼容性

- Xaero's Minimap 是**可选**依赖：未安装时 `ModList` 检查直接跳过，不加载任何 Xaero 类；
  专用服务器也不会加载（客户端类只在 `LocalizedrainClient` 的客户端初始化路径上被使用）。
- payload 用 `optional()` 注册，客户端没装本模组时服务端不会尝试发送。
- Xaero 若之后重建内置信息行，本模组每 20 tick 自动重新安装一次替换行。
- 替换行沿用原 `weather` 的 id 与名称翻译键，Xaero 配置里该行的启用状态保持不变。

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
.\gradlew.bat clean build --offline --console=plain
```

- 结果：BUILD SUCCESSFUL（无 javac 警告，且不需要 Xaero 的 jar 参与编译）
- 产物：`build/libs/localizedrain-1.1.0.jar`，47,814 字节
- SHA256：`3123D8E4370A5B7C519EACA080D6D7681870E74D9E5725BCFEE71DA08B52AB95`
- manifest 保留 `Automatic-Module-Name: com.spectrum255.localizedrain`
- `neoforge.mods.toml`：`version="1.1.0"`
- jar 内不含任何 `xaero.*` 类

## 未验证（如实标注）

按用户要求没有启动客户端/专用服务器，本版本**只做了构建与产物检查**，以下需要在游戏里确认：

1. 启用 Xaero 的天气信息行，进雨区：HUD 应在一个 tick 内从「晴天/无」切到「下雨」，而天空渐变仍约 5 秒。
2. 进雷暴区：HUD 显示「雷暴」，离开后立即回「下雨」，再离开雨区回无天气行。
3. Xaero 配置里该行的开关、排序不丢。
4. 未装 Xaero 时客户端正常启动；专用服务器正常启动。
5. 1.0.1 的 `/weather`、落雷、刷怪、亡灵燃烧行为不变。

## 备注

- Meteoros Protocol 仍内置 `libs/localizedrain-1.0.1.jar`，本次未改那个工程，也未把 1.1.0 复制过去。
  如果 Meteoros 要带上本次兼容，需要他们替换内置 jar。

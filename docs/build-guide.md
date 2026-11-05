# Meteoros Protocol 构建说明（本机唯一可行路径）

> 适用工作区：`D:\minecraft mod\Meteoros Protocol`（原 `E:\minecraft mod\meteorosprotocol-new`，E 盘已不存在）
> 适用工具链：NeoForge 1.21.1 / ModDevGradle 2.0.148 / Gradle 9.2.1 / Java 21
> 本文只讲**怎么把构建跑通**，不涉及代码内容。同机另一个模组 Localized rain 用完全相同的命令构建成功。

## 0. 先看结论：当前失败不是代码问题

`.modmind/builds/minecraft-test-build.log`（2026-11-02 19:56，`BUILD FAILED in 2m 23s`）里的关键行：

```
* What went wrong:
> Could not resolve all files for configuration ':neoFormRuntimeTool'.
   > Could not resolve net.neoforged:neoform-runtime:2.0.31.
      > Could not GET 'https://maven.neoforged.net/releases/net/neoforged/neoform-runtime/2.0.31/neoform-runtime-2.0.31.pom'.
         > Connect to maven.neoforged.net:443 ... failed: Connection timed out: getsockopt
```

失败发生在 **`:neoFormRuntimeTool` 依赖解析**阶段，源码一行都还没编译。
两个原因：

1. **Gradle 没有走本机代理**（直连 `maven.neoforged.net` 被墙，IPv4/IPv6 都超时）；
2. 默认 `JAVA_HOME` 指向 **JDK 26**，Gradle 9.2.1 的 Groovy 不支持它，一旦继续往下走就会在解析构建脚本时报

```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_' Unsupported class file major version 70
```

把这两条环境设好，构建就能过。

## 1. 本机环境事实（已核对）

| 项 | 值 |
|---|---|
| 可用的 JDK 21 | `C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot`（存在） |
| 默认 `JAVA_HOME` | `D:\Program Files\Java\jdk-26.0.1`（**不能用于 Gradle 9.2.1**） |
| 本机代理 | `127.0.0.1:7890`，访问 NeoForged maven 返回 **200**（约 1.7s） |
| Gradle wrapper | 9.2.1，发行包已缓存在 `%USERPROFILE%\.gradle\wrapper\dists` |
| 构建缓存 | `%USERPROFILE%\.gradle`（**构建进程必须能写这里**） |

## 2. 一次成功构建的完整命令

在项目根目录打开 **PowerShell**，整段复制执行：

```powershell
# 1) 切到 JDK 21（关键）
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'

# 2) 给 Gradle 传代理（关键）
$env:GRADLE_OPTS = '-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890'

# 3) 停掉可能沿用旧 JAVA_HOME 的 daemon（Gradle 会复用同参数的 daemon）
.\gradlew.bat --stop

# 4) 构建
.\gradlew.bat build --console=plain
```

最省事的单行版：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'; $env:GRADLE_OPTS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890'; .\gradlew.bat --stop; .\gradlew.bat build --console=plain
```

> 注意：不要用 `gradle` 全局命令，始终用项目里的 `.\gradlew.bat`。

## 3. 预期耗时与输出

- **首次构建**：约 10–12 分钟。会先下载 NeoFormRuntime，再反编译 Minecraft，日志里能看到
  `downloadJson / mergeMappings / decompile / recompile / sourcesWithNeoForge` 等步骤，最后 `BUILD SUCCESSFUL`。
  中途看起来“卡住”是正常的（decompile 单步可能要 3 分钟以上）。
- **之后**：几秒到十几秒（配置缓存 + 构建缓存生效）。
- **产物**：`build/libs/meteorosprotocol-1.0.0.jar`。
- 配置缓存生效时第二行通常显示 `Reusing configuration cache.`。

## 4. 错误对照表

| 日志里的错误文本 | 原因 | 处理 |
|---|---|---|
| `Unsupported class file major version 70` / `BUG! exception in phase 'semantic analysis'` | `JAVA_HOME` 还是 JDK 26 | 按第 2 节设为 JDK 21，然后 `.\gradlew.bat --stop` 再构建 |
| `Could not resolve net.neoforged:neoform-runtime:2.0.31`、`Connect to maven.neoforged.net:443 ... timed out` | 没走代理 | 设 `GRADLE_OPTS`（第 2 节），`--stop` 后重试 |
| `no explicit Java module name`（jarJar 阶段） | 被 jarJar 的本地 jar 缺少 `Automatic-Module-Name` | 当前 `libs/localizedrain-1.0.0.jar` **已含**该属性；若报错，说明用的是旧 jar，用 `D:\minecraft mod\Localized rain\build\libs\localizedrain-1.0.0.jar` 覆盖 |
| `Timeout waiting to lock ...gradle-9.2.1-bin.zip.lck (拒绝访问)` | 另一构建进程/沙箱持有锁，或沙箱不允许写 `%USERPROFILE%\.gradle` | 关掉其它构建进程，`.\gradlew.bat --stop` 后重试；若 DSH 沙箱是只读，需要 workspace-write / full access 才能构建 |
| 环境已改仍报旧错 | Gradle daemon 复用旧 JVM | `.\gradlew.bat --stop`；必要时加 `--no-daemon` |
| configuration cache 相关报错（改了环境/插件后） | 旧配置缓存失效 | `.\gradlew.bat build --no-configuration-cache` 跑通一次，之后恢复默认 |
| 代理偶发超时 | 网络抖动 | 直接重试；首次成功后可用 `--offline` |

## 5. 构建前 10 秒自检

```powershell
# JDK 是否为 21
& 'C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot\bin\java.exe' -version

# 代理是否可用（期望输出 200；000 表示代理没开）
curl.exe -s -o NUL -w "proxy=%{http_code}`n" --max-time 20 -x http://127.0.0.1:7890 `
  https://maven.neoforged.net/releases/net/neoforged/neoform-runtime/2.0.31/neoform-runtime-2.0.31.pom
```

两项都正常再开始构建，能避免等 2 分钟才失败。

## 6. 备选：把代理持久化到用户级 Gradle 配置

不想每次设 `GRADLE_OPTS`，可以把代理写进 `%USERPROFILE%\.gradle\gradle.properties`（该文件不属于仓库，不会污染项目）：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
```

这样即使不设环境变量，Gradle 也能下载依赖。
（**不建议**把 `org.gradle.java.home` 写进这个文件：它会影响本机所有 Gradle 项目；JDK 的切换用 `JAVA_HOME` 环境变量即可。）

## 7. 构建成功的验证清单

```powershell
# 1) 有 BUILD SUCCESSFUL
# 2) 产物存在
Get-ChildItem build\libs

# 3) jar 内容完整（应能看到 neoforge.mods.toml、jarjar 内嵌的前置）
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path 'build\libs\meteorosprotocol-1.0.0.jar'))
$z.Entries | Where-Object { $_.FullName -like 'META-INF/*' } | Select-Object FullName, Length
$z.Dispose()
```

期望至少包含：

- `META-INF/neoforge.mods.toml`
- `META-INF/jarjar/localizedrain-1.0.0.jar`
- `META-INF/jarjar/metadata.json`
- 各 `com/spectrum255/meteorosprotocol/**/*.class`

## 8. 关于 jarJar 内置前置（本项目的特殊前提）

`build.gradle` 里是：

```groovy
implementation files("libs/localizedrain-1.0.0.jar")
jarJar files("libs/localizedrain-1.0.0.jar")
```

两个前提必须同时满足，否则 `jarJar` 会失败：

1. `libs/localizedrain-1.0.0.jar` 必须是**重新构建过的**版本，其 `META-INF/MANIFEST.MF` 含
   `Automatic-Module-Name: com.spectrum255.localizedrain`（已核对当前 jar 包含该行）；
2. 该 jar 必须真的存在（`implementation files(...)` 在文件缺失时不会报得很明显，`jarJar` 才会报）。

如果 Localized rain 侧代码有改动，先在本项目构建里重新生成 jar，再覆盖到 Meteoros 的 `libs/`：

```powershell
# 在 D:\minecraft mod\Localized rain 下构建后
Copy-Item 'D:\minecraft mod\Localized rain\build\libs\localizedrain-1.0.0.jar' `
          'D:\minecraft mod\Meteoros Protocol\libs\localizedrain-1.0.0.jar' -Force
```

## 9. 不要做的事

- **不要**把 Gradle wrapper 降到 8.10.2。之前“wrapper 下载卡住”的真实原因也是代理，9.2.1 在本机已验证可用。
- **不要**为了绕开网络错误去删依赖、改 `neoForge` 配置或加 `repositories { mavenLocal() }` 之类。
- **不要**在项目文件里硬编码本机 JDK 路径（`org.gradle.java.home`），会把机器相关配置带进仓库。
- **不要**跳过 `--stop` 就改环境重试：daemon 复用旧 JVM 是“改了没生效”的常见原因。

## 10. 给自动化 agent 的最短路径

```text
1. $env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.6.7-hotspot'
2. $env:GRADLE_OPTS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890'
3. .\gradlew.bat --stop
4. .\gradlew.bat build --console=plain   （首次约 10 分钟，不要中途判定失败）
5. 检查 build\libs\meteorosprotocol-1.0.0.jar 存在且 BUILD SUCCESSFUL
```

如果第 4 步失败，先按第 4 节的错误对照表定位；只贴错误文本时，请同时给出**完整的第一段 `What went wrong`**，不要只贴 `BUILD FAILED`。

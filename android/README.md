# 直播监测 · Android 版

从 Windows 桌面版「直播监测」移植的 Android 应用（Kotlin + Jetpack Compose）。
**核心是开播提醒**：轮询你添加的斗鱼/虎牙/B站/抖音/快手直播间公开页面，主播开播时发出本地通知提醒。
v1.1.0 恢复桌面版的“进入直播间”能力：可从列表/通知打开直播间（优先平台 App；未安装时提示自行在应用商店下载安装，或直接用浏览器打开网页版），支持自定义提示音与“开播时自动打开直播间”（默认关闭）。
**v1.1.1 变更**：按用户要求**下架“下载 App/应用市场/官网下载页”跳转**，未安装 App 时只提示用户自行下载安装，保留“用浏览器打开网页版/取消”。
不登录账号、无服务器、无第三方统计 SDK。

| 项目 | 值 |
| --- | --- |
| 包名 | `com.livemonitor.app` |
| 版本 | **1.1.1（versionCode 3）** |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| 语言/UI | Kotlin 2.0.21 + Jetpack Compose（Material 3） |
| 网络 | OkHttp 4.12.0；仅 HTTPS + 平台域名精确白名单 + **严格 TLS 证书固定（CA 家族级，默认关闭；可开启）** |
| 构建 | Gradle 8.9 + AGP 8.7.3，release 启用 R8 minify + shrinkResources |
| 权限 | INTERNET、ACCESS_NETWORK_STATE、POST_NOTIFICATIONS、FOREGROUND_SERVICE、FOREGROUND_SERVICE_SPECIAL_USE、WAKE_LOCK（无新增权限） |
| 包可见性 | `<queries>` 仅声明 5 个平台 App 包名；**未申请 QUERY_ALL_PACKAGES** |
| 当前交付 | `dist/直播监测-Android-v1.1.3-signed.apk`：已签名 release APK（v2+v3、RSA 4096-bit、非 debug 证书），**可直接安装/覆盖升级**；签名密钥/备份均在本机且已 gitignore |

> 本目录是 Android 工程根目录（`android/`）。桌面版源码/文档在仓库根目录 `src/`、`README.md`、`docs/`。

## 1. 目录结构

```
android/
├── app/
│   ├── build.gradle.kts            # 应用模块（release: R8 + shrinkResources + lint abortOnError）
│   ├── proguard-rules.pro          # R8 规则（不放宽混淆）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml # 仅 MainActivity 导出；RoomJumpActivity/Service 未导出；queries 5 包；allowBackup=false
│       │   ├── java/com/livemonitor/app/
│       │   │   ├── App.kt / AppGraph            # 进程初始化、仓储单例、证书固定开关应用
│       │   │   ├── core/model/                  # 平台/状态/设置/房间模型（autoOpenRoom/customSoundFile 等）
│       │   │   ├── core/store/                  # AppRepository + KvStore（SharedPreferences）
│       │   │   ├── core/monitor/                # MonitorService / MonitorEngine / MonitorControl / Runtime
│       │   │   ├── core/notify/                 # AlertPolicy / AlertCenter / AlertNotifier / NotificationHelper / AlertSoundPlayer / CustomSound*
│       │   │   ├── core/platform/               # PlatformApi / UrlPolicy / TlsPolicy / HttpFetcher / Checkers / Parsers / Sanitizers
│       │   │   ├── core/jump/                   # JumpTargets / RoomLauncher / RoomJumpIntent（进入直播间）
│       │   │   ├── ui/                          # MainActivity / RoomJumpActivity + Compose（列表/添加/设置/诊断日志）
│       │   │   └── util/                        # AppLog（本地日志，脱敏/轮转）、TimeFormat
│       │   └── res/                             # 图标、内置提示音 chime.wav、文案、network_security_config.xml
│       └── test/java/...                        # JVM 单元测试（含 TlsPolicy / JumpTargets / CustomSound 等）
├── docs/                           # 迁移说明、架构与省电、威胁模型、合规评估、隐私政策、安全测试报告
├── security/
│   ├── check-apk-hardening.ps1     # 签名 APK 加固检查（签名/证书/pin/新功能/queries/跳转/自定义音/渠道）
│   ├── check-source-hardening.ps1  # 源码/Manifest/Gradle 加固规则扫描（bad/good 夹具自检）
│   ├── run-security-tests.ps1      # 一键编排：单测 → lint → 源码 → APK → 依赖 → SHA/版本 → 汇总
│   ├── tests/fixtures/             # 规则自检夹具（不参与产品构建）
│   └── reports/                    # 生成的报告（JSON/MD/日志；不入库）
├── scripts/                        # dev-gradle / build-release / sign-release
├── gradle/libs.versions.toml       # 依赖版本集中声明
├── keystore/livemonitor-release.jks        # 本机签名密钥（**已 gitignore，不入库**）
├── keystore-backup/                        # 签名密钥自助备份包（keystore+口令说明，**高度敏感、已 gitignore、不得提交/上传**）
├── build.gradle.kts / settings.gradle.kts / gradle.properties
└── dist/                           # 交付产物（已签名 APK + SHA-256；不入库）
```

## 2. 环境要求

| 工具 | 版本/路径（本机示例） |
| --- | --- |
| JDK | 17（`D:\DevTools\jdk17`，需 `JAVA_HOME`） |
| Gradle | 8.9（本机 `D:\DevTools\gradle-8.9\bin\gradle.bat`，或 `gradlew.bat`） |
| Android SDK | compileSdk 35；build-tools 34/35（需 `aapt2`、`apksigner`、`zipalign`；`ANDROID_HOME`） |
| PowerShell | 7.x |
| 动态测试（可选） | Android 模拟器/真机（本项目阶段三使用 LDPlayer 14 / Android 14 / x86_64） |

## 3. 构建

```powershell
cd android

# 方式 A：Gradle Wrapper（对外标准方式）
.\gradlew.bat :app:assembleDebug

# 方式 B：本机脚本（自动注入 JDK/SDK 路径）
pwsh -NoProfile -File scripts/dev-gradle.ps1 :app:assembleDebug

# 单元测试 / Lint
pwsh -NoProfile -File scripts/dev-gradle.ps1 :app:testDebugUnitTest --console=plain
pwsh -NoProfile -File scripts/dev-gradle.ps1 :app:lintRelease --console=plain

# 一键 release 构建（测试 → lintRelease → assembleRelease → 复制到 dist/ 并生成 SHA-256）
pwsh -NoProfile -File scripts/build-release.ps1
```

产物：

| 产物 | 说明 |
| --- | --- |
| `dist/直播监测-Android-v1.1.3-signed.apk` | **当前交付：已签名 release v1.1.3（兼容性修复：证书固定默认关闭、检测失败原因可见）**（v2+v3、RSA 4096-bit、非 debug 证书），可直接安装/覆盖升级 |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | AGP 生成的 unsigned 中间产物（用 `scripts/sign-release.ps1` 签名） |
| `dist/直播监测-Android-v1.0.0-debug-test.apk` | debug 测试包（debuggable=true，**仅本机功能试用，禁止分发**） |

## 4. 签名与密钥管理

- 本仓库**不提交 keystore/口令**：`android/keystore/`、`android/local.properties` 均已 `.gitignore`。
- 当前签名密钥：`android/keystore/livemonitor-release.jks`（PKCS12），口令写在 `local.properties` 的
  `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD`；`scripts/sign-release.ps1` 负责重签并校验（v2+v3 / 4 字节对齐 / SHA-256）。
- **首次安装与后续升级必须使用同一密钥**，否则无法覆盖安装。
- **签名密钥备份（当前，含 v1.1.3 版本记录）**：`android/keystore-backup/直播监测-Android-签名密钥备份.zip`（已去掉版本号）内含 keystore **与口令说明**（高度敏感），该目录已被 `.gitignore` 忽略（`git check-ignore` 确认不进入仓库；`git ls-files` 无该目录文件）。请把备份包存放在**其他可信离线设备/介质**并妥善保管；**不要上传网盘/聊天工具/仓库，不要随 APK 分发，也不要把口令截图或内容放进任何文档**。口令与 keystore 分离保管更安全。备份 zip 实测 SHA-256 `03770bcd6db465365a0fe3f09e200d6f4a5cb1a81427ab16e00a51fbc008f2c1`（与随附 `.sha256` 一致），内含 v1.1.3 版本记录与“无自动下载 App 功能”说明。
- **公开分发前建议**：用组织自有的正式发布密钥重签（当前为本机生成的发布密钥），同步替换备份；不要把 keystore/口令提交仓库或打包进 APK。

```powershell
# 如需用自己的密钥重签（生成新密钥示例）
keytool -genkeypair -v -keystore keystore/livemonitor-release.jks `
  -storetype PKCS12 -alias livemonitor -keyalg RSA -keysize 4096 -validity 10000 `
  -dname "CN=<你的组织>, O=<你的组织>, C=CN"

# local.properties 写入 RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD 后：
pwsh -NoProfile -File scripts/sign-release.ps1 `
  -UnsignedApk app/build/outputs/apk/release/app-release-unsigned.apk `
  -OutApk dist/直播监测-Android-v1.1.3-signed.apk

# 验证签名与哈希
& "$env:ANDROID_HOME\build-tools\34.0.0\apksigner.bat" verify --verbose --print-certs dist\直播监测-Android-v1.1.3-signed.apk
Get-FileHash dist\直播监测-Android-v1.1.3-signed.apk -Algorithm SHA256
```

当前交付：`dist/直播监测-Android-v1.1.3-signed.apk`，**1,640,615 字节**，SHA-256 `082f3e3708195886789004632db013693fed3d221b0cb031de070ebd16ed0d23`（同目录 `.sha256` 与安全测试报告 v3.3 一致），
签名沿用同一密钥：v2+v3、v1=false、RSA 4096-bit、证书 DN `CN=LiveMonitor Android, OU=Mobile, O=LiveMonitor, L=Beijing, ST=Beijing, C=CN`。

## 5. TLS 证书固定（防中间人）

- **默认关闭**（兼容华为/卓易通等网络与证书链差异的环境）：设置 → 安全 → “严格证书固定（防中间人）”可手动开启；开启后只信任 GlobalSign/DigiCert CA 家族，若平台更换证书或网络代理导致检测失败，可关闭后重试。
- 固定的是**平台证书链所属的 CA 家族**（中间证书+根证书 SPKI）：GlobalSign（斗鱼/B站/快手）、DigiCert（虎牙/抖音），共 9 个 pin；**不是叶子证书**，平台正常换证书不会误伤。
- 实现：OkHttp `CertificatePinner` + `ConnectionSpec.MODERN_TLS`（TLS 1.2+）；每次请求/重定向仍先过 https + 精确域名白名单。
- 固定失败会归类为 POLICY 并提示“TLS 证书固定校验失败，可能存在中间人劫持”。
- LDPlayer 实网验证（阶段三）：pin 开启时斗鱼/GlobalSign、B站/GlobalSign、虎牙/DigiCert 链均检测成功；关闭/再开启切换正常。
- **边界（务必理解）**：pin 固定 CA 家族，不是单一证书；平台换 CA 需更新 pin（或用户临时关闭开关）；同一 CA 家族下错误签发的证书仍可能通过；**pinning 不隐藏客户端 IP**（任何直连都会暴露 IP，隐藏需用户自建 VPN/代理）；无法防御“已 root 且植入系统 CA 并同时绕过 pin”的极端情况。

## 5.1 v1.1.x 新功能：进入直播间 / 自定义提示音 / 自动打开（v1.1.1 下架下载 App）

- **进入直播间（用户主动）**：列表行按钮与通知动作「进入直播间」→ 先尝试用 `setPackage` 显式打开对应平台 App 直播间；未安装/打不开时弹出提示，**仅提供「用浏览器打开网页版 / 取消」**（不再提供下载 App/应用市场/官网下载链接；提示用户自行在应用商店下载安装）。**默认不自动跳转**；通知点击本身仍只打开本 App 列表。
- **安全边界**：`RoomJumpActivity` 未导出、只接收本地房间 key 并从仓储重新查询；所有 URL 由本地已校验房间数据或固定 https 常量产生；平台 App 跳转显式 setPackage；浏览器打开先 `resolveActivity` 取系统默认 handler 再 `setPackage` 显式启动（解析失败时回退隐式为低风险残余）；`<queries>` 仅声明 5 个平台包 + https VIEW intent（浏览器解析），无 QUERY_ALL_PACKAGES；**v1.1.1 已无 market/应用市场/官网下载跳转**。
- **自定义提示音**：设置 → 提示音 → 选择音频（SAF，无需存储权限）→ 复制到应用私有目录（2MB 上限、wav/mp3/ogg/m4a/aac、固定文件名）→ MediaPlayer 解码校验；可试听/恢复内置音/删除。文件不上传、不读取其他文件，日志不记录路径。
- **开播时自动打开直播间**：默认**关闭**，需用户显式开启；提醒发出后 3 秒尝试一次（经 `RoomLauncher` 统一加 NEW_TASK+异常兜底）。**限制**：Android 10+ 后台启动 Activity（BAL）可能被系统静默拦截，自动打开不保证生效（SEC-103）；失败不影响监测与通知。
- **通知渠道迁移**：`live_alerts` → `live_alerts_v2`（静音渠道，首声由 App 播放器播放，遵循系统静音/勿扰/通知音量）；升级时会删除旧渠道（旧渠道的自定义声音设置会丢失，SEC-104）。
- **v1.1.1 变更**：**已下架“下载 App/应用市场/官网下载页”功能**；未安装 App 时只提示用户自行在应用商店下载安装，弹窗仅保留“用浏览器打开网页版/取消”。

## 6. 安全验证（可重复执行）

安全测试脚本位于 `android/security/`，只读、可重复执行；报告写入 `android/security/reports/`（JSON + Markdown + 原始日志）。
退出码约定：**0 = 无 FAIL，1 = 存在 FAIL，2 = 参数/环境错误**。

### 6.1 一键编排（推荐）

```powershell
pwsh -NoProfile -File security/run-security-tests.ps1 `
  -ApkPath "dist/直播监测-Android-v1.1.3-signed.apk" `
  -ExpectedSha256 082f3e3708195886789004632db013693fed3d221b0cb031de070ebd16ed0d23 -ExpectedVersionName 1.1.3 -RequireApk

# 快速静态复检（跳过 Gradle 单测/lint/依赖）
pwsh -NoProfile -File security/run-security-tests.ps1 -SkipGradle -SkipDependencies -ApkPath <apk>
```

内部依次：`:app:testDebugUnitTest` → `:app:lint<Debug|Release>`（有 release APK 用 Release）→ 源码加固扫描 → APK 加固检查 → `:app:dependencies --configuration releaseRuntimeClasspath` → SHA-256 核对 → 汇总。Gradle 步骤遇并行构建文件锁会自动等待重试；**不执行 clean、不执行 assembleRelease**。

### 6.2 源码加固扫描

```powershell
pwsh -NoProfile -File security/check-source-hardening.ps1
pwsh -NoProfile -File security/check-source-hardening.ps1 -SelfTest   # bad/good 夹具规则自检
```

检查：Manifest 权限白名单/导出组件/深链接、NSC 明文与 CA、allowBackup、specialUse、Gradle release 加固、ProGuard、依赖统计 SDK、源码禁用 API、明文 http、PendingIntent `FLAG_IMMUTABLE`、日志泄露与脱敏、TLS 绕过、**TLS 证书固定（pin 数量/默认关闭可开启/逃生口）**、SharedPreferences 模式、硬编码密钥、WakeLock 配对、15s 间隔下限、测试代码是否混入 main。

### 6.3 APK 加固检查

```powershell
pwsh -NoProfile -File security/check-apk-hardening.ps1 -ApkPath "dist/直播监测-Android-v1.0.0-signed.apk"
pwsh -NoProfile -File security/check-apk-hardening.ps1 -SelfTest   # 用 debug APK 自检规则
```

检查：包名/版本/minSdk/targetSdk、权限白名单与敏感权限、导出组件、allowBackup、usesCleartextTraffic、NSC 实际内容、debuggable/testOnly、前台服务 specialUse+property、**签名（v2/v3、v1、debug 证书、密钥长度、证书 DN）**、dex 二进制扫描（禁用 API/明文 http/硬编码密钥/第三方统计 SDK/**证书 pin 字符串**）、原生库、zipalign、SHA-256。

### 6.4 报告位置

| 文件 | 内容 |
| --- | --- |
| `security/reports/security-summary-latest.json/.md` | 一键编排汇总（单测/lint/源码/APK/依赖/SHA-256/结论） |
| `security/reports/source-hardening-latest.json/.md` | 源码加固扫描明细（含每条证据） |
| `security/reports/apk-hardening-latest.json/.md` | 签名 APK 加固明细（含签名/证书/pin/dex 证据） |
| `security/reports/debug-apk/` | debug APK 对照扫描 |
| `security/reports/gradle-*.log`、`dependencies-*.txt` | Gradle 原始日志与依赖清单 |

## 7. 测试与 Lint

```powershell
pwsh -NoProfile -File scripts/dev-gradle.ps1 :app:testDebugUnitTest --console=plain
pwsh -NoProfile -File scripts/dev-gradle.ps1 :app:lintRelease --console=plain
```

- 当前 v1.1.3 实测（兼容性修复：证书固定默认关闭、房间卡片显示失败原因、诊断日志记录每次检测；测试数不变）：**254 个 JVM 单元测试 / 12 个测试类 / 0 失败**；Lint release **0 error / 0 warning**（v1.1.2 图标资源整理后无告警）。
- 覆盖：输入解析、URL 白名单/SSRF、5 平台解析器（防御性）、状态机、提醒策略/通知 ID、HTTP 抓取、TLS 证书固定、**进入直播间目标构造/跳转契约、自定义提示音策略/存储、通知渠道迁移、设置默认值**等 JVM 测试。

## 8. 动态测试（LDPlayer 14）

环境：LDPlayer 14，Android 14（API 34），x86_64，1600x900；安装**签名 release APK**。结果详见 `docs/安全测试报告.md` v3.3 第 8 节（按用户要求，动态测试截图未随仓库发布，已在本地归档）。

**v1.1.2 图标更换 / v1.1.3 兼容性修复说明**：v1.1.2 仅更换为与 Windows 桌面版一致的图标；v1.1.3 针对华为/卓易通兼容环境将严格证书固定改为默认关闭，并新增“检测失败原因”显示与每次检测诊断日志（无权限/Manifest 变更）。以下行为动态结果继承自 v1.1.1（Lead 执行、评审员核验命令输出/证据）：
- ✅ v1.1.0→v1.1.1 覆盖安装成功（同密钥、versionCode 3），房间/设置/自定义提示音保留；启动无崩溃。
- ✅ 未安装 App（斗鱼）点“进入直播间”→ 弹窗正文“未检测到「斗鱼」App。请自行在应用商店下载安装后再进入，或直接用浏览器打开网页版。”；**仅「用浏览器打开网页版 / 取消」，无下载按钮/无市场跳转**（v1.1.1 已下架下载功能）。
- ✅ 浏览器网页版分支：打开 `m.douyu.com/252140`，对应直播间、标题正确。
- ✅ B站 App 已安装直达：代码未变（v1.1.0 已实测 `LiveRoomActivityV3`，v1.1.1 同一实现）。
- ✅ 通知动作仍为「进入直播间/忽略/不再提醒」；自定义提示音、自动打开（默认关）设置保持；无崩溃。
- ⚠️ 未覆盖：提示音实际可闻性（模拟器无音频采集）、App 后台时自动打开（Android 10+ BAL 限制）、真机 ARM/国产 ROM、Doze/厂商杀后台、24h 长跑、抓包、抖音/虎牙/快手 App 已安装时 deep link；v1.1.1 未复测 PSS/CPU（v1.0.0 模拟器读数约 41MB/idle≈0% 沿用）。

**v1.1.0 基线**（历史）：真实三平台开播检测、Heads-up+常驻通知、通知动作、暂停/继续、HOME 后台存活、PSS≈41MB、证书固定实网链匹配均通过；v1.0.0 基线：安装/启动、检测/提醒、服务常驻通知等通过。
## 9. 已知限制

1. **动态覆盖有限**：LDPlayer 模拟器（x86_64/Android 14）已验证核心链路，但未覆盖真机 ARM/厂商 ROM、Doze/厂商杀后台、24h 长跑、抓包、提示音可闻性、抖音/快手真实房间端到端（见安全测试报告第 8.4 节）。
2. **签名密钥与备份**：keystore + 备份包（含口令）都在本机且已 gitignore；必须离线备份、同密钥升级；备份包泄露=他人可伪造同签名 APK → 严格保管；公开分发建议组织自有密钥重签。
3. **证书固定维护**：pin 固定 CA 家族；平台换 CA 会检测失败，可用设置开关临时关闭（降低 MITM 防护）；同一 CA 家族错误签发仍可能通过；**不隐藏 IP**。
4. **无开机自启**：重启手机后需打开一次 App 才会恢复监测（刻意不做 `RECEIVE_BOOT_COMPLETED`）。
5. **后台限制**：Android 12+ 后台启动前台服务受限；Doze/厂商省电可能延迟提醒；**自动打开直播间受 Android 10+ BAL 限制可能不生效**（SEC-103）。
6. **外部跳转残余风险（v1.1.1）**：平台 App 跳转显式 setPackage；浏览器先解析系统默认 handler 再 setPackage 显式启动；仅当系统解析不到 handler 时回退隐式启动（低风险）。**已下架“下载 App/应用市场/官网下载页”跳转**，queries 仅保留 https（浏览器解析），无 market 相关能力。
7. **通知渠道迁移**：升级会删除旧 `live_alerts` 渠道，用户此前对该渠道的自定义声音设置会丢失；提醒音改为 App 播放器（遵循系统静音/勿扰/通知音量）（SEC-104）。
8. **自定义提示音**：仅支持 wav/mp3/ogg/m4a/aac、≤2MB；解码依赖系统 MediaPlayer（损坏/不支持时回退内置音）；音频文件内容与版权由用户负责。
9. **平台风控**：5 平台可能限流或调整页面结构，检测失败显示“检测失败/网络异常”，不会误报；不做验证码/代理/IP/UA 轮换等绕过。
10. **合规风险**：仅访问平台公开页面，不声称与平台有授权关系；robots/ToS 风险由使用者自负（详见 `docs/合规评估.md`，含虎牙 robots `User-Agent: * Disallow: /` 风险）。
11. **供应链残余风险**：未启用 Gradle dependency verification、仓库优先阿里云镜像（建议发布机构建切官方源并生成 verification metadata）。
12. 除上述 OkHttp `CertificatePinner` 证书固定外，无自定义 TLS/证书校验逻辑；不信任用户安装的 CA（常规抓包调试会失败，属预期安全设计）。

## 10. 文档索引（`android/docs/`）

| 文档 | 内容 |
| --- | --- |
| `迁移说明与功能对照.md` | 桌面 → Android 功能对照（保留/移除/原因）、行为差异、数据迁移与未实现项（含 v1.1.0 进入直播间/自动打开/自定义音、v1.1.1 下架下载 App） |
| `架构与后台省电设计.md` | 组件/线程模型、轮询/退避/抖动、前台服务 specialUse、WakeLock、TLS 固定、进入直播间/自定义音/自动打开、Doze/厂商限制与用户指引 |
| `威胁模型.md` | 资产/信任边界、STRIDE、移动端攻击面、MITM/CA 与证书固定、**外部跳转/Intent 劫持、自定义媒体解析、BAL、包可见性**、密钥备份 |
| `合规评估.md` | 平台访问方式/频率、robots/ToS 风险声明、PIPL/最小化、商店上架合规点、第三方跳转与自定义音合规说明 |
| `隐私政策.md` | 中文隐私政策草案（含外部跳转/自定义音说明；发布前填写开发者/联系方式/生效日期并法务审阅） |
| `安全测试报告.md` | **v3.3（v1.1.3）**：v1.1.1 增量检查 + v1.1.2 图标更换说明、签名 APK 加固、依赖、254 测试、LDPlayer 动态复测、证书固定、残留风险与结论 |

## 11. 合规与免责

- 本 App 不声称获得斗鱼/虎牙/B站/抖音/快手的任何授权；平台名称仅用于说明访问对象。对平台公开页面的自动化访问可能受平台服务条款/robots 协议限制，使用与分发风险由使用者/项目方自行承担；商用前请做法律评估（详见 `docs/合规评估.md`）。
- 请勿将本 App 用于大规模/商业性监控、绕过平台风控、抓取或转售平台数据。

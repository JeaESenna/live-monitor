# explorer.exe「基于堆栈的缓冲区溢出」崩溃分析

> 结论摘要（2026-10-31）：该错误是 **Windows 资源管理器（explorer.exe）自己 fail-fast 崩溃**，
> 由 **explorer 进程内加载的第三方外壳扩展泄漏 GDI 位图/字体对象**、逼近进程配额后触发；
> **与本项目程序无关**。本机最高优先级嫌疑是 **Internet Download Manager（IDM）旧版壳扩展**。
> 完整取证数据见 `diag/T1_rootcause.md`、`diag/T3_report.md`、`diag/T4_report.md`（分析过程产物），
> 一键取证脚本见 `tools/diagnose-explorer-crash.ps1`。

## 1. 现象

Windows 弹出：

```
explorer.exe - 系统错误
系统在此应用程序中检测到基于堆栈的缓冲区溢出。
溢出可能允许恶意用户获得此应用程序的控制。
```

随后桌面/任务栏消失约 1~3 秒（Winlogon 重启外壳），系统日志依次出现：

| 日志 | 事件 ID | 内容 |
| --- | --- | --- |
| System | 26 (Application Popup) | `弹出应用程序: explorer.exe - 系统错误: ... 基于堆栈的缓冲区溢出 ...` |
| Application | 1002 (Winlogon) | `外壳程序意外停止，explorer.exe 被重新启动。` |
| Application | 1001 (Windows Error Reporting) | 崩溃前 2~12 分钟出现 `EventType=GDIObjectLeak`、`GDIObjectType=5`、`IsFatal=0xFFFFFFFF` 的 NonCritical 报告 |

注意：**没有** Application Error 1000，也**没有** WER APPCRASH 报告——这是"进程被 fail-fast 快速终止 + 系统硬错误弹窗"的典型路径。

## 2. 机制（已证实）

1. `0xC0000409`（STATUS_STACK_BUFFER_OVERRUN）在现代 Windows 里有两个来源：① `/GS` cookie 校验失败；② `__fastfail` / `RaiseFailFastException`。explorer.exe 走的是 **②**，弹窗文案是系统对 0xC0000409 的固定（误导性）文案，**不代表栈真的被写坏**。
2. `GDIObjectLeak` 报告由 **explorer 进程内 `gdi32full.dll` 的 GDI 句柄泄漏跟踪器**（`clientcore\windows\core\ntgdi\client\full\gdihandleleaktracker.cxx`）生成；`GDIObjectType=5` = **BITMAP/SURFACE 位图对象**（少量 `a` = FONT 字体）。
3. explorer 的托盘消息循环里有 `explorer!CTray::CheckGDIHandleLimit`；当进程 GDI 对象数逼近配额（本机注册表 `GDIProcessHandleQuota` = 10000）时 → `wil::FailFast` → `RaiseFailFastException(0xC0000409)` → 硬错误弹窗 → Winlogon 重启外壳。
4. 类似的公开案例（TortoiseGit issue #4278，Windows 10 22H2 19045 x64）用 dump 符号化出完全相同的崩溃栈，其 dump 中 explorer 持有 8945 个 GDI（8347 个位图）；作者进一步用 ETW 证明是**第三方 shell 扩展**每次渲染泄漏约 4 个位图。

## 3. 为什么排除本项目程序（已证实）

| 反证 | 内容 |
| --- | --- |
| 时间早于程序 | 完全相同的 `explorer.exe ... 基于堆栈的缓冲区溢出` 弹窗在本机 **2025-11-11 21:10:19** 就出现过，比本项目第一个可执行文件（2026-08-27）早约 9 个月 |
| 进程隔离 | 本程序是纯托管 .NET 进程，无 DLL 注入、无全局钩子、无 `SetWindowsHookEx`/`WriteProcessMemory`；没有任何代码运行在 explorer.exe 地址空间内，物理上不可能写坏 explorer 的栈 |
| 资源稳定 | 本机实测（程序已连续运行数小时）：GDI=43、USER=48、句柄≈600、内存≈52MB，无增长；同时 explorer 290、IDM 组件上千 |
| 崩溃时程序正常 | 2026-10-31 12:20:01 崩溃前后 `app.log` 每 30 秒一条检测记录连续无缺口，程序未受波及 |
| 外壳交互面极小 | 只有托盘图标（`NotifyIcon.Text` 仅在值变化时才会真正发 `Shell_NotifyIcon`，IL 反汇编已确认 .NET 4.8 setter 相等短路）、打开浏览器、剪贴板、删除旧版自启动注册表项，均为低频合法调用 |

> 额外澄清：用户记忆里"程序崩溃"还混入了本项目程序自己在 2026-08-15 / 08-27 的 10 次 `0xC00000FD`（"无法创建新的堆栈防护页面"）——那是**当时开发构建自身进程的栈溢出**，发生在当前源码快照（08-27 09:15 之后）之前，与 explorer 崩溃是两条线。当前版本已加后台线程兜底与长跑稳定性修复，本次自测 79/79 通过。

## 4. 根因排序（本机）

| 优先级 | 嫌疑 | 关键证据 |
| --- | --- | --- |
| **R1 高度可能** | **IDM 旧壳扩展 + 新主程序混装** | ① IDM 官方 **6.43 Build 5（2026-07-11）** 更新日志：*"Fixed a bug that sometimes caused Windows Explorer to crash"*；② 本机 `IDMan.exe` = **6.43.2.3**（2026-06-28），早于该修复；③ explorer 加载的 `IDMShellExt64.dll` = **6.38.18.26 / 2021-03-02**（严重老旧混装）；④ `IDMIntegrator64.exe` 实测 GDI 2025→2403，约 +34~45/分钟；⑤ 公开论坛多例 IDM shell extension 导致 explorer 崩溃 |
| R2 可能 | 火绒 HRShell / HrShredShell | 安全软件注入 explorer 是常见崩溃源；2026-10-29 刚全量更新，但无法解释 10-08/10-16 的崩溃 |
| R3 可能 | 百度网盘 / Adobe / Bandizip / QQNT 的右键或图标叠加扩展 | 都会在 explorer 内做位图渲染，但缺少各自与 explorer 崩溃的已知缺陷证据 |
| R4 次要 | Windows 10 22H2 自身累积泄漏 | 22H2 已于 2025-10-14 结束支持，本机自 2025-06 后无累积更新；只能靠第三方组件侧规避 |

隔离实验说明：在独立进程内直接加载 IDM / 百度网盘 / Adobe 的图标叠加扩展各上千至数千次，未复现 GDI 泄漏（`diag/T4_report.md`）。原因很可能是这些扩展的泄漏发生在 **explorer 的图标加载/右键菜单/多线程上下文**，而非单纯的 `IsMemberOf` 调用路径；因此最终定位仍需下一次崩溃的 dump。

## 5. 处置建议

1. **升级 IDM 到 6.43 Build 5 或更新版本**，安装后确认
   `C:\Program Files (x86)\Internet Download Manager\IDMShellExt64.dll` 版本变为 6.43.x / 2026 年（旧文件很可能不会被覆盖，必要时先卸载 IDM 再安装新版）。
2. 把火绒、QQ、百度网盘、Bandizip、Adobe 等带 shell 集成的软件升级到最新版。
3. 收集证据：
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File tools\diagnose-explorer-crash.ps1
   ```
   报告会输出到桌面 `LiveMonitor_诊断报告_<时间>` 目录。
4. 仍复现则抓崩溃转储（管理员）：
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File tools\diagnose-explorer-crash.ps1 -SetupDumps
   ```
   下次崩溃后用 WinDbg 打开 `%LOCALAPPDATA%\LiveMonitor_ExplorerDumps\*.dmp`，执行 `!analyze -v` 与 `k`，
   故障栈顶的非 Microsoft DLL 即元凶，之后卸载/升级对应软件。
5. 不想等 dump 的快速对照：用微软官方 `Autoruns` 取消勾选第三方 shell 扩展（先 IDM，其次火绒、网盘），观察是否还崩；确认后只保留必要扩展。
6. 系统侧修复：管理员 PowerShell 执行 `sfc /scannow`、`DISM /Online /Cleanup-Image /RestoreHealth`，并保持系统更新到可用的最新补丁级别。

## 6. 本程序侧已做的加固（v1.0.1）

即使已排除本程序，v1.0.1 仍做了以下与"长时间运行 + 外壳交互"相关的加固：

- 托盘文本只在内容变化时通知外壳；不重复创建/更换托盘图标，不做 `Icon.ToBitmap` 之类"图标↔位图"转换。
- 图标资源改为规范 ICO（16~64 为 32bpp DIB、仅 256 为 PNG），自测断言并复核 0 违规。
- 打开直播间只允许 `http/https`（配置被改坏也不会 ShellExecute 本地文件）。
- 计时器/弹窗/托盘图标在关闭时全部释放；字体与界面图片进程内缓存，避免 GDI 抖动。
- 每小时记录 `health:`（GDI/USER/句柄/内存/线程）与 explorer 重启（TaskbarCreated）事件，便于下次长跑问题时快速定位。
- 后台监测线程有顶层异常兜底；配置原子保存 + 备份恢复；日志轮转保留上一份。

---

*本文档基于 2026-10-31 的现场取证（事件日志、WER 报告原文、explorer 模块清单、进程 GDI 计数、隔离实验、公开资料）。*

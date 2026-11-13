package com.livemonitor.app.core.jump

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.AndroidRuntimeException
import com.livemonitor.app.core.model.Platform

/** “进入直播间”动作的执行结果（UI/通知/服务共用）。 */
sealed interface OpenResult {
    /** 已打开 App 内的直播间（或 setPackage 打开平台网页被平台 App 接手）。 */
    data object OpenedRoom : OpenResult

    /** 只能打开 App 首页（尽力而为，App 内房间页打不开）。 */
    data object OpenedHome : OpenResult

    /** 未安装对应平台 App（由 UI 提示用户自行下载，或改用浏览器网页版）。 */
    data object NotInstalled : OpenResult

    /** 所有方式都失败（无浏览器 / 系统限制等）。 */
    data object Failed : OpenResult
}

/**
 * “进入直播间 / 打开平台 App / 浏览器网页版”的 Android 启动器。
 *
 * **下载功能已下架**（用户决定）：本类不提供、也不包含任何应用市场（market:）或
 * 官网下载页跳转；未安装平台 App 时，UI 只提示用户自行到应用商店/官网下载，或直接用
 * 浏览器看网页版。
 *
 * 安全与稳定性约束（安全评审会检查）：
 * - 所有跳转目标都来自 [JumpTargets]（固定常量 + [com.livemonitor.app.core.platform.PlatformApi]
 *   校验过的房间 URL），**绝不接受外部传入的任意 URL**；[openWeb] 也会再次做域名白名单校验；
 * - 每个 `startActivity` 都加 [Intent.FLAG_ACTIVITY_NEW_TASK] 并 try/catch
 *   （ActivityNotFoundException/SecurityException/AndroidRuntimeException 等一律不崩）；
 * - 不记录 URL/Cookie，不申请新权限，不查询这 5 个平台以外的任何应用
 *   （依赖 Manifest `<queries>` 中声明的 5 个包名与 https VIEW 解析能力）；
 * - [openWeb] 先 `resolveActivity` 取系统默认浏览器 handler，再用 `setPackage` 显式启动，
 *   解析不到时才回退系统标准隐式 ACTION_VIEW。残余风险（如实记录）：浏览器最终由系统
 *   默认浏览器选择决定，本 App 不做浏览器包白名单。
 *
 * 降级顺序：webUrl + setPackage(平台 App) → 平台 deep link 候选（已核实优先）→
 * 打开 App 首页（仅 [openRoomInApp]）→ 失败。[autoOpen] 在 App 不可用时直接回退浏览器网页版。
 */
object RoomLauncher {

    /** 对应平台 App 是否已安装（只查 Manifest 声明过的 5 个包）。 */
    fun isAppInstalled(context: Context, platform: Platform): Boolean =
        isInstalled(context, JumpTargets.appPackage(platform))

    /**
     * 尝试在平台 App 内打开直播间。
     *
     * 全部候选都打不开时退化为“打开 App 首页”（返回 [OpenResult.OpenedHome]）；
     * App 未安装返回 [OpenResult.NotInstalled]，非法房间数据返回 [OpenResult.Failed]。
     */
    fun openRoomInApp(
        context: Context,
        platform: Platform,
        roomId: String,
        storedWebUrl: String?,
    ): OpenResult {
        val targets = JumpTargets.of(platform, roomId, storedWebUrl) ?: return OpenResult.Failed
        if (!isInstalled(context, targets.appPackage)) return OpenResult.NotInstalled
        if (tryOpenRoomCandidates(context, targets)) return OpenResult.OpenedRoom
        return if (openAppHomeInternal(context, targets.appPackage)) {
            OpenResult.OpenedHome
        } else {
            OpenResult.Failed
        }
    }

    /** 打开平台 App 首页（固定包名；失败返回 false，不抛异常）。 */
    fun openAppHome(context: Context, platform: Platform): Boolean =
        openAppHomeInternal(context, JumpTargets.appPackage(platform))

    /**
     * 用系统浏览器打开网页版直播间。
     *
     * 只接受 [JumpTargets.isSafeOpenableUrl] 通过的地址（仅平台直播间页 https 域名），
     * 非法返回 false，绝不打开任意 host / 下载页。
     */
    fun openWeb(context: Context, url: String): Boolean {
        if (!JumpTargets.isSafeOpenableUrl(url)) return false
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val handlerPackage = resolveViewHandlerPackage(context, viewIntent)
        return if (handlerPackage != null) {
            // 系统已解析出默认浏览器 handler → 显式指定该包启动，避免同地址被其他 App 抢占
            tryStartActivity(context, Intent(viewIntent).setPackage(handlerPackage))
        } else {
            // 无法解析（例如包可见性限制 / 无默认浏览器）→ 回退系统标准隐式启动
            tryStartActivity(context, viewIntent)
        }
    }

    /**
     * 开播提醒“自动打开直播间”（用户设置开启时由服务调用）。
     *
     * 优先 App 内直播间；App 未安装或 App 内房间页打不开则用浏览器打开网页版。
     * 只要成功打开“直播间”（App 或浏览器）就返回 [OpenResult.OpenedRoom]；
     * 连浏览器都打不开才返回 [OpenResult.Failed]。
     */
    fun autoOpen(
        context: Context,
        platform: Platform,
        roomId: String,
        storedWebUrl: String?,
    ): OpenResult {
        val targets = JumpTargets.of(platform, roomId, storedWebUrl) ?: return OpenResult.Failed
        if (isInstalled(context, targets.appPackage) && tryOpenRoomCandidates(context, targets)) {
            return OpenResult.OpenedRoom
        }
        // App 未安装或 App 内无法直达直播间：回退浏览器网页版（仍是校验过的平台房间 URL）
        return if (openWeb(context, targets.webUrl)) OpenResult.OpenedRoom else OpenResult.Failed
    }

    // ---------------- 内部实现 ----------------

    /** 按 [JumpTargetSet.appDeepLinks] 的顺序逐个尝试（webUrl 用 setPackage 交给平台 App）。 */
    private fun tryOpenRoomCandidates(context: Context, targets: JumpTargetSet): Boolean {
        for (link in targets.appDeepLinks) {
            val uri = try {
                Uri.parse(link)
            } catch (_: Exception) {
                continue
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(targets.appPackage)
            if (tryStartActivity(context, intent)) return true
        }
        return false
    }

    private fun openAppHomeInternal(context: Context, packageName: String): Boolean {
        val launchIntent = try {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: Exception) {
            null
        } ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStartActivity(context, launchIntent)
    }

    /** 解析系统默认 handler 包名（解析失败/包不可见时返回 null）。 */
    @Suppress("DEPRECATION") // resolveActivity(Intent, Int) 在 API 33+ 有 flags 重载，这里兼容 minSdk 26
    private fun resolveViewHandlerPackage(context: Context, intent: Intent): String? = try {
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    } catch (_: Exception) {
        null
    }

    /**
     * 统一启动入口：NEW_TASK + 全异常兜底。
     *
     * 注意：这里不打印任何日志（避免 URL 进入日志），失败只返回 false 由调用方降级。
     */
    private fun tryStartActivity(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: AndroidRuntimeException) {
            false
        } catch (_: Exception) {
            // 兜底：任何启动异常都不能让提醒/UI 崩溃
            false
        }
    }

    /** 只查询 Manifest `<queries>` 里声明的 5 个平台包（无 QUERY_ALL_PACKAGES）。 */
    @Suppress("DEPRECATION") // getApplicationInfo(String, Int) 在 API 33+ 有 flags 重载，这里兼容 minSdk 26
    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }
}

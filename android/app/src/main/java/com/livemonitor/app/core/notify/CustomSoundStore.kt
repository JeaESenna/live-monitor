package com.livemonitor.app.core.notify

import android.content.ContentResolver
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import com.livemonitor.app.core.model.AppSettings
import com.livemonitor.app.util.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 自定义提示音的本地存储（Android，应用内部目录）。
 *
 * 流程：[importFromUri] 用 SAF 的 content:// 读取 → 流式复制到内部临时文件并强制 2MB 上限 →
 * MediaPlayer 校验可解码（prepare 成功且 duration > 0）→ 改名成固定文件名
 * `custom_alert.<ext>`（覆盖旧文件）→ 返回成功/失败原因。
 *
 * 安全约束：
 * - 只接受 SAF 的 content:// Uri（拒绝 file:// 等任意路径），不申请任何存储权限；
 * - 只写应用内部 `filesDir/sounds/`，文件名由本类固定生成，不接受外部路径；
 * - 屏幕显示名/路径不写日志，只记录格式与大小；
 * - 失败路径与临时文件全部清理（finally）。
 */
object CustomSoundStore {

    /** 单个自定义提示音大小上限：2MB。 */
    const val MAX_BYTES: Long = CustomSoundPolicy.MAX_BYTES

    private const val DIR_NAME = "sounds"
    private const val TEMP_FILE_NAME = ".import.tmp"
    private const val BUFFER_BYTES = 8 * 1024

    /** 导入结果（UI 直接消费：成功带固定文件名，失败带中文短句原因）。 */
    sealed interface ImportResult {
        data class Success(val fileName: String) : ImportResult
        data class Failure(val reason: String) : ImportResult
    }

    /**
     * 从 SAF 选择的音频导入为自定义提示音。
     *
     * 注意：包含文件读取与 MediaPlayer 解码校验（同步阻塞），应在 IO 协程中调用。
     */
    fun importFromUri(context: Context, uri: Uri): ImportResult {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return ImportResult.Failure("请通过系统文件选择器选择音频文件")
        }
        val extension: String
        try {
            val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
            val displayName = queryDisplayName(resolver, uri)
            // 有些 provider 会声明一个超过上限的大小：先快速拒绝，后续仍会用流式计数兜底。
            val declaredSize = queryDeclaredSize(resolver, uri)
            if (declaredSize != null && !CustomSoundPolicy.isAllowedSize(declaredSize)) {
                return ImportResult.Failure("文件过大（上限 2MB）")
            }
            extension = CustomSoundPolicy.extensionFor(mimeType, displayName)
                ?: return ImportResult.Failure("不支持的音频格式，请选择 wav/mp3/ogg/m4a/aac 文件")
        } catch (t: Throwable) {
            AppLog.error("Notify", "custom sound query failed", t)
            return ImportResult.Failure("无法读取所选文件")
        }
        val targetName = CustomSoundPolicy.targetFileName(extension)
            ?: return ImportResult.Failure("不支持的音频格式，请选择 wav/mp3/ogg/m4a/aac 文件")

        val dir = soundsDir(appContext)
        if (!dir.exists() && !dir.mkdirs()) {
            return ImportResult.Failure("无法创建提示音目录")
        }
        val temp = File(dir, TEMP_FILE_NAME)
        try {
            temp.delete()
            val copiedBytes = copyToTempFile(resolver, uri, temp) ?: return ImportResult.Failure("无法读取所选文件")
            if (copiedBytes > MAX_BYTES) {
                return ImportResult.Failure("文件过大（上限 2MB）")
            }
            if (copiedBytes <= 0L) {
                return ImportResult.Failure("所选文件为空")
            }
            if (!isDecodable(temp)) {
                return ImportResult.Failure("音频无法解码，请换一个文件")
            }
            val target = File(dir, targetName)
            // 同一时间只保留一个自定义提示音：先删除其它扩展名的旧文件。
            deleteOtherCustomFiles(appContext, keep = targetName)
            if (target.exists() && !target.delete()) {
                return ImportResult.Failure("保存失败，请重试")
            }
            if (!temp.renameTo(target)) {
                // 个别文件系统 rename 失败：退化为复制。
                if (!copyFile(temp, target)) {
                    target.delete()
                    return ImportResult.Failure("保存失败，请重试")
                }
            }
            AppLog.info("Notify", "custom sound imported ext=$extension bytes=$copiedBytes")
            return ImportResult.Success(targetName)
        } catch (t: Throwable) {
            AppLog.error("Notify", "custom sound import failed", t)
            return ImportResult.Failure("导入失败，请重试")
        } finally {
            temp.delete()
        }
    }

    /** 当前自定义提示音文件；文件名非法、文件不存在或为空时返回 null。 */
    fun currentFile(context: Context, fileName: String): File? {
        if (!CustomSoundPolicy.isValidStoredFileName(fileName)) return null
        return try {
            val dir = soundsDir(context.applicationContext).canonicalFile
            val file = File(dir, fileName).canonicalFile
            // 防御性校验：规范化后必须仍直接位于 sounds 目录内（防符号链接/路径穿越）。
            if (file.parentFile != dir) return null
            if (!file.isFile || file.length() <= 0L || file.length() > MAX_BYTES) return null
            file
        } catch (t: Throwable) {
            AppLog.error("Notify", "custom sound resolve failed", t)
            null
        }
    }

    /** 删除自定义提示音（含临时文件/其它扩展名残留）；返回是否确实删除了文件。 */
    fun deleteCustom(context: Context): Boolean {
        val appContext = context.applicationContext
        var deleted = false
        try {
            val dir = soundsDir(appContext)
            if (!dir.exists()) return false
            val files = dir.listFiles() ?: return false
            for (file in files) {
                if (file.name == TEMP_FILE_NAME ||
                    CustomSoundPolicy.isValidStoredFileName(file.name)
                ) {
                    if (file.delete()) deleted = true
                }
            }
        } catch (t: Throwable) {
            AppLog.error("Notify", "custom sound delete failed", t)
        }
        return deleted
    }

    /** 界面展示名：空/非法文件名 → “内置提示音”，否则显示固定文件名。 */
    fun displayName(fileName: String): String =
        CustomSoundPolicy.displayName(fileName, "内置提示音")

    private fun soundsDir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    private fun queryDeclaredSize(resolver: ContentResolver, uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    /** 流式复制；超过上限时立即停止并返回已复制大小（调用方据此报“过大”）。 */
    private fun copyToTempFile(resolver: ContentResolver, uri: Uri, temp: File): Long? {
        val input = try {
            resolver.openInputStream(uri)
        } catch (t: Throwable) {
            AppLog.error("Notify", "custom sound open failed", t)
            null
        } ?: return null
        input.use { source ->
            FileOutputStream(temp).use { sink ->
                return copyStream(source, sink)
            }
        }
    }

    /** @return 复制的字节数；超过 [MAX_BYTES] 时返回大于上限的值（用于区分“过大”）。 */
    private fun copyStream(source: java.io.InputStream, sink: OutputStream): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) return total
            sink.write(buffer, 0, read)
        }
        sink.flush()
        return total
    }

    /** MediaPlayer 解码校验：prepare 成功且 duration > 0（只 prepare、不播放，无声）。 */
    private fun isDecodable(file: File): Boolean {
        val player = MediaPlayer()
        return try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.duration > 0
        } catch (t: Throwable) {
            AppLog.info("Notify", "custom sound decode rejected (${t.javaClass.simpleName})")
            false
        } finally {
            try {
                player.release()
            } catch (_: Throwable) {
                // 释放失败忽略
            }
        }
    }

    private fun deleteOtherCustomFiles(context: Context, keep: String) {
        try {
            val files = soundsDir(context).listFiles() ?: return
            for (file in files) {
                if (file.name == keep) continue
                if (CustomSoundPolicy.isValidStoredFileName(file.name)) file.delete()
            }
        } catch (t: Throwable) {
            AppLog.error("Notify", "custom sound cleanup failed", t)
        }
    }

    private fun copyFile(source: File, target: File): Boolean = try {
        source.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, BUFFER_BYTES)
            }
        }
        target.isFile && target.length() > 0L
    } catch (t: Throwable) {
        AppLog.error("Notify", "custom sound copy failed", t)
        false
    }

    /** 供测试/诊断使用：固定文件名正则（与仓储校验一致）。 */
    val STORED_FILE_PATTERN = AppSettings.CUSTOM_SOUND_PATTERN
}

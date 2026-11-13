package com.livemonitor.app.notify

import com.livemonitor.app.core.notify.CustomSoundPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CustomSoundPolicy] 纯逻辑单元测试（JVM，不依赖 Android）：
 * MIME/扩展名识别、2MB 上限、固定文件名构造、存储文件名合法性与展示名。
 */
class CustomSoundPolicyTest {

    // ---------------- 格式识别 ----------------

    @Test
    fun extensionFromMimeTypes() {
        assertEquals("wav", CustomSoundPolicy.extensionFor("audio/x-wav", null))
        assertEquals("wav", CustomSoundPolicy.extensionFor("audio/wav", "song"))
        assertEquals("mp3", CustomSoundPolicy.extensionFor("audio/mpeg", null))
        assertEquals("mp3", CustomSoundPolicy.extensionFor("audio/mp3", null))
        assertEquals("ogg", CustomSoundPolicy.extensionFor("audio/ogg", null))
        assertEquals("m4a", CustomSoundPolicy.extensionFor("audio/mp4", null))
        assertEquals("m4a", CustomSoundPolicy.extensionFor("audio/x-m4a", null))
        assertEquals("aac", CustomSoundPolicy.extensionFor("audio/aac", null))
    }

    @Test
    fun mimeIsCaseInsensitiveAndIgnoresParameters() {
        assertEquals("mp3", CustomSoundPolicy.extensionFor("AUDIO/MPEG", null))
        assertEquals("mp3", CustomSoundPolicy.extensionFor("audio/mpeg; charset=binary", null))
    }

    @Test
    fun octetStreamFallsBackToSupportedExtension() {
        // 部分 SAF provider 会把音频报成 application/octet-stream，此时靠扩展名判断。
        assertEquals("mp3", CustomSoundPolicy.extensionFor("application/octet-stream", "song.mp3"))
        assertEquals("wav", CustomSoundPolicy.extensionFor(null, "song.wav"))
        assertEquals("ogg", CustomSoundPolicy.extensionFor("application/octet-stream", "mix.ogg"))
    }

    @Test
    fun unknownAudioSubtypeFallsBackToExtension() {
        assertEquals("m4a", CustomSoundPolicy.extensionFor("audio/x-unknown", "song.m4a"))
        assertNull(CustomSoundPolicy.extensionFor("audio/x-unknown", null))
    }

    @Test
    fun rejectsUnsupportedFormats() {
        assertNull(CustomSoundPolicy.extensionFor("text/plain", "note.txt"))
        assertNull(CustomSoundPolicy.extensionFor("video/mp4", "movie.mp4"))
        assertNull(CustomSoundPolicy.extensionFor(null, null))
        assertNull(CustomSoundPolicy.extensionFor("", ""))
        assertNull(CustomSoundPolicy.extensionFor("application/octet-stream", "file"))
        // 有精确 audio MIME 时以 MIME 为准：目标文件名固定为 custom_alert.mp3，不会保留 .exe。
        assertEquals("mp3", CustomSoundPolicy.extensionFor("audio/mpeg", "file.exe"))
        assertEquals("custom_alert.mp3", CustomSoundPolicy.targetFileName("mp3"))
        // 但真正的扩展名识别本身必须拒绝不支持的扩展名
        assertNull(CustomSoundPolicy.extensionFromName("song.exe"))
        assertNull(CustomSoundPolicy.extensionFromName("song.wma"))
    }

    @Test
    fun extensionFromNameHandlesPathsAndCase() {
        assertEquals("mp3", CustomSoundPolicy.extensionFromName("SONG.MP3"))
        assertEquals("mp3", CustomSoundPolicy.extensionFromName("dir/sub/song.mp3"))
        assertEquals("wav", CustomSoundPolicy.extensionFromName("dir\\sub\\song.wav"))
        assertNull(CustomSoundPolicy.extensionFromName(".mp3")) // 只有扩展名没有文件名
        assertNull(CustomSoundPolicy.extensionFromName("song."))
        assertNull(CustomSoundPolicy.extensionFromName("song"))
    }

    // ---------------- 大小上限 ----------------

    @Test
    fun maxBytesIsTwoMegabytes() {
        assertEquals(2L * 1024 * 1024, CustomSoundPolicy.MAX_BYTES)
        // CustomSoundStore.MAX_BYTES 与纯逻辑策略必须一致（const，编译期内联，不会加载 Android 类）
        assertEquals(CustomSoundPolicy.MAX_BYTES, com.livemonitor.app.core.notify.CustomSoundStore.MAX_BYTES)
    }

    @Test
    fun sizeLimitBoundaries() {
        assertFalse(CustomSoundPolicy.isAllowedSize(0))
        assertFalse(CustomSoundPolicy.isAllowedSize(-1))
        assertTrue(CustomSoundPolicy.isAllowedSize(1))
        assertTrue(CustomSoundPolicy.isAllowedSize(CustomSoundPolicy.MAX_BYTES))
        assertFalse(CustomSoundPolicy.isAllowedSize(CustomSoundPolicy.MAX_BYTES + 1))
    }

    // ---------------- 固定文件名 ----------------

    @Test
    fun targetFileNameIsAlwaysFixedAndSafe() {
        assertEquals("custom_alert.wav", CustomSoundPolicy.targetFileName("wav"))
        assertEquals("custom_alert.mp3", CustomSoundPolicy.targetFileName("MP3"))
        assertEquals("custom_alert.m4a", CustomSoundPolicy.targetFileName("m4a"))
        assertNull(CustomSoundPolicy.targetFileName("exe"))
        assertNull(CustomSoundPolicy.targetFileName("../mp3"))
        assertNull(CustomSoundPolicy.targetFileName(""))
        // 无论原始文件名是什么，落盘名都不会包含目录/用户可控部分
        assertFalse(CustomSoundPolicy.targetFileName("wav")!!.contains("/"))
        assertFalse(CustomSoundPolicy.targetFileName("wav")!!.contains(".."))
    }

    @Test
    fun storedFileNameValidation() {
        assertTrue(CustomSoundPolicy.isValidStoredFileName("custom_alert.mp3"))
        assertTrue(CustomSoundPolicy.isValidStoredFileName("custom_alert.wav"))
        assertTrue(CustomSoundPolicy.isValidStoredFileName("custom_alert.ogg"))
        assertTrue(CustomSoundPolicy.isValidStoredFileName("custom_alert.m4a"))
        assertTrue(CustomSoundPolicy.isValidStoredFileName("custom_alert.aac"))

        assertFalse(CustomSoundPolicy.isValidStoredFileName(null))
        assertFalse(CustomSoundPolicy.isValidStoredFileName(""))
        assertFalse(CustomSoundPolicy.isValidStoredFileName("custom_alert.exe"))
        assertFalse(CustomSoundPolicy.isValidStoredFileName("custom_alert.MP3"))
        assertFalse(CustomSoundPolicy.isValidStoredFileName("../custom_alert.mp3"))
        assertFalse(CustomSoundPolicy.isValidStoredFileName("/tmp/custom_alert.mp3"))
        assertFalse(CustomSoundPolicy.isValidStoredFileName("custom_alert.mp3/foo"))
    }

    @Test
    fun displayNameFallsBackToBuiltIn() {
        assertEquals("内置提示音", CustomSoundPolicy.displayName("", "内置提示音"))
        assertEquals("内置提示音", CustomSoundPolicy.displayName(null, "内置提示音"))
        assertEquals("内置提示音", CustomSoundPolicy.displayName("../x.mp3", "内置提示音"))
        assertEquals("custom_alert.mp3", CustomSoundPolicy.displayName("custom_alert.mp3", "内置提示音"))
        assertNotEquals("", CustomSoundPolicy.displayName("custom_alert.wav", "内置提示音"))
    }
}

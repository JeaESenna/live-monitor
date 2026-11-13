package com.livemonitor.app.platform

import com.livemonitor.app.core.model.FailureKind
import com.livemonitor.app.core.model.LiveState
import com.livemonitor.app.core.model.Platform
import com.livemonitor.app.core.platform.PlatformApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PlatformApi 公开契约的兜底测试：非法 id 时 check() 必须快速返回错误
 * （不发起网络请求），开播时间过滤边界必须与桌面版一致。
 */
class PlatformApiContractTest {

    @Test
    fun checkRejectsIllegalIdWithoutNetwork() {
        val badIds = listOf("", "bad id", "a/b", "a?b", "中文字符", "a".repeat(65), "9999\n")
        for (id in badIds) {
            val result = PlatformApi.check(Platform.DOUYU, id, "https://www.douyu.com/9999")
            assertEquals(LiveState.ERROR, result.state)
            assertEquals(FailureKind.PARSE, result.failure)
            assertTrue(result.error.isNotEmpty())
            assertTrue(result.error.length <= 120)
        }
    }

    @Test
    fun checkNeverThrows() {
        // 任意平台 + 非法 id 组合都不得抛异常
        for (platform in Platform.values()) {
            val result = PlatformApi.check(platform, "bad id", "")
            assertEquals(LiveState.ERROR, result.state)
        }
    }

    @Test
    fun sanitizeLiveStartAtBoundaries() {
        val now = 1_800_000_000_000L
        val minute = 60_000L
        val day = 24 * 60 * minute

        assertNull(PlatformApi.sanitizeLiveStartAt(null, now))
        assertNull(PlatformApi.sanitizeLiveStartAt(0L, now))
        assertNull(PlatformApi.sanitizeLiveStartAt(-1L, now))

        assertNotNull(PlatformApi.sanitizeLiveStartAt(now - 2 * 60 * minute, now))
        // 未来 10 分钟内仍接受（平台时间轻微偏差），超过则丢弃
        assertNotNull(PlatformApi.sanitizeLiveStartAt(now + 10 * minute, now))
        assertNull(PlatformApi.sanitizeLiveStartAt(now + 10 * minute + 1, now))
        // 30 天内接受，超过 30 天丢弃
        assertNotNull(PlatformApi.sanitizeLiveStartAt(now - 30 * day, now))
        assertNull(PlatformApi.sanitizeLiveStartAt(now - 30 * day - 1, now))
    }

    @Test
    fun sanitizeLiveStartAtKeepsValueUnchanged() {
        val now = 1_800_000_000_000L
        val value = now - 3_600_000L
        assertEquals(value, PlatformApi.sanitizeLiveStartAt(value, now))
    }
}

package com.livemonitor.app.core.platform

import com.livemonitor.app.core.model.FailureKind
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * 一次 HTTP 抓取的最终结果。
 *
 * 注意：这里只暴露“是否成功 / HTTP 状态码 / 失败分类”，不携带 URL、Cookie、
 * 页面内容，避免这些敏感信息出现在日志或异常链里。
 */
internal sealed interface FetchOutcome {
    data class Ok(val body: String) : FetchOutcome
    data class HttpError(val status: Int) : FetchOutcome
    data class Failure(val kind: FailureKind, val detail: String = "") : FetchOutcome
}

/**
 * 可注入的 HTTP 传输抽象：生产环境固定用 [HttpFetcher]（唯一实现），
 * JVM 单元测试可替换为假传输，离线覆盖各平台主路径/降级路径。
 */
internal interface HttpTransport {
    fun get(url: String, referer: String? = null): FetchOutcome

    /** 给内存 CookieJar 种公开页面里出现过的短期 Cookie；假传输可忽略。 */
    fun seedCookie(host: String, name: String, value: String) = Unit
}

/**
 * 内存 CookieJar：只保留当次进程内的 Cookie（B站首页预热、抖音 __ac_nonce），
 * **绝不落盘、绝不上传**；数量与长度都有上限，避免被平台页面撑爆内存。
 */
internal class MemoryCookieJar : CookieJar {

    private val store = LinkedHashMap<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val list = store.getOrPut(host) { ArrayList() }
        for (cookie in cookies) {
            if (cookie.name.length > MAX_NAME_LEN || cookie.value.length > MAX_VALUE_LEN) continue
            list.removeAll { it.name == cookie.name && it.path == cookie.path }
            if (list.size < MAX_COOKIES_PER_HOST) list.add(cookie)
        }
        while (store.size > MAX_HOSTS) {
            val first = store.keys.firstOrNull() ?: break
            store.remove(first)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        list.removeAll { it.expiresAt < now }
        return list.filter { it.matches(url) }
    }

    companion object {
        private const val MAX_COOKIES_PER_HOST = 20
        private const val MAX_HOSTS = 20
        private const val MAX_NAME_LEN = 64
        private const val MAX_VALUE_LEN = 512
    }
}

/**
 * 平台公开页面的 HTTP 抓取器。
 *
 * 安全/合规约束（安全评审会逐条检查）：
 * - 只用 OkHttp，不做任何代理配置、不轮换 UA/IP、不识别验证码、不执行 JS；
 * - 每次请求（包括重定向的每一跳）都过 [UrlPolicy]：仅 https + 平台精确域名白名单；
 * - 自动重定向关闭，改为手动跟随并逐跳校验，重定向上限 [MAX_REDIRECTS]；
 * - 连接 5s / 读取 10s / 整体 15s 超时，单次响应体上限 3MB（超限按解析失败处理）；
 * - 请求头与桌面版一致（普通 Chrome UA + 中文 Accept-Language），只是模拟正常
 *   浏览器访问公开页面，不用于规避风控；
 * - Cookie 仅内存保存；日志/错误信息不包含 Cookie 与完整 URL。
 *
 * 该类不依赖 android.*，Java 单元测试可以用 MockWebServer 直接覆盖。
 */
internal class HttpFetcher(
    baseClient: OkHttpClient = defaultClient(),
    private val policy: UrlPolicy = PlatformUrlPolicy,
    private val maxBodyBytes: Long = MAX_BODY_BYTES,
    private val maxRedirects: Int = MAX_REDIRECTS,
    private val cookieJar: CookieJar = MemoryCookieJar(),
) : HttpTransport {

    /** 统一关掉自动重定向，改为手动逐跳校验（并强制使用本实例的内存 CookieJar）。 */
    private val client: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(cookieJar)
        .build()

    /**
     * 抓取一个已经由调用方构造好的平台地址。
     *
     * 任何异常都不会抛出，全部转换成 [FetchOutcome]；调用方（平台检测逻辑）
     * 负责把它归类为 CheckResult。
     */
    override fun get(url: String, referer: String?): FetchOutcome {
        val parsed = url.toHttpUrlOrNull()
            ?: return FetchOutcome.Failure(FailureKind.POLICY, "地址格式无效")
        var current: HttpUrl = parsed
        var redirects = 0
        while (true) {
            if (!isAllowedByPolicy(current)) {
                return FetchOutcome.Failure(FailureKind.POLICY, "目标地址不在允许范围")
            }
            when (val attempt = attempt(current, referer)) {
                is Attempt.Redirect -> {
                    if (redirects >= maxRedirects) {
                        return FetchOutcome.Failure(FailureKind.POLICY, "重定向次数过多")
                    }
                    redirects++
                    current = attempt.next
                }

                is Attempt.Final -> return attempt.outcome
            }
        }
    }

    /** 给内存 CookieJar 种一个平台页面里公开出现过的短期 Cookie（抖音 __ac_nonce）。 */
    override fun seedCookie(host: String, name: String, value: String) {
        if (name.length !in 1..MAX_SEED_NAME_LEN) return
        if (value.isEmpty() || value.length > MAX_SEED_VALUE_LEN) return
        if (!SAFE_COOKIE_VALUE.matches(value)) return
        try {
            val cookie = Cookie.Builder()
                .domain(host)
                .path("/")
                .name(name)
                .value(value)
                .build()
            val url = "https://$host/".toHttpUrlOrNull() ?: return
            cookieJar.saveFromResponse(url, listOf(cookie))
        } catch (_: IllegalArgumentException) {
            // 非法 Cookie 直接丢弃：抖音非关键路径，不能因为 Cookie 影响检测
        }
    }

    private fun isAllowedByPolicy(url: HttpUrl): Boolean = try {
        policy.validate(url)
        true
    } catch (_: UrlPolicyViolation) {
        false
    }

    private sealed interface Attempt {
        data class Redirect(val next: HttpUrl) : Attempt
        data class Final(val outcome: FetchOutcome) : Attempt
    }

    private fun attempt(url: HttpUrl, referer: String?): Attempt {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Accept", "*/*")
        if (!referer.isNullOrEmpty()) {
            val ref = referer.toHttpUrlOrNull()
            if (ref != null && isAllowedByPolicy(ref)) builder.header("Referer", ref.toString())
        }
        val call = try {
            client.newCall(builder.build())
        } catch (_: IllegalArgumentException) {
            return Attempt.Final(FetchOutcome.Failure(FailureKind.POLICY, "请求构造失败"))
        }
        val response: Response = try {
            call.execute()
        } catch (e: IOException) {
            return Attempt.Final(ioFailure(e))
        } catch (_: UrlPolicyViolation) {
            return Attempt.Final(FetchOutcome.Failure(FailureKind.POLICY, "目标地址不在允许范围"))
        } catch (_: RuntimeException) {
            return Attempt.Final(FetchOutcome.Failure(FailureKind.UNKNOWN, "请求失败"))
        }
        response.use { resp ->
            val code = resp.code
            if (code in 300..399 && code != 304) {
                val location = resp.header("Location")
                    ?: return Attempt.Final(FetchOutcome.Failure(FailureKind.PARSE, "重定向缺少目标"))
                val next = url.resolve(location)
                    ?: return Attempt.Final(FetchOutcome.Failure(FailureKind.POLICY, "重定向目标无效"))
                return Attempt.Redirect(next)
            }
            if (code !in 200..299) return Attempt.Final(FetchOutcome.HttpError(code))
            return Attempt.Final(readBody(resp))
        }
    }

    private fun readBody(resp: Response): FetchOutcome {
        val body = resp.body ?: return FetchOutcome.Ok("")
        val declared = body.contentLength()
        if (declared > maxBodyBytes) {
            return FetchOutcome.Failure(FailureKind.PARSE, "响应体过大")
        }
        val bytes = try {
            body.byteStream().use { readLimited(it) }
        } catch (e: IOException) {
            // 读取响应体时同样可能超时/断连，必须走统一分类（不能笼统算网络错误）
            return ioFailure(e)
        } catch (_: RuntimeException) {
            return FetchOutcome.Failure(FailureKind.PARSE, "读取响应失败")
        } ?: return FetchOutcome.Failure(FailureKind.PARSE, "响应体过大")
        val charset = try {
            body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        } catch (_: RuntimeException) {
            Charsets.UTF_8
        }
        return try {
            FetchOutcome.Ok(String(bytes, charset))
        } catch (_: RuntimeException) {
            FetchOutcome.Ok(String(bytes, Charsets.UTF_8))
        }
    }

    /** 读取并限制响应体大小；超过上限返回 null（按解析失败处理，不继续下载）。 */
    private fun readLimited(input: InputStream): ByteArray? {
        val initial = if (maxBodyBytes in 1..(1L shl 20)) maxBodyBytes.toInt() else 64 * 1024
        val out = ByteArrayOutputStream(initial)
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBodyBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun ioFailure(e: IOException): FetchOutcome = when (e) {
        is SocketTimeoutException -> FetchOutcome.Failure(FailureKind.TIMEOUT, "请求超时")
        is InterruptedIOException -> FetchOutcome.Failure(FailureKind.TIMEOUT, "请求超时")
        is UnknownHostException, is ConnectException -> FetchOutcome.Failure(FailureKind.NETWORK, "网络错误")
        is SSLPeerUnverifiedException ->
            FetchOutcome.Failure(FailureKind.TLS_PIN, "TLS 证书固定校验失败，可能是系统代理/网络环境不兼容")
        is SSLException -> FetchOutcome.Failure(FailureKind.NETWORK, "安全连接失败")
        else -> FetchOutcome.Failure(FailureKind.NETWORK, e.javaClass.simpleName)
    }

    companion object {
        /** 与桌面版完全一致的普通 Chrome UA（不轮换、不伪造其他设备）。 */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"

        const val MAX_BODY_BYTES = 3L * 1024 * 1024

        const val MAX_REDIRECTS = 5

        private const val MAX_SEED_NAME_LEN = 32
        private const val MAX_SEED_VALUE_LEN = 128

        private val SAFE_COOKIE_VALUE = Regex("[A-Za-z0-9_\\-.=]{1,128}")

        /**
         * 生产 HTTP 客户端。
         *
         * @param pinningEnabled true 时启用 [TlsPolicy] 的证书固定（CA 家族级），
         *   防止恶意/被攻破的 CA 对检测流量做中间人；false 仅用于平台整体更换
         *   CA 时的临时逃生口（由设置项控制，默认开启）。
         */
        fun defaultClient(pinningEnabled: Boolean = true): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
            if (pinningEnabled) {
                builder.certificatePinner(TlsPolicy.certificatePinner())
            }
            return builder.build()
        }
    }
}

/**
 * 生产环境共享的抓取器（OkHttp 连接池/线程池复用，空闲时零 CPU）。
 *
 * 证书固定默认开启；设置里关闭时切换到未固定客户端。两个客户端都复用同一套
 * 超时/白名单策略，切换只影响“是否校验 CA 固定”，不降低其他安全约束。
 */
internal object PlatformHttp {
    @Volatile
    var pinningEnabled: Boolean = true

    private val pinned: HttpFetcher by lazy { HttpFetcher(HttpFetcher.defaultClient(pinningEnabled = true)) }

    private val unpinned: HttpFetcher by lazy { HttpFetcher(HttpFetcher.defaultClient(pinningEnabled = false)) }

    val fetcher: HttpFetcher
        get() = if (pinningEnabled) pinned else unpinned
}

internal val PlatformHttpFetcher: HttpFetcher
    get() = PlatformHttp.fetcher

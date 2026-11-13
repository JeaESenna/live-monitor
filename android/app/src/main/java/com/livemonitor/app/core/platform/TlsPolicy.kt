package com.livemonitor.app.core.platform

import okhttp3.CertificatePinner

/**
 * TLS 证书固定策略（防中间人 / 防被恶意 CA 签发证书后劫持检测数据）。
 *
 * 背景：Android 默认只信任系统 CA，但系统 CA 数量很多；若设备被安装/预置了恶意
 * 根证书（或被攻破的 CA），攻击者就能对 `live.bilibili.com` 等域名做中间人，
 * 伪造“开播/未开播”数据。证书固定把信任范围从“所有系统 CA”缩小到这些平台当前
 * 使用的 CA 家族（GlobalSign / DigiCert），显著提高 MITM 门槛。
 *
 * 固定方式：不固定叶子证书（平台/CDN 更换证书频繁，叶子固定会很快失效），而是固定
 * 证书链中的 **CA 中间证书 + 根证书公钥（SPKI）**。链上任一证书命中即通过：
 * - 平台正常轮换叶子证书不会误伤；
 * - 平台若整体更换 CA（例如改用 Let's Encrypt），检测会失败；设置里提供
 *   “严格证书固定”开关作为逃生口，并在文档中说明。
 *
 * 这些 pin 于 2026-11-13 通过 `openssl s_client -showcerts` 对生产域名实测获取。
 * 更新 pin 时需同时更新 [TlsPolicyTest] 与安全文档。
 */
internal object TlsPolicy {

    /**
     * GlobalSign 家族（斗鱼 / B站 / 快手实测链）：
     * 中间证书 + 根证书公钥的 SHA-256（base64）。
     */
    private val GLOBAL_SIGN_PINS = listOf(
        // GlobalSign GCC R46 OV TLS CA 2025（斗鱼 OV 证书的签发者）
        "sha256/nZ4QsWxivBcuuFkI8dXgfa0Pb2o1sjZ8hKx6h+729xw=",
        // GlobalSign GCC R46 DV TLS CA 2025（斗鱼 API / B站 API）
        "sha256/qla2PQT5GrIilpynO2s6RwuCnuoNma+quUi6nw4inYM=",
        // GlobalSign RSA OV SSL CA 2018（B站 / 快手）
        "sha256/hETpgVvaLC0bvcGG3t0cuqiHvr4XyP2MTwCiqhgRWwU=",
        // GlobalSign Root R46
        "sha256/rn+WLLnmp9v3uDP7GPqbcaiRdd+UnCMrap73yz3yu/w=",
        // GlobalSign Root R3（交叉签发链的根）
        "sha256/cGuxAXyFXFkWm61cF4HPWX8S0srS9j0aSqN0k4AP+4A=",
    )

    /**
     * DigiCert 家族（虎牙 / 抖音实测链）：
     * GeoTrust / RapidSSL 中间证书 + DigiCert 根证书。
     */
    private val DIGICERT_PINS = listOf(
        // GeoTrust G2 TLS CN RSA4096 SHA256 2022 CA1（虎牙）
        "sha256/udvVO5HfT3B83McRDgEhj2WVTnsk+Sb9NBCszsRWAqY=",
        // RapidSSL TLS RSA CA G1（抖音）
        "sha256/E3tYcwo9CiqATmKtpMLW5V+pzIq+ZoDmpXSiJlXGmTo=",
        // DigiCert Global Root G2
        "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=",
        // DigiCert Global Root CA
        "sha256/r/mIkG3eEpVdm+u/ko/cwxzOMo1bk4TyHIlByibiA5E=",
    )

    /** 每个允许请求的平台 host 对应的 CA 家族 pin 集合（精确匹配，不做通配）。 */
    private val HOST_PINS: Map<String, List<String>> = buildMap {
        for (host in listOf(
            "www.douyu.com",
            "open.douyucdn.cn",
            "live.bilibili.com",
            "api.live.bilibili.com",
            "www.bilibili.com",
            "space.bilibili.com",
            "live.kuaishou.com",
        )) {
            put(host, GLOBAL_SIGN_PINS)
        }
        for (host in listOf(
            "www.huya.com",
            "m.huya.com",
            "live.douyin.com",
        )) {
            put(host, DIGICERT_PINS)
        }
    }

    /** OkHttp 证书固定器；只对 [coveredHosts] 生效，测试用 localhost 等不受影响。 */
    fun certificatePinner(): CertificatePinner {
        val builder = CertificatePinner.Builder()
        for ((host, pins) in HOST_PINS) {
            builder.add(host, *pins.toTypedArray())
        }
        return builder.build()
    }

    fun isCoveredHost(host: String?): Boolean = host != null && HOST_PINS.containsKey(host.lowercase())

    /** 已固定 host 集合（供测试/文档核对）。 */
    val coveredHosts: Set<String> get() = HOST_PINS.keys

    /** 单个 host 的 pin（供测试核对格式与数量）。 */
    fun pinsForHost(host: String): List<String> = HOST_PINS[host.lowercase()] ?: emptyList()
}

package com.example.bad

import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.webkit.WebView
import java.io.File
import org.junit.Test

/**
 * SelfTest 夹具：故意包含禁用 API / 隐私问题 / 硬编码密钥，
 * 用于验证 check-source-hardening.ps1 的规则会触发。此文件不参与产品构建。
 */
object BadRules {

    const val FAKE_AWS_KEY = "AKIAIOSFODNN7EXAMPLE"

    fun webview(ctx: Context) {
        val wv = WebView(ctx)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = true
        wv.addJavascriptInterface(Any(), "bridge")
        wv.loadUrl("http://evil.example.com/steal")
    }

    fun dynamic() {
        val loader = DexClassLoader("/sdcard/x.jar", "/data/local/tmp", null, null)
        val proc = ProcessBuilder("sh", "-c", "id").start()
        Runtime.getRuntime().exec("su -c id")
        TelephonyManager::class.java
        val deviceId = "read-device-id"
        SmsManager.getDefault()
        val contacts = "READ_CONTACTS"
        val clip = ClipboardManager::class.java
        System.loadLibrary("evil")
        Class.forName("com.evil.Payload")
        val raw = File("/sdcard/a").readText()
    }

    fun settings(ctx: Context) {
        val prefs = ctx.getSharedPreferences("p", Context.MODE_WORLD_READABLE)
        prefs.edit().putString("token", "super-secret-token").commit()
        val tm = TrustManager()
        val mode = "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    }

    fun logging(url: String, title: String) {
        AppLog.info("Check", "url=$url")
        AppLog.error("Room", "title=$title")
        android.util.Log.d("Bad", "debug")
    }

    fun notify(ctx: Context) {
        val pending = PendingIntent.getActivity(ctx, 0, Intent(), 0)
        val builder = androidx.core.app.NotificationCompat.Builder(ctx, "c")
        builder.setContentText("直播: " + "title")
        builder.setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
    }
}

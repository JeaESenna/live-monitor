package com.example.bad

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * SelfTest 夹具：v1.1.0 新增攻击面（隐式外部 Intent / 自定义提示音路径穿越）。
 * 冲突检查：本文件故意不安全，用于验证 check-source-hardening.ps1 规则触发。
 */
object BadJump {

    fun openRoom(ctx: Context, roomUrl: String) {
        // 隐式 ACTION_VIEW，未 setPackage → 可被任意 App 劫持
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(roomUrl)))
    }

    fun download(ctx: Context) {
        // market:// 隐式跳转，未 setPackage
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=air.tv.douyu.android")))
    }

    fun soundPath(ctx: Context) {
        // 用外部输入拼接文件路径（路径穿越/任意文件读取）
        val f = java.io.File(ctx.getStringExtra("sound_path") ?: "")
        val f2 = java.io.File(ctx.filesDir, "../../" + ctx.getStringExtra("name"))
        f.readBytes(); f2.readBytes()
    }

    fun playCustomSound(ctx: Context) {
        // 自定义提示音直接使用外部输入路径
        val target = java.io.File(ctx.getStringExtra("customSound_path") ?: "")
        android.media.MediaPlayer().setDataSource(target.absolutePath)
    }
}

package com.livemonitor.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.livemonitor.app.core.jump.RoomJumpIntent

/**
 * SelfTest 夹具：安全的外部跳转实现（显式 setPackage、固定 https 房间链接）。
 * v1.1.1 已下架“下载 App”功能，本夹具不再包含 market/官网下载跳转。
 */
class RoomJumpActivity {

    fun jump(ctx: Context, roomUrl: String, installed: Boolean) {
        if (installed) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(roomUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setPackage("air.tv.douyu.android")
            ctx.startActivity(intent)
        }
    }

    fun openWeb(ctx: Context, roomUrl: String) {
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(roomUrl))
        val handler = ctx.packageManager.resolveActivity(viewIntent, 0)?.activityInfo?.packageName
        if (handler != null) {
            ctx.startActivity(Intent(viewIntent).setPackage(handler).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun fromNotification(ctx: Context, intent: Intent?) {
        val roomKey = RoomJumpIntent.roomKey(intent) ?: return
        ctx.startActivity(RoomJumpIntent.create(ctx, roomKey))
    }
}

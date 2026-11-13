package com.livemonitor.app.core.jump

import android.content.Context
import android.content.Intent

/** SelfTest 夹具：跳转 Intent 契约（只传本地房间 key）。 */
object RoomJumpIntent {
    const val EXTRA_ROOM_KEY = "com.livemonitor.app.extra.ROOM_KEY"

    fun create(context: Context, roomKey: String): Intent =
        Intent(context, RoomJumpActivity::class.java).putExtra(EXTRA_ROOM_KEY, roomKey)

    fun roomKey(intent: Intent?): String? = intent?.getStringExtra(EXTRA_ROOM_KEY)
}

private class RoomJumpActivity

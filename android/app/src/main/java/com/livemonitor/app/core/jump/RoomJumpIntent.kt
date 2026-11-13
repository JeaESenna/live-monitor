package com.livemonitor.app.core.jump

import android.content.Context
import android.content.Intent
import com.livemonitor.app.ui.RoomJumpActivity

/**
 * “进入直播间” Activity 的 Intent 契约（UI 与服务/通知共用）。
 *
 * 安全约束：Intent 只携带本机已保存房间的 key，不携带任何外部 URL；
 * Activity 内部从本地仓储重新查询房间并校验，避免被外部构造的 Intent 注入任意地址。
 */
object RoomJumpIntent {

    const val EXTRA_ROOM_KEY = "com.livemonitor.app.extra.ROOM_KEY"

    /** 拼装跳转 Activity 的显式 Intent（调用方负责 startActivity）。 */
    fun create(context: Context, roomKey: String): Intent =
        Intent(context, RoomJumpActivity::class.java)
            .putExtra(EXTRA_ROOM_KEY, roomKey)

    fun roomKey(intent: Intent?): String? = intent?.getStringExtra(EXTRA_ROOM_KEY)
}

package com.example.bad

import android.app.Activity
import android.os.Bundle

/** SelfTest 夹具：RoomJumpActivity 读取外部 URL/data（不安全）。 */
class RoomJumpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.data?.toString() ?: intent.getStringExtra("url") ?: ""
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}

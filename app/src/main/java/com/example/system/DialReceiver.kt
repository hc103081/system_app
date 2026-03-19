package com.example.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DialReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 衛語句：確保是暗碼廣播
        if (intent.action != "android.provider.Telephony.SECRET_CODE") return

        // 啟動 MainActivity
        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(appIntent)
    }
}

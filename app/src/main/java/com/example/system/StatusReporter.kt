package com.example.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class StatusReporter(private val context: Context) {

    private val client = OkHttpClient()
    private val statusUrl = "http://hc103081.servehttp.com:8080/status" 
    
    // 指令回調：(指令, 數值) -> Unit
    var onCommandReceived: ((String, String) -> Unit)? = null

    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UnknownDevice"
    }

    private val deviceName = Build.MODEL
    private val startTime = System.currentTimeMillis()

    /**
     * 收集狀態並發送至伺服器
     * @param uploadCount 目前成功上傳的圖片數量
     * @param interval 當前拍照頻率 (秒)
     * @param isStopping 是否正在停止服務 (最後遺言)
     */
    fun sendHeartbeat(uploadCount: Int, interval: Int = 60, isStopping: Boolean = false) {
        val batteryLevel = getBatteryLevel()
        val uptimeMinutes = (System.currentTimeMillis() - startTime) / (1000 * 60)

        val jsonPayload = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("batteryLevel", batteryLevel)
            put("uploadCount", uploadCount)
            put("interval", interval)
            put("uptime", "${uptimeMinutes} 分鐘")
            put("isStopping", isStopping)
        }.toString()

        postJsonToServer(jsonPayload)
    }

    private fun getBatteryLevel(): Int {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)
        
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        
        if (level == -1 || scale == -1) return 0
        
        return (level * 100 / scale.toFloat()).toInt()
    }

    private fun postJsonToServer(jsonString: String) {
        val body = jsonString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(statusUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("StatusReporter", "❌ 心跳包發送失敗: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        Log.e("StatusReporter", "⚠️ 心跳包被拒絕，狀態碼: ${it.code}")
                        return
                    }
                    
                    val responseData = it.body?.string()
                    Log.d("StatusReporter", "💓 心跳包發送成功，回應: $responseData")
                    
                    // 解析 Server 回傳的指令 (如果有)
                    if (!responseData.isNullOrBlank()) {
                        try {
                            val json = JSONObject(responseData)
                            if (json.has("command")) {
                                val command = json.getString("command")
                                val value = json.optString("value", "")
                                onCommandReceived?.invoke(command, value)
                            }
                        } catch (e: Exception) {
                            // 非 JSON 格式或解析失敗，忽略
                        }
                    }
                }
            }
        })
    }
}

package com.example.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
     * 收集狀態並發送至伺服器 (非同步)
     */
    fun sendHeartbeat(uploadCount: Int, currentInterval: Int = 60, isStopping: Boolean = false) {
        val jsonPayload = createPayload(uploadCount, currentInterval, isStopping)
        postJsonToServer(jsonPayload)
    }

    /**
     * 💥 修正 3：同步發送最後遺言 (確保在進程關閉前送達)
     */
    fun sendHeartbeatSync(uploadCount: Int, currentInterval: Int, isStopping: Boolean) {
        val jsonPayload = createPayload(uploadCount, currentInterval, isStopping)
        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url(statusUrl).post(body).build()

        try {
            // 使用 execute() 而不是 enqueue()，強制等待結果
            client.newCall(request).execute().use { response ->
                Log.w("StatusReporter", "💀 遺言已確實抵達伺服器，狀態碼: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("StatusReporter", "💀 遺言發送失敗", e)
        }
    }

    private fun createPayload(uploadCount: Int, interval: Int, isStopping: Boolean): String {
        val batteryLevel = getBatteryLevel()
        val uptimeMinutes = (System.currentTimeMillis() - startTime) / (1000 * 60)

        return JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("batteryLevel", batteryLevel)
            put("uploadCount", uploadCount)
            put("photoInterval", interval) // 伺服器依賴此 Key
            put("uptime", "${uptimeMinutes} 分鐘")
            put("isStopping", isStopping)
        }.toString()
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
                    if (!it.isSuccessful) return
                    
                    val responseData = it.body?.string() ?: return
                    Log.d("StatusReporter", "💓 心跳包發送成功")
                    
                    try {
                        val json = JSONObject(responseData)
                        if (json.has("command")) {
                            val command = json.getString("command")
                            val value = json.optString("value", "")
                            Log.w("StatusReporter", "📥 收到伺服器指令: $command ($value)")
                            
                            // 💥 修正 2：強制在主執行緒執行指令，確保 Service 狀態正確切換
                            Handler(Looper.getMainLooper()).post {
                                onCommandReceived?.invoke(command, value)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("StatusReporter", "❌ 解析伺服器回應失敗", e)
                    }
                    // 確保 lambda 返回 Unit，避免 try/if 被誤判為表達式
                    @Suppress("UNUSED_EXPRESSION")
                    Unit
                }
            }
        })
    }
}

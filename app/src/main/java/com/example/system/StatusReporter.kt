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
import org.json.JSONException
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
    fun sendHeartbeat(
        uploadCount: Int, 
        currentInterval: Int = 60, 
        isStopping: Boolean = false, 
        isRestarting: Boolean = false,
        isCameraEnabled: Boolean = true
    ) {
        val jsonPayload = createPayload(uploadCount, currentInterval, isStopping, isRestarting, isCameraEnabled)
        postJsonToServer(jsonPayload)
    }

    /**
     * 💥 同步發送最後遺言 (確保在進程關閉前送達)
     */
    fun sendHeartbeatSync(
        uploadCount: Int, 
        currentInterval: Int, 
        isStopping: Boolean = false, 
        isRestarting: Boolean = false,
        isCameraEnabled: Boolean = true
    ) {
        val jsonPayload = createPayload(uploadCount, currentInterval, isStopping, isRestarting, isCameraEnabled)
        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url(statusUrl).post(body).build()

        try {
            // 使用 execute() 而不是 enqueue()，強制等待結果
            client.newCall(request).execute().use { response ->
                Log.w("StatusReporter", "💀 遺言已確實抵達伺服器, 狀態碼: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("StatusReporter", "💀 遺言發送失敗", e)
        }
    }

    private fun createPayload(
        uploadCount: Int, 
        interval: Int, 
        isStopping: Boolean, 
        isRestarting: Boolean,
        isCameraEnabled: Boolean
    ): String {
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
            put("isRestarting", isRestarting) // 🌟 告訴 Web 我們要重啟了
            put("isCameraEnabled", isCameraEnabled) // 🌟 告訴 Web 目前相機開關狀態
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
                // 💥 使用 response.use 保證記憶體與連線絕對會被釋放，不會卡死
                response.use { res ->
                    if (!res.isSuccessful) {
                        Log.e("StatusReporter", "⚠️ HTTP 狀態錯誤: ${res.code}")
                        return
                    }

                    // 安全取得字串
                    val responseData = res.body?.string()
                    if (responseData.isNullOrEmpty()) {
                        Log.e("StatusReporter", "⚠️ 收到空的 Response")
                        return
                    }

                    Log.d("StatusReporter", "💓 伺服器回傳原始內容: $responseData")

                    try {
                        val json = JSONObject(responseData)
                        if (json.has("command")) {
                            val command = json.getString("command")
                            val value = json.optString("value", "")
                            
                            Log.w("StatusReporter", "📥 成功抓取指令: $command (參數: $value)")

                            // 拋回主執行緒執行
                            Handler(Looper.getMainLooper()).post {
                                if (onCommandReceived != null) {
                                    onCommandReceived?.invoke(command, value)
                                    Log.d("StatusReporter", "✅ 指令已成功傳遞給 MonitorService")
                                } else {
                                    Log.e("StatusReporter", "❌ 嚴重錯誤：MonitorService 忘記綁定 onCommandReceived")
                                }
                            }
                        } else {
                            Log.d("StatusReporter", "💤 此次心跳正常，無下達指令")
                        }
                    } catch (e: JSONException) {
                        Log.e("StatusReporter", "❌ 解析 JSON 失敗: $responseData", e)
                    } catch (e: Exception) {
                        Log.e("StatusReporter", "❌ 發生未知的嚴重錯誤", e)
                    }
                    @Suppress("UNUSED_EXPRESSION")
                    Unit
                }
            }
        })
    }
}

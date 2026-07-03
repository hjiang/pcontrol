package com.pcontrol.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusUsage: TextView
    private lateinit var statusAccessibility: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var statusNotifications: TextView
    private lateinit var statusBattery: TextView
    private lateinit var statusServer: TextView

    private lateinit var btnUsage: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnNotifications: Button
    private lateinit var btnBattery: Button
    private lateinit var btnServer: Button
    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusUsage = findViewById(R.id.status_usage)
        statusAccessibility = findViewById(R.id.status_accessibility)
        statusOverlay = findViewById(R.id.status_overlay)
        statusNotifications = findViewById(R.id.status_notifications)
        statusBattery = findViewById(R.id.status_battery)
        statusServer = findViewById(R.id.status_server)

        btnUsage = findViewById(R.id.btn_usage)
        btnAccessibility = findViewById(R.id.btn_accessibility)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnNotifications = findViewById(R.id.btn_notifications)
        btnBattery = findViewById(R.id.btn_battery)
        btnServer = findViewById(R.id.btn_server)
        btnStart = findViewById(R.id.btn_start)

        btnUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
        btnNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }
        }
        btnBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        btnServer.setOnClickListener {
            showServerConfigDialog()
        }

        btnStart.setOnClickListener {
            if (allPermissionsGranted()) {
                startTrackerService()
            } else {
                Toast.makeText(this, "Grant all permissions first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val usageOk = hasUsageStatsPermission()
        val accessibilityOk = isAccessibilityServiceEnabled()
        val overlayOk = hasOverlayPermission()
        val notificationsOk = hasNotificationPermission()
        val batteryOk = isBatteryOptimizationIgnored()
        val serverOk = isServerConfigured()

        statusUsage.text = if (usageOk) "\u2705 Usage access" else "\u274C Usage access"
        statusAccessibility.text = if (accessibilityOk) "\u2705 Accessibility service" else "\u274C Accessibility service"
        statusOverlay.text = if (overlayOk) "\u2705 Draw over other apps" else "\u274C Draw over other apps"
        statusNotifications.text = if (notificationsOk) "\u2705 Notifications" else "\u274C Notifications"
        statusBattery.text = if (batteryOk) "\u2705 Battery optimization off" else "\u274C Battery optimization"
        statusServer.text = if (serverOk) "\u2705 Server configured" else "\u274C Server URL + token"

        val allOk = allPermissionsGranted()
        btnStart.isEnabled = allOk
        btnStart.text = if (allOk) "Start monitoring" else "Grant all permissions above"
    }

    private fun allPermissionsGranted(): Boolean {
        return hasUsageStatsPermission() &&
            isAccessibilityServiceEnabled() &&
            hasOverlayPermission() &&
            hasNotificationPermission() &&
            isBatteryOptimizationIgnored() &&
            isServerConfigured()
    }

    private fun hasUsageStatsPermission(): Boolean {
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.unsafeCheckOp(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
            return mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            return false
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/$packageName.BrowserAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabledServices.split(':').any { it.equals(service, ignoreCase = true) }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isServerConfigured(): Boolean {
        val prefs = getSharedPreferences("pcontrol", MODE_PRIVATE)
        return prefs.getString("server_url", "")?.isNotEmpty() == true &&
            prefs.getString("device_token", "")?.isNotEmpty() == true
    }

    private fun showServerConfigDialog() {
        val prefs = getSharedPreferences("pcontrol", MODE_PRIVATE)
        val currentUrl = prefs.getString("server_url", "") ?: ""
        val currentToken = prefs.getString("device_token", "") ?: ""

        val inputUrl = android.widget.EditText(this).apply {
            setText(currentUrl)
            hint = "https://pcontrol.example.com"
        }
        val inputToken = android.widget.EditText(this).apply {
            setText(currentToken)
            hint = "Device token from server"
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.widget.TextView(this@MainActivity).apply { text = "Server URL" })
            addView(inputUrl)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "Device Token"
                setPadding(0, 32, 0, 0)
            })
            addView(inputToken)
            setPadding(32, 16, 32, 16)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Server Configuration")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("server_url", inputUrl.text.toString().trimEnd('/'))
                    .putString("device_token", inputToken.text.toString().trim())
                    .apply()
                refreshStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startTrackerService() {
        val intent = Intent(this, TrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Tracker service started", Toast.LENGTH_SHORT).show()
    }
}

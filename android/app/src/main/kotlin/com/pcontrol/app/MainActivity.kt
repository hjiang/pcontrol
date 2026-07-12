package com.pcontrol.app

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import com.pcontrol.app.update.UpdateCoordinator
import com.pcontrol.app.update.UpdateResult
import com.pcontrol.app.update.UpdateState
import com.pcontrol.app.ui.CapabilityRenderer
import com.pcontrol.app.ui.CapabilityViews
import com.pcontrol.app.ui.CapabilityFacts
import com.pcontrol.app.ui.SetupUiState
import com.pcontrol.app.ui.UpdateUiMapper
import com.pcontrol.app.ui.UpdateUiState
import com.pcontrol.app.ui.UpdateUiStatus
import com.pcontrol.app.ui.validateServerConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        /** Stable int op id for [AppOpsManager.OP_GET_USAGE_STATS] (API 21+).
         * The public int constant was deleted from the SDK at API 37, so we
         * use the literal when targeting the legacy checkOpNoThrow path. */
        private const val LEGACY_OP_GET_USAGE_STATS = 43
    }

    private val checkUpdateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var statusUsage: TextView
    private lateinit var statusAccessibility: TextView
    private lateinit var statusNotifications: TextView
    private lateinit var statusBattery: TextView
    private lateinit var statusServer: TextView
    private lateinit var statusUpdater: TextView

    private lateinit var btnUsage: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnNotifications: Button
    private lateinit var btnBattery: Button
    private lateinit var btnServer: Button
    private lateinit var btnUpdater: Button
    private lateinit var btnCheckUpdate: Button
    private lateinit var updateProgress: ProgressBar
    private lateinit var updateStatus: TextView
    private lateinit var btnStart: Button

    private lateinit var switchAutoUpdate: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusUsage = findViewById(R.id.status_usage)
        statusAccessibility = findViewById(R.id.status_accessibility)
        statusNotifications = findViewById(R.id.status_notifications)
        statusBattery = findViewById(R.id.status_battery)
        statusServer = findViewById(R.id.status_server)
        statusUpdater = findViewById(R.id.status_updater)

        btnUsage = findViewById(R.id.btn_usage)
        btnAccessibility = findViewById(R.id.btn_accessibility)
        btnNotifications = findViewById(R.id.btn_notifications)
        btnBattery = findViewById(R.id.btn_battery)
        btnServer = findViewById(R.id.btn_server)
        btnUpdater = findViewById(R.id.btn_updater)
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        btnStart = findViewById(R.id.btn_start)

        switchAutoUpdate = findViewById(R.id.switch_auto_update)
        updateProgress = findViewById(R.id.update_progress)
        updateStatus = findViewById(R.id.update_status)

        btnUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }
        }
        btnBattery.setOnClickListener {
            if (isBatteryOptimizationIgnored()) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } else {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        btnServer.setOnClickListener {
            showServerConfigDialog()
        }

        btnUpdater.setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }

        btnCheckUpdate.setOnClickListener {
            checkForUpdates()
        }

        btnStart.setOnClickListener {
            if (allPermissionsGranted()) {
                startTrackerService()
            } else {
                Toast.makeText(this, R.string.hero_setup_needed, Toast.LENGTH_SHORT).show()
            }
        }

        // Restore auto-update toggle
        val updateState = UpdateState(this)
        switchAutoUpdate.isChecked = updateState.autoUpdateEnabled
        switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            updateState.autoUpdateEnabled = isChecked
        }

        // Render an initial setup state so the screen is populated on the
        // very first frame; onResume() re-checks after returning from Settings.
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        checkUpdateScope.cancel()
    }

    override fun onPause() {
        BrowserAccessibilityService.notifyMainActivityBackground()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        BrowserAccessibilityService.notifyMainActivityForeground()
        refreshStatus()
    }

    private fun refreshStatus() {
        // Collect system facts once, build one state, and render through a
        // replaceable seam. Accessibility overlays are owned by the bound
        // accessibility service and require no ordinary overlay permission.
        val facts = CapabilityFacts(
            usage = hasUsageStatsPermission(),
            accessibility = isAccessibilityServiceEnabled(),
            notifications = hasNotificationPermission(),
            battery = isBatteryOptimizationIgnored(),
            server = isServerConfigured(),
            updater = hasInstallPermission(),
        )
        renderSetupState(SetupUiState.build(facts))
    }

    /** Render the pure [SetupUiState] into status views. */
    private fun renderSetupState(state: SetupUiState) {
        CapabilityRenderer(this).render(
            state,
            CapabilityViews(
                usage = statusUsage,
                accessibility = statusAccessibility,
                notifications = statusNotifications,
                battery = statusBattery,
                server = statusServer,
                updater = statusUpdater,
                startBtn = btnStart,
            )
        )
    }

    private fun allPermissionsGranted(): Boolean {
        return hasUsageStatsPermission() &&
            isAccessibilityServiceEnabled() &&
            hasNotificationPermission() &&
            isBatteryOptimizationIgnored() &&
            isServerConfigured()
    }

    private fun hasUsageStatsPermission(): Boolean {
        // `unsafeCheckOpNoThrow` was added in API 29; OPSTR_GET_USAGE_STATS is
        // the String op id since API 23. On API 26–28 neither String-overload
        // nor OP_GET_USAGE_STATS (int) are in the public API at compileSdk 37,
        // so fall back to reflective call on the legacy int-id overload
        // (`checkOpNoThrow(int, int, String)`, op id 43 since API 21).
        // Catch Throwable so an absent method on older SDKs (e.g. Robolectric
        // SDK 26) cannot bring down the launcher.
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName
                )
            } else {
                val m = AppOpsManager::class.java.getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
                m.invoke(
                    appOps,
                    LEGACY_OP_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName
                ) as Int
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Throwable) {
            false
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
        // SecretPrefs lazily builds the Android Keystore master key; on Robolectric
        // or environments where the keystore is unavailable it throws. Treat any
        // initialization failure as “not configured” so the screen still renders.
        return try {
            SecretPrefs.getInstance(this).isConfigured()
        } catch (e: Throwable) {
            false
        }
    }

    private fun hasInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return packageManager.canRequestPackageInstalls()
        }
        return true
    }

    private fun checkForUpdates() {
        // In-flight guard: disable the button so only one check runs at a time.
        // The durable inline status (rather than only a Toast) preserves the
        // last result beside the controls until replaced (Stage 5).
        btnCheckUpdate.isEnabled = false
        renderUpdateState(UpdateUiMapper.checking)

        checkUpdateScope.launch {
            try {
                val coordinator = UpdateCoordinator(
                    context = applicationContext,
                    versionName = BuildConfig.VERSION_NAME
                )
                val result = coordinator.runOnce(force = true)
                val uiState = UpdateUiMapper.fromResult(result)

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        renderUpdateState(uiState)
                        // A short Snackbar-style Toast remains for confirmation;
                        // errors/action-required status stay visible inline too.
                        uiState.messageResId?.let { msgRes ->
                            Toast.makeText(this@MainActivity, msgRes, Toast.LENGTH_LONG).show()
                        }
                        refreshStatus()
                    }
                }
            } finally {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        btnCheckUpdate.isEnabled = true
                    }
                }
            }
        }
    }

    /** Stage 5 durable inline UI: progress + status text. */
    private fun renderUpdateState(state: UpdateUiState) {
        when (state.status) {
            UpdateUiStatus.IDLE -> {
                updateProgress.visibility = View.GONE
                updateStatus.visibility = View.GONE
            }
            UpdateUiStatus.CHECKING -> {
                updateProgress.visibility = View.VISIBLE
                updateStatus.text = getString(R.string.updates_checking)
                updateStatus.visibility = View.VISIBLE
            }
            UpdateUiStatus.SUCCESS,
            UpdateUiStatus.ACTION_REQUIRED,
            UpdateUiStatus.ERROR -> {
                updateProgress.visibility = View.GONE
                updateStatus.text = state.messageResId?.let { getString(it) } ?: ""
                updateStatus.visibility = if (updateStatus.text.isBlank()) View.GONE else View.VISIBLE
                if (state.status == UpdateUiStatus.ACTION_REQUIRED) {
                    // Android still requires an explicit install confirmation.
                    Toast.makeText(this, R.string.updates_install_pending, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showServerConfigDialog() {
        val prefs = SecretPrefs.getInstance(this)
        val currentUrl = prefs.getServerUrl()
        val currentToken = prefs.getDeviceToken()

        val inputUrl = android.widget.EditText(this).apply {
            setText(currentUrl)
            hint = getString(R.string.dialog_server_url_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val inputToken = android.widget.EditText(this).apply {
            setText(currentToken)
            hint = getString(R.string.dialog_server_token_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.widget.TextView(this@MainActivity).apply { text = getString(R.string.dialog_server_url_label) })
            addView(inputUrl)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = getString(R.string.dialog_server_token_label)
                setPadding(0, 32, 0, 0)
            })
            addView(inputToken)
            setPadding(32, 16, 32, 16)
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.dialog_server_title)
            .setView(layout)
            .setPositiveButton(R.string.dialog_server_save) { _, _ ->
                val result = validateServerConfiguration(
                    inputUrl.text.toString(), inputToken.text.toString()
                )
                if (result.isOk) {
                    prefs.setServerUrl(result.cleanedUrl)
                    prefs.setDeviceToken(result.cleanedToken)
                    refreshStatus()
                } else {
                    // Keep the dialog open and show field-level errors.
                    val errRes = when (result.error) {
                        com.pcontrol.app.ui.ServerConfigError.URL_BLANK -> R.string.dialog_server_url_error_blank
                        com.pcontrol.app.ui.ServerConfigError.URL_BAD_SCHEME -> R.string.dialog_server_url_error_scheme
                        com.pcontrol.app.ui.ServerConfigError.URL_NO_HOST -> R.string.dialog_server_url_error_host
                        com.pcontrol.app.ui.ServerConfigError.URL_QUERY_OR_FRAGMENT -> R.string.dialog_server_url_error_query
                        com.pcontrol.app.ui.ServerConfigError.TOKEN_BLANK -> R.string.dialog_server_token_error_blank
                        null -> R.string.dialog_server_url_error_blank
                    }
                    Toast.makeText(this, errRes, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.dialog_server_cancel, null)
            .show()
    }

    private fun startTrackerService() {
        val intent = Intent(this, TrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, R.string.toast_tracker_started, Toast.LENGTH_SHORT).show()
    }
}

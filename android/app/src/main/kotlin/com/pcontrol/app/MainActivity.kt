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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
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
import kotlinx.coroutines.CancellationException
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

    /** Test seam; production initializes this with the real coordinator in [onCreate]. */
    internal var updateRunner: (suspend () -> UpdateResult)? = null

    private lateinit var statusHero: TextView
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
    private lateinit var sectionRequired: TextView
    private lateinit var sectionServer: TextView
    private lateinit var sectionUpdates: TextView

    private lateinit var switchAutoUpdate: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusHero = findViewById(R.id.status_hero)
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

        sectionRequired = findViewById(R.id.section_required)
        sectionServer = findViewById(R.id.section_server)
        sectionUpdates = findViewById(R.id.section_updates)

        // android:accessibilityHeading is only honored on API 28+;
        // set it at runtime for API 26–27.
        ViewCompat.setAccessibilityHeading(statusHero, true)
        ViewCompat.setAccessibilityHeading(sectionRequired, true)
        ViewCompat.setAccessibilityHeading(sectionServer, true)
        ViewCompat.setAccessibilityHeading(sectionUpdates, true)

        updateRunner = {
            UpdateCoordinator(
                context = applicationContext,
                versionName = BuildConfig.VERSION_NAME,
            ).runOnce(force = true)
        }
        configureWindowInsets()

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

    /** Test seam: render the pure [SetupUiState] into status views. */
    internal fun renderSetupState(state: SetupUiState) {
        CapabilityRenderer(this).render(
            state,
            CapabilityViews(
                hero = statusHero,
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

    internal fun checkForUpdates() {
        // In-flight guard: disable the button so only one check runs at a time.
        // The durable inline status (rather than only a Toast) preserves the
        // last result beside the controls until replaced (Stage 5).
        if (!btnCheckUpdate.isEnabled) return
        btnCheckUpdate.isEnabled = false
        renderUpdateState(UpdateUiMapper.checking)

        checkUpdateScope.launch {
            val uiState = try {
                UpdateUiMapper.fromResult(checkNotNull(updateRunner) { "update runner not initialized" }.invoke())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateUiMapper.fromResult(UpdateResult.NETWORK_ERROR)
            }

            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    renderUpdateState(uiState)
                    if (uiState.status == UpdateUiStatus.SUCCESS) {
                        uiState.messageResId?.let { msgRes ->
                            Snackbar.make(findViewById(R.id.main_root), msgRes, Snackbar.LENGTH_LONG).show()
                        }
                    }
                    refreshStatus()
                    btnCheckUpdate.isEnabled = true
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

            }
        }
    }

    private fun showServerConfigDialog() {
        val prefs = SecretPrefs.getInstance(this)
        val content = layoutInflater.inflate(R.layout.dialog_server_config, null)
        val urlLayout = content.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.input_server_url_layout)
        val tokenLayout = content.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.input_server_token_layout)
        val inputUrl = content.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_server_url)
        val inputToken = content.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_server_token)
        inputUrl.setText(prefs.getServerUrl())
        inputToken.setText(prefs.getDeviceToken())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_server_title)
            .setView(content)
            .setPositiveButton(R.string.dialog_server_save, null)
            .setNegativeButton(R.string.dialog_server_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val result = validateServerConfiguration(
                    inputUrl.text?.toString().orEmpty(), inputToken.text?.toString().orEmpty()
                )
                urlLayout.error = null
                tokenLayout.error = null
                if (result.isOk) {
                    prefs.setServerUrl(result.cleanedUrl)
                    prefs.setDeviceToken(result.cleanedToken)
                    dialog.dismiss()
                    refreshStatus()
                } else {
                    when (result.error) {
                        com.pcontrol.app.ui.ServerConfigError.URL_BLANK,
                        com.pcontrol.app.ui.ServerConfigError.URL_BAD_SCHEME,
                        com.pcontrol.app.ui.ServerConfigError.URL_NO_HOST,
                        com.pcontrol.app.ui.ServerConfigError.URL_QUERY_OR_FRAGMENT -> {
                            urlLayout.error = getString(when (result.error) {
                                com.pcontrol.app.ui.ServerConfigError.URL_BLANK -> R.string.dialog_server_url_error_blank
                                com.pcontrol.app.ui.ServerConfigError.URL_BAD_SCHEME -> R.string.dialog_server_url_error_scheme
                                com.pcontrol.app.ui.ServerConfigError.URL_NO_HOST -> R.string.dialog_server_url_error_host
                                else -> R.string.dialog_server_url_error_query
                            })
                            inputUrl.requestFocus()
                        }
                        com.pcontrol.app.ui.ServerConfigError.TOKEN_BLANK -> {
                            tokenLayout.error = getString(R.string.dialog_server_token_error_blank)
                            inputToken.requestFocus()
                        }
                        null -> Unit
                    }
                }
            }
        }
        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /** Assigns exactly one owner to the top and bottom system-bar insets. */
    private fun configureWindowInsets() {
        val root = findViewById<View>(R.id.main_root)
        val appBar = findViewById<View>(R.id.app_bar)
        val content = findViewById<View>(R.id.content_scroll)
        val bottomBar = findViewById<View>(R.id.bottom_bar)

        ViewCompat.setOnApplyWindowInsetsListener(appBar) { view, insets ->
            view.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { view, insets ->
            view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            view.post { content.updatePadding(bottom = bottomBar.height) }
            insets
        }
        bottomBar.doOnLayout { content.updatePadding(bottom = it.height) }
        ViewCompat.requestApplyInsets(root)
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

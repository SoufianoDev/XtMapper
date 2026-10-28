package xtr.keymapper.activity

import xtr.keymapper.log.DiagnosticLog
import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import xtr.keymapper.BuildConfig
import xtr.keymapper.Environment
import xtr.keymapper.GameKeyMapperBridge
import xtr.keymapper.IRemoteService
import xtr.keymapper.R
import xtr.keymapper.Server
import xtr.keymapper.TouchPointer
import xtr.keymapper.databinding.TextFieldBinding
import xtr.keymapper.editor.EditorActivity
import xtr.keymapper.editor.EditorUI
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfiles
import xtr.keymapper.log.XtLogger
import xtr.keymapper.profiles.ProfileSelector
import xtr.keymapper.profiles.ProfilesApps
import xtr.keymapper.server.RemoteServiceHelper

class MainActivity : AppCompatActivity() {

    var pointerOverlay: TouchPointer? = null
    private var selectedProfileName: String? = null
    private var composeServiceActive = false
    private var isServiceBound = false
    private var displaySelector: DisplaySelector? = null
    private var startedFromShell: Boolean? = null
    private var trustedShellInitialization = false
    private var shellInitStartupPending = false

    companion object {
        const val SHELL_INIT = "shell"
        private const val KEY_TRUSTED_SHELL_INIT = "trusted_shell_initialization"
        private const val SERVER_CRASH_DIALOG_TAG = "server_crash_dialog"
        private const val SERVER_CRASH_DISMISSED_REQUEST = "server_crash_dismissed"
        private const val MAX_DIAGNOSTIC_MESSAGE_CHARS = 16 * 1024

        init {
            // Set settings before the main shell can be created
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(10)
            )
        }

        fun checkOverlayPermission(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.info("MainActivity", "activity_created", "savedInstanceState" to (savedInstanceState != null), "launchAction" to intent.action, "sdkInt" to Build.VERSION.SDK_INT)
        supportFragmentManager.setFragmentResultListener(
            SERVER_CRASH_DISMISSED_REQUEST,
            this
        ) { _, _ ->
            if (startedFromShell == false) handleActivationFlow(KeymapConfig(this))
        }
        // The environment signal is separate from the legacy "data" extra.
        if (intent.hasExtra(Environment.INTENT_EXTRA)) {
            Environment.launchSignal = intent.getBooleanExtra(Environment.INTENT_EXTRA, false)
            XtLogger.info("MainActivity", "launch signal applied: environment=${Environment.name}")
        }

        val launchData = intent.getStringExtra("data")
        trustedShellInitialization = if (savedInstanceState != null) {
            savedInstanceState.getBoolean(KEY_TRUSTED_SHELL_INIT, false)
        } else {
            launchData == SHELL_INIT && isLaunchedByAndroidShell()
        }
        startedFromShell = trustedShellInitialization

        // Consume the crash payload before showing UI: an Activity recreation must not
        // replay it from the original Intent. DialogFragment restores an undismissed dialog.
        intent.removeExtra("data")
        if (savedInstanceState == null && launchData != null && launchData != SHELL_INIT) {
            showServerCrashDialog(launchData)
        }

        GameKeyMapperBridge.installGameKeyMapperContent(this)

        val keymapConfig = KeymapConfig(this)
        val profiles = KeymapProfiles(this)
        val isShellInit = isShellInitialization()
        shellInitStartupPending = isShellInit
        selectedProfileName = profiles.getStartupProfileName(isShellInit)
        TouchPointer.currentSelectedProfile = selectedProfileName
        DiagnosticLog.info("MainActivity", "startup_configuration_resolved", "profile" to selectedProfileName, "trustedShellInit" to isShellInit, "inputMode" to if (keymapConfig.isShizukuMode) "shizuku" else "root", "pointerMode" to keymapConfig.pointerMode, "autoProfilingEnabled" to !keymapConfig.disableAutoProfiling, "overlayPermission" to Settings.canDrawOverlays(this), "displayCount" to getSystemService(DisplayManager::class.java).displays.size, "fileSinkAvailable" to XtLogger.isFileSinkAvailable)

        // The persisted mode reset is allowed only for a shell launch whose caller UID was
        // verified by Android; caller-controlled extras alone are never sufficient.
        if (isShellInit && keymapConfig.isShizukuMode) {
            keymapConfig.useShizuku = false
            keymapConfig.inputMode = KeymapConfig.MODE_ROOT
            keymapConfig.applySharedPrefs()
        }

        RemoteServiceHelper.useShizuku = keymapConfig.isShizukuMode
        Server.setupServer(this, mCallback)

        if (startedFromShell == false &&
            supportFragmentManager.findFragmentByTag(SERVER_CRASH_DIALOG_TAG) == null
        ) {
            handleActivationFlow(keymapConfig)
        }

        displaySelector = DisplaySelector(this).register { startPointer() }
        RemoteServiceHelper.runIfActive(this) {
            runOnUiThread { composeServiceActive = true }
        }

        if (isShellInit) {
            startPointer()
        }
    }

    private fun handleActivationFlow(keymapConfig: KeymapConfig) {
        DiagnosticLog.info("MainActivity", "activation_flow_started", "inputMode" to if (keymapConfig.isShizukuMode) "shizuku" else "root", "trustedShellInit" to trustedShellInitialization)
        if (!RemoteServiceHelper.useShizuku) {
            Shell.getShell {
                // Ask user to enable shizuku if shizuku app detected
                if (Shizuku.pingBinder() || packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") != null) {
                    showAlertDialog(R.string.detected_shizuku, R.string.use_shizuku_for_activation, { _, _ ->
                        RemoteServiceHelper.useShizuku = true
                        keymapConfig.useShizuku = true
                        keymapConfig.inputMode = KeymapConfig.MODE_SHIZUKU
                        keymapConfig.applySharedPrefs()
                        alertShizukuNotAuthorized()
                    }, R.string.ok)
                } else if (Shell.isAppGrantedRoot() == false) {
                    alertRootAccessNotFound()
                }
            }
        } else {
            when {
                !isShizukuInstalled() -> alertShizukuNotInstalled()
                !Shizuku.pingBinder() -> alertShizukuNotRunning()
                Shizuku.checkSelfPermission() != PERMISSION_GRANTED -> alertShizukuNotAuthorized()
            }
        }
    }

    /**
     * Intent extras are caller-controlled. Only trust the shell-init marker when Android
     * exposes the actual launching UID, rather than treating "data=shell" as authentication.
     * Before API 34 Android exposes no supported caller-UID API for this launch flow, so the
     * legacy marker remains a harmless hint and cannot trigger privileged startup behavior.
     */
    private fun isLaunchedByAndroidShell(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            launchedFromUid == Process.SHELL_UID
    }

    private fun isShellInitialization(): Boolean = trustedShellInitialization

    private fun showServerCrashDialog(message: String) {
        if (supportFragmentManager.isStateSaved ||
            supportFragmentManager.findFragmentByTag(SERVER_CRASH_DIALOG_TAG) != null
        ) return
        val safeMessage = if (message.length > MAX_DIAGNOSTIC_MESSAGE_CHARS) {
            message.take(MAX_DIAGNOSTIC_MESSAGE_CHARS) + "\n\n[Message truncated]"
        } else {
            message
        }
        ServerCrashDialogFragment.newInstance(safeMessage)
            .showNow(supportFragmentManager, SERVER_CRASH_DIALOG_TAG)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_TRUSTED_SHELL_INIT, trustedShellInitialization)
        super.onSaveInstanceState(outState)
    }

    private fun launchSettings() {
        val context: Context = this
        val remoteServiceCallback = object : RemoteServiceHelper.RootRemoteServiceCallback {
            var editorUi: EditorUI? = EditorUI(context, { editorUi = null }, null, EditorUI.START_SETTINGS, null)

            override fun onConnection(remoteService: IRemoteService?) {
                if (remoteService != null && editorUi != null) try {
                    editorUi!!.registerOnKeyEventListener(remoteService)
                } catch (e: RemoteException) {
                    XtLogger.error("MainActivity", "Failed to register remote key-event listener", e)
                    editorUi!!.unregisterOnKeyEventListener()
                }
            }
        }
        remoteServiceCallback.editorUi?.openSettings()
        RemoteServiceHelper.getInstance(context, remoteServiceCallback)
    }

    private fun activateSelectedProfile() {
        DiagnosticLog.info("MainActivity", "activate_profile_requested", "profile" to selectedProfileName, "serviceBound" to isServiceBound)
        if (selectedProfileName == null) {
            showAlertDialog(R.string.no_profile_selected, R.string.select_profile_from_below, null, R.string.ok)
        } else {
            if (isServiceBound) pointerOverlay?.launchProfile(selectedProfileName)
            else startPointer()
        }
    }

    fun startPointer() {
        DiagnosticLog.info("MainActivity", "pointer_start_entry", "profile" to selectedProfileName, "currentlyRunning" to TouchPointer.isRunning)
        val displayManager = getSystemService(DisplayManager::class.java)

        if (displayManager.displays.size > 1) {
            displaySelector?.launch()
        } else {
            startPointer(null)
        }
    }

    private fun startPointer(displayId: Int?) {
        val keymapConfig = KeymapConfig(this)
        DiagnosticLog.info("MainActivity", "pointer_start_requested", "profile" to selectedProfileName, "displayId" to displayId, "inputMode" to if (keymapConfig.isShizukuMode) "shizuku" else "root", "overlayPermission" to Settings.canDrawOverlays(this))
        if (keymapConfig.isShizukuMode) {
            when {
                !isShizukuInstalled() -> {
                    DiagnosticLog.warn("MainActivity", "pointer_start_blocked", "reason" to "shizuku_not_installed")
                    alertShizukuNotInstalled()
                    return
                }
                !Shizuku.pingBinder() -> {
                    DiagnosticLog.warn("MainActivity", "pointer_start_blocked", "reason" to "shizuku_binder_unavailable")
                    alertShizukuNotRunning()
                    return
                }
                Shizuku.checkSelfPermission() != PERMISSION_GRANTED -> {
                    DiagnosticLog.warn("MainActivity", "pointer_start_blocked", "reason" to "shizuku_permission_missing")
                    alertShizukuNotAuthorized()
                    return
                }
            }
        } else if (startedFromShell == false && Shell.isAppGrantedRoot() == false) {
            DiagnosticLog.warn("MainActivity", "pointer_start_blocked", "reason" to "root_not_granted")
            alertRootAccessAndExit()
            return
        }

        checkOverlayPermission(this)
        val profiles = KeymapProfiles(this)
        if (selectedProfileName == null) {
            selectedProfileName = profiles.getLastSelectedProfile()
        }
        TouchPointer.currentSelectedProfile = selectedProfileName
        // Start service with selected profile if display on top permission is granted
        if (Settings.canDrawOverlays(this)) {
            DiagnosticLog.info("MainActivity", "pointer_start_permissions_ok", "profile" to selectedProfileName, "displayId" to displayId, "trustedShellInit" to shellInitStartupPending)
            profiles.recordStartupProfileSelection(selectedProfileName, shellInitStartupPending)
            val intent = Intent(this, TouchPointer::class.java).apply {
                putExtra(EditorActivity.PROFILE_NAME, selectedProfileName)
                if (displayId != null) putExtra(TouchPointer.DISPLAY_ID, displayId)
            }
            isServiceBound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
            DiagnosticLog.info("MainActivity", "touch_pointer_bind_requested", "bindAccepted" to isServiceBound, "profile" to selectedProfileName, "displayId" to displayId)
            ContextCompat.startForegroundService(this, intent)
            DiagnosticLog.info("MainActivity", "touch_pointer_foreground_start_requested", "profile" to selectedProfileName, "displayId" to displayId)
            shellInitStartupPending = false
            setButtonState(false)
            requestNotificationPermission()
        } else {
            DiagnosticLog.warn("MainActivity", "pointer_start_deferred", "reason" to "overlay_permission_not_granted", "profile" to selectedProfileName)
        }
    }

    private fun setButtonState(start: Boolean) {
        composeServiceActive = !start
    }

    override fun onResume() {
        super.onResume()
        composeServiceActive = TouchPointer.isRunning
        DiagnosticLog.debug("MainActivity", "activity_resumed", "profile" to selectedProfileName, "pointerRunning" to TouchPointer.isRunning, "backendConnected" to TouchPointer.backendConnected)
        // The user may have granted All-files access in Settings just now;
        // finish the pending file-logging setup without a restart. Guarded:
        // the check is an API 30 call (crashes every resume below Android 11).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            android.os.Environment.isExternalStorageManager()
        ) XtLogger.enableFileLogging()
    }

    fun stopPointer() {
        DiagnosticLog.info("MainActivity", "pointer_stop_requested", "profile" to selectedProfileName, "pointerRunning" to TouchPointer.isRunning, "serviceBound" to isServiceBound)
        TouchPointer.activeInstance?.stopKeymapper()
        unbindTouchPointer()
        val intent = Intent(this, TouchPointer::class.java).apply {
            action = TouchPointer.ACTION_STOP
        }
        stopService(intent)
        setButtonState(true)
    }

    private fun unbindTouchPointer() {
        DiagnosticLog.debug("MainActivity", "touch_pointer_unbind_requested", "wasBound" to isServiceBound, "hasOverlay" to (pointerOverlay != null))
        pointerOverlay?.activityCallback = null
        pointerOverlay = null
        if (isServiceBound) {
            unbindService(connection)
            isServiceBound = false
        }
    }

    private fun startEditor() {
        DiagnosticLog.info("MainActivity", "editor_launch_requested", "profile" to selectedProfileName, "editorOverlay" to KeymapConfig(this).editorOverlay)
        if (selectedProfileName == null) {
            showAlertDialog(R.string.no_profile_selected, R.string.select_profile_from_below, null, R.string.ok)
        } else {
            val intent = Intent(this, EditorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfileName)
            startActivity(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(POST_NOTIFICATIONS) != PERMISSION_GRANTED)
                requestPermissions(arrayOf(POST_NOTIFICATIONS), 0)
        }
    }

    fun alertRootAccessNotFound() {
        showAlertDialog(R.string.root_not_found_title, R.string.root_not_found_message, { _, _ ->
            val launchIntent = packageManager.getLaunchIntentForPackage("me.weishu.kernelsu")
            if (launchIntent != null) {
                startActivity(launchIntent)
                System.exit(0)
            }
        }, R.string.ok)
    }

    fun alertRootAccessAndExit() {
        showAlertDialog(R.string.root_no_privileges_title, R.string.root_no_privileges_message, { _, _ ->
            finishAffinity()
            System.exit(0)
        }, R.string.ok)
    }

    private fun alertShizukuNotAuthorized() {
        if (!isShizukuInstalled()) {
            alertShizukuNotInstalled()
            return
        }
        if (Shizuku.pingBinder()) Shizuku.requestPermission(0)
        showAlertDialog(R.string.shizuku_not_authorized_title, R.string.shizuku_not_authorized_message, { _, _ -> launchShizukuAndExit() }, R.string.ok)
    }

    private fun alertShizukuNotRunning() {
        if (!isShizukuInstalled()) {
            alertShizukuNotInstalled()
            return
        }
        showAlertDialog(R.string.shizuku_not_running_title, R.string.shizuku_not_running_message, { _, _ -> launchShizukuAndExit() }, R.string.start)
    }

    private fun alertShizukuNotInstalled() {
        showAlertDialog(R.string.shizuku_not_installed_title, R.string.shizuku_not_installed_message, null, R.string.ok)
    }

    fun isShizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    private fun launchShizukuAndExit() {
        val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launchIntent != null) {
            startActivity(launchIntent)
            System.exit(0)
        } else {
            alertShizukuNotInstalled()
        }
    }

    private fun showAlertDialog(@StringRes titleId: Int, @StringRes messageId: Int, listener: DialogInterface.OnClickListener?, @StringRes ok: Int) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(titleId)
            .setMessage(messageId)
            .setPositiveButton(ok, listener)
            .setNegativeButton(R.string.cancel, null)
        runOnUiThread { builder.create().show() }
    }

    // ============ COMPOSE UI BRIDGE ============

    fun isComposeServiceActive(): Boolean = composeServiceActive || TouchPointer.isRunning

    fun selectedProfileNameForCompose(): String? = selectedProfileName

    fun toggleServiceFromCompose() {
        DiagnosticLog.info("MainActivity", "service_toggle_clicked", "activeBefore" to isComposeServiceActive(), "profile" to selectedProfileName)
        if (isComposeServiceActive()) stopPointer() else startPointer()
    }

    fun fixPermissionFromCompose() {
        val config = KeymapConfig(this)
        DiagnosticLog.info("MainActivity", "permission_repair_requested", "inputMode" to if (config.isShizukuMode) "shizuku" else "root", "overlayPermission" to Settings.canDrawOverlays(this))
        if (config.isShizukuMode && !isShizukuInstalled()) {
            alertShizukuNotInstalled()
        } else if (config.isShizukuMode && (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PERMISSION_GRANTED)) {
            if (Shizuku.pingBinder()) alertShizukuNotAuthorized()
            else alertShizukuNotRunning()
        } else if (!Settings.canDrawOverlays(this)) {
            checkOverlayPermission(this)
        }
    }

    fun toggleInputModeFromCompose() {
        val config = KeymapConfig(this)
        val previousMode = if (config.isShizukuMode) "shizuku" else "root"
        val nextMode = if (config.isShizukuMode) {
            KeymapConfig.MODE_ROOT
        } else {
            KeymapConfig.MODE_SHIZUKU
        }
        config.inputMode = nextMode
        config.useShizuku = (nextMode == KeymapConfig.MODE_SHIZUKU)
        RemoteServiceHelper.useShizuku = config.useShizuku
        config.applySharedPrefs()
        DiagnosticLog.info("MainActivity", "input_mode_changed", "previous" to previousMode, "selected" to if (config.isShizukuMode) "shizuku" else "root", "pointerWasActive" to isComposeServiceActive())
        val modeStr = if (nextMode == KeymapConfig.MODE_SHIZUKU) "Shizuku (ADB)" else "Root"
        Toast.makeText(this, getString(R.string.input_mode_changed, modeStr), Toast.LENGTH_SHORT).show()
        if (config.isShizukuMode) {
            if (!isShizukuInstalled()) alertShizukuNotInstalled()
            else if (!Shizuku.pingBinder()) alertShizukuNotRunning()
            else if (Shizuku.checkSelfPermission() != PERMISSION_GRANTED) alertShizukuNotAuthorized()
        }
        // A previous Binder can remain alive briefly even when the pointer UI is stopped.
        // Always require the next connection to use the newly selected backend.
        RemoteServiceHelper.prepareForBackendSwitch()
        if (isComposeServiceActive()) {
            stopPointer()
            startPointer()
        }
    }

    fun testInputFromCompose() {
        val config = KeymapConfig(this)
        DiagnosticLog.info("MainActivity", "input_test_requested", "inputMode" to if (config.isShizukuMode) "shizuku" else "root", "pointerRunning" to isComposeServiceActive())
        if (config.isShizukuMode) {
            when {
                !isShizukuInstalled() -> {
                    alertShizukuNotInstalled()
                    return
                }
                !Shizuku.pingBinder() -> {
                    alertShizukuNotRunning()
                    return
                }
                Shizuku.checkSelfPermission() != PERMISSION_GRANTED -> {
                    alertShizukuNotAuthorized()
                    return
                }
            }
        } else if (Shell.isAppGrantedRoot() == false) {
            Toast.makeText(this, R.string.test_input_root_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        if (!isComposeServiceActive()) {
            Toast.makeText(this, R.string.test_input_service_inactive, Toast.LENGTH_LONG).show()
            return
        }
        val modeName = if (config.isShizukuMode) "Shizuku (ADB)" else "Root"
        Toast.makeText(this, getString(R.string.test_input_ready, modeName), Toast.LENGTH_SHORT).show()
    }

    fun selectProfileFromCompose(profileName: String) {
        DiagnosticLog.info("MainActivity", "profile_selection_from_ui", "profile" to profileName)
        onProfileSelected(profileName)
    }

    fun activateSelectedProfileFromCompose() {
        activateSelectedProfile()
    }

    fun startEditorFromCompose() {
        startEditor()
    }

    fun renameProfileFromCompose(profileName: String, onComplete: Runnable?) {
        DiagnosticLog.info("MainActivity", "profile_rename_dialog_requested", "profile" to profileName)
        val context: Context = this
        val field = TextFieldBinding.inflate(layoutInflater)
        field.root.hint = getString(R.string.profile_name)
        field.editText.setText(profileName)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_alert_add_profile)
            .setView(field.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val renamed = field.editText.text.toString()
                val profiles = KeymapProfiles(context)
                profiles.renameProfile(profileName, renamed)
                if (selectedProfileName == profileName) {
                    selectedProfileName = renamed
                    TouchPointer.currentSelectedProfile = renamed
                }
                if (onComplete != null) runOnUiThread(onComplete)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun changeProfileAppFromCompose(profileName: String, onComplete: Runnable?) {
        DiagnosticLog.info("MainActivity", "profile_package_change_dialog_requested", "profile" to profileName)
        val context: Context = this
        val builder = MaterialAlertDialogBuilder(context)
        ProfilesApps.asyncLoadAppsAndThen(context, builder) { p, adapter, loadingDialog ->
            p.binding.appsGrid.adapter = adapter
            loadingDialog.dismiss()

            val dialog = builder.setView(p.appsView).show()
            p.setListener { packageName ->
                KeymapProfiles(context).setProfilePackageName(profileName, packageName)
                p.onDestroyView()
                dialog.dismiss()
                if (onComplete != null) runOnUiThread(onComplete)
            }
        }
    }

    fun deleteProfileFromCompose(profileName: String?) {
        DiagnosticLog.info("MainActivity", "profile_delete_from_ui", "profile" to profileName, "wasSelected" to (profileName == selectedProfileName))
        val profiles = KeymapProfiles(this)
        profiles.deleteProfile(profileName)
        if (profileName != null && profileName == selectedProfileName) {
            selectedProfileName = profiles.getLastSelectedProfile()
            TouchPointer.currentSelectedProfile = selectedProfileName
        }
    }

    fun stopPointerFromCompose() {
        stopPointer()
    }

    fun openHelpFromCompose() {
        DiagnosticLog.info("MainActivity", "help_open_requested")
        startActivity(Intent(this, InfoActivity::class.java))
    }

    fun createNewProfileFromCompose(onComplete: Runnable?) {
        DiagnosticLog.info("MainActivity", "profile_creation_from_ui")
        ProfileSelector.createNewProfile(this) { profileName ->
            selectedProfileName = profileName
            val profiles = KeymapProfiles(this)
            profiles.setLastSelectedProfile(profileName)
            TouchPointer.currentSelectedProfile = profileName
            if (onComplete != null) runOnUiThread(onComplete)
        }
    }

    fun openExportImportFromCompose() {
        DiagnosticLog.info("MainActivity", "profile_backup_screen_opened")
        startActivity(Intent(this, ImportExportActivity::class.java))
    }

    fun toggleOverlayFromCompose() {
        DiagnosticLog.info("MainActivity", "overlay_permission_button_clicked", "granted" to Settings.canDrawOverlays(this))
        if (!Settings.canDrawOverlays(this)) checkOverlayPermission(this)
    }

    fun openSettingsFromCompose() {
        DiagnosticLog.info("MainActivity", "settings_open_from_ui")
        launchSettings()
    }

    fun openLogsFromCompose() {
        DiagnosticLog.info("MainActivity", "logs_status_opened", "fileSinkAvailable" to XtLogger.isFileSinkAvailable, "sdkInt" to Build.VERSION.SDK_INT)
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val needsAccess = supported && !android.os.Environment.isExternalStorageManager()
        val message = when {
            !supported -> R.string.logs_unsupported
            needsAccess -> R.string.logs_access_needed
            XtLogger.isFileSinkAvailable -> R.string.logs_active
            else -> R.string.logs_unavailable
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs)
            .setMessage(getString(message) + "\n\n" + XtLogger.canonicalDirectory().absolutePath)
            .setNegativeButton(R.string.ok, null)
            .apply {
                if (needsAccess) setPositiveButton(R.string.logs_grant_access) { _, _ ->
                    openLogAccessSettings()
                }
            }
            .show()
    }

    private fun openLogAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Some OEMs only expose the general All-files access page.
            } catch (_: SecurityException) {
                // Try the general page if the app-specific page is restricted.
            }
        }
        Toast.makeText(this, R.string.logs_settings_unavailable, Toast.LENGTH_LONG).show()
    }



    override fun onPause() {
        DiagnosticLog.debug("MainActivity", "activity_paused", "profile" to selectedProfileName, "pointerRunning" to TouchPointer.isRunning)
        super.onPause()
    }

    override fun onStop() {
        DiagnosticLog.debug("MainActivity", "activity_stopped", "profile" to selectedProfileName, "pointerRunning" to TouchPointer.isRunning)
        super.onStop()
    }

    override fun onDestroy() {
        DiagnosticLog.info("MainActivity", "activity_destroyed", "profile" to selectedProfileName, "isChangingConfigurations" to isChangingConfigurations)
        super.onDestroy()
        unbindTouchPointer()
    }

    fun onProfileSelected(profileName: String) {
        DiagnosticLog.info("MainActivity", "profile_selected", "previous" to selectedProfileName, "profile" to profileName)
        selectedProfileName = profileName
        val profiles = KeymapProfiles(this)
        profiles.setLastSelectedProfile(profileName)
        TouchPointer.currentSelectedProfile = profileName
        val p = profiles.getProfile(profileName, false)
        if (!p.packageName.isNullOrEmpty()) {
            profiles.setDesignatedProfileForApp(p.packageName, profileName)
        }
    }

    private val mCallback = object : TouchPointer.MainActivityCallback {
        override fun updateCmdView1(line: String?) {
            runOnUiThread { Toast.makeText(this@MainActivity, line, Toast.LENGTH_SHORT).show() }
        }

        override fun stopPointer() {
            this@MainActivity.stopPointer()
        }
    }

    /** Defines callbacks for service binding, passed to bindService() */
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            DiagnosticLog.info("MainActivity", "touch_pointer_service_connected", "component" to className.flattenToString())
            isServiceBound = true
            // We've bound to Service, cast the IBinder and get TouchPointer instance
            val binder = service as TouchPointer.TouchPointerBinder
            pointerOverlay = binder.service.apply {
                activityCallback = this@MainActivity.mCallback
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            DiagnosticLog.warn("MainActivity", "touch_pointer_service_disconnected", "component" to arg0.flattenToString(), "profile" to selectedProfileName)
            isServiceBound = false
        }
    }
}


/**
 * Lifecycle-aware crash relay dialog. The consumed Intent extra is not needed after this
 * fragment is shown, and FragmentManager restores the dialog across configuration changes.
 */
class ServerCrashDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val message = requireArguments().getString(ARG_MESSAGE).orEmpty()
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.unverified_diagnostic_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ -> notifyDismissed() }
            .create()
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        notifyDismissed()
    }

    private fun notifyDismissed() {
        parentFragmentManager.setFragmentResult(
            DISMISSED_REQUEST,
            Bundle.EMPTY
        )
    }

    companion object {
        private const val ARG_MESSAGE = "message"
        private const val DISMISSED_REQUEST = "server_crash_dismissed"

        fun newInstance(message: String): ServerCrashDialogFragment =
            ServerCrashDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_MESSAGE, message) }
            }
    }
}

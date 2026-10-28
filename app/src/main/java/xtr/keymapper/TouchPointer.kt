package xtr.keymapper

import xtr.keymapper.log.DiagnosticLog
import android.os.SystemClock
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import xtr.keymapper.databinding.CursorBinding
import xtr.keymapper.editor.EditorActivity
import xtr.keymapper.editor.EditorService
import xtr.keymapper.editor.ShowKeymapService
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.keymap.KeymapProfiles
import xtr.keymapper.profiles.ProfileSelector
import xtr.keymapper.server.RemoteServiceHelper
import xtr.keymapper.service.KeymapperTileService

class TouchPointer : Service() {
    private val binder: IBinder = TouchPointerBinder()
    var activityCallback: MainActivityCallback? = null
    var mService: IRemoteService? = null
    var selectedProfile: String? = null
    private val mHandler = Handler(Looper.getMainLooper())
    private var activityRemoteCallback = false
    private var mWindowManager: WindowManager? = null
    private var displayId = 0
    private var displayListener: DisplayListener? = null
    private var lastForegroundPackage: String? = null


    interface MainActivityCallback {
        fun updateCmdView1(line: String?)
        fun stopPointer()
    }


    inner class TouchPointerBinder : Binder() {
        val service: TouchPointer
            get() =// Return this instance of TouchPointer so clients can call public methods
                this@TouchPointer
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        DiagnosticLog.info("TouchPointer", "start_command_received", "action" to i?.action, "flags" to flags, "startId" to startId, "profileExtra" to i?.getStringExtra(EditorActivity.PROFILE_NAME))
        if (i == null || ACTION_STOP == i.action) {
            DiagnosticLog.info("TouchPointer", "stop_command_received", "reason" to if (i == null) "null_intent" else "action_stop", "startId" to startId)
            stopKeymapper()
            // stopKeymapper may return early when the instance is already inactive.
            // This service was still started for this command, so always stop this start ID.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Launch default profile
        this.selectedProfile = i.getStringExtra(EditorActivity.PROFILE_NAME)
        if (this.selectedProfile == null) {
            this.selectedProfile = KeymapProfiles(this).getLastSelectedProfile()
        }



        val name = "Overlay"
        val channel =
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
                .setName(name).build()

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.createNotificationChannel(channel)

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, notification)
        }

        this.displayId = i.getIntExtra(DISPLAY_ID, Display.DEFAULT_DISPLAY)

        markStarting()
        currentSelectedProfile = this.selectedProfile
        activeInstance = this
        KeymapperTileService.updateTile(this)

        if (displayListener == null) displayListener = object :
            DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
            }

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == this@TouchPointer.displayId) {
                    DiagnosticLog.info("TouchPointer", "display_changed", "displayId" to displayId)
                    Point().also {
                        getSystemService(DisplayManager::class.java).getDisplay(
                            displayId
                        ).getRealSize(it)
                    }.let {
                        /* We must notify remote service
                           when device orientation changes
                           keymap will be scaled in remote service */

                        // Get new instance of remote service to avoid DeadObjectException
                        connectRemoteService(
                            KeymapProfiles(this@TouchPointer).getProfile(selectedProfile, true)
                        )
                    }
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
            }
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(
            displayListener, Handler(Looper.getMainLooper())
        )

        // Register before connecting: a missing backend may be reported synchronously,
        // and failure cleanup must be able to unregister this listener.
        val targetProfile = selectedProfile ?: KeymapProfiles(this).getLastSelectedProfile()
        val keymapProfile = KeymapProfiles(this).getProfile(targetProfile, true)
        val config = KeymapConfig(this)
        DiagnosticLog.info("TouchPointer", "pointer_startup_resolved", "profile" to targetProfile, "package" to keymapProfile.packageName, "profileEnabled" to !keymapProfile.disabled, "displayId" to displayId, "inputMode" to if (config.isShizukuMode) "shizuku" else "root", "pointerMode" to config.pointerMode, "autoProfilingEnabled" to !config.disableAutoProfiling)
        DiagnosticLog.info("TouchPointer", "display_listener_registered", "displayId" to displayId)
        connectRemoteService(keymapProfile)

        return super.onStartCommand(i, flags, startId)
    }

    private fun buildNotification(): android.app.Notification {
        val keymapConfig = KeymapConfig(this)
        val pendingIntent: PendingIntent?

        if (keymapConfig.editorOverlay) {
            val intent = Intent(this, EditorService::class.java).apply {
                putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
            }
            pendingIntent = PendingIntent.getService(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val intent = Intent(this, EditorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
            }
            pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        return builder.setOngoing(true)
            .setContentTitle("Keymapper service running")
            .setContentText("Touch to launch editor ($selectedProfile)")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = NotificationManagerCompat.from(this)
        try {
            notificationManager.notify(2, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    fun launchProfile(profileName: String?) {
        DiagnosticLog.info("TouchPointer", "profile_launch_requested", "previousProfile" to selectedProfile, "requestedProfile" to profileName, "pointerRunning" to isRunning)
        this.selectedProfile = profileName
        currentSelectedProfile = profileName
        KeymapProfiles(this).setLastSelectedProfile(profileName)
        KeymapperTileService.updateTile(this)
        updateNotification()
        val keymapProfile = KeymapProfiles(this).getProfile(selectedProfile, true)
        connectRemoteService(keymapProfile)
    }

    private fun connectRemoteService(profile: KeymapProfile) {
        // Ignore requests from instances that are no longer the active service.
        if (activeInstance !== this || !isRunning) {
            DiagnosticLog.debug("TouchPointer", "connection_request_ignored", "profile" to selectedProfile, "reason" to if (activeInstance !== this) "not_active_instance" else "not_running")
            return
        }
        val requestStartedAt = SystemClock.elapsedRealtime()
        val requestGeneration = connectionState.beginRequest()
        DiagnosticLog.info("TouchPointer", "connection_request_started", "generation" to requestGeneration, "profile" to selectedProfile, "package" to profile.packageName, "profileEnabled" to !profile.disabled, "displayId" to displayId, "inputMode" to if (KeymapConfig(this).isShizukuMode) "shizuku" else "root")
        if (!connectionState.isCurrent(requestGeneration)) return

        if (activityCallback != null) activityCallback!!.updateCmdView1("connecting to server..")
        val config = KeymapConfig(this)
        RemoteServiceHelper.useShizuku = config.isShizukuMode
        RemoteServiceHelper.getInstance(
            this
        ) { service: IRemoteService? ->
            val callbackElapsedMs = SystemClock.elapsedRealtime() - requestStartedAt
            // A late callback must not alter state after stop or a newer request.
            if (!connectionState.isCurrent(requestGeneration) ||
                activeInstance !== this@TouchPointer
            ) {
                DiagnosticLog.warn("TouchPointer", "stale_connection_callback_ignored", "generation" to requestGeneration, "activeInstance" to (activeInstance === this@TouchPointer), "elapsedMs" to callbackElapsedMs)
                return@getInstance
            }
            if (service == null) {
                DiagnosticLog.warn("TouchPointer", "connection_callback_failed", "generation" to requestGeneration, "backend" to if (RemoteServiceHelper.useShizuku) "shizuku" else "root", "elapsedMs" to callbackElapsedMs, "reason" to "null_service")
                failConnection(
                    requestGeneration,
                    "Failed to connect to server (root/shizuku not available)",
                    null
                )
                return@getInstance
            }

            mService = service
            DiagnosticLog.info("TouchPointer", "remote_service_available", "generation" to requestGeneration, "elapsedMs" to callbackElapsedMs, "binderAlive" to runCatching { service.asBinder().isBinderAlive }.getOrDefault(false))
            try {
                val keymapConfig = KeymapConfig(this)
                val displayManager = getSystemService(DisplayManager::class.java)
                val display = displayManager.getDisplay(displayId)
                    ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                val size = Point()
                display?.getRealSize(size)
                mWindowManager =
                    this.displayContext?.getSystemService(WindowManager::class.java)

                if (!activityRemoteCallback && !keymapConfig.disableAutoProfiling) {
                    DiagnosticLog.info("TouchPointer", "foreground_observer_register_requested", "generation" to requestGeneration, "autoProfilingEnabled" to true)
                    service.registerActivityObserver(mActivityObserverCallback)
                    activityRemoteCallback = true
                    DiagnosticLog.info("TouchPointer", "foreground_observer_registered", "generation" to requestGeneration)
                } else {
                    DiagnosticLog.debug("TouchPointer", "foreground_observer_registration_skipped", "alreadyRegistered" to activityRemoteCallback, "autoProfilingEnabled" to !keymapConfig.disableAutoProfiling)
                }
                if (!connectionState.isCurrent(requestGeneration) ||
                    activeInstance !== this@TouchPointer
                ) {
                    return@getInstance
                }
                if (!profile.disabled) {
                    DiagnosticLog.info("TouchPointer", "remote_start_server_call", "generation" to requestGeneration, "profile" to selectedProfile, "package" to profile.packageName, "screenWidth" to size.x, "screenHeight" to size.y, "displayId" to displayId, "pointerMode" to keymapConfig.pointerMode, "note" to "startServer_initialization_is_asynchronous")
                    service.startServer(profile, keymapConfig, mCallback, size.x, size.y, displayId)
                    DiagnosticLog.info("TouchPointer", "remote_start_server_call_returned", "generation" to requestGeneration, "elapsedMs" to (SystemClock.elapsedRealtime() - requestStartedAt), "readiness" to "not_confirmed_by_async_method_return")
                } else {
                    DiagnosticLog.warn("TouchPointer", "remote_start_server_skipped", "generation" to requestGeneration, "profile" to selectedProfile, "reason" to "profile_disabled")
                }
                if (keymapConfig.showControls) {
                    DiagnosticLog.debug("TouchPointer", "show_controls_start_requested", "profile" to selectedProfile)
                    ShowKeymapService.start(this, selectedProfile)
                }
                if (!connectionState.markConnected(requestGeneration)) {
                    DiagnosticLog.warn("TouchPointer", "connected_transition_rejected", "generation" to requestGeneration, "elapsedMs" to (SystemClock.elapsedRealtime() - requestStartedAt))
                    return@getInstance
                }
                DiagnosticLog.info("TouchPointer", "connection_state_connected", "generation" to requestGeneration, "profile" to selectedProfile, "elapsedMs" to (SystemClock.elapsedRealtime() - requestStartedAt), "serverReadiness" to if (profile.disabled) "not_started_profile_disabled" else "async_start_requested_not_confirmed")
                KeymapperTileService.updateTile(this@TouchPointer)
            } catch (e: Exception) {
                DiagnosticLog.error("TouchPointer", "connection_setup_exception", e, "generation" to requestGeneration, "profile" to selectedProfile, "elapsedMs" to (SystemClock.elapsedRealtime() - requestStartedAt))
                failConnection(requestGeneration, e.toString(), e)
            }
        }
    }

    private fun failConnection(
        requestGeneration: Long,
        message: String,
        error: Exception?
    ) {
        // A failure from an older request must not tear down a newer run.
        if (activeInstance !== this || !connectionState.fail(requestGeneration)) {
            DiagnosticLog.warn("TouchPointer", "connection_failure_ignored", "generation" to requestGeneration, "activeInstance" to (activeInstance === this), "reason" to "stale_or_already_stopped")
            return
        }
        DiagnosticLog.error("TouchPointer", "connection_failed", error, "generation" to requestGeneration, "profile" to selectedProfile, "message" to message)
        isRunning = false
        if (error == null) {
            Log.w("TouchPointer", message)
        } else {
            Log.e("startServer", message, error)
        }
        if (activityCallback != null) {
            activityCallback!!.updateCmdView1(message)
        } else {
            mHandler.post {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        stopKeymapper()
    }

    fun stopKeymapper() {
        // Do not let an obsolete service instance stop a newer run.
        if (activeInstance !== this) {
            DiagnosticLog.debug("TouchPointer", "stop_ignored_for_inactive_instance", "profile" to selectedProfile)
            return
        }
        DiagnosticLog.info("TouchPointer", "stop_started", "profile" to selectedProfile, "backendConnected" to backendConnected, "hasRemoteService" to (mService != null), "observerRegistered" to activityRemoteCallback)
        connectionState.stop()
        isRunning = false
        activeInstance = null

        displayListener?.let {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(it)
        }
        displayListener = null

        if (mService != null) try {
            if (activityRemoteCallback) {
                mService!!.unregisterActivityObserver(mActivityObserverCallback)
                activityRemoteCallback = false
                DiagnosticLog.info("TouchPointer", "foreground_observer_unregistered")
            }
            stopServer()
            DiagnosticLog.info("TouchPointer", "remote_stop_server_requested")
        } catch (e: Exception) {
            DiagnosticLog.error("TouchPointer", "remote_cleanup_failed", e, "profile" to selectedProfile)
            Log.e("stopServer", e.toString(), e)
        }
        mService = null

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}

        activityCallback?.stopPointer()
        KeymapperTileService.updateTile(this)
        DiagnosticLog.info("TouchPointer", "stop_completed", "profile" to selectedProfile)
        stopSelf()
    }

    override fun onDestroy() {
        DiagnosticLog.info("TouchPointer", "service_destroyed", "profile" to selectedProfile, "backendConnected" to backendConnected)
        stopKeymapper()
        activityCallback = null
        super.onDestroy()
    }

    @Throws(RemoteException::class)
    private fun stopServer() {
        DiagnosticLog.debug("TouchPointer", "remote_stop_server_call", "profile" to selectedProfile)
        stopService(Intent(this, ShowKeymapService::class.java))
        mService!!.stopServer()
    }

    /**
     * This implementation is used to receive callbacks from the remote
     * service.
     */
    val mCallback: IRemoteServiceCallback = object : IRemoteServiceCallback.Stub() {
        private var cursorView: View? = null

        override fun launchEditor() {
            DiagnosticLog.info("TouchPointer", "remote_callback_launch_editor", "profile" to selectedProfile)
            val intent = Intent(this@TouchPointer, EditorService::class.java)
            intent.putExtra(EditorActivity.PROFILE_NAME, selectedProfile)
            startService(intent)
        }

        override fun alertMouseAimActivated() {
            // Notifying user that shooting mode was activated
            mHandler.post {
                Toast.makeText(
                    this@TouchPointer, R.string.mouse_aim_activated, Toast.LENGTH_SHORT
                ).show()
            }
        }

        override fun requestKeymapProfile(): KeymapProfile {
            return KeymapProfiles(this@TouchPointer).getProfile(selectedProfile, true)
        }

        override fun requestKeymapConfig(): KeymapConfig {
            return KeymapConfig(this@TouchPointer)
        }

        @UiThread
        override fun switchProfiles() {
            DiagnosticLog.info("TouchPointer", "profile_switch_requested_by_input")
            mHandler.post {
                val keymapProfiles = KeymapProfiles(this@TouchPointer)
                val keymapProfile = keymapProfiles.getProfile(selectedProfile, false)
                val application = keymapProfile.packageName

                if (keymapProfiles.getAllProfilesForApp(application).size == 1) {
                    DiagnosticLog.info("TouchPointer", "profile_switch_unavailable", "package" to application, "reason" to "single_profile")
                    Toast.makeText(
                        this@TouchPointer,
                        "Only one profile saved for $application",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@post
                }
                ProfileSelector.select(
                    this@TouchPointer,
                    { profile: String? ->
                        DiagnosticLog.info("TouchPointer", "profile_switch_selected", "previousProfile" to this@TouchPointer.selectedProfile, "profile" to profile, "package" to application)
                        this@TouchPointer.selectedProfile = profile
                        // Reloading profile
                        connectRemoteService(keymapProfiles.getProfile(profile, true))
                    },
                    application
                )
            }
        }

        override fun enablePointer() {
            val keymapConfig = requestKeymapConfig()
            DiagnosticLog.info("TouchPointer", "cursor_enable_requested", "pointerMode" to keymapConfig.pointerMode, "cursorViewExists" to (cursorView != null))

            // Set combined pointer mode automatically for 14 QPR3 and above
            if (keymapConfig.pointerMode == KeymapConfig.POINTER_OVERLAY) {
                keymapConfig.pointerMode = KeymapConfig.POINTER_COMBINED
                keymapConfig.applySharedPrefs()
                activityCallback?.stopPointer()
                try {
                    stopServer()
                } catch (_: RemoteException) {
                }
                return
            }

            mHandler.post {
                if (cursorView == null) {
                    cursorView = CursorBinding.inflate(
                        LayoutInflater.from(
                            ContextThemeWrapper(displayContext, R.style.Theme_XtMapper)
                        )
                    ).getRoot()

                    val mParams =
                        Utils.getPointerLayoutParams(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)

                    mWindowManager!!.addView(cursorView, mParams)
                }
            }
        }

        override fun disablePointer() {
            DiagnosticLog.debug("TouchPointer", "cursor_disable_requested", "cursorViewExists" to (cursorView != null))
            mHandler.post {
                if (cursorView != null) {
                    mWindowManager!!.removeView(cursorView)
                    cursorView = null
                }
            }
        }

        override fun setCursorX(x: Int) {
            mHandler.post {
                if (cursorView != null) cursorView!!.x = x.toFloat()
            }
        }

        override fun setCursorY(y: Int) {
            mHandler.post {
                if (cursorView != null) cursorView!!.y = y.toFloat()
            }
        }

        override fun alertKeyboardBlocked(blocked: Boolean) {
            DiagnosticLog.info("TouchPointer", "keyboard_block_state_callback", "blocked" to blocked)
            mHandler.post {
                Toast.makeText(
                    this@TouchPointer,
                    if (blocked) R.string.keyboard_blocked else R.string.keyboard_unblocked,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private val displayContext: Context?
        get() {
            val displayManager =
                getSystemService(DisplayManager::class.java)
            val display = displayManager.getDisplay(displayId)
            val context: Context? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                createDisplayContext(display).createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null
                )
            } else {
                createDisplayContext(display)
            }
            return context
        }

    private fun isLauncherOrSystem(packageName: String): Boolean {
        if (packageName == "com.android.systemui" ||
            packageName == applicationContext.packageName ||
            packageName.startsWith("xtr.keymapper") ||
            packageName == "android"
        ) {
            return true
        }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolveInfo?.activityInfo?.packageName == packageName) {
                return true
            }
            val homeActivities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            homeActivities.any { it.activityInfo?.packageName == packageName }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * This implementation is used to receive callbacks from the remote
     * service.
     */
    private val mActivityObserverCallback: ActivityObserver = object : ActivityObserver.Stub() {
        private var lastPackageName: String? = null

        override fun onForegroundActivitiesChanged(packageName: String) {
            if (isLauncherOrSystem(packageName)) {
                DiagnosticLog.debug("TouchPointer", "foreground_package_ignored", "package" to packageName, "reason" to "launcher_or_system")
                lastPackageName = null
                return
            }
            if (packageName == lastPackageName) {
                DiagnosticLog.debug("TouchPointer", "foreground_package_unchanged", "package" to packageName)
                return
            }
            DiagnosticLog.info("TouchPointer", "foreground_package_changed", "previous" to lastPackageName, "package" to packageName)
            lastPackageName = packageName
            val context: Context = this@TouchPointer
            val keymapProfiles = KeymapProfiles(context)
            val designated = keymapProfiles.getDesignatedProfileForApp(packageName)

            if (designated != null) {
                val designatedProfileDisabled = keymapProfiles.getProfile(designated, false).disabled
                DiagnosticLog.info("TouchPointer", "foreground_designation_resolved", "package" to packageName, "designatedProfile" to designated, "selectedProfile" to selectedProfile, "designatedDisabled" to designatedProfileDisabled)
                if (designated != selectedProfile || designatedProfileDisabled) {
                    mHandler.post {
                        if (designated != selectedProfile) {
                            DiagnosticLog.info("TouchPointer", "automatic_profile_switch", "foregroundPackage" to packageName, "previousProfile" to selectedProfile, "nextProfile" to designated)
                            launchProfile(designated)
                            Toast.makeText(this@TouchPointer, "Switched to profile: $designated", Toast.LENGTH_SHORT).show()
                        }
                        // A disabled designation may have no enabled fallback. Do not leave
                        // the previous app's keymap running in the newly foregrounded app.
                        if (keymapProfiles.getProfile(designated, false).disabled) {
                            DiagnosticLog.warn("TouchPointer", "foreground_profile_disabled", "package" to packageName, "profile" to designated, "action" to "pause_mapping")
                            try {
                                mService?.pauseMouse()
                            } catch (_: RemoteException) {
                            }
                        }
                    }
                }
                return
            }

            if (!keymapProfiles.profileExistsWithPackageName(packageName)) {
                DiagnosticLog.info("TouchPointer", "foreground_profile_missing", "package" to packageName, "action" to "prompt_user")
                // No profile found, prompt user to create a new profile
                mHandler.post {
                    ProfileSelector.showEnableProfileDialog(
                        context,
                        packageName
                    ) { enabled: Boolean ->
                        DiagnosticLog.info("TouchPointer", "foreground_profile_enable_answer", "package" to packageName, "enabled" to enabled)
                        ProfileSelector.createNewProfileForApp(
                            context,
                            packageName,
                            enabled
                        ) { profile: String? ->
                            launchProfile(profile)
                        }
                    }
                }
            } else {
                // App specific profiles selection dialog
                mHandler.post {
                    ProfileSelector.select(context, { profile: String? ->
                        // Reloading profile
                        DiagnosticLog.info("TouchPointer", "foreground_manual_profile_selection", "package" to packageName, "profile" to profile)
                        this@TouchPointer.selectedProfile = profile
                        val keymapProfile = keymapProfiles.getProfile(profile, true)
                        if (!keymapProfile.disabled) {
                            connectRemoteService(keymapProfile)
                            Toast.makeText(
                                this@TouchPointer,
                                "Keymapping enabled for $packageName",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            try {
                                mService!!.pauseMouse()
                            } catch (_: RemoteException) {
                            }
                            Toast.makeText(
                                this@TouchPointer,
                                "Keymapping disabled for $packageName",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }, packageName)
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID: String = "pointer_service"
        const val DISPLAY_ID: String = "display_id"
        const val ACTION_STOP: String = "xtr.keymapper.action.STOP"

        private val connectionState = PointerConnectionState()

        @Volatile
        var isRunning: Boolean = false

        val backendConnected: Boolean
            get() = connectionState.backendConnected

        fun markStarting() {
            connectionState.markStarting()
            isRunning = true
        }

        fun markStopped() {
            connectionState.stop()
            isRunning = false
        }

        @Volatile
        var currentSelectedProfile: String? = null

        @Volatile
        var activeInstance: TouchPointer? = null
    }
}

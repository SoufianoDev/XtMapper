package xtr.keymapper.server

import xtr.keymapper.log.DiagnosticLog
import android.os.SystemClock
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import rikka.shizuku.Shizuku
import xtr.keymapper.BuildConfig
import xtr.keymapper.IRemoteService
import xtr.keymapper.TouchPointer
import xtr.keymapper.keymap.KeymapConfig

object RemoteServiceHelper {

    @Volatile
    private var service: IRemoteService? = null

    /** Forces the next connection to honor the selected Root/Shizuku backend. */
    @Volatile
    private var forceSelectedBackend = false

    /** Invalidates callbacks from binds started before an input-mode switch. */
    @Volatile
    private var backendGeneration = 0L

    @JvmStatic
    @Volatile
    var useShizuku = false

    @JvmStatic
    fun pauseKeymap(context: Context) {
        DiagnosticLog.debug("RemoteServiceHelper", "pause_keymap_requested")
        getInstance(context) { service ->
            try {
                if (service == null) DiagnosticLog.warn("RemoteServiceHelper", "pause_keymap_skipped", "reason" to "service_unavailable")
                else {
                    service.pauseMouse()
                    DiagnosticLog.debug("RemoteServiceHelper", "pause_keymap_call_sent")
                }
            } catch (e: RemoteException) {
                DiagnosticLog.error("RemoteServiceHelper", "pause_keymap_failed", e)
                Log.i(RemoteService.TAG, e.message, e)
            }
        }
    }

    @JvmStatic
    fun resumeKeymap(context: Context) {
        DiagnosticLog.debug("RemoteServiceHelper", "resume_keymap_requested")
        getInstance(context) { service ->
            try {
                if (service == null) DiagnosticLog.warn("RemoteServiceHelper", "resume_keymap_skipped", "reason" to "service_unavailable")
                else {
                    service.resumeMouse()
                    DiagnosticLog.debug("RemoteServiceHelper", "resume_keymap_call_sent")
                }
            } catch (e: RemoteException) {
                DiagnosticLog.error("RemoteServiceHelper", "resume_keymap_failed", e)
                Log.i(RemoteService.TAG, e.message, e)
            }
        }
    }

    @JvmStatic
    fun reloadKeymap(context: Context) {
        DiagnosticLog.debug("RemoteServiceHelper", "reload_keymap_requested")
        getInstance(context) { service ->
            try {
                if (service == null) DiagnosticLog.warn("RemoteServiceHelper", "reload_keymap_skipped", "reason" to "service_unavailable")
                else {
                    service.reloadKeymap()
                    DiagnosticLog.debug("RemoteServiceHelper", "reload_keymap_call_sent")
                }
            } catch (e: RemoteException) {
                DiagnosticLog.error("RemoteServiceHelper", "reload_keymap_failed", e)
                Log.i(RemoteService.TAG, e.message, e)
            }
        }
    }

    @JvmStatic
    fun runIfActive(context: Context, runnable: Runnable) {
        getInstance(context) { service ->
            try {
                if (service != null && service.isActive) runnable.run()
            } catch (_: RemoteException) {
            }
        }
    }

    fun interface RootRemoteServiceCallback {
        fun onConnection(service: IRemoteService?)
    }

    /**
     * Call before restarting TouchPointer after an input-mode change. Old cached
     * binders and ServiceManager entries must not override the newly selected backend.
     */
    @JvmStatic
    fun prepareForBackendSwitch() {
        backendGeneration++
        DiagnosticLog.info("RemoteServiceHelper", "backend_switch_prepared", "generation" to backendGeneration, "selectedBackend" to if (useShizuku) "shizuku" else "root")
        service = null
        forceSelectedBackend = true
    }

    class RemoteServiceConnection(
        private val cb: RootRemoteServiceCallback,
        private val requestGeneration: Long,
        private val requestedShizuku: Boolean,
    ) : ServiceConnection {
        @Volatile
        private var connectedBinder: IBinder? = null
        private val bindStartedAt = SystemClock.elapsedRealtime()

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            DiagnosticLog.info("RemoteServiceHelper", "service_connected_callback", "component" to name.flattenToString(), "requestGeneration" to requestGeneration, "currentGeneration" to RemoteServiceHelper.backendGeneration, "requestedBackend" to if (requestedShizuku) "shizuku" else "root", "elapsedMs" to (SystemClock.elapsedRealtime() - bindStartedAt))
            // An older asynchronous bind may complete after the user switched modes.
            // Do not cache or deliver its Binder to the new editor/service instance.
            if (requestGeneration != RemoteServiceHelper.backendGeneration ||
                requestedShizuku != RemoteServiceHelper.useShizuku
            ) {
                // This bind belongs to a superseded request; don't let its callback
                // disrupt the new TouchPointer instance while it connects to the new mode.
                DiagnosticLog.warn("RemoteServiceHelper", "stale_service_connection_ignored", "requestGeneration" to requestGeneration, "currentGeneration" to RemoteServiceHelper.backendGeneration, "requestedBackend" to if (requestedShizuku) "shizuku" else "root", "currentBackend" to if (RemoteServiceHelper.useShizuku) "shizuku" else "root")
                return
            }

            connectedBinder = service
            val remote = IRemoteService.Stub.asInterface(service)
            DiagnosticLog.info("RemoteServiceHelper", "service_binder_accepted", "component" to name.flattenToString(), "binderAlive" to service.isBinderAlive, "backend" to if (requestedShizuku) "shizuku" else "root")
            RemoteServiceHelper.service = remote
            RemoteServiceHelper.forceSelectedBackend = false
            try {
                service.linkToDeath({
                    if (RemoteServiceHelper.service?.asBinder() == service) {
                        RemoteServiceHelper.service = null
                    }
                }, 0)
            } catch (e: RemoteException) {
                DiagnosticLog.warn("RemoteServiceHelper", "service_death_link_failed", "error" to e.message, "component" to name.flattenToString())
            }
            cb.onConnection(remote)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            DiagnosticLog.warn("RemoteServiceHelper", "service_disconnected_callback", "component" to name.flattenToString(), "requestGeneration" to requestGeneration, "currentGeneration" to RemoteServiceHelper.backendGeneration)
            // A disconnect from a superseded connection must not erase the newer
            // backend's Binder or notify a callback belonging to the old generation.
            if (requestGeneration != RemoteServiceHelper.backendGeneration) {
                DiagnosticLog.warn("RemoteServiceHelper", "stale_disconnect_ignored", "requestGeneration" to requestGeneration, "currentGeneration" to RemoteServiceHelper.backendGeneration)
                return
            }
            if (RemoteServiceHelper.service?.asBinder() == connectedBinder) {
                RemoteServiceHelper.service = null
                DiagnosticLog.warn("RemoteServiceHelper", "active_service_disconnected", "component" to name.flattenToString(), "backend" to if (requestedShizuku) "shizuku" else "root")
                cb.onConnection(null)
            }
        }
    }

    private fun getInstance() {
        if (service == null) {
            service = IRemoteService.Stub.asInterface(ServiceManager.getService("xtmapper"))
            try {
                service?.asBinder()?.linkToDeath({ service = null }, 0)
            } catch (_: RemoteException) {
            }
        }
    }

    private fun bindShizukuService(context: Context, connection: RemoteServiceConnection) {
        val userServiceArgs = Shizuku.UserServiceArgs(ComponentName(context, RemoteService::class.java.name))
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        Shizuku.bindUserService(userServiceArgs, connection)
    }

    @JvmStatic
    fun isSystemApp(context: Context): Boolean {
        return try {
            val packageName = context.packageName
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getInstanceAsSystemApp(context: Context, callback: RootRemoteServiceCallback?) {
        if (service == null) {
            val mService = RemoteService(context.applicationContext)
            mService.startedFromShell = true
            service = mService
        }
        // The system-app path intentionally uses its in-process service rather than
        // RootService/Shizuku, so backend selection is not applicable here.
        forceSelectedBackend = false
        callback?.onConnection(service)
    }

    /**
     * Get instance of remote service
     * @param context App context
     * @param callback Callback when instance of remote service is obtained
     */
    @JvmStatic
    fun getInstance(context: Context, callback: RootRemoteServiceCallback) {
        useShizuku = KeymapConfig(context).isShizukuMode
        DiagnosticLog.debug("RemoteServiceHelper", "service_lookup_started", "selectedBackend" to if (useShizuku) "shizuku" else "root", "forceSelectedBackend" to forceSelectedBackend, "generation" to backendGeneration)

        val forceBackend = forceSelectedBackend

        // 1. Reuse the active TouchPointer Binder only when the caller has not
        // explicitly requested a backend switch.
        val activePointerService = TouchPointer.activeInstance?.mService
        if (!forceBackend && activePointerService != null && activePointerService.asBinder().isBinderAlive) {
            DiagnosticLog.debug("RemoteServiceHelper", "service_lookup_cache_hit", "source" to "active_touch_pointer", "backend" to if (useShizuku) "shizuku" else "root")
            service = activePointerService
            callback.onConnection(activePointerService)
            return
        }

        // 2. Check if we are a system app
        if (isSystemApp(context)) {
            DiagnosticLog.info("RemoteServiceHelper", "service_backend_selected", "backend" to "system_app_in_process")
            getInstanceAsSystemApp(context, callback)
            return
        }

        // A mode switch must bypass old cached and ServiceManager Binders once;
        // otherwise an alive old Root/Shizuku process can win the race with stopServer().
        if (!forceBackend) {
            // 3. Check existing live cached service
            val existing = service
            if (existing != null && existing.asBinder().isBinderAlive) {
                DiagnosticLog.debug("RemoteServiceHelper", "service_lookup_cache_hit", "source" to "cached_service", "backend" to if (useShizuku) "shizuku" else "root")
                callback.onConnection(existing)
                return
            }

            // 4. Try ServiceManager (shell started service)
            getInstance()
            val smService = service
            if (smService != null && smService.asBinder().isBinderAlive) {
                DiagnosticLog.debug("RemoteServiceHelper", "service_lookup_cache_hit", "source" to "service_manager", "backend" to "shell")
                callback.onConnection(smService)
                return
            }
        }

        // 5. Connect via the configured backend, ignoring stale bind completions.
        val connection = RemoteServiceConnection(callback, backendGeneration, useShizuku)
        if (useShizuku) {
            val shizukuAvailable = Shizuku.pingBinder()
            val shizukuPermitted = shizukuAvailable && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            DiagnosticLog.info("RemoteServiceHelper", "service_backend_probe", "backend" to "shizuku", "binderAvailable" to shizukuAvailable, "permissionGranted" to shizukuPermitted, "generation" to backendGeneration)
            if (shizukuPermitted) {
                DiagnosticLog.info("RemoteServiceHelper", "service_bind_requested", "backend" to "shizuku", "generation" to backendGeneration)
                bindShizukuService(context, connection)
            } else {
                DiagnosticLog.warn("RemoteServiceHelper", "service_bind_rejected", "backend" to "shizuku", "reason" to if (!shizukuAvailable) "binder_unavailable" else "permission_missing")
                callback.onConnection(null)
            }
        } else {
            val rootGranted = Shell.isAppGrantedRoot() != false
            DiagnosticLog.info("RemoteServiceHelper", "service_backend_probe", "backend" to "root", "rootGrantedOrPending" to rootGranted, "generation" to backendGeneration)
            if (rootGranted) {
                val intent = Intent(context, RootRemoteService::class.java)
                DiagnosticLog.info("RemoteServiceHelper", "service_bind_requested", "backend" to "root", "generation" to backendGeneration)
                RootService.bind(intent, connection)
            } else {
                DiagnosticLog.warn("RemoteServiceHelper", "service_bind_rejected", "backend" to "root", "reason" to "root_not_granted")
                callback.onConnection(null)
            }
        }
    }
}

package xtr.keymapper.server

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.ServiceManager
import android.os.SystemProperties
import xtr.keymapper.BuildConfig
import xtr.keymapper.Environment
import xtr.keymapper.activity.MainActivity
import xtr.keymapper.log.Role
import xtr.keymapper.log.SessionStates
import xtr.keymapper.log.XtLogger
import java.lang.reflect.Method

object RemoteServiceShell {

    private const val TAG = "xtmapper-server"

    @JvmStatic
    fun main(args: Array<String>) {
        // Classification must precede logging: the SHELL session header renders
        // Environment.name during initStandalone.
        Environment.launchSignal = args.contains(Environment.CLI_ARG)
        // Logging next so even startup failures below can be recorded (uid/role based storage).
        XtLogger.initStandalone(Role.SHELL)
        XtLogger.debug(TAG, "app_process server starting (args=${args.joinToString(",")})")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            println("Unsupported Android version")
            XtLogger.endSession("Unsupported Android version")
            return
        }

        try {
            try {
                RemoteService.loadLibraries()
            } catch (t: Throwable) {
                // Native load failure kills the process in existing behavior;
                // rethrowing preserves that while the failure is now logged.
                XtLogger.fatal(TAG, "native library load failed", t)
                throw t
            }
            XtLogger.info(TAG, "native libraries loaded")
            Looper.prepareMainLooper()
            val mService = RemoteService(
                getContext(),
                initialIsWaylandClient = args.contains("--wayland-client")
            )
            mService.startedFromShell = true
            var noLogcat = false

            var autoLaunchClientActivity = true
            for (arg in args) {
                when (arg) {
                    "--wayland-client" -> {
                        mService.isWaylandClient = true
                        println("using wayland client")
                    }
                    "--no-auto-launch" -> autoLaunchClientActivity = false
                    "--no-logcat" -> noLogcat = true
                    // Already applied above.
                    Environment.CLI_ARG -> {}
                    else -> println("Invalid argument: $arg")
                }
            }
            XtLogger.info(TAG, "server configuration ready wayland=${mService.isWaylandClient}")
            Selinux.real.relax()
            // Hooks run only on System.exit (crash handler, stopServer exit) — Android does NOT
            // run them when the process is killed with SIGTERM (e.g. pkill); a clean stop is
            // restored by RemoteService.stopServer instead
            Runtime.getRuntime().addShutdownHook(
                Thread { ProcessBuilder("setenforce", "1").start() }
            )
            // A shell process that reaches JVM shutdown without the explicit normal-stop
            // path must not be reported as clean. Normal stop closes the session before exit.
            Runtime.getRuntime().addShutdownHook(
                Thread { XtLogger.endSession(SessionStates.STATUS_UNEXPECTED_EXIT) }
            )
            ServiceManager.addService("xtmapper", mService)

            ProcessBuilder("pm", "grant", BuildConfig.APPLICATION_ID, "android.permission.SYSTEM_ALERT_WINDOW").inheritIO().start()
            val settingsArgs = "settings put system alert_window_bypass_low_ram 1".split(Regex("\\s+"))
            ProcessBuilder(settingsArgs).inheritIO().start()

            val fakeTouch = SystemProperties.get("persist.waydroid.fake_touch", null)
            if (fakeTouch != null && !fakeTouch.isBlank()) {
                val ANSI_RED = "\u001B[31m"
                val ANSI_RESET = "\u001B[0m"
                println(ANSI_RED + "Warning: persist.waydroid.fake_touch=" + fakeTouch + " set" + ANSI_RESET)
                println(ANSI_RED + "Reset it with " + "waydroid prop set persist.waydroid.fake_touch \"\" to avoid issues with XtMapper" + ANSI_RESET)
            } else if (!noLogcat) {
                // Don't flood the console with logcat if warning is being shown
                ProcessBuilder("logcat", "-v", "color", "--pid=" + android.os.Process.myPid()).inheritIO().start()
            }

            // Launch-context signal, kept separate from data=shell.
            if (autoLaunchClientActivity) ProcessBuilder("am", "start", "-a", "android.intent.action.MAIN", "-n",
                ComponentName(mService.context, MainActivity::class.java).flattenToString(),
                "--es", "data",
                MainActivity.SHELL_INIT,
                "--ez", Environment.INTENT_EXTRA,
                Environment.launchSignal.toString()).inheritIO().start()

        } catch (e: Exception) {
            XtLogger.error(TAG, "server startup failed", e)
            // Bounded flush before the existing exit(1) behavior.
            XtLogger.flush(2_000)
            XtLogger.endSession(SessionStates.STATUS_STARTUP_FAILED)
            System.exit(1)
        }
        Looper.loop()
    }

    @JvmStatic
    fun getContextImpl(context: Context): Context {
        var context = context
        while (context is ContextWrapper) {
            context = context.baseContext
        }
        return context
    }

    @JvmStatic
    fun getContext(): Context {
        val systemContext = getSystemContext()
        var context: Context? = null
        val flags = Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
        try {
            context = systemContext.createPackageContext(BuildConfig.APPLICATION_ID, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            XtLogger.error(RemoteService.TAG, "Failed to create package context for the shell process", e)
        }
        return getContextImpl(context!!)
    }

    @SuppressLint("PrivateApi")
    internal fun getSystemContext(): Context {
        try {
            val atClazz = Class.forName("android.app.ActivityThread")
            val systemMain: Method = atClazz.getMethod("systemMain")
            val activityThread = systemMain.invoke(null)
            val getSystemContext: Method = atClazz.getMethod("getSystemContext")
            return getSystemContext.invoke(activityThread) as Context
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}

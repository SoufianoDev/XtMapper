package xtr.keymapper

import xtr.keymapper.log.DiagnosticLog
import android.os.Process
import android.os.Build
import android.app.Application
import xtr.keymapper.log.XtLogger

/**
 * Application hook for the logging subsystem: `onCreate` runs before every
 * component of the main process, so they all share one session and one writer.
 * It stays non-interactive: without a folder grant file logging stays off
 * (Logcat only) until the user enables it from the main screen.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        XtLogger.init(this)
        DiagnosticLog.info("App", "process_started", "version" to BuildConfig.VERSION_NAME, "buildType" to BuildConfig.BUILD_TYPE, "pid" to Process.myPid(), "sdkInt" to Build.VERSION.SDK_INT, "androidVersion" to Build.VERSION.RELEASE, "environment" to Environment.name, "fileSinkAvailable" to XtLogger.isFileSinkAvailable)
    }
}

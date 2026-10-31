package xtr.keymapper.server

import android.app.ApplicationErrorReport
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import xtr.keymapper.ActivityObserver
import xtr.keymapper.BuildConfig
import xtr.keymapper.IRemoteService
import xtr.keymapper.IRemoteServiceCallback
import xtr.keymapper.OnKeyEventListener
import xtr.keymapper.R
import xtr.keymapper.Utils
import xtr.keymapper.activity.MainActivity
import xtr.keymapper.databinding.CursorBinding
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.log.Role
import xtr.keymapper.log.SessionStates
import xtr.keymapper.log.XtLogger
import xtr.keymapper.server.event.KeyEventHandler
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong

class RemoteService @JvmOverloads constructor(
    context: Context,
    initialIsWaylandClient: Boolean = false
) : IRemoteService.Stub() {
    // currentDevice is only read/written on mHandler; device discovery is drained there too.
    private var currentDevice = ""
    private val mouseDeviceLifecycle = MouseDeviceLifecycle()
    // The current client session's service, arbitrated by ClientServiceSlot: Handler-thread
    // replacement and binder-thread death claims install/read/clear through one monitor,
    // and a death shutdown commits a terminal state in the same critical section as its
    // ownership decision, so a replacement arriving during the committed teardown is not
    // admitted and none of the shutdown's side effects can reach it.
    internal val mInputService = ClientServiceSlot<IBinder, InputService>()
    private var mOnKeyEventListener: OnKeyEventListener? = null
    internal var isWaylandClient = initialIsWaylandClient
    private var activityObserverService: ActivityObserverService? = null
    internal var nativeLibraryDir: String = System.getProperty("java.library.path").orEmpty()
    private var cursorView: View? = null
    private var TYPE_SECURE_SYSTEM_OVERLAY = 0
    private val mHandler = Handler(Looper.getMainLooper())
    // Coalesces mouse-device selection from the getevent reader thread into at most one
    // pending main-Handler task, so interleaved device paths cannot flood the queue; the
    // latest selection wins. The "differs from active device" check lives in the process
    // callback below and remains the source of truth for whether to open.
    private val mouseDeviceCoalescer = MouseDeviceCoalescer(
        schedule = { task -> mHandler.post { task() } },
        process = { device -> processMouseDeviceSelection(device) },
    )
    private var mWindowManager: WindowManager? = null
    internal var context: Context = context
    internal var startedFromShell = false

    private var geteventProcess: java.lang.Process? = null

    // shortcut: single-user check; multi-user/work-profile installs have a different uid per user
    private val appUid: Int by lazy {
        context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0)
    }

    private fun enforceCaller() {
        if (Binder.getCallingUid() != appUid && Binder.getCallingPid() != Process.myPid())
            throw SecurityException("XtMapper service access denied")
    }

    init {
        // Logging first: the Shizuku user service constructs this class directly
        // (uid 2000; uid 0 on the root fallback) with no Application.onCreate, so
        // a load failure escaping below could otherwise never be recorded.
        if (!XtLogger.isInitialized) {
            when (Process.myUid()) {
                2000 -> XtLogger.initStandalone(Role.SHIZUKU)
                0 -> XtLogger.initStandalone(Role.ROOT)
                else -> Unit
            }
        }
        try {
            loadLibraries()
        } catch (t: Throwable) {
            // Bounded flush so the record outlives the process; then the
            // original failure still propagates exactly as before.
            XtLogger.fatal(TAG, "native library load failed", t)
            throw t
        }
        init()
    }

    private fun getWindowManager(displayId: Int): WindowManager {
        return try {
            prepareCursorOverlayWindow(displayId)
        } catch (e: Exception) {
            XtLogger.error(TAG, "Failed to prepare cursor overlay window; falling back to default WindowManager", e)
            context.getSystemService(WindowManager::class.java)
        }
    }

    fun init() {
        val pm = context.packageManager
        val packageName = context.packageName
        try {
            val ai = pm.getApplicationInfo(packageName, 0)
            nativeLibraryDir = ai.nativeLibraryDir
            if (!isWaylandClient) Thread { start_getevent() }.start()
            else mHandler.post { start_getevent() }
        } catch (e: PackageManager.NameNotFoundException) {
            XtLogger.error(TAG, "Unable to resolve app native library directory", e)
            throw RuntimeException(e)
        }

        Looper.getMainLooper().thread.setUncaughtExceptionHandler { t, e ->
            // Record the terminal failure first (bounded flush) before attempting the UI relay.
            try {
                XtLogger.fatal(TAG, "uncaught exception on thread ${t.name}", e)
            } catch (ignored: Exception) {
            }
            try {
                val crashInfo = ApplicationErrorReport.CrashInfo(e)

                ProcessBuilder(
                    "am", "start", "-a", "android.intent.action.MAIN", "-n",
                    ComponentName(context, MainActivity::class.java).flattenToString(),
                    "--es", "data",
                    crashInfo.exceptionMessage + "\n" +
                            crashInfo.exceptionClassName + "\n" +
                            crashInfo.stackTrace + "\n" +
                            crashInfo.throwClassName + "\n" +
                            crashInfo.throwFileName + "\n" +
                            crashInfo.throwLineNumber + "\n" +
                            crashInfo.throwMethodName
                ).start()
            } catch (ex: Exception) {
                XtLogger.error(TAG, "Failed to relay the server crash to MainActivity", ex)
            }
            // Do not write a SESSION END marker for an uncaught fatal exception.
            // The next process must be able to identify this session as crashed even
            // when the diagnostic dialog was successfully relayed to MainActivity.
            System.exit(1)
        }
    }

    private fun addCursorView() {
        val view = cursorView ?: return

        if (view.isAttachedToWindow) {
            view.visibility = View.VISIBLE
        } else {
            val params = Utils.getPointerLayoutParams(TYPE_SECURE_SYSTEM_OVERLAY)
            try {
                mWindowManager!!.addView(view, params)
            } catch (e: IllegalStateException) { // A14 QPR3 issue https://gist.github.com/RikkaW/be3fe4178903702c54ec73b2fc1187fe
                cursorView = null
                XtLogger.error(TAG, "Failed to attach cursor overlay view", e)
            }
        }
    }

    @Throws(
        NoSuchMethodException::class,
        NoSuchFieldException::class,
        IllegalAccessException::class
    )
    fun prepareCursorOverlayWindow(displayId: Int): WindowManager {
        TYPE_SECURE_SYSTEM_OVERLAY = WindowManager.LayoutParams::class.java
            .getField("TYPE_SECURE_SYSTEM_OVERLAY").getInt(null)

        val display: Display =
            context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        this.context = this.context.createDisplayContext(display)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.context = this.context.createWindowContext(display, TYPE_SECURE_SYSTEM_OVERLAY, null)
        }
        /*
        * We obtained an instance of WindowManager configured to show windows on the given display.
        * Now call getSystemService(Class) on the returned window context.
        */
        val windowManager: WindowManager = this.context.getSystemService(WindowManager::class.java)

        val layoutInflater: LayoutInflater = this.context.getSystemService(LayoutInflater::class.java)
        this.context.setTheme(R.style.Theme_XtMapper)
        cursorView = CursorBinding.inflate(layoutInflater).root

        val sWindowToken = Binder()
        val setDefaultTokenMethod: Method =
            windowManager.javaClass.getMethod("setDefaultToken", IBinder::class.java)
        setDefaultTokenMethod.invoke(windowManager, sWindowToken)
        return windowManager
    }

    private fun stop_getevent() {
        try {
            geteventProcess?.destroy()
            geteventProcess = null
        } catch (_: Exception) {}
    }

    /**
     * Executes getevent command and processes the output or reads from stdin if wayland client
     */
    private fun start_getevent() {
        try {
            val getevent: BufferedReader
            if (isWaylandClient) {
                getevent = BufferedReader(InputStreamReader(System.`in`))
            } else {
                val proc = Utils.geteventStream(nativeLibraryDir)
                geteventProcess = proc
                getevent = BufferedReader(InputStreamReader(proc.inputStream))
            }
            var line: String?
            while (getevent.readLine().also { line = it } != null) {
                val data = line!!.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray() // split a string like "/dev/input/event2: EV_REL REL_X ffffffff"
                if (addNewDevices(data)) {
                    val service = mInputService.get()
                    if (service != null) try {
                        if (isWaylandClient && data[0].contains("wl_pointer"))
                            service.onWaylandMouseEvent(data[1])

                        if (!service.isKeyboardGrabbed) {
                            val k: KeyEventHandler = service.keyEventHandler
                            if (!service.stopEvents) {
                                k.handleEvent(data[1])
                            } else {
                                k.handleKeyboardShortcutEvent(data[1])
                            }
                        }
                    } catch (e: Exception) {
                        XtLogger.error(TAG, "Error handling input event", e)
                    }
                    if (service == null || !service.isKeyboardGrabbed) {
                        try {
                            mOnKeyEventListener?.onKeyEvent(line)
                        } catch (e: RemoteException) {
                            mOnKeyEventListener = null
                        } catch (e: Exception) {
                            XtLogger.error(TAG, "Error notifying key listener", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            XtLogger.error(TAG, "Input event reader failed", e)
        }
    }

    /**
     * @param data split output of getevent command
     * @return true if output is valid for processing
     */
    private fun addNewDevices(data: Array<String>): Boolean {
        if (data.size != 2) return false
        val evdev = data[0]

        val input_event = data[1].split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        if (isWaylandClient) return true
        if (mInputService.get()?.isKeyboardGrabbed == true) {
            if (input_event.size > 1 && input_event[1] == "EV_KEY") {
                mInputService.get()?.grabKeyboardDevice(evdev)
            }
        }
        if (input_event.size > 1 && input_event[1] == "EV_REL") {
            // Device discovery arrives from the getevent reader thread. The coalescer bounds
            // Handler work to at most one pending task, so interleaved device paths cannot
            // flood the queue; the latest selection wins.
            mouseDeviceCoalescer.request(evdev)
        }
        return true
    }

    // Runs on mHandler via the coalescer drain. The "differs from active device" check is the
    // source of truth for whether a newly selected device actually needs opening.
    private fun processMouseDeviceSelection(device: String) {
        if (currentDevice != device) {
            currentDevice = device
            XtLogger.info(
                TAG,
                "mouse device discovered path=$device inputServiceReady=${mInputService.get() != null}"
            )
            mouseDeviceLifecycle.onDeviceDiscovered(device)?.let(::openMouseDevice)
        }
    }

    private fun openMouseDevice(request: MouseDeviceLifecycle.OpenRequest) {
        val service = mInputService.get()
        if (service == null) {
            mouseDeviceLifecycle.onOpenResult(request, success = false)
            XtLogger.warn(
                TAG,
                "mouse device open deferred path=${request.device} generation=${request.serviceGeneration}; InputService is not ready"
            )
            return
        }

        val fd = service.openMouseDevice(request.device)
        val accepted = mouseDeviceLifecycle.onOpenResult(request, success = fd >= 0)
        if (!accepted) {
            XtLogger.warn(
                TAG,
                "ignored stale mouse open result path=${request.device} fd=$fd generation=${request.serviceGeneration}"
            )
        } else if (fd >= 0) {
            XtLogger.info(
                TAG,
                "mouse device opened path=${request.device} fd=$fd generation=${request.serviceGeneration}"
            )
        } else {
            XtLogger.warn(
                TAG,
                "mouse device open failed path=${request.device} fd=$fd generation=${request.serviceGeneration}"
            )
        }
    }

    // One death registration per client: RemoteService unlinks exactly the registration it
    // replaces (same-binder restarts) and never unlinks during service cleanup — the old
    // cleanup unlink removed a same-binder replacement's registration and left the new
    // session without death handling. A superseded registration that still fires is
    // rejected by the ownership guards in notifyClientDeath.
    private val mClientDeathRegistrations = ClientDeathRegistry<IBinder>()
    // Serialize request identity with Binder registration so concurrent startServer calls
    // cannot register in an order different from their request sequence.
    private val mStartRequestLock = Any()
    private val mStartRequestSequence = AtomicLong()

    private fun isCurrentStartRequest(
        clientBinder: IBinder,
        deathRecipient: IBinder.DeathRecipient,
        requestId: Long
    ): Boolean {
        return requestId == mStartRequestSequence.get() &&
            clientBinder.isBinderAlive &&
            mClientDeathRegistrations.isCurrent(clientBinder, deathRecipient)
    }

    private fun notifyClientDeath(clientBinder: IBinder, deathRecipient: IBinder.DeathRecipient) {
        if (!mClientDeathRegistrations.onDeath(clientBinder, deathRecipient)) {
            XtLogger.info(TAG, "ignored death notification from a superseded client registration")
            return
        }
        if (isWaylandClient) {
            // Wayland's stdin reader may be blocking the main Handler, so preserve immediate
            // cleanup of the client-owned InputService before posting or retrying startup.
            // The slot claim and terminal commitment remain atomic against replacement.
            val (service, superseded) = mInputService.claimForShutdown(clientBinder)
            if (superseded) {
                XtLogger.info(TAG, "ignored death notification of a replaced client session")
                return
            }
            try {
                try {
                    service?.stop()
                } catch (e: Exception) {
                    XtLogger.error(TAG, "Failed to stop input service after client binder death", e)
                }
                mouseDeviceLifecycle.onInputServiceStopped()
                // The slot is already empty, avoiding callbacks into the dead Binder.
                stopServer(true)
            } finally {
                // Retry waiters are notified only after every terminal commitment completes.
                mInputService.releaseTerminal()
            }
        } else {
            // The client Binder is dead. Skip callbacks into that process but keep local and
            // native cleanup serialized with device opening and server replacement.
            mHandler.post {
                val service = mInputService.get()
                if (service != null && service.callback.asBinder() != clientBinder) {
                    XtLogger.info(TAG, "ignored death notification of a client without a service")
                    return@post
                }
                stopServer(true, clientAlive = false)
            }
        }
    }

    /**
     * Queues a client start request. The exact death-recipient identity is captured so a
     * delayed callback from a superseded same-Binder registration cannot invalidate it.
     */
    @Throws(RemoteException::class)
    override fun startServer(
        profile: KeymapProfile,
        keymapConfig: KeymapConfig,
        cb: IRemoteServiceCallback,
        screenWidth: Int,
        screenHeight: Int,
        displayId: Int
    ) {
        enforceCaller()
        XtLogger.info(TAG, "startServer requested displayId=$displayId pointerMode=${keymapConfig.pointerMode}")
        val clientBinder = cb.asBinder()
        val deathRecipient = object : IBinder.DeathRecipient {
            override fun binderDied() {
                notifyClientDeath(clientBinder, this)
            }
        }
        // Keep request ordering and registration replacement in one critical section. This
        // prevents two Binder threads from publishing recipient A after the newer request B.
        val (requestId, superseded) = synchronized(mStartRequestLock) {
            val id = mStartRequestSequence.incrementAndGet()
            val previous = mClientDeathRegistrations.registerAndLink(
                clientBinder,
                deathRecipient
            ) {
                clientBinder.linkToDeath(deathRecipient, 0)
            }
            id to previous
        }
        // Link the replacement before unlinking the old recipient. If linkToDeath fails,
        // registerAndLink restores the previous registration so its active session can still
        // receive death cleanup.
        superseded?.let { clientBinder.unlinkToDeath(it, 0) }

        mHandler.post {
            startServerOnHandler(
                profile,
                keymapConfig,
                cb,
                screenWidth,
                screenHeight,
                displayId,
                clientBinder,
                deathRecipient,
                requestId
            )
        }
    }

    /**
     * Executes one startup attempt on the main Handler. A request waits without constructing
     * resources while shutdown is committed, then retries with a fresh InputService only
     * after the final commitment is released.
     */
    private fun startServerOnHandler(
        profile: KeymapProfile,
        keymapConfig: KeymapConfig,
        cb: IRemoteServiceCallback,
        screenWidth: Int,
        screenHeight: Int,
        displayId: Int,
        clientBinder: IBinder,
        deathRecipient: IBinder.DeathRecipient,
        requestId: Long
    ) {
        if (!isCurrentStartRequest(clientBinder, deathRecipient, requestId)) {
            XtLogger.info(TAG, "ignored stale start request displayId=$displayId")
            return
        }

        val retryStart: () -> Unit = {
            mHandler.post {
                startServerOnHandler(
                    profile,
                    keymapConfig,
                    cb,
                    screenWidth,
                    screenHeight,
                    displayId,
                    clientBinder,
                    deathRecipient,
                    requestId
                )
            }
        }
        if (mInputService.deferUntilAvailable(retryStart)) {
            XtLogger.info(TAG, "deferred start request displayId=$displayId until shutdown completes")
            return
        }

        // Recheck after the admission wait and before touching the active session or
        // allocating display/service resources.
        if (!isCurrentStartRequest(clientBinder, deathRecipient, requestId)) {
            XtLogger.info(TAG, "ignored stale start request before service creation displayId=$displayId")
            return
        }

        val service = mInputService.get()
        if (service != null) {
            if (isWaylandClient) {
                service.hideCursor()
                service.stop()
            }
            stopServer(false)
        }

        if (!isCurrentStartRequest(clientBinder, deathRecipient, requestId)) {
            cursorView?.visibility = View.GONE
            XtLogger.info(TAG, "ignored start request superseded during previous-session shutdown")
            return
        }

        mWindowManager = getWindowManager(displayId)
        if (keymapConfig.pointerMode != KeymapConfig.POINTER_SYSTEM) {
            addCursorView()
        } else {
            cursorView = null
        }

        if (!isCurrentStartRequest(clientBinder, deathRecipient, requestId)) {
            cursorView?.visibility = View.GONE
            XtLogger.info(TAG, "ignored stale start request before InputService construction")
            return
        }

        val newService = try {
            InputService(
                profile, keymapConfig, cb, screenWidth, screenHeight,
                cursorView, isWaylandClient, displayId
            )
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
        newService.keyListener = { line ->
            try {
                mOnKeyEventListener?.onKeyEvent(line)
            } catch (e: RemoteException) {
                mOnKeyEventListener = null
            }
        }

        // The final liveness/ownership check and slot installation are atomic with
        // consumption of this exact Binder death registration. If death wins, no service
        // is installed. If installation wins, the death callback runs afterward and cleans
        // up the just-installed service through the normal ownership-aware path.
        val admitted = mClientDeathRegistrations.runIfCurrent(
            clientBinder,
            deathRecipient,
            isAlive = {
                requestId == mStartRequestSequence.get() && clientBinder.isBinderAlive
            }
        ) {
            mInputService.install(clientBinder, newService)
        }
        if (!admitted) {
            discardUninstalledService(newService)
            if (isCurrentStartRequest(clientBinder, deathRecipient, requestId)) {
                // A shutdown may have committed after the initial availability check.
                // Registering the waiter is atomic with releaseTerminal; if the last release
                // already happened, queue one retry instead of losing the request.
                if (!mInputService.deferUntilAvailable(retryStart)) {
                    retryStart()
                }
            }
            XtLogger.warn(TAG, "startServer admission rejected; stale requests are discarded and blocked requests are deferred")
            return
        }

        if (!isWaylandClient) {
            newService.setMouseLock(true)
            mouseDeviceLifecycle.onInputServiceReady()?.let(::openMouseDevice)
        }

        // Launch app/game only after this request has actually installed its service.
        if (profile.packageName != BuildConfig.APPLICATION_ID && keymapConfig.disableAutoProfiling) {
            val launchIntent =
                context.packageManager.getLaunchIntentForPackage(profile.packageName)
            if (launchIntent != null && launchIntent.component != null) try {
                ProcessBuilder(
                    "am", "start", "-a", "android.intent.action.MAIN", "-n",
                    launchIntent.component!!.flattenToString()
                ).start()
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }

    /**
     * Releases every resource owned by a service that never passed slot admission. Each
     * cleanup is isolated so one failing native release cannot skip the remaining ones.
     */
    private fun discardUninstalledService(service: InputService) {
        cursorView?.visibility = View.GONE
        try {
            service.stop()
        } catch (e: Exception) {
            XtLogger.error(TAG, "Failed to stop a rejected InputService", e)
        }
        try {
            service.stopMouseIfStarted()
        } catch (e: Exception) {
            XtLogger.error(TAG, "Failed to stop mouse reader for rejected InputService", e)
        }
        try {
            service.stopTouchpad()
        } catch (e: Exception) {
            XtLogger.error(TAG, "Failed to stop touchpad for rejected InputService", e)
        }
        try {
            service.destroyUinputDev()
        } catch (e: Exception) {
            XtLogger.error(TAG, "Failed to destroy uinput for rejected InputService", e)
        }
    }

    override fun destroy() {
        enforceCaller()
        if (isWaylandClient) {
            // Wayland does not use the evdev mouse-reader lifecycle; the main Handler may be
            // blocked in its stdin loop, so preserve the existing direct shutdown path.
            stopServer(true)
        } else {
            mHandler.post { stopServer(true) }
        }
    }

    override fun stopServer() {
        enforceCaller()
        mHandler.post { stopServer(true) }
    }

    private fun stopServer(exitProcess: Boolean, clientAlive: Boolean = true) {
        // Restore enforcing only if this app actually relaxed SELinux (shell entry
        // point); startedFromShell is also set by the system-app path, which never does
        if (exitProcess && !Selinux.real.restore()) {
            XtLogger.error(TAG, "Failed to restore SELinux enforcing mode")
        }
        val service = mInputService.get()
        if (service != null && clientAlive) {
            try {
                service.callback.disablePointer()
            } catch (e: RemoteException) {
                // The Binder can die just before its death recipient is posted to mHandler.
                // Treat the callback as unavailable and continue local/native cleanup.
                XtLogger.warn(TAG, "Client Binder unavailable during pointer shutdown; continuing cleanup", e)
            }
        }

        if (!startedFromShell && exitProcess) {
            stop_getevent()
            XtLogger.info(TAG, "server stopped; exiting process")
            // END marker before the existing System.exit; endSession is bounded.
            XtLogger.endSession(SessionStates.STATUS_SERVICE_STOP)
            System.exit(0)
        } else if (service != null && !isWaylandClient) {
            service.stopEvents = true
            // Hide the cursor regardless of client Binder liveness: the local overlay belongs
            // to this process and must not stay visible after the client dies. If the
            // system-pointer fallback hits the dead Binder, CursorShutdown still runs cleanup
            // so the native mouse reader stops and resources are released.
            CursorShutdown.hideThenCleanup(
                hideCursor = { service.hideCursor() },
                cleanup = {
                    service.stop()
                    service.stopMouseIfStarted()
                    service.stopTouchpad()
                    service.destroyUinputDev()
                    // Death-recipient unlinking happens only in startServer, where the
                    // registration being replaced is known; unlinking here used to delete
                    // a same-binder replacement's registration. A superseded registration
                    // that still fires is rejected by the ownership guards instead.
                    mouseDeviceLifecycle.onInputServiceStopped()
                    mInputService.clear()
                }
            )
        }
    }

    private val mKeyEventListenerDeathRecipient = IBinder.DeathRecipient {
        mOnKeyEventListener = null
    }

    @Throws(RemoteException::class)
    override fun registerOnKeyEventListener(l: OnKeyEventListener) {
        enforceCaller()
        l.asBinder().linkToDeath(mKeyEventListenerDeathRecipient, 0)
        mOnKeyEventListener = l
    }

    override fun unregisterOnKeyEventListener(l: OnKeyEventListener?) {
        enforceCaller()
        l?.asBinder()?.unlinkToDeath(mKeyEventListenerDeathRecipient, 0)
        mOnKeyEventListener = null
    }

    override fun registerActivityObserver(callback: ActivityObserver) {
        enforceCaller()
        activityObserverService?.stop()
        activityObserverService = ActivityObserverService(callback)
    }

    override fun unregisterActivityObserver(callback: ActivityObserver) {
        enforceCaller()
        activityObserverService?.stop()
        activityObserverService = null
    }

    /**
     * Used to temporary stop the keymapping.
     */
    override fun pauseMouse() {
        enforceCaller()
        // startServer() creates/replaces InputService on this same handler. Queue the
        // pause too, so a pending start cannot install a fresh, unpaused service after
        // the caller has asked to disable mapping for the foreground app.
        mHandler.post {
            val service = mInputService.get()
            if (service != null && !service.stopEvents) {
                service.pauseResumeKeymap()
            }
        }
    }

    override fun resumeMouse() {
        enforceCaller()
        // Keep pause/resume state changes ordered with startServer() on the same handler.
        mHandler.post {
            val service = mInputService.get()
            if (service != null && service.stopEvents) {
                service.pauseResumeKeymap()
            }
        }
    }

    /**
     * Used to refresh by requesting new keymap from the user app
     */
    override fun reloadKeymap() {
        enforceCaller()
        mInputService.get()?.reloadKeymap()
    }

    override fun isActive(): Boolean {
        enforceCaller()
        return mInputService.get() != null
    }

    companion object {
        const val TAG = "xtmapper-server"

        fun loadLibraries() {
            System.loadLibrary("mouse_read")
            System.loadLibrary("mouse_cursor")
            System.loadLibrary("touchpad_direct")
            System.loadLibrary("touchpad_relative")
            System.loadLibrary("keyboard_grab")
        }
    }
}

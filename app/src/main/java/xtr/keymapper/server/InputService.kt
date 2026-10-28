package xtr.keymapper.server

import xtr.keymapper.log.DiagnosticLog
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.view.MotionEvent
import android.view.View
import xtr.keymapper.InputEventCodes.BTN_MIDDLE
import xtr.keymapper.InputEventCodes.BTN_MOUSE
import xtr.keymapper.InputEventCodes.BTN_RIGHT
import xtr.keymapper.InputEventCodes.REL_WHEEL
import xtr.keymapper.InputEventCodes.REL_X
import xtr.keymapper.InputEventCodes.REL_Y
import xtr.keymapper.IRemoteServiceCallback
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.log.XtLogger
import xtr.keymapper.server.event.KeyEventHandler
import xtr.keymapper.server.event.MouseEventHandler

class InputService @Throws(RemoteException::class) constructor(
    profile: KeymapProfile,
    keymapConfig: KeymapConfig,
    private val mCallback: IRemoteServiceCallback,
    screenWidth: Int,
    screenHeight: Int,
    private val cursorView: View?,
    private val isWaylandClient: Boolean,
    displayId: Int
) : IInputInterface {

    override var keymapConfig: KeymapConfig = keymapConfig
    override var keymapProfile: KeymapProfile = profile
    override val mouseEventHandler: MouseEventHandler
    override val keyEventHandler: KeyEventHandler
    override val callback: IRemoteServiceCallback = mCallback
    private val input: Input
    private val touchpadInputMode: Int
    private val currentPointerMode: Int

    private val mHandler = Handler(Looper.getMainLooper())
    private var supportsAbsEvents = false

    internal var stopEvents = false
    val touchJitter = TouchJitter(keymapConfig.touchJitter, keymapConfig.touchJitterSpread)
    var keyListener: ((String) -> Unit)? = null
    var isKeyboardGrabbed: Boolean = false
        private set
    override var isKeyboardBlocked: Boolean = false
        private set

    init {
        DiagnosticLog.info(RemoteService.TAG, "input_service_init_started", "package" to profile.packageName, "profileEnabled" to !profile.disabled, "screenWidth" to screenWidth, "screenHeight" to screenHeight, "displayId" to displayId, "pointerMode" to keymapConfig.pointerMode, "touchpadInputMode" to keymapConfig.touchpadInputMode, "waylandClient" to isWaylandClient, "keyCount" to profile.keys.size, "dpadCount" to profile.dpadArray.count { it != null }, "swipeKeyCount" to profile.swipeKeys.size, "shiftKeyCount" to profile.shiftKeys.size, "macroCount" to profile.macroIdMap.size)
        profile.scale(screenWidth.toFloat(), screenHeight.toFloat())
        this.currentPointerMode = keymapConfig.pointerMode
        if (currentPointerMode != KeymapConfig.POINTER_OVERLAY) {
            initMouseCursor(screenWidth, screenHeight)
            // Reduce visibility of system pointer
            cursorSetX(0)
            cursorSetY(0)
        }
        if (currentPointerMode != KeymapConfig.POINTER_SYSTEM) showCursor()

        // Disable touchpad features for wayland client
        this.touchpadInputMode = if (isWaylandClient) KeymapConfig.TOUCHPAD_DISABLED else keymapConfig.touchpadInputMode

        if (touchpadInputMode == KeymapConfig.TOUCHPAD_DIRECT)
            startTouchpadDirect()
        else if (touchpadInputMode == KeymapConfig.TOUCHPAD_RELATIVE)
            startTouchpadRelative()

        mouseEventHandler = MouseEventHandler(this)
        mouseEventHandler.init(screenWidth, screenHeight)

        keyEventHandler = KeyEventHandler(this)
        keyEventHandler.init()
        input = Input(displayId)
        touchJitter.setScreenDimensions(screenWidth, screenHeight)

        if (keymapConfig.isRootMode && keymapConfig.blockKeyboardInput && !isWaylandClient) {
            DiagnosticLog.info(RemoteService.TAG, "keyboard_grab_start_requested", "reason" to "initialization", "mappingPaused" to stopEvents)
            try {
                isKeyboardGrabbed = startKeyboardGrab()
                isKeyboardBlocked = isKeyboardGrabbed
                DiagnosticLog.info(RemoteService.TAG, "keyboard_grab_start_result", "success" to isKeyboardGrabbed, "blocked" to isKeyboardBlocked, "reason" to "initialization")
            } catch (e: Exception) {
                DiagnosticLog.error(RemoteService.TAG, "keyboard_grab_start_failed", e, "reason" to "initialization")
                XtLogger.error(RemoteService.TAG, "Failed to start keyboard grab", e)
            }
        } else {
            DiagnosticLog.debug(RemoteService.TAG, "keyboard_grab_skipped", "rootMode" to keymapConfig.isRootMode, "blockKeyboardInput" to keymapConfig.blockKeyboardInput, "waylandClient" to isWaylandClient)
        }
        DiagnosticLog.info(RemoteService.TAG, "input_service_init_completed", "keyboardGrabbed" to isKeyboardGrabbed, "keyboardBlocked" to isKeyboardBlocked, "touchpadInputMode" to touchpadInputMode)
    }

    override fun injectEvent(x: Float, y: Float, action: Int, pointerId: Int) {
        if (action != MOVE) {
            DiagnosticLog.debug(RemoteService.TAG, "touch_injection_transition", "action" to if (action == DOWN) "down" else if (action == UP) "up" else action, "pointerId" to pointerId, "x" to x, "y" to y, "mappingPaused" to stopEvents, "pointerMode" to currentPointerMode)
        }
        val target = touchJitter.wrapCoordinates(x, y, action, pointerId)
        when (action) {
            UP -> input.injectTouch(MotionEvent.ACTION_UP, pointerId, 0.0f, target.x, target.y)
            DOWN -> input.injectTouch(MotionEvent.ACTION_DOWN, pointerId, 1.0f, target.x, target.y)
            MOVE -> input.injectTouch(MotionEvent.ACTION_MOVE, pointerId, 0.0f, target.x, target.y)
        }
    }

    override fun injectHoverEvent(x: Float, y: Float, pointerId: Int) {
        if (input.noPointersDown() && currentPointerMode == KeymapConfig.POINTER_OVERLAY)
            input.injectTouch(MotionEvent.ACTION_HOVER_MOVE, pointerId, 1.0f, x, y)
    }

    override fun injectRightClickEvent(x: Float, y: Float, pointerId: Int, pressed: Boolean) {
        input.injectMouseClick(pointerId.toLong(), x, y, pressed, MotionEvent.BUTTON_SECONDARY)
    }

    override fun injectMiddleClickEvent(x: Float, y: Float, pointerId: Int, pressed: Boolean) {
        input.injectMouseClick(pointerId.toLong(), x, y, pressed, MotionEvent.BUTTON_TERTIARY)
    }

    override fun injectScroll(x: Float, y: Float, value: Float) {
        input.onScrollEvent(x, y, value)
    }

    override fun pauseResumeKeymap() {
        val wasStopped = stopEvents
        stopEvents = !stopEvents
        DiagnosticLog.info(RemoteService.TAG, "input_mapping_state_changed", "previouslyPaused" to wasStopped, "paused" to stopEvents, "keyboardGrabbed" to isKeyboardGrabbed, "keyboardBlocked" to isKeyboardBlocked, "waylandClient" to isWaylandClient)
        if (!isWaylandClient) {
            setMouseLock(!stopEvents)
            if (isKeyboardGrabbed) {
                setKeyboardGrabLock(!stopEvents && isKeyboardBlocked)
            }
        }
    }

    override fun toggleKeyboardBlock() {
        DiagnosticLog.info(RemoteService.TAG, "keyboard_block_toggle_requested", "rootMode" to keymapConfig.isRootMode, "waylandClient" to isWaylandClient, "grabbedBefore" to isKeyboardGrabbed, "blockedBefore" to isKeyboardBlocked, "mappingPaused" to stopEvents)
        if (!keymapConfig.isRootMode || isWaylandClient) {
            DiagnosticLog.warn(RemoteService.TAG, "keyboard_block_toggle_ignored", "reason" to if (!keymapConfig.isRootMode) "not_root_mode" else "wayland_client")
            return
        }
        if (!isKeyboardGrabbed) {
            try {
                DiagnosticLog.info(RemoteService.TAG, "keyboard_grab_start_requested", "reason" to "manual_toggle")
                isKeyboardGrabbed = startKeyboardGrab()
                DiagnosticLog.info(RemoteService.TAG, "keyboard_grab_start_result", "success" to isKeyboardGrabbed, "reason" to "manual_toggle")
                if (!isKeyboardGrabbed) {
                    XtLogger.warn(RemoteService.TAG, "startKeyboardGrab returned false, no keyboard device grabbed")
                    return
                }
                isKeyboardBlocked = true
                DiagnosticLog.info(RemoteService.TAG, "keyboard_block_state_changed", "blocked" to true, "mappingPaused" to stopEvents)
                // A new grab starts locked natively. Honour the existing pause state
                // immediately so a grab started while paused does not swallow all typing.
                setKeyboardGrabLock(!stopEvents)
            } catch (e: Exception) {
                DiagnosticLog.error(RemoteService.TAG, "keyboard_grab_start_failed", e, "reason" to "manual_toggle")
                XtLogger.error(RemoteService.TAG, "Failed to start keyboard grab", e)
                return
            }
        } else {
            isKeyboardBlocked = !isKeyboardBlocked
            setKeyboardGrabLock(isKeyboardBlocked && !stopEvents)
            DiagnosticLog.info(RemoteService.TAG, "keyboard_block_state_changed", "blocked" to isKeyboardBlocked, "mappingPaused" to stopEvents)
        }
        try {
            mCallback.alertKeyboardBlocked(isKeyboardBlocked && !stopEvents)
        } catch (e: RemoteException) {
            XtLogger.error(RemoteService.TAG, "Failed to send alertKeyboardBlocked", e)
        }
    }

    override fun moveCursorX(x: Int) {
        val view = cursorView
        if (view != null) {
            mHandler.post { view.x = x.toFloat() }
        } else {
            try {
                mCallback.setCursorX(x)
            } catch (ignored: RemoteException) {
            }
        }
        if (currentPointerMode != KeymapConfig.POINTER_OVERLAY) {
            // To avoid conflict with touch input when moving virtual pointer
            if (input.noPointersDown()) cursorSetX(x)
        }
    }

    override fun moveCursorY(y: Int) {
        val view = cursorView
        if (view != null) {
            mHandler.post { view.y = y.toFloat() }
        } else {
            try {
                mCallback.setCursorY(y)
            } catch (ignored: RemoteException) {
            }
        }
        if (currentPointerMode != KeymapConfig.POINTER_OVERLAY) {
            // To avoid conflict with touch input when moving virtual pointer
            if (input.noPointersDown()) cursorSetY(y)
        }
    }

    override fun hideCursor() {
        val view = cursorView
        if (view != null) {
            mHandler.post { view.visibility = View.GONE }
        } else {
            try {
                mCallback.disablePointer()
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
    }

    override fun showCursor() {
        val view = cursorView
        if (view != null) {
            mHandler.post { view.visibility = View.VISIBLE }
        } else {
            try {
                mCallback.enablePointer()
            } catch (e: RemoteException) {
                throw RuntimeException(e)
            }
        }
    }

    fun reloadKeymap() {
        DiagnosticLog.info(RemoteService.TAG, "input_keymap_reload_started")
        try {
            this.keymapProfile = mCallback.requestKeymapProfile()
            this.keymapConfig = mCallback.requestKeymapConfig()
            DiagnosticLog.info(RemoteService.TAG, "input_keymap_reload_received", "package" to keymapProfile.packageName, "profileEnabled" to !keymapProfile.disabled, "keyCount" to keymapProfile.keys.size, "dpadCount" to keymapProfile.dpadArray.count { it != null }, "swipeKeyCount" to keymapProfile.swipeKeys.size, "shiftKeyCount" to keymapProfile.shiftKeys.size, "macroCount" to keymapProfile.macroIdMap.size, "pointerMode" to keymapConfig.pointerMode, "blockKeyboardInput" to keymapConfig.blockKeyboardInput)
            // Release existing pointers with their original offsets before clearing
            // jitter state; then apply the newly loaded settings to future strokes.
            this.stop()
            touchJitter.updateConfig(this.keymapConfig.touchJitter, this.keymapConfig.touchJitterSpread)
            touchJitter.clear()
            keyEventHandler.init()
            mouseEventHandler.init()
            if (keymapConfig.isRootMode && keymapConfig.blockKeyboardInput && !isWaylandClient) {
                try {
                    isKeyboardGrabbed = startKeyboardGrab()
                    isKeyboardBlocked = isKeyboardGrabbed
                    if (isKeyboardGrabbed) setKeyboardGrabLock(!stopEvents)
                } catch (e: Exception) {
                    XtLogger.error(RemoteService.TAG, "Failed to start keyboard grab", e)
                }
            }
        } catch (e: Exception) {
            DiagnosticLog.error(RemoteService.TAG, "input_keymap_reload_failed", e)
            XtLogger.error(RemoteService.TAG, "Keyboard/input operation failed", e)
        }
    }

    fun stop() {
        DiagnosticLog.info(RemoteService.TAG, "input_service_stop_started", "keyboardGrabbed" to isKeyboardGrabbed, "keyboardBlocked" to isKeyboardBlocked, "mappingPaused" to stopEvents)
        if (isKeyboardGrabbed) {
            try {
                stopKeyboardGrab()
            } catch (e: Exception) {
                XtLogger.error(RemoteService.TAG, "Failed to stop keyboard grab", e)
            }
            isKeyboardGrabbed = false
            isKeyboardBlocked = false
            DiagnosticLog.info(RemoteService.TAG, "keyboard_grab_stopped")
        }
        keyEventHandler.stop()
        mouseEventHandler.stop()
        DiagnosticLog.info(RemoteService.TAG, "input_service_stop_completed")
    }

    fun stopTouchpad() {
        if (touchpadInputMode == KeymapConfig.TOUCHPAD_DIRECT)
            stopTouchpadDirect()
        else if (touchpadInputMode == KeymapConfig.TOUCHPAD_RELATIVE)
            stopTouchpadRelative()
    }

    external fun openDevice(device: String): Int
    external fun stopMouse()

    // mouse cursor created with uinput in mouse_cursor.cpp
    external fun cursorSetX(x: Int)
    external fun cursorSetY(y: Int)
    private external fun initMouseCursor(width: Int, height: Int): Int
    external fun destroyUinputDev()

    external fun setMouseLock(lock: Boolean)

    // keyboard_grab.cpp
    external fun startKeyboardGrab(): Boolean
    external fun stopKeyboardGrab()
    external fun setKeyboardGrabLock(lock: Boolean)
    external fun grabKeyboardDevice(device: String): Boolean

    // touchpad_direct.cpp
    private external fun startTouchpadDirect()
    external fun stopTouchpadDirect()

    private external fun startTouchpadRelative()
    external fun stopTouchpadRelative()

    /*
     * Called from native code to send mouse event to client
     */
    fun sendMouseEvent(code: Int, value: Int) {
        // Do not log REL_X/REL_Y: those events arrive at movement frequency.
        if (code != REL_X && code != REL_Y) {
            DiagnosticLog.debug(RemoteService.TAG, "mouse_input_transition", "code" to code, "value" to value, "mappingPaused" to stopEvents)
        }
        if (!stopEvents) mouseEventHandler.handleEvent(code, value)
    }

    /*
     * Called from native code when keyboard is grabbed
     */
    fun sendKeyboardEvent(codeName: String, action: Int) {
        val actionStr = if (action == DOWN) "DOWN" else "UP"
        DiagnosticLog.debug(RemoteService.TAG, "grabbed_keyboard_event", "keyCode" to codeName, "action" to actionStr, "mappingPaused" to stopEvents, "keyboardBlocked" to isKeyboardBlocked, "route" to if (shouldHandleMappedKeyboardEvent(stopEvents, isKeyboardBlocked)) "mapped" else "shortcut_only")
        val line = "EV_KEY $codeName $actionStr"
        try {
            if (shouldHandleMappedKeyboardEvent(stopEvents, isKeyboardBlocked)) {
                keyEventHandler.handleEvent(line)
            } else {
                keyEventHandler.handleKeyboardShortcutEvent(line)
            }
        } catch (e: Exception) {
            XtLogger.error(RemoteService.TAG, "Error handling keyboard event", e)
        }
        try {
            keyListener?.invoke(line)
        } catch (e: Exception) {
            XtLogger.error(RemoteService.TAG, "Error invoking keyListener", e)
        }
    }

    fun onWaylandMouseEvent(line: String) {
        val input_event = line.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val value = input_event[3].toInt()
        when (input_event[2]) {
            "ABS_X" -> {
                supportsAbsEvents = true
                if (!mouseEventHandler.mouseAimActive)
                    mouseEventHandler.evAbsX(value)
            }
            "ABS_Y" -> if (!mouseEventHandler.mouseAimActive) mouseEventHandler.evAbsY(value)
            "REL_WHEEL" -> mouseEventHandler.handleEvent(REL_WHEEL, value)
            "BTN_LEFT" -> mouseEventHandler.handleEvent(BTN_MOUSE, value)
            "BTN_RIGHT" -> mouseEventHandler.handleEvent(BTN_RIGHT, value)
            "BTN_MIDDLE" -> mouseEventHandler.handleEvent(BTN_MIDDLE, value)
            "REL_X" -> if (mouseEventHandler.mouseAimActive || !supportsAbsEvents)
                mouseEventHandler.handleEvent(REL_X, value)
            "REL_Y" -> if (mouseEventHandler.mouseAimActive || !supportsAbsEvents)
                mouseEventHandler.handleEvent(REL_Y, value)
        }
    }

    companion object {
        const val UP = 0
        const val DOWN = 1
        const val MOVE = 2
    }
}

internal fun shouldHandleMappedKeyboardEvent(stopEvents: Boolean, isKeyboardBlocked: Boolean): Boolean =
    !stopEvents && isKeyboardBlocked

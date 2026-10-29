package xtr.keymapper.server

/**
 * Serializes the relationship between a discovered evdev mouse and an InputService generation.
 *
 * The getevent reader and the server-start handler run on different threads. Keep the state
 * behind synchronized methods, and let RemoteService perform open requests on its main Handler.
 */
internal class MouseDeviceLifecycle {
    internal data class OpenRequest(
        val device: String,
        val serviceGeneration: Long
    )

    private var discoveredDevice: String? = null
    private var nextServiceGeneration = 0L
    private var activeServiceGeneration: Long? = null
    private var attemptedDevice: String? = null
    private var openedDevice: String? = null
    private var inFlightRequest: OpenRequest? = null

    @Synchronized
    fun onDeviceDiscovered(device: String): OpenRequest? {
        if (device.isBlank()) return null

        if (discoveredDevice != device) {
            discoveredDevice = device
            attemptedDevice = null
            openedDevice = null
            inFlightRequest = null
        }
        return nextOpenRequest()
    }

    @Synchronized
    fun onInputServiceReady(): OpenRequest? {
        nextServiceGeneration += 1
        activeServiceGeneration = nextServiceGeneration
        attemptedDevice = null
        openedDevice = null
        inFlightRequest = null
        return nextOpenRequest()
    }

    @Synchronized
    fun onInputServiceStopped() {
        activeServiceGeneration = null
        attemptedDevice = null
        openedDevice = null
        inFlightRequest = null
    }

    /**
     * Returns false for a result belonging to a stopped/replaced service or superseded device.
     * A failed open is not retried on every subsequent REL_X/REL_Y event; the next InputService
     * generation gets a fresh attempt and the diagnostic log records the failure.
     */
    @Synchronized
    fun onOpenResult(request: OpenRequest, success: Boolean): Boolean {
        if (activeServiceGeneration != request.serviceGeneration ||
            discoveredDevice != request.device ||
            inFlightRequest != request
        ) {
            return false
        }

        inFlightRequest = null
        attemptedDevice = request.device
        if (success) openedDevice = request.device
        return true
    }

    private fun nextOpenRequest(): OpenRequest? {
        val generation = activeServiceGeneration ?: return null
        val device = discoveredDevice ?: return null
        if (openedDevice == device || attemptedDevice == device || inFlightRequest != null) {
            return null
        }

        return OpenRequest(device, generation).also { inFlightRequest = it }
    }
}

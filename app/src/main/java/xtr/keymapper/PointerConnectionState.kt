package xtr.keymapper

/**
 * Tracks the local connection lifecycle independently of Android Service/UI state.
 * Keeping this small state machine free of Android APIs makes stale-callback and
 * failed-connect behavior straightforward to regression-test.
 */
internal class PointerConnectionState {
    private var generation = 0L

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var backendConnected: Boolean = false
        private set

    @Synchronized
    fun markStarting() {
        generation++
        isRunning = true
        backendConnected = false
    }

    /** Starts a new async request without clearing an already-working backend. */
    @Synchronized
    fun beginRequest(): Long {
        generation++
        return generation
    }

    @Synchronized
    fun isCurrent(requestGeneration: Long): Boolean =
        requestGeneration == generation && isRunning

    @Synchronized
    fun markConnected(requestGeneration: Long): Boolean {
        if (!isCurrent(requestGeneration)) return false
        backendConnected = true
        return true
    }

    /** Returns false for obsolete callbacks, which must not mutate current state. */
    @Synchronized
    fun fail(requestGeneration: Long): Boolean {
        if (!isCurrent(requestGeneration)) return false
        generation++
        isRunning = false
        backendConnected = false
        return true
    }

    /** Invalidates all in-flight callbacks and returns to the stopped state. */
    @Synchronized
    fun stop() {
        generation++
        isRunning = false
        backendConnected = false
    }
}

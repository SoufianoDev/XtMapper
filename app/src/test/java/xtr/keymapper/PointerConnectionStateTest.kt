package xtr.keymapper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerConnectionStateTest {
    @Test
    fun failedCurrentRequestReturnsToStoppedState() {
        val state = PointerConnectionState()
        state.markStarting()
        val request = state.beginRequest()

        assertTrue(state.isCurrent(request))
        assertTrue(state.fail(request))
        assertFalse(state.isRunning)
        assertFalse(state.backendConnected)
        assertFalse(state.isCurrent(request))
    }

    @Test
    fun callbackAfterStopCannotReconnectService() {
        val state = PointerConnectionState()
        state.markStarting()
        val request = state.beginRequest()

        state.stop()

        assertFalse(state.markConnected(request))
        assertFalse(state.isRunning)
        assertFalse(state.backendConnected)
    }

    @Test
    fun staleFailureCannotClearNewerSuccessfulConnection() {
        val state = PointerConnectionState()
        state.markStarting()
        val oldRequest = state.beginRequest()
        val currentRequest = state.beginRequest()

        assertTrue(state.markConnected(currentRequest))
        assertFalse(state.fail(oldRequest))
        assertTrue(state.isRunning)
        assertTrue(state.backendConnected)
    }

    @Test
    fun newStartupClearsConnectedStateFromPreviousRun() {
        val state = PointerConnectionState()
        state.markStarting()
        val firstRequest = state.beginRequest()
        assertTrue(state.markConnected(firstRequest))

        state.markStarting()

        assertFalse(state.isCurrent(firstRequest))
        assertTrue(state.isRunning)
        assertFalse(state.backendConnected)
    }
}

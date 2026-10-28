package xtr.keymapper.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {
    @Test
    fun formatsStableKeyValueEvents() {
        assertEquals("event=foreground_transition package=com.example.game profile=Battle_Royale",
            DiagnosticLog.formatMessage("foreground transition", "package" to "com.example.game", "profile" to "Battle Royale"))
    }

    @Test
    fun sanitizesNewlinesAndDelimiters() {
        assertEquals("event=clipboard_import result=malformed_line_second",
            DiagnosticLog.formatMessage("clipboard_import", "result" to "malformed\nline|second"))
    }

    @Test
    fun boundsFieldValues() {
        val message = DiagnosticLog.formatMessage("long_value", "value" to "x".repeat(500))
        assertEquals("event=long_value value=${"x".repeat(240)}", message)
    }

    @Test
    fun sanitizesKeys() {
        assertEquals("event=event weird_key=value", DiagnosticLog.formatMessage("event", "weird key" to "value"))
    }

    @Test
    fun rateLimitsRepeatedEventKeysButAllowsDifferentKeyTransitions() {
        val keyDown = "KeyEventHandler:key_event_parsed:KEY_A:down"
        assertTrue(DiagnosticLog.shouldEmitRateLimited(keyDown, 10_000L, 2_000L))
        assertFalse(DiagnosticLog.shouldEmitRateLimited(keyDown, 11_999L, 2_000L))
        assertTrue(DiagnosticLog.shouldEmitRateLimited(keyDown, 12_000L, 2_000L))
        assertTrue(DiagnosticLog.shouldEmitRateLimited("KeyEventHandler:key_event_parsed:KEY_A:up", 11_000L, 2_000L))
    }
}

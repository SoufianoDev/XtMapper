package xtr.keymapper.keymap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileParseTest {

    @Test
    fun parsesValidProfileLines() {
        val profile = KeymapProfiles.parse(
            setOf(
                "APPLICATION com.example.game",
                "ENABLED",
                "KEY_G 760.86 426.18 0",
                "MACRO macro0 KEY_M",
                "MOUSE_RIGHT 100 200"
            )
        )
        assertEquals("com.example.game", profile.packageName)
        assertFalse(profile.disabled)
        assertEquals(1, profile.keys.size)
        assertEquals("KEY_G", profile.keys[0].code)
        assertEquals(760.86f, profile.keys[0].x)
        assertEquals("KEY_M", profile.macroIdMap["macro0"]!!.triggerKey)
        assertEquals(100f, profile.rightClick!!.x)
        assertEquals(200f, profile.rightClick!!.y)
    }

    @Test
    fun malformedLinesAreSkippedNotCrashing() {
        val profile = KeymapProfiles.parse(
            setOf(
                "APPLICATION",       // missing package name
                "SCREENSIZE 1200",   // missing y
                "MOUSE_RIGHT 10",    // missing coords
                "KEY_G 1 2",         // key line too short
                "MACRO macro0"       // missing trigger key
            )
        )
        assertTrue(profile.keys.isEmpty())
        assertTrue(profile.macroIdMap.isEmpty())
        assertEquals(null, profile.rightClick)
    }

    @Test
    fun defaultProfileIsDisabled() {
        assertTrue(KeymapProfiles.parse(null).disabled)
        assertTrue(KeymapProfiles.parse(emptySet()).disabled)
    }
}

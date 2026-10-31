package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelinuxTest {

    @Test
    fun `does not force permissive when initial state is not enforcing`() {
        val commands = mutableListOf<Int>()
        val selinux = Selinux(
            setenforce = { mode -> commands.add(mode); true },
            getenforce = { "Permissive" },
        )

        assertFalse(selinux.relax())
        assertFalse(selinux.restorationPending)
        assertTrue(selinux.restore())
        assertTrue("no state change should be requested", commands.isEmpty())
    }

    @Test
    fun `restores enforcing after a verified relax transition`() {
        var mode = "Enforcing"
        val commands = mutableListOf<Int>()
        val selinux = Selinux(
            setenforce = { requested ->
                commands.add(requested)
                mode = if (requested == 0) "Permissive" else "Enforcing"
                true
            },
            getenforce = { mode },
        )

        assertTrue(selinux.relax())
        assertTrue(selinux.restorationPending)
        assertTrue(selinux.restore())
        assertFalse(selinux.restorationPending)
        assertEquals(listOf(0, 1), commands)
    }

    @Test
    fun `keeps restoration pending when relax result is uncertain`() {
        val commands = mutableListOf<Int>()
        var reads = 0
        val selinux = Selinux(
            setenforce = { requested ->
                commands.add(requested)
                true
            },
            getenforce = {
                reads++
                when (reads) {
                    1 -> "Enforcing"
                    2 -> null
                    else -> "Enforcing"
                }
            },
        )

        assertFalse(selinux.relax())
        assertTrue(selinux.restorationPending)
        assertTrue(selinux.restore())
        assertFalse(selinux.restorationPending)
        assertEquals(listOf(0, 1), commands)
    }

    @Test
    fun `retains pending state when restore cannot be verified`() {
        var mode = "Enforcing"
        val selinux = Selinux(
            setenforce = { requested ->
                if (requested == 0) {
                    mode = "Permissive"
                    true
                } else {
                    false
                }
            },
            getenforce = { mode },
        )

        assertTrue(selinux.relax())
        assertFalse(selinux.restore())
        assertTrue(selinux.restorationPending)
    }
}

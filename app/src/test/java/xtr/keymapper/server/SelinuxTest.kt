package xtr.keymapper.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Decision-logic tests only: fakes replace setenforce/getenforce, no real commands run
// and these do not prove SELinux behavior on a physical device.
class SelinuxTest {

    private class Fake(
        setenforceResults: List<Boolean> = emptyList(),
        modes: List<String?> = emptyList()
    ) {
        val setenforceCalls = mutableListOf<Int>()
        private val results = ArrayDeque(setenforceResults)
        private val modeAnswers = ArrayDeque(modes)
        val selinux = Selinux(
            setenforce = { mode ->
                setenforceCalls.add(mode)
                results.removeFirstOrNull() ?: false
            },
            getenforce = { modeAnswers.removeFirstOrNull() }
        )
    }

    @Test
    fun initialEnforcingIsRelaxedAndTracked() {
        val fake = Fake(setenforceResults = listOf(true), modes = listOf("Enforcing", "Permissive"))
        assertTrue(fake.selinux.relax())
        assertTrue(fake.selinux.isRelaxed)
        assertEquals(listOf(0), fake.setenforceCalls)
    }

    @Test
    fun initialPermissiveIsNotTrackedAsOurChange() {
        val fake = Fake(modes = listOf("Permissive"))
        assertFalse(fake.selinux.relax())
        assertFalse(fake.selinux.isRelaxed)
        assertTrue(fake.setenforceCalls.isEmpty())
        assertTrue(fake.selinux.restore())
        assertTrue(fake.setenforceCalls.isEmpty())
    }

    @Test
    fun failedSetenforceZeroLeavesStateUntracked() {
        val fake = Fake(setenforceResults = listOf(false), modes = listOf("Enforcing"))
        assertFalse(fake.selinux.relax())
        assertFalse(fake.selinux.isRelaxed)
        assertEquals(listOf(0), fake.setenforceCalls)
        // Nothing tracked -> stop must not run setenforce 1
        assertTrue(fake.selinux.restore())
        assertEquals(listOf(0), fake.setenforceCalls)
    }

    @Test
    fun successfulCommandWithFailedVerification() {
        // getenforce unreadable after a successful command: trust the exit code
        val unknown = Fake(setenforceResults = listOf(true), modes = listOf("Enforcing", null))
        assertTrue(unknown.selinux.relax())
        assertTrue(unknown.selinux.isRelaxed)

        // getenforce positively answers still Enforcing: command result is contradicted
        val contradicted = Fake(
            setenforceResults = listOf(true),
            modes = listOf("Enforcing", "Enforcing")
        )
        assertFalse(contradicted.selinux.relax())
        assertFalse(contradicted.selinux.isRelaxed)
        assertEquals(listOf(0), contradicted.setenforceCalls)
    }

    @Test
    fun systemAppPathNeverChangesSelinux() {
        val fake = Fake()
        // System-app stop: relax() was never called, restore must be a no-op
        assertTrue(fake.selinux.restore())
        assertFalse(fake.selinux.isRelaxed)
        assertTrue(fake.setenforceCalls.isEmpty())
    }

    @Test
    fun failedRestorationKeepsStateTrackedForRetry() {
        val fake = Fake(setenforceResults = listOf(true, false), modes = listOf("Enforcing", "Permissive"))
        assertTrue(fake.selinux.relax())
        assertFalse(fake.selinux.restore())
        assertTrue(fake.selinux.isRelaxed)
        assertEquals(listOf(0, 1), fake.setenforceCalls)
    }

    @Test
    fun repeatedShutdownAndRestartCyclesFully() {
        val fake = Fake(
            setenforceResults = listOf(true, true, true, true),
            modes = listOf(
                "Enforcing", "Permissive", "Enforcing",
                "Enforcing", "Permissive", "Enforcing"
            )
        )
        assertTrue(fake.selinux.relax())
        assertTrue(fake.selinux.restore())
        assertTrue(fake.selinux.relax())
        assertTrue(fake.selinux.restore())
        assertFalse(fake.selinux.isRelaxed)
        assertEquals(listOf(0, 1, 0, 1), fake.setenforceCalls)
    }
}

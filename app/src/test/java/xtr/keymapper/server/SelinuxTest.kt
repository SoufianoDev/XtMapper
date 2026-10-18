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
    fun initialEnforcingChangesToPermissiveAndVerifies() {
        val fake = Fake(setenforceResults = listOf(true), modes = listOf("Enforcing", "Permissive"))
        assertTrue(fake.selinux.relax())
        assertTrue(fake.selinux.restorationPending)
        assertEquals(listOf(0), fake.setenforceCalls)
    }

    @Test
    fun initialPermissiveIsNeverChangedOrRestored() {
        val fake = Fake(modes = listOf("Permissive"))
        assertFalse(fake.selinux.relax())
        assertFalse(fake.selinux.restorationPending)
        assertTrue(fake.setenforceCalls.isEmpty())
        // No unnecessary restoration may touch the pre-existing state
        assertTrue(fake.selinux.restore())
        assertTrue(fake.setenforceCalls.isEmpty())
    }

    @Test
    fun unknownOrInvalidInitialStateRunsNoCommand() {
        val unknown = Fake(modes = listOf(null))
        assertFalse(unknown.selinux.relax())
        assertFalse(unknown.selinux.restorationPending)
        assertTrue(unknown.setenforceCalls.isEmpty())

        val invalid = Fake(modes = listOf("Invalid-Mode"))
        assertFalse(invalid.selinux.relax())
        assertFalse(invalid.selinux.restorationPending)
        assertTrue(invalid.setenforceCalls.isEmpty())
    }

    @Test
    fun failedSetenforceZeroRecordsNoTransition() {
        val fake = Fake(setenforceResults = listOf(false), modes = listOf("Enforcing"))
        assertFalse(fake.selinux.relax())
        assertFalse(fake.selinux.restorationPending)
        assertEquals(listOf(0), fake.setenforceCalls)
        // Nothing pending -> stop must not run setenforce 1
        assertTrue(fake.selinux.restore())
        assertEquals(listOf(0), fake.setenforceCalls)
    }

    @Test
    fun successfulCommandWithUnknownVerificationIsUnverifiedButRecoverable() {
        val fake = Fake(
            setenforceResults = listOf(true, true),
            modes = listOf("Enforcing", null, "Enforcing")
        )
        // Command succeeded but the state could not be verified
        assertFalse(fake.selinux.relax())
        assertTrue(fake.selinux.restorationPending) // recovery information kept
        assertEquals(listOf(0), fake.setenforceCalls)
        // The pending change can still be recovered afterwards
        assertTrue(fake.selinux.restore())
        assertFalse(fake.selinux.restorationPending)
        assertEquals(listOf(0, 1), fake.setenforceCalls)
    }

    @Test
    fun restorationSucceedsOnlyWithVerifiedEnforcing() {
        val fake = Fake(setenforceResults = listOf(true, true), modes = listOf("Enforcing", "Permissive", "Enforcing"))
        assertTrue(fake.selinux.relax())
        assertTrue(fake.selinux.restore())
        assertFalse(fake.selinux.restorationPending)
        assertEquals(listOf(0, 1), fake.setenforceCalls)
    }

    @Test
    fun restorationWithUnknownVerificationStaysPending() {
        val fake = Fake(setenforceResults = listOf(true, true), modes = listOf("Enforcing", "Permissive", null))
        assertTrue(fake.selinux.relax())
        // setenforce 1 succeeded but Enforcing was never positively verified
        assertFalse(fake.selinux.restore())
        assertTrue(fake.selinux.restorationPending) // not cleared without proof
        assertEquals(listOf(0, 1), fake.setenforceCalls)
    }

    @Test
    fun failedRestorationKeepsPendingForRetry() {
        val fake = Fake(
            setenforceResults = listOf(true, false, true),
            modes = listOf("Enforcing", "Permissive", "Enforcing")
        )
        assertTrue(fake.selinux.relax())
        assertFalse(fake.selinux.restore())
        assertTrue(fake.selinux.restorationPending)
        assertEquals(listOf(0, 1), fake.setenforceCalls)
        // A later cleanup attempt retries and can succeed
        assertTrue(fake.selinux.restore())
        assertFalse(fake.selinux.restorationPending)
        assertEquals(listOf(0, 1, 1), fake.setenforceCalls)
    }

    @Test
    fun repeatedShutdownAndRestorationStayCorrect() {
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
        assertFalse(fake.selinux.restorationPending)
        assertEquals(listOf(0, 1, 0, 1), fake.setenforceCalls)
        // Extra shutdown after a completed restore changes nothing
        assertTrue(fake.selinux.restore())
        assertEquals(listOf(0, 1, 0, 1), fake.setenforceCalls)
    }

    @Test
    fun systemAppPathNeverRestores() {
        val fake = Fake()
        // System-app stop: relax() was never called, restore must be a no-op
        assertTrue(fake.selinux.restore())
        assertFalse(fake.selinux.restorationPending)
        assertTrue(fake.setenforceCalls.isEmpty())
    }
}

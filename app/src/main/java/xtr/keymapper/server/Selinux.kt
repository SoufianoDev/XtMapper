package xtr.keymapper.server

internal class Selinux(
    private val setenforce: (Int) -> Boolean,
    private val getenforce: () -> String?
) {
    var restorationPending: Boolean = false
        private set

    fun relax(): Boolean {
        if (getenforce() != ENFORCING) return false
        if (!setenforce(0)) return false
        return when (getenforce()) {
            PERMISSIVE -> {
                restorationPending = true
                true
            }
            ENFORCING -> false // command exited 0 but no change happened
            else -> {
                restorationPending = true
                false
            }
        }
    }

    fun restore(): Boolean {
        if (!restorationPending) return true
        if (!setenforce(1)) return false
        if (getenforce() != ENFORCING) return false // Permissive or unreadable: unverified
        restorationPending = false
        return true
    }

    companion object {
        private const val PERMISSIVE = "Permissive"
        private const val ENFORCING = "Enforcing"

        val real = Selinux(
            setenforce = { mode ->
                runCatching {
                    ProcessBuilder("setenforce", mode.toString()).start().waitFor() == 0
                }.getOrDefault(false)
            },
            getenforce = {
                runCatching {
                    val process = ProcessBuilder("getenforce").start()
                    val mode = process.inputStream.bufferedReader().use { it.readLine() }
                    if (process.waitFor() == 0) mode?.trim() else null
                }.getOrNull()
            }
        )
    }
}

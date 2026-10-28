package xtr.keymapper.log

/**
 * Structured diagnostic events for stable, searchable diagnostics.
 * Never pass profile serialization, macro contents, clipboard data, or typed text.
 */
object DiagnosticLog {
    private const val MAX_FIELD_LENGTH = 240
    private const val DEFAULT_RATE_LIMIT_INTERVAL_MS = 1_500L
    private const val MAX_RATE_LIMIT_KEYS = 256

    private val rateLimitedEvents = LinkedHashMap<String, Long>()

    @JvmStatic
    fun info(tag: String, event: String, vararg fields: Pair<String, Any?>) =
        XtLogger.info(tag, formatMessage(event, *fields))

    @JvmStatic
    fun debug(tag: String, event: String, vararg fields: Pair<String, Any?>) =
        XtLogger.debug(tag, formatMessage(event, *fields))

    /** Rate-limits repetitive DEBUG traces while preserving significant events. */
    @JvmStatic
    fun debugRateLimited(tag: String, event: String, rateLimitKey: String, vararg fields: Pair<String, Any?>) {
        val key = "$tag|$event|$rateLimitKey"
        val nowMs = System.nanoTime() / 1_000_000L
        if (shouldEmitRateLimited(key, nowMs, DEFAULT_RATE_LIMIT_INTERVAL_MS)) {
            debug(tag, event, *fields)
        }
    }

    @JvmStatic
    @Synchronized
    internal fun shouldEmitRateLimited(key: String, nowMs: Long, intervalMs: Long): Boolean {
        val previous = rateLimitedEvents[key]
        if (previous != null && nowMs >= previous && nowMs - previous < intervalMs) return false
        if (!rateLimitedEvents.containsKey(key) && rateLimitedEvents.size >= MAX_RATE_LIMIT_KEYS) {
            val cutoff = nowMs - 60_000L
            val iterator = rateLimitedEvents.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value < cutoff) iterator.remove()
            }
            while (rateLimitedEvents.size >= MAX_RATE_LIMIT_KEYS) {
                val oldest = rateLimitedEvents.entries.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
        }
        rateLimitedEvents[key] = nowMs
        return true
    }

    @JvmStatic
    fun warn(tag: String, event: String, vararg fields: Pair<String, Any?>) =
        XtLogger.warn(tag, formatMessage(event, *fields))

    @JvmStatic
    fun error(tag: String, event: String, throwable: Throwable? = null, vararg fields: Pair<String, Any?>) =
        XtLogger.error(tag, formatMessage(event, *fields), throwable)

    internal fun formatMessage(event: String, vararg fields: Pair<String, Any?>): String =
        buildString {
            append("event=").append(sanitize(event))
            fields.forEach { (key, value) ->
                append(' ').append(sanitizeKey(key)).append('=').append(sanitize(value?.toString() ?: "null"))
            }
        }

    private fun sanitizeKey(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '_' || it == '-' || it == '.') it else '_' }
            .joinToString("").take(80)

    private fun sanitize(value: String): String = buildString {
        for (character in value) {
            if (length >= MAX_FIELD_LENGTH) break
            append(when {
                character.isWhitespace() || character == '=' || character == '|' -> '_'
                character.isISOControl() -> '?'
                else -> character
            })
        }
    }
}

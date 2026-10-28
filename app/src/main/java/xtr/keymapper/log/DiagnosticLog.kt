package xtr.keymapper.log

/**
 * Structured diagnostic events for stable, searchable diagnostics.
 * Never pass profile serialization, macro contents, clipboard data, or typed text.
 */
object DiagnosticLog {
    private const val MAX_FIELD_LENGTH = 240

    @JvmStatic
    fun info(tag: String, event: String, vararg fields: Pair<String, Any?>) =
        XtLogger.info(tag, formatMessage(event, *fields))

    @JvmStatic
    fun debug(tag: String, event: String, vararg fields: Pair<String, Any?>) =
        XtLogger.debug(tag, formatMessage(event, *fields))

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

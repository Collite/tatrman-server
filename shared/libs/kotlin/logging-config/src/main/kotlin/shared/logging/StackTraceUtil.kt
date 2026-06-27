package shared.logging

object StackTraceUtil {
    private const val DEFAULT_MAX_LENGTH = 300

    fun formatStackTrace(
        throwable: Throwable,
        maxLength: Int = DEFAULT_MAX_LENGTH,
    ): String {
        val sb = StringBuilder()
        sb.append(throwable::class.simpleName)
        throwable.message?.let { sb.append(": ").append(it) }
        sb.append("\n")

        val stackTrace = throwable.stackTrace
        for (element in stackTrace.take(10)) {
            val line = "  at $element\n"
            if (sb.length + line.length > maxLength) {
                sb.append("  ... truncated")
                break
            }
            sb.append(line)
        }
        return sb.toString().take(maxLength)
    }
}

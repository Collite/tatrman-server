// SPDX-License-Identifier: Apache-2.0
package shared.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

object LoggingUtils {
    private val logger: Logger = LoggerFactory.getLogger("shared.logging")

    fun logError(
        logger: Logger,
        message: String,
        throwable: Throwable,
        userId: String? = null,
    ) {
        val stackTrace = StackTraceUtil.formatStackTrace(throwable)
        if (userId != null) {
            logger.error("{} userId={} stackTrace={}", message, userId, stackTrace)
        } else {
            logger.error("{} stackTrace={}", message, stackTrace)
        }
    }

    fun logErrorWithContext(
        logger: Logger,
        level: Level,
        message: String,
        context: Map<String, Any?>,
        throwable: Throwable,
    ) {
        val contextStr = context.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val stackTrace = StackTraceUtil.formatStackTrace(throwable)
        when (level) {
            Level.ERROR -> logger.error("{} {} stackTrace={}", message, contextStr, stackTrace)
            Level.WARN -> logger.warn("{} {} stackTrace={}", message, contextStr, stackTrace)
            else -> logger.info("{} {} stackTrace={}", message, contextStr, stackTrace)
        }
    }
}

package br.edu.fileshare.desktop.logging

import br.edu.fileshare.core.logging.FsapLogFormatter
import br.edu.fileshare.core.logging.FsapLogRecord
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.logging.LogLevel
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import org.slf4j.LoggerFactory

/**
 * Implementacao Desktop (SLF4J + Logback) de [FsapLogger].
 */
class Slf4jFsapLogger(
    private val clock: Clock = SystemClock,
) : FsapLogger {

    override fun log(
        level: LogLevel,
        component: String,
        message: String,
        context: LogContext,
        throwable: Throwable?,
    ) {
        val slf4j = LoggerFactory.getLogger("br.edu.fileshare.$component")
        val record = FsapLogRecord(
            timestamp = clock.nowIsoUtc(),
            level = level,
            component = component,
            message = message,
            context = context,
            throwable = throwable,
        )
        val formatted = FsapLogFormatter.format(record)
        when (level) {
            LogLevel.DEBUG -> if (throwable == null) slf4j.debug(formatted) else slf4j.debug(formatted, throwable)
            LogLevel.INFO -> if (throwable == null) slf4j.info(formatted) else slf4j.info(formatted, throwable)
            LogLevel.WARN -> if (throwable == null) slf4j.warn(formatted) else slf4j.warn(formatted, throwable)
            LogLevel.ERROR -> if (throwable == null) slf4j.error(formatted) else slf4j.error(formatted, throwable)
        }
    }
}


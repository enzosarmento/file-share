package br.edu.fileshare.android.logging

import android.util.Log
import br.edu.fileshare.core.logging.FsapLogFormatter
import br.edu.fileshare.core.logging.FsapLogRecord
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.logging.LogLevel
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock

/**
 * Implementacao Android (`android.util.Log`) de [FsapLogger].
 */
class AndroidFsapLogger(
    private val clock: Clock = SystemClock,
) : FsapLogger {

    override fun log(
        level: LogLevel,
        component: String,
        message: String,
        context: LogContext,
        throwable: Throwable?,
    ) {
        val tag = "FSAP/$component".take(23)
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
            LogLevel.DEBUG -> if (throwable == null) Log.d(tag, formatted) else Log.d(tag, formatted, throwable)
            LogLevel.INFO -> if (throwable == null) Log.i(tag, formatted) else Log.i(tag, formatted, throwable)
            LogLevel.WARN -> if (throwable == null) Log.w(tag, formatted) else Log.w(tag, formatted, throwable)
            LogLevel.ERROR -> if (throwable == null) Log.e(tag, formatted) else Log.e(tag, formatted, throwable)
        }
    }
}


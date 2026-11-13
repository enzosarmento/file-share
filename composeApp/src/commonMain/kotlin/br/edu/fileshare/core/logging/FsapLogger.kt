package br.edu.fileshare.core.logging

import br.edu.fileshare.core.concurrency.PlatformLock
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock

enum class LogLevel(val severity: Int) {
    DEBUG(10),
    INFO(20),
    WARN(30),
    ERROR(40),
}

/**
 * Contexto estruturado opcional associado a cada evento de log (RNF-10).
 */
data class LogContext(
    val deviceId: String? = null,
    val transferId: String? = null,
    val peerId: String? = null,
)

/**
 * Registro imutavel de evento de log capturado pelo sistema.
 */
data class FsapLogRecord(
    val timestamp: String,
    val level: LogLevel,
    val component: String,
    val message: String,
    val context: LogContext = LogContext(),
    val throwable: Throwable? = null,
)

/**
 * Formatador padronizado para linhas de log estruturado nas plataformas Desktop e Android.
 */
object FsapLogFormatter {
    fun format(record: FsapLogRecord): String = buildString {
        append('[').append(record.timestamp).append("] ")
        append('[').append(record.level.name).append("] ")
        append('[').append(record.component).append(']')

        val contextFields = buildList {
            record.context.deviceId?.takeIf { it.isNotBlank() }?.let { add("deviceId=$it") }
            record.context.transferId?.takeIf { it.isNotBlank() }?.let { add("transferId=$it") }
            record.context.peerId?.takeIf { it.isNotBlank() }?.let { add("peerId=$it") }
        }
        if (contextFields.isNotEmpty()) {
            append(" [").append(contextFields.joinToString(" ")).append(']')
        }

        append(' ').append(record.message)
        record.throwable?.let { error ->
            append(" | cause=").append(error::class.simpleName ?: "Throwable")
            error.message?.takeIf { it.isNotBlank() }?.let { msg ->
                append(": ").append(msg)
            }
        }
    }
}

/**
 * Porta multiplataforma de logging estruturado do projeto FileShare.
 * Nenhuma classe de protocolo ou rede deve depender diretamente de frameworks especificos.
 */
interface FsapLogger {
    fun log(
        level: LogLevel,
        component: String,
        message: String,
        context: LogContext = LogContext(),
        throwable: Throwable? = null,
    )

    fun debug(component: String, message: String, context: LogContext = LogContext()) =
        log(LogLevel.DEBUG, component, message, context)

    fun info(component: String, message: String, context: LogContext = LogContext()) =
        log(LogLevel.INFO, component, message, context)

    fun warn(
        component: String,
        message: String,
        context: LogContext = LogContext(),
        throwable: Throwable? = null,
    ) = log(LogLevel.WARN, component, message, context, throwable)

    fun error(
        component: String,
        message: String,
        context: LogContext = LogContext(),
        throwable: Throwable? = null,
    ) = log(LogLevel.ERROR, component, message, context, throwable)

    /**
     * Registra uma transicao formal de maquina de estados conforme requisito RNF-10.
     */
    fun logStateTransition(
        component: String,
        entityId: String,
        fromState: String,
        event: String,
        toState: String,
        context: LogContext = LogContext(),
    ) {
        info(
            component = component,
            message = "FSM[$entityId] $fromState --($event)--> $toState",
            context = context,
        )
    }
}

/**
 * Implementacao em memoria segura para testes unitarios e inspecao deterministica.
 */
class InMemoryFsapLogger(
    private val clock: Clock = SystemClock,
    private val minLevel: LogLevel = LogLevel.DEBUG,
) : FsapLogger {

    private val lock = PlatformLock()
    private val records = mutableListOf<FsapLogRecord>()

    override fun log(
        level: LogLevel,
        component: String,
        message: String,
        context: LogContext,
        throwable: Throwable?,
    ) {
        if (level.severity < minLevel.severity) return
        val record = FsapLogRecord(
            timestamp = clock.nowIsoUtc(),
            level = level,
            component = component,
            message = message,
            context = context,
            throwable = throwable,
        )
        lock.withLock {
            records += record
        }
    }

    fun snapshot(): List<FsapLogRecord> = lock.withLock { records.toList() }

    fun formattedSnapshot(): List<String> = snapshot().map(FsapLogFormatter::format)

    fun clear() {
        lock.withLock { records.clear() }
    }
}


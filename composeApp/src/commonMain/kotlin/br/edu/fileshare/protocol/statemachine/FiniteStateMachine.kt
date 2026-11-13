package br.edu.fileshare.protocol.statemachine

import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Excecao deterministica lancada quando um evento viola a tabela de transicoes permitidas.
 */
class InvalidStateTransitionException(
    val entityId: String,
    val currentState: Enum<*>,
    val event: Enum<*>,
    transferId: String? = null,
) : FsapProtocolException(
    errorCode = FsapErrorCode.INVALID_STATE_TRANSITION,
    message = "Transicao invalida em '$entityId': estado=${currentState.name}, evento=${event.name}",
    transferId = transferId,
)

/**
 * Motor deterministico generico para maquinas de estados do FSAP/1.0.
 * Garante exclusao mutua via [Mutex], execucao ordenada de `onExit`/`onEnter`,
 * notificacao reativa via [StateFlow] e registro estruturado conforme RNF-10.
 */
open class FiniteStateMachine<S : Enum<S>, E : Enum<E>>(
    private val entityId: String,
    private val componentName: String,
    initialState: S,
    private val transitions: Map<Pair<S, E>, S>,
    private val logger: FsapLogger? = null,
    private val transferIdForLogs: String? = null,
    private val onExit: suspend (S) -> Unit = {},
    private val onEnter: suspend (S) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    fun currentState(): S = _state.value

    fun canTransition(event: E): Boolean = (currentState() to event) in transitions

    suspend fun transition(event: E): S = mutex.withLock {
        val current = _state.value
        val next = transitions[current to event]
        if (next == null) {
            logger?.warn(
                component = componentName,
                message = "Rejected invalid transition on '$entityId': ${current.name} + ${event.name}",
                context = LogContext(transferId = transferIdForLogs),
            )
            throw InvalidStateTransitionException(
                entityId = entityId,
                currentState = current,
                event = event,
                transferId = transferIdForLogs,
            )
        }

        onExit(current)
        _state.value = next
        logger?.logStateTransition(
            component = componentName,
            entityId = entityId,
            fromState = current.name,
            event = event.name,
            toState = next.name,
            context = LogContext(transferId = transferIdForLogs),
        )
        onEnter(next)
        next
    }
}


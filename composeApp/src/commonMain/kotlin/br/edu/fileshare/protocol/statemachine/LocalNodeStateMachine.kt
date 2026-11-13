package br.edu.fileshare.protocol.statemachine

import br.edu.fileshare.core.logging.FsapLogger

enum class LocalNodeState {
    STOPPED,
    INITIALIZING,
    DISCOVERING,
    AVAILABLE,
    FAULTED,
    SHUTTING_DOWN,
}

enum class LocalNodeEvent {
    CMD_START_NODE,
    EVT_PORTS_BOUND,
    EVT_BIND_FAILED,
    EVT_INITIAL_ANNOUNCE_SENT,
    EVT_TRANSPORT_FATAL_ERROR,
    CMD_REFRESH_DISCOVERY,
    CMD_RETRY_START,
    CMD_STOP_NODE,
    EVT_CLEANUP_COMPLETED,
}

/**
 * Maquina de Estados 1: Ciclo de vida do no FSAP local (`docs/state-machine.md`, Secao 2).
 */
class LocalNodeStateMachine(
    nodeId: String = "local-node",
    logger: FsapLogger? = null,
    onExit: suspend (LocalNodeState) -> Unit = {},
    onEnter: suspend (LocalNodeState) -> Unit = {},
) : FiniteStateMachine<LocalNodeState, LocalNodeEvent>(
    entityId = nodeId,
    componentName = "LocalNodeFSM",
    initialState = LocalNodeState.STOPPED,
    transitions = TRANSITIONS,
    logger = logger,
    onExit = onExit,
    onEnter = onEnter,
) {
    companion object {
        val TRANSITIONS: Map<Pair<LocalNodeState, LocalNodeEvent>, LocalNodeState> = mapOf(
            (LocalNodeState.STOPPED to LocalNodeEvent.CMD_START_NODE) to LocalNodeState.INITIALIZING,
            (LocalNodeState.INITIALIZING to LocalNodeEvent.EVT_PORTS_BOUND) to LocalNodeState.DISCOVERING,
            (LocalNodeState.INITIALIZING to LocalNodeEvent.EVT_BIND_FAILED) to LocalNodeState.FAULTED,
            (LocalNodeState.DISCOVERING to LocalNodeEvent.EVT_INITIAL_ANNOUNCE_SENT) to LocalNodeState.AVAILABLE,
            (LocalNodeState.DISCOVERING to LocalNodeEvent.EVT_TRANSPORT_FATAL_ERROR) to LocalNodeState.FAULTED,
            (LocalNodeState.DISCOVERING to LocalNodeEvent.CMD_STOP_NODE) to LocalNodeState.SHUTTING_DOWN,
            (LocalNodeState.AVAILABLE to LocalNodeEvent.CMD_REFRESH_DISCOVERY) to LocalNodeState.DISCOVERING,
            (LocalNodeState.AVAILABLE to LocalNodeEvent.EVT_TRANSPORT_FATAL_ERROR) to LocalNodeState.FAULTED,
            (LocalNodeState.AVAILABLE to LocalNodeEvent.CMD_STOP_NODE) to LocalNodeState.SHUTTING_DOWN,
            (LocalNodeState.FAULTED to LocalNodeEvent.CMD_RETRY_START) to LocalNodeState.INITIALIZING,
            (LocalNodeState.FAULTED to LocalNodeEvent.CMD_STOP_NODE) to LocalNodeState.SHUTTING_DOWN,
            (LocalNodeState.SHUTTING_DOWN to LocalNodeEvent.EVT_CLEANUP_COMPLETED) to LocalNodeState.STOPPED,
        )
    }
}


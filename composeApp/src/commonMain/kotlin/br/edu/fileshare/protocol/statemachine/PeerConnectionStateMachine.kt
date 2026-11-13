package br.edu.fileshare.protocol.statemachine

import br.edu.fileshare.core.logging.FsapLogger

enum class PeerConnectionState {
    DISCONNECTED,
    DISCOVERED,
    STALE,
    CONNECTING,
    CONNECTED,
}

enum class PeerConnectionEvent {
    EVT_UDP_ANNOUNCE_RECEIVED,
    CMD_OPEN_PEER_CONNECTION,
    EVT_ANNOUNCE_MISSED_WARNING,
    EVT_PEER_TTL_EXPIRED,
    EVT_UDP_BYE_RECEIVED,
    EVT_IDENTIFY_SUCCESS,
    EVT_CONNECT_FAILED,
    EVT_HTTP_ACTIVITY,
    EVT_SESSION_IDLE_CLOSED,
    EVT_TCP_CONNECTION_LOST,
}

/**
 * Maquina de Estados 2: Presenca e conectividade de cada par remoto (`docs/state-machine.md`, Secao 3).
 */
class PeerConnectionStateMachine(
    peerId: String,
    initialState: PeerConnectionState = PeerConnectionState.DISCONNECTED,
    logger: FsapLogger? = null,
    onExit: suspend (PeerConnectionState) -> Unit = {},
    onEnter: suspend (PeerConnectionState) -> Unit = {},
) : FiniteStateMachine<PeerConnectionState, PeerConnectionEvent>(
    entityId = peerId,
    componentName = "PeerConnectionFSM",
    initialState = initialState,
    transitions = TRANSITIONS,
    logger = logger,
    onExit = onExit,
    onEnter = onEnter,
) {
    companion object {
        val TRANSITIONS: Map<Pair<PeerConnectionState, PeerConnectionEvent>, PeerConnectionState> = mapOf(
            (PeerConnectionState.DISCONNECTED to PeerConnectionEvent.EVT_UDP_ANNOUNCE_RECEIVED) to PeerConnectionState.DISCOVERED,
            (PeerConnectionState.DISCONNECTED to PeerConnectionEvent.CMD_OPEN_PEER_CONNECTION) to PeerConnectionState.CONNECTING,
            (PeerConnectionState.DISCOVERED to PeerConnectionEvent.EVT_UDP_ANNOUNCE_RECEIVED) to PeerConnectionState.DISCOVERED,
            (PeerConnectionState.DISCOVERED to PeerConnectionEvent.CMD_OPEN_PEER_CONNECTION) to PeerConnectionState.CONNECTING,
            (PeerConnectionState.DISCOVERED to PeerConnectionEvent.EVT_ANNOUNCE_MISSED_WARNING) to PeerConnectionState.STALE,
            (PeerConnectionState.DISCOVERED to PeerConnectionEvent.EVT_UDP_BYE_RECEIVED) to PeerConnectionState.DISCONNECTED,
            (PeerConnectionState.STALE to PeerConnectionEvent.EVT_UDP_ANNOUNCE_RECEIVED) to PeerConnectionState.DISCOVERED,
            (PeerConnectionState.STALE to PeerConnectionEvent.CMD_OPEN_PEER_CONNECTION) to PeerConnectionState.CONNECTING,
            (PeerConnectionState.STALE to PeerConnectionEvent.EVT_PEER_TTL_EXPIRED) to PeerConnectionState.DISCONNECTED,
            (PeerConnectionState.STALE to PeerConnectionEvent.EVT_UDP_BYE_RECEIVED) to PeerConnectionState.DISCONNECTED,
            (PeerConnectionState.CONNECTING to PeerConnectionEvent.EVT_IDENTIFY_SUCCESS) to PeerConnectionState.CONNECTED,
            (PeerConnectionState.CONNECTING to PeerConnectionEvent.EVT_CONNECT_FAILED) to PeerConnectionState.DISCONNECTED,
            (PeerConnectionState.CONNECTED to PeerConnectionEvent.EVT_UDP_ANNOUNCE_RECEIVED) to PeerConnectionState.CONNECTED,
            (PeerConnectionState.CONNECTED to PeerConnectionEvent.EVT_HTTP_ACTIVITY) to PeerConnectionState.CONNECTED,
            (PeerConnectionState.CONNECTED to PeerConnectionEvent.EVT_SESSION_IDLE_CLOSED) to PeerConnectionState.DISCOVERED,
            (PeerConnectionState.CONNECTED to PeerConnectionEvent.EVT_UDP_BYE_RECEIVED) to PeerConnectionState.DISCONNECTED,
            (PeerConnectionState.CONNECTED to PeerConnectionEvent.EVT_TCP_CONNECTION_LOST) to PeerConnectionState.DISCONNECTED,
        )
    }
}


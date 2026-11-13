package br.edu.fileshare.network.discovery

import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.statemachine.PeerConnectionEvent
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import br.edu.fileshare.protocol.statemachine.PeerConnectionStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Origem do registro de um par remoto no catalogo local.
 */
enum class PeerOrigin {
    MULTICAST,
    MANUAL_IP,
}

/**
 * Representacao imutavel de um dispositivo remoto descoberto ou conectado manualmente.
 */
data class DiscoveredPeer(
    val identity: DeviceIdentity,
    val ipAddress: String,
    val port: Int = identity.httpPort,
    val connectionState: PeerConnectionState,
    val origin: PeerOrigin,
    val lastSeenEpochMillis: Long,
    val firstSeenEpochMillis: Long,
) {
    val deviceId: String get() = identity.deviceId
    val alias: String get() = identity.alias
}

/**
 * Repositorio concorrente e reativo dos dispositivos conhecidos na rede local (`docs/state-machine.md`).
 * Indexado univocamente por `deviceId` e apoiado por [PeerConnectionStateMachine].
 */
class PeerRegistry(
    private val clock: Clock = SystemClock,
    private val logger: FsapLogger? = null,
) {
    private data class PeerEntry(
        var peer: DiscoveredPeer,
        val fsm: PeerConnectionStateMachine,
    )

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, PeerEntry>()
    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())

    val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    fun snapshot(): List<DiscoveredPeer> = _peers.value

    fun findById(deviceId: String): DiscoveredPeer? =
        _peers.value.firstOrNull { it.deviceId == deviceId }

    /**
     * Registra ou atualiza um dispositivo apos handshake TCP `IDENTIFY` bem-sucedido (`TASK-DISC-005`).
     */
    suspend fun registerManualPeer(
        identity: DeviceIdentity,
        ipAddress: String,
        port: Int = identity.httpPort,
    ): DiscoveredPeer = mutex.withLock {
        val now = clock.epochMillis()
        val normalizedIdentity = identity.copy(httpPort = port)
        val existing = entries[normalizedIdentity.deviceId]

        val fsm = existing?.fsm ?: PeerConnectionStateMachine(
            peerId = normalizedIdentity.deviceId,
            initialState = PeerConnectionState.DISCONNECTED,
            logger = logger,
        )

        val newState = transitionToConnected(fsm)
        val updated = DiscoveredPeer(
            identity = normalizedIdentity,
            ipAddress = ipAddress,
            port = port,
            connectionState = newState,
            origin = existing?.peer?.origin ?: PeerOrigin.MANUAL_IP,
            lastSeenEpochMillis = now,
            firstSeenEpochMillis = existing?.peer?.firstSeenEpochMillis ?: now,
        )
        entries[normalizedIdentity.deviceId] = PeerEntry(updated, fsm)
        publishSnapshotLocked()
        updated
    }

    /**
     * Registra ou renova um dispositivo a partir de um datagrama UDP `ANNOUNCE` (`TASK-DISC-004`).
     */
    suspend fun upsertFromAnnounce(
        identity: DeviceIdentity,
        ipAddress: String,
    ): DiscoveredPeer = mutex.withLock {
        val now = clock.epochMillis()
        val existing = entries[identity.deviceId]
        val fsm = existing?.fsm ?: PeerConnectionStateMachine(
            peerId = identity.deviceId,
            initialState = PeerConnectionState.DISCONNECTED,
            logger = logger,
        )

        val newState = when (fsm.currentState()) {
            PeerConnectionState.DISCONNECTED,
            PeerConnectionState.DISCOVERED,
            PeerConnectionState.STALE,
            PeerConnectionState.CONNECTED,
            -> fsm.transition(PeerConnectionEvent.EVT_UDP_ANNOUNCE_RECEIVED)

            PeerConnectionState.CONNECTING -> fsm.currentState()
        }

        val updated = DiscoveredPeer(
            identity = identity,
            ipAddress = ipAddress,
            port = identity.httpPort,
            connectionState = newState,
            origin = PeerOrigin.MULTICAST,
            lastSeenEpochMillis = now,
            firstSeenEpochMillis = existing?.peer?.firstSeenEpochMillis ?: now,
        )
        entries[identity.deviceId] = PeerEntry(updated, fsm)
        publishSnapshotLocked()
        updated
    }

    /**
     * Remove imediatamente um par que anunciou saida graciosa via `BYE`.
     */
    suspend fun removeByBye(
        deviceId: String,
        reason: String? = null,
    ): Boolean = mutex.withLock {
        val entry = entries.remove(deviceId) ?: return@withLock false
        val current = entry.fsm.currentState()
        if (current == PeerConnectionState.DISCOVERED ||
            current == PeerConnectionState.STALE ||
            current == PeerConnectionState.CONNECTED
        ) {
            entry.fsm.transition(PeerConnectionEvent.EVT_UDP_BYE_RECEIVED)
        }
        logger?.info(
            component = "PeerRegistry",
            message = "Par removido por BYE (reason=${reason ?: "nao informado"}).",
            context = LogContext(peerId = deviceId),
        )
        publishSnapshotLocked()
        true
    }

    /**
     * Marca um dispositivo previamente conectado como desconectado por perda de enlace ou ociosidade.
     */
    suspend fun markDisconnected(
        deviceId: String,
        connectionLost: Boolean = false,
    ): Boolean = mutex.withLock {
        val entry = entries[deviceId] ?: return@withLock false
        val newState = when (entry.fsm.currentState()) {
            PeerConnectionState.CONNECTED -> {
                val event = if (connectionLost) {
                    PeerConnectionEvent.EVT_TCP_CONNECTION_LOST
                } else {
                    PeerConnectionEvent.EVT_SESSION_IDLE_CLOSED
                }
                entry.fsm.transition(event)
            }

            PeerConnectionState.CONNECTING -> entry.fsm.transition(PeerConnectionEvent.EVT_CONNECT_FAILED)
            else -> entry.fsm.currentState()
        }
        if (newState == PeerConnectionState.DISCONNECTED) {
            entries.remove(deviceId)
        } else {
            entry.peer = entry.peer.copy(connectionState = newState)
        }
        publishSnapshotLocked()
        true
    }

    /**
     * Varre pares silenciosos: `> staleAfterMs` -> `STALE`; `> ttlAfterMs` -> removido (`DISCONNECTED`).
     * Pares manuais (`PeerOrigin.MANUAL_IP`) sem anuncio multicast permanecem enquanto nao cairem ou nao expirarem
     * caso explicitamente varridos.
     */
    suspend fun sweepExpired(
        staleAfterMs: Long = FsapConstants.T_PEER_STALE_MS,
        ttlAfterMs: Long = FsapConstants.T_PEER_TTL_MS,
    ): Int = mutex.withLock {
        val now = clock.epochMillis()
        var changed = false
        val toRemove = mutableListOf<String>()

        for ((deviceId, entry) in entries) {
            if (entry.peer.origin == PeerOrigin.MANUAL_IP) continue
            val elapsed = now - entry.peer.lastSeenEpochMillis
            val current = entry.fsm.currentState()

            if (elapsed >= ttlAfterMs && current == PeerConnectionState.STALE) {
                entry.fsm.transition(PeerConnectionEvent.EVT_PEER_TTL_EXPIRED)
                toRemove += deviceId
                changed = true
            } else if (elapsed >= ttlAfterMs && current == PeerConnectionState.DISCOVERED) {
                entry.fsm.transition(PeerConnectionEvent.EVT_ANNOUNCE_MISSED_WARNING)
                entry.fsm.transition(PeerConnectionEvent.EVT_PEER_TTL_EXPIRED)
                toRemove += deviceId
                changed = true
            } else if (elapsed >= staleAfterMs && current == PeerConnectionState.DISCOVERED) {
                val staleState = entry.fsm.transition(PeerConnectionEvent.EVT_ANNOUNCE_MISSED_WARNING)
                entry.peer = entry.peer.copy(connectionState = staleState)
                changed = true
            }
        }

        toRemove.forEach { entries.remove(it) }
        if (changed) {
            publishSnapshotLocked()
        }
        toRemove.size
    }

    suspend fun clear() = mutex.withLock {
        entries.clear()
        publishSnapshotLocked()
    }

    private suspend fun transitionToConnected(fsm: PeerConnectionStateMachine): PeerConnectionState =
        when (fsm.currentState()) {
            PeerConnectionState.DISCONNECTED,
            PeerConnectionState.DISCOVERED,
            PeerConnectionState.STALE,
            -> {
                fsm.transition(PeerConnectionEvent.CMD_OPEN_PEER_CONNECTION)
                fsm.transition(PeerConnectionEvent.EVT_IDENTIFY_SUCCESS)
            }

            PeerConnectionState.CONNECTING ->
                fsm.transition(PeerConnectionEvent.EVT_IDENTIFY_SUCCESS)

            PeerConnectionState.CONNECTED ->
                fsm.transition(PeerConnectionEvent.EVT_HTTP_ACTIVITY)
        }

    private fun publishSnapshotLocked() {
        _peers.value = entries.values
            .map { it.peer }
            .sortedWith(compareBy({ it.alias.lowercase() }, { it.deviceId }))
    }
}


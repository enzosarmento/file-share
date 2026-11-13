package br.edu.fileshare.network.discovery

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orquestrador completo do plano de descoberta UDP Multicast (`TASK-DISC-001`..`004` + `TASK-DCLI-002`):
 * gerencia transporte UDP, emissor periodico, listener com jitter e varredura TTL da `PeerRegistry`.
 */
class DiscoveryCoordinator(
    private val transport: MulticastTransport,
    private val peerRegistry: PeerRegistry,
    private val localIdentityProvider: () -> DeviceIdentity,
    private val scope: CoroutineScope,
    uuidGenerator: UuidGenerator = PlatformUuidGenerator,
    clock: Clock = SystemClock,
    announceIntervalMs: Long = DiscoveryDefaults.ANNOUNCE_INTERVAL_MS,
    sweepIntervalMs: Long = DiscoveryDefaults.PEER_SWEEP_INTERVAL_MS,
    staleAfterMs: Long = FsapConstants.T_PEER_STALE_MS,
    ttlAfterMs: Long = FsapConstants.T_PEER_TTL_MS,
    private val logger: FsapLogger? = null,
) {
    private val lifecycleMutex = Mutex()
    private var started: Boolean = false

    val broadcaster: DiscoveryBroadcaster = DiscoveryBroadcaster(
        transport = transport,
        localIdentityProvider = localIdentityProvider,
        scope = scope,
        uuidGenerator = uuidGenerator,
        clock = clock,
        announceIntervalMs = announceIntervalMs,
        logger = logger,
    )

    val listener: DiscoveryListener = DiscoveryListener(
        transport = transport,
        localDeviceId = localIdentityProvider().deviceId,
        peerRegistry = peerRegistry,
        scope = scope,
        onDiscoverRespond = { broadcaster.sendAnnounceNow() },
        logger = logger,
    )

    val sweeper: PeerRegistrySweeper = PeerRegistrySweeper(
        peerRegistry = peerRegistry,
        scope = scope,
        sweepIntervalMs = sweepIntervalMs,
        staleAfterMs = staleAfterMs,
        ttlAfterMs = ttlAfterMs,
        logger = logger,
    )

    val isRunning: Boolean
        get() = started

    suspend fun start() {
        lifecycleMutex.withLock {
            if (started) return
            transport.open()
            try {
                listener.start()
                broadcaster.start()
                sweeper.start()
                started = true
            } catch (ex: Throwable) {
                runCatching { sweeper.stop() }
                runCatching { listener.stop() }
                runCatching { transport.close() }
                throw ex
            }
        }
    }

    /**
     * Inicia a descoberta UDP sem derrubar o servidor HTTP caso o ambiente de rede bloqueie multicast
     * (preservando o fallback obrigatorio de Conexao Manual por IP `RF-05`).
     */
    suspend fun startBestEffort(): Boolean = runCatching {
        start()
        true
    }.getOrElse { error ->
        logger?.warn(
            component = "DiscoveryCoordinator",
            message = "Descoberta UDP multicast indisponivel; conexao manual por IP permanece ativa.",
            context = LogContext(deviceId = localIdentityProvider().deviceId),
            throwable = error,
        )
        false
    }

    suspend fun refresh(): Result<Unit> = runCatching {
        if (started && transport.isOpen) {
            broadcaster.triggerDiscover()
        }
    }

    suspend fun stop(reason: String = "USER_SHUTDOWN") {
        lifecycleMutex.withLock {
            if (!started && !transport.isOpen) return
            started = false
            runCatching { sweeper.stop() }
            runCatching { broadcaster.stop(reason = reason) }
            runCatching { listener.stop() }
            runCatching { transport.close() }
        }
    }
}


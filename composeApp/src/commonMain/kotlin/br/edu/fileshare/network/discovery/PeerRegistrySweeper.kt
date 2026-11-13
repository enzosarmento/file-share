package br.edu.fileshare.network.discovery

import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.protocol.FsapConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tarefa periodica que varre [PeerRegistry] a cada 3 s (`T_PEER_SWEEP_INTERVAL`)
 * para marcar pares silenciosos como `STALE` apos 12 s e expira-los apos 18 s (`TASK-DISC-004`).
 */
class PeerRegistrySweeper(
    private val peerRegistry: PeerRegistry,
    private val scope: CoroutineScope,
    private val sweepIntervalMs: Long = DiscoveryDefaults.PEER_SWEEP_INTERVAL_MS,
    private val staleAfterMs: Long = FsapConstants.T_PEER_STALE_MS,
    private val ttlAfterMs: Long = FsapConstants.T_PEER_TTL_MS,
    private val logger: FsapLogger? = null,
) {
    private val mutex = Mutex()
    private var sweepJob: Job? = null

    suspend fun start() {
        mutex.withLock {
            if (sweepJob?.isActive == true) return
            sweepJob = scope.launch {
                while (isActive) {
                    delay(sweepIntervalMs)
                    try {
                        val expiredCount = peerRegistry.sweepExpired(
                            staleAfterMs = staleAfterMs,
                            ttlAfterMs = ttlAfterMs,
                        )
                        if (expiredCount > 0) {
                            logger?.info(
                                component = "PeerRegistrySweeper",
                                message = "Varredura expirou $expiredCount par(es) inativo(s) por TTL.",
                            )
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ex: Throwable) {
                        logger?.warn(
                            component = "PeerRegistrySweeper",
                            message = "Erro inesperado na varredura TTL de pares.",
                            throwable = ex,
                        )
                    }
                }
            }
        }
    }

    suspend fun stop() {
        val job = mutex.withLock {
            sweepJob.also { sweepJob = null }
        }
        job?.cancelAndJoin()
    }
}


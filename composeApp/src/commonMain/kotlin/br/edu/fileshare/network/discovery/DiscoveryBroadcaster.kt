package br.edu.fileshare.network.discovery

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.AnnouncePayload
import br.edu.fileshare.protocol.model.ByePayload
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DiscoverPayload
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
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
 * Servico emissor de mensagens UDP Multicast (`DISCOVER`, `ANNOUNCE` periodico a cada 5s e `BYE`)
 * do plano de descoberta FSAP/1.0 (`TASK-DISC-002`).
 */
class DiscoveryBroadcaster(
    private val transport: MulticastTransport,
    private val localIdentityProvider: () -> DeviceIdentity,
    private val scope: CoroutineScope,
    private val uuidGenerator: UuidGenerator = PlatformUuidGenerator,
    private val clock: Clock = SystemClock,
    private val announceIntervalMs: Long = DiscoveryDefaults.ANNOUNCE_INTERVAL_MS,
    private val maxDatagramBytes: Int = DiscoveryDefaults.MAX_DATAGRAM_BYTES,
    private val logger: FsapLogger? = null,
) {
    private val stateMutex = Mutex()
    private var periodicJob: Job? = null
    private var running: Boolean = false

    suspend fun start() {
        stateMutex.withLock {
            if (running) return
            running = true
        }

        runCatching { sendDiscoverNow() }
            .onFailure { warnSendFailure("DISCOVER inicial", it) }
        runCatching { sendAnnounceNow() }
            .onFailure { warnSendFailure("ANNOUNCE inicial", it) }

        val launched = scope.launch {
            while (isActive) {
                delay(announceIntervalMs)
                if (!running) break
                try {
                    sendAnnounceNow()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ex: Throwable) {
                    warnSendFailure("ANNOUNCE periodico", ex)
                }
            }
        }
        stateMutex.withLock {
            if (!running) {
                launched.cancel()
            } else {
                periodicJob = launched
            }
        }
    }

    suspend fun triggerDiscover() {
        sendDiscoverNow()
    }

    suspend fun sendAnnounceNow() {
        val identity = localIdentityProvider()
        val envelope = FsapEnvelopeFactory.create(
            type = FsapMessageType.ANNOUNCE,
            senderId = identity.deviceId,
            payload = AnnouncePayload(
                alias = identity.alias,
                deviceType = identity.deviceType,
                platform = identity.platform,
                httpPort = identity.httpPort,
                capabilities = identity.capabilities,
            ),
            uuidGenerator = uuidGenerator,
            clock = clock,
        )
        encodeAndSend(FsapJson.encodeEnvelope(envelope), identity.deviceId, "ANNOUNCE")
    }

    suspend fun sendDiscoverNow() {
        val identity = localIdentityProvider()
        val envelope = FsapEnvelopeFactory.create(
            type = FsapMessageType.DISCOVER,
            senderId = identity.deviceId,
            payload = DiscoverPayload(
                requesterAlias = identity.alias,
                requesterHttpPort = identity.httpPort,
            ),
            uuidGenerator = uuidGenerator,
            clock = clock,
        )
        encodeAndSend(FsapJson.encodeEnvelope(envelope), identity.deviceId, "DISCOVER")
    }

    suspend fun stop(reason: String = "USER_SHUTDOWN") {
        val jobToCancel = stateMutex.withLock {
            if (!running && periodicJob == null) return
            running = false
            periodicJob.also { periodicJob = null }
        }
        jobToCancel?.cancelAndJoin()

        val safeReason = reason.trim().take(FsapConstants.MAX_ALIAS_LENGTH).ifEmpty { null }
        val identity = localIdentityProvider()
        val byeEnvelope = FsapEnvelopeFactory.create(
            type = FsapMessageType.BYE,
            senderId = identity.deviceId,
            payload = ByePayload(reason = safeReason),
            uuidGenerator = uuidGenerator,
            clock = clock,
        )
        runCatching {
            encodeAndSend(FsapJson.encodeEnvelope(byeEnvelope), identity.deviceId, "BYE")
        }.onFailure { warnSendFailure("BYE de encerramento", it) }
    }

    private suspend fun encodeAndSend(json: String, senderId: String, typeLabel: String) {
        val bytes = json.encodeToByteArray()
        if (bytes.size > maxDatagramBytes) {
            logger?.warn(
                component = "DiscoveryBroadcaster",
                message = "Datagrama $typeLabel descartado antes do envio por exceder $maxDatagramBytes bytes (${bytes.size} bytes).",
                context = LogContext(deviceId = senderId),
            )
            return
        }
        transport.send(bytes)
    }

    private fun warnSendFailure(label: String, error: Throwable) {
        logger?.warn(
            component = "DiscoveryBroadcaster",
            message = "Falha ao emitir $label via UDP multicast.",
            context = LogContext(deviceId = localIdentityProvider().deviceId),
            throwable = error,
        )
    }
}

package br.edu.fileshare.network.discovery

import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.protocol.model.AnnouncePayload
import br.edu.fileshare.protocol.model.ByePayload
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DiscoverPayload
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.validation.FsapMessageValidator
import br.edu.fileshare.protocol.validation.ValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Listener continuo de datagramas UDP Multicast (`239.255.70.83:53318`) responsavel por
 * filtrar auto-descoberta, descartar pacotes invalidos silenciosamente, agendar respostas a
 * `DISCOVER` com jitter de 50..300 ms e atualizar [PeerRegistry] (`TASK-DISC-003`).
 */
class DiscoveryListener(
    private val transport: MulticastTransport,
    private val localDeviceId: String,
    private val peerRegistry: PeerRegistry,
    private val scope: CoroutineScope,
    private val onDiscoverRespond: suspend () -> Unit = {},
    private val jitterMillisProvider: () -> Long = {
        Random.nextLong(
            from = DiscoveryDefaults.DISCOVER_JITTER_MIN_MS,
            until = DiscoveryDefaults.DISCOVER_JITTER_MAX_MS + 1L,
        )
    },
    private val logger: FsapLogger? = null,
) {
    private val stateMutex = Mutex()
    private val jitterMutex = Mutex()
    private var receiveJob: Job? = null
    private var pendingDiscoverResponseJob: Job? = null
    private var running: Boolean = false

    suspend fun start() {
        stateMutex.withLock {
            if (running) return
            running = true
            receiveJob = scope.launch {
                try {
                    while (isActive && running) {
                        val datagram = try {
                            transport.receive()
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (ex: Throwable) {
                            if (!running || !transport.isOpen) break
                            logger?.warn(
                                component = "DiscoveryListener",
                                message = "Erro transitorio ao ler socket UDP multicast.",
                                context = LogContext(deviceId = localDeviceId),
                                throwable = ex,
                            )
                            continue
                        }
                        if (datagram == null) break
                        try {
                            processDatagram(datagram)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (ex: Throwable) {
                            logger?.warn(
                                component = "DiscoveryListener",
                                message = "Falha inesperada ao processar datagrama UDP descartada silenciosamente.",
                                context = LogContext(deviceId = localDeviceId),
                                throwable = ex,
                            )
                        }
                    }
                } finally {
                    running = false
                }
            }
        }
    }

    suspend fun stop() {
        val (jobToStop, jitterJobToStop) = stateMutex.withLock {
            running = false
            val r = receiveJob.also { receiveJob = null }
            val j = jitterMutex.withLock { pendingDiscoverResponseJob.also { pendingDiscoverResponseJob = null } }
            r to j
        }
        jitterJobToStop?.cancelAndJoin()
        jobToStop?.cancelAndJoin()
    }

    internal suspend fun processDatagram(datagram: UdpDatagram) {
        if (datagram.bytes.isEmpty() || datagram.bytes.size > DiscoveryDefaults.MAX_DATAGRAM_BYTES) {
            warnDropped("Datagrama UDP com tamanho invalido (${datagram.bytes.size} bytes).")
            return
        }
        val rawJson = try {
            datagram.bytes.decodeToString()
        } catch (ex: Throwable) {
            warnDropped("Datagrama UDP com bytes UTF-8 invalidos.", ex)
            return
        }

        val rawEnvelope = try {
            FsapJson.decodeRawEnvelope(rawJson)
        } catch (ex: Throwable) {
            warnDropped("Datagrama UDP nao e envelope JSON FSAP valido.", ex)
            return
        }

        val envValidation = FsapMessageValidator.validateEnvelope(rawEnvelope)
        if (envValidation is ValidationResult.Invalid) {
            warnDropped("Envelope UDP rejeitado (${envValidation.errorCode.code}): ${envValidation.reason}")
            return
        }

        if (rawEnvelope.senderId == localDeviceId) {
            return
        }

        when (rawEnvelope.type) {
            FsapMessageType.ANNOUNCE.name -> handleAnnounce(rawJson, datagram.sourceIp)
            FsapMessageType.DISCOVER.name -> handleDiscover(rawJson)
            FsapMessageType.BYE.name -> handleBye(rawJson)
            else -> warnDropped("Tipo de mensagem '${rawEnvelope.type}' ignorado no plano multicast UDP.")
        }
    }

    private suspend fun handleAnnounce(rawJson: String, sourceIp: String) {
        if (sourceIp.isBlank()) {
            warnDropped("ANNOUNCE descartado por ausencia de IP de origem no pacote UDP.")
            return
        }
        val envelope = try {
            FsapJson.decodeEnvelope<AnnouncePayload>(rawJson)
        } catch (ex: Throwable) {
            warnDropped("Payload ANNOUNCE malformado.", ex)
            return
        }

        val check = FsapMessageValidator.validateAnnounce(envelope)
        if (check is ValidationResult.Invalid) {
            warnDropped("ANNOUNCE invalido (${check.errorCode.code}): ${check.reason}")
            return
        }

        val payload = envelope.payload
        val identity = DeviceIdentity(
            deviceId = envelope.senderId,
            alias = payload.alias.trim(),
            deviceType = payload.deviceType,
            platform = payload.platform,
            httpPort = payload.httpPort,
            capabilities = payload.capabilities,
        )
        peerRegistry.upsertFromAnnounce(identity = identity, ipAddress = sourceIp)
    }

    private suspend fun handleDiscover(rawJson: String) {
        val envelope = try {
            FsapJson.decodeEnvelope<DiscoverPayload>(rawJson)
        } catch (ex: Throwable) {
            warnDropped("Payload DISCOVER malformado.", ex)
            return
        }

        val check = FsapMessageValidator.validateDiscover(envelope)
        if (check is ValidationResult.Invalid) {
            warnDropped("DISCOVER invalido (${check.errorCode.code}): ${check.reason}")
            return
        }

        jitterMutex.withLock {
            if (pendingDiscoverResponseJob?.isActive == true) {
                return
            }
            val jitterMs = jitterMillisProvider().coerceIn(
                DiscoveryDefaults.DISCOVER_JITTER_MIN_MS,
                DiscoveryDefaults.DISCOVER_JITTER_MAX_MS,
            )
            pendingDiscoverResponseJob = scope.launch {
                try {
                    delay(jitterMs)
                    if (running) {
                        onDiscoverRespond()
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ex: Throwable) {
                    logger?.warn(
                        component = "DiscoveryListener",
                        message = "Falha ao enviar ANNOUNCE de resposta ao DISCOVER.",
                        context = LogContext(deviceId = localDeviceId),
                        throwable = ex,
                    )
                }
            }
        }
    }

    private suspend fun handleBye(rawJson: String) {
        val envelope = try {
            FsapJson.decodeEnvelope<ByePayload>(rawJson)
        } catch (ex: Throwable) {
            warnDropped("Payload BYE malformado.", ex)
            return
        }

        val check = FsapMessageValidator.validateBye(envelope)
        if (check is ValidationResult.Invalid) {
            warnDropped("BYE invalido (${check.errorCode.code}): ${check.reason}")
            return
        }

        peerRegistry.removeByBye(
            deviceId = envelope.senderId,
            reason = envelope.payload.reason,
        )
    }

    private fun warnDropped(reason: String, error: Throwable? = null) {
        logger?.warn(
            component = "DiscoveryListener",
            message = reason,
            context = LogContext(deviceId = localDeviceId),
            throwable = error,
        )
    }
}


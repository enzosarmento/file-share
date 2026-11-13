package br.edu.fileshare.network.client

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.ErrorPayload
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapHeaders
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.TransferAcceptPayload
import br.edu.fileshare.protocol.model.TransferRejectPayload
import br.edu.fileshare.protocol.model.TransferRequestPayload
import br.edu.fileshare.protocol.statemachine.TransferEvent
import br.edu.fileshare.protocol.statemachine.TransferStateMachine
import br.edu.fileshare.protocol.validation.FsapMessageValidator
import br.edu.fileshare.protocol.validation.ValidationResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/**
 * Endereco HTTP/TCP de um par remoto na rede local.
 */
data class FsapPeerEndpoint(
    val host: String,
    val port: Int = FsapConstants.DEFAULT_HTTP_PORT,
    val scheme: String = "http",
) {
    fun url(route: String): String {
        val normalizedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        return "$scheme://$normalizedHost:$port$route"
    }
}

/**
 * Sessao de envio autorizada apos `TRANSFER_ACCEPT` (`TASK-XFER-002`).
 */
data class PreparedTransferSession(
    val transferId: String,
    val endpoint: FsapPeerEndpoint,
    val transferToken: String,
    val acceptedFileIds: Set<String>,
    val acceptedFiles: List<FileItemDto>,
    val chunkSizeBytes: Int,
    val stateMachine: TransferStateMachine,
)

/**
 * Resultado tipado da fase de preparacao (`POST /fsap/v1/transfers/prepare`).
 */
sealed interface PrepareTransferResult {
    data class Accepted(val session: PreparedTransferSession) : PrepareTransferResult

    data class Rejected(
        val reject: TransferRejectPayload,
        val stateMachine: TransferStateMachine,
    ) : PrepareTransferResult

    data class Failed(
        val errorCode: FsapErrorCode,
        val message: String,
        val stateMachine: TransferStateMachine,
        val cause: Throwable? = null,
    ) : PrepareTransferResult
}

/**
 * Cliente HTTP/TCP FSAP/1.0 sobre Ktor CIO (`TASK-XFER-002`).
 * Responsavel por negociar transferencias (`TRANSFER_REQUEST` -> `ACCEPT`/`REJECT`/`ERROR`)
 * conduzindo a [TransferStateMachine] do lado remetente sem dependencia de UI.
 */
class FsapHttpClient(
    private val localIdentity: DeviceIdentity,
    private val logger: FsapLogger? = null,
    private val clock: Clock = SystemClock,
    private val uuidGenerator: UuidGenerator = PlatformUuidGenerator,
    private val connectTimeoutMs: Long = FsapConstants.T_TCP_CONNECT_TIMEOUT_MS,
    private val approvalTimeoutMs: Long = FsapConstants.T_APPROVAL_TIMEOUT_MS,
    private val streamTimeoutMs: Long = FsapConstants.T_STREAM_READ_TIMEOUT_MS,
    providedHttpClient: HttpClient? = null,
) {
    private val ownsHttpClient: Boolean = providedHttpClient == null
    internal val httpClient: HttpClient = providedHttpClient ?: createDefaultHttpClient(
        connectTimeoutMs = connectTimeoutMs,
        streamTimeoutMs = streamTimeoutMs,
    )

    suspend fun prepareTransfer(
        endpoint: FsapPeerEndpoint,
        files: List<FileItemDto>,
        transferId: String = uuidGenerator.nextUuidV4(),
        stateMachine: TransferStateMachine = TransferStateMachine(transferId = transferId, logger = logger),
    ): PrepareTransferResult {
        if (!FsapMessageValidator.isValidUuidV4(transferId)) {
            stateMachine.transitionIfPossible(TransferEvent.PREPARATION_ERROR)
            return PrepareTransferResult.Failed(
                errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                message = "transferId invalido: '$transferId'.",
                stateMachine = stateMachine,
            )
        }

        val totalBytes = computeSafeTotalBytes(files)
            ?: run {
                stateMachine.transitionIfPossible(TransferEvent.PREPARATION_ERROR)
                return PrepareTransferResult.Failed(
                    errorCode = FsapErrorCode.FILE_TOO_LARGE,
                    message = "Estouro aritmetico ao somar totalBytes do manifesto.",
                    stateMachine = stateMachine,
                )
            }

        val requestPayload = TransferRequestPayload(
            transferId = transferId,
            senderAlias = localIdentity.alias,
            senderDeviceType = localIdentity.deviceType,
            totalBytes = totalBytes,
            files = files,
        )
        val requestEnvelope = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_REQUEST,
            senderId = localIdentity.deviceId,
            payload = requestPayload,
            uuidGenerator = uuidGenerator,
            clock = clock,
        )

        val localValidation = FsapMessageValidator.validateTransferRequest(requestEnvelope)
        if (localValidation is ValidationResult.Invalid) {
            stateMachine.transitionIfPossible(TransferEvent.PREPARATION_ERROR)
            return PrepareTransferResult.Failed(
                errorCode = localValidation.errorCode,
                message = localValidation.reason,
                stateMachine = stateMachine,
            )
        }

        stateMachine.transition(TransferEvent.TRANSFER_REQUEST_SENT_OR_RECEIVED)
        logger?.info(
            component = "FsapHttpClient",
            message = "Enviando TRANSFER_REQUEST (${files.size} arquivo(s), $totalBytes B) para ${endpoint.host}:${endpoint.port}.",
            context = LogContext(deviceId = localIdentity.deviceId, transferId = transferId),
        )

        val targetUrl = endpoint.url(FsapConstants.Routes.TRANSFERS_PREPARE)
        val encodedBody = FsapJson.encodeEnvelope(requestEnvelope)

        val response = try {
            withTimeoutOrNull(approvalTimeoutMs + CLIENT_APPROVAL_GRACE_MS) {
                httpClient.post(targetUrl) {
                    applyStandardFsapHeaders()
                    contentType(ContentType.Application.Json)
                    applyOptionalTimeouts(
                        connectMs = connectTimeoutMs,
                        socketMs = approvalTimeoutMs + CLIENT_APPROVAL_GRACE_MS,
                        requestMs = approvalTimeoutMs + CLIENT_APPROVAL_GRACE_MS,
                    )
                    setBody(encodedBody)
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                stateMachine.transitionIfPossible(TransferEvent.CANCEL_TRANSFER)
            }
            throw cancelled
        } catch (timeoutEx: HttpRequestTimeoutException) {
            stateMachine.transitionIfPossible(TransferEvent.APPROVAL_TIMEOUT)
            return PrepareTransferResult.Failed(
                errorCode = FsapErrorCode.APPROVAL_TIMEOUT,
                message = "Tempo limite excedido aguardando aprovacao em ${endpoint.host}:${endpoint.port}.",
                stateMachine = stateMachine,
                cause = timeoutEx,
            )
        } catch (ex: Throwable) {
            stateMachine.transitionIfPossible(TransferEvent.CONNECTION_LOST)
            return PrepareTransferResult.Failed(
                errorCode = FsapErrorCode.CONNECTION_LOST,
                message = "Falha de rede durante TRANSFER_REQUEST para ${endpoint.host}:${endpoint.port}: ${ex.message}",
                stateMachine = stateMachine,
                cause = ex,
            )
        }

        if (response == null) {
            stateMachine.transitionIfPossible(TransferEvent.APPROVAL_TIMEOUT)
            return PrepareTransferResult.Failed(
                errorCode = FsapErrorCode.APPROVAL_TIMEOUT,
                message = "Timeout de aprovacao apos ${approvalTimeoutMs}ms aguardando resposta de ${endpoint.host}:${endpoint.port}.",
                stateMachine = stateMachine,
            )
        }

        return processPrepareResponse(
            response = response,
            endpoint = endpoint,
            transferId = transferId,
            requestedFiles = files,
            stateMachine = stateMachine,
        )
    }

    private suspend fun processPrepareResponse(
        response: HttpResponse,
        endpoint: FsapPeerEndpoint,
        transferId: String,
        requestedFiles: List<FileItemDto>,
        stateMachine: TransferStateMachine,
    ): PrepareTransferResult {
        val rawBody = response.bodyAsText()
        if (response.status == HttpStatusCode.OK) {
            val rawEnvelope = try {
                FsapJson.decodeRawEnvelope(rawBody)
            } catch (ex: SerializationException) {
                stateMachine.transitionIfPossible(TransferEvent.CONNECTION_LOST)
                return PrepareTransferResult.Failed(
                    errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                    message = "Resposta TRANSFER_ACCEPT invalida recebida do servidor.",
                    stateMachine = stateMachine,
                    cause = ex,
                )
            }

            if (rawEnvelope.type != FsapMessageType.TRANSFER_ACCEPT.name) {
                stateMachine.transitionIfPossible(TransferEvent.CONNECTION_LOST)
                return PrepareTransferResult.Failed(
                    errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                    message = "Esperado TRANSFER_ACCEPT, recebido '${rawEnvelope.type}'.",
                    stateMachine = stateMachine,
                )
            }

            val envelope = try {
                FsapJson.decodeEnvelope<TransferAcceptPayload>(rawBody)
            } catch (ex: SerializationException) {
                stateMachine.transitionIfPossible(TransferEvent.CONNECTION_LOST)
                return PrepareTransferResult.Failed(
                    errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                    message = "Payload TRANSFER_ACCEPT malformado: ${ex.message}",
                    stateMachine = stateMachine,
                    cause = ex,
                )
            }

            val payload = envelope.payload
            if (!payload.transferId.equals(transferId, ignoreCase = true) ||
                payload.transferToken.isBlank() ||
                payload.acceptedFileIds.isEmpty()
            ) {
                stateMachine.transitionIfPossible(TransferEvent.CONNECTION_LOST)
                return PrepareTransferResult.Failed(
                    errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                    message = "TRANSFER_ACCEPT invalido (token ausente, lista vazia ou transferId divergente).",
                    stateMachine = stateMachine,
                )
            }

            val requestedById = requestedFiles.associateBy { it.fileId }
            val acceptedIds = payload.acceptedFileIds.toSet()
            if (!acceptedIds.all { it in requestedById }) {
                stateMachine.transitionIfPossible(TransferEvent.CONNECTION_LOST)
                return PrepareTransferResult.Failed(
                    errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                    message = "TRANSFER_ACCEPT referenciou fileIds nao solicitados.",
                    stateMachine = stateMachine,
                )
            }

            stateMachine.transition(TransferEvent.TRANSFER_ACCEPTED)
            val session = PreparedTransferSession(
                transferId = transferId,
                endpoint = endpoint,
                transferToken = payload.transferToken,
                acceptedFileIds = acceptedIds,
                acceptedFiles = requestedFiles.filter { it.fileId in acceptedIds },
                chunkSizeBytes = payload.chunkSizeBytes.coerceAtLeast(1024),
                stateMachine = stateMachine,
            )
            return PrepareTransferResult.Accepted(session)
        }

        val rawType = runCatching { FsapJson.decodeRawEnvelope(rawBody).type }.getOrNull()
        if (response.status == HttpStatusCode.Forbidden && rawType == FsapMessageType.TRANSFER_REJECT.name) {
            val rejectEnvelope = runCatching { FsapJson.decodeEnvelope<TransferRejectPayload>(rawBody) }.getOrNull()
            if (rejectEnvelope != null) {
                stateMachine.transition(TransferEvent.TRANSFER_REJECTED)
                return PrepareTransferResult.Rejected(
                    reject = rejectEnvelope.payload,
                    stateMachine = stateMachine,
                )
            }
        }

        val errorInfo = parseErrorPayloadOrFallback(response.status, rawBody)
        val failureEvent = if (errorInfo.errorCode == FsapErrorCode.APPROVAL_TIMEOUT) {
            TransferEvent.APPROVAL_TIMEOUT
        } else {
            TransferEvent.CONNECTION_LOST
        }
        stateMachine.transitionIfPossible(failureEvent)
        return PrepareTransferResult.Failed(
            errorCode = errorInfo.errorCode,
            message = errorInfo.message,
            stateMachine = stateMachine,
        )
    }

    internal fun parseErrorPayloadOrFallback(
        status: HttpStatusCode,
        rawBody: String,
    ): FsapProtocolException {
        val parsed = runCatching { FsapJson.decodeEnvelope<ErrorPayload>(rawBody) }.getOrNull()
        if (parsed != null) {
            val code = FsapErrorCode.fromCode(parsed.payload.errorCode) ?: FsapErrorCode.INTERNAL_ERROR
            return FsapProtocolException(
                errorCode = code,
                message = parsed.payload.message,
                transferId = parsed.payload.transferId,
                fileId = parsed.payload.fileId,
            )
        }
        return FsapProtocolException(
            errorCode = mapStatusToDefaultError(status),
            message = "Servidor respondeu HTTP ${status.value}: ${rawBody.take(200)}",
        )
    }

    internal fun HttpRequestBuilder.applyStandardFsapHeaders(senderId: String = localIdentity.deviceId) {
        header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
        header(FsapHeaders.PROTOCOL, FsapConstants.PROTOCOL_NAME)
        header(FsapHeaders.SENDER_ID, senderId)
    }

    internal fun HttpRequestBuilder.applyOptionalTimeouts(
        connectMs: Long? = null,
        socketMs: Long? = null,
        requestMs: Long? = null,
    ) {
        if (httpClient.pluginOrNull(HttpTimeout) != null) {
            timeout {
                connectMs?.let { connectTimeoutMillis = it }
                socketMs?.let { socketTimeoutMillis = it }
                requestMs?.let { requestTimeoutMillis = it }
            }
        }
    }

    fun close() {
        if (ownsHttpClient) {
            httpClient.close()
        }
    }

    companion object {
        private const val CLIENT_APPROVAL_GRACE_MS = 2_000L

        fun createDefaultHttpClient(
            connectTimeoutMs: Long = FsapConstants.T_TCP_CONNECT_TIMEOUT_MS,
            streamTimeoutMs: Long = FsapConstants.T_STREAM_READ_TIMEOUT_MS,
        ): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = connectTimeoutMs
                socketTimeoutMillis = streamTimeoutMs
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }

        private fun computeSafeTotalBytes(files: List<FileItemDto>): Long? {
            var sum = 0L
            for (file in files) {
                if (file.sizeBytes < 0L) return 0L // Delegado para validacao padrao
                if (sum > Long.MAX_VALUE - file.sizeBytes) return null
                sum += file.sizeBytes
            }
            return sum
        }

        private fun mapStatusToDefaultError(status: HttpStatusCode): FsapErrorCode = when (status.value) {
            400 -> FsapErrorCode.MALFORMED_MESSAGE
            401 -> FsapErrorCode.UNAUTHORIZED_TRANSFER
            403 -> FsapErrorCode.TRANSFER_REJECTED
            404 -> FsapErrorCode.FILE_NOT_FOUND
            408 -> FsapErrorCode.APPROVAL_TIMEOUT
            409 -> FsapErrorCode.INVALID_STATE_TRANSITION
            413 -> FsapErrorCode.FILE_TOO_LARGE
            422 -> FsapErrorCode.HASH_MISMATCH
            505 -> FsapErrorCode.UNSUPPORTED_VERSION
            507 -> FsapErrorCode.INSUFFICIENT_STORAGE
            else -> FsapErrorCode.CONNECTION_LOST
        }
    }
}

internal suspend fun TransferStateMachine.transitionIfPossible(event: TransferEvent): Boolean {
    if (!canTransition(event)) return false
    transition(event)
    return true
}

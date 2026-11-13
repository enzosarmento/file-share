package br.edu.fileshare.network.client

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.network.streaming.IncrementalSha256Channel
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.protocol.model.CancelledBy
import br.edu.fileshare.protocol.model.CompletedFileSummary
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.ErrorPayload
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapHeaders
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.TransferAcceptPayload
import br.edu.fileshare.protocol.model.TransferCancelPayload
import br.edu.fileshare.protocol.model.TransferCompletePayload
import br.edu.fileshare.protocol.model.TransferFileStatusPayload
import br.edu.fileshare.protocol.model.TransferRejectPayload
import br.edu.fileshare.protocol.model.TransferRequestPayload
import br.edu.fileshare.protocol.model.TransferStatusPayload
import br.edu.fileshare.protocol.statemachine.TransferEvent
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.protocol.statemachine.TransferStateMachine
import br.edu.fileshare.protocol.validation.FsapMessageValidator
import br.edu.fileshare.protocol.validation.ValidationResult
import br.edu.fileshare.storage.FileSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
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
 * Entrada de arquivo preparado com digest SHA-256 calculado em streaming.
 */
data class PreparedOutgoingFile(
    val descriptor: FileItemDto,
    val source: FileSource,
)

/**
 * Resultado tipado da transmissao binaria em lote (`TRANSFER_CONTENT` + `TRANSFER_COMPLETE`).
 */
sealed interface TransferBatchUploadResult {
    data class Completed(
        val transferId: String,
        val confirmation: TransferCompletePayload,
        val stateMachine: TransferStateMachine,
    ) : TransferBatchUploadResult

    data class Cancelled(
        val transferId: String,
        val reason: String?,
        val stateMachine: TransferStateMachine,
    ) : TransferBatchUploadResult

    data class Failed(
        val transferId: String,
        val errorCode: FsapErrorCode,
        val message: String,
        val fileId: String? = null,
        val stateMachine: TransferStateMachine,
        val cause: Throwable? = null,
    ) : TransferBatchUploadResult
}

/**
 * Callback de progresso acumulado por arquivo e sessao durante o upload em chunks de 64 KB.
 */
fun interface TransferUploadProgressListener {
    suspend fun onProgress(
        fileId: String,
        fileBytesSent: Long,
        fileTotalBytes: Long,
        batchBytesSent: Long,
        batchTotalBytes: Long,
    )
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

    /**
     * Calcula SHA-256 em streaming de 64 KB para cada [FileSource] antes da negociacao `TRANSFER_REQUEST`.
     */
    suspend fun indexSourcesSequential(
        sources: List<FileSource>,
        onFileHashingProgress: suspend (fileIndex: Int, fileName: String, bytesHashed: Long, totalBytes: Long) -> Unit = { _, _, _, _ -> },
    ): List<PreparedOutgoingFile> = sources.mapIndexed { index, source ->
        val fileId = "file-${index + 1}"
        val summary = IncrementalSha256Channel.computeDigest(
            source = source,
            chunkSizeBytes = FsapConstants.CHUNK_SIZE_BYTES,
            onBytesProcessed = { processed ->
                onFileHashingProgress(index, source.fileName, processed, source.sizeBytes)
            },
        )
        PreparedOutgoingFile(
            descriptor = FileItemDto(
                fileId = fileId,
                fileName = source.fileName,
                sizeBytes = source.sizeBytes,
                sha256 = summary.sha256Hex,
                mimeType = source.mimeType,
            ),
            source = source,
        )
    }

    /**
     * Executa o envio completo em streaming (chunks de 64 KB via `OutgoingContent.WriteChannelContent`)
     * de todos os arquivos aprovados em [session] e finaliza com `POST /complete` (`TASK-XFER-003`).
     */
    suspend fun uploadAcceptedBatch(
        session: PreparedTransferSession,
        sourcesByFileId: Map<String, FileSource>,
        progressListener: TransferUploadProgressListener? = null,
    ): TransferBatchUploadResult {
        val stateMachine = session.stateMachine
        val orderedFiles = session.acceptedFiles
        val batchTotalBytes = orderedFiles.sumOf { it.sizeBytes }
        var completedFilesBytes = 0L
        val completedSummaries = ArrayList<CompletedFileSummary>(orderedFiles.size)

        try {
            orderedFiles.forEachIndexed { index, fileItem ->
                val source = sourcesByFileId[fileItem.fileId]
                    ?: return failBatch(
                        stateMachine = stateMachine,
                        errorCode = FsapErrorCode.FILE_NOT_FOUND,
                        message = "FileSource ausente para fileId='${fileItem.fileId}'.",
                        fileId = fileItem.fileId,
                    )

                if (index == 0) {
                    stateMachine.transition(TransferEvent.STREAM_STARTED)
                } else {
                    stateMachine.transitionIfPossible(TransferEvent.CHUNK_TRANSFERRED)
                }

                uploadSingleFileContent(
                    session = session,
                    fileItem = fileItem,
                    source = source,
                    onBytesSent = { fileBytesSent ->
                        progressListener?.onProgress(
                            fileId = fileItem.fileId,
                            fileBytesSent = fileBytesSent,
                            fileTotalBytes = fileItem.sizeBytes,
                            batchBytesSent = (completedFilesBytes + fileBytesSent).coerceAtMost(batchTotalBytes),
                            batchTotalBytes = batchTotalBytes,
                        )
                    },
                )

                completedFilesBytes += fileItem.sizeBytes
                progressListener?.onProgress(
                    fileId = fileItem.fileId,
                    fileBytesSent = fileItem.sizeBytes,
                    fileTotalBytes = fileItem.sizeBytes,
                    batchBytesSent = completedFilesBytes,
                    batchTotalBytes = batchTotalBytes,
                )
                completedSummaries += CompletedFileSummary(fileId = fileItem.fileId, sha256 = fileItem.sha256)

                stateMachine.transition(TransferEvent.ALL_BYTES_RECEIVED)
                if (index < orderedFiles.lastIndex) {
                    stateMachine.transition(TransferEvent.FILE_VERIFIED_MORE_REMAIN)
                }
            }

            val completePayload = sendTransferComplete(
                session = session,
                completedFiles = completedSummaries,
            )
            stateMachine.transition(TransferEvent.ALL_FILES_VERIFIED_AND_COMPLETED)
            return TransferBatchUploadResult.Completed(
                transferId = session.transferId,
                confirmation = completePayload,
                stateMachine = stateMachine,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                stateMachine.transitionIfPossible(TransferEvent.CANCEL_TRANSFER)
            }
            throw cancelled
        } catch (protocolEx: FsapProtocolException) {
            return handleBatchFailureException(stateMachine, session.transferId, protocolEx)
        } catch (timeoutEx: HttpRequestTimeoutException) {
            stateMachine.transitionIfPossible(TransferEvent.STREAM_TIMEOUT)
            return TransferBatchUploadResult.Failed(
                transferId = session.transferId,
                errorCode = FsapErrorCode.STREAM_TIMEOUT,
                message = "Timeout de socket/stream durante transmissao para ${session.endpoint.host}:${session.endpoint.port}.",
                stateMachine = stateMachine,
                cause = timeoutEx,
            )
        } catch (ex: Throwable) {
            stateMachine.transitionIfPossible(TransferEvent.CONNECTION_LOST)
            return TransferBatchUploadResult.Failed(
                transferId = session.transferId,
                errorCode = FsapErrorCode.CONNECTION_LOST,
                message = "Falha de transporte durante transferencia: ${ex.message}",
                stateMachine = stateMachine,
                cause = ex,
            )
        }
    }

    suspend fun uploadSingleFileContent(
        session: PreparedTransferSession,
        fileItem: FileItemDto,
        source: FileSource,
        onBytesSent: suspend (bytesSent: Long) -> Unit = {},
    ): TransferFileStatusPayload {
        val url = session.endpoint.url(FsapConstants.Routes.transferContent(session.transferId, fileItem.fileId))
        val streamingBody = object : OutgoingContent.WriteChannelContent() {
            override val contentType: ContentType = ContentType.Application.OctetStream
            override val contentLength: Long = fileItem.sizeBytes

            override suspend fun writeTo(channel: ByteWriteChannel) {
                IncrementalSha256Channel.copySourceToWriteChannel(
                    source = source,
                    writeChannel = channel,
                    chunkSizeBytes = session.chunkSizeBytes,
                    onChunkSent = onBytesSent,
                )
            }
        }

        val response = httpClient.put(url) {
            applyStandardFsapHeaders()
            header(FsapHeaders.TRANSFER_TOKEN, session.transferToken)
            header(FsapHeaders.FILE_SHA256, fileItem.sha256)
            applyOptionalTimeouts(
                connectMs = connectTimeoutMs,
                socketMs = streamTimeoutMs,
                requestMs = HttpTimeoutConfig.INFINITE_TIMEOUT_MS,
            )
            setBody(streamingBody)
        }

        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            throw parseErrorPayloadOrFallback(response.status, rawBody)
        }

        val envelope = try {
            FsapJson.decodeEnvelope<TransferFileStatusPayload>(rawBody)
        } catch (ex: SerializationException) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                message = "Resposta TRANSFER_STATUS invalida apos upload de '${fileItem.fileId}'.",
                transferId = session.transferId,
                fileId = fileItem.fileId,
                cause = ex,
            )
        }
        val payload = envelope.payload
        if (!payload.fileVerified || !payload.calculatedSha256.equals(fileItem.sha256, ignoreCase = true)) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.HASH_MISMATCH,
                message = "Servidor nao confirmou integridade SHA-256 de '${fileItem.fileId}'.",
                transferId = session.transferId,
                fileId = fileItem.fileId,
            )
        }
        return payload
    }

    suspend fun sendTransferComplete(
        session: PreparedTransferSession,
        completedFiles: List<CompletedFileSummary>,
    ): TransferCompletePayload {
        val url = session.endpoint.url(FsapConstants.Routes.transferComplete(session.transferId))
        val requestEnvelope = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_COMPLETE,
            senderId = localIdentity.deviceId,
            payload = TransferCompletePayload(
                transferId = session.transferId,
                completedFiles = completedFiles,
            ),
            uuidGenerator = uuidGenerator,
            clock = clock,
        )

        val response = httpClient.post(url) {
            applyStandardFsapHeaders()
            header(FsapHeaders.TRANSFER_TOKEN, session.transferToken)
            contentType(ContentType.Application.Json)
            applyOptionalTimeouts(
                connectMs = connectTimeoutMs,
                socketMs = streamTimeoutMs,
                requestMs = streamTimeoutMs,
            )
            setBody(FsapJson.encodeEnvelope(requestEnvelope))
        }

        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            throw parseErrorPayloadOrFallback(response.status, rawBody)
        }

        val envelope = try {
            FsapJson.decodeEnvelope<TransferCompletePayload>(rawBody)
        } catch (ex: SerializationException) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                message = "Resposta TRANSFER_COMPLETE invalida recebida do servidor.",
                transferId = session.transferId,
                cause = ex,
            )
        }
        if (envelope.payload.state != TransferState.COMPLETED.name) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INVALID_STATE_TRANSITION,
                message = "Servidor respondeu estado '${envelope.payload.state}' em TRANSFER_COMPLETE.",
                transferId = session.transferId,
            )
        }
        return envelope.payload
    }

    suspend fun queryTransferStatus(
        endpoint: FsapPeerEndpoint,
        transferId: String,
        transferToken: String? = null,
    ): TransferStatusPayload {
        val url = endpoint.url(FsapConstants.Routes.transferStatus(transferId))
        val response = httpClient.get(url) {
            applyStandardFsapHeaders()
            transferToken?.let { header(FsapHeaders.TRANSFER_TOKEN, it) }
            applyOptionalTimeouts(
                connectMs = connectTimeoutMs,
                socketMs = streamTimeoutMs,
                requestMs = connectTimeoutMs,
            )
        }
        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            throw parseErrorPayloadOrFallback(response.status, rawBody)
        }
        return FsapJson.decodeEnvelope<TransferStatusPayload>(rawBody).payload
    }

    suspend fun cancelTransferOnPeer(
        endpoint: FsapPeerEndpoint,
        transferId: String,
        cancelledBy: CancelledBy = CancelledBy.SENDER,
        transferToken: String? = null,
        reason: String? = null,
    ): TransferStatusPayload {
        val url = endpoint.url(FsapConstants.Routes.transferCancel(transferId))
        val requestEnvelope = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_CANCEL,
            senderId = localIdentity.deviceId,
            payload = TransferCancelPayload(
                transferId = transferId,
                cancelledBy = cancelledBy,
                reason = reason,
            ),
            uuidGenerator = uuidGenerator,
            clock = clock,
        )

        val response = httpClient.post(url) {
            applyStandardFsapHeaders()
            transferToken?.let { header(FsapHeaders.TRANSFER_TOKEN, it) }
            contentType(ContentType.Application.Json)
            applyOptionalTimeouts(
                connectMs = connectTimeoutMs,
                socketMs = connectTimeoutMs,
                requestMs = connectTimeoutMs,
            )
            setBody(FsapJson.encodeEnvelope(requestEnvelope))
        }
        val rawBody = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            throw parseErrorPayloadOrFallback(response.status, rawBody)
        }
        return FsapJson.decodeEnvelope<TransferStatusPayload>(rawBody).payload
    }

    private suspend fun failBatch(
        stateMachine: TransferStateMachine,
        errorCode: FsapErrorCode,
        message: String,
        fileId: String? = null,
        cause: Throwable? = null,
    ): TransferBatchUploadResult.Failed {
        if (!stateMachine.transitionIfPossible(TransferEvent.STREAM_ERROR)) {
            stateMachine.transitionIfPossible(TransferEvent.CONNECTION_LOST)
        }
        return TransferBatchUploadResult.Failed(
            transferId = stateMachine.transferId,
            errorCode = errorCode,
            message = message,
            fileId = fileId,
            stateMachine = stateMachine,
            cause = cause,
        )
    }

    private suspend fun handleBatchFailureException(
        stateMachine: TransferStateMachine,
        transferId: String,
        protocolEx: FsapProtocolException,
    ): TransferBatchUploadResult {
        return when (protocolEx.errorCode) {
            FsapErrorCode.TRANSFER_CANCELLED -> {
                stateMachine.transitionIfPossible(TransferEvent.CANCEL_TRANSFER)
                TransferBatchUploadResult.Cancelled(
                    transferId = transferId,
                    reason = protocolEx.message,
                    stateMachine = stateMachine,
                )
            }

            FsapErrorCode.HASH_MISMATCH -> {
                if (stateMachine.currentState() == TransferState.TRANSFERRING) {
                    stateMachine.transitionIfPossible(TransferEvent.ALL_BYTES_RECEIVED)
                }
                if (!stateMachine.transitionIfPossible(TransferEvent.HASH_MISMATCH)) {
                    stateMachine.transitionIfPossible(TransferEvent.STREAM_ERROR)
                }
                TransferBatchUploadResult.Failed(
                    transferId = transferId,
                    errorCode = protocolEx.errorCode,
                    message = protocolEx.message,
                    fileId = protocolEx.fileId,
                    stateMachine = stateMachine,
                    cause = protocolEx,
                )
            }

            FsapErrorCode.STREAM_TIMEOUT -> {
                if (!stateMachine.transitionIfPossible(TransferEvent.STREAM_TIMEOUT)) {
                    stateMachine.transitionIfPossible(TransferEvent.CONNECTION_LOST)
                }
                TransferBatchUploadResult.Failed(
                    transferId = transferId,
                    errorCode = protocolEx.errorCode,
                    message = protocolEx.message,
                    fileId = protocolEx.fileId,
                    stateMachine = stateMachine,
                    cause = protocolEx,
                )
            }

            else -> failBatch(
                stateMachine = stateMachine,
                errorCode = protocolEx.errorCode,
                message = protocolEx.message,
                fileId = protocolEx.fileId,
                cause = protocolEx,
            )
        }
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

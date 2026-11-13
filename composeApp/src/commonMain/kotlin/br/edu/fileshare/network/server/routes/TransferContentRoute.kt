package br.edu.fileshare.network.server.routes

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.network.server.respondFsapEnvelope
import br.edu.fileshare.network.server.validateOptionalFsapVersionHeader
import br.edu.fileshare.network.streaming.IncrementalSha256Channel
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapHeaders
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.TransferFileStatusPayload
import br.edu.fileshare.protocol.statemachine.TransferState
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.Routing
import io.ktor.server.routing.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Rota `PUT /fsap/v1/transfers/{transferId}/files/{fileId}/content` (`TASK-DSRV-004`):
 * - Valida estado da sessao, token efemero, `Content-Length` e `X-FSAP-File-SHA256` antes de abrir `.part`.
 * - Recebe fluxo binario em chunks de 64 KB com timeout de leitura (`T_STREAM_READ_TIMEOUT_MS` = 15 s).
 * - Calcula SHA-256 incremental e promove `.part` atomicamente somente em caso de coincidencia exata.
 */
fun Routing.installTransferContentRoute(
    localIdentity: DeviceIdentity,
    services: FsapTransferServerServices,
    logger: FsapLogger,
    clock: Clock = SystemClock,
    uuidGenerator: UuidGenerator = PlatformUuidGenerator,
) {
    put("${FsapConstants.Routes.BASE_V1}/transfers/{transferId}/files/{fileId}/content") {
        call.validateOptionalFsapVersionHeader()

        val transferId = call.parameters["transferId"]?.takeIf { it.isNotBlank() }
            ?: throw FsapProtocolException(FsapErrorCode.MALFORMED_MESSAGE, "Parametro transferId ausente.")
        val fileId = call.parameters["fileId"]?.takeIf { it.isNotBlank() }
            ?: throw FsapProtocolException(FsapErrorCode.MALFORMED_MESSAGE, "Parametro fileId ausente.")

        val presentedToken = call.request.headers[FsapHeaders.TRANSFER_TOKEN]
        val contentLength = call.request.contentLength()
            ?: call.request.headers["Content-Length"]?.toLongOrNull()
        val headerSha256 = call.request.headers[FsapHeaders.FILE_SHA256]
        val headerSenderId = call.request.headers[FsapHeaders.SENDER_ID]

        val snapshotBefore = services.sessionManager.findSnapshot(transferId)
        if (snapshotBefore != null && headerSenderId != null &&
            !headerSenderId.equals(snapshotBefore.senderId, ignoreCase = true)
        ) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.UNAUTHORIZED_TRANSFER,
                message = "Cabecalho X-FSAP-Sender-Id nao corresponde ao remetente da sessao.",
                transferId = transferId,
                fileId = fileId,
            )
        }

        val uploadJob = currentCoroutineContext()[Job]
        val streamContext = services.sessionManager.beginFileStream(
            transferId = transferId,
            fileId = fileId,
            presentedToken = presentedToken,
            contentLength = contentLength,
            headerSha256 = headerSha256,
            uploadJob = uploadJob,
        )
        val fileItem = streamContext.fileItem

        try {
            services.fileSink.ensureCapacity(fileItem.sizeBytes)
            val partSession = services.fileSink.openPartSession(
                transferId = transferId,
                fileId = fileId,
                requestedFileName = fileItem.fileName,
                expectedSizeBytes = fileItem.sizeBytes,
            )

            val verifiedResult = IncrementalSha256Channel.receiveChannelToSinkVerified(
                readChannel = call.receiveChannel(),
                sinkSession = partSession,
                expectedSizeBytes = fileItem.sizeBytes,
                expectedSha256 = fileItem.sha256,
                chunkSizeBytes = FsapConstants.CHUNK_SIZE_BYTES,
                readTimeoutMs = services.streamReadTimeoutMs,
                onChunkReceived = { bytesReceived ->
                    services.sessionManager.updateFileProgress(transferId, fileId, bytesReceived)
                },
            )

            val nextState = services.sessionManager.completeFileVerification(
                transferId = transferId,
                fileId = fileId,
                calculatedSha256 = verifiedResult.calculatedSha256,
                storedFile = verifiedResult.storedFile,
            )

            logger.info(
                component = "TransferContentRoute",
                message = "Arquivo '${verifiedResult.storedFile.finalName}' verificado (${verifiedResult.bytesReceived} B, SHA-256 OK).",
                context = LogContext(
                    deviceId = localIdentity.deviceId,
                    transferId = transferId,
                ),
            )

            val statusEnvelope = FsapEnvelopeFactory.create(
                type = FsapMessageType.TRANSFER_STATUS,
                senderId = localIdentity.deviceId,
                payload = TransferFileStatusPayload(
                    transferId = transferId,
                    fileId = fileId,
                    state = nextState.name,
                    fileVerified = true,
                    bytesReceived = verifiedResult.bytesReceived,
                    calculatedSha256 = verifiedResult.calculatedSha256,
                ),
                uuidGenerator = uuidGenerator,
                clock = clock,
            )
            call.respondFsapEnvelope(statusEnvelope, status = HttpStatusCode.OK)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val current = services.sessionManager.findSnapshot(transferId)?.state
                if (current != null && current != TransferState.CANCELLED && !current.isTerminal) {
                    services.sessionManager.recordStreamFailure(
                        transferId = transferId,
                        fileId = fileId,
                        error = FsapProtocolException(
                            errorCode = FsapErrorCode.CONNECTION_LOST,
                            message = "Fluxo HTTP interrompido durante transmissao de '$fileId'.",
                            transferId = transferId,
                            fileId = fileId,
                            cause = cancelled,
                        ),
                    )
                } else {
                    runCatching { services.fileSink.cleanupTransferParts(transferId) }
                }
            }
            throw cancelled
        } catch (protocolEx: FsapProtocolException) {
            services.sessionManager.recordStreamFailure(transferId, fileId, protocolEx)
            throw protocolEx
        } catch (ex: Throwable) {
            val mapped = FsapProtocolException(
                errorCode = FsapErrorCode.CONNECTION_LOST,
                message = "Falha de leitura/conexao durante upload de '$fileId': ${ex.message}",
                transferId = transferId,
                fileId = fileId,
                cause = ex,
            )
            services.sessionManager.recordStreamFailure(transferId, fileId, mapped)
            throw mapped
        } finally {
            withContext(NonCancellable) {
                services.sessionManager.finishUploadJobReference(transferId, uploadJob)
            }
        }
    }
}

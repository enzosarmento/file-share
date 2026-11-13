package br.edu.fileshare.network.server.routes

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.network.server.ApprovalRequestContext
import br.edu.fileshare.network.server.CreateSessionResult
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferApprovalDecision
import br.edu.fileshare.network.server.TransferApprovalHandler
import br.edu.fileshare.network.server.receiveBoundedJsonEnvelope
import br.edu.fileshare.network.server.respondFsapEnvelope
import br.edu.fileshare.network.server.validateOptionalFsapVersionHeader
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapErrorFactory
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.RejectReasonCode
import br.edu.fileshare.protocol.model.TransferAcceptPayload
import br.edu.fileshare.protocol.model.TransferRejectPayload
import br.edu.fileshare.protocol.model.TransferRequestPayload
import br.edu.fileshare.protocol.validation.FsapMessageValidator
import br.edu.fileshare.protocol.validation.ValidationResult
import br.edu.fileshare.storage.FileSink
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Configuracao dos servicos de sessao e armazenamento acoplados as rotas de transferencia do [FsapHttpServer].
 */
data class FsapTransferServerServices(
    val sessionManager: ServerTransferSessionManager,
    val fileSink: FileSink,
    val approvalHandler: TransferApprovalHandler,
    val approvalTimeoutMs: Long = FsapConstants.T_APPROVAL_TIMEOUT_MS,
    val streamReadTimeoutMs: Long = FsapConstants.T_STREAM_READ_TIMEOUT_MS,
    val maxJsonBodyBytes: Int = FsapConstants.MAX_JSON_BODY_BYTES,
)

/**
 * Rota `POST /fsap/v1/transfers/prepare` (`TASK-DSRV-003`):
 * valida manifesto (`FileNameSanitizer`), verifica espaco em disco (`FileSink`),
 * aguarda aprovacao explicita do destinatario (`60 s`) e emite `TRANSFER_ACCEPT` / `TRANSFER_REJECT`.
 */
fun Routing.installTransferPrepareRoute(
    localIdentity: DeviceIdentity,
    services: FsapTransferServerServices,
    logger: FsapLogger,
    clock: Clock = SystemClock,
    uuidGenerator: UuidGenerator = PlatformUuidGenerator,
) {
    post(FsapConstants.Routes.TRANSFERS_PREPARE) {
        call.validateOptionalFsapVersionHeader()
        val requestEnvelope = call.receiveBoundedJsonEnvelope<TransferRequestPayload>(
            maxJsonBodyBytes = services.maxJsonBodyBytes,
        )
        val validation = FsapMessageValidator.validateTransferRequest(requestEnvelope)
        if (validation is ValidationResult.Invalid) {
            throw FsapProtocolException(
                errorCode = validation.errorCode,
                message = validation.reason,
                transferId = requestEnvelope.payload.transferId,
            )
        }

        val request = requestEnvelope.payload
        services.fileSink.ensureCapacity(request.totalBytes)

        when (val createResult = services.sessionManager.createIncomingSession(requestEnvelope.senderId, request)) {
            CreateSessionResult.DuplicateTransferId -> throw FsapProtocolException(
                errorCode = FsapErrorCode.INVALID_STATE_TRANSITION,
                message = "Transferencia '${request.transferId}' ja foi iniciada anteriormente.",
                transferId = request.transferId,
            )

            CreateSessionResult.CapacityExceeded -> {
                val busyEnvelope = FsapEnvelopeFactory.create(
                    type = FsapMessageType.TRANSFER_REJECT,
                    senderId = localIdentity.deviceId,
                    payload = TransferRejectPayload(
                        transferId = request.transferId,
                        reasonCode = RejectReasonCode.BUSY,
                        reasonMessage = "Limite maximo de transferencias simultaneas atingido.",
                    ),
                    uuidGenerator = uuidGenerator,
                    clock = clock,
                )
                call.respondFsapEnvelope(busyEnvelope, status = HttpStatusCode.Forbidden)
                return@post
            }

            is CreateSessionResult.Created -> Unit
        }

        logger.info(
            component = "TransferPrepareRoute",
            message = "Solicitacao TRANSFER_REQUEST recebida de '${request.senderAlias}' (${request.files.size} arquivo(s), ${request.totalBytes} B).",
            context = LogContext(
                deviceId = localIdentity.deviceId,
                transferId = request.transferId,
                peerId = requestEnvelope.senderId,
            ),
        )

        val decision = try {
            withTimeoutOrNull(services.approvalTimeoutMs) {
                services.approvalHandler.requestApproval(
                    ApprovalRequestContext(
                        transferId = request.transferId,
                        senderId = requestEnvelope.senderId,
                        request = request,
                        receivedAtEpochMillis = clock.epochMillis(),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                services.sessionManager.failPreparation(
                    transferId = request.transferId,
                    errorCode = FsapErrorCode.CONNECTION_LOST,
                    reason = "Conexao encerrada durante aguardamento de aprovacao.",
                )
            }
            throw cancelled
        }

        if (decision == null) {
            services.sessionManager.failPreparation(
                transferId = request.transferId,
                errorCode = FsapErrorCode.APPROVAL_TIMEOUT,
                reason = FsapErrorCode.APPROVAL_TIMEOUT.defaultMessage,
            )
            val timeoutEnvelope = FsapErrorFactory.toEnvelope(
                senderId = localIdentity.deviceId,
                errorCode = FsapErrorCode.APPROVAL_TIMEOUT,
                transferId = request.transferId,
                uuidGenerator = uuidGenerator,
                clock = clock,
            )
            call.respondFsapEnvelope(timeoutEnvelope, status = HttpStatusCode.RequestTimeout)
            return@post
        }

        when (decision) {
            is TransferApprovalDecision.Rejected -> {
                services.sessionManager.rejectSession(
                    transferId = request.transferId,
                    reasonCode = decision.reasonCode,
                    reasonMessage = decision.reasonMessage,
                )
                val rejectEnvelope = FsapEnvelopeFactory.create(
                    type = FsapMessageType.TRANSFER_REJECT,
                    senderId = localIdentity.deviceId,
                    payload = TransferRejectPayload(
                        transferId = request.transferId,
                        reasonCode = decision.reasonCode,
                        reasonMessage = decision.reasonMessage,
                    ),
                    uuidGenerator = uuidGenerator,
                    clock = clock,
                )
                call.respondFsapEnvelope(rejectEnvelope, status = HttpStatusCode.Forbidden)
            }

            is TransferApprovalDecision.Approved -> {
                val approvedIds = decision.acceptedFileIds ?: request.files.map { it.fileId }
                val issued = services.sessionManager.acceptSession(
                    transferId = request.transferId,
                    selectedFileIds = approvedIds,
                )
                val acceptEnvelope = FsapEnvelopeFactory.create(
                    type = FsapMessageType.TRANSFER_ACCEPT,
                    senderId = localIdentity.deviceId,
                    payload = TransferAcceptPayload(
                        transferId = request.transferId,
                        transferToken = issued.token,
                        acceptedFileIds = approvedIds,
                        chunkSizeBytes = FsapConstants.CHUNK_SIZE_BYTES,
                    ),
                    uuidGenerator = uuidGenerator,
                    clock = clock,
                )
                call.respondFsapEnvelope(acceptEnvelope, status = HttpStatusCode.OK)
            }
        }
    }
}


package br.edu.fileshare.network.server.routes

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.network.server.receiveBoundedJsonEnvelope
import br.edu.fileshare.network.server.respondFsapEnvelope
import br.edu.fileshare.network.server.validateOptionalFsapVersionHeader
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.protocol.model.CancelledBy
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapHeaders
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.TransferCancelPayload
import br.edu.fileshare.protocol.model.TransferCompletePayload
import br.edu.fileshare.protocol.validation.FsapMessageValidator
import br.edu.fileshare.protocol.validation.ValidationResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Rotas de controle e consulta da sessao FSAP/1.0 (`TASK-DSRV-005`):
 * - `GET  /fsap/v1/transfers/{transferId}/status` (`TRANSFER_STATUS`)
 * - `POST /fsap/v1/transfers/{transferId}/cancel` (`TRANSFER_CANCEL`)
 * - `POST /fsap/v1/transfers/{transferId}/complete` (`TRANSFER_COMPLETE`)
 */
fun Routing.installTransferControlRoutes(
    localIdentity: DeviceIdentity,
    services: FsapTransferServerServices,
    logger: FsapLogger,
    clock: Clock = SystemClock,
    uuidGenerator: UuidGenerator = PlatformUuidGenerator,
) {
    get("${FsapConstants.Routes.BASE_V1}/transfers/{transferId}/status") {
        call.validateOptionalFsapVersionHeader()
        val transferId = call.requireValidTransferIdPathParam()
        val presentedToken = call.request.headers[FsapHeaders.TRANSFER_TOKEN]
        val headerSenderId = call.request.headers[FsapHeaders.SENDER_ID]

        services.sessionManager.verifyControlAccess(
            transferId = transferId,
            presentedToken = presentedToken,
            senderId = headerSenderId,
            requireTokenWhenActive = true,
        )

        val statusPayload = services.sessionManager.getStatusPayload(transferId)
        val envelope = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_STATUS,
            senderId = localIdentity.deviceId,
            payload = statusPayload,
            uuidGenerator = uuidGenerator,
            clock = clock,
        )
        call.respondFsapEnvelope(envelope, status = HttpStatusCode.OK)
    }

    post("${FsapConstants.Routes.BASE_V1}/transfers/{transferId}/cancel") {
        call.validateOptionalFsapVersionHeader()
        val transferId = call.requireValidTransferIdPathParam()
        val requestEnvelope = call.receiveBoundedJsonEnvelope<TransferCancelPayload>(
            maxJsonBodyBytes = services.maxJsonBodyBytes,
        )
        val envCheck = FsapMessageValidator.validateEnvelope(requestEnvelope, FsapMessageType.TRANSFER_CANCEL)
        if (envCheck is ValidationResult.Invalid) {
            throw FsapProtocolException(
                errorCode = envCheck.errorCode,
                message = envCheck.reason,
                transferId = transferId,
            )
        }

        val payload = requestEnvelope.payload
        if (!payload.transferId.equals(transferId, ignoreCase = true)) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                message = "transferId do payload (${payload.transferId}) diverge da rota ($transferId).",
                transferId = transferId,
            )
        }
        val reason = payload.reason
        if (reason != null && (reason.length > 256 || reason.contains('\u0000'))) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                message = "Campo reason invalido em TRANSFER_CANCEL.",
                transferId = transferId,
            )
        }

        val presentedToken = call.request.headers[FsapHeaders.TRANSFER_TOKEN]
        services.sessionManager.verifyControlAccess(
            transferId = transferId,
            presentedToken = presentedToken,
            senderId = requestEnvelope.senderId,
            allowedAdditionalSenderId = localIdentity.deviceId.takeIf { payload.cancelledBy == CancelledBy.RECEIVER },
            requireTokenWhenActive = false,
        )

        val cancelledStatus = services.sessionManager.cancelTransferAndStatus(
            transferId = transferId,
            cancelledBy = payload.cancelledBy,
            reason = payload.reason,
        )

        logger.warn(
            component = "TransferControlRoutes",
            message = "Transferencia cancelada por ${payload.cancelledBy}${payload.reason?.let { " ($it)" }.orEmpty()}.",
            context = LogContext(
                deviceId = localIdentity.deviceId,
                peerId = requestEnvelope.senderId,
                transferId = transferId,
            ),
        )

        val responseEnvelope = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_STATUS,
            senderId = localIdentity.deviceId,
            payload = cancelledStatus,
            uuidGenerator = uuidGenerator,
            clock = clock,
        )
        call.respondFsapEnvelope(responseEnvelope, status = HttpStatusCode.OK)
    }

    post("${FsapConstants.Routes.BASE_V1}/transfers/{transferId}/complete") {
        call.validateOptionalFsapVersionHeader()
        val transferId = call.requireValidTransferIdPathParam()
        val requestEnvelope = call.receiveBoundedJsonEnvelope<TransferCompletePayload>(
            maxJsonBodyBytes = services.maxJsonBodyBytes,
        )
        val envCheck = FsapMessageValidator.validateEnvelope(requestEnvelope, FsapMessageType.TRANSFER_COMPLETE)
        if (envCheck is ValidationResult.Invalid) {
            throw FsapProtocolException(
                errorCode = envCheck.errorCode,
                message = envCheck.reason,
                transferId = transferId,
            )
        }

        val payload = requestEnvelope.payload
        if (!payload.transferId.equals(transferId, ignoreCase = true)) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                message = "transferId do payload (${payload.transferId}) diverge da rota ($transferId).",
                transferId = transferId,
            )
        }
        if (payload.completedFiles.isEmpty()) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                message = "Lista completedFiles nao pode ser vazia em TRANSFER_COMPLETE.",
                transferId = transferId,
            )
        }
        for (item in payload.completedFiles) {
            if (item.fileId.isBlank() || !FsapMessageValidator.isValidSha256Hex(item.sha256)) {
                throw FsapProtocolException(
                    errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                    message = "Entrada invalida em completedFiles para fileId='${item.fileId}'.",
                    transferId = transferId,
                    fileId = item.fileId.takeIf { it.isNotBlank() },
                )
            }
        }

        val presentedToken = call.request.headers[FsapHeaders.TRANSFER_TOKEN]
        services.sessionManager.verifyControlAccess(
            transferId = transferId,
            presentedToken = presentedToken,
            senderId = requestEnvelope.senderId,
            requireTokenWhenActive = true,
        )

        val completedPayload = services.sessionManager.completeTransfer(
            transferId = transferId,
            submittedFiles = payload.completedFiles,
        )

        logger.info(
            component = "TransferControlRoutes",
            message = "Lote '$transferId' concluido (${completedPayload.completedFiles.size} arquivos verificados).",
            context = LogContext(
                deviceId = localIdentity.deviceId,
                peerId = requestEnvelope.senderId,
                transferId = transferId,
            ),
        )

        val responseEnvelope = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_COMPLETE,
            senderId = localIdentity.deviceId,
            payload = completedPayload,
            uuidGenerator = uuidGenerator,
            clock = clock,
        )
        call.respondFsapEnvelope(responseEnvelope, status = HttpStatusCode.OK)
    }
}

private fun io.ktor.server.application.ApplicationCall.requireValidTransferIdPathParam(): String {
    val raw = parameters["transferId"]?.takeIf { it.isNotBlank() }
        ?: throw FsapProtocolException(
            errorCode = FsapErrorCode.MALFORMED_MESSAGE,
            message = "Parametro transferId ausente na URL.",
        )
    if (!FsapMessageValidator.isValidUuidV4(raw)) {
        throw FsapProtocolException(
            errorCode = FsapErrorCode.MALFORMED_MESSAGE,
            message = "Parametro transferId nao e UUID v4 valido: '$raw'.",
        )
    }
    return raw
}

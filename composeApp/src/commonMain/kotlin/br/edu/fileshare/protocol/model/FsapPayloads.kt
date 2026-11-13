package br.edu.fileshare.protocol.model

import br.edu.fileshare.protocol.FsapConstants
import kotlinx.serialization.Serializable

/**
 * Payload da mensagem UDP `ANNOUNCE`.
 */
@Serializable
data class AnnouncePayload(
    val alias: String,
    val deviceType: DeviceType,
    val platform: PlatformType,
    val httpPort: Int,
    val capabilities: List<String> = DeviceIdentity.DEFAULT_CAPABILITIES,
)

/**
 * Payload da mensagem UDP `DISCOVER`.
 */
@Serializable
data class DiscoverPayload(
    val requesterAlias: String,
    val requesterHttpPort: Int,
)

/**
 * Payload da mensagem UDP `BYE`.
 */
@Serializable
data class ByePayload(
    val reason: String? = null,
)

/**
 * Payload da mensagem HTTP `IDENTIFY` (requisicao e resposta).
 */
@Serializable
data class IdentifyPayload(
    val alias: String,
    val deviceType: DeviceType,
    val platform: PlatformType,
    val httpPort: Int,
    val capabilities: List<String> = DeviceIdentity.DEFAULT_CAPABILITIES,
)

/**
 * Item individual de arquivo dentro do manifesto `TRANSFER_REQUEST`.
 */
@Serializable
data class FileItemDto(
    val fileId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val mimeType: String = "application/octet-stream",
)

/**
 * Payload da mensagem HTTP `TRANSFER_REQUEST`.
 */
@Serializable
data class TransferRequestPayload(
    val transferId: String,
    val senderAlias: String,
    val senderDeviceType: DeviceType,
    val totalBytes: Long,
    val files: List<FileItemDto>,
)

/**
 * Payload da mensagem HTTP `TRANSFER_ACCEPT`.
 */
@Serializable
data class TransferAcceptPayload(
    val transferId: String,
    val transferToken: String,
    val acceptedFileIds: List<String>,
    val chunkSizeBytes: Int = FsapConstants.CHUNK_SIZE_BYTES,
)

/**
 * Codigos padronizados de recusa explicita em `TRANSFER_REJECT`.
 */
@Serializable
enum class RejectReasonCode {
    USER_DECLINED,
    BUSY,
    POLICY_BLOCKED,
}

/**
 * Payload da mensagem HTTP `TRANSFER_REJECT`.
 */
@Serializable
data class TransferRejectPayload(
    val transferId: String,
    val reasonCode: RejectReasonCode,
    val reasonMessage: String? = null,
)

/**
 * Payload de resposta do servidor apos recepcao binaria e verificacao SHA-256 em `TRANSFER_CONTENT`.
 * Serializado com envelope `type = "TRANSFER_STATUS"`.
 */
@Serializable
data class TransferFileStatusPayload(
    val transferId: String,
    val fileId: String,
    val state: String,
    val fileVerified: Boolean,
    val bytesReceived: Long,
    val calculatedSha256: String,
)

/**
 * Payload da consulta consolidada `TRANSFER_STATUS` (`GET /fsap/v1/transfers/{transferId}/status`).
 */
@Serializable
data class TransferStatusPayload(
    val transferId: String,
    val state: String,
    val totalBytesTransferred: Long,
    val totalBytes: Long,
    val currentFileId: String? = null,
    val filesCompleted: Int,
    val totalFiles: Int,
)

/**
 * Origem do cancelamento em `TRANSFER_CANCEL`.
 */
@Serializable
enum class CancelledBy {
    SENDER,
    RECEIVER,
}

/**
 * Payload da mensagem HTTP `TRANSFER_CANCEL`.
 */
@Serializable
data class TransferCancelPayload(
    val transferId: String,
    val cancelledBy: CancelledBy,
    val reason: String? = null,
)

/**
 * Resumo de arquivo verificado na finalizacao `TRANSFER_COMPLETE`.
 */
@Serializable
data class CompletedFileSummary(
    val fileId: String,
    val sha256: String,
)

/**
 * Payload da mensagem HTTP `TRANSFER_COMPLETE` (enviado pelo cliente e confirmado pelo servidor).
 */
@Serializable
data class TransferCompletePayload(
    val transferId: String,
    val completedFiles: List<CompletedFileSummary>,
    val state: String? = null,
)

/**
 * Categorias padronizadas de erro em `ERROR`.
 */
@Serializable
enum class ErrorCategory {
    VALIDATION,
    AUTH,
    STATE,
    INTEGRITY,
    STORAGE,
    TIMEOUT,
    NETWORK,
    INTERNAL,
}

/**
 * Payload da mensagem HTTP `ERROR`.
 */
@Serializable
data class ErrorPayload(
    val errorCode: String,
    val httpStatus: Int,
    val category: ErrorCategory,
    val message: String,
    val transferId: String? = null,
    val fileId: String? = null,
    val recoverable: Boolean,
)


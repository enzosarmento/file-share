package br.edu.fileshare.protocol.error

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.protocol.model.ErrorCategory
import br.edu.fileshare.protocol.model.ErrorPayload
import br.edu.fileshare.protocol.model.FsapEnvelope
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapMessageType

/**
 * Catalogo completo e normativo dos 17 codigos de erro do protocolo FSAP/1.0 (`docs/error-codes.md`).
 */
enum class FsapErrorCode(
    val code: String,
    val httpStatus: Int,
    val category: ErrorCategory,
    val recoverable: Boolean,
    val defaultMessage: String,
) {
    MALFORMED_MESSAGE(
        code = "FSAP-ERR-400-MALFORMED_MESSAGE",
        httpStatus = 400,
        category = ErrorCategory.VALIDATION,
        recoverable = false,
        defaultMessage = "Mensagem JSON malformada ou envelope FSAP invalido.",
    ),
    INVALID_FILENAME(
        code = "FSAP-ERR-400-INVALID_FILENAME",
        httpStatus = 400,
        category = ErrorCategory.VALIDATION,
        recoverable = false,
        defaultMessage = "Nome de arquivo invalido ou inseguro.",
    ),
    SIZE_MISMATCH(
        code = "FSAP-ERR-400-SIZE_MISMATCH",
        httpStatus = 400,
        category = ErrorCategory.VALIDATION,
        recoverable = false,
        defaultMessage = "Quantidade de bytes recebidos diverge do tamanho declarado.",
    ),
    UNAUTHORIZED_TRANSFER(
        code = "FSAP-ERR-401-UNAUTHORIZED_TRANSFER",
        httpStatus = 401,
        category = ErrorCategory.AUTH,
        recoverable = false,
        defaultMessage = "Token de transferencia ausente, invalido ou expirado.",
    ),
    TRANSFER_REJECTED(
        code = "FSAP-ERR-403-TRANSFER_REJECTED",
        httpStatus = 403,
        category = ErrorCategory.AUTH,
        recoverable = false,
        defaultMessage = "Solicitacao de transferencia recusada pelo destinatario.",
    ),
    DEVICE_NOT_FOUND(
        code = "FSAP-ERR-404-DEVICE_NOT_FOUND",
        httpStatus = 404,
        category = ErrorCategory.NETWORK,
        recoverable = true,
        defaultMessage = "Dispositivo remoto nao encontrado ou inacessivel.",
    ),
    FILE_NOT_FOUND(
        code = "FSAP-ERR-404-FILE_NOT_FOUND",
        httpStatus = 404,
        category = ErrorCategory.STORAGE,
        recoverable = false,
        defaultMessage = "Arquivo ou sessao solicitada nao existe.",
    ),
    APPROVAL_TIMEOUT(
        code = "FSAP-ERR-408-APPROVAL_TIMEOUT",
        httpStatus = 408,
        category = ErrorCategory.TIMEOUT,
        recoverable = true,
        defaultMessage = "Tempo limite de aprovacao pelo destinatario expirado.",
    ),
    STREAM_TIMEOUT(
        code = "FSAP-ERR-408-STREAM_TIMEOUT",
        httpStatus = 408,
        category = ErrorCategory.TIMEOUT,
        recoverable = true,
        defaultMessage = "Tempo limite de leitura/escrita do fluxo binario expirado.",
    ),
    INVALID_STATE_TRANSITION(
        code = "FSAP-ERR-409-INVALID_STATE_TRANSITION",
        httpStatus = 409,
        category = ErrorCategory.STATE,
        recoverable = false,
        defaultMessage = "Operacao incompatível com o estado atual da sessao FSAP.",
    ),
    FILE_TOO_LARGE(
        code = "FSAP-ERR-413-FILE_TOO_LARGE",
        httpStatus = 413,
        category = ErrorCategory.STORAGE,
        recoverable = false,
        defaultMessage = "Tamanho do arquivo ou payload excede o limite suportado.",
    ),
    HASH_MISMATCH(
        code = "FSAP-ERR-422-HASH_MISMATCH",
        httpStatus = 422,
        category = ErrorCategory.INTEGRITY,
        recoverable = false,
        defaultMessage = "Digest SHA-256 calculado diverge do esperado.",
    ),
    TRANSFER_CANCELLED(
        code = "FSAP-ERR-499-TRANSFER_CANCELLED",
        httpStatus = 499,
        category = ErrorCategory.STATE,
        recoverable = false,
        defaultMessage = "Transferencia cancelada explicitamente.",
    ),
    INTERNAL_ERROR(
        code = "FSAP-ERR-500-INTERNAL_ERROR",
        httpStatus = 500,
        category = ErrorCategory.INTERNAL,
        recoverable = false,
        defaultMessage = "Erro interno inesperado ao processar operacao FSAP.",
    ),
    CONNECTION_LOST(
        code = "FSAP-ERR-503-CONNECTION_LOST",
        httpStatus = 503,
        category = ErrorCategory.NETWORK,
        recoverable = true,
        defaultMessage = "Conexao TCP perdida durante a comunicacao.",
    ),
    UNSUPPORTED_VERSION(
        code = "FSAP-ERR-505-UNSUPPORTED_VERSION",
        httpStatus = 505,
        category = ErrorCategory.VALIDATION,
        recoverable = false,
        defaultMessage = "Versao MAJOR do protocolo FSAP incompativel.",
    ),
    INSUFFICIENT_STORAGE(
        code = "FSAP-ERR-507-INSUFFICIENT_STORAGE",
        httpStatus = 507,
        category = ErrorCategory.STORAGE,
        recoverable = false,
        defaultMessage = "Espaco em armazenamento insuficiente para receber os arquivos.",
    );

    companion object {
        private val byCode: Map<String, FsapErrorCode> = entries.associateBy { it.code }

        fun fromCode(code: String): FsapErrorCode? = byCode[code]
    }
}

/**
 * Excecao tipada do protocolo FSAP mapeada diretamente para um [FsapErrorCode].
 */
open class FsapProtocolException(
    val errorCode: FsapErrorCode,
    override val message: String = errorCode.defaultMessage,
    val transferId: String? = null,
    val fileId: String? = null,
    val recoverable: Boolean = errorCode.recoverable,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Fabrica padronizada para gerar payloads e envelopes `ERROR` em conformidade com o FSAP/1.0.
 */
object FsapErrorFactory {
    fun toPayload(
        errorCode: FsapErrorCode,
        message: String = errorCode.defaultMessage,
        transferId: String? = null,
        fileId: String? = null,
        recoverable: Boolean = errorCode.recoverable,
    ): ErrorPayload = ErrorPayload(
        errorCode = errorCode.code,
        httpStatus = errorCode.httpStatus,
        category = errorCode.category,
        message = message,
        transferId = transferId,
        fileId = fileId,
        recoverable = recoverable,
    )

    fun toEnvelope(
        senderId: String,
        errorCode: FsapErrorCode,
        message: String = errorCode.defaultMessage,
        transferId: String? = null,
        fileId: String? = null,
        recoverable: Boolean = errorCode.recoverable,
        uuidGenerator: UuidGenerator = PlatformUuidGenerator,
        clock: Clock = SystemClock,
    ): FsapEnvelope<ErrorPayload> = FsapEnvelopeFactory.create(
        type = FsapMessageType.ERROR,
        senderId = senderId,
        payload = toPayload(
            errorCode = errorCode,
            message = message,
            transferId = transferId,
            fileId = fileId,
            recoverable = recoverable,
        ),
        uuidGenerator = uuidGenerator,
        clock = clock,
    )

    fun FsapProtocolException.toErrorEnvelope(
        senderId: String,
        uuidGenerator: UuidGenerator = PlatformUuidGenerator,
        clock: Clock = SystemClock,
    ): FsapEnvelope<ErrorPayload> = toEnvelope(
        senderId = senderId,
        errorCode = errorCode,
        message = message,
        transferId = transferId,
        fileId = fileId,
        recoverable = recoverable,
        uuidGenerator = uuidGenerator,
        clock = clock,
    )
}


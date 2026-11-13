package br.edu.fileshare.protocol.validation

import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.AnnouncePayload
import br.edu.fileshare.protocol.model.ByePayload
import br.edu.fileshare.protocol.model.DiscoverPayload
import br.edu.fileshare.protocol.model.FsapEnvelope
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.IdentifyPayload
import br.edu.fileshare.protocol.model.TransferRequestPayload
import br.edu.fileshare.storage.FileNameSanitizer
import br.edu.fileshare.storage.FileNameValidationResult

/**
 * Resultado deterministico das validacoes sintaticas e semanticas do FSAP/1.0.
 */
sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(
        val errorCode: FsapErrorCode,
        val reason: String,
    ) : ValidationResult
}

/**
 * Validadores sintaticos e semanticos do protocolo FSAP/1.0 (`docs/protocol-messages.md`).
 */
object FsapMessageValidator {

    private val UUID_V4_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    )
    private val VERSION_REGEX = Regex("^(\\d+)\\.(\\d+)$")
    private val ISO_UTC_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$")
    private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")
    private val KNOWN_TYPES = FsapMessageType.entries.map { it.name }.toSet()

    fun isValidUuidV4(value: String): Boolean = UUID_V4_REGEX.matches(value)

    fun isValidIsoUtcTimestamp(value: String): Boolean = ISO_UTC_REGEX.matches(value)

    fun isValidPort(port: Int): Boolean =
        port in FsapConstants.MIN_VALID_PORT..FsapConstants.MAX_VALID_PORT

    fun isValidAlias(alias: String): Boolean {
        if (alias.contains('\n') || alias.contains('\r') || alias.contains('\u0000')) return false
        val trimmed = alias.trim()
        return trimmed.isNotEmpty() && trimmed.length <= FsapConstants.MAX_ALIAS_LENGTH
    }

    fun isValidSha256Hex(sha256: String): Boolean = SHA256_HEX_REGEX.matches(sha256)

    fun validateProtocolVersion(version: String): ValidationResult {
        val match = VERSION_REGEX.matchEntire(version)
            ?: return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "Versao FSAP fora do formato MAJOR.MINOR: '$version'.",
            )
        val major = match.groupValues[1].toIntOrNull()
            ?: return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "Componente MAJOR invalido na versao '$version'.",
            )
        if (major != FsapConstants.PROTOCOL_MAJOR) {
            return ValidationResult.Invalid(
                FsapErrorCode.UNSUPPORTED_VERSION,
                "Versao MAJOR $major incompativel; servidor suporta ${FsapConstants.PROTOCOL_MAJOR}.x.",
            )
        }
        return ValidationResult.Valid
    }

    fun validateEnvelope(
        envelope: FsapEnvelope<*>,
        expectedType: FsapMessageType? = null,
    ): ValidationResult {
        if (envelope.protocol != FsapConstants.PROTOCOL_NAME) {
            return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "Campo 'protocol' deve ser '${FsapConstants.PROTOCOL_NAME}', recebido '${envelope.protocol}'.",
            )
        }
        val versionCheck = validateProtocolVersion(envelope.version)
        if (versionCheck is ValidationResult.Invalid) return versionCheck

        if (envelope.type !in KNOWN_TYPES) {
            return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "Tipo de mensagem desconhecido: '${envelope.type}'.",
            )
        }
        if (expectedType != null && envelope.type != expectedType.name) {
            return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "Esperado tipo '${expectedType.name}', mas recebido '${envelope.type}'.",
            )
        }
        if (!isValidUuidV4(envelope.messageId)) {
            return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "Campo 'messageId' nao e UUID v4 valido.",
            )
        }
        if (!isValidIsoUtcTimestamp(envelope.timestamp)) {
            return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "Campo 'timestamp' nao segue ISO-8601 UTC.",
            )
        }
        if (!isValidUuidV4(envelope.senderId)) {
            return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "Campo 'senderId' nao e UUID v4 valido.",
            )
        }
        return ValidationResult.Valid
    }

    fun validateAnnounce(envelope: FsapEnvelope<AnnouncePayload>): ValidationResult {
        val envResult = validateEnvelope(envelope, FsapMessageType.ANNOUNCE)
        if (envResult is ValidationResult.Invalid) return envResult

        val payload = envelope.payload
        if (!isValidAlias(payload.alias)) {
            return ValidationResult.Invalid(FsapErrorCode.MALFORMED_MESSAGE, "Alias invalido em ANNOUNCE.")
        }
        if (!isValidPort(payload.httpPort)) {
            return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "Porta httpPort fora do intervalo 1024..65535: ${payload.httpPort}.",
            )
        }
        return ValidationResult.Valid
    }

    fun validateDiscover(envelope: FsapEnvelope<DiscoverPayload>): ValidationResult {
        val envResult = validateEnvelope(envelope, FsapMessageType.DISCOVER)
        if (envResult is ValidationResult.Invalid) return envResult

        val payload = envelope.payload
        if (!isValidAlias(payload.requesterAlias)) {
            return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "requesterAlias invalido em DISCOVER.",
            )
        }
        if (!isValidPort(payload.requesterHttpPort)) {
            return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "requesterHttpPort fora do intervalo 1024..65535: ${payload.requesterHttpPort}.",
            )
        }
        return ValidationResult.Valid
    }

    fun validateBye(envelope: FsapEnvelope<ByePayload>): ValidationResult {
        val envResult = validateEnvelope(envelope, FsapMessageType.BYE)
        if (envResult is ValidationResult.Invalid) return envResult

        val reason = envelope.payload.reason
        if (reason != null && (reason.length > FsapConstants.MAX_ALIAS_LENGTH || reason.contains('\u0000'))) {
            return ValidationResult.Invalid(FsapErrorCode.MALFORMED_MESSAGE, "Campo reason invalido em BYE.")
        }
        return ValidationResult.Valid
    }

    fun validateIdentify(envelope: FsapEnvelope<IdentifyPayload>): ValidationResult {
        val envResult = validateEnvelope(envelope, FsapMessageType.IDENTIFY)
        if (envResult is ValidationResult.Invalid) return envResult

        val payload = envelope.payload
        if (!isValidAlias(payload.alias)) {
            return ValidationResult.Invalid(FsapErrorCode.MALFORMED_MESSAGE, "Alias invalido em IDENTIFY.")
        }
        if (!isValidPort(payload.httpPort)) {
            return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "Porta httpPort fora do intervalo 1024..65535: ${payload.httpPort}.",
            )
        }
        return ValidationResult.Valid
    }

    fun validateTransferRequest(envelope: FsapEnvelope<TransferRequestPayload>): ValidationResult {
        val envResult = validateEnvelope(envelope, FsapMessageType.TRANSFER_REQUEST)
        if (envResult is ValidationResult.Invalid) return envResult

        val payload = envelope.payload
        if (!isValidUuidV4(payload.transferId)) {
            return ValidationResult.Invalid(FsapErrorCode.MALFORMED_MESSAGE, "transferId nao e UUID v4 valido.")
        }
        if (!isValidAlias(payload.senderAlias)) {
            return ValidationResult.Invalid(FsapErrorCode.MALFORMED_MESSAGE, "senderAlias invalido.")
        }
        if (payload.files.isEmpty() || payload.files.size > FsapConstants.MAX_FILES_PER_BATCH) {
            return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "Manifesto deve conter entre 1 e ${FsapConstants.MAX_FILES_PER_BATCH} arquivos.",
            )
        }
        if (payload.totalBytes < 0L) {
            return ValidationResult.Invalid(FsapErrorCode.MALFORMED_MESSAGE, "totalBytes nao pode ser negativo.")
        }

        val seenFileIds = HashSet<String>(payload.files.size)
        var accumulatedBytes = 0L

        for (file in payload.files) {
            if (file.fileId.isBlank() || !seenFileIds.add(file.fileId)) {
                return ValidationResult.Invalid(
                    FsapErrorCode.MALFORMED_MESSAGE,
                    "fileId ausente ou duplicado: '${file.fileId}'.",
                )
            }
            val fileNameCheck = FileNameSanitizer.validate(file.fileName)
            if (fileNameCheck is FileNameValidationResult.Unsafe) {
                return ValidationResult.Invalid(
                    FsapErrorCode.INVALID_FILENAME,
                    "Nome de arquivo invalido ('${file.fileName}'): ${fileNameCheck.reason}",
                )
            }
            if (file.sizeBytes < 0L) {
                return ValidationResult.Invalid(
                    FsapErrorCode.MALFORMED_MESSAGE,
                    "sizeBytes negativo para '${file.fileName}'.",
                )
            }
            if (file.sizeBytes > FsapConstants.MAX_FILE_SIZE_BYTES) {
                return ValidationResult.Invalid(
                    FsapErrorCode.FILE_TOO_LARGE,
                    "Arquivo '${file.fileName}' excede o limite de 64 GiB.",
                )
            }
            if (!isValidSha256Hex(file.sha256)) {
                return ValidationResult.Invalid(
                    FsapErrorCode.MALFORMED_MESSAGE,
                    "SHA-256 invalido para '${file.fileName}'; esperado hex minusculo de 64 chars.",
                )
            }
            if (accumulatedBytes > Long.MAX_VALUE - file.sizeBytes) {
                return ValidationResult.Invalid(
                    FsapErrorCode.FILE_TOO_LARGE,
                    "Estouro aritmetico ao somar totalBytes do lote.",
                )
            }
            accumulatedBytes += file.sizeBytes
        }

        if (accumulatedBytes != payload.totalBytes) {
            return ValidationResult.Invalid(
                FsapErrorCode.MALFORMED_MESSAGE,
                "totalBytes (${payload.totalBytes}) difere da soma dos arquivos ($accumulatedBytes).",
            )
        }

        return ValidationResult.Valid
    }
}


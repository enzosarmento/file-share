package br.edu.fileshare.network.server

import br.edu.fileshare.core.concurrency.PlatformLock
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode

/**
 * Primitivas criptograficas especificas de plataforma para geracao de tokens efemeros (256 bits)
 * e comparacao em tempo constante contra ataques de temporizacao.
 */
expect object PlatformTokenCrypto {
    fun generateUrlSafeToken(byteLength: Int = 32): String
    fun constantTimeEquals(expected: String, actual: String): Boolean
}

sealed interface TokenValidationResult {
    data object Authorized : TokenValidationResult

    data class Denied(
        val errorCode: FsapErrorCode,
        val reason: String,
    ) : TokenValidationResult
}

data class IssuedTransferToken(
    val transferId: String,
    val token: String,
    val expiresAtEpochMillis: Long,
)

/**
 * Gerenciador de credenciais efemeras (`transferToken`) por sessao aprovada (`TASK-SEC-002`).
 * - Gera tokens de 256 bits via `SecureRandom` (`32` bytes -> 43 chars base64url).
 * - Exige correspondencia em tempo constante (`MessageDigest.isEqual`).
 * - Aplica expiracao por inatividade (`T_TOKEN_IDLE_TTL_MS` = 30 s).
 */
class TransferTokenManager(
    private val clock: Clock = SystemClock,
    private val logger: FsapLogger? = null,
    private val tokenTtlMillis: Long = FsapConstants.T_TOKEN_IDLE_TTL_MS,
) {
    private data class TokenEntry(
        val transferId: String,
        val token: String,
        val allowedFileIds: Set<String>,
        var expiresAtEpochMillis: Long,
    )

    private val lock = PlatformLock()
    private val activeTokens = HashMap<String, TokenEntry>()

    fun issueToken(
        transferId: String,
        allowedFileIds: Set<String>,
        ttlMillis: Long = tokenTtlMillis,
    ): IssuedTransferToken {
        require(allowedFileIds.isNotEmpty()) { "Sessao deve aprovar ao menos um fileId." }
        val now = clock.epochMillis()
        val token = PlatformTokenCrypto.generateUrlSafeToken(32)
        val expiresAt = now + ttlMillis

        lock.withLock {
            activeTokens[transferId] = TokenEntry(
                transferId = transferId,
                token = token,
                allowedFileIds = allowedFileIds.toSet(),
                expiresAtEpochMillis = expiresAt,
            )
        }

        logger?.debug(
            component = "TransferTokenManager",
            message = "Token efemero emitido para sessao (${allowedFileIds.size} arquivos, TTL=${ttlMillis}ms).",
            context = LogContext(transferId = transferId),
        )
        return IssuedTransferToken(
            transferId = transferId,
            token = token,
            expiresAtEpochMillis = expiresAt,
        )
    }

    fun validateAndRefresh(
        transferId: String,
        fileId: String,
        presentedToken: String?,
        refreshIdleTtl: Boolean = true,
    ): TokenValidationResult = validateInternal(
        transferId = transferId,
        requiredFileId = fileId,
        presentedToken = presentedToken,
        refreshIdleTtl = refreshIdleTtl,
    )

    fun validateSessionToken(
        transferId: String,
        presentedToken: String?,
        refreshIdleTtl: Boolean = false,
    ): TokenValidationResult = validateInternal(
        transferId = transferId,
        requiredFileId = null,
        presentedToken = presentedToken,
        refreshIdleTtl = refreshIdleTtl,
    )

    private fun validateInternal(
        transferId: String,
        requiredFileId: String?,
        presentedToken: String?,
        refreshIdleTtl: Boolean,
    ): TokenValidationResult {
        if (presentedToken.isNullOrBlank()) {
            return TokenValidationResult.Denied(
                errorCode = FsapErrorCode.UNAUTHORIZED_TRANSFER,
                reason = "Cabecalho X-FSAP-Transfer-Token ausente.",
            )
        }

        val now = clock.epochMillis()
        val outcome = lock.withLock {
            val entry = activeTokens[transferId]
                ?: return@withLock TokenValidationResult.Denied(
                    errorCode = FsapErrorCode.UNAUTHORIZED_TRANSFER,
                    reason = "Nenhum token ativo encontrado para a sessao '$transferId'.",
                )

            if (now >= entry.expiresAtEpochMillis) {
                activeTokens.remove(transferId)
                return@withLock TokenValidationResult.Denied(
                    errorCode = FsapErrorCode.UNAUTHORIZED_TRANSFER,
                    reason = "Token da sessao '$transferId' expirou por inatividade.",
                )
            }

            if (!PlatformTokenCrypto.constantTimeEquals(entry.token, presentedToken)) {
                return@withLock TokenValidationResult.Denied(
                    errorCode = FsapErrorCode.UNAUTHORIZED_TRANSFER,
                    reason = "Token de transferencia invalido para a sessao '$transferId'.",
                )
            }

            if (requiredFileId != null && requiredFileId !in entry.allowedFileIds) {
                return@withLock TokenValidationResult.Denied(
                    errorCode = FsapErrorCode.FILE_NOT_FOUND,
                    reason = "Arquivo '$requiredFileId' nao consta no manifesto aprovado da sessao '$transferId'.",
                )
            }

            if (refreshIdleTtl) {
                entry.expiresAtEpochMillis = now + tokenTtlMillis
            }
            TokenValidationResult.Authorized
        }

        if (outcome is TokenValidationResult.Denied) {
            logger?.warn(
                component = "TransferTokenManager",
                message = "Acesso negado (${outcome.errorCode.code}): ${outcome.reason}",
                context = LogContext(transferId = transferId),
            )
        }
        return outcome
    }

    fun touchSession(transferId: String): Boolean {
        val now = clock.epochMillis()
        return lock.withLock {
            val entry = activeTokens[transferId] ?: return@withLock false
            if (now >= entry.expiresAtEpochMillis) {
                activeTokens.remove(transferId)
                return@withLock false
            }
            entry.expiresAtEpochMillis = now + tokenTtlMillis
            true
        }
    }

    fun revoke(transferId: String): Boolean = lock.withLock {
        activeTokens.remove(transferId) != null
    }

    fun purgeExpired(): Int {
        val now = clock.epochMillis()
        return lock.withLock {
            val expiredIds = activeTokens.values
                .filter { now >= it.expiresAtEpochMillis }
                .map { it.transferId }
            expiredIds.forEach { activeTokens.remove(it) }
            expiredIds.size
        }
    }

    fun activeCount(): Int = lock.withLock { activeTokens.size }
}


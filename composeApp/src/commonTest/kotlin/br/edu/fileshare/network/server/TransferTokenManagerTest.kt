package br.edu.fileshare.network.server

import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransferTokenManagerTest {

    private class FakeClock(var currentMs: Long = 1_700_000_000_000L) : Clock {
        override fun epochMillis(): Long = currentMs
        override fun nowIsoUtc(): String = "2026-11-12T23:25:00Z"
    }

    private companion object {
        const val TRANSFER_A = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val TRANSFER_B = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
        val BASE64URL_REGEX = Regex("^[A-Za-z0-9_-]{32,128}$")
    }

    @Test
    fun `emite tokens opacos base64url unicos com 256 bits e valida arquivo autorizado`() {
        val clock = FakeClock()
        val manager = TransferTokenManager(clock = clock)

        val issuedA = manager.issueToken(TRANSFER_A, setOf("f1", "f2"))
        val issuedB = manager.issueToken(TRANSFER_B, setOf("f1"))

        assertTrue(BASE64URL_REGEX.matches(issuedA.token))
        assertEquals(43, issuedA.token.length)
        assertTrue(issuedA.token != issuedB.token)

        assertIs<TokenValidationResult.Authorized>(
            manager.validateAndRefresh(TRANSFER_A, "f1", issuedA.token),
        )
        assertIs<TokenValidationResult.Authorized>(
            manager.validateAndRefresh(TRANSFER_A, "f2", issuedA.token),
        )
    }

    @Test
    fun `TC-FSAP-013 rejeita token ausente, token de outra sessao ou arquivo nao autorizado`() {
        val clock = FakeClock()
        val manager = TransferTokenManager(clock = clock)

        val issuedA = manager.issueToken(TRANSFER_A, setOf("f1"))
        val issuedB = manager.issueToken(TRANSFER_B, setOf("f1"))

        val missing = assertIs<TokenValidationResult.Denied>(
            manager.validateAndRefresh(TRANSFER_A, "f1", null),
        )
        assertEquals(FsapErrorCode.UNAUTHORIZED_TRANSFER, missing.errorCode)

        val wrongSession = assertIs<TokenValidationResult.Denied>(
            manager.validateAndRefresh(TRANSFER_A, "f1", issuedB.token),
        )
        assertEquals(FsapErrorCode.UNAUTHORIZED_TRANSFER, wrongSession.errorCode)

        val tampered = issuedA.token.dropLast(1) + if (issuedA.token.last() == 'A') 'B' else 'A'
        val wrongChars = assertIs<TokenValidationResult.Denied>(
            manager.validateAndRefresh(TRANSFER_A, "f1", tampered),
        )
        assertEquals(FsapErrorCode.UNAUTHORIZED_TRANSFER, wrongChars.errorCode)

        val unknownFile = assertIs<TokenValidationResult.Denied>(
            manager.validateAndRefresh(TRANSFER_A, "file-nao-aprovado", issuedA.token),
        )
        assertEquals(FsapErrorCode.FILE_NOT_FOUND, unknownFile.errorCode)
    }

    @Test
    fun `TC-FSAP-013 renova TTL durante atividade e expira apos 30s de ociosidade ou revogacao`() {
        val clock = FakeClock()
        val manager = TransferTokenManager(
            clock = clock,
            tokenTtlMillis = FsapConstants.T_TOKEN_IDLE_TTL_MS,
        )

        val issued = manager.issueToken(TRANSFER_A, setOf("f1"))

        // 20s depois ainda valido e renova TTL para t + 50s
        clock.currentMs += 20_000L
        assertIs<TokenValidationResult.Authorized>(
            manager.validateAndRefresh(TRANSFER_A, "f1", issued.token),
        )

        // Mais 20s (t + 40s) ainda valido gracas a renovacao
        clock.currentMs += 20_000L
        assertTrue(manager.touchSession(TRANSFER_A))

        // Mais 30s sem atividade -> expirado
        clock.currentMs += FsapConstants.T_TOKEN_IDLE_TTL_MS
        val expired = assertIs<TokenValidationResult.Denied>(
            manager.validateAndRefresh(TRANSFER_A, "f1", issued.token),
        )
        assertEquals(FsapErrorCode.UNAUTHORIZED_TRANSFER, expired.errorCode)
        assertEquals(0, manager.activeCount())

        // Revogacao explicita
        val reissued = manager.issueToken(TRANSFER_B, setOf("f1"))
        assertTrue(manager.revoke(TRANSFER_B))
        assertFalse(manager.revoke(TRANSFER_B))
        val afterRevoke = assertIs<TokenValidationResult.Denied>(
            manager.validateAndRefresh(TRANSFER_B, "f1", reissued.token),
        )
        assertEquals(FsapErrorCode.UNAUTHORIZED_TRANSFER, afterRevoke.errorCode)
    }
}


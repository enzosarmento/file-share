package br.edu.fileshare.network.server

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.protocol.model.CancelledBy
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.TransferRequestPayload
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.InMemoryFileSink
import br.edu.fileshare.storage.StoredFileDescriptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServerTransferSessionManagerTest {

    private class MutableClock(var nowMs: Long = 1_700_000_000_000L) : Clock {
        override fun epochMillis(): Long = nowMs
        override fun nowIsoUtc(): String = "2026-11-12T23:30:00Z"
    }

    private companion object {
        const val SENDER_UUID = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        const val SHA_A = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        const val SHA_B = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

        fun sampleRequest(transferId: String, fileCount: Int = 2): TransferRequestPayload {
            val files = (1..fileCount).map { i ->
                FileItemDto(
                    fileId = "f$i",
                    fileName = "arquivo_$i.bin",
                    sizeBytes = 100L * i,
                    sha256 = if (i % 2 == 0) SHA_B else SHA_A,
                )
            }
            return TransferRequestPayload(
                transferId = transferId,
                senderAlias = "Sender Lab",
                senderDeviceType = DeviceType.DESKTOP,
                totalBytes = files.sumOf { it.sizeBytes },
                files = files,
            )
        }
    }

    @Test
    fun `ciclo completo de multi-arquivo atualiza progresso, estado e revoga token ao concluir`() =
        runTest {
            val clock = MutableClock()
            val logger = InMemoryFsapLogger(clock)
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(clock = clock, logger = logger)
            val manager = ServerTransferSessionManager(
                tokenManager = tokens,
                fileSink = sink,
                scope = backgroundScope,
                logger = logger,
                clock = clock,
            )

            val transferId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
            val created = assertIs<CreateSessionResult.Created>(
                manager.createIncomingSession(SENDER_UUID, sampleRequest(transferId, fileCount = 2)),
            )
            assertEquals(TransferState.PENDING_APPROVAL, created.session.fsm.currentState())

            val token = manager.acceptSession(transferId)
            assertEquals(TransferState.ACCEPTED, created.session.fsm.currentState())

            // Arquivo 1/2
            manager.beginFileStream(transferId, "f1", token.token, 100L, SHA_A)
            manager.updateFileProgress(transferId, "f1", 50L)
            val midStatus = manager.getStatusPayload(transferId)
            assertEquals("TRANSFERRING", midStatus.state)
            assertEquals(50L, midStatus.totalBytesTransferred)
            assertEquals("f1", midStatus.currentFileId)

            val stateAfterF1 = manager.completeFileVerification(
                transferId,
                "f1",
                SHA_A,
                StoredFileDescriptor("arquivo_1.bin", "mem://arquivo_1.bin", 100L, SHA_A),
            )
            assertEquals(TransferState.TRANSFERRING, stateAfterF1)

            // Tentativa de concluir antes do arquivo 2 falha com 409
            val premature = assertFailsWith<FsapProtocolException> {
                manager.completeTransfer(transferId)
            }
            assertEquals(FsapErrorCode.INVALID_STATE_TRANSITION, premature.errorCode)

            // Arquivo 2/2
            manager.beginFileStream(transferId, "f2", token.token, 200L, SHA_B)
            val stateAfterF2 = manager.completeFileVerification(
                transferId,
                "f2",
                SHA_B,
                StoredFileDescriptor("arquivo_2.bin", "mem://arquivo_2.bin", 200L, SHA_B),
            )
            assertEquals(TransferState.VERIFYING, stateAfterF2)

            val completed = manager.completeTransfer(transferId)
            assertEquals("COMPLETED", completed.state)
            assertEquals(2, completed.completedFiles.size)
            assertEquals(0, tokens.activeCount())
        }

    @Test
    fun `sessao em PENDING_APPROVAL expira apos 60s e sessao ACCEPTED ociosa expira apos 30s`() =
        runTest {
            val clock = MutableClock()
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(clock = clock)
            val manager = ServerTransferSessionManager(
                tokenManager = tokens,
                fileSink = sink,
                scope = backgroundScope,
                clock = clock,
            )

            // Caso 1: timeout de aprovacao 60s
            val pendingId = "11111111-1111-4111-8111-111111111111"
            manager.createIncomingSession(SENDER_UUID, sampleRequest(pendingId, fileCount = 1))
            advanceTimeBy(FsapConstants.T_APPROVAL_TIMEOUT_MS - 1L)
            runCurrent()
            assertEquals(TransferState.PENDING_APPROVAL, manager.findSnapshot(pendingId)?.state)

            advanceTimeBy(2L)
            runCurrent()
            val expiredPending = manager.findSnapshot(pendingId)
            assertEquals(TransferState.FAILED, expiredPending?.state)
            assertEquals(FsapErrorCode.APPROVAL_TIMEOUT, expiredPending?.errorCode)

            // Caso 2: token ocioso 30s em ACCEPTED
            val idleId = "22222222-2222-4222-8222-222222222222"
            manager.createIncomingSession(SENDER_UUID, sampleRequest(idleId, fileCount = 1))
            manager.acceptSession(idleId)
            assertEquals(TransferState.ACCEPTED, manager.findSnapshot(idleId)?.state)
            assertEquals(1, tokens.activeCount())

            advanceTimeBy(FsapConstants.T_TOKEN_IDLE_TTL_MS - 1L)
            runCurrent()
            assertEquals(TransferState.ACCEPTED, manager.findSnapshot(idleId)?.state)

            advanceTimeBy(2L)
            runCurrent()
            val expiredIdle = manager.findSnapshot(idleId)
            assertEquals(TransferState.FAILED, expiredIdle?.state)
            assertEquals(FsapErrorCode.UNAUTHORIZED_TRANSFER, expiredIdle?.errorCode)
            assertEquals(0, tokens.activeCount())
        }

    @Test
    fun `limita sessoes simultaneas, isola cancelamento e executa 5 sessoes concorrentes sem interferencia`() =
        runTest {
            val clock = MutableClock()
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(clock = clock)
            val manager = ServerTransferSessionManager(
                tokenManager = tokens,
                fileSink = sink,
                scope = backgroundScope,
                clock = clock,
                maxConcurrentSessions = 5,
            )

            val ids = (1..5).map { idx -> "0000000$idx-0000-4000-8000-00000000000$idx" }
            ids.forEach { id ->
                assertIs<CreateSessionResult.Created>(
                    manager.createIncomingSession(SENDER_UUID, sampleRequest(id, fileCount = 1)),
                )
            }

            // 6a sessao simultanea retorna CapacityExceeded
            val extraId = "00000006-0000-4000-8000-000000000006"
            assertIs<CreateSessionResult.CapacityExceeded>(
                manager.createIncomingSession(SENDER_UUID, sampleRequest(extraId, fileCount = 1)),
            )

            // Abre um .part para sessao 1 e cancela apenas sessao 1
            val tok1 = manager.acceptSession(ids[0])
            manager.beginFileStream(ids[0], "f1", tok1.token, 100L, SHA_A)
            sink.openPartSession(ids[0], "f1", "arquivo_1.bin", 100L)
            manager.cancelTransfer(ids[0], CancelledBy.SENDER, "Abortado")
            assertEquals(TransferState.CANCELLED, manager.findSnapshot(ids[0])?.state)
            assertTrue(sink.stagedPartKeys().isEmpty())

            // As outras 4 sessoes seguem concorrentemente ate COMPLETED
            ids.drop(1).map { id ->
                async {
                    val tok = manager.acceptSession(id)
                    manager.beginFileStream(id, "f1", tok.token, 100L, SHA_A)
                    manager.completeFileVerification(
                        id,
                        "f1",
                        SHA_A,
                        StoredFileDescriptor("ok_$id.bin", "mem://$id", 100L, SHA_A),
                    )
                    manager.completeTransfer(id)
                }
            }.awaitAll()

            ids.drop(1).forEach { id ->
                assertEquals(TransferState.COMPLETED, manager.findSnapshot(id)?.state)
            }

            // Vaga liberada -> 6a sessao agora e aceita normalmente
            assertIs<CreateSessionResult.Created>(
                manager.createIncomingSession(SENDER_UUID, sampleRequest(extraId, fileCount = 1)),
            )
        }
}


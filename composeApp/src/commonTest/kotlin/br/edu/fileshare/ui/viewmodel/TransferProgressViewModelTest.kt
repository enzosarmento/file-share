package br.edu.fileshare.ui.viewmodel

import br.edu.fileshare.network.transfer.TransferDirection
import br.edu.fileshare.network.transfer.TransferProgressSnapshot
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.ui.format.ByteFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransferProgressViewModelTest {

    private fun snapshot(
        transferId: String = "t-1",
        peerAlias: String = "Ubuntu-Desk",
        direction: TransferDirection = TransferDirection.SENDING,
        state: TransferState = TransferState.TRANSFERRING,
        bytesTransferred: Long = 512L * 1024L,
        totalBytes: Long = 2L * 1024L * 1024L,
        currentFileId: String? = "f-1",
        currentFileName: String? = "relatorio.pdf",
        filesCompleted: Int = 0,
        totalFiles: Int = 2,
        speedBytesPerSecond: Long = 256L * 1024L,
        estimatedRemainingSeconds: Long? = 6L,
        errorCode: FsapErrorCode? = null,
        errorMessage: String? = null,
        updatedAtEpochMillis: Long = 1_000L,
    ): TransferProgressSnapshot = TransferProgressSnapshot(
        transferId = transferId,
        peerAlias = peerAlias,
        direction = direction,
        state = state,
        bytesTransferred = bytesTransferred,
        totalBytes = totalBytes,
        currentFileId = currentFileId,
        currentFileName = currentFileName,
        filesCompleted = filesCompleted,
        totalFiles = totalFiles,
        speedBytesPerSecond = speedBytesPerSecond,
        estimatedRemainingSeconds = estimatedRemainingSeconds,
        errorCode = errorCode,
        errorMessage = errorMessage,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    @Test
    fun `formatadores de bytes velocidade e eta produzem texto legivel`() {
        assertEquals("0 B", ByteFormatter.formatBytes(0L))
        assertEquals("512 B", ByteFormatter.formatBytes(512L))
        assertEquals("1.5 KB", ByteFormatter.formatBytes(1536L))
        assertEquals("2.00 MB", ByteFormatter.formatBytes(2L * 1024L * 1024L))
        assertEquals("1.50 GB", ByteFormatter.formatBytes(1_610_612_736L))

        assertEquals("0 B/s", ByteFormatter.formatSpeed(0L))
        assertEquals("256.0 KB/s", ByteFormatter.formatSpeed(256L * 1024L))

        assertEquals("--", ByteFormatter.formatEta(null))
        assertEquals("< 1s", ByteFormatter.formatEta(0L))
        assertEquals("45s", ByteFormatter.formatEta(45L))
        assertEquals("2m 05s", ByteFormatter.formatEta(125L))
        assertEquals("1h 05m", ByteFormatter.formatEta(3900L))
    }

    @Test
    fun `mapeamento cobre todos os 9 estados FSAP e oculta velocidade em estados terminais`() {
        val activeStates = listOf(
            TransferState.CREATED,
            TransferState.PENDING_APPROVAL,
            TransferState.ACCEPTED,
            TransferState.TRANSFERRING,
            TransferState.VERIFYING,
        )
        activeStates.forEach { state ->
            val model = TransferProgressViewModel.toUiModel(
                snapshot(
                    state = state,
                    speedBytesPerSecond = 1024L,
                    estimatedRemainingSeconds = 12L,
                ),
            )
            assertTrue(model.canCancel, "Estado ativo $state deveria permitir cancelamento")
            assertEquals("1.0 KB/s", model.speedLabel)
            assertEquals("ETA: 12s", model.etaLabel)
        }

        val terminalStates = listOf(
            TransferState.COMPLETED,
            TransferState.REJECTED,
            TransferState.CANCELLED,
            TransferState.FAILED,
        )
        terminalStates.forEach { state ->
            val model = TransferProgressViewModel.toUiModel(
                snapshot(
                    state = state,
                    speedBytesPerSecond = 1024L,
                    estimatedRemainingSeconds = 12L,
                ),
            )
            assertFalse(model.canCancel, "Estado terminal $state nao deveria permitir cancelamento")
            assertEquals("--", model.speedLabel)
            assertEquals("--", model.etaLabel)
        }
    }

    @Test
    fun `viewmodel reage ao fluxo de snapshots, formata erros e ordena por atualizacao mais recente`() =
        runTest {
            val flow = MutableStateFlow<List<TransferProgressSnapshot>>(emptyList())
            val vm = TransferProgressViewModel(
                snapshotsFlow = flow,
                cancelPort = { _, _ -> false },
                scope = backgroundScope,
            )
            runCurrent()
            assertTrue(vm.uiState.value.items.isEmpty())
            assertEquals(0, vm.uiState.value.activeCount)

            val older = snapshot(
                transferId = "t-old",
                peerAlias = "Linux-PC",
                direction = TransferDirection.SENDING,
                state = TransferState.FAILED,
                bytesTransferred = 1024L,
                totalBytes = 4096L,
                errorCode = FsapErrorCode.HASH_MISMATCH,
                errorMessage = "Checksum diverge no destino",
                updatedAtEpochMillis = 100L,
            )
            val newer = snapshot(
                transferId = "t-new",
                peerAlias = "Pixel-9",
                direction = TransferDirection.RECEIVING,
                state = TransferState.TRANSFERRING,
                bytesTransferred = 1024L * 1024L,
                totalBytes = 2L * 1024L * 1024L,
                filesCompleted = 1,
                totalFiles = 3,
                speedBytesPerSecond = 512L * 1024L,
                estimatedRemainingSeconds = 2L,
                updatedAtEpochMillis = 200L,
            )
            flow.value = listOf(older, newer)
            runCurrent()

            val state = vm.uiState.value
            assertEquals(1, state.activeCount)
            assertEquals(listOf("t-new", "t-old"), state.items.map { it.transferId })

            val first = state.items[0]
            assertEquals("Recebendo", first.directionLabel)
            assertEquals("Transferindo", first.stateLabel)
            assertEquals(TransferStatusTone.ACTIVE, first.tone)
            assertEquals("50%", first.percentLabel)
            assertEquals("1.00 MB / 2.00 MB", first.bytesSummary)
            assertEquals("1/3 arquivo(s)", first.filesSummary)
            assertEquals("512.0 KB/s", first.speedLabel)
            assertEquals("ETA: 2s", first.etaLabel)

            val second = state.items[1]
            assertEquals("Falhou", second.stateLabel)
            assertEquals(TransferStatusTone.ERROR, second.tone)
            assertEquals("[${FsapErrorCode.HASH_MISMATCH.code}] Checksum diverge no destino", second.errorSummary)
        }

    @Test
    fun `cancelTransferInternal sinaliza estado em cancelamento e evita chamadas duplicadas concorrentes`() =
        runTest {
            val flow = MutableStateFlow(listOf(snapshot(transferId = "t-cancel")))
            val gate = CompletableDeferred<Boolean>()
            val calls = mutableListOf<Pair<String, String>>()

            val vm = TransferProgressViewModel(
                snapshotsFlow = flow,
                cancelPort = { id, reason ->
                    calls += id to reason
                    gate.await()
                },
                scope = backgroundScope,
            )
            runCurrent()

            val firstAttempt = async { vm.cancelTransferInternal("t-cancel") }
            runCurrent()
            assertTrue(vm.uiState.value.items.single().isCancelling)

            // Segunda tentativa enquanto a primeira ainda suspende deve retornar false imediatamente
            val duplicate = vm.cancelTransferInternal("t-cancel")
            assertFalse(duplicate)

            gate.complete(true)
            assertTrue(firstAttempt.await())
            runCurrent()

            assertFalse(vm.uiState.value.items.single().isCancelling)
            assertEquals(listOf("t-cancel" to "USER_CANCELLED"), calls)
        }
}


package br.edu.fileshare.network.transfer

import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.protocol.statemachine.TransferState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TransferProgressTrackerTest {

    private class FakeClock(var currentMs: Long = 1_000L) : Clock {
        override fun epochMillis(): Long = currentMs
        override fun nowIsoUtc(): String = "2026-11-12T12:00:00Z"
    }

    @Test
    fun `TC-FSAP-016 calcula percentual monotonicamente velocidade movel ETA e aplica throttling de 100ms`() {
        val fakeClock = FakeClock(currentMs = 10_000L)
        val tracker = TransferProgressTracker(clock = fakeClock, throttleMillis = 100L)
        val totalBytes = 1_000_000L

        tracker.registerTransfer(
            transferId = "tx-1",
            peerAlias = "Celular Teste",
            direction = TransferDirection.SENDING,
            totalBytes = totalBytes,
            totalFiles = 2,
            initialState = TransferState.TRANSFERRING,
        )

        // Dentro da janela de 100ms (+30ms): nao deve emitir no StateFlow ainda
        fakeClock.currentMs = 10_030L
        assertFalse(tracker.reportProgress("tx-1", bytesTransferred = 65_536L, currentFileId = "f1"))
        assertEquals(0L, tracker.transfers.value.first().bytesTransferred)

        // Em +100ms: deve emitir imediatamente com velocidade e percentual
        fakeClock.currentMs = 10_100L
        assertTrue(tracker.reportProgress("tx-1", bytesTransferred = 250_000L, currentFileId = "f1"))
        val snap1 = tracker.transfers.value.first()
        assertEquals(250_000L, snap1.bytesTransferred)
        assertEquals(25, snap1.percentComplete)
        assertEquals(2_500_000L, snap1.speedBytesPerSecond) // 250 KB / 0.1 s = 2.5 MB/s
        assertNotNull(snap1.estimatedRemainingSeconds)
        assertEquals(1L, snap1.estimatedRemainingSeconds) // 750 KB / 2.5 MB/s = 0.3s -> ceil 1s

        // Multiplos arquivos concorrentes mantendo isolamento
        tracker.registerTransfer(
            transferId = "tx-2",
            peerAlias = "Desktop Sala",
            direction = TransferDirection.RECEIVING,
            totalBytes = 200_000L,
            totalFiles = 1,
            initialState = TransferState.TRANSFERRING,
        )
        fakeClock.currentMs = 10_500L
        assertTrue(
            tracker.reportProgress(
                transferId = "tx-2",
                bytesTransferred = 100_000L,
                currentFileId = "f1",
                currentFileName = "foto.png",
            ),
        )
        assertEquals(2, tracker.transfers.value.size)

        // Atingir 100% em tx-1 sempre emite mesmo dentro da janela de throttling (< 100ms)
        fakeClock.currentMs = 10_520L
        assertTrue(
            tracker.reportProgress(
                transferId = "tx-1",
                bytesTransferred = totalBytes,
                filesCompleted = 2,
            ),
        )
        val snapFull = tracker.getSnapshot("tx-1")!!
        assertEquals(100, snapFull.percentComplete)
        assertEquals(totalBytes, snapFull.bytesTransferred)

        tracker.updateState("tx-1", TransferState.COMPLETED, filesCompleted = 2)
        val completed = tracker.transfers.value.first { it.transferId == "tx-1" }
        assertEquals(TransferState.COMPLETED, completed.state)
        assertEquals(0L, completed.speedBytesPerSecond)
        assertEquals(2, completed.filesCompleted)
    }
}

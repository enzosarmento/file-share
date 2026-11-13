package br.edu.fileshare.ui.viewmodel

import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.network.server.ApprovalRequestContext
import br.edu.fileshare.network.server.InteractiveTransferApprovalCoordinator
import br.edu.fileshare.network.server.TransferApprovalDecision
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.RejectReasonCode
import br.edu.fileshare.protocol.model.TransferRequestPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingApprovalViewModelTest {

    private class FakeClock(var nowMs: Long = 1_763_000_000_000L) : Clock {
        override fun epochMillis(): Long = nowMs
        override fun nowIsoUtc(): String = "2026-11-13T00:15:00Z"
    }

    private fun sampleContext(
        transferId: String,
        senderAlias: String,
        receivedAt: Long,
        fileIds: List<String> = listOf("f1", "f2"),
    ): ApprovalRequestContext {
        val items = fileIds.mapIndexed { idx, fid ->
            FileItemDto(
                fileId = fid,
                fileName = "arquivo_${idx + 1}.bin",
                sizeBytes = 1024L * (idx + 1),
                sha256 = "a".repeat(64),
            )
        }
        return ApprovalRequestContext(
            transferId = transferId,
            senderId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            request = TransferRequestPayload(
                transferId = transferId,
                senderAlias = senderAlias,
                senderDeviceType = DeviceType.MOBILE,
                totalBytes = items.sumOf { it.sizeBytes },
                files = items,
            ),
            receivedAtEpochMillis = receivedAt,
        )
    }

    @Test
    fun `fila interativa exibe detalhes do remetente, aceita primeiro pedido, avanca fila e recusa segundo`() =
        runTest {
            val clock = FakeClock(nowMs = 1_000_000L)
            val coordinator = InteractiveTransferApprovalCoordinator()
            var acceptedNotifications = 0

            val vm = IncomingApprovalViewModel(
                coordinator = coordinator,
                scope = backgroundScope,
                clock = clock,
                approvalTimeoutMs = FsapConstants.T_APPROVAL_TIMEOUT_MS,
                countdownTickMs = 0L,
                onAccepted = { acceptedNotifications++ },
            )
            runCurrent()
            assertNull(vm.uiState.value.activePrompt)
            assertEquals(0, vm.uiState.value.pendingCount)

            val req1 = sampleContext("t-1", "Pixel-8", receivedAt = 1_000_000L, fileIds = listOf("f1", "f2"))
            val req2 = sampleContext("t-2", "Linux-Note", receivedAt = 1_005_000L, fileIds = listOf("f3"))

            val d1 = async { coordinator.requestApproval(req1) }
            val d2 = async { coordinator.requestApproval(req2) }
            runCurrent()

            val active1 = assertNotNull(vm.uiState.value.activePrompt)
            assertEquals("t-1", active1.transferId)
            assertEquals("Pixel-8", active1.senderAlias)
            assertEquals(DeviceType.MOBILE, active1.senderDeviceType)
            assertEquals(2, active1.files.size)
            assertEquals(3072L, active1.totalBytes)
            assertEquals(2, vm.uiState.value.pendingCount)

            // Avanca 15 segundos no relogio e valida regressiva de timeout
            clock.nowMs += 15_000L
            vm.refreshPromptState()
            assertEquals(45L, vm.uiState.value.activePrompt?.remainingSeconds)

            // Aceitar t-1 avanca para t-2 e dispara callback
            assertTrue(vm.acceptActiveInternal())
            runCurrent()
            val decision1 = assertIs<TransferApprovalDecision.Approved>(d1.await())
            assertEquals(listOf("f1", "f2"), decision1.acceptedFileIds)
            assertEquals(1, acceptedNotifications)

            val active2 = assertNotNull(vm.uiState.value.activePrompt)
            assertEquals("t-2", active2.transferId)
            assertEquals("Linux-Note", active2.senderAlias)
            assertEquals(1, vm.uiState.value.pendingCount)

            // Recusar t-2 esvazia fila modal
            assertTrue(vm.declineActiveInternal())
            runCurrent()
            val decision2 = assertIs<TransferApprovalDecision.Rejected>(d2.await())
            assertEquals(RejectReasonCode.USER_DECLINED, decision2.reasonCode)
            assertNull(vm.uiState.value.activePrompt)
            assertEquals(0, vm.uiState.value.pendingCount)
        }
}


package br.edu.fileshare.ui.viewmodel

import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.network.server.ApprovalRequestContext
import br.edu.fileshare.network.server.InteractiveTransferApprovalCoordinator
import br.edu.fileshare.network.server.TransferApprovalDecision
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.RejectReasonCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

data class IncomingPromptUiModel(
    val transferId: String,
    val senderId: String,
    val senderAlias: String,
    val senderDeviceType: DeviceType,
    val totalBytes: Long,
    val files: List<FileItemDto>,
    val remainingSeconds: Long,
    val receivedAtEpochMillis: Long,
)

data class IncomingApprovalUiState(
    val activePrompt: IncomingPromptUiModel? = null,
    val pendingCount: Int = 0,
    val isSubmitting: Boolean = false,
)

/**
 * ViewModel responsavel pela fila modal de aprovacao explicita de recebimento (`TASK-DCLI-005`).
 */
class IncomingApprovalViewModel(
    private val coordinator: InteractiveTransferApprovalCoordinator,
    private val scope: CoroutineScope,
    private val clock: Clock = SystemClock,
    private val approvalTimeoutMs: Long = FsapConstants.T_APPROVAL_TIMEOUT_MS,
    private val countdownTickMs: Long = 1_000L,
    private val onAccepted: () -> Unit = {},
) {
    private val _uiState = MutableStateFlow(IncomingApprovalUiState())
    val uiState: StateFlow<IncomingApprovalUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            coordinator.pendingPrompts.collectLatest { prompts ->
                refreshPromptState(prompts)
                if (prompts.isEmpty() || countdownTickMs <= 0L) return@collectLatest
                while (isActive) {
                    delay(countdownTickMs)
                    refreshPromptState(coordinator.pendingPrompts.value)
                }
            }
        }
    }

    internal fun refreshPromptState(prompts: List<ApprovalRequestContext> = coordinator.pendingPrompts.value) {
        val first = prompts.firstOrNull()
        if (first == null) {
            _uiState.value = IncomingApprovalUiState(
                activePrompt = null,
                pendingCount = 0,
                isSubmitting = false,
            )
            return
        }

        val elapsed = (clock.epochMillis() - first.receivedAtEpochMillis).coerceAtLeast(0L)
        val remainingMs = max(0L, approvalTimeoutMs - elapsed)
        val remainingSeconds = (remainingMs + 999L) / 1000L

        _uiState.update { current ->
            current.copy(
                activePrompt = IncomingPromptUiModel(
                    transferId = first.transferId,
                    senderId = first.senderId,
                    senderAlias = first.request.senderAlias,
                    senderDeviceType = first.request.senderDeviceType,
                    totalBytes = first.request.totalBytes,
                    files = first.request.files,
                    remainingSeconds = remainingSeconds,
                    receivedAtEpochMillis = first.receivedAtEpochMillis,
                ),
                pendingCount = prompts.size,
            )
        }
    }

    fun acceptActive() {
        scope.launch { acceptActiveInternal() }
    }

    internal suspend fun acceptActiveInternal(): Boolean {
        val active = _uiState.value.activePrompt ?: return false
        _uiState.update { it.copy(isSubmitting = true) }
        return try {
            val accepted = coordinator.submitDecision(
                transferId = active.transferId,
                decision = TransferApprovalDecision.Approved(active.files.map { it.fileId }),
            )
            refreshPromptState()
            if (accepted) onAccepted()
            accepted
        } finally {
            _uiState.update { it.copy(isSubmitting = false) }
        }
    }

    fun declineActive() {
        scope.launch { declineActiveInternal() }
    }

    internal suspend fun declineActiveInternal(): Boolean {
        val active = _uiState.value.activePrompt ?: return false
        _uiState.update { it.copy(isSubmitting = true) }
        return try {
            val submitted = coordinator.submitDecision(
                transferId = active.transferId,
                decision = TransferApprovalDecision.Rejected(
                    reasonCode = RejectReasonCode.USER_DECLINED,
                    reasonMessage = "Usuario recusou a transferencia.",
                ),
            )
            refreshPromptState()
            submitted
        } finally {
            _uiState.update { it.copy(isSubmitting = false) }
        }
    }
}


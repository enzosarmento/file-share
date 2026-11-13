package br.edu.fileshare.ui.viewmodel

import br.edu.fileshare.network.transfer.TransferDirection
import br.edu.fileshare.network.transfer.TransferProgressSnapshot
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.ui.format.ByteFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Porta desacoplada para cancelamento de transferencias a partir da UI (`TASK-DCLI-006`).
 */
fun interface TransferCancelPort {
    suspend fun cancelTransfer(transferId: String, reason: String): Boolean
}

enum class TransferStatusTone {
    NEUTRAL,
    ACTIVE,
    SUCCESS,
    WARNING,
    ERROR,
}

data class TransferItemUiModel(
    val transferId: String,
    val peerAlias: String,
    val directionLabel: String,
    val state: TransferState,
    val stateLabel: String,
    val tone: TransferStatusTone,
    val progressFraction: Float,
    val percentLabel: String,
    val bytesSummary: String,
    val filesSummary: String,
    val currentFileName: String?,
    val speedLabel: String,
    val etaLabel: String,
    val errorSummary: String?,
    val canCancel: Boolean,
    val isCancelling: Boolean,
)

data class TransferProgressUiState(
    val items: List<TransferItemUiModel> = emptyList(),
) {
    val activeCount: Int
        get() = items.count { it.canCancel }
}

/**
 * ViewModel responsavel por formatar metricas e comandos da tela de progresso (`TASK-DCLI-006`).
 */
class TransferProgressViewModel(
    snapshotsFlow: StateFlow<List<TransferProgressSnapshot>>,
    private val cancelPort: TransferCancelPort,
    private val scope: CoroutineScope,
) {
    private val cancellingIds = MutableStateFlow<Set<String>>(emptySet())
    private val _uiState = MutableStateFlow(
        TransferProgressUiState(
            items = mapSnapshots(snapshotsFlow.value, emptySet()),
        ),
    )
    val uiState: StateFlow<TransferProgressUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            combine(snapshotsFlow, cancellingIds) { snapshots, pendingCancel ->
                TransferProgressUiState(items = mapSnapshots(snapshots, pendingCancel))
            }.collect { next ->
                _uiState.value = next
            }
        }
    }

    fun cancelTransfer(transferId: String) {
        scope.launch { cancelTransferInternal(transferId) }
    }

    internal suspend fun cancelTransferInternal(transferId: String): Boolean {
        if (transferId in cancellingIds.value) return false
        cancellingIds.update { it + transferId }
        return try {
            cancelPort.cancelTransfer(transferId, "USER_CANCELLED")
        } finally {
            cancellingIds.update { it - transferId }
        }
    }

    companion object {
        fun mapSnapshots(
            snapshots: List<TransferProgressSnapshot>,
            pendingCancelIds: Set<String> = emptySet(),
        ): List<TransferItemUiModel> = snapshots
            .sortedByDescending { it.updatedAtEpochMillis }
            .map { snap -> toUiModel(snap, isCancelling = snap.transferId in pendingCancelIds) }

        fun toUiModel(
            snapshot: TransferProgressSnapshot,
            isCancelling: Boolean = false,
        ): TransferItemUiModel {
            val isActive = isStateCancellable(snapshot.state)
            val directionLabel = when (snapshot.direction) {
                TransferDirection.SENDING -> "Enviando"
                TransferDirection.RECEIVING -> "Recebendo"
            }
            val (stateLabel, tone) = describeState(snapshot.state)
            val errorText = when {
                snapshot.errorCode != null && !snapshot.errorMessage.isNullOrBlank() ->
                    "[${snapshot.errorCode.code}] ${snapshot.errorMessage}"
                snapshot.errorCode != null ->
                    "[${snapshot.errorCode.code}] ${snapshot.errorCode.defaultMessage}"
                !snapshot.errorMessage.isNullOrBlank() ->
                    snapshot.errorMessage
                else -> null
            }

            return TransferItemUiModel(
                transferId = snapshot.transferId,
                peerAlias = snapshot.peerAlias,
                directionLabel = directionLabel,
                state = snapshot.state,
                stateLabel = stateLabel,
                tone = tone,
                progressFraction = snapshot.progressFraction,
                percentLabel = "${snapshot.percentComplete}%",
                bytesSummary = "${ByteFormatter.formatBytes(snapshot.bytesTransferred)} / ${ByteFormatter.formatBytes(snapshot.totalBytes)}",
                filesSummary = "${snapshot.filesCompleted}/${snapshot.totalFiles} arquivo(s)",
                currentFileName = snapshot.currentFileName,
                speedLabel = if (isActive) ByteFormatter.formatSpeed(snapshot.speedBytesPerSecond) else "--",
                etaLabel = if (isActive) "ETA: ${ByteFormatter.formatEta(snapshot.estimatedRemainingSeconds)}" else "--",
                errorSummary = errorText,
                canCancel = isActive,
                isCancelling = isCancelling,
            )
        }

        fun isStateCancellable(state: TransferState): Boolean = when (state) {
            TransferState.CREATED,
            TransferState.PENDING_APPROVAL,
            TransferState.ACCEPTED,
            TransferState.TRANSFERRING,
            TransferState.VERIFYING,
            -> true

            TransferState.COMPLETED,
            TransferState.REJECTED,
            TransferState.CANCELLED,
            TransferState.FAILED,
            -> false
        }

        fun describeState(state: TransferState): Pair<String, TransferStatusTone> = when (state) {
            TransferState.CREATED -> "Iniciando" to TransferStatusTone.NEUTRAL
            TransferState.PENDING_APPROVAL -> "Aguardando aprovacao" to TransferStatusTone.WARNING
            TransferState.ACCEPTED -> "Aceita" to TransferStatusTone.ACTIVE
            TransferState.TRANSFERRING -> "Transferindo" to TransferStatusTone.ACTIVE
            TransferState.VERIFYING -> "Verificando SHA-256" to TransferStatusTone.ACTIVE
            TransferState.COMPLETED -> "Concluida" to TransferStatusTone.SUCCESS
            TransferState.REJECTED -> "Recusada" to TransferStatusTone.WARNING
            TransferState.CANCELLED -> "Cancelada" to TransferStatusTone.NEUTRAL
            TransferState.FAILED -> "Falhou" to TransferStatusTone.ERROR
        }
    }
}


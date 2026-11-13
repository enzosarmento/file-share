package br.edu.fileshare.network.server

import br.edu.fileshare.protocol.model.RejectReasonCode
import br.edu.fileshare.protocol.model.TransferRequestPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ApprovalRequestContext(
    val transferId: String,
    val senderId: String,
    val request: TransferRequestPayload,
    val receivedAtEpochMillis: Long,
)

sealed interface TransferApprovalDecision {
    data class Approved(
        val acceptedFileIds: List<String>? = null,
    ) : TransferApprovalDecision

    data class Rejected(
        val reasonCode: RejectReasonCode = RejectReasonCode.USER_DECLINED,
        val reasonMessage: String? = null,
    ) : TransferApprovalDecision
}

/**
 * Porta desacoplada de aprovacao explicita de transferencias recebidas (`RF-08`, `TASK-DSRV-003`).
 * Permite implementacao headless/auto-accept na CLI, fila de dialogo reativa na GUI Compose ou fakes em testes.
 */
fun interface TransferApprovalHandler {
    suspend fun requestApproval(context: ApprovalRequestContext): TransferApprovalDecision
}

/**
 * Politica de auto-aceitacao imediata para `--auto-accept-test` (CLI headless) e testes de integracao.
 */
object AutoAcceptTransferApprovalHandler : TransferApprovalHandler {
    override suspend fun requestApproval(context: ApprovalRequestContext): TransferApprovalDecision =
        TransferApprovalDecision.Approved(
            acceptedFileIds = context.request.files.map { it.fileId },
        )
}

/**
 * Politica de recusa automatica configuravel para testes e modos restritivos.
 */
class RejectAllTransferApprovalHandler(
    private val reasonCode: RejectReasonCode = RejectReasonCode.USER_DECLINED,
    private val reasonMessage: String? = null,
) : TransferApprovalHandler {
    override suspend fun requestApproval(context: ApprovalRequestContext): TransferApprovalDecision =
        TransferApprovalDecision.Rejected(
            reasonCode = reasonCode,
            reasonMessage = reasonMessage,
        )
}

/**
 * Fila reativa de prompts pendentes que conecta rotas HTTP do servidor a qualquer interface
 * (Compose UI Desktop/Android ou console interativo) sem acoplamento de pacotes visuais na rede.
 */
class InteractiveTransferApprovalCoordinator : TransferApprovalHandler {
    private val mutex = Mutex()
    private val pendingDeferreds = LinkedHashMap<String, CompletableDeferred<TransferApprovalDecision>>()
    private val _pendingPrompts = MutableStateFlow<List<ApprovalRequestContext>>(emptyList())

    val pendingPrompts: StateFlow<List<ApprovalRequestContext>> = _pendingPrompts.asStateFlow()

    override suspend fun requestApproval(context: ApprovalRequestContext): TransferApprovalDecision {
        val deferred = CompletableDeferred<TransferApprovalDecision>()
        mutex.withLock {
            pendingDeferreds[context.transferId] = deferred
            _pendingPrompts.value = _pendingPrompts.value + context
        }
        return try {
            deferred.await()
        } finally {
            removePrompt(context.transferId)
        }
    }

    suspend fun submitDecision(
        transferId: String,
        decision: TransferApprovalDecision,
    ): Boolean {
        val deferred = mutex.withLock { pendingDeferreds[transferId] } ?: return false
        return deferred.complete(decision)
    }

    suspend fun cancelPrompt(transferId: String) {
        val deferred = removePrompt(transferId)
        deferred?.cancel()
    }

    private suspend fun removePrompt(
        transferId: String,
    ): CompletableDeferred<TransferApprovalDecision>? = mutex.withLock {
        val removed = pendingDeferreds.remove(transferId)
        if (removed != null) {
            _pendingPrompts.value = _pendingPrompts.value.filterNot { it.transferId == transferId }
        }
        removed
    }
}


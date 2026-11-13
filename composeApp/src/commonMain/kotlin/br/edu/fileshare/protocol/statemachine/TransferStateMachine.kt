package br.edu.fileshare.protocol.statemachine

import br.edu.fileshare.core.logging.FsapLogger

enum class TransferState(val isTerminal: Boolean) {
    CREATED(isTerminal = false),
    PENDING_APPROVAL(isTerminal = false),
    ACCEPTED(isTerminal = false),
    REJECTED(isTerminal = true),
    TRANSFERRING(isTerminal = false),
    VERIFYING(isTerminal = false),
    COMPLETED(isTerminal = true),
    CANCELLED(isTerminal = true),
    FAILED(isTerminal = true),
}

enum class TransferEvent {
    TRANSFER_REQUEST_SENT_OR_RECEIVED,
    PREPARATION_ERROR,
    TRANSFER_ACCEPTED,
    TRANSFER_REJECTED,
    APPROVAL_TIMEOUT,
    STREAM_STARTED,
    TOKEN_IDLE_TIMEOUT,
    CHUNK_TRANSFERRED,
    ALL_BYTES_RECEIVED,
    STREAM_ERROR,
    STREAM_TIMEOUT,
    FILE_VERIFIED_MORE_REMAIN,
    ALL_FILES_VERIFIED_AND_COMPLETED,
    HASH_MISMATCH,
    STORAGE_COMMIT_ERROR,
    CANCEL_TRANSFER,
    CONNECTION_LOST,
}

/**
 * Maquina de Estados 3: Sessao de transferencia de lote FSAP/1.0 (`docs/state-machine.md`, Secao 4).
 */
class TransferStateMachine(
    val transferId: String,
    logger: FsapLogger? = null,
    onExit: suspend (TransferState) -> Unit = {},
    onEnter: suspend (TransferState) -> Unit = {},
) : FiniteStateMachine<TransferState, TransferEvent>(
    entityId = transferId,
    componentName = "TransferFSM",
    initialState = TransferState.CREATED,
    transitions = TRANSITIONS,
    logger = logger,
    transferIdForLogs = transferId,
    onExit = onExit,
    onEnter = onEnter,
) {
    companion object {
        val TRANSITIONS: Map<Pair<TransferState, TransferEvent>, TransferState> = mapOf(
            // CREATED
            (TransferState.CREATED to TransferEvent.TRANSFER_REQUEST_SENT_OR_RECEIVED) to TransferState.PENDING_APPROVAL,
            (TransferState.CREATED to TransferEvent.CANCEL_TRANSFER) to TransferState.CANCELLED,
            (TransferState.CREATED to TransferEvent.PREPARATION_ERROR) to TransferState.FAILED,

            // PENDING_APPROVAL
            (TransferState.PENDING_APPROVAL to TransferEvent.TRANSFER_ACCEPTED) to TransferState.ACCEPTED,
            (TransferState.PENDING_APPROVAL to TransferEvent.TRANSFER_REJECTED) to TransferState.REJECTED,
            (TransferState.PENDING_APPROVAL to TransferEvent.CANCEL_TRANSFER) to TransferState.CANCELLED,
            (TransferState.PENDING_APPROVAL to TransferEvent.APPROVAL_TIMEOUT) to TransferState.FAILED,
            (TransferState.PENDING_APPROVAL to TransferEvent.CONNECTION_LOST) to TransferState.FAILED,

            // ACCEPTED
            (TransferState.ACCEPTED to TransferEvent.STREAM_STARTED) to TransferState.TRANSFERRING,
            (TransferState.ACCEPTED to TransferEvent.CANCEL_TRANSFER) to TransferState.CANCELLED,
            (TransferState.ACCEPTED to TransferEvent.TOKEN_IDLE_TIMEOUT) to TransferState.FAILED,
            (TransferState.ACCEPTED to TransferEvent.CONNECTION_LOST) to TransferState.FAILED,

            // TRANSFERRING
            (TransferState.TRANSFERRING to TransferEvent.CHUNK_TRANSFERRED) to TransferState.TRANSFERRING,
            (TransferState.TRANSFERRING to TransferEvent.ALL_BYTES_RECEIVED) to TransferState.VERIFYING,
            (TransferState.TRANSFERRING to TransferEvent.CANCEL_TRANSFER) to TransferState.CANCELLED,
            (TransferState.TRANSFERRING to TransferEvent.STREAM_ERROR) to TransferState.FAILED,
            (TransferState.TRANSFERRING to TransferEvent.STREAM_TIMEOUT) to TransferState.FAILED,
            (TransferState.TRANSFERRING to TransferEvent.CONNECTION_LOST) to TransferState.FAILED,

            // VERIFYING
            (TransferState.VERIFYING to TransferEvent.FILE_VERIFIED_MORE_REMAIN) to TransferState.TRANSFERRING,
            (TransferState.VERIFYING to TransferEvent.ALL_FILES_VERIFIED_AND_COMPLETED) to TransferState.COMPLETED,
            (TransferState.VERIFYING to TransferEvent.CANCEL_TRANSFER) to TransferState.CANCELLED,
            (TransferState.VERIFYING to TransferEvent.HASH_MISMATCH) to TransferState.FAILED,
            (TransferState.VERIFYING to TransferEvent.STORAGE_COMMIT_ERROR) to TransferState.FAILED,
        )
    }
}


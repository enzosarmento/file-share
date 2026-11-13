package br.edu.fileshare.network.transfer

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.client.PrepareTransferResult
import br.edu.fileshare.network.client.TransferBatchUploadResult
import br.edu.fileshare.network.client.transitionIfPossible
import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.network.server.ServerSessionSnapshot
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.CancelledBy
import br.edu.fileshare.protocol.statemachine.TransferEvent
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.protocol.statemachine.TransferStateMachine
import br.edu.fileshare.storage.FileSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SendFilesOutcome {
    data class Completed(val transferId: String, val totalBytes: Long, val filesCount: Int) : SendFilesOutcome
    data class Rejected(val transferId: String, val reason: String?) : SendFilesOutcome
    data class Cancelled(val transferId: String, val reason: String?) : SendFilesOutcome
    data class Failed(
        val transferId: String,
        val errorCode: FsapErrorCode,
        val message: String,
    ) : SendFilesOutcome
}

/**
 * Orquestrador unificado de envios, recebimentos e rastreamento de progresso (`TASK-XFER-004`).
 * Isola cada envio em sua propria coroutine filha sob `SupervisorJob` para suportar
 * multiplas transferencias simultaneas sem interferencia cruzada (`RF-15`).
 */
class TransferCoordinator(
    private val httpClient: FsapHttpClient,
    parentScope: CoroutineScope,
    private val serverSessionManager: ServerTransferSessionManager? = null,
    private val logger: FsapLogger? = null,
    clock: Clock = SystemClock,
    private val uuidGenerator: UuidGenerator = PlatformUuidGenerator,
    val progressTracker: TransferProgressTracker = TransferProgressTracker(clock = clock),
) {
    private data class ActiveOutgoingContext(
        val transferId: String,
        val endpoint: FsapPeerEndpoint,
        val stateMachine: TransferStateMachine,
        var job: Job? = null,
        var transferToken: String? = null,
        var cancelRequested: Boolean = false,
        var cancelReason: String? = null,
    )

    private val coordinatorScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
    )
    private val outgoingMutex = Mutex()
    private val activeOutgoing = LinkedHashMap<String, ActiveOutgoingContext>()
    private val trackedIncomingIds = mutableSetOf<String>()

    val transfers: StateFlow<List<TransferProgressSnapshot>> = progressTracker.transfers

    init {
        serverSessionManager?.let { manager ->
            coordinatorScope.launch {
                manager.sessions.collect { snapshots ->
                    syncIncomingServerSessions(snapshots)
                }
            }
        }
    }

    fun launchSendFiles(
        peer: DiscoveredPeer,
        sources: List<FileSource>,
        transferId: String = uuidGenerator.nextUuidV4(),
        onFinished: (SendFilesOutcome) -> Unit = {},
    ): Job = coordinatorScope.launch {
        val endpoint = FsapPeerEndpoint(host = peer.ipAddress, port = peer.port)
        val outcome = sendFiles(
            endpoint = endpoint,
            peerAlias = peer.alias,
            sources = sources,
            transferId = transferId,
        )
        onFinished(outcome)
    }

    suspend fun sendFiles(
        endpoint: FsapPeerEndpoint,
        peerAlias: String,
        sources: List<FileSource>,
        transferId: String = uuidGenerator.nextUuidV4(),
    ): SendFilesOutcome {
        val job = coordinatorScope.coroutineContext[Job]
        val stateMachine = TransferStateMachine(transferId = transferId, logger = logger)
        val totalBytes = sources.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        val context = ActiveOutgoingContext(
            transferId = transferId,
            endpoint = endpoint,
            stateMachine = stateMachine,
            job = kotlinx.coroutines.currentCoroutineContext()[Job] ?: job,
        )

        progressTracker.registerTransfer(
            transferId = transferId,
            peerAlias = peerAlias,
            direction = TransferDirection.SENDING,
            totalBytes = totalBytes,
            totalFiles = sources.size,
            initialState = TransferState.CREATED,
        )

        outgoingMutex.withLock {
            activeOutgoing[transferId] = context
        }

        try {
            val preparedFiles = try {
                httpClient.indexSourcesSequential(sources)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ex: Throwable) {
                stateMachine.transitionIfPossible(TransferEvent.PREPARATION_ERROR)
                progressTracker.updateState(
                    transferId = transferId,
                    state = TransferState.FAILED,
                    errorCode = FsapErrorCode.FILE_NOT_FOUND,
                    errorMessage = "Falha ao ler arquivo de origem: ${ex.message}",
                )
                return SendFilesOutcome.Failed(transferId, FsapErrorCode.FILE_NOT_FOUND, ex.message ?: "Erro de leitura.")
            }

            if (context.cancelRequested) {
                stateMachine.transitionIfPossible(TransferEvent.CANCEL_TRANSFER)
                progressTracker.updateState(
                    transferId = transferId,
                    state = TransferState.CANCELLED,
                    errorCode = FsapErrorCode.TRANSFER_CANCELLED,
                    errorMessage = context.cancelReason ?: "Cancelada pelo remetente.",
                )
                return SendFilesOutcome.Cancelled(transferId, context.cancelReason)
            }

            progressTracker.updateState(
                transferId = transferId,
                state = TransferState.PENDING_APPROVAL,
                currentFileId = preparedFiles.firstOrNull()?.descriptor?.fileId,
                currentFileName = preparedFiles.firstOrNull()?.descriptor?.fileName,
            )

            val prepareRes = httpClient.prepareTransfer(
                endpoint = endpoint,
                files = preparedFiles.map { it.descriptor },
                transferId = transferId,
                stateMachine = stateMachine,
            )

            val session = when (prepareRes) {
                is PrepareTransferResult.Accepted -> {
                    outgoingMutex.withLock {
                        context.transferToken = prepareRes.session.transferToken
                    }
                    progressTracker.updateState(transferId = transferId, state = TransferState.ACCEPTED)
                    prepareRes.session
                }

                is PrepareTransferResult.Rejected -> {
                    val reason = prepareRes.reject.reasonMessage ?: prepareRes.reject.reasonCode.name
                    progressTracker.updateState(
                        transferId = transferId,
                        state = TransferState.REJECTED,
                        errorCode = FsapErrorCode.TRANSFER_REJECTED,
                        errorMessage = reason,
                    )
                    return SendFilesOutcome.Rejected(transferId, reason)
                }

                is PrepareTransferResult.Failed -> {
                    val currentState = stateMachine.currentState()
                    progressTracker.updateState(
                        transferId = transferId,
                        state = currentState,
                        errorCode = prepareRes.errorCode,
                        errorMessage = prepareRes.message,
                    )
                    if (currentState == TransferState.CANCELLED ||
                        prepareRes.errorCode == FsapErrorCode.TRANSFER_CANCELLED
                    ) {
                        return SendFilesOutcome.Cancelled(transferId, prepareRes.message)
                    }
                    return SendFilesOutcome.Failed(transferId, prepareRes.errorCode, prepareRes.message)
                }
            }

            val fileNamesById = preparedFiles.associate { it.descriptor.fileId to it.descriptor.fileName }
            val completedFileIds = linkedSetOf<String>()
            progressTracker.updateState(transferId = transferId, state = TransferState.TRANSFERRING)

            val uploadOutcome = httpClient.uploadAcceptedBatch(
                session = session,
                sourcesByFileId = preparedFiles.associate { it.descriptor.fileId to it.source },
                progressListener = { fileId, fileSent, fileTotal, batchSent, _ ->
                    if (fileSent >= fileTotal) {
                        completedFileIds += fileId
                    }
                    progressTracker.reportProgress(
                        transferId = transferId,
                        bytesTransferred = batchSent,
                        currentFileId = fileId,
                        currentFileName = fileNamesById[fileId],
                        filesCompleted = completedFileIds.size,
                    )
                },
            )

            return when (uploadOutcome) {
                is TransferBatchUploadResult.Completed -> {
                    progressTracker.reportProgress(
                        transferId = transferId,
                        bytesTransferred = totalBytes,
                        filesCompleted = uploadOutcome.confirmation.completedFiles.size,
                        forceEmit = true,
                    )
                    progressTracker.updateState(
                        transferId = transferId,
                        state = TransferState.COMPLETED,
                        filesCompleted = uploadOutcome.confirmation.completedFiles.size,
                    )
                    SendFilesOutcome.Completed(
                        transferId = transferId,
                        totalBytes = totalBytes,
                        filesCount = uploadOutcome.confirmation.completedFiles.size,
                    )
                }

                is TransferBatchUploadResult.Cancelled -> {
                    progressTracker.updateState(
                        transferId = transferId,
                        state = TransferState.CANCELLED,
                        errorCode = FsapErrorCode.TRANSFER_CANCELLED,
                        errorMessage = uploadOutcome.reason,
                    )
                    SendFilesOutcome.Cancelled(transferId, uploadOutcome.reason)
                }

                is TransferBatchUploadResult.Failed -> {
                    if (context.cancelRequested) {
                        stateMachine.transitionIfPossible(TransferEvent.CANCEL_TRANSFER)
                        progressTracker.updateState(
                            transferId = transferId,
                            state = TransferState.CANCELLED,
                            errorCode = FsapErrorCode.TRANSFER_CANCELLED,
                            errorMessage = context.cancelReason ?: uploadOutcome.message,
                        )
                        SendFilesOutcome.Cancelled(transferId, context.cancelReason)
                    } else {
                        progressTracker.updateState(
                            transferId = transferId,
                            state = stateMachine.currentState(),
                            errorCode = uploadOutcome.errorCode,
                            errorMessage = uploadOutcome.message,
                        )
                        SendFilesOutcome.Failed(transferId, uploadOutcome.errorCode, uploadOutcome.message)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                stateMachine.transitionIfPossible(TransferEvent.CANCEL_TRANSFER)
                val reason = context.cancelReason ?: cancelled.message ?: "Transferencia cancelada."
                progressTracker.updateState(
                    transferId = transferId,
                    state = TransferState.CANCELLED,
                    errorCode = FsapErrorCode.TRANSFER_CANCELLED,
                    errorMessage = reason,
                )
            }
            return SendFilesOutcome.Cancelled(transferId, context.cancelReason)
        } finally {
            withContext(NonCancellable) {
                outgoingMutex.withLock {
                    activeOutgoing.remove(transferId)
                }
            }
        }
    }

    suspend fun cancelTransfer(
        transferId: String,
        reason: String = "USER_REQUESTED",
    ): Boolean {
        val outgoing = outgoingMutex.withLock { activeOutgoing[transferId] }
        if (outgoing != null) {
            outgoing.cancelRequested = true
            outgoing.cancelReason = reason
            // Notifica o servidor via POST /cancel antes de romper o socket local, evitando falso CONNECTION_LOST.
            runCatching {
                withTimeoutOrNull(800L) {
                    httpClient.cancelTransferOnPeer(
                        endpoint = outgoing.endpoint,
                        transferId = transferId,
                        cancelledBy = CancelledBy.SENDER,
                        transferToken = outgoing.transferToken,
                        reason = reason,
                    )
                }
            }
            outgoing.job?.cancel(CancellationException("Cancelada localmente: $reason"))
            logger?.info(
                component = "TransferCoordinator",
                message = "Cancelamento solicitado pelo remetente para sessao '$transferId' ($reason).",
                context = LogContext(transferId = transferId),
            )
            return true
        }

        val serverManager = serverSessionManager ?: return false
        val snapshot = serverManager.findSnapshot(transferId) ?: return false
        if (snapshot.state.isTerminal) return false
        serverManager.cancelTransfer(transferId, CancelledBy.RECEIVER, reason)
        return true
    }

    private suspend fun syncIncomingServerSessions(snapshots: List<ServerSessionSnapshot>) {
        for (snap in snapshots) {
            val fileNamesById = snap.files.associate { it.fileId to it.fileName }
            val currentFileName = snap.currentFileId?.let { fileNamesById[it] }
            if (snap.transferId !in trackedIncomingIds && progressTracker.getSnapshot(snap.transferId) == null) {
                trackedIncomingIds += snap.transferId
                progressTracker.registerTransfer(
                    transferId = snap.transferId,
                    peerAlias = snap.senderAlias,
                    direction = TransferDirection.RECEIVING,
                    totalBytes = snap.totalBytes,
                    totalFiles = snap.files.size,
                    initialState = snap.state,
                )
            }

            progressTracker.reportProgress(
                transferId = snap.transferId,
                bytesTransferred = snap.totalBytesTransferred,
                currentFileId = snap.currentFileId,
                currentFileName = currentFileName,
                filesCompleted = snap.verifiedFileIds.size,
            )
            val prevState = progressTracker.getSnapshot(snap.transferId)?.state
            if (prevState != snap.state) {
                progressTracker.updateState(
                    transferId = snap.transferId,
                    state = snap.state,
                    filesCompleted = snap.verifiedFileIds.size,
                    currentFileId = snap.currentFileId,
                    currentFileName = currentFileName,
                    errorCode = snap.errorCode,
                    errorMessage = snap.errorMessage,
                )
            }
        }
    }

    suspend fun close() {
        val activeJobs = outgoingMutex.withLock { activeOutgoing.values.toList() }
        for (ctx in activeJobs) {
            ctx.cancelRequested = true
            ctx.cancelReason = "APP_SHUTDOWN"
            runCatching {
                withTimeoutOrNull(800L) {
                    httpClient.cancelTransferOnPeer(
                        endpoint = ctx.endpoint,
                        transferId = ctx.transferId,
                        cancelledBy = CancelledBy.SENDER,
                        transferToken = ctx.transferToken,
                        reason = "APP_SHUTDOWN",
                    )
                }
            }
            ctx.job?.cancel()
        }
        coordinatorScope.cancel()
    }
}

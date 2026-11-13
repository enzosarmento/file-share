package br.edu.fileshare.network.server

import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.protocol.model.CancelledBy
import br.edu.fileshare.protocol.model.CompletedFileSummary
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.RejectReasonCode
import br.edu.fileshare.protocol.model.TransferCompletePayload
import br.edu.fileshare.protocol.model.TransferRequestPayload
import br.edu.fileshare.protocol.model.TransferStatusPayload
import br.edu.fileshare.protocol.statemachine.TransferEvent
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.protocol.statemachine.TransferStateMachine
import br.edu.fileshare.protocol.validation.FsapMessageValidator
import br.edu.fileshare.storage.FileSink
import br.edu.fileshare.storage.StoredFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface CreateSessionResult {
    data class Created(val session: ServerTransferSession) : CreateSessionResult
    data object CapacityExceeded : CreateSessionResult
    data object DuplicateTransferId : CreateSessionResult
}

data class VerifiedFileRecord(
    val fileId: String,
    val sha256: String,
    val storedFile: StoredFileDescriptor,
)

data class ServerSessionSnapshot(
    val transferId: String,
    val senderId: String,
    val senderAlias: String,
    val senderDeviceType: DeviceType,
    val state: TransferState,
    val totalBytes: Long,
    val totalBytesTransferred: Long,
    val files: List<FileItemDto>,
    val acceptedFileIds: Set<String>,
    val verifiedFileIds: Set<String>,
    val currentFileId: String?,
    val createdAtEpochMillis: Long,
    val errorCode: FsapErrorCode? = null,
    val errorMessage: String? = null,
)

data class ActiveFileStreamContext(
    val transferId: String,
    val fileItem: FileItemDto,
)

/**
 * Representa o contexto isolado de uma sessao de recebimento no servidor FSAP/1.0 (`TASK-DSRV-002`).
 */
class ServerTransferSession internal constructor(
    val transferId: String,
    val senderId: String,
    val senderAlias: String,
    val senderDeviceType: DeviceType,
    val totalBytes: Long,
    val files: List<FileItemDto>,
    val fsm: TransferStateMachine,
    val createdAtEpochMillis: Long,
    parentScope: CoroutineScope,
) {
    internal val mutex = Mutex()
    internal val sessionScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
    )
    internal val filesById: Map<String, FileItemDto> = files.associateBy { it.fileId }
    internal var approvalTimerJob: Job? = null
    internal var idleTimerJob: Job? = null
    internal var transferToken: String? = null
    internal var acceptedFileIds: Set<String> = emptySet()
    internal var currentFileId: String? = null
    internal val bytesReceivedByFile = LinkedHashMap<String, Long>()
    internal val verifiedFiles = LinkedHashMap<String, VerifiedFileRecord>()
    internal val activeUploadJobs = mutableSetOf<Job>()
    internal var lastErrorCode: FsapErrorCode? = null
    internal var lastErrorMessage: String? = null

    internal fun toSnapshotLocked(): ServerSessionSnapshot = ServerSessionSnapshot(
        transferId = transferId,
        senderId = senderId,
        senderAlias = senderAlias,
        senderDeviceType = senderDeviceType,
        state = fsm.currentState(),
        totalBytes = totalBytes,
        totalBytesTransferred = bytesReceivedByFile.values.sum(),
        files = files,
        acceptedFileIds = acceptedFileIds.toSet(),
        verifiedFileIds = verifiedFiles.keys.toSet(),
        currentFileId = currentFileId,
        createdAtEpochMillis = createdAtEpochMillis,
        errorCode = lastErrorCode,
        errorMessage = lastErrorMessage,
    )
}

/**
 * Gerenciador concorrente de sessoes de recebimento no servidor (`TASK-DSRV-002`).
 * - Isola cada sessao por `transferId` com seu proprio `Mutex`, `SupervisorJob` e [TransferStateMachine].
 * - Aplica limites de concorrencia (`maxConcurrentSessions`), timeout de aprovacao (`60 s`) e
 *   expiracao por inatividade de token (`30 s`).
 */
class ServerTransferSessionManager(
    private val tokenManager: TransferTokenManager,
    private val fileSink: FileSink,
    private val scope: CoroutineScope,
    private val logger: FsapLogger? = null,
    private val clock: Clock = SystemClock,
    private val maxConcurrentSessions: Int = FsapConstants.MAX_CONCURRENT_RECEIVE_SESSIONS,
    private val approvalTimeoutMs: Long = FsapConstants.T_APPROVAL_TIMEOUT_MS,
    private val tokenIdleTtlMs: Long = FsapConstants.T_TOKEN_IDLE_TTL_MS,
) {
    private val registryMutex = Mutex()
    private val sessionsMap = LinkedHashMap<String, ServerTransferSession>()
    private val _sessions = MutableStateFlow<List<ServerSessionSnapshot>>(emptyList())

    val sessions: StateFlow<List<ServerSessionSnapshot>> = _sessions.asStateFlow()

    suspend fun createIncomingSession(
        senderId: String,
        request: TransferRequestPayload,
    ): CreateSessionResult {
        val session = registryMutex.withLock {
            if (request.transferId in sessionsMap) {
                return CreateSessionResult.DuplicateTransferId
            }

            val activeCount = sessionsMap.values.count { !it.fsm.currentState().isTerminal }
            if (activeCount >= maxConcurrentSessions) {
                logger?.warn(
                    component = "ServerTransferSessionManager",
                    message = "Capacidade maxima ($maxConcurrentSessions) de sessoes simultaneas atingida; rejeitando '${request.transferId}'.",
                    context = LogContext(transferId = request.transferId, peerId = senderId),
                )
                return CreateSessionResult.CapacityExceeded
            }

            val fsm = TransferStateMachine(
                transferId = request.transferId,
                logger = logger,
            )
            val created = ServerTransferSession(
                transferId = request.transferId,
                senderId = senderId,
                senderAlias = request.senderAlias,
                senderDeviceType = request.senderDeviceType,
                totalBytes = request.totalBytes,
                files = request.files,
                fsm = fsm,
                createdAtEpochMillis = clock.epochMillis(),
                parentScope = scope,
            )
            sessionsMap[request.transferId] = created
            created
        }

        session.mutex.withLock {
            session.fsm.transition(TransferEvent.TRANSFER_REQUEST_SENT_OR_RECEIVED)
            armApprovalTimeoutLocked(session)
        }
        publishSnapshots()
        return CreateSessionResult.Created(session)
    }

    suspend fun acceptSession(
        transferId: String,
        selectedFileIds: List<String>? = null,
    ): IssuedTransferToken {
        val session = requireSession(transferId)
        val issued = session.mutex.withLock {
            session.approvalTimerJob?.cancel()
            session.approvalTimerJob = null

            val chosenIds = (selectedFileIds ?: session.files.map { it.fileId }).toSet()
            if (chosenIds.isEmpty() || !session.filesById.keys.containsAll(chosenIds)) {
                throw FsapProtocolException(
                    errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                    message = "Subconjunto de fileIds selecionados e vazio ou desconhecido.",
                    transferId = transferId,
                )
            }

            val token = tokenManager.issueToken(
                transferId = transferId,
                allowedFileIds = chosenIds,
                ttlMillis = tokenIdleTtlMs,
            )
            session.fsm.transition(TransferEvent.TRANSFER_ACCEPTED)
            session.acceptedFileIds = chosenIds
            session.transferToken = token.token
            armIdleTimeoutLocked(session)
            token
        }

        publishSnapshots()
        return issued
    }

    suspend fun rejectSession(
        transferId: String,
        reasonCode: RejectReasonCode = RejectReasonCode.USER_DECLINED,
        reasonMessage: String? = null,
    ) {
        val session = requireSession(transferId)
        session.mutex.withLock {
            session.approvalTimerJob?.cancel()
            session.approvalTimerJob = null
            session.fsm.transition(TransferEvent.TRANSFER_REJECTED)
            session.lastErrorCode = FsapErrorCode.TRANSFER_REJECTED
            session.lastErrorMessage = reasonMessage ?: "Recusado ($reasonCode)."
            tokenManager.revoke(transferId)
        }
        publishSnapshots()
    }

    suspend fun failPreparation(
        transferId: String,
        errorCode: FsapErrorCode,
        reason: String,
    ) {
        val session = findSession(transferId) ?: return
        session.mutex.withLock {
            session.approvalTimerJob?.cancel()
            session.approvalTimerJob = null
            if (session.fsm.canTransition(TransferEvent.PREPARATION_ERROR)) {
                session.fsm.transition(TransferEvent.PREPARATION_ERROR)
            } else if (session.fsm.canTransition(TransferEvent.CONNECTION_LOST)) {
                session.fsm.transition(TransferEvent.CONNECTION_LOST)
            }
            session.lastErrorCode = errorCode
            session.lastErrorMessage = reason
            tokenManager.revoke(transferId)
        }
        publishSnapshots()
    }

    suspend fun beginFileStream(
        transferId: String,
        fileId: String,
        presentedToken: String?,
        contentLength: Long?,
        headerSha256: String?,
        uploadJob: Job? = null,
    ): ActiveFileStreamContext {
        val session = requireSession(transferId)

        when (val tokenCheck = tokenManager.validateAndRefresh(transferId, fileId, presentedToken)) {
            is TokenValidationResult.Authorized -> Unit
            is TokenValidationResult.Denied -> throw FsapProtocolException(
                errorCode = tokenCheck.errorCode,
                message = tokenCheck.reason,
                transferId = transferId,
                fileId = fileId,
            )
        }

        val fileItem = session.mutex.withLock {
            val state = session.fsm.currentState()
            if (state != TransferState.ACCEPTED && state != TransferState.TRANSFERRING) {
                throw FsapProtocolException(
                    errorCode = FsapErrorCode.INVALID_STATE_TRANSITION,
                    message = "Sessao '$transferId' em estado '${state.name}' nao aceita upload binario.",
                    transferId = transferId,
                    fileId = fileId,
                )
            }

            if (fileId !in session.acceptedFileIds) {
                throw FsapProtocolException(
                    errorCode = FsapErrorCode.FILE_NOT_FOUND,
                    message = "Arquivo '$fileId' nao pertence ao manifesto aprovado.",
                    transferId = transferId,
                    fileId = fileId,
                )
            }

            if (fileId in session.verifiedFiles) {
                throw FsapProtocolException(
                    errorCode = FsapErrorCode.INVALID_STATE_TRANSITION,
                    message = "Arquivo '$fileId' ja foi transferido e verificado nesta sessao.",
                    transferId = transferId,
                    fileId = fileId,
                )
            }

            val item = session.filesById[fileId]
                ?: throw FsapProtocolException(
                    errorCode = FsapErrorCode.FILE_NOT_FOUND,
                    message = "Metadados de '$fileId' nao encontrados.",
                    transferId = transferId,
                    fileId = fileId,
                )

            if (contentLength == null || contentLength != item.sizeBytes) {
                throw FsapProtocolException(
                    errorCode = FsapErrorCode.SIZE_MISMATCH,
                    message = "Content-Length ($contentLength) diverge de sizeBytes (${item.sizeBytes}).",
                    transferId = transferId,
                    fileId = fileId,
                )
            }

            if (headerSha256 == null || !FsapMessageValidator.isValidSha256Hex(headerSha256) ||
                !headerSha256.equals(item.sha256, ignoreCase = true)
            ) {
                throw FsapProtocolException(
                    errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                    message = "Cabecalho X-FSAP-File-SHA256 ausente ou divergente do manifesto.",
                    transferId = transferId,
                    fileId = fileId,
                )
            }

            session.idleTimerJob?.cancel()
            session.idleTimerJob = null

            if (state == TransferState.ACCEPTED) {
                session.fsm.transition(TransferEvent.STREAM_STARTED)
            } else {
                session.fsm.transition(TransferEvent.CHUNK_TRANSFERRED)
            }

            session.currentFileId = fileId
            session.bytesReceivedByFile[fileId] = 0L
            uploadJob?.let { session.activeUploadJobs += it }
            item
        }

        publishSnapshots()
        return ActiveFileStreamContext(transferId = transferId, fileItem = fileItem)
    }

    suspend fun finishUploadJobReference(transferId: String, uploadJob: Job?) {
        val session = findSession(transferId) ?: return
        if (uploadJob == null) return
        session.mutex.withLock {
            session.activeUploadJobs.remove(uploadJob)
        }
    }

    suspend fun updateFileProgress(
        transferId: String,
        fileId: String,
        bytesReceived: Long,
    ) {
        val session = findSession(transferId) ?: return
        tokenManager.touchSession(transferId)
        session.mutex.withLock {
            session.bytesReceivedByFile[fileId] = bytesReceived
        }
        publishSnapshots()
    }

    suspend fun completeFileVerification(
        transferId: String,
        fileId: String,
        calculatedSha256: String,
        storedFile: StoredFileDescriptor,
    ): TransferState {
        val session = requireSession(transferId)
        val resultState = session.mutex.withLock {
            session.fsm.transition(TransferEvent.ALL_BYTES_RECEIVED)
            session.verifiedFiles[fileId] = VerifiedFileRecord(
                fileId = fileId,
                sha256 = calculatedSha256,
                storedFile = storedFile,
            )
            session.bytesReceivedByFile[fileId] = storedFile.sizeBytes

            val allVerified = session.acceptedFileIds.all { it in session.verifiedFiles }
            if (allVerified) {
                session.currentFileId = null
                TransferState.VERIFYING
            } else {
                session.fsm.transition(TransferEvent.FILE_VERIFIED_MORE_REMAIN)
                TransferState.TRANSFERRING
            }
        }
        publishSnapshots()
        return resultState
    }

    suspend fun recordStreamFailure(
        transferId: String,
        fileId: String?,
        error: FsapProtocolException,
    ) {
        val session = findSession(transferId) ?: return
        withContext(NonCancellable) {
            session.mutex.withLock {
                session.idleTimerJob?.cancel()
                session.idleTimerJob = null
                val current = session.fsm.currentState()
                if (!current.isTerminal) {
                    when {
                        error.errorCode == FsapErrorCode.HASH_MISMATCH -> {
                            if (current == TransferState.TRANSFERRING) {
                                session.fsm.transition(TransferEvent.ALL_BYTES_RECEIVED)
                            }
                            if (session.fsm.canTransition(TransferEvent.HASH_MISMATCH)) {
                                session.fsm.transition(TransferEvent.HASH_MISMATCH)
                            }
                        }

                        error.errorCode == FsapErrorCode.STREAM_TIMEOUT &&
                            session.fsm.canTransition(TransferEvent.STREAM_TIMEOUT) ->
                            session.fsm.transition(TransferEvent.STREAM_TIMEOUT)

                        error.errorCode == FsapErrorCode.CONNECTION_LOST &&
                            session.fsm.canTransition(TransferEvent.CONNECTION_LOST) ->
                            session.fsm.transition(TransferEvent.CONNECTION_LOST)

                        session.fsm.canTransition(TransferEvent.STREAM_ERROR) ->
                            session.fsm.transition(TransferEvent.STREAM_ERROR)
                    }
                }
                session.lastErrorCode = error.errorCode
                session.lastErrorMessage = error.message
                tokenManager.revoke(transferId)
            }
            runCatching { fileSink.cleanupTransferParts(transferId) }
            publishSnapshots()
        }
    }

    suspend fun verifyControlAccess(
        transferId: String,
        presentedToken: String?,
        senderId: String? = null,
        allowedAdditionalSenderId: String? = null,
        requireTokenWhenActive: Boolean = true,
    ) {
        val session = requireSession(transferId)
        val tokenToValidateActive = session.mutex.withLock {
            if (senderId != null) {
                val matchesSessionSender = senderId.equals(session.senderId, ignoreCase = true)
                val matchesAdditional = allowedAdditionalSenderId != null &&
                    senderId.equals(allowedAdditionalSenderId, ignoreCase = true)
                if (!matchesSessionSender && !matchesAdditional) {
                    throw FsapProtocolException(
                        errorCode = FsapErrorCode.UNAUTHORIZED_TRANSFER,
                        message = "Remetente '$senderId' nao autorizado para controlar a sessao '$transferId'.",
                        transferId = transferId,
                    )
                }
            }

            val state = session.fsm.currentState()
            when {
                state == TransferState.ACCEPTED ||
                    state == TransferState.TRANSFERRING ||
                    state == TransferState.VERIFYING -> {
                    requireTokenWhenActive || presentedToken != null
                }

                state.isTerminal -> {
                    if (presentedToken != null) {
                        val issued = session.transferToken
                        if (issued == null || !PlatformTokenCrypto.constantTimeEquals(issued, presentedToken)) {
                            throw FsapProtocolException(
                                errorCode = FsapErrorCode.UNAUTHORIZED_TRANSFER,
                                message = "Token de transferencia invalido para a sessao '$transferId'.",
                                transferId = transferId,
                            )
                        }
                    }
                    false
                }

                else -> false
            }
        }

        if (tokenToValidateActive) {
            when (
                val tokenResult = tokenManager.validateSessionToken(
                    transferId = transferId,
                    presentedToken = presentedToken,
                    refreshIdleTtl = false,
                )
            ) {
                is TokenValidationResult.Authorized -> Unit
                is TokenValidationResult.Denied -> throw FsapProtocolException(
                    errorCode = tokenResult.errorCode,
                    message = tokenResult.reason,
                    transferId = transferId,
                )
            }
        }
    }

    suspend fun completeTransfer(
        transferId: String,
        submittedFiles: List<CompletedFileSummary>? = null,
    ): TransferCompletePayload {
        val session = requireSession(transferId)
        var integrityFailure: FsapProtocolException? = null
        val payload = session.mutex.withLock {
            val state = session.fsm.currentState()
            if (state != TransferState.VERIFYING ||
                !session.acceptedFileIds.all { it in session.verifiedFiles }
            ) {
                throw FsapProtocolException(
                    errorCode = FsapErrorCode.INVALID_STATE_TRANSITION,
                    message = "Lote '$transferId' nao pode ser concluido no estado '${state.name}'.",
                    transferId = transferId,
                )
            }

            if (submittedFiles != null) {
                val submittedById = LinkedHashMap<String, CompletedFileSummary>(submittedFiles.size)
                for (entry in submittedFiles) {
                    if (entry.fileId.isBlank() || submittedById.put(entry.fileId, entry) != null) {
                        throw FsapProtocolException(
                            errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                            message = "Lista completedFiles possui fileId ausente ou duplicado ('${entry.fileId}').",
                            transferId = transferId,
                            fileId = entry.fileId.takeIf { it.isNotBlank() },
                        )
                    }
                }
                if (submittedById.keys != session.acceptedFileIds) {
                    throw FsapProtocolException(
                        errorCode = FsapErrorCode.INVALID_STATE_TRANSITION,
                        message = "Manifesto de finalizacao diverge dos arquivos aceitos na sessao '$transferId'.",
                        transferId = transferId,
                    )
                }
                for ((fileId, submitted) in submittedById) {
                    val verified = session.verifiedFiles[fileId]
                        ?: throw FsapProtocolException(
                            errorCode = FsapErrorCode.INVALID_STATE_TRANSITION,
                            message = "Arquivo '$fileId' ainda nao foi verificado na sessao '$transferId'.",
                            transferId = transferId,
                            fileId = fileId,
                        )
                    if (!submitted.sha256.equals(verified.sha256, ignoreCase = true)) {
                        session.idleTimerJob?.cancel()
                        session.idleTimerJob = null
                        session.fsm.transition(TransferEvent.HASH_MISMATCH)
                        val failure = FsapProtocolException(
                            errorCode = FsapErrorCode.HASH_MISMATCH,
                            message = "SHA-256 declarado em TRANSFER_COMPLETE para '$fileId' diverge do verificado.",
                            transferId = transferId,
                            fileId = fileId,
                        )
                        session.lastErrorCode = failure.errorCode
                        session.lastErrorMessage = failure.message
                        tokenManager.revoke(transferId)
                        integrityFailure = failure
                        break
                    }
                }
            }

            if (integrityFailure != null) {
                null
            } else {
                session.idleTimerJob?.cancel()
                session.idleTimerJob = null
                session.fsm.transition(TransferEvent.ALL_FILES_VERIFIED_AND_COMPLETED)
                tokenManager.revoke(transferId)

                val summaries = session.acceptedFileIds.mapNotNull { id ->
                    session.verifiedFiles[id]?.let { CompletedFileSummary(fileId = id, sha256 = it.sha256) }
                }
                TransferCompletePayload(
                    transferId = transferId,
                    completedFiles = summaries,
                    state = TransferState.COMPLETED.name,
                )
            }
        }
        publishSnapshots()
        integrityFailure?.let { throw it }
        return payload!!
    }

    suspend fun cancelTransfer(
        transferId: String,
        cancelledBy: CancelledBy,
        reason: String? = null,
    ) {
        cancelTransferAndStatus(transferId = transferId, cancelledBy = cancelledBy, reason = reason)
    }

    suspend fun cancelTransferAndStatus(
        transferId: String,
        cancelledBy: CancelledBy,
        reason: String? = null,
    ): TransferStatusPayload {
        val session = requireSession(transferId)
        val statusPayload = withContext(NonCancellable) {
            val payload = session.mutex.withLock {
                if (session.fsm.currentState().isTerminal) {
                    throw FsapProtocolException(
                        errorCode = FsapErrorCode.INVALID_STATE_TRANSITION,
                        message = "Sessao '$transferId' ja esta em estado terminal '${session.fsm.currentState().name}'.",
                        transferId = transferId,
                    )
                }

                session.approvalTimerJob?.cancel()
                session.approvalTimerJob = null
                session.idleTimerJob?.cancel()
                session.idleTimerJob = null

                session.activeUploadJobs.forEach { it.cancel(CancellationException("Transferencia cancelada ($cancelledBy).")) }
                session.activeUploadJobs.clear()

                session.fsm.transition(TransferEvent.CANCEL_TRANSFER)
                session.lastErrorCode = FsapErrorCode.TRANSFER_CANCELLED
                session.lastErrorMessage = reason ?: "Cancelada por $cancelledBy."
                tokenManager.revoke(transferId)
                session.toStatusPayloadLocked()
            }
            runCatching { fileSink.cleanupTransferParts(transferId) }
            publishSnapshots()
            payload
        }
        return statusPayload
    }

    suspend fun getStatusPayload(transferId: String): TransferStatusPayload {
        val session = requireSession(transferId)
        return session.mutex.withLock { session.toStatusPayloadLocked() }
    }

    private fun ServerTransferSession.toStatusPayloadLocked(): TransferStatusPayload {
        val totalAcceptedBytes = acceptedFileIds.sumOf { id ->
            filesById[id]?.sizeBytes ?: 0L
        }.takeIf { acceptedFileIds.isNotEmpty() } ?: totalBytes

        return TransferStatusPayload(
            transferId = transferId,
            state = fsm.currentState().name,
            totalBytesTransferred = bytesReceivedByFile.values.sum(),
            totalBytes = totalAcceptedBytes,
            currentFileId = currentFileId,
            filesCompleted = verifiedFiles.size,
            totalFiles = acceptedFileIds.size.takeIf { it > 0 } ?: files.size,
        )
    }

    suspend fun findSnapshot(transferId: String): ServerSessionSnapshot? {
        val session = findSession(transferId) ?: return null
        return session.mutex.withLock { session.toSnapshotLocked() }
    }

    suspend fun close() {
        val activeSessions = registryMutex.withLock {
            sessionsMap.values.toList().also { sessionsMap.clear() }
        }
        for (session in activeSessions) {
            session.sessionScope.cancel()
            tokenManager.revoke(session.transferId)
        }
        publishSnapshots()
    }

    private fun armApprovalTimeoutLocked(session: ServerTransferSession) {
        session.approvalTimerJob?.cancel()
        session.approvalTimerJob = session.sessionScope.launch {
            delay(approvalTimeoutMs)
            handleApprovalTimeout(session)
        }
    }

    private fun armIdleTimeoutLocked(session: ServerTransferSession) {
        session.idleTimerJob?.cancel()
        session.idleTimerJob = session.sessionScope.launch {
            delay(tokenIdleTtlMs)
            handleTokenIdleTimeout(session)
        }
    }

    private suspend fun handleApprovalTimeout(session: ServerTransferSession) {
        var expired = false
        session.mutex.withLock {
            if (session.fsm.currentState() == TransferState.PENDING_APPROVAL) {
                session.fsm.transition(TransferEvent.APPROVAL_TIMEOUT)
                session.lastErrorCode = FsapErrorCode.APPROVAL_TIMEOUT
                session.lastErrorMessage = FsapErrorCode.APPROVAL_TIMEOUT.defaultMessage
                tokenManager.revoke(session.transferId)
                expired = true
            }
        }
        if (expired) {
            logger?.warn(
                component = "ServerTransferSessionManager",
                message = "Timeout de aprovacao (${approvalTimeoutMs}ms) para sessao '${session.transferId}'.",
                context = LogContext(transferId = session.transferId, peerId = session.senderId),
            )
            publishSnapshots()
        }
    }

    private suspend fun handleTokenIdleTimeout(session: ServerTransferSession) {
        var expired = false
        session.mutex.withLock {
            if (session.fsm.currentState() == TransferState.ACCEPTED) {
                session.fsm.transition(TransferEvent.TOKEN_IDLE_TIMEOUT)
                session.lastErrorCode = FsapErrorCode.UNAUTHORIZED_TRANSFER
                session.lastErrorMessage = "Sessao expirou por ociosidade apos aceitacao (${tokenIdleTtlMs}ms)."
                tokenManager.revoke(session.transferId)
                expired = true
            }
        }
        if (expired) {
            withContext(NonCancellable) {
                runCatching { fileSink.cleanupTransferParts(session.transferId) }
            }
            logger?.warn(
                component = "ServerTransferSessionManager",
                message = "Token ocioso expirado (${tokenIdleTtlMs}ms) para sessao '${session.transferId}'.",
                context = LogContext(transferId = session.transferId, peerId = session.senderId),
            )
            publishSnapshots()
        }
    }

    private suspend fun findSession(transferId: String): ServerTransferSession? =
        registryMutex.withLock { sessionsMap[transferId] }

    private suspend fun requireSession(transferId: String): ServerTransferSession =
        findSession(transferId)
            ?: throw FsapProtocolException(
                errorCode = FsapErrorCode.FILE_NOT_FOUND,
                message = "Sessao de transferencia '$transferId' nao encontrada.",
                transferId = transferId,
            )

    private suspend fun publishSnapshots() {
        val currentSessions = registryMutex.withLock { sessionsMap.values.toList() }
        val list = currentSessions.map { session ->
            session.mutex.withLock { session.toSnapshotLocked() }
        }
        _sessions.value = list
    }
}


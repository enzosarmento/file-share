package br.edu.fileshare.network.transfer

import br.edu.fileshare.core.concurrency.PlatformLock
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.statemachine.TransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TransferDirection {
    SENDING,
    RECEIVING,
}

data class TransferProgressSnapshot(
    val transferId: String,
    val peerAlias: String,
    val direction: TransferDirection,
    val state: TransferState,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val currentFileId: String? = null,
    val currentFileName: String? = null,
    val filesCompleted: Int = 0,
    val totalFiles: Int = 0,
    val speedBytesPerSecond: Long = 0L,
    val estimatedRemainingSeconds: Long? = null,
    val errorCode: FsapErrorCode? = null,
    val errorMessage: String? = null,
    val updatedAtEpochMillis: Long = 0L,
) {
    val progressFraction: Float
        get() = when {
            state == TransferState.COMPLETED -> 1f
            totalBytes <= 0L -> 0f
            else -> (bytesTransferred.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }

    val percentComplete: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)
}

/**
 * Rastreador concorrente de progresso em tempo real com janela movel de velocidade e throttling de 100 ms (`TASK-XFER-004`).
 */
class TransferProgressTracker(
    private val clock: Clock = SystemClock,
    private val throttleMillis: Long = FsapConstants.T_PROGRESS_THROTTLE_MS,
    private val speedWindowMillis: Long = DEFAULT_SPEED_WINDOW_MS,
) {
    private data class SpeedSample(
        val epochMillis: Long,
        val bytesTransferred: Long,
    )

    private class TrackedEntry(
        val transferId: String,
        val peerAlias: String,
        val direction: TransferDirection,
        var state: TransferState,
        val totalBytes: Long,
        val totalFiles: Int,
        val createdAtEpochMillis: Long,
    ) {
        var bytesTransferred: Long = 0L
        var currentFileId: String? = null
        var currentFileName: String? = null
        var filesCompleted: Int = 0
        var errorCode: FsapErrorCode? = null
        var errorMessage: String? = null
        var updatedAtEpochMillis: Long = createdAtEpochMillis
        val samples: ArrayDeque<SpeedSample> = ArrayDeque()
    }

    private val lock = PlatformLock()
    private val entries = LinkedHashMap<String, TrackedEntry>()
    private val _transfers = MutableStateFlow<List<TransferProgressSnapshot>>(emptyList())
    val transfers: StateFlow<List<TransferProgressSnapshot>> = _transfers.asStateFlow()

    private var lastEmissionEpochMillis: Long = Long.MIN_VALUE / 2

    fun registerTransfer(
        transferId: String,
        peerAlias: String,
        direction: TransferDirection,
        totalBytes: Long,
        totalFiles: Int,
        initialState: TransferState = TransferState.CREATED,
    ) {
        val now = clock.epochMillis()
        val snapshotList = lock.withLock {
            val entry = TrackedEntry(
                transferId = transferId,
                peerAlias = peerAlias,
                direction = direction,
                state = initialState,
                totalBytes = totalBytes.coerceAtLeast(0L),
                totalFiles = totalFiles.coerceAtLeast(0),
                createdAtEpochMillis = now,
            )
            entry.samples.addLast(SpeedSample(now, 0L))
            entries[transferId] = entry
            lastEmissionEpochMillis = now
            buildSnapshotListLocked(now)
        }
        _transfers.value = snapshotList
    }

    fun updateState(
        transferId: String,
        state: TransferState,
        filesCompleted: Int? = null,
        currentFileId: String? = null,
        currentFileName: String? = null,
        errorCode: FsapErrorCode? = null,
        errorMessage: String? = null,
    ) {
        val now = clock.epochMillis()
        val snapshotList = lock.withLock {
            val entry = entries[transferId] ?: return@withLock null
            entry.state = state
            filesCompleted?.let { entry.filesCompleted = it.coerceIn(0, entry.totalFiles) }
            if (state.isTerminal) {
                entry.currentFileId = null
                entry.currentFileName = null
            } else {
                currentFileId?.let { entry.currentFileId = it }
                currentFileName?.let { entry.currentFileName = it }
            }
            if (errorCode != null) entry.errorCode = errorCode
            if (errorMessage != null) entry.errorMessage = errorMessage
            entry.updatedAtEpochMillis = now
            lastEmissionEpochMillis = now
            buildSnapshotListLocked(now)
        }
        if (snapshotList != null) {
            _transfers.value = snapshotList
        }
    }

    /**
     * Atualiza bytes transferidos; aplica throttling de [throttleMillis] exceto quando o lote atinge `totalBytes`.
     */
    fun reportProgress(
        transferId: String,
        bytesTransferred: Long,
        currentFileId: String? = null,
        currentFileName: String? = null,
        filesCompleted: Int? = null,
        forceEmit: Boolean = false,
    ): Boolean {
        val now = clock.epochMillis()
        val snapshotList = lock.withLock {
            val entry = entries[transferId] ?: return@withLock null
            val clampedBytes = bytesTransferred.coerceIn(0L, entry.totalBytes.coerceAtLeast(bytesTransferred))
            if (clampedBytes >= entry.bytesTransferred) {
                entry.bytesTransferred = clampedBytes
                recordSpeedSampleLocked(entry, now, clampedBytes)
            }
            currentFileId?.let { entry.currentFileId = it }
            currentFileName?.let { entry.currentFileName = it }
            filesCompleted?.let { entry.filesCompleted = it.coerceIn(0, entry.totalFiles) }
            entry.updatedAtEpochMillis = now

            val reachedEnd = entry.totalBytes in 1L..entry.bytesTransferred
            val shouldEmit = forceEmit || reachedEnd || (now - lastEmissionEpochMillis >= throttleMillis)
            if (!shouldEmit) return@withLock null

            lastEmissionEpochMillis = now
            buildSnapshotListLocked(now)
        }
        if (snapshotList != null) {
            _transfers.value = snapshotList
            return true
        }
        return false
    }

    fun getSnapshot(transferId: String): TransferProgressSnapshot? = lock.withLock {
        val entry = entries[transferId] ?: return@withLock null
        buildEntrySnapshotLocked(entry, clock.epochMillis())
    }

    fun clearCompleted() {
        val now = clock.epochMillis()
        val snapshotList = lock.withLock {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.state.isTerminal) {
                    iterator.remove()
                }
            }
            lastEmissionEpochMillis = now
            buildSnapshotListLocked(now)
        }
        _transfers.value = snapshotList
    }

    private fun recordSpeedSampleLocked(entry: TrackedEntry, now: Long, bytes: Long) {
        val last = entry.samples.lastOrNull()
        if (last != null && last.epochMillis == now) {
            entry.samples.removeLast()
        }
        entry.samples.addLast(SpeedSample(now, bytes))

        val cutoff = now - speedWindowMillis
        while (entry.samples.size > 2 && entry.samples.first().epochMillis < cutoff) {
            entry.samples.removeFirst()
        }
    }

    private fun buildSnapshotListLocked(now: Long): List<TransferProgressSnapshot> =
        entries.values.map { entry -> buildEntrySnapshotLocked(entry, now) }.reversed()

    private fun buildEntrySnapshotLocked(
        entry: TrackedEntry,
        now: Long,
    ): TransferProgressSnapshot {
        val speed = if (entry.state.isTerminal || entry.state != TransferState.TRANSFERRING) {
            0L
        } else {
            calculateSpeedLocked(entry, now)
        }
        val etaSeconds = if (!entry.state.isTerminal && speed > 0L && entry.totalBytes > entry.bytesTransferred) {
            val remaining = entry.totalBytes - entry.bytesTransferred
            (remaining + speed - 1L) / speed
        } else {
            null
        }
        return TransferProgressSnapshot(
            transferId = entry.transferId,
            peerAlias = entry.peerAlias,
            direction = entry.direction,
            state = entry.state,
            bytesTransferred = entry.bytesTransferred,
            totalBytes = entry.totalBytes,
            currentFileId = entry.currentFileId,
            currentFileName = entry.currentFileName,
            filesCompleted = entry.filesCompleted,
            totalFiles = entry.totalFiles,
            speedBytesPerSecond = speed,
            estimatedRemainingSeconds = etaSeconds,
            errorCode = entry.errorCode,
            errorMessage = entry.errorMessage,
            updatedAtEpochMillis = entry.updatedAtEpochMillis,
        )
    }

    private fun calculateSpeedLocked(entry: TrackedEntry, now: Long): Long {
        if (entry.samples.size < 2) return 0L
        val oldest = entry.samples.first()
        val newest = entry.samples.last()
        val deltaBytes = newest.bytesTransferred - oldest.bytesTransferred
        if (deltaBytes <= 0L) return 0L

        val elapsedMs = maxOf(newest.epochMillis - oldest.epochMillis, now - oldest.epochMillis, 1L)
        return (deltaBytes * 1_000L) / elapsedMs
    }

    companion object {
        const val DEFAULT_SPEED_WINDOW_MS: Long = 2_000L
    }
}

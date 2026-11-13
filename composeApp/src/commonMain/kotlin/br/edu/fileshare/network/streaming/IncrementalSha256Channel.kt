package br.edu.fileshare.network.streaming

import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.StagedFileSession
import br.edu.fileshare.storage.StoredFileDescriptor
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Calculadora incremental multiplataforma de resumo criptografico SHA-256 em minusculo hexadecimal.
 */
expect class IncrementalSha256() {
    fun update(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
    fun digestHex(): String
    fun reset()
}

data class Sha256DigestSummary(
    val sha256Hex: String,
    val totalBytes: Long,
)

data class VerifiedStreamReceiveResult(
    val storedFile: StoredFileDescriptor,
    val bytesReceived: Long,
    val calculatedSha256: String,
)

/**
 * Motor de streaming binario com memoria constante (`65536` bytes por buffer) e SHA-256 incremental (`TASK-XFER-001`).
 */
object IncrementalSha256Channel {

    suspend fun computeDigest(
        source: FileSource,
        chunkSizeBytes: Int = FsapConstants.CHUNK_SIZE_BYTES,
        onBytesProcessed: suspend (bytesProcessed: Long) -> Unit = {},
    ): Sha256DigestSummary {
        require(chunkSizeBytes > 0) { "chunkSizeBytes deve ser positivo: $chunkSizeBytes" }
        val buffer = ByteArray(chunkSizeBytes)
        val digest = IncrementalSha256()
        var totalBytes = 0L

        val session = source.openReadSession()
        try {
            while (true) {
                val read = session.readChunk(buffer)
                if (read < 0) break
                if (read == 0) continue

                digest.update(buffer, 0, read)
                totalBytes += read
                onBytesProcessed(totalBytes)
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { session.close() }
            }
        }

        return Sha256DigestSummary(
            sha256Hex = digest.digestHex(),
            totalBytes = totalBytes,
        )
    }

    suspend fun copySourceToWriteChannel(
        source: FileSource,
        writeChannel: ByteWriteChannel,
        chunkSizeBytes: Int = FsapConstants.CHUNK_SIZE_BYTES,
        onChunkSent: suspend (bytesSent: Long) -> Unit = {},
    ): Long {
        require(chunkSizeBytes > 0) { "chunkSizeBytes deve ser positivo: $chunkSizeBytes" }
        val buffer = ByteArray(chunkSizeBytes)
        var totalSent = 0L

        val session = source.openReadSession()
        try {
            while (true) {
                val read = session.readChunk(buffer)
                if (read < 0) break
                if (read == 0) continue

                writeChannel.writeFully(buffer, 0, read)
                totalSent += read
                onChunkSent(totalSent)
            }
            writeChannel.flush()
        } finally {
            withContext(NonCancellable) {
                runCatching { session.close() }
            }
        }

        return totalSent
    }

    suspend fun receiveChannelToSinkVerified(
        readChannel: ByteReadChannel,
        sinkSession: StagedFileSession,
        expectedSizeBytes: Long,
        expectedSha256: String,
        chunkSizeBytes: Int = FsapConstants.CHUNK_SIZE_BYTES,
        readTimeoutMs: Long = FsapConstants.T_STREAM_READ_TIMEOUT_MS,
        onChunkReceived: suspend (bytesReceived: Long) -> Unit = {},
    ): VerifiedStreamReceiveResult {
        require(chunkSizeBytes > 0) { "chunkSizeBytes deve ser positivo: $chunkSizeBytes" }
        require(expectedSizeBytes >= 0L) { "expectedSizeBytes nao pode ser negativo: $expectedSizeBytes" }

        val buffer = ByteArray(chunkSizeBytes)
        val digest = IncrementalSha256()
        var bytesReceived = 0L
        var committed = false

        try {
            while (!readChannel.isClosedForRead) {
                val read = try {
                    withTimeout(readTimeoutMs) {
                        readChannel.readAvailable(buffer, 0, buffer.size)
                    }
                } catch (timeout: TimeoutCancellationException) {
                    throw FsapProtocolException(
                        errorCode = FsapErrorCode.STREAM_TIMEOUT,
                        message = "Timeout de leitura do fluxo binario apos ${readTimeoutMs}ms.",
                        transferId = sinkSession.transferId,
                        fileId = sinkSession.fileId,
                        cause = timeout,
                    )
                }

                if (read < 0) break
                if (read == 0) continue

                if (read > expectedSizeBytes - bytesReceived) {
                    throw FsapProtocolException(
                        errorCode = FsapErrorCode.SIZE_MISMATCH,
                        message = "Stream excedeu o tamanho aprovado de $expectedSizeBytes bytes.",
                        transferId = sinkSession.transferId,
                        fileId = sinkSession.fileId,
                    )
                }

                digest.update(buffer, 0, read)
                sinkSession.writeChunk(buffer, 0, read)
                bytesReceived += read
                onChunkReceived(bytesReceived)
            }

            if (bytesReceived != expectedSizeBytes) {
                throw FsapProtocolException(
                    errorCode = FsapErrorCode.SIZE_MISMATCH,
                    message = "Quantidade recebida ($bytesReceived B) diverge do declarado ($expectedSizeBytes B).",
                    transferId = sinkSession.transferId,
                    fileId = sinkSession.fileId,
                )
            }

            val calculatedSha256 = digest.digestHex()
            if (!calculatedSha256.equals(expectedSha256, ignoreCase = true)) {
                throw FsapProtocolException(
                    errorCode = FsapErrorCode.HASH_MISMATCH,
                    message = "SHA-256 calculado ($calculatedSha256) diverge do esperado ($expectedSha256).",
                    transferId = sinkSession.transferId,
                    fileId = sinkSession.fileId,
                )
            }

            sinkSession.flushAndSync()
            val stored = sinkSession.commitPart(
                verifiedSha256 = calculatedSha256,
                totalBytesWritten = bytesReceived,
            )
            committed = true
            return VerifiedStreamReceiveResult(
                storedFile = stored,
                bytesReceived = bytesReceived,
                calculatedSha256 = calculatedSha256,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (!committed) {
                withContext(NonCancellable) {
                    runCatching { sinkSession.discardPart() }
                }
            }
        }
    }
}

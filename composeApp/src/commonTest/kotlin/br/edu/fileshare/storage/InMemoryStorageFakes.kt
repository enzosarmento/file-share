package br.edu.fileshare.storage

import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import kotlin.math.min

/**
 * Fonte de arquivo virtual que gera bytes determinísticos sob demanda em chunks,
 * permitindo testar arquivos de dezenas de MB com consumo de RAM O(1).
 */
class SyntheticPatternFileSource(
    override val fileName: String,
    override val sizeBytes: Long,
    private val patternByte: Byte = 0x00,
    override val mimeType: String = "application/octet-stream",
    val observedBufferSizes: MutableList<Int> = mutableListOf(),
) : FileSource {

    override suspend fun openReadSession(): FileReadSession = object : FileReadSession {
        private var remaining: Long = sizeBytes
        private var closed: Boolean = false

        override suspend fun readChunk(buffer: ByteArray): Int {
            check(!closed) { "Sessao de leitura ja encerrada." }
            observedBufferSizes += buffer.size
            if (remaining <= 0L) return -1
            val toRead = min(buffer.size.toLong(), remaining).toInt()
            buffer.fill(patternByte, 0, toRead)
            remaining -= toRead
            return toRead
        }

        override suspend fun close() {
            closed = true
        }
    }
}

class InMemoryFileSource(
    override val fileName: String,
    private val data: ByteArray,
    override val mimeType: String = "application/octet-stream",
) : FileSource {
    override val sizeBytes: Long = data.size.toLong()

    override suspend fun openReadSession(): FileReadSession = object : FileReadSession {
        private var offset: Int = 0
        private var closed: Boolean = false

        override suspend fun readChunk(buffer: ByteArray): Int {
            check(!closed) { "Sessao de leitura ja encerrada." }
            if (offset >= data.size) return -1
            val count = min(buffer.size, data.size - offset)
            data.copyInto(buffer, destinationOffset = 0, startIndex = offset, endIndex = offset + count)
            offset += count
            return count
        }

        override suspend fun close() {
            closed = true
        }
    }
}

/**
 * Destino em memoria que simula arquivos `.part`, commit atomico e politica de nao-sobrescrita.
 */
class InMemoryFileSink(
    var availableBytes: Long = 1024L * 1024L * 1024L,
) : FileSink {
    private val stagedParts = linkedMapOf<String, MutableList<Byte>>()
    private val committedFiles = linkedMapOf<String, ByteArray>()

    fun stagedPartKeys(): Set<String> = stagedParts.keys.toSet()

    fun committedFileNames(): Set<String> = committedFiles.keys.toSet()

    fun readCommittedBytes(finalName: String): ByteArray? = committedFiles[finalName]?.copyOf()

    override suspend fun availableStorageBytes(): Long = availableBytes

    override suspend fun openPartSession(
        transferId: String,
        fileId: String,
        requestedFileName: String,
        expectedSizeBytes: Long,
    ): StagedFileSession {
        val validName = FileNameSanitizer.requireValid(requestedFileName)
        val partKey = "${transferId}_${fileId}.part"
        if (partKey in stagedParts) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INVALID_STATE_TRANSITION,
                message = "Arquivo temporario '$partKey' ja esta aberto.",
                transferId = transferId,
                fileId = fileId,
            )
        }

        val buffer = ArrayList<Byte>()
        stagedParts[partKey] = buffer

        return object : StagedFileSession {
            override val transferId: String = transferId
            override val fileId: String = fileId
            override val sanitizedFileName: String = validName
            override val partIdentifier: String = partKey
            private var closed = false

            override suspend fun writeChunk(bufferArg: ByteArray, offset: Int, length: Int) {
                check(!closed) { "Sessao de escrita encerrada para '$partKey'." }
                for (i in offset until (offset + length)) {
                    buffer.add(bufferArg[i])
                }
            }

            override suspend fun flushAndSync() = Unit

            override suspend fun commitPart(
                verifiedSha256: String,
                totalBytesWritten: Long,
            ): StoredFileDescriptor {
                check(!closed) { "Sessao ja encerrada para '$partKey'." }
                closed = true
                val raw = stagedParts.remove(partKey)?.toByteArray() ?: ByteArray(0)
                val resolvedName = FileNameSanitizer.resolveNonOverwritingName(validName) {
                    it in committedFiles
                }
                committedFiles[resolvedName] = raw
                return StoredFileDescriptor(
                    finalName = resolvedName,
                    locationUri = "mem://$resolvedName",
                    sizeBytes = totalBytesWritten,
                    sha256 = verifiedSha256,
                )
            }

            override suspend fun discardPart() {
                closed = true
                stagedParts.remove(partKey)
            }
        }
    }

    override suspend fun cleanupTransferParts(transferId: String): Int {
        val prefix = "${transferId}_"
        val matching = stagedParts.keys.filter { it.startsWith(prefix) }
        matching.forEach { stagedParts.remove(it) }
        return matching.size
    }
}

package br.edu.fileshare.storage

import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException

/**
 * Descritor de arquivo efetivamente gravado, verificado por SHA-256 e promovido ao destino final.
 */
data class StoredFileDescriptor(
    val finalName: String,
    val locationUri: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * Porta multiplataforma de escrita segura em streaming no destinatario (`TASK-XFER-001`).
 * Garante gravacao previa em arquivo temporario `.part`, checagem de espaco disponivel e
 * promocao atomica somente apos confirmacao criptografica SHA-256.
 */
interface FileSink {
    /**
     * Retorna o espaco utilizavel estimado no armazenamento de destino, em bytes.
     */
    suspend fun availableStorageBytes(): Long

    /**
     * Valida cota minima antes da aceitacao/recepcao: `requiredBytes + safetyMarginBytes` (padrao 16 MiB).
     */
    suspend fun ensureCapacity(
        requiredBytes: Long,
        safetyMarginBytes: Long = DEFAULT_SAFETY_MARGIN_BYTES,
    ) {
        val available = availableStorageBytes()
        val requiredWithMargin = if (requiredBytes > Long.MAX_VALUE - safetyMarginBytes) {
            Long.MAX_VALUE
        } else {
            requiredBytes + safetyMarginBytes
        }
        if (available < requiredWithMargin) {
            throw FsapProtocolException(
                errorCode = FsapErrorCode.INSUFFICIENT_STORAGE,
                message = "Espaco em disco insuficiente: disponivel=$available B, necessario=$requiredWithMargin B.",
            )
        }
    }

    /**
     * Abre uma sessao isolada `<transferId>_<fileId>.part` em staging apos validar `requestedFileName`.
     */
    suspend fun openPartSession(
        transferId: String,
        fileId: String,
        requestedFileName: String,
        expectedSizeBytes: Long,
    ): StagedFileSession

    /**
     * Remove todos os arquivos temporarios `.part` pendentes de um `transferId`.
     */
    suspend fun cleanupTransferParts(transferId: String): Int

    companion object {
        const val DEFAULT_SAFETY_MARGIN_BYTES: Long = 16L * 1024L * 1024L // 16 MiB
    }
}

/**
 * Sessao ativa de gravacao em arquivo temporario `.part`.
 */
interface StagedFileSession {
    val transferId: String
    val fileId: String
    val sanitizedFileName: String
    val partIdentifier: String

    suspend fun writeChunk(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)

    suspend fun flushAndSync()

    /**
     * Move atomicamente `.part` para o nome final nao-sobrescrito apos validacao de tamanho e hash.
     */
    suspend fun commitPart(verifiedSha256: String, totalBytesWritten: Long): StoredFileDescriptor

    /**
     * Descarta imediatamente o arquivo temporario `.part`.
     */
    suspend fun discardPart()
}

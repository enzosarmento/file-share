package br.edu.fileshare.storage

/**
 * Porta multiplataforma de leitura sequencial de um arquivo de origem em blocos (chunks).
 * Implementacoes nativas (NIO Desktop / ContentResolver Android) nunca devem carregar o arquivo
 * inteiro em memoria RAM (`readBytes()` e proibido).
 */
interface FileSource {
    val fileName: String
    val sizeBytes: Long
    val mimeType: String

    suspend fun openReadSession(): FileReadSession
}

/**
 * Sessao de leitura sequencial em streaming associada a um [FileSource].
 */
interface FileReadSession {
    /**
     * Preenche `buffer[0 until length]` e retorna o numero de bytes lidos, ou `-1` no fim do arquivo (EOF).
     */
    suspend fun readChunk(buffer: ByteArray): Int

    suspend fun close()
}

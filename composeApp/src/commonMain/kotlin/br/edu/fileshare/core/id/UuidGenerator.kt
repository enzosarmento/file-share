package br.edu.fileshare.core.id

/**
 * Gerador de identificadores unicos UUID v4 para mensagens, sessoes e dispositivos FSAP.
 */
interface UuidGenerator {
    fun nextUuidV4(): String
}

/**
 * Implementacao padrao por plataforma utilizando fonte criptografica de UUID v4.
 */
expect object PlatformUuidGenerator : UuidGenerator {
    override fun nextUuidV4(): String
}


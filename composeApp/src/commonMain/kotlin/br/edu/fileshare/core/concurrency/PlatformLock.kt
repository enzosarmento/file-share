package br.edu.fileshare.core.concurrency

/**
 * Lock sincrono multiplataforma para proteger estruturas em memoria de acesso nao-suspenso.
 */
expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}


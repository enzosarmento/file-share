package br.edu.fileshare.core.time

/**
 * Abstracao de relogio para marcacao de timestamps e controle deterministico em testes.
 */
interface Clock {
    fun epochMillis(): Long
    fun nowIsoUtc(): String
}

/**
 * Implementacao de relogio do sistema operacional fornecida por plataforma.
 */
expect object SystemClock : Clock {
    override fun epochMillis(): Long
    override fun nowIsoUtc(): String
}


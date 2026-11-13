package br.edu.fileshare.core.di

import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock

/**
 * Contrato base de composicao manual de dependencias compartilhadas entre Desktop e Android.
 * Evita singleton global implicito e facilita a injecao de fakes em testes.
 */
interface AppContainer {
    val logger: FsapLogger
    val clock: Clock
}

/**
 * Implementacao padrao de [AppContainer] para componentes compartilhados e testes.
 */
open class DefaultAppContainer(
    override val logger: FsapLogger,
    override val clock: Clock = SystemClock,
) : AppContainer


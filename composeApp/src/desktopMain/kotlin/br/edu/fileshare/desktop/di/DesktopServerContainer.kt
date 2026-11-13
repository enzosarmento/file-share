package br.edu.fileshare.desktop.di

import br.edu.fileshare.core.di.DefaultAppContainer
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.desktop.logging.Slf4jFsapLogger

/**
 * Container manual de dependencias para o Servidor Desktop Independente (Headless/CLI).
 * Nao instancia nem referencia bibliotecas visuais.
 */
class DesktopServerContainer(
    clock: Clock = SystemClock,
    logger: FsapLogger = Slf4jFsapLogger(clock),
) : DefaultAppContainer(logger = logger, clock = clock)


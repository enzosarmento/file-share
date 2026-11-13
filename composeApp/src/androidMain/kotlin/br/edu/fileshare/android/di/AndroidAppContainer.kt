package br.edu.fileshare.android.di

import br.edu.fileshare.android.logging.AndroidFsapLogger
import br.edu.fileshare.core.di.DefaultAppContainer
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock

/**
 * Container manual de dependencias para o aplicativo e servicos Android.
 */
class AndroidAppContainer(
    clock: Clock = SystemClock,
    logger: FsapLogger = AndroidFsapLogger(clock),
) : DefaultAppContainer(logger = logger, clock = clock)


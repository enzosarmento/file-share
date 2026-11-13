package br.edu.fileshare.desktop.logging

import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.desktop.di.DesktopServerContainer
import kotlin.test.Test
import kotlin.test.assertTrue

class Slf4jFsapLoggerTest {

    @Test
    fun desktopContainerProvidesSlf4jLoggerWithoutUiDependencies() {
        val container = DesktopServerContainer()
        container.logger.info(
            component = "ServerBootstrap",
            message = "Initialized logging infrastructure",
            context = LogContext(deviceId = "desktop-test"),
        )
        assertTrue(container.clock.nowIsoUtc().endsWith("Z"))
    }
}


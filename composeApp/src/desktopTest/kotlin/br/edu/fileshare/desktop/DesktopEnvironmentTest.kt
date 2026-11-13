package br.edu.fileshare.desktop

import br.edu.fileshare.ProjectInfo
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Teste estrutural inicial do sourceSet `desktopTest` (Milestone M0).
 */
class DesktopEnvironmentTest {
    @Test
    fun verifyDesktopSourceSetConfiguration() {
        assertTrue(ProjectInfo.PROTOCOL_VERSION.isNotBlank())
    }
}


package br.edu.fileshare.android

import br.edu.fileshare.ProjectInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Teste estrutural inicial do sourceSet `androidUnitTest` (Milestone M0).
 */
class AndroidEnvironmentTest {
    @Test
    fun verifyAndroidSourceSetConfiguration() {
        assertEquals("FSAP", ProjectInfo.PROTOCOL_NAME)
    }
}


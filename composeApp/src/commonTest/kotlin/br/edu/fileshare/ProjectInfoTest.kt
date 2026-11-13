package br.edu.fileshare

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Teste estrutural inicial do sourceSet `commonTest` (Milestone M0).
 * Os casos de teste do protocolo FSAP estao especificados em `docs/protocol-test-cases.md`.
 */
class ProjectInfoTest {
    @Test
    fun verifyProjectMetadataConstants() {
        assertEquals("FileShare", ProjectInfo.PROJECT_NAME)
        assertEquals("FSAP", ProjectInfo.PROTOCOL_NAME)
        assertEquals("1.0", ProjectInfo.PROTOCOL_VERSION)
    }
}


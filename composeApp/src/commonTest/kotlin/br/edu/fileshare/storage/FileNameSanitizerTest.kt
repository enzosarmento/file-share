package br.edu.fileshare.storage

import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FileNameSanitizerTest {

    @Test
    fun acceptValidFilenames() {
        val validNames = listOf(
            "relatorio-redes-final.pdf",
            "Foto da Turma 2026.jpg",
            ".gitignore",
            "archive.tar.gz",
            "a",
        )
        validNames.forEach { name ->
            assertEquals(name, FileNameSanitizer.requireValid(name))
        }
    }

    @Test
    fun blockPathTraversalSubdirectoriesControlCharsAndReservedNamesTcFsap009() {
        val maliciousNames = listOf(
            "",
            "   ",
            ".",
            "..",
            "../etc/passwd",
            "../../etc/shadow",
            "..\\Windows\\System32\\cmd.exe",
            "pasta/arquivo.txt",
            "pasta\\arquivo.txt",
            "foo..bar.txt",
            "null\u0000byte.txt",
            "ctrl\nline.txt",
            "bad:name.txt",
            "bad*glob?.txt",
            "CON",
            "con.txt",
            "nul",
            "AUX.pdf",
            "COM1",
            "LPT9.log",
            "trailing-dot.",
            "trailing-space ",
            "x".repeat(256),
            "á".repeat(130), // > 255 bytes UTF-8 (260 bytes)
        )

        maliciousNames.forEach { raw ->
            assertIs<FileNameValidationResult.Unsafe>(
                FileNameSanitizer.validate(raw),
                "Expected unsafe rejection for: '$raw'",
            )
            val ex = assertFailsWith<FsapProtocolException> {
                FileNameSanitizer.requireValid(raw)
            }
            assertEquals(FsapErrorCode.INVALID_FILENAME, ex.errorCode)
        }
    }

    @Test
    fun resolveNonOverwritingNamePreservesExistingFilesTcFsap024() {
        val existing = mutableSetOf("documento.pdf", "documento (1).pdf", "README")

        assertEquals(
            "documento (2).pdf",
            FileNameSanitizer.resolveNonOverwritingName("documento.pdf") { it in existing },
        )
        assertEquals(
            "README (1)",
            FileNameSanitizer.resolveNonOverwritingName("README") { it in existing },
        )
        assertEquals(
            "novo.txt",
            FileNameSanitizer.resolveNonOverwritingName("novo.txt") { it in existing },
        )
    }

    @Test
    fun resolvedCollisionNameStaysWithinUtf8ByteLimit() {
        val longName = "a".repeat(250) + ".pdf" // 254 bytes
        val resolved = FileNameSanitizer.resolveNonOverwritingName(longName) { it == longName }
        assertTrueUtf8Limit(resolved)
        assertEquals(".pdf", resolved.takeLast(4))
        assertEquals(" (1).pdf", resolved.takeLast(8))
    }

    private fun assertTrueUtf8Limit(name: String) {
        val bytes = name.encodeToByteArray().size
        assertEquals(true, bytes <= FileNameSanitizer.MAX_FILENAME_UTF8_BYTES, "UTF-8 size=$bytes")
    }
}


package br.edu.fileshare.desktop.server

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerCliParserTest {

    @Test
    fun parseDefaultsWhenNoArgumentsProvided() {
        val config = ServerCliParser.parse(emptyArray())

        assertEquals(ServerCliConfig.DEFAULT_PORT, config.port)
        assertTrue(config.alias.isNotBlank())
        assertTrue(config.alias.length <= ServerCliConfig.MAX_ALIAS_LENGTH)
        assertFalse(config.autoAcceptTest)
        assertFalse(config.showHelp)
    }

    @Test
    fun parseAllSupportedOptionsInSeparateAndInlineForms() {
        val config = ServerCliParser.parse(
            arrayOf(
                "--port",
                "53319",
                "--alias=ServerLab",
                "--download-dir",
                "/tmp/fileshare-recv",
                "--auto-accept-test",
            )
        )

        assertEquals(53319, config.port)
        assertEquals("ServerLab", config.alias)
        assertEquals(Path.of("/tmp/fileshare-recv"), config.downloadDir)
        assertTrue(config.autoAcceptTest)
        assertFalse(config.showHelp)
    }

    @Test
    fun parseHelpFlag() {
        val config = ServerCliParser.parse(arrayOf("--help"))
        assertTrue(config.showHelp)
        assertTrue(ServerCliParser.usage().contains("--port"))
        assertTrue(ServerCliParser.usage().contains("--auto-accept-test"))
    }

    @Test
    fun rejectInvalidPortsAndMalformedValues() {
        assertFailsWith<CliParseException> {
            ServerCliParser.parse(arrayOf("--port", "0"))
        }
        assertFailsWith<CliParseException> {
            ServerCliParser.parse(arrayOf("--port", "65536"))
        }
        assertFailsWith<CliParseException> {
            ServerCliParser.parse(arrayOf("--port", "abc"))
        }
        assertFailsWith<CliParseException> {
            ServerCliParser.parse(arrayOf("--port"))
        }
    }

    @Test
    fun rejectBlankOrOversizedAliasAndUnknownOptions() {
        assertFailsWith<CliParseException> {
            ServerCliParser.parse(arrayOf("--alias", "   "))
        }
        assertFailsWith<CliParseException> {
            ServerCliParser.parse(arrayOf("--alias", "A".repeat(65)))
        }
        assertFailsWith<CliParseException> {
            ServerCliParser.parse(arrayOf("--download-dir", " "))
        }
        assertFailsWith<CliParseException> {
            ServerCliParser.parse(arrayOf("--unknown-flag"))
        }
        assertFailsWith<CliParseException> {
            ServerCliParser.parse(arrayOf("--auto-accept-test=maybe"))
        }
    }
}


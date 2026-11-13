package br.edu.fileshare.protocol.model

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.protocol.FsapConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private class DeterministicClock(
    private val iso: String = "2026-11-12T20:15:30Z",
    private val millis: Long = 1_762_978_530_000L,
) : Clock {
    override fun epochMillis(): Long = millis
    override fun nowIsoUtc(): String = iso
}

class FsapEnvelopeTest {

    @Test
    fun deviceIdentitySerializesAndDeserializesCleanly() {
        val identity = DeviceIdentity(
            deviceId = "a1b2c3d4-1111-4222-8333-444455556666",
            alias = "Notebook-Enzo",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
            httpPort = 53317,
        )

        val encoded = FsapJson.codec.encodeToString(identity)
        val decoded = FsapJson.codec.decodeFromString<DeviceIdentity>(encoded)
        assertEquals(identity, decoded)
        assertTrue(encoded.contains("\"protocolVersion\":\"1.0\""))
    }

    @Test
    fun envelopeRoundTripEncodesDefaultsAndIgnoresUnknownFields() {
        val envelope = FsapEnvelopeFactory.create(
            type = FsapMessageType.DISCOVER,
            senderId = "11111111-2222-4333-8444-555566667777",
            payload = mapOf("requesterAlias" to "Pixel-7"),
            clock = DeterministicClock(),
        )

        val rawJson = FsapJson.encodeEnvelope(envelope)
        assertTrue(rawJson.contains("\"protocol\":\"${FsapConstants.PROTOCOL_NAME}\""))
        assertTrue(rawJson.contains("\"version\":\"${FsapConstants.PROTOCOL_VERSION}\""))
        assertTrue(rawJson.contains("\"type\":\"DISCOVER\""))

        val withExtraField = rawJson.dropLast(1) + ",\"futureExtension\":42}"
        val decoded = FsapJson.decodeEnvelope<Map<String, String>>(withExtraField)
        assertEquals(envelope, decoded)
    }

    @Test
    fun uuidV4GeneratorMatchesRfcFormatAndUniqueness() {
        val uuidV4Regex = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
        )
        val first = PlatformUuidGenerator.nextUuidV4()
        val second = PlatformUuidGenerator.nextUuidV4()

        assertTrue(uuidV4Regex.matches(first), "Invalid UUID v4: $first")
        assertTrue(uuidV4Regex.matches(second), "Invalid UUID v4: $second")
        assertNotEquals(first, second)
    }
}


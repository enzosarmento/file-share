package br.edu.fileshare.network.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

actual object PlatformTokenCrypto {
    private val secureRandom = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    actual fun generateUrlSafeToken(byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    actual fun constantTimeEquals(expected: String, actual: String): Boolean {
        val expectedBytes = expected.encodeToByteArray()
        val actualBytes = actual.encodeToByteArray()
        return expectedBytes.size == actualBytes.size &&
            MessageDigest.isEqual(expectedBytes, actualBytes)
    }
}


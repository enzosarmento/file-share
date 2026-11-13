package br.edu.fileshare.network.streaming

import java.security.MessageDigest

actual class IncrementalSha256 actual constructor() {
    private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")

    actual fun update(buffer: ByteArray, offset: Int, length: Int) {
        if (length > 0) {
            digest.update(buffer, offset, length)
        }
    }

    actual fun digestHex(): String {
        val bytes = digest.digest()
        val hex = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            hex[i * 2] = HEX_CHARS[value ushr 4]
            hex[i * 2 + 1] = HEX_CHARS[value and 0x0F]
        }
        return String(hex)
    }

    actual fun reset() {
        digest.reset()
    }

    private companion object {
        private const val HEX_CHARS = "0123456789abcdef"
    }
}

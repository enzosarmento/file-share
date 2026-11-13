package br.edu.fileshare.protocol.model

import br.edu.fileshare.protocol.FsapConstants
import kotlinx.serialization.Serializable

/**
 * Classificacao canonica do hardware/papel do dispositivo no protocolo FSAP/1.0.
 */
@Serializable
enum class DeviceType {
    DESKTOP,
    MOBILE,
    TABLET,
    SERVER,
}

/**
 * Sistema operacional do no na rede local.
 */
@Serializable
enum class PlatformType {
    LINUX,
    WINDOWS,
    ANDROID,
}

/**
 * Identidade logica de um no FileShare anunciada em `ANNOUNCE` e `IDENTIFY`.
 */
@Serializable
data class DeviceIdentity(
    val deviceId: String,
    val alias: String,
    val deviceType: DeviceType,
    val platform: PlatformType,
    val protocolVersion: String = FsapConstants.PROTOCOL_VERSION,
    val httpPort: Int = FsapConstants.DEFAULT_HTTP_PORT,
    val capabilities: List<String> = DEFAULT_CAPABILITIES,
) {
    companion object {
        val DEFAULT_CAPABILITIES: List<String> = listOf("MULTI_FILE", "SHA256_STREAM")
    }
}


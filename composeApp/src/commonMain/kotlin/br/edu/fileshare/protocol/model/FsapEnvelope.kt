package br.edu.fileshare.protocol.model

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.protocol.FsapConstants
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Todos os tipos de mensagens canonicos definidos pelo FSAP/1.0.
 */
@Serializable
enum class FsapMessageType {
    ANNOUNCE,
    DISCOVER,
    BYE,
    IDENTIFY,
    TRANSFER_REQUEST,
    TRANSFER_ACCEPT,
    TRANSFER_REJECT,
    TRANSFER_CONTENT,
    TRANSFER_STATUS,
    TRANSFER_CANCEL,
    TRANSFER_COMPLETE,
    ERROR,
}

/**
 * Envelope padrao UTF-8 JSON para todas as mensagens de controle e descoberta FSAP/1.0.
 */
@Serializable
data class FsapEnvelope<T>(
    val protocol: String = FsapConstants.PROTOCOL_NAME,
    val version: String = FsapConstants.PROTOCOL_VERSION,
    val type: String,
    val messageId: String,
    val timestamp: String,
    val senderId: String,
    val payload: T,
)

/**
 * Envelope intermediario usado para inspecao de cabecalho e roteamento dinamico por `type`.
 */
typealias RawFsapEnvelope = FsapEnvelope<JsonElement>

/**
 * Instancia centralizada de serializacao JSON do protocolo FSAP/1.0.
 */
object FsapJson {
    val codec: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    inline fun <reified T> encodeEnvelope(envelope: FsapEnvelope<T>): String =
        codec.encodeToString(envelope)

    inline fun <reified T> decodeEnvelope(rawJson: String): FsapEnvelope<T> =
        codec.decodeFromString(rawJson)

    fun decodeRawEnvelope(rawJson: String): RawFsapEnvelope =
        codec.decodeFromString(rawJson)
}

/**
 * Construtor utilitario para emitir envelopes conformes a partir do remetente local.
 */
object FsapEnvelopeFactory {
    fun <T> create(
        type: FsapMessageType,
        senderId: String,
        payload: T,
        uuidGenerator: UuidGenerator = PlatformUuidGenerator,
        clock: Clock = SystemClock,
    ): FsapEnvelope<T> = FsapEnvelope(
        protocol = FsapConstants.PROTOCOL_NAME,
        version = FsapConstants.PROTOCOL_VERSION,
        type = type.name,
        messageId = uuidGenerator.nextUuidV4(),
        timestamp = clock.nowIsoUtc(),
        senderId = senderId,
        payload = payload,
    )
}


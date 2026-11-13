package br.edu.fileshare.network.discovery

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.logging.LogContext
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.ErrorPayload
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapHeaders
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.IdentifyPayload
import br.edu.fileshare.protocol.validation.FsapMessageValidator
import br.edu.fileshare.protocol.validation.ValidationResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException

/**
 * Resultado tipado de uma tentativa de conexao manual por IP via `IDENTIFY` (`TASK-DISC-005`).
 */
sealed interface ManualConnectResult {
    data class Connected(val peer: DiscoveredPeer) : ManualConnectResult

    data class Failed(
        val errorCode: FsapErrorCode,
        val message: String,
        val cause: Throwable? = null,
    ) : ManualConnectResult
}

/**
 * Servico de conexao manual por endereco IP/porta via handshake HTTP `POST /fsap/v1/identify`.
 * Funciona independentemente do UDP Multicast (`RF-05`, `TC-FSAP-006`, `TC-FSAP-007`).
 */
class ManualPeerConnector(
    private val localIdentity: DeviceIdentity,
    private val peerRegistry: PeerRegistry,
    private val logger: FsapLogger? = null,
    private val clock: Clock = SystemClock,
    private val uuidGenerator: UuidGenerator = PlatformUuidGenerator,
    private val connectTimeoutMs: Long = FsapConstants.T_TCP_CONNECT_TIMEOUT_MS,
    providedHttpClient: HttpClient? = null,
) {
    private val ownsHttpClient: Boolean = providedHttpClient == null
    private val httpClient: HttpClient = providedHttpClient ?: createDefaultHttpClient(connectTimeoutMs)

    suspend fun connectByManualIpOrThrow(
        ipAddress: String,
        port: Int = FsapConstants.DEFAULT_HTTP_PORT,
    ): DiscoveredPeer = when (val result = connectByManualIp(ipAddress, port)) {
        is ManualConnectResult.Connected -> result.peer
        is ManualConnectResult.Failed -> throw FsapProtocolException(
            errorCode = result.errorCode,
            message = result.message,
            cause = result.cause,
        )
    }

    suspend fun connectByManualIp(
        ipAddress: String,
        port: Int = FsapConstants.DEFAULT_HTTP_PORT,
    ): ManualConnectResult {
        val cleanHost = ipAddress.trim()
        if (!isValidTargetHost(cleanHost)) {
            return ManualConnectResult.Failed(
                errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                message = "Endereco IP ou hostname invalido: '$ipAddress'.",
            )
        }
        if (!FsapMessageValidator.isValidPort(port)) {
            return ManualConnectResult.Failed(
                errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                message = "Porta TCP fora do intervalo valido (1024..65535): $port.",
            )
        }

        val targetUrl = "http://${formatHostForUrl(cleanHost)}:$port${FsapConstants.Routes.IDENTIFY}"
        val requestEnvelope = FsapEnvelopeFactory.create(
            type = FsapMessageType.IDENTIFY,
            senderId = localIdentity.deviceId,
            payload = IdentifyPayload(
                alias = localIdentity.alias,
                deviceType = localIdentity.deviceType,
                platform = localIdentity.platform,
                httpPort = localIdentity.httpPort,
                capabilities = localIdentity.capabilities,
            ),
            uuidGenerator = uuidGenerator,
            clock = clock,
        )

        val response = try {
            withTimeout(connectTimeoutMs) {
                httpClient.post(targetUrl) {
                    header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                    header(FsapHeaders.PROTOCOL, FsapConstants.PROTOCOL_NAME)
                    contentType(ContentType.Application.Json)
                    setBody(FsapJson.encodeEnvelope(requestEnvelope))
                }
            }
        } catch (ex: TimeoutCancellationException) {
            logger?.warn(
                component = "ManualPeerConnector",
                message = "Timeout (${connectTimeoutMs}ms) ao conectar manualmente em $cleanHost:$port.",
                context = LogContext(deviceId = localIdentity.deviceId),
                throwable = ex,
            )
            return ManualConnectResult.Failed(
                errorCode = FsapErrorCode.DEVICE_NOT_FOUND,
                message = "Tempo limite excedido ao contatar $cleanHost:$port.",
                cause = ex,
            )
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Throwable) {
            logger?.warn(
                component = "ManualPeerConnector",
                message = "Dispositivo inacessivel em $cleanHost:$port: ${ex.message}",
                context = LogContext(deviceId = localIdentity.deviceId),
                throwable = ex,
            )
            return ManualConnectResult.Failed(
                errorCode = FsapErrorCode.DEVICE_NOT_FOUND,
                message = "Nao foi possivel conectar ao dispositivo em $cleanHost:$port.",
                cause = ex,
            )
        }

        return handleIdentifyResponse(response, cleanHost, port)
    }

    private suspend fun handleIdentifyResponse(
        response: HttpResponse,
        cleanHost: String,
        port: Int,
    ): ManualConnectResult {
        val rawBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return mapErrorHttpResponse(response.status, rawBody, cleanHost, port)
        }

        val rawEnvelope = try {
            FsapJson.decodeRawEnvelope(rawBody)
        } catch (ex: SerializationException) {
            return ManualConnectResult.Failed(
                errorCode = FsapErrorCode.DEVICE_NOT_FOUND,
                message = "O servico em $cleanHost:$port nao retornou um envelope JSON FSAP valido.",
                cause = ex,
            )
        }

        if (rawEnvelope.protocol != FsapConstants.PROTOCOL_NAME) {
            return ManualConnectResult.Failed(
                errorCode = FsapErrorCode.DEVICE_NOT_FOUND,
                message = "Resposta de $cleanHost:$port nao pertence ao protocolo FSAP.",
            )
        }

        val versionCheck = FsapMessageValidator.validateProtocolVersion(rawEnvelope.version)
        if (versionCheck is ValidationResult.Invalid) {
            return ManualConnectResult.Failed(
                errorCode = versionCheck.errorCode,
                message = versionCheck.reason,
            )
        }

        val envelope = try {
            FsapJson.decodeEnvelope<IdentifyPayload>(rawBody)
        } catch (ex: SerializationException) {
            return ManualConnectResult.Failed(
                errorCode = FsapErrorCode.MALFORMED_MESSAGE,
                message = "Payload IDENTIFY invalido recebido de $cleanHost:$port.",
                cause = ex,
            )
        }

        val validation = FsapMessageValidator.validateIdentify(envelope)
        if (validation is ValidationResult.Invalid) {
            return ManualConnectResult.Failed(
                errorCode = validation.errorCode,
                message = validation.reason,
            )
        }

        val remoteIdentity = DeviceIdentity(
            deviceId = envelope.senderId,
            alias = envelope.payload.alias,
            deviceType = envelope.payload.deviceType,
            platform = envelope.payload.platform,
            protocolVersion = envelope.version,
            httpPort = port,
            capabilities = envelope.payload.capabilities,
        )
        val registeredPeer = peerRegistry.registerManualPeer(
            identity = remoteIdentity,
            ipAddress = cleanHost,
            port = port,
        )

        logger?.info(
            component = "ManualPeerConnector",
            message = "Conexao manual concluida com '${registeredPeer.alias}' em $cleanHost:$port.",
            context = LogContext(deviceId = localIdentity.deviceId, peerId = registeredPeer.deviceId),
        )
        return ManualConnectResult.Connected(registeredPeer)
    }

    private fun mapErrorHttpResponse(
        status: HttpStatusCode,
        rawBody: String,
        cleanHost: String,
        port: Int,
    ): ManualConnectResult {
        val parsedError = runCatching {
            FsapJson.decodeEnvelope<ErrorPayload>(rawBody)
        }.getOrNull()

        if (parsedError != null) {
            val mappedCode = FsapErrorCode.fromCode(parsedError.payload.errorCode)
            if (mappedCode != null) {
                return ManualConnectResult.Failed(
                    errorCode = mappedCode,
                    message = parsedError.payload.message,
                )
            }
        }

        if (status.value == FsapErrorCode.UNSUPPORTED_VERSION.httpStatus) {
            return ManualConnectResult.Failed(
                errorCode = FsapErrorCode.UNSUPPORTED_VERSION,
                message = "Versao FSAP incompatível reportada por $cleanHost:$port (HTTP 505).",
            )
        }

        return ManualConnectResult.Failed(
            errorCode = FsapErrorCode.DEVICE_NOT_FOUND,
            message = "Falha no handshake IDENTIFY com $cleanHost:$port (HTTP ${status.value}).",
        )
    }

    fun close() {
        if (ownsHttpClient) {
            httpClient.close()
        }
    }

    companion object {
        fun createDefaultHttpClient(connectTimeoutMs: Long = FsapConstants.T_TCP_CONNECT_TIMEOUT_MS): HttpClient =
            HttpClient(CIO) {
                install(HttpTimeout) {
                    connectTimeoutMillis = connectTimeoutMs
                    requestTimeoutMillis = connectTimeoutMs
                    socketTimeoutMillis = connectTimeoutMs
                }
            }

        internal fun isValidTargetHost(host: String): Boolean {
            if (host.isBlank() || host.length > 253) return false
            return !host.any { it.isWhitespace() || it in setOf('/', '\\', '?', '#', '@') }
        }

        internal fun formatHostForUrl(host: String): String =
            if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
    }
}


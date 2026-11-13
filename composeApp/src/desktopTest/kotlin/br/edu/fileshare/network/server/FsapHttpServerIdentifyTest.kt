package br.edu.fileshare.network.server

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.desktop.server.detectDesktopPlatform
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.ErrorPayload
import br.edu.fileshare.protocol.model.FsapEnvelope
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapHeaders
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.IdentifyPayload
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.validation.FsapMessageValidator
import br.edu.fileshare.protocol.validation.ValidationResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FsapHttpServerIdentifyTest {

    private companion object {
        val SERVER_IDENTITY = DeviceIdentity(
            deviceId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            alias = "Servidor Lab Redes",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
            httpPort = 53317,
        )
        const val CLIENT_UUID = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    }

    @Test
    fun `GET info retorna envelope IDENTIFY valido com cabecalhos FSAP`() = testApplication {
        val logger = InMemoryFsapLogger()
        application {
            configureFsapServer(
                config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                logger = logger,
            )
        }

        val response = client.get(FsapConstants.Routes.INFO) {
            header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(FsapConstants.PROTOCOL_VERSION, response.headers[FsapHeaders.VERSION])
        assertEquals(FsapConstants.PROTOCOL_NAME, response.headers[FsapHeaders.PROTOCOL])

        val decoded = FsapJson.decodeEnvelope<IdentifyPayload>(response.bodyAsText())
        assertIs<ValidationResult.Valid>(FsapMessageValidator.validateIdentify(decoded))
        assertEquals(SERVER_IDENTITY.deviceId, decoded.senderId)
        assertEquals(SERVER_IDENTITY.alias, decoded.payload.alias)
        assertEquals(DeviceType.SERVER, decoded.payload.deviceType)
    }

    @Test
    fun `POST identify retorna identidade do servidor e registra handshake`() = testApplication {
        val logger = InMemoryFsapLogger()
        application {
            configureFsapServer(
                config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                logger = logger,
            )
        }

        val requestEnvelope = FsapEnvelopeFactory.create(
            type = FsapMessageType.IDENTIFY,
            senderId = CLIENT_UUID,
            payload = IdentifyPayload(
                alias = "Pixel 8 Pro",
                deviceType = DeviceType.MOBILE,
                platform = PlatformType.ANDROID,
                httpPort = 53317,
            ),
        )

        val response = client.post(FsapConstants.Routes.IDENTIFY) {
            header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
            contentType(ContentType.Application.Json)
            setBody(FsapJson.encodeEnvelope(requestEnvelope))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val decoded = FsapJson.decodeEnvelope<IdentifyPayload>(response.bodyAsText())
        assertIs<ValidationResult.Valid>(FsapMessageValidator.validateIdentify(decoded))
        assertEquals(SERVER_IDENTITY.alias, decoded.payload.alias)
        assertTrue(logger.snapshot().any { it.message.contains("Pixel 8 Pro") && it.context.peerId == CLIENT_UUID })
    }

    @Test
    fun `POST identify rejeita versao MAJOR incompativel com 505 ERROR`() = testApplication {
        val logger = InMemoryFsapLogger()
        application {
            configureFsapServer(
                config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                logger = logger,
            )
        }

        val v2Envelope = FsapEnvelope(
            protocol = FsapConstants.PROTOCOL_NAME,
            version = "2.0",
            type = FsapMessageType.IDENTIFY.name,
            messageId = PlatformUuidGenerator.nextUuidV4(),
            timestamp = "2026-11-12T23:00:00Z",
            senderId = CLIENT_UUID,
            payload = IdentifyPayload(
                alias = "Cliente Futuro",
                deviceType = DeviceType.DESKTOP,
                platform = PlatformType.WINDOWS,
                httpPort = 53317,
            ),
        )

        val responseByBody = client.post(FsapConstants.Routes.IDENTIFY) {
            header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
            contentType(ContentType.Application.Json)
            setBody(FsapJson.encodeEnvelope(v2Envelope))
        }
        assertEquals(HttpStatusCode.fromValue(505), responseByBody.status)
        val errBody = FsapJson.decodeEnvelope<ErrorPayload>(responseByBody.bodyAsText())
        assertEquals(FsapErrorCode.UNSUPPORTED_VERSION.code, errBody.payload.errorCode)

        val responseByHeader = client.get(FsapConstants.Routes.INFO) {
            header(FsapHeaders.VERSION, "3.0")
        }
        assertEquals(HttpStatusCode.fromValue(505), responseByHeader.status)
        val errHeader = FsapJson.decodeEnvelope<ErrorPayload>(responseByHeader.bodyAsText())
        assertEquals(FsapErrorCode.UNSUPPORTED_VERSION.code, errHeader.payload.errorCode)
    }

    @Test
    fun `POST identify rejeita JSON malformado e corpo maior que limite configurado com envelope ERROR`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            application {
                configureFsapServer(
                    config = FsapHttpServerConfig(
                        identity = SERVER_IDENTITY,
                        maxJsonBodyBytes = 256,
                    ),
                    logger = logger,
                    extraRoutes = {
                        get("/fsap/v1/test-boom") {
                            error("Falha simulada")
                        }
                    },
                )
            }

            val badJsonResp = client.post(FsapConstants.Routes.IDENTIFY) {
                contentType(ContentType.Application.Json)
                setBody("{json-quebrado")
            }
            assertEquals(HttpStatusCode.BadRequest, badJsonResp.status)
            val errJson = FsapJson.decodeEnvelope<ErrorPayload>(badJsonResp.bodyAsText())
            assertEquals(FsapErrorCode.MALFORMED_MESSAGE.code, errJson.payload.errorCode)

            val hugeBodyResp = client.post(FsapConstants.Routes.IDENTIFY) {
                contentType(ContentType.Application.Json)
                setBody("{\"padding\":\"${"x".repeat(400)}\"}")
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, hugeBodyResp.status)
            val errTooLarge = FsapJson.decodeEnvelope<ErrorPayload>(hugeBodyResp.bodyAsText())
            assertEquals(FsapErrorCode.FILE_TOO_LARGE.code, errTooLarge.payload.errorCode)

            val internalErrResp = client.get("/fsap/v1/test-boom")
            assertEquals(HttpStatusCode.InternalServerError, internalErrResp.status)
            val errInternal = FsapJson.decodeEnvelope<ErrorPayload>(internalErrResp.bodyAsText())
            assertEquals(FsapErrorCode.INTERNAL_ERROR.code, errInternal.payload.errorCode)
        }

    @Test
    fun `FsapHttpServer CIO real faz bind em porta efemera, responde identify e encerra graciosamente`() =
        runBlocking {
            val logger = InMemoryFsapLogger()
            val server = FsapHttpServer(
                config = FsapHttpServerConfig(
                    identity = SERVER_IDENTITY.copy(httpPort = 0),
                    host = "127.0.0.1",
                    port = 0,
                ),
                logger = logger,
            )

            val actualPort = server.start()
            assertTrue(actualPort > 0)
            assertTrue(server.isRunning())

            val httpClient = HttpClient(CIO)
            try {
                val infoResponse = httpClient.get("http://127.0.0.1:$actualPort${FsapConstants.Routes.INFO}")
                assertEquals(HttpStatusCode.OK, infoResponse.status)
                val decoded = FsapJson.decodeEnvelope<IdentifyPayload>(infoResponse.bodyAsText())
                assertEquals(actualPort, decoded.payload.httpPort)
                assertEquals(SERVER_IDENTITY.alias, decoded.payload.alias)
            } finally {
                httpClient.close()
                server.stop()
            }

            assertFalse(server.isRunning())
        }

    @Test
    fun `detectDesktopPlatform identifica Windows e Linux corretamente`() {
        assertEquals(PlatformType.WINDOWS, detectDesktopPlatform("Windows 11"))
        assertEquals(PlatformType.LINUX, detectDesktopPlatform("Linux"))
    }
}


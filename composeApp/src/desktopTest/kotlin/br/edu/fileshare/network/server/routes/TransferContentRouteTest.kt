package br.edu.fileshare.network.server.routes

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.configureFsapServer
import br.edu.fileshare.network.streaming.IncrementalSha256Channel
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.ErrorPayload
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapHeaders
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.model.TransferAcceptPayload
import br.edu.fileshare.protocol.model.TransferFileStatusPayload
import br.edu.fileshare.protocol.model.TransferRequestPayload
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.InMemoryFileSink
import br.edu.fileshare.storage.InMemoryFileSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferContentRouteTest {

    private companion object {
        val SERVER_IDENTITY = DeviceIdentity(
            deviceId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            alias = "Servidor Receptor",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
        )
        const val SENDER_UUID = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    }

    @Test
    fun `TC-FSAP-014 e TC-FSAP-013 validam token, recebem arquivo multi-MB e gravam com nao-sobrescrita`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(logger = logger)
            lateinit var manager: ServerTransferSessionManager

            application {
                manager = ServerTransferSessionManager(
                    tokenManager = tokens,
                    fileSink = sink,
                    scope = this,
                    logger = logger,
                )
                configureFsapServer(
                    config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                    logger = logger,
                    transferServices = FsapTransferServerServices(
                        sessionManager = manager,
                        fileSink = sink,
                        approvalHandler = AutoAcceptTransferApprovalHandler,
                    ),
                )
            }

            val payloadBytes = ByteArray(1_500_000) { idx -> ((idx * 31) % 251).toByte() }
            val expectedSha = IncrementalSha256Channel.computeDigest(
                InMemoryFileSource("video_aula.bin", payloadBytes),
            ).sha256Hex

            val transferId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
            val reqEnv = FsapEnvelopeFactory.create(
                type = FsapMessageType.TRANSFER_REQUEST,
                senderId = SENDER_UUID,
                payload = TransferRequestPayload(
                    transferId = transferId,
                    senderAlias = "Notebook",
                    senderDeviceType = DeviceType.DESKTOP,
                    totalBytes = payloadBytes.size.toLong(),
                    files = listOf(
                        FileItemDto(
                            fileId = "file-1",
                            fileName = "video_aula.bin",
                            sizeBytes = payloadBytes.size.toLong(),
                            sha256 = expectedSha,
                        ),
                    ),
                ),
            )

            val prepareResp = client.post(FsapConstants.Routes.TRANSFERS_PREPARE) {
                header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                contentType(ContentType.Application.Json)
                setBody(FsapJson.encodeEnvelope(reqEnv))
            }
            assertEquals(HttpStatusCode.OK, prepareResp.status)
            val accept = FsapJson.decodeEnvelope<TransferAcceptPayload>(prepareResp.bodyAsText())

            // TC-FSAP-013: tentativa sem token -> 401 sem criar .part
            val unauthResp = client.put(FsapConstants.Routes.transferContent(transferId, "file-1")) {
                header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                header(FsapHeaders.FILE_SHA256, expectedSha)
                contentType(ContentType.Application.OctetStream)
                setBody(payloadBytes)
            }
            assertEquals(HttpStatusCode.Unauthorized, unauthResp.status)
            val unauthErr = FsapJson.decodeEnvelope<ErrorPayload>(unauthResp.bodyAsText())
            assertEquals(FsapErrorCode.UNAUTHORIZED_TRANSFER.code, unauthErr.payload.errorCode)
            assertTrue(sink.stagedPartKeys().isEmpty())

            // Divergencia de Content-Length antes do stream -> 400 SIZE_MISMATCH sem criar .part
            val badLenResp = client.put(FsapConstants.Routes.transferContent(transferId, "file-1")) {
                header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                header(FsapHeaders.TRANSFER_TOKEN, accept.payload.transferToken)
                header(FsapHeaders.FILE_SHA256, expectedSha)
                contentType(ContentType.Application.OctetStream)
                setBody(payloadBytes.copyOf(10))
            }
            assertEquals(HttpStatusCode.BadRequest, badLenResp.status)
            val badLenErr = FsapJson.decodeEnvelope<ErrorPayload>(badLenResp.bodyAsText())
            assertEquals(FsapErrorCode.SIZE_MISMATCH.code, badLenErr.payload.errorCode)
            assertTrue(sink.stagedPartKeys().isEmpty())

            // Envio valido -> 200 OK com fileVerified = true
            val okResp = client.put(FsapConstants.Routes.transferContent(transferId, "file-1")) {
                header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                header(FsapHeaders.SENDER_ID, SENDER_UUID)
                header(FsapHeaders.TRANSFER_TOKEN, accept.payload.transferToken)
                header(FsapHeaders.FILE_SHA256, expectedSha)
                contentType(ContentType.Application.OctetStream)
                setBody(payloadBytes)
            }
            assertEquals(HttpStatusCode.OK, okResp.status)
            val statusEnv = FsapJson.decodeEnvelope<TransferFileStatusPayload>(okResp.bodyAsText())
            assertEquals(FsapMessageType.TRANSFER_STATUS.name, statusEnv.type)
            assertTrue(statusEnv.payload.fileVerified)
            assertEquals(payloadBytes.size.toLong(), statusEnv.payload.bytesReceived)
            assertEquals(expectedSha, statusEnv.payload.calculatedSha256)
            assertTrue(sink.stagedPartKeys().isEmpty())
            assertContentEquals(payloadBytes, sink.readCommittedBytes("video_aula.bin"))
        }

    @Test
    fun `TC-FSAP-019 corrupcao de 1 bit retorna 422 HASH_MISMATCH e deleta part sem expor arquivo final`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(logger = logger)
            lateinit var manager: ServerTransferSessionManager

            application {
                manager = ServerTransferSessionManager(
                    tokenManager = tokens,
                    fileSink = sink,
                    scope = this,
                    logger = logger,
                )
                configureFsapServer(
                    config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                    logger = logger,
                    transferServices = FsapTransferServerServices(
                        sessionManager = manager,
                        fileSink = sink,
                        approvalHandler = AutoAcceptTransferApprovalHandler,
                    ),
                )
            }

            val original = ByteArray(100_000) { idx -> (idx % 199).toByte() }
            val originalSha = IncrementalSha256Channel.computeDigest(
                InMemoryFileSource("relatorio.dat", original),
            ).sha256Hex
            val corrupted = original.copyOf().apply { this[50_000] = (this[50_000].toInt() xor 0x01).toByte() }

            val transferId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
            val reqEnv = FsapEnvelopeFactory.create(
                type = FsapMessageType.TRANSFER_REQUEST,
                senderId = SENDER_UUID,
                payload = TransferRequestPayload(
                    transferId = transferId,
                    senderAlias = "Sender",
                    senderDeviceType = DeviceType.DESKTOP,
                    totalBytes = original.size.toLong(),
                    files = listOf(FileItemDto("f1", "relatorio.dat", original.size.toLong(), originalSha)),
                ),
            )

            val prepareResp = client.post(FsapConstants.Routes.TRANSFERS_PREPARE) {
                header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                contentType(ContentType.Application.Json)
                setBody(FsapJson.encodeEnvelope(reqEnv))
            }
            val token = FsapJson.decodeEnvelope<TransferAcceptPayload>(prepareResp.bodyAsText()).payload.transferToken

            val putResp = client.put(FsapConstants.Routes.transferContent(transferId, "f1")) {
                header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                header(FsapHeaders.TRANSFER_TOKEN, token)
                header(FsapHeaders.FILE_SHA256, originalSha)
                contentType(ContentType.Application.OctetStream)
                setBody(corrupted)
            }

            assertEquals(HttpStatusCode.UnprocessableEntity, putResp.status)
            val err = FsapJson.decodeEnvelope<ErrorPayload>(putResp.bodyAsText())
            assertEquals(FsapErrorCode.HASH_MISMATCH.code, err.payload.errorCode)
            assertTrue(sink.stagedPartKeys().isEmpty())
            assertTrue(sink.committedFileNames().isEmpty())
            assertEquals(TransferState.FAILED, manager.findSnapshot(transferId)?.state)
            assertEquals(0, tokens.activeCount())
        }

    @Test
    fun `TC-FSAP-021 estolagem de stream por TCP real excedendo timeout retorna 408 STREAM_TIMEOUT e apaga part`() =
        runBlocking {
            val logger = InMemoryFsapLogger()
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(logger = logger)
            val sessionManager = ServerTransferSessionManager(
                tokenManager = tokens,
                fileSink = sink,
                scope = this,
                logger = logger,
            )
            val server = FsapHttpServer(
                config = FsapHttpServerConfig(
                    identity = SERVER_IDENTITY.copy(httpPort = 0),
                    host = "127.0.0.1",
                    port = 0,
                ),
                logger = logger,
                transferServices = FsapTransferServerServices(
                    sessionManager = sessionManager,
                    fileSink = sink,
                    approvalHandler = AutoAcceptTransferApprovalHandler,
                    streamReadTimeoutMs = 200L,
                ),
            )

            val port = server.start()
            val httpClient = HttpClient(CIO)
            val transferId = "11111111-2222-4333-8444-555555555555"
            val sampleBytes = "abcdefghij".encodeToByteArray()
            val sampleSha = IncrementalSha256Channel.computeDigest(
                InMemoryFileSource("parcial.txt", sampleBytes),
            ).sha256Hex

            try {
                val prepareResp = httpClient.post("http://127.0.0.1:$port${FsapConstants.Routes.TRANSFERS_PREPARE}") {
                    header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                    contentType(ContentType.Application.Json)
                    setBody(
                        FsapJson.encodeEnvelope(
                            FsapEnvelopeFactory.create(
                                type = FsapMessageType.TRANSFER_REQUEST,
                                senderId = SENDER_UUID,
                                payload = TransferRequestPayload(
                                    transferId = transferId,
                                    senderAlias = "Sender TCP",
                                    senderDeviceType = DeviceType.DESKTOP,
                                    totalBytes = sampleBytes.size.toLong(),
                                    files = listOf(
                                        FileItemDto("f1", "parcial.txt", sampleBytes.size.toLong(), sampleSha),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
                val token = FsapJson.decodeEnvelope<TransferAcceptPayload>(prepareResp.bodyAsText()).payload.transferToken

                // Abre socket TCP cru, envia metade dos bytes declarados e silencia ate exceder 200ms
                Socket("127.0.0.1", port).use { rawSocket ->
                    rawSocket.soTimeout = 3_000
                    val out = rawSocket.getOutputStream()
                    val headers = buildString {
                        append("PUT ").append(FsapConstants.Routes.transferContent(transferId, "f1")).append(" HTTP/1.1\r\n")
                        append("Host: 127.0.0.1:").append(port).append("\r\n")
                        append("Content-Type: application/octet-stream\r\n")
                        append("Content-Length: ").append(sampleBytes.size).append("\r\n")
                        append(FsapHeaders.VERSION).append(": ").append(FsapConstants.PROTOCOL_VERSION).append("\r\n")
                        append(FsapHeaders.TRANSFER_TOKEN).append(": ").append(token).append("\r\n")
                        append(FsapHeaders.FILE_SHA256).append(": ").append(sampleSha).append("\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(headers.encodeToByteArray())
                    out.write(sampleBytes, 0, 4)
                    out.flush()

                    val input = rawSocket.getInputStream()
                    val headerBuf = StringBuilder()
                    var prev4 = 0
                    while (true) {
                        val b = input.read()
                        check(b != -1) { "EOF prematuro antes de headers HTTP: $headerBuf" }
                        headerBuf.append(b.toChar())
                        prev4 = ((prev4 shl 8) or (b and 0xFF))
                        if (prev4 == 0x0D0A0D0A) break
                    }
                    val rawHeaders = headerBuf.toString()
                    val respContentLength = Regex("(?im)^Content-Length:\\s*(\\d+)\\r?$")
                        .find(rawHeaders)?.groupValues?.get(1)?.toInt() ?: 0
                    val bodyBytes = if (respContentLength > 0) {
                        val arr = ByteArray(respContentLength)
                        var off = 0
                        while (off < respContentLength) {
                            val r = input.read(arr, off, respContentLength - off)
                            check(r > 0) { "EOF no corpo HTTP ($off/$respContentLength)" }
                            off += r
                        }
                        arr.decodeToString()
                    } else {
                        ""
                    }
                    val rawResponse = rawHeaders + bodyBytes
                    assertTrue(rawResponse.contains("408"), "Esperava 408 em: $rawResponse")
                    assertTrue(rawResponse.contains(FsapErrorCode.STREAM_TIMEOUT.code), "Esperava STREAM_TIMEOUT em: $rawResponse")
                }

                assertTrue(sink.stagedPartKeys().isEmpty())
                assertTrue(sink.committedFileNames().isEmpty())
                assertEquals(TransferState.FAILED, sessionManager.findSnapshot(transferId)?.state)
            } finally {
                httpClient.close()
                sessionManager.close()
                server.stop()
            }
        }
}

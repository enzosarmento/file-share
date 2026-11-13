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
import br.edu.fileshare.protocol.model.CancelledBy
import br.edu.fileshare.protocol.model.CompletedFileSummary
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
import br.edu.fileshare.protocol.model.TransferCancelPayload
import br.edu.fileshare.protocol.model.TransferCompletePayload
import br.edu.fileshare.protocol.model.TransferRequestPayload
import br.edu.fileshare.protocol.model.TransferStatusPayload
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.InMemoryFileSink
import br.edu.fileshare.storage.InMemoryFileSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferControlRouteTest {

    private companion object {
        val SERVER_IDENTITY = DeviceIdentity(
            deviceId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            alias = "Servidor Controle",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
        )
        const val SENDER_UUID = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    }

    @Test
    fun `TC-FSAP-015 e TC-FSAP-016 lote multi-arquivos consulta status progressivo e conclui apos complete`() =
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

            val f1 = ByteArray(1024) { 1 }
            val f2 = ByteArray(2048) { 2 }
            val f3 = ByteArray(4096) { 3 }
            val sha1 = IncrementalSha256Channel.computeDigest(InMemoryFileSource("a.bin", f1)).sha256Hex
            val sha2 = IncrementalSha256Channel.computeDigest(InMemoryFileSource("b.bin", f2)).sha256Hex
            val sha3 = IncrementalSha256Channel.computeDigest(InMemoryFileSource("c.bin", f3)).sha256Hex

            val transferId = "7d2b5b8c-1f4e-4d9a-9c3a-112233445566"
            val prepareEnv = FsapEnvelopeFactory.create(
                type = FsapMessageType.TRANSFER_REQUEST,
                senderId = SENDER_UUID,
                payload = TransferRequestPayload(
                    transferId = transferId,
                    senderAlias = "Notebook Cliente",
                    senderDeviceType = DeviceType.DESKTOP,
                    totalBytes = (f1.size + f2.size + f3.size).toLong(),
                    files = listOf(
                        FileItemDto("f1", "a.bin", f1.size.toLong(), sha1),
                        FileItemDto("f2", "b.bin", f2.size.toLong(), sha2),
                        FileItemDto("f3", "c.bin", f3.size.toLong(), sha3),
                    ),
                ),
            )

            val prepareResp = client.post(FsapConstants.Routes.TRANSFERS_PREPARE) {
                header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                contentType(ContentType.Application.Json)
                setBody(FsapJson.encodeEnvelope(prepareEnv))
            }
            assertEquals(HttpStatusCode.OK, prepareResp.status)
            val token = FsapJson.decodeEnvelope<TransferAcceptPayload>(prepareResp.bodyAsText()).payload.transferToken

            // GET /status sem token ativo deve retornar 401
            val unauthorizedStatus = client.get(FsapConstants.Routes.transferStatus(transferId))
            assertEquals(HttpStatusCode.Unauthorized, unauthorizedStatus.status)

            // Upload arquivo 1
            assertEquals(
                HttpStatusCode.OK,
                client.put(FsapConstants.Routes.transferContent(transferId, "f1")) {
                    header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                    header(FsapHeaders.TRANSFER_TOKEN, token)
                    header(FsapHeaders.FILE_SHA256, sha1)
                    contentType(ContentType.Application.OctetStream)
                    setBody(f1)
                }.status,
            )

            val statusAfter1 = FsapJson.decodeEnvelope<TransferStatusPayload>(
                client.get(FsapConstants.Routes.transferStatus(transferId)) {
                    header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                    header(FsapHeaders.TRANSFER_TOKEN, token)
                }.bodyAsText(),
            ).payload
            assertEquals(TransferState.TRANSFERRING.name, statusAfter1.state)
            assertEquals(1024L, statusAfter1.totalBytesTransferred)
            assertEquals(1, statusAfter1.filesCompleted)
            assertEquals(3, statusAfter1.totalFiles)

            // Upload arquivo 2
            assertEquals(
                HttpStatusCode.OK,
                client.put(FsapConstants.Routes.transferContent(transferId, "f2")) {
                    header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                    header(FsapHeaders.TRANSFER_TOKEN, token)
                    header(FsapHeaders.FILE_SHA256, sha2)
                    contentType(ContentType.Application.OctetStream)
                    setBody(f2)
                }.status,
            )

            val statusAfter2 = FsapJson.decodeEnvelope<TransferStatusPayload>(
                client.get(FsapConstants.Routes.transferStatus(transferId)) {
                    header(FsapHeaders.TRANSFER_TOKEN, token)
                }.bodyAsText(),
            ).payload
            assertEquals(TransferState.TRANSFERRING.name, statusAfter2.state)
            assertEquals(3072L, statusAfter2.totalBytesTransferred)
            assertEquals(2, statusAfter2.filesCompleted)

            // Upload arquivo 3 -> sessao entra em VERIFYING (ainda nao COMPLETED ate /complete)
            assertEquals(
                HttpStatusCode.OK,
                client.put(FsapConstants.Routes.transferContent(transferId, "f3")) {
                    header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                    header(FsapHeaders.TRANSFER_TOKEN, token)
                    header(FsapHeaders.FILE_SHA256, sha3)
                    contentType(ContentType.Application.OctetStream)
                    setBody(f3)
                }.status,
            )
            assertEquals(TransferState.VERIFYING, manager.findSnapshot(transferId)?.state)

            // POST /complete finaliza lote para COMPLETED
            val completeEnv = FsapEnvelopeFactory.create(
                type = FsapMessageType.TRANSFER_COMPLETE,
                senderId = SENDER_UUID,
                payload = TransferCompletePayload(
                    transferId = transferId,
                    completedFiles = listOf(
                        CompletedFileSummary("f1", sha1),
                        CompletedFileSummary("f2", sha2),
                        CompletedFileSummary("f3", sha3),
                    ),
                ),
            )
            val completeResp = client.post(FsapConstants.Routes.transferComplete(transferId)) {
                header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                header(FsapHeaders.TRANSFER_TOKEN, token)
                contentType(ContentType.Application.Json)
                setBody(FsapJson.encodeEnvelope(completeEnv))
            }
            assertEquals(HttpStatusCode.OK, completeResp.status)
            val confirmed = FsapJson.decodeEnvelope<TransferCompletePayload>(completeResp.bodyAsText()).payload
            assertEquals(TransferState.COMPLETED.name, confirmed.state)
            assertEquals(3, confirmed.completedFiles.size)
            assertEquals(TransferState.COMPLETED, manager.findSnapshot(transferId)?.state)
            assertEquals(0, tokens.activeCount())

            // GET /status idempotente mesmo apos conclusao com o token da sessao
            val finalStatusResp = client.get(FsapConstants.Routes.transferStatus(transferId)) {
                header(FsapHeaders.TRANSFER_TOKEN, token)
            }
            assertEquals(HttpStatusCode.OK, finalStatusResp.status)
            val finalStatus = FsapJson.decodeEnvelope<TransferStatusPayload>(finalStatusResp.bodyAsText()).payload
            assertEquals(TransferState.COMPLETED.name, finalStatus.state)
            assertEquals(7168L, finalStatus.totalBytesTransferred)
            assertContentEquals(f1, sink.readCommittedBytes("a.bin"))
            assertContentEquals(f2, sink.readCommittedBytes("b.bin"))
            assertContentEquals(f3, sink.readCommittedBytes("c.bin"))
        }

    @Test
    fun `TC-FSAP-023 rejeita POST complete precoce em estado ACCEPTED com 409 INVALID_STATE_TRANSITION`() =
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

            val bytes = "dados-pendentes".encodeToByteArray()
            val sha = IncrementalSha256Channel.computeDigest(InMemoryFileSource("doc.txt", bytes)).sha256Hex
            val transferId = "88888888-1111-4222-8333-444444444444"

            val prepareResp = client.post(FsapConstants.Routes.TRANSFERS_PREPARE) {
                header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                contentType(ContentType.Application.Json)
                setBody(
                    FsapJson.encodeEnvelope(
                        FsapEnvelopeFactory.create(
                            type = FsapMessageType.TRANSFER_REQUEST,
                            senderId = SENDER_UUID,
                            payload = TransferRequestPayload(
                                transferId = transferId,
                                senderAlias = "Cliente",
                                senderDeviceType = DeviceType.MOBILE,
                                totalBytes = bytes.size.toLong(),
                                files = listOf(FileItemDto("f1", "doc.txt", bytes.size.toLong(), sha)),
                            ),
                        ),
                    ),
                )
            }
            val token = FsapJson.decodeEnvelope<TransferAcceptPayload>(prepareResp.bodyAsText()).payload.transferToken

            val prematureComplete = client.post(FsapConstants.Routes.transferComplete(transferId)) {
                header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                header(FsapHeaders.TRANSFER_TOKEN, token)
                contentType(ContentType.Application.Json)
                setBody(
                    FsapJson.encodeEnvelope(
                        FsapEnvelopeFactory.create(
                            type = FsapMessageType.TRANSFER_COMPLETE,
                            senderId = SENDER_UUID,
                            payload = TransferCompletePayload(
                                transferId = transferId,
                                completedFiles = listOf(CompletedFileSummary("f1", sha)),
                            ),
                        ),
                    ),
                )
            }

            assertEquals(HttpStatusCode.Conflict, prematureComplete.status)
            val err = FsapJson.decodeEnvelope<ErrorPayload>(prematureComplete.bodyAsText())
            assertEquals(FsapErrorCode.INVALID_STATE_TRANSITION.code, err.payload.errorCode)
            assertEquals(TransferState.ACCEPTED, manager.findSnapshot(transferId)?.state)
            assertTrue(sink.committedFileNames().isEmpty())
        }

    @Test
    fun `TC-FSAP-017 e TC-FSAP-018 cancelamento por SENDER e por RECEIVER abortam upload em curso e removem part`() =
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
                    streamReadTimeoutMs = 10_000L,
                ),
            )
            val port = server.start()
            val httpClient = HttpClient(CIO)

            try {
                // Caso TC-FSAP-017: SENDER cancela mid-stream via POST /cancel
                val transferSenderCancel = "aaaaaaaa-1111-4111-8111-111111111111"
                val content = ByteArray(100_000) { 0x5A }
                val sha = IncrementalSha256Channel.computeDigest(InMemoryFileSource("grande.bin", content)).sha256Hex
                val token1 = prepareViaHttp(httpClient, port, transferSenderCancel, "grande.bin", content.size.toLong(), sha)

                Socket("127.0.0.1", port).use { rawSocket ->
                    sendPartialContentPut(rawSocket, port, transferSenderCancel, "f1", token1, sha, content)

                    withTimeout(3_000) {
                        while (sessionManager.findSnapshot(transferSenderCancel)?.state != TransferState.TRANSFERRING ||
                            sink.stagedPartKeys().isEmpty()
                        ) {
                            delay(10)
                        }
                    }

                    val cancelResp = httpClient.post("http://127.0.0.1:$port${FsapConstants.Routes.transferCancel(transferSenderCancel)}") {
                        header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                        header(FsapHeaders.TRANSFER_TOKEN, token1)
                        contentType(ContentType.Application.Json)
                        setBody(
                            FsapJson.encodeEnvelope(
                                FsapEnvelopeFactory.create(
                                    type = FsapMessageType.TRANSFER_CANCEL,
                                    senderId = SENDER_UUID,
                                    payload = TransferCancelPayload(
                                        transferId = transferSenderCancel,
                                        cancelledBy = CancelledBy.SENDER,
                                        reason = "USER_REQUESTED",
                                    ),
                                ),
                            ),
                        )
                    }
                    assertEquals(HttpStatusCode.OK, cancelResp.status)
                    val cancelledStatus = FsapJson.decodeEnvelope<TransferStatusPayload>(cancelResp.bodyAsText()).payload
                    assertEquals(TransferState.CANCELLED.name, cancelledStatus.state)
                }

                withTimeout(3_000) {
                    while (sink.stagedPartKeys().isNotEmpty()) {
                        delay(10)
                    }
                }
                assertEquals(TransferState.CANCELLED, sessionManager.findSnapshot(transferSenderCancel)?.state)
                assertEquals(0, tokens.activeCount())
                assertTrue(sink.committedFileNames().isEmpty())

                // Caso TC-FSAP-018: RECEIVER cancela mid-stream via gerenciador local
                val transferReceiverCancel = "bbbbbbbb-2222-4222-8222-222222222222"
                val token2 = prepareViaHttp(httpClient, port, transferReceiverCancel, "video.mkv", content.size.toLong(), sha)

                Socket("127.0.0.1", port).use { rawSocket ->
                    sendPartialContentPut(rawSocket, port, transferReceiverCancel, "f1", token2, sha, content)

                    withTimeout(3_000) {
                        while (sessionManager.findSnapshot(transferReceiverCancel)?.state != TransferState.TRANSFERRING ||
                            sink.stagedPartKeys().isEmpty()
                        ) {
                            delay(10)
                        }
                    }

                    sessionManager.cancelTransfer(
                        transferId = transferReceiverCancel,
                        cancelledBy = CancelledBy.RECEIVER,
                        reason = "USER_ABORTED_AT_RECEIVER",
                    )
                }

                withTimeout(3_000) {
                    while (sink.stagedPartKeys().isNotEmpty()) {
                        delay(10)
                    }
                }
                assertEquals(TransferState.CANCELLED, sessionManager.findSnapshot(transferReceiverCancel)?.state)
                assertEquals(0, tokens.activeCount())
                assertTrue(sink.committedFileNames().isEmpty())

                // Consulta GET /status e nova tentativa de cancelamento apos estado terminal
                val statusAfterReceiverCancel = httpClient.get(
                    "http://127.0.0.1:$port${FsapConstants.Routes.transferStatus(transferReceiverCancel)}",
                ) {
                    header(FsapHeaders.TRANSFER_TOKEN, token2)
                }
                assertEquals(HttpStatusCode.OK, statusAfterReceiverCancel.status)
                assertEquals(
                    TransferState.CANCELLED.name,
                    FsapJson.decodeEnvelope<TransferStatusPayload>(statusAfterReceiverCancel.bodyAsText()).payload.state,
                )
            } finally {
                httpClient.close()
                sessionManager.close()
                server.stop()
            }
        }

    private suspend fun prepareViaHttp(
        httpClient: HttpClient,
        port: Int,
        transferId: String,
        fileName: String,
        sizeBytes: Long,
        sha256: String,
    ): String {
        val resp = httpClient.post("http://127.0.0.1:$port${FsapConstants.Routes.TRANSFERS_PREPARE}") {
            header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
            contentType(ContentType.Application.Json)
            setBody(
                FsapJson.encodeEnvelope(
                    FsapEnvelopeFactory.create(
                        type = FsapMessageType.TRANSFER_REQUEST,
                        senderId = SENDER_UUID,
                        payload = TransferRequestPayload(
                            transferId = transferId,
                            senderAlias = "Sender",
                            senderDeviceType = DeviceType.DESKTOP,
                            totalBytes = sizeBytes,
                            files = listOf(FileItemDto("f1", fileName, sizeBytes, sha256)),
                        ),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return FsapJson.decodeEnvelope<TransferAcceptPayload>(resp.bodyAsText()).payload.transferToken
    }

    private fun sendPartialContentPut(
        rawSocket: Socket,
        port: Int,
        transferId: String,
        fileId: String,
        token: String,
        sha256: String,
        fullContent: ByteArray,
    ) {
        val out = rawSocket.getOutputStream()
        val headers = buildString {
            append("PUT ").append(FsapConstants.Routes.transferContent(transferId, fileId)).append(" HTTP/1.1\r\n")
            append("Host: 127.0.0.1:").append(port).append("\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: ").append(fullContent.size).append("\r\n")
            append(FsapHeaders.VERSION).append(": ").append(FsapConstants.PROTOCOL_VERSION).append("\r\n")
            append(FsapHeaders.TRANSFER_TOKEN).append(": ").append(token).append("\r\n")
            append(FsapHeaders.FILE_SHA256).append(": ").append(sha256).append("\r\n\r\n")
        }
        out.write(headers.encodeToByteArray())
        out.write(fullContent, 0, fullContent.size / 2)
        out.flush()
    }
}

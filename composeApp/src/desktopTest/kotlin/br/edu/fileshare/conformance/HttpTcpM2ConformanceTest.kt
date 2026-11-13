package br.edu.fileshare.conformance

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.desktop.storage.DesktopNioFileSink
import br.edu.fileshare.desktop.storage.DesktopNioFileSource
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.client.PrepareTransferResult
import br.edu.fileshare.network.client.TransferBatchUploadResult
import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.network.discovery.ManualConnectResult
import br.edu.fileshare.network.discovery.ManualPeerConnector
import br.edu.fileshare.network.discovery.PeerOrigin
import br.edu.fileshare.network.discovery.PeerRegistry
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.InteractiveTransferApprovalCoordinator
import br.edu.fileshare.network.server.RejectAllTransferApprovalHandler
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferApprovalDecision
import br.edu.fileshare.network.server.TransferApprovalHandler
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.network.streaming.IncrementalSha256Channel
import br.edu.fileshare.network.transfer.SendFilesOutcome
import br.edu.fileshare.network.transfer.TransferCoordinator
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.CancelledBy
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.ErrorPayload
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapHeaders
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.model.RejectReasonCode
import br.edu.fileshare.protocol.model.TransferRequestPayload
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.FileReadSession
import br.edu.fileshare.storage.FileSink
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.StagedFileSession
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Suite integrada de conformidade HTTP/TCP da Milestone M2 (`TASK-TEST-002`):
 * verifica `TC-FSAP-006` a `TC-FSAP-025` sobre servidor Ktor CIO real e disco NIO (`DesktopNioFileSink`).
 */
@OptIn(ExperimentalPathApi::class)
class HttpTcpM2ConformanceTest {

    private class MutableClock(var nowMs: Long = 1_763_000_000_000L) : Clock {
        override fun epochMillis(): Long = nowMs
        override fun nowIsoUtc(): String = "2026-11-12T23:55:00Z"
    }

    private class ThrottledSecondPassFileSource(
        override val fileName: String,
        override val sizeBytes: Long,
        private val perChunkDelayMs: Long = 20L,
    ) : FileSource {
        override val mimeType: String = "application/octet-stream"
        private var opens = 0

        override suspend fun openReadSession(): FileReadSession {
            val isUploadPass = opens++ > 0
            return object : FileReadSession {
                private var remaining = sizeBytes
                override suspend fun readChunk(buffer: ByteArray): Int {
                    if (remaining <= 0L) return -1
                    if (isUploadPass) delay(perChunkDelayMs)
                    val toRead = min(buffer.size.toLong(), remaining).toInt()
                    buffer.fill(0x42, 0, toRead)
                    remaining -= toRead
                    return toRead
                }

                override suspend fun close() = Unit
            }
        }
    }

    private data class DiskServerFixture(
        val rootDir: Path,
        val sourceDir: Path,
        val downloadDir: Path,
        val clock: MutableClock,
        val logger: InMemoryFsapLogger,
        val fileSink: DesktopNioFileSink,
        val tokenManager: TransferTokenManager,
        val sessionManager: ServerTransferSessionManager,
        val server: FsapHttpServer,
        val port: Int,
        val endpoint: FsapPeerEndpoint,
        val client: FsapHttpClient,
    )

    private data class RawHttpResponse(
        val statusCode: Int,
        val body: String,
    )

    private companion object {
        val SENDER_IDENTITY = DeviceIdentity(
            deviceId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            alias = "Remetente Conformidade",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
            httpPort = 53320,
        )
        val RECEIVER_IDENTITY = DeviceIdentity(
            deviceId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            alias = "Receptor Conformidade",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
            httpPort = 0,
        )
        const val HASH_EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    private fun withDiskServer(
        approvalHandler: TransferApprovalHandler = AutoAcceptTransferApprovalHandler,
        approvalTimeoutMs: Long = FsapConstants.T_APPROVAL_TIMEOUT_MS,
        streamReadTimeoutMs: Long = FsapConstants.T_STREAM_READ_TIMEOUT_MS,
        tokenTtlMillis: Long = FsapConstants.T_TOKEN_IDLE_TTL_MS,
        maxConcurrentSessions: Int = FsapConstants.MAX_CONCURRENT_RECEIVE_SESSIONS,
        customSinkFactory: ((Path) -> FileSink)? = null,
        block: suspend (DiskServerFixture) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("fsap-m2-conf-")
        val srcDir = Files.createDirectories(root.resolve("src"))
        val dlDir = Files.createDirectories(root.resolve("downloads"))
        val clock = MutableClock()
        val logger = InMemoryFsapLogger(clock)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val realSink = DesktopNioFileSink(dlDir)
        val effectiveSink = customSinkFactory?.invoke(dlDir) ?: realSink
        val tokenManager = TransferTokenManager(
            tokenTtlMillis = tokenTtlMillis,
            clock = clock,
            logger = logger,
        )
        val sessionManager = ServerTransferSessionManager(
            tokenManager = tokenManager,
            fileSink = effectiveSink,
            scope = scope,
            logger = logger,
            clock = clock,
            maxConcurrentSessions = maxConcurrentSessions,
            approvalTimeoutMs = approvalTimeoutMs,
        )
        val server = FsapHttpServer(
            config = FsapHttpServerConfig(
                identity = RECEIVER_IDENTITY,
                host = "127.0.0.1",
                port = 0,
            ),
            logger = logger,
            clock = clock,
            transferServices = FsapTransferServerServices(
                sessionManager = sessionManager,
                fileSink = effectiveSink,
                approvalHandler = approvalHandler,
                approvalTimeoutMs = approvalTimeoutMs,
                streamReadTimeoutMs = streamReadTimeoutMs,
            ),
        )
        val port = server.start()
        val client = FsapHttpClient(localIdentity = SENDER_IDENTITY, logger = logger, clock = clock)
        try {
            block(
                DiskServerFixture(
                    rootDir = root,
                    sourceDir = srcDir,
                    downloadDir = dlDir,
                    clock = clock,
                    logger = logger,
                    fileSink = realSink,
                    tokenManager = tokenManager,
                    sessionManager = sessionManager,
                    server = server,
                    port = port,
                    endpoint = FsapPeerEndpoint("127.0.0.1", port),
                    client = client,
                ),
            )
        } finally {
            client.close()
            sessionManager.close()
            server.stop()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private fun readRawHttpResponse(socket: Socket): RawHttpResponse {
        val input = BufferedInputStream(socket.getInputStream())
        var contentLength = 0
        var statusCode = 0
        var firstLine = true

        while (true) {
            val line = readAsciiLine(input) ?: break
            if (firstLine) {
                firstLine = false
                statusCode = line.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            }
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                if (name.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }

        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(bodyBytes, offset, contentLength - offset)
            if (read < 0) break
            offset += read
        }
        return RawHttpResponse(statusCode, bodyBytes.decodeToString(0, offset))
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val out = StringBuilder()
        var b = input.read()
        if (b < 0) return null
        while (b >= 0) {
            if (b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
            b = input.read()
        }
        return out.toString()
    }

    @Test
    fun `TC-FSAP-006 e TC-FSAP-007 conexao manual IP registra par CONNECTED e trata porta inacessivel`() =
        withDiskServer { fx ->
            val registry = PeerRegistry(clock = fx.clock, logger = fx.logger)
            val connector = ManualPeerConnector(
                localIdentity = SENDER_IDENTITY,
                peerRegistry = registry,
                logger = fx.logger,
                clock = fx.clock,
            )
            try {
                // TC-FSAP-006
                val connected = assertIs<ManualConnectResult.Connected>(
                    connector.connectByManualIp("127.0.0.1", fx.port),
                )
                assertEquals(RECEIVER_IDENTITY.deviceId, connected.peer.deviceId)
                assertEquals(PeerConnectionState.CONNECTED, connected.peer.connectionState)

                // TC-FSAP-007: porta fechada em loopback
                val closedPort = ServerSocket(0).use { it.localPort }
                val failed = assertIs<ManualConnectResult.Failed>(
                    connector.connectByManualIp("127.0.0.1", closedPort),
                )
                assertEquals(FsapErrorCode.DEVICE_NOT_FOUND, failed.errorCode)
            } finally {
                connector.close()
            }
        }

    @Test
    fun `TC-FSAP-008 TC-FSAP-014 TC-FSAP-015 TC-FSAP-019 TC-FSAP-024 e TC-FSAP-025 fluxo completo em disco real`() {
        val approval = InteractiveTransferApprovalCoordinator()
        withDiskServer(approvalHandler = approval) { fx ->
            val emptyFile = Files.write(fx.sourceDir.resolve("vazio.txt"), ByteArray(0))
            val largeBytes = ByteArray(1_200_000) { idx -> ((idx * 29) % 251).toByte() }
            val largeFile = Files.write(fx.sourceDir.resolve("documento.pdf"), largeBytes)

            val prepared = fx.client.indexSourcesSequential(
                listOf(DesktopNioFileSource(emptyFile), DesktopNioFileSource(largeFile)),
            )

            // TC-FSAP-008: aprovacao explicita via coordenador
            val session = coroutineScope {
                val prepareDeferred = async {
                    fx.client.prepareTransfer(fx.endpoint, prepared.map { it.descriptor })
                }
                withTimeout(2_000L) {
                    while (approval.pendingPrompts.value.isEmpty()) delay(10L)
                }
                val prompt = approval.pendingPrompts.value.first()
                assertTrue(
                    approval.submitDecision(
                        prompt.transferId,
                        TransferApprovalDecision.Approved(prompt.request.files.map { it.fileId }),
                    ),
                )
                assertIs<PrepareTransferResult.Accepted>(prepareDeferred.await()).session
            }

            // TC-FSAP-014, TC-FSAP-015, TC-FSAP-019, TC-FSAP-025
            val completed = assertIs<TransferBatchUploadResult.Completed>(
                fx.client.uploadAcceptedBatch(
                    session = session,
                    sourcesByFileId = prepared.associate { it.descriptor.fileId to it.source },
                ),
            )
            assertEquals(TransferState.COMPLETED, completed.stateMachine.currentState())
            assertEquals(2, completed.confirmation.completedFiles.size)

            val savedEmpty = fx.downloadDir.resolve("vazio.txt")
            val savedDoc = fx.downloadDir.resolve("documento.pdf")
            assertTrue(Files.exists(savedEmpty))
            assertTrue(Files.exists(savedDoc))
            assertEquals(0L, Files.size(savedEmpty))
            assertEquals(HASH_EMPTY_SHA256, IncrementalSha256Channel.computeDigest(DesktopNioFileSource(savedEmpty)).sha256Hex)
            assertContentEquals(largeBytes, Files.readAllBytes(savedDoc))

            // TC-FSAP-024: colisao com documento.pdf existente cria 'documento (1).pdf' sem sobrescrever
            val secondBytes = "segunda-versao".encodeToByteArray()
            val collisionSrc = Files.write(fx.sourceDir.resolve("doc_v2.pdf"), secondBytes)
            val collisionPrepared = fx.client.indexSourcesSequential(
                listOf(DesktopNioFileSource(collisionSrc, fileNameOverride = "documento.pdf")),
            )
            val session2 = coroutineScope {
                val d = async {
                    fx.client.prepareTransfer(fx.endpoint, collisionPrepared.map { it.descriptor })
                }
                withTimeout(2_000L) {
                    while (approval.pendingPrompts.value.isEmpty()) delay(10L)
                }
                approval.submitDecision(approval.pendingPrompts.value.first().transferId, TransferApprovalDecision.Approved())
                assertIs<PrepareTransferResult.Accepted>(d.await()).session
            }
            assertIs<TransferBatchUploadResult.Completed>(
                fx.client.uploadAcceptedBatch(
                    session = session2,
                    sourcesByFileId = collisionPrepared.associate { it.descriptor.fileId to it.source },
                ),
            )
            val collided = fx.downloadDir.resolve("documento (1).pdf")
            assertTrue(Files.exists(collided))
            assertContentEquals(secondBytes, Files.readAllBytes(collided))
            assertContentEquals(largeBytes, Files.readAllBytes(savedDoc))
            Files.list(fx.fileSink.stagingDirectory).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `TC-FSAP-009 TC-FSAP-010 TC-FSAP-011 e TC-FSAP-012 validacoes pre-transferencia, recusa e limites`() {
        val approvalCalled = AtomicBoolean(false)
        withDiskServer(
            approvalHandler = {
                approvalCalled.set(true)
                TransferApprovalDecision.Approved()
            },
            customSinkFactory = { dir ->
                DesktopNioFileSink(dir, usableSpaceProvider = { 512L })
            },
        ) { fx ->
            val http = HttpClient(CIO)
            try {
                // TC-FSAP-009: path traversal rejeitado com 400 sem acionar aprovacao
                val badResp = http.post("http://127.0.0.1:${fx.port}${FsapConstants.Routes.TRANSFERS_PREPARE}") {
                    header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                    contentType(ContentType.Application.Json)
                    setBody(
                        FsapJson.encodeEnvelope(
                            FsapEnvelopeFactory.create(
                                type = FsapMessageType.TRANSFER_REQUEST,
                                senderId = SENDER_IDENTITY.deviceId,
                                payload = TransferRequestPayload(
                                    transferId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                    senderAlias = "Client",
                                    senderDeviceType = DeviceType.DESKTOP,
                                    totalBytes = 10L,
                                    files = listOf(FileItemDto("f1", "../etc/passwd", 10L, "a".repeat(64))),
                                ),
                            ),
                        ),
                    )
                }
                assertEquals(HttpStatusCode.BadRequest, badResp.status)
                assertEquals(FsapErrorCode.INVALID_FILENAME.code, FsapJson.decodeEnvelope<ErrorPayload>(badResp.bodyAsText()).payload.errorCode)

                // TC-FSAP-010: espaco insuficiente -> 507 INSUFFICIENT_STORAGE sem acionar aprovacao
                val spaceRejected = assertIs<PrepareTransferResult.Failed>(
                    fx.client.prepareTransfer(
                        fx.endpoint,
                        listOf(FileItemDto("f1", "grande.iso", 1024L, "a".repeat(64))),
                    ),
                )
                assertEquals(FsapErrorCode.INSUFFICIENT_STORAGE, spaceRejected.errorCode)
                assertFalse(approvalCalled.get())
            } finally {
                http.close()
            }
        }

        // TC-FSAP-011: recusa explicita -> 403 TRANSFER_REJECT sem token
        withDiskServer(
            approvalHandler = RejectAllTransferApprovalHandler(RejectReasonCode.USER_DECLINED, "Recusado"),
        ) { fx ->
            val rejected = assertIs<PrepareTransferResult.Rejected>(
                fx.client.prepareTransfer(
                    fx.endpoint,
                    listOf(FileItemDto("f1", "doc.txt", 10L, "a".repeat(64))),
                ),
            )
            assertEquals(RejectReasonCode.USER_DECLINED, rejected.reject.reasonCode)
            assertEquals(0, fx.tokenManager.activeCount())
        }

        // TC-FSAP-012: timeout 408 APPROVAL_TIMEOUT e excesso concorrente -> 403 BUSY
        withDiskServer(
            approvalHandler = {
                delay(700L)
                TransferApprovalDecision.Approved()
            },
            approvalTimeoutMs = 250L,
            maxConcurrentSessions = 1,
        ) { fx ->
            coroutineScope {
                val slowFirst = async {
                    fx.client.prepareTransfer(
                        fx.endpoint,
                        listOf(FileItemDto("f1", "um.txt", 5L, "a".repeat(64))),
                    )
                }
                withTimeout(2_000L) {
                    while (fx.sessionManager.sessions.value.isEmpty()) delay(10L)
                }
                val busySecond = assertIs<PrepareTransferResult.Rejected>(
                    fx.client.prepareTransfer(
                        fx.endpoint,
                        listOf(FileItemDto("f1", "dois.txt", 5L, "a".repeat(64))),
                    ),
                )
                assertEquals(RejectReasonCode.BUSY, busySecond.reject.reasonCode)

                val timedOutFirst = assertIs<PrepareTransferResult.Failed>(slowFirst.await())
                assertEquals(FsapErrorCode.APPROVAL_TIMEOUT, timedOutFirst.errorCode)
            }
        }
    }

    @Test
    fun `TC-FSAP-013 TC-FSAP-020 TC-FSAP-021 TC-FSAP-022 e TC-FSAP-023 token invalido-expirado, hash corrompido, truncado e timeout em disco`() {
        withDiskServer(streamReadTimeoutMs = 200L, tokenTtlMillis = 200L) { fx ->
            val http = HttpClient(CIO)
            try {
                val orig = "integridade-fsap-123".encodeToByteArray()
                val origSrc = Files.write(fx.sourceDir.resolve("orig.txt"), orig)
                val shaOrig = IncrementalSha256Channel.computeDigest(DesktopNioFileSource(origSrc)).sha256Hex
                val sessionCorrupt = assertIs<PrepareTransferResult.Accepted>(
                    fx.client.prepareTransfer(fx.endpoint, listOf(FileItemDto("f1", "orig.txt", orig.size.toLong(), shaOrig))),
                ).session

                // TC-FSAP-013: token invalido para sessao ativa -> 401 UNAUTHORIZED_TRANSFER
                val unauthResp = http.put("http://127.0.0.1:${fx.port}${FsapConstants.Routes.transferContent(sessionCorrupt.transferId, "f1")}") {
                    header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                    header(FsapHeaders.TRANSFER_TOKEN, "tok-invalido-12345678901234567890")
                    header(FsapHeaders.FILE_SHA256, shaOrig)
                    setBody(orig)
                }
                assertEquals(HttpStatusCode.Unauthorized, unauthResp.status)
                assertEquals(FsapErrorCode.UNAUTHORIZED_TRANSFER.code, FsapJson.decodeEnvelope<ErrorPayload>(unauthResp.bodyAsText()).payload.errorCode)

                // TC-FSAP-020: 1-bit corruption -> 422 HASH_MISMATCH e .part excluido

                val corrupted = orig.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
                val corruptResp = http.put("http://127.0.0.1:${fx.port}${FsapConstants.Routes.transferContent(sessionCorrupt.transferId, "f1")}") {
                    header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                    header(FsapHeaders.TRANSFER_TOKEN, sessionCorrupt.transferToken)
                    header(FsapHeaders.FILE_SHA256, shaOrig)
                    setBody(corrupted)
                }
                assertEquals(HttpStatusCode.UnprocessableEntity, corruptResp.status)
                assertEquals(FsapErrorCode.HASH_MISMATCH.code, FsapJson.decodeEnvelope<ErrorPayload>(corruptResp.bodyAsText()).payload.errorCode)
                assertFalse(Files.exists(fx.downloadDir.resolve("orig.txt")))
                Files.list(fx.fileSink.stagingDirectory).use { assertEquals(0L, it.count()) }

                // TC-FSAP-021: stream truncado via socket cru -> 400 SIZE_MISMATCH e .part excluido
                val sessionTrunc = assertIs<PrepareTransferResult.Accepted>(
                    fx.client.prepareTransfer(fx.endpoint, listOf(FileItemDto("f1", "trunc.txt", orig.size.toLong(), shaOrig))),
                ).session
                Socket("127.0.0.1", fx.port).use { s ->
                    val out = s.getOutputStream()
                    val hdr = "PUT ${FsapConstants.Routes.transferContent(sessionTrunc.transferId, "f1")} HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:${fx.port}\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: ${orig.size - 5}\r\n" +
                        "${FsapHeaders.VERSION}: ${FsapConstants.PROTOCOL_VERSION}\r\n" +
                        "${FsapHeaders.TRANSFER_TOKEN}: ${sessionTrunc.transferToken}\r\n" +
                        "${FsapHeaders.FILE_SHA256}: $shaOrig\r\n\r\n"
                    out.write(hdr.encodeToByteArray())
                    out.write(orig, 0, orig.size - 5)
                    out.flush()
                    val resp = readRawHttpResponse(s)
                    assertEquals(400, resp.statusCode)
                    assertEquals(FsapErrorCode.SIZE_MISMATCH.code, FsapJson.decodeEnvelope<ErrorPayload>(resp.body).payload.errorCode)
                }
                Files.list(fx.fileSink.stagingDirectory).use { assertEquals(0L, it.count()) }

                // TC-FSAP-022: stream ocioso > 200ms -> 408 STREAM_TIMEOUT e .part excluido
                val sessionSlow = assertIs<PrepareTransferResult.Accepted>(
                    fx.client.prepareTransfer(fx.endpoint, listOf(FileItemDto("f1", "lento.bin", 4096L, "b".repeat(64)))),
                ).session
                Socket("127.0.0.1", fx.port).use { s ->
                    val out = s.getOutputStream()
                    val hdr = "PUT ${FsapConstants.Routes.transferContent(sessionSlow.transferId, "f1")} HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:${fx.port}\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: 4096\r\n" +
                        "${FsapHeaders.VERSION}: ${FsapConstants.PROTOCOL_VERSION}\r\n" +
                        "${FsapHeaders.TRANSFER_TOKEN}: ${sessionSlow.transferToken}\r\n" +
                        "${FsapHeaders.FILE_SHA256}: ${"b".repeat(64)}\r\n\r\n"
                    out.write(hdr.encodeToByteArray())
                    out.write(ByteArray(128))
                    out.flush()
                    val resp = readRawHttpResponse(s)
                    assertEquals(408, resp.statusCode)
                    assertEquals(FsapErrorCode.STREAM_TIMEOUT.code, FsapJson.decodeEnvelope<ErrorPayload>(resp.body).payload.errorCode)
                }
                Files.list(fx.fileSink.stagingDirectory).use { assertEquals(0L, it.count()) }

                // TC-FSAP-023: expiracao de token por ociosidade -> 401 UNAUTHORIZED_TRANSFER + complete precoce -> 409
                val sessionPremature = assertIs<PrepareTransferResult.Accepted>(
                    fx.client.prepareTransfer(fx.endpoint, listOf(FileItemDto("f1", "precoce.txt", orig.size.toLong(), shaOrig))),
                ).session
                val prematureResp = http.post("http://127.0.0.1:${fx.port}${FsapConstants.Routes.transferComplete(sessionPremature.transferId)}") {
                    header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                    header(FsapHeaders.TRANSFER_TOKEN, sessionPremature.transferToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        FsapJson.encodeEnvelope(
                            FsapEnvelopeFactory.create(
                                type = FsapMessageType.TRANSFER_COMPLETE,
                                senderId = SENDER_IDENTITY.deviceId,
                                payload = br.edu.fileshare.protocol.model.TransferCompletePayload(
                                    transferId = sessionPremature.transferId,
                                    completedFiles = listOf(br.edu.fileshare.protocol.model.CompletedFileSummary("f1", shaOrig)),
                                ),
                            ),
                        ),
                    )
                }
                assertEquals(HttpStatusCode.Conflict, prematureResp.status)
                assertEquals(FsapErrorCode.INVALID_STATE_TRANSITION.code, FsapJson.decodeEnvelope<ErrorPayload>(prematureResp.bodyAsText()).payload.errorCode)

                fx.clock.nowMs += FsapConstants.T_TOKEN_IDLE_TTL_MS + 1_000L
                val expiredResp = http.put("http://127.0.0.1:${fx.port}${FsapConstants.Routes.transferContent(sessionPremature.transferId, "f1")}") {
                    header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                    header(FsapHeaders.TRANSFER_TOKEN, sessionPremature.transferToken)
                    header(FsapHeaders.FILE_SHA256, shaOrig)
                    setBody(orig)
                }
                assertEquals(HttpStatusCode.Unauthorized, expiredResp.status)
                assertEquals(FsapErrorCode.UNAUTHORIZED_TRANSFER.code, FsapJson.decodeEnvelope<ErrorPayload>(expiredResp.bodyAsText()).payload.errorCode)
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun `TC-FSAP-016 TC-FSAP-017 e TC-FSAP-018 coordenador consulta status, cancela SENDER e propaga cancelamento RECEIVER sem part em disco`() {
        withDiskServer { fx ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val coordinator = TransferCoordinator(
                httpClient = fx.client,
                parentScope = scope,
                serverSessionManager = fx.sessionManager,
                logger = fx.logger,
                clock = fx.clock,
            )
            val peer = DiscoveredPeer(
                identity = RECEIVER_IDENTITY,
                ipAddress = "127.0.0.1",
                port = fx.port,
                connectionState = PeerConnectionState.CONNECTED,
                origin = PeerOrigin.MANUAL_IP,
                lastSeenEpochMillis = fx.clock.nowMs,
                firstSeenEpochMillis = fx.clock.nowMs,
            )
            try {
                // TC-FSAP-016: concluido com sucesso via TransferCoordinator + progresso
                val okSrc = DesktopNioFileSource(Files.write(fx.sourceDir.resolve("coord.bin"), ByteArray(150_000) { 0x1A }))
                val okId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"
                val okOutcome = CompletableDeferred<SendFilesOutcome>()
                val okJob = coordinator.launchSendFiles(
                    peer = peer,
                    sources = listOf(okSrc),
                    transferId = okId,
                    onFinished = { okOutcome.complete(it) },
                )
                okJob.join()
                assertIs<SendFilesOutcome.Completed>(okOutcome.await())
                val okSnap = coordinator.progressTracker.getSnapshot(okId)!!
                assertEquals(TransferState.COMPLETED, okSnap.state)
                assertEquals(150_000L, okSnap.bytesTransferred)

                // TC-FSAP-017: cancelamento por SENDER mid-stream e limpeza .part em disco
                val cancelId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2"
                val cancelOutcome = CompletableDeferred<SendFilesOutcome>()
                val cancelJob = coordinator.launchSendFiles(
                    peer = peer,
                    sources = listOf(ThrottledSecondPassFileSource("abort_sender.bin", 2L * 1024L * 1024L)),
                    transferId = cancelId,
                    onFinished = { cancelOutcome.complete(it) },
                )
                withTimeout(5_000L) {
                    while (fx.sessionManager.findSnapshot(cancelId)?.state != TransferState.TRANSFERRING ||
                        (fx.sessionManager.findSnapshot(cancelId)?.totalBytesTransferred ?: 0L) <= 0L
                    ) {
                        delay(10L)
                    }
                }
                assertTrue(coordinator.cancelTransfer(cancelId, "USER_CANCEL_TEST"))
                cancelJob.join()
                assertIs<SendFilesOutcome.Cancelled>(cancelOutcome.await())
                assertEquals(TransferState.CANCELLED, coordinator.progressTracker.getSnapshot(cancelId)?.state)
                withTimeout(2_000L) {
                    while (Files.list(fx.fileSink.stagingDirectory).use { it.count() } > 0L) delay(10L)
                }

                // TC-FSAP-018: cancelamento por RECEIVER propagado e .part removido em disco
                val recvCancelId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3"
                val recvOutcome = CompletableDeferred<SendFilesOutcome>()
                val recvJob = coordinator.launchSendFiles(
                    peer = peer,
                    sources = listOf(ThrottledSecondPassFileSource("abort_recv.bin", 2L * 1024L * 1024L)),
                    transferId = recvCancelId,
                    onFinished = { recvOutcome.complete(it) },
                )
                withTimeout(5_000L) {
                    while (fx.sessionManager.findSnapshot(recvCancelId)?.state != TransferState.TRANSFERRING ||
                        (fx.sessionManager.findSnapshot(recvCancelId)?.totalBytesTransferred ?: 0L) <= 0L
                    ) {
                        delay(10L)
                    }
                }
                fx.sessionManager.cancelTransfer(recvCancelId, CancelledBy.RECEIVER, "RECEIVER_ABORT")
                recvJob.join()
                assertIs<SendFilesOutcome.Cancelled>(recvOutcome.await())
                assertEquals(TransferState.CANCELLED, coordinator.progressTracker.getSnapshot(recvCancelId)?.state)
                withTimeout(2_000L) {
                    while (Files.list(fx.fileSink.stagingDirectory).use { it.count() } > 0L) delay(10L)
                }
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }
    }
}

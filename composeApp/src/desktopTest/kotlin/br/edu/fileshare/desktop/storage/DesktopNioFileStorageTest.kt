package br.edu.fileshare.desktop.storage

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.desktop.di.DesktopServerContainer
import br.edu.fileshare.desktop.server.ConsoleTransferApprovalHandler
import br.edu.fileshare.desktop.server.ServerCliConfig
import br.edu.fileshare.desktop.server.createDesktopHeadlessServer
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.client.PrepareTransferResult
import br.edu.fileshare.network.client.TransferBatchUploadResult
import br.edu.fileshare.network.server.ApprovalRequestContext
import br.edu.fileshare.network.server.TransferApprovalDecision
import br.edu.fileshare.network.streaming.IncrementalSha256Channel
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.model.RejectReasonCode
import br.edu.fileshare.protocol.model.TransferRequestPayload
import br.edu.fileshare.protocol.statemachine.TransferState
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class DesktopNioFileStorageTest {

    private inline fun withTempDir(prefix: String, block: (Path) -> Unit) {
        val dir = Files.createTempDirectory(prefix)
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `leitura streaming NIO, escrita part, commit atomico e colisao nao-sobrescrita em disco`() =
        runTest {
            withTempDir("fileshare-nio-") { root ->
                val srcDir = Files.createDirectories(root.resolve("source"))
                val dstDir = Files.createDirectories(root.resolve("downloads"))

                val payload = ByteArray(256 * 1024 + 317) { idx -> ((idx * 17) % 251).toByte() }
                val sourceFile = srcDir.resolve("documento.pdf")
                Files.write(sourceFile, payload)

                val nioSource = DesktopNioFileSource(sourceFile)
                assertEquals("documento.pdf", nioSource.fileName)
                assertEquals(payload.size.toLong(), nioSource.sizeBytes)

                val digest = IncrementalSha256Channel.computeDigest(nioSource)
                assertEquals(64, digest.sha256Hex.length)

                val sink = DesktopNioFileSink(dstDir)
                assertTrue(sink.availableStorageBytes() > 0L)

                val transferId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

                // 1) Verifica staging .part durante escrita antes do commit
                val staged1 = sink.openPartSession(transferId, "f-1", "documento.pdf", payload.size.toLong())
                val partPath1 = sink.stagingDirectory.resolve(staged1.partIdentifier)
                assertTrue(Files.exists(partPath1), "Arquivo .part deve existir durante staging")
                assertFalse(Files.exists(dstDir.resolve("documento.pdf")))

                staged1.writeChunk(payload, 0, payload.size)
                staged1.flushAndSync()
                val stored1 = staged1.commitPart(digest.sha256Hex, payload.size.toLong())
                assertEquals("documento.pdf", stored1.finalName)
                assertFalse(Files.exists(partPath1), ".part deve sumir apos commit")
                assertContentEquals(payload, Files.readAllBytes(dstDir.resolve("documento.pdf")))

                // 2) Colisoes subsequentes produzem 'documento (1).pdf' e 'documento (2).pdf' sem sobrescrever
                val payload2 = "segundo arquivo".encodeToByteArray()
                val digest2 = IncrementalSha256Channel.computeDigest(
                    DesktopNioFileSource(Files.write(srcDir.resolve("doc2.pdf"), payload2), fileNameOverride = "documento.pdf"),
                )
                val staged2 = sink.openPartSession(transferId, "f-2", "documento.pdf", payload2.size.toLong())
                staged2.writeChunk(payload2, 0, payload2.size)
                staged2.flushAndSync()
                val stored2 = staged2.commitPart(digest2.sha256Hex, payload2.size.toLong())
                assertEquals("documento (1).pdf", stored2.finalName)

                val staged3 = sink.openPartSession(transferId, "f-3", "documento.pdf", payload2.size.toLong())
                staged3.writeChunk(payload2, 0, payload2.size)
                staged3.flushAndSync()
                val stored3 = staged3.commitPart(digest2.sha256Hex, payload2.size.toLong())
                assertEquals("documento (2).pdf", stored3.finalName)

                // Conteudo original permanece intocado
                assertContentEquals(payload, Files.readAllBytes(dstDir.resolve("documento.pdf")))
                assertContentEquals(payload2, Files.readAllBytes(dstDir.resolve("documento (1).pdf")))
                assertContentEquals(payload2, Files.readAllBytes(dstDir.resolve("documento (2).pdf")))
            }
        }

    @Test
    fun `rejeita Path Traversal, remove part em falha de hash e limpa partes por transferId`() =
        runTest {
            withTempDir("fileshare-sec-") { root ->
                val sink = DesktopNioFileSink(root.resolve("downloads"))

                for (maliciousName in listOf("../evil.sh", "/etc/passwd", "sub\\arq.txt", "nul\u0000.txt")) {
                    val ex = assertFailsWith<FsapProtocolException> {
                        sink.openPartSession("t-1", "f-1", maliciousName, 10L)
                    }
                    assertEquals(FsapErrorCode.INVALID_FILENAME, ex.errorCode)
                }

                // receiveChannelToSinkVerified com hash errado remove .part em staging
                val stagedCorrupt = sink.openPartSession("t-corrupt", "f-1", "arq.bin", 4L)
                val partCorrupt = sink.stagingDirectory.resolve(stagedCorrupt.partIdentifier)
                assertTrue(Files.exists(partCorrupt))

                val err = assertFailsWith<FsapProtocolException> {
                    IncrementalSha256Channel.receiveChannelToSinkVerified(
                        readChannel = ByteReadChannel(byteArrayOf(1, 2, 3, 4)),
                        sinkSession = stagedCorrupt,
                        expectedSizeBytes = 4L,
                        expectedSha256 = "0".repeat(64),
                    )
                }
                assertEquals(FsapErrorCode.HASH_MISMATCH, err.errorCode)
                assertFalse(Files.exists(partCorrupt))

                // cleanupTransferParts remove somente .part da transferencia alvo
                sink.openPartSession("t-clean", "f-a", "a.bin", 5L)
                sink.openPartSession("t-clean", "f-b", "b.bin", 5L)
                val keepSession = sink.openPartSession("t-other", "f-c", "c.bin", 5L)

                val deleted = sink.cleanupTransferParts("t-clean")
                assertEquals(2, deleted)
                assertTrue(Files.exists(sink.stagingDirectory.resolve(keepSession.partIdentifier)))
                keepSession.discardPart()
            }
        }

    @Test
    fun `ConsoleTransferApprovalHandler interpreta respostas S-N e timeout`() = runTest {
        val ctx = ApprovalRequestContext(
            transferId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            senderId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            request = TransferRequestPayload(
                transferId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                senderAlias = "Sender",
                senderDeviceType = DeviceType.MOBILE,
                totalBytes = 10L,
                files = listOf(
                    FileItemDto("f-1", "foto.jpg", 10L, "a".repeat(64)),
                ),
            ),
            receivedAtEpochMillis = 0L,
        )
        val quietOut = PrintStream(ByteArrayOutputStream())

        val acceptHandler = ConsoleTransferApprovalHandler(out = quietOut, lineProvider = { "sim" })
        val approved = assertIs<TransferApprovalDecision.Approved>(acceptHandler.requestApproval(ctx))
        assertEquals(listOf("f-1"), approved.acceptedFileIds)

        val declineHandler = ConsoleTransferApprovalHandler(out = quietOut, lineProvider = { "n" })
        val declined = assertIs<TransferApprovalDecision.Rejected>(declineHandler.requestApproval(ctx))
        assertEquals(RejectReasonCode.USER_DECLINED, declined.reasonCode)

        val timeoutHandler = ConsoleTransferApprovalHandler(
            out = quietOut,
            timeoutMs = 50L,
            lineProvider = {
                delay(500L)
                "s"
            },
        )
        val timedOut = assertIs<TransferApprovalDecision.Rejected>(timeoutHandler.requestApproval(ctx))
        assertEquals(RejectReasonCode.USER_DECLINED, timedOut.reasonCode)
    }

    @Test
    fun `ServerMain headless grava arquivo real em disco via DesktopNioFileSink sem artefatos part`() =
        runBlocking {
            withTempDir("fileshare-srv-") { root ->
                val srcDir = Files.createDirectories(root.resolve("src"))
                val downloadDir = Files.createDirectories(root.resolve("downloads"))

                val payload = ByteArray(180_000) { idx -> (idx % 239).toByte() }
                val srcPath = srcDir.resolve("relatorio-final.bin")
                Files.write(srcPath, payload)
                val nioSource = DesktopNioFileSource(srcPath)
                val expectedSha = IncrementalSha256Channel.computeDigest(nioSource).sha256Hex

                val runtime = createDesktopHeadlessServer(
                    config = ServerCliConfig(
                        port = 0,
                        alias = "Headless-Server-Test",
                        downloadDir = downloadDir,
                        autoAcceptTest = true,
                    ),
                    container = DesktopServerContainer(logger = InMemoryFsapLogger()),
                )

                val clientIdentity = DeviceIdentity(
                    deviceId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                    alias = "Cliente Teste",
                    deviceType = DeviceType.DESKTOP,
                    platform = PlatformType.LINUX,
                    httpPort = 53317,
                )
                val fsapClient = FsapHttpClient(localIdentity = clientIdentity)

                val boundPort = runtime.start()
                try {
                    val endpoint = FsapPeerEndpoint(host = "127.0.0.1", port = boundPort)
                    val prepared = fsapClient.indexSourcesSequential(listOf(nioSource))
                    val accepted = assertIs<PrepareTransferResult.Accepted>(
                        fsapClient.prepareTransfer(
                            endpoint = endpoint,
                            files = prepared.map { it.descriptor },
                        ),
                    )
                    val uploadResult = assertIs<TransferBatchUploadResult.Completed>(
                        fsapClient.uploadAcceptedBatch(
                            session = accepted.session,
                            sourcesByFileId = prepared.associate { it.descriptor.fileId to it.source },
                        ),
                    )
                    assertEquals(TransferState.COMPLETED, uploadResult.stateMachine.currentState())
                    assertEquals(1, uploadResult.confirmation.completedFiles.size)

                    val savedFile = downloadDir.resolve("relatorio-final.bin")
                    assertTrue(Files.exists(savedFile))
                    assertContentEquals(payload, Files.readAllBytes(savedFile))
                    assertEquals(expectedSha, IncrementalSha256Channel.computeDigest(DesktopNioFileSource(savedFile)).sha256Hex)

                    // Zero arquivos .part residuais
                    Files.list(runtime.fileSink.stagingDirectory).use { entries ->
                        assertEquals(0L, entries.count())
                    }
                    assertEquals(FsapConstants.CHUNK_SIZE_BYTES, 65_536)
                } finally {
                    fsapClient.close()
                    runtime.stopGracefully()
                }
            }
        }
}

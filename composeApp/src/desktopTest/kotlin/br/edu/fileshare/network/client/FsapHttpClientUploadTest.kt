package br.edu.fileshare.network.client

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.configureFsapServer
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.FileReadSession
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.InMemoryFileSink
import br.edu.fileshare.storage.InMemoryFileSource
import br.edu.fileshare.storage.SyntheticPatternFileSource
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FsapHttpClientUploadTest {

    private companion object {
        val SENDER_IDENTITY = DeviceIdentity(
            deviceId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            alias = "Remetente Linux",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
        )
        val SERVER_IDENTITY = DeviceIdentity(
            deviceId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            alias = "Receptor Server",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
        )
    }

    @Test
    fun `TC-FSAP-014 upload streaming CIO de 5 MB usa apenas chunks de 64 KB e conclui em COMPLETED`() =
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
                ),
            )
            val port = server.start()
            val client = FsapHttpClient(localIdentity = SENDER_IDENTITY, logger = logger)

            try {
                val fiveMbSize = 5L * 1024L * 1024L
                val fiveMbSource = SyntheticPatternFileSource(
                    fileName = "pacote_5mb.bin",
                    sizeBytes = fiveMbSize,
                    patternByte = 0x3C,
                )
                val preparedFiles = client.indexSourcesSequential(listOf(fiveMbSource))
                assertEquals(1, preparedFiles.size)
                assertEquals(fiveMbSize, preparedFiles[0].descriptor.sizeBytes)

                val endpoint = FsapPeerEndpoint(host = "127.0.0.1", port = port)
                val prepareResult = client.prepareTransfer(
                    endpoint = endpoint,
                    files = preparedFiles.map { it.descriptor },
                )
                val session = assertIs<PrepareTransferResult.Accepted>(prepareResult).session

                val reportedBatchProgress = mutableListOf<Long>()
                val uploadResult = client.uploadAcceptedBatch(
                    session = session,
                    sourcesByFileId = preparedFiles.associate { it.descriptor.fileId to it.source },
                    progressListener = { _, _, _, batchSent, batchTotal ->
                        assertEquals(fiveMbSize, batchTotal)
                        reportedBatchProgress += batchSent
                    },
                )

                val completed = assertIs<TransferBatchUploadResult.Completed>(uploadResult)
                assertEquals(TransferState.COMPLETED, completed.stateMachine.currentState())
                assertEquals(TransferState.COMPLETED.name, completed.confirmation.state)
                assertEquals(TransferState.COMPLETED, sessionManager.findSnapshot(session.transferId)?.state)
                assertTrue(fiveMbSource.observedBufferSizes.isNotEmpty())
                assertTrue(fiveMbSource.observedBufferSizes.all { it == FsapConstants.CHUNK_SIZE_BYTES })
                assertEquals(fiveMbSize, reportedBatchProgress.lastOrNull())
                assertTrue(reportedBatchProgress.zipWithNext().all { (a, b) -> b >= a })
                assertTrue(sink.stagedPartKeys().isEmpty())
                assertTrue("pacote_5mb.bin" in sink.committedFileNames())
                assertEquals(0, tokens.activeCount())
            } finally {
                client.close()
                sessionManager.close()
                server.stop()
            }
        }

    @Test
    fun `TC-FSAP-015 upload em lote multi-arquivos envia todos os arquivos em sequencia e confirma COMPLETE`() =
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
            startApplication()

            val fsapClient = FsapHttpClient(
                localIdentity = SENDER_IDENTITY,
                logger = logger,
                providedHttpClient = client,
            )

            val emptyBytes = ByteArray(0)
            val mediumBytes = ByteArray(150_000) { idx -> (idx % 251).toByte() }
            val smallBytes = "notas-finais-fsap".encodeToByteArray()

            val sources = listOf(
                InMemoryFileSource("vazio.txt", emptyBytes),
                InMemoryFileSource("video.bin", mediumBytes),
                InMemoryFileSource("leia-me.md", smallBytes),
            )
            val prepared = fsapClient.indexSourcesSequential(sources)
            val endpoint = FsapPeerEndpoint("localhost", 80)

            val accept = assertIs<PrepareTransferResult.Accepted>(
                fsapClient.prepareTransfer(endpoint, prepared.map { it.descriptor }),
            )
            val batchResult = fsapClient.uploadAcceptedBatch(
                session = accept.session,
                sourcesByFileId = prepared.associate { it.descriptor.fileId to it.source },
            )

            val completed = assertIs<TransferBatchUploadResult.Completed>(batchResult)
            assertEquals(3, completed.confirmation.completedFiles.size)
            assertEquals(TransferState.COMPLETED, accept.session.stateMachine.currentState())

            val remoteStatus = fsapClient.queryTransferStatus(
                endpoint = endpoint,
                transferId = completed.transferId,
                transferToken = accept.session.transferToken,
            )
            assertEquals(TransferState.COMPLETED.name, remoteStatus.state)
            assertEquals(3, remoteStatus.filesCompleted)
            assertContentEquals(emptyBytes, sink.readCommittedBytes("vazio.txt"))
            assertContentEquals(mediumBytes, sink.readCommittedBytes("video.bin"))
            assertContentEquals(smallBytes, sink.readCommittedBytes("leia-me.md"))
        }

    @Test
    fun `falha de hash em qualquer arquivo do lote aborta transmissao e move cliente e servidor para FAILED`() =
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
            startApplication()

            val fsapClient = FsapHttpClient(
                localIdentity = SENDER_IDENTITY,
                logger = logger,
                providedHttpClient = client,
            )

            val goodData = "ok-data".encodeToByteArray()
            val declaredSecond = ByteArray(1024) { 0x11 }
            val corruptedSecond = declaredSecond.copyOf().apply { this[512] = 0x99.toByte() }

            // Source que devolve conteudo adulterado na segunda leitura (apos o calculo de hash do manifesto)
            var readsCount = 0
            val mutatingSecondSource = object : FileSource {
                override val fileName: String = "corrompido.bin"
                override val sizeBytes: Long = declaredSecond.size.toLong()
                override val mimeType: String = "application/octet-stream"

                override suspend fun openReadSession(): FileReadSession {
                    val payload = if (readsCount++ == 0) declaredSecond else corruptedSecond
                    return InMemoryFileSource(fileName, payload).openReadSession()
                }
            }

            val prepared = fsapClient.indexSourcesSequential(
                listOf(InMemoryFileSource("bom.txt", goodData), mutatingSecondSource),
            )
            val endpoint = FsapPeerEndpoint("localhost", 80)
            val accept = assertIs<PrepareTransferResult.Accepted>(
                fsapClient.prepareTransfer(endpoint, prepared.map { it.descriptor }),
            )

            val uploadRes = fsapClient.uploadAcceptedBatch(
                session = accept.session,
                sourcesByFileId = prepared.associate { it.descriptor.fileId to it.source },
            )

            val failed = assertIs<TransferBatchUploadResult.Failed>(uploadRes)
            assertEquals(FsapErrorCode.HASH_MISMATCH, failed.errorCode)
            assertEquals(TransferState.FAILED, accept.session.stateMachine.currentState())
            assertEquals(TransferState.FAILED, manager.findSnapshot(accept.session.transferId)?.state)
            assertTrue(sink.stagedPartKeys().isEmpty())
        }
}

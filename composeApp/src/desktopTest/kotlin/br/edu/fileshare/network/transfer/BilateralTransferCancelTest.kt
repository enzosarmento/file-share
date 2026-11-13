package br.edu.fileshare.network.transfer

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.client.FsapPeerEndpoint
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.FileReadSession
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.InMemoryFileSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BilateralTransferCancelTest {

    private companion object {
        val SENDER_ID = DeviceIdentity(
            deviceId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            alias = "Remetente Cancel",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
        )
        val RECEIVER_ID = DeviceIdentity(
            deviceId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            alias = "Destinatario Cancel",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
        )
    }

    /**
     * Source que simula envio realista em chunks lentos apos a indexacao inicial (sem travar no hashing),
     * permitindo cancelar exatamente no meio do streaming HTTP/TCP.
     */
    private class ThrottledSecondPassFileSource(
        override val fileName: String,
        override val sizeBytes: Long,
        private val perChunkDelayMs: Long = 15L,
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

    @Test
    fun `TC-FSAP-017 e TC-FSAP-018 cancelamento bilateral interrompe stream em menos de 500ms e apaga part`() =
        runBlocking {
            val logger = InMemoryFsapLogger()
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(logger = logger)
            val receiverManager = ServerTransferSessionManager(
                tokenManager = tokens,
                fileSink = sink,
                scope = this,
                logger = logger,
            )
            val server = FsapHttpServer(
                config = FsapHttpServerConfig(
                    identity = RECEIVER_ID.copy(httpPort = 0),
                    host = "127.0.0.1",
                    port = 0,
                ),
                logger = logger,
                transferServices = FsapTransferServerServices(
                    sessionManager = receiverManager,
                    fileSink = sink,
                    approvalHandler = AutoAcceptTransferApprovalHandler,
                ),
            )
            val port = server.start()
            val endpoint = FsapPeerEndpoint(host = "127.0.0.1", port = port)

            val senderClient = FsapHttpClient(localIdentity = SENDER_ID, logger = logger)
            val receiverDummyClient = FsapHttpClient(localIdentity = RECEIVER_ID, logger = logger)

            val senderCoordinator = TransferCoordinator(
                httpClient = senderClient,
                parentScope = this,
                logger = logger,
            )
            val receiverCoordinator = TransferCoordinator(
                httpClient = receiverDummyClient,
                parentScope = this,
                serverSessionManager = receiverManager,
                logger = logger,
            )

            try {
                // TC-FSAP-017: Remetente cancela transferencia no meio do stream
                val txSender = "10101010-1010-4010-8010-101010101010"
                val senderOutcomeDeferred = CompletableDeferred<SendFilesOutcome>()
                val job1 = senderCoordinator.launchSendFiles(
                    peer = br.edu.fileshare.network.discovery.DiscoveredPeer(
                        identity = RECEIVER_ID,
                        ipAddress = "127.0.0.1",
                        port = port,
                        connectionState = br.edu.fileshare.protocol.statemachine.PeerConnectionState.CONNECTED,
                        origin = br.edu.fileshare.network.discovery.PeerOrigin.MANUAL_IP,
                        lastSeenEpochMillis = 0L,
                        firstSeenEpochMillis = 0L,
                    ),
                    sources = listOf(ThrottledSecondPassFileSource("stream_sender_cancel.bin", 2L * 1024L * 1024L)),
                    transferId = txSender,
                    onFinished = { senderOutcomeDeferred.complete(it) },
                )

                withTimeout(5_000) {
                    while (receiverManager.findSnapshot(txSender)?.state != TransferState.TRANSFERRING ||
                        (receiverManager.findSnapshot(txSender)?.totalBytesTransferred ?: 0L) <= 0L
                    ) {
                        delay(10)
                    }
                }

                val t0Sender = System.currentTimeMillis()
                assertTrue(senderCoordinator.cancelTransfer(txSender, "USER_REQUESTED"))
                val senderOutcome = withTimeout(2_000) { senderOutcomeDeferred.await() }
                job1.join()
                val elapsedSenderMs = System.currentTimeMillis() - t0Sender

                assertIs<SendFilesOutcome.Cancelled>(senderOutcome)
                assertEquals(TransferState.CANCELLED, senderCoordinator.progressTracker.getSnapshot(txSender)?.state)
                assertEquals(TransferState.CANCELLED, receiverManager.findSnapshot(txSender)?.state)
                assertTrue(sink.stagedPartKeys().isEmpty(), "Esperava .part removidos apos cancelamento do remetente.")
                assertTrue(sink.committedFileNames().isEmpty())
                assertTrue(elapsedSenderMs < 1_000L, "Cancelamento do remetente levou ${elapsedSenderMs}ms.")

                // TC-FSAP-018: Destinatario cancela transferencia em curso via TransferCoordinator
                val txReceiver = "20202020-2020-4020-8020-202020202020"
                val receiverOutcomeDeferred = CompletableDeferred<SendFilesOutcome>()
                val job2 = senderCoordinator.launchSendFiles(
                    peer = br.edu.fileshare.network.discovery.DiscoveredPeer(
                        identity = RECEIVER_ID,
                        ipAddress = "127.0.0.1",
                        port = port,
                        connectionState = br.edu.fileshare.protocol.statemachine.PeerConnectionState.CONNECTED,
                        origin = br.edu.fileshare.network.discovery.PeerOrigin.MANUAL_IP,
                        lastSeenEpochMillis = 0L,
                        firstSeenEpochMillis = 0L,
                    ),
                    sources = listOf(ThrottledSecondPassFileSource("stream_receiver_cancel.bin", 2L * 1024L * 1024L)),
                    transferId = txReceiver,
                    onFinished = { receiverOutcomeDeferred.complete(it) },
                )

                withTimeout(5_000) {
                    while (receiverManager.findSnapshot(txReceiver)?.state != TransferState.TRANSFERRING ||
                        (receiverManager.findSnapshot(txReceiver)?.totalBytesTransferred ?: 0L) <= 0L
                    ) {
                        delay(10)
                    }
                }

                val t0Receiver = System.currentTimeMillis()
                assertTrue(receiverCoordinator.cancelTransfer(txReceiver, "USER_ABORTED"))
                val outcomeAfterReceiverCancel = withTimeout(3_000) { receiverOutcomeDeferred.await() }
                job2.join()
                val elapsedReceiverMs = System.currentTimeMillis() - t0Receiver

                assertIs<SendFilesOutcome.Cancelled>(outcomeAfterReceiverCancel)
                assertEquals(TransferState.CANCELLED, senderCoordinator.progressTracker.getSnapshot(txReceiver)?.state)
                assertEquals(TransferState.CANCELLED, receiverCoordinator.progressTracker.getSnapshot(txReceiver)?.state)
                assertEquals(TransferState.CANCELLED, receiverManager.findSnapshot(txReceiver)?.state)
                assertTrue(sink.stagedPartKeys().isEmpty())
                assertTrue(sink.committedFileNames().isEmpty())
                assertEquals(0, tokens.activeCount())
                assertTrue(elapsedReceiverMs < 1_500L, "Cancelamento bilateral do receptor levou ${elapsedReceiverMs}ms.")
            } finally {
                senderCoordinator.close()
                receiverCoordinator.close()
                senderClient.close()
                receiverDummyClient.close()
                receiverManager.close()
                server.stop()
            }
        }
}

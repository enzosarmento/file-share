package br.edu.fileshare.network.client

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.network.server.ApprovalRequestContext
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.RejectAllTransferApprovalHandler
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferApprovalDecision
import br.edu.fileshare.network.server.TransferApprovalHandler
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.configureFsapServer
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.model.RejectReasonCode
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.protocol.statemachine.TransferStateMachine
import br.edu.fileshare.storage.InMemoryFileSink
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FsapHttpClientPrepareTest {

    private companion object {
        val SENDER_IDENTITY = DeviceIdentity(
            deviceId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            alias = "Remetente Desktop",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
        )
        val SERVER_IDENTITY = DeviceIdentity(
            deviceId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            alias = "Destinatario Desktop",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
        )
        const val SAMPLE_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    @Test
    fun `TC-FSAP-008 negociacao bem-sucedida avanca FSM local para ACCEPTED e recebe transferToken`() =
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

            val transferId = "11111111-1111-4111-8111-111111111111"
            val fsm = TransferStateMachine(transferId = transferId, logger = logger)
            val result = fsapClient.prepareTransfer(
                endpoint = FsapPeerEndpoint(host = "localhost", port = 80),
                transferId = transferId,
                stateMachine = fsm,
                files = listOf(FileItemDto("f1", "vazio.txt", 0L, SAMPLE_SHA)),
            )

            val accepted = assertIs<PrepareTransferResult.Accepted>(result)
            assertTrue(accepted.session.transferToken.isNotBlank())
            assertEquals(setOf("f1"), accepted.session.acceptedFileIds)
            assertEquals(TransferState.ACCEPTED, fsm.currentState())
            assertEquals(TransferState.ACCEPTED, manager.findSnapshot(transferId)?.state)
        }

    @Test
    fun `TC-FSAP-011 recusa explicita do destinatario transita FSM local para REJECTED`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(logger = logger)

            application {
                val manager = ServerTransferSessionManager(
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
                        approvalHandler = RejectAllTransferApprovalHandler(
                            reasonCode = RejectReasonCode.USER_DECLINED,
                            reasonMessage = "Usuario clicou em Recusar.",
                        ),
                    ),
                )
            }
            startApplication()

            val fsapClient = FsapHttpClient(
                localIdentity = SENDER_IDENTITY,
                logger = logger,
                providedHttpClient = client,
            )
            val fsm = TransferStateMachine(transferId = "22222222-2222-4222-8222-222222222222", logger = logger)
            val result = fsapClient.prepareTransfer(
                endpoint = FsapPeerEndpoint(host = "localhost", port = 80),
                transferId = fsm.transferId,
                stateMachine = fsm,
                files = listOf(FileItemDto("f1", "foto.jpg", 0L, SAMPLE_SHA)),
            )

            val rejected = assertIs<PrepareTransferResult.Rejected>(result)
            assertEquals(RejectReasonCode.USER_DECLINED, rejected.reject.reasonCode)
            assertEquals(TransferState.REJECTED, fsm.currentState())
        }

    @Test
    fun `TC-FSAP-012 timeout de aprovacao retorna APPROVAL_TIMEOUT e transita FSM local para FAILED`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(logger = logger)
            val stalledHandler = TransferApprovalHandler { _: ApprovalRequestContext ->
                delay(500)
                TransferApprovalDecision.Approved()
            }

            application {
                val manager = ServerTransferSessionManager(
                    tokenManager = tokens,
                    fileSink = sink,
                    scope = this,
                    logger = logger,
                    approvalTimeoutMs = 120L,
                )
                configureFsapServer(
                    config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                    logger = logger,
                    transferServices = FsapTransferServerServices(
                        sessionManager = manager,
                        fileSink = sink,
                        approvalHandler = stalledHandler,
                        approvalTimeoutMs = 120L,
                    ),
                )
            }
            startApplication()

            val fsapClient = FsapHttpClient(
                localIdentity = SENDER_IDENTITY,
                logger = logger,
                approvalTimeoutMs = 200L,
                providedHttpClient = client,
            )
            val fsm = TransferStateMachine(transferId = "33333333-3333-4333-8333-333333333333", logger = logger)
            val result = fsapClient.prepareTransfer(
                endpoint = FsapPeerEndpoint(host = "localhost", port = 80),
                transferId = fsm.transferId,
                stateMachine = fsm,
                files = listOf(FileItemDto("f1", "lento.bin", 0L, SAMPLE_SHA)),
            )

            val failed = assertIs<PrepareTransferResult.Failed>(result)
            assertEquals(FsapErrorCode.APPROVAL_TIMEOUT, failed.errorCode)
            assertEquals(TransferState.FAILED, fsm.currentState())
        }

    @Test
    fun `espaco insuficiente no servidor e nome invalido levam FSM local para FAILED`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            val smallSink = InMemoryFileSink(availableBytes = 1024L)
            val tokens = TransferTokenManager(logger = logger)

            application {
                val manager = ServerTransferSessionManager(
                    tokenManager = tokens,
                    fileSink = smallSink,
                    scope = this,
                    logger = logger,
                )
                configureFsapServer(
                    config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                    logger = logger,
                    transferServices = FsapTransferServerServices(
                        sessionManager = manager,
                        fileSink = smallSink,
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

            // Validacao local de Path Traversal falha antes mesmo de transmitir
            val badNameFsm = TransferStateMachine(transferId = "44444444-4444-4444-8444-444444444444")
            val badNameRes = fsapClient.prepareTransfer(
                endpoint = FsapPeerEndpoint("localhost", 80),
                transferId = badNameFsm.transferId,
                stateMachine = badNameFsm,
                files = listOf(FileItemDto("f1", "../segredo.txt", 10L, SAMPLE_SHA)),
            )
            val badNameFailed = assertIs<PrepareTransferResult.Failed>(badNameRes)
            assertEquals(FsapErrorCode.INVALID_FILENAME, badNameFailed.errorCode)
            assertEquals(TransferState.FAILED, badNameFsm.currentState())

            // Rejeicao 507 Insufficient Storage no servidor
            val noSpaceFsm = TransferStateMachine(transferId = "55555555-5555-4555-8555-555555555555")
            val noSpaceRes = fsapClient.prepareTransfer(
                endpoint = FsapPeerEndpoint("localhost", 80),
                transferId = noSpaceFsm.transferId,
                stateMachine = noSpaceFsm,
                files = listOf(FileItemDto("f1", "grande.iso", 500_000L, SAMPLE_SHA)),
            )
            val noSpaceFailed = assertIs<PrepareTransferResult.Failed>(noSpaceRes)
            assertEquals(FsapErrorCode.INSUFFICIENT_STORAGE, noSpaceFailed.errorCode)
            assertEquals(TransferState.FAILED, noSpaceFsm.currentState())
        }
}

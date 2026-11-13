package br.edu.fileshare.network.server.routes

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.InteractiveTransferApprovalCoordinator
import br.edu.fileshare.network.server.RejectAllTransferApprovalHandler
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferApprovalDecision
import br.edu.fileshare.network.server.TransferApprovalHandler
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.configureFsapServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
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
import br.edu.fileshare.protocol.model.RejectReasonCode
import br.edu.fileshare.protocol.model.TransferAcceptPayload
import br.edu.fileshare.protocol.model.TransferRejectPayload
import br.edu.fileshare.protocol.model.TransferRequestPayload
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.storage.InMemoryFileSink
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferPrepareRouteTest {

    private companion object {
        val SERVER_IDENTITY = DeviceIdentity(
            deviceId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            alias = "Desktop Receptor",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
        )
        const val SENDER_UUID = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        const val VALID_SHA256 = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"

        fun requestEnvelope(
            transferId: String = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            fileName: String = "relatorio.pdf",
            sizeBytes: Long = 1024L,
        ) = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_REQUEST,
            senderId = SENDER_UUID,
            payload = TransferRequestPayload(
                transferId = transferId,
                senderAlias = "Android Remetente",
                senderDeviceType = DeviceType.MOBILE,
                totalBytes = sizeBytes,
                files = listOf(
                    FileItemDto(
                        fileId = "file-001",
                        fileName = fileName,
                        sizeBytes = sizeBytes,
                        sha256 = VALID_SHA256,
                        mimeType = "application/pdf",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `TC-FSAP-008 aprova solicitacao via coordenador interativo e retorna TRANSFER_ACCEPT com token`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            val sink = InMemoryFileSink()
            val tokens = TransferTokenManager(logger = logger)
            val coordinator = InteractiveTransferApprovalCoordinator()
            lateinit var sessionManager: ServerTransferSessionManager

            application {
                sessionManager = ServerTransferSessionManager(
                    tokenManager = tokens,
                    fileSink = sink,
                    scope = this,
                    logger = logger,
                )
                configureFsapServer(
                    config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                    logger = logger,
                    transferServices = FsapTransferServerServices(
                        sessionManager = sessionManager,
                        fileSink = sink,
                        approvalHandler = coordinator,
                    ),
                )
            }

            val env = requestEnvelope()
            val response = coroutineScope {
                val responseDeferred = async {
                    client.post(FsapConstants.Routes.TRANSFERS_PREPARE) {
                        header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                        contentType(ContentType.Application.Json)
                        setBody(FsapJson.encodeEnvelope(env))
                    }
                }

                // Aguarda prompt chegar a fila reativa e decide explicitamente
                while (coordinator.pendingPrompts.value.isEmpty()) {
                    delay(10L)
                }
                assertEquals(env.payload.transferId, coordinator.pendingPrompts.value.first().transferId)
                assertTrue(
                    coordinator.submitDecision(
                        env.payload.transferId,
                        TransferApprovalDecision.Approved(listOf("file-001")),
                    ),
                )

                responseDeferred.await()
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val accept = FsapJson.decodeEnvelope<TransferAcceptPayload>(response.bodyAsText())
            assertEquals(FsapMessageType.TRANSFER_ACCEPT.name, accept.type)
            assertEquals(env.payload.transferId, accept.payload.transferId)
            assertTrue(accept.payload.transferToken.length in 32..128)
            assertEquals(listOf("file-001"), accept.payload.acceptedFileIds)
            assertEquals(1, tokens.activeCount())
            assertEquals(TransferState.ACCEPTED, sessionManager.findSnapshot(env.payload.transferId)?.state)
        }

    @Test
    fun `TC-FSAP-009 e TC-FSAP-010 rejeitam path traversal com 400 e falta de espaco com 507 antes do prompt`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            val sink = InMemoryFileSink(availableBytes = 10_000L) // < 16 MiB
            val approvalInvoked = AtomicBoolean(false)
            val spyHandler = TransferApprovalHandler {
                approvalInvoked.set(true)
                TransferApprovalDecision.Approved()
            }

            application {
                val manager = ServerTransferSessionManager(
                    tokenManager = TransferTokenManager(logger = logger),
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
                        approvalHandler = spyHandler,
                    ),
                )
            }

            // Path Traversal -> 400 INVALID_FILENAME
            val traversalResp = client.post(FsapConstants.Routes.TRANSFERS_PREPARE) {
                header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                contentType(ContentType.Application.Json)
                setBody(FsapJson.encodeEnvelope(requestEnvelope(fileName = "../../etc/passwd")))
            }
            assertEquals(HttpStatusCode.BadRequest, traversalResp.status)
            val traversalErr = FsapJson.decodeEnvelope<ErrorPayload>(traversalResp.bodyAsText())
            assertEquals(FsapErrorCode.INVALID_FILENAME.code, traversalErr.payload.errorCode)

            // Espaco insuficiente -> 507 INSUFFICIENT_STORAGE
            val lowDiskResp = client.post(FsapConstants.Routes.TRANSFERS_PREPARE) {
                header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                contentType(ContentType.Application.Json)
                setBody(FsapJson.encodeEnvelope(requestEnvelope(sizeBytes = 5_000L)))
            }
            assertEquals(HttpStatusCode.InsufficientStorage, lowDiskResp.status)
            val diskErr = FsapJson.decodeEnvelope<ErrorPayload>(lowDiskResp.bodyAsText())
            assertEquals(FsapErrorCode.INSUFFICIENT_STORAGE.code, diskErr.payload.errorCode)
            assertFalse(approvalInvoked.get())
        }

    @Test
    fun `TC-FSAP-011 recusa explicita retorna 403 TRANSFER_REJECT sem emitir token`() = testApplication {
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
                    approvalHandler = RejectAllTransferApprovalHandler(
                        reasonCode = RejectReasonCode.USER_DECLINED,
                        reasonMessage = "Usuario recusou recebimento",
                    ),
                ),
            )
        }

        val env = requestEnvelope()
        val resp = client.post(FsapConstants.Routes.TRANSFERS_PREPARE) {
            header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
            contentType(ContentType.Application.Json)
            setBody(FsapJson.encodeEnvelope(env))
        }

        assertEquals(HttpStatusCode.Forbidden, resp.status)
        val reject = FsapJson.decodeEnvelope<TransferRejectPayload>(resp.bodyAsText())
        assertEquals(FsapMessageType.TRANSFER_REJECT.name, reject.type)
        assertEquals(RejectReasonCode.USER_DECLINED, reject.payload.reasonCode)
        assertEquals(0, tokens.activeCount())
        assertEquals(TransferState.REJECTED, manager.findSnapshot(env.payload.transferId)?.state)
    }

    @Test
    fun `TC-FSAP-012 timeout de aprovacao retorna 408 APPROVAL_TIMEOUT e excesso retorna 403 BUSY`() =
        testApplication {
            val logger = InMemoryFsapLogger()
            val sink = InMemoryFileSink()
            val slowHandler = TransferApprovalHandler {
                delay(900L)
                TransferApprovalDecision.Approved()
            }
            lateinit var manager: ServerTransferSessionManager

            application {
                manager = ServerTransferSessionManager(
                    tokenManager = TransferTokenManager(logger = logger),
                    fileSink = sink,
                    scope = this,
                    logger = logger,
                    maxConcurrentSessions = 1,
                    approvalTimeoutMs = 450L,
                )
                configureFsapServer(
                    config = FsapHttpServerConfig(identity = SERVER_IDENTITY),
                    logger = logger,
                    transferServices = FsapTransferServerServices(
                        sessionManager = manager,
                        fileSink = sink,
                        approvalHandler = slowHandler,
                        approvalTimeoutMs = 400L,
                    ),
                )
            }
            startApplication()

            val id1 = "11111111-1111-4111-8111-111111111111"
            val id2 = "22222222-2222-4222-8222-222222222222"

            // Sessao 1 entra e espera 500ms; dispara sessao 2 simultanea -> 403 BUSY
            val firstResp = coroutineScope {
                val firstReq = async {
                    client.post(FsapConstants.Routes.TRANSFERS_PREPARE) {
                        header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                        contentType(ContentType.Application.Json)
                        setBody(FsapJson.encodeEnvelope(requestEnvelope(transferId = id1)))
                    }
                }

                while (manager.findSnapshot(id1)?.state != TransferState.PENDING_APPROVAL) {
                    delay(10L)
                }

                val secondResp = client.post(FsapConstants.Routes.TRANSFERS_PREPARE) {
                    header(FsapHeaders.VERSION, FsapConstants.PROTOCOL_VERSION)
                    contentType(ContentType.Application.Json)
                    setBody(FsapJson.encodeEnvelope(requestEnvelope(transferId = id2)))
                }
                assertEquals(HttpStatusCode.Forbidden, secondResp.status)
                val busyPayload = FsapJson.decodeEnvelope<TransferRejectPayload>(secondResp.bodyAsText())
                assertEquals(RejectReasonCode.BUSY, busyPayload.payload.reasonCode)

                firstReq.await()
            }
            assertEquals(HttpStatusCode.RequestTimeout, firstResp.status)
            val err = FsapJson.decodeEnvelope<ErrorPayload>(firstResp.bodyAsText())
            assertEquals(FsapErrorCode.APPROVAL_TIMEOUT.code, err.payload.errorCode)
            assertEquals(TransferState.FAILED, manager.findSnapshot(id1)?.state)

            // Valida ainda que AutoAcceptTransferApprovalHandler aceita nova transferencia apos timeout liberar vaga
            val decision = AutoAcceptTransferApprovalHandler.requestApproval(
                br.edu.fileshare.network.server.ApprovalRequestContext(id2, SENDER_UUID, requestEnvelope(id2).payload, 0L),
            )
            assertTrue(decision is TransferApprovalDecision.Approved)
        }
}


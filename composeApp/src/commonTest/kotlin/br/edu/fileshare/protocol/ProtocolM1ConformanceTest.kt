package br.edu.fileshare.protocol

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.core.logging.LogLevel
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapErrorFactory
import br.edu.fileshare.protocol.model.AnnouncePayload
import br.edu.fileshare.protocol.model.CancelledBy
import br.edu.fileshare.protocol.model.CompletedFileSummary
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.ErrorPayload
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.FsapEnvelope
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapHeaders
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.IdentifyPayload
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.model.TransferAcceptPayload
import br.edu.fileshare.protocol.model.TransferCancelPayload
import br.edu.fileshare.protocol.model.TransferCompletePayload
import br.edu.fileshare.protocol.model.TransferFileStatusPayload
import br.edu.fileshare.protocol.model.TransferRequestPayload
import br.edu.fileshare.protocol.statemachine.InvalidStateTransitionException
import br.edu.fileshare.protocol.statemachine.LocalNodeEvent
import br.edu.fileshare.protocol.statemachine.LocalNodeState
import br.edu.fileshare.protocol.statemachine.LocalNodeStateMachine
import br.edu.fileshare.protocol.statemachine.PeerConnectionEvent
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import br.edu.fileshare.protocol.statemachine.PeerConnectionStateMachine
import br.edu.fileshare.protocol.statemachine.TransferEvent
import br.edu.fileshare.protocol.statemachine.TransferState
import br.edu.fileshare.protocol.statemachine.TransferStateMachine
import br.edu.fileshare.protocol.validation.FsapMessageValidator
import br.edu.fileshare.protocol.validation.ValidationResult
import br.edu.fileshare.storage.FileNameSanitizer
import br.edu.fileshare.storage.FileNameValidationResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Suite consolidada de conformidade Nível 1 do M1 (`TASK-TEST-001`):
 * valida contratos de envelope, serializacao JSON UTF-8, versionamento,
 * sanitizacao anti-path-traversal, catalogo de 17 erros e maquinas de estados.
 */
class ProtocolM1ConformanceTest {

    private companion object {
        const val SENDER_UUID = "550e8400-e29b-41d4-a716-446655440000"
        const val TRANSFER_UUID = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
        const val EMPTY_FILE_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val SAMPLE_SHA256 =
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
    }

    @Test
    fun `TC-FSAP-001 envelope ANNOUNCE com UTF-8 e campos extras desconhecidos faz round-trip valido`() {
        val rawWithExtraKeys = """
            {
              "protocol": "FSAP",
              "version": "1.0",
              "type": "ANNOUNCE",
              "messageId": "${PlatformUuidGenerator.nextUuidV4()}",
              "timestamp": "2026-11-12T23:00:00Z",
              "senderId": "$SENDER_UUID",
              "futureEnvelopeField": 42,
              "payload": {
                "alias": "Estação Café 🚀",
                "deviceType": "DESKTOP",
                "platform": "LINUX",
                "httpPort": 53317,
                "capabilities": ["MULTI_FILE", "SHA256_STREAM"],
                "futurePayloadFlag": true
              }
            }
        """.trimIndent()

        val decoded = FsapJson.decodeEnvelope<AnnouncePayload>(rawWithExtraKeys)
        assertIs<ValidationResult.Valid>(FsapMessageValidator.validateAnnounce(decoded))
        assertEquals("Estação Café 🚀", decoded.payload.alias)
        assertEquals(DeviceType.DESKTOP, decoded.payload.deviceType)
        assertEquals(PlatformType.LINUX, decoded.payload.platform)

        val encoded = FsapJson.encodeEnvelope(decoded)
        val reDecoded = FsapJson.decodeEnvelope<AnnouncePayload>(encoded)
        assertEquals(decoded, reDecoded)
    }

    @Test
    fun `TC-FSAP-007 rejeita versao MAJOR incompativel com FSAP-ERR-505 e aceita MINOR diferente`() {
        assertIs<ValidationResult.Valid>(FsapMessageValidator.validateProtocolVersion("1.0"))
        assertIs<ValidationResult.Valid>(FsapMessageValidator.validateProtocolVersion("1.9"))

        val unsupportedMajor = FsapMessageValidator.validateProtocolVersion("2.0")
        assertIs<ValidationResult.Invalid>(unsupportedMajor)
        assertEquals(FsapErrorCode.UNSUPPORTED_VERSION, unsupportedMajor.errorCode)

        val identifyV2 = FsapEnvelope(
            protocol = FsapConstants.PROTOCOL_NAME,
            version = "2.0",
            type = FsapMessageType.IDENTIFY.name,
            messageId = PlatformUuidGenerator.nextUuidV4(),
            timestamp = "2026-11-12T23:00:00Z",
            senderId = SENDER_UUID,
            payload = IdentifyPayload(
                alias = "Cliente v2",
                deviceType = DeviceType.MOBILE,
                platform = PlatformType.ANDROID,
                httpPort = 53317,
            ),
        )
        val envelopeCheck = FsapMessageValidator.validateIdentify(identifyV2)
        assertIs<ValidationResult.Invalid>(envelopeCheck)
        assertEquals(FsapErrorCode.UNSUPPORTED_VERSION, envelopeCheck.errorCode)
        assertEquals(505, envelopeCheck.errorCode.httpStatus)
    }

    @Test
    fun `TC-FSAP-009 manifesto de arquivo zero bytes com hash vazio SHA-256 e aceito e path traversal e bloqueado`() {
        val emptyFileManifest = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_REQUEST,
            senderId = SENDER_UUID,
            payload = TransferRequestPayload(
                transferId = TRANSFER_UUID,
                senderAlias = "Computador Lab",
                senderDeviceType = DeviceType.DESKTOP,
                totalBytes = 0L,
                files = listOf(
                    FileItemDto(
                        fileId = "f1",
                        fileName = "vazio (cópia).txt",
                        sizeBytes = 0L,
                        sha256 = EMPTY_FILE_SHA256,
                    )
                ),
            ),
        )
        assertIs<ValidationResult.Valid>(FsapMessageValidator.validateTransferRequest(emptyFileManifest))

        val traversalManifest = emptyFileManifest.copy(
            payload = emptyFileManifest.payload.copy(
                files = listOf(
                    FileItemDto(
                        fileId = "f1",
                        fileName = "../segredo/etc_passwd",
                        sizeBytes = 10L,
                        sha256 = SAMPLE_SHA256,
                    )
                ),
                totalBytes = 10L,
            ),
        )
        val invalid = FsapMessageValidator.validateTransferRequest(traversalManifest)
        assertIs<ValidationResult.Invalid>(invalid)
        assertEquals(FsapErrorCode.INVALID_FILENAME, invalid.errorCode)
        assertEquals(400, invalid.errorCode.httpStatus)
    }

    @Test
    fun `TC-FSAP-023 todas as FSMs executam ciclo completo valido, registram logs e bloqueiam transicoes ilegais`() =
        runTest {
            val logger = InMemoryFsapLogger()

            // FSM 1: LocalNodeStateMachine
            val nodeFsm = LocalNodeStateMachine(nodeId = SENDER_UUID, logger = logger)
            assertEquals(LocalNodeState.STOPPED, nodeFsm.currentState())
            nodeFsm.transition(LocalNodeEvent.CMD_START_NODE)
            nodeFsm.transition(LocalNodeEvent.EVT_PORTS_BOUND)
            nodeFsm.transition(LocalNodeEvent.EVT_INITIAL_ANNOUNCE_SENT)
            assertEquals(LocalNodeState.AVAILABLE, nodeFsm.currentState())
            assertFailsWith<InvalidStateTransitionException> {
                nodeFsm.transition(LocalNodeEvent.EVT_PORTS_BOUND)
            }

            // FSM 2: PeerConnectionStateMachine
            val peerFsm = PeerConnectionStateMachine(peerId = "peer-1", logger = logger)
            peerFsm.transition(PeerConnectionEvent.EVT_UDP_ANNOUNCE_RECEIVED)
            peerFsm.transition(PeerConnectionEvent.CMD_OPEN_PEER_CONNECTION)
            peerFsm.transition(PeerConnectionEvent.EVT_IDENTIFY_SUCCESS)
            assertEquals(PeerConnectionState.CONNECTED, peerFsm.currentState())
            peerFsm.transition(PeerConnectionEvent.EVT_TCP_CONNECTION_LOST)
            assertEquals(PeerConnectionState.DISCONNECTED, peerFsm.currentState())

            // FSM 3: TransferStateMachine completo + tentativa pos-estado terminal
            val transferFsm = TransferStateMachine(transferId = TRANSFER_UUID, logger = logger)
            transferFsm.transition(TransferEvent.TRANSFER_REQUEST_SENT_OR_RECEIVED)
            transferFsm.transition(TransferEvent.TRANSFER_ACCEPTED)
            transferFsm.transition(TransferEvent.STREAM_STARTED)
            transferFsm.transition(TransferEvent.CHUNK_TRANSFERRED)
            transferFsm.transition(TransferEvent.ALL_BYTES_RECEIVED)
            transferFsm.transition(TransferEvent.FILE_VERIFIED_MORE_REMAIN)
            transferFsm.transition(TransferEvent.CHUNK_TRANSFERRED)
            transferFsm.transition(TransferEvent.ALL_BYTES_RECEIVED)
            transferFsm.transition(TransferEvent.ALL_FILES_VERIFIED_AND_COMPLETED)
            assertEquals(TransferState.COMPLETED, transferFsm.currentState())
            assertTrue(TransferState.COMPLETED.isTerminal)

            val rejectedAfterCompleted = assertFailsWith<InvalidStateTransitionException> {
                transferFsm.transition(TransferEvent.CANCEL_TRANSFER)
            }
            assertEquals(FsapErrorCode.INVALID_STATE_TRANSITION, rejectedAfterCompleted.errorCode)
            assertEquals(TRANSFER_UUID, rejectedAfterCompleted.transferId)

            val warnLogs = logger.snapshot().filter { it.level == LogLevel.WARN }
            assertEquals(2, warnLogs.size)
        }

    @Test
    fun `TC-FSAP-024 sanitizacao de nomes e politica de nao-sobrescrita deterministica`() {
        val unsafeNames = listOf(
            "../evil.txt",
            "a\\b.txt",
            "a/b.txt",
            "CON.txt",
            "lpt1",
            "trailingdot.",
            "trailingspace ",
            "null\u0000byte.txt",
            "colon:name.txt",
            "",
            "a".repeat(256),
        )
        unsafeNames.forEach { name ->
            val result = FileNameSanitizer.validate(name)
            assertIs<FileNameValidationResult.Unsafe>(result, "Esperava rejeicao para '$name'")
        }

        // UTF-8 de exatos 255 bytes deve ser aceito; 256 bytes rejeitado
        val exactly255Bytes = "é".repeat(127) + "a" // 127 * 2 + 1 = 255 bytes UTF-8
        assertIs<FileNameValidationResult.Safe>(FileNameSanitizer.validate(exactly255Bytes))
        val bytes256 = "é".repeat(128)
        assertIs<FileNameValidationResult.Unsafe>(FileNameSanitizer.validate(bytes256))

        val existing = setOf("relatorio.pdf", "relatorio (1).pdf", "README", "README (1)")
        assertEquals(
            "relatorio (2).pdf",
            FileNameSanitizer.resolveNonOverwritingName("relatorio.pdf") { it in existing },
        )
        assertEquals(
            "README (2)",
            FileNameSanitizer.resolveNonOverwritingName("README") { it in existing },
        )
        assertFalse("CON" in existing && FileNameSanitizer.validate("CON") is FileNameValidationResult.Safe)
    }

    @Test
    fun `catalogo de 17 erros, cabecalhos canonicos e mensagens de ciclo de vida fazem round-trip consistente`() {
        assertEquals(17, FsapErrorCode.entries.size)
        assertEquals("X-FSAP-Version", FsapHeaders.VERSION)
        assertEquals("X-FSAP-Transfer-Token", FsapHeaders.TRANSFER_TOKEN)
        assertEquals("X-FSAP-File-SHA256", FsapHeaders.FILE_SHA256)

        FsapErrorCode.entries.forEach { err ->
            val envelope = FsapErrorFactory.toEnvelope(
                senderId = SENDER_UUID,
                errorCode = err,
                transferId = TRANSFER_UUID,
            )
            val json = FsapJson.encodeEnvelope(envelope)
            val decoded = FsapJson.decodeEnvelope<ErrorPayload>(json)
            assertEquals(err.code, decoded.payload.errorCode)
            assertEquals(err.httpStatus, decoded.payload.httpStatus)
            assertEquals(err, FsapErrorCode.fromCode(decoded.payload.errorCode))
        }

        val acceptEnv = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_ACCEPT,
            senderId = SENDER_UUID,
            payload = TransferAcceptPayload(
                transferId = TRANSFER_UUID,
                transferToken = "tok-ephemeral-123",
                acceptedFileIds = listOf("f1"),
            ),
        )
        val fileStatusEnv = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_STATUS,
            senderId = SENDER_UUID,
            payload = TransferFileStatusPayload(
                transferId = TRANSFER_UUID,
                fileId = "f1",
                state = "VERIFYING",
                fileVerified = true,
                bytesReceived = 1024L,
                calculatedSha256 = SAMPLE_SHA256,
            ),
        )
        val cancelEnv = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_CANCEL,
            senderId = SENDER_UUID,
            payload = TransferCancelPayload(
                transferId = TRANSFER_UUID,
                cancelledBy = CancelledBy.SENDER,
                reason = "Cancelado pelo usuario",
            ),
        )
        val completeEnv = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_COMPLETE,
            senderId = SENDER_UUID,
            payload = TransferCompletePayload(
                transferId = TRANSFER_UUID,
                completedFiles = listOf(CompletedFileSummary("f1", SAMPLE_SHA256)),
                state = "COMPLETED",
            ),
        )

        assertEquals(
            acceptEnv,
            FsapJson.decodeEnvelope<TransferAcceptPayload>(FsapJson.encodeEnvelope(acceptEnv)),
        )
        assertEquals(
            fileStatusEnv,
            FsapJson.decodeEnvelope<TransferFileStatusPayload>(FsapJson.encodeEnvelope(fileStatusEnv)),
        )
        assertEquals(
            cancelEnv,
            FsapJson.decodeEnvelope<TransferCancelPayload>(FsapJson.encodeEnvelope(cancelEnv)),
        )
        assertEquals(
            completeEnv,
            FsapJson.decodeEnvelope<TransferCompletePayload>(FsapJson.encodeEnvelope(completeEnv)),
        )
        assertEquals(
            DeviceIdentity.DEFAULT_CAPABILITIES,
            DeviceIdentity(
                deviceId = SENDER_UUID,
                alias = "No Local",
                deviceType = DeviceType.SERVER,
                platform = PlatformType.LINUX,
            ).capabilities,
        )
    }
}

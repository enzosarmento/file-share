package br.edu.fileshare.protocol.validation

import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.model.AnnouncePayload
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.FileItemDto
import br.edu.fileshare.protocol.model.FsapEnvelope
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.IdentifyPayload
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.model.TransferRequestPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FsapMessageValidatorTest {

    private val validMsgId = "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"
    private val validSenderId = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    private val validTransferId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val validSha = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    private val validTimestamp = "2026-11-12T20:00:05Z"

    @Test
    fun validateValidAnnouncePassesTcFsap001() {
        val env = FsapEnvelope(
            protocol = "FSAP",
            version = "1.0",
            type = FsapMessageType.ANNOUNCE.name,
            messageId = validMsgId,
            timestamp = validTimestamp,
            senderId = validSenderId,
            payload = AnnouncePayload(
                alias = "Desktop-Ubuntu-Sala3",
                deviceType = DeviceType.DESKTOP,
                platform = PlatformType.LINUX,
                httpPort = 53317,
            ),
        )
        assertEquals(ValidationResult.Valid, FsapMessageValidator.validateAnnounce(env))
    }

    @Test
    fun rejectInvalidProtocolPortsAndUuidTcFsap003() {
        val wrongProtocol = FsapEnvelope(
            protocol = "OUTRO",
            version = "1.0",
            type = "ANNOUNCE",
            messageId = validMsgId,
            timestamp = validTimestamp,
            senderId = validSenderId,
            payload = AnnouncePayload("Ok", DeviceType.DESKTOP, PlatformType.LINUX, 53317),
        )
        val resProto = assertIs<ValidationResult.Invalid>(FsapMessageValidator.validateAnnounce(wrongProtocol))
        assertEquals(FsapErrorCode.MALFORMED_MESSAGE, resProto.errorCode)

        val invalidPort = wrongProtocol.copy(
            protocol = "FSAP",
            payload = AnnouncePayload("Ok", DeviceType.DESKTOP, PlatformType.LINUX, 99999),
        )
        val resPort = assertIs<ValidationResult.Invalid>(FsapMessageValidator.validateAnnounce(invalidPort))
        assertEquals(FsapErrorCode.MALFORMED_MESSAGE, resPort.errorCode)

        val invalidUuid = wrongProtocol.copy(protocol = "FSAP", messageId = "nao-e-um-uuid")
        val resUuid = assertIs<ValidationResult.Invalid>(FsapMessageValidator.validateAnnounce(invalidUuid))
        assertEquals(FsapErrorCode.MALFORMED_MESSAGE, resUuid.errorCode)

        val blankAlias = wrongProtocol.copy(
            protocol = "FSAP",
            payload = AnnouncePayload("   ", DeviceType.DESKTOP, PlatformType.LINUX, 53317),
        )
        assertIs<ValidationResult.Invalid>(FsapMessageValidator.validateAnnounce(blankAlias))
    }

    @Test
    fun rejectIncompatibleMajorVersionOnIdentifyTcFsap007() {
        val incompatibleMajor = FsapEnvelope(
            protocol = "FSAP",
            version = "2.0",
            type = "IDENTIFY",
            messageId = validMsgId,
            timestamp = validTimestamp,
            senderId = validSenderId,
            payload = IdentifyPayload("Cliente-V2", DeviceType.DESKTOP, PlatformType.LINUX, 53317),
        )
        val res = assertIs<ValidationResult.Invalid>(FsapMessageValidator.validateIdentify(incompatibleMajor))
        assertEquals(FsapErrorCode.UNSUPPORTED_VERSION, res.errorCode)

        val compatibleMinor = incompatibleMajor.copy(version = "1.4")
        assertEquals(ValidationResult.Valid, FsapMessageValidator.validateIdentify(compatibleMinor))
    }

    @Test
    fun validateTransferRequestChecksHashSumSizeAndFilename() {
        val valid = FsapEnvelope(
            protocol = "FSAP",
            version = "1.0",
            type = "TRANSFER_REQUEST",
            messageId = validMsgId,
            timestamp = validTimestamp,
            senderId = validSenderId,
            payload = TransferRequestPayload(
                transferId = validTransferId,
                senderAlias = "Desktop",
                senderDeviceType = DeviceType.DESKTOP,
                totalBytes = 2048L,
                files = listOf(
                    FileItemDto("file-001", "a.pdf", 1024L, validSha),
                    FileItemDto("file-002", "b.png", 1024L, validSha),
                ),
            ),
        )
        assertEquals(ValidationResult.Valid, FsapMessageValidator.validateTransferRequest(valid))

        val sumMismatch = valid.copy(payload = valid.payload.copy(totalBytes = 999L))
        assertIs<ValidationResult.Invalid>(FsapMessageValidator.validateTransferRequest(sumMismatch))

        val badShaUppercase = valid.copy(
            payload = valid.payload.copy(
                totalBytes = 1024L,
                files = listOf(FileItemDto("f1", "a.pdf", 1024L, validSha.uppercase())),
            )
        )
        assertIs<ValidationResult.Invalid>(FsapMessageValidator.validateTransferRequest(badShaUppercase))

        val pathTraversal = valid.copy(
            payload = valid.payload.copy(
                totalBytes = 1024L,
                files = listOf(FileItemDto("f1", "../../etc/passwd", 1024L, validSha)),
            )
        )
        val errPath = assertIs<ValidationResult.Invalid>(FsapMessageValidator.validateTransferRequest(pathTraversal))
        assertEquals(FsapErrorCode.INVALID_FILENAME, errPath.errorCode)

        val tooLarge = valid.copy(
            payload = valid.payload.copy(
                totalBytes = FsapConstants.MAX_FILE_SIZE_BYTES + 1L,
                files = listOf(
                    FileItemDto("f1", "huge.iso", FsapConstants.MAX_FILE_SIZE_BYTES + 1L, validSha)
                ),
            )
        )
        val errLarge = assertIs<ValidationResult.Invalid>(FsapMessageValidator.validateTransferRequest(tooLarge))
        assertEquals(FsapErrorCode.FILE_TOO_LARGE, errLarge.errorCode)
    }
}


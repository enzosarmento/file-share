package br.edu.fileshare.protocol.model

import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.protocol.FsapConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class TestFixedClock : Clock {
    override fun epochMillis(): Long = 1_762_978_500_000L
    override fun nowIsoUtc(): String = "2026-11-12T20:15:30Z"
}

class FsapPayloadsTest {

    private val senderId = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    private val transferId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    @Test
    fun parseNormativeJsonExamplesFromSpecification() {
        val announceJson = """
            {
              "protocol": "FSAP",
              "version": "1.0",
              "type": "ANNOUNCE",
              "messageId": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
              "timestamp": "2026-11-12T20:00:05Z",
              "senderId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
              "payload": {
                "alias": "Desktop-Ubuntu-Sala3",
                "deviceType": "DESKTOP",
                "platform": "LINUX",
                "httpPort": 53317,
                "capabilities": ["MULTI_FILE", "SHA256_STREAM"]
              }
            }
        """.trimIndent()
        val announce = FsapJson.decodeEnvelope<AnnouncePayload>(announceJson)
        assertEquals("Desktop-Ubuntu-Sala3", announce.payload.alias)
        assertEquals(DeviceType.DESKTOP, announce.payload.deviceType)
        assertEquals(PlatformType.LINUX, announce.payload.platform)
        assertEquals(53317, announce.payload.httpPort)

        val requestJson = """
            {
              "protocol": "FSAP",
              "version": "1.0",
              "type": "TRANSFER_REQUEST",
              "messageId": "550e8400-e29b-41d4-a716-446655440000",
              "timestamp": "2026-11-12T20:05:00Z",
              "senderId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
              "payload": {
                "transferId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                "senderAlias": "Desktop-Ubuntu-Sala3",
                "senderDeviceType": "DESKTOP",
                "totalBytes": 2097152,
                "files": [
                  {
                    "fileId": "file-001",
                    "fileName": "relatorio-redes-final.pdf",
                    "sizeBytes": 2097152,
                    "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                    "mimeType": "application/pdf"
                  }
                ]
              }
            }
        """.trimIndent()
        val request = FsapJson.decodeEnvelope<TransferRequestPayload>(requestJson)
        assertEquals(2097152L, request.payload.totalBytes)
        assertEquals(1, request.payload.files.size)
        assertEquals("relatorio-redes-final.pdf", request.payload.files.first().fileName)

        val fileStatusJson = """
            {
              "protocol": "FSAP",
              "version": "1.0",
              "type": "TRANSFER_STATUS",
              "messageId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
              "timestamp": "2026-11-12T20:16:10Z",
              "senderId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
              "payload": {
                "transferId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                "fileId": "file-001",
                "state": "VERIFYING",
                "fileVerified": true,
                "bytesReceived": 10485760,
                "calculatedSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
              }
            }
        """.trimIndent()
        val fileStatus = FsapJson.decodeEnvelope<TransferFileStatusPayload>(fileStatusJson)
        assertTrue(fileStatus.payload.fileVerified)
        assertEquals("VERIFYING", fileStatus.payload.state)

        val errorJson = """
            {
              "protocol": "FSAP",
              "version": "1.0",
              "type": "ERROR",
              "messageId": "c9a646d3-9c61-4cb7-bfcd-ee2522c8f633",
              "timestamp": "2026-11-12T20:05:02Z",
              "senderId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
              "payload": {
                "errorCode": "FSAP-ERR-400-INVALID_FILENAME",
                "httpStatus": 400,
                "category": "VALIDATION",
                "message": "Invalid file path",
                "transferId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                "fileId": "file-001",
                "recoverable": false
              }
            }
        """.trimIndent()
        val error = FsapJson.decodeEnvelope<ErrorPayload>(errorJson)
        assertEquals(ErrorCategory.VALIDATION, error.payload.category)
        assertEquals(400, error.payload.httpStatus)
    }

    @Test
    fun roundTripAllRemainingControlAndDiscoveryMessages() {
        val clock = TestFixedClock()

        val discover = FsapEnvelopeFactory.create(
            type = FsapMessageType.DISCOVER,
            senderId = senderId,
            payload = DiscoverPayload(requesterAlias = "Pixel-8", requesterHttpPort = 53317),
            clock = clock,
        )
        assertEquals(discover, FsapJson.decodeEnvelope<DiscoverPayload>(FsapJson.encodeEnvelope(discover)))

        val bye = FsapEnvelopeFactory.create(
            type = FsapMessageType.BYE,
            senderId = senderId,
            payload = ByePayload(reason = "USER_SHUTDOWN"),
            clock = clock,
        )
        assertEquals(bye, FsapJson.decodeEnvelope<ByePayload>(FsapJson.encodeEnvelope(bye)))

        val identify = FsapEnvelopeFactory.create(
            type = FsapMessageType.IDENTIFY,
            senderId = senderId,
            payload = IdentifyPayload(
                alias = "Windows-Lab",
                deviceType = DeviceType.DESKTOP,
                platform = PlatformType.WINDOWS,
                httpPort = 53319,
            ),
            clock = clock,
        )
        assertEquals(identify, FsapJson.decodeEnvelope<IdentifyPayload>(FsapJson.encodeEnvelope(identify)))

        val accept = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_ACCEPT,
            senderId = senderId,
            payload = TransferAcceptPayload(
                transferId = transferId,
                transferToken = "tok-0123456789abcdef0123456789abcdef",
                acceptedFileIds = listOf("file-001", "file-002"),
            ),
            clock = clock,
        )
        assertEquals(FsapConstants.CHUNK_SIZE_BYTES, accept.payload.chunkSizeBytes)
        assertEquals(accept, FsapJson.decodeEnvelope<TransferAcceptPayload>(FsapJson.encodeEnvelope(accept)))

        val reject = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_REJECT,
            senderId = senderId,
            payload = TransferRejectPayload(
                transferId = transferId,
                reasonCode = RejectReasonCode.USER_DECLINED,
                reasonMessage = "User tapped decline",
            ),
            clock = clock,
        )
        assertEquals(reject, FsapJson.decodeEnvelope<TransferRejectPayload>(FsapJson.encodeEnvelope(reject)))

        val status = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_STATUS,
            senderId = senderId,
            payload = TransferStatusPayload(
                transferId = transferId,
                state = "TRANSFERRING",
                totalBytesTransferred = 1024L,
                totalBytes = 4096L,
                currentFileId = "file-001",
                filesCompleted = 0,
                totalFiles = 1,
            ),
            clock = clock,
        )
        assertEquals(status, FsapJson.decodeEnvelope<TransferStatusPayload>(FsapJson.encodeEnvelope(status)))

        val cancel = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_CANCEL,
            senderId = senderId,
            payload = TransferCancelPayload(
                transferId = transferId,
                cancelledBy = CancelledBy.SENDER,
                reason = "USER_REQUESTED",
            ),
            clock = clock,
        )
        assertEquals(cancel, FsapJson.decodeEnvelope<TransferCancelPayload>(FsapJson.encodeEnvelope(cancel)))

        val complete = FsapEnvelopeFactory.create(
            type = FsapMessageType.TRANSFER_COMPLETE,
            senderId = senderId,
            payload = TransferCompletePayload(
                transferId = transferId,
                completedFiles = listOf(
                    CompletedFileSummary(
                        fileId = "file-001",
                        sha256 = "a".repeat(64),
                    )
                ),
                state = "COMPLETED",
            ),
            clock = clock,
        )
        assertEquals(complete, FsapJson.decodeEnvelope<TransferCompletePayload>(FsapJson.encodeEnvelope(complete)))

        assertEquals("X-FSAP-Version", FsapHeaders.VERSION)
        assertEquals("X-FSAP-Transfer-Token", FsapHeaders.TRANSFER_TOKEN)
        assertEquals("X-FSAP-File-SHA256", FsapHeaders.FILE_SHA256)
    }
}


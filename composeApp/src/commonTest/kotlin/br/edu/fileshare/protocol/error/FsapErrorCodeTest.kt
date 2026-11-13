package br.edu.fileshare.protocol.error

import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.protocol.error.FsapErrorFactory.toErrorEnvelope
import br.edu.fileshare.protocol.model.ErrorCategory
import br.edu.fileshare.protocol.model.ErrorPayload
import br.edu.fileshare.protocol.model.FsapJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class ErrorTestClock : Clock {
    override fun epochMillis(): Long = 1_762_979_400_000L
    override fun nowIsoUtc(): String = "2026-11-12T20:30:00Z"
}

class FsapErrorCodeTest {

    @Test
    fun allSeventeenCanonicalCodesArePresentAndUnique() {
        assertEquals(17, FsapErrorCode.entries.size)
        val distinctCodes = FsapErrorCode.entries.map { it.code }.toSet()
        assertEquals(17, distinctCodes.size)

        FsapErrorCode.entries.forEach { error ->
            assertTrue(error.code.startsWith("FSAP-ERR-${error.httpStatus}-"), "Bad format: ${error.code}")
            assertSame(error, FsapErrorCode.fromCode(error.code))
        }
        assertNull(FsapErrorCode.fromCode("UNKNOWN"))
    }

    @Test
    fun verifyExactStatusAndCategoryMappingPerSpecification() {
        assertEquals(400, FsapErrorCode.MALFORMED_MESSAGE.httpStatus)
        assertEquals(ErrorCategory.VALIDATION, FsapErrorCode.INVALID_FILENAME.category)
        assertEquals(401, FsapErrorCode.UNAUTHORIZED_TRANSFER.httpStatus)
        assertEquals(403, FsapErrorCode.TRANSFER_REJECTED.httpStatus)
        assertEquals(404, FsapErrorCode.DEVICE_NOT_FOUND.httpStatus)
        assertEquals(404, FsapErrorCode.FILE_NOT_FOUND.httpStatus)
        assertEquals(408, FsapErrorCode.APPROVAL_TIMEOUT.httpStatus)
        assertEquals(408, FsapErrorCode.STREAM_TIMEOUT.httpStatus)
        assertEquals(409, FsapErrorCode.INVALID_STATE_TRANSITION.httpStatus)
        assertEquals(413, FsapErrorCode.FILE_TOO_LARGE.httpStatus)
        assertEquals(422, FsapErrorCode.HASH_MISMATCH.httpStatus)
        assertEquals(499, FsapErrorCode.TRANSFER_CANCELLED.httpStatus)
        assertEquals(500, FsapErrorCode.INTERNAL_ERROR.httpStatus)
        assertEquals(503, FsapErrorCode.CONNECTION_LOST.httpStatus)
        assertEquals(505, FsapErrorCode.UNSUPPORTED_VERSION.httpStatus)
        assertEquals(507, FsapErrorCode.INSUFFICIENT_STORAGE.httpStatus)
    }

    @Test
    fun errorFactoryBuildsAndSerializesCanonicalEnvelope() {
        val ex = FsapProtocolException(
            errorCode = FsapErrorCode.HASH_MISMATCH,
            message = "Digest mismatch",
            transferId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            fileId = "file-001",
        )
        val envelope = ex.toErrorEnvelope(
            senderId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            clock = ErrorTestClock(),
        )
        val json = FsapJson.encodeEnvelope(envelope)
        val decoded = FsapJson.decodeEnvelope<ErrorPayload>(json)

        assertEquals("ERROR", decoded.type)
        assertEquals("FSAP-ERR-422-HASH_MISMATCH", decoded.payload.errorCode)
        assertEquals(422, decoded.payload.httpStatus)
        assertEquals(ErrorCategory.INTEGRITY, decoded.payload.category)
        assertEquals("file-001", decoded.payload.fileId)
        assertFalse(decoded.payload.recoverable)
    }
}


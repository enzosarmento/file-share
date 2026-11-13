package br.edu.fileshare.network.streaming

import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapProtocolException
import br.edu.fileshare.storage.InMemoryFileSink
import br.edu.fileshare.storage.InMemoryFileSource
import br.edu.fileshare.storage.SyntheticPatternFileSource
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IncrementalSha256ChannelTest {

    private companion object {
        const val TRANSFER_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val HASH_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val HASH_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        const val HASH_1KB_A =
            "6ab72eeb9e77b07540897e0c8d6d23ec8eef0f8c3a47e1b3f4e93443d9536bed"
        const val HASH_50MB_ZERO =
            "8565a714dca840f8652c5bae9249ab05f5fb5a4f9f13fbe23304b10f68252da2"
    }

    @Test
    fun `TC-FSAP-025 calculo SHA-256 em streaming coincide com vetores 0B abc 1KB e 50MB com buffer fixo 64KB`() =
        runTest {
            val emptySummary = IncrementalSha256Channel.computeDigest(
                SyntheticPatternFileSource("vazio.bin", 0L),
            )
            assertEquals(HASH_EMPTY, emptySummary.sha256Hex)
            assertEquals(0L, emptySummary.totalBytes)

            val abcSummary = IncrementalSha256Channel.computeDigest(
                InMemoryFileSource("abc.txt", "abc".encodeToByteArray()),
            )
            assertEquals(HASH_ABC, abcSummary.sha256Hex)
            assertEquals(3L, abcSummary.totalBytes)

            val oneKbSource = SyntheticPatternFileSource("1kb.bin", 1024L, patternByte = 'A'.code.toByte())
            val oneKbSummary = IncrementalSha256Channel.computeDigest(oneKbSource)
            assertEquals(HASH_1KB_A, oneKbSummary.sha256Hex)
            assertEquals(1024L, oneKbSummary.totalBytes)

            val fiftyMbSource = SyntheticPatternFileSource(
                fileName = "50mb.bin",
                sizeBytes = 50L * 1024L * 1024L,
                patternByte = 0x00,
            )
            val fiftyMbSummary = IncrementalSha256Channel.computeDigest(fiftyMbSource)
            assertEquals(HASH_50MB_ZERO, fiftyMbSummary.sha256Hex)
            assertEquals(50L * 1024L * 1024L, fiftyMbSummary.totalBytes)
            assertTrue(fiftyMbSource.observedBufferSizes.all { it == FsapConstants.CHUNK_SIZE_BYTES })
        }

    @Test
    fun `TC-FSAP-019 transferencia valida com 0B e multi-chunk promove part para nome final nao-sobrescrito`() =
        runTest {
            val sink = InMemoryFileSink()

            // Arquivo 0 bytes
            val emptySession = sink.openPartSession(TRANSFER_ID, "f0", "vazio.txt", 0L)
            val emptyResult = IncrementalSha256Channel.receiveChannelToSinkVerified(
                readChannel = ByteReadChannel(ByteArray(0)),
                sinkSession = emptySession,
                expectedSizeBytes = 0L,
                expectedSha256 = HASH_EMPTY,
            )
            assertEquals("vazio.txt", emptyResult.storedFile.finalName)
            assertEquals(0L, emptyResult.bytesReceived)
            assertTrue(sink.stagedPartKeys().isEmpty())

            // Arquivo multi-chunk via copySourceToWriteChannel -> receiveChannelToSinkVerified + colisao de nome
            val payload = ByteArray(150_000) { idx -> (idx % 251).toByte() }
            val source = InMemoryFileSource("dados.bin", payload)
            val expectedDigest = IncrementalSha256Channel.computeDigest(source)

            repeat(2) { index ->
                val channel = ByteChannel(autoFlush = true)
                val senderJob = launch {
                    try {
                        IncrementalSha256Channel.copySourceToWriteChannel(source, channel)
                    } finally {
                        channel.close()
                    }
                }

                val session = sink.openPartSession(TRANSFER_ID, "f-${index + 1}", "dados.bin", payload.size.toLong())
                val received = IncrementalSha256Channel.receiveChannelToSinkVerified(
                    readChannel = channel,
                    sinkSession = session,
                    expectedSizeBytes = payload.size.toLong(),
                    expectedSha256 = expectedDigest.sha256Hex,
                )
                senderJob.join()

                val expectedName = if (index == 0) "dados.bin" else "dados (1).bin"
                assertEquals(expectedName, received.storedFile.finalName)
                assertContentEquals(payload, sink.readCommittedBytes(expectedName))
                assertTrue(sink.stagedPartKeys().isEmpty())
            }
        }

    @Test
    fun `TC-FSAP-019 corrupcao de 1 bit no stream falha com HASH_MISMATCH e remove arquivo part`() =
        runTest {
            val sink = InMemoryFileSink()
            val corruptedPayload = "abc".encodeToByteArray().apply { this[2] = 'd'.code.toByte() }
            val session = sink.openPartSession(TRANSFER_ID, "f1", "corrompido.txt", 3L)

            val ex = assertFailsWith<FsapProtocolException> {
                IncrementalSha256Channel.receiveChannelToSinkVerified(
                    readChannel = ByteReadChannel(corruptedPayload),
                    sinkSession = session,
                    expectedSizeBytes = 3L,
                    expectedSha256 = HASH_ABC,
                )
            }
            assertEquals(FsapErrorCode.HASH_MISMATCH, ex.errorCode)
            assertTrue(sink.stagedPartKeys().isEmpty())
            assertTrue(sink.committedFileNames().isEmpty())
        }

    @Test
    fun `TC-FSAP-020 e TC-FSAP-021 overflow truncamento timeout de leitura e falta de espaco descartam part`() =
        runTest {
            val sink = InMemoryFileSink(availableBytes = 100L)
            val storageEx = assertFailsWith<FsapProtocolException> {
                sink.ensureCapacity(requiredBytes = 50L)
            }
            assertEquals(FsapErrorCode.INSUFFICIENT_STORAGE, storageEx.errorCode)

            // Truncamento (stream fecha antes de atingir expectedSizeBytes)
            val truncatedSession = sink.openPartSession(TRANSFER_ID, "f-trunc", "trunc.bin", 10L)
            val truncEx = assertFailsWith<FsapProtocolException> {
                IncrementalSha256Channel.receiveChannelToSinkVerified(
                    readChannel = ByteReadChannel(ByteArray(4)),
                    sinkSession = truncatedSession,
                    expectedSizeBytes = 10L,
                    expectedSha256 = HASH_ABC,
                )
            }
            assertEquals(FsapErrorCode.SIZE_MISMATCH, truncEx.errorCode)
            assertTrue(sink.stagedPartKeys().isEmpty())

            // Overflow (stream envia 1 byte alem do declarado)
            val overflowSession = sink.openPartSession(TRANSFER_ID, "f-over", "over.bin", 3L)
            val overEx = assertFailsWith<FsapProtocolException> {
                IncrementalSha256Channel.receiveChannelToSinkVerified(
                    readChannel = ByteReadChannel("abcd".encodeToByteArray()),
                    sinkSession = overflowSession,
                    expectedSizeBytes = 3L,
                    expectedSha256 = HASH_ABC,
                )
            }
            assertEquals(FsapErrorCode.SIZE_MISMATCH, overEx.errorCode)
            assertTrue(sink.stagedPartKeys().isEmpty())

            // Timeout de leitura no canal
            val stalledChannel = ByteChannel(autoFlush = true)
            val slowSender = launch {
                stalledChannel.writeFully("a".encodeToByteArray())
                delay(500L)
                stalledChannel.close()
            }
            val slowSession = sink.openPartSession(TRANSFER_ID, "f-slow", "slow.bin", 3L)
            val timeoutEx = assertFailsWith<FsapProtocolException> {
                IncrementalSha256Channel.receiveChannelToSinkVerified(
                    readChannel = stalledChannel,
                    sinkSession = slowSession,
                    expectedSizeBytes = 3L,
                    expectedSha256 = HASH_ABC,
                    readTimeoutMs = 100L,
                )
            }
            slowSender.cancel()
            assertEquals(FsapErrorCode.STREAM_TIMEOUT, timeoutEx.errorCode)
            assertTrue(sink.stagedPartKeys().isEmpty())
            assertTrue(sink.committedFileNames().isEmpty())
        }
}

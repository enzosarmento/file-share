package br.edu.fileshare.network.discovery

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.core.logging.LogLevel
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.protocol.model.AnnouncePayload
import br.edu.fileshare.protocol.model.ByePayload
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.DiscoverPayload
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryListenerTest {

    private class TestClock(var nowMs: Long = 1_763_000_000_000L) : Clock {
        override fun epochMillis(): Long = nowMs
        override fun nowIsoUtc(): String = "2026-11-13T01:10:00Z"
    }

    private val localId = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    private val peerBId = "550e8400-e29b-41d4-a716-446655440001"

    @Test
    fun `TC-FSAP-002 filtra auto-descoberta e responde DISCOVER com ANNOUNCE apos jitter`() =
        runTest {
            val clock = TestClock()
            val logger = InMemoryFsapLogger()
            val transport = FakeMulticastTransport()
            val registry = PeerRegistry(clock = clock, logger = logger)
            var discoverResponses = 0

            val listener = DiscoveryListener(
                transport = transport,
                localDeviceId = localId,
                peerRegistry = registry,
                scope = backgroundScope,
                onDiscoverRespond = { discoverResponses++ },
                jitterMillisProvider = { 120L },
                logger = logger,
            )
            listener.start()
            runCurrent()

            // Pacote do proprio no deve ser ignorado silenciosamente (sem entrada na PeerRegistry)
            val selfAnnounce = FsapEnvelopeFactory.create(
                type = FsapMessageType.ANNOUNCE,
                senderId = localId,
                payload = AnnouncePayload(
                    alias = "Meu-No",
                    deviceType = DeviceType.DESKTOP,
                    platform = PlatformType.LINUX,
                    httpPort = 53317,
                ),
            )
            transport.enqueueIncomingText(FsapJson.encodeEnvelope(selfAnnounce), sourceIp = "192.168.1.10")
            runCurrent()
            assertTrue(registry.snapshot().isEmpty())

            // DISCOVER remoto agenda resposta entre 50..300ms (120ms neste teste)
            val remoteDiscover = FsapEnvelopeFactory.create(
                type = FsapMessageType.DISCOVER,
                senderId = peerBId,
                payload = DiscoverPayload(requesterAlias = "Pixel-8", requesterHttpPort = 53317),
            )
            transport.enqueueIncomingText(FsapJson.encodeEnvelope(remoteDiscover), sourceIp = "192.168.1.44")
            runCurrent()
            assertEquals(0, discoverResponses)

            advanceTimeBy(119L)
            runCurrent()
            assertEquals(0, discoverResponses)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(1, discoverResponses)

            // ANNOUNCE valido de B usa o IP real do pacote UDP e entra em DISCOVERED
            val remoteAnnounce = FsapEnvelopeFactory.create(
                type = FsapMessageType.ANNOUNCE,
                senderId = peerBId,
                payload = AnnouncePayload(
                    alias = "Pixel-8",
                    deviceType = DeviceType.MOBILE,
                    platform = PlatformType.ANDROID,
                    httpPort = 54000,
                ),
            )
            transport.enqueueIncomingText(FsapJson.encodeEnvelope(remoteAnnounce), sourceIp = "192.168.1.44")
            runCurrent()

            val peer = registry.findById(peerBId)
            assertEquals("192.168.1.44", peer?.ipAddress)
            assertEquals(54000, peer?.port)
            assertEquals(PeerConnectionState.DISCOVERED, peer?.connectionState)

            // BYE remove imediatamente da PeerRegistry
            val remoteBye = FsapEnvelopeFactory.create(
                type = FsapMessageType.BYE,
                senderId = peerBId,
                payload = ByePayload(reason = "USER_SHUTDOWN"),
            )
            transport.enqueueIncomingText(FsapJson.encodeEnvelope(remoteBye), sourceIp = "192.168.1.44")
            runCurrent()
            assertTrue(registry.snapshot().isEmpty())

            listener.stop()
        }

    @Test
    fun `TC-FSAP-003 descarta datagramas corrompidos ou invalidos silenciosamente sem crash`() =
        runTest {
            val logger = InMemoryFsapLogger()
            val transport = FakeMulticastTransport()
            val registry = PeerRegistry(clock = TestClock(), logger = logger)
            val listener = DiscoveryListener(
                transport = transport,
                localDeviceId = localId,
                peerRegistry = registry,
                scope = backgroundScope,
                logger = logger,
            )
            listener.start()
            runCurrent()

            // (a) lixo binario nao-JSON
            transport.enqueueIncoming(UdpDatagram(byteArrayOf(0x00, 0xFF.toByte(), 0x7F), "192.168.1.90", 53318))
            // (b) JSON com protocol != FSAP
            transport.enqueueIncomingText(
                """{"protocol":"OUTRO","version":"1.0","type":"ANNOUNCE","messageId":"11111111-1111-4111-8111-000000000001","timestamp":"2026-11-13T00:00:00Z","senderId":"$peerBId","payload":{"alias":"Invasor","deviceType":"DESKTOP","platform":"LINUX","httpPort":53317}}""",
                sourceIp = "192.168.1.90",
            )
            // (c) JSON com porta fora do intervalo (99999)
            transport.enqueueIncomingText(
                """{"protocol":"FSAP","version":"1.0","type":"ANNOUNCE","messageId":"11111111-1111-4111-8111-000000000002","timestamp":"2026-11-13T00:00:00Z","senderId":"$peerBId","payload":{"alias":"Invalido","deviceType":"DESKTOP","platform":"LINUX","httpPort":99999}}""",
                sourceIp = "192.168.1.90",
            )
            runCurrent()

            // Nenhum pacote de erro foi enviado pelo transporte multicast e registry segue vazio
            assertTrue(transport.sentPayloads.isEmpty())
            assertTrue(registry.snapshot().isEmpty())
            assertEquals(3, logger.snapshot().count { it.level == LogLevel.WARN })

            // Listener continua vivo processando ANNOUNCE valido subsequente
            val validAnnounce = FsapEnvelopeFactory.create(
                type = FsapMessageType.ANNOUNCE,
                senderId = peerBId,
                payload = AnnouncePayload(
                    alias = "Colega-Valido",
                    deviceType = DeviceType.DESKTOP,
                    platform = PlatformType.WINDOWS,
                    httpPort = 53317,
                ),
            )
            transport.enqueueIncomingText(FsapJson.encodeEnvelope(validAnnounce), sourceIp = "192.168.1.55")
            runCurrent()

            assertEquals(1, registry.snapshot().size)
            assertEquals("Colega-Valido", registry.findById(peerBId)?.alias)

            listener.stop()
        }
}


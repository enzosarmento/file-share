package br.edu.fileshare.network.discovery

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.AnnouncePayload
import br.edu.fileshare.protocol.model.ByePayload
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.DiscoverPayload
import br.edu.fileshare.protocol.model.FsapEnvelopeFactory
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.LocalNodeState
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import br.edu.fileshare.protocol.validation.FsapMessageValidator
import br.edu.fileshare.protocol.validation.ValidationResult
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.StoredFileDescriptor
import br.edu.fileshare.ui.viewmodel.FileShareMainViewModel
import br.edu.fileshare.ui.viewmodel.FileShareRuntimePort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Suite consolidada de conformidade do Plano de Descoberta UDP Multicast (`TASK-TEST-003`):
 * executa `TC-FSAP-001` a `TC-FSAP-005` com `DiscoveryCoordinator`, `PeerRegistry` e ViewModel reativa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UdpDiscoveryConformanceTest {

    private class TestClock(var nowMs: Long = 1_763_000_000_000L) : Clock {
        override fun epochMillis(): Long = nowMs
        override fun nowIsoUtc(): String = "2026-11-13T01:30:00Z"
    }

    private class CoordinatorRuntimeAdapter(
        private val localIdentity: DeviceIdentity,
        private val peerRegistry: PeerRegistry,
        private val coordinator: DiscoveryCoordinator,
    ) : FileShareRuntimePort {
        override val localDeviceId: String = localIdentity.deviceId
        override val initialAlias: String = localIdentity.alias
        override val initialPort: Int = localIdentity.httpPort
        override val saveDirectoryLabel: String = "/downloads"
        override val peers: StateFlow<List<DiscoveredPeer>> = peerRegistry.peers
        override val receivedFiles: StateFlow<List<StoredFileDescriptor>> = MutableStateFlow(emptyList())
        override val localAddresses: StateFlow<List<String>> = MutableStateFlow(listOf("192.168.1.10"))

        override suspend fun startNode(alias: String, port: Int): Result<Int> {
            coordinator.start()
            return Result.success(port)
        }

        override suspend fun stopNode() {
            coordinator.stop(reason = "USER_STOPPED")
        }

        override suspend fun pickFilesToSend(): List<FileSource> = emptyList()

        override suspend fun sendFiles(peer: DiscoveredPeer, sources: List<FileSource>): Result<String> =
            Result.failure(UnsupportedOperationException("Nao usado no teste de descoberta"))

        override suspend fun refreshDiscovery(): Result<Unit> = coordinator.refresh()
    }

    private companion object {
        const val NODE_A_ID = "550e8400-e29b-41d4-a716-446655440201"
        const val NODE_B_ID = "550e8400-e29b-41d4-a716-446655440202"
    }

    private fun nodeAIdentity() = DeviceIdentity(
        deviceId = NODE_A_ID,
        alias = "Ubuntu-Node-A",
        deviceType = DeviceType.DESKTOP,
        platform = PlatformType.LINUX,
        httpPort = FsapConstants.DEFAULT_HTTP_PORT,
    )

    @Test
    fun `TC-FSAP-001 ANNOUNCE valido e validado, deserializado e registrado em PeerRegistry como DISCOVERED`() =
        runTest {
            val clock = TestClock()
            val transport = FakeMulticastTransport()
            val registry = PeerRegistry(clock = clock)
            val coordinator = DiscoveryCoordinator(
                transport = transport,
                peerRegistry = registry,
                localIdentityProvider = ::nodeAIdentity,
                scope = backgroundScope,
                clock = clock,
            )
            coordinator.start()
            runCurrent()

            val incomingAnnounce = FsapEnvelopeFactory.create(
                type = FsapMessageType.ANNOUNCE,
                senderId = NODE_B_ID,
                payload = AnnouncePayload(
                    alias = "Android-Sala",
                    deviceType = DeviceType.MOBILE,
                    platform = PlatformType.ANDROID,
                    httpPort = 53317,
                ),
            )
            val raw = FsapJson.encodeEnvelope(incomingAnnounce)
            assertIs<ValidationResult.Valid>(
                FsapMessageValidator.validateAnnounce(FsapJson.decodeEnvelope(raw)),
            )

            transport.enqueueIncomingText(raw, sourceIp = "192.168.1.77")
            runCurrent()

            val recorded = registry.findById(NODE_B_ID)
            assertEquals("Android-Sala", recorded?.alias)
            assertEquals("192.168.1.77", recorded?.ipAddress)
            assertEquals(53317, recorded?.port)
            assertEquals(PeerOrigin.MULTICAST, recorded?.origin)
            assertEquals(PeerConnectionState.DISCOVERED, recorded?.connectionState)

            coordinator.stop()
        }

    @Test
    fun `TC-FSAP-002 no responde a DISCOVER remoto dentro de 50 a 300ms e ignora proprio pacote`() =
        runTest {
            val clock = TestClock()
            val transport = FakeMulticastTransport()
            val registry = PeerRegistry(clock = clock)
            val coordinator = DiscoveryCoordinator(
                transport = transport,
                peerRegistry = registry,
                localIdentityProvider = ::nodeAIdentity,
                scope = backgroundScope,
                clock = clock,
            )
            coordinator.start()
            runCurrent()

            val initialCount = transport.sentPayloads.size // 1 DISCOVER + 1 ANNOUNCE = 2
            assertEquals(2, initialCount)

            // Proprio ANNOUNCE voltando via loopback nao entra na PeerRegistry
            val selfAnnounce = FsapEnvelopeFactory.create(
                type = FsapMessageType.ANNOUNCE,
                senderId = NODE_A_ID,
                payload = AnnouncePayload("Ubuntu-Node-A", DeviceType.DESKTOP, PlatformType.LINUX, 53317),
            )
            transport.enqueueIncomingText(FsapJson.encodeEnvelope(selfAnnounce), sourceIp = "192.168.1.10")
            runCurrent()
            assertTrue(registry.snapshot().isEmpty())

            // DISCOVER do par B gera resposta ANNOUNCE ate no maximo 300ms
            val peerDiscover = FsapEnvelopeFactory.create(
                type = FsapMessageType.DISCOVER,
                senderId = NODE_B_ID,
                payload = DiscoverPayload("Android-B", 53317),
            )
            transport.enqueueIncomingText(FsapJson.encodeEnvelope(peerDiscover), sourceIp = "192.168.1.77")
            runCurrent()

            advanceTimeBy(DiscoveryDefaults.DISCOVER_JITTER_MAX_MS + 10L)
            runCurrent()

            val afterDiscover = transport.snapshotSentStrings()
            assertEquals(initialCount + 1, afterDiscover.size)
            assertEquals(
                FsapMessageType.ANNOUNCE.name,
                FsapJson.decodeRawEnvelope(afterDiscover.last()).type,
            )

            coordinator.stop()
        }

    @Test
    fun `TC-FSAP-003 descarta lixo binario, protocolo invalido e porta invalida sem emitir erro multicast`() =
        runTest {
            val clock = TestClock()
            val logger = InMemoryFsapLogger()
            val transport = FakeMulticastTransport()
            val registry = PeerRegistry(clock = clock, logger = logger)
            val coordinator = DiscoveryCoordinator(
                transport = transport,
                peerRegistry = registry,
                localIdentityProvider = ::nodeAIdentity,
                scope = backgroundScope,
                clock = clock,
                logger = logger,
            )
            val vm = FileShareMainViewModel(
                runtime = CoordinatorRuntimeAdapter(nodeAIdentity(), registry, coordinator),
                scope = backgroundScope,
                logger = logger,
            )

            vm.startNodeInternal()
            runCurrent()
            assertEquals(LocalNodeState.AVAILABLE, vm.uiState.value.nodeState)
            val sentBefore = transport.sentPayloads.size

            transport.enqueueIncoming(UdpDatagram(byteArrayOf(0x01, 0x02, 0xFF.toByte()), "192.168.1.99", 53318))
            transport.enqueueIncomingText(
                """{"protocol":"OUTRO","version":"1.0","type":"ANNOUNCE","messageId":"11111111-1111-4111-8111-000000000010","timestamp":"2026-11-13T01:00:00Z","senderId":"$NODE_B_ID","payload":{"alias":"Invasor","deviceType":"DESKTOP","platform":"LINUX","httpPort":53317}}""",
            )
            transport.enqueueIncomingText(
                """{"protocol":"FSAP","version":"1.0","type":"ANNOUNCE","messageId":"11111111-1111-4111-8111-000000000011","timestamp":"2026-11-13T01:00:00Z","senderId":"$NODE_B_ID","payload":{"alias":"PortaBad","deviceType":"DESKTOP","platform":"LINUX","httpPort":99999}}""",
            )
            runCurrent()

            assertEquals(sentBefore, transport.sentPayloads.size)
            assertEquals(LocalNodeState.AVAILABLE, vm.uiState.value.nodeState)
            assertTrue(vm.uiState.value.peers.isEmpty())

            vm.stopNodeInternal()
        }

    @Test
    fun `TC-FSAP-004 e TC-FSAP-005 expiracao STALE em 13s, TTL em 19s e remocao imediata via BYE`() =
        runTest {
            val clock = TestClock(nowMs = 50_000L)
            val transport = FakeMulticastTransport()
            val registry = PeerRegistry(clock = clock)
            val coordinator = DiscoveryCoordinator(
                transport = transport,
                peerRegistry = registry,
                localIdentityProvider = ::nodeAIdentity,
                scope = backgroundScope,
                clock = clock,
            )
            val vm = FileShareMainViewModel(
                runtime = CoordinatorRuntimeAdapter(nodeAIdentity(), registry, coordinator),
                scope = backgroundScope,
            )
            vm.startNodeInternal()
            runCurrent()

            val announceB = FsapEnvelopeFactory.create(
                type = FsapMessageType.ANNOUNCE,
                senderId = NODE_B_ID,
                payload = AnnouncePayload("Notebook-B", DeviceType.DESKTOP, PlatformType.WINDOWS, 53317),
            )
            transport.enqueueIncomingText(FsapJson.encodeEnvelope(announceB), sourceIp = "192.168.1.88")
            runCurrent()
            assertEquals(PeerConnectionState.DISCOVERED, vm.uiState.value.peers.single().connectionState)

            // TC-FSAP-004: avanco de +13s (5 ciclos de sweep de 3s -> 15s) muda para STALE
            clock.nowMs += 13_000L
            advanceTimeBy(15_000L)
            runCurrent()
            assertEquals(PeerConnectionState.STALE, vm.uiState.value.peers.single().connectionState)

            // Mais +6s (total 19s > 18s TTL) -> removido da UI
            clock.nowMs += 6_000L
            advanceTimeBy(6_000L)
            runCurrent()
            assertTrue(vm.uiState.value.peers.isEmpty())
            assertNull(vm.uiState.value.selectedPeerDeviceId)

            // TC-FSAP-005: reaparece e sai imediatamente ao receber BYE sem aguardar 18s
            transport.enqueueIncomingText(FsapJson.encodeEnvelope(announceB), sourceIp = "192.168.1.88")
            runCurrent()
            assertEquals(1, vm.uiState.value.peers.size)

            val byeB = FsapEnvelopeFactory.create(
                type = FsapMessageType.BYE,
                senderId = NODE_B_ID,
                payload = ByePayload(reason = "USER_SHUTDOWN"),
            )
            transport.enqueueIncomingText(FsapJson.encodeEnvelope(byeB), sourceIp = "192.168.1.88")
            runCurrent()
            assertTrue(vm.uiState.value.peers.isEmpty())

            vm.stopNodeInternal()
        }
}


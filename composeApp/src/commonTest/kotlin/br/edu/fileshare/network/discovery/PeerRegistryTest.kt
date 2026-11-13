package br.edu.fileshare.network.discovery

import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PeerRegistryTest {

    private class MutableClock(var nowMs: Long = 1_000_000L) : Clock {
        override fun epochMillis(): Long = nowMs
        override fun nowIsoUtc(): String = "2026-11-13T01:20:00Z"
    }

    private fun peerIdentity(
        id: String,
        alias: String = "Ubuntu-Peer",
        port: Int = 53317,
    ) = DeviceIdentity(
        deviceId = id,
        alias = alias,
        deviceType = DeviceType.DESKTOP,
        platform = PlatformType.LINUX,
        httpPort = port,
    )

    @Test
    fun `deduplica por deviceId atualizando IP alias e porta sem duplicar entradas`() = runTest {
        val clock = MutableClock()
        val registry = PeerRegistry(clock = clock)
        val deviceId = "550e8400-e29b-41d4-a716-446655440010"

        registry.upsertFromAnnounce(peerIdentity(deviceId, alias = "Notebook-v1", port = 53317), "192.168.1.20")
        assertEquals(1, registry.snapshot().size)

        clock.nowMs += 4_000L
        registry.upsertFromAnnounce(peerIdentity(deviceId, alias = "Notebook-v2", port = 54001), "192.168.1.33")

        val peers = registry.snapshot()
        assertEquals(1, peers.size)
        assertEquals("Notebook-v2", peers.single().alias)
        assertEquals("192.168.1.33", peers.single().ipAddress)
        assertEquals(54001, peers.single().port)
        assertEquals(PeerConnectionState.DISCOVERED, peers.single().connectionState)
    }

    @Test
    fun `TC-FSAP-004 sweeper transita par silencioso para STALE em 13s e expira em 19s`() = runTest {
        val clock = MutableClock(nowMs = 10_000L)
        val registry = PeerRegistry(clock = clock)
        val sweeper = PeerRegistrySweeper(peerRegistry = registry, scope = backgroundScope)

        val multicastPeerId = "550e8400-e29b-41d4-a716-446655440020"
        val manualPeerId = "550e8400-e29b-41d4-a716-446655440021"

        registry.upsertFromAnnounce(peerIdentity(multicastPeerId, alias = "Android-WiFi"), "192.168.1.40")
        registry.registerManualPeer(peerIdentity(manualPeerId, alias = "Servidor-Fixo"), "192.168.1.50")

        sweeper.start()
        runCurrent()
        assertEquals(PeerConnectionState.DISCOVERED, registry.findById(multicastPeerId)?.connectionState)

        // Em +9s -> ainda ativo (DISCOVERED)
        clock.nowMs += 9_000L
        advanceTimeBy(9_000L)
        runCurrent()
        assertEquals(PeerConnectionState.DISCOVERED, registry.findById(multicastPeerId)?.connectionState)

        // Em +13s (sweep de t=15s ou +6s) -> transita para STALE (> 12s)
        clock.nowMs += 4_000L // total elapsed = 13_000L
        advanceTimeBy(3_000L) // t=12_000L
        runCurrent()
        advanceTimeBy(3_000L) // t=15_000L (clock elapsed 13s at +12s.. wait clock already at +13s)
        runCurrent()
        assertEquals(PeerConnectionState.STALE, registry.findById(multicastPeerId)?.connectionState)

        // Em +19s -> removido da tabela (> 18s), enquanto par MANUAL_IP permanece conectado
        clock.nowMs += 6_000L // total elapsed = 19_000L
        advanceTimeBy(3_000L) // t=18_000L
        runCurrent()

        assertNull(registry.findById(multicastPeerId))
        val manualPeer = registry.findById(manualPeerId)
        assertEquals(PeerConnectionState.CONNECTED, manualPeer?.connectionState)

        sweeper.stop()
    }

    @Test
    fun `TC-FSAP-005 removeByBye descarta par imediatamente e novo ANNOUNCE recupera par STALE`() = runTest {
        val clock = MutableClock(nowMs = 1_000L)
        val registry = PeerRegistry(clock = clock)
        val peerId = "550e8400-e29b-41d4-a716-446655440030"

        registry.upsertFromAnnounce(peerIdentity(peerId, alias = "Notebook-Aula"), "192.168.1.80")
        clock.nowMs += 13_000L
        registry.sweepExpired()
        assertEquals(PeerConnectionState.STALE, registry.findById(peerId)?.connectionState)

        // Novo ANNOUNCE recupera de STALE para DISCOVERED
        registry.upsertFromAnnounce(peerIdentity(peerId, alias = "Notebook-Aula"), "192.168.1.80")
        assertEquals(PeerConnectionState.DISCOVERED, registry.findById(peerId)?.connectionState)

        // BYE remove imediatamente antes dos 18s
        assertTrue(registry.removeByBye(peerId, reason = "USER_SHUTDOWN"))
        assertNull(registry.findById(peerId))
        assertTrue(registry.snapshot().isEmpty())
    }
}


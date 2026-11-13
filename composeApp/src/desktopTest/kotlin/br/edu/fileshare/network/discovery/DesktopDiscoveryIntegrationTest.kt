package br.edu.fileshare.network.discovery

import br.edu.fileshare.desktop.server.ServerCliParser
import br.edu.fileshare.desktop.server.createDesktopHeadlessServer
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDiscoveryIntegrationTest {

    @Test
    fun `dois coordenadores descoberta se enxergam via UDP multicast e removem ao enviar BYE`() =
        runBlocking {
            val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val transportA = JvmMulticastTransport(
                port = 0,
                enableLoopback = true,
                allowLoopbackFallback = true,
            )
            transportA.open()
            val sharedPort = transportA.boundPort

            val transportB = JvmMulticastTransport(
                port = sharedPort,
                enableLoopback = true,
                allowLoopbackFallback = true,
            )

            val registryA = PeerRegistry()
            val registryB = PeerRegistry()

            val identityA = DeviceIdentity(
                deviceId = "550e8400-e29b-41d4-a716-446655440101",
                alias = "Desktop-Alpha",
                deviceType = DeviceType.DESKTOP,
                platform = PlatformType.LINUX,
                httpPort = 53317,
            )
            val identityB = DeviceIdentity(
                deviceId = "550e8400-e29b-41d4-a716-446655440102",
                alias = "Android-Beta",
                deviceType = DeviceType.MOBILE,
                platform = PlatformType.ANDROID,
                httpPort = 54321,
            )

            val coordinatorA = DiscoveryCoordinator(
                transport = transportA,
                peerRegistry = registryA,
                localIdentityProvider = { identityA },
                scope = scopeA,
                announceIntervalMs = 2_000L,
            )
            val coordinatorB = DiscoveryCoordinator(
                transport = transportB,
                peerRegistry = registryB,
                localIdentityProvider = { identityB },
                scope = scopeB,
                announceIntervalMs = 2_000L,
            )

            try {
                coordinatorA.start()
                coordinatorB.start()

                val discoveredByB = withTimeout(5_000L) {
                    registryB.peers.first { peers -> peers.any { it.deviceId == identityA.deviceId } }
                }
                val peerAOnB = discoveredByB.first { it.deviceId == identityA.deviceId }
                assertEquals("Desktop-Alpha", peerAOnB.alias)
                assertEquals(53317, peerAOnB.port)
                assertEquals(PeerConnectionState.DISCOVERED, peerAOnB.connectionState)

                val discoveredByA = withTimeout(5_000L) {
                    registryA.peers.first { peers -> peers.any { it.deviceId == identityB.deviceId } }
                }
                assertEquals("Android-Beta", discoveredByA.first { it.deviceId == identityB.deviceId }.alias)

                // A envia BYE gracioso -> B remove imediatamente
                coordinatorA.stop(reason = "USER_SHUTDOWN")
                withTimeout(5_000L) {
                    registryB.peers.first { peers -> peers.none { it.deviceId == identityA.deviceId } }
                }
                assertTrue(registryB.findById(identityA.deviceId) == null)
            } finally {
                coordinatorA.stop("TEST_CLEANUP")
                coordinatorB.stop("TEST_CLEANUP")
                delay(50L)
                scopeA.cancel()
                scopeB.cancel()
            }
        }

    @Test
    fun `servidor desktop headless inicia descoberta UDP e encerra graciosamente`() = runBlocking {
        val tempDir = Files.createTempDirectory("fileshare-srv-disc")
        val freePort = ServerSocket(0).use { it.localPort }
        try {
            val config = ServerCliParser.parse(emptyArray()).copy(
                port = freePort,
                downloadDir = tempDir,
                alias = "Srv-Disc-Test",
                autoAcceptTest = true,
            )
            val runtime = createDesktopHeadlessServer(
                config = config,
                enableDiscovery = true,
                discoveryPort = 0,
            )
            val boundHttpPort = runtime.start()
            assertEquals(freePort, boundHttpPort)
            assertEquals(true, runtime.discoveryCoordinator?.isRunning)

            runtime.stopGracefully()
            assertFalse(runtime.discoveryCoordinator?.isRunning ?: true)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}


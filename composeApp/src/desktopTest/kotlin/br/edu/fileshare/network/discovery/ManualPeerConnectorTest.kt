package br.edu.fileshare.network.discovery

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.error.FsapErrorCode
import br.edu.fileshare.protocol.error.FsapErrorFactory
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.FsapEnvelope
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.IdentifyPayload
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManualPeerConnectorTest {

    private class FakeClock(var currentMs: Long = 1_700_000_000_000L) : Clock {
        override fun epochMillis(): Long = currentMs
        override fun nowIsoUtc(): String = "2026-11-12T23:15:00Z"
    }

    private companion object {
        val LOCAL_CLIENT = DeviceIdentity(
            deviceId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            alias = "Cliente Notebook",
            deviceType = DeviceType.DESKTOP,
            platform = PlatformType.LINUX,
            httpPort = 53320,
        )
        val REMOTE_SERVER = DeviceIdentity(
            deviceId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            alias = "Servidor Sala 3",
            deviceType = DeviceType.SERVER,
            platform = PlatformType.LINUX,
            httpPort = 0,
        )
    }

    @Test
    fun `TC-FSAP-006 conexao manual por IP registra par em PeerRegistry no estado CONNECTED sem duplicar`() =
        runBlocking {
            val clock = FakeClock()
            val logger = InMemoryFsapLogger(clock)
            val server = FsapHttpServer(
                config = FsapHttpServerConfig(identity = REMOTE_SERVER, host = "127.0.0.1", port = 0),
                logger = logger,
                clock = clock,
            )
            val port = server.start()
            val registry = PeerRegistry(clock = clock, logger = logger)
            val connector = ManualPeerConnector(
                localIdentity = LOCAL_CLIENT,
                peerRegistry = registry,
                logger = logger,
                clock = clock,
            )

            try {
                val first = connector.connectByManualIp("127.0.0.1", port)
                val connected = assertIs<ManualConnectResult.Connected>(first)
                assertEquals(REMOTE_SERVER.deviceId, connected.peer.deviceId)
                assertEquals(REMOTE_SERVER.alias, connected.peer.alias)
                assertEquals("127.0.0.1", connected.peer.ipAddress)
                assertEquals(port, connected.peer.port)
                assertEquals(PeerConnectionState.CONNECTED, connected.peer.connectionState)
                assertEquals(PeerOrigin.MANUAL_IP, connected.peer.origin)
                assertEquals(1, registry.peers.value.size)

                clock.currentMs += 2_000L
                val second = connector.connectByManualIp("127.0.0.1", port)
                val refreshed = assertIs<ManualConnectResult.Connected>(second)
                assertEquals(1, registry.peers.value.size)
                assertEquals(clock.currentMs, refreshed.peer.lastSeenEpochMillis)
                assertEquals(1_700_000_000_000L, refreshed.peer.firstSeenEpochMillis)
            } finally {
                connector.close()
                server.stop()
            }
        }

    @Test
    fun `TC-FSAP-007 IP ou porta inacessivel e timeout retornam FSAP-ERR-404-DEVICE_NOT_FOUND`() =
        runBlocking {
            val deadPort = ServerSocket(0).use { it.localPort }
            val registry = PeerRegistry()
            val connector = ManualPeerConnector(
                localIdentity = LOCAL_CLIENT,
                peerRegistry = registry,
                connectTimeoutMs = 250L,
            )

            // Caso 1: porta fechada (ConnectException imediata)
            val closedResult = connector.connectByManualIp("127.0.0.1", deadPort)
            val failedClosed = assertIs<ManualConnectResult.Failed>(closedResult)
            assertEquals(FsapErrorCode.DEVICE_NOT_FOUND, failedClosed.errorCode)
            assertTrue(registry.snapshot().isEmpty())

            // Caso 2: socket aceita TCP mas nao responde HTTP dentro de connectTimeoutMs
            val silentServer = ServerSocket(0)
            val acceptThread = Thread {
                runCatching {
                    silentServer.accept().use { Thread.sleep(600) }
                }
            }.apply { start() }

            try {
                val timeoutResult = connector.connectByManualIp("127.0.0.1", silentServer.localPort)
                val failedTimeout = assertIs<ManualConnectResult.Failed>(timeoutResult)
                assertEquals(FsapErrorCode.DEVICE_NOT_FOUND, failedTimeout.errorCode)
                assertTrue(registry.snapshot().isEmpty())
            } finally {
                connector.close()
                silentServer.close()
                acceptThread.join(1_000L)
            }
        }

    @Test
    fun `TC-FSAP-007 servidor com versao MAJOR 2_0 retorna FSAP-ERR-505-UNSUPPORTED_VERSION`() =
        runBlocking {
            val stub505 = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                routing {
                    post(FsapConstants.Routes.IDENTIFY) {
                        val err = FsapErrorFactory.toEnvelope(
                            senderId = REMOTE_SERVER.deviceId,
                            errorCode = FsapErrorCode.UNSUPPORTED_VERSION,
                        )
                        call.respondText(
                            text = FsapJson.encodeEnvelope(err),
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.fromValue(505),
                        )
                    }
                }
            }
            val stub200V2 = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                routing {
                    post(FsapConstants.Routes.IDENTIFY) {
                        val v2Body = FsapEnvelope(
                            protocol = FsapConstants.PROTOCOL_NAME,
                            version = "2.0",
                            type = FsapMessageType.IDENTIFY.name,
                            messageId = "550e8400-e29b-41d4-a716-446655440099",
                            timestamp = "2026-11-12T23:15:00Z",
                            senderId = REMOTE_SERVER.deviceId,
                            payload = IdentifyPayload(
                                alias = "Servidor V2",
                                deviceType = DeviceType.SERVER,
                                platform = PlatformType.LINUX,
                                httpPort = 53317,
                            ),
                        )
                        call.respondText(
                            text = FsapJson.encodeEnvelope(v2Body),
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.OK,
                        )
                    }
                }
            }

            stub505.start(wait = false)
            stub200V2.start(wait = false)
            val port505 = stub505.engine.resolvedConnectors().first().port
            val port200V2 = stub200V2.engine.resolvedConnectors().first().port

            val registry = PeerRegistry()
            val connector = ManualPeerConnector(
                localIdentity = LOCAL_CLIENT,
                peerRegistry = registry,
            )

            try {
                val res505 = assertIs<ManualConnectResult.Failed>(connector.connectByManualIp("127.0.0.1", port505))
                assertEquals(FsapErrorCode.UNSUPPORTED_VERSION, res505.errorCode)

                val resV2 = assertIs<ManualConnectResult.Failed>(connector.connectByManualIp("127.0.0.1", port200V2))
                assertEquals(FsapErrorCode.UNSUPPORTED_VERSION, resV2.errorCode)
                assertTrue(registry.snapshot().isEmpty())
            } finally {
                connector.close()
                stub505.stop(200, 500)
                stub200V2.stop(200, 500)
            }
        }

    @Test
    fun `validacao de entrada e resposta HTTP nao-FSAP sao tratadas de forma deterministica`() =
        runBlocking {
            val nonFsapServer = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                routing {
                    post(FsapConstants.Routes.IDENTIFY) {
                        call.respondText("<html>Not FSAP</html>", ContentType.Text.Html, HttpStatusCode.OK)
                    }
                }
            }
            nonFsapServer.start(wait = false)
            val portNonFsap = nonFsapServer.engine.resolvedConnectors().first().port

            val registry = PeerRegistry()
            val connector = ManualPeerConnector(localIdentity = LOCAL_CLIENT, peerRegistry = registry)

            try {
                val badHost = assertIs<ManualConnectResult.Failed>(connector.connectByManualIp("192.168.0.1/invalid"))
                assertEquals(FsapErrorCode.MALFORMED_MESSAGE, badHost.errorCode)

                val badPort = assertIs<ManualConnectResult.Failed>(connector.connectByManualIp("127.0.0.1", 80))
                assertEquals(FsapErrorCode.MALFORMED_MESSAGE, badPort.errorCode)

                val htmlResp = assertIs<ManualConnectResult.Failed>(connector.connectByManualIp("127.0.0.1", portNonFsap))
                assertEquals(FsapErrorCode.DEVICE_NOT_FOUND, htmlResp.errorCode)
            } finally {
                connector.close()
                nonFsapServer.stop(200, 500)
            }
        }
}


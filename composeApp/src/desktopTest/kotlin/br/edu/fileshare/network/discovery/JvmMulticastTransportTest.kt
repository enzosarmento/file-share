package br.edu.fileshare.network.discovery

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmMulticastTransportTest {

    @Test
    fun `enumerador de interfaces executa sem falhas e retorna apenas IPs RFC 1918`() {
        val provider = JvmNetworkInterfaceProvider()
        val interfaces = provider.listLanInterfaces()
        interfaces.forEach { info ->
            assertFalse(NetworkInterfaceFilter.isExcludedInterfaceName(info.name, info.displayName))
            assertTrue(NetworkInterfaceFilter.isRfc1918Ipv4(info.ipv4Address))
        }
    }

    @Test
    fun `rejeita datagrama maior que 1024 bytes e executa loopback UDP com fechamento limpo`() = runBlocking {
        val transportA = JvmMulticastTransport(
            port = 0,
            enableLoopback = true,
            allowLoopbackFallback = true,
        )
        transportA.open()
        assertTrue(transportA.isOpen)
        val sharedPort = transportA.boundPort
        assertTrue(sharedPort > 0)

        val transportB = JvmMulticastTransport(
            port = sharedPort,
            enableLoopback = true,
            allowLoopbackFallback = true,
        )
        try {
            transportB.open()
            assertTrue(transportB.isOpen)

            assertFailsWith<IllegalArgumentException> {
                transportA.send(ByteArray(DiscoveryDefaults.MAX_DATAGRAM_BYTES + 1))
            }

            val payload = "FSAP-LOOPBACK-TEST".toByteArray(StandardCharsets.UTF_8)
            val receiverDeferred = async {
                withTimeout(5_000L) {
                    var received: UdpDatagram? = null
                    while (received == null) {
                        val candidate = transportB.receive() ?: break
                        if (candidate.bytes.contentEquals(payload)) {
                            received = candidate
                        }
                    }
                    received
                }
            }

            transportA.send(payload)
            val datagram = assertNotNull(receiverDeferred.await())
            assertTrue(datagram.sourceIp.isNotBlank())
            assertEquals(sharedPort, datagram.sourcePort)
        } finally {
            transportB.close()
            transportA.close()
        }

        assertFalse(transportA.isOpen)
        assertFalse(transportB.isOpen)
        assertNull(transportA.receive())
    }
}


package br.edu.fileshare.network.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkInterfaceFilterTest {

    @Test
    fun `descarta interfaces virtuais docker wsl vpn e loopback`() {
        val rejectedNames = listOf(
            "docker0" to "docker0",
            "br-4a2b3c" to "br-4a2b3c",
            "veth9a8b7c" to "veth9a8b7c",
            "virbr0" to "virbr0",
            "vmnet8" to "VMware Network Adapter vmnet8",
            "vboxnet0" to "vboxnet0",
            "tun0" to "tun0",
            "tap0" to "tap0",
            "wg0" to "wg0",
            "tailscale0" to "tailscale0",
            "lo" to "Loopback Pseudo-Interface 1",
            "eth9" to "vEthernet (WSL)",
        )
        rejectedNames.forEach { (name, displayName) ->
            assertTrue(
                NetworkInterfaceFilter.isExcludedInterfaceName(name, displayName),
                "Interface '$name' ($displayName) deveria ser rejeitada",
            )
            assertNull(
                NetworkInterfaceFilter.selectValidRfc1918Ip(
                    name = name,
                    displayName = displayName,
                    isUp = true,
                    isLoopback = false,
                    supportsMulticast = true,
                    isVirtual = false,
                    ipv4Addresses = listOf("192.168.1.10"),
                ),
            )
        }
    }

    @Test
    fun `aceita faixas IPv4 RFC 1918 e rejeita loopback link-local ou publico`() {
        assertTrue(NetworkInterfaceFilter.isRfc1918Ipv4("10.0.0.1"))
        assertTrue(NetworkInterfaceFilter.isRfc1918Ipv4("172.16.0.1"))
        assertTrue(NetworkInterfaceFilter.isRfc1918Ipv4("172.31.255.254"))
        assertTrue(NetworkInterfaceFilter.isRfc1918Ipv4("192.168.0.42"))

        assertFalse(NetworkInterfaceFilter.isRfc1918Ipv4("127.0.0.1"))
        assertFalse(NetworkInterfaceFilter.isRfc1918Ipv4("169.254.10.1"))
        assertFalse(NetworkInterfaceFilter.isRfc1918Ipv4("172.15.255.255"))
        assertFalse(NetworkInterfaceFilter.isRfc1918Ipv4("172.32.0.1"))
        assertFalse(NetworkInterfaceFilter.isRfc1918Ipv4("8.8.8.8"))
        assertFalse(NetworkInterfaceFilter.isRfc1918Ipv4("not-an-ip"))
    }

    @Test
    fun `exige isUp notLoopback supportsMulticast notVirtual e endereco privado`() {
        assertEquals(
            "192.168.1.50",
            NetworkInterfaceFilter.selectValidRfc1918Ip(
                name = "wlan0",
                displayName = "Wi-Fi",
                isUp = true,
                isLoopback = false,
                supportsMulticast = true,
                isVirtual = false,
                ipv4Addresses = listOf("169.254.1.2", "192.168.1.50"),
            ),
        )
        assertNull(
            NetworkInterfaceFilter.selectValidRfc1918Ip(
                name = "wlan0",
                displayName = "Wi-Fi",
                isUp = false,
                isLoopback = false,
                supportsMulticast = true,
                isVirtual = false,
                ipv4Addresses = listOf("192.168.1.50"),
            ),
        )
        assertNull(
            NetworkInterfaceFilter.selectValidRfc1918Ip(
                name = "wlan0",
                displayName = "Wi-Fi",
                isUp = true,
                isLoopback = false,
                supportsMulticast = false,
                isVirtual = false,
                ipv4Addresses = listOf("192.168.1.50"),
            ),
        )
        assertNull(
            NetworkInterfaceFilter.selectValidRfc1918Ip(
                name = "wlan0",
                displayName = "Wi-Fi",
                isUp = true,
                isLoopback = false,
                supportsMulticast = true,
                isVirtual = true,
                ipv4Addresses = listOf("192.168.1.50"),
            ),
        )
        assertNull(
            NetworkInterfaceFilter.selectValidRfc1918Ip(
                name = "eth0",
                displayName = "Ethernet",
                isUp = true,
                isLoopback = false,
                supportsMulticast = true,
                isVirtual = false,
                ipv4Addresses = listOf("203.0.113.10"),
            ),
        )
    }
}


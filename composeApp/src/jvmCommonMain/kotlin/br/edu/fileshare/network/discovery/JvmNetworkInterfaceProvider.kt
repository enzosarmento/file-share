package br.edu.fileshare.network.discovery

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Representa uma interface de rede JVM pronta para `joinGroup` multicast e anuncio LAN.
 */
data class SelectedLanInterface(
    val networkInterface: NetworkInterface,
    val ipv4Address: Inet4Address,
    val info: LanInterfaceInfo,
)

/**
 * Provedor JVM/Android de interfaces de rede LAN com filtragem anti-Docker/VPN/Loopback (`TASK-DISC-001`).
 */
class JvmNetworkInterfaceProvider : NetworkInterfaceProvider {

    fun selectJoinableInterfaces(): List<SelectedLanInterface> {
        val enumeration = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()

        val selected = mutableListOf<SelectedLanInterface>()
        while (enumeration.hasMoreElements()) {
            val netIf = enumeration.nextElement()
            val ipv4Addresses = collectIpv4Addresses(netIf)
            val chosenIp = NetworkInterfaceFilter.selectValidRfc1918Ip(
                name = netIf.name ?: "",
                displayName = netIf.displayName ?: "",
                isUp = runCatching { netIf.isUp }.getOrDefault(false),
                isLoopback = runCatching { netIf.isLoopback }.getOrDefault(true),
                supportsMulticast = runCatching { netIf.supportsMulticast() }.getOrDefault(false),
                isVirtual = runCatching { netIf.isVirtual }.getOrDefault(true),
                ipv4Addresses = ipv4Addresses.mapNotNull { it.hostAddress },
            ) ?: continue

            val inet4 = ipv4Addresses.firstOrNull { it.hostAddress == chosenIp } ?: continue
            val name = netIf.name ?: chosenIp
            val displayName = netIf.displayName ?: name
            selected += SelectedLanInterface(
                networkInterface = netIf,
                ipv4Address = inet4,
                info = LanInterfaceInfo(
                    name = name,
                    displayName = displayName,
                    ipv4Address = chosenIp,
                ),
            )
        }

        return selected.sortedBy { it.info.name }
    }

    override fun listLanInterfaces(): List<LanInterfaceInfo> =
        selectJoinableInterfaces().map { it.info }

    private fun collectIpv4Addresses(netIf: NetworkInterface): List<Inet4Address> {
        val addrs = mutableListOf<Inet4Address>()
        val enumAddrs = netIf.inetAddresses
        while (enumAddrs.hasMoreElements()) {
            val addr = enumAddrs.nextElement()
            if (addr is Inet4Address) {
                addrs += addr
            }
        }
        return addrs
    }
}


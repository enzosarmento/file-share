package br.edu.fileshare.network.discovery

/**
 * Constantes operacionais do plano de descoberta UDP Multicast (`docs/discovery.md`, `FSAP/1.0`).
 */
object DiscoveryDefaults {
    const val MULTICAST_GROUP: String = "239.255.70.83"
    const val UDP_PORT: Int = 53318
    const val MULTICAST_TTL: Int = 1
    const val MAX_DATAGRAM_BYTES: Int = 1024
    const val ANNOUNCE_INTERVAL_MS: Long = 5_000L
    const val DISCOVER_JITTER_MIN_MS: Long = 50L
    const val DISCOVER_JITTER_MAX_MS: Long = 300L
    const val PEER_SWEEP_INTERVAL_MS: Long = 3_000L
}

/**
 * Datagrama UDP bruto recebido do grupo multicast com IP e porta reais extraidos do cabecalho IP/UDP.
 */
data class UdpDatagram(
    val bytes: ByteArray,
    val sourceIp: String,
    val sourcePort: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UdpDatagram) return false
        return bytes.contentEquals(other.bytes) &&
            sourceIp == other.sourceIp &&
            sourcePort == other.sourcePort
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + sourceIp.hashCode()
        result = 31 * result + sourcePort
        return result
    }
}

/**
 * Metadados de uma interface LAN fisica elegivel para descoberta FSAP/1.0.
 */
data class LanInterfaceInfo(
    val name: String,
    val displayName: String,
    val ipv4Address: String,
)

/**
 * Filtro deterministico multiplataforma de interfaces de rede LAN (`docs/discovery.md` Secao 2).
 */
object NetworkInterfaceFilter {
    val EXCLUDED_NAME_PREFIXES: List<String> = listOf(
        "docker",
        "br-",
        "veth",
        "virbr",
        "vmnet",
        "vboxnet",
        "tun",
        "tap",
        "wg",
        "tailscale",
        "lo",
    )

    fun isExcludedInterfaceName(name: String, displayName: String = name): Boolean {
        val lowerName = name.trim().lowercase()
        val lowerDisplay = displayName.trim().lowercase()
        if (lowerName.isEmpty()) return true
        return EXCLUDED_NAME_PREFIXES.any { prefix ->
            lowerName.startsWith(prefix) || lowerDisplay.startsWith(prefix)
        }
    }

    fun isRfc1918Ipv4(ipAddress: String): Boolean {
        val octets = parseIpv4Octets(ipAddress) ?: return false
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 10 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            else -> false
        }
    }

    /**
     * Valida os 6 criterios normativos de `docs/discovery.md` e retorna o primeiro IPv4 RFC 1918 valido, ou `null`.
     */
    fun selectValidRfc1918Ip(
        name: String,
        displayName: String,
        isUp: Boolean,
        isLoopback: Boolean,
        supportsMulticast: Boolean,
        isVirtual: Boolean,
        ipv4Addresses: List<String>,
    ): String? {
        if (!isUp || isLoopback || !supportsMulticast || isVirtual) return null
        if (isExcludedInterfaceName(name, displayName)) return null
        return ipv4Addresses.firstOrNull { isRfc1918Ipv4(it) }
    }

    private fun parseIpv4Octets(ip: String): IntArray? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        return octets
    }
}

/**
 * Porta multiplataforma para listar interfaces LAN validas para multicast e exibicao na UI.
 */
interface NetworkInterfaceProvider {
    fun listLanInterfaces(): List<LanInterfaceInfo>

    fun listLocalIpv4Addresses(): List<String> =
        listLanInterfaces().map { it.ipv4Address }.distinct()
}

/**
 * Transporte assincrono de datagramas UDP Multicast (`239.255.70.83:53318`, max 1024 bytes).
 */
interface MulticastTransport : AutoCloseable {
    val isOpen: Boolean
    val boundPort: Int

    suspend fun open()

    suspend fun send(bytes: ByteArray)

    /**
     * Aguarda o proximo datagrama do grupo multicast ou retorna `null` quando o transporte e fechado.
     */
    suspend fun receive(): UdpDatagram?

    override fun close()
}


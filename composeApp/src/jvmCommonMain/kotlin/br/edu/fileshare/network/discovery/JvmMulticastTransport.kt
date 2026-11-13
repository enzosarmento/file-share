package br.edu.fileshare.network.discovery

import br.edu.fileshare.core.logging.FsapLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.net.StandardSocketOptions

/**
 * Implementacao JVM/Android de [MulticastTransport] sobre `MulticastSocket` com TTL=1,
 * `SO_REUSEADDR=true` e `joinGroup` explicito nas interfaces fisicas selecionadas (`TASK-DISC-001`).
 */
class JvmMulticastTransport(
    private val groupIp: String = DiscoveryDefaults.MULTICAST_GROUP,
    private val port: Int = DiscoveryDefaults.UDP_PORT,
    private val ttl: Int = DiscoveryDefaults.MULTICAST_TTL,
    private val maxDatagramBytes: Int = DiscoveryDefaults.MAX_DATAGRAM_BYTES,
    private val interfaceProvider: JvmNetworkInterfaceProvider = JvmNetworkInterfaceProvider(),
    private val enableLoopback: Boolean = true,
    private val allowLoopbackFallback: Boolean = false,
    private val logger: FsapLogger? = null,
) : MulticastTransport {

    private val lifecycleMutex = Mutex()
    private val sendMutex = Mutex()

    @Volatile
    private var socket: MulticastSocket? = null

    @Volatile
    private var groupAddress: InetAddress? = null

    @Volatile
    private var joinedInterfaces: List<NetworkInterface> = emptyList()

    @Volatile
    private var openFlag: Boolean = false

    @Volatile
    private var activePort: Int = port

    override val isOpen: Boolean
        get() = openFlag

    override val boundPort: Int
        get() = activePort

    override suspend fun open(): Unit {
        lifecycleMutex.withLock {
            if (openFlag) return@withLock

            withContext(Dispatchers.IO) {
                val multicastSocket = MulticastSocket(null)
                try {
                    multicastSocket.reuseAddress = true
                    multicastSocket.bind(InetSocketAddress(port))
                    multicastSocket.timeToLive = ttl
                    runCatching {
                        multicastSocket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, enableLoopback)
                    }

                    val resolvedGroup = InetAddress.getByName(groupIp)
                    val resolvedPort = multicastSocket.localPort
                    val selectedNics = interfaceProvider.selectJoinableInterfaces()
                    val joined = mutableListOf<NetworkInterface>()

                    if (selectedNics.isNotEmpty()) {
                        val groupSocketAddr = InetSocketAddress(resolvedGroup, resolvedPort)
                        for (item in selectedNics) {
                            runCatching {
                                multicastSocket.joinGroup(groupSocketAddr, item.networkInterface)
                                joined += item.networkInterface
                            }.onFailure { err ->
                                logger?.warn(
                                    component = "JvmMulticastTransport",
                                    message = "Falha ao ingressar multicast na interface ${item.info.name} (${item.info.ipv4Address}): ${err.message}",
                                )
                            }
                        }
                    } else if (allowLoopbackFallback) {
                        val loopbackNic = runCatching {
                            NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress())
                        }.getOrNull()
                        val groupSocketAddr = InetSocketAddress(resolvedGroup, resolvedPort)
                        val joinedSpecific = loopbackNic != null && runCatching {
                            multicastSocket.joinGroup(groupSocketAddr, loopbackNic)
                            joined += loopbackNic
                        }.isSuccess
                        if (!joinedSpecific) {
                            @Suppress("DEPRECATION")
                            multicastSocket.joinGroup(resolvedGroup)
                        }
                    }

                    if (joined.isEmpty() && selectedNics.isNotEmpty()) {
                        throw SocketException("Nenhuma interface LAN conseguiu ingressar no grupo multicast $groupIp:$resolvedPort.")
                    }

                    socket = multicastSocket
                    groupAddress = resolvedGroup
                    joinedInterfaces = joined
                    activePort = resolvedPort
                    openFlag = true

                    logger?.info(
                        component = "JvmMulticastTransport",
                        message = "Socket UDP multicast aberto em $groupIp:$resolvedPort (interfaces=${joined.map { it.name }}).",
                    )
                } catch (ex: Throwable) {
                    runCatching { multicastSocket.close() }
                    socket = null
                    groupAddress = null
                    joinedInterfaces = emptyList()
                    openFlag = false
                    throw ex
                }
            }
        }
    }

    override suspend fun send(bytes: ByteArray) {
        require(bytes.size <= maxDatagramBytes) {
            "Datagrama FSAP excede limite maximo de $maxDatagramBytes bytes (tamanho=${bytes.size})."
        }
        val activeSocket = socket ?: throw IllegalStateException("Transporte multicast nao esta aberto.")
        val targetGroup = groupAddress ?: throw IllegalStateException("Endereco multicast nao resolvido.")
        if (!openFlag) throw IllegalStateException("Transporte multicast encerrado.")

        sendMutex.withLock {
            withContext(Dispatchers.IO) {
                val packet = DatagramPacket(bytes, bytes.size, targetGroup, activePort)
                if (joinedInterfaces.isNotEmpty()) {
                    var lastError: Throwable? = null
                    var sentCount = 0
                    for (netIf in joinedInterfaces) {
                        try {
                            activeSocket.networkInterface = netIf
                            activeSocket.send(packet)
                            sentCount++
                        } catch (ex: Throwable) {
                            lastError = ex
                            logger?.warn(
                                component = "JvmMulticastTransport",
                                message = "Falha ao enviar datagrama pela interface ${netIf.name}: ${ex.message}",
                            )
                        }
                    }
                    if (sentCount == 0 && lastError != null) {
                        throw lastError
                    }
                } else {
                    activeSocket.send(packet)
                }
            }
        }
    }

    override suspend fun receive(): UdpDatagram? {
        val activeSocket = socket ?: return null
        if (!openFlag) return null

        return withContext(Dispatchers.IO) {
            val buffer = ByteArray(maxDatagramBytes)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                activeSocket.receive(packet)
                if (!openFlag) return@withContext null
                UdpDatagram(
                    bytes = buffer.copyOf(packet.length),
                    sourceIp = packet.address?.hostAddress ?: "",
                    sourcePort = packet.port,
                )
            } catch (ex: SocketException) {
                if (!openFlag || activeSocket.isClosed) {
                    null
                } else {
                    throw ex
                }
            }
        }
    }

    override fun close() {
        openFlag = false
        val currentSocket = socket
        val currentGroup = groupAddress
        val currentJoined = joinedInterfaces

        socket = null
        groupAddress = null
        joinedInterfaces = emptyList()

        if (currentSocket != null && !currentSocket.isClosed) {
            if (currentGroup != null) {
                val groupAddr = InetSocketAddress(currentGroup, activePort)
                for (netIf in currentJoined) {
                    runCatching { currentSocket.leaveGroup(groupAddr, netIf) }
                }
                if (currentJoined.isEmpty()) {
                    @Suppress("DEPRECATION")
                    runCatching { currentSocket.leaveGroup(currentGroup) }
                }
            }
            runCatching { currentSocket.close() }
        }
    }
}

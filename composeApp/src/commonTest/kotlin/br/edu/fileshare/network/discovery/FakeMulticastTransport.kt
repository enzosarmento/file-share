package br.edu.fileshare.network.discovery

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Implementacao deterministica em memoria de [MulticastTransport] para testes unitarios e de conformidade.
 */
class FakeMulticastTransport(
    override val boundPort: Int = DiscoveryDefaults.UDP_PORT,
) : MulticastTransport {

    private val mutex = Mutex()
    private val incoming = Channel<UdpDatagram>(Channel.UNLIMITED)

    val sentPayloads: MutableList<ByteArray> = mutableListOf()
    var openCalls: Int = 0
        private set
    var closeCalls: Int = 0
        private set
    var throwOnSend: Throwable? = null

    @Volatile
    private var openState: Boolean = true

    override val isOpen: Boolean
        get() = openState

    override suspend fun open() {
        openState = true
        openCalls++
    }

    override suspend fun send(bytes: ByteArray) {
        throwOnSend?.let { throw it }
        check(openState) { "FakeMulticastTransport fechado" }
        mutex.withLock {
            sentPayloads += bytes.copyOf()
        }
    }

    override suspend fun receive(): UdpDatagram? {
        if (!openState) return null
        val result = incoming.receiveCatching()
        return result.getOrNull()
    }

    fun enqueueIncoming(datagram: UdpDatagram) {
        incoming.trySend(datagram)
    }

    fun enqueueIncomingText(
        json: String,
        sourceIp: String = "192.168.1.50",
        sourcePort: Int = DiscoveryDefaults.UDP_PORT,
    ) {
        enqueueIncoming(
            UdpDatagram(
                bytes = json.encodeToByteArray(),
                sourceIp = sourceIp,
                sourcePort = sourcePort,
            ),
        )
    }

    suspend fun snapshotSentStrings(): List<String> = mutex.withLock {
        sentPayloads.map { it.decodeToString() }
    }

    override fun close() {
        openState = false
        closeCalls++
        incoming.close()
    }
}


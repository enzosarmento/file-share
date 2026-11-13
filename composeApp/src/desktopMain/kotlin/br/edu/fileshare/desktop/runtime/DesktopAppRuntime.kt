package br.edu.fileshare.desktop.runtime

import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.core.logging.FsapLogger
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.core.time.SystemClock
import br.edu.fileshare.desktop.di.DesktopServerContainer
import br.edu.fileshare.desktop.server.detectDesktopPlatform
import br.edu.fileshare.desktop.storage.DesktopFilePicker
import br.edu.fileshare.desktop.storage.DesktopNioFileSink
import br.edu.fileshare.network.client.FsapHttpClient
import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.network.discovery.ManualPeerConnector
import br.edu.fileshare.network.discovery.PeerRegistry
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.InteractiveTransferApprovalCoordinator
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.network.transfer.TransferCoordinator
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.StoredFileDescriptor
import br.edu.fileshare.ui.viewmodel.FileShareRuntimePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runtime unificado do Cliente Desktop com UI + Servidor FSAP local (`TASK-DCLI-001`).
 */
class DesktopAppRuntime(
    private val container: DesktopServerContainer = DesktopServerContainer(),
    override val initialAlias: String = defaultDesktopAlias(),
    override val initialPort: Int = FsapConstants.DEFAULT_HTTP_PORT,
    val downloadDirectory: Path = defaultDownloadsDir(),
) : FileShareRuntimePort {

    val logger: FsapLogger = container.logger
    val clock: Clock = container.clock
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val localDeviceId: String = PlatformUuidGenerator.nextUuidV4()
    override val saveDirectoryLabel: String = downloadDirectory.toAbsolutePath().normalize().toString()

    val peerRegistry = PeerRegistry(
        clock = clock,
        logger = logger,
    )
    override val peers: StateFlow<List<DiscoveredPeer>> = peerRegistry.peers

    private val _receivedFiles = MutableStateFlow<List<StoredFileDescriptor>>(emptyList())
    override val receivedFiles: StateFlow<List<StoredFileDescriptor>> = _receivedFiles.asStateFlow()

    private val _localAddresses = MutableStateFlow(enumerateLocalIpv4Addresses())
    override val localAddresses: StateFlow<List<String>> = _localAddresses.asStateFlow()

    val approvalCoordinator = InteractiveTransferApprovalCoordinator()

    val fileSink = DesktopNioFileSink(
        targetDirectory = downloadDirectory,
        onCommitted = { stored ->
            _receivedFiles.update { current -> listOf(stored) + current }
        },
    )

    private var activeIdentity = buildIdentity(initialAlias, initialPort)
    private val tokenManager = TransferTokenManager(clock = clock, logger = logger)
    val sessionManager: ServerTransferSessionManager = createSessionManager()

    val httpClient = FsapHttpClient(localIdentity = activeIdentity, logger = logger, clock = clock)

    var manualConnector = createManualConnector(activeIdentity)
        private set

    val transferCoordinator = TransferCoordinator(
        httpClient = httpClient,
        parentScope = scope,
        serverSessionManager = sessionManager,
        logger = logger,
        clock = clock,
    )

    private var httpServer: FsapHttpServer? = null

    init {
        loadExistingReceivedFiles()
    }

    override suspend fun startNode(alias: String, port: Int): Result<Int> = runCatching {
        stopActiveServerOnly()

        activeIdentity = buildIdentity(alias, port)

        manualConnector.close()
        manualConnector = createManualConnector(activeIdentity)

        val server = FsapHttpServer(
            config = FsapHttpServerConfig(
                identity = activeIdentity,
                host = "0.0.0.0",
                port = port,
            ),
            logger = logger,
            clock = clock,
            transferServices = FsapTransferServerServices(
                sessionManager = sessionManager,
                fileSink = fileSink,
                approvalHandler = approvalCoordinator,
            ),
        )
        val boundPort = server.start()
        activeIdentity = activeIdentity.copy(httpPort = boundPort)
        httpServer = server
        _localAddresses.value = enumerateLocalIpv4Addresses()
        boundPort
    }

    override suspend fun stopNode() {
        stopActiveServerOnly()
    }

    override suspend fun pickFilesToSend(): List<FileSource> = DesktopFilePicker.pickMultipleFiles()

    override suspend fun sendFiles(peer: DiscoveredPeer, sources: List<FileSource>): Result<String> {
        val transferId = PlatformUuidGenerator.nextUuidV4()
        transferCoordinator.launchSendFiles(
            peer = peer,
            sources = sources,
            transferId = transferId,
        )
        return Result.success(transferId)
    }

    override suspend fun refreshDiscovery(): Result<Unit> {
        _localAddresses.value = enumerateLocalIpv4Addresses()
        return Result.success(Unit)
    }

    private val isClosed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        stopActiveServerOnly()
        runBlocking {
            withTimeoutOrNull(FsapConstants.T_SHUTDOWN_GRACE_MS) {
                runCatching { transferCoordinator.close() }
                runCatching { sessionManager.close() }
            }
        }
        manualConnector.close()
        httpClient.close()
        scope.cancel("Aplicacao Desktop encerrada.")
    }

    private fun stopActiveServerOnly() {
        httpServer?.let { srv ->
            runCatching {
                srv.stop(
                    gracePeriodMillis = FsapConstants.T_SHUTDOWN_GRACE_MS,
                    timeoutMillis = FsapConstants.T_SHUTDOWN_GRACE_MS,
                )
            }
        }
        httpServer = null
    }

    private fun buildIdentity(alias: String, port: Int): DeviceIdentity = DeviceIdentity(
        deviceId = localDeviceId,
        alias = alias.take(FsapConstants.MAX_ALIAS_LENGTH).ifBlank { defaultDesktopAlias() },
        deviceType = DeviceType.DESKTOP,
        platform = detectDesktopPlatform(),
        protocolVersion = FsapConstants.PROTOCOL_VERSION,
        httpPort = port,
    )

    private fun createSessionManager(): ServerTransferSessionManager = ServerTransferSessionManager(
        tokenManager = tokenManager,
        fileSink = fileSink,
        scope = scope,
        logger = logger,
        clock = clock,
    )

    private fun createManualConnector(identity: DeviceIdentity): ManualPeerConnector = ManualPeerConnector(
        localIdentity = identity,
        peerRegistry = peerRegistry,
        logger = logger,
        clock = clock,
    )

    private fun loadExistingReceivedFiles() {
        runCatching {
            val dir = downloadDirectory.toAbsolutePath().normalize()
            if (!Files.isDirectory(dir)) return
            val existing = Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map { path ->
                        StoredFileDescriptor(
                            finalName = path.fileName.toString(),
                            locationUri = path.toUri().toString(),
                            sizeBytes = Files.size(path),
                            sha256 = "local",
                        )
                    }
                    .toList()
            }
            if (existing.isNotEmpty()) {
                _receivedFiles.value = existing
            }
        }
    }

    companion object {
        fun defaultDesktopAlias(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != "localhost" }
            return (host ?: System.getProperty("user.name") ?: "FileShare-Desktop")
                .take(FsapConstants.MAX_ALIAS_LENGTH)
        }

        fun defaultDownloadsDir(): Path {
            val home = Path.of(System.getProperty("user.home", "."))
            return home.resolve("Downloads").resolve("FileShare").toAbsolutePath().normalize()
        }

        fun enumerateLocalIpv4Addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .filter { iface -> iface.isUp && !iface.isLoopback && !iface.isVirtual }
                .flatMap { iface -> iface.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { addr -> !addr.isLoopbackAddress && !addr.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrElse { emptyList() }
    }
}

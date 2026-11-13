package br.edu.fileshare.desktop.server

import br.edu.fileshare.ProjectInfo
import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.desktop.di.DesktopServerContainer
import br.edu.fileshare.desktop.storage.DesktopNioFileSink
import br.edu.fileshare.network.discovery.DiscoveryCoordinator
import br.edu.fileshare.network.discovery.DiscoveryDefaults
import br.edu.fileshare.network.discovery.JvmMulticastTransport
import br.edu.fileshare.network.discovery.JvmNetworkInterfaceProvider
import br.edu.fileshare.network.discovery.PeerRegistry
import br.edu.fileshare.network.server.AutoAcceptTransferApprovalHandler
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.network.server.ServerTransferSessionManager
import br.edu.fileshare.network.server.TransferApprovalHandler
import br.edu.fileshare.network.server.TransferTokenManager
import br.edu.fileshare.network.server.routes.FsapTransferServerServices
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Conjunto coeso dos servicos de rede e armazenamento do Servidor Desktop Headless (`TASK-DCLI-004`).
 */
internal class DesktopHeadlessServerRuntime(
    val identity: DeviceIdentity,
    val fileSink: DesktopNioFileSink,
    val tokenManager: TransferTokenManager,
    val sessionManager: ServerTransferSessionManager,
    val server: FsapHttpServer,
    val discoveryCoordinator: DiscoveryCoordinator? = null,
    val networkInterfaceProvider: JvmNetworkInterfaceProvider = JvmNetworkInterfaceProvider(),
    private val serverScope: CoroutineScope,
) {
    @Volatile
    private var currentIdentity: DeviceIdentity = identity

    fun activeIdentity(): DeviceIdentity = currentIdentity

    suspend fun start(): Int {
        val boundPort = server.start()
        currentIdentity = identity.copy(httpPort = boundPort)
        discoveryCoordinator?.startBestEffort()
        return boundPort
    }

    fun stopGracefully() {
        runCatching {
            runBlocking {
                withTimeoutOrNull(FsapConstants.T_SHUTDOWN_GRACE_MS) {
                    discoveryCoordinator?.stop(reason = "SERVICE_STOPPED")
                }
            }
        }
        runCatching {
            server.stop(
                gracePeriodMillis = FsapConstants.T_SHUTDOWN_GRACE_MS,
                timeoutMillis = FsapConstants.T_SHUTDOWN_GRACE_MS,
            )
        }
        serverScope.cancel("Servidor Desktop Headless encerrado.")
    }
}

internal fun createDesktopHeadlessServer(
    config: ServerCliConfig,
    container: DesktopServerContainer = DesktopServerContainer(),
    approvalHandlerOverride: TransferApprovalHandler? = null,
    enableDiscovery: Boolean = true,
    discoveryPort: Int = DiscoveryDefaults.UDP_PORT,
): DesktopHeadlessServerRuntime {
    val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val identity = DeviceIdentity(
        deviceId = PlatformUuidGenerator.nextUuidV4(),
        alias = config.alias,
        deviceType = DeviceType.SERVER,
        platform = detectDesktopPlatform(),
        protocolVersion = FsapConstants.PROTOCOL_VERSION,
        httpPort = config.port,
    )
    val fileSink = DesktopNioFileSink(config.downloadDir)
    val tokenManager = TransferTokenManager(
        clock = container.clock,
        logger = container.logger,
    )
    val sessionManager = ServerTransferSessionManager(
        tokenManager = tokenManager,
        fileSink = fileSink,
        scope = serverScope,
        logger = container.logger,
        clock = container.clock,
    )
    val approvalHandler = approvalHandlerOverride ?: if (config.autoAcceptTest) {
        AutoAcceptTransferApprovalHandler
    } else {
        ConsoleTransferApprovalHandler()
    }
    val server = FsapHttpServer(
        config = FsapHttpServerConfig(
            identity = identity,
            host = "0.0.0.0",
            port = config.port,
        ),
        logger = container.logger,
        clock = container.clock,
        transferServices = FsapTransferServerServices(
            sessionManager = sessionManager,
            fileSink = fileSink,
            approvalHandler = approvalHandler,
        ),
    )

    val nicProvider = JvmNetworkInterfaceProvider()
    val peerRegistry = PeerRegistry(clock = container.clock, logger = container.logger)
    var runtimeRef: DesktopHeadlessServerRuntime? = null
    val discoveryCoordinator = if (enableDiscovery) {
        DiscoveryCoordinator(
            transport = JvmMulticastTransport(
                port = discoveryPort,
                interfaceProvider = nicProvider,
                logger = container.logger,
            ),
            peerRegistry = peerRegistry,
            localIdentityProvider = { runtimeRef?.activeIdentity() ?: identity },
            scope = serverScope,
            clock = container.clock,
            logger = container.logger,
        )
    } else {
        null
    }

    return DesktopHeadlessServerRuntime(
        identity = identity,
        fileSink = fileSink,
        tokenManager = tokenManager,
        sessionManager = sessionManager,
        server = server,
        discoveryCoordinator = discoveryCoordinator,
        networkInterfaceProvider = nicProvider,
        serverScope = serverScope,
    ).also { runtimeRef = it }
}

/**
 * Ponto de entrada independente do Servidor Desktop (Headless / CLI).
 *
 * Responsabilidade arquitetural (Secao 4.1):
 * - Permitir inicializacao e execucao do servidor HTTP/TCP e do servico de anuncio UDP
 *   de forma independente do aplicativo grafico (Compose UI).
 * - Nao possui acoplamento com componentes visuais (`Compose`, `AWT` ou `Swing`).
 */
fun main(args: Array<String>) {
    val config = try {
        ServerCliParser.parse(args)
    } catch (ex: CliParseException) {
        System.err.println("Erro nos argumentos CLI: ${ex.message}")
        System.err.println(ServerCliParser.usage())
        exitProcess(1)
    }

    if (config.showHelp) {
        println(ServerCliParser.usage())
        return
    }

    val runtime = createDesktopHeadlessServer(config)
    val boundPort = runBlocking { runtime.start() }

    println("[${ProjectInfo.PROJECT_NAME}] Servidor Desktop Independente (${ProjectInfo.PROTOCOL_NAME}/${ProjectInfo.PROTOCOL_VERSION})")
    val lanIps = runtime.networkInterfaceProvider.listLocalIpv4Addresses()
    println("Configuracao ativa:")
    println("  - Device ID      : ${runtime.identity.deviceId}")
    println("  - Porta HTTP/TCP : $boundPort")
    println("  - Descoberta UDP : ${DiscoveryDefaults.MULTICAST_GROUP}:${DiscoveryDefaults.UDP_PORT} (ativa=${runtime.discoveryCoordinator?.isRunning == true})")
    println("  - IPs LAN        : ${if (lanIps.isEmpty()) "127.0.0.1" else lanIps.joinToString(", ")}")
    println("  - Alias          : ${runtime.identity.alias}")
    println("  - Plataforma     : ${runtime.identity.platform}")
    println("  - Download dir   : ${runtime.fileSink.targetDirectory}")
    println("  - Auto-accept    : ${config.autoAcceptTest}")
    println("Servidor pronto. Pressione Ctrl+C (SIGINT) ou envie SIGTERM para encerrar graciosamente.")

    val shutdownLatch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("Recebido sinal de encerramento. Parando servidor FSAP...")
            runtime.stopGracefully()
            shutdownLatch.countDown()
        },
    )

    shutdownLatch.await()
}

internal fun detectDesktopPlatform(
    osName: String = System.getProperty("os.name").orEmpty(),
): PlatformType = if (osName.contains("win", ignoreCase = true)) {
    PlatformType.WINDOWS
} else {
    PlatformType.LINUX
}

package br.edu.fileshare.desktop.server

import br.edu.fileshare.ProjectInfo
import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.desktop.di.DesktopServerContainer
import br.edu.fileshare.desktop.storage.DesktopNioFileSink
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
    private val serverScope: CoroutineScope,
) {
    suspend fun start(): Int = server.start()

    fun stopGracefully() {
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
    return DesktopHeadlessServerRuntime(
        identity = identity,
        fileSink = fileSink,
        tokenManager = tokenManager,
        sessionManager = sessionManager,
        server = server,
        serverScope = serverScope,
    )
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
    println("Configuracao ativa:")
    println("  - Device ID      : ${runtime.identity.deviceId}")
    println("  - Porta HTTP/TCP : $boundPort")
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

package br.edu.fileshare.desktop.server

import br.edu.fileshare.ProjectInfo
import br.edu.fileshare.core.id.PlatformUuidGenerator
import br.edu.fileshare.desktop.di.DesktopServerContainer
import br.edu.fileshare.network.server.FsapHttpServer
import br.edu.fileshare.network.server.FsapHttpServerConfig
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

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

    val container = DesktopServerContainer()
    val identity = DeviceIdentity(
        deviceId = PlatformUuidGenerator.nextUuidV4(),
        alias = config.alias,
        deviceType = DeviceType.SERVER,
        platform = detectDesktopPlatform(),
        protocolVersion = FsapConstants.PROTOCOL_VERSION,
        httpPort = config.port,
    )

    val server = FsapHttpServer(
        config = FsapHttpServerConfig(
            identity = identity,
            host = "0.0.0.0",
            port = config.port,
        ),
        logger = container.logger,
        clock = container.clock,
    )

    val boundPort = runBlocking { server.start() }

    println("[${ProjectInfo.PROJECT_NAME}] Servidor Desktop Independente (${ProjectInfo.PROTOCOL_NAME}/${ProjectInfo.PROTOCOL_VERSION})")
    println("Configuracao ativa:")
    println("  - Device ID      : ${identity.deviceId}")
    println("  - Porta HTTP/TCP : $boundPort")
    println("  - Alias          : ${identity.alias}")
    println("  - Plataforma     : ${identity.platform}")
    println("  - Download dir   : ${config.downloadDir.toAbsolutePath()}")
    println("  - Auto-accept    : ${config.autoAcceptTest}")
    println("Servidor pronto. Pressione Ctrl+C (SIGINT) ou envie SIGTERM para encerrar graciosamente.")

    val shutdownLatch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("Recebido sinal de encerramento. Parando servidor FSAP...")
            runCatching {
                server.stop(
                    gracePeriodMillis = FsapConstants.T_SHUTDOWN_GRACE_MS,
                    timeoutMillis = FsapConstants.T_SHUTDOWN_GRACE_MS,
                )
            }
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

package br.edu.fileshare.desktop.server

import br.edu.fileshare.ProjectInfo
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

    println("[${ProjectInfo.PROJECT_NAME}] Servidor Desktop Independente (${ProjectInfo.PROTOCOL_NAME}/${ProjectInfo.PROTOCOL_VERSION})")
    println("Configuracao inicial:")
    println("  - Porta HTTP/TCP : ${config.port}")
    println("  - Alias          : ${config.alias}")
    println("  - Download dir   : ${config.downloadDir.toAbsolutePath()}")
    println("  - Auto-accept    : ${config.autoAcceptTest}")
}

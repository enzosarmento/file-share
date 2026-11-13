package br.edu.fileshare.desktop.server

import java.net.InetAddress
import java.nio.file.Path

/**
 * Configuracao resolvida a partir dos argumentos CLI do Servidor Desktop Independente.
 */
data class ServerCliConfig(
    val port: Int = DEFAULT_PORT,
    val alias: String = defaultAlias(),
    val downloadDir: Path = defaultDownloadDir(),
    val autoAcceptTest: Boolean = false,
    val showHelp: Boolean = false,
) {
    companion object {
        const val DEFAULT_PORT: Int = 53317
        const val MIN_PORT: Int = 1
        const val MAX_PORT: Int = 65535
        const val MAX_ALIAS_LENGTH: Int = 64

        fun defaultAlias(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }
                .getOrNull()
                ?.trim()
                .orEmpty()
            val candidate = if (host.isNotBlank()) "FileShare-$host" else "FileShare-Desktop"
            return candidate.take(MAX_ALIAS_LENGTH)
        }

        fun defaultDownloadDir(): Path {
            val userHome = System.getProperty("user.home").orEmpty().ifBlank { "." }
            return Path.of(userHome, "Downloads", "FileShare")
        }
    }
}

/**
 * Erro lancado quando argumentos invalidos sao passados ao Servidor Desktop Independente.
 */
class CliParseException(message: String) : IllegalArgumentException(message)

/**
 * Parser puro de argumentos de linha de comando para [ServerMainKt].
 * Nao depende de bibliotecas graficas (`Compose`, `AWT` ou `Swing`).
 */
object ServerCliParser {

    fun parse(args: Array<String>): ServerCliConfig {
        var port = ServerCliConfig.DEFAULT_PORT
        var alias = ServerCliConfig.defaultAlias()
        var downloadDir = ServerCliConfig.defaultDownloadDir()
        var autoAcceptTest = false
        var showHelp = false

        var index = 0
        while (index < args.size) {
            val rawArg = args[index]
            val equalsIndex = rawArg.indexOf('=')
            val option = if (equalsIndex > 0) rawArg.substring(0, equalsIndex) else rawArg
            val inlineValue = if (equalsIndex > 0) rawArg.substring(equalsIndex + 1) else null

            fun requireValue(): String {
                if (inlineValue != null) return inlineValue
                if (index + 1 >= args.size) {
                    throw CliParseException("Opcao '$option' requer um valor.")
                }
                index += 1
                return args[index]
            }

            when (option) {
                "-h", "--help" -> {
                    if (inlineValue != null) {
                        throw CliParseException("Opcao '$option' nao aceita valor.")
                    }
                    showHelp = true
                }

                "--port" -> {
                    val rawPort = requireValue()
                    val parsedPort = rawPort.toIntOrNull()
                        ?: throw CliParseException("Valor invalido para --port: '$rawPort'. Informe um inteiro entre 1 e 65535.")
                    if (parsedPort !in ServerCliConfig.MIN_PORT..ServerCliConfig.MAX_PORT) {
                        throw CliParseException("Porta fora do intervalo permitido (1..65535): $parsedPort.")
                    }
                    port = parsedPort
                }

                "--alias" -> {
                    val parsedAlias = requireValue().trim()
                    if (parsedAlias.isEmpty()) {
                        throw CliParseException("O valor de --alias nao pode ser vazio.")
                    }
                    if (parsedAlias.length > ServerCliConfig.MAX_ALIAS_LENGTH) {
                        throw CliParseException(
                            "O valor de --alias excede ${ServerCliConfig.MAX_ALIAS_LENGTH} caracteres."
                        )
                    }
                    alias = parsedAlias
                }

                "--download-dir" -> {
                    val rawDir = requireValue().trim()
                    if (rawDir.isEmpty()) {
                        throw CliParseException("O valor de --download-dir nao pode ser vazio.")
                    }
                    downloadDir = Path.of(rawDir).normalize()
                }

                "--auto-accept-test" -> {
                    autoAcceptTest = parseBooleanFlag(option, inlineValue)
                }

                else -> throw CliParseException("Argumento desconhecido: '$rawArg'.")
            }
            index += 1
        }

        return ServerCliConfig(
            port = port,
            alias = alias,
            downloadDir = downloadDir,
            autoAcceptTest = autoAcceptTest,
            showHelp = showHelp,
        )
    }

    private fun parseBooleanFlag(option: String, inlineValue: String?): Boolean {
        if (inlineValue == null) return true
        return when (inlineValue.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> throw CliParseException("Valor booleano invalido para $option: '$inlineValue'.")
        }
    }

    fun usage(): String = buildString {
        appendLine("Uso: fileshare-server [opcoes]")
        appendLine("Opcoes suportadas:")
        appendLine("  --port <1..65535>        Porta TCP/HTTP do servidor FSAP (padrao: ${ServerCliConfig.DEFAULT_PORT})")
        appendLine("  --alias <nome>           Alias visivel na rede local (1..${ServerCliConfig.MAX_ALIAS_LENGTH} caracteres)")
        appendLine("  --download-dir <caminho> Diretorio de destino dos arquivos recebidos")
        appendLine("  --auto-accept-test       Aceita transferencias automaticamente (apenas testes/headless)")
        appendLine("  -h, --help               Exibe esta ajuda")
    }
}


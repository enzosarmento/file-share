package br.edu.fileshare.desktop.server

import br.edu.fileshare.ProjectInfo

/**
 * Ponto de entrada independente do Servidor Desktop (Headless / CLI).
 *
 * Responsabilidade arquitetural (Secao 4.1):
 * - Permitir inicializacao e execucao do servidor HTTP/TCP e do servico de anuncio UDP
 *   de forma independente do aplicativo grafico (Compose UI).
 * - Nao possui acoplamento com componentes visuais.
 *
 * Status: Aguardando implementacao nas Milestones M1/M2 (ver `tasks/backlog.md`).
 */
fun main(args: Array<String>) {
    println("[${ProjectInfo.PROJECT_NAME}] Servidor Desktop Independente (${ProjectInfo.PROTOCOL_NAME}/${ProjectInfo.PROTOCOL_VERSION})")
    println("Milestone atual: ${ProjectInfo.CURRENT_MILESTONE}")
    println("Argumentos recebidos: ${args.joinToString(", ").ifEmpty { "(nenhum)" }}")
    println("Estrutura inicial validada. Servidor de rede nao implementado na etapa M0.")
}


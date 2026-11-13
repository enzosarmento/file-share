package br.edu.fileshare.desktop

import br.edu.fileshare.ProjectInfo

/**
 * Ponto de entrada planejado para o Cliente Grafico Desktop (Compose Multiplatform).
 *
 * Responsabilidade arquitetural (Secao 4.1):
 * - Inicializar apenas a camada de apresentacao Desktop e orquestracao do cliente.
 * - O servico de rede/servidor Desktop possui entrypoint independente em
 *   [br.edu.fileshare.desktop.server.main].
 *
 * Status: Aguardando implementacao nas Milestones M1/M2 (ver `tasks/backlog.md`).
 */
fun main() {
    println("[${ProjectInfo.PROJECT_NAME}] Cliente Desktop (GUI) - ${ProjectInfo.CURRENT_MILESTONE}")
    println("Estrutura inicial validada. Funcionalidades de UI e rede nao implementadas na etapa M0.")
}


---
name: compose-multiplatform-ui
description: >-
  Use esta skill ao desenvolver telas, componentes visuais, dialogos de confirmacao
  de transferencia, barras de progresso e ViewModels reativos (StateFlow) em
  Compose Multiplatform para Desktop e Android no projeto FileShare.
---

# Skill: Compose Multiplatform UI (`compose-multiplatform-ui`)

## 1. Nome
`compose-multiplatform-ui`

## 2. Descrição
Diretrizes e padrões para construção da interface gráfica compartilhada e adaptativa (Desktop Windows/Linux e Android) utilizando Compose Multiplatform (Material 3) e gerenciamento de estado unidirecional (`UDF`).

## 3. Objetivo
Construir telas responsivas, acessíveis e livres de bloqueio da Main Thread para descoberta de dispositivos, conexão manual por IP, seleção de arquivos, confirmação explícita do destinatário e acompanhamento de progresso em tempo real.

## 4. Conhecimentos Necessários
- Compose Multiplatform (`compose.material3`, `compose.foundation`, `compose.runtime`).
- Fluxo de Dados Unidirecional (`StateFlow`, `SharedFlow`, `collectAsState`).
- Responsividade entre janelas redimensionáveis Desktop e telas móveis Android (retrato/paisagem).

## 5. Contexto Técnico
- Requisitos atendidos na UI: `RF-04` (listagem de dispositivos), `RF-05` (conexão manual por IP), `RF-06` (seleção de arquivos), `RF-08` (modal de confirmação explícita), `RF-10` (barra de progresso), `RF-11` (botão de cancelamento), `RF-13` (exibição clara de erros FSAP) e `RNF-08` (proibição de I/O na UI Thread).
- Documentos de referência: [docs/architecture.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/architecture.md) e [docs/state-machine.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/state-machine.md).

## 6. Escopo de Atuação
- Criação de `UiState` imutáveis e `ViewModels`/Presenters em `commonMain`.
- Componentes visuais para lista de dispositivos (`DISCOVERED`, `CONNECTED`, `STALE`), modal de aprovação de transferência com regressiva de 60 s (`PENDING_APPROVAL`) e painel de progresso (`TRANSFERRING`, `VERIFYING`, `COMPLETED`, `CANCELLED`, `FAILED`).

## 7. Arquivos e Módulos que Pode Modificar
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/ui/**`
- `composeApp/src/desktopMain/kotlin/br/edu/fileshare/desktop/Main.kt`
- `composeApp/src/androidMain/kotlin/br/edu/fileshare/android/MainActivity.kt`

## 8. Restrições
1. **Zero Lógica de Rede na UI:** Composables e ViewModels nunca podem instanciar sockets, clientes HTTP ou calcular hashes SHA-256 diretamente; devem acionar os coordenadores de domínio.
2. **Throttling de Recomposição:** Estados de barra de progresso não devem atualizar mais rápido que a cada `100 ms`.
3. **Confirmação Obrigatória:** O modal de recebimento (`IncomingTransferApprovalDialog`) deve exibir claramente o nome do remetente, nome de cada arquivo e tamanho em bytes antes dos botões "Aceitar" e "Recusar".

## 9. Dependências
- `kotlin-multiplatform-architecture`
- `fsap-protocol`

## 10. Critérios de Conclusão
- Todas as 9 fases de `TransferState` e os erros de `docs/error-codes.md` possuem representação visual clara na interface.
- Nenhuma operação de UI causa congelamento de quadros (jank) durante transferências de alta velocidade.


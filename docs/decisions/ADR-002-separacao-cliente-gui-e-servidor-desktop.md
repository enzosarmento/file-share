# ADR-002: Separação entre Cliente Gráfico e Servidor Desktop Independente

- **Status:** Aprovada (Premissa Obrigatória — Seção 4.1)
- **Data:** 2026-11-12
- **Contexto:** Milestone M0 — Planejamento e Arquitetura

## Contexto
O requisito arquitetural 4.1 estabelece expressamente que:
1. O servidor desktop deverá ser um executável independente do aplicativo gráfico, permitindo sua inicialização e execução separadamente;
2. As responsabilidades da interface gráfica não devem ser misturadas com as responsabilidades do serviço de rede.

Ao mesmo tempo, a equipe de 4 pessoas precisa manter um monorepo enxuto, sem proliferação de módulos Gradle redundantes.

## Decisão
1. **Isolamento de Pacotes e Dependências:** Todo o código do serviço de rede (`br.edu.fileshare.protocol.*` e `br.edu.fileshare.network.*`) é proibido de importar qualquer pacote de interface gráfica (`androidx.compose.*`, `java.awt.*`, `javax.swing.*`).
2. **Entrypoints Independentes no Target Desktop (`desktopMain`):**
   - `br.edu.fileshare.desktop.server.ServerMainKt`: Executável independente (*headless*/CLI) acionado via `./gradlew :composeApp:runDesktopServer` (ou `./scripts/run-desktop-server.sh`), que inicializa exclusivamente o Servidor HTTP/TCP FSAP e o Serviço de Descoberta UDP no console.
   - `br.edu.fileshare.desktop.MainKt`: Executável do aplicativo gráfico Desktop acionado via `./gradlew :composeApp:run` (ou `./scripts/run-desktop-ui.sh`).
3. **Evolução Opcional em M1:** Caso a equipe deseje separar fisicamente a compilação em submódulos Gradle distintos (`:core-network`, `:desktop-server`, `:composeApp`) durante a Milestone M1, os limites de pacotes já foram desenhados sem dependências circulares para permitir a extração imediata.

## Consequências
- **Positivas:**
  - Permite testar e demonstrar o Servidor Desktop em terminal puro (inclusive via SSH ou containers sem servidor gráfico X11/Wayland) durante a avaliação acadêmica.
  - Permite executar duas instâncias na mesma máquina (ex: um Servidor Independente na porta `53319` e um Cliente na porta `53317`) para validar transferências Desktop ↔ Desktop em loopback/LAN local.


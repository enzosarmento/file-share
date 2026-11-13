# Architecture Decision Records (ADRs) — FileShare

Este diretório armazena os registros formais de decisão arquitetural (ADRs) do projeto **FileShare**. Nenhuma decisão técnica estrutural deve ser tomada ou alterada sem o respectivo registro ou revisão de ADR.

## Índice de Decisões

| ID | Título | Status | Impacto Principal |
| :--- | :--- | :---: | :--- |
| [ADR-001](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-001-adocao-kmp-e-compose-multiplatform.md) | Adoção de Kotlin Multiplatform (KMP) e Compose Multiplatform (CMP) | **Aprovada (Premissa)** | Estrutura do monorepo, `commonMain`, compartilhamento de protocolo e UI |
| [ADR-002](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-002-separacao-cliente-gui-e-servidor-desktop.md) | Separação entre Cliente Gráfico e Servidor Desktop Independente | **Aprovada (Premissa)** | Entrypoint `ServerMainKt`, task `runDesktopServer`, desacoplamento UI/Rede |
| [ADR-003](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-003-arquitetura-hibrida-udp-multicast-e-http-tcp.md) | Arquitetura Híbrida de Transporte: UDP Multicast + HTTP/1.1 sobre TCP | **Aprovada (Premissa)** | Especificação do protocolo FSAP/1.0 |
| [ADR-004](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-004-escolha-ktor-vs-java-net-sockets.md) | Escolha de Ktor (CIO) para HTTP/TCP e `java.net.MulticastSocket` para UDP | **Proposta Técnica (Pendente)** | Dependências de rede em `commonMain`, `desktopMain` e `androidMain` |
| [ADR-005](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-005-estrategia-streaming-e-integridade-sha256.md) | Estratégia de Streaming Binário em Chunks (64 KB), Arquivos `.part` e SHA-256 | **Proposta Técnica (Pendente)** | Fluxo de transferência, consumo de RAM e verificação de integridade |
| [ADR-006](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-006-armazenamento-e-ciclo-de-vida-android.md) | Armazenamento Seguro (SAF/MediaStore) e `ForegroundService` no Android | **Proposta Técnica (Pendente)** | Implementação em `androidMain` (Milestones M4 e M5) |


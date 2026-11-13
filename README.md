# FileShare — Transferência de Arquivos Multiplataforma em Rede Local (FSAP/1.0)

> **Status Atual do Projeto:** **Milestone M0 — Planejamento, Arquitetura e Especificação Formal Concluída.**  
> **Estado de Implementação:** Nenhuma funcionalidade de rede, interface gráfica ou transferência de arquivos foi implementada nesta etapa. Toda a fundação arquitetural, especificação normativa do protocolo **FSAP/1.0**, backlog de **47 tasks** (`TODO`) e **10 skills especializadas** estão prontas para o início do desenvolvimento pela equipe.

---

## 1. Sobre o Projeto

O **FileShare** é uma aplicação multiplataforma inspirada no *LocalSend* para transferência direta de arquivos entre computadores **Desktop (Windows e Linux)** e dispositivos móveis **Android (smartphones e tablets)** conectados à mesma rede local (LAN/WLAN), sem depender de servidores centrais na internet ou armazenamento em nuvem.

### Objetivos Acadêmicos (Redes de Computadores)
- Projeto e especificação formal de um protocolo próprio da camada de aplicação: **FSAP/1.0 (FileShare Application Protocol)**;
- Uso complementar e justificado de **UDP Multicast** (descoberta automática e anúncio periódico de presença na LAN) e **HTTP/1.1 sobre TCP** (controle confiável de sessão e streaming de dados binários);
- Transferência eficiente em blocos de **64 KB** (memória constante $O(1)$) com escrita atômica em arquivos `.part` e verificação criptográfica obrigatória de integridade via **SHA-256**;
- Suporte a múltiplas conexões simultâneas, confirmação explícita do destinatário, cancelamento bilateral e tratamento determinístico de desconexões e timeouts;
- Separação estrita entre a interface gráfica e o serviço de rede, incluindo um **Servidor Desktop executável de forma independente (Headless/CLI)**.

---

## 2. Stack Tecnológica Obrigatória

- **Linguagem:** 100% **Kotlin** (Kotlin 2.1.21)
- **Arquitetura Multiplataforma:** **Kotlin Multiplatform (KMP)** (`commonMain`, `desktopMain`, `androidMain`)
- **Interface Gráfica:** **Compose Multiplatform (CMP)** (Material 3)
- **Concorrência:** **Kotlin Coroutines** (`StateFlow`, `Mutex`, `SupervisorJob`)
- **Rede e Transporte:** **Ktor Server & Client (`CIO` engine)** para HTTP/TCP + `java.net.MulticastSocket` / `NetworkInterface` para UDP Multicast
- **Serialização:** **Kotlinx Serialization (`JSON`)**
- **Build System:** **Gradle (Kotlin DSL)** com Version Catalog (`gradle/libs.versions.toml`)

---

## 3. Mapa Completo da Documentação (`docs/`)

| Documento | Descrição e Conteúdo |
| :--- | :--- |
| [**`docs/academic-foundation.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/academic-foundation.md) | Fundamentação acadêmica completa: problema, motivação, objetivos, comparativo TCP vs. UDP, requisitos funcionais (`RF-01` a `RF-16`), requisitos não funcionais (`RNF-01` a `RNF-10`) e matriz de maturidade (Decisões Aprovadas vs. Propostas Técnicas vs. Planejado). |
| [**`docs/architecture.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/architecture.md) | Arquitetura detalhada em camadas, separação entre Cliente GUI Desktop e Servidor Desktop Independente, arquitetura Android (`ForegroundService`, SAF, `MulticastLock`), organização de pacotes e análise de riscos técnicos. |
| [**`docs/protocol-fsap.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-fsap.md) | **Especificação Oficial do Protocolo FSAP/1.0:** transportes, identificação (`DeviceIdentity`), formato de mensagens, temporizadores normativos, catálogo de **20 eventos de comunicação** e **Matriz de Rastreabilidade Completa**. |
| [**`docs/state-machine.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/state-machine.md) | **Máquinas de Estados Formais:** diagramas Mermaid, condições de entrada/saída, tabelas exaustivas de transições e tratamento de transições inválidas para `LocalNodeState`, `PeerConnectionState` e `TransferState`. |
| [**`docs/protocol-messages.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-messages.md) | **Catálogo de Mensagens do FSAP/1.0:** especificação campo a campo e exemplos de payloads válidos e inválidos para as 12 mensagens (`ANNOUNCE`, `DISCOVER`, `BYE`, `IDENTIFY`, `TRANSFER_REQUEST`, `TRANSFER_ACCEPT`, `TRANSFER_REJECT`, `TRANSFER_CONTENT`, `TRANSFER_STATUS`, `TRANSFER_CANCEL`, `TRANSFER_COMPLETE`, `ERROR`). |
| [**`docs/communication-flows.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/communication-flows.md) | **Fluxos de Comunicação:** 10 diagramas de sequência Mermaid cobrindo descoberta, anúncio periódico, transferência com aceite, recepção interna, recusa, cancelamento, falha de disco, desconexão abrupta, verificação SHA-256 e encerramento gracioso. |
| [**`docs/discovery.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/discovery.md) | Funcionamento detalhado do UDP Multicast (`239.255.70.83:53318`), filtragem de interfaces virtuais, `PeerRegistry`, TTL de 18 s e fallback de Conexão Manual por IP (`IDENTIFY`). |
| [**`docs/transfer-flow.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/transfer-flow.md) | Engenharia de streaming binário em chunks de 64 KB, cálculo incremental de SHA-256, arquivos temporários `.part` e controle de concorrência. |
| [**`docs/security.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/security.md) | Modelo de segurança na LAN, prevenção de *Path Traversal* (`FileNameSanitizer`), autorização por `transferToken` e proteção contra exaustão de recursos. |
| [**`docs/error-codes.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/error-codes.md) | Catálogo padronizado dos 17 códigos de erro `FSAP-ERR-*`, status HTTP equivalentes e ações de recuperação. |
| [**`docs/protocol-test-cases.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-test-cases.md) | Especificação dos 26 casos de teste formais de conformidade (`TC-FSAP-001` a `TC-FSAP-026`). |
| [**`docs/testing.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/testing.md) | Estratégia de testes em 3 níveis (`commonTest`, `desktopTest` e interoperabilidade em LAN). |
| [**`docs/decisions/`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/README.md) | Architecture Decision Records (`ADR-001` a `ADR-006`). |

---

## 4. Planejamento, Backlog e Colaboração (`tasks/` e `CONTRIBUTING.md`)

- [**`tasks/README.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/tasks/README.md): Visão geral da organização do backlog e distribuição por domínio.
- [**`tasks/backlog.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/tasks/backlog.md): Especificação detalhada das **47 tasks** distribuídas em 10 domínios funcionais (todas com status `TODO`).
- [**`tasks/milestones.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/tasks/milestones.md): Planejamento das Milestones **M0 a M6** e critérios objetivos de conclusão.
- [**`tasks/dependency-graph.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/tasks/dependency-graph.md): Grafo de dependências em Mermaid, caminho crítico e divisão em 4 trilhas paralelas de desenvolvimento.
- [**`CONTRIBUTING.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/CONTRIBUTING.md): Convenções de código, estratégia de branches (`main`, `develop`, `feature/*`), padrão de commits, revisão de PR e *Definition of Done*.

---

## 5. Estrutura do Repositório

```text
fileshare/
├── README.md
├── CONTRIBUTING.md
├── AGENTS.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── docs/
│   ├── academic-foundation.md
│   ├── architecture.md
│   ├── protocol-fsap.md
│   ├── protocol-messages.md
│   ├── discovery.md
│   ├── transfer-flow.md
│   ├── communication-flows.md
│   ├── state-machine.md
│   ├── security.md
│   ├── error-codes.md
│   ├── protocol-test-cases.md
│   ├── testing.md
│   └── decisions/
│       ├── README.md
│       ├── ADR-001-adocao-kmp-e-compose-multiplatform.md
│       ├── ADR-002-separacao-cliente-gui-e-servidor-desktop.md
│       ├── ADR-003-arquitetura-hibrida-udp-multicast-e-http-tcp.md
│       ├── ADR-004-escolha-ktor-vs-java-net-sockets.md
│       ├── ADR-005-estrategia-streaming-e-integridade-sha256.md
│       └── ADR-006-armazenamento-e-ciclo-de-vida-android.md
├── composeApp/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/br/edu/fileshare/ProjectInfo.kt
│       ├── commonTest/kotlin/br/edu/fileshare/ProjectInfoTest.kt
│       ├── desktopMain/kotlin/br/edu/fileshare/desktop/
│       │   ├── Main.kt
│       │   └── server/ServerMain.kt
│       ├── desktopTest/kotlin/br/edu/fileshare/desktop/DesktopEnvironmentTest.kt
│       ├── androidMain/
│       │   ├── AndroidManifest.xml
│       │   └── kotlin/br/edu/fileshare/android/MainActivity.kt
│       └── androidUnitTest/kotlin/br/edu/fileshare/android/AndroidEnvironmentTest.kt
├── scripts/
│   ├── check-structure.sh
│   ├── run-desktop-server.sh
│   └── run-desktop-ui.sh
├── tasks/
│   ├── README.md
│   ├── backlog.md
│   ├── milestones.md
│   └── dependency-graph.md
└── .agent/
    └── skills/
        ├── kotlin-multiplatform-architecture/SKILL.md
        ├── compose-multiplatform-ui/SKILL.md
        ├── kotlin-networking/SKILL.md
        ├── udp-device-discovery/SKILL.md
        ├── ktor-http-server/SKILL.md
        ├── binary-file-transfer/SKILL.md
        ├── android-file-system/SKILL.md
        ├── fsap-protocol/SKILL.md
        ├── network-security/SKILL.md
        └── integration-testing/SKILL.md
```

---

## 6. Comandos para Validar e Executar a Estrutura Inicial

1. **Validar a integridade de todos os artefatos da fundação (M0):**
   ```bash
   ./scripts/check-structure.sh
   ```
2. **Executar o entrypoint estrutural do Servidor Desktop Independente (Headless/CLI):**
   ```bash
   ./gradlew :composeApp:runDesktopServer --args="--port 53317"
   # ou via script auxiliar:
   ./scripts/run-desktop-server.sh --port 53317
   ```
3. **Executar o entrypoint estrutural do Cliente Desktop:**
   ```bash
   ./gradlew :composeApp:run
   # ou via script auxiliar:
   ./scripts/run-desktop-ui.sh
   ```
4. **Executar os testes estruturais do target Desktop (`commonTest` + `desktopTest`):**
   ```bash
   ./gradlew :composeApp:desktopTest
   ```


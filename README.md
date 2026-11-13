# FileShare — Transferência de Arquivos Multiplataforma em Rede Local (FSAP/1.0)

> **Status Atual do Projeto:** **Milestone M3 — Descoberta Automática Concluída** (`CURRENT_MILESTONE = "M3 - Concluida"`).  
> **Progresso do Backlog:** **36 / 47 tasks concluídas (`DONE`)** — **Milestones M0, M1, M2 e M3** finalizadas; **11 tasks** pendentes entre **M4, M5 e M6** ([`tasks/backlog.md`](tasks/backlog.md)).

---

## 1. Sobre o Projeto

O **FileShare** é uma aplicação multiplataforma inspirada no *LocalSend* para transferência direta de arquivos entre computadores **Desktop (Linux e Windows)** e dispositivos móveis **Android (smartphones e tablets)** conectados à mesma rede local (LAN/WLAN), sem depender de servidores centrais na internet ou armazenamento em nuvem.

### Objetivos Acadêmicos (Redes de Computadores)
- Projeto e especificação formal de um protocolo próprio da camada de aplicação: **FSAP/1.0 (FileShare Application Protocol)**;
- Uso complementar de **UDP Multicast** (`239.255.70.83:53318`) para descoberta automática e anúncio periódico na LAN, e **HTTP/1.1 sobre TCP** (porta padrão `53317`) para controle confiável de sessão e streaming de dados binários;
- Transferência em blocos de **64 KB** (memória constante $O(1)$) com escrita temporária em arquivos `.part` e promoção atômica somente após verificação criptográfica de integridade via **SHA-256**;
- Suporte a conexões simultâneas, confirmação explícita do destinatário, cancelamento bilateral e tratamento padronizado de erros (`FSAP-ERR-*`);
- Separação estrita entre a interface gráfica Compose e o serviço de rede, incluindo um **Servidor Desktop executável de forma independente (Headless/CLI)**.

---

## 2. Estado de Implementação por Milestone

| Milestone | Escopo Principal | Status | Tasks |
| :--- | :--- | :---: | :---: |
| **M0** | Planejamento, Arquitetura, Especificação `FSAP/1.0`, ADRs e Estrutura KMP | **Concluída** | `3 / 3` |
| **M1** | Núcleo `FSAP/1.0` (`commonMain`), Sanitização, `ServerMainKt` e Handshake `IDENTIFY` | **Concluída** | `11 / 11` |
| **M2** | Transferência Desktop ↔ Desktop (Ktor HTTP, streaming 64 KB, `.part`, SHA-256, UI Desktop, `TC-FSAP-006`..`025`) | **Concluída** | `16 / 16` |
| **M3** | Descoberta Automática via UDP Multicast (`ANNOUNCE`, `DISCOVER`, `BYE`), `PeerRegistry` com TTL `18 s` e `TC-FSAP-001`..`005` | **Concluída** | `6 / 6` |
| **M4** | Integração Android (`MulticastLock`, SAF / `MediaStore.Downloads`, `FsapForegroundService`) | **Pendente** | `0 / 3` |
| **M5** | Transferência Desktop ↔ Android (UI Compose Android e interoperabilidade em LAN real) | **Pendente** | `0 / 3` |
| **M6** | Hardening (timeout de stream `15 s`, limites anti-DoS, TLS opcional), estresse (`TC-FSAP-026`) e relatório final | **Pendente** | `0 / 5` |

### O que já está funcional (até M3)
- **Descoberta Automática e Conexão Manual:** anúncio periódico (`ANNOUNCE`), busca ativa (`DISCOVER`), despedida graciosa (`BYE`), filtro de interfaces loopback/virtuais, gerenciamento de pares `ONLINE`/`STALE`/expiração TTL (`18 s`) e conexão manual por IP:Porta (`IDENTIFY`);
- **Transferência Desktop ↔ Desktop (GUI e Headless/CLI):** envio e recebimento de múltiplos arquivos, seleção nativa de arquivos no Desktop, diálogo de aprovação explícita com countdown de `60 s` (e aprovação via CLI no servidor headless), barra de progresso/velocidade em tempo real e cancelamento bilateral;
- **Integridade e Segurança Base:** streaming em chunks de `64 KB`, hash incremental `SHA-256`, limpeza garantida de arquivos `.part` em falhas/cancelamentos, `FileNameSanitizer` anti-*Path Traversal* e autorização por `transferToken` efêmero;
- **Alvo Android atual:** infraestrutura base compilável (`MainActivity`, `AndroidAppContainer`, `AndroidFsapLogger` e teste base `AndroidEnvironmentTest`), aguardando as tasks específicas das Milestones **M4** e **M5**.

---

## 3. Stack Tecnológica

- **Linguagem:** 100% **Kotlin** (`2.1.21`, target JVM `17`)
- **Arquitetura Multiplataforma:** **Kotlin Multiplatform (KMP)** com source sets `commonMain`, `jvmCommonMain`, `desktopMain` e `androidMain`
- **Interface Gráfica:** **Compose Multiplatform (`1.7.3`)** (Material 3)
- **Concorrência:** **Kotlin Coroutines (`1.10.1`)** (`StateFlow`, `Mutex`, `SupervisorJob`)
- **Rede e Transporte:** **Ktor Server & Client `3.0.3` (`CIO`)** para HTTP/TCP + `java.net.MulticastSocket` / `NetworkInterface` para UDP Multicast
- **Serialização e Logs:** **Kotlinx Serialization (`1.8.0` JSON)** + **Logback (`1.5.16`)** no Desktop
- **Android & Build:** **AGP `8.7.3`** (`minSdk 26`, `targetSdk 35`, `compileSdk 35`) e **Gradle (Kotlin DSL)** com Version Catalog ([`gradle/libs.versions.toml`](gradle/libs.versions.toml))

---

## 4. Mapa da Documentação (`docs/`)

| Documento | Descrição e Conteúdo |
| :--- | :--- |
| [**`docs/academic-foundation.md`**](docs/academic-foundation.md) | Fundamentação acadêmica: problema, objetivos, comparativo TCP vs. UDP, requisitos funcionais (`RF-01` a `RF-16`) e não funcionais (`RNF-01` a `RNF-10`). |
| [**`docs/architecture.md`**](docs/architecture.md) | Arquitetura em camadas, separação entre Cliente GUI Desktop e Servidor Desktop Independente, arquitetura Android e organização de pacotes. |
| [**`docs/protocol-fsap.md`**](docs/protocol-fsap.md) | **Especificação Oficial do Protocolo FSAP/1.0:** transportes, identificação (`DeviceIdentity`), temporizadores normativos, eventos e rastreabilidade. |
| [**`docs/state-machine.md`**](docs/state-machine.md) | **Máquinas de Estados Formais:** diagramas Mermaid e tabelas de transição para `LocalNodeState`, `PeerConnectionState` e `TransferState`. |
| [**`docs/protocol-messages.md`**](docs/protocol-messages.md) | **Catálogo de Mensagens do FSAP/1.0:** contratos campo a campo e payloads válidos/inválidos das 12 mensagens do protocolo. |
| [**`docs/communication-flows.md`**](docs/communication-flows.md) | **Fluxos de Comunicação:** diagramas de sequência Mermaid cobrindo descoberta, transferência, recusa, cancelamento, erros e encerramento gracioso. |
| [**`docs/discovery.md`**](docs/discovery.md) | Funcionamento detalhado do UDP Multicast (`239.255.70.83:53318`), filtragem de interfaces, `PeerRegistry`, TTL (`18 s`) e fallback `IDENTIFY`. |
| [**`docs/transfer-flow.md`**](docs/transfer-flow.md) | Engenharia de streaming binário em chunks de `64 KB`, cálculo incremental de SHA-256, arquivos temporários `.part` e controle de concorrência. |
| [**`docs/security.md`**](docs/security.md) | Modelo de segurança na LAN, prevenção de *Path Traversal* (`FileNameSanitizer`), autorização por `transferToken` e proteção contra exaustão de recursos. |
| [**`docs/error-codes.md`**](docs/error-codes.md) | Catálogo padronizado dos 17 códigos de erro `FSAP-ERR-*`, status HTTP equivalentes e ações de recuperação. |
| [**`docs/protocol-test-cases.md`**](docs/protocol-test-cases.md) | Especificação dos 26 casos de teste formais de conformidade (`TC-FSAP-001` a `TC-FSAP-026`). |
| [**`docs/testing.md`**](docs/testing.md) | Estratégia de testes em 3 níveis (`commonTest`, `desktopTest` e interoperabilidade em LAN). |
| [**`docs/decisions/`**](docs/decisions/README.md) | Architecture Decision Records (`ADR-001` a `ADR-006`). |

---

## 5. Planejamento, Backlog e Colaboração (`tasks/` e `CONTRIBUTING.md`)

- [**`tasks/README.md`**](tasks/README.md): Visão geral da organização do backlog e distribuição por domínio.
- [**`tasks/backlog.md`**](tasks/backlog.md): Especificação detalhada das **47 tasks** em 10 domínios funcionais (atualmente **36 `DONE`**, **11 `TODO`**).
- [**`tasks/milestones.md`**](tasks/milestones.md): Planejamento das Milestones **M0 a M6** e critérios objetivos de conclusão.
- [**`tasks/dependency-graph.md`**](tasks/dependency-graph.md): Grafo de dependências em Mermaid, caminho crítico e trilhas paralelas de desenvolvimento.
- [**`CONTRIBUTING.md`**](CONTRIBUTING.md): Convenções de código, estratégia de branches (`main`, `develop`, `feature/*`), padrão de commits, revisão e *Definition of Done*.
- [**`AGENTS.md`**](AGENTS.md): Diretrizes arquiteturais e de protocolo para agentes de desenvolvimento assistido.

---

## 6. Estrutura do Repositório

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
├── docs/                                # Especificação FSAP/1.0, arquitetura, fluxos, testes e ADRs
├── tasks/                               # Backlog (47 tasks), milestones (M0-M6) e grafo de dependências
├── scripts/
│   ├── check-structure.sh               # Validação dos artefatos documentais e de configuração
│   ├── run-desktop-server.sh            # Inicia o Servidor Desktop Independente (Gradle ou JAR)
│   └── run-desktop-ui.sh                # Inicia a interface gráfica Desktop Compose
├── composeApp/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/br/edu/fileshare/
│       │   ├── ProjectInfo.kt
│       │   ├── core/                    # DI, logging, relógio, UUID e locks multiplataforma
│       │   ├── protocol/                # Contratos FSAP/1.0, validadores, erros e máquinas de estado
│       │   ├── network/                 # Descoberta UDP, cliente/servidor HTTP Ktor, streaming e transferência
│       │   ├── storage/                 # Portas FileSource/FileSink e FileNameSanitizer
│       │   └── ui/                      # App Compose, tema, ViewModels e telas/diálogos reativos
│       ├── jvmCommonMain/kotlin/br/edu/fileshare/
│       │   ├── core/                    # Implementações JVM (Clock, UUID, PlatformLock)
│       │   └── network/                 # MulticastSocket, NetworkInterface, SHA-256 e tokens JVM
│       ├── desktopMain/
│       │   ├── kotlin/br/edu/fileshare/desktop/
│       │   │   ├── Main.kt              # Entrypoint da GUI Desktop (Compose)
│       │   │   ├── runtime/             # Runtime desacoplado do cliente/servidor Desktop
│       │   │   ├── server/              # Entrypoint CLI ServerMainKt, parser CLI e aprovação em console
│       │   │   ├── storage/             # Seletor nativo e armazenamento java.nio (DesktopNioFileStorage)
│       │   │   ├── di/                  # Container de dependências do servidor headless
│       │   │   └── logging/             # Slf4jFsapLogger
│       │   └── resources/logback.xml
│       ├── androidMain/
│       │   ├── AndroidManifest.xml
│       │   └── kotlin/br/edu/fileshare/android/   # Estrutura base (M4/M5 em desenvolvimento futuro)
│       ├── commonTest/                  # Testes unitários de protocolo, estados, UI e conformidade UDP (TC-FSAP-001..005)
│       ├── desktopTest/                 # Testes de rede, servidor/cliente e conformidade HTTP/TCP (TC-FSAP-006..025)
│       └── androidUnitTest/             # Testes unitários base Android
└── .agent/skills/                       # Skills especializadas do projeto FileShare
```

---

## 7. Como Executar, Empacotar e Testar

### Pré-requisitos
- **JDK 17+** configurada (`JAVA_HOME` apontando para JDK 17 ou superior);
- **Android SDK (`compileSdk 35`)** configurado via `ANDROID_HOME` ou `local.properties` apenas para compilar/testar os alvos Android.

### Comandos Principais

1. **Validar a estrutura documental e do repositório:**
   ```bash
   ./scripts/check-structure.sh
   ```

2. **Executar o Servidor Desktop Independente (Headless/CLI):**
   ```bash
   ./gradlew :composeApp:runDesktopServer --args="--port 53317 --alias Servidor-LAN"
   # ou via script auxiliar:
   ./scripts/run-desktop-server.sh --port 53317 --alias Servidor-LAN
   ```
   Para gerar e executar o JAR executável independente (`composeApp/build/libs/fileshare-server-all.jar`):
   ```bash
   ./scripts/run-desktop-server.sh --jar --port 53317
   # equivalente manual:
   ./gradlew :composeApp:packageDesktopServerJar
   java -jar composeApp/build/libs/fileshare-server-all.jar --help
   ```
   Opções CLI suportadas pelo `ServerMainKt`: `--port <1..65535>`, `--alias <nome>`, `--download-dir <caminho>`, `--auto-accept-test`, `-h` / `--help`.

3. **Executar o Aplicativo Desktop (Compose Multiplatform GUI):**
   ```bash
   ./gradlew :composeApp:run
   # ou via script auxiliar:
   ./scripts/run-desktop-ui.sh
   ```

4. **Executar os testes automatizados Desktop e de conformidade FSAP (`commonTest` + `desktopTest`):**
   ```bash
   ./gradlew :composeApp:desktopTest
   ```
   Inclui os testes unitários do núcleo e as suítes de conformidade **`TC-FSAP-001` a `TC-FSAP-025`** (`UdpDiscoveryConformanceTest` e `HttpTcpM2ConformanceTest`).

5. **Executar os testes unitários e gerar o APK de debug Android (estrutura base atual):**
   ```bash
   ./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug
   ```

# Backlog Geral e por Domínio Funcional — FileShare

> **Status Global:** `47 Tasks` registradas — Acompanhamento atualizado continuamente conforme conclusão e validação das Milestones M0–M6.  
> **Atribuição:** Sem atribuição nominal. A equipe de 4 integrantes alocará as tasks conforme as trilhas paralelas definidas em [dependency-graph.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/tasks/dependency-graph.md).

---

## Visão Geral do Backlog (Índice Rápido)

| ID | Título Resumido | Domínio Funcional | Milestone | Prioridade | Complexidade | Dependências | Status |
| :--- | :--- | :--- | :---: | :---: | :---: | :--- | :---: |
| `TASK-ARC-001` | Revisão e aprovação da especificação FSAP/1.0 | 1. Arquitetura e Documentação | M0 | P0 | Baixa | *(Nenhuma)* | `DONE` |
| `TASK-ARC-002` | Ratificação das ADRs pendentes (ADR-004 a ADR-006) | 1. Arquitetura e Documentação | M0 | P0 | Baixa | `TASK-ARC-001` | `DONE` |
| `TASK-ARC-003` | Consolidação do relatório acadêmico final | 1. Arquitetura e Documentação | M6 | P1 | Média | `TASK-TEST-005` | `TODO` |
| `TASK-INF-001` | Validação do ambiente KMP/Android SDK na equipe | 2. Configuração e Infraestrutura | M0 | P0 | Baixa | `TASK-ARC-002` | `DONE` |
| `TASK-INF-002` | CLI e empacotamento do Servidor Desktop Independente | 2. Configuração e Infraestrutura | M1 | P0 | Baixa | `TASK-INF-001` | `DONE` |
| `TASK-INF-003` | Logging estruturado e container de dependências KMP | 2. Configuração e Infraestrutura | M1 | P1 | Baixa | `TASK-INF-001` | `DONE` |
| `TASK-FSAP-001` | Entidades `DeviceIdentity` e envelope base JSON FSAP | 3. Protocolo FSAP | M1 | P0 | Baixa | `TASK-ARC-001`, `TASK-INF-001` | `DONE` |
| `TASK-FSAP-002` | DTOs de todas as 12 mensagens UDP e HTTP/TCP do FSAP | 3. Protocolo FSAP | M1 | P0 | Média | `TASK-FSAP-001` | `DONE` |
| `TASK-FSAP-003` | Máquinas de estados (`LocalNode`, `Peer`, `Transfer`) | 3. Protocolo FSAP | M1 | P0 | Alta | `TASK-FSAP-001` | `DONE` |
| `TASK-FSAP-004` | Catálogo `FsapErrorCode`, envelope `ERROR` e exceções | 3. Protocolo FSAP | M1 | P0 | Baixa | `TASK-FSAP-002` | `DONE` |
| `TASK-FSAP-005` | Validadores sintáticos/semânticos e versão do FSAP | 3. Protocolo FSAP | M1 | P0 | Média | `TASK-FSAP-002`, `TASK-FSAP-004` | `DONE` |
| `TASK-DISC-001` | `MulticastTransport` e filtro `NetworkInterfaceProvider` | 4. Descoberta UDP | M3 | P0 | Média | `TASK-FSAP-001`, `TASK-INF-003` | `TODO` |
| `TASK-DISC-002` | Emissor UDP de `ANNOUNCE` periódico, `DISCOVER` e `BYE` | 4. Descoberta UDP | M3 | P0 | Média | `TASK-DISC-001`, `TASK-FSAP-002` | `TODO` |
| `TASK-DISC-003` | Listener UDP Multicast com filtro de loopback e jitter | 4. Descoberta UDP | M3 | P0 | Média | `TASK-DISC-001`, `TASK-FSAP-005` | `TODO` |
| `TASK-DISC-004` | `PeerRegistry` concorrente com `STALE` e expiração TTL | 4. Descoberta UDP | M3 | P0 | Média | `TASK-FSAP-003`, `TASK-DISC-003` | `TODO` |
| `TASK-DISC-005` | Conexão Manual por IP via handshake TCP `IDENTIFY` | 4. Descoberta UDP | M1 | P0 | Média | `TASK-FSAP-002`, `TASK-DSRV-001` | `DONE` |
| `TASK-DSRV-001` | Motor HTTP Server Ktor CIO independente e rota `IDENTIFY` | 5. Servidor Desktop | M1 | P0 | Média | `TASK-INF-002`, `TASK-FSAP-002`, `TASK-FSAP-004` | `DONE` |
| `TASK-DSRV-002` | Gerenciador de sessões concorrentes no Servidor Desktop | 5. Servidor Desktop | M2 | P0 | Alta | `TASK-DSRV-001`, `TASK-FSAP-003` | `DONE` |
| `TASK-DSRV-003` | Endpoint `TRANSFER_REQUEST`, aprovação (60s) e token | 5. Servidor Desktop | M2 | P0 | Alta | `TASK-DSRV-002`, `TASK-SEC-001`, `TASK-SEC-002` | `DONE` |
| `TASK-DSRV-004` | Endpoint `TRANSFER_CONTENT`, escrita `.part` e SHA-256 | 5. Servidor Desktop | M2 | P0 | Alta | `TASK-DSRV-003`, `TASK-XFER-001` | `DONE` |
| `TASK-DSRV-005` | Endpoints `STATUS`, `CANCEL`, `COMPLETE` e limpeza `.part` | 5. Servidor Desktop | M2 | P0 | Média | `TASK-DSRV-004` | `DONE` |
| `TASK-DCLI-001` | Estrutura base da UI Compose Desktop e navegação | 6. Cliente Desktop | M2 | P1 | Baixa | `TASK-INF-003` | `TODO` |
| `TASK-DCLI-002` | Tela de listagem reativa de dispositivos (`PeerRegistry`) | 6. Cliente Desktop | M3 | P0 | Média | `TASK-DCLI-001`, `TASK-DISC-004` | `TODO` |
| `TASK-DCLI-003` | Diálogo/formulário de Conexão Manual por IP no Desktop | 6. Cliente Desktop | M2 | P0 | Baixa | `TASK-DCLI-001`, `TASK-DISC-005` | `TODO` |
| `TASK-DCLI-004` | Seletor nativo de arquivos Desktop e `DesktopNioFileStorage` | 6. Cliente Desktop | M2 | P0 | Média | `TASK-DCLI-001`, `TASK-XFER-001` | `TODO` |
| `TASK-DCLI-005` | Modal de confirmação explícita (Aceitar/Recusar) Desktop | 6. Cliente Desktop | M2 | P0 | Média | `TASK-DCLI-001`, `TASK-DSRV-003` | `TODO` |
| `TASK-DCLI-006` | Barra de progresso, métricas de velocidade e cancelamento | 6. Cliente Desktop | M2 | P0 | Média | `TASK-DCLI-001`, `TASK-XFER-004`, `TASK-XFER-005` | `TODO` |
| `TASK-AND-001` | `AndroidMulticastLockManager` e transporte UDP Android | 7. Cliente Android | M4 | P0 | Média | `TASK-DISC-001`, `TASK-DISC-003` | `TODO` |
| `TASK-AND-002` | Armazenamento Android via SAF (`ContentResolver`) e `MediaStore` | 7. Cliente Android | M4 | P0 | Alta | `TASK-XFER-001` | `TODO` |
| `TASK-AND-003` | `FsapForegroundService` (`dataSync`) e permissões Android | 7. Cliente Android | M4 | P0 | Alta | `TASK-AND-001`, `TASK-DSRV-005` | `TODO` |
| `TASK-AND-004` | Telas Compose adaptadas para Android (Descoberta e Aprovação) | 7. Cliente Android | M5 | P0 | Média | `TASK-AND-002`, `TASK-AND-003`, `TASK-DCLI-002` | `TODO` |
| `TASK-AND-005` | Fluxo completo de envio/recebimento Android com notificações | 7. Cliente Android | M5 | P0 | Alta | `TASK-AND-004`, `TASK-XFER-005` | `TODO` |
| `TASK-XFER-001` | Portas `FileSource`/`FileSink` e streaming 64 KB + SHA-256 | 8. Transferência de Arquivos | M2 | P0 | Alta | `TASK-FSAP-001` | `DONE` |
| `TASK-XFER-002` | `FsapHttpClient` para `TRANSFER_REQUEST` e `ACCEPT`/`REJECT` | 8. Transferência de Arquivos | M2 | P0 | Média | `TASK-FSAP-002`, `TASK-FSAP-004` | `TODO` |
| `TASK-XFER-003` | Upload streaming `TRANSFER_CONTENT` e `TRANSFER_COMPLETE` | 8. Transferência de Arquivos | M2 | P0 | Alta | `TASK-XFER-001`, `TASK-XFER-002` | `TODO` |
| `TASK-XFER-004` | `TransferProgressTracker` concorrente com throttling (100 ms) | 8. Transferência de Arquivos | M2 | P0 | Média | `TASK-XFER-003`, `TASK-FSAP-003` | `TODO` |
| `TASK-XFER-005` | Cancelamento bilateral `TRANSFER_CANCEL` e limpeza `.part` | 8. Transferência de Arquivos | M2 | P1 | Média | `TASK-XFER-003`, `TASK-DSRV-005` | `TODO` |
| `TASK-XFER-006` | Tratamento de desconexão abrupta e timeout de stream (15 s) | 8. Transferência de Arquivos | M6 | P0 | Média | `TASK-XFER-004`, `TASK-DSRV-005` | `TODO` |
| `TASK-SEC-001` | `FileNameSanitizer` contra Path Traversal e não-sobrescrita | 9. Segurança | M1 | P0 | Baixa | `TASK-FSAP-005` | `DONE` |
| `TASK-SEC-002` | Gerador e validador de `transferToken` efêmero (`SecureRandom`) | 9. Segurança | M2 | P0 | Baixa | `TASK-FSAP-003` | `DONE` |
| `TASK-SEC-003` | Limites de payload JSON (`256 KB`), cota de disco e overflow | 9. Segurança | M6 | P1 | Média | `TASK-DSRV-004` | `TODO` |
| `TASK-SEC-004` | Transporte TLS opcional com fingerprint SHA-256 na LAN | 9. Segurança | M6 | P2 | Alta | `TASK-SEC-003` | `TODO` |
| `TASK-TEST-001` | Testes unitários em `commonTest` (DTOs, estados, sanitização) | 10. Testes e Integração | M1 | P0 | Média | `TASK-FSAP-003`, `TASK-FSAP-005`, `TASK-SEC-001` | `DONE` |
| `TASK-TEST-002` | Testes de conformidade HTTP/TCP (`TC-FSAP-006` a `025`) | 10. Testes e Integração | M2 | P0 | Alta | `TASK-DSRV-005`, `TASK-XFER-005` | `TODO` |
| `TASK-TEST-003` | Testes de descoberta UDP e TTL (`TC-FSAP-001` a `005`) | 10. Testes e Integração | M3 | P0 | Média | `TASK-DISC-004` | `TODO` |
| `TASK-TEST-004` | Testes de estresse e múltiplas conexões (`TC-FSAP-026`) | 10. Testes e Integração | M6 | P1 | Média | `TASK-TEST-002`, `TASK-XFER-006` | `TODO` |
| `TASK-TEST-005` | Testes de interoperabilidade Desktop ↔ Android em LAN real | 10. Testes e Integração | M5 | P0 | Alta | `TASK-AND-005`, `TASK-TEST-002`, `TASK-TEST-003` | `TODO` |

---

## 1. Domínio Funcional: Arquitetura e Documentação (`TASK-ARC-*`)

### `TASK-ARC-001` — Revisão e aprovação formal da especificação do protocolo FSAP/1.0 pela equipe
- **ID único:** `TASK-ARC-001`
- **Título:** Revisão e aprovação formal da especificação do protocolo FSAP/1.0 pela equipe
- **Descrição:** Revisar conjuntamente entre os 4 integrantes os documentos `protocol-fsap.md`, `state-machine.md`, `protocol-messages.md`, `communication-flows.md` e `error-codes.md`, congelando o contrato da versão `1.0` antes de iniciar a codificação da Milestone M1.
- **Objetivo:** Garantir que nenhum desenvolvedor inicie a implementação do protocolo com interpretações divergentes sobre campos JSON, temporizadores ou estados.
- **Domínio funcional:** 1. Arquitetura e Documentação
- **Prioridade:** `P0`
- **Complexidade estimada:** `Baixa`
- **Dependências:** Nenhuma (Task raiz do projeto)
- **Critérios de aceitação objetivos:**
  - Ata/registro de revisão concluída sem ambiguidades nos 12 tipos de mensagens e 20 eventos do FSAP/1.0;
  - Portas padrão (`53317` TCP e `53318` UDP) e endereço multicast (`239.255.70.83`) ratificados.
- **Arquivos ou módulos potencialmente envolvidos:** `docs/protocol-fsap.md`, `docs/protocol-messages.md`, `docs/state-machine.md`, `docs/error-codes.md`
- **Testes necessários:** Execução de `./scripts/check-structure.sh` com código de saída `0`.
- **Status:** `DONE`

### `TASK-ARC-002` — Ratificação das ADRs pendentes (ADR-004, ADR-005 e ADR-006) e limites de módulos
- **ID único:** `TASK-ARC-002`
- **Título:** Ratificação das ADRs pendentes (ADR-004, ADR-005 e ADR-006) e limites de módulos
- **Descrição:** Avaliar em equipe as três propostas técnicas documentadas (`ADR-004` Ktor CIO + MulticastSocket, `ADR-005` Streaming 64 KB + `.part`, `ADR-006` SAF + ForegroundService Android) e atualizar seu status de `Proposta Técnica` para `Aprovada`.
- **Objetivo:** Formalizar as decisões de bibliotecas e abstrações de sistema operacional para destravar as tasks de infraestrutura e protocolo.
- **Domínio funcional:** 1. Arquitetura e Documentação
- **Prioridade:** `P0`
- **Complexidade estimada:** `Baixa`
- **Dependências:** `TASK-ARC-001`
- **Critérios de aceitação objetivos:**
  - Status das ADRs `004`, `005` e `006` atualizado em `docs/decisions/`;
  - Acordo sobre a estrutura de pacotes sob `br.edu.fileshare`.
- **Arquivos ou módulos potencialmente envolvidos:** `docs/decisions/ADR-004-*.md`, `docs/decisions/ADR-005-*.md`, `docs/decisions/ADR-006-*.md`, `docs/decisions/README.md`
- **Testes necessários:** Verificação de links e consistência documental.
- **Status:** `DONE`

### `TASK-ARC-003` — Consolidação do relatório acadêmico final e documentação de entrega
- **ID único:** `TASK-ARC-003`
- **Título:** Consolidação do relatório acadêmico final e documentação de entrega
- **Descrição:** Atualizar a documentação acadêmica (`docs/academic-foundation.md` e `README.md`) ao término da Milestone M6 com métricas reais obtidas nos testes de transferência, capturas de pacotes (Wireshark) e instruções finais de demonstração ao professor.
- **Objetivo:** Entregar o relatório acadêmico completo e fiel à implementação final realizada pela equipe.
- **Domínio funcional:** 1. Arquitetura e Documentação
- **Prioridade:** `P1`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-TEST-005`
- **Critérios de aceitação objetivos:**
  - Relatório contendo evidências dos fluxos UDP e TCP, resultados da suíte `TC-FSAP-001..026` e guia de execução passo a passo.
- **Arquivos ou módulos potencialmente envolvidos:** `docs/academic-foundation.md`, `README.md`, `docs/architecture.md`
- **Testes necessários:** Revisão cruzada de todos os links e comandos documentados.
- **Status inicial:** `TODO`

---

## 2. Domínio Funcional: Configuração e Infraestrutura (`TASK-INF-*`)

### `TASK-INF-001` — Validação do ambiente de compilação KMP nas máquinas da equipe
- **ID único:** `TASK-INF-001`
- **Título:** Validação do ambiente de compilação KMP (JDK 21, Android SDK e Gradle Wrapper)
- **Descrição:** Garantir que todos os integrantes consigam compilar os targets `desktop` e `androidDebug` e rodar `commonTest`, `desktopTest` e `androidUnitTest` sem erros de ambiente.
- **Objetivo:** Eliminar problemas de configuração de máquina antes do início da codificação da Milestone M1.
- **Domínio funcional:** 2. Configuração e Infraestrutura
- **Prioridade:** `P0`
- **Complexidade estimada:** `Baixa`
- **Dependências:** `TASK-ARC-002`
- **Critérios de aceitação objetivos:**
  - Comandos `./gradlew :composeApp:desktopTest` e `./gradlew :composeApp:assembleDebug` executam com `BUILD SUCCESSFUL`.
- **Arquivos ou módulos potencialmente envolvidos:** `build.gradle.kts`, `composeApp/build.gradle.kts`, `gradle/libs.versions.toml`
- **Testes necessários:** Execução de `ProjectInfoTest`, `DesktopEnvironmentTest` e `AndroidEnvironmentTest`.
- **Status:** `DONE`

### `TASK-INF-002` — Configuração de argumentos CLI e empacotamento do Servidor Desktop Independente
- **ID único:** `TASK-INF-002`
- **Título:** Configuração de empacotamento e argumentos CLI do Servidor Desktop Independente (`ServerMainKt`)
- **Descrição:** Estruturar o parser de argumentos de linha de comando (`--port`, `--alias`, `--download-dir`, `--auto-accept-test`) no entrypoint `ServerMain.kt` e configurar uma task Gradle `packageDesktopServerJar` para geração de executável independente da GUI.
- **Objetivo:** Cumprir rigorosamente o requisito 4.1 (Servidor Desktop executável separadamente da interface gráfica).
- **Domínio funcional:** 2. Configuração e Infraestrutura
- **Prioridade:** `P0`
- **Complexidade estimada:** `Baixa`
- **Dependências:** `TASK-INF-001`
- **Critérios de aceitação objetivos:**
  - `./gradlew :composeApp:runDesktopServer --args="--port 53319 --alias ServerLab"` lê e valida os parâmetros sem inicializar classes do Compose/AWT.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/build.gradle.kts`, `composeApp/src/desktopMain/kotlin/br/edu/fileshare/desktop/server/ServerMain.kt`, `scripts/run-desktop-server.sh`
- **Testes necessários:** Teste unitário do parser de argumentos CLI em `desktopTest`.
- **Status:** `DONE`

### `TASK-INF-003` — Infraestrutura de logging estruturado multiplataforma e container de dependências
- **ID único:** `TASK-INF-003`
- **Título:** Infraestrutura de logging estruturado multiplataforma e injeção manual de dependências (`AppContainer`)
- **Descrição:** Criar uma interface leve `FsapLogger` em `commonMain` (com implementação `SLF4J/Logback` em `desktopMain` e `android.util.Log` em `androidMain`) e o container de composição de dependências para injetar repositórios, transportes e casos de uso sem acoplamento global.
- **Objetivo:** Assegurar observabilidade padronizada das transições de estado do protocolo (`RNF-10`) e facilitar o uso de *fakes/mocks* nos testes.
- **Domínio funcional:** 2. Configuração e Infraestrutura
- **Prioridade:** `P1`
- **Complexidade estimada:** `Baixa`
- **Dependências:** `TASK-INF-001`
- **Critérios de aceitação objetivos:**
  - `FsapLogger` formata logs incluindo `timestamp`, `component`, `transferId`/`deviceId` e mensagem em ambas as plataformas.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/core/logging/`, `desktopMain`, `androidMain`
- **Testes necessários:** Teste unitário com `InMemoryFsapLogger` em `commonTest`.
- **Status:** `DONE`

---

## 3. Domínio Funcional: Protocolo FSAP (`TASK-FSAP-*`)

### `TASK-FSAP-001` — Implementação das entidades de identidade (`DeviceIdentity`) e envelope base FSAP
- **ID único:** `TASK-FSAP-001`
- **Título:** Implementação das entidades de identidade (`DeviceIdentity`) e envelope base FSAP com Kotlinx Serialization
- **Descrição:** Implementar em `commonMain` as estruturas `DeviceIdentity`, enums `DeviceType` e `PlatformType`, geração/persistência de `deviceId` (UUID v4) e o tipo genérico `FsapEnvelope<T>` configurado com `kotlinx.serialization.json.Json` (`ignoreUnknownKeys = true`, `encodeDefaults = true`).
- **Objetivo:** Fornecer a camada base de identificação e enquadramento JSON exigida nas seções 5.1 e 5.5.
- **Domínio funcional:** 3. Protocolo FSAP
- **Prioridade:** `P0`
- **Complexidade estimada:** `Baixa`
- **Dependências:** `TASK-ARC-001`, `TASK-INF-001`
- **Critérios de aceitação objetivos:**
  - `FsapEnvelope` serializa e deserializa campos `protocol`, `version`, `type`, `messageId`, `timestamp`, `senderId` e `payload` em UTF-8 exatamente conforme `docs/protocol-messages.md`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/protocol/model/FsapEnvelope.kt`, `DeviceIdentity.kt`
- **Testes necessários:** Testes de serialização *round-trip* em `commonTest`.
- **Status:** `DONE`

### `TASK-FSAP-002` — Implementação de todos os DTOs de mensagens UDP e HTTP/TCP do FSAP/1.0
- **ID único:** `TASK-FSAP-002`
- **Título:** Implementação de todos os DTOs de mensagens UDP e HTTP/TCP do FSAP/1.0
- **Descrição:** Implementar em `commonMain` as classes `@Serializable` para todas as 12 mensagens do catálogo: `AnnouncePayload`, `DiscoverPayload`, `ByePayload`, `IdentifyPayload`, `TransferRequestPayload` (e `FileItemDto`), `TransferAcceptPayload`, `TransferRejectPayload`, `TransferStatusPayload`, `TransferCancelPayload`, `TransferCompletePayload` e `ErrorPayload`, além das constantes de cabeçalhos HTTP (`X-FSAP-*`).
- **Objetivo:** Garantir que Desktop e Android compartilhem 100% dos contratos de mensagens sem duplicação.
- **Domínio funcional:** 3. Protocolo FSAP
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-FSAP-001`
- **Critérios de aceitação objetivos:**
  - Todos os exemplos JSON de `docs/protocol-messages.md` são parseados com sucesso pelos DTOs criados.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/protocol/model/`
- **Testes necessários:** Testes de contrato para cada uma das 12 mensagens em `commonTest`.
- **Status:** `DONE`

### `TASK-FSAP-003` — Implementação das máquinas de estados formais (`LocalNode`, `PeerConnection`, `Transfer`)
- **ID único:** `TASK-FSAP-003`
- **Título:** Implementação das máquinas de estados formais (`LocalNodeState`, `PeerConnectionState`, `TransferState`)
- **Descrição:** Codificar em `commonMain` as três máquinas de estados especificadas em `docs/state-machine.md`, com transições protegidas por `Mutex`, emissão de estados via `StateFlow`, callbacks `onEnter`/`onExit` e rejeição determinística de transições inválidas via `InvalidStateTransitionException`.
- **Objetivo:** Assegurar que nenhum componente mude estados de dispositivo, par ou transferência de maneira ad-hoc ou inconsistente.
- **Domínio funcional:** 3. Protocolo FSAP
- **Prioridade:** `P0`
- **Complexidade estimada:** `Alta`
- **Dependências:** `TASK-FSAP-001`
- **Critérios de aceitação objetivos:**
  - Todas as transições válidas documentadas em `docs/state-machine.md` funcionam;
  - Qualquer transição não permitida (ex: `PENDING_APPROVAL` → `COMPLETED` ou saída de estado terminal) é bloqueada e lança `InvalidStateTransitionException`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/protocol/statemachine/`
- **Testes necessários:** Testes exaustivos de matriz de transição válida x inválida em `commonTest` (`TC-FSAP-023`).
- **Status:** `DONE`

### `TASK-FSAP-004` — Implementação do catálogo padronizado de erros (`FsapErrorCode`) e envelope `ERROR`
- **ID único:** `TASK-FSAP-004`
- **Título:** Implementação do catálogo padronizado de erros (`FsapErrorCode`), envelope `ERROR` e mapeamento de exceções
- **Descrição:** Implementar o enum `FsapErrorCode` contendo todos os 17 códigos padronizados em `docs/error-codes.md` (com respectivo `httpStatus`, `category` e flag `recoverable`), além de construtores utilitários para geração do envelope `ERROR` e exceção `FsapProtocolException`.
- **Objetivo:** Padronizar a sinalização e o tratamento de falhas entre cliente e servidor.
- **Domínio funcional:** 3. Protocolo FSAP
- **Prioridade:** `P0`
- **Complexidade estimada:** `Baixa`
- **Dependências:** `TASK-FSAP-002`
- **Critérios de aceitação objetivos:**
  - Todos os códigos `FSAP-ERR-*` de `docs/error-codes.md` estão mapeados com status HTTP exato e serializam no formato esperado.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/protocol/error/`
- **Testes necessários:** Testes unitários de mapeamento código ↔ status HTTP em `commonTest`.
- **Status:** `DONE`

### `TASK-FSAP-005` — Implementação dos validadores sintáticos e semânticos de mensagens e versão do protocolo
- **ID único:** `TASK-FSAP-005`
- **Título:** Implementação dos validadores sintáticos e semânticos de mensagens e versão do protocolo
- **Descrição:** Implementar `FsapMessageValidator` em `commonMain` para validar: formato UUIDv4 de `messageId`/`senderId`/`transferId`, compatibilidade da versão `MAJOR == 1`, faixa de `httpPort` (`1024..65535`), `alias` não vazio (`1..64` chars), formato hexadecimal de 64 caracteres de `sha256` e consistência de `totalBytes == files.sumOf { it.sizeBytes }`.
- **Objetivo:** Impedir que mensagens malformadas ou de versões incompatíveis avancem para a camada de negócio.
- **Domínio funcional:** 3. Protocolo FSAP
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-FSAP-002`, `TASK-FSAP-004`
- **Critérios de aceitação objetivos:**
  - Mensagens inválidas retornam `ValidationResult.Invalid(FsapErrorCode)` correspondente (`MALFORMED_MESSAGE`, `UNSUPPORTED_VERSION`, etc.).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/protocol/validation/`
- **Testes necessários:** Testes parametrizados com payloads válidos e inválidos (`TC-FSAP-001`, `TC-FSAP-003`, `TC-FSAP-007`).
- **Status:** `DONE`

---

## 4. Domínio Funcional: Descoberta UDP (`TASK-DISC-*`)

### `TASK-DISC-001` — Abstração `MulticastTransport` e filtro de interfaces de rede LAN (`NetworkInterfaceProvider`)
- **ID único:** `TASK-DISC-001`
- **Título:** Abstração `MulticastTransport` e enumerador/filtro de interfaces de rede LAN (`NetworkInterfaceProvider`)
- **Descrição:** Definir a interface `MulticastTransport` em `commonMain` e implementar em `desktopMain` (reutilizável pela JVM do Android) o provedor `JvmNetworkInterfaceProvider` que filtra interfaces `isUp`, `!isLoopback`, `supportsMulticast`, `!isVirtual`, descartando interfaces Docker/WSL/VPN e vinculando `MulticastSocket` ao grupo `239.255.70.83:53318` com `SO_REUSEADDR`.
- **Objetivo:** Mitigar o risco `RISK-02` e garantir que o multicast opere sobre a interface física Wi-Fi/Ethernet real da LAN.
- **Domínio funcional:** 4. Descoberta UDP
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-FSAP-001`, `TASK-INF-003`
- **Critérios de aceitação objetivos:**
  - O filtro exclui interfaces `docker0`, `veth*`, `tun*`, `lo` e seleciona interfaces IPv4 RFC 1918 válidas;
  - `MulticastTransport` permite enviar e receber datagramas de até `1024 bytes` com fechamento limpo do socket.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/discovery/MulticastTransport.kt`, `desktopMain/kotlin/br/edu/fileshare/desktop/network/`
- **Testes necessários:** Testes unitários de filtragem de interfaces e teste de loopback UDP em `desktopTest`.
- **Status inicial:** `TODO`

### `TASK-DISC-002` — Serviço emissor de anúncios periódicos (`ANNOUNCE`), busca ativa (`DISCOVER`) e saída (`BYE`)
- **ID único:** `TASK-DISC-002`
- **Título:** Serviço emissor de anúncios periódicos (`ANNOUNCE`), busca ativa (`DISCOVER`) e saída graciosa (`BYE`)
- **Descrição:** Implementar `DiscoveryBroadcaster` em `commonMain` utilizando Coroutines para: (a) emitir 1 `DISCOVER` + 1 `ANNOUNCE` ao entrar em `DISCOVERING`; (b) manter o loop periódico de `ANNOUNCE` a cada `5000 ms` (`T_ANNOUNCE_INTERVAL`); (c) emitir `BYE` no encerramento (`SHUTTING_DOWN`).
- **Objetivo:** Cumprir os requisitos `RF-01` e `RF-02` e os eventos `EV-02`, `EV-03` e `EV-20`.
- **Domínio funcional:** 4. Descoberta UDP
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-DISC-001`, `TASK-FSAP-002`
- **Critérios de aceitação objetivos:**
  - Emissão exata a cada 5 s no `TestScope`;
  - Envio garantido de datagrama `BYE` ao invocar `stop()`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/discovery/DiscoveryBroadcaster.kt`
- **Testes necessários:** Testes com `FakeMulticastTransport` e `advanceTimeBy` em `commonTest`.
- **Status inicial:** `TODO`

### `TASK-DISC-003` — Listener UDP Multicast com validação de datagramas, filtro de loopback e resposta com jitter
- **ID único:** `TASK-DISC-003`
- **Título:** Listener UDP Multicast com validação de datagramas, filtro de loopback e resposta com jitter
- **Descrição:** Implementar `DiscoveryListener` em `commonMain` que escuta continuamente datagramas do `MulticastTransport`, descarta silenciosamente pacotes malformados ou cujo `senderId == localDeviceId`, responde a pacotes `DISCOVER` agendando um `ANNOUNCE` com jitter aleatório de `50..300 ms` e encaminha eventos válidos (`ANNOUNCE`, `BYE`) para a `PeerRegistry`.
- **Objetivo:** Cumprir o evento `EV-04` e evitar tempestades de broadcast e auto-descoberta.
- **Domínio funcional:** 4. Descoberta UDP
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-DISC-001`, `TASK-FSAP-005`
- **Critérios de aceitação objetivos:**
  - Pacotes do próprio `deviceId` nunca são repassados à `PeerRegistry` (`TC-FSAP-002`);
  - Pacotes corrompidos não derrubam a coroutine do listener (`TC-FSAP-003`).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/discovery/DiscoveryListener.kt`
- **Testes necessários:** Casos `TC-FSAP-002` e `TC-FSAP-003` em `commonTest`.
- **Status inicial:** `TODO`

### `TASK-DISC-004` — Implementação da `PeerRegistry` concorrente com controle `STALE` e expiração por TTL (18 s)
- **ID único:** `TASK-DISC-004`
- **Título:** Implementação da `PeerRegistry` concorrente com controle de estado `STALE` e expiração automática por TTL (18 s)
- **Descrição:** Implementar em `commonMain` o repositório reativo `PeerRegistry` que mantém o mapa de dispositivos descobertos indexado por `deviceId`, expõe `StateFlow<List<DiscoveredPeer>>` e executa a tarefa de varredura a cada `3000 ms` (`T_PEER_SWEEP_INTERVAL`), transitando pares sem anúncio há `> 12 s` para `STALE` e removendo pares com `> 18 s` (`T_PEER_TTL`) ou que enviaram `BYE`.
- **Objetivo:** Atender aos requisitos `RF-04` e `RNF-05` e eventos `EV-05`, `EV-06` e `EV-07`.
- **Domínio funcional:** 4. Descoberta UDP
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-FSAP-003`, `TASK-DISC-003`
- **Critérios de aceitação objetivos:**
  - Atualização de IP/alias de um mesmo `deviceId` não duplica entradas;
  - Expiração ocorre exatamente após 18 s de silêncio (`TC-FSAP-004`) e remoção imediata no `BYE` (`TC-FSAP-005`).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/discovery/PeerRegistry.kt`
- **Testes necessários:** Casos `TC-FSAP-004` e `TC-FSAP-005` em `commonTest`.
- **Status inicial:** `TODO`

### `TASK-DISC-005` — Serviço de Conexão Manual por IP e verificação direta via mensagem `IDENTIFY` sobre TCP
- **ID único:** `TASK-DISC-005`
- **Título:** Serviço de Conexão Manual por IP e verificação direta via mensagem `IDENTIFY` sobre TCP
- **Descrição:** Implementar no cliente de rede a função `connectByManualIp(ipAddress: String, port: Int = 53317)` que realiza `POST /fsap/v1/identify` (timeout de `5000 ms`), valida a identidade e versão do servidor remoto e registra o dispositivo na `PeerRegistry` no estado `CONNECTED`.
- **Objetivo:** Atender ao requisito obrigatório `RF-05` (alternativa quando redes locais bloqueiam multicast) e viabilizar a Milestone M2 antes mesmo da M3.
- **Domínio funcional:** 4. Descoberta UDP
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-FSAP-002`, `TASK-DSRV-001`
- **Critérios de aceitação objetivos:**
  - Conexão manual para um servidor válido registra o par na `PeerRegistry` (`TC-FSAP-006`);
  - IP inacessível ou versão incompatível retorna `FSAP-ERR-404-DEVICE_NOT_FOUND` ou `FSAP-ERR-505-UNSUPPORTED_VERSION` (`TC-FSAP-007`).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/discovery/ManualPeerConnector.kt`
- **Testes necessários:** Casos `TC-FSAP-006` e `TC-FSAP-007`.
- **Status inicial:** `DONE`

---

## 5. Domínio Funcional: Servidor Desktop (`TASK-DSRV-*`)

### `TASK-DSRV-001` — Inicialização do motor HTTP Server Ktor CIO independente e rota de handshake `IDENTIFY`
- **ID único:** `TASK-DSRV-001`
- **Título:** Inicialização do motor HTTP Server Ktor CIO independente e rota de handshake `IDENTIFY`
- **Descrição:** Implementar a classe `FsapHttpServer` (usando `embeddedServer(CIO, port = httpPort)`) com serialização `kotlinx.serialization`, tratamento global de erros retornando o envelope `ERROR`, e os endpoints `GET /fsap/v1/info` e `POST /fsap/v1/identify`, conectando-a ao entrypoint independente `ServerMain.kt`.
- **Objetivo:** Colocar no ar a fundação do servidor HTTP/TCP independente da GUI (`RF-16`).
- **Domínio funcional:** 5. Servidor Desktop
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-INF-002`, `TASK-FSAP-002`, `TASK-FSAP-004`
- **Critérios de aceitação objetivos:**
  - Servidor inicia via `./gradlew :composeApp:runDesktopServer`, faz bind na porta configurada, responde `200 OK` com envelope `IDENTIFY` válido e encerra graciosamente com `SIGINT`/`SIGTERM`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/server/FsapHttpServer.kt`, `desktopMain/.../ServerMain.kt`
- **Testes necessários:** Teste de integração da rota `/fsap/v1/identify` com `testApplication` em `desktopTest`.
- **Status inicial:** `DONE`

### `TASK-DSRV-002` — Gerenciador de sessões concorrentes do servidor (`ServerTransferSessionManager`)
- **ID único:** `TASK-DSRV-002`
- **Título:** Gerenciador de sessões concorrentes do servidor (`ServerTransferSessionManager`) e integração com `TransferStateMachine`
- **Descrição:** Implementar o gerenciador concorrente (`ConcurrentHashMap` / `Mutex`) capaz de manter até `MAX_CONCURRENT_TRANSFERS` sessões simultâneas, isolando cada `transferId` com sua própria instância de `TransferStateMachine`, metadados aprovados, `transferToken` e timers de expiração (`T_APPROVAL_TIMEOUT` = 60 s e `T_TOKEN_IDLE_TTL` = 30 s).
- **Objetivo:** Suportar múltiplas conexões e transferências simultâneas com segurança de threads (`RF-15`).
- **Domínio funcional:** 5. Servidor Desktop
- **Prioridade:** `P0`
- **Complexidade estimada:** `Alta`
- **Dependências:** `TASK-DSRV-001`, `TASK-FSAP-003`
- **Critérios de aceitação objetivos:**
  - Múltiplas sessões independentes podem coexistir sem interferência de estado;
  - Sessões que excedem `60 s` em `PENDING_APPROVAL` ou `30 s` ociosas em `ACCEPTED` transitam automaticamente para `FAILED`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/server/ServerTransferSessionManager.kt`
- **Testes necessários:** Testes unitários concorrentes e de timeout em `commonTest`/`desktopTest`.
- **Status inicial:** `DONE`

### `TASK-DSRV-003` — Endpoint `POST /fsap/v1/transfers/prepare` (`TRANSFER_REQUEST`) com aprovação explícita
- **ID único:** `TASK-DSRV-003`
- **Título:** Endpoint `POST /fsap/v1/transfers/prepare` (`TRANSFER_REQUEST`) com callback de aprovação humana, timeout de 60 s e geração de `transferToken`
- **Descrição:** Implementar a rota de recepção de `TRANSFER_REQUEST`: validar manifesto e nomes de arquivos (`FileNameSanitizer`), verificar espaço livre em disco no `FileSink`, delegar pedido de confirmação para a interface `TransferApprovalHandler` (com implementação para GUI via modal e implementação para Servidor CLI/Headless via prompt de console ou política configurada), aguardar até 60 s e responder `TRANSFER_ACCEPT` (200) ou `TRANSFER_REJECT` (403).
- **Objetivo:** Atender aos requisitos `RF-07` e `RF-08` (confirmação explícita obrigatória do destinatário antes de receber).
- **Domínio funcional:** 5. Servidor Desktop
- **Prioridade:** `P0`
- **Complexidade estimada:** `Alta`
- **Dependências:** `TASK-DSRV-002`, `TASK-SEC-001`, `TASK-SEC-002`
- **Critérios de aceitação objetivos:**
  - Nomes inválidos retornam `400` (`FSAP-ERR-400-INVALID_FILENAME`);
  - Falta de espaço retorna `507` (`FSAP-ERR-507-INSUFFICIENT_STORAGE`);
  - Aprovação gera `transferToken` e retorna `200 OK`; recusa retorna `403`; ausência de decisão em 60 s retorna `408`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/server/routes/TransferPrepareRoute.kt`
- **Testes necessários:** Casos `TC-FSAP-008`, `TC-FSAP-009`, `TC-FSAP-010`, `TC-FSAP-011` e `TC-FSAP-012`.
- **Status inicial:** `DONE`

### `TASK-DSRV-004` — Endpoint `PUT /fsap/v1/transfers/{id}/files/{fileId}/content` (`TRANSFER_CONTENT`)
- **ID único:** `TASK-DSRV-004`
- **Título:** Endpoint `PUT /fsap/v1/transfers/{id}/files/{fileId}/content` (`TRANSFER_CONTENT`) com escrita em arquivo `.part`, verificação SHA-256 e commit atômico
- **Descrição:** Implementar a rota de recebimento do stream binário: validar cabeçalho `X-FSAP-Transfer-Token`, ler do `ByteReadChannel` em blocos de 64 KB com timeout de leitura de 15 s, gravar no arquivo temporário `<transferId>_<fileId>.part`, atualizar o digest SHA-256 incrementalmente, verificar `bytesReceived == sizeBytes` e `calculatedSha256 == expectedSha256` ao final, promovendo o arquivo `.part` para o nome final ou excluindo-o imediatamente em qualquer erro.
- **Objetivo:** Cumprir os requisitos `RF-09`, `RF-12`, `RF-14`, `RNF-01` e `RNF-07`.
- **Domínio funcional:** 5. Servidor Desktop
- **Prioridade:** `P0`
- **Complexidade estimada:** `Alta`
- **Dependências:** `TASK-DSRV-003`, `TASK-XFER-001`
- **Critérios de aceitação objetivos:**
  - Recepção de arquivo de múltiplos megabytes usa buffer constante de 64 KB;
  - Hash SHA-256 idêntico promove o arquivo `.part` para o destino final;
  - Divergência de 1 bit no conteúdo apaga o `.part` e retorna `422` (`FSAP-ERR-422-HASH_MISMATCH`).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/server/routes/TransferContentRoute.kt`
- **Testes necessários:** Casos `TC-FSAP-013`, `TC-FSAP-014`, `TC-FSAP-019`, `TC-FSAP-020` e `TC-FSAP-021`.
- **Status inicial:** `DONE`

### `TASK-DSRV-005` — Endpoints `GET /status`, `POST /cancel` e `POST /complete` no Servidor Desktop
- **ID único:** `TASK-DSRV-005`
- **Título:** Endpoints `GET /status`, `POST /cancel` e `POST /complete` e limpeza garantida de arquivos `.part` no Servidor Desktop
- **Descrição:** Implementar as rotas de consulta de estado (`TRANSFER_STATUS`), cancelamento explícito (`TRANSFER_CANCEL` abortando o canal ativo e apagando `.part`) e finalização de lote (`TRANSFER_COMPLETE` verificando se todos os arquivos aceitos foram validados antes de transitar para `COMPLETED`).
- **Objetivo:** Completar o ciclo de controle de sessão HTTP/TCP no servidor FSAP.
- **Domínio funcional:** 5. Servidor Desktop
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-DSRV-004`
- **Critérios de aceitação objetivos:**
  - `POST /complete` só aceita transitar para `COMPLETED` se 100% dos arquivos do lote estiverem verificados (`TC-FSAP-015`, `TC-FSAP-023`);
  - `POST /cancel` interrompe imediatamente a recepção e remove arquivos `.part` (`TC-FSAP-017`).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/server/routes/TransferControlRoutes.kt`
- **Testes necessários:** Casos `TC-FSAP-015`, `TC-FSAP-016`, `TC-FSAP-017`, `TC-FSAP-018` e `TC-FSAP-023`.
- **Status inicial:** `DONE`

---

## 6. Domínio Funcional: Cliente Desktop (`TASK-DCLI-*`)

### `TASK-DCLI-001` — Estrutura base da interface Compose Desktop, navegação e tema visual compartilhado
- **ID único:** `TASK-DCLI-001`
- **Título:** Estrutura base da interface Compose Desktop, navegação e tema visual compartilhado
- **Descrição:** Configurar a janela principal Desktop em `Main.kt` e criar em `commonMain` (`br.edu.fileshare.ui`) o layout base Material 3 (barra lateral/superior de status do nó local: alias, IP, porta, estado `LocalNodeState` e abas de Enviar / Recebidos / Configurações) desacoplado da camada de rede via `ViewModel` / `StateFlow`.
- **Objetivo:** Prover o esqueleto visual reativo compartilhado entre Desktop e Android.
- **Domínio funcional:** 6. Cliente Desktop
- **Prioridade:** `P1`
- **Complexidade estimada:** `Baixa`
- **Dependências:** `TASK-INF-003`
- **Critérios de aceitação objetivos:**
  - Aplicativo gráfico Desktop abre exibindo a identidade do dispositivo local e o estado atual do `LocalNodeStateMachine` sem chamadas diretas a sockets na camada de UI.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/ui/`, `desktopMain/kotlin/br/edu/fileshare/desktop/Main.kt`
- **Testes necessários:** Teste de estado do ViewModel principal em `commonTest`.
- **Status inicial:** `TODO`

### `TASK-DCLI-002` — Tela de listagem de dispositivos disponíveis integrada com a `PeerRegistry`
- **ID único:** `TASK-DCLI-002`
- **Título:** Tela de listagem de dispositivos disponíveis integrada em tempo real com a `PeerRegistry`
- **Descrição:** Implementar o painel de dispositivos descobertos na rede local observando `PeerRegistry.peers: StateFlow<List<DiscoveredPeer>>`, exibindo nome (`alias`), tipo de dispositivo (ícone Desktop/Mobile), endereço IP:Porta, indicador de status (`DISCOVERED`, `CONNECTED`, `STALE`) e botão de atualizar busca (`DISCOVER`).
- **Objetivo:** Atender ao requisito `RF-04` na interface gráfica.
- **Domínio funcional:** 6. Cliente Desktop
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-DCLI-001`, `TASK-DISC-004`
- **Critérios de aceitação objetivos:**
  - A lista reflete automaticamente a entrada, atualização e expiração (TTL) de dispositivos sem necessidade de recarregar a tela.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/ui/screens/DeviceDiscoveryScreen.kt`
- **Testes necessários:** Teste do ViewModel de descoberta alimentado por `PeerRegistry` em `commonTest`.
- **Status inicial:** `TODO`

### `TASK-DCLI-003` — Componente de Conexão Manual por IP:Porta na interface Desktop
- **ID único:** `TASK-DCLI-003`
- **Título:** Componente de Conexão Manual por IP:Porta na interface Desktop
- **Descrição:** Implementar o diálogo "Conectar por IP" com validação de formato IPv4 e porta (`1024..65535`, padrão `53317`), acionando o `ManualPeerConnector` (`IDENTIFY`) com indicador de carregamento (`CONNECTING`) e tratamento amigável de erros (`DEVICE_NOT_FOUND`, `UNSUPPORTED_VERSION`).
- **Objetivo:** Permitir operação completa em redes com bloqueio de multicast (`RF-05`).
- **Domínio funcional:** 6. Cliente Desktop
- **Prioridade:** `P0`
- **Complexidade estimada:** `Baixa`
- **Dependências:** `TASK-DCLI-001`, `TASK-DISC-005`
- **Critérios de aceitação objetivos:**
  - Usuário consegue adicionar um Servidor Desktop Independente informando `127.0.0.1:53319` ou IP da LAN e selecioná-lo para envio.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/ui/components/ManualIpConnectDialog.kt`
- **Testes necessários:** Teste de validação de entrada de IP/Porta no ViewModel em `commonTest`.
- **Status inicial:** `TODO`

### `TASK-DCLI-004` — Integração com seletor nativo de arquivos Desktop e `DesktopNioFileStorage`
- **ID único:** `TASK-DCLI-004`
- **Título:** Integração com seletor nativo de arquivos Desktop e leitura/escrita `DesktopNioFileStorage`
- **Descrição:** Implementar em `desktopMain` o seletor nativo de arquivos (`FileDialog` / `JFileChooser` em `Dispatchers.Swing`) para seleção de múltiplos arquivos e a implementação `DesktopNioFileStorage` (`FileSource` e `FileSink` baseados em `java.nio.file.Files` com suporte a arquivos `.part` e movimentação atômica).
- **Objetivo:** Cumprir os requisitos `RF-06` e `RF-14` na plataforma Desktop (Windows e Linux).
- **Domínio funcional:** 6. Cliente Desktop
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-DCLI-001`, `TASK-XFER-001`
- **Critérios de aceitação objetivos:**
  - Seleção de 1 ou mais arquivos exibe nome, tamanho formatado e permite iniciar o envio;
  - Recepção grava em `.part` na pasta de downloads configurada e move para o arquivo final após verificação.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/desktopMain/kotlin/br/edu/fileshare/desktop/storage/`
- **Testes necessários:** Testes de leitura, escrita `.part`, commit e colisão de nomes com `Files.createTempDirectory` em `desktopTest`.
- **Status inicial:** `TODO`

### `TASK-DCLI-005` — Modal de confirmação explícita de recebimento de arquivos (Aceitar / Recusar)
- **ID único:** `TASK-DCLI-005`
- **Título:** Modal de confirmação explícita de recebimento de arquivos (Aceitar / Recusar) no Desktop
- **Descrição:** Implementar o componente modal que é exibido automaticamente quando o nó local recebe uma solicitação `TRANSFER_REQUEST` (`PENDING_APPROVAL`), mostrando o `senderAlias`, tipo do dispositivo remetente, lista de arquivos, tamanho total, contador regressivo de `60 s` e botões de **Aceitar** e **Recusar**.
- **Objetivo:** Cumprir o requisito `RF-08` na interface gráfica.
- **Domínio funcional:** 6. Cliente Desktop
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-DCLI-001`, `TASK-DSRV-003`
- **Critérios de aceitação objetivos:**
  - Clicar em "Aceitar" libera o `TRANSFER_ACCEPT` no servidor; clicar em "Recusar" envia `TRANSFER_REJECT`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/ui/components/IncomingTransferApprovalDialog.kt`
- **Testes necessários:** Teste de interação do estado de aprovação no ViewModel em `commonTest`.
- **Status inicial:** `TODO`

### `TASK-DCLI-006` — Tela de progresso de transferências e botão de cancelamento
- **ID único:** `TASK-DCLI-006`
- **Título:** Tela de progresso de transferências (velocidade, percentual, ETA) e botão de cancelamento no Desktop
- **Descrição:** Implementar a visualização em tempo real das transferências ativas e concluídas (envio e recebimento), exibindo barra de progresso linear (`0..100%`), bytes transferidos/total, taxa de transferência (MB/s), estado atual (`PENDING_APPROVAL`, `TRANSFERRING`, `VERIFYING`, `COMPLETED`, `CANCELLED`, `FAILED`), código/mensagem de erro caso ocorra falha e botão **Cancelar**.
- **Objetivo:** Atender aos requisitos `RF-09`, `RF-10`, `RF-11` e `RF-13` na interface do usuário.
- **Domínio funcional:** 6. Cliente Desktop
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-DCLI-001`, `TASK-XFER-004`, `TASK-XFER-005`
- **Critérios de aceitação objetivos:**
  - Barra de progresso atualiza suavemente durante a transferência e botão "Cancelar" interrompe a operação em andamento.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/ui/screens/TransferProgressScreen.kt`
- **Testes necessários:** Teste unitário de formatação de métricas e estados de UI em `commonTest`.
- **Status inicial:** `TODO`

---

## 7. Domínio Funcional: Cliente Android (`TASK-AND-*`)

### `TASK-AND-001` — Implementação do `AndroidMulticastLockManager` e adaptador UDP Multicast para Android
- **ID único:** `TASK-AND-001`
- **Título:** Implementação do `AndroidMulticastLockManager` e adaptador UDP Multicast para Android
- **Descrição:** Implementar em `androidMain` o gerenciamento seguro de `WifiManager.MulticastLock` e a integração com o `MulticastTransport` sobre a interface `wlan0` ativa do dispositivo Android.
- **Objetivo:** Permitir que smartphones e tablets Android recebam e enviem pacotes UDP Multicast `ANNOUNCE`/`DISCOVER`/`BYE` na rede Wi-Fi local.
- **Domínio funcional:** 7. Cliente Android
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-DISC-001`, `TASK-DISC-003`
- **Critérios de aceitação objetivos:**
  - `MulticastLock` é adquirido ao iniciar a descoberta e garantidamente liberado ao parar o serviço (`isHeld == false`).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/androidMain/kotlin/br/edu/fileshare/android/network/AndroidMulticastLockManager.kt`
- **Testes necessários:** Teste unitário com mock/fakes do gerenciador de lock em `androidUnitTest`.
- **Status inicial:** `TODO`

### `TASK-AND-002` — Adaptador de arquivos Android com Storage Access Framework (SAF) e `MediaStore`
- **ID único:** `TASK-AND-002`
- **Título:** Adaptador de arquivos Android com *Storage Access Framework* (SAF), `ContentResolver` e publicação em `MediaStore.Downloads`
- **Descrição:** Implementar em `androidMain`: (a) seletor nativo via `ActivityResultContracts.OpenMultipleDocuments()`; (b) `AndroidSafFileSource` que lê metadados (`DISPLAY_NAME`, `SIZE`) e abre `InputStream` via `ContentResolver`; (c) `AndroidFileSink` que grava o arquivo `.part` em `context.cacheDir/incoming/` durante a recepção e, somente após a confirmação do SHA-256, publica o arquivo final em `MediaStore.Downloads` (API 29+) ou diretório SAF autorizado.
- **Objetivo:** Cumprir os requisitos `RF-06`, `RF-12` e `RF-14` respeitando o *Scoped Storage* do Android sem permissões invasivas.
- **Domínio funcional:** 7. Cliente Android
- **Prioridade:** `P0`
- **Complexidade estimada:** `Alta`
- **Dependências:** `TASK-XFER-001`
- **Critérios de aceitação objetivos:**
  - Leitura e escrita em streaming funcionam para arquivos grandes sem `OutOfMemoryError`;
  - Falha de SHA-256 ou cancelamento apaga o `.part` do `cacheDir` sem criar entrada corrompida em `Downloads`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/androidMain/kotlin/br/edu/fileshare/android/storage/`
- **Testes necessários:** Testes unitários da lógica de staging e limpeza em `androidUnitTest`.
- **Status inicial:** `TODO`

### `TASK-AND-003` — Implementação do `FsapForegroundService` (`dataSync`) e permissões no Android
- **ID único:** `TASK-AND-003`
- **Título:** Implementação do `FsapForegroundService` (`dataSync`) e gerenciamento de permissões de runtime e notificações no Android
- **Descrição:** Criar e registrar no `AndroidManifest.xml` o serviço `FsapForegroundService` (`foregroundServiceType="dataSync"`) que mantém o `FsapHttpServer` e o serviço de descoberta UDP ativos enquanto o modo de recepção ou uma transferência estiver em curso, exibindo notificação no canal do sistema e solicitando `POST_NOTIFICATIONS` no Android 13+.
- **Objetivo:** Evitar que o sistema operacional Android suspenda o servidor HTTP ou corte conexões TCP durante transferências longas (Seção 4.2 e `RISK-04`).
- **Domínio funcional:** 7. Cliente Android
- **Prioridade:** `P0`
- **Complexidade estimada:** `Alta`
- **Dependências:** `TASK-AND-001`, `TASK-DSRV-005`
- **Critérios de aceitação objetivos:**
  - Servidor FSAP no Android continua respondendo a `IDENTIFY` e recebendo `TRANSFER_CONTENT` mesmo com a tela bloqueada ou app em segundo plano;
  - Botão "Parar serviço" na notificação encerra o nó enviando `BYE`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/androidMain/kotlin/br/edu/fileshare/android/service/FsapForegroundService.kt`, `AndroidManifest.xml`
- **Testes necessários:** Validação de ciclo de vida do serviço e transições de `LocalNodeState`.
- **Status inicial:** `TODO`

### `TASK-AND-004` — Adaptação responsiva das telas Compose no Android (Descoberta, IP Manual e Aprovação)
- **ID único:** `TASK-AND-004`
- **Título:** Adaptação responsiva das telas Compose no Android (Descoberta, Conexão Manual por IP e Diálogo de Aprovação)
- **Descrição:** Conectar a `MainActivity.kt` do Android às telas compartilhadas em Compose Multiplatform (`commonMain`), adaptando o layout para telas verticais (smartphones) e horizontais (tablets) e integrando o launcher de seleção de arquivos SAF.
- **Objetivo:** Disponibilizar todas as interações de descoberta e aprovação na interface móvel Android.
- **Domínio funcional:** 7. Cliente Android
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-AND-002`, `TASK-AND-003`, `TASK-DCLI-002`
- **Critérios de aceitação objetivos:**
  - Aplicativo Android lista dispositivos da LAN, permite conexão manual por IP e exibe o modal de confirmação ao receber um `TRANSFER_REQUEST`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/androidMain/kotlin/br/edu/fileshare/android/MainActivity.kt`, `commonMain/.../ui/`
- **Testes necessários:** Testes de estado da UI móvel.
- **Status inicial:** `TODO`

### `TASK-AND-005` — Integração completa de envio e recebimento de arquivos no Android
- **ID único:** `TASK-AND-005`
- **Título:** Integração completa de envio e recebimento de arquivos em segundo plano com progresso e cancelamento no Android
- **Descrição:** Integrar o fluxo ponta a ponta de envio (Android → Desktop / Android → Android) e recebimento (Desktop → Android / Android → Android) com atualização de barra de progresso tanto na tela do app quanto na notificação do `FsapForegroundService`.
- **Objetivo:** Concluir a funcionalidade completa nas 4 direções de transferência previstas na Seção 3.
- **Domínio funcional:** 7. Cliente Android
- **Prioridade:** `P0`
- **Complexidade estimada:** `Alta`
- **Dependências:** `TASK-AND-004`, `TASK-XFER-005`
- **Critérios de aceitação objetivos:**
  - Arquivos enviados e recebidos no Android passam na verificação SHA-256 e ficam acessíveis na pasta `Downloads` do dispositivo.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/androidMain/kotlin/br/edu/fileshare/android/`
- **Testes necessários:** Casos de interoperabilidade da Milestone M5 (`TASK-TEST-005`).
- **Status inicial:** `TODO`

---

## 8. Domínio Funcional: Transferência de Arquivos (`TASK-XFER-*`)

### `TASK-XFER-001` — Abstrações `FileSource`/`FileSink` e motor de streaming de 64 KB com SHA-256 incremental
- **ID único:** `TASK-XFER-001`
- **Título:** Abstrações `FileSource`/`FileSink` e motor de streaming em chunks de 64 KB com cálculo incremental de SHA-256 (`IncrementalSha256Channel`)
- **Descrição:** Definir em `commonMain` as interfaces `FileSource` (leitura em stream), `FileSink` (criação de `.part`, escrita em stream, verificação de espaço livre, `commitPart()` e `discardPart()`) e o utilitário de cópia em chunks de `65536 bytes` que calcula o resumo `MessageDigest("SHA-256")` simultaneamente à leitura/escrita sem alocar o arquivo inteiro na memória.
- **Objetivo:** Atender aos requisitos `RF-09`, `RF-12`, `RNF-01` e `RNF-07`.
- **Domínio funcional:** 8. Transferência de Arquivos
- **Prioridade:** `P0`
- **Complexidade estimada:** `Alta`
- **Dependências:** `TASK-FSAP-001`
- **Critérios de aceitação objetivos:**
  - Hash SHA-256 calculado em streaming para arquivos de 0 B, 1 KB e 50 MB coincide exatamente com vetores de referência NIST;
  - Alocação de buffer é fixa em 64 KB.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/streaming/`, `storage/`
- **Testes necessários:** Testes unitários de streaming e hashing em `commonTest` (`TC-FSAP-019`, `TC-FSAP-025`).
- **Status inicial:** `DONE`

### `TASK-XFER-002` — Cliente HTTP FSAP (`FsapHttpClient`) para `TRANSFER_REQUEST` e tratamento de `ACCEPT`/`REJECT`
- **ID único:** `TASK-XFER-002`
- **Título:** Cliente HTTP FSAP (`FsapHttpClient` Ktor CIO) para envio de `TRANSFER_REQUEST` e tratamento de `TRANSFER_ACCEPT` / `TRANSFER_REJECT`
- **Descrição:** Implementar em `commonMain` o cliente HTTP Ktor CIO configurado com `T_TCP_CONNECT_TIMEOUT = 5000 ms` e `T_APPROVAL_TIMEOUT = 60000 ms` para enviar `POST /fsap/v1/transfers/prepare`, processando as respostas `200 OK` (`TRANSFER_ACCEPT` com `transferToken`), `403 Forbidden` (`TRANSFER_REJECT`) e envelopes `ERROR`.
- **Objetivo:** Implementar a negociação de sessão do lado do remetente (`RF-07` e `RF-08`).
- **Domínio funcional:** 8. Transferência de Arquivos
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-FSAP-002`, `TASK-FSAP-004`
- **Critérios de aceitação objetivos:**
  - Cliente transita corretamente a `TransferStateMachine` local de `CREATED` → `PENDING_APPROVAL` → `ACCEPTED` / `REJECTED` / `FAILED`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/client/FsapHttpClient.kt`
- **Testes necessários:** Testes de cliente com `MockEngine` / `testApplication` (`TC-FSAP-008`, `TC-FSAP-011`, `TC-FSAP-012`).
- **Status inicial:** `TODO`

### `TASK-XFER-003` — Upload binário em streaming (`TRANSFER_CONTENT`) e confirmação de lote (`TRANSFER_COMPLETE`)
- **ID único:** `TASK-XFER-003`
- **Título:** Upload binário em streaming (`TRANSFER_CONTENT`) e confirmação de lote (`TRANSFER_COMPLETE`) no `FsapHttpClient`
- **Descrição:** Implementar no `FsapHttpClient` o envio streaming `PUT /fsap/v1/transfers/{id}/files/{fileId}/content` utilizando `OutgoingContent.WriteChannelContent` (lendo do `FileSource` em chunks de 64 KB) com os cabeçalhos obrigatórios (`X-FSAP-Transfer-Token`, `X-FSAP-File-SHA256`, `Content-Length`) e, ao término de todos os arquivos do lote, a chamada `POST /fsap/v1/transfers/{id}/complete`.
- **Objetivo:** Viabilizar o envio eficiente de arquivos únicos ou múltiplos (`RF-06`, `RF-09`, `RF-12`).
- **Domínio funcional:** 8. Transferência de Arquivos
- **Prioridade:** `P0`
- **Complexidade estimada:** `Alta`
- **Dependências:** `TASK-XFER-001`, `TASK-XFER-002`
- **Critérios de aceitação objetivos:**
  - Nenhum arquivo é carregado integralmente em memória durante o upload;
  - Falha em qualquer arquivo do lote interrompe o envio e transita para `FAILED`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/client/FsapHttpClient.kt`
- **Testes necessários:** Casos `TC-FSAP-014` e `TC-FSAP-015`.
- **Status inicial:** `TODO`

### `TASK-XFER-004` — Coordenador de progresso de transferência em tempo real (`TransferProgressTracker`)
- **ID único:** `TASK-XFER-004`
- **Título:** Coordenador de progresso de transferência em tempo real (`TransferProgressTracker`) com throttling e suporte a múltiplas sessões
- **Descrição:** Implementar o rastreador concorrente que agrega o progresso de cada sessão ativa (bytes enviados/recebidos, percentual `0..100%`, velocidade em bytes/s por janela móvel e tempo restante estimado), aplicando throttling de `100 ms` nas emissões do `StateFlow` para manter a UI fluida.
- **Objetivo:** Atender aos requisitos `RF-10`, `RF-15` e `RNF-08`.
- **Domínio funcional:** 8. Transferência de Arquivos
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-XFER-003`, `TASK-FSAP-003`
- **Critérios de aceitação objetivos:**
  - Cálculo preciso de percentual e velocidade tanto para arquivo único quanto para lotes de múltiplos arquivos (`TC-FSAP-016`).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/transfer/TransferCoordinator.kt`
- **Testes necessários:** Caso `TC-FSAP-016` em `commonTest`.
- **Status inicial:** `TODO`

### `TASK-XFER-005` — Mecanismo de cancelamento cooperativo bilateral (`TRANSFER_CANCEL`)
- **ID único:** `TASK-XFER-005`
- **Título:** Mecanismo de cancelamento cooperativo bilateral (`TRANSFER_CANCEL`) e descarte imediato de arquivos `.part`
- **Descrição:** Implementar a lógica que permite ao usuário remetente ou destinatário cancelar uma sessão a qualquer momento: cancelando o `Job` da coroutine de streaming, enviando `POST /fsap/v1/transfers/{id}/cancel`, transitando a máquina de estados para `CANCELLED` e garantindo a exclusão imediata de todos os arquivos `.part` da sessão.
- **Objetivo:** Cumprir o requisito `RF-11` e o Fluxo 6 de `docs/communication-flows.md`.
- **Domínio funcional:** 8. Transferência de Arquivos
- **Prioridade:** `P1`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-XFER-003`, `TASK-DSRV-005`
- **Critérios de aceitação objetivos:**
  - Cancelamento aos 50% de um arquivo interrompe o tráfego TCP em `< 500 ms` e não deixa nenhum arquivo `.part` no diretório temporário (`TC-FSAP-017`, `TC-FSAP-018`).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/transfer/TransferCoordinator.kt`
- **Testes necessários:** Casos `TC-FSAP-017` e `TC-FSAP-018`.
- **Status inicial:** `TODO`

### `TASK-XFER-006` — Detector de desconexão abrupta, timeout de leitura (15 s) e recuperação limpa de estado
- **ID único:** `TASK-XFER-006`
- **Título:** Detector de desconexão abrupta, timeout de leitura (`15 s`) e recuperação limpa de estado
- **Descrição:** Refinar o tratamento de exceções de transporte (`SocketTimeoutException`, `EOFException`, `ConnectException`, queda de interface Wi-Fi) durante todas as fases da transferência e no encerramento gracioso da aplicação (envio de `TRANSFER_CANCEL` e `BYE` dentro de `T_SHUTDOWN_GRACE = 2000 ms`).
- **Objetivo:** Cumprir integralmente o requisito `RF-13` e os Fluxos 8 e 10.
- **Domínio funcional:** 8. Transferência de Arquivos
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-XFER-004`, `TASK-DSRV-005`
- **Critérios de aceitação objetivos:**
  - Queda simulada de socket ou congelamento por `> 15 s` move a sessão para `FAILED` (`FSAP-ERR-408-STREAM_TIMEOUT` / `FSAP-ERR-503-CONNECTION_LOST`) e limpa o `.part` sem vazar file descriptors (`TC-FSAP-021`, `TC-FSAP-022`).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/`
- **Testes necessários:** Casos `TC-FSAP-021` e `TC-FSAP-022`.
- **Status inicial:** `TODO`

---

## 9. Domínio Funcional: Segurança (`TASK-SEC-*`)

### `TASK-SEC-001` — Implementação do `FileNameSanitizer` contra Path Traversal e não-sobrescrita
- **ID único:** `TASK-SEC-001`
- **Título:** Implementação do `FileNameSanitizer` contra *Path Traversal*, nomes reservados e política de não-sobrescrita
- **Descrição:** Implementar em `commonMain` o validador/sanitizador de nomes de arquivos conforme as 6 regras de `docs/security.md` (bloqueio de `/`, `\`, `..`, bytes de controle `0x00..0x1F`, caracteres proibidos e nomes reservados `CON`, `NUL`, etc.) e o resolvedor de colisão de nomes (`arquivo (1).ext`).
- **Objetivo:** Cumprir o requisito `RNF-06` e proteger o sistema de arquivos do destinatário.
- **Domínio funcional:** 9. Segurança
- **Prioridade:** `P0`
- **Complexidade estimada:** `Baixa`
- **Dependências:** `TASK-FSAP-005`
- **Critérios de aceitação objetivos:**
  - Qualquer tentativa de *Path Traversal* é rejeitada com `FSAP-ERR-400-INVALID_FILENAME` (`TC-FSAP-009`);
  - Arquivos já existentes no diretório de destino nunca são sobrescritos (`TC-FSAP-024`).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/storage/FileNameSanitizer.kt`
- **Testes necessários:** Casos `TC-FSAP-009` e `TC-FSAP-024` em `commonTest`.
- **Status:** `DONE`

### `TASK-SEC-002` — Gerador e validador de `transferToken` efêmero (`SecureRandom`)
- **ID único:** `TASK-SEC-002`
- **Título:** Gerador e validador de `transferToken` efêmero (`SecureRandom`) vinculado à sessão e aos `fileId`s aprovados
- **Descrição:** Implementar `TransferTokenManager` utilizando `java.security.SecureRandom` (256 bits de entropia) para emitir tokens únicos por `transferId` aprovado, validando em tempo constante (`MessageDigest.isEqual`) se o token apresentado em `X-FSAP-Transfer-Token` pertence à sessão ativa e não expirou (`T_TOKEN_IDLE_TTL = 30 s`).
- **Objetivo:** Impedir uploads não autorizados ou sequestro de sessão por outros hosts na mesma LAN.
- **Domínio funcional:** 9. Segurança
- **Prioridade:** `P0`
- **Complexidade estimada:** `Baixa`
- **Dependências:** `TASK-FSAP-003`
- **Critérios de aceitação objetivos:**
  - Requisições sem token, com token de outra sessão ou com token expirado recebem `401 Unauthorized` (`FSAP-ERR-401-UNAUTHORIZED_TRANSFER` — `TC-FSAP-013`).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/server/TransferTokenManager.kt`
- **Testes necessários:** Caso `TC-FSAP-013`.
- **Status inicial:** `DONE`

### `TASK-SEC-003` — Proteção contra exaustão de recursos (limites JSON, cota de disco e overflow de stream)
- **ID único:** `TASK-SEC-003`
- **Título:** Proteção contra exaustão de recursos (limites de payload JSON `256 KB`, verificação prévia de espaço em disco e limite de bytes no stream)
- **Descrição:** Endurecer o servidor FSAP para: (a) rejeitar corpos JSON maiores que `256 KB`; (b) interromper imediatamente o stream `TRANSFER_CONTENT` se o remetente enviar sequer 1 byte além do `sizeBytes` aprovado no manifesto; (c) revalidar espaço em disco antes e durante a escrita.
- **Objetivo:** Proteger o nó receptor contra ataques de negação de serviço (DoS) de memória e disco na LAN.
- **Domínio funcional:** 9. Segurança
- **Prioridade:** `P1`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-DSRV-004`
- **Critérios de aceitação objetivos:**
  - Envio de mais bytes que o declarado é cortado imediatamente com `FSAP-ERR-400-SIZE_MISMATCH` e remoção do `.part`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/server/`
- **Testes necessários:** Testes de overflow de stream e limite de payload JSON em `desktopTest`.
- **Status inicial:** `TODO`

### `TASK-SEC-004` — Suporte opcional a transporte TLS autoassinado com verificação de fingerprint SHA-256
- **ID único:** `TASK-SEC-004`
- **Título:** Suporte opcional a transporte TLS autoassinado com verificação de *fingerprint* SHA-256 do certificado na LAN
- **Descrição:** Adicionar suporte opcional (configurável na Milestone M6) para geração de par de chaves RSA/ECDSA e certificado X.509 autoassinado local, exibindo os últimos 8 caracteres do fingerprint SHA-256 no diálogo de confirmação para autenticar visualmente o par.
- **Objetivo:** Elevar a segurança em redes públicas contra interceptação passiva/ativa.
- **Domínio funcional:** 9. Segurança
- **Prioridade:** `P2`
- **Complexidade estimada:** `Alta`
- **Dependências:** `TASK-SEC-003`
- **Critérios de aceitação objetivos:**
  - Modo HTTP padrão permanece funcional para inspeção acadêmica e modo HTTPS opcional funciona com validação de fingerprint.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/desktopMain/`, `composeApp/src/androidMain/`
- **Testes necessários:** Teste de handshake TLS entre cliente e servidor em `desktopTest`.
- **Status inicial:** `TODO`

---

## 10. Domínio Funcional: Testes e Integração (`TASK-TEST-*`)

### `TASK-TEST-001` — Suíte de testes unitários em `commonTest` (serialização, validadores e máquinas de estados)
- **ID único:** `TASK-TEST-001`
- **Título:** Suíte de testes unitários de serialização JSON, validadores, `FileNameSanitizer` e máquinas de estados em `commonTest`
- **Descrição:** Implementar a bateria completa de testes unitários de Nível 1 em `commonTest` cobrindo 100% das mensagens do protocolo FSAP/1.0, todas as transições válidas e inválidas das 3 máquinas de estados e todos os vetores de sanitização de nomes de arquivos.
- **Objetivo:** Garantir a solidez do núcleo compartilhado antes de integrar sockets reais (fechamento da Milestone M1).
- **Domínio funcional:** 10. Testes e Integração
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-FSAP-003`, `TASK-FSAP-005`, `TASK-SEC-001`
- **Critérios de aceitação objetivos:**
  - `./gradlew :composeApp:desktopTest` executa todos os testes unitários do protocolo com 100% de aprovação.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonTest/kotlin/br/edu/fileshare/protocol/`
- **Testes necessários:** Casos `TC-FSAP-001`, `TC-FSAP-007`, `TC-FSAP-009`, `TC-FSAP-023` e `TC-FSAP-024`.
- **Status inicial:** `DONE`

### `TASK-TEST-002` — Suíte de testes de conformidade HTTP/TCP (`TC-FSAP-006` a `TC-FSAP-025`)
- **ID único:** `TASK-TEST-002`
- **Título:** Suíte de testes de conformidade HTTP/TCP (`TC-FSAP-006` a `TC-FSAP-025`) com injeção de corrupção SHA-256 e truncamento
- **Descrição:** Implementar em `desktopTest` os testes automatizados de integração cliente-servidor cobrindo: aprovação, recusa, timeout de 60 s, token inválido, transferência multi-chunk, lote de múltiplos arquivos, cancelamento pelo remetente e pelo destinatário, corrupção proposital de 1 byte (validando `FSAP-ERR-422-HASH_MISMATCH` e remoção do `.part`) e truncamento de stream.
- **Objetivo:** Comprovar matematicamente a conformidade da implementação HTTP/TCP com a especificação FSAP/1.0 (fechamento da Milestone M2).
- **Domínio funcional:** 10. Testes e Integração
- **Prioridade:** `P0`
- **Complexidade estimada:** `Alta`
- **Dependências:** `TASK-DSRV-005`, `TASK-XFER-005`
- **Critérios de aceitação objetivos:**
  - Todos os casos `TC-FSAP-006` a `TC-FSAP-025` passam de forma determinística e nenhum arquivo `.part` resta nos diretórios temporários após os testes.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/desktopTest/kotlin/br/edu/fileshare/conformance/`
- **Testes necessários:** Execução automatizada de `TC-FSAP-006` a `TC-FSAP-025`.
- **Status inicial:** `TODO`

### `TASK-TEST-003` — Suíte de testes do subsistema de descoberta UDP Multicast e expiração de `PeerRegistry`
- **ID único:** `TASK-TEST-003`
- **Título:** Suíte de testes do subsistema de descoberta UDP Multicast e expiração de `PeerRegistry` (`TC-FSAP-001` a `TC-FSAP-005`)
- **Descrição:** Implementar os testes automatizados cobrindo emissão de `DISCOVER`/`ANNOUNCE`/`BYE`, filtro de loopback do próprio `deviceId`, descarte de datagramas inválidos e expiração exata aos 18 s na `PeerRegistry`.
- **Objetivo:** Validar o subsistema de descoberta automática (fechamento da Milestone M3).
- **Domínio funcional:** 10. Testes e Integração
- **Prioridade:** `P0`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-DISC-004`
- **Critérios de aceitação objetivos:**
  - Casos `TC-FSAP-001` a `TC-FSAP-005` executam com sucesso em `commonTest` e `desktopTest`.
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/commonTest/kotlin/br/edu/fileshare/network/discovery/`
- **Testes necessários:** Execução automatizada de `TC-FSAP-001` a `TC-FSAP-005`.
- **Status inicial:** `TODO`

### `TASK-TEST-004` — Testes de carga e múltiplas conexões simultâneas (`TC-FSAP-026`)
- **ID único:** `TASK-TEST-004`
- **Título:** Testes de carga e múltiplas conexões simultâneas (`TC-FSAP-026`) entre múltiplos clientes e Servidor Desktop Independente
- **Descrição:** Criar teste automatizado em `desktopTest` instanciando 1 Servidor Desktop e 4 Clientes simultâneos enviando lotes de arquivos em paralelo, verificando ausência de condições de corrida (*race conditions*), integridade SHA-256 de todos os arquivos e estabilidade de memória.
- **Objetivo:** Validar o requisito `RF-15` (suporte a múltiplas transferências e conexões simultâneas).
- **Domínio funcional:** 10. Testes e Integração
- **Prioridade:** `P1`
- **Complexidade estimada:** `Média`
- **Dependências:** `TASK-TEST-002`, `TASK-XFER-006`
- **Critérios de aceitação objetivos:**
  - 4 sessões simultâneas concluem em `COMPLETED` com 100% dos hashes SHA-256 íntegros (`TC-FSAP-026`).
- **Arquivos ou módulos potencialmente envolvidos:** `composeApp/src/desktopTest/kotlin/br/edu/fileshare/conformance/ConcurrentTransfersTest.kt`
- **Testes necessários:** Caso `TC-FSAP-026`.
- **Status inicial:** `TODO`

### `TASK-TEST-005` — Bateria de testes de interoperabilidade ponta a ponta (Desktop ↔ Android)
- **ID único:** `TASK-TEST-005`
- **Título:** Bateria de testes de interoperabilidade ponta a ponta (Desktop ↔ Desktop, Desktop ↔ Android e Android ↔ Android)
- **Descrição:** Executar e registrar o roteiro de validação em rede local real nos 4 sentidos de transferência (Desktop → Desktop, Desktop → Android, Android → Desktop e Android → Android), testando arquivos pequenos, arquivos grandes (`> 500 MB`), recusa, cancelamento e queda de Wi-Fi.
- **Objetivo:** Homologar o sistema integrado nas plataformas alvo antes da entrega final.
- **Domínio funcional:** 10. Testes e Integração
- **Prioridade:** `P0`
- **Complexidade estimada:** `Alta`
- **Dependências:** `TASK-AND-005`, `TASK-TEST-002`, `TASK-TEST-003`
- **Critérios de aceitação objetivos:**
  - Checklist de interoperabilidade nos sentidos exigidos aprovado com verificação `sha256sum` idêntica entre origem e destino.
- **Arquivos ou módulos potencialmente envolvidos:** `docs/testing.md`, scripts de verificação
- **Testes necessários:** Roteiro completo de aceitação em LAN.
- **Status inicial:** `TODO`


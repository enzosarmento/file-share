# Guia de Colaboração e Engenharia — Equipe FileShare

> **Escopo:** Convenções técnicas, fluxo de trabalho Git, processo de revisão e critérios de qualidade para o desenvolvimento colaborativo do projeto **FileShare** por uma equipe de 4 pessoas (Seção 10).  
> **Nota:** Este documento não atribui responsabilidades nominais fixas; qualquer integrante pode assumir tasks das trilhas paralelas respeitando as regras abaixo.

---

## 1. Convenção de Nomenclatura de Código e Artefatos

Todo o código do projeto segue o guia oficial de estilo Kotlin (`kotlin.code.style=official`):

| Elemento | Convenção | Exemplos no Projeto |
| :--- | :--- | :--- |
| **Pacotes** | `lowercase`, sem underscores, sob `br.edu.fileshare` | `br.edu.fileshare.protocol.statemachine`, `br.edu.fileshare.network.discovery` |
| **Classes e Interfaces** | `PascalCase` (substantivos claros indicando o papel arquitetural) | `TransferStateMachine`, `PeerRegistry`, `FileSource`, `FsapHttpServer` |
| **DTOs do Protocolo FSAP** | Sufixo `Payload` ou `Dto` em `protocol.model` | `AnnouncePayload`, `TransferRequestPayload`, `FileItemDto` |
| **Funções e Propriedades** | `camelCase` | `calculateSha256()`, `bytesTransferred`, `acceptedFileIds` |
| **Constantes Normativas** | `UPPER_SNAKE_CASE` | `T_ANNOUNCE_INTERVAL`, `T_PEER_TTL`, `DEFAULT_HTTP_PORT` |
| **Componentes Compose UI** | `PascalCase` (substantivos descritivos) | `DeviceDiscoveryScreen`, `IncomingTransferApprovalDialog` |
| **Testes Automatizados** | `<ClasseAlvo>Test` e métodos descritivos (ou `tcFsap0xx_...`) | `TransferStateMachineTest`, `tcFsap020_rejectCorruptedFileOnHashMismatch()` |

---

## 2. Estratégia de Branches (Git Flow Simplificado para 4 Integrantes)

Adotamos uma estratégia enxuta baseada em `main`, `develop` e branches curtas por funcionalidade/task:

- **`main`**: Branch estável contendo apenas entregas de Milestones concluídas e homologadas (`M0`, `M1`, `M2`, `M3`, `M4`, `M5`, `M6`). Protegida contra *push* direto.
- **`develop`**: Branch de integração contínua da equipe. Todas as branches de trabalho partem de `develop` e voltam para `develop` via Pull Request.
- **Branches de Trabalho (curta duração, vinculadas às tasks do backlog):**
  - `feature/<dominio>-<id-curto>`: Novas funcionalidades (ex: `feature/fsap-dtos`, `feature/discovery-multicast`, `feature/server-prepare-route`, `feature/client-desktop-ui`, `feature/android-saf`).
  - `fix/<id-ou-descricao>`: Correções de bugs ou falhas de conformidade (ex: `fix/sha256-part-cleanup`).
  - `docs/<topico>`: Atualizações de especificação, ADRs ou relatório acadêmico (ex: `docs/adr-ratification`).
  - `test/<escopo>`: Adição de casos de teste de conformidade (ex: `test/fsap-conformance-tcp`).

---

## 3. Padrão de Commits (Conventional Commits)

Todos os commits devem seguir o formato:

```text
<tipo>(<escopo>): <descrição curta no imperativo> [TASK-XXX-NNN]
```

### Tipos Permitidos
- `feat`: Implementação de nova funcionalidade de protocolo, rede ou UI.
- `fix`: Correção de bug, vazamento de recurso ou falha de transição de estado.
- `docs`: Alterações na documentação em `docs/`, `tasks/` ou `README.md`.
- `test`: Criação ou refatoração de testes em `commonTest`, `desktopTest` ou `androidUnitTest`.
- `refactor`: Melhoria interna de código sem alterar contratos do protocolo FSAP.
- `build` / `chore`: Ajustes em Gradle, scripts ou dependências.

### Exemplos Válidos
- `feat(protocol): implement TransferStateMachine transitions and guards [TASK-FSAP-003]`
- `feat(discovery): add UDP multicast listener with loopback filter [TASK-DISC-003]`
- `test(conformance): add TC-FSAP-020 SHA-256 mismatch test [TASK-TEST-002]`

---

## 4. Estratégia para Evitar Conflitos em Arquivos Compartilhados

Com 4 pessoas trabalhando simultaneamente no mesmo monorepo KMP, siga estas regras para evitar conflitos de merge:

1. **Isolamento por Pacotes e Trilhas Paralelas:**
   - Utilize a divisão de 4 trilhas definida em [tasks/dependency-graph.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/tasks/dependency-graph.md) (Trilha A: `protocol.*`, Trilha B: `network.server.*`, Trilha C: `network.client.*` / `discovery.*` / `android.*`, Trilha D: `ui.*`).
2. **Um Arquivo por Rota ou Tela:**
   - No servidor Ktor, não coloque todos os endpoints dentro de um único arquivo gigante. Separe em `IdentifyRoute.kt`, `TransferPrepareRoute.kt`, `TransferContentRoute.kt` e `TransferControlRoutes.kt`.
   - Na UI Compose, separe cada tela e cada diálogo em seu próprio arquivo dentro de `ui/screens/` e `ui/components/`.
3. **Congelamento Prévio de Contratos (`commonMain`):**
   - As interfaces de porta (`FileSource`, `FileSink`, `MulticastTransport`) e os DTOs do protocolo (`TASK-FSAP-001` e `TASK-FSAP-002`) são mergeados primeiro em `develop`, permitindo que servidor, cliente e UI evoluam em arquivos separados.
4. **Alterações em `build.gradle.kts` e `libs.versions.toml`:**
   - O catálogo de bibliotecas já foi pré-configurado na etapa M0. Caso seja estritamente necessário adicionar uma dependência, avise a equipe e faça um PR pequeno e imediato apenas para o arquivo de build.

---

## 5. Processo de Pull Request (PR) e Revisão de Código

1. **Tamanho do PR:** Cada PR deve corresponder idealmente a **1 task** do backlog (`tasks/backlog.md`).
2. **Revisão Cruzada:** Todo PR para `develop` requer aprovação de pelo menos **1 outro integrante da equipe**. Alterações em `docs/protocol-fsap.md` ou `docs/protocol-messages.md` exigem ciência de **toda a equipe**, pois impactam todas as frentes.
3. **Checklist do Revisor:**
   - O código respeita a separação entre UI e serviço de rede?
   - Existe algum uso proibido de `readBytes()` que viole o streaming de 64 KB?
   - Os arquivos `.part` são garantidamente removidos em blocos `finally` em caso de falha ou cancelamento?
   - As mensagens JSON, cabeçalhos `X-FSAP-*` e códigos `FSAP-ERR-*` coincidem 100% com a documentação?

---

## 6. Compatibilidade Obrigatória com o Protocolo FSAP/1.0

O protocolo **FSAP/1.0** ([docs/protocol-fsap.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-fsap.md)) é o contrato sagrado do projeto:
- É **proibido** inventar campos JSON ad-hoc, alterar nomes de propriedades ou omitir cabeçalhos obrigatórios (`X-FSAP-Version`, `X-FSAP-Transfer-Token`, `X-FSAP-File-SHA256`) no código sem antes propor, aprovar e documentar a mudança na especificação do protocolo.

---

## 7. Definition of Done (DoD) — Quando uma Task está Concluída?

Uma task só pode mudar seu status de `TODO` / `IN_PROGRESS` para `DONE` quando atender **todos** os critérios abaixo:
1. Código implementado 100% em Kotlin seguindo a arquitetura em camadas definida em `docs/architecture.md`.
2. Todos os **Critérios de Aceitação Objetivos** listados na definição da task em `tasks/backlog.md` foram cumpridos.
3. Os **Testes Necessários** especificados na task foram escritos e passam localmente (`./gradlew :composeApp:desktopTest` e/ou `testDebugUnitTest`).
4. Nenhum warning crítico de compilação ou vazamento de coroutine/socket foi introduzido.
5. Pull Request revisado por pelo menos um par e integrado na branch `develop`.


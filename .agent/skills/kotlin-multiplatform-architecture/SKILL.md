---
name: kotlin-multiplatform-architecture
description: >-
  Use esta skill ao estruturar pacotes, interfaces de porta (ports & adapters),
  declaracoes expect/actual, injecao de dependencias ou separar responsabilidades
  entre commonMain, desktopMain (GUI vs Servidor Independente) e androidMain no projeto FileShare.
---

# Skill: Kotlin Multiplatform Architecture (`kotlin-multiplatform-architecture`)

## 1. Nome
`kotlin-multiplatform-architecture`

## 2. Descrição
Guia arquitetural para organização de código, fronteiras de módulos, inversão de dependência e uso correto de `commonMain`, `desktopMain` e `androidMain` no monorepo FileShare.

## 3. Objetivo
Garantir que a implementação respeite estritamente a separação entre lógica compartilhada de protocolo/domínio (`commonMain`), adaptações nativas de sistema operacional (`desktopMain`/`androidMain`) e o desacoplamento obrigatório entre o Servidor Desktop Independente e a Interface Gráfica.

## 4. Conhecimentos Necessários
- Kotlin Multiplatform (KMP) e hierarquia de `sourceSets`.
- Padrão *Ports and Adapters* (Arquitetura Hexagonal) aplicado a KMP.
- Concorrência estruturada com `kotlinx.coroutines` (`CoroutineScope`, `SupervisorJob`, `Dispatcher` injection).
- Configuração de múltiplos entrypoints JVM no Gradle (`MainKt` vs `ServerMainKt`).

## 5. Contexto Técnico
- Documentos de referência obrigatórios:
  - [docs/architecture.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/architecture.md)
  - [ADR-001 (KMP/CMP)](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-001-adocao-kmp-e-compose-multiplatform.md)
  - [ADR-002 (Servidor Desktop Independente)](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-002-separacao-cliente-gui-e-servidor-desktop.md)
- O projeto compila para `jvm("desktop")` (JDK 21) e `androidTarget` (minSdk 26, targetSdk 35).

## 6. Escopo de Atuação
- Definição de interfaces de fronteira em `commonMain` (`FileSource`, `FileSink`, `MulticastTransport`, `NetworkInterfaceProvider`, `TransferApprovalHandler`, `FsapLogger`).
- Composição e injeção manual de dependências (`AppContainer` / `ServerContainer`).
- Manutenção de `composeApp/build.gradle.kts` e `gradle/libs.versions.toml`.

## 7. Arquivos e Módulos que Pode Modificar
- `composeApp/build.gradle.kts`
- `gradle/libs.versions.toml`
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/domain/**`
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/core/**`
- `composeApp/src/desktopMain/kotlin/br/edu/fileshare/desktop/Main.kt`
- `composeApp/src/desktopMain/kotlin/br/edu/fileshare/desktop/server/ServerMain.kt`

## 8. Restrições
1. **Proibido acoplar UI ao Serviço de Rede:** Nenhum arquivo sob `br.edu.fileshare.protocol.*` ou `br.edu.fileshare.network.*` pode importar `androidx.compose.*`, `java.awt.*` ou `javax.swing.*`.
2. **Proibido código Java/Python/Rust:** Todo o código deve ser 100% Kotlin.
3. **Preferir Interfaces a `expect/actual` para Serviços de I/O:** Para facilitar o uso de *fakes* em `commonTest`, defina interfaces em `commonMain` (ex: `interface MulticastTransport`) e injete as implementações de `desktopMain` e `androidMain` via construtor.

## 9. Dependências
- Aprovação de `TASK-ARC-001` e `TASK-ARC-002`.

## 10. Critérios de Conclusão
- `commonMain` compila sem depender de APIs exclusivas de JVM ou Android.
- O Servidor Desktop Independente (`./gradlew :composeApp:runDesktopServer`) executa sem carregar classes gráficas.
- Testes em `commonTest` conseguem instanciar os coordenadores usando implementações *fake* das portas de plataforma.


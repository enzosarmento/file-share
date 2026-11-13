# Regras de Contexto para Agentes de IA — Projeto FileShare

Este repositório contém o projeto acadêmico **FileShare** (transferência local de arquivos entre Desktop Linux/Windows e Android via protocolo próprio **FSAP/1.0** em Kotlin Multiplatform e Compose Multiplatform).

## 1. Diretrizes Obrigatórias de Arquitetura e Protocolo

1. **Fonte Única da Verdade do Protocolo (`FSAP/1.0`):**
   - Antes de implementar qualquer código de rede, serialização, estados ou tratamento de erros, leia obrigatoriamente:
     - [docs/protocol-fsap.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-fsap.md)
     - [docs/state-machine.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/state-machine.md)
     - [docs/protocol-messages.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-messages.md)
     - [docs/error-codes.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/error-codes.md)
2. **Separação Estrita entre Interface Gráfica e Serviço de Rede:**
   - O Servidor Desktop possui um executável independente (`br.edu.fileshare.desktop.server.ServerMainKt`, task Gradle `runDesktopServer`).
   - Classes nos pacotes `br.edu.fileshare.protocol.*` e `br.edu.fileshare.network.*` **jamais** podem importar `androidx.compose.*`, `java.awt.*` ou `javax.swing.*`.
3. **Streaming Binário e Integridade SHA-256:**
   - Nunca carregue arquivos inteiros na memória RAM (`readBytes()` é proibido para conteúdo transferido).
   - Utilize sempre streaming em chunks de `64 KB` (`65536` bytes), cálculo incremental de `SHA-256` e gravação em arquivo temporário `.part` promovido para o arquivo final somente após validação do hash.
4. **Restrição de Linguagens:**
   - Utilize exclusivamente **Kotlin** (com APIs JVM/Android quando aplicável). Não crie arquivos em Python, Rust ou Java (`.java`).

## 2. Skills Especializadas do Projeto (`.agent/skills/`)

Ao executar tasks do backlog ([tasks/backlog.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/tasks/backlog.md)), ative e siga a skill correspondente ao domínio técnico:

- `kotlin-multiplatform-architecture`: Estrutura de pacotes, portas e adaptadores em `commonMain`, `desktopMain` e `androidMain`.
- `fsap-protocol`: DTOs, envelopes JSON, validadores, máquinas de estados e códigos de erro do FSAP/1.0.
- `udp-device-discovery`: Descoberta automática via UDP Multicast (`239.255.70.83:53318`), `ANNOUNCE`/`DISCOVER`/`BYE` e `PeerRegistry`.
- `kotlin-networking`: Cliente HTTP Ktor CIO, conexão manual por IP (`IDENTIFY`), filtro de interfaces de rede e timeouts.
- `ktor-http-server`: Servidor HTTP Ktor CIO, rotas `/fsap/v1/...`, sessões concorrentes e `ServerMainKt`.
- `binary-file-transfer`: Streaming em chunks de 64 KB, arquivos `.part` e hashing incremental SHA-256.
- `android-file-system`: SAF (`OpenMultipleDocuments`), `ContentResolver`, `MediaStore.Downloads`, `MulticastLock` e `FsapForegroundService`.
- `compose-multiplatform-ui`: Telas e componentes reativos em Compose Multiplatform para Desktop e Android.
- `network-security`: `FileNameSanitizer` (anti-Path Traversal), `TransferTokenManager` e limites anti-DoS.
- `integration-testing`: Testes unitários em `commonTest` e suíte de conformidade `TC-FSAP-001` a `TC-FSAP-026`.


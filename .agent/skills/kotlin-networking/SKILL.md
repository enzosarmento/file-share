---
name: kotlin-networking
description: >-
  Use esta skill ao implementar comunicacao de rede com Kotlin Coroutines, Ktor Client CIO,
  gerenciamento de timeouts TCP/UDP, enumeracao de NetworkInterface ou tratamento de
  desconexoes e excecoes de socket no projeto FileShare.
---

# Skill: Kotlin Networking & Coroutines I/O (`kotlin-networking`)

## 1. Nome
`kotlin-networking`

## 2. Descrição
Padrões de implementação de rede assíncrona em Kotlin utilizando Coroutines, Ktor Client (`CIO`), sockets da plataforma JVM/Android, configuração rigorosa de timeouts e tratamento determinístico de falhas de enlace.

## 3. Objetivo
Assegurar que todas as operações de rede TCP e UDP do FileShare sejam não-bloqueantes, respeitem os temporizadores normativos do protocolo FSAP/1.0, tratem quedas de conexão sem vazamento de descritores e selecionem corretamente as interfaces de rede locais.

## 4. Conhecimentos Necessários
- `kotlinx.coroutines` (`Dispatchers.IO`, cancelamento cooperativo, `withTimeout`, `SupervisorJob`).
- Ktor Client (`CIO` engine, `HttpTimeout`, `ContentNegotiation`, streaming com `ByteReadChannel` e `ByteWriteChannel`).
- APIs `java.net.NetworkInterface`, `Inet4Address`, `SocketTimeoutException`, `ConnectException` e `EOFException`.

## 5. Contexto Técnico
- Especificação de temporizadores em [docs/protocol-fsap.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-fsap.md):
  - `T_TCP_CONNECT_TIMEOUT = 5000 ms`
  - `T_APPROVAL_TIMEOUT = 60000 ms`
  - `T_STREAM_READ_TIMEOUT = 15000 ms`
  - `T_SHUTDOWN_GRACE = 2000 ms`
- Catálogo de erros em [docs/error-codes.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/error-codes.md).

## 6. Escopo de Atuação
- Implementação do `FsapHttpClient` e do `ManualPeerConnector` (`IDENTIFY`).
- Filtragem de interfaces de rede físicas IPv4 (excluindo Docker, WSL, VPN e loopback).
- Tradução de exceções de baixo nível (`IOException`, `ConnectException`, `SocketTimeoutException`) para códigos canônicos do FSAP (`FSAP-ERR-503-CONNECTION_LOST`, `FSAP-ERR-408-STREAM_TIMEOUT`, `FSAP-ERR-404-DEVICE_NOT_FOUND`).

## 7. Arquivos e Módulos que Pode Modificar
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/client/**`
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/discovery/ManualPeerConnector.kt`
- `composeApp/src/desktopMain/kotlin/br/edu/fileshare/desktop/network/**`
- `composeApp/src/androidMain/kotlin/br/edu/fileshare/android/network/**`

## 8. Restrições
1. **Proibido I/O sem Timeout:** Nenhuma chamada de socket ou requisição HTTP pode ter timeout infinito.
2. **Respeito ao Cancelamento de Coroutine:** Ao capturar `Throwable` em blocos de rede, **sempre relançar `CancellationException`** (`if (e is CancellationException) throw e`) para preservar a concorrência estruturada.
3. **Fechamento Garantido de Recursos:** Todo socket, canal ou stream deve ser fechado em bloco `use { ... }` ou `finally`.

## 9. Dependências
- `fsap-protocol`
- `kotlin-multiplatform-architecture`

## 10. Critérios de Conclusão
- Quedas abruptas de conexão são detectadas em no máximo 15 s (`T_STREAM_READ_TIMEOUT`).
- Handshake `IDENTIFY` por IP manual funciona mesmo quando o UDP Multicast está desativado (`TC-FSAP-006`).


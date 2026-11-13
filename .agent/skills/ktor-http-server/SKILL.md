---
name: ktor-http-server
description: >-
  Use esta skill ao implementar o servidor HTTP/TCP local do FileShare (Ktor Server CIO),
  suas rotas FSAP (/fsap/v1/...), o executavel independente do Servidor Desktop
  (ServerMainKt) e o gerenciamento concorrente de sessoes de recebimento.
---

# Skill: Ktor HTTP Server & FSAP Receiver (`ktor-http-server`)

## 1. Nome
`ktor-http-server`

## 2. Descrição
Guia técnico para construção do servidor HTTP/1.1 embutido (`ktor-server-cio`) que atua como receptor de transferências FSAP tanto no Servidor Desktop Independente (Headless/CLI) quanto no Cliente Desktop e Android.

## 3. Objetivo
Implementar todas as rotas receptoras do protocolo FSAP/1.0 sobre TCP (porta padrão `53317`) com suporte a múltiplas sessões concorrentes, validação estrita de estado, exigência de `transferToken` e tratamento padronizado de erros.

## 4. Conhecimentos Necessários
- Ktor Server (`CIO`, `Routing`, `ContentNegotiation`, `StatusPages`, leitura de `ByteReadChannel` em streaming).
- Concorrência e sincronização de sessões (`ConcurrentHashMap`, `Mutex`, `withTimeoutOrNull`).
- Ciclo de vida de processo JVM (`Runtime.getRuntime().addShutdownHook`) para o Servidor Desktop Independente.

## 5. Contexto Técnico
- Endpoints normativos especificados em [docs/protocol-messages.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-messages.md):
  - `GET /fsap/v1/info` e `POST /fsap/v1/identify` (`IDENTIFY`)
  - `POST /fsap/v1/transfers/prepare` (`TRANSFER_REQUEST` → `TRANSFER_ACCEPT` / `TRANSFER_REJECT`)
  - `PUT /fsap/v1/transfers/{transferId}/files/{fileId}/content` (`TRANSFER_CONTENT`)
  - `GET /fsap/v1/transfers/{transferId}/status` (`TRANSFER_STATUS`)
  - `POST /fsap/v1/transfers/{transferId}/cancel` (`TRANSFER_CANCEL`)
  - `POST /fsap/v1/transfers/{transferId}/complete` (`TRANSFER_COMPLETE`)

## 6. Escopo de Atuação
- Implementação de `FsapHttpServer`, `ServerTransferSessionManager` e rotas HTTP em `commonMain`.
- Implementação do entrypoint CLI independente `ServerMain.kt` em `desktopMain`.

## 7. Arquivos e Módulos que Pode Modificar
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/server/**`
- `composeApp/src/desktopMain/kotlin/br/edu/fileshare/desktop/server/ServerMain.kt`
- `composeApp/src/desktopTest/kotlin/br/edu/fileshare/conformance/**`

## 8. Restrições
1. **Independência Total da Interface Gráfica:** O servidor HTTP e o `ServerMain.kt` não podem depender de nenhuma classe do Compose Multiplatform. A confirmação do destinatário deve ser solicitada através da interface `TransferApprovalHandler`.
2. **Envelope de Erro Obrigatório:** Toda resposta `4xx` ou `5xx` deve serializar o envelope `ERROR` do FSAP com o respectivo `FsapErrorCode`.
3. **Validação Pré-Stream:** Em `PUT .../content`, o servidor deve validar estado da sessão, `X-FSAP-Transfer-Token`, `Content-Length` e `X-FSAP-File-SHA256` **antes** de criar o arquivo `.part` no disco.

## 9. Dependências
- `fsap-protocol`
- `binary-file-transfer`
- `network-security`

## 10. Critérios de Conclusão
- O Servidor Desktop Independente inicia via `./scripts/run-desktop-server.sh` e processa transferências completas sem interface gráfica.
- Suporte validado para até 5 transferências simultâneas sem corrupção de estado (`TC-FSAP-026`).


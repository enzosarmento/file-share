# Fluxos Completos de Comunicação — Protocolo FSAP/1.0

> **Status:** Especificação Normativa Aprovada (FSAP/1.0 — Seção 5.6)  
> **Escopo:** Diagramas de sequência formais em Mermaid e análise de estados, mensagens, resultados e falhas para os 10 cenários operacionais do FileShare.

---

## 1. Fluxo 1: Descoberta de Dois Dispositivos (Multicast UDP e Variante Manual TCP)

- **Dispositivos Envolvidos:** `Dispositivo A` (recém-iniciado na LAN) e `Dispositivo B` (já ativo na LAN).
- **Mensagens Trocadas:** `DISCOVER` (UDP), `ANNOUNCE` (UDP) — ou `IDENTIFY` (HTTP/TCP) na variante manual.
- **Estados Envolvidos:**
  - `Dispositivo A`: `LocalNodeState: DISCOVERING` → `AVAILABLE`; `PeerConnectionState(B): DISCONNECTED` → `DISCOVERED`.
  - `Dispositivo B`: `LocalNodeState: AVAILABLE`; `PeerConnectionState(A): DISCONNECTED` → `DISCOVERED`.
- **Resultados Esperados:** Ambos os dispositivos passam a constar mutuamente em suas respectivas `PeerRegistry` com IP, `httpPort`, `alias` e `deviceId` atualizados.
- **Possíveis Falhas:** Roteador Wi-Fi com *AP Isolation* bloqueia `239.255.70.83:53318` (mitigado pelo bloco alternativo de Conexão Manual por IP usando `IDENTIFY`).

```mermaid
sequenceDiagram
    autonumber
    participant A as Dispositivo A (Novo Nó)
    participant MC as Grupo Multicast UDP (239.255.70.83:53318)
    participant B as Dispositivo B (Nó Ativo)

    Note over A: LocalNodeState: DISCOVERING
    Note over B: LocalNodeState: AVAILABLE

    A->>MC: UDP DISCOVER (senderId=A, alias="Desktop-A", httpPort=53317)
    MC->>B: Entrega datagrama DISCOVER
    A->>MC: UDP ANNOUNCE (senderId=A, alias="Desktop-A", httpPort=53317)
    MC->>B: Entrega datagrama ANNOUNCE

    Note over B: PeerRegistry registra A -> PeerState(A) = DISCOVERED
    Note over A: LocalNodeState: AVAILABLE

    Note over B: Aguarda jitter aleatório (50..300 ms)
    B->>MC: UDP ANNOUNCE (senderId=B, alias="Android-B", httpPort=53317)
    MC->>A: Entrega datagrama ANNOUNCE

    Note over A: PeerRegistry registra B -> PeerState(B) = DISCOVERED

    alt Variante Fallback: Multicast Bloqueado (Conexão Manual por IP)
        Note over A: Usuário informa IP 192.168.1.50:53317
        Note over A: PeerState(B): DISCONNECTED -> CONNECTING
        A->>B: HTTP POST /fsap/v1/identify (IDENTIFY de A)
        B-->>A: HTTP 200 OK (IDENTIFY de B)
        Note over A: PeerState(B): CONNECTING -> CONNECTED
    end
```

---

## 2. Fluxo 2: Inicialização, Anúncio Periódico de Presença e Expiração por TTL

- **Dispositivos Envolvidos:** `Dispositivo A` (Anunciante) e `Dispositivo B` (Observador).
- **Mensagens Trocadas:** `ANNOUNCE` periódico a cada `5000 ms` (`T_ANNOUNCE_INTERVAL`).
- **Estados Envolvidos:**
  - `Dispositivo A`: `STOPPED` → `INITIALIZING` → `DISCOVERING` → `AVAILABLE`.
  - `Dispositivo B`: `PeerConnectionState(A): DISCONNECTED` → `DISCOVERED` → `STALE` (após 12 s sem anúncio) → `DISCONNECTED` (após 18 s sem anúncio).
- **Resultados Esperados:** Presença mantida ativa enquanto houver batimentos periódicos e remoção automática caso o `Dispositivo A` perca conectividade sem enviar `BYE`.
- **Possíveis Falhas:** `BindException` na porta `53317`/`53318` levando `Dispositivo A` ao estado `FAULTED`.

```mermaid
sequenceDiagram
    autonumber
    participant A as Dispositivo A
    participant B as Dispositivo B (PeerRegistry)

    Note over A: STOPPED -> INITIALIZING (bind TCP 53317 e UDP 53318)
    Note over A: INITIALIZING -> DISCOVERING -> AVAILABLE

    loop A cada T_ANNOUNCE_INTERVAL (5 segundos)
        A->>B: UDP ANNOUNCE (senderId=A, timestamp=t0)
        Note over B: PeerState(A) = DISCOVERED (lastSeenAt = t0)
    end

    Note over A: Queda abrupta de energia / Wi-Fi desligado em A
    Note over B: t0 + 12s sem ANNOUNCE (2 ciclos perdidos)
    Note over B: PeerState(A): DISCOVERED -> STALE

    Note over B: t0 + 18s (T_PEER_TTL atingido no sweep timer)
    Note over B: PeerState(A): STALE -> DISCONNECTED (Removido da UI)
```

---

## 3. Fluxo 3: Envio de Arquivo com Aceitação (Perspectiva Ponta a Ponta)

- **Dispositivos Envolvidos:** `Cliente Remetente (A)` e `Servidor Destinatário (B)`.
- **Mensagens Trocadas:** `TRANSFER_REQUEST`, `TRANSFER_ACCEPT`, `TRANSFER_CONTENT`, `TRANSFER_STATUS`, `TRANSFER_COMPLETE`.
- **Estados Envolvidos (`TransferState`):** `CREATED` → `PENDING_APPROVAL` → `ACCEPTED` → `TRANSFERRING` → `VERIFYING` → `COMPLETED`.
- **Resultados Esperados:** Arquivo transmitido integralmente em chunks de 64 KB, validado por SHA-256 e confirmado por ambas as partes.
- **Possíveis Falhas:** Timeout de aprovação (`60 s`), token expirado (`30 s`) ou queda de conexão TCP.

```mermaid
sequenceDiagram
    autonumber
    actor UserA as Usuário Remetente
    participant A as Cliente FSAP (A)
    participant B as Servidor FSAP (B)
    actor UserB as Usuário Destinatário

    UserA->>A: Seleciona arquivo(s) e escolhe Dispositivo B
    Note over A: TransferState: CREATED (calcula SHA-256 e sizeBytes)

    A->>B: HTTP POST /fsap/v1/transfers/prepare (TRANSFER_REQUEST)
    Note over A,B: TransferState: CREATED -> PENDING_APPROVAL

    B->>UserB: Exibe diálogo de confirmação (Remetente, Nome, Tamanho)
    UserB->>B: Clica em "Aceitar"

    Note over B: Gera transferToken efêmero
    B-->>A: HTTP 200 OK (TRANSFER_ACCEPT com transferToken)
    Note over A,B: TransferState: PENDING_APPROVAL -> ACCEPTED

    A->>B: HTTP PUT /fsap/v1/transfers/{id}/files/{fileId}/content (Headers + Stream)
    Note over A,B: TransferState: ACCEPTED -> TRANSFERRING

    loop Streaming de blocos de 64 KB
        A->>B: Chunk binário (application/octet-stream)
        Note over A,B: Atualiza progresso local (EV-15)
    end

    Note over B: TransferState: TRANSFERRING -> VERIFYING (compara SHA-256)
    B-->>A: HTTP 200 OK (TRANSFER_STATUS: fileVerified=true)

    A->>B: HTTP POST /fsap/v1/transfers/{id}/complete (TRANSFER_COMPLETE)
    B-->>A: HTTP 200 OK (TRANSFER_COMPLETE: state=COMPLETED)
    Note over A,B: TransferState: VERIFYING -> COMPLETED
```

---

## 4. Fluxo 4: Recebimento de Arquivo (Perspectiva Interna do Servidor e Armazenamento Seguro)

- **Dispositivos Envolvidos:** `Cliente Remetente (A)`, `Servidor FSAP Receptor (B)` e `Subsistema de Arquivos / Hashing (B)`.
- **Mensagens Trocadas:** `TRANSFER_CONTENT`, `TRANSFER_STATUS`, `TRANSFER_COMPLETE`.
- **Estados Envolvidos (`TransferState` no Destinatário):** `ACCEPTED` → `TRANSFERRING` → `VERIFYING` → `COMPLETED`.
- **Resultados Esperados:** Os dados são gravados exclusivamente em arquivo `.part` durante a recepção, o hash SHA-256 é calculado incrementalmente sem releitura do disco, e o arquivo final só é exposto ao usuário após confirmação do digest.
- **Possíveis Falhas:** `FSAP-ERR-507-INSUFFICIENT_STORAGE`, `FSAP-ERR-422-HASH_MISMATCH`.

```mermaid
sequenceDiagram
    autonumber
    participant A as Cliente Remetente (A)
    participant Srv as Servidor HTTP FSAP (B)
    participant Hash as IncrementalSha256 (B)
    participant Disk as FileSink / Armazenamento (B)

    A->>Srv: PUT /fsap/v1/transfers/{id}/files/{fileId}/content
    Note over Srv: Valida X-FSAP-Transfer-Token e Content-Length
    Note over Srv: TransferState: ACCEPTED -> TRANSFERRING

    Srv->>Disk: Cria arquivo temporário "<transferId>_<fileId>.part"
    Srv->>Hash: Inicializa MessageDigest.getInstance("SHA-256")

    loop Enquanto bytesReceived < sizeBytes (blocos de 64 KB)
        A->>Srv: Transmite chunk de até 65536 bytes
        Srv->>Hash: digest.update(chunk, 0, bytesRead)
        Srv->>Disk: Escreve chunk em "<transferId>_<fileId>.part"
        Note over Srv: Incrementa bytesReceived e notifica UI do receptor
    end

    Note over Srv: bytesReceived == sizeBytes -> TransferState: VERIFYING
    Srv->>Hash: Finaliza digest.digest() -> hexString (64 chars)

    alt Hash calculado == X-FSAP-File-SHA256 esperado
        Srv->>Disk: Move/renomeia atomicamente ".part" para arquivo final
        Srv-->>A: HTTP 200 OK (TRANSFER_STATUS: fileVerified=true)
        A->>Srv: POST /fsap/v1/transfers/{id}/complete (TRANSFER_COMPLETE)
        Note over Srv: TransferState: VERIFYING -> COMPLETED
        Srv-->>A: HTTP 200 OK (TRANSFER_COMPLETE)
    else Hash divergente
        Srv->>Disk: Deleta imediatamente "<transferId>_<fileId>.part"
        Note over Srv: TransferState: VERIFYING -> FAILED
        Srv-->>A: HTTP 422 Unprocessable Entity (ERROR: FSAP-ERR-422-HASH_MISMATCH)
    end
```

---

## 5. Fluxo 5: Recusa de Transferência pelo Destinatário

- **Dispositivos Envolvidos:** `Cliente Remetente (A)` e `Servidor Destinatário (B)`.
- **Mensagens Trocadas:** `TRANSFER_REQUEST`, `TRANSFER_REJECT`.
- **Estados Envolvidos (`TransferState`):** `CREATED` → `PENDING_APPROVAL` → `REJECTED`.
- **Resultados Esperados:** Nenhum byte de conteúdo de arquivo é transmitido na rede; nenhum arquivo `.part` é criado no disco do destinatário; o remetente é informado imediatamente da recusa.
- **Possíveis Falhas:** Usuário destinatário não interagir em 60 s (resultando em `FAILED` por `FSAP-ERR-408-APPROVAL_TIMEOUT` em vez de `REJECTED`).

```mermaid
sequenceDiagram
    autonumber
    participant A as Cliente Remetente (A)
    participant B as Servidor Destinatário (B)
    actor UserB as Usuário Destinatário

    Note over A: TransferState: CREATED
    A->>B: HTTP POST /fsap/v1/transfers/prepare (TRANSFER_REQUEST)
    Note over A,B: TransferState: PENDING_APPROVAL

    B->>UserB: Exibe modal de confirmação de recebimento
    UserB->>B: Clica em "Recusar"

    Note over B: TransferState: PENDING_APPROVAL -> REJECTED
    B-->>A: HTTP 403 Forbidden (TRANSFER_REJECT: reasonCode="USER_DECLINED")
    Note over A: TransferState: PENDING_APPROVAL -> REJECTED
    Note over A: Exibe aviso "Transferência recusada pelo destinatário"
```

---

## 6. Fluxo 6: Cancelamento de Transferência em Andamento

- **Dispositivos Envolvidos:** `Cliente Remetente (A)` e `Servidor Destinatário (B)`.
- **Mensagens Trocadas:** `TRANSFER_CONTENT` (interrompido) e `TRANSFER_CANCEL`.
- **Estados Envolvidos (`TransferState`):** `TRANSFERRING` → `CANCELLED` (ou `PENDING_APPROVAL` → `CANCELLED`).
- **Resultados Esperados:** Interrupção imediata do envio de chunks, fechamento limpo do canal e exclusão obrigatória do arquivo `.part` no destinatário.
- **Possíveis Falhas:** Perda de conectividade simultânea ao envio do `TRANSFER_CANCEL` (tratada pelo bloco `finally` que limpa o `.part` em ambos os casos).

```mermaid
sequenceDiagram
    autonumber
    actor User as Usuário (A ou B)
    participant A as Cliente Remetente (A)
    participant B as Servidor Destinatário (B)
    participant DiskB as Armazenamento (B)

    Note over A,B: TransferState: TRANSFERRING (enviando chunks de 64 KB)
    A->>B: Stream PUT /fsap/v1/transfers/{id}/files/{fileId}/content (em curso)

    alt Caso 6A: Cancelamento iniciado pelo Remetente (A)
        User->>A: Clica em "Cancelar Transferência"
        Note over A: Cancela coroutine de leitura do arquivo e fecha stream PUT
        A->>B: HTTP POST /fsap/v1/transfers/{id}/cancel (TRANSFER_CANCEL: cancelledBy="SENDER")
        B->>DiskB: Exclui arquivo temporário "<transferId>_<fileId>.part"
        Note over A,B: TransferState: TRANSFERRING -> CANCELLED
        B-->>A: HTTP 200 OK (TRANSFER_STATUS: state="CANCELLED")
    else Caso 6B: Cancelamento iniciado pelo Destinatário (B)
        User->>B: Clica em "Cancelar Recebimento"
        Note over B: Interrompe leitura do canal TCP
        B->>DiskB: Exclui arquivo temporário "<transferId>_<fileId>.part"
        Note over B: TransferState: TRANSFERRING -> CANCELLED
        B-->>A: HTTP 200/499 (TRANSFER_CANCEL: cancelledBy="RECEIVER")
        Note over A: TransferState: TRANSFERRING -> CANCELLED
    end
```

---

## 7. Fluxo 7: Falha Durante o Envio (Erro de Armazenamento / Falta de Espaço)

- **Dispositivos Envolvidos:** `Cliente Remetente (A)` e `Servidor Destinatário (B)`.
- **Mensagens Trocadas:** `TRANSFER_CONTENT`, `ERROR` (`FSAP-ERR-507-INSUFFICIENT_STORAGE` ou `FSAP-ERR-500-STORAGE_IO_ERROR`).
- **Estados Envolvidos (`TransferState`):** `TRANSFERRING` → `FAILED`.
- **Resultados Esperados:** Quando o disco do destinatário fica cheio ou sofre erro de escrita durante o streaming, o servidor interrompe imediatamente a recepção, remove o arquivo `.part` parcial e devolve o envelope padronizado `ERROR`.
- **Possíveis Falhas:** Falha secundária ao tentar apagar o `.part` (registrada em log de erro crítico).

```mermaid
sequenceDiagram
    autonumber
    participant A as Cliente Remetente (A)
    participant B as Servidor Destinatário (B)
    participant DiskB as Armazenamento (B)

    Note over A,B: TransferState: TRANSFERRING
    A->>B: PUT /fsap/v1/transfers/{id}/files/{fileId}/content (Chunk N)
    B->>DiskB: write(chunk)
    DiskB-->>B: Lança IOException (No space left on device)

    B->>DiskB: deleteIfExists("<transferId>_<fileId>.part")
    Note over B: TransferState: TRANSFERRING -> FAILED

    B-->>A: HTTP 507 Insufficient Storage (ERROR: FSAP-ERR-507-INSUFFICIENT_STORAGE)
    Note over A: Interrompe envio do stream
    Note over A: TransferState: TRANSFERRING -> FAILED
```

---

## 8. Fluxo 8: Desconexão Inesperada no Meio da Transferência

- **Dispositivos Envolvidos:** `Cliente Remetente (A)` e `Servidor Destinatário (B)`.
- **Mensagens Trocadas:** Interrupção abrupta do stream TCP (`RST` ou timeout de leitura de `15 s` `T_STREAM_READ_TIMEOUT`).
- **Estados Envolvidos:**
  - `TransferState`: `TRANSFERRING` → `FAILED`.
  - `PeerConnectionState`: `CONNECTED` → `DISCONNECTED`.
- **Resultados Esperados:** Nenhuma coroutine fica travada indefinidamente; ambos os lados detectam a perda de conexão em no máximo `15 s` (`T_STREAM_READ_TIMEOUT`), o destinatário apaga o arquivo `.part` órfão e a UI informa a queda de conexão (`FSAP-ERR-503-CONNECTION_LOST` / `FSAP-ERR-408-STREAM_TIMEOUT`).

```mermaid
sequenceDiagram
    autonumber
    participant A as Cliente Remetente (A)
    participant B as Servidor Destinatário (B)
    participant DiskB as Armazenamento (B)

    Note over A,B: TransferState: TRANSFERRING (50% concluído)
    A->>B: Chunk de 64 KB

    Note over A,B: X--- Queda abrupta do Wi-Fi / Perda de enlace de rede ---X

    par Detecção no Remetente (A)
        Note over A: Socket write falha com IOException / SocketTimeoutException
        Note over A: TransferState: TRANSFERRING -> FAILED (FSAP-ERR-503-CONNECTION_LOST)
        Note over A: PeerState(B): CONNECTED -> DISCONNECTED
    and Detecção no Destinatário (B)
        Note over B: Nenhum byte recebido por T_STREAM_READ_TIMEOUT (15s) ou EOF prematuro
        B->>DiskB: Bloco finally remove "<transferId>_<fileId>.part"
        Note over B: TransferState: TRANSFERRING -> FAILED (FSAP-ERR-408-STREAM_TIMEOUT)
        Note over B: PeerState(A): CONNECTED -> DISCONNECTED
    end
```

---

## 9. Fluxo 9: Verificação de Integridade SHA-256 (Sucesso vs. Corrupção de Dados)

- **Dispositivos Envolvidos:** `Cliente Remetente (A)` e `Servidor Destinatário (B)`.
- **Mensagens Trocadas:** `TRANSFER_CONTENT`, `TRANSFER_STATUS`, `TRANSFER_COMPLETE` ou `ERROR` (`FSAP-ERR-422-HASH_MISMATCH`).
- **Estados Envolvidos (`TransferState`):** `TRANSFERRING` → `VERIFYING` → `COMPLETED` (se íntegro) ou `FAILED` (se corrompido).
- **Resultados Esperados:** Garantia matemática de que o arquivo salvo no disco do destinatário é bit a bit idêntico ao arquivo original do remetente.

```mermaid
sequenceDiagram
    autonumber
    participant A as Cliente Remetente (A)
    participant B as Servidor Destinatário (B)

    Note over A,B: Último chunk do arquivo enviado (bytesReceived == sizeBytes)
    A->>B: Último bloco de bytes do PUT /content
    Note over B: TransferState: TRANSFERRING -> VERIFYING
    Note over B: Calcula digest final SHA-256 dos bytes recebidos

    alt Caso 9A: calculatedSha256 == expectedSha256 (Integridade Confirmada)
        Note over B: Renomeia ".part" para arquivo definitivo
        B-->>A: HTTP 200 OK (TRANSFER_STATUS: fileVerified=true)
        A->>B: POST /fsap/v1/transfers/{id}/complete (TRANSFER_COMPLETE)
        Note over A,B: TransferState: VERIFYING -> COMPLETED
        B-->>A: HTTP 200 OK (TRANSFER_COMPLETE: state="COMPLETED")
    else Caso 9B: calculatedSha256 != expectedSha256 (Corrupção Detectada)
        Note over B: Exclui imediatamente arquivo ".part" corrompido
        Note over B: TransferState: VERIFYING -> FAILED
        B-->>A: HTTP 422 Unprocessable Entity (ERROR: FSAP-ERR-422-HASH_MISMATCH)
        Note over A: TransferState: VERIFYING -> FAILED
    end
```

---

## 10. Fluxo 10: Encerramento Normal da Aplicação (Graceful Shutdown)

- **Dispositivos Envolvidos:** `Nó em Encerramento (A)`, `Par com Transferência Ativa (B)` e `Grupo Multicast UDP`.
- **Mensagens Trocadas:** `TRANSFER_CANCEL` (se houver sessão ativa) e `BYE` (UDP Multicast).
- **Estados Envolvidos:**
  - `LocalNodeState(A)`: `AVAILABLE` → `SHUTTING_DOWN` → `STOPPED`.
  - `TransferState`: `TRANSFERRING` → `CANCELLED`.
  - `PeerConnectionState(A)` nos vizinhos: `DISCOVERED` → `DISCONNECTED`.
- **Resultados Esperados:** Cancelamento limpo de sessões em curso, remoção de arquivos `.part`, notificação imediata de saída via `BYE` e liberação de portas TCP/UDP em até `2000 ms` (`T_SHUTDOWN_GRACE`).

```mermaid
sequenceDiagram
    autonumber
    actor UserA as Usuário (A)
    participant A as Nó Local (A)
    participant B as Par com Sessão Ativa (B)
    participant MC as Grupo Multicast UDP (239.255.70.83:53318)

    UserA->>A: Solicita fechar o aplicativo (ou SIGTERM no Servidor Desktop)
    Note over A: LocalNodeState: AVAILABLE -> SHUTTING_DOWN
    Note over A: Para o timer periódico T_ANNOUNCE_INTERVAL

    opt Existem transferências ativas (PENDING_APPROVAL ou TRANSFERRING)
        A->>B: HTTP POST /fsap/v1/transfers/{id}/cancel (TRANSFER_CANCEL: reason="APP_SHUTDOWN")
        Note over A,B: TransferState -> CANCELLED (arquivos .part removidos)
    end

    A->>MC: UDP BYE (senderId=A, reason="USER_SHUTDOWN")
    MC->>B: Entrega datagrama BYE
    Note over B: PeerRegistry remove A imediatamente -> PeerState(A) = DISCONNECTED

    Note over A: Fecha Servidor HTTP (53317), Fecha MulticastSocket (53318), libera MulticastLock
    Note over A: LocalNodeState: SHUTTING_DOWN -> STOPPED
```


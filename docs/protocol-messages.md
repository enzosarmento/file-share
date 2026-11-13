# Catálogo Oficial de Mensagens — Protocolo FSAP/1.0

> **Status:** Especificação Normativa Aprovada (FSAP/1.0 — Seções 5.4 e 5.5)  
> **Escopo:** Definição semântica, sintática, regras de validação, estados associados e exemplos de payloads válidos e inválidos para todas as 12 mensagens do protocolo FSAP/1.0.

---

## 1. Envelope Comum para Mensagens JSON

Todas as mensagens baseadas em texto do protocolo FSAP (tanto no transporte UDP quanto no transporte HTTP/TCP, exceto o corpo binário bruto de `TRANSFER_CONTENT`) utilizam o **Envelope Padrão FSAP** codificado em **JSON (UTF-8)**:

| Campo do Envelope | Obrigatório | Tipo | Regras de Validação |
| :--- | :---: | :--- | :--- |
| `protocol` | Sim | `String` | Deve ser exatamente `"FSAP"`. |
| `version` | Sim | `String` | Formato `MAJOR.MINOR` (`^\d+\.\d+$`). Na versão atual: `"1.0"`. |
| `type` | Sim | `String` | Identificador canônico da mensagem (`"ANNOUNCE"`, `"DISCOVER"`, `"BYE"`, `"IDENTIFY"`, `"TRANSFER_REQUEST"`, `"TRANSFER_ACCEPT"`, `"TRANSFER_REJECT"`, `"TRANSFER_STATUS"`, `"TRANSFER_CANCEL"`, `"TRANSFER_COMPLETE"`, `"ERROR"`). |
| `messageId` | Sim | `String` | UUID v4 único por mensagem emitida (`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$`). |
| `timestamp` | Sim | `String` | Instante de emissão em ISO-8601 UTC (ex: `"2026-11-12T20:15:30Z"`). |
| `senderId` | Sim | `String` | UUID v4 que identifica univocamente o dispositivo emissor (`deviceId`). |
| `payload` | Sim | `Object` | Objeto JSON contendo os campos específicos da mensagem conforme especificado nas seções 2 e 3. |

---

## 2. Mensagens do Plano de Descoberta (UDP Multicast — `239.255.70.83:53318`)

### 2.1. `ANNOUNCE` — Anúncio de Presença e Capacidade

- **Nome e Identificador:** `ANNOUNCE`
- **Significado Semântico e Objetivo:** Declara à sub-rede local que o dispositivo emissor está ativo, disponível para conexões FSAP e escutando requisições TCP na porta informada. Serve tanto como batimento cardíaco periódico (*heartbeat* a cada 5 s) quanto como resposta a um `DISCOVER`.
- **Emissor e Receptor:**
  - *Emissor:* Qualquer nó FSAP nos estados `DISCOVERING` ou `AVAILABLE`.
  - *Receptor:* Todos os nós FSAP inscritos no grupo multicast `239.255.70.83:53318` (ou o emissor de um `DISCOVER` via unicast/multicast).
- **Transporte Utilizado:** Datagrama UDP (`239.255.70.83`, porta `53318`), tamanho máximo `1024 bytes`.
- **Formato:** JSON (UTF-8) dentro do Envelope Padrão FSAP.
- **Campos do `payload`:**

| Campo | Obrigatório | Tipo | Regras de Validação |
| :--- | :---: | :--- | :--- |
| `alias` | Sim | `String` | `1..64` caracteres UTF-8 imprimíveis (após `trim()`). Não pode conter `\n`, `\r` ou `\u0000`. |
| `deviceType` | Sim | `String` | Enum: `"DESKTOP"`, `"MOBILE"`, `"TABLET"`, `"SERVER"`. |
| `platform` | Sim | `String` | Enum: `"LINUX"`, `"WINDOWS"`, `"ANDROID"`. |
| `httpPort` | Sim | `Int` | Inteiro no intervalo `1024..65535` (padrão `53317`). |
| `capabilities` | Não | `Array<String>` | Lista opcional de capacidades suportadas (ex: `["MULTI_FILE", "SHA256_STREAM"]`). |

- **Resposta Esperada:** Nenhuma resposta de rede (operação *fire-and-forget* unidirecional). O receptor atualiza silenciosamente sua `PeerRegistry`.
- **Possíveis Erros:** Datagrama inválido/corrompido é descartado silenciosamente pelo receptor com log local `WARN`.
- **Estados Associados:**
  - *No Emissor:* `LocalNodeState: DISCOVERING` → `AVAILABLE` (ou permanece em `AVAILABLE`).
  - *No Receptor:* `PeerConnectionState: DISCONNECTED` → `DISCOVERED` (ou renova `DISCOVERED`).

---

### 2.2. `DISCOVER` — Solicitação Ativa de Descoberta Imediata

- **Nome e Identificador:** `DISCOVER`
- **Significado Semântico e Objetivo:** Solicita que todos os nós FSAP ativos na sub-rede local emitam imediatamente uma mensagem `ANNOUNCE` (com jitter de `50..300 ms`), acelerando a descoberta logo após a inicialização ou quando o usuário aciona "Atualizar lista".
- **Emissor e Receptor:**
  - *Emissor:* Nó que acabou de iniciar (`DISCOVERING`) ou solicitou refresh.
  - *Receptor:* Todos os nós FSAP no estado `AVAILABLE` escutando `239.255.70.83:53318`.
- **Transporte Utilizado:** Datagrama UDP (`239.255.70.83`, porta `53318`).
- **Formato:** JSON (UTF-8) dentro do Envelope Padrão FSAP.
- **Campos do `payload`:**

| Campo | Obrigatório | Tipo | Regras de Validação |
| :--- | :---: | :--- | :--- |
| `requesterAlias` | Sim | `String` | `1..64` caracteres UTF-8. |
| `requesterHttpPort` | Sim | `Int` | `1024..65535`. |

- **Resposta Esperada:** Cada receptor válido agenda o envio de um datagrama `ANNOUNCE` para o grupo multicast após um atraso aleatório de `50..300 ms`.
- **Possíveis Erros:** Descarte silencioso caso o envelope seja inválido.
- **Estados Associados:** `LocalNodeState: DISCOVERING`.

---

### 2.3. `BYE` — Notificação Graciosa de Saída da Rede

- **Nome e Identificador:** `BYE`
- **Significado Semântico e Objetivo:** Notifica aos pares da rede local que o nó emissor está encerrando suas atividades normalmente, permitindo que o removam imediatamente da lista de dispositivos disponíveis sem aguardar a expiração do TTL de 18 segundos.
- **Emissor e Receptor:**
  - *Emissor:* Nó local transitando para `SHUTTING_DOWN`.
  - *Receptor:* Todos os nós no grupo multicast `239.255.70.83:53318`.
- **Transporte Utilizado:** Datagrama UDP (`239.255.70.83`, porta `53318`).
- **Formato:** JSON (UTF-8) dentro do Envelope Padrão FSAP.
- **Campos do `payload`:**

| Campo | Obrigatório | Tipo | Regras de Validação |
| :--- | :---: | :--- | :--- |
| `reason` | Não | `String` | Motivo informativo (ex: `"USER_SHUTDOWN"`, `"SERVICE_STOPPED"`), máx. 64 caracteres. |

- **Resposta Esperada:** Nenhuma. Os receptores removem o `senderId` da `PeerRegistry`.
- **Possíveis Erros:** Descarte silencioso se malformado.
- **Estados Associados:**
  - *No Emissor:* `LocalNodeState: SHUTTING_DOWN` → `STOPPED`.
  - *No Receptor:* `PeerConnectionState: DISCOVERED` / `STALE` → `DISCONNECTED`.

---

## 3. Mensagens do Plano de Controle e Transferência (HTTP/1.1 sobre TCP)

Todas as requisições HTTP/TCP usam a porta anunciada pelo destinatário (padrão `53317`) e devem incluir o cabeçalho `X-FSAP-Version: 1.0`.

### 3.1. `IDENTIFY` — Identificação Direta e Handshake de Conexão Manual

- **Nome e Identificador:** `IDENTIFY`
- **Significado Semântico e Objetivo:** Permite a um cliente verificar a disponibilidade TCP, a identidade completa e a compatibilidade de versão de um nó receptor. É o mecanismo principal da **Conexão Manual por IP** (quando o UDP Multicast é bloqueado pela rede) e também pode ser usado como verificação pré-envio.
- **Emissor e Receptor:** Cliente (Remetente) → Servidor (Destinatário).
- **Transporte e Endpoint HTTP:**
  - `POST /fsap/v1/identify` (enviando a identidade do cliente no corpo e recebendo a identidade do servidor na resposta `200 OK`), **ou** `GET /fsap/v1/info` (consulta rápida de leitura).
- **Formato:** JSON (`application/json; charset=utf-8`).
- **Campos do `payload` (Requisição e Resposta):**

| Campo | Obrigatório | Tipo | Regras de Validação |
| :--- | :---: | :--- | :--- |
| `alias` | Sim | `String` | `1..64` caracteres UTF-8. |
| `deviceType` | Sim | `String` | `"DESKTOP"` \| `"MOBILE"` \| `"TABLET"` \| `"SERVER"`. |
| `platform` | Sim | `String` | `"LINUX"` \| `"WINDOWS"` \| `"ANDROID"`. |
| `httpPort` | Sim | `Int` | `1024..65535`. |
| `capabilities` | Não | `Array<String>` | Capacidades suportadas pelo nó. |

- **Resposta Esperada:** HTTP `200 OK` contendo um envelope FSAP `type: "IDENTIFY"` com os dados do servidor receptor.
- **Possíveis Erros:**
  - `400 Bad Request` (`FSAP-ERR-400-MALFORMED_MESSAGE` / `FSAP-ERR-400-VALIDATION_FAILED`)
  - `505 HTTP Version Not Supported` (`FSAP-ERR-505-UNSUPPORTED_VERSION`)
- **Estados Associados:** `PeerConnectionState: DISCONNECTED` / `DISCOVERED` → `CONNECTING` → `CONNECTED`.

---

### 3.2. `TRANSFER_REQUEST` — Solicitação e Manifesto de Transferência

- **Nome e Identificador:** `TRANSFER_REQUEST`
- **Significado Semântico e Objetivo:** Propõe formalmente uma sessão de transferência de um ou mais arquivos do remetente para o destinatário. Transporta o manifesto completo (nomes, tamanhos em bytes, tipos MIME e hashes SHA-256 calculados previamente) para que o usuário destinatário tome uma decisão informada antes de qualquer tráfego binário.
- **Emissor e Receptor:** Cliente (Remetente) → Servidor (Destinatário).
- **Transporte e Endpoint HTTP:** `POST /fsap/v1/transfers/prepare`
- **Formato:** JSON (`application/json; charset=utf-8`).
- **Campos do `payload`:**

| Campo | Obrigatório | Tipo | Regras de Validação |
| :--- | :---: | :--- | :--- |
| `transferId` | Sim | `String` | UUID v4 único da sessão de transferência gerada pelo remetente. |
| `senderAlias` | Sim | `String` | `1..64` caracteres UTF-8. |
| `senderDeviceType` | Sim | `String` | `"DESKTOP"` \| `"MOBILE"` \| `"TABLET"` \| `"SERVER"`. |
| `totalBytes` | Sim | `Long` | Soma exata dos `sizeBytes` de todos os itens em `files` (`>= 0`). |
| `files` | Sim | `Array<FileItem>` | Lista não vazia (`1..100` arquivos por sessão). |
| `files[].fileId` | Sim | `String` | Identificador único do arquivo dentro do lote (UUID v4 ou índice `"file-001"`). |
| `files[].fileName` | Sim | `String` | Nome base do arquivo (`1..255` caracteres). **Proibido** conter `/`, `\`, `..` ou `\u0000`. |
| `files[].sizeBytes` | Sim | `Long` | Tamanho exato em bytes (`0 .. 68719476736` [64 GiB]). |
| `files[].sha256` | Sim | `String` | Hash SHA-256 em hexadecimal minúsculo de exatos 64 caracteres (`^[0-9a-f]{64}$`). |
| `files[].mimeType` | Não | `String` | Tipo MIME informativo (ex: `"application/pdf"`, `"image/png"`), padrão `"application/octet-stream"`. |

- **Resposta Esperada:**
  - HTTP `200 OK` com mensagem `TRANSFER_ACCEPT` (se aceito pelo destinatário dentro de `T_APPROVAL_TIMEOUT` = 60 s); ou
  - HTTP `403 Forbidden` com mensagem `TRANSFER_REJECT` (se recusado pelo destinatário).
- **Possíveis Erros:**
  - `400 Bad Request` (`FSAP-ERR-400-INVALID_FILENAME`, `FSAP-ERR-400-VALIDATION_FAILED`)
  - `408 Request Timeout` (`FSAP-ERR-408-APPROVAL_TIMEOUT`)
  - `413 Payload Too Large` (`FSAP-ERR-413-FILE_TOO_LARGE`)
  - `507 Insufficient Storage` (`FSAP-ERR-507-INSUFFICIENT_STORAGE`)
- **Estados Associados:** `TransferState: CREATED` → `PENDING_APPROVAL`.

---

### 3.3. `TRANSFER_ACCEPT` — Aceitação e Autorização da Transferência

- **Nome e Identificador:** `TRANSFER_ACCEPT`
- **Significado Semântico e Objetivo:** Confirma que o usuário destinatário aprovou explicitamente a solicitação `TRANSFER_REQUEST` e concede ao remetente uma credencial efêmera (`transferToken`) autorizando o upload dos arquivos listados.
- **Emissor e Receptor:** Servidor (Destinatário) → Cliente (Remetente).
- **Transporte e Endpoint HTTP:** Corpo da resposta HTTP `200 OK` do endpoint `POST /fsap/v1/transfers/prepare`.
- **Formato:** JSON (`application/json; charset=utf-8`).
- **Campos do `payload`:**

| Campo | Obrigatório | Tipo | Regras de Validação |
| :--- | :---: | :--- | :--- |
| `transferId` | Sim | `String` | Mesmo UUID v4 recebido no `TRANSFER_REQUEST`. |
| `transferToken` | Sim | `String` | Token opaco criptograficamente aleatório (`32..128` caracteres hex/base64url) vinculado à sessão. |
| `acceptedFileIds` | Sim | `Array<String>` | Lista não vazia de `fileId`s autorizados para envio. |
| `chunkSizeBytes` | Não | `Int` | Tamanho de bloco recomendado pelo receptor (padrão `65536` = 64 KB). |

- **Resposta Esperada:** O cliente inicia o envio de `TRANSFER_CONTENT` para cada arquivo em `acceptedFileIds` em até `30 s` (`T_TOKEN_IDLE_TTL`).
- **Possíveis Erros:** Não se aplica (é uma resposta de sucesso).
- **Estados Associados:** `TransferState: PENDING_APPROVAL` → `ACCEPTED`.

---

### 3.4. `TRANSFER_REJECT` — Recusa Explícita de Transferência

- **Nome e Identificador:** `TRANSFER_REJECT`
- **Significado Semântico e Objetivo:** Informa ao remetente que o usuário destinatário (ou a política local do receptor) recusou a solicitação de transferência. Encerra imediatamente a sessão sem criação de arquivos temporários.
- **Emissor e Receptor:** Servidor (Destinatário) → Cliente (Remetente).
- **Transporte e Endpoint HTTP:** Corpo da resposta HTTP `403 Forbidden` do endpoint `POST /fsap/v1/transfers/prepare`.
- **Formato:** JSON (`application/json; charset=utf-8`).
- **Campos do `payload`:**

| Campo | Obrigatório | Tipo | Regras de Validação |
| :--- | :---: | :--- | :--- |
| `transferId` | Sim | `String` | UUID v4 da sessão recusada. |
| `reasonCode` | Sim | `String` | `"USER_DECLINED"` \| `"BUSY"` \| `"POLICY_BLOCKED"`. |
| `reasonMessage` | Não | `String` | Mensagem legível opcional (máx. 256 caracteres). |

- **Resposta Esperada:** O cliente fecha a sessão local e exibe o motivo da recusa na interface.
- **Estados Associados:** `TransferState: PENDING_APPROVAL` → `REJECTED`.

---

### 3.5. `TRANSFER_CONTENT` — Transmissão de Conteúdo Binário em Streaming

- **Nome e Identificador:** `TRANSFER_CONTENT`
- **Significado Semântico e Objetivo:** Transmite o fluxo binário bruto de um arquivo específico previamente autorizado na sessão `transferId`. Os bytes trafegam diretamente no corpo da requisição HTTP sem codificação textual, permitindo leitura e escrita em chunks de 64 KB e cálculo simultâneo do hash SHA-256.
- **Emissor e Receptor:** Cliente (Remetente) → Servidor (Destinatário).
- **Transporte e Endpoint HTTP:** `PUT /fsap/v1/transfers/{transferId}/files/{fileId}/content`
- **Formato:**
  - **Cabeçalhos HTTP Obrigatórios:**
    - `Content-Type: application/octet-stream`
    - `Content-Length: <sizeBytes exato do arquivo>`
    - `X-FSAP-Version: 1.0`
    - `X-FSAP-Sender-Id: <UUIDv4 do remetente>`
    - `X-FSAP-Transfer-Token: <transferToken recebido em TRANSFER_ACCEPT>`
    - `X-FSAP-File-SHA256: <hash SHA-256 de 64 caracteres hex minúsculo>`
  - **Corpo da Requisição:** Stream de bytes brutos de tamanho igual a `Content-Length`.
- **Regras de Validação no Servidor:**
  1. Verificar se `transferId` existe e está no estado `ACCEPTED` ou `TRANSFERRING`.
  2. Validar se `X-FSAP-Transfer-Token` corresponde ao token da sessão (`401 Unauthorized` se inválido).
  3. Validar se `fileId` pertence ao manifesto aceito e ainda não foi transferido.
  4. Validar se `Content-Length` e `X-FSAP-File-SHA256` coincidem exatamente com os metadados aprovados no `TRANSFER_REQUEST`.
  5. Ao terminar a leitura de todos os bytes (`VERIFYING`), comparar o digest SHA-256 calculado em tempo real com `X-FSAP-File-SHA256`.
- **Resposta Esperada (por arquivo concluído):**
  - HTTP `200 OK` com envelope JSON confirmando o recebimento e a verificação de integridade daquele arquivo:
    ```json
    {
      "protocol": "FSAP",
      "version": "1.0",
      "type": "TRANSFER_STATUS",
      "messageId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
      "timestamp": "2026-11-12T20:16:10Z",
      "senderId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
      "payload": {
        "transferId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        "fileId": "file-001",
        "state": "VERIFYING",
        "fileVerified": true,
        "bytesReceived": 10485760,
        "calculatedSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
      }
    }
    ```
- **Possíveis Erros:**
  - `400 Bad Request` (`FSAP-ERR-400-SIZE_MISMATCH`)
  - `401 Unauthorized` (`FSAP-ERR-401-UNAUTHORIZED_TRANSFER`)
  - `404 Not Found` (`FSAP-ERR-404-TRANSFER_NOT_FOUND`)
  - `408 Request Timeout` (`FSAP-ERR-408-STREAM_TIMEOUT`)
  - `409 Conflict` (`FSAP-ERR-409-INVALID_STATE_TRANSITION`)
  - `422 Unprocessable Entity` (`FSAP-ERR-422-HASH_MISMATCH`)
  - `507 Insufficient Storage` (`FSAP-ERR-507-INSUFFICIENT_STORAGE`)
- **Estados Associados:** `TransferState: ACCEPTED` → `TRANSFERRING` → `VERIFYING`.

---

### 3.6. `TRANSFER_STATUS` — Consulta e Relatório de Progresso da Transferência

- **Nome e Identificador:** `TRANSFER_STATUS`
- **Significado Semântico e Objetivo:** Relata o estado atual da máquina de estados da transferência, a quantidade de bytes já recebidos pelo servidor e o status de verificação de cada arquivo.
- **Emissor e Receptor:**
  - Requisição `GET /fsap/v1/transfers/{transferId}/status` pelo Cliente → Resposta `200 OK` pelo Servidor.
- **Transporte e Endpoint HTTP:** `GET /fsap/v1/transfers/{transferId}/status` (com cabeçalho `X-FSAP-Transfer-Token`).
- **Formato:** JSON (`application/json; charset=utf-8`).
- **Campos do `payload`:**

| Campo | Obrigatório | Tipo | Regras de Validação |
| :--- | :---: | :--- | :--- |
| `transferId` | Sim | `String` | UUID v4 da sessão. |
| `state` | Sim | `String` | Estado atual de `TransferState` (`PENDING_APPROVAL`, `ACCEPTED`, `TRANSFERRING`, `VERIFYING`, `COMPLETED`, `CANCELLED`, `FAILED`, `REJECTED`). |
| `totalBytesTransferred` | Sim | `Long` | Total acumulado de bytes recebidos na sessão (`0..totalBytes`). |
| `totalBytes` | Sim | `Long` | Total esperado na sessão. |
| `currentFileId` | Não | `String` | `fileId` atualmente em recepção, se aplicável. |
| `filesCompleted` | Sim | `Int` | Quantidade de arquivos já validados com SHA-256. |
| `totalFiles` | Sim | `Int` | Quantidade total de arquivos do lote. |

- **Estados Associados:** Qualquer estado de `TransferState` (consulta idempotente sem efeito colateral).

---

### 3.7. `TRANSFER_CANCEL` — Cancelamento Explícito de Transferência

- **Nome e Identificador:** `TRANSFER_CANCEL`
- **Significado Semântico e Objetivo:** Solicita o aborto imediato de uma transferência em andamento ou aguardando aprovação. Determina a interrupção imediata do stream TCP e a exclusão obrigatória de todos os arquivos `.part` vinculados ao `transferId`.
- **Emissor e Receptor:**
  - *Caso 1 (Remetente cancela):* O Cliente envia `POST /fsap/v1/transfers/{transferId}/cancel` para o Servidor e fecha o stream de envio.
  - *Caso 2 (Destinatário cancela):* O Servidor aborta a leitura do `TRANSFER_CONTENT` respondendo com `TRANSFER_CANCEL` (ou HTTP `499`/`200` no canal de controle) e invalida a sessão.
- **Transporte e Endpoint HTTP:** `POST /fsap/v1/transfers/{transferId}/cancel`
- **Formato:** JSON (`application/json; charset=utf-8`).
- **Campos do `payload`:**

| Campo | Obrigatório | Tipo | Regras de Validação |
| :--- | :---: | :--- | :--- |
| `transferId` | Sim | `String` | UUID v4 da sessão a cancelar. |
| `cancelledBy` | Sim | `String` | `"SENDER"` \| `"RECEIVER"`. |
| `reason` | Não | `String` | Motivo opcional (ex: `"USER_REQUESTED"`, `"APP_SHUTDOWN"`). |

- **Resposta Esperada:** HTTP `200 OK` confirmando o estado `CANCELLED` e a limpeza dos arquivos `.part`.
- **Estados Associados:** `TransferState: CREATED` / `PENDING_APPROVAL` / `ACCEPTED` / `TRANSFERRING` / `VERIFYING` → `CANCELLED`.

---

### 3.8. `TRANSFER_COMPLETE` — Finalização e Confirmação Global do Lote

- **Nome e Identificador:** `TRANSFER_COMPLETE`
- **Significado Semântico e Objetivo:** Sinaliza que todos os arquivos autorizados na sessão `transferId` foram transmitidos pelo remetente e solicita ao servidor a confirmação final de que todos os hashes SHA-256 foram verificados e todos os arquivos foram persistidos definitivamente no armazenamento de destino.
- **Emissor e Receptor:** Cliente (Remetente) → Servidor (Destinatário) (e resposta de confirmação Servidor → Cliente).
- **Transporte e Endpoint HTTP:** `POST /fsap/v1/transfers/{transferId}/complete`
- **Formato:** JSON (`application/json; charset=utf-8`).
- **Campos do `payload`:**

| Campo | Obrigatório | Tipo | Regras de Validação |
| :--- | :---: | :--- | :--- |
| `transferId` | Sim | `String` | UUID v4 da sessão. |
| `completedFiles` | Sim | `Array<CompletedFileSummary>` | Lista de arquivos finalizados com `fileId` e `sha256`. |

- **Resposta Esperada:** HTTP `200 OK` com envelope `TRANSFER_COMPLETE` do servidor confirmando `state: "COMPLETED"`.
- **Possíveis Erros:**
  - `409 Conflict` (`FSAP-ERR-409-INVALID_STATE_TRANSITION` se algum arquivo do lote ainda não foi recebido ou verificado).
  - `422 Unprocessable Entity` (`FSAP-ERR-422-HASH_MISMATCH`).
- **Estados Associados:** `TransferState: VERIFYING` → `COMPLETED`.

---

### 3.9. `ERROR` — Mensagem Padronizada de Erro do Protocolo

- **Nome e Identificador:** `ERROR`
- **Significado Semântico e Objetivo:** Comunica de forma estruturada e determinística qualquer erro ocorrido durante o processamento de uma mensagem HTTP/TCP do FSAP.
- **Emissor e Receptor:** Servidor → Cliente (no corpo de respostas HTTP `4xx` e `5xx`).
- **Transporte Utilizado:** HTTP/1.1 sobre TCP.
- **Formato:** JSON (`application/json; charset=utf-8`).
- **Campos do `payload`:**

| Campo | Obrigatório | Tipo | Regras de Validação |
| :--- | :---: | :--- | :--- |
| `errorCode` | Sim | `String` | Código canônico do catálogo (ex: `"FSAP-ERR-422-HASH_MISMATCH"`). |
| `httpStatus` | Sim | `Int` | Código de status HTTP associado (`400..599`). |
| `category` | Sim | `String` | Categoria do erro (`"VALIDATION"`, `"AUTH"`, `"STATE"`, `"INTEGRITY"`, `"STORAGE"`, `"TIMEOUT"`, `"NETWORK"`, `"INTERNAL"`). |
| `message` | Sim | `String` | Descrição técnica clara do problema em UTF-8. |
| `transferId` | Não | `String` | UUID v4 da transferência afetada, quando aplicável. |
| `fileId` | Não | `String` | Identificador do arquivo afetado, quando aplicável. |
| `recoverable` | Sim | `Boolean` | Indica se o cliente pode tentar reenviar ou se a sessão foi encerrada (`FAILED`). |

- **Estados Associados:** Provoca ou reflete a transição da sessão para `TransferState: FAILED` (quando `recoverable == false`).

---

## 4. Exemplos Concretos de Mensagens Válidas e Inválidas (Seção 5.5)

### 4.1. Exemplo 1: Mensagem UDP `ANNOUNCE` Válida
```json
{
  "protocol": "FSAP",
  "version": "1.0",
  "type": "ANNOUNCE",
  "messageId": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
  "timestamp": "2026-11-12T20:00:05Z",
  "senderId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "payload": {
    "alias": "Desktop-Ubuntu-Sala3",
    "deviceType": "DESKTOP",
    "platform": "LINUX",
    "httpPort": 53317,
    "capabilities": ["MULTI_FILE", "SHA256_STREAM"]
  }
}
```

### 4.2. Exemplo 2: Mensagem UDP `ANNOUNCE` Inválida (Descartada pelo Receptor)
```json
{
  "protocol": "FSAP",
  "version": "1.0",
  "type": "ANNOUNCE",
  "messageId": "nao-e-um-uuid",
  "timestamp": "2026-11-12T20:00:05Z",
  "senderId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "payload": {
    "alias": "",
    "deviceType": "SMARTWATCH",
    "platform": "LINUX",
    "httpPort": 70000
  }
}
```
- **Motivos de Invalidação:**
  1. `messageId` não obedece ao formato UUID v4;
  2. `payload.alias` está vazio (`length < 1`);
  3. `payload.deviceType` (`"SMARTWATCH"`) não pertence ao enum permitido;
  4. `payload.httpPort` (`70000`) excede o limite máximo de porta TCP (`65535`).

---

### 4.3. Exemplo 3: Mensagem HTTP `TRANSFER_REQUEST` Válida
```http
POST /fsap/v1/transfers/prepare HTTP/1.1
Host: 192.168.1.42:53317
Content-Type: application/json; charset=utf-8
X-FSAP-Version: 1.0

{
  "protocol": "FSAP",
  "version": "1.0",
  "type": "TRANSFER_REQUEST",
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "timestamp": "2026-11-12T20:05:00Z",
  "senderId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "payload": {
    "transferId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "senderAlias": "Desktop-Ubuntu-Sala3",
    "senderDeviceType": "DESKTOP",
    "totalBytes": 2097152,
    "files": [
      {
        "fileId": "file-001",
        "fileName": "relatorio-redes-final.pdf",
        "sizeBytes": 2097152,
        "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        "mimeType": "application/pdf"
      }
    ]
  }
}
```

### 4.4. Exemplo 4: Mensagem HTTP `TRANSFER_REQUEST` Inválida (Tentativa de Path Traversal e Hash Malformado)
```json
{
  "protocol": "FSAP",
  "version": "1.0",
  "type": "TRANSFER_REQUEST",
  "messageId": "550e8400-e29b-41d4-a716-446655440001",
  "timestamp": "2026-11-12T20:05:02Z",
  "senderId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "payload": {
    "transferId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "senderAlias": "Cliente-Suspeito",
    "senderDeviceType": "DESKTOP",
    "totalBytes": 1024,
    "files": [
      {
        "fileId": "file-001",
        "fileName": "../../etc/passwd",
        "sizeBytes": 1024,
        "sha256": "hash_curto_invalido"
      }
    ]
  }
}
```
- **Resposta Imediata do Servidor (`400 Bad Request`):**
```json
{
  "protocol": "FSAP",
  "version": "1.0",
  "type": "ERROR",
  "messageId": "c9a646d3-9c61-4cb7-bfcd-ee2522c8f633",
  "timestamp": "2026-11-12T20:05:02Z",
  "senderId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "payload": {
    "errorCode": "FSAP-ERR-400-INVALID_FILENAME",
    "httpStatus": 400,
    "category": "VALIDATION",
    "message": "O nome de arquivo '../../etc/passwd' contem sequencias de navegacao de diretorio proibidas.",
    "transferId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "fileId": "file-001",
    "recoverable": false
  }
}
```


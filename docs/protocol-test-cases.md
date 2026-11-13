# Casos de Teste de Conformidade — Protocolo FSAP/1.0

> **Status:** Especificação de Testes de Conformidade (Milestone M0 — Seção 5.9)  
> **Nota:** Nenhum teste foi executado como concluído nesta etapa. Estes casos de teste formam a suíte obrigatória de validação para as implementações das Milestones M1 a M6.

---

## 1. Convenções da Suíte de Conformidade

Cada caso de teste abaixo verifica de maneira determinística um aspecto da especificação **FSAP/1.0**, relacionando:
- **Requisito Funcional / Não Funcional (`RF` / `RNF`)**
- **Mensagens e Eventos FSAP**
- **Pré-condições e Entradas**
- **Resultado e Estado Final Esperado**

---

## 2. Catálogo de Casos de Teste (`TC-FSAP-001` a `TC-FSAP-026`)

### 2.1. Descoberta UDP e Identificação (`TC-FSAP-001` a `TC-FSAP-007`)

| ID | Título do Caso de Teste | Requisito / Mensagem | Pré-condição e Entrada | Resultado e Estado Esperado |
| :--- | :--- | :--- | :--- | :--- |
| **`TC-FSAP-001`** | Validação de serialização e parsing de `ANNOUNCE` válido | RF-01, RF-03 / `ANNOUNCE` | Envelope JSON válido com `protocol="FSAP"`, `version="1.0"`, `type="ANNOUNCE"`, UUIDv4 válido e `httpPort=53317`. | Parser deserializa sem erros; validador aprova a mensagem; `PeerRegistry` insere o dispositivo no estado `DISCOVERED`. |
| **`TC-FSAP-002`** | Resposta a `DISCOVER` com `ANNOUNCE` e filtro de auto-descoberta | RF-01, RF-02 / `DISCOVER`, `ANNOUNCE` | Nó Local `A` no estado `AVAILABLE` recebe datagrama `DISCOVER` de `B` (`senderId != A`) e datagrama `ANNOUNCE` do próprio `A` (`senderId == A`). | Nó `A` ignora seu próprio pacote e agenda emissão de `ANNOUNCE` para responder a `B` dentro de `50..300 ms`. |
| **`TC-FSAP-003`** | Descarte silencioso de datagramas UDP malformados ou inválidos | Seção 5.1 / `ANNOUNCE` | Envio no socket UDP de: (a) lixo binário não-JSON; (b) JSON com `protocol="OUTRO"`; (c) JSON com `httpPort=99999`. | Nenhum pacote de erro é emitido no multicast; o receptor não sofre crash, mantém estado `AVAILABLE` e não adiciona entrada na `PeerRegistry`. |
| **`TC-FSAP-004`** | Expiração automática de dispositivo por TTL (`18 s`) | RF-04, RNF-05 / `ANNOUNCE` | Par `B` está `DISCOVERED` com `lastSeenAt = t0`. Relógio virtual avança `t0 + 13 s` e depois `t0 + 19 s` sem novos anúncios. | Em `t0 + 13 s` o estado do par muda para `STALE`; em `t0 + 19 s` o par transita para `DISCONNECTED` e é removido da lista ativa. |
| **`TC-FSAP-005`** | Remoção imediata ao receber datagrama `BYE` | RF-02, RF-04 / `BYE` | Par `B` está `DISCOVERED`. Nó `A` recebe datagrama UDP `BYE` válido com `senderId = B`. | Par `B` transita imediatamente para `DISCONNECTED` sem aguardar os 18 segundos do TTL. |
| **`TC-FSAP-006`** | Handshake `IDENTIFY` bem-sucedido por IP manual | RF-05 / `IDENTIFY` | Multicast desabilitado. Cliente envia `POST /fsap/v1/identify` para `IP:53317` de servidor ativo compatível (`version="1.0"`). | Servidor retorna HTTP `200 OK` com sua identidade; cliente registra o par na `PeerRegistry` com estado `CONNECTED`. |
| **`TC-FSAP-007`** | Rejeição de `IDENTIFY` com versão `MAJOR` incompatível | Seção 5.1 / `IDENTIFY`, `ERROR` | Cliente envia `IDENTIFY` declarando `version="2.0"` para um servidor `1.0`. | Servidor retorna HTTP `505` com envelope `ERROR` e código `FSAP-ERR-505-UNSUPPORTED_VERSION`. |

---

### 2.2. Negociação e Autorização de Transferência (`TC-FSAP-008` a `TC-FSAP-012`)

| ID | Título do Caso de Teste | Requisito / Mensagem | Pré-condição e Entrada | Resultado e Estado Esperado |
| :--- | :--- | :--- | :--- | :--- |
| **`TC-FSAP-008`** | Fluxo nominal de `TRANSFER_REQUEST` e aprovação `TRANSFER_ACCEPT` | RF-07, RF-08 / `TRANSFER_REQUEST`, `TRANSFER_ACCEPT` | Cliente envia manifesto válido com 1 arquivo (`sizeBytes=1024`, `sha256` válido). Usuário destinatário aprova em 2 s. | Estado avança `CREATED` → `PENDING_APPROVAL` → `ACCEPTED`. Servidor retorna HTTP `200 OK` com `transferToken` não vazio. |
| **`TC-FSAP-009`** | Bloqueio de *Path Traversal* no manifesto de transferência | RF-07, RNF-06 / `TRANSFER_REQUEST`, `ERROR` | Cliente envia `TRANSFER_REQUEST` contendo `fileName = "../../etc/shadow"` ou `"pasta/arquivo.txt"`. | Servidor rejeita imediatamente com HTTP `400` e `FSAP-ERR-400-INVALID_FILENAME` sem acionar modal de confirmação do usuário. |
| **`TC-FSAP-010`** | Rejeição por falta de espaço livre no destinatário | RF-13, RF-14 / `TRANSFER_REQUEST`, `ERROR` | Cliente envia `TRANSFER_REQUEST` com `totalBytes` superior ao espaço livre reportado pelo `FileSink` do servidor. | Servidor retorna HTTP `507` com `FSAP-ERR-507-INSUFFICIENT_STORAGE`; estado vai para `FAILED`. |
| **`TC-FSAP-011`** | Recusa explícita pelo usuário destinatário (`TRANSFER_REJECT`) | RF-08 / `TRANSFER_REJECT` | Destinatário clica em "Recusar" no modal de `PENDING_APPROVAL`. | Servidor retorna HTTP `403` com `TRANSFER_REJECT` (`reasonCode="USER_DECLINED"`). Estado em ambos os nós vai para `REJECTED`. |
| **`TC-FSAP-012`** | Timeout de aprovação humana (`60 s`) | RF-08, RF-13 / `ERROR` | Destinatário não responde ao modal por mais de `T_APPROVAL_TIMEOUT` (`60000 ms`). | Servidor encerra a espera e retorna HTTP `408` com `FSAP-ERR-408-APPROVAL_TIMEOUT`. Estado em ambos vai para `FAILED`. |

---

### 2.3. Transferência Binária em Streaming, Progresso e Cancelamento (`TC-FSAP-013` a `TC-FSAP-018`)

| ID | Título do Caso de Teste | Requisito / Mensagem | Pré-condição e Entrada | Resultado e Estado Esperado |
| :--- | :--- | :--- | :--- | :--- |
| **`TC-FSAP-013`** | Bloqueio de `TRANSFER_CONTENT` sem `transferToken` válido | RF-08, Seção 5.1 / `TRANSFER_CONTENT`, `ERROR` | Cliente tenta `PUT /fsap/v1/transfers/{id}/files/{fileId}/content` sem cabeçalho `X-FSAP-Transfer-Token` ou com token incorreto. | Servidor retorna HTTP `401` (`FSAP-ERR-401-UNAUTHORIZED_TRANSFER`) sem criar arquivo `.part`. |
| **`TC-FSAP-014`** | Transferência em streaming de arquivo multi-chunk (ex: 5 MB em blocos de 64 KB) | RF-09, RNF-01 / `TRANSFER_CONTENT` | Sessão no estado `ACCEPTED`. Cliente transmite 5 MB de dados binários determinísticos. | Servidor grava em `.part`, valida SHA-256 em `VERIFYING`, move para o arquivo final e retorna HTTP `200 OK`. |
| **`TC-FSAP-015`** | Transferência de lote com múltiplos arquivos em uma única sessão | RF-06, RF-09 / `TRANSFER_REQUEST`, `TRANSFER_CONTENT`, `TRANSFER_COMPLETE` | Manifesto com 3 arquivos distintos aprovado. Cliente envia 3 chamadas `PUT /content` seguidas de `POST /complete`. | Todos os 3 arquivos são verificados individualmente e a sessão transita para `COMPLETED` somente após o `TRANSFER_COMPLETE`. |
| **`TC-FSAP-016`** | Emissão de progresso e consulta via `TRANSFER_STATUS` | RF-10 / `TRANSFER_STATUS` | Durante o envio de um arquivo em chunks, o cliente (ou UI) observa o progresso ou consulta `GET /status`. | `totalBytesTransferred` cresce monotonicamente de `0` até `totalBytes` e o estado reportado é `TRANSFERRING`. |
| **`TC-FSAP-017`** | Cancelamento iniciado pelo Remetente durante `TRANSFERRING` | RF-11, RNF-07 / `TRANSFER_CANCEL` | Aos 50% do envio, o remetente cancela e envia `POST /cancel` (`cancelledBy="SENDER"`). | Stream é fechado, servidor exclui imediatamente o arquivo `.part` e ambos os nós transitam para `CANCELLED`. |
| **`TC-FSAP-018`** | Cancelamento iniciado pelo Destinatário durante `TRANSFERRING` | RF-11, RNF-07 / `TRANSFER_CANCEL` | Aos 50% do recebimento, o destinatário aciona cancelar no servidor. | Servidor interrompe leitura, apaga o arquivo `.part`, responde com cancelamento e ambos os nós transitam para `CANCELLED`. |

---

### 2.4. Verificação de Integridade, Erros de Rede e Máquinas de Estados (`TC-FSAP-019` a `TC-FSAP-026`)

| ID | Título do Caso de Teste | Requisito / Mensagem | Pré-condição e Entrada | Resultado e Estado Esperado |
| :--- | :--- | :--- | :--- | :--- |
| **`TC-FSAP-019`** | Sucesso na verificação de integridade SHA-256 e commit atômico | RF-12, RF-14, RNF-07 / `TRANSFER_COMPLETE` | Bytes enviados possuem exatamente o mesmo digest SHA-256 informado no `TRANSFER_REQUEST`. | Arquivo `.part` é renomeado para o nome final; nenhum arquivo `.part` resta na pasta temporária; estado final é `COMPLETED`. |
| **`TC-FSAP-020`** | Detecção de corrupção de dados (Divergência de SHA-256) | RF-12, RNF-07 / `ERROR` (`HASH_MISMATCH`) | Cliente altera 1 bit de um chunk durante o envio de `TRANSFER_CONTENT`, mantendo o mesmo `sizeBytes`. | Na fase `VERIFYING`, o servidor detecta `calculatedSha256 != expectedSha256`, **deleta o arquivo `.part`**, retorna HTTP `422` (`FSAP-ERR-422-HASH_MISMATCH`) e vai para `FAILED`. |
| **`TC-FSAP-021`** | Detecção de stream truncado (`bytesReceived < sizeBytes`) | RF-13 / `ERROR` (`SIZE_MISMATCH`) | Conexão TCP é fechada após enviar apenas 40% dos bytes declarados em `sizeBytes`. | Servidor detecta contagem incompleta de bytes, remove o arquivo `.part` e transita a sessão para `FAILED`. |
| **`TC-FSAP-022`** | Timeout de leitura por estagnação do stream (`15 s`) | RF-13, Seção 5.1 / `ERROR` (`STREAM_TIMEOUT`) | Cliente abre `TRANSFER_CONTENT`, envia 1 chunk e congela sem enviar novos bytes por `> 15000 ms`. | Servidor aborta leitura com `FSAP-ERR-408-STREAM_TIMEOUT`, exclui o `.part` e move a sessão para `FAILED`. |
| **`TC-FSAP-023`** | Rejeição de transição inválida na máquina de estados | Seção 5.3 / `ERROR` (`INVALID_STATE_TRANSITION`) | Cliente envia `TRANSFER_COMPLETE` logo após `TRANSFER_ACCEPT`, sem ter enviado o `TRANSFER_CONTENT` dos arquivos. | Máquina de estados do servidor recusa a transição `ACCEPTED` → `COMPLETED` e retorna HTTP `409` (`FSAP-ERR-409-INVALID_STATE_TRANSITION`). |
| **`TC-FSAP-024`** | Prevenção de sobrescrita de arquivo já existente no destino | RF-14, Seção 2 Security | Diretório de destino já contém `documento.pdf`. Uma nova transferência válida de outro `documento.pdf` é concluída. | O arquivo existente é preservado intacto e o novo arquivo é salvo como `documento (1).pdf`. |
| **`TC-FSAP-025`** | Transferência de arquivo vazio (`0 bytes`) | RF-09, RF-12 | Arquivo válido de `0 bytes` (SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`). | Protocolo aceita `sizeBytes = 0`, valida o hash SHA-256 do vazio e cria o arquivo de 0 bytes no destino (`COMPLETED`). |
| **`TC-FSAP-026`** | Múltiplas transferências simultâneas para o mesmo servidor | RF-15 | Dois clientes distintos (`A` e `B`) enviam arquivos simultaneamente em sessões concorrentes para o servidor `C`. | Ambos os fluxos progridem em coroutines isoladas sem corrupção de buffers ou mistura de arquivos `.part`, concluindo em `COMPLETED`. |


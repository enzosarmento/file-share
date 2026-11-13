# Especificação Oficial do Protocolo FSAP/1.0 (FileShare Application Protocol)

> **Identificador do Protocolo:** `FSAP`  
> **Versão da Especificação:** `1.0`  
> **Status:** Especificação Normativa (Milestone M0 — Nenhuma implementação iniciada)  
> **Documentos Complementares:**
> - [Máquinas de Estados (`state-machine.md`)](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/state-machine.md)
> - [Catálogo de Mensagens (`protocol-messages.md`)](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-messages.md)
> - [Fluxos de Comunicação (`communication-flows.md`)](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/communication-flows.md)
> - [Catálogo de Erros (`error-codes.md`)](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/error-codes.md)
> - [Casos de Teste de Conformidade (`protocol-test-cases.md`)](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-test-cases.md)

---

## 1. Especificação Geral (Seção 5.1)

### 1.1. Nome e Versão do Protocolo
- **Nome Oficial:** **FileShare Application Protocol (FSAP)**
- **String de Identificação de Protocolo:** `"FSAP"`
- **Versão Atual:** `"1.0"` (formato `MAJOR.MINOR`)
- **Cabeçalho de Versão em Transporte HTTP:** `X-FSAP-Version: 1.0`

### 1.2. Objetivo e Responsabilidades
O **FSAP/1.0** é um protocolo de camada de aplicação projetado para permitir que dispositivos heterogêneos (Desktop Linux/Windows e Android) conectados à mesma rede local (LAN/WLAN) descubram uns aos outros sem servidor central, negociem sessões de transferência com aprovação humana explícita e transmitam arquivos binários em streaming com verificação criptográfica de integridade ponta a ponta.

São responsabilidades do FSAP/1.0:
1. **Anúncio e Descoberta de Presença:** Divulgar a existência, identidade e porta de serviço de um nó na sub-rede local e permitir busca ativa por pares.
2. **Identificação e Handshake:** Permitir que dois nós verifiquem compatibilidade de versão e metadados tanto após descoberta multicast quanto via conexão manual por IP.
3. **Negociação e Autorização de Transferência:** Transmitir o manifesto de arquivos propostos antes do envio de qualquer byte de conteúdo, exigindo aceitação explícita do destinatário e emissão de um token de autorização de sessão (`transferToken`).
4. **Transporte de Conteúdo Binário em Streaming:** Delimitar o envio de cada arquivo autorizado sobre conexões TCP confiáveis, permitindo acompanhamento de progresso e cancelamento a qualquer instante.
5. **Verificação de Integridade e Fechamento de Sessão:** Validar tamanho exato em bytes e resumo criptográfico **SHA-256** de cada arquivo recebido antes de confirmá-lo como concluído.
6. **Sinalização Padronizada de Falhas:** Comunicar erros sintáticos, semânticos, de estado, de autorização ou de armazenamento por meio de códigos determinísticos.

### 1.3. Arquitetura de Comunicação e Transportes Utilizados

O FSAP opera sobre uma arquitetura híbrida que combina **UDP** (Plano de Descoberta) e **HTTP/1.1 sobre TCP** (Plano de Controle de Sessão e Plano de Dados Binários):

| Parâmetro de Rede | Plano de Descoberta (UDP) | Plano de Controle e Dados (HTTP/1.1 sobre TCP) |
| :--- | :--- | :--- |
| **Protocolo de Transporte** | UDP (User Datagram Protocol — RFC 768) | TCP (Transmission Control Protocol — RFC 9293) + HTTP/1.1 (RFC 9112) |
| **Endereço / Grupo Padrão** | Grupo Multicast IPv4 `239.255.70.83` (escopo administrativo local RFC 2365) | Endereço IPv4 unicast da interface LAN do nó receptor (`0.0.0.0` no bind local) |
| **Porta Padrão** | **`53318` / UDP** | **`53317` / TCP** (configurável; anunciada no campo `httpPort` do UDP `ANNOUNCE`) |
| **Tamanho Máximo de Pacote** | `1024 bytes` por datagrama UDP (evita fragmentação IP abaixo do MTU Ethernet de 1500 B) | Metadados JSON: máx. `256 KB` (`262144 bytes`); Stream binário: até `64 GiB` por arquivo |
| **Codificação** | JSON em UTF-8 sem BOM | Metadados em JSON UTF-8 (`application/json`); Arquivos em binário puro (`application/octet-stream`) |

### 1.4. Justificativa Técnica para Utilização Conjunta de UDP e TCP
- **Por que UDP no Plano de Descoberta:** Na descoberta de dispositivos em LAN, o emissor desconhece previamente quais IPs estão ativos. O **UDP Multicast** permite enviar um único datagrama para `239.255.70.83:53318` que é replicado na camada de enlace para todos os membros inscritos no grupo IGMP, sem o custo de estabelecer dezenas de handshakes TCP nem varrer 254 endereços IP sequencialmente. A eventual perda de um pacote UDP é absorvida pelo próximo ciclo de anúncio periódico.
- **Por que TCP (com enquadramento HTTP/1.1) no Plano de Controle e Transferência:** A transferência de arquivos exige que **nenhum byte seja perdido, duplicado ou entregue fora de ordem**. O **TCP** provê controle de fluxo por janela deslizante, controle de congestionamento, retransmissão automática de segmentos perdidos e detecção de fechamento de conexão (`FIN`/`RST`). O uso de **HTTP/1.1** sobre TCP fornece enquadramento padronizado de cabeçalhos (`Content-Length`, `Content-Type`, `X-FSAP-*`), códigos de status semânticos e suporte maduro a streaming bidirecional em qualquer plataforma.

### 1.5. Identificação das Partes Comunicantes
Todo nó participante do FSAP mantém uma estrutura imutável durante a sessão denominada **`DeviceIdentity`**:

| Campo | Tipo | Formato / Restrição | Descrição |
| :--- | :--- | :--- | :--- |
| `deviceId` | `String` | UUID v4 canônico (36 caracteres, RFC 9562), ex: `"f47ac10b-58cc-4372-a567-0e02b2c3d479"` | Identificador único global do dispositivo, gerado na primeira execução e persistido localmente. |
| `alias` | `String` | UTF-8, `1..64` caracteres visíveis (sem quebras de linha ou caracteres de controle) | Nome amigável exibido ao usuário (ex: `"Notebook-Linux-Lab"`, `"Pixel-8-Maria"`). |
| `deviceType` | `String` | Enum: `"DESKTOP"` \| `"MOBILE"` \| `"TABLET"` \| `"SERVER"` | Categoria física/operacional do dispositivo. |
| `platform` | `String` | Enum: `"LINUX"` \| `"WINDOWS"` \| `"ANDROID"` | Sistema operacional em execução. |
| `protocolVersion` | `String` | `"1.0"` (`^\d+\.\d+$`) | Versão do protocolo FSAP suportada pelo nó. |
| `httpPort` | `Int` | `1024..65535` (padrão `53317`) | Porta TCP onde o servidor HTTP FSAP daquele nó está escutando conexões. |

### 1.6. Formato e Codificação das Mensagens
1. **Mensagens de Controle e Descoberta (JSON):**
   - Codificadas obrigatoriamente em **UTF-8**.
   - Nomes de propriedades em `camelCase`.
   - Todas as mensagens JSON (tanto sobre UDP quanto sobre HTTP) utilizam um **Envelope Padrão FSAP**:
     ```json
     {
       "protocol": "FSAP",
       "version": "1.0",
       "type": "<MESSAGE_TYPE>",
       "messageId": "<UUIDv4>",
       "timestamp": "2026-11-12T20:00:00Z",
       "senderId": "<UUIDv4 do dispositivo emissor>",
       "payload": { ... }
     }
     ```
2. **Mensagens de Conteúdo Binário (`TRANSFER_CONTENT`):**
   - O corpo HTTP (`Request Body`) contém **exclusivamente os bytes brutos do arquivo** (`Content-Type: application/octet-stream`).
   - Os metadados de enquadramento viajam nos parâmetros de rota (`/fsap/v1/transfers/{transferId}/files/{fileId}/content`) e nos cabeçalhos HTTP obrigatórios:
     - `X-FSAP-Protocol: FSAP/1.0`
     - `X-FSAP-Sender-Id: <UUIDv4>`
     - `X-FSAP-Transfer-Token: <token emitido no TRANSFER_ACCEPT>`
     - `X-FSAP-File-SHA256: <64 chars hex minúsculo>`
     - `Content-Length: <tamanho exato em bytes>`

### 1.7. Regras Gerais de Comunicação
1. **Idempotência de Descoberta:** Receber múltiplos `ANNOUNCE` do mesmo `deviceId` apenas atualiza o timestamp `lastSeenAt` e eventuais alterações de `alias`/`ipAddress`/`httpPort`, sem duplicar entradas na lista de dispositivos.
2. **Ignorar Loopback de Multicast:** Um nó deve descartar silenciosamente qualquer datagrama UDP `ANNOUNCE`, `DISCOVER` ou `BYE` cujo `senderId` seja igual ao seu próprio `deviceId`.
3. **Proibição de Envio Binário Não Autorizado:** Nenhum cliente pode invocar `TRANSFER_CONTENT` sem antes receber uma resposta `TRANSFER_ACCEPT` válida contendo um `transferToken` ativo. Tentativas sem token ou com token inválido são rejeitadas imediatamente com `401 Unauthorized` (`FSAP-ERR-401-UNAUTHORIZED_TRANSFER`).
4. **Ordem de Envio em Lote:** Quando uma transferência contém múltiplos arquivos, o remetente envia um `TRANSFER_CONTENT` por arquivo (sequencialmente por padrão no MVP) e, somente após o sucesso de todos os arquivos aceitos, envia `TRANSFER_COMPLETE`.

### 1.8. Temporizadores e Timeouts Normativos

Todos os nós compatíveis com o FSAP/1.0 devem respeitar os seguintes parâmetros temporais:

| Constante Normativa | Valor Padrão | Descrição e Comportamento |
| :--- | :---: | :--- |
| `T_ANNOUNCE_INTERVAL` | **`5000 ms` (5 s)** | Intervalo periódico entre emissões consecutivas de datagramas UDP `ANNOUNCE` pelo nó disponível. |
| `T_DISCOVER_JITTER` | **`50..300 ms`** | Atraso aleatório antes de responder a um `DISCOVER` multicast com um `ANNOUNCE`, prevenindo colisão de pacotes. |
| `T_PEER_TTL` | **`18000 ms` (18 s)** | Tempo máximo sem receber `ANNOUNCE` após o qual um dispositivo remoto transita para expirado/indisponível (equivale a 3 anúncios perdidos + 3 s de margem). |
| `T_PEER_SWEEP_INTERVAL` | **`3000 ms` (3 s)** | Periodicidade da tarefa local que varre a `PeerRegistry` em busca de dispositivos com `now - lastSeenAt > T_PEER_TTL`. |
| `T_TCP_CONNECT_TIMEOUT` | **`5000 ms` (5 s)** | Tempo limite para estabelecimento do socket TCP (`SYN` → `SYN-ACK`) em chamadas `IDENTIFY` ou `TRANSFER_REQUEST`. |
| `T_APPROVAL_TIMEOUT` | **`60000 ms` (60 s)** | Tempo máximo que o destinatário tem para clicar em "Aceitar" ou "Recusar" uma `TRANSFER_REQUEST` pendente. Ao expirar, a sessão transita para `FAILED` (`FSAP-ERR-408-APPROVAL_TIMEOUT`). |
| `T_TOKEN_IDLE_TTL` | **`30000 ms` (30 s)** | Tempo máximo entre a emissão do `TRANSFER_ACCEPT` (ou término do arquivo anterior) e o início do próximo `TRANSFER_CONTENT`. |
| `T_STREAM_READ_TIMEOUT` | **`15000 ms` (15 s)** | Tempo máximo sem recepção de nenhum byte durante uma transferência ativa (`TRANSFERRING`) antes de considerar a conexão morta (`FSAP-ERR-408-STREAM_TIMEOUT`). |
| `T_SHUTDOWN_GRACE` | **`2000 ms` (2 s)** | Tempo máximo aguardado no encerramento da aplicação para envio de `TRANSFER_CANCEL` e `BYE` antes de fechar os sockets forçosamente. |

### 1.9. Tratamento de Mensagens Inválidas
1. **No Transporte UDP (Descoberta):**
   - Datagramas que excedam `1024 bytes`, não sejam UTF-8 válido, falhem no parsing JSON, não contenham `protocol == "FSAP"` ou possuam campos obrigatórios inválidos **devem ser descartados silenciosamente** (registrando um `WARN` no log local com IP de origem), sem enviar pacotes de erro em resposta no canal multicast (para evitar amplificação/tempestade de broadcast).
2. **No Transporte HTTP/TCP (Controle e Transferência):**
   - Qualquer mensagem com JSON malformado, campo obrigatório ausente ou tipo de dado inválido deve ser respondida imediatamente com HTTP `400 Bad Request` contendo a mensagem `ERROR` com código `FSAP-ERR-400-MALFORMED_MESSAGE` ou `FSAP-ERR-400-VALIDATION_FAILED`.
   - Qualquer mensagem recebida fora da ordem esperada pela máquina de estados da sessão deve ser respondida com HTTP `409 Conflict` e código `FSAP-ERR-409-INVALID_STATE_TRANSITION`.

### 1.10. Compatibilidade entre Versões
O FSAP utiliza versionamento `MAJOR.MINOR` (`"1.0"`):
- **Mesmo `MAJOR` (ex: `1.0` e `1.1`):** Compatíveis entre si. Implementações devem configurar o parser JSON com `ignoreUnknownKeys = true`, ignorando campos opcionais adicionados em versões `MINOR` futuras.
- **`MAJOR` Diferente (ex: `1.0` e `2.0`):** Incompatíveis. Durante o `IDENTIFY` ou `TRANSFER_REQUEST`, se o receptor detectar um `MAJOR` diferente de `1`, deve rejeitar a requisição com HTTP `505 HTTP Version Not Supported` (ou `426 Upgrade Required`) e envelope `ERROR` com código `FSAP-ERR-505-UNSUPPORTED_VERSION`.

---

## 2. Catálogo Completo de Eventos de Comunicação (Seção 5.2)

A tabela a seguir especifica formalmente todos os **20 eventos de comunicação** do sistema FileShare, detalhando origem, destinatário, condição de ocorrência, mensagem associada, transição de estado e ação esperada de cada parte:

| ID | Evento | Origem | Destinatário | Condição de Ocorrência | Mensagem FSAP | Estado Anterior | Estado Resultante | Ação Esperada (Origem e Destinatário) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **EV-01** | **Inicialização de um dispositivo** | Sistema / Usuário Local | Nó Local | Usuário inicia o app (GUI) ou o Servidor Desktop Independente. | *(Interno)* | `Node: STOPPED` | `Node: INITIALIZING` | Carregar/gerar `deviceId` UUIDv4, validar diretórios de escrita, alocar servidor HTTP na porta configurada (`53317`) e preparar socket UDP (`53318`). |
| **EV-02** | **Entrada de um dispositivo na rede** | Nó Local | Grupo Multicast (`239.255.70.83:53318`) | Servidor HTTP local vinculado com sucesso e interface LAN ativa; ingresso no grupo IGMP concluído. | `DISCOVER` + `ANNOUNCE` | `Node: INITIALIZING` | `Node: DISCOVERING` → `AVAILABLE` | **Origem:** Envia 1 datagrama `DISCOVER` seguido do primeiro `ANNOUNCE` e inicia o timer periódico `T_ANNOUNCE_INTERVAL`. **Destinatários:** Agendam resposta `ANNOUNCE` com jitter. |
| **EV-03** | **Anúncio periódico de presença** | Timer Local (`T_ANNOUNCE_INTERVAL`) | Grupo Multicast (`239.255.70.83:53318`) | Disparo a cada 5 s enquanto o nó estiver em `AVAILABLE` ou `DISCOVERING`. | `ANNOUNCE` | `Node: AVAILABLE` | `Node: AVAILABLE` | **Origem:** Serializa `AnnouncePayload` e envia datagrama UDP multicast em todas as interfaces LAN válidas. |
| **EV-04** | **Recebimento de anúncio UDP** | Nó Remoto (Par na LAN) | Listener UDP do Nó Local | Recepção de datagrama UDP na porta `53318` com `senderId != localDeviceId`. | `ANNOUNCE` ou `DISCOVER` | `Node: AVAILABLE` | `Node: AVAILABLE` | **Destinatário:** Valida envelope FSAP; se for `DISCOVER`, responde com `ANNOUNCE`; se for `ANNOUNCE`, encaminha para o `PeerRegistry` (disparando EV-05 ou EV-06). |
| **EV-05** | **Descoberta de um novo dispositivo** | `PeerRegistry` Local | Camada de Sessão / UI Local | Recebimento de `ANNOUNCE` (ou resposta `IDENTIFY`) cujo `deviceId` não consta na tabela local. | `ANNOUNCE` / `IDENTIFY` | `Peer: DISCONNECTED` *(inexistente)* | `Peer: DISCOVERED` | Inserir par na `PeerRegistry` com `lastSeenAt = now`, IP de origem e `httpPort`, e emitir novo snapshot no `StateFlow` de dispositivos disponíveis. |
| **EV-06** | **Atualização das informações de um dispositivo** | `PeerRegistry` Local | Camada de Sessão / UI Local | Recebimento de `ANNOUNCE` de um `deviceId` já registrado na `PeerRegistry`. | `ANNOUNCE` | `Peer: DISCOVERED` ou `STALE` | `Peer: DISCOVERED` | Atualizar `lastSeenAt = now` e sincronizar eventual mudança de `alias`, endereço IP ou `httpPort`. |
| **EV-07** | **Expiração de dispositivo indisponível** | Timer `T_PEER_SWEEP_INTERVAL` (ou `BYE`) | `PeerRegistry` / UI Local | `now - peer.lastSeenAt > 18000 ms` ou recepção de datagrama UDP `BYE` do par. | `BYE` *(ou timeout interno)* | `Peer: DISCOVERED` | `Peer: DISCONNECTED` *(removido)* | Remover dispositivo da lista de disponíveis (ou marcá-lo offline) e notificar a UI. |
| **EV-08** | **Solicitação de conexão (Manual ou Pré-Envio)** | Cliente (Remetente) | Servidor (Destinatário) | Usuário informa IP:Porta manualmente ou seleciona um par descoberto para validar conectividade TCP. | `IDENTIFY` (`GET /fsap/v1/info`) | `Peer: DISCONNECTED` ou `DISCOVERED` | `Peer: CONNECTING` | **Origem:** Abre socket TCP (timeout 5 s) e envia requisição `IDENTIFY`. **Destinatário:** Valida versão do protocolo do chamador. |
| **EV-09** | **Estabelecimento de conexão** | Servidor (Destinatário) | Cliente (Remetente) | Servidor responde com sucesso ao `IDENTIFY` informando sua `DeviceIdentity` completa. | `IDENTIFY` (Response 200 OK) | `Peer: CONNECTING` | `Peer: CONNECTED` | **Origem:** Registra/atualiza o dispositivo como confirmado via TCP e habilita o início de transferências. |
| **EV-10** | **Solicitação de transferência** | Cliente (Remetente) | Servidor (Destinatário) | Usuário seleciona arquivos, o cliente calcula tamanhos e hashes SHA-256 e envia proposta ao par. | `TRANSFER_REQUEST` | `Transfer: CREATED` | `Transfer: PENDING_APPROVAL` | **Origem:** Envia manifesto JSON com `transferId` e lista de arquivos e aguarda até `T_APPROVAL_TIMEOUT` (60 s). **Destinatário:** Registra sessão `PENDING_APPROVAL` e solicita aprovação explícita ao usuário local. |
| **EV-11** | **Aceitação de transferência** | Usuário / Servidor Destinatário | Cliente (Remetente) | Usuário destinatário aprova o recebimento dos arquivos dentro de 60 s. | `TRANSFER_ACCEPT` | `Transfer: PENDING_APPROVAL` | `Transfer: ACCEPTED` | **Destinatário:** Gera `transferToken` aleatório, reserva espaço temporário e retorna HTTP 200 com `TRANSFER_ACCEPT`. **Origem:** Armazena `transferToken` e inicia imediatamente o EV-13. |
| **EV-12** | **Rejeição de transferência** | Usuário / Servidor Destinatário | Cliente (Remetente) | Usuário destinatário recusa a transferência (ou política local bloqueia). | `TRANSFER_REJECT` | `Transfer: PENDING_APPROVAL` | `Transfer: REJECTED` | **Destinatário:** Retorna HTTP 403 com `TRANSFER_REJECT` e descarta a sessão. **Origem:** Exibe notificação de recusa ao remetente e encerra a sessão. |
| **EV-13** | **Início do envio de arquivo** | Cliente (Remetente) | Servidor (Destinatário) | Transição para `ACCEPTED` concluída; cliente abre stream do primeiro/próximo arquivo do lote. | `TRANSFER_CONTENT` (Headers + Início do Stream) | `Transfer: ACCEPTED` | `Transfer: TRANSFERRING` | **Origem:** Inicia `PUT /fsap/v1/transfers/{transferId}/files/{fileId}/content` com `X-FSAP-Transfer-Token`. **Destinatário:** Valida token, cria arquivo `<id>.part` e instância de `MessageDigest("SHA-256")`. |
| **EV-14** | **Recepção de dados (Streaming em Chunks)** | Cliente (Remetente) | Servidor (Destinatário) | Leitura e escrita contínua de blocos de 64 KB no socket TCP. | `TRANSFER_CONTENT` (Body Stream) | `Transfer: TRANSFERRING` | `Transfer: TRANSFERRING` | **Origem:** Lê 64 KB do `FileSource` e escreve no canal TCP. **Destinatário:** Lê chunk do socket, atualiza `sha256Digest.update(chunk)`, grava no arquivo `.part` e incrementa `bytesTransferred`. |
| **EV-15** | **Atualização do progresso** | Motor de Streaming / Monitor | Camadas de UI (Remetente e Destinatário) / Cliente via Polling opcional | A cada chunk processado (com throttling de 100 ms) ou consulta `TRANSFER_STATUS`. | `TRANSFER_STATUS` *(ou evento interno de fluxo)* | `Transfer: TRANSFERRING` | `Transfer: TRANSFERRING` | Atualizar barra de progresso (`percentage`, `bytesTransferred`, `totalBytes`, `speedBytesPerSec`) em ambas as pontas. |
| **EV-16** | **Verificação de integridade e conclusão de arquivo/lote** | Servidor (Destinatário) + Cliente (Remetente) | Ambos | Todos os `sizeBytes` do arquivo foram recebidos; servidor compara o SHA-256 calculado com o declarado e cliente envia fechamento do lote. | `TRANSFER_COMPLETE` | `Transfer: TRANSFERRING` → `VERIFYING` | `Transfer: COMPLETED` | **Destinatário:** Compara `calculatedSha256 == expectedSha256`; se idêntico, move `.part` para o arquivo final no armazenamento local e responde 200 OK ao `TRANSFER_COMPLETE`. |
| **EV-17** | **Cancelamento da transferência** | Usuário (Remetente ou Destinatário) | Contraparte da Sessão | Usuário aciona o botão "Cancelar" durante `PENDING_APPROVAL` ou `TRANSFERRING`. | `TRANSFER_CANCEL` | `Transfer: PENDING_APPROVAL` ou `TRANSFERRING` | `Transfer: CANCELLED` | **Quem cancelou:** Envia `TRANSFER_CANCEL` e aborta a coroutine de stream. **Contraparte:** Interrompe leitura/escrita, fecha stream e exclui imediatamente qualquer arquivo `.part` residual. |
| **EV-18** | **Erro durante a transferência (Integridade / Disco / Protocolo)** | Servidor ou Cliente | Contraparte da Sessão | Divergência de SHA-256, falta de espaço em disco, permissão negada ou violação de protocolo. | `ERROR` | `Transfer: TRANSFERRING` ou `VERIFYING` | `Transfer: FAILED` | **Detector:** Envia mensagem `ERROR` com código padronizado (ex: `FSAP-ERR-422-HASH_MISMATCH` ou `FSAP-ERR-507-INSUFFICIENT_STORAGE`), apaga `.part` e transita para `FAILED`. |
| **EV-19** | **Desconexão inesperada** | Camada de Transporte TCP / SO | Motor de Sessão (Remetente e Destinatário) | Queda de Wi-Fi, `IOException`, `SocketTimeoutException` (`> 15 s` sem dados) ou `EOFException` antes de `Content-Length`. | *(Exceção TCP / `ERROR` local)* | `Transfer: TRANSFERRING` / `Peer: CONNECTED` | `Transfer: FAILED` / `Peer: UNREACHABLE` | Cancelar job de transferência da sessão, fechar file descriptors, remover arquivos `.part` incompletos e notificar UI com código `FSAP-ERR-408-STREAM_TIMEOUT` ou `FSAP-ERR-503-CONNECTION_LOST`. |
| **EV-20** | **Encerramento normal da aplicação** | Usuário / Sistema Operacional | Grupo Multicast e Sessões Ativas | Usuário fecha o aplicativo ou envia `SIGINT`/`SIGTERM` ao Servidor Desktop Independente. | `TRANSFER_CANCEL` (se houver sessão ativa) + `BYE` (UDP) | `Node: AVAILABLE` | `Node: SHUTTING_DOWN` → `STOPPED` | Cancelar transferências em andamento, limpar arquivos `.part`, emitir datagrama UDP `BYE`, liberar `MulticastLock` (no Android) e encerrar servidor HTTP e sockets UDP. |

---

## 3. Delimitação de Dados Binários e Streaming Sem Carregamento em Memória (Seção 5.5)

Um requisito fundamental do FSAP/1.0 é garantir que **arquivos de qualquer tamanho (ex: 500 MB, 4 GB, 20 GB) sejam transmitidos sem nunca carregar o conteúdo completo em memória RAM**.

### 3.1. Estratégia de Enquadramento no Transporte HTTP/1.1
Na mensagem `TRANSFER_CONTENT` (`PUT /fsap/v1/transfers/{transferId}/files/{fileId}/content`):
1. O cliente abre um canal de leitura sequencial sobre o arquivo de origem (`FileChannel` no Desktop ou `ContentResolver.openInputStream(uri)` no Android).
2. O cabeçalho HTTP `Content-Length` é preenchido com o valor exato de `sizeBytes` (inteiro de 64 bits `Long`), anunciado previamente no `TRANSFER_REQUEST`.
3. O corpo da requisição HTTP **não é serializado em JSON nem codificado em Base64** (o que inflaria o tráfego em 33% e exigiria buffers extras). Ele é enviado como fluxo binário puro (`Content-Type: application/octet-stream`).
4. O loop de envio e o loop de recepção operam com um **buffer fixo reutilizável de 64 KB (`65536` bytes)**:

```text
[Arquivo Origem (Disco)]
       │  (lê bloco de 64 KB)
       ▼
[Buffer do Remetente: ByteArray(65536)]
       │  (escreve no ByteWriteChannel do Socket TCP)
       ▼
══════════════ Rede Local (Segmentos TCP) ══════════════
       │  (lê do ByteReadChannel do Socket TCP)
       ▼
[Buffer do Destinatário: ByteArray(65536)]
       ├──► 1. Atualiza MessageDigest("SHA-256").update(buffer, 0, bytesRead)
       ├──► 2. Escreve no FileOutputStream("<transferId>_<fileId>.part")
       └──► 3. Atualiza contador atômico bytesReceived (+= bytesRead)
```

5. **Detecção de Truncamento:** Como o `Content-Length` e o `sizeBytes` do manifesto são conhecidos antes do primeiro byte, se o canal TCP fechar (`EOF`) quando `bytesReceived < sizeBytes`, o servidor detecta imediatamente o truncamento, descarta o arquivo `.part` e registra `FSAP-ERR-400-SIZE_MISMATCH` / `FSAP-ERR-503-CONNECTION_LOST`.
6. **Proteção contra Overflow de Envio:** Se o cliente tentar enviar mais bytes do que o `sizeBytes` declarado no `TRANSFER_REQUEST`, o servidor interrompe a leitura ao atingir `sizeBytes` (ou rejeita com `FSAP-ERR-400-SIZE_MISMATCH`), protegendo o disco local contra exaustão não autorizada.

---

## 4. Garantias de Consistência do Protocolo (Seção 5.8)

Para que o FSAP seja a fonte única de verdade entre Desktop e Android:
1. **Contratos Únicos em `commonMain`:** Todas as classes de mensagens (`@Serializable`) residirão no pacote `br.edu.fileshare.protocol.model` em `commonMain`. É proibido criar classes DTO duplicadas em `desktopMain` ou `androidMain`.
2. **Determinismo de Estados:** Nenhuma transição de estado pode ocorrer fora das classes de máquina de estados documentadas em [state-machine.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/state-machine.md).
3. **Padronização de Erros:** Qualquer rejeição HTTP (`4xx` ou `5xx`) emitida pelo servidor FSAP retorna obrigatoriamente o envelope `ERROR` especificado em [error-codes.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/error-codes.md).

---

## 5. Matriz de Rastreabilidade Completa (Seção 5.9)

A matriz abaixo estabelece a rastreabilidade ponta a ponta entre **Requisitos Funcionais**, **Eventos de Comunicação**, **Estados das Máquinas**, **Mensagens do Protocolo FSAP**, **Fluxos de Sequência**, **Casos de Teste de Conformidade** e **Tasks do Backlog**:

| Requisito | Eventos Associados | Estados Envolvidos | Mensagens FSAP | Fluxos (`communication-flows.md`) | Casos de Teste (`protocol-test-cases.md`) | Tasks do Backlog (`tasks/backlog.md`) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **RF-01** (Descoberta UDP Multicast) | `EV-01`, `EV-02`, `EV-04`, `EV-05` | `Node: INITIALIZING` → `DISCOVERING` → `AVAILABLE`; `Peer: DISCOVERED` | `DISCOVER`, `ANNOUNCE` | Fluxo 1, Fluxo 2 | `TC-FSAP-001`, `TC-FSAP-002`, `TC-FSAP-003` | `TASK-FSAP-001`, `TASK-DISC-001`, `TASK-DISC-002` |
| **RF-02** (Anúncio periódico e saída) | `EV-02`, `EV-03`, `EV-20` | `Node: AVAILABLE` → `SHUTTING_DOWN` → `STOPPED` | `ANNOUNCE`, `BYE` | Fluxo 2, Fluxo 10 | `TC-FSAP-002`, `TC-FSAP-005` | `TASK-DISC-002`, `TASK-DISC-003` |
| **RF-03** (Identificação `alias` e `deviceId`) | `EV-01`, `EV-05`, `EV-08`, `EV-09` | `Node: INITIALIZING`; `Peer: DISCOVERED`, `CONNECTED` | `ANNOUNCE`, `IDENTIFY` | Fluxo 1, Fluxo 2 | `TC-FSAP-001`, `TC-FSAP-006` | `TASK-FSAP-001`, `TASK-DISC-001` |
| **RF-04** (Listagem e expiração TTL) | `EV-05`, `EV-06`, `EV-07` | `Peer: DISCOVERED` ↔ `STALE` → `DISCONNECTED` | `ANNOUNCE`, `BYE` | Fluxo 2, Fluxo 10 | `TC-FSAP-004`, `TC-FSAP-005` | `TASK-DISC-004`, `TASK-DCLI-002`, `TASK-AND-003` |
| **RF-05** (Conexão manual por IP) | `EV-08`, `EV-09` | `Peer: DISCONNECTED` → `CONNECTING` → `CONNECTED` | `IDENTIFY`, `ERROR` | Fluxo 1 (Variante Manual) | `TC-FSAP-006`, `TC-FSAP-007` | `TASK-DISC-005`, `TASK-DCLI-003`, `TASK-AND-003` |
| **RF-06** (Seleção nativa de arquivos) | `EV-10` | `Transfer: CREATED` | *(Preparação local para `TRANSFER_REQUEST`)* | Fluxo 3 | `TC-FSAP-008` | `TASK-DCLI-004`, `TASK-AND-002` |
| **RF-07** (Negociação prévia de metadados) | `EV-10` | `Transfer: CREATED` → `PENDING_APPROVAL` | `TRANSFER_REQUEST`, `ERROR` | Fluxo 3, Fluxo 5 | `TC-FSAP-008`, `TC-FSAP-009`, `TC-FSAP-010` | `TASK-FSAP-002`, `TASK-XFER-002` |
| **RF-08** (Confirmação explícita do destinatário) | `EV-10`, `EV-11`, `EV-12` | `Transfer: PENDING_APPROVAL` → `ACCEPTED` \| `REJECTED` | `TRANSFER_ACCEPT`, `TRANSFER_REJECT` | Fluxo 3, Fluxo 4, Fluxo 5 | `TC-FSAP-008`, `TC-FSAP-011`, `TC-FSAP-012` | `TASK-DSRV-003`, `TASK-DCLI-005`, `TASK-AND-004` |
| **RF-09** (Streaming de dados binários) | `EV-13`, `EV-14` | `Transfer: ACCEPTED` → `TRANSFERRING` | `TRANSFER_CONTENT` | Fluxo 3, Fluxo 4 | `TC-FSAP-013`, `TC-FSAP-014`, `TC-FSAP-015` | `TASK-XFER-003`, `TASK-DSRV-004` |
| **RF-10** (Barra de progresso em tempo real) | `EV-14`, `EV-15` | `Transfer: TRANSFERRING` | `TRANSFER_STATUS` | Fluxo 3, Fluxo 4 | `TC-FSAP-016` | `TASK-XFER-004`, `TASK-DCLI-006`, `TASK-AND-005` |
| **RF-11** (Cancelamento de transferências) | `EV-17` | `Transfer: PENDING_APPROVAL` \| `TRANSFERRING` → `CANCELLED` | `TRANSFER_CANCEL` | Fluxo 6 | `TC-FSAP-017`, `TC-FSAP-018` | `TASK-XFER-005`, `TASK-DSRV-005` |
| **RF-12** (Verificação de integridade SHA-256) | `EV-16`, `EV-18` | `Transfer: TRANSFERRING` → `VERIFYING` → `COMPLETED` \| `FAILED` | `TRANSFER_COMPLETE`, `ERROR` | Fluxo 3, Fluxo 4, Fluxo 9 | `TC-FSAP-019`, `TC-FSAP-020` | `TASK-XFER-001`, `TASK-XFER-003`, `TASK-SEC-003` |
| **RF-13** (Tratamento de desconexões e erros) | `EV-18`, `EV-19` | `Transfer: *` → `FAILED`; `Peer: *` → `UNREACHABLE` | `ERROR` | Fluxo 7, Fluxo 8 | `TC-FSAP-021`, `TC-FSAP-022`, `TC-FSAP-023` | `TASK-FSAP-004`, `TASK-XFER-006` |
| **RF-14** (Armazenamento local seguro) | `EV-14`, `EV-16`, `EV-18` | `Transfer: TRANSFERRING` → `VERIFYING` → `COMPLETED` | `TRANSFER_CONTENT`, `TRANSFER_COMPLETE` | Fluxo 4, Fluxo 7, Fluxo 9 | `TC-FSAP-019`, `TC-FSAP-024`, `TC-FSAP-025` | `TASK-DSRV-004`, `TASK-AND-002`, `TASK-SEC-001` |
| **RF-15** (Múltiplas conexões simultâneas) | `EV-10` a `EV-16` (paralelos) | Múltiplas instâncias de `TransferState` | Todas as mensagens TCP | Fluxo 3 (Concorrente) | `TC-FSAP-026` | `TASK-DSRV-002`, `TASK-XFER-004`, `TASK-TEST-004` |
| **RF-16** (Servidor Desktop independente) | `EV-01`, `EV-02`, `EV-10` a `EV-20` | `Node`, `Peer` e `Transfer` completos em CLI | Todas as mensagens UDP e TCP | Todos os fluxos | `TC-FSAP-001` a `TC-FSAP-026` | `TASK-INF-002`, `TASK-DSRV-001`, `TASK-DSRV-002` |


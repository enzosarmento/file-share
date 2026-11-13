# Engenharia de Transferência Binária, Streaming e Integridade SHA-256

> **Status:** Especificação Técnica (Milestone M0)  
> **Relacionado a:** [protocol-fsap.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-fsap.md), [ADR-005](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-005-estrategia-streaming-e-integridade-sha256.md)

---

## 1. Visão Geral do Pipeline de Transferência

A transferência de arquivos no FileShare ocorre exclusivamente sobre conexões **HTTP/1.1 sobre TCP** após negociação prévia e aprovação explícita do destinatário. O pipeline é desenhado para atender três requisitos críticos simultâneos:
1. **Consumo de Memória Constante $O(1)$:** Nunca alocar arrays do tamanho do arquivo (`readBytes()` é proibido).
2. **Integridade Criptográfica Ponta a Ponta:** Validar tamanho exato (`sizeBytes`) e resumo **SHA-256** antes de disponibilizar o arquivo recebido.
3. **Atomicidade no Sistema de Arquivos:** Nenhum arquivo incompleto, cancelado ou corrompido pode permanecer visível no diretório de downloads do destinatário.

---

## 2. Etapas Detalhadas da Transferência

### Etapa 1: Preparação Local no Remetente (`CREATED`)
Quando o usuário seleciona um ou mais arquivos:
1. O adaptador de plataforma (`FileSource`) obtém o nome original sanitizado (`fileName`), o tamanho exato em bytes (`sizeBytes`) e um stream de leitura sequencial.
2. Uma coroutine em `Dispatchers.IO` lê o arquivo em blocos de **64 KB (`65536` bytes)** alimentando `MessageDigest.getInstance("SHA-256")` para produzir a string hexadecimal minúscula de 64 caracteres (`sha256`).
3. O manifesto completo é montado na mensagem `TRANSFER_REQUEST`.

### Etapa 2: Negociação e Autorização (`PENDING_APPROVAL` → `ACCEPTED`)
1. O cliente envia `POST /fsap/v1/transfers/prepare` com o manifesto JSON.
2. O servidor receptor valida:
   - Se a versão do protocolo é `1.x`;
   - Se todos os nomes de arquivos respeitam as regras de segurança (sem *Path Traversal*);
   - Se há espaço livre suficiente no diretório de destino (`usableSpace >= totalBytes + 16 MiB` de margem). Caso contrário, responde imediatamente com `507 Insufficient Storage` (`FSAP-ERR-507-INSUFFICIENT_STORAGE`).
3. O servidor notifica a camada de apresentação do destinatário e suspende a coroutine da requisição (com `withTimeoutOrNull(60_000L)`) aguardando a decisão do usuário:
   - **Aprovado:** Gera um `transferToken` aleatório de 256 bits (codificado em hex/base64url), muda o estado para `ACCEPTED` e retorna HTTP `200 OK` (`TRANSFER_ACCEPT`).
   - **Recusado:** Muda o estado para `REJECTED` e retorna HTTP `403 Forbidden` (`TRANSFER_REJECT`).
   - **Timeout (60 s):** Muda o estado para `FAILED` e retorna HTTP `408 Request Timeout` (`FSAP-ERR-408-APPROVAL_TIMEOUT`).

### Etapa 3: Transmissão em Streaming (`TRANSFERRING`)
Para cada arquivo aceito no lote, o cliente executa `PUT /fsap/v1/transfers/{transferId}/files/{fileId}/content`:
- **No Remetente:**
  - Abre o stream de origem do arquivo e escreve no `ByteWriteChannel` do Ktor Client CIO em blocos de `65536` bytes.
  - A cada chunk enviado, atualiza `bytesSent` e calcula a velocidade média móvel (em bytes/s), emitindo atualizações para a UI a cada `100 ms`.
- **No Destinatário:**
  - Cria o arquivo temporário `<transferId>_<fileId>.part` no diretório de staging.
  - Inicializa `val digest = MessageDigest.getInstance("SHA-256")`.
  - Lê do `ByteReadChannel` do Ktor Server CIO em um buffer de `65536` bytes com timeout de inatividade por leitura de **15 segundos** (`T_STREAM_READ_TIMEOUT`):
    1. Atualiza o cálculo do hash: `digest.update(buffer, 0, bytesRead)`
    2. Escreve no disco: `partOutputStream.write(buffer, 0, bytesRead)`
    3. Atualiza `bytesReceived += bytesRead` e emite progresso para a UI do receptor.

### Etapa 4: Verificação SHA-256 e Commit Atômico (`VERIFYING` → `COMPLETED`)
Assim que o stream do arquivo encerra:
1. O servidor verifica se `bytesReceived == expectedSizeBytes`. Se divergir, deleta o `.part` e retorna `400 Bad Request` (`FSAP-ERR-400-SIZE_MISMATCH`).
2. O servidor finaliza o cálculo do hash: `val calculatedSha256 = digest.digest().toHexString()`.
3. Compara `calculatedSha256.equals(expectedSha256, ignoreCase = true)`:
   - **Se divergir:** Deleta imediatamente `<transferId>_<fileId>.part`, muda a sessão para `FAILED` e responde `422 Unprocessable Entity` (`FSAP-ERR-422-HASH_MISMATCH`).
   - **Se coincidir:**
     - Fecha e sincroniza (`flush`/`fd.sync()`) o arquivo `.part`;
     - Resolve colisões de nome no diretório de destino (se `relatorio.pdf` já existir, resolve para `relatorio (1).pdf` sem sobrescrever arquivos existentes do usuário);
     - Move atomicamente (`Files.move` no Desktop ou publicação via `MediaStore`/`SAF` no Android) o arquivo `.part` para o destino final;
     - Responde HTTP `200 OK` no `PUT` do arquivo.
4. Após o último arquivo do lote ser verificado, o cliente envia `POST /fsap/v1/transfers/{transferId}/complete` (`TRANSFER_COMPLETE`), consolidando a sessão no estado terminal `COMPLETED`.

---

## 3. Concorrência e Múltiplas Conexões Simultâneas (RF-15)

- O Servidor FSAP mantém um mapa concorrente (`ConcurrentHashMap<String, TransferSessionContext>`) indexado por `transferId`.
- Cada sessão possui seu próprio `CoroutineScope` filho (`SupervisorJob`) e arquivo `.part` exclusivo (`<transferId>_<fileId>.part`), permitindo que o servidor receba transferências de múltiplos dispositivos simultaneamente (limitado por padrão a `MAX_CONCURRENT_TRANSFERS = 5` sessões ativas para evitar contenção excessiva de disco).
- Caso uma 6ª transferência seja solicitada simultaneamente, o servidor pode enfileirar a aprovação ou responder com `TRANSFER_REJECT` (`reasonCode = "BUSY"`).


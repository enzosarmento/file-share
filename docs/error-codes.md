# Catálogo Padronizado de Erros — Protocolo FSAP/1.0

> **Status:** Especificação Normativa (Milestone M0 — Seção 5.7)  
> **Escopo:** Padronização de códigos, categorias, status HTTP equivalentes, causas e comportamentos obrigatórios de recuperação para todas as falhas do protocolo FSAP/1.0.

---

## 1. Princípios de Tratamento de Erros no FSAP

1. **Códigos Canônicos Imutáveis:** O cliente e o servidor nunca devem tomar decisões de fluxo baseadas no texto livre do campo `message`. Toda lógica de tratamento e tradução na UI deve basear-se exclusivamente no código canônico `errorCode` (formato `FSAP-ERR-<HTTP>-<IDENTIFICADOR>`).
2. **Limpeza Garantida de Recursos (`Clean-on-Error`):** Qualquer erro com `recoverable: false` ocorrido durante `TRANSFERRING` ou `VERIFYING` obriga o destinatário a remover imediatamente todos os arquivos temporários `.part` associados antes ou no instante de emitir o envelope `ERROR`.
3. **Silêncio em Multicast UDP:** Erros de formato recebidos no socket UDP (`239.255.70.83:53318`) nunca geram resposta de rede; são registrados apenas no log local. O envelope `ERROR` trafega exclusivamente em respostas HTTP/TCP.

---

## 2. Tabela Completa de Códigos de Erro do FSAP/1.0

| Código Canônico (`errorCode`) | Categoria | HTTP Status | Descrição Técnica e Condição de Disparo | Estado Resultante (`TransferState`) | Comportamento Esperado (Servidor e Cliente) |
| :--- | :---: | :---: | :--- | :---: | :--- |
| **`FSAP-ERR-400-MALFORMED_MESSAGE`** | Mensagem inválida (`VALIDATION`) | `400` | JSON sintaticamente inválido, codificação UTF-8 corrompida, envelope FSAP ausente ou campos obrigatórios nulos/com tipo incorreto. | `FAILED` *(se vinculado a sessão)* | **Servidor:** Rejeita imediatamente sem alocar sessão. **Cliente:** Registra erro de contrato e aborta chamada. |
| **`FSAP-ERR-400-INVALID_FILENAME`** | Nome de arquivo inválido (`VALIDATION`) | `400` | O campo `fileName` está vazio, excede 255 caracteres, contém caracteres proibidos (`/`, `\`, `\u0000`), sequências de *Path Traversal* (`..`) ou nomes reservados do SO (`CON`, `NUL`, `AUX`). | `FAILED` | **Servidor:** Rejeita o `TRANSFER_REQUEST` inteiro antes de exibir modal ao usuário. **Cliente:** Informa qual arquivo possui nome inválido. |
| **`FSAP-ERR-400-SIZE_MISMATCH`** | Mensagem inválida / Truncamento (`VALIDATION`) | `400` | O número de bytes enviados no stream `TRANSFER_CONTENT` difere do `sizeBytes` aprovado no manifesto ou do cabeçalho `Content-Length`. | `FAILED` | **Servidor:** Deleta o arquivo `.part` e invalida a sessão. **Cliente:** Exibe falha de transmissão incompleta. |
| **`FSAP-ERR-401-UNAUTHORIZED_TRANSFER`** | Transferência não autorizada (`AUTH`) | `401` | Tentativa de enviar `TRANSFER_CONTENT`, `TRANSFER_STATUS` ou `TRANSFER_COMPLETE` sem o cabeçalho `X-FSAP-Transfer-Token` ou com token inválido/expirado (`> 30 s` ocioso). | `FAILED` *(na sessão alvo, se aplicável)* | **Servidor:** Bloqueia leitura do corpo binário imediatamente. **Cliente:** Não deve reenviar sem novo `TRANSFER_REQUEST`. |
| **`FSAP-ERR-403-TRANSFER_REJECTED`** | Conexão / Transferência recusada (`AUTH`) | `403` | O usuário destinatário clicou em "Recusar" no diálogo de confirmação ou o nó receptor está com recepção bloqueada. (Também representado pelo envelope `TRANSFER_REJECT`). | `REJECTED` | **Servidor:** Descarta a proposta. **Cliente:** Exibe ao usuário que o destinatário recusou o envio. |
| **`FSAP-ERR-404-DEVICE_NOT_FOUND`** | Dispositivo não encontrado (`NETWORK`) | `404` | O endereço IP:Porta informado manualmente não responde ao handshake `IDENTIFY` ou o `deviceId` esperado no destino mudou. | `FAILED` (`Peer: DISCONNECTED`) | **Cliente:** Remove ou marca o par como indisponível e solicita verificação do IP/rede. |
| **`FSAP-ERR-404-FILE_NOT_FOUND`** | Arquivo inexistente (`STORAGE`) | `404` | No cliente: arquivo selecionado foi removido/movido antes do envio. No servidor: `transferId` ou `fileId` informado na URL de `TRANSFER_CONTENT` não existe no manifesto aprovado. | `FAILED` | **Servidor/Cliente:** Aborta a transferência e notifica qual arquivo não foi localizado. |
| **`FSAP-ERR-408-APPROVAL_TIMEOUT`** | Timeout (`TIMEOUT`) | `408` | O usuário destinatário não aceitou nem recusou a solicitação `TRANSFER_REQUEST` dentro de `T_APPROVAL_TIMEOUT` (**60 segundos**). | `FAILED` | **Servidor:** Fecha o diálogo pendente e descarta a proposta. **Cliente:** Informa que o tempo de resposta do destinatário expirou. |
| **`FSAP-ERR-408-STREAM_TIMEOUT`** | Timeout (`TIMEOUT`) | `408` | Nenhum byte foi lido ou escrito no socket TCP durante `T_STREAM_READ_TIMEOUT` (**15 segundos**) no estado `TRANSFERRING`. | `FAILED` | **Servidor:** Fecha o canal, apaga o arquivo `.part`. **Cliente:** Cancela o job de envio e informa perda de conexão por inatividade. |
| **`FSAP-ERR-409-INVALID_STATE_TRANSITION`** | Violação de máquina de estados (`STATE`) | `409` | Recebimento de mensagem incompatível com o estado atual da sessão (ex: `TRANSFER_COMPLETE` antes do envio de todos os arquivos, ou `TRANSFER_CONTENT` em sessão já `COMPLETED`/`CANCELLED`). | Permanece em estado terminal ou vai para `FAILED` | **Servidor:** Rejeita a operação sem alterar arquivos já consolidados. **Cliente:** Sincroniza estado via `TRANSFER_STATUS` ou encerra. |
| **`FSAP-ERR-413-FILE_TOO_LARGE`** | Arquivo muito grande (`STORAGE`) | `413` | O `sizeBytes` de um arquivo excede o limite do protocolo (`64 GiB`) ou o `totalBytes` do manifesto excede a política configurada no receptor. | `FAILED` | **Servidor:** Rejeita já na fase de `TRANSFER_REQUEST`. **Cliente:** Informa limite excedido ao usuário. |
| **`FSAP-ERR-422-HASH_MISMATCH`** | Falha de integridade (`INTEGRITY`) | `422` | O hash **SHA-256** calculado pelo destinatário ao final do recebimento dos bytes diverge do `sha256` declarado pelo remetente. | `FAILED` | **Servidor:** **Exclui imediatamente o arquivo `.part` corrompido** e retorna erro 422. **Cliente:** Alerta o usuário sobre falha de integridade e sugere nova tentativa. |
| **`FSAP-ERR-499-TRANSFER_CANCELLED`** | Transferência cancelada (`STATE`) | `499` / `200` | A transferência foi interrompida por solicitação explícita de uma das partes via `TRANSFER_CANCEL`. | `CANCELLED` | **Ambos:** Interrompem leitura/escrita imediatamente, removem arquivos `.part` e exibem status "Cancelada". |
| **`FSAP-ERR-500-INTERNAL_ERROR`** | Erro interno (`INTERNAL`) | `500` | Exceção inesperada não tratada no processamento do servidor ou falha interna do motor de armazenamento (`IOException` genérica). | `FAILED` | **Servidor:** Faz log completo da stacktrace, limpa recursos temporários da sessão e retorna mensagem segura sem expor caminhos sensíveis do sistema operacional. |
| **`FSAP-ERR-503-CONNECTION_LOST`** | Conexão recusada / Desconexão (`NETWORK`) | `503` | Socket TCP recusado (`ECONNREFUSED`), resetado (`ECONNRESET`) ou encerrado prematuramente (`EOFException`) antes do fim do fluxo. | `FAILED` (`Peer: DISCONNECTED`) | **Ambos:** Limpam recursos locais (`.part` no receptor), atualizam estado do par e notificam desconexão inesperada. |
| **`FSAP-ERR-505-UNSUPPORTED_VERSION`** | Versão incompatível (`VALIDATION`) | `505` | A versão `MAJOR` do protocolo FSAP informada pelo par é diferente da versão suportada (`1.x`). | `FAILED` | **Ambos:** Impedem o início da transferência e informam incompatibilidade de versão do aplicativo. |
| **`FSAP-ERR-507-INSUFFICIENT_STORAGE`** | Falha de armazenamento (`STORAGE`) | `507` | Espaço livre no diretório de destino do receptor é menor que `totalBytes` (verificado no `TRANSFER_REQUEST` ou durante escrita em disco no `TRANSFER_CONTENT`). | `FAILED` | **Servidor:** Apaga arquivos `.part` da sessão e retorna HTTP 507. **Cliente:** Informa ao remetente que o dispositivo de destino não possui espaço livre suficiente. |

---

## 3. Estrutura JSON Normativa do Envelope `ERROR`

Sempre que o servidor retornar qualquer um dos erros acima sobre HTTP, o corpo da resposta deverá seguir exatamente este contrato:

```json
{
  "protocol": "FSAP",
  "version": "1.0",
  "type": "ERROR",
  "messageId": "d3b07384-d113-4e09-8386-4f63274e5c88",
  "timestamp": "2026-11-12T20:30:00Z",
  "senderId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
  "payload": {
    "errorCode": "FSAP-ERR-422-HASH_MISMATCH",
    "httpStatus": 422,
    "category": "INTEGRITY",
    "message": "Verificacao SHA-256 falhou para o arquivo 'video-aula.mp4'. Esperado: 9f86d08..., Calculado: 4b22777...",
    "transferId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "fileId": "file-001",
    "recoverable": false
  }
}
```


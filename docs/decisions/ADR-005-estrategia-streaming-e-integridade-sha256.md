# ADR-005: Estratégia de Streaming Binário em Chunks (64 KB), Arquivos `.part` e Integridade SHA-256

- **Status:** Proposta Técnica (Pendente de Aprovação da Equipe antes da M2)
- **Data:** 2026-11-12
- **Contexto:** Milestone M0 — Planejamento e Arquitetura

## Contexto
A transferência de arquivos no FileShare deve suportar arquivos de qualquer tamanho (de poucos kilobytes a vários gigabytes) sem esgotar a memória heap da JVM ou do Android, garantindo que arquivos corrompidos ou interrompidos nunca sejam entregues como válidos ao usuário.

## Decisão Proposta
1. **Tamanho de Buffer de Streaming:** Utilizar buffers reutilizáveis de **64 KB (`65536` bytes)** no loop de leitura (`ByteReadChannel` / `InputStream`) e escrita (`OutputStream` / `FileChannel`).
2. **Cálculo de SHA-256 Incremental (Zero-Copy Digest):**
   - No **Remetente**: Calcular o hash SHA-256 do arquivo em streaming durante a fase de preparação do `TRANSFER_REQUEST` (para incluí-lo no manifesto assinado pelo usuário) e validar que o número de bytes lidos no envio coincide com `sizeBytes`.
   - No **Destinatário**: Instanciar `MessageDigest.getInstance("SHA-256")` no início da recepção de `TRANSFER_CONTENT` e alimentar `digest.update(buffer, 0, bytesRead)` a cada chunk de 64 KB recebido da rede antes de gravá-lo no disco. Ao término do stream, comparar o digest calculado (em hexadecimal minúsculo de 64 caracteres) com o `sha256` declarado no `TRANSFER_REQUEST` e no cabeçalho `X-FSAP-File-SHA256`.
3. **Escrita Atômica com Arquivo Temporário (`.part`):**
   - Enquanto o estado for `TRANSFERRING` ou `VERIFYING`, os bytes são gravados em `<transferId>_<fileId>.part`.
   - Se o tamanho bater e o SHA-256 for idêntico, o arquivo `.part` é movido/renomeado atomicamente para o nome final sanitizado.
   - Se ocorrer divergência de hash (`FSAP-ERR-422-HASH_MISMATCH`), tamanho incorreto (`FSAP-ERR-400-SIZE_MISMATCH`), cancelamento ou queda de conexão, o arquivo `.part` é deletado imediatamente no bloco `finally`.
4. **Throttling de Progresso:** Emitir atualizações para o `StateFlow` da UI no máximo a cada **100 ms** (ou a cada 1% de avanço) para evitar sobrecarga de recomposição no Compose Multiplatform.

## Consequências
- **Positivas:** Consumo de memória RAM constante $O(1)$ independentemente do tamanho do arquivo e garantia forte de integridade criptográfica.


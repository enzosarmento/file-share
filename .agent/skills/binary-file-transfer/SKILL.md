---
name: binary-file-transfer
description: >-
  Use esta skill ao implementar a leitura e escrita de arquivos binarios em streaming
  (chunks de 64 KB), calculo incremental de hash SHA-256, gerenciamento de arquivos
  temporarios (.part), commit atomico e rastreamento de progresso no FileShare.
---

# Skill: Binary File Transfer & SHA-256 Streaming (`binary-file-transfer`)

## 1. Nome
`binary-file-transfer`

## 2. Descrição
Especialização na engenharia de transferência de dados binários em streaming com memória constante $O(1)$, verificação criptográfica de integridade **SHA-256** em tempo real e escrita atômica com arquivos `.part`.

## 3. Objetivo
Permitir a transferência íntegra de arquivos de qualquer tamanho (0 bytes a dezenas de gigabytes) entre Desktop e Android sem carregar o arquivo inteiro na memória RAM e sem deixar artefatos corrompidos em disco em caso de erro ou cancelamento.

## 4. Conhecimentos Necessários
- Streaming binário com Ktor (`ByteReadChannel`, `ByteWriteChannel`) e `java.io.InputStream` / `OutputStream` / `java.nio.file.Files`.
- Cálculo incremental de hash via `java.security.MessageDigest.getInstance("SHA-256")`.
- Operações atômicas de sistema de arquivos e limpeza garantida em blocos `finally`.

## 5. Contexto Técnico
- Especificação detalhada em [docs/transfer-flow.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/transfer-flow.md) e [ADR-005](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-005-estrategia-streaming-e-integridade-sha256.md).
- Tamanho padrão de buffer por fluxo: **`65536` bytes (64 KB)**.
- Formato do resumo SHA-256: string hexadecimal minúscula de exatos **64 caracteres**.

## 6. Escopo de Atuação
- Implementação de `FileSource`, `FileSink`, `IncrementalSha256Channel`, `TransferProgressTracker` e `DesktopNioFileStorage`.
- Lógica de validação de `bytesReceived == expectedSizeBytes` e `calculatedSha256 == expectedSha256` na transição `TRANSFERRING` → `VERIFYING` → `COMPLETED` / `FAILED`.

## 7. Arquivos e Módulos que Pode Modificar
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/streaming/**`
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/transfer/**`
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/storage/**`
- `composeApp/src/desktopMain/kotlin/br/edu/fileshare/desktop/storage/**`

## 8. Restrições
1. **Proibido `readBytes()`:** É terminantemente proibido invocar `File.readBytes()` ou `InputStream.readAllBytes()` para conteúdo de arquivos transferidos (`RNF-01`).
2. **Proibido Expor Arquivo Antes do SHA-256:** Todo arquivo em recepção deve usar extensão temporária `<transferId>_<fileId>.part` e só pode ser movido para o nome final se o tamanho e o hash SHA-256 coincidirem com o manifesto (`RNF-07`).
3. **Exclusão Imediata em Falha/Cancelamento:** Qualquer exceção, cancelamento ou `HASH_MISMATCH` deve deletar o arquivo `.part` dentro do bloco `finally`.

## 9. Dependências
- `fsap-protocol`
- `network-security`

## 10. Critérios de Conclusão
- Casos de teste `TC-FSAP-014` a `TC-FSAP-022` e `TC-FSAP-025` aprovados.
- Nenhum arquivo `.part` remanescente após testes de sucesso, cancelamento ou corrupção de hash.


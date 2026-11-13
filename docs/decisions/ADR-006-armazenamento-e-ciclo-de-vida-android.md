# ADR-006: Armazenamento Seguro (SAF / MediaStore) e Ciclo de Vida com `ForegroundService` no Android

- **Status:** Proposta Técnica (Pendente de Aprovação da Equipe antes da M4)
- **Data:** 2026-11-12
- **Contexto:** Milestone M0 — Planejamento e Arquitetura

## Contexto
No Android (API 26 a 35), aplicativos sofrem restrições severas de *Scoped Storage* (proibição de escrita direta em caminhos arbitrários do armazenamento externo) e restrições de execução em segundo plano (suspensão de sockets e perda de `MulticastLock` quando a Activity sai do topo).

## Decisão Proposta
1. **Seleção de Arquivos para Envio (Android Cliente):**
   - Utilizar o contrato oficial `ActivityResultContracts.OpenMultipleDocuments()` do *Storage Access Framework* (SAF), operando sobre `Uri`s via `ContentResolver.openInputStream(uri)` e consultando `OpenableColumns.DISPLAY_NAME` e `OpenableColumns.SIZE`. Nenhuma permissão invasiva (`MANAGE_EXTERNAL_STORAGE`) será solicitada.
2. **Armazenamento de Arquivos Recebidos (Android Servidor):**
   - Receber o arquivo temporário `.part` dentro de `context.cacheDir.resolve("incoming")` (armazenamento interno privado do app, rápido e sem necessidade de permissão de runtime).
   - Após a verificação do SHA-256 (`VERIFYING` → `COMPLETED`), publicar o arquivo validado na coleção pública `Downloads` via `MediaStore.Downloads` (Android 10 / API 29+) ou gravar na pasta escolhida pelo usuário via `OpenDocumentTree` (`DocumentFile`).
3. **Execução do Servidor HTTP e Descoberta UDP no Android:**
   - Encapsular o ciclo de vida do nó FSAP Android em um `ForegroundService` (`foregroundServiceType="dataSync"`), exibindo uma notificação contínua enquanto o usuário habilitar o modo "Visível / Recebendo na Rede Local" ou durante transferências ativas.
   - Gerenciar o `WifiManager.MulticastLock` exclusivamente dentro do ciclo de vida ativo desse serviço para evitar drenagem de bateria quando o app estiver fechado.

## Consequências
- **Positivas:** Conformidade com as políticas modernas do Android 10–15, sem necessidade de permissões amplas negadas pelo sistema, e estabilidade de conexões TCP durante transferências longas.


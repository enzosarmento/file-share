---
name: android-file-system
description: >-
  Use esta skill ao implementar selecao de arquivos no Android (Storage Access Framework /
  OpenMultipleDocuments), leitura via ContentResolver, gravacao em cacheDir e publicacao
  em MediaStore.Downloads, alem do FsapForegroundService no Android.
---

# Skill: Android File System, SAF & Lifecycle (`android-file-system`)

## 1. Nome
`android-file-system`

## 2. Descrição
Diretrizes técnicas para acesso seguro ao armazenamento no Android (API 26–35) utilizando *Storage Access Framework* (SAF), `ContentResolver` e `MediaStore.Downloads`, além da execução contínua do nó de rede via `ForegroundService`.

## 3. Objetivo
Integrar o cliente e o servidor FSAP ao ecossistema Android respeitando as regras de *Scoped Storage* e as restrições de execução em segundo plano sem exigir permissões invasivas como `MANAGE_EXTERNAL_STORAGE`.

## 4. Conhecimentos Necessários
- Android *Storage Access Framework* (`ActivityResultContracts.OpenMultipleDocuments`, `OpenableColumns.DISPLAY_NAME`, `OpenableColumns.SIZE`).
- Leitura e escrita por streams via `ContentResolver` e `MediaStore.Downloads` (`IS_PENDING = 1` → `0`).
- Android `ForegroundService` (`foregroundServiceType="dataSync"`), canais de notificação (`NotificationChannel`) e permissão `POST_NOTIFICATIONS`.

## 5. Contexto Técnico
- Especificação em [ADR-006](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-006-armazenamento-e-ciclo-de-vida-android.md) e [docs/architecture.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/architecture.md) (Seção 3.2).
- `minSdk = 26` (Android 8.0) e `targetSdk = 35` (Android 15).

## 6. Escopo de Atuação
- Implementação de `AndroidSafFileSource` e `AndroidFileSink` em `androidMain`.
- Implementação de `FsapForegroundService` e integração com `MainActivity.kt`.

## 7. Arquivos e Módulos que Pode Modificar
- `composeApp/src/androidMain/kotlin/br/edu/fileshare/android/**`
- `composeApp/src/androidMain/AndroidManifest.xml`
- `composeApp/src/androidUnitTest/kotlin/br/edu/fileshare/android/**`

## 8. Restrições
1. **Staging Obrigatório em `cacheDir`:** Durante o estado `TRANSFERRING`, os bytes recebidos no Android devem ser gravados em `context.cacheDir.resolve("incoming/<transferId>_<fileId>.part")`. Jamais publicar diretamente no `MediaStore.Downloads` antes da validação do SHA-256 em `VERIFYING`.
2. **Proibido `File(uri.path)`:** Nunca assumir que uma `Uri` `content://` retornada pelo SAF possui caminho de arquivo POSIX direto; sempre usar `ContentResolver.openInputStream(uri)`.
3. **Ciclo de Vida do `ForegroundService`:** O serviço deve ser encerrado imediatamente quando o usuário desligar a recepção e não houver transferências ativas.

## 9. Dependências
- `binary-file-transfer`
- `udp-device-discovery`
- `ktor-http-server`

## 10. Critérios de Conclusão
- Envio e recebimento de arquivos funcionando entre Android e Desktop (`M4` e `M5`) com arquivos válidos disponíveis no aplicativo "Arquivos / Downloads" do Android.


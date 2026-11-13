---
name: network-security
description: >-
  Use esta skill ao implementar sanitizacao de nomes de arquivos contra Path Traversal,
  geracao/validacao de transferToken efemero, limites de payload contra DoS, politicas de
  nao-sobrescrita ou transporte TLS opcional no projeto FileShare.
---

# Skill: Network & File System Security (`network-security`)

## 1. Nome
`network-security`

## 2. Descrição
Especialização na aplicação dos controles de segurança de rede local e proteção do sistema de arquivos do destinatário no protocolo FSAP/1.0.

## 3. Objetivo
Proteger dispositivos Desktop e Android contra escrita arbitrária de arquivos (*Path Traversal*), envio de arquivos não autorizados na LAN, sobrescrita acidental de arquivos do usuário e ataques de exaustão de memória ou disco.

## 4. Conhecimentos Necessários
- Sanitização de caminhos e nomes de arquivos multiplataforma (Linux, Windows e Android).
- Geração de tokens criptográficos (`java.security.SecureRandom`) e comparação em tempo constante (`MessageDigest.isEqual`).
- Limites de leitura de stream e cotas de armazenamento.

## 5. Contexto Técnico
- Especificação normativa em [docs/security.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/security.md) e [docs/error-codes.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/error-codes.md).

## 6. Escopo de Atuação
- Implementação de `FileNameSanitizer` e resolução de colisões de nome (`arquivo (1).ext`) em `commonMain`.
- Implementação de `TransferTokenManager` (geração, validação e expiração `T_TOKEN_IDLE_TTL = 30 s`).
- Guardas de tamanho máximo de JSON (`256 KB`), tamanho máximo de datagrama UDP (`1024 B`) e corte imediato de stream que exceda `sizeBytes`.

## 7. Arquivos e Módulos que Pode Modificar
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/storage/FileNameSanitizer.kt`
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/server/TransferTokenManager.kt`
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/server/**`
- `composeApp/src/commonTest/kotlin/br/edu/fileshare/storage/**`

## 8. Restrições
1. **Verificação Dupla contra Path Traversal:** Além de validar a string `fileName` com `FileNameSanitizer`, o `FileSink` deve verificar que `targetFile.canonicalPath.startsWith(baseDownloadDir.canonicalPath)` antes de abrir o arquivo no disco.
2. **Proibido Sobrescrever Arquivos Existentes:** Se o arquivo de destino já existir, gerar nome incremental seguro (`nome (1).ext`).
3. **Proibido Aceitar Upload Sem Token:** Nenhuma rota `TRANSFER_CONTENT` pode processar bytes sem antes validar o cabeçalho `X-FSAP-Transfer-Token`.

## 9. Dependências
- `fsap-protocol`

## 10. Critérios de Conclusão
- Casos `TC-FSAP-009` (Path Traversal), `TC-FSAP-010` (Espaço insuficiente), `TC-FSAP-013` (Token ausente/inválido) e `TC-FSAP-024` (Não-sobrescrita) aprovados nos testes automatizados.


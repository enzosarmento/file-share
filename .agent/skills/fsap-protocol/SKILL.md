---
name: fsap-protocol
description: >-
  Use esta skill sempre que implementar, validar ou modificar DTOs, envelopes JSON,
  cabecalhos HTTP, maquinas de estados (LocalNodeState, PeerConnectionState, TransferState),
  eventos de comunicacao ou codigos de erro do protocolo FSAP/1.0.
---

# Skill: FSAP Application Protocol (`fsap-protocol`)

## 1. Nome
`fsap-protocol`

## 2. Descrição
Guardiã da conformidade normativa do protocolo da camada de aplicação **FSAP/1.0 (FileShare Application Protocol)** em todas as camadas do projeto.

## 3. Objetivo
Garantir que 100% das mensagens, eventos, temporizadores, cabeçalhos, códigos de erro e transições de estado implementados em `commonMain` sigam fielmente a especificação oficial, assegurando interoperabilidade total entre qualquer cliente e servidor compatível.

## 4. Conhecimentos Necessários
- Serialização tipada com `kotlinx.serialization` (`@Serializable`, `@SerialName`, `Json`).
- Modelagem de máquinas de estados finitos (FSM) determinísticas e thread-safe em Kotlin (`Mutex`, `StateFlow`).
- Especificação normativa do FSAP/1.0.

## 5. Contexto Técnico
- **Fontes da Verdade Obrigatórias:**
  - [docs/protocol-fsap.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-fsap.md)
  - [docs/state-machine.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/state-machine.md)
  - [docs/protocol-messages.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-messages.md)
  - [docs/communication-flows.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/communication-flows.md)
  - [docs/error-codes.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/error-codes.md)

## 6. Escopo de Atuação
- Implementação do pacote `br.edu.fileshare.protocol.*` em `commonMain`:
  - `model/` (Envelopes e payloads das 12 mensagens: `ANNOUNCE`, `DISCOVER`, `BYE`, `IDENTIFY`, `TRANSFER_REQUEST`, `TRANSFER_ACCEPT`, `TRANSFER_REJECT`, `TRANSFER_CONTENT`, `TRANSFER_STATUS`, `TRANSFER_CANCEL`, `TRANSFER_COMPLETE`, `ERROR`)
  - `statemachine/` (`LocalNodeStateMachine`, `PeerConnectionStateMachine`, `TransferStateMachine`)
  - `validation/` (`FsapMessageValidator`)
  - `error/` (`FsapErrorCode`, `FsapProtocolException`)

## 7. Arquivos e Módulos que Pode Modificar
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/protocol/**`
- `composeApp/src/commonTest/kotlin/br/edu/fileshare/protocol/**`

## 8. Restrições
1. **Proibido Alterar Contratos Sem Atualizar a Especificação:** Nenhum campo obrigatório, nome de tipo ou código de erro pode divergir de `docs/protocol-messages.md` e `docs/error-codes.md`.
2. **Proibido Estado Implícito:** Nenhuma transição de estado pode ocorrer fora das máquinas de estados formais. Transições inválidas devem obrigatoriamente resultar em `FSAP-ERR-409-INVALID_STATE_TRANSITION`.
3. **Compatibilidade de Versão:** O parser JSON deve ser configurado com `ignoreUnknownKeys = true` e o validador deve exigir `protocol == "FSAP"` e `version` iniciando com `"1."`.

## 9. Dependências
- Aprovação de `TASK-ARC-001`.

## 10. Critérios de Conclusão
- 100% dos DTOs, validadores, erros e transições de estados cobertos por testes unitários em `commonTest` (`TASK-TEST-001`).


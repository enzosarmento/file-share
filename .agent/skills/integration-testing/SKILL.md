---
name: integration-testing
description: >-
  Use esta skill ao escrever ou executar testes unitarios em commonTest, testes de
  conformidade do protocolo FSAP (TC-FSAP-001 a TC-FSAP-026) em desktopTest ou
  roteiros de interoperabilidade entre Desktop e Android.
---

# Skill: Integration & Protocol Conformance Testing (`integration-testing`)

## 1. Nome
`integration-testing`

## 2. Descrição
Guia para implementação e execução da pirâmide de testes do FileShare: testes unitários em `commonTest`, suíte de conformidade de protocolo FSAP (`TC-FSAP-001` a `TC-FSAP-026`) e testes de carga/interoperabilidade multi-nó.

## 3. Objetivo
Comprovar de forma automatizada e reprodutível que as implementações Desktop e Android obedecem integralmente à especificação do protocolo FSAP/1.0, tratam falhas de rede/corrupção de dados e suportam múltiplas conexões simultâneas.

## 4. Conhecimentos Necessários
- `kotlin.test` e `kotlinx-coroutines-test` (`runTest`, `TestScope`, `advanceTimeBy`).
- `ktor-server-test-host` (`testApplication`) e testes de integração em loopback (`127.0.0.1` com portas efêmeras).
- Injeção de falhas em canais de I/O (corrupção de bytes para teste de SHA-256, truncamento de stream e atraso para teste de timeout).

## 5. Contexto Técnico
- Especificação da suíte de conformidade em [docs/protocol-test-cases.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-test-cases.md) e estratégia em [docs/testing.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/testing.md).

## 6. Escopo de Atuação
- Implementação de testes em `commonTest`, `desktopTest` e `androidUnitTest`.
- Criação de *fakes* reutilizáveis (`FakeMulticastTransport`, `InMemoryFileSink`, `FaultInjectingByteReadChannel`, `AutoApprovalHandler`).

## 7. Arquivos e Módulos que Pode Modificar
- `composeApp/src/commonTest/kotlin/br/edu/fileshare/**`
- `composeApp/src/desktopTest/kotlin/br/edu/fileshare/**`
- `composeApp/src/androidUnitTest/kotlin/br/edu/fileshare/**`

## 8. Restrições
1. **Isolamento Total de Testes:** Testes que gravam em disco devem usar diretórios temporários exclusivos (`Files.createTempDirectory`) e removê-los no `@AfterTest`.
2. **Portas Efêmeras:** Testes de socket/servidor em `desktopTest` nunca devem depender de portas fixas que possam colidir; usar `port = 0` ou `testApplication`.
3. **Proibido `Thread.sleep`:** Em testes de TTL (18 s) e timeouts (15 s / 60 s), utilizar o relógio virtual de `runTest` ou valores de timeout injetáveis na configuração de teste para que a suíte inteira execute em poucos segundos.

## 9. Dependências
- `fsap-protocol`
- `ktor-http-server`
- `udp-device-discovery`
- `binary-file-transfer`

## 10. Critérios de Conclusão
- Todos os 26 casos de teste de conformidade (`TC-FSAP-001` a `TC-FSAP-026`) implementados e passando com `./gradlew :composeApp:desktopTest`.


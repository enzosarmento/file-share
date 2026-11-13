# ADR-004: Escolha de Ktor (CIO Engine) para HTTP/TCP e `java.net.MulticastSocket` para UDP

- **Status:** Aprovada (`TASK-ARC-002` — Ratificada para implementação M1+)
- **Data:** 2026-11-12
- **Contexto:** Milestone M0 — Planejamento e Arquitetura

## Contexto
Precisamos decidir quais bibliotecas/APIs concretas implementarão os motores HTTP/TCP e os sockets UDP Multicast no Desktop (Kotlin/JVM) e no Android, respeitando o uso de Kotlin Coroutines e Kotlin Multiplatform.

## Opções Consideradas
1. **Opção A (Recomendada):**
   - **HTTP/TCP:** **Ktor Server (`ktor-server-cio`)** e **Ktor Client (`ktor-client-cio`)** em `commonMain`. O engine **CIO** (*Coroutine-based I/O*) é escrito 100% em Kotlin baseado em Coroutines, funciona identicamente em JVM Desktop e Android, suporta streaming nativo via `ByteReadChannel` / `ByteWriteChannel` e negociação JSON com `kotlinx.serialization`.
   - **UDP Multicast:** **`java.net.MulticastSocket` + `java.net.NetworkInterface`** encapsulados atrás da interface `MulticastTransport` (em `desktopMain` e `androidMain`), pois a API `ktor-network` ainda possui limitações no controle fino de `joinGroup(SocketAddress, NetworkInterface)` em múltiplas interfaces de rede físicas/virtuais.
2. **Opção B:** Implementar um parser HTTP/1.1 manual sobre `java.net.ServerSocket` / `Socket` puros.
   - *Desvantagem:* Alto risco de bugs de parsing de cabeçalhos HTTP, chunking, keep-alive e tratamento de concorrência, desviando o foco do protocolo de aplicação FSAP.
3. **Opção C:** Usar `ktor-network` puro para UDP Multicast.
   - *Desvantagem:* Controle limitado sobre `NetworkInterface` específica e opções de socket multicast em sistemas com interfaces virtuais (Docker/VPN) e Android.

## Decisão Proposta
Adotar a **Opção A**: Ktor Server/Client com engine **CIO** para o plano HTTP/TCP do FSAP e `java.net.MulticastSocket` com seleção explícita de `NetworkInterface` para o plano de descoberta UDP.

## Pontos para Ratificação da Equipe
- Confirmar se toda a equipe está de acordo com o uso do engine `CIO` do Ktor ou se algum cenário acadêmico específico exigirá demonstração adicional de `ServerSocket` direto em teste isolado.


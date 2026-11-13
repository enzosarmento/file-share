---
name: udp-device-discovery
description: >-
  Use esta skill ao implementar a descoberta automatica de dispositivos via UDP Multicast
  (239.255.70.83:53318), anuncio periodico ANNOUNCE, busca ativa DISCOVER, despedida BYE,
  PeerRegistry com expiracao TTL e MulticastLock no Android.
---

# Skill: UDP Device Discovery (`udp-device-discovery`)

## 1. Nome
`udp-device-discovery`

## 2. Descrição
Especialização na implementação do Plano de Descoberta do protocolo FSAP/1.0 sobre sockets UDP Multicast e gerenciamento reativo da tabela de vizinhança (`PeerRegistry`).

## 3. Objetivo
Implementar a descoberta automática, resiliente e livre de colisões de dispositivos na sub-rede local, com atualização dinâmica de presença e expiração determinística por TTL.

## 4. Conhecimentos Necessários
- Sockets UDP e IP Multicast (`java.net.MulticastSocket`, `DatagramPacket`, `InetSocketAddress`, `joinGroup`, `leaveGroup`).
- Controle de `WifiManager.MulticastLock` no Android.
- Testes assíncronos determinísticos com relógio virtual (`kotlinx.coroutines.test.TestScope`).

## 5. Contexto Técnico
- Especificação normativa em [docs/discovery.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/discovery.md) e [docs/protocol-fsap.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-fsap.md):
  - Grupo Multicast: `239.255.70.83`, porta UDP `53318`, buffer máximo `1024 bytes`.
  - Intervalo de `ANNOUNCE`: `5000 ms` (`T_ANNOUNCE_INTERVAL`).
  - Jitter de resposta a `DISCOVER`: `50..300 ms` (`T_DISCOVER_JITTER`).
  - Limiar de alerta `STALE`: `12000 ms`; Expiração `T_PEER_TTL`: `18000 ms`; Varredura a cada `3000 ms`.

## 6. Escopo de Atuação
- Implementação de `MulticastTransport`, `DiscoveryBroadcaster`, `DiscoveryListener` e `PeerRegistry`.
- Integração com `LocalNodeStateMachine` (`INITIALIZING` → `DISCOVERING` → `AVAILABLE` → `SHUTTING_DOWN` → `STOPPED`) e `PeerConnectionStateMachine`.

## 7. Arquivos e Módulos que Pode Modificar
- `composeApp/src/commonMain/kotlin/br/edu/fileshare/network/discovery/**`
- `composeApp/src/desktopMain/kotlin/br/edu/fileshare/desktop/network/**`
- `composeApp/src/androidMain/kotlin/br/edu/fileshare/android/network/**`
- `composeApp/src/commonTest/kotlin/br/edu/fileshare/network/discovery/**`

## 8. Restrições
1. **Descarte Silencioso de Pacotes Inválidos:** Jamais responder com pacotes de erro no grupo multicast ao receber datagramas corrompidos ou de protocolo desconhecido.
2. **Filtro Obrigatório de Auto-Descoberta:** Todo datagrama cujo `senderId == localDeviceId` deve ser ignorado imediatamente.
3. **Extração Real de IP:** O endereço IP do par remoto deve ser obtido do cabeçalho do pacote UDP recebido (`packet.address.hostAddress`) combinado com o `payload.httpPort` do JSON.

## 9. Dependências
- `fsap-protocol`
- `kotlin-networking`

## 10. Critérios de Conclusão
- Casos de conformidade `TC-FSAP-001` a `TC-FSAP-005` passando integralmente em `commonTest` / `desktopTest`.
- Liberação comprovada do socket UDP e do `MulticastLock` ao encerrar o nó (`BYE`).


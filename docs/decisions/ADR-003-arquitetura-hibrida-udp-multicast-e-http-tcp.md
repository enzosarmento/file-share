# ADR-003: Arquitetura Híbrida de Transporte — UDP Multicast e HTTP/1.1 sobre TCP

- **Status:** Aprovada (Premissa Obrigatória — Seções 4.4 e 5)
- **Data:** 2026-11-12
- **Contexto:** Milestone M0 — Planejamento e Arquitetura

## Contexto
O FileShare precisa descobrir dinamicamente dispositivos na mesma sub-rede local sem servidor central e, em seguida, transferir arquivos potencialmente grandes com garantia de entrega, controle de fluxo e integridade verificada.

## Decisão
Dividir o protocolo **FSAP/1.0** em dois planos de transporte complementares:
1. **Plano de Descoberta (UDP Multicast — `239.255.70.83:53318`):**
   - Utiliza datagramas UDP compactos (≤ 1024 bytes, UTF-8 JSON) para as mensagens `ANNOUNCE`, `DISCOVER` e `BYE`.
   - Justificativa: Sem overhead de conexão, suporte nativo a comunicação um-para-muitos na LAN e tolerância natural a perdas eventuais pela periodicidade dos anúncios.
2. **Plano de Controle de Sessão e Transferência Binária (HTTP/1.1 sobre TCP — porta padrão `53317`):**
   - Utiliza conexões TCP confiáveis com semântica HTTP/1.1 para `IDENTIFY`, `TRANSFER_REQUEST`, `TRANSFER_ACCEPT`/`REJECT`, `TRANSFER_CONTENT`, `TRANSFER_STATUS`, `TRANSFER_CANCEL`, `TRANSFER_COMPLETE` e `ERROR`.
   - Justificativa: Entrega ordenada de bytes, retransmissão automática de segmentos perdidos, controle de congestionamento TCP e enquadramento padronizado de streams binários (`application/octet-stream` com `Content-Length`).

## Consequências
- **Positivas:** Atende integralmente aos requisitos acadêmicos de Redes de Computadores (uso prático e justificado de sockets UDP e TCP) e mantém alta performance na transferência de arquivos.
- **Negativas / Mitigação:** Em redes que filtram UDP Multicast, o sistema recorre ao fallback de **Conexão Manual por IP** realizando a chamada `IDENTIFY` diretamente via TCP.


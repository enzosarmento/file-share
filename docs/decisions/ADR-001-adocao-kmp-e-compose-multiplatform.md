# ADR-001: Adoção de Kotlin Multiplatform (KMP) e Compose Multiplatform (CMP)

- **Status:** Aprovada (Premissa Obrigatória do Projeto)
- **Data:** 2026-11-12
- **Contexto:** Milestone M0 — Planejamento e Arquitetura

## Contexto
O projeto FileShare precisa executar em sistemas Desktop (Windows e Linux) e dispositivos móveis Android, implementando um protocolo próprio da camada de aplicação (FSAP/1.0) com forte exigência de consistência comportamental entre plataformas, sem utilizar linguagens como Rust, Python ou código-fonte Java.

## Decisão
Adotar **Kotlin Multiplatform (KMP)** como arquitetura de compartilhamento de código, **Compose Multiplatform (CMP)** para a interface gráfica, **Kotlin Coroutines** para concorrência estruturada e **Kotlinx Serialization** para serialização JSON das mensagens do protocolo FSAP.

## Consequências
- **Positivas:**
  - Contratos do protocolo FSAP, DTOs, validadores e máquinas de estados são implementados uma única vez em `commonMain`, eliminando divergências de protocolo entre Desktop e Android.
  - Testes unitários de conformidade de mensagens e transições de estados podem rodar rapidamente em `commonTest`.
- **Negativas / Cuidados:**
  - APIs dependentes de sistema operacional (`MulticastSocket`, `WifiManager.MulticastLock`, `Storage Access Framework`) não devem ser forçadas em `commonMain`, exigindo abstrações limpas por interfaces.


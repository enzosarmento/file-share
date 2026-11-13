# Sistema de Gestão de Tasks e Backlog — FileShare

> **Status Global do Backlog:** `47 tasks` especificadas — **100% com status inicial `TODO`**.  
> **Regra de Governança:** Nenhuma task possui atribuição nominal prévia. A distribuição será realizada pela equipe de 4 integrantes a cada sprint/milestone respeitando o grafo de dependências.

---

## 1. Estrutura dos Documentos de Planejamento

- [**`backlog.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/tasks/backlog.md): Catálogo completo das **47 tasks** organizadas pelos 10 domínios funcionais, contendo todos os atributos obrigatórios (ID, título, descrição, objetivo, domínio, prioridade, complexidade, dependências, critérios de aceitação, arquivos envolvidos, testes necessários e status `TODO`).
- [**`milestones.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/tasks/milestones.md): Organização das entregas nas Milestones **M0 a M6**, análise crítica da sequência de entrega e critérios objetivos de conclusão (*Definition of Done* de cada milestone).
- [**`dependency-graph.md`**](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/tasks/dependency-graph.md): Grafo visual de dependências em Mermaid, análise do caminho crítico e identificação de trilhas de trabalho paralelo para 4 desenvolvedores.

---

## 2. Convenção de Identificadores e Prioridades

### Prefixos por Domínio Funcional
| Prefixo | Domínio Funcional | Quantidade de Tasks |
| :--- | :--- | :---: |
| **`TASK-ARC-xxx`** | 1. Arquitetura e Documentação | 3 |
| **`TASK-INF-xxx`** | 2. Configuração e Infraestrutura | 3 |
| **`TASK-FSAP-xxx`** | 3. Protocolo FSAP | 5 |
| **`TASK-DISC-xxx`** | 4. Descoberta UDP | 5 |
| **`TASK-DSRV-xxx`** | 5. Servidor Desktop | 5 |
| **`TASK-DCLI-xxx`** | 6. Cliente Desktop | 6 |
| **`TASK-AND-xxx`** | 7. Cliente Android | 5 |
| **`TASK-XFER-xxx`** | 8. Transferência de Arquivos | 6 |
| **`TASK-SEC-xxx`** | 9. Segurança | 4 |
| **`TASK-TEST-xxx`** | 10. Testes e Integração | 5 |
| **Total Geral** | **10 Domínios Funcionais** | **47 Tasks** |

### Escala de Prioridades
- **`P0` (Crítica / Bloqueante):** Essencial para o funcionamento do protocolo FSAP, descoberta, transferência íntegra e requisitos acadêmicos obrigatórios.
- **`P1` (Importante):** Funcionalidades de suporte a concorrência, cancelamento, usabilidade e robustez operacional.
- **`P2` (Desejável / Aprimoramento):** Melhorias avançadas de segurança (ex: TLS autoassinado opcional na M6) após consolidação do MVP.

### Escala de Complexidade
- **`Baixa`:** Escopo isolado em 1 a 2 classes, regras diretas e sem concorrência complexa.
- **`Média`:** Envolve coordenação entre camadas, I/O assíncrono com Coroutines ou integração de UI com estado reativo.
- **`Alta`:** Envolve máquinas de estados concorrentes, streaming binário em tempo real, sockets multi-interface ou ciclo de vida nativo do Android.


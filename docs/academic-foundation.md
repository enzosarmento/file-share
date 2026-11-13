# Fundamentação Acadêmica e Especificação de Requisitos — FileShare

> **Status do Documento:** Base Acadêmica e Engenharia de Requisitos (Milestone M0)  
> **Estado de Implementação:** Nenhuma funcionalidade de rede, interface ou transferência foi implementada nesta etapa. Este documento fundamenta o relatório acadêmico da disciplina de Redes de Computadores e orienta o desenvolvimento das Milestones M1 a M6.

---

## 1. Descrição do Projeto (Seção 9.1)

### 1.1. Problema que o Projeto Resolve
A troca de arquivos entre dispositivos heterogêneos (computadores Desktop executando Linux ou Windows e dispositivos móveis Android) conectados à mesma rede local (LAN/WLAN) ainda depende frequentemente de soluções indiretas e ineficientes: envio de arquivos para serviços de armazenamento em nuvem, aplicativos de mensagens instantâneas que comprimem mídias ou limitam o tamanho de anexos, cabos USB sujeitos a incompatibilidades de drivers (MTP) ou configurações manuais complexas de compartilhamento de rede (SMB/SFTP).

Essas abordagens intermediadas pela internet desperdiçam largura de banda externa, aumentam a latência de transferência, expõem dados privados a servidores de terceiros e tornam impossível a operação em ambientes sem conectividade WAN (como laboratórios acadêmicos isolados ou redes locais desconectadas).

### 1.2. Motivação
Do ponto de vista prático, o projeto inspira-se no **LocalSend** para oferecer uma solução local, descentralizada e multiplataforma de compartilhamento de arquivos.

Do ponto de vista acadêmico (Redes de Computadores), o desenvolvimento do **FileShare** proporciona a aplicação prática e integrada dos conceitos fundamentais da pilha TCP/IP:
- Concepção formal de um protocolo próprio da camada de aplicação (**FSAP — FileShare Application Protocol**);
- Uso complementar dos protocolos da camada de transporte **UDP** (para descoberta e anúncio de presença via multicast) e **TCP** (para controle confiável de sessão e streaming de dados binários sobre HTTP);
- Tratamento real de concorrência, multiplexação de conexões, controle de fluxo em memória restrita, detecção de falhas de enlace e verificação criptográfica de integridade ponta a ponta via **SHA-256**.

### 1.3. Objetivo Geral
Projetar, especificar e desenvolver (nas etapas subsequentes do cronograma) uma aplicação multiplataforma para Desktop (Windows e Linux) e Android que permita a descoberta automática de dispositivos na mesma rede local e a transferência direta, íntegra e concorrente de arquivos utilizando o protocolo proprietário da camada de aplicação **FSAP/1.0**.

### 1.4. Objetivos Específicos
1. **Especificar formalmente o protocolo FSAP/1.0**, incluindo sintaxe de mensagens, semântica de eventos, máquinas de estados finitos, temporizadores e catálogo padronizado de erros.
2. **Desenvolver o mecanismo de descoberta de vizinhança em LAN** baseado em sockets **UDP Multicast**, com anúncio periódico de presença (`ANNOUNCE`), busca ativa (`DISCOVER`), despedida graciosa (`BYE`) e expiração automática por TTL, além de fallback manual por endereço IP.
3. **Construir um Servidor Desktop independente (Kotlin/JVM)** capaz de ser executado como processo separado da interface gráfica, escutando conexões TCP/HTTP concorrentes e gerenciando o recebimento de fluxos binários.
4. **Construir clientes Desktop e Android em Kotlin Multiplatform (KMP) e Compose Multiplatform (CMP)** compartilhando contratos de protocolo, serialização, máquinas de estados e regras de negócio em `commonMain`.
5. **Garantir a transferência eficiente de arquivos em streaming** (leitura e escrita em blocos/chunks sem carregamento integral em memória RAM) e validação obrigatória de integridade por hash **SHA-256** e tamanho em bytes.
6. **Validar a interoperabilidade entre plataformas** (Desktop ↔ Desktop, Desktop → Android, Android → Desktop e Android ↔ Android) por meio de casos de teste formais de conformidade de protocolo.

### 1.5. Escopo do Projeto (MVP vs. Fora do Escopo)

| Classificação | Itens Contemplados |
| :--- | :--- |
| **Dentro do Escopo (Planejado para M1–M6)** | Descoberta automática via UDP Multicast; Anúncio periódico de presença; Identificação por alias e UUID único; Listagem dinâmica de dispositivos disponíveis; Conexão manual por IP:Porta; Envio e recebimento de arquivos únicos ou múltiplos em lote; Confirmação explícita do destinatário antes da transferência de dados; Barra de progresso em tempo real; Cancelamento bilateral de transferências; Verificação de integridade SHA-256; Tratamento de desconexões e timeouts; Seleção e persistência de arquivos pelas APIs nativas de Desktop e Android; Suporte a múltiplas conexões simultâneas; Executável independente para o Servidor Desktop. |
| **Fora do Escopo (Não contemplado no MVP)** | Servidores centrais na internet (STUN/TURN/Relay em nuvem); Sincronização contínua de pastas em nuvem; Contas de usuário ou autenticação federada (OAuth/SSO); Retomada de transferência parcial após reinicialização completa da aplicação (chunk resume persistente em disco); Transferência via Bluetooth ou Wi-Fi Direct (P2P sem roteador/AP). |

### 1.6. Limitações Conhecidas
1. **Bloqueio de Multicast / Isolamento de Clientes (AP Isolation):** Redes Wi-Fi públicas, corporativas ou universitárias frequentemente habilitam *Client Isolation* ou filtram pacotes IGMP/UDP Multicast no ponto de acesso. Nesses cenários, a descoberta automática não receberá pacotes `ANNOUNCE`, exigindo o uso da funcionalidade planejada de **Conexão Manual por IP** (desde que o roteamento unicast TCP entre os hosts seja permitido pela VLAN).
2. **Restrições de Execução em Segundo Plano no Android:** O sistema operacional Android suspende processos em background e libera `MulticastLock` / sockets ociosos para economia de bateria. A recepção contínua no Android requer que o aplicativo esteja em primeiro plano ou sustentado por um *Foreground Service* ativo com notificação visível.
3. **Firewalls Locais de Sistema Operacional:** No Windows (*Windows Defender Firewall*) e em distribuições Linux com `ufw`/`firewalld` ativos, portas UDP (`53318`) e TCP (`53317`) podem ser bloqueadas até que o usuário conceda permissão de rede local ao executável JVM.
4. **Múltiplas Interfaces de Rede Virtuais:** Em computadores de desenvolvimento com Docker, WSL2, VirtualBox ou VPNs ativas, o socket multicast pode associar-se por padrão a uma interface virtual incorreta se não houver enumeração explícita das interfaces físicas IPv4 ativas.

---

## 2. Fundamentação Técnica (Seção 9.2)

### 2.1. Arquitetura Cliente-Servidor Simétrica na Rede Local
Embora o FileShare opere entre pares na mesma rede local, sua arquitetura de transporte adota o **modelo Cliente-Servidor clássico na camada de aplicação**, onde cada nó pode assumir ambos os papéis de maneira bem definida:
- **Papel de Servidor (Receptor de Arquivos / Anunciante):** Mantém um socket TCP passivo escutando requisições HTTP do protocolo FSAP e responde a solicitações de identificação (`IDENTIFY`), preparação de transferência (`TRANSFER_REQUEST`), recepção de stream binário (`TRANSFER_CONTENT`), consulta de estado (`TRANSFER_STATUS`), cancelamento (`TRANSFER_CANCEL`) e finalização (`TRANSFER_COMPLETE`). No Desktop, este servidor é empacotado como um **executável independente da interface gráfica**.
- **Papel de Cliente (Remetente de Arquivos / Descobridor):** Escuta anúncios de presença na rede, mantém o catálogo de dispositivos disponíveis, inicia conexões TCP ativas em direção ao servidor do dispositivo destinatário e transmite os fluxos de metadados e bytes dos arquivos selecionados pelo usuário.

### 2.2. Justificativa da Escolha de Kotlin
- **Segurança de Tipos e Null Safety:** Reduz drasticamente falhas em tempo de execução ao manipular estados de rede, payloads desconhecidos e referências de sockets.
- **Concorrência Estruturada com Coroutines:** A biblioteca `kotlinx.coroutines` permite modelar dezenas de fluxos assíncronos simultâneos (loop de anúncio UDP, listener multicast, servidor TCP multi-cliente, progresso de leitura/escrita de múltiplos arquivos e timeouts) sem o custo de criação de threads bloqueantes por operação e evitando vazamento de recursos (*coroutine scopes* vinculados ao ciclo de vida da sessão).
- **Interoperabilidade Total com a JVM e Android:** Permite acesso direto às APIs de baixo nível de rede da plataforma Java (`java.net.MulticastSocket`, `java.net.NetworkInterface`, `java.security.MessageDigest`) quando necessário, mantendo código idiomático Kotlin.

### 2.3. Justificativa do Kotlin Multiplatform (KMP)
O uso do KMP evita a duplicação e a divergência de implementação do protocolo FSAP entre Desktop e Android:
- Contratos de mensagens (DTOs serializáveis via `kotlinx.serialization`), validadores semânticos, códigos de erro, cálculo de transições das máquinas de estados e orquestração das sessões residem uma única vez em `commonMain`.
- Apenas as interações dependentes de sistema operacional (seleção de arquivos, escrita em `ContentResolver`/SAF no Android vs `java.nio.file.Path` no Desktop, e aquisição de `WifiManager.MulticastLock` no Android) são isoladas por interfaces ou `expect/actual`.

### 2.4. Justificativa do Compose Multiplatform (CMP)
O Compose Multiplatform permite construir interfaces declarativas e reativas baseadas em `StateFlow` compartilhando componentes visuais (lista de dispositivos descobertos, diálogos de confirmação de recebimento, barras de progresso e telas de erro) entre Desktop (Windows/Linux) e Android, mantendo a camada de UI estritamente desacoplada do serviço de rede.

### 2.5. Justificativa da Utilização de TCP e UDP e suas Diferenças no Projeto

O FileShare utiliza **UDP** e **TCP** de forma complementar, explorando as propriedades intrínsecas de cada protocolo da camada de transporte:

| Critério Técnico | UDP no FileShare (Descoberta de Presença) | TCP sobre HTTP no FileShare (Controle e Transferência de Arquivos) |
| :--- | :--- | :--- |
| **Subsistema do FSAP** | Plano de Descoberta (`ANNOUNCE`, `DISCOVER`, `BYE`) | Plano de Controle e Dados (`IDENTIFY`, `TRANSFER_*`, `ERROR`) |
| **Orientação a Conexão** | **Sem conexão (*Connectionless*):** Permite enviar um único datagrama para o grupo multicast `239.255.70.83:53318` alcançando todos os hosts interessados na sub-rede simultaneamente sem handshake prévio. | **Orientado a conexão (*Connection-oriented*):** Estabelece sessão ponto a ponto via *Three-Way Handshake* (`SYN`, `SYN-ACK`, `ACK`) entre remetente e destinatário antes da troca de dados. |
| **Confiabilidade e Ordem** | **Melhor esforço (*Best-effort*):** Não há garantia de entrega nem ordenação. Se um datagrama `ANNOUNCE` for perdido por colisão no Wi-Fi, o próximo anúncio periódico (a cada 5 s) recupera o estado naturalmente sem custo de retransmissão. | **Entrega confiável e ordenada:** Garante que todos os bytes de um arquivo de múltiplos gigabytes cheguem exatamente na ordem enviada, retransmitindo segmentos perdidos de forma transparente para a aplicação. |
| **Controle de Fluxo e Congestionamento** | **Inexistente no transporte:** Controlado na aplicação pela baixa frequência de envio (1 pacote leve de ≤ 512 bytes a cada 5 segundos). | **Nativo (Janela Deslizante e Controle de Congestionamento TCP):** Impede que um remetente rápido (ex: Desktop via Gigabit Ethernet) sature os buffers de recepção de um destinatário mais lento (ex: smartphone Android em Wi-Fi). |
| **Delimitadores de Mensagem** | **Preserva fronteiras de datagrama:** Cada pacote UDP lido corresponde exatamente a uma mensagem JSON do FSAP. | **Fluxo contínuo de bytes (*Byte Stream*):** Requer enquadramento na camada de aplicação, provido pelo protocolo HTTP/1.1 (`Content-Length` e cabeçalhos FSAP) sobre o socket TCP. |

---

## 3. Síntese da Arquitetura de Comunicação (Seção 9.3)

A especificação completa da comunicação encontra-se distribuída nos documentos dedicados da pasta `docs/`. Em síntese:
1. **Arquitetura de Rede:** Topologia local ponto a ponto assistida por grupo multicast IPv4 (`239.255.70.83`, porta UDP `53318`) e servidor HTTP/1.1 dedicado por nó (porta TCP padrão `53317`, configurável). Consulte [architecture.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/architecture.md) e [discovery.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/discovery.md).
2. **Protocolo da Camada de Aplicação (FSAP/1.0):** Protocolo formal orientado a estados e eventos. Consulte [protocol-fsap.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-fsap.md).
3. **Formato das Mensagens:** Metadados de controle codificados em JSON (UTF-8) com envelope padronizado (`protocol`, `version`, `type`, `messageId`, `timestamp`, `senderId`, `payload`) e carga útil de arquivos transmitida como fluxo binário puro (`application/octet-stream`) em chunks de 64 KB. Consulte [protocol-messages.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-messages.md).
4. **Máquinas de Estados:** Três máquinas de estados determinísticas (Nó Local, Conexão de Par e Sessão de Transferência). Consulte [state-machine.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/state-machine.md).
5. **Fluxos de Comunicação:** Dez diagramas de sequência cobrindo todos os cenários nominais e de exceção. Consulte [communication-flows.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/communication-flows.md).
6. **Tratamento de Falhas e Integridade:** Catálogo de 14 códigos de erro padronizados ([error-codes.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/error-codes.md)) e validação dupla de integridade (`sizeBytes` + digest `SHA-256` calculado incrementalmente em streaming antes da efetivação do arquivo `.part` no destino final, detalhado em [transfer-flow.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/transfer-flow.md)).

---

## 4. Engenharia de Requisitos (Seção 9.4)

### 4.1. Requisitos Funcionais (RF) — Status: Planejados (Não Implementados)

| ID | Requisito Funcional | Prioridade | Rastreabilidade (FSAP / Tasks) |
| :--- | :--- | :---: | :--- |
| **RF-01** | O sistema deve descobrir automaticamente outros dispositivos FileShare ativos na mesma sub-rede local via UDP Multicast. | P0 | `ANNOUNCE`, `DISCOVER` / `TASK-DISC-001`, `TASK-DISC-002` |
| **RF-02** | O sistema deve emitir anúncios periódicos de presença (`ANNOUNCE`) na rede local e enviar notificação de saída (`BYE`) ao encerrar. | P0 | `ANNOUNCE`, `BYE` / `TASK-DISC-002`, `TASK-DISC-003` |
| **RF-03** | O sistema deve identificar cada dispositivo por um nome amigável (`alias`) configurável e um identificador único persistente (`deviceId` UUIDv4). | P0 | `DeviceIdentity` / `TASK-FSAP-001`, `TASK-DISC-001` |
| **RF-04** | O sistema deve listar em tempo real os dispositivos disponíveis na rede local, atualizando seus metadados e removendo dispositivos expirados por inatividade (TTL). | P0 | `PeerRegistry` / `TASK-DISC-004`, `TASK-DCLI-002`, `TASK-AND-003` |
| **RF-05** | O sistema deve permitir a conexão manual a um dispositivo destinatário informando seu endereço IP (e porta opcional) como alternativa ao multicast. | P0 | `IDENTIFY` / `TASK-DISC-005`, `TASK-DCLI-003`, `TASK-AND-003` |
| **RF-06** | O sistema deve permitir selecionar um ou mais arquivos para envio utilizando os seletores nativos de cada plataforma (Desktop e Android SAF). | P0 | `FileSource` / `TASK-DCLI-004`, `TASK-AND-002` |
| **RF-07** | O sistema deve enviar previamente os metadados da transferência (lista de arquivos, nomes, tamanhos e hashes SHA-256) e aguardar autorização antes de transmitir os bytes. | P0 | `TRANSFER_REQUEST` / `TASK-FSAP-002`, `TASK-XFER-002` |
| **RF-08** | O destinatário deve exibir confirmação explícita (Aceitar / Recusar) ao usuário antes de iniciar o recebimento de qualquer arquivo. | P0 | `TRANSFER_ACCEPT`, `TRANSFER_REJECT` / `TASK-DSRV-003`, `TASK-DCLI-005`, `TASK-AND-004` |
| **RF-09** | O sistema deve transmitir e receber o conteúdo dos arquivos como dados binários por streaming sobre HTTP/TCP. | P0 | `TRANSFER_CONTENT` / `TASK-XFER-003`, `TASK-DSRV-004` |
| **RF-10** | O sistema deve apresentar o progresso da transferência em tempo real (percentual, bytes transferidos/total, velocidade e estado atual). | P0 | `TRANSFER_STATUS` / `TASK-XFER-004`, `TASK-DCLI-006`, `TASK-AND-005` |
| **RF-11** | O sistema deve permitir que tanto o remetente quanto o destinatário cancelem uma transferência em andamento a qualquer momento, removendo arquivos parciais. | P1 | `TRANSFER_CANCEL` / `TASK-XFER-005`, `TASK-DSRV-005` |
| **RF-12** | O sistema deve calcular e verificar a integridade de cada arquivo recebido utilizando o algoritmo **SHA-256**, rejeitando e descartando arquivos corrompidos. | P0 | `TRANSFER_COMPLETE`, `ERROR` / `TASK-XFER-001`, `TASK-XFER-003`, `TASK-SEC-003` |
| **RF-13** | O sistema deve detectar e tratar desconexões inesperadas, timeouts de rede e erros de armazenamento, informando o motivo padronizado ao usuário. | P0 | `ERROR` / `TASK-FSAP-004`, `TASK-XFER-006` |
| **RF-14** | O sistema deve armazenar os arquivos recebidos e validados no diretório local configurado pelo usuário no Desktop ou no armazenamento apropriado do Android (`Downloads` / SAF). | P0 | `FileSink` / `TASK-DSRV-004`, `TASK-AND-002` |
| **RF-15** | O sistema deve suportar múltiplas conexões e transferências concorrentes sem bloqueio da interface gráfica ou corrupção de estado. | P1 | Coroutines / `TASK-DSRV-002`, `TASK-XFER-004` |
| **RF-16** | O Servidor Desktop deve poder ser inicializado e executado como um executável independente da aplicação gráfica Desktop. | P0 | `ServerMain.kt` / `TASK-DSRV-001`, `TASK-INF-002` |

### 4.2. Requisitos Não Funcionais (RNF)

| ID | Categoria | Especificação Objetiva |
| :--- | :--- | :--- |
| **RNF-01** | **Uso de Memória (Streaming)** | O envio e o recebimento de arquivos não devem carregar o arquivo inteiro em memória RAM. O buffer de transferência por fluxo ativo deve ser limitado a blocos de **64 KB** (máximo de 1 MB de buffers em trânsito por sessão). |
| **RNF-02** | **Separação Arquitetural** | O módulo/serviço de rede do Servidor Desktop não pode depender de classes de UI (`androidx.compose.*` ou `java.awt.*`), garantindo execução em ambientes *headless*. |
| **RNF-03** | **Interoperabilidade de Protocolo** | Qualquer implementação compatível com a especificação **FSAP/1.0** deve interoperar independentemente do sistema operacional (Windows, Linux, Android). |
| **RNF-04** | **Tempo de Descoberta** | Em uma LAN sem bloqueio de multicast, um novo dispositivo deve ser descoberto pelos demais em no máximo **6 segundos** (ou em até **1 segundo** mediante probe inicial `DISCOVER`). |
| **RNF-05** | **Detecção de Saída / Expiração** | Dispositivos que saírem abruptamente da rede (sem enviar `BYE`) devem ser marcados como indisponíveis após **18 segundos** (3 ciclos de anúncio + margem de jitter) sem recepção de `ANNOUNCE`. |
| **RNF-06** | **Segurança contra Path Traversal** | Nenhum nome de arquivo recebido pela rede pode escrever fora do diretório de destino autorizado. Sequências como `../`, `..\`, caminhos absolutos ou bytes nulos devem ser rejeitados com `FSAP-ERR-400-INVALID_FILENAME`. |
| **RNF-07** | **Atomicidade de Escrita** | Arquivos em recepção devem ser gravados com sufixo temporário `.part` e somente renomeados para o nome final após a validação bem-sucedida do tamanho e do hash SHA-256. |
| **RNF-08** | **Responsividade de UI** | Nenhuma operação de socket, cálculo de hash SHA-256 ou I/O de disco pode executar na Main/UI Thread (`Dispatchers.Main`). |
| **RNF-09** | **Tecnologias Restritas** | Todo o código de produção e testes deve ser escrito exclusivamente em **Kotlin** (utilizando APIs da JVM/Android quando aplicável), sendo vedado o uso de Python, Rust ou código-fonte `.java`. |
| **RNF-10** | **Observabilidade e Logs** | Todas as transições das máquinas de estados e erros do protocolo FSAP devem gerar registros estruturados contendo `sessionId`/`transferId`, estado anterior, evento, estado resultante e timestamp. |

### 4.3. Requisitos Mínimos de Execução e Restrições

- **Desktop (Windows e Linux):**
  - Sistema Operacional: Ubuntu 22.04+ / Debian 12+ / Fedora 39+ ou Windows 10/11 (64-bit).
  - Runtime: JDK / JRE 21 (para Desktop) e JDK 17+ compatível com Android Gradle Plugin.
  - Memória RAM mínima disponível: 256 MB para o Servidor Desktop Headless; 512 MB para o Cliente GUI.
  - Rede: Interface de rede local IPv4 ativa com suporte a sockets TCP e UDP.
- **Android (Smartphones e Tablets):**
  - Versão mínima: **Android 8.0 (API Level 26)** — escolhida para suporte completo a `java.nio.file`/`java.time` desugared, canais de notificação e APIs modernas de rede.
  - Versão alvo: **Android 15 (API Level 35)**.
  - Permissões: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`.

---

## 5. Classificação de Maturidade: Decisões Aprovadas vs. Propostas Técnicas vs. Funcionalidades Planejadas

Para total transparência acadêmica e de engenharia, a tabela abaixo diferencia o que já é premissa fixada no projeto, o que é proposta arquitetural documentada aguardando ratificação da equipe e o que é funcionalidade planejada para implementação futura:

| Categoria | Item | Status Atual | Referência |
| :--- | :--- | :---: | :--- |
| **Decisão Aprovada (Premissa Obrigatória)** | Linguagem Kotlin, Kotlin Multiplatform (KMP), Compose Multiplatform (CMP), Coroutines, Kotlinx Serialization e Gradle. | **Aprovada** | Seção 2 do Escopo / [ADR-001](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-001-adocao-kmp-e-compose-multiplatform.md) |
| **Decisão Aprovada (Premissa Obrigatória)** | Servidor Desktop executável de forma independente da interface gráfica. | **Aprovada** | Seção 4.1 do Escopo / [ADR-002](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-002-separacao-cliente-gui-e-servidor-desktop.md) |
| **Decisão Aprovada (Premissa Obrigatória)** | Descoberta via UDP Multicast + fallback por IP manual, e transferência binária via HTTP/TCP com validação SHA-256. | **Aprovada** | Seções 3, 4.4 e 5 / [ADR-003](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-003-arquitetura-hibrida-udp-multicast-e-http-tcp.md), [ADR-005](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-005-estrategia-streaming-e-integridade-sha256.md) |
| **Proposta Técnica (Para Aprovação da Equipe)** | Endereço de grupo Multicast IPv4 `239.255.70.83`, porta UDP `53318` e porta TCP padrão `53317`. | **Proposta Técnica** | [protocol-fsap.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-fsap.md) |
| **Proposta Técnica (Para Aprovação da Equipe)** | Uso do motor **Ktor (`CIO` engine)** para o servidor e cliente HTTP/TCP combinado com `java.net.MulticastSocket` para o plano UDP. | **Proposta Técnica** | [ADR-004](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-004-escolha-ktor-vs-java-net-sockets.md) |
| **Proposta Técnica (Para Aprovação da Equipe)** | Modelo de confirmação síncrona (long-polling HTTP de até 60 s no `TRANSFER_REQUEST`) vs. confirmação assíncrona via webhook reverso. | **Proposta Técnica** | [protocol-fsap.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/protocol-fsap.md) |
| **Proposta Técnica (Para Aprovação da Equipe)** | Uso de `ForegroundService` (`dataSync`) no Android para manter o servidor HTTP e o `MulticastLock` ativos durante sessões de recepção. | **Proposta Técnica** | [ADR-006](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/docs/decisions/ADR-006-armazenamento-e-ciclo-de-vida-android.md) |
| **Funcionalidades do Sistema (RF-01 a RF-16)** | Implementação de sockets, descoberta UDP, servidor/cliente HTTP, streaming de arquivos e telas em Compose. | **Planejada (`TODO`)** | [backlog.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/tasks/backlog.md) |


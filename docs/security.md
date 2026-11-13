# Modelo de Segurança e Proteção em Rede Local — FileShare

> **Status:** Especificação de Segurança (Milestone M0)  
> **Escopo:** Ameaças em redes locais (LANs compartilhadas), controles obrigatórios do protocolo FSAP/1.0, sanitização de sistema de arquivos e integridade de dados.

---

## 1. Modelo de Ameaças em Redes Locais (LAN)

O FileShare opera em redes locais sem servidor central (como redes domésticas, laboratórios universitários ou Wi-Fi corporativo). Nesses ambientes, qualquer dispositivo conectado à mesma VLAN pode enviar datagramas UDP ou abrir conexões TCP para a porta do servidor FileShare (`53317`).

As principais ameaças identificadas para o escopo do projeto e suas defesas arquiteturais são:

| Ameaça | Vetor de Ataque | Controle Obrigatório no FileShare / FSAP |
| :--- | :--- | :--- |
| **Escrita Arbitrária de Arquivos (*Path Traversal*)** | Um cliente malicioso envia `fileName: "../../.bashrc"` ou `"..\\Windows\\System32\\cmd.exe"` no `TRANSFER_REQUEST` para sobrescrever arquivos do sistema. | Validador estrito `FileNameSanitizer` em `commonMain` + verificação de `canonicalPath.startsWith(targetDir)` antes de abrir qualquer arquivo. Rejeição imediata com `FSAP-ERR-400-INVALID_FILENAME`. |
| **Upload Não Solicitado / Injeção de Sessão** | Um terceiro na LAN tenta fazer `PUT /fsap/v1/transfers/{id}/files/{fileId}/content` sem aprovação do usuário ou adivinhando IDs. | Confirmação explícita obrigatória do destinatário (`PENDING_APPROVAL`) + exigência de cabeçalho `X-FSAP-Transfer-Token` gerado com `SecureRandom` (256 bits) apenas após clique em "Aceitar". |
| **Corrupção ou Adulteração de Dados em Trânsito** | Falha na interface de rede ou modificação de bytes durante o envio do stream binário. | Cálculo obrigatório de hash criptográfico **SHA-256** sobre 100% dos bytes recebidos e descarte automático do arquivo `.part` caso divirja do manifesto (`FSAP-ERR-422-HASH_MISMATCH`). |
| **Negação de Serviço por Exaustão de Memória (JSON Bomb)** | Envio de um payload JSON de centenas de megabytes no endpoint `TRANSFER_REQUEST` ou datagrama UDP gigante. | Limite rígido de `1024 bytes` no buffer UDP e limite de `256 KB` (`bodyLimit`) para payloads JSON nas rotas HTTP do servidor. |
| **Negação de Serviço por Exaustão de Disco** | Cliente declara `sizeBytes = 1 KB` na aprovação, mas envia dezenas de gigabytes no stream `TRANSFER_CONTENT`. | O receptor interrompe a leitura imediatamente se `bytesReceived > expectedSizeBytes` e aborta a sessão com `FSAP-ERR-400-SIZE_MISMATCH`, apagando o `.part`. |
| **Conexões Lentas (*Slowloris* / Socket Hanging)** | Cliente abre sessão ou stream e para de enviar dados para prender recursos do servidor. | Timeouts estritos: `T_TCP_CONNECT_TIMEOUT` (5 s), `T_APPROVAL_TIMEOUT` (60 s), `T_TOKEN_IDLE_TTL` (30 s) e `T_STREAM_READ_TIMEOUT` (15 s). |

---

## 2. Regras Normativas de Sanitização de Nomes de Arquivos (`FileNameSanitizer`)

Todo `fileName` recebido em `TRANSFER_REQUEST` deve passar pelas seguintes validações em `commonMain` antes que a solicitação seja sequer apresentada ao usuário:

1. **Comprimento:** Deve possuir entre `1` e `255` bytes em UTF-8.
2. **Proibição de Separadores de Diretório:** Não pode conter `/` (barra normal) nem `\` (barra invertida).
3. **Proibição de Navegação Relativa:** Não pode ser `"."`, `".."` nem conter a sequência `".."` em conjunto com separadores.
4. **Proibição de Caracteres de Controle e Proibidos em Windows/Linux:** Não pode conter bytes `0x00` a `0x1F`, nem os caracteres `<`, `>`, `:`, `"`, `|`, `?`, `*`.
5. **Proibição de Nomes de Dispositivos Reservados do Windows:** Não pode corresponder (case-insensitive, com ou sem extensão) a `CON`, `PRN`, `AUX`, `NUL`, `COM1`..`COM9`, `LPT1`..`LPT9`.
6. **Política de Não-Sobrescrita:** Ao consolidar o arquivo `.part` no diretório final de downloads, se já existir um arquivo com o mesmo nome, o sistema nunca deve sobrescrevê-lo silenciosamente; deve aplicar sufixo incremental seguro (`arquivo (1).ext`, `arquivo (2).ext`).

---

## 3. Evolução de Segurança Planejada para a Milestone M6 (Opcional / Avançado)

Como o tráfego HTTP padrão na LAN é texto claro (*cleartext*), na Milestone M6 está prevista a task de endurecimento de segurança (`TASK-SEC-004`) para avaliar:
- Geração de certificado X.509 autoassinado efêmero (TLS 1.3) por dispositivo e divulgação do hash da chave pública (`fingerprint` SHA-256) no anúncio e na tela de confirmação para evitar interceptação ativa (*ARP Spoofing / MITM*) em redes não confiáveis.


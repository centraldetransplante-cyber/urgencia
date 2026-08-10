# Vistoria de brechas de visibilidade nas decisões excepcionais (2026-08-10)

**Status: DIAGNÓSTICO. Nada foi implementado.** Este documento não altera nenhuma
regra de negócio, endpoint ou comportamento — é leitura de código + verificação
contra uma instância real do SAUR. Aguarda revisão do dono do produto antes de
qualquer implementação.

**Pergunta que guiou a vistoria:** *se eu for um operador/ADMIN olhando qualquer
tela, PDF, e-mail ou log deste sistema, dá para saber que ESTA decisão não seguiu
o caminho padrão de maioria simples 2-de-3?*

---

## ⚠ LEIA PRIMEIRO — nenhuma sugestão deste relatório altera o cálculo da votação

> A regra de negócio das votações é a alma do sistema. Por isso, este relatório
> foi escrito sob a premissa explícita de que **ela não se toca**.

**NENHUM** dos 12 achados e **NENHUMA** das 6 fases propostas altera:

| Não se toca | Onde vive hoje |
|---|---|
| Maioria simples 2-de-3 (deferir e indeferir) | `ProcessoService.FAVORAVEIS_PARA_DEFERIR` / `DESFAVORAVEIS_PARA_INDEFERIR` |
| Exceção do coordenador (defere sozinho) | `ProcessoValidator.temVotoCoordenadorFavoravel` / `favoraveisNecessariosParaDeferir` |
| Bloqueio da pausa "Solicita informação" | `ProcessoValidator.validarPausaDecisao` / `temPedidoInformacaoAtivo` |
| Contagem de pareceres | `ProcessoValidator.contarFavoraveis` / `contarNaoFavoraveis` / `contarRespondidos` |
| Sugestão e validação da decisão | `ProcessoValidator.sugerirDecisao` / `validarDecisao` / `validarContagemVotos` |
| Quem decide, quando e com quantos votos | `ProcessoService.decidir` / `tentarDecisaoAutomatica` |

Tudo o que se propõe aqui é **aditivo ou de apresentação**: um badge, um rótulo,
uma frase de documento, uma ação de auditoria mais específica, um campo *novo* e
*nullable* que apenas **registra** o que já foi decidido. Nenhuma proposta muda
quem vence a votação, nem altera o resultado de nenhum processo — existente ou
futuro.

**Uma única exceção a essa tranquilidade, tratada à parte:** o **Achado 1**
(nome do coordenador no PDF lido pelo cargo "ao vivo" em vez do snapshot do voto)
é o único item que encosta em algo **adjacente** à lógica de decisão. Ele foi
isolado na **Fase F1**, marcado com **risco ALTA**, e **exige aprovação explícita
do dono do produto antes de qualquer linha de código, sem exceção** — ver §4.0.

---

## 0. Metodologia e base de comparação

- **Suíte completa como base:** `mvn test` com JDK 21 → **932 testes, 0 falhas,
  0 erros, 0 pulados** (agregado dos relatórios do surefire). Nada foi alterado,
  então esta é a contagem-base para qualquer PR de implementação futura.
- **Aplicação subida de verdade** (jar real, perfil `dev`, H2 em arquivo próprio,
  porta 3011, diretório de anexos isolado) e dirigida ponta a ponta por HTTP,
  como um operador faria: criação de usuários, envio de solicitação pelo Portal
  do Solicitante, triagem/conversão, anexo do documento clínico, registro do
  envio, votos reais pelo Portal do Avaliador, decisão, reabertura, redecisão.
- **Todas as evidências abaixo são reais**, extraídas do HTML servido, dos PDFs
  gerados (texto extraído com `pypdf`) e do ZIP de dossiê baixado — nenhuma é
  hipotética ou inferida só por leitura de código.

### Cenários construídos

| # | Processo | Como foi montado | Resultado |
|---|---|---|---|
| A | `01/2026` | Coordenador (membro 1, `coordenador = true`) vota **Favorável sozinho** | Deferido automático com **1 voto** |
| B | `01/2026` (cont.) | ADMIN **reabre** e **redecide** como Cancelado | Decisão trocada |
| C | `02/2026` | 1 Favorável → coordenador pede **Solicita informação** (pausa) → outro Favorável → operador **retoma a análise** | Deferido automático **na retomada**, sem o 3º voto |
| D | `03/2026` | 2 Favoráveis (um deles do coordenador) → depois o **cargo de coordenador muda de mão** para outro médico | Deferido; documentos regerados |

### A rede de segurança que JÁ existe hoje sobre a regra de votação

Antes de sugerir qualquer coisa, a cobertura que hoje protege a regra de votação
foi executada de verdade e conferida — **não presumida**.

```
$ JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn test -Dtest='ProcessoValidatorTest,\
  ProcessoServiceTest,SnapshotCoordenadorVotoIntegrationTest,ProcessoDecisaoControllerTest,\
  ProcessoDecisaoTransacaoIntegrationTest,DecisaoAutomaticaSchedulerIntegrationTest,\
  AvaliadorVotoDuranteSolicitaInformacaoIntegrationTest,AvaliadorControllerTest,\
  FluxoProcessoServiceTest'

[INFO] Tests run: 169, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Resultado por classe (saída real do Surefire):

| Classe | Testes | O que protege |
|---|---|---|
| `ProcessoValidatorTest` | 36 | maioria simples, exceção do coordenador, pausa, motivo, contagens |
| `ProcessoServiceTest` | 56 | `decidir`, `tentarDecisaoAutomatica`, `retomarAposInformacao`, `reabrir` |
| `FluxoProcessoServiceTest` | 30 | etapas/gating do checklist |
| `AvaliadorControllerTest` | 22 | voto no portal, justificativa obrigatória, autorização |
| `DecisaoAutomaticaSchedulerIntegrationTest` | 9 | varredura agendada (H2 real) |
| `ProcessoDecisaoControllerTest` | 7 | rejeição de decisão sem os votos exigidos |
| `AvaliadorVotoDuranteSolicitaInformacaoIntegrationTest` | 5 | pausa não bloqueia os demais avaliadores (H2 real) |
| `SnapshotCoordenadorVotoIntegrationTest` | 2 | **snapshot do papel de coordenador** (H2 real) |
| `ProcessoDecisaoTransacaoIntegrationTest` | 2 | decisão sobrevive a falha de pós-processamento |

E a **suíte inteira** também está verde na base atual: **932 testes, 0 falhas,
0 erros, 0 pulados** (`mvn test`, JDK 21). Qualquer PR nascido deste relatório
deve partir desses números.

Destaque para `SnapshotCoordenadorVotoIntegrationTest`, que já cobre justamente
os dois casos difíceis do Achado 1 **no lado da regra**:
`votoDoCoordenadorContinuaDecisivoMesmoDepoisDeOCargoMudarDeMao` e
`parecerAntigoSemSnapshotNaoContaComoVotoDeCoordenadorMesmoQueOMembroSejaCoordenadorHoje`.
Ou seja: **a regra de decisão já está protegida contra a troca de cargo**. O que
o Achado 1 mostra é que a *impressão do nome no documento* ficou de fora dessa
proteção.

### Correção de premissa da missão (importante)

O enunciado desta vistoria pedia para reavaliar o **Achado 4 da "Vistoria de bugs
de 2026-08-03"** (`temVotoCoordenadorFavoravel` lendo o campo `coordenador` "ao
vivo"), descrito no `CLAUDE.md` como pendente de decisão de produto.

**Esse achado já foi corrigido** — commit `3dac941`, *"feat(processos): snapshot
do papel de coordenador no instante do voto (Achado 4)"*. Hoje existe
`Parecer.eraCoordenadorNoVoto` (`Boolean`, nullable) gravado por
`AvaliadorController.registrarVoto:408`, e `ProcessoValidator
.temVotoCoordenadorFavoravel:71-75` lê **o snapshot**, não o cargo atual. O
`CLAUDE.md` ainda descreve o achado como aberto — **o texto do guia está
desatualizado nesse ponto** (registrar isso é, por si só, uma correção de
documentação a fazer).

Porém, **a migração para o snapshot foi incompleta**: sobrou exatamente um ponto
lendo a flag ao vivo, e é justamente o que imprime o **nome do médico no
documento oficial**. Ver o Achado 1, que é a consequência direta e mais grave
disso.

---

## 1. Quadro-resumo dos achados

| # | Achado | Severidade | Depende de decisão de produto? |
|---|---|---|---|
| 1 | Relatório Final e dossiê atribuem a exceção do coordenador ao **médico errado** | **ALTA** | **SIM — aprovação explícita obrigatória** (§4, Fase F1) |
| 2 | Dossiê afirma *"1 favorável (regra: 2 de 3 defere)"* num processo **deferido** | **ALTA** | Não — é bug |
| 3 | *"Maioria formada"* / *"regra 2 de 3 favoráveis"* em processo decidido por **1 voto** | **ALTA** | Não — é bug |
| 4 | *"Dispensado pela maioria"* onde **não houve maioria** nenhuma | MÉDIA | Não — é bug de texto |
| 5 | `PROCESSO_DECIDIDO` na auditoria **não distingue** qual regra foi aplicada | MÉDIA | Parcial |
| 6 | Badge do coordenador existe em **1 tela só** (o gatilho original do usuário) | MÉDIA | Sim (onde exibir) |
| 7 | Pausa sobreposta pela retomada não deixa rastro; **justificativa é apagada** | MÉDIA | Sim |
| 8 | Reabertura: **nenhuma tela** mostra que o processo já foi decidido antes | MÉDIA | Sim |
| 9 | Relatório Final **obsoleto** continua anexado após reabrir | BAIXA | Não |
| 10 | Avaliador que não votou: o processo **some** do portal sem aviso | BAIXA | Sim |
| 11 | Nenhuma tela marca **qual** parecer é o do coordenador | BAIXA | Sim |
| 12 | Cancelamento: **sem regressão** (verificado) | — | — |

---

## 2. Achados detalhados

### Achado 1 — O documento oficial nomeia o médico ERRADO como coordenador  ·  **ALTA**

**Onde:** `src/main/java/br/gov/saude/sgpur/service/RelatorioService.java`,
método `paragrafoRegraDecisao`, linhas **466-471**.

A decisão de *se* a exceção do coordenador vale já usa o snapshot (correto). Mas
para descobrir **o nome** de quem exerceu a exceção, o código volta a filtrar
pela flag ao vivo:

```java
if (processoService.deferidoPeloCoordenador(p)) {          // ← snapshot (correto)
    String nomeCoordenador = p.getPareceres().stream()
        .filter(par -> par.getResultado() == ResultadoParecer.FAVORAVEL
            && par.getMembro() != null && par.getMembro().isCoordenador())  // ← AO VIVO (errado)
        .map(par -> par.getMembro().getNome())
        .findFirst()
        .orElse("Coordenador da CET-RS");
```

**Evidência real (cenário D).** Relatório Final do processo `03/2026`, gerado
antes e depois de o cargo de coordenador passar do membro 1 para o membro 2:

```
ANTES  →  Deferido pelo voto do Coordenador da CET-RS (Coordenador Teste), que defere
          isoladamente, conforme exceção regimental que dispensa a maioria de 2 de 3.

DEPOIS →  Deferido pelo voto do Coordenador da CET-RS (Avaliador HCPA Teste), que defere
          isoladamente, conforme exceção regimental que dispensa a maioria de 2 de 3.
```

O médico nomeado na segunda versão **nunca foi coordenador quando votou**. O
documento é o Relatório Final — peça de arquivo e auditoria — e o mesmo texto
errado entra no **PDF dentro do ZIP do dossiê** (verificado).

Dois modos de falha, ambos reproduzidos:
- **Atribuição errada** (acima): outro médico que votou Favorável assumiu o cargo
  depois → o documento credita a ele uma prerrogativa regimental que não exerceu.
- **Perda do nome:** se ninguém que votou Favorável for o coordenador atual, cai
  no fallback `"Coordenador da CET-RS"` — o documento deixa de identificar quem
  decidiu.

**Proposta:** trocar o filtro por `Boolean.TRUE.equals(par.getEraCoordenadorNoVoto())`,
o mesmo predicado de `ProcessoValidator.temVotoCoordenadorFavoravel`. Melhor
ainda: **não deixar esse predicado duplicado em lugar nenhum** — expor
`ProcessoValidator.parecerDoCoordenador(Processo): Optional<Parecer>` e fazer
`RelatorioService` (e qualquer consumidor futuro) chamá-lo. Pareceres antigos com
snapshot `null` continuam caindo no fallback genérico, que é o comportamento
conservador já documentado no javadoc de `Parecer.eraCoordenadorNoVoto`.

**Importante — o que este achado NÃO é.** A **regra** de decisão já está correta e
já protegida por teste: `ProcessoValidator.temVotoCoordenadorFavoravel` usa o
snapshot, e `SnapshotCoordenadorVotoIntegrationTest` cobre a troca de cargo (§0).
Nenhum processo foi ou será decidido errado por causa disto — **o defeito é
exclusivamente o nome impresso no documento**.

**Ainda assim, é o único achado desta vistoria que encosta no conceito de "quem é
o coordenador para efeito de voto".** Por isso está isolado na **Fase F1**,
marcado com **risco ALTA**, com bateria de testes ampliada e **dependente de
aprovação explícita do dono do produto antes de qualquer linha de código, sem
exceção** — ver §4, Fase F1, que também detalha o que **não pode** ser tocado
junto.

---

### Achado 2 — O dossiê afirma que a própria regra citada foi violada  ·  **ALTA**

**Onde:** `src/main/java/br/gov/saude/sgpur/service/ExportacaoProcessoService.java`,
linhas **268-270**.

```java
campo(sb, "Pareceres favoraveis", processoService.contarFavoraveis(p)
    + " (regra: " + ProcessoService.FAVORAVEIS_PARA_DEFERIR + " de "
    + ProcessoService.AVALIADORES_POR_PROCESSO + " defere)");
```

**Evidência real (cenário A).** `Resumo-do-Processo.txt` dentro do ZIP de
`/processos/1/exportar`, com o processo **Deferido**:

```
Pareceres favoraveis: 1 (regra: 2 de 3 defere)
...
5. DECISAO FINAL
Resultado: Deferido
```

O texto declara literalmente que o processo foi deferido com 1 favorável sob uma
regra que exige 2 — sem citar a exceção que o autoriza. Um leitor externo
(auditoria, judicial, controle interno) lê isso como decisão irregular.

Este é **exatamente o mesmo defeito que o `RelatorioService` já corrigiu** no PDF
(item B3 do relatório V2 / PR #45, ver o comentário longo em
`RelatorioService:312-323`) e que sobreviveu no dossiê porque a correção foi
aplicada só num dos dois geradores.

**Agravante — contradição dentro do mesmo pacote.** O ZIP do processo `03/2026`
contém, lado a lado:
- `Resumo-do-Processo.txt`: `Pareceres favoraveis: 2 (regra: 2 de 3 defere)`
- `Relatorio-Final.pdf`: `Deferido pelo voto do Coordenador da CET-RS (...), que
  defere isoladamente, conforme exceção regimental que dispensa a maioria de 2 de 3.`

Os dois documentos do mesmo arquivo morto discordam sobre qual regra decidiu o
processo.

**Proposta:** o dossiê deve consumir a **mesma fonte única** proposta na §3, em
vez de reconstruir a frase da regra por conta própria.

---

### Achado 3 — "Maioria formada" e "regra 2 de 3" em processo decidido por 1 voto  ·  **ALTA**

**Onde:** `src/main/java/br/gov/saude/sgpur/service/FluxoProcessoService.java`,
linhas **130-136** (etapa Respostas) e **174-179** (etapa Decisão final).

O texto é montado a partir de `processoService.sugerirDecisao(p)`, que retorna
`DEFERIDO` **também** pela exceção do coordenador (`ProcessoValidator
.sugerirDecisao:105-107` faz curto-circuito nela). O texto, porém, afirma
"maioria" e cita a regra de 2 de 3 incondicionalmente.

**Evidência real (cenário A), tela de detalhe de `/processos/1` já Deferido:**

```
Maioria formada (Deferido) - pronto para decidir. Favoraveis: 1.
```

Três problemas numa frase: (a) não houve maioria; (b) "Favoraveis: 1" ao lado de
"Maioria formada" é autocontraditório; (c) "pronto para decidir" num processo
**já decidido**.

**Evidência real (cenário A, após reabertura — etapa Decisão final):**

```
Sugestao automatica: Deferido (regra 2 de 3 favoraveis).
```

com um único voto favorável registrado.

**Raio de alcance — não é só a tela.** `FluxoProcessoService.montarEtapas` é a
fonte da seção **"4. Andamento do processo"** do Relatório Final PDF
(`RelatorioService:400-409`) e da seção **"6. MOVIMENTAÇÃO"** do dossiê. Ou seja,
o mesmo Relatório Final que na seção 2 explica corretamente a exceção do
coordenador, uma página adiante imprime:

```
Concluída   Respostas dos médicos   Maioria formada (Deferido) - pronto para decidir.
                                    Favoraveis: 1.
```

**O documento oficial se contradiz sozinho, com uma página de distância.**

**Proposta:** os textos de `montarEtapas` devem consultar a regra efetivamente
aplicada (§3) em vez de assumir maioria; e o rótulo "pronto para decidir" deve
sumir quando `status.isFinalizado()`.

**Observação marginal (fora do escopo, mas registrada):** os textos vindos de
`FluxoProcessoService` entram no PDF **sem acentuação** ("Enviado aos 3 medicos.",
"insercao da urgencia renal"), destoando do resto do documento, que é acentuado.
Se a §3 for implementada, é a oportunidade natural de acertar isso — respeitando
a decisão deliberada de **não** acentuar `ResultadoParecer.descricao` (§10 do
`RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md`).

---

### Achado 4 — "Dispensado pela maioria" onde não houve maioria  ·  MÉDIA

**Onde:** `processos/detalhe.html:822` e `:852`; `RelatorioService:265`
(`"Dispensado pela maioria"`); `web/dto/PainelLinha.java:101` (`"Dispensado"`).

Quando o coordenador defere sozinho, os outros 2 pareceres ficam com
`resultado == null` e são rotulados **"Dispensado pela maioria"** — mas o que os
dispensou foi o voto isolado do coordenador, não uma maioria.

**Evidência real (cenário A):** o Relatório Final do processo `01/2026` traz, na
mesma tabela, o coordenador como `Favorável` e os outros dois como
`Dispensado pela maioria`, logo abaixo da frase que diz que a decisão
*"dispensa a maioria de 2 de 3"*.

O termo neutro do Painel (`"Dispensado"`, sem "pela maioria") não tem esse
problema — é o vocabulário certo para generalizar.

**Proposta:** rótulo derivado da regra aplicada — *"Dispensado pela maioria"*
quando foi maioria simples, *"Dispensado pelo voto do Coordenador"* na exceção,
*"Dispensado (processo cancelado)"* no cancelamento. Uma única função,
consumida pelas 3 superfícies.

---

### Achado 5 — A auditoria não distingue qual regra decidiu  ·  MÉDIA

**Onde:** três call-sites gravam a mesma ação `PROCESSO_DECIDIDO` com textos
livres diferentes, e um quarto usa ação própria:

| Origem | Arquivo:linha | Texto gravado |
|---|---|---|
| Decisão manual | `ProcessoDecisaoController:336` | `Processo NN/AAAA - <decisão>` |
| Automática na retomada da pausa | `ProcessoDecisaoController:175` | `... - decisao automatica: <decisão>` |
| Automática após voto no portal | `AvaliadorController:497` | `... - decisao automatica portal: <decisão>` |
| Varredura agendada | `DecisaoAutomaticaScheduler:229` | ação `PROCESSO_DECIDIDO_VARREDURA` |

**Evidência real (cenário A), `/auditoria`:**

```
PROCESSO_DECIDIDO   Processo 01/2026 - decisao automatica portal: Deferido
PARECER_VOTADO      Processo 01/2026 - Coordenador Teste - Favoravel
```

Nada diz que a decisão veio do **voto único do coordenador**. Para descobrir, o
ADMIN precisa cruzar `PARECER_VOTADO` na linha de cima, contar quantos votos
existiam e saber de cor quem era coordenador **naquela data** — informação que a
própria tela de membros não guarda historicamente.

Dois desalinhamentos menores no mesmo ponto:
- `AvaliadorController:497` e `ProcessoDecisaoController:175` gravam **sem IP**,
  enquanto o caminho manual (`:336`) grava com IP. Decisões automáticas têm um
  ator humano por trás (o voto/clique que as disparou).
- O vocabulário "portal"/"automatica"/vazio é ad-hoc, difícil de filtrar.

**Proposta:** manter a ação `PROCESSO_DECIDIDO` (não criar valor novo — não há
CHECK constraint em `log_auditoria.acao`, mas estabilidade de vocabulário facilita
o filtro já existente em `/auditoria`) e padronizar o detalhe com o rótulo da
regra aplicada, vindo da mesma fonte única da §3. **Nunca** incluir nome de
paciente nem justificativa clínica — só número do processo, decisão e regra
(recaídas desse tipo já ocorreram duas vezes, ver `CLAUDE.md`).

---

### Achado 6 — O badge existe em uma tela só  ·  MÉDIA  *(gatilho original)*

Confirmado por leitura **e** contra o sistema real. `deferidoPeloCoordenador` é
exposto ao modelo em **um** lugar (`ProcessoDetalheController:475`) e consumido em
**um** template (`processos/detalhe.html:22`).

**Evidência real (cenário A), processo Deferido pelo coordenador sozinho:**

| Superfície | O que mostra |
|---|---|
| `/processos/1` (detalhe) | `Deferido pelo Coordenador da CET-RS` ✅ |
| `/processos` (lista) | `Deferido` — sem marca |
| `/arquivo` | `Deferido` — sem marca |
| `/` (Painel) | `Deferido` — sem marca |
| Relatório Final PDF | frase correta na §2, **contraditória** na §4 (Achado 3) |
| Dossiê (`Resumo-do-Processo.txt`) | sem marca, e ainda cita a regra errada (Achado 2) |
| Auditoria | sem marca (Achado 5) |
| `RelatorioAnualService` / `RelatorioAvaliadorService` | nenhuma menção (`grep` = 0) |
| E-mail ao solicitante (`EmailTemplateService:307`) | sem marca |
| Portal do Avaliador | sem marca (Achado 11) |

**Sobre o e-mail e o Portal do Solicitante — recomendação de NÃO mudar.** O
`emailDeferido` informa o resultado à equipe solicitante; a mecânica interna de
qual regra regimental decidiu não é assunto do destinatário e expô-la só
convidaria a questionamento sobre o mérito do voto individual. Mesma coisa na
tela do solicitante (verificada: mostra *"Deferido — Urgência renal reconhecida"*,
adequado). **Decisão de produto a confirmar**, mas a recomendação técnica é
manter como está.

---

### Achado 7 — A pausa sobreposta some sem deixar rastro  ·  MÉDIA

**Onde:** `ProcessoService.retomarAposInformacao:328-341`.

O reset do parecer que pediu informação é **completo e deliberado** (o javadoc
explica: "pendência limpa", preservando o não-repúdio do voto definitivo):

```java
par.setResultado(null);
par.setDataResposta(null);
par.setDataHoraVoto(null);
par.setVotadoPor(null);
par.setOrigem(null);
par.setJustificativa(null);   // ← o texto clínico do pedido é destruído
```

Quando a retomada é seguida de decisão automática imediata — comportamento
**confirmado como correto** pelo dono do produto (achado B de
`RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md`) — o avaliador que
pediu a informação nunca mais vota, e o parecer fica `null` para sempre.

**Evidência real (cenário C).** Processo `02/2026`: coordenador pediu informação
com a justificativa *"Faltou o exame de imagem recente."*; após a retomada, o
processo foi Deferido na hora, e:

- busca pelo texto da justificativa no detalhe do processo → **0 ocorrências**
  (apagada do banco, irrecuperável);
- Relatório Final, tabela de pareceres → `CET-RS - Coordenador Teste ·
  Dispensado pela maioria · -`. **Nada** indica que esse médico chegou a votar,
  que pediu informação complementar, nem que o pedido foi sobreposto.

A trilha existe **só** em `/auditoria` (ADMIN), espalhada em duas linhas
cronologicamente invertidas (`PARECER_VOTADO ... Solicita informacao` e
`ANALISE_RETOMADA ... reabertos como pendencia limpa`) que precisam ser
reconstruídas à mão.

Isso colide com uma regra documentada: a justificativa é **obrigatória** em voto
`SOLICITA_INFORMACAO` justamente porque *"o operador depende desse texto pronto
para redigir o pedido de informação complementar"* (`CLAUDE.md`). O sistema exige
o texto e depois o apaga.

**Proposta (depende de decisão de produto).** Duas opções, sem mudar a regra de
decisão:
- **(a) Histórico de parecer** — entidade nova `HistoricoParecer` (staging/append-only,
  no espírito de `SolicitacaoOnline`/`AnexoSolicitacaoOnline`) gravada **antes** do
  reset, guardando resultado, data, autor e justificativa. Mais completo; permite
  ao Relatório Final registrar "pediu informação em dd/mm, análise retomada em
  dd/mm". Custo: tabela nova.
- **(b) Campos de "pausa" no próprio `Processo`** — ex.
  `houvePedidoInformacao` / `dataUltimaRetomada` (nullable, **sem backfill**, mesmo
  raciocínio de `Parecer.ultimoLembreteEm`). Bem mais barato, mas guarda só o
  fato, não o texto.

Recomendação: **(a)** se o texto clínico tiver valor de arquivo; **(b)** se
bastar registrar que a pausa existiu.

---

### Achado 8 — Reabertura não deixa marca em nenhuma tela do processo  ·  MÉDIA

**Onde:** `ProcessoService.reabrir` — apaga `dataDecisao`, `motivoIndeferimento`,
`emailEnviadoSolicitante` e `mensagemResposta`.

**Evidência real (cenário B).** Processo `01/2026`, Deferido pelo coordenador →
reaberto pelo ADMIN → redecidido como Cancelado. Depois disso:

- busca por "reaberto", "anteriormente", "já foi decidido" no detalhe →
  **nenhuma ocorrência**;
- a tela apresenta o Cancelado como se fosse a **única** decisão que já existiu;
- a única trilha é `/auditoria` (ADMIN-only):
  ```
  PROCESSO_DECIDIDO   Processo 01/2026 - Cancelado
  PROCESSO_REABERTO   Processo 01/2026 reaberto (voltou para Enviado)
  PROCESSO_DECIDIDO   Processo 01/2026 - decisao automatica portal: Deferido
  ```
  Note que a leitura correta exige percorrer a lista **de baixo para cima** e
  saber que a segunda linha anula a terceira. O OPERADOR (que não acessa
  `/auditoria`) não tem nenhum caminho para essa informação.

Vale notar que o `PROCESSO_REABERTO` **não registra qual era a decisão anulada**
nem o IP — só "voltou para Enviado".

**Proposta (depende de decisão de produto):** um contador/registro leve
(ex. `Processo.reaberturas`, ou o mesmo histórico do Achado 7) alimentando um
badge discreto *"Reaberto Nx"* com `title` explicando, nas mesmas superfícies do
Achado 6, e uma linha no Relatório Final. No mínimo, enriquecer o detalhe de
`PROCESSO_REABERTO` com a decisão anulada e o IP.

---

### Achado 9 — Relatório Final obsoleto continua anexado após a reabertura  ·  BAIXA

**Evidência real (cenário B).** Logo após `POST /processos/1/reabrir` (status de
volta para **Enviado**, decisão anulada), o anexo `RELATORIO_FINAL` da decisão
anulada continua **listado no detalhe e baixável**, e sua capa diz:

```
RELATÓRIO FINAL
PROCESSO CET-RS 01/2026
RESULTADO
DEFERIDO
```

Ou seja: um processo que **não está deferido** oferece para download um documento
institucional afirmando que está.

A janela é **limitada**: `DecisaoFinalService:75-77` gera o novo e remove os
antigos na decisão seguinte (confirmado — após redecidir como Cancelado o anexo
passou a dizer `RESULTADO: CANCELADO`). Mas a janela dura de "reabrir" até
"redecidir", que pode ser dias, e é permanente se o processo nunca for redecidido.

**Proposta:** `reabrir` remover o `RELATORIO_FINAL` vigente (é derivado, sempre
regenerável) ou renomeá-lo/marcá-lo como anulado. Risco baixo; não depende de
decisão de produto, mas convém confirmar se a equipe prefere **remover** ou
**preservar marcado**.

---

### Achado 10 — Para o avaliador que não votou, o processo simplesmente evapora  ·  BAIXA

**Evidência real (cenário A).** O membro 2 recebeu o convite e tinha parecer
pendente. Depois de o coordenador deferir sozinho:

- `/avaliador` (portal dele) → *"Nenhuma avaliação pendente no momento"*, e busca
  por `01/2026` na página inteira → **0 ocorrências** (não aparece nem no
  "Histórico das minhas avaliações", que só lista o que ele votou);
- `/avaliador/1` (acesso direto) → **403**.

Nenhuma mensagem explica que o processo foi decidido sem o voto dele. Do ponto de
vista do médico, um pedido que estava na fila desapareceu. Vale igualmente para a
maioria simples comum (2 de 3) e para o cenário C.

**Proposta (depende de decisão de produto):** uma linha no histórico do portal do
tipo *"Processo 01/2026 — decidido antes do seu parecer (dispensado)"*, **sem
revelar o resultado nem quem votou o quê** (imparcialidade: o portal segue
mostrando apenas iniciais e nada dos co-avaliadores). Alternativa mais barata e
igualmente honesta: só o registro do fato, sem o desfecho.

---

### Achado 11 — Nenhuma tela marca QUAL parecer é o do coordenador  ·  BAIXA

O snapshot `Parecer.eraCoordenadorNoVoto` **não é exposto em nenhum template**
(`grep` nos templates = 0). Na tela de detalhe, o badge do topo diz *"Deferido
pelo Coordenador da CET-RS"*, mas a tabela do card **Respostas dos Avaliadores**
não identifica de qual das 3 linhas veio essa prerrogativa.

No cenário A isso passou despercebido só porque o membro de teste se chama
"Coordenador Teste"; com um nome real ("Dr. João Silva") não haveria nenhuma
pista na tela.

**Proposta:** um badge discreto *"Coordenador CET-RS"* na linha do parecer, ligado
ao **snapshot** (não ao cargo atual — senão recria o Achado 1 na tela), com
`title` explicando que o papel é o do momento do voto.

---

### Achado 12 — Cancelamento: sem regressão  ·  informativo

Verificado rapidamente, conforme o escopo pedia. Processo `01/2026` cancelado via
`ProcessoService.decidir(id, CANCELADO, null)`: o Portal do Solicitante mostra
**"Cancelado"** (não "Reprovada" — a correção de 2026-07-29 segue de pé), o
`badgeEncerramento` trata `CANCELADO` como "Encerrado", `FluxoProcessoService`
não trava mais o progresso, e o Relatório Final regenerado exibe
`RESULTADO: CANCELADO` sem imprimir frase de regra de maioria. Nada a fazer.

---

## 3. A proposta central: uma fonte única para "qual regra decidiu este processo"

Os achados 2, 3, 4, 5 e 6 são **o mesmo defeito estrutural**: cada superfície
reconstrói, por conta própria, a frase sobre a regra de decisão. Hoje existem
**quatro** reconstruções independentes (`RelatorioService.paragrafoRegraDecisao`,
`FluxoProcessoService.montarEtapas`, `ExportacaoProcessoService.montarResumo`,
`processos/detalhe.html`), e apenas **uma** delas conhece a exceção do
coordenador. Corrigir cada uma na mão só garante que voltem a divergir.

O projeto já tem o padrão certo para isso — `SituacaoPedidoView` (calculada uma
vez no controller e consumida pelo template inteiro) e `EtapaFluxo.tom()` /
`StatusProcesso.getTom()` (vocabulário fechado + fragment `layout :: tomBadge`).
A proposta é aplicar o mesmo padrão aqui.

### 3.1 `RegraDecisao` — vocabulário fechado

`src/main/java/br/gov/saude/sgpur/service/dto/RegraDecisao.java` (novo), com um
rótulo curto (badge), um rótulo longo (documento) e o tom semântico:

| Valor | Rótulo curto | Quando |
|---|---|---|
| `MAIORIA_SIMPLES` | *Maioria simples (2 de 3)* | ≥2 votos no mesmo sentido |
| `VOTO_COORDENADOR` | *Voto único do Coordenador CET-RS* | snapshot favorável do coordenador |
| `CANCELAMENTO` | *Cancelado* | `CANCELADO` (nenhuma regra de maioria) |
| `NAO_DECIDIDO` | — | processo ainda em tramitação |

Calculada por **`ProcessoValidator.regraAplicada(Processo)`** — junto de
`temVotoCoordenadorFavoravel`, que já é a fonte única das contagens e é chamada
tanto pelo serviço quanto pelos controllers. É função pura, sem acesso a banco,
como o resto da classe.

### 3.2 O que fica derivado e o que exige persistência

**Derivável do estado atual (não precisa de coluna nova):** `MAIORIA_SIMPLES`,
`VOTO_COORDENADOR`, `CANCELAMENTO`. Recomendo começar **só por aqui** — resolve os
achados 2, 3, 4 e 6 inteiros, sem nenhuma mudança de schema e sem decisão de
produto pendente.

**NÃO derivável:** *"decidida automaticamente ao retomar a pausa"* e *"decidida
após reabertura"*. Depois do fato, o estado final de um processo decidido na
retomada é indistinguível de um decidido normalmente — a informação **só existe
no instante da ação**. Persistir isso é o que os achados 5, 7 e 8 pedem, e é
onde entra a decisão de produto:

- **Mínimo:** um `Processo.regraDecisaoAplicada` (`@Enumerated(STRING)`, **nullable**)
  gravado por `ProcessoService.decidir`. **Atenção ao pitfall documentado no
  `CLAUDE.md`:** coluna de enum nova em tabela já criada pelo Hibernate pode
  esbarrar em CHECK constraint congelada — hoje `processo` **não tem** CHECK
  (verificado em produção em 2026-08-03), e o `EnumCheckConstraintValidator`
  avisa no boot se isso mudar. Sendo nullable, **não exige backfill**; linhas
  antigas caem na regra derivada.
- **Completo:** o histórico do Achado 7/8, que resolve pausa e reabertura juntos.

### 3.3 Superfícies de consumo

Um fragment novo `layout :: badgeRegraDecisao(p, classes)` — irmão do
`badgeEncerramento`, que já existe **exatamente** por esse motivo ("virou fragment
justamente para não existir uma quarta cópia dessa regra para divergir de novo").
Consumido por `processos/detalhe.html`, `processos/lista.html`,
`arquivo/lista.html` e `dashboard.html`; e o rótulo longo por `RelatorioService`,
`ExportacaoProcessoService`, `FluxoProcessoService` e pela auditoria.

Exibir o badge **só quando a regra não é a padrão** (`VOTO_COORDENADOR` e, se
persistido, as automáticas) — poluir as listas com "maioria simples" em todo
processo não informa nada e é o caminho mais curto para o usuário pedir que se
remova tudo.

---

## 4. Plano de trabalho faseado (um PR por fase)

Cada fase é testável isoladamente. **Nenhuma fase altera a regra de negócio** —
quem pode decidir, quando e com quantos votos permanece **exatamente** como está
(ver o quadro no topo deste documento e a regra de ouro da F0 abaixo).

**Ordem sugerida de execução:** se o objetivo for entregar valor com o menor
risco possível, comece por **F2** (todo o ganho de visibilidade, sem tocar em
nada da votação) e trate a **F1** à parte, quando houver aprovação explícita.
A numeração abaixo é por gravidade do achado, não por ordem obrigatória.

### F0 — Regra de ouro para todas as fases

Nenhuma fase pode alterar `ProcessoValidator` (contagens, `sugerirDecisao`,
`validarDecisao`, `validarContagemVotos`, `validarPausaDecisao`,
`temVotoCoordenadorFavoravel`) nem `ProcessoService.decidir`/
`tentarDecisaoAutomatica` em nada que mude **qual decisão sai** de um conjunto de
votos. Acréscimo de método de *leitura* (ex. `parecerDoCoordenador`) é permitido;
alteração de predicado existente, não. Toda fase deve terminar com os **932
testes** verdes e, em especial, com as 169 da tabela da §0 intactas.

### F1 — Nome do coordenador no documento oficial  ·  Achado 1

> ### ⚠ RISCO ALTA · EXIGE APROVAÇÃO EXPLÍCITA DO DONO DO PRODUTO ANTES DE CODAR

**Esta fase é diferente de todas as outras deste relatório e não deve ser tratada
como "mais uma correção de visibilidade".** É o único ponto em que o código a
alterar encosta na noção de *"quem é o coordenador para efeito de voto"* — o
mesmo conceito que sustenta a exceção regimental que defere um processo sozinho.
O restante do relatório é badge e texto; aqui não.

**Escopo estrito, para dimensionar o risco com honestidade.** A regra de decisão
**já usa o snapshot** e **já está coberta por teste** (`SnapshotCoordenadorVoto
IntegrationTest`, ver §0). O que se propõe mudar é **exclusivamente** a busca do
*nome a imprimir* em `RelatorioService.paragrafoRegraDecisao:466-471`, hoje feita
com `par.getMembro().isCoordenador()` (ao vivo). Ainda assim, por tocar no mesmo
conceito, vale o rigor máximo:

- **Nenhuma linha de `ProcessoValidator.temVotoCoordenadorFavoravel`,
  `favoraveisNecessariosParaDeferir`, `sugerirDecisao` ou `validarContagemVotos`
  pode ser alterada.** O método novo `parecerDoCoordenador(Processo):
  Optional<Parecer>` é **somente leitura** e deve ser escrito de forma que
  `temVotoCoordenadorFavoravel` **continue existindo com o corpo atual** — nada de
  "reaproveitar" reescrevendo o predicado da regra em termos do método novo nesta
  fase. Se um dia isso for desejável, é outro PR, com outra aprovação.
- **PR isolado.** F1 não pode viajar junto com F2 nem com nenhuma outra fase.
- **Revisão humana obrigatória do diff**, mesmo com a suíte verde.

**Bateria de testes exigida (a mais rigorosa que o projeto pratica — todos
`@SpringBootTest` com H2 real e serviços reais; `@WebMvcTest` + `@MockitoBean`
é incapaz de expressar estes cenários):**

1. Coordenador vota Favorável sozinho → processo é **Deferido** com 1 voto
   (confirma que a regra continua idêntica após a mudança).
2. Cenário do Achado 1: voto do coordenador → **cargo muda de mão** para outro
   médico que também votou Favorável → o PDF é **gerado de verdade** e o nome
   impresso continua sendo o do **votante original**, nunca o do coordenador atual.
3. Cargo muda de mão para um médico que **não votou** → o PDF continua nomeando o
   votante original (não cai no fallback genérico por engano).
4. **Processo já decidido não pode ser afetado retroativamente:** após a troca de
   cargo, `status`, `dataDecisao` e `motivoIndeferimento` do processo decidido
   permanecem **byte a byte** os mesmos, e `deferidoPeloCoordenador` continua
   `true`.
5. **Decisão futura usa o snapshot, não o cadastro atual:** um processo *novo*,
   aberto depois da troca, em que o **ex**-coordenador vota Favorável sozinho,
   **não** é deferido por voto único; e um em que o **novo** coordenador vota
   Favorável sozinho, **é**.
6. Parecer legado com snapshot `null` → não conta como voto de coordenador e o
   documento cai no fallback genérico `"Coordenador da CET-RS"` (comportamento
   conservador atual, preservado).
7. Regressão do documento: processo deferido por **maioria simples comum** (sem
   coordenador) continua imprimindo a frase de maioria, sem citar coordenador.

**Alternativa de risco ainda menor, se o dono do produto preferir:** deixar F1
para depois e implementar antes só o **Achado 9** (remover o Relatório Final
obsoleto na reabertura), que é independente, pequeno e não encosta em nada da
votação.

### F2 — `RegraDecisao` derivada + badge reutilizável  ·  Achados 2, 3, 4, 6
O grosso do valor, ainda sem mudança de schema.
- `service/dto/RegraDecisao.java` + `ProcessoValidator.regraAplicada`.
- Fragment `layout :: badgeRegraDecisao`; aplicar em detalhe, lista, arquivo, Painel.
- Reescrever os textos de `FluxoProcessoService` (fim do "Maioria formada …
  Favoraveis: 1" e do "pronto para decidir" em processo decidido), de
  `ExportacaoProcessoService:268` e o rótulo "Dispensado pela maioria" (detalhe,
  PDF, Painel) — todos consumindo a mesma fonte.
- **Testes:** unitário de `regraAplicada` cobrindo os 4 valores; teste que
  renderiza os templates de verdade (padrão de `HomeControllerTest`/
  `ProcessoListaControllerTest` — foi por falta disso que a correção do badge
  "Encerrado" passou despercebida em 2 telas em 2026-08-04); e um teste de
  integração que **gera o PDF e o ZIP** e afirma que nenhum dos dois contém
  "regra: 2 de 3" nem "Maioria formada" num processo deferido pelo coordenador.
- **Risco:** médio (mexe em 4 templates + 3 serviços), mas mecânico.
- **Atenção:** o E2E localiza botões por texto exato — conferir
  `ProcessoDetalhePage` antes do merge (armadilha já materializada duas vezes).

### F3 — Auditoria estruturada da decisão  ·  Achado 5
- Padronizar o detalhe de `PROCESSO_DECIDIDO` nos 3 call-sites com o rótulo de
  `RegraDecisao`; passar o IP nos dois que hoje não passam.
- Enriquecer `PROCESSO_REABERTO` com a decisão anulada + IP.
- **Nunca** incluir nome de paciente ou justificativa clínica.
- **Teste:** integração real que vota, deixa decidir automaticamente e lê a linha
  de `LogAuditoria` do banco (escrita irreversível → sem mock do serviço).
- **Risco:** baixo.

### F4 — Rastro da pausa sobreposta  ·  Achado 7  ·  **exige decisão de produto**
Escolher entre `HistoricoParecer` (guarda a justificativa) e campos no `Processo`
(guarda só o fato). Expor no card Respostas e no Relatório Final.
- **Teste:** integração que percorre pausa → retomada → decisão automática e
  confirma que o rastro sobreviveu ao reset de `retomarAposInformacao`.
- **Risco:** médio (entidade/campos novos; se enum, revisar CHECK constraint).

### F5 — Histórico de reaberturas + relatório obsoleto  ·  Achados 8 e 9
- Badge *"Reaberto Nx"* nas mesmas superfícies da F2 e linha no Relatório Final.
- `reabrir` passa a remover (ou marcar) o `RELATORIO_FINAL` da decisão anulada.
- **Teste:** integração que decide → reabre → confirma que nenhum anexo
  `RELATORIO_FINAL` afirmando o resultado anulado continua acessível.
- **Risco:** baixo a médio. A parte do Achado 9 é independente e pequena — pode
  ser antecipada para antes de tudo, inclusive como primeiro PR, já que não
  encosta em nenhum ponto da regra de votação.

### F6 — Aviso ao avaliador dispensado  ·  Achado 10  ·  **exige decisão de produto**
Linha no histórico do Portal do Avaliador, preservando a imparcialidade (sem
resultado, sem co-avaliadores, só iniciais).
- **Risco:** baixo, mas mexe na tela mais sensível do ponto de vista de
  vazamento — revisar contra `ProcessoVotoView`/`ParecerVotoView`, que existem
  exatamente para fechar esse risco por design.

**Fora do plano, mas recomendado junto da F1:** atualizar o `CLAUDE.md`, que
ainda descreve o Achado 4 da vistoria de 2026-08-03 como pendente de decisão de
produto quando ele já foi implementado no commit `3dac941` (ver §0).

---

## 5. O que esta vistoria NÃO recomenda mudar

- **A regra de decisão em si — em nenhuma hipótese.** Quem decide, com quantos
  votos, sob qual exceção, e o que a pausa bloqueia: nada disso é questionado
  aqui, e nenhuma fase proposta mexe nos predicados que a implementam. Todos os
  achados são de *visibilidade*. Ver o quadro no topo do documento e a regra de
  ouro da F0.
- **A decisão imediata ao retomar a pausa** (achado B de
  `RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md`) — confirmada pelo
  dono do produto. O Achado 7 pede apenas que ela deixe rastro.
- **Expor a mecânica da decisão ao solicitante** (e-mail e Portal do Solicitante).
  Ver Achado 6.
- **Expor identidade ou voto de co-avaliadores no Portal do Avaliador** — a
  imparcialidade (só iniciais) permanece intocada em qualquer proposta acima.
- **Reverter o reset de `retomarAposInformacao`.** O reset protege o não-repúdio
  do voto definitivo; a proposta é *preservar cópia* do que foi apagado, não
  deixar de apagar.

# Relatório — "dois votos deferem, mesmo que o processo esteja em 'solicita informação'"

**Data:** 2026-08-07
**Origem:** relato do dono do produto, textual: *"dois votos deferem, mesmo que o
processo esteja em 'solicita informação'"*.
**Escopo original:** diagnóstico. **Atualização de 2026-08-07 (mesmo dia):** os
achados C e D foram **corrigidos**, aprovados explicitamente pelo usuário ("sim
corrija tudo") — ver o item 3 da seção 10 para o detalhe da correção. Achado A foi
corrigido em outra branch da mesma sessão (card de Respostas/Sugestão automática).
Achado B segue **fora de escopo**, aguardando decisão de produto do usuário.
**Base:** leitura integral do caminho de decisão no código + consulta SQL direta ao
Postgres de produção (só leitura, via SSH).

---

## 1. Veredito em uma linha

**Não houve nenhum deferimento durante a pausa em produção** — o banco prova isso
(seção 3). A regra está corretamente imposta nos 4 caminhos que gravam decisão
(seção 4). Porém, a investigação encontrou **um problema de tela** que explica o
relato quase palavra por palavra (achado A) e **três defeitos estruturais reais**
na forma como a pausa é travada (achados B, C, D), sendo o achado B
**determinístico e já armado no processo 09/2026 neste momento**.

| # | Achado | Já aconteceu em prod? | Gravidade | Status |
|---|---|---|---|---|
| A | A tela diz "Maioria já formada" e "Sugestão automática: **Deferido**" sem citar a pausa | Sim, é o que a tela mostra agora | Alta (confiança/comunicação) | Corrigido (outra branch da mesma sessão) |
| B | Retomar a análise defere **na hora**, sem o avaliador que pediu a informação votar | Não, mas está armado no 09/2026 | Alta (decisão de produto) | Fora de escopo — aguarda decisão do usuário |
| C | Reabrir (ADMIN) não restaura a pausa: processo volta a ENVIADO com parecer `SOLICITA_INFORMACAO` vivo | Não | Média | **Corrigido em 2026-08-07** |
| D | Corrida entre dois votos quase simultâneos pode furar a pausa | Não | Baixa (probabilidade) / Alta (efeito) | **Corrigido em 2026-08-07** (risco residual documentado, ver seção 10) |

---

## 2. Método

1. Releitura de **todo** o caminho de decisão, não só dos 3 métodos já conferidos
   antes nesta sessão: `ProcessoValidator`, `ProcessoService`
   (`decidir`/`atualizarStatusPorPareceres`/`tentarDecisaoAutomatica`/
   `retomarAposInformacao`/`registrarEnvio`/`reabrir`), `ProcessoDecisaoController`,
   `AvaliadorController`, `DecisaoAutomaticaScheduler`, `SolicitacaoOnlineService`.
   Levantados por `grep` **todos** os pontos que chamam `decidir(...)` ou executam
   `setStatus(...)` em `Processo` — nenhum ficou de fora.
2. Consulta SQL direta ao Postgres de produção (só `SELECT`): tabela `processo`,
   `parecer`, `membro_urgencia_renal`, `solicitacao_online` e `log_auditoria`.
3. Reconstrução da linha do tempo do processo 09/2026 pela auditoria.

---

## 3. Evidência de produção (SQL real, 2026-08-07 ~13:00 UTC)

### 3.1 Nenhum processo decidido tem pedido de informação em aberto

```sql
SELECT count(*) FROM processo p
WHERE p.status IN ('DEFERIDO','INDEFERIDO')
  AND EXISTS (SELECT 1 FROM parecer x
              WHERE x.processo_id = p.id AND x.resultado = 'SOLICITA_INFORMACAO');
-- resultado: 0
```

Os 8 processos existentes (5 Deferidos, 2 Indeferidos, 1 pausado) foram conferidos
parecer a parecer. Todos os deferimentos vieram de **2 votos favoráveis de membros
comuns, sem nenhum parecer `SOLICITA_INFORMACAO` ativo** no momento da decisão.

### 3.2 O processo 09/2026 (id=14) — o caso citado — continua pausado

| parecer | médico | coordenador | resultado | voto em |
|---|---|---|---|---|
| 40 | Marcia Abichequer | não | FAVORAVEL | 07/08 09:26:12 |
| 41 | Ana Lúcia | não | FAVORAVEL | 07/08 09:05:24 |
| 42 | Verônica Horbe | não | SOLICITA_INFORMACAO | 07/08 09:24:25 |

`processo.status = SOLICITA_INFORMACAO`, `data_decisao = NULL`, `versao = 4`.

**Ou seja: 2 votos favoráveis de membros comuns, com a pausa ativa, e o processo
NÃO foi deferido.** Isso é exatamente a situação descrita no relato, e o sistema
segurou.

Reforço empírico importante: a varredura automática
(`app.decisao-automatica.varredura.habilitado`, default `true` em produção, e o
`/opt/sgpur/sgpur.env` **não** sobrescreve) roda a cada 15 min. Do 2º voto favorável
(09:26 UTC) até agora (13:01 UTC) ela rodou aproximadamente 14 vezes sobre esse
processo — e não o decidiu nenhuma vez. `DecisaoAutomaticaScheduler.elegivel`
(`DecisaoAutomaticaScheduler.java:178-187`) está de fato barrando.

### 3.3 Linha do tempo do 09/2026 (log_auditoria)

```
06/08 17:14:42  veronica  PARECER_VOTADO                     Solicita informacao   -> status vira SOLICITA_INFORMACAO
07/08 09:05:24  ana       PARECER_VOTADO                     Favoravel             (votou DURANTE a pausa - correto, fix do commit 4171987)
07/08 09:10:33  santa casa INFO_COMPLEMENTAR_RECEBIDA_PORTAL
07/08 09:11:17  rafael    ANALISE_RETOMADA                                          -> parecer da Veronica resetado, status volta a ENVIADO
07/08 09:24:25  veronica  PARECER_VOTADO                     Solicita informacao   -> pausa DE NOVO
07/08 09:26:12  marcia    PARECER_VOTADO                     Favoravel             -> 2 favoraveis, mas pausado: NAO decidiu
```

Repare no ponto crítico: às 09:11 a retomada **não** deferiu porque naquele instante
havia só **1** voto favorável (o da Ana). O segundo favorável (Marcia) só entrou
às 09:26. **Se a retomada tivesse acontecido depois das 09:26, o processo teria
sido deferido imediatamente** — ver achado B.

### 3.4 O coordenador CET-RS existe, mas nunca votou

`membro_urgencia_renal`: **Rogério Caruso Bezerra (id=8, CET-RS)** é o único com
`coordenador = true` (os outros 7 são `false`). Ele **não tem nenhum parecer
registrado em nenhum processo**. Portanto, a exceção "coordenador defere sozinho"
**nunca foi exercida em produção** e não pode explicar nenhum deferimento existente.

---

## 4. Leitura do código: os caminhos que gravam decisão

Todos os pontos que podem levar um processo a `DEFERIDO` foram conferidos:

| Caminho | Arquivo:linha | Valida a pausa antes de gravar? |
|---|---|---|
| Decisão manual do operador | `web/ProcessoDecisaoController.java:314-332` → `service/ProcessoService.java:558-591` | Sim, duas vezes: `validarPausaDecisao` no controller (linha 319) e de novo dentro de `decidir` via `validarDecisao` (`ProcessoService.java:569`) |
| Voto no Portal do Avaliador | `web/AvaliadorController.java:443-463` → `ProcessoService.tentarDecisaoAutomatica` | Sim: gate de status em `ProcessoService.java:224` + `decidir` valida de novo |
| Varredura periódica | `service/DecisaoAutomaticaScheduler.java:178-187` → `tentarDecisaoAutomatica` | Sim: pré-filtro `elegivel` exige coordenador favorável para sequer considerar um processo pausado, e o serviço barra de novo |
| Cancelamento pelo solicitante | `service/SolicitacaoOnlineService.java:412` → `decidir(..., CANCELADO, null)` | N/A — `CANCELADO` não é bloqueado pela pausa, por design (`ProcessoValidator.java:125-127`) |

A regra central, `ProcessoValidator.validarPausaDecisao`
(`service/ProcessoValidator.java:124-135`):

```java
boolean bloqueiaDeferido = decisao == StatusProcesso.DEFERIDO
    && !temVotoCoordenadorFavoravel(processo);
boolean bloqueiaIndeferido = decisao == StatusProcesso.INDEFERIDO;
if (processo.getStatus() == StatusProcesso.SOLICITA_INFORMACAO
        && (bloqueiaDeferido || bloqueiaIndeferido)) { ... bloqueia ... }
```

**A regra real, em português:** com o processo pausado, 2 votos favoráveis **não**
deferem. A única forma de deferir durante a pausa é o **voto favorável do
coordenador da CET-RS** — que sozinho já bastaria, mesmo sem pausa nenhuma. Não
existe nenhum caminho em que "2 favoráveis comuns" atravessem a pausa por causa da
regra do coordenador: `temVotoCoordenadorFavoravel` (`ProcessoValidator.java:60-64`)
exige que **o próprio parecer favorável** seja de um membro com
`coordenador = true`; ele nunca é satisfeito por dois membros comuns.

Sobre a preocupação levantada de que `favoraveisNecessariosParaDeferir` pudesse
confundir os dois casos: ele retorna `1` **apenas** quando `temVotoCoordenadorFavoravel`
é verdadeiro (`ProcessoValidator.java:76-79`), e nesse caso deferir com 1 voto é
justamente a regra pretendida. Se o coordenador votou `NAO_FAVORAVEL` ou
`SOLICITA_INFORMACAO`, `temVotoCoordenadorFavoravel` é falso e o mínimo volta a 2 —
não há caminho em que a exceção do coordenador seja aplicada a uma maioria comum.

---

## 5. Achado A — a TELA diz "Deferido" enquanto o processo está travado

**Este é, de longe, o candidato mais provável para o que o usuário viu.**

Na aba **"Respostas"** do processo 09/2026, agora mesmo, o operador lê:

1. `templates/processos/detalhe.html:765` — a frase-resumo do placar:
   **"Maioria já formada"**
   (vem de `web/ProcessoDetalheController.java:422-430`, que só olha
   `sugerirDecisao(p).isPresent()` e não sabe nada sobre a pausa);
2. `templates/processos/detalhe.html:932-936` — alerta azul:
   **"Sugestão automática: Deferido** (maioria simples de 2 em 3 votos, tanto para
   deferir quanto para indeferir; o voto favorável do coordenador da CET-RS defere
   sozinho)."
   (vem de `ProcessoDetalheController.java:400-401`, que chama
   `ProcessoValidator.sugerirDecisao` — `ProcessoValidator.java:93-107`, que
   **deliberadamente não consulta o status**, é uma contagem pura de votos).

Nenhum desses dois textos menciona que a decisão está bloqueada. Um operador que
não conhece o código lê literalmente *"dois votos → Deferido"* numa tela cujo topo
diz "Solicita informação". A frase do relato é praticamente a leitura em voz alta
dessa tela.

Note que a correção do PR #47 (`f862707`) endureceu o texto **da timeline lateral**
(`service/FluxoProcessoService.java:121-132` e `:160-168`, que hoje dizem "mas a
decisão está BLOQUEADA" e "BLOQUEADA pela pausa"), mas **não tocou nesses dois
textos do card de Respostas** — eles continuam afirmando "maioria formada" e
"sugestão: Deferido" sem ressalva. A aplicação em produção foi reiniciada às
12:43 UTC de hoje já com o PR #47, então o usuário está vendo a timeline corrigida
**e** o card de Respostas ainda não corrigido, ao mesmo tempo, na mesma página.

O único freio de UI que funciona ali é indireto: o botão "Ir à Decisão"
(`detalhe.html:766-769`) some quando `liberadoDecisao` é falso. Some sem explicar
por quê.

---

## 6. Achado B — retomar a análise defere na hora, sem o avaliador que pediu a informação votar

**Determinístico, reproduzível, e já armado no processo 09/2026 neste momento.**

`ProcessoDecisaoController.retomarAnalise` (`web/ProcessoDecisaoController.java:136-186`)
faz, em sequência:

1. `processoService.retomarAposInformacao(id)` (linha 156) — reseta o parecer que
   pediu informação para pendência limpa e volta o status para `ENVIADO`
   (`ProcessoService.java:296-310`);
2. `processoService.tentarDecisaoAutomatica(id)` (linha 167) — que agora vê status
   `ENVIADO` e conta os votos.

O comentário no próprio código descreve a intenção (`ProcessoDecisaoController.java:163-164`):
*"pode ocorrer quando so um medico havia pedido info e os demais ja votaram"*.

Consequência concreta para o 09/2026: **hoje há 2 favoráveis (Marcia e Ana) e o
pedido de informação da Verônica.** No instante em que o operador clicar
"Registrar recebimento e retomar análise", o parecer da Verônica é zerado, o status
vira `ENVIADO`, a maioria de 2/3 é imediatamente reconhecida e o processo é
**Deferido na mesma requisição** — a Verônica nunca chega a votar sobre a informação
complementar que ela mesma pediu, e o e-mail/tela dirão apenas "decisão automática
aplicada: Deferido".

Do ponto de vista da regra escrita no CLAUDE.md isso é *coerente* (maioria simples
2 de 3 dispensa o terceiro voto). Do ponto de vista de quem opera, é literalmente
**"dois votos deferiram um processo que estava em Solicita informação"** — só que
um segundo depois de sair da pausa. É a explicação alternativa mais forte do relato,
caso o usuário tenha testado esse clique em algum ambiente.

Vale registrar que isto **não é um bug de implementação** — é uma decisão de produto
implícita, nunca explicitada no CLAUDE.md, sobre o que deve acontecer quando a
informação complementar chega e a maioria já está formada sem o voto de quem pediu.
As duas leituras defensáveis são:

- **(i) hoje:** a informação complementar era um insumo do voto de quem pediu; se a
  maioria já existe sem ele, o processo decide (mais rápido, respeita 2/3);
- **(ii) alternativa:** quem pediu a informação tem o direito de votar sobre ela
  antes de o processo fechar (o pedido dele deixa de ser inócuo).

Não há teste automatizado cobrindo esse encadeamento específico (retomada com
maioria já formada) — `ProcessoServiceTest:297-360` cobre o reset do parecer, não o
deferimento imediato que vem logo depois no controller.

---

## 7. Achado C — reabrir (ADMIN) apaga a pausa silenciosamente

`ProcessoService.reabrir` (`service/ProcessoService.java:695-706`) faz
`p.setStatus(StatusProcesso.ENVIADO)` **incondicionalmente**, e o próprio javadoc
diz que "os pareceres são mantidos como estão" (linha 683). Ele **não** recalcula o
status a partir dos pareceres (não chama `atualizarStatusPorPareceres`).

Sequência realista, sem nenhuma corrida:

1. Avaliador X vota `SOLICITA_INFORMACAO` → processo pausado.
2. O processo é encerrado por um caminho que a pausa permite:
   **`CANCELADO`** (não é bloqueado — `ProcessoValidator.java:125-127`), ou
   **`DEFERIDO` pelo coordenador** (exceção legítima). O parecer de X continua
   valendo `SOLICITA_INFORMACAO`.
3. ADMIN reabre (`POST /processos/{id}/reabrir`) → status vira `ENVIADO`, **com o
   parecer de X ainda pedindo informação**.
4. A partir daí a pausa simplesmente não existe mais para o sistema: qualquer 2
   favoráveis (ou 2 desfavoráveis) fazem `tentarDecisaoAutomatica` — ou, sem
   nenhuma ação humana, **a varredura de 15 minutos** — decidir o processo, com um
   pedido de informação não resolvido registrado.

Causa raiz: **a trava da pausa é ancorada no campo derivado `Processo.status`, não
no fato observável "existe um parecer com resultado `SOLICITA_INFORMACAO`"**. Toda
vez que os dois se dessincronizam, a proteção some sem erro nenhum.

Nenhum teste cobre reabertura de processo com parecer `SOLICITA_INFORMACAO` ativo
(conferido: os testes de `reabrir` em `RegistrarEnvioDuasVezesIntegrationTest`,
`ProcessoDetalheSemTransacaoIntegrationTest` e `SecurityIntegrationTest` tratam de
outros cenários).

---

## 8. Achado D — janela de corrida entre dois votos quase simultâneos

`AvaliadorController.registrarVoto` grava o voto e o status em **transações
separadas**, de propósito (para o voto nunca ser perdido por falha de
pós-processamento — `AvaliadorController.java:313-348`):

- **TX1** grava o parecer (linha 380-402);
- **TX3** `atualizarStatusPorPareceres` (linha 444) recalcula o status **lendo os
  pareceres commitados naquele instante**;
- **TX4** `tentarDecisaoAutomatica` (linha 457) decide **lendo o status commitado
  naquele instante**.

Com dois avaliadores votando quase junto (B pedindo informação, C votando favorável
e fechando a maioria), em `READ COMMITTED` existe um intercalamento que fura a
pausa:

```
C.TX1 commit  (FAVORAVEL, fecha 2 favoraveis)
B.TX1 begin   (SOLICITA_INFORMACAO gravado, AINDA NAO commitado)
C.TX3         le pareceres -> nao ve o voto de B -> pediuInfo=false -> status = ENVIADO, commit
B.TX1 commit  (agora o pedido de informacao existe no banco)
C.TX4         le status = ENVIADO (a pausa nunca chegou a ser gravada)
              -> 2 favoraveis -> validarPausaDecisao passa -> DEFERE
B.TX3         atualizarStatusPorPareceres -> processo ja finalizado -> IllegalStateException
              -> flash "Voto registrado, mas houve um conflito ao atualizar o status"
```

O resultado é um processo `DEFERIDO` com um parecer `SOLICITA_INFORMACAO` vivo — e
o avaliador B recebendo uma mensagem genérica de "conflito", sem entender que o
pedido dele foi atropelado.

A janela é de milissegundos e **não há evidência de que isso tenha ocorrido**
(seção 3.1 dá zero). Mas ela é real, o efeito é irreversível (só ADMIN reabrindo), e
tem a mesma causa raiz do achado C: a trava olha o status, não os pareceres. O
`@Version` do `Processo` não protege aqui, porque as duas escritas concorrentes são
em linhas diferentes (o parecer de B e o processo, salvo por C).

---

## 9. O que foi verificado e está correto (não mexer)

- `ProcessoValidator.validarPausaDecisao` — lógica correta, incluindo a assimetria
  deliberada (coordenador defere na pausa; indeferir continua bloqueado mesmo com
  coordenador favorável). Coberto por `ProcessoValidatorTest:155-185`.
- `ProcessoValidator.validarContagemVotos` (`:138-161`) — a exceção do coordenador
  nunca é confundida com maioria comum; e ela também **veda** indeferir quando o
  coordenador votou favorável.
- `tentarDecisaoAutomatica` (`ProcessoService.java:214-245`) — gate de pausa antes de
  qualquer coisa, e ainda passa por `decidir`, que revalida tudo.
- `DecisaoAutomaticaScheduler.elegivel` (`:178-187`) — defesa em profundidade
  correta, e comprovadamente efetiva (seção 3.2).
- `registrarEnvio` (`ProcessoService.java:173`) — a correção de 03/08 (não acordar um
  processo pausado) continua no lugar.
- O `EmailTemplateService.gerar` durante a pausa oferece o e-mail *"Pedido de
  informação complementar"*, não um e-mail de deferimento (`EmailTemplateService.java:47-58`).
- O achado 4 já documentado no CLAUDE.md (`temVotoCoordenadorFavoravel` lê
  `coordenador` ao vivo, não no momento do voto) **continua valendo como pendência
  de produto**, mas é inofensivo hoje: só existe 1 coordenador e ele nunca votou.

---

## 10. Sugestões (não implementadas — precisam de decisão)

Em ordem de custo/benefício:

1. **(Achado A, barato e sem risco)** Fazer o card de Respostas dizer a mesma coisa
   que a timeline já diz desde o PR #47: quando
   `status == SOLICITA_INFORMACAO && !temVotoCoordenadorFavoravel`, trocar
   "Maioria já formada" por algo como *"Maioria formada, mas a decisão está
   bloqueada: aguardando informação complementar"*, e acrescentar a mesma ressalva
   ao alerta "Sugestão automática". A informação já existe pronta no serviço
   (`FluxoProcessoService` calcula `pausaBloqueiaDecisao`) — é reaproveitamento, não
   regra nova. Isso sozinho provavelmente encerra o relato.
2. **(Achado B, exige decisão do dono do produto)** Definir se, ao retomar a análise,
   o processo pode decidir imediatamente com a maioria já formada (comportamento
   atual) ou se deve esperar o voto definitivo de quem pediu a informação. Se a
   resposta for "esperar", a mudança é pequena (não encadear
   `tentarDecisaoAutomatica` logo após `retomarAposInformacao`, deixando a decisão
   para o próximo voto ou para a varredura), mas **muda comportamento em produção** e
   por isso não deve ser feita sem aval. Em qualquer dos casos, vale documentar a
   escolha no CLAUDE.md, que hoje é silencioso sobre ela.
3. **(Achados C e D, mesma causa raiz) — CORRIGIDO em 2026-08-07, aprovado
   explicitamente pelo usuário ("sim corrija tudo").**

   **O que foi feito:**
   - `ProcessoValidator.temPedidoInformacaoAtivo(Processo)` (método novo,
     função pura): `true` se existe algum parecer do processo com
     `resultado == SOLICITA_INFORMACAO` — o FATO observável, independente de
     `Processo.status`.
   - `ProcessoValidator.validarPausaDecisao` passou a bloquear quando
     `status == SOLICITA_INFORMACAO` **OU** `temPedidoInformacaoAtivo(processo)`
     (OU, não substituição — o status continua sendo a fonte no caminho
     normal, o fato cobre exatamente os casos em que os dois dessincronizam).
     A exceção do coordenador (voto Favorável dele defere mesmo com a pausa
     ativa) foi mantida intacta, testada inclusive com o status
     dessincronizado.
   - `ProcessoService.tentarDecisaoAutomatica` usa a mesma checagem OR antes
     de considerar decidir — como o método sempre recarrega o processo via
     `buscar(id)` no início da própria transação, a leitura reflete o que
     estiver commitado no banco naquele instante (ver análise do achado D
     abaixo).
   - `DecisaoAutomaticaScheduler.elegivel` (pré-filtro em memória da
     varredura periódica) recebeu a mesma checagem OR, para não virar o elo
     mais fraco da cadeia depois que `reabrir` passou a poder devolver
     `SOLICITA_INFORMACAO` mesmo com o status anterior tendo sido `ENVIADO`
     num appended read desatualizado (defesa em profundidade, coerente com
     o resto).
   - `ProcessoService.reabrir`: depois de setar `ENVIADO` (como antes), passou
     a chamar `atualizarStatusPorPareceres(id)` em seguida, que recalcula o
     status a partir dos pareceres de verdade — se ainda houver um parecer
     `SOLICITA_INFORMACAO` não resolvido, o status pós-reabertura vira
     `SOLICITA_INFORMACAO` (a pausa continua valendo) em vez de `ENVIADO`.
     Sem nenhum parecer pendente, o resultado é o mesmo de antes (`ENVIADO`)
     — nenhuma mudança de comportamento no caso comum.

   **Conclusão sobre o achado D (corrida entre transações):** a correção do
   item 1 fecha a janela quase por completo, não por acaso — `decidir`/
   `tentarDecisaoAutomatica` sempre releem os pareceres do banco (via
   `buscar(id)`) no início da própria transação, então a checagem baseada no
   FATO reflete o que estiver commitado naquele instante, não um snapshot
   antigo. A única janela residual que sobra é mais estreita que a original:
   as DUAS transações do voto que fecha a maioria (recálculo de status +
   tentativa de decisão) teriam que terminar de ler ANTES do commit da
   transação do voto que pede informação — uma corrida de poucos
   milissegundos entre 4 operações de banco. **Decisão consciente, documentada
   no javadoc de `tentarDecisaoAutomatica`: não vale o custo/complexidade de
   lock pessimista ou isolamento SERIALIZABLE para essa janela residual**,
   dada a ausência de qualquer evidência de ocorrência em produção (seção 3.1)
   e a magnitude da janela. Tratado como risco residual aceito, não como
   pendência.

   **Testes novos** (padrão do projeto — trava de decisão é escrita
   irreversível, exige teste de integração real, sem mock do serviço):
   - `ProcessoValidatorTest`: `temPedidoInformacaoAtivo*`,
     `validarPausaDecisaoBloqueiaQuandoStatusDessincronizaDoParecerAtivo`,
     `validarPausaDecisaoLiberaDeferidoDoCoordenadorMesmoComStatusDessincronizado`.
   - `ReaberturaMantemPausaAtivaIntegrationTest` (novo, `@SpringBootTest` + H2
     real, `ProcessoService`/`DecisaoAutomaticaScheduler` reais — mesmo
     padrão de `DecisaoAutomaticaSchedulerIntegrationTest`): reabertura com
     parecer `SOLICITA_INFORMACAO` ativo restaura a pausa (não `ENVIADO`);
     reabertura sem pendência continua indo para `ENVIADO` (regressão);
     cenário completo do achado C — processo pausado → Cancelado (caminho que
     a pausa permite) → ADMIN reabre → um 3º voto favorável forma maioria
     "crua" de 2/3, mas a decisão continua bloqueada nos 3 caminhos (manual,
     evento de voto, varredura periódica) enquanto o parecer antigo não for
     resolvido; e o caminho positivo — depois de `retomarAposInformacao` de
     verdade e o voto definitivo, a decisão automática volta a funcionar
     normalmente.
   - Testes existentes de `ProcessoValidatorTest`/`ProcessoServiceTest`/
     `DecisaoAutomaticaSchedulerIntegrationTest` que dependiam do
     comportamento antigo continuam passando sem nenhum ajuste (a checagem
     nova é um OR aditivo sobre a condição de status já coberta).

   **Validação:** suíte completa, **822 testes, 0 falhas** (JDK 21).

---

## 11. O que ainda precisa ser esclarecido com o usuário

Nenhum dado de produção corresponde a um deferimento durante a pausa. Para fechar o
diagnóstico com certeza, seria útil saber:

- **Ele viu um processo efetivamente Deferido**, ou viu a tela dizendo "Sugestão
  automática: Deferido" / "Maioria já formada" (achado A)?
- Se viu deferido: **em qual número de processo e em qual ambiente** (produção,
  ou algum teste local)? Em produção, os 5 Deferidos existentes (02, 04, 05, 06,
  08/2026) foram todos por 2 favoráveis comuns **sem** pausa ativa, conferidos um a
  um.
- Se o incômodo for com o **momento** da decisão logo após "retomar análise"
  (achado B), isso é comportamento atual e intencional do código, e a mudança
  depende da decisão descrita na sugestão 2.

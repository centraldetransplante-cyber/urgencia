# Catálogo de bugs conhecidos do SAUR

Este é um **catálogo vivo**, organizado por categoria (não por data), de todo
bug/erro/pitfall **já encontrado e já corrigido** no histórico do projeto.
Propósito: ser consultado **antes** de mexer numa área de risco conhecida —
por um humano ou por uma IA — para não reintroduzir um defeito que já foi
pago uma vez.

**Como usar:** antes de tocar em Hibernate/schema, Thymeleaf, decisão de
processo, chat, e-mail, PDF ou concorrência, dê um `Ctrl+F` na seção
correspondente. Se o que você está prestes a fazer soa parecido com algum
item abaixo, leia o item inteiro antes de codar.

**Como manter:** ao encontrar um bug novo da MESMA classe de um item já
listado, **acrescente ao item existente** (uma linha "recaída em ...") em
vez de duplicar a entrada. Só crie uma entrada nova para uma causa raiz
realmente distinta. Bug pontual sem valor de generalização (ex.: erro de
digitação corrigido na hora) não precisa entrar aqui — este catálogo é para
classes de erro que podem se repetir, não um changelog.

Cada entrada segue o formato: **Sintoma** → **Causa raiz** → **Correção
aplicada** → **Como evitar recair**. Onde o detalhe completo já existe em
outro arquivo, o resumo aqui é acionável e o texto linka para lá em vez de
copiar tudo.

---

## 1. Persistência / Hibernate (schema, backfill, CHECK constraint)

### 1.1 `ddl-auto: update` não faz backfill em coluna nova tratada como não-nula
**Sintoma:** salvar (editar/decidir/reabrir/anexar) uma entidade **antiga**
começa a dar 500 sem stacktrace óbvio, depois de adicionar um campo como
`@Version` a uma entidade que já tem linhas no banco.
**Causa raiz:** o Hibernate cria a coluna nova com `NULL` nas linhas
antigas; o próximo UPDATE nelas quebra porque o Hibernate trata o campo
(ex. `@Version`) como obrigatório para gravar. `ddl-auto: update` só faz
`ALTER TABLE ADD COLUMN`, nunca populações.
**Correção aplicada:** backfill manual em produção logo após o deploy —
`UPDATE <tabela> SET <coluna> = 0 WHERE <coluna> IS NULL;`. Já ocorreu
com `Processo.versao` (2026-07-10), `Usuario.versao` (2026-07-29),
`membro_urgencia_renal.versao` (2026-07-28), `ControleUrgencia.versao`
(2026-08-03).
**Como evitar recair:** toda vez que adicionar `@Version` ou qualquer
coluna tratada como não-nula numa entidade já populada, rodar o backfill em
produção **imediatamente** após o deploy — não há Flyway/Liquibase, é
responsabilidade manual. Ver `CLAUDE.md`, "Convenções de código".

### 1.2 `@Version` sozinho não fecha a janela de concorrência quando o serviço recarrega a entidade
**Sintoma:** dois operadores editam a mesma entidade ao mesmo tempo (ex.
`ControleUrgencia`); o segundo salva por cima do primeiro **sem nenhum
erro**, mesmo com `@Version` presente.
**Causa raiz:** o padrão `atualizar()` do projeto sempre recarrega a
entidade gerenciada via `findById` e muta essa instância (nunca o objeto
`dados` vindo do formulário) antes de `save()`. Isso significa que o
`@Version` puro do JPA nunca detecta divergência — o `save()` sempre
flusha a versão recém-lida, nunca a versão antiga que o navegador tinha.
**Correção aplicada:** `ControleUrgenciaService.atualizar` (2026-08-03)
compara explicitamente `dados.getVersao()` (vindo do formulário, via
`<input type="hidden" th:field="*{versao}">`) contra a versão atual do
registro, **antes** de aplicar os demais campos, lançando
`ObjectOptimisticLockingFailureException` manualmente em caso de
divergência.
**Como evitar recair:** ao adicionar `@Version` a uma entidade cujo
`Service.atualizar` segue o padrão "recarrega + muta", sempre acrescentar a
checagem manual de versão explícita — o `@Version` do JPA sozinho não
basta nesse padrão de código. Teste modelo:
`ControleUrgenciaAtualizacaoIntegrationTest.edicaoConcorrenteComRenovacaoNaoSobrescreveSilenciosamente`.

### 1.3 `ddl-auto: update` não atualiza CHECK constraint de enum
**Sintoma:** um valor novo de enum (`@Enumerated(STRING)`) funciona em
dev/H2 mas quebra em produção com
`violates check constraint "<tabela>_<coluna>_check"`.
**Causa raiz:** o Hibernate gera uma CHECK `(coluna IN (...))` com a lista
de valores **congelada no momento em que a tabela foi criada**. Isso vale
para **toda tabela nova** criada pelo Hibernate — não é dívida histórica
isolada (confirmado gerando o DDL: as 8 colunas `@Enumerated(STRING)` do
projeto originalmente ganhavam CHECK).
**Correção aplicada:** `ALTER TABLE ... DROP/ADD CONSTRAINT` manual na VM.
Incidente original: `StatusSolicitacaoOnline.PROCESSO_EXCLUIDO`
(2026-07-27). Estado real de produção (reconfirmado por SQL em 2026-08-03):
só 2 dessas constraints sobreviveram (`controle_urgencia_situacao_check`,
`mensagem_solicitacao_remetente_check`) — as demais nunca tiveram CHECK ou
foram derrubadas e nunca recriadas, então hoje a maioria dos enums do
projeto "escapa" por sorte estrutural, não por garantia.
**Como evitar recair:** existe proteção automática — `EnumCheckConstraintValidator`
roda no boot e avisa (sem derrubar a app) quando um CHECK diverge do enum
Java atual. Mesmo assim, ao adicionar valor a um enum `@Enumerated(STRING)`
existente numa tabela que já tem CHECK, planejar o `ALTER TABLE` manual em
produção. Diagnóstico manual: `SELECT conrelid::regclass, conname,
pg_get_constraintdef(oid) FROM pg_constraint WHERE contype = 'c' AND
conrelid = '<tabela>'::regclass;`. Ver `CLAUDE.md`, "Convenções de código".

### 1.4 `ddl-auto: update` também não relaxa `NOT NULL` existente
**Sintoma:** apagar uma mensagem de chat (soft-delete, `texto = null`)
sempre quebra em produção com `DataIntegrityViolationException` (23502,
"NULL not allowed"), mesmo funcionando em dev/H2.
**Causa raiz:** `MensagemSolicitacao.texto` era `nullable = false` na
entidade, mas o serviço de soft-delete sempre fazia `setTexto(null)`. A
suíte de testes (`@MockitoBean`) nunca toca o banco real, então não pegou.
Corrigir a entidade Java (tirar `nullable = false`) **não** relaxa a
constraint já existente no Postgres de produção — mesma classe de pitfall
do `@Version`/CHECK de enum.
**Correção aplicada (2026-07-28):** entidade corrigida no código +
`ALTER TABLE mensagem_solicitacao ALTER COLUMN texto DROP NOT NULL;`
rodado manualmente em produção via Oracle Cloud Shell, confirmado por
`information_schema.columns` (`is_nullable = YES`).
**Como evitar recair:** qualquer coluna de banco criada pelo Hibernate,
seja `NOT NULL`, CHECK ou tipo, **não se ajusta sozinha** quando a anotação
Java muda — sempre planejar o `ALTER TABLE` manual correspondente em
produção e confirmar por `information_schema`/`pg_constraint`, nunca supor.

### 1.5 Query de listagem com `:param IS NULL OR ...` quebra no Postgres real (funciona no H2)
**Sintoma:** `/auditoria` devolvia 500 em toda carga em produção
(`could not determine data type of parameter $7`, SQLState `42P18`),
mesmo passando limpo pela suíte local.
**Causa raiz:** a query usava o padrão `(:de is null or l.dataHora >= :de)`
com o parâmetro usado **só** em `IS NULL`, sem outro contexto de tipo por
perto. O protocolo estendido do Postgres precisa inferir o tipo de cada
`?` antes de qualquer valor chegar; um parâmetro nulo sem comparação
tipada por perto não tem como ter o tipo inferido. O H2 é tolerante ao
mesmo padrão SQL — o defeito só se manifesta contra o dialeto real.
**Correção aplicada (2026-08-07):** `LogAuditoriaRepository.buscar`
reescrita para nunca passar `null`: normalizar para valor efetivo (string
vazia, sentinela de data) na camada de serviço/controller antes de chamar
o repositório, e a query passa a comparar sempre contra um valor concreto
(`:usuario = '' or ...`, `l.dataHora >= :de` sempre).
**Como evitar recair:** nunca usar `:param IS NULL OR ...` em query de
listagem/exportação neste projeto — normalizar para valor efetivo antes do
repositório, sempre. Testar contra Postgres real (não só H2) qualquer query
nova desse tipo antes de confiar na suíte local. Ver `CLAUDE.md`,
"Segurança e sessão — reforços".

---

## 2. Thymeleaf / Frontend

### 2.1 `/*[[expr]]*/` (natural templating em JS) exige `th:inline="javascript"` na tag `<script>`
**Sintoma:** um valor calculado no servidor (URL, flag) sempre renderiza
como o valor de **fallback** do JS, nunca o valor real — sem erro nenhum,
nem no build nem em teste `@WebMvcTest`/`MockMvc` (eles testam status/model,
não o JS final renderizado).
**Causa raiz:** sem `th:inline="javascript"` na tag `<script>`, o
Thymeleaf não reconhece o padrão de comentário `/*[[expr]]*/` — só
substitui o `[[expr]]` interno, deixando os delimitadores `/* */` como
comentário JS **literal**, que o navegador ignora, caindo sempre no
fallback depois do comentário.
**Correção aplicada:** adicionado `th:inline="javascript"` nas 3 tags
`<script>` do chat que tinham o padrão (2026-07-28). Descoberto rodando o
chat AJAX contra um servidor de verdade e inspecionando o HTML via `curl`
— não pelos testes automatizados.
**Como evitar recair:** toda tag `<script>` que usa `/*[[expr]]*/` **deve**
ter `th:inline="javascript"`. Ao revisar/criar JS gerado por Thymeleaf,
inspecionar o HTML renderizado de verdade (`curl` contra app rodando), não
confiar em teste `@WebMvcTest`. Ver `CLAUDE.md`, "Convenções de código".

### 2.2 Ternários aninhados em atributos Thymeleaf quebram o parser
**Sintoma:** `th:classappend`/`th:class`/`th:style` com ternário aninhado
em >2-3 níveis falha a renderizar (parser do Thymeleaf não lida bem com
ternários multi-linha aninhados).
**Correção aplicada / regra fixa:** nunca aninhar ternários em mais de
2-3 níveis nesses atributos — usar `th:switch` ou `th:with` para
pré-calcular valores complexos. Nunca `th:if` + `th:unless` no mesmo
elemento (combinar numa única expressão `th:if="${cond and !outra}"`).
**Como evitar recair:** regra de convenção fixa, ver `CLAUDE.md`,
"Convenções de código".

### 2.3 `<input type="date">` sem `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)` some em silêncio
**Sintoma:** um campo `LocalDate` (ex. `pacienteDataNascimento`) fica vazio
ao reabrir o formulário de edição/conversão, mesmo com o dado íntegro no
banco. Sem erro nenhum.
**Causa raiz:** sem a anotação, o Thymeleaf renderiza o `LocalDate` no
formato padrão da JVM (ex. `"11/2/80"`) em vez de ISO no `value` do
`<input type="date">`. O navegador **descarta em silêncio** um `value` que
não está em formato ISO — o campo aparece vazio, sem nenhum erro visível.
**Correção aplicada (2026-08-21):** `@DateTimeFormat(iso =
DateTimeFormat.ISO.DATE)` em `Processo.pacienteDataNascimento` e no
espelho de `SolicitacaoOnline`.
**Como evitar recair:** qualquer `LocalDate` novo que vá para um
`<input type="date">` via `th:field` **precisa** dessa anotação, sempre.
Ver `CLAUDE.md`, item 17 das Regras de negócio.

### 2.4 Fragmento Thymeleaf só copia o elemento marcado, não os irmãos seguintes
**Sintoma:** um `<script>` colocado logo **depois** de um bloco
`th:fragment="navbar"`, esperando ser incluído junto, simplesmente não
aparece em nenhuma página que inclui `layout :: navbar`.
**Causa raiz:** `th:fragment` só copia o elemento que ele marca — colocar
algo "do lado de fora" mas fisicamente próximo no arquivo fonte não o
inclui.
**Correção aplicada:** os dois blocos `<script>` do poll global de
notificação foram movidos para **dentro** do fragment `navbar`
(2026-07-28), corrigido antes de subir para produção.
**Como evitar recair:** ao adicionar JS/HTML que deve viajar junto com um
fragment, sempre colocá-lo fisicamente **dentro** da tag marcada com
`th:fragment`, nunca como irmão logo depois.

### 2.5 E2E localiza elementos por texto exato — acentuação/rótulo quebra teste sem quebrar a regra
**Sintoma:** ao acentuar um rótulo/botão (ex. "Enviar solicitacao" →
"Enviar solicitação"), o teste E2E Playwright (que localiza por texto
exato) para de encontrar o elemento e falha — já aconteceu 3× num único
lote de acentuação (Fase 8).
**Correção aplicada:** atualizar simultaneamente o Page Object (`*Page.java`
em `src/test/java/.../e2e/pages/`) e qualquer assert `containsString` da
suíte junto com a mudança de texto visível.
**Como evitar recair:** antes de mesclar qualquer PR que acentue ou
reescreva um rótulo/botão visível, conferir os Page Objects do E2E e as
asserções de texto da suíte. Não é opcional — já recaiu.

---

## 3. Concorrência / Transação

### 3.1 Rota que grava algo irreversível precisa de teste do caminho de FALHA sem mock do serviço
**Sintoma:** o voto do avaliador era **perdido silenciosamente** em caso de
falha no pós-processamento, e ~15 endpoints devolviam "Erro interno" no
lugar da mensagem de negócio — com a suíte inteira **verde**.
**Causa raiz:** `@WebMvcTest` + `@MockitoBean` no service não consegue
expressar erro de transação: sem o proxy real do Spring, não existe
transação nesse tipo de teste — a classe inteira de bug é inexprimível
nele.
**Correção aplicada:** para voto, decisão, envio de e-mail oficial,
exclusão e qualquer escrita irreversível, escrever ao menos um
`@SpringBootTest` (H2 real, serviço real) que força a falha do
pós-processamento e comprova que a escrita principal sobreviveu e que o
usuário recebeu erro tratado (não 500). Modelo:
`AvaliadorVotoTransacaoIntegrationTest`.
**Como evitar recair:** regra fixa de convenção — toda rota que grava algo
irreversível exige esse tipo de teste. Ver `CLAUDE.md`, "Convenções de
código".

### 3.2 Voto de avaliador em transações separadas cria janela de corrida na pausa "Solicita informação"
**Sintoma (nunca reproduzido em produção, achado por análise, risco
residual aceito):** dois avaliadores votando quase simultaneamente — um
pedindo informação, outro fechando maioria favorável — poderiam, em teoria,
furar a pausa (`READ COMMITTED`, transações separadas de propósito para o
voto nunca ser perdido).
**Causa raiz:** a trava de decisão consultava só `Processo.status`
(campo derivado), não o fato observável "existe parecer com
`SOLICITA_INFORMACAO`". Quando as duas coisas dessincronizam (também
achado no cenário de reabertura — ver 4.4), a proteção some sem erro.
**Correção aplicada (2026-08-07):**
`ProcessoValidator.temPedidoInformacaoAtivo(Processo)` (função pura,
verifica os pareceres de verdade) passou a ser checado em **OU** com o
status em `validarPausaDecisao`, `tentarDecisaoAutomatica` e
`DecisaoAutomaticaScheduler.elegivel`. Documentado no relatório
`docs/RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md` (achados
C e D).
**Como evitar recair:** ao adicionar uma trava nova baseada num campo
derivado (`status`), preferir também checar o fato observável de que ele
deriva, com OU — nunca confiar só no campo cacheado quando existe um
caminho (reabertura, corrida entre transações) que pode dessincronizar os
dois.

---

## 4. Fluxo de decisão (maioria simples, pausa "Solicita informação", coordenador)

### 4.1 Pausa de UM avaliador bloqueava o VOTO dos outros dois (não só a decisão) — bug grave, corrigido
**Sintoma:** assim que qualquer avaliador votava `SOLICITA_INFORMACAO`, os
outros dois — que não pediram nada e podiam estar prontos para votar —
recebiam 403 ao tentar votar, ou o processo simplesmente sumia da lista de
pendências deles, sem nenhuma explicação. Podia travar por dias.
**Causa raiz:** `AvaliadorController.resolverParecerPendente` e
`pendenteAtivoParaVoto` exigiam `status == ENVIADO` para **qualquer**
parecer do processo, não só o que causou a pausa —
`atualizarStatusPorPareceres` muda `Processo.status` inteiro assim que
**um** parecer vira `SOLICITA_INFORMACAO` (`anyMatch`).
**Correção aplicada (2026-08-06):** `StatusProcesso.aceitaVotoAvaliador()`
(novo, `true` para `ENVIADO` e `SOLICITA_INFORMACAO`) substituiu a
checagem de status exato no gate de voto — a trava da **decisão**
(`ProcessoValidator.validarPausaDecisao`) não foi tocada, continua
bloqueando só a decisão, corretamente. Detalhe completo:
`docs/RELATORIO-BUG-PAUSA-BLOQUEIA-OUTROS-AVALIADORES-2026-08.md`. Teste:
`AvaliadorVotoDuranteSolicitaInformacaoIntegrationTest`.
**Como evitar recair:** ao adicionar/alterar qualquer gate baseado em
`Processo.status`, perguntar explicitamente: "isso deveria bloquear a
DECISÃO ou também o VOTO/interação individual de quem não causou a
pausa?". São regras diferentes neste sistema — não reusar o mesmo campo
para as duas sem pensar.

### 4.2 Retomar a análise com maioria já formada decide na hora, sem esperar o 3º voto — NÃO é bug (esclarecido)
**Relato original:** "dois votos deferem mesmo com o processo em 'solicita
informação'".
**Investigação:** confirmado por SQL de produção que nenhum deferimento
real ocorreu durante a pausa — a regra estava correta. O que de fato
acontece é: se a maioria simples (2/3) já estava formada pelos outros 2
pareceres quando o operador clica "Retomar análise", o sistema decide
**na mesma requisição**, sem esperar o avaliador que pediu a informação
votar de novo (`ProcessoDecisaoController.retomarAnalise` encadeia
`retomarAposInformacao` + `tentarDecisaoAutomatica`).
**Veredito:** **comportamento intencional, confirmado explicitamente pelo
dono do produto** ("manter o comportamento atual") — não corrigir sem novo
pedido explícito. Documentado em
`docs/RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md`, achado B,
e em `CLAUDE.md`, seção "Solicita informação (PAUSA)".
**O que era de fato bug e FOI corrigido na mesma investigação:** achado A
(a tela dizia "Maioria já formada"/"Sugestão: Deferido" sem citar a pausa
— ver 4.3) e achados C/D (reabertura não restaurava a pausa; corrida entre
transações — ver 3.2 e 4.4).
**Como evitar recair:** ao investigar um relato parecido de novo, checar
primeiro se o processo em questão realmente foi decidido **durante** a
pausa (SQL/auditoria) antes de assumir bug na regra — historicamente o
problema real esteve na **exibição**, não na regra.

### 4.3 Texto da tela afirma "Maioria formada"/"regra 2 de 3" em processo decidido por voto único do coordenador
**Sintoma:** em múltiplas telas (card de Respostas, timeline, Relatório
Final, dossiê), o texto dizia "Maioria já formada"/"regra: 2 de 3 defere"
mesmo quando a decisão saiu do voto único do coordenador — inclusive
contradições dentro do **mesmo documento** (Relatório Final citando a
exceção regimental numa seção e "maioria formada" duas seções depois).
**Causa raiz:** cada superfície (`RelatorioService`, `FluxoProcessoService`,
`ExportacaoProcessoService`, `processos/detalhe.html`) reconstruía a frase
sobre a regra de decisão **de forma independente**, e só uma delas
conhecia a exceção do coordenador.
**Correção aplicada (2026-08-10):** fonte única `service/dto/RegraDecisao`
(enum `MAIORIA_SIMPLES`/`VOTO_COORDENADOR`/`CANCELAMENTO`/`NAO_DECIDIDO`)
+ `ProcessoValidator.regraAplicada`, consumida por todas as superfícies via
fragment `layout :: badgeRegraDecisao`. Detalhe completo em
`docs/RELATORIO-VISTORIA-BRECHAS-DECISAO-2026-08-10.md` (achados 2, 3, 4,
6) — 6 fases implementadas no mesmo dia (F1-F6), todas mescladas.
**Recaída pontual corrigida depois (2026-08-11):** o placar do card
"Respostas dos Avaliadores" (`fraseMaioria` em
`ProcessoDetalheController`) tinha ficado de fora daquela varredura e
continuava dizendo "Maioria já formada" para decisão por coordenador — ver
`docs/RELATORIO-STATUS-PROCESSO-12-2026-2026-08-11.md`, achado 3.
**Como evitar recair:** qualquer texto novo que descreva "por que o
processo foi decidido assim" **deve** consumir `RegraDecisao`/
`ProcessoValidator.regraAplicada` — nunca reconstruir a frase inline
checando contagem de votos na mão. Ao adicionar uma superfície nova que
fale de decisão (nova tela, novo PDF, novo e-mail), fazer uma varredura
`grep` por "maioria"/"2 de 3"/"regra" para achar textos que ainda não usam
a fonte única.

### 4.4 Reabertura (ADMIN) apagava a pausa "Solicita informação" silenciosamente
**Sintoma:** um processo pausado, encerrado por um caminho que a pausa
permite (Cancelado, ou Deferido pelo coordenador), reaberto pelo ADMIN,
voltava para `ENVIADO` **mesmo com o parecer `SOLICITA_INFORMACAO` ainda
vivo** — a pausa deixava de existir para o sistema, e qualquer 2 votos (ou
a varredura periódica de 15 min) decidiam o processo com um pedido de
informação nunca resolvido.
**Causa raiz:** `ProcessoService.reabrir` fazia
`setStatus(StatusProcesso.ENVIADO)` incondicionalmente, sem recalcular a
partir dos pareceres reais.
**Correção aplicada (2026-08-07):** `reabrir` passou a chamar
`atualizarStatusPorPareceres(id)` logo depois de setar `ENVIADO` — se
ainda houver parecer `SOLICITA_INFORMACAO` não resolvido, o status
pós-reabertura volta a `SOLICITA_INFORMACAO`. Teste:
`ReaberturaMantemPausaAtivaIntegrationTest`. Ver
`docs/RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md`, achado C.
**Como evitar recair:** qualquer método que force `Processo.status` para
um valor fixo (não calculado) deve considerar se precisa recalcular a
partir do estado real dos pareceres logo em seguida — não presumir que o
valor fixado é sempre correto.

### 4.5 A pausa "some" da timeline/Painel quando acontece ANTES da maioria se formar
**Sintoma real de produção (processo 12/2026):** Painel e lista de
processos mostravam, lado a lado, o badge amarelo "Solicita informação" **e**
a pendência "Respostas dos médicos — Faltam 1 de 3 pareceres" — contradição
literal. O operador era induzido a cobrar o 3º médico, quando isso **não
destrava** o processo (quem destrava é o solicitante responder + o
operador clicar "retomar análise").
**Causa raiz:** `FluxoProcessoService.montarEtapas` só marcava a etapa
"Respostas dos médicos" como `CONCLUIDA` com maioria formada **ou** todos
os 3 votados. Sem nenhuma das duas, ela ficava `ATUAL`, e a cascata
`anterioresConcluidas=false` derrubava a etapa "Informação complementar"
(que deveria ser a atual) para `BLOQUEADA`. Assimétrico: quando a pausa
chegava **depois** da maioria já formada, o mesmo código acertava.
**Correção aplicada:** quando o processo está em pausa, a etapa
`INFO_COMPLEMENTAR` passou a ser sempre `ATUAL` (nunca `BLOQUEADA`),
independente de a etapa anterior estar concluída — corrigindo só o
`EstadoEtapa` dessa etapa específica. Card de Respostas e badge de status
sem acento também corrigidos na mesma leva. Ver
`docs/RELATORIO-STATUS-PROCESSO-12-2026-2026-08-11.md` (implementado no
mesmo dia, "sim implemente não deixe nada pendente").
**Como evitar recair:** ao adicionar uma etapa nova ao checklist do
`FluxoProcessoService`, testar explicitamente os dois cenários de ordem
de eventos (evento X antes de Y, e Y antes de X) — não presumir que o
mesmo código que acerta uma ordem acerta a outra. `FluxoProcessoServiceTest`
já tem os dois pares nomeados lado a lado para não passar despercebido de
novo.

### 4.6 `temVotoCoordenadorFavoravel` lia o cargo "ao vivo", não o papel no momento do voto
**Sintoma (risco, não incidente real confirmado em produção):** se o
coordenador votar Favorável e depois **deixar de ser** coordenador (outro
médico assume o cargo) antes do processo ser decidido, o voto antigo
deixaria de contar como "voto de coordenador" — ou, na direção oposta, um
médico que virou coordenador **depois** de votar como membro comum
ganharia retroativamente o peso de coordenador.
**Correção aplicada (2026-08-07, commit `3dac941`):**
`Parecer.eraCoordenadorNoVoto` (nullable) — snapshot gravado no INSTANTE
do voto (`AvaliadorController.registrarVoto`).
`ProcessoValidator.temVotoCoordenadorFavoravel` passou a ler esse
snapshot, nunca o cargo ao vivo. `null` (voto legado, anterior à mudança)
nunca conta como voto de coordenador — decisão conservadora, sem backfill.
Teste: `SnapshotCoordenadorVotoIntegrationTest`.
**Recaída parcial encontrada depois (2026-08-10):** a migração para o
snapshot ficou incompleta em **um** ponto — o **nome impresso** no
Relatório Final/dossiê ("Deferido pelo voto do Coordenador da CET-RS
(<nome>)") ainda filtrava por `membro.isCoordenador()` ao vivo, podia
nomear o médico **errado** depois de uma troca de cargo. A regra de
decisão em si nunca esteve errada — só o nome no documento. Corrigido
trocando o filtro por `Boolean.TRUE.equals(par.getEraCoordenadorNoVoto())`
em `RelatorioService.paragrafoRegraDecisao`. Ver
`docs/RELATORIO-VISTORIA-BRECHAS-DECISAO-2026-08-10.md`, achado 1 (fase de
maior risco do relatório, tratada com aprovação explícita e bateria de
testes ampliada por tocar em algo adjacente à regra de decisão).
**Como evitar recair:** qualquer código novo que precise saber "quem
exerceu a prerrogativa de coordenador neste processo" deve usar
`Parecer.eraCoordenadorNoVoto` (o snapshot), **nunca**
`membro.isCoordenador()` direto — mesmo que pareça "só um detalhe de
exibição". Fazer `grep -rn "isCoordenador()"` antes de mesclar qualquer
mudança na área de decisão/relatório para achar leituras ao vivo perdidas.

### 4.7 Justificativa do avaliador (obrigatória desde 2026-08-03) nunca chegava ao solicitante
**Sintoma real de produção:** processo pausado por "Solicita informação",
mas em nenhum lugar do Portal do Solicitante aparecia **o que** o
avaliador tinha pedido — só a mensagem genérica "um(a) avaliador(a) pediu
mais informações". O solicitante não sabia o que enviar.
**Causa raiz:** `Parecer.justificativa` nunca era repassada a nada voltado
ao solicitante — nem ao cartão de situação do Portal, nem ao corpo do
e-mail pronto (`EmailTemplateService.emailSolicitaInfo`, 100% genérico). O
texto só existia no card interno do lado do OPERADOR.
**Correção aplicada (2026-08-11/12):**
`SolicitanteController.montarSituacaoPedido` passou a coletar as
justificativas de todo parecer `SOLICITA_INFORMACAO` (pode haver mais de
um, ver 4.8) e preencher `SituacaoPedidoView.detalhe`; mesmo bloco
acrescentado ao corpo do e-mail pronto. Imparcialidade preservada — só o
CONTEÚDO do pedido é exposto, nenhum campo de autoria do médico.
**Como evitar recair:** ao adicionar um campo obrigatório de texto pensado
para uso posterior (ex. justificativa que "o operador depende para redigir
X"), conferir explicitamente se ele chega a **todos** os destinatários
que precisam dele, não só o mais óbvio — nesse caso o texto existia e era
exibido corretamente do lado do operador havia dias antes de alguém notar
que faltava do lado do solicitante.

### 4.8 Pausa "Solicita informação" com mais de um pedido simultâneo
Cobrir múltiplos pedidos de informação abertos ao mesmo tempo (um por
avaliador) como estado independente por pedido, nunca "uma rodada única".
Ver `CLAUDE.md`, item 16 das Regras de negócio, e a seção "Solicita
informação — múltiplos pedidos simultâneos" — bug real corrigido no
processo 12/2026 de produção (2026-08-11/12). `SolicitacaoOnlineService
.EstadoInformacaoComplementar` é a fonte única; não duplicar essa lógica em
lugar nenhum novo.

---

## 5. Imparcialidade / vazamento de dado (nome do paciente, termo de busca)

### 5.1 Nome completo do paciente vazando em log de auditoria (recaída 2×)
**Sintoma:** o nome completo do paciente aparecia na tela de auditoria
(ADMIN-only), quebrando a convenção de imparcialidade/privacidade do
projeto (avaliadores só veem iniciais; auditoria também deveria).
**Ocorrências:**
1. `PROCESSO_CADASTRADO` (até 2026-07-28) — corrigido usando `Iniciais.de()`.
2. `ProcessoExportacaoController` (achado em 2026-08-03) — a mensagem de
   auditoria da exportação de dossiê incluía `dossie.nomePasta()`
   ("`<Paciente> - Processo CET-RS NN-AAAA`"). Corrigido: mensagem passou a
   citar só o id do processo.
**Como evitar recair:** qualquer chamada nova a `AuditoriaService.registrar`
que monte a mensagem a partir de um objeto/entidade do processo deve ser
lida com atenção redobrada para nome completo escondido dentro de um
método auxiliar (ex. `nomePasta()`, `toString()`) — nunca assumir que só
porque não há `paciente.getNome()` explícito na linha, o nome não está
vazando indiretamente.

### 5.2 Termo de busca/filtro nunca deve ser gravado em log de auditoria/aplicação
Regra fixa preventiva (mesmo padrão de vazamento de nome de paciente já
corrigido 2×, ver 5.1): em nenhuma das listas com busca (Processos,
Arquivo, Auditoria, Membros, Usuários, Controle de Urgências, Solicitações
online), o termo de busca do operador entra em auditoria ou log — porque
pode conter nome de paciente digitado pelo próprio operador. Ver
`CLAUDE.md`, "Auditoria — filtros e exportação".

### 5.3 `VerificadorNomePaciente` (chat avaliador↔operador) erra nos dois sentidos
**Sintoma:** nome curto do paciente (≤2 tokens, ex. "Ana Luz") passa livre
mesmo citado quase por completo na mensagem; palavra comum/topônimo (ex.
"clinicas", "alegre") bloqueia mensagens legítimas sem relação com o
paciente.
**Causa raiz:** o corte de 4 caracteres para considerar um token
"significativo" descartava nomes curtos inteiros; o bloqueio por 1 único
token de equipe, sem stoplist ampla, capturava vocabulário clínico comum e
topônimos.
**Correção aplicada (calibrada em 2026-08-10):** nome inteiro **curto**
(≤2 tokens) passou a bloquear já com 1 token citado; equipe passou a
exigir **2 tokens** distintos para bloquear (exceto equipe já curta [≤1
token], que basta 1). Ver `docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md`,
achados A4/A5, fase F3 (dependeu de decisão explícita do dono do produto —
calibragem é sempre trade-off entre bloquear demais e deixar passar).
**Como evitar recair:** qualquer ajuste futuro nessa calibragem é decisão
de produto, não técnica — não mexer sem aprovação explícita (o
`VerificadorNomePacienteTest` precisa cobrir nome curto inteiro, N-1 de N
tokens, token genérico de equipe e topônimo antes de qualquer mudança).

---

## 6. E-mail e anexos

### 6.1 `EmailSenderService.enviarComAnexo` enviava "sucesso" mesmo sem o anexo, se o arquivo não existisse em disco
**Sintoma:** o destinatário recebia um e-mail de "sucesso" prometendo um
anexo (ofício, comprovante SNT) que **nunca chegou** — sem nenhum erro
visível ao operador.
**Causa raiz:** quando `anexo != null` mas o arquivo não existia mais em
disco, o método enviava o e-mail sem o anexo em vez de falhar.
**Correção aplicada (2026-08-03):** se `anexo != null` e `!anexo.exists()`,
o método retorna `false` sem enviar nada (mesmo padrão de falha alta já
usado no resto do serviço). `anexo == null` continua sendo o caminho
legítimo de "enviar sem anexo de propósito".
**Como evitar recair:** qualquer envio de e-mail com anexo obrigatório
deve tratar "arquivo referenciado no banco mas ausente em disco" como
falha explícita, nunca como "enviar sem ele silenciosamente".

### 6.2 Reenvio (`registrarEnvio`) resetava `dataEnvio` de pareceres JÁ respondidos
**Sintoma:** o indicador de "tempo de resposta do avaliador" ficava
injustamente zerado para quem já tinha votado, contando o prazo de novo a
partir do reenvio.
**Correção aplicada (2026-08-03):** só pareceres com `resultado == null`
(ainda sem voto) têm `dataEnvio` atualizada no reenvio.
**Como evitar recair:** qualquer operação de "reenvio"/"convite de novo"
deve distinguir explicitamente entre pendências ainda abertas e itens já
resolvidos antes de tocar em campos de data usados por indicadores.

### 6.3 Reenvio podia "acordar" um processo pausado silenciosamente
**Sintoma:** reenviar durante a pausa `SOLICITA_INFORMACAO` fazia o
processo avançar para `ENVIADO` por engano, deixando o parecer que pediu
informação preso para sempre e pulando `ProcessoValidator.validarPausaDecisao`.
**Causa raiz:** a condição de avanço de status em `registrarEnvio` usava
`status.isEmAndamento()`, que incluía `SOLICITA_INFORMACAO`.
**Correção aplicada (2026-08-03):** `registrarEnvio` só avança para
`ENVIADO` quando o status **não** é `SOLICITA_INFORMACAO`.
**Como evitar recair:** ao usar um método de conveniência tipo
`isEmAndamento()` para decidir um avanço de estado, checar explicitamente
se ele engloba estados que **não deveriam** ser tocados por aquela ação
específica (aqui, a pausa é um subconjunto de "em andamento" que precisa
de tratamento diferente).

---

## 7. (reservado)

Nenhum item catalogado aqui ainda — mantido como número de seção reservado
para uma futura categoria que não se encaixe nas demais. Itens que pareciam
bug mas foram esclarecidos como comportamento correto estão em §11.

---

## 8. UI — regressões visuais reais

### 8.1 Cabeçalho fixo (`sticky-top`) "fatiava" linhas de altura variável no Painel
**Sintoma real de produção:** ao rolar a tabela do Painel, badges e botões
apareciam cortados/sobrepostos ao cabeçalho, parecendo colidir com a linha
seguinte.
**Causa raiz:** `<thead>` com `sticky-top` sobre linhas de altura MUITO
variável (1 a 4 linhas de conteúdo por célula) — a metade de cima de uma
linha ficava escondida atrás do cabeçalho fixo durante o scroll.
**Correção aplicada (2026-08-12):** remoção do `sticky-top` resolveu por
completo (testado; `border-collapse: separate` **não** resolvia,
hipótese descartada por experimento).
**Como evitar recair:** cuidado ao aplicar cabeçalho fixo (`sticky-top`)
sobre uma tabela com altura de linha muito variável — reproduzir
visualmente (app local + Playwright) antes de assumir a causa por leitura
de CSS; a hipótese óbvia (colisão horizontal) não foi a real aqui.

### 8.2 Chevron `.chevron-collapse` nunca girava visualmente
Bug real corrigido em 2026-08-08 — ver histórico
(`docs/historico/CLAUDE-log-sessoes-2026-07-a-08.md`, "Bug real corrigido:
chevron `.chevron-collapse` nunca girava visualmente") para o detalhe
completo; classe de bug: transição CSS dependente de uma classe JS que não
era alternada no elemento certo.

### 8.3 Cor genérica (azul "selecionado") aplicada a opções com significado semântico próprio — recaída 2×
**Sintoma:** cards de voto/atalhos lado a lado perdiam a cor própria de
cada opção (verde/vermelho/dourado) em favor de um esquema neutro único —
já recaiu 2× no card "Atalhos" e 1× nas caixas de voto do Portal do
Avaliador (`.voto-opcao`, virava azul genérico do Bootstrap ao selecionar,
independente do voto escolhido).
**Correção aplicada:** classe própria por opção
(`voto-opcao-favoravel`/`voto-opcao-nao-favoravel`/`voto-opcao-solicita-info`)
estilizada com os tokens já existentes (`--rs-green`/`--rs-red`/
`--rs-gold`), tanto no hover quanto ao selecionar.
**Como evitar recair:** **REGRA FIXA do produto** — qualquer grupo de
opções lado a lado com significado semântico distinto (voto, atalho,
badge) usa SUA PRÓPRIA cor, nunca uma cor neutra genérica "por elegância".
Aplicar proativamente em telas novas com esse padrão, sem esperar o
usuário reclamar de novo. Ver `CLAUDE.md`, "Decisões de não fazer" e
memória do projeto `feedback-cor-por-opcao-semantica-sgpur`.

---

## 9. Deploy / Infra

### 9.1 Secrets do GitHub Actions não migram junto com o repositório
**Sintoma:** deploy automático (CI→Deploy) fica quebrado por dias depois
de migrar/trocar o repositório remoto, com o CI (build/testes) continuando
**verde** — só a etapa de entrega (`scp`/`ssh`) falha
(`Load key ".../deploy_key": error in libcrypto`,
`Permission denied (publickey)`).
**Causa raiz:** o secret `SAUR_ORACLE_SSH_KEY` não acompanha o código numa
migração de repositório — precisa ser recadastrado manualmente no destino.
**Correção aplicada (2026-08-03):** secret recadastrado colando o
**conteúdo integral** da chave privada, incluindo linhas `BEGIN`/`END` **e
a quebra de linha final** — a ausência dela é a causa clássica de
`error in libcrypto` (formatação, não conteúdo).
**Como evitar recair:** ao trocar o repositório remoto, conferir
`Settings → Secrets → Actions` no destino **antes** de confiar no
pipeline — "CI verde" não significa "está no ar" nesse cenário. Ver
`CLAUDE.md`, seção "Deploy".

### 9.2 IP público efêmero da VM Oracle muda quando a instância é parada/reiniciada
**Sintoma:** `Deploy` falha em segundos, sempre no `ssh-keyscan` (timeout),
enquanto a aplicação em si continua saudável (o domínio DuckDNS já
apontava para o IP novo por conta própria).
**Correção aplicada (2026-08-21):** atualizadas as 3 ocorrências do IP
antigo no `.github/workflows/deploy.yml` e a documentação operacional.
**Pendência real, não resolvida:** reservar o IP público na Oracle Cloud —
sem isso, o mesmo incidente se repete na próxima vez que a instância for
parada/reiniciada pelo console. Ver `CLAUDE.md`, seção "Deploy", e memória
do projeto `deploy-sgpur-oracle`.
**Como evitar recair:** ao investigar "Deploy falha do nada", checar
primeiro se o IP/hostname hardcoded no workflow ainda bate com o IP real
da VM (`curl -Ik https://urgenciarenal.duckdns.org/login` continuando 200
enquanto o Deploy falha é o sintoma característico deste caso, distinto do
9.1).

---

## 10. Processo de build/teste (Maven, ambiente local)

### 10.1 `mvn test-compile` sem `clean` pode mascarar erro de compilação real
**Sintoma:** depois de mudar a assinatura de um método usado por testes, um
`mvn test-compile` roda "limpo", mas a mudança na verdade não recompilou o
teste dependente.
**Causa raiz:** o compilador incremental do Maven não recompila de forma
confiável um teste cuja única mudança de dependência foi noutro arquivo.
**Como evitar recair:** depois de mudar assinatura de método, rodar
`mvn clean test-compile` (ou `clean test`) ao menos uma vez antes de
confiar num `test-compile` incremental limpo. Ver `CLAUDE.md`, "Pitfalls
de processo".

### 10.2 Editar arquivo fonte enquanto `mvn test`/`mvn verify` roda em background corrompe o build
**Sintoma:** cascata de falhas de `ApplicationContext` em testes
completamente não relacionados ao que foi editado.
**Causa raiz:** edição concorrente corrompe `target/classes`/
`target/test-classes` enquanto o Maven ainda está lendo/escrevendo neles.
**Como evitar recair:** **nunca** editar arquivo fonte enquanto um
`mvn test`/`mvn verify` está rodando em background — esperar o build
terminar antes de editar de novo. Já recaiu 2× (2026-08-08, 2026-08-21).
Ver `CLAUDE.md`, "Pitfalls de processo", e memória do projeto.

### 10.3 Comando encadeado com `;` terminando em `echo`/`tee` mascara o exit code real
**Sintoma:** `mvn ...; echo "EXIT=$?"` sempre reporta 0, escondendo falha
real do comando anterior.
**Como evitar recair:** nunca terminar uma cadeia `;` num comando que
sempre sucede — sempre propagar o `$?` explicitamente (`RC=$?; ...; exit
$RC`) se precisar de um passo depois. Ver `CLAUDE.md`, "Pitfalls de
processo", e memória do projeto `feedback-bash-echo-mascara-exit-code`.

### 10.4 Teste de atualização (`atualizar()`) que não relê do banco não pega campo esquecido
**Sintoma:** usuário salva uma edição, vê "sucesso", e o dado é
**silenciosamente perdido** — sem erro nenhum. Já aconteceu 3×:
`UsuarioService.atualizar` (esqueceu o e-mail), `MembroController.salvar`
(usava `persist` em vez de `merge`), `ControleUrgenciaService.atualizar`
(esqueceu `dataVencimento`, mesmo o form oferecendo o campo).
**Causa raiz:** os métodos `atualizar()` copiam campo a campo manualmente;
um teste com **repositório mockado** passa mesmo esquecendo um campo,
porque nunca relê o estado persistido de verdade.
**Como evitar recair:** o teste de atualização deve alterar **todos** os
campos editáveis com valores distintos, salvar, **reler a entidade do
banco** (não do mock) e afirmar **cada campo** individualmente. Regra
fixa de convenção — ver `CLAUDE.md`, "Convenções de código".

---

## 11. Quase-bugs esclarecidos (investigados, concluídos como comportamento correto)

Itens que pareciam bug num primeiro relato, mas a investigação concluiu
que o comportamento é intencional — registrados aqui para não reabrir a
mesma investigação do zero:

- **4.2** — "Retomar análise decide com maioria já formada, sem esperar o
  3º voto": confirmado como regra de produto (maioria simples 2/3 dispensa
  o terceiro voto assim que a pausa é removida). Ver
  `docs/RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md`.
- **Achado 12** do `docs/RELATORIO-VISTORIA-BRECHAS-DECISAO-2026-08-10.md`
  — cancelamento de processo: verificado sem regressão (Portal do
  Solicitante mostra "Cancelado", não "Reprovada"; Relatório Final regenera
  corretamente com `RESULTADO: CANCELADO`).
- **Status gravado do processo 12/2026**: confirmado **correto**
  (`SOLICITA_INFORMACAO` é exatamente o que a regra manda). O que estava
  errado era a exibição (ver 4.5), não o dado.

---

## Índice rápido por sintoma

| Se você vai... | Consulte |
|---|---|
| Adicionar `@Version` ou coluna não-nula a entidade já populada | §1.1, §1.2 |
| Adicionar valor a um enum `@Enumerated(STRING)` existente | §1.3 |
| Tornar uma coluna nullable que já era `NOT NULL` em produção | §1.4 |
| Escrever query de listagem com filtro opcional | §1.5 |
| Usar `/*[[expr]]*/` num `<script>` Thymeleaf | §2.1 |
| Escrever `th:classappend`/`th:class` com condição composta | §2.2 |
| Adicionar `<input type="date">` ligado a `LocalDate` | §2.3 |
| Adicionar conteúdo que deve viajar com um fragment | §2.4 |
| Renomear/acentuar um rótulo/botão visível | §2.5 |
| Adicionar rota que grava algo irreversível (voto, decisão, exclusão) | §3.1 |
| Mexer em `ProcessoValidator`/pausa/coordenador/maioria | §4 (todos) |
| Mexer no chat avaliador↔operador ou solicitante↔operador | §5.3, `docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md` |
| Adicionar/alterar envio de e-mail com anexo | §6 |
| Escrever teste de `Service.atualizar()` | §10.4 |
| Investigar "Deploy falhou do nada" | §9 |

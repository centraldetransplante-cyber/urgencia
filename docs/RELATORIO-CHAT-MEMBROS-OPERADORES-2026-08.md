# Relatório de diagnóstico e proposta de arquitetura — Chat interno entre Membros da Urgência Renal (AVALIADOR) e Operadores

**Data:** 2026-08-06 · **Analista:** auditoria técnica (Opus 5)
**Escopo:** funcionalidade **nova** — canal de comunicação escrita, dentro do
SAUR, entre os médicos avaliadores (perfil `AVALIADOR`) e a equipe operacional
(perfis `OPERADOR`/`ADMIN`). Hoje esse canal **não existe em nenhuma forma**.
**Quinto da série.** Complementar a:
- `RELATORIO-UI-SOLICITANTE-AVALIADOR-2026-08.md` (Portais externos, Fases 1–10)
- `RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md` (área do operador, Fases A–E)
- `RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md` (interação, polling, teclado)
- `RELATORIO-OFICIO-COMPROVANTE-SNT-2026-08.md` (fluxo pós-decisão)

> **Documento de diagnóstico e proposta. NENHUM código foi alterado para
> produzi-lo, e nada aqui deve ser implementado antes das aprovações da §11.**
> Todo achado sobre o estado atual foi verificado por leitura do código real,
> com `arquivo:linha`. Onde a decisão depende de produto, o texto diz
> explicitamente **"decisão do usuário"** e apresenta uma recomendação — nunca
> decide sozinho.

## STATUS: IMPLEMENTADO (2026-08-06/07, commit `6d9b8a5`)

**Este relatório deixou de ser só diagnóstico — a proposta foi aprovada e
construída.** Numa sessão retomada à noite do mesmo dia (ver CLAUDE.md, seção
"Sessão de 2026-08-06/07... item 3, Feature nova — chat interno Avaliador
(Membro) ↔ Operador"), o usuário reverteu uma decisão anterior de descartar a
feature e aprovou a implementação das Fases F1–F5 do plano abaixo (F6/F7 —
e-mail de notificação e canal geral sem processo associado — ficaram fora de
escopo, deliberadamente).

Resumo do que foi implementado, para quem for ler este relatório como
histórico: entidade nova e separada `domain/MensagemAvaliador.java` (não
reaproveita `MensagemSolicitacao`, cuja CHECK constraint de enum está
congelada em produção); `service/MensagemAvaliadorService` espelhando
`MensagemSolicitacaoService`; proteção de imparcialidade via
`service/VerificadorNomePaciente.java` (bloqueio determinístico de nome do
paciente/equipe solicitante na mensagem, por palavra inteira); endpoints em
`AvaliadorController` (lado avaliador) e `ProcessoDetalheController` (lado
operador); caixa de entrada em `GET /processos/mensagens-avaliadores`; reuso
integral de `chat-solicitacao.js` sem modificação; auditoria sem texto da
mensagem nem nome do paciente. Detalhes completos na seção do CLAUDE.md
citada acima — este documento continua valendo como registro do diagnóstico e
da arquitetura original que guiou a implementação.

---

## 1. Sumário executivo

O médico avaliador é hoje o único participante **mudo** do SAUR. Ele recebe um
PDF anonimizado, vota, e pronto: `AvaliadorController` (652 linhas) expõe
exatamente três capacidades — listar pendentes, ver o PDF, votar. Não há
nenhum campo em que ele possa escrever para a Secretaria, e não há nenhum
caminho pelo qual a Secretaria escreva para ele **dentro do sistema** (só
e-mails automáticos de convite e lembrete, que são de mão única e não aceitam
resposta rastreável).

Na prática, isso significa que toda dúvida operacional real — *"o PDF abriu em
branco"*, *"este processo é o mesmo do mês passado?"*, *"perdi minha senha do
portal"*, *"vou viajar, remanejem meus pendentes"* — acontece hoje **fora do
sistema**, por telefone ou WhatsApp, sem trilha, sem prazo e sem ninguém além
das duas pessoas envolvidas sabendo que aconteceu.

**A boa notícia é que quase toda a infraestrutura já existe e está madura em
produção.** O chat Solicitante↔Operador (`MensagemSolicitacao` +
`chat-solicitacao.js`) resolve, em 3 telas, exatamente o mesmo conjunto de
problemas técnicos: poll AJAX sem recarregar página, renderização de balões,
soft-delete, timestamps relativos, marcação de lido, notificação sonora/toast,
pausa com aba em background, badge na navbar. O módulo JS já está
**parametrizado por configuração** (`chat-solicitacao.js:9`,
`iniciarChatSolicitacao(cfg)`), com URLs, seletores e rótulos todos vindos de
fora. Ele foi escrito genérico e é reutilizável **sem modificação**.

**A má notícia é que este canal novo não é uma cópia do canal antigo.** Ele
cruza a regra mais rígida do sistema:

> O avaliador nunca pode saber quem é o paciente, nem qual é a equipe
> solicitante, nem quem são os outros dois avaliadores do mesmo processo.

Um chat é uma caixa de texto livre. Do lado do operador, a tela
(`processos/detalhe.html`) mostra o nome completo do paciente a poucos
centímetros do campo de digitação. **A única coisa que separa um sistema
íntegro de uma quebra irreversível de imparcialidade é o operador não digitar
um nome** — e "não esquecer" nunca foi um controle de segurança aceitável.
Este é o risco central do relatório e está tratado na §5 e na §8.

**Os cinco pontos que mais importam:**

| # | Ponto | Por quê |
|---|-------|---------|
| 1 | **Vazamento de nome do paciente pelo operador** é o risco de design dominante | O risco é **unidirecional**: o avaliador não sabe o nome, logo não pode vazá-lo. Só o operador pode. Isso torna o problema tratável — §8.1 propõe uma verificação **determinística** (não heurística) que só é possível se a conversa for vinculada a um `Processo`. |
| 2 | Se a conversa for por processo, ela **tem** de ser 1:1 (avaliador ↔ equipe), nunca em grupo | Três avaliadores num mesmo grupo destruiriam a independência dos pareceres, que é a razão de existir do modelo de 3 médicos e maioria simples. Não é preferência de UI: é regra de negócio. |
| 3 | O chat **não pode virar um atalho para "Solicita informação"** | Hoje pedir informação clínica tem um caminho formal e caro de propósito: o voto `SOLICITA_INFORMACAO`, que **pausa o processo**, reabre o parecer e gera e-mail à equipe solicitante. Um chat informal ao lado do botão de voto convida o médico a pedir por ali — e o pedido some do fluxo. §4.6. |
| 4 | Estender `MensagemSolicitacao` com um discriminador é **tecnicamente perigoso** e deve ser recusado | Três motivos concretos, não estéticos: a FK `solicitacao_online_id` é `NOT NULL` (`MensagemSolicitacao.java:18-20`); o enum `RemetenteMensagem` tem uma **CHECK constraint congelada em produção** (`mensagem_solicitacao_remetente_check`, uma das duas únicas que sobraram, ver CLAUDE.md); e as consultas de contagem existentes passariam a somar mensagens do canal novo no badge do canal antigo, silenciosamente. §6.2. |
| 5 | O avaliador é o único perfil **sem poll global de notificação** | `layout.html:217-288` tem dois blocos de polling (ADMIN/OPERADOR e SOLICITANTE). O AVALIADOR tem apenas o sino estático `pendentesAvaliador` (`layout.html:163-171`), renderizado uma vez e congelado. Sem um terceiro bloco, uma mensagem para o médico pode ficar dias sem ser vista. §7.4. |

**Recomendação de escopo (sujeita a aprovação, §11):** implementar
**conversa por processo, 1:1 (avaliador ↔ equipe operacional), iniciável pelos
dois lados**, em entidade nova e separada, reaproveitando o JS existente sem
modificá-lo. Não implementar canal geral na primeira leva.

---

## 2. Método e limites

**Examinado integralmente:** `MensagemSolicitacao.java` (134 linhas),
`MensagemSolicitacaoService.java` (132), `MensagemSolicitacaoRepository.java`
(25), `chat-solicitacao.js` (235), `AvaliadorController.java` (652),
`GlobalModelAdvice.java` (232), `SecurityConfig.java` (275),
`AuditoriaService.java` (80), `Iniciais.java`, `MembroUrgenciaRenal.java`,
`Perfil.java`, os blocos de chat/poll de `layout.html` (`:110-298`,
`:449-509`), o card de chat e a seção "Respostas dos Avaliadores" de
`processos/detalhe.html`, e `avaliador/lista.html` + `avaliador/votar.html`.

**Examinado por varredura:** os endpoints de chat dos três controllers
(`ProcessoDetalheController:681-776`, `SolicitanteController:444-620`,
`SolicitacaoOnlineTriagemController:169-275`), as chamadas de
`AuditoriaService.registrar` relacionadas a mensagem, o `Parecer` e as
consultas de `ParecerRepository`, e o inventário de `static/js/` (18 arquivos).

**Fora de escopo (deliberado):**
- Qualquer alteração em regra de decisão (maioria simples, coordenador
  CET-RS), no `ProcessoValidator`, no fluxo de 5 passos ou no chat
  Solicitante↔Operador existente.
- A **posição do chat com o solicitante** em `/processos/{id}`: é REGRA fixa
  do produto desde 2026-08-06 (barra lateral esquerda, `col-lg-3`). Este
  relatório trata explicitamente de **não** colidir com ela (§7.3), e não
  propõe movê-la.
- WebSocket e Web Push: já foram analisados e **desaconselhados** no
  `RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md` §8. Esta proposta segue a mesma
  decisão e usa poll AJAX.

**Limites honestos:**
1. Não subi a aplicação nem exercitei nenhum fluxo em navegador para produzir
   este documento — é análise estática. As afirmações sobre comportamento
   visual do chat atual vêm da leitura do template + do JS, não de observação.
2. **Não sei com que frequência a comunicação Membro↔Operador acontece hoje
   por fora**, nem qual é a natureza mais comum dessas conversas. Isso não é
   levantável pelo código, e muda o cálculo de custo/benefício de várias
   decisões abaixo — é a primeira pergunta da §5.

---

## 3. O que já existe (base para reaproveitar, não reinventar)

### 3.1 `MensagemSolicitacao` — a entidade

`domain/MensagemSolicitacao.java`. Modelo simples e correto:

| Campo | Observação relevante para o canal novo |
|---|---|
| `solicitacaoOnline` (`:18-20`) | `@ManyToOne(optional = false)` + `nullable = false`. **É a âncora da thread** — o modelo inteiro assume "uma conversa por solicitação". |
| `remetente` (`:22-24`) | Enum aninhado `RemetenteMensagem { SOLICITANTE, OPERADOR }`, `@Enumerated(STRING)`. |
| `remetenteId` (`:26-27`) | Id do `Usuario`, não do papel. É o que permite `podeApagar` preciso. |
| `texto` (`:29-35`) | **Nullable de propósito**, com comentário explicando o bug real de 2026-07-28: o soft-delete zera o texto, e a coluna `NOT NULL` fazia "apagar mensagem" quebrar em produção. |
| `lida` (`:40-41`) | Booleano simples. Semântica atual: "lida pelo outro lado". Do lado do operador é uma **caixa compartilhada** — qualquer OPERADOR que abrir marca como lida para todos. |
| `deletada` / `deletadaEm` (`:43-47`) | Soft delete. A mensagem permanece na base; a UI mostra "Mensagem apagada". |
| `versao` (`:49-50`) | `@Version`. |

**Não existe** anexo em mensagem, edição de mensagem, reação, menção ou
resposta encadeada. O canal novo não precisa de nenhum deles na primeira leva.

### 3.2 `MensagemSolicitacaoService` — a camada de regra

`service/MensagemSolicitacaoService.java`, 132 linhas, sete métodos públicos.
Dois merecem destaque porque são o *padrão* a repetir:

**`paraChat(...)` (`:113-131`)** projeta a entidade num record
`MensagemChatView` (`:103-105`) **já relativo a quem está vendo**: `deVoce`,
`nomeRemetente`, `podeApagar` são calculados no serviço, não no template. O
javadoc registra o motivo: evitar duplicar "de quem é essa mensagem" em 3
templates. É o mesmo princípio de DTO projetado que `AvaliadorController` usa
para não vazar `pacienteNome` (`AvaliadorController.java:286-291`) — e no canal
novo esse princípio deixa de ser higiene e vira **controle de segurança**.

**`apagar(...)` (`:80-93`)** exige `remetenteId` **e** `remetente` iguais aos
da mensagem. Ninguém apaga mensagem de outro, nem mesmo outro operador.

### 3.3 `chat-solicitacao.js` — o módulo de poll

`static/js/chat-solicitacao.js`, 235 linhas. **É a peça mais reaproveitável do
sistema inteiro.** Toda a configuração entra por parâmetro
(`iniciarChatSolicitacao(cfg)`, `:9`): `pollUrl`, `sendUrl`, `deleteUrlBase`,
`formSelector`, `inputSelector`, `chatBoxSelector`, `emptySelector`,
`badgeTotalSelector`, `badgeNaoLidaSelector`, `notifMensagem`,
`labelApagadaOutro`, `intervaloMs`.

Qualidades já resolvidas que **não** precisam ser reescritas:

1. **Assinatura de estado** (`:123-148`) — não reescreve o DOM quando o poll
   devolve a mesma conversa, preservando scroll e seleção de texto.
2. **Detecção de mensagem nova por comparação de IDs entre ciclos**
   (`:150-161`), com `idsRecebidosConhecidos = null` até o primeiro poll, para
   nunca notificar sobre o que já estava na tela ao carregar.
3. **Pausa com `visibilitychange`** (`:227-230`).
4. **Timestamps relativos** reavaliados a cada poll (`:45-60`).
5. **Confirmação de apagar** via modal genérico com fallback (`:32-38`).
6. **CSRF** lido das metatags (`:17-20`).
7. **`podeEnviar: false` esconde o formulário** (`:171`) — mecanismo pronto
   para o modo somente-leitura que a §5.4 propõe.

**Duas restrições técnicas a registrar antes de reusar:**

- **(a)** O sufixo da URL de apagar é **hardcoded**: `cfg.deleteUrlBase + id +
  '/apagar/ajax'` (`:217`). Os endpoints novos devem seguir exatamente a mesma
  forma de URL, ou o módulo precisa de um parâmetro a mais. Recomendo seguir a
  forma (custo zero).
- **(b)** Todos os seletores usam `document.querySelector` com IDs fixos vindos
  do `cfg`. Duas instâncias na mesma página **funcionam**, desde que recebam
  seletores distintos. Isso importa: a §7.3 coloca dois chats na mesma tela
  (`/processos/{id}`).

### 3.4 A notificação global (`layout.html`)

Dois blocos `<script>` dentro do fragment `navbar`:

- **ADMIN/OPERADOR** (`:217-256`) → `GET /processos/solicitacoes-online/nao-lidas-count`
- **SOLICITANTE** (`:257-288`) → `GET /solicitante/nao-lidas-count`

Ambos: poll de 20 s, comparação contra `sessionStorage` (só notifica se o
número **subiu**, e o primeiro ciclo só define a base), pausa com
`visibilitychange`, e `th:if="${... chatAtivoNestaTela != true}"` para não
duplicar som/toast nas telas que já têm poll próprio de 5 s.

**O AVALIADOR não tem bloco equivalente.** Tem apenas o sino estático da
navbar (`layout.html:163-171`), alimentado por
`GlobalModelAdvice.pendentesAvaliador()` (`:80-94`) — um número renderizado no
`render` e congelado até F5. Já era um achado do relatório de interação
avançada (item 2, "o contador de pendências do avaliador nunca se atualiza");
com um canal de mensagens, deixa de ser incômodo e vira defeito funcional.

`tocarNotificacao()` (fragment `notificacaoSonora`, `layout.html:454+`) e
`mostrarToast()` (`static/js/toast.js`, unificado em 2026-08-04) já estão
disponíveis em **toda** tela que inclui a navbar — incluindo as do avaliador.
Nada a fazer aqui além de usar.

### 3.5 Identidade, vínculo e rotas

- `Perfil` (`domain/Perfil.java`): `ADMIN`, `OPERADOR`, `AVALIADOR`,
  `SOLICITANTE`. Nenhum perfil novo é necessário.
- `Usuario.membro` (`domain/Usuario.java:52-57`): `@ManyToOne` nullable, LAZY.
  Preenchido só para AVALIADOR. **É a ponte entre o login e o médico** —
  `MembroUrgenciaRenal` é a entidade que o `Parecer` referencia, não o
  `Usuario`.
- `AvaliadorController.resolverMembro(...)` (`:585-607`) já resolve
  `Principal → Usuario → MembroUrgenciaRenal`, recarregando a entidade completa
  (o proxy LAZY não sobrevive fora de transação) e tratando **sessão órfã** com
  `SessaoInvalidaException`. **Reusar este método tal como está** — não
  duplicar a resolução em serviço novo.
- `SecurityConfig`: `/avaliador/**` → `hasRole("AVALIADOR")` (`:151`);
  `/processos/**` → `hasAnyRole("ADMIN","OPERADOR")` (`:150`). Os dois lados do
  canal novo já caem em rotas protegidas se forem pendurados nesses prefixos —
  **nenhuma regra nova de `SecurityConfig` é necessária**, o que é uma
  vantagem real (menos superfície para errar).

Uma consequência importante da modelagem atual: **o lado "operador" é sempre
uma caixa compartilhada**, porque a autorização é por *role*, não por posse
(`ADMIN`/`OPERADOR` acessam qualquer processo). Isso é o design pretendido do
sistema, confirmado na vistoria de segurança de 2026-07-28, e o canal novo deve
segui-lo em vez de inventar atribuição de conversa a um operador específico.

### 3.6 Auditoria — e o precedente que **não** pode ser quebrado

`AuditoriaService.registrar(acao, detalhe[, ip])` (`:27-46`): trunca o detalhe
em 400 caracteres, nunca derruba a operação principal.

O chat existente já registra, e registra **do jeito certo**:

```
SolicitanteController.java:551-552
    auditoria.registrar("MENSAGEM_SOLICITANTE_ENVIADA",
        "Solicitacao " + id + " - " + Iniciais.de(s.getPacienteNome()));
```

Ou seja: **id + iniciais, nunca o nome completo e nunca o texto da mensagem.**
Isso não é acaso — é o resultado de duas recaídas corrigidas
(`PROCESSO_CADASTRADO` em 2026-07-28, exportação de dossiê em 2026-08-03), em
que nome de paciente foi parar na tela `/auditoria`. O canal novo **deve**
seguir o mesmo padrão, e a §8.4 transforma isso em requisito explícito.

### 3.7 O que hoje o Portal do Avaliador tem — e não tem

`avaliador/lista.html` (442 linhas): pendentes atrasados, demais pendentes,
histórico dos próprios votos, estatísticas.
`avaliador/votar.html` (266 linhas): grade `col-xl-7` (PDF em `<iframe>`) /
`col-xl-5` (formulário de voto), modal de confirmação de voto.

**Não existe**, em nenhum dos dois: campo de texto livre para o médico
(exceto `justificativa`, que é do voto e não é conversa), qualquer indicação de
que exista alguém do outro lado, ou canal de retorno. O único texto que o
avaliador produz e que chega ao operador é `Parecer.justificativa`
(`Parecer.java:107-108`), obrigatório em voto negativo desde 2026-08-03 — e ela
é **entrada formal do ofício**, não conversa (ver §6.4).

---

## 4. Achados e restrições que a arquitetura precisa respeitar

### 4.1 A imparcialidade é uma regra de negócio, não uma preferência

`AvaliadorController.java:45-65` (javadoc da classe) e o CLAUDE.md são
inequívocos: o avaliador nunca vê nome do paciente, equipe solicitante,
co-avaliadores ou votos alheios. O PDF é anonimizado no envio
(`SolicitacaoAvaliadorService`), o cabeçalho carimbado usa iniciais, os
metadados do PDF são higienizados (`PdfCabecalhoStamper.anonimizarMetadados`),
e até os DTOs da tela de voto foram projetados especificamente para que um
`th:text` futuro digitado errado **não consiga** vazar o nome
(`AvaliadorController.java:286-291`).

Um chat de texto livre é a primeira funcionalidade do sistema que **não pode
ser protegida por construção**. Todo o resto do material que chega ao avaliador
passa por um transformador (`Iniciais.de()`, anonimização de PDF, DTO
projetado). Uma mensagem digitada não passa por nenhum. §8.1 trata disso.

### 4.2 O risco é unidirecional — e isso é a chave da solução

O avaliador **não sabe** o nome do paciente. Ele não pode vazá-lo nem por
descuido. Portanto:

- Mensagem **avaliador → operador**: risco de imparcialidade **nulo**.
- Mensagem **operador → avaliador**: é onde está 100% do risco.

Isso permite assimetria deliberada de controles: a caixa de digitação do
operador pode ser mais restritiva, mais avisada e verificada, sem prejudicar a
experiência do médico. É muito mais barato do que tratar as duas direções
igualmente.

### 4.3 Se for por processo, tem de ser 1:1 — não é escolha de UI

O modelo de decisão do SAUR é 3 pareceres independentes com maioria simples. Se
os 3 avaliadores de um processo compartilhassem uma conversa, cada um saberia
quem são os outros dois e, cedo ou tarde, o que eles acham. Isso **anula** o
desenho do sistema.

Consequência de modelagem: a chave da thread é o par **(processo, membro)**, e
uma consulta jamais pode devolver mensagens de um membro para outro. A
verificação de posse existente — `parecerRepo.findByProcessoIdAndMembroId(...)`,
já usada em `AvaliadorController.baixarPdf` (`:520-523`) — é exatamente o
predicado necessário e deve ser reusada.

### 4.4 Há coisas que o sistema **não consegue** impedir (e é preciso dizê-lo)

Nenhum controle técnico impede o operador de escrever *"os outros dois já
votaram favorável"* ou *"a equipe do HCPA insistiu muito nesse caso"*. Não há
como validar automaticamente a semântica de uma frase. O que o sistema pode
fazer: (a) avisar de forma persistente na caixa de digitação; (b) manter
trilha de auditoria de quem escreveu para quem e quando, que é o que
transforma um descuido invisível num descuido rastreável. **Isso precisa ser
dito com todas as letras ao dono do produto antes da aprovação** — ver §11.

### 4.5 A âncora do chat atual é a `SolicitacaoOnline`, não o `Processo`

Detalhe fácil de passar batido: o chat que aparece em `/processos/{id}` **não é
um chat do processo**. `ProcessoDetalheController.mensagensJson` (`:722-740`)
resolve `processo → solicitacaoOnlineOrigemId` e opera sobre a
`SolicitacaoOnline`; se o processo não tiver origem vinculada, devolve lista
vazia e `podeEnviar: false` (`:728-732`).

O canal novo é o oposto: ele nasce **do processo** (é sobre o caso em análise) e
não tem nada a ver com a solicitação de origem. Isso reforça, de forma
independente, que as duas conversas são entidades diferentes.

### 4.6 O chat pode canibalizar o `SOLICITA_INFORMACAO` — e isso seria grave

Hoje, quando um avaliador precisa de mais informação clínica, o caminho é
votar `ResultadoParecer.SOLICITA_INFORMACAO`. Esse voto é **caro de
propósito**: coloca o processo em `StatusProcesso.SOLICITA_INFORMACAO`, **pausa
a decisão** (`ProcessoValidator.validarPausaDecisao`), insere a etapa
"Informação complementar" no checklist, gera o e-mail à equipe solicitante e —
desde 2026-08-03 — **exige justificativa escrita**, porque o operador depende
desse texto para redigir o pedido.

Coloque uma caixa de texto informal ao lado do botão de voto e o
comportamento previsível é: o médico digita *"me manda a creatinina dos
últimos 3 meses"* no chat, o operador resolve por fora, e **o pedido nunca
entra no fluxo** — sem pausa, sem etapa, sem prazo, sem rastro no relatório
final. O indicador de tempo de resposta do avaliador (`TempoRespostaService`)
também passa a mentir, porque o médico ficou legitimamente esperando sem que o
sistema soubesse.

Mitigação proposta (§7.1): texto de ajuda fixo na caixa do avaliador
direcionando pedido de informação **clínica** para o voto correto, e escopo
declarado do chat como "dúvidas operacionais". É mitigação de UI, não garantia
— por isso está listado como risco aceito na §10.

### 4.7 Pegadinhas de infraestrutura já documentadas que se aplicam aqui

Do CLAUDE.md, aplicáveis diretamente a este módulo:

1. **`texto` deve nascer `nullable`.** O soft-delete zera o texto; a coluna
   `NOT NULL` foi bug real em produção (2026-07-28). Não repetir.
2. **Enum novo `@Enumerated(STRING)` numa tabela nova → o Hibernate cria uma
   CHECK constraint com os valores congelados.** Definir o enum completo desde o
   primeiro deploy; acrescentar um valor depois exige `ALTER TABLE DROP/ADD
   CONSTRAINT` manual na VM. O `EnumCheckConstraintValidator` avisa no boot,
   mas não corrige.
3. **`@Version` numa tabela nova não exige backfill** (nasce vazia) — ao
   contrário de `Processo.versao`/`Usuario.versao`. Incluir desde o início.
4. **`/*[[expr]]*/` exige `th:inline="javascript"`** na tag `<script>`. Bug
   real de 2026-07-28: as 3 chamadas do chat renderizaram string vazia por
   faltar esse atributo, e **nenhum teste pegou** — só `curl` no HTML servido.
5. **Rota que grava algo irreversível exige teste do caminho de falha sem mock
   do serviço.** Enviar mensagem a um médico é irreversível na prática (ele
   pode já ter lido). Ver `AvaliadorVotoTransacaoIntegrationTest` como modelo.
6. **Nunca `th:if` + `th:unless` no mesmo elemento; nunca ternário aninhado
   além de 2–3 níveis** em atributo Thymeleaf.

---

## 5. Perguntas de produto — decisões do usuário (com recomendação)

> Nada abaixo está decidido. Cada item traz a recomendação técnica e o custo de
> escolher diferente. **Nenhuma linha de código deve ser escrita antes das
> respostas de Q1, Q2 e Q3.**

### Q0 — Com que frequência isso acontece hoje, e sobre o quê?

Não é levantável pelo código. Se a resposta for *"raríssimo, duas vezes por
ano"*, a solução correta pode ser muito menor do que qualquer coisa proposta
aqui (§6.5 apresenta a alternativa de custo quase zero). Se for *"toda semana
alguém liga"*, o investimento se paga.

**Recomendação:** responder isto antes de aprovar o resto.

### Q1 — Escopo: por processo, canal geral, ou os dois?

| Opção | Prós | Contras |
|---|---|---|
| **(a) Por processo** (thread por `Processo` + membro) | Contexto explícito (todos sabem de que caso se fala); **habilita a verificação automática de nome** (§8.1), porque o sistema sabe exatamente qual nome não pode aparecer; casa com o modelo mental do médico ("tenho dúvida sobre o caso que estou avaliando") | Não serve para assunto sem processo ("estarei de férias em agosto"); exige cuidado extra de imparcialidade |
| **(b) Canal geral** (membro ↔ equipe, sem processo) | Zero risco *estrutural* de imparcialidade (não há processo em pauta); serve para assuntos administrativos | **A verificação automática de nome fica impossível** — sem processo, o sistema não sabe qual nome procurar. Se alguém mencionar um paciente mesmo assim, o vazamento é indetectável. E, na prática, o médico vai usá-lo para falar de casos, porque é o canal que existe |
| **(c) Os dois** | Cobre tudo | Dobra a superfície de UI, de testes e de notificação numa primeira leva |

**Recomendação: (a) por processo, na primeira leva.** O paradoxo importante é
que o canal geral *parece* mais seguro e é, na verdade, **menos** auditável: o
canal por processo é o único em que o sistema consegue verificar o texto contra
um nome concreto. Se depois de usar (a) sobrar demanda administrativa, (b) entra
como fase posterior (§9, F7) reutilizando 100% do que foi feito — basta tornar
`processo` nullable, mudança de uma linha.

### Q2 — Grupo ou 1:1?

**Não há escolha real se Q1 = (a).** Tem de ser 1:1: um par
(processo, avaliador), com o lado operacional funcionando como caixa
compartilhada (qualquer OPERADOR/ADMIN lê e responde). O avaliador vê o outro
lado como **"Equipe CET-RS"**, nunca o nome pessoal do operador — mesmo rótulo
já usado no chat do solicitante (`ProcessoDetalheController.java:737`).

Se Q1 = (b), a pergunta volta a fazer sentido e a resposta recomendada continua
sendo 1:1 pelas mesmas razões da §4.3.

### Q3 — Quem pode iniciar a conversa?

| Opção | Comentário |
|---|---|
| Só o avaliador | Mais conservador. O operador só responde. Elimina a categoria de risco "operador escreve espontaneamente algo que não devia" |
| Ambos | Mais útil: o operador consegue avisar *"o PDF do processo NN foi substituído, favor reler"* sem inventar um e-mail novo |

**Recomendação: ambos**, com a ressalva de que o operador já tem
`POST /processos/{id}/lembrete-avaliador` para o caso "vote logo" — o chat não
deve substituí-lo (o lembrete registra `Parecer.ultimoLembreteEm`, o chat não).
Se o usuário preferir máxima cautela na primeira leva, "só o avaliador inicia"
é uma restrição barata de implementar e barata de remover depois.

### Q4 — Até quando a conversa fica aberta?

Alternativas: (i) enquanto o parecer estiver pendente; (ii) enquanto o processo
não estiver finalizado; (iii) sempre.

**Recomendação: (ii)** — envio permitido enquanto o `Processo` não for
Deferido/Indeferido/Cancelado e o membro tiver `Parecer` naquele processo;
depois disso, **somente leitura** (o mecanismo `podeEnviar: false` do JS já
existe, `chat-solicitacao.js:171`). Motivos: (a) o médico frequentemente tem
dúvida *depois* de votar e antes da decisão; (b) manter conversa aberta em
processo encerrado conflita com o espírito da regra
`ProcessoValidator.edicaoBloqueada`, ainda que tecnicamente não seja uma edição
do `Processo`.

### Q5 — Apagar mensagem: mantém o comportamento atual?

O chat existente permite ao autor apagar a própria mensagem (soft delete, a
outra parte vê "Mensagem apagada"). **Recomendação: manter idêntico**, por
consistência e porque a implementação já está pronta e testada. Vale registrar
o efeito colateral: se o operador vazar um nome e apagar em seguida, o
avaliador **já pode ter lido** — apagar não desfaz nada. Isso é argumento a
favor da verificação *antes* do envio (§8.1), não contra o apagar.

### Q6 — O avaliador recebe e-mail quando o operador responde?

O sistema já manda e-mail ao avaliador em dois momentos (convite automático ao
registrar envio; lembrete manual). Um terceiro tipo é plausível, mas tem custo
de irritação e de SMTP.

**Recomendação: não na primeira leva.** Badge + som + toast no portal já é mais
do que o avaliador tem hoje. Se entrar depois (F6), com duas regras
inegociáveis: **nunca incluir o texto da mensagem no e-mail** (só "há uma nova
mensagem sobre o processo NN/AAAA, acesse o portal", com iniciais), e **enviar
fora da transação**, tratando falha de SMTP como aviso e nunca como rollback —
mesmo contrato de `RegistroEnvioService.enviarConvitesAvaliadores`.

### Q7 — Retenção: a conversa entra no dossiê/relatório final?

Hoje `ExportacaoProcessoService` monta o dossiê ZIP e `RelatorioService` o
relatório final. **Recomendação: não incluir** a conversa em nenhum dos dois na
primeira leva. Uma dúvida operacional (*"o PDF abriu em branco"*) não é peça de
instrução do processo administrativo, e incluí-la cria um problema novo: o
dossiê pode ser entregue à equipe solicitante, e a conversa contém a
identificação do avaliador — que a equipe solicitante não deve conhecer.

---

## 6. Modelagem de dados — alternativas e recomendação

### 6.1 Alternativa A — entidade nova `MensagemAvaliador` **(RECOMENDADA)**

Tabela nova `mensagem_avaliador`, espelhando `MensagemSolicitacao` e mudando só
a âncora:

| Campo | Tipo | Observação |
|---|---|---|
| `id` | `IDENTITY` | |
| `processo` | `@ManyToOne(LAZY, optional=false)` → `Processo` | Âncora da thread. **`nullable=false` na primeira leva**; se Q1 evoluir para canal geral, vira nullable |
| `membro` | `@ManyToOne(LAZY, optional=false)` → `MembroUrgenciaRenal` | O **lado avaliador** do par. Nunca `Usuario`: é `MembroUrgenciaRenal` que o `Parecer` referencia |
| `remetente` | enum novo `RemetenteMensagemAvaliador { AVALIADOR, OPERADOR }` | Enum **próprio**, não o de `MensagemSolicitacao` (ver §6.2) |
| `remetenteId` | `Long`, not null | Id do `Usuario` que escreveu |
| `texto` | `TEXT`, **nullable** | Obrigatoriamente nullable (soft delete) |
| `dataEnvio` | `LocalDateTime`, not null | |
| `lida` | `boolean`, not null | "Lida pelo outro lado". Do lado operacional, caixa compartilhada |
| `deletada` / `deletadaEm` | `boolean` / `LocalDateTime` | Soft delete |
| `versao` | `@Version` | Tabela nova → **sem backfill** |

Índice composto em `(processo_id, membro_id, data_envio)` — é a consulta de
thread, e é a única que roda a cada 5 s por avaliador com a tela aberta.

**Prós:**
1. **Isolamento estrutural entre dois domínios de privacidade diferentes.** Uma
   tabela pode conter contexto com nome completo (solicitante); a outra **nunca**
   pode. Separadas, nenhum bug futuro numa consulta contamina a outra. É
   exatamente o raciocínio que o CLAUDE.md registra para
   `RascunhoSolicitacaoOnline` (staging separado em vez de relaxar as
   constraints de `SolicitacaoOnline`) e para `SolicitacaoOnline` em si.
2. Zero risco de regressão no chat que já está em produção.
3. Semânticas divergentes ficam livres para divergir: `podeEnviar` do canal
   novo depende do status do processo e da existência de `Parecer`; o do canal
   antigo, do status da solicitação.
4. Nenhuma alteração de enum existente → **nenhum risco de CHECK constraint
   congelada** (§6.2).

**Contras e mitigação:**
- Duplica ~120 linhas de serviço/repositório muito parecidas.
  **Mitigação recomendada: aceitar a duplicação no Java e compartilhar o JS.**
  Extrair uma superclasse/interface comum acoplaria justamente os dois domínios
  que o desenho quer separar, e o ganho seria de ~100 linhas. O JS, por outro
  lado, já é genérico e o reuso é literalmente zero-custo. Se um dia um terceiro
  canal aparecer, aí sim vale generalizar — com três casos reais na mão.

### 6.2 Alternativa B — estender `MensagemSolicitacao` com discriminador **(DESACONSELHADA)**

A ideia seria acrescentar `AVALIADOR` ao enum `RemetenteMensagem`, tornar
`solicitacaoOnline` nullable e adicionar `processo`/`membro` nullable.

**Recuso, por quatro motivos concretos — nenhum estético:**

1. **A FK é `NOT NULL` e isso é uma invariante do canal atual**
   (`MensagemSolicitacao.java:18-20`: `optional = false` + `nullable = false`).
   Relaxá-la significa que todo código que hoje pode assumir
   `msg.getSolicitacaoOnline() != null` passa a poder receber `null` — e esse
   código está espalhado por 3 controllers. É exatamente o padrão de decisão que
   o CLAUDE.md já registrou e recusou ao criar `RascunhoSolicitacaoOnline`
   (*"relaxar essas constraints abriria uma classe de bug/risco nova"*).

2. **Risco real de CHECK constraint congelada em produção.** O CLAUDE.md
   documenta que sobraram exatamente **duas** CHECK de enum no Postgres da VM,
   e uma delas é **`mensagem_solicitacao_remetente_check`, com 2 valores**.
   `ddl-auto: update` **não** atualiza CHECK constraint. Acrescentar `AVALIADOR`
   ao enum funcionaria em dev/H2 (schema recriado a cada teste) e **quebraria em
   produção na primeira mensagem**, com `violates check constraint` — o
   incidente idêntico ao de `StatusSolicitacaoOnline.PROCESSO_EXCLUIDO`
   (2026-07-27). A alternativa A não toca em nenhum enum existente e, por criar
   uma **tabela nova**, nasce com a CHECK correta.

3. **Contaminação silenciosa dos contadores.** `countByLidaFalseAndRemetente`,
   `findDistinctSolicitacaoOnlineIdsByLidaFalseAndRemetente` e
   `countByRemetenteAndLidaFalseAndSolicitacaoOnlineUsuarioSolicitanteId`
   (`MensagemSolicitacaoRepository.java:16-24`) alimentam o badge da navbar do
   operador e do solicitante. Passariam a contar mensagens do canal novo a menos
   que **cada uma** ganhe um filtro adicional. Um esquecimento não dá erro: dá
   um número errado numa notificação, que é o tipo de bug que ninguém reporta e
   ninguém percebe.

4. **Mesmo raio de explosão para dois níveis de sigilo.** Colocar na mesma
   tabela as mensagens que *podem* citar o paciente e as que **nunca** podem
   significa que qualquer consulta mal escrita no futuro pode cruzar as duas.
   A separação física torna essa classe de erro impossível, e não apenas
   improvável.

### 6.3 Alternativa C — reaproveitar `Parecer` (campo de texto) **(REJEITADA)**

Acrescentar `observacaoAvaliador`/`respostaOperador` ao `Parecer`. Rejeitada de
imediato: não é conversa (dois campos não fazem uma thread), não tem estado de
leitura, não tem timestamp por mensagem e não escala além de uma troca. Além
disso, `Parecer.justificativa` (`:107-108`) é **entrada formal do ofício de
indeferimento** desde 2026-08-03 — sobrecarregar essa área com bate-papo
corromperia um insumo documental.

### 6.4 Alternativa D — não persistir nada: e-mail assistido **(faixa de comparação)**

Botão no portal que abre um template de e-mail pronto (padrão
`EmailTemplateService`) do avaliador para a Secretaria. Custo quase zero.
**Contras:** nenhuma trilha no sistema, nenhum estado de lido, a resposta cai
na caixa pessoal de um operador (não na equipe), e nenhum badge. É a régua
contra a qual medir o custo/benefício se a resposta de **Q0** for "isso quase
nunca acontece".

### 6.5 Recomendação

**Alternativa A**, com `processo` `NOT NULL` na primeira leva, enum próprio
completo desde o início, `texto` nullable, `@Version` presente, e reuso integral
de `chat-solicitacao.js` sem modificá-lo.

---

## 7. Proposta de UI

### 7.1 Lado do avaliador — `avaliador/votar.html`

Um card novo na **coluna direita** (`col-xl-5`, a mesma do formulário de voto),
posicionado **abaixo** do card de voto, `collapse` **recolhido por padrão**:

```
┌─ Dúvida sobre este processo ──────────────── [N] ▾ ─┐
│  (recolhido; expande ao clicar)                     │
│  ┌───────────────────────────────────────────────┐  │
│  │  balões (chat-box, max-height 350px)          │  │
│  └───────────────────────────────────────────────┘  │
│  [ Escrever para a equipe da Secretaria... ] [Enviar]│
│  ⓘ Para pedir informação clínica adicional, use o    │
│    parecer "Solicita informação" — é ele que aciona  │
│    a equipe solicitante e pausa o prazo.             │
└─────────────────────────────────────────────────────┘
```

Decisões e justificativas:
- **Abaixo do voto e recolhido**: a ação primária desta tela é votar. O card não
  pode competir com ela nem empurrar o formulário para fora da dobra —
  especialmente em celular, onde o `<iframe>` do PDF já ocupa 45–60 vh
  (`app.css`, ajuste de 2026-08-05).
- **Texto de ajuda fixo** (`form-text`): é a mitigação de §4.6. Não é garantia,
  é orientação no ponto exato da decisão.
- O card só aparece se o membro tiver `Parecer` no processo (sempre verdadeiro
  nessa tela) e o processo não estiver finalizado; caso contrário, histórico
  somente-leitura via `podeEnviar: false`.

### 7.2 Lado do avaliador — `avaliador/lista.html`

Uma coluna/ícone de "mensagens não lidas" por linha de pendente, ao lado do
número do processo, e — se houver conversa em processo já votado — uma seção
enxuta "Minhas conversas" abaixo do histórico. **Recomendo deixar para uma fase
posterior (F5)**: sem ela, o avaliador ainda é notificado pelo badge global
(F4) e chega à conversa abrindo o processo.

### 7.3 Lado do operador — onde colocar sem colidir com a REGRA

**A restrição:** o chat com o solicitante fica na **barra lateral esquerda**
(`col-lg-3`) de `/processos/{id}`. É REGRA fixa desde 2026-08-06, registrada
tanto no CLAUDE.md quanto num comentário no próprio template
(`processos/detalhe.html:229-236`). A barra já tem 4 cards (Progresso, Atalhos,
Textos de e-mail prontos, Conversa com o solicitante).

**Proposta: as conversas com avaliadores NÃO vão para a barra lateral.** Vão
para a **coluna direita** (`col-lg-9`), dentro do card **"Respostas dos
Avaliadores"** (`processos/detalhe.html:670+`, aba `pane-respostas`), como uma
linha expansível por avaliador na tabela que já existe:

```
Médico                    | Parecer      | Data resposta | Ação
──────────────────────────┼──────────────┼───────────────┼──────────────────────
HCPA - Verônica Horbe     | Favorável    | 04/08/2026    | [Lembrar] 💬 2
  └─ (linha expansível: thread 1:1 com este avaliador)
ISCMPA - Fulano de Tal    | (pendente)   | —             | [Lembrar] 💬
```

Por que aqui:
1. **Separação física e semântica** das duas conversas: solicitante à
   esquerda, avaliadores à direita. Nenhum risco de o operador confundir a caixa
   em que está digitando — o que, neste sistema, é um erro com consequência
   séria (mandar o nome do paciente para o lado errado).
2. É onde o operador **já** olha para o assunto "avaliadores": a tabela já traz
   `par.membro.rotulo`, "Aguardando há N dias", o botão "Lembrar por e-mail" e
   o "Último lembrete" (`:781-798`). A conversa é a continuação natural.
3. A estrutura `<th:block th:each="par : ${processo.pareceres}">` (`:731`) já
   envolve cada linha — acrescentar uma segunda `<tr>` expansível por avaliador
   é uma mudança contida.

**Nota técnica obrigatória:** com isso a página passa a ter **até 4 instâncias
de chat** (1 solicitante + 3 avaliadores). O módulo suporta, mas cada instância
precisa de IDs distintos (`#chatBoxAval{parecerId}` etc.) e de `notifMensagem`
próprio. O projeto já tem um teste que guarda contra colisão de `id`
(`IdsDuplicadosTest`) — ele deve continuar verde. Vale considerar carregar as
instâncias de avaliador **só quando a aba Respostas estiver ativa**, para não
manter 4 polls de 5 s numa tela só.

### 7.4 Lado do operador — caixa de entrada dedicada

Embutir só na tela do processo tem um furo: **se ninguém abrir aquele processo,
a mensagem do médico fica invisível**. Proposta: tela nova
`/processos/mensagens-avaliadores`, análoga a `/processos/solicitacoes-online`
(lista de threads, não lidas primeiro, colunas: processo, avaliador, última
mensagem, data), com item e badge na navbar.

**Privacidade da própria lista:** ela deve mostrar **número do processo +
nome do avaliador**, não nome de paciente. Não porque o operador não possa vê-lo
(pode), mas porque é uma lista de trabalho e nome de paciente em lista aumenta
exposição sem ganho — mesma linha de raciocínio já aplicada ao termo de busca,
que nunca vai para auditoria.

### 7.5 Notificação — três peças, todas já existentes

1. **Poll da thread aberta:** `iniciarChatSolicitacao({...})`, 5 s. Zero código
   novo de JS.
2. **Poll global do avaliador (NOVO):** terceiro bloco em `layout.html`, com
   `sec:authorize="hasRole('AVALIADOR')"`, apontando para um endpoint novo
   `GET /avaliador/nao-lidas-count`. Cópia estrutural do bloco existente
   (`layout.html:257-288`), incluindo `sessionStorage`, pausa por
   `visibilitychange` e `chatAtivoNestaTela`.
   **Atenção:** os dois blocos atuais são guardados por
   `th:if="${solicitanteHabilitado == true and ...}"`. O bloco do avaliador
   **não pode** herdar essa condição — este canal não tem nada a ver com o
   Portal do Solicitante e continuaria funcionando com aquele módulo desligado.
3. **Badge global do operador:** somar ao badge existente ou criar um segundo,
   ao lado do item de navbar novo da §7.4. **Recomendo um segundo badge
   separado** — misturar "solicitante escreveu" com "médico escreveu" numa
   contagem só destrói a informação mais útil (quem está esperando).

`tocarNotificacao()` e `mostrarToast()` já estão em toda tela com navbar. Nada a
fazer.

### 7.6 Reuso do JS: veredicto

**Reusar `chat-solicitacao.js` como está, sem modificar.** Requisitos que isso
impõe ao backend (todos triviais de atender):
- endpoints no formato `GET .../mensagens`, `POST .../mensagem/ajax`,
  `POST .../mensagem/{id}/apagar/ajax` (por causa do sufixo hardcoded em `:217`);
- resposta do poll `{ "mensagens": [...], "podeEnviar": bool }`;
- itens no formato de `MensagemChatView` (mesmos nomes de campo).

**Não renomear o arquivo** nesta leva (o nome fica um pouco impreciso, mas
renomear toca 3 templates em produção sem nenhum ganho funcional). Basta
atualizar o comentário do topo registrando que ele passou a servir dois canais.

---

## 8. Segurança e imparcialidade — controles concretos

### 8.1 Verificação determinística do nome antes do envio (operador → avaliador)

**A ideia central deste relatório.** Como a thread é vinculada a um `Processo`,
o servidor sabe **exatamente** qual string não pode aparecer: o
`pacienteNome` daquele processo (e, por extensão, `solicitanteEquipe`). Isso
torna a verificação **determinística**, não uma heurística de "detectar nomes
próprios" — que seria inviável e cheia de falso-positivo.

Esboço da regra, a ser aplicada **no endpoint de envio do operador, antes de
persistir**:

1. Normalizar mensagem e alvo: minúsculas, sem acentos (mesmo `Normalizer` já
   usado em `Iniciais.de()` e `ConflitoEquipeMatcher`).
2. Tokenizar `pacienteNome`, descartando conectivos ("da", "de", "dos"…) e
   tokens com menos de 4 caracteres.
3. Comparar por **palavra inteira** (não substring — senão "Ana" casaria dentro
   de "análise"), reaproveitando a técnica já validada em
   `ConflitoEquipeMatcher`.
4. **Dois níveis de resposta:**
   - **≥ 2 tokens do nome, ou o nome completo → bloqueia** (HTTP 400 + mensagem
     clara). Falso-positivo praticamente impossível.
   - **1 token → avisa e pede confirmação** ("esta mensagem contém *Rosa*, que
     faz parte do nome do paciente; confirma o envio?"). Cobre o caso legítimo
     de sobrenome comum sem travar o operador.
5. Aplicar a mesma checagem a `solicitanteEquipe` (o avaliador também não deve
   saber de que serviço veio o pedido).
6. **Nunca aplicar do lado do avaliador** — ele não sabe o nome, a checagem só
   produziria ruído.

Este controle é barato, testável de forma exaustiva com testes de unidade, e
não depende de nenhum serviço externo. **Ele é o argumento técnico mais forte
a favor de Q1 = "por processo"**, e desaparece por completo se o canal for
geral.

### 8.2 Avisos permanentes na composição (operador)

Acima da caixa do operador, sempre visível (não um `placeholder`, que some ao
digitar): *"Esta mensagem será lida pelo médico avaliador. Refira-se ao
paciente apenas pelas iniciais **M.R.M.** Não cite o nome, a equipe solicitante
nem os pareceres dos outros avaliadores."* — com as iniciais reais renderizadas
ali, para que copiar seja mais fácil do que digitar o nome.

### 8.3 Nada de entidade inteira no template

Do lado do avaliador, seguir o padrão já estabelecido em
`AvaliadorController.votar()` (`:286-291`): **nunca** passar `Processo`,
`Parecer` ou a entidade de mensagem ao template — só o record projetado. Se um
`th:text` futuro for digitado errado, o pior que pode acontecer é não renderizar
nada. Esse padrão foi introduzido exatamente para fechar essa classe de risco e
deve valer para as telas novas desde o primeiro commit.

### 8.4 Auditoria: registrar o ato, nunca o conteúdo

Ações sugeridas: `MENSAGEM_AVALIADOR_ENVIADA` e
`MENSAGEM_OPERADOR_AVALIADOR_ENVIADA`.
Detalhe permitido: número/id do processo + rótulo do membro (já é prática
estabelecida em `PARECER_VOTADO`, `AvaliadorController:402-406`) + iniciais.
**Proibido:** o texto da mensagem e o `pacienteNome` completo — regra que já
custou duas correções ao projeto (`PROCESSO_CADASTRADO` 2026-07-28; exportação
de dossiê 2026-08-03) e que o chat existente respeita
(`SolicitanteController:551-552`).

### 8.5 Posse verificada em todo endpoint

Lado avaliador: `parecerRepo.findByProcessoIdAndMembroId(processoId, membroId)`
presente **e** thread pertencente a esse membro. Nunca resolver a thread só por
id — mesmo cuidado que `SolicitanteController.baixarAnexoProcesso` tomou com o
whitelist de `TipoAnexo`.
Lado operador: role já basta (design pretendido do sistema), mas a consulta deve
sempre filtrar por `(processoId, membroId)`, nunca por id de mensagem solto.

### 8.6 CSP e cabeçalhos

Nada muda: o canal é `fetch` same-origin, sem recurso externo. A CSP de produção
(`SecurityConfig:79-87`) já cobre.

---

## 9. Plano faseado

Uma fase por PR, cada uma com suíte completa verde antes do merge, no estilo já
praticado no projeto. **F0 é obrigatória e não é código.**

| Fase | Conteúdo | Risco | Revisão humana antes de produção? |
|---|---|---|---|
| **F0** | Respostas de Q0–Q3 (§5) registradas por escrito. Nenhum código. | — | — |
| **F1** | `MensagemAvaliador` + `RemetenteMensagemAvaliador` + repositório + `MensagemAvaliadorService` (enviar / listar / marcar lidas / contar / apagar / `paraChat`) + `VerificadorNomePaciente` (§8.1) com testes exaustivos. **Nenhuma UI, nenhum endpoint.** | **Baixo** — código novo, isolado, nada existente é tocado | Não |
| **F2** | Endpoints do avaliador (`GET/POST /avaliador/{processoId}/mensagens…`) + card em `avaliador/votar.html` + reuso do JS. Inclui teste de integração do caminho de falha sem mock do serviço. | **Médio-baixo** — toca a tela mais sensível do sistema, mas de forma aditiva e recolhida por padrão | **Sim** (é a tela do médico) |
| **F3** | Endpoints do operador + threads embutidas no card "Respostas dos Avaliadores" + verificação de nome ativa no envio + avisos de composição. | **MÉDIO-ALTO — a fase de maior risco do plano** | **SIM, obrigatoriamente** |
| **F4** | `GET /avaliador/nao-lidas-count` + terceiro bloco de poll global em `layout.html` + badge no sino do avaliador. | **Baixo** — cópia estrutural de algo já em produção | Não |
| **F5** | Caixa de entrada `/processos/mensagens-avaliadores` + item/badge de navbar + indicadores em `avaliador/lista.html`. | **Baixo-médio** — tela nova, sem alterar existentes | Sim (leve) |
| **F6** *(opcional, depende de Q6)* | E-mail de notificação com throttle, sem conteúdo, fora da transação. | **Médio** — SMTP, e o padrão "e-mail nunca faz rollback do que já foi gravado" | Não |
| **F7** *(opcional, depende de Q1)* | Canal geral: `processo` nullable + tela própria. | **Médio** | Sim |

**Por que F3 é a de maior risco** (convenção do projeto: mudanças de UI de
grande superfície não vão a produção sem revisão visual humana, mesmo com suíte
e E2E verdes):
1. Mexe em `processos/detalhe.html` — **1.296 linhas**, a tela mais complexa do
   sistema, com wizard, 4 abas, timeline e o chat do solicitante.
2. É o mesmo arquivo que carrega a **REGRA fixa** de posição do chat lateral
   (`:229-236`). Um lote anterior já moveu esse card sem revisão e foi
   reportado como bug pelo dono do produto — o precedente é recente e concreto.
3. Introduz até 4 instâncias simultâneas de chat na mesma página (IDs, polls,
   toasts).
4. É a fase que **efetivamente cria o risco de vazamento**: até F2, só o
   avaliador escreve, e ele não sabe o nome do paciente.

**Testes exigidos pela convenção do projeto:**
- F1: teste de atualização que **relê do banco e confere campo a campo**;
  `VerificadorNomePaciente` com casos de nome composto, acento, sobrenome comum,
  substring falsa ("Ana" em "análise") e nome vazio.
- F2/F3: `@SpringBootTest` com H2 real e serviço real, forçando falha do
  pós-processamento e comprovando que a mensagem sobreviveu (ou não foi gravada)
  e que o usuário recebeu erro tratado, não 500 — modelo:
  `AvaliadorVotoTransacaoIntegrationTest`.
- F3: teste que garante que uma mensagem contendo o nome do paciente **é
  recusada** e não chega ao banco.
- Todas as fases com template: `AcessibilidadeEstruturaTest` e
  `IdsDuplicadosTest` devem continuar verdes sem ajuste.
- E2E: acrescentar ao `FluxoCompletoProcessoIT` um passo de "avaliador pergunta
  / operador responde" é desejável, mas **opcional** — lembrando que esse teste
  falha localmente há semanas no passo 5 por SMTP ausente, falha
  pré-existente e documentada.

---

## 10. Riscos

| # | Risco | Prob. | Impacto | Mitigação |
|---|---|---|---|---|
| R1 | **Operador cita o nome do paciente numa mensagem ao avaliador** | Média sem controles / Baixa com eles | **Alto** — quebra irreversível de imparcialidade num processo real | §8.1 (verificação determinística, bloqueio/confirmação) + §8.2 (aviso persistente com as iniciais à mão) |
| R2 | Operador relata a opinião dos outros avaliadores ou a insistência da equipe solicitante | Baixa | **Alto** | **Não é detectável por código.** Aviso na composição + trilha de auditoria como deterrente. Risco aceito conscientemente — ver §11 |
| R3 | O chat vira canal informal de pedido de informação clínica, contornando `SOLICITA_INFORMACAO` | **Média** | Médio-alto (processo sem pausa, prazo do avaliador contando indevidamente, nada no relatório final) | Texto de ajuda na caixa do avaliador (§7.1); monitorar após 1–2 meses de uso |
| R4 | Mensagem do médico fica dias sem ser lida porque ninguém abriu aquele processo | Média | Médio | F5 (caixa de entrada dedicada + badge na navbar). Enquanto F5 não existir, F3 sozinha tem esse furo — **considerar entregar F3 e F5 juntas** |
| R5 | Quatro polls simultâneos numa mesma tela degradam a experiência ou mantêm a sessão viva | Baixa | Baixo-médio | Instanciar os chats de avaliador só com a aba Respostas ativa; `visibilitychange` já vem de graça no módulo |
| R6 | Regressão visual em `processos/detalhe.html` | Média | Médio | F3 com revisão humana obrigatória; PR isolado, sem merge automático |
| R7 | Enum novo com valor acrescentado depois quebra em produção (CHECK congelada) | Baixa | Médio | Definir `RemetenteMensagemAvaliador` completo no primeiro deploy; `EnumCheckConstraintValidator` avisa no boot |
| R8 | `texto NOT NULL` reintroduzido por descuido → apagar mensagem quebra | Baixa | Médio | Nullable desde a criação + teste que apaga uma mensagem contra H2 real (o bug de 2026-07-28 passou por 526 testes com mock) |
| R9 | Funcionalidade construída e pouco usada | **Depende inteiramente de Q0** | Médio (esforço desperdiçado) | Responder Q0 antes de aprovar; §6.4 é a alternativa de custo quase zero |
| R10 | Conversa acaba exposta à equipe solicitante via dossiê/relatório, revelando a identidade do avaliador | Baixa | Alto | Q7: **não incluir** a conversa no dossiê nem no relatório final |

---

## 11. Itens que exigem aval explícito do usuário antes de qualquer código

> O dono do produto decide por aprovação explícita, não por inferência. Nada
> abaixo deve ser presumido a partir de uma aprovação genérica do relatório.

1. **Q0 — Isso é um problema real hoje?** Com que frequência médico e
   Secretaria precisam se falar, e sobre o quê? Se for esporádico, a alternativa
   de e-mail assistido (§6.4) pode ser a resposta certa, e o resto deste plano
   não deve ser executado.

2. **Q1 — Escopo: por processo, canal geral, ou os dois?**
   *Recomendação: por processo.* Consequência da escolha: só o modelo "por
   processo" permite a verificação automática de nome (§8.1); o canal geral é
   estruturalmente mais simples, porém **indetectável** se alguém citar um
   paciente.

3. **Q3 — Quem pode iniciar a conversa?**
   *Recomendação: os dois lados*, com o lembrete por e-mail permanecendo o
   caminho para "vote logo". Se o usuário preferir, restringir a "só o
   avaliador inicia" é barato agora e barato de reverter depois.

4. **Aceitação consciente do R2.** É preciso que o dono do produto registre que
   entendeu: **o sistema não tem como impedir** que um operador escreva algo que
   comprometa a imparcialidade (ex.: revelar como os outros votaram). Os
   controles propostos cobrem o nome do paciente e a equipe solicitante de forma
   determinística; o resto depende de conduta humana, apoiada por aviso na tela
   e trilha de auditoria.

5. **Q4 — Até quando a conversa aceita novas mensagens?**
   *Recomendação: até o processo ser decidido; depois, somente leitura.*

6. **Q6 — E-mail de notificação ao avaliador?**
   *Recomendação: não na primeira leva.* Se sim, sem nenhum trecho da mensagem
   no corpo do e-mail.

7. **Q7 — A conversa entra no dossiê ZIP / relatório final?**
   *Recomendação: não* (evita expor a identidade do avaliador à equipe
   solicitante).

8. **Autorização para F3 ir a produção** só após revisão visual humana, em PR
   separado e sem merge automático — mesma convenção usada nas fases de UI
   anteriores.

---

## 12. O que **não** fazer

1. **Não** mover o chat com o solicitante de `/processos/{id}`. É REGRA fixa,
   registrada no CLAUDE.md e no próprio template (`processos/detalhe.html:229-236`).
2. **Não** estender `MensagemSolicitacao` com discriminador (§6.2).
3. **Não** acrescentar valor ao enum `RemetenteMensagem` existente — há uma
   CHECK constraint congelada em produção esperando por isso.
4. **Não** passar `Processo`/`Parecer`/entidade de mensagem inteiros aos
   templates do Portal do Avaliador.
5. **Não** registrar o texto de nenhuma mensagem em `LogAuditoria` nem em log de
   aplicação.
6. **Não** introduzir WebSocket nem Web Push (já desaconselhados, com
   fundamentação, no `RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md` §8).
7. **Não** reescrever `chat-solicitacao.js`. Reusar como está; se algo faltar,
   acrescentar um parâmetro ao `cfg`, nunca bifurcar o arquivo.
8. **Não** criar perfil novo, nem regra nova em `SecurityConfig`: os prefixos
   `/avaliador/**` e `/processos/**` já cobrem os dois lados.
9. **Não** tornar o poll global do avaliador dependente de
   `app.solicitante.habilitado`.
10. **Não** usar `T(...)` em atributo Thymeleaf (quebra em tempo de render), e
    não esquecer `th:inline="javascript"` em qualquer `<script>` com
    `/*[[...]]*/`.

---

## Anexo A — comandos de verificação usados

```bash
# Inventário do chat existente
wc -l src/main/java/br/gov/saude/sgpur/domain/MensagemSolicitacao.java \
      src/main/java/br/gov/saude/sgpur/service/MensagemSolicitacaoService.java \
      src/main/java/br/gov/saude/sgpur/repository/MensagemSolicitacaoRepository.java \
      src/main/resources/static/js/chat-solicitacao.js

# Endpoints AJAX de chat nos 3 controllers
grep -rn "mensagem/ajax\|/mensagens\"\|nao-lidas-count" src/main/java/br/gov/saude/sgpur/web/*.java

# Confirmação de que auditoria de mensagem nunca leva nome completo
grep -rn "MENSAGEM_" src/main/java/

# Blocos de poll global e ausência de bloco para AVALIADOR
grep -n "nao-lidas-count\|setInterval\|sec:authorize" src/main/resources/templates/layout.html

# Posição atual do chat na tela do processo (REGRA)
grep -n "chatBox\|Conversa com\|col-lg-3" src/main/resources/templates/processos/detalhe.html
```

## Anexo B — esboço das assinaturas propostas (não implementado)

```java
// domain/MensagemAvaliador.java
@Entity @Table(name = "mensagem_avaliador")
public class MensagemAvaliador {
    public enum RemetenteMensagemAvaliador { AVALIADOR, OPERADOR }   // completo no 1o deploy
    Long id;
    @ManyToOne(fetch = LAZY, optional = false) Processo processo;     // ancora da thread
    @ManyToOne(fetch = LAZY, optional = false) MembroUrgenciaRenal membro;
    @Enumerated(STRING) RemetenteMensagemAvaliador remetente;
    Long remetenteId;                      // Usuario que escreveu
    String texto;                          // TEXT, NULLABLE (soft delete)
    LocalDateTime dataEnvio;
    boolean lida, deletada; LocalDateTime deletadaEm;
    @Version Long versao;                  // tabela nova -> sem backfill
}

// service/MensagemAvaliadorService.java  (espelha MensagemSolicitacaoService)
MensagemAvaliador enviar(Processo p, MembroUrgenciaRenal m, String texto,
                         RemetenteMensagemAvaliador de, Long remetenteId);
List<MensagemChatView> paraChat(Long processoId, Long membroId,
                                RemetenteMensagemAvaliador atual, Long atualId,
                                String labelEu, String labelOutro);
void   marcarComoLidas(Long processoId, Long membroId,
                       RemetenteMensagemAvaliador de, Long remetenteId);
long   contarNaoLidasParaMembro(Long membroId);      // badge global do avaliador
long   contarNaoLidasParaOperador();                 // badge global do operador
void   apagar(Long mensagemId, Long remetenteId, RemetenteMensagemAvaliador de);

// service/VerificadorNomePaciente.java  (§8.1) — determinístico, nunca heurístico
enum Nivel { LIVRE, ALERTA, BLOQUEADO }
record Resultado(Nivel nivel, List<String> termosEncontrados) {}
Resultado verificar(String texto, String pacienteNome, String solicitanteEquipe);

// Endpoints (forma de URL ditada pelo reuso de chat-solicitacao.js:217)
GET  /avaliador/{processoId}/mensagens
POST /avaliador/{processoId}/mensagem/ajax
POST /avaliador/{processoId}/mensagem/{mensagemId}/apagar/ajax
GET  /avaliador/nao-lidas-count
GET  /processos/{id}/avaliador/{membroId}/mensagens
POST /processos/{id}/avaliador/{membroId}/mensagem/ajax
POST /processos/{id}/avaliador/{membroId}/mensagem/{mensagemId}/apagar/ajax
GET  /processos/mensagens-avaliadores            (caixa de entrada, F5)
GET  /processos/mensagens-avaliadores/nao-lidas-count
```

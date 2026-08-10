# Relatório de vistoria — os DOIS sistemas de chat do SAUR

**Data:** 2026-08-10 · **Analista:** vistoria técnica (Opus 5)
**Escopo:** os dois canais de mensagem hoje em produção —
Solicitante↔Operador (`MensagemSolicitacao`, desde 2026-07-27/28) e
Avaliador↔Operador (`MensagemAvaliador`, desde 2026-08-06/07).
**Sexto da série.** Complementar a:
- `RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md` (arquitetura original do canal
  Avaliador↔Operador — **leitura obrigatória antes deste**; as decisões de
  design justificadas lá não são reabertas aqui)
- `RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md` (polling, teclado, WebSocket)
- `RELATORIO-UI-SOLICITANTE-AVALIADOR-2026-08.md` e
  `RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md` (UI dos portais e do operador)

> **Documento de diagnóstico e plano. NENHUMA linha de código foi alterada para
> produzi-lo.** Nada aqui deve ser implementado antes das aprovações da §7.
> Todo achado tem cenário concreto e foi **reproduzido de verdade** (ou está
> marcado explicitamente como "verificado por leitura"). Onde a decisão depende
> de produto, o texto diz **"decisão do usuário"** e apresenta recomendação —
> nunca decide sozinho.

---

## 1. Sumário executivo

Os dois chats estão **funcionalmente saudáveis**. A suíte completa passa
(**908 testes, 0 falhas**, JDK 21) e o E2E dedicado
(`ChatVisualVerificacaoIT`, Playwright/Chromium real, headless) percorre os
dois canais ponta a ponta **sem nenhuma falha**. Nenhum dos bugs históricos
documentados no `CLAUDE.md` regrediu — conferi um a um (§3).

O problema real não é nenhum dos dois chats isoladamente: é que **o canal do
avaliador tem um vazamento de sinal**. A mensagem do médico chega, é contada,
aciona badge e toast — e depois é marcada como lida por uma tela que o
operador nem estava olhando, sumindo de todos os indicadores sem que ninguém
a tenha lido. O achado **A1** é isso, e o **A2** é o botão que deveria levar o
operador até a conversa levando-o para a aba errada. Os dois juntos formam um
caminho completo em que a pergunta de um médico desaparece silenciosamente —
exatamente o problema que o canal foi criado para resolver.

**Os cinco pontos que mais importam:**

| # | Ponto | Por quê |
|---|-------|---------|
| 1 | **A1 — mensagem marcada como lida sem nunca ter sido exibida** | Abrir `/processos/{id}` em QUALQUER aba dispara o poll da thread do avaliador, que marca como lida. Reproduzido: `pane-respostas` inativa e `chatAval1` com `show` no mesmo HTML. |
| 2 | **A2 — "Abrir processo" da caixa de entrada não abre a conversa** | `#respostas` é âncora morta (o id real é `pane-respostas`) e nenhum JS lê `location.hash`. O hotfix `559b380` consertou o *parse*, não o *destino*. Alimenta o A1. |
| 3 | **A4/A5 — a verificação de nome erra dos dois lados** | Nome curto passa livre (`"Ana Luz"` de `"Ana Luz Silva"` → **LIVRE**); e palavra comum bloqueia (`"exame de clinicas"` → **BLOQUEADO**). Ambos reproduzidos. |
| 4 | **A6 — o caminho de SUCESSO do "apagar mensagem" não tem teste** | As 6 asserções existentes conferem só a **recusa** (`isDeletada()).isFalse()`). Se `texto` voltar a ser `NOT NULL` (o bug real de 2026-07-28), a suíte continua verde. |
| 5 | **Duas entidades quase idênticas: a duplicação está JUSTIFICADA** | Confirmei os três motivos do relatório original. Não é dívida a pagar — §4. |

**Recomendação de escopo:** executar as Fases 1 e 2 do plano (§8) como
correções, sem depender de decisão de produto; levar as Fases 3–5 ao dono do
produto antes de codar.

---

## 2. Método e limites

**Lido integralmente:** `MensagemSolicitacao.java` (134), `MensagemAvaliador.java`
(176), `MensagemSolicitacaoService.java` (132), `MensagemAvaliadorService.java`
(222), `MensagemSolicitacaoRepository.java` (25),
`MensagemAvaliadorRepository.java` (77), `VerificadorNomePaciente.java` (129),
`chat-solicitacao.js` (260), os blocos de chat/poll de `layout.html`
(`:76-400`), os 4 templates de chat e `mensagens-avaliadores-lista.html`.

**Lido por varredura:** os endpoints de chat dos 4 controllers
(`AvaliadorController:554-652`, `ProcessoDetalheController:886-1098`,
`SolicitanteController:600-660`, `SolicitacaoOnlineTriagemController:169-280`),
`GlobalModelAdvice:80-165`, `SecurityConfig`, e os testes existentes.

**Executado de verdade (não é análise estática):**
1. `mvn test` completo — **908 testes, 0 falhas** (contagem-base desta vistoria).
2. `mvn verify -Pe2e -Dit.test=ChatVisualVerificacaoIT -Dsaur.e2e.headed=false`
   — Chromium real, **1 teste, 0 falhas**, os dois chats ponta a ponta.
3. Aplicação subida de verdade (`java -jar`, H2/dev, porta 3011), login por
   formulário e navegação por `curl`.
4. **Um teste de integração temporário** (`@SpringBootTest` + H2 real +
   `MockMvc`), escrito só para esta vistoria, que renderiza o HTML de verdade
   de `/avaliador/{id}` e `/processos/{id}` e exercita
   `VerificadorNomePaciente`, `MensagemAvaliadorService.apagar` e `enviar`.
   **Foi apagado ao final** — os achados A1, A3, A4, A5, A6, A7 e A10 vêm da
   saída real dele, transcrita neste documento.

**Limites honestos:**
1. Não exercitei os chats em **celular real** nem com **leitor de tela real**.
   Os achados A12 (acessibilidade) e o item de teclado virtual da §6 vêm de
   leitura do HTML/CSS, e estão marcados como tal.
2. Não medi carga: as afirmações de performance (A13, A14) são estruturais
   ("carrega a tabela inteira", "sem índice"), não medições. Com o volume atual
   de produção (4 processos, 6 solicitações) nada disso dói hoje.
3. **Não sei com que frequência cada canal é usado hoje em produção.** Isso não
   é levantável pelo código e muda a prioridade de vários itens — é a primeira
   pergunta da §7.

---

## 3. Regressões dos bugs históricos: nenhuma

Conferi um a um os bugs que o `CLAUDE.md` documenta como já corrigidos.
**Todos continuam corrigidos.**

| Bug histórico | Estado hoje | Como conferi |
|---|---|---|
| `th:inline="javascript"` faltando em `/*[[expr]]*/` | **OK** nos 4 templates de chat (`solicitante/detalhe:327`, `solicitacoes-online-detalhe:140`, `processos/detalhe:1363`, `avaliador/votar:338`) | grep + HTML renderizado (`var url = "\/avaliador\/nao-lidas-count"`, valor real, não fallback) |
| `texto NOT NULL` quebrando "apagar mensagem" | **OK** nas duas entidades (`@Column(columnDefinition = "TEXT")`, sem `nullable=false`), com comentário explicativo | leitura + execução real de `apagar` (`deletada=true texto=null`) |
| Poll global não pausava em background | **OK** — os 4 blocos de `layout.html` têm `visibilitychange` + flag `pollAtivo`; `chat-solicitacao.js:252` idem | leitura |
| `mostrarToast` duplicado | **OK** — implementação única em `/js/toast.js`, carregado antes de `notificacaoSonora` (`layout.html:234-235`) | leitura |
| Som exigindo gesto do usuário | **OK** — `AudioContext` compartilhada destravada no 1º gesto | leitura |
| `marcarComoLidas` faltando em `/processos/{id}` | **OK** — presente (`ProcessoDetalheController:517`) | leitura |
| N+1 do card "Respostas dos Avaliadores" | **OK** — `resumoConversasDoProcesso` em lote (2 queries), coberto por `MensagemAvaliadorResumoConversasBatchTest` | leitura + teste existente |
| Sessão órfã → 401 cru | **OK** — `SessaoInvalidaException` em `usuarioLogado`/`resolverMembro` | leitura |
| BFcache / `sessionStorage` / scroll ao enviar | **OK** — o mecanismo antigo morreu junto com o server-rendering; hoje é assinatura de estado + preservação de scroll (`chat-solicitacao.js:129-148`) | leitura |
| Link quebrado da caixa de entrada (`559b380`) | **Parse OK, destino ainda errado** → vira o achado **A2** | HTML renderizado |

---

## 4. Consistência entre os dois sistemas (eixo 2)

### 4.1 Por que existem duas entidades — e por que está certo

O `RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md` §6.2 recusou estender
`MensagemSolicitacao` por quatro motivos. **Reverifiquei os três verificáveis
por código e todos continuam válidos:**

1. `MensagemSolicitacao.solicitacaoOnline` é `optional = false` +
   `nullable = false` (`:18-20`) — invariante de que 3 controllers dependem.
2. A CHECK `mensagem_solicitacao_remetente_check` é **uma das duas únicas
   sobreviventes em produção** (CLAUDE.md, reconfirmada por SQL em
   2026-08-03), e `ddl-auto: update` não a atualiza. Acrescentar `AVALIADOR`
   ao enum quebraria na primeira mensagem em produção. O `SchemaMigration` do
   boot confirma que a coluna é enum/VARCHAR gerenciada.
3. Os 4 contadores de `MensagemSolicitacaoRepository` alimentam badges do
   solicitante e do operador e passariam a somar o canal novo.

**Veredicto: a duplicação de ~100 linhas é dívida ACEITA e bem fundamentada.
Não propor unificação.** Unificar hoje custaria uma migração de CHECK
constraint em produção para economizar código que já está escrito, testado e
estável.

### 4.2 Onde os dois divergem — e se a divergência se justifica

| Aspecto | Solicitante↔Operador | Avaliador↔Operador | Justificado? |
|---|---|---|---|
| Verificação de nome do paciente | não tem | `VerificadorNomePaciente` | **Sim** — o solicitante já conhece o paciente |
| `podeEnviar` | assimétrico entre os lados | simétrico (`!status.isFinalizado()`) | **Não** → achado **A8** |
| Card nasce recolhido/expandido | sempre expandido | recolhido se não há conversa | Sim (espaço na tela do médico) |
| Poll inicia no load / ao expandir | sempre no load | só ao expandir **se vazio** | Parcial → alimenta **A1** |
| Contagem por thread | 1 query por tela | consulta em lote | Sim (o card tem 3 linhas) |
| Auditoria ao enviar | sim | sim | Sim |
| Auditoria ao apagar | **não** | **não** | Não → **A15** |
| Limite de tamanho no servidor | **não** | **não** | Não → **A7** |
| Índice de banco | **nenhum** | **nenhum** | Não → **A13** |

---

## 5. Achados

Severidade: **ALTA** = perda ou vazamento de informação de trabalho ·
**MÉDIA** = comportamento errado com impacto real · **BAIXA** = correção de
qualidade, sem impacto operacional hoje.

---

### A1 — ALTA · Mensagem do avaliador é marcada como lida sem nunca ter sido exibida

**Reproduzido.**

`/processos/{id}` abre na aba calculada pelo servidor (o primeiro passo do
wizard ainda não concluído, `ProcessoDetalheController:599-604`). No HTML
renderizado de um processo `ENVIADO` com uma mensagem não lida do avaliador:

```
class="tab-pane fade show active" id="pane-envio"     <- aba visível
class="tab-pane fade"             id="pane-respostas" <- OCULTA
<div class="collapse chat-avaliador-thread show" id="chatAval1"
```

A thread nasce com `show` porque já existe conversa
(`existeConversaPorParecer`), e `processos/detalhe.html:1416` faz
`if (elCollapse.classList.contains('show')) iniciarThread();` — sem nenhuma
checagem de a aba estar visível. `iniciarChatSolicitacao` dispara `poll()`
imediatamente (`chat-solicitacao.js:258`), e o endpoint de poll
**marca como lida no servidor** (`ProcessoDetalheController:1016`).

**Cenário concreto:** o Dr. avaliador escreve *"o PDF abriu em branco"* no
processo 12/2026. Badge da navbar = 1; a caixa de entrada mostra 1 nova. Um
operador abre `/processos/12` para mexer no **anexo** (aba Envio) e fecha.
Sem ter aberto a aba Respostas, sem ter visto nada: badge = 0, caixa de
entrada sem destaque, `table-warning` some. **A pergunta do médico
desapareceu de todos os indicadores do sistema.** Como o lado operador é caixa
compartilhada, some para *todos* os operadores de uma vez.

Vale para os 3 avaliadores do processo simultaneamente.

> O chat do solicitante tem o mesmo mecanismo, mas **não** o mesmo defeito: o
> card dele fica na barra lateral esquerda, sempre visível em qualquer aba
> (REGRA fixa do produto). Marcar como lida ali é correto.

**Arquivos:** `processos/detalhe.html:1389-1417`,
`ProcessoDetalheController:1004-1022`, `chat-solicitacao.js:188-199,258`.

---

### A2 — MÉDIA · O botão "Abrir processo" da caixa de entrada não abre a conversa

**Reproduzido.**

`mensagens-avaliadores-lista.html:42` aponta para
`/processos/{id}` + `'#respostas'`. **Não existe elemento com `id="respostas"`**
— o painel é `pane-respostas` e o botão da aba é `tab-respostas`. E `grep` por
`location.hash` em `static/js/` e nos templates devolve **zero** ocorrências:
nada ativa a aba a partir do fragmento da URL. A aba ativa é decidida
exclusivamente no servidor.

**Cenário concreto:** o operador vê "1 nova(s)" na caixa de entrada, clica em
"Abrir processo" — o botão que existe exatamente para levá-lo até a mensagem —
e cai na aba **Envio**. A conversa está a dois cliques de distância, sem
nenhuma indicação. E, por **A1**, a mensagem acabou de ser marcada como lida.

O hotfix `559b380` corrigiu a sintaxe Thymeleaf que derrubava a página, mas o
alvo do link continuou inválido — nenhum teste verifica o destino, só que a
página renderiza.

**Arquivos:** `processos/mensagens-avaliadores-lista.html:42`,
`ProcessoDetalheController:599-604`, `static/js/processo-detalhe.js:38-44`.

---

### A3 — MÉDIA · Notificação duplicada na tela de voto do avaliador

**Reproduzido.**

`layout.html:326` renderiza o poll global de 20 s do avaliador quando
`chatAtivoNestaTela != true`. Esse atributo existe justamente para não duplicar
som/toast em telas que já têm poll próprio, e é setado em
`SolicitanteController:321`, `SolicitacaoOnlineTriagemController:125` e
`ProcessoDetalheController:385` — **mas não em `AvaliadorController.votar()`**,
que é a única tela do avaliador com chat.

No HTML renderizado de `/avaliador/{processoId}` estão presentes, ao mesmo
tempo: `iniciarChatSolicitacao` (poll de 5 s) **e** `saur_nl_avaliador_msg` +
`var url = "\/avaliador\/nao-lidas-count"` (poll global de 20 s).

**Cenário concreto:** o médico está com a tela de voto aberta; o operador
responde. Dependendo de qual poll chega primeiro na janela de 5 s/20 s, ele
recebe **dois sons e dois toasts** com textos diferentes ("Nova mensagem da
equipe da Secretaria." e "Nova mensagem da equipe CET-RS sobre um dos seus
processos."). Pior: o toast global tem
`window.location.href = '/avaliador'` no clique — **tira o médico da tela de
voto que ele estava preenchendo**, com o risco de perder a justificativa já
digitada.

Correção: uma linha (`model.addAttribute("chatAtivoNestaTela", true)`).

**Arquivos:** `AvaliadorController:302-307`, `layout.html:326-354`.

---

### A4 — MÉDIA · A verificação de nome não protege nomes curtos

**Reproduzido.**

`VerificadorNomePaciente.tokensSignificativosNome` (`:89`) descarta todo token
com menos de 4 caracteres, para evitar falso-positivo de sigla. Consequência
medida, com paciente `"Ana Luz Silva"`:

```
mensagem: "paciente Ana Luz esta na fila"   -> LIVRE, termos=[]
paciente: "Joao Ze" / "o caso do Sr. Joao Ze" -> ALERTA, termos=[joao]
```

A primeira mensagem contém **dois dos três tokens** do nome do paciente e é
enviada ao avaliador **sem nenhum atrito**. Nomes como Ana, Luz, Ivo, Zé, Ari,
Léa, Rui, Elis ficam estruturalmente fora da proteção; um paciente cujo nome
inteiro seja curto (`"Ana Luz"`) não tem proteção nenhuma.

O `VerificadorNomePacienteTest` não cobre esse caso: seus nomes de teste têm
todos ≥4 caracteres.

**Arquivos:** `service/VerificadorNomePaciente.java:78-94`,
`service/VerificadorNomePacienteTest.java`.

---

### A5 — MÉDIA · A mesma verificação bloqueia mensagens legítimas (falso-positivo)

**Reproduzido.**

Qualquer token de `solicitanteEquipe` com ≥3 caracteres fora da stopword list
**bloqueia direto**, sem nível intermediário (`:63-65`). Com a equipe
`"Hospital de Clínicas de Porto Alegre"` os tokens são `clinicas`, `porto`,
`alegre`:

```
"o exame de clinicas nao abriu"              -> BLOQUEADO [clinicas]
"...favor desconsiderar o alegre"            -> BLOQUEADO [alegre]
"bom dia doutora, tudo certo?"               -> LIVRE
```

`clínicas` é vocabulário clínico corrente e `alegre` é metade do nome de uma
cidade — nenhum dos dois identifica a equipe sozinho. O operador é recusado sem
entender o porquê, e a mensagem de erro cita o termo, o que **ensina** qual
palavra evitar (efeito colateral menor, mas real).

Some-se a isso que `Nivel.ALERTA` (1 token do nome, previsto no design como
"pede confirmação") é **tratado igual a BLOQUEADO** no controller —
decisão consciente e documentada em `ProcessoDetalheController:991-1001`
(o `chat-solicitacao.js` não pode ser bifurcado e não tem fluxo de confirmação
em 2 passos). Registro aqui só para deixar claro que o `Nivel.ALERTA` do
serviço **não tem consumidor com semântica própria hoje**.

**Arquivos:** `service/VerificadorNomePaciente.java:39-76,96-108`,
`ProcessoDetalheController:1041-1048`.

---

### A6 — MÉDIA · O caminho de SUCESSO do "apagar mensagem" não tem nenhum teste

**Verificado por varredura + execução.**

Todas as 6 asserções de soft-delete da suíte conferem a **recusa**:

```
SolicitacaoOnlineTriagemSemTransacaoIntegrationTest:184,201  assertThat(aindaLa.isDeletada()).isFalse();
ProcessoDetalheSemTransacaoIntegrationTest:405,422           assertThat(aindaLa.isDeletada()).isFalse();
SolicitanteControllerSemTransacaoIntegrationTest:222         assertThat(aindaIntacta.isDeletada()).isFalse();
MensagemAvaliadorIntegrationTest:205                         assertThat(relida.isDeletada()).isFalse();
```

**Nenhum teste, em nenhum dos dois canais, apaga uma mensagem legitimamente e
confere que a linha sobreviveu com `texto = null`.** Confirmei por execução que
o comportamento está **correto hoje** (`deletada=true texto=null`), mas essa é
exatamente a classe de bug que chegou à produção em 2026-07-28
(`texto NOT NULL` → apagar mensagem estourava `DataIntegrityViolationException`
nas 3 telas) e que passou por **526 testes verdes**. A rede de proteção contra
a recaída não existe.

Agravante: `MensagemAvaliador` é uma cópia estrutural de `MensagemSolicitacao`;
um terceiro canal futuro copiado do mesmo molde não teria como saber que
`texto` **precisa** ser nullable, e a suíte não avisaria.

---

### A7 — MÉDIA · Nenhum limite de tamanho de mensagem no servidor

**Reproduzido.**

Os 4 campos têm `maxlength="2000"` — **só no HTML**, trivialmente burlável por
DevTools ou `curl`. Nenhuma entidade tem `@Size`, nenhum endpoint valida
tamanho. Enviei 200 000 caracteres pelo serviço real:

```
>>> A7 tamanho gravado=200000
```

A coluna é `TEXT`, então não há erro — a mensagem entra inteira. Consequências:
a caixa de entrada trunca em 80 chars (ok), mas o balão do chat renderiza o
texto inteiro com `white-space: pre-wrap` dentro de um `.chat-box` de
`max-height: 350px`, e a resposta do poll (a cada 5 s) passa a carregar
centenas de KB. Vale igualmente para os dois canais — e no canal do
solicitante o autor é um usuário **externo**.

**Arquivos:** os 4 templates de chat, `MensagemSolicitacaoService.enviar`,
`MensagemAvaliadorService.enviar`, e os endpoints `/mensagem/ajax`.

---

### A8 — MÉDIA-BAIXA · `podeEnviar` assimétrico no chat do solicitante

**Verificado por leitura.**

| Lado | Regra |
|---|---|
| Solicitante (`SolicitanteController:618`) | bloqueia se `CANCELADA` ou `PROCESSO_EXCLUIDO` |
| Operador na triagem (`SolicitacaoOnlineTriagemController:256`) | `true` fixo |
| Operador no processo (`ProcessoDetalheController:941`) | `true` fixo |

**Cenário concreto:** o solicitante cancela o pedido (direito garantido até a
decisão final). O formulário some da tela dele. O operador, nas duas telas
dele, continua com o campo habilitado e escreve *"recebemos, vamos verificar"*
— mensagem que o solicitante **vê mas não pode responder**, sem nenhuma
explicação na tela. O canal do avaliador não tem esse problema (é simétrico:
`!status.isFinalizado()` nos dois lados).

---

### A9 — BAIXA · O formulário escondido pelo `podeEnviar` nunca reaparece

`chat-solicitacao.js:196` só faz `form.classList.add('d-none')` — não existe
`remove`. Se `podeEnviar` voltar a `true` no meio da sessão, o formulário fica
escondido até um reload manual.

**Cenário concreto:** o operador está com `/processos/{id}` aberto quando o
processo é decidido; o formulário do chat com o avaliador some (correto). Um
ADMIN reabre o processo (`POST /processos/{id}/reabrir`, status volta a
`Enviado`). O poll passa a devolver `podeEnviar: true`, mas o campo continua
invisível — o operador conclui que o chat "quebrou".

---

### A10 — BAIXA · O badge da navbar não consegue aparecer durante a sessão

**Reproduzido.**

`layout.html:174` renderiza o `<span id="navBadgeMsgAvaliadorNaoLida">` sob
`th:if="${mensagensAvaliadorNaoLidasOperador > 0}"`; o poll global atualiza o
badge via `document.getElementById(...)` (`:374`). Medido:

```
>>> A3 badgeElementoComZero=false badgeElementoComUm=true
```

Ou seja: quando a página carrega com zero não lidas — o caso normal —, o
elemento **não existe** e o `getElementById` devolve `null`. Chegando uma
mensagem, o operador recebe o toast (uma vez, e se estiver olhando a tela) mas
**o badge só aparece depois de navegar para outra página**. Vale igual para
`navBadgeMsgNaoLida` (canal do solicitante, `:163`).

Relacionado: **o AVALIADOR não tem badge de mensagem nenhum.** O sino da navbar
(`:188-195`) conta **pareceres pendentes** (`pendentesAvaliador`), não
mensagens. Se ele perder o toast, não há indicador persistente algum.

---

### A11 — BAIXA · `assinatura()` concatena sem separador (colisão possível)

`chat-solicitacao.js:123-127`:

```js
return mensagens.map(function (m) {
    return [m.id, m.deletada ? 1 : 0, m.lida ? 1 : 0, m.podeApagar ? 1 : 0, m.texto].join('');
}).join('');
```

Sem delimitador, estados distintos podem gerar a mesma string: `id=1` +
flags `000` + texto `"0023"` e `id=10` + flags `000` + texto `"023"` produzem
ambos `"10000023"`. O efeito é o chat **não re-renderizar** uma mudança real
(uma mensagem apagada continuar aparecendo até o próximo poll que mude a
assinatura). Probabilidade baixíssima (exige texto numérico), mas a correção é
um caractere.

---

### A12 — BAIXA · Acessibilidade: mensagens novas são silenciosas para leitor de tela

**Verificado por leitura do HTML** (não testei com leitor de tela real).

Nenhum dos 5 contêineres de chat (`#chatBox`, `#chatBoxAval`,
`#chatBoxAval{id}`) tem `aria-live`, `role="log"` ou `aria-atomic`. O conteúdo
é substituído por `innerHTML` a cada mudança (`:137`) sem nenhum anúncio: para
quem usa leitor de tela, **mensagens novas simplesmente não existem** — o
único aviso é o `mostrarToast`, que também não está numa região `aria-live`
declarada no chat.

Secundário: o botão "Conversa" (`processos/detalhe.html:881`) tem
`data-bs-target` e `aria-expanded`, mas **não tem `aria-controls`** apontando
para `chatAval{id}` — os demais collapses do sistema seguem o mesmo padrão
incompleto, e `AcessibilidadeEstruturaTest` só valida referências
`aria-labelledby`/`aria-controls` **existentes**, não a ausência delas.

---

### A13 — BAIXA · `marcarComoLidas` varre a thread em Java a cada poll, e não há índice

Os dois serviços implementam `marcarComoLidas` carregando **a thread inteira**
e filtrando em Java (`MensagemSolicitacaoService:42-54`,
`MensagemAvaliadorService:56-66`). Isso roda a **cada 5 segundos** por
instância de chat aberta, dentro de uma transação de escrita, mesmo quando não
há nada para marcar.

Some-se que **nenhuma das duas tabelas declara índice**: o único `@Index` do
projeto inteiro está em `LogAuditoria`. O Postgres **não** cria índice
automático em coluna de FK, então `findByProcessoIdAndMembroIdOrderByDataEnvioAsc`
e `countByLidaFalseAndRemetente` fazem varredura sequencial. O índice composto
`(processo_id, membro_id, data_envio)` era item explícito do design original
(`RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md` §6.1) e não foi implementado.

Irrelevante no volume de hoje; vira relevante numa VM de 1 vCPU com threads
longas.

---

### A14 — BAIXA · A caixa de entrada carrega a tabela inteira de mensagens

`MensagemAvaliadorRepository.findAllComProcessoEMembroOrderByDataEnvioDesc()`
traz **todas** as mensagens de **todos** os processos, com fetch join, e
`listarCaixaDeEntradaOperador` agrupa em Java. Sem `LIMIT`, sem paginação, sem
recorte por período. É a única tela do sistema cujo custo **só cresce** —
mesmo problema que motivou a paginação do Arquivo na Fase E da UI do operador.
Está documentado como aceito ("sem paginação nesta leva"); registro para que a
dívida não se perca.

---

### A15 — BAIXA · Apagar mensagem não gera registro de auditoria

Os 4 endpoints de apagar (2 por canal, clássico e AJAX) chamam
`servico.apagar(...)` e não chamam `AuditoriaService.registrar` em nenhum
caminho — ao contrário do enviar, que registra nos dois canais. O dado não se
perde (a linha permanece com `remetenteId` e `deletadaEm`), mas **não aparece
em `/auditoria`**, que é onde o ADMIN olha. Numa conversa que pode conter
pedido de informação de um médico, "quem apagou o quê e quando" é justamente o
que se vai querer reconstruir.

---

## 6. Segurança, notificação e UX — o que está CERTO

Registro explícito do que auditei e **não** virou achado, para uma sessão
futura não reinvestigar:

**Segurança / IDOR — sem falhas.**
- `AvaliadorController`: toda rota de chat passa por `resolverMembro` +
  `resolverParecerDoMembro` (`parecerRepo.findByProcessoIdAndMembroIdComProcesso`),
  403 se o médico não avalia aquele processo. Nunca resolve thread por id solto.
- `ProcessoDetalheController` (lado operador): `parecerRepo.findByProcessoIdAndMembroId`
  antes de listar **e** antes de enviar; autorização por role é o design
  pretendido do sistema (confirmado na vistoria de 2026-07-28).
- **Apagar é seguro nos dois canais**: `apagar` exige `remetenteId` **e**
  `remetente` idênticos aos da mensagem, então os `@PathVariable` de
  processo/membro serem ignorados no método de apagar **não** abre IDOR —
  ninguém apaga mensagem de outro, nem outro operador. Coberto por teste.
- CSRF: os POSTs mandam o header lido das metatags (`chat-solicitacao.js:17-20`);
  os E2E enviam e apagam mensagem de verdade pelos dois lados.
- XSS: todo conteúdo dinâmico passa por `escapeHtml` (`:26-30`) antes de entrar
  no `innerHTML`.

**Imparcialidade e privacidade — sem vazamento.**
- Auditoria **nunca** grava o texto da mensagem, em nenhum dos 6 pontos de
  registro. `MENSAGEM_SOLICITANTE_ENVIADA` usa `Iniciais.de(...)`;
  `MENSAGEM_AVALIADOR_ENVIADA`/`MENSAGEM_OPERADOR_AVALIADOR_ENVIADA` usam
  número do processo + rótulo do médico + username do operador. Confirmado por
  leitura dos 6 call-sites, não presumido.
- O template nunca recebe entidade `Processo`/`Parecer`/mensagem inteira: só
  `MensagemChatView`/`ProcessoVotoView`/`ParecerVotoView`.
- A caixa de entrada mostra número do processo + rótulo do avaliador, nunca
  nome de paciente.
- O aviso de composição do operador está presente e cita as iniciais reais
  (`processos/detalhe.html:910-915`), conforme §8.2 do relatório original.
- O avaliador vê o outro lado como "Equipe CET-RS", nunca o nome do operador.

**Notificação — o desenho está correto.** Pausa em `visibilitychange` nos 5
polls; `sessionStorage` com o primeiro ciclo só definindo a base (nunca
notifica sozinho ao abrir); detecção de mensagem nova por comparação de ids
entre ciclos, com `idsRecebidosConhecidos = null` até o 1º poll.
**Múltiplas abas do mesmo usuário:** cada aba tem seu `setInterval` e seu
`sessionStorage` (que é por aba), então cada aba visível notifica uma vez —
comportamento aceitável e não um bug; a aba em background não toca som.

**Polling vs. WebSocket/SSE — manter polling.** Reafirmo a conclusão do
`RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md` §8, agora com o dado de infra
confirmado na vistoria de 2026-08-03: a VM é **Always Free, 1 OCPU / 956 MB,
compartilhada com outras 3 aplicações**, com o SAUR em `-Xmx512m`. WebSocket
exigiria conexão persistente por usuário, configuração de proxy no nginx
(`Upgrade`/`Connection`), tratamento de reconexão e um caminho novo de
autenticação/autorização fora do `SecurityConfig` atual — para uma equipe de
poucos operadores e 3 médicos por processo. SSE seria mais barato que
WebSocket, mas ainda mantém uma conexão HTTP aberta por usuário e por aba, o
que num Tomcat com pool default nessa VM é pior que 3 requisições curtas por
minuto. **O polling atual é a escolha certa e não deve ser trocado.**

**UX que já está boa:** estado vazio explícito nos 5 chats ("Nenhuma mensagem
ainda."), "mensagem apagada" distinguindo autor ("Você apagou esta mensagem." ×
"Mensagem apagada pelo avaliador."), confirmação em modal antes de apagar,
check simples/duplo de leitura, timestamps relativos reavaliados a cada poll,
scroll preservado para quem está lendo histórico, `.chat-box` escondido na
impressão.

**Não avaliado a fundo (limite honesto):** teclado virtual em celular. O campo
é um `<input type="text">` no fim de um card, dentro de um `.chat-box` de
`max-height: 350px`; não há `scrollIntoView` no `focus`. Em celular, o teclado
pode cobrir o campo em telas longas (`processos/detalhe.html` tem 1.296
linhas). **Só um teste em aparelho real confirma** — por isso não virou achado
numerado, e sim o item S8 da §7, condicionado a verificação.

---

## 7. Sugestões de melhoria

Cada uma com: problema, proposta, arquivos, risco e se depende de decisão de
produto. **"Decisão do usuário = Não"** significa que é correção de defeito
com comportamento pretendido já documentado; **"Sim"** significa que a
mudança altera comportamento visível sem que exista uma regra escrita que a
determine.

---

### S1 — Só marcar como lida a conversa realmente visível *(resolve A1)*

**Problema:** abrir `/processos/{id}` em qualquer aba zera o sinal de mensagem
não lida do avaliador para todos os operadores.

**Proposta:** desacoplar "renderizar o chat" de "marcar como lida", nas duas
pontas, sem tocar em `chat-solicitacao.js` (item 12.7 do relatório original —
não bifurcar o módulo):
1. Em `processos/detalhe.html`, trocar `if (elCollapse.classList.contains('show')) iniciarThread();`
   por uma condição que exija **também** que o painel `pane-respostas` esteja
   ativo; e registrar `shown.bs.tab` do botão `tab-respostas` para iniciar as
   threads visíveis quando o operador entrar na aba. O `shown.bs.collapse` já
   existente continua cobrindo a expansão manual.
2. Reforço no servidor (defesa em profundidade, porque o item 1 é só JS):
   `GET .../avaliador/{membroId}/mensagens` passa a aceitar um parâmetro
   explícito (ex.: `?marcarLida=true`) e só marca quando ele vier; o JS o
   envia porque a instância só é criada quando a thread está de fato visível.
   Sem o parâmetro, o endpoint devolve as mensagens sem marcar.

**Arquivos:** `processos/detalhe.html` (bloco `<script>` final),
`ProcessoDetalheController.mensagensAvaliadorJson`.
**Risco:** **médio** — mexe na tela mais complexa do sistema; exige conferir
que a conversa continua sendo marcada como lida quando o operador *de fato*
abre a aba. Cobrir com E2E (o Playwright já sabe navegar entre abas).
**Decisão do usuário:** **Não** — o comportamento pretendido já está escrito
no `layout.html:240-241` ("abrir a conversa de fato continua sendo o que marca
como lida").

---

### S2 — Fazer o link da caixa de entrada abrir a aba Respostas *(resolve A2)*

**Problema:** o botão "Abrir processo" leva à aba errada.

**Proposta:** `ProcessoDetalheController.detalhe` passa a aceitar
`@RequestParam(required = false) String aba` e, quando vier um `paneId`
válido **da lista de passos do wizard** (nunca uma string arbitrária vinda da
URL), usá-lo em vez do cálculo automático. A caixa de entrada passa a apontar
para `?aba=pane-respostas`. Trocar fragmento por query param evita depender de
`location.hash` em JS e deixa o destino testável por `MockMvc`.

Bônus barato: com o operador já caindo na aba certa, S1 e S2 se completam — a
mensagem é marcada como lida exatamente quando ele chega para lê-la.

**Arquivos:** `ProcessoDetalheController:599-604`,
`processos/mensagens-avaliadores-lista.html:42`.
**Risco:** **baixo** — aditivo; sem o parâmetro nada muda.
**Decisão do usuário:** **Não** — o link já existe com essa intenção declarada.

---

### S3 — `chatAtivoNestaTela` na tela de voto *(resolve A3)*

**Proposta:** `model.addAttribute("chatAtivoNestaTela", true)` em
`AvaliadorController.votar()`, exatamente como nas outras 3 telas de chat.
Acrescentar um teste que renderiza `/avaliador/{id}` e afirma que o bloco de
poll global **não** aparece (é o tipo de coisa que `@WebMvcTest` de status não
pega — só a inspeção do HTML renderizado).

**Arquivos:** `AvaliadorController.votar`, teste novo.
**Risco:** **baixo**.
**Decisão do usuário:** **Não** — é o contrato já documentado do atributo.

---

### S4 — Calibrar o `VerificadorNomePaciente` *(resolve A4 e A5)*

**Problema:** falha nos dois sentidos — deixa passar nome curto e bloqueia
palavra comum.

**Proposta (três ajustes independentes, todos testáveis por unidade):**
1. **Nome curto:** baixar o corte de 4 para 3 caracteres **e**, quando o nome
   inteiro do paciente tiver ≤2 tokens significativos, tratar **1 token
   presente como BLOQUEADO** (hoje é ALERTA→bloqueado, mas o token sequer é
   gerado). Alternativa mais forte: considerar sempre todos os tokens
   não-conectivos, independentemente do tamanho, e compensar o falso-positivo
   pela regra de 2 tokens.
2. **Equipe:** trocar o bloqueio por token isolado por (a) exigir **≥2 tokens**
   da equipe, ou (b) manter 1 token mas só para tokens **distintivos** —
   ampliando `STOPWORDS_EQUIPE` com termos genéricos e topônimos
   (`clinicas`, `porto`, `alegre`, `santa`, `casa`, `geral`, `universitario`,
   `federal`, `municipal`, `estadual`, `nefrologia`, `transplante`). Recomendo
   **(a) + ampliar a stoplist**, e reaproveitar o mapa `ALIASES` de
   `ConflitoEquipeMatcher`, que já resolve sigla × nome por extenso.
3. **Mensagem de erro:** parar de citar o termo encontrado; dizer só *"a
   mensagem parece citar o paciente ou a equipe solicitante"* + a orientação
   das iniciais.

Ampliar `VerificadorNomePacienteTest` com: nome inteiro curto, 2 de 3 tokens,
token de equipe genérico, topônimo, e acento/maiúscula em cada um.

**Arquivos:** `service/VerificadorNomePaciente.java`,
`service/VerificadorNomePacienteTest.java`, `ProcessoDetalheController:1041-1048`.
**Risco:** **médio** — mexe no controle de imparcialidade mais sensível do
sistema; calibrar de menos deixa passar, de mais trava o operador.
**Decisão do usuário:** **SIM.** Item 1 endurece (mais bloqueios), item 2
afrouxa (menos bloqueios) — a troca entre "recusar demais" e "deixar passar"
é decisão de produto, não técnica. Ver Q3 da §7 abaixo.

---

### S5 — Teste do caminho de sucesso do soft delete, nos dois canais *(resolve A6)*

**Proposta:** dois testes `@SpringBootTest` com H2 real e serviço real (sem
mock, conforme a convenção do projeto para escrita irreversível): o autor apaga
a própria mensagem, e o teste **relê do banco** e afirma
`deletada == true`, `texto == null`, `deletadaEm != null`, `remetenteId`
preservado, e que a linha **não sumiu** (`count` inalterado). Um para
`MensagemSolicitacao`, um para `MensagemAvaliador`.

**Arquivos:** `MensagemAvaliadorIntegrationTest` (acrescentar) + um teste novo
para o canal do solicitante.
**Risco:** **baixo** — só teste.
**Decisão do usuário:** **Não**.

---

### S6 — Limite de tamanho de mensagem no servidor *(resolve A7)*

**Proposta:** validar no servidor o mesmo 2000 já anunciado no `maxlength` dos
4 formulários, nos 4 endpoints de envio (2 por canal), devolvendo o mesmo
formato de erro JSON (`400` + `{"erro": "..."}`) que o `chat-solicitacao.js` já
sabe exibir via `mostrarToast`. Acrescentar `@Column(length = 2000)` seria
inócuo com `columnDefinition = "TEXT"` e **perigoso** de trocar (a coluna já
existe em produção) — a validação deve ficar na camada web/serviço, não no DDL.

**Arquivos:** `SolicitanteController`, `SolicitacaoOnlineTriagemController`,
`ProcessoDetalheController` (2 endpoints), `AvaliadorController`.
**Risco:** **baixo**.
**Decisão do usuário:** **Não** — só torna real o limite que a UI já promete.

---

### S7 — Simetria e reexibição do formulário *(resolve A8 e A9)*

**Proposta:**
1. Os dois endpoints de poll do operador no canal do solicitante passam a
   calcular `podeEnviar` com a **mesma** regra do lado do solicitante
   (`status != CANCELADA && status != PROCESSO_EXCLUIDO`), com o texto de
   somente-leitura explicando que o pedido foi cancelado.
2. `chat-solicitacao.js:196` passa a usar
   `form.classList.toggle('d-none', data.podeEnviar === false)`, de modo que o
   formulário volte quando o processo for reaberto.

**Arquivos:** `ProcessoDetalheController:941`,
`SolicitacaoOnlineTriagemController:256`, `static/js/chat-solicitacao.js:196`.
**Risco:** **baixo-médio** — o item 1 **remove** uma capacidade que o operador
tem hoje (escrever em conversa cancelada).
**Decisão do usuário:** **SIM para o item 1** (é possível que a equipe use
justamente isso para explicar o cancelamento ao solicitante — ver Q4).
**Não para o item 2**, que é correção pura.

---

### S8 — Higiene de notificação e acessibilidade *(resolve A10, A11, A12)*

**Proposta (lote pequeno, sem regra de negócio):**
1. Renderizar os dois badges da navbar **sempre**, com `d-none` quando zero, em
   vez de `th:if` — assim o poll consegue exibi-los ao vivo.
2. Dar ao AVALIADOR um badge de **mensagens** próprio na navbar (hoje o sino é
   de pareceres pendentes), alimentado por `contarNaoLidasParaMembro`, no mesmo
   padrão dos outros dois.
3. Delimitador em `assinatura()` (`.join('|')` interno e externo).
4. `role="log" aria-live="polite" aria-relevant="additions"` nos 5 contêineres
   de chat, e `aria-controls` no botão "Conversa".
5. **Só se confirmado em aparelho real:** `scrollIntoView({block:'center'})` no
   `focus` do campo em telas estreitas, para o teclado virtual não cobrir o
   campo.

**Arquivos:** `layout.html`, `static/js/chat-solicitacao.js`, os 4 templates de
chat, `GlobalModelAdvice`.
**Risco:** **baixo** (itens 1–4). O item 5 depende de verificação em celular.
**Decisão do usuário:** **Não** para 1–4. O item 2 é aditivo mas visível ao
médico — vale mencionar ao dono do produto, sem bloquear.

---

### S9 — Auditoria de exclusão de mensagem *(resolve A15)*

**Proposta:** `MENSAGEM_APAGADA` (canal do solicitante) e
`MENSAGEM_AVALIADOR_APAGADA` (canal do avaliador), com **id da mensagem +
número do processo/solicitação + quem apagou**, e — repetindo a regra que já
custou duas correções ao projeto — **nunca o texto** e **nunca o nome completo
do paciente** (iniciais, se precisar identificar).

**Arquivos:** os 4 endpoints de apagar.
**Risco:** **baixo**.
**Decisão do usuário:** **Não**.

---

### S10 — Índices e `marcarComoLidas` em lote *(resolve A13)*

**Proposta:**
1. `@Table(indexes = ...)` em `(processo_id, membro_id, data_envio)` e
   `(lida, remetente)` para `mensagem_avaliador`; `(solicitacao_online_id,
   data_envio)` e `(lida, remetente)` para `mensagem_solicitacao`.
   **Atenção operacional:** `ddl-auto: update` **cria** índice novo sem
   problema (ao contrário de CHECK constraint e de coluna não-nula), mas
   conferir na VM após o deploy, seguindo a prática do projeto de nunca
   presumir o que o `update` fez.
2. Trocar os dois `marcarComoLidas` por um `@Modifying` de update em lote
   (mesmo padrão de `ParecerRepository.registrarUltimoLembrete`), eliminando o
   carregamento da thread inteira a cada 5 s.

**Arquivos:** as 2 entidades, os 2 repositórios, os 2 serviços.
**Risco:** **baixo-médio** — o item 2 muda escrita; exige teste de integração
que confirme que só as mensagens do outro lado são marcadas.
**Decisão do usuário:** **Não**.

---

### S11 — Paginar/recortar a caixa de entrada *(resolve A14)*

**Proposta:** trocar `findAll...` por uma consulta que traga só a última
mensagem por thread (ou limitar por período/`Pageable`, seguindo
`ArquivoController`). **Não fazer agora** se o volume seguir baixo — registrar
como dívida com gatilho explícito: reavaliar quando `mensagem_avaliador`
passar de ~2.000 linhas.

**Risco:** **baixo**. **Decisão do usuário:** **Não** (é dívida, não defeito).

---

### O que NÃO fazer

1. **Não** unificar `MensagemSolicitacao` e `MensagemAvaliador` (§4.1).
2. **Não** acrescentar valor ao enum `RemetenteMensagem` — CHECK congelada em
   produção.
3. **Não** trocar polling por WebSocket/SSE (§6).
4. **Não** bifurcar nem reescrever `chat-solicitacao.js`: se faltar
   comportamento, acrescentar parâmetro ao `cfg`.
5. **Não** mover o chat do solicitante da barra lateral esquerda de
   `/processos/{id}` — REGRA fixa do produto.
6. **Não** registrar texto de mensagem em auditoria nem em log de aplicação.
7. **Não** aplicar `VerificadorNomePaciente` ao lado do avaliador (ele não
   conhece o nome; só geraria ruído).
8. **Não** incluir as conversas no dossiê ZIP nem no Relatório Final (Q7 do
   relatório original: exporia a identidade do avaliador à equipe solicitante).

---

### Perguntas de produto (decisões do usuário)

- **Q1 — Com que frequência cada canal é usado hoje?** Não é levantável pelo
  código. Se o canal do avaliador for pouco usado, S1/S2 caem de prioridade;
  se for usado toda semana, são urgentes (é perda de trabalho real).
- **Q2 — A1 é percebido na prática?** Alguém já reclamou de "mandei mensagem e
  ninguém respondeu"? Uma resposta afirmativa confirma o achado em campo.
- **Q3 (S4) — calibragem da verificação de nome.** Prefere **errar bloqueando
  demais** (operador reescreve a frase) ou **errar deixando passar** (risco de
  imparcialidade)? Recomendação: endurecer o nome (item 1) e afrouxar a equipe
  (item 2) — os dois juntos, não um só.
- **Q4 (S7.1) — o operador deve poder escrever ao solicitante depois do
  cancelamento?** Se sim, o certo é o **inverso**: liberar também o lado do
  solicitante, em vez de bloquear o operador.
- **Q5 (S8.2) — dar ao avaliador um badge de mensagens na navbar?** Recomendo
  sim; hoje ele só tem o toast, que passa.

---

## 8. Plano de trabalho — fases executáveis

Uma fase por PR, na ordem recomendada, cada uma com escopo fechado, testável
isoladamente e com a suíte completa verde antes do merge (base atual:
**908 testes**). Nenhuma fase toca regra de decisão, `ProcessoValidator`,
`ProcessoService`, imparcialidade do PDF ou o fluxo de 5 passos.

| Fase | Conteúdo | Achados | Risco | Depende de decisão? | Revisão humana antes de produção? | Status |
|---|---|---|---|---|---|---|
| **F1** | **S3** (`chatAtivoNestaTela` na tela de voto) + **S5** (testes de soft delete nos 2 canais) + **S6** (limite de 2000 no servidor) + **S9** (auditoria de exclusão) | A3, A6, A7, A15 | **Baixo** | Não | Não | **MESCLADA** (2026-08-10, ver CLAUDE.md) |
| **F2** | **S2** (`?aba=` + link da caixa de entrada) + **S1** (marcar como lida só com a conversa visível) | **A1, A2** | **Médio** | Não | **Sim** — mexe em `processos/detalhe.html` | **MESCLADA** (2026-08-10, ver CLAUDE.md) |
| **F3** | **S4** (calibragem do `VerificadorNomePaciente` + testes de borda) | A4, A5 | **Médio** | **SIM (Q3)** | **Sim** | **MESCLADA** (2026-08-10, ver CLAUDE.md — desvio deliberado da stoplist sugerida, documentado) |
| **F4** | **S8** (badges sempre no DOM, badge de mensagem do avaliador, `assinatura()`, `aria-live`/`aria-controls`) + **S7.2** (`toggle` do formulário) | A9, A10, A11, A12 | **Baixo** | Não (Q5 é só confirmação) | Sim (leve) | Pendente |
| **F5** | **S7.1** (simetria de `podeEnviar` no canal do solicitante) | A8 | **Baixo-médio** | **SIM (Q4)** | Sim (leve) | Pendente |
| **F6** | **S10** (índices + `marcarComoLidas` em lote) | A13 | **Baixo-médio** | Não | Não | **Implementada, PR aberto** (2026-08-10, `feat/chat-f6-indices-lote`, ver CLAUDE.md — aguardando o agente principal coordenar o merge com F2-F5) |
| **F7** *(adiar)* | **S11** (recorte da caixa de entrada) | A14 | Baixo | Não | Não | Adiada de propósito |

**Por que F2 é a fase de maior risco:** mexe em `processos/detalhe.html`
(1.296 linhas, wizard + 4 abas + timeline + o chat do solicitante que carrega a
REGRA fixa de posição). O precedente é concreto: um lote anterior moveu aquele
card sem revisão visual e o dono do produto reportou como bug. PR isolado, sem
merge automático.

**Sugestão de agrupamento:** F1 pode ir sozinha e cedo (é toda de baixo risco e
fecha 4 achados). **F2 deve ir inteira** — entregar S2 sem S1 leva o operador à
aba certa mas a mensagem já foi marcada como lida no caminho; entregar S1 sem
S2 mantém a conversa não lida mas o operador continua caindo na aba errada.
As duas juntas fecham o furo.

**Testes exigidos pela convenção do projeto:**
- F1: `@SpringBootTest` com H2 e serviço real para o soft delete (relendo do
  banco, campo a campo) e para o limite de tamanho; teste que renderiza
  `/avaliador/{id}` e afirma a **ausência** do bloco de poll global.
- F2: teste que renderiza `/processos/{id}?aba=pane-respostas` e confere a aba
  ativa; teste de integração que confirma que um `GET` do detalhe em **outra**
  aba **não** marca a mensagem do avaliador como lida (relendo `lida` do
  banco). Desejável: passo no E2E navegando até a aba Respostas.
- F3: `VerificadorNomePacienteTest` ampliado — nome curto inteiro, 2 de 3
  tokens, token genérico de equipe, topônimo, acento e maiúscula.
- Todas as fases com template: `AcessibilidadeEstruturaTest` e
  `IdsDuplicadosTest` verdes sem ajuste; `ChatVisualVerificacaoIT` verde
  (roda em ~57 s, headless).

---

## Anexo A — comandos de verificação usados

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# Contagem-base da suite (908 testes, 0 falhas)
mvn test

# E2E dedicado dos dois chats, navegador real headless (1 teste, 0 falhas)
mvn verify -Pe2e -Dsaur.e2e.headed=false -Dit.test=ChatVisualVerificacaoIT

# Aplicacao de verdade (H2/dev) para navegacao manual por curl
mvn -DskipTests package
java -jar target/saur-0.0.1-SNAPSHOT.jar --server.port=3011 --spring.profiles.active=dev

# Inventario dos dois canais
wc -l src/main/java/br/gov/saude/sgpur/domain/Mensagem*.java \
      src/main/java/br/gov/saude/sgpur/service/Mensagem*Service.java \
      src/main/java/br/gov/saude/sgpur/service/VerificadorNomePaciente.java \
      src/main/resources/static/js/chat-solicitacao.js

# Auditoria nunca grava texto de mensagem (6 call-sites)
grep -rn "MENSAGEM_" src/main/java/

# Quem seta chatAtivoNestaTela (achado A3: AvaliadorController nao esta na lista)
grep -rn "chatAtivoNestaTela" src/main/java/ src/main/resources/templates/

# Ancora morta da caixa de entrada (achado A2): zero resultados
grep -rn "location.hash" src/main/resources/static/js/ src/main/resources/templates/

# Indices declarados no dominio (achado A13): so LogAuditoria
grep -rn "@Index\|indexes" src/main/java/br/gov/saude/sgpur/domain/

# Soft delete: todas as assercoes existentes so cobrem a RECUSA (achado A6)
grep -rn "isDeletada()" src/test/java/
```

## Anexo B — saída real do teste temporário de verificação

Teste `@SpringBootTest` + H2 + `MockMvc` escrito só para esta vistoria e
**apagado ao final** (não faz parte do repositório). Processo `77/2026`,
paciente `Ana Luz Silva`, equipe `Hospital de Clinicas de Porto Alegre`,
avaliador `HCPA - Ana Avaliadora` com uma mensagem não lida.

```
>>> A1 pollLocal=true pollGlobal=false temNavbar=true      <- "false" era falso-negativo do
>>> A1 contem[saur_nl_avaliador_msg]=true                     meu grep: a URL sai JS-escapada
>>> A1 (HTML) var url = "\/avaliador\/nao-lidas-count";     <- poll GLOBAL presente
>>> A1 (HTML) iniciarChatSolicitacao({...})                 <- poll LOCAL presente  => A3

>>> A2 paneRespostasAtiva=false
>>> A2 divThread: <div class="collapse chat-avaliador-thread show" id="chatAval1"
>>> A2 (HTML) class="tab-pane fade show active" id="pane-envio"
>>> A2 (HTML) class="tab-pane fade"             id="pane-respostas"   => A1

>>> A3 badgeElementoComZero=false badgeElementoComUm=true             => A10

>>> A4 'Ana Luz' -> LIVRE termos=[]                                   => A4
>>> A4 'Joao Ze' -> ALERTA termos=[joao]

>>> A5 'clinicas' -> BLOQUEADO [clinicas]                             => A5
>>> A5 'alegre'   -> BLOQUEADO [alegre]
>>> A5 neutro     -> LIVRE

>>> A6 deletada=true texto=null            <- comportamento correto, mas sem teste => A6
>>> A7 tamanho gravado=200000              <- 200 mil caracteres aceitos           => A7
```

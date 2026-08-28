# Relatório de UI/UX — Clareza, densidade e conclusão de etapa (área do operador)

**Data:** 2026-08-05 · **Tipo:** diagnóstico + plano (nenhum código alterado)
**Gatilho:** feedback direto do dono do produto olhando `/processos/{id}` em
produção, aba **Envio**:

> *"a pagina a acima e o paso 1 recebimento e tem todo aqueles textos e tal,
> gostaria de deixar menos poluido visualmente, as informacoes deve ser
> objetivas e consisas. Neste caso temos dois passos a ser concluido para passar
> para a outro etapada, essa conclusao de passo 1 e 2 tbm deve ser bem visutal"*

**Escopo:** as telas do operador/administrador, com foco em **densidade de
texto, hierarquia visual, clareza de conclusão de etapa e redundância**.
**Complementar a** (e deliberadamente **sem repetir**):
- `RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md` — acessibilidade estrutural,
  contraste, breakpoints, paginação, fonte local, alinhamento de tabela.
  **Fases A–E executadas** (PRs #8–#13).
- `RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md` — polls, toast, atalho de
  teclado, N+1 do contador, aviso ao sair. Parcialmente executado.
- `RELATORIO-UI-SOLICITANTE-AVALIADOR-2026-08.md` — os dois Portais externos.

> **Este documento é diagnóstico + plano.** Nenhuma linha de código foi
> alterada para produzi-lo. Todo achado foi verificado lendo o template/serviço
> real; todo número é reproduzível pelos comandos do **Anexo A**.

---

## 1. Sumário executivo

A queixa do usuário está correta e tem uma causa raiz identificável, não é
questão de gosto: a tela de detalhe do processo acumulou, ao longo de cinco
sessões de correção, **22 caixas de alerta e 1.381 palavras visíveis**, sendo
**435 só na aba Envio** — onde a instrução real ("anexe um PDF anonimizado" e
"clique para enviar") cabe em duas linhas. Cada aviso nasceu de um incidente
real e faz sentido isolado; empilhados, formam um muro de texto em que a
informação de segurança mais importante (o nome do paciente pode estar **dentro**
do PDF) é o **quarto parágrafo** de um bloco cinza, com menos destaque visual do
que a explicação de como o sistema funde arquivos.

O ponto mais grave, e a resposta direta à segunda metade do pedido
("essa conclusão de passo 1 e 2 também deve ser bem visual"): **a cor mais
visível de cada sub-passo não codifica o estado dele.** A barra lateral colorida
é **fixa** — azul no sub-passo 1, verde no sub-passo 2 — independentemente de
estarem concluídos ou pendentes. O operador vê "verde" num passo que ainda não
foi feito, e o estado real só é comunicado por uma palavra pequena ("Concluído"
/ "Pendente") alinhada à direita do título. Isso convive com **três outros**
indicadores de progresso na mesma tela (wizard horizontal, timeline vertical,
barra de %), nenhum deles descendo ao nível do sub-passo.

A auditoria também encontrou **três trechos factualmente errados** ainda na
tela — texto que descreve regras que não existem mais no código (exigência de
anexo comprobatório para decidir, edição de datas na Finalização, numeração
errada de aba) — e **uma ação irreversível sem confirmação**: "Registrar envio e
enviar convites" dispara e-mails reais a 3 médicos com um clique, sem modal,
enquanto ações menos custosas já ganharam essa proteção.

**Nada de segurança é removido neste plano.** A regra de anonimização, a de
imparcialidade e a trava de documentos do Portal permanecem — passam a ser a
frase **mais curta e mais visível** de cada bloco, em vez do parágrafo mais
longo e mais ignorado. O §7 traz o mapa "onde cada informação de segurança
passa a viver".

---

## 2. Princípios de design adotados (a régua)

Estes seis critérios foram aplicados a cada tela. Toda proposta abaixo cita
qual princípio a motiva.

**P1 — Uma instrução por ação, no ponto da ação.**
Texto que descreve *o que o sistema fará depois do clique* pertence ao modal de
confirmação (lido no momento da decisão), não à página (lida — ou não — cinco
minutos antes).

**P2 — Estado por cor, não por palavra.**
Se um bloco tem estado (concluído / pendente / bloqueado), a **cor mais visível
do bloco** codifica esse estado. Cor fixa decorativa que não muda com o estado é
ruído ativo: ensina o olho a ignorar a cor.

**P3 — Um assunto, um lugar.**
Regra repetida em dois pontos da mesma tela é regra que ninguém lê nos dois. A
segunda ocorrência não reforça — ela dilui e ainda cria risco de divergirem
quando uma for atualizada.

**P4 — Aviso permanente vira aviso condicional.**
Se o aviso só se aplica a um caso (ex.: "há documentos do Portal bloqueados
acima"), ele só deve renderizar nesse caso. Aviso sempre presente vira parte do
papel de parede.

**P5 — Segurança encurta e sobe; nunca sai.**
Reduzir verbosidade **não** é reduzir informação de segurança. O núcleo
acionável do aviso ("apague o nome de dentro do PDF") sobe e ganha destaque; o
"porquê" e o "como o sistema funciona" descem para um `<details>` expansível.

**P6 — Peso visual proporcional à consequência.**
A ação irreversível é o maior botão da seção. A ação trivial (copiar texto) é a
menor. Hoje há pelo menos dois lugares onde isso está invertido.

---

## 3. Método e medições

Leitura integral de `processos/detalhe.html` (1.247 linhas), `dashboard.html`,
`processos/lista.html`, `processos/editar.html`, `processos/form.html`,
`arquivo/lista.html`, `membros/lista.html`, `membros/form.html`,
`controle-urgencias/lista.html`, `controle-urgencias/form.html`,
`usuarios/lista.html`, `usuarios/form.html`,
`processos/solicitacoes-online-lista.html`,
`processos/solicitacoes-online-detalhe.html`, mais `layout.html`, `app.css`,
`FluxoProcessoService`, `ProcessoValidator`, `PassoWizard`/`EtapaFluxo` e os
Page Objects do E2E.

Contagem de palavras = apenas nós de texto (comentários HTML e atributos
excluídos), tokens contendo ao menos uma letra. Reproduzível pelo Anexo A.

### 3.1 Densidade medida — `processos/detalhe.html`

| Região | Palavras visíveis | `alert` | `<form>` | `<button>` |
|---|---:|---:|---:|---:|
| Aba 1 · Recebimento | 40 | 1 | 0 | 1 |
| **Aba 2 · Envio** | **435** | **5** | **5** | **7** |
| Aba 3 · Respostas | 219 | 5 | 1 | 4 |
| Aba 4 · Decisão | 161 | 1 | 1 | 3 |
| Aba 5 · Finalização | 274 | 8 | 3 | 3 |
| Coluna lateral (Progresso/Atalhos/E-mails/Chat) | 110 | 0 | 3 | — |
| **Tela inteira** | **1.381** | **22** | — | — |

Detalhe da aba Envio, o ponto da queixa:

| Bloco | Palavras |
|---|---:|
| Trava de anonimização (só quando há documento do Portal pendente) | 117 |
| Sub-passo 1 — Anexar documentos clínicos | **170** |
| Sub-passo 2 — Registrar envio | **136** |

São **170 palavras** entre o título do sub-passo 1 e o campo de arquivo em que
o operador precisa clicar.

### 3.2 Repetição medida (só dentro da aba Envio)

| Termo | Ocorrências |
|---|---:|
| "anonimiz*" | **9** |
| "nome completo" | 4 |
| "carimb*" | 3 |
| "iniciais" | 2 |

---

## 4. Achados — `processos/detalhe.html`

### 4.1 A cor do sub-passo não indica o estado do sub-passo — **[P2]** ⚑ raiz da queixa

**Problema.** Os dois sub-passos da aba Envio usam borda lateral e cor de
título **fixas por posição**, não por estado:

```html
<!-- linha 450 — sub-passo 1, SEMPRE azul -->
<div th:unless="${envioFeito}" class="border-start border-3 border-primary ps-3 mb-4">
    <h6 class="fw-bold text-primary mb-2 d-flex justify-content-between align-items-center">
        <span><span class="badge bg-primary me-2 rounded-pill">1</span>
        Anexar documentos clínicos anonimizados</span>
        <span th:replace="~{layout :: status(${!documentosClinicos.isEmpty()})}"></span>
    </h6>

<!-- linha 527 — sub-passo 2, SEMPRE verde, mesmo pendente -->
<div th:unless="${envioFeito}" class="border-start border-3 border-success ps-3 mb-3">
    <h6 class="fw-bold text-success mb-2 ...">
        <span><span class="badge bg-success me-2 rounded-pill">2</span>
        Registrar envio</span>
        <span th:replace="~{layout :: status(${envioFeito})}"></span>
```

No print colado pelo usuário isso aparece exatamente assim: o passo 1 está
**Concluído** e o passo 2 **Pendente**, mas o passo 2 é o que está pintado de
**verde**. O único sinal correto é o badge `status(ok)` — texto de `.8rem`
alinhado à direita, o elemento menos visível dos dois cabeçalhos.

Some-se a isso que a tela já tem **três** indicadores de progresso concorrentes
(wizard horizontal com círculos, timeline vertical no card "Progresso", barra de
`% concluido`) e **nenhum** deles chega ao nível do sub-passo — que é justamente
onde o operador executa o trabalho.

**Proposta.** Um componente único `.subpasso` no `app.css`, em que **a cor é
função do estado** e o número vira check quando concluído. Reaproveita as
variáveis `--rs-*` e o fragment `layout :: status(ok)` já existentes — sem
Bootstrap novo, sem JS.

```css
/* app.css — sub-passo de etapa (aba Envio e aba Finalizacao).
   A cor da barra lateral e do circulo numerado codifica o ESTADO,
   nunca a posicao do passo. */
.subpasso            { border-left: 3px solid var(--rs-gray-300);
                       padding: .25rem 0 .25rem 1rem; margin-bottom: 1.5rem; }
.subpasso-atual      { border-left-color: var(--rs-blue); }
.subpasso-ok         { border-left-color: var(--rs-green); }
.subpasso-bloqueado  { border-left-color: var(--rs-gray-200); opacity: .65; }

.subpasso-head       { display: flex; align-items: center; gap: .5rem;
                       margin-bottom: .5rem; }
.subpasso-num        { width: 1.6rem; height: 1.6rem; border-radius: 50%;
                       display: inline-flex; align-items: center;
                       justify-content: center; font-weight: 700;
                       font-size: .8rem; background: var(--rs-gray-200);
                       color: var(--rs-gray-700); flex-shrink: 0; }
.subpasso-atual .subpasso-num { background: var(--rs-blue);  color: #fff; }
.subpasso-ok    .subpasso-num { background: var(--rs-green); color: #fff; }
.subpasso-head .status-mark   { margin-left: auto; }
```

```html
<section class="subpasso"
         th:classappend="${!documentosClinicos.isEmpty()} ? 'subpasso-ok' : 'subpasso-atual'">
    <header class="subpasso-head">
        <span class="subpasso-num">
            <i class="bi bi-check-lg" th:if="${!documentosClinicos.isEmpty()}"></i>
            <span th:if="${documentosClinicos.isEmpty()}">1</span>
        </span>
        <h3 class="h6 fw-bold mb-0">Documentos clínicos anonimizados</h3>
        <span th:replace="~{layout :: status(${!documentosClinicos.isEmpty()})}"></span>
    </header>
    <div class="subpasso-body"> … </div>
</section>
```

**Antes / depois (aba Envio, estado "1 feito, 2 pendente"):**

```
ANTES                                        DEPOIS
┃(azul) ① Anexar documentos … [Concluído]    ┃(VERDE) ✓ Documentos clínicos      [Concluído]
┃  <parágrafo de 88 palavras>                ┃  1 arquivo anexado · alterar
┃  <alerta amarelo de 82 palavras>           ┃
┃  arquivo.pdf                               ┃(AZUL)  ② Registrar envio          [Pendente]
┃  [escolher arquivo] [Anexar]               ┃  ⚠ Apague o nome do paciente de dentro
                                             ┃    do PDF — o carimbo é sobreposto.
┃(VERDE) ② Registrar envio   [Pendente]  ←?? ┃  › O que o sistema faz com estes arquivos
┃  <parágrafo + lista de 3 itens>            ┃  Convite para 3 médicos:
┃  Convite para 3 médicos: …                 ┃    HBBL · HCI · HCPA
┃  [Registrar envio e enviar convites] (sm)  ┃  [ REGISTRAR ENVIO E ENVIAR CONVITES ]
```

**Risco/impacto.** CSS novo + reescrita de 2 blocos `<div>` em `detalhe.html`.
**Não** altera nenhum `id` usado pelo E2E (`#pane-envio`, `form[action*=…]`,
`#finalizacao`). O `<h6>` vira `<h3 class="h6">` para hierarquia semântica
correta — conferir se `AcessibilidadeEstruturaTest` não valida ordem de heading
(hoje não valida). Risco baixo, ganho alto e diretamente ligado ao pedido.

---

### 4.2 Aba Envio: 170 palavras entre o título e o campo de upload — **[P1][P5]**

**Problema.** O bloco do sub-passo 1 mistura quatro assuntos de naturezas
diferentes com o mesmo peso tipográfico:

```html
<p class="small text-muted mb-2">
    Anexe o(s) documento(s) clínico(s) <strong>já anonimizados</strong> (sem o nome do
    paciente). Ao registrar o envio eles serão fundidos em um único PDF e cada página
    receberá um <strong>cabeçalho carimbado</strong> com o número do processo e as iniciais do
    paciente (sem alterar o conteúdo). A solicitação original NUNCA entra neste PDF
    (contem o nome completo). Apenas arquivos PDF entram na consolidação;
    <strong>e obrigatório ao menos um</strong> para registrar o envio.
</p>
<div class="alert alert-warning py-2 small mb-2">
    <i class="bi bi-exclamation-triangle-fill"></i>
    <strong>Anonimize o CORPO dos documentos, não so o nome do arquivo.</strong>
    O cabeçalho carimbado e apenas sobreposto - ele <em>não apaga</em> o nome do
    paciente que estiver escrito dentro do documento. Confira cada PDF e remova/oculte
    o nome completo antes de registrar o envio aos avaliadores. Documentos vindos do
    <strong>Portal do Solicitante</strong> chegam sem anonimização e ficam bloqueados
    acima até você confirmar a revisão &mdash; eles não aparecem nesta lista.
</div>
```

São: **(a)** a instrução (12 palavras); **(b)** como o sistema monta o PDF —
consequência do clique **seguinte**, e repetida palavra por palavra no item 1 da
lista do sub-passo 2 (**[P3]**); **(c)** a regra de segurança de verdade; **(d)**
uma explicação sobre documentos bloqueados "acima" que, quando não há nenhum
bloqueado, descreve algo que **não está na tela** (**[P4]**) — e que, quando há,
já está escrita no alerta vermelho logo acima, com 117 palavras próprias.

**Proposta.** Três camadas, na ordem em que o olho precisa delas:

```html
<!-- 1. Instrucao: uma linha -->
<p class="mb-2">Anexe os documentos clínicos em <strong>PDF</strong>, já anonimizados.
   Pelo menos um é obrigatório.</p>

<!-- 2. Regra de seguranca: curta, com destaque proprio (nao e um alert generico) -->
<p class="subpasso-regra">
    <i class="bi bi-shield-exclamation"></i>
    <strong>Apague o nome do paciente de dentro do PDF.</strong>
    O carimbo do sistema é sobreposto — ele não apaga texto que já esteja no documento.
</p>

<!-- 3. Contexto: fechado por padrao, sem JS, impresso junto (ver regra @media print) -->
<details class="subpasso-ajuda">
    <summary>O que o sistema faz com estes arquivos</summary>
    <p class="small text-muted mb-0 mt-1">
        Ao registrar o envio, os PDFs são fundidos em um arquivo único e cada página
        recebe um cabeçalho com o número do processo e as <strong>iniciais</strong> do
        paciente, sem alterar o conteúdo. A solicitação original, que tem o nome
        completo, nunca entra nesse arquivo. Arquivos que não forem PDF são ignorados
        na consolidação.
    </p>
</details>

<!-- 4. Só quando existe algo bloqueado — some quando nao ha (P4) -->
<p class="small text-muted mb-2" th:if="${!documentosPendentesAnonimizacao.isEmpty()}">
    <i class="bi bi-info-circle"></i>
    Documentos do Portal aguardando sua revisão aparecem no bloco vermelho acima e
    não entram nesta lista.
</p>
```

`<details>`/`<summary>` é HTML nativo: não precisa de JS (respeitando "JS
específico em `static/js/*.js`, nunca inline"), é acessível por teclado e leitor
de tela por padrão, e não introduz componente novo de framework. Acrescentar ao
bloco `@media print` de `app.css`: `details { display: block; } details > *
{ display: block !important; }` para o impresso não esconder o contexto.

**Resultado medido esperado:** 170 → **~45 palavras** sempre visíveis, com
**zero** informação de segurança perdida (ver §7).

**Risco/impacto.** Só template + 3 regras de CSS. Nenhum `id`/`name`/`action`
alterado. O E2E anexa por `#pane-envio form[action*='documento-clinico'] input[name=arquivo]`
e clica em `button:has-text('Anexar documento clínico')` — **ambos preservados**.

---

### 4.3 "Registrar envio e enviar convites" dispara e-mails reais sem confirmação — **[P1][P6]**

**Problema.** O botão que funde o PDF, muda o status para *Enviado* e **envia
e-mail a 3 médicos de verdade** é um `btn-primary btn-sm` sem `data-confirm-msg`:

```html
<form th:unless="${processo.status.finalizado}"
      th:action="@{/processos/{id}/registrar-envio(id=${processo.id})}" method="post"
      data-lock-submit="Registrando envio...">
    <button class="btn btn-primary btn-sm">
        <i class="bi bi-calendar-check"></i> Registrar envio e enviar convites
    </button>
</form>
```

Ele tem o **mesmo tamanho** (`btn-sm`) do botão auxiliar "Anexar documento
clínico" logo acima. E o texto que existe justamente para o operador revisar
antes de clicar (a `<ol>` de 3 consequências, 136 palavras) fica na página, onde
compete com tudo o mais — não no ponto da decisão.

Compare com o precedente já estabelecido no projeto: "Enviar Resposta ao
Solicitante" ganhou `data-confirm-msg` em 2026-08-04 exatamente por ser
irreversível e disparar e-mail oficial. "Registrar envio" tem a mesma natureza e
não recebeu a mesma proteção.

**Proposta.** Mover as consequências para o modal genérico (`confirmar-acao.js`
+ `layout :: confirmModal`, já incluídos nesta tela) e promover o botão:

```html
<div class="d-grid">
  <form th:unless="${processo.status.finalizado}"
        th:action="@{/processos/{id}/registrar-envio(id=${processo.id})}" method="post"
        data-lock-submit="Registrando envio..."
        th:attr="data-confirm-msg='Registrar o envio agora? O sistema vai: gerar o PDF único
                 carimbado (só iniciais do paciente), marcar o processo como Enviado e
                 mandar o convite do Portal do Avaliador por e-mail para '
                 + ${#lists.size(pendentes)} + ' médico(s). Os e-mails saem na hora.'">
      <button class="btn btn-primary btn-lg w-100">
          <i class="bi bi-send-check me-1"></i> Registrar envio e enviar convites
      </button>
  </form>
</div>
```

Na página fica só a lista de destinatários (a informação de verificação que o
operador precisa **antes** de decidir clicar, adicionada de propósito numa
sessão anterior) e uma linha de resumo:

```html
<p class="small text-muted mb-2">
    Ao confirmar, o sistema gera o PDF anonimizado, marca o processo como
    <strong>Enviado</strong> e envia o convite por e-mail. <em>Os e-mails saem na hora.</em>
</p>
```

**Resultado:** 136 → ~55 palavras na página, com a informação completa
aparecendo no momento em que ela é útil.

**Risco/impacto.** ⚠ **Impacto direto no E2E.**
`ProcessoDetalhePage:46` faz `page.locator("#pane-envio button:has-text('Registrar envio')").click()`
e segue direto para o `waitForLoadState()`. Com o modal, é preciso acrescentar
`page.locator("#btnConfirmarAcaoFinal").click();` — exatamente como já foi feito
em `passo5_confirmarRespostaAoSolicitante()` quando o mesmo modal foi adicionado
à etapa 6. **Atualizar `ProcessoDetalhePage` no mesmo commit.** O texto do botão
não muda, então o seletor `has-text` continua válido.

---

### 4.4 Texto factualmente errado na aba Decisão (regra que não existe mais) — **[P1]**

**Problema.** O parágrafo de abertura da Decisão (161 palavras, quatro blocos
separados por `<br>`) termina afirmando uma regra **removida do código**:

```html
Deferir/Indeferir fica bloqueado — aqui e no automático — enquanto
houver parecer antigo (registrado por e-mail) sem o anexo
comprobatório, ou o processo estiver pausado aguardando informação
complementar (salvo o voto favorável do coordenador, que defere
mesmo assim). Resolva a pendência na aba <strong>3. Respostas</strong>.
```

Verificado: `ProcessoValidator.validarDecisao` encadeia apenas
`validarPausaDecisao` → `validarContagemVotos` → `validarMotivoIndeferimento`.
Não existe checagem de anexo; `pareceresRecebidosSemAnexo` foi removido junto
com `OrigemParecer.OPERADOR_EMAIL` no commit `041dc43` (2026-07-29). "Parecer
registrado por e-mail" **não pode mais existir** — `OrigemParecer` tem um único
valor. `grep -rn 'comprobat' src/main` só encontra esta linha do template e um
comentário sem relação em `AnexoStorageService`.

Ou seja: o operador lê, num campo obrigatório de decisão, uma condição de
bloqueio que ele nunca conseguirá satisfazer nem observar — e que o levaria a
procurar na aba 3 um problema inexistente.

**Proposta.** Duas frases + `<details>`, sem a cláusula morta:

```html
<p class="small text-muted mb-2">
    <strong>O sistema decide sozinho</strong> ao formar maioria simples (2 de 3):
    2 favoráveis deferem, 2 desfavoráveis indeferem. O voto favorável do
    <strong>coordenador da CET-RS</strong> defere sozinho.
</p>
<details class="small text-muted mb-3">
    <summary>Quando usar o formulário abaixo</summary>
    <ul class="mb-0 mt-1 ps-3">
        <li><strong>Cancelado</strong> — nunca é automático.</li>
        <li><strong>Redecidir</strong> após uma reabertura pelo administrador.</li>
        <li>No indeferimento automático o motivo gravado é um texto institucional
            padrão; para substituí-lo, o administrador reabre e redecide aqui.</li>
        <li>Deferir e Indeferir ficam bloqueados enquanto o processo estiver
            <strong>pausado</strong> aguardando informação complementar (exceto o voto
            favorável do coordenador, que defere mesmo assim).</li>
    </ul>
</details>
```

**Risco/impacto.** Só texto. **Nenhuma regra de negócio muda** — a proposta
apenas para de descrever uma regra que já não existe e mantém, íntegra, a que
existe (pausa + exceção do coordenador). Risco ~nulo, e o ganho de confiança é
desproporcional ao esforço: texto errado na tela corrói a credibilidade de todo
o resto do texto.

---

### 4.5 Texto factualmente errado na aba Finalização ("registre as datas") — **[P1]**

**Problema.**

```html
<p class="small text-muted mb-3">
    Conclua o processo preenchendo os campos abaixo. As etapas obrigatórias
    variam conforme a decisão:
    <span th:if="${processo.status.name() == 'INDEFERIDO'}">
        <strong>Indeferido:</strong> anexe o ofício de indeferimento e registre as datas.
    </span>
    …
```

A edição manual de datas foi **removida** em 2026-08-04 (decisão de produto
"data de ato = momento do anexo, nunca digitada"): o endpoint
`POST /processos/{id}/finalizacao` e `ProcessoService.atualizarDatasFinalizacao`
não existem mais, e há um teste em `ProcessoAnexoControllerTest` que trava a
remoção (404). Também não há "campos a preencher" — só uploads e um botão.
Mais abaixo, a própria tela já explica corretamente que as datas são gravadas
sozinhas ("— registrada automaticamente ao anexar o ofício").

**Proposta.**

```html
<p class="small text-muted mb-3" th:switch="${processo.status.name()}">
    <span th:case="'INDEFERIDO'">Anexe o <strong>ofício de indeferimento</strong> e envie a
        resposta ao solicitante. As datas são registradas pelo sistema.</span>
    <span th:case="'DEFERIDO'">Anexe o <strong>comprovante de inserção no SNT</strong> e envie a
        resposta ao solicitante. As datas são registradas pelo sistema.</span>
    <span th:case="*">Processo cancelado: não há resposta formal a enviar.</span>
</p>
```

**Risco/impacto.** Só texto. `th:switch` em vez de dois `th:if` sequenciais
segue a convenção do CLAUDE.md. Risco ~nulo.

---

### 4.6 Numeração de aba errada e ação inexistente na aba Respostas — **[P1]**

**Problema.**

```html
<div th:unless="${envioFeito}" class="alert alert-warning">
    <i class="bi bi-hourglass-split"></i>
    Registre o envio aos avaliadores (aba <strong>1. Envio</strong>) antes de lançar as respostas.
</div>
```

Dois erros numa frase de 14 palavras: **(a)** Envio é o passo **2** do wizard
(`FluxoProcessoService.montarPassosWizard` rotula literalmente `"2. Envio"`);
**(b)** "lançar as respostas" é uma ação que **não existe mais** desde
2026-07-27 — o operador não lança parecer por nenhum caminho, os avaliadores
votam autenticados no Portal. O próprio card logo abaixo explica isso.

**Proposta.**

```html
Registre o envio aos avaliadores (passo <strong>2. Envio</strong>) para que os médicos
possam votar no Portal do Avaliador.
```

**Risco/impacto.** Só texto, ~nulo.

---

### 4.7 A aba Respostas enterra a informação nº 1 sob a explicação — **[P6]**

**Problema.** O placar (`favoráveis / não favoráveis / pendentes` +
`fraseMaioria`) é a resposta à única pergunta que o operador faz ao abrir esta
aba: *"já dá para decidir?"*. Ele existe e é bem construído — mas fica num
`card-body py-2 border-bottom bg-light-subtle` com badges pequenos, imediatamente
seguido por um parágrafo de 52 palavras explicando **como o sistema funciona**:

```html
<p class="small text-muted mb-3">
    O parecer de cada médico e registrado por ele mesmo, autenticado no
    <strong>Portal do Avaliador</strong>. Aqui o operador so acompanha o
    resultado de cada avaliador e pode enviar um lembrete por e-mail para
    quem ainda não votou.
    A decisão ocorre por <strong>maioria simples</strong> (2 de 3):
    &ge;2 favoráveis = Deferido, &ge;2 desfavoráveis = Indeferido.
</p>
```

Esse parágrafo é redundante com a própria tabela (a coluna "Ação" já mostra
"Lembrar por e-mail", os badges já mostram o resultado) e com a explicação de
maioria simples que aparece **de novo** na aba Decisão e **outra vez** no alerta
de sugestão automática (`sugestao != null`) — três vezes a mesma regra na mesma
tela (**[P3]**).

**Proposta.** Promover o placar e reduzir a explicação a uma linha + `<details>`:

```
┌──────────────────────────────────────────────────────────────┐
│  ✔ 2 favoráveis   ✘ 0 não favoráveis   ⏳ 1 pendente         │
│  Maioria formada — pronto para decidir.        [ Ir à Decisão ]│
└──────────────────────────────────────────────────────────────┘
Os médicos votam no Portal do Avaliador. Aqui você acompanha e lembra quem falta.
› Como funciona a maioria simples
```

`fraseMaioria` já é calculada no controller; basta subir para `fs-6`/`fw-semibold`
e colocar o botão "Ir à Decisão" ao lado quando `liberadoDecisao` for verdadeiro
(hoje esse botão só aparece num alerta verde no **fim** do card, depois da
tabela — abaixo da dobra em qualquer processo com 3 avaliadores e justificativas).

**Risco/impacto.** Reordenação dentro do mesmo card + `<details>`. Nenhum id do
E2E envolvido (o E2E não interage com esta aba pelo lado do operador). Baixo.

---

### 4.8 Aba Finalização: 8 alertas, 3 blocos de mesmo peso e uma pendência dita duas vezes — **[P3][P6]**

**Problema.** A aba tem **8 caixas de alerta** — a maior concentração da tela — e
três blocos `border rounded p-3 bg-light` com peso visual idêntico (Ofício /
Comprovante SNT / Resposta ao solicitante), embora só um seja o próximo passo.
A mesma pendência é anunciada **duas vezes**:

```html
<!-- dentro do bloco Comprovante SNT -->
<div th:unless="${comprovanteSnT != null}" class="alert alert-warning py-2 mb-2">
    <span th:replace="~{layout :: status(false)}"></span> Comprovante SNT ainda não anexado.
</div>
…
<!-- de novo, dentro do bloco Resposta ao solicitante -->
<div th:if="${processo.status.name() == 'DEFERIDO' and comprovanteSnT == null}"
     class="alert alert-warning py-2 mb-2">
    <span th:replace="~{layout :: status(false)}"></span>
    Anexe o comprovante de inserção no SNT acima antes de enviar a resposta.
</div>
```

O botão de envio já está `th:disabled` exatamente nessa condição — ou seja, a
informação existe **três vezes**: dois alertas e o estado do botão.

**Proposta.** Aplicar o mesmo componente `.subpasso` do §4.1, numerando o que de
fato são passos sequenciais, e deixar a segunda ocorrência apenas como `title`
do botão desabilitado:

```
① Ofício de indeferimento          [Pendente]   ← barra AZUL (é o passo atual)
   Baixar rascunho editável (.rtf) · anexar o documento final
② Resposta ao solicitante          [Bloqueado]  ← barra CINZA, opacidade
   [ Enviar Resposta ao Solicitante ]  (disabled, title="Anexe o ofício acima")
```

Isso troca 8 alertas por 2 sub-passos com estado explícito, sem remover nenhuma
condição de bloqueio (todas continuam no `th:disabled`, que é a trava real).

**Risco/impacto.** ⚠ Bloco mais delicado da tela: contém as duas ações
irreversíveis finais. Os seletores do E2E são por `action` do form
(`form[action*='oficio-upload']`, `form[action*='comprovante-snt']`,
`form[action*='/finalizar']`) e por `#finalizacao` — **todos preservados** se a
reestruturação for só de wrapper/cabeçalho. Fazer em PR próprio, com o E2E
rodado antes do merge.

---

### 4.9 Aba Recebimento: o mesmo fato dito duas vezes numa aba de 40 palavras — **[P3]**

**Problema.**

```html
<p class="small text-muted mb-0">
    <i class="bi bi-check-circle-fill text-success"></i>
    Esta solicitação foi enviada diretamente pelo <strong>Portal do
    Solicitante</strong> — recebimento automático, sem necessidade de
    confirmação manual. <a …>Ver solicitação original</a>
</p>
…
<div th:if="${liberadoEnvio}" class="alert alert-success …">
    <strong>Recebimento concluido!</strong>
    <span class="text-muted small ms-2">Recebimento automático (solicitação enviada pelo Portal do Solicitante).</span>
    <button … data-goto-pane="pane-envio">Avancar para Envio</button>
</div>
```

Duas frases dizendo literalmente a mesma coisa, uma abaixo da outra, numa aba
que não tem nenhuma ação. Como `liberadoEnvio` é **sempre** verdadeiro (o
recebimento é automático desde 2026-07-27), as duas sempre aparecem juntas.

**Proposta (baixo risco).** Manter só o rodapé verde de conclusão, com o link
"Ver solicitação original" movido para dentro dele. 40 → ~18 palavras.

**Proposta (decisão de produto — §8).** Este passo **não tem ação nenhuma** e
está sempre concluído. Vale perguntar se ele merece uma das 5 posições do
wizard, ou se o processo deveria abrir direto na aba **Envio**, com o
recebimento aparecendo apenas como item ✓ da timeline vertical. Isso encurtaria
o wizard para 4 passos clicáveis e removeria um clique de todo fluxo novo.
**Não implementar sem aval** — muda `FluxoProcessoService.montarPassosWizard` e
`ProcessoDetalhePage.passoEstado(numero)`, que localiza por `nth-child`.

---

### 4.10 Coluna lateral: arco-íris de botões sem hierarquia — **[P6]**

**Problema.** O card "Atalhos" tem 5 controles em 5 cores diferentes, todos
`btn-sm text-start`, sem nenhuma indicação de qual é o mais usado:

```html
<a … class="btn btn-outline-danger  btn-sm text-start">Relatório Final (PDF)</a>
<a … class="btn btn-outline-warning btn-sm text-start">Ofício de Indeferimento</a>
<a … class="btn btn-outline-warning btn-sm text-start">Comprovante SNT</a>
<a … class="btn btn-outline-success btn-sm text-start">Baixar processo completo (ZIP)</a>
<a … class="btn btn-outline-primary btn-sm text-start">Editar processo</a>
<button … class="btn btn-outline-danger btn-sm w-100 text-start">Excluir processo</button>
```

"Relatório Final" e "Excluir processo" têm **a mesma cor** (`outline-danger`).
No design system do projeto, vermelho significa destrutivo/negativo — usá-lo
para baixar um PDF treina o olho a ignorar o vermelho justamente onde ele
importa.

**Proposta.** Uma cor por significado: `btn-outline-secondary` para todos os
downloads/ações neutras, e `btn-outline-danger` **exclusivo** para "Excluir
processo", separado por um `<hr class="my-2">`. Os ícones já distinguem cada
item. O parágrafo de 22 palavras explicando a estrutura da pasta do ZIP passa a
`title` do próprio botão.

**Risco/impacto.** ⚠ `ProcessoDetalhePage:161` localiza
`a.btn:has-text('Relatório Final (PDF)')` — o **texto** não muda, só a classe.
Seguro. Baixo.

---

### 4.11 E-mails prontos: a ação trivial está mais destacada que a séria — **[P6]**

**Problema.** Dentro de cada e-mail pronto:

```html
<button class="btn btn-primary btn-sm flex-fill btn-copiar">Copiar corpo</button>
<button class="btn btn-outline-secondary btn-sm flex-fill btn-revisar-ia">Revisar com IA</button>
…
<button class="btn btn-outline-danger btn-sm w-100 btn-enviar-email">Enviar agora por e-mail</button>
```

"Copiar corpo" (ação sem consequência, reversível, gratuita) é o **botão
primário azul**. "Enviar agora por e-mail" (dispara e-mail institucional real) é
um *outline* vermelho — visualmente mais fraco que o de copiar.

**Proposta.** `Copiar corpo` → `btn-outline-secondary`; `Enviar agora por
e-mail` → `btn-danger` sólido. O vermelho sólido é adequado aqui: é irreversível
e já tem o modal de pré-visualização (`#modalConfirmaEmail`) por trás.

**Risco/impacto.** Só classes CSS. Nenhum seletor do E2E envolvido. ~nulo.

---

### 4.12 Três listas do mesmo arquivo na mesma página — **[P3]**

**Problema.** Um documento clínico anexado aparece **três vezes**: no sub-passo 1
da aba Envio, no card "Anexos (e-mails e documentos)" no fim da página, e (o
ofício/comprovante) também dentro da aba Finalização. O card "Anexos" é um
`list-group` sempre expandido, sem contador, no fim de uma página que já tem
1.381 palavras.

**Proposta.** Manter o card (é o repositório completo e o único lugar com
exclusão genérica), mas **colapsado por padrão**, com contador no cabeçalho —
mesmo padrão já usado no card de chat desta tela:

```html
<div class="card-header d-flex justify-content-between align-items-center" role="button"
     data-bs-toggle="collapse" data-bs-target="#corpoAnexos" aria-expanded="false">
    <span><i class="bi bi-paperclip"></i> Todos os anexos</span>
    <span class="d-flex align-items-center gap-1">
        <span class="badge bg-secondary" th:text="${#lists.size(processo.anexos)}">0</span>
        <i class="bi bi-chevron-up chevron-collapse"></i>
    </span>
</div>
<div class="card-body collapse" id="corpoAnexos"> … </div>
```

**Risco/impacto.** O E2E não usa este card. `.chevron-collapse` já existe no
`app.css`. Baixo.

---

### 4.13 Acentuação: sobrou o que vem do Java (e é o texto mais visível do wizard) — **[P1]**

**Problema.** A Fase D do relatório anterior corrigiu a acentuação **dos
templates**. Mas os rótulos do wizard horizontal e **todos** os títulos e
detalhes da timeline vertical vêm de strings Java, que ficaram de fora:

```java
// FluxoProcessoService — texto exibido em .timeline-title / .timeline-desc
etapas.add(montar("Recebimento da solicitacao", …, "Recebimento automatico (solicitacao enviada pelo Portal do Solicitante)."));
etapas.add(montar("Envio aos 3 medicos", …));
etapas.add(montar("Respostas dos medicos", …));
etapas.add(montar("Decisao final", …));
etapas.add(montar("Oficio de indeferimento", …));
// montarPassosWizard — texto exibido em .wizard-label (com text-transform: uppercase)
adicionarPasso(passos, 4, "4. Decisao",     …);
adicionarPasso(passos, 5, "5. Finalizacao", …);
```

Ou seja, a navegação principal da tela exibe **"4. DECISAO"** e
**"5. FINALIZACAO"** em caixa alta. Restam ainda 8 ocorrências no próprio
template: `concluido` (linhas 55, 93, 350, 598), `concluidas` (815) e
`Avancar` (355, 603, 820, 902).

**Proposta em duas partes, com riscos bem diferentes:**

**(a) Template — risco ~nulo.** Corrigir as 8 ocorrências. Nenhum desses textos
é usado pelo E2E (a navegação entre abas é por `.wizard-step[href='#pane-…']` e
`data-goto-pane`, não por texto).

**(b) Java — exige refatoração pequena antes.** ⚠ `EtapaFluxo.titulo()` é usado
hoje como **chave de identidade**, não só como rótulo: `montarPassosWizard`
compara `etapaConcluida(etapas, "Envio aos 3 medicos")` por string literal, e
**~15 asserções** de `FluxoProcessoServiceTest` fazem
`e.titulo().equals("Decisao final")`. Acentuar o rótulo sem separar a chave
quebraria o wizard **silenciosamente** (todos os passos ficariam "não
concluídos") e derrubaria a suíte. A correção correta é adicionar um campo de
chave estável ao record antes de tocar no rótulo:

```java
public record EtapaFluxo(Chave chave, String titulo, String icone, Estado estado, String detalhe) {
    public enum Chave { RECEBIMENTO, ENVIO, RESPOSTAS, INFO_COMPLEMENTAR,
                        DECISAO, OFICIO, COMPROVANTE_SNT, RESPOSTA_SOLICITANTE }
    …
}
```

…com `montarPassosWizard` e os testes passando a casar por `chave`, e `titulo`
virando texto puramente de exibição, livre para ser acentuado. **Fazer em PR
próprio**, com a suíte inteira verde antes do merge.

---

## 5. Achados — demais telas do operador

### 5.1 `processos/lista.html` e `dashboard.html` — a coluna "O que falta" é uma frase, não um rótulo — **[P1]**

`FluxoProcessoService.pendenciaAberta` devolve `titulo + ": " + detalhe`, o que
produz células como:

> *"Envio aos 3 medicos: Anexe o(s) documento(s) clinico(s) (PDF) para gerar o
> processo dos avaliadores."*

— 14 palavras, sem acento, numa coluna de tabela varrida em diagonal. No Painel
o mesmo texto entra em `font-size:.7rem` com `max-width:12rem` **sem** truncamento
por reticências, e no `/processos` entra sem limite de largura, empurrando as
demais colunas.

**Proposta.** Separar as duas partes no serviço
(`pendenciaAberta` devolvendo o `EtapaFluxo` em vez de uma string já concatenada)
e exibir **o rótulo curto** na célula, com o detalhe completo no `title`:

| Situação | Célula | `title` |
|---|---|---|
| Envio pendente | `Envio — falta documento clínico` | frase completa |
| Respostas | `Respostas — faltam 2 de 3` | frase completa |
| Finalização | `Finalização — falta comprovante SNT` | frase completa |

**Risco/impacto.** Toca `FluxoProcessoService` + 2 templates + os testes de
`HomeControllerTest`/`ProcessoListaControllerTest` que renderizam de verdade.
Médio-baixo. Combina naturalmente com a refatoração de chave do §4.13(b).

### 5.2 `processos/solicitacoes-online-lista.html` — manual permanente no topo de uma tela diária — **[P4]**

```html
<p class="small text-muted">
    Pedidos enviados diretamente pela equipe solicitante. Ao converter, você revisa os dados
    no formulario normal de cadastro e escolhe os 3 médicos avaliadores.
</p>
```

Texto de treinamento, exibido para sempre a quem usa a tela todos os dias (e
sem acento em "formulario"). **Proposta:** remover a primeira frase (redundante
com o `<h1>` "Solicitações online — triagem") e mover a segunda para o `title`
do botão "Ver" / para o próprio detalhe, onde a conversão acontece.

### 5.3 `processos/solicitacoes-online-detalhe.html` — a ação principal está no meio da página — **[P6]**

"Revisar e converter" (`btn-primary`) e "Devolver" (`btn-outline-danger`) ficam
**abaixo** da lista de anexos, no meio da página, com peso quase igual, e ainda
seguidos pelo card de chat. Numa solicitação com muitos documentos, a ação
principal da tela cai abaixo da dobra.

**Proposta.** Subir o par de ações para o cabeçalho, ao lado do `<h1>`
(`d-flex justify-content-between`), mantendo "Devolver" como
`btn-outline-secondary` com ícone (ação minoritária) e "Revisar e converter"
como `btn-primary`.
⚠ `SolicitacoesOnlineTriagemPage:49` usa `getByRole(LINK, name="Revisar e converter")`
— **o texto não pode mudar**; mover de lugar é seguro.

### 5.4 `controle-urgencias/lista.html` — coluna de ações com três dialetos — **[P6]**

Na mesma célula: "Editar" só ícone, "Renovar" com ícone **e** texto, "Cancelar"
só ícone. **Proposta:** ou os três só com ícone + `title`/`aria-label` (já
presentes), ou os três com texto. Preferir só ícone (a coluna é estreita e a
tabela tem 8 colunas), mantendo "Renovar" com texto por ser a ação frequente —
mas então "Editar" e "Cancelar" precisam da mesma decisão explícita, não do
acaso.

### 5.5 `usuarios/form.html` — JS inline viola a convenção do projeto

Linhas 96–117 trazem um `<script>` inline com a lógica de mostrar/ocultar os
campos condicionais por perfil. O CLAUDE.md é explícito: *"JavaScript específico
fica em `static/js/*.js`, nunca inline nos templates"*. **Proposta:** extrair
para `static/js/usuario-form.js`. Achado colateral, sem impacto visual — incluir
numa fase de higiene.

### 5.6 Telas que estão certas e **não** devem ser mexidas

- **`controle-urgencias/form.html`** é o melhor exemplo de texto contextual do
  sistema: o `form-text` do campo "Vencimento" muda conforme seja cadastro
  (*"Vazio = hoje + 30 dias"*) ou edição (*"Use para corrigir a data; para
  prorrogar, use Renovar"*). É exatamente o **[P4]** aplicado. Usar como
  referência nas demais telas.
- **`membros/lista.html`**, coluna "Processos" (3 badges com rótulo
  designados/avaliados/favoráveis): parece denso, mas os rótulos foram
  adicionados **de propósito** na Fase C, porque em toque não existe tooltip e o
  leitor de tela lia "0 / 0 / 0". **Não reverter.**
- **`processos/editar.html`**, **`membros/form.html`**, **`usuarios/lista.html`**,
  **`arquivo/lista.html`**: densidade adequada, hierarquia correta, um botão
  primário por tela. Nada a fazer.
- **`dashboard.html`**: os 8 cartões e a legenda de cores estão corretos; o único
  achado é o da pendência (§5.1).

---

## 6. Resumo dos achados

| # | Tela / região | Princípio | Severidade | Esforço |
|:--:|---|:--:|:--:|:--:|
| 4.1 | Cor do sub-passo não indica estado | P2 | **Alta** | Médio |
| 4.2 | 170 palavras antes do campo de upload | P1 P5 | **Alta** | Médio |
| 4.3 | "Registrar envio" sem confirmação e sem destaque | P1 P6 | **Alta** | Baixo ⚠E2E |
| 4.4 | Texto obsoleto (anexo comprobatório) na Decisão | P1 | **Alta** | Trivial |
| 4.5 | Texto obsoleto ("registre as datas") na Finalização | P1 | Média | Trivial |
| 4.6 | "aba 1. Envio" / "lançar as respostas" | P1 | Média | Trivial |
| 4.7 | Placar enterrado sob a explicação | P6 P3 | Média | Baixo |
| 4.8 | Finalização: 8 alertas, pendência dita 2× | P3 P6 | Média | Médio ⚠ |
| 4.9 | Recebimento: fato repetido em aba sem ação | P3 | Baixa | Trivial |
| 4.10 | Atalhos: arco-íris sem hierarquia | P6 | Média | Trivial |
| 4.11 | "Copiar" mais destacado que "Enviar e-mail" | P6 | Média | Trivial |
| 4.12 | Três listas do mesmo anexo | P3 | Baixa | Baixo |
| 4.13a | Acentuação residual no template (8) | P1 | Baixa | Trivial |
| 4.13b | Acentuação do wizard/timeline (vem do Java) | P1 | Média | Médio ⚠ |
| 5.1 | "O que falta" é frase longa em célula | P1 | Média | Médio |
| 5.2 | Manual permanente na triagem | P4 | Baixa | Trivial |
| 5.3 | Ação principal no meio da página (triagem) | P6 | Média | Baixo |
| 5.4 | Ações com três dialetos (urgências) | P6 | Baixa | Trivial |
| 5.5 | JS inline em `usuarios/form.html` | — | Baixa | Baixo |

---

## 7. Mapa: onde cada informação de segurança passa a viver

Esta seção existe para tornar auditável a promessa do §1: **nenhuma trava de
segurança/imparcialidade é removida**. Toda informação abaixo é regra de
negócio documentada no CLAUDE.md, não decoração.

| Informação | Hoje | Depois | Sai da tela? |
|---|---|---|---|
| Anonimizar o **corpo** do PDF, não só o nome do arquivo | 4º parágrafo de um `alert` de 82 palavras | **1ª frase** do bloco, com destaque próprio (`.subpasso-regra`) | **Não** — sobe |
| O carimbo é sobreposto e não apaga texto | mesma frase acima, meio do parágrafo | mesma frase, imediatamente após o negrito | **Não** |
| A solicitação original (nome completo) nunca entra no PDF | parágrafo sempre visível | `<details>` "O que o sistema faz com estes arquivos" | **Não** — 1 clique |
| Só iniciais vão aos avaliadores (imparcialidade) | repetida 4× na aba | 1× no `<details>` + 1× no modal de confirmação do envio | **Não** — deduplicada |
| Documentos do Portal ficam bloqueados até revisão | frase sempre visível + `alert` vermelho de 117 palavras | `alert` vermelho preservado **integralmente**; a frase solta passa a `th:if` do mesmo caso | **Não** |
| Só PDF entra na consolidação; ao menos um é obrigatório | parágrafo | 1ª linha (obrigatoriedade) + `<details>` (não-PDF ignorado) | **Não** |
| Os e-mails saem na hora, para 3 médicos reais | `<ol>` de 3 itens na página | **modal de confirmação** + lista de destinatários preservada na página | **Não** — ganha barreira |
| Deferido exige comprovante SNT antes da resposta | 2 `alert` + `th:disabled` | 1 sub-passo bloqueado + `th:disabled` + `title` | **Não** — trava real intacta |
| Indeferido exige ofício anexado | idem | idem | **Não** |
| Voto é irreversível (Portal do Avaliador) | modal com checkbox | **inalterado** | — |

O único texto que **desaparece de vez** é o do §4.4/§4.5/§4.6: descreve regras
que **não existem no código**. Removê-lo aumenta a segurança do sistema, não
diminui — instrução falsa treina o operador a desconfiar de todas as outras.

---

## 8. Fases de implementação sugeridas

Cada fase é um PR pequeno e independente, na ordem impacto ÷ esforço. Toda fase
exige a suíte completa verde; as marcadas ⚠ exigem também `.\e2e.ps1 -Headless`
antes do merge.

### FASE 1 — Verdade da tela (risco ~nulo, impacto alto)
Só texto. Nenhum seletor de E2E envolvido.
- §4.4 remover a cláusula do "anexo comprobatório" da aba Decisão;
- §4.5 corrigir "registre as datas" na Finalização (`th:switch`);
- §4.6 corrigir "aba 1. Envio" → "passo 2. Envio" e "lançar as respostas";
- §4.9 remover a duplicação na aba Recebimento;
- §4.13a corrigir as 8 acentuações restantes do template;
- §5.2 enxugar a introdução da triagem.

**Aceite:** `grep -rn 'comprobat\|registre as datas\|aba <strong>1. Envio' src/main/resources/templates` → vazio; o comando de acentuação do Anexo A → vazio para `processos/detalhe.html`.

### FASE 2 — Hierarquia por classe CSS (risco ~nulo)
Nenhum texto de botão muda, nenhum id muda — só classes.
- §4.10 atalhos: `outline-secondary` para tudo, `outline-danger` só para excluir, `<hr>` separando;
- §4.11 e-mails prontos: copiar → secundário, enviar → `btn-danger` sólido;
- §4.3 (parte visual) botão "Registrar envio" → `btn-lg w-100` num `d-grid`.

**Aceite:** um único `btn-outline-danger` no card Atalhos; verificação visual em `/processos/{id}` com a app no ar.

### FASE 3 — Componente `.subpasso` e conclusão visual ⚑ (responde à queixa)
- §4.1 CSS `.subpasso*` no `app.css` + aplicação aos 2 sub-passos da aba Envio;
- §4.2 reescrita do texto do sub-passo 1 (instrução / regra / `<details>` / condicional);
- regra `@media print` para `<details>`.

**Aceite:** aba Envio ≤ **180 palavras** visíveis (hoje 435); barra lateral verde ⇔ badge "Concluído" em 100% dos casos. ⚠ rodar o E2E (a aba Envio é o passo 2 do `FluxoCompletoProcessoIT`).

### FASE 4 — Confirmação do envio aos avaliadores ⚠E2E
- §4.3 `data-confirm-msg` no form de `registrar-envio` + enxugamento da `<ol>`;
- **no mesmo commit:** `ProcessoDetalhePage.passo2_registrarEnvio()` passa a clicar
  em `#btnConfirmarAcaoFinal` (mesmo ajuste já feito em `passo5_…`).

**Aceite:** E2E verde; nenhum e-mail sai sem o modal.

### FASE 5 — Abas Respostas e Decisão
- §4.7 promover o placar + botão "Ir à Decisão" no topo do card;
- §4.4 (parte estrutural) `<details>` "Quando usar o formulário abaixo";
- avaliar `data-confirm-msg` em "Registrar decisão" — **⚠ se adotado**, atualizar
  `ProcessoDetalhePage:78` (`#decisao button:has-text('Registrar decisão')`) para
  clicar no modal. Decisão de produto: registrar decisão é reversível pelo ADMIN,
  mas espelha o status no Portal do Solicitante imediatamente.

### FASE 6 — Aba Finalização ⚠
- §4.8 aplicar `.subpasso` aos blocos Ofício/Comprovante/Resposta;
- eliminar o alerta duplicado (fica `th:disabled` + `title`);
- §4.12 card "Todos os anexos" colapsado com contador.

**Aceite:** aba Finalização ≤ 4 alertas (hoje 8); E2E verde (3 uploads + finalizar).

### FASE 7 — Rótulo curto de pendência + acentuação vinda do Java ⚠
- §4.13b introduzir `EtapaFluxo.Chave`, migrar `montarPassosWizard` e os ~15
  testes para casar por chave, e **então** acentuar os rótulos;
- §5.1 `pendenciaAberta` devolvendo título e detalhe separados; lista e Painel
  passam a exibir o rótulo curto com o detalhe em `title`.

**Aceite:** wizard exibe "4. DECISÃO"/"5. FINALIZAÇÃO"; `FluxoProcessoServiceTest` verde sem comparar strings de exibição.

### FASE 8 — Higiene das demais telas
- §5.3 subir as ações da triagem para o cabeçalho (⚠ texto do link intacto);
- §5.4 padronizar a coluna de ações de `controle-urgencias`;
- §5.5 extrair o JS inline de `usuarios/form.html`.

### Fora de fase — decisões de produto (não implementar sem aval)
1. **Eliminar a aba 1 · Recebimento** (§4.9), abrindo o processo direto em Envio
   e deixando o recebimento só como ✓ da timeline. Muda `montarPassosWizard` e
   `ProcessoDetalhePage.passoEstado` (localiza por `nth-child`).
2. **Fragmentar `processos/detalhe.html`.** O §10 do relatório anterior proíbe, e
   **a proibição continua válida** — mas registro a pergunta em aberto: 5 das 8
   fases acima tocam esse arquivo, e o argumento original ("é a tela mais coberta
   por testes e pelo E2E") é exatamente o que tornaria uma fragmentação
   verificável hoje. Se algum dia for feito, deve ser PR dedicado, sem nenhuma
   mudança de comportamento junto — **não** como efeito colateral destas fases.

---

## 9. O que **não** fazer

1. **Não remover nenhuma informação da tabela do §7.** "Menos poluído" é
   reorganizar e hierarquizar. A frase sobre anonimizar o corpo do PDF é uma
   regra de negócio; ela pode encolher e mudar de lugar, nunca sumir.

2. **Não trocar o Bootstrap nem introduzir framework de front.** Todas as
   propostas usam Bootstrap 5.3.8 + `--rs-*` já existentes. **Nunca Tailwind** —
   já foi removido do projeto uma vez.

3. **Não fragmentar `processos/detalhe.html` como efeito colateral.** Restrição
   herdada do §10 do relatório anterior; mantida (ver a ressalva do §8).

4. **Não trocar texto de botão sem abrir o Page Object no mesmo commit.** Os
   textos exatos hoje protegidos por E2E: `Anexar documento clínico`,
   `Registrar envio`, `Registrar decisão`, `Relatório Final (PDF)`,
   `Registrar meu voto`, `Enviar solicitação`, `Revisar e converter`,
   `Cadastrar`, `Ver`. Nenhuma proposta deste relatório muda esses textos — é
   deliberado, e deve continuar assim.

5. **Não acentuar `ResultadoParecer.descricao`.** Decisão deliberada e
   documentada: alimenta PDF oficial, exportação e auditoria. As duas telas onde
   esse termo aparece já usam literal acentuado via `th:switch`.

6. **Não acentuar `EtapaFluxo.titulo` sem antes separar a chave** (§4.13b). Hoje
   o título é chave de identidade em `montarPassosWizard` e em ~15 asserções de
   teste — acentuar direto quebra o wizard silenciosamente.

7. **Não transformar os `<details>` em componente com JS.** `<details>`/`<summary>`
   nativo é acessível por padrão, não exige script e não pode dessincronizar. O
   único ajuste necessário é a regra de `@media print`.

8. **Não substituir alerta por tooltip em informação obrigatória.** Tooltip não
   existe em toque e não é lido em varredura. Onde este relatório propõe
   `title`, é sempre **complemento** de um texto visível (o rótulo curto), nunca
   o único portador da informação.

9. **Não mexer na coluna "Processos" de `membros/lista.html`** (§5.6) — a
   aparente verbosidade é uma correção de acessibilidade deliberada da Fase C.

10. **Não reduzir o número de indicadores de progresso a menos de dois.** Wizard
    (onde estou / para onde vou) e timeline (o que falta em detalhe) servem a
    perguntas diferentes. O problema não é existirem dois; é **nenhum** deles
    chegar ao nível do sub-passo — que é o que a Fase 3 corrige.

---

## Anexo A — Comandos de verificação

```bash
# Densidade por aba de processos/detalhe.html (palavras visiveis, sem comentarios/atributos)
python3 - <<'EOF'
import re
raw=open('src/main/resources/templates/processos/detalhe.html',encoding='utf-8').read()
def wc(f):
    t=re.sub(r'<!--.*?-->','',f,flags=re.S); t=re.sub(r'<[^>]*>',' ',t)
    return len([w for w in re.sub(r'\s+',' ',t).split() if re.search(r'[A-Za-zÀ-ÿ]',w)])
for k,(a,b) in {'ABA1':('id="pane-recebimento"','/ABA 1 Recebimento'),
                'ABA2':('id="pane-envio"','/ABA 2 Envio'),
                'ABA3':('id="pane-respostas"','/ABA 3 Respostas'),
                'ABA4':('id="pane-decisao"','/ABA 4 Decisao'),
                'ABA5':('id="pane-finalizacao"','/ABA 5 Finalizacao')}.items():
    print(k, wc(raw[raw.find(a):raw.find(b)]))
print('TELA', wc(raw))
EOF

# Caixas de alerta por tela (hoje: detalhe = 22)
grep -c 'class="alert' src/main/resources/templates/processos/detalhe.html

# Texto factualmente obsoleto (esperado ao fim da Fase 1: vazio)
grep -rn 'comprobat\|registre as datas\|aba <strong>1\. Envio\|lançar as respostas' \
     src/main/resources/templates/

# Acentuacao residual em texto visivel (ignora comentarios e atributos)
python3 - <<'EOF'
import re,glob
pat=r'\b(concluido|concluida|concluidas|Avancar|solicitacao|decisao|oficio|informacao|analise)\b'
for f in sorted(glob.glob('src/main/resources/templates/**/*.html',recursive=True)):
    raw=re.sub(r'<!--.*?-->','',open(f,encoding='utf-8').read(),flags=re.S)
    hits=re.findall(pat,re.sub(r'<[^>]*>','\n',raw))
    if hits: print(f,hits)
EOF

# Rotulos do wizard/timeline que vem do Java (esperado ao fim da Fase 7: vazio)
grep -n 'montar("\|adicionarPasso(passos' src/main/java/br/gov/saude/sgpur/service/FluxoProcessoService.java

# Repeticao dentro da aba Envio (hoje: anonimiz*=9, "nome completo"=4)
python3 - <<'EOF'
import re
raw=open('src/main/resources/templates/processos/detalhe.html',encoding='utf-8').read()
f=raw[raw.find('id="pane-envio"'):raw.find('/ABA 2 Envio')]
t=re.sub(r'<[^>]*>',' ',re.sub(r'<!--.*?-->','',f,flags=re.S))
for p in ['[Aa]nonimiz','nome completo','carimb','iniciais']:
    print(p, len(re.findall(p,t)))
EOF

# Acoes irreversiveis sem confirmacao (esperado ao fim da Fase 4: registrar-envio presente)
grep -n 'registrar-envio\|/decidir\|/finalizar' src/main/resources/templates/processos/detalhe.html
grep -n 'data-confirm-msg' src/main/resources/templates/processos/detalhe.html

# Textos de botao protegidos pelo E2E (conferir antes de qualquer renomeacao)
grep -rn 'has-text\|GetByRoleOptions().setName' src/test/java/br/gov/saude/sgpur/e2e/pages/
```

## Anexo B — Critério de aceite consolidado

| Métrica | Hoje | Alvo | Fase |
|---|---:|---:|:--:|
| Palavras visíveis na aba Envio | 435 | ≤ 180 | 3 |
| Palavras entre o título do sub-passo 1 e o campo de upload | 170 | ≤ 45 | 3 |
| Caixas de alerta em `processos/detalhe.html` | 22 | ≤ 14 | 3 + 6 |
| Caixas de alerta na aba Finalização | 8 | ≤ 4 | 6 |
| Ocorrências de "anonimiz*" na aba Envio | 9 | ≤ 4 | 3 |
| Trechos de texto factualmente obsoletos | 3 | 0 | 1 |
| Palavras sem acento em texto visível (detalhe) | 8 | 0 | 1 |
| Rótulos do wizard sem acento | 2 | 0 | 7 |
| Sub-passos cuja cor não reflete o estado | 2 de 2 | 0 | 3 |
| Ações irreversíveis sem confirmação | 1 (registrar-envio) | 0 | 4 |
| `btn-outline-danger` no card Atalhos | 2 | 1 | 2 |

# Relatório de redesign visual — Portal do Solicitante

**Data:** 2026-08-06 · **Tipo:** diagnóstico visual + proposta de direção
(**nenhuma linha de código foi alterada para produzir este documento**)
**Escopo:** `/solicitante/**` — `templates/solicitante/lista.html`,
`nova.html`, `detalhe.html`, `indisponivel.html`, mais a tela de login
(`login.html`), que é o primeiro contato do solicitante com o sistema.

**Gatilho:** feedback direto do dono do produto:

> *"o visual é tão simples e feio"*

---

## STATUS: IMPLEMENTADO (V1–V6) — leia antes de reimplementar

| Item | Status |
|---|---|
| §10 — as 7 decisões de produto | **Aprovadas** pelo dono do produto em 2026-08-06 (nota no próprio §10) |
| §8 — fases V1, V2, V3, V4, V5, V6 | **Implementadas e mescladas em `main`** pelo **PR #42** (`feat/redesign-visual-solicitante`), merge em 2026-08-06 14:40 UTC, um commit por fase |
| §5.4 — reparo do ícone de `indisponivel.html` (`bi-tools` → envelope) | **Implementado em 2026-08-08** (era o único item do relatório que tinha ficado de fora do PR #42) |
| Tokens do Anexo B (`--saur-elev-*`, `--saur-surface-*`, `--saur-on-*`, `--saur-font-2xl`) | **No `app.css`**, os 12 |
| Classes novas previstas no Anexo B (as 10) | **No `app.css`**: `.pagina-cabecalho`, `.pagina-titulo`, `.secao-titulo`, `.secao-rotulo`, `.estado-vazio`, `.estado-vazio-icone`, `.cartao-resultado`, `.cartao-resultado-icone`, `.chip-protocolo`, `.superficie-apoio` |

**Este documento descreve, do §1 ao §7, o estado ANTERIOR à implementação** —
é o diagnóstico que a justificou, não o código de hoje. Nenhum achado das
seções de diagnóstico (§4, §5) continua valendo como defeito atual: o
`<h2 class="h6 text-muted">` foi substituído por `.secao-titulo`, a faixa de
resumo deixou de ser 60% âmbar, os estados vazios ganharam ícone e ação, o
cartão de resultado existe, e a marca passou a aparecer dentro das telas
(`.pc-marca`). Antes de "corrigir" qualquer coisa listada aqui, **confira o
código real**.

**Por que este bloco existe:** uma vistoria de 2026-08-08 concluiu que este
relatório *"nunca foi implementado e está esfriando"*, porque a nota do §10
ainda dizia que a implementação seria "um passo separado, a ser retomado
quando solicitado" — texto verdadeiro quando escrito e falso poucas horas
depois. O trabalho estava feito e em produção; faltava só o registro. É
exatamente a classe de erro que o CLAUDE.md já documenta em outras vistorias
(*"o texto do guia estava desatualizado"*).

---

**Natureza da queixa:** estética e percepção, **não** funcionalidade. O fluxo,
a hierarquia de informação e a carga cognitiva do Portal já foram tratados nas
Fases 1–10 de `RELATORIO-UI-SOLICITANTE-AVALIADOR-2026-08.md` — todas
implementadas e em produção. Este documento trata do que aquele relatório
explicitamente **não** tratou: tipografia, cor, espaçamento, profundidade,
personalidade de marca e a sensação de "produto cuidado".

> Aquele relatório abre, na §0, com a frase *"O problema hoje **não é feiúra** —
> é hierarquia e carga cognitiva"*. Aquilo estava certo **naquele momento e para
> aquele escopo**. Resolvida a hierarquia, o que sobrou exposto foi exatamente a
> camada que ficou de fora: a aparência. As duas leituras não se contradizem —
> a segunda só ficou visível depois que a primeira foi corrigida.

---

## 1. Sumário executivo

A queixa procede e tem causa identificável, não é questão de gosto.

O Portal do Solicitante hoje é **funcionalmente maduro e visualmente
inexpressivo**, por seis motivos verificáveis no código, não por falta de
capricho:

1. **A escala tipográfica tem 2 degraus úteis.** O maior texto da tela é um
   `<h1 class="h3">` (~1,75rem) e **todo** título de seção é
   `<h2 class="h6 text-muted">` — 1rem, exatamente o mesmo tamanho do parágrafo
   ao lado, diferenciado só por peso e cor cinza. Entre o corpo e o título não
   existe degrau nenhum em uso: `1,25rem` aparece **uma única vez** nas três
   telas.
2. **A cor institucional não entra no conteúdo.** Azul e dourado existem na
   navbar, no logo e nos botões. Da navbar para baixo, as três telas são
   **cartões brancos sobre um fundo branco-acinzentado**, com texto cinza. O
   `linear-gradient` do `body` vai de `#f8fafc` a `#f1f5f9` — 7, 5 e 3 níveis de
   diferença por canal: existe no CSS e não existe na tela.
3. **O dourado da marca está semanticamente ocupado por "atenção".** É o achado
   estrutural deste relatório (§4.3): a segunda cor da identidade visual não pode
   ser usada como identidade, porque em qualquer superfície ela hoje significa
   *alerta*. Isso não se resolve com capricho — exige uma decisão do dono do
   produto.
4. **Existe um único nível de profundidade.** Os 9 cartões de `detalhe.html`
   usam a mesma `box-shadow` — o cartão de situação (a resposta que o
   solicitante veio buscar) tem exatamente o mesmo peso visual do cartão que
   lista o RGCT. A única diferenciação é uma borda esquerda de 4px.
5. **A escala de espaçamento do design system é praticamente inédita.**
   `var(--saur-space-*)` é consumida **2 vezes** em 1.397 linhas de `app.css`.
   Todo respiro vem de utilitárias genéricas do Bootstrap (`my-4`, `mb-3`), que
   é exatamente o que faz uma tela "parecer Bootstrap".
6. **Os estados vazios não foram desenhados.** Há quatro tratamentos diferentes
   entre si (um ícone `fs-2` cinza, um `fs-3`, e dois só de texto cinza), e
   **nenhum deles oferece a ação óbvia**. A primeira tela que um solicitante novo
   vê depois de logar é uma tabela vazia e cinza — sem o botão "enviar minha
   primeira solicitação", que existe no topo da mesma página.

Somando: **o logo Gota+Cruz aparece 0 vezes** dentro das quatro telas do Portal
(`grep -c "<svg"` = 0 em todas). A identidade visual termina na navbar.

**A proposta (§6) não é trocar nada da base.** Bootstrap continua, a paleta
institucional continua, o fluxo das Fases 1–10 continua intocado. São **seis
movimentos** que consomem infraestrutura que o projeto **já criou e não usa**
(os tokens `--saur-*` de 2026-08-05), mais três classes novas em `app.css`. A
maior parte do ganho percebido está nas fases V1–V3, que não tocam em nenhum
controller, nenhuma regra e nenhum `name=` de formulário.

**Alerta metodológico (§11):** as 783 asserções da suíte e o E2E podem ficar
**inteiramente verdes** com o Portal visualmente quebrado. Nenhuma mudança
deste plano pode ser mesclada sem alguém **olhar a tela renderizada**.

---

## 2. O que este relatório NÃO é

Delimitação deliberada, para não repropor trabalho já feito nem invadir escopo
alheio:

| Assunto | Onde já foi tratado | Status |
|---|---|---|
| Hierarquia de informação, "uma tela, uma pergunta" | `RELATORIO-UI-SOLICITANTE-AVALIADOR-2026-08.md` Fases 1–10 | **Feito**, em produção |
| Consolidação dos 8 `alert` no cartão de situação único | idem, Fase 6 (`SituacaoPedidoView`) | **Feito** |
| Filtro por card, busca, número do processo na lista | idem, Fase 7 | **Feito** |
| Acentuação e microcopy dos portais | idem, Fase 8 | **Feito** |
| `<main id="conteudo">`, skip-link, contraste, alvo de toque | idem, Fase 9 + `RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md` Fase E | **Feito** |
| Rascunho, `beforeunload`, lock-submit, contador de caracteres | CLAUDE.md, sessões de 2026-08-04 | **Feito** |
| Densidade e tom por perfil (`data-densidade`, `tomBadge`) | CLAUDE.md, régua de tokens 2026-08-05 | **Infraestrutura pronta, pouco consumida** |
| Telas do operador | `RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md`, `RELATORIO-UI-CLAREZA-OPERADOR-2026-08.md` | Fora do escopo deste documento |

**Nada aqui reestrutura fluxo, adiciona funcionalidade, muda regra de negócio,
mexe em `SolicitacaoOnline`/`Processo`/`ProcessoValidator`, altera endpoint ou
toca no whitelist de `TipoAnexo` do download do solicitante.** Se alguma fase
adiante parecer exigir isso, ela está mal escrita — não implemente.

---

## 3. Método

Tudo abaixo foi verificado lendo o arquivo real. Os comandos que reproduzem
cada número estão no **Anexo A**. Os contrastes do **Anexo C** foram calculados
pela fórmula de luminância relativa da WCAG 2.x, não estimados a olho.

Arquivos lidos integralmente: os 4 templates de `templates/solicitante/`,
`login.html`, `layout.html` (fragments `head`/`marca`/`navbar`/`footer`/
`tomBadge`/`confirmModal`), `static/css/app.css` (1.397 linhas),
`web/dto/SituacaoPedidoView.java` e `SolicitanteController.montarSituacaoPedido`
(para conhecer o texto real de cada estado, usado nos mockups).

---

## 4. Diagnóstico transversal — por que a tela "parece um formulário"

### 4.1 Tipografia: uma escala de dois degraus

O que existe hoje, nas 4 telas do Portal:

| Papel | Marcação real | Tamanho efetivo |
|---|---|---|
| Título da página | `<h1 class="h3">` | ~1,75rem |
| Título de seção | `<h2 class="h6 text-muted">` | **1rem** |
| Corpo | `<p>` | 1rem |
| Apoio | `.small` / `.form-text` | 0,875rem |
| Rótulo de card | `.stat-label` | 0,75rem |
| Número do card | `.stat-number` (`--saur-font-xl`) | **1,75rem** |

Duas consequências diretas:

- **Título de seção e corpo de texto têm o mesmo tamanho.** "Documentos
  anexados", "Andamento do pedido", "Paciente", "Equipe solicitante" são todos
  `h6 text-muted`: 1rem, cinza. A única diferença perceptível entre um título de
  seção e uma frase comum é o peso 600. Isso achata a página inteira — o olho não
  encontra ponto de entrada e lê tudo em sequência, como um documento impresso.
- **O número decorativo de um cartão de resumo tem exatamente o mesmo tamanho do
  nome da tela** (`--saur-font-xl` = 1,75rem = `h3`). O "0" de "Devolvidas"
  compete tipograficamente com "Minhas solicitações".

O token `--saur-font-lg` (1,25rem) — o degrau que resolveria isso — é usado nas
três telas **uma única vez**: o `<h2 class="h5">` do cartão de situação
(`detalhe.html:41`).

### 4.2 Cor: institucional na navbar, ausente no conteúdo

Da navbar para baixo, o inventário de superfícies coloridas do Portal é:

- **`lista.html`**: 5 cartões de resumo, dos quais 3 usam `stat-card-andamento`
  (fundo `--rs-gold-light`) e 2 `stat-card-total` (fundo **branco**). Resto da
  tela: um card branco com uma tabela de linhas brancas.
- **`nova.html`**: **zero** superfícies coloridas fora dos `alert` condicionais
  (que só aparecem em erro/rascunho). O formulário inteiro — a tela mais longa do
  Portal — é branco sobre cinza-claríssimo.
- **`detalhe.html`**: uma borda esquerda de 4px colorida no cartão de situação e
  um ícone de 2rem na mesma cor. Os outros 8 cartões são brancos.

O `body` tem `linear-gradient(135deg, #f8fafc, #f1f5f9)`. A diferença entre as
duas paradas é de 7, 5 e 3 níveis por canal — imperceptível na maioria dos
monitores e em qualquer tela de celular sob luz de hospital. **Existe um
gradiente no CSS e não existe um gradiente na tela.**

### 4.3 O dourado da marca está semanticamente ocupado — o achado estrutural

`--rs-gold` (`#f5a623`) tem hoje **dois papéis incompatíveis** no mesmo sistema:

| Papel | Onde |
|---|---|
| **Identidade** | preenchimento da gota do logo; `border-bottom: 2px solid var(--rs-gold)` da navbar; badge "UR" |
| **Atenção / pendência** | `.stat-card-andamento`, `.badge-rs-gold`, `alert-warning`, "Ação necessária", `situacao.classeCor == 'warning'` |

Consequência prática: **hoje é impossível usar a segunda cor da identidade
visual como identidade dentro do conteúdo**, porque qualquer superfície dourada
que se coloque numa tela será lida como aviso. O Portal fica, por construção,
restrito a azul + cinza — e o azul só é usado em botões e na navbar. É esse
mecanismo, e não falta de capricho, que produz a sensação de "genérico".

Isso tem um efeito colateral já visível: **a faixa de resumo de `lista.html` é
60% âmbar.** "Aguardando triagem", "Em análise" e "Devolvidas" são todos
`stat-card-andamento`; "Total" e "Decididas" são brancos. Ou seja, os estados
**rotineiros e sem ação nenhuma do solicitante** ("em análise" é literalmente
"não faça nada") são pintados de alerta, e o desfecho é neutro.

> Não é um erro de quem fez: a Fase 1 (itens 1.3/1.4) trocou verde/vermelho por
> neutro/âmbar exatamente para **corrigir** cores que mentiam ("Decididas" verde
> agregando indeferidos). A correção estava certa isoladamente; o efeito
> agregado — uma faixa majoritariamente âmbar — só aparece olhando a tela
> inteira, que é o que este relatório faz.

**Esta é a decisão nº 1 do §10.** Há dois caminhos e eles são mutuamente
exclusivos.

### 4.4 Profundidade: um nível de sombra para nove cartões

```css
--rs-shadow: 0 1px 3px rgba(0,0,0,.1), 0 1px 2px rgba(0,0,0,.06);
.card { box-shadow: var(--rs-shadow); }
.card:hover { box-shadow: var(--rs-shadow-md); }
```

Esse é o valor padrão de sombra de qualquer framework utilitário moderno — é
literalmente o "shadow" default. Os tokens `--rs-shadow-md` e `--rs-shadow-lg`
existem, mas em repouso **todo cartão do sistema tem a mesma elevação**.

Em `detalhe.html` isso significa: o cartão de situação (que responde "meu pedido
foi deferido?"), o cartão da timeline, o cartão de dados cadastrais e o cartão do
chat são visualmente **o mesmo objeto**, quatro vezes empilhado. A hierarquia foi
resolvida por **ordem** (Fase 6 colocou o cartão certo em primeiro) mas nunca por
**peso visual**.

### 4.5 A escala de espaçamento existe e não é usada

`--saur-space-1..6` foi criada em 2026-08-05 como infraestrutura. Uso real hoje:

```
var(--saur-space  → 2 ocorrências em app.css (1.397 linhas)
var(--saur-radius →13
var(--saur-font   →28
```

Ou seja: **o ritmo vertical do Portal é 100% Bootstrap genérico** (`my-4`,
`mb-3`, `g-3`, `p-4`). Isso não é errado, mas é exatamente o que produz "cara de
template": o espaçamento não tem intenção, tem default.

Vale notar que `[data-densidade="confortavel"]` redefine `--saur-space-4` para
`1.25rem` — a régua de densidade por portal, que é o mecanismo desenhado para dar
ao Solicitante mais respiro que ao operador, **hoje quase não tem efeito**,
porque quase nada consome o token que ela redefine.

### 4.6 Estados vazios: quatro tratamentos, nenhuma ação

| Estado | Marcação atual | Ícone | Ação oferecida |
|---|---|---|---|
| Lista sem solicitações (`lista.html:176-181`) | `<td colspan=6 class="text-center text-muted py-5">` | `bi-inbox fs-2` cinza | **nenhuma** |
| Filtro/busca sem resultado (`lista.html:113-116`) | `<p class="text-muted text-center py-4">` | `bi-funnel fs-3` cinza | nenhuma |
| Sem documentos anexados (`detalhe.html:175-177`) | `<li class="list-group-item text-muted">` | **nenhum** | nenhuma |
| Sem mensagens (`detalhe.html:194-196`) | `<div class="text-muted small text-center py-3">` | **nenhum** | (o campo está logo abaixo) |

O primeiro é o mais grave: **é a primeira tela que um solicitante novo vê depois
do primeiro login**. Ele recebe uma tabela vazia com um ícone cinza e uma frase
cinza. O botão "Nova solicitação" existe — mas no topo da página, fora do bloco
que está dizendo "você não tem nada aqui".

### 4.7 A marca não entra na tela

```
grep -c "<svg" templates/solicitante/*.html
detalhe.html:0   lista.html:0   indisponivel.html:0   nova.html:0
```

O logo Gota+Cruz aparece a 28px na navbar e a 36px no login. Dentro do conteúdo
do Portal, **nunca**. Some a navbar (que o usuário para de enxergar em ~2
visitas) e o que resta poderia ser o portal de qualquer secretaria de qualquer
assunto. Não há um único elemento visual que diga "isto é a Central de
Transplantes do RS cuidando do seu pedido de urgência renal".

---

## 5. Diagnóstico tela a tela

### 5.1 `lista.html` — 248 linhas, 5 cartões, 0 estilos inline

**O que já está bom e não deve ser tocado:** o banner de "ação necessária"; os
cartões de resumo funcionando como filtro; a busca por paciente/RGCT; o par
tabela (≥768px) / cards empilhados (<768px); o chevron sinalizando que o card
mobile é clicável; `.stat-card-portal` tendo eliminado 30 declarações inline.

**Diagnóstico visual:**

- **A faixa de resumo é 60% âmbar** (§4.3). O olho é puxado para "Aguardando
  triagem" e "Em análise" — os dois estados em que o solicitante **não tem nada a
  fazer**.
- **O título da página flutua sem contexto.** `<h1 class="h3">` + um `<p
  class="text-muted small">` com a equipe, direto sobre o fundo cinza-claro,
  imediatamente abaixo da navbar azul. Não há transição visual entre a barra
  institucional e o conteúdo: o azul termina abruptamente num vazio cinza.
- **`.stat-number` a 1,75rem compete com o `<h1>`** (§4.1). Cinco números do
  mesmo tamanho do nome da tela.
- **A tabela é uma tabela.** `table-hover` sobre linhas brancas, cabeçalho
  `table-light`, badges pill nas células. É correta e é o visual padrão de
  qualquer painel administrativo. Para o operador (densidade `operacional`, dezenas
  de linhas) isso é adequado; para o solicitante — que costuma ter **1 a 3
  pedidos na vida** — uma tabela de 6 colunas é um exagero de formalidade
  administrativa para exibir três linhas.
- **Estado vazio sem ação** (§4.6).

### 5.2 `nova.html` — 166 linhas, a tela mais longa e a mais cinza

**O que já está bom:** os blocos de orientação da justificativa clínica; o
contador de caracteres; `data-lock-submit`; o aviso de reanexar documentos; o
resumo verde de documentos selecionados; o rascunho; os `form-text` explicando
RGCT e data; a separação visual entre "Cancelar" (link discreto à esquerda) e
"Enviar" (primário à direita).

**Diagnóstico visual:**

- **Zero cor em repouso.** Num preenchimento normal (sem erro, sem rascunho), a
  tela é: um `<h1>`, uma linha "* Campo obrigatório" e **um único cartão branco
  com quatro `<hr>` dentro**. Os `alert` coloridos só existem em estados de
  exceção.
- **Os quatro `<hr>` são o único mecanismo de agrupamento.** Uma régua cinza de
  1px separando "Equipe solicitante" de "Paciente" de "Data" de "Justificativa"
  de "Documentos". Os títulos dessas seções são `h6 text-muted` = 1rem = tamanho
  do corpo. Visualmente, é um formulário de papel digitalizado.
- **A área de upload não parece uma área de upload.** É um `<input type="file"
  class="form-control">` — um retângulo do mesmo tamanho e mesma aparência do
  campo "Nome do paciente". Anexar os exames é uma das duas ações que realmente
  importam nesta tela (a outra é a justificativa) e não tem nenhum destaque
  visual: nenhuma zona pontilhada, nenhum ícone, nenhuma diferenciação de fundo.
- **O momento de sucesso não existe.** Enviar a solicitação leva a um redirect
  para a lista com um flash. Não há nenhum instante visual de "pronto, seu pedido
  de urgência foi recebido" — que é, emocionalmente, o ponto mais alto do fluxo
  para quem está do outro lado.

### 5.3 `detalhe.html` — 303 linhas, 9 cartões idênticos

**O que já está bom e é intocável:** o cartão de situação único alimentado por
`SituacaoPedidoView` (Fase 6); a timeline como resumo de progresso; o botão de
download promovido a ação primária; o vocabulário unificado Deferido/Indeferido;
o modal dedicado com checkbox para cancelar processo; os ícones por extensão de
arquivo; o chat com poll AJAX.

**Diagnóstico visual:**

- **O desfecho tem o mesmo desenho da espera.** "Aguardando triagem" e
  "Deferido — Urgência renal reconhecida" renderizam **exatamente a mesma
  estrutura**: mesmo cartão, mesma borda esquerda de 4px, mesmo ícone `fs-2`,
  mesmo `<h2 class="h5">`, mesmo parágrafo. Só o **matiz** muda (âmbar → verde).
  O resultado que uma família esperou semanas para receber chega com o mesmo peso
  visual de "não é necessário fazer nada agora". **Este é o ponto único de maior
  retorno estético do Portal inteiro.**
- **Nove cartões, uma elevação** (§4.4).
- **O número do processo — a chave que a equipe usa ao telefone — é um `<span
  class="text-muted fs-5 font-monospace">`**: cinza, do lado do `<h1>`. É a
  informação mais "de protocolo" da tela e está renderizada como se fosse
  secundária.
- **A timeline é o único componente com personalidade visual** (dots coloridos,
  linha vertical, anel pulsante no passo atual) e está enterrada no terceiro
  cartão, com `timeline-title` a `.85rem` — menor que o corpo de texto.
- **O bloco `<dl class="row">`** de RGCT/data/justificativa é a definição de lista
  de definições padrão: rótulo em 4 colunas, valor em 8, sem nenhum tratamento.
- **Dois estados vazios sem ícone nenhum** (§4.6).

### 5.4 `indisponivel.html` — 31 linhas

Tela curta, e curiosamente **a mais bem resolvida do Portal**: cartão centralizado,
ícone `fs-1`, título, parágrafo explicativo, uma ação. É o padrão de "estado
vazio bem tratado" que as outras telas não têm — vale usá-la como referência
interna em vez de inventar um padrão novo.

Único reparo: `bi-tools` (ferramentas) comunica "sistema quebrado / em
manutenção", quando a mensagem real é "seu perfil ainda não foi habilitado, siga
usando o e-mail". Um ícone de envelope/relógio seria mais fiel — ajuste de 1
linha.

### 5.5 Login — o melhor visual do sistema, e por quê

`login.html` + `.auth-page-bg` é, disparado, a tela mais bonita do SAUR:
gradiente azul institucional de 3 paradas em tela cheia, logo de 36px num
"badge" de vidro (`rgba(255,255,255,.12)` + `backdrop-filter: blur(4px)`),
cartão branco translúcido de raio 16px com `shadow-lg`, botão pill, animações
`fade-in` escalonadas, assinatura institucional no rodapé.

**Isso importa muito para este relatório por dois motivos:**

1. **Prova que o problema não é a paleta nem o Bootstrap.** Com exatamente os
   mesmos ingredientes já se produziu, neste repositório, uma tela com
   personalidade. A diferença é que ali as cores institucionais foram usadas como
   **superfície**, e não apenas como cor de borda e de botão.
2. **Cria uma queda de qualidade percebida logo no primeiro clique.** O
   solicitante vê a tela mais caprichada do sistema, digita a senha, e cai numa
   tabela branca sobre cinza. A régua de "o quanto este produto é cuidado" é
   estabelecida pelo login e imediatamente contrariada.

**A direção proposta na §6 é, em boa medida, "levar a linguagem do login para
dentro do Portal"** — sem repetir o gradiente de tela cheia (que seria pesado
para uma tela de trabalho), mas herdando o vocabulário: superfície colorida,
raio maior, elevação real, marca presente.

---

## 6. Direção de design proposta — "calma institucional"

**Princípio:** o Portal do Solicitante é usado por equipes de hospital em
situação de urgência, muitas vezes pelo celular, para acompanhar um pedido cujo
desfecho afeta a vida de um paciente. O tom visual correto não é "app moderno
divertido" nem "sistema administrativo": é **institucional, calmo, legível e
inequívoco** — o equivalente visual de um atendimento bem feito. Confiança, não
entusiasmo.

**Restrições auto-impostas** (mesmas de todos os relatórios anteriores):
Bootstrap 5.3.8 permanece; nenhuma biblioteca nova; nenhum asset externo/CDN
(a CSP de produção deixou de liberar Google Fonts em 2026-08-04 — não reintroduzir);
`app.css` continua sendo o único CSS; JS só em `static/js/*.js`; nenhuma cor
inventada fora de `--rs-*`.

### M1 — Faixa de cabeçalho de página (o maior ganho por linha de CSS)

**Problema atacado:** §4.2 (azul termina na navbar), §4.7 (marca ausente), §5.1
(título flutuando), §5.5 (queda de qualidade após o login).

Cada tela do Portal passa a abrir com uma faixa de identidade — largura total,
fundo em azul institucional suave, contendo o título da página, a linha de
contexto (equipe / número do processo) e a ação primária. A faixa emenda
visualmente na navbar em vez de deixar o corte seco entre o azul e o cinza.

Duas variantes possíveis, e é uma decisão do dono do produto (§10, decisão 4):

- **(a) Suave** — fundo `--rs-blue-light` (`#e3edf7`), texto `--rs-blue`
  (contraste **7,1:1**, calculado no Anexo C), borda inferior de 1px. Discreta,
  integra bem com cartões brancos, risco visual ~zero.
- **(b) Sólida** — gradiente `--rs-blue → --rs-blue-dark` com texto branco
  (contraste **8,4:1**), herdando literalmente o tratamento da navbar e do login.
  Muito mais presença; consome mais espaço vertical; em telas com pouco conteúdo
  pode ficar dominante.

Recomendação: **(a) para `lista.html` e `nova.html`, (b) apenas em
`detalhe.html`**, onde a faixa carrega o número do processo e faz o papel de
"capa do protocolo". **Os dois mockups permitem comparar:** o da lista usa a
variante (a) suave, o do detalhe usa a (b) sólida.

Opcionalmente, a faixa carrega o **logo Gota+Cruz como marca d'água** — o mesmo
`<svg>` inline já existente no fragment `layout :: marca`, a ~120px, com
`opacity: .06`, alinhado à direita. Custo: zero asset novo, ~10 linhas. É a forma
mais barata de fazer a marca existir dentro da tela. **Precisa de aval** (§10,
decisão 3) — marca d'água mal calibrada vira sujeira.

### M2 — Escala tipográfica de verdade

**Problema atacado:** §4.1.

Introduzir um degrau acima (`--saur-font-2xl`) e, principalmente, **passar a usar
o degrau do meio que já existe**:

| Papel | Hoje | Proposto |
|---|---|---|
| Título de página | `h3` ~1,75rem | `.pagina-titulo` — 1,75rem, `font-weight: 700`, `letter-spacing: -.02em` (mesmo tratamento do `.navbar-brand` e do `.auth-heading`, já no `app.css`) |
| Título de seção | `h6 text-muted` 1rem cinza | `.secao-titulo` — **1,25rem** (`--saur-font-lg`), peso 600, cor `--saur-text` (não cinza) |
| Rótulo de grupo | — | `.secao-rotulo` — 0,75rem, maiúsculas, `letter-spacing: .06em`, cor `--saur-text-muted` (o padrão que `.stat-label` já usa e funciona) |
| Resultado (Deferido/Indeferido) | `h5` 1,25rem | **1,75rem** (`--saur-font-xl`) no cartão de resultado |
| Número do card de resumo | 1,75rem | **1,5rem** — deixa de competir com o título da página |

O ganho aqui é desproporcional ao custo: dar 1,25rem e cor de texto real aos
títulos de seção resolve sozinho a maior parte da sensação de "tudo do mesmo
tamanho".

**Cuidado obrigatório:** todo valor precisa sair de um token da escala, senão
`DesignSystemFontSizeInlineTest` acende (e ele já falha de propósito hoje por
outros arquivos — não piorar a conta).

### M3 — Três níveis de profundidade

**Problema atacado:** §4.4.

```css
--saur-elev-0: none;                 /* superfície afundada (sunken), sem sombra */
--saur-elev-1: var(--rs-shadow);     /* cartão padrão — o que existe hoje */
--saur-elev-2: var(--rs-shadow-md);  /* o cartão que importa na tela */
```

Regra de uso, deliberadamente restritiva: **no máximo um elemento por tela usa
`--saur-elev-2`**. No Portal: o cartão de situação/resultado em `detalhe.html`, o
cartão do formulário em `nova.html`, o bloco de estado vazio (quando é a única
coisa na tela) em `lista.html`. O `<dl>` de dados cadastrais e a área de rolagem
do chat vão para `elev-0` + fundo `--saur-surface-sunken` — deixam de ser
"cartões" e viram superfícies de apoio, o que já reduz a contagem de caixas
brancas idênticas de 9 para 6 sem remover nada.

### M4 — Cor como superfície, não como borda

**Problema atacado:** §4.2, §4.3, §5.1, §5.3.

Duas mudanças:

**(i) O cartão de situação passa a ter superfície tintada**, não uma borda
esquerda de 4px: fundo `--rs-{cor}-light`, ícone dentro de um "token" circular de
64px (exatamente o mecanismo de `.stat-card .stat-icon`, que já existe e
funciona), texto na variante `-dark` da cor. **Obrigatório usar as variantes
`-dark` para o texto** — o Anexo C mostra que `--rs-green` sobre `--rs-green-light`
dá **4,11:1** e **reprova** em WCAG AA para texto pequeno; `--rs-green-dark` dá
5,82:1 e passa. O mesmo vale, de forma extrema, para o dourado (1,85:1 vs 5,93:1).

**(ii) Recalibrar a semântica de cor da faixa de resumo** para que âmbar volte a
significar "você precisa fazer algo":

| Card | Hoje | Proposto |
|---|---|---|
| Total enviadas | branco | branco (inalterado) |
| Aguardando triagem | âmbar | **neutro/azul** — é espera normal, não pendência do solicitante |
| Em análise | âmbar | **azul** (`stat-card-membros` já existe com `--rs-blue-light`) — informativo |
| Decididas | branco | branco (inalterado — agrega deferidos e indeferidos, neutro está certo) |
| Devolvidas | âmbar | **âmbar** (correto: exige ação do solicitante) |

Resultado: a faixa passa de 3 âmbares para **1**, e esse 1 volta a chamar
atenção de verdade. Isso **não** contradiz a Fase 1 (que removeu verde/vermelho
enganosos); refina o passo seguinte.

### M5 — Estados vazios desenhados, com ação

**Problema atacado:** §4.6.

Uma classe compartilhada `.estado-vazio`, com a estrutura que `indisponivel.html`
já provou funcionar: ícone grande (2–2,5rem) dentro de um círculo tintado, título
em 1,25rem, uma linha de orientação, e **a ação, quando existir**.

| Onde | Ícone | Título | Ação |
|---|---|---|---|
| Lista vazia (1º acesso) | `bi-send-plus` em círculo azul | "Você ainda não enviou nenhuma solicitação" | **Botão primário "Enviar minha primeira solicitação"** |
| Filtro sem resultado | `bi-funnel` em círculo cinza | "Nenhum resultado para este filtro" | Link "Limpar filtro" |
| Sem documentos | `bi-paperclip` em círculo cinza | "Nenhum documento anexado" | — |
| Sem mensagens | `bi-chat-dots` em círculo cinza | "Nenhuma mensagem ainda" | (campo já está logo abaixo) |

Custo: uma classe em `app.css` + 4 blocos de ~6 linhas. Nenhum asset novo,
nenhum ícone fora do bootstrap-icons já instalado (conferir cada um contra
`IconesBootstrapTest`).

### M6 — Um momento de destaque no resultado

**Problema atacado:** §5.3 (o desfecho tem o mesmo desenho da espera). É o item
de maior retorno emocional do plano.

Quando `situacao.rotulo` é **Deferido** ou **Indeferido**, o cartão de situação
deixa de ser "mais um cartão" e vira um **cartão de resultado**:

- superfície tintada em largura total (M4), elevação `--saur-elev-2` (M3);
- ícone de 64px no token circular, na cor `-dark`;
- título em **1,75rem** (M2) — "Deferido — Urgência renal reconhecida";
- o **número do processo** promovido a "chip de protocolo": `font-monospace`,
  fundo branco sólido, raio pill, ao lado do título — em vez de cinza-claro
  discreto no `<h1>`;
- o **botão de download** (comprovante SNT / ofício) como âncora visual do
  cartão: `btn-lg`, largura total no celular;
- entrada com `animate-fade-in` — animação que **já existe** e que
  `@media (prefers-reduced-motion: reduce)` **já neutraliza** (`app.css:1153`).
  Nenhuma animação nova.

**Calibragem do tom — decisão do dono do produto (§10, decisão 2).** Indeferido
é uma notícia difícil para uma família. A proposta deste relatório é
**simetria estrutural com assimetria cromática**: os dois resultados usam
exatamente o mesmo layout, mudando apenas cor e ícone. Nada de confete, emoji,
"Parabéns!" ou ilustração comemorativa. A dignidade do Indeferido é o teto do
Deferido.

---

## 7. Mockups

Dois arquivos HTML **estáticos e autossuficientes** em `docs/mockups/`, para
abrir com duplo clique no navegador (não precisam do sistema rodando, não têm
Thymeleaf, não fazem requisição nenhuma):

| Arquivo | Mostra |
|---|---|
| `docs/mockups/solicitante-dashboard-proposta.html` | `lista.html` — **ANTES** e **DEPOIS**, incluindo a versão de primeiro acesso (estado vazio) |
| `docs/mockups/solicitante-detalhe-proposta.html` | `detalhe.html` em dois estados — "Em análise" (rotina) e "Deferido" (momento de destaque) — **ANTES** e **DEPOIS** |

Cada arquivo é organizado como: barra fixa no topo com atalhos → bloco **ANTES**
→ bloco **DEPOIS**. Rolar de um para o outro é a forma de comparar.

**Limitações declaradas dentro dos próprios arquivos** (para ninguém tomá-los
como render fiel): Bootstrap e bootstrap-icons vivem em WebJars dentro do JAR e
não podem ser carregados de um arquivo solto, então o CSS necessário foi
reescrito de forma condensada e os ícones são `<svg>` inline simplificados — a
geometria não é idêntica à do bootstrap-icons. A paleta `--rs-*` é **copiada
verbatim** de `app.css`, e a fonte Inter é carregada por caminho relativo a
partir de `src/main/resources/static/fonts/` (funciona abrindo o arquivo de
dentro do repositório; fora dele, cai para a fonte do sistema). O texto de cada
estado foi copiado do `SolicitanteController.montarSituacaoPedido` real — os
mockups não inventam copy.

---

## 8. Plano faseado de implementação

Ordenado por **impacto percebido ÷ risco**. Cada fase é um commit/PR próprio e
não depende de fase posterior. Nenhuma fase toca controller, entidade, endpoint,
regra de negócio ou `name=` de campo.

> **Regra que vale para as seis fases:** rodar `.\test.ps1` (JDK 21) e, além
> disso, **subir a aplicação de verdade e olhar a tela** (`.\start.ps1`,
> http://localhost:3000, perfil SOLICITANTE) antes de abrir o PR. Ver §11 — a
> suíte não é capaz de reprovar nenhuma destas mudanças. **Nenhum merge sem
> revisão visual humana**, convenção já estabelecida no projeto.

### V1 — Tokens visuais (só `app.css`, nenhum template) · risco ~zero

Adicionar ao `:root`: `--saur-elev-0/1/2`, `--saur-font-2xl`, e os pares de
superfície tintada (`--saur-surface-ok/danger/attention/info` apontando para
`--rs-*-light`, com os respectivos `--saur-on-*` apontando para as variantes
`-dark`). **Nenhuma mudança visual acontece nesta fase** — nada consome os tokens
ainda. É o commit de infraestrutura que torna as fases seguintes pequenas.

Validação: `.\test.ps1`. `RecursosEstaticosCacheTest` cobre o arquivo; o hash de
conteúdo muda sozinho.

### V2 — Estados vazios (`.estado-vazio`) · risco baixo

M5 aplicado aos 4 pontos da tabela. Uma classe nova em `app.css` + edição de 4
blocos em `lista.html`/`detalhe.html`.

Atenção: `IconesBootstrapTest` (conferir que `bi-send-plus`, `bi-paperclip`,
`bi-funnel`, `bi-chat-dots` existem — todos já usados no projeto) e
`AcessibilidadeBotaoIconeTest` (o botão novo tem rótulo textual, então passa).
**Não** alterar o texto "Você ainda não enviou nenhuma solicitação" sem grepar
`src/test/java` antes.

### V3 — Faixa de cabeçalho + escala tipográfica · risco baixo-médio

M1 (variante suave) + M2 nas 4 telas do Portal. Duas classes novas
(`.pagina-cabecalho`, `.secao-titulo`/`.secao-rotulo`) e substituição das
marcações `h6 text-muted`.

Atenção: `TextoVisivelAcentuacaoTest` e `AcessibilidadeEstruturaTest` varrem os
templates — **manter `<main id="conteudo">`** e a ordem de landmarks. **Não mudar
o texto de nenhum título** nesta fase (os Page Objects do E2E localizam por
texto); mudar só a marcação em volta. Rodar também `.\e2e.ps1 -Headless`.

### V4 — Cartão de resultado (`detalhe.html`) · risco médio — **maior retorno**

M4(i) + M6. Mexe no bloco que a Fase 6 consolidou, então é a fase que exige mais
cuidado de leitura prévia.

**Invariantes que não podem mudar:** o template continua consumindo apenas
`${situacao.*}` e **nunca** recalcula a regra de status; `SituacaoPedidoView`
não ganha nem perde campo; o formulário de informação complementar continua
dentro do cartão quando `situacao.precisaAcao`; o link de download continua
apontando para `/solicitante/{id}/processo-anexo/{anexoId}` com a whitelist
intacta; `podeCancelar` segue sendo fonte única; `chatAtivoNestaTela=true`
permanece no model.

Atenção a `SolicitanteControllerTest`, `SolicitanteControllerSemTransacaoIntegrationTest`,
`SolicitacaoOnlineDetalheIntegrationTest`,
`SolicitanteInformacaoComplementarIntegrationTest` e
`SolicitanteCancelamentoTransacaoIntegrationTest` — vários fazem
`containsString(...)` sobre o HTML. Rodar `.\test.ps1` **e** `.\e2e.ps1 -Headless`.

Executar **sozinha**, em PR próprio.

### V5 — Faixa de resumo: cor semântica + profundidade · risco baixo-médio

M4(ii) + M3 aplicados a `lista.html`. Trocar `stat-card-andamento` por
`stat-card-membros`/`stat-card-total` em "Aguardando triagem" e "Em análise";
reduzir `.stat-number` para 1,5rem.

**Não tocar** nos atributos `data-filtro`/`data-categoria` nem no
`solicitante-lista.js` — o contrato do filtro client-side é independente da cor.

Depende da **decisão nº 1 do §10** (papel do dourado): se o dono do produto
escolher liberar o dourado como cor de marca, esta fase muda de forma.

### V6 — Formulário `nova.html` · risco médio

Ritmo visual real no lugar dos quatro `<hr>` (rótulos de grupo M2, superfícies
`elev-0` por seção), e tratamento da zona de upload (fundo `--saur-surface-sunken`,
borda tracejada, ícone, texto de apoio — puramente CSS em volta do `<input
type="file">` existente).

**⚠ Risco de teste alto e específico**, já documentado no relatório anterior e
ainda válido: `CamposDeFormulario` + `SolicitacaoOnlineCamposIntegrationTest`
derivam a lista de campos **do próprio HTML**, por regex sobre `th:field="*{...}"`
e `name="..."`. **Nenhum elemento auxiliar de UI pode ganhar atributo `name`** —
usar `id`/`data-*`. E `PortalSolicitantePage.java` (E2E) localiza
`input[name=pacienteNome]`, `input[name=pacienteRgct]`,
`input[name=dataSituacaoEspecial]`, `textarea[name=justificativaClinica]` e o
botão **"Enviar solicitação"** pelo texto exato — **não renomear o botão**.
Rodar `.\test.ps1` **e** `.\e2e.ps1 -Headless`.

### Ordem sugerida

**Bloco 1 (base invisível):** V1.
**Bloco 2 (maior retorno percebido, baixo risco):** V2 → V3.
**Bloco 3 (o momento de destaque):** V4, sozinha, PR próprio.
**Bloco 4 (refino):** V5 → V6.

Estimativa de esforço: V1 e V2 são pequenas (uma sessão curta cada); V3 e V5
médias; V4 e V6 exigem sessão dedicada com revisão visual.

---

## 9. O que propositalmente NÃO mexer

Lista fechada. Se uma fase parecer exigir algo daqui, a fase está errada.

**Estrutura e fluxo**
- A organização do fluxo, a timeline de 3 passos e a consolidação do cartão de
  situação — trabalho das Fases 1–10, em produção. Redesenho visual **não é**
  redesenho estrutural.
- `SituacaoPedidoView` e `SolicitanteController.montarSituacaoPedido`: fonte
  única da decisão de status. Nenhum template pode voltar a recalcular regra.
- `podeCancelar` como fonte única do botão de cancelamento.
- O whitelist de `TipoAnexo` (`COMPROVANTE_SNT` / `OFICIO_INDEFERIMENTO`) e a
  checagem de posse no download.
- O chat, seus 3 endpoints AJAX, `chat-solicitacao.js` e `chatAtivoNestaTela`.
- O rascunho, o `data-lock-submit`, o `beforeunload` e os modais de confirmação.

**Base técnica**
- **Bootstrap 5.3.8 permanece** (CLAUDE.md é explícito). Nada de Tailwind, nada
  de biblioteca de componentes, nada de framework de ícones novo.
- Nenhum asset externo, nenhuma origem nova na CSP, nenhuma fonte nova. A Inter
  auto-hospedada e o bootstrap-icons do WebJar são o conjunto disponível.
- JS continua em `static/js/*.js`; feedback continua via `mostrarToast()`.
- Thymeleaf: máximo 2 níveis de ternário em atributo; nunca `th:if` +
  `th:unless` no mesmo elemento; `/*[[...]]*/` só com `th:inline="javascript"`.

**Identidade e acessibilidade**
- **A paleta institucional não muda de matiz.** Azul `#1a4d8f` e dourado
  `#f5a623` são a identidade visual da Central de Transplantes do RS. Este
  relatório propõe usá-los **mais e melhor**, jamais substituí-los. Nenhuma cor
  fora de `--rs-*` entra no sistema.
- O logo Gota+Cruz não é redesenhado.
- **Nada pode piorar o contraste.** Todas as correções de WCAG AA já feitas
  (`--rs-gray-600` em vez de `gray-400`, `--rs-gold-dark`, `.text-white-70`)
  permanecem, e toda superfície tintada nova precisa passar pelo Anexo C.
- `data-densidade` e o par `--saur-*` continuam sendo o mecanismo — a proposta os
  **consome**, não os substitui.
- `prefers-reduced-motion` continua respeitado; nenhuma animação nova é
  introduzida (só reuso de `animate-fade-in`).
- Alvos de toque ≥44px nas ações primárias; nenhum botão só-ícone sem rótulo
  acessível.

---

## 10. Decisões que exigem aval explícito do dono do produto

Nenhuma linha de código deve ser escrita antes destas respostas.

> **APROVADO em 2026-08-06.** O dono do produto viu os dois mockups
> publicados (lista e detalhe, antes×depois) e respondeu explicitamente:
> decisão 1 → opção recomendada (A); decisão 2 → "vamos manter o tom de
> profissionalismo, o limite que você mostrou" (proposta deste relatório,
> sem elemento comemorativo); decisão 3 → opção recomendada (a, só
> bootstrap-icons). Na sequência, autorizou de forma ampla: *"suas
> recomendações são autorizadas"* — por isso as decisões 4–7 abaixo também
> ficam fechadas na opção recomendada de cada uma (faixa suave nas
> listas/formulário e sólida só no detalhe; marca d'água a 6% de opacidade
> como no mockup; mudança restrita ao Portal do Solicitante por ora; mais
> espaço vertical aceito).
>
> **IMPLEMENTADO em 2026-08-06** — ver o bloco de status no topo deste
> arquivo. As seis fases do §8 (V1–V6) foram executadas e mescladas em
> `main` pelo PR #42 no mesmo dia. Versões anteriores deste parágrafo
> diziam *"nenhuma decisão aqui foi implementada ainda"*, o que deixou de
> ser verdade poucas horas depois e induziu uma vistoria posterior ao erro
> de concluir que o relatório inteiro nunca havia saído do papel.



### Decisão 1 — O papel do dourado (a mais importante)

Hoje `--rs-gold` é, ao mesmo tempo, **cor da marca** e **cor de "atenção"**
(§4.3). São papéis incompatíveis, e é por isso que o conteúdo do Portal acaba
restrito a azul e cinza.

- **Opção A — o dourado continua sendo "atenção".** A identidade visual do
  conteúdo passa a ser construída só sobre o azul (faixas, superfícies, ícones),
  com o dourado reservado ao logo, à navbar e aos avisos. *Custo:* nenhum;
  compatível com tudo que existe. *Consequência:* a personalidade do Portal fica
  mais sóbria e monocromática.
- **Opção B — o dourado volta a ser cor de marca.** "Atenção" migra para um âmbar
  distinto e os detalhes dourados (filetes, ícones de destaque, o chip de
  protocolo) voltam a ser usáveis no conteúdo. *Custo:* exige uma cor nova na
  paleta e revisão de **todos** os `alert-warning`/`badge` âmbar do sistema
  inteiro — inclusive das telas do operador. Muito maior alcance.

**Recomendação:** **Opção A** agora. A Opção B é um projeto de identidade visual
próprio, não um redesenho de portal, e cruzaria o escopo das telas do operador
que acabaram de passar por 5 fases.

### Decisão 2 — Quão "comemorativo" pode ser o resultado Deferido

O M6 cria um momento visual de destaque no desfecho. Num sistema público de
saúde onde o outro desfecho possível é uma família recebendo uma negativa, o
limite disso é uma decisão de produto, não de design.

- **Proposta deste relatório:** simetria estrutural com assimetria cromática —
  mesmo layout para Deferido e Indeferido, mudando só cor e ícone. Sóbrio, claro,
  sem elementos de celebração.
- **Alternativa mais expressiva** (só se o dono quiser): destaque adicional
  exclusivo do Deferido — ícone maior, animação de entrada mais marcada.
- **Fora de cogitação, salvo pedido explícito:** confete, emoji, "Parabéns!",
  ilustração comemorativa.

### Decisão 3 — Ilustrações e imagens: de onde viriam

O projeto é **offline-first em assets** e tem CSP restritiva. Três caminhos, em
ordem crescente de custo:

- **(a) Só bootstrap-icons, ampliados e dentro de tokens circulares tintados.**
  É o que os mockups usam. Custo zero, nenhum asset novo, nenhuma mudança de CSP,
  coberto por `IconesBootstrapTest`. **Recomendado.**
- **(b) SVG inline próprio, derivado do logo Gota+Cruz** (a gota como base de uma
  pequena família de marcas: gota+relógio para "aguardando", gota+check para
  "deferido"). Custo: alguém precisa desenhar; some ~1–2 KB por tela; risco de
  ficar amador se mal executado. **Exige aval e, idealmente, alguém com traquejo
  de ilustração.**
- **(c) Ilustrações de biblioteca externa** (unDraw, Storyset e similares).
  **Não recomendado, e não deve ser presumido:** exigiria baixar e versionar
  arquivos de terceiros no repositório, verificar licença para uso por órgão
  público, e aumentaria o peso das páginas. Registrado aqui só para deixar
  explícito que **nenhuma imagem externa/CDN será usada sem aprovação**.

### Decisão 4 — Faixa de cabeçalho: suave ou sólida

Ver M1. **(a) suave** (`--rs-blue-light` + texto azul) é discreta e de risco
zero; **(b) sólida** (gradiente azul + texto branco) tem muito mais presença e
consome mais espaço vertical — o que, no celular, significa menos conteúdo acima
da dobra. Recomendação: (a) nas listas/formulário, (b) só no detalhe. Vale
aprovar olhando os mockups.

### Decisão 5 — A marca d'água do logo dentro da faixa

Logo Gota+Cruz a ~120px com `opacity: .06` na faixa de cabeçalho (M1). É a forma
mais barata de a marca existir na tela, e também a mais fácil de errar (vira
sujeira de fundo se a opacidade estiver alta demais). **Decidir olhando o
mockup.**

### Decisão 6 — Consistência com a área do operador

Estas mudanças são propostas **só para o Portal do Solicitante**. Os dois portais
externos e a área do operador passariam a ter linguagens visuais distintas — o
que é defensável (públicos e densidades diferentes, e `data-densidade` já existe
justamente para isso), mas é uma escolha consciente.

- **Opção A:** aplicar só ao Solicitante agora; avaliar o Avaliador depois, com o
  aprendizado. **Recomendada.**
- **Opção B:** estender ao Portal do Avaliador na mesma leva (as classes novas
  seriam reaproveitáveis, mas dobra a superfície de revisão visual).
- **Opção C:** estender à área do operador. **Não recomendado** — ela acabou de
  passar por 5 fases de UI e usa densidade `operacional`, com objetivos opostos.

### Decisão 7 — Aceitar mais espaço vertical

M1, M2 e M5 tornam as telas mais espaçosas: menos itens acima da dobra no
celular. Para o Solicitante (densidade `confortavel`, 1–3 pedidos, uso
esporádico) a troca vale a pena. **Confirmar** que isso é aceitável para o dono
do produto olhando os mockups no celular, não só no monitor.

---

## 11. Riscos de teste — e por que a suíte não protege nada aqui

**O ponto mais importante desta seção:** as 783 asserções da suíte e o E2E
completo podem passar **verdes** com o Portal visualmente destruído. Os testes
verificam status HTTP, model attributes, presença de `id`/texto e estrutura de
landmarks — **nenhum deles renderiza cor, tamanho, espaçamento ou sombra**. Já há
precedente documentado no CLAUDE.md: a fonte Inter auto-hospedada respondia 302
para `/login` (a fonte simplesmente não carregava na tela de login) e nenhum
teste acusou; só apareceu ao subir a aplicação e pedir o arquivo.

Portanto, para **toda** fase deste plano:

1. `.\test.ps1` (JDK 21) — garante que nada quebrou funcionalmente.
2. **Subir a aplicação de verdade** (`.\start.ps1`, http://localhost:3000) e
   percorrer as 4 telas do Portal logado como SOLICITANTE, **incluindo em
   viewport de celular** (DevTools).
3. `.\e2e.ps1 -Headless` nas fases V3, V4 e V6.
4. **Revisão visual humana antes do merge.** Convenção já estabelecida no
   projeto: mudança de UI extensa não vai a produção sem alguém olhar.

Testes de guarda que podem acusar, por fase:

| Teste | O que trava | Fases sensíveis |
|---|---|---|
| `IconesBootstrapTest` | ícone `bi-*` inexistente | V2, V4 |
| `AcessibilidadeBotaoIconeTest` | botão/link só-ícone sem rótulo | V2, V4 |
| `AcessibilidadeEstruturaTest` | `<main id="conteudo">` ausente/desbalanceado, `aria-*` apontando para `id` inexistente | V3, V4 |
| `DesignSystemFontSizeInlineTest` | `style="font-size:"` fora da escala de tokens (**já falha de propósito hoje** — não piorar) | V2, V3, V4 |
| `TextoVisivelAcentuacaoTest` | texto visível sem acento | V2, V3 |
| `IdsDuplicadosTest` | `id` repetido no mesmo template | V4, V6 |
| `RecursosEstaticosCacheTest` | recurso estático fora da política de cache | V1 |
| `CamposDeFormulario` + `SolicitacaoOnlineCamposIntegrationTest` | `name="..."` novo em `nova.html` | **V6** |
| `e2e/pages/PortalSolicitantePage.java` | `input[name=...]` e o botão "Enviar solicitação" por texto | **V6** |
| `SolicitanteControllerTest` e os 4 testes de integração do detalhe | `containsString(...)` sobre o HTML | **V4** |

---

## Anexo A — comandos que reproduzem os números deste relatório

```bash
cd /workspaces/urgencia

# §4.7 — o logo não aparece dentro do Portal
grep -c "<svg" src/main/resources/templates/solicitante/*.html      # 0 nos 4

# §4.5 — a escala de espaçamento do design system é quase inédita
grep -c "var(--saur-space"  src/main/resources/static/css/app.css   # 2
grep -c "var(--saur-radius" src/main/resources/static/css/app.css   # 13
grep -c "var(--saur-font"   src/main/resources/static/css/app.css   # 28

# §4.1 — o degrau de 1,25rem é usado uma vez nas três telas
grep -n 'class="h5' src/main/resources/templates/solicitante/*.html

# §4.1 — todo título de seção é h6 (= 1rem = tamanho do corpo)
grep -n '<h2 class="h6' src/main/resources/templates/solicitante/*.html

# §4.3 — o dourado nos dois papéis, marca e atenção
grep -n "rs-gold" src/main/resources/static/css/app.css

# §4.4 — um único nível de elevação em repouso
grep -n "box-shadow: var(--rs-shadow)" src/main/resources/static/css/app.css

# §5.1 — a faixa de resumo é 60% âmbar
grep -n "stat-card-andamento\|stat-card-total" \
     src/main/resources/templates/solicitante/lista.html

# §4.2 — o gradiente do body (#f8fafc → #f1f5f9)
grep -n "^body" -A 4 src/main/resources/static/css/app.css

# §6/M6 — prefers-reduced-motion já neutraliza as animações existentes
grep -n "prefers-reduced-motion" -A 10 src/main/resources/static/css/app.css
```

---

## Anexo B — tokens propostos (CSS pronto para a fase V1)

Para colar no `:root` de `app.css`, logo após o bloco de tokens de 2026-08-05.
**Nenhum matiz novo:** cada token aponta para um `--rs-*` que já existe.

```css
/* === Design system - nivel 2, leva de redesign visual do Portal (2026-08-06) ===
   Elevacao em 3 degraus. Regra de uso: no MAXIMO um elemento por tela usa
   elev-2 - se dois cartoes "importam", nenhum importa. */
--saur-elev-0: none;                  /* superficie de apoio (sunken) */
--saur-elev-1: var(--rs-shadow);      /* cartao padrao - o que existe hoje */
--saur-elev-2: var(--rs-shadow-md);   /* o cartao que importa na tela */

/* Superficies tintadas + a cor de texto OBRIGATORIA sobre cada uma.
   Nunca usar a variante base (--rs-green/--rs-gold/...) como texto sobre a
   superficie clara correspondente: ver Anexo C - o dourado da 1,85:1 e o
   verde 4,11:1, ambos reprovam em WCAG AA. As variantes -dark existem
   exatamente para isto. */
--saur-surface-ok:        var(--rs-green-light);   --saur-on-ok:        var(--rs-green-dark);
--saur-surface-danger:    var(--rs-red-light);     --saur-on-danger:    var(--rs-red-dark);
--saur-surface-attention: var(--rs-gold-light);    --saur-on-attention: var(--rs-gold-dark);
--saur-surface-info:      var(--rs-blue-light);    --saur-on-info:      var(--rs-blue);

/* Degrau tipografico acima do --saur-font-xl (1.75rem), para o titulo do
   cartao de RESULTADO (Deferido/Indeferido) - o unico ponto do Portal que
   justifica passar de 1.75rem. */
--saur-font-2xl: 2.25rem;
```

Classes novas previstas (V2/V3/V4), com os nomes usados nos mockups:
`.pagina-cabecalho`, `.pagina-titulo`, `.secao-titulo`, `.secao-rotulo`,
`.estado-vazio`, `.estado-vazio-icone`, `.cartao-resultado`,
`.cartao-resultado-icone`, `.chip-protocolo`, `.superficie-apoio`.

---

## Anexo C — contrastes das superfícies tintadas (calculados)

Calculados pela fórmula de luminância relativa da WCAG 2.x sobre os hex reais de
`app.css`. **WCAG AA exige 4,5:1 para texto normal e 3:1 para texto grande
(≥24px ou ≥19px em negrito).** Reconferir com ferramenta antes do merge.

| Texto | Sobre | Contraste | AA texto normal |
|---|---|---|---|
| `--rs-blue` `#1a4d8f` | branco | **8,39:1** | ✅ |
| `--rs-blue` `#1a4d8f` | `--rs-blue-light` `#e3edf7` | **7,08:1** | ✅ |
| branco | `--rs-blue` `#1a4d8f` | **8,39:1** | ✅ |
| `--rs-green` `#2d8546` | `--rs-green-light` `#e8f5ed` | **4,11:1** | ❌ **reprova** |
| `--rs-green-dark` `#1f6b36` | `--rs-green-light` `#e8f5ed` | **5,82:1** | ✅ |
| `--rs-red` `#c62828` | `--rs-red-light` `#fce8e8` | **4,78:1** | ✅ (por pouco) |
| `--rs-red-dark` `#8b1a1a` | `--rs-red-light` `#fce8e8` | **7,90:1** | ✅ |
| `--rs-gold` `#f5a623` | `--rs-gold-light` `#fef3e2` | **1,85:1** | ❌ **reprova gravemente** |
| `--rs-gold-dark` `#755a12` | `--rs-gold-light` `#fef3e2` | **5,93:1** | ✅ |

**Regra que sai daqui, e que a fase V4 precisa seguir à risca:** em superfície
tintada, o texto **sempre** usa a variante `-dark`. O ícone grande do cartão de
resultado, por ser ≥24px, poderia usar a variante base (limite de 3:1), mas o
verde a 4,11:1 e sobretudo o dourado a 1,85:1 tornam isso arriscado — usar
`-dark` também no ícone, por consistência e margem.

---

## 12. Fechamento

O Portal do Solicitante é **funcionalmente bom e visualmente mudo**. Não há um
erro grosseiro a corrigir; há uma camada inteira que nunca foi executada, porque
as dez fases anteriores — corretamente — priorizaram fluxo, clareza e
acessibilidade.

A boa notícia é que quase toda a infraestrutura necessária já foi construída e
está ociosa: os tokens `--saur-*`, a régua de densidade por portal, o
`tomBadge`, as animações com `prefers-reduced-motion`, o mecanismo de ícone em
token circular do `.stat-card`, e a prova de conceito de que a paleta institucional
pode produzir uma tela bonita — que é o `login.html`. A proposta é, em boa
medida, **usar o que já existe**.

O caminho mais curto entre a queixa e a satisfação do dono do produto são as
fases **V2, V3 e V4**: estados vazios com ação, uma escala tipográfica real com
faixa de identidade, e um momento de destaque no resultado. Isso não toca
nenhuma regra de negócio, nenhum controller e nenhum endpoint.

**Próximo passo:** abrir os dois mockups em `docs/mockups/`, responder as 7
decisões do §10 — em especial a nº 1 (papel do dourado) e a nº 2 (tom do
resultado) — e só então autorizar a fase V1.

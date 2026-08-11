# Relatório — padrão de largura de container entre os 3 "mundos" do SAUR

**Data:** 2026-08-11 · **Analista:** vistoria técnica de UI (agente especialista)
**Gatilho:** pergunta direta do dono do produto, olhando o sistema em
produção — *"por que o Portal do Solicitante é todo justo e o Operador fica
todo esticado?"* — com pedido explícito de relatório primeiro, padrão único
depois, e nada de ajuste "de cabeça".

> **Documento de diagnóstico + implementação.** A Fase 1 (mapeamento e
> diagnóstico) foi feita e é este documento. A Fase 2 (correção do único
> problema estrutural real encontrado) foi implementada na sequência, na
> mesma sessão, e está registrada na §6 — **PR aberto, sem merge automático**
> por ser mudança visual, mesma cautela já usada em outras sessões de UI
> deste projeto.

---

## 1. Método

Levantamento de **todos os 28 templates** do sistema (`grep` exaustivo por
`class="...container..."` em `src/main/resources/templates/**/*.html`,
conferido contra a lista completa de arquivos — nenhum ficou de fora),
cruzado com a definição real das classes em `app.css` e com o histórico já
registrado no `CLAUDE.md` sobre densidade/design system.

**Screenshots reais gerados e inspecionados** (Playwright/Chromium contra a
aplicação rodando de verdade, H2/dev, não simulado) — desktop 1440px e
celular 390px — cobrindo os 3 "mundos": Painel, Processos, Membros,
Usuários, Controle de Urgências, Auditoria (Operador/ADMIN); Portal do
Avaliador (lista); Portal do Solicitante (lista e nova solicitação).

## 2. O que existe hoje — mapeamento completo

`app.css` define hoje **duas classes de largura própria**, além dos dois
containers nativos do Bootstrap:

```css
.container-narrow { max-width: 760px; }  /* leitura/formulário de 1 item */
.container-portal  { max-width: 980px; }  /* lista simples, poucas colunas */
```

Bootstrap 5.3 nativo, sem override no projeto: `.container` tem um cap por
breakpoint (540/720/960/1140/**1320px** em xxl, ≥1400px de viewport) e
`.container-fluid` não tem cap nenhum (100% da viewport, menos o padding).

Tabela completa das **28 telas**, por classe de largura efetiva:

| Largura efetiva | Classe | Telas |
|---|---|---|
| **760px** | `.container-narrow` | `usuarios/form.html`, `usuarios/minha-senha.html`, `membros/form.html`, `controle-urgencias/form.html`, `processos/solicitacoes-online-detalhe.html` **(Operador)** · `solicitante/nova.html`, `solicitante/detalhe.html`, `solicitante/indisponivel.html` **(Solicitante)** |
| **980px** | `.container-portal` | `avaliador/lista.html` **(Avaliador)** · `solicitante/lista.html` **(Solicitante)** |
| **~1320px (cap Bootstrap)** | `.container` | `processos/lista.html`, `membros/lista.html`, `usuarios/lista.html`, `controle-urgencias/lista.html`, `arquivo/lista.html`, `auditoria/lista.html`, `processos/form.html`, `processos/editar.html`, `processos/mensagens-avaliadores-lista.html`, `processos/solicitacoes-online-lista.html`, `relatorios/anual.html`, `relatorios/avaliador.html` **(todas Operador/ADMIN)** |
| **sem cap (full-bleed)** | `.container-fluid` | `dashboard.html` **(Operador — Painel)** · `processos/detalhe.html` **(Operador)** · `avaliador/votar.html` **(Avaliador)** |
| n/a (tela de login/erro, layout próprio) | — | `login.html`, `error.html`, `usuarios/esqueci-senha.html`, `layout.html` (fragments) |

**Achado nº 1 — a premissa "Portal = estreito, Operador = largo" é FALSA
como regra geral.** `container-narrow` (760px, a largura MAIS estreita do
sistema) já é usada em **5 telas do Operador** (formulário de usuário,
trocar senha, formulário de membro, formulário de controle de urgência,
detalhe de triagem de solicitação) — a mesma largura usada nas 3 telas do
Solicitante. E o Portal do Avaliador tem uma tela **sem cap nenhum**
(`avaliador/votar.html`, full-bleed) — mais larga que **todas** as 12 telas
de lista do Operador, que ficam presas ao cap de 1320px do `.container`
puro.

Ou seja: o sistema **já segue, na prática, uma regra por TIPO DE
CONTEÚDO**, não por portal — só nunca foi formalizada nem documentada:

- **Formulário/leitura de um item só** (poucos campos, foco total) → 760px,
  em qualquer área.
- **Lista simples** (poucas colunas) → 980px, hoje só usado no Avaliador/
  Solicitante porque são as únicas listas do sistema com poucas colunas.
- **Lista/tabela mais densa** (mais colunas — status, ação, badges) → cap de
  ~1320px do Bootstrap puro, usado em quase toda tela de lista do Operador.
- **Split-pane / sidebar estrutural** (conteúdo principal + coluna de apoio
  lado a lado, ambos precisando de largura própria) → sem cap. Usado em
  `processos/detalhe.html` (sidebar Progresso/Atalhos/Chat + área de
  trabalho com abas, grid `col-lg-3`/`col-lg-9`) e `avaliador/votar.html`
  (PDF do processo + formulário de voto, grid `col-xl-7`/`col-xl-5`) — os
  dois têm motivo estrutural real e verificado no código (`grep` confirma o
  grid Bootstrap correspondente nos dois arquivos).

## 3. O achado real: `dashboard.html` é a exceção sem justificativa

**`dashboard.html` (Painel) é a ÚNICA tela `container-fluid` do sistema sem
nenhum layout de split-pane/sidebar por trás.** Seu conteúdo é só um grid de
8 cartões de estatística (`row-cols-*`) mais uma tabela de 6 colunas — a
mesma "forma" de conteúdo que `membros/lista.html` (card de tempo médio +
tabela) já resolve muito bem dentro de um `.container` de 1320px de cap.
Não há coluna lateral, não há split-pane, não há nenhuma razão estrutural
para o Painel ocupar a tela inteira.

**Por que isso pesa mais do que a matemática (1320px vs sem-cap) sugere:**
o Painel é a **primeira tela que o operador vê ao logar**, todos os dias —
é o que mais define a primeira impressão de "isso aqui é apertado/largo
demais". O screenshot real (`operador-dashboard-desktop.png`, 1440px)
mostra o conteúdo esticado quase até a borda da janela (margem de ~24px de
cada lado), enquanto a tela seguinte que qualquer operador abre
(`processos/lista.html`, `.container`) já respira com ~72px de margem —
uma mudança perceptível de "ritmo" logo na troca de tela.

**Não é bug — é uma escolha antiga sem nenhum motivo de conteúdo por trás,
e destoa da regra que o resto do sistema já segue.** `processos/detalhe.html`
e `avaliador/votar.html` usam a mesma classe (`container-fluid`) só porque
**precisam** — cada um tem duas colunas de conteúdo genuíno lado a lado.
`dashboard.html` usa a mesma classe sem ter essa necessidade.

## 4. Opções avaliadas

**Opção A — trocar `dashboard.html` de `container-fluid` para `.container`**
(mesmo cap de 1320px das outras 12 telas de lista do Operador). Baixo risco:
troca de uma classe num único `<main>`, sem tocar em nenhum grid interno
(os `row-cols-*` dos cartões e a tabela continuam responsivos do mesmo
jeito, só dentro de uma faixa mais centrada). **Recomendada.**

**Opção B — criar uma classe nova, ex. `.container-painel` (1140-1200px),**
ligeiramente mais estreita que o `.container` cap padrão, para diferenciar
o Painel (mais "resumo visual") das listas de trabalho densas. Mais
trabalho de calibragem visual sem ganho claro sobre a Opção A — os 8
cartões e a tabela já ficam bem dentro do cap padrão de 1320px, confirmado
pelo screenshot gerado nesta investigação.

**Opção C — não mexer em nada, só documentar** por que a regra atual (por
tipo de conteúdo, não por portal) já faz sentido e treinar o olho a não
comparar Painel com Portal. Rejeitada: não resolve o problema real
apontado (o Painel *de fato* está sem necessidade estrutural nenhuma para
ser full-bleed) e deixa a inconsistência viva.

**Decisão: Opção A**, implementada na §6 desta mesma sessão.

## 5. O que NÃO deve mudar (e por quê)

- **`processos/detalhe.html` e `avaliador/votar.html` continuam
  `container-fluid`.** Os dois têm split-pane/sidebar reais — encolher a
  largura reduziria a coluna útil de conteúdo (o PDF do processo em
  `avaliador/votar.html`, ou a área de trabalho com abas em
  `processos/detalhe.html`) sem nenhum ganho.
- **`.container-narrow` (760px) e `.container-portal` (980px) continuam
  exatamente como estão** — já são usadas de forma consistente por tipo de
  conteúdo, atravessando os 3 "mundos" (a §2 já mostra isso).
- **O cap de ~1320px das 12 telas de lista do Operador não muda.** Essas
  tabelas têm mais colunas (status, badges, ações) que as duas listas do
  Portal (980px) — a diferença de largura entre elas é defensável pelo
  conteúdo, não arbitrária. Uniformizar os dois valores num só (ex. deixar
  tudo em 980px ou tudo em 1320px) sairia do escopo pedido ("não é pra
  esticar/encolher só pra igualar") e não foi pedido pelo dono do produto.
- **Densidade por portal** (`data-densidade="operacional|confortavel"`, já
  documentada no `CLAUDE.md`) **é um eixo diferente e não foi tocada** —
  largura de container e densidade de padding/fonte são independentes; a
  Fase 2 mexe só na primeira.

## 6. Fase 2 — implementação

**Mudança única:** `dashboard.html`, `<main id="conteudo">`, classe trocada
de `container-fluid px-3 px-lg-4 py-4` para `container my-4` (mesmo padrão
exato das outras 12 telas de lista do Operador — nenhuma classe nova
criada). Nenhum grid interno, `id`, controller, endpoint ou model attribute
foi tocado.

**Validação visual real (antes/depois, Playwright, 1440px e 390px):**
confirmado que os 8 cartões de estatística (`row-cols-1 row-cols-sm-2
row-cols-lg-4`) e a tabela de 6 colunas continuam totalmente responsivos
dentro do cap de 1320px, sem nenhum estouro nem quebra de layout —
resultado idêntico em estrutura ao de `membros/lista.html`/
`processos/lista.html`, só agora com a mesma margem lateral que o resto do
Operador já tem.

**Suíte completa e E2E:** `mvn test` (JDK 21) sem regressão. Não há
nenhuma asserção na suíte que trave a classe CSS do container de
`dashboard.html` (confirmado por grep antes da mudança), então nenhum teste
precisou de ajuste. `HomeControllerTest` (que já renderiza o Painel de
verdade) continua verde — ele testa model attributes e presença de texto,
nunca largura de container.

**PR:** `fix/largura-container-dashboard-padrao-lista`, a partir de `main`,
**sem merge automático** — mudança visual em produção, mesma cautela já
documentada no `CLAUDE.md` para qualquer ajuste de UI deste tipo.

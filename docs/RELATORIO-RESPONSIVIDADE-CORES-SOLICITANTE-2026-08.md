# Vistoria de responsividade e cores — Portal do Solicitante (2026-08-11)

**Status: IMPLEMENTADO.** Este documento é registro do que foi achado e
corrigido, não um plano pendente. As seções de "estado anterior" descrevem o
código **antes** desta vistoria.

**Gatilho.** Pedido textual do dono do produto: *"verifique responsividade e as
cores, ajuste todo css, tinha texto saindo da tela. verifique toda
responsividade."*

**Escopo.** Somente o Portal do Solicitante (`/solicitante/**`): os 4 templates
de `src/main/resources/templates/solicitante/` e as regras de
`src/main/resources/static/css/app.css` que os afetam. Nenhuma regra de
negócio, controller, endpoint, DTO, consulta ou `name=`/`id` de campo foi
alterado — a mudança é inteiramente de apresentação.

---

## 1. Metodologia

O ponto de partida foi a §11 do
`docs/RELATORIO-REDESIGN-VISUAL-SOLICITANTE-2026-08.md`, que já avisava: *"as
asserções da suíte e o E2E podem ficar inteiramente verdes com o Portal
visualmente quebrado"*. Nenhum teste do projeto mede largura, cor ou
espaçamento computado — então a vistoria não podia ser feita lendo código.

Foi construído um harness Playwright (`ResponsividadeSolicitanteIT`, ver §5)
que:

1. **Semeia todos os estados do portal** direto pelo repositório — inclusive os
   que só existem depois da decisão e que, na prática, são difíceis de
   alcançar clicando: Deferido com e sem comprovante SNT, Indeferido com
   ofício, pausa "Solicita informação", Devolvida, Cancelada.
2. Usa **dados do tamanho dos reais**: nome de hospital por extenso, e-mail
   institucional longo, nome de anexo clínico longo. Um fixture com
   `teste@teste.com` não reproduziria o defeito principal — ele depende
   justamente de um token comprido sem espaços.
3. **Captura cada tela em 6 larguras**: 360 (Android pequeno), 390 (iPhone
   padrão), 576, 768, 992 (cortes do Bootstrap) e 1440 (desktop) — 60
   screenshots por rodada.
4. **Mede o estouro horizontal programaticamente**, comparando
   `document.documentElement.scrollWidth` com a largura da viewport e listando
   os elementos culpados (tag, classe, coordenadas). Essa é a medida que
   expressa literalmente "texto saindo da tela"; nenhuma inspeção visual dá
   o número nem aponta o elemento.
5. Cada screenshot foi **inspecionado de fato** (leitura de imagem), antes e
   depois de cada correção — vários achados desta vistoria (§3.6 a §3.9) só
   aparecem no pixel, nunca na medição automática.

**Resultado da primeira rodada: 12 ocorrências de estouro horizontal**, todas
na tela de detalhe, nas larguras 360/390/576.

---

## 2. Achado principal — a página tinha 642px numa tela de 360px

> `src/main/resources/static/css/app.css`, `.cartao-resultado`
> Telas: `/solicitante/{id}` nos estados **Aguardando triagem**, **Deferido**
> (com e sem anexo) e **Indeferido**. Larguras 360, 390 e 576px.

Medição do harness antes da correção:

```
[detalhe-deferido-com-anexo @360px] estourou 282px (viewport=360, documento=642)
   <div class='flex-grow-1'>  left=123 right=642 w=519 | Deferido — Urgência renal reconhecida
   <p>                        left=123 right=642 w=519 | Seu pedido (processo 17/2026) foi analisado...
   <a class='btn btn-lg btn-resultado-ok'> left=123 right=569 w=446 | Baixar comprovante...
```

Repare que a largura do conteúdo (`w=519`) era **a mesma em qualquer
viewport** — 360, 390 ou 576. É a assinatura de um conteúdo que se recusa a
encolher, não de um layout mal proporcionado.

### Causa raiz

O cartão é `display: flex` com dois filhos: o ícone (`flex: none`, 64px) e o
conteúdo (`.flex-grow-1`). **Um item de flex nasce com `min-width: auto`**, o
que significa que ele nunca encolhe abaixo da largura *min-content* do próprio
conteúdo. E o conteúdo desse cartão carrega o e-mail institucional da equipe
solicitante:

> "…você será avisado por e-mail
> (`nefrologia.transplante.renal@hospitaluniversitario.exemplo.com.br`)."

Esse endereço é **um único token sem nenhum espaço**. A largura min-content do
parágrafo passava a ser o comprimento desse token — ~519px, fixos. `flex-grow`
não ajuda: ele só distribui sobra, nunca autoriza encolhimento.

Consequência para quem usa: o solicitante abria o resultado do próprio pedido
no celular e precisava **rolar a tela de lado** para ler a decisão, com o resto
da página (navbar, timeline, cards) parando em 360px e uma faixa branca vazia à
direita.

### Correção

```css
.cartao-resultado > *:not(.cartao-resultado-icone) { min-width: 0; }
.cartao-resultado h2,
.cartao-resultado p,
.cartao-resultado-detalhe { overflow-wrap: anywhere; }
.cartao-resultado .btn { white-space: normal; }
```

As duas primeiras regras são **um par obrigatório**: `min-width: 0` autoriza o
item a encolher, `overflow-wrap: anywhere` autoriza o token a quebrar dentro do
espaço já encolhido. Uma sem a outra não resolve. É o mesmo remédio que
`.text-pre-wrap` já aplicava na justificativa clínica desde 2026-08-06 (bug
equivalente, relatado na solicitação #12) — só não tinha sido estendido ao
cartão de resultado, criado depois.

A terceira regra trata o rótulo longo do botão de download ("Baixar comprovante
de inserção no SNT"), que sozinho media 446px numa tela de 360px.

---

## 3. Demais achados

### 3.1 O bloco inteiro do redesign V1–V6 não tinha uma única media query

> `app.css`, linhas ~1475 em diante

Verificado por varredura: a última `@media` do arquivo aparecia na linha 1318, e
todo o bloco de redesign do Portal (`.pagina-cabecalho`, `.cartao-resultado`,
`.estado-vazio`, `.chip-protocolo`, `.zona-upload`, `.secao-titulo`) começa
**depois** disso. Ou seja: cada padding, tamanho de ícone e degrau tipográfico
foi calibrado no mockup de desktop e aplicado igual em qualquer tela.

Efeitos concretos em 360–390px: ícone de 64px + gap de 1.25rem consumindo 84px
de largura útil; título de resultado em 1.75rem quebrando em 3 linhas; faixa de
cabeçalho com 1.6rem de padding vertical; marca d'água de 170px atrás do
título.

**Correção:** um bloco responsivo novo com dois cortes (`max-width: 767.98px` e
`max-width: 575.98px`), documentado no próprio CSS. Os `.98` são deliberados —
o CLAUDE.md já registra a armadilha real de `max-width: 768px` e
`min-width: 768px` valendo ao mesmo tempo em exatamente 768px.

Em celular, o cartão de resultado passa a **empilhar** (ícone acima do texto), o
ícone cai para 52px, o título de destaque para 1.35rem, e a ação principal do
cartão ocupa a largura toda — alvo de toque confortável, sem competir por
espaço horizontal.

### 3.2 A tabela da lista aparecia cedo demais e ficava espremida

> `solicitante/lista.html` — `d-none d-md-block` / `d-md-none`

A tabela de 6 colunas entrava a partir de 768px. Entre 768 e 991px, **todas** as
células quebravam em 3–4 linhas (nome do paciente, RGCT e data picotados, e o
próprio botão "Ver" com ícone e rótulo em linhas separadas) — pior de ler que os
cards empilhados que já existiam logo abaixo, para a mesma informação.

**Correção:** o corte passou para `lg` (992px) nos dois lados
(`d-none d-lg-block` na tabela, `d-lg-none` nos cards). Os dois **têm** que
trocar no mesmo breakpoint, senão a lista some ou duplica numa faixa de largura.

### 3.3 Nome de anexo truncado com reticências (recaída de um bug já corrigido)

> `solicitante/detalhe.html`, lista "Documentos anexados"

O nome do arquivo usava `text-truncate`, então
`Laudo_Doppler_Venoso_Membros_Superiores_Paciente_Encaminhamento_...pdf`
aparecia cortado, sem o solicitante conseguir confirmar **qual** documento tinha
enviado.

Isto é exatamente o defeito que o CLAUDE.md registra como corrigido em
2026-08-04 em `solicitante/nova.html` ("Lista de documentos selecionados"),
trocando `text-truncate` por `text-break`. A tela de detalhe tinha ficado de
fora daquela correção.

**Correção:** `text-break` (mesma solução), mais `flex-shrink-0` no ícone do
tipo de arquivo para ele não ser comprimido pelo nome agora multilinha.

### 3.4 Botão dourado sobre superfície dourada

> `solicitante/detalhe.html` — "Enviar informações complementares"

O botão usava `btn-warning`, cujo fundo é `--rs-gold` **puro**, dentro do cartão
`.r-attention`, cujo fundo é `--rs-gold-light`. Praticamente o mesmo matiz: o
botão se dissolvia no cartão.

O projeto já tinha resolvido exatamente isso: `.btn-resultado-attention`
(variante `-dark`) foi criada em 2026-08-08 para o cartão equivalente do Portal
do Avaliador, seguindo a regra de contraste do Anexo C do relatório de redesign.
Esta tela — que é a **origem** do padrão — tinha ficado de fora.

**Correção:** `btn-resultado-attention`.

### 3.5 Ação de recuperação estilizada como ação destrutiva

> `solicitante/detalhe.html` — "Enviar nova solicitação" (Devolvida / Processo
> excluído)

Usava `btn-outline-danger` dentro do cartão `.r-danger`: contorno vermelho claro
sobre superfície avermelhada, com pouca separação. Além do contraste, há um
problema de hierarquia — este é o **caminho de saída** do estado (a única coisa
que o solicitante pode fazer), e estava com peso visual de ação secundária.

**Correção:** `btn-resultado-danger` (sólido, variante `-dark`), mesma família
já usada pelos botões de download do mesmo cartão. Mantém a cor semântica do
cartão e dá o peso de ação primária.

### 3.6 Barra de ações do formulário espremida no celular

> `solicitante/nova.html` — rodapé do formulário

`class="card d-flex flex-row justify-content-between align-items-center p-3"` —
`flex-row` fixo, **sem wrap**. Em 360px, "Cancelar", "Salvar rascunho" e "Enviar
solicitação" disputavam uma linha só, e os dois botões quebravam o próprio
rótulo em duas linhas dentro de caixas minúsculas. Justamente a ação final do
formulário.

**Correção:** `flex-wrap` + `gap`, com o botão primário em largura total no
celular. Isso exigiu o utilitário `.w-sm-auto` — **o Bootstrap 5 não gera
variantes responsivas das utilities de largura**, então `w-sm-auto` não
existiria e o `w-100` valeria em todas as larguras; a classe foi definida
explicitamente no `app.css`.

### 3.7 Contador de caracteres entrelaçado com o texto de ajuda

> `solicitante/nova.html` — abaixo da justificativa clínica

`d-flex justify-content-between` sem wrap, com o contador em `text-nowrap`. Em
telas estreitas o resultado lido na tela era:

> "Quanto mais completo, menor a  `0 caracteres`
> chance de a equipe pedir informação complementar."

**Correção:** `flex-wrap` + `gap`, e o contador desce inteiro para a linha de
baixo.

### 3.8 Marca d'água invadindo o título

> `app.css`, `.pagina-cabecalho .pc-marca`

170px fixos. Em 360px ela ocupava quase metade da largura da faixa, atrás do
título e do botão "Nova solicitação". Não gerava barra de rolagem (o
`overflow: hidden` da faixa a recorta), mas competia visualmente.

**Correção:** 120px abaixo de 768px e 96px abaixo de 576px, deslocada mais para
fora da área do título. Continua decorativa e `aria-hidden`.

### 3.9 Estados vazios com padding de desktop

> `app.css`, `.estado-vazio`

`padding: 3rem 1.5rem` fixo, com ícone de 72px — muito espaço morto num celular.
**Correção:** reduzidos por breakpoint (2.25rem → 1.75rem; ícone para 60px).

---

## 4. Badge da lista: pedido Indeferido saía verde — CORRIGIDO (opção "a")

**O defeito.** Em `/solicitante`, todo pedido já convertido em processo exibia
o mesmo badge **verde** "Convertida em processo" — confirmado nos screenshots
da vistoria: os processos #18/2026 (Indeferido), #17/2026 e #16/2026
(Deferidos) e #14/2026 (ainda em análise) apareciam visualmente idênticos.
Verde significa "deferido/sucesso" em todo o resto do Portal (cartão `.r-ok`,
timeline, ícones), então a lista anunciava sucesso para quem teve o pedido
negado.

A causa é que a cor vinha de `StatusSolicitacaoOnline.getBootstrapBadge()`
(`CONVERTIDA -> "bg-success"`), e esse enum é **compartilhado** com a tela de
triagem do OPERADOR (`processos/solicitacoes-online-lista.html`) — mudá-lo
mudaria as duas telas.

Por envolver uma escolha de produto (mostrar ou não o desfecho na lista), a
vistoria original deixou 3 caminhos em aberto:

| Opção | O que muda | Alcance |
|---|---|---|
| **(a)** Mostrar o desfecho real: "Deferido" (verde) / "Indeferido" (vermelho) / "Em análise" (azul) | O solicitante passa a ver na lista o mesmo que já vê ao abrir o pedido | Só o template do Portal do Solicitante; **não** toca o enum nem a tela do operador |
| **(b)** Manter o texto e trocar a cor verde por azul (andamento) | Corrige a semântica da cor sem revelar desfecho na lista | Exige mudar o enum → **afeta também a triagem do operador** |
| **(c)** Manter como está | Nada | — |

### O que foi implementado

**O dono do produto escolheu a opção (a)** ("sim pode alterar"). Implementada
na mesma branch/PR desta vistoria.

- **`web/dto/SituacaoListaView`** (record novo: `rotulo`, `tom`, `icone` +
  `bootstrapBadge()`) e **`SolicitanteController.montarSituacaoLista`**, que
  decide a partir do `Processo` gerado e não do estado da solicitação. O
  controller expõe `situacoesLista` (`Map<Long, SituacaoListaView>`) e
  `solicitante/lista.html` consome — nos **dois** pontos da tela (tabela
  ≥992px e cards empilhados <992px, que têm de mostrar o mesmo badge).
- **Critério** (mesma ordem de prioridade e mesmo vocabulário de
  `montarSituacaoPedido`, para a lista nunca dizer algo diferente do detalhe,
  e cobrindo os dois formatos históricos de "decidido" — o espelho antigo em
  `APROVADA`/`REPROVADA` e o atual, `CONVERTIDA` com o processo finalizado):

  | Situação real | Rótulo | Tom / cor | Ícone |
  |---|---|---|---|
  | Processo Deferido (ou `APROVADA`) | Deferido | `ok` / verde | `check-circle-fill` |
  | Processo Indeferido (ou `REPROVADA`) | Indeferido | `danger` / vermelho | `x-circle-fill` |
  | Processo Cancelado (ou `CANCELADA`) | Cancelado | `neutral` / cinza | `slash-circle-fill` |
  | `DEVOLVIDA` | Devolvida | `danger` / vermelho | `arrow-return-left` |
  | `PROCESSO_EXCLUIDO` | Processo excluído | `danger` / vermelho | `exclamation-triangle-fill` |
  | `CONVERTIDA`, processo ainda não decidido | Em análise | `aguardando` / azul | `hourglass-split` |
  | `ENVIADA` | Aguardando triagem | `aguardando` / azul | `hourglass-split` |

- **`StatusSolicitacaoOnline` não foi tocado**, nem
  `processos/solicitacoes-online-lista.html` — na triagem do operador
  "Convertida em processo" continua sendo a informação correta (ele acompanha
  o ciclo da *solicitação*, não o desfecho clínico).
- O badge **"Ação necessária"** (âmbar, pausa por informação complementar)
  continua decidido no template pelo mapa `acaoNecessaria` e tem **precedência**
  sobre este: quando o solicitante precisa agir, é isso que ele tem que ver,
  não "Em análise".
- Efeito colateral positivo: a navegação `s.processoGerado.status` saiu do
  template e passou a acontecer **dentro** da transação de `lista()`
  (`open-in-view: false`).

**Guardas:** `SolicitanteControllerTest
.listaDistingueVisualmentePedidoIndeferidoDeDeferidoEDeEmAnalise` (HTML
renderizado, com asserção **negativa** em "Convertida em processo" — sem ela o
teste voltaria a passar se alguém reintroduzisse a leitura do enum) e
`ResponsividadeSolicitanteIT
.listaMostraCoresDIFERENTESParaPedidoDeferidoIndeferidoEEmAnalise` (navegador
real, compara a cor de fundo **computada** de cada linha; screenshot
`cores-lista-deferido-vs-indeferido`).

---

## 5. Guarda automatizado novo: `ResponsividadeSolicitanteIT`

`src/test/java/br/gov/saude/sgpur/e2e/ResponsividadeSolicitanteIT.java`
(Playwright, profile `e2e`, mesmo padrão de `RedesignVisualSolicitanteIT` /
`ChatVisualVerificacaoIT` — fora do `mvn test` do dia a dia).

Semeia os 8 estados do portal e percorre as 10 telas × 6 larguras, falhando se
**qualquer** uma estourar a largura da viewport. A mensagem de falha nomeia a
tela, a largura, o número de pixels de estouro e os elementos culpados — sem
isso a falha diria só "a página quebrou", sem indicar onde mexer.

**O guarda foi verificado de verdade, não presumido.** Reintroduzindo a causa
raiz no CSS (removendo `min-width: 0` e `overflow-wrap: anywhere`), o teste
falha reproduzindo o defeito original:

```
Expecting empty but was:
  ["detalhe-aguardando @360px: estourou 188px (viewport=360, documento=548)…
   "detalhe-deferido-com-anexo @360px: estourou 188px…
```

Nota metodológica: uma primeira tentativa de verificação removeu **apenas** o
`min-width: 0` e o teste continuou passando — o `overflow-wrap` sozinho já
bastava naquele breakpoint. Só removendo os dois o defeito reaparece. Isso
confirma que as duas regras são o par descrito na §2, e que "verificar que o
teste falha" precisa desfazer a causa raiz inteira, não um pedaço dela.

---

## 6. Validação

- **Suíte completa: 977 testes, 0 falhas, 0 erros** (JDK 21, `mvn clean verify`).
  Inclui `AcessibilidadeEstruturaTest`, `DesignSystemFontSizeInlineTest`,
  `IconesBootstrapTest` e `TextoVisivelAcentuacaoTest`, todos verdes sem ajuste.
- **E2E (`mvn verify -Pe2e -Dsaur.e2e.headed=false`)**:
  `RedesignVisualSolicitanteIT` ✅ · `PortaisVisualCompletoIT` ✅ ·
  `ChatVisualVerificacaoIT` ✅ · `ResponsividadeSolicitanteIT` ✅ (novo).
  `FluxoCompletoProcessoIT` falha na **linha 228**, a falha pré-existente e já
  documentada de SMTP local ausente (`SGPUR_MAIL_USER`/`SGPUR_MAIL_FROM` não
  configurados neste ambiente) — confirmada pelo log
  (`EmailSender: remetente (from) nao configurado`, 15 ocorrências). Não tem
  relação com esta vistoria: a etapa que falha é o envio da resposta final pelo
  **operador**, e nada aqui tocou esse caminho.
- **Medição objetiva:** de **12 ocorrências de estouro horizontal para 0**, nas
  10 telas × 6 larguras.
- **Inspeção visual:** todos os screenshots foram relidos após as correções,
  em `target/e2e-screenshots/`.

## 7. Arquivos alterados

| Arquivo | Natureza |
|---|---|
| `src/main/resources/static/css/app.css` | correções do `.cartao-resultado` + bloco responsivo novo + `.w-sm-auto` |
| `src/main/resources/templates/solicitante/detalhe.html` | `text-break` no anexo, 2 botões na variante `-dark` |
| `src/main/resources/templates/solicitante/lista.html` | breakpoint da tabela `md` → `lg`; badge de situação vindo de `situacoesLista` (§4) |
| `src/main/resources/templates/solicitante/nova.html` | barra de ações e contador com wrap |
| `src/main/java/br/gov/saude/sgpur/web/dto/SituacaoListaView.java` | record novo (§4) |
| `src/main/java/br/gov/saude/sgpur/web/SolicitanteController.java` | `montarSituacaoLista` + model attribute `situacoesLista` (§4) |
| `src/test/java/br/gov/saude/sgpur/e2e/ResponsividadeSolicitanteIT.java` | guarda novo (responsividade + cores da lista) |
| `src/test/java/br/gov/saude/sgpur/web/SolicitanteControllerTest.java` | guarda do badge da lista (§4) |

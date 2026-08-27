# Relatório — Duplicação de solicitação preemptiva e cobertura do badge de tipo (2026-08-27)

Investigação de dois problemas relatados em produção depois do merge dos
PRs #126/#127 (feature de paciente preemptivo) e do backfill manual da
coluna `paciente_rgct` (`NOT NULL` destravado).

## Problema 1 — solicitação preemptiva apareceu DUPLICADA

### Causa raiz confirmada

**`solicitante/nova.html` marca o `<form>` com `data-lock-submit`, mas nunca
inclui o script que implementa essa trava (`layout :: lockSubmitScript`,
`static/js/lock-submit.js`).** O atributo `data-lock-submit` é só um
marcador (`data-*`) — sem o script que o lê, o navegador o ignora por
completo. Resultado: o botão "Enviar solicitação" **nunca é desabilitado**
depois do clique, e como é um `<form method="post" enctype="multipart/form-data">`
clássico (POST + redirect, sem AJAX), a página inteira **permanece
interativa durante todo o tempo do upload/processamento** (pode levar vários
segundos com documentos anexados) — uma janela real em que um segundo
clique no mesmo botão dispara um segundo POST idêntico, criando uma segunda
`SolicitacaoOnline`.

Evidência, não suposição:

1. `grep -n "lockSubmitScript" src/main/resources/templates/*/*.html` mostra
   que **todo outro template** que usa `data-lock-submit`
   (`processos/detalhe.html`, `processos/editar.html`, `processos/form.html`,
   `solicitante/detalhe.html`) inclui
   `<script th:replace="~{layout :: lockSubmitScript}"></script>`.
   `solicitante/nova.html` é a **única exceção**: só inclui `layout ::
   scripts`, `confirmarAcaoScript`, `avisoSairScript` e o próprio
   `solicitante-nova.js` — nunca `lockSubmitScript`.
2. `git log --oneline -S "data-lock-submit" -- src/main/resources/templates/solicitante/nova.html`
   aponta o commit `9e616c4` ("Fase 5 - formulário de Nova solicitação
   reduz devolução na origem") como quem introduziu o atributo no form,
   com a mensagem de commit citando explicitamente *"data-lock-submit no
   formulario (ja existe static/js/lock-submit.js, ...)"* — ou seja, a
   intenção sempre foi reaproveitar a trava genérica, mas o `<script>`
   correspondente nunca foi adicionado a este template específico. É o
   mesmo padrão de bug já documentado no `CLAUDE.md` para
   `/*[[expr]]*/` sem `th:inline="javascript"`: um wiring de duas partes
   (atributo no HTML + script incluído) onde só metade foi feita, sem
   nenhum erro visível — o formulário continua funcionando normalmente no
   caminho feliz (um único clique), só a proteção fica inerte.
3. Nenhum teste de integração cobria esse caminho (nem havia como: é um
   comportamento client-side puro, invisível para `@WebMvcTest`/`MockMvc`).
   Não havia, tampouco, nenhuma trava equivalente no backend
   (`SolicitacaoOnlineService.criar` grava sem qualquer checagem de
   duplicidade recente).

Este mesmo commit (0a425f3, "convite duplicado + melhorias visuais do
relatorio") que **criou** `lock-submit.js` foi motivado por um incidente real
de duplo-clique gerando convite duplicado ao avaliador — a mesma classe de
bug, só que noutra tela, já tinha acontecido antes; a proteção genérica foi
criada para isso, mas o rollout nunca chegou completo a `solicitante/nova.html`.

### Por que isso explica o relato do usuário

O usuário gerou UMA solicitação marcando preemptivo — telas com anexos e
mais campos tendem a levar mais tempo de upload/processamento no servidor
(mais um fator que amplia a janela de duplo clique), tornando esse
formulário específico um alvo mais provável para o sintoma, mesmo a causa
raiz não tendo nada a ver com o campo "preemptivo" em si (o mesmo bug existe
para qualquer tipo de solicitação enviada por este formulário).

### Correção aplicada

1. **Frontend (correção da causa raiz):** adicionado
   `<script th:replace="~{layout :: lockSubmitScript}"></script>` a
   `solicitante/nova.html` — mesma convenção já usada nos outros 4
   templates. O botão "Enviar solicitação" passa a ficar desabilitado
   (com spinner "Enviando solicitação...") assim que o `submit` dispara,
   fechando a janela de duplo clique client-side.
2. **Backend (defesa em profundidade, cobre reenvio manual/refresh/rede
   instável, não só duplo clique):** `SolicitacaoOnlineService.criar` passa
   a rejeitar (com `IllegalStateException`, mesmo caminho de erro amigável
   já usado para as demais validações do método) uma nova solicitação do
   mesmo usuário com o mesmo CPF de paciente enviada nos últimos 15
   segundos (`SolicitacaoOnlineRepository
   .existsByUsuarioSolicitanteIdAndPacienteCpfAndDataEnvioAfter`). Não
   depende de nenhum estado client-side (token, sessão), então cobre
   também o caso de o solicitante reenviar manualmente (F5 num POST,
   reabrir aba antiga) — únicas duas solicitações genuinamente distintas
   para o mesmo paciente feitas propositalmente em menos de 15s seriam uma
   coincidência extrema, não um caso de uso real.

## Problema 2 — cobertura do badge de tipo (`RotuloProcesso.tipoCurto`)

Levantamento completo de `templates/**/*.html` por `preemptivo`/`tipoCurto`.

### Já tinham o badge (confirmado, PR #126/#127)
- `processos/solicitacoes-online-lista.html`
- `processos/solicitacoes-online-detalhe.html`
- `arquivo/lista.html`
- `processos/lista.html`
- `avaliador/lista.html`
- `avaliador/votar.html`
- `processos/detalhe.html`
- `processos/editar.html`
- `solicitante/nova.html` (formulário de criação, mostra o próprio tipo
  sendo escolhido)

### Faltando o badge (achado desta investigação, corrigido nesta sessão)
- **`solicitante/lista.html`** ("Minhas solicitações", Portal do
  Solicitante) — nem a tabela desktop nem os cards mobile mostravam o tipo
  do pedido.
- **`solicitante/detalhe.html`** (detalhe de UMA solicitação own do
  Portal do Solicitante) — o cabeçalho mostrava nome do paciente + número
  do processo, mas não o tipo.
- **`dashboard.html`** (Painel do operador) — a linha de cada processo na
  tabela principal não tinha o badge (só existe em `processos/lista.html`,
  uma tela diferente). Confirma a suspeita do pedido: os TÍTULOS gerais do
  Painel continuam "Urgência Renal" (decisão de produto já fixada, não
  mexida), mas a LINHA de cada processo preemptivo estava sem indicação
  nenhuma.

### Já estava correto, verificado nesta investigação
- **Relatório Anual (PDF)** — `RelatorioAnualService` já tem uma coluna
  "Tipo" na tabela de processos do ano (comentário no código já cita
  "Coluna Tipo (paciente preemptivo, 2026-08-27)"), a pendência mencionada
  no plano original do PR #126 já havia sido implementada.
  `relatorios/anual.html` (a TELA que só gera o PDF) não lista processos
  individualmente — não há nada a corrigir ali.
- E-mails: não existe hoje nenhum e-mail que liste MÚLTIPLAS solicitações
  de uma vez (cada e-mail é sempre sobre um processo específico, e já usa
  `RotuloProcesso` no assunto/corpo) — não há "lista" de e-mail a cobrir.

### Correção aplicada
Badge `<span class="badge bg-warning text-dark ...">` com
`RotuloProcesso.tipoCurto(...)`, mesmo estilo visual já usado em
`arquivo/lista.html`/`processos/lista.html`, adicionado em:
- `solicitante/lista.html` (tabela desktop + card mobile, ao lado do nome
  do paciente).
- `solicitante/detalhe.html` (no `<h1>`, ao lado do nome do paciente/chip
  de protocolo).
- `dashboard.html` (na célula de paciente da tabela principal do Painel,
  abaixo do nome — título geral "Urgência Renal" da tela **não muda**,
  só a linha do processo específico).

## Testes adicionados/ajustados
- `SolicitacaoOnlineServiceTest`: novo teste cobrindo a rejeição de um
  segundo `criar()` com mesmo usuário+CPF dentro da janela de 15s
  (`IllegalStateException`), e teste confirmando que um `criar()` normal
  (sem duplicata recente) continua funcionando.
- `SolicitanteControllerTest`: teste cobrindo que o controller devolve o
  formulário com flash de erro (não 500) quando o serviço rejeita por
  duplicidade.
- Verificação manual do `<script th:replace="~{layout :: lockSubmitScript}"/>`
  presente em `solicitante/nova.html` (mesma convenção dos outros 4
  templates que usam `data-lock-submit`).

## Atualização de `CLAUDE.md`
Seção "Paciente preemptivo" ganhou um bullet novo documentando a causa raiz
da duplicação (script de trava de duplo-submit ausente neste template
específico) e a dupla correção (frontend + guarda de 15s no backend), para
não recair no mesmo wiring incompleto em um template futuro que reutilize
`data-lock-submit`.

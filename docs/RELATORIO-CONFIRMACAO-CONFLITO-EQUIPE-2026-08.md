# Relatório: confirmação bloqueante de conflito de equipe na escolha dos médicos avaliadores

**Status: IMPLEMENTADO (Opção A, aprovada explicitamente pelo dono do
produto).** Ver seção "10. Implementação (Opção A)" ao final deste documento
para o resumo do que foi feito, arquivos tocados e cobertura de testes. O
diagnóstico original (seções 1-9) é mantido como registro do raciocínio que
levou à decisão — reflete o estado do código ANTES desta implementação.

## 1. Pedido do usuário (resumo)

Hoje existe um aviso quando um dos 3 médicos avaliadores de um processo é
(aparentemente) da mesma equipe/instituição do solicitante
(`ConflitoEquipeMatcher.mesmaEquipe`). O usuário quer:

1. O aviso aparecer **logo após a escolha dos 3 médicos** — não mais só (na
   percepção dele) no momento de "Registrar envio".
2. Virar um **fluxo bloqueante de confirmação**: o operador precisa responder
   explicitamente **"prosseguir mesmo assim"** ou **"reescolher os
   médicos"** antes de continuar.

## 2. Diagnóstico do estado atual

### 2.1 Onde e quando os 3 médicos são escolhidos hoje

**Existe um único ponto no sistema inteiro onde os avaliadores de um
processo são escolhidos, e é irreversível depois.**

- Tela: `GET /processos/novo?origemSolicitacaoOnlineId=NN`
  (`src/main/resources/templates/processos/form.html`), controller
  `ProcessoDetalheController.novo`/`salvar`
  (`src/main/java/br/gov/saude/sgpur/web/ProcessoDetalheController.java:195-361`).
- É a tela de **"Novo processo"**, alcançada a partir do botão "Revisar e
  converter" na triagem de uma `SolicitacaoOnline` — desde 2026-07-27 não
  existe mais cadastro manual "do zero"; todo processo nasce de uma
  solicitação do Portal do Solicitante convertida aqui.
- **A escolha dos médicos é UM campo a mais dentro do MESMO formulário/POST**
  que cadastra todos os outros dados do processo (paciente, RGCT, equipe
  solicitante, e-mail, número, observações) — não é uma tela/etapa separada,
  nem um POST próprio. O form tem:
  ```html
  <div class="row g-2" id="listaMedicos" th:attr="data-max-avaliadores=${totalAvaliadores}">
      <div class="col-md-6" th:each="m : ${medicos}">
          <input class="form-check-input medico-check" type="checkbox" name="medicoIds"
                 th:value="${m.id}" th:id="'med' + ${m.id}">
          <label ... th:text="${m.rotulo}">Médico</label>
      </div>
  </div>
  ```
  (`processos/form.html:78-86`). `${medicos}` é
  `membroService.listarAtivos()` — a lista de `MembroUrgenciaRenal` **inteira**
  (entidade, não um DTO projetado; cada `m` já expõe `.id`, `.rotulo`
  ("instituição - nome") e `.instituicao` via getter).
- `static/js/processo-form.js` só faz UMA coisa: atualiza o contador
  "N / 3" e desabilita as caixas restantes quando `marcados >= max` — **não
  há nenhuma outra lógica JS nessa tela hoje**, e nenhum fetch/AJAX.
- No `POST /processos` (`salvar`), o servidor valida
  `medicoIds.size() != AVALIADORES_POR_PROCESSO` (rejeita com erro de
  formulário) e delega a `ProcessoService.cadastrar(processo, medicoIds)`,
  que cria um `Parecer` pendente por médico
  (`processo.addParecer(new Parecer(medico))`) — **é aqui que os avaliadores
  do processo são fixados**.
- **Depois de criado, os médicos NUNCA mais mudam.** `atualizarDados`
  (chamado por `POST /processos/{id}/editar`, a única forma de editar um
  processo depois de criado) tem o comentário explícito: *"Atualiza apenas
  os dados descritivos do processo (numero e medicos nao mudam)."* Não existe
  nenhum endpoint para trocar um avaliador de um processo já cadastrado — a
  única forma de "reescolher" hoje seria excluir o processo (só ADMIN) e
  cadastrar de novo, ou cancelar/decidir e o fluxo terminar.
- **Achado relevante para o desenho da correção:** os checkboxes de
  `processos/form.html` **não têm `th:checked`** — se o form for
  re-renderizado por qualquer erro de validação (número duplicado, data fora
  do intervalo, etc.), a seleção de médicos feita pelo operador **se perde**
  e ele precisa marcar tudo de novo. Isso já é uma limitação hoje, sem
  relação com o pedido, mas é relevante porque qualquer solução que reenvie o
  formulário inteiro para o servidor e o re-renderize (Opção B abaixo)
  herdaria esse mesmo comportamento, a menos que seja corrigida junto.

### 2.2 Onde e quando o aviso de "mesma equipe" é calculado/exibido hoje

- **Cálculo**: `ProcessoDetalheController.detalhe()` (`GET /processos/{id}`,
  linhas 603-613), toda vez que a tela de detalhe do processo é renderizada
  (não é calculado uma única vez nem persistido — é recalculado a cada
  carregamento da página):
  ```java
  String equipe = p.getSolicitanteEquipe();
  List<String> medicosMesmaEquipe = p.getPareceres().stream()
      .map(Parecer::getMembro)
      .filter(m -> conflitoEquipeMatcher.mesmaEquipe(m.getInstituicao(), equipe))
      .map(m -> m.getNome() + " (" + m.getInstituicao() + ")")
      .distinct()
      .collect(Collectors.toList());
  model.addAttribute("medicosMesmaEquipe", medicosMesmaEquipe);
  ```
  Note que isso lê `p.getPareceres()` — ou seja, **só existe depois que o
  processo (com seus 3 `Parecer`) já foi criado**. Não há nenhum cálculo
  equivalente na tela `processos/form.html` (onde a escolha acontece).
- **Exibição**: `processos/detalhe.html:382-390`, dentro da aba/painel
  "1. Envio" (`<!-- ABA 1: Envio -->`), **acima** do card "Status do envio":
  ```html
  <div th:if="${!medicosMesmaEquipe.isEmpty()}" class="alert alert-warning py-2 mb-3">
      <i class="bi bi-exclamation-triangle-fill"></i>
      <strong>Possível conflito de interesse.</strong>
      Avaliador(es) aparentemente da mesma equipe/instituição do solicitante
      (...): <strong>...</strong>.
      Confirme antes de enviar — o envio não é bloqueado, apenas sinalizado.
  </div>
  ```
- **Correção de precisão sobre a percepção do usuário**: o aviso **não** está
  atrelado ao clique em "Registrar envio" especificamente — é um `<div
  th:if>` estático que aparece **sempre que a página de detalhe é
  carregada**, contanto que haja conflito, mesmo antes do operador tocar em
  qualquer botão da aba Envio. Como a única forma de chegar a essa tela é
  logo após o `POST /processos` bem-sucedido (redirect
  `redirect:/processos/{id}`), na prática **o aviso já aparece "logo depois"
  de escolher os médicos**, cronologicamente — só que:
  1. Está numa **página diferente** da que fez a escolha (houve um
     redirect/reload no meio, quebrando a continuidade "acabei de marcar os
     checkboxes → aviso aparece").
  2. É um `alert-warning` **passivo**, competindo visualmente com vários
     outros avisos da mesma aba (recebimento automático, status do envio,
     trava de anonimização, etc. — a aba Envio é descrita no próprio
     CLAUDE.md como historicamente "poluída visualmente", parcialmente
     corrigida em 2026-08 pelo relatório de clareza do operador).
  3. **Não exige nenhuma ação** — o operador pode simplesmente ignorá-lo e
     seguir clicando em "Registrar envio" sem nunca ter respondido nada
     sobre o conflito.
  4. É recalculado (e reaparece) em **toda** visita à tela de detalhe
     enquanto o conflito existir — inclusive depois do envio já ter sido
     registrado — porque não há nenhum estado "já confirmado"/"já visto".

  Ou seja: a queixa do usuário está tecnicamente correta em espírito (o
  aviso, hoje, só se materializa depois que o processo já existe e os
  médicos já estão fixados para sempre) mesmo que a causa raiz não seja
  literalmente "só aparece ao clicar em Registrar envio" — é "só aparece
  numa tela seguinte, de forma não-bloqueante, depois que a decisão de quem
  serão os avaliadores já é irreversível".

- **Nenhum outro lugar do sistema calcula ou usa `ConflitoEquipeMatcher`**
  hoje. Confirmado por grep: só `ProcessoDetalheController` (exibição) e os
  testes (`ConflitoEquipeMatcherTest`, `ProcessoDetalheControllerTest`).
  `RegistroEnvioService` (o serviço que de fato registra o envio aos
  avaliadores) **não** chama o matcher — o requisito "≥1 documento clínico
  PDF" é a única validação de negócio ali; o conflito de equipe nunca foi,
  tecnicamente, parte do fluxo de "Registrar envio", apesar de estar
  visualmente na mesma aba.

### 2.3 Mecanismo de confirmação bloqueante já existente

O sistema já tem um modal de confirmação genérico e reutilizável, usado em
pelo menos 9 telas (Registrar decisão, Enviar Resposta ao Solicitante,
Registrar envio, Reabrir processo, Excluir processo, Confirmar
anonimização, Retomar análise, Encaminhar informação ao avaliador, etc.):

- **Fragment do modal**: `layout.html :: confirmModal` — um Bootstrap Modal
  fixo (`#modalConfirmarAcao`), com um `<p id="modalConfirmarAcaoMensagem">`
  (texto dinâmico) e dois botões: "Cancelar" (`data-bs-dismiss="modal"`) e
  "Confirmar" (`#btnConfirmarAcaoFinal`). É **genuinamente binário**:
  Confirmar ou Cancelar — não tem hoje um terceiro estado/ação nem suporte
  nativo a "duas opções nomeadas" (ex.: "Prosseguir" vs "Reescolher"), mas
  isso é só rótulo de botão fixo ("Confirmar"/"Cancelar") — o texto da
  mensagem em si já é livre, então já dá para escrever a pergunta como
  "Prosseguir mesmo assim?" e Cancelar naturalmente comunica "não, deixa eu
  reescolher".
- **JS**: `static/js/confirmar-acao.js`. Tem **dois modos de uso**:
  1. **Declarativo**: qualquer `<form>`/`<a>` com `data-confirm-msg="..."` é
     interceptado automaticamente (delegação de evento em `document`, fase de
     captura) — ao submeter/clicar, abre o modal; só continua (re-submete o
     form via `el.submit()`, ou re-clica o link) se o operador clicar
     "Confirmar". Usado hoje em `data-confirm-msg` estático ou **calculado
     dinamicamente por JS antes do submit** (ex.: "Registrar decisão" recalcula
     o texto do atributo a cada troca do `<select>`).
  2. **Programático**: `window.confirmarAcao(mensagem)` — retorna uma
     `Promise<boolean>`, chamável de qualquer lugar do JS, **sem** precisar
     de um `<form>`/submit em andamento. O próprio comentário do arquivo já
     cita esse caso de uso: *"telas que precisam decidir programaticamente
     (ex.: reverter um `<select>` se o usuário não confirmar) em vez de um
     form/link simples."* **Esse é exatamente o encaixe técnico do pedido
     do usuário** — reagir a uma mudança de estado (3º checkbox marcado) sem
     que isso esteja ligado a nenhum submit de formulário.
  3. Fallback: se o Bootstrap/modal não estiverem disponíveis por algum
     motivo, cai para `window.confirm()` nativo — nunca deixa a ação seguir
     sem alguma confirmação.
- **Nenhuma modificação no JS/modal genérico é necessária** para o caso de
  uso do pedido — o padrão programático já resolve "confirmar ou não, e se
  não, o chamador decide o que fazer" (aqui: não fazer nada, deixando o
  operador livre para mexer nos checkboxes de novo).

### 2.4 A escolha dos médicos é síncrona com o resto do submit, não um passo separado

Como descrito em 2.1, não existe hoje (nem em nenhuma outra tela) uma etapa
"Escolher médicos" isolada com POST próprio — é um `<input type="checkbox"
name="medicoIds">` dentro do MESMO `<form>` que tem paciente/equipe/número.
Isso é decisivo para o desenho da solução:

- Uma checagem **client-side** (JS, no momento em que o 3º checkbox é
  marcado) é natural e não exige nenhuma mudança na estrutura do form nem no
  contrato do `POST /processos`.
- Uma checagem **server-side síncrona no mesmo submit** (como o "Registrar
  decisão"/"Enviar resposta" fazem, via `data-confirm-msg` calculado
  ANTES do clique) não é possível da mesma forma aqui, porque o servidor só
  vê os `medicoIds` **depois** que o operador já preencheu TUDO e clicou
  "Cadastrar" — ou seja, o aviso apareceria só no fim do preenchimento do
  formulário inteiro, não "logo após a escolha dos 3 médicos" como pedido.
  Para fazer o aviso aparecer nesse momento exato usando dado
  computado no servidor, é preciso uma chamada assíncrona (fetch) disparada
  pelo evento de "3º médico marcado" — não dá para reaproveitar o padrão
  `data-confirm-msg` estático como está.

### 2.5 Único ponto de escolha — não há duplicidade a cobrir

Conferido: não existe hoje nenhuma segunda tela/fluxo em que médicos são
escolhidos ou trocados para um processo (edição não permite; não há
reatribuição de avaliador; a única forma de "trocar" um avaliador de fato é
reabrir/excluir/recriar o processo, fora do escopo do formulário de
escolha). Portanto **o único arquivo/tela a tocar para o item 1 do pedido é
`processos/form.html` + seu JS + (se optar pela Opção A) um endpoint novo
pequeno**.

## 3. Problema com a UX atual

1. **Timing**: o aviso só existe depois que o `Processo` (com seus 3
   `Parecer`) já foi persistido — a decisão de quem avalia já é
   irreversível quando o aviso aparece pela primeira vez. Não há como
   "reescolher" no sentido pedido (trocar o médico) sem apagar e recriar o
   processo inteiro (ação exclusiva de ADMIN).
2. **Não-bloqueante**: é um `alert-warning` comum, no meio de outros avisos
   da mesma aba — fácil de ignorar, sem exigir nenhuma ação/clique.
3. **Descontinuidade**: há um redirect de página entre "escolher os
   médicos" e "ver o aviso", quebrando a associação causal na cabeça do
   operador (ele pode nem lembrar que acabou de escolher aqueles 3 médicos
   quando vê o aviso na tela seguinte).
4. **Reaparece sempre**: sem nenhum estado de "já visto/confirmado", o aviso
   volta a cada visita à tela de detalhe enquanto o conflito existir — o que
   é aceitável para um aviso passivo, mas incompatível com um fluxo de
   confirmação único no momento da escolha.

## 4. Proposta de solução técnica

### Opção A (recomendada): checagem client-side em tempo real, ao completar a seleção de 3 médicos, ainda em `processos/form.html`

**Ideia central**: assim que o operador marca o 3º checkbox (ou troca algum
dos 3 já marcados, resultando num trio diferente), o JS dispara uma consulta
assíncrona ao servidor perguntando "algum desses 3 médicos tem conflito com
esta equipe solicitante?" e, se houver, abre o modal genérico
programaticamente (`window.confirmarAcao(...)`) pedindo a confirmação.

**Por que consultar o servidor em vez de reimplementar a lógica em JS:**
`ConflitoEquipeMatcher.mesmaEquipe` não é trivial — normalização de
acento/maiúscula, mapa de apelidos por sigla (`ALIASES`), casamento
bidirecional por tokens, `STOPWORDS`. Duplicar esse algoritmo em
JavaScript criaria duas fontes de verdade fadadas a divergir (o mesmo
problema documentado no CLAUDE.md para `VerificadorNomePaciente`, que
tem seu PRÓPRIO conjunto de stopwords hoje só porque resolve um problema
ligeiramente diferente — nunca é o padrão do projeto duplicar regra de
negócio em client e servidor sem necessidade). A alternativa (endpoint
pequeno, só leitura) evita essa duplicação por completo, ao custo de uma
chamada de rede.

**Passo a passo técnico:**

1. **Endpoint novo, só leitura, em `ProcessoDetalheController`** (ou um
   controller dedicado, se preferir isolar):
   ```
   GET /processos/conflito-equipe?equipe={texto}&medicoIds=1,2,3
   ```
   - Protegido pelo MESMO matcher de segurança já existente para
     `/processos/**` (`hasAnyRole("ADMIN","OPERADOR")`) — nenhuma mudança
     no `SecurityConfig` é necessária.
   - Implementação: para cada id em `medicoIds`, busca o
     `MembroUrgenciaRenal` (ou filtra a lista já carregada de ativos, para
     evitar N buscas) e chama `conflitoEquipeMatcher.mesmaEquipe(m.getInstituicao(), equipe)`
     — **exatamente a mesma chamada** já usada em `detalhe()`, sem
     duplicar lógica alguma.
   - Retorna JSON, por exemplo:
     ```json
     {"conflitos": [{"id": 3, "nome": "Dra. Ana", "instituicao": "HCPA"}]}
     ```
   - `GET` simples, sem CSRF a considerar (não é mutação).

2. **`static/js/processo-form.js`**: além do contador já existente, ao
   `atualizar()` (chamado em todo `change` de checkbox), quando
   `marcados === max`:
   - Monta a URL com os 3 ids atuais + o valor corrente do campo
     `input[name=solicitanteEquipe]` (encoding via `encodeURIComponent`).
   - Evita disparar de novo para o MESMO trio já perguntado nesta sessão de
     preenchimento (guarda simples: comparar contra o último trio
     consultado, ordenado, como string) — assim, alternar entre dois
     médicos não-conflitantes sem nunca soltar do 3º não gera fetch
     repetido desnecessário.
   - `fetch(...)`; se `conflitos.length > 0`, monta a mensagem (nomes +
     instituições) e chama `window.confirmarAcao(mensagem)`.
   - Se confirmado ("Confirmar" = "Prosseguir mesmo assim"): não faz nada
     além de fechar o modal — o operador segue livre para preencher o
     resto do formulário e clicar em "Cadastrar" normalmente. Opcionalmente
     grava num `data-*`/variável local que aquele trio específico já foi
     confirmado, para não perguntar de novo se nada mudar.
   - Se **não** confirmado ("Cancelar" = "quero reescolher"): também não
     precisa fazer nada de especial automaticamente — como nada foi
     submetido ainda, "reescolher" já é trivial: o operador desmarca/marca
     outro médico com o mouse. **Melhoria de UX opcional** (não obrigatória):
     desmarcar automaticamente o último checkbox que completou o trio
     (`c.checked = false; atualizar();`), para deixar claro visualmente que
     a seleção "não fechou" e convidar a nova escolha — ver pergunta aberta
     P4 abaixo, é uma decisão de produto, não técnica.
   - Deve também re-disparar a checagem se o operador **editar o campo
     "Equipe / hospital solicitante"** depois de já ter 3 médicos marcados
     (ex.: campo pré-preenchido errado e corrigido manualmente) — listener
     de `change`/`input` nesse campo também, reaproveitando a mesma função.

3. **Nenhuma mudança em `ConflitoEquipeMatcher`, `ProcessoService.cadastrar`,
   `ProcessoValidator` ou qualquer regra de negócio server-side de
   verdade** — o conflito continua sendo um **aviso heurístico**, nunca
   vira uma trava impossível de contornar. Isso é deliberado: o próprio
   matcher tem risco documentado de falso positivo (casamento por tokens
   curtos/genéricos, mitigado mas não eliminado pelas calibragens de
   2026-08-10 registradas no CLAUDE.md) — transformar isso numa regra de
   negócio server-side **bloqueante de verdade** (que rejeita o `POST
   /processos`) arriscaria travar cadastros legítimos por causa de um
   falso positivo de regex, sem nenhuma forma de override a não ser
   burlar via requisição direta.

4. **`GET /processos/novo`**: nenhuma mudança necessária além de,
   opcionalmente, adicionar `th:data-instituicao="${m.instituicao}"` nos
   checkboxes SE decidir montar a mensagem de erro no client sem round-trip
   nenhum ao servidor (não recomendado — ver acima, prefira o endpoint).

**Vantagens desta opção:**
- Satisfaz literalmente o pedido #1 ("logo após a escolha dos 3 médicos",
  na MESMA tela, sem navegação de página no meio).
- Satisfaz o pedido #2 (bloqueia visualmente com modal até o operador
  responder) sem precisar reformular o contrato do formulário/POST.
- Não introduz nenhuma regra de negócio nova capaz de travar um cadastro
  legítimo por falso positivo — mantém a filosofia "aviso, não bloqueio de
  dado" já documentada, só troca "aviso passivo" por "aviso que exige
  resposta".
- Reaproveita 100% da infraestrutura de modal já existente
  (`confirmar-acao.js`, `layout :: confirmModal`) — zero componente visual
  novo.
- Baixo raio de impacto: só toca `processo-form.js` + 1 endpoint GET novo +
  (opcional) 1 atributo `data-*` no template.

**Limitação conhecida (a documentar/aceitar conscientemente):**
- É contornável desabilitando JS ou chamando `POST /processos` diretamente
  (curl/Postman) sem nunca ter respondido ao modal — mas isso é **consistente**
  com o comportamento atual do sistema (o aviso já não bloqueia o
  `POST /processos` nem o `POST /registrar-envio` hoje) e com a natureza
  heurística do matcher. Se o dono do produto quiser uma garantia
  server-side inquebrável, ver Opção B abaixo.

### Opção B (alternativa, mais invasiva): checagem server-side síncrona no `POST /processos`, com resubmissão confirmada

Mantém a lógica 100% no servidor, ao custo de mexer no fluxo de submit:

1. `salvar()` (`POST /processos`) passa a checar conflito ANTES de chamar
   `processoService.cadastrar(...)`, usando os `medicoIds` recebidos e o
   `solicitanteEquipe` do form.
2. Se houver conflito **e** o request não trouxer um novo parâmetro (ex.:
   `conflitoConfirmado=true`), o método **não cadastra nada** e re-renderiza
   `processos/form.html` com:
   - Todos os campos preenchidos como já ficam hoje em caso de erro de
     validação (`@ModelAttribute("processo")` preserva o objeto).
   - **Precisa corrigir a lacuna do item 2.1** (checkboxes sem `th:checked`)
     para os 3 médicos continuarem marcados no re-render — hoje isso
     simplesmente não existe (bug latente que essa opção obrigatoriamente
     expõe/precisa resolver).
   - Um aviso destacado no topo (ou um `data-confirm-msg` num botão extra
     "Cadastrar mesmo assim") explicando o conflito, com um segundo botão
     que reenvia o form com `conflitoConfirmado=true` no hidden input.
3. Se o request já tiver `conflitoConfirmado=true`, ignora o conflito e
   segue o fluxo normal de cadastro (best-effort: mesmo que a equipe/médico
   tenham mudado entre o primeiro e o segundo submit, não há problema real
   em confiar na confirmação do segundo POST).

**Vantagens**: garantia real (não hackeável via DevTools/JS desabilitado),
única fonte de verdade (o servidor), sem endpoint novo de leitura.

**Desvantagens**: não satisfaz tão bem o pedido #1 ("logo após a escolha")
— o aviso só aparece depois de preencher TODOS os outros campos e clicar
"Cadastrar" pela primeira vez, exigindo um segundo clique num formulário já
totalmente preenchido; corrige (ou herda, se não corrigido) o bug de
checkbox não persistido; transforma efetivamente um aviso heurístico numa
mini regra de negócio com um "escape hatch" (`conflitoConfirmado`), que é
mais código e mais estado para manter do que a Opção A.

### Recomendação

**Opção A** é o caminho recomendado — atende ao pedido com o menor raio de
impacto, sem transformar uma heurística sujeita a falso positivo numa regra
de negócio nova, e reaproveita a infraestrutura de modal exatamente como
ela já foi desenhada para ser usada programaticamente. A Opção B fica
registrada como alternativa caso o dono do produto prefira uma garantia
que não dependa de JavaScript.

## 5. Impacto em testes existentes

- **`ConflitoEquipeMatcherTest`** (`src/test/java/br/gov/saude/sgpur/service/`):
  não muda — continua testando só `mesmaEquipe(...)`, que não é alterado
  em nenhuma das duas opções.
- **`ProcessoDetalheControllerTest`**: os testes de `medicosMesmaEquipe`
  (linhas ~673-677, checagem do model attribute no `detalhe()`) não mudam —
  esse cálculo/exibição na tela de detalhe **continua existindo** (é o
  "aviso de segunda linha", útil para quem chega direto pela URL ou revisita
  o processo depois). A Opção A é aditiva, não substitui o aviso existente.
  Se a Opção A for implementada, um novo teste `@WebMvcTest`
  (`GET /processos/conflito-equipe`) precisa ser criado, mockando
  `ConflitoEquipeMatcher`/`MembroUrgenciaRenalRepository`.
- **`ProcessoDetalheControllerTest` / `ProcessoServiceTest` (cadastro)**:
  nenhuma mudança de assinatura em `ProcessoService.cadastrar` na Opção A —
  sem impacto. Na Opção B, `salvar()` ganharia um parâmetro novo
  (`conflitoConfirmado`) e os testes de `POST /processos` (`salvar`)
  precisariam de um caso novo cobrindo o re-render sem confirmação e o
  cadastro efetivo com confirmação.
- **E2E (`NovoProcessoPage`/`FluxoCompletoProcessoIT`)**: hoje
  `NovoProcessoPage.selecionarMedicos(List<Long> medicoIds)` marca os 3
  checkboxes em sequência e `cadastrar()` clica direto em "Cadastrar". Se os
  3 médicos de fixture do E2E tiverem conflito de equipe com a equipe
  solicitante de fixture (a checar — hoje não há indicação de que tenham,
  já que o teste passa sem tocar `ConflitoEquipeMatcher`), a Opção A faria
  o modal abrir no meio da seleção e travar o teste até responder. Duas
  formas de lidar com isso:
  1. Garantir na fixture do E2E que os médicos escolhidos NÃO conflitam
     (mais simples, e reflete o caso majoritário real).
  2. Se algum cenário de teste quiser exercitar o conflito de propósito,
     `NovoProcessoPage` precisaria de um método novo (ex.:
     `confirmarConflitoDeEquipe()`) que espera o modal e clica em
     "Confirmar", similar ao padrão já usado em
     `ProcessoDetalhePage.passo3_decidir()`/`passo4_confirmarRespostaAoSolicitante()`
     (clique em `#btnConfirmarAcaoFinal`).
- **Nenhum teste hoje verifica ausência/presença de um endpoint
  `/processos/conflito-equipe`** obviamente, porque ele não existe — será
  todo novo.

## 6. Riscos e casos de borda

- **0 conflitos entre os 3 médicos**: nenhuma mudança de comportamento —
  fluxo continua exatamente como hoje (sem fetch extra relevante, ou fetch
  retornando lista vazia sem nenhum modal).
- **1 de 3 médicos em conflito**: modal cita só o médico problemático — igual
  ao aviso atual de `detalhe.html`, que já lista médicos individualmente.
- **2 ou 3 de 3 médicos em conflito**: a mensagem deve listar todos —
  reaproveitar a mesma formatação de `#strings.listJoin(medicosMesmaEquipe, '; ')`
  já usada em `detalhe.html`, adaptada para JS (`.join('; ')`).
- **Operador confirma, depois desmarca 1 médico e marca outro**: o trio
  mudou — a checagem deve rodar de novo para o NOVO trio (guarda por
  "último trio perguntado", não por "já confirmei uma vez nesta página").
- **Operador confirma, mas NUNCA chega a clicar em "Cadastrar"** (fecha a
  aba, navega para outro lugar): nenhum efeito colateral — nada foi
  persistido, é um estado 100% client-side/efêmero.
- **Campo "Equipe / hospital solicitante" mudado DEPOIS de já ter 3
  médicos marcados**: precisa re-disparar a checagem (item já coberto no
  passo a passo da Opção A, seção 4).
- **Latência/erro de rede no fetch**: se o `fetch` falhar (endpoint fora do
  ar, timeout), a solução NÃO deve travar o operador — cair silenciosamente
  em "sem conflito detectado" (fail-open), coerente com a natureza de aviso
  não-crítico. Mesma filosofia do resto do sistema para funcionalidades
  auxiliares (ex.: o convite automático ao avaliador é "best-effort", nunca
  trava a ação principal).
- **Processo criado por outro caminho que não este formulário**: não
  existe — confirmado na seção 2.5 que este é o único ponto de escolha de
  médicos hoje.
- **O aviso de `detalhe.html` (seção 2.2) vira redundante?** Não
  necessariamente — mesmo com a Opção A implementada, o aviso na tela de
  detalhe continua útil para quem revisita o processo depois (ex.: um
  segundo operador que não participou do cadastro original, ou o mesmo
  operador dias depois, verificando antes de "Registrar envio"). Recomenda-se
  **manter os dois** (a checagem no momento da escolha É NOVA e ADITIVA; o
  aviso existente continua como está).

## 7. Escopo estimado (Opção A recomendada)

Arquivos prováveis a tocar:

- `src/main/java/br/gov/saude/sgpur/web/ProcessoDetalheController.java` —
  novo endpoint `GET /processos/conflito-equipe` (ou um controller novo
  dedicado, se preferir não inchar mais essa classe já descrita no próprio
  CLAUDE.md como "a mais complexa do sistema" — considerar um
  `ProcessoConflitoEquipeController` isolado, só com esse endpoint, para
  não competir por revisão com a classe mais sensível do projeto).
- `src/main/resources/static/js/processo-form.js` — lógica de fetch +
  chamada a `window.confirmarAcao`.
- `src/main/resources/templates/processos/form.html` — garantir que
  `layout :: confirmModal` e `confirmarAcaoScript` estejam incluídos nessa
  página (checar se já estão — hoje ela só inclui `scripts`,
  `lockSubmitScript` e o próprio `processo-form.js`; **não inclui
  `confirmarAcaoScript` nem `confirmModal` ainda**, precisa adicionar os
  dois).
- Teste novo: `ProcessoConflitoEquipeControllerTest` (ou dentro de
  `ProcessoDetalheControllerTest`, conforme onde o endpoint for colocado).
- Possível ajuste em `NovoProcessoPage`/`FluxoCompletoProcessoIT` (E2E) —
  ver seção 5.
- `CLAUDE.md` — atualizar a seção "Passo 2 (Envio)" (que hoje descreve o
  aviso como só "não bloqueia" na aba Envio) para registrar o novo
  comportamento no momento da escolha.

**Confirmado durante a investigação**: `processos/form.html` hoje **não**
inclui `layout :: confirmModal` nem `layout :: confirmarAcaoScript` — só
`scripts` e `lockSubmitScript`. Isso precisa ser adicionado independente da
opção escolhida (A ou B), senão `window.confirmarAcao` cai automaticamente
no fallback `window.confirm()` nativo (funcional, mas foge do padrão visual
do resto do sistema).

## 8. Perguntas em aberto para o dono do produto

1. **Texto exato da mensagem de confirmação.** Sugestão de rascunho: *"Um ou
   mais médicos selecionados parecem ser da mesma equipe/instituição do
   solicitante (Dra. Fulana — HCPA). Deseja prosseguir com esta seleção
   mesmo assim?"* — Confirmar OK, Cancelar deixa o operador reescolher.
2. **"Reescolher" precisa de alguma ação automática do sistema**, além de
   simplesmente deixar o operador clicar nos checkboxes de novo? (ex.:
   desmarcar automaticamente o médico em conflito ao clicar "Cancelar" no
   modal, ou até dar scroll/focar visualmente na lista de médicos). Ver
   seção 4, item 2 do passo a passo — hoje o rascunho técnico assume "não faz
   nada automático", só o modal fecha.
3. **A checagem deve rodar de novo ao reabrir o form por erro de
   validação** (número duplicado etc., que hoje perde a seleção de
   médicos)? Recomendação: aproveitar esta refatoração para também corrigir
   o `th:checked` ausente (seção 2.1), independente da opção escolhida —
   é um bug pequeno e correlato.
4. **Confirma que o item 2 do pedido ("bloqueante") significa "modal que
   exige resposta explícita no momento da escolha" (Opção A) e não "o
   cadastro do processo fica de fato impossível de completar sem
   confirmar" mesmo via chamada direta ao servidor** (Opção B, mais
   rígida)? A recomendação técnica é a Opção A, mas a palavra "bloqueante"
   no pedido original é ambígua o suficiente para justificar essa
   confirmação antes de codar.
5. **O aviso client-side deve rodar só na criação (`processos/form.html`)
   ou também deveria alimentar/substituir de alguma forma o aviso passivo
   já existente em `processos/detalhe.html`** (ex.: um link "Já vi este
   aviso"/estado persistido)? Recomendação: não mexer no aviso de
   `detalhe.html` nesta rodada — é aditivo, resolve um problema diferente
   (quem revisita o processo depois), e mexer nele authoriza escopo maior
   sem necessidade.
6. **Nome/local do endpoint novo**: `GET /processos/conflito-equipe` está
   OK, ou prefere um path mais específico como
   `GET /processos/novo/conflito-equipe` (evita colidir topologicamente
   com futuros `/processos/{id}/...`)? Sugestão técnica: usar um path que
   não comece com um segmento que pareça um `{id}` (ex.: **evitar**
   `/processos/{algumaCoisaQuePareceId}/conflito-equipe`), para não correr
   risco de ambiguidade de rota com `/processos/{id}` — `/processos/conflito-equipe`
   já é seguro porque não é numérico e o Spring resolve por padrão de
   string exato antes de cair no `{id}` variável, mas vale confirmar no
   código na hora de implementar.

## 9. Resumo executivo

- **Onde os médicos são escolhidos hoje**: um único lugar, `processos/form.html`
  (`GET/POST /processos`), dentro do MESMO formulário que cria o resto do
  processo — não há uma etapa separada nem forma de trocar depois.
- **Onde o aviso aparece hoje**: `processos/detalhe.html`, aba Envio, como
  `alert-warning` passivo recalculado a cada carregamento da tela de
  detalhe — nunca bloqueia nada, nunca exige resposta, e só existe depois
  que o processo (e os avaliadores) já foram criados de forma irreversível.
- **Proposta recomendada**: checagem client-side em tempo real dentro de
  `processos/form.html`, disparada assim que o 3º médico é marcado (ou o
  trio muda), consultando um endpoint novo e simples que reusa
  `ConflitoEquipeMatcher` sem duplicar lógica em JS, exibindo o modal
  genérico já existente (`window.confirmarAcao`) — sem transformar o
  aviso heurístico numa regra de negócio server-side bloqueante.
- **Escopo pequeno**: 1 endpoint GET novo, ajustes em
  `processo-form.js`/`processos/form.html` (incluindo os fragments de modal
  que faltam), 1-2 testes novos, revisão pontual do E2E de cadastro.
- **6 perguntas em aberto** para o dono do produto antes de codar (seção 8),
  a mais importante sendo a nº 4 (confirmar que "bloqueante" quer dizer
  "modal client-side obrigatório", não "regra de negócio server-side
  inquebrável").

## 10. Implementação (Opção A)

Implementada exatamente como desenhada na seção 4 (Opção A), sem nenhum
desvio de regra de negócio — nenhuma mudança em `ConflitoEquipeMatcher`,
`ProcessoService.cadastrar`/`ProcessoValidator`. O aviso continua sendo uma
heurística client-side, nunca uma trava server-side inquebrável (resposta
assumida à pergunta 4 da seção 8, no espírito já documentado na seção 4:
"bloqueante" = modal que exige resposta explícita no momento da escolha).

**Arquivos tocados:**

- `src/main/java/br/gov/saude/sgpur/web/ProcessoConflitoEquipeController.java`
  (novo) — `GET /processos/conflito-equipe?equipe=...&medicoIds=1,2,3`,
  controller isolado (não inchou `ProcessoDetalheController`, seguindo a
  recomendação da seção 7). Reusa `ConflitoEquipeMatcher.mesmaEquipe` sem
  duplicar lógica; devolve `{"conflitos": [{"id","nome","instituicao"}, ...]}`.
  Protegido pelo mesmo `hasAnyRole("ADMIN","OPERADOR")` de `/processos/**`
  já existente em `SecurityConfig` (casa por padrão de URL, nenhuma mudança
  na configuração de segurança foi necessária). Path literal
  `/conflito-equipe` (não numérico) para nunca colidir com
  `/processos/{id}`, mesmo padrão já usado por `/processos/solicitacoes-online`.
- `src/main/resources/static/js/processo-form.js` — ao completar o trio de
  3 médicos (ou ele mudar depois de completo), consulta o endpoint via
  `fetch`; se houver conflito, chama `window.confirmarAcao(mensagem)`
  (modal genérico já existente). Guarda contra reperguntar o MESMO trio+
  equipe já consultado. Também reage a mudança no campo "Equipe / hospital
  solicitante" (`change`/`blur`) depois de o trio já estar completo.
  **Fail-open deliberado**: falha de rede/endpoint não trava o operador —
  cai silenciosamente em "sem conflito detectado" (`.catch` vazio), coerente
  com a natureza de aviso não-crítico já documentada na seção 6.
- `src/main/resources/templates/processos/form.html` —
  `data-conflito-equipe-url` no container dos checkboxes (URL montada via
  `@{...}` do Thymeleaf, nunca hardcoded no JS); inclusão de
  `layout :: confirmModal` e `layout :: confirmarAcaoScript` (confirmado
  pela seção 7 que faltavam nesta tela); e o achado extra da seção 2.1
  corrigido — os checkboxes de `medicoIds` ganharam `th:checked`, then
  preservando a seleção do operador se o form for re-renderizado por erro
  de validação (número duplicado etc.).
- `src/main/java/br/gov/saude/sgpur/web/ProcessoDetalheController.java` —
  `novo()`/`salvar()` passaram a expor `medicoIdsSelecionados` (`Set<Long>`)
  ao model (vazio em `novo()`, os ids submetidos em `salvar()` quando há
  erro de validação), alimentando o `th:checked` acima.
- `src/test/java/br/gov/saude/sgpur/web/ProcessoConflitoEquipeControllerTest.java`
  (novo, `@WebMvcTest`) — sem conflito, 1 médico em conflito, 2 médicos em
  conflito, equipe ausente/em branco (não chama o matcher), sem
  `medicoIds`, acesso por ADMIN e por OPERADOR. **Sem teste de "sem
  autenticação"**: `@WebMvcTest` não carrega o `SecurityConfig` customizado
  do app (usa o fallback padrão do Spring Boot, HTTP Basic, 401) — o
  comportamento real em produção (302 para `/login`, `hasAnyRole("ADMIN",
  "OPERADOR")`) já está coberto para o prefixo `/processos/**` inteiro por
  `SecurityIntegrationTest`, sem precisar duplicar aqui.

**E2E:** confirmado que a fixture do `FluxoCompletoProcessoIT`
(`MembroDevSeed`: instituições `CET-RS`/`HCPA`/`ISCMPA`, equipe solicitante
de teste `"Equipe Teste E2E"`) não tem conflito de equipe segundo
`ConflitoEquipeMatcher` — nenhum ajuste foi necessário em `NovoProcessoPage`/
`FluxoCompletoProcessoIT` (cenário 1 da seção 5).

**Validação:** suíte completa, **1057 testes** — 0 falhas atribuíveis a esta
mudança (a única falha vista numa rodada, em
`LembreteAvaliadorTimestampIntegrationTest`, é a flakiness de precisão de
nanossegundo do H2 já documentada no `CLAUDE.md`, pré-existente e não
relacionada — reproduzida isolada). `mvn -DskipTests compile`/`test-compile`
sem erros (JDK 21).

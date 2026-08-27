# CLAUDE.md — Guia do projeto SAUR

Sistema de Gestão de Processos de Urgência Renal (SAUR). Substitui a planilha
Excel da equipe de Urgência Renal da Secretaria de Saúde.

> **Este arquivo foi enxugado em 2026-08-21** (de ~57 mil pra ~10 mil
> palavras — ver `docs/RELATORIO-OTIMIZACAO-CLAUDE-MD-2026-08-21.md`). O que
> era um log cronológico de ~90 sessões virou `docs/historico/
> CLAUDE-log-sessoes-2026-07-a-08.md` (arqueologia, não fonte da verdade);
> toda regra/decisão que ainda vale hoje foi condensada de volta aqui. **Ao
> terminar uma sessão que muda comportamento real do sistema, EDITE a seção
> de referência certa** (Regras de negócio, Convenções de código, etc.) em
> vez de anexar uma seção nova "## <coisa> (2026-MM-DD)" no fim do arquivo —
> é assim que ele volta a inchar. Detalhe pontual de uma investigação/bug
> específico vai pro histórico ou pra um `docs/RELATORIO-*.md` próprio, não
> aqui. **Precisa do "porquê" de uma decisão antiga, não só o "o quê"?**
> Consulte `docs/INDEX.md` (catálogo de todo `docs/*.md` com resumo de 1
> linha) e `grep` o arquivo/relatório certo — não precisa carregar tudo de
> novo pra achar contexto histórico.

## Stack
Java 21 · Spring Boot 3.5.16 (web, data-jpa, thymeleaf, security, validation) ·
PostgreSQL (prod — rodando na própria VM Oracle desde 2026-07-25, ver seção
Deploy; usou Neon até essa data) e H2 (dev) · Thymeleaf + Bootstrap ·
OpenPDF · Maven.
Pacote base `br.gov.saude.sgpur` e env vars `SGPUR_*` (mantidos por enquanto,
não renomeados no rebrand SAUR). `artifactId` do Maven é `saur` (gera
`target/saur-0.0.1-SNAPSHOT.jar`).

## Toolchain (Windows desta máquina)
- JDK 21: `C:\Users\rafae\Tools\jdk-21.0.11+10` (NÃO usar o Java 17 do sistema).
- Maven: `C:\Users\rafae\Tools\apache-maven-3.9.6`.

## Como rodar / testar
```powershell
.\start.ps1            # dev (H2) — sandbox de teste
.\start.ps1 prod       # prod (Postgres) — usa application-local.yml (gitignored)
```
- App em http://localhost:3000 (porta trocada de 8080 para 3000 no commit
  `93debdf`, 2026-07-21; `start.ps1` **não abre o navegador sozinho**, precisa
  acessar manualmente após o boot) · login inicial `admin` / `Admin123!`
  (criado automaticamente por `AdminBootstrap` só quando a tabela `usuario`
  está vazia; em prod exige `SGPUR_ADMIN_PASSWORD` via env var, sem default).
- Testes: `.\test.ps1` (ou `mvn test`) — **1.131 testes** (contagem exata via
  `target/surefire-reports`, reverificada em 2026-08-25; esse número sobe a
  cada sessão que adiciona teste novo — se divergir, reconte em vez de
  confiar cegamente nele), sempre com **JDK 21**.
  Build: `mvn -DskipTests package` (gera o JAR).
- **Teste E2E de navegador (Playwright):** `.\e2e.ps1` sobe o SAUR real (porta
  aleatória, H2, perfil dev) e um Chromium de verdade, **com janela visível
  por padrão** (`saur.e2e.headed=true` em `pom.xml`, repassado ao processo
  forkado do failsafe via `<systemPropertyVariables>` — não basta setar env
  var, o Failsafe forka um processo Java novo que nem sempre a herda),
  percorrendo TODO o fluxo clicando na tela — login → cadastro → Recebimento
  → Envio → 3 pareceres → Decisão (maioria simples) → Finalização — como um
  operador humano faria, com `slowMo` de 900ms entre ações para dar pra
  acompanhar. A janela abre na área de trabalho de quem rodou o comando
  (processo `chrome.exe` local, não algo remoto/headless por engano); se não
  aparecer, checar se não foi minimizada ou se abriu atrás de outra janela.
  Um banner fixo no topo da página narra em texto o que o bot está fazendo
  em cada ação (ex. "🤖 Passo 3/5 - Respostas: registrando o parecer de
  ..."), injetado via `Legenda.mostrar()` — cosmético, não afeta a lógica do
  teste. Fica em `src/test/java/br/gov/saude/sgpur/e2e/` (Page Object
  Model: `PlaywrightTestBase` + `Legenda` + `pages/*Page.java` +
  `*IT.java`), separado dos testes rápidos (contagem acima) via
  `maven-failsafe-plugin`/profile `e2e` (não roda em `.\test.ps1`/
  `mvn test`). Primeira vez só, instala o browser:
  `.\e2e.ps1 -InstalarBrowser`. Rodar sem janela (mais rápido, ex. CI):
  `.\e2e.ps1 -Headless`. Screenshot automático em `target/e2e-screenshots/`
  se o teste falhar. (Equivalente cru, sem o script: `mvn verify -Pe2e`, mas
  exige JDK 21 e Maven já no PATH da sessão — prefira `.\e2e.ps1`.)
- **Deploy em produção:** VM Oracle Cloud (`ubuntu@163.176.30.222` — IP
  **mudou** em 2026-08, ver incidente "IP público efêmero mudou" na seção
  Deploy abaixo; não é mais `163.176.163.213`, domínio
  `urgenciarenal.duckdns.org`), systemd `sgpur.service`, jar em
  `/opt/sgpur/sgpur.jar` (usuário `sgpur`). Chave SSH local:
  `~/.ssh/saur_oracle`. Deploy manual: `scp target/saur-0.0.1-SNAPSHOT.jar
  ubuntu@163.176.30.222:/tmp/sgpur-novo.jar`, depois na VM `sudo cp
  /opt/sgpur/sgpur.jar /opt/sgpur/sgpur.jar.bak-<timestamp>` (backup), `sudo mv
  /tmp/sgpur-novo.jar /opt/sgpur/sgpur.jar && sudo chown sgpur:sgpur
  /opt/sgpur/sgpur.jar && sudo systemctl restart sgpur`. Validar com
  `systemctl status sgpur` e `curl -Ik https://urgenciarenal.duckdns.org/login`
  (espera 200). HTTPS já ativo via certbot (cert válido até 2026-10-05,
  renovação automática). Ver também o agente `oracle-vm` (nome correto do
  agente registrado — não `saur-oracle-vm`, mesmo que sessões antigas tenham
  citado esse nome; ele é compartilhado com o projeto Petrobras, que roda na
  mesma VM) para tarefas de VM (SSH, systemd, nginx, certbot) — mas ele só
  age mediante instrução direta
  do usuário no mesmo turno em que foi invocado, não aceita autorização
  repassada por outro agente/coordenador em mensagens posteriores (proteção
  contra escalonamento de privilégio; para reaproveitar, ela deve vir junto da
  invocação inicial).
- **Não há mais empacotamento desktop** (`release.ps1`/`package-desktop.ps1`/
  Inno Setup foram removidos em 2026-07-03). O projeto é só web agora — rode
  via `start.ps1` e acesse pelo navegador.
- **Modo teste de e-mail:** em dev, `app.mail.override-recipient`
  (`application.yml`, default `rafaelioppi@gmail.com`) faz **todo** e-mail
  enviado pelo `EmailSenderService` ser redirecionado para esse endereço,
  independente do destinatário real calculado pelo sistema — nunca manda
  e-mail de teste para avaliadores/solicitantes de verdade. O assunto ganha
  um prefixo `[TESTE - para: ...]` com os destinatários originais. Em prod
  (`application-prod.yml`) fica explicitamente vazio, então o envio real
  funciona normalmente.

## Regras de negócio (não violar)
- Cada processo vai para **exatamente 3 médicos**. Decisão por **maioria
  simples (2 de 3)**: **≥2 favoráveis = Deferido**; **≥2 desfavoráveis =
  Indeferido** (exige **ofício + motivo**). As duas regras são **impostas** no
  serviço e no controller (`decidir` rejeita Deferido sem 2 favoráveis e
  Indeferido sem 2 desfavoráveis).
- **Paciente preemptivo (2026-08-27) — segundo tipo de processo, mesma
  equipe/mesmo rito.** `Processo`/`SolicitacaoOnline`/
  `RascunhoSolicitacaoOnline.preemptivo` (`Boolean` nullable — `null`/`false`
  == urgência renal comum, sem exigir backfill; sempre ler via
  `isPreemptivo()`, nunca o getter cru). Paciente preemptivo ainda **não está
  na lista de espera do SNT** — o processo dele avalia a **inserção** na lista
  de espera renal, **não é uma urgência**. Julgado pela mesma equipe, no mesmo
  sistema: **nenhuma regra de votação/decisão muda** — 3 avaliadores, maioria
  simples 2/3, exceção do coordenador CET-RS, pausa `SOLICITA_INFORMACAO`,
  fluxo de 5 passos, trava de processo encerrado, tudo idêntico. O que muda é
  classificação/nomenclatura/numeração/obrigatoriedade de um campo:
  - **Nomenclatura:** "Urgência Renal" → **"Inserção em Lista de Espera
    Renal"** em todo texto/documento **específico de um processo
    preemptivo** (título de PDF, carimbo de página, badge, e-mails daquele
    processo) — fonte única em `service/RotuloProcesso.java`. Títulos
    GERAIS/compartilhados do operador (Painel, rodapé, navbar, título do
    Relatório Anual) **não mudam**, continuam "Urgência Renal" sempre.
    **O ASSUNTO do e-mail também segue essa fonte única**
    (`EmailTemplateService.assunto(Processo, String)` usa
    `RotuloProcesso.prefixoAssunto(Processo)` — "Lista de Espera Renal - ..."
    em vez do prefixo configurável padrão "Urgência Renal - ..." — para
    TODOS os e-mails de um processo preemptivo: deferido, indeferido,
    convite/lembrete ao avaliador, solicita-info, cancelamento, lembrete de
    comprovante SNT. Corrigido em 2026-08-27 (continuação do PR #126): o
    método existia desde a leva original mas nunca era chamado, então o
    assunto de todo e-mail de processo preemptivo saía com o prefixo fixo,
    só o corpo/rótulos internos usavam a nomenclatura certa.
    **A fila de triagem do operador também mostra o tipo** — badge
    "Preemptivo" (mesmo padrão visual usado em `processos/lista.html`/
    `processos/detalhe.html`) em `processos/solicitacoes-online-lista.html`,
    `processos/solicitacoes-online-detalhe.html` e `arquivo/lista.html`
    (`RotuloProcesso.tipoCurto`, corrigido na mesma leva — existia mas não
    era chamado por nenhum desses 3 templates).
  - **Rótulo do campo "Justificativa clínica" também é condicional**
    (`RotuloProcesso.rotuloJustificativa`): "Por que a urgência se aplica"
    (comum) vs. "Por que a inserção preemptiva se aplica" (preemptivo) — no
    Portal do Solicitante (`solicitante/nova.html`, dinâmico via
    `solicitante-nova.js`/`atualizarTipoSolicitacao`, e no `th:text`
    server-side), na tela de detalhe da triagem do operador
    (`solicitacoes-online-detalhe.html`) e no texto de ajuda de
    "Observações" do formulário de conversão (`processos/form.html`,
    dinâmico via `processo-form.js`/`atualizarTipoProcesso`). Corrigido em
    2026-08-27: o método existia mas nunca era chamado.
  - **`processos/editar.html` reusa o mesmo script `processo-form.js` do
    formulário de criação** (corrigido em 2026-08-27) — antes, o rádio
    Urgência Renal/Preemptivo do formulário de EDIÇÃO não tinha nenhum
    `onchange`, então trocar o tipo só surtia efeito depois de salvar
    (round-trip ao servidor com erro de validação), diferente do formulário
    de criação (`processos/form.html`), que já alternava a visibilidade/
    obrigatoriedade do RGCT na hora. O bloco do RGCT em `editar.html`
    também passou a ser sempre renderizado (como em `form.html`, alternando
    só via `display:none`/`th:required`), em vez de sumir do DOM por
    completo quando o processo já era preemptivo — sem isso o JS não
    conseguia reexibi-lo ao trocar de volta para Urgência Renal.
  - **UX do campo "tipo de pedido" trocada de 2 rádios obrigatórios para 1
    checkbox opcional (2026-08-27, feedback direto do usuário).** Urgência
    renal é o caso padrão/mais comum — antes o solicitante/operador era
    obrigado a clicar num dos 2 rádios ("Sim — é uma urgência renal" / "Não
    — é um pedido de inserção preemptiva") toda vez, mesmo no caso comum.
    Hoje é 1 `<input type="checkbox" th:field="*{preemptivo}">` único,
    **desmarcado por padrão** = urgência renal (nenhum clique exigido);
    marcado = preemptivo. Aplicado nos 3 formulários que tinham o mesmo
    padrão de rádio: `solicitante/nova.html` (Portal do Solicitante),
    `processos/form.html` (conversão pelo operador) e `processos/editar.html`
    (edição, só quando `status == SOLICITADO`). `th:field` num campo
    `Boolean` do `SolicitacaoOnline`/`Processo` já resolve sozinho o binding
    de checkbox (gera o hidden `_preemptivo` que o Spring usa para tratar
    "não marcado" como `false` — não precisou de nenhum hidden manual extra
    nem de `@InitBinder` novo). Os handlers JS (`atualizarTipoSolicitacao`/
    `atualizarTipoProcesso` em `solicitante-nova.js`/`processo-form.js`)
    continuam os mesmos, só passam a receber `this.checked` do checkbox em
    vez do valor fixo `true`/`false` de cada rádio — nenhuma lógica de
    obrigatoriedade/visibilidade do RGCT mudou. `ProcessoAtualizacaoIntegrationTest`
    passou a ignorar `preemptivo` no loop genérico "todo campo do form
    chega ao banco" (`IGNORADOS_DE_PROPOSITO`) — sua escrita é gated por
    status (`ProcessoService.atualizarDados` rejeita a troca fora de
    `SOLICITADO`) e já tem cobertura dedicada em
    `PacientePreemptivoIntegrationTest`, então generalizar aquele loop
    misturaria essa regra de negócio com a família de bug "campo esquecido
    no copy" que o teste protege.
  - **RGCT deixou de ser `@NotBlank` na entidade** (`Processo`/
    `SolicitacaoOnline.pacienteRgct`) — paciente preemptivo não tem RGCT.
    Obrigatoriedade agora é **condicional** (`!isPreemptivo()`), validada em
    `SolicitacaoOnlineService.criar` e
    `ProcessoDetalheController.salvar`/`atualizar` (mesmo padrão já usado
    para CPF/data de nascimento — nunca reintroduzir Bean Validation
    incondicional nesse campo, quebraria escrita em processo preemptivo já
    existente).
  - **Numeração em série separada**, ver bullet "Numeração `NN/AAAA`" abaixo.
  - **Tipo editável até o processo ser enviado** aos avaliadores (status
    `SOLICITADO`), por ADMIN ou OPERADOR (`ProcessoService.atualizarDados`)
    — troca reemite o número na série certa na mesma transação e grava
    auditoria `PROCESSO_TIPO_ALTERADO` (id + número antigo→novo + tipo
    antigo→novo, nunca o nome do paciente). Depois do envio a troca é
    rejeitada (`IllegalStateException`).
  - **Portal do Avaliador expõe o tipo claramente** (badge "Preemptivo" na
    lista `/avaliador` e no formulário de voto `/avaliador/{id}`, texto
    explícito "Inserção em Lista de Espera Renal" no cabeçalho, e-mails de
    convite/lembrete citando o tipo) — reforço explícito de produto: o
    avaliador não pode inferir isso só pelo texto corrido. Nunca viola a
    imparcialidade (`ProcessoVotoView`/`ParecerPendenteView`/
    `ParecerHistoricoView`/`ParecerDispensadoView` ganham só um `boolean
    preemptivo`, nunca nome/equipe do paciente).
  - Ver o bullet "Deferido exige anexar o comprovante..." abaixo para a
    exceção do Comprovante SNT (paciente preemptivo não tem, a decisão só
    AUTORIZA a equipe a inscrever depois, fora do sistema).
  - Desenho completo e decisões fechadas em
    `docs/PLANO-PACIENTE-PREEMPTIVO-2026-08-27.md`.
- **Exceção — coordenador CET-RS defere sozinho:** se o médico marcado como
  `MembroUrgenciaRenal.coordenador` votar **Favorável**, o processo é
  **Deferido com esse único voto**, sem esperar os outros 2 pareceres
  (`ProcessoService.temVotoCoordenadorFavoravel` /
  `favoraveisNecessariosParaDeferir` — usado em `sugerirDecisao` e `decidir`).
  A regra de **Indeferido continua exigindo ≥2 desfavoráveis** sempre (o
  coordenador não tem peso especial para indeferir). O detalhe do processo
  exibe o badge "Deferido pelo Coordenador da CET-RS"
  (`ProcessoService.deferidoPeloCoordenador`). Só 1 membro deve ter
  `coordenador = true` por vez.
- **Parecer só entra no sistema pelo Portal do Avaliador (mudança de
  2026-07-27).** O médico se autentica em `/avaliador` e vota ele mesmo
  (`origem = AVALIADOR_SISTEMA`) — não existe mais nenhum caminho para o
  OPERADOR lançar/editar manualmente o resultado de um parecer. Os dois
  endpoints que faziam isso (`POST /processos/{id}/resposta-avaliador` e
  `POST /processos/{id}/pareceres`, em `ProcessoDecisaoController`) e os
  respectivos forms na tela de detalhe (card "Respostas dos Avaliadores")
  foram **removidos**. **`OrigemParecer.OPERADOR_EMAIL` e `TipoAnexo.
  RESPOSTA_AVALIADOR` foram removidos por completo do enum no commit
  `041dc43` (2026-07-29)** — não havia nenhuma linha em produção usando
  esses valores (banco tratado como vazio, confirmado pelo usuário antes da
  remoção), então não é mais um caso de "legado só leitura": o valor
  literalmente não existe mais no código, não compila. Foi removido junto
  todo o código que só existia para ler/exibir esses valores (badge "Origem:
  Operador", coluna "Resposta anexada" etc.). O card de Respostas na tela de
  detalhe agora só acompanha o resultado de cada avaliador e permite enviar
  lembrete por e-mail (`POST /processos/{id}/lembrete-avaliador`/
  `lembrete-pendentes`, que continuam existindo — só avisam o avaliador para
  ir votar no portal, não registram parecer). O requisito de anexo antes de
  decidir também foi removido junto (`pareceresRecebidosSemAnexo` não existe
  mais em `ProcessoValidator`/`ProcessoService`): como hoje só existe o voto
  autenticado (`AVALIADOR_SISTEMA`, que dispensa anexo), `decidir` exige
  apenas os ≥2 pareceres emitidos (maioria simples), sem checagem de anexo
  nenhuma.
- **Justificativa obrigatória em voto Não favorável/Solicita informação
  (desde 2026-08-03).** Decisão de produto explicitamente aprovada pelo
  usuário (item 1 da "Fase 11" do
  `docs/RELATORIO-UI-SOLICITANTE-AVALIADOR-2026-08.md`, antes marcada como
  "não implementar sem aval"). Quando o avaliador vota
  `ResultadoParecer.NAO_FAVORAVEL` ou `SOLICITA_INFORMACAO` em
  `POST /avaliador/{processoId}/votar`, o campo `justificativa` passa a ser
  **obrigatório** — sem ele, `AvaliadorController.registrarVoto` rejeita
  ANTES de abrir a transação do voto (flash `erro` + redirect de volta ao
  formulário, nunca grava nada). Voto `FAVORAVEL` continua com justificativa
  opcional, sem mudança. Motivo: o operador depende desse texto pronto para
  redigir o ofício de indeferimento (`OFICIO_INDEFERIMENTO`) ou o pedido de
  informação complementar ao solicitante (`SOLICITA_INFORMACAO`), evitando
  ter que reescrever do zero. O `<textarea>` de `avaliador/votar.html` ganha
  `required` dinâmico via JS (`avaliador-votar.js`,
  `atualizarObrigatoriedadeJustificativa`, disparado ao trocar o rádio de
  resultado) — é só UX, a regra de verdade mora no controller (o `required`
  do HTML sozinho é burlável via DevTools/requisição direta). Não altera
  `OrigemParecer`, `ProcessoValidator` nem a lógica de maioria
  simples/coordenador. Coberto por
  `AvaliadorControllerTest.registrarVotoNaoFavoravelSemJustificativaERejeitado`
  /`ComJustificativaEmBrancoERejeitado`/`ComJustificativaEAceito`,
  `registrarVotoSolicitaInformacaoSemJustificativaERejeitado` e
  `registrarVotoFavoravelSemJustificativaContinuaAceito`.
- **Deferido exige anexar o comprovante de inserção da urgência renal no SNT**
  (`TipoAnexo.COMPROVANTE_SNT`) e enviá-lo junto na resposta ao solicitante; a
  etapa "Comprovante SNT" bloqueia a conclusão até o anexo existir (simétrico
  ao ofício no indeferimento). O comprovante é gerado fora do sistema.
  **Exceção — paciente preemptivo (2026-08-27, ver item "Paciente preemptivo"
  logo abaixo):** essa exigência **não vale** quando `Processo.preemptivo =
  true` — o paciente preemptivo ainda não está na lista de espera do SNT, então
  não existe comprovante nenhum a anexar. `FluxoProcessoService` nem cria a
  etapa "Comprovante SNT" nesse caso (nunca aparece, nunca bloqueia) e
  `ProcessoValidator.validarRespostaSolicitante`/`ProcessoService
  .finalizarResposta` liberam a conclusão sem o anexo, enviando o e-mail de
  Deferido **sem anexo** (texto ajustado: "autoriza a equipe a proceder com a
  inscrição", nunca afirma que a inscrição já ocorreu). Só urgência renal
  comum (`preemptivo` nulo/false) continua exigindo o anexo como sempre.
  **`POST /processos/{id}/comprovante-snt` também recusa o upload quando o
  processo é preemptivo** (corrigido em 2026-08-27, continuação do PR #126)
  — antes só a TELA escondia o formulário nesse caso
  (`processos/detalhe.html`), mas o endpoint em si não checava
  `isPreemptivo()`, então um POST direto (ex. requisição manual, aba antiga
  aberta) ainda conseguia anexar um `COMPROVANTE_SNT` com a descrição
  "Comprovante de inserção da urgência renal no SNT" a um processo que não
  deveria ter esse anexo. Devolve flash `erro` explicando que o processo
  preemptivo não tem comprovante SNT, sem chamar `registrarDataEnvioSnt`.
  **Desde 2026-07-27, o Portal do Solicitante também exibe o resultado final**
  (`/solicitante/{id}`): quando o `Processo` gerado está Deferido/Indeferido/
  Cancelado, a tela mostra a decisão e, se o anexo já existir, um botão de
  download do comprovante SNT (Deferido) ou do ofício de indeferimento
  (Indeferido) via `GET /solicitante/{id}/processo-anexo/{anexoId}`
  (`SolicitanteController.baixarAnexoProcesso`) — endpoint com whitelist
  explícita de `TipoAnexo` (só esses dois) e checagem de posse, nunca serve
  qualquer anexo do processo por ID. Isso é **além** do e-mail com
  comprovante de envio, que continua obrigatório para concluir o processo
  (não foi substituído).
- Status (ciclo expandido, reflete a planilha): `Solicitado` → `Enviado` →
  { `Deferido` / `Indeferido` / `Solicita informação` } (+ `Cancelado`).
  Finais: Deferido/Indeferido/Cancelado. **Atualização de 2026-07-29 (commit
  `041dc43`): `StatusProcesso.EM_ANALISE` foi removido do enum** — não havia
  nenhuma linha em produção usando esse valor (confirmado pelo usuário antes
  da remoção), então deixou de existir como sinônimo legado de `Enviado`; o
  enum só tem os 6 valores citados acima. Ver `docs/PLANO-FLUXO.md`.
- **Processo ENCERRADO trava a edição:** quando o status é final
  (Deferido/Indeferido/Cancelado), `ProcessoValidator.edicaoBloqueada` é `true`
  e **toda alteração é rejeitada** — imposto no controller (guarda
  `bloqueadoPorEncerrado` que devolve flash `erro` com
  `ProcessoValidator.MSG_ENCERRADO`) e reforçado no serviço
  (`ProcessoService.atualizarDados`/`decidir` lançam `IllegalStateException`).
  Bloqueia as etapas 1–4 (recebimento, envio/documento clínico/comprovante aos
  avaliadores, pareceres/resposta-avaliador, redecidir), o upload genérico
  `/anexos`, a exclusão de anexos e os lembretes. **Continuam liberadas** as
  etapas 5–6 (ofício, comprovante SNT, comprovante de envio ao solicitante,
  confirmar resposta) e o e-mail de resposta ao solicitante (`email/enviar`) —
  são a papelada pós-decisão. Downloads/relatórios (GET) sempre liberados. Para
  voltar a editar, **só o ADMIN reabre** (`POST /processos/{id}/reabrir`, que
  volta o status para `Enviado`). O detalhe mostra banner "Processo encerrado" e
  esconde os formulários bloqueados.
- **Solicita informação (PAUSA):** quando um avaliador vota
  `ResultadoParecer.SOLICITA_INFORMACAO`, o processo entra em
  `StatusProcesso.SOLICITA_INFORMACAO` (via
  `ProcessoService.atualizarStatusPorPareceres`, chamado em `salvarPareceres`).
  Isso **pausa o fluxo**: a Decisão fica **bloqueada** — `ProcessoService.decidir`
  lança erro ao tentar Deferir/Indeferir, o controller devolve flash de erro e a
  aba **4. Decisão** fica travada (`liberadoDecisao=false`). O checklist
  (`FluxoProcessoService`) insere a etapa **"Informacao complementar"** com o
  aviso *"Aguardando informacao complementar do solicitante"*. O sistema gera o
  e-mail pronto *"Pedido de informacao complementar ao solicitante"*
  (`EmailTemplateService.emailSolicitaInfo`) endereçado à **equipe solicitante**,
  com nº do processo + **nome completo** do paciente (e-mail ao solicitante leva
  o nome completo; só o material dos avaliadores usa iniciais). Como **todo**
  `Processo` nasce de uma `SolicitacaoOnline` convertida, o solicitante envia a
  informação complementar diretamente pelo **Portal do Solicitante**
  (`POST /solicitante/{id}/informacao-complementar`,
  `SolicitacaoOnlineService.enviarInformacaoComplementar`) — vira o anexo
  `TipoAnexo.INFO_COMPLEMENTAR` no `Processo`, visível ao operador no card de
  Respostas (o operador pode reforçar o pedido pelo e-mail pronto acima, se
  quiser, mas isso é só o texto/e-mail — não existe mais nenhum endpoint para
  "registrar" esse reenvio manualmente). Na aba **3. Respostas**, quando a
  resposta chegar, o operador **registra o recebimento + retoma a análise**
  (`POST /processos/{id}/retomar-analise` →
  `ProcessoService.retomarAposInformacao`): o processo **volta para `Enviado`**,
  os pareceres marcados como *Solicita informação* são **reabertos** (resultado
  limpo) para o voto definitivo, e o fluxo de Respostas/Decisão é liberado. O
  solicitante só ENVIA; retomar a análise continua exclusivo do OPERADOR via
  `retomarAposInformacao`.
  **Decisão de produto confirmada (2026-08-07):** se, no momento de retomar a
  análise, a maioria simples já estava formada pelos outros 2 pareceres (ex.:
  2 favoráveis, com o 3º em pausa), `retomarAnalise`
  (`ProcessoDecisaoController.java`) encadeia `retomarAposInformacao` +
  `tentarDecisaoAutomatica` **na mesma requisição** — o processo é decidido
  imediatamente, **sem esperar** o avaliador que pediu a informação votar de
  novo. Investigado e perguntado explicitamente ao dono do produto
  (`docs/RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md`, achado
  B) depois de um relato de "dois votos deferem durante a pausa" — **resposta
  confirmada: manter o comportamento atual** (decidir na hora, não esperar o
  3º voto). Não é um bug; é a regra de maioria simples 2 de 3 aplicada
  normalmente assim que a pausa é removida.
- **Solicitante pode cancelar até a decisão final (desde 2026-07-29).** Antes, o
  Portal do Solicitante só permitia cancelar enquanto a solicitação estava
  `ENVIADA` (não triada). Agora `SolicitacaoOnlineService.podeCancelar` abre
  duas janelas: `ENVIADA`, **ou** `CONVERTIDA` com o `Processo` gerado **ainda
  não decidido** (`StatusProcesso.isFinalizado() == false`) — casos reais:
  paciente transplantado, óbito, ou pedido aberto por engano enquanto os 3
  médicos já analisam. Depois de Deferido/Indeferido **não cancela mais**.
  `podeCancelar` é **fonte única**: a tela pergunta a ele se mostra o botão e
  `cancelar` pergunta a ele antes de efetivar — não duplicar a condição no
  template. Quando já há processo, `cancelar` **delega a
  `ProcessoService.decidir(id, CANCELADO, null)`** em vez de trocar status na
  mão (mesmo caminho do cancelamento pelo operador, mesmas travas,
  `dataDecisao` gravada) e devolve o `processoId`; sem processo, devolve
  `null`. Os **avaliadores pendentes são avisados por e-mail**
  (`EmailTemplateService.emailCancelamentoAvaliador`, só iniciais) via
  `notificarAvaliadoresCancelamento`, chamado pelo controller **depois** do
  commit e **nunca** dentro da transação — falha de SMTP vira flash `aviso`,
  jamais um rollback que "descancelaria" o processo (mesmo contrato do convite
  automático ao registrar envio). Auditoria:
  `CANCELAMENTO_AVISO_AVALIADOR_ENVIADO`/`_FALHA`.
- **`decidir` espelha `CANCELADO` como `StatusSolicitacaoOnline.CANCELADA`**
  (corrigido em 2026-07-29). Antes, o `switch` mandava tudo que não fosse
  `DEFERIDO` para `REPROVADA` — um processo cancelado aparecia como
  "Reprovada" no Portal do Solicitante, dizendo que a equipe analisou e negou
  o pedido quando ele só foi cancelado (às vezes pelo próprio solicitante).
- **Fluxo em 5 passos** (checklist `FluxoProcessoService` + abas na tela):
  **1 Envio · 2 Respostas · 3 Decisão · 4 Ofício/Comprovante · 5 Resposta ao
  solicitante**. **Atualização de 2026-08-05: era "Fluxo em 6 passos" até
  então, com um "1 Recebimento" antes de Envio** — removido como etapa/aba
  própria e fundido em Envio (ver bullet "Passo 1 (Recebimento)" logo abaixo
  para o detalhe completo da mudança — não existe uma seção `##` própria com
  esse nome, é o bullet seguinte mesmo). Boa parte do
  texto histórico abaixo, sobre o antigo "Passo 1 (Recebimento)", continua
  aqui só como arqueologia de por que ele já nascia sempre automático desde
  2026-07-27 — não descreve mais uma aba/etapa que existe hoje. Cada etapa
  só fica **CONCLUIDA (verde)** na
  timeline se a **sua própria condição** estiver satisfeita **E** todas as
  etapas anteriores também estiverem `CONCLUIDA` (`montar()`: `concluida &&
  anterioresConcluidas`). Sem essa segunda checagem uma etapa posterior pode
  ficar verde "fora de ordem" mesmo com uma etapa anterior ainda pendente
  (bug real corrigido em 2026-07-09: "Resposta ao solicitante" aparecia
  concluída antes do "Comprovante SNT" ser anexado, num processo Deferido).
  Auditoria da mesma sessão também achou e corrigiu uma inconsistência no
  Passo 1: `FluxoProcessoService` só conferia `SOLICITACAO_RECEBIDA`, mas o
  gate real que libera a aba de Envio (`ProcessoDetalheController.
  recebimentoFeito`) sempre exigiu **também** `CAPA_PROCESSO` — a timeline
  podia mostrar "Recebimento" verde mesmo sem a capa, embora a aba de Envio
   já estivesse corretamente bloqueada (inconsistência só visual, sem
   regressão funcional). **Capa automática corrigida em 2026-07-09:**
   `ProcessoDetalheController.registrarRecebimento` chamava
   `RelatorioService.gerarCapaProcesso` automaticamente, gerando a capa
   sempre que o recebimento era registrado. **Esse endpoint e esse método
   foram removidos em 2026-07-27** (ver bullet "Passo 1 (Recebimento)"
   abaixo) — o parágrafo acima é histórico, mantido para quem for procurar o
   contexto do bug de 2026-07-09, mas o comportamento descrito já não existe
   mais no código.
- **Passo 1 (Recebimento): SEMPRE automático desde 2026-07-27** (histórico —
  desde 2026-08-05 não é mais uma aba/etapa própria, foi fundido em Envio —
  ver o bullet "Fluxo em 5 passos" logo acima). Criação
  manual de processo "do zero" deixou de existir — `GET/POST /processos`
  (`ProcessoDetalheController.novo`/`salvar`) agora **exigem**
  `origemSolicitacaoOnlineId` (rejeita com flash de erro e redireciona para
  `/processos/solicitacoes-online` se vier nulo/ausente; se o módulo do
  Portal estiver desligado via `app.solicitante.habilitado=false`, redireciona
  para `/processos` com mensagem própria, já que a fila de triagem nem está
  registrada nesse caso). Como **todo** `Processo` agora nasce de uma
  `SolicitacaoOnline` convertida, a distinção "veio do portal ou não" deixou
  de ter efeito prático no Passo 1: `FluxoProcessoService.montarEtapas`
  marca a etapa Recebimento como **sempre `CONCLUIDA`**, incondicionalmente,
  sem checar nenhum anexo (`SOLICITACAO_RECEBIDA`/`CAPA_PROCESSO`); mesmo
  vale para `calcularGating` (`recebimentoFeito = true` sempre). O antigo
  endpoint `POST /processos/{id}/recebimento`
  (`ProcessoDetalheController.registrarRecebimento`, upload da solicitação
  original + geração da `CAPA_PROCESSO` via `RelatorioService.
  gerarCapaProcesso`) foi **removido** — sem cadastro manual não sobrou
  nenhum processo real que precisasse dele. `FluxoProcessoService.
  veioDoPortal(p)` **continua existindo** (não foi removido), mas hoje serve
  só para achar o `solicitacaoOnlineOrigemId` e exibir o link "Ver
  solicitação original" no card de Recebimento da tela de detalhe — não
  influencia mais nenhum gating. **Atualização de 2026-07-29 (commit
  `041dc43`): os valores de enum `TipoAnexo.SOLICITACAO_RECEBIDA`/
  `CAPA_PROCESSO` foram removidos por completo** (0 linhas em produção,
  confirmado antes da remoção) — não é mais "legado de leitura", o valor não
  existe mais no enum. O método `PdfRelatorioBuilder.adicionarCapa`
  **continua existindo** (reaproveitado pelo Relatório Final, com outros
  parâmetros) — só os dois valores de `TipoAnexo` acima saíram.
- **Passo 2 (Envio):** ao registrar o envio o sistema gera a **cópia anonimizada
  para as equipes** (`SOLICITACAO_AVALIADOR`, só iniciais), nome oficial
  `Processo CET-RS NN-AAAA - Paciente X.X.X.pdf`
  (`SolicitacaoAvaliadorService.nomeArquivoOficial`). **Não há mais folha-rosto
  gerada pelo sistema.** Esse anexo é um **PDF único** = os **documentos clínicos
  anonimizados** anexados ao processo (`DOCUMENTO_CLINICO_AVALIADOR`, só os PDF)
  **fundidos** (`SolicitacaoAvaliadorService.consolidar`) e depois **carimbados
  página a página** com um cabeçalho
  (`SolicitacaoAvaliadorService.carimbarCabecalho`, PdfStamper sobre o
  over-content — não altera o conteúdo). Cabeçalho em 2 linhas: "Central de
  Transplantes do Estado do Rio Grande do Sul - URGENCIA RENAL" e "Processo
  CET-RS NN/AAAA - Paciente X.X.X" (número + **iniciais**, nunca o nome
  completo — imparcialidade). O mesmo texto institucional ("Central de
  Transplantes do Estado do Rio Grande do Sul") é usado no Ofício
  (`OficioService`), no Relatório Final (`RelatorioService`) e no Relatório
  Anual (`RelatorioAnualService`) — trocar em um exige trocar nos 4 lugares.
  **`PdfCabecalhoStamper.anonimizarMetadados` usa `PdfStamper.setInfoDictionary`
  (desde 2026-07-29), não o `setMoreInfo` deprecado no OpenPDF 1.3.34** — os
  dois fazem exatamente a mesma coisa (confirmado decompilando o `.jar`:
  `setMoreInfo` só chama `setInfoDictionary` e liga uma flag interna que só
  importa quando o XMP é gerado automaticamente, e aqui o XMP é sempre setado
  explícito logo depois). Cobertura em `PdfCabecalhoStamperTest`: lê o
  `/Info` e o XMP do PDF resultante e confirma que um nome de paciente
  "envenenando" até uma chave `/Info` **customizada** (fora do padrão) some
  por completo — é a mesma proteção de imparcialidade que o texto visível já
  tinha, mas nos metadados (o navegador mostra o `Title` na aba ao abrir um
  PDF inline). **Teto de páginas por PDF individual (2026-08-24, achado real
  de vistoria — defesa contra DoS por CPU/memória):** o limite de tamanho de
  arquivo (25MB/30MB, `application.yml`) não protege contra um PDF com
  páginas minúsculas e milhares delas — a fusão/carimbo página a página
  custa CPU/memória proporcional ao NÚMERO de páginas, não ao tamanho do
  arquivo. `RegistroEnvioService.registrar` agora verifica
  `PdfReader.getNumberOfPages()` contra `app.upload.max-paginas-pdf`
  (env `SGPUR_MAX_PAGINAS_PDF`, default 300) ANTES de consolidar/carimbar —
  um documento que excede o teto fica de fora da consolidação com o MESMO
  tratamento de aviso não-bloqueante já usado para PDF corrompido/sem
  páginas (envio segue se sobrar outro documento válido); se TODOS excederem,
  o envio é bloqueado com mensagem de negócio que **cita o motivo real de
  cada documento descartado** (revisão adicional 2026-08-24, PR #120 — antes
  a mensagem era sempre o texto genérico "sem páginas válidas", mesmo quando
  o motivo verdadeiro era outro, ex. teto de páginas excedido; o operador não
  tinha como saber e reenviar o mesmo arquivo não resolvia nada), nunca 500.
  **É
  obrigatório ao menos um documento clínico PDF anexado:** `registrarEnvio`
  **bloqueia** (flash `erro`, sem efetivar o envio) se não houver nenhum. A
  **solicitação original** (a informação completa da `SolicitacaoOnline` de
  origem, com o nome completo) **NUNCA** entra nesse PDF — desde que o
  cadastro manual acabou (2026-07-27) ela nem é mais anexada ao `Processo`
  como `TipoAnexo` (o valor que existia pra isso, `SOLICITACAO_RECEBIDA`, foi
  removido do enum em 2026-07-29 por falta de uso — ver bullet "Passo 1"
  acima). Documentos clínicos não-PDF são ignorados do merge com **aviso
  não-bloqueante** (flash `aviso`). **O comprovante de envio aos avaliadores
  deixou de ser exigido em 2026-07-27** (fluxo antigo, do tempo em que o
  envio aos avaliadores era só por e-mail): os avaliadores hoje votam
  autenticados no Portal do Avaliador (`/avaliador`), que nunca dependeu
  desse anexo (`AvaliadorController` só checa o vínculo do parecer) — o
  requisito era um gate sem função posterior. `registrarEnvio` e
  `ProcessoValidator.validarRegistroEnvio` não checam mais esse anexo, e a
  seção "Anexar comprovante de envio" e o endpoint
  `POST /processos/{id}/comprovante-envio-avaliadores` foram removidos da
  aba Envio. **Atualização de 2026-07-29 (commit `041dc43`): o enum
  `TipoAnexo.EMAIL_ENVIADO_AVALIADORES` foi removido por completo** — não
  "permanece só sem ser exigido" como versões anteriores deste arquivo
  diziam; não havia nenhuma linha em produção usando esse valor (confirmado
  pelo usuário antes da remoção), então não havia mais risco de quebrar
  carregamento de processo antigo. Os 2 sub-passos
  restantes da aba Envio (documentos clínicos, registrar envio) continuam
  obrigatórios. O método legado `SolicitacaoAvaliadorService.gerar`
  (folha-rosto) foi **removido em 2026-07-27** por falta de qualquer
  chamador (`consolidar`/`carimbarCabecalho`/`nomeArquivoOficial` continuam
  ativos na mesma classe). Os documentos clínicos são anexados na
  própria aba Envio (`POST /processos/{id}/documento-clinico`). **Aviso (não
  bloqueia)** se algum médico for da mesma equipe/instituição do solicitante —
  `ConflitoEquipeMatcher.mesmaEquipe(instituicaoMembro, solicitanteEquipe)`
  ignora maiúsculas/acentos e casa sigla × nome por extenso × cidade via mapa
  de apelidos por sigla (`ALIASES`); usa palavra/frase inteira (não substring).
  Instituições novas fora do `ALIASES` caem no match por tokens da própria
  sigla — ao cadastrar uma nova instituição relevante, enriquecer o `ALIASES`.
- **Convite automático ao Portal do Avaliador (desde 2026-07-29):** registrar o
  envio dispara, para **cada avaliador com parecer pendente**, o e-mail
  `EmailTemplateService.emailConviteAvaliador` (só iniciais do paciente, link
  para `{app.base-url}/avaliador`). Antes desse ajuste o método existia mas
  **não tinha nenhum chamador** — era código morto, e o operador precisava
  copiar/colar o texto pronto na mão. Implementado em
  `RegistroEnvioService.enviarConvitesAvaliadores`, chamado pelo controller
  **depois** de `registrar` ter commitado e **nunca de dentro da transação
  dele**: avaliador sem e-mail cadastrado ou falha de SMTP viram flash `aviso`
  nomeando quem ficou de fora, com o envio já gravado. Isso é o **oposto** de
  `ProcessoService.finalizarResposta`, onde a falha de SMTP faz rollback de
  propósito — lá o e-mail **é** a entrega ao solicitante; aqui é só o aviso de
  que há trabalho no portal, e o operador reenvia pelo lembrete manual
  (`POST /processos/{id}/lembrete-avaliador`). Usa
  `pareceresPendentesComEmail` (resultado nulo + `dataEnvio` preenchida), então
  num **reenvio quem já votou não recebe convite de novo**. Auditoria:
  `CONVITE_AVALIADOR_ENVIADO` / `CONVITE_AVALIADOR_NAO_ENVIADO` (sem e-mail) /
  `CONVITE_AVALIADOR_FALHA` (SMTP).
- Numeração `NN/AAAA`: **manual em 2026**, **automática a partir de 2027**.
  **Paciente preemptivo (2026-08-27) usa uma série SEPARADA, formato
  `P-NN/AAAA`** (ex. `P-01/2026`), com sequência própria por ano
  (`ProcessoRepository.findMaxSequencialByAnoEPreemptivo`, `coalesce
  (preemptivo, false)` para não excluir da contagem as linhas legadas
  `NULL`) — nunca compartilha a contagem com a série normal.
  `ProcessoService.proximoNumero(ano, preemptivo)` gera o número de cada
  série; `extrairSequencial` tolera o prefixo `P-`. Regex de validação manual
  aceita `^(P-)?\d{1,3}/\d{4}$` com checagem cruzada (processo preemptivo
  precisa do prefixo, urgência renal não pode ter). O formulário de novo
  processo sugere o próximo número de cada série (`proximoNumeroUrgencia`/
  `proximoNumeroPreemptivo`), atualizado por JS ao trocar o tipo.
- Fluxo por e-mail com anexos por etapa. **Identificação do paciente:** o
  e-mail/material aos **médicos avaliadores oculta o nome** do paciente (só
  iniciais), para preservar a **imparcialidade do julgamento** — os avaliadores
  decidem sem saber quem é o paciente (convenção da equipe de Urgência Renal,
  **não** é LGPD). Já os e-mails/documentos dirigidos à **equipe solicitante**
  (pedido de informação complementar, resposta de Deferido/Indeferido) levam o
  **nome completo** do paciente. Decisão manual com **sugestão automática** por
  maioria simples (2/3 favoráveis → Deferido; 2/3 desfavoráveis → Indeferido).
- "Membros da Urgência Renal" (nunca "Câmara Técnica").

## Portal do Avaliador (/avaliador) — Fase 1 MVP

**Atualização de 2026-07-27: o "modelo híbrido" original foi encerrado.**
Antes, convivia o voto pelo operador (e-mail) e o voto autenticado do próprio
médico no sistema. Agora **parecer só é registrado pelo avaliador autenticado
no Portal** (`origem = AVALIADOR_SISTEMA`) — o operador não lança/edita mais
resultado de parecer manualmente por nenhum caminho. **Atualização de
2026-07-29 (commit `041dc43`): `OrigemParecer.OPERADOR_EMAIL` e `TipoAnexo.
RESPOSTA_AVALIADOR` foram removidos do enum**, não apenas do caminho de
escrita — não sobrou nenhuma linha em produção usando esses valores, então
não fazia sentido manter "legado só leitura". Ver detalhe da remoção em
"Regras de negócio" acima.

### Paciente preemptivo — aviso explícito ao avaliador (2026-08-27)
O avaliador precisa saber, sem ambiguidade, quando está julgando uma
**inserção em lista de espera renal (preemptiva)** em vez de uma urgência —
o critério clínico é outro. Isso é **compatível** com a imparcialidade (que
protege a identidade do paciente, não a natureza do pedido): o badge de tipo
aparece na lista `/avaliador` (`avaliador/lista.html`) e no formulário de
voto `/avaliador/{id}` (`avaliador/votar.html`, título "Inserção em Lista de
Espera Renal (Preemptivo)" bem visível, não só implícito no texto corrido),
e os e-mails de convite/lembrete (`EmailTemplateService.emailConviteAvaliador`
/`emailConvitePortal`/`emailLembreteAvaliador`) citam o tipo do processo. O
flag chega aos templates só como `boolean preemptivo` nos records projetados
(`AvaliadorController.ProcessoVotoView`/`ParecerPendenteView`/
`ParecerHistoricoView`/`ParecerDispensadoView`) — nunca via `Processo`/
`Parecer` cru, mantendo a mesma proteção por design contra vazar
`pacienteNome`/equipe (ver item 12 das regras de negócio).

### Perfil AVALIADOR
- Novo valor `Perfil.AVALIADOR` em `domain/Perfil.java`.
- `Usuario.membro` (`@ManyToOne`, nullable): vincula o login ao
  `MembroUrgenciaRenal` que ele representa. Obrigatório para AVALIADOR;
  ADMIN/OPERADOR devem ter `membro = null`.
- `UsuarioService` valida e persiste o vínculo (sobrecarga `criar/atualizar` com
  `membroId`). `UsuarioController` passa a lista de membros ao form.
- Seed dev-only: `avaliador1` / `avaliador123`, vinculado ao primeiro membro ativo.

### OrigemParecer (domain/OrigemParecer.java)
- **Histórico:** existiu `OPERADOR_EMAIL` (parecer lançado pelo operador após
  receber a resposta por e-mail, exigindo `TipoAnexo.RESPOSTA_AVALIADOR` como
  comprovante) até 2026-07-29, quando foi **removido do enum** no commit
  `041dc43` — sem nenhuma linha em produção usando esse valor, o usuário
  pediu para tratar o banco como vazio e eliminar de vez o valor e todo o
  código que só existia para lê-lo/exibi-lo (não ficou como "legado só
  leitura"). Hoje `OrigemParecer` só tem um valor.
- `AVALIADOR_SISTEMA` — **único valor do enum hoje.** Médico se autentica no
  portal e vota diretamente; o registro autenticado (usuario + `dataHoraVoto`
  + IP no log de auditoria) substitui o anexo — não há mais nenhum requisito
  de anexo antes de decidir.
- Campos em `Parecer`: `origem`, `dataHoraVoto`, `votadoPor`.

### Segurança
- `SecurityConfig`: `/avaliador/**` exige `ROLE_AVALIADOR`; OPERADOR/ADMIN ficam
  bloqueados nessa rota. Success handler redireciona AVALIADOR para `/avaliador`,
  demais para `/`.

### AvaliadorController (web/AvaliadorController.java)
- `GET /avaliador` — lista pareceres pendentes do membro logado (status
  ENVIADO, resultado nulo, dataEnvio preenchida). Exibe **somente
  iniciais** do paciente — nunca nome completo, equipe solicitante ou
  co-avaliadores. (`StatusProcesso.EM_ANALISE` foi removido do enum em
  2026-07-29, commit `041dc43` — versões antigas deste arquivo citavam
  "ENVIADO/EM_ANALISE" aqui, hoje é só `ENVIADO`.)
- `GET /avaliador/{processoId}` — formulário de voto. 403 se não for avaliador do
  processo, se o parecer já foi emitido, ou se o status não é ENVIADO.
- `POST /avaliador/{processoId}/votar` — grava `resultado`, `dataResposta`,
  `dataHoraVoto`, `votadoPor`, `origem=AVALIADOR_SISTEMA`; chama
  `atualizarStatusPorPareceres`; registra auditoria com IP.

### Auditoria com IP
- `LogAuditoria.ip` (VARCHAR 45, nullable — comporta IPv6).
- `AuditoriaService.registrar(acao, detalhe, ip)` — sobrecarga que grava o IP.
  Método sem IP delega a ela com `null` (sem quebrar chamadas existentes).
- Coluna IP visível em `/auditoria` (ADMIN).

### E-mail
- `EmailTemplateService.emailConviteAvaliador(p, membro)` — gera texto com
  iniciais e link `{app.base-url}/avaliador` para copiar/colar.
- `app.base-url` configurável em `application.yml` (default `http://localhost:3000`,
  variável de ambiente `SGPUR_BASE_URL` em prod).
- Template "convite-portal" incluído em `gerar(p)` quando status ENVIADO.

## Perfis e permissões (SecurityConfig)
- **ADMIN**: acesso total, incluindo `/usuarios/**` (cadastro de LOGINS) e
  `/auditoria/**` — exclusivos dele.
- **OPERADOR**: acesso operacional completo a `/processos/**`,
  `/controle-urgencias/**`, `/membros/**` (criar/editar/inativar médicos
  avaliadores) e `/relatorios/**`. **Não** cria/edita usuários (logins) nem vê
  auditoria. Não acessa `/avaliador/**`.
- **AVALIADOR**: acesso restrito ao portal `/avaliador/**`; não acessa
  `/usuarios/**`, `/auditoria/**` nem as áreas operacionais.
- **Conta própria**: qualquer perfil logado troca a própria senha em
  `/usuarios/minha-senha` (menu dropdown no nome do usuário, navbar) — rota
  liberada com `authenticated()`, ANTES da regra geral `/usuarios/**` (ADMIN).

## Conta de usuário (Usuario)
- **E-mail obrigatório** no cadastro/edição via `/usuarios` (validado no
  `UsuarioController`, criar e atualizar — como a senha). **Não** é
  `@NotBlank` na entidade `Usuario.email`: colocar a anotação lá quebra o
  `AdminBootstrap` (cria o ADMIN inicial sem e-mail) e qualquer seed/usuário
  legado sem e-mail no persist (`ConstraintViolationException` no boot). A
  entidade só valida o **formato** (`@Email`), a obrigatoriedade fica na
  camada web.
- `UsuarioService.atualizar` precisa copiar `form.getEmail()` explicitamente
  (não é campo automático) — já corrigido, mas é fácil esquecer de novo se
  reescrever esse método.
- **Solicitante com pedidos enviados NÃO pode ser excluído (2026-08-04).**
  `UsuarioService.excluir` recusa com `IllegalStateException` (mensagem de
  negócio na tela, apontando o "Inativar" que já existe ao lado) quando
  `SolicitacaoOnlineRepository.countByUsuarioSolicitanteId > 0` — apagar o
  usuário exigiria apagar junto as solicitações e, por tabela, mensagens,
  anexos e até o `Processo` gerado. Um eventual **rascunho**
  (`RascunhoSolicitacaoOnline`) é o oposto: dado de staging descartável, que
  nunca chega à triagem, então é apagado **junto** com o usuário
  (`rascunhoRepo.deleteByUsuarioSolicitanteId` antes do `delete`) — sem isso a
  FK do rascunho bloquearia a exclusão de um solicitante que só abriu o
  formulário uma vez. **Antes desta correção** o DELETE ia direto ao banco e a
  FK `solicitacao_online.usuario_solicitante_id` (NOT NULL, sem cascade)
  estourava `DataIntegrityViolationException`, exibida pelo
  `GlobalExceptionHandler` como *"os dados informados violam uma regra do
  banco (…) revise os campos e tente novamente"* — genérica e sem sentido numa
  exclusão, onde não existe campo nenhum para revisar (bug real relatado em
  produção). Coberto por `ExclusaoSolicitanteIntegrationTest`
  (`@SpringBootTest` + H2 real: um teste com repositório mockado nunca
  expressaria uma violação de FK).
- **"Esqueci minha senha" é um fluxo de TOKEN por link, não mais troca
  imediata (2026-08-24).** Antes, `POST /usuarios/esqueci-senha` trocava a
  senha ativa na hora do pedido (`UsuarioService.resetarSenha`, removido) e
  mandava a senha nova por e-mail — permitia DoS/lockout: qualquer pessoa que
  soubesse o LOGIN de um avaliador/admin (não precisa do e-mail nem da senha)
  conseguia derrubar o acesso dele repetidamente. Hoje o fluxo é todo de
  `PasswordResetService` (novo), em 2 passos:
  1. `POST /usuarios/esqueci-senha` chama `PasswordResetService
     .gerarTokenResetSenha`, que gera e persiste um `PasswordResetToken` de
     uso único com TTL de 60 min (constante `TTL`), invalidando qualquer
     token pendente anterior do mesmo usuário. A senha ativa NAO muda neste
     passo — continua válida até o usuário abrir o link. Sempre a mesma
     mensagem neutra na tela, exista ou não o login (evita enumeração),
     igual antes.
  2. `GET/POST /usuarios/redefinir-senha?token=...` — formulário de nova
     senha; `PasswordResetService.confirmarNovaSenha` valida o token (existe,
     não expirado, não usado), aplica a mesma política de senha
     (`SenhaPolicy`, extraída de `UsuarioService` para ser reaproveitada
     aqui) e troca a senha ativa + marca o token como usado na MESMA
     transação (atômico). Token inválido/expirado/já usado nunca revela qual
     dos três motivos — só a mensagem varia por UX.
  - Ordem commit-antes-de-notificar (mesmo padrão de
    `RegistroEnvioService.enviarConvitesAvaliadores`): o e-mail com o link
    só é disparado pelo `UsuarioController` DEPOIS que
    `gerarTokenResetSenha` retorna (token já comitado), nunca de dentro
    dessa transação — falha de SMTP não desfaz o token nem impede um
    reenvio.
  - Rotas públicas (`permitAll` no `SecurityConfig`, precisam vir ANTES da
    regra geral `/usuarios/**` de ADMIN): `/usuarios/esqueci-senha` e
    `/usuarios/redefinir-senha`.
  - `PasswordResetTokenRepository` + limpeza periódica dos tokens expirados
    via `RateLimitLimpezaScheduler` (mesmo agendador que já limpava os
    mapas em memória de `LoginAttemptService`/`PasswordResetAttemptService`).
  - Auditoria: `SENHA_RESET_SOLICITADO` (passo 1, como antes) +
    `SENHA_RESET_CONFIRMADO` (novo, passo 2 com sucesso).
  - `PasswordResetAttemptService` (rate-limit de 3 pedidos/15min por
    username) continua protegendo o passo 1 contra spam de e-mail, sem
    mudança de comportamento.

## Indicador: tempo de resposta dos avaliadores
- `TempoRespostaService.calcular()` — média de **dias corridos** entre
  `Parecer.dataEnvio` e `Parecer.dataResposta`, geral e por avaliador, mais a
  contagem "fora do prazo". Prazo-meta configurável em
  `app.avaliador.prazo-dias` (env `SGPUR_PRAZO_AVALIADOR`, default 7).
- Exibido em `/membros` (card da média geral + coluna por avaliador) e no
  Painel (`/`, card "Tempo de resposta"). Formatação pt-BR pronta no service
  (`formatarDias`), nunca calculada na view.
- Pareceres reabertos por "Solicita informação" mantêm `dataEnvio` original
  (só `dataResposta` é limpo) — o 2º voto conta desde o envio original, não
  reseta o relógio.

## UI / Frontend
- Design system completo em `app.css` com variáveis `--rs-*` (azul, dourado,
  verde, vermelho, escala de cinza). **Nunca usar Tailwind** — o dashboard foi
  migrado para Bootstrap + app.css.
- Templates usam `layout.html` com fragments `head`, `navbar`, `flash`,
  `status(ok)`, `statusRotulo(ok, r)`, `statusNa(r)`, `footer`, `scripts`.
- JavaScript específico fica em `static/js/*.js` (ex.: `processo-detalhe.js`),
  nunca inline nos templates. Feedback ao usuário usa `mostrarToast()` (toast
  estilizado), nunca `alert()`.
- Responsividade: Bootstrap grid, `table-responsive` em TODAS as tabelas,
  breakpoints em 576px, 768px e 992px. Ver `docs/AJUSTES-UI.md` para histórico
  completo de correções.

## Design system — régua de tokens (2026-08-05)
Camada semântica sobre os primitivos `--rs-*` (paleta), em `app.css`,
`:root`: `--saur-surface`/`--saur-surface-sunken`, `--saur-text`/
`--saur-text-muted`, `--saur-border`, `--saur-action`, `--saur-state-ok`/
`-danger`/`-attention`/`-neutral`, escalas `--saur-space-1..6`,
`--saur-radius-sm/md/lg/pill` e `--saur-font-xs/sm/md/lg/xl`. **Leva de
infraestrutura apenas** — os componentes existentes ainda não foram
migrados em massa (só alguns `border-radius`/`font-size` do próprio
`app.css`, ver comentários no arquivo); migrar `style=` inline dos
templates é trabalho de uma leva futura, à parte. `DesignSystemFontSizeInlineTest`
guarda essa limpeza (falha até o último `style="font-size:..."` fora da
escala ser migrado — falha esperada e documentada, não um bug).
- **Densidade por portal**: `<html data-densidade="operacional|confortavel">`,
  setado via script inline em `layout.html :: navbar` a partir de
  `GlobalModelAdvice.densidadeAtual()` (`th:inline="javascript"`
  obrigatório). ADMIN/OPERADOR = `operacional` (mais compacto);
  AVALIADOR/SOLICITANTE/anônimo = `confortavel`. `[data-densidade="..."]`
  redefine `--saur-font-md`/`--saur-space-4`/`--saur-radius-md`.
- **Tom em vez de classe Bootstrap**: vocabulário fixo `"ok"|"danger"|
  "attention"|"neutral"`, exposto por `StatusProcesso.getTom()`,
  `SituacaoPedidoView.tom()`, `PainelLinha.CelulaMedico.tom()` e
  `EtapaFluxo.tom()` (os antigos `getBootstrapBadge()`/`classeCor()`/`cor()`
  continuam funcionando, só `@Deprecated`). Fragment
  `layout :: tomBadge(tom, texto, icone)` traduz para Bootstrap — nenhum
  template migrado a consumi-lo ainda nesta leva.

## Organização do repositório (limpeza de 2026-07-29)
A raiz só tem o essencial: `pom.xml`, `CLAUDE.md`, `README.md`, os scripts de
uso diário (`start.ps1`/`start.sh`, `test.ps1`/`test.sh`, `e2e.ps1`) e os
diretórios `src/`, `docs/`, `deploy/`, `scripts/`, `teste-pdfs/`, `.github/`.
- `scripts/` — utilitários avulsos que **não** são do fluxo diário: hoje
  `testar-portas.ps1` e `git-hooks/pre-commit` (hook opcional, não instalado
  sozinho — `.git/hooks/` não é versionado pelo git — que roda só `mvn -o
  compile` quando há `.java` staged; a suíte completa continua sendo gate só
  do CI, não do commit local, decisão de 2026-08-21). Os scripts documentados
  acima ficam na raiz de propósito, porque o CLAUDE.md e o README os citam
  como `.\start.ps1` etc.
- `docs/historico/` — **arquivo morto**: notas de sessão e relatórios de
  vistoria antigos, movidos da raiz. Conteúdo já absorvido neste CLAUDE.md;
  vários têm encoding corrompido (mojibake) da época em que foram criados.
  Não é fonte da verdade para nada — não consultar para decidir comportamento
  do sistema, só para arqueologia.
- **`brasao.png` existe em UM lugar só:** `src/main/resources/static/brasao.png`
  (carregado do classpath por `PdfCabecalhoStamper`, `PdfRelatorioBuilder`,
  `RelatorioAnualService` e `RelatorioAvaliadorService`). Havia uma cópia
  idêntica byte a byte na raiz, morta, removida em 2026-07-29 — não recriar.
- `dist/` (empacotamento desktop, removido em 2026-07-03) e `node_modules/` +
  `package-lock.json` (experimento bun/typescript nunca usado — o projeto é
  Maven puro) foram apagados do disco em 2026-07-29. Continuam no `.gitignore`
  como guarda, mas **não devem voltar a existir**.
- `data/` é o H2 de dev + anexos locais (gitignored) — **nunca apagar** numa
  limpeza, é o sandbox de teste em uso.

### Organização de pacotes em `src/main/java/` (reorganização enxuta de 2026-07-29)
- **`bootstrap/`** (novo): tudo que roda uma vez no boot e não é `@Configuration`
  de verdade — `AdminBootstrap`, `MembroDevSeed`, `SchemaMigration`,
  `EnumCheckConstraintValidator` (+ seu par `EnumCheckConstraintAdvice`, que
  antes vivia em `web/`). `config/` ficou só com `SecurityConfig`,
  `AgendamentoConfig`, `EmailProperties` — configuração Spring de verdade.
- **`service/dto/`** e **`web/dto/`** (novos): DTOs/records de apoio que
  estavam soltos misturados com serviços/controllers de verdade —
  `service/dto/EmailTemplate.java`, `PassoWizard.java`, `EtapaFluxo.java`;
  `web/dto/AcaoResponse.java`, `EmailPreviewResponse.java`,
  `IaTextoResponse.java`, `PainelLinha.java`. `Iniciais`, `NomePadraoAnexo` e
  `ConflitoEquipeMatcher` **continuam em `service/`** — são utilitários de
  regra de negócio de verdade, não DTOs.
- **`@Auditavel`** (renomeado de `@LogAuditoria`, em
  `service/auditoria/`): havia duas classes chamadas `LogAuditoria` — a
  entidade JPA (`domain/LogAuditoria.java`, continua com esse nome) e essa
  anotação de auditoria automática via AOP, mesmo nome simples forçando
  imports qualificados e confundindo busca. Só a anotação foi renomeada; a
  entidade não mudou.
- Escopo desta reorganização foi deliberadamente **enxuto**: `service/` (35
  arquivos na época, 45 em 2026-08-25 — cresceu com features novas, número
  não é reavaliado a cada sessão) e `web/` (23 arquivos na época, 20 em
  2026-08-25 — parte migrou para `web/dto/`) continuam pacotes "achatados" —
  quebrá-los em subpacotes temáticos (e-mail, PDF/relatório, processo) ficou
  fora de escopo por exigir atualizar import em cascata num sistema de
  produção com deploy automático; avaliar numa sessão dedicada, se fizer
  sentido.

## Convenções de código
- Entidades JPA em `domain/` com getters/setters simples (sem Lombok).
- Serviços em `service/`, controllers em `web/`, repos em `repository/`.
- Templates Thymeleaf usam os fragments de `templates/layout.html`.
- **NUNCA aninhar expressoes ternarias em mais de 3 niveis** em atributos Thymeleaf (`th:classappend`, `th:class`, `th:style`). O parser do Thymeleaf quebra com multi-line ternaries aninhados. Preferir `th:switch` ou `th:with` para pre-calcular valores complexos. Exemplo RUIM: `th:classappend="${a} ? x : (${b} ? y : (${c} ? z : w))"` (4 niveis, risco de quebra). Exemplo BOM: `th:classappend="${a} ? x : (${b} ? y : 'default')"` (max 2 niveis, seguro). **Nunca** usar `th:if` + `th:unless` no mesmo elemento — combinar numa unica expressao `th:if="${cond and !outra}"`.
- **`/*[[expr]]*/` (natural templating em JS) EXIGE `th:inline="javascript"` na tag `<script>`.** Sem esse atributo, Thymeleaf NAO reconhece o padrao de comentario e so substitui o `[[expr]]` interno, deixando os delimitadores `/* */` como comentario JS literal ao redor — o navegador enxerga so o fallback depois do comentario (`/*valorreal*/ 'fallback'` vira, na pratica, `'fallback'`, porque o `/* ... */` vira comentario de verdade e e ignorado). Bug real descoberto em 2026-07-28 rodando o chat AJAX contra um servidor de verdade: `pollUrl: /*[[@{...}]]*/ ''` estava renderizando como string vazia (fallback) em produtos, nao a URL real, porque as 3 tags `<script>` do chat nao tinham `th:inline="javascript"`. Corrigido nas 3. **Suspeita:** o mesmo padrao usado antes (`/*[[${temMsgNaoLida}]]*/ false` da vistoria de notificacao de 2026-07-28, ja removido/substituido nesta sessao) tambem nunca tinha `th:inline="javascript"` — a feature de notificacao ao carregar a pagina pode nunca ter disparado de verdade em producao, sempre caindo no fallback `false`. Nenhum teste (`@WebMvcTest`/`MockMvc`) pega isso porque eles testam status/model attributes, nao o JS renderizado de fato.
- Não commitar segredos: `application-local.yml`, `deploy/sgpur.env` e `/dist/`
  estão no `.gitignore`.
- Testes `@WebMvcTest` usam `@MockitoBean` (import
  `org.springframework.test.context.bean.override.mockito.MockitoBean`), **não**
  o `@MockBean` antigo (`org.springframework.boot.test.mock.mockito.MockBean`)
  — depreciado desde o Spring Boot 3.4 e removido em versão futura.
- **Rota que grava algo irreversível exige um teste do CAMINHO DE FALHA, sem
  mock do serviço.** `@WebMvcTest` + `@MockitoBean` no service **não consegue**
  pegar erro de transação: sem o proxy do Spring não existe transação, então a
  classe inteira de bug é *inexprimível* nesse tipo de teste. Foi assim que
  passaram despercebidos, com a suíte verde, o voto do avaliador sendo perdido
  (`AvaliadorController`, 2026-07-29) e ~15 endpoints devolvendo "Erro interno"
  no lugar da mensagem de negócio. Para voto, decisão, envio de e-mail oficial,
  exclusão e qualquer escrita irreversível: escreva ao menos um
  `@SpringBootTest` (H2 real, serviço real) que **force a falha do
  pós-processamento** e comprove que a escrita principal sobreviveu e que o
  usuário recebeu erro tratado — não 500. Ver
  `AvaliadorVotoTransacaoIntegrationTest` como modelo.
- **Teste de atualização deve reler do banco e conferir campo a campo.** Os
  métodos `atualizar()` copiam campo a campo; esquecer um campo faz o usuário
  salvar, ver "sucesso" e perder o dado **sem nenhum erro**. Já aconteceu 3x:
  `UsuarioService.atualizar` (e-mail), `MembroController.salvar`
  (`persist` em vez de `merge`) e `ControleUrgenciaService.atualizar`
  (`dataVencimento`, achado em 2026-07-29 — o form oferecia o campo e o
  serviço o ignorava). O teste que impede a recaída altera **todos** os campos
  editáveis com valores distintos, salva, **relê a entidade do banco** e
  asseve **cada campo**; com mock do repositório ele passa mesmo com o bug,
  que foi exatamente como a família escapou.
- `SecurityConfig`: `requestMatchers(String...)` usa padrão de string simples
  (ex.: `"/h2-console/**"`), **não** `AntPathRequestMatcher.antMatcher(...)`
  — o Spring Security resolve o matcher automaticamente; `AntPathRequestMatcher`
  está deprecated e marcado para remoção.
- **`dashboard.html` (Painel) foi migrado para Bootstrap + app.css**
  (commit `3bfba9b`, 2026-07-09): removeu Tailwind em favor das classes
  `stat-card-*` com `--rs-*` CSS variables e grid Bootstrap (`row-cols-*`).
  O arquivo `static/css/tailwind-dashboard.css` não é mais referenciado por
  nenhum template. Ver `docs/AJUSTES-UI.md` para detalhes. (Nota de merge
  2026-07-21: a cópia `rafaelioppi/urgencia` ainda descrevia o dashboard como
  Tailwind pré-compilado — desatualizado, essa migração já removeu o arquivo
  `tailwind-dashboard.css`; verificado no código pós-merge que não sobra
  nenhuma classe Tailwind em `dashboard.html`.)
- **`ddl-auto: update` não faz backfill em coluna nova.** Adicionar um campo
  que o Hibernate trata como obrigatório para gravar (ex.: `@Version`) numa
  entidade que já tem linhas no banco cria a coluna com valor `NULL` nessas
  linhas antigas — o próximo UPDATE nelas quebra (NPE dentro do Hibernate ao
  tentar incrementar/validar o campo, sem stacktrace óbvio até
  `journalctl`). Aconteceu em 2026-07-10: `Processo.versao` (`@Version`,
  commit `8f98d60`) deixou processos antigos com `versao = NULL` em prod;
  qualquer salvamento neles (editar, decidir, reabrir, anexar) dava 500.
  Corrigido com backfill manual via Neon SQL Console:
  `UPDATE processo SET versao = 0 WHERE versao IS NULL;`. **Sempre que
  adicionar `@Version` ou qualquer coluna que passa a ser tratada como
  não-nula numa entidade já populada, rodar esse tipo de backfill em prod
  logo após o deploy** (não há Flyway/Liquibase neste projeto — é
  responsabilidade manual).
  **Backfill de `Usuario.versao` feito (2026-07-29).** `@Version` foi
  adicionado a `domain/Usuario.java` (era a única entidade "quente" sem lock
  otimista; Processo, Parecer, MembroUrgenciaRenal, SolicitacaoOnline e
  MensagemSolicitacao já tinham). Rodado em prod logo após o deploy do commit
  `b34643a` (2026-07-29 17:09 UTC): `UPDATE usuario SET versao = 0 WHERE
  versao IS NULL;` — 8 linhas corrigidas, confirmado 0 nulos restantes.
- **`ddl-auto: update` também não atualiza CHECK constraints de enum.**
  Mesma classe do pitfall acima, mas para colunas de enum
  (`@Enumerated(EnumType.STRING)`), que ganham uma constraint
  `CHECK (coluna IN (...))` com a lista de valores **congelada no momento em
  que a tabela foi criada**. Adicionar um valor novo ao enum Java **não**
  propaga pra essa constraint: o `ddl-auto: update` só faz `ALTER TABLE ADD
  COLUMN`, nunca `DROP/ADD CONSTRAINT`.

  **Correção de premissa (verificada em 2026-07-29):** versões anteriores
  deste arquivo diziam que essas constraints eram "criadas fora do
  Hibernate, em algum momento do histórico do banco". **É falso** — quem as
  cria é o próprio Hibernate. Gerando o DDL Postgres do schema atual
  (Hibernate 6.6 + `PostgreSQLDialect`) saem CHECKs para **todas as 8
  colunas `@Enumerated(STRING)`** do projeto (`anexo.tipo`,
  `usuario.perfil`, `processo.status`, `solicitacao_online.status`,
  `parecer.resultado`, `parecer.origem`, `controle_urgencia.situacao`,
  `mensagem_solicitacao.remetente`). Ou seja, o incidente de 2026-07-27 não
  foi um caso isolado de dívida histórica: é o comportamento padrão, e vale
  para **toda tabela nova** criada pelo Hibernate em produção daqui pra
  frente.

  Incidente original: `StatusSolicitacaoOnline.PROCESSO_EXCLUIDO` (commits
  `a7f9974`/`18ec060`, 2026-07-27) funcionava em dev/H2 (schema recriado do
  zero a cada teste, sempre com a lista atual) mas quebrava em prod com
  `violates check constraint "solicitacao_online_status_check"` — qualquer
  exclusão de processo originado do Portal falhava. Corrigido com
  `ALTER TABLE ... DROP/ADD CONSTRAINT` manual na VM.

  **Estado real de produção em 2026-07-29** (consultado no Postgres da VM,
  não presumido): sobraram apenas **2** dessas constraints, ambas completas
  e corretas — `controle_urgencia_situacao_check` (4 valores) e
  `mensagem_solicitacao_remetente_check` (2 valores). As colunas de `anexo`,
  `usuario`, `parecer`, `processo` e `solicitacao_online` **não têm CHECK
  nenhuma** hoje (a de `solicitacao_online` foi derrubada na correção de
  27/07 e nunca recriada, e as demais nunca chegaram a existir nessas
  tabelas). Por isso `TipoAnexo.ANEXO_AVALIADOR` (2026-07-27) e
  `Perfil.SOLICITANTE` (2026-07-25) **não** causam erro em produção, apesar
  de terem sido adicionados depois — é sorte estrutural, não garantia.

  **Proteção automática:** existe um verificador que roda no boot e compara
  cada CHECK de enum do banco com os valores do enum Java, avisando (sem
  derrubar a aplicação) quando divergem — ver `EnumCheckConstraintValidator`
  na seção de configuração. Ele torna desnecessário lembrar dessa regra na
  mão, mas o diagnóstico manual continua sendo:
  ```sql
  SELECT conrelid::regclass AS tabela, conname, pg_get_constraintdef(oid)
  FROM pg_constraint WHERE contype = 'c'
    AND conrelid = '<tabela>'::regclass;
  ```
  Nenhum teste local pega isso sozinho: só se manifesta contra o Postgres
  real.

  **Reconfirmado por SQL direto em produção em 2026-08-03** (vistoria SSH
  real, não presumido): continuam existindo exatamente as mesmas **2**
  constraints — `controle_urgencia_situacao_check` (4 valores) e
  `mensagem_solicitacao_remetente_check` (2 valores) —, ambas completas.
  Nenhuma constraint nova apareceu nas colunas de `anexo`, `usuario`,
  `parecer`, `processo` ou `solicitacao_online`. Na mesma vistoria, também
  foi confirmado por SQL que **nenhuma coluna `@Version` está com NULL**
  hoje em `processo`, `parecer`, `usuario`, `membro_urgencia_renal` nem
  `solicitacao_online` — os backfills manuais documentados neste arquivo
  (`versao = 0 WHERE versao IS NULL`) continuam íntegros, sem regressão.

## Sessão de 2026-07-28 (correções na VM)
Todas as pendências de infra resolvidas neste ciclo:

1. **Vistoria operacional/infra**: criado `/etc/logrotate.d/sgpur` (weekly,
   rotate 4, compress), com rotina de backup de anexos adicionada ao
   `backup-db.sh` (rclone sync para Google Drive, pasta `sgpur-backups/anexos/`
   com archive versionado em `anexos-archive/`). O script roda via crontab do
   postgres (`0 3 * * *`). Backup de DB já existia (pg_dump, 14d retenção,
   rclone para `gdrive:sgpur-backups/`). **Anexos antes sem backup ← corrigido**.
2. **Jars de backup**: script `rotacionar-backups-jar.sh` (KEEP=3) já existia
   mas sem cron; adicionado ao crontab do root (`0 5 * * 0`). Rodado
   manualmente: 29 jars antigos (~70MB cada) removidos, 3 mais recentes
   mantidos. Liberados ~2GB em disco (agora 37G livres de 45G).
3. **Erro 413**: nginx real na VM (`/etc/nginx/sites-available/sgpur`) tem
   `client_max_body_size 30m;` idêntico ao `deploy/nginx-sgpur.conf` do repo.
   Nenhum log de 413 encontrado no journalctl. **Suspeita descartada** — se
   o erro reaparecer, investigar o multipart do Spring Boot
   (`spring.servlet.multipart.max-file-size`/`max-request-size`) que estava em
   25MB/30MB (application.yml) antes do commit `e15ff82` (04/07).
4. **Backfill parecer.versao**: `UPDATE parecer SET versao = 0 WHERE versao IS
   NULL` — 0 linhas afetadas (já estavam OK).
5. **Backfill membro_urgencia_renal.versao**: `UPDATE membro_urgencia_renal SET
   versao = 0 WHERE versao IS NULL` — 8 linhas corrigidas.
6. **Upgrades de dependência (patches)**: `postgresql 42.7.13`, `bootstrap
   5.3.8`, `h2 2.4.240` — atualizados no pom.xml, build OK.

7. **Backfill solicitacao_online.status**: `UPDATE solicitacao_online s SET
   status = CASE WHEN p.status = 'DEFERIDO' THEN 'APROVADA' WHEN p.status IN
   ('INDEFERIDO','CANCELADO') THEN 'REPROVADA' ELSE s.status END FROM processo
   p WHERE s.processo_gerado_id = p.id AND s.status = 'CONVERTIDA'` — 1 linha
   corrigida. A propagação CONVERTIDA->APROVADA/REPROVADA já existe no código
   desde o commit que adicionou `ProcessoService.decidir()` (linhas 422-428),
   mas dados históricos anteriores a essa implementação ficavam travados.
8. **Portal do Solicitante (timeline)**: passo 3 da timeline consulta
   `processoGerado.status` quando `solicitacao.status==CONVERTIDA`, exibindo
   o resultado (Deferido/Indeferido) em vez de "Aguardando decisao". Badge do
   topo também reflete a decisão real. Passo 4 redundante removido.
9. **Correção bootstrap 5.3.8**: `layout.html` tinha caminho hardcoded
   `/webjars/bootstrap/5.3.3/...`; atualizado para 5.3.8 após o upgrade no
   pom.xml (commit anterior). App ficou sem CSS/JS no login até esta correção.

**Vistoria geral de segurança (2026-07-28)**: auditoria completa de
autenticação, exposição de dados, IDOR e configuração. 6 correções aplicadas:
- OpenPDF 1.3.30 → 1.3.34 (CVE XXE crítico — processa PDFs de terceiros)
- GeminiService default `enabled=false` (antes `true`); prod já tinha
  kill-switch, mas default no código agora é seguro também
- Password policy: mínimo 8 chars + maiúscula + minúscula + número + especial
  (aplicado em criar, editar e alterar própria senha)
- Login audit trail: toda tentativa de login (sucesso e falha) logada com IP
  (antes só logava o bloqueio de 15 min). **Atualização mesmo dia (commit
  `cfc9f86`): o bloqueio de 15 min foi removido** — decisão deliberada de
  produto (não bug), o log de auditoria com IP continua sendo a defesa
  usada. `LoginAttemptService.estaBloqueado` agora sempre retorna `false`.
  **Limpeza de 2026-07-29:** o código morto que sobrou dessa remoção foi
  apagado — `estaBloqueado()` (e a contagem de falhas em memória que só ele
  lia), o `LockedException` inalcançável do `UsuarioDetailsService`, o ramo
  `LockedException → /login?bloqueado` do `SecurityConfig.loginFailureHandler`
  e o alerta `th:if="${param.bloqueado}"` do `login.html`. `LoginAttemptService`
  continua existindo e intacto no que importa: é o `Filter` que captura o IP
  + os `@EventListener` que logam sucesso/falha de login. Se o bloqueio um dia
  voltar, os 4 pontos precisam voltar juntos (documentado no javadoc da classe).
- LogAuditoria.PROCESSO_CADASTRADO: usa `Iniciais.de()` (antes nome completo
  do paciente no detalhe, visível na tela de auditoria ADMIN)
- Session management: timeout 30m explícito + `maxSessions=1` (concorrência
  bloqueada por usuário)

Nenhuma falha estrutural de IDOR encontrada (AvaliadorController e
SolicitanteController têm verificação de posse rigorosa; controllers de
processo usam controle por role, que é o design pretendido).

**Demais upgrades** (Spring Boot 4, Spring Security 7, OpenPDF >=1.3.35):
continuam pendentes para sessão dedicada (major version, risco de breaking
change).

**Vistoria de conformidade de regras de negócio concluída em 2026-07-24**
(cada regra da seção "Regras de negócio" deste arquivo vs. o código real,
service+controller, cobrindo maioria simples/coordenador, anexos
obrigatórios, processo encerrado, pausa "Solicita informação", fluxo de 6
passos, Recebimento/Envio, Portal do Avaliador e imparcialidade). Nenhuma
violação de regra de negócio encontrada. 4 gaps estruturais corrigidos no
mesmo dia:
- `ProcessoService.confirmarRespostaSolicitante` criado como fonte única da
  regra "Deferido exige comprovante SNT" (antes vivia duplicada em 3
  lugares: só no controller, sem espelho no service, mais uma cópia inline
  em `prepararEmailPronto`).
- `MembroController.salvar` ganhou `@Transactional` (fechava a janela de
  race condition ao desmarcar outros coordenadores CET-RS).
- `atualizarStatusPorPareceres`/`tentarDecisaoAutomatica`/
  `retomarAposInformacao` (ProcessoService) agora lançam
  `IllegalStateException` em vez de no-op silencioso quando chamados sobre
  processo já finalizado (todos os call-sites reais já garantiam isso
  antes; só torna bugs futuros ruidosos em vez de mascarados).
- `AvaliadorController.votar()` passou a expor `ProcessoVotoView`/
  `ParecerVotoView` (DTOs projetados) ao template em vez da entidade
  `Processo`/`Parecer` inteira, fechando por design o risco de um `th:text`
  futuro vazar `pacienteNome` (quebraria a regra de imparcialidade).
Suíte completa validada após as correções: **418 testes, 0 falhas** (JDK 21).

Nota: o **deploy automático via GitHub Actions foi corrigido em 2026-07-10**
(o secret `SAUR_ORACLE_SSH_KEY` estava vazio/malformado desde 21/07, todo
`Deploy` falhava; corrigido + também corrigido um falso-negativo no
health-check que tinha timeout curto demais pro boot real de ~76s contra o
Neon). Confirmado funcionando ponta a ponta (CI -> Deploy) na época.
**Atualização de 2026-08-03: voltou a ser pendência** — ver seção "Deploy"
abaixo, o repositório foi migrado e o secret não acompanhou.

## Deploy
Artefatos em `deploy/` (systemd, nginx, env de exemplo, guia). Host alvo:
**Oracle Always Free (São Paulo)** — ver `deploy/README-deploy.md`.
A **Vercel não hospeda o app Java** (histórico: só servia como front pro
Neon, que nem é mais o banco de produção — ver status abaixo).

**RESOLVIDO em 2026-08-21: IP público efêmero mudou, deploy automático
quebrado desde antes de 2026-08-17.** A pendência "Reservar o IP público"
(mais abaixo neste arquivo) nunca foi resolvida pelo usuário, e o IP mudou
de fato: `163.176.163.213` → **`163.176.30.222`**. Sintoma: `Deploy`
falhava em segundos, sempre na etapa `ssh-keyscan -H 163.176.163.213`
(timeout — nada respondendo naquele endereço), enquanto a aplicação em si
continuava **saudável** (`https://urgenciarenal.duckdns.org/login` sempre
200) — o DuckDNS já apontava pro IP novo (atualizado por algum mecanismo na
própria VM, não investigado), só o workflow e a documentação é que
continuavam com o endereço antigo hardcoded. Confirmado por SSH direto no
IP novo: VM de pé (`uptime` 29 dias, sem reboot), `sgpur.service active`,
app respondendo 200 em `localhost:3000`. **Correção:** as 3 ocorrências em
`.github/workflows/deploy.yml` (`ssh-keyscan`, `scp`, `ssh`) e as menções
operacionais em `CLAUDE.md`/`deploy/README-deploy.md` foram atualizadas
para `163.176.30.222`. **A pendência "Reservar o IP público" continua
valendo** — sem reservar, o mesmo incidente se repete na próxima vez que a
instância for parada/reiniciada pelo console Oracle.

**RESOLVIDO em 2026-08-03 (mesmo dia).** O secret foi recadastrado e o
pipeline voltou a funcionar: run `30844431318` concluiu `✓ deploy in 2m8s`
— **primeiro deploy automático bem-sucedido neste repositório**. Produção
está em `d94381d`, validada por fora (HTTP 200 + o marcador de código novo
servido em `/js/processo-detalhe.js`) e por dentro (jar de 19:13, serviço
`active`, **0 erros** no log). O relato abaixo fica como histórico do
incidente, porque a causa raiz (migração de repositório) volta a valer em
qualquer migração futura.

**O incidente (achado em vistoria SSH real de 2026-08-03): deploy
automático via GitHub Actions ficou QUEBRADO de 2026-07-31 a 2026-08-03.** O
repositório foi migrado para `centraldetransplante-cyber/urgencia` (criado
2026-07-31 12:32 UTC, **público**) — secrets do GitHub Actions **não
acompanham** o código num push/migração de repo, e o secret
`SAUR_ORACLE_SSH_KEY` nunca foi recadastrado no repositório novo. Os 3
Deploys que rodaram desde a migração (31/07, 12:35/16:09/17:47 UTC) falharam
todos na etapa de entrega, com:
```
Load key "/home/runner/.ssh/deploy_key": error in libcrypto
ubuntu@163.176.163.213: Permission denied (publickey)
```
O CI (build/testes) passa normalmente — só a entrega (`scp`/`ssh` para a VM)
quebra. **Correção:** recadastrar o secret `SAUR_ORACLE_SSH_KEY` nas
configurações do repositório novo, colando o **conteúdo integral** da chave
privada (`~/.ssh/saur_oracle`), incluindo as linhas `BEGIN`/`END` e a quebra
de linha final — a ausência dessa quebra de linha final é a causa clássica
de `error in libcrypto` (OpenSSH recusa a chave por formatação, não por
conteúdo errado). Foi exatamente essa a correção aplicada em 03/08.

**Lição para migrações futuras:** ao trocar o repositório remoto, o código
vai junto mas **os secrets do Actions não** — conferir
`Settings → Secrets → Actions` no destino **antes** de confiar no pipeline.
O sintoma engana: o CI fica verde (build/testes rodam sem secret), só a
etapa de entrega falha, então "está tudo verde" não significa "está no ar".

**Como identificar a versão em produção sem adivinhar** (técnica usada em
03/08): comparar marcadores estruturais dentro do jar com o histórico do
git — ex. `sudo unzip -l /opt/sgpur/sgpur.jar | grep bootstrap/` distingue
`a8c3b02` (9 classes em `br/gov/saude/sgpur/bootstrap/`, 0 sobras em
`config/`, `Auditavel.class` presente) de qualquer commit anterior à
reorganização de pacotes. Arquivos estáticos servidos publicamente
(`/js/*.js`) também funcionam como marcador barato, por `curl`, sem SSH.

**Status em produção (2026-07-26)**: SAUR está no ar em
https://urgenciarenal.duckdns.org/, envio de e-mail (SMTP Gmail) funcionando.
HTTPS confirmado ativo via certbot (nginx redireciona 80→443, cert válido até
2026-10-05). Banco: **Postgres local na própria VM** (`localhost:5432`, db
`sgpur`, usuário `sgpur`) — migrado do Neon em 2026-07-25 depois que o Neon
estourou a cota gratuita e o app caiu (ver memória do projeto
`incidente-neon-cota-migracao-postgres-vm-2026-07-25`); não usar mais o Neon
SQL Console para nada de produção atual, é histórico. `SGPUR_BASE_URL` já
está corretamente configurada em `/opt/sgpur/sgpur.env`
(`https://urgenciarenal.duckdns.org`) — a pendência antiga sobre isso (abaixo,
descrita como aberta em versões anteriores deste arquivo) **já foi
resolvida**, confirmado em 2026-07-26.
`deploy/README-deploy.md` ganhou 2 seções novas: acesso via Oracle Cloud
Shell quando SSH direto é bloqueado por proxy corporativo, e troubleshooting
de "Authentication failed" no SMTP (causa raiz encontrada: o `sgpur.env` da
VM tinha uma senha de app diferente da testada/válida — sempre confirmar a
senha real em uso via `/proc/<PID>/environ`, não só o arquivo, antes de
trocar de teoria). Utilitário `deploy/testar-smtp.py` testa a credencial
SMTP isolada (sem depender do Java) com `getpass`.

## Regras de negócio adicionais (consolidado de sessões 2026-07/08)

Estas regras foram implementadas em sessões posteriores à escrita original
da seção "Regras de negócio" acima e não estavam refletidas nela. Histórico
completo de cada uma em `docs/historico/CLAUDE-log-sessoes-2026-07-a-08.md`.

**Decisão — snapshot do coordenador e regra aplicada, sempre auditáveis:**
- `Parecer.eraCoordenadorNoVoto` (nullable): snapshot de
  `MembroUrgenciaRenal.coordenador` capturado no INSTANTE do voto
  (`AvaliadorController.registrarVoto`). `ProcessoValidator
  .temVotoCoordenadorFavoravel` lê esse snapshot, não o cargo ao vivo —
  corrige o caso do coordenador trocar de mão entre o voto e a decisão.
  `null` (voto legado, anterior a esta mudança) nunca conta como voto de
  coordenador — decisão conservadora, sem backfill necessário.
- `service/dto/RegraDecisao` (enum: `MAIORIA_SIMPLES`, `VOTO_COORDENADOR`,
  `CANCELAMENTO`, `NAO_DECIDIDO`) + `ProcessoValidator.regraAplicada` —
  fonte única de "por que essa decisão saiu assim", usada por
  `ExportacaoProcessoService` (dossiê), `RelatorioService` (PDF),
  `FluxoProcessoService` e o fragment `layout :: badgeRegraDecisao`,
  exibido em `processos/detalhe.html`, `processos/lista.html`,
  `arquivo/lista.html` e `dashboard.html`. Nunca mais dizer "maioria
  formada"/"2 de 3" num processo decidido pelo voto único do coordenador.
- `Processo.reaberturas` (contador, nullable, sem backfill): incrementado
  em `ProcessoService.reabrir`, que também **remove o anexo
  `RELATORIO_FINAL` da decisão anulada** (é sempre derivado, regenerado na
  próxima decisão — nunca deixar baixável um relatório afirmando um
  resultado que já foi desfeito). Badge "Reaberto Nx" no mesmo fragment
  `badgeRegraDecisao`/`badgeReaberturas`, mesmas 4 telas.
- `HistoricoParecer` (entidade de staging, append-only, nunca se mistura ao
  ciclo de vida do `Parecer` real): antes de `retomarAposInformacao` zerar
  um parecer que pediu "Solicita informação", um snapshot completo
  (incluindo a justificativa original) é gravado ali — sem isso, o pedido e
  a justificativa que motivaram a pausa somem quando o parecer é reaberto
  para novo voto. Exibido em seção colapsável no card Respostas e no
  Relatório Final.
- Portal do Avaliador mostra "Processos decididos sem o seu voto" (quem foi
  dispensado por maioria simples/coordenador antes de conseguir votar) —
  **só** número do processo + iniciais, nunca a decisão nem o voto dos
  outros avaliadores (imparcialidade).

**Solicita informação — múltiplos pedidos simultâneos (2026-08-11/12,
bug real corrigido no processo 12/2026 de produção):** a pausa não é mais
tratada como "uma rodada" única. `SolicitacaoOnlineService
.EstadoInformacaoComplementar` (record) é a fonte única: cada pedido de
informação é avaliado **independentemente** (respondido = existe anexo
`INFO_COMPLEMENTAR` enviado DEPOIS daquele pedido específico) — um pedido
novo nunca apaga a resposta a um pedido anterior, e um pedido já atendido
nunca é relistado. **Um único envio do solicitante responde a TODOS os
pedidos abertos naquele momento** (decisão de produto confirmada
2026-08-12, não exige resposta por avaliador). Lista/detalhe do Portal do
Solicitante e o placar do card Respostas do operador usam o mesmo estado —
nunca duplicar essa lógica em lugar nenhum novo. Durante a pausa, a etapa
"Respostas dos médicos" só fica CONCLUÍDA com maioria de verdade (não com
"todos responderam", já que "Solicita informação" não é veredito).

**Encaminhar informação complementar ao avaliador que pediu
(2026-08-11):** `TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR` — o operador
**redige** (nunca promove automaticamente) o texto que vai ao avaliador,
que passa pelo mesmo `VerificadorNomePaciente` do chat antes de ser salvo.
Solicitante também pode responder com **texto livre**, não só arquivo
(`SolicitacaoOnlineService.enviarInformacaoComplementar` aceita texto OU
arquivo). Quem vê o material: só quem pediu a informação naquele processo
(via `Parecer` vivo ou `HistoricoParecer`), nunca outro avaliador.

**Confirmação de conflito de equipe ao escolher os 3 médicos
(2026-08-17):** `processos/form.html` consulta `GET /processos/conflito-
equipe` assim que o trio de médicos fica completo e, se algum for da mesma
equipe do solicitante (`ConflitoEquipeMatcher`), pede confirmação via modal
antes de cadastrar — client-side, fail-open (erro de rede não bloqueia),
não substitui o aviso não-bloqueante já existente na tela de detalhe.

**Dados adicionais de identificação do paciente (2026-08-20):**
`pacienteDataNascimento` (`LocalDate`, `@NotNull`), `pacienteCpf` (11
dígitos, validado por módulo-11 via `CpfUtil`), `pacienteSexo` (enum
`Sexo`, `MASCULINO`/`FEMININO`, sem terceira opção por decisão de produto),
`pacienteNomeMae` (opcional) — em `Processo`/`SolicitacaoOnline`
(`RascunhoSolicitacaoOnline` espelha, sem obrigatoriedade). Todos
`nullable` na coluna mesmo com `@NotNull` na Bean Validation (compatível
com linhas gravadas antes destes campos existirem — mesma lacuna já
documentada para `pacienteRgct`), nenhum backfill necessário.
**`pacienteRgct` (2026-08-27, paciente preemptivo):** deixou de ter
`@NotBlank` na ENTIDADE — passou a ser condicionalmente obrigatório (só
quando `!isPreemptivo()`), validado em `SolicitacaoOnlineService.criar` e
`ProcessoDetalheController` — ver o bullet "Paciente preemptivo" em "Regras
de negócio" acima. **Nunca**
chegam ao avaliador — só até o Relatório Final/dossiê, lado do operador.
**`pacienteDataNascimento` PRECISA de `@DateTimeFormat(iso =
DateTimeFormat.ISO.DATE)`** em `Processo`/`SolicitacaoOnline` — bug real de
produção (2026-08-21): sem essa anotação, o Thymeleaf renderiza o
`LocalDate` no formato padrão da JVM (`"11/2/80"`) em vez de ISO no
`value` do `<input type="date">`, que o navegador **descarta em silêncio**
(campo aparece vazio, sem erro) — o dado ficava íntegro no banco, só
sumia visualmente ao reabrir o formulário de conversão/edição. Qualquer
`LocalDate` novo que vá para um `<input type="date">` via `th:field`
precisa dessa anotação, sempre.

**E-mail adicional (CC) do solicitante por processo (2026-08-21):**
`Processo`/`SolicitacaoOnline`/`RascunhoSolicitacaoOnline.emailAdicional`
(opcional, `@Email`, sem `@NotBlank`) — um segundo e-mail que recebe
**cópia (CC)**, nunca substituição, dos avisos automáticos sobre aquele
processo específico (`ProcessoService.finalizarResposta` e o e-mail pronto
manual equivalente em `ProcessoDecisaoController.prepararEmailPronto`,
templates `"deferido"|"indeferido"|"solicita-info"`). Fonte única do CC:
`ProcessoService.ccEmailAdicional(Processo)`. **Nunca** usado nos e-mails
ao time interno (convite/lembrete a avaliador, cancelamento). Validação de
formato explícita em `SolicitacaoOnlineService.criar` (antes do `save()`,
para não cair em 500 via `ConstraintViolationException` sem
`@ExceptionHandler`).

**Validação leve de domínio + isolamento de falha do CC (2026-08-24, achado
real de vistoria):** `@Email` só confere a FORMA do endereço, nunca se o
domínio existe — `EmailDominioValidator.dominioResolvivel` (só JDK puro,
`javax.naming`/`java.net`, sem lib nova) consulta MX e, se não houver,
A/AAAA do domínio antes de rejeitar. **Fail-open por design**: qualquer erro
que não seja uma resolução negativa clara (timeout de DNS, rede fora do ar
no próprio servidor) é tratado como "domínio ok" — só bloqueia quando NEM
MX NEM A/AAAA resolvem. Chamado em `SolicitacaoOnlineService.criar` (form do
solicitante) e `ProcessoService.atualizarDados` (form do operador,
`ProcessoDetalheController.atualizar` trata a rejeição com
`result.rejectValue` no campo, nunca cai no handler genérico de "registro
não encontrado"). **Isolamento de falha do CC** (mesmo achado):
`EmailSenderService.enviar`/`enviarComAnexo` com CC agora tentam de novo
SEM o CC se o primeiro envio falhar (JavaMailSender rejeita a mensagem
inteira — TO+CC são o mesmo envelope SMTP) — um `emailAdicional` com
domínio ruim NUNCA mais bloqueia a entrega ao destinatário principal
(solicitante), só falha de verdade quando nem sem CC funciona.

**Revisão adicional (2026-08-24, PR #120) corrigiu 2 problemas na checagem
de domínio:**
1) **Fail-open que na prática rejeitava:** `InetAddress.getAllByName` lança
a MESMA `UnknownHostException` tanto para "domínio realmente não existe"
quanto para "DNS instável/rede intermitente" — as duas causas eram tratadas
igual (rejeitando), contradizendo o fail-open prometido no javadoc.
2) **DoS síncrono na thread HTTP:** a consulta rodava direto na thread do
servlet, sem teto de tempo agregado (timeout do MX + timeout NÃO
configurável do `InetAddress` podiam, sob DNS lento, esgotar o pool de
threads do Tomcat). Corrigido rodando a checagem inteira num
`ExecutorService` DEDICADO (nunca o pool de request do Tomcat, 4 threads
daemon) com um teto RÍGIDO de 2s via `CompletableFuture.get(timeout, ...)`
— qualquer timeout, interrupção ou exceção inesperada cai no MESMO
fail-open do catch externo; só uma resposta RÁPIDA e limpa de "host not
found" (dentro do teto) continua rejeitando. Coberto por
`EmailDominioValidatorTest` (simula timeout ocupando a única thread de um
executor de teste antes da chamada real, sem depender de rede/DNS real).

**Exceção específica para erro de domínio (`EmailDominioInvalidoException
extends IllegalArgumentException`, mesma revisão):** antes,
`ProcessoDetalheController.atualizar` capturava `IllegalArgumentException`
genérica vinda de `ProcessoService.atualizarDados` e SEMPRE assumia que era
erro de `emailAdicional` (`result.rejectValue("emailAdicional", ...)`) —
qualquer outra validação de negócio dentro de `atualizarDados` (atual ou
futura) seria incorretamente atribuída a esse campo. Agora só
`EmailDominioInvalidoException` (lançada por
`SolicitacaoOnlineService.criar`/`ProcessoService.atualizarDados` só no
ponto que valida `emailAdicional`) aponta o campo; qualquer outra
`IllegalArgumentException` cai num flash de erro genérico, sem apontar
campo nenhum.

## Redesign visual — Portais do Solicitante e Avaliador (2026-08-06 a 08)

Sistema de design próprio (`app.css`, tokens `--saur-elev-*`,
`.pagina-cabecalho`/`.pagina-cabecalho-solida`, `.cartao-resultado`,
`.estado-vazio`, `.chip-protocolo`, `.superficie-apoio`) aplicado primeiro
ao Portal do Solicitante, depois estendido ao Portal do Avaliador (decisão
de produto aprovada). **Limites fixados, não reabrir sem pedido
explícito:** dourado continua = "atenção" (não virou cor de marca); sem
ilustrações SVG próprias (só bootstrap-icons em token circular); área do
operador (ADMIN/OPERADOR, densidade `operacional`) fica de fora do
redesign visual.

**Padrão de largura de container** (não uniformizar sem pedido): `.container`
puro (~1320px) para as 12+ listas densas do Operador; `.container-narrow`
(760px) para formulário/leitura de 1 item (usado nos dois lados); `.container-portal`
(980px) para lista simples (Avaliador, Solicitante); `container-fluid`
só onde há split-pane estrutural real (`processos/detalhe.html`,
`avaliador/votar.html`) — nenhuma outra tela deve usar `container-fluid`
sem essa justificativa.

**REGRA FIXA: cada opção lado a lado usa SUA PRÓPRIA cor semântica, nunca
uma genérica** (confirmado explicitamente pelo usuário, 2026-08-12).
Aplica a qualquer grupo de opções com significado distinto (voto, atalho,
badge) — reusar `--rs-green`/`--rs-red`/`--rs-gold`/`--rs-blue`, nunca
uma cor neutra única por "elegância". Já recaiu 2× no card "Atalhos" do
detalhe do processo (revertido de volta pro esquema colorido as duas
vezes) — **não reaplicar esquema neutro/uniforme nos Atalhos sem pedido
explícito do usuário**, mesmo que pareça mais "limpo".

## Chat / mensageria — regras consolidadas

Dois sistemas deliberadamente separados: `MensagemSolicitacao`
(solicitante↔operador) e `MensagemAvaliador` (avaliador↔operador, entidade
própria por causa da CHECK constraint congelada da primeira). Ambos usam
polling AJAX (`chat-solicitacao.js`, ~5s ativo / poll global de 20s fora
das telas de chat) — **nunca bifurcar/reescrever esse módulo** para um caso
novo, estender via config.

**REGRA FIXA: o chat do solicitante em `/processos/{id}` fica sempre na
barra lateral esquerda** (`col-lg-3`, último card). Já foi movido por
engano uma vez (merge de outra sessão) e teve que voltar — não mover de
novo sem pedido explícito.

`VerificadorNomePaciente` bloqueia nome do paciente/equipe solicitante no
chat avaliador↔operador (calibrado em 2026-08-10: nome inteiro **curto**
[≤2 tokens] bloqueia já com 1 token citado; equipe exige **2 tokens**
distintos para bloquear, exceto equipe já curta [≤1 token], que basta 1).
Mensagem de erro não cita qual termo disparou o bloqueio (evita "ensinar" a
burlar). Nunca cobre revelar voto de outro avaliador — mitigado só por
aviso fixo na composição + auditoria, aceito como risco não mitigável por
código.

Portal do Avaliador abre `GET /avaliador/{id}` em **modo leitura** (nunca
mais 403 cru) quando o avaliador já votou ou o processo já foi decidido —
o chat continua acessível, sem vazar decisão/voto de outros. Badge de
mensagens não lidas por processo na lista (`avaliador/lista.html`) e link
"Abrir processo" nas linhas de histórico/dispensados.

Sessão órfã (username renomeado/excluído por baixo de uma sessão ativa)
sempre vira redirect gracioso pra `/login?erro=sessao-invalida`
(`SessaoInvalidaException` + `GlobalExceptionHandler.handleSessaoInvalida`),
nunca 401/500 cru — implementado em `AvaliadorController`,
`SolicitanteController`, `SolicitacaoOnlineTriagemController` e
`ProcessoDetalheController`.

## PDF — Relatório Final, Ofício, Anual, Avaliador

O Relatório Final **tem capa** (reintroduzida em 2026-08-07 a pedido
explícito do usuário, revertendo uma remoção anterior — não tirar de novo
sem pedido explícito), única página sem carimbo/numeração institucional,
sempre em A4 cheio. Ofício de Indeferimento é **sempre um documento
anexado manualmente pelo operador** (RTF editável gerado como rascunho,
`OficioService.gerarRascunhoRtf`) — nunca gerado/anexado automaticamente
na decisão; a numeração (`Processo.numeroOficio`) é só reservada. Todos os
4 documentos institucionais (Ofício, Relatório Final, Relatório Anual,
Relatório do Avaliador) usam acentuação correta e o mesmo texto/timbre
institucional — **exceto** `ResultadoParecer.descricao`/
`StatusProcesso.descricao`, mantidos sem acento de propósito (usados
internamente e por esses PDFs via tradutor local acentuado à parte,
mudar o enum teria raio de impacto maior). `PdfCabecalhoStamper` injeta
`/ToUnicode` em fontes simples WinAnsi (hardening defensivo, bug de
extração nunca foi de fato reproduzido).

## Segurança e sessão — reforços

- **Inativar usuário revoga sessão ativa na hora (2026-08-24, achado real de
  vistoria):** antes, `UsuarioDetailsService.disabled(!u.isAtivo())` só
  bloqueava autenticações NOVAS — uma sessão HTTP já aberta (Portal do
  Avaliador, chat, voto) continuava funcionando normalmente até o timeout de
  30min mesmo com o acesso já revogado no cadastro. `SecurityConfig` agora
  expõe um bean `SessionRegistry` explícito (amarrado via
  `.sessionManagement().sessionRegistry(...)`, em vez do registry interno
  implícito que o Spring Security cria sozinho) e `.expiredUrl("/login")`
  (sem isso, o `SessionInformationExpiredStrategy` padrão devolve 200 com
  texto plano, não um redirect). `UsuarioService.revogarSessoesAtivas`,
  chamado por `alternarAtivo`/`atualizar` sempre que a transição é
  `ativo=true → false`, percorre `SessionRegistry.getAllPrincipals()`
  (nunca `getAllSessions(username, ...)` direto — o principal registrado é
  o `UserDetails`, cujo `equals` não compara igual a uma `String` crua) e
  expira cada `SessionInformation` encontrada via `expireNow()` — tolerante
  a usuário sem sessão nenhuma, nunca lança exceção. Auditoria:
  `SESSAO_REVOGADA_POR_INATIVACAO`. Coberto por
  `UsuarioInativacaoRevogaSessaoIntegrationTest` (sessão HTTP real via login
  por formulário, não `@WithMockUser` — mesmo padrão de
  `AvaliadorSessaoOrfaIntegrationTest`). O bean `SessionRegistry` é
  **JVM-local** (`SessionRegistryImpl`, em memória) — não escala em cluster
  com múltiplas instâncias; não é problema hoje (SAUR roda numa VM única,
  sem load balancer), documentado no javadoc do bean para quem um dia avaliar
  clusterizar.
  **Revisão adicional (2026-08-24, PR #120) fechou 2 bypasses:**
  1) `atualizar` agora captura `usernameAntigo`/`perfilAntigo` **antes** de
  qualquer `set...` — se o ADMIN troca `username` E `ativo=false` na MESMA
  chamada, revogar pelo username NOVO simplesmente não encontrava a sessão
  (registrada sob o username com que o login foi feito, o antigo); a
  revogação usa sempre o username antigo quando ele mudou.
  2) troca de **perfil** (role) também revoga a sessão ativa, mesmo que o
  usuário continue `ativo` — sem isso, um usuário rebaixado (ex.
  ADMIN → AVALIADOR) continuava operando com as authorities antigas (fixas
  na `Authentication` desde o login) até o timeout de 30min. Auditoria
  `SESSAO_REVOGADA_POR_MUDANCA_PERFIL` (distinta de
  `SESSAO_REVOGADA_POR_INATIVACAO`, mesmo método `revogarSessoesAtivas`,
  agora recebendo `username`/`acaoAuditoria`/`detalheMotivo` explícitos em
  vez do `Usuario` inteiro).
- **Atraso progressivo no login, NÃO bloqueio** (2026-08-07): após 2 falhas
  seguidas do mesmo username numa janela de 15min, cada falha soma atraso
  (teto 5s) — mas login com senha certa **nunca** é atrasado, mesmo logo
  após falhas. Mantém a filosofia de nunca travar o usuário legítimo (ver
  "Sessão de 2026-07-28" acima, onde o bloqueio de 15min foi removido de
  propósito em favor só do log de auditoria com IP).
- **Actuator ligado**: só `/actuator/health` é público (`show-details:
  never`, sem checar SMTP — `management.health.mail.enabled=false`); resto
  de `/actuator/**` é ADMIN-only por regra explícita (defesa em
  profundidade, já que endpoints não listados nem existem como bean).
- **CSV Formula Injection mitigado** na exportação de auditoria (campo que
  começa com `=`/`+`/`-`/`@` ganha `'` na frente antes do escape normal).
- Query de listagem/exportação nunca deve usar o padrão `:param IS NULL OR
  ...` — o H2 tolera, o **Postgres real não** (`could not determine data
  type of parameter`), incidente real em `/auditoria`. Sempre normalizar
  pra valor efetivo (string vazia, sentinela de data) antes de passar pro
  repositório, nunca `null`.

## Auditoria — filtros e exportação

Filtro por usuário/ação/período em `/auditoria` (desde 2026-08-04) +
exportação CSV (`GET /auditoria/exportar`, desde 2026-08-07, BOM UTF-8,
separador `;`). **O termo de busca/filtro nunca é gravado em log de
auditoria nem de aplicação**, em nenhuma das listas com busca (Processos,
Arquivo, Auditoria, Membros, Usuários, Controle de Urgências, Solicitações
online) — recaída conhecida do mesmo padrão de vazamento de nome de
paciente já corrigido 2× antes; nunca incluir o termo de busca numa
mensagem de auditoria nova.

## Infra / Deploy — atualizações desde a escrita original

- **IP da VM mudou em 2026-08-21**: `163.176.163.213` → `163.176.30.222`
  (IP público efêmero, muda se a instância for parada/reiniciada pelo
  console Oracle — reservar o IP continua sendo pendência real, não feita
  ainda). `.github/workflows/deploy.yml` e os comandos SSH acima já
  refletem o IP novo.
- Backup: alerta por e-mail de falha instalado e confirmado funcionando
  (best-effort, não derruba o backup se o alerta falhar); `client_id`
  próprio do rclone aparenta resolvido (aviso do client_id compartilhado
  sumiu do log).
- Deploy automático (CI→Deploy) confirmado funcionando ponta a ponta
  repetidamente; se voltar a falhar logo após migrar/trocar o repositório
  remoto, checar primeiro se o secret `SAUR_ORACLE_SSH_KEY` acompanhou a
  migração (secrets do Actions NÃO migram sozinhos).

## Decisões de "não fazer" (não reabrir sem pedido explícito)

- **Não fragmentar `processos/detalhe.html`** em arquivos/componentes
  menores — decisão de produto reafirmada em pelo menos 2 relatórios de UI
  diferentes. Fica como está, por maior que a tela seja.
- Dourado não vira cor de marca do Portal do Solicitante; sem ilustrações
  SVG próprias derivadas do logo (bootstrap-icons só); redesign visual não
  se estende à área do operador (ADMIN/OPERADOR) — ver seção de redesign
  visual acima.
- Cor dos Atalhos do detalhe do processo é o esquema colorido por botão
  (não neutro) — já revertido 2× pro colorido a pedido do usuário.

## Pitfalls de processo (reforço — já causaram recaída real)

- **`mvn test-compile` sem `clean` pode mascarar erro de compilação real**
  depois de mudar a assinatura de um método usado por testes (o
  compilador incremental do Maven não recompila de forma confiável um
  teste cuja única mudança de dependência foi noutro arquivo). Depois de
  mudar assinatura de método, rodar `mvn clean test-compile` (ou `clean
  test`) ao menos uma vez antes de confiar num `test-compile` limpo.
- **Nunca editar arquivo fonte enquanto um `mvn test`/`mvn verify` está
  rodando em background** — corrompe `target/classes`/`target/test-classes`
  e produz uma cascata de falhas de `ApplicationContext` em testes
  completamente não relacionados. Esperar o build terminar antes de editar
  de novo. (Já recaiu 2×: 2026-08-08 e 2026-08-21.)
- Um comando encadeado com `;` nunca deve terminar num comando que sempre
  sucede (`echo`, `tee` sem `pipefail`) — mascara o exit code real do
  comando que importa (`mvn ...; echo "EXIT=$?"` sempre reporta 0).

## Uso de agentes/sub-agentes — regra fixa (2026-08-23)

**SEMPRE avisar o coordenador (usuário/sessão principal) ao disparar
qualquer agente ou sub-agente — sempre, sem exceção.** Regra de projeto
explícita do usuário, motivada por um incidente real: dois agentes
independentes (um deles nunca lançado explicitamente por ninguém que o
usuário pudesse rastrear) acabaram rodando `mvn test`/editando os mesmos
arquivos concorrentemente na mesma working directory/worktree, corrompendo
`target/classes` e produzindo trabalho duplicado sem que o coordenador
soubesse que aquele agente existia.

- Antes de chamar `Agent` (lançar um sub-agente novo), avisar explicitamente
  o que vai ser lançado e por quê, mesmo que a resposta chegue só depois
  (não é pedir permissão a cada vez, é nunca lançar em silêncio).
- Um agente que ele mesmo tem a ferramenta `Agent` disponível (ex.
  `urgencia-renal` — ver `.claude/agents/urgencia-renal.md`, seção
  "Delegação a sub-agentes") **nunca deve delegar a outro sub-agente sem
  primeiro avisar quem o invocou**, via `SendMessage`. Preferir sempre fazer
  o trabalho diretamente, sequencialmente, em vez de recursar.
- Se um sub-agente for mesmo necessário, ele precisa rodar numa worktree
  **própria e isolada** — nunca a mesma pasta/worktree de quem o lançou nem
  de outro sub-agente irmão. Nunca dois processos `mvn`/build rodando ao
  mesmo tempo na mesma árvore de arquivos.
- Ao final de qualquer execução, se um sub-agente próprio ainda estiver
  rodando, avisar quem invocou antes de encerrar — nunca deixar um agente
  "órfão" sem ninguém sabendo que ele existe.

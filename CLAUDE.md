# CLAUDE.md — Guia do projeto SAUR

Sistema de Gestão de Processos de Urgência Renal (SAUR). Substitui a planilha
Excel da equipe de Urgência Renal da Secretaria de Saúde.

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
- Testes: `.\test.ps1` (ou `mvn test`) — **144 testes**, sempre com **JDK 21**.
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
  `*IT.java`), separado dos 144 testes rápidos via
  `maven-failsafe-plugin`/profile `e2e` (não roda em `.\test.ps1`/
  `mvn test`). Primeira vez só, instala o browser:
  `.\e2e.ps1 -InstalarBrowser`. Rodar sem janela (mais rápido, ex. CI):
  `.\e2e.ps1 -Headless`. Screenshot automático em `target/e2e-screenshots/`
  se o teste falhar. (Equivalente cru, sem o script: `mvn verify -Pe2e`, mas
  exige JDK 21 e Maven já no PATH da sessão — prefira `.\e2e.ps1`.)
- **Deploy em produção:** VM Oracle Cloud (`ubuntu@163.176.163.213`, domínio
  `urgenciarenal.duckdns.org`), systemd `sgpur.service`, jar em
  `/opt/sgpur/sgpur.jar` (usuário `sgpur`). Chave SSH local:
  `~/.ssh/saur_oracle`. Deploy manual: `scp target/saur-0.0.1-SNAPSHOT.jar
  ubuntu@163.176.163.213:/tmp/sgpur-novo.jar`, depois na VM `sudo cp
  /opt/sgpur/sgpur.jar /opt/sgpur/sgpur.jar.bak-<timestamp>` (backup), `sudo mv
  /tmp/sgpur-novo.jar /opt/sgpur/sgpur.jar && sudo chown sgpur:sgpur
  /opt/sgpur/sgpur.jar && sudo systemctl restart sgpur`. Validar com
  `systemctl status sgpur` e `curl -Ik https://urgenciarenal.duckdns.org/login`
  (espera 200). HTTPS já ativo via certbot (cert válido até 2026-10-05,
  renovação automática). Ver também o agente `saur-oracle-vm` para tarefas de
  VM (SSH, systemd, nginx, certbot) — mas ele só age mediante instrução direta
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
  própria e fundido em Envio (ver seção "Recebimento fundido em Envio" mais
  abaixo neste arquivo para o detalhe completo da mudança). Boa parte do
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
  desde 2026-08-05 não é mais uma aba/etapa própria, foi fundido em Envio;
  ver seção "Recebimento fundido em Envio" abaixo). Criação
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
  PDF inline). **É
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
- `scripts/` — utilitários avulsos que **não** são do fluxo diário (hoje só
  `testar-portas.ps1`). Os scripts documentados acima ficam na raiz de
  propósito, porque o CLAUDE.md e o README os citam como `.\start.ps1` etc.
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
  arquivos) e `web/` (23 arquivos) continuam pacotes "achatados" — quebrá-los
  em subpacotes temáticos (e-mail, PDF/relatório, processo) ficou fora de
  escopo por exigir atualizar import em cascata num sistema de produção com
  deploy automático; avaliar numa sessão dedicada, se fizer sentido.

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

## Sessão de 2026-07-27 (sistema de mensagens + notificações)

1. **Entidade `MensagemSolicitacao`**: `id`, `solicitacaoOnline` (FK),
   `remetente` (SOLICITANTE/OPERADOR), `remetenteId`, `texto`, `dataEnvio`,
   `lida`, `versao`. Repository com queries JPQL para contagem de não lidas
   e `@Query` para IDs distintos de solicitações com mensagens não lidas.
2. **Service + Controller**: `MensagemSolicitacaoService` (enviar, listar,
   marcar lidas, contar não lidas). Endpoints `POST /{id}/mensagem` em ambos
   `SolicitanteController` e `SolicitacaoOnlineTriagemController`.
3. **Indicador na lista de triagem**: `SolicitacaoOnlineTriagemController.lista()`
   passa `idsComMsgNaoLidaSolicitante` (`Set<Long>`) ao modelo; template
   `solicitacoes-online-lista.html` exibe badge amarelo `bi-chat-dots-fill` ao
   lado do botão "Ver" quando o solicitante enviou mensagem não lida.
4. **Cards separados "Em análise" / "Decididas"**: `SolicitacaoOnlineService.Resumo`
   record alterado de 4 campos para 5 (`aguardandoTriagem`, `emAnalise`,
   `decididas`, `devolvidas`). Solicitante vê 5 cards no dashboard.
5. **Sound + Toast notification**: fragmento `notificacaoSonora` no
   `layout.html` — função `tocarNotificacao()` (2 tons, 600Hz+900Hz, Web Audio
   API) + `mostrarToast()` que exibe toast não intrusivo. Disparados nos
   templates de detalhe quando `temMsgNaoLida` é true (mensagens não lidas da
   outra parte — só ao *receber*, nunca ao enviar). Toast some em 5s.
6. **Auto-scroll chat**: `id="chatBox"` nos dois detalhes + JS
   `chatBox.scrollTop = chatBox.scrollHeight` no final da página.
7. **Chat melhorado**: balões com nome do remetente ("Você"/"Solicitante"/
   "Equipe CET-RS"), `white-space: pre-wrap`, contador de mensagens no header.
8. **Fix overlap badge navbar**: badges de "Solicitações online" trocados de
   `position-absolute top-0 start-100 translate-middle` para `badge` inline
   com `gap-1` no link (`d-inline-flex align-items-center gap-1`). Só o badge
   do sino do avaliador (lado direito, sem adjacentes) manteve
   `position-absolute`. Resolve colisão visual com "Usuarios"/"Auditoria".
9. **Password default dev**: `application.yml` alterado de `admin123` para
   `Admin123!` para satisfazer a password policy (8 chars + maiúscula +
   minúscula + número + especial). Testes `UsuarioServiceTest` atualizados.
   `AdminBootstrapTest` corrigido. 526 testes, 0 falhas.

10. **Fix notificação falsa positiva (2026-07-28)**: notificação (som+toast)
    disparava em toda visita ao detalhe mesmo sem mensagens novas. Causas e
    correções:

    **Hipótese A — BFcache (back-forward cache):** navegador restaura HTML
    cacheado com `temMsgNaoLida=true` estale ao voltar (back/forward).
    *Correção:* `pageshow` + `location.reload()` força reload do servidor
    quando `event.persisted` é true, garantindo estado fresco.

    **Hipótese B — sessionStorage sem limpeza:** chave `notif_*` no
    `sessionStorage` nunca era limpa em carregamentos frescos, suprimindo
    notificações de mensagens realmente novas.
    *Correção:* `PerformanceNavigationTiming.type` detecta carregamento
    fresco (`type !== 'back_forward'`) e limpa a chave antes de checar
    `temMsgNaoLida`; em BFcache (`type === 'back_forward'`) a chave é
    preservada para suprimir notificação estale até o reload.

    **Hipótese C — JPQL separada para `temMsgNaoLida`:** controlador
    computava `temMsgNaoLida` via `idsSolicitacoesComMsgNaoLidaSolicitante()
    .contains(id)` — JPQL independente que podia divergir da lista de
    mensagens carregada na mesma requisição.
    *Correção:* `temMsgNaoLida` é computado em Java a partir da mesma
    `List<MensagemSolicitacao>` carregada via `listarPorSolicitacao(id)`,
    garantindo consistência com as mensagens exibidas no chat.

    **Hipótese D — `th:if` impedia registro de handler:** o script de
    notificação estava envolvido em `<script th:if="${temMsgNaoLida}">`,
    então o handler `pageshow` (quando existia) só era registrado se
    houvesse mensagem não lida no momento do carregamento — em BFcache
    o script nem existia no HTML para reagir.
    *Correção:* `<script>` agora SEMPRE renderizado (sem `th:if`), usa
    Thymeleaf inlining (`/*[[${temMsgNaoLida}]]*/`) para condicionar
    apenas o disparo da notificação, mantendo handlers e lógica BFcache
    ativos em qualquer estado.

    **Padrão para futuras páginas sensíveis a BFcache:** sempre usar
    `PerformanceNavigationTiming` (fresh vs BFcache) + `pageshow` reload
    como segurança suplementar; NUNCA usar `th:if` em scripts que
    registram event listeners; computar flags de estado no servidor a
    partir dos mesmos dados carregados (evitar JPQLs independentes).
    Ver templates `solicitacoes-online-detalhe.html` e `detalhe.html`
    (solicitante) para o padrão completo.

## Sessão de 2026-07-28 (logo Gota+Cruz + chat)

1. **Logo Gota+Cruz implementado**: substituiu `bi-droplet-fill` por SVG próprio
   nos templates. Gota dourada (`--rs-gold`) com cruz ao centro. Onde:
   - `layout.html` — navbar (20×20, cruz `--rs-blue-dark`)
   - `login.html` e `esqueci-senha.html` — telas de login (36×36, cruz `rgba(255,255,255,.2)`)
2. **Chat recolhível**: cabeçalho do chat clicável com `data-bs-toggle="collapse"`,
   chevron com `rotate(180deg)` via classe `.chevron-collapse` em `app.css`.
3. **Apagar mensagem**: endpoint `POST /{id}/mensagem/{mensagemId}/apagar` nos
   3 controllers (`ProcessoDetalheController`, `SolicitacaoOnlineTriagemController`,
   `SolicitanteController`). `MensagemSolicitacao` ganhou campos `deletada`/`deletadaEm`
   (soft delete): texto apagado (vira `null`), mas a mensagem permanece na base.
4. **Timestamps relativos**: todas as mensagens exibem "agora", "X min atras",
   "X h atras", "ontem" ou a data normal via JS `ts-relative` nos 3 templates de chat.
5. **Chat no detalhe do processo**: `ProcessoDetalheController` passou a carregar
   as mensagens da `SolicitacaoOnline` de origem e exibir o chat na tela de detalhe do
   processo (`/processos/{id}`), com campo de resposta e badge de não lidas.
6. **Badge e chevron**: os 3 templates de chat ganharam badge de contagem de mensagens
   e classe `chevron-collapse` para animação do chevron ao recolher.
7. **Fix scroll do chat ao enviar mensagem** (commit `3ba402f`): o scroll suave
   simples (`chatBox.scrollTo(...)`) rodava só no load inicial e não
   sobrevivia ao POST+redirect do formulário de mensagem, deixando o chat
   "pulando" pro topo depois de enviar. Substituído por um mecanismo baseado
   em `sessionStorage` nos 3 templates de chat
   (`processos/detalhe.html`, `processos/solicitacoes-online-detalhe.html`,
   `solicitante/detalhe.html`): ao submeter o form de mensagem, salva se o
   usuário estava perto do fim (`estaProximoDoFim`, threshold 80px) e a
   posição de scroll da página; no próximo load (`load` + `pageshow`, cobre
   BFcache — mesmo padrão da Hipótese A/D da seção de notificação acima)
   restaura scroll pro fim do chat se estava seguindo a conversa, ou
   preserva a posição se o usuário tinha rolado pra cima pra ler histórico.
   `history.scrollRestoration = 'manual'` evita o navegador brigar com essa
   lógica.

## Sessão de 2026-07-28 (ajustes finais do dia)

1. **Fix N+1 evitável no detalhe do processo** (commit `9989945`):
   `ProcessoDetalheController` buscava a `SolicitacaoOnline` de origem
   (`findByProcessoGeradoId`, entidade completa) incondicionalmente pra
   achar só o ID a exibir no link "Ver solicitação original" — mesmo em
   processos que não vieram do portal. Trocado por
   `findIdByProcessoGeradoId` (projeção só do ID) e a query só roda quando
   `processoVeioDoPortal` é `true`.
2. **Fix: chat da tela de detalhe do processo não notificava (som/toast)**.
   Quando o chat foi trazido pra `/processos/{id}` na sessão anterior (ver
   "logo Gota+Cruz + chat" acima), só o badge de contagem
   (`msgNaoLidas`) foi portado — `temMsgNaoLida`, o fragmento
   `layout :: notificacaoSonora` e a chamada de `tocarNotificacao()`/
   `mostrarToast()` nunca foram adicionados a essa tela (só existiam em
   `solicitacoes-online-detalhe.html` e `solicitante/detalhe.html`).
   `ProcessoDetalheController.detalhe` agora seta `temMsgNaoLida` (mesmo
   padrão das outras duas telas: `msgNaoLidas > 0`, calculado a partir da
   mesma lista de mensagens já carregada, `false` nos ramos sem origem de
   portal) e `processos/detalhe.html` ganhou o mesmo script anti-BFcache
   (`pageshow` + `PerformanceNavigationTiming` + `sessionStorage`) das
   outras duas telas de chat.
   **Superado pelo rework abaixo, no mesmo dia** — o mecanismo baseado em
   `temMsgNaoLida`/recarregar página inteira foi inteiramente substituído
   por polling AJAX; ver próxima seção.

## Sessão de 2026-07-28 (rework do chat: polling AJAX)

Usuário reportou o chat "horrível, demora, sem notificação" nas 3 telas
(`solicitante/detalhe.html`, `processos/detalhe.html`,
`processos/solicitacoes-online-detalhe.html`). Causa raiz: o chat era
**100% server-rendered** — cada mensagem enviada/apagada era um
`<form method="post">` clássico (POST + redirect + GET da página inteira,
não só do chat) e a notificação só rodava uma vez no `load` da página.
Não existia polling nem WebSocket — só recarregando manualmente é que uma
mensagem nova aparecia.

**Reescrito para polling AJAX** (sem WebSocket, ~5s de intervalo):
- `MensagemSolicitacaoService.MensagemChatView` (record) + `paraChat(...)`:
  projeta as mensagens já relativas a quem está vendo (`deVoce`,
  `nomeRemetente`, `podeApagar` calculados no service, evitando duplicar
  "de quem é essa mensagem" em 3 templates). `podeApagar` agora exige
  `remetenteId` exato (antes o botão de apagar aparecia pra qualquer
  OPERADOR em mensagem de outro operador e falhava no clique — corrigido
  de graça por essa reprojeção).
- Cada controller (`SolicitanteController`, `ProcessoDetalheController`,
  `SolicitacaoOnlineTriagemController`) ganhou 3 endpoints novos, paralelos
  aos clássicos (que continuam existindo, sem uso pelo JS novo):
  `GET .../mensagens` (JSON, poll — já marca como lida),
  `POST .../mensagem/ajax` e `POST .../mensagem/{id}/apagar/ajax`
  (`ResponseEntity<Map>`, 200/400 JSON em vez de redirect).
- `static/js/chat-solicitacao.js` (novo, `iniciarChatSolicitacao(cfg)`):
  módulo único usado pelas 3 telas — poll periódico, renderiza os balões via
  JS (nada de `th:each` de mensagem nos templates agora — só um `<div
  id="chatBox">` vazio), detecta mensagem nova do outro lado comparando IDs
  entre polls pra disparar `tocarNotificacao()`/`mostrarToast()` **mesmo com
  a aba já aberta** (o problema original), envia/apaga via `fetch` com o
  header CSRF (mesmo padrão de `processo-detalhe.js`), pausa o poll com
  `visibilitychange` quando a aba fica em background.
- Os 3 templates perderam o JS duplicado inline (scroll anti-BFcache,
  ts-relative, notificação por load) — tudo isso morreu junto com o
  mecanismo antigo, substituído pela única chamada a
  `iniciarChatSolicitacao({...})`.

**Bug real corrigido (achado pela auditoria, não pelo usuário):**
`ProcessoDetalheController.detalhe()` era a ÚNICA das 3 telas que carregava
as mensagens mas nunca chamava `marcarComoLidas` — o badge/notificação
ficava preso pra sempre em quem só usa essa tela. A rota deixou de poder
ser `@Transactional(readOnly = true)` (só liberado antes por ser "a única
leitura pura do controller" — comentário desatualizado, removido).

**Bug real corrigido (achado rodando o fluxo contra um H2 de verdade, não
pelos 526 testes — eles usam `@MockitoBean`, nunca tocam o banco real):**
`MensagemSolicitacao.texto` era `nullable = false`, mas
`MensagemSolicitacaoService.apagar()` sempre fazia `msg.setTexto(null)` no
soft-delete — **apagar mensagem sempre quebrava** com
`DataIntegrityViolationException` (23502, "NULL not allowed"), nas 3 telas,
nos endpoints clássicos E nos novos. Corrigido tirando `nullable = false` da
entidade. Como esperado (mesma classe de pitfall do `@Version`/CHECK de
enum documentados acima), o `ddl-auto: update` não relaxou a constraint
`NOT NULL` sozinha no Postgres de prod — confirmado via
`information_schema.columns` (`is_nullable = NO`) logo após o deploy.
**Corrigido manualmente em produção em 2026-07-28** com
`ALTER TABLE mensagem_solicitacao ALTER COLUMN texto DROP NOT NULL;`
(rodado pelo usuário via Oracle Cloud Shell, confirmado `is_nullable = YES`
depois). Apagar mensagem funciona em produção desde então.

**Gotcha do Thymeleaf que causou uma segunda rodada de bug** (ver também a
entrada em "Convenções de código"): as 3 chamadas
`iniciarChatSolicitacao({pollUrl: /*[[@{...}]]*/ '', ...})` só funcionaram
depois de adicionar `th:inline="javascript"` na tag `<script>` — sem isso,
Thymeleaf não reconhece o padrão de comentário e as URLs renderizavam como
string vazia (o fallback), não a URL real. Só foi percebido inspecionando o
HTML renderizado via `curl`, não pelos testes automatizados nem pela
primeira rodada de testes manuais (que só bateu nos endpoints JSON
diretamente, não no HTML final).

**Validação:** 526 testes (suite completa, sem regressão) + fluxo manual
completo via `curl` contra um `mvn spring-boot:run` real (H2 de arquivo
limpo): criar usuário SOLICITANTE, enviar solicitação, poll, enviar
mensagem AJAX, poll do lado do operador (confirma `marcarComoLidas`),
responder, poll do solicitante, apagar mensagem, converter solicitação em
processo e repetir o poll em `/processos/{id}` (confirma o fix do
`marcarComoLidas` que faltava nessa tela especificamente).

**Bug pós-deploy (2026-07-28, mesmo dia): som da notificação não tocava do
lado do operador.** A notificação (toast + som) do polling dispara sozinha
via `setInterval`, sem nenhum gesto do usuário no momento — navegadores
mantêm a Web Audio API (`AudioContext`) suspensa até um clique/tecla/toque
direto na página, e `tocarNotificacao()` rodava sem erro (o `catch`
mascarava) mas sem som nenhum. Corrigido em `layout.html`
(`notificacaoSonora`): uma `AudioContext` compartilhada é criada e
"destravada" (`ctx.resume()`) no primeiro gesto do usuário na página
(`click`/`keydown`/`touchstart`, listener `{once:true}`), reaproveitada em
vez de criar uma nova a cada notificação. **Limitação que continua
existindo:** a notificação que chega ANTES de qualquer gesto do usuário na
página ainda pode tocar sem som (só o toast visual aparece) — é uma
restrição de segurança do navegador, não contornável em JS; a partir do
primeiro clique/tecla na página, todo som seguinte funciona normalmente.

**Bug reportado logo em seguida (mesmo dia): notificação só existia DENTRO
das 3 telas de chat.** Em qualquer outra tela (Painel, listas), pra
qualquer um dos dois lados (operador OU solicitante), nada disparava —
porque `iniciarChatSolicitacao()` só era chamado nessas 3 telas. Resolvido
com um poll GLOBAL, leve (só contagem, a cada 20s, nunca marca como lida —
isso continua sendo exclusivo de abrir a conversa de fato):
- Novo endpoint `GET /processos/solicitacoes-online/nao-lidas-count`
  (`{"total": N}`, reaproveita `contarNaoLidasOperador()` já usado pelo
  badge estático da navbar) e `GET /solicitante/nao-lidas-count` (novo
  método `MensagemSolicitacaoService.contarNaoLidasParaSolicitante` +
  `MensagemSolicitacaoRepository.
  countByRemetenteAndLidaFalseAndSolicitacaoOnlineUsuarioSolicitanteId` —
  soma TODAS as solicitações do usuário, não só uma).
- Dois blocos `<script>` novos em `layout.html`, dentro do fragment
  `navbar` (não depois dele — fragments do Thymeleaf só copiam o elemento
  marcado com `th:fragment`, não os irmãos seguintes; erro cometido e
  corrigido na hora antes de subir), um por papel
  (`sec:authorize="hasAnyRole('ADMIN','OPERADOR')"` /
  `sec:authorize="hasRole('SOLICITANTE')"`), comparando a contagem contra
  `sessionStorage` a cada poll — só notifica se **subiu** desde o poll
  anterior (primeiro poll da sessão só define a base, nunca notifica
  sozinho, mesmo padrão do `chat-solicitacao.js`).
- `layout :: notificacaoSonora` passou a ser incluído automaticamente
  dentro do fragment `navbar` (antes cada uma das 3 telas de chat incluía
  na mão) — as 25 telas que já usam `layout :: navbar` ganham
  `tocarNotificacao()`/`mostrarToast()` de graça.
- **Evita notificação duplicada**: as 3 telas de chat (que já têm seu
  próprio poll de 5s) setam `model.addAttribute("chatAtivoNestaTela",
  true)`, e os dois scripts globais usam `th:unless="${chatAtivoNestaTela}"`
  pra não rodar nelas.
- Badge da navbar (`#navBadgeMsgNaoLida`, ao lado de "Solicitações online")
  atualiza ao vivo pro lado do operador junto com o poll global; o lado do
  solicitante não tem badge equivalente na navbar (não criado, fora de
  escopo — só som/toast).
- **Validado manualmente** (não só pelos 526 testes) com `mvn spring-boot:run`
  real: mensagem do solicitante → contador do operador sobe em qualquer
  tela E fica parado (não marca lida) até abrir o detalhe de fato; mesma
  coisa na direção operador→solicitante; as 3 telas de chat confirmadas
  SEM o script global (evita duplicar) mas COM `tocarNotificacao` definido.

## Vistoria de 2026-07-31 (texto desatualizado sobre enums removidos)
Uma vistoria pontual achou que este arquivo, em vários trechos ("Regras de
negócio" e "Portal do Avaliador — Fase 1 MVP"), ainda descrevia
`OrigemParecer.OPERADOR_EMAIL`, `StatusProcesso.EM_ANALISE` e os `TipoAnexo`
legados (`SOLICITACAO_RECEBIDA`, `CAPA_PROCESSO`, `EMAIL_ENVIADO_AVALIADORES`,
`RESPOSTA_AVALIADOR`) como "legado, só leitura" — falso desde o commit
`041dc43` (2026-07-29), que removeu esses valores **por completo** do enum
(não só do caminho de escrita). Corrigido nesta sessão para refletir que os
valores não existem mais no código, sem mexer em nenhuma regra de negócio ou
comportamento — só o texto do guia estava desatualizado.

**Risco encerrado em 2026-08-03:** a remoção do commit `041dc43` foi
verificada por SQL direto no Postgres de produção (não mais "confirmado
pelo usuário", ver seção da vistoria SSH abaixo) — `parecer.origem` só tem
`AVALIADOR_SISTEMA`, `processo.status` só tem `DEFERIDO`/`INDEFERIDO`
(nenhum processo em produção hoje está em outro status) e `anexo.tipo` só
tem os 7 valores atuais do enum (`ANEXO_AVALIADOR`, `COMPROVANTE_SNT`,
`DOCUMENTO_CLINICO_AVALIADOR`, `INFO_COMPLEMENTAR`,
`OFICIO_INDEFERIMENTO`, `RELATORIO_FINAL`, `SOLICITACAO_AVALIADOR`).
Nenhum valor removido está presente em nenhuma linha real. Trata-se de um
risco **encerrado**, não mais um item "a confirmar".

## Backup e conta Oracle — vistoria e correções de 2026-08-05

Motivada por uma pergunta do usuário: *"minha VM vai perder muitas coisas
quando acabar o período grátis?"*. Tudo abaixo foi verificado por SSH na VM
real, não presumido.

### A VM é Always Free — não deve ser perdida
`shape = VM.Standard.E2.1.Micro` (1 OCPU, 1 GB RAM, `sa-saopaulo-1`, criada
2026-07-06 por `rafaelioppi@gmail.com`), lido do metadata service
(`curl -H "Authorization: Bearer Oracle" http://169.254.169.254/opc/v2/instance/`).
Esse é exatamente o shape do **Always Free** da Oracle (a conta tem direito a
2), e o boot volume de 45 GB cabe no limite de 200 GB. Quando os créditos do
trial de 30 dias acabam, recursos Always Free **continuam**; só o que excede
é recuperado. **O que não dá para ver de dentro da VM** (exige o console
Oracle): estado de cobrança da conta e existência de outros recursos criados
no trial. O `oci` CLI **não** está instalado na VM.

### Correções aplicadas na VM (2026-08-05)
1. **Cron duplicado removido.** `backup-db.sh` estava agendado em DOIS lugares
   às 03:00 — crontab do usuário `postgres` **e** `/etc/cron.d/sgpur-backup`.
   Os dois processos calculavam quase o mesmo `TIMESTAMP`, disputavam o mesmo
   `.tmp`, um vencia e o outro abortava com `mv: cannot stat` (erro diário no
   log desde 04/08, mascarando falhas de verdade), além de dois `pg_dump`
   simultâneos numa VM de 1 GB. Ficou só o `/etc/cron.d/`, que é o que
   redireciona para o log.
2. **`flock` no script**, para o problema não voltar por um cron novo ou
   execução manual sobreposta. Usa `flock -n -E 99` e **não** `exec`: com
   `exec` o processo é substituído e o tratamento nunca roda (a execução
   ignorada saía com 1 e **sem mensagem** — o silêncio que o script tenta
   eliminar). O `|| rc=$?` também é obrigatório: com `set -e`, o retorno != 0
   derrubaria o script antes de avaliar `rc=$?`. Ambos foram bugs reais
   cometidos e corrigidos durante esta sessão, com teste de execução
   concorrente de verdade.
3. **Falha de backup offsite deixou de ser silenciosa.** O script confirma
   que o dump **chegou** ao Drive (`rclone lsf`, em vez de confiar no exit
   code do `rclone copy`), loga `ERRO: ... Backup offsite FALHOU.`, termina o
   resumo com `offsite=0`, sai != 0, e mantém
   `/opt/sgpur/backups/.ultimo-offsite-ok` com a data da última cópia
   confirmada (alerta no log se passar de 3 dias). Também recusa dump menor
   que 1 KB (pg_dump truncado deixava de ser reportado como sucesso).
   Validado simulando um `rclone` quebrado no PATH.
4. **`cd "${BACKUP_DIR}"`** no início: rodado à mão de `/home/ubuntu`, o
   `find` falhava com "Failed to restore initial working directory".
5. **`/etc/logrotate.d/sgpur-backup` criado.** O `logrotate.d/sgpur` existente
   cobre só `/opt/sgpur/logs/*.log`, então `/var/log/sgpur-backup.log` crescia
   sem rotação desde julho. Precisa de `su root syslog` — `/var/log` é
   `root:syslog` com `g+w` e o logrotate recusa rotacionar sem isso.
6. **Os scripts passaram a existir no repositório** (`deploy/backup-db.sh`,
   `deploy/rotacionar-backups-jar.sh`, `deploy/cron/`). Antes viviam **só na
   VM** — perder a VM perdia junto a própria rotina de recuperação.
   `deploy/backup-anexos.sh` foi **removido**: nunca esteve agendado em cron
   nenhum e o `backup-db.sh` já sincroniza os anexos; manter os dois só
   convidava a rodar o errado.

### Estado dos backups (verificado)
Dumps diários íntegros de 2026-07-29 a 2026-08-05 no Drive, **incluindo 04 e
05/08** (os erros `mv` eram da execução duplicada que abortava, não do backup
em si). Anexos: 36 arquivos na VM, 34 no Drive (a diferença são anexos de
hoje, posteriores ao sync das 03:00). O banco inteiro comprimido tem ~12 KB.

### Alerta por e-mail da falha de backup (escrito, PENDENTE DE INSTALAR)
Aprovado pelo usuário depois da vistoria. **Sem duplicar a senha SMTP**: o
backup roda como `postgres`, que não lê `/opt/sgpur/sgpur.env` (600, dono
`sgpur`); em vez de copiar a credencial para um segundo arquivo, uma regra
pontual em `/etc/sudoers.d/sgpur-backup-alerta` deixa o `postgres`
**executar** `deploy/notificar-falha-backup.sh` como `sgpur` — e nada além
disso. A credencial continua num arquivo só.

O alerta é **best-effort por design**: notificador ausente, sudo negado ou
SMTP fora do ar viram uma linha de aviso no log e o backup segue (o oposto
seria o backup parar porque o Gmail recusou conexão). Os três caminhos
(notificador ok / ausente / quebrado) foram testados localmente com
simuladores de `pg_dump`/`rclone`/`sudo`.

**Ainda não está instalado na VM:** o envio de arquivos novos para a máquina
de produção foi bloqueado pelo classificador de segurança do harness nesta
sessão. Os arquivos estão versionados (`deploy/notificar-falha-backup.sh`,
`deploy/cron/sudoers-sgpur-backup-alerta`, e a função `alertar()` já dentro
de `deploy/backup-db.sh`) e o passo a passo de instalação + teste está em
`deploy/README-deploy.md`. **O `backup-db.sh` que roda na VM hoje é a versão
SEM a função `alertar()`** — reinstalar o do repositório é parte da mesma
tarefa.

### Pendências que EXIGEM o usuário no navegador (não dá por SSH)
Passo a passo completo de ambas em `deploy/README-deploy.md`.
- **`client_id` próprio do rclone** (https://rclone.org/drive/#making-your-own-client-id):
  o compartilhado será desativado durante 2026 e o backup offsite para. Exige
  Google Cloud Console (habilitar Drive API, tela de consentimento OAuth,
  criar ID de cliente tipo "App para computador") + `rclone config` na VM.
- **Reservar o IP público** no console Oracle (Compute → Instances → VNIC →
  IPv4 Addresses → Edit → Reserved): se for efêmero, parar a instância troca
  o IP e derruba DuckDNS/certbot. IP reservado continua dentro do Always Free.
  O `oci` CLI **não** está instalado na VM, então não dá para fazer por SSH.

## Vistoria de 2026-08-03 (inspeção SSH real na VM de produção)

Levantamento feito diretamente na VM (`ubuntu@163.176.163.213`) via SSH, não
por suposição — todos os itens abaixo foram checados no servidor.

1. **Deploy automático quebrado** — ver seção "Deploy" acima (pendência
   reaberta: migração de repositório não migrou o secret
   `SAUR_ORACLE_SSH_KEY`).
2. **A VM é COMPARTILHADA, não dedicada ao SAUR.** Rodando na mesma
   instância: `sgpur.service` (SAUR), `metamorph.service` (METAMORPH chat,
   Flask + Gunicorn), `petrobras.service` (Petrobras Study Tracker) e
   `sentinela.service` (Sentinela, bot de auditoria de segurança), além de
   `nginx` e `postgresql@14-main` compartilhados. Recursos totais: **956 MB
   de RAM** (529 usados, 88 livres, 272 disponíveis no momento da vistoria)
   e disco de 45 G com 37 G livres. O SAUR roda com `-Xmx512m`. **A folga de
   memória é apertada** — qualquer uma das outras 3 aplicações crescendo em
   uso de RAM é um risco real de OOM no SAUR (e vice-versa). O certbot dessa
   VM gerencia certificado para **4 domínios**: `urgenciarenal`,
   `metamorphchat`, `petrobrasacademy` e `sentinela-bot` — não presumir que
   qualquer mudança em nginx/certbot na VM é exclusiva do SAUR.
3. **Versão em produção**: ver bloco "Versão em produção hoje" na seção
   "Deploy" acima (commit `a8c3b02`).
4. **Enums removidos**: ver bloco "Risco encerrado" logo acima.
5. **Integridade do banco**: ver nota de 2026-08-03 na seção de CHECK
   constraints/`@Version` acima. Volume atual em produção: 4 processos, 6
   solicitações online, 8 usuários, 25 anexos.
6. **Portal do Solicitante está LIGADO em produção** —
   `/opt/sgpur/sgpur.env` tem `SGPUR_SOLICITANTE_HABILITADO=true` (confirmado
   lendo o arquivo real na VM, não o `.example`). Isso resolve a ambiguidade
   que `docs/PROTOCOLO-TESTE-PRODUCAO.md` registrava (o `sgpur.env` real é
   gitignored, então não dava pra confirmar só pelo repositório) — a seção 13
   desse protocolo foi atualizada para refletir o valor confirmado.
7. **Backups saudáveis, com um risco futuro identificado.** Cron:
   `0 3 * * *` (usuário `postgres`) roda `/opt/sgpur/backup-db.sh`; `0 5 * *
   0` (root) roda a rotação de jars. Backups diários **ininterruptos de
   2026-07-25 a 2026-08-03** (mais recente: `sgpur-20260803-030001.sql.gz`),
   sync offsite via rclone para o Google Drive funcionando (inclui anexos).
   **Risco:** o rclone avisa em toda execução que está usando o `client_id`
   compartilhado/padrão do rclone, que **vai parar de funcionar durante
   2026**. Quando isso acontecer, o backup offsite passa a falhar
   **silenciosamente** (o backup local continua rodando normalmente, então
   ninguém percebe pela ausência de erro visível) — e perder a VM sem backup
   offsite já foi estabelecido como cenário catastrófico em vistorias
   anteriores. **Correção recomendada:** criar um `client_id` próprio no
   Google Cloud Console para o rclone, ver
   https://rclone.org/drive/#making-your-own-client-id — não depender do
   client_id compartilhado além de 2026.
8. **Saúde geral confirmada**: `sgpur.service` está `active`/`enabled` desde
   30/07 02:30 UTC, **zero erros** no log desde então. Certificado de
   `urgenciarenal.duckdns.org` válido até 2026-10-05, `certbot.timer` ativo e
   rodando normalmente.

## Vistoria de bugs de 2026-08-03 (busca ampla, não restrita a merges recentes)

Vistoria com múltiplos agentes especializados em paralelo, cobrindo todo o
código (não só os PRs recém-mesclados), com verificação manual de cada achado
antes de corrigir. 5 dos 6 achados confirmados foram corrigidos nesta mesma
sessão (branch `fix/vistoria-anexo-pausa-metricas-versao`):

1. **`EmailSenderService.enviarComAnexo` enviava o e-mail SEM o anexo se o
   arquivo não existisse em disco**, em vez de falhar — o destinatário recebia
   um e-mail "de sucesso" prometendo um anexo (ofício, comprovante SNT) que
   nunca chegou. Corrigido: se `anexo != null` mas `!anexo.exists()`, o método
   agora retorna `false` sem enviar nada (mesmo padrão de falha alta usado no
   resto do serviço). `anexo == null` continua sendo o caminho legítimo de
   "enviar sem anexo de propósito" (usado por outros callers) — não mudou.
2. **`ProcessoService.registrarEnvio` podia tirar o processo da pausa
   `SOLICITA_INFORMACAO` silenciosamente.** A condição antiga
   (`status.isEmAndamento()`) incluía `SOLICITA_INFORMACAO`, então reenviar
   durante uma pausa "acordava" o processo para `ENVIADO` por engano,
   deixando o parecer que pediu a informação preso para sempre e pulando a
   trava de `ProcessoValidator.validarPausaDecisao`. Corrigido: `registrarEnvio`
   agora só avança para `ENVIADO` quando o status NÃO é `SOLICITA_INFORMACAO`.
3. **Reenvio (`RegistroEnvioService`) resetava `dataEnvio` de pareceres JÁ
   respondidos.** Isso zerava injustamente o indicador de "tempo de resposta"
   do avaliador que já tinha votado, contando o prazo dele de novo a partir
   do reenvio. Corrigido: só os pareceres com `resultado == null` (ainda sem
   voto) têm `dataEnvio` atualizada.
4. **`ProcessoValidator.temVotoCoordenadorFavoravel` lê `coordenador` ao
   vivo, não no momento do voto** (achado, **NÃO corrigido** de propósito —
   requer decisão de produto). Cenário: se o coordenador votar Favorável, o
   processo é deferido com esse voto sozinho; mas se ELE deixar de ser
   coordenador depois (outro médico assume o cargo) e o processo ainda não
   foi decidido, o voto antigo dele deixa de contar como "voto de coordenador"
   na hora do `decidir` — ou o inverso, um médico que virou coordenador DEPOIS
   de votar como membro comum ganha retroativamente o peso de coordenador.
   Corrigir exigiria uma coluna nova em `Parecer` (snapshot do papel no
   momento do voto) com decisão explícita sobre a semântica correta — fora de
   escopo desta sessão, fica pendente de definição do usuário.
5. **`ControleUrgencia` era a única entidade "quente" do sistema sem
   `@Version`** (lock otimista). Cenário real: operador A abre a tela de
   edição (carrega a `dataVencimento` atual); operador B clica "Renovar"
   nesse meio tempo (grava `RENOVADA` + vencimento hoje+30); A salva a edição
   que tinha aberto ANTES da renovação de B e sobrescreve silenciosamente o
   trabalho de B, sem nenhum erro, numa tela cuja única função é controlar o
   prazo de 30 dias. **Corrigido em 2 camadas** (`@Version` sozinho NÃO
   bastaria — ver adiante):
   - `ControleUrgencia` ganhou o campo `versao` (`@Version`) +
     `getVersao()`/`setVersao()`.
   - `controle-urgencias/form.html` ganhou um `<input type="hidden"
     th:field="*{versao}">` que carrega a versão lida junto com a tela.
   - `ControleUrgenciaService.atualizar` agora compara explicitamente
     `dados.getVersao()` (o que veio do formulário) contra a versão atual do
     registro no banco, **antes** de aplicar os demais campos, lançando
     `ObjectOptimisticLockingFailureException` em caso de divergência —
     reaproveitando o mesmo handler genérico já existente em
     `GlobalExceptionHandler` para conflito de escrita concorrente.
   - **Por que a checagem explícita é necessária:** `atualizar()` sempre
     recarrega a entidade GERENCIADA via `findById` e muta essa instância
     (nunca o objeto `dados` vindo do formulário) antes de chamar `save()`.
     Isso significa que o `@Version` puro do JPA, sozinho, nunca detectaria
     nada aqui — o `save()` sempre flusharia com a versão mais recente
     recém-lida, nunca a versão antiga que o navegador tinha. A checagem
     manual contra `dados.getVersao()` é o que realmente fecha a janela.
   - **Requer backfill manual em produção após o deploy** (mesmo pitfall de
     `@Version` novo em entidade já populada, documentado acima):
     `UPDATE controle_urgencia SET versao = 0 WHERE versao IS NULL;`
   - Coberto por `ControleUrgenciaAtualizacaoIntegrationTest
     .edicaoConcorrenteComRenovacaoNaoSobrescreveSilenciosamente` (H2 real,
     serviço real — simula A lendo antes, B renovando, A tentando salvar por
     cima).
6. **Auditoria de exportação de dossiê (`ProcessoExportacaoController`) vazava
   o NOME COMPLETO do paciente** no log de `/auditoria` (ADMIN-only) — recaída
   do mesmo padrão já endurecido em 2026-07-28 para `PROCESSO_CADASTRADO`
   (que usa `Iniciais.de()` por causa exatamente disto). Corrigido: a mensagem
   de auditoria agora só cita o id do processo, sem `dossie.nomePasta()`
   (que carrega `<Paciente> - Processo CET-RS NN-AAAA`).

## Melhorias de UI dos Portais (2026-08-03/04, execução autônoma noturna)

A pedido do usuário, foi produzido um relatório de diagnóstico + plano
faseado de UI (`docs/RELATORIO-UI-SOLICITANTE-AVALIADOR-2026-08.md`, com
Opus) cobrindo o Portal do Solicitante, o Portal do Avaliador e o card
"Respostas dos Avaliadores" da tela de detalhe do processo. O plano foi
executado de forma **autônoma** (sem supervisão visual humana em tempo
real) na branch `feat/ui-solicitante-avaliador`, um commit por fase, cada
um com suíte completa (0 falhas) + `.\e2e.ps1 -Headless` antes do commit.

**Fases 1–10 implementadas** (com uma redução de escopo na Fase 6, ver
abaixo). A Fase 8 (acentuação/microcopy) tinha sido inicialmente adiada
nesta mesma sessão por volume/risco (reescrever a acentuação de todo texto
visível em 6 templates, com dois botões localizados pelo E2E via texto
exato) — **implementada depois, a pedido explícito do usuário**, ver
detalhes logo abaixo. **Fase 11 (decisões de produto: justificativa
obrigatória para voto negativo, registro de último lembrete, rascunho de
solicitação) não deve ser implementada sem aval explícito do usuário** —
está documentada no relatório, não no código.

**Fase 8 (acentuação) — detalhes da execução:** corrigida a acentuação de
todo texto visível nos 6 templates dos dois portais + o skip-link novo em
`layout.html`. O botão "Enviar solicitacao" virou "Enviar solicitação" —
`PortalSolicitantePage.java` (E2E) e o teste
`SolicitanteControllerTest` (assert de `"Previsao baseada no historico"`)
foram atualizados no mesmo commit. `StatusSolicitacaoOnline.descricao`
também foi corrigido (sem consumidor em relatório/exportação, verificado
antes). **`ResultadoParecer.descricao` ("Favoravel"/"Nao favoravel"/
"Solicita informacao") foi DELIBERADAMENTE mantido sem acento** — alimenta
`RelatorioService`, `RelatorioAnualService`, `RelatorioAvaliadorService`,
`PdfRelatorioBuilder`, `ExportacaoProcessoService` e auditoria; mudar esse
enum teria um raio de impacto real em documentos oficiais (PDFs), bem além
do escopo de "acentuar templates". Os dois lugares onde esse valor
aparecia de forma bem visível (badge de histórico em `avaliador/lista.html`
e rótulo da opção de voto em `avaliador/votar.html`) foram corrigidos
escrevendo o termo acentuado como literal num `th:switch` — mesmo padrão
já usado desde a Fase 4 no card de Respostas dos Avaliadores — em vez de
ler `${resultado.descricao}` diretamente. Se algum dia esse enum for
revisado, os dois templates citados devem ser reconferidos.

**Fase 6 (detalhe do solicitante) teve escopo reduzido inicialmente**: na
sessão original (2026-08-03/04) a consolidação completa dos 8 blocos
`alert` condicionais num único "cartão de situação" alimentado por um
record novo no controller (itens 6.1/6.2/6.3/6.5 do relatório) **não foi
feita** — era a mudança de maior superfície/risco do plano inteiro (o
próprio relatório a marcava como "⚠⚠ fase de maior risco" e recomendava um
PR dedicado só para ela). Foram implementados ali só os itens de menor
risco da mesma fase: vocabulário unificado (Deferido/Indeferido, que antes
aparecia como "Aprovada/Reprovada"/"Pedido aprovado!"/"APROVADO" em 3
lugares diferentes da mesma tela), número do processo no `<h1>`, botão de
download promovido, e a mesma proteção do voto do avaliador (modal +
checkbox) para "Cancelar processo" quando já virou processo em análise.

**Fase 6 completa implementada em sessão posterior** (branch
`feat/ui-consolidacao-alertas-solicitante`, a pedido explícito do
usuário). Os itens que faltavam (6.1, 6.2, 6.3, 6.5, 6.6) foram concluídos:
- `web/dto/SituacaoPedidoView.java` (record novo): `rotulo`, `classeCor`,
  `icone`, `titulo`, `mensagem`, `detalhe`, `precisaAcao`,
  `mostrarNovaSolicitacao`, `anexoParaBaixar` (nested record
  `AnexoDownload(id, rotulo)`), `numeroProcesso`.
- `SolicitanteController.montarSituacaoPedido` (privado, chamado por
  `detalhe`): fonte única da decisão de status — calcula o record uma
  única vez a partir de `SolicitacaoOnline`/`Processo`, cobrindo os dois
  formatos possíveis de dado "decidido" (o espelho antigo direto em
  `APROVADA`/`REPROVADA`/`CANCELADA` e o caminho atual, `CONVERTIDA` com
  `Processo.status` já finalizado — dados históricos anteriores ao ajuste
  do espelho de status podem estar em qualquer um dos dois formatos, e o
  método trata ambos como o mesmo resultado).
- `solicitante/detalhe.html`: os 8 `<alert>` viraram um cartão único
  (`situacao.*`), posicionado entre o parágrafo "Enviada em" e a timeline.
  Quando `situacao.precisaAcao` é verdadeiro, o formulário de upload de
  informação complementar fica dentro desse cartão, no topo da página. O
  botão de download do anexo final (comprovante SNT / ofício) virou botão
  de destaque (`btn-{classeCor}`) dentro do cartão, não mais um link
  pequeno num alerta. O badge do `<h1>` e o item "Decisão" da timeline
  passaram a consumir `situacao.rotulo`/`classeCor`/`icone` também — nenhum
  lugar recalcula mais a mesma regra de status separadamente. A timeline
  ficou só como resumo de progresso (item 6.5): o texto longo de resultado
  foi removido de lá e vive exclusivamente no cartão.
- **Bug latente corrigido de graça**: `solicitante/detalhe.html` lia
  `${mensagemResposta}` para mostrar a "Mensagem enviada à sua equipe", mas
  nenhum código do `SolicitanteController` jamais preenchia esse model
  attribute — era sempre `null` (a caixa nunca aparecia, dead code desde
  que foi escrito). Agora `situacao.detalhe` é alimentado de
  `Processo.getMensagemResposta()` de verdade nos casos Deferido/Indeferido.
- Suíte completa (675 testes) e `.\e2e.ps1 -Headless` validados sem
  regressão (a única falha do E2E é a pré-existente de finalização por
  e-mail em ambiente sem SMTP local, não relacionada a esta tela).
- PR aberto contra `main`, **sem merge automático** — mesma decisão
  deliberada da sessão original: mudança de UI de maior risco visual do
  plano, requer revisão humana antes de produção.

**Achado real durante a Fase 5** (pego pela suíte antes do commit, nunca
chegou a produção): `th:attr="max=${T(java.time.LocalDate).now()}"` no
campo de data de `solicitante/nova.html` quebrava a tela inteira com
`TemplateProcessingException: "Instantiation of new objects and access to
static classes or parameters is forbidden in this context"` — o Thymeleaf
bloqueia `T(...)`/instanciação de objeto nesse tipo de contexto de
expressão. Corrigido calculando a data máxima em JS puro
(`solicitante-nova.js`) em vez de Thymeleaf/SpringEL. **Lição:** nunca usar
`T(...)` em atributos Thymeleaf; se precisar de "hoje" no template, passe
como model attribute do controller ou calcule em JS.

**Erro de metodologia encontrado e corrigido no meio da sessão:** os
primeiros comandos de verificação em background usavam o padrão
`mvn ...; echo "EXIT=$?"` — como o **último** comando da cadeia (`echo`)
sempre sucede, o exit code reportado ao orquestrador era **sempre 0**,
mascarando silenciosamente uma falha real de teste (a mesma do parágrafo
acima) nas primeiras tentativas de validação da Fase 5. As fases
anteriores (1, 3, 4) foram reconferidas lendo o **conteúdo** dos logs
diretamente (não só o exit code reportado) e estavam de fato limpas — o
mascaramento não escondeu uma falha real nelas, só invalidou a forma como
a verificação inicial tinha sido feita. A partir da Fase 5 o padrão passou
a ser `mvn ...; RC=$?; ...; exit $RC`, que propaga o código real de saída.
**Lição:** em qualquer verificação futura via comando encadeado com `;`,
nunca terminar a cadeia num comando que sempre sucede (`echo`, `tee` sem
`pipefail`) — sempre propagar o exit code do comando que importa.

Nenhuma fase mexeu em regra de negócio, em `ProcessoValidator`/
`ProcessoService` (decisão/maioria simples), na imparcialidade do
avaliador (segue só iniciais) ou no whitelist de anexos do Portal do
Solicitante. PR único aberto ao final contra `centraldetransplante-cyber/urgencia`,
**sem merge automático** — decisão deliberada de não colocar mudanças de UI
extensas em produção sem revisão visual humana, mesmo com toda a suíte e o
E2E verdes.

## Rascunho de solicitação (2026-08-04) — Fase 11, item 3

A pedido explícito do usuário (item que a Fase 11 do relatório de UI listava
como "não implementar sem aval"), o Portal do Solicitante ganhou a
capacidade de salvar um **rascunho** do formulário de "Nova solicitação"
(`/solicitante/nova`), para o solicitante continuar de onde parou se sair da
página no meio do preenchimento.

**Decisão de modelagem: entidade de staging separada
(`RascunhoSolicitacaoOnline`), não um status novo em `SolicitacaoOnline`.**
Motivo: `SolicitacaoOnline` tem `@NotBlank`/`@NotNull` em
`pacienteNome`/`pacienteRgct`/`solicitanteEquipe`/`solicitanteEmail`/
`dataSituacaoEspecial`/`justificativaClinica` — essas anotações protegem o
pedido **real**, o que a equipe de Urgência Renal de fato analisa. Relaxar
essas constraints para acomodar um rascunho parcialmente preenchido abriria
uma classe de bug/risco nova: um pedido incompleto virando uma
`SolicitacaoOnline` de verdade (e visível à triagem) sem passar pela
validação completa, por um bug futuro em qualquer código que manipule essa
entidade sem saber que ela agora pode estar "pela metade". A alternativa (2)
— entidade separada, sem nenhuma validação obrigatória — não tem esse risco:
`RascunhoSolicitacaoOnline` (`domain/RascunhoSolicitacaoOnline.java`) é uma
tabela própria (`rascunho_solicitacao_online`), com os mesmos 4 campos que o
solicitante preenche (`pacienteNome`, `pacienteRgct`,
`dataSituacaoEspecial`, `justificativaClinica`) **sem `@NotBlank`/`@NotNull`
nenhum** (só `@Size` nos campos de texto, que é null-safe — não rejeita
branco/nulo, só limita o tamanho máximo). Um rascunho pode estar totalmente
vazio sem erro.

**Um rascunho por solicitante**: `usuario_solicitante_id` é `unique` em
`RascunhoSolicitacaoOnline` — salvar um novo rascunho faz upsert (atualiza o
existente), não acumula histórico. `RascunhoSolicitacaoOnlineService.salvar`
é a fonte única dessa lógica de upsert.

**Nunca aparece para o operador.** Nenhuma tela/consulta de triagem
(`SolicitacaoOnlineTriagemController`, `SolicitacaoOnlineService.
listarPendentesTriagem`/`listarTodas`) lê `RascunhoSolicitacaoOnlineRepository`
— é uma tabela e um repositório completamente separados de
`SolicitacaoOnlineRepository`, então a isolação é estrutural (não uma
checagem de status que poderia ser esquecida em algum caminho novo). Coberto
por `RascunhoSolicitacaoOnlineServiceTest.
rascunhoSalvoNuncaApareceNaFilaDeTriagemDoOperador` (contexto real, H2:
salva um rascunho e confirma que `solicitacao_online` continua vazia).

**Fluxo:**
- `GET /solicitante/nova` (`SolicitanteController.nova`): se houver rascunho
  salvo para o usuário logado, pré-preenche os 4 campos do formulário e
  expõe `rascunhoSalvoEm` (timestamp) ao model — o template mostra um aviso
  "Rascunho carregado (salvo em dd/MM/yyyy HH:mm)" com um botão para
  descartá-lo. Sem rascunho, comportamento inalterado (formulário em
  branco, data de hoje pré-selecionada).
- `POST /solicitante/nova/rascunho` (AJAX, `salvarRascunho`): salva/atualiza
  o rascunho via `@RequestParam` individuais (não `@ModelAttribute` de
  entidade — evita qualquer superfície de mass assignment nesse endpoint,
  nem precisa de `@InitBinder` allowlist). Devolve JSON
  `{"ok": true, "salvoEm": "..."}`. Botão "Salvar rascunho" em
  `solicitante/nova.html`/`solicitante-nova.js`, salvamento **manual**
  (autosave ficou fora de escopo desta primeira versão, como o pedido
  original já sinalizava como aceitável).
- `POST /solicitante/nova/rascunho/apagar`: descarta o rascunho salvo
  explicitamente (redireciona de volta para `/solicitante/nova` em branco).
- `POST /solicitante/nova` (`criar`, o envio final): **inalterado na
  validação** — continua sendo o mesmo `@ModelAttribute("solicitacao")
  SolicitacaoOnline` com o `@InitBinder` allowlist e todas as constraints
  `@NotBlank`/`@NotNull` de sempre; o rascunho só pré-preenche o HTML, não
  contorna nada no submit. Após `solicitacaoService.criar(...)` retornar com
  sucesso, o controller apaga o rascunho do usuário
  (`rascunhoService.apagar`, best-effort dentro de um try/catch próprio —
  uma falha ali nunca desfaz o envio já commitado, só deixa um rascunho
  órfão que reaparece inofensivo na próxima visita a `/nova`).

**Testes** (padrão do CLAUDE.md — reler do banco e conferir campo a campo):
`RascunhoSolicitacaoOnlineServiceTest` (upsert com campos parciais, upsert
não duplica registro, `buscarPorUsuario` vazio, `apagar` com e sem rascunho
existente, isolamento da fila de triagem) e testes novos em
`SolicitanteControllerTest` (`novaSemRascunhoExistenteMostraFormularioEmBranco`,
`novaComRascunhoExistentePreenchePreviamenteOFormulario`,
`salvarRascunhoAceitaCamposParciaisEDevolveHorarioDeSalvamento`,
`apagarRascunhoRedirecionaParaNovaComMensagemDeSucesso`,
`criarAPartirDeUmRascunhoApagaORascunhoAposEnvioComSucesso` — este último
confirma que o envio final a partir de um rascunho segue exigindo a mesma
validação completa e apaga o rascunho só depois do sucesso).

**Cuidado de merge conhecido**: este PR mexe em `SolicitanteController.java`
e `solicitante/nova.html`; os PRs #3 (`feat/justificativa-obrigatoria-voto-negativo`)
e #4 (Fase 6 da UI, `feat/ui-consolidacao-alertas-solicitante`, mexe em
`solicitante/detalhe.html`) estavam abertos e não mesclados no momento desta
implementação — conflito de merge esperado ao integrar os três, resolvido na
hora da integração (arquivos tocados não coincidem entre este PR e o #4,
exceto o controller compartilhado).

## Fase 11.2: registro do último lembrete enviado ao avaliador (2026-08-04)

Item da "Fase 11 — Decisões de produto" do
`docs/RELATORIO-UI-SOLICITANTE-AVALIADOR-2026-08.md` (que exigia aval
explícito do usuário antes de implementar) — **aprovado explicitamente**
nesta sessão. Os outros dois itens da Fase 11 (justificativa obrigatória
para voto negativo, rascunho de solicitação) continuam pendentes de aval,
não foram tocados.

**O que foi implementado:** o card "Respostas dos Avaliadores" (detalhe do
processo, `/processos/{id}`) agora mostra, por avaliador pendente, a
data/hora do último lembrete manual enviado (`POST
/processos/{id}/lembrete-avaliador`/`lembrete-pendentes`,
`ProcessoDecisaoController`) — antes o operador não tinha nenhuma
visibilidade de quando (ou se) já tinha lembrado cada médico.

**Decisão de modelagem — campo novo em `Parecer` (opção "a" do enunciado),
não consulta a `LogAuditoria` (opção "b"):** `Parecer` já guarda outros
timestamps de ciclo de vida do próprio parecer (`dataEnvio`,
`dataResposta`, `dataHoraVoto`, e o precedente direto
`conviteEnviadoEm`, do convite automático ao Portal). Consultar
`LogAuditoria` exigiria uma query textual sobre o campo `detalhe` (que hoje
é só uma string livre, `"Processo NN/AAAA - Nome do Medico"`, sem
`parecerId` estruturado) para achar "o log de lembrete mais recente deste
parecer específico" — mais frágil e mais lento que ler um campo já
indexado por PK. Seguiu o padrão já estabelecido, sem motivo concreto para
desviar.

**`Parecer.ultimoLembreteEm` (`LocalDateTime`, nullable) — nullable é
seguro e não precisa de backfill:** distinto de `conviteEnviadoEm` (convite
automático, uma vez, ao registrar o envio) — este campo acompanha os
lembretes manuais repetidos que o operador pode disparar depois. Segue o
mesmo padrão de pitfall documentado em "Convenções de código"
(`ddl-auto: update` não faz backfill em coluna nova/obrigatória): como o
campo é **nullable desde a criação** (nem todo parecer teve lembrete
enviado ainda — `NULL` é o valor semanticamente correto de "nunca
lembrado"), não há nenhuma linha antiga que fique num estado inválido e
**nenhum backfill manual é necessário em produção** após o deploy — ao
contrário de `Processo.versao`/`Usuario.versao`/`MembroUrgenciaRenal.versao`
(`@Version`, tratados como obrigatórios), que exigiram
`UPDATE ... SET versao = 0 WHERE versao IS NULL` na VM.

**Onde a escrita acontece:** `ParecerRepository.registrarUltimoLembrete`
(`@Modifying` de linha única, mesmo padrão de
`reivindicarConviteSeElegivel`) + `ProcessoService.registrarLembreteAvaliador`
(`@Transactional`, transação própria — o controller não é
`@Transactional` de classe, ver javadoc de `ProcessoDecisaoController`).
`ProcessoDecisaoController.lembreteAvaliador`/`lembretePendentes` chamam
esse método **somente depois** de `emailSenderService.enviar(...)`
confirmar sucesso — se o SMTP falhar, o timestamp **não avança** (coberto
por teste; o operador não deve achar que já lembrou o avaliador se o
e-mail nem saiu).

**Template:** `processos/detalhe.html`, dentro da célula "Ação" da tabela
de pareceres, mostra "Último lembrete: dd/MM/yyyy HH:mm"
(`#temporals.format`, mesmo padrão já usado no resto da tela) quando
`par.resultado == null and par.ultimoLembreteEm != null` — mesma condição
de exibição do botão "Lembrar por e-mail" (só pendente, processo não
finalizado).

**Testes:** `LembreteAvaliadorTimestampIntegrationTest`
(`src/test/java/br/gov/saude/sgpur/web/`) — `@SpringBootTest` com H2 real e
`ProcessoDecisaoController`/`ProcessoService` reais (só `EmailSenderService`
mockado), seguindo o modelo de `ConviteAvaliadorDuplicidadeIntegrationTest`:
um `@WebMvcTest`/`@MockitoBean` do serviço inteiro não pegaria a escrita
real via `@Modifying`. Cobre: lembrete individual com sucesso grava o
timestamp (relido do banco); falha de envio NÃO grava; lembrete em lote
grava para todos os enviados com sucesso; um segundo lembrete atualiza o
timestamp para o momento mais recente.

**Validação:** suíte completa 679 testes, 0 falhas (JDK 21). `.\e2e.ps1
-Headless` falha neste ambiente local em `FluxoCompletoProcessoIT` no passo
5 (confirmação da resposta final ao solicitante) — **pré-existente e não
relacionado a esta mudança**: confirmado rodando o mesmo teste isolado
contra o `main` sem nenhuma alteração desta sessão (`git stash` +
`mvn verify -Pe2e -Dit.test=FluxoCompletoProcessoIT`), mesma falha. Causa
raiz aparente: `SGPUR_MAIL_USER`/`SGPUR_MAIL_FROM` não configurados nesta
máquina local, então `EmailSenderService` loga "remetente (from) nao
configurado" e o e-mail de resposta ao solicitante (que `finalizarResposta`
exige com sucesso) falha, travando o passo 5 do fluxo E2E. Não investigado
a fundo nesta sessão (fora de escopo da Fase 11.2) — fica registrado aqui
para quem for rodar o E2E localmente de novo não perder tempo achando que é
regressão.

## Ofício de Indeferimento e cobrança do Comprovante SNT (2026-08-04)

Motivado por um caso real relatado pelo usuário em produção
(`/processos/8`: o processo aparecia com o badge preto "Encerrado" mesmo com
a barra de progresso em 83%, dando a falsa impressão de que nada mais
precisava ser feito). Investigação gerou um relatório completo
(`docs/RELATORIO-OFICIO-COMPROVANTE-SNT-2026-08.md`) com achados e sugestões
priorizadas; os itens 1-7 foram aprovados e implementados no mesmo dia,
mesclados em `main` via PR #7 (commits `fb3a865`/`ebb13e7`, merge `0e5ed68`),
com deploy automático confirmado em produção (`curl` no `/login` retornando
200 logo após o merge). **O item 8 do relatório (gerar automaticamente o
ofício ao SNT, nos moldes do documento real encontrado na raiz do
repositório) foi deliberadamente adiado** — é uma feature nova, não uma
correção, e o próprio relatório recomendou tratar à parte; ainda não há
issue/branch aberta para isso, só o registro no relatório.

1. **Badge "Encerrado" redesenhado.** Antes disparava só com
   `processo.status.finalizado` (Deferido/Indeferido/Cancelado), sem
   considerar se as etapas de conclusão (ofício/comprovante SNT + resposta ao
   solicitante) já tinham sido feitas — daí a confusão do caso relatado.
   Agora existe um estado intermediário **"Decisão tomada"** (cinza,
   `bg-secondary`) para quando falta alguma dessas etapas, e "Encerrado"
   (preto) só quando `processo.emailEnviadoSolicitante == true`. Processos
   `CANCELADO` são tratados como sempre "Encerrado" (não têm etapa de
   resposta formal aplicável — ver item 6 abaixo). O banner de aviso no topo
   da tela segue a mesma lógica, com um link direto para a aba Finalização
   quando ainda há pendência.
   **Correção de 2026-08-04 (mesmo dia, depois):** essa distinção só tinha
   sido aplicada em `processos/detalhe.html`. O **Painel** (`dashboard.html`)
   e a **lista** (`processos/lista.html`) continuaram com
   `status.isFinalizado()` puro, mostrando "Encerrado" em processo que ainda
   devia comprovante SNT e resposta (bug relatado em produção no 04/2026 —
   mesmo sintoma que motivou a correção original, em outra tela). A regra
   virou **fragment único** `layout :: badgeEncerramento(p, classes)`, usado
   pelas 3 telas — não duplicar essa condição em template novo. Junto,
   `HomeController` passou a calcular "o que falta" **também** para processo
   decidido (antes só `isEmAndamento()`, então quem mais precisava de ação
   aparecia sem nenhuma pendência no Painel), via
   `FluxoProcessoService.pendenciaAberta` (`Optional`, vazio = nada pendente;
   `resumoPendencia` delega a ele). Coberto por testes que renderizam os
   templates de verdade em `HomeControllerTest`/`ProcessoListaControllerTest`
   — a correção original de detalhe não tinha nenhum teste, foi por isso que
   as outras duas telas passaram despercebidas.
2. **Atalhos da barra lateral corrigidos.** "Ofício de Indeferimento" e o
   novo atalho "Comprovante SNT" só aparecem depois que o anexo
   correspondente existe de fato, e baixam **o anexo real**
   (`/processos/anexos/{id}/download`) em vez de regenerar um PDF novo na
   hora — antes o atalho do ofício (`GET /processos/{id}/oficio`,
   `OficioService.gerar`) sempre gerava um PDF a partir dos dados atuais do
   processo, que podia divergir do arquivo realmente anexado se o operador
   tivesse substituído por upload manual.
3. **`OficioService` sem placeholders.** O texto gerado tinha "Local," (a
   palavra "Local" nunca virava uma cidade de verdade) e uma assinatura fixa
   genérica ("Responsavel - Equipe de Urgencia Renal / Secretaria de
   Saude"), sem usar a assinatura já configurável em
   `app.email.assinatura` (usada nos e-mails prontos). Corrigido: nova
   propriedade `app.email.oficio-cidade` (env `SGPUR_OFICIO_CIDADE`, default
   `Porto Alegre`) substitui "Local,", e a assinatura passa a reusar
   `app.email.assinatura`. Corpo do ofício ganhou acentuação correta (é
   documento oficial que sai da instituição — diferente da convenção
   deliberada de não acentuar `ResultadoParecer.descricao`, que é uso
   interno). `OficioService` deixou de ser stateless: agora tem construtor
   com `EmailProperties` injetado.
4. **Timbre institucional + numeração própria do ofício.** O PDF do ofício
   passou a carregar `static/brasao.png` (mesmo tratamento tolerante a
   ausência do arquivo já usado em `PdfRelatorioBuilder`/
   `RelatorioAnualService`) e ganhou `Processo.numeroOficio` (`String`,
   nullable, formato `NNNN/AAAA`, sequencial anual reiniciando a cada ano,
   **independente** do número do processo CET-RS) — inspirado no documento
   real de referência encontrado na raiz do repositório
   (`Of nº 1398 Julho 2026 SNT.doc`, um ofício de verdade emitido pela
   Central de Transplantes ao SNT). O próximo número é calculado em
   `DecisaoFinalService` no momento da geração automática, lendo o maior
   `numeroOficio` já usado no ano via `ProcessoRepository` e comparando
   **numericamente** (não como string — "999/2026" não pode perder de
   "1000/2026" numa comparação lexicográfica). Sem UNIQUE constraint na
   coluna (deliberado: não impedir o registro de uma decisão já tomada por
   causa de uma corrida rara na numeração; documentado como ressalva no
   javadoc, não resolvido com lock distribuído).
5. **Divergências entre tela e anexo fechadas.** `GET /processos/{id}/oficio`
   passou a recusar (400) fora de `INDEFERIDO` (antes gerava o PDF para
   qualquer status, inclusive Deferido). Salvar as datas do ofício
   (`POST /processos/{id}/finalizacao`) agora **regenera o anexo**
   automaticamente com as novas datas (mesmo padrão de substituição de
   `DecisaoFinalService`: salva o novo antes de remover o antigo), com aviso
   explícito na tela de que isso sobrescreve qualquer upload manual anterior.
   **Esse endpoint deixou de ser `@Transactional` no controller** — a
   escrita foi movida para `ProcessoService.atualizarDatasFinalizacao`,
   porque com transação de controller o `try/catch` em volta da regeneração
   marcaria a transação como rollback-only e o commit estouraria 500 (mesma
   classe de bug já documentada em "Convenções de código" sobre rotas que
   gravam algo irreversível). O mesmo endpoint passou a aceitar também
   `dataEnvioSnt` (ver item 7).
6. **Processo Cancelado não trava mais o progresso.** A etapa "Resposta ao
   solicitante" (`FluxoProcessoService`) exigia
   `processo.emailEnviadoSolicitante == true`, mas o botão de envio fica
   **permanentemente desabilitado** para `CANCELADO` (cancelamento não passa
   pelo fluxo de resposta formal por e-mail) — ou seja, essa etapa nunca
   podia ser concluída para um processo cancelado, travando a barra de
   progresso abaixo de 100% para sempre (bug estrutural pré-existente, só
   corrigido agora). `respostaOk` passou a considerar
   `processo.getStatus() == StatusProcesso.CANCELADO` como concluído também,
   com uma mensagem de detalhe própria explicando que cancelamento não exige
   essa etapa.
7. **Cobrança ativa do Comprovante SNT.** Antes, um processo Deferido sem
   comprovante anexado não aparecia em nenhum contador do Painel (que só
   somava processos "em andamento") — pendência invisível. Agora:
   - Card novo no Painel (`HomeController`/`dashboard.html`) contando
     Deferidos sem `TipoAnexo.COMPROVANTE_SNT`, via query dedicada (sem N+1).
   - Badge de aviso na lista de processos + filtro `?filtro=snt-pendente`
     (`ProcessoListaController`/`lista.html`).
   - Campo `Processo.dataEnvioSnt` (`LocalDate`, nullable). **Deixou de ser
     editável em 2026-08-04 (mesmo dia) — ver a regra de datas logo abaixo.**
   - **Lembrete automático diário por e-mail**, mecanismo novo (não existia
     nada equivalente automático antes — o lembrete de avaliador pendente é
     manual, clicado pelo operador): `ComprovanteSntLembreteScheduler`
     (`@Scheduled`, mesmo padrão arquitetural de
     `DecisaoAutomaticaScheduler`: isolamento de falha por item, sem
     `@Transactional` na varredura) avisa usuários ativos ADMIN/OPERADOR com
     e-mail cadastrado (reusa `UsuarioRepository.
     findByPerfilInAndAtivoTrue`, já usado para notificar sobre novas
     solicitações) quando um Deferido passa de `app.snt.lembrete.prazo-dias`
     (default 7, env `SGPUR_SNT_PRAZO_DIAS`) sem comprovante, sem reenviar
     todo dia (`Processo.ultimoLembreteSntEm`, mesmo padrão de
     `Parecer.ultimoLembreteEm`). Gate próprio, **independente** do de
     decisão automática: `app.snt.lembrete.varredura.habilitado`
     (`SGPUR_SNT_LEMBRETE_HABILITADO`, default `false` em dev/teste,
     `true` em produção) + `AgendamentoSntConfig` (`@Configuration` separada
     de `AgendamentoConfig` de propósito — um `@ConditionalOnProperty` só
     avalia UMA propriedade; sem a classe própria, desligar a varredura de
     decisão automática desligaria junto, silenciosamente, o agendador do
     lembrete SNT). Ter `@EnableScheduling` em duas `@Configuration` é seguro
     (Spring registra um único `ScheduledAnnotationBeanPostProcessor`).

Todos os campos novos em `Processo` (`numeroOficio`, `dataEnvioSnt`,
`ultimoLembreteSntEm`) são **nullable desde a criação** — nenhum backfill
manual necessário em produção (mesmo raciocínio já documentado para
`Parecer.ultimoLembreteEm`).

**Validação:** suíte completa rodada duas vezes de forma independente antes
do merge — **735 testes, 0 falhas** (JDK 21). Novos testes de integração
`@SpringBootTest` (sem mock do service) para a regeneração do ofício ao
salvar datas (escrita irreversível, seguindo a convenção do projeto) e para
o scheduler de lembrete SNT (elegibilidade, não reenvio antes do prazo,
exclusão de processos já com comprovante).

## Ofício de indeferimento: sempre ANEXADO, nunca gerado (2026-08-04)

**Decisão de produto do usuário**, no mesmo dia em que o ofício automático foi
criado: *"o ofício deve ser algo editável"* / *"ofício será sempre anexado"*.
Cada indeferimento tem particularidade de redação que nenhum modelo fixo
cobre, e um PDF gerado pelo sistema não é editável.

- `DecisaoFinalService.gerarDocumentos` **não gera nem anexa mais** o
  `OFICIO_INDEFERIMENTO`. Na decisão sai só o **Relatório Final**; do ofício
  fica apenas a **numeração reservada** (`numeroOficio`, `NNNN/AAAA`, ver
  `atribuirNumeroOficioSeNecessario`), que aparece na tela e no rascunho.
  `regerarOficio` foi removido (ficou sem chamador), junto com a dependência
  de `OficioService` nessa classe.
- `dataEmissaoOficio` **não é mais gravada na decisão** — seria datar um
  documento que ainda não existe. É gravada no upload do ofício, pela regra de
  datas da seção seguinte.
- **Novo:** `GET /processos/{id}/oficio-rascunho` →
  `OficioService.gerarRascunhoRtf`, um **RTF editável** (abre no Word/
  LibreOffice) já preenchido com número do ofício, cidade/data por extenso,
  paciente, motivo, assinatura e bloco do destinatário, seguindo a estrutura
  do ofício real da Central de Transplantes usado como referência. **RTF e não
  DOCX** porque é texto puro: nenhuma dependência nova (POI/docx4j) só para
  isso. Baixar o rascunho **não anexa nada nem move data alguma**.
- `OficioService.escaparRtf` é obrigatório em todo texto interpolado: `\`, `{`
  e `}` são caracteres de **controle** do RTF (um motivo de indeferimento com
  `{` corromperia o documento inteiro) e a acentuação precisa virar `\'hh` no
  code page declarado. Coberto por teste.
- O PDF antigo (`OficioService.gerar` + `GET /processos/{id}/oficio`)
  **continua existindo** como visualização somente-leitura, mas não é mais
  anexado por nenhum caminho.
- O **documento de registro é sempre o anexo** (`POST /processos/{id}/
  oficio-upload`): é ele que vai ao solicitante por e-mail e que o Portal do
  Solicitante disponibiliza. `validarRespostaSolicitante` continua exigindo o
  anexo antes de enviar a resposta, então a etapa fica pendente até o operador
  anexar — foi só a origem do arquivo que mudou.
- **Ressalva conhecida:** o operador pode trocar o número do ofício ao editar
  o rascunho, e aí o `numeroOficio` guardado diverge do documento anexado. O
  sistema não tem como saber — se a numeração oficial vier do controle próprio
  do setor, avaliar remover `numeroOficio` numa próxima passada.
- O E2E (`FluxoCompletoProcessoIT`, caminho INDEFERIDO) passou a **anexar o
  ofício** antes de confirmar a resposta (`ProcessoDetalhePage
  .passo5_anexarOficioIndeferimento`); antes ele contava com o ofício
  automático.

**Não confundir com o item 8 do `docs/RELATORIO-OFICIO-COMPROVANTE-SNT-2026-08.md`
(ofício ao SNT).** O modelo `Of nº 1398 Julho 2026 SNT.doc` na raiz do
repositório é um ofício **à Coordenadora-Geral do SNT em Brasília** (pedido de
alteração de status de paciente) — documento diferente do ofício de
indeferimento, que vai à equipe solicitante. Esse item continua **não
implementado**; o modelo foi usado aqui só como referência de estrutura
(numeração, cabeçalho do departamento, bloco do destinatário).

## Regra de datas: data de ato = momento do anexo, nunca digitada (2026-08-04)

**Decisão de produto do usuário, vale para todo o sistema.** A data de um ato
registrado por anexo é o **momento em que o anexo entra no sistema**, gravada
pelo relógio do servidor. Um `<input type="date">` para essas datas aceita
**data retroativa** (ou futura) — inadmissível num processo administrativo,
onde a data registrada precisa ser a do ato real, não a que alguém escolheu
digitar.

Removidos: os 3 campos de data da aba Finalização (`dataEmissaoOficio`,
`dataEnvioOficio`, `dataEnvioSnt`) e o endpoint que os gravava,
`POST /processos/{id}/finalizacao` (`ProcessoAnexoController.finalizacao` +
`ProcessoService.atualizarDatasFinalizacao`). Onde cada data é gravada hoje:

| Campo | Gravado em |
|---|---|
| `dataEmissaoOficio` | geração do ofício na decisão (`DecisaoFinalService.gerarDocumentos`) **e** upload manual (`ProcessoAnexoController.uploadOficio` → `ProcessoService.registrarDataEmissaoOficio`) |
| `dataEnvioSnt` | upload do comprovante (`uploadComprovanteSnt` → `registrarDataEnvioSnt`) |
| `dataEnvioOficio` | envio real da resposta ao solicitante (`ProcessoService.finalizarResposta`, só INDEFERIDO) |

A gravação da data acontece **depois** do anexo ter sido salvo com sucesso —
anexo recusado (extensão fora da allowlist, disco cheio) **não** move a data,
senão a tela exibiria a data de um documento que nunca entrou (coberto por
teste). Sumiu junto a regeneração do ofício "com as datas novas" que aquele
endpoint disparava, e com ela `DecisaoFinalService.regerarOficio(Long)` (sem
chamador) e a dependência de `DecisaoFinalService` no
`ProcessoAnexoController`: sem edição de data não há divergência possível
entre a data da tela e a impressa no PDF anexado.

**A regra NÃO se aplica a data que é fato do mundo real informado por
terceiro**, e não registro de um ato do sistema: `dataSituacaoEspecial`
(quando a situação de urgência começou, informada pela equipe solicitante)
continua sendo campo preenchido à mão — inclusive com data passada, que é o
caso normal. `ControleUrgencia.dataVencimento` também segue editável: é um
**prazo futuro**, não o registro de um ato ocorrido.

Coberto por `DatasFinalizacaoIntegrationTest` (`@SpringBootTest` + H2 + PDF
real, renomeado de `OficioRegeracaoDatasIntegrationTest`) e por um teste em
`ProcessoAnexoControllerTest` que trava a **remoção** do endpoint (404) — se
alguém reintroduzir a edição manual, a suíte falha.

## UI da área do operador — 5 fases EXECUTADAS (2026-08-04)

`docs/RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md` audita as **19 telas do
operador/administrador** e a camada transversal (design system,
acessibilidade, responsividade, privacidade, estados de erro) — a metade do
sistema que o `RELATORIO-UI-SOLICITANTE-AVALIADOR-2026-08.md` (dois Portais
externos, Fases 1–10 já implementadas) não cobria.

**Status: CONCLUÍDO.** As 5 fases (A–E) foram implementadas e mescladas em
`main` no mesmo dia, uma por PR (#8 a #12), cada uma com a suíte completa
verde antes do merge. A suíte saiu de **735 para 747 testes** (12 novos),
sempre 0 falhas. O relatório passou a ser registro do que foi feito — as
seções §5/§6 dele descrevem o estado ANTERIOR, não o código atual.

O que cada fase entregou (o que segue é o estado ANTES da correção, para
quem for entender o porquê de cada mudança):
- Skip-link "Pular para o conteúdo" **quebrado em 20 das 25 telas** (o
  `id="conteudo"` só existe nos 5 templates dos Portais, feitos na Fase 9);
  **18 telas não têm `<main>` nenhum**.
- **7 dos 8 formulários de upload sem `data-lock-submit`** — o mecanismo
  existe (`lock-submit.js`, escrito por causa do incidente de duplo clique de
  03/08) e não foi aplicado justamente onde a espera é maior. Detalhe: o
  formulário de voto do avaliador chama `form.submit()` direto, que **não
  dispara o evento `submit`** — ali `data-lock-submit` não teria efeito, a
  correção é o spinner no botão do modal.
- Contraste **2.56:1** (mínimo 4.5:1) em `.timeline-item.pendente
  .timeline-title` (`--rs-gray-400` sobre branco), no cartão "Progresso" de
  todo processo. A mesma correção já foi aplicada ao rótulo de passo
  bloqueado (`gray-600`, 7.58:1) — falta replicar.
- **60 rótulos de formulário** sem `for=` e sem envolver o campo.
- **5 `aria-labelledby="tab-*"` apontando para `id` inexistentes** em
  `processos/detalhe.html`; passo "bloqueado" do wizard **abre normalmente**
  ao clique (só tem `cursor: not-allowed`, sem `preventDefault`).
- `.stat-card-portal` foi extraída no `app.css` para eliminar `style=`
  repetido, aplicada ao Portal do Solicitante e **não** ao Painel, que segue
  repetindo o mesmo trecho 8× (52 dos 128 `style=` inline do sistema).
- `order-md-*` com grade `col-lg-*` inverte a ordem das colunas na faixa
  768–991px (iPad retrato): a lateral vem antes da área de trabalho.
- `@media (min-width:768px)` e `@media (max-width:768px)` se **sobrepõem em
  exatamente 768px** (o arquivo já usa `991.98` corretamente noutro ponto).
- Página de erro é **beco sem saída para AVALIADOR e SOLICITANTE**: o único
  botão aponta para `/`, que exige ADMIN/OPERADOR.
- Acentuação: a Fase 8 corrigiu só os Portais; sobram **67 ocorrências em
  texto visível (18 arquivos) + 27 em atributos**, incluindo o menu e os
  fragments de status de `layout.html`.
- `/arquivo` carrega **todos** os encerrados sem paginação (a única tela que
  só cresce), enquanto `/processos` — limitada pelo trabalho ativo — pagina
  em 15.
- Fonte Inter carregada de `fonts.googleapis.com` em toda página (LGPD +
  render-blocking); auto-hospedar resolve.

Todos corrigidos. O relatório mantém o **§10 "o que NÃO fazer"** (não
fragmentar `processos/detalhe.html`, não trocar o Bootstrap, **não acentuar
`ResultadoParecer.descricao`** — alimenta PDF oficial; decisão deliberada,
não esquecimento) — essas restrições **continuam valendo** e foram
respeitadas na execução.

**Testes novos criados nestas fases** (impedem a regressão dos achados):
- `AcessibilidadeEstruturaTest` — toda tela com navbar precisa de
  `<main id="conteudo">` (o skip-link do layout aponta para ele), `<main>`
  balanceado, e nenhuma referência `aria-labelledby`/`aria-controls` apontando
  para `id` inexistente. Verificado que ele falha de verdade ao introduzir uma
  referência quebrada.
- `FormulariosRenderizamTest` — `membros/form` e `processos/editar` não tinham
  NENHUM teste que os renderizasse (o `MembroControllerTest` é unitário puro),
  e as expressões `#fields.hasErrors` novas só são avaliadas no render.
- `ArquivoBuscaPaginadaIntegrationTest` — com a busca do Arquivo movida para
  JPQL, um mock de repositório não valida mais o filtro; este roda contra H2
  real (ordem entre páginas, contagem respeitando filtro, e a garantia de que
  processo em andamento nunca aparece no Arquivo).

**Dois achados que só apareceram DURANTE a execução, ambos corrigidos:**
1. A armadilha prevista da Fase D se confirmou: três botões que o E2E localiza
   por texto exato ("Registrar decisão", "Anexar documento clínico",
   "Relatório Final (PDF)") mudaram ao ganhar acento. `ProcessoDetalhePage` e
   dois testes de `ProcessoDetalheControllerTest` foram atualizados no mesmo
   commit.
2. **`/fonts/**` não estava liberado no `SecurityConfig`** depois de
   auto-hospedar a fonte: `/fonts/inter-*.woff2` respondia **302 para /login**,
   então a Inter nunca carregava justamente na tela de login, que é anônima.
   **Nenhum teste pegaria isso** (a suíte verifica status e model attributes,
   não o carregamento de recurso estático referenciado pelo CSS) — só apareceu
   ao subir a aplicação de verdade e pedir o arquivo. Lição: ao auto-hospedar
   qualquer recurso novo, conferir a lista de `permitAll` do `SecurityConfig`.

**Ajuste posterior no mesmo dia (PR #13), a pedido do usuário:** o valor da
coluna "Situação" de `/processos` não ficava centralizado. Em vez de corrigir
só ali, varri TODAS as tabelas comparando o alinhamento de cada `<th>` com o
`<td>` da mesma posição. Três achados: (a) Situação/Decisão em
`processos/lista` e `arquivo/lista` — a célula pode ter até 3 badges que
quebram em várias linhas, e à esquerda ficavam desencontrados do cabeçalho;
(b) **regressão da própria Fase C** em `membros/lista` (ao reescrever a coluna
"Processos" com rótulos, o `<td>` perdeu o `text-center` e o `<th>` manteve);
(c) **6 telas com `<th></th>` vazio** na coluna de ação enquanto os botões
ficavam à direita — agora `<th class="text-end">Ações</th>`, que também dá
nome à coluna para leitor de tela. Varredura final: 0 `<th>` vazio, 0
cabeçalho desalinhado do valor. A coluna Situação **nunca** esteve
centralizada (`git log -L` confirma) — não era regressão.

**Validação além da suíte:** aplicação subida de verdade (H2, porta 3011) e
conferida por `curl` — as 7 telas do operador em 200, os filtros novos de
auditoria filtrando de fato (termo impossível → estado vazio), acentuação e
`main#conteudo` presentes no HTML servido, e os 3 woff2 em
`200 font/woff2`. O E2E percorre o fluxo inteiro; a única falha é a
**pré-existente** de SMTP no passo 5 (linha 225), confirmada idêntica no
`main` sem as mudanças.

## Busca no banco + atalho de teclado nas listas do operador (2026-08-04, item 5 do RELATORIO-UI-INTERACAO-AVANCADA)

Antes desta sessão, busca existia em só 3 das telas de lista do operador
(`processos/lista`, `arquivo/lista`, `auditoria/lista`). Adicionada busca
resolvida **no banco** (JPQL `like`/`lower`, mesmo padrão de
`ProcessoRepository.buscar`) em mais 4: **Membros**
(`MembroUrgenciaRenalRepository.buscar` — nome/instituição/e-mail),
**Usuários** (`UsuarioRepository.buscar` — login/nome), **Controle de
Urgências** (`ControleUrgenciaRepository.buscarAtivas` — paciente/RGCT/
equipe, restrito aos ativos como a listagem já era) e **Solicitações online/
triagem** (`SolicitacaoOnlineRepository.buscarPorStatus`/`buscarTodas` —
paciente/RGCT/equipe solicitante, respeitando a aba Pendentes×Todas). Sem
paginação nova nesses 4 (volume pequeno, diferente de Processo/Arquivo — se
crescer, seguir o padrão de `ArquivoController`/`ProcessoListaController`).
`solicitante/lista.html` **não foi tocada** (filtro client-side em JS,
decisão de escopo do relatório — volume de uma lista pessoal do próprio
solicitante é sempre pequeno).

**O termo de busca (`q`) NUNCA é gravado em log de auditoria nem em log de
aplicação.** É dado sensível — nome de paciente digitado por quem busca. Os 4
métodos `listar`/`lista` desses controllers não chamam `AuditoriaService`
nenhuma (só as ações de escrita da mesma classe chamam, sem tocar em `q`), e
o projeto não tem nenhum filtro de log de request/query-string (`grep` por
`CommonsRequestLoggingFilter`/`getQueryString` no código: vazio). Motivo:
duas recaídas anteriores exatas desse padrão de vazamento (nome completo de
paciente em `/auditoria`) já foram corrigidas em 2026-07-28
(`PROCESSO_CADASTRADO`) e 2026-08-03 (exportação de dossiê) — ver seção
"Regras de negócio" e "Vistoria de bugs de 2026-08-03" acima. **Se algum dia
alguém adicionar uma chamada de auditoria a um desses 4 `listar`/`lista`,
nunca incluir `q` na mensagem.**

A busca continua **restrita a ADMIN/OPERADOR** em duas camadas independentes
(nenhuma delas nova — já existiam antes desta sessão, só confirmadas): rota
por `SecurityConfig.requestMatchers` (`/membros/**`, `/usuarios/**`,
`/controle-urgencias/**`, `/processos/**` — todas `hasRole`/`hasAnyRole`
ADMIN/OPERADOR) e menu por `sec:authorize` na navbar. AVALIADOR/SOLICITANTE
nunca alcançam essas URLs, então nunca veem nome completo de paciente por
esse caminho.

**Atalho de teclado `/` foca a busca da tela atual** (padrão GitHub/Gmail),
`static/js/busca-atalho.js`, incluído em `layout.html` dentro do fragment
`navbar` com `sec:authorize="hasAnyRole('ADMIN','OPERADOR')"` (defesa em
profundidade — o script só age se existir `[data-busca-atalho]` na página, e
essa marcação só existe nas 7 telas do operador). Escopo deliberadamente
mínimo, conforme o relatório: **não** é uma command palette (sem navegação
entre telas, sem busca cross-entidade), **não** intercepta a tecla enquanto o
foco está em `input`/`textarea`/`select`/`contenteditable` (o operador digita
motivo de indeferimento, corpo de e-mail etc. — `/` faz parte do texto
normal), e **nenhum atalho para voto ou ação destrutiva** (fora de escopo por
design, mesma decisão do relatório).

Testado com integração real contra H2
(`BuscaListasIntegrationTest` — as 4 queries JPQL novas, incluindo termo
nulo/vazio não filtrando e termo sem match devolvendo vazio) e testes de
controller (`MembroControllerTest`/`UsuarioControllerTest`/
`ControleUrgenciaControllerTest`/`SolicitacaoOnlineTriagemControllerTest` —
o termo chega ao serviço certo e volta ao model, sem cair no caminho
"sem filtro" por engano).

## Contador de pendências do avaliador: N+1 corrigido com query de COUNT (2026-08-04)

Item 2 do `docs/RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md` (relatório de
diagnóstico de interação avançada, referenciado pelo PR #19 ainda não
mesclado). `GlobalModelAdvice.pendentesAvaliador()` — um `@ModelAttribute`
de `@ControllerAdvice`, portanto executado em **toda** requisição de um
usuário AVALIADOR — carregava as entidades `Parecer` inteiras
(`ParecerRepository.findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull`)
e filtrava em Java navegando `par.getProcesso().getStatus()` (LAZY), um N+1
por render. Corrigido trocando por uma query de contagem direta no banco:
`ParecerRepository.
countByMembroIdAndResultadoIsNullAndDataEnvioIsNotNullAndProcessoStatus`
(derived query name, `count(...)`, sem carregar nenhuma entidade), com o
MESMO critério de `AvaliadorController.pendenteAtivoParaVoto` (resultado
nulo, `dataEnvio` preenchida, `processo.status == ENVIADO`). O método
estático `AvaliadorController.pendentesDoMembro` (que fazia a versão antiga,
usado só por `GlobalModelAdvice`) foi removido por ter ficado sem chamador;
`pendenteAtivoParaVoto` continua vivo, reaproveitado pelas consultas com
fetch join de `lista()`/`registrarVoto`. `pendentesAvaliador()` passou de
`int` para `long` (o tipo natural de `count()`) — sem efeito visível no
badge da navbar (`layout.html`), que já comparava com `> 0`. Coberto por
`ParecerRepositoryPendentesCountIntegrationTest` (`@SpringBootTest` + H2
real, compara a nova query com a lógica antiga reimplementada localmente
para o teste, em 6 cenários: zero pendentes, N pendentes, pendente de outro
avaliador, parecer já respondido, processo em status que não aceita votação,
e uma mistura de todos). Esta correção é só o pré-requisito técnico citado
pelo relatório para um possível *poll* futuro do contador (não implementado
nesta sessão — fica para quando/se o item for aprovado explicitamente).

## Aviso ao sair sem salvar (`beforeunload`) — 2026-08-04

Item 4 do `docs/RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md` (§4.4): o sistema
não tinha **nenhuma** ocorrência de `beforeunload` até esta sessão. Dois
cenários reais de perda silenciosa de dado:
1. Solicitante preenchendo a justificativa clínica em `solicitante/nova.html`
   e fechando a aba sem enviar (o rascunho manual — ver seção "Rascunho de
   solicitação" acima — exige um clique explícito em "Salvar rascunho" e, de
   qualquer forma, **nunca salva os arquivos anexados**, só os 4 campos de
   texto).
2. Operador editando o corpo de um e-mail pronto em `processos/detalhe.html`
   (`<textarea>` do accordion "Textos de e-mail prontos") e navegando para
   outra aba do wizard ou saindo sem enviar.

**Utilitário novo e reutilizável:** `static/js/aviso-sair-sem-salvar.js`,
expõe `window.iniciarAvisoSairSemSalvar({campos: [elemento, ...]})`. Cada
campo listado vira "sujo" no primeiro `input`/`change`; o `beforeunload`
nativo do navegador só dispara se **algum** campo estiver sujo (o texto da
mensagem é sempre o padrão do navegador — customizar `returnValue` é
ignorado por todos os navegadores modernos, documentado no próprio arquivo).
Novo fragment `layout :: avisoSairScript` (ao lado de `confirmarAcaoScript`/
`lockSubmitScript`) inclui o script.

**Nunca dispara no submit normal** — cada campo, se estiver dentro de um
`<form>`, desarma **só a si mesmo** no evento `submit` desse form
(`solicitante/nova.html`: os 5 campos do formulário de nova solicitação,
incluindo o `<input type="file">`).

**Nunca duplica o aviso quando já existe uma confirmação própria
(`data-confirm-msg`, ver `confirmar-acao.js`).** `confirmar-acao.js` passou a
disparar `document.dispatchEvent(new CustomEvent('saur:acao-confirmada'))`
logo antes de seguir com a navegação/submit já confirmados pelo usuário no
modal — sem isso, clicar "Cancelar" ou "Descartar rascunho" em
`solicitante/nova.html` mostraria DOIS avisos em sequência (o modal
customizado e, na sequência, o alerta nativo do navegador). O listener do
evento desarma o guard **globalmente** na página (não por campo) — como o
estado do guard é sempre recriado a cada carregamento de página, não há risco
de vazamento entre páginas diferentes; dentro da mesma página, um clique em
qualquer outro `data-confirm-msg` não relacionado (ex.: excluir um anexo em
`processos/detalhe.html`) também desarma o aviso do e-mail em edição — aceito
como limitação de baixo risco (o cenário é raro e o pior caso é só a ausência
do aviso extra, não perda de dado silenciosa nova).

**Achado e corrigido de graça:** `solicitante/nova.html` **não incluía**
`layout :: confirmarAcaoScript`, então os `data-confirm-msg` de "Cancelar" e
"Descartar rascunho" (que já existiam na tela) nunca tiveram efeito nenhum —
o navegador ignora um atributo `data-*` desconhecido sem o JS que o lê.
Corrigido incluindo o fragment junto com o `avisoSairScript` novo.

**Múltiplos campos independentes em `processos/detalhe.html`:** cada
`<textarea>` "corpo" do accordion de e-mails prontos é um campo independente
(`#accEmails textarea[id^="corpo"]`, pode haver vários e-mails prontos ao
mesmo tempo). `chamarAcao` (função genérica de `processo-detalhe.js`) ganhou
um parâmetro `onSucesso` opcional, chamado só quando `data.ok` é verdadeiro —
o handler de "Enviar agora por e-mail" o usa para limpar (`avisoEmailPronto
.limpar(corpoEl)`) só o campo que foi de fato enviado, sem afetar outros
textareas ainda editados/não enviados na mesma tela. O botão "Revisar com IA"
atribui `corpoEl.value` programaticamente (não dispara `input` sozinho) —
corrigido disparando manualmente `corpoEl.dispatchEvent(new Event('input',
{bubbles: true}))` depois, senão o texto revisado pela IA não contava como
"sujo" para o aviso.

**Sem teste automatizado direto — verificado manualmente e por leitura de
código.** `beforeunload` é um evento nativo do navegador que o Playwright não
dispara de forma confiável em teste automatizado (fechar a aba de verdade
sai do controle do driver); `@WebMvcTest`/`MockMvc` não roda JS nenhum. A
verificação foi: (1) leitura cuidadosa do fluxo de eventos (`input`→sujo,
`submit`/`saur:acao-confirmada`→desarma, `onSucesso`→limpa campo específico)
confirmando que não há caminho em que o submit normal dispara o aviso; (2)
suíte completa (`mvn test`, JDK 21) sem regressão — só a falha isolada e não
relacionada de `ComprovanteSntPendenteQueriesIntegrationTest` (comparação de
`LocalDateTime` com precisão de nanossegundos, flake de timing pré-existente,
reproduzida também isolada e depois passando; nada a ver com JS/beforeunload).
Verificação manual recomendada para quem revisar o PR: abrir
`/solicitante/nova`, digitar na justificativa, tentar fechar a aba (o
navegador deve avisar); abrir o detalhe de um processo com e-mails prontos,
editar um corpo de e-mail, tentar navegar para outra aba do wizard (deve
avisar) e depois confirmar que enviar o e-mail normalmente **não** dispara
nenhum aviso extra.

## Poll global pausado em background + toast unificado + CSP sem Google Fonts (2026-08-04)

3 itens do `docs/RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md` (PR #19, ainda
não mesclado no momento desta sessão), implementados na branch
`feat/poll-pausado-toast-unificado`:

1. **Os dois polls globais de notificação (`layout.html`, `setInterval` de
   20s que checam contagem de mensagens não lidas — um para ADMIN/OPERADOR,
   outro para SOLICITANTE) agora pausam com a aba em background.** Antes
   nunca paravam: uma aba aberta e esquecida mantinha a `HttpSession` viva
   indefinidamente via poll, na prática anulando o `timeout: 30m` de
   `application-prod.yml` (decisão de segurança deliberada da vistoria de
   2026-07-28). Reaproveitado exatamente o padrão já usado em
   `chat-solicitacao.js` (`visibilitychange` + uma flag `pollAtivo` que o
   `setInterval` consulta a cada tick, retomando o poll imediatamente quando
   a aba volta a ficar visível). `chat-solicitacao.js` e o intervalo de 20s
   em si **não foram tocados** — só o pause/resume dos dois polls do
   `layout.html`.
2. **`mostrarToast` tinha DUAS implementações divergentes** — uma inline no
   fragment `notificacaoSonora` de `layout.html` (usada pelas ~24 telas que
   não são o detalhe do processo) e outra em `processo-detalhe.js` (só a
   tela `/processos/{id}`, que sobrescrevia `window.mostrarToast` por
   carregar depois na mesma página). A de `processo-detalhe.js` tinha fade-out
   suave (300ms) e `aria-label="Fechar"` no botão de fechar; a de
   `layout.html` não tinha nenhum dos dois. **Unificadas em
   `static/js/toast.js`** (arquivo novo, com a versão mais completa),
   carregado por `layout.html` dentro do fragment `navbar` — precisa vir
   ANTES do `<script th:replace="~{layout :: notificacaoSonora}">`, que
   ainda define só `tocarNotificacao()`. `processo-detalhe.js` perdeu a
   definição duplicada; continua chamando `mostrarToast(...)` livremente
   (função global, mesma assinatura de antes: `(mensagem, tipo)`), garantido
   porque `toast.js` carrega mais cedo na página via o fragment `navbar`
   (todo template que usa `processo-detalhe.js` também usa `layout ::
   navbar` — verificado). Nenhum teste dependia do texto/estrutura interna
   de nenhuma das duas versões antigas.
3. **CSP de produção (`SecurityConfig.CSP_PROD`) parou de liberar
   `fonts.googleapis.com`/`fonts.gstatic.com`.** Essas origens sobraram da
   época em que a fonte Inter vinha do Google Fonts — desde a Fase E da
   sessão "UI da área do operador" (2026-08-04, mais acima neste arquivo) a
   fonte é auto-hospedada (`@font-face` em `app.css`, arquivos em
   `/fonts`), mas ninguém tinha voltado para apertar a CSP. Confirmado por
   grep no projeto inteiro que não sobra nenhuma outra referência a
   `googleapis`/`gstatic` fora de `GeminiService` (API do Gemini, endpoint
   diferente, não afetado por essa CSP) — remover as duas origens de
   `style-src`/`font-src` não quebra nada.

**Validação:** suíte completa **759 testes** — 0 falhas relevantes (as 2
falhas vistas nesta sessão foram a flakiness de timing já documentada em
`ComprovanteSntPendenteQueriesIntegrationTest`/
`LembreteAvaliadorTimestampIntegrationTest`, nem sempre as duas aparecem
juntas na mesma rodada). `.\e2e.ps1 -Headless` chegou até o passo 5 do fluxo
e falhou na mesma linha já documentada (`FluxoCompletoProcessoIT:228`,
SMTP local não configurado — `SGPUR_MAIL_USER`/`SGPUR_MAIL_FROM` ausentes
nesta máquina), confirmando que a falha é pré-existente e não relacionada.
Nenhuma regra de negócio, entidade ou endpoint mudou nesta sessão — só
JS/CSS/CSP.

## Visualização do PDF na tela de voto do avaliador em celular (2026-08-05)

Investigação pontual pedida pelo usuário sobre como `avaliador/votar.html`
(`GET /avaliador/{processoId}`) se comporta em celular ao exibir o documento
clínico anonimizado (`TipoAnexo.SOLICITACAO_AVALIADOR`).

**O que já existia (de fases anteriores da UI do Portal do Avaliador) e
continua correto, não foi tocado:**
- Cada PDF é exibido num `<iframe>` (`.pdf-avaliador-frame`) com um link
  "Abrir em nova aba" (`btn-outline-secondary btn-sm`) e um botão de tela
  cheia (`btn-tela-cheia`, `avaliador-pdf-fullscreen.js`) já sempre visíveis
  ao lado do nome do arquivo, acima do iframe — não são um fallback
  escondido, sempre renderizados.
- `.pdf-avaliador-frame` já tinha uma regra `@media (max-width: 991.98px)`
  reduzindo a altura de `calc(100vh - 220px)` (só faz sentido no layout
  split-pane `col-lg-7/col-lg-5`) para `60vh`, e um atalho "Ir para o voto"
  (`d-lg-none`) para não deixar o formulário de voto soterrado abaixo do PDF
  em telas empilhadas.

**O que estava faltando (achado real, verificado pela leitura do HTML/CSS,
não por suposição genérica):** o link "Abrir em nova aba" e o botão de tela
cheia ficam lado a lado no cabeçalho de cada PDF, com o mesmo tamanho
discreto (`btn-sm`) usado no desktop — em celular, onde o `<iframe>` de PDF é
historicamente pouco confiável (Safari iOS e Chrome Android variam muito na
renderização inline entre versões, às vezes mostrando em branco ou exigindo
um visualizador externo), não havia nenhum destaque visual maior para a
alternativa "abrir de verdade" nessa faixa de tela — ela competia por espaço
com o botão de tela cheia e o nome do arquivo truncado, do mesmo jeito que no
desktop, onde o iframe costuma funcionar bem.

**Correção aplicada (só apresentação — nenhuma URL, permissão ou lógica de
anexo exibido mudou):**
- `avaliador/votar.html`: cada PDF ganhou um botão `btn-primary w-100
  d-lg-none` ("Abrir PDF em nova aba (recomendado no celular)"), com `id`
  estável `btnAbrirPdfMobile{index}`, posicionado logo ACIMA do `<iframe>`
  correspondente, visível só abaixo do breakpoint `lg` do Bootstrap (992px) —
  em desktop nada muda, o iframe splitado com o botão pequeno de sempre
  continua a experiência principal, porque lá ele funciona bem.
- `app.css`: nova regra `@media (max-width: 575.98px)` reduz a altura do
  iframe de `60vh` para `45vh` só em telas de celular (não tablets/iPad, que
  já caem em `991.98px`), dando mais prioridade visual ao botão novo acima
  dele.
- Não mudou `AvaliadorController` (mesmo endpoint `GET /avaliador/{id}/pdf/
  {pdfId}`, mesma checagem de posse/imparcialidade), nem o critério de quais
  anexos aparecem (`TipoAnexo.SOLICITACAO_AVALIADOR`, iniciais do paciente).

**Validação:** suíte completa **773 testes, 0 falhas** (JDK 21) — a suíte
completa via `mvn test` isolado passou de primeira; as duas rodadas
subsequentes via `.\e2e.ps1 -Headless` reproduziram a MESMA flakiness de
timing já documentada acima (`ComprovanteSntPendenteQueriesIntegrationTest`/
`LembreteAvaliadorTimestampIntegrationTest`, precisão de nanossegundos do
H2), confirmando que não é regressão desta mudança. Rodando
`mvn verify -Pe2e -Dmaven.test.failure.ignore=true` (só para deixar o estágio
Failsafe/Playwright rodar apesar do flake do Surefire) o
`FluxoCompletoProcessoIT` passou pelo Passo 3 (avaliador votando, que
exercita exatamente a tela alterada) sem problema e falhou na mesma linha 228
já documentada (confirmação de resposta por e-mail, SMTP local não
configurado) — mesma falha pré-existente, não relacionada a esta mudança.
`AvaliadorPage.materialInline()` localiza o iframe por `title` (não por
`id`/classe do botão novo), então o seletor do E2E não foi afetado pelo botão
adicionado.

## Confirmação antes de "Enviar Resposta ao Solicitante" (2026-08-04)

O botão único da etapa 6 (`POST /processos/{id}/finalizar`,
`processos/detalhe.html`) disparava o e-mail oficial de Deferido/Indeferido
com o anexo obrigatório (comprovante SNT ou ofício) com um único clique, sem
nenhuma barreira contra clique acidental — mesma classe de risco já coberta
em excluir/reabrir/cancelar. Ganhou `data-confirm-msg`, reaproveitando o
modal genérico já existente (`static/js/confirmar-acao.js` +
`layout.html :: confirmModal`), sem nenhuma rota nova nem mudança de regra
de negócio.

Ajuste necessário no E2E: `ProcessoDetalhePage.
passo5_confirmarRespostaAoSolicitante()` clicava só no botão do form; com o
modal, precisa clicar também em `#btnConfirmarAcaoFinal` antes do
`waitForLoadState()`. Suíte completa (773 testes) validada — a única
falha vista foi a flakiness de precisão de timestamp já documentada acima
(`ComprovanteSntPendenteQueriesIntegrationTest`/
`LembreteAvaliadorTimestampIntegrationTest`, não relacionada). `.\e2e.ps1
-Headless` chega até o passo 5 e falha na mesma linha pré-existente
(`FluxoCompletoProcessoIT:228`, SMTP local indisponível nesta máquina —
"EmailSender: remetente (from) nao configurado"), confirmando via log que a
falha continua sendo a ausência de SMTP local, e não o modal novo (o clique
em `#btnConfirmarAcaoFinal` completa sem timeout).

## Fix: 401 cru no Portal do Avaliador com sessão órfã (2026-08-04)

**Bug real reportado pelo usuário:** acessar `/avaliador` pelo link do
e-mail de convite às vezes devolvia um 401 cru (página de erro técnica do
navegador) em vez de cair na tela de login normal do SAUR.

**Causa raiz:** o Spring Security não relê o `UserDetails` a cada
requisição — ele fica fixo na `HttpSession` desde o login. Se o ADMIN troca
o `username` de um avaliador em `/usuarios` (ou exclui a conta) enquanto
ele tem sessão ativa, a sessão continua "autenticada" com o username antigo,
mas `AvaliadorController.resolverMembro` (via
`UsuarioRepository.findByUsername`) não encontra mais ninguém. Até esta
correção, o método lançava `ResponseStatusException(HttpStatus.
UNAUTHORIZED)` direto — o `GlobalExceptionHandler` deixa esse tipo de
exceção passar para o Spring tratar sozinho (preserva o status HTTP
original), então o resultado era um 401 técnico sem nenhuma chance de o
usuário simplesmente logar de novo. Diferente do fluxo normal de usuário
deslogado (que sempre cai em 302 para `/login` via
`LoginUrlAuthenticationEntryPoint`, não alterado).

**Correção:** `resolverMembro` agora lança `SessaoInvalidaException`
(`web/SessaoInvalidaException.java`, tipo próprio) em vez do
`ResponseStatusException` cru — cobre tanto `GET /avaliador` (ponto de
entrada do link do e-mail) quanto qualquer outra ação do portal que chame
`resolverMembro` (votar, baixar PDF etc.), já que é o único ponto de
resolução do membro logado, sem duplicar a lógica. As demais
`ResponseStatusException(FORBIDDEN)` do controller (usuário sem membro
vinculado, não é avaliador do processo, parecer já emitido, processo fora
de status ativo) **não mudaram** — são erros de autorização de um usuário
genuinamente autenticado e válido, cenário diferente do desta correção.

`GlobalExceptionHandler` ganhou `handleSessaoInvalida` (`@ExceptionHandler
(SessaoInvalidaException.class)`): invalida a sessão de verdade via
`SecurityContextLogoutHandler` (limpa `HttpSession` **e**
`SecurityContext` — mais robusto que só `session.invalidate()` manual) e
redireciona para `/login?erro=sessao-invalida`. `login.html` ganhou um
alerta amarelo para esse parâmetro ("Sua sessão não é mais válida... Faça
login novamente"), no mesmo padrão dos alertas já existentes para
`param.error`/`param.logout`/`${msg}`/`${erro}`.

**Por que não foi tratado como caso geral do `UsuarioRepository.
findByUsername` em todos os controllers:** o mesmo padrão
(`findByUsername(...).orElseThrow(() -> new ResponseStatusException
(UNAUTHORIZED))`) existe também em `SolicitanteController`,
`SolicitacaoOnlineTriagemController` e `ProcessoDetalheController` — não
foram tocados nesta correção (escopo pedido foi só o Portal do Avaliador,
onde o bug foi reportado e reproduzido). Se o mesmo sintoma aparecer nesses
outros portais, o mesmo padrão (`SessaoInvalidaException` +
`GlobalExceptionHandler.handleSessaoInvalida`, que já é genérico e reusável
por estar no `@ControllerAdvice` global) resolve sem duplicar código.

**Teste de regressão** (`AvaliadorSessaoOrfaIntegrationTest`, `@SpringBootTest`
+ H2 real, **sessão HTTP de verdade via login por formulário — não
`@WithMockUser`**, porque o bug é sobre o estado da `HttpSession` entre
duas requisições, algo que `@WithMockUser` recria do zero a cada método e
nunca reproduz): loga de verdade via `POST /login`, captura a
`MockHttpSession` resultante, renomeia o `username` do `Usuario` no banco
"por baixo" da sessão ativa (mesmo efeito prático de uma conta excluída), e
reusa exatamente essa sessão numa nova requisição a `/avaliador` —
confirma redirect 302 para `/login?erro=sessao-invalida` (nunca 401/500
cru), que a própria sessão fica `isInvalid()==true` depois (prova que foi
de fato invalidada, não só ignorada), e que uma requisição seguinte sem
essa sessão continua exigindo login (nunca reaproveita nada). Também
ajustado `SecurityIntegrationTest.avaliadorAcessaPortalProprio`, que usava
`@WithMockUser` com um usuário fictício sem registro no banco (o mesmo
cenário de sessão "órfã" na prática) e esperava 401 — passou a esperar o
redirect gracioso.

## Lista de documentos selecionados (Portal do Solicitante) — corte visual e pouca proeminência (2026-08-04)

Usuário relatou dois problemas na tela `solicitante/nova.html` ao selecionar
documentos clínicos: a lista de arquivos escolhidos (`<ul
id="documentosSelecionados">`, preenchida por `static/js/solicitante-nova.js`)
era pouco visível, e o nome do arquivo aparecia **cortado**.

**Causa raiz do corte:** o `<span>` do nome do arquivo usava a classe
utilitária `text-truncate` do Bootstrap (`white-space: nowrap` +
`overflow: hidden` + `text-overflow: ellipsis`) — para nomes de arquivo
longos (comum em anexo clínico, ex. `Exame_Creatinina_Ultrassom_Renal_
Paciente_2026.pdf`), o nome era cortado com reticências, sem forma de
confirmar visualmente qual arquivo foi de fato selecionado. Não havia
`overflow`/altura fixa em nenhum container pai (`.card`, `.card-body`,
`.container-narrow`) — o corte era só desse `text-truncate` no nome.
Corrigido trocando para a utilitária `text-break` (quebra de linha em vez de
reticências), o mesmo tratamento já usado no projeto para nomes de anexo
longos em `#pane-envio .list-group-item` (`app.css`).

**Proeminência visual:** `solicitante-nova.js` (`atualizarResumo`) passou a
manter um aviso `#documentosResumo` (`alert-success`, ícone
`bi-check-circle-fill`) acima da lista, com "N documento(s) selecionado(s)" —
escondido (`d-none`) quando `input.files.length === 0`, atualizado a cada
evento `change` do `<input id="documentos">`. Cada item da lista ganhou um
ícone `bi-file-earmark-text-fill` antes do nome, para reforçar visualmente
que a linha é um arquivo anexado. Nenhum `id`/`name` de campo mudou (o
`PortalSolicitantePage.java` do E2E localiza `#documentos` pelo mesmo
seletor de sempre, sem referência aos elementos internos da lista).

## Confirmação antes de "Registrar decisão" (2026-08-05)

Mudança aprovada explicitamente pelo dono do produto nesta sessão. O
formulário da aba Decisão (`POST /processos/{id}/decidir`,
`processos/detalhe.html`) registrava Deferido/Indeferido/Cancelado com um
único clique, sem nenhuma barreira contra clique acidental — mesma classe de
risco já coberta em excluir/reabrir/cancelar/"Enviar Resposta ao
Solicitante" (ver seção "Confirmação antes de Enviar Resposta ao
Solicitante" acima, usada como modelo). Ganhou `data-confirm-msg`,
reaproveitando o modal genérico já existente (`static/js/confirmar-acao.js`
+ `layout.html :: confirmModal`, `#btnConfirmarAcaoFinal`) — **nenhum modal
novo foi criado**.

Como o formulário tem um único botão "Registrar decisão" que vale para as 3
opções do `<select id="decisaoSelect">` (Deferido/Indeferido/Cancelado), a
mensagem de confirmação é **dinâmica**: `processo-detalhe.js` atualiza o
atributo `data-confirm-msg` do `<form id="formDecisao">` a cada troca do
select (`change`, mesmo listener que já existia para mostrar/esconder o
campo de motivo), citando o rótulo da decisão selecionada — ex. *"Confirma o
registro da decisão "Indeferido" para este processo? Esta ação é
irreversível."* Funciona porque `confirmar-acao.js` só lê
`el.dataset.confirmMsg` no momento do `submit`, não faz cache do valor no
carregamento da página.

O E2E (`ProcessoDetalhePage.passo4_decidir`) passou a clicar em
`#btnConfirmarAcaoFinal` depois do clique no botão "Registrar decisão",
mesmo padrão já usado no ajuste do botão "Enviar Resposta ao Solicitante".
Esse caminho manual só é exercitado em `Cancelado` ou redecisão após
reabertura pelo ADMIN — no caminho feliz de maioria simples o processo
decide sozinho (`ProcessoService.tentarDecisaoAutomatica`), sem passar por
este formulário; por isso `FluxoCompletoProcessoIT` (que só cobre o caminho
automático) não precisou de nenhum ajuste para esta mudança específica.

**Validação:** suíte completa, 0 falhas (JDK 21).

## Recebimento fundido em Envio (2026-08-05)

Segunda mudança aprovada explicitamente pelo dono do produto na mesma
sessão da confirmação de "Registrar decisão" acima (branch
`feat/confirmacao-decisao-fundir-recebimento`, commit separado).

**Contexto:** desde 2026-07-27 (ver seção "Passo 1 (Recebimento)" acima), a
etapa Recebimento é **sempre automática e concluída** — todo `Processo`
nasce de uma `SolicitacaoOnline` convertida pelo Portal do Solicitante, e
não sobrava nenhuma ação real do operador nessa aba, só uma etiqueta
sempre-verde antes de Envio. **Decisão de produto:** eliminar o Recebimento
como passo/etapa próprio, fundindo-o em Envio. O fluxo passou de **6 para 5**
passos no checklist: **1 Envio · 2 Respostas · 3 Decisão · 4
Ofício/Comprovante · 5 Resposta ao solicitante** (ver bullet "Fluxo em 5
passos" acima, atualizado no lugar do antigo "Fluxo em 6 passos").

**Levantamento feito antes de codar** (grep por `Recebimento`/`RECEBIMENTO`
em todo `src/`, mais `docs/PLANO-FLUXO.md`) para não deixar aba/id/string
órfã — todos os pontos abaixo foram tocados na mesma sessão:

- **`EtapaFluxo.Chave`** (`service/dto/EtapaFluxo.java`): valor `RECEBIMENTO`
  removido do enum (não ficou como "legado não usado" — não havia motivo
  para manter um valor de identidade que nunca mais é produzido).
- **`FluxoProcessoService.montarEtapas`**: o bloco que sempre adicionava a
  etapa "Recebimento da solicitação" (`CONCLUIDA` incondicional) foi
  removido; Envio passou a ser a **primeira** etapa da lista, com
  `anterioresConcluidas` já `true` desde o início (mesmo efeito prático de
  antes — Envio sempre foi liberado desde que Recebimento existia). Os
  comentários numéricos das etapas seguintes foram renumerados (2→1, 3→2,
  3b→2b, 4→3, 5→4, 5b→4b, 6→5).
- **`FluxoProcessoService.montarPassosWizard`**: o passo "1. Recebimento"
  (`pane-recebimento`) foi removido; os 4 passos restantes (Envio,
  Respostas, Decisão, Finalização) foram renumerados 1-4. Tooltips que
  citavam "(passo N)" foram ajustados para os números novos.
- **`FluxoProcessoService.GatingAbas`**: o campo `liberadoRecebimento`
  (sempre `true`, sem influenciar mais nada) foi removido do record — passou
  de 5 para 4 campos: `(liberadoEnvio, liberadoRespostas, liberadoDecisao,
  liberadoFinalizacao)`. `calcularGating` simplificado: `liberadoEnvio` é
  agora diretamente `true` (era `recebimentoFeito`, que também já era
  sempre `true`).
- **`ProcessoDetalheController.detalhe`**: parou de expor o model attribute
  `liberadoRecebimento` (não lido por nenhum template depois da remoção da
  aba). `liberadoEnvio` continua exposto, com a mesma semântica de sempre
  (`true`).
- **`processos/detalhe.html`**: a `<!-- ABA 1: Recebimento -->` inteira foi
  removida (era o card "Recebimento e ajuste do texto", sem nenhum
  formulário — só o banner "Recebimento concluído!" e o botão "Avançar para
  Envio", ambos sem função depois da fusão). O link **"Ver solicitação
  original"** (`solicitacaoOnlineOrigemId`, alimentado por
  `FluxoProcessoService.veioDoPortal`) **não desapareceu** — migrou para um
  aviso curto no topo da aba Envio (agora a aba 1), com o mesmo texto/rota
  de sempre. O switch de ícones do wizard (`bi-inbox` de Recebimento,
  `bi-send` de Envio etc.) perdeu o case 1 antigo e foi renumerado. O badge
  de contagem de favoráveis no passo "Respostas" trocou a condição de
  `passo.numero == 3` para `passo.numero == 2`. As demais 4 abas (`ABA 2..5`
  → `ABA 1..4`) só tiveram o comentário HTML renumerado — nenhum `id`,
  `paneId` ou `aria-*` de Respostas/Decisão/Finalização mudou (eles são
  gerados dinamicamente a partir de `passosWizard`, então a remoção do item
  Recebimento da lista já bastou para não sobrar nenhum `id`/`aria-*` órfão
  — confirmado que `AcessibilidadeEstruturaTest` continua verde sem
  nenhum ajuste nele). Os dois banners do topo da tela (processo
  encerrado/decisão tomada) tiveram a menção a "etapas 1-4 (recebimento,
  envio, pareceres)" reescrita para não citar mais o recebimento como etapa
  separada.
- **`docs/PLANO-FLUXO.md`**: tabela "As 6 etapas do checklist" virou "As 5
  etapas do checklist"; a linha do Recebimento foi removida e as demais
  renumeradas, com uma nota explicando a fusão.
- **Testes**: `FluxoProcessoServiceTest` (a maior mudança — removida a
  asserção de `Chave.RECEBIMENTO`/`liberadoRecebimento()` em ~8 métodos,
  contagens de `etapas.hasSize(n)` reduzidas em 1 onde aplicável, números de
  `PassoWizard.numero()` ajustados nos 2 testes do wizard),
  `ProcessoDetalheControllerTest` (`GatingAbas` com 4 args em todas as 15
  ocorrências, fixture padrão do `PassoWizard` trocada de `"Recebimento"`
  para `"Envio"`), `ProcessoExportacaoIntegrationTest` (a asserção do
  relatório de movimentação, que verificava a string "Recebimento da
  solicitação" no ZIP exportado, passou a verificar "Envio aos 3 médicos").
- **E2E** (`ProcessoDetalhePage`, `FluxoCompletoProcessoIT`): os métodos
  `passo2_anexarDocumentoClinico`/`passo2_registrarEnvio` viraram
  `passo1_*`; `passo4_decidir` (que já tinha ganhado o clique em
  `#btnConfirmarAcaoFinal` na mudança de "Registrar decisão" acima) virou
  `passo3_decidir`;
  `passo5_anexarOficioIndeferimento`/`passo5_anexarComprovanteSnt`/
  `passo5_confirmarRespostaAoSolicitante` viraram `passo4_*`. As chamadas a
  `passoConcluido(N)` no teste de fluxo completo foram renumeradas (a
  antiga assertiva `passoConcluido(1)` logo após converter a solicitação,
  que checava o Recebimento sempre-verde, foi removida — não há mais nada
  ali para checar antes de anexar o primeiro documento clínico).

**Validação:** suíte completa rodada de forma independente após esta
mudança (com a confirmação de "Registrar decisão" já commitada antes) —
**783 testes, 0 falhas** (JDK 21, mesma contagem de antes: nenhum teste foi
adicionado nem removido de fato, só renomeado/reescrito). E2E rodado via
`mvn verify -Pe2e -Dsaur.e2e.headed=false` (ambiente sem X server, sempre
headless aqui — diferente do `.\e2e.ps1` do Windows citado no restante
deste arquivo, que abre janela por padrão): o fluxo percorreu com sucesso a
conversão da solicitação, o Envio (aba fundida, `passo1_*`), os votos reais
dos 2 avaliadores, a decisão automática por maioria simples
(`passoConcluido(2)`/`passoConcluido(3)` confirmados) e o anexo do ofício de
indeferimento — validando a navegação completa pelas abas renumeradas.
Falhou só na linha da confirmação final da resposta ao solicitante, com o
mesmo log **pré-existente e documentado** ("EmailSender: remetente (from)
nao configurado" — `SGPUR_MAIL_USER`/`SGPUR_MAIL_FROM` ausentes nesta
máquina local), não relacionado a nenhuma das duas mudanças desta sessão.


## REGRA: chat com o solicitante fica na barra lateral esquerda de /processos/{id} (2026-08-06)

Um lote de mudanças pulled de outra sessão (commits "lote 4"/"lote 5",
trazendo `EstadoEtapa`/unificação de vocabulário wizard-timeline, entre
outras) tinha movido o card "Conversa com o solicitante" da tela de detalhe
do processo (`processos/detalhe.html`) para fora da barra lateral esquerda
(`col-lg-3`, onde vivem Progresso/Atalhos/Textos de e-mail prontos) e para
uma linha própria, larga (`col-lg-6`), no fim da página — sem revisão visual
humana antes do merge. O usuário reportou como "conversa com o solicitante
está torto, em todo o sistema" a partir de uma URL de produção.

**Investigação:** o HTML/CSS dos balões em si estava correto (flex
`justify-content-start`/`justify-content-end`, sem regressão de alinhamento
interno) nas 3 telas de chat do sistema — reproduzido localmente (H2/dev,
fluxo completo via Playwright: criar solicitante → enviar solicitação →
mensagens dos dois lados → triagem → converter em processo) e conferido por
screenshot. O problema real era de **posição do card na página**, não de
alinhamento dos balões: o chat saiu do lugar de sempre (lateral esquerda) e
foi parar numa faixa larga isolada mais abaixo.

**Correção, a pedido explícito do usuário ("o chat na tela do operador deve
ficar a esquerda, como estava antes. REGRA"):** o card do chat voltou para
dentro do `col-lg-3` da barra lateral esquerda, como o último card da
coluna (depois de Progresso/Atalhos/Textos de e-mail prontos), com
`class="card mt-3 animate-fade-in-d1"` — mesmo padrão dos cards vizinhos,
sem wrapper `row`/`col-lg-6` próprio. O `id`/estrutura interna do chat
(`#chatBodyProcesso`, `#chatBox`, `#chatForm` etc.) não mudou — só o
container em volta.

**Isto é regra fixa do produto, não preferência pontual desta sessão: o
chat de `/processos/{id}` deve permanecer na barra lateral esquerda. Não
mover para outro lugar da página sem pedido explícito do usuário.**

Não mexeu nas outras 2 telas de chat (`solicitante/detalhe.html`,
`processos/solicitacoes-online-detalhe.html`), que não foram tocadas pelo
lote pulled e já estavam corretas.

## Sessão de 2026-08-06/07 (retomada em casa): 4 itens da lista de pendências implementados

O usuário pediu, ainda no trabalho, para salvar tudo (relatórios/ideias em
`docs/`, resumo em memória) para poder dizer só "prossiga de onde parou" ao
chegar em casa. Numa sessão separada, à noite (commits de autoria
`rafaelioppi`), ele retomou e implementou os 4 itens pendentes, um PR por
item, todos mesclados em `main` e já em produção (deploy automático
confirmado verde). Registrando aqui porque aquela sessão não chegou a
atualizar este arquivo — só os `docs/*.md` de cada item.

**1. Bug corrigido — pausa "Solicita informação" não bloqueia mais os outros
avaliadores** (commit `4171987`). Ver a seção "Solicita informação (PAUSA)"
acima: o texto de lá descreve a regra pretendida (só a Decisão fica
bloqueada); o bug real era `AvaliadorController` exigir
`status == ENVIADO` para **qualquer** voto, então quando um avaliador pedia
informação e o `Processo` inteiro mudava para `SOLICITA_INFORMACAO`, os
outros dois médicos — que não pediram nada — ficavam impedidos de votar
(403, e o processo sumia da lista/badge deles) até o operador concluir todo
o ciclo de retomada, o que podia levar dias. Corrigido com
`StatusProcesso.aceitaVotoAvaliador()` (novo método, `true` para `ENVIADO`
e `SOLICITA_INFORMACAO`), usado em `resolverParecerPendente`/
`pendenteAtivoParaVoto`; a query de contagem do badge
(`ParecerRepository.countByMembroId...ProcessoStatus`) virou
`...ProcessoStatusIn` (aceita os dois status). `ProcessoValidator
.validarPausaDecisao`/`tentarDecisaoAutomatica` (a trava da **decisão**)
não foram tocados — continuam corretos, só bloqueavam a etapa certa. O
avaliador que causou a pausa continua impedido de votar de novo (checagem
`parecer.getResultado() != null`, inalterada). Teste de regressão dedicado:
`AvaliadorVotoDuranteSolicitaInformacaoIntegrationTest`. Diagnóstico
completo em `docs/RELATORIO-BUG-PAUSA-BLOQUEIA-OUTROS-AVALIADORES-2026-08.md`
(status atualizado para CORRIGIDO).

**2. Padronização de cores implementada — "Solicita informação" = amarelo,
"Aguardando" = azul** (commit `d35dfaa`). Antes, as duas coisas usavam cor
ao contrário do que fazia sentido (e inconsistente entre si — o badge direto
do status dizia azul, o `tom()` semântico do mesmo status já dizia amarelo).
Seguiu a Opção A do documento de ideia (criar um **5º tom semântico**, não
uma exceção fora do vocabulário): `"aguardando"`, distinto de `"attention"`,
com token `--saur-state-aguardando` e caso próprio em `layout ::
tomBadge`. **O vocabulário de tom deixou de ter só 4 valores** — onde este
arquivo cita "ok/danger/attention/neutral" em outras seções (Design system,
Regras de negócio), ler como **histórico**: hoje é
`ok/danger/attention/aguardando/neutral`, 5 valores.
`StatusProcesso.getBootstrapBadge()` (`SOLICITA_INFORMACAO`: `bg-info` →
`bg-warning`), `StatusSolicitacaoOnline.getBootstrapBadge()` (`ENVIADA`:
`bg-warning` → `bg-primary`), `PainelLinha.CelulaMedico` ("Aguardando":
warning→primary; "Solicita info": info→warning),
`SolicitanteController.montarSituacaoPedido` (aguardando triagem/análise →
primary) todos ajustados. Nenhuma regra de negócio mudou, só cor/classe
CSS. Doc atualizado para IMPLEMENTADA em
`docs/IDEIA-PADRONIZACAO-CORES-SOLICITA-INFO-AGUARDANDO-2026-08.md`.

**3. Feature nova — chat interno Avaliador (Membro) ↔ Operador, por
processo** (commit `6d9b8a5`, F1-F5). Antes desta sessão o médico avaliador
era o único participante "mudo" do sistema — sem nenhum canal, dentro do
SAUR, para trocar mensagem com a equipe operacional (dúvida sobre um PDF que
não abriu, aviso de viagem etc.), tudo acontecia por fora (telefone/
WhatsApp), sem trilha. **Nota: uma vistoria anterior no mesmo dia (2026-08-06)
tinha registrado essa mesma feature como "DESCARTADA — usuário decidiu
explicitamente não implementar"; nesta sessão seguinte o usuário retomou e
aprovou a implementação (mensagem do commit registra "aprovacao repassada
pela tarefa") — a decisão de não implementar foi revertida, não é uma
inconsistência.** Arquitetura: entidade nova e separada
`domain/MensagemAvaliador.java` (tabela `mensagem_avaliador`, enum próprio
`RemetenteMensagemAvaliador`, **não** reusa `MensagemSolicitacao
.RemetenteMensagem` — a CHECK constraint dela está congelada em produção,
ver seção de CHECK constraints acima) — conversa **por processo**, 1:1
(avaliador ↔ equipe operacional), iniciável pelos dois lados, até o
processo ser decidido (depois, só leitura). `service/MensagemAvaliadorService`
espelha `MensagemSolicitacaoService` de propósito (mesmos métodos:
enviar/paraChat/marcarComoLidas/contarNaoLidas/apagar). **Proteção de
imparcialidade:** `service/VerificadorNomePaciente.java` bloqueia
(determinístico, por palavra inteira — nunca heurística) qualquer mensagem
que contenha o nome do paciente ou da equipe solicitante, reaproveitando a
normalização já validada em `ConflitoEquipeMatcher`/`Iniciais`; o operador
escrevendo outra coisa sensível (ex. revelar o voto de outro avaliador) foi
**aceito conscientemente como risco não mitigável por código** — mitigado só
por aviso fixo na composição + auditoria (nunca com o texto da mensagem).
Endpoints: lado avaliador em `AvaliadorController`
(`GET/POST /avaliador/{id}/mensagens`, `mensagem/ajax`,
`mensagem/{id}/apagar/ajax`, `GET /avaliador/nao-lidas-count`); lado
operador em `ProcessoDetalheController`
(`GET/POST /processos/{id}/avaliador/{membroId}/mensagens` e pares, com
verificação de nome **antes** de gravar); caixa de entrada nova (F5) em
`GET /processos/mensagens-avaliadores`
(`processos/mensagens-avaliadores-lista.html`, todas as threads, mais
recente primeiro) + item de navbar com badge. Reusa `chat-solicitacao.js`
**sem nenhuma modificação**. Auditoria: `MENSAGEM_AVALIADOR_ENVIADA` /
`MENSAGEM_OPERADOR_AVALIADOR_ENVIADA`, nunca com o texto da mensagem nem o
nome completo do paciente. **Fora de escopo nesta leva** (F6/F7,
deliberado): e-mail de notificação ao avaliador de mensagem nova; canal
geral sem processo associado. Relatório de arquitetura original em
`docs/RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md` (o header desse doc
ainda não foi atualizado com o status IMPLEMENTADO — só este parágrafo do
CLAUDE.md registra isso por ora).

**4. Relatório Final (PDF) — plano completo R0-R6 implementado** (5
commits, `06783ba`→`d60f4d6`, mesclados em `bb167c3`), continuação da
correção de conteúdo/paleta já registrada acima (PR #45) e do
diagnóstico nível 2 (`docs/RELATORIO-REFORMULACAO-RELATORIO-FINAL-PDF-V2-2026-08.md`,
que pediu pesquisa web e reconfirmou a falta de acentuação). Implementado
nesta sessão: **R0** (cabeçalho de tabela repetido entre páginas, "Nº" em
vez de "N", nome do sistema unificado nos 4 documentos institucionais,
`/Lang` + `DisplayDocTitle` no PDF); **R1b+R2** (conteúdo simétrico entre os
caminhos Deferido/Indeferido + acentuação completa de todos os literais
Java do relatório — a queixa original do usuário); **R3b+R4** (paleta
institucional compartilhada entre os documentos + tipografia/cor comedida,
reduzindo a proporção de página que é faixa azul decorativa em vez de
conteúdo); **R5** (correção do tamanho de página — o PDF **não era A4 de
verdade**, 6,5% mais alto que A4 real, o que encolhia o corpo de 9pt para
~8,4pt efetivo ao imprimir — mais divisórias por anexo e marcadores de
navegação); **R6** (capa eliminada — o sumário virou a folha de rosto,
mesmo padrão do Ofício de Indeferimento — e rótulo "RELATÓRIO PARCIAL"
quando o processo ainda não foi decidido, em vez de poder emitir um
documento chamado "RELATÓRIO FINAL" a qualquer momento). **O R6 foi
REVERTIDO em 2026-08-07 a pedido explícito do usuário — a capa voltou, com
desenho novo; ver a seção "Capa do Relatório Final reintroduzida" logo
abaixo.** Suíte completa
validada a cada bloco (a última rodada geral: 826 testes, 0 falhas, JDK
21, contando também o item 3 acima). **Pendência:** nem o CLAUDE.md nem o
header do doc V2 foram atualizados por aquela sessão com o status de
"implementado" — só o commit `5ed8bf4` ("docs: registra implementacao do
plano do relatorio V2") existe, sem corpo detalhado. Quem for mexer no
Relatório Final de novo: o diagnóstico V2 (seções 1-11) descreve o estado
**anterior** a esta implementação, não o atual — conferir o código real
antes de assumir que um achado do V2 ainda se aplica.

**Deploy automático confirmado funcionando ponta a ponta** para os 4 merges
desta sessão (GitHub Actions "Deploy" verde após cada merge em `main`,
último em 2026-08-07 03:10 UTC) — produção validada por `curl -Ik
https://urgenciarenal.duckdns.org/login` (200) na sessão seguinte.

## Capa do Relatório Final reintroduzida (2026-08-07) — reverte o R6

**Pedido explícito do dono do produto**, textual: *"o relatório final em PDF
dos processos precisa ter uma capa. Faça uma capa bonita."* Isso **reverte
deliberadamente o item R6** do plano implementado horas antes (ver a seção
anterior), que tinha eliminado a capa e feito o sumário virar a folha de
rosto. Não é um retrocesso acidente/merge — é decisão de produto posterior.

**Não é o `revert` do commit do R6.** Os três motivos concretos que
justificaram aquela remoção continuam valendo como critérios de projeto, e
o desenho novo trata cada um. Estão registrados em javadoc extenso em
`PdfRelatorioBuilder.gerarCapa` e travados por teste
(`RelatorioServiceTest`, bloco no fim da classe):

| Defeito da capa antiga | Como a capa nova evita |
|---|---|
| Repetia a tabela de dados INTEIRA + a tabela de avaliadores que o sumário reimprimia logo depois (achado 6.6) | Mostra **4 dados** (número, paciente, situação, emissão) em tipografia grande. Teste `capaNaoRepeteAsTabelasDoSumario` |
| Sobrava ~1/3 de página em branco | Respiro distribuído entre blocos + painel de identificação com peso visual (fundo `--rs-gray-50` + filete azul à esquerda) |
| DOIS brasões na mesma dupla de páginas (o da capa e o do carimbo do `PdfCabecalhoStamper`, que carimbava também a capa) | A capa é a **única página sem carimbo**. Teste `capaNaoRecebeOCarimboInstitucionalNemNumeroDePagina` |

**Mecanismo do "sem carimbo":** `PdfCabecalhoStamper.estampar` ganhou uma
sobrecarga `estampar(pdf, linha1, linha2, primeiraPagina)` que pula
cabeçalho, numeração **e a expansão de `ALTURA_CABECALHO` (55pt) do topo**
nas páginas anteriores a `primeiraPagina`. A versão de 3 argumentos delega
com `1` — `RelatorioAnualService`/`RelatorioAvaliadorService` não mudaram.
Consequência obrigatória: **a capa nasce em `PageSize.A4` cheio**, e não em
`PdfRelatorioBuilder.TAMANHO_PAGINA_SISTEMA` (que já desconta os 55pt que o
stamper devolve) — senão o documento teria páginas de dois tamanhos e a
correção do R5 (A4 de verdade) regrediria. Travado por
`todasAsPaginasGeradasPeloSistemaContinuamEmA4InclusiveACapa`.
`RelatorioService.gerar` **lê a contagem de páginas da capa do PDF gerado**
(`PdfReader`) em vez de assumir 1, para o carimbo continuar começando no
sumário se o desenho da capa um dia crescer.

**"Emitido em" mudou de lugar (regra A12 preservada):** o documento continua
com **um único** carimbo de emissão, mas ele agora vive na capa — saiu da
linha de subtítulo do sumário (`RelatorioService.gerarSummary`). Sem isso as
duas páginas chamariam `LocalDateTime.now()` separadamente e poderiam exibir
horários diferentes entre si. Travado por
`documentoTemUmUnicoCarimboDeEmissaoEEleFicaNaCapa`.

**Layout da capa** (A4, margens laterais 62pt): régua institucional azul de
3pt no topo · brasão 92pt de altura (escalado **pela altura**, não por um
quadrado — `static/brasao.png` é 300x168 com folga transparente nas laterais,
então `scaleToFit(N, N)` trava na largura e o brasão sai minúsculo; erro real
cometido e corrigido na primeira iteração deste desenho) · nome do órgão +
`SECRETARIA DE SAÚDE` · filete azul curto centralizado · título 27pt azul ·
subtítulo · painel de identificação (74% da largura, centralizado) · rodapé
em posição fixa com "Emitido em ..." + `PdfCabecalhoStamper.NOME_SISTEMA`.
**Sem faixa azul chapada** — o R4 reduziu de propósito a proporção da página
ocupada por decoração azul e esta capa não regride nisso (cor só na régua, no
filete, na borda do painel, no título e no texto do desfecho).

**Os 3 estados cobertos:** `RELATÓRIO FINAL` + `RESULTADO: DEFERIDO`
(verde) / `INDEFERIDO` (vermelho) / `CANCELADO` (cinza); e `RELATÓRIO
PARCIAL` + `SITUAÇÃO: Em andamento` (cinza) quando o processo ainda não foi
decidido — a capa **nunca** anuncia um status de tramitação como se fosse
desfecho ("RESULTADO: ENVIADO"), mesma correção já aplicada à seção "3." do
sumário (B4+A7).

**Cada rótulo do painel é um `Paragraph` separado do valor**, não um único
parágrafo com `\n`: num parágrafo só, o leading é calculado a partir da fonte
do PRIMEIRO chunk (o rótulo de 8pt) e o valor de 22pt subia por cima do
rótulo (bug real visto no primeiro PDF gerado).

**Validação:** os PDFs foram **gerados de verdade e inspecionados
visualmente** (não só assertivas de texto) nos 3 casos, com duas iterações de
ajuste a partir do que se viu (tamanho do brasão, leading do painel,
equilíbrio vertical). Suíte completa: **844 testes, 0 falhas** (JDK 21).

## Auditoria de `th:utext` (2026-08-07) — nenhuma ocorrência no projeto

Verificação pontual pedida pelo usuário: `th:utext` renderiza HTML cru sem
escapar (ao contrário de `th:text`), então qualquer uso sobre entrada de
usuário (nome de paciente, justificativa, texto de mensagem etc.) seria um
risco real de XSS armazenado. Grep completo em
`src/main/resources/templates/**/*.html` (e no repositório inteiro, por
segurança) não encontra **nenhuma** ocorrência de `th:utext` em nenhum
template — as duas únicas menções à string no código são a lista de
seletores de rótulo acessível do teste `AcessibilidadeBotaoIconeTest`
(inclui `"th:utext"` na lista de atributos aceitos, mas não usa a diretiva)
e um comentário já existente em `SecurityConfig` (linha ~76, sobre a CSP)
que já documentava essa ausência como justificativa para manter
`'unsafe-inline'` em script/style. Nenhuma correção de código foi necessária
— risco **inexistente**, não apenas mitigado.

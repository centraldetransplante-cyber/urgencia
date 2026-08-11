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

### Alerta por e-mail da falha de backup — CONFIRMADO INSTALADO (verificado em 2026-08-08)
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

**Estava documentado aqui como "ainda não instalado" (bloqueio do
classificador de segurança do harness na sessão que escreveu isto) — uma
vistoria de pendências em 2026-08-08 confirmou por SSH que já está
instalado**: `/etc/sudoers.d/sgpur-backup-alerta` existe na VM (datado de
2026-08-05 12:27 UTC — instalado manualmente pelo usuário pouco depois
daquela sessão, sem atualizar este registro) e `/opt/sgpur/backup-db.sh` já
contém a função `alertar()`. Pendência **encerrada**; texto acima mantido só
como histórico de por que foi escrito assim.

### `client_id` próprio do rclone — aparenta RESOLVIDO (verificado em 2026-08-08, não 100% confirmável por SSH)
O compartilhado seria desativado durante 2026 e derrubaria o backup offsite
silenciosamente — texto abaixo (`deploy/README-deploy.md`) descrevia o passo
a passo via Google Cloud Console + `rclone config` como pendência do
usuário. Evidência forte de que já foi feito: `/var/lib/postgresql/
.config/rclone/rclone.conf` tem as chaves `client_id`/`client_secret`
preenchidas (não só `type`/`token`, que é o mínimo do client padrão), e o
log `/var/log/sgpur-backup.log` **parou de emitir o aviso** "This remote
uses rclone's shared Google Drive client_id..." a partir de algum ponto
entre 2026-08-03 e 2026-08-06 — antes disso, o aviso aparecia em toda
execução diária sem exceção. Não dá para confirmar 100% por SSH que o
`client_id` é de fato um ID próprio (não o Console da Oracle/Google), mas a
combinação dos dois sinais é consistente com a tarefa concluída pelo
usuário. Se o aviso reaparecer no log num próximo backup, reabrir esta
pendência.

### Pendência que EXIGE o usuário no navegador (não dá por SSH)
Passo a passo completo em `deploy/README-deploy.md`.
- **Reservar o IP público** no console Oracle (Compute → Instances → VNIC →
  IPv4 Addresses → Edit → Reserved): se for efêmero, parar a instância troca
  o IP e derruba DuckDNS/certbot. IP reservado continua dentro do Always Free.
  O `oci` CLI **não** está instalado na VM, então não dá para fazer por SSH.
  Sem sinal disponível via metadata/SSH para confirmar se já foi feito —
  segue como pendência real até o usuário confirmar no Console.

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
4. **`ProcessoValidator.temVotoCoordenadorFavoravel` lia `coordenador` ao
   vivo, não no momento do voto** (achado desta sessão, na época **não
   corrigido** de propósito — exigia decisão de produto). **Corrigido em
   2026-08-07** — ver a seção "Achado 4 (snapshot do papel de coordenador no
   voto) — implementado" mais abaixo neste arquivo: `Parecer.eraCoordenadorNoVoto`
   (snapshot gravado no instante do voto) resolve exatamente o cenário
   descrito abaixo. **Texto original do achado, mantido como histórico:**
   Cenário: se o coordenador votar Favorável, o
   processo é deferido com esse voto sozinho; mas se ELE deixar de ser
   coordenador depois (outro médico assume o cargo) e o processo ainda não
   foi decidido, o voto antigo dele deixa de contar como "voto de coordenador"
   na hora do `decidir` — ou o inverso, um médico que virou coordenador DEPOIS
   de votar como membro comum ganha retroativamente o peso de coordenador.
   Corrigir exigiria uma coluna nova em `Parecer` (snapshot do papel no
   momento do voto) com decisão explícita sobre a semântica correta — fora de
   escopo daquela sessão, ficou pendente de definição do usuário até 2026-08-07.
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
   **Correção de QA (achado posterior): `arquivo/lista.html` era a 4ª tela
   com o mesmo status cru sem o fragment.** Ficou de fora das 3 originais
   (`processos/detalhe.html`, `dashboard.html`, `processos/lista.html`) por
   não ter sido lembrada na varredura da correção de 2026-08-04 — o Arquivo
   é justamente a tela onde processos Deferido/Indeferido/Cancelado vivem
   permanentemente, então mostrar "Indeferido" cru sem indicar se a
   papelada pós-decisão (ofício/comprovante SNT + resposta ao solicitante)
   já foi concluída é o mesmo bug de confusão visual, só que na tela de
   consulta histórica. `ArquivoController.listar` já carregava `Page<Processo>`
   com a entidade completa (não uma projeção/DTO), então nenhuma mudança de
   controller/query foi necessária — só adicionar
   `<span th:replace="~{layout :: badgeEncerramento(${p}, 'ms-1')}"></span>`
   ao lado do badge de status na célula "Situação", mesmo padrão exato já
   usado em `processos/lista.html`. `arquivo/lista.html` é agora a 4ª e
   última tela candidata a essa regra — não sobra nenhuma tela do sistema
   listando status final de processo sem passar por este fragment.
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
   **independente** do número do processo CET-RS) — inspirado num documento
   real de referência (`Of nº 1398 Julho 2026 SNT.doc`, um ofício de verdade
   emitido pela Central de Transplantes ao SNT) que na época estava na raiz
   do repositório como material de apoio; **esse arquivo não existe mais no
   disco nem foi versionado** (`.doc`, gitignored) — confirmado ausente em
   vistoria de 2026-08-10. Se precisar dele de novo como referência de
   estrutura, peça ao usuário. O próximo número é calculado em
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
(ofício ao SNT).** O modelo `Of nº 1398 Julho 2026 SNT.doc` (não existe mais
no disco — ver nota acima, seção anterior) era um ofício **à
Coordenadora-Geral do SNT em Brasília** (pedido de alteração de status de
paciente) — documento diferente do ofício de indeferimento, que vai à
equipe solicitante. Esse item continua **não implementado**; o modelo foi
usado aqui só como referência de estrutura
(numeração, cabeçalho do departamento, bloco do destinatário).

### Bug corrigido: rascunho RTF sem acentuação (2026-08-08)

Achado numa simulação real de QA: `OficioService.gerarRascunhoRtf` tinha
todos os literais Java fixos **sem nenhum acento** ("Regulacao", "Divisao",
"Oficio n", "apos analise", "Permanecemos a disposicao" etc.) — diferente do
PDF antigo (`OficioService.gerar`), que a seção anterior já registra como
corrigido (acentuação correta, é documento oficial). O rascunho RTF é
exatamente esse mesmo documento oficial, só que editável — não fazia sentido
os dois caminhos divergirem.

**Correção:** todos os literais fixos de `gerarRascunhoRtf` foram acentuados
("Regulação", "Divisão", "Ofício nº", "após análise", "Permanecemos à
disposição", "À equipe solicitante", etc. — inclusive o fallback do motivo,
`"(motivo não informado)"`, que já era acentuado no PDF mas não no RTF) e o
fallback `OficioService.NUMERO_NAO_ATRIBUIDO` (compartilhado entre PDF e RTF)
passou de `"(numero nao atribuido)"` para `"(número não atribuído)"` — o PDF
também ganhou a acentuação correta nesse placeholder de tabela, que tinha
escapado da correção original por estar numa constante à parte.

**Nenhuma mudança no mecanismo de escape em si.** `OficioService.escaparRtf`
já cobria corretamente qualquer caractere fora do ASCII (`\'hh` no code page
`\ansicpg1252` declarado no cabeçalho do RTF) — usado tanto para texto
dinâmico (nome do paciente, motivo) quanto, através dos helpers `linha`/
`centralizado`, para os literais fixos. Bastava acentuar as strings Java
(`ç`, `ã`, `é`, `í`, `ú`, `à`, `À`, `º` etc.) normalmente — elas passam pelo
mesmo `escaparRtf` de sempre e saem como `\'hh`, nunca cru. **Não colocar
acento cru fora desse caminho**: qualquer literal novo em `gerarRascunhoRtf`
deve continuar entrando via `linha(...)`/`centralizado(...)`, nunca
concatenado direto no `StringBuilder` sem passar pelo escape.

**Validado gerando o RTF de verdade** (não só por assertiva de texto): um
teste escreveu `gerarRascunhoRtf(...)` em disco e o arquivo foi lido byte a
byte — confirmado que cada acento vira a sequência `\'hh` esperada (ex.
`Regula\'e7\'e3o` = "Regulação", `Of\'edcio n\'ba` = "Ofício nº", `\'c0
equipe` = "À equipe") e que a estrutura RTF (chaves balanceadas, sem
corrupção) permanece intacta. Coberto por
`OficioServiceTest.rascunhoTemAcentuacaoCorretaNosTextosFixos` (decodifica o
`\'hh` de volta para o caractere original via um helper de teste,
`desescapaRtf`, e compara com os literais acentuados esperados) e pelo teste
de fallback atualizado (`rascunhoUsaFallbacksQuandoOProcessoAindaNaoTem
NumeroDeOficioNemMotivo`), que passou a decodificar antes de comparar — a
string RTF crua nunca contém "número"/"não" literalmente, só o escape.

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
(UNAUTHORIZED))`) existia também em `SolicitanteController`,
`SolicitacaoOnlineTriagemController` e `ProcessoDetalheController` — não
foram tocados nesta correção original (escopo pedido foi só o Portal do
Avaliador, onde o bug foi reportado e reproduzido). O mesmo padrão
(`SessaoInvalidaException` + `GlobalExceptionHandler.handleSessaoInvalida`,
já genérico e reusável por estar no `@ControllerAdvice` global) resolve sem
duplicar código.

**`SolicitanteController.resolverUsuario` corrigido em sessão separada
(2026-08-08, PR #72).** Mesma troca (`SessaoInvalidaException` em vez de
`ResponseStatusException(UNAUTHORIZED)`), sem nenhuma classe nova — só
reaproveitando a infraestrutura já existente. Teste de regressão
`SolicitanteSessaoOrfaIntegrationTest`, no mesmo modelo de
`AvaliadorSessaoOrfaIntegrationTest` (sessão HTTP real via login por
formulário, username renomeado por baixo da sessão ativa, mesma sessão
reusada confirma redirect gracioso). `SecurityIntegrationTest.
solicitanteAcessaOProprioPortal` e `SolicitanteControllerTest.
resolverUsuarioLancaSessaoInvalidaQuandoUsuarioAutenticadoNaoExisteNoBanco`
(renomeado de `...Lanca401...`) atualizados para o novo comportamento.
`SolicitacaoOnlineTriagemController` e `ProcessoDetalheController`
continuam com o padrão antigo pendente — se o mesmo sintoma aparecer
nesses dois, é a mesma correção.

**Replicado em `SolicitacaoOnlineTriagemController` (2026-08-08).** Os 5
pontos desse controller (`detalhe`, `enviarMensagem`, `apagarMensagem`,
`mensagensJson`, `enviarMensagemAjax`, `apagarMensagemAjax`) que resolviam
o operador logado com o mesmo padrão antigo passaram a lançar
`SessaoInvalidaException` também — mesma infraestrutura reaproveitada
(`GlobalExceptionHandler.handleSessaoInvalida`), nenhuma classe nova. As
demais `ResponseStatusException` desse controller (`baixarAnexo`, com
`NOT_FOUND`/`FORBIDDEN` por posse de anexo) não foram tocadas, mesma
distinção de escopo já explicada acima. Coberto por
`SolicitacaoOnlineTriagemSessaoOrfaIntegrationTest` (mesmo modelo de
`AvaliadorSessaoOrfaIntegrationTest`: sessão HTTP real via login por
formulário, renomeia o `username` do operador "por baixo" da sessão ativa,
confirma redirect gracioso e sessão de fato invalidada). `SolicitanteController`
e `ProcessoDetalheController` continuam com o padrão antigo — se o mesmo
sintoma aparecer neles, é o mesmo fix a aplicar.

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

## Acentuação em RelatorioAnualService e RelatorioAvaliadorService (2026-08-07)

Mesmo tratamento já aplicado ao Relatório Final (R1b/R2, ver seção acima) —
esses dois documentos tinham ficado de fora daquela leva. Corrigidos todos
os literais de texto visíveis no PDF: título institucional
("URGÊNCIA RENAL"), títulos dos documentos ("Relatório Geral de Urgência
Renal", "Relatório do Avaliador"), rótulos de tabela ("Tempo médio", "Em
análise", "Solicita informação", "Médico 1/2/3", "Decisão") e o cabeçalho
"Nº/Ano" (era "No/Ano" — mesmo padrão "Nº" já usado no Relatório Final,
R0).

**`ResultadoParecer.descricao` continua INTOCADO** (decisão deliberada,
documentada acima) — as duas linhas que exibiam
`par.getResultado().getDescricao()` diretamente foram trocadas por
`PdfRelatorioBuilder.descricaoResultado(...)`, o mesmo tradutor local
(switch com literais acentuados) já usado pelo Relatório Final. Um segundo
tradutor novo, `PdfRelatorioBuilder.descricaoStatus(StatusProcesso)`, foi
criado no mesmo espírito para a coluna "Status" da lista de processos do
Relatório Anual — `StatusProcesso.getDescricao()` também não foi acentuado
(alimenta badges/telas do sistema, mudança de raio de impacto maior), só o
texto impresso no PDF usa a versão acentuada.

**Validação:** os dois PDFs foram **gerados de verdade e inspecionados
visualmente** (renderizados em imagem via PyMuPDF, já que este ambiente não
tinha `pdftoppm`/visualizador gráfico) — capa, resumo, tabela de tempo por
avaliador e lista de processos, cobrindo Deferido/Indeferido/Solicita
informação. Todos os acentos (Ê, Ó, É, Á, Ã, Ç) renderizaram corretamente
com a fonte Helvetica padrão do OpenPDF (WinAnsi/Cp1252, mesmo mecanismo já
usado no resto do sistema — nenhuma mudança de encoding foi necessária, só
escrever os literais Java com os caracteres acentuados corretos). Suíte
completa: **853 testes, 0 falhas** (JDK 21).

## Atraso progressivo no login (2026-08-07) — NÃO é bloqueio

Aprovado explicitamente pelo dono do produto: algo "leve, SEM bloqueio total
de conta" — mantém o espírito da decisão de 2026-07-28 de nunca deixar um
usuário legítimo trancado fora do sistema (que removeu o bloqueio de 15min
por força bruta, ver seção "Login audit trail" acima), mas dá alguma fricção
real contra um atacante tentando adivinhar a senha de um usuário específico.

**Mecanismo (`LoginAttemptService`):** após `LIMIAR_INICIAL` (2) falhas
seguidas do **mesmo username** dentro de uma janela de
`app.login.rate-limit.janela-minutos` (default 15min), cada falha SEGUINTE
soma `app.login.rate-limit.atraso-por-tentativa-ms` (default 1000ms) ao
atraso, até um teto de `app.login.rate-limit.atraso-maximo-ms` (default
5000ms) — ex.: 3ª falha atrasa 1s, 4ª atrasa 2s, 5ª+ atrasa sempre 5s (teto).
O atraso é aplicado com `Thread.sleep` dentro de `aoFalhar` (o listener do
evento de FALHA de autenticação do Spring Security), **antes** de a resposta
de erro voltar ao navegador.

**Por que NÃO é bloqueio, nunca:**
- O atraso só existe no caminho de **falha**. `aoLogarComSucesso` nunca
  chama o cálculo de atraso nem dorme — uma senha certa autentica **na
  mesma velocidade de sempre**, mesmo logo após várias tentativas erradas.
  Não há nenhum estado ("bloqueado") que impeça uma tentativa de acontecer.
- O contador do username é **zerado no sucesso**
  (`aoLogarComSucesso`/`limparContador`) — o atraso é sobre uma sequência de
  erros, nunca uma penalidade permanente ou cumulativa entre sessões
  distintas de tentativa.
- Teto de 5s (configurável): mesmo com dezenas de falhas seguidas, o atraso
  nunca cresce sem limite — evita virar, ele próprio, uma superfície de
  negação de serviço (segurar a thread da requisição por tempo desmedido).
- Janela de 15min: falha antiga fora da janela não conta mais na próxima
  tentativa — o contador reinicia, então uma tentativa isolada muito depois
  de outra não herda atraso nenhum.

**Por username, não por IP:** o cenário mitigado é alguém tentando adivinhar
a senha de UM usuário específico (credential stuffing/senha fraca) — um IP
corporativo atrás de NAT, com vários usuários legítimos, nunca deve ser
penalizado pelo erro de outro colega. Mapa em memória
(`ConcurrentHashMap<String, Contador>`), chave = username normalizado
(minúsculo, trim) — sem infraestrutura nova (Redis etc.), mesmo padrão leve
já usado no projeto.

**Testado sem sleep real longo:** os testes usam constantes pequenas
(1ms/tentativa, teto de 5ms) injetadas por um construtor com `@Value`
configurável, e um gancho `usarRelogioParaTeste` (só para teste) para
simular a expiração da janela sem esperar minutos de verdade — a mesma
lógica de produção (`calcularAtrasoMsEContabilizarFalha`) é exercitada
diretamente. `LoginAttemptServiceTest` cobre: primeiras falhas sem atraso;
atraso crescendo por falha; teto nunca ultrapassado; sucesso zera o
contador; janela expirada reinicia a contagem; e a regra central — sucesso
nunca é atrasado, mesmo após muitas falhas seguidas (medido por tempo de
execução real, deve ficar bem abaixo de 500ms). Suíte completa: **859
testes, 0 falhas** (JDK 21), sem aumento perceptível de tempo total.

## Achado 4 (snapshot do papel de coordenador no voto) — implementado (2026-08-07)

A "Vistoria de bugs de 2026-08-03" (seção acima) tinha registrado o Achado 4
como identificado mas **não corrigido de propósito** (exigia decisão de
produto). **Aprovado explicitamente pelo dono do produto nesta sessão** —
implementado.

**Não confundir com a decisão registrada em "docs: registra decisão
confirmada do achado B" (commit `91abe55`)**, que trata de um problema
DIFERENTE apesar do nome parecido ("Achado B" ali é o item 4 da vistoria de
2026-08 sobre `retomarAposInformacao`/decidir-na-hora-sem-esperar-o-3º-voto
— ver a seção "Solicita informação (PAUSA)" acima). Este bloco aqui é sobre
o Achado **4** da "Vistoria de bugs de 2026-08-03" (leitura ao vivo do papel
de coordenador).

**O problema:** `ProcessoValidator.temVotoCoordenadorFavoravel` lia
`parecer.getMembro().isCoordenador()` **ao vivo**, navegando a associação no
momento da decisão — não o papel que o membro tinha quando de fato votou. Se
o cargo de coordenador mudasse de mão entre o voto e a decisão final (outro
médico assume, ou o próprio deixa de ser coordenador), o peso do voto antigo
mudava retroativamente: um voto Favorável dado como coordenador podia deixar
de decidir sozinho, ou um voto dado como membro comum podia ganhar
retroativamente o peso de coordenador.

**Correção:** `Parecer.eraCoordenadorNoVoto` (`Boolean`, nullable) — snapshot
de `MembroUrgenciaRenal.coordenador` capturado no INSTANTE do voto, em
`AvaliadorController.registrarVoto` (junto com `setResultado`/
`setDataHoraVoto`/etc., mesma transação). `ProcessoValidator
.temVotoCoordenadorFavoravel` passou a ler esse snapshot em vez do papel ao
vivo do membro.

**Regra para pareceres antigos (nullable, SEM backfill obrigatório):** um
parecer votado ANTES desta mudança nasce com `eraCoordenadorNoVoto = null`.
A leitura trata `null` como **"não sabemos, não conta como voto de
coordenador"** — decisão conservadora deliberada: prefere negar
retroativamente o peso especial a um voto antigo (o processo cai de volta na
regra padrão de maioria 2 de 3, que ainda pode decidir corretamente com os
demais votos) a inferir esse peso de um dado que nunca foi de fato capturado
no momento do voto. Como o campo é nullable desde a criação, **não há
backfill manual necessário em produção** (mesmo raciocínio já documentado
para `Parecer.ultimoLembreteEm`/`conviteEnviadoEm`).

**Testes:** `SnapshotCoordenadorVotoIntegrationTest`
(`src/test/java/br/gov/saude/sgpur/web/`, `@SpringBootTest` + MockMvc + H2
real, mesmo modelo de `AvaliadorVotoTransacaoIntegrationTest`) cobre o
cenário completo do achado — coordenador vota Favorável pelo Portal (via
`POST /avaliador/{id}/votar` de verdade), o processo defere na hora, o cargo
de coordenador muda de mão em seguida, e o processo continua Deferido (o
snapshot do voto antigo não muda) — e o contraste: um parecer "legado"
simulado com `eraCoordenadorNoVoto=null` não conta como voto de coordenador
mesmo que o membro seja coordenador hoje, confirmado tanto via
`ProcessoValidator` direto quanto rodando `DecisaoAutomaticaScheduler
.varrer()` de verdade. Helpers de outros testes que construíam `Parecer`
diretamente simulando um voto de coordenador (`ProcessoServiceTest`,
`ProcessoValidatorTest`, `FluxoProcessoServiceTest`,
`DecisaoAutomaticaSchedulerIntegrationTest`) foram ajustados para também
setar `eraCoordenadorNoVoto`, já que representam votos "atuais" simulados,
não pareceres legados. Suíte completa: **855 testes, 0 falhas relevantes**
(JDK 21— a única falha vista é a flakiness de timing pré-existente e
documentada em `ComprovanteSntPendenteQueriesIntegrationTest`, não
relacionada).

## Teste de integração real do scheduler de lembrete SNT (2026-08-07)

`ComprovanteSntLembreteSchedulerTest` (mocks, `MockitoExtension`) cobria a
lógica de orquestração/isolamento de falha do
`ComprovanteSntLembreteScheduler`, mas nunca exercitava contra um banco de
verdade a query real `ProcessoRepository.findCandidatosLembreteSnt` (prazo +
exclusão de processo já com `COMPROVANTE_SNT` + "não reenviar antes do
prazo") nem o `UPDATE` de linha única `registrarUltimoLembreteSnt` — a
mesma classe de risco já documentada em "Convenções de código" para rotas
que gravam algo irreversível. Adicionado
`ComprovanteSntLembreteSchedulerIntegrationTest` (`@SpringBootTest`, H2
real, `ProcessoRepository`/`ProcessoService` reais, só `EmailSenderService`
mockado — mesmo modelo de `DecisaoAutomaticaSchedulerIntegrationTest`),
cobrindo: processo Deferido sem comprovante SNT há mais que
`app.snt.lembrete.prazo-dias` dispara e-mail e grava `ultimoLembreteSntEm`
(relido do banco); processo dentro do prazo não dispara; processo já com
`TipoAnexo.COMPROVANTE_SNT` não dispara; falha de SMTP num processo não
impede a varredura dos demais candidatos elegíveis na mesma passada
(isolamento de falha por item). O teste unitário com mocks foi mantido —
continua sendo a cobertura mais rápida da lógica de orquestração em si.
Suíte completa: **857 testes, 0 falhas** (JDK 21).

## `@Version` em `Anexo`/`AnexoSolicitacaoOnline` (2026-08-07) — backfill CONCLUÍDO em 2026-08-08

As duas últimas entidades "quentes" sem lock otimista ganharam `@Version`
(campo `versao`, mesmo padrão de `Processo`/`Usuario`/`MembroUrgenciaRenal`/
`ControleUrgencia`/`SolicitacaoOnline`/`MensagemSolicitacao`). Sem isso, dois
uploads concorrentes para o mesmo processo/solicitação podiam se sobrescrever
silenciosamente (mesma classe de risco já corrigida nas outras entidades).

**Backfill executado em produção em 2026-08-08** (numa vistoria de
pendências, via SSH real na VM — não presumido): `SELECT count(*) ... WHERE
versao IS NULL` confirmou 39 linhas em `anexo` e 7 em
`anexo_solicitacao_online` antes do backfill; rodado
`UPDATE anexo SET versao = 0 WHERE versao IS NULL;` e
`UPDATE anexo_solicitacao_online SET versao = 0 WHERE versao IS NULL;`
(39 e 7 linhas afetadas, respectivamente); confirmado 0 linhas `NULL`
restantes nas duas tabelas depois. Mesmo padrão de "Backfill de
`Usuario.versao` feito" documentado acima — pendência **encerrada**.

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

## CORRIGIDO — erro 500 em `/auditoria` (2026-08-07, corrigido em sessão posterior no mesmo dia)

**Sintoma relatado:** `/auditoria` (tela exclusiva de ADMIN) devolvia erro
500 em produção, em TODA carga (com ou sem filtro de data preenchido).

**Causa raiz confirmada via SSH real** (`sudo journalctl -u sgpur`, dois
stacktraces reais capturados em produção, 2026-08-07 21:41 e 21:44 UTC):

```
Caused by: org.postgresql.util.PSQLException: ERROR: could not determine data type of parameter $7
SQLState: 42P18
```

na consulta de `AuditoriaController.listar` → `AuditoriaService.buscar` →
`LogAuditoriaRepository.buscar` (o método `buscar` original, com paginação),
que usava o padrão `:param IS NULL OR ...`:

```java
where (:usuario is null or :usuario = '' or lower(l.usuario) like lower(concat('%', :usuario, '%')))
  and (:acao is null or :acao = '' or l.acao = :acao)
  and (:de is null or l.dataHora >= :de)
  and (:ate is null or l.dataHora <= :ate)
```

O parâmetro `$7` correspondia à ocorrência de `:de` usada **isoladamente**
em `:de is null` — sem nenhum outro contexto de tipo na mesma posição
(Hibernate 6 gera uma posição `?` distinta para cada ocorrência textual do
mesmo `:param` nomeado, mesmo repetido). O PostgreSQL, via protocolo
estendido (`Parse`/`Describe`), precisa inferir o tipo de cada `?` **antes**
de qualquer valor chegar; um parâmetro usado só em `IS NULL`, sem nenhuma
comparação com uma coluna tipada por perto, não tem como ter seu tipo
inferido — e aqui, como o valor é sempre `null` quando não há filtro de
data, ele nunca ganhava tipo por outro caminho.

**Por que nunca apareceu na suíte local:** o H2 (dev/test) é tolerante a
parâmetro nulo sem tipo explícito nesse mesmo padrão SQL — o defeito só se
manifesta contra o dialeto real do PostgreSQL. Mesma classe de armadilha já
documentada no CLAUDE.md para CHECK constraints de enum/`@Version`: código
que passa limpo no H2 mas quebra em produção.

**Correção aplicada:** `LogAuditoriaRepository.buscar` foi reescrita no
mesmo padrão já usado por `LogAuditoriaRepository.buscarParaExportacao`
(escrita antes, na mesma sessão original, deliberadamente evitando esse
padrão desde o início) — nunca passar `null` para a consulta. A query
passou a exigir valores sempre efetivos:

```java
where (:usuario = '' or lower(l.usuario) like lower(concat('%', :usuario, '%')))
  and (:acao = '' or l.acao = :acao)
  and l.dataHora >= :de
  and l.dataHora <= :ate
```

`AuditoriaService.buscar` converte usuário/ação ausentes para string vazia
e data ausente para as sentinelas já existentes `DATA_MINIMA`/`DATA_MAXIMA`
(1900-01-01 / 2200-12-31 23:59:59) **antes** de chamar o repositório — o
mesmo tratamento que `buscarParaExportacao` já fazia. Com isso, todo
parâmetro sempre aparece em comparação com tipo bem definido pela coluna da
entidade, nunca isolado num `IS NULL`.

**Validado contra o Postgres real da VM antes do commit (leitura, sem
alterar nada):** como H2 não reproduz este bug (mesmo em `MODE=PostgreSQL`),
a prova de que a correção funciona precisou ser contra Postgres de verdade.
Um `PREPARE`/`EXECUTE` via `psql` na VM (`sudo -u postgres psql -d sgpur`),
que força a mesma etapa de inferência de tipo sem valores (equivalente ao
`Describe` do protocolo estendido que o driver JDBC/Hibernate dispara),
reproduziu o erro exato `could not determine data type of parameter $1`
com o padrão antigo, e confirmou sucesso (com e sem filtro) com o padrão
novo. Nenhum dado foi alterado — só `SELECT`, com `PREPARE`/`EXECUTE`/
`DEALLOCATE` de teste, removidos ao final.

**Teste de regressão:** `LogAuditoriaBuscaPaginadaIntegrationTest`
(`@SpringBootTest`, H2 real em `MODE=PostgreSQL`, mesmo padrão de
`LogAuditoriaExportacaoIntegrationTest`) cobre filtro por usuário parcial,
ação exata, período, paginação, e — o cenário exato do bug relatado —
`AuditoriaService.buscar` chamado com os 4 parâmetros `null` (equivalente a
abrir `/auditoria` sem clicar em nenhum filtro). **Limitação documentada no
javadoc da classe de teste:** o H2 não reproduz o `42P18` mesmo com a query
antiga (quebrada) — a suíte local não teria pego a recaída se alguém
reintroduzir o padrão `:param IS NULL OR ...` aqui; a proteção real contra
essa classe específica de erro só existe validando contra Postgres de
verdade (como feito manualmente nesta correção). Se o projeto ganhar
infraestrutura de teste contra Postgres real (Testcontainers ou similar),
vale portar este cenário para lá.

Suíte completa validada após a correção: **886 testes** (7 novos), única
falha vista foi a flakiness de timing pré-existente e não relacionada de
`ComprovanteSntPendenteQueriesIntegrationTest` (precisão de nanossegundos do
H2, já documentada em outras seções deste arquivo), confirmada isolada e
depois passando.

## Filtros de auditoria já existiam; exportação em CSV adicionada (2026-08-07)

**Filtro por usuário, ação e período em `/auditoria` já existiam** desde a
"fase E" da vistoria de UI do operador (commit `5ba6751`, 2026-08-04) —
`AuditoriaController.listar`/`AuditoriaService.buscar`/
`LogAuditoriaRepository.buscar`, com os 4 parâmetros opcionais combinados
na mesma consulta JPQL. Não havia nada a implementar nesse ponto (o pedido
do usuário presumia que não existiam ainda).

**O que faltava e foi implementado nesta sessão: exportação dos registros
filtrados em CSV.** `GET /auditoria/exportar` (mesmos parâmetros de query
de `/auditoria`: `usuario`, `acao`, `de`, `ate`), protegida pelo mesmo
padrão de string simples já existente para todo `/auditoria/**`
(`SecurityConfig`, `hasRole("ADMIN")` — nenhuma rota nova precisou de
matcher próprio).

- **Exporta exatamente as mesmas 5 colunas que a tela já mostra**
  (data/hora, usuário, ação, detalhe, IP) — nada além disso. Não introduz
  nenhum campo novo que pudesse vazar mais dado do que a própria tela.
- **O termo de filtro nunca é gravado em log de auditoria nem de
  aplicação** — mesmo padrão já documentado para as buscas de Membros/
  Usuários/Controle de Urgências/Solicitações online.
- CSV escolhido em vez de PDF: dado é tabular, o consumo típico
  (abrir no Excel/planilha, filtrar/ordenar/cruzar fora do sistema) é
  exatamente o caso de uso de CSV; gerar PDF exigiria paginação/tabela via
  OpenPDF sem nenhum ganho real para este caso. Separador `;` (não `,`)
  porque o Excel em `pt-BR` interpreta `;` como delimitador de coluna por
  padrão; BOM UTF-8 no início do arquivo evita acentuação corrompida ao
  abrir no Excel do Windows.
- **`LogAuditoriaRepository.buscarParaExportacao`: consulta JPQL nova,
  deliberadamente escrita para NÃO reproduzir o defeito descrito na seção
  anterior.** Em vez do padrão `:param IS NULL OR ...` (que quebra em
  produção — PostgreSQL não consegue inferir o tipo de um parâmetro usado
  só em `IS NULL`), `AuditoriaService.buscarParaExportacao` sempre passa
  valores efetivos e nunca `null`: string vazia para usuário/ação ausentes,
  e as sentinelas `DATA_MINIMA`/`DATA_MAXIMA` (1900-01-01 / 2200-12-31) para
  data ausente — nunca `LocalDateTime.MIN`/`MAX`.
  **Achado real durante esta sessão, corrigido antes do commit final:**
  a primeira versão usava `LocalDateTime.MIN`/`MAX` como sentinela "sem
  limite", mas o ano `-999999999`/`+999999999` desses valores estoura a
  faixa representável de `timestamp` no PostgreSQL/H2 (aprox. 4713 a.C. a
  294276 d.C.) — a comparação `dataHora >= inicio` nunca casava com
  NENHUM registro real, então a exportação "sem filtro de data" sempre
  devolvia lista vazia. Pego pelo próprio teste de integração (H2 real,
  não mock) antes de chegar a produção — trocado para sentinelas seguras
  (`1900-01-01`/`2200-12-31 23:59:59`, bem fora de qualquer uso real do
  sistema, mas dentro da faixa do banco).
- Escapamento CSV: campo com `;` ou `"` é envolvido em aspas duplas (aspas
  internas duplicadas), `\r`/`\n` do campo "detalhe" viram espaço (uma
  mensagem de auditoria multi-linha não pode quebrar uma linha do CSV).
- Testes: `AuditoriaControllerTest` (3 novos casos — cabeçalho + conteúdo do
  filtro, lista vazia só com cabeçalho, escapamento de `;`/`"`) e
  `LogAuditoriaExportacaoIntegrationTest` (novo, `@SpringBootTest` + H2 real
  em modo PostgreSQL — mesmo raciocínio de `BuscaListasIntegrationTest`:
  mock de repositório nunca pegaria um erro de sintaxe/tipo que só aparece
  contra um banco de verdade, e foi exatamente isso que expôs o bug do
  `LocalDateTime.MIN`/`MAX` acima): filtro por usuário parcial, por ação
  exata, por período, todos combinados na mesma consulta, e a tradução de
  `LocalDate`→limites do dia + aceitação de filtros nulos feita pelo
  service.
- Suíte completa validada após a mudança: **848 testes, 0 falhas** (JDK 21).

## Fix: botão "Enviar" do chat operador↔avaliador quebrava para a linha de
baixo (bug real de CSS, 2026-08-07)

Usuário relatou dois problemas juntos: "o CSS do chat do operador com
membro está horrível, botão de enviar está no lado esquerdo" e um 502 ao
tentar enviar mensagem. Investigados separadamente, com navegador de
verdade (Playwright) contra o app local — não só releitura de código.

**Causa raiz do CSS (confirmada navegando de verdade, não só lendo o CSS
fonte):** não era bug de HTML/markup nem de JS — o form do chat por
avaliador (card "Conversa", aba Respostas) é estruturalmente idêntico
(`<input>` antes do `<button>`, dentro de `.input-group`) ao chat que já
funciona corretamente (Portal do Solicitante). A diferença é que a thread
de conversa por avaliador vive dentro de `<table><td><form>` (é uma linha
da tabela de pareceres), e há uma regra genérica em `app.css` para
controles soltos em célula de tabela: `.table td form .form-control {
width: 100% }`. Essa regra tem especificidade MAIOR (2 classes + 2 tipos =
`0,0,2,2`) que a própria regra do Bootstrap `.input-group>.form-control {
width: 1% }` (2 classes = `0,0,2,0`) que normalmente faz o campo dividir a
linha com o botão — e vencia mesmo vindo antes no arquivo (especificidade
> ordem de declaração). Com o campo forçado a `width:100%` e
`.input-group` tendo `flex-wrap: wrap` (padrão do Bootstrap), o algoritmo
de quebra de linha do flexbox decide os cortes usando o tamanho-base
(`flex-basis`, que herda do `width` quando `flex-basis:auto`) **antes** de
aplicar o encolhimento — um campo cujo tamanho-base já é 100% do
container sozinho já preenche a linha inteira, então o botão ao lado é
empurrado para a linha seguinte, alinhado à esquerda por não sobrar espaço
na linha de cima. Confirmado via `getComputedStyle` no navegador real:
`flex-wrap: wrap` e o campo de texto com `width: 806px` (100% do
container de 806px), com o botão "Enviar" caindo numa segunda linha.

**Por que só esse chat e não os outros dois** (Portal do Solicitante,
Portal do Avaliador): são os únicos dois forms de chat do sistema que
**não** vivem dentro de uma `<table>` — a regra genérica de "controles
soltos em célula de tabela" nunca se aplicava a eles.

**Correção:** `src/main/resources/static/css/app.css` ganhou uma regra
mais específica logo depois da genérica, restaurando explicitamente o
`width: 1%` do Bootstrap dentro de `.input-group`:
```css
.table td form .input-group > .form-control { width: 1%; }
```
3 classes (`.table`, `.input-group`, `.form-control`) + 2 tipos (`td`,
`form`) vence a regra genérica de 2 classes + 2 tipos. A regra genérica
**continua existindo** — ela é legítima para os demais controles soltos em
célula de tabela (ex.: selects/inputs de ação de parecer), só não deve
mais vencer dentro de um `.input-group`.

**Teste de regressão:** `ChatAvaliadorInputGroupCssTest` (arquivo, não
`@WebMvcTest`/`MockMvc` — como já documentado para
`DesignSystemFontSizeInlineTest`/`AcessibilidadeEstruturaTest`, um teste de
controller nunca chega a calcular layout de CSS) garante que a regra
genérica continua existindo e que a regra de correção continua presente
**com especificidade estritamente maior** (mais classes no seletor) que a
genérica — falha alto se alguém remover/reordenar sem perceber o efeito
colateral. Validado com Playwright de verdade: antes da correção,
`getComputedStyle` mostrava o botão numa segunda linha (`inputLeft: 388,
btnLeft: 387` — sobrepostos, botão embaixo); depois, lado a lado
(`inputLeft: 388, btnLeft: 1119`).

**O 502 relatado era efeito colateral confirmado de deploys em sequência
rápida, NÃO um bug de aplicação.** Investigado por SSH direto na VM de
produção (`journalctl -u sgpur`, `/var/log/nginx/error.log` e
`access.log`): no dia do relato houve 4 restarts do `sgpur.service` em
~9 minutos (21:18, 21:22, 21:24, 21:27 UTC), cada um levando ~65s para
voltar a escutar na porta (boot normal do Spring Boot contra o Postgres da
VM). Todo `502` do nginx (`connect() failed (111: Unknown error) while
connecting to upstream`) caiu **exatamente** dentro dessas janelas de
restart, nas mesmas URLs do polling do chat (`GET /processos/{id}/
mensagens`, `GET /processos/{id}/avaliador/{membroId}/mensagens`, `POST
.../mensagem/ajax`) e também em `GET /login` (checado por `curl` externo
no mesmo período) — inclusive o `/login` (sem relação nenhuma com chat)
recebeu 502 nas mesmas janelas, provando que era o processo Java
inteiro fora do ar, não um endpoint específico quebrado. Depois do último
restart (21:27 + ~65s de boot), nenhum 502 novo apareceu nos logs; os
logs de aplicação mostram logins e uso normal em seguida, sem nenhuma
exceção nos endpoints de mensagem. **Não foi feita nenhuma alteração de
código por causa do 502** — não havia bug de aplicação para corrigir, só a
janela normal de indisponibilidade de um restart do systemd. Se voltar a
acontecer FORA de uma janela de deploy, investigar de novo (memória de RAM
apertada da VM compartilhada — ver seção "Vistoria de 2026-08-03" acima —
é a suspeita mais provável de uma próxima causa real).

## Fix: card de "Dúvida sobre este processo" (avaliador) nascia sempre
recolhido, escondendo mensagem nova + toast de chat virou clicável
(2026-08-07, mesma sessão do fix acima)

Continuação do relato do usuário: além do botão desalinhado (corrigido
acima), duas queixas adicionais confirmadas navegando de verdade com
Playwright contra o app local (screenshot antes/depois, não só leitura de
código):

**Causa raiz 1 — card do avaliador sempre nascia fechado.** Em
`avaliador/votar.html`, o card "Dúvida sobre este processo"
(`#chatBodyAvaliador`) tinha `class="card-body collapse"` **sem** `show` —
recolhido incondicionalmente no primeiro load, mesmo quando o operador já
tinha mandado mensagem antes. O poll (`iniciarChatSolicitacao`) sempre
rodou ali desde que essa tela existe (diferente do chat por-avaliador do
lado do operador, que só inicia o poll ao expandir de fato — ver comentário
"risco R5" no template) — ou seja, a mensagem **chegava** via AJAX (o badge
"N total" atualizava), só ficava escondida dentro do `<div class="collapse">`
fechado. Confirmado com Playwright: operador manda mensagem, avaliador
recarrega a tela, card continua fechado por padrão até 2026-08-07.

**Correção:** `MensagemAvaliadorRepository.countByProcessoIdAndMembroId` +
`MensagemAvaliadorService.existeConversa(processoId, membroId)` (nova
query de existência, não de contagem de não-lidas — decide só "já existe
QUALQUER mensagem nesta thread, lida ou não"). `AvaliadorController.votar`
expõe `existeConversaAval` ao model; `avaliador/votar.html` usa
`th:classappend="${existeConversaAval} ? 'show' : ''"` no `card-body` e
`th:attr="aria-expanded=${existeConversaAval}"` no cabeçalho — nasce
recolhido **só** quando a conversa está genuinamente vazia (não compete
com o formulário de voto, que é a ação primária da tela), e expandido
sempre que já há histórico, mesmo com tudo lido. Mesmo padrão aplicado ao
lado do operador: `ProcessoDetalheController.detalhe` calcula
`existeConversaPorParecer` (`Map<Long, Boolean>`, um por avaliador) e
`processos/detalhe.html` usa a mesma classe condicional na thread
`.chat-avaliador-thread` de cada parecer — o botão "Conversa" tinha um
badge de não-lidas, mas o card em si também nascia sempre fechado mesmo
com conversa relida.

**Causa raiz 2 — toast de "mensagem nova" não levava a lugar nenhum.**
Pedido explícito do usuário: "o aviso que vem no canto direito da tela
deveria ter um atalho pro local ideal do chat". `mostrarToast(mensagem,
tipo)` (`static/js/toast.js`) ganhou um **3º parâmetro opcional**
`onClick` — retrocompatível com as ~24 chamadas existentes sem esse
argumento (nunca clicáveis). Quando informado, o toast ganha
`role="button"`, `tabindex`, cursor de ponteiro (`.toast-sgpur-clicavel`
em `app.css`) e dispara `onClick()` ao clicar/Enter/Espaço, além de se
fechar. `chat-solicitacao.js` ganhou `irParaOChat()` (rola até
`cfg.collapseAlvoSelector` com `scrollIntoView({block:'center'})` e
expande o collapse via `bootstrap.Collapse.getOrCreateInstance(...).show()`
se estiver fechado) — chamado por `detectarNovasMensagens` como o 3º
argumento de `mostrarToast`, só quando `cfg.collapseAlvoSelector` foi
informado. Os 5 pontos de chamada de `iniciarChatSolicitacao` do sistema
(chat com o solicitante em `processos/detalhe.html`,
`processos/solicitacoes-online-detalhe.html` e `solicitante/detalhe.html`;
chat com o avaliador em `avaliador/votar.html` e por-parecer em
`processos/detalhe.html`) passaram a informar `collapseAlvoSelector`
apontando para o `id` do respectivo `.collapse` — nos 3 chats que já
nasciam sempre expandidos (solicitante), o toast clicável ainda funciona
(rola até o card, o `show()` do Bootstrap é idempotente se já estiver
aberto).

**Validado com Playwright real, não só os 841 testes de unidade** (novo
teste `src/test/java/br/gov/saude/sgpur/e2e/ChatVisualVerificacaoIT.java`,
roda via `mvn verify -Pe2e`/`.\e2e.ps1`, fora do `mvn test` do dia a dia):
cria um processo com os 3 pareceres pendentes, operador manda mensagem
real para o avaliador pela tabela de Respostas (confere via
`getComputedStyle`/`boundingBox()` que o botão continua ao lado do campo,
não regride o fix de CSS acima), avaliador abre `/avaliador/{id}` numa
sessão própria e confere que o card já nasce expandido com a mensagem
visível **sem nenhum clique**, avaliador responde, operador (página já
aberta, poll de 5s rodando) recebe um toast clicável, clica nele sem gerar
erro JS, e por fim confirma round-trip do chat com o solicitante também
com o botão alinhado. Screenshots em `target/e2e-screenshots/`
(`chat-operador-avaliador-alinhado.png`,
`avaliador-chat-expandido-com-mensagem.png`,
`operador-toast-clicavel-resposta-avaliador.png`,
`solicitante-chat-recebendo-mensagem-operador.png`) confirmam visualmente
os 4 pontos. Suíte completa (`mvn test`): **841 testes, 0 falhas** (JDK
21) antes deste commit.

**Reconfirmação independente (mesma sessão, revisão posterior):** os dois
achados acima foram reproduzidos manualmente do zero com um script
Playwright avulso (fora da suíte), rodando o app local via `java -jar` (H2
limpo): criação dos usuários pelo próprio `/usuarios/novo`, envio real de
solicitação pelo Portal do Solicitante, conversão/envio pelo operador,
confirmando (a) `#chatBodyAvaliador` sem `show` no primeiro load sem
conversa, (b) toast `.toast-sgpur-clicavel` aparecendo após o poll do
avaliador e expandindo/rolando até o chat ao clicar, (c) reload da tela do
avaliador já nascendo com `show` quando a conversa existe, e (d) reload
"a frio" da tela do operador (sessão nova, sem ter clicado em "Conversa"
antes) confirmando que o `<div class="chat-avaliador-thread">` já vem
`show` do servidor. Também foram adicionados 3 testes de integração novos
em `MensagemAvaliadorIntegrationTest`
(`telaDeVotoDoAvaliadorNascecomChatRecolhidoQuandoAindaNaoHaConversa`,
`telaDeVotoDoAvaliadorNascecomChatEXPANDIDOQuandoJaExisteConversa`,
`telaDeDetalheDoProcessoNascecomThreadDoAvaliadorEXPANDIDAQuandoJaExisteConversa`)
que leem o HTML renderizado (`MockMvc` + contexto real) e travam a presença/
ausência da classe `collapse show` conforme exista ou não mensagem na
thread — cobertura de regressão mais barata que o E2E Playwright para essa
parte específica (o E2E continua sendo o único jeito de cobrir o toast
clicável de verdade, que depende de JS rodando no navegador). Suíte
completa após esses 3 testes novos: **864 testes** (861 + 3), única falha
é a flakiness de precisão de timestamp já documentada em
`LembreteAvaliadorTimestampIntegrationTest` (não relacionada).

## Chevron de estado no botão "Conversa" com o avaliador (2026-08-07)

Ajuste puramente visual pedido pelo usuário. O botão "Conversa" da tabela
de pareceres em `processos/detalhe.html` (que abre/fecha a thread de chat
com CADA avaliador, `#chatAval{id}`) não tinha nenhum indicador visual de
estado aberto/fechado — diferente dos outros dois pontos de chat do
sistema, que já usam a mesma linguagem visual: o cabeçalho de "Conversa
com o solicitante" (mesmo arquivo) e o card "Dúvida sobre este processo"
(`avaliador/votar.html`), ambos com um ícone `bi-chevron-up`/
`bi-chevron-down` + classe `chevron-collapse` (`app.css`, gira 180° via
`[data-bs-toggle="collapse"].collapsed .chevron-collapse`, aplicado
automaticamente pelo próprio Bootstrap quando o elemento com
`data-bs-toggle="collapse"` está recolhido — sem JS adicional).

Correção: o botão "Conversa" ganhou o mesmo ícone
(`<i class="bi bi-chevron-up chevron-collapse ms-1"></i>`), dentro do
próprio `<button>` (que já é o elemento com `data-bs-toggle="collapse"`,
diferente do padrão de cabeçalho de card usado nos outros dois pontos, mas
o seletor CSS funciona igual porque não depende de ser um cabeçalho — só
do atributo `data-bs-toggle="collapse"` no ancestral/próprio elemento).
Nenhum comportamento funcional mudou (poll AJAX, envio, expansão inicial
via `existeConversaPorParecer` — tudo intocado), só a classe/ícone novos.
Os outros dois pontos já estavam corretos e não precisaram de mudança.
Suíte completa validada sem regressão (JDK 21).

**Ajuste posterior no mesmo PR (mesmo dia): aviso de imparcialidade da
thread menos chamativo.** O texto fixo dentro de cada thread de chat com
avaliador ("Esta mensagem será lida pelo médico avaliador. Refira-se ao
paciente apenas pelas iniciais... Não cite o nome, a equipe solicitante
nem os pareceres dos outros avaliadores.") estava num
`<div class="alert alert-warning py-2 mb-2 small">` com ícone
`bi-exclamation-triangle-fill` — visualmente pesado para um aviso que
aparece em toda thread, competindo com as mensagens de verdade do chat.
**O texto foi mantido integralmente** (é a defesa documentada contra
vazar nome/equipe/parecer nesse canal, complementar ao bloqueio
automático por código do `VerificadorNomePaciente` — ver seção "Chat
interno Avaliador ↔ Operador" acima) — só o destaque visual mudou: virou
um `<p class="small text-muted mb-2">` simples, sem `alert-warning`, sem
ícone, no mesmo padrão discreto já usado em vários outros avisos
auxiliares do sistema (ex.: o texto de ajuda acima do accordion de
"Textos de e-mail prontos", no mesmo template). Continua em fluxo normal
do documento (sem `position: absolute`/z-index), como o primeiro elemento
dentro do card da thread, antes da lista de mensagens/campo de
digitar/botão enviar — confirmado por HTML renderizado de verdade (teste
de integração `@SpringBootTest`+`MockMvc` temporário, descartado após a
verificação) que o parágrafo não sobrepõe nenhum outro elemento do chat
(`.chat-box`/`.chat-form-avaliador` não têm `position` absoluta em
`app.css`, então qualquer conteúdo em fluxo normal nunca se sobreporia).
Suíte completa revalidada: **879 testes, 0 falhas** (JDK 21).

## Chat operador↔solicitante: nome real do solicitante em vez do literal genérico (2026-08-07)

**Bug relatado pelo usuário**: no chat entre OPERADOR e SOLICITANTE (as duas
telas do lado do operador — `processos/detalhe.html` e
`solicitacoes-online-detalhe.html`/triagem), toda mensagem do solicitante
aparecia rotulada com o texto fixo genérico **"Solicitante"**, em vez do
nome de verdade da pessoa. `ProcessoDetalheController.mensagensJson` e
`SolicitacaoOnlineTriagemController.mensagensJson` passavam o literal
`"Solicitante"` como `labelOutro` para `MensagemSolicitacaoService.paraChat(...)`.

**Não é uma questão de imparcialidade** (a regra de só-iniciais do CLAUDE.md
é sobre o **paciente**, para os avaliadores — ver seção "Identificação do
paciente"). O solicitante é um usuário do próprio sistema conversando
diretamente com o operador; não há motivo de negócio para esconder quem ele
é nesse canal — o e-mail de resposta ao solicitante já usa nome completo do
paciente, então esconder o nome de quem está do outro lado do chat não
protegia nada.

**Correção:** `SolicitacaoOnlineRepository.findNomeSolicitanteById(Long id)`
(projeção `select s.usuarioSolicitante.nome from SolicitacaoOnline s where
s.id = :id`, não a entidade + navegação LAZY — `spring.jpa.open-in-view` é
`false` neste projeto, então tocar `s.getUsuarioSolicitante().getNome()`
fora da transação do serviço estouraria `LazyInitializationException`).
`SolicitacaoOnlineService.nomeSolicitante(Long)` expõe isso com fallback
para o literal `"Solicitante"` se o nome vier nulo/em branco (nunca quebra a
tela). Os dois controllers passaram a chamar esse método e usar o nome real
como `labelOutro`.

**`SolicitanteController` não foi tocado** — o rótulo do lado do
solicitante para o "outro lado" da conversa é `"Equipe CET-RS"` (o time
operacional, não uma pessoa específica), e isso continua correto/intencional.

**Testes**: `ProcessoDetalheSemTransacaoIntegrationTest` e
`SolicitacaoOnlineTriagemSemTransacaoIntegrationTest` (ambos `@SpringBootTest`
+ H2 real, sem `@MockitoBean` de serviço) ganharam uma mensagem do
solicitante no fixture e um teste
(`mensagensAjaxRotulaMensagemDoSolicitanteComONomeRealENaoComLiteralGenerico`)
que confirma o JSON de `GET .../mensagens` contendo o nome real
("Solicitante Detalhe Teste"/"Solicitante Teste") em vez do literal antigo.

## Toast do poll global clicável + acentuação de mensagens em controllers (2026-08-08)

Duas correções pontuais de UX/ortografia, sem mudança de regra de negócio.

**1. Toast do poll GLOBAL de mensagens (`layout.html`) virou clicável.**
Os 4 blocos `<script>` de poll global de notificação (20s, dentro do
fragment `navbar`: ADMIN/OPERADOR "Nova mensagem de um solicitante.",
SOLICITANTE "Nova mensagem da equipe CET-RS.", AVALIADOR "Nova mensagem da
equipe CET-RS sobre um dos seus processos." e ADMIN/OPERADOR "Nova mensagem
de um médico avaliador.") chamavam `mostrarToast(mensagem, tipo)` sem o 3º
parâmetro `onClick` (`static/js/toast.js` já suporta desde 2026-08-07, ver
o comentário do próprio arquivo — usado por `chat-solicitacao.js` para os
toasts *dentro* das 3 telas de chat, que já eram clicáveis). Estes 4 blocos
diferentes (o poll global que roda em QUALQUER tela, fora das telas de chat
— ver `chatAtivoNestaTela`) tinham ficado de fora dessa correção anterior.
Agora cada um navega para a tela correspondente ao clicar: operador/admin
(mensagem de solicitante) → `/processos/solicitacoes-online`; solicitante →
`/solicitante`; avaliador → `/avaliador`; operador/admin (mensagem de
avaliador) → `/processos/mensagens-avaliadores`. Usa
`/*[[@{...}]]*/` (Thymeleaf inlining) com fallback para a URL literal — os 4
`<script>` já tinham `th:inline="javascript"`, sem o qual esse padrão
falharia silenciosamente (ver "Convenções de código").

**2. Acentuação de literais Java em 4 controllers.** A "Fase 8" de
acentuação (2026-08-03/04) cobriu só templates HTML Thymeleaf, não string
literals dentro de código Java — mensagens de flash (`erro`/`sucesso`/
`aviso`), corpos de `ResponseEntity`/`Map` de erro JSON e descrições de
anexo visíveis ao usuário em `SolicitanteController`, `AvaliadorController`,
`ProcessoDetalheController` e `ProcessoDecisaoController` estavam sem
acento. Corrigidas todas as mensagens desses 4 arquivos (e os testes que
faziam assert do texto exato/`containsString` do texto antigo sem acento,
em `ProcessoDecisaoControllerTest`, `ProcessoDetalheControllerTest`,
`SolicitanteControllerTest` e `SubstituicaoDocumentoAnonimizadoIntegrationTest`).
**`StatusProcesso.descricao` (`getDescricao()`) foi DELIBERADAMENTE
mantido sem acento** — mesmo padrão já documentado para
`ResultadoParecer.descricao`: é consumido por `RelatorioService.java`
(Relatório Final PDF) e `ExportacaoProcessoService.java` (dossiê
exportado), então mudar o enum teria impacto direto em documentos oficiais,
fora do escopo desta correção de UX. Prompts enviados à API do Gemini
(`sugestaoMotivo`/`revisarEmailIa` em `ProcessoDecisaoController`) também
não foram tocados — são instruções para a IA, não mensagens exibidas ao
usuário.

Suíte completa validada (JDK 21): 890 testes, 0 falhas atribuíveis a esta
mudança (a única falha vista, `ComprovanteSntPendenteQueriesIntegrationTest
.registrarUltimoLembreteSntGravaOTimestampNoBanco`, é a flakiness de
precisão de nanossegundos do H2 já documentada em outras sessões — passa
isolada, confirmado nesta mesma sessão).
Suíte completa validada (JDK 21), sem regressão.

## Dois bugs de robustez achados em QA (2026-08-08): 500 cru em `/usuarios/minha-senha` e no Painel/`/processos`

Achados numa simulação de QA (Playwright, ambiente local H2), investigados e
corrigidos na mesma sessão, cada um com causa raiz confirmada por
reproducao direta (nunca presumida) e teste de integracao real (H2, sem
mock do service - mesma convencao do CLAUDE.md para escrita irreversivel/
caminho de falha).

### Bug 1 - `POST /usuarios/minha-senha` devolvia 500 para `Usuario.versao` nula

**Sintoma:** trocar a propria senha (`/usuarios/minha-senha`, disponivel a
qualquer perfil autenticado) devolvia "Erro interno do servidor" em vez de
trocar a senha, para um usuario cujo `Usuario.versao` estava `NULL` no
banco - cenario real em qualquer arquivo H2 de desenvolvimento criado
*antes* do commit `b34643a` (2026-07-29, que adicionou `@Version` a
`Usuario`) sem o backfill manual (`UPDATE usuario SET versao = 0 WHERE
versao IS NULL`) ter rodado - o H2 de dev e um arquivo persistente entre
reinicios (`data/sgpur.mv.db`, nunca apagado), entao e facil acumular linhas
assim sem perceber.

**Causa raiz (confirmada reproduzindo direto, nao presumida):** ao contrario
do que se poderia esperar, salvar um `Usuario` com `versao == null` **nao**
lanca `ObjectOptimisticLockingFailureException` (que o
`GlobalExceptionHandler` ja trata graciosamente desde a vistoria de
2026-07-24, ver "Regras de negocio" acima). O Hibernate lanca uma
`NullPointerException` **crua** dentro de si mesmo
(`org.hibernate.type.descriptor.java.LongJavaType.next`, ao tentar
`current.longValue()` com `current == null` para incrementar a versao no
momento do **commit** da transacao), envolvida numa
`org.springframework.transaction.TransactionSystemException` - um tipo que
nenhum `@ExceptionHandler` do projeto reconhecia, resultando no fallback
generico de 500.

**Duas tentativas de correcao, so a segunda funcionou (documentado para nao
recair):**
1. **`u.setVersao(0L)` num objeto ja gerenciado antes de `save()` - NAO
   resolve**, mesmo confirmado por reproducao direta. O Hibernate calcula a
   proxima versao a partir do **snapshot carregado na sessao no momento do
   `SELECT`** (o mesmo valor usado na clausula `WHERE` do `UPDATE` real para
   o lock otimista) - nao a partir do valor atual do campo no objeto Java.
   Mudar so o campo em memoria nao muda esse snapshot; o Hibernate segue
   tentando incrementar o `null` original no commit.
2. **Correcao de verdade: alcancar o BANCO e recarregar.**
   `UsuarioRepository.normalizarVersaoNula(Long id)` - um `@Modifying
   (clearAutomatically = true) @Query("update Usuario u set u.versao = 0
   where u.id = :id and u.versao is null")` - corrige a coluna direto no
   banco (bypassa o lock otimista, e um UPDATE em lote/bulk) e
   `clearAutomatically = true` descarta o persistence-context inteiro depois
   dele. `UsuarioService.normalizarVersaoLegada(Usuario u)` (privado, chamado
   logo apos CADA fetch de escrita - `atualizar`, `alternarAtivo`,
   `resetarSenha`, `alterarPropriaSenha` - **antes** de qualquer `set...` no
   objeto, porque `clearAutomatically` descartaria mutacoes pendentes) checa
   `versao == null`, roda a normalizacao e **RECARREGA** a entidade
   (`buscar(u.getId())`), retornando essa nova instancia (ja com `versao =
   0`) para o chamador continuar a partir dela.

**Arquivos:** `src/main/java/br/gov/saude/sgpur/repository/
UsuarioRepository.java`, `src/main/java/br/gov/saude/sgpur/service/
UsuarioService.java`. Teste: `src/test/java/br/gov/saude/sgpur/web/
UsuarioMinhaSenhaVersaoNulaIntegrationTest.java` (`@SpringBootTest` + H2
real + sessao HTTP via `SecurityMockMvcRequestPostProcessors.user(...)`,
forca `versao = NULL` via SQL nativo no fixture, confirma `POST
/usuarios/minha-senha` redireciona com sucesso - nunca 500 - e que a senha
nova vale de verdade relida do banco). `UsuarioServiceTest` (mocks) precisou
setar `versao(0L)` nos fixtures de `Usuario` usados com `resetarSenha` - sem
isso, o proprio `new Usuario()` de teste (nunca persistido, `versao` nula
por default) disparava a normalizacao e quebrava porque `repo.findById`
nao estava stubado nesses testes (nao e o cenario real do bug, so um
artefato de POJO de teste puro).

### Bug 2 - Painel (`/`) e `/processos` (sem filtro) devolviam 500 com `Anexo.tipo` fora do enum atual

**Sintoma:** um unico `Anexo` com `tipo` gravado no banco fora dos valores
validos atuais de `TipoAnexo` (ex.: `CAPA_PROCESSO`, `SOLICITACAO_RECEBIDA`
- **removidos por completo do enum** no commit `041dc43`, 2026-07-29, mas
que podem sobrar em linhas de banco antigo, ja que `ddl-auto: update`
**nunca** valida dado ja gravado - ver "Convencoes de codigo") derrubava o
Painel **inteiro** e a lista de `/processos` **inteira** com 500, mesmo
havendo dezenas de outros processos saudaveis na mesma pagina.

**Causa raiz (confirmada reproduzindo direto):** duas variantes do mesmo
problema de fundo, dependendo do caminho de acesso:
- `ProcessoRepository.inicializarAnexos` (consulta em lote/JOIN FETCH,
  chamada por `HomeController.dashboard` e, via `ProcessoService.
  inicializarPareceresEAnexos`, por `ProcessoListaController.listar`) lanca
  `org.springframework.dao.InvalidDataAccessApiUsageException` (traduzida
  pelo Spring a partir de um `IllegalArgumentException: No enum constant
  ...`) - um tipo **sem nenhum** `@ExceptionHandler` no projeto.
- O acesso lazy direto (`p.getAnexos()`, em `FluxoProcessoService.
  montarEtapas`/`temAnexo`) lanca o `IllegalArgumentException` **cru**, sem
  traducao - esse **e** capturado por
  `GlobalExceptionHandler.handleNotFound` (`@ExceptionHandler
  (IllegalArgumentException.class)`), mas produz um redirect confuso
  ("Registro nao encontrado") para a tela inteira por causa de UM processo
  com dado ruim - ainda uma degradacao ruim, mesmo nao sendo 500 tecnico.

**Correcao, em duas camadas** (a mesma classe de protecao ja documentada
para outros defeitos de robustez do projeto - nunca deixar dado legado
inesperado virar 500 cru; degradar ignorando/logando a linha invalida):
1. **Pre-carregamento em lote com fallback.**
   `HomeController.dashboard` e `ProcessoService.inicializarPareceresEAnexos`
   agora envolvem a chamada a `inicializarAnexos` num `try/catch
   (RuntimeException)`: se falhar, loga um aviso e segue sem o
   pre-carregamento - cada processo volta a carregar seus proprios anexos
   sob demanda (lazy), mais lento (reintroduz o N+1 que o metodo existe para
   evitar) mas so na hipotese rara de dado invalido.
2. **Isolamento por processo.** `FluxoProcessoService.anexosSeguro(Processo
   p)` (privado, novo) substitui os dois usos diretos de `p.getAnexos()`
   (`montarEtapas`/`temAnexo`): tenta materializar a colecao
   (`new ArrayList<>(p.getAnexos())` - **crucial forcar a materializacao
   dentro do proprio `try`**, ver "bug do meu proprio fix" abaixo), captura
   `RuntimeException`, loga o `Processo.id` afetado e devolve lista vazia.
   Assim, so o processo com o anexo corrompido perde o calculo de
   pendencia/documento clinico naquele item - o resto da pagina (Painel ou
   lista) renderiza normalmente, sem 500 e sem redirect surpresa.

**Bug do proprio fix, pego pelo teste de integracao real (nao pela leitura
do codigo):** a primeira versao de `anexosSeguro` fazia so `return
p.getAnexos();` dentro do `try` - mas isso devolve a **referencia** da
colecao lazy, ainda nao inicializada; a excecao de hidratacao so dispara no
primeiro acesso de verdade (`.stream()`/`.iterator()`), que acontecia
**fora** do `try`, no codigo chamador. Corrigido forcando a materializacao
(`new ArrayList<>(...)`) dentro do proprio bloco `try`. Licao: um `try/catch`
em volta de uma colecao JPA lazy so protege de verdade se tambem forca a
inicializacao - devolver a referencia sem tocar nela nao conta.

**Arquivos:** `src/main/java/br/gov/saude/sgpur/service/
FluxoProcessoService.java`, `src/main/java/br/gov/saude/sgpur/service/
ProcessoService.java`, `src/main/java/br/gov/saude/sgpur/web/
HomeController.java`. Teste: `src/test/java/br/gov/saude/sgpur/web/
AnexoTipoInvalidoNaoDerrubaPainelIntegrationTest.java` (`@SpringBootTest` +
H2 real, insere um `Processo` com um `Anexo` de `tipo = 'CAPA_PROCESSO'` via
SQL nativo, confirma `GET /` e `GET /processos` retornam 200).

**Validacao:** suite completa, **891 testes** (2 novos: um por bug), 0
falhas relacionadas - a unica falha vista numa rodada foi a flakiness de
precisao de timestamp ja documentada
(`ComprovanteSntPendenteQueriesIntegrationTest`), confirmada pre-existente
e nao relacionada rodando o mesmo teste isolado (passou).

**Nota de processo desta sessao:** o trabalho foi feito num **git worktree
dedicado** (`../urgencia-wt-fix500`), nao no checkout principal - esta
maquina tinha multiplas sessoes/processos concorrentes mexendo no mesmo
diretorio principal (branch trocado e edicoes nao commitadas revertidas por
fora, no meio da investigacao), entao isolar o trabalho num worktree proprio
evitou perder o progresso de novo.

## Extração de texto em PDF: investigação do "acento corrompido" — bug NÃO reproduzido, hardening aplicada mesmo assim (2026-08-08)

Investigação pedida a partir de um relato de "achado em simulação de QA":
texto extraído (copiar/colar, Ctrl+F, leitor de tela) dos PDFs do Relatório
Final, Relatório Anual e Relatório do Avaliador viria corrompido nos
caracteres acentuados (`á` virando `�`/U+FFFD), apesar do render visual
estar correto — causa alegada: `ToUnicode` CMap ausente nas fontes Helvetica
não embutidas (`FontFactory.getFont`/`BaseFont.createFont(..., WINANSI/
CP1252, NOT_EMBEDDED)`, usadas em `PdfCabecalhoStamper`, `PdfRelatorioBuilder`,
`RelatorioAnualService`, `RelatorioAvaliadorService`).

**Resultado da investigação: o bug alegado NÃO reproduz.** Gerados os 3 PDFs
de verdade com o código **sem nenhuma alteração** (branch limpa a partir de
`main`) e extraído o texto com **três** ferramentas independentes —
`pypdf` 6.15, `PyMuPDF` 1.28 e `poppler pdftotext` — contando
programaticamente ocorrências de `�` (não visualmente): **zero** em
todos os casos, nos 3 documentos, nas duas bibliotecas Python, com o texto
acentuado ("Conceição", "Urgência", "São José", "inequívoca", "Decisão")
presente e correto. A "corrupção" observada na simulação original de QA (e
replicada por mim na primeira tentativa) era um **artefato de encoding do
console**: imprimir uma `string` Python corretamente decodificada
(`Conceição`, Unicode de verdade) para um terminal Windows/git-bash sem
`PYTHONIOENCODING=utf-8` definido produz `Concei??o`/`Concei��o`
**na tela**, mesmo que a string em memória esteja perfeita — confirmado
depurando `pypdf._cmap.parse_bfchar` e comparando `ord(char)` (correto, ex.
`0xE7`) contra o texto impresso no console (garbled). Ferramentas de
extração de PDF, incluindo as duas testadas, **já implementam corretamente**
o fallback da especificação PDF (ISO 32000-1 §9.10.2): na ausência de
`ToUnicode`, uma fonte simples com `/Encoding /WinAnsiEncoding` é resolvida
via a tabela padrão código→nome de glifo→Unicode — exatamente o caso destes
3 documentos (`get_fonts(full=True)` confirmou `/Encoding /WinAnsiEncoding`
em toda fonte usada). O render visual nunca esteve em risco (é sempre
correto, com ou sem `ToUnicode`) porque o desenho do glifo usa a mesma
tabela `WinAnsiEncoding`, não o `ToUnicode`.

**Mesmo sem bug confirmado, a hardening foi implementada e mesclada**, como
correção defensiva de baixo custo/baixo risco: nem toda ferramenta do
ecossistema PDF implementa o fallback via `/Encoding` corretamente (é um
comportamento opcional, não obrigatório, da leitura de simples fontes sem
`ToUnicode`) — um sistema de indexação/OCR mais rígido, um leitor de tela
mais antigo, ou uma automação futura poderiam se comportar diferente das
duas bibliotecas testadas aqui. `PdfCabecalhoStamper` (usado pelos 3
geradores, sempre como último passo de pós-processamento) ganhou um SEGUNDO
passe de leitura/gravação (`corrigirToUnicodeDeFontesSimples`, chamado por
`estampar` depois de `carimbarPaginas`) que injeta manualmente um
`/ToUnicode` CMap (formato padrão da especificação, §9.10.3) em toda fonte
`/Type1` `WinAnsiEncoding` sem um já presente — cobre tanto as fontes do
corpo original quanto as criadas pelo próprio carimbo (cabeçalho +
numeração de página, que também tem acento: "Página X de Y"). A tabela
byte→Unicode usada (`PdfCabecalhoStamper.WINANSI_BYTE_PARA_UNICODE`, 256
entradas) é uma cópia literal de `com.lowagie.text.pdf.PdfEncodings
.winansiByteToChar` (pacote-privada no OpenPDF, por isso copiada) — a MESMA
tabela que o OpenPDF usa para converter caracteres Java em bytes na escrita,
garantindo round-trip exato nos dois sentidos. **Zero mudança visual**: os
bytes do conteúdo da página não são tocados, só é adicionado um objeto novo
(`/ToUnicode`) referenciado pelo dicionário da fonte.

**Cuidado real encontrado e corrigido durante a implementação:** todo
`PdfStamper` do OpenPDF, por padrão, anexa `"; modified using OpenPDF
X.Y.Z"` ao `/Producer` existente ao fechar — como o segundo passe usa um
`PdfStamper` novo, isso sujava o `/Producer` institucional
(`Central de Transplantes do Estado do Rio Grande do Sul`, gravado por
`anonimizarMetadados` no primeiro passe) com esse sufixo técnico, quebrando
2 testes existentes
(`PdfCabecalhoStamperTest.estamparMantemProducerInstitucionalMesmoSemMetadadosDeOrigem`/
`estamparRemoveNomeDoPacienteDeTodasAsChavesDoInfo`). Corrigido reafirmando
o `/Producer` explicitamente via `stamper.setInfoDictionary(Map.of(
"Producer", NOME_INSTITUICAO))` no segundo passe também (mesma API
`setInfoDictionary`, não o `setMoreInfo` deprecado, já documentado acima
para o primeiro passe).

**Validação (antes/depois, com os 3 extratores, PDFs gerados de verdade —
não simulado):**
```
# ANTES da correção (código de main, sem alteração):
pypdf:    FFFD count = 0  (3 documentos)
PyMuPDF:  FFFD count = 0  (3 documentos)
pdftotext -enc UTF-8: "João da Silva Conceição", "URGÊNCIA RENAL" presentes e corretos

# DEPOIS da correção (com /ToUnicode injetado):
pypdf:    FFFD count = 0  (3 documentos) — sem regressão
PyMuPDF:  FFFD count = 0  (3 documentos) — sem regressão
pdftotext -enc UTF-8: idêntico ao antes, mais o /Producer sem sufixo "modified using"
/Producer do PDF resultante: "Central de Transplantes do Estado do Rio Grande do Sul" (sem sufixo)
```
Ou seja: **antes** já não havia corrupção real (só a percebida no console),
e **depois** a extração continua correta, agora também com `/ToUnicode`
explícito presente (confirmado objeto a objeto via `PyMuPDF.xref_object`/
`xref_stream`) e sem a regressão do `/Producer`.

Testes novos em `PdfCabecalhoStamperTest`:
`estamparInjetaToUnicodeEmTodasAsFontesType1WinAnsiDoDocumento` (varre todo
objeto do PDF resultante, confirma `/ToUnicode` presente em toda fonte
`/Type1`/`WinAnsiEncoding` — cobre corpo original + fonte do próprio
carimbo) e `textoExtraidoDoDocumentoEstampadoMantemAAcentuacaoOriginal`
(ponta a ponta com `PdfTextExtractor`, o próprio extrator do OpenPDF).
Suíte completa: **890 testes, 0 falhas** (JDK 21).

**Lição de metodologia, para quem for investigar relato semelhante no
futuro:** ao extrair texto de PDF via script Python (ou qualquer linguagem)
para comparar "antes/depois" de um bug de acentuação, **nunca confie no que
aparece impresso no console** sem antes confirmar programaticamente (contar
`�`, comparar `ord()`/codepoints, ou escrever em arquivo UTF-8 e reler
com uma ferramenta que declara o encoding) — o console em si é uma fonte
comum de falso positivo nesse tipo de investigação, inclusive para quem já
está avisado do risco (aconteceu nesta própria investigação, na primeira
tentativa, antes de isolar a causa).

## Fix: cartão "Deferido"/"Indeferido" do Portal do Solicitante afirmava envio de e-mail que ainda não tinha ocorrido (2026-08)

**Bug real achado em simulação de QA (Playwright)**, no cartão de situação
único do detalhe do Portal do Solicitante (`SolicitanteController
.montarSituacaoPedido`, `solicitante/detalhe.html`). Quando o processo
estava Deferido, o cartão afirmava, ao mesmo tempo, duas coisas
contraditórias: que "a resposta oficial foi enviada por e-mail (...)
contendo o comprovante de inserção no Sistema Nacional de Transplantes
(SNT) em anexo" **e**, logo abaixo, que "Comprovante SNT ainda sendo
providenciado pela equipe" — o mesmo tipo de bug se repetia no ramo
Indeferido, com o ofício.

**Causa raiz:** o texto de `mensagem` era montado de forma incondicional em
`montarSituacaoPedido`, ignorando se o anexo (`comprovanteSnt`/
`oficioIndeferimento`, já calculados antes da chamada via
`AnexoStorageService.buscarUltimoPorTipo`) de fato existia, e ignorando
`Processo.emailEnviadoSolicitante` (só passa a `true` dentro de
`ProcessoService.finalizarResposta`, ver seção "Fluxo em 5 passos" acima) —
ou seja, o cartão podia anunciar "já enviado" para um processo que acabou
de ser Deferido automaticamente por maioria simples, mas cuja etapa 6
(Resposta ao solicitante) o operador ainda nem tinha executado. O aviso
"ainda sendo providenciado" (mais abaixo, no `solicitante/detalhe.html`) já
lia corretamente `situacao.anexoParaBaixar() == null` — a contradição era
sempre entre o texto fixo de `mensagem` e essa segunda checagem correta no
template, nunca uma lógica duplicada/divergente no HTML.

**Correção:** os dois ramos (`deferido`/`indeferido`) de
`montarSituacaoPedido` passaram a calcular `respostaJaEnviada` (anexo
correspondente não-nulo **e** `proc.isEmailEnviadoSolicitante()`) e montar
a `mensagem` condicionalmente: só afirma "foi enviada/enviado por e-mail"
quando as duas condições valem; caso contrário, afirma que a equipe **está
providenciando** o envio formal, sem mencionar um e-mail que ainda não
saiu. `SituacaoPedidoView` continua sendo a fonte única da decisão — nada
foi duplicado no template, que já consumia `situacao.anexoParaBaixar()`
corretamente.

**Testes** (`SolicitanteControllerTest`): 4 casos novos cobrindo Deferido
sem/com comprovante SNT (+ `emailEnviadoSolicitante`) e Indeferido sem/com
ofício, cada um verificando por `content().string(...)` que a frase de
"já enviado" só aparece no cenário correto e nunca coexiste com o aviso de
"ainda sendo providenciado".

**PR:** `fix/mensagem-comprovante-snt-contraditoria` (branch dedicada a
partir de `main`, sem outra mudança de regra de negócio).

## Sessão orfã em ProcessoDetalheController + N+1 no card "Respostas dos Avaliadores" (2026-08-08)

Duas correções pontuais achadas em vistoria, no mesmo arquivo
(`ProcessoDetalheController.java`), aplicadas juntas na branch
`fix/sessao-orfa-processodetalhe-e-nplus1-mensagens`.

**1. 401 cru vira redirect gracioso para `/login`.** O controller tinha 8
ocorrências do padrão `usuarioRepo.findByUsername(principal.getName())
.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED))`
— exatamente o mesmo bug já corrigido em `AvaliadorController.resolverMembro`
(ver seção "Fix: 401 cru no Portal do Avaliador com sessão órfã" acima):
se um ADMIN renomeia o `username` de um operador (ou exclui a conta)
enquanto ele tem sessão ativa, o Spring Security não relê o `UserDetails` a
cada requisição — a sessão continua "autenticada" com o username antigo, que
já não existe mais no banco, e `usuarioRepo.findByUsername` devolve vazio.
O `ResponseStatusException(UNAUTHORIZED)` cru é tratado pelo Spring
diretamente (`GlobalExceptionHandler.handleResponseStatus` só repropaga),
resultando num 401 técnico sem nenhuma chance de o usuário simplesmente
logar de novo — inclusive nos 6 endpoints `@ResponseBody`/JSON do chat
(operador↔solicitante e operador↔avaliador), onde o sintoma seria um erro
JS silencioso no polling em vez de qualquer mensagem visível.

Corrigido reaproveitando a MESMA infraestrutura já existente (nenhuma classe
nova): um método privado `resolverOperador(Principal)`, adicionado a
`ProcessoDetalheController`, lança `SessaoInvalidaException` em vez do
`ResponseStatusException` cru — tratada globalmente por
`GlobalExceptionHandler.handleSessaoInvalida` (invalida a sessão via
`SecurityContextLogoutHandler` e redireciona para
`/login?erro=sessao-invalida`), o mesmo tratamento que já existe desde a
correção no Portal do Avaliador. As 8 ocorrências (`enviarMensagem`,
`apagarMensagem`, `mensagensJson`, `enviarMensagemAjax`,
`apagarMensagemAjax`, `mensagensAvaliadorJson`,
`enviarMensagemAvaliadorAjax`, `apagarMensagemAvaliadorAjax`) foram trocadas
para chamar `resolverOperador(principal)`. As demais
`ResponseStatusException` do controller (`NOT_FOUND` para avaliador
inexistente) não foram tocadas — são erros de negócio genuínos, não sessão
órfã.

Teste de regressão (`ProcessoDetalheSessaoOrfaIntegrationTest`, seguindo o
modelo de `AvaliadorSessaoOrfaIntegrationTest` já documentado acima): sessão
HTTP real via `POST /login` (não `@WithMockUser` — o bug é sobre o estado da
`HttpSession` entre duas requisições, que `@WithMockUser` recria do zero a
cada teste), renomeia o `username` no banco por baixo da sessão ativa, e
confirma que `POST /processos/{id}/mensagem/{id}/apagar/ajax` com essa MESMA
sessão devolve `302` para `/login?erro=sessao-invalida` (nunca 401/500 cru)
e que a sessão fica `isInvalid()==true` depois — prova que foi de fato
invalidada, não só ignorada.

**2. N+1 no card "Respostas dos Avaliadores": até 6 queries extras por
render viraram 2.** `ProcessoDetalheController.detalhe` chamava, dentro de
um loop sobre os 3 pareceres do processo (`ProcessoService
.AVALIADORES_POR_PROCESSO`, regra fixa), `mensagemAvaliadorService
.contarNaoLidasPorThreadParaOperador(...)` e `mensagemAvaliadorService
.existeConversa(...)` — até 2 SELECTs por avaliador, 6 no total, toda vez
que a tela de detalhe é aberta. Baixa prioridade (N é sempre 3, não escala),
mas corrigido no mesmo arquivo por já estar sob vistoria.

Solução: `MensagemAvaliadorRepository` ganhou 2 queries em lote, ambas
escopadas por `processoId` (não por parecer individual):
`contarNaoLidasPorMembroAgrupado` (`SELECT membro.id, COUNT(m) ... GROUP BY
membro.id`, filtrada por `remetente=AVALIADOR` e `lida=false`) e
`membrosComConversa` (`SELECT DISTINCT membro.id ...`, qualquer mensagem,
lida ou não). `MensagemAvaliadorService.resumoConversasDoProcesso(processoId)`
(novo, `@Transactional(readOnly = true)`) roda as duas e monta um record
`ResumoConversasProcesso(Map<Long, Long> naoLidasPorMembro, Map<Long,
Boolean> existeConversaPorMembro)`, **chaveado por `membro.id`** — essa
camada de serviço não conhece `Parecer`. O controller chama esse método UMA
vez antes do loop e remapeia para a chave que o template espera
(`parecer.id`) dentro do mesmo loop, sem nenhuma consulta nova ali.

**Achado ao rodar a suíte completa (não um bug de produção):**
`ProcessoDetalheControllerTest` (`@WebMvcTest` com `@MockitoBean
MensagemAvaliadorService`) quebrou com `NullPointerException` em 13 testes
— o Mockito devolve `null` por padrão para um método que retorna um record,
e `resumoConversas.naoLidasPorMembro()` estourava NPE antes mesmo de
qualquer asserção rodar. Corrigido com um stub default no `@BeforeEach`
(`when(mensagemAvaliadorService.resumoConversasDoProcesso(anyLong()))
.thenReturn(new ResumoConversasProcesso(Map.of(), Map.of()))`), mesmo padrão
já usado para os demais mocks "default seguro" da classe.

Teste de integração dedicado (`MensagemAvaliadorResumoConversasBatchTest`,
H2 real, sem mock) confere que o resultado da versão em lote é idêntico ao
que os métodos antigos (`contarNaoLidasPorThreadParaOperador`/
`existeConversa`, chamados um a um) devolveriam, em 4 cenários: membro com
não lidas, membro só com mensagem já lida (existe conversa, mas zero não
lidas), membro sem nenhuma mensagem (ausente dos dois mapas — o chamador
trata como 0/`false`) e processo inteiro sem nenhuma mensagem (mapas
vazios). Os testes já existentes de `MensagemAvaliadorIntegrationTest`
(`telaDeDetalheDoProcessoNascecomThreadDoAvaliadorEXPANDIDAQuandoJaExisteConversa`)
continuam cobrindo a integração ponta a ponta pelo HTML renderizado.

**Validação:** suíte completa — **881 testes, 0 falhas, 0 erros** (JDK 21,
`mvn clean test`).

**Nota de processo desta sessão:** o repositório teve outra sessão/agente
trabalhando concorrentemente na mesma working copy durante parte desta
tarefa (evidenciado por branches e stashes alheios, ex. `stash@{5}:
wip-ProcessoDetalheController-sessao-orfa-nao-relacionado`), causando
reversões intermitentes de edições em progresso. Resolvido criando uma
branch nova e isolada a partir de `origin/main`
(`fix/sessao-orfa-processodetalhe-e-nplus1-mensagens`) e commitando cada
arquivo assim que confirmado correto, em vez de acumular edições
não-commitadas por muito tempo na mesma working copy compartilhada.

## Redesign visual do Portal do Solicitante (V1–V6) — registro atrasado + reparo final (2026-08-08)

**Leia isto antes de abrir `docs/RELATORIO-REDESIGN-VISUAL-SOLICITANTE-2026-08.md`:
aquele relatório JÁ FOI IMPLEMENTADO por inteiro.** Da §1 à §7 ele descreve o
estado **anterior**, não o código de hoje.

### O que aconteceu (e por que este registro chega atrasado)

O relatório (2026-08-06) respondeu a uma queixa textual do dono do produto —
*"o visual é tão simples e feio"* — sobre o Portal do Solicitante. Diagnosticou
seis causas verificáveis (escala tipográfica de 2 degraus, cor institucional
ausente do conteúdo, dourado semanticamente ocupado por "atenção", um único
nível de sombra para 9 cartões, escala de espaçamento criada e não usada,
estados vazios sem ação), propôs seis movimentos (M1–M6) e um plano faseado
V1–V6, com mockups em `docs/mockups/`.

As 7 decisões de produto do §10 foram **aprovadas explicitamente** pelo dono do
produto no mesmo dia (decisão 1 → opção A; decisão 2 → sem elemento
comemorativo; decisão 3 → só bootstrap-icons; e um *"suas recomendações são
autorizadas"* que fechou as decisões 4–7 na opção recomendada de cada uma). As
**seis fases foram implementadas e mescladas em `main` pelo PR #42**
(`feat/redesign-visual-solicitante`), merge em 2026-08-06 14:40 UTC, um commit
por fase (`58ca923` V1 → `7583829` V6).

**O que falhou foi só o registro:** o CLAUDE.md nunca citou o relatório, e a
nota do §10 continuou dizendo que a implementação seria *"um passo separado, a
ser retomado quando solicitado"* — verdadeiro quando escrito, falso poucas
horas depois. Resultado: uma vistoria de 2026-08-08 concluiu que o relatório
*"nunca foi implementado e está esfriando"* e uma sessão foi aberta para
implementá-lo de novo. **Nada foi reimplementado** — a sessão verificou o
código real (os 12 tokens do Anexo B e as 10 classes previstas estão todos no
`app.css`; as 4 telas consomem todos eles) e corrigiu a documentação.

### O que está no código hoje (V1–V6, todas em produção)

- **V1** — tokens: `--saur-elev-0/1/2`, `--saur-font-2xl`, e os pares
  `--saur-surface-{ok,danger,attention,info}` / `--saur-on-*`. Nenhum matiz
  novo: cada um aponta para um `--rs-*` que já existia.
- **V2** — `.estado-vazio`/`.estado-vazio-icone` nos 4 pontos que não tinham
  tratamento; a lista vazia do primeiro acesso ganhou o botão primário
  "Enviar minha primeira solicitação".
- **V3** — `.pagina-cabecalho` (faixa de identidade que emenda na navbar) +
  escala tipográfica real: `.pagina-titulo`, `.secao-titulo` (1,25rem, cor de
  texto real — não mais `h6 text-muted` de 1rem), `.secao-rotulo`. A marca
  Gota+Cruz passou a existir dentro do conteúdo, como marca d'água
  (`.pc-marca`, 6% na variante suave, 10% na sólida).
- **V4** — `.cartao-resultado`: superfície tintada em largura total no lugar
  da borda esquerda de 4px, ícone em token circular, `.chip-protocolo` com o
  número do processo. **Simetria estrutural com assimetria cromática**
  (decisão 2): Deferido e Indeferido usam exatamente o mesmo layout, mudando
  só cor e ícone — sem nenhum elemento comemorativo.
- **V5** — faixa de resumo recalibrada: "Aguardando triagem" e "Em análise"
  deixaram de ser âmbar (viraram azul informativo), porque não exigem ação
  nenhuma do solicitante. Só "Devolvidas" continua âmbar — o dourado voltou a
  significar "você precisa agir".
- **V6** — `nova.html` com ritmo visual real no lugar dos quatro `<hr>`, e a
  zona de upload deixando de parecer mais um campo de texto.

**Contraste:** em superfície tintada o texto usa **sempre** a variante `-dark`
(Anexo C do relatório: `--rs-gold` sobre `--rs-gold-light` dá 1,85:1 e
`--rs-green` sobre `--rs-green-light` dá 4,11:1 — os dois reprovam em WCAG AA).
Não trocar por conta própria a variante base numa superfície clara.

### Reparo desta sessão (o único item que faltava)

`solicitante/indisponivel.html` usava `bi-tools` (ferramentas), que comunica
"sistema quebrado / em manutenção", quando a mensagem real da tela é "seu
perfil ainda não foi habilitado, continue usando o e-mail de sempre". Trocado
por `bi-envelope-paper` (§5.4 do relatório, o reparo de 1 linha que tinha
ficado de fora do PR #42). Ícone conferido contra o webjar antes da troca —
`IconesBootstrapTest` reprova ícone inexistente.

### Guarda automatizado novo: `RedesignVisualSolicitanteIT`

A §11 do relatório é explícita: *"as asserções da suíte e o E2E podem ficar
inteiramente verdes com o Portal visualmente quebrado"* — os testes normais
verificam status HTTP, model attributes e presença de texto/id, **nunca** cor,
tamanho, espaçamento ou sombra. O redesign inteiro entrou em produção sem
nenhum guarda: bastava trocar `.secao-titulo` de volta por `h6 text-muted`
para o Portal voltar ao estado que gerou a queixa, sem uma única falha.

`src/test/java/br/gov/saude/sgpur/e2e/RedesignVisualSolicitanteIT.java`
(Playwright, profile `e2e`, mesmo padrão de `ChatVisualVerificacaoIT`) mede o
**CSS computado pelo navegador**, não o texto do template, e cada asserção
mira um achado nominal do diagnóstico: título de seção estritamente **maior**
que o corpo de texto (§4.1), faixa de cabeçalho com fundo que de fato pinta
nas duas variantes (§4.2), estado vazio com ícone e ação (§4.6), e a marca
dentro do conteúdo (§4.7). Gera screenshots em `target/e2e-screenshots/`
também em viewport de celular — **não substitui** a revisão visual humana que
o §11 exige, só garante que ela tenha o que olhar e que o básico não regrediu.

### O que continua PENDENTE DE DECISÃO do dono do produto

Nada disto foi implementado, e nenhum é bug — são caminhos que o próprio
relatório deixou explicitamente em aberto:

1. **Estender o redesign ao Portal do Avaliador** (decisão 6). Foi aprovada a
   opção A: aplicar **só** ao Solicitante e *"avaliar o Avaliador depois, com
   o aprendizado"*. Esse "depois" nunca foi perguntado. As classes novas são
   reaproveitáveis; o custo é dobrar a superfície de revisão visual.
2. **Dourado como cor de marca** (decisão 1, opção B). Aprovada a opção A (o
   dourado continua sendo "atenção"). A opção B exigiria uma cor nova na
   paleta e revisão de **todos** os `alert-warning`/badge âmbar do sistema,
   inclusive das telas do operador — é um projeto de identidade visual
   próprio, não um redesenho de portal.
3. **Ilustrações SVG próprias derivadas do logo** (decisão 3, opção b —
   gota+relógio para "aguardando", gota+check para "deferido"). Aprovada a
   opção (a): só bootstrap-icons ampliados em tokens circulares. A (b) exige
   alguém com traquejo de ilustração e tem risco real de ficar amador.
4. **Estender à área do operador** (decisão 6, opção C) — o relatório
   **desaconselha**: ela acabou de passar por 5 fases de UI e usa densidade
   `operacional`, com objetivos opostos aos do Portal.

### Lição de processo (recaída conhecida)

Esta é a **terceira** vez que uma vistoria conclui algo errado por causa de
texto desatualizado num documento — as duas anteriores estão registradas em
"Vistoria de 2026-07-31" (enums removidos ainda descritos como "legado, só
leitura") e no header do relatório V2 do Relatório Final. **Ao terminar de
implementar um relatório faseado, atualizar o header do próprio relatório e
citar o resultado no CLAUDE.md faz parte da tarefa** — sem isso o trabalho
some da memória do projeto e alguém propõe refazê-lo.

## Actuator com /actuator/health público + acentuação no Portal do Avaliador (2026-08-08)

Duas melhorias independentes, mesmo PR, branch `feat/actuator-health-e-
acentuacao-avaliador` a partir de `main`.

### 1. Acentuação faltando no `title` dinâmico de `avaliador/votar.html`

`th:title="${'Visualizacao do processo anonimizado: ' + pdf.nomeArquivo}"`
(atributo `title` do `<iframe>` que mostra o PDF anonimizado) estava sem
acento — corrigido para "Visualização". Esse é exatamente o tipo de string
que escapa de buscas de texto HTML plano: literal dentro de uma expressão
Thymeleaf concatenada (`${'texto' + var}`), não texto solto entre tags.
Varredura feita em todo `src/main/resources/templates/**/*.html` por esse
padrão específico (`th:title="${'...'`,  `th:attr=...='...'`, concatenações
`${'texto' + var}`/`${var + 'texto'}`) — esse era o único caso encontrado.

### 2. Spring Boot Actuator — só `/actuator/health`, público, sem detalhes

Adicionado `spring-boot-starter-actuator` ao `pom.xml`. Motivação: dar a um
health-check externo (script de deploy, monitoramento, um eventual balanceador)
um jeito padrão de perguntar "o app está de pé?" sem precisar autenticar e
sem depender de uma rota de negócio (`/login` sempre 200 mesmo com o banco
fora do ar, por exemplo, não serve como sinal).

- `management.endpoints.web.exposure.include=health` em `application.yml`
  (dev) **e** `application-prod.yml` (repetido explicitamente nos dois —
  não herdado por acidente, para nunca depender de alguém lembrar de manter
  os dois perfis em sincronia). **Nenhum outro endpoint** (`/actuator/env`,
  `/actuator/beans`, `/actuator/mappings` etc.) é exposto.
- `management.endpoint.health.show-details=never`: nem o único endpoint
  liberado mostra detalhes internos (ex.: a URL do datasource) — só o status
  agregado (`UP`/`DOWN`), suficiente para um health-check automatizado.
- `SecurityConfig` libera `/actuator/health` e `/actuator/health/**` como
  `permitAll()`, e adiciona `.requestMatchers("/actuator/**").hasRole("ADMIN")`
  como defesa em profundidade explícita para qualquer outro subpath de
  `/actuator/**`.
- **Comportamento real confirmado por teste manual (não presumido):** como
  o Spring Boot só registra os endpoints listados em
  `management.endpoints.web.exposure.include`, `/actuator/env`/`/actuator/beans`/
  `/actuator` (raiz) **não existem como bean nenhum** — mesmo autenticado
  como ADMIN via `POST /login` de verdade (sessão HTTP real, não
  `@WithMockUser`), a resposta é **404**, não "200 vazio" nem "403". Sem
  login, caem no `.hasRole("ADMIN")`/`anyRequest().authenticated()` e
  redirecionam 302 para `/login` antes mesmo de chegar a um controller —
  então a regra explícita do `SecurityConfig` é redundante com o 404 nativo
  do Boot, mas intencional: se um dia mais endpoints forem adicionados a
  `exposure.include` por engano, eles não ficam liberados por padrão, viram
  ADMIN-only em vez de público.
- **Achado ao testar de verdade (não presumido a partir da doc):** com
  `spring-boot-starter-mail` no classpath, o Actuator auto-registra um
  `MailHealthIndicator` que abre uma conexão SMTP real a cada consulta —
  em dev (sem `SGPUR_MAIL_USER`/`SGPUR_MAIL_PASS`, contra `smtp.gmail.com`)
  isso derrubava o health inteiro para `DOWN` só por causa do SMTP, mesmo
  com banco/disco saudáveis. Corrigido com
  `management.health.mail.enabled=false` (nos dois perfis) — `db` e
  `diskSpace` continuam ativos (default do Boot, não desligados). Um Gmail
  temporariamente instável não deve derrubar o sinal de "o app está de pé".

**Validação real, não só a suíte:** aplicação subida de verdade
(`mvn spring-boot:run`, H2, porta alternativa por já haver outra instância
rodando na 3000 nesta máquina) e testada com `curl`:
`GET /actuator/health` → `200 {"status":"UP"}` sem cookie/login nenhum;
`GET /actuator/env`, `/actuator/beans`, `/actuator` (raiz) → `302` para
`/login` sem sessão, e **`404`** com uma sessão ADMIN autenticada de
verdade (login real via `POST /login` com CSRF, não simulado).

**Suíte completa: 898 testes, 0 falhas** (JDK 21). A única falha vista numa
rodada isolada (`ComprovanteSntPendenteQueriesIntegrationTest
.registrarUltimoLembreteSntGravaOTimestampNoBanco`) é a flakiness de
precisão de timestamp do H2 já documentada em sessões anteriores deste
arquivo — reproduzida e confirmada passando ao rodar essa classe sozinha
logo em seguida, sem nenhuma mudança de código entre as duas rodadas.

**Nota operacional desta sessão:** o checkout local (`c:\Users\rafae\
projetos\urgencia`) estava sendo usado concorrentemente por outras sessões/
agentes automatizados no momento desta tarefa (branches `worktree-agent-*`,
trocas de branch e `git stash` de trabalho alheio acontecendo no meio da
implementação). O trabalho desta tarefa foi isolado num `git worktree`
próprio (`c:\Users\rafae\projetos\urgencia-actuator-wt`, mesma branch
`feat/actuator-health-e-acentuacao-avaliador`) para não sofrer interferência
— toda a validação (compilação, suíte, `mvn spring-boot:run` + `curl`) foi
feita nesse worktree isolado, não no checkout principal compartilhado.

## Status do relatório UI-Clareza-Operador (2026-08-05) — verificado em 2026-08-08

O `docs/RELATORIO-UI-CLAREZA-OPERADOR-2026-08.md` (diagnóstico + plano sobre
poluição visual de `processos/detalhe.html` e telas correlatas, gatilho:
feedback do dono do produto sobre a aba Envio) nunca tinha ganhado um
parágrafo de status neste arquivo, ao contrário de todos os outros
relatórios de diagnóstico do projeto. Esta seção fecha essa lacuna, com
verificação linha a linha do relatório contra o código real (não presumido).

**Achado principal da vistoria: quase todo o plano (FASES 1 a 8) já estava
implementado antes desta sessão**, espalhado por commits/PRs anteriores que
não tinham sido registrados de volta no relatório nem citados explicitamente
aqui como "isto resolve o RELATORIO-UI-CLAREZA". Confirmado lendo o código
atual de `processos/detalhe.html`, `dashboard.html`, `processos/lista.html`,
`processos/solicitacoes-online-lista.html`,
`processos/solicitacoes-online-detalhe.html`, `controle-urgencias/lista.html`,
`usuarios/form.html`, `FluxoProcessoService`, `service/dto/EtapaFluxo.java`:

- **FASE 1 (verdade da tela)** — §4.4 (texto obsoleto de "anexo
  comprobatório" na Decisão), §4.5 ("registre as datas" na Finalização),
  §4.6 ("aba 1. Envio"/"lançar as respostas"), §4.9 (duplicação no
  Recebimento — hoje nem existe mais como aba própria, fundida em Envio em
  2026-08-05), §4.13a (acentuação residual do template) e §5.2 (manual
  permanente na triagem): **todos corrigidos**. Confirmado por grep: nenhuma
  ocorrência de `comprobat`/`registre as datas`/`aba <strong>1. Envio` em
  `src/main/resources/templates`, e nenhum resíduo de
  `concluido|concluida|concluidas|Avancar|solicitacao|decisao|oficio|
  informacao|analise` em texto visível (fora de comentário/atributo) em
  **nenhum** template do projeto — não só em `detalhe.html`.
- **FASE 2 (hierarquia por classe CSS)** — §4.11 (e-mails prontos: "Copiar
  corpo" virou `btn-outline-secondary`, "Enviar agora por e-mail" virou
  `btn-danger` sólido) e a parte visual do §4.3 (botão "Registrar envio" em
  `btn-lg w-100` dentro de um `d-grid`): **já implementados**. **§4.10
  (atalhos: uma cor por significado) era o único item pendente desta fase**
  — corrigido nesta sessão, ver abaixo.
- **FASE 3 (`.subpasso` e conclusão visual, ⚑ raiz da queixa original)** —
  §4.1 (componente `.subpasso*` no `app.css`, cor por ESTADO em vez de
  posição) e §4.2 (reescrita do sub-passo 1 da aba Envio em
  instrução/regra/`<details>`/condicional): **implementados**. A aba Envio
  hoje usa `<section class="subpasso" th:classappend="... 'subpasso-ok' :
  'subpasso-atual'">` com `subpasso-num`/`subpasso-head`/`subpasso-regra`/
  `subpasso-ajuda` (`processos/detalhe.html`, em torno da linha 517).
- **FASE 4 (confirmação do envio aos avaliadores, ⚠E2E)** — §4.3: o form de
  `registrar-envio` já tem `data-confirm-msg` dinâmico com a lista de
  destinatários e a promessa exata do que o clique faz; `ProcessoDetalhePage`
  já clica em `#btnConfirmarAcaoFinal` nesse fluxo. **Implementado.**
- **FASE 5 (abas Respostas e Decisão)** — §4.7 (placar promovido para o topo
  do card "Respostas dos Avaliadores", com botão "Ir à Decisão" ao lado
  quando a maioria já se formou) e a parte estrutural do §4.4 (`<details>`
  "Quando usar o formulário abaixo" na aba Decisão): **implementados**. A
  confirmação de "Registrar decisão" (a sugestão opcional desta fase) também
  já existe (`data-confirm-msg` dinâmico conforme a opção do `<select>`,
  ver seção "Confirmação antes de Registrar decisão" acima neste arquivo,
  2026-08-05).
- **FASE 6 (aba Finalização, ⚠)** — §4.8 (`.subpasso` aplicado a
  Ofício/Comprovante/Resposta ao solicitante, com a pendência dita uma única
  vez — o segundo alerta virou `title` do botão desabilitado, exposto também
  como `<p class="subpasso-regra">` visível) e §4.12 (card "Todos os anexos"
  colapsado por padrão, com contador no cabeçalho): **implementados**. A aba
  Finalização caiu de 8 para 6 caixas de alerta (a meta do relatório era ≤4;
  os 2 alertas restantes são o "ofício/comprovante ainda não anexado" e o
  aviso informativo sobre o ofício ser sempre um anexo, não gerado —
  considerados aceitáveis por não serem duplicação de informação, só não
  perseguidos até o número exato porque essa aba é o "bloco mais delicado da
  tela" citado pelo próprio relatório, ⚠ risco médio de mexer mais).
- **FASE 7 (rótulo curto de pendência + acentuação vinda do Java, ⚠)** —
  §4.13b (`EtapaFluxo.Chave`, casamento por código em vez de string, com o
  `titulo` livre para ser acentuado) e §5.1 (`pendenciaAberta` devolvendo o
  `EtapaFluxo` inteiro, com o rótulo curto na célula e o detalhe completo no
  `title`): **implementados por completo**. `EtapaFluxo` já tem o enum
  `Chave` (`ENVIO, RESPOSTAS, INFO_COMPLEMENTAR, DECISAO, OFICIO,
  COMPROVANTE_SNT, RESPOSTA_SOLICITANTE`) com javadoc citando explicitamente
  o item 4.13b como motivo da separação; os títulos em
  `FluxoProcessoService.montarEtapas`/`montarPassosWizard` já saem
  acentuados ("Envio aos 3 médicos", "Decisão final", "4. Finalização"
  etc.); `dashboard.html` e `processos/lista.html` já exibem só
  `pendencias.get(id).titulo()` na célula com `title="... + detalhe()"`.
- **FASE 8 (higiene das demais telas)** — §5.3 (ações de
  `solicitacoes-online-detalhe.html` promovidas para o cabeçalho, "Devolver"
  como `btn-outline-secondary`), §5.4 (`controle-urgencias/lista.html`:
  decisão explícita — Editar/Cancelar só ícone, Renovar com texto por ser a
  ação mais frequente, com comentário no próprio template citando o item
  5.4) e §5.5 (JS de `usuarios/form.html` extraído para
  `static/js/usuario-form.js`): **todos implementados**.

**O que foi implementado NESTA sessão (2026-08-08):** só o item pendente da
FASE 2 — **§4.10, hierarquia de cor do card "Atalhos"**
(`processos/detalhe.html`, card lateral esquerdo). Antes, "Relatório Final
(PDF)" usava `btn-outline-danger` — a **mesma cor** de "Excluir processo",
treinando o olho a ignorar o vermelho onde ele de fato importa (ação
destrutiva) — e "Ofício"/"Comprovante SNT"/"ZIP"/"Editar processo" usavam
mais três cores diferentes sem critério (`warning`, `success`, `primary`).
Corrigido para `btn-outline-secondary` em todos os downloads/ações neutras
(Relatório Final, Ofício, Comprovante SNT, ZIP, Editar), deixando
`btn-outline-danger` exclusivo de "Excluir processo" — já separado por
`<hr>` desde antes. Só classes CSS; nenhum texto de botão, `id`, `action` ou
regra de negócio mudou (`ProcessoDetalhePage:180` localiza o botão por
`has-text('Relatório Final (PDF)')`, preservado). Suíte completa validada
após a mudança: **898 testes, 0 falhas reais** (1 falha vista era o flake de
precisão de nanossegundo já documentado em
`ComprovanteSntPendenteQueriesIntegrationTest`, reproduzido também isolado
antes desta sessão e confirmado não-relacionado — passa isoladamente).

**O que ficou pendente, e por quê** (nenhum mexido nesta sessão — todos são
risco médio/alto ou dependem de decisão de produto explícita, conforme o
próprio relatório já sinalizava com ⚠):
- **§4.8, meta exata de "≤4 alertas na Finalização"** não perseguida até o
  fim — os 6 alertas restantes (contra os 8 originais) não são duplicação;
  reduzir mais exigiria reestruturar o bloco mais sensível da tela (duas
  ações irreversíveis finais), fora do apetite de risco desta sessão de
  verificação.
- **§4.9 "fora de fase" (eliminar de vez a etapa Recebimento do wizard)** —
  já não é mais uma pendência: o Recebimento deixou de ter aba própria desde
  2026-08-05 (ver seção "Recebimento fundido em Envio" acima). O texto do
  relatório sobre isso é histórico.
- **"Fragmentar `processos/detalhe.html`"** (item 2 de "Fora de fase") —
  **decisão de produto explícita de NÃO fazer**, herdada do relatório
  anterior (`RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md`, §10) e reafirmada
  pelo próprio `RELATORIO-UI-CLAREZA-OPERADOR-2026-08.md`. Não implementado
  de propósito, não é uma pendência técnica.

**Conclusão:** o plano do relatório estava, na prática, **quase 100%
concluído** antes desta sessão de verificação — só não havia registro
consolidado disso em nenhum lugar. Esta seção supre esse registro; a única
mudança de código desta sessão foi o item 4.10.

## Fix: CSV/Formula Injection na exportação de auditoria (2026-08-08)

Achado de vistoria de segurança: `AuditoriaController.exportar` (CSV de
`/auditoria`, ver seção "Busca no banco..." acima para o contexto de que o
termo de busca nunca é logado) escapava `;`, `"` e quebra de linha via
`csvCampo(...)`, mas **não** neutralizava campos começando com `=`, `+`,
`-` ou `@` — o vetor clássico de **CSV/Formula Injection**: Excel/
LibreOffice interpretam esse tipo de campo como início de fórmula ao abrir
o CSV (ex. um `detalhe`/`usuario`/`ip` malicioso começando com
`=HYPERLINK(...)` podia disparar navegação/exfiltração de dado no
computador de quem abre a exportação).

**Correção (mitigação padrão OWASP):** `csvCampo` agora prefixa o valor com
um apóstrofo `'` quando ele começa com `=`, `+`, `-` ou `@`, **antes** do
escape de `;`/`"`/quebra de linha já existente — o apóstrofo faz o
Excel/LibreOffice exibir o valor como texto puro, sem interpretar fórmula.
Aplicado dentro do próprio `csvCampo`, então cobre as 4 colunas exportadas
(data/hora, usuário, ação, detalhe, IP) sem duplicar a lógica.

Coberto por `AuditoriaControllerTest`: um caso por caractere perigoso
(`=`, `+`, `-`, `@`) no campo `detalhe`, um caso combinando `usuario`+`ip`
maliciosos na mesma linha, e um caso de regressão confirmando que um valor
normal (sem esses caracteres no início) **não** ganha o apóstrofo extra —
não queremos poluir todo campo exportado.

**PR:** `fix/csv-formula-injection-auditoria` (branch dedicada a partir de
`main`, sem outra mudança de regra de negócio).

## Redesign visual estendido ao Portal do Avaliador (2026-08-08)

**Decisão de produto aprovada explicitamente pelo dono do produto** nesta
sessão: estender ao Portal do Avaliador (`/avaliador`) o sistema de design
criado nas fases V1–V6 do
`docs/RELATORIO-REDESIGN-VISUAL-SOLICITANTE-2026-08.md` (PR #42). Isso
**reabre, na opção B, a decisão 6 do §10 daquele relatório** ("aplicar só ao
Solicitante agora; avaliar o Avaliador depois, com o aprendizado") — o
aprendizado veio e o dono do produto autorizou a extensão. Três limites
fixados junto com a aprovação, que **não devem ser reabertos**:

- **Não** trazer o dourado de volta como cor de marca (decisão 1 do §10
  segue na opção A: dourado = "atenção").
- **Não** criar ilustrações SVG próprias derivadas do logo (decisão 3 segue
  na opção (a): só bootstrap-icons em token circular).
- **Só o Portal do Avaliador.** A área do operador (ADMIN/OPERADOR,
  densidade `operacional`) continua **fora** — decisão 6, opção C, segue
  não recomendada e não foi tocada.

**Mudança puramente visual.** Nenhum controller, endpoint, DTO, regra de
negócio, `name=`/`id` de campo ou consulta foi alterado. A imparcialidade
segue intacta: as telas continuam expondo **só as iniciais** do paciente e
**nenhuma informação nova** (o `.chip-protocolo` da faixa de `votar.html`
carrega apenas o número CET-RS, que a tela já mostrava num badge cinza).

### O que foi aplicado (reaproveitando as classes que já existiam)

Nenhuma classe nova de componente foi criada — todas vêm do bloco de
redesign do `app.css` (`.pagina-cabecalho`, `.pagina-cabecalho-solida`,
`.pagina-titulo`, `.pagina-contexto`, `.chip-protocolo`, `.secao-titulo`,
`.cartao-resultado` + `.r-*`, `.estado-vazio` + `.estado-vazio-compacto`,
`.superficie-apoio`, `.elev-2`, `.pc-marca`).

`avaliador/lista.html`:
- Faixa de cabeçalho **suave** (M1(a)) com a marca d'água gota+cruz, no
  lugar do `<h1 class="h3">` solto sobre o cinza; conteúdo passou a viver
  num `container my-4 container-portal` dentro do `<main id="conteudo">`.
- O `alert-warning` "Você tem N processos aguardando o seu voto" virou
  **`.cartao-resultado.cartao-resultado-destaque.r-attention`** (M4(i)+M6) —
  ícone em token circular de 64px, título 1,75rem e CTA `btn-lg`. É o
  **único elev-2 da tela** (regra do M3): no Portal do Avaliador a pergunta
  que o médico veio responder é "tenho algo para votar?". O caso sem
  pendência usa o mesmo layout em `.r-ok` ("simetria estrutural com
  assimetria cromática", decisão 2).
- Títulos de seção `h2.h5` → `.secao-titulo` (1,25rem, cor de texto real).
- Os 3 estados vazios (`text-muted py-5` com ícone solto) → `.estado-vazio`
  / `.estado-vazio-compacto`.
- **Redundância removida:** a seção "Pendentes de voto" inteira (título +
  lista + aviso de imparcialidade, que descreve essa lista) só é renderizada
  quando há pendência — o cartão verde acima já dizia exatamente a mesma
  coisa, e a tela exibia a informação duas vezes seguidas com dois desenhos
  diferentes. Achado na **captura de tela**, não no código.

`avaliador/votar.html`:
- Faixa de cabeçalho **sólida** (M1(b), a mesma de `solicitante/detalhe.html`)
  fazendo o papel de "capa do processo": link de volta, título e o número
  CET-RS promovido de badge cinza para `.chip-protocolo`; a posição na fila
  de pendentes virou `.pagina-contexto`.
- Cartão de identificação (dl de Processo/Paciente/Enviado em) → 
  `.superficie-apoio` (elev-0, fundo afundado): deixa de ser mais um cartão
  branco idêntico.
- Cartão do formulário de voto ganhou `.elev-2` — **único** da tela; o card
  do PDF continua em elevação padrão (é leitura de apoio).
- Estado vazio do chat → `.estado-vazio-compacto`. O `id="chatVazioAval"`
  não mudou: `chat-solicitacao.js` só alterna `d-none` nele, nunca reescreve
  o conteúdo interno.

`app.css`: **única adição** — `.btn-resultado-attention` (fundo
`--rs-gold-dark`), completando a família `.btn-resultado-ok/-danger` já
existente. `btn-warning` do Bootstrap (fundo `--rs-gold` puro) sobre
`--saur-surface-attention` some no próprio cartão; a variante `-dark` é a
mesma regra de contraste do Anexo C do relatório.

### Validação visual (obrigatória — a suíte não reprova nada disto)

Conforme o §11 do relatório, as telas foram **renderizadas de verdade** com
Playwright contra a aplicação real (H2/dev, fluxo completo: solicitação →
triagem → conversão → envio → login do avaliador) e **inspecionadas**:
`/avaliador` com pendência, `/avaliador` sem nenhuma pendência e
`/avaliador/{id}` — cada uma em desktop (1440px) e celular (390px). Dois
ajustes saíram dessa inspeção (a redundância da seção de pendentes e a
calibragem do botão âmbar), nenhum deles visível na leitura do código. O IT
de captura foi temporário e **não** ficou no repositório.

### Testes

- Suíte completa: **908 testes, 0 falhas** (JDK 21).
- `FluxoCompletoProcessoIT` (E2E) percorre todo o Portal do Avaliador
  alterado — PDF inline, modal de confirmação, voto real dos 2 médicos,
  decisão automática — e falha só na linha 228, a **pré-existente e já
  documentada** de SMTP local ausente.
- **Bug pré-existente corrigido de graça:**
  `AvaliadorPage.materialInline()` (E2E) procurava
  `iframe[title^='Visualizacao do processo anonimizado']` **sem acento**,
  enquanto o template usa "Visualização" desde a Fase 8 de acentuação — o
  seletor casava **zero** elementos e `.first().isVisible()` devolvia
  `false` em silêncio (assert falso, sem indicar que o seletor é que estava
  errado). Confirmado por `git stash` que a falha existia igual no `main`
  sem nenhuma mudança desta sessão.

## Sessão de consolidação de 2026-08-08: E2E ligado no CI + validação visual manual dos 2 Portais

Sessão pedida explicitamente para PARAR de abrir frentes novas e consolidar,
depois de uma vistoria apontar "fadiga de qualidade" (muitos PRs mesclados
rápido, com o E2E (Playwright) nunca tendo rodado de fato no GitHub Actions
— só a suíte rápida `mvn test`).

### Parte 1 — E2E ligado no CI (`.github/workflows/ci.yml`)

Novo job `e2e`, `needs: build` (só roda depois que a suíte rápida passa no
job existente), instalando o Chromium do Playwright
(`com.microsoft.playwright.CLI install --with-deps chromium`, via o
classpath resolvido por `mvn dependency:build-classpath`) e rodando
`mvn verify -Pe2e -Dsaur.e2e.headed=false -Dmaven.test.failure.ignore=true`.
Publica `target/e2e-screenshots/` e `target/failsafe-reports/` como
artifacts (`if: always()`), para debug de falha futura sem precisar
reproduzir localmente.

**Decisão sobre não bloquear o Deploy:** o job `e2e` tem
`continue-on-error: true`. Confirmado rodando `mvn verify -Pe2e
-Dsaur.e2e.headed=false` nesta máquina, sem `SGPUR_MAIL_USER`/
`SGPUR_MAIL_FROM`: `FluxoCompletoProcessoIT` (caminho INDEFERIDO) sempre
avança até a confirmação final da resposta ao solicitante e falha só ali,
com `EmailSender: remetente (from) nao configurado` — exatamente a
limitação já documentada no CLAUDE.md para quem roda o E2E localmente sem
essas env vars, não uma regressão. Como `deploy.yml` dispara via
`workflow_run` olhando só a conclusão do workflow `CI`
(`github.event.workflow_run.conclusion == 'success'`) e não depende de
nenhum job específico, um job com `continue-on-error: true` que falha não
derruba a conclusão do workflow — o Deploy continua liberado normalmente
mesmo com essa falha conhecida. Avaliado configurar um SMTP fake
(mailhog/smtp4dev) como `services:` do job para o teste passar de verdade —
descartado por ora: exigiria uma propriedade de teste separada só para o
perfil de CI (o projeto não tem isso hoje) e o ganho não parecia valer o
tempo extra nesta rodada; fica como melhoria futura se algum dia o SMTP
virar algo que o E2E realmente precisa validar em CI.

**`-Dmaven.test.failure.ignore=true`, não `-Dsurefire.skip=true`:** tentativa
inicial de pular a suíte rápida no job `e2e` (já validada no job `build`)
usando `-Dsurefire.skip=true` **não funcionou** — o Maven Surefire Plugin
não reconhece essa property (ela não existe; a que existe é `skipTests`,
que também pausaria o Failsafe, já que os dois plugins escutam a mesma
flag). `-Dmaven.test.failure.ignore=true` foi o que funcionou: deixa a
suíte rápida rodar (redundante com o job `build`, mas inofensivo) e, mesmo
que ela tenha uma falha isolada (ex.: o flake de precisão de nanossegundo
de `ComprovanteSntPendenteQueriesIntegrationTest`/
`LembreteAvaliadorTimestampIntegrationTest`, já documentado acima), o
Maven registra e segue para o Failsafe em vez de abortar antes — sem isso,
um dia flaky na suíte rápida faria o Playwright nunca ser exercitado nesse
job.

**Corrupção de build local encontrada e não relacionada ao código:** editar
um arquivo de teste (`.java`) enquanto um `mvn verify` estava compilando em
segundo plano deixou `target/classes`/`target/test-classes` num estado
inconsistente (`NoSuchBeanDefinitionException` em cascata, "quase toda
classe falha"). Não é um bug do projeto — é risco de editar fonte durante
um build ativo. `mvn clean` resolveu de imediato. Registrado aqui só para
quem for depurar uma falha em massa parecida no futuro não perder tempo
achando que é regressão real.

### Parte 2 — Validação visual manual dos 2 Portais (novo teste, `PortaisVisualCompletoIT`)

Novo teste `src/test/java/br/gov/saude/sgpur/e2e/PortaisVisualCompletoIT.java`
(Failsafe, mesmo padrão de `RedesignVisualSolicitanteIT`/
`ChatVisualVerificacaoIT`): percorre os dois processos completos (DEFERIDO
via 2 avaliadores FAVORÁVEL, INDEFERIDO via 2 avaliadores NÃO FAVORÁVEL,
voto real no Portal do Avaliador) e gera 20 screenshots em
`target/e2e-screenshots/` — Portal do Solicitante (lista vazia, nova
solicitação, lista em andamento, detalhe em andamento, detalhe DEFERIDO,
detalhe INDEFERIDO) e Portal do Avaliador (lista com pendência, tela de
votar, lista sem pendência), cada um em desktop (1440x900) **e** celular
(390x844). Diferente dos testes visuais anteriores (que citavam a inspeção
visual humana como pendência separada), esta sessão **gerou os 20
screenshots e cada um foi de fato lido/inspecionado** (ferramenta de
leitura de imagem, não só o texto do HTML) antes de considerar a validação
concluída.

**Resultado da inspeção: nenhum defeito visual encontrado.** As 20 telas
(dois portais × dois estados de decisão que ainda não tinham guarda
visual — DEFERIDO/INDEFERIDO, ver `RedesignVisualSolicitanteIT` que só
cobria "em andamento" — × duas resoluções) renderizam corretamente: sem
elemento sobreposto, sem texto cortado, sem quebra de layout em mobile, bom
contraste. Nenhuma correção de CSS/template foi necessária nesta sessão.

**Dois achados reais durante a construção do teste, ambos documentados no
código para não se repetir:**

1. **Coordenador CET-RS como um dos votantes quebra um cenário de teste que
   assume 2 votos sempre necessários.** `membroRepository.
   findByAtivoTrueOrderByInstituicaoAsc()` sempre devolve o Coordenador CET-RS
   primeiro (`MembroDevSeed`, "CET-RS" vem primeiro em ordem alfabética de
   instituição) — um voto FAVORÁVEL dele sozinho já defere o processo (regra
   de negócio real). Um teste que usa `medicos.get(0)` e `medicos.get(1)`
   como os "2 votantes" do caminho DEFERIDO estava, sem perceber, fazendo o
   coordenador votar primeiro: o processo já ficava decidido com 1 voto, e a
   segunda janela batia um 403 genuíno ("Acesso não permitido") tentando
   acessar `/avaliador/{id}` de um processo já finalizado — não um bug de
   timing. **Diagnosticado por engano inicialmente como problema de
   concorrência/contenção de recursos** (múltiplas janelas do Playwright
   abertas ao mesmo tempo), hipótese descartada só depois de imprimir o
   texto renderizado da página no momento da falha e ver a mensagem de
   permissão negada. Corrigido escolhendo deliberadamente os dois votantes
   do processo DEFERIDO como os dois membros que **não** são o coordenador;
   o coordenador só vota no processo INDEFERIDO, onde ele não tem peso
   especial (a regra do indeferimento sempre exige 2 votos, mesmo dele).
   Lição para qualquer novo teste E2E que monte um cenário de "2 votos
   necessários": **nunca presumir que o primeiro membro da lista é um
   membro comum** — conferir se é o coordenador antes de montar o roteiro
   de votos.
2. **`passoConcluido(4)` (Finalização) não foi assertado como `true`** para
   nenhum dos dois processos — a etapa só fica concluída com o e-mail final
   realmente enviado (`ProcessoService.finalizarResposta`), que falha nesta
   máquina local sem `SGPUR_MAIL_USER`/`SGPUR_MAIL_FROM` (mesma limitação já
   documentada para `FluxoCompletoProcessoIT`). A decisão (DEFERIDO/
   INDEFERIDO) e os anexos obrigatórios (comprovante SNT/ofício) já
   acontecem antes desse clique e não dependem do envio do e-mail — por
   isso os screenshots de detalhe DEFERIDO/INDEFERIDO do Portal do
   Solicitante continuam válidos mesmo com essa etapa final não confirmada
   localmente.

**Validação:** suíte completa **908 testes** — só as 2 falhas de flakiness
de timestamp já documentadas (nanossegundo, H2), confirmadas não
relacionadas por já existirem antes desta sessão. E2E completo (4 classes
`*IT`) via `mvn verify -Pe2e -Dsaur.e2e.headed=false
-Dmaven.test.failure.ignore=true`: `ChatVisualVerificacaoIT`,
`PortaisVisualCompletoIT` (novo) e `RedesignVisualSolicitanteIT` passam;
`FluxoCompletoProcessoIT` falha só na linha pré-existente e já documentada
de confirmação de e-mail (SMTP local ausente) — nenhuma regressão.

## Bug real corrigido: chevron `.chevron-collapse` nunca girava visualmente (2026-08-08)

Reportado pelo usuário em **produção**, em `/processos/15#respostas`: o
ícone de seta do botão "Conversa" (por avaliador, dentro do card "Respostas
dos Avaliadores") deveria girar 180° ao abrir/fechar o painel de chat e
nunca girava — ficava sempre apontando para cima, aberto ou fechado.

**Causa raiz real, confirmada no navegador (Playwright, screenshots reais
antes/depois de cada clique — não hipótese de leitura de código):** o
Bootstrap alternava a classe `.collapsed` no botão **corretamente**
(confirmado por `getComputedStyle` e pelo funcionamento normal do
collapse), e a regra CSS `[data-bs-toggle="collapse"].collapsed
.chevron-collapse { transform: rotate(180deg); }` também **era aplicada**
— `getComputedStyle(...).transform` já reportava a matriz de rotação
correta em cada estado. O problema é que `.chevron-collapse` é um `<i>`
(`display: inline` por padrão), e a especificação de CSS Transforms **não
aplica visualmente `transform` a elementos inline não substituídos** — o
navegador calculava e reportava a rotação (computed style), mas nunca a
pintava na tela. Confirmado comparando screenshots reais do botão nos
estados aberto/fechado (idênticos, sempre "^") e depois confirmando que
injetar `display: inline-block` no elemento fazia a rotação aparecer de
verdade ("v" ao fechar).

**Não era exclusivo do botão "Conversa" do avaliador** — os outros dois
lugares que usam a mesma classe `.chevron-collapse` (o card "Todos os
anexos" e o card "Conversa com o solicitante", ambos em
`processos/detalhe.html`, além de `avaliador/votar.html` e
`processos/solicitacoes-online-detalhe.html`/`solicitante/detalhe.html`)
tinham **exatamente o mesmo defeito visual**, reproduzido e confirmado por
screenshot antes da correção — só ninguém tinha reparado nos outros
lugares.

**Correção:** uma linha em `app.css`, na regra de base (não na regra de
rotação): `[data-bs-toggle="collapse"] .chevron-collapse { display:
inline-block; ... }`. Como é uma regra CSS compartilhada por todos os 4
templates que usam `.chevron-collapse`, um único ajuste corrige todos os
lugares de uma vez — nenhum template precisou mudar.

**Testado manualmente clicando várias vezes em múltiplos avaliadores na
mesma tela** (parecer que nasce com o painel fechado e parecer que nasce
com o painel já aberto, por já ter conversa registrada) — cada botão gira
de forma independente e correta, sem interferência entre eles. Suíte
completa: 888 testes, 0 falhas (JDK 21).

## Nome real do solicitante também no CABEÇALHO do chat, não só nas mensagens (2026-08-08)

Continuação do PR #61 (já mesclado), que trocou o rótulo genérico
"Solicitante" pelo nome real **dentro** das mensagens do chat (via
`MensagemSolicitacaoService.paraChat`, usado pelo endpoint AJAX de
polling). O usuário reportou que o **título do card** — o texto estático
"Conversa com o solicitante" no `card-header`, renderizado no HTML inicial
pelo Thymeleaf, fora do JS de polling — continuava genérico.

**Causa raiz:** `SolicitacaoOnlineService.nomeSolicitante(Long)` (já
existente, com fallback para o literal "Solicitante" se vier nulo/em
branco) só era chamado dentro do endpoint AJAX `mensagensJson`, nunca no
método de render principal (`detalhe()`/`ProcessoDetalheController` e
`SolicitacaoOnlineTriagemController`) que gera o HTML inicial da página —
por isso o `card-header` nunca teve acesso a esse dado.

**Correção:** os dois controllers passaram a adicionar
`model.addAttribute("nomeSolicitante", ...)` no método de render principal:
- `SolicitacaoOnlineTriagemController.detalhe` — sempre (o `id` da própria
  `SolicitacaoOnline` está sempre disponível; o card de chat nessa tela
  nunca teve `th:if` de guarda).
- `ProcessoDetalheController.detalhe` — só dentro do bloco
  `if (processoVeioDoPortal)`, quando `solicitacaoOrigemId` já foi
  resolvido (mesmo guard que os demais atributos de chat dessa tela usam;
  o card inteiro só renderiza com `th:if="${solicitacaoOnlineOrigemId !=
  null}"`, então nunca falta o atributo quando o `<span>` novo é avaliado).

Templates `processos/solicitacoes-online-detalhe.html` e
`processos/detalhe.html`: o texto fixo `Conversa com o solicitante` virou
`Conversa com <span th:text="${nomeSolicitante}">o solicitante</span>` —
mesmo ícone `bi-chat-dots`, sem mudar nenhum `id`/classe/estrutura do
resto do card.

**Não mexido, de propósito:** o rótulo das mensagens dentro do chat (já
correto desde o PR #61) e o toast de notificação (`"Nova mensagem recebida
do solicitante"`, deliberadamente genérico — usuário confirmou que está
OK). `solicitante/detalhe.html` (chat do lado do próprio solicitante,
título "Conversa com a equipe de Urgência Renal") também não foi tocado —
não é genérico, já nomeia a equipe corretamente.

Coberto por `ProcessoDetalheControllerTest.
detalheExpoeProcessoVeioDoPortalTrueELinkDaOrigem` e
`SolicitacaoOnlineTriagemControllerTest.detalheExibeASolicitacao`, ambos
ajustados para stubar `nomeSolicitante` e confirmar, via
`content().string(containsString(...))`, que o **HTML renderizado** (não
só o model attribute) mostra o nome real no cabeçalho.

## Cores dos Atalhos revertidas de novo pro esquema colorido (2026-08-08)

**Segunda reversão da mesma mudança** — pedido explícito do usuário ao ver
`/processos/{id}` em produção ("os atalhos possuíam cores, cada um tinha
uma cor e agora estão em cor [só]"). O card "Atalhos" (barra lateral
esquerda do detalhe do processo) tinha acabado de ganhar hierarquia neutra
(item 4.10 do relatório de clareza, commit `0be95ec`/PR #73, mesmo dia) —
todos os downloads/ações não-destrutivas viraram `btn-outline-secondary`,
deixando `btn-outline-danger` exclusivo de "Excluir processo". O usuário
não gostou do resultado visual e pediu pra voltar ao esquema anterior, uma
cor por botão:

| Botão | Cor (revertida) |
|---|---|
| Relatório Final (PDF) | `btn-outline-danger` |
| Ofício de Indeferimento | `btn-outline-warning` |
| Comprovante SNT | `btn-outline-warning` |
| Baixar processo completo (ZIP) | `btn-outline-success` |
| Editar processo | `btn-outline-primary` |
| Excluir processo | `btn-outline-danger` (inalterado) |

**Não é a primeira vez que essa mudança específica vai e volta** — o
histórico do repositório já tinha um revert anterior do mesmo tipo
(commit `b5a4935`, "Reverte cores dos atalhos do card lateral do processo
(pedido do usuário)"), bem antes do item 4.10 reintroduzir o esquema
neutro. **Esta é, portanto, a segunda vez que o usuário pede exatamente
essa reversão.** Registro explícito pra não reaplicar o esquema neutro de
novo numa vistoria futura sem pedido explícito — mesmo que o relatório de
clareza continue recomendando a versão neutra, a preferência confirmada do
usuário (duas vezes) é o esquema colorido.

Nenhum teste trava a cor desses botões (confirmado por grep antes da
mudança) — mudança só de classe CSS no template, sem impacto em
`id`/`name`/rota. `ProcessoDetalhePage` (E2E) localiza esse botão por texto
("Relatório Final (PDF)"), não por classe, então não foi afetado.

## Vistoria dos dois sistemas de chat (2026-08-10) — plano faseado F1-F6

`docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md` — vistoria completa dos dois
canais de mensagem em produção (Solicitante↔Operador, `MensagemSolicitacao`;
Avaliador↔Operador, `MensagemAvaliador`), com 15 achados (A1-A15) e um plano
de 6 fases executáveis (F1-F6, F7 adiada de propósito — dívida, não
defeito). Executando sequencialmente, cada fase seu próprio PR, squash
merge, base 908 testes.

### F1 — MESCLADA (S3 + S5 + S6 + S9, achados A3/A6/A7/A15)

Lote de correções de baixo risco, sem decisão de produto pendente:

- **S3 (achado A3):** `AvaliadorController.votar()` ganhou
  `model.addAttribute("chatAtivoNestaTela", true)` — a tela de voto já tem
  seu próprio poll de chat (5s); sem esse atributo, o poll GLOBAL de
  mensagens do avaliador (`layout.html`, 20s) também rodava ali, duplicando
  som/toast com textos diferentes, e o toast global levava o médico de
  volta para `/avaliador` no clique, descartando o formulário de voto em
  preenchimento. Mesmo contrato já usado em `SolicitanteController`/
  `SolicitacaoOnlineTriagemController`/`ProcessoDetalheController`.
- **S5 (achado A6):** o caminho de SUCESSO do soft delete não tinha
  NENHUM teste em nenhum dos 2 canais — as 6 asserções existentes só
  cobriam a recusa (mensagem de outro remetente). Exatamente a classe de
  bug que chegou a produção em 2026-07-28 (`texto NOT NULL` quebrando o
  soft delete) e passou pelos 526 testes da época. Testes novos, `@SpringBootTest`
  + H2 real (nunca mock, convenção do projeto para escrita irreversível):
  `MensagemAvaliadorIntegrationTest` (avaliador apaga a própria + operador
  apaga a própria, no canal do avaliador) e `MensagemSolicitacaoChatIntegrationTest`
  (novo arquivo — solicitante apaga a própria + operador apaga a própria
  via triagem, no canal do solicitante). Todos releem do banco e conferem
  `deletada=true`, `texto=null`, `deletadaEm != null`, `remetenteId`
  preservado e a contagem de linhas inalterada (a linha não some).
- **S6 (achado A7):** limite de 2000 caracteres passou a ser imposto no
  SERVIDOR nos 8 pontos de escrita (`enviar`, clássico e AJAX, dos dois
  canais) — antes só existia `maxlength="2000"` no HTML, trivialmente
  burlável via DevTools/curl (uma mensagem de 200 mil caracteres era aceita
  inteira, coluna `TEXT` sem erro). Constante `TEXTO_MAX_LENGTH = 2000`
  criada em `MensagemSolicitacaoService` e em `MensagemAvaliadorService`
  (uma em cada, de propósito — as duas entidades são deliberadamente
  separadas, ver "duplicação aceita" abaixo); cada controller valida
  explicitamente ANTES de chamar `enviar` (mesmo padrão já usado para
  "texto em branco"), devolvendo o mesmo formato de erro (flash nos
  endpoints clássicos, JSON `{"erro": "..."}` nos AJAX) que
  `chat-solicitacao.js` já sabe exibir via `mostrarToast`.
- **S9 (achado A15):** os 8 endpoints de apagar mensagem passaram a
  registrar auditoria — `MENSAGEM_APAGADA` (canal do solicitante) e
  `MENSAGEM_AVALIADOR_APAGADA` (canal do avaliador), só com id da
  mensagem/processo/solicitação + quem apagou, **nunca o texto nem o nome
  completo do paciente** (mesma regra já endurecida em 2026-07-28 e
  2026-08-03 para outros vazamentos do mesmo tipo). Antes, apagar mensagem
  não deixava nenhum rastro em `/auditoria` — só o enviar era auditado.

Suíte completa validada: **918 testes, 0 falhas** (908 + 10 novos, JDK 21).

### F3 — MESCLADA (S4, calibragem do `VerificadorNomePaciente`, achados A4/A5)

**Decisão de produto confirmada pelo usuário (Q3 do relatório): seguir a
recomendação do relatório na íntegra.** Implementados os 3 itens do S4:

1. **Nome curto (achado A4):** o corte de tamanho mínimo de token do nome do
   paciente caiu de 4 para 3 caracteres (`tokensSignificativosNome`). Um
   nome inteiro "curto" (≤2 tokens significativos no total —
   `NOME_CURTO_MAX_TOKENS`) passou a BLOQUEAR já com **1 único** token
   encontrado (antes gerava só `ALERTA`, e no caso mais extremo — nome de
   3 letras por token, ex. "Ana Luz" — nenhum token sequer era gerado,
   então a mensagem passava **livre**). Nomes "normais" (>2 tokens
   significativos) continuam com a regra antiga: 1 token = `ALERTA`, 2+ =
   `BLOQUEADO`.
2. **Equipe (achado A5):** `BLOQUEIO_EQUIPE_MIN_TOKENS = 2` — bloquear por
   equipe agora exige **2 tokens distintos** da equipe solicitante
   encontrados na mensagem (antes, 1 token isolado já bloqueava). Corrige
   os dois falsos-positivos reproduzidos no relatório: "o exame de
   *clinicas* não abriu" e "...desconsiderar o *alegre*" (vocabulário
   clínico corrente e metade do nome de uma cidade, nenhum dos dois
   identifica a equipe sozinho). Exceção simétrica à do nome: equipe
   **curta** (≤1 token significativo no total — sigla sem espaços, ex.
   "HNSC") continua bloqueando com 1 único token
   (`BLOQUEIO_EQUIPE_MIN_TOKENS_TOTAL_CURTA`), senão essa equipe ficaria
   estruturalmente impossível de detectar.
   **`STOPWORDS_EQUIPE` ganhou termos genéricos**: `santa`, `casa`,
   `geral`, `universitario`, `federal`, `municipal`, `estadual`,
   `nefrologia`, `transplante`.
   **Desvio deliberado da lista literal sugerida pelo relatório — não
   inclui `clinicas`/`porto`/`alegre`.** Achado durante a implementação,
   não previsto pelo relatório original: para a equipe "Hospital de
   Clínicas de Porto Alegre" (HCPA — a instituição mais citada como
   exemplo em todo o código/CLAUDE.md), essas 3 palavras são os **únicos**
   tokens significativos que sobram depois de excluir "hospital"/"de" (já
   stopwords) — excluí-las também tornaria essa equipe **estruturalmente
   impossível de detectar** por este mecanismo, mesmo que uma mensagem
   citasse o nome inteiro da instituição por extenso. Seria uma regressão
   de proteção pior que o falso-positivo que o achado A5 queria corrigir.
   A exigência de 2 tokens sozinha já resolve os dois casos concretos do
   achado A5, sem essa exclusão adicional — documentado em javadoc extenso
   em `VerificadorNomePaciente.STOPWORDS_EQUIPE`. **Sinalizado aqui para
   revisão do usuário** (não bloqueou o merge: é uma mudança estritamente
   mais protetora que a alternativa sugerida, não uma redução de proteção
   em relação ao estado anterior a esta calibragem).
3. **Mensagem de erro simplificada:** `ProcessoDetalheController` parou de
   citar o(s) termo(s) encontrado(s) na resposta 400 — "ensinava" ao
   operador exatamente qual palavra evitar da próxima vez, sem nenhum
   benefício real (o operador já sabe o nome do paciente/equipe do
   processo que está editando).

`VerificadorNomePacienteTest` ampliado com os casos de borda do relatório:
nome inteiro curto com 1 e com 2 tokens citados, 2 de 3 tokens num nome
"normal" (comportamento pré-existente confirmado inalterado), token
genérico de equipe isolado, topônimo isolado, token 100% stoplistado,
equipe curta de 1 token, acento/maiúscula. Dois testes pré-existentes
(`VerificadorNomePacienteTest.equipeSolicitanteCitadaEBloqueada` e
`MensagemAvaliadorIntegrationTest.operadorNaoConsegueEnviarMensagemQueCitaEquipeSolicitante`)
tiveram a mensagem-fixture ajustada para citar 2 tokens da equipe em vez
de 1 — o cenário que exercitavam (equipe citada por inteiro) continua
`BLOQUEADO` como sempre foi; só deixou de ser satisfeito por um único
token isolado, que é exatamente a calibragem pretendida.

Suíte completa: **926 testes, 0 falhas** (918 + 8 novos, JDK 21).

### F5 — MESCLADA (S7.1, simetria de `podeEnviar` no canal do solicitante, achado A8)

**Decisão de produto confirmada pelo usuário (Q4 do relatório): caminho
OPOSTO ao sugerido pela recomendação original.** O relatório propunha, como
opção padrão, restringir o lado do OPERADOR (bloquear `podeEnviar` também
quando a solicitação/processo já foi cancelado) para ficar simétrico ao
lado do solicitante, que já bloqueava. **O usuário escolheu afrouxar o lado
do SOLICITANTE** para ficar simétrico ao operador, que sempre foi
permissivo (sempre `true`) — o solicitante pode continuar enviando mensagem
para a equipe mesmo depois de cancelar o próprio pedido (ex.: explicar o
motivo do cancelamento, confirmar algo, etc.).

`SolicitanteController` ganhou um método privado único,
`podeEnviarMensagem(StatusSolicitacaoOnline status)`, fonte única da regra
— usado no poll (`GET .../mensagens`, campo `podeEnviar` do JSON) e nos
dois endpoints de envio (`POST .../mensagem`, clássico, e `POST
.../mensagem/ajax`). Antes, os 3 pontos bloqueavam em `CANCELADA` **e**
`PROCESSO_EXCLUIDO`; agora só bloqueiam em `PROCESSO_EXCLUIDO`.

**`PROCESSO_EXCLUIDO` continua bloqueado, decisão tomada lendo o código
real** (`ProcessoService.excluir`, `SolicitanteController
.montarSituacaoPedido`) — não presumido. É um estado estruturalmente mais
definitivo que `CANCELADA`: é o `Processo` gerado a partir desta
`SolicitacaoOnline` ter sido **excluído pelo ADMIN** (`processoGerado` é
desvinculado, seta `null`, e a solicitação fica órfã) — diferente de um
cancelamento, que é uma decisão do próprio fluxo normal (solicitante ou
operador registrando `CANCELADO` via `ProcessoService.decidir`). A própria
mensagem que a tela já mostra ao solicitante nesse estado
(`montarSituacaoPedido`) orienta a enviar uma **nova** solicitação, não a
continuar esta conversa — não há mais processo/equipe ativa do outro lado
desta thread específica. Nenhum outro estado (`DEVOLVIDA`, `APROVADA`,
`REPROVADA`, `CONVERTIDA`, `ENVIADA`) já bloqueava antes desta mudança, e
nenhum passou a bloquear — só a condição de `CANCELADA` foi removida.

**Não mexido:** `ProcessoDetalheController`/
`SolicitacaoOnlineTriagemController` (lado do OPERADOR, já permissivo,
sempre `true` — não precisou de nenhuma mudança) e `solicitante/detalhe.html`
(não havia nenhum texto do tipo "você não pode mais enviar mensagem porque
cancelou" para ajustar — conferido antes de codar).

Testes novos (`SolicitanteChatPodeEnviarSimetriaIntegrationTest`,
`@SpringBootTest` + H2 real, sem mock — convenção do projeto para escrita
irreversível): poll devolve `podeEnviar: true` para `CANCELADA` e `false`
para `PROCESSO_EXCLUIDO`; envio com sucesso via AJAX e via endpoint
clássico para uma solicitação `CANCELADA` (mensagem persistida, relida do
banco); recusa (400/flash de erro, sem persistir nada) nos dois endpoints
para `PROCESSO_EXCLUIDO`.

Suíte completa: **932 testes, 0 falhas** (926 + 6 novos, JDK 21).

### F2 — MESCLADA (S2 + S1, achados A1/A2 — a fase de maior risco do plano)

Mexe em `processos/detalhe.html` (a tela mais complexa do sistema) e no
`ProcessoDetalheController`. As duas sub-fases foram implementadas **juntas**
(o próprio relatório explica por quê: entregar uma sem a outra deixa o furo
aberto pela metade).

- **S2 (achado A2):** `ProcessoDetalheController.detalhe` passou a aceitar
  `@RequestParam(required = false) String aba` — quando vier um `paneId`
  **real do wizard deste processo** (nunca uma string arbitrária da query
  string; validado contra o `Set` de `paneId`s de `passosWizard`), usa esse
  valor para `abaAtivaPaneId` em vez do cálculo automático (primeira etapa
  não concluída). Sem o parâmetro, ou com um valor inválido, comportamento
  idêntico a sempre. O link "Abrir processo" da caixa de entrada
  (`mensagens-avaliadores-lista.html`) trocou a âncora morta `#respostas`
  (não existia elemento com esse id — o painel real é `pane-respostas`, e
  nenhum JS lia `location.hash`) por `@{/processos/{id}(id=...,
  aba='pane-respostas')}`, um link expression Thymeleaf de verdade.
- **S1 (achado A1):** o bug real — abrir `/processos/{id}` em **qualquer**
  aba marcava a mensagem do avaliador como lida para **todos** os
  operadores, porque `elCollapse.classList.contains('show')` (Bootstrap
  Collapse) é independente de qual aba do Bootstrap Tab está visível. A tela
  disparava o poll (que marca como lida no servidor) sempre que existia
  conversa, mesmo com a aba "Respostas" oculta. Corrigido em duas camadas:
  1. **JS** (`processos/detalhe.html`): nova função `tabRespostasEstaAtiva()`
     (checa `#pane-respostas.classList.contains('active')`). A thread do
     avaliador só inicia (`iniciarThread`) quando **as duas** condições
     valem: `tabRespostasEstaAtiva()` **e** o collapse tem `show`. Registrado
     também `shown.bs.tab` no botão `#tab-respostas` (id gerado
     automaticamente a partir do `paneId`, ver `processos/detalhe.html`) —
     quando o operador de fato entra na aba, as threads já expandidas (mas
     ainda não iniciadas) começam a pollar naquele momento.
  2. **Servidor (defesa em profundidade):** `GET .../avaliador/{membroId}/
     mensagens` passou a exigir `?marcarLida=true` explícito para chamar
     `marcarComoLidas` — sem o parâmetro (omitido ou `false`), devolve as
     mensagens sem marcar nada. O `pollUrl` montado no Thymeleaf já embute
     `marcarLida=true`, porque a instância só é criada quando a conversa
     está de fato visível — não foi preciso modificar `chat-solicitacao.js`
     (que continua proibido de ser bifurcado/reescrito), só o parâmetro
     extra na URL montada no servidor.
  Escopo confirmado: o canal **Avaliador↔Operador** (só o lado operador,
  em `ProcessoDetalheController`) tinha esse defeito — o chat do
  **Solicitante** não (fica sempre visível na barra lateral esquerda, REGRA
  fixa do produto, sem tabs escondendo), e o lado do próprio **avaliador**
  em `avaliador/votar.html` também não (página única, sem tabs escondendo o
  chat).

Testes novos em `MensagemAvaliadorIntegrationTest`: `?aba=pane-respostas`
abre de fato no pane certo (com o pane Envio, que seria o padrão automático
nesse fixture, confirmadamente INATIVO); `?aba` ausente preserva o cálculo
automático de sempre; `?aba` com string arbitrária (`<script>...`) é
ignorado com segurança (nunca refletido no HTML, cai no cálculo
automático); abrir o detalhe em qualquer aba nunca marca mensagem do
avaliador como lida sozinho; o poll AJAX sem `marcarLida=true` não altera
nada; com `marcarLida=true`, marca de verdade.

Suíte completa: **932 testes, 0 falhas** (926 + 6 novos, JDK 21). E2E
dedicado (`ChatVisualVerificacaoIT`) verde; `FluxoCompletoProcessoIT` falha
na mesma linha pré-existente e documentada (SMTP local ausente), não
relacionada.

### F4 — MESCLADA (S8 + S7.2, achados A9/A10/A11/A12)

Lote de baixo risco (higiene de notificação + acessibilidade), sem decisão
de produto pendente além da confirmação leve de Q5 (badge de mensagens para
o avaliador — recomendado pelo relatório, sem objeção).

- **S8.1 (achado A10):** os dois badges globais da navbar
  (`#navBadgeMsgNaoLida`, mensagens do solicitante; `#navBadgeMsgAvaliadorNaoLida`,
  mensagens de avaliador do lado operador) eram renderizados com `th:if="${...
  > 0}"` — **não existiam no DOM** quando a contagem começava em zero (o
  caso normal ao carregar a página), então o poll global em JS (`document.
  getElementById(...)`) nunca conseguia fazê-los **aparecer pela primeira
  vez**, só atualizar um que já existisse. Os dois passaram a ser **sempre**
  renderizados, com `th:classappend="${contagem == 0} ? 'd-none' : ''"`; os
  dois blocos de poll (20s, dentro do fragment `navbar`) passaram a fazer
  `badge.classList.toggle('d-none', atual <= 0)` além de atualizar o texto.
- **S8.2 (achado A10, "o AVALIADOR não tem badge de mensagens"):** o sino que
  já existia para o AVALIADOR na navbar (`th:if="${pendentesAvaliador > 0}"`)
  conta **pareceres pendentes de voto** — um conceito totalmente diferente de
  mensagem de chat não lida. Antes desta fase, uma mensagem do operador só
  gerava um toast (que passa) — nenhum indicador persistente. Novo
  `@ModelAttribute("mensagensNaoLidasAvaliador")` em `GlobalModelAdvice`
  (mesmo padrão de `pendentesAvaliador()`: resolve o `Usuario` autenticado →
  `Usuario.getMembro()` → `MensagemAvaliadorService.contarNaoLidasParaMembro
  (membroId)`, já existente, `0` se não autenticado/sem papel AVALIADOR/
  serviço nulo em `@WebMvcTest`). Novo item de navbar (`sec:authorize=
  "hasRole('AVALIADOR')"`), `id="navBadgeMsgAvaliadorPortalNaoLida"`
  (nome escolhido de propósito para não colidir com
  `#navBadgeMsgAvaliadorNaoLida`, que é o badge do **lado OPERADOR** dentro
  do link "Mensagens dos avaliadores") — mesmo padrão S8.1 (sempre no DOM,
  `d-none` quando zero). **Reaproveita a MESMA chamada de rede** do poll
  global já existente do avaliador (`GET /avaliador/nao-lidas-count`, 20s,
  chave de sessionStorage `saur_nl_avaliador_msg`) — não cria um segundo
  poll para a mesma informação, só passou a também fazer
  `document.getElementById('navBadgeMsgAvaliadorPortalNaoLida')` e alternar
  `d-none`/texto a cada resposta. **Cuidado que custou uma rodada de teste
  quebrado:** o comentário HTML que documenta esse reaproveitamento fica
  FORA do `th:if="${chatAtivoNestaTela != true}"` que envolve o próprio
  bloco de poll (S3/F1) — citar ali, sem pensar, a *string literal* da chave
  de sessionStorage daquele poll (`saur_nl_avaliador_msg`) quebrou
  `MensagemAvaliadorIntegrationTest.telaDeVotoNaoRendaOPollGlobalDeMensagensDoAvaliador`,
  que confirma a **ausência** dessa string no HTML da tela de voto (onde o
  poll global é propositalmente suprimido). Corrigido reescrevendo o
  comentário para descrever o mecanismo sem citar a chave literal.
- **S8.3 (achado A11):** `chat-solicitacao.js`, função `assinatura()` —
  concatenava `[id, deletada, lida, podeApagar, texto].join('')` **sem
  delimitador** entre os campos e entre mensagens, então dois estados
  distintos podiam produzir a MESMA string (`id=1` + flags `000` + texto
  `"0023"` e `id=10` + flags `000` + texto `"023"` geravam ambos
  `"10000023"`), fazendo o chat deixar de re-renderizar uma mudança real.
  Corrigido para `.join('|')` (interno e externo). **Achado ao editar:** o
  código já em produção (herdado de uma sessão anterior não documentada)
  usava, no lugar de um delimitador visível, os **bytes de controle brutos**
  `\x01`/`\x02` (SOH/STX) escritos direto dentro das aspas do literal
  JavaScript — tecnicamente funcionava como delimitador (nunca aparece em
  texto digitado por humano), mas é invisível em qualquer diff/editor e
  extremamente frágil a qualquer ferramenta que normalize encoding. Trocado
  pelo delimitador visível `|` recomendado pelo plano.
- **S8.4 (achado A12, acessibilidade):** os 5 contêineres de chat que
  recebem conteúdo via `innerHTML` (`#chatBox` em `processos/detalhe.html`,
  `solicitante/detalhe.html` e `solicitacoes-online-detalhe.html`;
  `#chatBoxAval` no avaliador; `#chatBoxAval{parecerId}`, dinâmico, por
  avaliador em `processos/detalhe.html`) ganharam `role="log" aria-live=
  "polite" aria-relevant="additions"` — antes, mensagem nova era
  completamente silenciosa para leitor de tela (só o toast, fora de
  qualquer região `aria-live` declarada no chat). O botão "Conversa"
  (`processos/detalhe.html`, tabela de pareceres) tinha `data-bs-target` e
  `aria-expanded` mas **não** `aria-controls` apontando para
  `chatAval{parecerId}` — adicionado (`th:attr` com a mesma expressão do
  `id`). `AcessibilidadeEstruturaTest` (já existente, valida que toda
  referência `aria-controls`/`aria-labelledby` aponta para um `id`
  realmente presente) confirmado verde sem ajuste.
- **S7.2 (achado A9):** `chat-solicitacao.js` — quando `podeEnviar` vinha
  `false` do poll, o form era escondido com `form.classList.add('d-none')`
  mas **nunca reaparecia** se `podeEnviar` voltasse a `true` (ex.: ADMIN
  reabre o processo enquanto o operador está com a tela aberta). Trocado
  para `form.classList.toggle('d-none', data.podeEnviar === false)` — o
  formulário reaparece sozinho no próximo poll (5s) sem precisar de reload
  manual. Correção pontual explicitamente prevista no plano (S7.2), dentro
  do escopo permitido de mexer em `chat-solicitacao.js` (o módulo continua
  proibido de ser bifurcado/reescrito para o resto do sistema).

**Testes:** `MensagemAvaliadorIntegrationTest` ganhou
`navbarDoAvaliadorMostraOBadgeDeMensagensVisivelQuandoHaNaoLida` e
`navbarDoAvaliadorEscondeOBadgeDeMensagensQuandoNaoHaNaoLida` — renderizam
`GET /avaliador` de verdade (não `@WebMvcTest` de status) e conferem a
presença/ausência da classe `d-none` no `<span id=
"navBadgeMsgAvaliadorPortalNaoLida">`, com e sem mensagem não lida do
operador.

**Validação:** suíte completa **914 testes, 0 falhas/erros** (JDK 21,
`mvn test` isolado). `AcessibilidadeEstruturaTest` verde sem ajuste. E2E:
`ChatVisualVerificacaoIT` verde (51,6s); `FluxoCompletoProcessoIT` falha na
MESMA linha 228 já documentada (SMTP local ausente,
`SGPUR_MAIL_USER`/`SGPUR_MAIL_FROM` não configurados nesta máquina), sem
nenhuma relação com esta fase — confirmado pelo log
(`EmailSender: remetente (from) nao configurado`), idêntico ao já registrado
nas sessões anteriores.

### F6 — MESCLADA (S10, achado A13): índices de banco + `marcarComoLidas` em lote

Implementada em paralelo à F2-F5 (sub-agente isolado em worktree próprio,
branch `feat/chat-f6-indices-lote`), enquanto o agente principal cuidava
das demais fases. Escopo fechado, sem decisão de produto pendente — só
performance estrutural, nenhuma regra de negócio mudou. Rebase manual sobre
`main` (após F1/F2/F3 mescladas) feito pelo agente principal antes do
merge, resolvendo o único conflito real (este arquivo, `CLAUDE.md` —
mantidas as seções de F3/F2/F6, nenhuma descartada).

- **Índices de banco** via `@Table(indexes = ...)`:
  - `MensagemAvaliador` (`mensagem_avaliador`): índice composto
    `idx_mensagem_avaliador_processo_membro_data` em `(processo_id,
    membro_id, data_envio)` — acelera `findByProcessoIdAndMembroId
    OrderByDataEnvioAsc` (a thread aberta, poll de 5s) e o novo UPDATE em
    lote abaixo; e `idx_mensagem_avaliador_lida_remetente` em `(lida,
    remetente)` — acelera os badges (`countByLidaFalseAndRemetente` e
    variantes).
  - `MensagemSolicitacao` (`mensagem_solicitacao`): mesmo racional,
    `idx_mensagem_solicitacao_solic_data` em `(solicitacao_online_id,
    data_envio)` e `idx_mensagem_solicitacao_lida_remetente` em `(lida,
    remetente)`.
  - **`ddl-auto: update` CRIA índice novo sem drama** (ao contrário de CHECK
    constraint de enum e coluna NOT NULL, ver "Convenções de código" acima)
    — mas, seguindo a prática do projeto de nunca presumir o que o `update`
    fez, **confirmar em produção via SSH após o deploy** que os 4 índices
    existem de fato:
    ```sql
    SELECT indexname, indexdef FROM pg_indexes
    WHERE tablename IN ('mensagem_avaliador', 'mensagem_solicitacao');
    ```
- **`marcarComoLidas` em lote** (elimina o carregamento da thread inteira em
  Java a cada poll de 5s de uma conversa aberta, mesmo quando não há nada
  para marcar): `MensagemAvaliadorRepository.marcarComoLidasEmLote` e
  `MensagemSolicitacaoRepository.marcarComoLidasEmLote` — dois `@Modifying`
  `@Query` de UPDATE em lote, mesmo padrão exato de
  `ParecerRepository.registrarUltimoLembrete` (JPQL, não SQL nativo,
  `@Transactional` herdado do método de serviço que chama, sem
  `clearAutomatically` — os dois controllers que chamam `marcarComoLidas`
  não têm `@Transactional` de classe, então o UPDATE sempre commita numa
  transação própria antes de qualquer leitura seguinte, ex.:
  `paraChat(...)`, abrir a sua). `MensagemAvaliadorService.marcarComoLidas`/
  `MensagemSolicitacaoService.marcarComoLidas` mantiveram a assinatura
  pública inalterada, só a implementação interna mudou — de "carregar tudo
  + filtrar em Java + `saveAll`" para delegar direto ao UPDATE.

**Testes:** dois testes de integração novos
(`MensagemAvaliadorMarcarComoLidasEmLoteIntegrationTest`,
`MensagemSolicitacaoMarcarComoLidasEmLoteIntegrationTest`, em
`src/test/java/br/gov/saude/sgpur/service/`, `@SpringBootTest` + H2 real,
sem mock — convenção do projeto para escrita em lote), cada um cobrindo:
mensagens do outro lado não lidas (marcadas), mensagens do próprio lado
(nunca tocadas, mesmo não lidas), mensagens já lidas (idempotência — seguem
lidas, sem erro), mensagem com o mesmo `remetenteId` de quem está marcando
(guarda contra marcar a própria mensagem), mensagem de OUTRA
thread/processo/solicitação (nunca tocada — o cenário mais importante de um
UPDATE em lote, garantir que ele não vaza pro escopo errado), e nenhuma
mensagem para marcar (não quebra). Todos releem cada linha do banco depois
da chamada e conferem `isLida()` campo a campo, nunca confiando em mock.

Suíte completa validada: **924 testes, 0 falhas** (918 + 6 novos, JDK 21).

## Vistoria de brechas de visibilidade nas decisões excepcionais — F2 a F6 implementadas (2026-08-10)

`docs/RELATORIO-VISTORIA-BRECHAS-DECISAO-2026-08-10.md` (diagnóstico +
plano faseado, seguindo a pergunta "se eu for um operador/ADMIN olhando
qualquer tela/PDF/e-mail/log deste sistema, dá para saber que ESTA decisão
não seguiu o caminho padrão de maioria simples 2-de-3?") teve as 6 fases
(F1-F6) implementadas e mescladas no mesmo dia, uma por PR (#89 a #94),
cada uma com a suíte completa verde antes do merge. **Regra de ouro
respeitada em todas as fases:** nenhuma alterou `ProcessoValidator`
(contagens, `sugerirDecisao`, `validarDecisao`, `validarContagemVotos`,
`validarPausaDecisao`, `temVotoCoordenadorFavoravel`,
`favoraveisNecessariosParaDeferir`) nem `ProcessoService.decidir`/
`tentarDecisaoAutomatica` em nada que mude **qual decisão sai** de um
conjunto de votos — tudo é aditivo ou de apresentação/auditoria. F1 (achado
1, nome do coordenador no PDF) foi tratada em paralelo por outro agente/PR
(`#89`), isolada por ser a única fase que encostava no conceito de "quem é
o coordenador para efeito de voto" (ainda que só na *impressão do nome*, a
regra em si já usava o snapshot desde 2026-08-07 — ver seção "Achado 4" mais
acima, cujo texto original ficou desatualizado até esta sessão corrigir a
menção). F2-F6 foram implementadas em sequência por este agente, cada uma
como branch/PR própria a partir do `main` já atualizado pela fase anterior.

**F2 — fonte única `RegraDecisao` + badge do voto do coordenador (PR #90,
achados 2/3/4/6).** `service/dto/RegraDecisao.java` (enum novo,
vocabulário fechado: `MAIORIA_SIMPLES`, `VOTO_COORDENADOR`, `CANCELAMENTO`,
`NAO_DECIDIDO`) + `ProcessoValidator.regraAplicada(Processo)` (método de
LEITURA, reusa `temVotoCoordenadorFavoravel` sem duplicar/alterar o
predicado) + wrapper fino `ProcessoService.regraAplicada`. Corrige: dossiê
exportado dizendo *"1 favorável (regra: 2 de 3 defere)"* num processo
deferido pelo coordenador (achado 2, `ExportacaoProcessoService`);
`FluxoProcessoService` dizendo *"Maioria formada"*/"regra 2 de 3
favoráveis" num processo já decidido ou decidido por 1 voto só (achado 3);
rótulo *"Dispensado pela maioria"* onde não houve maioria nenhuma (achado
4, `RelatorioService`/`processos/detalhe.html`); e o badge "Deferido pelo
Coordenador da CET-RS" existindo só na tela de detalhe (achado 6) — novo
fragment `layout :: badgeRegraDecisao(regra, classes)`, aplicado também em
`processos/lista.html`, `arquivo/lista.html` e `dashboard.html`. **Decisão
de arquitetura importante:** o fragment recebe o `RegraDecisao` **já
calculado pelo controller** (não chama `@processoValidator...` direto do
template) — chamar um bean de serviço de dentro de um fragment Thymeleaf
quebraria qualquer `@WebMvcTest` que renderize essas 4 telas sem subir o
contexto completo do Spring (`ArquivoController`/`HomeController` ganharam
`ProcessoValidator` injetado só para isso). Testes: unitário de
`regraAplicada` (5 cenários), 3 cenários novos em
`FluxoProcessoServiceTest`, render em `ProcessoDetalheControllerTest`/
`ProcessoListaControllerTest`, e um teste de integração real
(`ProcessoExportacaoIntegrationTest`) que **gera o PDF e o ZIP de
verdade** de um processo deferido pelo coordenador e confirma ausência de
"regra: 2 de 3"/"Maioria formada" em ambos.

**F3 — auditoria estruturada da decisão (PR #91, achado 5).**
`AuditoriaService.formatarDetalheProcessoDecidido(Processo, origem,
RegraDecisao)`: fonte única de formatação, reusada pelos 3 pontos que
gravam `PROCESSO_DECIDIDO` (`ProcessoDecisaoController.decidir`/
`retomarAnalise`, `AvaliadorController.registrarVoto` — decisão automática
no portal). IP passou a ser gravado nos 2 pontos automáticos que não
gravavam antes (havia um ator humano por trás: o clique em "retomar
análise" ou o próprio voto). `PROCESSO_REABERTO` passou a registrar a
decisão anulada (status + regra). **Nunca inclui nome de paciente nem
justificativa clínica** — só número do processo, decisão e regra. **Bug
real corrigido no caminho** (achado só pelo teste de integração H2, nunca
pelos testes com mock): `ProcessoDetalheController.reabrir` usava
`processoService.buscar(id)` (`findById` puro); como a entidade fica
*detached* logo em seguida (`spring.jpa.open-in-view: false`), ler
`processo.pareceres` (necessário para `regraAplicada`) lançava
`LazyInitializationException` e a reabertura falhava silenciosamente
(nunca chegava a chamar `processoService.reabrir`). Corrigido usando
`processoRepo.findByIdComPareceres` (fetch join). Teste:
`DecisaoAuditoriaEstruturadaIntegrationTest` (`@SpringBootTest` + H2 real,
sem mock), 3 cenários relendo `LogAuditoria` do banco.

**F5 — histórico de reaberturas + relatório obsoleto removido (PR #92,
achados 8 e 9; implementada antes da F4 de propósito, por ter menor
risco/dependência).** `Processo.reaberturas` (`Integer`, nullable — sem
backfill, mesmo padrão de `ultimoLembreteSntEm`/`numeroOficio`;
`getReaberturasOuZero()` trata `null` como 0). `ProcessoService.reabrir`
incrementa o contador e **remove o anexo `RELATORIO_FINAL`** da decisão
anulada (`anexoStorage.removerAntigosDoTipo(p, TipoAnexo.RELATORIO_FINAL,
null)`) — o relatório é sempre DERIVADO (regenerado por
`DecisaoFinalService.gerarDocumentos` na próxima decisão), então continuar
oferecendo para download um documento institucional afirmando "RESULTADO:
DEFERIDO" de um processo que voltou para ENVIADO era uma janela real (dias,
ou permanente se nunca redecidido). Fragment `layout ::
badgeReaberturas(p, classes)` — "Reaberto Nx", leitura pura de
`Processo.reaberturasOuZero` (sem bean de serviço, ao contrário do
`badgeRegraDecisao` da F2 — aqui não precisou porque o dado já está na
própria entidade), aplicado nas mesmas 4 superfícies. Linha "Reaberturas"
condicional no Relatório Final. Teste:
`ReaberturaRemoveRelatorioObsoletoIntegrationTest` (H2 real, sem mock)
decide pelo coordenador, confirma o PDF acessível, reabre, confirma 404 no
download + contador incrementado.

**F4 — histórico de pareceres sobrepostos pela pausa (PR #93, achado 7 —
decisão de produto já aprovada: opção "a", guardar o texto completo).**
`domain/HistoricoParecer.java` (entidade nova, tabela `historico_parecer`):
staging/append-only, no espírito de `SolicitacaoOnline`/
`AnexoSolicitacaoOnline` — nunca se mistura ao ciclo de vida do `Parecer`
real. `HistoricoParecer.deParecer(par, motivo)` constrói o snapshot (com a
justificativa clínica completa) **antes** de
`ProcessoService.retomarAposInformacao` zerar os campos do parecer que
pediu informação — o reset em si continua **exatamente** o mesmo de
sempre, ganhou só um passo adicional antes dele.
`ProcessoService.historicoParecer(processoId)` (wrapper) alimenta o card
Respostas (colapsável, só quando há algum registro) e uma seção nova do
Relatório Final. Como é tabela **nova** (sem linhas antigas), os 2 enums
reusados (`ResultadoParecer`/`OrigemParecer`) não têm nenhum risco de CHECK
constraint desatualizada. Teste: `HistoricoParecerIntegrationTest` (H2
real, sem mock) percorre pausa → retomada → decisão automática e confirma
que o rastro (incluindo a justificativa original) sobreviveu ao reset,
relendo do banco — e que o parecer VIVO continua resetado por completo.
`ReaberturaMantemPausaAtivaIntegrationTest` precisou de um ajuste de
limpeza (`historicoParecerRepo.deleteAll()` antes de `processoRepo.deleteAll()`
no `@BeforeEach`) por causa da FK nova.

**F6 — aviso ao avaliador dispensado pela decisão (PR #94, achado 10 —
decisão de produto já aprovada: sim, adicionar o aviso).**
`ParecerRepository.findDispensadosComProcesso(membroId, statusFinal)`:
pareceres nunca votados (`resultado is null`) cujo processo já foi decidido
— o avaliador foi dispensado por maioria simples ou pela exceção do
coordenador antes de conseguir votar. Antes, esse processo simplesmente
"evaporava" da tela do avaliador: sumia de "Pendentes" (status deixou de
aceitar voto) e nunca aparecia no "Histórico" (que exige `resultado !=
null`). Nova seção "Processos decididos sem o seu voto" no Portal do
Avaliador, projetada num record novo (`AvaliadorController.ParecerDispensadoView`)
com **somente** número do processo + iniciais — deliberadamente sem
resultado da decisão nem identidade/voto de outros avaliadores (mesmo
padrão de `ProcessoVotoView`/`ParecerVotoView`, reaproveitado, não um
caminho novo de exposição de dado). **Revisão extra de imparcialidade**
(é a tela mais sensível a vazamento do sistema): além do teste de render
condicional em `AvaliadorControllerTest`,
`AvaliadorDispensadoIntegrationTest` (`@SpringBootTest` + H2 real, HTTP
real) monta um cenário completo (2 avaliadores decidem por maioria, o 3º
nunca vota) e confirma por asserção **negativa** que a resposta renderizada
não contém o nome completo do paciente, o resultado da decisão
("Deferido"/"Indeferido") nem os nomes dos outros 2 avaliadores — só
número do processo + iniciais.

**Validação final:** suíte completa em **977 testes, 0 falhas** (JDK 21,
partindo da base de 946 antes desta sessão). `mvn verify -Pe2e
-Dsaur.e2e.headed=false` rodado ao final de cada fase que tocou
templates/rótulos visíveis (F2, F5, F4, F6) — sem regressão em nenhuma,
única falha observada é a pré-existente de SMTP local
(`FluxoCompletoProcessoIT:228`, `SGPUR_MAIL_USER`/`SGPUR_MAIL_FROM`
ausentes neste ambiente, já documentada em sessões anteriores deste
arquivo).

Corrigida nesta mesma sessão a menção desatualizada no `CLAUDE.md` ao
"Achado 4" da vistoria de 2026-08-03 (`temVotoCoordenadorFavoravel` lendo
o cargo "ao vivo") como pendente de decisão de produto — já estava
implementado desde o commit `3dac941` (2026-08-07), só o texto do guia
não tinha sido atualizado (ver a correção logo acima, na mesma seção
"Vistoria de bugs de 2026-08-03").

## Cartão de situação do Portal do Solicitante deixou de repetir o corpo do e-mail (2026-08-11)

**Relato do dono do produto olhando `/solicitante/16` em produção:** o
cartão de resultado mostrava a prosa polida do controller ("...foi
analisado e DEFERIDO... A resposta oficial foi enviada por e-mail à sua
equipe, com o comprovante de inserção no SNT em anexo") e, **logo abaixo**,
o corpo BRUTO do e-mail institucional dizendo exatamente a mesma coisa em
linguagem de ofício ("Prezados(as), Informamos que o processo... foi
DEFERIDO... Segue EM ANEXO o comprovante... Atenciosamente, Equipe de
Urgência Renal"). Duas vezes a mesma informação, uma embaixo da outra.

**Causa:** `SolicitanteController.montarSituacaoPedido` usava
`Processo.mensagemResposta` (gravado por `ProcessoService.finalizarResposta`
com o corpo do e-mail de fato enviado) como o `detalhe` do
`SituacaoPedidoView` — no ramo Deferido sempre, e no ramo Indeferido como
**fallback** quando não havia `motivoIndeferimento`. Isso vinha da Fase 6 da
UI (2026-08-03/04), que preencheu o `detalhe` com esse campo para consertar
um `${mensagemResposta}` morto no template; o efeito colateral de
duplicidade só ficou visível com um processo real já finalizado.

**Correção (`montarSituacaoPedido`, único ponto — o template não recalcula
nada):**
- **Deferido:** `mensagemResposta` não alimenta mais o `detalhe`. Quando a
  resposta já saiu (`respostaJaEnviada`, a mesma condição da correção de
  contradição documentada acima), o `detalhe` traz só o que é **novo** —
  *"Não é preciso fazer mais nada por aqui — guarde o comprovante para os
  seus registros."*; enquanto a resposta está pendente, `detalhe` é `null`
  (quem tem pendência é a equipe, e a `mensagem` acima já diz isso).
- **Indeferido COM motivo:** inalterado — `"Motivo informado: ..."` é
  informação nova e continua sendo o texto mais útil da tela (só ganhou uma
  checagem de `isBlank`, para um motivo em branco não renderizar o rótulo
  sozinho).
- **Indeferido SEM motivo:** o fallback deixou de ser o corpo do e-mail e
  passou a apontar para o documento — *"A fundamentação completa da decisão
  está no ofício, disponível abaixo."* (o botão de download já está logo em
  seguida, no mesmo cartão); sem ofício anexado ainda, `detalhe` é `null`.
- As duas `mensagem` (Deferido/Indeferido) perderam o trecho redundante com
  o próprio título do cartão ("resultando no reconhecimento/indeferimento da
  urgência renal", com o título já dizendo "Deferido — Urgência renal
  reconhecida"). **As frases "A resposta oficial foi enviada por e-mail" e
  "O ofício com os detalhes foi enviado por e-mail" foram preservadas
  literalmente** — são o que os testes de contradição usam para provar que o
  cartão nunca afirma um envio que não ocorreu.

Nada mais mudou: `respostaJaEnviada`, a condição do botão de download, o
whitelist de anexos e `solicitante/detalhe.html` seguem intactos.

**Validação:** 4 testes novos em `SolicitanteControllerTest` (o corpo bruto
do e-mail some do HTML nos 3 casos; Deferido sem resposta enviada não ganha
detalhe nenhum; Indeferido com motivo segue exibindo o motivo) e **captura
visual real** com Playwright (IT temporário, não commitado) dos 3 cartões
renderizados — inspecionados um a um, sem repetição e com o botão de
download visível. Suíte completa: **981 testes, 0 falhas** (JDK 21).

## Acentuação e formatação dos e-mails prontos (2026-08-11)

Relato do dono do produto sobre um e-mail real de produção (processo 11/2026,
Deferido): *"a formatação dele está ridículo"*. Três defeitos sistêmicos em
`EmailTemplateService` — o arquivo nunca passou pela leva de acentuação de
2026-08-03/04 (só templates HTML) nem pela de 2026-08-08 (4 controllers).
Passaram a valer 3 regras, documentadas no javadoc da classe e travadas por
teste (`EmailTemplateServiceTest`, 3 casos novos que varrem TODOS os
templates): (1) **acentuação correta** — inclusive nos defaults
`sgpur.email.assinatura`/`prefixo-assunto` (`EmailProperties`/
`application.yml`); (2) **nunca CAIXA ALTA no meio de frase** ("foi
DEFERIDO", "Segue EM ANEXO", "foi CANCELADO") — o envio é em texto puro
(`EmailSenderService`, `setText(body, false)`), não há negrito, então caixa
alta não vira ênfase, só parece grito; (3) **bloco de identificação
(Processo/Paciente/Equipe solicitante) antes da prosa** nos e-mails à
equipe solicitante, em vez de uma linha de dado solta entre parágrafos.
`UsuarioService` (e-mail de redefinição de senha) foi acentuado junto. Nada
de regra de negócio, rota ou `gerar(Processo)` mudou;
`ResultadoParecer.descricao`/`StatusProcesso.descricao` seguem sem acento
de propósito (PDF oficial). **Ressalva de produção:** se a VM definir
`SGPUR_EMAIL_ASSINATURA`/`SGPUR_EMAIL_PREFIXO_ASSUNTO` em
`/opt/sgpur/sgpur.env` (fora do git), o valor de lá vence o default e
precisa ser acentuado manualmente. Suíte: **980 testes, 0 falhas** (JDK
21). PR #99.

## Fix: faixa de cabeçalho do Portal do Solicitante tinha o MESMO fundo da navbar (2026-08-11)

**Bug visual relatado pelo dono do produto em produção**, olhando
`/solicitante/16`: *"onde vai estas informações: Minhas solicitações / Ana
de Oliveira / Processo 11/2026 / Enviada em ... — o fundo está igual do
começo da página, altere isso. o css"*.

**Causa raiz confirmada VISUALMENTE (screenshot real via Playwright em
desktop 1440px e celular 390px, não leitura de CSS):** `.pagina-cabecalho-solida`
(a "capa do processo" criada na fase V4 do redesign visual, usada em
`solicitante/detalhe.html` e `avaliador/votar.html`) declarava
**literalmente o mesmo** `background: linear-gradient(135deg, var(--rs-blue)
0%, var(--rs-blue-dark) 100%)` de `.navbar-sgpur` — o comentário no
`app.css` até dizia isso explicitamente ("herda literalmente o tratamento
visual da navbar/login"). Resultado: navbar e faixa emendavam num **único
bloco azul** do topo da tela até o fim do cabeçalho, sem nenhuma pista de
onde o menu termina e o conteúdo começa (a borda dourada de 2px da navbar
some no meio da massa azul). Em celular o efeito era ainda mais forte —
~250px contínuos de azul. Nenhuma das outras hipóteses levantadas se
confirmou: não havia herança/especificidade errada nem cartão mais abaixo
repetindo a cor.

**Correção (só `app.css`, nenhuma cor nova, nenhum template alterado):** a
faixa passou a receber uma camada branca translúcida fixa
(`linear-gradient(rgba(255,255,255,.16), rgba(255,255,255,.16))`) por cima
do **mesmo** gradiente institucional, mais um filete branco no topo
(`box-shadow: inset 0 1px 0 rgba(255,255,255,.22)`) marcando a emenda. Isso
cria um degrau tonal claro entre navbar e faixa **mantendo** a identidade
azul, o texto branco, o `.chip-protocolo` e a marca d'água — ou seja, sem
reverter a decisão 4 do §10 do
`docs/RELATORIO-REDESIGN-VISUAL-SOLICITANTE-2026-08.md` (variante sólida
como capa do processo), que foi aprovada pelo dono do produto. Contraste do
texto branco continua aprovando em WCAG AA (~5,6:1 na ponta mais clara do
gradiente resultante).

**Alternativas NÃO adotadas** (registradas caso o dono do produto prefira
outro caminho ao revisar o PR): (a) usar a variante suave/clara
`.pagina-cabecalho` da lista/formulário, o que reverteria a decisão 4 do
§10; (b) fundo branco/cinza sem faixa colorida. As duas mudam mais do que a
queixa pedia — a queixa é sobre o fundo ser **igual ao do topo**, não sobre
existir uma faixa azul.

**Validação visual obrigatória feita** (a suíte nunca reprova cor/fundo —
ver §11 do relatório de redesign): screenshots regenerados e inspecionados
em desktop e celular para `/solicitante/{id}` (em andamento, DEFERIDO e
INDEFERIDO) **e** para `/avaliador/{id}`, que usa a mesma classe — nenhuma
regressão, o degrau tonal aparece nas duas telas. Suíte completa: **977
testes, 0 falhas** (JDK 21). E2E `RedesignVisualSolicitanteIT` e
`PortaisVisualCompletoIT` verdes (o `assertPinta` do primeiro segue válido:
a faixa continua pintando por gradiente).

## Vistoria de responsividade e cores do Portal do Solicitante (2026-08-11)

`docs/RELATORIO-RESPONSIVIDADE-CORES-SOLICITANTE-2026-08.md` — **IMPLEMENTADO**
(o relatório é registro, não plano pendente). Gatilho: pedido do dono do
produto, *"verifique responsividade e as cores, ajuste todo css, tinha texto
saindo da tela"*. Só o Portal do Solicitante; nenhuma regra de negócio,
controller, endpoint ou `name=`/`id` de campo mudou.

**O bug relatado era real e mensurável: a página tinha 642px de largura numa
tela de 360px** (282px de estouro), nos estados Aguardando triagem, Deferido e
Indeferido de `/solicitante/{id}` — o solicitante precisava rolar de lado para
ler a decisão do próprio pedido.

**Causa raiz (vale para qualquer cartão flex do sistema):** `.cartao-resultado`
é `display:flex`, e **item de flex nasce com `min-width: auto`** — nunca encolhe
abaixo da largura *min-content* do conteúdo. As mensagens desse cartão carregam
o e-mail institucional da equipe solicitante, **um token sem nenhum espaço**, o
que fixava a largura do conteúdo em ~519px em qualquer viewport. `flex-grow` não
ajuda (só distribui sobra, não autoriza encolher). A correção é sempre **um
par**: `min-width: 0` (autoriza encolher) **+** `overflow-wrap: anywhere`
(autoriza o token a quebrar no espaço encolhido) — uma sem a outra não resolve.
Mesmo remédio que `.text-pre-wrap` já aplicava na justificativa clínica desde
2026-08-06; só não tinha sido estendido ao cartão de resultado, criado depois.

**Achado estrutural: o bloco de redesign V1–V6 do `app.css` não tinha UMA
media query.** A última `@media` do arquivo estava na linha ~1318 e todo o
bloco do Portal (`.pagina-cabecalho`, `.cartao-resultado`, `.estado-vazio`,
`.chip-protocolo`, `.zona-upload`) vem depois — tudo calibrado no mockup de
desktop e servido igual no celular. Criado um bloco responsivo novo
(`max-width: 767.98px` e `max-width: 575.98px`, sempre com `.98` pela armadilha
de sobreposição em pixel exato já documentada acima): o cartão de resultado
**empilha** em celular (ícone acima do texto), ícone 64→52px, título de destaque
1.75→1.35rem, ação principal em largura total.

Demais correções: tabela da lista trocou o corte `md` (768px) por `lg` (992px)
— entre 768 e 991px as 6 colunas quebravam todas as células em 3-4 linhas, pior
que os cards que já existiam logo abaixo (os dois lados, `d-lg-block` e
`d-lg-none`, **têm** que trocar no mesmo breakpoint); `text-truncate` →
`text-break` no nome do anexo em `detalhe.html` (**recaída** do bug já corrigido
em `nova.html` em 2026-08-04, ver "Lista de documentos selecionados" acima —
aquela correção não cobriu a tela de detalhe); `btn-warning` →
`btn-resultado-attention` e `btn-outline-danger` → `btn-resultado-danger` nos
dois botões dentro de cartão tintado (a família `.btn-resultado-*` foi criada em
2026-08-08 exatamente por esse motivo de contraste, para o Portal do Avaliador —
a tela que originou o padrão tinha ficado de fora); barra de ações e contador de
caracteres de `nova.html` com `flex-wrap`.

**`.w-sm-auto` foi definida no `app.css`**: o Bootstrap 5 **não** gera variantes
responsivas das utilities de largura, então `w-sm-auto` simplesmente não
existiria e o `w-100` valeria em todas as larguras.

**Guarda novo: `ResponsividadeSolicitanteIT`** (Playwright, profile `e2e`).
Semeia os 8 estados do portal com dados do tamanho dos reais (o e-mail curto de
fixture não reproduz o defeito) e percorre 10 telas × 6 larguras (360/390/576/
768/992/1440), falhando se qualquer uma estourar a largura da viewport —
nomeando tela, largura, pixels de estouro e elementos culpados. Nenhum outro
teste do projeto mede isso: a suíte olha status/model/texto e o
`RedesignVisualSolicitanteIT` olha cor e tamanho de fonte, nunca a largura do
documento. **Verificado que o guarda falha de verdade** ao reintroduzir a causa
raiz. Nota metodológica: removendo **só** o `min-width: 0` o teste continuava
passando (o `overflow-wrap` sozinho já bastava naquele breakpoint) — para
provar que um guarda pega o bug é preciso desfazer a causa raiz inteira, não um
pedaço dela.

**Badge da lista (§4) — CORRIGIDO no mesmo PR, opção "a" aprovada pelo dono do
produto ("sim pode alterar").** O defeito: na lista `/solicitante`, todo pedido
convertido mostrava o mesmo badge **verde** "Convertida em processo" —
inclusive os **INDEFERIDOS** (confirmado por screenshot: #18 indeferido,
#17/#16 deferidos e #14 em análise, visualmente idênticos), e verde significa
"deferido/sucesso" em todo o resto do Portal.

A cor vinha de `StatusSolicitacaoOnline.getBootstrapBadge()`
(`CONVERTIDA -> "bg-success"`), enum **compartilhado com a triagem do
OPERADOR** — por isso a correção **não toca o enum**: `web/dto/SituacaoListaView`
(record novo) + `SolicitanteController.montarSituacaoLista` decidem a partir do
`Processo` gerado e alimentam `situacoesLista` no model; `solicitante/lista.html`
consome nos **dois** pontos da tela (tabela ≥992px e cards <992px). Rótulos/tom:
Deferido (verde) · Indeferido (vermelho) · Cancelado (cinza) · Devolvida e
Processo excluído (vermelho) · Em análise e Aguardando triagem (azul) —
**mesmo vocabulário de `montarSituacaoPedido`**, de propósito: abrir o pedido
não pode mostrar rótulo diferente do que a lista mostrou. Cobre os dois
formatos históricos de "decidido" (`APROVADA`/`REPROVADA` e `CONVERTIDA` +
processo finalizado). O badge âmbar **"Ação necessária"** continua vindo do
mapa `acaoNecessaria` e tem **precedência** sobre este. `processos/solicitacoes-
online-lista.html` (triagem do operador) segue lendo o enum, inalterado — lá
"Convertida em processo" é a informação correta. Efeito colateral positivo: a
navegação `s.processoGerado.status` saiu do template para dentro da transação
de `lista()` (`open-in-view: false`). Guardas:
`SolicitanteControllerTest.listaDistingueVisualmentePedidoIndeferidoDeDeferidoEDeEmAnalise`
(HTML renderizado, com asserção **negativa** em "Convertida em processo") e
`ResponsividadeSolicitanteIT.listaMostraCoresDIFERENTESParaPedidoDeferidoIndeferidoEEmAnalise`
(navegador real, compara a cor de fundo **computada** de cada linha).

**Validação:** suíte completa **977 testes, 0 falhas** (JDK 21); E2E com
`RedesignVisualSolicitanteIT`/`PortaisVisualCompletoIT`/`ChatVisualVerificacaoIT`/
`ResponsividadeSolicitanteIT` verdes e `FluxoCompletoProcessoIT` falhando só na
linha 228 pré-existente de SMTP local ausente; de **12 ocorrências de estouro
horizontal para 0**; todos os screenshots relidos após as correções.

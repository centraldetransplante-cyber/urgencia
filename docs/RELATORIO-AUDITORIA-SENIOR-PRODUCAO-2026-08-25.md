# RELATÓRIO DE AUDITORIA — SISTEMA SAUR (PRODUÇÃO)

**Data:** 25 de agosto de 2026
**Sistema:** SAUR — Sistema de Gestão de Processos de Urgência Renal (CET-RS)
**Stack:** Java 21 · Spring Boot 3.5.16 · Spring Data JPA · Spring Security · Thymeleaf + Bootstrap 5.3.8 · OpenPDF 1.3.34 · PostgreSQL/H2

## Metodologia e como ler este documento

Este relatório passou por duas rodadas:

1. **Rodada 1 (sessão anterior):** um relatório gerado por IA externa (Gemini)
   foi conferido linha a linha contra o código real. A maioria dos
   *mecanismos* descritos era real, mas várias citações de `arquivo:linha`
   estavam erradas ou inventadas — corrigidas nessa rodada.
2. **Rodada 2 (esta sessão):** varredura independente do código-fonte,
   focada em achar bugs novos (lógica de negócio, corretude, race
   conditions, tratamento de erro) e riscos de segurança concretos, lendo
   diretamente os arquivos-fonte (não reaproveitando achados do Gemini sem
   reconferir). Áreas cobertas nesta rodada: `ProcessoValidator`,
   `ProcessoService` (decidir/retomarAposInformacao/reabrir/
   tentarDecisaoAutomatica/finalizarResposta/atualizarDados),
   `AvaliadorController` (voto, transações, IDOR), `DecisaoFinalService`
   (numeração de ofício), `DecisaoAutomaticaScheduler`,
   `RegistroEnvioService`, `EmailSenderService`, `EmailDominioValidator`,
   `UsuarioService` (revogação de sessão), `AnexoStorageService` (path
   traversal), `CpfUtil`, `Iniciais`, `ConflitoEquipeMatcher`,
   `InfoComplementarAvaliadorService`, `TempoRespostaService`,
   `SolicitacaoOnlineService` (pausa/cancelamento),
   `ProcessoAnexoController` (redação de PII para IA), `AuditoriaController`
   (exportação CSV), e os scripts `avaliador-votar.js`/`lock-submit.js`.

**Toda citação de `arquivo:linha` abaixo foi verificada por leitura direta
do arquivo nesta sessão.** Onde não foi possível verificar algo com
confiança, isso é dito explicitamente em vez de citar um número inventado.

## Resultado desta rodada: nenhum bug novo corrigido

Depois de uma varredura extensa e deliberadamente cética nas áreas listadas
acima, **não foi encontrado nenhum bug novo, isolado e seguro de corrigir
sem decisão de produto**. Isso não significa "sistema perfeito" — significa
que, nesta amostra de código (que cobre as trilhas de decisão, voto,
e-mail, anexos e segurança de sessão mais críticas do sistema), a lógica
bateu com o que o código-fonte e os comentários afirmam, e os principais
bugs de corretude que existiram nessas áreas **já foram corrigidos em
sessões anteriores** (a maioria com um comentário grande no próprio código
explicando o bug histórico, a causa raiz e por que a correção atual é
suficiente — ex. `AvaliadorController.registrarVoto`, `ProcessoService
.reabrir`, `ProcessoValidator.temPedidoInformacaoAtivo`,
`RegistroEnvioService.registrar`). Por isso **nenhum commit de código foi
feito nesta sessão**: não havia nada com risco baixo o bastante e
confirmado o bastante para justificar mexer no código de produção sem
aprovação — conforme a regra desta tarefa ("achado maior/ambíguo não
corrige sozinho, documenta").

Isso é consistente com o histórico documentado em `CLAUDE.md`: o projeto já
passou por múltiplas vistorias de segurança e de regras de negócio
(2026-07-24, 2026-07-28, 2026-08-03, 2026-08-10/12, 2026-08-24) que
encontraram e corrigiram bugs reais de produção, cada um documentado com
bastante detalhe. A superfície mais "batida" (decisão, voto, transações,
anexos, sessão) reflete isso.

## Achados verificados desta sessão (não são bugs — comportamento
confirmado como correto, registrado para não precisar reverificar depois)

- **`DecisaoFinalService.proximoNumeroOficio`** (numeração `NNNN/AAAA` do
  ofício, `MAX()+1` sem lock/sequence) — comportamento e risco já
  documentados no próprio javadoc da classe (linhas 86-106 do arquivo,
  método `atribuirNumeroOficioSeNecessario`/`proximoNumeroOficio` em
  `src/main/java/br/gov/saude/sgpur/service/DecisaoFinalService.java`):
  race condition teórica sob dois indeferimentos simultâneos, aceita
  conscientemente pelo baixo volume real (indeferimentos são raros e
  disparados por humano). **Pendência de baixa severidade, já conhecida —
  não uma descoberta nova.**
- **`AvaliadorController.registrarVoto`** — a alegação do relatório-fonte de
  que o voto roda em transações curtas e independentes (`TransactionTemplate`)
  para nunca perder o voto do médico numa falha de pós-processamento é
  **real e verificada**: 4 blocos de transação (voto, anexo opcional, status,
  decisão automática), cada um com seu próprio tratamento de erro,
  documentado com um javadoc extenso (linhas 438-474) que descreve o bug
  histórico corrigido (2026-07-29) e por que a correção atual é suficiente.
- **`ProcessoValidator`/`ProcessoService.retomarAposInformacao`** — ao
  reabrir um parecer pausado (`SOLICITA_INFORMACAO`) para novo voto, o
  método reseta `resultado`/`dataResposta`/`dataHoraVoto`/`votadoPor`/
  `origem`/`justificativa`, mas **não** reseta explicitamente
  `eraCoordenadorNoVoto`. Investigado com cuidado: **não é um bug
  explorável**, porque `temVotoCoordenadorFavoravel`
  (`ProcessoValidator.java:72-76`) só considera um parecer quando
  `resultado == FAVORAVEL` **e** `eraCoordenadorNoVoto == true`
  simultaneamente — com `resultado` zerado, o predicado já é falso
  independente do valor "sujo" que sobra no campo; e o próximo voto
  (`AvaliadorController.registrarVoto`, linha 519) sempre grava
  `eraCoordenadorNoVoto` de novo a partir do papel atual do membro. Sem
  janela de exploração real.
- **`AnexoStorageService.resolverArquivo`** (linhas 304-326) — a defesa
  contra path traversal usa `Path.startsWith(raiz)` (comparação por
  componentes de caminho), não comparação de string — evita o bypass
  clássico onde `"/data/anexos-evil".startsWith("/data/anexos")` seria
  verdadeiro por string mas o acesso deveria ser negado. Confirmado correto.
- **`EmailSenderService.enviar`/`enviarComAnexo`** (isolamento de falha do
  CC, achado de 2026-08-24) — confirmado que a falha do envio COM cópia
  (`emailAdicional` com domínio inválido) tenta de novo SEM a cópia antes
  de desistir, nos dois métodos (linhas 94-130 e 183-223 de
  `EmailSenderService.java`), preservando a entrega ao destinatário
  principal.
- **`AuditoriaController.csvCampo`** (exportação CSV, linhas 142-154) —
  mitigação de CSV/Formula Injection confirmada correta (prefixo `'` antes
  de aplicar o escape de `;`/`"`), incluindo a ordem certa (apóstrofo
  aplicado antes da citação entre aspas, para o Excel continuar tratando o
  valor como texto mesmo quando o campo também precisa ser citado).

## Nuance encontrada (não é bug, é documentação otimista demais)

- **`EmailDominioValidator`** (`src/main/java/br/gov/saude/sgpur/service/
  EmailDominioValidator.java`): o javadoc da classe (linhas 43-65) descreve
  a correção de 2026-08-24 como tendo resolvido o problema de
  `InetAddress.getAllByName` lançar a mesma `UnknownHostException` tanto
  para "domínio realmente não existe" quanto para "falha transitória de
  rede". **Isso é impreciso**: a correção de fato aplicada (rodar a
  consulta com timeout agregado de 2s num executor dedicado) resolve o
  problema de **DoS síncrono na thread HTTP** (o achado 3 do PR #120), mas
  **não** resolve a ambiguidade da exceção em si — `possuiMxOuEnderecoIp`
  (linhas 143-163) ainda trata **qualquer** `UnknownHostException` rápida
  como "domínio não existe" (`return false`), mesmo que a causa real seja
  um SERVFAIL/timeout curto do resolver do SO em vez de um NXDOMAIN de
  verdade. Na prática o impacto é baixo (o pior caso é rejeitar um e-mail
  de CC opcional válido por uma falha de DNS transitória, nunca bloqueia o
  cadastro inteiro nem o envio ao destinatário principal — `EmailSenderService`
  reenvia sem CC de qualquer forma), mas **o texto do javadoc afirma uma
  garantia que o código não entrega**. Sugestão de baixa prioridade: ajustar
  o comentário para não prometer distinção entre as duas causas, já que
  `InetAddress` não expõe essa informação. Não corrigido nesta sessão por
  ser puramente uma imprecisão de documentação, sem efeito de comportamento
  a testar/validar.

## Segurança — pontos já bem cobertos (verificados, não genéricos)

- **IDOR**: `AvaliadorController.resolverParecerDoMembro`/
  `resolverParecerPendente` (linhas 805-809 e 897-920) e
  `AnexoStorageService.resolverArquivo` fazem checagem de posse antes de
  qualquer acesso por ID. Não foi encontrado nenhum endpoint que aceite um
  ID de recurso sem checar propriedade/perfil nas classes lidas.
- **Revogação de sessão ativa** (`UsuarioService.revogarSessoesAtivas`,
  linhas 280-342): confirmado que usa o `username` **antigo** (capturado
  antes de qualquer `set`) mesmo quando username e perfil mudam na mesma
  chamada — corrige exatamente o bypass que uma versão anterior teria.
  Cobre tanto inativação quanto mudança de perfil (rebaixamento).
- **CC nunca derruba o e-mail principal** (verificado acima).
- **Path traversal em anexos** (verificado acima).
- **Teto de páginas por PDF** (`RegistroEnvioService.registrar`, linhas
  174-204): verificado que a checagem de `PdfReader.getNumberOfPages()`
  roda ANTES de consolidar/carimbar, com mensagem específica citando o
  motivo real de cada documento descartado (não um "sem páginas" genérico).

## Pendências já conhecidas (não corrigidas aqui, decisão de produto/infra)

Estas já estavam documentadas no `CLAUDE.md`/relatório anterior e continuam
válidas — não são descobertas novas, listadas aqui só para consolidar:

1. **CSP com `'unsafe-inline'`** em `script-src`/`style-src` — reduz defesa
   em profundidade contra XSS caso surja uma injeção de HTML. Migrar
   scripts inline dos templates para arquivos externos + CSP por nonce é
   trabalho de várias sessões, não um fix pontual.
2. **`SessionRegistry`/rate-limit em memória JVM** — correto para VM única
   (arquitetura atual do SAUR), não escala em cluster sem Spring
   Session/Redis. Documentar como requisito de infraestrutura se algum dia
   for cogitada uma segunda instância.
3. **Numeração de ofício por `MAX()+1` sem lock** (`DecisaoFinalService`) —
   ver acima; risco teórico, aceito pelo volume real.
4. **Redação de PII antes de enviar texto de PDF ao Gemini**
   (`ProcessoAnexoController.redigirDadosSensiveis`, linhas 456-493) é
   **best-effort** por padrão de regex (nome tokenizado, CPF, data,
   RGCT) — não é uma anonimização formal/garantida contra todo formato
   possível de dado sensível num documento clínico livre. Já documentado no
   próprio código como "reforço", não proteção absoluta; o próprio
   comentário já avisa disso. Continua sendo um risco residual aceito, não
   uma descoberta desta sessão.

## Melhorias de UI sugeridas (não implementadas — baixo impacto, sem
decisão de produto envolvida)

Nenhuma inconsistência de UI nova e concreta foi encontrada nos scripts e
templates revisados nesta sessão (`avaliador-votar.js`, `lock-submit.js`).
Ambos têm tratamento defensivo de ausência de Bootstrap/DOM, prevenção de
duplo-envio e uso de `textContent` (nunca `innerHTML`) para dados vindos do
próprio formulário. Não há achado de UI novo a reportar nesta rodada — as
melhorias de UI já cobertas no `CLAUDE.md` (design system, densidade por
perfil, cores semânticas por opção) continuam sendo a referência vigente.

## Rodada 3 (mesma sessão, depois da Rodada 2): segunda opinião via Gemini, verificada, com uma correção aplicada

Depois da Rodada 2, foi pedida uma segunda opinião ao Gemini CLI — em modo
só-texto (sem permissão de escrever arquivo: uma tentativa anterior mostrou
que o Gemini CLI instalado nesta máquina tem `write_file`/`replace`/
`run_shell_command` bloqueadas pelo ambiente, e fica tentando escrever
silenciosamente sem nunca conseguir — ver `.claude/agents/gemini-cli.md`).
A resposta foi conferida arquivo por arquivo, com resultado bem mais
preciso que tentativas anteriores desta sessão:

- **Convergência real com a Rodada 2 — `EmailDominioValidator`:** o Gemini
  apontou de forma independente o mesmo ponto documentado acima (javadoc
  prometendo distinguir NXDOMAIN de falha transitória, código não
  entregando isso). Duas fontes independentes achando o mesmo ponto é sinal
  forte de achado real — **corrigido nesta sessão**: o javadoc da classe
  foi ajustado para documentar essa limitação residual explicitamente, sem
  mudar comportamento (mudar comportamento de verdade exigiria trocar
  `InetAddress` por uma lib de DNS bruta, contra a decisão deliberada de
  manter a classe "só JDK puro"). Validado com `mvn compile` limpo.
- **Achado apresentado como "risco pendente de decisão de produto" que na
  verdade JÁ FOI decidido — `ProcessoDecisaoController.retomarAnalise`:** o
  Gemini descreveu corretamente o mecanismo (linhas 157-172: ao retomar
  análise após pausa, se a maioria já estava formada pelos outros votos, o
  processo é decidido na mesma requisição, sem esperar o avaliador que
  pediu informação votar de novo) mas apresentou isso como risco em aberto.
  **Não é** — está no `CLAUDE.md` ("Solicita informação (PAUSA)") como
  decisão de produto confirmada pelo usuário em 2026-08-07 após investigar
  um relato de produção: "manter o comportamento atual". Nenhuma ação
  necessária.
- **Achado de UI que se mostrou FALSO ao verificar:** o Gemini descreveu o
  botão "Ir à Decisão" (`processos/detalhe.html:862`) como sumindo "de
  forma confusa, sem explicar" quando a maioria está formada mas bloqueada
  pela pausa. Verificado em `ProcessoDetalheController.java:520-551`: já
  existe uma frase explícita para esse cenário exato — *"Maioria formada,
  mas BLOQUEADA: aguardando informação complementar"* — ao lado de onde o
  botão ficaria. Não é um achado real; nenhuma ação tomada.
- Demais achados do Gemini (CSP `unsafe-inline`, `SessionRegistry`/
  rate-limit em memória JVM, numeração de ofício sem lock) reforçam,
  consistentes, o que já estava documentado acima — nenhuma informação
  nova.

## Conclusão

O sistema já tinha passado por várias rodadas de auditoria de segurança e
correção de bugs (documentadas extensivamente no `CLAUDE.md`), e as duas
rodadas independentes desta sessão — uma lendo o código diretamente, outra
usando o Gemini como segunda opinião e verificando cada citação depois —
não encontraram bug novo, comprovado e não decidido, precisando de correção
de comportamento. A contribuição concreta desta sessão foi: **1 correção de
documentação aplicada** (javadoc do `EmailDominioValidator`, achado de
forma convergente pelas duas rodadas — validado com `mvn compile` limpo),
**1 achado de risco corretamente descartado** por já ter decisão de produto
documentada (`retomarAnalise`), e **1 achado de UI corretamente descartado**
por já ter mitigação existente no código (`fraseMaioria` do placar de
Respostas). Fora o ajuste de javadoc, nenhum outro código de produção foi
alterado nesta sessão.

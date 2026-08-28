# Relatório de Limpeza de Código e Documentação — SAUR
**Data: 25 de agosto de 2026**

Duas frentes conduzidas nesta sessão: (1) busca por código morto/duplicação
segura de remover, sem mudar comportamento; (2) conferência de `CLAUDE.md` e
`docs/*.md` contra o código real, corrigindo divergências pontuais.

**Build baseline:** `mvn clean test` (JDK 21) rodado ANTES de qualquer
edição — **1.131 testes, 0 falhas, 0 erros** (BUILD SUCCESS). Nenhuma
alteração de código-fonte (`.java`) foi feita nesta sessão — só documentação
(`CLAUDE.md` e `docs/INDEX.md`) — então o mesmo baseline continua válido
como estado final; não houve necessidade de rerodar a suíte depois.

---

## 1. Código morto / duplicação — resultado: nada removido

Investiguei por conta própria (sem confiar cegamente no relatório já
existente `docs/RELATORIO-ANALISE-ESTATICA-E-CODIGO-MORTO-2026-08-24.md`,
que já havia concluído algo parecido no dia anterior) e cheguei à mesma
conclusão por caminhos independentes:

- **Scan de classes referenciadas por nome** (125 arquivos `.java` de
  produção): só 3 classes apareceram com "baixo uso" —
  `AgendamentoRateLimitConfig`, `ProcessoExportacaoController`,
  `SolicitanteIndisponivelController`. As três são falsos positivos: são
  `@Configuration`/`@Controller` descobertas via component scan do Spring,
  nunca instanciadas por nome em código Java — comportamento esperado, não
  código morto.
- **Scan de métodos públicos "pouco referenciados"** em `service/` e
  `web/`: os únicos achados foram métodos de `@Controller` (endpoints REST/
  MVC como `minhaSenha`, `salvarRascunho`, `anualPdf` etc.) — invocados via
  requisição HTTP nos testes (`MockMvc.perform(post("/url"))`), não por
  chamada Java direta ao nome do método, então o grep por nome sempre dá
  "1 ocorrência" (a própria declaração) mesmo estando em uso pleno. Não são
  código morto.
- **`@Deprecated` existentes** (`StatusProcesso.getBootstrapBadge()`,
  `SituacaoPedidoView.classeCor()`, `PainelLinha.CelulaMedico.cor()`):
  confirmei que continuam referenciados em templates Thymeleaf
  (`solicitante/detalhe.html`) e em testes — mantidos como estão, já
  documentados no CLAUDE.md como "deprecados mas funcionando", não é
  código morto de fato.
- **2 warnings de compilação** (`javac`, records `SituacaoPedidoView` e
  `PainelLinha.CelulaMedico`, tag `@deprecated` no Javadoc sem
  `@Deprecated` no componente canônico) — reconfirmados presentes
  (`mvn clean compile`), mesma causa raiz já documentada no relatório de
  24/08: o acessor customizado tem `@Deprecated` próprio, mas o warning
  aponta pro construtor canônico implícito do record. Suprimir de vez
  exigiria reescrever o construtor canônico por extenso só para calar um
  warning cosmético sem efeito funcional — não fiz essa mudança (mesma
  decisão já tomada e documentada antes).
- **Nenhum `th:if` + `th:unless` no mesmo elemento** e **nenhum ternário
  Thymeleaf com mais de 2-3 níveis** encontrados nos templates — as regras
  do CLAUDE.md sobre isso já estão sendo respeitadas em todo o código atual.
- **Nenhum TODO/FIXME/XXX real** encontrado em `src/main/java` (o grep
  inicial por "TODO" deu falso positivo por causa da palavra portuguesa
  "todo/todos", filtrado depois).

**Duplicação:** não encontrei duplicação de lógica de negócio genuína entre
2+ serviços que valesse a pena extrair com segurança — os padrões que o
CLAUDE.md já cita como centralizados (regra de maioria simples em
`ProcessoValidator`, CC de e-mail em `ccEmailAdicional`, estado de
"Solicita informação" em `EstadoInformacaoComplementar`, regra de decisão
auditável em `RegraDecisao`) já são, de fato, fonte única no código —
verificado por grep, não só por confiança no texto do CLAUDE.md.

**Conclusão da frente 1:** a vistoria de 24/08 (`RELATORIO-ANALISE-
ESTATICA-E-CODIGO-MORTO-2026-08-24.md`) estava correta — a base de código
está genuinamente limpa hoje. Não fiz nenhuma remoção de código nesta
sessão porque não encontrei nada que atendesse ao critério de segurança
pedido (remoção comprovadamente sem uso, incluindo testes/templates/
reflection).

---

## 2. Divergências de documentação corrigidas

Todas em `CLAUDE.md` (raiz) e `docs/INDEX.md`, edição pontual nas seções já
existentes (sem anexar seção nova solta no fim, conforme a própria regra do
arquivo).

### `CLAUDE.md`

1. **Contagem de testes desatualizada** (linha ~44): dizia "1.094 testes"
   (verificado em 2026-08-24); a contagem real hoje é **1.131 testes, 0
   falhas** (`target/surefire-reports`, verificado nesta sessão). Corrigido
   para 1.131 e adicionado aviso de que esse número sobe a cada sessão —
   reconferir em vez de confiar cegamente nele da próxima vez.
2. **Referência solta a "144 testes rápidos"** (linha ~65, na descrição do
   `.\e2e.ps1`): número desatualizado (mesma raiz do item 1). Trocado por
   uma referência à contagem citada acima, em vez de hardcodar outro número
   que também vai ficar velho.
3. **2 referências quebradas a uma seção inexistente "Recebimento fundido
   em Envio"** (linhas ~271 e ~299): o texto prometia "ver seção
   'Recebimento fundido em Envio' mais abaixo/abaixo", mas **essa seção
   nunca existe no arquivo** (`grep -n "^#.*Recebimento" CLAUDE.md` não
   acha nada) — o conteúdo real está inline, nos bullets "Fluxo em 5
   passos" e "Passo 1 (Recebimento)" logo ali por perto. Corrigido para
   apontar para os bullets certos em vez de uma seção fantasma.
4. **Referência quebrada a uma seção inexistente "Login sem bloqueio"**
   (linha ~1286): mesmo padrão — a seção nunca existiu com esse nome; o
   conteúdo real (remoção do bloqueio de 15min, decisão de 2026-07-28) está
   na seção "Sessão de 2026-07-28 (correções na VM)". Corrigido o ponteiro.
5. **Nome de agente errado**: CLAUDE.md citava o agente `saur-oracle-vm`
   para tarefas de VM, mas o agente registrado de fato se chama `oracle-vm`
   (confirmado na lista de agentes disponíveis desta sessão e na memória
   `agente-oracle-vm-so-aceita-instrucao-direta.md`). Corrigido, com nota
   explicando a divergência de nome para não confundir sessões futuras.
6. **Contagem de arquivos em `service/`/`web/` desatualizada** (linha
   ~684-685, seção de reorganização de pacotes de 2026-07-29): dizia
   "`service/` (35 arquivos) e `web/` (23 arquivos)". Hoje são **45** e
   **20** respectivamente (cresceu com features novas; parte de `web/`
   migrou para `web/dto/`). Corrigido para mostrar os dois números (época
   da reorganização vs. hoje), já que congelar o número de novo só o
   deixaria stale de novo na próxima sessão que adicionar um serviço.
7. **`scripts/` incompleto**: o texto dizia que a pasta só tem
   `testar-portas.ps1` "hoje", mas existe também `scripts/git-hooks/
   pre-commit` (hook opcional de 2026-08-21, não documentado em lugar
   nenhum até agora) — adicionado ao texto, com a explicação de que é
   opcional (precisa ser copiado manualmente pra `.git/hooks/`) e só roda
   `mvn -o compile` quando há `.java` staged (não a suíte completa).

### `docs/INDEX.md`

8. **6 relatórios de 24/08 não catalogados**: `RELATORIO-ANALISE-TECNICA-
   SAUR-2026-08-24.md`, `RELATORIO-BRECHAS-E-RISCOS-NAO-CATALOGADOS-
   SAUR-2026-08-24.md`, `RELATORIO-DIAGNOSTICO-BUGS-SAUR-2026-08-24.md`,
   `RELATORIO-ANALISE-ESTATICA-E-CODIGO-MORTO-2026-08-24.md`,
   `RELATORIO-RESPONSIVIDADE-PROGRAMATICA-SAUR-2026-08-24.md` e
   `RELATORIO-UI-E-RESPONSIVIDADE-SAUR-2026-08-24.md` existiam em `docs/`
   mas não apareciam no catálogo (`docs/INDEX.md`), cujo propósito
   declarado é listar "todo `docs/*.md`" — ficavam invisíveis pra quem
   segue a instrução do próprio CLAUDE.md de "buscar no INDEX antes de
   adivinhar". Adicionei uma seção nova "Vistorias amplas de IA externa
   (2026-08-24, não catalogadas até 2026-08-25)" com resumo de 1 linha
   cada, incluindo um aviso importante: o achado principal de
   `RELATORIO-BRECHAS-...` (sessão HTTP de usuário inativado continuava
   ativa) **já estava corrigido no mesmo dia** (`revogarSessoesAtivas`) —
   o relatório foi escrito antes da correção entrar, então descreve como
   "em aberto" algo que já não está; sem esse aviso, alguém lendo só aquele
   relatório isolado chegaria a uma conclusão errada sobre o estado atual
   do sistema.

### Verificado e confirmado OK (sem divergência, não alterado)

- Enum `StatusProcesso` tem exatamente os 6 valores documentados
  (`SOLICITADO`, `ENVIADO`, `SOLICITA_INFORMACAO`, `DEFERIDO`,
  `INDEFERIDO`, `CANCELADO`) — `EM_ANALISE` de fato não existe mais.
- `OrigemParecer` tem só `AVALIADOR_SISTEMA` — `OPERADOR_EMAIL` de fato
  não existe mais.
- `TipoAnexo` bate exatamente com a lista do CLAUDE.md; `RESPOSTA_AVALIADOR`,
  `SOLICITACAO_RECEBIDA`, `CAPA_PROCESSO`, `EMAIL_ENVIADO_AVALIADORES` de
  fato não existem mais no enum (só sobra 1 menção em javadoc/comentário
  histórico, correta).
- IP da VM (`163.176.30.222`) consistente entre `CLAUDE.md`,
  `deploy/README-deploy.md` e `.github/workflows/deploy.yml`.
- Todos os arquivos `docs/*.md` referenciados de dentro do `CLAUDE.md`
  existem de fato (nenhum link morto).
- Todos os arquivos citados dentro de `docs/INDEX.md` existem de fato.
- Raiz do repositório bate com a descrição "só o essencial" (`pom.xml`,
  `CLAUDE.md`, `README.md`, scripts de uso diário, `.gitignore`/
  `.gitattributes`); nenhum resquício de `release.ps1`/`package-desktop.ps1`/
  `dist/`/`node_modules`/`package-lock.json`.
- Símbolos centrais citados pelo CLAUDE.md (`AVALIADORES_POR_PROCESSO`,
  `temVotoCoordenadorFavoravel`, `regraAplicada`, `eraCoordenadorNoVoto`,
  `ccEmailAdicional`, `EstadoInformacaoComplementar`, `ConflitoEquipeMatcher`,
  `PasswordResetService`, `EmailDominioValidator`,
  `EmailDominioInvalidoException`, `revogarSessoesAtivas`,
  `SessaoInvalidaException`, etc.) todos existem no código com esse nome
  exato.
- Porta 3000, login `admin`/`Admin123!`, `AdminBootstrap` — confirmados em
  `application.yml`.

---

## 3. Itens não mexidos por incerteza (nada nesta sessão)

Não encontrei nenhum item onde tive dúvida genuína sobre uso (reflection,
referência só por nome em template, etc.) e decidi não mexer por
segurança — todos os "candidatos a código morto" investigados se
confirmaram como falsos positivos com evidência clara (uso real
encontrado), então não há uma lista de "risco não assumido" nesta sessão.

---

## Resumo para commit (se o usuário pedir)

Nenhuma mudança de comportamento. Só `CLAUDE.md` e `docs/INDEX.md`
editados para corrigir 8 divergências pontuais (contagem de testes,
2 referências cruzadas quebradas, nome de agente errado, contagem de
arquivos por pacote desatualizada, `scripts/git-hooks/pre-commit` não
documentado, e 6 relatórios de 24/08 fora do catálogo). Suíte de testes
inalterada: 1.131 testes, 0 falhas.

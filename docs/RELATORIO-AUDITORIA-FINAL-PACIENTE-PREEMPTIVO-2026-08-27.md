# Relatório — Auditoria final da feature "paciente preemptivo" (2026-08-27)

Auditoria independente, do zero, de TUDO que foi implementado para o
**paciente preemptivo** (inserção em lista de espera renal) ao longo de
5 rodadas de trabalho, feitas por duas sessões diferentes:

| # | Entrega | Commit(s) |
|---|---|---|
| 1 | PR #126 — feature base (campo, RGCT condicional, série `P-NN/AAAA`, `RotuloProcesso`, badges no Portal do Avaliador, sem comprovante SNT) | `0f02c68`, `a11f7bf` |
| 2 | PR #127 — checkbox único + badge de regra de decisão vazando no Painel | `2bfa38b`, `9aafeae` |
| 3 | Hotfix manual em produção — `paciente_rgct` `NOT NULL` residual destravado | (SQL na VM) |
| 4 | PR #128 — trava de duplo-submit + guarda de 15s + badge em 3 telas | `8c10857` |
| 5 | Fixes da outra sessão (merge direto em `main`) | `c7a80ee`, `f70a935`, `6a0ad43`, `b949038`, `aa0cd23` |

Base auditada: `main` em `5063288`. **Nada foi corrigido nesta tarefa** —
este documento é só o levantamento.

---

## 1. Resumo executivo

**A feature está funcional e coerente de ponta a ponta.** O fluxo completo
foi reproduzido num navegador real (Playwright, H2/dev): solicitação
preemptiva sem RGCT → triagem → conversão com número `P-01/2026` → envio aos
3 avaliadores → voto FAVORÁVEL do coordenador CET-RS (deferimento isolado) →
etapa "Comprovante SNT" marcada como *"Não se aplica"* → resposta ao
solicitante liberada. Nenhum passo travou, nenhum 500, nenhum vazamento de
identidade do paciente ao avaliador. Suíte completa verde: **1195 testes,
0 falhas** (JDK 21).

**Nenhum achado crítico.** Foram encontrados **3 achados importantes**
(1 bug de UX real e reproduzido, 2 textos que contradizem a própria regra
do preemptivo), **7 pontos cosméticos/de consistência** e **2 lacunas de
cobertura de teste** que o plano original exigia explicitamente e que não
foram escritas.

| Severidade | Qtd | Achados |
|---|---|---|
| Crítico | 0 | — |
| Importante | 3 | A1 (sugestão de número nunca troca), A2 ("Comprovante SNT ainda sendo providenciado" em preemptivo), A3 (e-mail de triagem diz "urgência renal") |
| Cosmético / consistência | 7 | A4 a A10 |
| Lacuna de teste | 2 | A11, A12 |

Nenhuma inconsistência entre rodadas foi encontrada — nenhum fix posterior
desfez outro anterior (detalhe na seção 5).

---

## 2. Checklist camada por camada

### 2.1 Domain

| Item | Veredito | Evidência |
|---|---|---|
| `Processo.preemptivo` `Boolean` nullable + `isPreemptivo()` null-safe | PASSOU | `domain/Processo.java:91-92`, `:395-397` |
| `SolicitacaoOnline.preemptivo` idem | PASSOU | `domain/SolicitacaoOnline.java:63-64`, `:236-238` |
| `RascunhoSolicitacaoOnline.preemptivo` idem, sem obrigatoriedade | PASSOU | `domain/RascunhoSolicitacaoOnline.java:77-78`, `:191-193` |
| `pacienteRgct` sem `@NotBlank` nas 3 entidades (só `@Size`) | PASSOU | `Processo.java:62-74`, `SolicitacaoOnline.java:48-56` (javadoc explica o motivo) |
| Getter cru `getPreemptivo()` nunca usado em regra/template | PASSOU | Só em 2 cópias de propagação (`ProcessoDetalheController.java:240`, `SolicitanteController.java:206`) e 1 assert de teste — em ambos o `null` é propagado de propósito |
| Coluna nullable em produção | PASSOU | SQL na VM: `preemptivo`/`paciente_rgct` = `is_nullable: YES` nas 3 tabelas |

### 2.2 Numeração (série separada)

| Item | Veredito | Evidência |
|---|---|---|
| `findMaxSequencialByAnoEPreemptivo` com `coalesce(preemptivo,false)` | PASSOU | `repository/ProcessoRepository.java:105-107` |
| `proximoNumero(ano, preemptivo)` prefixa `P-` | PASSOU | `service/ProcessoService.java:121-126` |
| `cadastrar` usa a série do tipo | PASSOU | `ProcessoService.java:158-161` |
| `extrairSequencial` tolera `P-` e cai no fallback da série certa | PASSOU | `ProcessoService.java:985-1004` |
| Regex + checagem cruzada de prefixo × tipo | PASSOU | `web/ProcessoDetalheController.java:339-357` |
| Troca de tipo antes do envio reemite número + audita `PROCESSO_TIPO_ALTERADO` | PASSOU | `ProcessoService.java:400-420` |
| Troca de tipo depois do envio rejeitada | PASSOU | `ProcessoService.java:408-411` + `ProcessoDetalheController.java:863-869` |
| Nome de arquivo/pasta com `P-` (`/` já era trocado por `-`) | PASSOU | `SolicitacaoAvaliadorService.java:42`, `AnexoStorageService.java:58-59`, `NomePadraoAnexo.java:67-68` |
| **Sugestão de número no formulário troca ao alternar o tipo** | **FALHOU** | `static/js/processo-form.js:145` — ver achado **A1** |

### 2.3 Validação condicional do RGCT

| Item | Veredito | Evidência |
|---|---|---|
| `SolicitacaoOnlineService.criar` exige RGCT só quando não preemptivo + normaliza `""`→`null` | PASSOU | `service/SolicitacaoOnlineService.java:485-494` |
| `ProcessoDetalheController.salvar` (criação) | PASSOU | `:358-366` |
| `ProcessoDetalheController.atualizar` (edição) | PASSOU | `:852-855` |
| `ProcessoService.atualizarDados` normaliza RGCT para `null` quando preemptivo | PASSOU | `ProcessoService.java:427` |
| `identificacao()` / dossiê / redação de dados sensíveis já null-safe | PASSOU | `Processo.java:299-300`, `SolicitacaoOnline.java:188-189` |

### 2.4 `RotuloProcesso` (fonte única) e rótulos vazando

| Item | Veredito | Evidência |
|---|---|---|
| `prefixoAssunto` aplicado a TODOS os assuntos de e-mail do processo | PASSOU | `EmailTemplateService.java:83-88`; todos os 8 pontos de assunto passam por `assunto(p, ...)` (`:177,223,256,290,332,379,414,494,522`) |
| `nomeLongo` / `rotuloDataClinica` / `rotuloComprovanteSnt` nos e-mails | PASSOU | `EmailTemplateService.java:214,216,286,328,369,371` |
| Carimbo página a página do PDF do avaliador | PASSOU | `SolicitacaoAvaliadorService.java:154` |
| Relatório Final (título, seção 2, rótulo da data) | PASSOU | `RelatorioService.java:169-170,211-212,242,255` |
| Relatório Anual (coluna Tipo + linha de resumo + capa) | PASSOU | `RelatorioAnualService.java:186-189,216-241,328` |
| Prompt de IA (resumo de anexo, ofício) | PASSOU | `ProcessoAnexoController.java:452`, `ProcessoDecisaoController.java:412` |
| Ofício (PDF + rascunho RTF) | ATENÇÃO | `OficioService.java:71-72` usa ternário inline em vez de `RotuloProcesso` (texto certo, fonte única furada) — **A6** |
| Dossiê ZIP (`ExportacaoProcessoService`) | ATENÇÃO | `:216-218` e `:243` — mesmo caso — **A6** |
| `ProcessoService.rotuloTipo` duplica `RotuloProcesso.tipoCurto` | ATENÇÃO | `ProcessoService.java:981-983` — **A7** |
| **E-mail interno de nova solicitação** | **FALHOU** | `SolicitacaoOnlineService.java:602` — **A3** |
| **Aviso "Comprovante SNT ainda sendo providenciado"** | **FALHOU** | `solicitante/detalhe.html:86` — **A2** |
| Textos gerais ("Painel da Urgência Renal", rodapé, navbar, Relatório Anual, assinatura) mantidos | PASSOU (por decisão §9.1) | `dashboard.html:21`, `layout.html`, `EmailProperties.assinatura` |

### 2.5 Comprovante SNT (não se aplica ao preemptivo)

| Item | Veredito | Evidência |
|---|---|---|
| `FluxoProcessoService` nem cria a etapa | PASSOU | `FluxoProcessoService.java:313-326` |
| `ProcessoValidator.validarRespostaSolicitante` não exige | PASSOU | `ProcessoValidator.java:361-370` |
| `ProcessoService.finalizarResposta` envia sem anexo | PASSOU | `ProcessoService.java:825-834` |
| `POST /{id}/comprovante-snt` recusa preemptivo | PASSOU | `ProcessoAnexoController.java:212-224` + teste `ProcessoAnexoControllerTest.uploadComprovanteSntBloqueadoSeProcessoPreemptivo` |
| Botão "Enviar Resposta ao Solicitante" liberado | PASSOU | `processos/detalhe.html:1386-1389` (fix `c7a80ee`) + confirmado visualmente |
| Contadores/scheduler de "Deferido sem comprovante SNT" excluem preemptivo | PASSOU | `ProcessoRepository.java:132,148,163,181` (`coalesce(preemptivo,false) = false`) |
| E-mail pronto manual (`prepararEmailPronto`) não bloqueia nem promete anexo | PASSOU | `ProcessoDecisaoController.java:699-715` (usa o mesmo validator; `buscarUltimoPorTipo` devolve `null`) |

### 2.6 Badges / indicadores visuais por tela

| Tela | Veredito | Evidência |
|---|---|---|
| `processos/lista.html` (badge + filtro) | PASSOU | `:38`, `:93` |
| `processos/detalhe.html` (badge no cabeçalho + rótulo de data + subpasso "não se aplica") | PASSOU | `:20-21`, `:309`, `:1290-1301` |
| `processos/form.html` / `editar.html` (checkbox + RGCT condicional + sugestão) | PASSOU (menos A1) | `form.html:27-46`, `editar.html:17-33,54-57` |
| `processos/solicitacoes-online-lista.html` / `-detalhe.html` | PASSOU | `:66` / `:19,62` |
| `arquivo/lista.html` (badge + filtro) | PASSOU | `:35`, `:72` |
| `dashboard.html` (badge na linha + card "Preemptivos") | PASSOU | `:247`, `:120-131` |
| `avaliador/lista.html` (badge por linha em 7 blocos + contador + card) | PASSOU | `:61,133,209,265,306,360,422,476,524` |
| `avaliador/votar.html` (título explícito + badge + textos dos votos) | PASSOU | `:36-42,189-190,309-315` |
| `solicitante/lista.html` (tabela + card mobile) | PASSOU | `:173`, `:267` |
| `solicitante/detalhe.html` (badge no `<h1>`) | PASSOU | `:26` |
| `membros/lista.html` (badge "preemptivos" por avaliador) | PASSOU | `:98-101` |
| Cor do card "Preemptivos" x card vizinho | ATENÇÃO | `app.css:942-943` — **A4** |
| Linha "RGCT / SNT" vazia no Portal do Solicitante | ATENÇÃO | `solicitante/detalhe.html:204-205` — **A5** |

### 2.7 Ordenação das listas (fix `f70a935`)

| Item | Veredito | Evidência |
|---|---|---|
| `/processos` e `/arquivo` ordenam por `dataCadastro desc nulls last` antes de `sequencial desc` | PASSOU | `ProcessoRepository.java:237`, `:300` |
| Painel usa o mesmo critério em memória | PASSOU | `HomeController.java:69-74` |
| Caso NÃO preemptivo não regrediu | PASSOU | `dataCadastro` é preenchido no construtor (`Processo.java:252`), então processos comuns novos continuam no topo; linhas legadas sem a coluna caem no fim do próprio ano por `nulls last` (todas já encerradas) |
| Relatório Anual continua por `sequencial asc` | ATENÇÃO | `ProcessoRepository.java:35` — as duas séries se intercalam (dois "1" no mesmo ano). Cosmético, a coluna "Tipo" desambigua — **A8** |

### 2.8 Duplo-submit (PR #128)

| Item | Veredito | Evidência |
|---|---|---|
| Todo template com `data-lock-submit` inclui `layout :: lockSubmitScript` | PASSOU | 5/5: `solicitante/nova.html:71,364`, `solicitante/detalhe.html:110,374`, `processos/detalhe.html:1642`, `processos/editar.html:12,122`, `processos/form.html:12,164` — nenhum wiring pela metade sobrou |
| Guarda de 15s no backend (usuário + CPF) | PASSOU | `SolicitacaoOnlineService.java:479-484` + `SolicitacaoOnlineServiceTest` |
| Produção: a duplicata real (ids 23/24, 9 s de intervalo) seria barrada hoje | PASSOU | SQL na VM (ver seção 4.3) |

### 2.9 Contadores e filtros nos painéis (`aa0cd23`, o menos revisado)

| Item | Veredito | Evidência |
|---|---|---|
| Filtro `?tipo=urgencia|preemptivo` normalizado no controller (valor desconhecido = todos) | PASSOU | `ProcessoListaController.java:47-50`, `ArquivoController.java:74-79` |
| Query sem o antipadrão `:param is null or ...` (Postgres) | PASSOU | `ProcessoRepository.java:227`, `:295` (2 booleanos não-nulos) |
| Legado `preemptivo = NULL` conta como urgência renal no filtro | PASSOU | `coalesce(p.preemptivo,false)` + teste `PacientePreemptivoIntegrationTest.buscarFiltraPorTipoIncluindoLegadoNuloComoUrgenciaRenal` |
| Filtro preservado na paginação | PASSOU | `processos/lista.html:136,150`, `arquivo/lista.html:116,126` |
| Card "Preemptivos" do Painel conta o ANO CORRENTE | PASSOU | `HomeController.java:61,120-125` |
| Card linka `/processos?tipo=preemptivo` (sem recorte de ano) | ATENÇÃO | Mesma ambiguidade dos cards Deferidos/Indeferidos já existentes — não é regressão — **A9** |
| Portal do Avaliador: "(N preemptivos)" pendentes + "Preemptivos avaliados" | PASSOU | `AvaliadorController.java:257-262,290-291`; `ParecerRepository.countByMembroIdAndResultadoNotNullAndProcesso_PreemptivoTrue` (o `...PreemptivoTrue` exclui `null`, que é o correto) |
| Relatório Anual: linha de resumo + citação na capa | PASSOU | `RelatorioAnualService.java:186-189`, `:240-241` + `RelatorioAnualServiceTest` |
| `?tipo` combinado com `?filtro=snt-pendente` | ATENÇÃO | `ProcessoListaController.java:52-57` ignora o tipo nesse ramo, mas o `<select>` continua mostrando o valor escolhido — **A10** |

### 2.10 Portal do Avaliador / imparcialidade

| Item | Veredito | Evidência |
|---|---|---|
| Flag chega só como `boolean preemptivo` nos records projetados | PASSOU | `AvaliadorController.java:940,957-958,966-967,976` |
| Nenhuma entidade `Processo`/`Parecer` exposta ao template | PASSOU | mesmos records |
| Nome/CPF/equipe do paciente nunca aparecem | PASSOU | confirmado visualmente (`avaliador/votar.html` mostra "P.P.T.") |

### 2.11 Testes

| Item | Veredito | Evidência |
|---|---|---|
| Suíte completa | PASSOU | `mvn clean test` (JDK 21): **1195 testes, 0 falhas**, BUILD SUCCESS |
| Cobertura dedicada (RGCT condicional, séries, troca de tipo, legado nulo, filtro, comprovante SNT) | PASSOU | `PacientePreemptivoIntegrationTest` (13 testes) + `ProcessoAnexoControllerTest`, `HomeControllerTest`, `ArquivoControllerTest`, `ProcessoListaControllerTest`, `AvaliadorControllerTest`, `SolicitanteControllerTest`, `RelatorioAnualServiceTest`, `EmailTemplateServiceTest` |
| Propagação `preemptivo` na CONVERSÃO (solicitação → processo) | **FALTA** | **A11** |
| Regressão de decisão (2/3 e coordenador) em processo preemptivo | **FALTA** | **A12** |

---

## 3. Achados

### A1 — [IMPORTANTE] A sugestão de número nunca troca ao alternar o tipo no formulário de conversão

**Descrição.** O plano (§5.7/§9.2) e o comentário do próprio controller
dizem que "o JS troca a sugestão no campo `numero` ao alternar o rádio de
tipo, sem sobrescrever algo já digitado". Na prática o JS **nunca** troca,
porque o campo nunca está vazio: no regime manual (2026) o controller já
pré-preenche o `numero` ao montar o formulário.

**Evidência (código real).**

`web/ProcessoDetalheController.java:256-258` (GET `/processos/novo`):
```java
if (!automatica) {
    p.setNumero(p.isPreemptivo() ? proximoNumeroPreemptivo : proximoNumeroUrgencia); // sugestao editavel
}
```

`static/js/processo-form.js:143-150`:
```java
// Sugestao de numero: so preenche se o campo ainda estiver vazio (nunca
// sobrescreve algo ja digitado pelo operador - §5.7/§9.2 do plano).
if (campoNumero && !campoNumero.value.trim()) {
```

**Evidência empírica (Playwright, nesta auditoria).** No formulário de
conversão de uma solicitação preemptiva, desmarcar e remarcar o checkbox
deixou o campo inalterado nas duas transições:
```
AUDITORIA >>> numero sugerido no form de conversao = 'P-01/2026' | checkbox preemptivo marcado = true
AUDITORIA >>> numero apos alternar o checkbox = 'P-01/2026'
```

**Impacto.** Não é destrutivo (a checagem cruzada de prefixo do
`ProcessoDetalheController.salvar:347-352` barra o POST), mas todo operador
que marcar/desmarcar o tipo na tela leva um erro de validação
("Processo preemptivo precisa de numero no formato P-NN/AAAA" ou "Número
de processo de urgência renal não pode ter o prefixo P-") e precisa
corrigir o número na mão — exatamente o atrito que a sugestão automática
existia para evitar. Vale enquanto a numeração for manual (2026).

**Sugestão de correção (não aplicada).** Trocar a condição para "preenche
quando o campo está vazio **ou** quando o valor atual é exatamente a
sugestão da OUTRA série" — continua nunca sobrescrevendo um número digitado
de verdade pelo operador.

---

### A2 — [IMPORTANTE] "Comprovante SNT ainda sendo providenciado pela equipe" aparece em processo preemptivo Deferido

**Descrição.** No Portal do Solicitante, o cartão de resultado de um pedido
preemptivo DEFERIDO diz corretamente que "esta decisão autoriza a equipe a
proceder com a inscrição…", e logo abaixo exibe o aviso
*"Comprovante SNT ainda sendo providenciado pela equipe."* — um documento
que, por definição, **nunca existirá** nesse processo. Como
`situacao.anexoParaBaixar` é sempre `null` no preemptivo, o aviso fica ali
para sempre, mesmo com o processo 100% concluído.

**Evidência.** `templates/solicitante/detalhe.html:79-88`:
```html
<span th:unless="${situacao.anexoParaBaixar != null}" class="small">
    <span th:if="${situacao.rotulo == 'Deferido'}">Comprovante SNT ainda sendo providenciado pela equipe.</span>
```
O `rotulo` é `"Deferido"` para os dois tipos (`SolicitanteController.java:562`)
— só o `titulo` é condicional. Confirmado visualmente no screenshot
`preempt-13-solicitante-detalhe-final.png` desta auditoria.

**Impacto.** Mensagem falsa a um usuário externo (equipe solicitante), que
contradiz o texto do próprio cartão logo acima e sugere uma pendência
inexistente da Central.

**Sugestão de correção (não aplicada).** Condicionar o `<span>` a
`!solicitacao.isPreemptivo()`, ou (melhor, mantendo a fonte única) expor no
`SituacaoPedidoView` um campo do tipo `avisoDocumentoPendente` já calculado
no `SolicitanteController`, junto do resto da redação condicional que a
rodada `6a0ad43` centralizou ali.

---

### A3 — [IMPORTANTE] E-mail interno de nova solicitação diz sempre "urgência renal"

**Descrição.** O aviso enviado a ADMIN/OPERADOR quando chega uma solicitação
nova abre com "Uma nova solicitacao de **urgencia renal** foi enviada pelo
Portal do Solicitante", mesmo quando o pedido é preemptivo. O assunto
("Nova solicitacao online aguardando triagem - <paciente>") também não
distingue o tipo.

**Evidência.** `service/SolicitacaoOnlineService.java:601-610`:
```java
String corpo = """
    Uma nova solicitacao de urgencia renal foi enviada pelo Portal do Solicitante.
```
Mitigado (mas não corrigido) pela linha `Tipo: Preemptivo (inserção em lista
de espera)` no bloco de identificação (`:585-586`) — a frase de abertura
continua contradizendo o corpo.

**Impacto.** Baixo/moderado: é e-mail interno e a linha "Tipo" está lá; mas
é justamente o caso em que a rodada `6a0ad43` foi caçar "rótulos vazando" e
este escapou.

**Sugestão de correção (não aplicada).** Usar
`RotuloProcesso.tipoPedido(s.isPreemptivo())` na frase de abertura e citar o
tipo no assunto.

---

### A4 — [COSMÉTICO] Card "Preemptivos" tem exatamente a mesma cor do card vizinho

**Descrição.** `stat-card-preemptivo` foi definido com a paleta idêntica a
`stat-card-membros`, e os dois ficam **lado a lado** no Painel. O mesmo
acontece no Portal do Avaliador ("Preemptivos avaliados" x "Atribuídos a
mim", ambos azuis). Isso colide com a regra fixa do projeto
("cada opção lado a lado usa SUA PRÓPRIA cor semântica").

**Evidência.** `static/css/app.css:942-943`:
```css
.stat-card-membros   { --stat-bg: var(--rs-blue-light); --stat-border: var(--rs-blue); ... }
.stat-card-preemptivo{ --stat-bg: var(--rs-blue-light); --stat-border: var(--rs-blue); ... }
```
Confirmado visualmente (`preempt-10-painel.png`, `preempt-06-avaliador-lista.png`).
Some-se a isso que o **badge** de tipo, em todas as 11 telas, é dourado
(`bg-warning text-dark`) — o mesmo conceito tem duas cores diferentes
dependendo do componente.

**Sugestão (não aplicada).** Dar ao card de preemptivo um token próprio,
preferencialmente alinhado ao dourado já usado no badge (o dourado significa
"atenção" no design system, então convém decidir com o dono do produto se o
tipo deve mesmo ser "atenção" — hoje o badge já assume que sim).

---

### A5 — [COSMÉTICO] Linha "RGCT / SNT" renderizada vazia no Portal do Solicitante

**Evidência.** `templates/solicitante/detalhe.html:204-205` imprime o `<dt>`
e um `<dd>` sem valor quando o pedido é preemptivo (o plano previa tornar a
linha condicional). Visível no screenshot `preempt-13`. As demais telas
(`processos/detalhe.html`) já mostram `-`, e a fila de triagem
(`solicitacoes-online-lista.html:69`) mostra a célula vazia — aceitável,
mas o Portal externo é o pior lugar para um rótulo órfão.

---

### A6 — [COSMÉTICO] Dois documentos usam ternário inline em vez de `RotuloProcesso`

**Evidência.** `service/OficioService.java:71-72`
(`p.isPreemptivo() ? "INSERÇÃO EM LISTA DE ESPERA RENAL" : "URGÊNCIA RENAL"`)
e `service/ExportacaoProcessoService.java:216-218` e `:243`. Os textos estão
corretos hoje; o risco é de divergirem de `RotuloProcesso` numa futura
mudança de vocabulário — exatamente o que a classe existe para impedir
(o javadoc dela é explícito: *"Nunca espalhar `if (p.isPreemptivo())`…"*).

---

### A7 — [COSMÉTICO] `ProcessoService.rotuloTipo` duplica `RotuloProcesso.tipoCurto`

**Evidência.** `service/ProcessoService.java:981-983` — método privado com
o mesmo par de strings, usado só na auditoria `PROCESSO_TIPO_ALTERADO`.
Mesma observação de A6.

---

### A8 — [COSMÉTICO] Relatório Anual continua ordenado por `sequencial asc`

**Evidência.** `repository/ProcessoRepository.java:30-37`
(`findByAnoComPareceres`, `order by p.sequencial asc`). Com duas séries
independentes, o ano tem dois "1", dois "2" etc., e a tabela do PDF
intercala urgência e preemptivo. A coluna "Tipo" (já implementada)
desambigua, então é só apresentação — mas se o setor esperar os preemptivos
agrupados, é aqui que se resolve.

---

### A9 — [COSMÉTICO] Card "Preemptivos" conta o ano corrente e linka uma lista sem recorte de ano

**Evidência.** `HomeController.java:61` (só processos do ano) x
`dashboard.html:121` (`/processos(tipo='preemptivo')`, todos os anos).
**Não é regressão**: os cards Deferidos/Indeferidos/Cancelados já se
comportam assim desde antes. Fica registrado por ser o tipo de divergência
que gera pergunta do usuário ("o card diz 1 e a lista mostra 3").

---

### A10 — [COSMÉTICO] `?tipo` é ignorado quando `?filtro=snt-pendente` está ativo

**Evidência.** `web/ProcessoListaController.java:52-57` — no ramo
`sntPendente` a lista vem de `listarDeferidosSemComprovanteSnt()` (que já
exclui preemptivos por definição), mas o `<select>` de tipo continua exibindo
o valor escolhido (`model.addAttribute("tipoSelecionado", tipoFiltro)` roda
nos dois ramos). O usuário pode achar que o filtro está aplicado.

---

### A11 — [LACUNA DE TESTE] Propagação de `preemptivo` na conversão não tem teste

O plano (§8.6) exigia explicitamente: *"Conversão `SolicitacaoOnline` →
`Processo` propaga `preemptivo` (reler do banco e conferir campo a campo)"*.
`ProcessoDetalheController.java:240` faz a cópia, mas nenhum teste a assere —
`ProcessoAtualizacaoIntegrationTest` cobre `atualizarDados`, não a conversão.
É exatamente a família de bug ("campo esquecido no copy") que o `CLAUDE.md`
registra como já ocorrida 3×.

---

### A12 — [LACUNA DE TESTE] Nenhuma regressão automatizada de decisão em processo preemptivo

O plano (§8.6) pedia um teste provando que maioria simples 2/3 e a exceção do
coordenador funcionam idênticas em processo preemptivo (isto é, que o tipo
não vazou para a lógica de decisão). Não existe: `PacientePreemptivoIntegrationTest`
cobre RGCT, numeração, troca de tipo, legado nulo, comprovante SNT e filtro,
mas nenhum `decidir`/voto. O caminho foi validado **manualmente** nesta
auditoria (voto único do coordenador deferiu o `P-01/2026`), o que reforça
que a regra está certa — falta só o teste que impede a recaída.

---

## 4. Verificações executadas

### 4.1 Suíte de testes

```
mvn clean test   (JAVA_HOME = jdk-21.0.11+10)
Tests run: 1195, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS (02:05 min)
```

### 4.2 Reprodução visual (Playwright, Chromium real, H2/dev)

Fluxo preemptivo completo percorrido clicando na tela, com um IT temporário
(criado só para esta auditoria e **removido depois**, nada commitado):
login do solicitante → `#tipoPreemptivo` marcado → bloco RGCT some →
envio → triagem → conversão (`P-01/2026`) → documento clínico → envio aos
3 avaliadores → coordenador CET-RS vota FAVORÁVEL → deferimento isolado →
Finalização → Portal do Solicitante. Resultado: **passou**, sem erro de
JS, sem 500. Screenshots em `target/e2e-screenshots/preempt-*.png`.

Confirmado nas telas:
- badge "Preemptivo" no cabeçalho do processo, na linha do Painel, na lista
  do avaliador e no `<h1>` do Portal do Solicitante;
- "Emitir parecer — Inserção em Lista de Espera Renal (Preemptivo) — este
  processo NÃO é uma urgência renal" no formulário de voto, com paciente
  só por iniciais (`P.P.T.`);
- textos de voto condicionais ("Concordo com a inserção do paciente na lista
  de espera renal");
- badge "Deferido pelo Coordenador da CET-RS" + `RegraDecisao` correta;
- subpasso "Comprovante SNT — Não se aplica" e botão "Enviar Resposta ao
  Solicitante" **habilitado**;
- assunto do e-mail: `Lista de Espera Renal - Processo P-01/2026 - Deferido`,
  corpo sem promessa de anexo e com a redação de "autoriza a equipe a
  proceder com a inscrição".

Observação: a etapa de envio da resposta falhou com *"Falha ao enviar e-mail…
Verifique a configuracao de SMTP"* — comportamento **correto e esperado** no
ambiente de teste (sem remetente configurado; `finalizarResposta` faz
rollback de propósito), não um achado.

### 4.3 Produção (somente leitura, via SSH/psql na VM)

```
 id |  numero   | ano  | seq | preemptivo | rgct_nulo |  status
 20 | P-01/2026 | 2026 |   1 | t          | t         | DEFERIDO   (email_enviado_solicitante = t, 3 anexos)
 19 | 14/2026   | 2026 |  14 | (null)     | f         | ENVIADO
 ... demais processos com preemptivo = NULL (legado), série intacta
```
- Schema: `preemptivo` e `paciente_rgct` **nullable** em `processo`,
  `solicitacao_online` e `rascunho_solicitacao_online` — o hotfix do
  `NOT NULL` residual está de fato aplicado, nada regrediu.
- O único processo preemptivo real (`P-01/2026`) está íntegro: RGCT nulo,
  deferido, resposta ao solicitante já enviada.
- As duas solicitações duplicadas do relato original continuam no banco
  (ids 23 e 24, 9 segundos de intervalo); a 23 foi **cancelada**
  manualmente. Com a guarda de 15 s de hoje, a segunda teria sido barrada.
- Solicitação `id 25` (preemptiva) está `ENVIADA`, aguardando triagem — vale
  avisar a equipe, é trabalho real pendente na fila.

---

## 5. Consistência entre as rodadas

Foram comparados os diffs das 5 rodadas em busca de trabalho desfeito. **Não
há nenhuma reversão nem sobreposição destrutiva:**

- `c7a80ee` (template da Finalização), `f70a935` (ordenação no repositório +
  Painel), `6a0ad43` (rótulos), `b949038` (textos de IA/solicitante) e
  `aa0cd23` (contadores/filtros) tocam conjuntos de linhas disjuntos;
  o único arquivo tocado por duas rodadas seguidas é
  `ProcessoRepository.java` (`f70a935` mexeu no `order by`, `aa0cd23`
  adicionou o `where` do filtro) — as duas mudanças coexistem corretamente
  na query final (`:224-241`).
- `SolicitanteController` foi editado por `6a0ad43` (rótulos do resultado) e
  antes por #126/#128; a versão atual (`:495-604`) mantém as duas coisas.
- O fix do PR #127 (badge de regra de decisão vazando no Painel) segue
  aplicado; o Painel renderizado nesta auditoria mostra os badges em
  colunas corretas.
- A única "meia-implementação" remanescente é a do achado **A1**, e ela vem
  do PR #126 original — nenhuma rodada posterior a desfez, apenas nenhuma a
  completou.

---

## 6. Conclusão

A feature pode ser considerada **entregue e correta nas suas regras de
negócio**: nenhuma invariante do `Processo`/`Parecer` foi afrouxada, a
numeração em série separada está isolada de verdade, o RGCT condicional não
quebra escrita de linha legada (nem em produção), o comprovante SNT deixou
de ser exigido nos 4 pontos que importam (checklist, validator, serviço e
endpoint) e o avaliador vê o tipo sem ver o paciente.

Para fechar 100%, na ordem de retorno: **A2** (mensagem falsa ao solicitante
externo), **A1** (atrito real do operador em toda conversão com troca de
tipo), **A3**, depois **A11/A12** (os dois testes que o plano já previa) e,
por último, os cosméticos A4–A10 — sendo A4 uma decisão de design que
convém confirmar com o dono do produto antes de mexer.

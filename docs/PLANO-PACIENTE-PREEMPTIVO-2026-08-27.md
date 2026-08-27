# Plano: paciente preemptivo (Inserção em Lista de Espera Renal)

**Status: PLANEJADO — nada implementado.** Este documento é só investigação +
desenho. Nenhum arquivo de código foi alterado na sessão que o produziu.

Data: 2026-08-27.

---

## 1. Resumo executivo

O dono do produto quer introduzir um **segundo tipo de paciente** no fluxo já
existente: o **paciente preemptivo**.

**O que ele é:** um paciente que ainda **não está na lista de espera** do
Sistema Nacional de Transplantes (SNT). O processo dele **não é uma urgência
renal** — serve para avaliar a **inserção do paciente na lista de espera renal
(preemptiva)**. Ele é julgado pela mesma equipe, no mesmo sistema, com o mesmo
rito.

### O que NÃO muda (invariantes preservados — não relaxar)

- **Exatamente 3 médicos avaliadores** por processo
  (`ProcessoService.AVALIADORES_POR_PROCESSO = 3`).
- **Maioria simples 2 de 3**: ≥2 favoráveis = DEFERIDO; ≥2 desfavoráveis =
  INDEFERIDO (`ProcessoValidator.FAVORAVEIS_PARA_DEFERIR` /
  `DESFAVORAVEIS_PARA_INDEFERIR`).
- **Exceção do coordenador CET-RS** (defere sozinho com voto favorável), com
  o snapshot `Parecer.eraCoordenadorNoVoto` e o
  `RegraDecisao.VOTO_COORDENADOR`.
- **Pausa `SOLICITA_INFORMACAO`**, retomada, múltiplos pedidos simultâneos.
- **Fluxo em 5 passos** (`FluxoProcessoService`), trava de processo encerrado,
  reabertura só por ADMIN.
- **Portal do Avaliador como único caminho de voto**, com **iniciais apenas**
  (imparcialidade).
- Ofício de indeferimento anexado manualmente; anexo obrigatório na etapa 5.
- Conflito de equipe, chat, auditoria, e-mail adicional (CC), campos de
  identificação do paciente (CPF, data de nascimento, sexo, nome da mãe).

**Nenhuma linha de `ProcessoValidator`, `ProcessoService.decidir`,
`AvaliadorController.registrarVoto` ou `DecisaoAutomaticaScheduler` precisa
mudar de LÓGICA.** A mudança é de **classificação + nomenclatura + numeração +
obrigatoriedade condicional de um campo**.

### O que muda

| Eixo | Mudança |
|---|---|
| **Dados** | Campo booleano `preemptivo` em `Processo`, `SolicitacaoOnline` e `RascunhoSolicitacaoOnline`. |
| **Formulário do solicitante** | Campo novo Sim/Não ("O paciente é preemptivo?") em `solicitante/nova.html`. |
| **RGCT** | Deixa de ser obrigatório (e some da tela) quando `preemptivo = true`. |
| **Numeração** | Série numérica **separada**, formato distinto do `NN/AAAA` da urgência (ver §5). |
| **Nomenclatura** | "Urgência Renal" → **"Inserção em Lista de Espera Renal"** em toda UI/e-mail/PDF do processo preemptivo. |
| **Comprovante SNT** | Mesmo `TipoAnexo.COMPROVANTE_SNT`, com **rótulo condicional** (ver §6). |
| **Listas/filtros** | Badge de tipo + filtro "Urgência Renal / Preemptivo". |

---

## 2. Modelagem de dados (DECIDIDO)

### 2.1 Campo: booleano simples `preemptivo`

**Decisão fechada do usuário: booleano, não um enum `TipoPaciente` novo.**

```java
/**
 * Paciente PREEMPTIVO: ainda nao esta na lista de espera do SNT — o
 * processo avalia a INSERCAO dele na lista de espera renal, nao uma
 * urgencia. Nao altera nenhuma regra de votacao/decisao (3 avaliadores,
 * maioria simples 2/3, excecao do coordenador): muda a NUMERACAO, os
 * ROTULOS institucionais e a obrigatoriedade do RGCT.
 *
 * Nullable de proposito: processo/solicitacao gravado antes deste campo
 * existir fica com null == false (urgencia renal comum), sem exigir
 * backfill manual em producao — mesmo padrao ja usado em
 * Processo.reaberturas/ultimoLembreteSntEm/numeroOficio (ver CLAUDE.md,
 * "ddl-auto: update nao faz backfill em coluna nova").
 */
@Column(name = "preemptivo")
private Boolean preemptivo;

/** Null-safe: null (legado) == urgencia renal comum. */
public boolean isPreemptivo() {
    return Boolean.TRUE.equals(preemptivo);
}
```

Pontos de atenção:

- **`Boolean` (wrapper) + coluna nullable, nunca `boolean` primitivo com
  `nullable = false`.** Um `boolean` primitivo faria o Hibernate tratar a
  coluna como NOT NULL; as ~12 linhas de `processo` e as `solicitacao_online`
  já existentes em produção ficariam com NULL e **qualquer escrita nelas
  quebraria** — exatamente o incidente já documentado no `CLAUDE.md` com
  `Processo.versao` (@Version) em 2026-07-10.
- **Sempre ler via `isPreemptivo()` (helper null-safe), nunca
  `getPreemptivo()` cru** em template ou regra — `null` numa expressão
  Thymeleaf booleana é fonte clássica de NPE/falso silencioso.
- Getter/setter simples, sem Lombok (convenção do projeto).

### 2.2 Onde entra

| Entidade | Tabela | Observação |
|---|---|---|
| `Processo` | `processo` | Fonte da verdade do processo real; alimenta rótulos, numeração, PDFs, e-mails. |
| `SolicitacaoOnline` | `solicitacao_online` | Origem do dado (o solicitante é quem marca); espelhado no `Processo` na conversão. |
| `RascunhoSolicitacaoOnline` | `rascunho_solicitacao_online` | Espelha, sem nenhuma obrigatoriedade (rascunho nunca valida nada). |

**Propagação na conversão:** `ProcessoDetalheController.novo` (linhas ~234-243)
já copia campo a campo de `SolicitacaoOnline` para `Processo` — acrescentar
`p.setPreemptivo(s.getPreemptivo())` ali. Mesma família de bug já registrada 3×
no `CLAUDE.md` ("teste de atualização deve reler do banco e conferir campo a
campo") — o teste de conversão precisa asseverar esse campo explicitamente.

**Tipo editável após a conversão — DECIDIDO (§9.4): SIM, até o processo ser
enviado aos avaliadores, por ADMIN *ou* OPERADOR** (não é ação restrita a
ADMIN). `ProcessoService.atualizarDados` (linhas ~370-393) já copia campo a
campo e passa a copiar `preemptivo` também, com três consequências
obrigatórias:

1. **Janela de edição:** só enquanto o processo ainda **não foi enviado** —
   na prática, status `SOLICITADO` (antes de `registrarEnvio` promover para
   `ENVIADO`). Depois disso o tipo é imutável: os avaliadores já receberam o
   PDF carimbado com o rótulo e o número daquele tipo. A trava de
   `ProcessoValidator.edicaoBloqueada` (processo encerrado) continua valendo
   por cima, como para qualquer outro campo.
2. **Reemissão do número na série certa.** Mudar o tipo deixa o número fora da
   série (um `P-03/2027` que virou urgência renal, ou vice-versa). A troca de
   tipo **precisa reemitir o número**: `numero` recalculado por
   `proximoNumero(ano, novoTipo)` e `sequencial` recalculado junto, na **mesma
   transação** da alteração. No regime manual (2026) o operador confirma/edita
   o número sugerido; no automático (2027+) é transparente. O número antigo
   **não** é reaproveitado por outro processo (a sequência é `MAX+1`, nunca
   preenche buracos) — comportamento já existente, não é regressão.
3. **Auditoria `PROCESSO_TIPO_ALTERADO` (obrigatória, ver §8.3/§9.4):**
   registra id do processo, número antigo → número novo e tipo antigo → tipo
   novo. **Nunca** o nome do paciente (regra fixa do `CLAUDE.md`: auditoria de
   processo usa `Iniciais.de()`, e o termo de busca nunca é logado).

### 2.3 RGCT condicionalmente obrigatório

Estado atual (verificado no código):

| Local | Anotação/validação hoje |
|---|---|
| `Processo.pacienteRgct` | `@NotBlank` **na entidade** + `@Size(max=60)`, coluna nullable |
| `SolicitacaoOnline.pacienteRgct` | `@NotBlank` **na entidade** + `@Size(max=60)`, coluna nullable |
| `RascunhoSolicitacaoOnline.pacienteRgct` | só `@Size(max=60)` (rascunho não valida) |
| `solicitante/nova.html` | `required` no `<input>` |
| `processos/form.html` / `editar.html` | `required` no `<input>` |

**Problema:** `@NotBlank` na ENTIDADE é validação incondicional — o Hibernate
valida a entidade inteira a cada flush (`jakarta.persistence.validation.mode
=AUTO`). Com um processo preemptivo (RGCT nulo), **qualquer escrita**
(decidir, finalizar, anexar) estouraria `ConstraintViolationException`/500.
É **exatamente** o hotfix de produção de 2026-08-22 já documentado no javadoc
de `Processo.pacienteDataNascimento` — não repetir o erro.

**Proposta (segue o padrão já consolidado no projeto):**

1. **Remover `@NotBlank` de `Processo.pacienteRgct` e
   `SolicitacaoOnline.pacienteRgct`**, mantendo `@Size(max=60)` (null-safe).
   Registrar no javadoc o motivo, como já foi feito para os outros campos.
2. **Mover a obrigatoriedade condicional para a camada que já valida os
   demais campos:**
   - `SolicitacaoOnlineService.criar` — junto das checagens de
     `pacienteDataNascimento`/`pacienteSexo`/`pacienteCpf` (linhas ~449-465):
     ```java
     if (!solicitacao.isPreemptivo()
             && (solicitacao.getPacienteRgct() == null || solicitacao.getPacienteRgct().isBlank())) {
         throw new IllegalArgumentException("Informe o RGCT/SNT do paciente.");
     }
     if (solicitacao.isPreemptivo()) {
         solicitacao.setPacienteRgct(null); // normaliza "" -> null, nunca string vazia no banco
     }
     ```
     E acrescentar `"pacienteRgct"` ao mapa mensagem→campo do
     `SolicitanteController` (usado para destacar o campo com erro).
   - `ProcessoDetalheController.salvar`/`atualizar` — `result.rejectValue(
     "pacienteRgct", ...)`, mesmo padrão já usado ali para CPF/data de
     nascimento/sexo.
3. **Front-end (`solicitante/nova.html` + `solicitante-nova.js`):** o rádio
   Sim/Não de "preemptivo" liga/desliga o bloco do RGCT (esconde o campo e
   remove o `required` dinamicamente) — mesmo padrão de `required` dinâmico
   já usado em `avaliador-votar.js`
   (`atualizarObrigatoriedadeJustificativa`). **UX apenas** — a regra de
   verdade mora no service (o `required` do HTML é burlável via DevTools).
4. **`Processo.identificacao()` / `SolicitacaoOnline.identificacao()`** já
   omitem a parte do RGCT quando ele é nulo/em branco (linhas 270 e 172) —
   **nada a fazer**, já é null-safe.
5. **`ProcessoAnexoController.redigirDadosSensiveis`** (linha ~476) já testa
   `pacienteRgct != null && !isBlank()` — **nada a fazer**.
6. **`SolicitacaoOnlineService.notificarOperadores`** (linha 556) imprime
   `"RGCT/SNT: " + s.getPacienteRgct()` **incondicionalmente** — imprimiria
   `RGCT/SNT: null` no e-mail interno. **Precisa virar condicional**, no
   mesmo estilo das linhas de CPF/data logo abaixo, e ganhar uma linha
   `Tipo: Preemptivo (inserção em lista de espera)` quando for o caso.

---

## 3. Nomenclatura (DECIDIDO)

**Rótulo base definido pelo usuário: "Inserção em Lista de Espera Renal".**
Nome longo do processo: **"Processo de Inserção em Lista de Espera Renal
(Preemptivo)"**. Substitui "Urgência Renal" em tudo que é voltado a esse tipo
de processo.

Vocabulário derivado, a ser usado consistentemente:

| Contexto | Urgência Renal (hoje) | Preemptivo (novo) |
|---|---|---|
| Nome longo do processo | "Processo de Urgência Renal" | "Processo de Inserção em Lista de Espera Renal (Preemptivo)" |
| Título de PDF (caixa alta) | "RELATÓRIO FINAL - PROCESSO DE URGÊNCIA RENAL" | "RELATÓRIO FINAL - PROCESSO DE INSERÇÃO EM LISTA DE ESPERA RENAL" |
| Carimbo de página (linha 1) | "…RS - URGENCIA RENAL" | "…RS - INSERCAO EM LISTA DE ESPERA RENAL" (sem acento, como já é hoje) |
| Badge curto em lista/detalhe | "Urgência Renal" | **"Preemptivo"** |
| Campo/data clínica (`dataSituacaoEspecial`) | "Data da urgência" | **"Data da solicitação"** |
| Justificativa | "Por que a urgência se aplica" | **"Por que a inserção preemptiva se aplica"** |
| Anexo de deferimento | "Comprovante de inserção da urgência renal no SNT" | "Comprovante de inserção em lista de espera renal no SNT" |
| Assunto de e-mail (prefixo) | "Urgência Renal" (`EmailProperties.prefixoAssunto`) | "Lista de Espera Renal" |
| Assinatura de e-mail | "Equipe de Urgência Renal - Secretaria de Saúde" | **manter** (é a equipe, não o processo) |

**Regra de implementação recomendada — fonte única de rótulo.** Não espalhar
`if (p.isPreemptivo()) "..." else "..."` por 20 arquivos. Criar um utilitário
puro em `service/` (mesma família de `Iniciais`/`NomePadraoAnexo`/
`ConflitoEquipeMatcher`), por exemplo `service/RotuloProcesso.java`:

```java
public final class RotuloProcesso {
    public static String tipoCurto(Processo p)     // "Urgência Renal" | "Preemptivo"
    public static String nomeLongo(Processo p)     // "Processo de Urgência Renal" | "Processo de Inserção em Lista de Espera Renal (Preemptivo)"
    public static String tituloPdfCaixaAlta(...)   // idem, sem acento onde o PDF exige
    public static String carimboLinha1(Processo p) // NOME_INSTITUICAO + " - ..."
    public static String rotuloComprovanteSnt(Processo p)
    public static String rotuloDataClinica(Processo p) // "Data da urgência" | "Data da solicitação"
}
```

É a mesma lição já registrada no `CLAUDE.md` e na memória do projeto
("wizard/timeline: fonte única — nunca calcular o mesmo estado de UI em 2
lugares"). Aqui vale em dobro: são 4 PDFs institucionais + 6 templates de
e-mail + ~10 telas.

**O que NÃO renomear** (é a EQUIPE/o sistema, não o processo, e renomear teria
raio de impacto enorme sem ganho):

- `MembroUrgenciaRenal` (entidade), `/membros`, "Membros da Urgência Renal".
- `EmailProperties.assinatura` ("Equipe de Urgência Renal - Secretaria de
  Saúde").
- Nome do sistema (`PdfCabecalhoStamper.NOME_SISTEMA`, rodapé do
  `layout.html`, `SgpurApplication`), pacote `br.gov.saude.sgpur`, env vars
  `SGPUR_*`.
- `/controle-urgencias` (módulo separado, não é o `Processo` — e **pacientes
  preemptivos não entram nele**, decisão fechada, ver §9.8).
- **Títulos gerais da área do operador — DECIDIDO: manter como estão.**
  "Painel da Urgência Renal" (`dashboard.html` l.21), o rodapé do
  `layout.html` (l.675), a navbar e os títulos de lista **não mudam**, mesmo
  com os dois tipos convivendo nas listas. O rótulo novo aparece **só dentro
  do processo/tela específica do tipo preemptivo** (badge na linha, detalhe do
  processo, formulário, e-mail e PDF daquele processo) — nunca nos títulos
  gerais. Isso reduz bastante o escopo de §8.5.

---

## 4. Aviso ao avaliador: preemptivo sem quebrar a imparcialidade

O avaliador **precisa** saber que está julgando uma inserção preemptiva e não
uma urgência (o critério clínico é outro). Isso é **compatível** com a regra
de imparcialidade, que protege a **identidade do paciente**, não a natureza do
pedido.

Recomendação: exibir o tipo como um **badge** na lista e na tela de voto do
Portal do Avaliador, e citá-lo no corpo do e-mail de convite/lembrete. Ponto
de atenção técnico: `AvaliadorController` **nunca** expõe a entidade
`Processo` ao template — só `ProcessoVotoView` / `ParecerPendenteView` /
`ParecerHistoricoView` / `ParecerDispensadoView` (records privados, linhas
~923-935). O campo `preemptivo` precisa entrar **nesses records**, não via
`processo` cru — senão a proteção por design cai.

---

## 5. Numeração do processo (DECIDIDO: série separada)

**Decisão fechada do usuário: a numeração do preemptivo NÃO pode ser igual nem
compartilhar a mesma sequência da urgência renal.** A opção "mesma sequência
com badge visual" está **descartada**.

Estado atual:
- `Processo.numero` — `String`, `length = 12`, **UNIQUE**, formato `NN/AAAA`.
- `Processo.ano` + `Processo.sequencial` (int) — derivados.
- `ProcessoService.proximoNumero(ano)` = `MAX(sequencial) WHERE ano = :ano` + 1
  (`ProcessoRepository.findMaxSequencialByAno`).
- Numeração **manual em 2026**, automática a partir de 2027
  (`ANO_NUMERACAO_AUTOMATICA`).
- Validação manual no controller:
  `numero.matches("\\d{1,3}/\\d{4}")` (`ProcessoDetalheController.salvar`,
  linha ~331).

### Opção recomendada — prefixo `P-` + sequência própria por ano

Formato: **`P-NN/AAAA`** (ex.: `P-01/2027`) para preemptivo; `NN/AAAA`
continua exatamente como está para urgência renal.

Implementação:

1. `ProcessoRepository.findMaxSequencialByAnoEPreemptivo(ano, preemptivo)` —
   consulta nova, `where p.ano = :ano and (coalesce(p.preemptivo, false) = :preemptivo)`.
   **Usar `coalesce`** — as linhas legadas têm `preemptivo = NULL` e um
   `= false` cru as excluiria da contagem, quebrando a sequência da urgência.
2. `proximoNumero(ano, preemptivo)` — prefixa `"P-"` quando preemptivo.
3. `extrairSequencial` (linha 916) faz `numero.split("/")[0]` e
   `Integer.parseInt` — **quebra com `"P-01"`** e cai no fallback silencioso
   (`MAX+1` da sequência errada). Precisa remover o prefixo antes do parse.
4. Regex do controller vira `^(P-)?\d{1,3}/\d{4}$`, com checagem cruzada: um
   processo preemptivo **tem que** ter o prefixo, um de urgência **não pode**
   ter. Sem essa checagem cruzada, a digitação manual de 2026 fura a série.
5. `Processo.numero` tem `length = 12` — `P-123/2026` = 10 caracteres, **cabe
   sem alterar a coluna**.
6. `UNIQUE` em `numero` continua garantindo a não-colisão entre as duas
   séries (`01/2027` ≠ `P-01/2027`).
7. **Sugestão do próximo número no formulário (DECIDIDO — §9.2).** Enquanto a
   numeração for manual (2026), `processos/form.html` passa a **sugerir** o
   próximo número da série correspondente ao tipo marcado. Detalhes:
   - O controller (`ProcessoDetalheController.novo`) já sabe o tipo vindo da
     `SolicitacaoOnline` convertida: expõe **os dois** valores como model
     attributes (ex. `proximoNumeroUrgencia` e `proximoNumeroPreemptivo`,
     ambos de `proximoNumero(ano, preemptivo)`), e o JS troca a sugestão se o
     operador alterar o tipo na tela (§9.4) sem recarregar a página.
   - É **sugestão, não imposição**: o campo continua editável e a validação de
     formato/duplicidade/checagem cruzada de prefixo (item 4) continua sendo a
     regra de verdade. Preenche o `value` do input quando ele está vazio e
     mostra um texto de ajuda ("próximo da série: …") — nunca sobrescreve algo
     já digitado pelo operador.
   - `proximoNumero` calcula `MAX+1` **sem lock** (comentário já existente em
     `ProcessoService.cadastrar`): a sugestão pode ficar desatualizada se
     outro operador cadastrar no intervalo. Isso **já é o comportamento de
     hoje** no regime automático e é coberto pelo `UNIQUE` + tradução amigável
     do `GlobalExceptionHandler` — não introduzir lock por causa da sugestão.

**Prós:** legível em qualquer lugar onde o número já aparece hoje (carimbo do
PDF, nome do arquivo oficial, assunto de e-mail, busca, ofício) sem precisar
carregar o `Processo` junto; distingue à primeira vista; uma coluna nova só
(`preemptivo`); a busca por "P-" vira um filtro natural.

**Contras:** toca o parser de número (`extrairSequencial`), a regex de
validação manual e o nome de arquivo/pasta de anexo
(`AnexoStorageService`/`NomePadraoAnexo` usam o número — conferir se algum
monta caminho de disco com ele; `-` é seguro em nome de arquivo, `/` já é
tratado hoje).

### Alternativas consideradas (não recomendadas)

- **Sufixo `NN/AAAA-P`**: mesmo custo, mas o prefixo ordena/agrupa melhor em
  listagem alfabética e é lido antes (mais visível num assunto de e-mail
  truncado).
- **Faixa reservada (ex.: preemptivos a partir de 500/AAAA)**: não exige
  mudar formato nem regex, mas é uma convenção **implícita** — nada no sistema
  impede um operador de digitar `500/2026` numa urgência, e o usuário pediu
  distinção clara. Descartada.
- **Campo `numeroPreemptivo` separado**: duplicaria toda a lógica de
  unicidade/sequência e a chave de exibição. Descartada.

---

## 6. Comprovante SNT (proposta: mesmo `TipoAnexo`, rótulo condicional)

Hoje: `TipoAnexo.COMPROVANTE_SNT("Comprovante de insercao da urgencia renal no
SNT")`, exigido para concluir a etapa 5 num processo DEFERIDO.

**Recomendação: manter o MESMO valor de enum** e tornar o **rótulo**
condicional (`RotuloProcesso.rotuloComprovanteSnt`). Motivos:

- Funcionalmente é o mesmo documento (comprovante de inserção no SNT, gerado
  fora do sistema) e cumpre o mesmo papel de gate.
- Um valor de enum novo obrigaria a duplicar a condição em **6 queries JPQL**
  do `ProcessoRepository` (lembrete de comprovante pendente, contadores,
  filtro da lista), no `ProcessoValidator`, no `FluxoProcessoService`, no
  `ComprovanteSntLembreteScheduler`, no `ProcessoService.finalizarResposta`
  e na whitelist do `SolicitanteController.baixarAnexoProcesso` — muita
  superfície nova para zero ganho.
- Valor de enum novo em `anexo.tipo` é justamente o caso do pitfall de CHECK
  constraint. (Mitigado hoje pelo `SchemaMigration`, ver §7 — mas por que
  gastar a mitigação sem necessidade.)

Pontos que precisam do rótulo condicional:
`TipoAnexo.COMPROVANTE_SNT.getDescricao()` (usado direto em
`ProcessoAnexoController` linha ~217), `PdfRelatorioBuilder` linha 743,
`FluxoProcessoService` linhas 316-320, `EmailTemplateService.emailDeferido` e
`emailLembreteComprovanteSnt`, `NomePadraoAnexo` linha 41.
**Consequência prática:** `TipoAnexo.getDescricao()` deixa de ser a fonte do
texto exibido para este tipo — quem exibe passa a chamar `RotuloProcesso`.

---

## 7. Impacto em produção

**Decisão fechada do usuário: a regra nova vale SÓ PARA FRENTE.** Não há
backfill, não há migração de dados históricos e não há preocupação em
"reclassificar" processos de Urgência Renal já cadastrados. O único requisito
é que o campo novo tenha um **default seguro** que não quebre a leitura do que
já existe.

**Nenhuma ação manual em produção é necessária:**

1. **É um booleano, não um enum** — não existe CHECK constraint de enum a ser
   atualizada, então o pitfall clássico do `ddl-auto: update` (documentado no
   `CLAUDE.md`) **não se aplica**.
2. **Coluna nullable** (`Boolean`, sem `nullable = false`) — o `ddl-auto:
   update` cria a coluna com NULL nas linhas existentes, e o helper null-safe
   `isPreemptivo()` lê `null` como `false` (= urgência renal comum). Processo
   antigo continua sendo lido, editado, decidido e finalizado exatamente como
   hoje. **Sem backfill, sem script SQL, sem janela de manutenção.**
3. `SchemaMigration.removerChecksDeEnumObsoletasPostgres` já derruba, a cada
   boot, qualquer CHECK de enum em `processo`, `anexo`, `parecer`, `usuario` e
   `solicitacao_online` — rede de segurança extra caso algum campo vire enum
   no futuro. **Atenção:** `rascunho_solicitacao_online` **não está nessa
   lista**.

**Numeração:** nenhum processo existente muda de número (todos continuam
`NN/AAAA`, sem prefixo). A série `P-` começa do zero, a partir do primeiro
preemptivo cadastrado.

**Ordem de deploy:** deploy único, sem janela.

**Única regra de código que essa simplificação impõe:** **nunca** ler
`getPreemptivo()` cru (pode ser `null`) — sempre `isPreemptivo()`. É o que
mantém a compatibilidade sem custo operacional nenhum.

---

## 8. Arquivos afetados

Levantado por inspeção direta do código (`Grep`/`Read`), não por estimativa.

### 8.1 Domain

| Arquivo | Mudança |
|---|---|
| `domain/Processo.java` | + campo `preemptivo` (`Boolean`, nullable) + `isPreemptivo()`; **remover `@NotBlank` de `pacienteRgct`** (mantendo `@Size`), com javadoc explicando. |
| `domain/SolicitacaoOnline.java` | idem (campo + remoção do `@NotBlank` do RGCT). |
| `domain/RascunhoSolicitacaoOnline.java` | + campo `preemptivo`, sem obrigatoriedade nenhuma. |
| `domain/TipoAnexo.java` | Sem mudança estrutural; a descrição de `COMPROVANTE_SNT` deixa de ser a fonte do texto exibido (passa por `RotuloProcesso`). |

### 8.2 Service

| Arquivo | Mudança |
|---|---|
| `service/RotuloProcesso.java` (**novo**) | Fonte única de todo rótulo dependente do tipo. |
| `service/SolicitacaoOnlineService.java` | `criar`: valida RGCT condicionalmente + normaliza para null quando preemptivo; `notificarOperadores` (linha 556): linha de RGCT condicional + linha "Tipo". |
| `service/RascunhoSolicitacaoOnlineService.java` | `salvar(...)` ganha o parâmetro `preemptivo` (assinatura de 10 → 11 parâmetros). |
| `service/ProcessoService.java` | `proximoNumero(ano, preemptivo)`; `extrairSequencial` tolerante ao prefixo `P-`; `atualizarDados` copia `preemptivo` **e reemite `numero`/`sequencial` na série certa quando o tipo muda** (§2.2/§9.4), rejeitando a troca se o processo já foi enviado. |
| `service/EmailTemplateService.java` | 6 textos: `emailConviteAvaliador` (l.184/189), `emailConvitePortal` (l.339/343), `emailSolicitaInfo` (l.385), `emailDeferido` (l.450-454), `emailIndeferido` (l.475), `emailLembreteComprovanteSnt` (l.302-304). Todos passam a usar `RotuloProcesso`. |
| `service/OficioService.java` | l.71 (cabeçalho "URGÊNCIA RENAL"), l.105-106 (corpo do PDF) e l.182-184 (rascunho RTF). **Decidido (§9.6): mesmo texto do modelo atual, SÓ o rótulo trocado** — sem redação institucional própria, sem parágrafo novo, sem alterar a estrutura do ofício. |
| `service/RelatorioService.java` | l.123 (cabeçalho), l.168-169, l.210-211 (título), l.241 (rótulo da data — "Data de solicitação da urgência renal" → "Data da solicitação" quando preemptivo, §9.7), l.254 (seção 2). |
| `service/SolicitacaoAvaliadorService.java` | l.154 — carimbo página a página do PDF que vai aos avaliadores. **É o rótulo mais sensível: é o que o avaliador lê em cada página.** |
| `service/RelatorioAnualService.java` | **Decidido (§9.5): UM relatório único, com coluna/indicador de tipo por linha** — não separar em seções nem gerar 2 PDFs. Muda a tabela (l.293: cabeçalho `"Nº/Ano", "Paciente", "RGCT", "Status", …` ganha uma coluna **Tipo**; a célula de RGCT fica vazia no preemptivo) e os títulos/capa (l.68-69, l.166, l.184) continuam genéricos, cobrindo os dois tipos. |
| `service/RelatorioAvaliadorService.java` | l.68, l.160 — capa. |
| `service/FluxoProcessoService.java` | l.313-320 — rótulo/mensagem da etapa "Comprovante SNT". |
| `service/ExportacaoProcessoService.java` | l.217, l.220, l.228 (RGCT), l.241 — título do dossiê + linha do tipo. |
| `service/PdfRelatorioBuilder.java` | l.743 — descrição do `COMPROVANTE_SNT`. |
| `service/NomePadraoAnexo.java` | l.41 — nome padrão do anexo. |
| `config/EmailProperties.java` | l.30 — `prefixoAssunto` deixa de ser fixo (ou ganha variante); l.27 (assinatura) **não muda**. |

### 8.3 Web / controller

| Arquivo | Mudança |
|---|---|
| `web/SolicitanteController.java` | `CAMPOS_PERMITIDOS` (l.143, allowlist do binder) ganha `"preemptivo"`; `nova` repassa o rascunho; `/nova/rascunho` ganha o parâmetro; mapa mensagem→campo ganha `pacienteRgct`; rótulos de resultado l.545/581/592/608-621. |
| `web/ProcessoDetalheController.java` | conversão (l.234-243) copia `preemptivo`; validação do `numero` (l.331) aceita `P-` + checagem cruzada de prefixo × tipo; validação condicional do RGCT; l.322 (mensagem "Data de solicitação da urgência renal"); expõe `proximoNumeroUrgencia`/`proximoNumeroPreemptivo` ao form (§5.7); `atualizar` permite trocar o tipo até o envio (ADMIN **ou** OPERADOR) e registra `PROCESSO_TIPO_ALTERADO`. |
| `service/AuditoriaService.java` (uso, sem mudança de assinatura) | **Nova ação `PROCESSO_TIPO_ALTERADO`** (§9.4), obrigatória: id + número antigo → novo + tipo antigo → novo. **Nunca** o nome do paciente (usar `Iniciais.de()`, regra fixa do `CLAUDE.md`). |
| `web/AvaliadorController.java` | `ProcessoVotoView` (l.923) e os records de lista/histórico/dispensados ganham o flag de tipo. |
| `web/ProcessoAnexoController.java` | l.212/217 — rótulo do comprovante; l.438 (prompt de IA cita "urgência renal"). |
| `web/ProcessoDecisaoController.java` | l.411 — prompt de IA do ofício. |
| `web/ProcessoListaController.java` / `ArquivoController.java` | filtro opcional por tipo. |
| `web/dto/SituacaoPedidoView.java` | l.25 — rótulo "Deferido - Urgência renal reconhecida". |

### 8.4 Repository

| Arquivo | Mudança |
|---|---|
| `repository/ProcessoRepository.java` | + `findMaxSequencialByAnoEPreemptivo`; opcionalmente filtro de tipo em `buscar`/`buscarEncerrados`. **Cuidado documentado no `CLAUDE.md`:** nunca usar `:param IS NULL OR ...` (H2 tolera, Postgres não) — normalizar o filtro para um valor efetivo antes de passar ao repositório. |

### 8.5 Templates

| Arquivo | Mudança |
|---|---|
| `solicitante/nova.html` | **Campo novo Sim/Não** (radio, com texto explicativo do que é preemptivo); RGCT vira condicional; l.221 "Data da urgência" → **"Data da solicitação"** quando preemptivo (§9.7); l.234-249 bloco de justificativa. |
| `solicitante/detalhe.html` | badge de tipo; RGCT só quando existe (l.202); l.166 e l.269 ("equipe de Urgência Renal"). |
| `solicitante/lista.html` | coluna/badge de tipo; RGCT condicional (l.154/171/270); textos l.40, l.122-126. |
| `processos/form.html` | tipo (exibido/editável na conversão) + RGCT condicional (l.50-52) + **sugestão do próximo número da série conforme o tipo** (§5.7), com texto de ajuda; nunca sobrescreve número já digitado. |
| `processos/editar.html` | idem (l.35-37) + tipo editável **só enquanto o processo não foi enviado** (§9.4), com aviso de que a troca reemite o número. |
| `processos/detalhe.html` | badge de tipo no cabeçalho; RGCT condicional (l.288); l.977. |
| `processos/lista.html` / `arquivo/lista.html` | badge de tipo na coluna de identificação + filtro. |
| `processos/solicitacoes-online-lista.html` / `-detalhe.html` | badge/coluna de tipo; RGCT condicional (l.33/54/64). |
| `avaliador/lista.html` / `avaliador/votar.html` | badge de tipo (via DTO); l.64 do `lista.html`. |
| `dashboard.html` | **SEM MUDANÇA** — decidido (§9.1): "Painel da Urgência Renal" (l.21) fica como está. |
| `layout.html` | **SEM MUDANÇA** — rodapé (l.675) e navbar ficam como estão (§9.1). |
| `relatorios/anual.html` | l.18 — título **fica como está** (§9.1/§9.5, relatório único); só a tabela do PDF ganha a coluna de tipo. |
| `static/js/solicitante-nova.js` | liga/desliga o RGCT (`required` dinâmico) conforme o rádio; l.84-91 (o rascunho AJAX envia `pacienteRgct`) ganha `preemptivo`. |
| `static/js/` (form do processo) | troca a sugestão de número ao alterar o tipo na tela (§5.7) — JS em arquivo próprio, nunca inline (convenção do projeto). |

### 8.6 Testes

Testes que **quebram** por texto fixo ou por assinatura (levantados por grep):

| Arquivo | Motivo |
|---|---|
| `service/OficioServiceTest.java` | assere o texto "Urgência Renal" do ofício. |
| `service/ProcessoServiceTest.java` | idem + numeração. |
| `service/SolicitacaoAvaliadorServiceTest.java` | assere o carimbo. |
| `web/SolicitanteControllerTest.java` | rótulos + campos do form. |
| `service/RascunhoSolicitacaoOnlineServiceTest.java` | **assinatura** de `salvar(...)`. |
| `service/SolicitacaoOnlineCamposIntegrationTest.java` | validação de campos (RGCT obrigatório). |
| `service/ProcessoAtualizacaoIntegrationTest.java` | cópia campo a campo — precisa do campo novo. |
| `web/ProcessoDetalheControllerTest.java`, `web/SolicitacaoOnlineTriagemControllerTest.java` | RGCT/conversão. |
| `service/EmailTemplateServiceTest.java`, `service/RelatorioServiceTest.java`, `service/RelatorioAnualServiceTest.java` | textos. |
| `e2e/FluxoCompletoProcessoIT.java`, `e2e/PortaisVisualCompletoIT.java`, `e2e/ResponsividadeSolicitanteIT.java`, `e2e/pages/PortalSolicitantePage.java`, `e2e/pages/NovoProcessoPage.java` | preenchem o formulário e leem rótulos. |

Testes **novos** necessários:

- Solicitação preemptiva **sem RGCT** é aceita; não-preemptiva sem RGCT é
  rejeitada com mensagem de negócio (não 500).
- **Default seguro:** processo com `preemptivo = NULL` no banco (o estado de
  todo processo já cadastrado após o deploy) é lido como urgência renal e
  **aceita escrita** (decidir/finalizar/anexar) sem
  `ConstraintViolationException` — teste de integração com H2 real, não
  `@WebMvcTest` (é exatamente a classe de bug que mock não pega, ver
  `CLAUDE.md`). É o único teste que a decisão "vale só para frente" exige.
- Numeração: preemptivo e urgência do mesmo ano têm sequências independentes
  (`01/2027` e `P-01/2027` coexistem); `extrairSequencial` lê `P-07/2027`
  como 7.
- Conversão `SolicitacaoOnline` → `Processo` propaga `preemptivo` (reler do
  banco e conferir campo a campo — regra do `CLAUDE.md`).
- **Regra de decisão inalterada em processo preemptivo**: maioria simples 2/3
  e exceção do coordenador funcionam idênticas (teste de regressão explícito,
  para provar que o tipo não vazou para a lógica de decisão).
- Avaliador vê o tipo mas **não** vê nome/CPF/equipe (o record projetado
  continua fechado).
- **Troca de tipo antes do envio** (§9.4): reemite o número na série certa,
  grava `PROCESSO_TIPO_ALTERADO` e é **rejeitada** depois do envio aos
  avaliadores.

---

## 9. Decisões adicionais (todas fechadas)

Todas as perguntas que este plano deixou em aberto foram respondidas pelo dono
do produto em 2026-08-27. **Nada permanece em aberto.** Já decidido nas seções
anteriores: modelagem (booleano `preemptivo`, §2), nomenclatura base
("Inserção em Lista de Espera Renal", §3), numeração em série separada (§5) e
"vale só para frente", sem backfill (§7).

**9.1 Títulos gerais da área do operador — NÃO mudam.** "Painel da Urgência
Renal" (`dashboard.html` l.21), o rodapé do `layout.html` (l.675), a navbar e
o título do Relatório Anual ficam exatamente como estão. O rótulo novo aparece
**só dentro do processo/tela específica do tipo preemptivo**. → §3 (lista "o
que NÃO renomear"), §8.5 (3 templates saem do escopo).

**9.2 Numeração manual de 2026 — SIM, sugerir o próximo número da série
correta** no formulário, conforme o tipo marcado, para reduzir erro do
operador. Continua sendo sugestão (campo editável), com a validação de
formato/duplicidade/prefixo como regra de verdade. → detalhado em §5.7, entra
na **Fase 4**.

**9.3 Prazo-meta do avaliador — MESMO PRAZO (7 dias) para os dois tipos.**
`app.avaliador.prazo-dias` continua único. **A ideia de criar
`app.avaliador.prazo-dias-preemptivo` está DESCARTADA** — `TempoRespostaService`,
o indicador de `/membros`, o card do Painel e o "fora do prazo" do Portal do
Avaliador **não mudam nada**.

**9.4 Tipo editável após a conversão — SIM, até o processo ser enviado aos
avaliadores, por ADMIN *ou* OPERADOR** (não restringir a ADMIN). Consequências
obrigatórias, detalhadas em §2.2: janela de edição limitada ao status
`SOLICITADO`; **reemissão do número** na série certa na mesma transação; e
**auditoria `PROCESSO_TIPO_ALTERADO` passa a ser NECESSÁRIA** (não é mais
condicional) — id + número antigo → novo + tipo antigo → novo, nunca o nome do
paciente. → §2.2, §8.2 (`ProcessoService`), §8.3 (`ProcessoDetalheController`
+ `AuditoriaService`), §8.5 (`processos/editar.html`), Fase 4.

**9.5 Relatório Anual — UM relatório único**, com uma coluna/indicador de tipo
em cada linha. Não são dois PDFs nem duas seções. → §8.2
(`RelatorioAnualService`, tabela da l.293 ganha coluna **Tipo**; a célula RGCT
fica vazia no preemptivo), Fase 7/8.

**9.6 Ofício de indeferimento — MESMO TEXTO do modelo atual**, só com o rótulo
trocado ("urgência renal" → "inserção em lista de espera renal") onde aparece.
Sem redação institucional própria por enquanto, sem parágrafo novo, sem mudar
a estrutura. → §8.2 (`OficioService`, l.71/105-106/182-184), Fase 7.

**9.7 Rótulo de `dataSituacaoEspecial` quando preemptivo — "Data da
solicitação".** O nome da coluna no banco **não muda** (`data_situacao_especial`),
nem o nome do campo Java; muda só o rótulo exibido no formulário, no dossiê,
no Relatório Final e nos e-mails quando `preemptivo = true`. → §3 (tabela de
vocabulário), `RotuloProcesso.rotuloDataClinica`, §8.2/§8.3/§8.5.

**9.8 Controle de Urgências (`/controle-urgencias`) — pacientes preemptivos
NÃO entram.** Confirma a presunção original: o módulo continua exclusivo de
urgência renal, independente do `Processo`, **sem nenhuma alteração** neste
trabalho. → §3, §8 (o módulo não aparece em nenhuma tabela de arquivos
afetados).

**9.9 Auditoria — reforço da regra fixa.** Além de `PROCESSO_TIPO_ALTERADO`
(§9.4), nenhuma ação nova é necessária. Se o filtro por tipo das listas for
logado, pode ser (é um valor fechado) — mas **o termo textual de busca nunca
entra em log de auditoria**, recaída conhecida já corrigida 2× no projeto.

---

## 10. Ordem de implementação proposta (sessão futura)

Fases pequenas e independentes, cada uma com a suíte verde antes da seguinte.
**Nunca editar fonte com `mvn test`/`verify` rodando** (pitfall do
`CLAUDE.md`, já recaiu 2×).

**Fase 0 — decisões: CONCLUÍDA (2026-08-27).** Todas as perguntas foram
respondidas (§9). Resta apenas confirmar o **prefixo literal `P-`** ao começar
a Fase 4, caso o setor prefira outra marca — mas o formato (série separada,
prefixo, não sufixo/faixa) já está fechado.

**Fase 1 — dado, sem comportamento.**
Campo `preemptivo` nas 3 entidades + helper null-safe + propagação na conversão
e em `atualizarDados` + testes de propagação e de leitura de linha legada
(`NULL` → `false`). Nada muda na UI. **Deployável sozinha, risco quase zero.**

**Fase 2 — RGCT condicional.**
Remover `@NotBlank` das 2 entidades, mover para `SolicitacaoOnlineService.criar`
e `ProcessoDetalheController`; corrigir `notificarOperadores` (linha 556, hoje
imprimiria `null`). Testes: preemptivo sem RGCT aceito, comum sem RGCT
rejeitado com mensagem de negócio, escrita em processo legado sem 500.

**Fase 3 — formulário do solicitante.**
Rádio Sim/Não em `solicitante/nova.html` + `solicitante-nova.js` (esconde RGCT,
`required` dinâmico) + rascunho (`salvar(...)` +1 parâmetro, allowlist do
binder). Aqui o dado começa a existir de verdade.

**Fase 4 — numeração em série separada + troca de tipo.**
`findMaxSequencialByAnoEPreemptivo` (com `coalesce`), `proximoNumero(ano,
preemptivo)`, `extrairSequencial` tolerante ao prefixo, regex + checagem
cruzada no controller. **A fase mais delicada** — o número é chave única e
aparece em todo lugar. Entram aqui, por dependerem da mesma lógica de série:
- **sugestão do próximo número no formulário** conforme o tipo (§9.2/§5.7),
  com o JS que atualiza a sugestão ao trocar o tipo na tela;
- **troca de tipo até o envio** (§9.4), por ADMIN ou OPERADOR, com reemissão
  do número na mesma transação e auditoria `PROCESSO_TIPO_ALTERADO`;
- teste de que a troca é **rejeitada** depois do envio aos avaliadores.

**Fase 5 — `RotuloProcesso` + rótulos de UI.**
Criar o utilitário e migrar as telas (badge de tipo em lista, detalhe,
arquivo, triagem, Portal do Solicitante, Portal do Avaliador via DTO).

**Fase 6 — e-mails.**
6 templates do `EmailTemplateService` + `EmailProperties.prefixoAssunto`.

**Fase 7 — PDFs institucionais.**
Carimbo (`SolicitacaoAvaliadorService`, o mais sensível), Ofício (**só rótulo,
mesmo texto** — §9.6), Relatório Final, Relatório do Avaliador, dossiê, e o
**Relatório Anual único com coluna de tipo** (§9.5).

**Fase 8 — filtros.**
Filtro por tipo nas listas (Processos, Arquivo, Solicitações online) + badge na
coluna de identificação. O Relatório Anual já ficou resolvido na Fase 7 (§9.5);
os títulos gerais não mudam (§9.1).

**Fase 9 — E2E.**
Estender `e2e/` com um fluxo preemptivo completo (solicitação sem RGCT →
conversão → número `P-` → 3 pareceres → decisão), ao lado do fluxo de urgência
já existente.

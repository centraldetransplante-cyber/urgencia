# Relatório: dados adicionais de identificação do paciente (data de nascimento, CPF, sexo, nome da mãe)

**Status: IMPLEMENTADO.**

## 1. Motivação

O Portal do Solicitante coletava, até esta mudança, apenas nome e RGCT/SNT
do paciente — insuficiente para o registro administrativo completo do
processo de urgência renal (o CPF, em particular, é o identificador que
outros sistemas/documentos da Central de Transplantes esperam ver). Esta
mudança adiciona quatro campos novos, coletados no envio da solicitação e
propagados até o `Processo`:

- **Data de nascimento** (`pacienteDataNascimento`, `LocalDate`) —
  obrigatória, não pode ser futura.
- **CPF** (`pacienteCpf`, `String`, 11 dígitos) — obrigatório, validado por
  dígito verificador (módulo-11).
- **Sexo** (`pacienteSexo`, enum `Sexo` — `MASCULINO`/`FEMININO`) —
  obrigatório, binário por decisão de produto (sem terceira opção).
- **Nome da mãe** (`pacienteNomeMae`, `String`, opcional) — sem validação de
  obrigatoriedade em nenhum lugar do sistema (nem no envio final, nem no
  rascunho).

## 2. Onde os campos foram adicionados

- **`domain/Sexo.java`** (novo enum) e **`service/CpfUtil.java`** (novo
  utilitário puro, sem estado — mesma família de `Iniciais`/
  `ConflitoEquipeMatcher`): `normalizar` (remove tudo que não é dígito,
  null-safe), `valido` (módulo-11, rejeita as 10 sequências degeneradas tipo
  `000.000.000-00`), `formatar` (`000.000.000-00`, só para apresentação).
  **CPF é sempre armazenado como 11 dígitos crus, sem máscara** — a
  formatação é responsabilidade da camada de apresentação
  (`CpfUtil.formatar`, chamado pelos controllers ao montar o model
  attribute `pacienteCpfFormatado`), nunca de `T(...)` em template.
- **`Processo`, `SolicitacaoOnline`**: os 4 campos, com `@NotNull`
  (data de nascimento, sexo) / `@NotBlank` + `@Size(min=11,max=11)` (CPF) /
  `@Size(max=200)` (nome da mãe, sem obrigatoriedade) na Bean Validation.
- **`RascunhoSolicitacaoOnline`**: os mesmos 4 campos, **sem nenhuma
  anotação de obrigatoriedade** — só `@Size` (null-safe) nos campos de
  texto. Ver seção 5.
- **Formulários**: `processos/form.html` (cadastro, a partir da conversão de
  uma `SolicitacaoOnline`), `processos/editar.html` (edição posterior) e
  `solicitante/nova.html` (envio do pedido pelo Portal). Os três usam os
  mesmos `name`: `pacienteDataNascimento` (`<input type="date">`),
  `pacienteCpf` (`<input type="text" maxlength="14"
  placeholder="000.000.000-00">`) e `pacienteSexo` (`<select>`, populado a
  partir do model attribute `opcoesSexo` = `Sexo.values()`).
- **Telas de leitura**: `processos/detalhe.html`,
  `processos/solicitacoes-online-detalhe.html` (triagem, lado do operador) e
  `solicitante/detalhe.html` (o próprio solicitante revendo o pedido
  enviado) — os 3 exibem os 4 campos num bloco de identificação, com o CPF
  já formatado (model attribute `pacienteCpfFormatado`, montado pelo
  controller via `CpfUtil.formatar`).
- **Dossiê e Relatório Final** (`ExportacaoProcessoService.montarResumo`,
  `RelatorioService.gerar`): os 4 campos entram na seção de identificação do
  paciente, junto com nome e RGCT já existentes — CPF sempre formatado,
  sexo pela descrição do enum (`getDescricao()`), nome da mãe só quando
  preenchido.

## 3. Coluna nullable mesmo com Bean Validation obrigatória

`pacienteDataNascimento`, `pacienteCpf` e `pacienteSexo` são `@NotNull`/
`@NotBlank` na Bean Validation, mas **deliberadamente sem `nullable = false`**
na coluna (`@Column`) em `Processo` nem em `SolicitacaoOnline` — mesma
lacuna que já existia em `pacienteRgct` desde antes desta mudança, agora
tratada como decisão consciente: garante compatibilidade com qualquer
`Processo`/`SolicitacaoOnline` já gravado em produção antes destes campos
existirem, sem exigir nenhum backfill manual (ver `CLAUDE.md`, seção
"`ddl-auto: update` não faz backfill em coluna nova"). A obrigatoriedade de
fato é imposta inteiramente pela camada de validação (formulário +
`@Valid`/`BindingResult` nos controllers, `SolicitacaoOnlineService.criar`)
— nunca pela constraint do banco.

## 4. Imparcialidade: nunca chega ao avaliador

Regra inviolável do projeto: o material que os médicos avaliadores veem
(Portal do Avaliador, PDF anonimizado enviado aos avaliadores, e-mails de
convite/lembrete) usa **só as iniciais** do paciente, nunca dado que
permita identificá-lo — imparcialidade do julgamento, não LGPD (ver
`CLAUDE.md`, "Identificação do paciente"). Os 4 campos novos são ainda mais
sensíveis nesse sentido (CPF identifica de forma inequívoca), então:

- Nenhum template do Portal do Avaliador (`avaliador/lista.html`,
  `avaliador/votar.html`) referencia `pacienteDataNascimento`, `pacienteCpf`,
  `pacienteSexo` ou `pacienteNomeMae` — confirmado por grep, zero
  ocorrências.
- `AvaliadorController` nunca expõe a entidade `Processo` inteira ao
  template (projeta `ProcessoVotoView`/`ParecerVotoView`), então mesmo um
  `th:text` futuro no template do avaliador não teria acesso direto a esses
  campos sem alterar o DTO — mesma proteção "por design" já documentada
  para `pacienteNome`.
- `SolicitacaoAvaliadorService` (gera o PDF único anonimizado enviado aos
  avaliadores) e `PdfCabecalhoStamper` (carimbo institucional + limpeza de
  metadados) seguem carimbando só as iniciais — não tocados por esta
  mudança.
- Os 4 campos só aparecem em telas/documentos do lado **operador** (detalhe
  do processo, triagem) e do próprio **solicitante** (ele revendo o próprio
  pedido), além do dossiê exportado e do Relatório Final — documentos
  internos da equipe de Urgência Renal, nunca distribuídos ao avaliador.

## 5. Rascunho (`RascunhoSolicitacaoOnline`): campos opcionais, sem validação

Segue o mesmo racional já documentado para o restante da entidade (staging
descartável, nunca chega à triagem — ver `CLAUDE.md`, "Rascunho de
solicitação"): os 4 campos novos entraram em
`RascunhoSolicitacaoOnlineService.salvar(...)` sem nenhuma obrigatoriedade,
podendo ser salvos em branco/nulos a qualquer momento enquanto o solicitante
ainda preenche o formulário. A assinatura de `salvar` passou a receber os 3
campos novos obrigatórios no envio final (data de nascimento, CPF, sexo —
nome da mãe já existia como opcional antes desta leva de campos), na mesma
posição em que aparecem no formulário. A validação completa (CPF com dígito
verificador, data não-futura, sexo preenchido) só acontece no envio
definitivo, em `SolicitacaoOnlineService.criar` — nunca no rascunho.

## 6. Validação de CPF

`CpfUtil.valido` implementa o algoritmo padrão de dígito verificador
(módulo-11) e rejeita as 10 sequências degeneradas (`000.000.000-00` até
`999.999.999-99`, que passariam no cálculo do dígito mas não são CPFs
reais). A validação roda em três pontos, sempre sobre o valor já normalizado
(`CpfUtil.normalizar`, que remove tudo que não é dígito):

- `SolicitacaoOnlineService.criar` — rejeita com `IllegalArgumentException`
  se o CPF vier em branco ou com dígito verificador inválido; a data de
  nascimento não pode ser nula nem futura; o sexo não pode ser nulo. Em caso
  de sucesso, o CPF é normalizado antes de persistir (`solicitacao.
  setPacienteCpf(cpfDigits)`).
- `ProcessoDetalheController.salvar` (cadastro, `POST /processos`) e
  `ProcessoDetalheController.atualizar` (edição, `POST /processos/{id}/editar`)
  — as anotações `@NotBlank`/`@Size` da entidade já cobrem ausência/tamanho
  errado via `@Valid`; o dígito verificador é checado manualmente logo
  depois (`CpfUtil.valido`), com `result.rejectValue("pacienteCpf", ...)`
  em caso de erro, e o valor normalizado é regravado no objeto antes de
  seguir.

A formatação (`000.000.000-00`) é sempre calculada na apresentação — os
controllers montam o model attribute `pacienteCpfFormatado`
(`CpfUtil.formatar`) para as telas de leitura; o CPF nunca é formatado antes
de ser persistido.

## 7. Testes

Cobertura em `CpfUtilTest` (normalização, validação módulo-11, as 10
sequências degeneradas, formatação) e nos testes de integração/controller
já existentes de `Processo`/`SolicitacaoOnline`/`RascunhoSolicitacaoOnline`,
ajustados para preencher os 4 campos novos nos fixtures — como os 3
primeiros passaram a ser obrigatórios na Bean Validation, qualquer teste que
persiste um `Processo`/`SolicitacaoOnline` de verdade (via `@SpringBootTest`
+ H2 real) precisa deles preenchidos, senão a gravação falha com violação de
constraint da Bean Validation no flush do Hibernate.

## 8. HOTFIX de 2026-08-22 — produção quebrada, Bean Validation removida da entidade

**No mesmo dia do merge, produção quebrou por completo para qualquer escrita
em `Processo` já existente** (finalizar resposta, decidir, editar, reabrir —
qualquer ação que dispare um flush do Hibernate). Os 12 processos reais de
produção, criados antes desta feature, têm `pacienteDataNascimento`/
`pacienteCpf`/`pacienteSexo` NULL — e a seção 7 acima já registrava, sem
perceber a gravidade, o mecanismo exato da causa: "a gravação falha com
violação de constraint da Bean Validation no flush do Hibernate". O
`@NotNull`/`@NotBlank` na ENTIDADE dispara essa validação em **qualquer**
INSERT/UPDATE (`jakarta.persistence.validation.mode=AUTO`), não só quando o
controller usa `@Valid` — mesmo um método que só grava `dataEnvioSnt`, sem
nenhuma relação com paciente, quebrava com `ConstraintViolationException`/500
num processo legado. A ressalva "DELIBERADAMENTE sem `nullable = false` na
coluna" (seções acima) não bastava: a coluna aceitar NULL não impede o
Hibernate de validar a entidade Java antes do flush.

**Correção:** `@NotNull`/`@NotBlank`/`@Size` removidos da entidade em
`Processo`/`SolicitacaoOnline` para os 3 campos — mesmo padrão já documentado
no `CLAUDE.md` para `Usuario.email` ("obrigatoriedade fica na camada web, não
na entidade"). A obrigatoriedade de verdade, para dado **novo**:
- `SolicitacaoOnline`: já vivia em `SolicitacaoOnlineService.criar`
  (checagem explícita, `IllegalArgumentException`) — não precisou mudar,
  só deixou de ser redundante/perigosa na entidade.
- `Processo`: não existia (dependia só do `@Valid` da entidade). Adicionada
  em `ProcessoDetalheController.salvar`/`atualizar` (`result.rejectValue`,
  mesmo padrão já usado ali para o dígito verificador do CPF).

**O mesmo risco também existia (e foi corrigido) em `SolicitacaoOnline`**:
`cancelar`/`devolver`/`converter` mutam a entidade GERENCIADA carregada do
banco e dependem do flush no commit da transação — uma `SolicitacaoOnline`
legada com esses 3 campos NULL quebraria do mesmo jeito ao ser cancelada,
devolvida ou convertida, mesmo sem `criar` ser chamado de novo.

**Por que a suíte não pegou isso antes do merge:** todo teste que persiste um
`Processo`/`SolicitacaoOnline` real (H2) já preenchia os 4 campos novos nos
fixtures (a seção 7 documenta isso como premissa, não como risco) — nenhum
simulava um registro PRÉ-EXISTENTE sem eles, que é exatamente o cenário real
de produção (dado legado, não dado de teste criado do zero). Testes de
regressão novos: `ProcessoAtualizacaoIntegrationTest
.processoLegadoComCamposDePacienteNulosAceitaQualquerOutraEscritaSemQuebrar`
e `SolicitacaoOnlineCamposIntegrationTest
.solicitacaoLegadaComCamposDePacienteNulosAceitaDevolucaoSemQuebrar` —
ambos criam o registro diretamente com os 3 campos NULL (sem passar pela
validação de criação) e confirmam que uma escrita não relacionada continua
funcionando.

## 9. Não incluído nesta leva (fora de escopo, não esquecimento)

O Ofício de Indeferimento (`OficioService`) e os e-mails prontos
(`EmailTemplateService`) continuam identificando o paciente só pelo nome
completo, sem RGCT nem os 4 campos novos — mesmo padrão que já valia antes
desta mudança (RGCT também nunca apareceu nesses documentos). Adicionar CPF
a um documento oficial que sai da instituição é decisão de produto própria,
não uma correção decorrente desta feature — não implementado aqui sem pedido
explícito.

# Relatório: e-mail adicional (CC) do solicitante por processo

**Status: IMPLEMENTADO.**

## 1. Motivação

O Portal do Solicitante só tinha um e-mail de contato por processo: o
e-mail da conta (`Usuario.email`), sempre o mesmo em todos os pedidos de
uma mesma equipe. Não havia como um segundo endereço (colega, chefia,
e-mail pessoal de quem de fato está acompanhando aquele pedido específico)
receber os avisos automáticos sobre a decisão — precisava ser reenviado
manualmente por fora do sistema. Esta mudança adiciona um campo opcional,
`emailAdicional`, coletado no envio da solicitação (ou editável depois pelo
operador), que recebe **cópia (CC)** dos e-mails de atualização daquele
processo específico — nunca em substituição ao e-mail principal.

## 2. Onde o campo foi adicionado

- **`SolicitacaoOnline.emailAdicional`** — preenchido pelo próprio
  solicitante no formulário de nova solicitação (`solicitante/nova.html`),
  opcional, sem `@NotBlank`/`@NotNull` (só `@Email` — null-safe, valida
  formato apenas quando preenchido — e `@Size(max=150)`).
- **`RascunhoSolicitacaoOnline.emailAdicional`** — espelha o campo acima no
  rascunho, mesmo tratamento sem obrigatoriedade dos demais campos dessa
  entidade (`RascunhoSolicitacaoOnlineService.salvar` ganhou o parâmetro).
- **`Processo.emailAdicional`** — copiado de `SolicitacaoOnline` no momento
  da conversão (`ProcessoDetalheController.novo`/`ProcessoService.cadastrar`),
  e editável depois pelo operador em `processos/form.html`/`editar.html`.
  É a partir DESTE campo (não do `SolicitacaoOnline` original) que os
  e-mails de atualização calculam o CC — o operador pode corrigir/remover o
  endereço a qualquer momento sem depender do pedido original.

Nenhum dos três campos tem backfill necessário em produção — são
`nullable` desde a criação, mesmo padrão já documentado no `CLAUDE.md` para
outras colunas opcionais recentes (`Processo.ultimoLembreteSntEm` etc.).

## 3. Validação de formato — por que checada explicitamente em `criar()`

`SolicitanteController.criar` usa `@ModelAttribute` **sem `@Valid`** (mesmo
padrão de sempre, com `@InitBinder` allowlist explícita de campos). Sem
`@Valid`, um `emailAdicional` mal formatado só seria pego pela validação
automática do Hibernate no momento do `repository.save()`
(`jakarta.persistence.validation.mode=AUTO`), que lança
`ConstraintViolationException` — exceção **sem** `@ExceptionHandler`
dedicado neste projeto, cai no handler genérico e vira 500 cru, diferente
do redirect gracioso (campo destacado, mensagem junto dele) que os demais
erros de `criar()` já devolvem.

Por isso `SolicitacaoOnlineService.criar` valida o formato explicitamente
com um regex permissivo (`^[^\s@]+@[^\s@]+\.[^\s@]+$` — não tenta cobrir
toda a RFC 5322, só pega erro óbvio de digitação) **antes** de qualquer
`save()`, lançando `IllegalArgumentException` — o mesmo tipo já tratado
pelo `catch` de `SolicitanteController.criar`. Campo vazio/em branco vira
`null` (nunca uma string em branco gravada), consistente com o resto do
sistema (CC nos e-mails e exibição condicional nos templates testam
`null`/`isBlank`).

## 4. Onde o CC é aplicado (levantamento completo)

`ProcessoService.ccEmailAdicional(Processo p)` é a fonte única: devolve
`null` quando o campo está vazio, ou um array de 1 posição quando
preenchido — os métodos de `EmailSenderService` tratam `null` como "sem
CC" sem checagem extra no chamador.

| Ponto de envio | Usa CC? | Por quê |
|---|---|---|
| `ProcessoService.finalizarResposta` (resposta final Deferido/Indeferido, envio automático da etapa 6) | **Sim** | É o e-mail oficial de decisão ao solicitante — o caso de uso principal desta feature. |
| `ProcessoDecisaoController.prepararEmailPronto`, templates `"deferido"`/`"indeferido"`/`"solicita-info"` (o operador clicando "Enviar agora" manualmente) | **Sim** | Caminho manual equivalente ao automático acima — mesmo destinatário, mesmo motivo. |
| `EmailTemplateService.emailConviteAvaliador`/lembrete a avaliador, aviso de cancelamento a avaliador, aviso de informação complementar disponível no Portal | **Não** | Dirigidos ao TIME interno (avaliador), nunca ao solicitante — o e-mail adicional dele não faz sentido nesses envios. |

Nenhuma outra rota de e-mail do sistema foi tocada.

## 5. UI

- `solicitante/nova.html`: campo opcional logo abaixo do bloco "Equipe
  solicitante" (readonly), com `type="email"`, `maxlength="150"` e texto de
  ajuda explicando que é um segundo destinatário, não substitui o e-mail da
  conta. Também salvo/recuperado no rascunho (AJAX,
  `POST /solicitante/nova/rascunho`).
- `processos/form.html`/`editar.html`: mesmo campo, editável pelo operador.
- Telas de leitura (`processos/detalhe.html`,
  `processos/solicitacoes-online-detalhe.html`, `solicitante/detalhe.html`):
  exibido como "E-mail adicional (cópia)" só quando preenchido
  (`th:if="${... != null and !....isBlank()}"`).

## 6. Bug de UX corrigido de graça (achado ao revisar o caminho de erro)

`solicitante/nova.html` tinha um alerta de erro (`th:if="${erro}"`) cujo
**texto era fixo** ("Reanexe os documentos clínicos"), nunca
`th:text="${erro}"` — o solicitante nunca via a mensagem real da validação
(ex. "CPF do paciente inválido", "Informe o sexo do paciente"), só o aviso
genérico de reanexar arquivos, mesmo quando o problema não tinha nada a ver
com anexo. Corrigido com dois alertas distintos (o erro real, quando não
mapeado a um campo específico; o aviso de reanexar documentos, sempre que
há erro) e um mapa `SolicitanteController.campoDoErro(mensagem)` que
destaca visualmente (`is-invalid` + `invalid-feedback`) o campo do
formulário a que a mensagem se refere — `pacienteDataNascimento`,
`pacienteSexo`, `pacienteCpf`, `emailAdicional`, `documentos` — com scroll
automático até ele (`solicitante-nova.js`). Mensagens sem campo
correspondente (ex. "Usuário solicitante sem equipe vinculada") continuam
só no alerta genérico do topo.

Achado relacionado, corrigido na mesma sessão: o `catch` de `criar()` não
repassava `opcoesSexo` ao model ao reexibir o formulário com erro — o
`<select>` de Sexo ficava sem nenhuma opção disponível além de "Selecione"
(só o `GET /solicitante/nova` populava esse atributo). Corrigido adicionando
`model.addAttribute("opcoesSexo", Sexo.values())` também no `catch`.

## 7. Testes

- `SolicitacaoOnlineServiceTest`: e-mail adicional válido (salvo com
  `trim`), em branco (normaliza para `null`), inválido (lança
  `IllegalArgumentException` antes de qualquer `save()`).
- `ProcessoServiceTest`: `ccEmailAdicional` (unitário puro, 3 casos) e
  `finalizarResposta` enviando de fato em cópia quando o campo está
  preenchido (mock de `EmailSenderService` verificando o array de CC).
- `ProcessoControllerEmailTest`: o caminho manual ("Enviar agora") também
  aplica o CC.
- `SolicitanteControllerTest`: `campoComErro` mapeado corretamente para
  `pacienteCpf`/`emailAdicional`, `opcoesSexo` presente no reenvio com erro.
- `RascunhoSolicitacaoOnlineServiceTest`/`ExclusaoSolicitanteIntegrationTest`:
  assinatura de `RascunhoSolicitacaoOnlineService.salvar` atualizada com o
  parâmetro novo em todos os call-sites.

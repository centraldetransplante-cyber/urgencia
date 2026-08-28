# Relatório — Ofício de Indeferimento e Comprovante SNT (aba "5. Finalização")

Data: 2026-08-04 · Escopo: fluxo pós-decisão da tela `/processos/{id}`
Público: gestão do produto. Nenhuma alteração de código foi feita para produzir
este relatório (apenas leitura do código real; os achados citam arquivo:linha).

---

## 1. Resumo executivo

**Corrigido nesta sessão** (ainda não commitado, em `processos/detalhe.html`):

- O badge preto **"Encerrado"** aparecia assim que havia decisão, mesmo faltando
  ofício/comprovante e o envio da resposta — foi exatamente o caso relatado no
  processo real `/processos/8` ("encerrado, mas em 83%"). Agora existe um estado
  intermediário **"Decisão tomada"** (cinza), e "Encerrado" (preto) só quando a
  resposta ao solicitante foi de fato enviada. Processos **Cancelados** seguem
  como "Encerrado" sempre.
- O atalho **"Ofício de Indeferimento"** na barra lateral aparecia sempre que o
  processo estava Indeferido e baixava um PDF **regerado na hora**, podendo
  divergir do ofício que o operador tinha substituído por upload manual. Agora
  só aparece quando o anexo existe e baixa **o anexo real**.
- Foi criado atalho equivalente para o **Comprovante SNT** (Deferido).
- Confirmado por leitura do código: o botão **"Enviar Resposta ao Solicitante"**
  já estava corretamente desabilitado sem o documento obrigatório
  (`detalhe.html:1005-1014`) e o serviço também recusa
  (`ProcessoValidator.java:231-243`). Esse ponto **não era um bug** — era só a
  confusão visual acima.

**Em aberto (motivo deste relatório):**

- O **Ofício de Indeferimento gerado pelo sistema sai com placeholders literais**
  ("Local," em vez da cidade; assinatura genérica sem nome/cargo) e sem o timbre
  institucional usado nos demais PDFs. Esse arquivo é anexado automaticamente ao
  e-mail enviado à equipe solicitante e fica disponível para download no Portal
  do Solicitante.
- O **Comprovante SNT depende 100% de o operador lembrar de anexá-lo**. Não há
  data de envio ao SNT, lembrete, alerta no painel nem qualquer validação de que
  o arquivo é mesmo um comprovante. Se ninguém anexar, o processo fica parado
  indefinidamente, sem que o sistema cobre alguém.
- Processos **Cancelados nunca chegam a 100%** de progresso, por construção.

---

## 2. Ofício de Indeferimento

### 2.1 Achados

1. **Placeholder "Local," vai impresso no documento oficial.**
   `OficioService.java:52-57` escreve literalmente `"Local, 4 de agosto de 2026."`.
   A palavra "Local" nunca é substituída por cidade nenhuma.
2. **Assinatura é um placeholder fixo, sem nome nem cargo.**
   `OficioService.java:88-91`: linha de assinatura seguida de
   *"Responsavel - Equipe de Urgencia Renal / Secretaria de Saude"*. O sistema
   **já tem** uma assinatura configurável (`app.email.assinatura`,
   `application.yml:161`), usada nos e-mails — mas o ofício a ignora.
3. **Destinatário genérico.** `OficioService.java:60`: *"Ao(A) solicitante: "* +
   nome da equipe. Não há bloco formal de destinatário (tratamento, nome,
   cargo, órgão, cidade) como no ofício real da Central de Transplantes.
4. **Sem brasão/timbre.** Todos os outros PDFs do sistema carregam
   `static/brasao.png` (`PdfRelatorioBuilder.java:156-166`,
   `RelatorioAnualService.java:143-148`). O ofício — justamente o documento mais
   "oficial" de todos — é só texto centralizado (`OficioService.java:37-43`).
5. **Sem acentuação.** O corpo inteiro sai sem acentos ("Prezado(a)", "analise",
   "disposicao", "Urgencia Renal") — aceitável em tela interna, ruim num
   documento formal que sai da instituição.
6. **Não existe número de ofício próprio.** O título usa
   `p.identificacao()` (`OficioService.java:47`), que é o número do **processo**
   CET-RS + nome do paciente + RGCT (`Processo.java:134-140`). O ofício real de
   referência na raiz do repositório (`Of n 1398 Julho 2026 SNT.doc`) usa
   **"Ofício nº 1398/2026"**, numeração sequencial própria do setor,
   independente do processo. Hoje não há campo nem contador para isso.
7. **O documento sai automaticamente, sem revisão obrigatória do conteúdo.**
   Ao indeferir (manual, por voto do portal ou pela varredura automática),
   `DecisaoFinalService.java:53-71` gera o PDF e o anexa sozinho; o e-mail final
   (`ProcessoService.java:532-572`) anexa exatamente esse arquivo. O operador
   precisa clicar em "Enviar Resposta ao Solicitante" (há gate humano), mas
   **nada o obriga a abrir o PDF antes** — e a tela não avisa que ele contém
   campos genéricos. O mesmo arquivo fica disponível ao solicitante para download
   pelo Portal (`SolicitanteController.java:691`).
8. **Editar as datas do ofício depois não regenera o PDF.**
   `ProcessoAnexoController.java:122-144` grava `dataEmissaoOficio`/
   `dataEnvioOficio` no processo, mas o anexo permanece com a data original —
   tela e relatório final passam a mostrar uma data, o documento anexado outra.
9. **`GET /processos/{id}/oficio` não valida o status** (`ProcessoAnexoController.java:285-299`):
   gera um "Ofício de Indeferimento" sob demanda para qualquer processo,
   inclusive Deferido. O atalho na tela foi removido nesta sessão, mas a URL
   continua acessível a quem estiver logado como operador.
10. **Quando a decisão é automática, o motivo impresso é genérico por decisão de
    produto** (`ProcessoService.java:269-276`): *"Indeferido por decisão da
    maioria dos membros da Urgência Renal (2 de 3 pareceres desfavoráveis)"* —
    deliberado, para não expor a justificativa dos avaliadores ao solicitante.
    Vale saber que é esse o texto que chega ao ofício quando ninguém escreve
    um motivo à mão.

### 2.2 Sugestões (em ordem de prioridade)

1. **Eliminar os placeholders (alta prioridade, esforço baixo).** Cidade,
   nome e cargo do responsável e nome do setor devem vir de configuração
   (como já acontece com a assinatura dos e-mails), não estar escritos no
   código como "Local" e "Responsavel". Enquanto isso não existe, vale um
   aviso explícito na aba Finalização: *"o ofício gerado automaticamente tem
   campos genéricos — confira ou substitua pelo documento oficial antes de
   enviar"*.
2. **Aproximar o layout do ofício real que a equipe já usa** (média/média):
   brasão no topo, "Departamento de Regulação Estadual / Divisão de
   Transplantes", número do ofício, local e data por extenso, destinatário
   formal e fecho com nome e cargo. O documento de referência na raiz do
   repositório serve de modelo direto, e o brasão já está no projeto.
3. **Numeração própria de ofício** (média/média): um sequencial anual de
   ofícios, separado do número do processo, no formato "Ofício nº NNNN/AAAA".
   É o que torna o documento rastreável no protocolo do setor.
4. **Fechar as brechas de divergência** (baixa/baixo): regerar o PDF quando as
   datas forem alteradas (ou impedir a alteração depois do ofício emitido) e
   restringir a geração sob demanda a processos Indeferidos.

---

## 3. Comprovante SNT ("sempre será anexado")

### 3.1 Achados

1. **É apenas um upload manual, sem geração nem verificação.**
   `ProcessoAnexoController.java:202-222` aceita qualquer arquivo
   `.pdf/.png/.jpg/.jpeg` (`detalhe.html:948-949`). Não há checagem de conteúdo:
   qualquer PDF ou foto passa como "comprovante do SNT".
2. **Assimetria de rastreabilidade em relação ao ofício.** O processo tem
   `dataEmissaoOficio` e `dataEnvioOficio` (`Processo.java:97-100`), e a etapa do
   ofício exige as três coisas: motivo, anexo e data de emissão
   (`FluxoProcessoService.java:148-162`). Para o SNT, a etapa exige **só a
   existência do anexo** (`FluxoProcessoService.java:164-173`). A única data
   disponível é a de upload no sistema — que não é a data em que a urgência foi
   de fato inserida no SNT.
3. **Não há cobrança de ninguém.** Existe lembrete por e-mail para avaliador
   pendente de parecer, mas **nada** equivalente para "processo Deferido há N
   dias sem comprovante". O painel só calcula pendência para processos **em
   andamento** (`HomeController.java:75-78`) — um Deferido sem comprovante não
   entra em nenhum contador. A pendência aparece apenas como texto na coluna da
   lista de processos (`ProcessoListaController.java:48-53`), sem filtro, sem
   destaque e sem alerta.
4. **Consequência prática.** O processo fica travado em "Decisão tomada": o
   paciente foi deferido, mas a equipe solicitante **não recebe a comunicação
   oficial** enquanto o comprovante não for anexado (o envio está bloqueado por
   `ProcessoValidator.java:231-243`). Com a correção desta sessão isso ao menos
   ficou visível na tela; antes ficava escondido atrás do badge "Encerrado".

### 3.2 Sugestões (em ordem de prioridade)

1. **Alerta ativo para Deferidos sem comprovante (alta prioridade, esforço
   baixo/médio):** um card no Painel e um filtro na lista de processos do tipo
   "Deferidos aguardando comprovante SNT (N)", com destaque quando passar de X
   dias da decisão. É a correção mais barata para o problema que originou este
   relatório, porque transforma uma pendência invisível em número na tela
   inicial.
2. **Campo "data de inserção/envio ao SNT" (média/baixo):** espelhar o que já
   existe para o ofício, para o sistema saber *quando* a urgência entrou no SNT,
   não apenas quando alguém subiu um arquivo. Serve de base para indicadores de
   prazo e para o próprio alerta acima.
3. **Lembrete automático por e-mail ao operador (média/média):** mesma mecânica
   já usada para lembrar avaliadores, aplicada a processos Deferidos parados
   nessa etapa por mais de X dias.
4. **Avaliar gerar o ofício ao SNT pelo próprio sistema (baixa/alta):** o
   documento de referência encontrado na raiz mostra que a equipe já emite
   ofícios ao SNT fora do sistema, num formato estável. Gerar esse ofício
   pré-preenchido (paciente, RGCT, motivo) reduziria retrabalho — mas é a
   sugestão de maior esforço e deveria vir depois das três acima.

---

## 4. Outros riscos observados no fluxo de finalização

1. **Processo Cancelado nunca chega a 100%.** A etapa "Resposta ao solicitante"
   exige o e-mail enviado (`FluxoProcessoService.java:175-187`), mas o botão de
   envio fica permanentemente desabilitado para Cancelado
   (`detalhe.html:1005-1014`) e o serviço recusa explicitamente
   (`ProcessoService.java:518-520`). Ou seja: a etapa existe, é contada no
   progresso e é impossível de concluir. Isso é pré-existente, não foi
   introduzido agora. Sugestão: para processos cancelados, a etapa simplesmente
   **não deveria ser exibida nem contar no progresso** — o cancelamento já
   notifica os avaliadores por e-mail e o solicitante vê o resultado no Portal.
2. **Anexo ausente em disco no envio final: comportamento correto, vale
   registrar.** Se o arquivo do ofício/comprovante sumir do disco, o e-mail
   **não é enviado** e o operador recebe erro (`EmailSenderService.java:144-147`)
   — não sai e-mail prometendo anexo sem anexo. Bom.
3. **Dossiê ZIP tolera anexo ausente.** Na exportação, um anexo com caminho
   inválido é registrado em log e o ZIP é gerado sem ele
   (`ExportacaoProcessoService.java:196-204`), sem avisar quem baixou. Aceitável
   para uso interno, mas quem exporta um dossiê para instrução de processo pode
   receber um pacote silenciosamente incompleto. Sugestão de baixo custo: incluir
   no resumo do dossiê uma linha listando os anexos que não puderam ser
   incluídos.
4. **Relatório final não depende dos dois anexos** — imprime "-" quando as datas
   do ofício estão vazias (`RelatorioService.java:191-193`). Sem risco.

---

## 5. Priorização sugerida

| # | Item | Risco se não fizer | Esforço |
|---|------|--------------------|---------|
| 1 | Eliminar "Local," e a assinatura genérica do ofício | Documento oficial com placeholder chega à equipe solicitante | Baixo |
| 2 | Alerta/filtro de "Deferidos sem comprovante SNT" | Paciente deferido sem comunicação formal, sem ninguém perceber | Baixo/médio |
| 3 | Aviso na tela de que o PDF gerado é um rascunho institucional | Mitiga o item 1 enquanto ele não fica pronto | Muito baixo |
| 4 | Data de envio ao SNT + lembrete automático | Sem rastreabilidade nem cobrança da etapa | Médio |
| 5 | Timbre/brasão + numeração própria do ofício | Documento não bate com o padrão do setor | Médio |
| 6 | Etapa "Resposta ao solicitante" não contar para Cancelado | Progresso eternamente incompleto (só confunde) | Baixo |
| 7 | Regerar ofício ao mudar datas / restringir `GET /oficio` | Divergência entre o anexo e o que a tela mostra | Baixo |
| 8 | Geração do ofício ao SNT pelo sistema | Retrabalho manual (situação atual já funciona) | Alto |

Os itens 1 a 3 resolvem os dois problemas concretos levantados. Os demais são
melhorias de robustez e podem entrar em ciclos seguintes.

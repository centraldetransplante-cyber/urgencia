# Relatório Final (PDF) — diagnóstico V2: re-inspeção, pesquisa externa e recomendações

**Data:** 2026-08-06 · **Tipo:** diagnóstico documental + recomendação fundamentada
(**nenhuma linha de código de produção foi alterada para produzir este
documento** — a árvore de trabalho foi conferida antes e depois)

**Documento anterior:** `docs/RELATORIO-REFORMULACAO-RELATORIO-FINAL-PDF-2026-08.md`
(~1080 linhas). Ele **continua válido e correto** sobre o que descreveu; este
V2 **não o substitui**. O V2 existe porque o dono do produto pediu, textualmente,
*"um nível acima, consulte web e outros locais, tbm está sem acentos"*. O que
este documento acrescenta ao anterior:

1. **Re-inspeção visual do estado de hoje**, pós-PR #45 (4 PDFs novos gerados e
   olhados página a página, mais o Ofício gerado para comparação lado a lado).
2. **Nove achados novos** que a primeira rodada não tinha (§4), dois deles
   graves e um deles **criado pela própria correção anterior**.
3. **Pesquisa externa com fontes citáveis** (§5) — padrão ofício da Presidência
   da República, manual de identidade visual do **próprio Governo do RS**,
   Butterick, USWDS, UKAAF, Section508, GOV.UK, W3C/WCAG, documentação
   iText/OpenPDF e PDF/UA. É o que faltava no relatório anterior, que decidia
   por argumento interno.
4. **Prova empírica local** da questão de codificação de caracteres (§5.4) —
   não mais "está provado porque a seção 4 renderiza", e sim um teste que
   percorre o repertório inteiro do português e confere caractere a caractere.
5. **Protótipo visual medido** (§6): a página de sumário redesenhada, gerada de
   verdade, com a métrica de tinta antes/depois.
6. **Recomendação decidida para cada uma das 8 decisões pendentes** (§7), com a
   fonte que a sustenta — em vez da lista de opções que o relatório anterior
   apresentou. Mais **duas decisões novas** (§7.9 e §7.10), uma delas
   consequência de um achado que muda o custo da Decisão 2.

**A palavra final continua sendo do dono do produto.** Nada aqui foi
implementado, nenhuma branch foi aberta, nenhum commit foi feito.

---

## IMPLEMENTADO em 2026-08-06

Todas as 10 decisões (§7) foram aprovadas pelo dono do produto — exceto a
Decisão 4 (retrato guardado no dossiê), deliberadamente adiada para PR próprio
por mexer em fluxo (`ExportacaoProcessoService`/`DecisaoFinalService`), não em
documento — e implementadas nos 4 blocos sugeridos pelo plano (§8), um commit
por bloco, na mesma branch deste worktree:

- **Bloco 0 (R0)** — `setHeaderRows(1)` nas tabelas (B2), `"Nº do Processo:"`
  (A10), nome do sistema unificado (`PdfCabecalhoStamper.NOME_SISTEMA`, B8),
  `/Lang` + `DisplayDocTitle` (Decisão 10, nível 1), e os 2 literais do
  stamper (Decisão 5: `"Página "`, `"SECRETARIA DE SAÚDE"`).
- **Bloco 1 (R1b+R2)** — frase da regra simétrica por status (B3), seção "3."
  condicional com título "Situação atual" quando não decidido (B4+A7),
  `dataEnvio` do parecer visível (A5), um único carimbo de emissão (A12),
  fecho movido para o fim do documento (A11), e os 28 literais acentuados
  (R2) via tradutores locais `PdfRelatorioBuilder.descricaoResultado`/
  `descricaoTipoAnexo` — `ResultadoParecer`/`TipoAnexo` **não** foram tocados.
- **Bloco 2 (R3b+R4)** — `PaletaPdf` (classe nova) compartilhada entre
  Relatório Final/Anual/Avaliador (Decisões 1 e 6); título de seção com
  filete de 1,5pt no lugar da faixa AZUL chapada, cabeçalho de tabela claro
  com filete azul inferior, corpo a 10pt, coluna "Situação" por extenso
  (R4) — proporções de coluna reajustadas olhando o PDF gerado, como o
  plano exigia.
- **Bloco 3 (R5)** — páginas geradas pelo sistema voltam a ser A4 de verdade
  (`PdfRelatorioBuilder.TAMANHO_PAGINA_SISTEMA`, B1/Decisão 9; páginas de
  anexo importadas continuam expandidas, de propósito); folha divisória por
  anexo (P8a); marcadores/bookmarks de navegação via `PdfCopy.setOutlines`
  (P8c); `setKeepTogether` nas duas tabelas curtas do sumário (P9).
- **Bloco 4 (R6)** — capa separada eliminada, sumário promovido a folha de
  rosto com cabeçalho institucional compacto (Decisão 7, Opção B); rótulo
  "RELATÓRIO PARCIAL" quando o processo não foi decidido, sem bloquear a
  rota (Decisão 3).

**Validação:** suíte completa rodada ao final de cada bloco (812 testes — os
5 arquivos citados em §4.9 foram atualizados/divididos conforme o esperado),
0 falhas de código novo em todas as rodadas (só o par de flakes de timing do
H2 já documentado em `CLAUDE.md`, intermitente, não relacionado). Em cada
bloco os 4 PDFs de exemplo (DEFERIDO, INDEFERIDO, EM ANDAMENTO,
DEFERIDO-POR-COORDENADOR) foram gerados de verdade via instrumento de teste
temporário (apagado ao final, mesmo padrão do Anexo A) e inspecionados —
texto extraído página a página, tamanho de página (`PdfReader.getPageSize`),
presença de `/Lang`/`DisplayDocTitle`/`/Outlines` no binário. Nenhum PDF
visualizado em leitor gráfico (ambiente sem `pdftoppm`/visualizador nesta
sessão) — a inspeção estrutural/textual foi o substituto disponível; se
alguém revisar visualmente depois e achar um problema de layout que a
inspeção textual não captura, é esperado e deve ser tratado à parte.

**Não implementado nesta rodada** (por decisão explícita do plano): Decisão 4
(retrato guardado no dossiê) e a Decisão 8(b) (índice com número de página) —
o layout da coluna "Página" já foi validado no protótipo do §6, falta só o
mecanismo de duas passagens, "só se (a) e (c) não bastarem".

## STATUS FINAL: IMPLEMENTADO — com o Bloco 4 (R6) REVERTIDO em 2026-08-07

**Todo o plano acima foi de fato implementado e está em produção.** Porém, o
**Bloco 4 (R6) — eliminação da capa separada — foi deliberadamente revertido
no dia seguinte (2026-08-07)**, a pedido explícito do dono do produto
("o relatório final em PDF dos processos precisa ter uma capa. Faça uma capa
bonita."). A capa **voltou**, com um desenho novo (ver CLAUDE.md, seção
"Capa do Relatório Final reintroduzida (2026-08-07) — reverte o R6") que
resolve, através de outro caminho, os 3 problemas concretos que a remoção
original tinha corrigido (repetição de tabela, espaço em branco, brasão
duplicado com o carimbo) — não é uma regressão para o estado anterior ao R6,
e sim um terceiro desenho, feito para não reintroduzir os defeitos antigos.

Os Blocos 0, 1, 2 e 3 (R0, R1b+R2, R3b+R4, R5) **continuam implementados e
intactos**, sem reversão nenhuma. Quem for mexer no Relatório Final de novo:
o sumário voltou a ser uma página separada da capa (não é mais a folha de
rosto); a capa é a **primeira** página do documento e é a única página sem
o carimbo institucional do `PdfCabecalhoStamper` (ver
`PdfRelatorioBuilder.gerarCapa` e `PdfCabecalhoStamper.estampar(..., primeiraPagina)`).

---

## 1. Sumário executivo

### 1.1 O que o PR #45 resolveu — confirmado no código e no PDF

Verificado em `RelatorioService.java` e `PdfRelatorioBuilder.java` no `main`
(commit de merge `afd3f54`), e confirmado nos PDFs gerados hoje:

| Achado original | Situação |
|---|---|
| **A1** — "Resultado: ENVIADO" como se fosse decisão | ✅ **Resolvido.** `RelatorioService.java:244-254` testa `isFinalizado()`; o não decidido imprime "Em andamento (processo ainda nao decidido)", sem `linhaDestaque` |
| **A3** — frase da regra ignora a exceção do coordenador | 🟡 **Metade resolvido.** O caso do coordenador ganhou texto próprio (`:205-218`); a **assimetria do indeferimento continua** — ver §4.3 |
| **A4** — parecer impedido some | ✅ **Resolvido** nos dois lugares (`RelatorioService.java:151` e `PdfRelatorioBuilder.java:297`) |
| **A5** — prova do voto ausente | 🟡 **Quase.** `votadoPor`/`dataHoraVoto`/`origem` entraram (`:161-177`); `dataEnvio` do parecer (que daria o prazo de resposta legível no documento) **continua fora** |
| **A6** — `numeroOficio`/`dataEnvioSnt` ausentes | ✅ **Resolvido** (`:258` e `:263`) — mas ver §4.4, a correção agravou o A7 |
| **6.1** — paleta do Bootstrap | 🟡 **Um quinto resolvido.** Só `AZUL` virou institucional (`PdfRelatorioBuilder.java:45`). `CINZA`, `CINZA_BORDA`, `VERDE_ESCURO` e `VERMELHO` continuam sendo `$secondary`, `$gray-300`, `$success` e `$danger` do Bootstrap (`:46-49`) |

**Continuam integralmente em aberto:** A2, A7, A8, A9, A10, A11, A12 e todos os
achados de forma (§6 do relatório original), exatamente como a nota de
atualização daquele documento já registrava.

**A10 merece nota:** era um achado de **um caractere** (`"N do Processo:"`, sem
o `º`), estava listado dentro do escopo do R1 no plano original, e não foi
feito. Continua em `PdfRelatorioBuilder.java:213`, e aparece na capa de todo
relatório emitido desde então.

### 1.2 O que este relatório conclui

**Sobre acentuação — a queixa direta do usuário.** Ele está certo, e a causa
não é técnica. Levantados **28 literais** sem acentuação nos três arquivos que
compõem o documento (§4.9). Dentro da **mesma página** convivem "Envio aos 3
**médicos**" (vem de `EtapaFluxo`, acentuado desde 2026-08-05) e "2. Pareceres
dos **medicos**", "3. Deci**sao** final", "5. Rela**cao** de anexos". O
relatório anterior já dizia que acentuar não custa nada; agora isso está
**provado por experimento** (§5.4) e **respaldado por fonte** (§5.4.2), não
inferido: Helvetica standard-14 com WinAnsiEncoding recupera **100%** do
repertório do português, `º` e `ª` inclusive, num PDF de **1,1 KB sem fonte
incorporada**.

**Sobre "um nível acima".** A pesquisa externa mudou três conclusões do
relatório anterior, e as endureceu:

1. **O corpo de 9pt é menor que o mínimo de toda fonte consultada.** Butterick:
   10–12pt em impresso. Section508: 11–12pt. UKAAF (impressão acessível): 12pt
   mínimo, 14 recomendado. Padrão ofício da Presidência: 12pt. O relatório
   anterior propunha subir para 10pt; **10pt continua sendo o piso, não o
   alvo** — e o Ofício do próprio sistema já usa **11pt** (`OficioService.java:60`).
2. **A faixa colorida chapada como marcador de seção contraria o padrão ofício
   brasileiro**, que é explícito: *"os textos devem ser impressos na cor preta
   em papel branco, reservando-se, se necessário, a impressão colorida para
   gráficos e ilustrações"* e *"para destaques deve-se utilizar, sem abuso, o
   negrito"*. A recomendação P2 do relatório anterior (régua no lugar de faixa)
   deixa de ser preferência estética e passa a ser **conformidade com a norma
   de redação oficial**. Butterick reforça pelo outro lado: bordas pesadas
   *"criam ruído que ofusca a informação"*.
3. **A decisão sobre a fonte (Decisão 2) tem uma condicionante que ninguém
   levantou:** se o Relatório Final um dia precisar ser **PDF/A** (formato de
   arquivamento de longo prazo — plausível para peça de processo
   administrativo), a Helvetica **deixa de poder ser usada sem incorporação**,
   e o cálculo inteiro muda. Enquanto for PDF comum, Helvetica está correta e
   **fica recomendada** — agora com respaldo do próprio Manual de Identidade
   Visual do Governo do RS, que prevê Arial como substituta quando há
   restrição técnica.

**Sobre o estado atual do documento.** Nove achados novos (§4). Os dois de
maior consequência:

- **O Relatório Final não é A4.** É 595 × 897 pt = 21,0 × **31,6 cm**, 6,5%
  mais alto que A4 (§4.1). O `pdfinfo` não o reconhece como A4; o Ofício, sim.
  Impresso em A4 com "ajustar à página", o documento inteiro encolhe 6,5% e o
  corpo de 9pt vira **~8,4pt efetivos** — abaixo de qualquer mínimo citado, num
  documento feito para ser impresso e autuado.
- **A tabela "Andamento do processo" quebra entre páginas e não repeteo
  cabeçalho.** No exemplo INDEFERIDO, a página 2 **termina** com a faixa de
  cabeçalho "Situação | Etapa | Detalhe" e **zero** linhas de dado; a página 3
  começa com as linhas, sem nenhum cabeçalho (§4.2). Falta um
  `setHeaderRows(1)` — uma linha. O protótipo (§6) prova o efeito da correção.

**Medição objetiva da saturação** (§4.7): **11–13% da área da página** de
sumário é preenchimento azul chapado, e isso equivale a **59% a 68% de toda a
tinta da página**. Ou seja: mais da metade do que se imprime numa página do
registro oficial do processo é mobiliário de seção, não conteúdo. O protótipo
derruba para **1,5%**.

**O argumento mais forte não veio de fora, veio de dentro.** O Ofício de
Indeferimento — a régua de qualidade que o projeto estabeleceu deliberadamente
em 2026-08-04 — foi gerado e olhado nesta rodada (§4.10). Ele não tem **uma
única área de cor**: brasão, preto sobre branco, 11pt, justificado, acentuado,
hierarquia por peso e espaço. É exatamente o que o padrão ofício prescreve. Os
dois documentos saem da mesma instituição, no mesmo processo, e não parecem do
mesmo órgão.

---

## 2. Escopo e limites — o que continua valendo do relatório anterior

Tudo da §2 do relatório original permanece:

- **Fora de escopo:** Relatório Anual, Relatório do Avaliador, Ofício e material
  anonimizado aos avaliadores. Aparecem aqui só como referência de consistência.
- **Nenhuma regra de negócio muda.** Maioria simples, exceção do coordenador,
  pausa por `SOLICITA_INFORMACAO`, travas de processo encerrado, whitelist de
  `TipoAnexo` do Portal do Solicitante e imparcialidade do avaliador ficam
  intocadas. As correções de conteúdo **passam a exibir** regras que já existem.
- **`ResultadoParecer.getDescricao()` não deve ser acentuado** — proibição
  documentada no `CLAUDE.md` e no `RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md`
  §10. A solução continua sendo o tradutor local (`switch` no relatório), mesmo
  padrão do `th:switch` de `avaliador/lista.html`.
- **Confidencialidade:** o Relatório Final carrega nome completo do paciente,
  dados clínicos e cópia integral dos anexos. É documento **interno/de
  arquivo/de dossiê**. Nenhuma recomendação deste documento cria rota nova de
  download, alarga whitelist ou aproxima esse PDF de um portal externo.

**Uma correção ao relatório anterior, sobre raio de impacto.** A §2 e a Decisão
5 do documento original dizem que `PdfCabecalhoStamper` é *"compartilhado com o
Relatório Anual"*. São **três** documentos, não dois — `estampar` é chamado por
`RelatorioService.java:88`, `RelatorioAnualService.java:62` e
`RelatorioAvaliadorService.java:64`. Isso importa para dimensionar a Decisão 5,
e é o que permite reduzi-la bastante (§7.5).

---

## 3. Método desta rodada

Mesmo princípio do relatório original: **não se diagnostica um PDF lendo o Java
que o gera.** O que foi feito:

1. **Leitura integral** do estado atual de `RelatorioService.java` (318 linhas),
   `PdfRelatorioBuilder.java` (439), `PdfCabecalhoStamper.java` (347),
   `OficioService.java` (261) e `RelatorioServiceTest.java` (337), mais
   `ExportacaoProcessoService`, `DecisaoFinalService`, `EtapaFluxo`,
   `EstadoEtapa`, `TipoAnexo`, `OrigemParecer` e `AnexoStorageService`.
2. **Quatro geradores temporários** (JUnit, molde de `RelatorioServiceTest`),
   **todos apagados ao final** — a árvore ficou sem nenhum arquivo novo de
   teste:
   - `ZzGerarPdfV2Test` — quatro processos realistas (avaliadores nomeados,
     justificativas clínicas longas, anexo PDF real em disco, anexo PNG,
     parecer impedido, coordenador);
   - `ZzGerarOficioV2Test` — o Ofício, para comparação lado a lado;
   - `ZzProtoV2Test` — **protótipo visual**, escrito inteiramente em escopo de
     teste, **sem tocar em nenhum arquivo de produção** (§6);
   - `ZzCharsetV2Test` — prova empírica do repertório de caracteres (§5.4).
3. **Renderização e leitura visual** de todas as páginas (`pdftoppm -r 100`),
   não inferência a partir do código.
4. **Medições instrumentais**: cobertura de tinta por página (contagem de
   pixels), tamanho real da página (`pdfinfo`), fontes e incorporação
   (`pdffonts`), presença de metadados de acessibilidade (varredura do binário),
   e recálculo independente dos contrastes WCAG (Anexo B).
5. **Pesquisa externa** via agente com acesso a web, com exigência de leitura
   direta da fonte (não snippet de busca) e marcação explícita do que não foi
   possível confirmar (§5.7).

| Arquivo gerado | Processo | Páginas |
|---|---|---|
| `v2-deferido.pdf` | **DEFERIDO**, 2 favoráveis + 1 sem voto, `numeroOficio`, `dataEnvioSnt`, 2 anexos PDF + 1 PNG | 6 |
| `v2-indeferido.pdf` | **INDEFERIDO**, 2 não favoráveis + 1 favorável, motivo longo, ofício `0143/2026` | 3 |
| `v2-andamento.pdf` | **ENVIADO**, nenhum parecer, um avaliador **impedido** | 3 |
| `v2-coordenador.pdf` | **DEFERIDO pelo coordenador** (voto isolado) | 3 |
| `v2-oficio.pdf` | Ofício de Indeferimento (**referência de qualidade**) | 1 |
| `v2-proto.pdf` | **protótipo** da página de sumário redesenhada | 2 |

Todos em
`/tmp/claude-1000/-workspaces-urgencia/c2f2b9a8-b6bb-4bd7-8d28-5e344fb0b523/scratchpad/pdfs/`.
Cada achado abaixo cita em qual deles foi observado.

---

## 4. Achados novos — o que a primeira rodada não viu

Numerados **B1…B9** para não colidir com os A1–A12 do relatório original.

### B1 — O Relatório Final **não é A4**  ⚠ grave
*Medido com `pdfinfo` nos três exemplos.*

```
v2-oficio.pdf     Page size: 595 x 842 pts (A4)
v2-deferido.pdf   Page size: 595 x 897 pts        <- sem rótulo "(A4)"
v2-indeferido.pdf Page size: 595 x 897 pts
v2-andamento.pdf  Page size: 595 x 897 pts
```

**Causa.** `PdfCabecalhoStamper.expandirTopo` (`:161-192`) **aumenta o
MediaBox/CropBox** de cada página em `ALTURA_CABECALHO = 55pt` para desenhar o
carimbo sem cobrir o conteúdo. É uma solução deliberada e boa **para as páginas
de anexo** — documentos clínicos escaneados costumam não ter margem superior
nenhuma, e sobrepor o carimbo destruiria conteúdo. Mas ela é aplicada
**também** às páginas que o próprio sistema gera, que já têm margem sobrando:
`abrirDocumentoA4` (`PdfRelatorioBuilder.java:66`) abre A4 com margem superior
de 30pt, e o comentário no código explica que 30pt foi escolhido **justamente
porque o stamper acrescenta 55pt**. Ou seja: o layout já se planeja em função da
expansão, mas o resultado final deixa de ser A4.

**Consequências práticas, num documento feito para ser impresso e autuado:**

- 897pt = **31,6 cm**, contra 29,7 cm do A4 — **6,5% mais alto**.
- Impresso em A4 com "ajustar à área imprimível" (padrão da maioria dos
  drivers), o documento inteiro encolhe 6,5%: o corpo de 9pt sai a **~8,4pt
  efetivos**. Isso é menor que o mínimo de **todas** as fontes consultadas em
  §5.3 — Butterick (10pt), Section508 (11pt), UKAAF (12pt), padrão ofício
  (12pt).
- Impresso sem ajuste, corta 1,9 cm — que é exatamente onde fica a numeração
  de página (`showTextAligned(..., 22, 0)`, `PdfCabecalhoStamper.java:335`).
- O padrão ofício exige **A4 (29,7 × 21 cm)** explicitamente (§5.1).
- **As páginas de anexo também são esticadas**: um exame escaneado em A4 vira
  897pt e reimprime 6,5% menor.

**Raio de impacto:** afeta os **três** documentos que usam `estampar`
(Relatório Final, Relatório Anual, Relatório do Avaliador) e o material
anonimizado aos avaliadores, que usa a mesma `expandirTopo`
(`SolicitacaoAvaliadorService`). O **Ofício não é afetado** — ele não passa pelo
stamper, e é o único A4 de verdade.

**Correção possível, e barata para a parte que importa.** Para as páginas que o
sistema gera, reservar o espaço do carimbo **no layout** em vez de expandir a
página: abrir o documento do sumário em `595 × 787pt` (A4 menos 55) para que,
após a expansão, resulte **exatamente A4**; ou abrir em A4 com margem superior
de ~85pt e fazer o stamper desenhar dentro dela, sem expandir, para as páginas
próprias. Para as **páginas de anexo importadas** a expansão continua sendo o
comportamento certo — ali não há margem garantida. O documento passaria a ter
tamanhos de página mistos (sumário A4 + anexos esticados), o que é honesto: o
sumário é a parte que se lê e se imprime. **É decisão nova, §7.9.**

### B2 — Tabela quebra entre páginas e **não repete o cabeçalho** ⚠ grave
*Observado em `v2-indeferido.pdf` (p. 2→3) e `v2-deferido.pdf` (p. 2→3).*

No exemplo INDEFERIDO, a página 2 **termina** com a faixa azul de cabeçalho
"Situação | Etapa | Detalhe" e **nenhuma linha de dado**; as cinco linhas
aparecem no topo da página 3, **sem cabeçalho nenhum**. Quem lê a página 3
isolada — que é o que acontece quando o dossiê é fotocopiado ou desmembrado —
vê uma tabela de três colunas sem saber o que cada coluna significa.

**Causa.** Nenhuma das cinco `PdfPTable` do relatório chama `setHeaderRows(1)`,
e nenhuma usa `setKeepTogether`/`setSplitLate`. É o padrão do OpenPDF: quebra
onde couber e não repete nada.

Isto é uma extensão do achado 6.7 do relatório original ("barra de seção órfã
no pé da página"), mas é **pior** do que aquele descrevia: não é só a barra que
fica órfã, é o cabeçalho da tabela inteira, e o dado do outro lado fica sem
rótulo. **Custo da correção: uma linha por tabela.** O protótipo (§6) demonstra
o efeito — lá o cabeçalho se repete corretamente na página 2.

O achado 6.7 original também foi **reconfirmado** de forma independente: em
`v2-andamento.pdf`, a barra "5. Relacao de anexos" é o **último** elemento da
página 2, e o conteúdo dela abre na página 3.

### B3 — A frase da regra continua **assimétrica** no indeferimento
*Observado em `v2-indeferido.pdf`, p. 2.*

O PR #45 corrigiu o caso do coordenador. Mas o terceiro problema que o achado
A3 apontava — a assimetria — **não foi tocado**. Num processo INDEFERIDO, o
documento imprime, logo abaixo da tabela de pareceres:

> *"Favoraveis: 1 (regra: 2 de 3 defere o processo)."*

E, três centímetros abaixo, "Resultado: **INDEFERIDO**". O documento informa
quantos foram **favoráveis** e explica a regra de **deferimento** num documento
cuja conclusão é o oposto; nunca cita `DESFAVORAVEIS_PARA_INDEFERIR`, que é a
constante que governou aquela decisão. O leitor precisa deduzir de cabeça.

Pior no processo **em andamento** (`v2-andamento.pdf`): sem nenhum parecer
emitido, o documento afirma *"Favoraveis: 0 (regra: 2 de 3 defere o
processo)."* — enuncia a regra de deferimento num processo que não está sendo
deferido nem indeferido.

O código está em `RelatorioService.java:219-225` (ramo `else`). A recomendação
P6 do relatório original já previa o texto correto para cada caso; só o ramo do
coordenador foi implementado.

### B4 — A correção do A6 **agravou** o A7 (linhas inaplicáveis)
*Observado nos quatro exemplos.*

A seção "3. Decisão final" é uma lista fixa de linhas, impressa
incondicionalmente (`RelatorioService.java:255-265`). Ela tinha 6 linhas; o PR
#45, ao resolver o A6, acrescentou duas (`Numero do oficio` e `Data de envio ao
SNT`). Como as duas novas são **mutuamente exclusivas por construção** — o
`numeroOficio` só é atribuído em INDEFERIDO (`DecisaoFinalService.java:66-67`) e
o `dataEnvioSnt` só existe em DEFERIDO —, cada uma delas é **garantidamente
"-"** na metade dos processos.

Contagem real de linhas em branco na seção mais importante do documento:

| Cenário | Linhas | Preenchidas | `-` | % de ruído |
|---|---|---|---|---|
| DEFERIDO (`v2-coordenador.pdf`) | 8 | 4 | **4** | 50% |
| INDEFERIDO (`v2-indeferido.pdf`) | 8 | 7 | 1 | 12% |
| EM ANDAMENTO (`v2-andamento.pdf`) | 8 | 2 | **6** | **75%** |

O caso do processo em andamento é o mais desconfortável: uma seção intitulada
**"3. Decisão final"** com seis das oito linhas vazias e a sétima dizendo "Em
andamento (processo ainda nao decidido)". A informação está correta desde o PR
#45 — o **enquadramento** é que não está. O relatório anterior já propunha
(P5) renomear a seção para "3. Situação atual" quando `!isFinalizado()` e
condicionar as linhas ao status; nada disso foi feito, e agora há duas linhas a
mais para condicionar.

**Isto não é crítica ao PR #45** — ele fez o que estava aprovado (A6) e o A7
estava explicitamente fora do escopo daquela rodada. É a constatação de que os
dois achados são acoplados: **A6 e A7 deveriam ter sido feitos juntos**, e
qualquer nova rodada que mexa na seção 3 deve tratá-los como um item só.

### B5 — O comprovante SNT (imagem) **nunca aparece** no corpo do relatório
*Observado em `v2-deferido.pdf`, p. 6.*

O javadoc do `RelatorioService` promete *"cópia integral de todos os documentos
anexados ao processo"*. Isso vale só para os PDFs: qualquer anexo não-PDF vira
uma **página de aviso textual** dizendo *"Este anexo esta disponivel para
download na pagina do processo"* (`PdfRelatorioBuilder.java:114-122`).

O problema é qual anexo costuma ser não-PDF. `AnexoStorageService:34` aceita
`pdf, eml, msg, png, jpg, jpeg`, e o **comprovante de inserção no SNT** é
gerado fora do sistema, tipicamente como **print de tela** — PNG ou JPG. Ou
seja: no relatório de um processo DEFERIDO, **o documento que prova a inserção
no SNT — o desfecho inteiro do processo — não está no documento de arquivo**,
só uma nota dizendo que ele existe em outro lugar. O mesmo vale para um ofício
digitalizado em imagem.

O OpenPDF embute imagem como página com meia dúzia de linhas
(`Image.getInstance` + `scaleToFit` na página de aviso, que já existe e já é
gerada). **Não é achado de layout, é de completude do dossiê** — e vale mais
que qualquer item de tipografia deste relatório.

### B6 — Agora há **dois azuis institucionais diferentes** nos PDFs do sistema

Consequência não intencional do escopo estrito do PR #45, que trocou só o
`AZUL` do `PdfRelatorioBuilder`:

| Documento | Constante | Valor | O que é |
|---|---|---|---|
| **Relatório Final** | `PdfRelatorioBuilder.AZUL` (`:45`) | `#1A4D8F` | `--rs-blue` institucional |
| Relatório Anual | `RelatorioAnualService.AZUL` (`:44`) | `#0D6EFD` | `$primary` do Bootstrap |
| Relatório do Avaliador | `RelatorioAvaliadorService.AZUL` (`:44`) | `#0D6EFD` | `$primary` do Bootstrap |

Antes do PR #45 os três estavam errados **do mesmo jeito**, o que ao menos era
consistente. Hoje um está certo e dois estão errados, e a diferença é visível a
olho nu (azul-marinho ao lado de azul-royal). O relatório original previu isso
na Decisão 6 e recomendou deliberadamente a Opção B ("só o Relatório Final por
ora"), registrando a triplicação como dívida conhecida — a dívida agora
**venceu**. Ver §7.6.

Registro completo da triplicação, hoje: `CINZA = #6C757D` aparece em **quatro**
arquivos (`PdfRelatorioBuilder:46`, `RelatorioAvaliadorService:45`,
`RelatorioAnualService:45`, `SolicitacaoAvaliadorService:34`); `BORDA/#DEE2E6`
em três; `VERMELHO/#DC3545` em dois.

### B7 — Quanto do documento é tinta de mobiliário: **59% a 68%**

Medição instrumental (contagem de pixels na renderização a 100 dpi, amostragem
de 1 em cada 4 pixels), nas páginas de sumário:

| Página | Área em azul chapado | Tinta total | Azul / tinta total |
|---|---|---|---|
| `v2-deferido.pdf` p. 2 | **11,2%** | 19,1% | **59%** |
| `v2-indeferido.pdf` p. 2 | **11,4%** | 18,5% | **62%** |
| `v2-andamento.pdf` p. 2 | **13,1%** | 19,2% | **68%** |
| `v2-deferido.pdf` p. 1 (capa) | 1,9% | 5,1% | 37% |
| **`v2-proto.pdf` p. 1 (protótipo, §6)** | **1,5%** | **12,4%** | **12%** |

O achado 6.2 do relatório original ("o documento é uma sequência de faixas
azuis") era uma impressão visual; agora é um número. **Mais de metade da tinta
de cada página do registro oficial do processo é rótulo organizacional, não
conteúdo.** Em fotocópia, cada uma dessas faixas vira um retângulo cinza-escuro
chapado com texto branco vazado — o pior caso de legibilidade (§5.5).

### B8 — O sistema tem **dois nomes** nos rodapés dos próprios documentos

| Documento | Rodapé |
|---|---|
| Relatório Final (`RelatorioService.java:309`) | "SAUR - Sistema de **Gestao de Processos de** Urgencia Renal" |
| Relatório Anual (`RelatorioAnualService.java:111`) | "SAUR - Sistema de **Avaliacao de** Urgencia Renal" |
| Relatório do Avaliador (`RelatorioAvaliadorService.java:107`) | "SAUR - Sistema de **Avaliacao de** Urgencia Renal" |

É exatamente a classe de problema que `PdfCabecalhoStamper.NOME_INSTITUICAO`
resolveu para o nome do órgão, e cujo javadoc diz existir *"para evitar o que já
aconteceu uma vez: um documento ficar com o nome do órgão desatualizado enquanto
os outros já tinham sido corrigidos"*. O nome do **sistema** tem hoje o problema
que o nome do **órgão** já teve. Custo da correção: uma constante.

### B9 — Metadados de acessibilidade: um acerto e quatro ausências
*Varredura do binário de `v2-deferido.pdf` e `pdffonts`.*

```
Title            -> presente ("Processo CET-RS 07/2026 - Paciente M.A.G.O.")
/Lang            -> 0 ocorrências
/ViewerPreferences (DisplayDocTitle) -> 0
/StructTreeRoot, /MarkInfo (tags)    -> 0    (pdfinfo: "Tagged: no")
/Outlines (marcadores)               -> 0
/ToUnicode                           -> 0    (pdffonts: coluna "uni" = no)
```

O `Title` está correto e **já com as iniciais**, não o nome do paciente — efeito
colateral bem-vindo do trabalho de anonimização de metadados
(`PdfCabecalhoStamper.anonimizarMetadados`), que é exatamente o que a técnica
WCAG **PDF18** pede (§5.5.2).

As ausências têm custos muito diferentes entre si, e **não devem ser tratadas
como um pacote** (§7.10): `/Lang` e `DisplayDocTitle` são **dois campos no
dicionário do documento**, escrita direta pelo stamper, sem tocar em layout;
marcadores são viáveis (§5.6); **tags de estrutura são inviáveis** — metade das
páginas do documento são exames escaneados sem camada de texto nenhuma, e
nenhuma tag conserta isso.

### 4.9 Inventário de acentuação — a queixa do usuário, quantificada

**28 literais** sem acentuação nos três arquivos, levantados por varredura:

| Arquivo | Ocorrências | Exemplos |
|---|---|---|
| `RelatorioService.java` | 21 | `"RELATORIO FINAL - PROCESSO DE URGENCIA RENAL"` (`:113`), `"1. Dados da solicitacao"` (`:124`), `"2. Pareceres dos medicos (Urgencia Renal)"` (`:138`), `"3. Decisao final"` (`:229`), `"5. Relacao de anexos"` (`:285`), `"Numero do processo"` (`:126`), `"Observacoes"` (`:135`), `"Numero do oficio"` (`:258`), `"Data de emissao do oficio"` (`:259`), `"Nao"` (`:265`), `"excecao regimental"` (`:214`), `"Situacao: "` (`:118`) |
| `PdfRelatorioBuilder.java` | 4 | `"URGENCIA RENAL"` (`:185`), `"N do Processo:"` (`:213`, também sem o `º` — achado A10), `"Data de solicitacao da urgencia renal:"` (`:222`), `"Instituicao"` (`:279`) |
| `PdfCabecalhoStamper.java` | 3 | `"Pagina "` (`:334`), `"SECRETARIA DE SAUDE"` (`:44`) |

**O contraste dentro da mesma página** é o que torna isso visível: a seção 4
imprime "Envio aos 3 **médicos**", "Respostas dos **médicos**", "**Decisão**
final", "comprovante de **inserção** no SNT" — porque esses textos vêm de
`EtapaFluxo`, acentuado desde 2026-08-05 — enquanto o título da seção logo
acima diz "2. Pareceres dos **medicos**". Confirmado na extração de texto do
PDF real:

```
44: 2. Pareceres dos medicos (Urgencia Renal)
80: 3. Decisao final
122: Envio aos 3 médicos
128: Respostas dos médicos
```

**Impacto na suíte, verificado.** Acentuar quebra exatamente **4 asserções**, e
todas em `RelatorioServiceTest`: `:249` (`"excecao regimental"`), `:264`
(`"Favoraveis: 2 (regra: 2 de 3 defere o processo)"`), `:320` (`"Numero do
oficio"`) e `:322` (`"Data de envio ao SNT"`). `ProcessoExportacaoIntegrationTest`
**não** é afetado — as strings que ele verifica (`:230` `"Nao favoravel"`,
`:238` `"Envio aos 3 médicos"`) vêm de `ResultadoParecer` (que não muda) e de
`EtapaFluxo` (já acentuado). O E2E localiza o botão **"Relatório Final (PDF)"**
por texto exato — o botão **não pode ser renomeado**, mas nada nesta frente o
renomeia.

**Extração de acentos funciona.** `PdfTextExtractor` recupera corretamente o
texto acentuado do PDF gerado (verificado acima na linha 122), então os testes
de R2 podem asseverar as strings acentuadas diretamente, sem gambiarra.

### 4.10 O Ofício, gerado e olhado — a régua interna, medida

O `OficioService` foi executado e o PDF renderizado. O que ele faz e o
Relatório Final não:

| Elemento | Ofício | Relatório Final |
|---|---|---|
| Tamanho de página | **A4 exato** (595×842) | 595×**897** (B1) |
| Corpo do texto | **11pt** (`:60`) | **9pt** |
| Área de cor | **nenhuma** (só o brasão) | 11–13% da página (B7) |
| Acentuação | **completa** | 28 literais sem acento (§4.9) |
| Alinhamento do corpo | justificado (`:114`) | tabelas à esquerda |
| Cidade e data por extenso | sim, configurável (`:85-92`) | ausente |
| Numeração própria | `numeroOficio` impresso no título (`:81`) | impresso desde o PR #45 ✅ |
| Assinatura | `app.email.assinatura` + linha de assinatura (`:123-126`) | **ausente** |
| Margens | 56pt ≈ 2 cm em todos os lados (`:51`) | 40/40/30/40 pt |

Nenhum desses itens é acidente: o javadoc do `OficioService` (`:29-32`) declara
a acentuação como decisão consciente, e a sessão de 2026-08-04 removeu dele os
placeholders literais justamente para elevá-lo a documento formal. **O
Relatório Final vai para o mesmo dossiê e não recebeu o mesmo tratamento.**

O ponto onde o Ofício sozinho **decide** um argumento deste relatório: ele não
usa **cor nenhuma**. Não é omissão — é o que o padrão ofício prescreve (§5.1).
A pergunta da Decisão 1 deixa de ser "que azul usar" e passa a ser "por que
este documento usa faixa colorida quando o documento formal irmão, feito depois
e com mais cuidado, não usa nenhuma".

---

## 5. Pesquisa externa — fontes

Cada achado traz a URL e o que a fonte diz. Marcações: **[lida]** = a fonte foi
aberta e o texto extraído; **[mirror]** = o conteúdo é de um espelho, não do
domínio canônico (a fonte canônica falhou); **[não confirmado]** = só apareceu
em resultado de busca, **não deve ser citado**.

### 5.1 Padrão ofício — Manual de Redação da Presidência da República, 3ª ed. (2018)

**[lida — via reprodução em `https://juspodium.net/manual_redacao_oficial.pdf`;
a URL canônica do Planalto falhou com `ECONNRESET` em duas tentativas]**
**Corroborada** por documento oficial `gov.br`: Guia de Padronização de
Documentos da ANP, `https://www.gov.br/anp/pt-br/centrais-de-conteudo/publicacoes/cartilhas-e-guias/guiapadronizacaooficialanp.pdf`
**[lida]**, que reproduz os mesmos valores palavra por palavra.

Regras que incidem diretamente sobre este relatório:

| Regra (§5.1.6 III e §5.2) | Valor prescrito | Estado do Relatório Final |
|---|---|---|
| Tamanho de papel | **A4 (29,7 × 21 cm)** | ❌ 31,6 × 21 cm (B1) |
| Corpo do texto | **12 pontos** | ❌ 9 pontos |
| Notas de rodapé | 10 pontos | ❌ 8 pontos |
| Espaçamento entre linhas | simples | ✅ |
| Margens | esq. ≥3 cm · dir. 1,5 cm · sup./inf. 2 cm | ❌ 40pt ≈ 1,4 cm nas laterais |
| Numeração de páginas | **centralizada, no rodapé, a partir da 2ª página** | ❌ à direita, em todas as páginas |
| **Cores** | *"os textos devem ser impressos na cor preta em papel branco, reservando-se, se necessário, a impressão colorida para gráficos e ilustrações"* | ❌ faixas coloridas + texto branco vazado |
| **Destaques** | *"para destaques deve-se utilizar, sem abuso, o negrito. Deve-se evitar destaques com uso de itálico, sublinhado, letras maiúsculas, sombreado, sombra, relevo, bordas ou qualquer outra forma de formatação que afete a sobriedade e a padronização do documento"* | ❌ usa itálico (justificativas, fechos), caixa alta (`linhaDestaque`) e sombreado (faixas) |
| Fonte | Calibri ou Carlito | — (ver §7.2) |

**Duas ressalvas honestas sobre como usar esta fonte:**

1. O padrão ofício governa **comunicações oficiais** (ofício, memorando,
   exposição de motivos) — texto corrido. O Relatório Final é majoritariamente
   **tabular**, e o manual não trata de tabela. Não se deve usá-lo como se
   fosse norma vinculante sobre este documento.
2. Ainda assim, ele é **a régua de sobriedade** que a administração pública
   brasileira publicou, e as duas regras que mais importam aqui — **cor** e
   **destaques** — são sobre *sobriedade do documento*, não sobre a tabela.
   Elas se aplicam por analogia direta, e o Ofício do próprio sistema já as
   segue.

**A regra de cor é estável há mais de 20 anos.** A 2ª edição (2002) já dizia:
*"a impressão dos textos e do timbre deve ser feita na cor preta em papel
branco. A impressão colorida deve ser usada apenas para gráficos e
ilustrações"* — confirmado em livro-texto UFSC/CAPES,
`https://educapes.capes.gov.br/bitstream/capes/401192/1/RedacaoOficial-3ed-web-atualizado.pdf`
**[lida]**. Não é uma preferência da edição atual.

### 5.2 Identidade visual de governo

**Manual de Identidade Visual do Governo do Estado do Rio Grande do Sul (2023)**
— `https://cultura.rs.gov.br/upload/arquivos/202305/08143924-miv-o-futuro-nos-une.pdf`
**[lida]**. É o manual do **próprio ente ao qual a Central de Transplantes
pertence**, e traz um achado decisivo para a Decisão 2:

> *"A Gotham — fonte de design limpo e fácil leitura — será o tipo oficial para
> as comunicações do Governo do Rio Grande do Sul e suas secretarias. **Quando
> não for possível usar a fonte Gotham por razões técnicas, recomenda-se a
> fonte Arial.**"*

Ou seja: **o manual institucional do RS prevê explicitamente o fallback para
uma fonte de sistema quando há restrição técnica.** Helvetica é a fonte
metricamente equivalente à Arial entre as 14 padrão do PDF — o que enquadra a
escolha atual não como um "vamos deixar como está", e sim como **exatamente o
caminho que o manual prescreve**.

Outras regras úteis do mesmo manual: aplicação monocromática prevista para
impressão em uma cor (*"a versão em tons de cinza da marca deve ser usada apenas
em materiais cuja impressão será feita na cor preta"*), e área de proteção da
marca definida em múltiplos da altura da letra "O". **Não há** seção de
papelaria/ofício/A4 nesse manual (busca negativa) — ele não cobre documento
administrativo, o que é mais uma razão para o padrão ofício ser a referência
para isso.

**Manual de Identidade Visual do Governo de São Paulo (GESP v1.6, 2023)** —
`https://sigam.ambiente.sp.gov.br/sigam3/repositorio/559/documentos/GESP_MANUAL_DE%20IDENTIDADE_VISUAL_2023.pdf`
**[lida]**. Relevante como padrão de método, não de valor: organiza a cor em
*"dois grupos hierárquicos PALETA PRINCIPAL e PALETA SECUNDÁRIA"* e prevê
versões monocromáticas (preta, branca, cinza 50%, cinza 25%). É o modelo do que
uma `PaletaPdf` compartilhada deveria ser (§7.6): poucas cores, com hierarquia
declarada, não cinco constantes soltas replicadas em quatro arquivos.

**Manual do Governo Federal na Internet (SECOM/PR)** —
`https://nationbrandingnow.com/assets/SA/BR/lula/internet.pdf` **[lida]
[mirror — não encontrei cópia em domínio `.gov.br`]**. Especifica cor
(`#FFCC00`) e tipografia do identificador em pontos exatos. Citado apenas para
registrar que manuais de governo especificam tipografia em **pontos absolutos**,
não em escalas relativas — o que sustenta a recomendação de uma escala
declarada (§7.4).

### 5.3 Tipografia e hierarquia

**Butterick's Practical Typography — "Summary of key rules"** —
`https://practicaltypography.com/summary-of-key-rules.html` **[lida]**:

- *"Point size should be **10–12 points in printed documents**"*
- *"Line spacing should be **120–145%** of the point size"*
- *"The average line length should be **45–90 characters**"*
- *"Use bold or italic **as little as possible**, and not together"*
- *"All caps are fine for **less than one line** of text"* — e recomenda 5–12%
  de entreletra extra em caixa alta

**Butterick — "Rules & borders"** — `https://practicaltypography.com/rules-and-borders.html`
**[lida]**. É a fonte que responde diretamente à escolha "filete vs. faixa
sólida" da proposta P2:

- *"Rules and borders are best used **sparingly**. Ask yourself: do you really
  need a rule or border to make a visual distinction?"*
- *"You can usually get equally good results by **increasing the space** above
  and below the text. **Try that first.**"*
- *"For borders, set the thickness between **half a point and one point**"*
- bordas grossas *"create noise that **upstages the information inside**. You
  want to see the data, not the lines around the data."*

**US Web Design System** — `https://designsystem.digital.gov/components/typography/`
e `https://designsystem.digital.gov/design-tokens/typesetting/line-height/`
**[lidas]**:

- *"For most text, including body copy, use at least an effective size of 16px"*
- *"Most lines of text should be 45–90 characters"*, alvo 66
- entrelinha de texto corrido: **1.62**; de títulos: **1.15**
- *"Headings should be closer to the text they introduce than the text that
  precedes them"* — ao menos **1,5× mais espaço acima do título do que abaixo**
- *"consider replacing long sections of bold or italic text with a callout box,
  a section header, or some other technique that avoids extended stretches of
  styled text"*

**Section508.gov — Fonts and Typography** — `https://www.section508.gov/develop/fonts-typography/`
**[lida]**: *"use a typical font size of **11 or 12pt**"*; *"Sans serif is most
important for body text and fluid reading"*; e a confirmação dos limiares de
contraste (4.5:1 normal, 3:1 para texto grande e ícones).

**UKAAF G003 — Creating clear print and large print documents** —
`https://www.ukaaf.org/wp-content/uploads/2024/12/G003-UKAAF-Creating-clear-print-and-large-print-documents-v4.pdf`
**[lida]**: *"For clear print use **12 point text size minimum**, though 14
point is recommended"*; *"Space between paragraphs ideally minimum of one blank
line"*; *"In general text and headings should be **left-aligned**"*.

**GOV.UK — Publishing accessible documents** —
`https://www.gov.uk/guidance/publishing-accessible-documents` **[lida]**: *"Use
a sans serif font like **Arial or Helvetica**. Use a minimum size of 12
points"*; *"Make sure the text is left aligned, not justified"*; *"Do not use
things like colour or shape alone to show meaning"*.

> **[não confirmado]** A "regra dos 3 níveis de hierarquia tipográfica" (título,
> subtítulo, corpo), que circula amplamente, **não foi encontrada em nenhuma
> fonte técnica ou normativa** — só em blogs. **Não deve ser citada.** O que é
> defensável, e vem do USWDS, é o inverso e mais preciso: manter o texto corrido
> numa faixa **estreita** de tamanhos e reservar os menores para legendas e
> notas. O problema real do documento (9 tamanhos entre 8 e 16, achado 6.3 do
> relatório original) fica caracterizado por esse critério, sem precisar da
> regra dos 3 níveis.

### 5.4 Codificação de caracteres — o bloco decisivo

#### 5.4.1 Prova empírica local

Antes das fontes externas, o experimento direto. Um PDF gerado com
`FontFactory.getFont(FontFactory.HELVETICA, ...)` — exatamente a chamada que o
relatório usa — contendo o repertório do português:

```
áàâãéêíóôõúüç ÁÀÂÃÉÊÍÓÔÕÚÜÇ nº 1ª 2º § °
URGÊNCIA RENAL - Decisão, Relação, Observações, Não favorável, Ofício, Página, Saúde
```

nas três variantes usadas pelo documento (normal, negrito, itálico), seguido de
extração e conferência caractere a caractere:

```
CHARSET: caracteres NAO recuperados do PDF: []
CHARSET: tamanho do PDF = 1181 bytes (fonte NAO incorporada)
```

E a confirmação de que nada foi incorporado:

```
$ pdffonts v2-charset.pdf
name              type      encoding   emb  sub  uni
Helvetica         Type 1    WinAnsi    no   no   no
Helvetica-Bold    Type 1    WinAnsi    no   no   no
Helvetica-Oblique Type 1    WinAnsi    no   no   no
```

**Zero caracteres perdidos. `º` e `ª` inclusive. 1,1 KB. Nenhuma fonte
incorporada.** O custo de acentuar o documento inteiro é, literalmente, **zero
byte**.

#### 5.4.2 Fontes que confirmam

- **PDF Reference, Apêndice D ("Character Sets and Encodings")** —
  `https://eclecticgeek.com/dompdf/debug_tests/charsetsupport.htm` **[lida]
  [mirror — a ISO 32000 é norma paga e não tem URL pública; o mirror alternativo
  em verypdf.com retornou 404]**. A tabela lista código octal em
  WinAnsiEncoding para **todos** os caracteres do português: `á`(341) `à`(340)
  `â`(342) `ã`(343) `é`(351) `ê`(352) `í`(355) `ó`(363) `ô`(364) `õ`(365)
  `ú`(372) `ç`(347), as maiúsculas correspondentes, `º`(272) e `ª`(252). E
  declara: *"This is the character set supported by the **Times, Helvetica, and
  Courier** font families"*.
- **Windows-1252** — `https://en.wikipedia.org/wiki/Windows-1252` **[lida]**
  (fonte secundária, use em conjunto com a acima): o repertório cobre
  integralmente, entre outras línguas, o **português**.
- **iText Knowledge Base, cap. 6** — `https://kb.itextpdf.com/itext/chapter-6-using-fonts-in-pdfhtml`
  **[lida]** (OpenPDF é fork do iText 4; a mecânica de encoding é a mesma):
  *"you **don't have to embed** these fonts when creating a PDF document,
  because you can expect that every PDF viewer knows how to render these
  fourteen fonts"* e *"iText uses the **Winansi** encoding for the Helvetica,
  Times, and Courier font family"*.
- **Quando Identity-H é realmente necessário** — mesma fonte, cap. 6 e cap. 1
  (`https://kb.itextpdf.com/itext/chapter-1-introducing-fonts` **[lida]**):
  *"if you want support for **more than 256 characters** in one font, you need a
  composite font"*. Winansi *"is a superset of ISO 8859-1"* e **não** representa
  caracteres do centro/leste europeu (ex.: `ř`), cirílico ou CJK. **Nada disso
  ocorre no português.**

**Conclusão do bloco, sem hesitação:** para escrever português correto neste
PDF **não é necessário incorporar fonte nenhuma nem usar Identity-H**. A
Decisão 2 está resolvida — com **uma** condicionante, a seguir.

#### 5.4.3 A condicionante que ninguém levantou: PDF/A e PDF/UA obrigam a incorporar

- **Apache FOP, documentação de PDF/A** — `https://xmlgraphics.apache.org/fop/2.0/pdfa.html`
  **[lida]**: *"Make sure **all (!)** fonts are embedded. If you use **base 14
  fonts (like Helvetica)** you need to obtain a license for them and embed them
  like any other font."*
- **PDFlib, requisitos de PDF/UA-1 (ISO 14289)** —
  `https://www.pdflib.com/pdf-knowledge-base/pdfua/requirements/` **[lida]**:
  *"All fonts used in the document must be embedded"*, *"The document title must
  be specified in the document's metadata"*, *"The document must be tagged"*,
  *"The natural language of text must be declared"*, *"PDF/UA requires proper
  Unicode semantics for all text"*.
- **iText KB cap. 6** **[lida]**: o mapeamento `toUnicode` *"is a requirement
  for PDF/A Level U, and it's a requirement in terms of accessibility"* — e o
  `pdffonts` acima mostra `uni = no` nos PDFs de hoje.

**Consequência prática:** enquanto o Relatório Final for um PDF comum,
Helvetica não incorporada está **correta, barata e recomendada**. Se algum dia a
instituição exigir **PDF/A** para arquivamento de longo prazo — cenário
plausível para peça de processo administrativo —, a conta inverte: seria preciso
incorporar um arquivo de fonte, e a Helvetica das 14 padrão **é licenciada**, o
que na prática significa trocar por uma fonte livre **metricamente compatível**
(Liberation Sans ou DejaVu Sans), não pela Inter do sistema. Isso é uma
**decisão nova**, §7.10 — e é a única coisa que poderia mudar a resposta da
Decisão 2.

> **[não confirmado]** As normas ISO 32000, ISO 19005 (PDF/A) e ISO 14289
> (PDF/UA) são pagas e não têm URL pública; `pdfa.org` retornou 403. Todas as
> afirmações acima vêm de documentação de implementadores (Apache FOP, PDFlib,
> iText), não do texto normativo. É respaldo suficiente para uma decisão de
> produto, **não** para uma declaração de conformidade formal.

### 5.5 Acessibilidade aplicada a PDF

**WCAG 2.2, 1.4.3 Contrast (Minimum)** — `https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum`
**[lida]**: mínimo de **4.5:1** para texto normal; **3:1** para texto grande,
definido como *"at least **18 point** or **14 point bold**"*. Logotipos e
elementos puramente decorativos são isentos.

Aplicando ao documento (contrastes recalculados de forma independente, Anexo B):

| Elemento | Tamanho | Regra aplicável | Hoje | Situação |
|---|---|---|---|---|
| Barra de seção, texto branco sobre `AZUL` | 11pt bold | 4.5:1 | **8,39:1** | ✅ folgado (ganho do PR #45; era 4,50:1) |
| Cabeçalho de tabela, branco sobre `AZUL` | 9pt bold | 4.5:1 | **8,39:1** | ✅ |
| `linhaDestaque` "DEFERIDO" verde | **13pt** bold | **4.5:1** (13 < 14, **não** é texto grande) | **4,53:1** | 🟡 passa por 0,03 |
| "Não favorável" vermelho | 9pt | 4.5:1 | **4,53:1** | 🟡 passa por 0,03 |
| `CINZA` em rodapés e na frase da regra | **8pt** | 4.5:1 | **4,69:1** | 🟡 passa por 0,19 |
| Bordas de tabela `CINZA_BORDA` | — | isento (não é texto) | 1,30:1 | — |

Com a paleta institucional completa: verde `#1F6B36` → **6,53:1**; vermelho
`#8B1A1A` → **9,29:1**; cinza `#475569` → **7,58:1**. **A margem sobre o mínimo
passa de 0,03 para 2 a 4,8 pontos de razão** — e o mínimo da WCAG foi calibrado
para **tela**, não para papel fotocopiado.

**Técnica WCAG PDF18 (Title + DisplayDocTitle)** —
`https://www.w3.org/WAI/WCAG22/Techniques/pdf/PDF18` **[lida]**: o título
descritivo deve estar em `/Title` **e** o `DisplayDocTitle` deve estar ligado no
dicionário de preferências. Atende ao critério **2.4.2 Page Titled**. O sistema
já faz metade (§B9).

**Técnica WCAG PDF16 (/Lang)** — `https://www.w3.org/WAI/WCAG22/Techniques/pdf/PDF16`
**[lida]**: declarar o idioma padrão no catálogo do documento; atende ao
critério **3.1.1 Language of Page**. *"Both assistive technologies and
conventional user agents can render text more accurately when the language of
the document is identified."* Ressalva lida na própria página: a técnica *"applies
specifically to tagged PDF documents"*, e as técnicas do W3C são exemplos, não
requisitos.

**Texto branco sobre fundo colorido chapado, em impresso** — UKAAF G003
**[lida]**:

> *"Reversing out type (white on dark background) is preferred by some readers
> as it also reduces paper glare. When doing this, use a dark background colour
> with a bold font as white text can appear smaller; **or if badly printed the
> darker ink may start to fill in the white**. The text size may also need to be
> increased to compensate for this."*

E o guia GOV.UK repackaged pelo Glasgow City HSCP
(`https://glasgowcity.hscp.scot/sites/default/files/publications/How%20to%20Create%20Accessible%20Print%20Publications.pdf`
**[lida]**): *"Avoid using blocks of capital letters in titles or body text"*;
*"Tints can be helpful to break up a document… **Make sure there is a strong
contrast between text and tint**"*.

**Síntese:** texto branco sobre faixa sólida **não é proibido**, mas todas as
fontes o condicionam — fundo escuro o bastante, peso negrito, corpo
possivelmente aumentado — e alertam para o risco específico de **a tinta
escura preencher o branco em impressão ruim ou fotocópia**, que é exatamente o
cenário de um dossiê administrativo. Somado à regra de cor do padrão ofício
(§5.1), o argumento contra a faixa sólida como marcador de seção se sustenta em
**duas linhas independentes de evidência**.

### 5.6 O que é tecnicamente viável no OpenPDF 1.3.34 — verificado no `.jar`

Inspeção direta do artefato (`javap`/`unzip` sobre
`openpdf-1.3.34.jar`), para que nenhuma recomendação dependa de suposição:

| Recurso | Disponível? | Como |
|---|---|---|
| Marcadores/bookmarks num PDF resultante de merge | ✅ | `SimpleBookmark` + `PdfCopy.setOutlines(List)` / `PdfCopy.add(PdfOutline)` — a receita clássica do iText 2 |
| `/Lang` no catálogo | ✅ | escrita direta no dicionário via `PdfStamper` (o stamper já manipula `/Info` e XMP) |
| `DisplayDocTitle` | ✅ | `/ViewerPreferences` no catálogo, mesmo caminho |
| Repetir cabeçalho de tabela na quebra (B2) | ✅ | `PdfPTable.setHeaderRows(1)` — **demonstrado funcionando** no protótipo (§6) |
| Manter seção junto do conteúdo (6.7) | ✅ | `setKeepTogether(true)` / `setSplitLate` |
| Imagem como página do documento (B5) | ✅ | `Image.getInstance` + `scaleToFit` na página que já é gerada |
| PDF marcado (tags) | ⚠ existe (`setTagged()`, `PdfStructureElement`) | **inviável na prática**: metade das páginas são exames escaneados sem camada de texto |

### 5.7 Tensões reais entre as fontes — registradas, não escondidas

1. **Alinhamento justificado.** O padrão ofício **exige** justificado (§5.1); a
   literatura de acessibilidade impressa (UKAAF, GOV.UK, Glasgow HSCP)
   **desaconselha**, porque o espaçamento irregular entre palavras pode ser
   confundido com fim de linha. É conflito normativo genuíno, não erro de uma
   das partes. **Para este documento o conflito quase não incide**, porque ele é
   majoritariamente tabular — só o fecho e a frase da regra são texto corrido.
   Recomendação: manter à esquerda, como já está.
2. **Corpo de texto.** Butterick 10–12, Section508 11–12, UKAAF 12 (14
   recomendado), padrão ofício 12. **A faixa de consenso começa em 10 e o alvo
   confortável é 11–12.** Os 9pt de hoje estão abaixo de todos, e os ~8,4pt
   efetivos após o encolhimento do B1 estão muito abaixo.
3. **Manuais de identidade visual não cobrem documento administrativo.** Os
   manuais do RS e de SP tratam de marca, cor e aplicação em peças de
   comunicação; nenhum deles prescreve formatação de ofício ou relatório. Por
   isso a régua para este documento é o **padrão ofício** (para sobriedade e
   formatação) e o **manual do RS** (para cor institucional e fonte).

---

## 6. Protótipo — a proposta gerada e medida, não descrita

Para que as recomendações da §7 não sejam abstratas, a página de sumário foi
reescrita no estilo proposto e **gerada de verdade**. O protótipo foi escrito
**inteiramente em escopo de teste** (`ZzProtoV2Test`, desde então apagado),
reimplementando os helpers localmente — **nenhum arquivo de produção foi
tocado**, e a árvore foi verificada antes e depois.

O que o protótipo aplica: paleta institucional completa · seção como **título
tipográfico com filete de 1,5pt** em vez de faixa chapada · cabeçalho de tabela
em cinza `#F1F5F9` com texto preto e filete azul inferior · **corpo a 10pt** ·
acentuação completa · seção 3 **condicional** (só as linhas aplicáveis ao
DEFERIDO) · frase da regra correta e simétrica · coluna "Situação" por extenso
em vez de `[X]`/`[>]`/`[ ]` · `setHeaderRows(1)` em todas as tabelas · coluna
"Página" na relação de anexos.

### Medição antes/depois

| Métrica | Hoje | Protótipo | Δ |
|---|---|---|---|
| Área da página em azul chapado | **11,2%** | **1,5%** | −87% |
| Tinta total na página | 19,1% | 12,4% | −35% |
| Azul chapado como fração da tinta | **59%** | **12%** | — |
| Corpo do texto | 9pt | 10pt | +11% |
| Linhas em branco (`-`) na seção 3, caso DEFERIDO | 4 de 8 | **0 de 4** | — |
| Cabeçalho repetido na quebra de tabela | não | **sim** | — |

### O que o protótipo confirmou empiricamente

- **`setHeaderRows(1)` resolve o B2.** Na página 2 do protótipo, o cabeçalho
  "Situação | Etapa | Detalhe" **reaparece** corretamente acima das linhas que
  transbordaram. Não é teoria.
- **O risco previsto para o corpo a 10pt é real e se manifestou.** A relação de
  anexos, com proporções `{4.5, 3.5, 1.2, 1.3}`, quebrou a data em duas linhas
  (`10/03/202` / `6`). O relatório original já alertava que subir o corpo exige
  **reajustar as proporções olhando o PDF**; aqui está o caso concreto. Isso
  **não invalida** a recomendação — mostra que a fase R4 não pode ser feita sem
  inspeção visual, exatamente como o plano prescreve.
- **A régua funciona sem cor chapada.** A hierarquia continua imediatamente
  legível: título em azul-escuro caixa alta sobre filete, dados em preto. Em
  fotocópia, o filete sobrevive; a faixa chapada vira mancha.

---

## 7. As 8 decisões — recomendação fundamentada

O relatório anterior apresentou opções. Aqui vai a **recomendação**, com a
evidência que a sustenta. A decisão continua sendo do dono do produto.

### 7.1 Decisão 1 — Paleta: manter o Bootstrap ou adotar o institucional
**Recomendação: adotar o institucional, e completar o que ficou pela metade.**
Confiança: **alta**.

Já é meio-caminho: `AZUL` foi trocado no PR #45. As outras quatro constantes
(`CINZA`, `CINZA_BORDA`, `VERDE_ESCURO`, `VERMELHO`) continuam sendo defaults do
Bootstrap (`PdfRelatorioBuilder.java:46-49`) — o documento oficial ainda imprime
"Favorável" no verde `$success` e "Não favorável" no vermelho `$danger` de um
framework de front-end.

Evidência: os dois valores de hoje passam a WCAG AA por **0,03** de margem
(4,53:1 contra 4,5 exigidos); os institucionais dão **6,53:1** e **9,29:1**
(Anexo B). Num documento destinado a papel e fotocópia, uma margem de 0,03 não é
margem. O ganho de identidade é o argumento **menos** forte dos dois.

Custo: quatro constantes. Risco: nenhum para a suíte (nenhum teste olha cor);
exige **gerar e olhar** o PDF.

### 7.2 Decisão 2 — Fonte: Helvetica ou incorporar uma TTF
**Recomendação: manter Helvetica não incorporada.** Confiança: **muito alta**
— agora por evidência, não por inércia. **Reavaliar apenas se PDF/A entrar em
cena (§7.10).**

Três apoios independentes:

1. **Empírico** (§5.4.1): 100% do repertório do português recuperado, incluindo
   `º` e `ª`, num PDF de 1,1 KB sem fonte incorporada.
2. **Técnico** (§5.4.2): PDF Reference Apêndice D lista os códigos WinAnsi de
   todos os caracteres; iText KB confirma que as 14 padrão não precisam ser
   incorporadas e que Identity-H só é necessário acima de 256 glifos ou fora do
   repertório Latin-1 — nada disso é o caso.
3. **Institucional** (§5.2): o Manual de Identidade Visual do **Governo do RS**
   prevê **Arial** quando a fonte oficial não é viável por razões técnicas.
   Helvetica é a equivalente métrica entre as 14 padrão. A escolha atual é a que
   o manual do próprio ente prescreve.

Contra a Opção B (incorporar TTF): a Inter do projeto está em `.woff2`
(`static/fonts/inter-{400,600,700}.woff2`), formato que o OpenPDF **não**
incorpora; seria preciso versionar `.ttf`, registrar a licença OFL e engordar o
JAR — para o menor ganho de todo o plano. **Não recomendada.**

### 7.3 Decisão 3 — Rotular "RELATÓRIO PARCIAL" quando o processo não terminou
**Recomendação: rotular (Opção A). Não bloquear a rota.** Confiança: **alta**.

`GET /processos/{id}/relatorio` (`ProcessoAnexoController.java:271-277`) não tem
guarda de status: qualquer processo, inclusive recém-convertido, produz um PDF
intitulado "RELATORIO FINAL". O operador tem uso legítimo de imprimir o
andamento parcial — bloquear tira uma capacidade real, e o precedente do Ofício
(que passou a recusar 400 fora de `INDEFERIDO`) não se aplica: lá o documento
**contradizia** a decisão; aqui ele só está **incompleto**.

O achado B4 reforça: hoje, um processo em andamento gera um documento cujo
título diz "FINAL" e cuja seção "3. Decisão final" tem **6 de 8 linhas vazias**.
Rotular corrige a promessa do título; renomear a seção para "3. Situação atual"
(P5) corrige o resto. **Os dois andam juntos.**

### 7.4 Decisão 4 — O retrato guardado na decisão (A8)
**Recomendação: mantida a do relatório original — (c) datar o retrato + (d)
filtrar a duplicata do ZIP.** Confiança: **média-alta**.

Reconfirmado no código: `ExportacaoProcessoService.montarDossie` copia **todos**
os anexos (`:178`, `new ArrayList<>(p.getAnexos())`, sem filtro) **e** gera o
relatório ao vivo (`:182`). O dossiê entregue a um auditor contém, hoje, dois
arquivos com a mesma função e conteúdo diferente: `Relatorio-Final.pdf` na raiz
(estado atual) e `NN - Relatorio final do processo - ....pdf` em `Anexos/` (o
retrato do momento da decisão, que nunca inclui o ofício nem o comprovante SNT).

(c) e (d) são baratas, não tocam fluxo e resolvem o que chega ao auditor. (a) —
regerar ao concluir a etapa 6 — é a solução "certa" e pode vir depois, em PR
próprio com teste de integração real (escrita irreversível, convenção do
projeto). **Não misturar com as fases visuais.**

### 7.5 Decisão 5 — Tocar ou não o `PdfCabecalhoStamper`
**Recomendação: tocar, e o raio de impacto é bem menor do que se supunha.**
Confiança: **alta**.

Medição precisa do que muda ao acentuar o stamper: **apenas dois literais**.

| Literal | Onde | Quem é afetado |
|---|---|---|
| `"Pagina "` → `"Página "` | `PdfCabecalhoStamper.java:334` | Relatório Final, Relatório Anual, Relatório do Avaliador (**não** o material aos avaliadores, que não numera páginas) |
| `"SECRETARIA DE SAUDE"` → `"SECRETARIA DE SAÚDE"` | `:44` | as capas dos mesmos três relatórios |

O que **não** está no stamper, e por isso é de raio zero: `"URGENCIA RENAL"` é
literal **do chamador** — `RelatorioService.java:89`, `RelatorioAnualService.java:63`,
`RelatorioAvaliadorService.java:65`, `SolicitacaoAvaliadorService.java:154`.
Acentuar o do Relatório Final **não afeta nenhum outro documento**.

Ou seja, a Decisão 5 se reduz a: *"aceito que 'Página' e 'SECRETARIA DE SAÚDE'
fiquem acentuados também no Relatório Anual e no Relatório do Avaliador?"* A
resposta natural é sim — é correção de português em nome de órgão, do mesmo
sinal em todos os documentos. **Recomendada, com uma olhada nos dois PDFs
vizinhos no mesmo PR.** A alternativa (não tocar) deixa o corpo acentuado e o
rodapé dizendo "Pagina 2 de 6" na mesma página — inconsistência visível.

### 7.6 Decisão 6 — `PaletaPdf` compartilhada ou cor só no Relatório Final
**Recomendação: mudou. Agora é a Opção A — classe compartilhada — em PR
próprio.** Confiança: **média-alta**.

O relatório original recomendou a Opção B (só o Relatório Final), para manter o
escopo honesto. Foi a decisão certa **naquele momento**, e foi o que o PR #45
fez. Mas ela criou o B6: **hoje o sistema tem dois azuis institucionais
diferentes** nos seus PDFs oficiais, o que é pior que ter um errado
consistente. A dívida registrada venceu no primeiro uso.

A extração para `PaletaPdf` (ou constantes em `PdfCabecalhoStamper`, que já é o
lugar do "padrão institucional") elimina a triplicação — `CINZA` está hoje em
**quatro** arquivos — e é exatamente o que `NOME_INSTITUICAO` resolveu para o
texto, com o javadoc explicando o porquê. O modelo do manual do GESP (§5.2),
com paleta principal e secundária declaradas, é a referência de como estruturá-la.

**Ressalva que continua valendo:** unificar **muda a cor** do Relatório Anual e
do Relatório do Avaliador, que estão fora do escopo. Por isso: **PR próprio,
com os três PDFs gerados e olhados**, não um efeito colateral de uma fase de
tipografia.

### 7.7 Decisão 7 — Capa: enxugar, eliminar ou manter
**Recomendação: eliminar a capa separada e promover a página de sumário a folha
de rosto (Opção B, bem executada).** Confiança: **média** — é a recomendação
mais discutível deste documento, e a que mais depende de gosto institucional.

O que sustenta:

- **O Ofício — a régua interna, estabelecida deliberadamente — não tem capa**, e
  ainda assim é inequivocamente formal: brasão, nome do órgão, título, corpo.
- A capa é **subconjunto** do sumário: `Nº`, paciente, RGCT, data da decisão,
  resultado e a tabela de avaliadores reaparecem inteiros nas seções 1, 2 e 3
  (achado 6.6). E ainda sobra um terço de página em branco.
- **Dois brasões e dois nomes do órgão na mesma página**: o carimbo já imprime
  "Central de Transplantes… - URGENCIA RENAL" a ~1 cm do topo, e a capa repete o
  nome 3 cm abaixo com um segundo brasão.
- Economiza **uma página por relatório emitido**.
- O padrão ofício não prevê capa para comunicação oficial.

Contra: alguns setores esperam folha de rosto em peça autuada. Se essa
expectativa existir na Central de Transplantes — **é o dono do produto quem
sabe** —, a Opção A (enxugar: brasão, identificação institucional, título,
número, paciente, resultado e **uma única** data de emissão, sem a tabela de
avaliadores) é perfeitamente defensável e resolve a duplicação sem eliminar a
capa.

**Não recomendada: a Opção C (manter como está)** — ela conserva a duplicação, o
terço vazio e os dois brasões.

### 7.8 Decisão 8 — Divisórias e navegação
**Recomendação: (a) folha divisória + (c) marcadores agora; (b) índice com
página, depois.** Confiança: **alta**.

- **(a) Folha divisória por anexo** — o mecanismo **já existe e já é usado**
  (`adicionarPaginaAviso`, `PdfRelatorioBuilder.java:128-146`), só não é
  chamado no caminho normal. Num processo real de 30–60 páginas de exames
  escaneados, hoje não há **nenhuma** marca de onde termina um anexo e começa o
  outro — o carimbo do topo é idêntico em todas as páginas. Custo: uma página
  por anexo. **Recomendada.**
- **(c) Marcadores/bookmarks** — viabilidade confirmada no `.jar` (§5.6:
  `SimpleBookmark` + `PdfCopy.setOutlines`). Zero efeito visual, ganho grande de
  navegação num dossiê de 40+ páginas. **Recomendada junto com (a).**
- **(b) Índice com página inicial** — exige **duas passagens** de geração
  (montar o sumário, contar páginas, contar as de cada anexo, regerar). Viável,
  mas dobra a geração e cria uma classe de bug nova (índice divergente) que hoje
  não existe. O protótipo (§6) já demonstra a coluna "Página" na relação de
  anexos — o layout está pronto; falta só o mecanismo. **Só se (a) e (c) não
  bastarem.**

### 7.9 Decisão 9 (NOVA) — O documento deve ser A4 de verdade?
**Recomendação: sim, ao menos as páginas de sumário.** Confiança: **alta**.
Ver B1.

O sumário é a parte que se lê, se imprime e se autua; ele **é** gerado pelo
sistema, com margens sob controle, e não tem motivo para não ser A4. As páginas
de **anexo importadas** são outra história: ali a expansão do MediaBox é a
solução certa (evita cobrir conteúdo de scan sem margem) e deve ficar.

O resultado é um documento com tamanhos de página mistos — sumário A4, anexos
esticados. Isso é honesto e melhor que o estado atual, em que **nada** é A4.
Alternativa mais radical, se a mistura incomodar: normalizar tudo para A4
escalando as páginas de anexo na importação, o que é mais caro e degrada
levemente a resolução de scans.

**Impacto:** afeta os três documentos que usam `estampar`. Merece PR próprio,
com verificação por `pdfinfo` (`Page size: 595 x 842 pts (A4)`) além da
inspeção visual.

### 7.10 Decisão 10 (NOVA) — Acessibilidade e arquivamento: até onde ir?
**Recomendação: fazer o barato agora; declarar PDF/A e PDF/UA fora de escopo até
que alguém os exija formalmente.** Confiança: **alta**.

Três níveis, com custos que diferem em ordens de grandeza:

| Nível | O que envolve | Custo | Recomendação |
|---|---|---|---|
| **1. Barato** | `/Lang = "pt-BR"` + `DisplayDocTitle = true` no catálogo (`Title` **já existe**) | duas escritas no dicionário, no stamper; zero efeito visual | **Fazer.** Atende às técnicas WCAG PDF16 e PDF18 |
| **2. Caro** | **PDF/A** (arquivamento de longo prazo) | obriga incorporar **todas** as fontes, inclusive Helvetica → trocar por fonte livre metricamente compatível (Liberation Sans/DejaVu), `ToUnicode`, perfil de cor, XMP conforme | **Não fazer agora.** Mas **saber que existe**: é a única coisa que inverte a Decisão 2 |
| **3. Inviável** | **PDF/UA** (tags de estrutura) | tudo do nível 2 + documento marcado | **Não fazer.** Metade das páginas são exames escaneados sem camada de texto; nenhuma tag conserta isso |

**A pergunta que o dono do produto deve levar adiante:** *alguém — jurídico,
auditoria, o arquivo do Estado — exige ou vai exigir PDF/A para peça de
processo administrativo?* Se sim, isso reordena o plano inteiro e a Decisão 2
muda. Se não, o nível 1 é tudo que vale a pena fazer.

---

## 8. Plano de execução atualizado

A ordem do relatório original continua correta em espírito — **primeiro o que o
documento diz, depois como ele parece** — mas ganha itens novos e uma
reordenação por causa do B1 e do B2, que são baratos e de alto retorno.

> **Regra obrigatória, mantida e reforçada:** toda fase roda `.\test.ps1` (JDK
> 21) **e gera PDFs de verdade nos quatro estados** (DEFERIDO com anexos,
> INDEFERIDO, EM ANDAMENTO, DEFERIDO-POR-COORDENADOR) **e alguém olha**. A §9 do
> relatório original explica por que a suíte não substitui isso — e o B2, que
> conviveu com a suíte verde desde sempre, é a prova.

### R0 — Higiene estrutural · risco muito baixo · **melhor retorno por linha**
*(fase nova, não existia no plano original)*
- **B2**: `setHeaderRows(1)` nas cinco tabelas. Uma linha cada.
- **A10**: `"N do Processo:"` → `"Nº do Processo:"`. Um caractere.
- **B8**: unificar o nome do sistema numa constante.
- **Nível 1 da Decisão 10**: `/Lang` + `DisplayDocTitle`.

Nada aqui muda layout ou cor. Se só uma fase for aprovada, que seja esta.

### R1b — Conteúdo, o que sobrou · risco baixo · **maior valor**
Completa o R1 do plano original:
- **B3** — frase da regra simétrica e correta em todos os casos (P6 do relatório
  original; só o ramo do coordenador foi feito).
- **B4 + A7** — seção 3 **condicional**, tratados como item único: renomear para
  "3. Situação atual" quando `!isFinalizado()`, e mostrar apenas as linhas
  aplicáveis ao status.
- **A5 (resto)** — `dataEnvio` do parecer, para o prazo de cada avaliador ficar
  legível no documento.
- **A12** — um único carimbo de emissão, num formato só.
- **A11** — o fecho sai do fim do sumário e vai para o fim do documento.

Testes novos por extração de texto, seguindo o padrão que o PR #45 já
estabeleceu em `RelatorioServiceTest`.

### R2 — Acentuação completa · risco baixo
28 literais + tradutor local de `ResultadoParecer` (e, se confirmado, de
`TipoAnexo`, que tem 5 consumidores — na dúvida, **tradutor local**). **Não
tocar nos enums.** Quebra 4 asserções conhecidas de `RelatorioServiceTest`
(§4.9), todas de atualização trivial. Decisão 5 primeiro, se o stamper entrar.

### R3b — Paleta institucional completa · risco baixo · **Decisões 1 e 6**
As quatro constantes restantes. Se a Decisão 6 for aprovada, esta fase vira a
extração de `PaletaPdf` e **precisa de PR próprio**, com os três PDFs gerados e
olhados (Decisão 6 hoje é recomendada como Opção A, ver §7.6).

### R4 — Tipografia e cor comedida · risco médio · **maior mudança visual**
Régua no lugar da faixa, cabeçalho de tabela claro, corpo a 10pt, escala
declarada, coluna "Situação" por extenso em vez de `[X]`/`[>]`/`[ ]`. **O
protótipo da §6 é o alvo visual.** Executar **sozinha**, em PR próprio.

**Riscos concretos, um deles já observado:** o protótipo quebrou a data em duas
linhas na tabela de anexos com proporções `{4.5, 3.5, 1.2, 1.3}`. Reajustar
proporções **olhando o PDF**, não no papel. Vigiar também "Data de solicitação
da urgência renal" na tabela `{3, 7}`.

### R5 — Página A4 e divisórias · risco médio · **Decisões 8 e 9**
- **B1** — sumário volta a ser A4 (`pdfinfo` deve dizer `(A4)`).
- **P8(a)** — folha divisória por anexo.
- **P8(c)** — marcadores.
- **P9** — `setKeepTogether` nas seções.
- **B5** — anexo em imagem entra como página do documento, em vez de virar nota
  de rodapé. *(Discutível se cabe aqui ou no R1b: é completude de conteúdo, não
  layout. Se o dono do produto considerar grave — e há bom argumento para isso —,
  **subir para o R1b**.)*

Testar os quatro caminhos de anexo que `RelatorioServiceTest` já cobre e que
**não podem quebrar**: sem anexo, um anexo, anexo não-PDF, anexo ausente do
disco.

### R6 — Capa e rótulo parcial · risco médio · **Decisões 3 e 7**
P7 + P10. Depende da escolha entre enxugar e eliminar a capa.

### Fora de fase — Decisão 4 (o retrato guardado)
Toca `DecisaoFinalService` e/ou `ExportacaoProcessoService`: é **fluxo**, não
documento. PR próprio, teste de integração real (não `@MockitoBean` do serviço),
conforme a convenção do projeto para escrita irreversível.

### Ordem sugerida
**Bloco 0 (higiene):** R0 — isolada, aprovável sem nenhuma decisão pendente.
**Bloco 1 (verdade):** R1b → R2.
**Bloco 2 (identidade):** R3b → R4, com revisão visual entre as duas.
**Bloco 3 (estrutura física):** R5.
**Bloco 4 (forma):** R6.
**À parte:** Decisão 4.

---

## 9. O que este relatório **não** recomenda

- **Trocar de biblioteca.** Mantida integralmente a P12 do relatório original:
  iText 7/8 é AGPL ou comercial (descartado para órgão público sem contrato);
  openhtmltopdf/Flying Saucer exigiria reescrever os quatro serviços de PDF,
  **não resolve o merge de anexos**, traria dependência nova numa VM de 1 GB
  compartilhada com outras três aplicações, e obrigaria a refazer o carimbo
  página a página — que é a parte mais delicada e **mais bem-feita** do código
  atual, incluindo o tratamento de páginas rotacionadas. **Tudo que este
  relatório propõe cabe no OpenPDF 1.3.34, sem dependência nova** (§5.6).
- **Acentuar `ResultadoParecer.getDescricao()`.** Proibição documentada;
  alimenta cinco serviços e documentos oficiais. Tradutor local, sempre.
- **Renomear o botão "Relatório Final (PDF)".** O E2E o localiza por texto
  exato.
- **Bloquear a rota do relatório fora de status final** (§7.3).
- **PDF/UA / documento marcado** (§7.10, nível 3).
- **Incorporar a Inter** (§7.2).
- **Mexer no Ofício.** Ele é a régua — foi elevado deliberadamente a esse papel
  em 2026-08-04, e este relatório o usa como referência, não como alvo.
- **Mexer no carimbo do avaliador ou nas iniciais.** O cabeçalho identificar o
  paciente só pelas iniciais enquanto o corpo traz o nome completo é **correto e
  deliberado** (imparcialidade + `Title` do PDF). Registrado aqui, de novo, para
  que ninguém "corrija" isso por engano.

---

## Anexo A — como reproduzir este diagnóstico

```bash
cd /workspaces/urgencia
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# B1 - o documento nao e A4 (o Oficio e)
pdfinfo relatorio.pdf | grep "Page size"     # 595 x 897 pts, sem rotulo (A4)

# B6 - dois azuis institucionais convivendo nos PDFs do sistema
grep -n "new Color(" src/main/java/br/gov/saude/sgpur/service/*.java

# B8 - dois nomes para o mesmo sistema
grep -n "Documento gerado" src/main/java/br/gov/saude/sgpur/service/*.java

# 4.9 - inventario de literais sem acento (28 ocorrencias)
grep -on '"[^"]*\(cao\|coes\|Numero\|Medico\|medico\|Decisao\|Situacao\|Relacao\|Observ\|Favorav\|Nao\|Pagina\|URGENCIA\|SAUDE\|oficio\|excecao\|Gestao\|Instituicao\)[^"]*"' \
  src/main/java/br/gov/saude/sgpur/service/RelatorioService.java \
  src/main/java/br/gov/saude/sgpur/service/PdfRelatorioBuilder.java \
  src/main/java/br/gov/saude/sgpur/service/PdfCabecalhoStamper.java

# 7.5 - raio de impacto real do stamper: so 2 literais
grep -rn "PdfCabecalhoStamper.estampar" src/main/java   # 3 documentos

# B9 - metadados de acessibilidade
pdffonts relatorio.pdf                        # emb=no, uni=no
pdfinfo relatorio.pdf                         # Tagged: no
python3 -c "d=open('relatorio.pdf','rb').read(); \
  print({k: d.count(k.encode()) for k in ['/Lang','/StructTreeRoot','/Outlines']})"

# B7 - cobertura de tinta (requer pillow)
pdftoppm -png -r 100 relatorio.pdf pag
```

**Para gerar os PDFs de exemplo:** copiar `RelatorioServiceTest` para um
`ZzGerarPdfV2Test`, montar os quatro processos da §3, gravar com
`Files.write(...)` e rodar `mvn -o test -Dtest=ZzGerarPdfV2Test`. **Apagar o
arquivo depois** — é instrumento de inspeção, não teste. O protótipo da §6 foi
escrito da mesma forma, **inteiramente em escopo de teste**, sem tocar em
produção.

---

## Anexo B — contrastes recalculados (WCAG 2.x, verificação independente)

Luminância relativa pela fórmula da WCAG 2.x, recomputada nesta rodada sobre os
hex reais. Confirma o Anexo B do relatório original (divergência máxima de 0,01,
arredondamento).

| Texto | Sobre | Contraste | AA (4,5:1) |
|---|---|---|---|
| branco | `#1A4D8F` (`AZUL` **atual**, pós-PR #45) | **8,39:1** | ✅ folgado |
| branco | `#0D6EFD` (`AZUL` antigo, Bootstrap) | 4,50:1 | no limite exato |
| `#198754` (`VERDE_ESCURO` **atual**) | branco | **4,53:1** | 🟡 por 0,03 |
| `#1F6B36` (`--rs-green-dark`) | branco | **6,53:1** | ✅ |
| `#DC3545` (`VERMELHO` **atual**) | branco | **4,53:1** | 🟡 por 0,03 |
| `#8B1A1A` (`--rs-red-dark`) | branco | **9,29:1** | ✅ |
| `#6C757D` (`CINZA` **atual**, usado a 8pt) | branco | **4,69:1** | 🟡 por 0,19 |
| `#475569` (`--rs-gray-600`) | branco | **7,58:1** | ✅ |
| `#0F3163` (`--rs-blue-dark`) | branco | **12,81:1** | ✅ |
| `#1A4D8F` (título de seção proposto) | branco | **8,39:1** | ✅ |
| preto | `#F1F5F9` (cabeçalho de tabela proposto) | **19,17:1** | ✅ |

**Limiar aplicável, confirmado no W3C** (§5.5): 4,5:1 para texto normal; 3:1
apenas para *"at least 18 point or 14 point bold"*. O `linhaDestaque` do
resultado é **13pt bold** — está **abaixo** do limiar de texto grande e por isso
precisa dos 4,5:1, que hoje cumpre por 0,03.

**Observação que vale mais que a tabela:** o mínimo da WCAG foi calibrado para
**tela**. Este documento é feito para ser impresso e fotocopiado, e a UKAAF
alerta que em impressão ruim *"the darker ink may start to fill in the white"*
sobre texto vazado. Valores no limite (4,5–4,7:1) em corpo de 8–9pt degradam
rápido nesse cenário — o que sustenta, de forma **independente** do argumento de
identidade visual, tanto a Decisão 1 quanto a subida do corpo (§7.1, R4).

---

## Anexo C — índice das fontes externas

| # | Fonte | URL | Status |
|---|---|---|---|
| 1 | Manual de Redação da Presidência da República, 3ª ed. | `https://juspodium.net/manual_redacao_oficial.pdf` | **[lida] [mirror]** — canônica do Planalto falhou (ECONNRESET ×2) |
| 2 | Guia de Padronização de Documentos — ANP (corrobora o item 1) | `https://www.gov.br/anp/pt-br/centrais-de-conteudo/publicacoes/cartilhas-e-guias/guiapadronizacaooficialanp.pdf` | **[lida]** — domínio `gov.br` |
| 3 | Redação Oficial (UFSC/CAPES) — regra de cor na 2ª ed. (2002) | `https://educapes.capes.gov.br/bitstream/capes/401192/1/RedacaoOficial-3ed-web-atualizado.pdf` | **[lida]** |
| 4 | Manual de Identidade Visual — Governo do RS (2023) | `https://cultura.rs.gov.br/upload/arquivos/202305/08143924-miv-o-futuro-nos-une.pdf` | **[lida]** |
| 5 | Manual de Identidade Visual — GESP/SP v1.6 (2023) | `https://sigam.ambiente.sp.gov.br/sigam3/repositorio/559/documentos/GESP_MANUAL_DE%20IDENTIDADE_VISUAL_2023.pdf` | **[lida]** |
| 6 | Manual do Governo Federal na Internet (SECOM) | `https://nationbrandingnow.com/assets/SA/BR/lula/internet.pdf` | **[lida] [mirror]** |
| 7 | Butterick — Summary of key rules | `https://practicaltypography.com/summary-of-key-rules.html` | **[lida]** |
| 8 | Butterick — Rules & borders | `https://practicaltypography.com/rules-and-borders.html` | **[lida]** |
| 9 | USWDS — Typography | `https://designsystem.digital.gov/components/typography/` | **[lida]** |
| 10 | USWDS — Line height tokens | `https://designsystem.digital.gov/design-tokens/typesetting/line-height/` | **[lida]** |
| 11 | Section508.gov — Fonts and Typography | `https://www.section508.gov/develop/fonts-typography/` | **[lida]** |
| 12 | UKAAF G003 — Clear print and large print | `https://www.ukaaf.org/wp-content/uploads/2024/12/G003-UKAAF-Creating-clear-print-and-large-print-documents-v4.pdf` | **[lida]** |
| 13 | GOV.UK — Publishing accessible documents | `https://www.gov.uk/guidance/publishing-accessible-documents` | **[lida]** |
| 14 | Glasgow City HSCP — Accessible print publications | `https://glasgowcity.hscp.scot/sites/default/files/publications/How%20to%20Create%20Accessible%20Print%20Publications.pdf` | **[lida]** |
| 15 | PDF Reference, Apêndice D — Character Sets and Encodings | `https://eclecticgeek.com/dompdf/debug_tests/charsetsupport.htm` | **[lida] [mirror]** — ISO 32000 é paga |
| 16 | Windows-1252 (repertório de línguas) | `https://en.wikipedia.org/wiki/Windows-1252` | **[lida]** — fonte secundária |
| 17 | iText KB cap. 6 — Using fonts | `https://kb.itextpdf.com/itext/chapter-6-using-fonts-in-pdfhtml` | **[lida]** |
| 18 | iText KB cap. 1 — Introducing fonts | `https://kb.itextpdf.com/itext/chapter-1-introducing-fonts` | **[lida]** |
| 19 | Apache FOP — PDF/A (fontes obrigatoriamente incorporadas) | `https://xmlgraphics.apache.org/fop/2.0/pdfa.html` | **[lida]** |
| 20 | PDFlib — Requisitos de PDF/UA-1 (ISO 14289) | `https://www.pdflib.com/pdf-knowledge-base/pdfua/requirements/` | **[lida]** |
| 21 | W3C — WCAG 2.2, 1.4.3 Contrast (Minimum) | `https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum` | **[lida]** |
| 22 | W3C — Técnica PDF18 (Title + DisplayDocTitle) | `https://www.w3.org/WAI/WCAG22/Techniques/pdf/PDF18` | **[lida]** |
| 23 | W3C — Técnica PDF16 (/Lang) | `https://www.w3.org/WAI/WCAG22/Techniques/pdf/PDF16` | **[lida]** |

**Não foi possível confirmar** (e por isso **não** é citado como evidência
neste relatório): as normas ISO 32000 / 19005 / 14289 em texto primário (pagas,
sem URL pública); `pdfa.org` (HTTP 403); a URL canônica do Manual de Redação no
Planalto (ECONNRESET); os checkpoints do Matterhorn Protocol; o nível AAA da
WCAG (7:1); e a "regra dos 3 níveis de hierarquia tipográfica", que só aparece
em blogs.

---

## 10. Fechamento

O diagnóstico do relatório original continua correto: o Relatório Final é
**tecnicamente bem-feito e documentalmente incompleto**. O PR #45 corrigiu a
parte mais grave do que ele **dizia** — não anuncia mais um status de tramitação
como decisão, conhece a exceção do coordenador, registra o parecer impedido,
carrega a prova do voto e o número do ofício — e deu o primeiro passo na
identidade visual.

O que esta segunda rodada acrescenta é que **as correções pararam no meio, e uma
delas criou dois problemas novos**: a seção da decisão ganhou duas linhas que
são garantidamente vazias em metade dos processos (B4), e o sistema passou a
ter dois azuis institucionais diferentes nos seus PDFs oficiais (B6). Nenhum dos
dois é culpa do PR #45 — os dois são consequência previsível de fatiar o
trabalho, e ambos se resolvem terminando o que foi começado.

Acrescenta também dois defeitos estruturais que nenhuma das duas rodadas de
inspeção visual tinha visto, porque só aparecem com instrumento: **o documento
não é A4** (B1), e portanto encolhe 6,5% ao ser impresso — levando um corpo já
pequeno de 9pt para ~8,4pt efetivos, abaixo de todo mínimo publicado; e **as
tabelas quebram entre páginas sem repetir o cabeçalho** (B2), entregando ao
auditor páginas de dados sem rótulo de coluna. Os dois são baratos de corrigir.

Sobre a queixa direta do usuário — a acentuação —, a resposta é a mais simples
de todo o relatório: são **28 literais**, o custo é **zero byte**, está provado
por experimento e respaldado pela especificação. Não há nada a decidir, só a
fazer.

E o argumento que dispensa toda a bibliografia: **o Ofício de Indeferimento,
gerado pelo mesmo sistema, para o mesmo processo, indo para o mesmo dossiê, não
usa uma única área de cor, usa 11pt e é integralmente acentuado.** Ele foi
elevado a esse padrão por decisão explícita, em 2026-08-04. O Relatório Final
merece a mesma decisão.

**Próximo passo sugerido:** aprovar o **R0** (higiene: cabeçalho de tabela
repetido, `Nº`, nome do sistema, `/Lang`) — que não depende de nenhuma das dez
decisões e é o melhor retorno por linha alterada do plano inteiro —, e responder
as decisões **1** (completar a paleta), **6** (unificar, agora que há dois azuis)
e **10** (existe exigência de PDF/A?), que são as que travam as fases seguintes.

# Relatório: otimização do CLAUDE.md

**Status: DIAGNÓSTICO — plano proposto, execução NÃO iniciada.** Este
relatório mede o problema e propõe o corte; a reescrita em si é uma tarefa
grande e arriscada (ver seção 6) que pede aprovação explícita antes de
começar.

## 1. O problema, medido

`CLAUDE.md` hoje: **6.794 linhas, 56.875 palavras, 435.683 caracteres** —
isso é ~124 mil tokens (regra prática de ~3,5 caracteres/token em texto em
português). Esse arquivo é carregado **inteiro, em toda sessão**, antes de
qualquer tarefa começar — é o maior custo fixo e recorrente do projeto:

- **Dinheiro**: ~124k tokens de prompt em toda sessão nova, sempre, mesmo
  para um pedido de uma linha.
- **Atenção do modelo**: quanto maior o "palheiro", mais fácil uma regra
  real ficar soterrada — e isso já aconteceu de fato nesta mesma sessão
  (ver seção 3, "a lição repetida que não colou").
- **Velocidade**: cada sessão gasta tempo/tokens só processando o arquivo
  antes de poder agir.

## 2. Causa raiz

O arquivo tem dois blocos com naturezas completamente diferentes, e eles
estão misturados:

| Bloco | Linhas | Palavras | % do arquivo |
|---|---|---|---|
| Referência viva (Stack, Regras de negócio, Convenções, Deploy, Design system — linhas 1–846) | 846 | 7.285 | **12,8%** |
| Log cronológico de sessões (linhas 847–6794, **90 seções datadas**, uma por sessão) | 5.948 | 49.590 | **87,2%** |

Ou seja: **quase 9 em cada 10 palavras do arquivo são diário de bordo**, não
regra atual. Cada sessão, em vez de **editar** a seção de referência
relevante e descartar a narrativa de investigação, **anexou** uma seção
nova no fim do arquivo (`## <título> (2026-MM-DD)`) contando a história
completa do que foi investigado/corrigido/testado. O arquivo nunca encolhe,
só cresce — clássico padrão "log append-only" aplicado a um documento que
deveria ser um manual de estado atual.

Isso não é hipotético: o próprio projeto **já resolveu exatamente esse
problema para outros documentos**, criando `docs/historico/` — "arquivo
morto: notas de sessão e relatórios de vistoria antigos, movidos da raiz...
não é fonte da verdade para nada, só arqueologia" (CLAUDE.md, seção
"Organização do repositório"). O `CLAUDE.md` nunca recebeu o mesmo
tratamento que ele próprio prescreve para outros arquivos.

## 3. Padrões de desperdício encontrados (com números)

- **Boilerplate repetido dezenas de vezes.** "suíte completa"/"suite
  completa" aparece **59×**; "0 falhas" **47×**; "JDK 21" **47×**;
  "Validação:" **24×**. Cada seção reafirma o mesmo ritual de validação com
  frase quase idêntica.
- **36 contagens de teste diferentes** aparecem no arquivo (de "418 testes"
  até "1083 testes"), cada uma incrustada em prosa de uma sessão específica
  — é literalmente o changelog do tamanho da suíte, disperso em 36 lugares
  em vez de existir em UM lugar (só a contagem atual importa pra alguém
  lendo hoje).
- **Seções que existem só para corrigir um parágrafo de outra seção do
  mesmo arquivo.** Exemplo real, linha 1205 ("Vistoria de 2026-07-31 (texto
  desatualizado sobre enums removidos)"): a seção inteira é "encontrei um
  trecho deste próprio CLAUDE.md desatualizado e corrigi" — hoje isso é só
  arqueologia sobre o processo de escrever o arquivo, não sobre o SAUR.
- **Colisão de nomes entre sessões distantes**, sintoma direto de escala:
  duas seções diferentes usam "Achado B"/"Achado 4" pra coisas diferentes,
  e cada uma precisa de um parágrafo "**Não confundir com...**" (linhas
  1977, 3088) só pra se desambiguar da outra.
- **A lição repetida que não colou — achado na PRÓPRIA sessão de hoje.** O
  arquivo já documenta, **duas vezes**, o mesmo pitfall ("editar arquivo de
  teste enquanto um build/`mvn test` está rodando corrompe
  `target/test-classes`" — seções "Sessão de consolidação de 2026-08-08" e
  a nota de metodologia da Fase 5, 2026-08-03/04). Mesmo assim, **eu mesmo
  caí nisso de novo nesta sessão** (rodei `mvn test` em background e editei
  testes em paralelo, corrompendo o classpath, precisei re-rodar do zero).
  Isso é evidência direta — não teórica — de que densidade alta prejudica
  a própria função do arquivo: a regra estava lá, mas afogada.
- **Decisões de produto genuinamente permanentes escondidas dentro de
  narrativa de bug.** Ex.: a regra "cada opção lado a lado usa sua própria
  cor semântica, nunca uma genérica" (linha 6197, "REGRA FIXA") é uma regra
  de UI válida pra sempre — mas está no meio do log cronológico, não em
  "UI / Frontend" (linha 523), onde alguém desenhando uma tela nova
  procuraria.

## 4. O que NÃO é desperdício (cuidado ao cortar)

Nem tudo no log é descartável — várias seções carregam **decisão de
produto** que precisa sobreviver como regra atual, só não precisa da
narrativa de investigação em volta:
- Marcadores de "decisão de produto confirmada pelo usuário" (aprovações
  explícitas, ex. "Achado 4... aprovado pelo dono do produto nesta sessão")
  — o FATO da aprovação e o comportamento resultante importam; a reunião de
  como se chegou lá, não.
- Reversões deliberadas que já aconteceram 2x (ex. "Cores dos Atalhos
  revertidas de novo", linha 4764) — o registro "isso já foi pedido de
  volta duas vezes, não reaplicar sem pedido explícito" tem valor real e
  deve sobreviver, só não precisa do histórico completo das duas idas e
  vindas.
- Pitfalls técnicos genuínos e ainda vigentes (CHECK constraint de enum
  gerada pelo Hibernate, `ddl-auto: update` não faz backfill,
  `th:inline="javascript"` obrigatório etc.) — já estão em "Convenções de
  código" e devem continuar lá, só não repetidos de novo em cada seção que
  os cita.

## 5. Plano de otimização proposto

**Espelhar o padrão que o projeto já usa em `docs/historico/`:**

1. **Extrair as ~90 seções datadas para um arquivo de arquivo morto**
   (`docs/historico/CLAUDE-log-sessoes-2026-07-a-08.md`, ou dividido por
   mês se ficar grande) — preservadas na íntegra, sem perder nada, só
   saindo do arquivo carregado toda sessão.
2. **Para cada seção extraída, extrair a REGRA ATUAL (se houver) e
   fundir na seção de referência certa** — 1 a 3 frases, não a narrativa.
   Exemplos concretos do que sobreviveria, condensado:
   - "Solicita informação" com múltiplos pedidos simultâneos → 1 frase nova
     em "Regras de negócio" (cada pedido é avaliado independente; um envio
     responde a todos os pedidos abertos).
   - Chevron/toast/CSS de chat → já não é regra de negócio, é bug já
     corrigido; não precisa de linha nenhuma na referência, só arquivo.
   - "REGRA FIXA: cor semântica por opção" → migra pra dentro de "UI /
     Frontend".
   - Mudanças de schema/coluna nullable → já cobertas pela regra geral em
     "Convenções de código" (`ddl-auto: update`), não precisam de uma
     entrada por campo.
3. **Consolidar o boilerplate de validação** numa frase-padrão só, ou
   remover — ninguém precisa saber que uma sessão de julho tinha "629
   testes"; só a contagem ATUAL (que já muda a cada sessão) importa, e essa
   já vive em `test.ps1`/na própria suíte.
4. **Manter cronologia só onde ela É a informação** (ex.: a tabela de IP de
   produção mudando, o histórico de migração Neon→Postgres local) — não
   court tudo às cegas, seção por seção com julgamento.

**Meta de tamanho:** reduzir a seção de referência de ~7.300 para talvez
10.000-15.000 palavras (absorvendo as poucas dezenas de regras reais que
estão hoje enterradas no log), e tirar as ~50.000 palavras de narrativa do
arquivo carregado toda sessão. **Resultado esperado: CLAUDE.md cai de
~124k para ~25-35k tokens — uma redução de ~70-75%**, sem perder nenhuma
informação real (só reorganizando + descartando repetição/narrativa).

## 6. Por que não fiz a cirurgia agora, e o que eu preciso pra fazer

Esta é uma reescrita de conteúdo, não uma correção de bug — o risco real é
**perder, por engano, uma exceção/decisão de produto sutil** que hoje só
existe dentro de uma seção histórica (o arquivo tem vários "não fazer X sem
aval explícito", "decisão revertida 2×, não reaplicar" — ver seção 4). Uma
passada rápida e automática arrisca exatamente o tipo de coisa que este
arquivo existe pra prevenir.

**Proposta de próximo passo, se aprovado:** dividir em lotes por bloco
temático (ex.: um lote por mês de sessões), cada lote como sua própria
tarefa — extrair pro histórico, condensar a regra permanente na referência,
validar que nada de "regra ainda vigente" ficou de fora (grep por
"REGRA", "decisão de produto", "NÃO implementar sem aval", "reverte" antes
de arquivar cada seção) — e comparar o CLAUDE.md final linha a linha com o
original antes de apagar qualquer coisa. Cada lote vira 1 commit, revisável
separado. Consigo começar assim que confirmado.

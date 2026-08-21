# Índice de `docs/` — para busca rápida (não carregar tudo)

Catálogo de todo `docs/*.md` + o arquivo histórico do CLAUDE.md, com um
resumo de 1 linha cada. **Propósito:** desde que o CLAUDE.md foi enxugado
(2026-08-21, ver `RELATORIO-OTIMIZACAO-CLAUDE-MD-2026-08-21.md`), a
narrativa detalhada de cada investigação/decisão não fica mais carregada
por padrão em toda sessão — vive aqui. Quando precisar de contexto que não
está no CLAUDE.md atual (por que uma decisão foi tomada, o raciocínio
completo de um bug, um plano que não virou regra permanente), **busque
aqui em vez de adivinhar ou reimplementar do zero**:

```
grep -rn "<termo>" docs/*.md docs/historico/*.md
```

**Quase tudo listado abaixo já foi implementado** — o CLAUDE.md é sempre a
fonte da verdade sobre o que é comportamento atual; estes arquivos são o
"porquê" e o histórico, não a especificação vigente.

## Arquivo histórico (o log de sessões que saiu do CLAUDE.md)
- `historico/CLAUDE-log-sessoes-2026-07-a-08.md` — ~90 sessões datadas
  (2026-07-27 a 2026-08-21), narrativa completa de cada bug/feature/decisão
  já resolvida. Ponto de partida para "como/quando isso foi decidido".

## Relatórios — status IMPLEMENTADO/CONCLUÍDO (arqueologia do "porquê")
- `RELATORIO-CAMPOS-PACIENTE-SOLICITANTE-2026-08.md` — data de nascimento/
  CPF/sexo/nome da mãe do paciente, adicionados ao Portal do Solicitante.
- `RELATORIO-EMAIL-ADICIONAL-SOLICITANTE-2026-08.md` — e-mail adicional
  (CC) opcional por processo.
- `RELATORIO-CONFIRMACAO-CONFLITO-EQUIPE-2026-08.md` — confirmação ao
  escolher médico da mesma equipe do solicitante.
- `RELATORIO-PADRAO-LARGURA-PORTAIS-2026-08.md` — padronização de largura
  de container entre Operador/Avaliador/Solicitante.
- `RELATORIO-RESPONSIVIDADE-CORES-SOLICITANTE-2026-08.md` — vistoria de
  estouro horizontal + cores de badge na lista do Portal do Solicitante.
- `RELATORIO-STATUS-PROCESSO-12-2026-2026-08-11.md` — inconsistências de
  status exibido, motivadas por um caso real de produção (pausa antes da
  maioria se formar).
- `RELATORIO-VISTORIA-BRECHAS-DECISAO-2026-08-10.md` — 6 fases sobre
  visibilidade de decisões excepcionais (voto do coordenador, reaberturas,
  histórico de pareceres sobrepostos pela pausa, avaliador dispensado).
- `RELATORIO-VISTORIA-CHAT-2026-08-10.md` — 6 fases sobre os dois sistemas
  de chat (badges, marcar como lida fora de hora, N+1, índices).
- `RELATORIO-BUG-PAUSA-BLOQUEIA-OUTROS-AVALIADORES-2026-08.md` — pausa de
  um avaliador travava o voto dos outros dois (corrigido).
- `RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md` — investigação
  que confirmou "decidir na hora ao retomar, sem esperar o 3º voto" como
  comportamento correto (não bug).
- `IDEIA-PADRONIZACAO-CORES-SOLICITA-INFO-AGUARDANDO-2026-08.md` —
  "Solicita informação" = amarelo, "Aguardando" = azul.
- `RELATORIO-OTIMIZACAO-CLAUDE-MD-2026-08-21.md` — este próprio corte do
  CLAUDE.md (diagnóstico + plano), executado no mesmo dia.

## Relatórios de UI — a maior parte já executada (ver CLAUDE.md pra saber o que sobrou)
- `RELATORIO-REDESIGN-VISUAL-SOLICITANTE-2026-08.md` — sistema de design
  V1-V6 do Portal do Solicitante (depois estendido ao Avaliador).
- `RELATORIO-UI-SOLICITANTE-AVALIADOR-2026-08.md` — plano faseado original
  dos dois Portais externos (Fases 1-10 implementadas; item de rascunho de
  solicitação e justificativa obrigatória vieram de decisões aprovadas à
  parte).
- `RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md` — auditoria das 19 telas do
  operador (5 fases A-E, todas executadas).
- `RELATORIO-UI-CLAREZA-OPERADOR-2026-08.md` — poluição visual de
  `processos/detalhe.html` (quase 100% implementado — ver seção própria no
  CLAUDE.md pro que ficou de fora e por quê).
- `RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md` — busca nas listas, atalho
  de teclado, `beforeunload`, poll pausado em background, toast unificado.

## Relatórios do PDF (Relatório Final)
- `RELATORIO-REFORMULACAO-RELATORIO-FINAL-PDF-2026-08.md` — diagnóstico V1.
- `RELATORIO-REFORMULACAO-RELATORIO-FINAL-PDF-V2-2026-08.md` — diagnóstico
  V2 (pesquisa externa) — plano R0-R6 implementado; **R6 (capa removida)
  foi revertido depois**, ver seção "Capa do Relatório Final reintroduzida"
  no CLAUDE.md.

## Feature nunca implementada / arquitetura só proposta
- `RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md` — proposta original do
  chat avaliador↔operador; **implementado** numa sessão posterior (F1-F5),
  ver CLAUDE.md — este arquivo é só o desenho original.
- `RELATORIO-OFICIO-COMPROVANTE-SNT-2026-08.md` — itens 1-7 implementados;
  **item 8 (ofício ao SNT automático) segue não implementado**, deliberado.

## Documentos de referência/planejamento gerais (não são "relatório de sessão")
- `PLANO-FLUXO.md` — desenho original do fluxo de 5 etapas do processo.
- `PLANO-SOLICITANTE.md` — desenho original do módulo Portal do Solicitante.
- `AJUSTES-UI.md` — histórico de ajustes de UI anteriores à leva de
  relatórios faseados de 2026-08.
- `ESTUDO-UI-COMPORTAMENTAL.md` — princípios de leitura visual aplicados.
- `MEMBROS-AVALIADORES.md` — lista/cadastro de referência dos avaliadores.
- `PROTOCOLO-TESTE-PRODUCAO.md` — roteiro de teste manual em produção.

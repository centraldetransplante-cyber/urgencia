# Índice de `docs/` — para busca rápida (não carregar tudo)

Catálogo de todo `docs/*.md` + `docs/historico/*` com um resumo de 1 linha
cada. **Propósito:** desde que o CLAUDE.md foi enxugado (2026-08-21, ver
`RELATORIO-OTIMIZACAO-CLAUDE-MD-2026-08-21.md`; reestruturado de novo em
2026-08-28), a narrativa detalhada de cada investigação/decisão não fica
mais carregada por padrão em toda sessão — vive aqui. Quando precisar de
contexto que não está no CLAUDE.md atual (por que uma decisão foi tomada, o
raciocínio de um bug, um plano que não virou regra permanente), **busque
aqui em vez de adivinhar ou reimplementar do zero**:

```
grep -rn "<termo>" docs/*.md docs/historico/*.md
```

**Quase tudo listado abaixo já foi implementado** — o CLAUDE.md é sempre a
fonte da verdade sobre o comportamento atual; estes arquivos são o "porquê"
e o histórico. Muitos são citados por nome em comentários/javadoc do `src/`
(`ver docs/RELATORIO-X.md`) — por isso continuam em `docs/`, não em
`docs/historico/`: são a camada "por que este código é assim".

Legenda de status: 🔴 aberto/pendente · ✅ implementado · 📐 referência viva
· 🗄️ movido para `docs/historico/` em 2026-08-28.

---

## 🔴 Aberto — ação pendente

- 🔴 `IDEIA-TERMO-IMPARCIALIDADE-AVALIADOR-2026-08.md` — decisão de produto
  COMPLETA (2026-08-23), **implementação NÃO iniciada**. Remover a
  anonimização do avaliador (nome + CPF + data de nascimento + nome da mãe
  passam a aparecer) e exigir **termo de imparcialidade obrigatório por
  processo**; avaliador que recusa fica impedido e o operador substitui.
  Falta o desenho técnico do fluxo de substituição de avaliador impedido
  (toca `ProcessoValidator`/maioria simples).
- 🔴 `RELATORIO-VISTORIA-CODIGO-2026-08-22.md` — vistoria de código (outra
  IA). Achados **ainda não corrigidos**: P0 (credencial de banco em log de
  debug do `test.ps1`), P1 (`ddl-auto: update` em prod, `SchemaMigration`
  destrutiva no boot), P2 (rate-limit em memória sem TTL, upload sem checar
  conteúdo real). Os P1 de reset de senha e ordem e-mail/commit já foram
  resolvidos depois (ver CLAUDE.md "Esqueci minha senha" e "Segurança e
  sessão").

## 📐 Referência viva (design/runbook/catálogo)

- 📐 `CATALOGO-BUGS-CONHECIDOS.md` — catálogo vivo de todo bug já encontrado
  e corrigido, por categoria (persistência/Hibernate, Thymeleaf,
  concorrência, decisão, imparcialidade, e-mail, UI, deploy, build).
  Consultar **antes** de mexer em área de risco; atualizar em vez de
  duplicar quando uma recaída aparecer.
- 📐 `PROTOCOLO-TESTE-PRODUCAO.md` — roteiro de teste manual em produção.
- 📐 `MEMBROS-AVALIADORES.md` — lista/cadastro de referência dos avaliadores.
- 📐 `PLANO-FLUXO.md` — desenho do fluxo de 5 etapas do processo.
- 📐 `PLANO-SOLICITANTE.md` — desenho do módulo Portal do Solicitante.
- 📐 `ESTUDO-UI-COMPORTAMENTAL.md` — princípios de leitura visual aplicados.
- 📐 `AJUSTES-UI.md` — histórico de ajustes de UI anteriores à leva de
  relatórios faseados de 2026-08 (citado 2× no CLAUDE.md).

## ✅ Paciente preemptivo (2026-08-27) — implementado

- ✅ `PLANO-PACIENTE-PREEMPTIVO-2026-08-27.md` — desenho completo e decisões
  fechadas do segundo tipo de processo (inserção em lista de espera renal).
- ✅ `RELATORIO-AUDITORIA-FINAL-PACIENTE-PREEMPTIVO-2026-08-27.md` — auditoria
  final da feature, achados A1–A12 (todos aplicados).
- ✅ `RELATORIO-BUG-DUPLICACAO-E-COBERTURA-BADGE-PREEMPTIVO-2026-08-27.md` —
  duplo-submit em `solicitante/nova.html` + cobertura do badge de tipo.

## ✅ Implementado — arqueologia do "porquê" (citados no `src/`)

- ✅ `RELATORIO-CAMPOS-PACIENTE-SOLICITANTE-2026-08.md` — data de
  nascimento/CPF/sexo/nome da mãe do paciente no Portal do Solicitante.
- ✅ `RELATORIO-EMAIL-ADICIONAL-SOLICITANTE-2026-08.md` — e-mail adicional
  (CC) opcional por processo.
- ✅ `RELATORIO-CONFIRMACAO-CONFLITO-EQUIPE-2026-08.md` — confirmação ao
  escolher médico da mesma equipe do solicitante.
- ✅ `RELATORIO-PADRAO-LARGURA-PORTAIS-2026-08.md` — padronização de largura
  de container Operador/Avaliador/Solicitante.
- ✅ `RELATORIO-RESPONSIVIDADE-CORES-SOLICITANTE-2026-08.md` — estouro
  horizontal + cores de badge na lista do Portal do Solicitante.
- ✅ `RELATORIO-STATUS-PROCESSO-12-2026-2026-08-11.md` — inconsistências de
  status exibido (caso real de produção: pausa antes da maioria se formar).
- ✅ `RELATORIO-VISTORIA-BRECHAS-DECISAO-2026-08-10.md` — visibilidade de
  decisões excepcionais (voto do coordenador, reaberturas, histórico de
  pareceres sobrepostos pela pausa, avaliador dispensado).
- ✅ `RELATORIO-VISTORIA-CHAT-2026-08-10.md` — os dois sistemas de chat
  (badges, marcar como lida fora de hora, N+1, índices).
- ✅ `RELATORIO-BUG-PAUSA-BLOQUEIA-OUTROS-AVALIADORES-2026-08.md` — pausa de
  um avaliador travava o voto dos outros dois.
- ✅ `RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md` — confirmou
  "decidir na hora ao retomar, sem esperar o 3º voto" como correto (não bug).
- ✅ `IDEIA-PADRONIZACAO-CORES-SOLICITA-INFO-AGUARDANDO-2026-08.md` —
  "Solicita informação" = amarelo, "Aguardando" = azul.
- ✅ `RELATORIO-OTIMIZACAO-CLAUDE-MD-2026-08-21.md` — o corte do CLAUDE.md de
  2026-08-21 (diagnóstico + plano).
- ✅ `RELATORIO-REDESIGN-VISUAL-SOLICITANTE-2026-08.md` — sistema de design
  V1–V6 do Portal do Solicitante (depois estendido ao Avaliador).
- ✅ `RELATORIO-UI-SOLICITANTE-AVALIADOR-2026-08.md` — plano faseado original
  dos dois Portais externos (citado no CLAUDE.md).
- ✅ `RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md` — auditoria das 19 telas do
  operador (5 fases A–E).
- ✅ `RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md` — busca nas listas, atalho
  de teclado, `beforeunload`, poll pausado em background, toast unificado.
- ✅ `RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md` — desenho original do
  chat avaliador↔operador (implementado depois, F1–F5).
- ✅ `RELATORIO-REFORMULACAO-RELATORIO-FINAL-PDF-2026-08.md` — diagnóstico V1
  do PDF Relatório Final.
- ✅ `RELATORIO-REFORMULACAO-RELATORIO-FINAL-PDF-V2-2026-08.md` — diagnóstico
  V2 (R0–R6 implementado; **R6/capa removida foi revertido depois** — ver
  "Capa do Relatório Final reintroduzida" no CLAUDE.md).

## 🗄️ Movidos para `docs/historico/` em 2026-08-28

Superseded / sem achado novo confirmado / sem citação no `src/` ou CLAUDE.md.
O `grep -rn "<termo>" docs/*.md docs/historico/*.md` continua achando.

- 🗄️ `RELATORIO-UI-CLAREZA-OPERADOR-2026-08.md` — poluição visual de
  `processos/detalhe.html` (quase 100% implementado; o que ficou de fora
  está no CLAUDE.md).
- 🗄️ `RELATORIO-OFICIO-COMPROVANTE-SNT-2026-08.md` — itens 1–7
  implementados; item 8 (ofício ao SNT automático) segue não implementado,
  deliberado (registrado no CLAUDE.md).
- 🗄️ `RELATORIO-AUDITORIA-SENIOR-PRODUCAO-2026-08-25.md` — auditoria sênior
  em 3 rodadas; 1 correção de código (javadoc de `EmailDominioValidator`),
  nenhuma vulnerabilidade nova.
- 🗄️ `RELATORIO-LIMPEZA-CODIGO-DOCS-2026-08-25.md` — limpeza de código morto
  e docs.
- 🗄️ Lote de vistoria de IA externa de 2026-08-24 — **nenhum achado novo
  confirmado**, o principal (sessão de usuário inativado) já estava
  corrigido no mesmo dia (`revogarSessoesAtivas`, ver CLAUDE.md):
  `RELATORIO-ANALISE-TECNICA-SAUR-2026-08-24.md`,
  `RELATORIO-BRECHAS-E-RISCOS-NAO-CATALOGADOS-SAUR-2026-08-24.md`,
  `RELATORIO-DIAGNOSTICO-BUGS-SAUR-2026-08-24.md` (dup de
  `CATALOGO-BUGS-CONHECIDOS.md`),
  `RELATORIO-ANALISE-ESTATICA-E-CODIGO-MORTO-2026-08-24.md`,
  `RELATORIO-RESPONSIVIDADE-PROGRAMATICA-SAUR-2026-08-24.md`,
  `RELATORIO-UI-E-RESPONSIVIDADE-SAUR-2026-08-24.md`.

## Arquivo histórico (log de sessões e stubs antigos)

- `historico/CLAUDE-log-sessoes-2026-07-a-08.md` — ~90 sessões datadas
  (2026-07-27 a 2026-08-21) + a seção "Sessão de 2026-07-28" movida do
  CLAUDE.md em 2026-08-28. Ponto de partida para "como/quando isso foi
  decidido".
- `historico/README.md` — índice da pasta `historico/` (arquivo morto de
  2026-07-29): `sessao-2026-07-27-resumo.md`, `vistoria-pendente.md` (stub
  vazio), `relatorio-vistoria-limpeza-codigo.txt`,
  `nota-modulo-solicitante.txt`.

## Mockups (HTML estático, não são relatório)

- `mockups/solicitante-dashboard-proposta.html` — proposta visual do
  dashboard do Portal do Solicitante.
- `mockups/solicitante-detalhe-proposta.html` — proposta visual da tela de
  detalhe do Portal do Solicitante.

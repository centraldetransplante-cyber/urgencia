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

## Decisão de produto registrada, desenho técnico pendente (ainda não implementado)
- `IDEIA-TERMO-IMPARCIALIDADE-AVALIADOR-2026-08.md` — **decisão COMPLETA
  (2026-08-23), implementação NÃO iniciada.** O dono do produto decidiu
  remover a anonimização do avaliador (nome completo + CPF + data de
  nascimento + nome da mãe passam a ser exibidos) e exigir um **termo de
  imparcialidade obrigatório por processo** — se o avaliador não aceitar,
  fica impedido de avaliar aquele caso e o operador substitui por outro
  médico. Decisão tomada mesmo após uma alternativa de menor risco
  (automatizar a redação do nome nos documentos, sem expor nada ao
  avaliador) ter sido apresentada e recusada, e sem consulta
  jurídica/regulatória externa (também recusada). Falta só o **desenho
  técnico do fluxo de substituição de avaliador impedido** (toca
  `ProcessoValidator`/maioria simples, ainda sem caminho no sistema) antes
  de abrir PR de implementação.

## Auditoria Completa de Produção (2026-08-25)
- `RELATORIO-AUDITORIA-SENIOR-PRODUCAO-2026-08-25.md` — Auditoria sênior do
  SAUR em 3 rodadas: (1) relatório gerado por Gemini com dezenas de citações
  arquivo:linha erradas, corrigidas uma a uma; (2) varredura independente do
  agente `urgencia-renal` em ~20 arquivos críticos (decisão/voto/e-mail/
  anexo/sessão) — nenhum bug novo encontrado, sistema já bem coberto por
  vistorias anteriores; (3) segunda opinião do Gemini (modo texto, sem
  escrita de arquivo — ver achado abaixo sobre isso), verificada achado a
  achado. Resultado: **1 correção de código aplicada** (javadoc impreciso de
  `EmailDominioValidator`, achado convergente das rodadas 2 e 3), 2 achados
  do Gemini descartados por verificação (1 já é decisão de produto
  documentada, 1 é UI que já tem mitigação no código). Nenhuma vulnerabilidade
  nova, exploravel e não corrigida foi encontrada.

## Vistorias amplas de IA externa (2026-08-24, não catalogadas até 2026-08-25)
Lote de 6 relatórios gerados por uma vistoria ampla (auditoria "de fora")
sobre arquitetura, bugs, UI/responsividade e código morto — nenhum achado
novo confirmado que já não estivesse coberto por vistorias anteriores.
**Atenção:** o achado principal de `RELATORIO-BRECHAS-...` (sessão HTTP de
usuário inativado continuava ativa) já estava **corrigido no mesmo dia**
(2026-08-24, `revogarSessoesAtivas`, ver CLAUDE.md seção "Segurança e
sessão") — o relatório descreve o problema como aberto porque foi escrito
antes da correção entrar; não confiar nele para saber o estado atual, só
CLAUDE.md.
- `RELATORIO-ANALISE-TECNICA-SAUR-2026-08-24.md` — panorama de arquitetura/
  stack, sem achado acionável novo.
- `RELATORIO-BRECHAS-E-RISCOS-NAO-CATALOGADOS-SAUR-2026-08-24.md` — achado
  de sessão de usuário inativado (ver aviso acima) já corrigido no mesmo dia.
- `RELATORIO-DIAGNOSTICO-BUGS-SAUR-2026-08-24.md` — taxonomia de bugs
  históricos já catalogados em `CATALOGO-BUGS-CONHECIDOS.md`, sem achado novo.
- `RELATORIO-ANALISE-ESTATICA-E-CODIGO-MORTO-2026-08-24.md` — varredura de
  compilação/código morto: só 2 warnings cosméticos de `@Deprecated` em
  records (sem correção viável que valha a pena), nenhum código morto real
  encontrado — reconfirmado por varredura independente em 2026-08-25.
- `RELATORIO-RESPONSIVIDADE-PROGRAMATICA-SAUR-2026-08-24.md` — validação via
  Playwright (`ResponsividadeSolicitanteIT`) de estouro de layout; sem
  regressão encontrada.
- `RELATORIO-UI-E-RESPONSIVIDADE-SAUR-2026-08-24.md` — auditoria do design
  system (`app.css`, tokens `--saur-*`/`--rs-*`); sem achado acionável novo
  além do que já está documentado no CLAUDE.md.

## Vistoria de segurança pendente de ação (2026-08-22)
- `RELATORIO-VISTORIA-CODIGO-2026-08-22.md` — vistoria de código (outra IA,
  revisão pedida pelo usuário). Achados **ainda não corrigidos**: P0
  (credencial de banco em log de debug do `test.ps1`, não reproduzido nesta
  sessão), P1 (`ddl-auto: update` em prod, `SchemaMigration` destrutiva no
  boot, reset de senha público permite DoS de conta via `/usuarios/esqueci-
  senha`), P2 (rate-limit em memória sem TTL, `Thread.sleep` no login,
  e-mail de reset antes do commit, upload sem checar conteúdo real). Os
  achados P1 de reset de senha e ordem e-mail/commit foram conferidos no
  código real e são precisos. Ver PR onde entrou para o histórico de origem.

## Catálogo de bugs conhecidos (consultar antes de mexer em área de risco)
- `CATALOGO-BUGS-CONHECIDOS.md` — catálogo vivo, organizado por categoria
  (persistência/Hibernate, Thymeleaf, concorrência, fluxo de decisão,
  imparcialidade, e-mail, UI, deploy, build/teste), de todo bug já
  encontrado e já corrigido no projeto. Consultar **antes** de mexer numa
  área de risco conhecida (schema/enum, chat, decisão de processo, PDF,
  concorrência); atualizar quando uma recaída da mesma classe aparecer, em
  vez de duplicar entrada.

## Arquivo histórico (o log de sessões que saiu do CLAUDE.md)
- `historico/CLAUDE-log-sessoes-2026-07-a-08.md` — ~90 sessões datadas
  (2026-07-27 a 2026-08-21), narrativa completa de cada bug/feature/decisão
  já resolvida. Ponto de partida para "como/quando isso foi decidido".
- `historico/README.md` — índice próprio da pasta `historico/` (arquivo
  morto de 2026-07-29): `sessao-2026-07-27-resumo.md`,
  `vistoria-pendente.md` (stub truncado, sem conteúdo),
  `relatorio-vistoria-limpeza-codigo.txt`, `nota-modulo-solicitante.txt`
  (ideia original do módulo do solicitante, ditada pelo usuário).

## Mockups (HTML estático, não são relatório)
- `mockups/solicitante-dashboard-proposta.html` — proposta visual do
  dashboard do Portal do Solicitante.
- `mockups/solicitante-detalhe-proposta.html` — proposta visual da tela de
  detalhe do Portal do Solicitante.

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

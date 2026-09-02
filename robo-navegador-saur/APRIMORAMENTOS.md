# Robô navegador SAUR — relatório de aprimoramento

Estado atual (v1): BFS a partir de `/`, login por perfil, ~12 sondas por
página, `report/index.html` + `findings.json`, modo `seguro-remoto`, proxy
corporativo, fallback de navegador (Chrome/Edge do sistema).

Abaixo: o que falta, priorizado. **Marcados com ✅ vão nesta leva.**

---

## 1. Cobertura de navegação

| # | Melhoria | Por quê | Nesta leva |
|---|---|---|---|
| 1.1 | **Rotas-semente** (`rotas-extra` no config) | páginas não linkadas de `/` nunca são vistas (ex. `/usuarios/minha-senha` só aparece no dropdown) | ✅ |
| 1.2 | **Amostragem de deep-links**: extrair IDs de `/processos`, `/membros`, `/usuarios`, `/arquivo` e visitar N detalhes de cada | as telas de detalhe (as mais complexas) só entram se houver dado; hoje some em dev vazio | ✅ |
| 1.3 | **Multi-perfil comparado**: rodar ADMIN/OPERADOR/AVALIADOR/SOLICITANTE e anotar o que cada um alcança | flagra rota que vaza pra perfil errado, ou 403 onde devia abrir | ✅ (roda; comparação fica no relatório) |
| 1.4 | Crawl **paralelo** (`paralelismo=N` páginas por vez) | seria mais rápido | ❌ **não dá** — Playwright-Java não é thread-safe por instância (driver único trava/corrompe com 2 threads dirigindo páginas). Ficou sequencial. |
| 1.5 | **Orçamento de tempo** (`tempo-max-min`) + relatório parcial | não deixar uma varredura de prod rodar sem teto | ✅ |
| 1.6 | Denylist/allowlist por **regex**, `--only <regex>` | hoje é `contains` de substring | ✅ |

## 2. Sondas novas (o que ele passa a detectar)

| # | Sonda | Categoria | Nesta leva |
|---|---|---|---|
| 2.1 | **Acessibilidade** (feita à mão, sem dependência): `<img>` sem `alt`; input/select/textarea sem label/`aria-label`; botão/link sem nome acessível; `<html>` sem `lang`; `<meta viewport>` ausente; `id` duplicado; `<label for>` órfão; pulo de nível de heading (h1→h3); sem `<h1>`; sem landmark `main` | `a11y` | ✅ |
| 2.2 | **Links internos mortos**: junta todo `<a href>` interno do site e faz HEAD/GET, reporta 404/410/500 | `link-morto` | ✅ |
| 2.3 | **Toda resposta HTTP 4xx/5xx** (não só a navegação) — XHR, imagem, iframe | `http-recurso` | ✅ |
| 2.4 | **Requests lentos** (> limite configurável, default 3s) | `lento` | ✅ |
| 2.5 | **Timing da página** (`PerformanceNavigationTiming`): DOMContentLoaded / load; flag acima do limite | `perf` | ✅ |
| 2.6 | **HTML suspeito**: `<a>` sem href e sem role; `<form>` sem submit; `<form>` sem `method`; input `name` duplicado; `<table>` sem `<th>`; texto `TODO`/`lorem ipsum`/`undefined`/`null` renderizado | `html` | ✅ |
| 2.7 | **Mixed content** (página https puxando http) | `seguranca` | ✅ |
| 2.8 | **Título/ível de página**: `<title>` repetido entre páginas diferentes; `<title>` genérico ("SAUR" puro) | `meta` | ✅ |
| 2.9 | **Flaky**: cada página é reexaminada 1×; achado que aparece só numa das duas passadas vira severidade BAIXA com nota "intermitente" | (todas) | ✅ |
| 2.10 | **Regressão visual**: screenshot de cada página vs. baseline salvo, diff pixel a pixel (via `javax.imageio`, sem lib externa); página que muda > `visual-limite-pct` vira achado `visual` + imagem de diff em `report/diff/`. Opt-in (`regressao-visual=true`); 1ª execução cria os baselines. | `visual` | ✅ |

## 3. Relatório

| # | Melhoria | Nesta leva |
|---|---|---|
| 3.1 | HTML: **duas visões** — por severidade **e** por página (colapsável); filtro por categoria; contadores por categoria; tema claro/escuro | ✅ |
| 3.2 | **`report/report.md`** (markdown) pra colar em issue/PR | ✅ |
| 3.3 | **`report/junit.xml`** — cada achado ALTO = testcase falho; serve pra gate de CI | ✅ |
| 3.4 | **Diff vs. execução anterior**: cada achado marcado `NOVO` / `PERSISTE`; seção "corrigidos desde a última vez" | ✅ |
| 3.5 | **`report/history.csv`** — 1 linha por execução (data, alvo, páginas, altos/médios/baixos) | ✅ |
| 3.6 | Screenshot inline (miniatura clicável) no HTML | ✅ |
| 3.7 | Painel de "páginas mais problemáticas" (top N) | ✅ |

## 4. Robustez / operação

| # | Melhoria | Nesta leva |
|---|---|---|
| 4.1 | Retry de navegação 1× antes de registrar `navegacao-falhou` | ✅ |
| 4.2 | Exit codes distintos: 0 ok · 1 achados altos · 2 config/segurança · 3 alvo fora do ar · 4 nenhum login funcionou | ✅ |
| 4.3 | `robo.env` carregado pelo próprio Java (feito na leva anterior) — mantém | ✅ (já) |
| 4.4 | Log estruturado em `report/run.log` | ✅ |
| 4.5 | `--perfil <nome>` pra rodar só um perfil | ✅ |

---

## Implementado nesta leva (resumo)

**Navegação:** rotas-semente (12 rotas do operador sempre visitadas) + amostragem
de deep-links (`/x/{id}`, N por prefixo) + `rotas-extra` no config + `--only <regex>`
+ `--perfil <nome>` + teto de tempo (`tempo-max-min`) com relatório parcial +
retry de navegação 1×. **Paralelismo: NÃO** (limitação do Playwright-Java).

**Sondas novas:** `a11y` (10 checagens próprias), `link-morto` (GET em todo link
interno não visitado), `http-recurso` (4xx/5xx de qualquer recurso), `lento`
(request acima de `req-lento-ms`), `perf` (load da página acima de `perf-limite-ms`),
`html` (form sem submit/method, `<a>` sem href, name duplicado, texto
`undefined`/`lorem ipsum`/`TODO`), `seguranca` (mixed content). Detecção de
**flaky** (2ª passada; achado que só aparece 1× vira BAIXA "intermitente").

**Relatório:** `index.html` com visão **por severidade** E **por página**
(colapsável) + contadores por categoria + top-8 páginas problemáticas + seção
"corrigidos desde a última execução" + tags NOVO/persiste. Novos arquivos:
`report.md`, `junit.xml`, `history.csv` (1 linha por execução). Diff automático
contra o `findings.json` anterior.

**Operação:** exit codes 0 (ok) / N (nº de altos, teto 99) / 2 (config/segurança)
/ 3 (alvo fora do ar) / 4 (nenhum login funcionou).

## 5. Fora de escopo desta leva

- Testar submit de formulário num ambiente de sandbox dedicado (o robô é e
  continua **read-only**).
- Rodar o [axe-core] oficial — evitado de propósito: dependência de ~500 KB
  e o ambiente é offline atrás de proxy MITM. As checagens de 2.1 cobrem a
  maior parte dos achados comuns sem isso.

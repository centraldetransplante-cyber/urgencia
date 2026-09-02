# Robô navegador SAUR

Navega o SAUR (o app em `../urgencia`) de forma autônoma num navegador real
e escreve um relatório do que está quebrado. Ferramenta avulsa — **não faz
parte do build Maven do SAUR**.

**Este diretório já vem apontado para PRODUÇÃO** (`robo.config` →
`https://urgenciarenal.duckdns.org`). Para dev, use `--config robo.config.local`.

## O que ele faz

Para cada perfil configurado:

1. **loga** no `/login`;
2. faz um **BFS a partir da rota inicial**, seguindo todo `<a href>` do mesmo
   domínio (só GET), até o limite de páginas/profundidade;
3. em cada página, roda uma bateria de **sondas**;
4. gera `report/index.html` (visual) + `report/findings.json` (máquina).
   O *exit code* é o nº de achados **ALTOS** (bom pra CI).

### Sondas por página

| Categoria | Severidade | Pega |
|---|---|---|
| `http` / `http-recurso` | ALTA | navegação ou qualquer recurso (XHR/img/iframe) retornou 4xx/5xx |
| `js-erro` | ALTA | exceção de JS não tratada |
| `css-nao-carregou` | ALTA/MÉDIA | `app.css`/bootstrap fora das folhas de estilo |
| `bootstrap-js-ausente` | ALTA | tela com `data-bs-*` mas sem `window.bootstrap` (faltou `~{layout :: scripts}`) |
| `dropdown-navbar-morto`¹ | ALTA | menu do usuário não abre ao clicar |
| `link-morto` | ALTA/MÉDIA | link interno aponta para 404/410/5xx |
| `seguranca` | ALTA | mixed content (recurso http:// em página https) |
| `console-erro` / `console-warning` | MÉDIA/BAIXA | erro de runtime |
| `asset-falhou` | MÉDIA | `.css/.js/.woff/.png` não carregou |
| `layout-estouro` / `-mobile` | MÉDIA | rola na horizontal a 1280px **e** a 390px |
| `toggle-nao-responde`¹ | MÉDIA | aba/collapse `data-bs-toggle` não reage ao clique |
| `a11y` | MÉDIA/BAIXA | `<img>` sem alt, campo sem label, botão sem nome, `<html>` sem lang, sem viewport, id duplicado, `<label for>` órfão, pulo de heading, sem `<main>` |
| `html` | MÉDIA/BAIXA | `<form>` sem submit/method, `<a>` sem href, name duplicado, texto `undefined`/`lorem ipsum`/`TODO` renderizado |
| `lento` / `perf` | MÉDIA | request > `req-lento-ms` / página carrega em > `perf-limite-ms` |
| `sem-title` / `sem-h1` / `img-quebrada` / `main-vazio` | BAIXA/MÉDIA | página incompleta |

¹ Só rodam em **modo local** (clicam em elementos). Em alvo remoto o robô só navega.

Achado que só aparece numa de 2 passadas é marcado **`[intermitente]`** e rebaixado
para BAIXA. As sondas de "bootstrap ausente" e "dropdown morto" nasceram de bugs
reais desta base em 2026-08.

### Saídas em `report/`

`index.html` (visão por severidade **e** por página, contadores, top-8 páginas,
diff NOVO/persiste/corrigidos) · `report.md` (colar em issue/PR) · `junit.xml`
(gate de CI) · `findings.json` (máquina) · `history.csv` (1 linha por execução).

## Segurança

- **Só leitura.** Segue links (GET) e — **só em modo local** — clica em
  gatilhos seguros (dropdown / aba / collapse), sempre apertando `Esc`
  depois. **Nunca** preenche nem envia formulário, nunca confirma modal.
- Denylist embutida: `/logout`, `/excluir`, `/reabrir`, `/decidir`,
  `/votar`, `/cancelar`, `/enviar`, `/anexos`, `/comprovante-snt`,
  `/documento-clinico`, `/exportar`, `/oficio`, `/ajax`, `/marcar`,
  `/nao-lidas`, `/h2-console`, … (extensível: `denylist-url-extra`).
- **Recusa alvo que não seja `localhost`** a menos que **as duas** estejam no
  config: `permitir-remoto = true` **e** `eu-entendo-os-riscos = true`.
- Alvo remoto força o modo **`seguro-remoto`**:

  | | local | seguro-remoto |
  |---|---|---|
  | clica em dropdown/aba/collapse | sim | **não** — só navega |
  | screenshot das páginas com achado | sim | **não** (não grava dado de paciente no disco) |
  | pausa entre páginas | 0 | 700 ms |
  | máx. páginas / profundidade | 150 / 6 | 60 / 4 |

## Rodando contra PRODUÇÃO — configuração de UMA vez

```bash
cd robo-navegador-saur
cp robo.env.example robo.env
#  edite robo.env e troque o placeholder pela senha real do admin de produção
```

Pronto. Daqui pra frente, **sem digitar nada**:

```bash
./run.sh            # ou:  .\run.ps1     → roda contra prod e abre o report
```

- `robo.env` está no `.gitignore` (não vai pro git).
- A senha de produção é a de `SGPUR_ADMIN_PASSWORD` no deploy da VM Oracle —
  **não é `Admin123!`** (esse é o default só de dev).
- Se você não criar `robo.env`, o `run.*` pergunta a senha na hora (oculta).

Pré-requisitos: **JDK 21** + **Maven** (os `run.*` acham nos caminhos desta
máquina; senão defina `JAVA_HOME` e ponha `mvn` no PATH).

**O que isso implica**, mesmo em `seguro-remoto`:

- **cada página vira uma entrada no log de auditoria do SAUR (com IP)** — o
  robô vai poluir `/auditoria` com dezenas de acessos;
- as telas carregam **dados reais de paciente** no navegador do robô (só em
  memória — sem screenshot em `seguro-remoto`);
- a denylist cobre os endpoints de mutação conhecidos; um `<a href>` que
  mude estado por GET e não esteja na lista **rodaria de verdade** (a
  auditoria de segurança do SAUR não achou nenhum, mas o risco existe);
- login errado conta como tentativa falha (o SAUR aplica atraso
  progressivo após 2 falhas — não trava a conta).

O proxy corporativo desta máquina é lido de `HTTPS_PROXY`/`HTTP_PROXY` e
repassado ao navegador (o `bypass` cobre `localhost`).

## Rodando contra DEV

```bash
# 1. sobe o SAUR:   ../urgencia > .\start.ps1     (porta 3000)
# 2.
./run.sh --config robo.config.local              # ou .\run.ps1 -Config robo.config.local
./run.sh --config robo.config.local --headed --max 40
```

Em dev o SAUR só cria `admin` / `Admin123!` — e o ADMIN já vê quase todas as
telas do operador. Para cobrir `/avaliador` e `/solicitante`, crie os logins
em `/usuarios` e preencha `credencial.2` / `.3` no `robo.config.local`.

## Navegador

Tenta o **Chromium do Playwright** e, se não estiver baixado, **cai sozinho
para o Chrome e depois o Edge do sistema** (sem download). Atrás do proxy
com MITM de TLS o download do Chromium falha
(`UNABLE_TO_VERIFY_LEAF_SIGNATURE`) — aí roda o Chrome/Edge do sistema e está
tudo certo. Forçar: `canal = chrome` (ou `msedge`) no config.

Se quiser mesmo o Chromium do Playwright:
`NODE_TLS_REJECT_UNAUTHORIZED=0 ./run.sh --install-browser`
(o `run.ps1 -InstalarBrowser` já seta a variável).

## Opções de linha de comando

```
--config <arquivo>     robo.config a usar          (padrão: ./robo.config → PROD)
--base-url <url>        sobrepõe a base-url         (flip local<->remoto re-deriva os defaults)
--max <n>               máx de páginas por perfil
--perfil <nome>         roda só esse perfil do config
--only <regex>          só visita URLs que casam com a regex
--headed | --headless  abre / esconde a janela
--install-browser      baixa o Chromium do Playwright e sai
-h | --help
```

Chaves extras do `robo.config` (todas com default): `rotas-extra`,
`deep-links-por-lista`, `tempo-max-min`, `req-lento-ms`, `perf-limite-ms`,
`detectar-flaky`, `so-regex`, `regressao-visual`. Veja `robo.config.example`.

### Regressão visual

`regressao-visual = true` no config: a 1ª execução salva o screenshot de cada
página em `baseline/` (gitignored); as seguintes comparam pixel a pixel e, se
mais de `visual-limite-pct` dos pixels mudaram, geram um achado `visual` + a
imagem de diff (vermelho onde mudou) em `report/diff/`. Sem dependência —
usa `javax.imageio`. Para "resetar" o baseline, apague a pasta `baseline/`.

## Arquivos

```
pom.xml                  projeto Maven standalone (Playwright 1.61.0, Java 21)
robo.config              ATIVO — apontado p/ PRODUÇÃO, modo seguro-remoto   (gitignored)
robo.config.local        template p/ dev (localhost:3000, admin/Admin123!)
robo.config.example      referência de todas as chaves
run.ps1 / run.sh         atalhos de execução
src/main/java/saur/robo/
  Robo.java              main: args, trava de segurança, proxy, escolha de navegador
  Config.java            lê o robo.config (com ${VAR} de ambiente)
  Rastreador.java        login + BFS de links + screenshots
  Sonda.java             as verificações de cada página
  Achado.java            registro de um problema
  RelatorioHtml.java     gera report/index.html + findings.json
```

`report/`, `target/` e `robo.config` estão no `.gitignore`
(`robo.config.local` é versionável — não tem segredo).

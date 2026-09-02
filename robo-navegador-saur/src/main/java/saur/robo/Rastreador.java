package saur.robo;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** BFS por ondas (paralelo) de um perfil: loga, anda pelos links, chama a {@link Sonda} em cada página. */
final class Rastreador {

    /** Rotas do operador que nem sempre estão linkadas da landing — semeadas sempre. */
    private static final List<String> ROTAS_CONHECIDAS = List.of(
            "/", "/processos", "/arquivo", "/membros", "/usuarios", "/auditoria",
            "/controle-urgencias", "/relatorios/anual", "/relatorios/avaliador",
            "/processos/solicitacoes-online", "/processos/mensagens-avaliadores",
            "/usuarios/minha-senha");

    private static final Pattern ID_EM_LINK = Pattern.compile("/([a-z\\-]+)/(\\d+)(?:/[a-z\\-]+)?/?$");

    private final Config cfg;
    private final Browser browser;
    private final Path dirScreenshots;

    final List<Achado> achados = new CopyOnWriteArrayList<>();
    final Set<String> linksInternos = ConcurrentHashMap.newKeySet();
    int paginasVisitadas = 0;
    int loginOk = 0; // 0 = não tentou, 1 = ok, -1 = falhou
    boolean estourouTempo = false;

    private final RegressaoVisual visual;

    Rastreador(Config cfg, Browser browser, Path dirScreenshots) {
        this.cfg = cfg;
        this.browser = browser;
        this.dirScreenshots = dirScreenshots;
        this.visual = cfg.regressaoVisual
                ? new RegressaoVisual(cfg.baselineDir, dirScreenshots.getParent().resolve("diff"), cfg.visualLimitePct)
                : null;
    }

    void rastrear(Config.Credencial cred) {
        String perfil = cred.perfil();
        long deadline = cfg.tempoMaxMin > 0
                ? System.currentTimeMillis() + cfg.tempoMaxMin * 60_000L : Long.MAX_VALUE;

        try (BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 900)
                .setIgnoreHTTPSErrors(true)
                .setUserAgent("SAUR-Robo-Navegador/1.1 (autonomo; somente-leitura)"))) {

            ctx.setDefaultTimeout(cfg.timeoutMs);

            // login numa página; se ok, ela vira o worker 0
            Page primeira = ctx.newPage();
            Sonda.Sinais sinaisLogin = new Sonda.Sinais();
            if (!logar(primeira, cred, sinaisLogin)) {
                loginOk = -1;
                achados.add(Achado.alta("login-falhou", perfil, cfg.baseUrl + "/login", "Login",
                        "Não consegui autenticar como '" + cred.usuario() + "' (" + descreveSenha(cred.senha())
                        + "). Perfil PULADO."));
                return;
            }
            loginOk = 1;

            // Playwright-Java NÃO é thread-safe por instância — o crawl é sequencial
            // (o driver único trava se duas threads dirigem páginas ao mesmo tempo).
            Trabalhador tr = new Trabalhador(primeira, perfil);

            Set<String> vistos = new LinkedHashSet<>();
            java.util.Deque<String[]> fila = new java.util.ArrayDeque<>(); // [url, prof]

            // semente: rota inicial + rotas conhecidas + rotas-extra do config
            Set<String> semente = new LinkedHashSet<>();
            semente.add(abs(cred.rotaInicial()));
            for (String r : ROTAS_CONHECIDAS) semente.add(abs(r));
            for (String r : cfg.rotasExtra) semente.add(abs(r));
            for (String u : semente) {
                if (bloqueado(u) || !aceitaRegex(u)) continue;
                if (vistos.add(norm(u))) fila.add(new String[]{u, "0"});
            }

            int n = 0;
            java.util.Map<String, Integer> deepPorPrefixo = new java.util.HashMap<>();
            while (!fila.isEmpty() && n < cfg.maxPaginasPorPerfil) {
                if (System.currentTimeMillis() >= deadline) { estourouTempo = true; break; }
                String[] item = fila.poll();
                int prof = Integer.parseInt(item[1]);
                n++;
                Set<String> novosLinks = new LinkedHashSet<>();
                processar(tr, item[0], prof, novosLinks);

                if (prof >= cfg.profundidadeMax) continue;
                for (String href : novosLinks) {
                    if (bloqueado(href) || !aceitaRegex(href)) continue;
                    Matcher m = ID_EM_LINK.matcher(href.substring(cfg.baseUrl.length()));
                    if (m.find() && deepPorPrefixo.merge(m.group(1), 1, Integer::sum) > cfg.deepLinksPorLista) continue;
                    if (vistos.add(norm(href))) fila.add(new String[]{href, String.valueOf(prof + 1)});
                }
            }
            paginasVisitadas = n;
            if (estourouTempo)
                achados.add(Achado.baixa("tempo", perfil, cfg.baseUrl, "",
                        "Varredura interrompida pelo teto de " + cfg.tempoMaxMin + " min — relatório é parcial."));
            if (visual != null && visual.baselinesCriados > 0)
                System.out.printf("  [%s] regressão visual: %d baseline(s) criado(s) em %s (rode de novo pra comparar)%n",
                        perfil, visual.baselinesCriados, cfg.baselineDir);

            checarLinksMortos(primeira, perfil, vistos);
        }
    }

    /** GET (com o cookie de sessão) em cada link interno coletado que não foi visitado; reporta 4xx/5xx. */
    private void checarLinksMortos(Page page, String perfil, Set<String> jaVisitados) {
        com.microsoft.playwright.APIRequestContext api = page.request();
        int checados = 0, limite = Math.min(120, cfg.maxPaginasPorPerfil * 3);
        for (String href : new LinkedHashSet<>(linksInternos)) {
            if (checados >= limite) break;
            if (bloqueado(href)) continue;
            if (jaVisitados.contains(norm(href))) continue; // já sabemos o status pela navegação
            checados++;
            try {
                com.microsoft.playwright.APIResponse r = api.get(href,
                        com.microsoft.playwright.options.RequestOptions.create().setTimeout(10000));
                int st = r.status();
                r.dispose();
                if (st == 404 || st == 410) {
                    achados.add(Achado.alta("link-morto", perfil, href, "", "Link interno aponta para HTTP " + st));
                } else if (st >= 500) {
                    achados.add(Achado.alta("link-morto", perfil, href, "", "Link interno devolve HTTP " + st + " (erro no servidor)"));
                } else if (st >= 400 && st != 403 && st != 405) {
                    achados.add(Achado.media("link-morto", perfil, href, "", "Link interno devolve HTTP " + st));
                }
            } catch (RuntimeException ignore) { /* timeout / download / etc. */ }
        }
        if (checados > 0)
            System.out.printf("  [%s] links internos checados: %d%n", perfil, checados);
    }

    // ---------- processamento de 1 página ----------

    private void processar(Trabalhador tr, String url, int prof, Set<String> novosLinks) {
        Page page = tr.page;
        Sonda.Sinais s = tr.sinais;
        s.limpar();

        Response resp = null;
        RuntimeException erroNav = null;
        for (int tentativa = 1; tentativa <= 2 && resp == null; tentativa++) {
            try {
                resp = page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(cfg.timeoutMs));
            } catch (RuntimeException e) {
                erroNav = e;
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("Download is starting") || msg.contains("net::ERR_ABORTED")) {
                    achados.add(Achado.baixa("download-pulado", tr.perfil, url, "",
                            "Link vira download ao abrir; pulei. Ponha em denylist-url-extra se incomodar."));
                    return;
                }
                if (tentativa == 1) { try { Thread.sleep(800); } catch (InterruptedException ignore) {} }
            }
        }
        if (resp == null) {
            achados.add(Achado.alta("navegacao-falhou", tr.perfil, url, "",
                    "page.navigate falhou 2x: " + primeiraLinha(String.valueOf(erroNav == null ? "" : erroNav.getMessage()))));
            return;
        }
        s.statusNavegacao = resp.status();
        try { page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(4000)); } catch (RuntimeException ignore) {}

        if (page.url().contains("/login")) {
            achados.add(Achado.media("sessao-perdida", tr.perfil, url, "",
                    "Abrir esta URL redirecionou para /login (sessão expirou ou a rota exige outro perfil)."));
            return;
        }

        List<Achado> daPagina = new ArrayList<>(tr.sonda.inspecionar(page, url, s));

        // flaky: 2ª passada; achado que só apareceu numa das duas cai pra BAIXA
        if (cfg.detectarFlaky) {
            try { page.waitForTimeout(150); } catch (RuntimeException ignore) {}
            List<Achado> p2 = tr.sonda.inspecionar(page, url, s);
            Set<String> chaves2 = new LinkedHashSet<>();
            for (Achado a : p2) chaves2.add(a.categoria() + "|" + a.detalhe());
            List<Achado> ajust = new ArrayList<>();
            for (Achado a : daPagina) {
                if (chaves2.contains(a.categoria() + "|" + a.detalhe())) ajust.add(a);
                else ajust.add(Achado.baixa(a.categoria(), a.perfil(), a.url(), a.tituloPagina(),
                        a.detalhe() + "  [intermitente: só apareceu numa das 2 passadas]"));
            }
            daPagina = ajust;
        }

        // estouro horizontal no mobile
        try {
            page.setViewportSize(390, 800);
            page.waitForTimeout(150);
            Object over = page.evaluate("() => Math.max(document.documentElement.scrollWidth,"
                    + " document.body ? document.body.scrollWidth : 0) - window.innerWidth");
            int excesso = (over instanceof Number nu) ? nu.intValue() : 0;
            if (excesso > 3)
                daPagina.add(Achado.media("layout-estouro-mobile", tr.perfil, url, safeTitle(page),
                        "Estoura " + excesso + "px na horizontal em viewport de 390px (mobile)."));
        } catch (RuntimeException ignore) {
        } finally {
            try { page.setViewportSize(1280, 900); } catch (RuntimeException ignore) {}
        }

        // regressão visual: 1 screenshot por página (independe de ter achado)
        if (visual != null) {
            try {
                byte[] png = page.screenshot(new Page.ScreenshotOptions().setFullPage(true)
                        .setAnimations(com.microsoft.playwright.options.ScreenshotAnimations.DISABLED));
                daPagina.addAll(visual.comparar(tr.perfil, url, safeTitle(page), png, slugDe(tr.perfil, url)));
            } catch (RuntimeException ignore) {}
        }

        if (!daPagina.isEmpty()) {
            String shot = cfg.screenshots ? screenshot(page, tr.perfil, url) : null;
            for (Achado a : daPagina) achados.add(shot != null && a.screenshot() == null ? a.comScreenshot(shot) : a);
            System.out.printf("  [%s] %-52s  %d achado(s)%n", tr.perfil, encurtaUrl(url), daPagina.size());
        } else {
            System.out.printf("  [%s] %-52s  ok%n", tr.perfil, encurtaUrl(url));
        }

        if (cfg.pausaEntrePaginasMs > 0)
            try { page.waitForTimeout(cfg.pausaEntrePaginasMs); } catch (RuntimeException ignore) {}

        // coleta todos os links internos (o cap de deep-links por prefixo é do chamador)
        for (String href : linksNaPagina(page)) {
            linksInternos.add(href);
            novosLinks.add(href);
        }
    }

    // ---------- login ----------

    private boolean logar(Page page, Config.Credencial cred, Sonda.Sinais sinais) {
        sinais.limpar();
        page.navigate(cfg.baseUrl + "/login", new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(cfg.timeoutMs));
        try {
            page.fill(cfg.campoUsuario, cred.usuario());
            page.fill(cfg.campoSenha, cred.senha());
            page.locator(cfg.seletorSubmitLogin).first().click(
                    new com.microsoft.playwright.Locator.ClickOptions().setTimeout(cfg.timeoutMs));
        } catch (RuntimeException e) {
            System.out.println("  login: não achei os campos do formulário (" + e.getMessage() + ")");
            return false;
        }
        try { page.waitForURL(u -> !u.contains("/login"),
                new Page.WaitForURLOptions().setTimeout(8000)); } catch (RuntimeException ignore) {}
        try { page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(4000)); } catch (RuntimeException ignore) {}
        boolean ok = !page.url().contains("/login");
        if (!ok) {
            boolean alerta = safeVisible(page, ".alert-danger, .alert.alert-danger");
            System.out.println("  login falhou: continua em " + page.url()
                    + (alerta ? " (\"Usuário ou senha inválidos\")" : "")
                    + " — senha usada: " + descreveSenha(cred.senha()));
        }
        return ok;
    }

    private static boolean safeVisible(Page p, String sel) {
        try { return p.locator(sel).first().isVisible(); } catch (RuntimeException e) { return false; }
    }

    private static String descreveSenha(String s) {
        if (s == null || s.isEmpty()) return "senha VAZIA — ponha SAUR_PROD_ADMIN no robo.env, ou a senha literal no config";
        if (s.startsWith("${") && s.endsWith("}"))
            return "texto literal '" + s + "' (${...} é variável de ambiente, não valor)";
        return s.length() + " caracteres";
    }

    // ---------- worker ----------

    private final class Trabalhador {
        final Page page;
        final String perfil;
        final Sonda sonda;
        final Sonda.Sinais sinais = new Sonda.Sinais();

        Trabalhador(Page page, String perfil) {
            this.page = page;
            this.perfil = perfil;
            this.sonda = new Sonda(perfil, cfg.probesInterativas, cfg.perfLimiteMs);
            page.onConsoleMessage(m -> {
                if ("error".equals(m.type())) sinais.consoleErros.add(m.text());
                else if ("warning".equals(m.type())) sinais.consoleWarnings.add(m.text());
            });
            page.onPageError(sinais.jsErros::add);
            page.onRequestFailed(r -> {
                String u = r.url();
                if (u.startsWith(cfg.baseUrl) && u.matches(".*\\.(css|js|woff2?|png|jpe?g|svg|ico)(\\?.*)?$")) {
                    String f = r.failure();
                    sinais.requestsFalhos.add(u + (f != null ? " (" + f + ")" : ""));
                }
            });
            page.onResponse(r -> {
                try {
                    if (!r.url().startsWith(cfg.baseUrl)) return;
                    int st = r.status();
                    if (st >= 400 && !r.url().contains("/login")) sinais.respostasErro.add(st + " " + r.url());
                } catch (RuntimeException ignore) {}
            });
            // request lento: mede tempo de parede entre início e fim
            java.util.Map<Object, Long> ini = new java.util.concurrent.ConcurrentHashMap<>();
            page.onRequest(rq -> ini.put(rq, System.currentTimeMillis()));
            page.onRequestFinished(rq -> {
                Long t0 = ini.remove(rq);
                if (t0 == null || !rq.url().startsWith(cfg.baseUrl)) return;
                long ms = System.currentTimeMillis() - t0;
                if (ms > cfg.reqLentoMs) sinais.requestsLentos.add(ms + "ms " + rq.url());
            });
        }
    }

    // ---------- helpers ----------

    private List<String> linksNaPagina(Page page) {
        try {
            Object raw = page.evaluate("""
                () => [...document.querySelectorAll('a[href]')].map(a => a.href)
                        .filter(h => h && !h.startsWith('javascript:') && !h.startsWith('mailto:') && !h.startsWith('tel:'))
                """);
            List<String> out = new ArrayList<>();
            if (raw instanceof List<?> lista) {
                for (Object o : lista) {
                    String h = String.valueOf(o);
                    int hash = h.indexOf('#');
                    if (hash >= 0) h = h.substring(0, hash);
                    if (h.startsWith(cfg.baseUrl) && !h.isBlank()) out.add(h);
                }
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private boolean bloqueado(String url) {
        String u = url.toLowerCase();
        for (String d : cfg.denylistUrl) if (u.contains(d.toLowerCase())) return true;
        return u.matches(".*\\.(pdf|zip|csv|xlsx?|rtf|png|jpe?g|svg|ico|woff2?|js|css|map)(\\?.*)?$");
    }

    private boolean aceitaRegex(String url) {
        return cfg.soRegex == null || cfg.soRegex.matcher(url).find();
    }

    private String abs(String rota) {
        if (rota == null || rota.isBlank()) return cfg.baseUrl + "/";
        if (rota.startsWith("http")) return rota;
        return cfg.baseUrl + (rota.startsWith("/") ? rota : "/" + rota);
    }

    private String norm(String url) {
        String u = url;
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        if (u.length() > 1 && u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private String encurtaUrl(String u) {
        String s = u.startsWith(cfg.baseUrl) ? u.substring(cfg.baseUrl.length()) : u;
        return s.isEmpty() ? "/" : (s.length() > 52 ? s.substring(0, 51) + "…" : s);
    }

    private static String safeTitle(Page p) {
        try { return p.title(); } catch (RuntimeException e) { return ""; }
    }

    private static String primeiraLinha(String s) {
        if (s == null) return "(sem mensagem)";
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).trim();
    }

    private static String slugDe(String perfil, String url) {
        String slug = (perfil + "-" + url.replaceFirst("^https?://[^/]+", "")
                .replaceAll("[^a-zA-Z0-9]+", "_")).replaceAll("_+", "_");
        return slug.length() > 120 ? slug.substring(0, 120) : slug;
    }

    private String screenshot(Page page, String perfil, String url) {
        try {
            Path destino = dirScreenshots.resolve(slugDe(perfil, url) + ".png");
            page.screenshot(new Page.ScreenshotOptions().setFullPage(true).setPath(destino));
            return "screenshots/" + destino.getFileName();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

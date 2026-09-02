package saur.robo;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Roda as verificações de uma página já carregada e devolve os {@link Achado}.
 * Nada aqui envia formulário nem confirma modal — só observa e clica em
 * gatilhos seguros (dropdown/aba/collapse), sempre desfazendo depois.
 */
final class Sonda {

    /** Buffers preenchidos pelos listeners do Rastreador durante a navegação. */
    static final class Sinais {
        int statusNavegacao = -1;
        final List<String> consoleErros = new ArrayList<>();
        final List<String> consoleWarnings = new ArrayList<>();
        final List<String> jsErros = new ArrayList<>();
        final List<String> requestsFalhos = new ArrayList<>();
        /** "STATUS URL" de respostas 4xx/5xx (qualquer recurso, não só a navegação). */
        final List<String> respostasErro = new ArrayList<>();
        /** "MS URL" de requests acima do limite. */
        final List<String> requestsLentos = new ArrayList<>();

        void limpar() {
            statusNavegacao = -1;
            consoleErros.clear();
            consoleWarnings.clear();
            jsErros.clear();
            requestsFalhos.clear();
            respostasErro.clear();
            requestsLentos.clear();
        }
    }

    private final String perfil;
    private final boolean probesInterativas;
    private final int perfLimiteMs;

    Sonda(String perfil, boolean probesInterativas, int perfLimiteMs) {
        this.perfil = perfil;
        this.probesInterativas = probesInterativas;
        this.perfLimiteMs = perfLimiteMs;
    }

    List<Achado> inspecionar(Page page, String url, Sinais s) {
        List<Achado> out = new ArrayList<>();
        String titulo = safeTitle(page);

        // 1. HTTP da própria navegação
        if (s.statusNavegacao >= 400) {
            out.add(Achado.alta("http", perfil, url, titulo,
                    "Navegação retornou HTTP " + s.statusNavegacao));
        }

        // 2. Erros de JS não capturados
        for (String e : dedupe(s.jsErros, 5)) {
            out.add(Achado.alta("js-erro", perfil, url, titulo, "Exceção de JS não tratada: " + corta(e, 300)));
        }

        // 3. Console
        for (String e : dedupe(s.consoleErros, 6)) {
            if (ruidoConhecido(e)) continue;
            out.add(Achado.media("console-erro", perfil, url, titulo, "console.error: " + corta(e, 300)));
        }
        for (String w : dedupe(s.consoleWarnings, 3)) {
            if (ruidoConhecido(w)) continue;
            out.add(Achado.baixa("console-warning", perfil, url, titulo, "console.warn: " + corta(w, 220)));
        }

        // 4. Assets que falharam (CSS/JS/fonte/imagem)
        for (String r : dedupe(s.requestsFalhos, 8)) {
            out.add(Achado.media("asset-falhou", perfil, url, titulo, "Request de asset falhou: " + corta(r, 300)));
        }

        // 5. CSS base carregou? (app.css define --rs-blue; bootstrap define --bs-blue)
        try {
            Object css = page.evaluate("""
                () => {
                  const cs = getComputedStyle(document.documentElement);
                  const rs = cs.getPropertyValue('--rs-blue').trim();
                  const bs = cs.getPropertyValue('--bs-primary').trim() || cs.getPropertyValue('--bs-blue').trim();
                  const folhas = [...document.styleSheets].map(s => s.href || '').join(' ');
                  return { rs, bs, temAppCss: folhas.includes('app.css') || folhas.includes('/css/app'),
                           temBootstrap: folhas.toLowerCase().includes('bootstrap') };
                }""");
            String m = String.valueOf(css);
            if (m.contains("temAppCss=false")) {
                out.add(Achado.alta("css-nao-carregou", perfil, url, titulo,
                        "app.css não está entre as folhas de estilo da página (" + corta(m, 240) + ")"));
            } else if (m.contains("temBootstrap=false")) {
                out.add(Achado.media("css-nao-carregou", perfil, url, titulo,
                        "Nenhuma folha 'bootstrap' carregada (" + corta(m, 240) + ")"));
            }
        } catch (RuntimeException ignore) { /* página sem <html> normal, etc. */ }

        // 6. Estouro horizontal no viewport atual
        try {
            Object over = page.evaluate("""
                () => {
                  const de = document.documentElement;
                  const larg = Math.max(de.scrollWidth, document.body ? document.body.scrollWidth : 0);
                  return { over: larg - window.innerWidth, vw: window.innerWidth };
                }""");
            String m = String.valueOf(over);
            int excesso = extrairInt(m, "over=");
            int vw = extrairInt(m, "vw=");
            if (excesso > 3) {
                out.add(Achado.media("layout-estouro", perfil, url, titulo,
                        "Página rola na horizontal: " + excesso + "px além da viewport de " + vw + "px"));
            }
        } catch (RuntimeException ignore) {}

        // 7. Conteúdo principal não-vazio
        try {
            Locator main = page.locator("#conteudo, main").first();
            if (main.count() == 0) {
                out.add(Achado.media("sem-main", perfil, url, titulo, "Página sem <main>/#conteudo"));
            } else {
                String txt = main.innerText().strip();
                if (txt.length() < 2) {
                    out.add(Achado.media("main-vazio", perfil, url, titulo, "<main>/#conteudo está vazio"));
                }
            }
        } catch (RuntimeException ignore) {}

        // 8. Bootstrap JS presente quando a tela usa data-bs-*
        try {
            boolean usaBs = page.locator("[data-bs-toggle]").count() > 0;
            if (usaBs) {
                Object temBs = page.evaluate("() => typeof window.bootstrap !== 'undefined'");
                if (Boolean.FALSE.equals(temBs)) {
                    out.add(Achado.alta("bootstrap-js-ausente", perfil, url, titulo,
                            "A tela tem elementos data-bs-* mas window.bootstrap não existe — "
                            + "faltou <script th:replace=\"~{layout :: scripts}\"> neste template "
                            + "(dropdown/modal/collapse ficam mortos)."));
                }
            }
        } catch (RuntimeException ignore) {}

        // 9 e 10 CLICAM em elementos — só quando probes interativas estão ligadas
        // (em alvo remoto vêm desligadas por padrão: lá o robô só navega).
        if (probesInterativas) {
            // 9. Dropdown do usuário na navbar realmente abre
            try {
                Locator gatilho = page.locator(".navbar [data-bs-toggle=dropdown], #menuUsuario").first();
                if (gatilho.count() > 0 && gatilho.isVisible()) {
                    gatilho.click(new Locator.ClickOptions().setTimeout(3000));
                    page.waitForTimeout(350);
                    boolean abriu = page.locator(".dropdown-menu.show").count() > 0;
                    if (!abriu) {
                        out.add(Achado.alta("dropdown-navbar-morto", perfil, url, titulo,
                                "Cliquei no nome do usuário na navbar e nenhum .dropdown-menu.show apareceu."));
                    }
                    page.keyboard().press("Escape");
                    page.waitForTimeout(120);
                }
            } catch (RuntimeException ignore) {}

            // 10. Abas / collapses respondem (fora da navbar), até 8
            try {
                Locator gatilhos = page.locator(
                        ":is([data-bs-toggle=tab],[data-bs-toggle=collapse],[data-bs-toggle=pill]):not(.navbar [data-bs-toggle])");
                int n = Math.min(gatilhos.count(), 8);
                for (int i = 0; i < n; i++) {
                    Locator g = gatilhos.nth(i);
                    try {
                        if (!g.isVisible()) continue;
                        String antesExp = attr(g, "aria-expanded");
                        String antesCls = attr(g, "class");
                        g.click(new Locator.ClickOptions().setTimeout(2500));
                        page.waitForTimeout(250);
                        String depoisExp = attr(g, "aria-expanded");
                        String depoisCls = attr(g, "class");
                        boolean mexeu = !nvl(antesExp).equals(nvl(depoisExp))
                                || !nvl(antesCls).equals(nvl(depoisCls))
                                || page.locator(".tab-pane.active, .collapse.show, .collapsing").count() > 0;
                        if (!mexeu) {
                            out.add(Achado.media("toggle-nao-responde", perfil, url, titulo,
                                    "Gatilho data-bs-toggle não reagiu ao clique: " + corta(nvl(antesCls), 120)));
                        }
                    } catch (RuntimeException ignore) { /* elemento sumiu / re-render */ }
                }
            } catch (RuntimeException ignore) {}
        }

        // 11. <title> e <h1>
        try {
            if (titulo == null || titulo.isBlank()) {
                out.add(Achado.baixa("sem-title", perfil, url, "", "Página sem <title>"));
            }
            if (page.getByRole(AriaRole.HEADING).count() == 0 && page.locator("h1").count() == 0) {
                out.add(Achado.baixa("sem-h1", perfil, url, titulo, "Página sem nenhum heading (<h1>..)"));
            }
        } catch (RuntimeException ignore) {}

        // 12. Imagens quebradas
        try {
            Object quebradas = page.evaluate("""
                () => [...document.images]
                        .filter(i => i.complete && i.naturalWidth === 0)
                        .map(i => i.currentSrc || i.src).slice(0, 5)""");
            String m = String.valueOf(quebradas);
            if (!m.equals("[]") && !m.equals("null")) {
                out.add(Achado.media("img-quebrada", perfil, url, titulo, "Imagem(ns) não carregou: " + corta(m, 260)));
            }
        } catch (RuntimeException ignore) {}

        // 13. Respostas HTTP 4xx/5xx de QUALQUER recurso da página (XHR, img, iframe)
        for (String r : dedupe(s.respostasErro, 8)) {
            String sev = r.startsWith("5") ? "ALTA" : "MEDIA";
            Achado a = "ALTA".equals(sev)
                    ? Achado.alta("http-recurso", perfil, url, titulo, "Recurso da página retornou HTTP " + r)
                    : Achado.media("http-recurso", perfil, url, titulo, "Recurso da página retornou HTTP " + r);
            out.add(a);
        }

        // 14. Requests lentos
        for (String r : dedupe(s.requestsLentos, 5)) {
            out.add(Achado.media("lento", perfil, url, titulo, "Request acima do limite: " + corta(r, 260)));
        }

        // 15. Timing da própria página
        try {
            Object t = page.evaluate("""
                () => { const n = performance.getEntriesByType('navigation')[0];
                        return n ? Math.round(n.loadEventEnd || n.domComplete || 0) : 0; }""");
            int loadMs = (t instanceof Number nu) ? nu.intValue() : 0;
            if (loadMs > perfLimiteMs) {
                out.add(Achado.media("perf", perfil, url, titulo,
                        "Página levou " + loadMs + "ms para carregar (limite " + perfLimiteMs + "ms)"));
            }
        } catch (RuntimeException ignore) {}

        // 16. Acessibilidade (checagens próprias, sem dependência externa)
        try {
            Object a11y = page.evaluate("""
                () => {
                  const r = {};
                  r.imgSemAlt = [...document.images].filter(i => !i.hasAttribute('alt')).length;
                  const controles = [...document.querySelectorAll('input:not([type=hidden]):not([type=submit]):not([type=button]),select,textarea')];
                  r.semLabel = controles.filter(c => {
                     if (c.getAttribute('aria-label') || c.getAttribute('aria-labelledby') || c.title) return false;
                     if (c.id && document.querySelector('label[for="'+CSS.escape(c.id)+'"]')) return false;
                     if (c.closest('label')) return false;
                     return true;
                  }).length;
                  r.botoesSemNome = [...document.querySelectorAll('button,a[role=button],[role=button]')].filter(b => {
                     const txt = (b.innerText||'').trim();
                     return !txt && !b.getAttribute('aria-label') && !b.title && !b.querySelector('img[alt]');
                  }).length;
                  r.semLang = !document.documentElement.getAttribute('lang');
                  r.semViewport = !document.querySelector('meta[name=viewport]');
                  const ids = [...document.querySelectorAll('[id]')].map(e => e.id);
                  r.idsDup = ids.filter((v,i) => v && ids.indexOf(v) !== i).filter((v,i,arr)=>arr.indexOf(v)===i).slice(0,6);
                  r.labelOrfao = [...document.querySelectorAll('label[for]')]
                     .filter(l => !document.getElementById(l.getAttribute('for'))).length;
                  const hs = [...document.querySelectorAll('h1,h2,h3,h4,h5,h6')].map(h => +h.tagName[1]);
                  r.puloHeading = hs.some((n,i) => i>0 && n - hs[i-1] > 1);
                  r.semMain = !document.querySelector('main,[role=main]');
                  return r;
                }""");
            String m = String.valueOf(a11y);
            a11yAchado(out, url, titulo, m, "imgSemAlt=", n -> n > 0, n -> n + " <img> sem atributo alt");
            a11yAchado(out, url, titulo, m, "semLabel=", n -> n > 0, n -> n + " campo(s) de formulário sem <label>/aria-label");
            a11yAchado(out, url, titulo, m, "botoesSemNome=", n -> n > 0, n -> n + " botão/link sem texto nem nome acessível");
            a11yAchado(out, url, titulo, m, "labelOrfao=", n -> n > 0, n -> n + " <label for> apontando para id inexistente");
            if (m.contains("semLang=true")) out.add(Achado.media("a11y", perfil, url, titulo, "<html> sem atributo lang"));
            if (m.contains("semViewport=true")) out.add(Achado.media("a11y", perfil, url, titulo, "sem <meta name=viewport>"));
            if (m.contains("puloHeading=true")) out.add(Achado.baixa("a11y", perfil, url, titulo, "hierarquia de headings pula nível (ex. h1→h3)"));
            if (m.contains("semMain=true")) out.add(Achado.baixa("a11y", perfil, url, titulo, "página sem landmark <main>"));
            java.util.regex.Matcher mi = java.util.regex.Pattern.compile("idsDup=\\[([^\\]]+)]").matcher(m);
            if (mi.find() && !mi.group(1).isBlank())
                out.add(Achado.media("a11y", perfil, url, titulo, "id(s) HTML duplicado(s): " + corta(mi.group(1), 160)));
        } catch (RuntimeException ignore) {}

        // 17. HTML suspeito
        try {
            Object html = page.evaluate("""
                () => {
                  const r = {};
                  r.aSemHref = [...document.querySelectorAll('a:not([href]):not([role])')].filter(a => (a.innerText||'').trim()).length;
                  r.formSemSubmit = [...document.forms].filter(f =>
                     !f.querySelector('button:not([type=button]),input[type=submit],button[type=submit]')).length;
                  r.formSemMethod = [...document.forms].filter(f => !f.getAttribute('method')).length;
                  // name duplicado DENTRO do mesmo form (ignora radio/checkbox, que compartilham name de propósito)
                  r.nameDup = [];
                  [...document.forms].forEach((f, fi) => {
                     const vistos = {};
                     [...f.querySelectorAll('[name]')].forEach(e => {
                        if (e.type === 'radio' || e.type === 'checkbox') return;
                        const k = e.name; vistos[k] = (vistos[k]||0) + 1;
                     });
                     Object.entries(vistos).filter(([,c]) => c > 1).forEach(([k]) => r.nameDup.push('form#'+fi+' '+k));
                  });
                  r.nameDup = r.nameDup.slice(0, 5);
                  const corpo = document.body ? document.body.innerText : '';
                  r.textoRuim = ['lorem ipsum','undefined','null','[object Object]','NaN','TODO:'].filter(t =>
                     corpo.includes(t)).slice(0,4);
                  return r;
                }""");
            String m = String.valueOf(html);
            a11yAchado(out, url, titulo, m, "aSemHref=", n -> n > 0, n -> n + " <a> com texto mas sem href (link morto no DOM)", "html");
            a11yAchado(out, url, titulo, m, "formSemSubmit=", n -> n > 0, n -> n + " <form> sem botão de submit", "html");
            a11yAchado(out, url, titulo, m, "formSemMethod=", n -> n > 0, n -> n + " <form> sem atributo method", "html");
            java.util.regex.Matcher mt = java.util.regex.Pattern.compile("textoRuim=\\[([^\\]]+)]").matcher(m);
            if (mt.find() && !mt.group(1).isBlank())
                out.add(Achado.media("html", perfil, url, titulo, "texto suspeito renderizado na página: " + corta(mt.group(1), 160)));
            java.util.regex.Matcher mn = java.util.regex.Pattern.compile("nameDup=\\[([^\\]]+)]").matcher(m);
            if (mn.find() && !mn.group(1).isBlank())
                out.add(Achado.baixa("html", perfil, url, titulo, "campos com name duplicado no mesmo form: " + corta(mn.group(1), 160)));
        } catch (RuntimeException ignore) {}

        // 18. Mixed content (página https puxando http)
        if (url.startsWith("https://")) {
            try {
                Object mixed = page.evaluate("""
                    () => [...document.querySelectorAll('[src],[href]')]
                        .map(e => e.getAttribute('src') || e.getAttribute('href'))
                        .filter(u => u && u.startsWith('http://')).slice(0, 5)""");
                String m = String.valueOf(mixed);
                if (!m.equals("[]") && !m.equals("null"))
                    out.add(Achado.alta("seguranca", perfil, url, titulo, "mixed content (recurso http:// em página https): " + corta(m, 240)));
            } catch (RuntimeException ignore) {}
        }

        return out;
    }

    private interface RotuloInt { String de(int n); }

    private void a11yAchado(List<Achado> out, String url, String titulo, String mapStr,
                            String chave, java.util.function.IntPredicate quando, RotuloInt rotulo) {
        a11yAchado(out, url, titulo, mapStr, chave, quando, rotulo, "a11y");
    }

    private void a11yAchado(List<Achado> out, String url, String titulo, String mapStr,
                            String chave, java.util.function.IntPredicate quando, RotuloInt rotulo, String cat) {
        int n = extrairInt(mapStr, chave);
        if (quando.test(n)) out.add(Achado.media(cat, perfil, url, titulo, rotulo.de(n)));
    }

    // ---------- helpers ----------

    private static String safeTitle(Page p) {
        try { return p.title(); } catch (RuntimeException e) { return ""; }
    }

    private static String attr(Locator l, String a) {
        try { return l.getAttribute(a); } catch (RuntimeException e) { return null; }
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private static List<String> dedupe(List<String> in, int max) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s == null || s.isBlank()) continue;
            if (!out.contains(s)) out.add(s);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static boolean ruidoConhecido(String s) {
        String l = s.toLowerCase();
        return l.contains("favicon")
                || l.contains("chrome-extension")
                || l.contains("download the react devtools")
                || l.contains("[deprecation]")
                || l.contains("autocomplete attributes");
    }

    private static String corta(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static int extrairInt(String mapToString, String chave) {
        int i = mapToString.indexOf(chave);
        if (i < 0) return 0;
        int j = i + chave.length();
        int k = j;
        while (k < mapToString.length() && (Character.isDigit(mapToString.charAt(k)) || mapToString.charAt(k) == '-')) k++;
        try { return Integer.parseInt(mapToString.substring(j, k)); } catch (RuntimeException e) { return 0; }
    }
}

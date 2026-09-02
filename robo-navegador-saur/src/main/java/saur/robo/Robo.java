package saur.robo;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Proxy;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Robô que navega o SAUR de forma autônoma num Chromium real e reporta o que
 * está quebrado. Só leitura: segue links (GET), clica em gatilhos seguros
 * (dropdown/aba/collapse) e NUNCA envia formulário.
 *
 * <pre>
 *   mvn -q compile exec:java                       # usa ./robo.config (ou defaults)
 *   mvn -q compile exec:java -Dexec.args="--headed --base-url http://localhost:3000"
 * </pre>
 */
public final class Robo {

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("robo.config");
        String baseOverride = null, perfilFiltro = null, onlyOverride = null;
        Boolean headedOverride = null;
        Integer maxOverride = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = Path.of(args[++i]);
                case "--base-url" -> baseOverride = args[++i];
                case "--headed" -> headedOverride = Boolean.TRUE;
                case "--headless" -> headedOverride = Boolean.FALSE;
                case "--max" -> maxOverride = Integer.parseInt(args[++i]);
                case "--perfil" -> perfilFiltro = args[++i];
                case "--only" -> onlyOverride = args[++i];
                case "-h", "--help" -> { ajuda(); return; }
                default -> System.out.println("(ignorando argumento desconhecido: " + args[i] + ")");
            }
        }

        Config cfg = Config.carregar(configPath);
        if (baseOverride != null) cfg = cfg.comOverride("base-url", baseOverride);
        if (maxOverride != null) cfg = cfg.comOverride("max-paginas-por-perfil", String.valueOf(maxOverride));
        if (onlyOverride != null) cfg = cfg.comOverride("so-regex", onlyOverride);
        if (perfilFiltro != null && !perfilFiltro.equalsIgnoreCase("ADMIN")) {
            System.err.println("Acesso recusado: o robô só pode executar o perfil ADMIN.");
            System.exit(2);
        }
        cfg.credenciais.removeIf(cr -> !cr.perfil().equalsIgnoreCase("ADMIN"));
        if (cfg.credenciais.isEmpty()) {
            System.err.println("Acesso recusado: nenhuma credencial com perfil ADMIN foi configurada.");
            System.exit(2);
        }
        if (perfilFiltro != null) {
            final String pf = perfilFiltro;
            cfg.credenciais.removeIf(cr -> !cr.perfil().equalsIgnoreCase(pf));
            if (cfg.credenciais.isEmpty()) { System.err.println("Nenhum perfil chamado '" + pf + "' no config."); System.exit(2); }
        }
        final Config c = cfg;
        final boolean headless = headedOverride != null ? !headedOverride : c.headless;
        final int maxPag = c.maxPaginasPorPerfil;

        System.out.println("== Robô navegador SAUR ==");
        System.out.println("alvo:        " + c.baseUrl);
        System.out.println("perfis:      " + c.credenciais.stream().map(Config.Credencial::perfil).toList()
            + " (somente ADMIN)");
        System.out.println("modo:        " + c.modo + (c.baseEhLocal() ? " (local)" : " (REMOTO)"));
        System.out.println("máx páginas: " + maxPag + " por perfil · profundidade " + c.profundidadeMax
                + " · pausa " + c.pausaEntrePaginasMs + "ms · " + (headless ? "headless" : "com janela"));
        System.out.println("probes:      " + (c.probesInterativas ? "clica em dropdown/aba/collapse" : "SÓ NAVEGA (sem cliques)")
                + " · screenshots " + (c.screenshots ? "on" : "OFF"));

        // Senha vazia / mal-formada -> tenta perguntar agora (entrada oculta, não vai pro disco).
        for (int i = 0; i < c.credenciais.size(); i++) {
            Config.Credencial cr = c.credenciais.get(i);
            String s = cr.senha();
            boolean faltando = s == null || s.isBlank();
            boolean literalDeVar = s != null && s.startsWith("${") && s.endsWith("}");
            if (!faltando && !literalDeVar) continue;

            if (literalDeVar) {
                System.out.println("  ⚠ credencial '" + cr.perfil() + "': a senha ficou literalmente '" + s
                        + "'. ${NOME} lê a variável de ambiente NOME (letras/dígitos/_), não é valor literal.");
            }
            String nova = pedirSenha(cr, c.baseUrl);
            if (nova != null && !nova.isBlank()) {
                c.credenciais.set(i, new Config.Credencial(cr.perfil(), cr.usuario(), nova, cr.rotaInicial()));
                System.out.println("  senha de '" + cr.perfil() + "' recebida (" + nova.length() + " caracteres).");
            } else {
                System.out.println("  ⚠ credencial '" + cr.perfil() + "' (" + cr.usuario() + "): SEM SENHA. "
                        + "Rode com  export SAUR_PROD_ADMIN='...'  antes, ou ponha a senha literal no robo.config, "
                        + "ou rode num terminal interativo pra digitar aqui. Esse perfil vai ser PULADO.");
            }
        }

        // --- trava de segurança: não sair batendo em produção sem querer ---
        if (!c.baseEhLocal()) {
            if (!c.permitirRemoto || !c.entendoRiscos) {
                System.err.println("""

                    RECUSADO: alvo remoto (não é localhost).

                    Este robô loga e navega no SAUR. Apontá-lo para PRODUÇÃO significa:
                      - cada página abre uma entrada no log de auditoria (com IP);
                      - telas de operador carregam dados de paciente (nome/CPF/RGCT);
                      - endpoints de mutação por GET que escapem da denylist rodam de verdade.

                    Se você entende isso e quer mesmo assim, ponha NO robo.config:
                      permitir-remoto = true
                      eu-entendo-os-riscos = true
                    Em alvo remoto o robô já entra em 'seguro-remoto': só navega
                    (não clica em nada), sem screenshot, com pausa entre páginas.
                    """);
                System.exit(2);
            }
            System.out.println("\n*** ALVO REMOTO liberado. Modo '" + c.modo + "'. "
                    + "Registra auditoria em produção e pode carregar dados de paciente. ***\n");
        }

        // Proxy corporativo: o Java não lê HTTP(S)_PROXY sozinho; o Chrome do
        // sistema costuma pegar o proxy do Windows, mas garantimos passando
        // explícito quando o alvo é remoto e a env está setada.
        ProxyEnv proxy = ProxyEnv.doAmbiente();

        boolean alcancou = respondeHttp(c.baseUrl + "/login", c.baseEhLocal() ? null : proxy);
        if (!alcancou && c.baseEhLocal()) {
            System.err.println("\nNão consegui falar com " + c.baseUrl + "/login."
                    + "\nO SAUR está no ar? (../urgencia: .\\start.ps1, porta 3000)\n");
            System.exit(3);
        }
        if (!alcancou) {
            // preflight via Java pode falhar atrás do proxy mesmo com o alvo no ar;
            // o Chrome (com o proxy passado no launch) é o cliente de verdade.
            System.out.println("  (preflight HTTP não confirmou " + c.baseUrl
                    + " — proxy: " + (proxy == null ? "nenhum na env" : proxy.host + ":" + proxy.port)
                    + ". Seguindo mesmo assim; o Chrome tenta pelo proxy.)");
        }

        Path out = c.saida.toAbsolutePath();
        Path shots = out.resolve("screenshots");
        Files.createDirectories(shots);

        // achados da execução anterior (pro diff NOVO/PERSISTE/CORRIGIDO)
        List<Achado> anteriores = RelatorioHtml.lerAnteriores(out.resolve("findings.json"));

        List<Achado> todos = new ArrayList<>();
        List<RelatorioHtml.ResumoPerfil> resumos = new ArrayList<>();
        long t0 = System.currentTimeMillis();

        // Não deixa o Playwright tentar BAIXAR navegador ao subir o driver
        // (atrás de proxy com MITM de TLS o download quebra). Usamos o
        // Chrome/Edge do sistema via canal — ver abrirNavegador().
        Playwright.CreateOptions criar = new Playwright.CreateOptions().setEnv(
                java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
        try (Playwright pw = Playwright.create(criar)) {
            Browser browser = abrirNavegador(pw, c, headless, c.baseEhLocal() ? null : proxy);

            try (browser) {
                for (Config.Credencial cred : c.credenciais) {
                    System.out.println("\n--- perfil " + cred.perfil() + " (login " + cred.usuario() + ") ---");
                    Rastreador r = new Rastreador(c, browser, shots);
                    try {
                        r.rastrear(cred);
                    } catch (RuntimeException e) {
                        todos.add(Achado.alta("robo-quebrou", cred.perfil(), c.baseUrl,
                                "", "O próprio robô lançou exceção neste perfil: " + e));
                    }
                    todos.addAll(r.achados);
                    resumos.add(new RelatorioHtml.ResumoPerfil(cred.perfil(), r.loginOk, r.paginasVisitadas));
                }
            }
        }

        long dur = System.currentTimeMillis() - t0;
        RelatorioHtml.escrever(out, c, resumos, todos, dur, anteriores);

        long altas = todos.stream().filter(a -> a.severidade() == Achado.Severidade.ALTA).count();
        long medias = todos.stream().filter(a -> a.severidade() == Achado.Severidade.MEDIA).count();
        long baixas = todos.stream().filter(a -> a.severidade() == Achado.Severidade.BAIXA).count();

        boolean algumLoginOk = resumos.stream().anyMatch(r -> r.loginStatus() == 1);

        System.out.println("\n=======================================================");
        System.out.printf("páginas: %d · achados: %d ALTOS, %d médios, %d baixos · %ds%n",
                resumos.stream().mapToInt(RelatorioHtml.ResumoPerfil::paginas).sum(),
                altas, medias, baixas, dur / 1000);
        System.out.println("relatório: " + out.resolve("index.html") + "   (report.md / junit.xml / history.csv ao lado)");
        System.out.println("=======================================================");

        // exit codes: 0 ok · 1 achados altos · 4 nenhum login funcionou
        int code = !algumLoginOk ? 4 : altas > 0 ? (int) Math.min(altas, 99) : 0;
        System.exit(code);
    }

    // ---------- infra ----------

    /**
     * Abre o navegador. Se {@code canal} estiver setado na config, usa ele.
     * Senão tenta: Chromium do Playwright -> Chrome do sistema -> Edge do
     * sistema. O download do Chromium do Playwright costuma falhar atrás de
     * proxy com MITM de TLS; Chrome/Edge do sistema não precisam de download.
     */
    private static Browser abrirNavegador(Playwright pw, Config c, boolean headless, ProxyEnv proxy) {
        // ordem de tentativa: o canal pedido (se houver) primeiro, depois os outros como fallback
        List<String> tentativas = new ArrayList<>();
        if (!c.canal.isBlank()) tentativas.add(c.canal);
        for (String extra : new String[]{"", "chrome", "msedge"}) {
            if (!tentativas.contains(extra)) tentativas.add(extra);
        }

        RuntimeException ultima = null;
        for (String canal : tentativas) {
            for (int tent = 1; tent <= 2; tent++) {   // 1 retry por canal (launch às vezes falha transitório)
                try {
                    BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions()
                            .setHeadless(headless).setSlowMo(c.slowMoMs);
                    if (!canal.isBlank()) opts.setChannel(canal);
                    if (proxy != null) {
                        Proxy p = new Proxy("http://" + proxy.host + ":" + proxy.port).setBypass("localhost,127.0.0.1,::1");
                        if (proxy.user != null) p.setUsername(proxy.user).setPassword(proxy.pass);
                        opts.setProxy(p);
                    }
                    Browser b = pw.chromium().launch(opts);
                    System.out.println("navegador:   " + (canal.isBlank() ? "Chromium do Playwright" : canal + " (sistema)")
                            + (proxy != null ? " · via proxy " + proxy.host + ":" + proxy.port : ""));
                    return b;
                } catch (RuntimeException e) {
                    ultima = e;
                    if (tent == 1) { try { Thread.sleep(1500); } catch (InterruptedException ignore) {} continue; }
                    System.out.println("  (não abriu com " + (canal.isBlank() ? "chromium bundled" : canal)
                            + ": " + primeiraLinha(e.getMessage()) + ")");
                }
            }
        }
        System.err.println("""

            Não consegui abrir NENHUM navegador (Chromium bundled, Chrome, Edge).
            - Chrome/Edge instalados? Aponte um: 'canal = chrome' (ou msedge) no robo.config.
            - Para usar o Chromium do Playwright: run.ps1 -InstalarBrowser
              (atrás de proxy com MITM: NODE_TLS_REJECT_UNAUTHORIZED=0 nessa etapa).
            """);
        throw ultima != null ? ultima : new IllegalStateException("sem navegador");
    }

    private static String primeiraLinha(String s) {
        if (s == null) return "(sem mensagem)";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** Lê a senha do terminal (oculta se possível). null se não há terminal interativo. */
    private static String pedirSenha(Config.Credencial cr, String baseUrl) {
        String prompt = "Senha de '" + cr.perfil() + "' (usuário " + cr.usuario() + ") em " + baseUrl + ": ";
        java.io.Console con = System.console();
        if (con != null) {
            char[] c = con.readPassword("  %s", prompt);
            return c == null ? null : new String(c);
        }
        // sem Console (rodando por pipe/CI): tenta stdin normal, senão desiste
        try {
            if (System.in.available() >= 0) {
                System.out.print("  " + prompt);
                System.out.flush();
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                String linha = br.readLine();
                return (linha == null || linha.isBlank()) ? null : linha.trim();
            }
        } catch (java.io.IOException ignore) {}
        return null;
    }

    /** Proxy lido de HTTPS_PROXY / HTTP_PROXY (aceita userinfo url-encoded, ex. %23 = #). */
    static final class ProxyEnv {
        final String host; final int port; final String user; final String pass;
        private ProxyEnv(String host, int port, String user, String pass) {
            this.host = host; this.port = port; this.user = user; this.pass = pass;
        }
        static ProxyEnv doAmbiente() {
            String raw = null;
            for (String k : new String[]{"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"}) {
                String v = System.getenv(k);
                if (v != null && !v.isBlank()) { raw = v.trim(); break; }
            }
            if (raw == null) return null;
            try {
                URI u = URI.create(raw.contains("://") ? raw : "http://" + raw);
                String user = null, pass = null;
                if (u.getRawUserInfo() != null) {
                    String[] up = u.getRawUserInfo().split(":", 2);
                    user = URLDecoder.decode(up[0], StandardCharsets.UTF_8);
                    pass = up.length > 1 ? URLDecoder.decode(up[1], StandardCharsets.UTF_8) : "";
                }
                int port = u.getPort() > 0 ? u.getPort() : 3128;
                return new ProxyEnv(u.getHost(), port, user, pass);
            } catch (RuntimeException e) {
                System.out.println("  (não entendi a env de proxy '" + raw + "': " + e.getMessage() + ")");
                return null;
            }
        }
    }

    /** proxy: null = conexão direta. */
    private static boolean respondeHttp(String url, ProxyEnv proxy) {
        try {
            HttpClient.Builder b = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .followRedirects(HttpClient.Redirect.NORMAL);
            if (proxy != null) {
                b.proxy(ProxySelector.of(new InetSocketAddress(proxy.host, proxy.port)));
                if (proxy.user != null) {
                    b.authenticator(new java.net.Authenticator() {
                        @Override protected java.net.PasswordAuthentication getPasswordAuthentication() {
                            return new java.net.PasswordAuthentication(proxy.user, proxy.pass.toCharArray());
                        }
                    });
                }
            }
            HttpResponse<Void> r = b.build().send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                            .GET().build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private static void ajuda() {
        System.out.println("""
            Robô navegador SAUR — navega o app e reporta o que está quebrado.

              mvn -q compile exec:java
              mvn -q compile exec:java -Dexec.args="--base-url http://localhost:3000 --headed --max 80"

            Opções:
              --config <arquivo>   caminho do robo.config (padrão: ./robo.config)
              --base-url <url>      sobrepõe a base-url
              --headed | --headless abre/esconde a janela do navegador
              --max <n>            máx de páginas por perfil
              -h, --help          esta ajuda

            Credenciais e limites: robo.config (veja robo.config.example).
            """);
    }

    private Robo() {}
}

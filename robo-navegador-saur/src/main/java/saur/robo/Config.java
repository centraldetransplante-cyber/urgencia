package saur.robo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuração do robô, lida de um {@code robo.config} no formato
 * {@code chave=valor} (linhas começando com {@code #} são ignoradas).
 * Valores aceitam {@code ${VAR}} — expandido a partir de variável de ambiente
 * (útil para senha de produção sem deixá-la em texto no disco).
 */
public final class Config {

    /** Um login a ser testado: perfil é só rótulo, rota-inicial é de onde o crawl começa. */
    public record Credencial(String perfil, String usuario, String senha, String rotaInicial) {}

    public final String baseUrl;
    public final boolean permitirRemoto;
    public final boolean entendoRiscos;
    /** "local" (default) ou "seguro-remoto" — auto-selecionado quando a base não é localhost. */
    public final String modo;
    public final boolean headless;
    /** "" = tenta o Chromium do Playwright; senão "chrome" / "msedge" (navegador do sistema, sem download). */
    public final String canal;
    public final int maxPaginasPorPerfil;
    public final int profundidadeMax;
    public final int timeoutMs;
    public final int slowMoMs;
    public final int pausaEntrePaginasMs;
    /** Tirar screenshot das páginas com achado. Em alvo remoto vem FALSE por padrão (dados de paciente no disco). */
    public final boolean screenshots;
    public final boolean liveScreenshot;
    /** Clicar em dropdown/aba/collapse pra ver se respondem. Em alvo remoto vem FALSE (só navega). */
    public final boolean probesInterativas;
    public final List<String> denylistUrl;
    public final List<Credencial> credenciais;
    public final String campoUsuario;
    public final String campoSenha;
    public final String seletorSubmitLogin;
    public final Path saida;

    // --- aprimoramentos ---
    /** rotas absolutas extras a semear no crawl (não dependem de link em /). */
    public final List<String> rotasExtra;
    /** quantas telas de detalhe visitar por lista (IDs extraídos de /processos, /membros, ...). */
    public final int deepLinksPorLista;
    /** páginas processadas em paralelo. Em remoto = 1 (respeita o throttle). */
    public final int paralelismo;
    /** teto de tempo total da varredura, em minutos (0 = sem teto). */
    public final int tempoMaxMin;
    /** request acima disso vira achado "lento". */
    public final int reqLentoMs;
    /** load da página acima disso vira achado "perf". */
    public final int perfLimiteMs;
    /** roda cada página 2x pra separar achado intermitente (só local). */
    public final boolean detectarFlaky;
    /** regex opcional: só visita URLs que casam (null = todas). */
    public final Pattern soRegex;
    /** regressão visual: compara screenshot de cada página com um baseline salvo. */
    public final boolean regressaoVisual;
    public final double visualLimitePct;
    public final Path baselineDir;

    /** cópia dos pares crus lidos do arquivo, pra {@link #comOverride}. */
    private final Map<String, String> cru;

    private Config(Map<String, String> p) {
        this.cru = new LinkedHashMap<>(p);
        this.baseUrl = valor(p, "base-url", "http://localhost:3000").replaceAll("/+$", "");
        boolean baseLocal = hostEhLocal(this.baseUrl);
        String modoPadrao = baseLocal ? "local" : "seguro-remoto";
        this.modo = valor(p, "modo", modoPadrao).trim();
        boolean remoto = !this.modo.equals("local") || !baseLocal;

        this.permitirRemoto = Boolean.parseBoolean(valor(p, "permitir-remoto", "false"));
        this.entendoRiscos = Boolean.parseBoolean(valor(p, "eu-entendo-os-riscos", "false"));
        this.headless = Boolean.parseBoolean(valor(p, "headless", "true"));
        this.canal = valor(p, "canal", "").trim();

        this.maxPaginasPorPerfil = Integer.parseInt(valor(p, "max-paginas-por-perfil", remoto ? "60" : "150"));
        this.profundidadeMax = Integer.parseInt(valor(p, "profundidade-max", remoto ? "4" : "6"));
        this.timeoutMs = Integer.parseInt(valor(p, "timeout-ms", "15000"));
        this.slowMoMs = Integer.parseInt(valor(p, "slowmo-ms", "0"));
        this.pausaEntrePaginasMs = Integer.parseInt(valor(p, "pausa-entre-paginas-ms", remoto ? "700" : "0"));
        this.screenshots = Boolean.parseBoolean(valor(p, "screenshots", remoto ? "false" : "true"));
        this.liveScreenshot = Boolean.parseBoolean(valor(p, "live-screenshot", "false"));
        this.probesInterativas = Boolean.parseBoolean(valor(p, "probes-interativas", remoto ? "false" : "true"));

        this.campoUsuario = valor(p, "campo-usuario", "input[name=username]");
        this.campoSenha = valor(p, "campo-senha", "input[name=password]");
        this.seletorSubmitLogin = valor(p, "seletor-submit-login", "button[type=submit], input[type=submit]");
        this.saida = Path.of(valor(p, "saida", "report"));

        this.denylistUrl = new ArrayList<>(List.of(
                // ações que mudam estado / disparam e-mail — NUNCA seguir
                "/logout", "/login?", "/excluir", "/alternar-ativo", "/reabrir",
                "/decidir", "/cancelar", "/votar", "/retomar-analise", "/lembrete",
                "/comprovante-snt", "/documento-clinico", "/informacao-complementar",
                "/registrar-envio", "/finalizar", "/enviar", "/anexos", "/h2-console",
                "/marcar", "/nao-lidas", "/ajax",
                // endpoints de download (não são páginas; navegar neles = "Download is starting")
                "/exportar", "/export", "/baixar", "/download", "/gerar-pdf",
                "/oficio", "/dossie", "/relatorio-final", "/capa-processo", "/rascunho-rtf"));
        String extra = valor(p, "denylist-url-extra", "");
        if (!extra.isBlank()) for (String s : extra.split(",")) if (!s.isBlank()) denylistUrl.add(s.trim());

        this.rotasExtra = new ArrayList<>();
        String re = valor(p, "rotas-extra", "");
        if (!re.isBlank()) for (String s : re.split(",")) if (!s.isBlank()) rotasExtra.add(s.trim());
        this.deepLinksPorLista = Integer.parseInt(valor(p, "deep-links-por-lista", remoto ? "2" : "3"));
        this.paralelismo = Math.max(1, remoto ? 1 : Integer.parseInt(valor(p, "paralelismo", "3")));
        this.tempoMaxMin = Integer.parseInt(valor(p, "tempo-max-min", remoto ? "20" : "0"));
        this.reqLentoMs = Integer.parseInt(valor(p, "req-lento-ms", "3000"));
        this.perfLimiteMs = Integer.parseInt(valor(p, "perf-limite-ms", "5000"));
        this.detectarFlaky = Boolean.parseBoolean(valor(p, "detectar-flaky", baseLocal ? "true" : "false"));
        String only = valor(p, "so-regex", "");
        this.soRegex = only.isBlank() ? null : Pattern.compile(only);
        this.regressaoVisual = Boolean.parseBoolean(valor(p, "regressao-visual", "false"));
        this.visualLimitePct = Double.parseDouble(valor(p, "visual-limite-pct", "1.0"));
        this.baselineDir = Path.of(valor(p, "baseline-dir", "baseline"));

        this.credenciais = new ArrayList<>();
        for (int n = 1; n <= 20; n++) {
            String u = valor(p, "credencial." + n + ".usuario", "");
            if (u.isBlank()) continue;
            credenciais.add(new Credencial(
                    valor(p, "credencial." + n + ".perfil", "perfil" + n),
                    u.trim(),
                    valor(p, "credencial." + n + ".senha", ""),
                    valor(p, "credencial." + n + ".rota-inicial", "/")));
        }
        if (credenciais.isEmpty()) {
            // só o ADMIN semeado automaticamente pelo SAUR em dev
            credenciais.add(new Credencial("ADMIN", "admin", "Admin123!", "/"));
        }
    }

    public static Config carregar(Path arquivo) {
        Map<String, String> p = new LinkedHashMap<>();
        if (arquivo != null && !Files.isRegularFile(arquivo)) {
            System.out.println("  ⚠ config '" + arquivo + "' (abs: " + arquivo.toAbsolutePath()
                    + ") NÃO existe — usando defaults embutidos (admin/Admin123!, alvo local). "
                    + "Caminho tipo /tmp/... vira C:\\tmp\\... no Windows; use caminho relativo ou absoluto real.");
        }
        if (arquivo != null && Files.isRegularFile(arquivo)) {
            try {
                for (String linha : Files.readAllLines(arquivo)) {
                    String l = linha.strip();
                    if (l.isEmpty() || l.startsWith("#")) continue;
                    int eq = l.indexOf('=');
                    if (eq <= 0) continue;
                    p.put(l.substring(0, eq).strip(), l.substring(eq + 1).strip());
                }
            } catch (IOException e) {
                throw new RuntimeException("Não consegui ler " + arquivo + ": " + e.getMessage(), e);
            }
        }
        return new Config(p);
    }

    /** Nova Config com uma chave sobreposta (CLI). Re-deriva TODOS os defaults
     *  (inclusive os que dependem de alvo local x remoto). */
    public Config comOverride(String chave, String valor) {
        Map<String, String> m = new LinkedHashMap<>(cru);
        // se o override FAZ o alvo mudar de local<->remoto, deixa os defaults
        // sensíveis a alvo (modo/screenshots/probes/pausa/limites) re-derivarem
        if ("base-url".equals(chave)
                && hostEhLocal(valor.replaceAll("/+$", "")) != hostEhLocal(this.baseUrl)) {
            m.remove("modo");
            m.remove("screenshots");
            m.remove("probes-interativas");
            m.remove("pausa-entre-paginas-ms");
            m.remove("max-paginas-por-perfil");
            m.remove("profundidade-max");
        }
        m.put(chave, valor);
        return new Config(m);
    }

    public boolean baseEhLocal() {
        return hostEhLocal(baseUrl);
    }

    private static boolean hostEhLocal(String url) {
        String h = url.replaceFirst("^https?://", "").split("[:/]")[0].toLowerCase();
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("0.0.0.0");
    }

    // ${VAR} -> variável de ambiente, senão robo.env, senão o default.
    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** robo.env (KEY=VALUE por linha, gitignored) no diretório de trabalho — carregado uma vez. */
    private static final Map<String, String> ROBO_ENV = carregarRoboEnv();

    private static Map<String, String> carregarRoboEnv() {
        Map<String, String> m = new LinkedHashMap<>();
        Path f = Path.of("robo.env");
        if (!Files.isRegularFile(f)) return m;
        try {
            for (String linha : Files.readAllLines(f)) {
                String l = linha.strip();
                if (l.isEmpty() || l.startsWith("#")) continue;
                int eq = l.indexOf('=');
                if (eq <= 0) continue;
                m.put(l.substring(0, eq).strip(), l.substring(eq + 1).strip());
            }
            System.out.println("  robo.env carregado (" + m.size() + " variável(is)).");
        } catch (IOException e) {
            System.out.println("  ⚠ não consegui ler robo.env: " + e.getMessage());
        }
        return m;
    }

    private static String valor(Map<String, String> p, String chave, String padrao) {
        String v = p.getOrDefault(chave, padrao);
        if (v == null) return null;
        Matcher m = ENV.matcher(v);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String nome = m.group(1);
            String env = System.getenv(nome);
            if (env == null || env.isBlank()) env = ROBO_ENV.get(nome);
            if (env == null || env.isBlank()) {
                System.out.println("  ⚠ config " + chave + ": ${" + nome + "} não veio de variável de ambiente"
                        + " nem do robo.env — vai virar string vazia. Ponha  " + nome + "=...  no robo.env"
                        + " (copie de robo.env.example).");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(env == null ? "" : env));
        }
        m.appendTail(sb);
        String out = sb.toString();
        if (out.contains("${")) {
            System.out.println("  ⚠ config " + chave + " = '" + out + "': isso NÃO é interpolado. "
                    + "${NOME} só funciona com NOME = letras/dígitos/_ e serve pra LER variável de ambiente. "
                    + "Para um valor literal, escreva sem ${ }.");
        }
        return out;
    }
}

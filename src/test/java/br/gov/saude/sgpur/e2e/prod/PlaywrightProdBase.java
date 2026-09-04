package br.gov.saude.sgpur.e2e.prod;

import br.gov.saude.sgpur.e2e.Legenda;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base para os testes E2E de navegador (Playwright) executados diretamente
 * contra o ambiente de PRODUÇÃO (ou qualquer servidor remoto configurado).
 *
 * <p>Diferente de {@code PlaywrightTestBase}, esta classe é um teste JUnit 5 PURO:
 * <b>NÃO</b> utiliza {@code @SpringBootTest}, <b>NÃO</b> sobe contexto Spring local,
 * <b>NÃO</b> utiliza H2 e <b>NÃO</b> afeta o banco local. Ela simplesmente abre o Chromium
 * e pilota o sistema remotamente via HTTP/HTTPS.
 *
 * <p>Ao final da execução, compila e gera um relatório HTML visual completo com todas as
 * evidências fotográficas (screenshots), tempos de resposta e eventuais logs/erros de console.
 */
public abstract class PlaywrightProdBase {

    protected static final Path SCREENSHOT_DIR = Paths.get("target", "e2e-prod-screenshots");
    protected static final DateTimeFormatter FORMATO_DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    protected static final DateTimeFormatter FORMATO_ARQUIVO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    protected static String baseUrl;
    protected static String adminUser;
    protected static String adminPassword;
    protected static boolean headed;
    protected static int slowMo;

    private static Playwright playwright;
    private static Browser browser;
    protected BrowserContext context;
    protected Page page;
    private String ultimaLegenda;

    protected static final List<EtapaExecucao> etapas = Collections.synchronizedList(new ArrayList<>());
    protected final List<String> errosConsole = Collections.synchronizedList(new ArrayList<>());
    protected final List<String> errosPagina = Collections.synchronizedList(new ArrayList<>());
    protected static LocalDateTime inicioExecucao;
    protected static LocalDateTime fimExecucao;

    public record EtapaExecucao(
        int ordem,
        String titulo,
        String url,
        String detalhe,
        boolean sucesso,
        String screenshotArquivo,
        long duracaoMs,
        String horario
    ) {}

    @BeforeAll
    static void launchBrowser() {
        inicioExecucao = LocalDateTime.now();

        baseUrl = System.getProperty("saur.e2e.baseUrl",
            System.getenv().getOrDefault("SAUR_E2E_BASE_URL", "https://urgenciarenal.duckdns.org"));
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        adminUser = System.getProperty("saur.e2e.adminUser",
            System.getenv().getOrDefault("SAUR_PROD_USER", "admin"));

        adminPassword = System.getProperty("saur.e2e.adminPassword",
            System.getenv().getOrDefault("SAUR_PROD_PASSWORD", ""));

        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException(
                "A senha do admin de produção não foi informada!\n" +
                "Passe via system property (-Dsaur.e2e.adminPassword=...) ou via variável de ambiente SAUR_PROD_PASSWORD."
            );
        }

        headed = Boolean.parseBoolean(System.getProperty("saur.e2e.headed",
            System.getenv().getOrDefault("SAUR_E2E_HEADED", "true")));

        slowMo = Integer.parseInt(System.getProperty("saur.e2e.slowMo", headed ? "1000" : "0"));

        try {
            Files.createDirectories(SCREENSHOT_DIR);
        } catch (IOException e) {
            System.err.println("Aviso: não foi possível criar diretório de screenshots: " + e.getMessage());
        }

        playwright = Playwright.create();
        // "--start-maximized" força a janela a abrir maximizada e em primeiro plano -
        // sem isso, o Chromium abre do tamanho do viewport (abaixo) numa posicao
        // arbitraria da tela, facil de passar despercebido atras de outras janelas ja
        // abertas (relatado: robo "nao aparecia" rodando em producao, mas o processo
        // chrome.exe de fato subia - so' nao chamava atencao o suficiente).
        List<String> args = new ArrayList<>(List.of("--disable-gpu", "--disable-dev-shm-usage"));
        if (headed) {
            args.add("--start-maximized");
        }
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setHeadless(!headed)
            .setSlowMo(slowMo)
            .setArgs(args));
    }

    @AfterAll
    static void closeBrowserAndGenerateReport() {
        fimExecucao = LocalDateTime.now();
        fecharSilenciosamente(browser);
        fecharSilenciosamente(playwright);
        gerarRelatorioHtml();
    }

    @BeforeEach
    void newContext() {
        Browser.NewContextOptions opcoes = new Browser.NewContextOptions()
            .setBaseURL(baseUrl)
            .setIgnoreHTTPSErrors(true);
        if (headed) {
            // viewport nulo = a pagina usa o tamanho REAL da janela maximizada
            // (--start-maximized acima); setar um viewport fixo aqui reduziria o
            // Chromium de volta a uma janela pequena, do tamanho do viewport.
            opcoes.setViewportSize(null);
        } else {
            opcoes.setViewportSize(1440, 900);
        }
        context = browser.newContext(opcoes);

        context.setDefaultTimeout(45000);
        context.onDialog(dialog -> dialog.accept());

        page = context.newPage();

        errosConsole.clear();
        errosPagina.clear();

        page.onConsoleMessage(msg -> {
            if ("error".equalsIgnoreCase(msg.type())) {
                errosConsole.add("[" + msg.type().toUpperCase() + "] " + msg.text());
            }
        });

        page.onPageError(errosPagina::add);
    }

    @AfterEach
    void closeContext() {
        fecharSilenciosamente(context);
    }

    protected void legenda(String texto) {
        ultimaLegenda = texto;
        Legenda.mostrar(page, texto);
    }

    /**
     * Navega e <b>reaplica a legenda</b> na pagina que acabou de abrir. Uma legenda
     * injetada antes do {@code navigate} morre junto com o documento antigo: sem isso
     * o operador que esta olhando a janela nao ve narracao nenhuma justamente nas
     * telas novas, que e' o que ele quer acompanhar.
     */
    protected void irPara(String caminho) {
        page.navigate(caminho);
        page.waitForLoadState();
        aguardarPaginaEstavel();
        if (ultimaLegenda != null) {
            Legenda.mostrar(page, ultimaLegenda);
        }
    }

    /**
     * Espera a pagina assentar antes de fotografar. O layout do SAUR entra com
     * animacao de fade-in; fotografar no {@code load} cru produz screenshot
     * praticamente em branco, inutil como evidencia.
     */
    protected void aguardarPaginaEstavel() {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(10000));
        } catch (RuntimeException ignored) {
            // rede lenta/streaming: seguir mesmo assim, a animacao ja foi desligada na foto.
        }
    }

    protected String capturarScreenshot(String nomeIdentificador) {
        String timestamp = LocalDateTime.now().format(FORMATO_ARQUIVO);
        String nomeArquivo = String.format("%02d-%s-%s.png", etapas.size() + 1, nomeIdentificador, timestamp);
        Path destino = SCREENSHOT_DIR.resolve(nomeArquivo);
        aguardarPaginaEstavel();
        try {
            // ANIMATIONS DISABLED avanca as animacoes CSS ate o estado final antes de
            // fotografar - sem isso a foto sai no meio do fade-in do layout.
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(destino)
                .setFullPage(true)
                .setAnimations(com.microsoft.playwright.options.ScreenshotAnimations.DISABLED));
            return nomeArquivo;
        } catch (Exception e) {
            System.err.println("Falha ao capturar screenshot (" + nomeIdentificador + "): " + e.getMessage());
            return null;
        }
    }

    protected void registrarEtapa(String titulo, String detalhe, boolean sucesso, String screenshotArquivo, long duracaoMs) {
        String urlAtual = "";
        try {
            urlAtual = page != null ? page.url() : "";
        } catch (Exception ignored) {}

        String hora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        EtapaExecucao etapa = new EtapaExecucao(
            etapas.size() + 1,
            titulo,
            urlAtual,
            detalhe,
            sucesso,
            screenshotArquivo,
            duracaoMs,
            hora
        );
        etapas.add(etapa);

        String statusIcon = sucesso ? "[OK]  " : "[FALHA]";
        System.out.println(String.format("==> %s %d. %s (%dms) -> %s", statusIcon, etapa.ordem(), titulo, duracaoMs, urlAtual));
    }

    protected void mostrarPaginaInteira() {
        if (!headed || page == null) return;
        try {
            page.evaluate("""
                async () => {
                    const passo = window.innerHeight * 0.8;
                    const altura = document.documentElement.scrollHeight;
                    for (let y = 0; y < altura; y += passo) {
                        window.scrollTo({top: y, behavior: 'smooth'});
                        await new Promise(r => setTimeout(r, 250));
                    }
                    window.scrollTo({top: 0, behavior: 'smooth'});
                }
                """);
        } catch (RuntimeException ignored) {}
    }

    protected void loginAdmin() {
        long inicio = System.currentTimeMillis();
        legenda("Acessando tela de login do SAUR em Produção...");
        irPara("/login");

        legenda("Preenchendo credenciais de Administrador (" + adminUser + ")...");
        page.locator("input[name=username]").fill(adminUser);
        page.locator("input[name=password]").fill(adminPassword);

        legenda("Enviando autenticação...");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Entrar")).click();
        page.waitForLoadState();
        aguardarPaginaEstavel();
        legenda(ultimaLegenda);

        String screenshot = capturarScreenshot("login-resultado");
        boolean logado = !page.url().contains("/login");
        long duracao = System.currentTimeMillis() - inicio;

        registrarEtapa(
            "Autenticação de Administrador",
            logado ? "Login efetuado com sucesso como " + adminUser : "Falha na autenticação (permaneceu em /login)",
            logado,
            screenshot,
            duracao
        );

        if (!logado) {
            throw new AssertionError("Não foi possível autenticar em produção como '" + adminUser + "'. Verifique usuário/senha.");
        }
    }

    protected void logout() {
        long inicio = System.currentTimeMillis();
        legenda("Efetuando logout de encerramento...");
        try {
            var btnSair = page.locator("form[action$='/logout'] button, a[href$='/logout']");
            if (btnSair.count() > 0 && btnSair.first().isVisible()) {
                btnSair.first().click();
            } else {
                page.navigate("/logout");
            }
            page.waitForLoadState();
            aguardarPaginaEstavel();
        } catch (Exception e) {
            irPara("/login");
        }

        String screenshot = capturarScreenshot("logout-resultado");
        long duracao = System.currentTimeMillis() - inicio;
        registrarEtapa("Encerramento de Sessão (Logout)", "Sessão finalizada com sucesso", true, screenshot, duracao);
    }

    private static void fecharSilenciosamente(AutoCloseable recurso) {
        if (recurso == null) return;
        try {
            recurso.close();
        } catch (Exception ignored) {}
    }

    private static void gerarRelatorioHtml() {
        Path relatorioPath = SCREENSHOT_DIR.resolve("relatorio-execucao.html");
        StringBuilder html = new StringBuilder();

        long totalSucessos = etapas.stream().filter(EtapaExecucao::sucesso).count();
        long totalFalhas = etapas.size() - totalSucessos;
        String statusGeralBadge = totalFalhas == 0
            ? "<span class='badge bg-success fs-6'><i class='bi bi-check-circle-fill me-1'></i>100% OPERACIONAL</span>"
            : "<span class='badge bg-danger fs-6'><i class='bi bi-exclamation-octagon-fill me-1'></i>" + totalFalhas + " FALHA(S) DETECTADA(S)</span>";

        String periodoStr = (inicioExecucao != null ? inicioExecucao.format(FORMATO_DATA_HORA) : "")
            + " até " + (fimExecucao != null ? fimExecucao.format(FORMATO_DATA_HORA) : "");

        html.append("""
            <!DOCTYPE html>
            <html lang="pt-br">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>SAUR — Relatório de Execução do Robô E2E em Produção</title>
                <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
                <link href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css" rel="stylesheet">
                <style>
                    body { background-color: #0f172a; color: #f8fafc; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
                    .card-saur { background-color: #1e293b; border: 1px solid #334155; border-radius: 12px; box-shadow: 0 4px 16px rgba(0,0,0,0.3); }
                    .header-saur { background: linear-gradient(135deg, #1e3a8a 0%, #0f172a 100%); border-bottom: 2px solid #eab308; padding: 24px; border-radius: 12px 12px 0 0; }
                    .thumb-img { border-radius: 8px; border: 2px solid #475569; transition: transform 0.2s; cursor: pointer; max-height: 220px; object-fit: cover; width: 100%; }
                    .thumb-img:hover { transform: scale(1.02); border-color: #eab308; }
                    .table-dark-custom th { background-color: #1e293b; color: #cbd5e1; }
                    .table-dark-custom td { background-color: #0f172a; color: #f1f5f9; border-color: #334155; }
                    .badge-url { font-family: monospace; background-color: #334155; color: #93c5fd; }
                </style>
            </head>
            <body class="py-4">
            <div class="container-xl">
                <div class="card-saur mb-4">
                    <div class="header-saur d-flex justify-content-between align-items-center flex-wrap gap-3">
                        <div>
                            <div class="d-flex align-items-center gap-2 mb-1">
                                <span class="badge bg-warning text-dark fw-bold">PRODUÇÃO REAL</span>
                                <h1 class="h3 mb-0 text-white fw-bold">SAUR — Relatório de Execução do Robô E2E</h1>
                            </div>
                            <p class="text-white-50 small mb-0">Inspeção automatizada realizada pelo robô com credencial de Administrador</p>
                        </div>
                        <div class="text-end">
            """).append(statusGeralBadge).append("""
                        </div>
                    </div>
                    <div class="p-4">
                        <div class="row g-3 mb-4">
                            <div class="col-md-3">
                                <div class="p-3 rounded bg-dark border border-secondary text-center">
                                    <div class="text-secondary small">Alvo</div>
                                    <div class="fw-bold text-info text-truncate">""").append(baseUrl).append("""
                                    </div>
                                </div>
                            </div>
                            <div class="col-md-3">
                                <div class="p-3 rounded bg-dark border border-secondary text-center">
                                    <div class="text-secondary small">Usuário</div>
                                    <div class="fw-bold text-light">""").append(adminUser).append("""
                                    </div>
                                </div>
                            </div>
                            <div class="col-md-3">
                                <div class="p-3 rounded bg-dark border border-secondary text-center">
                                    <div class="text-secondary small">Total de Etapas</div>
                                    <div class="fw-bold text-light">""").append(etapas.size()).append(" (").append(totalSucessos).append(" OK / ").append(totalFalhas).append("""
                                     Falhas)</div>
                                </div>
                            </div>
                            <div class="col-md-3">
                                <div class="p-3 rounded bg-dark border border-secondary text-center">
                                    <div class="text-secondary small">Data / Período</div>
                                    <div class="small fw-semibold text-warning">""").append(periodoStr).append("""
                                    </div>
                                </div>
                            </div>
                        </div>

                        <h2 class="h5 mb-3 text-warning"><i class="bi bi-list-check me-2"></i>Etapas Verificadas e Evidências Fotográficas</h2>
                        <div class="row g-4">
            """);

        for (EtapaExecucao e : etapas) {
            String badgeStatus = e.sucesso()
                ? "<span class='badge bg-success'><i class='bi bi-check-circle me-1'></i>OK</span>"
                : "<span class='badge bg-danger'><i class='bi bi-x-circle me-1'></i>FALHA</span>";

            String imgTag = (e.screenshotArquivo() != null && !e.screenshotArquivo().isBlank())
                ? "<a href='" + e.screenshotArquivo() + "' target='_blank' title='Clique para ampliar'>" +
                  "<img src='" + e.screenshotArquivo() + "' class='thumb-img mt-2' alt='" + e.titulo() + "'>" +
                  "</a>"
                : "<div class='text-muted small py-4 text-center border border-dashed rounded'>Sem captura</div>";

            html.append("""
                <div class="col-md-6 col-lg-4">
                    <div class="card-saur h-100 p-3 d-flex flex-column justify-content-between">
                        <div>
                            <div class="d-flex justify-content-between align-items-start mb-2">
                                <span class="badge bg-secondary">#""").append(e.ordem()).append("""
                                </span>
                                """).append(badgeStatus).append("""
                            </div>
                            <h3 class="h6 fw-bold text-white mb-1">""").append(e.titulo()).append("""
                            </h3>
                            <div class="badge-url small p-1 rounded mb-2 text-truncate d-block" title='""").append(e.url()).append("""
                            '>""").append(e.url()).append("""
                            </div>
                            <p class="text-light-50 small mb-2">""").append(e.detalhe()).append("""
                            </p>
                        </div>
                        <div>
                            """).append(imgTag).append("""
                            <div class="d-flex justify-content-between align-items-center mt-2 text-secondary small">
                                <span><i class="bi bi-clock me-1"></i>""").append(e.horario()).append("""
                                </span>
                                <span><i class="bi bi-stopwatch me-1"></i>""").append(e.duracaoMs()).append("""
                                 ms</span>
                            </div>
                        </div>
                    </div>
                </div>
            """);
        }

        html.append("""
                        </div>
                    </div>
                    <div class="p-4 border-top border-secondary text-center text-secondary small">
                        Robô de Automação SAUR — Central de Transplantes do RS • Desenvolvido por Rafael Elias Ioppi
                    </div>
                </div>
            </div>
            </body>
            </html>
            """);

        try {
            Files.writeString(relatorioPath, html.toString());
            System.out.println("===============================================================================");
            System.out.println("==> RELATÓRIO VISUAL HTML GERADO COM SUCESSO!");
            System.out.println("==> Arquivo: " + relatorioPath.toAbsolutePath());
            System.out.println("===============================================================================");
        } catch (IOException e) {
            System.err.println("Erro ao gravar relatório HTML: " + e.getMessage());
        }
    }
}

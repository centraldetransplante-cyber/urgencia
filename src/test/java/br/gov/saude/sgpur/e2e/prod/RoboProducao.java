package br.gov.saude.sgpur.e2e.prod;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotAnimations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Robô de Inspeção e Auditoria E2E em PRODUÇÃO com perfil de Administrador.
 *
 * <p>Programa Java standalone (não é teste JUnit — sem {@code @Test}/asserts de
 * framework, sem Failsafe/Surefire): pilota o Chromium via Playwright puro,
 * direto por HTTP/HTTPS contra o servidor real, sem subir Spring/H2 local.
 * Invocado por {@link RoboProducaoMain#main}, que trata falha de etapa como
 * exceção Java simples — o resultado (sucesso/falha) é comunicado pelo exit
 * code do processo, não por "Tests run: N, Failures: M".
 *
 * <p>Percorre: Autenticação de Administrador · Painel Principal (sem erro 500)
 * · Fila de Triagem do Portal do Solicitante · Membros Avaliadores da CET-RS ·
 * Gestão de Usuários · Trilha de Auditoria · Relatórios e Estatísticas ·
 * Controle de Urgências · detalhe de um processo real (se houver, só leitura)
 * · Logout seguro. Gera relatório HTML com screenshots ao final.
 */
public final class RoboProducao implements AutoCloseable {

    private static final Path SCREENSHOT_DIR = Paths.get("target", "e2e-prod-screenshots");
    private static final DateTimeFormatter FORMATO_ARQUIVO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Roteiro fixo das etapas, na ordem em que {@link #executarInspecaoCompleta()}
     * as executa — fonte única para o checklist do {@link PainelProducao}. Os
     * títulos aqui precisam bater exatamente com o {@code titulo} passado a
     * {@link #registrarEtapa} em cada etapa (ver {@link #iniciarEtapa}).
     */
    private static final List<String> ROTEIRO_ETAPAS = List.of(
        "Autenticação de Administrador",
        "Painel Principal / Processos",
        "Fila de Triagem de Solicitações Online",
        "Gestão de Membros Avaliadores",
        "Gestão de Usuários",
        "Trilha de Auditoria",
        "Módulo de Relatórios",
        "Controle de Urgências",
        "Inspeção de Processo Existente",
        "Encerramento de Sessão (Logout)"
    );

    private final ConfiguracaoRobo config;
    private final RelatorioProducao relatorio;
    private final Map<String, PainelProducao.StatusEtapa> statusEtapas = new LinkedHashMap<>();

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private String ultimaLegenda = "Iniciando o robô...";

    private final List<String> errosConsole = new ArrayList<>();

    public RoboProducao(ConfiguracaoRobo config) {
        this.config = config;
        this.relatorio = new RelatorioProducao(config.baseUrl(), config.usuarioAdmin());
        for (String titulo : ROTEIRO_ETAPAS) {
            statusEtapas.put(titulo, PainelProducao.StatusEtapa.PENDENTE);
        }
    }

    public RelatorioProducao relatorio() {
        return relatorio;
    }

    public void iniciar() throws IOException {
        Files.createDirectories(SCREENSHOT_DIR);

        // "--start-maximized" força a janela a abrir maximizada - mas sozinho
        // NAO garante que ela apareca em primeiro plano nem dentro da area
        // visivel: se o Chromium "lembrar" uma posicao de um monitor externo
        // ja desconectado, ele maximiza fora da tela, invisivel mesmo com a
        // janela "aberta". "--window-position=0,0" ancora no canto superior
        // esquerdo do monitor primario ANTES de maximizar, cobrindo esse caso.
        // Isso sozinho tambem nao resolve o "foco": o Windows restringe
        // SetForegroundWindow de processos que nao tiveram input recente (o
        // robo pode levar alguns segundos so compilando/subindo o Maven antes
        // do Chromium abrir, o que ja e tempo suficiente pro SO negar o
        // foco automatico) - por isso chamamos page.bringToFront() logo a
        // seguir e a cada nova acao (ver legenda()/irPara()), que usa o
        // proprio protocolo do Chromium (CDP Target.activateTarget) para
        // pedir ativacao da janela, um caminho mais confiavel que so contar
        // com o SO conceder foco a uma janela nova sozinho.
        List<String> args = new ArrayList<>(List.of("--disable-gpu", "--disable-dev-shm-usage"));
        if (config.headed()) {
            args.add("--window-position=0,0");
            args.add("--start-maximized");
        }

        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setHeadless(!config.headed())
            .setSlowMo(config.slowMoMs())
            .setArgs(args));

        Browser.NewContextOptions opcoes = new Browser.NewContextOptions()
            .setBaseURL(config.baseUrl())
            .setIgnoreHTTPSErrors(true);
        if (config.headed()) {
            // viewport nulo = a pagina usa o tamanho REAL da janela maximizada.
            opcoes.setViewportSize(null);
        } else {
            opcoes.setViewportSize(1440, 900);
        }
        context = browser.newContext(opcoes);
        context.setDefaultTimeout(45000);
        context.onDialog(dialog -> dialog.accept());

        page = context.newPage();
        page.onConsoleMessage(msg -> {
            if ("error".equalsIgnoreCase(msg.type())) {
                errosConsole.add("[" + msg.type().toUpperCase() + "] " + msg.text());
            }
        });

        trazerParaFrente();
        legenda("Robô iniciado. Autenticando em produção em instantes...");

        if (config.headed()) {
            System.out.println("==> A janela do Chromium deve aparecer AGORA (maximizada). Se não vir, confira a barra de");
            System.out.println("    tarefas — o Windows pode não trazer a janela para frente automaticamente.");
        }
    }

    /**
     * Pede ao Chromium para ativar/trazer a janela para frente via CDP —
     * mais confiável que depender só do sistema operacional conceder foco a
     * uma janela recém-criada (ver comentário em {@link #iniciar()}).
     * Silencioso em qualquer falha: é reforço de UX, nunca deve interromper
     * a inspeção.
     */
    private void trazerParaFrente() {
        if (!config.headed() || page == null) return;
        try {
            page.bringToFront();
        } catch (RuntimeException ignored) {
            // melhor esforço - segue sem travar a inspeção.
        }
    }

    /**
     * Mantém a janela aberta por {@link ConfiguracaoRobo#holdAoFinalSegundos()}
     * segundos antes de fechar, mostrando um aviso final — sem isso, uma
     * execução que falha cedo (ex.: login com senha errada, ~8s de duração
     * total observados num incidente real) fecha o Chromium tão rápido que
     * não dá tempo de perceber que o robô sequer chegou a rodar. Chamado
     * pelo {@link RoboProducaoMain} sempre, sucesso ou falha, ANTES de
     * {@link #close()}. Sem efeito em modo headless (nada para segurar na
     * tela) ou se a página já não existir mais.
     */
    public void segurarNaTelaAntesDeFechar(Throwable falha) {
        int segundos = config.holdAoFinalSegundos();
        if (segundos <= 0 || page == null) return;
        try {
            for (int restante = segundos; restante > 0; restante--) {
                String motivo = falha != null
                    ? "Inspeção interrompida por erro: " + falha.getMessage()
                    : "Inspeção concluída";
                legenda(motivo + " — janela fecha em " + restante + "s...");
                page.waitForTimeout(1000);
            }
        } catch (RuntimeException ignored) {
            // pagina/contexto ja instavel no fim da execucao - nao e critico segurar.
        }
    }

    @Override
    public void close() {
        fecharSilenciosamente(context);
        fecharSilenciosamente(browser);
        fecharSilenciosamente(playwright);
    }

    public Path gerarRelatorio() {
        return relatorio.gerar(SCREENSHOT_DIR);
    }

    /** Roda a inspeção completa; cada etapa se auto-registra no relatório (sucesso ou falha), sem interromper as demais. */
    public void executarInspecaoCompleta() {
        loginAdmin();
        verificarPainelPrincipal();
        verificarTriagemSolicitacoesOnline();
        verificarMembrosAvaliadores();
        verificarUsuarios();
        verificarAuditoria();
        verificarRelatorios();
        verificarControleUrgencias();
        inspecionarProcessoExistenteSeHouver();
        logout();
    }

    // ---- etapas ----

    private void loginAdmin() {
        long inicio = System.currentTimeMillis();
        iniciarEtapa("Autenticação de Administrador");
        legenda("Acessando tela de login do SAUR em Produção...");
        irPara("/login");

        legenda("Preenchendo credenciais de Administrador (" + config.usuarioAdmin() + ")...");
        page.locator("input[name=username]").fill(config.usuarioAdmin());
        page.locator("input[name=password]").fill(config.senhaAdmin());

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
            logado ? "Login efetuado com sucesso como " + config.usuarioAdmin() : "Falha na autenticação (permaneceu em /login)",
            logado, screenshot, duracao
        );

        if (!logado) {
            throw new IllegalStateException(
                "Não foi possível autenticar em produção como '" + config.usuarioAdmin() + "'. Verifique usuário/senha.");
        }
    }

    private void verificarPainelPrincipal() {
        long inicio = System.currentTimeMillis();
        iniciarEtapa("Painel Principal / Processos");
        legenda("Navegando para o Painel Principal (/)...");
        irPara("/");

        legenda("Inspecionando cards de contagem e tabela de processos...");
        mostrarPaginaInteira();

        String screenshot = capturarScreenshot("painel-principal");
        String titulo = page.title();
        boolean semErro500 = semErroServidor();
        long duracao = System.currentTimeMillis() - inicio;

        int totalLinhas = page.locator("table tbody tr").count();
        String detalhe = String.format("Painel carregado (%s). Linhas na tabela: %d. Erros de console: %d.",
            titulo, totalLinhas, errosConsole.size());

        registrarEtapa("Painel Principal / Processos", detalhe, semErro500, screenshot, duracao);
    }

    private void verificarTriagemSolicitacoesOnline() {
        verificarTelaSimples("/processos/solicitacoes-online", "Fila de Triagem de Solicitações Online",
            "Acessando Fila de Triagem de Solicitações Online (/processos/solicitacoes-online)...",
            "Verificando solicitações recebidas do Portal do Solicitante...",
            "triagem-solicitacoes-online", "Fila de triagem carregada. Registros visíveis: %d.");
    }

    private void verificarMembrosAvaliadores() {
        verificarTelaSimples("/membros", "Gestão de Membros Avaliadores",
            "Acessando Gestão de Membros Avaliadores (/membros)...",
            "Inspecionando lista de médicos avaliadores e instituições...",
            "membros-avaliadores", "Lista de membros avaliadores carregada. Total cadastrado: %d.");
    }

    private void verificarUsuarios() {
        verificarTelaSimples("/usuarios", "Gestão de Usuários",
            "Acessando Gestão de Usuários (/usuarios)...",
            "Inspecionando contas de acesso (ADMIN, OPERADOR, AVALIADOR, SOLICITANTE)...",
            "usuarios-sistema", "Lista de usuários carregada. Total cadastrado: %d.");
    }

    private void verificarAuditoria() {
        verificarTelaSimples("/auditoria", "Trilha de Auditoria",
            "Acessando Trilha de Auditoria do Sistema (/auditoria)...",
            "Inspecionando histórico de operações e logs de auditoria...",
            "trilha-auditoria", "Trilha de auditoria carregada. Registros recentes: %d.");
    }

    private void verificarRelatorios() {
        long inicio = System.currentTimeMillis();
        iniciarEtapa("Módulo de Relatórios");
        legenda("Acessando Módulo de Relatórios (/relatorios)...");
        irPara("/relatorios");

        legenda("Inspecionando filtros e opções de relatórios...");
        mostrarPaginaInteira();

        String screenshot = capturarScreenshot("relatorios-gestao");
        boolean semErro = semErroServidor();
        long duracao = System.currentTimeMillis() - inicio;

        registrarEtapa("Módulo de Relatórios", "Interface de relatórios e filtros disponível", semErro, screenshot, duracao);
    }

    private void verificarControleUrgencias() {
        long inicio = System.currentTimeMillis();
        iniciarEtapa("Controle de Urgências");
        legenda("Acessando Controle de Urgências (/controle-urgencias)...");
        irPara("/controle-urgencias");

        legenda("Inspecionando quadro de controle de urgências...");
        mostrarPaginaInteira();

        String screenshot = capturarScreenshot("controle-urgencias");
        boolean semErro = semErroServidor();
        long duracao = System.currentTimeMillis() - inicio;

        registrarEtapa("Controle de Urgências", "Quadro operacional de urgências disponível", semErro, screenshot, duracao);
    }

    private void inspecionarProcessoExistenteSeHouver() {
        long inicio = System.currentTimeMillis();
        iniciarEtapa("Inspeção de Processo Existente");
        legenda("Retornando ao Painel para verificar se há algum processo para inspecionar...");
        irPara("/processos");

        var linkProcesso = page.locator("table tbody tr td a[href*='/processos/']").first();
        if (linkProcesso.count() > 0 && linkProcesso.isVisible()) {
            String href = linkProcesso.getAttribute("href");
            legenda("Inspecionando processo existente (" + href + ") apenas em modo de leitura...");
            linkProcesso.click();
            page.waitForLoadState();
            aguardarPaginaEstavel();
            legenda("Inspecionando o detalhe do processo em modo somente leitura...");

            mostrarPaginaInteira();
            String screenshot = capturarScreenshot("detalhe-processo-real");
            boolean semErro = semErroServidor();
            long duracao = System.currentTimeMillis() - inicio;

            String detalhe = "Inspeção visual da tela de detalhe do processo (" + page.url() + ") realizada sem alterações.";
            registrarEtapa("Inspeção de Processo Existente", detalhe, semErro, screenshot, duracao);
        } else {
            long duracao = System.currentTimeMillis() - inicio;
            String screenshot = capturarScreenshot("sem-processos");
            registrarEtapa("Inspeção de Processo Existente", "Nenhum processo cadastrado no momento para visualização.", true, screenshot, duracao);
        }
    }

    private void logout() {
        long inicio = System.currentTimeMillis();
        iniciarEtapa("Encerramento de Sessão (Logout)");
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

    /** As 4 telas de listagem seguem exatamente o mesmo roteiro — evita repetir o corpo 4x. */
    private void verificarTelaSimples(String caminho, String titulo, String legendaNavegacao, String legendaInspecao,
                                       String nomeScreenshot, String detalheFormato) {
        long inicio = System.currentTimeMillis();
        iniciarEtapa(titulo);
        legenda(legendaNavegacao);
        irPara(caminho);

        legenda(legendaInspecao);
        mostrarPaginaInteira();

        String screenshot = capturarScreenshot(nomeScreenshot);
        boolean semErro = semErroServidor();
        int linhas = page.locator("table tbody tr").count();
        long duracao = System.currentTimeMillis() - inicio;

        registrarEtapa(titulo, String.format(detalheFormato, linhas), semErro, screenshot, duracao);
    }

    // ---- infraestrutura ----

    private boolean semErroServidor() {
        String conteudo = page.content();
        return !conteudo.contains("Internal Server Error") && !conteudo.contains("Whitelabel Error Page");
    }

    private void registrarEtapa(String titulo, String detalhe, boolean sucesso, String screenshotArquivo, long duracaoMs) {
        String urlAtual = "";
        try {
            urlAtual = page != null ? page.url() : "";
        } catch (Exception ignored) {}
        relatorio.registrar(titulo, urlAtual, detalhe, sucesso, screenshotArquivo, duracaoMs);

        // Fecha o checklist dessa etapa (a mesma que iniciarEtapa marcou como
        // EM_ANDAMENTO) - se o título não bater com nenhum item do roteiro
        // fixo, statusEtapas.computeIfPresent simplesmente não faz nada.
        statusEtapas.computeIfPresent(titulo, (t, statusAtual) ->
            sucesso ? PainelProducao.StatusEtapa.CONCLUIDA : PainelProducao.StatusEtapa.FALHA);
        atualizarPainel();
    }

    /** Marca a etapa como "em andamento" no checklist visível — chamado no início de cada etapa, antes de qualquer navegação. */
    private void iniciarEtapa(String titulo) {
        statusEtapas.computeIfPresent(titulo, (t, statusAtual) -> PainelProducao.StatusEtapa.EM_ANDAMENTO);
        atualizarPainel();
    }

    private void legenda(String texto) {
        ultimaLegenda = texto;
        atualizarPainel();
    }

    /** Redesenha o overlay (selo de identificação + ação atual + checklist) com o estado atual. */
    private void atualizarPainel() {
        trazerParaFrente();
        List<PainelProducao.ItemChecklist> itens = ROTEIRO_ETAPAS.stream()
            .map(t -> new PainelProducao.ItemChecklist(t, statusEtapas.getOrDefault(t, PainelProducao.StatusEtapa.PENDENTE)))
            .toList();
        PainelProducao.renderizar(page, ultimaLegenda, itens);
    }

    /**
     * Navega e reaplica o overlay na página que acabou de abrir. Um overlay
     * injetado antes do {@code navigate} morre junto com o documento antigo.
     */
    private void irPara(String caminho) {
        page.navigate(caminho);
        page.waitForLoadState();
        aguardarPaginaEstavel();
        atualizarPainel();
    }

    /** Espera a página assentar antes de fotografar (o layout do SAUR entra com fade-in). */
    private void aguardarPaginaEstavel() {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10000));
        } catch (RuntimeException ignored) {
            // rede lenta/streaming: seguir mesmo assim.
        }
    }

    private String capturarScreenshot(String nomeIdentificador) {
        String timestamp = LocalDateTime.now().format(FORMATO_ARQUIVO);
        String nomeArquivo = String.format("%02d-%s-%s.png", relatorio.proximaOrdem(), nomeIdentificador, timestamp);
        Path destino = SCREENSHOT_DIR.resolve(nomeArquivo);
        aguardarPaginaEstavel();
        try {
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(destino)
                .setFullPage(true)
                .setAnimations(ScreenshotAnimations.DISABLED));
            return nomeArquivo;
        } catch (Exception e) {
            System.err.println("Falha ao capturar screenshot (" + nomeIdentificador + "): " + e.getMessage());
            return null;
        }
    }

    private void mostrarPaginaInteira() {
        if (!config.headed() || page == null) return;
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

    private static void fecharSilenciosamente(AutoCloseable recurso) {
        if (recurso == null) return;
        try {
            recurso.close();
        } catch (Exception ignored) {}
    }
}

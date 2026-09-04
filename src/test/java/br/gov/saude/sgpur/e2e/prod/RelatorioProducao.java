package br.gov.saude.sgpur.e2e.prod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Coleta as etapas executadas pelo {@link RoboProducao} e gera, ao final, um
 * relatório HTML visual com screenshots — separado do robô em si (única
 * responsabilidade: registrar e renderizar, não pilotar o navegador).
 */
public final class RelatorioProducao {

    private static final DateTimeFormatter FORMATO_DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final List<EtapaExecucao> etapas = Collections.synchronizedList(new ArrayList<>());
    private final LocalDateTime inicioExecucao = LocalDateTime.now();
    private LocalDateTime fimExecucao;
    private final String baseUrl;
    private final String adminUser;

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

    public RelatorioProducao(String baseUrl, String adminUser) {
        this.baseUrl = baseUrl;
        this.adminUser = adminUser;
    }

    public int proximaOrdem() {
        return etapas.size() + 1;
    }

    public void registrar(String titulo, String urlAtual, String detalhe, boolean sucesso, String screenshotArquivo, long duracaoMs) {
        String hora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        EtapaExecucao etapa = new EtapaExecucao(
            etapas.size() + 1, titulo, urlAtual, detalhe, sucesso, screenshotArquivo, duracaoMs, hora
        );
        etapas.add(etapa);

        String statusIcon = sucesso ? "[OK]  " : "[FALHA]";
        System.out.println(String.format("==> %s %d. %s (%dms) -> %s", statusIcon, etapa.ordem(), titulo, duracaoMs, urlAtual));
    }

    public boolean houveFalha() {
        return etapas.stream().anyMatch(e -> !e.sucesso());
    }

    public List<EtapaExecucao> etapas() {
        return List.copyOf(etapas);
    }

    /** Gera o relatório HTML e devolve o caminho absoluto do arquivo gerado. */
    public Path gerar(Path screenshotDir) {
        fimExecucao = LocalDateTime.now();
        Path relatorioPath = screenshotDir.resolve("relatorio-execucao.html");
        StringBuilder html = new StringBuilder();

        long totalSucessos = etapas.stream().filter(EtapaExecucao::sucesso).count();
        long totalFalhas = etapas.size() - totalSucessos;
        String statusGeralBadge = totalFalhas == 0
            ? "<span class='badge bg-success fs-6'><i class='bi bi-check-circle-fill me-1'></i>100% OPERACIONAL</span>"
            : "<span class='badge bg-danger fs-6'><i class='bi bi-exclamation-octagon-fill me-1'></i>" + totalFalhas + " FALHA(S) DETECTADA(S)</span>";

        String periodoStr = inicioExecucao.format(FORMATO_DATA_HORA) + " até " + fimExecucao.format(FORMATO_DATA_HORA);

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
                        Robô de Automação SAUR — Central de Transplantes do RS
                    </div>
                </div>
            </div>
            </body>
            </html>
            """);

        try {
            Files.createDirectories(screenshotDir);
            Files.writeString(relatorioPath, html.toString());
        } catch (IOException e) {
            System.err.println("Erro ao gravar relatório HTML: " + e.getMessage());
        }
        return relatorioPath.toAbsolutePath();
    }
}

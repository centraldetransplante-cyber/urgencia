package br.gov.saude.sgpur.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RoboNavegadorService {

    private final Path script;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "robo-navegador-saur");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean executando = new AtomicBoolean();
    private final AtomicReference<Process> processoAtual = new AtomicReference<>();
    private final AtomicBoolean paradaSolicitada = new AtomicBoolean();
    private volatile String status = "PARADO";
    private volatile Instant iniciadoEm;
    private volatile Instant finalizadoEm;
    private static final String MENSAGEM_INICIANDO = "Robo executando em modo producao com login ADMIN.";

    private volatile String mensagem = "Nenhuma execucao realizada.";

    /**
     * Teto rígido de execução: se o processo do robô travar (ex.: senha vazia caindo no
     * fallback de leitura de stdin em Robo.pedirSenha, ou qualquer outro bloqueio inesperado),
     * `waitFor` sem timeout deixava o status preso em "EXECUTANDO" para sempre (executor
     * single-thread, iniciar() sempre retorna false depois disso). O robô completo levou ~33s
     * num teste manual; 15 minutos dá folga generosa sem deixar travar indefinidamente.
     */
    private static final long TIMEOUT_MINUTOS = 15;

    public RoboNavegadorService(
            @Value("${app.robo.script:/opt/sgpur/robo-navegador-saur/run.sh}") String script) {
        this.script = Path.of(script).toAbsolutePath().normalize();
    }

    public boolean iniciar() {
        if (!Files.isRegularFile(script) || !Files.isExecutable(script)) {
            status = "ERRO";
            mensagem = "Script do robo nao encontrado ou sem permissao de execucao.";
            return false;
        }
        if (!executando.compareAndSet(false, true)) return false;
        status = "EXECUTANDO";
        iniciadoEm = Instant.now();
        finalizadoEm = null;
        mensagem = MENSAGEM_INICIANDO;
        executor.submit(this::executar);
        return true;
    }

    public boolean estaExecutando() {
        return executando.get();
    }

    /**
     * Interrompe a execucao em andamento, se houver. Mata o processo do robo
     * (destroyForcibly, mesmo tratamento ja usado no estouro de timeout) e sinaliza
     * `paradaSolicitada` para que `executar()` reporte "interrompido manualmente" em vez
     * do texto generico de "concluido com achados" que sairia so olhando o exit code.
     * Nunca deixa `executando` travado: quem zera esse estado continua sendo o `finally`
     * de `executar()`, disparado normalmente assim que `waitFor` retorna apos o kill.
     */
    public boolean parar() {
        if (!executando.get()) return false;
        Process processo = processoAtual.get();
        if (processo == null) return false;
        paradaSolicitada.set(true);
        processo.destroyForcibly();
        return true;
    }

    public Path getRelatorioHtml() {
        return script.getParent().resolve("report").resolve("index.html");
    }

    public String getStatus() {
        return status;
    }

    public Instant getIniciadoEm() {
        return iniciadoEm;
    }

    public Instant getFinalizadoEm() {
        return finalizadoEm;
    }

    public String getMensagem() {
        return mensagem;
    }

    public Path getLiveScreenshot() {
        // O robô roda com CWD = script.getParent() (ver ProcessBuilder abaixo) e grava o
        // screenshot em <saida>/live/latest.png, onde <saida> é a config "saida" do robô
        // (default "report", ver robo-navegador-saur/src/main/java/saur/robo/Config.java e
        // Rastreador.java: "dirScreenshots.resolveSibling(\"live\")", com dirScreenshots =
        // <saida>/screenshots). Sem o prefixo "report/" aqui, o arquivo nunca é encontrado
        // (404 permanente em /admin/robo/live.png, bug real relatado em produção).
        return script.getParent().resolve("report").resolve("live").resolve("latest.png");
    }

    private void executar() {
        int codigo = -1;
        try {
            if (!Files.isRegularFile(script) || !Files.isExecutable(script)) {
                throw new IOException("Script do robo nao encontrado ou sem permissao de execucao: " + script);
            }
            Process processo = new ProcessBuilder("/bin/bash", script.toString(), "--headless")
                    .directory(script.getParent().toFile())
                    .redirectErrorStream(true)
                    .start();
            processoAtual.set(processo);
            // Fecha o stdin do processo filho imediatamente: força EOF em qualquer leitura de
            // stdin dentro do robô (ex.: Robo.pedirSenha, acionado quando SAUR_PROD_ADMIN
            // resolve para string vazia/ausente) em vez de bloquear pra sempre esperando uma
            // entrada que nunca chega - sem isso o fallback de leitura de senha travava a
            // execução indefinidamente.
            try {
                processo.getOutputStream().close();
            } catch (IOException ignored) {
            }
            Thread leitura = Thread.startVirtualThread(() -> {
                try (var leitor = processo.inputReader()) {
                    leitor.lines().filter(linha -> !linha.isBlank()).forEach(linha -> {
                        mensagem = linha;
                    });
                } catch (IOException ignored) {
                }
            });
            boolean terminouNoPrazo = processo.waitFor(TIMEOUT_MINUTOS, TimeUnit.MINUTES);
            if (!terminouNoPrazo) {
                processo.destroyForcibly();
                leitura.join();
                status = "ERRO";
                mensagem = "Robo excedeu o tempo maximo de " + TIMEOUT_MINUTOS
                        + " minutos e foi encerrado a forca. Verifique a credencial (SAUR_PROD_ADMIN)"
                        + " e os logs no servidor.";
                System.err.println("Robo navegador SAUR excedeu " + TIMEOUT_MINUTOS
                        + " minutos e foi encerrado a forca.");
                return;
            }
            codigo = processo.exitValue();
            leitura.join();
            if (paradaSolicitada.get()) {
                status = "PARADO";
                mensagem = "Robo interrompido manualmente pelo administrador.";
                System.out.println("Robo navegador SAUR interrompido manualmente.");
                return;
            }
            // Antes de sobrescrever `mensagem` com o texto generico abaixo, guarda a
            // ultima linha real de stdout/stderr do processo (capturada pela thread de
            // leitura acima) - sem isso o motivo real de uma falha (ex. "Maven nao
            // encontrado.") ficava escondido do admin, so visivel investigando na mao
            // via SSH/journalctl, mesmo ja estando disponivel no proprio processo.
            String ultimaLinha = mensagem;
            boolean temSaidaReal = ultimaLinha != null && !ultimaLinha.equals(MENSAGEM_INICIANDO);
            if (codigo == 0) {
                status = "CONCLUIDO";
                mensagem = "Robo concluido sem achados altos.";
                System.out.println("Robo navegador SAUR concluido com sucesso.");
            } else {
                status = "CONCLUIDO_COM_ACHADOS";
                mensagem = "Robo concluido com codigo " + codigo
                        + (temSaidaReal ? ": " + ultimaLinha : "")
                        + ". Consulte o relatorio no servidor.";
                System.err.println("Robo navegador SAUR terminou com codigo " + codigo + ".");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "ERRO";
            mensagem = "Robo interrompido.";
            System.err.println("Robo navegador SAUR interrompido.");
        } catch (IOException e) {
            status = "ERRO";
            mensagem = "Nao foi possivel iniciar o robo.";
            System.err.println("Nao foi possivel iniciar o robo navegador SAUR: " + e.getMessage());
        } finally {
            finalizadoEm = Instant.now();
            processoAtual.set(null);
            paradaSolicitada.set(false);
            executando.set(false);
        }
    }

    @PreDestroy
    void encerrar() {
        executor.shutdownNow();
    }
}

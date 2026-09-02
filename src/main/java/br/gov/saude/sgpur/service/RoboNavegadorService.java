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
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RoboNavegadorService {

    private final Path script;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "robo-navegador-saur");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean executando = new AtomicBoolean();
    private volatile String status = "PARADO";
    private volatile Instant iniciadoEm;
    private volatile Instant finalizadoEm;
    private volatile String mensagem = "Nenhuma execucao realizada.";

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
        mensagem = "Robo executando em modo producao com login ADMIN.";
        executor.submit(this::executar);
        return true;
    }

    public boolean estaExecutando() {
        return executando.get();
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
        return script.getParent().resolve("live").resolve("latest.png");
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
            Thread leitura = Thread.startVirtualThread(() -> {
                try (var leitor = processo.inputReader()) {
                    leitor.lines().filter(linha -> !linha.isBlank()).forEach(linha -> {
                        mensagem = linha;
                    });
                } catch (IOException ignored) {
                }
            });
            codigo = processo.waitFor();
            leitura.join();
            if (codigo == 0) {
                status = "CONCLUIDO";
                mensagem = "Robo concluido sem achados altos.";
                System.out.println("Robo navegador SAUR concluido com sucesso.");
            } else {
                status = "CONCLUIDO_COM_ACHADOS";
                mensagem = "Robo concluido com codigo " + codigo + ". Consulte o relatorio no servidor.";
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
            executando.set(false);
        }
    }

    @PreDestroy
    void encerrar() {
        executor.shutdownNow();
    }
}

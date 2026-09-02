package br.gov.saude.sgpur.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public RoboNavegadorService(
            @Value("${app.robo.script:/opt/sgpur/robo-navegador-saur/run.sh}") String script) {
        this.script = Path.of(script).toAbsolutePath().normalize();
    }

    public boolean iniciar() {
        if (!executando.compareAndSet(false, true)) return false;
        executor.submit(this::executar);
        return true;
    }

    public boolean estaExecutando() {
        return executando.get();
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
                    .inheritIO()
                    .start();
            codigo = processo.waitFor();
            if (codigo == 0) {
                System.out.println("Robo navegador SAUR concluido com sucesso.");
            } else {
                System.err.println("Robo navegador SAUR terminou com codigo " + codigo + ".");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Robo navegador SAUR interrompido.");
        } catch (IOException e) {
            System.err.println("Nao foi possivel iniciar o robo navegador SAUR: " + e.getMessage());
        } finally {
            executando.set(false);
        }
    }

    @PreDestroy
    void encerrar() {
        executor.shutdownNow();
    }
}

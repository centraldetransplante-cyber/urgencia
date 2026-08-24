package br.gov.saude.sgpur.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cobre o fail-open por TIMEOUT do EmailDominioValidator (revisao adicional
 * do PR #120, achados 1 e 3): a consulta de dominio roda num executor
 * dedicado com teto rigido de tempo - qualquer timeout tem que devolver
 * {@code true} ("dominio ok"), nunca {@code false}, mesmo que a checagem de
 * verdade nunca tenha chegado a concluir.
 */
class EmailDominioValidatorTest {

    @Test
    void semArrobaOuVazioSempreDevolveTrueSemChamarOExecutor() {
        // Executor que falharia se qualquer tarefa fosse submetida a ele -
        // prova que os casos triviais (sem "@", nulo, vazio) nunca chegam a
        // consultar DNS nenhum.
        ExecutorService executorQueNuncaDeveSerUsado = new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public java.util.List<Runnable> shutdownNow() { return java.util.List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) {
                throw new AssertionError("Nao deveria consultar DNS para entrada trivial");
            }
        };
        EmailDominioValidator validator = new EmailDominioValidator(executorQueNuncaDeveSerUsado, 2000);

        assertThat(validator.dominioResolvivel(null)).isTrue();
        assertThat(validator.dominioResolvivel("")).isTrue();
        assertThat(validator.dominioResolvivel("   ")).isTrue();
        assertThat(validator.dominioResolvivel("sem-arroba")).isTrue();
        assertThat(validator.dominioResolvivel("termina-com-arroba@")).isTrue();
    }

    /**
     * TIMEOUT (achado 1/3 da revisao adicional): a checagem de DNS nunca
     * chega a concluir dentro do teto configurado - o metodo tem que
     * devolver true (fail-open), nunca false, e nunca bloquear a chamada
     * alem do teto configurado.
     *
     * <p>Ocupa a UNICA thread do executor com uma tarefa lenta antes de
     * chamar {@code dominioResolvivel}: a consulta de verdade fica
     * enfileirada e nao comeca a executar dentro da janela de timeout -
     * deterministico, sem depender de rede/DNS real.</p>
     */
    @Test
    void consultaQueNaoConcluiDentroDoTetoDeTempoFazFailOpen() throws Exception {
        ExecutorService executorComThreadOcupada = Executors.newSingleThreadExecutor();
        try {
            executorComThreadOcupada.submit(() -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            EmailDominioValidator validator = new EmailDominioValidator(executorComThreadOcupada, 200);

            long inicio = System.currentTimeMillis();
            boolean resultado = validator.dominioResolvivel("contato@dominio-qualquer-que-seja.com");
            long duracaoMs = System.currentTimeMillis() - inicio;

            assertThat(resultado)
                .as("timeout na consulta de dominio tem que ser fail-open (true), nunca false")
                .isTrue();
            assertThat(duracaoMs)
                .as("a chamada nao pode bloquear muito alem do teto configurado (200ms)")
                .isLessThan(2000);
        } finally {
            executorComThreadOcupada.shutdownNow();
        }
    }
}

package br.gov.saude.sgpur.e2e.prod;

import java.nio.file.Path;

/**
 * Ponto de entrada do Robô de Inspeção E2E em Produção — programa Java
 * standalone, invocado via {@code mvn test-compile exec:java} (ver
 * {@code e2e-prod.ps1}), nunca via {@code mvn test}/{@code mvn verify}.
 *
 * <p>Sucesso/falha é comunicado pelo <b>exit code do processo</b>: uma etapa
 * malsucedida vira {@link IllegalStateException}, que o exec-maven-plugin
 * traduz em "BUILD FAILURE" (exit code != 0); tudo certo termina com
 * "BUILD SUCCESS" (exit code 0) — sem nenhuma semântica de "Tests run: N"
 * emprestada de JUnit, que nunca fez sentido pra uma inspeção de produção.
 */
public final class RoboProducaoMain {

    private RoboProducaoMain() {
    }

    public static void main(String[] args) throws Exception {
        ConfiguracaoRobo config = ConfiguracaoRobo.lerDoAmbiente();

        System.out.println();
        System.out.println("==========================================================================");
        System.out.println("  ROBÔ E2E — INSPEÇÃO EM PRODUÇÃO (SAUR)");
        System.out.println("==========================================================================");
        System.out.println("  Alvo:            " + config.baseUrl());
        System.out.println("  Usuário:         " + config.usuarioAdmin());
        System.out.println("  Browser Visível: " + config.headed());
        System.out.println("  Intervalo Ações: " + config.slowMoMs() + " ms");
        System.out.println("==========================================================================");
        System.out.println();

        RoboProducao robo = new RoboProducao(config);
        try {
            robo.iniciar();
            robo.executarInspecaoCompleta();
        } finally {
            robo.close();
            Path relatorioPath = robo.gerarRelatorio();
            System.out.println();
            System.out.println("===============================================================================");
            System.out.println("==> RELATÓRIO VISUAL HTML GERADO: " + relatorioPath);
            System.out.println("===============================================================================");
        }

        if (robo.relatorio().houveFalha()) {
            throw new IllegalStateException(
                "Robô de produção encontrou falha(s) em ao menos uma etapa. Veja o relatório acima.");
        }

        System.out.println();
        System.out.println("==> Inspeção em Produção finalizada com sucesso!");
    }
}

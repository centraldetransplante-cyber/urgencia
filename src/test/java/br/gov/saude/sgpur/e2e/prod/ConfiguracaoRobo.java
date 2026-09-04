package br.gov.saude.sgpur.e2e.prod;

/**
 * Configuração do Robô de Inspeção E2E em Produção, lida de system properties
 * (repassadas via {@code mvn -D... exec:java}) com fallback em variável de
 * ambiente e um default sensato — mesmo padrão já usado no restante do
 * projeto (ex. {@code app.base-url}/{@code SGPUR_BASE_URL}).
 *
 * <p>Programa standalone (não JUnit/Failsafe): {@code exec-maven-plugin}
 * roda o {@code main()} no MESMO JVM do processo Maven, então um
 * {@code -Dsaur.e2e.adminPassword=...} passado na linha de comando do
 * {@code mvn} já chega aqui via {@link System#getProperty}, sem nenhuma
 * complicação de propagação para processo forkado (ao contrário do
 * Failsafe, que forka um JVM novo — ver histórico no CLAUDE.md).
 */
public record ConfiguracaoRobo(
    String baseUrl,
    String usuarioAdmin,
    String senhaAdmin,
    boolean headed,
    int slowMoMs
) {

    public static ConfiguracaoRobo lerDoAmbiente() {
        String baseUrl = propriedadeOuEnv("saur.e2e.baseUrl", "SAUR_E2E_BASE_URL", "https://urgenciarenal.duckdns.org");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String usuario = propriedadeOuEnv("saur.e2e.adminUser", "SAUR_PROD_USER", "admin");
        String senha = propriedadeOuEnv("saur.e2e.adminPassword", "SAUR_PROD_PASSWORD", "");

        if (senha == null || senha.isBlank()) {
            throw new IllegalStateException(
                "A senha do admin de produção não foi informada!\n" +
                "Passe via system property (-Dsaur.e2e.adminPassword=...) ou via variável de ambiente SAUR_PROD_PASSWORD."
            );
        }

        boolean headed = Boolean.parseBoolean(propriedadeOuEnv("saur.e2e.headed", "SAUR_E2E_HEADED", "true"));
        int slowMo = Integer.parseInt(propriedadeOuEnv("saur.e2e.slowMo", "SAUR_E2E_SLOWMO", headed ? "1000" : "0"));

        return new ConfiguracaoRobo(baseUrl, usuario, senha, headed, slowMo);
    }

    private static String propriedadeOuEnv(String propriedade, String variavelAmbiente, String padrao) {
        String valor = System.getProperty(propriedade);
        if (valor != null && !valor.isBlank()) {
            return valor;
        }
        valor = System.getenv(variavelAmbiente);
        if (valor != null && !valor.isBlank()) {
            return valor;
        }
        return padrao;
    }
}

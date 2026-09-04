package br.gov.saude.sgpur.e2e.prod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Robô de Inspeção e Auditoria E2E em PRODUÇÃO com perfil de Administrador.
 *
 * <p>Percorre todas as áreas administrativas e operacionais do SAUR em produção,
 * simulando um operador humano com o browser Chromium visível, injetando legendas
 * de narração, tirando capturas de tela (screenshots) e validando integridade de:
 * <ul>
 *   <li>Autenticação de Administrador</li>
 *   <li>Painel Principal e ausência de erro 500</li>
 *   <li>Fila de Triagem do Portal do Solicitante (/processos/solicitacoes-online)</li>
 *   <li>Painel de Membros Avaliadores da CET-RS (/membros)</li>
 *   <li>Gestão de Usuários do Sistema (/usuarios)</li>
 *   <li>Trilha de Auditoria (/auditoria)</li>
 *   <li>Relatórios e Estatísticas (/relatorios e /relatorios/anual)</li>
 *   <li>Controle de Urgências (/controle-urgencias)</li>
 *   <li>Inspeção de detalhe e abas de processo real (se houver no banco, sem alterar nada)</li>
 *   <li>Encerramento seguro de sessão (Logout)</li>
 * </ul>
 *
 * <p>Ao final gera um relatório HTML visual com os screenshots em
 * {@code target/e2e-prod-screenshots/relatorio-execucao.html}.
 */
public class AdminProdRobotIT extends PlaywrightProdBase {

    @Test
    @DisplayName("Executa varredura visual e funcional completa do SAUR em Produção como ADMIN")
    void executarInspecaoProducaoComoAdmin() {
        // 1. Login
        loginAdmin();

        // 2. Painel Principal / Processos
        verificarPainelPrincipal();

        // 3. Fila de Triagem de Solicitações Online
        verificarTriagemSolicitacoesOnline();

        // 4. Membros Avaliadores (Área restrita de gestão)
        verificarMembrosAvaliadores();

        // 5. Usuários do Sistema (Área restrita ADMIN)
        verificarUsuarios();

        // 6. Auditoria (Área restrita ADMIN)
        verificarAuditoria();

        // 7. Relatórios de Gestão
        verificarRelatorios();

        // 8. Controle de Urgências
        verificarControleUrgencias();

        // 9. Inspeção de Processo Existente (apenas leitura, se houver)
        inspecionarProcessoExistenteSeHouver();

        // 10. Logout Seguro
        logout();

        System.out.println("\n==> Inspeção em Produção finalizada com sucesso!");
    }

    private void verificarPainelPrincipal() {
        long inicio = System.currentTimeMillis();
        legenda("Navegando para o Painel Principal (/)...");
        irPara("/");

        legenda("Inspecionando cards de contagem e tabela de processos...");
        mostrarPaginaInteira();

        String screenshot = capturarScreenshot("painel-principal");
        String titulo = page.title();
        boolean semErro500 = !page.content().contains("Internal Server Error") && !page.content().contains("Whitelabel Error Page");
        long duracao = System.currentTimeMillis() - inicio;

        int totalLinhas = page.locator("table tbody tr").count();
        String detalhe = String.format("Painel carregado com sucesso (%s). Linhas na tabela: %d. Erros de console: %d.",
            titulo, totalLinhas, errosConsole.size());

        registrarEtapa("Painel Principal / Processos", detalhe, semErro500, screenshot, duracao);
        assertThat(semErro500).as("Painel principal não deve apresentar erro 500").isTrue();
    }

    private void verificarTriagemSolicitacoesOnline() {
        long inicio = System.currentTimeMillis();
        legenda("Acessando Fila de Triagem de Solicitações Online (/processos/solicitacoes-online)...");
        irPara("/processos/solicitacoes-online");

        legenda("Verificando solicitações recebidas do Portal do Solicitante...");
        mostrarPaginaInteira();

        String screenshot = capturarScreenshot("triagem-solicitacoes-online");
        boolean semErro = !page.content().contains("Whitelabel Error Page");
        int pendentes = page.locator("table tbody tr").count();
        long duracao = System.currentTimeMillis() - inicio;

        String detalhe = String.format("Fila de triagem carregada. Registros visíveis: %d.", pendentes);
        registrarEtapa("Fila de Triagem de Solicitações", detalhe, semErro, screenshot, duracao);
        assertThat(semErro).as("Fila de triagem deve responder normalmente").isTrue();
    }

    private void verificarMembrosAvaliadores() {
        long inicio = System.currentTimeMillis();
        legenda("Acessando Gestão de Membros Avaliadores (/membros)...");
        irPara("/membros");

        legenda("Inspecionando lista de médicos avaliadores e instituições...");
        mostrarPaginaInteira();

        String screenshot = capturarScreenshot("membros-avaliadores");
        boolean semErro = !page.content().contains("Whitelabel Error Page");
        int membros = page.locator("table tbody tr").count();
        long duracao = System.currentTimeMillis() - inicio;

        String detalhe = String.format("Lista de membros avaliadores carregada. Total cadastrado: %d.", membros);
        registrarEtapa("Gestão de Membros Avaliadores", detalhe, semErro, screenshot, duracao);
        assertThat(semErro).as("Tela de membros deve responder normalmente").isTrue();
    }

    private void verificarUsuarios() {
        long inicio = System.currentTimeMillis();
        legenda("Acessando Gestão de Usuários (/usuarios)...");
        irPara("/usuarios");

        legenda("Inspecionando contas de acesso (ADMIN, OPERADOR, AVALIADOR, SOLICITANTE)...");
        mostrarPaginaInteira();

        String screenshot = capturarScreenshot("usuarios-sistema");
        boolean semErro = !page.content().contains("Whitelabel Error Page");
        int usuarios = page.locator("table tbody tr").count();
        long duracao = System.currentTimeMillis() - inicio;

        String detalhe = String.format("Lista de usuários carregada com sucesso. Total cadastrado: %d.", usuarios);
        registrarEtapa("Gestão de Usuários", detalhe, semErro, screenshot, duracao);
        assertThat(semErro).as("Tela de usuários deve responder normalmente").isTrue();
    }

    private void verificarAuditoria() {
        long inicio = System.currentTimeMillis();
        legenda("Acessando Trilha de Auditoria do Sistema (/auditoria)...");
        irPara("/auditoria");

        legenda("Inspecionando histórico de operações e logs de auditoria...");
        mostrarPaginaInteira();

        String screenshot = capturarScreenshot("trilha-auditoria");
        boolean semErro = !page.content().contains("Whitelabel Error Page");
        int registros = page.locator("table tbody tr").count();
        long duracao = System.currentTimeMillis() - inicio;

        String detalhe = String.format("Trilha de auditoria carregada. Registros recentes: %d.", registros);
        registrarEtapa("Trilha de Auditoria", detalhe, semErro, screenshot, duracao);
        assertThat(semErro).as("Tela de auditoria deve responder normalmente").isTrue();
    }

    private void verificarRelatorios() {
        long inicio = System.currentTimeMillis();
        legenda("Acessando Módulo de Relatórios (/relatorios)...");
        irPara("/relatorios");

        legenda("Inspecionando filtros e opções de relatórios...");
        mostrarPaginaInteira();

        String screenshot = capturarScreenshot("relatorios-gestao");
        boolean semErro = !page.content().contains("Whitelabel Error Page");
        long duracao = System.currentTimeMillis() - inicio;

        registrarEtapa("Módulo de Relatórios", "Interface de relatórios e filtros disponível", semErro, screenshot, duracao);
        assertThat(semErro).as("Tela de relatórios deve responder normalmente").isTrue();
    }

    private void verificarControleUrgencias() {
        long inicio = System.currentTimeMillis();
        legenda("Acessando Controle de Urgências (/controle-urgencias)...");
        irPara("/controle-urgencias");

        legenda("Inspecionando quadro de controle de urgências...");
        mostrarPaginaInteira();

        String screenshot = capturarScreenshot("controle-urgencias");
        boolean semErro = !page.content().contains("Whitelabel Error Page");
        long duracao = System.currentTimeMillis() - inicio;

        registrarEtapa("Controle de Urgências", "Quadro operacional de urgências disponível", semErro, screenshot, duracao);
        assertThat(semErro).as("Tela de controle de urgências deve responder normalmente").isTrue();
    }

    private void inspecionarProcessoExistenteSeHouver() {
        long inicio = System.currentTimeMillis();
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
            boolean semErro = !page.content().contains("Whitelabel Error Page");
            long duracao = System.currentTimeMillis() - inicio;

            String detalhe = "Inspeção visual da tela de detalhe do processo (" + page.url() + ") realizada sem alterações.";
            registrarEtapa("Inspeção de Processo Existente", detalhe, semErro, screenshot, duracao);
        } else {
            long duracao = System.currentTimeMillis() - inicio;
            String screenshot = capturarScreenshot("sem-processos");
            registrarEtapa("Inspeção de Processo Existente", "Nenhum processo cadastrado no momento para visualização.", true, screenshot, duracao);
        }
    }
}

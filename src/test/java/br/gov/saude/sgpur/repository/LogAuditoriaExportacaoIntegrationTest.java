package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.LogAuditoria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de INTEGRACAO (H2 real, modo PostgreSQL) da consulta de exportacao
 * de auditoria ({@code LogAuditoriaRepository.buscarParaExportacao}) e da
 * combinacao de filtros feita por {@code AuditoriaService.buscarParaExportacao}.
 *
 * <p><b>Por que precisa ser integracao.</b> Mesmo raciocinio de
 * {@code BuscaListasIntegrationTest}: e uma consulta JPQL nova, e um mock de
 * repositorio nunca pegaria um erro de sintaxe/tipo que so aparece contra um
 * banco de verdade — foi exatamente esse tipo de erro (nao pego por nenhum
 * teste local, so por SQL real de producao) que motivou este metodo a evitar
 * por completo o padrao {@code :param IS NULL OR ...} usado em
 * {@code LogAuditoriaRepository.buscar} (ver CLAUDE.md, secao "PENDENTE —
 * erro 500 em /auditoria", 2026-08-07).</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-auditoria-export-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-auditoria-export-it"
})
class LogAuditoriaExportacaoIntegrationTest {

    // Mesmas sentinelas seguras usadas por AuditoriaService (ver o javadoc de
    // DATA_MINIMA/DATA_MAXIMA la) — LocalDateTime.MIN/MAX estouram a faixa
    // representavel de "timestamp" no banco (achado real desta sessao,
    // reproduzido por este teste antes da correcao).
    private static final LocalDateTime SEM_LIMITE_INICIO = LocalDateTime.of(1900, 1, 1, 0, 0);
    private static final LocalDateTime SEM_LIMITE_FIM = LocalDateTime.of(2200, 12, 31, 23, 59, 59);

    @Autowired
    private LogAuditoriaRepository repo;
    @Autowired
    private br.gov.saude.sgpur.service.AuditoriaService auditoriaService;

    @BeforeEach
    void limpar() {
        repo.deleteAll();
    }

    private LogAuditoria salvar(String usuario, String acao, String detalhe, LocalDateTime dataHora) {
        LogAuditoria log = new LogAuditoria(usuario, acao, detalhe);
        log.setDataHora(dataHora);
        return repo.save(log);
    }

    @Test
    void repositorioSemFiltroDevolveTodosOrdenadosPorDataDesc() {
        salvar("admin", "LOGIN", "ok", LocalDateTime.of(2026, 7, 21, 10, 0));
        salvar("operador1", "PROCESSO_CADASTRADO", "Processo 01/2026", LocalDateTime.of(2026, 7, 22, 9, 0));

        var todos = repo.buscarParaExportacao("", "", SEM_LIMITE_INICIO, SEM_LIMITE_FIM);

        assertThat(todos).hasSize(2);
        assertThat(todos.get(0).getUsuario()).isEqualTo("operador1"); // mais recente primeiro
        assertThat(todos.get(1).getUsuario()).isEqualTo("admin");
    }

    @Test
    void repositorioFiltraPorUsuarioParcialIgnorandoCaixa() {
        salvar("Admin", "LOGIN", "ok", LocalDateTime.of(2026, 7, 21, 10, 0));
        salvar("operador1", "PROCESSO_CADASTRADO", "Processo 01/2026", LocalDateTime.of(2026, 7, 22, 9, 0));

        var filtrado = repo.buscarParaExportacao("adm", "", SEM_LIMITE_INICIO, SEM_LIMITE_FIM);

        assertThat(filtrado).extracting(LogAuditoria::getUsuario).containsExactly("Admin");
    }

    @Test
    void repositorioFiltraPorAcaoExata() {
        salvar("admin", "LOGIN", "ok", LocalDateTime.of(2026, 7, 21, 10, 0));
        salvar("operador1", "PROCESSO_CADASTRADO", "Processo 01/2026", LocalDateTime.of(2026, 7, 22, 9, 0));

        var filtrado = repo.buscarParaExportacao("", "PROCESSO_CADASTRADO", SEM_LIMITE_INICIO, SEM_LIMITE_FIM);

        assertThat(filtrado).extracting(LogAuditoria::getAcao).containsExactly("PROCESSO_CADASTRADO");
    }

    @Test
    void repositorioFiltraPorPeriodo() {
        salvar("admin", "LOGIN", "dia 20", LocalDateTime.of(2026, 7, 20, 10, 0));
        salvar("admin", "LOGIN", "dia 21", LocalDateTime.of(2026, 7, 21, 10, 0));
        salvar("admin", "LOGIN", "dia 25", LocalDateTime.of(2026, 7, 25, 10, 0));

        var filtrado = repo.buscarParaExportacao("", "",
            LocalDateTime.of(2026, 7, 21, 0, 0), LocalDateTime.of(2026, 7, 21, 23, 59, 59));

        assertThat(filtrado).extracting(LogAuditoria::getDetalhe).containsExactly("dia 21");
    }

    @Test
    void repositorioCombinaTodosOsFiltrosNaMesmaConsulta() {
        salvar("operador1", "PROCESSO_CADASTRADO", "match", LocalDateTime.of(2026, 7, 21, 10, 0));
        // Mesmo usuario/acao mas fora do periodo:
        salvar("operador1", "PROCESSO_CADASTRADO", "fora do periodo", LocalDateTime.of(2026, 6, 1, 10, 0));
        // Mesmo periodo/acao mas outro usuario:
        salvar("operador2", "PROCESSO_CADASTRADO", "outro usuario", LocalDateTime.of(2026, 7, 21, 11, 0));
        // Mesmo periodo/usuario mas outra acao:
        salvar("operador1", "LOGIN", "outra acao", LocalDateTime.of(2026, 7, 21, 12, 0));

        var filtrado = repo.buscarParaExportacao("operador1", "PROCESSO_CADASTRADO",
            LocalDateTime.of(2026, 7, 21, 0, 0), LocalDateTime.of(2026, 7, 21, 23, 59, 59));

        assertThat(filtrado).extracting(LogAuditoria::getDetalhe).containsExactly("match");
    }

    @Test
    void servicoTraduzLocalDateParaLimitesDoDiaEAceitaFiltrosNulos() {
        salvar("admin", "LOGIN", "dia 21 de manha", LocalDateTime.of(2026, 7, 21, 0, 30));
        salvar("admin", "LOGIN", "dia 21 de noite", LocalDateTime.of(2026, 7, 21, 23, 30));
        salvar("admin", "LOGIN", "dia 22", LocalDateTime.of(2026, 7, 22, 10, 0));

        var doDia21 = auditoriaService.buscarParaExportacao(null, null,
            LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 21));

        assertThat(doDia21).extracting(LogAuditoria::getDetalhe)
            .containsExactlyInAnyOrder("dia 21 de manha", "dia 21 de noite");

        var semFiltroNenhum = auditoriaService.buscarParaExportacao(null, null, null, null);
        assertThat(semFiltroNenhum).hasSize(3);
    }
}

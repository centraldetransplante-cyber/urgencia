package br.gov.saude.sgpur.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes de {@link SchemaMigration} focados na distincao entre falha ESPERADA
 * (idempotencia/dialeto sem suporte — deve ficar em DEBUG, silenciosa) e
 * falha INESPERADA (deve virar WARN com stacktrace completa, para nunca mais
 * passar despercebida em producao — achado P1 de
 * {@code docs/RELATORIO-VISTORIA-CODIGO-2026-08-22.md}, corrigido em
 * 2026-08-23).
 *
 * <p>Nao usa Spring context: {@link SchemaMigration} so depende de
 * {@link JdbcTemplate}, mockado puro com Mockito. Todos os metodos de bloco
 * sao privados por design (nao expor detalhe de implementacao), entao os
 * testes exercitam o comportamento observavel via {@link
 * SchemaMigration#run}: (1) {@code run()} nunca lanca, mesmo com falha em
 * toda etapa (o boot nunca pode ser bloqueado — decisao de projeto mantida
 * de proposito, ver javadoc da classe); (2) o NIVEL do log (WARN vs DEBUG)
 * diverge corretamente conforme a falha e esperada ou nao. O nivel e
 * capturado anexando um {@link ListAppender} do Logback diretamente no
 * logger da classe.
 */
class SchemaMigrationTest {

    private JdbcTemplate jdbc;
    private SchemaMigration migration;
    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        migration = new SchemaMigration(jdbc);

        logbackLogger = (Logger) LoggerFactory.getLogger(SchemaMigration.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.ALL);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("falha ESPERADA (idempotencia: 'already exists') nao gera WARN e run() nao lanca")
    void falhaEsperadaNaoGeraWarn() {
        doThrow(new BadSqlGrammarException("execute", "ALTER TABLE ...",
                new SQLException("column \"COORDENADOR\" already exists")))
            .when(jdbc).execute(anyString());
        when(jdbc.queryForList(anyString(), (Class<Object>) any())).thenReturn(List.of());
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        migration.run(null);

        List<ILoggingEvent> warns = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .toList();
        assertThat(warns).isEmpty();

        List<ILoggingEvent> debugs = appender.list.stream()
            .filter(e -> e.getLevel() == Level.DEBUG)
            .toList();
        assertThat(debugs).isNotEmpty();
    }

    @Test
    @DisplayName("falha INESPERADA nao derruba o boot (run() nao lanca), mas vira WARN com stacktrace")
    void falhaInesperadaViraWarnComStacktrace() {
        RuntimeException falhaReal = new DataAccessResourceFailureException("connection refused");
        doThrow(falhaReal).when(jdbc).execute(anyString());
        when(jdbc.queryForList(anyString(), (Class<Object>) any())).thenThrow(falhaReal);
        when(jdbc.queryForList(anyString())).thenThrow(falhaReal);

        // run() nao pode lancar, mesmo com falha inesperada em toda etapa — boot sempre sobe.
        migration.run(null);

        List<ILoggingEvent> warnsComStack = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .filter(e -> e.getThrowableProxy() != null)
            .toList();
        assertThat(warnsComStack).isNotEmpty();

        boolean temResumoAgregado = appender.list.stream()
            .anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("de 4 etapa(s)"));
        assertThat(temResumoAgregado).isTrue();

        // Nenhuma das WARNs de falha inesperada pode ter ficado sem stacktrace anexada.
        assertThat(warnsComStack).allSatisfy(e ->
            assertThat(e.getThrowableProxy().getClassName())
                .isEqualTo(DataAccessResourceFailureException.class.getName()));
    }

    @Test
    @DisplayName("falha inesperada apenas em uma etapa: resumo agregado reflete a contagem certa")
    void falhaInesperadaParcialContaCorretamente() {
        // adicionarColunasFaltantes (3x execute) falha de forma inesperada;
        // as demais etapas (queryForList) nao encontram nada a fazer.
        doThrow(new DataAccessResourceFailureException("connection refused"))
            .when(jdbc).execute(anyString());
        when(jdbc.queryForList(anyString(), (Class<Object>) any())).thenReturn(List.of());
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        migration.run(null);

        boolean temResumoDeUmaEtapa = appender.list.stream()
            .anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("1 de 4 etapa(s)"));
        assertThat(temResumoDeUmaEtapa).isTrue();
    }
}

package br.gov.saude.sgpur.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Migracao leve de schema executada na subida do app, idempotente.
 *
 * <p>Motivo: o Hibernate 6, no H2, mapeia enums {@code @Enumerated(STRING)} para
 * o tipo nativo {@code ENUM(...)} da coluna. Com {@code ddl-auto=update} esse
 * tipo NAO e ampliado quando o enum Java ganha novos valores, entao bancos
 * criados antes da expansao passam a rejeitar valores novos (ex.: o status
 * {@code SOLICITADO} ou o anexo {@code COMPROVANTE_SNT}) com
 * "Value not permitted for column" ao salvar — quebrando o cadastro.
 *
 * <p>Correcao: converter toda coluna {@code ENUM} para {@code VARCHAR} (no H2)
 * e remover CHECK constraints de enum obsoletas (no PostgreSQL/Neon). Assim as
 * colunas passam a aceitar qualquer valor do enum atual ou futuro; a validade e
 * garantida no nivel da aplicacao pelo proprio enum Java. Cada bloco roda em
 * try/catch para nunca impedir a subida do app — essa decisao e deliberada
 * (ver CLAUDE.md, "nunca travar o usuario/deploy") e NAO deve virar bloqueante.
 *
 * <p>Dentro de cada catch, porem, a falha e classificada: uma falha
 * ESPERADA (idempotencia — coluna/constraint ja existe ou ja foi removida,
 * recurso do dialeto errado) fica em DEBUG; qualquer falha que nao bata com
 * essa heuristica e tratada como INESPERADA e sobe para WARN com a
 * stacktrace completa, para nunca mais passar batido em silencio num log de
 * producao (achado P1 de `docs/RELATORIO-VISTORIA-CODIGO-2026-08-22.md`).
 */
@Component
@Order(1)
public class SchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    /**
     * Fragmentos (case-insensitive) que indicam falha ESPERADA/idempotente:
     * a operacao ja foi feita antes, ou o recurso simplesmente nao existe
     * nesse dialeto. Qualquer outra falha e tratada como inesperada (WARN),
     * de proposito conservador — na duvida, WARN.
     */
    private static final String[] FRAGMENTOS_FALHA_ESPERADA = {
        "already exists",
        "duplicate",
        "does not exist",
        "unknown data type",
        "syntax error",
        "feature not supported",
        "not found",
        "table not found",
        "column not found",
        "constraint not found",
        "unsupported"
    };

    private final JdbcTemplate jdbc;

    public SchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        int falhasInesperadas = 0;
        if (limparCopiasHibernate()) {
            falhasInesperadas++;
        }
        if (adicionarColunasFaltantes()) {
            falhasInesperadas++;
        }
        if (converterEnumsParaVarcharH2()) {
            falhasInesperadas++;
        }
        if (removerChecksDeEnumObsoletasPostgres()) {
            falhasInesperadas++;
        }
        if (falhasInesperadas > 0) {
            log.warn("SchemaMigration: {} de 4 etapa(s) de migracao de schema tiveram falha "
                + "INESPERADA no boot (ver WARNs acima com stacktrace). O boot NAO foi "
                + "interrompido de proposito — revisar manualmente se o schema ficou "
                + "parcialmente migrado.", falhasInesperadas);
        }
    }

    /**
     * Adiciona colunas que podem faltar em tabelas existentes (ex.: coordenador em
     * MEMBRO_URGENCIA_RENAL). Retorna {@code true} se alguma falha INESPERADA ocorreu.
     */
    boolean adicionarColunasFaltantes() {
        boolean falhaInesperada = false;
        try {
            jdbc.execute("ALTER TABLE MEMBRO_URGENCIA_RENAL ADD COLUMN IF NOT EXISTS "
                + "COORDENADOR BOOLEAN DEFAULT FALSE NOT NULL");
        } catch (Exception e) {
            falhaInesperada |= registrar("coluna COORDENADOR ja existe ou erro ignorado", e);
        }
        try {
            jdbc.execute("ALTER TABLE MEMBRO_URGENCIA_RENAL ALTER COLUMN ATIVO SET DEFAULT TRUE");
        } catch (Exception e) {
            falhaInesperada |= registrar("default de ATIVO ja configurado ou erro ignorado", e);
        }
        try {
            jdbc.execute("ALTER TABLE USUARIO ADD COLUMN IF NOT EXISTS EMAIL VARCHAR(150)");
        } catch (Exception e) {
            falhaInesperada |= registrar("coluna EMAIL (usuario) ja existe ou erro ignorado", e);
        }
        return falhaInesperada;
    }

    /**
     * Remove tabelas temporarias deixadas por migracoes Hibernate que falharam (_COPY_n).
     * Retorna {@code true} se alguma falha INESPERADA ocorreu.
     */
    boolean limparCopiasHibernate() {
        try {
            List<String> tabelas = jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLE_NAME LIKE '%\\_COPY\\_%' ESCAPE '\\'",
                String.class);
            for (String t : tabelas) {
                jdbc.execute("DROP TABLE IF EXISTS \"" + t + "\" CASCADE");
                log.warn("SchemaMigration: removida tabela temporaria Hibernate: {}", t);
            }
            return false;
        } catch (Exception e) {
            return registrar("limpeza de copias Hibernate ignorada", e);
        }
    }

    /**
     * H2: converte colunas de tipo nativo ENUM em VARCHAR(255).
     * Retorna {@code true} se alguma falha INESPERADA ocorreu.
     */
    boolean converterEnumsParaVarcharH2() {
        try {
            List<Map<String, Object>> colunas = jdbc.queryForList(
                "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE DATA_TYPE = 'ENUM'");
            for (Map<String, Object> col : colunas) {
                String tabela = String.valueOf(col.get("TABLE_NAME"));
                String coluna = String.valueOf(col.get("COLUMN_NAME"));
                jdbc.execute("ALTER TABLE \"" + tabela + "\" ALTER COLUMN \"" + coluna
                    + "\" SET DATA TYPE VARCHAR(255)");
                log.warn("SchemaMigration: coluna {}.{} convertida de ENUM para VARCHAR(255).",
                    tabela, coluna);
            }
            return false;
        } catch (Exception e) {
            // Em bancos sem o tipo ENUM (ex.: PostgreSQL) a consulta nao retorna nada;
            // qualquer outra falha e classificada e registrada, mas nao impede a subida.
            return registrar("etapa H2 (ENUM->VARCHAR) ignorada", e);
        }
    }

    /**
     * PostgreSQL: o Hibernate cria CHECK constraints para enums STRING. Quando o
     * enum cresce, a constraint fica obsoleta. Remove as CHECK constraints das
     * tabelas de enum (cuja unica origem e o mapeamento de enum), exceto as de
     * NOT NULL. No H2 nao ha o que remover (nenhuma CHECK nessas tabelas).
     * Retorna {@code true} se alguma falha INESPERADA ocorreu.
     */
    boolean removerChecksDeEnumObsoletasPostgres() {
        try {
            List<Map<String, Object>> checks = jdbc.queryForList(
                "SELECT tc.table_name, tc.constraint_name "
                    + "FROM information_schema.table_constraints tc "
                    + "WHERE tc.constraint_type = 'CHECK' "
                    + "  AND lower(tc.table_name) IN ('processo','anexo','parecer','usuario','solicitacao_online') "
                    + "  AND lower(tc.constraint_name) NOT LIKE '%not_null%'");
            boolean falhaInesperada = false;
            for (Map<String, Object> ck : checks) {
                String tabela = String.valueOf(ck.get("table_name"));
                String constraint = String.valueOf(ck.get("constraint_name"));
                try {
                    jdbc.execute("ALTER TABLE " + tabela + " DROP CONSTRAINT \"" + constraint + "\"");
                    log.warn("SchemaMigration: removida CHECK constraint de enum {} em {}.",
                        constraint, tabela);
                } catch (Exception e) {
                    // constraint pode ter sido removida em execucao anterior (esperado);
                    // qualquer outra causa e classificada e registrada abaixo.
                    falhaInesperada |= registrar(
                        "remocao de CHECK " + constraint + " em " + tabela + " ignorada", e);
                }
            }
            return falhaInesperada;
        } catch (Exception e) {
            return registrar("etapa PostgreSQL (drop CHECK) ignorada", e);
        }
    }

    /**
     * Classifica a falha e registra no nivel apropriado. Falha ESPERADA
     * (idempotencia/dialeto) vai para DEBUG; qualquer outra vai para WARN
     * com a stacktrace completa. Retorna {@code true} quando a falha foi
     * classificada como INESPERADA.
     */
    private boolean registrar(String contexto, Exception e) {
        if (isFalhaEsperada(e)) {
            log.debug("SchemaMigration: {}: {}", contexto, e.getMessage());
            return false;
        }
        log.warn("SchemaMigration: {} — falha INESPERADA, revisar manualmente.", contexto, e);
        return true;
    }

    /**
     * Heuristica conservadora: so classifica como esperada quando a mensagem
     * contem um dos fragmentos conhecidos de idempotencia/dialeto. Na duvida,
     * classifica como inesperada (WARN) — e melhor um WARN a mais do que
     * deixar um erro real invisivel.
     */
    private boolean isFalhaEsperada(Exception e) {
        String mensagem = mensagemCompleta(e);
        if (mensagem == null || mensagem.isBlank()) {
            return false;
        }
        String mensagemLower = mensagem.toLowerCase(Locale.ROOT);
        for (String fragmento : FRAGMENTOS_FALHA_ESPERADA) {
            if (mensagemLower.contains(fragmento)) {
                return true;
            }
        }
        return false;
    }

    private String mensagemCompleta(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable atual = t;
        while (atual != null) {
            if (atual.getMessage() != null) {
                sb.append(atual.getMessage()).append(' ');
            }
            atual = atual.getCause();
        }
        return sb.toString();
    }
}

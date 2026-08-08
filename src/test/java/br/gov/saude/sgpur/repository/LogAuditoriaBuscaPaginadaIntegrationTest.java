package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.LogAuditoria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de INTEGRACAO (H2 real, modo PostgreSQL) da consulta PAGINADA de
 * auditoria ({@code LogAuditoriaRepository.buscar}), usada por
 * {@code AuditoriaController}/{@code AuditoriaService} para renderizar a
 * tela {@code /auditoria}.
 *
 * <p><b>Bug real de producao que este teste guarda (CORRIGIDO em
 * 2026-08-07):</b> a tela quebrava com 500 em TODA carga, com ou sem filtro
 * preenchido — confirmado por log real de producao (`journalctl`), dois
 * stacktraces reais:
 * <pre>
 * Caused by: org.postgresql.util.PSQLException: ERROR: could not determine data type of parameter $7
 * SQLState: 42P18
 * </pre>
 * Causa: a consulta antiga usava o padrao {@code :de is null or l.dataHora
 * >= :de} — quando nao ha filtro de data (o caso comum, inclusive o de
 * simplesmente abrir a tela), {@code :de} chegava como {@code null} usado
 * SOMENTE em {@code IS NULL}, sem nenhum outro contexto de tipo na mesma
 * ocorrencia posicional. O protocolo estendido do PostgreSQL (Parse/
 * Describe) precisa inferir o tipo de cada parametro ANTES de qualquer
 * valor chegar, e um parametro usado so em {@code IS NULL} nao tem como ter
 * o tipo inferido. Corrigido convertendo toda ausencia de filtro
 * (usuario/acao vazios, datas nas sentinelas {@code DATA_MINIMA}/
 * {@code DATA_MAXIMA}) em {@code AuditoriaService.buscar} ANTES de chamar o
 * repositorio — mesma tecnica ja usada em {@code buscarParaExportacao} desde
 * a criacao dela (ver {@link LogAuditoriaExportacaoIntegrationTest}).</p>
 *
 * <p><b>Limitacao conhecida deste teste: H2 (mesmo em MODE=PostgreSQL) e
 * TOLERANTE ao padrao antigo (`:param IS NULL OR ...`) e NUNCA reproduziu o
 * defeito</b> — foi exatamente por isso que o bug chegou em producao sem
 * nenhum teste local acusando nada (documentado no CLAUDE.md, secao
 * "PENDENTE — erro 500 em /auditoria"). Ou seja: mesmo rodando este teste
 * contra a versao ANTIGA (quebrada) da query, ele passaria — H2 nao serve
 * de rede de seguranca para ESTE tipo especifico de erro (inferencia de
 * tipo do protocolo estendido do Postgres). A prova real de que o
 * PostgreSQL de producao aceita a consulta corrigida foi feita a parte,
 * rodando a query equivalente via `psql` contra o Postgres real da VM
 * (fora da suite automatizada, nao reproduzivel em CI sem um Postgres de
 * verdade disponivel). O valor deste teste e outro, e permanente: fixar o
 * comportamento funcional esperado da consulta (filtra corretamente,
 * combina os 4 criterios, ordena por data desc, pagina) no MESMO padrao
 * sem-null usado agora pelo codigo de producao — se algum dia alguem
 * reintroduzir {@code :param IS NULL OR ...} aqui, este teste continua
 * verde (nao pega isso), mas a leitura do codigo/dos comentarios deve
 * impedir a recaida. Se o projeto algum dia ganhar infraestrutura de teste
 * contra um Postgres real (Testcontainers ou similar), portar este cenario
 * para la fecha essa lacuna.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-auditoria-busca-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-auditoria-busca-it"
})
class LogAuditoriaBuscaPaginadaIntegrationTest {

    // Mesmas sentinelas seguras usadas por AuditoriaService.
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
    void repositorioSemNenhumFiltroDevolveTodosOrdenadosPorDataDesc() {
        salvar("admin", "LOGIN", "ok", LocalDateTime.of(2026, 7, 21, 10, 0));
        salvar("operador1", "PROCESSO_CADASTRADO", "Processo 01/2026", LocalDateTime.of(2026, 7, 22, 9, 0));

        Page<LogAuditoria> pagina = repo.buscar("", "", SEM_LIMITE_INICIO, SEM_LIMITE_FIM,
            PageRequest.of(0, 30));

        assertThat(pagina.getContent()).hasSize(2);
        assertThat(pagina.getContent().get(0).getUsuario()).isEqualTo("operador1");
        assertThat(pagina.getContent().get(1).getUsuario()).isEqualTo("admin");
    }

    @Test
    void repositorioFiltraPorUsuarioParcialIgnorandoCaixa() {
        salvar("Admin", "LOGIN", "ok", LocalDateTime.of(2026, 7, 21, 10, 0));
        salvar("operador1", "PROCESSO_CADASTRADO", "Processo 01/2026", LocalDateTime.of(2026, 7, 22, 9, 0));

        Page<LogAuditoria> pagina = repo.buscar("adm", "", SEM_LIMITE_INICIO, SEM_LIMITE_FIM,
            PageRequest.of(0, 30));

        assertThat(pagina.getContent()).extracting(LogAuditoria::getUsuario).containsExactly("Admin");
    }

    @Test
    void repositorioFiltraPorAcaoExata() {
        salvar("admin", "LOGIN", "ok", LocalDateTime.of(2026, 7, 21, 10, 0));
        salvar("operador1", "PROCESSO_CADASTRADO", "Processo 01/2026", LocalDateTime.of(2026, 7, 22, 9, 0));

        Page<LogAuditoria> pagina = repo.buscar("", "PROCESSO_CADASTRADO", SEM_LIMITE_INICIO, SEM_LIMITE_FIM,
            PageRequest.of(0, 30));

        assertThat(pagina.getContent()).extracting(LogAuditoria::getAcao).containsExactly("PROCESSO_CADASTRADO");
    }

    @Test
    void repositorioFiltraPorPeriodo() {
        salvar("admin", "LOGIN", "dia 20", LocalDateTime.of(2026, 7, 20, 10, 0));
        salvar("admin", "LOGIN", "dia 21", LocalDateTime.of(2026, 7, 21, 10, 0));
        salvar("admin", "LOGIN", "dia 25", LocalDateTime.of(2026, 7, 25, 10, 0));

        Page<LogAuditoria> pagina = repo.buscar("", "",
            LocalDateTime.of(2026, 7, 21, 0, 0), LocalDateTime.of(2026, 7, 21, 23, 59, 59),
            PageRequest.of(0, 30));

        assertThat(pagina.getContent()).extracting(LogAuditoria::getDetalhe).containsExactly("dia 21");
    }

    @Test
    void repositorioRespeitaPaginacao() {
        for (int i = 1; i <= 5; i++) {
            salvar("admin", "LOGIN", "log " + i, LocalDateTime.of(2026, 7, 20, 0, i, 0));
        }
        Pageable primeiraPagina = PageRequest.of(0, 2);

        Page<LogAuditoria> pagina = repo.buscar("", "", SEM_LIMITE_INICIO, SEM_LIMITE_FIM, primeiraPagina);

        assertThat(pagina.getContent()).hasSize(2);
        assertThat(pagina.getTotalElements()).isEqualTo(5);
        assertThat(pagina.getTotalPages()).isEqualTo(3);
    }

    /**
     * Este e o cenario exato do bug relatado: o service e chamado sem
     * nenhum filtro preenchido (todos os 4 parametros {@code null}), que e
     * exatamente o que acontece ao simplesmente abrir {@code /auditoria}
     * sem clicar em nenhum filtro. Antes da correcao, o service repassava
     * {@code null} cru ao repositorio; agora converte para os valores
     * efetivos (string vazia / sentinelas) documentados na classe. H2 nao
     * reproduz o 42P18 do Postgres (ver javadoc da classe), mas este teste
     * garante que o CAMINHO DE CODIGO exercitado pelo bug (service sem
     * filtro nenhum -> repositorio) continua funcionalmente correto, e serve
     * de documentacao executavel do cenario que quebrava em producao.
     */
    @Test
    void servicoSemNenhumFiltroPreenchidoNaoRepassaNullAoRepositorioEDevolveTodos() {
        salvar("admin", "LOGIN", "dia 21 de manha", LocalDateTime.of(2026, 7, 21, 0, 30));
        salvar("admin", "LOGIN", "dia 21 de noite", LocalDateTime.of(2026, 7, 21, 23, 30));
        salvar("admin", "LOGIN", "dia 22", LocalDateTime.of(2026, 7, 22, 10, 0));

        Page<LogAuditoria> pagina = auditoriaService.buscar(null, null, null, null,
            PageRequest.of(0, 30));

        assertThat(pagina.getContent()).hasSize(3);
    }

    @Test
    void servicoTraduzLocalDateParaLimitesDoDia() {
        salvar("admin", "LOGIN", "dia 21 de manha", LocalDateTime.of(2026, 7, 21, 0, 30));
        salvar("admin", "LOGIN", "dia 21 de noite", LocalDateTime.of(2026, 7, 21, 23, 30));
        salvar("admin", "LOGIN", "dia 22", LocalDateTime.of(2026, 7, 22, 10, 0));

        Page<LogAuditoria> pagina = auditoriaService.buscar(null, null,
            LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 21), PageRequest.of(0, 30));

        assertThat(pagina.getContent()).extracting(LogAuditoria::getDetalhe)
            .containsExactlyInAnyOrder("dia 21 de manha", "dia 21 de noite");
    }
}

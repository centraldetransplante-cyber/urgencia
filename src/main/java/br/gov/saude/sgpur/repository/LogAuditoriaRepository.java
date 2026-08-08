package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.LogAuditoria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogAuditoriaRepository extends JpaRepository<LogAuditoria, Long> {

    Page<LogAuditoria> findAllByOrderByDataHoraDesc(Pageable pageable);

    /**
     * Busca filtrada da trilha de auditoria (/auditoria).
     *
     * <p><b>Motivacao (auditoria de UI, 2026-08-04).</b> A tela nao tinha filtro
     * nenhum - nem por usuario, nem por acao, nem por periodo -, e e exatamente
     * a tela que se usa quando ja se sabe o que procurar: "o que o usuario X fez
     * ontem", "quem excluiu este anexo". A unica navegacao era paginar do mais
     * recente para tras, 30 em 30.</p>
     *
     * <p>Todos os parametros sao opcionais para quem chama {@link
     * br.gov.saude.sgpur.service.AuditoriaService#buscar} (null = nao filtra),
     * mas o {@code AuditoriaService} SEMPRE traduz {@code null} para um valor
     * efetivo (string vazia / sentinelas de data) antes de invocar este
     * metodo — ver o motivo abaixo.</p>
     *
     * <p><b>CORRIGIDO em 2026-08-07 (bug real de producao, confirmado por
     * log): esta consulta usava o padrao {@code :param IS NULL OR ...}, que
     * quebra no PostgreSQL com {@code PSQLException: could not determine
     * data type of parameter} (SQLState 42P18).</b> O parametro {@code :de}
     * aparecia SOMENTE em {@code :de is null}, sem nenhum outro contexto de
     * tipo na mesma ocorrencia posicional (Hibernate 6 gera um {@code ?} por
     * ocorrencia textual do parametro nomeado) — o protocolo estendido do
     * Postgres (Parse/Describe) precisa inferir o tipo de cada {@code ?}
     * ANTES de qualquer valor chegar, e um parametro usado so em
     * {@code IS NULL} nao tem como ter o tipo inferido. Isso derrubava
     * {@code /auditoria} em TODA carga, com ou sem filtro preenchido — o
     * valor de {@code :de} era sempre {@code null} no caso "sem filtro de
     * data", que e o caso comum. O H2 usado nos testes e tolerante a esse
     * padrao, entao o defeito nunca apareceu na suite (mesma classe de
     * armadilha ja documentada no CLAUDE.md para CHECK constraints de enum/
     * {@code @Version}: limpo no H2, quebra no Postgres real).</p>
     *
     * <p><b>Correcao:</b> mesma tecnica ja usada em {@link
     * #buscarParaExportacao} desde a sua criacao — nunca passar {@code null}
     * para esta consulta. {@code AuditoriaService.buscar} converte
     * usuario/acao ausentes para string vazia e data ausente para as
     * sentinelas {@code DATA_MINIMA}/{@code DATA_MAXIMA} (1900-01-01 /
     * 2200-12-31 23:59:59, bem fora de qualquer registro real, mas dentro da
     * faixa representavel de {@code timestamp}). Com isso todo parametro
     * aparece SEMPRE em comparacao com tipo bem definido pela coluna da
     * entidade, nunca isolado num {@code IS NULL} — o Postgres infere o tipo
     * sem ambiguidade.</p>
     */
    @org.springframework.data.jpa.repository.Query("""
        select l from LogAuditoria l
        where (:usuario = '' or lower(l.usuario) like lower(concat('%', :usuario, '%')))
          and (:acao = '' or l.acao = :acao)
          and l.dataHora >= :de
          and l.dataHora <= :ate
        order by l.dataHora desc
        """)
    Page<LogAuditoria> buscar(@org.springframework.data.repository.query.Param("usuario") String usuario,
                              @org.springframework.data.repository.query.Param("acao") String acao,
                              @org.springframework.data.repository.query.Param("de") java.time.LocalDateTime de,
                              @org.springframework.data.repository.query.Param("ate") java.time.LocalDateTime ate,
                              Pageable pageable);

    /** Acoes distintas ja registradas, para alimentar o filtro da tela. */
    @org.springframework.data.jpa.repository.Query("select distinct l.acao from LogAuditoria l order by l.acao")
    java.util.List<String> acoesDistintas();

    /**
     * Busca filtrada da trilha de auditoria para EXPORTACAO (sem paginacao —
     * o CSV exportado precisa de todos os registros do filtro, nao so uma
     * pagina de 30). Mesmos 4 filtros de {@link #buscar}, combinados na MESMA
     * consulta (nunca filtrar em memoria uma lista grande carregada do banco
     * inteiro).
     *
     * <p>Deliberadamente NAO usa o padrao {@code :param IS NULL OR ...} —
     * esse padrao quebra no PostgreSQL com {@code PSQLException: could not
     * determine data type of parameter} quando o parametro de data e usado
     * SOMENTE em {@code :de IS NULL}, sem nenhum outro contexto de tipo na
     * mesma ocorrencia posicional (ver o javadoc completo do bug, e da
     * correcao espelhada, em {@link #buscar}). Esta consulta foi escrita
     * desde o inicio (2026-08-07) do jeito que {@link #buscar} foi corrigido
     * depois para seguir: o service SEMPRE passa valores efetivos (nunca
     * {@code null}) — string vazia para usuario/acao ausentes, sentinelas de
     * data para data ausente — entao cada parametro so aparece em
     * comparacoes com tipo bem definido pelo proprio campo da entidade,
     * nunca um {@code IS NULL} isolado.</p>
     */
    @org.springframework.data.jpa.repository.Query("""
        select l from LogAuditoria l
        where (:usuario = '' or lower(l.usuario) like lower(concat('%', :usuario, '%')))
          and (:acao = '' or l.acao = :acao)
          and l.dataHora >= :de
          and l.dataHora <= :ate
        order by l.dataHora desc
        """)
    java.util.List<LogAuditoria> buscarParaExportacao(
            @org.springframework.data.repository.query.Param("usuario") String usuario,
            @org.springframework.data.repository.query.Param("acao") String acao,
            @org.springframework.data.repository.query.Param("de") java.time.LocalDateTime de,
            @org.springframework.data.repository.query.Param("ate") java.time.LocalDateTime ate);
}

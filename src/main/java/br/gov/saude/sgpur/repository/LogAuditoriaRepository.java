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
     * <p>Todos os parametros sao opcionais (null = nao filtra), entao a mesma
     * consulta serve a listagem completa.</p>
     */
    @org.springframework.data.jpa.repository.Query("""
        select l from LogAuditoria l
        where (:usuario is null or :usuario = ''
               or lower(l.usuario) like lower(concat('%', :usuario, '%')))
          and (:acao is null or :acao = '' or l.acao = :acao)
          and (:de is null or l.dataHora >= :de)
          and (:ate is null or l.dataHora <= :ate)
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
     * <p><b>Deliberadamente NAO usa o padrao {@code :param IS NULL OR ...}
     * de {@link #buscar}.</b> Esse padrao — confirmado por SQL real de
     * producao em 2026-08-07 (ver CLAUDE.md, secao "PENDENTE — erro 500 em
     * /auditoria") — quebra no PostgreSQL com {@code PSQLException: could
     * not determine data type of parameter}: o parametro de data usado
     * SOMENTE em {@code :de IS NULL}, sem nenhum outro contexto de tipo na
     * mesma ocorrencia posicional, chega ao driver sem tipo (OID
     * indeterminado) e o Postgres nao consegue inferir. No H2 (dev/teste)
     * isso nunca aparece — e por isso o defeito escapou da suite antes.</p>
     *
     * <p>Este metodo evita o problema por construcao: o service SEMPRE passa
     * valores efetivos (nunca {@code null}) — string vazia para
     * usuario/acao ausentes, {@link java.time.LocalDateTime#MIN}/
     * {@link java.time.LocalDateTime#MAX} para data ausente — entao cada
     * parametro so aparece em comparacoes com tipo bem definido pelo
     * proprio campo da entidade (nunca um {@code IS NULL} isolado). Isto
     * NAO "corrige" o bug de {@link #buscar} (que continua intocado por
     * pedido explicito do usuario nesta sessao) — e so uma consulta nova,
     * escrita desde o inicio de um jeito que nao reproduz o mesmo
     * problema.</p>
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

package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProcessoRepository extends JpaRepository<Processo, Long> {

    /**
     * Processos encerrados (arquivo): recebe a colecao de status finais
     * (DEFERIDO/INDEFERIDO/CANCELADO), mais recentes primeiro.
     */
    List<Processo> findByStatusInOrderByAnoDescSequencialDesc(java.util.Collection<StatusProcesso> status);

    /** Anos distintos que possuem ao menos um processo, mais recente primeiro. */
    @Query("select distinct p.ano from Processo p order by p.ano desc")
    List<Integer> findAnosComProcessos();

    /**
     * Processos de um ano, ordenados por sequencial, ja com pareceres e medicos
     * (fetch join) para o relatorio anual sem incorrer em N+1.
     *
     * <p><b>Achado A8 da auditoria de 2026-08-27:</b> paciente preemptivo usa
     * uma serie de numeracao SEPARADA ({@code P-NN/AAAA}), entao um "order by
     * sequencial asc" puro intercalava as duas series no mesmo ano (dois "1",
     * dois "2" etc.) - confuso visualmente, mesmo com a coluna "Tipo" ja
     * desambiguando. Agora agrupa por tipo primeiro (urgencia renal comum
     * antes de preemptivo - {@code coalesce(preemptivo,false)} trata o legado
     * NULL como urgencia renal, mesmo criterio ja usado nos filtros/contadores)
     * e so depois ordena por sequencial dentro de cada grupo.</p>
     */
    @Query("""
        select distinct p from Processo p
        left join fetch p.pareceres par
        left join fetch par.membro
        where p.ano = :ano
        order by coalesce(p.preemptivo, false) asc, p.sequencial asc
        """)
    List<Processo> findByAnoComPareceres(@Param("ano") int ano);

    /**
     * Candidatos a decisao automatica pela varredura periodica
     * ({@code DecisaoAutomaticaScheduler}): processos NAO finalizados, nos
     * status informados, que ja tenham ao menos um parecer respondido.
     *
     * <p>Traz {@code pareceres} e {@code membro} por fetch join porque o
     * varredor precisa avaliar o pre-filtro em memoria (voto do coordenador
     * CET-RS) com o processo ja desanexado — sem isso seriam 2 selects extras
     * por processo (N+1) e {@code LazyInitializationException} com
     * {@code open-in-view: false}.</p>
     *
     * <p>O {@code exists} descarta de saida os processos sem nenhum voto (a
     * esmagadora maioria dos "em andamento"): eles nunca teriam maioria e so
     * dariam trabalho ao varredor. O filtro de status fica com o chamador para
     * que a lista de status elegiveis viva num lugar so (o varredor).</p>
     */
    @Query("""
        select distinct p from Processo p
        left join fetch p.pareceres par
        left join fetch par.membro
        where p.status in :status
          and exists (select 1 from Parecer x where x.processo = p and x.resultado is not null)
        order by p.ano asc, p.sequencial asc
        """)
    List<Processo> findCandidatosDecisaoAutomatica(
        @Param("status") java.util.Collection<StatusProcesso> status);

    /**
     * UM processo com {@code pareceres} e {@code membro} ja carregados (fetch
     * join), para a tela de detalhe ({@code ProcessoDetalheController.detalhe})
     * — que navega os pareceres no controller E no template, ja fora de
     * qualquer transacao, com {@code open-in-view: false}.
     *
     * <p><b>Por que so os pareceres e nao tambem os anexos:</b>
     * {@code Processo.pareceres} e {@code Processo.anexos} sao ambos
     * {@code List} (bag) no mapeamento JPA; um {@code left join fetch}
     * simultaneo dos dois na MESMA consulta lanca
     * {@code MultipleBagFetchException} (limitacao do Hibernate). A segunda
     * colecao e inicializada explicitamente pelo chamador, DENTRO da mesma
     * transacao, com um simples {@code getAnexos().size()} (1 SELECT extra,
     * aceitavel para 1 processo so) — sem converter {@code List} para
     * {@code Set} no dominio.</p>
     */
    @Query("""
        select distinct p from Processo p
        left join fetch p.pareceres par
        left join fetch par.membro
        where p.id = :id
        """)
    Optional<Processo> findByIdComPareceres(@Param("id") Long id);

    Optional<Processo> findByNumero(String numero);

    /** Maior sequencial ja usado em um ano (para gerar o proximo numero). */
    @Query("select max(p.sequencial) from Processo p where p.ano = :ano")
    Integer findMaxSequencialByAno(@Param("ano") int ano);

    /**
     * Maior sequencial ja usado em um ano, dentro da serie do TIPO informado
     * (preemptivo ou urgencia renal comum) - as duas series sao
     * independentes (paciente preemptivo, 2026-08-27), numero final
     * "P-NN/AAAA" x "NN/AAAA". <b>Usa {@code coalesce} de proposito</b>: as
     * linhas legadas tem {@code preemptivo = NULL}, que um {@code = false}
     * cru excluiria da contagem da serie de urgencia renal, quebrando a
     * sequencia existente.
     */
    @Query("select max(p.sequencial) from Processo p "
        + "where p.ano = :ano and coalesce(p.preemptivo, false) = :preemptivo")
    Integer findMaxSequencialByAnoEPreemptivo(@Param("ano") int ano, @Param("preemptivo") boolean preemptivo);

    long countByStatus(StatusProcesso status);

    /**
     * Numeros de oficio ja usados num ano (formato NNNN/AAAA). Devolve a lista
     * crua para o chamador comparar NUMERICAMENTE em Java
     * ({@code DecisaoFinalService.proximoNumeroOficio}): um
     * {@code max(p.numeroOficio)} no banco compararia texto, e "999/2026"
     * seria "maior" que "1000/2026". O volume e de poucas dezenas de oficios
     * por ano, entao a lista e minuscula.
     */
    @Query("select p.numeroOficio from Processo p "
        + "where p.numeroOficio is not null and p.numeroOficio like concat('%/', :ano)")
    List<String> findNumerosOficioDoAno(@Param("ano") String ano);

    /**
     * Quantos processos estao DEFERIDOS sem nenhum anexo
     * {@code COMPROVANTE_SNT} - a pendencia que trava a comunicacao oficial ao
     * solicitante (o envio da resposta e bloqueado sem esse documento).
     * Contagem feita no SQL, sem carregar processo/anexo nenhum em memoria.
     */
    @Query("""
        select count(p) from Processo p
        where p.status = br.gov.saude.sgpur.domain.StatusProcesso.DEFERIDO
          and coalesce(p.preemptivo, false) = false
          and not exists (
            select 1 from Anexo a
            where a.processo = p
              and a.tipo = br.gov.saude.sgpur.domain.TipoAnexo.COMPROVANTE_SNT)
        """)
    long contarDeferidosSemComprovanteSnt();

    /**
     * Ids dos processos Deferidos sem comprovante SNT - usado para destacar a
     * pendencia com badge na lista de processos sem incorrer em N+1 (uma
     * consulta so, em vez de navegar {@code getAnexos()} linha a linha).
     */
    @Query("""
        select p.id from Processo p
        where p.status = br.gov.saude.sgpur.domain.StatusProcesso.DEFERIDO
          and coalesce(p.preemptivo, false) = false
          and not exists (
            select 1 from Anexo a
            where a.processo = p
              and a.tipo = br.gov.saude.sgpur.domain.TipoAnexo.COMPROVANTE_SNT)
        """)
    List<Long> findIdsDeferidosSemComprovanteSnt();

    /**
     * Processos Deferidos sem comprovante SNT, mais antigos primeiro. Usado
     * pelo filtro "so pendentes de comprovante SNT" da lista.
     */
    @Query("""
        select p from Processo p
        where p.status = br.gov.saude.sgpur.domain.StatusProcesso.DEFERIDO
          and coalesce(p.preemptivo, false) = false
          and not exists (
            select 1 from Anexo a
            where a.processo = p
              and a.tipo = br.gov.saude.sgpur.domain.TipoAnexo.COMPROVANTE_SNT)
        order by p.dataDecisao asc, p.ano desc, p.sequencial desc
        """)
    List<Processo> findDeferidosSemComprovanteSnt();

    /**
     * Candidatos ao lembrete automatico de comprovante SNT
     * ({@code ComprovanteSntLembreteScheduler}): Deferidos, sem comprovante,
     * decididos ANTES de {@code limiteDecisao} e que nunca foram lembrados ou
     * cujo ultimo lembrete e anterior a {@code limiteLembrete}.
     */
    @Query("""
        select p from Processo p
        where p.status = br.gov.saude.sgpur.domain.StatusProcesso.DEFERIDO
          and coalesce(p.preemptivo, false) = false
          and p.dataDecisao is not null
          and p.dataDecisao < :limiteDecisao
          and (p.ultimoLembreteSntEm is null or p.ultimoLembreteSntEm < :limiteLembrete)
          and not exists (
            select 1 from Anexo a
            where a.processo = p
              and a.tipo = br.gov.saude.sgpur.domain.TipoAnexo.COMPROVANTE_SNT)
        order by p.dataDecisao asc
        """)
    List<Processo> findCandidatosLembreteSnt(
        @Param("limiteDecisao") java.time.LocalDateTime limiteDecisao,
        @Param("limiteLembrete") java.time.LocalDateTime limiteLembrete);

    /**
     * Grava o momento do ultimo lembrete de comprovante SNT enviado para um
     * processo. UPDATE de linha unica (mesmo padrao de
     * {@code ParecerRepository.registrarUltimoLembrete}) para nao disputar o
     * {@code @Version} do processo com nenhuma outra escrita em curso.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update Processo p set p.ultimoLembreteSntEm = :agora where p.id = :processoId")
    int registrarUltimoLembreteSnt(@Param("processoId") Long processoId,
                                   @Param("agora") java.time.LocalDateTime agora);

    /**
     * Lista paginada de /processos. Ativos primeiro, depois "mais recente
     * primeiro" por {@code dataCadastro}.
     *
     * <p><b>Ordenacao por {@code dataCadastro}, NAO por {@code sequencial}</b>
     * (paciente preemptivo, 2026): o preemptivo usa uma serie de numeracao
     * SEPARADA ({@code P-NN/AAAA}) que reinicia em 1 a cada ano, entao um
     * preemptivo recem-criado tem {@code sequencial} baixo e, sob
     * {@code sequencial desc}, aparecia LA NO FIM da lista, fora de ordem
     * cronologica. {@code sequencial} fica so como desempate final. {@code nulls
     * last}: linhas gravadas antes da coluna {@code dataCadastro} existir (todas
     * ja encerradas) vao para o fim do seu ano.</p>
     *
     * <p>Filtro por TIPO (paciente preemptivo): {@code filtrarTipo=false}
     * traz os dois tipos; {@code true} restringe a {@code coalesce(preemptivo,
     * false) = :preemptivo}. Dois booleanos nao-nulos de proposito - o padrao
     * {@code :param is null or ...} nao infere tipo no Postgres (ver CLAUDE.md).</p>
     */
    @Query("""
        select p from Processo p
        where (:status is null or p.status = :status)
          and (:filtrarTipo = false or coalesce(p.preemptivo, false) = :preemptivo)
          and (:q is null or :q = ''
               or lower(p.pacienteNome) like lower(concat('%', :q, '%'))
               or p.numero like concat('%', :q, '%')
               or lower(p.solicitanteEquipe) like lower(concat('%', :q, '%')))
        order by
          case when p.status in (
            br.gov.saude.sgpur.domain.StatusProcesso.DEFERIDO,
            br.gov.saude.sgpur.domain.StatusProcesso.INDEFERIDO,
            br.gov.saude.sgpur.domain.StatusProcesso.CANCELADO) then 1 else 0 end asc,
          p.ano desc, p.dataCadastro desc nulls last, p.sequencial desc
        """)
    Page<Processo> buscar(@Param("q") String q, @Param("status") StatusProcesso status,
                          @Param("filtrarTipo") boolean filtrarTipo, @Param("preemptivo") boolean preemptivo,
                          Pageable pageable);

    /**
     * Inicializa {@code pareceres}+{@code membro} (fetch join) para um lote de
     * processos JA carregados na mesma transacao/persistence context (ex.: a
     * pagina de {@link #buscar}). Nao serve para paginar - o resultado desta
     * consulta e descartado pelo chamador; o efeito util e o Hibernate casar
     * (pelo id, via o identity map da sessao) as colecoes recem-carregadas com
     * as MESMAS instancias gerenciadas de {@code Processo} que ja estao na
     * lista/pagina, evitando 1 select de pareceres por processo (N+1).
     *
     * <p>Query separada de {@code buscar}/{@code buscarEncerrados} de
     * proposito: {@code Page} paginado + {@code left join fetch} de colecao na
     * MESMA consulta faz o Hibernate paginar em memoria (aviso
     * "firstResult/maxResults specified with collection fetch"), quebrando a
     * paginacao real do banco.</p>
     */
    @Query("""
        select distinct p from Processo p
        left join fetch p.pareceres par
        left join fetch par.membro
        where p.id in :ids
        """)
    List<Processo> inicializarPareceresComMembro(@Param("ids") java.util.Collection<Long> ids);

    /**
     * Inicializa {@code anexos} (fetch join) para um lote de processos, mesmo
     * padrao/motivo de {@link #inicializarPareceresComMembro} - consulta
     * separada porque {@code pareceres} e {@code anexos} sao ambos
     * {@code List} (bag): um {@code left join fetch} simultaneo dos dois
     * lancaria {@code MultipleBagFetchException}.
     */
    @Query("""
        select distinct p from Processo p
        left join fetch p.anexos
        where p.id in :ids
        """)
    List<Processo> inicializarAnexos(@Param("ids") java.util.Collection<Long> ids);

    /**
     * Busca paginada dos processos ENCERRADOS (/arquivo).
     *
     * <p>Ate 2026-08-04 o ArquivoController carregava TODOS os encerrados em
     * memoria e filtrava a busca em Java. Funcionava com o volume atual, mas o
     * Arquivo e a unica tela do sistema que so cresce - nada nunca sai dela -,
     * enquanto /processos, limitada pelo trabalho ativo, ja era paginada em 15.
     * O esforco de paginacao estava na tela que nao precisava.</p>
     *
     * <p>Mesmo criterio de busca de {@link #buscar} (paciente, numero, equipe
     * solicitante), agora resolvido no banco.</p>
     */
    @Query("""
        select p from Processo p
        where p.status in :status
          and (:filtrarTipo = false or coalesce(p.preemptivo, false) = :preemptivo)
          and (:q is null or :q = ''
               or lower(p.pacienteNome) like lower(concat('%', :q, '%'))
               or p.numero like concat('%', :q, '%')
               or lower(p.solicitanteEquipe) like lower(concat('%', :q, '%')))
        order by p.ano desc, p.dataCadastro desc nulls last, p.sequencial desc
        """)
    Page<Processo> buscarEncerrados(@Param("q") String q,
                                    @Param("status") java.util.Collection<StatusProcesso> status,
                                    @Param("filtrarTipo") boolean filtrarTipo,
                                    @Param("preemptivo") boolean preemptivo,
                                    Pageable pageable);
}

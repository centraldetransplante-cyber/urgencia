package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SolicitacaoOnlineRepository extends JpaRepository<SolicitacaoOnline, Long> {

    /**
     * Carrega a solicitacao com as associacoes que as telas de DETALHE
     * renderizam (anexos, processo gerado, usuario solicitante). Necessario
     * porque {@code spring.jpa.open-in-view} e {@code false} neste projeto: o
     * Thymeleaf renderiza DEPOIS do commit da transacao do controller, entao
     * qualquer proxy LAZY tocado no template estoura
     * {@code LazyInitializationException} (500). Ver
     * {@code SolicitacaoOnlineService#buscarParaDetalhe}.
     *
     * Sem {@code distinct} de proposito: o fetch join da colecao multiplica as
     * linhas (1 por anexo), mas o Hibernate 6 ja deduplica a raiz sozinho, e o
     * {@code Optional} continua valido com N anexos (coberto por
     * {@code SolicitacaoOnlineDetalheIntegrationTest}). Um
     * {@code select distinct} aqui so adicionaria um DISTINCT sobre todas as
     * colunas das 4 entidades - custo inutil, e quebraria caso alguma delas
     * ganhe no futuro um tipo nao comparavel no Postgres (ex. @Lob/oid).
     */
    @Query("""
        select s from SolicitacaoOnline s
        left join fetch s.anexos
        left join fetch s.processoGerado
        left join fetch s.usuarioSolicitante
        where s.id = :id
        """)
    Optional<SolicitacaoOnline> findParaDetalhe(@Param("id") Long id);

    /**
     * Versao para a tela "Minhas solicitacoes", que agora precisa checar
     * {@code processoGerado.status} (para saber se aguarda informacao
     * complementar) sem estourar N+1 - o fetch join carrega o processo de
     * cada linha na mesma query. Mesmo raciocinio de {@link #findParaDetalhe}.
     */
    @Query("""
        select s from SolicitacaoOnline s
        left join fetch s.processoGerado
        where s.usuarioSolicitante.id = :usuarioId
        order by s.dataEnvio desc
        """)
    List<SolicitacaoOnline> findMinhasParaLista(@Param("usuarioId") Long usuarioId);

    List<SolicitacaoOnline> findByStatusOrderByDataEnvioAsc(StatusSolicitacaoOnline status);

    List<SolicitacaoOnline> findAllByOrderByDataEnvioDesc();

    /**
     * Mesma lista de {@link #findByStatusOrderByDataEnvioAsc}, com busca por
     * paciente, RGCT ou equipe solicitante resolvida no banco (mesmo padrao
     * de {@code ProcessoRepository.buscar}). {@code q} nulo/vazio devolve
     * todas as solicitacoes do status informado.
     */
    @Query("""
        select s from SolicitacaoOnline s
        where s.status = :status
          and (:q is null or :q = ''
               or lower(s.pacienteNome) like lower(concat('%', :q, '%'))
               or lower(s.pacienteRgct) like lower(concat('%', :q, '%'))
               or lower(s.solicitanteEquipe) like lower(concat('%', :q, '%')))
        order by s.dataEnvio asc
        """)
    List<SolicitacaoOnline> buscarPorStatus(@Param("status") StatusSolicitacaoOnline status, @Param("q") String q);

    /**
     * Mesma lista de {@link #findAllByOrderByDataEnvioDesc}, com a mesma
     * busca de {@link #buscarPorStatus}, sem restringir o status (aba
     * "Todas" da triagem).
     */
    @Query("""
        select s from SolicitacaoOnline s
        where (:q is null or :q = ''
               or lower(s.pacienteNome) like lower(concat('%', :q, '%'))
               or lower(s.pacienteRgct) like lower(concat('%', :q, '%'))
               or lower(s.solicitanteEquipe) like lower(concat('%', :q, '%')))
        order by s.dataEnvio desc
        """)
    List<SolicitacaoOnline> buscarTodas(@Param("q") String q);

    long countByStatus(StatusSolicitacaoOnline status);

    /**
     * Quantas solicitacoes um usuario SOLICITANTE ja enviou. Usado por
     * {@code UsuarioService#excluir} para RECUSAR a exclusao com uma mensagem
     * de negocio antes de tentar o DELETE: {@code usuario_solicitante_id} e
     * uma FK NOT NULL sem cascade, entao apagar o usuario com solicitacoes
     * estoura {@code DataIntegrityViolationException}, que o
     * {@code GlobalExceptionHandler} traduz para "revise os campos e tente
     * novamente" - mensagem sem sentido numa exclusao (nao existe campo
     * nenhum) e que nao diz o que realmente aconteceu.
     */
    long countByUsuarioSolicitanteId(Long usuarioSolicitanteId);

    /**
     * Detecta se um {@link br.gov.saude.sgpur.domain.Processo} foi originado
     * do Portal do Solicitante (convertido a partir de uma
     * {@code SolicitacaoOnline}). Usado por {@code FluxoProcessoService} para
     * exibir o link "Ver solicitacao original" no card de Recebimento.
     */
    boolean existsByProcessoGeradoId(Long processoId);

    /**
     * A {@code SolicitacaoOnline} que gerou o processo, se houver. Usado por
     * {@code ProcessoService#excluir} para desvincular o processo antes de
     * apagar - {@code SolicitacaoOnline.processoGerado} e um
     * {@code @ManyToOne} sem cascade/orphanRemoval configurado a partir do
     * {@code Processo}, entao um DELETE direto do processo estoura violacao
     * de FK enquanto essa linha ainda apontar pra ele.
     */
    Optional<SolicitacaoOnline> findByProcessoGeradoId(Long processoId);

    /**
     * Id da {@code SolicitacaoOnline} que gerou o processo, se houver. Usado
     * para linkar de volta a triagem original (ver
     * {@code SolicitacaoOnlineTriagemController}) na tela de detalhe do
     * processo.
     */
    @Query("""
        select s.id from SolicitacaoOnline s
        where s.processoGerado.id = :processoId
        """)
    Optional<Long> findIdByProcessoGeradoId(@Param("processoId") Long processoId);

    /**
     * Nome real de quem enviou a solicitacao (usuario SOLICITANTE), usado
     * como rotulo do "outro lado" nos chats do operador
     * ({@code ProcessoDetalheController}/{@code SolicitacaoOnlineTriagemController}),
     * no lugar do literal generico "Solicitante" que era usado ate
     * 2026-08-07. Projecao direta (nao entidade + navegacao LAZY) porque
     * {@code spring.jpa.open-in-view} e {@code false} neste projeto -
     * tocar {@code s.getUsuarioSolicitante().getNome()} fora da transacao
     * do servico estouraria {@code LazyInitializationException}.
     */
    @Query("""
        select s.usuarioSolicitante.nome from SolicitacaoOnline s
        where s.id = :id
        """)
    Optional<String> findNomeSolicitanteById(@Param("id") Long id);
}

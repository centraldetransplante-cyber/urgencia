package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Usado para impedir a exclusao/desativacao do ultimo ADMIN ativo (auto-lockout). */
    long countByPerfilAndAtivoTrue(Perfil perfil);

    /**
     * Usuarios ativos de um dos perfis informados - usado para notificar
     * ADMIN/OPERADOR quando chega uma nova SolicitacaoOnline (ver
     * SolicitacaoOnlineService).
     */
    List<Usuario> findByPerfilInAndAtivoTrue(List<Perfil> perfis);

    /**
     * Busca por login ou nome, resolvida no banco (mesmo padrao de
     * {@code ProcessoRepository.buscar}). {@code q} nulo/vazio devolve
     * todos. Nunca busca por senha/e-mail de propósito (a tela so mostra
     * login/nome/perfil, e o termo digitado aqui nunca vai para auditoria -
     * ver AuditoriaService, que so recebe id/username em outros pontos).
     */
    @Query("""
        select u from Usuario u
        where (:q is null or :q = ''
               or lower(u.username) like lower(concat('%', :q, '%'))
               or lower(u.nome) like lower(concat('%', :q, '%')))
        order by u.username asc
        """)
    List<Usuario> buscar(@Param("q") String q);

    /**
     * Corrige em BANCO um {@code Usuario} com {@code versao} nula (dado
     * seed/legado de antes do commit que adicionou {@code @Version} a esta
     * entidade, sem o backfill manual documentado no CLAUDE.md ter rodado).
     *
     * <p><b>Por que precisa ser um UPDATE em lote (bulk, via {@code
     * @Modifying}), e nao so {@code usuario.setVersao(0L)} num objeto ja
     * gerenciado:</b> o Hibernate calcula a proxima versao a partir do
     * SNAPSHOT carregado na sessao no momento do fetch (o valor lido do banco,
     * usado tambem na clausula {@code WHERE} do UPDATE de verdade) - nao a
     * partir do valor atual do campo no objeto Java. Setar o campo em memoria
     * NAO muda esse snapshot, entao o Hibernate segue tentando incrementar o
     * {@code null} original no COMMIT e a {@code NullPointerException}
     * continua acontecendo (confirmado por reproducao direta - so mudar o
     * campo no objeto NAO bastou). {@code clearAutomatically = true} descarta
     * o persistence-context inteiro apos este UPDATE, forcando quem chamou a
     * RECARREGAR o {@code Usuario} do banco (ja com {@code versao = 0}) antes
     * de aplicar qualquer mutacao - ver {@code UsuarioService.
     * normalizarVersaoLegada}, que e sempre chamado ANTES de qualquer
     * {@code set...} no objeto, exatamente por causa disso.</p>
     */
    @Modifying(clearAutomatically = true)
    @Query("update Usuario u set u.versao = 0 where u.id = :id and u.versao is null")
    int normalizarVersaoNula(@Param("id") Long id);
}

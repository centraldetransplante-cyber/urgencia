package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.RascunhoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UsuarioService {

    private static final Logger log = LoggerFactory.getLogger(UsuarioService.class);

    private final UsuarioRepository repo;
    private final PasswordEncoder encoder;
    private final MembroUrgenciaRenalRepository membroRepo;
    private final SolicitacaoOnlineRepository solicitacaoRepo;
    private final RascunhoSolicitacaoOnlineRepository rascunhoRepo;

    public UsuarioService(UsuarioRepository repo, PasswordEncoder encoder,
                          MembroUrgenciaRenalRepository membroRepo,
                          SolicitacaoOnlineRepository solicitacaoRepo,
                          RascunhoSolicitacaoOnlineRepository rascunhoRepo) {
        this.repo = repo;
        this.encoder = encoder;
        this.membroRepo = membroRepo;
        this.solicitacaoRepo = solicitacaoRepo;
        this.rascunhoRepo = rascunhoRepo;
    }

    public List<Usuario> listar() {
        return repo.findAll();
    }

    /**
     * Lista com busca por login/nome, resolvida no banco
     * ({@code UsuarioRepository.buscar}). {@code q} nulo/vazio devolve
     * todos, mesmo comportamento de {@link #listar()}.
     */
    public List<Usuario> listar(String q) {
        return repo.buscar(q);
    }

    public Usuario buscar(Long id) {
        return repo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado: " + id));
    }

    /** Cria um novo usuario, codificando a senha. */
    @Transactional
    public Usuario criar(Usuario u, String senhaPura) {
        return criar(u, senhaPura, null);
    }

    /**
     * Cria usuario com membro vinculado (para perfil AVALIADOR).
     * Valida: AVALIADOR exige membroId; ADMIN/OPERADOR nao devem ter membro.
     */
    @Transactional
    public Usuario criar(Usuario u, String senhaPura, Long membroId) {
        return criar(u, senhaPura, membroId, u.getEquipeSolicitante());
    }

    /**
     * Cria usuario com membro vinculado (AVALIADOR) e/ou equipe solicitante
     * (SOLICITANTE). Valida: AVALIADOR exige membroId; SOLICITANTE exige
     * equipeSolicitante; os demais perfis nao devem ter nenhum dos dois.
     */
    @Transactional
    public Usuario criar(Usuario u, String senhaPura, Long membroId, String equipeSolicitante) {
        if (repo.existsByUsername(u.getUsername())) {
            throw new IllegalArgumentException("Ja existe um usuario com este login.");
        }
        u.setId(null);
        SenhaPolicy.validar(senhaPura);
        aplicarMembro(u, membroId);
        aplicarEquipeSolicitante(u, equipeSolicitante);
        u.setSenha(encoder.encode(senhaPura));
        return repo.save(u);
    }

    /** Atualiza dados; troca a senha apenas se 'senhaPura' for informada. */
    @Transactional
    public Usuario atualizar(Long id, Usuario form, String senhaPura) {
        return atualizar(id, form, senhaPura, null);
    }

    /**
     * Atualiza dados com suporte ao membro vinculado (para perfil AVALIADOR).
     */
    @Transactional
    public Usuario atualizar(Long id, Usuario form, String senhaPura, Long membroId) {
        return atualizar(id, form, senhaPura, membroId, form.getEquipeSolicitante());
    }

    /**
     * Atualiza dados com suporte ao membro vinculado (AVALIADOR) e a equipe
     * solicitante (SOLICITANTE).
     *
     * <p><b>Copia campo a campo</b> tudo o que o formulario
     * ({@code templates/usuarios/form.html}) envia: {@code username},
     * {@code nome}, {@code email}, {@code perfil}, {@code ativo}, mais
     * {@code membroId} e {@code equipeSolicitante} (que chegam como
     * {@code @RequestParam}, nao no objeto {@code form}) e a senha, so quando
     * informada. <b>Esquecer um desses = a edicao e descartada em silencio</b>,
     * com mensagem de sucesso na tela - ja aconteceu com {@code email}. O teste
     * {@code UsuarioAtualizacaoIntegrationTest} confere campo a campo relendo
     * do banco, inclusive derivando a lista de campos do proprio HTML.
     *
     * <p><b>Ignorados de proposito</b> (nao "corrigir" numa proxima vistoria):
     * <ul>
     *   <li>{@code form.getSenha()} - o formulario manda a senha em
     *       {@code name="senha"} (parametro {@code senhaPura}), ja codificada
     *       aqui; copiar o campo cru da entidade gravaria texto puro.</li>
     *   <li>{@code form.getId()} - o alvo e o {@code id} do path, nunca o do
     *       corpo do POST (evita editar outro usuario forjando o campo).</li>
     *   <li>{@code form.getMembro()} / {@code form.getEquipeSolicitante()} -
     *       aplicados pelos parametros explicitos, validados por perfil em
     *       {@link #aplicarMembro} / {@link #aplicarEquipeSolicitante}.</li>
     *   <li>{@code versao} ({@code @Version}) - controlado pelo Hibernate.</li>
     * </ul>
     */
    @Transactional
    public Usuario atualizar(Long id, Usuario form, String senhaPura, Long membroId, String equipeSolicitante) {
        Usuario u = normalizarVersaoLegada(buscar(id));
        if (!u.getUsername().equals(form.getUsername())) {
            if (repo.existsByUsername(form.getUsername())) {
                throw new IllegalArgumentException("Ja existe um usuario com este login.");
            }
            u.setUsername(form.getUsername());
        }
        u.setNome(form.getNome());
        u.setEmail(form.getEmail());
        u.setPerfil(form.getPerfil());
        u.setAtivo(form.isAtivo());
        aplicarMembro(u, membroId);
        aplicarEquipeSolicitante(u, equipeSolicitante);
        if (senhaPura != null && !senhaPura.isBlank()) {
            SenhaPolicy.validar(senhaPura);
            u.setSenha(encoder.encode(senhaPura));
        }
        return repo.save(u);
    }

    /**
     * Ativa/desativa o usuario. Bloqueia a operacao (IllegalStateException) quando
     * ela desativaria a propria conta logada ({@code usernameLogado}) ou quando
     * desativaria o ultimo ADMIN ativo do sistema - evita auto-lockout do acesso a
     * /usuarios e /auditoria, ja que o AdminBootstrap so recria o admin inicial
     * quando a tabela 'usuario' esta totalmente vazia.
     */
    @Transactional
    public void alternarAtivo(Long id, String usernameLogado) {
        Usuario u = normalizarVersaoLegada(buscar(id));
        boolean vaiDesativar = u.isAtivo();
        if (vaiDesativar) {
            validarNaoAutoGerenciamento(u, usernameLogado, "desativar");
            validarNaoUltimoAdminAtivo(u, "desativar");
        }
        u.setAtivo(!u.isAtivo());
        repo.save(u);
    }

    /**
     * Exclui o usuario. Bloqueia a operacao (IllegalStateException) quando o alvo e
     * a propria conta logada ({@code usernameLogado}) ou o ultimo ADMIN ativo do
     * sistema - evita auto-lockout do acesso a /usuarios e /auditoria - e tambem
     * quando o usuario ja enviou solicitacoes pelo Portal do Solicitante
     * (ver {@link #validarSemHistoricoDeSolicitacoes}).
     *
     * <p>Um eventual RASCUNHO de solicitacao e apagado junto: e dado de
     * staging descartavel ({@code RascunhoSolicitacaoOnline}, nunca visivel a
     * triagem), diferente das solicitacoes de verdade, que sao historico. Sem
     * isso a FK {@code rascunho_solicitacao_online.usuario_solicitante_id}
     * bloquearia a exclusao de um solicitante que so tinha comecado a
     * preencher um formulario.
     */
    @Transactional
    public void excluir(Long id, String usernameLogado) {
        Usuario u = buscar(id);
        validarNaoAutoGerenciamento(u, usernameLogado, "excluir");
        validarNaoUltimoAdminAtivo(u, "excluir");
        validarSemHistoricoDeSolicitacoes(u);
        rascunhoRepo.deleteByUsuarioSolicitanteId(u.getId());
        repo.delete(u);
    }

    /**
     * Recusa a exclusao de um solicitante que ja enviou pedidos. A FK
     * {@code solicitacao_online.usuario_solicitante_id} e NOT NULL e sem
     * cascade: apagar mesmo assim exigiria apagar junto as solicitacoes (e,
     * por tabela, mensagens, anexos e ate o processo gerado) - destruicao de
     * historico que nunca deve acontecer por um clique em "Excluir". A
     * alternativa correta e INATIVAR o usuario, que ja existe na mesma tela e
     * bloqueia o acesso preservando tudo.
     *
     * <p>Sem esta checagem o DELETE chega ao banco e estoura
     * {@code DataIntegrityViolationException}, exibida ao operador como
     * "os dados informados violam uma regra do banco (...) revise os campos"
     * - generica, e enganosa numa tela de exclusao, onde nao ha campo algum
     * para revisar.
     */
    private void validarSemHistoricoDeSolicitacoes(Usuario u) {
        long solicitacoes = solicitacaoRepo.countByUsuarioSolicitanteId(u.getId());
        if (solicitacoes > 0) {
            throw new IllegalStateException(
                "Nao e possivel excluir o usuario \"" + u.getUsername() + "\": ele possui "
                + solicitacoes + (solicitacoes == 1 ? " solicitacao registrada" : " solicitacoes registradas")
                + " no Portal do Solicitante, e apaga-lo destruiria esse historico. "
                + "Use 'Inativar' para bloquear o acesso dele ao sistema, preservando os pedidos ja enviados.");
        }
    }

    private void validarNaoAutoGerenciamento(Usuario u, String usernameLogado, String acao) {
        if (usernameLogado != null && u.getUsername() != null
                && u.getUsername().equalsIgnoreCase(usernameLogado)) {
            throw new IllegalStateException(
                "Voce nao pode " + acao + " a propria conta. Para trocar sua senha, use 'Minha senha'.");
        }
    }

    private void validarNaoUltimoAdminAtivo(Usuario u, String acao) {
        if (u.getPerfil() == Perfil.ADMIN && u.isAtivo()
                && repo.countByPerfilAndAtivoTrue(Perfil.ADMIN) <= 1) {
            throw new IllegalStateException(
                "Nao e possivel " + acao + " o unico administrador ativo do sistema.");
        }
    }

    /**
     * Permite que o proprio usuario logado troque sua senha, informando a
     * senha atual (verificada) e a nova. Disponivel para todos os perfis
     * (ADMIN/OPERADOR/AVALIADOR), ao contrario da edicao em /usuarios que e
     * exclusiva do ADMIN. Lanca IllegalArgumentException com mensagem amigavel
     * quando a senha atual esta errada ou a nova e invalida.
     */
    @Transactional
    public void alterarPropriaSenha(String username, String senhaAtual,
                                    String novaSenha, String confirmacao) {
        Usuario u = repo.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("Usuario nao encontrado."));
        u = normalizarVersaoLegada(u);
        if (senhaAtual == null || !encoder.matches(senhaAtual, u.getSenha())) {
            throw new IllegalArgumentException("Senha atual incorreta.");
        }
        SenhaPolicy.validar(novaSenha);
        if (!novaSenha.equals(confirmacao)) {
            throw new IllegalArgumentException("A confirmacao nao confere com a nova senha.");
        }
        if (encoder.matches(novaSenha, u.getSenha())) {
            throw new IllegalArgumentException("A nova senha deve ser diferente da atual.");
        }
        u.setSenha(encoder.encode(novaSenha));
        repo.save(u);
    }

    /**
     * Corrige em tempo de execucao um {@code Usuario} carregado com
     * {@code versao} nula - dado seed/legado de antes do commit que adicionou
     * {@code @Version} a esta entidade (2026-07-29), sem o backfill manual
     * (documentado no CLAUDE.md, {@code UPDATE usuario SET versao = 0 WHERE
     * versao IS NULL}) ter rodado no banco em uso (ex.: o arquivo H2 de
     * desenvolvimento de alguem, que persiste entre reinicios e pode ter sido
     * criado antes daquele commit).
     *
     * <p><b>Bug real corrigido (2026-08-08):</b> sem esta normalizacao, salvar
     * um {@code Usuario} com {@code versao == null} nao lanca
     * {@code ObjectOptimisticLockingFailureException} (que o
     * {@code GlobalExceptionHandler} ja trata graciosamente) - lanca uma
     * {@code NullPointerException} CRUA de dentro do Hibernate
     * ({@code org.hibernate.type.descriptor.java.LongJavaType.next}, ao tentar
     * fazer {@code current.longValue()} com {@code current == null} para
     * incrementar a versao no COMMIT), envolvida em
     * {@code TransactionSystemException} - um tipo que nenhum
     * {@code @ExceptionHandler} do projeto reconhecia, resultando em "Erro
     * interno do servidor" (500) para o usuario, reproduzido primeiro em
     * {@code /usuarios/minha-senha} pelo proprio ADMIN seed local.
     *
     * <p><b>Por que NAO basta {@code u.setVersao(0L)} num objeto ja
     * gerenciado</b> (primeira tentativa, tambem confirmada por reproducao
     * direta que NAO corrige o bug): o Hibernate calcula a proxima versao a
     * partir do snapshot carregado na sessao no momento do {@code SELECT}
     * (usado tambem na clausula {@code WHERE} do {@code UPDATE} real para o
     * lock otimista), nao a partir do valor atual do campo no objeto Java -
     * mudar so o campo em memoria nao muda esse snapshot, entao o Hibernate
     * segue tentando incrementar o {@code null} original no commit. A
     * correcao de verdade precisa alcancar o BANCO (via
     * {@code UsuarioRepository.normalizarVersaoNula}, um {@code UPDATE} em
     * lote com {@code clearAutomatically = true}) e depois RECARREGAR a
     * entidade - por isso este metodo devolve um {@code Usuario} (que pode
     * ser uma instancia DIFERENTE da recebida) e precisa ser chamado logo
     * apos o fetch, ANTES de qualquer {@code set...} no objeto original (que
     * seria perdido pelo {@code clearAutomatically}).</p>
     */
    private Usuario normalizarVersaoLegada(Usuario u) {
        if (u.getVersao() != null) {
            return u;
        }
        log.warn("Usuario '{}' (id {}) tinha versao nula (dado legado sem backfill) - "
            + "normalizando para 0 no banco e recarregando antes de qualquer alteracao.",
            u.getUsername(), u.getId());
        repo.normalizarVersaoNula(u.getId());
        return buscar(u.getId());
    }

    /**
     * Aplica a regra de membro vinculado: AVALIADOR exige membro; outros perfis
     * nao devem ter membro (limpa o campo para evitar estado inconsistente).
     */
    private void aplicarMembro(Usuario u, Long membroId) {
        if (u.getPerfil() == Perfil.AVALIADOR) {
            if (membroId == null) {
                throw new IllegalArgumentException(
                    "Perfil Avaliador exige um membro da Urgencia Renal vinculado.");
            }
            MembroUrgenciaRenal membro = membroRepo.findById(membroId)
                .orElseThrow(() -> new IllegalArgumentException(
                    "Membro nao encontrado: " + membroId));
            u.setMembro(membro);
        } else {
            // ADMIN e OPERADOR nao tem membro vinculado
            u.setMembro(null);
        }
    }

    /**
     * Aplica a regra de equipe solicitante: SOLICITANTE exige o campo
     * preenchido (identifica qual equipe o formulario de nova solicitacao
     * online deve pre-preencher); outros perfis nao devem ter equipe
     * vinculada (limpa o campo para evitar estado inconsistente).
     */
    private void aplicarEquipeSolicitante(Usuario u, String equipeSolicitante) {
        if (u.getPerfil() == Perfil.SOLICITANTE) {
            if (equipeSolicitante == null || equipeSolicitante.isBlank()) {
                throw new IllegalArgumentException(
                    "Perfil Solicitante exige o nome da equipe/hospital solicitante.");
            }
            u.setEquipeSolicitante(equipeSolicitante.trim());
        } else {
            u.setEquipeSolicitante(null);
        }
    }
}

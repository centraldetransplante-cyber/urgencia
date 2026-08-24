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
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
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
    private final EmailSenderService emailSenderService;
    private final PasswordResetAttemptService passwordResetAttemptService;
    private final SolicitacaoOnlineRepository solicitacaoRepo;
    private final RascunhoSolicitacaoOnlineRepository rascunhoRepo;
    private final SessionRegistry sessionRegistry;
    private final AuditoriaService auditoriaService;

    public UsuarioService(UsuarioRepository repo, PasswordEncoder encoder,
                          MembroUrgenciaRenalRepository membroRepo,
                          EmailSenderService emailSenderService,
                          PasswordResetAttemptService passwordResetAttemptService,
                          SolicitacaoOnlineRepository solicitacaoRepo,
                          RascunhoSolicitacaoOnlineRepository rascunhoRepo,
                          SessionRegistry sessionRegistry,
                          AuditoriaService auditoriaService) {
        this.repo = repo;
        this.encoder = encoder;
        this.membroRepo = membroRepo;
        this.emailSenderService = emailSenderService;
        this.passwordResetAttemptService = passwordResetAttemptService;
        this.solicitacaoRepo = solicitacaoRepo;
        this.rascunhoRepo = rascunhoRepo;
        this.sessionRegistry = sessionRegistry;
        this.auditoriaService = auditoriaService;
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
        validarSenha(senhaPura);
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
        // Capturados ANTES de qualquer alteracao: a sessao HTTP ja aberta,
        // se existir, esta registrada no SessionRegistry sob o username COM
        // QUE O LOGIN FOI FEITO (o antigo) - se username e ativo/perfil
        // mudarem na MESMA chamada, revogar usando o username NOVO (salvo.
        // getUsername()) simplesmente nao encontra nada no registry e a
        // sessao escapa da revogacao (achado real de revisao, 2026-08-24).
        String usernameAntigo = u.getUsername();
        Perfil perfilAntigo = u.getPerfil();
        boolean estavaAtivo = u.isAtivo();
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
            validarSenha(senhaPura);
            u.setSenha(encoder.encode(senhaPura));
        }
        Usuario salvo = repo.save(u);
        boolean foiDesativado = estavaAtivo && !salvo.isAtivo();
        // Troca de PERFIL (role) tambem precisa revogar a sessao ativa (achado
        // real de revisao, 2026-08-24): sem isso, um usuario rebaixado (ex.
        // ADMIN -> AVALIADOR) continua operando com as authorities ANTIGAS
        // (fixas na Authentication desde o login) ate o timeout de 30min -
        // um problema mesmo permanecendo "ativo".
        boolean perfilMudou = perfilAntigo != salvo.getPerfil();
        if (foiDesativado || perfilMudou) {
            String acao = foiDesativado
                ? "SESSAO_REVOGADA_POR_INATIVACAO"
                : "SESSAO_REVOGADA_POR_MUDANCA_PERFIL";
            String motivo = foiDesativado && perfilMudou
                ? "usuario inativado e perfil alterado de " + perfilAntigo + " para " + salvo.getPerfil()
                : foiDesativado
                    ? "usuario inativado"
                    : "perfil alterado de " + perfilAntigo + " para " + salvo.getPerfil();
            revogarSessoesAtivas(usernameAntigo, acao, motivo);
        }
        return salvo;
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
        // Username com que a sessao HTTP foi aberta - alternarAtivo nunca
        // muda username, entao usar 'u' (nao o retorno de repo.save, que em
        // alguns testes/implementacoes de repositorio pode nao ecoar a
        // mesma instancia) e equivalente e mais robusto.
        String username = u.getUsername();
        repo.save(u);
        if (vaiDesativar) {
            revogarSessoesAtivas(username, "SESSAO_REVOGADA_POR_INATIVACAO", "usuario inativado");
        }
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

    /**
     * Revoga ativamente qualquer sessao HTTP ja aberta sob {@code username},
     * quando o usuario acaba de ser INATIVADO (transicao ativo=true -&gt;
     * false) OU tem o PERFIL alterado. Sem isto, o {@code
     * disabled(!u.isAtivo())} de {@link UsuarioDetailsService} so bloqueia
     * autenticacoes NOVAS - uma sessao ja aberta (Portal do Avaliador, chat,
     * voto) continuava funcionando com as authorities/estado ANTIGOS ate o
     * timeout de inatividade de 30min mesmo com o acesso ja revogado ou o
     * perfil ja rebaixado no cadastro (achados reais de vistoria/revisao,
     * 2026-08-24).
     *
     * <p><b>Sempre chamar com o username COM QUE A SESSAO FOI ABERTA</b> (o
     * antigo, se o proprio username tambem mudou nesta mesma edicao) - nunca
     * o username novo. O principal registrado no {@link SessionRegistry} eh
     * fixado no login e nao acompanha uma troca de username feita depois por
     * um ADMIN; buscar pelo username novo simplesmente nao encontra nada e a
     * sessao escapa da revogacao.</p>
     *
     * <p>Busca percorrendo {@link SessionRegistry#getAllPrincipals()} (nao
     * {@code getAllSessions(username, false)} direto: o principal registrado
     * eh o {@code UserDetails} retornado por {@code UsuarioDetailsService},
     * cujo {@code equals} nao compara igual a uma {@code String} crua). Cada
     * {@link SessionInformation} encontrada eh expirada via
     * {@code expireNow()} - na proxima requisicao autenticada daquele
     * usuario, o {@code ConcurrentSessionFilter} do Spring Security
     * redireciona para o login (comportamento padrao dele para sessao
     * expirada pelo registry, ver {@code SecurityConfig.expiredUrl}), sem
     * exigir nenhum codigo extra aqui.</p>
     *
     * <p>Tolerante a ausencia de sessao (usuario nunca logado, ou sessao ja
     * expirada por outro motivo) - simplesmente nao encontra nada para
     * expirar, sem lancar excecao.</p>
     *
     * @param username        username sob o qual a sessao HTTP foi aberta (o
     *                        ANTIGO, se tiver mudado nesta mesma chamada)
     * @param acaoAuditoria   ex.: {@code "SESSAO_REVOGADA_POR_INATIVACAO"} ou
     *                        {@code "SESSAO_REVOGADA_POR_MUDANCA_PERFIL"}
     * @param detalheMotivo   texto curto explicando o motivo, para o log de auditoria
     */
    private void revogarSessoesAtivas(String username, String acaoAuditoria, String detalheMotivo) {
        try {
            int expiradas = 0;
            for (Object principal : sessionRegistry.getAllPrincipals()) {
                String principalUsername = extrairUsername(principal);
                if (principalUsername != null && principalUsername.equalsIgnoreCase(username)) {
                    for (SessionInformation info : sessionRegistry.getAllSessions(principal, false)) {
                        info.expireNow();
                        expiradas++;
                    }
                }
            }
            if (expiradas > 0) {
                auditoriaService.registrar(acaoAuditoria,
                    "Usuario '" + username + "' - " + detalheMotivo + " - " + expiradas
                    + (expiradas == 1 ? " sessao ativa revogada" : " sessoes ativas revogadas") + ".");
            }
        } catch (Exception e) {
            // Revogacao de sessao eh reforco de seguranca, nunca pode impedir
            // a propria alteracao (que ja bloqueia login novo/vale a partir da
            // proxima autenticacao de qualquer forma).
            log.warn("Falha ao revogar sessoes ativas de '{}': {}", username, e.getMessage());
        }
    }

    private String extrairUsername(Object principal) {
        if (principal instanceof UserDetails ud) {
            return ud.getUsername();
        }
        if (principal instanceof String s) {
            return s;
        }
        return null;
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
     * Redefine a senha do usuario (se existir e tiver e-mail cadastrado) e
     * envia a nova senha temporaria por e-mail - NUNCA expoe a senha em texto
     * puro na tela. Sempre retorna sem lancar excecao, mesmo quando o usuario
     * nao existe ou nao tem e-mail cadastrado, para o chamador poder exibir
     * uma mensagem neutra e evitar enumeracao de usuarios validos. Tambem retorna
     * silenciosamente (sem alterar nada) quando o rate-limit de tentativas de
     * reset para este username foi excedido ({@link PasswordResetAttemptService}) -
     * protege contra "bombear" reset de senha/e-mail de um login conhecido.
     */
    @Transactional
    public void resetarSenha(String username) {
        if (!passwordResetAttemptService.tentarRegistrar(username)) {
            return;
        }
        Usuario u = repo.findByUsername(username).orElse(null);
        if (u == null) {
            log.debug("resetarSenha: usuario '{}' nao encontrado.", username);
            return;
        }
        u = normalizarVersaoLegada(u);
        if (u.getEmail() == null || u.getEmail().isBlank()) {
            log.warn("resetarSenha: usuario '{}' nao tem e-mail cadastrado - "
                + "senha NAO foi alterada. Peca ao ADMIN redefinir manualmente.", username);
            return;
        }
        String novaSenha = gerarSenhaTemporaria();
        // Acentuado em 2026-08-11, junto com EmailTemplateService: e um e-mail
        // institucional visto pelo usuario, mesma regra de redacao (ver o javadoc
        // de EmailTemplateService).
        String corpo = """
            Olá, %s,

            Sua senha de acesso ao SAUR foi redefinida a seu pedido.

            Nova senha temporária: %s

            Recomendamos alterar esta senha após o próximo login.

            Se você não solicitou esta redefinição, entre em contato com o
            administrador do sistema imediatamente.

            Atenciosamente,
            Equipe SAUR - Secretaria de Saúde
            """.formatted(u.getNome(), novaSenha);
        boolean enviado = emailSenderService.enviar(u.getEmail(), "SAUR - Redefinição de senha", corpo);
        if (!enviado) {
            log.warn("resetarSenha: falha ao enviar e-mail para '{}' - senha NAO foi alterada.", username);
            return;
        }
        u.setSenha(encoder.encode(novaSenha));
        repo.save(u);
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
        validarSenha(novaSenha);
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

    private void validarSenha(String senha) {
        if (senha == null || senha.length() < 8) {
            throw new IllegalArgumentException("A senha deve ter ao menos 8 caracteres.");
        }
        if (!senha.matches(".*[A-Z].*")) {
            throw new IllegalArgumentException("A senha deve conter ao menos uma letra maiuscula.");
        }
        if (!senha.matches(".*[a-z].*")) {
            throw new IllegalArgumentException("A senha deve conter ao menos uma letra minuscula.");
        }
        if (!senha.matches(".*\\d.*")) {
            throw new IllegalArgumentException("A senha deve conter ao menos um numero.");
        }
        if (!senha.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*")) {
            throw new IllegalArgumentException("A senha deve conter ao menos um caractere especial.");
        }
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private String gerarSenhaTemporaria() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
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

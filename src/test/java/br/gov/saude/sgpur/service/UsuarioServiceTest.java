package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.RascunhoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cobre o fluxo seguro de "esqueci minha senha": a senha nova NUNCA e
 * exposta em texto puro pelo metodo (o antigo comportamento retornava a
 * senha para a tela mostrar); em vez disso e enviada por e-mail. Tambem
 * cobre os casos sem usuario/sem e-mail cadastrado, que devem ser
 * silenciosos (sem excecao) para nao permitir enumeracao de usuarios.
 */
@ExtendWith(MockitoExtension.class)
class UsuarioServiceTest {

    @Mock private UsuarioRepository repo;
    @Mock private PasswordEncoder encoder;
    @Mock private MembroUrgenciaRenalRepository membroRepo;
    @Mock private EmailSenderService emailSenderService;
    @Mock private SolicitacaoOnlineRepository solicitacaoRepo;
    @Mock private RascunhoSolicitacaoOnlineRepository rascunhoRepo;
    @Mock private org.springframework.security.core.session.SessionRegistry sessionRegistry;
    @Mock private AuditoriaService auditoriaService;

    private PasswordResetAttemptService passwordResetAttemptService;
    private UsuarioService service;

    @BeforeEach
    void setUp() {
        passwordResetAttemptService = new PasswordResetAttemptService();
        service = new UsuarioService(repo, encoder, membroRepo, emailSenderService,
            passwordResetAttemptService, solicitacaoRepo, rascunhoRepo, sessionRegistry, auditoriaService);
    }

    private Usuario usuarioComEmail() {
        Usuario u = new Usuario();
        u.setUsername("operador1");
        u.setNome("Operador Um");
        u.setEmail("operador1@example.com");
        // versao != null: representa um Usuario normal, ja persistido (a
        // situacao real de "versao nula" e legado/seed sem backfill, coberta
        // a parte por UsuarioMinhaSenhaVersaoNulaIntegrationTest com H2 real
        // - aqui, sem isso, o service tentaria "corrigir" um dado que so
        // esta null porque e um POJO de teste nunca persistido).
        u.setVersao(0L);
        return u;
    }

    @Test
    void resetarSenhaEnviaPorEmailSemExporSenhaEmTextoPuro() {
        Usuario u = usuarioComEmail();
        when(repo.findByUsername("operador1")).thenReturn(Optional.of(u));
        when(encoder.encode(any())).thenReturn("hash-fake");
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        service.resetarSenha("operador1");

        verify(repo).save(u);
        assertThat(u.getSenha()).isEqualTo("hash-fake");

        ArgumentCaptor<String> corpoCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSenderService).enviar(eq("operador1@example.com"), anyString(), corpoCaptor.capture());
        // A senha temporaria gerada aparece no corpo do e-mail, nunca em um valor de retorno do metodo.
        assertThat(corpoCaptor.getValue()).contains("Nova senha temporária:");
    }

    @Test
    void resetarSenhaSemUsuarioNaoLancaExcecaoNemEnviaEmail() {
        when(repo.findByUsername("inexistente")).thenReturn(Optional.empty());

        service.resetarSenha("inexistente");

        verifyNoInteractions(emailSenderService);
        verify(repo, never()).save(any());
    }

    @Test
    void resetarSenhaSemEmailCadastradoNaoAlteraSenhaNemEnvia() {
        Usuario u = new Usuario();
        u.setUsername("sememail");
        u.setNome("Sem Email");
        u.setVersao(0L);
        when(repo.findByUsername("sememail")).thenReturn(Optional.of(u));

        service.resetarSenha("sememail");

        verify(repo, never()).save(any());
        verifyNoInteractions(emailSenderService);
    }

    @Test
    void resetarSenhaComFalhaNoEnvioNaoAlteraSenha() {
        Usuario u = usuarioComEmail();
        String senhaOriginal = u.getSenha();
        when(repo.findByUsername("operador1")).thenReturn(Optional.of(u));
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(false);

        service.resetarSenha("operador1");

        verify(repo, never()).save(any());
        assertThat(u.getSenha()).isEqualTo(senhaOriginal);
    }

    @Test
    void resetarSenhaBloqueiaAposExcederLimiteDeTentativasParaOMesmoUsername() {
        Usuario u = usuarioComEmail();
        when(repo.findByUsername("operador1")).thenReturn(Optional.of(u));
        when(encoder.encode(any())).thenReturn("hash-fake");
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        // As 3 primeiras tentativas devem passar (limite = MAX_TENTATIVAS de
        // PasswordResetAttemptService); a partir da 4a, o rate-limit bloqueia
        // silenciosamente - sem exceção, sem novo e-mail, sem nova senha salva.
        service.resetarSenha("operador1");
        service.resetarSenha("operador1");
        service.resetarSenha("operador1");
        verify(repo, times(3)).save(u);
        verify(emailSenderService, times(3)).enviar(anyString(), anyString(), anyString());

        service.resetarSenha("operador1");
        service.resetarSenha("operador1");

        verify(repo, times(3)).save(u);
        verify(emailSenderService, times(3)).enviar(anyString(), anyString(), anyString());
    }

    @Test
    void resetarSenhaRateLimitEIndependentePorUsername() {
        Usuario u1 = usuarioComEmail();
        Usuario u2 = new Usuario();
        u2.setUsername("operador2");
        u2.setNome("Operador Dois");
        u2.setEmail("operador2@example.com");
        u2.setVersao(0L);
        when(repo.findByUsername("operador1")).thenReturn(Optional.of(u1));
        when(repo.findByUsername("operador2")).thenReturn(Optional.of(u2));
        when(encoder.encode(any())).thenReturn("hash-fake");
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        service.resetarSenha("operador1");
        service.resetarSenha("operador1");
        service.resetarSenha("operador1");
        service.resetarSenha("operador1"); // bloqueado

        // operador2 nao foi afetado pelo limite consumido por operador1.
        service.resetarSenha("operador2");

        verify(repo, times(3)).save(u1);
        verify(repo, times(1)).save(u2);
    }

    // ---- Auto-lockout: exclusao/desativacao do ultimo ADMIN ativo ou da propria conta ----

    private Usuario admin(Long id, String username) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setUsername(username);
        u.setNome("Admin " + username);
        u.setPerfil(Perfil.ADMIN);
        u.setAtivo(true);
        return u;
    }

    private Usuario operador(Long id, String username) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setUsername(username);
        u.setNome("Operador " + username);
        u.setPerfil(Perfil.OPERADOR);
        u.setAtivo(true);
        return u;
    }

    @Test
    void unicoAdminAtivoNaoConsegueSeAutoExcluir() {
        Usuario admin = admin(1L, "admin");
        when(repo.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.excluir(1L, "admin"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("propria conta");

        verify(repo, never()).delete(any());
    }

    @Test
    void unicoAdminAtivoNaoConsegueSeAutoDesativar() {
        Usuario admin = admin(1L, "admin");
        when(repo.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.alternarAtivo(1L, "admin"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("propria conta");

        verify(repo, never()).save(any());
        assertThat(admin.isAtivo()).isTrue();
    }

    @Test
    void ultimoAdminAtivoNaoPodeSerExcluidoPorOutroUsuario() {
        Usuario admin = admin(1L, "admin");
        when(repo.findById(1L)).thenReturn(Optional.of(admin));
        when(repo.countByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.excluir(1L, "outro-operador"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unico administrador ativo");

        verify(repo, never()).delete(any());
    }

    @Test
    void ultimoAdminAtivoNaoPodeSerDesativadoPorOutroUsuario() {
        Usuario admin = admin(1L, "admin");
        when(repo.findById(1L)).thenReturn(Optional.of(admin));
        when(repo.countByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.alternarAtivo(1L, "outro-operador"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unico administrador ativo");

        verify(repo, never()).save(any());
        assertThat(admin.isAtivo()).isTrue();
    }

    @Test
    void comDoisAdminsAtivosExcluirUmDelesFunciona() {
        Usuario admin1 = admin(1L, "admin1");
        when(repo.findById(1L)).thenReturn(Optional.of(admin1));
        when(repo.countByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(2L);

        service.excluir(1L, "outro-usuario");

        verify(repo).delete(admin1);
    }

    @Test
    void comDoisAdminsAtivosDesativarUmDelesFunciona() {
        Usuario admin1 = admin(1L, "admin1");
        when(repo.findById(1L)).thenReturn(Optional.of(admin1));
        when(repo.countByPerfilAndAtivoTrue(Perfil.ADMIN)).thenReturn(2L);

        service.alternarAtivo(1L, "outro-usuario");

        verify(repo).save(admin1);
        assertThat(admin1.isAtivo()).isFalse();
    }

    @Test
    void excluirUsuarioNaoAdminSempreFuncionaLivremente() {
        Usuario op = operador(2L, "operador1");
        when(repo.findById(2L)).thenReturn(Optional.of(op));

        service.excluir(2L, "admin");

        verify(repo).delete(op);
    }

    @Test
    void desativarUsuarioNaoAdminSempreFuncionaLivremente() {
        Usuario op = operador(2L, "operador1");
        when(repo.findById(2L)).thenReturn(Optional.of(op));

        service.alternarAtivo(2L, "admin");

        verify(repo).save(op);
        assertThat(op.isAtivo()).isFalse();
    }

    // ---- Perfil SOLICITANTE: equipe obrigatoria + limpeza de vinculo ao trocar de perfil ----

    private Usuario formSolicitante(String equipe) {
        Usuario u = new Usuario();
        u.setUsername("solicitante1");
        u.setNome("Solicitante Um");
        u.setPerfil(br.gov.saude.sgpur.domain.Perfil.SOLICITANTE);
        u.setEquipeSolicitante(equipe);
        return u;
    }

    @Test
    void criarComPerfilSolicitanteSemEquipeLancaExcecao() {
        when(repo.existsByUsername("solicitante1")).thenReturn(false);

        assertThatThrownBy(() -> service.criar(formSolicitante(null), "Senha123!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("equipe/hospital solicitante");
    }

    @Test
    void criarComPerfilSolicitanteComEquipeEmBrancoLancaExcecao() {
        when(repo.existsByUsername("solicitante1")).thenReturn(false);

        assertThatThrownBy(() -> service.criar(formSolicitante("   "), "Senha123!"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("equipe/hospital solicitante");
    }

    @Test
    void criarComPerfilSolicitanteComEquipePreenchidaSalvaComSucesso() {
        when(repo.existsByUsername("solicitante1")).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hash-fake");
        when(repo.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        Usuario salvo = service.criar(formSolicitante("  HCPA  "), "Senha123!");

        // Equipe e sempre trim()ada antes de salvar.
        assertThat(salvo.getEquipeSolicitante()).isEqualTo("HCPA");
    }

    @Test
    void trocaDeAvaliadorParaSolicitanteLimpaOVinculoDeMembroAntigo() {
        Usuario existente = new Usuario();
        existente.setId(5L);
        existente.setUsername("usuario5");
        existente.setPerfil(br.gov.saude.sgpur.domain.Perfil.AVALIADOR);
        br.gov.saude.sgpur.domain.MembroUrgenciaRenal membro =
            new br.gov.saude.sgpur.domain.MembroUrgenciaRenal("HCPA", "Dr. Fulano", "fulano@hcpa.edu.br");
        membro.setId(1L);
        existente.setMembro(membro);
        when(repo.findById(5L)).thenReturn(Optional.of(existente));
        when(repo.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        Usuario form = formSolicitante("HNSC");
        form.setUsername("usuario5"); // mesmo username - nao dispara checagem de duplicidade

        Usuario atualizado = service.atualizar(5L, form, null, null, "HNSC");

        assertThat(atualizado.getMembro()).isNull();
        assertThat(atualizado.getEquipeSolicitante()).isEqualTo("HNSC");
    }

    @Test
    void trocaDeSolicitanteParaOutroPerfilLimpaAEquipeSolicitante() {
        Usuario existente = new Usuario();
        existente.setId(6L);
        existente.setUsername("usuario6");
        existente.setPerfil(br.gov.saude.sgpur.domain.Perfil.SOLICITANTE);
        existente.setEquipeSolicitante("HCPA");
        when(repo.findById(6L)).thenReturn(Optional.of(existente));
        when(repo.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        Usuario form = new Usuario();
        form.setUsername("usuario6");
        form.setNome("Operador Seis");
        form.setPerfil(br.gov.saude.sgpur.domain.Perfil.OPERADOR);

        Usuario atualizado = service.atualizar(6L, form, null, null, null);

        assertThat(atualizado.getEquipeSolicitante()).isNull();
        assertThat(atualizado.getMembro()).isNull();
    }

    // ---- Revogacao de sessao ativa (achados reais de revisao, 2026-08-24) ----

    /** Sessao registrada no SessionRegistry sob um dado username (fake, sem H2/Spring Security real). */
    private org.springframework.security.core.session.SessionInformation sessaoDe(String username, String sessionId) {
        org.springframework.security.core.userdetails.UserDetails principal =
            org.springframework.security.core.userdetails.User.builder()
                .username(username).password("hash").authorities("ROLE_AVALIADOR").build();
        return new org.springframework.security.core.session.SessionInformation(
            principal, sessionId, new java.util.Date());
    }

    /**
     * BYPASS CORRIGIDO (achado real de revisao do PR #120, item 2): editar
     * username E ativo=false na MESMA chamada tem que revogar a sessao
     * registrada sob o username ANTIGO (o que o login usou de fato), nunca o
     * novo - buscar pelo novo simplesmente nao acha nada no registry.
     */
    @Test
    void trocarUsernameEInativarNaMesmaEdicaoRevogaSessaoDoUsernameAntigo() {
        Usuario existente = operador(7L, "usuario-antigo");
        when(repo.findById(7L)).thenReturn(Optional.of(existente));
        when(repo.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));
        var sessaoAntiga = sessaoDe("usuario-antigo", "sessao-1");
        when(sessionRegistry.getAllPrincipals()).thenReturn(java.util.List.of(sessaoAntiga.getPrincipal()));
        when(sessionRegistry.getAllSessions(sessaoAntiga.getPrincipal(), false))
            .thenReturn(java.util.List.of(sessaoAntiga));

        Usuario form = new Usuario();
        form.setUsername("usuario-novo");
        form.setNome("Operador Sete");
        form.setPerfil(Perfil.OPERADOR);
        form.setAtivo(false);
        when(repo.existsByUsername("usuario-novo")).thenReturn(false);

        service.atualizar(7L, form, null, null, null);

        assertThat(sessaoAntiga.isExpired())
            .as("a sessao registrada sob o username ANTIGO tem que ser expirada")
            .isTrue();
        verify(auditoriaService).registrar(eq("SESSAO_REVOGADA_POR_INATIVACAO"), anyString());
    }

    /**
     * MUDANCA DE PERFIL revoga sessao mesmo permanecendo ATIVO (achado real
     * de revisao do PR #120, item 6): sem isso, um usuario rebaixado
     * continua com as authorities ANTIGAS ate o timeout de 30min.
     */
    @Test
    void trocarPerfilComUsuarioAindaAtivoRevogaSessaoAtiva() {
        Usuario existente = admin(8L, "usuario8");
        existente.setAtivo(true);
        when(repo.findById(8L)).thenReturn(Optional.of(existente));
        when(repo.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));
        var sessaoAtiva = sessaoDe("usuario8", "sessao-8");
        when(sessionRegistry.getAllPrincipals()).thenReturn(java.util.List.of(sessaoAtiva.getPrincipal()));
        when(sessionRegistry.getAllSessions(sessaoAtiva.getPrincipal(), false))
            .thenReturn(java.util.List.of(sessaoAtiva));

        Usuario form = new Usuario();
        form.setUsername("usuario8");
        form.setNome("Usuario Oito");
        form.setPerfil(Perfil.OPERADOR); // ADMIN -> OPERADOR: nem membro nem equipe extra exigidos
        form.setAtivo(true);

        service.atualizar(8L, form, null, null, null);

        assertThat(sessaoAtiva.isExpired())
            .as("sessao tem que ser revogada quando o PERFIL muda, mesmo continuando ativo")
            .isTrue();
        verify(auditoriaService).registrar(eq("SESSAO_REVOGADA_POR_MUDANCA_PERFIL"), anyString());
        verify(auditoriaService, never()).registrar(eq("SESSAO_REVOGADA_POR_INATIVACAO"), anyString());
    }

    /** Sem mudanca de username/ativo/perfil, nenhuma revogacao e sequer tentada. */
    @Test
    void semMudancaRelevanteNaoConsultaOSessionRegistry() {
        Usuario existente = operador(9L, "usuario9");
        existente.setNome("Nome Antigo");
        when(repo.findById(9L)).thenReturn(Optional.of(existente));
        when(repo.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        Usuario form = new Usuario();
        form.setUsername("usuario9");
        form.setNome("Nome Novo"); // so o nome muda
        form.setPerfil(Perfil.OPERADOR);
        form.setAtivo(true);

        service.atualizar(9L, form, null, null, null);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(auditoriaService);
    }
}

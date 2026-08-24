package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.PasswordResetService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import br.gov.saude.sgpur.service.UsuarioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Testes de camada HTTP do UsuarioController (cadastro/gestao de logins,
 * troca da propria senha, ativar/desativar/excluir e "esqueci minha senha").
 * A logica de negocio (auto-lockout do ultimo ADMIN, auto-gerenciamento,
 * rate-limit de reset) ja e coberta em UsuarioServiceTest - aqui o foco e
 * bind de request, view/redirect corretos, flash attributes e como as
 * IllegalArgumentException/IllegalStateException do servico viram flash de
 * erro em vez de 500. Restricao de role por URL e responsabilidade do
 * SecurityConfig, ja coberta em SecurityIntegrationTest (@SpringBootTest) -
 * nao repetida aqui (nao funciona de forma confiavel dentro do slice
 * @WebMvcTest).
 */
@WebMvcTest(UsuarioController.class)
class UsuarioControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private UsuarioService service;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private MembroUrgenciaRenalService membroService;
    @MockitoBean private PasswordResetService passwordResetService;
    // Nao usados diretamente pelo UsuarioController, mas exigidos pelo
    // GlobalModelAdvice (@ControllerAdvice global carregado em qualquer
    // slice @WebMvcTest) - sem eles o contexto falha ao subir.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private SolicitacaoOnlineService solicitacaoOnlineService;

    @BeforeEach
    void setUp() {
        when(membroService.listarAtivos()).thenReturn(List.of());
    }

    private Usuario usuario(Long id, String username, Perfil perfil) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setUsername(username);
        u.setNome("Nome " + username);
        u.setEmail(username + "@example.com");
        u.setPerfil(perfil);
        u.setAtivo(true);
        return u;
    }

    // ---- listar / novo / editar ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void listarExibeUsuariosDoServico() throws Exception {
        List<Usuario> usuarios = List.of(usuario(1L, "admin", Perfil.ADMIN), usuario(2L, "operador1", Perfil.OPERADOR));
        when(service.listar(null)).thenReturn(usuarios);

        mvc.perform(get("/usuarios"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/lista"))
            .andExpect(model().attribute("usuarios", usuarios))
            .andExpect(model().attribute("q", (Object) null));
    }

    /**
     * O e-mail de cada usuario precisa aparecer na tela (item pedido pelo
     * usuario, 2026-08-05) - so conferir o model attribute nao pega um
     * template que carregue os dados mas esqueca de exibir a coluna. Renderiza
     * o HTML de verdade. Sem e-mail cadastrado, mostra um traco em vez de
     * string vazia (mais claro que "campo desapareceu").
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void listarExibeOEmailDeCadaUsuarioNaTela() throws Exception {
        Usuario semEmail = usuario(3L, "sememail", Perfil.OPERADOR);
        semEmail.setEmail(null);
        List<Usuario> usuarios = List.of(usuario(1L, "admin", Perfil.ADMIN), semEmail);
        when(service.listar(null)).thenReturn(usuarios);

        mvc.perform(get("/usuarios"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("admin@example.com")))
            .andExpect(content().string(containsString(">E-mail<")));
    }

    /**
     * A busca (item 5 do docs/RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md) e
     * resolvida no banco (UsuarioRepository.buscar) - aqui so confirmamos
     * que o termo digitado chega ao servico e volta ao model, sem cair de
     * volta em listar() sem filtro.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void listarComTermoDeBuscaRepassaAoServicoEAoModel() throws Exception {
        List<Usuario> filtrados = List.of(usuario(1L, "admin", Perfil.ADMIN));
        when(service.listar("adm")).thenReturn(filtrados);

        mvc.perform(get("/usuarios").param("q", "adm"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/lista"))
            .andExpect(model().attribute("usuarios", filtrados))
            .andExpect(model().attribute("q", "adm"));

        verify(service, never()).listar();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void novoExibeFormularioComUsuarioNovoENaoEdicao() throws Exception {
        mvc.perform(get("/usuarios/novo"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/form"))
            .andExpect(model().attribute("edicao", false))
            .andExpect(model().attribute("usuario", instanceOf(Usuario.class)));

        verify(membroService).listarAtivos();
    }

    /**
     * Relatorio de clareza (2026-08-05), item 5.5: o JS que mostra/oculta os
     * campos condicionais por perfil (Membro/Equipe solicitante) estava
     * inline no template, contrariando a convencao do projeto ("JavaScript
     * especifico fica em static/js/*.js, NUNCA inline" - CLAUDE.md). Extraido
     * para static/js/usuario-form.js. Renderiza o template de verdade e
     * confere que o script agora e carregado por src, sem nenhum bloco
     * <script> solto com a logica antiga.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void formularioDeUsuarioCarregaOJsDeCamposCondicionaisPorSrcSemInline() throws Exception {
        String html = mvc.perform(get("/usuarios/novo"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // Recurso com fingerprint de conteudo (ex. /js/usuario-form-<hash>.js) -
        // mesmo padrao dos demais estaticos servidos pelo projeto.
        org.assertj.core.api.Assertions.assertThat(html).containsPattern("src=\"/js/usuario-form(-[0-9a-f]+)?\\.js");
        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("perfilSelect.addEventListener");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void editarExibeFormularioComUsuarioExistenteEEdicaoVerdadeira() throws Exception {
        Usuario existente = usuario(5L, "operador1", Perfil.OPERADOR);
        when(service.buscar(5L)).thenReturn(existente);

        mvc.perform(get("/usuarios/5/editar"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/form"))
            .andExpect(model().attribute("edicao", true))
            .andExpect(model().attribute("usuario", existente));
    }

    // ---- criar (POST /usuarios) ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void criarComDadosValidosRedirecionaComFlashMsgERegistraAuditoria() throws Exception {
        mvc.perform(post("/usuarios")
                .with(csrf())
                .param("username", "novo1")
                .param("nome", "Novo Usuario")
                .param("email", "novo1@example.com")
                .param("perfil", "OPERADOR")
                .param("senha", "segredo123"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("msg", "Usuario criado."));

        verify(service).criar(any(Usuario.class), eq("segredo123"), isNull(), isNull());
        verify(auditoria).registrar(eq("USUARIO_CRIADO"), eq("Usuario novo1"), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void criarComSenhaEmBrancoRetornaFormularioComErroDeCampo() throws Exception {
        mvc.perform(post("/usuarios")
                .with(csrf())
                .param("username", "novo1")
                .param("nome", "Novo Usuario")
                .param("email", "novo1@example.com")
                .param("perfil", "OPERADOR")
                .param("senha", ""))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/form"))
            .andExpect(model().attribute("edicao", false))
            .andExpect(model().attributeHasFieldErrors("usuario", "senha"));

        verify(service, never()).criar(any(), anyString(), any(), any());
        verifyNoInteractions(auditoria);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void criarSemEmailRetornaFormularioComErroDeCampo() throws Exception {
        mvc.perform(post("/usuarios")
                .with(csrf())
                .param("username", "novo1")
                .param("nome", "Novo Usuario")
                .param("email", "")
                .param("perfil", "OPERADOR")
                .param("senha", "segredo123"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/form"))
            .andExpect(model().attributeHasFieldErrors("usuario", "email"));

        verify(service, never()).criar(any(), anyString(), any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void criarComLoginDuplicadoExibeErroDoServicoSemRedirecionar() throws Exception {
        doThrow(new IllegalArgumentException("Ja existe um usuario com este login."))
            .when(service).criar(any(Usuario.class), anyString(), isNull(), isNull());

        mvc.perform(post("/usuarios")
                .with(csrf())
                .param("username", "duplicado")
                .param("nome", "Duplicado")
                .param("email", "duplicado@example.com")
                .param("perfil", "OPERADOR")
                .param("senha", "segredo123"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/form"))
            .andExpect(model().attribute("edicao", false))
            .andExpect(model().attribute("erro", "Ja existe um usuario com este login."));

        verifyNoInteractions(auditoria);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void criarSemTokenCsrfEhRejeitado() throws Exception {
        mvc.perform(post("/usuarios")
                .param("username", "novo1")
                .param("nome", "Novo Usuario")
                .param("email", "novo1@example.com")
                .param("perfil", "OPERADOR")
                .param("senha", "segredo123"))
            .andExpect(status().isForbidden());

        verify(service, never()).criar(any(), anyString(), any(), any());
    }

    // ---- atualizar (POST /usuarios/{id}/editar) ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void atualizarComDadosValidosRedirecionaComFlashMsgERegistraAuditoria() throws Exception {
        mvc.perform(post("/usuarios/5/editar")
                .with(csrf())
                .param("username", "editado1")
                .param("nome", "Editado")
                .param("email", "editado1@example.com")
                .param("perfil", "OPERADOR"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("msg", "Usuario atualizado."));

        verify(service).atualizar(eq(5L), any(Usuario.class), isNull(), isNull(), isNull());
        verify(auditoria).registrar(eq("USUARIO_EDITADO"), eq("Usuario id 5"), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void atualizarSemEmailRetornaFormularioComEdicaoVerdadeira() throws Exception {
        mvc.perform(post("/usuarios/5/editar")
                .with(csrf())
                .param("username", "editado1")
                .param("nome", "Editado")
                .param("email", "")
                .param("perfil", "OPERADOR"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/form"))
            .andExpect(model().attribute("edicao", true))
            .andExpect(model().attributeHasFieldErrors("usuario", "email"));

        verify(service, never()).atualizar(any(), any(), any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void atualizarComLoginDuplicadoRedirecionaParaEdicaoComFlashErro() throws Exception {
        doThrow(new IllegalArgumentException("Ja existe um usuario com este login."))
            .when(service).atualizar(eq(9L), any(Usuario.class), isNull(), isNull(), isNull());

        mvc.perform(post("/usuarios/9/editar")
                .with(csrf())
                .param("username", "conflitante")
                .param("nome", "Conflitante")
                .param("email", "conflitante@example.com")
                .param("perfil", "OPERADOR"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios/9/editar"))
            .andExpect(flash().attribute("erro", "Ja existe um usuario com este login."));

        verifyNoInteractions(auditoria);
    }

    // ---- alternar-ativo (POST /usuarios/{id}/alternar-ativo) ----

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void alternarAtivoComSucessoRedirecionaComFlashMsg() throws Exception {
        mvc.perform(post("/usuarios/3/alternar-ativo").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("msg", "Situacao do usuario atualizada."));

        verify(service).alternarAtivo(3L, "admin1");
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void alternarAtivoBloqueadoPorAutoGerenciamentoExibeFlashErroSemRedirecionarParaErro500() throws Exception {
        // Regra real do servico: o proprio usuario logado nao pode se desativar.
        doThrow(new IllegalStateException(
                "Voce nao pode desativar a propria conta. Para trocar sua senha, use 'Minha senha'."))
            .when(service).alternarAtivo(1L, "admin1");

        mvc.perform(post("/usuarios/1/alternar-ativo").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("erro", containsString("propria conta")));
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void alternarAtivoBloqueadoPorUltimoAdminAtivoExibeFlashErro() throws Exception {
        doThrow(new IllegalStateException("Nao e possivel desativar o unico administrador ativo do sistema."))
            .when(service).alternarAtivo(4L, "admin1");

        mvc.perform(post("/usuarios/4/alternar-ativo").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("erro", containsString("unico administrador ativo")));
    }

    // ---- excluir (POST /usuarios/{id}/excluir) ----

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void excluirComSucessoRedirecionaComFlashMsgERegistraAuditoria() throws Exception {
        mvc.perform(post("/usuarios/7/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("msg", "Usuario excluido."));

        verify(service).excluir(7L, "admin1");
        verify(auditoria).registrar(eq("USUARIO_EXCLUIDO"), eq("Usuario id 7"), any());
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void excluirBloqueadoPorAutoGerenciamentoExibeFlashErroSemAuditar() throws Exception {
        doThrow(new IllegalStateException(
                "Voce nao pode excluir a propria conta. Para trocar sua senha, use 'Minha senha'."))
            .when(service).excluir(1L, "admin1");

        mvc.perform(post("/usuarios/1/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("erro", containsString("propria conta")));

        verify(auditoria, never()).registrar(eq("USUARIO_EXCLUIDO"), anyString());
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void excluirBloqueadoPorUltimoAdminAtivoExibeFlashErroSemAuditar() throws Exception {
        doThrow(new IllegalStateException("Nao e possivel excluir o unico administrador ativo do sistema."))
            .when(service).excluir(4L, "admin1");

        mvc.perform(post("/usuarios/4/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"))
            .andExpect(flash().attribute("erro", containsString("unico administrador ativo")));

        verify(auditoria, never()).registrar(eq("USUARIO_EXCLUIDO"), anyString());
    }

    // ---- minha-senha ----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void minhaSenhaExibeFormulario() throws Exception {
        mvc.perform(get("/usuarios/minha-senha"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/minha-senha"));
    }

    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void trocarMinhaSenhaComSucessoRedirecionaComFlashMsgERegistraAuditoria() throws Exception {
        mvc.perform(post("/usuarios/minha-senha")
                .with(csrf())
                .param("senhaAtual", "atual123")
                .param("novaSenha", "novaSenha123")
                .param("confirmacao", "novaSenha123"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios/minha-senha"))
            .andExpect(flash().attribute("msg", "Senha alterada com sucesso."));

        verify(service).alterarPropriaSenha("operador1", "atual123", "novaSenha123", "novaSenha123");
        verify(auditoria).registrar(eq("SENHA_ALTERADA"), eq("Usuario operador1"), any());
    }

    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void trocarMinhaSenhaComSenhaAtualIncorretaExibeFlashErroSemAuditar() throws Exception {
        doThrow(new IllegalArgumentException("Senha atual incorreta."))
            .when(service).alterarPropriaSenha(eq("operador1"), anyString(), anyString(), anyString());

        mvc.perform(post("/usuarios/minha-senha")
                .with(csrf())
                .param("senhaAtual", "errada")
                .param("novaSenha", "novaSenha123")
                .param("confirmacao", "novaSenha123"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios/minha-senha"))
            .andExpect(flash().attribute("erro", "Senha atual incorreta."));

        verifyNoInteractions(auditoria);
    }

    // ---- esqueci-senha (passo 1: gera token + envia link) ----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void esqueciSenhaExibeFormulario() throws Exception {
        mvc.perform(get("/usuarios/esqueci-senha"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/esqueci-senha"));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void redefinirSenhaSempreExibeMensagemNeutraIndependenteDoUsuarioExistir() throws Exception {
        when(passwordResetService.gerarTokenResetSenha("qualquerLogin")).thenReturn(Optional.empty());

        mvc.perform(post("/usuarios/esqueci-senha")
                .with(csrf())
                .param("username", "qualquerLogin"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/esqueci-senha"))
            .andExpect(model().attribute("sucesso", true))
            .andExpect(model().attribute("msgRedefinicao", containsString("Se o login existir")));

        verify(passwordResetService).gerarTokenResetSenha("qualquerLogin");
        verify(passwordResetService, never()).enviarEmail(any());
        verify(auditoria).registrar(eq("SENHA_RESET_SOLICITADO"), eq("Usuario qualquerLogin"), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void redefinirSenhaComUsuarioValidoGeraTokenEEnviaEmailDepois() throws Exception {
        PasswordResetService.TokenGerado gerado =
            new PasswordResetService.TokenGerado("tok-123", "op1@example.com", "Operador Um");
        when(passwordResetService.gerarTokenResetSenha("operador1")).thenReturn(Optional.of(gerado));

        mvc.perform(post("/usuarios/esqueci-senha")
                .with(csrf())
                .param("username", "operador1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("sucesso", true));

        verify(passwordResetService).gerarTokenResetSenha("operador1");
        verify(passwordResetService).enviarEmail(gerado);
    }

    // ---- redefinir-senha (passo 2: confirma a nova senha a partir do token) ----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void redefinirSenhaFormComTokenValidoExibeFormulario() throws Exception {
        when(passwordResetService.validar("tok-valido")).thenReturn(PasswordResetService.EstadoToken.VALIDO);

        mvc.perform(get("/usuarios/redefinir-senha").param("token", "tok-valido"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/redefinir-senha"))
            .andExpect(model().attribute("token", "tok-valido"))
            .andExpect(model().attributeDoesNotExist("erroToken"));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void redefinirSenhaFormComTokenExpiradoExibeErroGenerico() throws Exception {
        when(passwordResetService.validar("tok-velho")).thenReturn(PasswordResetService.EstadoToken.EXPIRADO);

        mvc.perform(get("/usuarios/redefinir-senha").param("token", "tok-velho"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/redefinir-senha"))
            .andExpect(model().attributeDoesNotExist("token"))
            .andExpect(model().attribute("erroToken", containsString("expirou")));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void redefinirSenhaConfirmarComSucessoRedirecionaParaLoginEAuditaSemExporToken() throws Exception {
        mvc.perform(post("/usuarios/redefinir-senha")
                .with(csrf())
                .param("token", "tok-123")
                .param("novaSenha", "NovaSenha123!")
                .param("confirmacao", "NovaSenha123!"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"))
            .andExpect(flash().attributeExists("msg"));

        verify(passwordResetService).confirmarNovaSenha("tok-123", "NovaSenha123!", "NovaSenha123!");
        verify(auditoria).registrar(eq("SENHA_RESET_CONFIRMADO"), anyString(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void redefinirSenhaConfirmarComTokenJaUsadoReexibeFormComErroGenericoSemAuditar() throws Exception {
        doThrow(new IllegalArgumentException("Este link de redefinição já foi utilizado. Solicite um novo."))
            .when(passwordResetService).confirmarNovaSenha(eq("tok-usado"), anyString(), anyString());
        when(passwordResetService.validar("tok-usado")).thenReturn(PasswordResetService.EstadoToken.JA_USADO);

        mvc.perform(post("/usuarios/redefinir-senha")
                .with(csrf())
                .param("token", "tok-usado")
                .param("novaSenha", "NovaSenha123!")
                .param("confirmacao", "NovaSenha123!"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/redefinir-senha"))
            .andExpect(model().attributeDoesNotExist("token"))
            .andExpect(model().attribute("erroToken", containsString("já foi utilizado")));

        verifyNoInteractions(auditoria);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void redefinirSenhaConfirmarComSenhaFracaReexibeFormComToken() throws Exception {
        doThrow(new IllegalArgumentException("A senha deve ter ao menos 8 caracteres."))
            .when(passwordResetService).confirmarNovaSenha(eq("tok-123"), anyString(), anyString());
        when(passwordResetService.validar("tok-123")).thenReturn(PasswordResetService.EstadoToken.VALIDO);

        mvc.perform(post("/usuarios/redefinir-senha")
                .with(csrf())
                .param("token", "tok-123")
                .param("novaSenha", "abc")
                .param("confirmacao", "abc"))
            .andExpect(status().isOk())
            .andExpect(view().name("usuarios/redefinir-senha"))
            .andExpect(model().attribute("token", "tok-123"))
            .andExpect(model().attribute("erro", containsString("8 caracteres")));

        verifyNoInteractions(auditoria);
    }
}

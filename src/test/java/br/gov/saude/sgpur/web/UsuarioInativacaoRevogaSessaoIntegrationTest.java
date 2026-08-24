package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, sessao HTTP de verdade via
 * login por formulario — nao {@code @WithMockUser}, sem mock de
 * {@code UsuarioService}) para o achado real de vistoria (2026-08-24):
 * inativar um usuario em {@code /usuarios} so bloqueava autenticacoes NOVAS
 * ({@code UsuarioDetailsService.disabled(!u.isAtivo())}) - uma sessao ja
 * aberta continuava funcionando ate o timeout de 30min mesmo com o acesso
 * ja revogado no cadastro.
 *
 * <p><b>Por que precisa ser sessao real, nao {@code @WithMockUser}:</b> o bug
 * (e a correcao) sao sobre o ESTADO do {@link org.springframework.security.core.session.SessionRegistry}
 * entre duas requisicoes distintas (autenticar de verdade, inativar por
 * baixo da sessao ativa, requisitar de novo com a MESMA sessao) - o mesmo
 * padrao ja usado em {@code AvaliadorSessaoOrfaIntegrationTest}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-usuario-inativacao-revoga-sessao;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-usuario-inativacao-revoga-sessao"
})
class UsuarioInativacaoRevogaSessaoIntegrationTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;
    @Autowired
    private PasswordEncoder encoder;

    private static final String USERNAME_AVALIADOR = "avaliador-inativacao-sessao-it";
    private static final String USERNAME_ADMIN = "admin-inativacao-sessao-it";
    private static final String SENHA = "SenhaForte123!";

    private Long avaliadorId;

    @BeforeEach
    void preparar() {
        usuarioRepo.findByUsername(USERNAME_AVALIADOR).ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername(USERNAME_ADMIN).ifPresent(usuarioRepo::delete);

        MembroUrgenciaRenal membro = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HCPA", "Medico Inativacao Sessao", "medico.inativacao.sessao@example.com"));

        Usuario avaliador = new Usuario();
        avaliador.setUsername(USERNAME_AVALIADOR);
        avaliador.setSenha(encoder.encode(SENHA));
        avaliador.setNome("Medico Inativacao Sessao");
        avaliador.setEmail("medico.inativacao.sessao@example.com");
        avaliador.setPerfil(Perfil.AVALIADOR);
        avaliador.setAtivo(true);
        avaliador.setMembro(membro);
        avaliadorId = usuarioRepo.saveAndFlush(avaliador).getId();

        Usuario admin = new Usuario();
        admin.setUsername(USERNAME_ADMIN);
        admin.setSenha(encoder.encode(SENHA));
        admin.setNome("Admin Inativacao Sessao");
        admin.setEmail("admin.inativacao.sessao@example.com");
        admin.setPerfil(Perfil.ADMIN);
        admin.setAtivo(true);
        usuarioRepo.saveAndFlush(admin);
    }

    /**
     * REGRESSAO DO ACHADO: login real do avaliador -> ADMIN inativa a conta
     * dele (por baixo da sessao ativa, via requisicao HTTP de verdade, nao
     * chamada direta ao service) -> a MESMA sessao do avaliador, numa
     * requisicao seguinte, tem que ser redirecionada para o login (nunca
     * continuar servindo o Portal do Avaliador).
     */
    @Test
    void inativarUsuarioComSessaoAtivaRevogaAcessoImediatamente() throws Exception {
        // ---- 1) Login real do avaliador - estabelece uma HttpSession de verdade. ----
        MvcResult loginAvaliador = mvc.perform(post("/login")
                .with(csrf())
                .param("username", USERNAME_AVALIADOR)
                .param("password", SENHA))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/avaliador"))
            .andReturn();

        MockHttpSession sessaoAvaliador = (MockHttpSession) loginAvaliador.getRequest().getSession(false);
        assertThat(sessaoAvaliador).isNotNull();

        // Controle: a sessao acessa o portal normalmente antes da inativacao.
        mvc.perform(get("/avaliador").session(sessaoAvaliador))
            .andExpect(status().isOk());

        // ---- 2) Login real do ADMIN (sessao PROPRIA, distinta) e inativacao do
        // avaliador via requisicao HTTP de verdade ao controller (nao chamada
        // direta ao service - exercita o caminho completo). ----
        MvcResult loginAdmin = mvc.perform(post("/login")
                .with(csrf())
                .param("username", USERNAME_ADMIN)
                .param("password", SENHA))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"))
            .andReturn();
        MockHttpSession sessaoAdmin = (MockHttpSession) loginAdmin.getRequest().getSession(false);

        mvc.perform(post("/usuarios/{id}/alternar-ativo", avaliadorId)
                .with(csrf())
                .session(sessaoAdmin))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/usuarios"));

        assertThat(usuarioRepo.findById(avaliadorId).orElseThrow().isAtivo()).isFalse();

        // ---- 3) MESMA sessao do avaliador (nunca deslogada explicitamente por
        // ele): antes da correcao continuava servindo /avaliador normalmente ate
        // o timeout de 30min. Com a correcao, a sessao foi expirada ativamente
        // no SessionRegistry e a proxima requisicao autenticada cai no login. ----
        mvc.perform(get("/avaliador").session(sessaoAvaliador))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));
    }
}

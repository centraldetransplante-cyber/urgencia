package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, sessao HTTP de verdade via
 * login por formulario — nao {@code @WithMockUser}) do mesmo bug ja corrigido
 * em {@code AvaliadorSessaoOrfaIntegrationTest}, agora em
 * {@link ProcessoDetalheController}: qualquer rota que resolvia o operador
 * logado via {@code usuarioRepo.findByUsername(...).orElseThrow(() -> new
 * ResponseStatusException(UNAUTHORIZED))} devolvia um 401 cru (pagina de erro
 * tecnica) quando a sessao ficava "orfa" — o username gravado na sessao deixa
 * de existir no banco (renomeado ou excluido por um ADMIN) enquanto a sessao
 * continua ativa.
 *
 * <p><b>Por que precisa ser um teste de sessao real, nao {@code
 * @WithMockUser}:</b> o bug e sobre o ESTADO da {@code HttpSession} entre
 * duas requisicoes (autenticar, alterar o banco, requisitar de novo com a
 * MESMA sessao) — {@code @WithMockUser} recria o {@code SecurityContext} do
 * zero a cada metodo de teste, nunca reproduz uma sessao que "ficou para
 * tras". Este teste loga de verdade via {@code POST /login}, captura a
 * {@link MockHttpSession} resultante, corrompe o vinculo no banco e reusa
 * exatamente essa sessao na proxima requisicao.</p>
 *
 * <p>Cobre {@link ProcessoDetalheController#apagarMensagemAjax} como rota
 * representativa: {@code resolverOperador(principal)} e a PRIMEIRA coisa
 * chamada dentro do {@code try}, antes de qualquer acesso a
 * {@code mensagemService.apagar} — nao precisa de nenhuma
 * {@code SolicitacaoOnline}/{@code MensagemSolicitacao} real no banco para
 * expressar o bug, so um {@code Processo} e a sessao orfa.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-processo-detalhe-sessao-orfa;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-processo-detalhe-sessao-orfa"
})
class ProcessoDetalheSessaoOrfaIntegrationTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private br.gov.saude.sgpur.repository.ProcessoRepository processoRepo;
    @Autowired
    private PasswordEncoder encoder;

    private static final String USERNAME_ORIGINAL = "operador-sessao-orfa-it";
    private static final String SENHA = "SenhaForte123!";

    private Long processoId;

    @BeforeEach
    void preparar() {
        usuarioRepo.findByUsername(USERNAME_ORIGINAL).ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername(USERNAME_ORIGINAL + "-renomeado").ifPresent(usuarioRepo::delete);

        Usuario u = new Usuario();
        u.setUsername(USERNAME_ORIGINAL);
        u.setSenha(encoder.encode(SENHA));
        u.setNome("Operador Sessao Orfa");
        u.setEmail("operador.sessao.orfa@example.com");
        u.setPerfil(Perfil.OPERADOR);
        usuarioRepo.saveAndFlush(u);

        br.gov.saude.sgpur.domain.Processo p = new br.gov.saude.sgpur.domain.Processo();
        p.setNumero("1/2026");
        p.setAno(2026);
        p.setSequencial(1);
        p.setPacienteNome("Paciente Sessao Orfa");
        p.setPacienteRgct("111111111");
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(java.time.LocalDate.of(2026, 1, 1));
        p.setStatus(br.gov.saude.sgpur.domain.StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();
    }

    /**
     * REGRESSAO DO BUG: login real -> username muda no banco por baixo da
     * sessao ativa -> a MESMA sessao chamando uma rota AJAX de
     * {@link ProcessoDetalheController} de novo NAO pode devolver 401/500
     * cru; tem que cair num redirect gracioso para /login, com a sessao
     * antiga de fato invalidada (nunca mais reaproveitavel).
     */
    @Test
    void sessaoOrfaAposTrocaDeUsernameCaiParaLoginEmVezDe401Cru() throws Exception {
        // ---- 1) Login de verdade (nao @WithMockUser) - estabelece uma HttpSession real. ----
        MvcResult loginResult = mvc.perform(post("/login")
                .with(csrf())
                .param("username", USERNAME_ORIGINAL)
                .param("password", SENHA))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/")) // perfilSuccessHandler: OPERADOR -> /
            .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.isInvalid()).isFalse();

        // ---- 2) Por baixo da sessao ativa, o ADMIN renomeia o username do operador
        // (mesmo efeito pratico de excluir a conta) - a sessao continua "autenticada"
        // com o username antigo, que nao existe mais no banco. ----
        Usuario usuario = usuarioRepo.findByUsername(USERNAME_ORIGINAL).orElseThrow();
        usuario.setUsername(USERNAME_ORIGINAL + "-renomeado");
        usuarioRepo.saveAndFlush(usuario);

        // ---- 3) MESMA sessao, POST .../mensagem/{id}/apagar/ajax: antes do fix isso
        // estourava ResponseStatusException(UNAUTHORIZED) cru (401 tecnico, sem
        // chance de logar de novo). Com o fix, cai num redirect gracioso. ----
        mvc.perform(post("/processos/" + processoId + "/mensagem/999999/apagar/ajax")
                .with(csrf())
                .session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?erro=sessao-invalida"));

        // A sessao antiga foi REALMENTE invalidada (nao so "esquecida"/ignorada).
        assertThat(session.isInvalid()).isTrue();
    }
}

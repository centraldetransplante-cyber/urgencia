package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, sessao HTTP de verdade via
 * login por formulario — nao {@code @WithMockUser}) da mesma classe de bug
 * ja corrigida em {@code AvaliadorSessaoOrfaIntegrationTest} para o Portal do
 * Avaliador, replicada aqui para a fila de triagem do OPERADOR
 * ({@link SolicitacaoOnlineTriagemController}).
 *
 * <p><b>Causa raiz (identica):</b> o Spring Security nao rele o
 * {@code UserDetails} a cada requisicao — ele fica fixo na sessao desde o
 * login. Se o {@code username} do operador muda (ex.: um ADMIN edita em
 * {@code /usuarios}) enquanto ele tem sessao ativa, a sessao continua
 * "autenticada" com o username antigo, mas
 * {@code UsuarioRepository.findByUsername} nao encontra mais ninguem. Antes
 * desta correcao, cada um dos 5 pontos do controller que resolvia o operador
 * logado estourava {@code ResponseStatusException(HttpStatus.UNAUTHORIZED)}
 * direto — 401 cru, sem chance de logar de novo. Corrigido lancando
 * {@link SessaoInvalidaException}, tratada globalmente por
 * {@code GlobalExceptionHandler#handleSessaoInvalida}.</p>
 *
 * <p>Cobre 2 dos 5 pontos que tinham o padrao antigo — {@code GET
 * .../\{id\}} (detalhe, leitura+escrita via marcarComoLidas) e {@code POST
 * .../\{id\}/mensagem} (escrita) — suficiente para provar que a correcao
 * funciona nos dois estilos de metodo (retorno de view/redirect) usados
 * pelos 5 pontos do controller.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-triagem-sessao-orfa;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.solicitante.habilitado=true",
    "app.anexos.dir=./target/test-anexos-triagem-sessao-orfa"
})
class SolicitacaoOnlineTriagemSessaoOrfaIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired
    private PasswordEncoder encoder;

    private static final String USERNAME_ORIGINAL = "operador-triagem-sessao-orfa-it";
    private static final String SENHA = "SenhaForte123!";

    private Long solicitacaoId;

    @BeforeEach
    @Transactional
    void preparar() {
        usuarioRepo.findByUsername(USERNAME_ORIGINAL).ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername(USERNAME_ORIGINAL + "-renomeado").ifPresent(usuarioRepo::delete);

        Usuario operador = new Usuario();
        operador.setUsername(USERNAME_ORIGINAL);
        operador.setSenha(encoder.encode(SENHA));
        operador.setNome("Operador Sessao Orfa");
        operador.setEmail("operador.sessao.orfa@example.com");
        operador.setPerfil(Perfil.OPERADOR);
        usuarioRepo.saveAndFlush(operador);

        Usuario dono = usuarioRepo.findByUsername("solicitante-triagem-sessao-orfa-it").orElseGet(() -> {
            Usuario u = new Usuario();
            u.setUsername("solicitante-triagem-sessao-orfa-it");
            u.setSenha(encoder.encode(SENHA));
            u.setNome("Equipe Solicitante IT");
            u.setEmail("solicitante-triagem-sessao-orfa-it@example.com");
            u.setPerfil(Perfil.SOLICITANTE);
            u.setEquipeSolicitante("HCPA");
            return usuarioRepo.save(u);
        });

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(dono);
        s.setPacienteNome("Maria Souza da Silva");
        s.setPacienteRgct("123456");
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf("11144477735");
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setSolicitanteEquipe("HCPA");
        s.setSolicitanteEmail("solicitante-triagem-sessao-orfa-it@example.com");
        s.setDataSituacaoEspecial(LocalDate.now());
        s.setJustificativaClinica("Justificativa clinica de teste.");
        solicitacaoRepo.saveAndFlush(s);
        solicitacaoId = s.getId();
    }

    /**
     * REGRESSAO DO BUG: login real -> username muda no banco por baixo da
     * sessao ativa -> a MESMA sessao acessando o detalhe da triagem de novo
     * NAO pode devolver 401/500 cru; tem que cair num redirect gracioso para
     * /login, com a sessao antiga de fato invalidada.
     */
    @Test
    void sessaoOrfaNoDetalheDaTriagemCaiParaLoginEmVezDe401Cru() throws Exception {
        MvcResult loginResult = mvc.perform(post("/login")
                .with(csrf())
                .param("username", USERNAME_ORIGINAL)
                .param("password", SENHA))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.isInvalid()).isFalse();

        // Controle: a sessao recem-criada realmente acessa a tela, prova que o
        // login funcionou de verdade antes de corromper o vinculo.
        mvc.perform(get("/processos/solicitacoes-online/" + solicitacaoId).session(session))
            .andExpect(status().isOk());

        // Por baixo da sessao ativa, o ADMIN renomeia o username do operador
        // (mesmo efeito pratico de excluir a conta).
        Usuario usuario = usuarioRepo.findByUsername(USERNAME_ORIGINAL).orElseThrow();
        usuario.setUsername(USERNAME_ORIGINAL + "-renomeado");
        usuarioRepo.saveAndFlush(usuario);

        // MESMA sessao, nova requisicao ao detalhe: antes do fix isso estourava
        // ResponseStatusException(UNAUTHORIZED) cru. Com o fix, redirect gracioso.
        mvc.perform(get("/processos/solicitacoes-online/" + solicitacaoId).session(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?erro=sessao-invalida"));

        assertThat(session.isInvalid()).isTrue();

        mvc.perform(get("/processos/solicitacoes-online/" + solicitacaoId))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    /**
     * Mesmo cenario, mas no endpoint de ENVIO de mensagem
     * ({@code POST .../mensagem}) — ponto de escrita, cobre o segundo dos 5
     * padroes corrigidos.
     */
    @Test
    void sessaoOrfaAoEnviarMensagemCaiParaLoginEmVezDe401Cru() throws Exception {
        MvcResult loginResult = mvc.perform(post("/login")
                .with(csrf())
                .param("username", USERNAME_ORIGINAL)
                .param("password", SENHA))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        Usuario usuario = usuarioRepo.findByUsername(USERNAME_ORIGINAL).orElseThrow();
        usuario.setUsername(USERNAME_ORIGINAL + "-renomeado");
        usuarioRepo.saveAndFlush(usuario);

        mvc.perform(post("/processos/solicitacoes-online/" + solicitacaoId + "/mensagem")
                .session(session)
                .with(csrf())
                .param("texto", "Ola, tudo bem?"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?erro=sessao-invalida"));

        assertThat(session.isInvalid()).isTrue();
    }
}

package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.PasswordResetToken;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.PasswordResetTokenRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Exclusao de usuario e ESCRITA IRREVERSIVEL: exige teste de integracao com o
 * servico real e H2 real (convencao do CLAUDE.md), mesmo padrao de
 * {@code ExclusaoSolicitanteIntegrationTest}. Um {@code @WebMvcTest}/mock de
 * repositorio nunca pegaria a violacao de FK aqui, porque ela so existe
 * contra um banco de verdade.
 *
 * <p><b>bug_004 (revisao de codigo do PR de reset de senha por token,
 * 2026-08-24):</b> um {@code PasswordResetToken} pendente (usuario pediu
 * "esqueci minha senha" e nunca chegou a abrir o link) bloqueava
 * {@code UsuarioService.excluir} com {@code DataIntegrityViolationException}
 * (FK {@code password_reset_token.usuario_id}) - mesmo bug de origem do caso
 * ja corrigido para {@code RascunhoSolicitacaoOnline}. Corrigido apagando o
 * token pendente junto com o usuario (dado de staging descartavel, nao
 * historico).
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-exclusao-usuario-token-pendente;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-exclusao-usuario-token-pendente"
})
class ExclusaoUsuarioComTokenPendenteIntegrationTest {

    @Autowired
    private UsuarioService usuarioService;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private PasswordResetTokenRepository tokenRepo;
    @Autowired
    private EntityManager em;

    @BeforeEach
    void limpar() {
        tokenRepo.deleteAll();
        usuarioRepo.findByUsername("operador-token-pendente").ifPresent(usuarioRepo::delete);
        usuarioRepo.flush();
    }

    private Usuario criarOperador() {
        Usuario u = new Usuario();
        u.setUsername("operador-token-pendente");
        u.setNome("Operador Token Pendente");
        u.setEmail("operador.token.pendente@example.com");
        u.setSenha("{noop}irrelevante");
        u.setPerfil(Perfil.OPERADOR);
        u.setAtivo(true);
        return usuarioRepo.saveAndFlush(u);
    }

    private void criarTokenPendente(Usuario dono) {
        PasswordResetToken prt = new PasswordResetToken();
        prt.setUsuario(dono);
        prt.setToken("token-pendente-" + dono.getId());
        prt.setDataCriacao(Instant.now());
        prt.setDataExpiracao(Instant.now().plusSeconds(3600));
        prt.setUsado(false);
        tokenRepo.saveAndFlush(prt);
    }

    @Test
    void excluirUsuarioComTokenDeResetPendenteNaoEstouraViolacaoDeFkEApagaOTokenJunto() {
        Usuario operador = criarOperador();
        criarTokenPendente(operador);
        Long id = operador.getId();
        assertThat(tokenRepo.findByToken("token-pendente-" + id)).isPresent();

        assertThatCode(() -> usuarioService.excluir(id, "admin")).doesNotThrowAnyException();

        em.clear();
        assertThat(usuarioRepo.findById(id)).isEmpty();
        assertThat(tokenRepo.findByToken("token-pendente-" + id)).isEmpty();
    }

    @Test
    void excluirUsuarioSemTokenPendenteContinuaFuncionandoNormalmente() {
        Long id = criarOperador().getId();

        usuarioService.excluir(id, "admin");

        em.clear();
        assertThat(usuarioRepo.findById(id)).isEmpty();
    }
}

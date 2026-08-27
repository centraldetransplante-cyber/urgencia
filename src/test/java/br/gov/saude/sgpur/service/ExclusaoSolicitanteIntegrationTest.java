package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.RascunhoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exclusao de usuario e ESCRITA IRREVERSIVEL: exige teste de integracao com o
 * servico real e H2 real (convencao do CLAUDE.md). Um {@code @WebMvcTest} com
 * {@code @MockitoBean} do servico nunca pegaria o que se cobre aqui, porque a
 * violacao de FK so existe contra um banco de verdade.
 *
 * <p>Bug de origem: excluir um SOLICITANTE que ja tinha enviado pedidos
 * estourava {@code DataIntegrityViolationException} (FK
 * {@code solicitacao_online.usuario_solicitante_id}), traduzida pelo
 * {@code GlobalExceptionHandler} na mensagem generica "os dados informados
 * violam uma regra do banco (...) revise os campos e tente novamente" - sem
 * sentido numa exclusao, onde nao ha campo nenhum para revisar.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-exclusao-solicitante;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.solicitante.habilitado=true",
    "app.anexos.dir=./target/test-anexos-exclusao-solicitante"
})
class ExclusaoSolicitanteIntegrationTest {

    @Autowired
    private UsuarioService usuarioService;
    @Autowired
    private RascunhoSolicitacaoOnlineService rascunhoService;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired
    private RascunhoSolicitacaoOnlineRepository rascunhoRepo;
    @Autowired
    private EntityManager em;

    /** Nenhum destes fluxos manda e-mail; o mock so evita SMTP real por acidente. */
    @MockitoBean
    private EmailSenderService emailSenderService;

    @BeforeEach
    void limpar() {
        solicitacaoRepo.deleteAll();
        rascunhoRepo.deleteAll();
        usuarioRepo.findByUsername("solicitante-exclusao").ifPresent(usuarioRepo::delete);
        usuarioRepo.flush();
    }

    private Usuario criarSolicitante() {
        Usuario u = new Usuario();
        u.setUsername("solicitante-exclusao");
        u.setNome("Solicitante Exclusao");
        u.setEmail("solicitante.exclusao@example.com");
        u.setSenha("{noop}irrelevante");
        u.setPerfil(Perfil.SOLICITANTE);
        u.setAtivo(true);
        u.setEquipeSolicitante("HCPA - Nefrologia");
        return usuarioRepo.saveAndFlush(u);
    }

    private void criarSolicitacao(Usuario dono) {
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(dono);
        s.setPacienteNome("Paciente de Teste");
        s.setPacienteRgct("123456789-12345");
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf("11144477735");
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setDataSituacaoEspecial(LocalDate.now().minusDays(1));
        s.setJustificativaClinica("Justificativa clinica.");
        s.setSolicitanteEquipe(dono.getEquipeSolicitante());
        s.setSolicitanteEmail(dono.getEmail());
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        s.setDataEnvio(LocalDateTime.now());
        solicitacaoRepo.saveAndFlush(s);
    }

    @Test
    void excluirSolicitanteComSolicitacoesERecusadoComMensagemDeNegocio() {
        Usuario solicitante = criarSolicitante();
        criarSolicitacao(solicitante);
        Long id = solicitante.getId();

        assertThatThrownBy(() -> usuarioService.excluir(id, "admin"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("solicitante-exclusao")
            .hasMessageContaining("1 solicitacao registrada")
            .hasMessageContaining("Inativar");

        em.clear();
        assertThat(usuarioRepo.findById(id)).isPresent();
        assertThat(solicitacaoRepo.countByUsuarioSolicitanteId(id)).isEqualTo(1);
    }

    /**
     * O plural da mensagem muda com a contagem - o operador le "possui 2
     * solicitacoes registradas", nao "2 solicitacao registrada".
     */
    @Test
    void mensagemDeRecusaConcordaNoPluralComMaisDeUmaSolicitacao() {
        Usuario solicitante = criarSolicitante();
        criarSolicitacao(solicitante);
        criarSolicitacao(solicitante);

        assertThatThrownBy(() -> usuarioService.excluir(solicitante.getId(), "admin"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("2 solicitacoes registradas");
    }

    /**
     * Rascunho e dado de staging descartavel (nunca chega a triagem), entao
     * some junto com o usuario - se a FK do rascunho bloqueasse a exclusao,
     * um solicitante que so abriu o formulario uma vez ficaria impossivel de
     * apagar.
     */
    @Test
    void excluirSolicitanteComApenasRascunhoFuncionaEApagaORascunhoJunto() {
        Usuario solicitante = criarSolicitante();
        rascunhoService.salvar(solicitante.getId(), "Paciente Rascunho", null, null, null, null, null, null, null, null, null);
        Long id = solicitante.getId();
        assertThat(rascunhoRepo.findByUsuarioSolicitanteId(id)).isPresent();

        usuarioService.excluir(id, "admin");

        em.clear();
        assertThat(usuarioRepo.findById(id)).isEmpty();
        assertThat(rascunhoRepo.findByUsuarioSolicitanteId(id)).isEmpty();
    }

    @Test
    void excluirSolicitanteSemNenhumPedidoNemRascunhoFunciona() {
        Long id = criarSolicitante().getId();

        usuarioService.excluir(id, "admin");

        em.clear();
        assertThat(usuarioRepo.findById(id)).isEmpty();
    }
}

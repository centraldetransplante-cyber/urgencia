package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.RascunhoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.RascunhoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contexto real (H2) + servico real: cobre o upsert de rascunho (padrao
 * "reler do banco e conferir campo a campo" do CLAUDE.md) e a exclusao.
 * Nenhum campo do rascunho e obrigatorio - salvar com todos os campos nulos
 * tem que funcionar sem erro (diferente de SolicitacaoOnline, que exige
 * todos os campos no envio final).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-rascunho-solicitacao;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.solicitante.habilitado=true"
})
@Transactional
class RascunhoSolicitacaoOnlineServiceTest {

    @Autowired
    private RascunhoSolicitacaoOnlineService service;
    @Autowired
    private RascunhoSolicitacaoOnlineRepository repository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoOnlineRepository;

    private Usuario criarSolicitante(String username) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setSenha("{noop}x");
        u.setNome("Fulano");
        u.setEmail(username + "@example.com");
        u.setPerfil(Perfil.SOLICITANTE);
        u.setEquipeSolicitante("HCPA");
        return usuarioRepository.save(u);
    }

    @Test
    void salvarCriaRascunhoComCamposParciais() {
        Usuario u = criarSolicitante("rascunho-parcial");

        RascunhoSolicitacaoOnline salvo = service.salvar(
            u.getId(), "Fulano de Tal", null, null, null, null, null, null, null, null, null);

        RascunhoSolicitacaoOnline reler = repository.findById(salvo.getId()).orElseThrow();
        assertThat(reler.getUsuarioSolicitante().getId()).isEqualTo(u.getId());
        assertThat(reler.getPacienteNome()).isEqualTo("Fulano de Tal");
        assertThat(reler.getPacienteRgct()).isNull();
        assertThat(reler.getPacienteDataNascimento()).isNull();
        assertThat(reler.getPacienteCpf()).isNull();
        assertThat(reler.getPacienteSexo()).isNull();
        assertThat(reler.getPacienteNomeMae()).isNull();
        assertThat(reler.getDataSituacaoEspecial()).isNull();
        assertThat(reler.getJustificativaClinica()).isNull();
        assertThat(reler.getEmailAdicional()).isNull();
        assertThat(reler.getAtualizadoEm()).isNotNull();
    }

    @Test
    void salvarDuasVezesFazUpsertSemDuplicarRegistro() {
        Usuario u = criarSolicitante("rascunho-upsert");

        service.salvar(u.getId(), "Nome 1", "RGCT-1", LocalDate.of(1980, 1, 1), "11144477735",
            Sexo.MASCULINO, "Mae 1", LocalDate.of(2026, 1, 1), "Justificativa 1", "primeiro@example.com", null);
        RascunhoSolicitacaoOnline segundo = service.salvar(
            u.getId(), "Nome 2", "RGCT-2", LocalDate.of(1990, 2, 2), "22233344405",
            Sexo.FEMININO, "Mae 2", LocalDate.of(2026, 2, 2), "Justificativa 2", "segundo@example.com", null);

        assertThat(repository.count()).isEqualTo(1);
        RascunhoSolicitacaoOnline reler = repository.findById(segundo.getId()).orElseThrow();
        assertThat(reler.getPacienteNome()).isEqualTo("Nome 2");
        assertThat(reler.getPacienteRgct()).isEqualTo("RGCT-2");
        assertThat(reler.getPacienteDataNascimento()).isEqualTo(LocalDate.of(1990, 2, 2));
        assertThat(reler.getPacienteCpf()).isEqualTo("22233344405");
        assertThat(reler.getPacienteSexo()).isEqualTo(Sexo.FEMININO);
        assertThat(reler.getPacienteNomeMae()).isEqualTo("Mae 2");
        assertThat(reler.getDataSituacaoEspecial()).isEqualTo(LocalDate.of(2026, 2, 2));
        assertThat(reler.getJustificativaClinica()).isEqualTo("Justificativa 2");
        assertThat(reler.getEmailAdicional()).isEqualTo("segundo@example.com");
    }

    @Test
    void buscarPorUsuarioSemRascunhoDevolveVazio() {
        Usuario u = criarSolicitante("rascunho-sem-nada");

        Optional<RascunhoSolicitacaoOnline> resultado = service.buscarPorUsuario(u.getId());

        assertThat(resultado).isEmpty();
    }

    @Test
    void apagarRemoveORascunhoDoUsuario() {
        Usuario u = criarSolicitante("rascunho-apagar");
        service.salvar(u.getId(), "Nome", "RGCT", LocalDate.of(1985, 3, 15), "11144477735",
            Sexo.MASCULINO, null, LocalDate.now(), "Justificativa", null, null);
        assertThat(service.buscarPorUsuario(u.getId())).isPresent();

        service.apagar(u.getId());

        assertThat(service.buscarPorUsuario(u.getId())).isEmpty();
    }

    @Test
    void apagarSemRascunhoExistenteNaoLancaErro() {
        Usuario u = criarSolicitante("rascunho-apagar-inexistente");

        service.apagar(u.getId());

        assertThat(service.buscarPorUsuario(u.getId())).isEmpty();
    }

    /**
     * Um rascunho salvo NUNCA pode aparecer na fila de triagem do operador
     * (SolicitacaoOnlineTriagemController le exclusivamente
     * SolicitacaoOnlineRepository - tabela diferente, entidade diferente).
     * Este teste prova a isolacao no nivel de banco: salvar um rascunho nao
     * cria nenhuma linha em solicitacao_online.
     */
    @Test
    void rascunhoSalvoNuncaApareceNaFilaDeTriagemDoOperador() {
        Usuario u = criarSolicitante("rascunho-nao-vaza-triagem");

        service.salvar(u.getId(), "Paciente Rascunho", "999999999-99999",
            LocalDate.of(1985, 3, 15), "11144477735", Sexo.MASCULINO, null,
            LocalDate.now(), "Justificativa clinica completa do rascunho.", null, null);

        assertThat(solicitacaoOnlineRepository.findAllByOrderByDataEnvioDesc()).isEmpty();
        assertThat(solicitacaoOnlineRepository.countByStatus(
            br.gov.saude.sgpur.domain.StatusSolicitacaoOnline.ENVIADA)).isZero();
    }
}

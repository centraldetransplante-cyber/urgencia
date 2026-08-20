package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.ControleUrgencia;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.domain.SituacaoUrgencia;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de INTEGRACAO (H2 real) da busca no banco adicionada as listas do
 * operador/admin que nao tinham busca nenhuma (item 5 do
 * docs/RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md): Membros, Usuarios,
 * Controle de Urgencias e Solicitacoes online (triagem).
 *
 * <p><b>Por que precisa ser integracao.</b> As 4 consultas novas sao JPQL
 * com {@code like}/{@code lower} - um mock de repositorio devolveria o que o
 * teste mandar, escondendo um erro de sintaxe/campo que so aparece contra um
 * banco de verdade (mesmo raciocinio de
 * {@code ArquivoBuscaPaginadaIntegrationTest}).</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-busca-listas-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-busca-listas-it"
})
class BuscaListasIntegrationTest {

    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private ControleUrgenciaRepository controleRepo;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoRepo;

    @BeforeEach
    void limpar() {
        solicitacaoRepo.deleteAll();
        controleRepo.deleteAll();
        membroRepo.deleteAll();
        // Mantem qualquer usuario ja criado por outro contexto compartilhado
        // (ex. AdminBootstrap) fora do jeito - apagamos so os que este teste cria.
    }

    // ---------- MembroUrgenciaRenalRepository.buscar ----------

    @Test
    void buscaMembroPorNomeInstituicaoOuEmail() {
        membroRepo.save(new MembroUrgenciaRenal("HCPA", "Maria Silva", "maria@hcpa.example.com"));
        membroRepo.save(new MembroUrgenciaRenal("HSL", "Joao Souza", "joao@hsl.example.com"));

        assertThat(membroRepo.buscar("maria"))
            .extracting(MembroUrgenciaRenal::getNome).containsExactly("Maria Silva");
        assertThat(membroRepo.buscar("HSL"))
            .extracting(MembroUrgenciaRenal::getNome).containsExactly("Joao Souza");
        assertThat(membroRepo.buscar("hcpa.example"))
            .extracting(MembroUrgenciaRenal::getNome).containsExactly("Maria Silva");
        assertThat(membroRepo.buscar(null)).hasSize(2);
        assertThat(membroRepo.buscar("")).hasSize(2);
        assertThat(membroRepo.buscar("ninguem com esse nome")).isEmpty();
    }

    // ---------- UsuarioRepository.buscar ----------

    @Test
    void buscaUsuarioPorLoginOuNome() {
        Usuario u1 = criarUsuario("opxyz", "Fulano de Tal", Perfil.OPERADOR);
        Usuario u2 = criarUsuario("admzyx", "Ciclana Beltrana", Perfil.ADMIN);

        try {
            assertThat(usuarioRepo.buscar("opxyz"))
                .extracting(Usuario::getUsername).contains("opxyz");
            assertThat(usuarioRepo.buscar("ciclana"))
                .extracting(Usuario::getUsername).contains("admzyx");
            assertThat(usuarioRepo.buscar(null))
                .extracting(Usuario::getUsername).contains("opxyz", "admzyx");
            assertThat(usuarioRepo.buscar("login-que-nao-existe-em-lugar-nenhum")).isEmpty();
        } finally {
            usuarioRepo.delete(u1);
            usuarioRepo.delete(u2);
        }
    }

    private Usuario criarUsuario(String username, String nome, Perfil perfil) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setNome(nome);
        u.setEmail(username + "@example.com");
        u.setSenha("{noop}irrelevante");
        u.setPerfil(perfil);
        u.setAtivo(true);
        return usuarioRepo.saveAndFlush(u);
    }

    // ---------- ControleUrgenciaRepository.buscarAtivas ----------

    @Test
    void buscaControleUrgenciaPorPacienteRgctOuEquipe() {
        controleRepo.save(new ControleUrgencia("Maria Silva", "RGCT-001", "HCPA",
            "A", SituacaoUrgencia.ATIVA, LocalDate.now().plusDays(30)));
        controleRepo.save(new ControleUrgencia("Joao Souza", "RGCT-002", "HSL",
            "O", SituacaoUrgencia.ATIVA, LocalDate.now().plusDays(30)));
        // inativo: nunca deve aparecer, filtro ou nao
        ControleUrgencia inativo = new ControleUrgencia("Bruna Reis", "RGCT-003", "HCPA",
            "B", SituacaoUrgencia.ATIVA, LocalDate.now().plusDays(30));
        inativo.setAtivo(false);
        controleRepo.save(inativo);

        assertThat(controleRepo.buscarAtivas("maria"))
            .extracting(ControleUrgencia::getNomePaciente).containsExactly("Maria Silva");
        assertThat(controleRepo.buscarAtivas("RGCT-002"))
            .extracting(ControleUrgencia::getNomePaciente).containsExactly("Joao Souza");
        assertThat(controleRepo.buscarAtivas("hsl"))
            .extracting(ControleUrgencia::getEquipe).containsExactly("HSL");
        assertThat(controleRepo.buscarAtivas(null)).hasSize(2);
        assertThat(controleRepo.buscarAtivas("bruna")).isEmpty();
    }

    // ---------- SolicitacaoOnlineRepository.buscarPorStatus / buscarTodas ----------

    @Test
    void buscaSolicitacaoOnlinePorPacienteRgctOuEquipeRespeitandoOStatus() {
        Usuario solicitante = criarSolicitante("solicitante-busca-it");
        try {
            SolicitacaoOnline pendente = solicitacao(solicitante, "Maria Silva", "RGCT-100", "HCPA",
                StatusSolicitacaoOnline.ENVIADA);
            SolicitacaoOnline convertida = solicitacao(solicitante, "Joao Souza", "RGCT-200", "HSL",
                StatusSolicitacaoOnline.CONVERTIDA);
            solicitacaoRepo.save(pendente);
            solicitacaoRepo.save(convertida);

            // buscarPorStatus (aba "Pendentes"): so a ENVIADA aparece, mesmo sem termo
            assertThat(solicitacaoRepo.buscarPorStatus(StatusSolicitacaoOnline.ENVIADA, null))
                .extracting(SolicitacaoOnline::getPacienteNome).containsExactly("Maria Silva");
            assertThat(solicitacaoRepo.buscarPorStatus(StatusSolicitacaoOnline.ENVIADA, "joao"))
                .isEmpty();
            assertThat(solicitacaoRepo.buscarPorStatus(StatusSolicitacaoOnline.ENVIADA, "maria"))
                .extracting(SolicitacaoOnline::getPacienteNome).containsExactly("Maria Silva");

            // buscarTodas (aba "Todas"): os dois status aparecem
            assertThat(solicitacaoRepo.buscarTodas(null))
                .extracting(SolicitacaoOnline::getPacienteNome)
                .containsExactlyInAnyOrder("Maria Silva", "Joao Souza");
            assertThat(solicitacaoRepo.buscarTodas("RGCT-200"))
                .extracting(SolicitacaoOnline::getPacienteNome).containsExactly("Joao Souza");
            assertThat(solicitacaoRepo.buscarTodas("hsl"))
                .extracting(SolicitacaoOnline::getPacienteNome).containsExactly("Joao Souza");
            assertThat(solicitacaoRepo.buscarTodas("nao existe")).isEmpty();
        } finally {
            solicitacaoRepo.deleteAll();
            usuarioRepo.delete(solicitante);
        }
    }

    private Usuario criarSolicitante(String username) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setNome("Solicitante Busca IT");
        u.setEmail(username + "@example.com");
        u.setSenha("{noop}irrelevante");
        u.setPerfil(Perfil.SOLICITANTE);
        u.setAtivo(true);
        u.setEquipeSolicitante("HCPA - Nefrologia");
        return usuarioRepo.saveAndFlush(u);
    }

    private SolicitacaoOnline solicitacao(Usuario solicitante, String paciente, String rgct,
                                          String equipe, StatusSolicitacaoOnline status) {
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(solicitante);
        s.setPacienteNome(paciente);
        s.setPacienteRgct(rgct);
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf("11144477735");
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setSolicitanteEquipe(equipe);
        s.setSolicitanteEmail(solicitante.getEmail());
        s.setDataSituacaoEspecial(LocalDate.now().minusDays(2));
        s.setJustificativaClinica("Justificativa clinica de teste.");
        s.setStatus(status);
        s.setDataEnvio(LocalDateTime.now());
        return s;
    }
}

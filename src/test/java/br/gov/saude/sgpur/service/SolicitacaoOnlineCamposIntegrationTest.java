package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2) do contrato de campos de
 * {@code SolicitacaoOnlineService.criar}.
 *
 * <p>Este servico NAO faz copia campo a campo (a entidade e o proprio objeto do
 * {@code @ModelAttribute}), entao nao esta sujeito ao bug de "esquecer de copiar
 * um campo". O risco aqui e o oposto - alguem "consertar" um dos campos que sao
 * sobrescritos DE PROPOSITO (defesa contra mass assignment). Este teste fixa os
 * dois lados: o que o formulario manda tem que sobreviver, e o que e do sistema
 * tem que continuar sendo imposto pelo servidor.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-solicitacao-campos;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-solicitacao-campos"
})
class SolicitacaoOnlineCamposIntegrationTest {

    @Autowired
    private SolicitacaoOnlineService service;
    @Autowired
    private SolicitacaoOnlineRepository repo;
    @Autowired
    private UsuarioRepository usuarioRepo;

    /** Evita qualquer tentativa de SMTP real na notificacao aos operadores. */
    @MockitoBean
    private EmailSenderService emailSenderService;

    private Usuario solicitante;

    @BeforeEach
    void preparar() {
        repo.deleteAll();
        usuarioRepo.findByUsername("solicitante-campos").ifPresent(usuarioRepo::delete);
        Usuario u = new Usuario();
        u.setUsername("solicitante-campos");
        u.setNome("Solicitante Campos");
        u.setEmail("solicitante.campos@example.com");
        u.setSenha("{noop}irrelevante");
        u.setPerfil(Perfil.SOLICITANTE);
        u.setAtivo(true);
        u.setEquipeSolicitante("HCPA - Nefrologia");
        solicitante = usuarioRepo.saveAndFlush(u);
    }

    @Test
    void camposDoFormularioSobrevivemEOsDoSistemaSaoImpostos() {
        SolicitacaoOnline form = new SolicitacaoOnline();
        // Campos do formulario solicitante/nova.html
        form.setPacienteNome("Paciente do Portal");
        form.setPacienteRgct("123456789-12345");
        form.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        form.setPacienteCpf("11144477735");
        form.setPacienteSexo(Sexo.MASCULINO);
        form.setDataSituacaoEspecial(LocalDate.now().minusDays(2));
        form.setJustificativaClinica("Justificativa clinica detalhada.");
        // Tentativa de mass assignment: nenhum destes pode vencer o servidor
        form.setId(999L);
        form.setSolicitanteEquipe("Equipe Forjada");
        form.setSolicitanteEmail("forjado@example.com");
        form.setStatus(StatusSolicitacaoOnline.APROVADA);
        form.setDataEnvio(LocalDateTime.now().minusYears(1));
        form.setObservacoesTriagem("triagem forjada");

        Long id = service.criar(form, solicitante, null).getId();

        SolicitacaoOnline doBanco = repo.findById(id).orElseThrow();
        assertThat(doBanco.getPacienteNome()).isEqualTo("Paciente do Portal");
        assertThat(doBanco.getPacienteRgct()).isEqualTo("123456789-12345");
        assertThat(doBanco.getDataSituacaoEspecial()).isEqualTo(LocalDate.now().minusDays(2));
        assertThat(doBanco.getJustificativaClinica()).isEqualTo("Justificativa clinica detalhada.");

        assertThat(doBanco.getId()).isNotEqualTo(999L);
        assertThat(doBanco.getSolicitanteEquipe()).isEqualTo("HCPA - Nefrologia");
        assertThat(doBanco.getSolicitanteEmail()).isEqualTo("solicitante.campos@example.com");
        assertThat(doBanco.getStatus()).isEqualTo(StatusSolicitacaoOnline.ENVIADA);
        assertThat(doBanco.getObservacoesTriagem()).isNull();
        assertThat(doBanco.getDataEnvio()).isAfter(LocalDateTime.now().minusMinutes(5));
    }
}

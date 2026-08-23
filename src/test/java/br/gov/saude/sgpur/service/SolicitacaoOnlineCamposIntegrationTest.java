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

    /**
     * <b>Regressao do HOTFIX de 2026-08-22 (producao quebrada):</b> mesma
     * classe de bug documentada em
     * {@code ProcessoAtualizacaoIntegrationTest
     * .processoLegadoComCamposDePacienteNulosAceitaQualquerOutraEscritaSemQuebrar}
     * - aqui do lado de {@code SolicitacaoOnline}. Uma solicitacao LEGADA
     * (criada antes de {@code pacienteDataNascimento}/{@code pacienteCpf}/
     * {@code pacienteSexo} existirem, portanto com os 3 campos NULL) precisa
     * continuar aceitando escrita via {@code devolver}/{@code cancelar}/
     * {@code converter} - metodos que carregam a entidade GERENCIADA e mutam
     * so o status/observacoes, sem tocar nesses 3 campos. Antes da correcao,
     * o flush no commit da transacao validava a entidade INTEIRA e quebrava
     * com {@code ConstraintViolationException}/500 mesmo sem nenhuma relacao
     * com o que estava de fato sendo alterado.
     */
    @Test
    void solicitacaoLegadaComCamposDePacienteNulosAceitaDevolucaoSemQuebrar() {
        SolicitacaoOnline legada = new SolicitacaoOnline();
        legada.setUsuarioSolicitante(solicitante);
        legada.setPacienteNome("Paciente Legado");
        legada.setPacienteRgct("RGCT-LEGADO");
        // Os 3 campos novos ficam NULL de proposito - simula uma
        // SolicitacaoOnline criada ANTES deles existirem.
        legada.setPacienteDataNascimento(null);
        legada.setPacienteCpf(null);
        legada.setPacienteSexo(null);
        legada.setSolicitanteEquipe("HCPA - Nefrologia");
        legada.setSolicitanteEmail("solicitante.campos@example.com");
        legada.setDataSituacaoEspecial(LocalDate.now().minusDays(20));
        legada.setJustificativaClinica("Justificativa antiga.");
        legada.setStatus(StatusSolicitacaoOnline.ENVIADA);
        legada.setDataEnvio(LocalDateTime.now().minusDays(20));
        Long id = repo.saveAndFlush(legada).getId();

        service.devolver(id, "Falta documento X.");

        SolicitacaoOnline doBanco = repo.findById(id).orElseThrow();
        assertThat(doBanco.getStatus()).isEqualTo(StatusSolicitacaoOnline.DEVOLVIDA);
        assertThat(doBanco.getObservacoesTriagem()).isEqualTo("Falta documento X.");
        // Os 3 campos continuam null - a escrita nao inventou dado nenhum.
        assertThat(doBanco.getPacienteDataNascimento()).isNull();
        assertThat(doBanco.getPacienteCpf()).isNull();
        assertThat(doBanco.getPacienteSexo()).isNull();
    }
}

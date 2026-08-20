package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.EmailSenderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, {@code SolicitacaoOnlineService}
 * e {@code ProcessoService} REAIS, com o proxy transacional de verdade) do
 * cancelamento pelo solicitante de um pedido que JA virou {@link Processo}
 * (feature de 2026-07-29, ver CLAUDE.md "Solicitante pode cancelar ate a
 * decisao final").
 *
 * <p><b>Por que nao da para cobrir isso com {@code @WebMvcTest}:</b>
 * {@code SolicitanteControllerTest} mocka {@code SolicitacaoOnlineService}
 * inteiro - nao existe nenhuma transacao real ali, entao o cenario de
 * "escrita principal ja commitada sobrevive a uma falha no pos-processamento"
 * e literalmente inexprimivel: sem o proxy do Spring, nao ha o que fazer
 * rollback-only. Mesmo padrao de {@code AvaliadorVotoTransacaoIntegrationTest}.
 *
 * <p><b>O que este teste protege:</b> {@code SolicitanteController.cancelar}
 * chama {@code solicitacaoService.cancelar(...)} (commita o cancelamento) e
 * SO DEPOIS {@code notificarAvaliadoresCancelamento(...)} (aviso por e-mail
 * aos avaliadores pendentes). O aviso e cortesia, nao pode "descancelar" nada
 * - nem numa falha esperada (SMTP recusa, ja coberto por teste mockado) nem
 * numa falha INESPERADA de verdade, que e o que este teste injeta forcando
 * {@code EmailSenderService.enviar} a lancar uma excecao em vez de so devolver
 * {@code false}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-solicitante-cancelamento-tx;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-solicitante-cancelamento-tx"
})
class SolicitanteCancelamentoTransacaoIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;

    /**
     * Unico ponto mockado: usado dentro de
     * {@code notificarAvaliadoresCancelamento} para avisar o avaliador
     * pendente. Serve de gatilho deterministico para a falha INESPERADA do
     * pos-processamento (ver javadoc da classe) - nunca lanca de verdade na
     * implementacao real (so devolve {@code false}), por isso o mock e
     * necessario para exercitar esse caminho.
     */
    @MockitoBean
    private EmailSenderService emailSenderService;

    private Long solicitacaoId;
    private Long processoId;

    @BeforeEach
    @Transactional
    void preparar() {
        parecerRepo.deleteAll();
        solicitacaoRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();
        usuarioRepo.findByUsername("solicitante-cancel-tx-it").ifPresent(usuarioRepo::delete);

        Usuario dono = new Usuario();
        dono.setUsername("solicitante-cancel-tx-it");
        dono.setSenha("{noop}x");
        dono.setNome("Equipe Cancelamento TX IT");
        dono.setEmail("cancel-tx-it@example.com");
        dono.setPerfil(Perfil.SOLICITANTE);
        dono.setEquipeSolicitante("HCPA");
        usuarioRepo.saveAndFlush(dono);

        Processo p = new Processo();
        p.setNumero("88/2026");
        p.setAno(2026);
        p.setSequencial(88);
        p.setPacienteNome("Paciente Cancelamento TX");
        p.setPacienteRgct("321321321");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("cancel-tx-it@example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.ENVIADO); // em andamento, nao decidido - cancelavel
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        // Avaliador pendente COM e-mail: precisa ter e-mail para
        // notificarAvaliadoresCancelamento tentar de fato enviar (e o mock
        // poder interceptar a chamada e lancar).
        MembroUrgenciaRenal pendente = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HCPA", "Dr. Pendente TX", "pendente-tx@example.com"));
        Parecer parecerPendente = new Parecer(pendente);
        parecerPendente.setProcesso(p);
        parecerPendente.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parecerPendente);

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(dono);
        s.setPacienteNome(p.getPacienteNome());
        s.setPacienteRgct(p.getPacienteRgct());
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf("11144477735");
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setSolicitanteEquipe("HCPA");
        s.setSolicitanteEmail("cancel-tx-it@example.com");
        s.setDataSituacaoEspecial(p.getDataSituacaoEspecial());
        s.setJustificativaClinica("Justificativa de teste.");
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(p);
        solicitacaoRepo.saveAndFlush(s);
        solicitacaoId = s.getId();
    }

    /**
     * O QUE IMPORTA: o cancelamento (Processo.status == CANCELADO,
     * SolicitacaoOnline.status == CANCELADA) esta gravado ANTES de
     * {@code notificarAvaliadoresCancelamento} sequer rodar. Uma excecao
     * inesperada dali NAO pode desfazer isso - o usuario ve flash de aviso,
     * nunca 500, e o processo continua cancelado no banco.
     */
    @Test
    @WithMockUser(username = "solicitante-cancel-tx-it", roles = "SOLICITANTE")
    void cancelamentoSobreviveQuandoAvisoAoAvaliadorFalhaDeFormaInesperada() throws Exception {
        when(emailSenderService.enviar(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("falha inesperada simulada no envio"));

        var resultado = mvc.perform(post("/solicitante/" + solicitacaoId + "/cancelar").with(csrf()))
                .andReturn();

        // A escrita principal sobrevive - e o que este teste protege.
        Processo processoDepois = processoRepo.findById(processoId).orElseThrow();
        assertThat(processoDepois.getStatus()).isEqualTo(StatusProcesso.CANCELADO);
        SolicitacaoOnline solicitacaoDepois = solicitacaoRepo.findById(solicitacaoId).orElseThrow();
        assertThat(solicitacaoDepois.getStatus()).isEqualTo(StatusSolicitacaoOnline.CANCELADA);

        // Nao 500: redirect normal com aviso, nao pagina de erro.
        assertThat(resultado.getResponse().getStatus()).isEqualTo(302);
        assertThat(resultado.getResponse().getRedirectedUrl()).isEqualTo("/solicitante");
        assertThat(resultado.getFlashMap().get("aviso")).isNotNull();
        assertThat(resultado.getFlashMap().get("aviso").toString()).contains("falha inesperada");
    }

    /**
     * Controle: sem a falha injetada, o MESMO cenario cancela de ponta a
     * ponta e avisa o avaliador normalmente - garante que o teste acima falha
     * pelo motivo certo (o aviso realmente e alcancado) e nao por um setup
     * que nunca chegaria la.
     */
    @Test
    @WithMockUser(username = "solicitante-cancel-tx-it", roles = "SOLICITANTE")
    void semFalhaOCancelamentoAvisaOAvaliadorNormalmente() throws Exception {
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        mvc.perform(post("/solicitante/" + solicitacaoId + "/cancelar").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/solicitante"))
                .andExpect(flash().attributeExists("msg"))
                .andExpect(flash().attribute("msg",
                        org.hamcrest.Matchers.containsString("avaliadores pendentes foram avisados")));

        Processo processoDepois = processoRepo.findById(processoId).orElseThrow();
        assertThat(processoDepois.getStatus()).isEqualTo(StatusProcesso.CANCELADO);
    }
}

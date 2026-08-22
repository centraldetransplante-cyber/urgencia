package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.LogAuditoriaRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2 real, {@code ProcessoService}
 * e {@code RegistroEnvioService} REAIS, com o proxy transacional de verdade)
 * que reproduz e comprova a correcao do bug de producao de 2026-08-03:
 *
 * <pre>
 * 17:18:30  CONVITE_AVALIADOR_ENVIADO  Processo 05/2026 - Marcia Abichequer   execucao A
 * 17:18:34  CONVITE_AVALIADOR_ENVIADO  Processo 05/2026 - Ana Lucia
 * 17:18:37  CONVITE_AVALIADOR_ENVIADO  Processo 05/2026 - Veronica Horbe
 * 17:18:33  CONVITE_AVALIADOR_ENVIADO  Processo 05/2026 - Marcia Abichequer   execucao B
 * 17:18:38  CONVITE_AVALIADOR_ENVIADO  Processo 05/2026 - Ana Lucia
 * 17:18:41  CONVITE_AVALIADOR_ENVIADO  Processo 05/2026 - Veronica Horbe
 * </pre>
 *
 * O operador clicou duas vezes em "Registrar envio" (form classico, sem
 * nenhuma trava) e os 3 avaliadores receberam o convite ao Portal 2x.
 *
 * <p><b>Por que precisa ser {@code @SpringBootTest} (nao mock de
 * ProcessoService):</b> a correcao e um {@code @Modifying} de linha unica
 * ({@code ParecerRepository.reivindicarConviteSeElegivel}) chamado dentro de
 * uma transacao real ({@code ProcessoService.reivindicarConviteAvaliador}) -
 * com o service mockado nao existe UPDATE nenhum acontecendo contra um banco
 * de verdade, e o comportamento "duas chamadas -> um so envio" seria
 * inexprimivel (o mock so devolveria o que o teste programasse).</p>
 *
 * <p>Unico ponto mockado: {@code EmailSenderService} (cliente SMTP externo) -
 * nao e o alvo do teste, so precisa confirmar quantas vezes foi chamado.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-convite-duplicidade-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-convite-duplicidade-it"
})
class ConviteAvaliadorDuplicidadeIntegrationTest {

    @Autowired
    private RegistroEnvioService registroEnvioService;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;
    @Autowired
    private LogAuditoriaRepository auditoriaRepo;

    @MockitoBean
    private EmailSenderService emailSenderService;

    private Long processoId;
    private Long parecerId;

    @BeforeEach
    @Transactional
    void preparar() {
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        Processo p = new Processo();
        p.setNumero("05/2026");
        p.setAno(2026);
        p.setSequencial(5);
        p.setPacienteNome("Paciente Teste Convite Duplicado");
        p.setPacienteRgct("555555555");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        MembroUrgenciaRenal membro = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HCPA", "Marcia Abichequer", "marcia@example.com"));

        Parecer parecer = new Parecer(membro);
        parecer.setProcesso(p);
        parecer.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parecer);
        parecerId = parecer.getId();
    }

    /**
     * REGRESSAO DO BUG: chamar enviarConvitesAvaliadores duas vezes seguidas
     * (simulando o duplo-clique / duplo-POST real) manda o e-mail UMA SO VEZ.
     * A 2a chamada e ignorada como duplicata, registrada em auditoria propria,
     * e NAO aparece como erro/aviso para o operador.
     */
    @Test
    void duasChamadasSeguidasEnviamOConviteUmaVezSo() {
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        RegistroEnvioService.ConvitesResultado primeira = registroEnvioService.enviarConvitesAvaliadores(processoId);
        RegistroEnvioService.ConvitesResultado segunda = registroEnvioService.enviarConvitesAvaliadores(processoId);

        assertThat(primeira.enviados()).isEqualTo(1);
        assertThat(primeira.avisos()).isEmpty();

        // A segunda chamada nao manda e-mail de novo, e isso NAO e um erro:
        // nao aparece nem em enviados nem em avisos.
        assertThat(segunda.enviados()).isZero();
        assertThat(segunda.avisos()).isEmpty();

        // O e-mail de convite so saiu 1 vez no total, nao 2.
        verify(emailSenderService, times(1)).enviar(anyString(), anyString(), anyString());

        // A duplicidade fica observavel na auditoria (nao silenciosa).
        boolean ignoradoRegistrado = auditoriaRepo.findAll().stream()
                .anyMatch(l -> l.getAcao().equals("CONVITE_AVALIADOR_IGNORADO_DUPLICADO")
                        && l.getDetalhe() != null && l.getDetalhe().contains("05/2026"));
        assertThat(ignoradoRegistrado).isTrue();

        // O parecer sobrevive intacto - a trava so afeta o convite, nunca o
        // registro do envio em si (dataEnvio continua a mesma).
        Parecer depois = parecerRepo.findById(parecerId).orElseThrow();
        assertThat(depois.getDataEnvio()).isEqualTo(LocalDate.of(2026, 5, 2));
        assertThat(depois.getConviteEnviadoEm()).isNotNull();
    }

    /**
     * Reenvio LEGITIMO (fora da janela de duplo-clique, ex.: processo reaberto
     * e reenviado horas depois): volta a mandar o convite normalmente. Sem
     * esperar minutos de verdade no teste, o relogio e "adiantado" mexendo
     * direto em {@code Parecer.conviteEnviadoEm} para simular que o ultimo
     * envio foi ha mais de 5 minutos.
     */
    @Test
    void reenvioAposAJanelaVoltaAEnviarNormalmente() {
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        RegistroEnvioService.ConvitesResultado primeira = registroEnvioService.enviarConvitesAvaliadores(processoId);
        assertThat(primeira.enviados()).isEqualTo(1);

        // Simula que o convite foi enviado ha 10 minutos (fora da janela de 5).
        Parecer parecer = parecerRepo.findById(parecerId).orElseThrow();
        parecer.setConviteEnviadoEm(LocalDateTime.now().minusMinutes(10));
        parecerRepo.saveAndFlush(parecer);

        RegistroEnvioService.ConvitesResultado depoisDaJanela = registroEnvioService.enviarConvitesAvaliadores(processoId);

        assertThat(depoisDaJanela.enviados()).isEqualTo(1);
        assertThat(depoisDaJanela.avisos()).isEmpty();
        verify(emailSenderService, times(2)).enviar(anyString(), anyString(), anyString());
    }
}

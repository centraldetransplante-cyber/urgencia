package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, {@code ProcessoService}/
 * {@code ProcessoRepository} REAIS) do varredor de cobranca do Comprovante
 * SNT, complementando {@link ComprovanteSntLembreteSchedulerTest} (que usa
 * mocks e cobre a logica de orquestracao/isolamento de falha do
 * {@code ComprovanteSntLembreteScheduler} isoladamente).
 *
 * <p><b>Por que precisa ser {@code @SpringBootTest} e nao um teste de
 * unidade:</b> a query real
 * {@code ProcessoRepository.findCandidatosLembreteSnt} (prazo + exclusao de
 * processo ja com {@code COMPROVANTE_SNT} + "nao reenviar antes do prazo") e
 * o UPDATE de linha unica {@code registrarUltimoLembreteSnt} nunca sao
 * exercitados contra um banco de verdade nos testes com mock — e exatamente
 * o tipo de escrita irreversivel (grava e-mail oficial de cobranca) que o
 * CLAUDE.md pede para ter cobertura de integracao real, seguindo o modelo de
 * {@code DecisaoAutomaticaSchedulerIntegrationTest}/
 * {@code LembreteAvaliadorTimestampIntegrationTest}.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-varredura-snt;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-varredura-snt",
        // Liga o componente (default e desligado em dev/teste)...
        "app.snt.lembrete.varredura.habilitado=true",
        "app.snt.lembrete.prazo-dias=7",
        // ...mas o agendador nunca chega a disparar sozinho durante o teste.
        "app.snt.lembrete.varredura.intervalo-ms=3600000",
        "app.snt.lembrete.varredura.atraso-inicial-ms=3600000"
})
class ComprovanteSntLembreteSchedulerIntegrationTest {

    @Autowired
    private ComprovanteSntLembreteScheduler scheduler;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private AnexoRepository anexoRepo;
    @Autowired
    private UsuarioRepository usuarioRepo;

    /** Unico ponto mockado: confirma envio de e-mail sem depender de SMTP real. */
    @MockitoBean
    private EmailSenderService emailSenderService;

    @BeforeEach
    void preparar() {
        anexoRepo.deleteAll();
        processoRepo.deleteAll();
        usuarioRepo.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.OPERADOR || u.getPerfil() == Perfil.ADMIN)
                .forEach(usuarioRepo::delete);

        Usuario operador = new Usuario();
        operador.setUsername("operador-snt-it");
        operador.setNome("Operador SNT IT");
        operador.setSenha("hash-qualquer");
        operador.setPerfil(Perfil.OPERADOR);
        operador.setEmail("operador-snt@example.com");
        operador.setAtivo(true);
        usuarioRepo.saveAndFlush(operador);
    }

    private Processo deferidoSemComprovante(String numero, int sequencial, int diasDesdeDecisao) {
        Processo p = new Processo();
        p.setNumero(numero);
        p.setAno(2026);
        p.setSequencial(sequencial);
        p.setPacienteNome("Paciente SNT " + sequencial);
        p.setPacienteRgct("999" + sequencial);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.DEFERIDO);
        p.setDataDecisao(LocalDateTime.now().minusDays(diasDesdeDecisao));
        return processoRepo.saveAndFlush(p);
    }

    private void anexarComprovanteSnt(Processo p) {
        Anexo a = new Anexo();
        a.setProcesso(p);
        a.setTipo(TipoAnexo.COMPROVANTE_SNT);
        a.setNomeArquivo("comprovante.pdf");
        a.setCaminhoArmazenado("caminho/qualquer.pdf");
        anexoRepo.saveAndFlush(a);
    }

    @Test
    void processoDeferidoSemComprovanteHaMaisQueOPrazoDisparaEmailEGravaTimestamp() {
        Processo p = deferidoSemComprovante("20/2026", 20, 10);
        when(emailSenderService.enviar(any(String[].class), any(), anyString(), anyString()))
                .thenReturn(true);

        int enviados = scheduler.varrer();

        assertThat(enviados).isEqualTo(1);
        Processo depois = processoRepo.findById(p.getId()).orElseThrow();
        assertThat(depois.getUltimoLembreteSntEm()).isNotNull();
    }

    @Test
    void processoDentroDoPrazoNaoDispara() {
        deferidoSemComprovante("21/2026", 21, 3); // 3 dias, prazo e 7

        int enviados = scheduler.varrer();

        assertThat(enviados).isZero();
        org.mockito.Mockito.verifyNoInteractions(emailSenderService);
    }

    @Test
    void processoJaComComprovanteNaoDispara() {
        Processo p = deferidoSemComprovante("22/2026", 22, 30);
        anexarComprovanteSnt(p);

        int enviados = scheduler.varrer();

        assertThat(enviados).isZero();
        org.mockito.Mockito.verifyNoInteractions(emailSenderService);
        assertThat(processoRepo.findById(p.getId()).orElseThrow().getUltimoLembreteSntEm()).isNull();
    }

    /**
     * Isolamento de falha por item: SMTP fora do ar num processo nao pode
     * impedir a cobranca dos demais candidatos elegiveis na mesma varredura
     * (mesmo contrato do {@code DecisaoAutomaticaScheduler}).
     */
    @Test
    void falhaDeSmtpEmUmProcessoNaoImpedeAVarreduraDosDemais() {
        Processo comFalha = deferidoSemComprovante("23/2026", 23, 15);
        Processo ok = deferidoSemComprovante("24/2026", 24, 10);

        when(emailSenderService.enviar(any(String[].class), any(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String assunto = inv.getArgument(2, String.class);
                    // O template inclui o numero do processo no assunto -- usa isso
                    // para direcionar a falha a um processo especifico.
                    return !assunto.contains("23/2026");
                });

        int enviados = scheduler.varrer();

        assertThat(enviados).isEqualTo(1);
        assertThat(processoRepo.findById(comFalha.getId()).orElseThrow().getUltimoLembreteSntEm())
                .isNull();
        assertThat(processoRepo.findById(ok.getId()).orElseThrow().getUltimoLembreteSntEm())
                .isNotNull();
    }
}

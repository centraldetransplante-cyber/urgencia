package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.service.EmailSenderService;
import br.gov.saude.sgpur.web.dto.AcaoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2 real, {@code ProcessoService}
 * e {@code ProcessoDecisaoController} REAIS) para a Fase 11.2 (aprovada
 * explicitamente pelo usuario): registrar {@code Parecer.ultimoLembreteEm}
 * sempre que um lembrete manual e enviado com sucesso a um avaliador
 * pendente.
 *
 * <p><b>Por que precisa ser {@code @SpringBootTest} (nao mock de
 * ProcessoService):</b> a escrita e um {@code @Modifying} de linha unica
 * ({@code ParecerRepository.registrarUltimoLembrete}) chamado dentro de uma
 * transacao real ({@code ProcessoService.registrarLembreteAvaliador}) - com o
 * service mockado nao existiria nenhum UPDATE de verdade contra o banco, e o
 * comportamento "so grava quando o e-mail realmente foi enviado" seria
 * inexprimivel (mesmo padrao de {@code ConviteAvaliadorDuplicidadeIntegrationTest}).</p>
 *
 * <p>Unico ponto mockado: {@code EmailSenderService} (cliente SMTP externo).</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-lembrete-timestamp-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-lembrete-timestamp-it"
})
class LembreteAvaliadorTimestampIntegrationTest {

    @Autowired
    private ProcessoDecisaoController controller;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;

    @MockitoBean
    private EmailSenderService emailSenderService;

    private Long processoId;
    private Long parecerId;
    private Long parecerId2;

    @BeforeEach
    @Transactional
    void preparar() {
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        Processo p = new Processo();
        p.setNumero("07/2026");
        p.setAno(2026);
        p.setSequencial(7);
        p.setPacienteNome("Paciente Teste Lembrete");
        p.setPacienteRgct("444444444");
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
        MembroUrgenciaRenal membro2 = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HPS", "Ana Lucia", "ana@example.com"));

        Parecer parecer = new Parecer(membro);
        parecer.setProcesso(p);
        parecer.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parecer);
        parecerId = parecer.getId();

        Parecer parecer2 = new Parecer(membro2);
        parecer2.setProcesso(p);
        parecer2.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parecer2);
        parecerId2 = parecer2.getId();
    }

    /**
     * Caminho de sucesso do lembrete individual: e-mail enviado com sucesso ->
     * `Parecer.ultimoLembreteEm` gravado no banco (relido, nao confiado no
     * retorno do mock).
     */
    @Test
    void lembreteIndividualEnviadoComSucessoGravaUltimoLembreteEm() {
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        // Truncado pra milissegundo (nao whole-second) nos 3 pontos de
        // comparacao: o H2 arredonda a precisao de nanossegundo do
        // LocalDateTime.now() do Java para microssegundo ao persistir/reler,
        // podendo empurrar o valor lido alguns nanossegundos pra frente do
        // "depois" capturado em memoria - falso negativo intermitente
        // (achado real, RELATORIO-RESPONSIVIDADE-PROGRAMATICA-2026-08-24).
        // Truncar pra milissegundo elimina esse ruido de sub-microssegundo
        // sem perder a precisao que o teste realmente precisa.
        LocalDateTime antes = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        AcaoResponse resposta = controller.lembreteAvaliador(processoId, parecerId);
        LocalDateTime depois = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS).plus(1, ChronoUnit.MILLIS);

        assertThat(resposta.ok()).isTrue();

        Parecer relido = parecerRepo.findById(parecerId).orElseThrow();
        assertThat(relido.getUltimoLembreteEm()).isNotNull();
        assertThat(relido.getUltimoLembreteEm().truncatedTo(ChronoUnit.MILLIS)).isBetween(antes, depois);
    }

    /**
     * Caminho de FALHA (sem mock do resultado de sucesso): se o envio de
     * e-mail falha (SMTP fora do ar), o timestamp NAO deve avancar - o
     * operador nao pode achar que ja lembrou o avaliador quando o e-mail nem
     * saiu.
     */
    @Test
    void lembreteIndividualComFalhaDeEnvioNaoGravaUltimoLembreteEm() {
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(false);

        AcaoResponse resposta = controller.lembreteAvaliador(processoId, parecerId);

        assertThat(resposta.ok()).isFalse();

        Parecer relido = parecerRepo.findById(parecerId).orElseThrow();
        assertThat(relido.getUltimoLembreteEm()).isNull();
    }

    /**
     * Lembrete em lote: os 2 pareceres pendentes recebem o timestamp quando o
     * envio da 2 os dois tem sucesso.
     */
    @Test
    void lembretePendentesGravaUltimoLembreteEmParaTodosOsEnviadosComSucesso() {
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        AcaoResponse resposta = controller.lembretePendentes(processoId);

        assertThat(resposta.ok()).isTrue();
        assertThat(parecerRepo.findById(parecerId).orElseThrow().getUltimoLembreteEm()).isNotNull();
        assertThat(parecerRepo.findById(parecerId2).orElseThrow().getUltimoLembreteEm()).isNotNull();
    }

    /**
     * Reenvio: um segundo lembrete manual atualiza o timestamp para o momento
     * mais recente (nao mantem o valor antigo).
     */
    @Test
    void segundoLembreteAtualizaOTimestampParaOMomentoMaisRecente() {
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        controller.lembreteAvaliador(processoId, parecerId);
        LocalDateTime primeiro = parecerRepo.findById(parecerId).orElseThrow().getUltimoLembreteEm();

        // Forca o primeiro timestamp para o passado, para garantir uma diferenca
        // mensuravel mesmo que os dois lembretes rodem no mesmo milissegundo.
        Parecer parecer = parecerRepo.findById(parecerId).orElseThrow();
        parecer.setUltimoLembreteEm(primeiro.minusHours(1));
        parecerRepo.saveAndFlush(parecer);

        controller.lembreteAvaliador(processoId, parecerId);
        LocalDateTime segundo = parecerRepo.findById(parecerId).orElseThrow().getUltimoLembreteEm();

        assertThat(segundo).isAfter(primeiro.minusHours(1));
    }
}

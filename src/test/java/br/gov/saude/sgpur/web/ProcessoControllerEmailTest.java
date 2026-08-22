package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.*;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Cobre os endpoints de envio real de e-mail (disparo manual): lembrete de
 * avaliacao pendente (individual e em lote) e "Enviar agora" dos textos
 * prontos. EmailSenderService e sempre mockado - nenhum teste dispara SMTP
 * real. Cobre tambem a regra de imparcialidade (nome completo do paciente
 * nunca vai no corpo do lembrete ao avaliador) e o bloqueio de envio do
 * e-mail oficial de Deferido sem o comprovante SNT anexado.
 */
@WebMvcTest(ProcessoDecisaoController.class)
class ProcessoControllerEmailTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoService processoService;
    @MockitoBean private ProcessoValidator processoValidator;
    @MockitoBean private FluxoProcessoService fluxoService;
    @MockitoBean private EmailTemplateService emailTemplateService;
    @MockitoBean private RelatorioService relatorioService;
    @MockitoBean private OficioService oficioService;
    @MockitoBean private RegistroEnvioService registroEnvioService;
    @MockitoBean private MembroUrgenciaRenalRepository membroRepository;
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private AnexoStorageService anexoStorage;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private DecisaoFinalService decisaoFinalService;
    @MockitoBean private GeminiService geminiService;
    @MockitoBean private EmailSenderService emailSenderService;
    // GlobalModelAdvice (@ControllerAdvice global) precisa deste bean pro
    // contexto do @WebMvcTest subir, mesmo o controller nao usando mais
    // ParecerRepository diretamente (movido para ProcessoService.buscarParecer).
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private SolicitacaoOnlineService solicitacaoOnlineService;
    // ProcessoDecisaoController passou a receber um PlatformTransactionManager
    // (TransactionTemplate proprio de decidir()/retomarAnalise(), 2026-07-29).
    // Em @WebMvcTest nao ha JPA, entao o gerenciador e mockado - o
    // TransactionTemplate executa o callback normalmente. Ver
    // AvaliadorControllerTest.
    @MockitoBean private org.springframework.transaction.PlatformTransactionManager txManager;

    private Processo processo;
    private MembroUrgenciaRenal membro;
    private Parecer parecerPendente;

    @BeforeEach
    void setUp() {
        membro = new MembroUrgenciaRenal("HCPA", "Dra. Veronica Horbe", "veronica@example.com");
        membro.setId(10L);

        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("07/2026");
        processo.setPacienteNome("Mariana da Rosa Martins");
        processo.setStatus(StatusProcesso.ENVIADO);
        processo.setSolicitanteEmail("solicitante@example.com");

        parecerPendente = new Parecer(membro);
        parecerPendente.setId(100L);
        parecerPendente.setProcesso(processo);
        parecerPendente.setDataEnvio(LocalDate.now());
        processo.addParecer(parecerPendente);

        when(processoService.buscar(1L)).thenReturn(processo);
    }

    // ===== Lembrete individual =====

    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembreteAvaliadorEnviaEAuditaQuandoParecerPendente() throws Exception {
        when(processoService.buscarParecer(1L, 100L)).thenReturn(Optional.of(parecerPendente));
        when(emailTemplateService.emailLembreteAvaliador(eq(processo), eq(membro)))
            .thenReturn(new EmailTemplate("lembrete-avaliador", "titulo", "bell",
                "Assunto lembrete", "Corpo do lembrete", false));
        when(emailSenderService.enviar(eq("veronica@example.com"), anyString(), anyString()))
            .thenReturn(true);

        mvc.perform(post("/processos/1/lembrete-avaliador")
                .param("parecerId", "100")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        verify(emailSenderService).enviar(eq("veronica@example.com"), eq("Assunto lembrete"), eq("Corpo do lembrete"));
        verify(auditoria).registrar(eq("LEMBRETE_AVALIADOR_ENVIADO"), anyString());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembreteAvaliadorFalhaQuandoParecerJaRespondido() throws Exception {
        parecerPendente.setResultado(ResultadoParecer.FAVORAVEL);
        when(processoService.buscarParecer(1L, 100L)).thenReturn(Optional.of(parecerPendente));

        mvc.perform(post("/processos/1/lembrete-avaliador")
                .param("parecerId", "100")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));

        verifyNoInteractions(emailSenderService);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembreteAvaliadorFalhaQuandoAvaliadorSemEmail() throws Exception {
        MembroUrgenciaRenal semEmail = new MembroUrgenciaRenal("ISCMPA", "Dr. Sem Email", null);
        semEmail.setId(11L);
        Parecer parecerSemEmail = new Parecer(semEmail);
        parecerSemEmail.setId(101L);
        parecerSemEmail.setProcesso(processo);
        parecerSemEmail.setDataEnvio(LocalDate.now());
        when(processoService.buscarParecer(1L, 101L)).thenReturn(Optional.of(parecerSemEmail));

        mvc.perform(post("/processos/1/lembrete-avaliador")
                .param("parecerId", "101")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));

        verifyNoInteractions(emailSenderService);
    }

    /** Imparcialidade: o corpo do lembrete ao avaliador nunca contem o nome completo do paciente. */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembreteAvaliadorNuncaExpoeNomeCompletoDoPaciente() throws Exception {
        when(processoService.buscarParecer(1L, 100L)).thenReturn(Optional.of(parecerPendente));
        when(emailTemplateService.emailLembreteAvaliador(eq(processo), eq(membro)))
            .thenReturn(new EmailTemplate("lembrete-avaliador", "t", "bell",
                "Processo 07/2026 CET-RS - Paciente M.R.M.",
                "Processo 07/2026 CET-RS - Paciente M.R.M. esta disponivel para sua avaliacao.", false));
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        mvc.perform(post("/processos/1/lembrete-avaliador")
                .param("parecerId", "100")
                .with(csrf()))
            .andExpect(status().isOk());

        var corpoCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailSenderService).enviar(anyString(), anyString(), corpoCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(corpoCaptor.getValue())
            .doesNotContain("Mariana da Rosa Martins")
            .contains("esta disponivel para sua avaliacao");
    }

    // ===== Lembrete em lote =====

    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembretePendentesEnviaParaTodosOsPendentesComEmail() throws Exception {
        when(processoService.pareceresPendentesComEmail(1L))
            .thenReturn(List.of(parecerPendente));
        when(emailTemplateService.emailLembreteAvaliador(eq(processo), eq(membro)))
            .thenReturn(new EmailTemplate("lembrete-avaliador", "t", "bell", "Assunto", "Corpo", false));
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        mvc.perform(post("/processos/1/lembrete-pendentes").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        verify(emailSenderService, times(1)).enviar(eq("veronica@example.com"), anyString(), anyString());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembretePendentesFalhaQuandoNaoHaPendentes() throws Exception {
        when(processoService.pareceresPendentesComEmail(1L))
            .thenReturn(List.of());

        mvc.perform(post("/processos/1/lembrete-pendentes").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));

        verifyNoInteractions(emailSenderService);
    }

    // ===== Enviar e-mail pronto (accordion) =====

    @Test
    @WithMockUser(roles = "OPERADOR")
    void enviarEmailDeferidoBloqueadoSemComprovanteSnt() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        // Checagem de SNT/oficio delega a ProcessoValidator.validarRespostaSolicitante
        // (fonte unica, mesma regra de ProcessoService.confirmarRespostaSolicitante);
        // validator e mock aqui, entao precisa do stub para simular o bloqueio.
        when(processoValidator.validarRespostaSolicitante(processo))
            .thenReturn(Optional.of("Anexe o comprovante de insercao no SNT antes de confirmar a resposta ao solicitante."));

        mvc.perform(post("/processos/1/email/enviar")
                .param("chave", "deferido")
                .param("assunto", "assunto")
                .param("corpo", "corpo")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));

        verifyNoInteractions(emailSenderService);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void enviarEmailDeferidoFuncionaComComprovanteSnt() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        Anexo comprovante = new Anexo();
        comprovante.setTipo(TipoAnexo.COMPROVANTE_SNT);
        processo.addAnexo(comprovante);
        when(emailSenderService.enviar(eq(new String[]{"solicitante@example.com"}), isNull(), anyString(), anyString()))
            .thenReturn(true);

        mvc.perform(post("/processos/1/email/enviar")
                .param("chave", "deferido")
                .param("assunto", "assunto")
                .param("corpo", "corpo")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        verify(emailSenderService).enviar(eq(new String[]{"solicitante@example.com"}), isNull(), eq("assunto"), eq("corpo"));
        verify(auditoria).registrar(eq("EMAIL_ENVIADO"), anyString());
    }

    /**
     * E-mail adicional (2026-08-21): este e o caminho MANUAL (operador
     * clicando "Enviar agora" no e-mail pronto) equivalente ao automatico ja
     * coberto em ProcessoServiceTest.finalizarRespostaComEmailAdicionalEnviaEmCopia -
     * quando Processo.emailAdicional esta preenchido, o CC tambem vai junto
     * aqui.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void enviarEmailDeferidoComEmailAdicionalEnviaEmCopia() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        processo.setEmailAdicional("copia@equipe.com.br");
        Anexo comprovante = new Anexo();
        comprovante.setTipo(TipoAnexo.COMPROVANTE_SNT);
        processo.addAnexo(comprovante);
        when(emailSenderService.enviar(eq(new String[]{"solicitante@example.com"}),
                eq(new String[]{"copia@equipe.com.br"}), anyString(), anyString()))
            .thenReturn(true);

        mvc.perform(post("/processos/1/email/enviar")
                .param("chave", "deferido")
                .param("assunto", "assunto")
                .param("corpo", "corpo")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        verify(emailSenderService).enviar(eq(new String[]{"solicitante@example.com"}),
                eq(new String[]{"copia@equipe.com.br"}), eq("assunto"), eq("corpo"));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void enviarEmailConviteAvaliadorUsaEmailsDosAvaliadoresDoProcesso() throws Exception {
        when(emailSenderService.enviar(any(String[].class), any(), anyString(), anyString()))
            .thenReturn(true);

        mvc.perform(post("/processos/1/email/enviar")
                .param("chave", "convite-avaliador")
                .param("assunto", "assunto")
                .param("corpo", "corpo")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        verify(emailSenderService).enviar(eq(new String[]{"veronica@example.com"}), isNull(), eq("assunto"), eq("corpo"));
    }

    // ===== Pre-visualizacao (modal de confirmacao) =====

    /** A pre-visualizacao nunca envia e-mail; apenas devolve o conteudo a exibir. */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void previewProntoDevolveDestinatariosEConteudoSemEnviar() throws Exception {
        mvc.perform(post("/processos/1/email/preview")
                .param("tipo", "pronto")
                .param("chave", "convite-avaliador")
                .param("assunto", "Assunto X")
                .param("corpo", "Corpo Y")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.mensagens[0].destinatarios").value("veronica@example.com"))
            .andExpect(jsonPath("$.mensagens[0].assunto").value("Assunto X"))
            .andExpect(jsonPath("$.mensagens[0].corpo").value("Corpo Y"));

        verifyNoInteractions(emailSenderService);
    }

    /** O bloqueio (anexo obrigatorio ausente) ja aparece na pre-visualizacao. */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void previewProntoDeferidoBloqueadoSemComprovanteSnt() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        when(processoValidator.validarRespostaSolicitante(processo))
            .thenReturn(Optional.of("Anexe o comprovante de insercao no SNT antes de confirmar a resposta ao solicitante."));

        mvc.perform(post("/processos/1/email/preview")
                .param("tipo", "pronto")
                .param("chave", "deferido")
                .param("assunto", "a")
                .param("corpo", "c")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.erro").isNotEmpty());

        verifyNoInteractions(emailSenderService);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void previewLembreteAvaliadorDevolveDestinatarioEConteudo() throws Exception {
        when(processoService.buscarParecer(1L, 100L)).thenReturn(Optional.of(parecerPendente));
        when(emailTemplateService.emailLembreteAvaliador(eq(processo), eq(membro)))
            .thenReturn(new EmailTemplate("lembrete-avaliador", "t", "bell",
                "Assunto lembrete", "Corpo do lembrete", false));

        mvc.perform(post("/processos/1/email/preview")
                .param("tipo", "lembrete-avaliador")
                .param("parecerId", "100")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.mensagens[0].destinatarios").value("veronica@example.com"))
            .andExpect(jsonPath("$.mensagens[0].assunto").value("Assunto lembrete"));

        verifyNoInteractions(emailSenderService);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void previewLembretePendentesUmaMensagemPorAvaliador() throws Exception {
        when(processoService.pareceresPendentesComEmail(1L))
            .thenReturn(List.of(parecerPendente));
        when(emailTemplateService.emailLembreteAvaliador(eq(processo), eq(membro)))
            .thenReturn(new EmailTemplate("lembrete-avaliador", "t", "bell", "Assunto", "Corpo", false));

        mvc.perform(post("/processos/1/email/preview")
                .param("tipo", "lembrete-pendentes")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.mensagens[0].destinatarios").value("veronica@example.com"));

        verifyNoInteractions(emailSenderService);
    }

    // ===== Bloqueio de edicao em processo encerrado =====

    /** Processo encerrado bloqueia o registro de envio (redirect com flash de erro). */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void registrarEnvioBloqueadoQuandoProcessoEncerrado() throws Exception {
        when(processoValidator.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(post("/processos/1/registrar-envio").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("erro"));

        verifyNoInteractions(anexoStorage);
        verify(processoService, never()).registrarEnvio(anyLong());
    }

    /** Processo encerrado bloqueia o lembrete a avaliador (resposta JSON de erro). */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void lembreteAvaliadorBloqueadoQuandoProcessoEncerrado() throws Exception {
        when(processoValidator.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(post("/processos/1/lembrete-avaliador")
                .param("parecerId", "100")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));

        verifyNoInteractions(emailSenderService);
    }

    // ===== registrar-envio: o controller so mapeia o RegistroEnvioResultado =====
    //
    // A logica de negocio (documento clinico PDF obrigatorio, comprovante de
    // envio obrigatorio, consolidacao/carimbo do PDF) foi extraida para
    // RegistroEnvioService (RegistroEnvioServiceTest cobre esses casos em
    // detalhe). Aqui o controller e mockado por tras dessa fronteira: so
    // confere que o resultado de erro/sucesso e mapeado para o flash certo.

    /**
     * Erro do RegistroEnvioService (ex.: sem documento clinico PDF valido) vira
     * flash "erro" e nao efetiva nenhum efeito colateral adicional no controller.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void registrarEnvioMapeiaErroDoRegistroEnvioServicoParaFlash() throws Exception {
        when(registroEnvioService.registrar(1L)).thenReturn(
            RegistroEnvioService.RegistroEnvioResultado.erro(
                "Anexe ao menos um documento clinico (PDF) antes de registrar o envio."));

        mvc.perform(post("/processos/1/registrar-envio").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro",
                "Anexe ao menos um documento clinico (PDF) antes de registrar o envio."));

        verify(registroEnvioService).registrar(1L);
        // Envio recusado: nem tenta convidar ninguem para o portal.
        verify(registroEnvioService, org.mockito.Mockito.never()).enviarConvitesAvaliadores(1L);
    }

    /**
     * Sucesso do RegistroEnvioService vira flash "msg" e, quando ha avisos
     * (documentos ignorados na consolidacao), tambem flash "aviso".
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void registrarEnvioMapeiaSucessoEAvisosDoRegistroEnvioServicoParaFlash() throws Exception {
        when(registroEnvioService.registrar(1L)).thenReturn(
            RegistroEnvioService.RegistroEnvioResultado.sucesso(
                "Envio aos avaliadores registrado em 25/07/2026.",
                List.of("exame.jpg")));
        when(registroEnvioService.enviarConvitesAvaliadores(1L)).thenReturn(
            new RegistroEnvioService.ConvitesResultado(3, List.of()));

        mvc.perform(post("/processos/1/registrar-envio").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString(
                "Envio aos avaliadores registrado em 25/07/2026.")))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString(
                "Convite ao Portal do Avaliador enviado a 3 avaliadores.")))
            .andExpect(flash().attribute("aviso", org.hamcrest.Matchers.containsString("exame.jpg")));

        verify(registroEnvioService).registrar(1L);
        verify(registroEnvioService).enviarConvitesAvaliadores(1L);
    }

    /**
     * Convite que nao pode ser enviado (avaliador sem e-mail, falha de SMTP) NAO
     * derruba o registro do envio: continua flash "msg" de sucesso, com um
     * "aviso" nomeando quem ficou sem o convite.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void registrarEnvioComConviteFalhandoMantemSucessoEAvisaQuemFicouDeFora() throws Exception {
        when(registroEnvioService.registrar(1L)).thenReturn(
            RegistroEnvioService.RegistroEnvioResultado.sucesso(
                "Envio aos avaliadores registrado em 25/07/2026.", List.of()));
        when(registroEnvioService.enviarConvitesAvaliadores(1L)).thenReturn(
            new RegistroEnvioService.ConvitesResultado(2,
                List.of("Dr. Sem Email (sem e-mail cadastrado)")));

        mvc.perform(post("/processos/1/registrar-envio").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString(
                "Envio aos avaliadores registrado")))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString(
                "enviado a 2 avaliadores.")))
            .andExpect(flash().attribute("aviso", org.hamcrest.Matchers.containsString(
                "Dr. Sem Email (sem e-mail cadastrado)")));
    }

    /**
     * Nenhum convite enviado (ex.: os 3 avaliadores sem e-mail): a mensagem de
     * sucesso NAO afirma que convidou ninguem - so o registro do envio.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void registrarEnvioSemNenhumConviteEnviadoNaoAfirmaEnvioDeConvite() throws Exception {
        when(registroEnvioService.registrar(1L)).thenReturn(
            RegistroEnvioService.RegistroEnvioResultado.sucesso(
                "Envio aos avaliadores registrado em 25/07/2026.", List.of()));
        when(registroEnvioService.enviarConvitesAvaliadores(1L)).thenReturn(
            new RegistroEnvioService.ConvitesResultado(0, List.of("Dr. A (falha no envio do e-mail)")));

        mvc.perform(post("/processos/1/registrar-envio").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", "Envio aos avaliadores registrado em 25/07/2026."))
            .andExpect(flash().attribute("aviso", org.hamcrest.Matchers.containsString(
                "Dr. A (falha no envio do e-mail)")));
    }

}

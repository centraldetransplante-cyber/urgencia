package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.*;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do endpoint POST /processos/{id}/decidir - onde a decisao medica e
 * de fato gravada. Cobre o encadeamento de validacoes (pausa -> contagem),
 * o bloqueio de processo encerrado, o motivo obrigatorio no indeferimento, e
 * que uma falha em gerarDocumentos nao propaga (so vira flash de erro).
 */
@WebMvcTest(ProcessoDecisaoController.class)
class ProcessoDecisaoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoService processoService;
    @MockitoBean private ProcessoValidator validator;
    @MockitoBean private DecisaoFinalService decisaoFinalService;
    @MockitoBean private RegistroEnvioService registroEnvioService;
    @MockitoBean private EmailTemplateService emailTemplateService;
    @MockitoBean private EmailSenderService emailSenderService;
    @MockitoBean private AnexoStorageService anexoStorage;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private GeminiService geminiService;
    @MockitoBean private UsuarioRepository usuarioRepository;
    // GlobalModelAdvice (@ControllerAdvice global) precisa deste bean pro
    // contexto do @WebMvcTest subir, mesmo o controller nao usando mais
    // ParecerRepository diretamente (movido para ProcessoService.buscarParecer).
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private SolicitacaoOnlineService solicitacaoOnlineService;
    // decidir()/retomarAnalise() usam um TransactionTemplate proprio (transacoes
    // curtas e independentes, para uma falha na geracao dos PDFs nunca desfazer
    // a decisao ja gravada). Em @WebMvcTest nao existe JPA/DataSource, entao o
    // gerenciador e mockado: o TransactionTemplate executa o callback
    // normalmente e commit/rollback viram no-op no mock. Mesmo padrao de
    // AvaliadorControllerTest.
    @MockitoBean private org.springframework.transaction.PlatformTransactionManager txManager;

    private Processo processo;

    @BeforeEach
    void setUp() {
        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("01/2026");
        processo.setStatus(StatusProcesso.ENVIADO);
        when(processoService.buscar(1L)).thenReturn(processo);
        // Por padrao, nenhuma validacao bloqueia (cada teste sobrescreve o que precisa).
        when(validator.validarPausaDecisao(any(), any())).thenReturn(Optional.empty());
        when(validator.validarContagemVotos(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void decisaoInvalidaEhRejeitadaAntesDeQualquerValidacao() throws Exception {
        mvc.perform(post("/processos/1/decidir")
                .param("decisao", "ENVIADO")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("Decisão inválida")));

        verify(processoService, never()).decidir(anyLong(), any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void indeferidoSemMotivoEhRejeitado() throws Exception {
        mvc.perform(post("/processos/1/decidir")
                .param("decisao", "INDEFERIDO")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("exige o motivo")));

        verify(processoService, never()).decidir(anyLong(), any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void processoEncerradoBloqueiaAntesDeDecidir() throws Exception {
        when(validator.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(post("/processos/1/decidir")
                .param("decisao", "DEFERIDO")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));

        verify(processoService, never()).decidir(anyLong(), any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void pausaBloqueiaERedirecionaParaAncoraRespostas() throws Exception {
        when(validator.validarPausaDecisao(eq(processo), eq(StatusProcesso.DEFERIDO)))
            .thenReturn(Optional.of("Processo aguardando informacao complementar do solicitante."));

        mvc.perform(post("/processos/1/decidir")
                .param("decisao", "DEFERIDO")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1#respostas"))
            .andExpect(flash().attribute("erro",
                org.hamcrest.Matchers.containsString("informacao complementar")));

        verify(processoService, never()).decidir(anyLong(), any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void contagemDeVotosInsuficienteBloqueiaSemAncora() throws Exception {
        when(validator.validarContagemVotos(eq(processo), eq(StatusProcesso.DEFERIDO)))
            .thenReturn(Optional.of("Deferimento exige no minimo 2 parecer(es) favoravel(is)."));

        mvc.perform(post("/processos/1/decidir")
                .param("decisao", "DEFERIDO")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("Deferimento exige")));

        verify(processoService, never()).decidir(anyLong(), any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void decisaoValidaChamaServicoGeraDocumentosERegistraAuditoria() throws Exception {
        Processo decidido = new Processo();
        decidido.setId(1L);
        decidido.setNumero("01/2026");
        decidido.setStatus(StatusProcesso.DEFERIDO);
        when(processoService.decidir(1L, StatusProcesso.DEFERIDO, null)).thenReturn(decidido);

        mvc.perform(post("/processos/1/decidir")
                .param("decisao", "DEFERIDO")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("Decisão registrada")));

        verify(processoService).decidir(1L, StatusProcesso.DEFERIDO, null);
        // A geracao dos PDFs roda numa transacao curta e propria, com o
        // processo RECARREGADO dentro dela (o devolvido por decidir() ja esta
        // desanexado e DecisaoFinalService navega colecoes LAZY) - por isso a
        // verificacao e sobre o processo de processoService.buscar(1L).
        verify(decisaoFinalService).gerarDocumentos(processo);
        // Acao sensivel: auditoria com IP (mesmo padrao do voto do avaliador).
        verify(auditoria).registrar(eq("PROCESSO_DECIDIDO"), any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void falhaAoGerarDocumentosNaoImpedeQueADecisaoJaGravadaFiquePendenteDeAvisoNaTela() throws Exception {
        // decidir() ja persistiu a decisao antes de gerarDocumentos ser chamado;
        // uma falha na geracao do PDF/oficio nao pode ser um 500 nem reverter
        // silenciosamente a decisao - so avisa o operador via flash "erro".
        Processo decidido = new Processo();
        decidido.setId(1L);
        decidido.setStatus(StatusProcesso.DEFERIDO);
        when(processoService.decidir(1L, StatusProcesso.DEFERIDO, null)).thenReturn(decidido);
        // gerarDocumentos recebe o processo recarregado (processoService.buscar),
        // nao a instancia devolvida por decidir() - ver o teste acima.
        doThrow(new IllegalStateException("falha ao gerar capa"))
            .when(decisaoFinalService).gerarDocumentos(processo);

        mvc.perform(post("/processos/1/decidir")
                .param("decisao", "DEFERIDO")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", "falha ao gerar capa"));

        verify(processoService).decidir(1L, StatusProcesso.DEFERIDO, null);
    }

    // Restricao de role (OPERADOR/ADMIN em /processos/**, AVALIADOR fora) e o
    // redirecionamento de anonimo para /login sao regras do SecurityConfig
    // central, ja cobertas em SecurityIntegrationTest (@SpringBootTest) - nao
    // fazem parte deste slice @WebMvcTest, que nao carrega esse filtro.
}

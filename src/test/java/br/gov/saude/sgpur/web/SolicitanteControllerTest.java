package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.AnexoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoSolicitacaoOnlineStorageService;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import br.gov.saude.sgpur.service.RascunhoSolicitacaoOnlineService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import br.gov.saude.sgpur.service.TempoRespostaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do SolicitanteController: posse da propria solicitacao (nunca
 * acessa/cancela pedido de outro usuario) e o fluxo feliz de criacao com
 * anexo. Restricao de ROLE_SOLICITANTE por rota (SecurityConfig) e coberta em
 * SecurityIntegrationTest, seguindo o mesmo padrao do AvaliadorControllerTest
 * (testes de role/matcher ficam no teste de integracao com contexto completo;
 * este slice cobre so a logica do controller).
 */
@WebMvcTest(SolicitanteController.class)
class SolicitanteControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private UsuarioRepository usuarioRepo;
    @MockitoBean private SolicitacaoOnlineService solicitacaoService;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private MensagemSolicitacaoService mensagemService;
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private AnexoSolicitacaoOnlineRepository anexoRepo;
    @MockitoBean private AnexoSolicitacaoOnlineStorageService anexoStorage;
    @MockitoBean private AnexoStorageService anexoStorageProcesso;
    @MockitoBean private TempoRespostaService tempoRespostaService;
    @MockitoBean private RascunhoSolicitacaoOnlineService rascunhoService;

    @TempDir
    Path tempDir;

    private Usuario dono;
    private Usuario outroUsuario;
    private SolicitacaoOnline solicitacaoDoDono;

    @BeforeEach
    void setUp() {
        dono = new Usuario();
        dono.setId(1L);
        dono.setUsername("solicitante1");
        dono.setPerfil(Perfil.SOLICITANTE);
        dono.setEquipeSolicitante("HCPA");
        dono.setEmail("hcpa@example.com");

        outroUsuario = new Usuario();
        outroUsuario.setId(2L);
        outroUsuario.setUsername("solicitante2");
        outroUsuario.setPerfil(Perfil.SOLICITANTE);
        outroUsuario.setEquipeSolicitante("HNSC");
        outroUsuario.setEmail("hnsc@example.com");

        solicitacaoDoDono = new SolicitacaoOnline();
        solicitacaoDoDono.setId(50L);
        solicitacaoDoDono.setUsuarioSolicitante(dono);
        solicitacaoDoDono.setPacienteNome("Fulano de Tal");
        solicitacaoDoDono.setPacienteRgct("123456789-12345");
        solicitacaoDoDono.setDataSituacaoEspecial(LocalDate.now());
        solicitacaoDoDono.setJustificativaClinica("Quadro grave.");
        solicitacaoDoDono.setStatus(StatusSolicitacaoOnline.ENVIADA);
    }

    @Test
    @WithMockUser(username = "solicitante2", roles = "SOLICITANTE")
    void detalheExibe403ParaSolicitacaoDeOutroUsuario() throws Exception {
        when(usuarioRepo.findByUsername("solicitante2")).thenReturn(Optional.of(outroUsuario));
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);

        mvc.perform(get("/solicitante/50"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "solicitante2", roles = "SOLICITANTE")
    void cancelarExibe403ParaSolicitacaoDeOutroUsuario() throws Exception {
        when(usuarioRepo.findByUsername("solicitante2")).thenReturn(Optional.of(outroUsuario));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);

        mvc.perform(post("/solicitante/50/cancelar").with(csrf()))
            .andExpect(status().isForbidden());

        // 403 antes de qualquer tentativa de cancelamento no service
        verify(solicitacaoService, never()).cancelar(any(), any());
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void detalheExibeAPropriaSolicitacaoNormalmente() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);
        when(solicitacaoService.diasEspera(solicitacaoDoDono))
            .thenReturn(new SolicitacaoOnlineService.DiasEspera(0, "bg-secondary"));
        when(mensagemService.listarPorSolicitacao(50L)).thenReturn(java.util.List.of());

        mvc.perform(get("/solicitante/50"))
            .andExpect(status().isOk())
            .andExpect(view().name("solicitante/detalhe"));
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void detalheExibePrevisaoDePrazoQuandoProcessoEstaEmAnaliseAtiva() throws Exception {
        solicitacaoDoDono.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        br.gov.saude.sgpur.domain.Processo processo = new br.gov.saude.sgpur.domain.Processo();
        processo.setId(7L);
        processo.setStatus(br.gov.saude.sgpur.domain.StatusProcesso.ENVIADO);
        solicitacaoDoDono.setProcessoGerado(processo);

        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);
        when(solicitacaoService.diasEspera(solicitacaoDoDono))
            .thenReturn(new SolicitacaoOnlineService.DiasEspera(0, "bg-secondary"));
        when(mensagemService.listarPorSolicitacao(50L)).thenReturn(java.util.List.of());
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(10, 5.0, 1, 7, java.util.Map.of()));

        mvc.perform(get("/solicitante/50"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("previsaoPrazo", "5 dias"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Previsão baseada no histórico")));
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void cancelarAPropriaSolicitacaoFunciona() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);
        // null = ainda nao tinha processo gerado; ninguem para avisar.
        when(solicitacaoService.cancelar(50L, 1L)).thenReturn(null);

        mvc.perform(post("/solicitante/50/cancelar").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/solicitante"))
            .andExpect(flash().attribute("msg", "Solicitação cancelada."));

        verify(solicitacaoService).cancelar(50L, 1L);
        verify(auditoria).registrar(eq("SOLICITACAO_ONLINE_CANCELADA"), any());
        verify(solicitacaoService, never()).notificarAvaliadoresCancelamento(any());
    }

    /**
     * Cancelamento de um pedido que ja virou processo: avisa os avaliadores
     * pendentes DEPOIS que o cancelamento ja foi commitado pelo servico.
     */
    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void cancelarPedidoJaConvertidoAvisaAvaliadoresPendentes() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);
        when(solicitacaoService.cancelar(50L, 1L)).thenReturn(500L);
        when(solicitacaoService.notificarAvaliadoresCancelamento(500L))
            .thenReturn(java.util.List.of());

        mvc.perform(post("/solicitante/50/cancelar").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString(
                "avaliadores pendentes foram avisados")));

        verify(solicitacaoService).notificarAvaliadoresCancelamento(500L);
    }

    /**
     * Avaliador que nao pode ser avisado (sem e-mail / SMTP fora) vira flash
     * "aviso" - o cancelamento em si continua valendo.
     */
    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void cancelarComAvisoNaoEntregueMantemCancelamentoEAvisaNaTela() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);
        when(solicitacaoService.cancelar(50L, 1L)).thenReturn(500L);
        when(solicitacaoService.notificarAvaliadoresCancelamento(500L))
            .thenReturn(java.util.List.of("Dr. A"));

        mvc.perform(post("/solicitante/50/cancelar").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("cancelada")))
            .andExpect(flash().attribute("aviso", org.hamcrest.Matchers.containsString("Dr. A")));
    }

    /**
     * Falha INESPERADA (nao o caso ja tratado de "sem e-mail"/SMTP recusado)
     * dentro de notificarAvaliadoresCancelamento nao pode virar 500: o
     * cancelamento ja esta commitado quando isto roda, e uma excecao ali e so
     * um problema no aviso-cortesia, nunca no cancelamento em si.
     */
    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void cancelarComFalhaInesperadaNoAvisoNaoDevolve500() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);
        when(solicitacaoService.cancelar(50L, 1L)).thenReturn(500L);
        when(solicitacaoService.notificarAvaliadoresCancelamento(500L))
            .thenThrow(new RuntimeException("falha inesperada simulada"));

        mvc.perform(post("/solicitante/50/cancelar").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/solicitante"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("cancelada")))
            .andExpect(flash().attribute("aviso", org.hamcrest.Matchers.containsString("falha inesperada")));
    }

    /** Regra de exibicao do botao vem do servidor, nunca recalculada na tela. */
    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void detalheExpoePodeCancelarNoModel() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);
        when(solicitacaoService.podeCancelar(solicitacaoDoDono)).thenReturn(true);
        when(solicitacaoService.diasEspera(solicitacaoDoDono))
            .thenReturn(new SolicitacaoOnlineService.DiasEspera(0, "bg-secondary"));
        when(mensagemService.listarPorSolicitacao(50L)).thenReturn(java.util.List.of());

        mvc.perform(get("/solicitante/50"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("podeCancelar", true));
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void listaExpoeResumoEDiasEsperaNoModel() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.listarMinhas(1L)).thenReturn(java.util.List.of(solicitacaoDoDono));
        when(solicitacaoService.resumir(java.util.List.of(solicitacaoDoDono)))
            .thenReturn(new br.gov.saude.sgpur.service.SolicitacaoOnlineService.Resumo(1, 1, 0, 0, 0));
        when(mensagemService.contarNaoLidasSolicitantePorSolicitacao(any(), any())).thenReturn(0L);
        when(solicitacaoService.diasEspera(solicitacaoDoDono))
            .thenReturn(new SolicitacaoOnlineService.DiasEspera(2, "bg-secondary"));

        mvc.perform(get("/solicitante"))
            .andExpect(status().isOk())
            .andExpect(view().name("solicitante/lista"))
            .andExpect(model().attributeExists("resumo"))
            .andExpect(model().attributeExists("diasEspera"));
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void listaExibeBannerDeAcaoNecessariaQuandoHaSolicitacaoAguardandoResposta() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.listarMinhas(1L)).thenReturn(java.util.List.of(solicitacaoDoDono));
        when(solicitacaoService.resumir(java.util.List.of(solicitacaoDoDono)))
            .thenReturn(new br.gov.saude.sgpur.service.SolicitacaoOnlineService.Resumo(1, 0, 0, 0, 1));
        when(mensagemService.contarNaoLidasSolicitantePorSolicitacao(any(), any())).thenReturn(0L);
        when(solicitacaoService.diasEspera(solicitacaoDoDono))
            .thenReturn(new SolicitacaoOnlineService.DiasEspera(2, "bg-secondary"));
        when(solicitacaoService.precisaInformacaoComplementar(solicitacaoDoDono)).thenReturn(true);

        mvc.perform(get("/solicitante"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("totalAcaoNecessaria", 1L))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "aguardando sua resposta")));
    }

    @Test
    @WithMockUser(username = "usuarioSemCadastro", roles = "SOLICITANTE")
    void resolverUsuarioLanca401QuandoUsuarioAutenticadoNaoExisteNoBanco() throws Exception {
        when(usuarioRepo.findByUsername("usuarioSemCadastro")).thenReturn(Optional.empty());

        mvc.perform(get("/solicitante"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void criarComAnexoFluxoFelizRedirecionaComMensagemDeSucesso() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        SolicitacaoOnline salva = new SolicitacaoOnline();
        salva.setId(60L);
        salva.setPacienteNome("Ciclano da Silva");
        salva.setPacienteRgct("987654321-12345");
        when(solicitacaoService.criar(any(SolicitacaoOnline.class), eq(dono), any()))
            .thenReturn(salva);

        MockMultipartFile documento = new MockMultipartFile("documentos", "laudo.pdf",
            MediaType.APPLICATION_PDF_VALUE, "conteudo".getBytes());

        mvc.perform(multipart("/solicitante/nova")
                .file(documento)
                .param("pacienteNome", "Ciclano da Silva")
                .param("pacienteRgct", "987654321-12345")
                .param("dataSituacaoEspecial", LocalDate.now().toString())
                .param("justificativaClinica", "Quadro clinico grave, necessita avaliacao urgente.")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/solicitante"));

        verify(solicitacaoService).criar(any(SolicitacaoOnline.class), eq(dono), any());
        org.mockito.ArgumentCaptor<String> detalheCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(eq("SOLICITACAO_ONLINE_ENVIADA"), detalheCaptor.capture());
        String detalhe = detalheCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(detalhe)
            .doesNotContain("Ciclano")
            .doesNotContain("Silva")
            .doesNotContain("987654321-12345")
            .contains("60")
            .contains("C.S."); // iniciais de "Ciclano da Silva" (conector "da" ignorado)
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void enviarMensagemAuditaSomenteIniciaisDoPacienteSemNomeCompletoOuRgct() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);

        mvc.perform(post("/solicitante/50/mensagem")
                .param("texto", "Ola, alguma novidade?")
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        org.mockito.ArgumentCaptor<String> detalheCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(eq("MENSAGEM_SOLICITANTE_ENVIADA"), detalheCaptor.capture());
        String detalhe = detalheCaptor.getValue();
        // solicitacaoDoDono: pacienteNome = "Fulano de Tal" (RGCT "123456789-12345")
        org.assertj.core.api.Assertions.assertThat(detalhe)
            .doesNotContain("Fulano")
            .doesNotContain("Tal")
            .doesNotContain("123456789-12345")
            .contains("50")
            .contains("F.T."); // "de" e conector, ignorado nas iniciais
    }

    @Test
    @WithMockUser(username = "solicitante2", roles = "SOLICITANTE")
    void baixarAnexoExibe403ParaSolicitacaoDeOutroUsuario() throws Exception {
        when(usuarioRepo.findByUsername("solicitante2")).thenReturn(Optional.of(outroUsuario));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);

        mvc.perform(get("/solicitante/50/anexos/999"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(anexoRepo);
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void baixarAnexoRetorna403QuandoAnexoNaoPertenceAEstaSolicitacao() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);

        SolicitacaoOnline outraSolicitacao = new SolicitacaoOnline();
        outraSolicitacao.setId(51L);
        AnexoSolicitacaoOnline anexoDeOutraSolicitacao = new AnexoSolicitacaoOnline();
        anexoDeOutraSolicitacao.setId(999L);
        anexoDeOutraSolicitacao.setSolicitacaoOnline(outraSolicitacao);
        when(anexoRepo.findById(999L)).thenReturn(Optional.of(anexoDeOutraSolicitacao));

        mvc.perform(get("/solicitante/50/anexos/999"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void baixarAnexoFuncionaParaAPropriaSolicitacao() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.buscar(50L)).thenReturn(solicitacaoDoDono);

        AnexoSolicitacaoOnline anexo = new AnexoSolicitacaoOnline();
        anexo.setId(999L);
        anexo.setSolicitacaoOnline(solicitacaoDoDono);
        anexo.setNomeArquivo("laudo.pdf");
        anexo.setContentType(MediaType.APPLICATION_PDF_VALUE);
        when(anexoRepo.findById(999L)).thenReturn(Optional.of(anexo));

        Path arquivo = tempDir.resolve("laudo.pdf");
        Files.write(arquivo, "conteudo".getBytes());
        when(anexoStorage.resolverArquivo(anexo)).thenReturn(arquivo);

        mvc.perform(get("/solicitante/50/anexos/999"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("laudo.pdf")));
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void criarComErroDeNegocioVoltaParaOFormularioComMensagemDeErro() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(solicitacaoService.criar(any(SolicitacaoOnline.class), eq(dono), any()))
            .thenThrow(new IllegalStateException("Usuario solicitante sem equipe vinculada."));

        mvc.perform(multipart("/solicitante/nova")
                .param("pacienteNome", "Ciclano da Silva")
                .param("pacienteRgct", "987654321-12345")
                .param("dataSituacaoEspecial", LocalDate.now().toString())
                .param("justificativaClinica", "Quadro clinico grave.")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("solicitante/nova"))
            .andExpect(model().attributeExists("erro"));
    }

    @Test
    @WithMockUser(username = "solicitante2", roles = "SOLICITANTE")
    void enviarInformacaoComplementarExibe403ParaSolicitacaoDeOutroUsuario() throws Exception {
        when(usuarioRepo.findByUsername("solicitante2")).thenReturn(Optional.of(outroUsuario));
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);

        MockMultipartFile arquivo = new MockMultipartFile("arquivos", "resposta.pdf",
            MediaType.APPLICATION_PDF_VALUE, "conteudo".getBytes());

        mvc.perform(multipart("/solicitante/50/informacao-complementar").file(arquivo).with(csrf()))
            .andExpect(status().isForbidden());

        verify(solicitacaoService, never()).enviarInformacaoComplementar(any(), any());
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void enviarInformacaoComplementarFluxoFelizRedirecionaComMensagemDeSucesso() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        br.gov.saude.sgpur.domain.Processo processo = new br.gov.saude.sgpur.domain.Processo();
        processo.setId(200L);
        processo.setNumero("05/2026");
        solicitacaoDoDono.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        solicitacaoDoDono.setProcessoGerado(processo);
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);

        MockMultipartFile arquivo = new MockMultipartFile("arquivos", "resposta.pdf",
            MediaType.APPLICATION_PDF_VALUE, "conteudo".getBytes());

        mvc.perform(multipart("/solicitante/50/informacao-complementar").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/solicitante/50"))
            .andExpect(flash().attributeExists("msg"));

        verify(solicitacaoService).enviarInformacaoComplementar(eq(solicitacaoDoDono), any());
        verify(auditoria).registrar(eq("INFO_COMPLEMENTAR_RECEBIDA_PORTAL"), any());
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void enviarInformacaoComplementarComEstadoErradoVoltaComFlashDeErroSemQuebrar() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        br.gov.saude.sgpur.domain.Processo processo = new br.gov.saude.sgpur.domain.Processo();
        processo.setId(200L);
        processo.setNumero("05/2026");
        solicitacaoDoDono.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        solicitacaoDoDono.setProcessoGerado(processo);
        when(solicitacaoService.buscarParaDetalhe(50L)).thenReturn(solicitacaoDoDono);
        doThrow(new IllegalStateException("Este pedido nao esta aguardando informacao complementar no momento."))
            .when(solicitacaoService).enviarInformacaoComplementar(eq(solicitacaoDoDono), any());

        MockMultipartFile arquivo = new MockMultipartFile("arquivos", "resposta.pdf",
            MediaType.APPLICATION_PDF_VALUE, "conteudo".getBytes());

        mvc.perform(multipart("/solicitante/50/informacao-complementar").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/solicitante/50"))
            .andExpect(flash().attributeExists("erro"));
    }

    // ----- Rascunho de "Nova solicitacao" (Fase 11, item 3) -----

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void novaSemRascunhoExistenteMostraFormularioEmBranco() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        when(rascunhoService.buscarPorUsuario(1L)).thenReturn(Optional.empty());

        mvc.perform(get("/solicitante/nova"))
            .andExpect(status().isOk())
            .andExpect(view().name("solicitante/nova"))
            .andExpect(model().attributeDoesNotExist("rascunhoSalvoEm"));
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void novaComRascunhoExistentePreenchePreviamenteOFormulario() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        br.gov.saude.sgpur.domain.RascunhoSolicitacaoOnline rascunho =
            new br.gov.saude.sgpur.domain.RascunhoSolicitacaoOnline();
        rascunho.setId(10L);
        rascunho.setUsuarioSolicitante(dono);
        rascunho.setPacienteNome("Rascunho Fulano");
        rascunho.setPacienteRgct("111111111-11111");
        rascunho.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        rascunho.setJustificativaClinica("Rascunho da justificativa.");
        java.time.LocalDateTime salvoEm = java.time.LocalDateTime.of(2026, 8, 4, 10, 30);
        rascunho.setAtualizadoEm(salvoEm);
        when(rascunhoService.buscarPorUsuario(1L)).thenReturn(Optional.of(rascunho));

        mvc.perform(get("/solicitante/nova"))
            .andExpect(status().isOk())
            .andExpect(view().name("solicitante/nova"))
            .andExpect(model().attribute("rascunhoSalvoEm", salvoEm))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Rascunho Fulano")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Rascunho da justificativa.")));
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void salvarRascunhoAceitaCamposParciaisEDevolveHorarioDeSalvamento() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        br.gov.saude.sgpur.domain.RascunhoSolicitacaoOnline salvo =
            new br.gov.saude.sgpur.domain.RascunhoSolicitacaoOnline();
        salvo.setId(11L);
        java.time.LocalDateTime agora = java.time.LocalDateTime.of(2026, 8, 4, 15, 0);
        salvo.setAtualizadoEm(agora);
        when(rascunhoService.salvar(eq(1L), eq("So o nome"), isNull(), isNull(), isNull()))
            .thenReturn(salvo);

        mvc.perform(post("/solicitante/nova/rascunho")
                .param("pacienteNome", "So o nome")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.salvoEm").value(agora.toString()));

        verify(rascunhoService).salvar(1L, "So o nome", null, null, null);
    }

    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void apagarRascunhoRedirecionaParaNovaComMensagemDeSucesso() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));

        mvc.perform(post("/solicitante/nova/rascunho/apagar").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/solicitante/nova"))
            .andExpect(flash().attribute("msg", "Rascunho descartado."));

        verify(rascunhoService).apagar(1L);
    }

    /**
     * Envio final funciona a partir de um rascunho (o formulario ja vem
     * pre-preenchido, mas o POST /nova continua sendo o mesmo endpoint de
     * sempre, com a MESMA validacao completa - o rascunho nao contorna nada)
     * e apaga o rascunho depois de criar a solicitacao de verdade.
     */
    @Test
    @WithMockUser(username = "solicitante1", roles = "SOLICITANTE")
    void criarAPartirDeUmRascunhoApagaORascunhoAposEnvioComSucesso() throws Exception {
        when(usuarioRepo.findByUsername("solicitante1")).thenReturn(Optional.of(dono));
        SolicitacaoOnline salva = new SolicitacaoOnline();
        salva.setId(61L);
        salva.setPacienteNome("Rascunho Fulano");
        when(solicitacaoService.criar(any(SolicitacaoOnline.class), eq(dono), any()))
            .thenReturn(salva);

        mvc.perform(multipart("/solicitante/nova")
                .param("pacienteNome", "Rascunho Fulano")
                .param("pacienteRgct", "111111111-11111")
                .param("dataSituacaoEspecial", LocalDate.now().toString())
                .param("justificativaClinica", "Justificativa completa, quadro grave.")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/solicitante"));

        verify(solicitacaoService).criar(any(SolicitacaoOnline.class), eq(dono), any());
        verify(rascunhoService).apagar(1L);
    }
}

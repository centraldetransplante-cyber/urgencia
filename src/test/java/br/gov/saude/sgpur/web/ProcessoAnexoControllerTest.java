package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.DecisaoFinalService;
import br.gov.saude.sgpur.service.GeminiService;
import br.gov.saude.sgpur.service.OficioService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import br.gov.saude.sgpur.service.RelatorioService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do ProcessoAnexoController: passos 5/6 (finalizacao, oficio,
 * comprovantes, resposta ao solicitante), upload/download/exclusao de
 * anexos, geracao de PDFs (relatorio/oficio) e o resumo por IA de um anexo.
 */
@WebMvcTest(ProcessoAnexoController.class)
class ProcessoAnexoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoService processoService;
    @MockitoBean private ProcessoValidator validator;
    @MockitoBean private AnexoStorageService anexoStorage;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private OficioService oficioService;
    @MockitoBean private RelatorioService relatorioService;
    @MockitoBean private GeminiService geminiService;
    @MockitoBean private DecisaoFinalService decisaoFinalService;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas duas pro
    // contexto do @WebMvcTest subir - ver ArquivoControllerTest.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private SolicitacaoOnlineService solicitacaoOnlineService;

    @TempDir
    Path tempDir;

    private Processo processo;

    @BeforeEach
    void setUp() {
        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("01/2026");
        processo.setStatus(StatusProcesso.ENVIADO);
        when(processoService.buscar(1L)).thenReturn(processo);
        when(validator.edicaoBloqueada(processo)).thenReturn(false);
        // substituirAnexo salva o novo primeiro e usa o id dele para remover
        // so os antigos (removerAntigosDoTipo) - o mock de salvar() precisa
        // devolver um Anexo com id, senao NPE ao ler novo.getId().
        Anexo anexoSalvo = new Anexo();
        anexoSalvo.setId(99L);
        try {
            when(anexoStorage.salvar(any(), any(), any(), any())).thenReturn(anexoSalvo);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] pdfValido(String texto) {
        Document doc = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();
            doc.add(new Paragraph(texto));
            doc.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    // ----- datas de finalizacao: gravadas pelo sistema, nunca digitadas -----

    /**
     * O endpoint POST /{id}/finalizacao (campos de data editaveis) foi
     * REMOVIDO em 2026-08-04: a data de um ato registrado por anexo passou a
     * ser sempre o momento do anexo, pelo relogio do servidor. Um <input
     * type="date"> aceitava data retroativa, o que e inadmissivel num
     * processo administrativo. Este teste trava a remocao - se alguem
     * reintroduzir o endpoint, ele falha.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void endpointDeEdicaoManualDeDatasNaoExisteMais() throws Exception {
        processo.setStatus(StatusProcesso.INDEFERIDO);

        mvc.perform(post("/processos/1/finalizacao")
                .param("dataEmissaoOficio", "2020-01-01")
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    // ----- oficio-upload -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void uploadOficioBloqueadoSeProcessoNaoIndeferido() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "oficio.pdf",
            "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/oficio-upload").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro",
                "Upload de oficio so e permitido para processos Indeferidos."));

        verify(anexoStorage, never()).salvar(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void uploadOficioComSucessoRegistraAuditoria() throws Exception {
        processo.setStatus(StatusProcesso.INDEFERIDO);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "oficio.pdf",
            "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/oficio-upload").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", "Oficio de indeferimento anexado (data de emissao registrada com a data de hoje)."));

        verify(anexoStorage).salvar(eq(processo), eq(TipoAnexo.OFICIO_INDEFERIMENTO), anyString(), eq(arquivo));
        verify(auditoria).registrar(eq("ANEXO_ADICIONADO"), anyString());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void uploadOficioComFalhaDeIOEhCapturadaComoFlashDeErro() throws Exception {
        processo.setStatus(StatusProcesso.INDEFERIDO);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "oficio.pdf",
            "application/pdf", "conteudo".getBytes());
        when(anexoStorage.salvar(any(), any(), any(), any())).thenThrow(new IOException("disco cheio"));

        mvc.perform(multipart("/processos/1/oficio-upload").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("disco cheio")));
    }

    // ----- comprovante-envio-solicitante -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void uploadComprovanteEnvioSolicitanteComSucesso() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "comprovante.pdf",
            "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/comprovante-envio-solicitante").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", "Comprovante de envio ao solicitante anexado."));

        verify(auditoria).registrar(eq("ANEXO_ADICIONADO"), anyString());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void uploadComprovanteEnvioSolicitanteComFalhaVoltaFlashDeErro() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "comprovante.pdf",
            "application/pdf", "conteudo".getBytes());
        when(anexoStorage.salvar(any(), any(), any(), any())).thenThrow(new IllegalArgumentException("tipo invalido"));

        mvc.perform(multipart("/processos/1/comprovante-envio-solicitante").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("tipo invalido")));
    }

    // ----- comprovante-snt -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void uploadComprovanteSntBloqueadoSeProcessoNaoDeferido() throws Exception {
        processo.setStatus(StatusProcesso.INDEFERIDO);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "snt.pdf",
            "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/comprovante-snt").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro",
                "Upload do comprovante SNT so e permitido para processos Deferidos."));

        verify(anexoStorage, never()).salvar(any(), any(), any(), any());
    }

    /**
     * Correcao de continuacao do PR #126 ("paciente preemptivo"): a tela ja
     * escondia este formulario para processo preemptivo (processos/
     * detalhe.html), mas o endpoint em si nao recusava um POST direto -
     * paciente preemptivo NAO TEM comprovante SNT (a decisao ja autoriza a
     * inscricao na lista de espera, sem anexo).
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void uploadComprovanteSntBloqueadoSeProcessoPreemptivo() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        processo.setPreemptivo(true);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "snt.pdf",
            "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/comprovante-snt").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("preemptivo")));

        verify(anexoStorage, never()).salvar(any(), any(), any(), any());
        verify(processoService, never()).registrarDataEnvioSnt(anyLong());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void uploadComprovanteSntComSucesso() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "snt.pdf",
            "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/comprovante-snt").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg",
                "Comprovante SNT anexado (data de envio ao SNT registrada com a data de hoje)."));

        verify(auditoria).registrar(eq("ANEXO_ADICIONADO"), anyString());
        // A data de envio ao SNT e gravada aqui, no momento do anexo - nao ha
        // mais campo na tela para digita-la (nem retroativamente).
        verify(processoService).registrarDataEnvioSnt(1L);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void uploadDoOficioRegistraADataDeEmissaoComADataDeHoje() throws Exception {
        processo.setStatus(StatusProcesso.INDEFERIDO);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "oficio.pdf",
            "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/oficio-upload").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg",
                "Oficio de indeferimento anexado (data de emissao registrada com a data de hoje)."));

        verify(processoService).registrarDataEmissaoOficio(1L);
    }

    /**
     * Se o anexo falhar, a data NAO pode avancar - senao o processo passaria a
     * exibir uma data de emissao/envio de um documento que nunca entrou.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void anexoQueFalhaNaoRegistraDataNenhuma() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        when(anexoStorage.salvar(any(), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("extensao nao permitida"));
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "snt.exe",
            "application/octet-stream", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/comprovante-snt").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("erro"));

        verify(processoService, never()).registrarDataEnvioSnt(anyLong());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void uploadComprovanteSntComAnexoAntigoQuebradoNaoQuebraOUpload() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        Anexo anexoAntigo = new Anexo();
        anexoAntigo.setId(10L);
        anexoAntigo.setTipo(TipoAnexo.COMPROVANTE_SNT);
        processo.setAnexos(java.util.List.of(anexoAntigo));
        when(anexoStorage.resolverArquivo(anexoAntigo)).thenThrow(new NullPointerException("caminho nulo"));

        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "snt.pdf",
            "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/comprovante-snt").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", "Comprovante SNT anexado (data de envio ao SNT registrada com a data de hoje)."));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void uploadComprovanteSntComErroRuntimeNoCleanupNaoQuebraOUpload() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        Anexo anexoAntigo = new Anexo();
        anexoAntigo.setId(10L);
        anexoAntigo.setTipo(TipoAnexo.COMPROVANTE_SNT);
        processo.setAnexos(java.util.List.of(anexoAntigo));
        when(anexoStorage.resolverArquivo(anexoAntigo)).thenThrow(new RuntimeException("erro de storage"));

        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "snt.pdf",
            "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/comprovante-snt").file(arquivo).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", "Comprovante SNT anexado (data de envio ao SNT registrada com a data de hoje)."));
    }

    // ----- anexos (upload generico) -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void anexarBloqueadoQuandoProcessoEncerrado() throws Exception {
        when(validator.edicaoBloqueada(processo)).thenReturn(true);
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "doc.pdf",
            "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/anexos")
                .file(arquivo)
                .param("tipo", "OUTRO")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1#anexos"))
            .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));

        verify(anexoStorage, never()).salvar(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void anexarComSucesso() throws Exception {
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "doc.pdf",
            "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/processos/1/anexos")
                .file(arquivo)
                .param("tipo", "OUTRO")
                .param("descricao", "Documento extra")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("msg", "Anexo enviado."));

        verify(anexoStorage).salvar(processo, TipoAnexo.OUTRO, "Documento extra", arquivo);
        verify(auditoria).registrar(eq("ANEXO_ADICIONADO"), anyString());
    }

    // ----- excluir anexo -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void excluirAnexoBloqueadoQuandoProcessoEncerrado() throws Exception {
        Anexo anexo = new Anexo();
        anexo.setId(5L);
        anexo.setProcesso(processo);
        anexo.setTipo(TipoAnexo.OUTRO);
        when(anexoStorage.buscar(5L)).thenReturn(anexo);
        when(validator.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(post("/processos/anexos/5/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1#anexos"))
            .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));

        verify(anexoStorage, never()).excluir(any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void excluirAnexoComumRemoveNormalmente() throws Exception {
        Anexo anexo = new Anexo();
        anexo.setId(5L);
        anexo.setProcesso(processo);
        anexo.setTipo(TipoAnexo.OUTRO);
        when(anexoStorage.buscar(5L)).thenReturn(anexo);
        when(anexoStorage.excluir(5L)).thenReturn(1L);

        mvc.perform(post("/processos/anexos/5/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1#anexos"))
            .andExpect(flash().attribute("msg", "Anexo removido."));
    }

    // ----- PDFs gerados (relatorio / oficio) -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void relatorioGeraOPdfDoProcesso() throws Exception {
        when(relatorioService.gerar(processo)).thenReturn("relatorio-fake".getBytes());

        mvc.perform(get("/processos/1/relatorio"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("relatorio-processo-01-2026.pdf")))
            .andExpect(content().bytes("relatorio-fake".getBytes()));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void oficioGeraOPdfDoProcesso() throws Exception {
        processo.setStatus(StatusProcesso.INDEFERIDO);
        when(oficioService.gerar(processo)).thenReturn("oficio-fake".getBytes());

        mvc.perform(get("/processos/1/oficio"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("oficio-indeferimento-01-2026.pdf")))
            .andExpect(content().bytes("oficio-fake".getBytes()));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void oficioRecusaProcessoDeferidoCom400() throws Exception {
        // Antes de 2026-08-04 esta URL gerava um "Oficio de Indeferimento"
        // para QUALQUER processo, inclusive um Deferido - um documento que
        // contradiz a propria decisao do processo.
        processo.setStatus(StatusProcesso.DEFERIDO);

        mvc.perform(get("/processos/1/oficio"))
            .andExpect(status().isBadRequest());

        verify(oficioService, never()).gerar(any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void oficioRecusaProcessoAindaEmAndamentoCom400() throws Exception {
        processo.setStatus(StatusProcesso.ENVIADO);

        mvc.perform(get("/processos/1/oficio"))
            .andExpect(status().isBadRequest());

        verify(oficioService, never()).gerar(any());
    }

    // ----- download de anexo -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void baixarAnexoRetorna404QuandoArquivoNaoExisteNoDisco() throws Exception {
        Anexo anexo = new Anexo();
        anexo.setId(5L);
        anexo.setNomeArquivo("sumiu.pdf");
        when(anexoStorage.buscar(5L)).thenReturn(anexo);
        when(anexoStorage.resolverArquivo(anexo)).thenReturn(tempDir.resolve("nao-existe.pdf"));

        mvc.perform(get("/processos/anexos/5/download"))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void baixarAnexoComSucessoUsaOContentTypeDoAnexo() throws Exception {
        Path arquivo = tempDir.resolve("comprovante.pdf");
        Files.write(arquivo, "conteudo-real".getBytes());
        Anexo anexo = new Anexo();
        anexo.setId(5L);
        anexo.setNomeArquivo("comprovante.pdf");
        anexo.setContentType("application/pdf");
        when(anexoStorage.buscar(5L)).thenReturn(anexo);
        when(anexoStorage.resolverArquivo(anexo)).thenReturn(arquivo);

        mvc.perform(get("/processos/anexos/5/download"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("attachment")))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("comprovante.pdf")))
            .andExpect(content().bytes("conteudo-real".getBytes()));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void baixarAnexoSemContentTypeUsaOctetStreamComoFallback() throws Exception {
        Path arquivo = tempDir.resolve("arquivo-sem-tipo");
        Files.write(arquivo, "bytes".getBytes());
        Anexo anexo = new Anexo();
        anexo.setId(5L);
        anexo.setNomeArquivo("arquivo-sem-tipo");
        anexo.setContentType(null);
        when(anexoStorage.buscar(5L)).thenReturn(anexo);
        when(anexoStorage.resolverArquivo(anexo)).thenReturn(arquivo);

        mvc.perform(get("/processos/anexos/5/download"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM));
    }

    // ----- resumo por IA -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void resumoIaRetornaErroQuandoIaNaoDisponivel() throws Exception {
        when(geminiService.isDisponivel()).thenReturn(false);

        mvc.perform(get("/processos/anexos/5/resumo-ia"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.erro").value("Assistencia por IA nao configurada."))
            .andExpect(jsonPath("$.texto").doesNotExist());

        verify(anexoStorage, never()).buscar(any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void resumoIaRetornaErroQuandoAnexoNaoEhPdf() throws Exception {
        when(geminiService.isDisponivel()).thenReturn(true);
        Anexo anexo = new Anexo();
        anexo.setId(5L);
        anexo.setContentType("image/png");
        when(anexoStorage.buscar(5L)).thenReturn(anexo);

        mvc.perform(get("/processos/anexos/5/resumo-ia"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.erro").value("Resumo por IA disponivel apenas para anexos em PDF."));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void resumoIaRetornaErroQuandoArquivoNaoPodeSerLido() throws Exception {
        when(geminiService.isDisponivel()).thenReturn(true);
        Anexo anexo = new Anexo();
        anexo.setId(5L);
        anexo.setContentType("application/pdf");
        when(anexoStorage.buscar(5L)).thenReturn(anexo);
        when(anexoStorage.resolverArquivo(anexo)).thenReturn(tempDir.resolve("nao-existe.pdf"));

        mvc.perform(get("/processos/anexos/5/resumo-ia"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.erro").value(org.hamcrest.Matchers.containsString("Falha ao ler o PDF")));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void resumoIaComSucessoExtraiTextoEChamaGemini() throws Exception {
        when(geminiService.isDisponivel()).thenReturn(true);
        Path pdf = tempDir.resolve("laudo.pdf");
        Files.write(pdf, pdfValido("Achados clinicos relevantes do paciente."));
        Anexo anexo = new Anexo();
        anexo.setId(5L);
        anexo.setContentType("application/pdf");
        when(anexoStorage.buscar(5L)).thenReturn(anexo);
        when(anexoStorage.resolverArquivo(anexo)).thenReturn(pdf);
        when(geminiService.perguntar(anyString())).thenReturn(Optional.of("Resumo gerado pela IA."));

        mvc.perform(get("/processos/anexos/5/resumo-ia"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.texto").value("Resumo gerado pela IA."))
            .andExpect(jsonPath("$.erro").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void resumoIaRedigeDadosSensiveisAntesDeChamarGemini() throws Exception {
        when(geminiService.isDisponivel()).thenReturn(true);
        Path pdf = tempDir.resolve("laudo.pdf");
        Files.write(pdf, pdfValido(
            "Paciente Maria da Silva, CPF 123.456.789-00, nascida em 01/02/1980, "
            + "RGCT AB1234, apresenta quadro renal grave."));
        Anexo anexo = new Anexo();
        anexo.setId(5L);
        anexo.setContentType("application/pdf");
        Processo processo = new Processo();
        processo.setPacienteNome("Maria da Silva");
        processo.setPacienteRgct("AB1234");
        anexo.setProcesso(processo);
        when(anexoStorage.buscar(5L)).thenReturn(anexo);
        when(anexoStorage.resolverArquivo(anexo)).thenReturn(pdf);
        when(geminiService.perguntar(anyString())).thenReturn(Optional.of("Resumo gerado pela IA."));

        mvc.perform(get("/processos/anexos/5/resumo-ia"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.texto").value("Resumo gerado pela IA."));

        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(geminiService).perguntar(promptCaptor.capture());
        String prompt = promptCaptor.getValue();

        org.assertj.core.api.Assertions.assertThat(prompt)
            .doesNotContain("Maria")
            .doesNotContain("Silva")
            .doesNotContain("123.456.789-00")
            .doesNotContain("01/02/1980")
            .doesNotContain("AB1234")
            .contains("[REDIGIDO]");
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void resumoIaRetornaErroQuandoGeminiFalha() throws Exception {
        when(geminiService.isDisponivel()).thenReturn(true);
        Path pdf = tempDir.resolve("laudo.pdf");
        Files.write(pdf, pdfValido("Texto clinico."));
        Anexo anexo = new Anexo();
        anexo.setId(5L);
        anexo.setContentType("application/pdf");
        when(anexoStorage.buscar(5L)).thenReturn(anexo);
        when(anexoStorage.resolverArquivo(anexo)).thenReturn(pdf);
        when(geminiService.perguntar(anyString())).thenReturn(Optional.empty());

        mvc.perform(get("/processos/anexos/5/resumo-ia"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.erro").value("Falha ao consultar a IA. Tente novamente."));
    }
}

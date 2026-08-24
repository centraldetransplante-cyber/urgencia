package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cobre a logica de negocio extraida de
 * {@code ProcessoDecisaoController.registrarEnvio}: documento clinico PDF
 * obrigatorio, PDFs corrompidos/sem paginas ficam de fora da consolidacao
 * (aviso, nao bloqueio automatico), e o service so efetiva o envio
 * (processoService.registrarEnvio) quando ha ao menos um PDF valido.
 */
@ExtendWith(MockitoExtension.class)
class RegistroEnvioServiceTest {

    @Mock
    ProcessoService processoService;
    @Mock
    SolicitacaoAvaliadorService solicitacaoAvaliadorService;
    @Mock
    AnexoStorageService anexoStorage;
    @Mock
    AuditoriaService auditoria;
    @Mock
    EmailTemplateService emailTemplateService;
    @Mock
    EmailSenderService emailSenderService;

    RegistroEnvioService service;

    @TempDir
    Path tempDir;

    private Processo processo;

    @BeforeEach
    void setUp() {
        service = new RegistroEnvioService(processoService, solicitacaoAvaliadorService, anexoStorage,
            auditoria, emailTemplateService, emailSenderService, 300);

        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("01/2026");
        processo.setPacienteNome("Fulano de Tal");
        processo.addParecer(new Parecer(new br.gov.saude.sgpur.domain.MembroUrgenciaRenal("HCPA", "Medico", null)));

        when(processoService.buscar(1L)).thenReturn(processo);
    }

    /** Gera um PDF minimo (1 pagina) valido, para simular um documento clinico real. */
    private byte[] pdfValido() {
        try {
            Document doc = new Document(PageSize.A4);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();
            doc.add(new Paragraph("Documento clinico de teste"));
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Gera um PDF com N paginas - usado para exercitar o teto de paginas. */
    private byte[] pdfComPaginas(int numPaginas) {
        try {
            Document doc = new Document(PageSize.A4);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();
            for (int i = 0; i < numPaginas; i++) {
                doc.add(new Paragraph("Pagina " + (i + 1)));
                if (i < numPaginas - 1) {
                    doc.newPage();
                }
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Anexo documentoClinicoPdf(String nome, byte[] bytes) throws Exception {
        Anexo a = new Anexo();
        a.setTipo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        a.setNomeArquivo(nome);
        a.setContentType("application/pdf");
        Path arquivo = tempDir.resolve(nome);
        Files.write(arquivo, bytes);
        // lenient: em cenarios onde o service bloqueia ANTES de ler o arquivo
        // (ex.: nenhum documento clinico valido), este stub nunca chega a ser
        // usado - sem lenient, o modo STRICT_STUBS padrao do MockitoExtension
        // falha com UnnecessaryStubbingException nesses testes.
        org.mockito.Mockito.lenient().when(anexoStorage.resolverArquivo(a)).thenReturn(arquivo);
        return a;
    }

    @Test
    void sucessoComUmDocumentoClinicoPdfValido() throws Exception {
        processo.addAnexo(documentoClinicoPdf("exame.pdf", pdfValido()));

        when(solicitacaoAvaliadorService.consolidar(any())).thenReturn(pdfValido());
        when(solicitacaoAvaliadorService.carimbarCabecalho(any(), eq(processo))).thenReturn(pdfValido());
        Anexo novoAnexo = new Anexo();
        novoAnexo.setId(99L);
        when(anexoStorage.salvarBytes(eq(processo), eq(TipoAnexo.SOLICITACAO_AVALIADOR),
            anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(novoAnexo);
        when(processoService.registrarEnvio(1L)).thenReturn(processo);

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isTrue();
        assertThat(resultado.mensagemErro()).isNull();
        assertThat(resultado.mensagemSucesso()).contains("Envio aos avaliadores registrado em");
        assertThat(resultado.avisos()).isEmpty();

        verify(anexoStorage).removerAntigosDoTipo(processo, TipoAnexo.SOLICITACAO_AVALIADOR, 99L);
        verify(processoService).salvar(processo);
        verify(processoService).registrarEnvio(1L);
        verify(auditoria).registrar(eq("ANEXO_ADICIONADO"), anyString());
        verify(auditoria).registrar(eq("ENVIO_AVALIADORES_REGISTRADO"), anyString());
    }

    /**
     * Bug real corrigido (2026-08-03): reenviar documentos atualizados aos
     * avaliadores NAO pode sobrescrever dataEnvio de quem JA respondeu -
     * antes, o forEach rodava sobre todos os pareceres sem filtro, deixando
     * dataEnvio > dataResposta pra quem ja tinha votado. TempoRespostaService
     * descarta silenciosamente pareceres com dias negativos, entao o parecer
     * simplesmente sumia das metricas sem nenhum aviso.
     */
    @Test
    void reenvioSoAtualizaDataEnvioDeQuemAindaNaoRespondeu() throws Exception {
        Parecer jaRespondeu = processo.getPareceres().get(0);
        java.time.LocalDate dataEnvioOriginal = java.time.LocalDate.of(2026, 7, 1);
        jaRespondeu.setDataEnvio(dataEnvioOriginal);
        jaRespondeu.setResultado(br.gov.saude.sgpur.domain.ResultadoParecer.FAVORAVEL);
        jaRespondeu.setDataResposta(java.time.LocalDate.of(2026, 7, 5));

        Parecer aindaPendente = new Parecer(new br.gov.saude.sgpur.domain.MembroUrgenciaRenal("HNSC", "Medico 2", null));
        processo.addParecer(aindaPendente);

        processo.addAnexo(documentoClinicoPdf("exame-atualizado.pdf", pdfValido()));
        when(solicitacaoAvaliadorService.consolidar(any())).thenReturn(pdfValido());
        when(solicitacaoAvaliadorService.carimbarCabecalho(any(), eq(processo))).thenReturn(pdfValido());
        Anexo novoAnexo = new Anexo();
        novoAnexo.setId(101L);
        when(anexoStorage.salvarBytes(eq(processo), eq(TipoAnexo.SOLICITACAO_AVALIADOR),
            anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(novoAnexo);
        when(processoService.registrarEnvio(1L)).thenReturn(processo);

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isTrue();
        assertThat(jaRespondeu.getDataEnvio())
            .as("dataEnvio de quem ja respondeu nao pode ser sobrescrita pelo reenvio")
            .isEqualTo(dataEnvioOriginal);
        assertThat(aindaPendente.getDataEnvio())
            .as("dataEnvio de quem ainda nao respondeu deve ser atualizada pelo reenvio")
            .isEqualTo(java.time.LocalDate.now());
    }

    @Test
    void pdfCorrompidoFicaDeForaComAvisoMasEnvioSeguePorHaverOutroValido() throws Exception {
        processo.addAnexo(documentoClinicoPdf("bom.pdf", pdfValido()));
        // bytes que nao formam um PDF valido - PdfReader lanca excecao ao ler
        processo.addAnexo(documentoClinicoPdf("corrompido.pdf", "isto nao e um pdf valido".getBytes()));

        when(solicitacaoAvaliadorService.consolidar(any())).thenReturn(pdfValido());
        when(solicitacaoAvaliadorService.carimbarCabecalho(any(), eq(processo))).thenReturn(pdfValido());
        Anexo novoAnexo = new Anexo();
        novoAnexo.setId(100L);
        when(anexoStorage.salvarBytes(eq(processo), eq(TipoAnexo.SOLICITACAO_AVALIADOR),
            anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(novoAnexo);
        when(processoService.registrarEnvio(1L)).thenReturn(processo);

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isTrue();
        assertThat(resultado.avisos()).anyMatch(a -> a.contains("corrompido.pdf"));
        verify(processoService).registrarEnvio(1L);
    }

    @Test
    void bloqueiaSemNenhumDocumentoClinicoPdfValido() {
        // sem nenhum documento clinico anexado

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isFalse();
        assertThat(resultado.mensagemErro()).contains("documento clinico");
        verifyNoInteractions(solicitacaoAvaliadorService);
        verify(processoService, org.mockito.Mockito.never()).registrarEnvio(any());
    }

    /** Anexo do Portal do Solicitante ainda nao revisado (staging). */
    private Anexo documentoPortalPendente(String nome) throws Exception {
        Anexo a = new Anexo();
        a.setTipo(TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO);
        a.setNomeArquivo(nome);
        a.setContentType("application/pdf");
        Path arquivo = tempDir.resolve(nome);
        Files.write(arquivo, pdfValido());
        org.mockito.Mockito.lenient().when(anexoStorage.resolverArquivo(a)).thenReturn(arquivo);
        return a;
    }

    /**
     * TRAVA DE ANONIMIZACAO: com SO o documento vindo do portal (staging), o
     * envio e BLOQUEADO com mensagem especifica - nao passa silenciosamente
     * nem entrega o laudo com o nome do paciente aos 3 avaliadores.
     */
    @Test
    void bloqueiaQuandoSoHaDocumentoDoPortalPendenteDeAnonimizacao() throws Exception {
        processo.addAnexo(documentoPortalPendente("laudo-original.pdf"));

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isFalse();
        assertThat(resultado.mensagemErro())
            .contains("Confirme a anonimizacao")
            .contains("laudo-original.pdf");
        verifyNoInteractions(solicitacaoAvaliadorService);
        verify(processoService, org.mockito.Mockito.never()).registrarEnvio(any());
    }

    /**
     * O documento em staging NUNCA entra no PDF consolidado: mesmo havendo um
     * documento anonimizado valido (que libera o envio), so ele e fundido, e o
     * pendente vira aviso nao bloqueante.
     */
    @Test
    void documentoDoPortalPendenteNaoEntraNoPdfConsolidado() throws Exception {
        byte[] bytesAnonimizado = pdfValido();
        processo.addAnexo(documentoClinicoPdf("anonimizado.pdf", bytesAnonimizado));
        processo.addAnexo(documentoPortalPendente("laudo-original.pdf"));

        when(solicitacaoAvaliadorService.consolidar(any())).thenReturn(pdfValido());
        when(solicitacaoAvaliadorService.carimbarCabecalho(any(), eq(processo))).thenReturn(pdfValido());
        Anexo novoAnexo = new Anexo();
        novoAnexo.setId(101L);
        when(anexoStorage.salvarBytes(eq(processo), eq(TipoAnexo.SOLICITACAO_AVALIADOR),
            anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(novoAnexo);
        when(processoService.registrarEnvio(1L)).thenReturn(processo);

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isTrue();
        assertThat(resultado.avisos()).anyMatch(a -> a.contains("laudo-original.pdf"));
        // So o documento anonimizado foi para a consolidacao. O captor e criado
        // com ArgumentCaptor.captor() (Mockito 5.7+) em vez de
        // forClass(List.class): forClass devolve o tipo cru e obriga a um cast
        // com aviso de unchecked em cada uso.
        org.mockito.ArgumentCaptor<java.util.List<byte[]>> captor = org.mockito.ArgumentCaptor.captor();
        verify(solicitacaoAvaliadorService).consolidar(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0)).isEqualTo(bytesAnonimizado);
    }

    /**
     * Processo LEGADO (convertido antes da trava): o anexo do portal foi
     * gravado como DOCUMENTO_CLINICO_AVALIADOR e continua elegivel - a
     * mudanca nao quebra processos ja existentes.
     */
    @Test
    void processoLegadoComDocumentoDoPortalNoTipoAntigoContinuaEnviando() throws Exception {
        processo.addAnexo(documentoClinicoPdf("laudo-legado.pdf", pdfValido()));

        when(solicitacaoAvaliadorService.consolidar(any())).thenReturn(pdfValido());
        when(solicitacaoAvaliadorService.carimbarCabecalho(any(), eq(processo))).thenReturn(pdfValido());
        Anexo novoAnexo = new Anexo();
        novoAnexo.setId(102L);
        when(anexoStorage.salvarBytes(eq(processo), eq(TipoAnexo.SOLICITACAO_AVALIADOR),
            anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(novoAnexo);
        when(processoService.registrarEnvio(1L)).thenReturn(processo);

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isTrue();
        assertThat(resultado.avisos()).isEmpty();
        verify(processoService).registrarEnvio(1L);
    }

    @Test
    void bloqueiaQuandoTodosOsPdfsEstaoCorrompidos() throws Exception {
        processo.addAnexo(documentoClinicoPdf("corrompido.pdf", "nao e pdf".getBytes()));

        RegistroEnvioService.RegistroEnvioResultado resultado = service.registrar(1L);

        assertThat(resultado.ok()).isFalse();
        assertThat(resultado.mensagemErro()).contains("PDF valido");
        verifyNoInteractions(solicitacaoAvaliadorService);
        verify(processoService, org.mockito.Mockito.never()).registrarEnvio(any());
    }

    // -------------------------------------------------------------------------
    // Teto de paginas por PDF (defesa contra DoS por CPU/memoria, 2026-08-24).
    // -------------------------------------------------------------------------

    /**
     * Um PDF que excede o teto de paginas fica de fora da consolidacao (mesmo
     * tratamento de "aviso, nao bloqueio automatico" ja usado para PDF
     * corrompido) - o envio segue normalmente se houver outro documento
     * valido dentro do limite.
     */
    @Test
    void documentoQueExcedeTetoDePaginasFicaDeForaComAvisoMasEnvioSeguePorHaverOutroValido() throws Exception {
        RegistroEnvioService servicoComTetoBaixo = new RegistroEnvioService(processoService,
            solicitacaoAvaliadorService, anexoStorage, auditoria, emailTemplateService, emailSenderService, 2);
        processo.addAnexo(documentoClinicoPdf("bom.pdf", pdfComPaginas(1)));
        processo.addAnexo(documentoClinicoPdf("gigante.pdf", pdfComPaginas(5)));

        when(solicitacaoAvaliadorService.consolidar(any())).thenReturn(pdfValido());
        when(solicitacaoAvaliadorService.carimbarCabecalho(any(), eq(processo))).thenReturn(pdfValido());
        Anexo novoAnexo = new Anexo();
        novoAnexo.setId(200L);
        when(anexoStorage.salvarBytes(eq(processo), eq(TipoAnexo.SOLICITACAO_AVALIADOR),
            anyString(), anyString(), anyString(), any(byte[].class))).thenReturn(novoAnexo);
        when(processoService.registrarEnvio(1L)).thenReturn(processo);

        RegistroEnvioService.RegistroEnvioResultado resultado = servicoComTetoBaixo.registrar(1L);

        assertThat(resultado.ok()).isTrue();
        assertThat(resultado.avisos()).anyMatch(a -> a.contains("gigante.pdf") && a.contains("excede o limite"));
        verify(processoService).registrarEnvio(1L);
    }

    /**
     * Se TODOS os documentos excedem o teto, o envio e bloqueado com mensagem
     * clara (nunca 500) - e a mensagem tem que citar o MOTIVO REAL (teto de
     * paginas excedido, achado real de revisao do PR #120), nao so o texto
     * generico de "sem paginas", que confundia o operador e nao dava
     * nenhuma pista de como resolver.
     */
    @Test
    void bloqueiaQuandoTodosOsDocumentosExcedemOTetoDePaginasComMensagemCitandoOMotivo() throws Exception {
        RegistroEnvioService servicoComTetoBaixo = new RegistroEnvioService(processoService,
            solicitacaoAvaliadorService, anexoStorage, auditoria, emailTemplateService, emailSenderService, 2);
        processo.addAnexo(documentoClinicoPdf("gigante.pdf", pdfComPaginas(5)));

        RegistroEnvioService.RegistroEnvioResultado resultado = servicoComTetoBaixo.registrar(1L);

        assertThat(resultado.ok()).isFalse();
        assertThat(resultado.mensagemErro())
            .contains("gigante.pdf")
            .contains("excede o limite")
            .contains("2 paginas");
        verifyNoInteractions(solicitacaoAvaliadorService);
        verify(processoService, org.mockito.Mockito.never()).registrarEnvio(any());
    }

    // -------------------------------------------------------------------------
    // Convite automatico ao Portal do Avaliador (enviarConvitesAvaliadores).
    // Roda DEPOIS de registrar() ter commitado; nenhuma falha aqui pode derrubar
    // o envio - so vira aviso.
    // -------------------------------------------------------------------------

    /** Parecer pendente (resultado nulo) de um avaliador com o e-mail informado. */
    private Parecer parecerPendente(String nome, String email) {
        return new Parecer(new br.gov.saude.sgpur.domain.MembroUrgenciaRenal("HCPA", nome, email));
    }

    private EmailTemplate templateConvite() {
        return new EmailTemplate("convite-avaliador", "Convite", "person-check",
            "Assunto do convite", "Corpo do convite");
    }

    @Test
    void enviaConviteDoPortalParaCadaAvaliadorPendente() {
        Parecer a = parecerPendente("Dr. A", "a@hcpa.br");
        Parecer b = parecerPendente("Dr. B", "b@hcpa.br");
        when(processoService.pareceresPendentesComEmail(1L)).thenReturn(java.util.List.of(a, b));
        when(processoService.reivindicarConviteAvaliador(any(), anyInt())).thenReturn(true);
        when(emailTemplateService.emailConviteAvaliador(eq(processo), any())).thenReturn(templateConvite());
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        RegistroEnvioService.ConvitesResultado r = service.enviarConvitesAvaliadores(1L);

        assertThat(r.enviados()).isEqualTo(2);
        assertThat(r.avisos()).isEmpty();
        verify(emailSenderService).enviar("a@hcpa.br", "Assunto do convite", "Corpo do convite");
        verify(emailSenderService).enviar("b@hcpa.br", "Assunto do convite", "Corpo do convite");
        verify(auditoria, org.mockito.Mockito.times(2))
            .registrar(eq("CONVITE_AVALIADOR_ENVIADO"), anyString());
    }

    /**
     * Avaliador sem e-mail cadastrado nao vira erro: os demais recebem, e o nome
     * dele volta como aviso para o operador resolver e reenviar no lembrete.
     */
    @Test
    void avaliadorSemEmailViraAvisoSemImpedirOsDemais() {
        Parecer semEmail = parecerPendente("Dr. Sem Email", "   ");
        Parecer comEmail = parecerPendente("Dr. B", "b@hcpa.br");
        when(processoService.pareceresPendentesComEmail(1L))
            .thenReturn(java.util.List.of(semEmail, comEmail));
        when(processoService.reivindicarConviteAvaliador(any(), anyInt())).thenReturn(true);
        when(emailTemplateService.emailConviteAvaliador(eq(processo), any())).thenReturn(templateConvite());
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        RegistroEnvioService.ConvitesResultado r = service.enviarConvitesAvaliadores(1L);

        assertThat(r.enviados()).isEqualTo(1);
        assertThat(r.avisos()).containsExactly("Dr. Sem Email (sem e-mail cadastrado)");
        verify(emailSenderService).enviar("b@hcpa.br", "Assunto do convite", "Corpo do convite");
        verify(auditoria).registrar(eq("CONVITE_AVALIADOR_NAO_ENVIADO"), anyString());
    }

    /** Falha de SMTP vira aviso + auditoria de falha, nunca excecao. */
    @Test
    void falhaDeSmtpViraAvisoEAuditoriaSemLancarExcecao() {
        when(processoService.pareceresPendentesComEmail(1L))
            .thenReturn(java.util.List.of(parecerPendente("Dr. A", "a@hcpa.br")));
        when(processoService.reivindicarConviteAvaliador(any(), anyInt())).thenReturn(true);
        when(emailTemplateService.emailConviteAvaliador(eq(processo), any())).thenReturn(templateConvite());
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(false);

        RegistroEnvioService.ConvitesResultado r = service.enviarConvitesAvaliadores(1L);

        assertThat(r.enviados()).isZero();
        assertThat(r.avisos()).containsExactly("Dr. A (falha no envio do e-mail)");
        verify(auditoria).registrar(eq("CONVITE_AVALIADOR_FALHA"), anyString());
    }

    /**
     * TRAVA DE DUPLICIDADE (bug real de producao, 2026-08-03): quando
     * {@code ProcessoService.reivindicarConviteAvaliador} devolve
     * {@code false} - simulando outra execucao concorrente/duplo-clique que ja
     * reivindicou o mesmo parecer dentro da janela - nenhum e-mail e enviado
     * para esse avaliador, o resultado NAO conta como "enviado" nem como
     * "aviso" (nao e erro para o operador), e a auditoria registra o motivo.
     */
    @Test
    void parecerJaReivindicadoDentroDaJanelaNaoEnviaEmailDeNovo() {
        Parecer a = parecerPendente("Dr. A", "a@hcpa.br");
        when(processoService.pareceresPendentesComEmail(1L)).thenReturn(java.util.List.of(a));
        when(processoService.reivindicarConviteAvaliador(any(), anyInt())).thenReturn(false);

        RegistroEnvioService.ConvitesResultado r = service.enviarConvitesAvaliadores(1L);

        assertThat(r.enviados()).isZero();
        assertThat(r.avisos()).isEmpty();
        verifyNoInteractions(emailSenderService, emailTemplateService);
        verify(auditoria).registrar(eq("CONVITE_AVALIADOR_IGNORADO_DUPLICADO"), anyString());
        verify(auditoria, never()).registrar(eq("CONVITE_AVALIADOR_ENVIADO"), anyString());
    }

    /**
     * Espelho do teste acima: se a reivindicacao for concedida (janela ja
     * passou / primeiro envio), o fluxo normal de envio continua intacto -
     * garante que o teste anterior falha pelo motivo certo (o guard em si),
     * nao por um mock mal configurado.
     */
    @Test
    void parecerComReivindicacaoConcedidaEnviaEmailNormalmente() {
        Parecer a = parecerPendente("Dr. A", "a@hcpa.br");
        when(processoService.pareceresPendentesComEmail(1L)).thenReturn(java.util.List.of(a));
        when(processoService.reivindicarConviteAvaliador(any(), anyInt())).thenReturn(true);
        when(emailTemplateService.emailConviteAvaliador(eq(processo), any())).thenReturn(templateConvite());
        when(emailSenderService.enviar(anyString(), anyString(), anyString())).thenReturn(true);

        RegistroEnvioService.ConvitesResultado r = service.enviarConvitesAvaliadores(1L);

        assertThat(r.enviados()).isEqualTo(1);
        verify(emailSenderService).enviar("a@hcpa.br", "Assunto do convite", "Corpo do convite");
        verify(auditoria, never()).registrar(eq("CONVITE_AVALIADOR_IGNORADO_DUPLICADO"), anyString());
    }

    /**
     * Num reenvio, quem ja votou nao esta em pareceresPendentesComEmail - logo
     * nao recebe convite de novo. Sem pendentes, nenhum e-mail sai.
     */
    @Test
    void semAvaliadorPendenteNaoEnviaNenhumEmail() {
        when(processoService.pareceresPendentesComEmail(1L)).thenReturn(java.util.List.of());

        RegistroEnvioService.ConvitesResultado r = service.enviarConvitesAvaliadores(1L);

        assertThat(r.enviados()).isZero();
        assertThat(r.avisos()).isEmpty();
        verifyNoInteractions(emailSenderService, emailTemplateService);
    }
}

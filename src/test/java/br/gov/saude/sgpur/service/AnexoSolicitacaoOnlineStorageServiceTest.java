package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.AnexoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.repository.AnexoSolicitacaoOnlineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Testes de {@link AnexoSolicitacaoOnlineStorageService}. Como
 * {@code validarTipoPermitido}/{@code nomeArquivoUnico} sao privados, sao
 * cobertos indiretamente atraves de {@code salvar()}.
 */
@ExtendWith(MockitoExtension.class)
class AnexoSolicitacaoOnlineStorageServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private AnexoSolicitacaoOnlineRepository repository;

    private AnexoSolicitacaoOnlineStorageService service;
    private SolicitacaoOnline solicitacao;

    @BeforeEach
    void setUp() {
        service = new AnexoSolicitacaoOnlineStorageService(repository, tempDir.toString());
        solicitacao = new SolicitacaoOnline();
        solicitacao.setId(1L);
    }

    private void stubSaveRetornandoOArgumento() {
        when(repository.save(org.mockito.ArgumentMatchers.any(AnexoSolicitacaoOnline.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    /** Bytes minimos de um PDF de verdade ("%PDF-1.4" + resto qualquer) - passa na checagem de assinatura. */
    private static final byte[] CONTEUDO_PDF_VALIDO = "%PDF-1.4\nconteudo".getBytes();
    private static final byte[] CONTEUDO_PNG_VALIDO =
        {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01};
    private static final byte[] CONTEUDO_JPEG_VALIDO = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] CONTEUDO_MSG_VALIDO =
        {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    @Test
    void aceitaExtensaoPdf() throws IOException {
        stubSaveRetornandoOArgumento();
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "laudo.pdf",
            "application/pdf", CONTEUDO_PDF_VALIDO);

        AnexoSolicitacaoOnline salvo = service.salvar(solicitacao, arquivo);

        assertThat(salvo.getNomeArquivo()).isEqualTo("laudo.pdf");
    }

    @Test
    void aceitaExtensaoEml() throws IOException {
        stubSaveRetornandoOArgumento();
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "copia.eml",
            "message/rfc822", "From: a@b.com\r\nSubject: teste\r\n\r\nCorpo.".getBytes());

        AnexoSolicitacaoOnline salvo = service.salvar(solicitacao, arquivo);

        assertThat(salvo.getNomeArquivo()).isEqualTo("copia.eml");
    }

    @Test
    void aceitaExtensaoMsg() throws IOException {
        stubSaveRetornandoOArgumento();
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "copia.msg",
            "application/octet-stream", CONTEUDO_MSG_VALIDO);

        AnexoSolicitacaoOnline salvo = service.salvar(solicitacao, arquivo);

        assertThat(salvo.getNomeArquivo()).isEqualTo("copia.msg");
    }

    @Test
    void aceitaExtensaoPngJpgJpeg() throws IOException {
        stubSaveRetornandoOArgumento();
        assertThat(service.salvar(solicitacao, new MockMultipartFile("documentos", "a.png",
            "image/png", CONTEUDO_PNG_VALIDO)).getNomeArquivo()).isEqualTo("a.png");
        assertThat(service.salvar(solicitacao, new MockMultipartFile("documentos", "b.jpg",
            "image/jpeg", CONTEUDO_JPEG_VALIDO)).getNomeArquivo()).isEqualTo("b.jpg");
        assertThat(service.salvar(solicitacao, new MockMultipartFile("documentos", "c.jpeg",
            "image/jpeg", CONTEUDO_JPEG_VALIDO)).getNomeArquivo()).isEqualTo("c.jpeg");
    }

    @Test
    void aceitaExtensaoEmMaiusculaPorSerCaseInsensitive() throws IOException {
        stubSaveRetornandoOArgumento();
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "LAUDO.PDF",
            "application/pdf", CONTEUDO_PDF_VALIDO);

        AnexoSolicitacaoOnline salvo = service.salvar(solicitacao, arquivo);

        assertThat(salvo.getNomeArquivo()).isEqualTo("LAUDO.PDF");
    }

    @Test
    void rejeitaExtensaoNaoPermitida() {
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "malware.exe",
            "application/octet-stream", "conteudo".getBytes());

        assertThatThrownBy(() -> service.salvar(solicitacao, arquivo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nao permitido");
    }

    @Test
    void rejeitaArquivoSemExtensao() {
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "semextensao",
            "application/octet-stream", "conteudo".getBytes());

        assertThatThrownBy(() -> service.salvar(solicitacao, arquivo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nao permitido");
    }

    @Test
    void rejeitaArquivoVazio() {
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "vazio.pdf",
            "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service.salvar(solicitacao, arquivo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vazio");
    }

    @Test
    void rejeitaExecutavelDisfarcadoDeExtensaoPermitida() {
        // Extensao ".pdf" (passa na allowlist), mas conteudo real e um
        // executavel Windows ("MZ...") - deve ser rejeitado pela checagem de
        // assinatura (magic number), mesmo com a extensao correta.
        byte[] bytesExecutavel = {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03};
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "laudo.pdf",
            "application/pdf", bytesExecutavel);

        assertThatThrownBy(() -> service.salvar(solicitacao, arquivo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nao permitido");
    }

    @Test
    void dedupeDeNomeQuandoJaExisteArquivoComMesmoNome() throws IOException {
        stubSaveRetornandoOArgumento();
        MockMultipartFile arquivo1 = new MockMultipartFile("documentos", "laudo.pdf",
            "application/pdf", "%PDF-1.4\nprimeiro".getBytes());
        MockMultipartFile arquivo2 = new MockMultipartFile("documentos", "laudo.pdf",
            "application/pdf", "%PDF-1.4\nsegundo".getBytes());

        AnexoSolicitacaoOnline salvo1 = service.salvar(solicitacao, arquivo1);
        AnexoSolicitacaoOnline salvo2 = service.salvar(solicitacao, arquivo2);

        assertThat(salvo1.getNomeArquivo()).isEqualTo("laudo.pdf");
        assertThat(salvo2.getNomeArquivo()).isEqualTo("laudo (2).pdf");
    }

    @Test
    void resolverArquivoRejeitaCaminhoForaDaAreaDeArmazenamentoEstiloUnix() {
        AnexoSolicitacaoOnline anexo = new AnexoSolicitacaoOnline();
        anexo.setCaminhoArmazenado("../../../etc/passwd");

        assertThatThrownBy(() -> service.resolverArquivo(anexo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalido");
    }

    @Test
    void resolverArquivoRejeitaCaminhoForaDaAreaDeArmazenamentoEstiloWindows() {
        AnexoSolicitacaoOnline anexo = new AnexoSolicitacaoOnline();
        anexo.setCaminhoArmazenado("..\\..\\..\\Windows\\win.ini");

        assertThatThrownBy(() -> service.resolverArquivo(anexo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalido");
    }

    @Test
    void resolverArquivoAceitaCaminhoValidoDentroDaArea() throws IOException {
        stubSaveRetornandoOArgumento();
        MockMultipartFile arquivo = new MockMultipartFile("documentos", "laudo.pdf",
            "application/pdf", CONTEUDO_PDF_VALIDO);
        AnexoSolicitacaoOnline salvo = service.salvar(solicitacao, arquivo);

        Path resolvido = service.resolverArquivo(salvo);

        assertThat(resolvido).exists();
    }
}

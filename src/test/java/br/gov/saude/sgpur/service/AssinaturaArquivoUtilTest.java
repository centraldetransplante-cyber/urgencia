package br.gov.saude.sgpur.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cobre a checagem de assinatura (magic number) usada por
 * {@code AnexoStorageService}/{@code AnexoSolicitacaoOnlineStorageService}
 * para alem da extensao declarada pelo cliente (vistoria de seguranca
 * 2026-08-22, achado P2 "Upload valida extensao, mas nao o conteudo real").
 */
class AssinaturaArquivoUtilTest {

    private static final byte[] PDF_DE_VERDADE = {
        0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34}; // "%PDF-1.4"

    private static final byte[] PNG_DE_VERDADE = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private static final byte[] JPEG_DE_VERDADE = {
        (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    private static final byte[] MSG_DE_VERDADE = {
        (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private static final byte[] TEXTO_PURO =
        "Isto e apenas um arquivo de texto qualquer, nao e um PDF de verdade."
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** Assinatura "MZ" de executaveis/DLLs do Windows (PE). */
    private static final byte[] EXECUTAVEL_WINDOWS = {0x4D, 0x5A, (byte) 0x90, 0x00};

    @Test
    void pdfDeVerdadeEAceito() {
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("pdf", PDF_DE_VERDADE)).isTrue();
    }

    @Test
    void textoPuroRenomeadoParaPdfERejeitado() {
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("pdf", TEXTO_PURO)).isFalse();
    }

    @Test
    void pngDeVerdadeEAceito() {
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("png", PNG_DE_VERDADE)).isTrue();
    }

    @Test
    void executavelWindowsRenomeadoParaPngERejeitado() {
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("png", EXECUTAVEL_WINDOWS)).isFalse();
    }

    @Test
    void jpegDeVerdadeEAceito() {
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("jpg", JPEG_DE_VERDADE)).isTrue();
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("jpeg", JPEG_DE_VERDADE)).isTrue();
    }

    @Test
    void executavelWindowsRenomeadoParaJpgERejeitado() {
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("jpg", EXECUTAVEL_WINDOWS)).isFalse();
    }

    @Test
    void msgDeVerdadeEAceito() {
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("msg", MSG_DE_VERDADE)).isTrue();
    }

    @Test
    void executavelWindowsRenomeadoParaMsgERejeitado() {
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("msg", EXECUTAVEL_WINDOWS)).isFalse();
    }

    @Test
    void emailTextoPuroEAceito() {
        byte[] emlDeVerdade = ("From: solicitante@example.com\r\n"
            + "To: urgencia-renal@example.com\r\n"
            + "Subject: Encaminhamento\r\n\r\nCorpo do e-mail.")
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("eml", emlDeVerdade)).isTrue();
    }

    @Test
    void executavelWindowsRenomeadoParaEmlERejeitado() {
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("eml", EXECUTAVEL_WINDOWS)).isFalse();
    }

    @Test
    void pdfDisfarcadoDeEmlERejeitado() {
        // Um PDF de verdade renomeado para .eml tambem deve ser rejeitado -
        // .eml so aceita texto, nunca uma assinatura binaria conhecida.
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("eml", PDF_DE_VERDADE)).isFalse();
    }

    @Test
    void bytesNulosSaoTratadosComoVazios() {
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("pdf", null)).isFalse();
    }

    @Test
    void extensaoDesconhecidaEInvalida() {
        assertThat(AssinaturaArquivoUtil.validoParaExtensao("exe", PDF_DE_VERDADE)).isFalse();
    }
}

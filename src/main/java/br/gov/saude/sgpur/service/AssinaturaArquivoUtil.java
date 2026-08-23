package br.gov.saude.sgpur.service;

import java.util.Locale;

/**
 * Verifica a ASSINATURA (magic number) dos primeiros bytes de um arquivo
 * contra a extensao declarada, para alem da checagem de nome de arquivo ja
 * feita em {@code AnexoStorageService}/{@code AnexoSolicitacaoOnlineStorageService}.
 *
 * <p><b>Por que existe.</b> A allowlist de extensao (PDF/e-mail/imagem) so
 * olha o nome informado pelo CLIENTE no upload - nunca o conteudo real do
 * arquivo. Um atacante pode renomear qualquer binario (ex.: um executavel)
 * para {@code documento.pdf} e a checagem de extensao aceita, gravando o
 * arquivo em disco do jeito que veio. Esta classe fecha essa lacuna
 * verificando os primeiros bytes contra a assinatura conhecida de cada
 * formato aceito, SEM depender de nenhuma biblioteca de deteccao de MIME
 * (o projeto nao usa Apache Tika nem equivalente - ver {@code pom.xml}).</p>
 *
 * <p>Classe utilitaria pura, sem estado (mesma familia de {@link CpfUtil} e
 * {@link Iniciais}).</p>
 */
public final class AssinaturaArquivoUtil {

    private AssinaturaArquivoUtil() {
    }

    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46, 0x2D}; // "%PDF-"
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    /** MSG (Outlook) e um arquivo OLE2/Compound File - mesma assinatura de .doc/.xls antigos. */
    private static final byte[] OLE2_MSG = {
        (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    /**
     * Confere se {@code primeirosBytes} tem a assinatura esperada para a
     * {@code extensao} declarada (minusculas, sem ponto - ex. {@code "pdf"}).
     *
     * <p>{@code eml} nao tem uma assinatura binaria propria (e texto RFC822
     * puro): aqui apenas se rejeita quando o conteudo COMECA com a
     * assinatura binaria de outro formato conhecido (ex. um executavel
     * disfarcado de {@code .eml}) - nao se exige nenhuma estrutura de texto
     * especifica.</p>
     *
     * <p>Extensao desconhecida (fora das 5 aceitas pela allowlist do
     * chamador) e tratada como INVALIDA aqui tambem, por seguranca -
     * embora na pratica o chamador ja tenha barrado antes de chegar a este
     * metodo.</p>
     */
    public static boolean validoParaExtensao(String extensao, byte[] primeirosBytes) {
        if (primeirosBytes == null) {
            primeirosBytes = new byte[0];
        }
        String ext = extensao == null ? "" : extensao.toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "pdf" -> comecaCom(primeirosBytes, PDF);
            case "png" -> comecaCom(primeirosBytes, PNG);
            case "jpg", "jpeg" -> comecaCom(primeirosBytes, JPEG);
            case "msg" -> comecaCom(primeirosBytes, OLE2_MSG);
            case "eml" -> !comecaCom(primeirosBytes, PDF)
                && !comecaCom(primeirosBytes, PNG)
                && !comecaCom(primeirosBytes, JPEG)
                && !comecaCom(primeirosBytes, OLE2_MSG)
                && !ehExecutavelWindows(primeirosBytes);
            default -> false;
        };
    }

    /** Assinatura "MZ" de executaveis/DLLs do Windows (PE) - checada a parte do .eml. */
    private static boolean ehExecutavelWindows(byte[] bytes) {
        return bytes.length >= 2 && bytes[0] == 0x4D && bytes[1] == 0x5A;
    }

    private static boolean comecaCom(byte[] bytes, byte[] assinatura) {
        if (bytes.length < assinatura.length) {
            return false;
        }
        for (int i = 0; i < assinatura.length; i++) {
            if (bytes[i] != assinatura[i]) {
                return false;
            }
        }
        return true;
    }
}

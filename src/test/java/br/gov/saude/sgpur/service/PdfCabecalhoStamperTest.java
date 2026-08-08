package br.gov.saude.sgpur.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cobre {@link PdfCabecalhoStamper#anonimizarMetadados} (pacote-privado, so
 * testavel a partir daqui): confirma que o carimbo de cabecalho realmente
 * remove qualquer nome de paciente colado nos metadados do PDF de origem.
 *
 * <p>Existe porque a limpeza de metadados protege a mesma regra de
 * imparcialidade que o texto visivel do documento (so iniciais aos
 * avaliadores) - um PDF "anonimizado" no corpo mas com o nome completo ainda
 * no {@code /Info} ou no XMP entrega a informacao mesmo assim (o navegador
 * mostra o {@code Title} na aba ao abrir o PDF inline). Ver o javadoc de
 * {@code anonimizarMetadados} para o incidente que motivou a limpeza.
 *
 * <p>Tambem serve de teste de regressao para a troca do
 * {@code PdfStamper.setMoreInfo} (deprecado no OpenPDF 1.3.34) por
 * {@code setInfoDictionary}: qualquer diferenca de comportamento entre os
 * dois apareceria aqui como um metadado vazando.
 */
class PdfCabecalhoStamperTest {

    private static final String NOME_PACIENTE = "Joao da Silva Pereira";

    /**
     * PDF "envenenado": simula um documento vindo de um sistema hospitalar de
     * verdade, com o nome do paciente espalhado por varias chaves do
     * {@code /Info} - incluindo uma chave CUSTOM ({@code PatientName}), que e
     * exatamente o caso que a limpeza por "zerar so as chaves conhecidas"
     * (CHAVES_INFO_CONHECIDAS) deixaria passar.
     */
    private byte[] pdfComNomeDoPacienteNosMetadados() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document();
        PdfWriter.getInstance(doc, baos);
        doc.addTitle("LAUDO CLINICO - " + NOME_PACIENTE.toUpperCase());
        doc.addAuthor("Dr. Fulano de Tal - HCPA");
        doc.addSubject("Paciente: " + NOME_PACIENTE + ", quadro grave");
        doc.addCreator("Sistema Hospitalar XYZ");
        doc.addKeywords(NOME_PACIENTE);
        doc.addHeader("PatientName", NOME_PACIENTE); // chave customizada, fora do padrao
        doc.open();
        doc.add(new Paragraph("Documento clinico de teste."));
        doc.close();
        return baos.toByteArray();
    }

    @Test
    void estamparRemoveNomeDoPacienteDeTodasAsChavesDoInfo() throws Exception {
        byte[] original = pdfComNomeDoPacienteNosMetadados();
        String tituloSeguro = "Processo CET-RS 01/2026 - Paciente J.S.P.";

        byte[] resultado = PdfCabecalhoStamper.estampar(original,
            PdfCabecalhoStamper.NOME_INSTITUICAO, tituloSeguro);

        PdfReader reader = new PdfReader(resultado);
        try {
            var info = reader.getInfo();

            // Nenhum valor remanescente do /Info cita o nome do paciente -
            // nem nas chaves conhecidas, nem em qualquer chave customizada
            // que o documento de origem tivesse.
            assertThat(info.values()).noneMatch(v -> v != null && v.contains(NOME_PACIENTE));

            // A chave customizada (fora de CHAVES_INFO_CONHECIDAS) some por
            // completo - nao fica com valor vazio, e removida do dicionario
            // (comportamento de valor null no moreInfo -> PdfDictionary.remove).
            assertThat(info).doesNotContainKey("PatientName");

            // O titulo seguro e o unico dado de identificacao que sobrevive.
            assertThat(info.get("Title")).isEqualTo(tituloSeguro);
            assertThat(info.get("Producer")).isEqualTo(PdfCabecalhoStamper.NOME_INSTITUICAO);

            // Author/Subject/Keywords do documento original foram zerados.
            assertThat(info.get("Author")).isNullOrEmpty();
            assertThat(info.get("Subject")).isNullOrEmpty();
            assertThat(info.get("Keywords")).isNullOrEmpty();
        } finally {
            reader.close();
        }
    }

    @Test
    void estamparSubstituiOXmpPorUmPacoteNeutroSemNomeDoPaciente() throws Exception {
        byte[] original = pdfComNomeDoPacienteNosMetadados();
        String tituloSeguro = "Processo CET-RS 01/2026 - Paciente J.S.P.";

        byte[] resultado = PdfCabecalhoStamper.estampar(original,
            PdfCabecalhoStamper.NOME_INSTITUICAO, tituloSeguro);

        PdfReader reader = new PdfReader(resultado);
        try {
            byte[] xmp = reader.getMetadata();
            assertThat(xmp).isNotNull();
            String xmpTexto = new String(xmp, StandardCharsets.UTF_8);

            assertThat(xmpTexto).doesNotContain(NOME_PACIENTE);
            assertThat(xmpTexto).contains(tituloSeguro);
        } finally {
            reader.close();
        }
    }

    @Test
    void estamparMantemProducerInstitucionalMesmoSemMetadadosDeOrigem() throws Exception {
        // PDF minimo, sem NENHUM /Info customizado - garante que a limpeza nao
        // depende de o documento de origem ja ter metadados para funcionar.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document();
        PdfWriter.getInstance(doc, baos);
        doc.open();
        doc.add(new Paragraph("Documento sem metadados."));
        doc.close();

        byte[] resultado = PdfCabecalhoStamper.estampar(baos.toByteArray(),
            PdfCabecalhoStamper.NOME_INSTITUICAO, "Titulo seguro");

        PdfReader reader = new PdfReader(resultado);
        try {
            assertThat(reader.getInfo().get("Producer")).isEqualTo(PdfCabecalhoStamper.NOME_INSTITUICAO);
            assertThat(reader.getInfo().get("Title")).isEqualTo("Titulo seguro");
        } finally {
            reader.close();
        }
    }

    // -------------------------------------------------------------------
    // ToUnicode CMap (2026-08-08) - ver CLAUDE.md "Extracao de texto em PDF"
    // -------------------------------------------------------------------

    /** PDF minimo com um paragrafo Helvetica (FontFactory, WinAnsiEncoding) com acentuacao. */
    private byte[] pdfComTextoAcentuado(String texto) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document();
        PdfWriter.getInstance(doc, baos);
        doc.open();
        doc.add(new Paragraph(texto, FontFactory.getFont(FontFactory.HELVETICA, 12)));
        doc.close();
        return baos.toByteArray();
    }

    /**
     * Toda fonte {@code /Type1}/{@code WinAnsiEncoding} do documento
     * estampado ganha um {@code /ToUnicode} - tanto a fonte do corpo original
     * quanto a fonte nova que o proprio carimbo (cabecalho/numeracao de
     * pagina) cria. Sem isso, ferramentas que dependem do ToUnicode em vez de
     * reconstruir o mapeamento a partir do {@code /Encoding} (nem toda
     * biblioteca de extracao faz esse fallback) devolveriam caracteres
     * substitutos para toda letra acentuada.
     */
    @Test
    void estamparInjetaToUnicodeEmTodasAsFontesType1WinAnsiDoDocumento() throws Exception {
        byte[] original = pdfComTextoAcentuado("Situação de urgência confirmada, indicação inequívoca.");

        byte[] resultado = PdfCabecalhoStamper.estampar(original,
            "Central de Transplantes do Estado do Rio Grande do Sul - URGÊNCIA RENAL",
            "Processo CET-RS 01/2026 - Paciente J.S.P.");

        try (PdfReader reader = new PdfReader(resultado)) {
            int totalObjetos = reader.getXrefSize();
            int fontesType1WinAnsi = 0;
            for (int i = 1; i < totalObjetos; i++) {
                var obj = reader.getPdfObject(i);
                if (!(obj instanceof PdfDictionary dict)) {
                    continue;
                }
                if (!PdfName.FONT.equals(dict.get(PdfName.TYPE))
                        || !PdfName.TYPE1.equals(dict.get(PdfName.SUBTYPE))) {
                    continue;
                }
                var encoding = dict.get(PdfName.ENCODING);
                if (!(encoding instanceof PdfName) || !PdfName.WIN_ANSI_ENCODING.equals(encoding)) {
                    continue;
                }
                fontesType1WinAnsi++;
                assertThat(dict.get(PdfName.TOUNICODE))
                    .as("fonte %s deveria ter ganho um /ToUnicode", dict)
                    .isNotNull();
            }
            // Confirma que o teste realmente encontrou fontes para checar (o
            // corpo do documento + o carimbo do cabecalho/numeracao) - senao
            // as asserções acima passariam vazias sem provar nada.
            assertThat(fontesType1WinAnsi).isGreaterThanOrEqualTo(2);
        }
    }

    /**
     * Prova de ponta a ponta com o PROPRIO extrator do OpenPDF
     * ({@code PdfTextExtractor}): o texto extraido do documento estampado
     * bate exatamente com o texto acentuado original, incluindo o cabecalho
     * carimbado (que tambem tem acento - "URGÊNCIA").
     */
    @Test
    void textoExtraidoDoDocumentoEstampadoMantemAAcentuacaoOriginal() throws Exception {
        String corpo = "Situação de urgência confirmada, indicação inequívoca.";
        byte[] original = pdfComTextoAcentuado(corpo);

        byte[] resultado = PdfCabecalhoStamper.estampar(original,
            "Central de Transplantes do Estado do Rio Grande do Sul - URGÊNCIA RENAL",
            "Processo CET-RS 01/2026 - Paciente J.S.P.");

        try (PdfReader reader = new PdfReader(resultado)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            String texto = extractor.getTextFromPage(1);

            assertThat(texto).contains(corpo);
            assertThat(texto).contains("URGÊNCIA RENAL");
        }
    }
}

package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfCopy;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Gera o PDF "Solicitacao de Avaliacao — Urgencia Renal" destinado aos
 * medicos avaliadores. O documento NAO contem o nome completo do paciente
 * (apenas as iniciais), para preservar a IMPARCIALIDADE do julgamento: os
 * avaliadores decidem sem saber quem e o paciente, evitando vies (convencao
 * da equipe de Urgencia Renal). Aos documentos dirigidos a equipe SOLICITANTE
 * vai o nome completo do paciente.
 *
 * <p>
 * {@code gerar} (folha-rosto legada, substituida pelos documentos
 * clinicos consolidados desde a mudanca de fluxo de Envio) foi removido em
 * 2026-07-27 por falta de qualquer chamador - ver {@code consolidar}/
 * {@code carimbarCabecalho}, que continuam ativos.
 */
@Service
public class SolicitacaoAvaliadorService {

    private static final Color CINZA = new Color(108, 117, 125);

    /**
     * Nome de arquivo oficial da copia da solicitacao para envio das equipes,
     * no padrao: "Processo CET-RS NN-AAAA - Paciente X.X.X.pdf" (numero com a
     * barra trocada por traco e iniciais do paciente — sem expor o nome).
     */
    public static String nomeArquivoOficial(Processo p) {
        String numero = p.getNumero() == null ? "" : p.getNumero().replace("/", "-");
        String iniciais = Iniciais.de(p.getPacienteNome());
        if (iniciais.endsWith(".")) {
            iniciais = iniciais.substring(0, iniciais.length() - 1);
        }
        return "Processo CET-RS " + numero + " - Paciente " + iniciais + ".pdf";
    }

    /**
     * Consolida varios PDFs em um unico documento (folha-rosto + documentos
     * clinicos anonimizados), preservando a ordem da lista. Usado para montar o
     * arquivo oficial unico enviado aos avaliadores. Ignora entradas nulas ou
     * vazias.
     *
     * <p>Mesmo com UM unico PDF valido a lista passa pelo {@link PdfCopy}, em
     * vez de devolver o arquivo original intacto: o {@code PdfCopy} reescreve
     * o documento e descarta os metadados de origem ({@code /Info} e XMP, que
     * costumam trazer o nome completo do paciente gravado pelo sistema do
     * hospital). Antes, o caminho de um unico documento - justamente o mais
     * comum - era o unico que preservava esses metadados. A limpeza definitiva
     * acontece em {@link #carimbarCabecalho} (ponto final do pipeline), mas
     * normalizar os dois caminhos aqui evita que uma variacao futura de fluxo
     * volte a diferir.
     */
    public byte[] consolidar(List<byte[]> pdfs) {
        List<byte[]> validos = pdfs.stream()
                .filter(b -> b != null && b.length > 0)
                .toList();
        if (validos.isEmpty()) {
            throw new IllegalArgumentException("Nenhum PDF para consolidar.");
        }
        if (validos.size() == 1) {
            // Valida se o PDF tem ao menos uma pagina (evita "The document has no pages" no
            // carimbo) - com mensagem especifica de documento unico.
            try (PdfReader reader = new PdfReader(validos.get(0))) {
                int paginas = reader.getNumberOfPages();
                if (paginas == 0) {
                    throw new IllegalStateException(
                            "O documento clinico anexado esta vazio (0 paginas). "
                                    + "Remova-o e anexe novamente o arquivo original.");
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Falha ao ler o documento clinico PDF: " + e.getMessage());
            }
        }
        Document doc = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfCopy copy = new PdfCopy(doc, out);
            doc.open();
            boolean algumaPaginaAdicionada = false;
            for (byte[] pdf : validos) {
                try (PdfReader reader = new PdfReader(pdf)) {
                    int paginas = reader.getNumberOfPages();
                    for (int i = 1; i <= paginas; i++) {
                        copy.addPage(copy.getImportedPage(reader, i));
                        algumaPaginaAdicionada = true;
                    }
                    copy.freeReader(reader);
                }
            }
            if (!algumaPaginaAdicionada) {
                doc.close();
                throw new IllegalStateException(
                        "Os PDFs anexados estao vazios (nenhuma pagina encontrada). "
                                + "Remova-os e anexe novamente os documentos clinicos originais.");
            }
            doc.close();
            return out.toByteArray();
        } catch (DocumentException | java.io.IOException e) {
            throw new IllegalStateException("Falha ao consolidar os PDFs da solicitacao", e);
        }
    }

    /**
     * Altura (pt) reservada no topo de cada pagina para o carimbo de 2 linhas.
     * Menor que {@link PdfCabecalhoStamper#ALTURA_CABECALHO} (que tambem reserva
     * espaco para logo e numeracao de pagina) - aqui e so texto pequeno (8pt).
     */
    private static final float ALTURA_CARIMBO = 30f;
    /**
     * Margem lateral usada so para calcular a largura util do texto truncado do
     * carimbo.
     */
    private static final float MARGEM_CARIMBO = 40f;

    /**
     * Carimba um cabecalho de duas linhas no TOPO de CADA pagina de um PDF ja
     * existente. Linha 1: identificacao institucional. Linha 2: numero do
     * processo + INICIAIS do paciente (NUNCA o nome completo, para preservar a
     * imparcialidade do julgamento dos avaliadores).
     *
     * <p>
     * Em vez de desenhar por cima do conteudo original (o que podia deixar
     * o carimbo sobreposto/ilegivel em documentos clinicos escaneados sem
     * margem superior), EXPANDE a pagina no topo - mesma tecnica ja usada por
     * {@link PdfCabecalhoStamper#estampar} via
     * {@link PdfCabecalhoStamper#expandirTopo} - deslocando o conteudo
     * original para baixo antes de escrever o carimbo no over-content.
     *
     * <p>Este e o PONTO FINAL do material que vai aos avaliadores, entao e
     * aqui que os metadados do PDF de origem sao apagados
     * ({@link PdfCabecalhoStamper#anonimizarMetadados}): o {@code /Info} e o
     * XMP herdados do sistema do hospital costumam conter o NOME COMPLETO do
     * paciente (o navegador chega a exibi-lo no rotulo da aba, via
     * {@code Title}), o que quebraria a imparcialidade mesmo com o corpo do
     * documento anonimizado.
     */
    public byte[] carimbarCabecalho(byte[] pdf, Processo p) {
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("PDF vazio para carimbar.");
        }
        String linha1 = RotuloProcesso.carimboLinha1(p);
        String iniciais = Iniciais.de(p.getPacienteNome());
        if (iniciais.endsWith(".")) {
            iniciais = iniciais.substring(0, iniciais.length() - 1);
        }
        String linha2 = "Processo CET-RS " + nvl(p.getNumero()) + " - Paciente " + iniciais;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfReader reader = new PdfReader(pdf)) {
            int paginas = reader.getNumberOfPages();
            if (paginas == 0) {
                throw new IllegalStateException(
                        "O PDF consolidado esta vazio (0 paginas). "
                                + "Verifique os documentos clinicos anexados e tente novamente.");
            }
            PdfStamper stamper = PdfCabecalhoStamper.novoStamper(reader, out);
            // Apaga /Info e XMP herdados do documento clinico original (podem
            // trazer o nome completo do paciente); deixa so o titulo seguro.
            PdfCabecalhoStamper.anonimizarMetadados(reader, stamper, linha2);
            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            for (int i = 1; i <= paginas; i++) {
                // Expande o MediaBox/CropBox no topo VISUAL da pagina (conteudo
                // original desce junto) em vez de escrever por cima dele; a area
                // devolvida ja considera o /Rotate da pagina.
                PdfCabecalhoStamper.AreaCarimbo area =
                        PdfCabecalhoStamper.expandirTopo(reader, i, ALTURA_CARIMBO);
                float topo = area.altura();
                float xCentro = area.largura() / 2f;
                // Truncamento defensivo: showTextAligned nao faz wrap/clipping - um
                // numero de processo ou identificacao mais longa que o normal (dado
                // legado, por exemplo) desenharia para fora dos limites da pagina.
                float larguraMax = area.largura() - 2 * MARGEM_CARIMBO;
                String linha1T = PdfCabecalhoStamper.truncarParaLargura(bf, 8, linha1, larguraMax);
                String linha2T = PdfCabecalhoStamper.truncarParaLargura(bf, 8, linha2, larguraMax);
                PdfContentByte over = stamper.getOverContent(i);
                over.saveState();
                area.aplicarEm(over);
                over.setColorFill(CINZA);
                ColumnText.showTextAligned(over, Element.ALIGN_CENTER,
                        new Phrase(linha1T, new Font(bf, 8, Font.NORMAL, CINZA)),
                        xCentro, topo - 14, 0);
                ColumnText.showTextAligned(over, Element.ALIGN_CENTER,
                        new Phrase(linha2T, new Font(bf, 8, Font.NORMAL, CINZA)),
                        xCentro, topo - 24, 0);
                over.restoreState();
            }
            stamper.close();
            return out.toByteArray();
        } catch (DocumentException | java.io.IOException e) {
            throw new IllegalStateException("Falha ao carimbar o cabecalho do PDF dos avaliadores", e);
        }
    }

    private String nvl(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}

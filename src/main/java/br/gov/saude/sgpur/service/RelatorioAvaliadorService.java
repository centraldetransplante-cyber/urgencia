package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.service.TempoRespostaService.DetalheParecer;
import br.gov.saude.sgpur.service.TempoRespostaService.ResumoTempo;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gera o Relatorio Individual do Avaliador (por ano) em PDF: para um medico
 * especifico, lista os processos que ele avaliou no ano com o tempo de resposta
 * de CADA processo (dias corridos entre envio e resposta) e um resumo com a
 * media geral dele e a contagem fora do prazo.
 *
 * <p>Complementa o {@link RelatorioAnualService} (que mostra o agregado do ano
 * por avaliador). Mantem o mesmo padrao visual e o mesmo cabecalho estampado
 * pagina a pagina via {@link PdfCabecalhoStamper}. O calculo de tempo reusa o
 * {@link TempoRespostaService} para nao divergir dos indicadores de /membros.
 */
@Service
public class RelatorioAvaliadorService {

    private static final Logger log = LoggerFactory.getLogger(RelatorioAvaliadorService.class);

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // Decisao 6 do relatorio V2 (§7.6): paleta institucional compartilhada
    // com o Relatorio Final e o Relatorio Anual via PaletaPdf - ver o mesmo
    // comentario em RelatorioAnualService.
    private static final Color AZUL = PaletaPdf.AZUL;
    private static final Color CINZA = PaletaPdf.CINZA;
    private static final Color BORDA = PaletaPdf.CINZA_BORDA;
    private static final Color VERMELHO = PaletaPdf.VERMELHO;

    private final TempoRespostaService tempoRespostaService;

    public RelatorioAvaliadorService(TempoRespostaService tempoRespostaService) {
        this.tempoRespostaService = tempoRespostaService;
    }

    /**
     * Gera o PDF do relatorio individual do avaliador.
     *
     * @param ano       ano de referencia
     * @param membro    avaliador
     * @param processos processos do ano (ja com pareceres e membros carregados)
     */
    public byte[] gerar(int ano, MembroUrgenciaRenal membro, List<Processo> processos) {
        byte[] semCabecalho = gerarSemCabecalho(ano, membro, processos);
        return PdfCabecalhoStamper.estampar(semCabecalho,
            PdfCabecalhoStamper.NOME_INSTITUICAO + " - URGÊNCIA RENAL",
            "Relatório do Avaliador - " + membro.getRotulo() + " - Ano " + ano);
    }

    private byte[] gerarSemCabecalho(int ano, MembroUrgenciaRenal membro, List<Processo> processos) {
        // Pareceres respondidos DESTE membro no ano + o processo de cada um
        // (para exibir numero/paciente na tabela detalhada).
        List<Parecer> pareceresDoMembro = new ArrayList<>();
        Map<Long, Processo> processoPorParecer = new LinkedHashMap<>();
        for (Processo p : processos) {
            for (Parecer par : p.getPareceres()) {
                if (par.getMembro() != null && membro.getId().equals(par.getMembro().getId())
                    && par.getResultado() != null && par.getDataEnvio() != null
                    && par.getDataResposta() != null) {
                    pareceresDoMembro.add(par);
                    processoPorParecer.put(par.getId(), p);
                }
            }
        }
        ResumoTempo resumo = tempoRespostaService.calcularDe(pareceresDoMembro);
        List<DetalheParecer> detalhes = tempoRespostaService.detalharDe(pareceresDoMembro);

        Document doc = new Document(PageSize.A4, 36, 36, 46, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            adicionarCapa(doc, ano, membro, detalhes.size());
            doc.newPage();

            Font fSecao = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.WHITE);

            // 1. Resumo do avaliador no ano
            secao(doc, fSecao, "1. Resumo do avaliador no ano " + ano);
            doc.add(tabelaResumo(resumo));

            // 2. Detalhe processo a processo (tempo de cada resposta)
            secao(doc, fSecao, "2. Tempo de resposta por processo");
            doc.add(tabelaDetalhe(detalhes, processoPorParecer));

            Paragraph rodape = new Paragraph(
                "Documento gerado automaticamente pelo " + PdfCabecalhoStamper.NOME_SISTEMA + " em "
                    + LocalDate.now().format(DATA) + ".",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, CINZA));
            rodape.setAlignment(Element.ALIGN_CENTER);
            rodape.setSpacingBefore(16);
            doc.add(rodape);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Falha ao gerar o relatorio do avaliador PDF", e);
        }
    }

    // -----------------------------------------------------------------------
    // Capa
    // -----------------------------------------------------------------------

    private void adicionarCapa(Document doc, int ano, MembroUrgenciaRenal membro, int totalAvaliados)
            throws DocumentException {
        Font fOrgao = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.BLACK);
        Font fSubOrgao = FontFactory.getFont(FontFactory.HELVETICA, 12, Color.BLACK);
        Font fUrgencia = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, AZUL);
        Font fTituloDoc = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.BLACK);
        Font fSub = FontFactory.getFont(FontFactory.HELVETICA, 11, CINZA);

        Paragraph espaco = new Paragraph(" ");
        espaco.setSpacingAfter(40);
        doc.add(espaco);

        try {
            byte[] logoBytes = getClass().getClassLoader()
                .getResourceAsStream("static/brasao.png").readAllBytes();
            Image brasao = Image.getInstance(logoBytes);
            brasao.scaleToFit(110, 110);
            brasao.setAlignment(Element.ALIGN_CENTER);
            brasao.setSpacingAfter(12);
            doc.add(brasao);
        } catch (Exception e) {
            log.warn("Logo nao encontrado em static/brasao.png, capa do relatorio do avaliador sem imagem");
        }

        Paragraph orgao = new Paragraph(PdfCabecalhoStamper.NOME_INSTITUICAO, fOrgao);
        orgao.setAlignment(Element.ALIGN_CENTER);
        doc.add(orgao);

        Paragraph secretaria = new Paragraph(PdfCabecalhoStamper.SECRETARIA, fSubOrgao);
        secretaria.setAlignment(Element.ALIGN_CENTER);
        doc.add(secretaria);

        Paragraph urgencia = new Paragraph("URGÊNCIA RENAL", fUrgencia);
        urgencia.setAlignment(Element.ALIGN_CENTER);
        urgencia.setSpacingAfter(36);
        doc.add(urgencia);

        PdfPTable separador = new PdfPTable(1);
        separador.setWidthPercentage(50);
        separador.setSpacingAfter(36);
        PdfPCell linhaSep = new PdfPCell(new Phrase(" "));
        linhaSep.setBorderWidthBottom(1.5f);
        linhaSep.setBorderWidthTop(0);
        linhaSep.setBorderWidthLeft(0);
        linhaSep.setBorderWidthRight(0);
        linhaSep.setBorderColor(AZUL);
        linhaSep.setPadding(0);
        separador.addCell(linhaSep);
        doc.add(separador);

        Paragraph tituloDoc = new Paragraph("RELATÓRIO DO AVALIADOR - ANO " + ano, fTituloDoc);
        tituloDoc.setAlignment(Element.ALIGN_CENTER);
        tituloDoc.setSpacingAfter(8);
        doc.add(tituloDoc);

        Paragraph nomeMembro = new Paragraph(membro.getRotulo(),
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.BLACK));
        nomeMembro.setAlignment(Element.ALIGN_CENTER);
        nomeMembro.setSpacingAfter(16);
        doc.add(nomeMembro);

        Paragraph resumoCapa = new Paragraph(
            "Processos avaliados no ano: " + totalAvaliados
                + "  |  Emitido em " + java.time.LocalDateTime.now().format(DATA_HORA), fSub);
        resumoCapa.setAlignment(Element.ALIGN_CENTER);
        doc.add(resumoCapa);
    }

    // -----------------------------------------------------------------------
    // Resumo do avaliador
    // -----------------------------------------------------------------------

    private PdfPTable tabelaResumo(ResumoTempo resumo) {
        PdfPTable t = new PdfPTable(new float[]{6, 2});
        t.setWidthPercentage(60);
        t.setSpacingBefore(6);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);

        linhaResumo(t, "Processos avaliados (respondidos)", String.valueOf(resumo.totalAvaliados()), true);
        linhaResumo(t, "Tempo médio de resposta",
            TempoRespostaService.formatarDias(resumo.mediaGeralDias()), true);
        linhaResumo(t, "Respostas fora do prazo (meta " + resumo.prazoDias() + " dias corridos)",
            resumo.foraDoPrazo() + " de " + resumo.totalAvaliados(), false);
        return t;
    }

    // -----------------------------------------------------------------------
    // Detalhe processo a processo
    // -----------------------------------------------------------------------

    private PdfPTable tabelaDetalhe(List<DetalheParecer> detalhes, Map<Long, Processo> processoPorParecer) {
        PdfPTable t = new PdfPTable(new float[]{1.6f, 3.4f, 2.2f, 1.8f, 1.8f, 1.4f, 1.8f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        t.setHeaderRows(1);
        cabecalho(t, "Nº/Ano", "Paciente", "Parecer", "Envio", "Resposta", "Dias", "Prazo");

        if (detalhes.isEmpty()) {
            PdfPCell vazio = new PdfPCell(new Phrase("Nenhum processo avaliado por este medico neste ano.",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, CINZA)));
            vazio.setColspan(7);
            vazio.setPadding(8);
            vazio.setHorizontalAlignment(Element.ALIGN_CENTER);
            vazio.setBorderColor(BORDA);
            t.addCell(vazio);
            return t;
        }

        for (DetalheParecer d : detalhes) {
            Parecer par = d.parecer();
            Processo p = processoPorParecer.get(par.getId());
            celula(t, p != null ? nvl(p.getNumero()) : "-");
            celula(t, p != null ? nvl(p.getPacienteNome()) : "-");
            celula(t, par.getResultado() != null ? PdfRelatorioBuilder.descricaoResultado(par.getResultado()) : "-");
            celula(t, par.getDataEnvio().format(DATA));
            celula(t, par.getDataResposta().format(DATA));
            celula(t, String.valueOf(d.dias()));
            celulaPrazo(t, d.foraDoPrazo());
        }
        return t;
    }

    // -----------------------------------------------------------------------
    // Helpers de estilo (espelham o RelatorioAnualService)
    // -----------------------------------------------------------------------

    private void linhaResumo(PdfPTable t, String rotulo, String valor, boolean destaque) {
        Font fr = FontFactory.getFont(destaque ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, 10,
            destaque ? Color.BLACK : CINZA);
        Font fv = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
        PdfPCell c1 = new PdfPCell(new Phrase(rotulo, fr));
        PdfPCell c2 = new PdfPCell(new Phrase(valor, fv));
        c2.setHorizontalAlignment(Element.ALIGN_RIGHT);
        for (PdfPCell c : new PdfPCell[]{c1, c2}) {
            c.setPadding(5);
            c.setBorderColor(BORDA);
        }
        t.addCell(c1);
        t.addCell(c2);
    }

    private void secao(Document doc, Font fSecao, String texto) throws DocumentException {
        PdfPTable barra = new PdfPTable(1);
        barra.setWidthPercentage(100);
        barra.setSpacingBefore(12);
        barra.setSpacingAfter(2);
        PdfPCell c = new PdfPCell(new Phrase(texto, fSecao));
        c.setBackgroundColor(AZUL);
        c.setPadding(5);
        c.setBorder(Rectangle.NO_BORDER);
        barra.addCell(c);
        doc.add(barra);
    }

    private void cabecalho(PdfPTable t, String... cols) {
        Font f = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        for (String col : cols) {
            PdfPCell c = new PdfPCell(new Phrase(col, f));
            c.setBackgroundColor(CINZA);
            c.setPadding(4);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            t.addCell(c);
        }
    }

    private void celula(PdfPTable t, String texto) {
        Font f = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
        PdfPCell c = new PdfPCell(new Phrase(texto, f));
        c.setPadding(4);
        c.setHorizontalAlignment(Element.ALIGN_LEFT);
        c.setBorderColor(BORDA);
        t.addCell(c);
    }

    /** Celula da coluna "Prazo": "Dentro" (cinza) ou "Fora" (vermelho, negrito). */
    private void celulaPrazo(PdfPTable t, boolean foraDoPrazo) {
        Font f = foraDoPrazo
            ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, VERMELHO)
            : FontFactory.getFont(FontFactory.HELVETICA, 8, CINZA);
        PdfPCell c = new PdfPCell(new Phrase(foraDoPrazo ? "Fora" : "Dentro", f));
        c.setPadding(4);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setBorderColor(BORDA);
        t.addCell(c);
    }

    private String nvl(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}

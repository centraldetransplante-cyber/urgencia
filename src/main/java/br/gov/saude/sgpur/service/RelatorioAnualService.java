package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.service.TempoRespostaService.ResumoTempo;
import br.gov.saude.sgpur.service.TempoRespostaService.TempoMembro;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gera o Relatorio Geral (anual) de Urgencia Renal em PDF: um resumo com a
 * contagem por status do ano e a lista completa dos processos daquele ano.
 *
 * Mantem o mesmo padrao visual do {@link RelatorioService} (capa institucional
 * com brasao, barras de secao azuis, tabelas com bordas claras) e o MESMO
 * cabecalho estampado em toda pagina (logo + 2 linhas + numeracao "Pagina X de
 * Y"), via {@link PdfCabecalhoStamper} - documento gerado normalmente e depois
 * estampado como pos-processamento, igual ao Relatorio Final.
 */
@Service
public class RelatorioAnualService {

    private static final Logger log = LoggerFactory.getLogger(RelatorioAnualService.class);

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // Decisao 6 do relatorio V2 (§7.6): paleta institucional compartilhada
    // com o Relatorio Final e o Relatorio do Avaliador via PaletaPdf, no
    // lugar do azul/cinza/borda do Bootstrap que este documento usava desde
    // sempre - eram a origem do achado B6 (dois azuis institucionais
    // diferentes nos PDFs do proprio sistema).
    private static final Color AZUL = PaletaPdf.AZUL;
    private static final Color CINZA = PaletaPdf.CINZA;
    private static final Color BORDA = PaletaPdf.CINZA_BORDA;

    private final TempoRespostaService tempoRespostaService;

    public RelatorioAnualService(TempoRespostaService tempoRespostaService) {
        this.tempoRespostaService = tempoRespostaService;
    }

    /**
     * Gera o PDF do relatorio anual.
     *
     * @param ano       ano de referencia
     * @param processos processos do ano (ja ordenados por sequencial)
     */
    public byte[] gerar(int ano, List<Processo> processos) {
        byte[] semCabecalho = gerarSemCabecalho(ano, processos);
        return PdfCabecalhoStamper.estampar(semCabecalho,
            PdfCabecalhoStamper.NOME_INSTITUICAO + " - URGÊNCIA RENAL",
            "Relatório Geral de Urgência Renal - Ano " + ano);
    }

    private byte[] gerarSemCabecalho(int ano, List<Processo> processos) {
        Document doc = new Document(PageSize.A4, 36, 36, 46, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            adicionarCapa(doc, ano, processos);
            doc.newPage();

            Font fSecao = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.WHITE);

            // Pareceres respondidos do ano (mesmo criterio da query
            // findRespondidosComDatas: resultado, dataEnvio e dataResposta
            // preenchidos), para o indicador de tempo de resposta.
            Map<Long, String> nomePorMembro = new LinkedHashMap<>();
            List<Parecer> pareceresRespondidos = new java.util.ArrayList<>();
            for (Processo p : processos) {
                for (Parecer par : p.getPareceres()) {
                    if (par.getResultado() != null && par.getDataEnvio() != null
                        && par.getDataResposta() != null) {
                        pareceresRespondidos.add(par);
                        MembroUrgenciaRenal m = par.getMembro();
                        if (m != null) {
                            nomePorMembro.putIfAbsent(m.getId(), m.getRotulo());
                        }
                    }
                }
            }
            ResumoTempo tempoAno = tempoRespostaService.calcularDe(pareceresRespondidos);

            // 1. Resumo do ano
            secao(doc, fSecao, "1. Resumo do ano " + ano);
            doc.add(tabelaResumo(processos, tempoAno));

            // 2. Tempo de resposta por avaliador
            secao(doc, fSecao, "2. Tempo de resposta por avaliador");
            doc.add(tabelaTempoPorAvaliador(tempoAno, nomePorMembro));

            // 3. Lista completa
            secao(doc, fSecao, "3. Lista de processos do ano " + ano);
            doc.add(tabelaLista(processos));

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
            throw new IllegalStateException("Falha ao gerar o relatorio anual PDF", e);
        }
    }

    // -----------------------------------------------------------------------
    // Capa
    // -----------------------------------------------------------------------

    private void adicionarCapa(Document doc, int ano, List<Processo> processos) throws DocumentException {
        Font fOrgao = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.BLACK);
        Font fSubOrgao = FontFactory.getFont(FontFactory.HELVETICA, 12, Color.BLACK);
        Font fUrgencia = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, AZUL);
        Font fTituloDoc = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.BLACK);
        Font fSub = FontFactory.getFont(FontFactory.HELVETICA, 11, CINZA);

        Paragraph espaco = new Paragraph(" ");
        espaco.setSpacingAfter(40);
        doc.add(espaco);

        // Brasao do RS no topo da capa - mesmo padrao do Relatorio Final.
        try {
            byte[] logoBytes = getClass().getClassLoader()
                .getResourceAsStream("static/brasao.png").readAllBytes();
            Image brasao = Image.getInstance(logoBytes);
            brasao.scaleToFit(110, 110);
            brasao.setAlignment(Element.ALIGN_CENTER);
            brasao.setSpacingAfter(12);
            doc.add(brasao);
        } catch (Exception e) {
            log.warn("Logo nao encontrado em static/brasao.png, capa do relatorio anual sem imagem");
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

        Paragraph tituloDoc = new Paragraph("RELATÓRIO GERAL DE URGÊNCIA RENAL - ANO " + ano, fTituloDoc);
        tituloDoc.setAlignment(Element.ALIGN_CENTER);
        tituloDoc.setSpacingAfter(16);
        doc.add(tituloDoc);

        Paragraph resumoCapa = new Paragraph(
            "Total de processos no ano: " + processos.size()
                + "  |  Emitido em " + java.time.LocalDateTime.now().format(DATA_HORA), fSub);
        resumoCapa.setAlignment(Element.ALIGN_CENTER);
        doc.add(resumoCapa);
    }

    // -----------------------------------------------------------------------
    // Resumo (contagem por status)
    // -----------------------------------------------------------------------

    private PdfPTable tabelaResumo(List<Processo> processos, ResumoTempo tempoAno) {
        Map<StatusProcesso, Integer> contagem = new EnumMap<>(StatusProcesso.class);
        for (Processo p : processos) {
            contagem.merge(p.getStatus(), 1, Integer::sum);
        }
        int total = processos.size();
        int solicitado = contagem.getOrDefault(StatusProcesso.SOLICITADO, 0);
        int emAndamento = contagem.getOrDefault(StatusProcesso.ENVIADO, 0);
        int solicitaInfo = contagem.getOrDefault(StatusProcesso.SOLICITA_INFORMACAO, 0);
        int deferido = contagem.getOrDefault(StatusProcesso.DEFERIDO, 0);
        int indeferido = contagem.getOrDefault(StatusProcesso.INDEFERIDO, 0);
        int cancelado = contagem.getOrDefault(StatusProcesso.CANCELADO, 0);

        int decididos = deferido + indeferido;
        String percentDeferimento = decididos == 0
            ? "-" : Math.round(deferido * 100.0 / decididos) + "%";

        PdfPTable t = new PdfPTable(new float[]{6, 2});
        t.setWidthPercentage(60);
        t.setSpacingBefore(6);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);

        linhaResumo(t, "Total de processos", String.valueOf(total), true);
        linhaResumo(t, "Solicitados (aguardando envio)", String.valueOf(solicitado), false);
        linhaResumo(t, "Em andamento (enviados / em análise)", String.valueOf(emAndamento), false);
        linhaResumo(t, "Solicita informação", String.valueOf(solicitaInfo), false);
        linhaResumo(t, "Deferidos", String.valueOf(deferido), false);
        linhaResumo(t, "Indeferidos", String.valueOf(indeferido), false);
        linhaResumo(t, "Cancelados", String.valueOf(cancelado), false);
        linhaResumo(t, "% de deferimento (sobre os decididos)", percentDeferimento, true);
        linhaResumo(t, "Tempo médio de resposta dos avaliadores",
            TempoRespostaService.formatarDias(tempoAno.mediaGeralDias()), true);
        linhaResumo(t, "Pareceres fora do prazo (meta " + tempoAno.prazoDias() + " dias corridos)",
            tempoAno.foraDoPrazo() + " de " + tempoAno.totalAvaliados(), false);
        return t;
    }

    // -----------------------------------------------------------------------
    // Tempo de resposta por avaliador
    // -----------------------------------------------------------------------

    private PdfPTable tabelaTempoPorAvaliador(ResumoTempo tempoAno, Map<Long, String> nomePorMembro) {
        PdfPTable t = new PdfPTable(new float[]{4, 2, 2, 2});
        t.setWidthPercentage(80);
        t.setSpacingBefore(6);
        t.setHeaderRows(1);
        cabecalho(t, "Avaliador", "Respondidos", "Tempo médio", "Fora do prazo");

        if (tempoAno.porMembro().isEmpty()) {
            PdfPCell vazio = new PdfPCell(new Phrase("Nenhum parecer respondido neste ano.",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, CINZA)));
            vazio.setColspan(4);
            vazio.setPadding(8);
            vazio.setHorizontalAlignment(Element.ALIGN_CENTER);
            vazio.setBorderColor(BORDA);
            t.addCell(vazio);
            return t;
        }

        for (var e : tempoAno.porMembro().entrySet()) {
            TempoMembro tm = e.getValue();
            celula(t, nomePorMembro.getOrDefault(e.getKey(), "Membro #" + e.getKey()));
            celula(t, String.valueOf(tm.avaliados()));
            celula(t, TempoRespostaService.formatarDias(tm.mediaDias()));
            celula(t, String.valueOf(tm.foraDoPrazo()));
        }
        return t;
    }

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

    // -----------------------------------------------------------------------
    // Lista completa
    // -----------------------------------------------------------------------

    private PdfPTable tabelaLista(List<Processo> processos) {
        PdfPTable t = new PdfPTable(new float[]{1.4f, 2.6f, 1.6f, 1.6f, 3, 3, 3, 1.6f, 1.6f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        t.setHeaderRows(1);
        cabecalho(t, "Nº/Ano", "Paciente", "RGCT", "Status",
            "Médico 1", "Médico 2", "Médico 3", "Cadastro", "Decisão");

        if (processos.isEmpty()) {
            PdfPCell vazio = new PdfPCell(new Phrase("Nenhum processo neste ano.",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, CINZA)));
            vazio.setColspan(9);
            vazio.setPadding(8);
            vazio.setHorizontalAlignment(Element.ALIGN_CENTER);
            vazio.setBorderColor(BORDA);
            t.addCell(vazio);
            return t;
        }

        for (Processo p : processos) {
            celula(t, nvl(p.getNumero()));
            celula(t, nvl(p.getPacienteNome()));
            celula(t, nvl(p.getPacienteRgct()));
            celula(t, PdfRelatorioBuilder.descricaoStatus(p.getStatus()));

            List<Parecer> pareceres = p.getPareceres();
            for (int i = 0; i < 3; i++) {
                if (i < pareceres.size()) {
                    Parecer par = pareceres.get(i);
                    String res = par.getResultado() != null
                        ? PdfRelatorioBuilder.descricaoResultado(par.getResultado()) : "Aguardando";
                    celula(t, par.getMembro().getRotulo() + " (" + res + ")");
                } else {
                    celula(t, "-");
                }
            }

            celula(t, p.getDataCadastro() != null ? p.getDataCadastro().format(DATA) : "-");
            celula(t, p.getDataDecisao() != null ? p.getDataDecisao().format(DATA) : "-");
        }
        return t;
    }

    // -----------------------------------------------------------------------
    // Helpers de estilo (espelham o RelatorioService)
    // -----------------------------------------------------------------------

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

    private String nvl(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}

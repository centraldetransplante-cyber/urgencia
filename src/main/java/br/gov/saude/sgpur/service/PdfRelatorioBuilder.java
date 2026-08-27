package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Mecanica de construcao visual/binaria do Relatorio Final em PDF: paragrafos,
 * tabelas, fontes, cores, capa institucional e merge de paginas via
 * PdfCopy/PdfReader. Nao decide o que entra no relatorio nem em que ordem -
 * essa responsabilidade eh do {@link RelatorioService}, que chama estes
 * metodos ja com os dados/filtros aplicados.
 */
class PdfRelatorioBuilder {

    private static final Logger log = LoggerFactory.getLogger(PdfRelatorioBuilder.class);

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Paleta institucional COMPLETA (Decisao 1 + Decisao 6 do relatorio V2,
    // §7.1/§7.6) - antes so o AZUL tinha sido trocado (PR #45), deixando
    // CINZA/CINZA_BORDA/VERDE_ESCURO/VERMELHO como defaults do Bootstrap
    // ($secondary/$gray-300/$success/$danger). Agora as 5 cores vem de
    // PaletaPdf, compartilhada com RelatorioAnualService/
    // RelatorioAvaliadorService - ver javadoc de PaletaPdf para o raciocinio
    // completo (inclusive por que NAO ficou "so o Relatorio Final" desta
    // vez, ao contrario da decisao original do PR #45).
    static final Color AZUL = PaletaPdf.AZUL;
    static final Color CINZA = PaletaPdf.CINZA;
    static final Color CINZA_BORDA = PaletaPdf.CINZA_BORDA;
    static final Color VERDE_ESCURO = PaletaPdf.VERDE_ESCURO;
    static final Color VERMELHO = PaletaPdf.VERMELHO;

    private final AnexoStorageService anexoStorage;

    PdfRelatorioBuilder(AnexoStorageService anexoStorage) {
        this.anexoStorage = anexoStorage;
    }

    // B1/Decisao 9 do relatorio V2 (§4.1/§7.9): o Relatorio Final NUNCA foi
    // A4 de verdade - PdfCabecalhoStamper.expandirTopo AUMENTA o MediaBox de
    // cada pagina em ALTURA_CABECALHO (55pt) no topo para desenhar o carimbo
    // sem cobrir conteudo, mas isso e aplicado tambem as paginas que o
    // PROPRIO sistema gera (sumario, avisos, fecho), que ja tem margem
    // sobrando e nao precisavam da expansao - o resultado final tinha
    // 595x897pt (31,6cm de altura, 6,5% mais que os 29,7cm do A4), sem
    // rotulo "(A4)" no pdfinfo. Corrigido reservando o espaco do carimbo NO
    // LAYOUT dessas paginas (abrindo o Document ja 55pt mais baixo), para
    // que a expansao do stamper resulte EXATAMENTE em A4. As paginas de
    // ANEXO IMPORTADAS continuam sendo expandidas sem este ajuste - ali a
    // expansao e o comportamento certo (documento escaneado sem margem
    // superior garantida), e normalizar tudo pra A4 exigiria reescalar scans,
    // degradando a resolucao (ver ressalva na Decisao 9).
    private static final Rectangle TAMANHO_PAGINA_SISTEMA =
        new Rectangle(PageSize.A4.getWidth(), PageSize.A4.getHeight() - PdfCabecalhoStamper.ALTURA_CABECALHO);

    Document abrirDocumentoA4(ByteArrayOutputStream out) throws DocumentException {
        // Margem superior 30pt (era 50pt, quando a pagina ainda abria em
        // PageSize.A4 "cheio"): o PdfCabecalhoStamper expande 55pt no topo
        // fisico da pagina para desenhar o cabecalho carimbado (logo + 2
        // linhas + regua em y=topo-44). A conta do respiro entre a regua e o
        // primeiro texto do corpo (~41pt, ~1,4cm) e a MESMA de antes: reduzir
        // a altura do Document em 55pt (TAMANHO_PAGINA_SISTEMA, acima) e
        // depois expandir 55pt de volta no stamper resulta no mesmo gap
        // relativo, so que agora a pagina final e A4 de verdade em vez de
        // 55pt mais alta.
        Document doc = new Document(TAMANHO_PAGINA_SISTEMA, 40, 40, 30, 40);
        PdfWriter.getInstance(doc, out);
        doc.open();
        return doc;
    }

    // -----------------------------------------------------------------------
    // Merge com anexos
    // -----------------------------------------------------------------------

    byte[] mergeComAnexos(byte[] capa, byte[] summary, List<Anexo> pdfs, List<Anexo> naoPdf, String fechoTexto)
            throws DocumentException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document();
        PdfCopy copier = new PdfCopy(doc, baos);
        doc.open();

        // P8(c) do relatorio V2 (§7.8/§5.6): marcadores/bookmarks de
        // navegacao, viabilidade confirmada decompilando o .jar
        // (SimpleBookmark + PdfCopy/PdfWriter.setOutlines). Custo zero de
        // layout, ganho grande de navegacao num dossie de 40+ paginas -
        // hoje nao ha NENHUMA marca de onde termina um anexo e comeca o
        // outro alem da folha divisoria nova (P8(a) abaixo).
        List<HashMap<String, Object>> marcadores = new ArrayList<>();

        if (capa != null && capa.length > 0) {
            marcadores.add(marcador("Capa", copier.getCurrentPageNumber()));
            try (PdfReader capaReader = new PdfReader(capa)) {
                for (int i = 1; i <= capaReader.getNumberOfPages(); i++) {
                    copier.addPage(copier.getImportedPage(capaReader, i));
                }
            }
        }

        marcadores.add(marcador("Sumário", copier.getCurrentPageNumber()));

        try (PdfReader summaryReader = new PdfReader(summary)) {
            for (int i = 1; i <= summaryReader.getNumberOfPages(); i++) {
                copier.addPage(copier.getImportedPage(summaryReader, i));
            }
        }

        for (Anexo a : pdfs) {
            Path path = anexoStorage.resolverArquivo(a);
            if (!Files.exists(path)) {
                log.warn("Anexo PDF nao encontrado no disco: {} ({})", a.getNomeArquivo(), path);
                marcadores.add(marcador(a.getNomeArquivo() + " (não encontrado)", copier.getCurrentPageNumber()));
                adicionarPaginaAviso(copier,
                    "Anexo não encontrado: " + a.getNomeArquivo(),
                    "O arquivo \"" + a.getNomeArquivo()
                        + "\" (" + descricaoTipoAnexo(a.getTipo())
                        + ") não foi localizado no disco.");
                continue;
            }
            // P8(a): folha divisoria por anexo (mecanismo ja existia -
            // adicionarPaginaAviso - so nao era chamado no caminho normal,
            // so nos avisos de erro). Num processo real de 30-60 paginas de
            // exame escaneado, sem isto nao ha NENHUMA marca visual de onde
            // termina um anexo e comeca o outro - o carimbo do topo e
            // identico em todas as paginas.
            marcadores.add(marcador(a.getNomeArquivo(), copier.getCurrentPageNumber()));
            adicionarFolhaDivisoria(copier, a);
            try (PdfReader reader = new PdfReader(Files.readAllBytes(path))) {
                for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                    copier.addPage(copier.getImportedPage(reader, i));
                }
            } catch (Exception e) {
                log.error("Erro ao importar anexo PDF {}: {}", a.getNomeArquivo(), e.getMessage());
                adicionarPaginaAviso(copier,
                    "Erro ao importar: " + a.getNomeArquivo(),
                    "Não foi possível importar \"" + a.getNomeArquivo()
                        + "\" (" + descricaoTipoAnexo(a.getTipo())
                        + "): " + e.getMessage());
            }
        }

        for (Anexo a : naoPdf) {
            marcadores.add(marcador(a.getNomeArquivo(), copier.getCurrentPageNumber()));
            adicionarPaginaAviso(copier,
                "Anexo (formato não-PDF): " + a.getNomeArquivo(),
                "Tipo: " + descricaoTipoAnexo(a.getTipo())
                    + "\nArquivo: " + a.getNomeArquivo()
                    + "\nFormato: " + (a.getContentType() != null ? a.getContentType() : "desconhecido")
                    + "\nData: " + (a.getDataUpload() != null ? a.getDataUpload().format(DATA) : "-")
                    + "\n\nEste anexo está disponível para download na página do processo.");
        }

        // A11 do relatorio V2: o fecho do documento sai do fim do SUMARIO
        // (onde ficava antes de tudo isto, ou seja, ANTES dos anexos
        // importados) e vira a ultima pagina do documento inteiro - dossies
        // reais tem dezenas de paginas de exame depois do sumario, e um
        // "documento gerado automaticamente por..." no meio do dossie nao
        // faz sentido como fecho.
        if (fechoTexto != null && !fechoTexto.isBlank()) {
            adicionarPaginaFecho(copier, fechoTexto);
        }

        copier.setOutlines(marcadores);
        doc.close();
        return baos.toByteArray();
    }

    /** Um item de marcador (bookmark) apontando para {@code pagina} do PDF resultante. */
    private static HashMap<String, Object> marcador(String titulo, int pagina) {
        HashMap<String, Object> item = new HashMap<>();
        item.put("Title", titulo);
        item.put("Action", "GoTo");
        item.put("Page", pagina + " Fit");
        return item;
    }

    /**
     * Folha divisoria simples, gerada antes de cada anexo PDF importado com
     * sucesso - P8(a) do relatorio V2. Reaproveita a mesma pagina A4 "de
     * sistema" ({@link #TAMANHO_PAGINA_SISTEMA}) das demais paginas geradas
     * pelo proprio Relatorio Final.
     */
    private void adicionarFolhaDivisoria(PdfCopy copier, Anexo a)
            throws DocumentException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document d = new Document(TAMANHO_PAGINA_SISTEMA, 40, 40, 50, 40);
        PdfWriter.getInstance(d, baos);
        d.open();
        Paragraph pTitulo = new Paragraph("Anexo: " + a.getNomeArquivo(),
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, AZUL));
        pTitulo.setAlignment(Element.ALIGN_CENTER);
        pTitulo.setSpacingAfter(10);
        d.add(pTitulo);
        Paragraph pTipo = new Paragraph(descricaoTipoAnexo(a.getTipo()),
            FontFactory.getFont(FontFactory.HELVETICA, 11, CINZA));
        pTipo.setAlignment(Element.ALIGN_CENTER);
        d.add(pTipo);
        if (a.getDataUpload() != null) {
            Paragraph pData = new Paragraph("Anexado em " + a.getDataUpload().format(DATA),
                FontFactory.getFont(FontFactory.HELVETICA, 9, CINZA));
            pData.setAlignment(Element.ALIGN_CENTER);
            pData.setSpacingBefore(4);
            d.add(pData);
        }
        d.close();

        try (PdfReader reader = new PdfReader(baos.toByteArray())) {
            copier.addPage(copier.getImportedPage(reader, 1));
        }
    }

    private void adicionarPaginaAviso(PdfCopy copier, String titulo, String corpo)
            throws DocumentException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document d = new Document(TAMANHO_PAGINA_SISTEMA, 40, 40, 50, 40);
        PdfWriter.getInstance(d, baos);
        d.open();
        Paragraph pTitulo = new Paragraph(titulo,
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, CINZA));
        pTitulo.setSpacingAfter(20);
        d.add(pTitulo);
        Paragraph pCorpo = new Paragraph(corpo,
            FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK));
        d.add(pCorpo);
        d.close();

        try (PdfReader reader = new PdfReader(baos.toByteArray())) {
            copier.addPage(copier.getImportedPage(reader, 1));
        }
    }

    /**
     * Ultima pagina do documento inteiro (apos todos os anexos importados),
     * so com o texto de fecho institucional - ver {@link #mergeComAnexos}.
     */
    private void adicionarPaginaFecho(PdfCopy copier, String texto)
            throws DocumentException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document d = new Document(TAMANHO_PAGINA_SISTEMA, 40, 40, 50, 40);
        PdfWriter.getInstance(d, baos);
        d.open();
        Paragraph p = new Paragraph(texto, FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, CINZA));
        p.setAlignment(Element.ALIGN_CENTER);
        d.add(p);
        d.close();

        try (PdfReader reader = new PdfReader(baos.toByteArray())) {
            copier.addPage(copier.getImportedPage(reader, 1));
        }
    }

    // -----------------------------------------------------------------------
    // Capa (folha de rosto dedicada) - REINTRODUZIDA em 2026-08-07
    // -----------------------------------------------------------------------

    /**
     * Dados que a capa exibe. Deliberadamente CURTO: a capa e uma folha de
     * rosto, nao um resumo do processo - quem decide o conteudo e
     * {@link RelatorioService}, que ja monta as secoes completas na pagina
     * seguinte.
     *
     * @param titulo         "RELATÓRIO FINAL" ou "RELATÓRIO PARCIAL"
     * @param subtitulo      linha menor sob o titulo (natureza do documento)
     * @param numeroProcesso numero CET-RS (NN/AAAA)
     * @param paciente       nome completo do paciente (documento interno de
     *                       arquivo - nao e material de avaliador, entao nao
     *                       se aplica a regra das iniciais)
     * @param rotuloSituacao rotulo do ultimo campo ("Situação"/"Resultado")
     * @param situacao       texto do desfecho/andamento
     * @param corSituacao    cor semantica do desfecho (reforco, nunca o unico
     *                       portador da informacao - o texto sozinho basta)
     * @param emitidoEm      data/hora de emissao ja formatada
     */
    record DadosCapa(String titulo, String subtitulo, String numeroProcesso, String paciente,
                     String rotuloSituacao, String situacao, Color corSituacao, String emitidoEm) {
    }

    /** Margem lateral da capa (maior que a das paginas de conteudo, de proposito). */
    private static final float MARGEM_CAPA = 62;

    /** Fundo do painel de identificacao da capa ({@code --rs-gray-50}). */
    private static final Color FUNDO_PAINEL_CAPA = new Color(0xF8, 0xFA, 0xFC);

    /**
     * Gera a CAPA do Relatorio Final: uma pagina A4 propria, com brasao,
     * identificacao institucional, titulo do documento e um painel enxuto
     * com numero do processo, paciente, situacao e data de emissao.
     *
     * <p><b>Historico - por que a capa voltou.</b> Ate 2026-08-06 existia uma
     * capa; o R6 do relatorio V2 (§7.7) a ELIMINOU, e o sumario passou a ser
     * a folha de rosto. Os motivos daquela remocao eram concretos e continuam
     * validos como criterios de projeto: a capa antiga repetia uma tabela de
     * dados INTEIRA mais a tabela de avaliadores que o sumario, logo depois,
     * reimprimia por completo (achado 6.6 do relatorio original); sobrava
     * cerca de um terco de pagina em branco; e havia DOIS brasoes na mesma
     * dupla de paginas (o da capa e o do carimbo do
     * {@link PdfCabecalhoStamper}, que carimbava tambem a capa).
     *
     * <p>Em 2026-08-07 o dono do produto pediu explicitamente uma capa de
     * volta ("o relatorio final em PDF dos processos precisa ter uma capa").
     * Esta implementacao e um desenho NOVO, nao a restauracao do commit
     * revertido, e cada um dos tres problemas acima foi tratado:
     * <ul>
     *   <li><b>Sem duplicacao de tabela:</b> a capa mostra 4 dados
     *       (numero, paciente, situacao, emissao) em tipografia grande, nao
     *       uma copia das secoes 1/2/3. A tabela de avaliadores nao aparece
     *       aqui. O "Emitido em" saiu da linha de subtitulo do sumario e
     *       passou a existir SO na capa, preservando a regra A12 do
     *       relatorio V2 (um unico carimbo de emissao no documento).</li>
     *   <li><b>Sem vazio esquisito:</b> o espaco livre e distribuido (respiro
     *       generoso entre blocos + painel de identificacao com peso visual),
     *       nao acumulado num bloco morto no rodape.</li>
     *   <li><b>Um brasao so:</b> a capa e a UNICA pagina que nao recebe o
     *       carimbo institucional - {@link RelatorioService} chama
     *       {@link PdfCabecalhoStamper#estampar(byte[], String, String, int)}
     *       comecando na primeira pagina DEPOIS da capa. Por isso esta pagina
     *       e criada em {@link PageSize#A4} cheio, e nao em
     *       {@link #TAMANHO_PAGINA_SISTEMA} (que ja desconta os 55pt que o
     *       stamper devolve ao expandir o topo) - o resultado continua A4 de
     *       verdade em todas as paginas, mantendo a correcao do R5.</li>
     * </ul>
     *
     * <p>Sobre a paleta: cor so no filete/regua institucional e no texto do
     * desfecho ({@link PaletaPdf}), sem faixa chapada de fundo a fundo - o R4
     * reduziu de proposito a proporcao da pagina ocupada por decoracao azul, e
     * esta capa nao regride nisso.
     */
    byte[] gerarCapa(DadosCapa dados) throws DocumentException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, MARGEM_CAPA, MARGEM_CAPA, 86, 40);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        doc.open();

        float larguraPagina = PageSize.A4.getWidth();
        float alturaPagina = PageSize.A4.getHeight();
        PdfContentByte cb = writer.getDirectContent();

        // Regua institucional no topo (3pt): acabamento discreto, sem virar
        // faixa decorativa (ver R4 acima).
        cb.setLineWidth(3f);
        cb.setColorStroke(AZUL);
        cb.moveTo(MARGEM_CAPA, alturaPagina - 46);
        cb.lineTo(larguraPagina - MARGEM_CAPA, alturaPagina - 46);
        cb.stroke();

        // --- Bloco institucional (brasao + orgao + secretaria) ---
        try {
            byte[] logoBytes = getClass().getClassLoader()
                .getResourceAsStream("static/brasao.png").readAllBytes();
            Image brasao = Image.getInstance(logoBytes);
            // Limite pela ALTURA, nao por um quadrado: static/brasao.png e
            // 300x168 com folga transparente nas laterais, entao
            // scaleToFit(N, N) trava na largura e o brasao sai visualmente
            // minusculo numa capa (foi o que aconteceu na primeira versao).
            brasao.scaleToFit(220, 92);
            brasao.setAlignment(Element.ALIGN_CENTER);
            brasao.setSpacingAfter(16);
            doc.add(brasao);
        } catch (Exception e) {
            log.warn("Logo nao encontrado em static/brasao.png, capa sem imagem");
        }

        Paragraph orgao = new Paragraph(PdfCabecalhoStamper.NOME_INSTITUICAO,
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.BLACK));
        orgao.setAlignment(Element.ALIGN_CENTER);
        doc.add(orgao);

        Paragraph secretaria = new Paragraph(PdfCabecalhoStamper.SECRETARIA,
            FontFactory.getFont(FontFactory.HELVETICA, 9.5f, CINZA));
        secretaria.setAlignment(Element.ALIGN_CENTER);
        secretaria.setSpacingBefore(3);
        doc.add(secretaria);

        doc.add(fileteCentral(16, 0));

        // --- Titulo do documento ---
        Paragraph titulo = new Paragraph(dados.titulo(),
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 27, AZUL));
        titulo.setAlignment(Element.ALIGN_CENTER);
        titulo.setSpacingBefore(76);
        doc.add(titulo);

        Paragraph subtitulo = new Paragraph(dados.subtitulo(),
            FontFactory.getFont(FontFactory.HELVETICA, 12, CINZA));
        subtitulo.setAlignment(Element.ALIGN_CENTER);
        subtitulo.setSpacingBefore(8);
        doc.add(subtitulo);

        // --- Painel de identificacao ---
        doc.add(painelIdentificacao(dados));

        // --- Rodape da capa (posicao fixa, para o respiro ficar entre os
        // blocos e nao empilhado no pe da pagina) ---
        try {
            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            cb.setLineWidth(0.7f);
            cb.setColorStroke(CINZA_BORDA);
            cb.moveTo(MARGEM_CAPA, 104);
            cb.lineTo(larguraPagina - MARGEM_CAPA, 104);
            cb.stroke();

            cb.beginText();
            cb.setColorFill(CINZA);
            cb.setFontAndSize(bf, 9);
            cb.showTextAligned(Element.ALIGN_CENTER, "Emitido em " + dados.emitidoEm(),
                larguraPagina / 2, 86, 0);
            cb.setFontAndSize(bf, 8);
            cb.showTextAligned(Element.ALIGN_CENTER, PdfCabecalhoStamper.NOME_SISTEMA,
                larguraPagina / 2, 72, 0);
            cb.endText();
        } catch (IOException e) {
            log.warn("Falha ao desenhar o rodape da capa: {}", e.getMessage());
        }

        doc.close();
        return out.toByteArray();
    }

    /** Filete curto centralizado, em AZUL - separador tipografico da capa. */
    private PdfPTable fileteCentral(float espacoAntes, float espacoDepois) {
        PdfPTable filete = new PdfPTable(1);
        filete.setWidthPercentage(14);
        filete.setHorizontalAlignment(Element.ALIGN_CENTER);
        filete.setSpacingBefore(espacoAntes);
        filete.setSpacingAfter(espacoDepois);
        PdfPCell c = new PdfPCell(new Phrase(" ", FontFactory.getFont(FontFactory.HELVETICA, 2)));
        c.setBorderWidthBottom(2f);
        c.setBorderWidthTop(0);
        c.setBorderWidthLeft(0);
        c.setBorderWidthRight(0);
        c.setBorderColorBottom(AZUL);
        c.setPadding(0);
        filete.addCell(c);
        return filete;
    }

    /**
     * Painel central da capa: numero do processo, paciente e situacao, em
     * tipografia grande sobre fundo claro com filete AZUL a esquerda. E o
     * unico bloco "denso" da pagina de proposito - dar peso visual a ele e o
     * que evita a capa parecer uma folha vazia com um titulo solto (um dos
     * defeitos da capa antiga).
     */
    private PdfPTable painelIdentificacao(DadosCapa dados) {
        Font fRotulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, CINZA);

        PdfPTable painel = new PdfPTable(1);
        painel.setWidthPercentage(74);
        painel.setHorizontalAlignment(Element.ALIGN_CENTER);
        painel.setSpacingBefore(78);

        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(FUNDO_PAINEL_CAPA);
        cell.setBorderWidthLeft(3f);
        cell.setBorderColorLeft(AZUL);
        cell.setBorderWidthTop(0);
        cell.setBorderWidthRight(0);
        cell.setBorderWidthBottom(0);
        cell.setPadding(26);

        adicionarLinhaPainel(cell, "PROCESSO CET-RS", fRotulo,
            dados.numeroProcesso(),
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, Color.BLACK), 0);
        adicionarLinhaPainel(cell, "PACIENTE", fRotulo,
            dados.paciente(),
            FontFactory.getFont(FontFactory.HELVETICA, 13, Color.BLACK), 22);
        adicionarLinhaPainel(cell, dados.rotuloSituacao().toUpperCase(), fRotulo,
            dados.situacao(),
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, dados.corSituacao()), 22);

        painel.addCell(cell);
        return painel;
    }

    /**
     * Par "rotulo pequeno + valor destacado", centralizado, dentro do painel
     * da capa. Sao DOIS paragrafos, e nao um so com {@code \n}: num paragrafo
     * unico o leading e calculado a partir da fonte do PRIMEIRO chunk (o
     * rotulo de 8pt), e o valor de 22pt subia por cima do rotulo.
     */
    private void adicionarLinhaPainel(PdfPCell cell, String rotulo, Font fRotulo,
                                      String valor, Font fValor, float espacoAntes) {
        Paragraph pRotulo = new Paragraph(rotulo, fRotulo);
        pRotulo.setAlignment(Element.ALIGN_CENTER);
        pRotulo.setSpacingBefore(espacoAntes);
        pRotulo.setSpacingAfter(4);
        cell.addElement(pRotulo);

        Paragraph pValor = new Paragraph(nvl(valor), fValor);
        pValor.setAlignment(Element.ALIGN_CENTER);
        pValor.setLeading(fValor.getSize() * 1.25f);
        cell.addElement(pValor);
    }

    /**
     * Cabecalho institucional COMPACTO no topo da pagina de sumario. Nasceu na
     * Decisao 7 do relatorio V2 (§7.7) como SUBSTITUTO da capa entao
     * eliminada; com a capa de volta (ver {@link #gerarCapa}), ele continua
     * util por outro motivo: o sumario e a primeira pagina CARIMBADA, e este
     * bloco identifica o orgao no corpo da pagina sem depender do carimbo do
     * topo. Nao repete nenhum dado do processo - so orgao e secretaria.
     */
    void cabecalhoInstitucionalCompacto(Document doc) throws DocumentException {
        Font fOrgao = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.BLACK);
        Font fSubOrgao = FontFactory.getFont(FontFactory.HELVETICA, 10, CINZA);

        try {
            byte[] logoBytes = getClass().getClassLoader()
                .getResourceAsStream("static/brasao.png").readAllBytes();
            Image brasao = Image.getInstance(logoBytes);
            brasao.scaleToFit(48, 48);
            brasao.setAlignment(Element.ALIGN_CENTER);
            brasao.setSpacingAfter(6);
            doc.add(brasao);
        } catch (Exception e) {
            log.warn("Logo nao encontrado em static/brasao.png, cabecalho institucional sem imagem");
        }

        Paragraph orgao = new Paragraph(PdfCabecalhoStamper.NOME_INSTITUICAO, fOrgao);
        orgao.setAlignment(Element.ALIGN_CENTER);
        doc.add(orgao);

        Paragraph secretaria = new Paragraph(PdfCabecalhoStamper.SECRETARIA, fSubOrgao);
        secretaria.setAlignment(Element.ALIGN_CENTER);
        secretaria.setSpacingAfter(10);
        doc.add(secretaria);
    }

    // -----------------------------------------------------------------------
    // Helpers de layout
    // -----------------------------------------------------------------------

    /**
     * Titulo de secao como TIPOGRAFIA (negrito, azul institucional, sem caixa
     * alta - o texto passado ja vem no case que deve ser exibido) com um
     * filete de 1,5pt embaixo, em vez da faixa AZUL chapada de fundo a
     * fundo que o documento usava antes (R4 do relatorio V2, §7 e §6 -
     * protótipo). Motivo, com respaldo externo (§5.1/§5.5 do relatorio V2):
     * o padrao ofício da Presidencia da Republica prescreve texto preto em
     * papel branco, reservando cor para graficos/ilustracoes, e a mesma faixa
     * chapada mede 11-13% da area da pagina e 59-68% de toda a tinta impressa
     * (achado B7) - virando mancha solida em fotocopia. O filete sozinho ja
     * demarca a secao sem pagar esse custo (Butterick: bordas finas nao
     * "ofuscam a informacao" como bordas grossas).
     */
    void secao(Document doc, Font fSecao, String texto) throws DocumentException {
        Paragraph titulo = new Paragraph(texto, fSecao);
        titulo.setSpacingBefore(14);
        titulo.setSpacingAfter(2);
        doc.add(titulo);

        PdfPTable filete = new PdfPTable(1);
        filete.setWidthPercentage(100);
        filete.setSpacingAfter(6);
        PdfPCell c = new PdfPCell(new Phrase(" ", FontFactory.getFont(FontFactory.HELVETICA, 2)));
        c.setBorderWidthBottom(1.5f);
        c.setBorderWidthTop(0);
        c.setBorderWidthLeft(0);
        c.setBorderWidthRight(0);
        c.setBorderColorBottom(AZUL);
        c.setPadding(0);
        filete.addCell(c);
        doc.add(filete);
    }

    PdfPTable tabelaDados() {
        PdfPTable t = new PdfPTable(new float[]{3, 7});
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        return t;
    }

    void linha(PdfPTable t, String rotulo, String valor) {
        Font fr = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, CINZA);
        Font fv = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
        PdfPCell c1 = new PdfPCell(new Phrase(rotulo, fr));
        PdfPCell c2 = new PdfPCell(new Phrase(valor, fv));
        for (PdfPCell c : new PdfPCell[]{c1, c2}) {
            c.setPadding(4);
            c.setBorderColor(CINZA_BORDA);
        }
        t.addCell(c1);
        t.addCell(c2);
    }

    /**
     * Linha rotulo:valor com destaque visual - usada para o dado mais
     * importante de uma secao (hoje so o resultado da decisao, secao "3.
     * Decisao final" do sumario). Fonte maior + negrito + CAIXA ALTA
     * garantem que a informacao sobreviva a impressao em preto-e-branco; a
     * cor semantica ({@code cor}) e reforco visual, nunca o unico portador
     * do dado (mesmo principio usado na capa, {@link #gerarCapa}).
     */
    void linhaDestaque(PdfPTable t, String rotulo, String valor, Color cor) {
        Font fr = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, CINZA);
        Font fv = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, cor);
        PdfPCell c1 = new PdfPCell(new Phrase(rotulo, fr));
        c1.setPadding(6);
        c1.setBorderColor(CINZA_BORDA);
        c1.setVerticalAlignment(Element.ALIGN_MIDDLE);
        PdfPCell c2 = new PdfPCell(new Phrase(valor.toUpperCase(), fv));
        c2.setPadding(6);
        c2.setBorderColor(CINZA_BORDA);
        c2.setVerticalAlignment(Element.ALIGN_MIDDLE);
        t.addCell(c1);
        t.addCell(c2);
    }

    /** Fundo claro do cabecalho de tabela ({@code --rs-gray-100}), R4 do relatorio V2. */
    private static final Color CABECALHO_TABELA_FUNDO = new Color(0xF1, 0xF5, 0xF9);

    // Cabecalho de tabela CLARO (fundo --rs-gray-100 + texto preto + filete
    // AZUL de 1,5pt embaixo), no lugar do fundo AZUL chapado com texto branco
    // que o documento usava antes (R4 do relatorio V2) - mesmo raciocinio de
    // secao() acima: a faixa chapada e o elemento que mais contribui pra
    // saturacao de tinta da pagina (achado B7) e contraria o padrao ofício
    // (§5.1). O contraste preto-sobre-#F1F5F9 (19,17:1, Anexo B do relatorio
    // V2) folga MUITO mais que o branco-sobre-AZUL que ele substitui.
    void cabecalho(PdfPTable t, String... cols) {
        Font f = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK);
        for (String col : cols) {
            PdfPCell c = new PdfPCell(new Phrase(col, f));
            c.setBackgroundColor(CABECALHO_TABELA_FUNDO);
            c.setBorderWidthBottom(1.5f);
            c.setBorderColorBottom(AZUL);
            c.setBorderWidthTop(0.5f);
            c.setBorderColorTop(CINZA_BORDA);
            c.setBorderWidthLeft(0.5f);
            c.setBorderColorLeft(CINZA_BORDA);
            c.setBorderWidthRight(0.5f);
            c.setBorderColorRight(CINZA_BORDA);
            c.setPadding(5);
            t.addCell(c);
        }
        // Achado B2 do relatorio V2: sem isto, uma tabela que quebra entre
        // paginas termina a pagina com o cabecalho e ZERO linhas de dado, e a
        // pagina seguinte comeca com dado nenhum sem rotulo de coluna. Custo:
        // uma linha, aqui uma unica vez para as 3 tabelas que usam este helper
        // (pareceres, andamento, anexos).
        t.setHeaderRows(1);
    }

    // Corpo a 10pt (era 9pt) - R4 do relatorio V2: 9pt fica abaixo do minimo
    // de toda fonte tipografica/de acessibilidade consultada (Butterick
    // 10-12pt, Section508 11-12pt, UKAAF 12pt, padrao oficio 12pt) e o
    // proprio Oficio de Indeferimento do sistema (a regua de qualidade
    // estabelecida em 2026-08-04) ja usa 11pt. 10pt e o piso da faixa de
    // consenso, nao o alvo confortavel - mas subir mais exigiria reajustar
    // TODAS as proporcoes de coluna do zero, fora do escopo desta rodada.
    void celula(PdfPTable t, String texto, int align, boolean bold) {
        Font f = FontFactory.getFont(bold ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, 10, Color.BLACK);
        PdfPCell c = new PdfPCell(new Phrase(texto, f));
        c.setPadding(4);
        c.setHorizontalAlignment(align);
        c.setBorderColor(CINZA_BORDA);
        t.addCell(c);
    }

    static String nvl(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    /**
     * Traduz {@link ResultadoParecer} para texto acentuado, SEM tocar no
     * enum: {@code ResultadoParecer.getDescricao()} alimenta varios outros
     * servicos/relatorios/exportacoes e o `avaliador/lista.html` -
     * proibicao documentada no CLAUDE.md e no
     * RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md §10. Mesmo padrao ja usado
     * la (tradutor local via switch), aplicado aqui so para o texto que sai
     * no Relatorio Final.
     */
    static String descricaoResultado(ResultadoParecer r) {
        if (r == null) {
            return null;
        }
        return switch (r) {
            case FAVORAVEL -> "Favorável";
            case NAO_FAVORAVEL -> "Não favorável";
            case SOLICITA_INFORMACAO -> "Solicita informação";
            case SEM_RESPOSTA -> "Sem resposta";
        };
    }

    /**
     * Traduz {@link StatusProcesso} para texto acentuado, mesmo raciocinio de
     * {@link #descricaoResultado} - {@code StatusProcesso.getDescricao()} nao
     * e acentuado porque alimenta badges/telas onde uma mudanca teria raio de
     * impacto maior; aqui e so o texto impresso nos relatorios PDF
     * (Relatorio Final, Relatorio Anual, Relatorio do Avaliador). Reusado por
     * {@link RelatorioAnualService}/{@link RelatorioAvaliadorService} desde
     * 2026-08-07.
     */
    static String descricaoStatus(StatusProcesso s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case SOLICITADO -> "Solicitado";
            case ENVIADO -> "Enviado";
            case SOLICITA_INFORMACAO -> "Solicita informação";
            case DEFERIDO -> "Deferido";
            case INDEFERIDO -> "Indeferido";
            case CANCELADO -> "Cancelado";
        };
    }

    /**
     * Traduz {@link TipoAnexo} para texto acentuado, mesmo raciocinio de
     * {@link #descricaoResultado} - so o texto impresso no Relatorio Final
     * muda; {@code TipoAnexo.getDescricao()} continua sem acento para quem
     * mais consome (badge de anexo em processos/detalhe.html e outros).
     */
    static String descricaoTipoAnexo(TipoAnexo t) {
        return switch (t) {
            case SOLICITACAO_AVALIADOR -> "Solicitação de avaliação gerada pelo sistema";
            case DOCUMENTO_CLINICO_AVALIADOR -> "Documento clínico anonimizado para os avaliadores";
            case DOCUMENTO_PORTAL_NAO_ANONIMIZADO ->
                "Documento do Portal do Solicitante pendente de anonimização (não vai aos avaliadores)";
            case DOCUMENTO_PACIENTE -> "Documento do paciente";
            case EMAIL_PARECER_RECEBIDO -> "Cópia do e-mail de parecer recebido do avaliador";
            case ANEXO_AVALIADOR -> "Documento anexado pelo avaliador junto ao parecer (Portal do Avaliador)";
            case INFO_COMPLEMENTAR -> "Pedido/resposta de informação complementar (solicitante)";
            case INFO_COMPLEMENTAR_AVALIADOR ->
                "Resposta à informação complementar revisada pelo operador (liberada aos avaliadores)";
            case OFICIO_INDEFERIMENTO -> "Ofício de indeferimento";
            // So urgencia renal comum tem esse anexo: paciente preemptivo nunca
            // tem COMPROVANTE_SNT (ProcessoService/ProcessoAnexoController recusam
            // o upload), entao este rotulo nunca renderiza num relatorio preemptivo.
            case COMPROVANTE_SNT -> "Comprovante de inserção da urgência renal no SNT";
            case EMAIL_RESPOSTA_SOLICITANTE -> "Cópia do e-mail de resposta ao solicitante";
            case COMPROVANTE_ENVIO_SOLICITANTE ->
                "Comprovante de envio da resposta ao solicitante (print/PDF do e-mail)";
            case RELATORIO_FINAL -> "Relatório final do processo";
            case OUTRO -> "Outro";
        };
    }
}

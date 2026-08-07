package br.gov.saude.sgpur.service;

import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfRectangle;
import com.lowagie.text.pdf.PdfStamper;
import com.lowagie.text.pdf.PdfString;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Padrao unico de cabecalho institucional para os documentos PDF oficiais do
 * sistema (Relatorio Final e Relatorio Anual): logo do RS + 2 linhas de texto
 * centralizadas + linha separadora + numeracao "Pagina X de Y", estampados em
 * TODAS as paginas do documento (inclusive a capa) via {@link PdfStamper}.
 *
 * <p>Aumenta o MediaBox de cada pagina no topo (em vez de sobrepor o conteudo
 * existente), entao o conteudo original fica sempre abaixo do cabecalho, sem
 * ser cortado - inclusive em paginas de anexos com tamanho diferente de A4.
 */
final class PdfCabecalhoStamper {

    /**
     * Nome institucional padrao, usado em TODOS os documentos oficiais
     * (Oficio, Relatorio Final, Relatorio Anual, carimbo do avaliador) - uma
     * unica fonte de verdade para evitar o que ja aconteceu uma vez: um
     * documento ficar com o nome do orgao desatualizado enquanto os outros
     * ja tinham sido corrigidos.
     */
    static final String NOME_INSTITUICAO = "Central de Transplantes do Estado do Rio Grande do Sul";

    /** Linha da secretaria, usada nas capas dos relatorios (Final e Anual). */
    static final String SECRETARIA = "SECRETARIA DE SAÚDE";

    /**
     * Nome do sistema, unico para os rodapes de TODOS os documentos PDF
     * oficiais (Relatorio Final, Relatorio Anual, Relatorio do Avaliador) -
     * mesmo raciocinio de {@link #NOME_INSTITUICAO}: antes desta constante,
     * o Relatorio Final dizia "SAUR - Sistema de Gestao de Processos de
     * Urgencia Renal" enquanto o Anual e o do Avaliador diziam "SAUR -
     * Sistema de Avaliacao de Urgencia Renal" (dois nomes para o mesmo
     * sistema, achado B8 de
     * docs/RELATORIO-REFORMULACAO-RELATORIO-FINAL-PDF-V2-2026-08.md).
     */
    static final String NOME_SISTEMA = "SAUR - Sistema de Gestão de Processos de Urgência Renal";

    /**
     * Codigo de idioma (RFC 5646) escrito no catalogo do documento
     * ({@code /Lang}) para os tres documentos que passam por
     * {@link #estampar} - atende a tecnica WCAG PDF16 (3.1.1 Language of
     * Page). Nivel 1 da Decisao 10 do relatorio V2 (§7.10): custo de duas
     * escritas no dicionario, zero efeito visual.
     */
    private static final String IDIOMA_DOCUMENTO = "pt-BR";

    private static final Logger log = LoggerFactory.getLogger(PdfCabecalhoStamper.class);

    private static final float MARGEM_ESQ = 40;
    private static final float MARGEM_DIR = 40;
    private static final float LOGO_TAMANHO = 33;
    private static final float LOGO_MARGEM = 36;

    /** Altura (pt) reservada no topo para o cabecalho completo (logo + 2 linhas + numeracao). */
    static final float ALTURA_CABECALHO = 55;

    /** Logo do RS em cache (carregado uma vez e reusado em todos os documentos). */
    private static volatile Image LOGO_CACHE;

    private static Image carregarLogo() {
        if (LOGO_CACHE != null) return LOGO_CACHE;
        try {
            byte[] logoBytes = PdfCabecalhoStamper.class.getClassLoader()
                .getResourceAsStream("static/brasao.png").readAllBytes();
            LOGO_CACHE = Image.getInstance(logoBytes);
        } catch (Exception e) {
            log.warn("Logo nao encontrado em static/brasao.png, cabecalho sem imagem");
        }
        return LOGO_CACHE;
    }

    private PdfCabecalhoStamper() {
    }

    /**
     * Trunca {@code texto} (com reticencias) ate caber em {@code larguraMax}
     * pontos na fonte/tamanho informados. showTextAligned nao faz wrap nem
     * clipping - sem isso, um texto mais longo que o esperado (nome de
     * equipe/processo de um registro legado, por exemplo) desenha para fora
     * dos limites laterais da pagina em vez de ser cortado com aviso visual.
     * Retorna o texto original se ja couber.
     */
    static String truncarParaLargura(BaseFont bf, float tamanhoFonte, String texto, float larguraMax) {
        if (texto == null || texto.isEmpty()) {
            return texto;
        }
        if (bf.getWidthPoint(texto, tamanhoFonte) <= larguraMax) {
            return texto;
        }
        String reticencias = "...";
        float larguraReticencias = bf.getWidthPoint(reticencias, tamanhoFonte);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texto.length(); i++) {
            String candidato = sb.toString() + texto.charAt(i);
            if (bf.getWidthPoint(candidato, tamanhoFonte) + larguraReticencias > larguraMax) {
                break;
            }
            sb.append(texto.charAt(i));
        }
        return sb.toString().stripTrailing() + reticencias;
    }

    /**
     * Area onde o cabecalho deve ser desenhado, ja em coordenadas VISUAIS (as
     * da pagina como o leitor de PDF a exibe, com a rotacao {@code /Rotate}
     * aplicada e a origem no canto inferior esquerdo do que se ve na tela).
     *
     * <p>{@link #aplicarEm(PdfContentByte)} instala no content byte a matriz
     * que converte essas coordenadas visuais para o espaco do usuario da
     * pagina - por isso o stamper precisa ser criado por
     * {@link #novoStamper}, que desliga o {@code rotateContents} do OpenPDF
     * (senao a rotacao seria aplicada duas vezes).
     */
    record AreaCarimbo(float largura, float altura,
                       float a, float b, float c, float d, float e, float f) {
        void aplicarEm(PdfContentByte over) {
            over.concatCTM(a, b, c, d, e, f);
        }
    }

    /**
     * Cria o {@link PdfStamper} usado para carimbar cabecalhos. Desliga o
     * {@code rotateContents} (que o OpenPDF liga por padrao) porque o
     * posicionamento do cabecalho e feito com a matriz devolvida por
     * {@link #expandirTopo} - que, ao contrario da rotacao automatica do
     * OpenPDF, tambem respeita MediaBox com origem diferente de (0,0).
     */
    static PdfStamper novoStamper(PdfReader reader, OutputStream out)
            throws com.lowagie.text.DocumentException, java.io.IOException {
        PdfStamper stamper = new PdfStamper(reader, out);
        stamper.setRotateContents(false);
        return stamper;
    }

    /**
     * Expande o MediaBox/CropBox de UMA pagina de {@code reader} em
     * {@code alturaExtra} pontos no TOPO, deslocando o conteudo original para
     * baixo (em vez de desenhar por cima dele). Devolve a {@link AreaCarimbo}
     * (dimensoes visuais + matriz de rotacao) para quem for desenhar no
     * over-content saber onde posicionar o cabecalho.
     *
     * <p>Compartilhado por quem precisa carimbar um cabecalho sem arriscar
     * cobrir o conteudo original de paginas com pouca ou nenhuma margem
     * superior (ex.: documentos clinicos escaneados) - usado tanto por
     * {@link #estampar} quanto por
     * {@link SolicitacaoAvaliadorService#carimbarCabecalho}.
     *
     * <p><b>Paginas rotacionadas:</b> {@code getPageSize} devolve o MediaBox
     * <i>sem</i> aplicar {@code /Rotate}, entao expandir sempre o topo do
     * MediaBox punha o espaco extra na LATERAL da pagina exibida (e o carimbo
     * fora da area visivel) em documentos escaneados em paisagem - que os
     * scanners costumam gravar como retrato + {@code /Rotate 90}. Aqui a
     * borda expandida e escolhida conforme a rotacao (90 -> esquerda,
     * 180 -> baixo, 270 -> direita, 0 -> topo), de forma que o espaco sempre
     * apareca no topo <i>visual</i>.
     *
     * <p>A caixa expandida preserva a origem do box original (MediaBox com
     * origem diferente de (0,0) nao e mais achatado para (0,0), o que cortava
     * conteudo) e parte do CropBox quando ele existe, que e o que o leitor de
     * PDF realmente exibe.
     */
    static AreaCarimbo expandirTopo(PdfReader reader, int pagina, float alturaExtra) {
        Rectangle box = reader.getBoxSize(pagina, "crop");
        if (box == null) {
            box = reader.getPageSize(pagina);
        }
        float x0 = box.getLeft();
        float y0 = box.getBottom();
        float x1 = box.getRight();
        float y1 = box.getTop();

        int rotacao = reader.getPageRotation(pagina);
        switch (rotacao) {
            case 90 -> x0 -= alturaExtra;
            case 180 -> y0 -= alturaExtra;
            case 270 -> x1 += alturaExtra;
            default -> y1 += alturaExtra;
        }

        PdfDictionary pageDict = reader.getPageN(pagina);
        PdfRectangle novoBox = new PdfRectangle(x0, y0, x1, y1);
        pageDict.put(PdfName.MEDIABOX, novoBox);
        pageDict.put(PdfName.CROPBOX, novoBox);

        float largura = x1 - x0;
        float altura = y1 - y0;
        return switch (rotacao) {
            case 90 -> new AreaCarimbo(altura, largura, 0, 1, -1, 0, x1, y0);
            case 180 -> new AreaCarimbo(largura, altura, -1, 0, 0, -1, x1, y1);
            case 270 -> new AreaCarimbo(altura, largura, 0, -1, 1, 0, x0, y1);
            default -> new AreaCarimbo(largura, altura, 1, 0, 0, 1, x0, y0);
        };
    }

    /**
     * Remove do PDF resultante TODOS os metadados herdados do arquivo de
     * origem (dicionario {@code /Info} e pacote XMP), deixando apenas o
     * {@code Title} informado.
     *
     * <p><b>Por que:</b> o {@link PdfStamper} preserva o {@code /Info} e o XMP
     * do PDF original. Sistemas hospitalares gravam rotineiramente o nome do
     * paciente em {@code Title}/{@code Author}/{@code Subject}, e o navegador
     * exibe o {@code Title} no rotulo da aba ao abrir o PDF inline - ou seja,
     * o material "anonimizado" enviado aos avaliadores entregava o nome
     * completo do paciente sem ninguem perceber, quebrando a regra de
     * imparcialidade. A limpeza e feita no ponto final (na hora de escrever o
     * PDF carimbado), para que nenhum caminho de montagem escape dela.
     *
     * <p>Nao basta zerar as chaves conhecidas: o {@code moreInfo} do OpenPDF e
     * MESCLADO com o {@code /Info} original, e chaves customizadas (ex.:
     * {@code /PatientName}) sobreviveriam. Por isso todas as chaves presentes
     * no original sao explicitamente removidas (valor {@code null}).
     *
     * <p><b>{@code setInfoDictionary}, nao o {@code setMoreInfo} deprecado
     * (OpenPDF 1.3.34):</b> decompilado o {@code .jar}, os dois fazem
     * exatamente a mesma atribuicao (o corpo de {@code setMoreInfo} e um
     * {@code putfield moreInfo} seguido de {@code setInfoDictionary(meta)}); a
     * unica diferenca e {@code setMoreInfo} tambem ligar a flag interna
     * {@code cleanMetadata}, que so importa quando o XMP e gerado
     * automaticamente a partir do {@code /Info} no {@code close()} do
     * stamper - e aqui o XMP e sempre setado explicitamente na linha de baixo
     * ({@link #xmpNeutro}), entao essa flag nunca chega a ser lida. Comparado
     * antes/depois com um PDF de teste "envenenado" (Title/Author/Subject com
     * o nome do paciente): {@code /Info} e XMP do resultado identicos byte a
     * byte nas duas versoes.
     *
     * @param titulo texto seguro (sem nome completo de paciente) para o
     *               {@code Title} do PDF resultante
     */
    static void anonimizarMetadados(PdfReader reader, PdfStamper stamper, String titulo) {
        HashMap<String, String> info = new HashMap<>();
        for (String chave : reader.getInfo().keySet()) {
            info.put(chave, null);
        }
        for (String chave : CHAVES_INFO_CONHECIDAS) {
            info.put(chave, null);
        }
        info.put("Title", titulo == null ? "" : titulo);
        info.put("Producer", NOME_INSTITUICAO);
        stamper.setInfoDictionary(info);
        stamper.setXmpMetadata(xmpNeutro(titulo));
    }

    /** Chaves padrao do {@code /Info}, zeradas mesmo se ausentes no original. */
    private static final String[] CHAVES_INFO_CONHECIDAS = {
        "Title", "Author", "Subject", "Keywords", "Creator", "Producer", "Trapped"
    };

    /**
     * Pacote XMP minimo, so com o titulo seguro - substitui o XMP do PDF de
     * origem (que costuma repetir o {@code dc:title} com o nome do paciente).
     * Um pacote vazio nao serve: o OpenPDF tenta interpreta-lo ao fechar o
     * stamper e loga erro de XML malformado.
     */
    private static byte[] xmpNeutro(String titulo) {
        String seguro = escaparXml(titulo == null ? "" : titulo);
        String xmp = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
            + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
            + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
            + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">" + seguro + "</rdf:li></rdf:Alt></dc:title>\n"
            + "</rdf:Description>\n"
            + "</rdf:RDF>\n"
            + "</x:xmpmeta>\n"
            + "<?xpacket end=\"w\"?>";
        return xmp.getBytes(StandardCharsets.UTF_8);
    }

    private static String escaparXml(String s) {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    /**
     * Escreve {@code /Lang} no catalogo do documento e liga
     * {@code DisplayDocTitle} nas preferencias do visualizador - as duas
     * pecas "baratas" de acessibilidade (nivel 1 da Decisao 10 do relatorio
     * V2), sem nenhum efeito no layout. O {@code Title} em si ja existe
     * (escrito por {@link #anonimizarMetadados}); aqui so se garante que o
     * leitor de PDF o exiba no lugar do nome do arquivo (tecnica WCAG PDF18)
     * e que o idioma do documento seja declarado (tecnica WCAG PDF16).
     */
    private static void aplicarAcessibilidadeBasica(PdfReader reader, PdfStamper stamper) {
        PdfDictionary catalog = reader.getCatalog();
        catalog.put(PdfName.LANG, new PdfString(IDIOMA_DOCUMENTO));
        stamper.markUsed(catalog);
        stamper.setViewerPreferences(PdfWriter.DisplayDocTitle);
    }

    /**
     * Estampa o cabecalho institucional padrao em todas as paginas de
     * {@code pdf}.
     *
     * @param pdf    PDF de entrada (bytes)
     * @param linha1 primeira linha do cabecalho (institucional, em negrito)
     * @param linha2 segunda linha do cabecalho (identificacao do documento)
     */
    static byte[] estampar(byte[] pdf, String linha1, String linha2) {
        return estampar(pdf, linha1, linha2, 1);
    }

    /**
     * Igual a {@link #estampar(byte[], String, String)}, mas deixa as
     * primeiras {@code primeiraPagina - 1} paginas SEM carimbo (sem cabecalho,
     * sem numeracao e sem a expansao de {@link #ALTURA_CABECALHO} no topo).
     *
     * <p>Existe por causa da CAPA do Relatorio Final (reintroduzida em
     * 2026-08-07, ver {@code PdfRelatorioBuilder.gerarCapa}): a capa ja tem o
     * brasao e o nome do orgao em tamanho grande no proprio corpo, entao
     * carimba-la produziria dois brasoes na mesma pagina - exatamente um dos
     * defeitos que motivaram a remocao da capa antiga. Capa tambem nao leva
     * numero de pagina, como e usual em documento oficial.
     *
     * <p>Como a expansao do topo tambem e pulada nessas paginas, elas devem
     * ser criadas ja em A4 cheio (as demais nascem
     * {@link #ALTURA_CABECALHO} mais baixas e voltam a A4 aqui) - do
     * contrario o documento final teria paginas de dois tamanhos.
     *
     * @param primeiraPagina numero (base 1) da primeira pagina a carimbar
     */
    static byte[] estampar(byte[] pdf, String linha1, String linha2, int primeiraPagina) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PdfReader reader = new PdfReader(pdf)) {
            PdfStamper stamper = novoStamper(reader, baos);
            anonimizarMetadados(reader, stamper, linha2);
            aplicarAcessibilidadeBasica(reader, stamper);

            Image logo = carregarLogo();

            BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            int totalPaginas = reader.getNumberOfPages();

            for (int i = 1; i <= totalPaginas; i++) {
                if (i < primeiraPagina) {
                    continue;
                }
                AreaCarimbo area = expandirTopo(reader, i, ALTURA_CABECALHO);
                float largura = area.largura();
                float topo = area.altura();
                float largUtil = largura - MARGEM_ESQ - MARGEM_DIR;

                PdfContentByte over = stamper.getOverContent(i);
                over.saveState();
                area.aplicarEm(over);

                if (logo != null) {
                    Image img = Image.getInstance(logo);
                    img.setAbsolutePosition(MARGEM_ESQ, topo - LOGO_MARGEM);
                    img.scaleToFit(LOGO_TAMANHO, LOGO_TAMANHO);
                    over.addImage(img);
                }

                float textoX = MARGEM_ESQ + LOGO_TAMANHO + 6;
                float textoLarg = largUtil - LOGO_TAMANHO - 6;
                String linha1Truncada = truncarParaLargura(bf, 10, linha1, textoLarg);
                String linha2Truncada = truncarParaLargura(bf, 10, linha2, textoLarg);

                over.beginText();
                over.setFontAndSize(bf, 10);
                over.showTextAligned(Element.ALIGN_CENTER, linha1Truncada,
                    textoX + textoLarg / 2, topo - 20, 0);
                over.setFontAndSize(bf, 10);
                over.showTextAligned(Element.ALIGN_CENTER, linha2Truncada,
                    textoX + textoLarg / 2, topo - 35, 0);
                over.endText();

                over.setLineWidth(0.5f);
                over.setColorStroke(new Color(180, 180, 180));
                over.moveTo(MARGEM_ESQ, topo - 44);
                over.lineTo(largura - MARGEM_DIR, topo - 44);
                over.stroke();

                over.beginText();
                over.setFontAndSize(bf, 9);
                over.showTextAligned(Element.ALIGN_RIGHT,
                    "Página " + i + " de " + totalPaginas,
                    largura - MARGEM_DIR, 22, 0);
                over.endText();

                over.restoreState();
            }

            stamper.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao estampar cabecalho do PDF", e);
        }
    }
}

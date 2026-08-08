package br.gov.saude.sgpur.service;

import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfIndirectReference;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfRectangle;
import com.lowagie.text.pdf.PdfStamper;
import com.lowagie.text.pdf.PdfStream;
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
        byte[] carimbado = carimbarPaginas(pdf, linha1, linha2, primeiraPagina);
        return corrigirToUnicodeDeFontesSimples(carimbado);
    }

    /**
     * Faz o trabalho visual de {@link #estampar} (logo, 2 linhas, regua,
     * numeracao de pagina) - extraido para um metodo proprio para que
     * {@link #estampar} possa aplicar {@link #corrigirToUnicodeDeFontesSimples}
     * como ultimo passo, depois que TODAS as fontes do documento (as do corpo
     * original e as novas criadas aqui para o carimbo) ja existem no PDF.
     */
    private static byte[] carimbarPaginas(byte[] pdf, String linha1, String linha2, int primeiraPagina) {
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

    // -----------------------------------------------------------------------
    // Correcao de extracao de texto (ToUnicode CMap ausente) - 2026-08-08
    // -----------------------------------------------------------------------

    /**
     * Tabela byte -&gt; Unicode IDENTICA a {@code com.lowagie.text.pdf.PdfEncodings
     * .winansiByteToChar} (pacote-privada, por isso copiada aqui, e nao
     * importada). E a tabela que o proprio OpenPDF usa para CONVERTER os
     * caracteres Java para bytes ao escrever o conteudo de qualquer fonte
     * criada com {@code BaseFont.WINANSI} ou {@code BaseFont.CP1252} - as duas
     * constantes sao, na pratica, a MESMA string {@code "Cp1252"}
     * ({@code BaseFont.java}, OpenPDF 1.3.34) - e tambem o encoding padrao de
     * {@code com.lowagie.text.FontFactory} ({@code defaultEncoding =
     * BaseFont.WINANSI}), usado em toda chamada {@code FontFactory.getFont(...)}
     * deste sistema. Usar a mesma tabela nos dois sentidos (Java-&gt;byte na
     * escrita, byte-&gt;Unicode aqui) garante round-trip exato: o byte que o
     * sistema grava para 'ç' (0xE7) volta a ser lido como 'ç', nunca uma
     * aproximacao. O valor 65533 (0xFFFD) marca posicoes sem glifo definido no
     * WinAnsiEncoding (Anexo D da especificacao PDF) - ficam de fora do CMap.
     */
    private static final char[] WINANSI_BYTE_PARA_UNICODE = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
        11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
        28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44,
        45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61,
        62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78,
        79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95,
        96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109,
        110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122,
        123, 124, 125, 126, 127, 8364, 65533, 8218, 402, 8222, 8230, 8224,
        8225, 710, 8240, 352, 8249, 338, 65533, 381, 65533, 65533, 8216,
        8217, 8220, 8221, 8226, 8211, 8212, 732, 8482, 353, 8250, 339,
        65533, 382, 376, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169,
        170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182,
        183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195,
        196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208,
        209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221,
        222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234,
        235, 236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247,
        248, 249, 250, 251, 252, 253, 254, 255
    };

    /** Marca de "sem glifo definido" na tabela acima (nunca vira entrada do CMap). */
    private static final char WINANSI_INDEFINIDO = 65533;

    /**
     * Corrige um defeito real do OpenPDF/iText: uma fonte Type1 padrao
     * (Helvetica, a unica familia usada nos documentos deste sistema) NAO
     * embutida nunca ganha um {@code /ToUnicode} CMap, porque o proprio
     * renderizador de PDF nao precisa dele para desenhar o glifo certo (a
     * codificacao {@code WinAnsiEncoding} basta). Isso e invisivel a olho nu -
     * o RENDER VISUAL do PDF fica perfeito - mas quebra a EXTRACAO de texto
     * (copiar/colar, Ctrl+F, leitor de tela e qualquer ferramenta que
     * dependa do {@code ToUnicode} em vez de reconstruir o mapeamento a
     * partir do nome do glifo): confirmado gerando os 3 relatorios do sistema
     * e extraindo o texto com {@code pypdf}/{@code PyMuPDF} antes desta
     * correcao - toda letra acentuada saia como {@code U+FFFD}
     * ("Concei��o" no lugar de "Conceição"). Ver CLAUDE.md
     * ("Extracao de texto em PDF" / 2026-08-08) para o antes/depois completo.
     *
     * <p>A correcao injeta manualmente um {@code /ToUnicode} CMap (formato
     * padrao da secao 9.10.3 da especificacao PDF) em toda fonte
     * {@code /Type1} simples do documento JA CARIMBADO (corpo original +
     * cabecalho/numeracao desenhados por {@link #carimbarPaginas}) que ainda
     * nao tenha um, usando {@link #WINANSI_BYTE_PARA_UNICODE} - a MESMA
     * tabela que o OpenPDF usa para escrever os bytes originalmente, ver seu
     * javadoc para o porque disso garantir round-trip exato. So mexe em
     * fontes {@code WinAnsiEncoding} (as unicas que este sistema cria) - PDFs
     * de terceiros com fontes embutidas/estrutura diferente (documentos
     * clinicos anexados, por exemplo) nao entram aqui: essa funcao roda
     * DEPOIS que o corpo do documento ja foi fundido/carimbado, mas o padrao
     * de fonte que ela procura (Type1 nao-embutida, WinAnsiEncoding, sem
     * ToUnicode) so bate com o que o PROPRIO sistema gera - uma fonte
     * embutida de terceiro tem {@code /FontFile}/{@code /FontFile2} e
     * normalmente ja chega com seu proprio {@code ToUnicode} ou uma estrutura
     * de fonte composta (Type0/CID), nenhuma das duas casando com o filtro
     * abaixo.
     *
     * <p>Roda como um SEGUNDO passe de leitura/gravacao (reader+stamper
     * novos, sobre os bytes ja carimbados) de proposito: as fontes do
     * cabecalho/numeracao de pagina so passam a existir como objetos do PDF
     * depois que {@link #carimbarPaginas} fecha o primeiro {@code PdfStamper}
     * - rodar a correcao ANTES disso deixaria "Página X de Y" (que tem
     * acento) de fora.
     *
     * <p>Reafirma o {@code /Producer} explicitamente (mesmo valor que
     * {@link #anonimizarMetadados} ja tinha gravado no primeiro passe) porque
     * TODO {@code PdfStamper} do OpenPDF, por padrao, ANEXA
     * {@code "; modified using OpenPDF X.Y.Z"} ao {@code /Producer} existente
     * ao fechar - util para rastrear que um PDF foi alterado por uma
     * ferramenta externa, mas nao faz sentido aqui: da perspectiva de quem
     * abre o documento este SEGUNDO passe nao e uma alteracao de terceiro, e
     * a mesma geracao do sistema. Sem isso o texto institucional do
     * {@code /Producer} (usado por
     * {@code PdfCabecalhoStamperTest.estamparRemoveNomeDoPacienteDeTodasAsChavesDoInfo}/
     * {@code estamparMantemProducerInstitucionalMesmoSemMetadadosDeOrigem})
     * ficaria com esse sufixo tecnico grudado.
     */
    private static byte[] corrigirToUnicodeDeFontesSimples(byte[] pdf) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PdfReader reader = new PdfReader(pdf)) {
            PdfStamper stamper = new PdfStamper(reader, baos);
            stamper.setInfoDictionary(java.util.Map.of("Producer", NOME_INSTITUICAO));
            int totalObjetos = reader.getXrefSize();
            for (int i = 1; i < totalObjetos; i++) {
                PdfObject obj = reader.getPdfObject(i);
                if (!(obj instanceof PdfDictionary dict)) {
                    continue;
                }
                if (!PdfName.FONT.equals(dict.get(PdfName.TYPE))
                        || !PdfName.TYPE1.equals(dict.get(PdfName.SUBTYPE))
                        || dict.get(PdfName.TOUNICODE) != null) {
                    continue;
                }
                PdfObject encoding = dict.get(PdfName.ENCODING);
                if (!(encoding instanceof PdfName) || !PdfName.WIN_ANSI_ENCODING.equals(encoding)) {
                    continue;
                }
                PdfIndirectReference toUnicodeRef = reader.addPdfObject(construirCMapWinAnsi());
                dict.put(PdfName.TOUNICODE, toUnicodeRef);
                stamper.markUsed(dict);
            }
            stamper.close();
            return baos.toByteArray();
        } catch (Exception e) {
            // Best-effort: uma falha aqui nao pode derrubar a geracao do
            // relatorio - o pior caso e devolver o PDF sem a correcao
            // (mesmo defeito de extracao de antes desta mudanca), nao um erro
            // 500 para quem so queria baixar o documento.
            log.warn("Falha ao corrigir o ToUnicode das fontes do PDF - a extracao de texto "
                + "pode ficar sem acentuacao: {}", e.getMessage());
            return pdf;
        }
    }

    /**
     * Monta o stream do {@code /ToUnicode} CMap (formato da secao 9.10.3 da
     * especificacao PDF) cobrindo os 256 bytes de {@link #WINANSI_BYTE_PARA_UNICODE}
     * (exceto os marcados {@link #WINANSI_INDEFINIDO}), em blocos de no
     * maximo 100 entradas por secao {@code beginbfchar}/{@code endbfchar} -
     * limite da propria especificacao (9.7.5.2).
     */
    private static PdfStream construirCMapWinAnsi() {
        StringBuilder sb = new StringBuilder();
        sb.append("/CIDInit /ProcSet findresource begin\n")
          .append("12 dict begin\n")
          .append("begincmap\n")
          .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
          .append("/CMapName /Adobe-Identity-UCS def\n")
          .append("/CMapType 2 def\n")
          .append("1 begincodespacerange\n<00> <FF>\nendcodespacerange\n");

        final int tamanhoBloco = 100;
        for (int inicio = 0; inicio < WINANSI_BYTE_PARA_UNICODE.length; inicio += tamanhoBloco) {
            int fim = Math.min(inicio + tamanhoBloco, WINANSI_BYTE_PARA_UNICODE.length);
            StringBuilder bloco = new StringBuilder();
            int entradas = 0;
            for (int b = inicio; b < fim; b++) {
                char unicode = WINANSI_BYTE_PARA_UNICODE[b];
                if (unicode == WINANSI_INDEFINIDO) {
                    continue;
                }
                bloco.append(String.format("<%02X> <%04X>\n", b, (int) unicode));
                entradas++;
            }
            if (entradas > 0) {
                sb.append(entradas).append(" beginbfchar\n").append(bloco).append("endbfchar\n");
            }
        }

        sb.append("endcmap\n")
          .append("CMapName currentdict /CMap defineresource pop\n")
          .append("end\n")
          .append("end\n");

        return new PdfStream(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }
}

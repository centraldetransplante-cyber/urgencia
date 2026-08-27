package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.config.EmailProperties;
import br.gov.saude.sgpur.domain.Processo;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Gera o Oficio de Indeferimento em PDF (documento formal enviado a equipe
 * solicitante e disponibilizado no Portal do Solicitante). Modelo com brasao,
 * cabecalho institucional, numero proprio do oficio, local e data por extenso,
 * destinatario, motivo e fecho com a assinatura configurada.
 *
 * <p><b>Nada de placeholder literal aqui</b> (corrigido em 2026-08-04): a
 * cidade e a assinatura vem de configuracao ({@code sgpur.email.oficio-cidade}
 * e {@code sgpur.email.assinatura}, ambas em {@link EmailProperties}), nunca
 * das strings "Local," / "Responsavel" que sairam impressas no documento
 * oficial ate esta data.</p>
 *
 * <p><b>Acentuacao:</b> este texto e o unico do sistema que sai da instituicao
 * como documento formal, entao usa acentuacao correta - diferente de
 * {@code ResultadoParecer.descricao} e afins, deliberadamente sem acento por
 * alimentarem varios relatorios/exportacoes internas.</p>
 */
@Service
public class OficioService {

    private static final Logger log = LoggerFactory.getLogger(OficioService.class);

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    /** Exibido no lugar do numero do oficio em processos anteriores a numeracao propria. */
    static final String NUMERO_NAO_ATRIBUIDO = "(número não atribuído)";

    private final EmailProperties emailProperties;

    public OficioService(EmailProperties emailProperties) {
        this.emailProperties = emailProperties;
    }

    public byte[] gerar(Processo p) {
        Document doc = new Document(PageSize.A4, 56, 56, 56, 56);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font fCab = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.BLACK);
            Font fCabSub = FontFactory.getFont(FontFactory.HELVETICA, 10, new Color(80, 80, 80));
            Font fTitulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.BLACK);
            Font fCorpo = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.BLACK);

            // Brasao institucional (mesmo tratamento de PdfRelatorioBuilder:
            // se o recurso sumir do classpath, loga e segue sem imagem - a
            // geracao do oficio nunca pode falhar por causa do timbre).
            adicionarBrasao(doc);

            // Cabecalho institucional
            Paragraph cab = new Paragraph(PdfCabecalhoStamper.NOME_INSTITUICAO, fCab);
            cab.setAlignment(Element.ALIGN_CENTER);
            doc.add(cab);
            Paragraph cab2 = new Paragraph(
                p.isPreemptivo() ? "INSERÇÃO EM LISTA DE ESPERA RENAL" : "URGÊNCIA RENAL", fCabSub);
            cab2.setAlignment(Element.ALIGN_CENTER);
            cab2.setSpacingAfter(24);
            doc.add(cab2);

            // Numero do oficio e data
            LocalDate dataEmissao = p.getDataEmissaoOficio() != null ? p.getDataEmissaoOficio() : LocalDate.now();
            String numeroOficio = (p.getNumeroOficio() == null || p.getNumeroOficio().isBlank())
                ? NUMERO_NAO_ATRIBUIDO : p.getNumeroOficio();
            Paragraph titulo = new Paragraph(
                "Ofício nº " + numeroOficio + " - INDEFERIMENTO - " + p.identificacao(), fTitulo);
            titulo.setSpacingAfter(6);
            doc.add(titulo);

            String mes = dataEmissao.getMonth().getDisplayName(TextStyle.FULL, PT_BR);
            Paragraph local = new Paragraph(
                cidade() + ", " + dataEmissao.getDayOfMonth() + " de " + mes
                    + " de " + dataEmissao.getYear() + ".",
                fCorpo);
            local.setAlignment(Element.ALIGN_RIGHT);
            local.setSpacingAfter(18);
            doc.add(local);

            // Destinatario
            Paragraph dest = new Paragraph("Ao(À) solicitante: " + p.getSolicitanteEquipe(), fCorpo);
            dest.setSpacingAfter(12);
            doc.add(dest);

            // Corpo
            String motivo = (p.getMotivoIndeferimento() == null || p.getMotivoIndeferimento().isBlank())
                ? "(motivo não informado)" : p.getMotivoIndeferimento();
            String texto = """
                Prezado(a) Senhor(a),

                Em referência ao %s n. %s, referente ao(à) paciente \
                %s, comunicamos que, após análise dos pareceres da equipe de Urgência Renal, \
                o pedido foi INDEFERIDO.

                Motivo do indeferimento: %s

                Permanecemos à disposição para os esclarecimentos que se fizerem necessários.
                """.formatted(RotuloProcesso.nomeLongo(p),
                    p.getNumero(), p.getPacienteNome(), motivo);
            Paragraph corpo = new Paragraph(texto, fCorpo);
            corpo.setAlignment(Element.ALIGN_JUSTIFIED);
            corpo.setSpacingAfter(28);
            doc.add(corpo);

            // Fecho / assinatura (configuravel: sgpur.email.assinatura)
            Paragraph fecho = new Paragraph("Atenciosamente,", fCorpo);
            fecho.setSpacingAfter(36);
            doc.add(fecho);

            Paragraph assinatura = new Paragraph("____________________________________\n"
                + emailProperties.getAssinatura(), fCorpo);
            assinatura.setAlignment(Element.ALIGN_CENTER);
            doc.add(assinatura);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Falha ao gerar o oficio de indeferimento", e);
        }
    }

    /**
     * Gera o oficio como RASCUNHO EDITAVEL (RTF), para o operador abrir no
     * Word, ajustar o texto e anexar o documento final assinado.
     *
     * <p><b>Por que RTF e nao PDF/DOCX:</b> o PDF do sistema nao e editavel, e
     * o oficio precisa ser (decisao do usuario, 2026-08-04) - cada caso tem
     * particularidade de redacao que nenhum modelo fixo cobre. O DOCX exigiria
     * uma biblioteca nova (POI/docx4j) so para isso; o RTF e texto puro, abre
     * nativamente no Word/LibreOffice e nao adiciona dependencia nenhuma.
     *
     * <p><b>O rascunho NUNCA vira anexo do processo.</b> Ele e so um ponto de
     * partida para download; o documento de registro e sempre o arquivo que o
     * operador anexa depois ({@code POST /processos/{id}/oficio-upload}), e a
     * data de emissao e o momento desse anexo (ver
     * {@code ProcessoService.registrarDataEmissaoOficio}).
     *
     * <p>A estrutura segue o modelo real da Central de Transplantes usado como
     * referencia (cabecalho do departamento, numero do oficio, cidade e data
     * por extenso, vocativo, corpo, fecho, assinatura e bloco do destinatario).
     * O timbre/brasao vem do modelo do Word da propria equipe - o RTF entrega
     * o texto, nao a identidade visual.
     */
    public byte[] gerarRascunhoRtf(Processo p) {
        LocalDate dataEmissao = p.getDataEmissaoOficio() != null
            ? p.getDataEmissaoOficio() : LocalDate.now();
        String numeroOficio = (p.getNumeroOficio() == null || p.getNumeroOficio().isBlank())
            ? NUMERO_NAO_ATRIBUIDO : p.getNumeroOficio();
        String motivo = (p.getMotivoIndeferimento() == null || p.getMotivoIndeferimento().isBlank())
            ? "(motivo não informado)" : p.getMotivoIndeferimento();
        String mes = dataEmissao.getMonth().getDisplayName(TextStyle.FULL, PT_BR);
        String dataPorExtenso = cidade() + ", " + String.format("%02d", dataEmissao.getDayOfMonth())
            + " de " + mes + " de " + dataEmissao.getYear() + ".";

        StringBuilder rtf = new StringBuilder();
        rtf.append("{\\rtf1\\ansi\\ansicpg1252\\deff0")
           .append("{\\fonttbl{\\f0\\froman\\fcharset0 Times New Roman;}}")
           .append("\\viewkind4\\uc1\\pard\\f0\\fs24\n");
        centralizado(rtf, "Departamento de Regulação Estadual");
        centralizado(rtf, "Divisão de Transplantes");
        linha(rtf, "");
        linha(rtf, "");
        linha(rtf, "Ofício nº " + numeroOficio);
        linha(rtf, dataPorExtenso);
        linha(rtf, "");
        linha(rtf, "");
        linha(rtf, "Prezado(a) Senhor(a),");
        linha(rtf, "");
        linha(rtf, "Em referência ao " + RotuloProcesso.nomeLongo(p) + " n. " + p.getNumero()
            + ", referente ao(à) paciente " + p.getPacienteNome() + ", comunicamos que, após "
            + "análise dos pareceres da equipe de Urgência Renal, o pedido foi INDEFERIDO.");
        linha(rtf, "");
        linha(rtf, "Motivo do indeferimento: " + motivo);
        linha(rtf, "");
        linha(rtf, "Permanecemos à disposição para os esclarecimentos que se fizerem necessários.");
        linha(rtf, "");
        linha(rtf, "Atenciosamente.");
        linha(rtf, "");
        linha(rtf, "");
        centralizado(rtf, emailProperties.getAssinatura());
        linha(rtf, "");
        linha(rtf, "");
        linha(rtf, "À equipe solicitante");
        linha(rtf, p.getSolicitanteEquipe());
        rtf.append("}");
        return rtf.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static void linha(StringBuilder rtf, String texto) {
        rtf.append("\\pard\\ql ").append(escaparRtf(texto)).append("\\par\n");
    }

    private static void centralizado(StringBuilder rtf, String texto) {
        rtf.append("\\pard\\qc ").append(escaparRtf(texto)).append("\\par\n");
    }

    /**
     * Escapa o texto para RTF. As 3 chaves de controle ({@code \ { }}) tem de
     * virar sequencias escapadas, senao um motivo de indeferimento que contenha
     * uma chave corrompe o documento inteiro; e todo caractere fora do ASCII
     * (acentuacao do nome do paciente, do motivo) vira {@code \'hh}, a forma
     * que o Word entende no code page declarado no cabecalho.
     */
    static String escaparRtf(String texto) {
        if (texto == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : texto.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '{' -> sb.append("\\{");
                case '}' -> sb.append("\\}");
                case '\n' -> sb.append("\\par ");
                case '\r' -> { }
                default -> {
                    if (c < 128) {
                        sb.append(c);
                    } else if (c <= 255) {
                        sb.append(String.format("\\'%02x", (int) c));
                    } else {
                        sb.append("\\u").append((int) c).append("?");
                    }
                }
            }
        }
        return sb.toString();
    }

    private String cidade() {
        String cidade = emailProperties.getOficioCidade();
        return (cidade == null || cidade.isBlank()) ? "Porto Alegre" : cidade.trim();
    }

    private void adicionarBrasao(Document doc) {
        try {
            byte[] logoBytes = getClass().getClassLoader()
                .getResourceAsStream("static/brasao.png").readAllBytes();
            Image brasao = Image.getInstance(logoBytes);
            brasao.scaleToFit(80, 80);
            brasao.setAlignment(Element.ALIGN_CENTER);
            brasao.setSpacingAfter(8);
            doc.add(brasao);
        } catch (Exception e) {
            log.warn("Logo nao encontrado em static/brasao.png, oficio sem imagem");
        }
    }
}

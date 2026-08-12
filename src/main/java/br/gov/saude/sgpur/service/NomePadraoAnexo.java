package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.TipoAnexo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Gera o NOME PADRAO com que os anexos sao salvos (nome fisico em disco e nome
 * de exibicao/download), no formato:
 *
 * <pre>AAAA-MM-DD - CET-RS NN-AAAA - &lt;Tipo do documento&gt;[ (n)].&lt;ext&gt;</pre>
 *
 * Exemplo: {@code 2026-07-06 - CET-RS 37-2026 - Documento clinico.pdf}.
 *
 * <p>O nome NAO contem o nome do paciente (a identificacao do paciente fica
 * apenas na PASTA do processo), o que evita expor o nome completo em anexos
 * enviados aos avaliadores (regra de imparcialidade).
 */
public final class NomePadraoAnexo {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private NomePadraoAnexo() {
    }

    /** Rotulo curto e legivel do tipo de documento, usado no nome do arquivo. */
    public static String rotuloTipo(TipoAnexo tipo) {
        return switch (tipo) {
            case SOLICITACAO_AVALIADOR -> "Solicitacao avaliador";
            case DOCUMENTO_CLINICO_AVALIADOR -> "Documento clinico";
            case DOCUMENTO_PORTAL_NAO_ANONIMIZADO -> "Documento do portal (nao anonimizado)";
            case DOCUMENTO_PACIENTE -> "Documento paciente";
            case EMAIL_PARECER_RECEBIDO -> "Parecer recebido";
            case ANEXO_AVALIADOR -> "Anexo do avaliador";
            case INFO_COMPLEMENTAR -> "Informacao complementar";
            case INFO_COMPLEMENTAR_AVALIADOR -> "Informacao complementar (avaliadores)";
            case OFICIO_INDEFERIMENTO -> "Oficio indeferimento";
            case COMPROVANTE_SNT -> "Comprovante SNT";
            case EMAIL_RESPOSTA_SOLICITANTE -> "Resposta ao solicitante";
            case COMPROVANTE_ENVIO_SOLICITANTE -> "Comprovante envio solicitante";
            case RELATORIO_FINAL -> "Relatorio final";
            case OUTRO -> "Documento";
        };
    }

    /** Extensao (sem ponto, minuscula) derivada do nome original, ou "" se nao houver. */
    public static String extensao(String nomeOriginal) {
        if (nomeOriginal == null) {
            return "";
        }
        int i = nomeOriginal.lastIndexOf('.');
        if (i < 0 || i == nomeOriginal.length() - 1) {
            return "";
        }
        return nomeOriginal.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Nome padrao (sem tratar colisao) de um anexo, incluindo a extensao quando
     * conhecida. A unicidade dentro da pasta e responsabilidade de quem salva
     * (adicionando " (n)" antes da extensao se ja existir).
     */
    public static String gerar(Processo p, TipoAnexo tipo, String nomeOriginal, LocalDate data) {
        String numero = (p.getNumero() == null || p.getNumero().isBlank())
                ? "SN" : p.getNumero().replace("/", "-");
        String base = DATA.format(data) + " - CET-RS " + numero + " - " + rotuloTipo(tipo);
        String ext = extensao(nomeOriginal);
        return ext.isEmpty() ? base : base + "." + ext;
    }
}

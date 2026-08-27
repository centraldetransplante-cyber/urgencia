package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;

/**
 * Fonte UNICA de todo rotulo/texto que muda conforme o processo ser
 * "Urgencia Renal" (comum) ou "Preemptivo" (paciente ainda nao esta na lista
 * de espera do SNT - o processo avalia a INSERCAO na lista, nao uma
 * urgencia) - paciente preemptivo, 2026-08-27. Ver
 * docs/PLANO-PACIENTE-PREEMPTIVO-2026-08-27.md, secao 3.
 *
 * <p><b>Nunca espalhar {@code if (p.isPreemptivo()) "..." else "..."} pelos
 * templates/servicos/PDFs</b> - centralizar aqui, mesma familia de
 * {@link Iniciais}/{@link NomePadraoAnexo}/{@link ConflitoEquipeMatcher}.
 * Sao 4 PDFs institucionais + varios templates de e-mail + ~10 telas usando
 * este vocabulario; divergir um deles do resto e a razao de existir desta
 * classe.</p>
 *
 * <p><b>O que NAO muda mesmo com processo preemptivo</b> (decisao de
 * produto): titulos GERAIS/compartilhados da area do operador (Painel,
 * rodape do layout, navbar, titulo do Relatorio Anual) continuam dizendo
 * "Urgencia Renal" sempre - o rotulo novo so aparece dentro da tela/documento
 * ESPECIFICO de um processo preemptivo.</p>
 */
public final class RotuloProcesso {

    private RotuloProcesso() {
    }

    /** Badge curto em lista/detalhe: "Urgência Renal" | "Preemptivo". */
    public static String tipoCurto(boolean preemptivo) {
        return preemptivo ? "Preemptivo" : "Urgência Renal";
    }

    public static String tipoCurto(Processo p) {
        return tipoCurto(p != null && p.isPreemptivo());
    }

    public static String tipoCurto(SolicitacaoOnline s) {
        return tipoCurto(s != null && s.isPreemptivo());
    }

    /**
     * Nome longo do processo: "Processo de Urgência Renal" | "Processo de
     * Inserção em Lista de Espera Renal (Preemptivo)".
     */
    public static String nomeLongo(boolean preemptivo) {
        return preemptivo
            ? "Processo de Inserção em Lista de Espera Renal (Preemptivo)"
            : "Processo de Urgência Renal";
    }

    public static String nomeLongo(Processo p) {
        return nomeLongo(p != null && p.isPreemptivo());
    }

    /** Titulo de PDF em caixa alta, sem acento onde o documento exige (mesmo padrao ja usado hoje). */
    public static String tituloPdfCaixaAlta(boolean preemptivo) {
        return preemptivo
            ? "PROCESSO DE INSERÇÃO EM LISTA DE ESPERA RENAL"
            : "PROCESSO DE URGÊNCIA RENAL";
    }

    public static String tituloPdfCaixaAlta(Processo p) {
        return tituloPdfCaixaAlta(p != null && p.isPreemptivo());
    }

    /** Linha 1 do carimbo de pagina do PDF que vai aos avaliadores (sem acento, como ja e hoje). */
    public static String carimboLinha1(boolean preemptivo) {
        return preemptivo
            ? "Central de Transplantes do Estado do Rio Grande do Sul - INSERCAO EM LISTA DE ESPERA RENAL"
            : "Central de Transplantes do Estado do Rio Grande do Sul - URGENCIA RENAL";
    }

    public static String carimboLinha1(Processo p) {
        return carimboLinha1(p != null && p.isPreemptivo());
    }

    /** Rotulo do comprovante de inserção no SNT (mesmo TipoAnexo.COMPROVANTE_SNT, rotulo condicional). */
    public static String rotuloComprovanteSnt(boolean preemptivo) {
        return preemptivo
            ? "Comprovante de inserção em lista de espera renal no SNT"
            : "Comprovante de inserção da urgência renal no SNT";
    }

    public static String rotuloComprovanteSnt(Processo p) {
        return rotuloComprovanteSnt(p != null && p.isPreemptivo());
    }

    /** Rotulo do campo dataSituacaoEspecial (nome do campo/coluna NAO muda, so o rotulo exibido). */
    public static String rotuloDataClinica(boolean preemptivo) {
        return preemptivo ? "Data da solicitação" : "Data da urgência";
    }

    public static String rotuloDataClinica(Processo p) {
        return rotuloDataClinica(p != null && p.isPreemptivo());
    }

    /** Rotulo curto do prefixo de assunto de e-mail. */
    public static String prefixoAssunto(boolean preemptivo) {
        return preemptivo ? "Lista de Espera Renal" : "Urgência Renal";
    }

    public static String prefixoAssunto(Processo p) {
        return prefixoAssunto(p != null && p.isPreemptivo());
    }

    /** Justificativa clinica: "Por que a urgência se aplica" | "Por que a inserção preemptiva se aplica". */
    public static String rotuloJustificativa(boolean preemptivo) {
        return preemptivo ? "Por que a inserção preemptiva se aplica" : "Por que a urgência se aplica";
    }

    public static String rotuloJustificativa(Processo p) {
        return rotuloJustificativa(p != null && p.isPreemptivo());
    }
}

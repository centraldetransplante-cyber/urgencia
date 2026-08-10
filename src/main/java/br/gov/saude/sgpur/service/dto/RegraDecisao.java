package br.gov.saude.sgpur.service.dto;

/**
 * Vocabulario fechado para "qual regra decidiu (ou decide hoje) este
 * processo" - fonte unica consumida por templates (badge), pelo Relatorio
 * Final, pelo dossie exportado ({@code ExportacaoProcessoService}) e pela
 * auditoria, para as quatro reconstrucoes independentes da mesma frase
 * pararem de poder divergir entre si (achados 2, 3, 4 e 6 do
 * {@code docs/RELATORIO-VISTORIA-BRECHAS-DECISAO-2026-08-10.md}).
 *
 * <p>Calculado por {@code ProcessoValidator.regraAplicada(Processo)} - metodo
 * de LEITURA que reusa os predicados JA existentes ({@code
 * temVotoCoordenadorFavoravel}), sem duplicar nem alterar nenhum deles.</p>
 *
 * <p><b>NAO altera nem substitui a regra de decisao.</b> E so a leitura, em
 * texto, de uma decisao ja tomada (ou do estado atual, se o processo ainda
 * esta em tramitacao). Nunca e usado para decidir nada - ver a "Regra de
 * ouro" (F0) do relatorio acima: nenhum predicado de
 * {@code ProcessoValidator}/{@code ProcessoService.decidir} foi alterado
 * para este vocabulario existir.</p>
 */
public enum RegraDecisao {

    /** Decisao (Deferido ou Indeferido) por >=2 pareceres no mesmo sentido. */
    MAIORIA_SIMPLES(
        "Maioria simples (2 de 3)",
        "Decisão por maioria simples (2 de 3 pareceres no mesmo sentido)."),

    /**
     * Deferido pelo voto favoravel isolado do coordenador CET-RS (snapshot
     * {@code Parecer.eraCoordenadorNoVoto}), exceção regimental que dispensa
     * a maioria de 2 de 3.
     */
    VOTO_COORDENADOR(
        "Voto único do Coordenador CET-RS",
        "Deferido pelo voto único do Coordenador da CET-RS, que defere "
            + "isoladamente, conforme exceção regimental que dispensa a maioria de 2 de 3."),

    /** Processo CANCELADO - nenhuma regra de maioria de votos foi aplicada. */
    CANCELAMENTO(
        "Cancelado",
        "Processo cancelado - nenhuma regra de maioria de votos foi aplicada."),

    /** Processo ainda em tramitação: nenhuma decisão foi tomada ainda. */
    NAO_DECIDIDO(
        "Em tramitação",
        "Processo ainda em tramitação; nenhuma decisão foi tomada.");

    private final String rotuloCurto;
    private final String rotuloLongo;

    RegraDecisao(String rotuloCurto, String rotuloLongo) {
        this.rotuloCurto = rotuloCurto;
        this.rotuloLongo = rotuloLongo;
    }

    /** Rótulo curto, para badge (ex.: "Voto único do Coordenador CET-RS"). */
    public String getRotuloCurto() {
        return rotuloCurto;
    }

    /** Rótulo longo, em forma de frase, para documentos (PDF/dossiê/auditoria). */
    public String getRotuloLongo() {
        return rotuloLongo;
    }

    /**
     * Rótulo do parecer NÃO votado, dispensado por esta regra (Achado 4) -
     * "Dispensado pela maioria" só faz sentido quando de fato houve maioria;
     * a exceção do coordenador e o cancelamento dispensam por outro motivo.
     */
    public String getRotuloDispensado() {
        return switch (this) {
            case VOTO_COORDENADOR -> "Dispensado pelo voto do Coordenador";
            case CANCELAMENTO -> "Dispensado (processo cancelado)";
            case MAIORIA_SIMPLES -> "Dispensado pela maioria";
            case NAO_DECIDIDO -> "Pendente";
        };
    }

    /**
     * True quando esta regra vale a pena ser destacada num badge (foge do
     * caminho padrão de maioria simples) - Achado 6/§3.3: não poluir listas
     * com "maioria simples" em todo processo, só o que é excepcional.
     */
    public boolean isExcepcional() {
        return this == VOTO_COORDENADOR;
    }
}

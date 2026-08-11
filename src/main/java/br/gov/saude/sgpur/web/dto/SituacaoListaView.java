package br.gov.saude.sgpur.web.dto;

/**
 * Badge de situacao de UMA linha da lista do Portal do Solicitante
 * ({@code /solicitante}, {@code solicitante/lista.html}).
 *
 * <p><b>Por que existe (vistoria de responsividade/cores de 2026-08-11,
 * §4 do relatorio):</b> a lista pintava o badge direto de
 * {@code StatusSolicitacaoOnline.getBootstrapBadge()}/{@code getDescricao()},
 * e esse enum trata {@code CONVERTIDA} como um estado unico
 * ({@code bg-success} + "Convertida em processo") — entao um pedido
 * <b>INDEFERIDO</b> aparecia com o MESMO badge <b>verde</b> de um pedido
 * deferido e de um ainda em analise. Verde significa "deferido/sucesso" em
 * todo o resto do Portal (cartao {@code .r-ok}, timeline, icones), o que
 * fazia a lista anunciar sucesso para quem teve o pedido negado.
 *
 * <p><b>O enum NAO foi alterado de proposito:</b> ele e compartilhado com a
 * tela de triagem do OPERADOR
 * ({@code processos/solicitacoes-online-lista.html}), onde "Convertida em
 * processo" e a informacao correta (o operador acompanha o ciclo da
 * <i>solicitacao</i>, nao o desfecho clinico). A decisao de mostrar o
 * desfecho real e exclusiva da view do Solicitante — por isso este record,
 * calculado em {@code SolicitanteController.montarSituacaoLista}, e nao um
 * caso novo no enum.
 *
 * <p>O vocabulario (rotulo/tom/icone) e o MESMO de
 * {@link SituacaoPedidoView} (tela de detalhe): abrir o pedido nao pode
 * mostrar um rotulo diferente do que a lista acabou de mostrar.
 *
 * @param rotulo texto curto do badge (ex.: "Indeferido").
 * @param tom vocabulario semantico do design system:
 *            {@code "ok"|"danger"|"attention"|"aguardando"|"neutral"}
 *            (ver "Design system - regua de tokens" no CLAUDE.md).
 * @param icone bootstrap-icon sem o prefixo {@code bi-}.
 */
public record SituacaoListaView(String rotulo, String tom, String icone) {

    /**
     * Traducao do {@link #tom()} para classe do Bootstrap.
     *
     * <p>Nao usa o fragment {@code layout :: tomBadge} porque os badges
     * desta lista sao {@code rounded-pill} (pilula) e o fragment gera um
     * badge retangular — consumi-lo aqui mudaria a forma dos badges, que
     * nao e o que esta vistoria se propos a corrigir.
     */
    public String bootstrapBadge() {
        return switch (tom) {
            case "ok" -> "bg-success";
            case "danger" -> "bg-danger";
            case "attention" -> "bg-warning text-dark";
            case "aguardando" -> "bg-primary";
            default -> "bg-secondary";
        };
    }
}

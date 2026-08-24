package br.gov.saude.sgpur.web.dto;

/**
 * View pronta para o "cartao de situacao" unico do Portal do Solicitante
 * ({@code /solicitante/{id}}, Fase 6 do relatorio de UI de 2026-08). Antes
 * disso a tela tinha 8 blocos {@code <alert>} condicionais reescrevendo a
 * mesma decisao de status em formatos/vocabularios diferentes (ex.:
 * "Aprovada"/"Deferido"/"Pedido aprovado!" na mesma pagina). Agora toda a
 * decisao e feita UMA VEZ em {@code SolicitanteController.montarSituacaoPedido}
 * e o template so consome este record - nunca recalcula a regra sozinho.
 *
 * @param rotulo texto curto pro badge do topo (ex.: "Deferido").
 * @param classeCor sufixo Bootstrap (success/danger/warning/primary/secondary),
 *                   usado no alerta do cartao e no badge do {@code <h1>}.
 *                   @deprecated acopla esta view a uma classe CSS de um
 *                   framework especifico - use {@link #tom()} (vocabulario
 *                   semantico "ok"/"danger"/"attention"/"neutral") no lugar,
 *                   com o fragment {@code layout :: tomBadge}. Mantido porque
 *                   {@code solicitante/detalhe.html} ja consome este campo
 *                   diretamente e nao foi migrado nesta leva (infraestrutura
 *                   apenas - ver "Design system - regua de tokens" no
 *                   CLAUDE.md).
 * @param icone bootstrap-icon sem o prefixo {@code bi-}.
 * @param titulo frase curta de resultado, cabecalho do cartao (ex.:
 *               "Deferido - Urgencia renal reconhecida").
 * @param mensagem paragrafo principal explicando o que aconteceu / o que fazer.
 * @param detalhe texto secundario opcional (motivo do indeferimento/devolucao,
 *                 ou a mensagem oficial enviada a equipe) - {@code null} se nao houver.
 * @param precisaAcao quando {@code true}, o cartao inclui o formulario de
 *                     upload de informacao complementar, no topo da pagina.
 * @param mostrarNovaSolicitacao quando {@code true}, o cartao oferece o link
 *                                para enviar uma nova solicitacao (devolvida
 *                                ou processo excluido).
 * @param anexoParaBaixar anexo final (comprovante SNT / oficio de indeferimento)
 *                         pronto para download, ou {@code null} se ainda nao existir.
 * @param numeroProcesso numero {@code NN/AAAA} do processo gerado, ou
 *                         {@code null} se ainda nao convertido.
 */
public record SituacaoPedidoView(
    String rotulo,
    @Deprecated String classeCor,
    String icone,
    String titulo,
    String mensagem,
    String detalhe,
    boolean precisaAcao,
    boolean mostrarNovaSolicitacao,
    AnexoDownload anexoParaBaixar,
    String numeroProcesso
) {

    /** Anexo final pronto pra baixar: id do {@code Anexo} do processo + rotulo do botao. */
    public record AnexoDownload(Long id, String rotulo) {
    }

    /**
     * Acessor explicito do componente {@code classeCor}, so para carregar a
     * anotacao {@code @Deprecated} (nao muda o comportamento do record - ver
     * javadoc do parametro acima).
     */
    @Deprecated
    public String classeCor() {
        return classeCor;
    }

    /**
     * Tom semantico do design system, derivado de {@link #classeCor()}:
     * {@code "ok"} (success), {@code "danger"} (danger),
     * {@code "attention"} (warning - "Informacao necessaria"),
     * {@code "aguardando"} (primary - "Aguardando triagem"/"Aguardando
     * analise"/"Em analise") ou {@code "neutral"} (demais sufixos
     * Bootstrap, ex. secondary). Padronizacao de cores 2026-08-06: ver
     * {@code docs/IDEIA-PADRONIZACAO-CORES-SOLICITA-INFO-AGUARDANDO-2026-08.md}.
     */
    public String tom() {
        return switch (classeCor) {
            case "success" -> "ok";
            case "danger" -> "danger";
            case "warning" -> "attention";
            case "primary" -> "aguardando";
            default -> "neutral";
        };
    }
}

package br.gov.saude.sgpur.domain;

/**
 * Categoria do documento/e-mail anexado ao processo. Reflete as etapas do
 * fluxo, que sao conduzidas por e-mail (recebimento, envio, votos, resposta).
 */
public enum TipoAnexo {
    SOLICITACAO_AVALIADOR("Solicitacao de avaliacao gerada pelo sistema"),
    DOCUMENTO_CLINICO_AVALIADOR("Documento clinico anonimizado para os avaliadores"),
    /**
     * STAGING (trava de anonimizacao): documento que veio do Portal do
     * Solicitante e ainda NAO foi revisado/anonimizado pelo operador. Este tipo
     * <b>nunca</b> entra no PDF consolidado enviado aos 3 avaliadores
     * ({@code RegistroEnvioService} so funde {@link #DOCUMENTO_CLINICO_AVALIADOR})
     * e nao satisfaz {@code ProcessoValidator.validarRegistroEnvio}. So sai desse
     * estado por acao explicita e auditada do operador, por um de dois caminhos:
     *
     * <ul>
     *   <li><b>Substituicao</b> (o caso comum - o documento realmente traz o nome
     *   do paciente): o operador sobe a versao ja anonimizada e o original sai do
     *   processo na mesma acao -
     *   {@code POST /processos/{id}/documento-clinico/{anexoId}/substituir};</li>
     *   <li><b>Confirmacao</b> (falso-positivo - o documento nao identifica o
     *   paciente): o proprio arquivo vira {@link #DOCUMENTO_CLINICO_AVALIADOR} -
     *   {@code POST /processos/{id}/documento-clinico/{anexoId}/confirmar-anonimizacao}.</li>
     * </ul>
     *
     * <p><b>Processos anteriores a esta trava</b> tiveram o documento do portal
     * copiado direto como {@link #DOCUMENTO_CLINICO_AVALIADOR}: aquele tipo
     * continua valido e elegivel ao envio, entao esses processos seguem
     * funcionando normalmente - a trava so vale para conversoes novas.
     */
    DOCUMENTO_PORTAL_NAO_ANONIMIZADO(
        "Documento do Portal do Solicitante pendente de anonimizacao (nao vai aos avaliadores)"),
    DOCUMENTO_PACIENTE("Documento do paciente"),
    EMAIL_PARECER_RECEBIDO("Copia do e-mail de parecer recebido do avaliador"),
    ANEXO_AVALIADOR("Documento anexado pelo avaliador junto ao parecer (Portal do Avaliador)"),
    /**
     * STAGING/BRUTO: o que o SOLICITANTE enviou em resposta a um pedido de
     * informacao complementar (texto digitado no Portal e/ou arquivos), ou o
     * que o operador lancou por e-mail. <b>Visivel apenas ao OPERADOR</b> -
     * nunca chega ao avaliador, porque pode conter o nome completo do
     * paciente, a equipe solicitante e qualquer outro dado identificador
     * (o solicitante nao tem como saber o que quebra a imparcialidade).
     *
     * <p>O caminho para o material chegar aos avaliadores que pediram a
     * informacao e {@link #INFO_COMPLEMENTAR_AVALIADOR}, sempre por acao
     * explicita, revisada e auditada do operador - nunca por promocao
     * automatica deste tipo.</p>
     */
    INFO_COMPLEMENTAR("Pedido/resposta de informacao complementar (solicitante)"),
    /**
     * Conteudo de resposta a informacao complementar JA revisado/redigido pelo
     * operador, liberado para os avaliadores que pediram a informacao nesta
     * rodada (quem tem {@code HistoricoParecer} para o processo).
     *
     * <p><b>Nunca e gerado automaticamente a partir do envio bruto do
     * solicitante</b> ({@link #INFO_COMPLEMENTAR}): so nasce por acao
     * explicita do operador em
     * {@code POST /processos/{id}/info-complementar/encaminhar-avaliadores},
     * onde o texto que ele digita passa por {@code VerificadorNomePaciente}
     * antes de qualquer gravacao. Espirito identico ao de
     * {@link #DOCUMENTO_PORTAL_NAO_ANONIMIZADO}: material do solicitante so
     * atravessa a barreira de imparcialidade com revisao humana registrada.
     * Promover o texto bruto direto tornaria a checagem de nome um teatro -
     * a checagem existe justamente sobre o texto que o operador vai de fato
     * enviar.</p>
     */
    INFO_COMPLEMENTAR_AVALIADOR(
        "Resposta a informacao complementar revisada pelo operador (liberada aos avaliadores)"),
    OFICIO_INDEFERIMENTO("Oficio de indeferimento"),
    COMPROVANTE_SNT("Comprovante de insercao da urgencia renal no SNT"),
    EMAIL_RESPOSTA_SOLICITANTE("Copia do e-mail de resposta ao solicitante"),
    COMPROVANTE_ENVIO_SOLICITANTE("Comprovante de envio da resposta ao solicitante (print/PDF do e-mail)"),
    RELATORIO_FINAL("Relatorio final do processo"),
    OUTRO("Outro");

    private final String descricao;

    TipoAnexo(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }
}

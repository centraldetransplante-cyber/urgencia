package br.gov.saude.sgpur.service;

/**
 * Lancada especificamente quando {@code emailAdicional} tem formato valido
 * mas o DOMINIO nao resolve (ver {@link EmailDominioValidator}).
 *
 * <p><b>Por que uma excecao propria, e nao {@code IllegalArgumentException}
 * generica (achado real de revisao do PR #120):</b>
 * {@code ProcessoService.atualizarDados}/{@code SolicitacaoOnlineService.criar}
 * podem lancar {@code IllegalArgumentException} por VARIOS motivos (datas,
 * CPF, equipe, estado do processo etc., atuais ou futuros) - um controller
 * que captura {@code IllegalArgumentException} generica e assume sempre que
 * o erro e do campo {@code emailAdicional} (ex.:
 * {@code result.rejectValue("emailAdicional", ...)}) associa incorretamente
 * QUALQUER outro erro de validacao aquele campo, confundindo o operador.
 * Capturando este tipo especifico, o controller so aponta o campo
 * {@code emailAdicional} quando o erro e REALMENTE dele; qualquer outra
 * {@code IllegalArgumentException} cai no tratamento genérico (flash de
 * erro sem apontar campo nenhum).</p>
 */
public class EmailDominioInvalidoException extends IllegalArgumentException {

    public EmailDominioInvalidoException(String message) {
        super(message);
    }
}

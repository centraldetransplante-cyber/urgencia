package br.gov.saude.sgpur.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Varredura periodica que libera memoria dos mapas em memoria de rate-limit
 * de {@link LoginAttemptService} e {@link PasswordResetAttemptService}, e
 * remove do banco os {@code PasswordResetToken} ja expirados (ver
 * {@link PasswordResetService#limparTokensExpirados}, desde 2026-08-24).
 *
 * <p><b>Por que existe.</b> As duas classes de rate-limit em memoria
 * acumulam uma entrada por username (ou username inventado) que tentou
 * autenticar/resetar senha, e uma entrada so e removida quando a MESMA chave
 * e acessada de novo (sucesso de login, em {@link LoginAttemptService}, ou
 * qualquer chamada apos a janela expirar, em {@link PasswordResetAttemptService}).
 * Um atacante disparando uma sequencia de usernames INVENTADOS diferentes,
 * cada um usado uma unica vez, nunca aciona essa remocao - os mapas crescem
 * sem limite (vazamento de memoria de longo prazo). Este varredor remove
 * periodicamente as entradas cuja janela ja expirou, sem mudar nenhuma
 * semantica de rate-limit (uma janela expirada ja e ignorada no calculo
 * seguinte de qualquer forma). Os tokens de reset de senha expirados tem o
 * mesmo problema, so que no banco em vez de em memoria - a limpeza evita a
 * tabela {@code password_reset_token} crescer sem limite.</p>
 *
 * <p>Registrado apenas quando
 * {@code app.rate-limit.limpeza.varredura.habilitado=true} - mesma convencao
 * de {@link DecisaoAutomaticaScheduler}/{@link ComprovanteSntLembreteScheduler}
 * (desligado por padrao em dev/teste, ligado por padrao em producao, ver
 * {@code application-prod.yml}): mesmo esta varredura nao tocando no banco,
 * manter o mesmo padrao evita qualquer agendador rodando por engano no meio
 * da suite de testes.</p>
 */
@Component
@ConditionalOnProperty(
    prefix = "app.rate-limit.limpeza.varredura", name = "habilitado", havingValue = "true")
public class RateLimitLimpezaScheduler {

    private static final Logger log = LoggerFactory.getLogger(RateLimitLimpezaScheduler.class);

    private final LoginAttemptService loginAttemptService;
    private final PasswordResetAttemptService passwordResetAttemptService;
    private final PasswordResetService passwordResetService;

    public RateLimitLimpezaScheduler(LoginAttemptService loginAttemptService,
                                     PasswordResetAttemptService passwordResetAttemptService,
                                     PasswordResetService passwordResetService) {
        this.loginAttemptService = loginAttemptService;
        this.passwordResetAttemptService = passwordResetAttemptService;
        this.passwordResetService = passwordResetService;
    }

    /**
     * {@code fixedDelay} (nao {@code fixedRate}): o intervalo conta a partir
     * do FIM da execucao anterior. Intervalo curto por padrao (5 min) - a
     * varredura e barata (so percorre mapas em memoria).
     */
    @Scheduled(
        fixedDelayString = "${app.rate-limit.limpeza.varredura.intervalo-ms}",
        initialDelayString = "${app.rate-limit.limpeza.varredura.atraso-inicial-ms}")
    public void varrerAgendado() {
        varrer();
    }

    /** Roda a limpeza dos mapas em memoria + tokens de reset expirados no banco. Falha de um nao impede os outros. */
    public void varrer() {
        try {
            loginAttemptService.limparExpirados();
        } catch (RuntimeException e) {
            log.warn("Limpeza de rate-limit: falha ao limpar LoginAttemptService", e);
        }
        try {
            passwordResetAttemptService.limparExpirados();
        } catch (RuntimeException e) {
            log.warn("Limpeza de rate-limit: falha ao limpar PasswordResetAttemptService", e);
        }
        try {
            passwordResetService.limparTokensExpirados();
        } catch (RuntimeException e) {
            log.warn("Limpeza de rate-limit: falha ao limpar tokens de reset de senha expirados", e);
        }
    }
}

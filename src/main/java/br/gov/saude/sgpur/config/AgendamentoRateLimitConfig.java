package br.gov.saude.sgpur.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Liga o agendador do Spring ({@code @Scheduled}) quando a limpeza periodica
 * dos mapas em memoria de rate-limit esta habilitada
 * ({@code app.rate-limit.limpeza.varredura.habilitado=true} — ligada em
 * producao, desligada em dev/teste).
 *
 * <p>Classe separada de {@link AgendamentoConfig}/{@link AgendamentoSntConfig}
 * pelo mesmo motivo documentado nelas: interruptores INDEPENDENTES, e ter
 * {@code @EnableScheduling} em varias {@code @Configuration} e seguro (o
 * Spring registra um unico {@code ScheduledAnnotationBeanPostProcessor}).
 * Ver {@link br.gov.saude.sgpur.service.RateLimitLimpezaScheduler}.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "app.rate-limit.limpeza.varredura", name = "habilitado", havingValue = "true")
public class AgendamentoRateLimitConfig {
}

package br.gov.saude.sgpur.service;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trilha de auditoria das tentativas de login: registra em log TODA tentativa
 * de autenticacao (sucesso e falha) com o usuario informado e o IP de origem.
 * Alem disso, aplica um ATRASO PROGRESSIVO (nao um bloqueio) apos varias
 * falhas seguidas do mesmo usuario.
 *
 * <p><b>Nao existe bloqueio por forca bruta.</b> A versao anterior desta
 * classe travava a combinacao usuario+IP por 15 minutos apos 5 senhas erradas;
 * isso foi <b>removido deliberadamente</b> em 2026-07-28 (decisao de produto,
 * nao bug - ver CLAUDE.md): o log de auditoria com IP passou a ser a defesa
 * adotada, para nunca deixar um usuario legitimo trancado fora do sistema.
 *
 * <p><b>Atraso progressivo (2026-08-07), aprovado pelo dono do produto -
 * "leve, SEM bloqueio total de conta":</b> apos
 * {@link #LIMIAR_INICIAL} falhas seguidas do MESMO username numa janela de
 * {@link #janelaMinutos} minutos, cada falha SEGUINTE adiciona
 * {@link #atrasoPorTentativaMs} ms de atraso ANTES do usuario receber a
 * resposta de erro, ate um teto de {@link #atrasoMaximoMs} ms (por padrao 5
 * tentativas → 5s). O atraso mora em {@link #aoFalhar}, disparado pelo
 * evento de FALHA de autenticacao — nunca no caminho de sucesso, entao um
 * login com a senha certa **nunca** e atrasado, mesmo logo apos varias falhas.
 * O login em si NUNCA e recusado por este mecanismo (diferente do bloqueio
 * antigo): a senha certa sempre autentica, so mais devagar depois de uma
 * sequencia de erros — o oposto de negar servico, e leve o bastante (mapa em
 * memoria, sem infra nova) para nao abrir uma superficie de DoS por si so
 * (teto de {@link #atrasoMaximoMs} ms por tentativa, contador por username
 * -- nao cresce sem limite porque falha antiga fora da janela e descartada
 * na proxima leitura, e {@link #aoLogarComSucesso} zera o contador do
 * username assim que ele acerta).</p>
 *
 * <p><b>Limite de threads dormindo ao mesmo tempo (2026-08-23):</b> o
 * {@code Thread.sleep} de {@link #aoFalhar} roda na MESMA thread HTTP que
 * processa o POST de login - e a semantica DELIBERADA do atraso (precisa
 * acontecer antes da resposta, senao nao atrasa nada). Sob um ataque
 * distribuido disparando muitas tentativas simultaneas contra usernames
 * DIFERENTES, isso poderia ocupar boa parte do pool de threads do Tomcat
 * por ate {@link #atrasoMaximoMs} ms cada. {@link #permissoesAtraso} limita
 * quantas threads podem estar dormindo por causa deste atraso ao mesmo
 * tempo: {@code tryAcquire()} nao-bloqueante - se o limite ja foi atingido,
 * a tentativa atual simplesmente PULA o atraso (loga em DEBUG) em vez de
 * esperar a vez. Nunca impede o login de prosseguir, nunca bloqueia
 * esperando o semaforo, e nao muda o comportamento em uso normal (mesmo
 * teto de {@link #atrasoMaximoMs} ms por tentativa) - so limita o dano
 * maximo de "threads presas dormindo" sob ataque massivo.</p>
 *
 * <p><b>Por que por username e nao por IP:</b> um atacante tentando adivinhar
 * a senha de UM usuario especifico e o cenario que este mecanismo mitiga
 * (credential stuffing/senha fraca); um IP corporativo com varios usuarios
 * legitimos atras de NAT nunca deve ser penalizado pelo erro de outro
 * colega. O contador usa o username normalizado (minusculo, sem espacos nas
 * pontas) como chave — nao distingue IP, entao mesmo trocando de IP a mesma
 * tentativa contra o mesmo usuario continua sendo desacelerada.</p>
 *
 * <p><b>Como o IP chega ate aqui:</b> os eventos de autenticacao do Spring
 * Security sao publicados DENTRO da cadeia de filtros, ANTES do
 * DispatcherServlet - {@code RequestContextHolder} ainda NAO esta disponivel
 * nesse ponto (so e vinculado pelo DispatcherServlet, que sequer chega a ser
 * acionado para a URL de login, tratada inteiramente pelo filtro de
 * autenticacao do Security). Por isso esta propria classe tambem se registra
 * como {@link Filter} (o Spring Boot registra automaticamente qualquer bean
 * {@code Filter} encontrado no contexto) com a maior precedencia possivel
 * ({@link Ordered#HIGHEST_PRECEDENCE}), rodando ANTES da cadeia do Spring
 * Security, so para capturar {@code request.getRemoteAddr()} num ThreadLocal.
 * Nos eventos de autenticacao (falha/sucesso) o IP e obtido preferencialmente
 * do proprio {@code WebAuthenticationDetails} da autenticacao (mais direto e
 * sempre disponivel nesse ponto), caindo para o ThreadLocal so como reforco.
 */
@Service
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoginAttemptService implements Filter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** IP da requisicao HTTP corrente nesta thread - ver javadoc da classe. */
    private static final ThreadLocal<String> IP_REQUISICAO_ATUAL = new ThreadLocal<>();

    /** Falhas seguidas ANTES de comecar a atrasar (as primeiras nunca atrasam). */
    static final int LIMIAR_INICIAL = 2;

    /** Contagem de falhas por username normalizado, dentro da janela de tempo. */
    private final ConcurrentHashMap<String, Contador> tentativas = new ConcurrentHashMap<>();

    private final long atrasoPorTentativaMs;
    private final long atrasoMaximoMs;
    private final int janelaMinutos;

    /**
     * Limita quantas threads HTTP podem estar dormindo (dentro de
     * {@link #aoFalhar}) ao mesmo tempo por causa do atraso progressivo -
     * ver javadoc da classe. {@code tryAcquire()} nunca bloqueia.
     */
    final Semaphore permissoesAtraso;

    /** Permite ao teste "avancar" o relogio sem esperar minutos de verdade. */
    private java.util.function.Supplier<Instant> relogio = Instant::now;

    public LoginAttemptService(
            @Value("${app.login.rate-limit.atraso-por-tentativa-ms:1000}") long atrasoPorTentativaMs,
            @Value("${app.login.rate-limit.atraso-maximo-ms:5000}") long atrasoMaximoMs,
            @Value("${app.login.rate-limit.janela-minutos:15}") int janelaMinutos,
            @Value("${app.login.rate-limit.max-threads-atraso-simultaneas:20}") int maxThreadsAtrasoSimultaneas) {
        this.atrasoPorTentativaMs = atrasoPorTentativaMs;
        this.atrasoMaximoMs = atrasoMaximoMs;
        this.janelaMinutos = janelaMinutos;
        this.permissoesAtraso = new Semaphore(maxThreadsAtrasoSimultaneas);
    }

    /** Ponto de extensao usado SO por teste, para nao depender de tempo real. */
    void usarRelogioParaTeste(java.util.function.Supplier<Instant> relogio) {
        this.relogio = relogio;
    }

    private static final class Contador {
        final AtomicInteger falhas = new AtomicInteger(0);
        volatile Instant inicioJanela;
    }

    private String chave(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Calcula (e aplica) o atraso para a proxima falha deste username, ja
     * contabilizando a falha atual. Publico por ser exercitado diretamente em
     * teste (sem sleep real).
     */
    long calcularAtrasoMsEContabilizarFalha(String username) {
        String k = chave(username);
        Instant agora = relogio.get();
        Contador c = tentativas.computeIfAbsent(k, x -> {
            Contador novo = new Contador();
            novo.inicioJanela = agora;
            return novo;
        });
        // Janela expirada: reinicia a contagem em vez de acumular falhas
        // antigas indefinidamente (evita o mapa crescer sem limite e evita
        // penalizar uma tentativa isolada muito depois da anterior).
        if (Duration.between(c.inicioJanela, agora).toMinutes() >= janelaMinutos) {
            c.falhas.set(0);
            c.inicioJanela = agora;
        }
        int n = c.falhas.incrementAndGet();
        if (n <= LIMIAR_INICIAL) {
            return 0L;
        }
        long atraso = (long) (n - LIMIAR_INICIAL) * atrasoPorTentativaMs;
        return Math.min(atraso, atrasoMaximoMs);
    }

    private void limparContador(String username) {
        tentativas.remove(chave(username));
    }

    /**
     * Varredura periodica (ver {@code RateLimitLimpezaScheduler}) que remove
     * do mapa em memoria toda entrada cuja janela ja expirou. Sem isso, um
     * atacante testando muitos USERNAMES INVENTADOS diferentes (nenhum deles
     * jamais acerta a senha, entao {@link #limparContador} nunca roda para
     * eles) faz {@link #tentativas} crescer sem limite - vazamento de
     * memoria de longo prazo. Nao muda nenhuma semantica de rate-limit: so
     * libera memoria de chaves que ja nao afetariam mais o calculo do
     * proximo atraso mesmo se permanecessem.
     */
    void limparExpirados() {
        Instant agora = relogio.get();
        tentativas.entrySet().removeIf(entry ->
            Duration.between(entry.getValue().inicioJanela, agora).toMinutes() >= janelaMinutos);
    }

    /**
     * Captura o IP remoto da requisicao corrente num ThreadLocal, ANTES da
     * cadeia do Spring Security processar a autenticacao (ver javadoc da
     * classe). Nao interfere em mais nada da requisicao.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http) {
            IP_REQUISICAO_ATUAL.set(http.getRemoteAddr());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            IP_REQUISICAO_ATUAL.remove();
        }
    }

    @EventListener
    public void aoFalhar(AbstractAuthenticationFailureEvent evento) {
        String username = String.valueOf(evento.getAuthentication().getPrincipal());
        String ip = ipDaAutenticacao(evento.getAuthentication());
        log.warn("Login falhou para usuario '{}' (ip {}): {}", username, ip,
            evento.getException().getMessage());

        long atrasoMs = calcularAtrasoMsEContabilizarFalha(username);
        if (atrasoMs > 0) {
            aplicarAtraso(atrasoMs, username, ip);
        }
    }

    /**
     * Aplica o {@code Thread.sleep} do atraso progressivo, respeitando o
     * teto de threads dormindo simultaneamente ({@link #permissoesAtraso}).
     * Se o limite ja foi atingido, PULA o atraso desta vez (nunca bloqueia
     * esperando a vez) - ver javadoc da classe.
     */
    void aplicarAtraso(long atrasoMs, String username, String ip) {
        if (!permissoesAtraso.tryAcquire()) {
            log.debug("Login: atraso progressivo PULADO para usuario '{}' (ip {}) - "
                + "limite de threads dormindo simultaneamente ja atingido.", username, ip);
            return;
        }
        try {
            log.info("Login: atraso progressivo de {} ms aplicado para usuario '{}' "
                + "(ip {}) apos falhas seguidas - NAO e bloqueio, a proxima tentativa "
                + "com a senha certa continua autenticando normalmente.", atrasoMs, username, ip);
            Thread.sleep(atrasoMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            permissoesAtraso.release();
        }
    }

    @EventListener
    public void aoLogarComSucesso(AuthenticationSuccessEvent evento) {
        // O contador de falhas do usuario e zerado no sucesso: o atraso e so
        // sobre uma SEQUENCIA de erros, nunca uma penalidade permanente.
        limparContador(evento.getAuthentication().getName());
        String username = evento.getAuthentication().getName();
        String ip = ipDaAutenticacao(evento.getAuthentication());
        log.info("Login bem-sucedido para usuario '{}' (ip {})", username, ip);
    }

    /**
     * IP associado a uma autenticacao: preferencialmente o {@code WebAuthenticationDetails}
     * (populado pelo Spring Security a partir do request no momento do login),
     * com fallback no ThreadLocal capturado pelo filtro desta classe.
     */
    private String ipDaAutenticacao(Authentication authentication) {
        Object details = authentication == null ? null : authentication.getDetails();
        if (details instanceof WebAuthenticationDetails web && web.getRemoteAddress() != null) {
            return web.getRemoteAddress();
        }
        return IP_REQUISICAO_ATUAL.get();
    }
}

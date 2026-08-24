package br.gov.saude.sgpur.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Hashtable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Checagem LEVE de existencia de dominio para {@code Processo}/
 * {@code SolicitacaoOnline}.{@code emailAdicional} (achado real de vistoria,
 * 2026-08-24): a Bean Validation ({@code @Email}) so confere a FORMA do
 * endereco, nunca se o dominio existe de verdade - um erro de digitacao
 * comum (ex. "gmial.com") passava sem aviso nenhum.
 *
 * <p><b>So JDK puro</b> ({@code javax.naming}/{@code java.net}), sem
 * biblioteca nova: consulta o registro MX do dominio via JNDI (mesma tecnica
 * usada por qualquer verificador simples de e-mail); se nao houver MX,
 * tenta o registro A/AAAA (alguns dominios recebem e-mail direto no host,
 * sem MX dedicado) antes de concluir que o dominio nao existe.</p>
 *
 * <p><b>Fail-open deliberado:</b> qualquer erro que NAO seja uma resolucao
 * de nome negativa e clara e RAPIDA (DNS respondendo NXDOMAIN de verdade)
 * faz o metodo devolver {@code true} ("dominio ok") - nunca queremos
 * bloquear um cadastro legitimo por uma falha transitoria de infraestrutura
 * nossa. Um {@code false} so acontece quando a consulta CONCLUIU dentro do
 * teto de tempo e nem MX nem A/AAAA resolveram.</p>
 *
 * <p><b>Correcao de 2026-08-24 (revisao adicional do PR #120 - achados 1 e
 * 3):</b> antes desta correcao havia dois problemas serios:
 * <ol>
 *   <li><b>Fail-open quebrado:</b> {@code InetAddress.getAllByName} lanca a
 *   MESMA {@code UnknownHostException} tanto para "dominio realmente nao
 *   existe" (NXDOMAIN) quanto para "DNS instavel/rede intermitente" - o
 *   codigo tratava as duas causas da mesma forma (rejeitando), contradizendo
 *   o javadoc que prometia fail-open em falha de rede.</li>
 *   <li><b>DoS sincrono na thread HTTP:</b> a consulta rodava direto na
 *   thread do servlet, sem teto de tempo agregado - o timeout do MX (1500ms
 *   x2 tentativas = ate 3s) MAIS o timeout NAO configuravel do
 *   {@code InetAddress.getAllByName} (pode ser bem maior que isso,
 *   dependendo do resolver do SO) podiam, sob DNS lento, esgotar o pool de
 *   threads do Tomcat com varios cadastros simultaneos.</li>
 * </ol>
 * A correcao roda a checagem inteira ({@code possuiMxOuEnderecoIp}) num
 * {@link ExecutorService} dedicado (nunca o pool de request do Tomcat), com
 * um teto RIGIDO de {@link #TIMEOUT_MS} via
 * {@code CompletableFuture.get(timeout, ...)}. Qualquer timeout, interrupcao
 * ou excecao inesperada nesse caminho cai no MESMO fail-open do catch
 * externo - so uma resposta RAPIDA e limpa de "host not found" (a consulta
 * terminou dentro do teto e o DNS respondeu negativamente) continua sendo
 * tratada como dominio inexistente.</p>
 */
@Component
public class EmailDominioValidator {

    private static final Logger log = LoggerFactory.getLogger(EmailDominioValidator.class);

    private static final long TIMEOUT_MS = 2000;

    private final ExecutorService executor;
    private final long timeoutMillis;

    public EmailDominioValidator() {
        this(criarExecutorDedicado(), TIMEOUT_MS);
    }

    /** Visivel para teste: permite injetar um executor/timeout controlados (ex.: simular timeout). */
    EmailDominioValidator(ExecutorService executor, long timeoutMillis) {
        this.executor = executor;
        this.timeoutMillis = timeoutMillis;
    }

    private static ExecutorService criarExecutorDedicado() {
        // Pool pequeno e dedicado, NUNCA o pool de request do Tomcat - um
        // cadastro com emailAdicional preenchido nao pode competir por
        // threads HTTP com o resto da aplicacao. Threads daemon: nao impedem
        // o shutdown da JVM mesmo se alguma consulta ficar presa.
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "email-dominio-validator");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void encerrar() {
        executor.shutdownNow();
    }

    /**
     * @param email endereco completo (ex. {@code "fulano@dominio.com"});
     *              {@code null}/vazio/sem "@" e tratado como "ok" (nao e
     *              responsabilidade desta classe validar formato - isso ja
     *              e feito por {@code @Email}/regex no chamador).
     * @return {@code true} quando o dominio parece existir (ou a checagem
     *         nao pode ser concluida com confianca/dentro do teto de tempo -
     *         fail-open); {@code false} somente quando a consulta terminou
     *         DENTRO do teto de tempo e nem MX nem A/AAAA resolveram.
     */
    public boolean dominioResolvivel(String email) {
        if (email == null || email.isBlank()) {
            return true;
        }
        int arroba = email.lastIndexOf('@');
        if (arroba < 0 || arroba == email.length() - 1) {
            return true;
        }
        String dominio = email.substring(arroba + 1).trim();
        if (dominio.isEmpty()) {
            return true;
        }
        CompletableFuture<Boolean> futuro = CompletableFuture.supplyAsync(
            () -> possuiMxOuEnderecoIp(dominio), executor);
        try {
            return futuro.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("EmailDominioValidator: consulta de dominio '{}' excedeu {}ms - "
                + "fail-open (tratando como valido), sem esperar a rede responder.",
                dominio, timeoutMillis);
            futuro.cancel(true);
            return true;
        } catch (Exception e) {
            log.warn("EmailDominioValidator: falha inesperada verificando dominio '{}' - "
                + "fail-open (tratando como valido): {}", dominio, e.getMessage());
            return true;
        }
    }

    private boolean possuiMxOuEnderecoIp(String dominio) {
        try {
            if (possuiRegistroMx(dominio)) {
                return true;
            }
        } catch (NamingException e) {
            // Sem MX (ou a propria consulta MX falhou/expirou) - nao decide
            // nada ainda, cai para a tentativa de A/AAAA abaixo antes de
            // considerar o dominio inexistente.
            log.debug("EmailDominioValidator: consulta MX de '{}' sem resultado ({}), "
                + "tentando A/AAAA.", dominio, e.getMessage());
        }
        try {
            InetAddress.getAllByName(dominio);
            return true;
        } catch (UnknownHostException e) {
            log.info("EmailDominioValidator: dominio '{}' nao resolveu (nem MX nem A/AAAA) "
                + "dentro do teto de tempo.", dominio);
            return false;
        }
    }

    /** Timeout curto de proposito: nunca vale a pena travar um cadastro esperando DNS. */
    private boolean possuiRegistroMx(String dominio) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "1500");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext ctx = new InitialDirContext(env);
        try {
            Attributes attrs = ctx.getAttributes(dominio, new String[]{"MX"});
            Attribute mx = attrs.get("MX");
            return mx != null && mx.size() > 0;
        } finally {
            ctx.close();
        }
    }
}

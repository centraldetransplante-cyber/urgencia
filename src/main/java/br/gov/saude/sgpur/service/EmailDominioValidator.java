package br.gov.saude.sgpur.service;

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
 * de nome negativa e clara (timeout de DNS, rede fora do ar no proprio
 * servidor, excecao inesperada) faz o metodo devolver {@code true}
 * ("dominio ok") - nunca queremos bloquear um cadastro legitimo por uma
 * falha transitoria de infraestrutura nossa. Um {@code false} so acontece
 * quando NEM o MX NEM o A/AAAA resolvem (o cenario claro de "esse dominio
 * nao existe").</p>
 */
@Component
public class EmailDominioValidator {

    private static final Logger log = LoggerFactory.getLogger(EmailDominioValidator.class);

    /**
     * @param email endereco completo (ex. {@code "fulano@dominio.com"});
     *              {@code null}/vazio/sem "@" e tratado como "ok" (nao e
     *              responsabilidade desta classe validar formato - isso ja
     *              e feito por {@code @Email}/regex no chamador).
     * @return {@code true} quando o dominio parece existir (ou a checagem
     *         nao pode ser concluida com confianca - fail-open); {@code
     *         false} somente quando nem MX nem A/AAAA resolveram.
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
        try {
            return possuiMxOuEnderecoIp(dominio);
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
            log.info("EmailDominioValidator: dominio '{}' nao resolveu (nem MX nem A/AAAA).", dominio);
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

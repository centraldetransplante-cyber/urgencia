package br.gov.saude.sgpur.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;

/**
 * Seguranca da aplicacao: login por formulario com usuarios persistidos no
 * banco (ver UsuarioDetailsService). O primeiro ADMIN e criado por
 * {@link br.gov.saude.sgpur.bootstrap.AdminBootstrap} somente quando a tabela usuario esta vazia (o
 * DataSeed antigo, que sempre recriava dados de demo, foi removido).
 *
 * Perfis e rotas protegidas:
 *  - ADMIN    : acesso total, incluindo /usuarios/** e /auditoria/**.
 *  - OPERADOR : acesso operacional (processos, membros, relatorios).
 *               NAO acessa /avaliador/**.
 *  - AVALIADOR: acesso restrito ao portal /avaliador/**.
 *               NAO acessa /usuarios/**, /auditoria/** nem areas operacionais.
 *
 * Apos login, AVALIADOR e redirecionado para /avaliador; os demais para /.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Sem isso, o controle de sessao concorrente (maximumSessions(1)) nunca
     * fica sabendo quando uma HttpSession morre (timeout do servidor,
     * navegador fechado sem logout, etc.) - o SessionRegistry continua
     * contando a sessao antiga como ativa para sempre, e qualquer login
     * seguinte do mesmo usuario esbarra no limite. Registrar
     * HttpSessionEventPublisher como listener do servlet container (via
     * ServletListenerRegistrationBean - um @Bean simples do publisher NAO
     * e auto-registrado como listener pelo Spring Boot) resolve isso: toda
     * destruicao de sessao passa a remover a entrada correspondente do
     * registry.
     */
    @Bean
    public ServletListenerRegistrationBean<HttpSessionEventPublisher> httpSessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    /**
     * Registro explicito das sessoes autenticadas. Sem expor este bean, o
     * Spring Security cria um {@code SessionRegistryImpl} interno soh para o
     * controle de {@code maximumSessions(1)} - nenhum outro componente
     * consegue enxergar ou expirar uma sessao especifica. Expondo aqui e
     * amarrando via {@code .sessionRegistry(...)} abaixo, o MESMO registry
     * fica disponivel para injecao (ver {@code UsuarioService}), permitindo
     * revogar ativamente a sessao de um usuario que acabou de ser inativado
     * por um ADMIN - sem isso, a sessao ja aberta continuava valendo ate o
     * timeout de 30min mesmo com o login/senha ja bloqueado para NOVAS
     * autenticacoes (achado real de vistoria, 2026-08-24).
     *
     * <p><b>Limitacao conhecida, nao um bug ativo (achado 4 da revisao
     * adicional do PR #120):</b> {@link SessionRegistryImpl} guarda o estado
     * das sessoes SOMENTE EM MEMORIA da JVM local - nao escala para um
     * cluster com multiplas instancias atras de um load balancer (uma sessao
     * registrada na instancia A nao e visivel/revogavel pela instancia B).
     * Hoje o SAUR roda numa UNICA VM Oracle, sem cluster nem load balancer
     * (ver secao "Deploy" do CLAUDE.md) - nao ha problema real em producao.
     * Se um dia isto for clusterizado, este bean precisa virar um
     * {@code SessionRegistry} com estado compartilhado (ex.: backed por
     * Redis/Spring Session) para a revogacao continuar funcionando em
     * qualquer instancia.</p>
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Content-Security-Policy de producao. Restringe as origens ao proprio
     * host. A fonte Inter e auto-hospedada desde 2026-08-04 (@font-face em
     * app.css, arquivos em /fonts) - ate essa correcao a CSP ainda liberava
     * fonts.googleapis.com/fonts.gstatic.com por heranca do tempo em que a
     * fonte vinha do Google Fonts, mesmo sem nenhum uso restante dessas
     * origens (conferido: nenhum outro CSS/JS do projeto referencia essas
     * URLs). Mantem 'unsafe-inline' para script/style porque os templates
     * usam scripts e estilos inline; como nao ha nenhum th:utext no projeto,
     * a superficie de XSS refletido e minima.
     */
    private static final String CSP_PROD = String.join("; ",
        "default-src 'self'",
        "img-src 'self' data:",
        "style-src 'self' 'unsafe-inline'",
        "font-src 'self'",
        "script-src 'self' 'unsafe-inline'",
        "base-uri 'self'",
        "form-action 'self'",
        "frame-ancestors 'none'");

    /**
     * Mesma CSP de producao, mas com frame-ancestors 'self': usada SOMENTE na
     * resposta do PDF anonimizado do Portal do Avaliador (ver AVALIADOR_PDF_MATCHER),
     * que precisa ser embutida num <iframe> na propria pagina de votacao
     * (visualizacao sem download). Escopada via addHeaderWriter/
     * DelegatingRequestMatcherHeaderWriter abaixo - o resto do app mantem
     * 'none' (framing por qualquer origem, inclusive a propria, bloqueado).
     */
    private static final String CSP_PROD_AVALIADOR_PDF =
        CSP_PROD.replace("frame-ancestors 'none'", "frame-ancestors 'self'");

    /** Rota do PDF anonimizado servido ao avaliador (ver AvaliadorController.baixarPdf). */
    private static final RequestMatcher AVALIADOR_PDF_MATCHER =
        PathPatternRequestMatcher.withDefaults().matcher("/avaliador/*/pdf/*");

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, Environment env) throws Exception {
        // O console H2 (frames + sem CSRF) so existe em dev. Em producao ele nao
        // e registrado (spring.h2.console.enabled=false), e aqui tambem NAO
        // abrimos as excecoes de seguranca correspondentes (defesa em profundidade).
        boolean dev = env.matchesProfiles("dev");

        http
            .authorizeHttpRequests(auth -> {
                // favicon.svg (nao .ico): o icone e servido como SVG desde
                // 2026-08-04. O .ico continua liberado por seguranca - alguns
                // navegadores/leitores de feed ainda o pedem na raiz por conta
                // propria, e um 401 nesse pedido polui o log de auditoria.
                // /fonts/**: a fonte Inter passou a ser auto-hospedada em
                // 2026-08-04. Sem liberar aqui, o @font-face do app.css pedia
                // /fonts/inter-*.woff2, levava 302 para /login e a fonte nunca
                // carregava - justamente na tela de login, que e anonima.
                auth.requestMatchers("/css/**", "/js/**", "/webjars/**", "/fonts/**",
                    "/favicon.ico", "/favicon.svg").permitAll();
                if (dev) {
                    auth.requestMatchers("/h2-console/**").permitAll();
                }
                // Actuator: SOMENTE /actuator/health e publico (health-check para
                // monitoramento/deploy, sem exigir login). management.endpoints.web.
                // exposure.include=health (application.yml/application-prod.yml) ja
                // faz o Spring Boot nao registrar nenhum outro endpoint (/actuator/env,
                // /actuator/beans etc. respondem 404, nem chegam a esta cadeia de
                // seguranca) - a regra abaixo e defesa em profundidade explicita: se
                // algum dia mais endpoints forem expostos por engano em
                // exposure.include, eles NAO ficam liberados por padrao aqui, exigem
                // ADMIN.
                auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/**").hasRole("ADMIN");
                // Precisa vir ANTES da regra geral /usuarios/** (ADMIN) - senao ninguem
                // deslogado consegue acessar a recuperacao de senha, justamente quando
                // mais precisa (nao consegue logar).
                auth.requestMatchers("/usuarios/esqueci-senha").permitAll()
                    .requestMatchers("/login").permitAll()
                    // Troca da propria senha: qualquer usuario logado (ADMIN/OPERADOR/
                    // AVALIADOR). Precisa vir ANTES da regra /usuarios/** (ADMIN), senao
                    // OPERADOR/AVALIADOR ficariam sem como trocar a propria senha.
                    .requestMatchers("/usuarios/minha-senha").authenticated()
                    .requestMatchers("/usuarios/**", "/auditoria/**").hasRole("ADMIN")
                    // Membros e relatorios sao "acesso operacional" (comentario da classe):
                    // OPERADOR tem acesso completo (criar/editar/inativar membros, gerar
                    // relatorios), igual ao ADMIN. So /usuarios/** (cadastro de LOGINS) e
                    // /auditoria/** ficam exclusivos do ADMIN.
                    .requestMatchers("/membros/**", "/relatorios/**").hasAnyRole("ADMIN", "OPERADOR")
                    .requestMatchers("/controle-urgencias/**").hasAnyRole("ADMIN", "OPERADOR")
                    // Reabrir processo encerrado e exclusivo do ADMIN. Precisa vir
                    // ANTES da regra geral /processos/** (ADMIN,OPERADOR), senao o
                    // OPERADOR herdaria o acesso.
                    .requestMatchers(HttpMethod.POST, "/processos/*/reabrir").hasRole("ADMIN")
                    // Excluir processo tambem e exclusivo do ADMIN (OPERADOR edita,
                    // mas nao exclui). Mesma logica de ordenacao do reabrir acima.
                    .requestMatchers(HttpMethod.POST, "/processos/*/excluir").hasRole("ADMIN")
                    .requestMatchers("/arquivo/**").hasAnyRole("ADMIN", "OPERADOR")
                    .requestMatchers("/", "/processos/**").hasAnyRole("ADMIN", "OPERADOR")
                    .requestMatchers("/avaliador/**").hasRole("AVALIADOR")
                    // Portal do Solicitante (modulo experimental, ver
                    // docs/PLANO-SOLICITANTE.md): restrito a ROLE_SOLICITANTE
                    // independente do feature flag - se app.solicitante.habilitado
                    // estiver falso, SolicitanteController nem e registrado
                    // (@ConditionalOnProperty), entao a rota simplesmente 404
                    // para quem tiver a role, sem precisar duplicar o flag aqui.
                    .requestMatchers("/solicitante/**").hasRole("SOLICITANTE")
                    .anyRequest().authenticated();
            })
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(perfilSuccessHandler())
                .failureHandler(loginFailureHandler())
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )
            .sessionManagement(session -> session
                .maximumSessions(1)
                // false: um novo login com senha correta EXPIRA a sessao antiga em vez
                // de ser rejeitado. Antes era true (rejeitava o login novo) - isso
                // travava o usuario de fora quando a sessao anterior ficava "presa"
                // no SessionRegistry (navegador fechado sem logout, restart do
                // servico, timeout do lado do servidor) porque nao existia
                // HttpSessionEventPublisher (ver bean logo abaixo) avisando o
                // Spring Security que aquela sessao morreu - o registry continuava
                // contando ela pro limite de 1 indefinidamente. Com
                // HttpSessionEventPublisher registrado, sessoes expiradas/invalidadas
                // agora saem do registry corretamente; ainda assim mantemos false
                // por seguranca (senha certa nunca deve travar o dono legitimo fora
                // da propria conta).
                .maxSessionsPreventsLogin(false)
                .sessionRegistry(sessionRegistry())
                // Sem isto, uma sessao expirada pelo registry (concorrencia OU
                // revogacao ativa por inativacao - ver UsuarioService.
                // revogarSessoesAtivas) cai no SessionInformationExpiredStrategy
                // PADRAO do Spring Security (ResponseBodySessionInformationExpiredStrategy),
                // que escreve um texto plano avisando "sessao expirada" com
                // status 200 OK - nao um redirect. Um usuario nessa situacao
                // veria uma pagina tecnica em branco em vez de cair no login
                // de novo. expiredUrl forca o redirect gracioso de sempre.
                .expiredUrl("/login")
            )
            .csrf(csrf -> {
                // H2 console usa frames e nao envia CSRF token - excecao so em dev
                if (dev) {
                    csrf.ignoringRequestMatchers("/h2-console/**");
                }
            })
            .headers(headers -> {
                if (dev) {
                    // Console H2 precisa renderizar em frame do mesmo host
                    headers.frameOptions(frame -> frame.sameOrigin());
                } else {
                    // Producao: bloqueia enquadramento (clickjacking) do app INTEIRO por
                    // padrao (deny + frame-ancestors 'none') e forca HTTPS (HSTS). O
                    // Portal do Avaliador embute o PDF anonimizado num <iframe> na propria
                    // pagina de votacao - mas em vez de relaxar isso pra toda a aplicacao,
                    // a excecao fica restrita a AVALIADOR_PDF_MATCHER.
                    //
                    // X-Frame-Options: XFrameOptionsHeaderWriter sempre sobrescreve
                    // (response.setHeader sem guarda), entao basta registrar o writer
                    // "deny" (via frameOptions.deny() abaixo) e depois, via addHeaderWriter,
                    // um writer "sameOrigin" escopado ao path do PDF - como os writers
                    // custom (addHeaderWriter) rodam DEPOIS dos padrao no HeaderWriterFilter,
                    // o sameOrigin so tem efeito quando o path bate.
                    //
                    // Content-Security-Policy: ContentSecurityPolicyHeaderWriter SO escreve
                    // se o header AINDA NAO existir (response.containsHeader guard) - a
                    // mesma tecnica do X-Frame-Options nao funciona aqui (o writer "geral"
                    // rodando primeiro ja grava o header e o writer escopado que roda depois
                    // vira no-op). Por isso a CSP usa dois addHeaderWriter com matchers
                    // MUTUAMENTE EXCLUSIVOS (path do PDF vs o resto), em vez do metodo
                    // .contentSecurityPolicy(...) do configurer - so um dos dois bate por
                    // requisicao, sem depender de ordem de sobrescrita.
                    headers.frameOptions(frame -> frame.deny());
                    headers.httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31_536_000));
                    headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        AVALIADOR_PDF_MATCHER,
                        new XFrameOptionsHeaderWriter(XFrameOptionsMode.SAMEORIGIN)));
                    headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        new NegatedRequestMatcher(AVALIADOR_PDF_MATCHER),
                        new ContentSecurityPolicyHeaderWriter(CSP_PROD)));
                    headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        AVALIADOR_PDF_MATCHER,
                        new ContentSecurityPolicyHeaderWriter(CSP_PROD_AVALIADOR_PDF)));
                }
            });
        return http.build();
    }

    /**
     * Redireciona o usuario apos login conforme o perfil:
     *  - AVALIADOR   -> /avaliador (portal restrito, sem dados sigilosos)
     *  - SOLICITANTE -> /solicitante (portal restrito, modulo experimental)
     *  - demais      -> / (dashboard operacional)
     */
    @Bean
    public AuthenticationSuccessHandler perfilSuccessHandler() {
        return new AuthenticationSuccessHandler() {
            @Override
            public void onAuthenticationSuccess(HttpServletRequest request,
                                                HttpServletResponse response,
                                                Authentication authentication) throws IOException {
                boolean isAvaliador = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_AVALIADOR"));
                boolean isSolicitante = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_SOLICITANTE"));
                String destino = isAvaliador ? "/avaliador" : isSolicitante ? "/solicitante" : "/";
                response.sendRedirect(request.getContextPath() + destino);
            }
        };
    }

    /**
     * Toda falha de autenticacao volta para {@code /login?error} com a mesma
     * mensagem generica ("Usuario ou senha invalidos"), sem revelar o motivo
     * exato (usuario inexistente x senha errada x conta inativa).
     *
     * <p>Antes havia um ramo extra que mandava {@code LockedException} para
     * {@code /login?bloqueado}; ele foi removido junto com o bloqueio por
     * forca bruta (ver javadoc de {@code LoginAttemptService}) - nada mais
     * lanca {@code LockedException} neste sistema.
     */
    @Bean
    public AuthenticationFailureHandler loginFailureHandler() {
        return (request, response, exception) ->
            response.sendRedirect(request.getContextPath() + "/login?error");
    }
}

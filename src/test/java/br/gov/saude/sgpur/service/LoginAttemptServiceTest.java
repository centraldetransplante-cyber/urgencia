package br.gov.saude.sgpur.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * O bloqueio por forca bruta foi removido do sistema (decisao de produto, ver
 * javadoc de {@link LoginAttemptService}); o que sobra e a trilha de
 * auditoria (nenhuma tentativa de login pode derrubar a autenticacao com
 * excecao, e o IP precisa continuar sendo capturado pelo filtro) MAIS o
 * atraso progressivo (2026-08-07): varias falhas seguidas do mesmo usuario
 * atrasam a resposta, mas NUNCA impedem o login (a senha certa sempre
 * autentica).
 *
 * <p>Os testes de atraso usam constantes pequenas (1ms/tentativa, teto de
 * 5ms) injetadas via construtor -- exercita a MESMA logica de producao (o
 * calculo em si, {@code calcularAtrasoMsEContabilizarFalha}) sem deixar a
 * suite lenta com sleeps reais de segundos.</p>
 */
class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService(1, 5, 15, 20);

    /**
     * Simula uma requisicao HTTP passando pelo filtro (que captura o IP no
     * ThreadLocal) e executa {@code dentroDoChain} dentro do chain, igual ao
     * Security faria durante a autenticacao.
     */
    private void requisicaoDe(String ip, Runnable dentroDoChain) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        FilterChain chain = (req, res) -> dentroDoChain.run();
        try {
            service.doFilter(request, new MockHttpServletResponse(), chain);
        } catch (IOException | ServletException e) {
            throw new RuntimeException(e);
        }
    }

    private void falhar(String username, String ip) {
        var auth = new UsernamePasswordAuthenticationToken(username, "senha-errada");
        auth.setDetails(new WebAuthenticationDetails(criarRequestComIp(ip)));
        service.aoFalhar(new AuthenticationFailureBadCredentialsEvent(auth, new BadCredentialsException("bad")));
    }

    private MockHttpServletRequest criarRequestComIp(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(ip);
        return r;
    }

    private void logarComSucesso(String username, String ip) {
        var auth = new UsernamePasswordAuthenticationToken(username, "senha-certa");
        auth.setDetails(new WebAuthenticationDetails(criarRequestComIp(ip)));
        service.aoLogarComSucesso(new AuthenticationSuccessEvent(auth));
    }

    /** Le o ThreadLocal privado que guarda o IP da requisicao corrente. */
    @SuppressWarnings("unchecked")
    private String ipCapturadoAgora() {
        try {
            Field f = LoginAttemptService.class.getDeclaredField("IP_REQUISICAO_ATUAL");
            f.setAccessible(true);
            return ((ThreadLocal<String>) f.get(null)).get();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void filtroCapturaOIpDaRequisicaoDuranteAExecucaoDoChain() {
        AtomicReference<String> visto = new AtomicReference<>();
        requisicaoDe("10.0.0.1", () -> visto.set(ipCapturadoAgora()));

        assertThat(visto.get()).isEqualTo("10.0.0.1");
    }

    @Test
    void filtroLimpaOThreadLocalDepoisDaRequisicao() {
        requisicaoDe("10.0.0.1", () -> {});

        assertThat(ipCapturadoAgora()).isNull();
    }

    @Test
    void filtroLimpaOThreadLocalMesmoQuandoOChainLancaExcecao() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("erro dentro do chain");
        };

        assertThatCode(() -> service.doFilter(request, new MockHttpServletResponse(), chain))
            .isInstanceOf(IllegalStateException.class);
        assertThat(ipCapturadoAgora()).isNull();
    }

    @Test
    void filtroSempreSegueACadeia() {
        AtomicBoolean chamou = new AtomicBoolean(false);
        requisicaoDe("10.0.0.1", () -> chamou.set(true));

        assertThat(chamou).isTrue();
    }

    @Test
    void muitasFalhasSeguidasNaoBloqueiamNemLancamExcecao() {
        // O bloqueio por tentativas foi removido: errar a senha muitas vezes
        // continua sendo apenas auditado, nunca trava a conta.
        assertThatCode(() -> {
            for (int i = 0; i < 20; i++) {
                falhar("admin", "10.0.0.1");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void sucessoDepoisDeVariasFalhasNaoLancaExcecao() {
        for (int i = 0; i < 5; i++) {
            falhar("admin", "10.0.0.1");
        }

        assertThatCode(() -> logarComSucesso("admin", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void eventoDeFalhaForaDeUmaRequisicaoNaoLanca() {
        // Sem WebAuthenticationDetails e sem ThreadLocal (ex.: login
        // programatico), o IP fica nulo - o log ainda assim precisa sair.
        var auth = new UsernamePasswordAuthenticationToken("ninguem", "x");

        assertThatCode(() ->
            service.aoFalhar(new AuthenticationFailureBadCredentialsEvent(auth, new BadCredentialsException("bad"))))
            .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------
    // Atraso progressivo (2026-08-07) - NAO e bloqueio.
    // -----------------------------------------------------------------

    /** service com constantes de producao (usado so para checar os valores default). */
    private final LoginAttemptService serviceComConstantesDeProducao =
            new LoginAttemptService(1000, 5000, 15, 20);

    @Test
    void primeirasFalhasNaoGeramAtraso() {
        assertThat(serviceComConstantesDeProducao
                .calcularAtrasoMsEContabilizarFalha("usuario-primeiras-falhas")).isZero();
        assertThat(serviceComConstantesDeProducao
                .calcularAtrasoMsEContabilizarFalha("usuario-primeiras-falhas")).isZero();
    }

    @Test
    void falhasSeguintesAumentamOAtrasoProgressivamente() {
        LoginAttemptService s = new LoginAttemptService(1000, 5000, 15, 20);
        String u = "usuario-atraso-crescente";
        s.calcularAtrasoMsEContabilizarFalha(u); // 1a - sem atraso
        s.calcularAtrasoMsEContabilizarFalha(u); // 2a - sem atraso (LIMIAR_INICIAL)
        long terceira = s.calcularAtrasoMsEContabilizarFalha(u);
        long quarta = s.calcularAtrasoMsEContabilizarFalha(u);
        long quinta = s.calcularAtrasoMsEContabilizarFalha(u);

        assertThat(terceira).isEqualTo(1000);
        assertThat(quarta).isEqualTo(2000);
        assertThat(quinta).isEqualTo(3000);
    }

    @Test
    void atrasoNuncaUltrapassaOTeto() {
        LoginAttemptService s = new LoginAttemptService(1000, 5000, 15, 20);
        String u = "usuario-muitas-falhas";
        long ultimoAtraso = 0;
        for (int i = 0; i < 50; i++) {
            ultimoAtraso = s.calcularAtrasoMsEContabilizarFalha(u);
        }

        assertThat(ultimoAtraso).isEqualTo(5000);
    }

    @Test
    void loginComSucessoZeraOContadorDeAtraso() {
        LoginAttemptService s = new LoginAttemptService(1000, 5000, 15, 20);
        String u = "usuario-reseta-apos-sucesso";
        for (int i = 0; i < 10; i++) {
            s.calcularAtrasoMsEContabilizarFalha(u);
        }
        // Antes do sucesso, ja estaria atrasando bastante.
        assertThat(s.calcularAtrasoMsEContabilizarFalha(u)).isGreaterThan(0);

        var auth = new UsernamePasswordAuthenticationToken(u, "senha-certa");
        auth.setDetails(new WebAuthenticationDetails(criarRequestComIp("10.0.0.1")));
        s.aoLogarComSucesso(new AuthenticationSuccessEvent(auth));

        // Depois do sucesso, volta a ser tratado como as primeiras falhas.
        assertThat(s.calcularAtrasoMsEContabilizarFalha(u)).isZero();
    }

    @Test
    void janelaExpiradaReiniciaAContagemSemAtraso() {
        LoginAttemptService s = new LoginAttemptService(1000, 5000, 1, 20);
        AtomicReference<Instant> agora = new AtomicReference<>(Instant.now());
        s.usarRelogioParaTeste(agora::get);
        String u = "usuario-janela-expira";

        s.calcularAtrasoMsEContabilizarFalha(u);
        s.calcularAtrasoMsEContabilizarFalha(u);
        long comFalhasRecentes = s.calcularAtrasoMsEContabilizarFalha(u);
        assertThat(comFalhasRecentes).isGreaterThan(0);

        // Avanca o "relogio" alem da janela de 1 minuto: a proxima falha
        // reinicia a contagem, como se fosse a primeira de novo.
        agora.set(agora.get().plusSeconds(120));
        assertThat(s.calcularAtrasoMsEContabilizarFalha(u)).isZero();
    }

    // -----------------------------------------------------------------
    // Limpeza periodica (2026-08-23) - libera memoria de entradas expiradas.
    // -----------------------------------------------------------------

    @Test
    void limparExpiradosRemoveEntradaComJanelaJaExpirada() throws ReflectiveOperationException {
        LoginAttemptService s = new LoginAttemptService(1000, 5000, 1, 20);
        AtomicReference<Instant> agora = new AtomicReference<>(Instant.now());
        s.usarRelogioParaTeste(agora::get);
        String u = "usuario-expira-e-e-limpo";

        s.calcularAtrasoMsEContabilizarFalha(u);
        assertThat(tamanhoDoMapaDeTentativas(s)).isEqualTo(1);

        // Ainda dentro da janela (1 min): a limpeza NAO remove.
        s.limparExpirados();
        assertThat(tamanhoDoMapaDeTentativas(s)).isEqualTo(1);

        // Avanca o relogio alem da janela: a limpeza remove a entrada.
        agora.set(agora.get().plusSeconds(120));
        s.limparExpirados();
        assertThat(tamanhoDoMapaDeTentativas(s)).isZero();
    }

    @Test
    void limparExpiradosNaoRemoveEntradaAindaDentroDaJanela() throws ReflectiveOperationException {
        LoginAttemptService s = new LoginAttemptService(1000, 5000, 15, 20);
        String u = "usuario-ainda-na-janela";
        s.calcularAtrasoMsEContabilizarFalha(u);

        s.limparExpirados();

        assertThat(tamanhoDoMapaDeTentativas(s)).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private int tamanhoDoMapaDeTentativas(LoginAttemptService s) throws ReflectiveOperationException {
        Field f = LoginAttemptService.class.getDeclaredField("tentativas");
        f.setAccessible(true);
        var mapa = (java.util.concurrent.ConcurrentHashMap<String, Object>) f.get(s);
        return mapa.size();
    }

    // -----------------------------------------------------------------
    // Semaforo de threads dormindo simultaneamente (2026-08-23).
    // -----------------------------------------------------------------

    @Test
    void aplicarAtrasoComSemaforoSaturadoPulaOAtrasoSemLancarNemTravar() {
        // Semaforo com capacidade 1: a 1a chamada adquire e fica "dormindo"
        // (simulado aqui mantendo a permissao presa manualmente, sem thread
        // de verdade), a 2a chamada deve PULAR o atraso em vez de bloquear.
        LoginAttemptService s = new LoginAttemptService(1000, 5000, 15, 1);
        assertThat(s.permissoesAtraso.tryAcquire()).isTrue(); // ocupa a unica permissao

        long antes = System.currentTimeMillis();
        assertThatCode(() -> s.aplicarAtraso(5000, "usuario-saturado", "10.0.0.1"))
            .doesNotThrowAnyException();
        long duracaoMs = System.currentTimeMillis() - antes;

        // Sem permissao disponivel, aplicarAtraso nao deve dormir os 5000ms
        // configurados - deve retornar quase instantaneamente.
        assertThat(duracaoMs).isLessThan(500);
    }

    @Test
    void aplicarAtrasoComSemaforoLivreDormeEDevolveAPermissao() {
        LoginAttemptService s = new LoginAttemptService(1000, 5000, 15, 1);

        assertThatCode(() -> s.aplicarAtraso(50, "usuario-com-permissao", "10.0.0.1"))
            .doesNotThrowAnyException();

        // A permissao foi devolvida ao final (finally) - uma nova aquisicao
        // deve funcionar sem travar.
        assertThat(s.permissoesAtraso.tryAcquire()).isTrue();
    }

    /**
     * REGRA CENTRAL: mesmo com o contador de falhas alto, o login com a
     * senha certa em si NUNCA e recusado por este mecanismo - so mais
     * devagar. O metodo que aplica o atraso (aoFalhar) so roda no caminho de
     * FALHA; aoLogarComSucesso nunca chama calcularAtraso/dorme.
     */
    @Test
    void sucessoNuncaEAtrasadoMesmoAposMuitasFalhas() {
        for (int i = 0; i < 20; i++) {
            falhar("usuario-nao-bloqueia", "10.0.0.1");
        }

        long antes = System.currentTimeMillis();
        assertThatCode(() -> logarComSucesso("usuario-nao-bloqueia", "10.0.0.1"))
                .doesNotThrowAnyException();
        long duracaoMs = System.currentTimeMillis() - antes;

        // aoLogarComSucesso nao dorme - deve ser praticamente instantaneo.
        assertThat(duracaoMs).isLessThan(500);
    }
}

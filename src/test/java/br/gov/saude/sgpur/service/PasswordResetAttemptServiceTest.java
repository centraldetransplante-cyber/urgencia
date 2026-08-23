package br.gov.saude.sgpur.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetAttemptServiceTest {

    private final PasswordResetAttemptService service = new PasswordResetAttemptService();

    @Test
    void permiteAteTresTentativasNaJanela() {
        assertThat(service.tentarRegistrar("rafael")).isTrue();
        assertThat(service.tentarRegistrar("rafael")).isTrue();
        assertThat(service.tentarRegistrar("rafael")).isTrue();
    }

    @Test
    void bloqueiaAQuartaTentativaNaMesmaJanela() {
        service.tentarRegistrar("rafael");
        service.tentarRegistrar("rafael");
        service.tentarRegistrar("rafael");

        assertThat(service.tentarRegistrar("rafael")).isFalse();
    }

    @Test
    void quartaTentativaBloqueadaNaoIncrementaContadorAlemDoLimite() {
        service.tentarRegistrar("rafael");
        service.tentarRegistrar("rafael");
        service.tentarRegistrar("rafael");
        service.tentarRegistrar("rafael"); // bloqueada, nao deveria contar
        service.tentarRegistrar("rafael"); // tambem bloqueada

        // ainda bloqueado, nao "destravou" por ter continuado tentando
        assertThat(service.tentarRegistrar("rafael")).isFalse();
    }

    @Test
    void usernameEComparadoIgnorandoMaiusculasEMinusculas() {
        service.tentarRegistrar("Rafael");
        service.tentarRegistrar("RAFAEL");
        service.tentarRegistrar("rafael");

        assertThat(service.tentarRegistrar("rAfAeL")).isFalse();
    }

    @Test
    void usuariosDiferentesTemLimitesIndependentes() {
        service.tentarRegistrar("rafael");
        service.tentarRegistrar("rafael");
        service.tentarRegistrar("rafael");
        assertThat(service.tentarRegistrar("rafael")).isFalse();

        // outro usuario nao foi afetado pelo limite do 'rafael'
        assertThat(service.tentarRegistrar("outro")).isTrue();
    }

    @Test
    void usernameNuloNaoLancaExcecao() {
        assertThat(service.tentarRegistrar(null)).isTrue();
        assertThat(service.tentarRegistrar(null)).isTrue();
        assertThat(service.tentarRegistrar(null)).isTrue();
        assertThat(service.tentarRegistrar(null)).isFalse();
    }

    @Test
    void janelaExpiradaLiberaNovasTentativas() throws Exception {
        service.tentarRegistrar("rafael");
        service.tentarRegistrar("rafael");
        service.tentarRegistrar("rafael");
        assertThat(service.tentarRegistrar("rafael")).isFalse();

        // Sem Clock injetavel na classe: simula o tempo passando reescrevendo
        // o inicioJanela do estado interno via reflexao, igual a janela real
        // de 15min ja teria expirado.
        forcarInicioJanelaNoPassado("rafael", java.time.Duration.ofMinutes(20));

        assertThat(service.tentarRegistrar("rafael")).isTrue();
    }

    // -----------------------------------------------------------------
    // Limpeza periodica (2026-08-23) - libera memoria de entradas expiradas.
    // -----------------------------------------------------------------

    @Test
    void limparExpiradosRemoveEntradaComJanelaJaExpirada() throws ReflectiveOperationException {
        PasswordResetAttemptService s = new PasswordResetAttemptService();
        java.util.concurrent.atomic.AtomicReference<Instant> agora =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        usarRelogioParaTeste(s, agora::get);
        String u = "usuario-reset-expira";

        s.tentarRegistrar(u);
        assertThat(tamanhoDoMapa(s)).isEqualTo(1);

        // Ainda dentro da janela (15min): a limpeza NAO remove.
        s.limparExpirados();
        assertThat(tamanhoDoMapa(s)).isEqualTo(1);

        // Avanca o relogio alem da janela: a limpeza remove a entrada.
        agora.set(agora.get().plus(java.time.Duration.ofMinutes(20)));
        s.limparExpirados();
        assertThat(tamanhoDoMapa(s)).isZero();
    }

    @Test
    void limparExpiradosNaoRemoveEntradaAindaDentroDaJanela() throws ReflectiveOperationException {
        PasswordResetAttemptService s = new PasswordResetAttemptService();
        s.tentarRegistrar("usuario-reset-ainda-na-janela");

        s.limparExpirados();

        assertThat(tamanhoDoMapa(s)).isEqualTo(1);
    }

    private void usarRelogioParaTeste(PasswordResetAttemptService s,
            java.util.function.Supplier<Instant> relogio) throws ReflectiveOperationException {
        Field f = PasswordResetAttemptService.class.getDeclaredField("relogio");
        f.setAccessible(true);
        f.set(s, relogio);
    }

    @SuppressWarnings("unchecked")
    private int tamanhoDoMapa(PasswordResetAttemptService s) throws ReflectiveOperationException {
        Field f = PasswordResetAttemptService.class.getDeclaredField("tentativasPorUsuario");
        f.setAccessible(true);
        var mapa = (ConcurrentHashMap<String, Object>) f.get(s);
        return mapa.size();
    }

    @SuppressWarnings("unchecked")
    private void forcarInicioJanelaNoPassado(String username, java.time.Duration ha) throws Exception {
        Field mapaField = PasswordResetAttemptService.class.getDeclaredField("tentativasPorUsuario");
        mapaField.setAccessible(true);
        ConcurrentHashMap<String, Object> mapa =
            (ConcurrentHashMap<String, Object>) mapaField.get(service);

        Class<?> estadoClass = Class.forName(
            "br.gov.saude.sgpur.service.PasswordResetAttemptService$Estado");
        RecordComponent[] componentes = estadoClass.getRecordComponents();
        String chave = username.toLowerCase(java.util.Locale.ROOT);
        Object estadoAtual = mapa.get(chave);

        var accessor = estadoClass.getDeclaredMethod(componentes[0].getName());
        accessor.setAccessible(true);
        int tentativas = (int) accessor.invoke(estadoAtual);
        Instant inicioNoPassado = Instant.now().minus(ha);

        var construtor = estadoClass.getDeclaredConstructor(int.class, Instant.class);
        construtor.setAccessible(true);
        Object novoEstado = construtor.newInstance(tentativas, inicioNoPassado);

        mapa.put(chave, novoEstado);
    }
}

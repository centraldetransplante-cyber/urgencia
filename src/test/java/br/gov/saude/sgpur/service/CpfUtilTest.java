package br.gov.saude.sgpur.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CpfUtilTest {

    private static final String CPF_VALIDO = "11144477735";

    @Test
    void normalizarRemoveTudoQueNaoEDigito() {
        assertThat(CpfUtil.normalizar("111.444.777-35")).isEqualTo("11144477735");
        assertThat(CpfUtil.normalizar("  111 444 777 35  ")).isEqualTo("11144477735");
        assertThat(CpfUtil.normalizar(null)).isEmpty();
    }

    @Test
    void validaUmCpfRealComDigitosVerificadoresCorretos() {
        assertThat(CpfUtil.valido(CPF_VALIDO)).isTrue();
    }

    @Test
    void rejeitaCadaDigitoVerificadorErradoIsoladamente() {
        // primeiro digito verificador errado (posicao 9)
        assertThat(CpfUtil.valido("11144477745")).isFalse();
        // segundo digito verificador errado (posicao 10)
        assertThat(CpfUtil.valido("11144477736")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "00000000000", "11111111111", "22222222222", "33333333333", "44444444444",
        "55555555555", "66666666666", "77777777777", "88888888888", "99999999999"
    })
    void rejeitaAsDezSequenciasDegeneradas(String sequencia) {
        assertThat(CpfUtil.valido(sequencia)).isFalse();
    }

    @Test
    void rejeitaTamanhoDiferenteDeOnzeDigitos() {
        assertThat(CpfUtil.valido("1234567890")).isFalse();
        assertThat(CpfUtil.valido("123456789012")).isFalse();
        assertThat(CpfUtil.valido("")).isFalse();
        assertThat(CpfUtil.valido(null)).isFalse();
    }

    @Test
    void rejeitaEntradaComCaractereNaoNumerico() {
        assertThat(CpfUtil.valido("1114447773a")).isFalse();
    }

    @Test
    void formataOnzeDigitosCrus() {
        assertThat(CpfUtil.formatar(CPF_VALIDO)).isEqualTo("111.444.777-35");
    }

    @Test
    void formatarDevolveEntradaSemAlteracaoQuandoNaoTemOnzeDigitos() {
        assertThat(CpfUtil.formatar("123")).isEqualTo("123");
        assertThat(CpfUtil.formatar(null)).isNull();
    }
}

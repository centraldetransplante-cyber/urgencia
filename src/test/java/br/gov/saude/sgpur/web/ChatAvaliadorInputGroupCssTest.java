package br.gov.saude.sgpur.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guarda a correcao do bug real relatado em producao em 2026-08-07: o botao
 * "Enviar" do chat operador&lt;-&gt;avaliador (aba Respostas, card "Conversa"
 * por parecer, {@code processos/detalhe.html}) aparecia numa linha ABAIXO do
 * campo de texto, alinhado a esquerda, em vez de ao lado dele — a mesma
 * queixa do usuario ("botao de enviar do lado esquerdo").
 *
 * <p><b>Causa raiz (nao era bug de HTML/markup nem de JS):</b> a regra
 * generica {@code .table td form .form-control { width: 100%; }} (existe
 * para os demais forms soltos dentro de celula de tabela, ex.: selects/
 * inputs de acao de parecer) tem especificidade MAIOR
 * (2 classes + 2 tipos = 0,0,2,2) que a regra do proprio Bootstrap
 * {@code .input-group>.form-control { width: 1% }} (2 classes = 0,0,2,0) -
 * e o form do chat por avaliador VIVE dentro de {@code <table><td><form>},
 * porque a thread de conversa fica numa linha da tabela de pareceres. A
 * regra generica vencia e forcava {@code width:100%} no campo de texto;
 * como {@code .input-group} tem {@code flex-wrap:wrap} (padrao do
 * Bootstrap), um campo que sozinho ja ocupa 100% da linha forca o botao ao
 * lado a quebrar para a linha seguinte, alinhado a esquerda por nao ter
 * mais espaco na linha de cima.
 *
 * <p>Confirmado navegando de verdade com Playwright (nao so lendo o CSS
 * fonte): o computed style de {@code .input-group} tinha
 * {@code flex-wrap: wrap} e o campo de texto tinha {@code width: 806px}
 * (100% do container), com o botao "Enviar" caindo numa segunda linha.
 *
 * <p><b>Por que um teste de arquivo e nao de MockMvc.</b> Um
 * {@code @WebMvcTest}/{@code MockMvc} verifica status e model attributes,
 * nunca o layout final calculado pelo navegador a partir do CSS - a mesma
 * classe de bug so aparece renderizando de verdade (ver
 * {@code DesignSystemFontSizeInlineTest}/{@code AcessibilidadeEstruturaTest}
 * para o mesmo raciocinio). Como o projeto nao tem infraestrutura de
 * screenshot-diff, este teste faz a proxima melhor coisa: garante que a
 * regra de CSS que corrige a especificidade continua presente e com
 * especificidade suficiente para vencer a regra generica, e falha alto se
 * alguem remover/reordenar sem perceber o efeito colateral.
 */
class ChatAvaliadorInputGroupCssTest {

    private static final Path APP_CSS = Path.of("src/main/resources/static/css/app.css");

    /** A regra generica que causa o problema (tem que continuar existindo -
     * ela e legitima para os outros forms soltos em celula de tabela). */
    private static final Pattern REGRA_GENERICA = Pattern.compile(
        "\\.table\\s+td\\s+form\\s+\\.form-control\\s*\\{[^}]*width\\s*:\\s*100%");

    /** A correcao: precisa ter especificidade >= a da regra generica acima
     * (2 classes + 2 tipos) para vencer, reafirmando o width:1% que o
     * Bootstrap usa dentro de .input-group. Aceita a declaracao com 3
     * classes (.table, .input-group, .form-control) + 2 tipos (td, form),
     * que e estritamente mais especifica. */
    private static final Pattern REGRA_CORRECAO = Pattern.compile(
        "\\.table\\s+td\\s+form\\s+\\.input-group\\s*>\\s*\\.form-control\\s*\\{[^}]*width\\s*:\\s*1%");

    @Test
    void regraGenericaDeFormEmTabelaContinuaExistindo() throws IOException {
        String css = ler(APP_CSS);
        assertThat(REGRA_GENERICA.matcher(css).find())
            .as("`.table td form .form-control { width: 100% }` deveria continuar existindo - "
                + "e a regra legitima para os demais forms soltos (nao input-group) numa celula "
                + "de tabela. Se ela mudou de forma, reveja tambem "
                + "regraDeCorrecaoDoInputGroupContinuaExistindoEVencendoAGenerica abaixo.")
            .isTrue();
    }

    @Test
    void regraDeCorrecaoDoInputGroupContinuaExistindoEVencendoAGenerica() throws IOException {
        String css = ler(APP_CSS);
        Matcher generica = REGRA_GENERICA.matcher(css);
        Matcher correcao = REGRA_CORRECAO.matcher(css);

        assertThat(correcao.find())
            .as("A regra que corrige o bug do botao 'Enviar' quebrando para a linha de baixo no "
                + "chat operador<->avaliador (`.table td form .input-group > .form-control "
                + "{ width: 1% }`) nao foi encontrada em app.css - ela precisa continuar existindo "
                + "com especificidade maior que `.table td form .form-control`, senao o campo de "
                + "texto volta a ocupar 100% da linha e o botao ao lado quebra visualmente "
                + "(bug real relatado em producao em 2026-08-07).")
            .isTrue();

        // Especificidade CSS por contagem de seletores simples: a regra de
        // correcao tem 1 classe a mais (.input-group) que a generica -
        // conferimos isso contando ocorrencias de "." e de palavras-tipo no
        // seletor capturado, em vez de reimplementar um parser de CSS.
        assertThat(generica.find()).isTrue();
        String seletorGenerico = css.substring(generica.start(), css.indexOf('{', generica.start()));
        String seletorCorrecao = css.substring(correcao.start(), css.indexOf('{', correcao.start()));
        int classesGenerico = contarClasses(seletorGenerico);
        int classesCorrecao = contarClasses(seletorCorrecao);
        assertThat(classesCorrecao)
            .as("A regra de correcao precisa ter MAIS classes no seletor que a regra generica "
                + "para vencer no empate de tipos (td/form em ambas) - senao a ordem de "
                + "declaracao no arquivo decide, o que e fragil.")
            .isGreaterThan(classesGenerico);
    }

    private static int contarClasses(String seletor) {
        Matcher m = Pattern.compile("\\.[a-zA-Z-]+").matcher(seletor);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private static String ler(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}

package br.gov.saude.sgpur.e2e;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.e2e.pages.PortalSolicitantePage;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.service.UsuarioService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verificacao visual de VERDADE (browser real) do redesign visual do Portal
 * do Solicitante — fases V1–V6 do
 * {@code docs/RELATORIO-REDESIGN-VISUAL-SOLICITANTE-2026-08.md}, mescladas
 * em {@code main} pelo PR #42 em 2026-08-06.
 *
 * <p><b>Por que este teste existe.</b> A §11 daquele relatorio e explicita:
 * <i>"as 783 asseroes da suite e o E2E podem ficar inteiramente verdes com o
 * Portal visualmente quebrado"</i> — os testes normais verificam status HTTP,
 * model attributes e presenca de texto/id, <b>nunca</b> cor, tamanho,
 * espacamento ou sombra. O redesign inteiro entrou em producao sem nenhum
 * guarda automatizado: bastava alguem apagar uma classe do {@code app.css}
 * ou trocar {@code .secao-titulo} de volta por {@code h6 text-muted} para o
 * Portal voltar ao estado que gerou a queixa do dono do produto
 * (<i>"o visual e tao simples e feio"</i>) sem uma unica falha na suite.
 *
 * <p>Aqui cada asserçao mira um achado nominal do diagnostico, medindo o
 * <b>CSS computado pelo navegador</b> (nao o texto do template):
 * <ul>
 *   <li>§4.1 — titulo de secao tinha 1rem, o MESMO tamanho do corpo de texto.
 *       Hoje {@code .secao-titulo} usa {@code --saur-font-lg} (1,25rem): o
 *       teste exige que ele seja estritamente MAIOR que o paragrafo ao lado;</li>
 *   <li>§4.2 — a cor institucional nao entrava no conteudo. Hoje a faixa
 *       {@code .pagina-cabecalho} pinta de fato (fundo nao-transparente), nas
 *       duas variantes: suave na lista/formulario, solida no detalhe;</li>
 *   <li>§4.6 — o estado vazio da lista (primeira tela de um solicitante novo)
 *       nao oferecia acao nenhuma. Hoje tem icone, titulo e o botao primario
 *       "Enviar minha primeira solicitacao" visivel;</li>
 *   <li>§4.7 — o logo Gota+Cruz aparecia 0 vezes dentro do Portal. Hoje esta
 *       na faixa, como marca d'agua ({@code .pc-marca svg}).</li>
 * </ul>
 *
 * <p>Gera screenshots em {@code target/e2e-screenshots/} tambem em viewport
 * de celular, porque o §11 pede revisao visual humana <i>"incluindo em
 * viewport de celular"</i> — este teste nao substitui essa revisao, so
 * garante que ela tenha o que olhar e que o basico nao regrediu.
 *
 * <p>Roda via {@code .\e2e.ps1} / {@code mvn verify -Pe2e} (Failsafe), fora
 * do {@code mvn test} do dia a dia — mesmo padrao de
 * {@link ChatVisualVerificacaoIT}.
 */
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-e2e-redesign;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RedesignVisualSolicitanteIT extends PlaywrightTestBase {

    @Autowired
    private UsuarioService usuarioService;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoOnlineRepository;

    private static final String SENHA_TESTE = "Senha123!";
    private static final String EQUIPE = "Equipe Redesign Visual";

    /** Viewport de celular (iPhone 12/13/14 em CSS px) para o §11. */
    private static final int LARGURA_CELULAR = 390;
    private static final int ALTURA_CELULAR = 844;

    private void criarLoginSolicitante(String username) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setNome(EQUIPE);
        u.setEmail("redesign@example.com");
        u.setPerfil(Perfil.SOLICITANTE);
        u.setEquipeSolicitante(EQUIPE);
        usuarioService.criar(u, SENHA_TESTE);
    }

    /** Tamanho de fonte em px, como o navegador realmente calculou. */
    private static double fontePx(Locator alvo) {
        String valor = (String) alvo.evaluate("el => getComputedStyle(el).fontSize");
        return Double.parseDouble(valor.replace("px", ""));
    }

    /**
     * Fundo que efetivamente pinta algo. Precisa olhar as DUAS propriedades:
     * a variante suave da faixa usa {@code background: var(--saur-surface-info)}
     * (cor solida, cai em {@code backgroundColor}), mas a variante solida usa
     * {@code background: linear-gradient(...)} — e gradiente e
     * {@code backgroundImage}, com {@code backgroundColor} continuando
     * {@code rgba(0, 0, 0, 0)}. Checar so a cor reprovaria a faixa solida do
     * detalhe, que e justamente a que mais pinta na tela.
     */
    private static void assertPinta(Locator alvo, String oQue) {
        String cor = (String) alvo.evaluate("el => getComputedStyle(el).backgroundColor");
        String imagem = (String) alvo.evaluate("el => getComputedStyle(el).backgroundImage");
        boolean corSolida = !"rgba(0, 0, 0, 0)".equals(cor) && !"transparent".equals(cor);
        boolean gradiente = imagem != null && imagem.contains("gradient");
        assertThat(corSolida || gradiente)
            .as(oQue + " precisa ter fundo proprio - cor ou gradiente"
                + " (§4.2: a cor institucional nao entrava no conteudo)."
                + " backgroundColor=" + cor + " backgroundImage=" + imagem)
            .isTrue();
    }

    private void emCelular(Runnable acao) {
        page.setViewportSize(LARGURA_CELULAR, ALTURA_CELULAR);
        try {
            acao.run();
        } finally {
            page.setViewportSize(1440, 1080);
        }
    }

    @Test
    void portalDoSolicitanteMantemORedesignVisualV1aV6() {
        criarLoginSolicitante("sol.redesign");

        try {
            login("sol.redesign", SENHA_TESTE);

            // ===== 1) Primeira tela de um solicitante NOVO: lista vazia =====
            // §4.6 era o achado mais grave do diagnostico: o primeiro contato
            // apos o login era uma tabela vazia e cinza, sem nenhuma acao.
            page.navigate("/solicitante");
            page.waitForLoadState();
            legenda("Primeiro acesso: a lista vazia precisa oferecer a acao obvia (§4.6).");

            // Escopo IMPORTA: a tela tem 3 blocos .estado-vazio (o do filtro sem
            // resultado, que nasce d-none; o da tabela, visivel >=768px; e o dos
            // cards empilhados, visivel <768px). Um .locator(".estado-vazio")
            // solto casa primeiro com o do filtro, que esta escondido - e o
            // waitFor() (que espera VISIBILIDADE) estoura por timeout sem que
            // haja nada errado com a tela.
            Locator estadoVazio = page.locator("#tabelaSolicitacoes .estado-vazio");
            estadoVazio.waitFor();
            assertThat(estadoVazio.isVisible()).isTrue();
            Locator acaoPrimeiraSolicitacao = page.locator("#tabelaSolicitacoes")
                .getByText("Enviar minha primeira solicitação");
            assertThat(acaoPrimeiraSolicitacao.isVisible())
                .as("estado vazio precisa oferecer a acao primaria (§4.6)")
                .isTrue();
            assertThat(estadoVazio.locator(".estado-vazio-icone").isVisible())
                .as("estado vazio desenhado tem icone em token circular (M5)")
                .isTrue();
            screenshot("redesign-01-lista-vazia-primeiro-acesso");

            // A faixa de cabecalho (M1) precisa PINTAR - suave nesta tela.
            Locator faixa = page.locator(".pagina-cabecalho").first();
            assertPinta(faixa, "faixa de cabecalho da lista");
            assertThat(page.locator(".pagina-cabecalho .pc-marca svg").count())
                .as("marca Gota+Cruz dentro do conteudo (§4.7: aparecia 0 vezes)")
                .isGreaterThan(0);

            // Titulo da pagina no degrau de cima da escala (M2, --saur-font-xl).
            double tituloPx = fontePx(page.locator(".pagina-titulo").first());
            assertThat(tituloPx).as("titulo de pagina ~1.75rem (M2)").isGreaterThanOrEqualTo(26.0);

            emCelular(() -> {
                page.waitForTimeout(300);
                screenshot("redesign-02-lista-vazia-CELULAR");
            });

            // ===== 2) Formulario de nova solicitacao (V6) =====
            new PortalSolicitantePage(page).abrirNova();
            page.waitForLoadState();
            assertPinta(page.locator(".pagina-cabecalho").first(), "faixa de cabecalho do formulario");
            assertThat(page.locator(".secao-rotulo").count())
                .as("V6 trocou os 4 <hr> por rotulos de grupo (M2)")
                .isGreaterThan(0);
            screenshot("redesign-03-nova-solicitacao");
            emCelular(() -> {
                page.waitForTimeout(300);
                screenshot("redesign-04-nova-solicitacao-CELULAR");
            });

            // ===== 3) Envia o pedido e volta para a lista COM dados =====
            new PortalSolicitantePage(page)
                .preencher("Paciente Redesign da Silva", "999999999-00003",
                    LocalDate.now(), "Quadro clinico grave (verificacao visual do redesign).")
                .enviar();
            page.waitForLoadState();
            screenshot("redesign-05-lista-com-pedido");

            // ===== 4) Detalhe do pedido: faixa SOLIDA + cartao de resultado =====
            Long solicitacaoId = solicitacaoOnlineRepository.findAll().get(0).getId();
            page.navigate("/solicitante/" + solicitacaoId);
            page.waitForLoadState();
            legenda("Detalhe: faixa solida (M1b) e cartao de resultado (M4/M6).");

            Locator faixaSolida = page.locator(".pagina-cabecalho-solida").first();
            assertThat(faixaSolida.count()).as("detalhe usa a variante solida (decisao 4 do §10)")
                .isGreaterThan(0);
            assertPinta(faixaSolida, "faixa solida do detalhe");

            Locator cartaoResultado = page.locator(".cartao-resultado").first();
            assertThat(cartaoResultado.isVisible())
                .as("cartao de situacao/resultado (M4(i)+M6)").isTrue();
            assertPinta(cartaoResultado, "cartao de resultado (superficie tintada, nao borda de 4px)");
            assertThat(page.locator(".cartao-resultado-icone").first().isVisible())
                .as("icone em token circular de 64px (M4(i))").isTrue();

            // §4.1 - O achado tipografico: titulo de secao TEM que ser maior
            // que o corpo de texto. Antes os dois tinham 1rem.
            Locator secaoTitulo = page.locator(".secao-titulo").first();
            double secaoPx = fontePx(secaoTitulo);
            double corpoPx = fontePx(page.locator("body"));
            assertThat(secaoPx)
                .as("titulo de secao precisa ser MAIOR que o corpo (§4.1: os dois tinham 1rem)")
                .isGreaterThan(corpoPx);
            assertThat(secaoPx).as("titulo de secao ~1.25rem (--saur-font-lg)")
                .isGreaterThanOrEqualTo(19.0);

            // M3 - superficies de apoio deixaram de ser "mais um cartao branco".
            assertThat(page.locator(".superficie-apoio").count())
                .as("M3: dados/timeline viraram superficie de apoio, nao cartao")
                .isGreaterThan(0);

            screenshot("redesign-06-detalhe-em-analise");
            emCelular(() -> {
                page.waitForTimeout(300);
                screenshot("redesign-07-detalhe-em-analise-CELULAR");
            });

        } catch (AssertionError | RuntimeException e) {
            screenshot("redesign-visual-solicitante-FALHA");
            throw e;
        }
    }
}

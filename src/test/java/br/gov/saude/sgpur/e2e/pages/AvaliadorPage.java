package br.gov.saude.sgpur.e2e.pages;

import br.gov.saude.sgpur.e2e.Legenda;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Page Object do Portal do Avaliador (/avaliador): o medico se autentica e
 * vota diretamente no processo, sem passar pelo operador. Reflete o fluxo
 * real da Fase 1 do portal (origem AVALIADOR_SISTEMA).
 */
public class AvaliadorPage {

    private final Page page;

    public AvaliadorPage(Page page) {
        this.page = page;
    }

    private void narrar(String texto) {
        Legenda.mostrar(page, texto);
    }

    /** Abre a tela de voto para um processo especifico e vai direto ao formulario. */
    public AvaliadorPage abrirVotacao(Long processoId) {
        page.navigate("/avaliador/" + processoId);
        narrar("Avaliador autenticado revisando o processo para emitir o parecer...");
        return this;
    }

    /**
     * Locator do primeiro &lt;iframe&gt; que embute um PDF anonimizado diretamente
     * na tela de votacao (visualizacao sem download, ver votar.html). Usado pelo
     * teste para confirmar que o material carrega inline em vez de exigir um
     * download. O titulo de cada iframe inclui o nome do arquivo (unico por
     * anexo) - o seletor usa "comeca com" (^=) e .first() porque um processo
     * pode ter mais de um PDF anexado, e um locator estatico casaria varios
     * elementos (Playwright "strict mode violation" em chamadas como .click()).
     *
     * <p><b>O texto do title E ACENTUADO</b> ("Visualiza&ccedil;&atilde;o..."),
     * como todo texto visivel dos dois portais desde a Fase 8 de acentuacao.
     * Este seletor ficou com a versao sem acento por um tempo e casava ZERO
     * elementos - {@code .first().isVisible()} devolve {@code false} em vez de
     * estourar, entao a quebra so aparecia como um assert falso, sem dizer que
     * o seletor e que estava errado. Corrigido em 2026-08-08; se a acentuacao
     * do template mudar de novo, este seletor tem que acompanhar.
     */
    public Locator materialInline() {
        return page.locator("iframe[title^='Visualização do processo anonimizado']").first();
    }

    /** Locator do botao de confirmacao final dentro do modal de ciencia do voto. */
    public Locator botaoConfirmarModal() {
        return page.locator("#btnConfirmarVotoFinal");
    }

    /** Locator do checkbox "li e confirmo" dentro do modal de ciencia do voto. */
    public Locator checkboxConfirmaModal() {
        return page.locator("#checkConfirmaVoto");
    }

    /**
     * Preenche o resultado (FAVORAVEL / NAO_FAVORAVEL / SOLICITA_INFORMACAO) e a
     * justificativa opcional, e clica em "Registrar meu voto" - o que abre o
     * modal de confirmacao (nao envia o formulario ainda, ver
     * {@link #confirmarNoModal()} e avaliador-votar.js).
     */
    public AvaliadorPage preencherEAbrirConfirmacao(String resultado, String justificativa) {
        narrar("Preenchendo o parecer: " + resultado + "...");
        page.locator("#resultado_" + resultado).check();
        if (justificativa != null && !justificativa.isBlank()) {
            page.locator("textarea[name=justificativa]").fill(justificativa);
        }
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Registrar meu voto")).click();
        page.locator("#modalConfirmarVoto.show").waitFor();
        return this;
    }

    /**
     * Marca o checkbox de ciencia e clica na confirmacao final do modal - so
     * entao o voto e de fato enviado (definitivo, sem edicao posterior).
     */
    public AvaliadorPage confirmarNoModal() {
        narrar("Confirmando o voto definitivo no modal de ciencia...");
        checkboxConfirmaModal().check();
        botaoConfirmarModal().click();
        page.waitForLoadState();
        return this;
    }

    /**
     * Fluxo completo: preenche, abre o modal e confirma. Usado quando o teste
     * nao precisa inspecionar o estado intermediario do modal.
     */
    public AvaliadorPage votar(String resultado, String justificativa) {
        return preencherEAbrirConfirmacao(resultado, justificativa).confirmarNoModal();
    }
}

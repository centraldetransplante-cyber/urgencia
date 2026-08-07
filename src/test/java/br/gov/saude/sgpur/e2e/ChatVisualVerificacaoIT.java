package br.gov.saude.sgpur.e2e;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.e2e.pages.AvaliadorPage;
import br.gov.saude.sgpur.e2e.pages.PortalSolicitantePage;
import br.gov.saude.sgpur.e2e.pages.ProcessoDetalhePage;
import br.gov.saude.sgpur.e2e.pages.SolicitacoesOnlineTriagemPage;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.service.UsuarioService;
import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.FilePayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verificacao visual de VERDADE (browser real, nao leitura de codigo) dos 3
 * chats do sistema, motivada por um bug relatado em producao em 2026-08-07:
 * "chat com falhas tanto com solicitante quanto com membro votante. O CSS
 * do chat do operador com membro esta horrivel, botao de enviar esta no
 * lado esquerdo" + mensagens do operador nao chegando visivelmente ao
 * avaliador (card "Duvida sobre este processo" nascia sempre recolhido).
 *
 * <p>Cobre, na mesma sessao de browser real:
 * <ol>
 *   <li>o botao "Enviar" do chat operador&lt;-&gt;avaliador (dentro da tabela de
 *       pareceres em /processos/{id}) fica ao lado do campo de texto, nunca
 *       quebrado para uma linha abaixo/a esquerda;</li>
 *   <li>uma mensagem enviada pelo operador chega visivelmente ao avaliador
 *       (card ja nasce expandido quando ha conversa) sem exigir nenhum
 *       clique manual para expandir;</li>
 *   <li>o toast de "mensagem nova" e clicavel (classe
 *       toast-sgpur-clicavel) nos 2 lados da conversa;</li>
 *   <li>o chat operador&lt;-&gt;solicitante continua funcionando ponta a ponta.</li>
 * </ol>
 *
 * <p>Roda via ".\e2e.ps1"/"mvn verify -Pe2e" (Failsafe, fora do "mvn test"
 * do dia a dia), mesmo padrao de {@link FluxoCompletoProcessoIT}.
 */
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-e2e-chat;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ChatVisualVerificacaoIT extends PlaywrightTestBase {

    @Autowired
    private MembroUrgenciaRenalRepository membroRepository;
    @Autowired
    private UsuarioService usuarioService;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoOnlineRepository;

    private static final String SENHA_TESTE = "Senha123!";

    private static byte[] pdf(String texto) {
        Document doc = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph(texto));
        doc.close();
        return out.toByteArray();
    }

    private static FilePayload pdfPayload(String nome, String texto) {
        return new FilePayload(nome, "application/pdf", pdf(texto));
    }

    private void criarLoginAvaliador(String username, MembroUrgenciaRenal membro) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setNome(membro.getNome());
        u.setPerfil(Perfil.AVALIADOR);
        usuarioService.criar(u, SENHA_TESTE, membro.getId());
    }

    private void criarLoginSolicitante(String username, String equipe, String email) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setNome(equipe);
        u.setEmail(email);
        u.setPerfil(Perfil.SOLICITANTE);
        u.setEquipeSolicitante(equipe);
        usuarioService.criar(u, SENHA_TESTE);
    }

    private static Long extrairIdDaUrl(String url) {
        String semQuery = url.split("[?#]")[0];
        String[] partes = semQuery.split("/");
        return Long.parseLong(partes[partes.length - 1]);
    }

    @Test
    void chatOperadorAvaliadorFicaAlinhadoENotificaComToastClicavel() {
        List<MembroUrgenciaRenal> medicos = membroRepository.findByAtivoTrueOrderByInstituicaoAsc();
        assertThat(medicos).hasSize(3);
        List<Long> medicoIds = medicos.stream().map(MembroUrgenciaRenal::getId).toList();
        MembroUrgenciaRenal medico1 = medicos.get(0);
        criarLoginAvaliador("avaliador.chat.1", medico1);
        criarLoginSolicitante("solicitante.chat", "Equipe Teste Chat", "solicitante.chat@example.com");

        try {
            // ===== Solicitante envia o pedido (todo Processo nasce assim) =====
            Page janelaSolicitante = novoAtor();
            login(janelaSolicitante, "solicitante.chat", SENHA_TESTE);
            new PortalSolicitantePage(janelaSolicitante)
                .abrirNova()
                .preencher("Paciente Chat da Silva", "999999999-00002",
                    LocalDate.now(), "Quadro clinico grave (verificacao visual do chat).");
            janelaSolicitante.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Enviar solicitação")).click();
            janelaSolicitante.waitForURL("**/solicitante");

            // ===== Operador: converte, registra envio (3 pareceres pendentes) =====
            login("admin", "Admin123!");
            ProcessoDetalhePage detalhe = new SolicitacoesOnlineTriagemPage(page)
                .abrir()
                .abrirPrimeiraPendente()
                .revisarEConverter()
                .preencher("02/2026", LocalDate.now(),
                    "Paciente Chat da Silva", "999999999-00002",
                    "Equipe Teste Chat", "solicitante.chat@example.com")
                .selecionarMedicos(medicoIds)
                .cadastrar();
            Long processoId = extrairIdDaUrl(page.url());
            detalhe
                .passo1_anexarDocumentoClinico(pdfPayload("laudo.pdf", "Laudo clinico anonimizado"))
                .passo1_registrarEnvio();

            // ===== 1) Operador abre a conversa com o 1o avaliador e verifica o
            //          alinhamento do botao "Enviar" (causa raiz do bug relatado:
            //          .table td form .form-control forcava width:100%, o botao
            //          quebrava para a linha de baixo). =====
            page.navigate("/processos/" + processoId);
            page.waitForLoadState();
            Locator botaoConversa = page.locator("button:has-text('Conversa')").first();
            botaoConversa.scrollIntoViewIfNeeded();
            botaoConversa.click();
            Locator threadOperador = page.locator(".chat-avaliador-thread.show").first();
            threadOperador.waitFor();
            Locator inputOperador = threadOperador.locator("input[name='texto']");
            Locator botaoEnviarOperador = threadOperador.locator("button[type=submit]");
            BoundingBox boxInput = inputOperador.boundingBox();
            BoundingBox boxBotao = botaoEnviarOperador.boundingBox();
            assertThat(boxInput).isNotNull();
            assertThat(boxBotao).isNotNull();
            // Mesma linha (nao quebrou para baixo) e o botao fica À DIREITA do
            // campo de texto - exatamente o sintoma relatado invertido.
            assertThat(Math.abs(boxInput.y - boxBotao.y)).as("botao na mesma linha do campo").isLessThan(5);
            assertThat(boxBotao.x).as("botao Enviar a DIREITA do campo de texto").isGreaterThan(boxInput.x);
            screenshot("chat-operador-avaliador-alinhado");

            // Confirma tambem via CSS computado que o campo nao esta mais
            // sendo forcado a width:100% dentro do .input-group da tabela.
            String display = (String) inputOperador.evaluate(
                "el => getComputedStyle(el.closest('.input-group')).flexWrap");
            assertThat(display).isEqualTo("wrap"); // continua wrap (Bootstrap padrao) -
            // o que mudou foi o campo parar de reivindicar 100% da largura.
            String larguraInput = (String) inputOperador.evaluate("el => getComputedStyle(el).width");
            String larguraContainer = (String) inputOperador.evaluate(
                "el => getComputedStyle(el.closest('.input-group')).width");
            double wInput = Double.parseDouble(larguraInput.replace("px", ""));
            double wContainer = Double.parseDouble(larguraContainer.replace("px", ""));
            assertThat(wInput).as("campo nao ocupa mais 100% do container (regressao do bug)")
                .isLessThan(wContainer * 0.95);

            // Operador manda mensagem real para o avaliador.
            inputOperador.fill("Ola doutor, pode confirmar se o PDF abriu certo?");
            botaoEnviarOperador.click();
            page.waitForTimeout(500);

            // ===== 2) Avaliador abre /avaliador/{id}: o card de chat deve nascer
            //          JA EXPANDIDO (existe conversa) e a mensagem do operador
            //          deve estar visivel SEM nenhum clique manual. =====
            Page janelaMedico1 = novoAtor();
            login(janelaMedico1, "avaliador.chat.1", SENHA_TESTE);
            new AvaliadorPage(janelaMedico1).abrirVotacao(processoId);
            janelaMedico1.waitForTimeout(1000); // deixa o 1o poll do chat rodar
            Locator corpoChatAval = janelaMedico1.locator("#chatBodyAvaliador");
            boolean jaExpandido = (Boolean) corpoChatAval.evaluate("el => el.classList.contains('show')");
            assertThat(jaExpandido).as("card do avaliador nasce expandido quando ja existe conversa").isTrue();
            screenshot(janelaMedico1, "avaliador-chat-expandido-com-mensagem");
            assertThat(janelaMedico1.locator("#chatBoxAval").locator(".mb-2, .mb-3")
                .filter(new Locator.FilterOptions().setHasText("Ola doutor")).first()
                .isVisible()).isTrue();

            // Avaliador responde - confirma o alinhamento do botao tambem nesta
            // tela (chat fora de tabela, mas mesma verificacao de sanidade).
            Locator inputAval = janelaMedico1.locator("#chatFormAval input[name='texto']");
            Locator botaoEnviarAval = janelaMedico1.locator("#chatFormAval button[type=submit]");
            BoundingBox boxInputAval = inputAval.boundingBox();
            BoundingBox boxBotaoAval = botaoEnviarAval.boundingBox();
            assertThat(Math.abs(boxInputAval.y - boxBotaoAval.y)).isLessThan(5);
            assertThat(boxBotaoAval.x).isGreaterThan(boxInputAval.x);
            inputAval.fill("Sim, abriu perfeitamente, obrigado!");
            botaoEnviarAval.click();
            janelaMedico1.context().close();

            // ===== 3) De volta ao operador (pagina ja aberta, poll de 5s rodando):
            //          confirma que aparece um toast CLICAVEL avisando da resposta,
            //          sem precisar recarregar a pagina. =====
            // O toast some sozinho 5s depois de aparecer - esperar um tempo fixo
            // arbitrario arrisca checar DEPOIS dele ja ter sumido (dependendo de
            // onde o ciclo de poll de 5s estava quando a mensagem foi enviada).
            // waitFor() ativo aguarda o elemento aparecer, com folga para 2-3
            // ciclos de poll.
            Locator toastClicavel = page.locator(".toast-sgpur-clicavel");
            toastClicavel.first().waitFor(new Locator.WaitForOptions().setTimeout(15000));
            assertThat(toastClicavel.first().isVisible()).isTrue();
            screenshot("operador-toast-clicavel-resposta-avaliador");
            // Clicar no toast nao pode gerar erro JS nenhum (scrollIntoView +
            // expandir o collapse, ver chat-solicitacao.js:irParaOChat).
            List<String> errosPagina = new java.util.ArrayList<>();
            page.onPageError(errosPagina::add);
            toastClicavel.first().click();
            page.waitForTimeout(500);
            assertThat(errosPagina).isEmpty();

            // ===== 4) Chat operador<->solicitante continua ok (round trip). =====
            Long solicitacaoId = solicitacaoOnlineRepository.findIdByProcessoGeradoId(processoId)
                .orElseThrow();
            page.locator("#chatForm input[name='texto']").fill(
                "Recebido, aguarde o parecer dos avaliadores.");
            page.locator("#chatForm button[type=submit]").click();
            page.waitForTimeout(500);

            Page janelaSolicitante2 = novoAtor();
            login(janelaSolicitante2, "solicitante.chat", SENHA_TESTE);
            janelaSolicitante2.navigate("/solicitante/" + solicitacaoId);
            janelaSolicitante2.waitForTimeout(1000);
            screenshot(janelaSolicitante2, "solicitante-chat-recebendo-mensagem-operador");
            assertThat(janelaSolicitante2.locator("#chatBox")
                .filter(new Locator.FilterOptions().setHasText("Recebido, aguarde")).first()
                .isVisible()).isTrue();
            Locator inputSol = janelaSolicitante2.locator("#chatForm input[name='texto']");
            Locator botaoEnviarSol = janelaSolicitante2.locator("#chatForm button[type=submit]");
            BoundingBox boxInputSol = inputSol.boundingBox();
            BoundingBox boxBotaoSol = botaoEnviarSol.boundingBox();
            assertThat(Math.abs(boxInputSol.y - boxBotaoSol.y)).isLessThan(5);
            assertThat(boxBotaoSol.x).isGreaterThan(boxInputSol.x);
            janelaSolicitante2.context().close();

        } catch (AssertionError | RuntimeException e) {
            screenshot("chat-visual-verificacao-falha");
            throw e;
        }
    }
}

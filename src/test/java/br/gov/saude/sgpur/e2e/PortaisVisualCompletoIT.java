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
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validação visual manual (sessão de consolidação de 2026-08-08) dos dois
 * Portais redesenhados recentemente — Solicitante (PR #75, V1-V6) e
 * Avaliador (PR #77) — em DUAS resoluções (desktop 1440x900 e celular
 * 390x844) e cobrindo os 3 estados reais do Portal do Solicitante que os
 * testes visuais anteriores (ver {@link RedesignVisualSolicitanteIT}) não
 * exercitavam: DEFERIDO e INDEFERIDO (só "em andamento" tinha guarda
 * automatizada até agora).
 *
 * <p>Este teste NÃO substitui a inspeção visual humana pedida pelo dono do
 * produto — ele só gera, de forma reprodutível, os screenshots de cada tela
 * relevante em {@code target/e2e-screenshots/} para essa inspeção (feita à
 * parte, lendo cada PNG com a ferramenta de leitura de imagem). Sem
 * asserções de pixel/CSS aqui de propósito (isso já é papel de
 * {@link RedesignVisualSolicitanteIT}) — só asserções de navegação/estado
 * (chegou na tela certa, com o resultado certo), para o teste falhar cedo
 * se o fluxo quebrar antes de gerar os screenshots.
 *
 * <p>Cobre dois processos completos, com voto real dos avaliadores no
 * Portal (nunca lançado pelo operador):
 * <ul>
 *   <li>Processo A: 2 avaliadores votam FAVORÁVEL → DEFERIDO (maioria
 *       simples automática) → comprovante SNT anexado → resposta enviada;</li>
 *   <li>Processo B: 2 avaliadores votam NÃO FAVORÁVEL → INDEFERIDO → ofício
 *       de indeferimento anexado → resposta enviada.</li>
 * </ul>
 * Usado para capturar o Portal do Avaliador "com pendência" (antes dos dois
 * processos decidirem) e "sem pendência" (depois, quando os dois já estão
 * finalizados - qualquer avaliador serve para a captura "sem pendência",
 * mesmo tendo votado num dos dois processos, ver comentário no corpo do
 * teste).
 *
 * <p><b>Escolha deliberada de quem vota em cada processo.</b> O primeiro
 * membro devolvido por {@code findByAtivoTrueOrderByInstituicaoAsc()} é
 * sempre o Coordenador CET-RS (ver {@code MembroDevSeed}, "CET-RS" vem
 * primeiro em ordem alfabética de instituição) - um voto FAVORÁVEL dele
 * sozinho já defere o processo (regra de negócio real, ver
 * "coordenador defere sozinho" no CLAUDE.md), sem esperar o 2º parecer. Se
 * ele fosse um dos 2 votantes do Processo A (DEFERIDO), a 2ª janela tentaria
 * acessar {@code /avaliador/{id}} de um processo que JÁ FOI decidido pelo
 * voto solo do coordenador - e levaria um 403 genuíno ("Acesso não
 * permitido"), não um bug de timing (achado real depurado nesta sessão,
 * inicialmente com hipótese errada de contenção de recursos/concorrência de
 * janelas - descartada depois de imprimir o HTML da página no momento da
 * falha). Por isso o Processo A é decidido pelos dois membros QUE NÃO são o
 * coordenador (maioria simples "normal", 2 de 3); o coordenador só vota no
 * Processo B (indeferir, onde ele não tem peso especial - a regra do
 * indeferimento sempre exige 2 votos, mesmo dele).
 *
 * <p>Roda via {@code .\e2e.ps1} / {@code mvn verify -Pe2e} (Failsafe), fora
 * do {@code mvn test} do dia a dia — mesmo padrão de
 * {@link FluxoCompletoProcessoIT}.
 */
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-e2e-visual-completo;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PortaisVisualCompletoIT extends PlaywrightTestBase {

    @Autowired
    private MembroUrgenciaRenalRepository membroRepository;
    @Autowired
    private UsuarioService usuarioService;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoOnlineRepository;

    private static final String SENHA_TESTE = "Senha123!";
    private static final int LARGURA_CELULAR = 390;
    private static final int ALTURA_CELULAR = 844;
    private static final int LARGURA_DESKTOP = 1440;
    private static final int ALTURA_DESKTOP = 900;

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

    /**
     * Tira o screenshot da tela ATUAL em desktop (1440x900) e depois em
     * celular (390x844), devolvendo a página ao viewport de desktop ao
     * final. Chame sempre numa página que NÃO vai ser usada para interação
     * (preencher/clicar) logo em seguida - abra uma aba própria para isso
     * quando precisar (ver uso em "tela de votar" abaixo) e fechá-la depois.
     */
    private void capturarNasDuasResolucoes(Page alvo, String nomeBase) {
        alvo.setViewportSize(LARGURA_DESKTOP, ALTURA_DESKTOP);
        alvo.waitForTimeout(200);
        screenshot(alvo, nomeBase + "-desktop-1440x900");

        alvo.setViewportSize(LARGURA_CELULAR, ALTURA_CELULAR);
        alvo.waitForTimeout(200);
        screenshot(alvo, nomeBase + "-mobile-390x844");

        alvo.setViewportSize(LARGURA_DESKTOP, ALTURA_DESKTOP);
    }

    /** Vota num processo com um avaliador, numa janela própria fechada ao final. */
    private void votarComoAvaliador(String username, Long processoId, String resultado, String justificativa) {
        Page janela = novoAtor();
        login(janela, username, SENHA_TESTE);
        new AvaliadorPage(janela).abrirVotacao(processoId).votar(resultado, justificativa);
        janela.context().close();
    }

    @Test
    void validaVisualmenteOsDoisPortaisNosEstadosDeferidoEIndeferido() {
        List<MembroUrgenciaRenal> medicos = membroRepository.findByAtivoTrueOrderByInstituicaoAsc();
        assertThat(medicos).hasSize(3);
        List<Long> medicoIds = medicos.stream().map(MembroUrgenciaRenal::getId).toList();
        MembroUrgenciaRenal medico1 = medicos.get(0);
        MembroUrgenciaRenal medico2 = medicos.get(1);
        MembroUrgenciaRenal medico3 = medicos.get(2);
        String avaliador1 = "avaliador.visual.1";
        String avaliador2 = "avaliador.visual.2";
        String avaliador3 = "avaliador.visual.3";
        criarLoginAvaliador(avaliador1, medico1);
        criarLoginAvaliador(avaliador2, medico2);
        criarLoginAvaliador(avaliador3, medico3);
        criarLoginSolicitante("solicitante.visual.a", "Equipe Visual A", "visual.a@example.com");
        criarLoginSolicitante("solicitante.visual.b", "Equipe Visual B", "visual.b@example.com");

        try {
            // ============================================================
            // PORTAL DO SOLICITANTE - lista vazia + formulario de nova
            // solicitacao (comuns aos dois fluxos, capturados so uma vez
            // com o solicitante A) + envio do pedido A.
            // ============================================================
            Page janelaSolA = novoAtor();
            login(janelaSolA, "solicitante.visual.a", SENHA_TESTE);
            janelaSolA.navigate("/solicitante");
            janelaSolA.waitForLoadState();
            legenda("Portal do Solicitante: lista vazia (primeiro acesso).");
            capturarNasDuasResolucoes(janelaSolA, "portal-solicitante-01-lista-vazia");

            new PortalSolicitantePage(janelaSolA).abrirNova();
            janelaSolA.waitForLoadState();
            legenda("Portal do Solicitante: formulario de nova solicitacao.");
            capturarNasDuasResolucoes(janelaSolA, "portal-solicitante-02-nova-solicitacao");

            new PortalSolicitantePage(janelaSolA)
                .preencher("Paciente Visual Deferido", "111111111-00001",
                    LocalDate.now(), "Quadro clinico grave - fluxo visual DEFERIDO.")
                .enviar();
            janelaSolA.waitForLoadState();
            legenda("Portal do Solicitante: lista com pedido em andamento.");
            capturarNasDuasResolucoes(janelaSolA, "portal-solicitante-03-lista-em-andamento");

            Long solicitacaoIdA = solicitacaoOnlineRepository.findAllByOrderByDataEnvioDesc().get(0).getId();
            janelaSolA.navigate("/solicitante/" + solicitacaoIdA);
            janelaSolA.waitForLoadState();
            legenda("Portal do Solicitante: detalhe do pedido em andamento (aguardando decisao).");
            capturarNasDuasResolucoes(janelaSolA, "portal-solicitante-04-detalhe-em-andamento");
            assertThat(janelaSolA.locator("body").innerText()).contains("Paciente Visual Deferido");
            janelaSolA.context().close();

            // ============================================================
            // OPERADOR converte a solicitacao A em processo e registra o
            // envio aos 3 avaliadores.
            // ============================================================
            login(page, "admin", "Admin123!");
            assertThat(page.url()).doesNotContain("/login");

            ProcessoDetalhePage detalheA = new SolicitacoesOnlineTriagemPage(page)
                .abrir()
                .abrirPrimeiraPendente()
                .revisarEConverter()
                .preencher("01/2026", LocalDate.now(),
                    "Paciente Visual Deferido", "111111111-00001",
                    "Equipe Visual A", "visual.a@example.com")
                .selecionarMedicos(medicoIds)
                .cadastrar();
            Long processoIdA = extrairIdDaUrl(page.url());
            detalheA
                .passo1_anexarDocumentoClinico(pdfPayload("laudo-a.pdf", "Laudo clinico A"))
                .passo1_registrarEnvio();
            assertThat(detalheA.passoConcluido(1)).isTrue();

            // ============================================================
            // PORTAL DO AVALIADOR - lista COM pendencia (medico3, captura
            // antes de qualquer decisao - ele vai votar no processo A mais
            // adiante, mas isso nao importa para esta captura) e tela de
            // votar (medico1/coordenador, so exibida - ele nao vota no
            // processo A, ver javadoc da classe sobre o coordenador).
            // ============================================================
            Page janelaMed3 = novoAtor();
            login(janelaMed3, avaliador3, SENHA_TESTE);
            janelaMed3.navigate("/avaliador");
            janelaMed3.waitForLoadState();
            legenda("Portal do Avaliador: lista COM pendencia de voto.");
            capturarNasDuasResolucoes(janelaMed3, "portal-avaliador-01-lista-com-pendencia");
            assertThat(janelaMed3.locator("body").innerText()).doesNotContain("Nenhum parecer pendente");
            janelaMed3.context().close();

            Page janelaVotar = novoAtor();
            login(janelaVotar, avaliador1, SENHA_TESTE);
            new AvaliadorPage(janelaVotar).abrirVotacao(processoIdA);
            janelaVotar.waitForLoadState();
            legenda("Portal do Avaliador: tela de votar (antes de registrar o parecer).");
            capturarNasDuasResolucoes(janelaVotar, "portal-avaliador-02-tela-de-votar");
            janelaVotar.context().close();

            // ===== Processo A: 2 votos FAVORAVEL (avaliador2 + avaliador3,
            // NUNCA o coordenador - ver javadoc da classe) -> DEFERIDO
            // automatico por maioria simples "normal" =====
            votarComoAvaliador(avaliador2, processoIdA, "FAVORAVEL",
                "Quadro compativel com a urgencia renal alegada.");
            votarComoAvaliador(avaliador3, processoIdA, "FAVORAVEL",
                "Concordo com a avaliacao anterior.");

            page.navigate("/processos/" + processoIdA);
            page.waitForLoadState();
            assertThat(detalheA.passoConcluido(2)).isTrue();
            assertThat(detalheA.passoConcluido(3)).isTrue();
            detalheA.passo4_anexarComprovanteSnt(
                pdfPayload("comprovante-snt-a.pdf", "Comprovante de insercao no SNT - processo A"));
            // Sem assert de passoConcluido(4): a etapa so fica concluida com
            // o e-mail final REALMENTE enviado (ProcessoService.
            // finalizarResposta), e este ambiente local nao tem
            // SGPUR_MAIL_USER/SGPUR_MAIL_FROM configurados - mesma limitacao
            // documentada no CLAUDE.md para FluxoCompletoProcessoIT. A
            // decisao (DEFERIDO) e o anexo do comprovante SNT ja aconteceram
            // antes deste clique e nao dependem do envio do e-mail.
            detalheA.passo4_confirmarRespostaAoSolicitante();

            Page janelaSolADepois = novoAtor();
            login(janelaSolADepois, "solicitante.visual.a", SENHA_TESTE);
            janelaSolADepois.navigate("/solicitante/" + solicitacaoIdA);
            janelaSolADepois.waitForLoadState();
            legenda("Portal do Solicitante: detalhe do pedido DEFERIDO.");
            capturarNasDuasResolucoes(janelaSolADepois, "portal-solicitante-05-detalhe-DEFERIDO");
            assertThat(janelaSolADepois.locator("body").innerText()).containsIgnoringCase("Deferido");
            janelaSolADepois.context().close();

            // ============================================================
            // Processo B: solicitante B, 2 votos NAO FAVORAVEL -> INDEFERIDO.
            // ============================================================
            Page janelaSolB = novoAtor();
            login(janelaSolB, "solicitante.visual.b", SENHA_TESTE);
            new PortalSolicitantePage(janelaSolB)
                .abrirNova()
                .preencher("Paciente Visual Indeferido", "222222222-00002",
                    LocalDate.now(), "Quadro clinico - fluxo visual INDEFERIDO.")
                .enviar();
            janelaSolB.waitForLoadState();
            Long solicitacaoIdB = solicitacaoOnlineRepository.findAllByOrderByDataEnvioDesc().get(0).getId();
            janelaSolB.context().close();

            page.navigate("/processos/solicitacoes-online");
            ProcessoDetalhePage detalheB = new SolicitacoesOnlineTriagemPage(page)
                .abrir()
                .abrirPrimeiraPendente()
                .revisarEConverter()
                .preencher("02/2026", LocalDate.now(),
                    "Paciente Visual Indeferido", "222222222-00002",
                    "Equipe Visual B", "visual.b@example.com")
                .selecionarMedicos(medicoIds)
                .cadastrar();
            Long processoIdB = extrairIdDaUrl(page.url());
            detalheB
                .passo1_anexarDocumentoClinico(pdfPayload("laudo-b.pdf", "Laudo clinico B"))
                .passo1_registrarEnvio();

            votarComoAvaliador(avaliador1, processoIdB, "NAO_FAVORAVEL",
                "Achados clinicos nao sustentam a urgencia alegada.");
            votarComoAvaliador(avaliador2, processoIdB, "NAO_FAVORAVEL",
                "Concordo: sem indicacao de urgencia.");

            page.navigate("/processos/" + processoIdB);
            page.waitForLoadState();
            assertThat(detalheB.passoConcluido(2)).isTrue();
            assertThat(detalheB.passoConcluido(3)).isTrue();
            detalheB.passo4_anexarOficioIndeferimento(
                pdfPayload("oficio-indeferimento-b.pdf", "Oficio de indeferimento assinado - processo B"));
            // Mesma ressalva de SMTP local do processo A logo acima - sem
            // assert de passoConcluido(4) aqui tambem.
            detalheB.passo4_confirmarRespostaAoSolicitante();

            Page janelaSolBDepois = novoAtor();
            login(janelaSolBDepois, "solicitante.visual.b", SENHA_TESTE);
            janelaSolBDepois.navigate("/solicitante/" + solicitacaoIdB);
            janelaSolBDepois.waitForLoadState();
            legenda("Portal do Solicitante: detalhe do pedido INDEFERIDO.");
            capturarNasDuasResolucoes(janelaSolBDepois, "portal-solicitante-06-detalhe-INDEFERIDO");
            assertThat(janelaSolBDepois.locator("body").innerText()).containsIgnoringCase("Indeferido");
            janelaSolBDepois.context().close();

            // ============================================================
            // PORTAL DO AVALIADOR - lista SEM pendencia: qualquer avaliador
            // mostra 0 pendentes aqui porque os DOIS processos ja estao
            // decididos (o filtro de pendencia so considera processo com
            // status ENVIADO/SOLICITA_INFORMACAO) - nao importa se este
            // avaliador especifico chegou a votar ou nao em cada um.
            // ============================================================
            Page janelaMed3Depois = novoAtor();
            login(janelaMed3Depois, avaliador3, SENHA_TESTE);
            janelaMed3Depois.navigate("/avaliador");
            janelaMed3Depois.waitForLoadState();
            legenda("Portal do Avaliador: lista SEM pendencia (ambos os processos ja decididos).");
            capturarNasDuasResolucoes(janelaMed3Depois, "portal-avaliador-03-lista-sem-pendencia");
            janelaMed3Depois.context().close();
        } catch (AssertionError | RuntimeException e) {
            screenshot("portais-visual-completo-FALHA");
            throw e;
        }
    }

    private static Long extrairIdDaUrl(String url) {
        String semQuery = url.split("\\?")[0];
        String[] partes = semQuery.split("/");
        return Long.parseLong(partes[partes.length - 1]);
    }
}

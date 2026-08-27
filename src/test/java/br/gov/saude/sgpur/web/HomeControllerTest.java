package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import br.gov.saude.sgpur.service.TempoRespostaService;
import br.gov.saude.sgpur.service.dto.EstadoEtapa;
import br.gov.saude.sgpur.service.dto.EtapaFluxo;
import br.gov.saude.sgpur.web.dto.PainelLinha;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Year;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do HomeController: /login (publico) e o Painel (/), que agrega
 * contadores do ano corrente, pendencias (FluxoProcessoService), a planilha
 * (PainelLinha) e o indicador de tempo de resposta dos avaliadores.
 */
@WebMvcTest(HomeController.class)
class HomeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoRepository processoRepository;
    @MockitoBean private MembroUrgenciaRenalService membroService;
    @MockitoBean private FluxoProcessoService fluxoService;
    @MockitoBean private TempoRespostaService tempoRespostaService;
    // F2 do relatorio de vistoria de brechas (2026-08-10): HomeController
    // agora calcula "qual regra decidiu" por processo (badge do voto unico
    // do Coordenador CET-RS no Painel). Sem stub explicito o Mockito devolve
    // null - o template trata null com seguranca (fragment badgeRegraDecisao).
    @MockitoBean private ProcessoValidator processoValidator;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas duas pro
    // contexto do @WebMvcTest subir - ver ArquivoControllerTest.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private SolicitacaoOnlineService solicitacaoOnlineService;

    private static Processo processo(Long id, Integer sequencial, StatusProcesso status) {
        Processo p = new Processo();
        p.setId(id);
        p.setNumero("0" + sequencial + "/2026");
        p.setSequencial(sequencial);
        p.setStatus(status);
        return p;
    }

    // GET /login nao e testavel neste slice @WebMvcTest: sem o SecurityConfig
    // real (nao carregado aqui), a seguranca autoconfigurada do Spring Boot
    // gera sua PROPRIA pagina de login padrao em /login (DefaultLoginPageGeneratingFilter),
    // que intercepta a requisicao antes dela chegar em HomeController.login() -
    // mesmo com @WithMockUser. O comportamento real (permitAll + view "login"
    // do proprio controller) ja e coberto por SecurityIntegrationTest.loginEhPublico(),
    // que roda com @SpringBootTest e o SecurityConfig completo.

    @Test
    @WithMockUser(roles = "OPERADOR")
    void painelContaOsStatusDoAnoCorrenteCorretamente() throws Exception {
        int ano = Year.now().getValue();
        List<Processo> processos = List.of(
            processo(1L, 1, StatusProcesso.DEFERIDO),
            processo(2L, 2, StatusProcesso.INDEFERIDO),
            processo(3L, 3, StatusProcesso.CANCELADO),
            processo(4L, 4, StatusProcesso.ENVIADO),
            processo(5L, 5, StatusProcesso.SOLICITADO));
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(processos);
        when(fluxoService.resumoPendencia(org.mockito.ArgumentMatchers.any())).thenReturn("Falta algo");
        when(membroService.contarAtivos()).thenReturn(3L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("dashboard"))
            .andExpect(model().attribute("anoCorrente", ano))
            .andExpect(model().attribute("totalProcessos", 5))
            .andExpect(model().attribute("deferidos", 1L))
            .andExpect(model().attribute("indeferidos", 1L))
            .andExpect(model().attribute("cancelados", 1L))
            .andExpect(model().attribute("emAndamento", 2L))
            .andExpect(model().attribute("membrosAtivos", 3L));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void painelExpoeAContagemDeDeferidosSemComprovanteSnt() throws Exception {
        // Pendencia que antes nao entrava em contador nenhum: um Deferido sem
        // comprovante SNT bloqueia a resposta oficial ao solicitante.
        int ano = Year.now().getValue();
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of());
        when(processoRepository.contarDeferidosSemComprovanteSnt()).thenReturn(3L);
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("deferidosSemComprovanteSnt", 3L));
    }

    /**
     * Um processo DECIDIDO nao e um processo pronto: o oficio, o comprovante
     * SNT e a resposta ao solicitante continuam pendentes depois da decisao.
     * O Painel calculava "o que falta" so para {@code isEmAndamento()}, entao
     * esses ficavam com a celula vazia - e, ate a correcao do badge, ainda
     * rotulados "Encerrado" (bug relatado em producao no processo 04/2026).
     * Quem NAO tem nada pendente continua sem texto nenhum.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void pendenciasTambemAparecemParaProcessoDecididoQueAindaTemEtapasAFazer() throws Exception {
        int ano = Year.now().getValue();
        Processo emAndamento = processo(1L, 1, StatusProcesso.ENVIADO);
        Processo decididoIncompleto = processo(2L, 2, StatusProcesso.DEFERIDO);
        Processo concluido = processo(3L, 3, StatusProcesso.INDEFERIDO);
        when(processoRepository.findByAnoComPareceres(ano))
            .thenReturn(List.of(emAndamento, decididoIncompleto, concluido));
        EtapaFluxo etapaDecisao = new EtapaFluxo(EtapaFluxo.Chave.DECISAO, "Decisão final", "hammer",
            EstadoEtapa.ATUAL, "aguardando os pareceres");
        EtapaFluxo etapaSnt = new EtapaFluxo(EtapaFluxo.Chave.COMPROVANTE_SNT, "Comprovante SNT", "clipboard2-check-fill",
            EstadoEtapa.ATUAL, "anexe o comprovante");
        when(fluxoService.pendenciaAberta(emAndamento)).thenReturn(java.util.Optional.of(etapaDecisao));
        when(fluxoService.pendenciaAberta(decididoIncompleto)).thenReturn(java.util.Optional.of(etapaSnt));
        when(fluxoService.pendenciaAberta(concluido)).thenReturn(java.util.Optional.empty());
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("pendencias", Map.of(
                1L, etapaDecisao,
                2L, etapaSnt)))
            // O contador "em andamento" nao muda: decidido continua fora dele.
            .andExpect(model().attribute("emAndamento", 1L));
    }

    /**
     * Relatorio de clareza (2026-08-05), item 5.1: a celula da pendencia
     * mostra so o titulo curto da etapa - a frase completa (o que falta de
     * verdade) fica so no title, para nao competir por espaco na tabela do
     * Painel. Renderiza o template de verdade.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void pendenciaNaCelulaDoPainelMostraSoOTituloCurtoEAFraseCompletaNoTitle() throws Exception {
        int ano = Year.now().getValue();
        Processo p = processo(1L, 1, StatusProcesso.ENVIADO);
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of(p));
        when(fluxoService.pendenciaAberta(p)).thenReturn(java.util.Optional.of(
            new EtapaFluxo(EtapaFluxo.Chave.ENVIO, "Envio aos 3 médicos", "send-fill",
                EstadoEtapa.ATUAL, "Anexe o(s) documento(s) clinico(s) (PDF) para gerar o processo dos avaliadores.")));
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        String html = mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).contains(">Envio aos 3 médicos<");
        org.assertj.core.api.Assertions.assertThat(html).contains(
            "title=\"Envio aos 3 médicos: Anexe o(s) documento(s) clinico(s) (PDF) para gerar o processo dos avaliadores.\"");
    }

    /**
     * Badge do Painel: "Encerrado" (preto) so quando a resposta ao solicitante
     * ja saiu; enquanto faltar etapa de conclusao e "Decisao tomada" (cinza).
     * A distincao foi criada em 2026-08-04 na tela de detalhe e nao chegou ao
     * Painel nem a lista - dai o processo aparecer como encerrado com etapas
     * pendentes. Renderiza o template de verdade.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void painelMostraDecisaoTomadaEnquantoFaltaRespostaAoSolicitante() throws Exception {
        int ano = Year.now().getValue();
        Processo deferidoSemResposta = processo(4L, 4, StatusProcesso.DEFERIDO);
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of(deferidoSemResposta));
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Decisão tomada")))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Encerrado"))));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void painelMostraEncerradoDepoisQueARespostaAoSolicitanteFoiEnviada() throws Exception {
        int ano = Year.now().getValue();
        Processo concluido = processo(4L, 4, StatusProcesso.DEFERIDO);
        concluido.setEmailEnviadoSolicitante(true);
        Processo cancelado = processo(5L, 5, StatusProcesso.CANCELADO);
        when(processoRepository.findByAnoComPareceres(ano))
            .thenReturn(List.of(concluido, cancelado));
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        String html = mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // Cancelado tambem conta como encerrado: nao passa pela resposta formal.
        org.assertj.core.api.Assertions.assertThat(html).contains("Encerrado");
        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("Decisão tomada");
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void indicadorDeTempoDentroDoPrazoQuandoMediaMenorOuIgualAoPrazo() throws Exception {
        int ano = Year.now().getValue();
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of());
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(10, 5.0, 1, 7, Map.of()));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("tempoDentroPrazo", true))
            .andExpect(model().attribute("mediaGeralTempoTexto", TempoRespostaService.formatarDias(5.0)))
            .andExpect(model().attribute("prazoDiasTempo", 7));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void indicadorDeTempoForaDoPrazoQuandoMediaMaiorQueOPrazo() throws Exception {
        int ano = Year.now().getValue();
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of());
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(10, 9.0, 4, 7, Map.of()));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("tempoDentroPrazo", false));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void indicadorDeTempoConsideraDentroDoPrazoQuandoNaoHaMediaAinda() throws Exception {
        int ano = Year.now().getValue();
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of());
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("tempoDentroPrazo", true))
            .andExpect(model().attribute("mediaGeralTempoTexto", "—"));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void processosEmAndamentoAparecemAntesDosFinalizadosNaPlanilha() throws Exception {
        int ano = Year.now().getValue();
        // Ordem de chegada do repositorio: finalizado primeiro, em andamento depois -
        // o controller deve reordenar (em andamento primeiro).
        Processo finalizado = processo(1L, 1, StatusProcesso.DEFERIDO);
        Processo emAndamento = processo(2L, 2, StatusProcesso.ENVIADO);
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of(finalizado, emAndamento));
        when(fluxoService.resumoPendencia(emAndamento)).thenReturn("Falta algo");
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        // O controller nao expoe a lista "processos" bruta ao model - o reflexo
        // observavel da reordenacao e a planilha "linhas" (PainelLinha::de,
        // aplicado sobre a mesma lista ja reordenada).
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("linhas",
                List.of(PainelLinha.de(emAndamento), PainelLinha.de(finalizado))));
    }

    /**
     * Achado 4 do RELATORIO-STATUS-PROCESSO-12-2026-2026-08-11.md:
     * {@code StatusProcesso.descricao} nao pode ser acentuado (alimenta PDF
     * oficial/dossie/auditoria), entao a acentuacao vem do fragment
     * {@code layout :: statusProcessoTexto} - este teste confirma que o HTML
     * de verdade (nao o enum) mostra "Solicita informação" acentuado, no
     * lugar do "Solicita informacao" cru que o enum devolve.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void badgeDeStatusDoPainelMostraSolicitaInformacaoAcentuado() throws Exception {
        int ano = Year.now().getValue();
        Processo pausado = processo(1L, 1, StatusProcesso.SOLICITA_INFORMACAO);
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of(pausado));
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        String html = mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).contains("Solicita informação");
        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("Solicita informacao<");
    }

    /**
     * Bug real relatado pelo dono do produto em produção (2026-08-12): o
     * cabeçalho da tabela do Painel (dentro de
     * {@code .dashboard-tabela-scroll}, que rola por dentro com
     * {@code overflow-y:auto}) tinha {@code sticky-top}. Como as linhas têm
     * altura MUITO variável (1 a 4 linhas de conteúdo por célula — status,
     * pendência, badge do coordenador, comprovante SNT etc.), o cabeçalho
     * fixo "fatiava" visualmente a linha que passava por baixo dele durante
     * o scroll: um pedacinho de badge (ou do botão "Abrir") ficava solto
     * bem na borda do cabeçalho, parecendo colidir com ele e com a linha
     * seguinte — reproduzido visualmente (Playwright) antes da correção, e
     * confirmado que sumia por completo ao remover o {@code sticky-top}.
     * Guarda barata: falha se o `<thead>` do Painel ganhar `sticky-top` de
     * novo (nenhuma outra tela do sistema usa esse padrão).
     */
    /**
     * Cobertura do badge de tipo (RotuloProcesso.tipoCurto) - correcao de
     * 2026-08-27 (continuacao do PR #126/#127): a linha de cada processo na
     * tabela do Painel nao mostrava se era preemptivo (so existia em
     * processos/lista.html, uma tela diferente). O titulo GERAL "Painel da
     * Urgência Renal" continua o mesmo - decisao de produto ja fixada, nao
     * mexida aqui.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void linhaDaTabelaMostraBadgeDePreemptivoQuandoOProcessoForPreemptivo() throws Exception {
        int ano = Year.now().getValue();
        Processo preemptivo = processo(1L, 1, StatusProcesso.SOLICITADO);
        preemptivo.setPreemptivo(true);
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of(preemptivo));
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        String html = mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).contains("Preemptivo");
        org.assertj.core.api.Assertions.assertThat(html).contains("Painel da Urgência Renal");
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void linhaDaTabelaNaoMostraBadgeDePreemptivoParaProcessoComum() throws Exception {
        int ano = Year.now().getValue();
        Processo comum = processo(1L, 1, StatusProcesso.SOLICITADO);
        comum.setPreemptivo(false);
        when(processoRepository.findByAnoComPareceres(ano)).thenReturn(List.of(comum));
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        String html = mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("Preemptivo");
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void cabecalhoDaTabelaDoPainelNaoUsaStickyTop() throws Exception {
        when(processoRepository.findByAnoComPareceres(org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(List.of());
        when(membroService.contarAtivos()).thenReturn(0L);
        when(tempoRespostaService.calcular()).thenReturn(
            new TempoRespostaService.ResumoTempo(0, null, 0, 7, Map.of()));

        String html = mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("<thead class=\"sticky-top\"");
    }
}

package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do ProcessoListaController: listagem paginada de /processos, com
 * filtros de busca/status e o resumo de pendencia por processo (reusa
 * FluxoProcessoService, a mesma regra do checklist do detalhe).
 */
@WebMvcTest(ProcessoListaController.class)
class ProcessoListaControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoService processoService;
    @MockitoBean private FluxoProcessoService fluxoService;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas duas pro
    // contexto do @WebMvcTest subir - ver ArquivoControllerTest.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private SolicitacaoOnlineService solicitacaoOnlineService;

    private Processo processo;

    @BeforeEach
    void setUp() {
        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("01/2026");
        processo.setStatus(StatusProcesso.ENVIADO);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaSemFiltrosUsaPaginaZeroETamanho15() throws Exception {
        Page<Processo> pagina = new PageImpl<>(List.of(processo), PageRequest.of(0, 15), 1);
        when(processoService.buscar(isNull(), isNull(), eq(PageRequest.of(0, 15)))).thenReturn(pagina);
        br.gov.saude.sgpur.service.dto.EtapaFluxo etapaEnvio = new br.gov.saude.sgpur.service.dto.EtapaFluxo(
            br.gov.saude.sgpur.service.dto.EtapaFluxo.Chave.ENVIO, "Envio aos 3 médicos", "send-fill",
            br.gov.saude.sgpur.service.dto.EstadoEtapa.ATUAL, "Falta o Envio");
        when(fluxoService.pendenciaAberta(processo)).thenReturn(java.util.Optional.of(etapaEnvio));

        mvc.perform(get("/processos"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/lista"))
            .andExpect(model().attribute("processos", List.of(processo)))
            .andExpect(model().attribute("paginaAtual", 0))
            .andExpect(model().attribute("totalPaginas", 1))
            .andExpect(model().attribute("pendencias", org.hamcrest.Matchers.hasEntry(1L, etapaEnvio)));
    }

    /**
     * Relatorio de clareza (2026-08-05), item 5.1: a celula da coluna "O que
     * falta" mostra so o titulo curto da etapa - a frase completa fica no
     * title, sem empurrar as demais colunas da tabela. Renderiza o template
     * de verdade.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void colunaOQueFaltaMostraSoOTituloCurtoEAFraseCompletaNoTitle() throws Exception {
        Page<Processo> pagina = new PageImpl<>(List.of(processo), PageRequest.of(0, 15), 1);
        when(processoService.buscar(isNull(), isNull(), eq(PageRequest.of(0, 15)))).thenReturn(pagina);
        when(fluxoService.pendenciaAberta(processo)).thenReturn(java.util.Optional.of(
            new br.gov.saude.sgpur.service.dto.EtapaFluxo(
                br.gov.saude.sgpur.service.dto.EtapaFluxo.Chave.ENVIO, "Envio aos 3 médicos", "send-fill",
                br.gov.saude.sgpur.service.dto.EstadoEtapa.ATUAL,
                "Anexe o(s) documento(s) clinico(s) (PDF) para gerar o processo dos avaliadores.")));

        String html = mvc.perform(get("/processos"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).contains(">Envio aos 3 médicos<");
        org.assertj.core.api.Assertions.assertThat(html).contains(
            "title=\"Envio aos 3 médicos: Anexe o(s) documento(s) clinico(s) (PDF) para gerar o processo dos avaliadores.\"");
        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("Nada pendente");
    }

    /** Processo sem nenhuma pendencia aberta cai no fallback "Nada pendente". */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void processoSemPendenciaAbertaMostraNadaPendente() throws Exception {
        Page<Processo> pagina = new PageImpl<>(List.of(processo), PageRequest.of(0, 15), 1);
        when(processoService.buscar(isNull(), isNull(), eq(PageRequest.of(0, 15)))).thenReturn(pagina);
        when(fluxoService.pendenciaAberta(processo)).thenReturn(java.util.Optional.empty());

        String html = mvc.perform(get("/processos"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).contains("Nada pendente");
    }

    /**
     * Mesma distincao da tela de detalhe e do Painel (fragment
     * {@code layout :: badgeEncerramento}): decidido mas ainda devendo
     * oficio/comprovante SNT/resposta e "Decisao tomada", nao "Encerrado" -
     * so vira "Encerrado" quando a resposta ao solicitante ja saiu. Renderiza
     * o template de verdade.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaDistingueDecisaoTomadaDeProcessoRealmenteEncerrado() throws Exception {
        Processo decididoIncompleto = new Processo();
        decididoIncompleto.setId(2L);
        decididoIncompleto.setNumero("04/2026");
        decididoIncompleto.setStatus(StatusProcesso.DEFERIDO);
        Processo encerrado = new Processo();
        encerrado.setId(3L);
        encerrado.setNumero("05/2026");
        encerrado.setStatus(StatusProcesso.DEFERIDO);
        encerrado.setEmailEnviadoSolicitante(true);
        Page<Processo> pagina = new PageImpl<>(List.of(decididoIncompleto, encerrado),
            PageRequest.of(0, 15), 2);
        when(processoService.buscar(isNull(), isNull(), any())).thenReturn(pagina);

        String html = mvc.perform(get("/processos"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).contains("Decisão tomada");
        org.assertj.core.api.Assertions.assertThat(html).contains("Encerrado");
    }

    /**
     * F2 do relatorio de vistoria de brechas (2026-08-10) - Achado 6: o
     * badge do voto unico do Coordenador CET-RS (RegraDecisao) so existia
     * na tela de detalhe. Renderiza a lista de verdade e confere que o
     * badge aparece so no processo decidido pela excecao do coordenador.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaMostraBadgeDeRegraDecisaoApenasNoProcessoDecididoPeloCoordenador() throws Exception {
        Processo peloCoordenador = new Processo();
        peloCoordenador.setId(2L);
        peloCoordenador.setNumero("04/2026");
        peloCoordenador.setStatus(StatusProcesso.DEFERIDO);
        Page<Processo> pagina = new PageImpl<>(List.of(processo, peloCoordenador), PageRequest.of(0, 15), 2);
        when(processoService.buscar(isNull(), isNull(), any())).thenReturn(pagina);
        when(processoService.regraAplicada(processo))
            .thenReturn(br.gov.saude.sgpur.service.dto.RegraDecisao.NAO_DECIDIDO);
        when(processoService.regraAplicada(peloCoordenador))
            .thenReturn(br.gov.saude.sgpur.service.dto.RegraDecisao.VOTO_COORDENADOR);

        String html = mvc.perform(get("/processos"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).contains("Voto único do Coordenador CET-RS");
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void filtraPorTermoDeBuscaEStatus() throws Exception {
        Page<Processo> pagina = new PageImpl<>(List.of(processo), PageRequest.of(0, 15), 1);
        when(processoService.buscar(eq("Maria"), eq(StatusProcesso.ENVIADO), any())).thenReturn(pagina);

        mvc.perform(get("/processos").param("q", "Maria").param("status", "ENVIADO"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("q", "Maria"))
            .andExpect(model().attribute("statusSelecionado", StatusProcesso.ENVIADO));

        verify(processoService).buscar("Maria", StatusProcesso.ENVIADO, PageRequest.of(0, 15));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void paginaNegativaEhTratadaComoZero() throws Exception {
        Page<Processo> paginaVazia = new PageImpl<>(List.of(), PageRequest.of(0, 15), 0);
        when(processoService.buscar(isNull(), isNull(), eq(PageRequest.of(0, 15)))).thenReturn(paginaVazia);

        mvc.perform(get("/processos").param("page", "-5"))
            .andExpect(status().isOk());

        verify(processoService).buscar(null, null, PageRequest.of(0, 15));
    }

    // ----- pendencia de comprovante SNT (badge + filtro) -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaExpoeOsIdsDeferidosSemComprovanteSntParaOBadge() throws Exception {
        Page<Processo> pagina = new PageImpl<>(List.of(processo), PageRequest.of(0, 15), 1);
        when(processoService.buscar(isNull(), isNull(), any())).thenReturn(pagina);
        when(processoService.idsDeferidosSemComprovanteSnt()).thenReturn(java.util.Set.of(1L, 9L));

        mvc.perform(get("/processos"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("idsSemComprovanteSnt", java.util.Set.of(1L, 9L)))
            .andExpect(model().attribute("totalSemComprovanteSnt", 2))
            .andExpect(model().attribute("filtroSntPendente", false));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void filtroSntPendenteListaSomenteOsDeferidosSemComprovante() throws Exception {
        Processo deferidoSemComprovante = new Processo();
        deferidoSemComprovante.setId(9L);
        deferidoSemComprovante.setNumero("09/2026");
        deferidoSemComprovante.setStatus(StatusProcesso.DEFERIDO);
        when(processoService.listarDeferidosSemComprovanteSnt())
            .thenReturn(List.of(deferidoSemComprovante));
        when(processoService.idsDeferidosSemComprovanteSnt()).thenReturn(java.util.Set.of(9L));

        mvc.perform(get("/processos").param("filtro", "snt-pendente"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/lista"))
            .andExpect(model().attribute("processos", List.of(deferidoSemComprovante)))
            .andExpect(model().attribute("filtroSntPendente", true))
            .andExpect(model().attribute("totalPaginas", 1));

        // O filtro nao passa pela busca paginada normal.
        verify(processoService, org.mockito.Mockito.never()).buscar(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void expoeTodosOsValoresDeStatusParaOFiltro() throws Exception {
        Page<Processo> pagina = new PageImpl<>(List.of(), PageRequest.of(0, 15), 0);
        when(processoService.buscar(isNull(), isNull(), any())).thenReturn(pagina);

        mvc.perform(get("/processos"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("statusValores", StatusProcesso.values()));
    }
}

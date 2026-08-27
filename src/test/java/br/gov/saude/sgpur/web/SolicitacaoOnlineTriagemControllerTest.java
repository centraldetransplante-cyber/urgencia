package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoSolicitacaoOnlineStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do SolicitacaoOnlineTriagemController (fila de triagem do
 * operador/admin): lista, detalhe, redirecionamento de conversao e devolucao.
 * A restricao de rota por role (so ADMIN/OPERADOR, ver SecurityConfig -
 * "/processos/**" hasAnyRole ADMIN,OPERADOR) e coberta em
 * SecurityIntegrationTest (contexto completo), mesmo padrao adotado para os
 * demais controllers deste modulo - este slice cobre so a logica do
 * controller.
 */
@WebMvcTest(SolicitacaoOnlineTriagemController.class)
class SolicitacaoOnlineTriagemControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private SolicitacaoOnlineService service;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private MensagemSolicitacaoService mensagemService;
    @MockitoBean private UsuarioRepository usuarioRepo;
    @MockitoBean private ParecerRepository parecerRepo;
    @MockitoBean private AnexoSolicitacaoOnlineRepository anexoRepo;
    @MockitoBean private AnexoSolicitacaoOnlineStorageService anexoStorage;

    private SolicitacaoOnline solicitacao;

    @BeforeEach
    void setUp() {
        solicitacao = new SolicitacaoOnline();
        solicitacao.setId(50L);
        solicitacao.setPacienteNome("Fulano de Tal");
        solicitacao.setPacienteRgct("123456789-12345");
        solicitacao.setDataSituacaoEspecial(LocalDate.now());
        solicitacao.setJustificativaClinica("Quadro grave.");
        solicitacao.setStatus(StatusSolicitacaoOnline.ENVIADA);

        Usuario operador = new Usuario();
        operador.setId(1L);
        operador.setUsername("user");
        when(usuarioRepo.findByUsername("user")).thenReturn(Optional.of(operador));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaExibeSolicitacoesPendentesDeTriagem() throws Exception {
        when(service.listarPendentesTriagem(null)).thenReturn(List.of(solicitacao));
        when(service.diasEspera(solicitacao)).thenReturn(new SolicitacaoOnlineService.DiasEspera(2, "bg-secondary"));

        mvc.perform(get("/processos/solicitacoes-online"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/solicitacoes-online-lista"))
            .andExpect(model().attribute("solicitacoes", List.of(solicitacao)))
            .andExpect(model().attribute("filtro", "pendentes"))
            .andExpect(model().attribute("q", (Object) null));
    }

    /**
     * RotuloProcesso.tipoCurto(SolicitacaoOnline) - correcao de continuacao
     * do PR #126 ("paciente preemptivo"): a fila de triagem do operador nao
     * mostrava se a solicitacao era Preemptiva, o metodo existia mas nunca
     * era chamado por nenhum template.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaMostraBadgeDePreemptivoQuandoASolicitacaoForPreemptiva() throws Exception {
        solicitacao.setPreemptivo(true);
        when(service.listarPendentesTriagem(null)).thenReturn(List.of(solicitacao));
        when(service.diasEspera(solicitacao)).thenReturn(new SolicitacaoOnlineService.DiasEspera(2, "bg-secondary"));

        mvc.perform(get("/processos/solicitacoes-online"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Preemptivo")));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaNaoMostraBadgeDePreemptivoParaSolicitacaoComum() throws Exception {
        solicitacao.setPreemptivo(false);
        when(service.listarPendentesTriagem(null)).thenReturn(List.of(solicitacao));
        when(service.diasEspera(solicitacao)).thenReturn(new SolicitacaoOnlineService.DiasEspera(2, "bg-secondary"));

        String html = mvc.perform(get("/processos/solicitacoes-online"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html).doesNotContain("Preemptivo");
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaComFiltroTodasExibeTodasAsSolicitacoes() throws Exception {
        when(service.listarTodas(null)).thenReturn(List.of(solicitacao));
        when(service.diasEspera(solicitacao)).thenReturn(new SolicitacaoOnlineService.DiasEspera(2, "bg-secondary"));

        mvc.perform(get("/processos/solicitacoes-online").param("filtro", "todas"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/solicitacoes-online-lista"))
            .andExpect(model().attribute("solicitacoes", List.of(solicitacao)))
            .andExpect(model().attribute("filtro", "todas"));

        verify(service, never()).listarPendentesTriagem(any());
    }

    /**
     * A busca (item 5 do docs/RELATORIO-UI-INTERACAO-AVANCADA-2026-08.md) e
     * resolvida no banco (SolicitacaoOnlineRepository.buscarPorStatus/
     * buscarTodas) - aqui so confirmamos que o termo digitado chega ao
     * servico certo conforme a aba (pendentes vs todas) e volta ao model.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaComTermoDeBuscaRepassaAoServicoCorretoConformeAAba() throws Exception {
        when(service.listarPendentesTriagem("fulano")).thenReturn(List.of(solicitacao));
        when(service.diasEspera(solicitacao)).thenReturn(new SolicitacaoOnlineService.DiasEspera(2, "bg-secondary"));

        mvc.perform(get("/processos/solicitacoes-online").param("q", "fulano"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("solicitacoes", List.of(solicitacao)))
            .andExpect(model().attribute("q", "fulano"));

        verify(service, never()).listarPendentesTriagem((String) null);
        verify(service, never()).listarTodas(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void detalheExibeASolicitacao() throws Exception {
        when(service.buscarParaDetalhe(50L)).thenReturn(solicitacao);
        when(service.nomeSolicitante(50L)).thenReturn("Santa Casa - Nefro");

        mvc.perform(get("/processos/solicitacoes-online/50"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/solicitacoes-online-detalhe"))
            .andExpect(model().attribute("solicitacao", solicitacao))
            .andExpect(model().attribute("nomeSolicitante", "Santa Casa - Nefro"))
            // Cabecalho do card de chat mostra o nome real, nao o literal
            // generico "Conversa com o solicitante" (correcao de 2026-08-08).
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Santa Casa - Nefro")));
    }

    /**
     * Mesma correcao (RotuloProcesso.tipoCurto/rotuloJustificativa) para a
     * tela de detalhe da triagem: badge de tipo no cabecalho + rotulo
     * condicional do campo "Justificativa clinica".
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void detalhePreemptivoMostraBadgeERotuloDeJustificativaPreemptiva() throws Exception {
        solicitacao.setPreemptivo(true);
        solicitacao.setPacienteRgct(null);
        when(service.buscarParaDetalhe(50L)).thenReturn(solicitacao);
        when(service.nomeSolicitante(50L)).thenReturn("Santa Casa - Nefro");

        mvc.perform(get("/processos/solicitacoes-online/50"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Preemptivo")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Por que a inserção preemptiva se aplica")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void detalheComumMostraRotuloDeJustificativaDeUrgenciaSemBadge() throws Exception {
        solicitacao.setPreemptivo(false);
        when(service.buscarParaDetalhe(50L)).thenReturn(solicitacao);
        when(service.nomeSolicitante(50L)).thenReturn("Santa Casa - Nefro");

        String html = mvc.perform(get("/processos/solicitacoes-online/50"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
            .contains("Por que a urgência se aplica")
            .doesNotContain("Preemptivo");
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void converterRedirecionaParaNovoProcessoComOrigemPreenchida() throws Exception {
        mvc.perform(get("/processos/solicitacoes-online/50/converter"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/novo?origemSolicitacaoOnlineId=50"));

        // Nao verifica "sem interacoes" puro: o GlobalModelAdvice global chama
        // service.contarPendentesTriagem() (badge da navbar) para qualquer
        // requisicao autenticada como OPERADOR/ADMIN, mesmo nesta rota. O que
        // importa aqui e que o controller em si nao chamou nenhum metodo de
        // busca/conversao da solicitacao (o redirect e so uma URL montada).
        verify(service, never()).buscar(any());
        verify(service, never()).converter(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void devolverComSucessoRedirecionaComMensagem() throws Exception {
        when(service.buscar(50L)).thenReturn(solicitacao);
        doNothing().when(service).devolver(eq(50L), anyString());

        mvc.perform(post("/processos/solicitacoes-online/50/devolver")
                .param("observacoes", "Falta documento clinico.")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/solicitacoes-online"))
            .andExpect(flash().attributeExists("msg"));

        verify(service).devolver(50L, "Falta documento clinico.");
        org.mockito.ArgumentCaptor<String> detalheCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(eq("SOLICITACAO_ONLINE_DEVOLVIDA"), detalheCaptor.capture());
        String detalhe = detalheCaptor.getValue();
        // solicitacao: pacienteNome = "Fulano de Tal" (RGCT "123456789-12345")
        org.assertj.core.api.Assertions.assertThat(detalhe)
            .doesNotContain("Fulano")
            .doesNotContain("Tal")
            .doesNotContain("123456789-12345")
            .contains("50")
            .contains("F.T.");
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void devolverComErroDeNegocioRedirecionaComFlashDeErro() throws Exception {
        when(service.buscar(50L)).thenReturn(solicitacao);
        doThrow(new IllegalStateException("Esta solicitacao ja foi triada."))
            .when(service).devolver(eq(50L), anyString());

        mvc.perform(post("/processos/solicitacoes-online/50/devolver")
                .param("observacoes", "motivo")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/solicitacoes-online/50"))
            .andExpect(flash().attribute("erro", "Esta solicitacao ja foi triada."));

        verify(auditoria, never()).registrar(eq("SOLICITACAO_ONLINE_DEVOLVIDA"), any());
    }
}

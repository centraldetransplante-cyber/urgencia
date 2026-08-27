package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.ProcessoValidator;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do ArquivoController: listagem read-only e PAGINADA dos processos
 * ENCERRADOS (Deferido/Indeferido/Cancelado).
 *
 * <p><b>Mudanca de 2026-08-04 (Fase E da auditoria de UI).</b> A busca deixou de
 * ser feita em Java sobre a lista inteira e passou a ser resolvida no banco, com
 * paginacao. Por isso os testes daqui mudaram de natureza: com o repositorio
 * mockado nao ha mais como verificar o FILTRO em si (o mock devolve o que se
 * mandar devolver) - o que se verifica aqui e o CONTRATO, ou seja, que o
 * controller repassa termo, status e pagina corretos e publica os atributos de
 * paginacao. O filtro de verdade e coberto por
 * {@code ArquivoBuscaPaginadaIntegrationTest}, com H2 real.</p>
 */
@WebMvcTest(ArquivoController.class)
class ArquivoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoRepository processoRepository;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas para o
    // contexto do @WebMvcTest subir, mesmo sem serem chamadas (usuario OPERADOR
    // nao tem ROLE_AVALIADOR, entao pendentesAvaliador() curto-circuita em 0).
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private SolicitacaoOnlineService solicitacaoOnlineService;
    // F2 do relatorio de vistoria de brechas (2026-08-10): ArquivoController
    // agora calcula "qual regra decidiu" por processo (Achado 6). Sem stub
    // explicito o Mockito devolve null - o template trata null com
    // seguranca (fragment badgeRegraDecisao).
    @MockitoBean private ProcessoValidator processoValidator;

    private static Processo processo(Long id, String numero, String paciente,
                                      String equipe, StatusProcesso status) {
        Processo p = new Processo();
        p.setId(id);
        p.setNumero(numero);
        p.setPacienteNome(paciente);
        p.setSolicitanteEquipe(equipe);
        p.setStatus(status);
        return p;
    }

    private void devolve(Processo... ps) {
        Page<Processo> pagina = new PageImpl<>(List.of(ps), PageRequest.of(0, 15), ps.length);
        when(processoRepository.buscarEncerrados(any(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(pagina);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaOsEncerradosDoRepositorio() throws Exception {
        Processo deferido = processo(1L, "01/2026", "Maria Silva", "Equipe A", StatusProcesso.DEFERIDO);
        devolve(deferido);

        mvc.perform(get("/arquivo"))
            .andExpect(status().isOk())
            .andExpect(view().name("arquivo/lista"))
            .andExpect(model().attribute("processos", List.of(deferido)))
            .andExpect(model().attribute("q", (Object) null));
    }

    /** So os 3 status finais entram no Arquivo - nunca um processo em andamento. */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void consultaApenasOsTresStatusEncerrados() throws Exception {
        devolve();

        mvc.perform(get("/arquivo")).andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<StatusProcesso>> status =
            ArgumentCaptor.forClass(java.util.Collection.class);
        verify(processoRepository).buscarEncerrados(any(), status.capture(), anyBoolean(), anyBoolean(), any());
        assertThat(status.getValue()).containsExactlyInAnyOrder(
            StatusProcesso.DEFERIDO, StatusProcesso.INDEFERIDO, StatusProcesso.CANCELADO);
    }

    /**
     * RotuloProcesso.tipoCurto(Processo) - correcao de continuacao do PR
     * #126 ("paciente preemptivo"): o Arquivo (processos encerrados) nao
     * mostrava se o processo era preemptivo, mesmo padrao visual do badge
     * ja usado em processos/lista.html.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaMostraBadgeDePreemptivoQuandoOProcessoForPreemptivo() throws Exception {
        Processo preemptivo = processo(2L, "P-01/2026", "Fulano", "Equipe B", StatusProcesso.DEFERIDO);
        preemptivo.setPreemptivo(true);
        devolve(preemptivo);

        mvc.perform(get("/arquivo"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Preemptivo")));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void listaNaoMostraBadgeDePreemptivoParaProcessoComum() throws Exception {
        Processo comum = processo(3L, "01/2026", "Ciclano", "Equipe C", StatusProcesso.INDEFERIDO);
        comum.setPreemptivo(false);
        devolve(comum);

        String html = mvc.perform(get("/arquivo"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain(">Preemptivo</span>");
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void repassaOTermoDeBuscaAoRepositorio() throws Exception {
        devolve(processo(1L, "01/2026", "Maria Silva", "Equipe A", StatusProcesso.DEFERIDO));

        mvc.perform(get("/arquivo").param("q", "maria"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("q", "maria"));

        verify(processoRepository).buscarEncerrados(eq("maria"), any(), anyBoolean(), anyBoolean(), any());
    }

    /**
     * Paginacao: a pagina pedida chega ao repositorio e o tamanho e o mesmo de
     * /processos, para as duas telas se comportarem igual.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void repassaAPaginaPedidaComOTamanhoPadrao() throws Exception {
        devolve();

        mvc.perform(get("/arquivo").param("page", "2")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(processoRepository).buscarEncerrados(any(), any(), anyBoolean(), anyBoolean(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(ArquivoController.TAMANHO_PAGINA);
    }

    /** Pagina negativa na URL nao pode estourar - vira a primeira pagina. */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void paginaNegativaCaiParaAPrimeira() throws Exception {
        devolve();

        mvc.perform(get("/arquivo").param("page", "-5")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(processoRepository).buscarEncerrados(any(), any(), anyBoolean(), anyBoolean(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void publicaOsAtributosDePaginacaoParaAView() throws Exception {
        Page<Processo> pagina = new PageImpl<>(
            List.of(processo(1L, "01/2026", "Maria Silva", "Equipe A", StatusProcesso.DEFERIDO)),
            PageRequest.of(1, 15), 40);
        when(processoRepository.buscarEncerrados(any(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(pagina);

        mvc.perform(get("/arquivo").param("page", "1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("paginaAtual", 1))
            .andExpect(model().attribute("totalPaginas", 3))
            .andExpect(model().attribute("totalEncerrados", 40L));
    }
}

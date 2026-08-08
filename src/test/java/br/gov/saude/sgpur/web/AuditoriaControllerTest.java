package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.LogAuditoria;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do AuditoriaController (/auditoria): agrupamento dos registros por
 * dia, atributos de paginacao no model e a view retornada. Restricao de
 * acesso a ADMIN e responsabilidade do SecurityConfig (coberta a parte) -
 * aqui usamos @WithMockUser so para satisfazer qualquer checagem incidental
 * de autenticacao, nao para validar autorizacao.
 */
@WebMvcTest(AuditoriaController.class)
class AuditoriaControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuditoriaService auditoria;
    // GlobalModelAdvice (@ControllerAdvice global) exige estes dois beans em
    // qualquer slice @WebMvcTest, mesmo quando o controller sob teste nao os usa.
    @MockitoBean
    private UsuarioRepository usuarioRepository;
    @MockitoBean
    private ParecerRepository parecerRepository;
    @MockitoBean
    private SolicitacaoOnlineService solicitacaoOnlineService;

    private LogAuditoria logAr(String usuario, String acao, String detalhe, LocalDateTime dataHora) {
        LogAuditoria log = new LogAuditoria(usuario, acao, detalhe);
        log.setDataHora(dataHora);
        return log;
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void listarRetornaViewCorreta() throws Exception {
        Pageable pageable = PageRequest.of(0, 30);
        Page<LogAuditoria> vazio = new PageImpl<>(List.of(), pageable, 0);
        when(auditoria.buscar(any(), any(), any(), any(), any())).thenReturn(vazio);

        mvc.perform(get("/auditoria"))
                .andExpect(status().isOk())
                .andExpect(view().name("auditoria/lista"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void listarSemParametroUsaPaginaZero() throws Exception {
        Pageable pageable = PageRequest.of(0, 30);
        Page<LogAuditoria> vazio = new PageImpl<>(List.of(), pageable, 0);
        when(auditoria.buscar(any(), any(), any(), any(), eq(pageable))).thenReturn(vazio);

        mvc.perform(get("/auditoria"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("paginaAtual", 0))
                .andExpect(model().attribute("totalPaginas", 0));

        verify(auditoria).buscar(any(), any(), any(), any(), eq(pageable));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void listarRepassaParametroDePaginaAoServico() throws Exception {
        Pageable pageable = PageRequest.of(2, 30);
        Page<LogAuditoria> pagina = new PageImpl<>(List.of(), pageable, 100);
        when(auditoria.buscar(any(), any(), any(), any(), eq(pageable))).thenReturn(pagina);

        mvc.perform(get("/auditoria").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("paginaAtual", 2));

        verify(auditoria).buscar(any(), any(), any(), any(), eq(pageable));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void listarComPaginaNegativaEhTratadaComoZero() throws Exception {
        Pageable pageable = PageRequest.of(0, 30);
        Page<LogAuditoria> vazio = new PageImpl<>(List.of(), pageable, 0);
        when(auditoria.buscar(any(), any(), any(), any(), eq(pageable))).thenReturn(vazio);

        mvc.perform(get("/auditoria").param("page", "-5"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("paginaAtual", 0));

        // Math.max(page, 0) - nunca deve pedir uma pagina negativa ao servico.
        verify(auditoria).buscar(any(), any(), any(), any(), eq(pageable));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void listarAgrupaRegistrosPorDiaPreservandoOrdemMaisRecentePrimeiro() throws Exception {
        LogAuditoria log1 = logAr("admin", "LOGIN", "ok", LocalDateTime.of(2026, 7, 21, 10, 0));
        LogAuditoria log2 = logAr("operador1", "PROCESSO_CADASTRADO", "Processo 01/2026",
                LocalDateTime.of(2026, 7, 21, 9, 0));
        LogAuditoria log3 = logAr("admin", "LOGIN", "ok", LocalDateTime.of(2026, 7, 20, 15, 0));

        Pageable pageable = PageRequest.of(0, 30);
        Page<LogAuditoria> pagina = new PageImpl<>(List.of(log1, log2, log3), pageable, 3);
        when(auditoria.buscar(any(), any(), any(), any(), eq(pageable))).thenReturn(pagina);

        mvc.perform(get("/auditoria"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("gruposPorDia"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("21/07/2026")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("20/07/2026")));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void listarComRepositorioVazioMostraMensagemDeNenhumRegistro() throws Exception {
        Pageable pageable = PageRequest.of(0, 30);
        Page<LogAuditoria> vazio = new PageImpl<>(List.of(), pageable, 0);
        when(auditoria.buscar(any(), any(), any(), any(), eq(pageable))).thenReturn(vazio);

        mvc.perform(get("/auditoria"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Nenhum registro de auditoria")));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void listarExibeIpQuandoPresenteETracoQuandoAusente() throws Exception {
        LogAuditoria comIp = new LogAuditoria("avaliador1", "PARECER_VOTADO", "Favoravel", "203.0.113.42");
        comIp.setDataHora(LocalDateTime.of(2026, 7, 21, 10, 0));
        LogAuditoria semIp = new LogAuditoria("sistema", "LEMBRETE_ENVIADO", "lote automatico");
        semIp.setDataHora(LocalDateTime.of(2026, 7, 21, 11, 0));

        Pageable pageable = PageRequest.of(0, 30);
        Page<LogAuditoria> pagina = new PageImpl<>(List.of(semIp, comIp), pageable, 2);
        when(auditoria.buscar(any(), any(), any(), any(), eq(pageable))).thenReturn(pagina);

        mvc.perform(get("/auditoria"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("203.0.113.42")));
    }

    // ---------- GET /auditoria/exportar ----------

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void exportarDevolveCsvComCabecalhoEConteudoDoFiltro() throws Exception {
        LogAuditoria log1 = new LogAuditoria("operador1", "PROCESSO_CADASTRADO", "Processo 01/2026", "203.0.113.42");
        log1.setDataHora(LocalDateTime.of(2026, 7, 21, 9, 0));
        when(auditoria.buscarParaExportacao(eq("operador1"), eq("PROCESSO_CADASTRADO"), any(), any()))
                .thenReturn(List.of(log1));

        mvc.perform(get("/auditoria/exportar")
                        .param("usuario", "operador1")
                        .param("acao", "PROCESSO_CADASTRADO"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".csv")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Data/Hora;Usuario;Acao;Detalhe;IP")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("operador1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PROCESSO_CADASTRADO")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("203.0.113.42")));

        verify(auditoria).buscarParaExportacao(eq("operador1"), eq("PROCESSO_CADASTRADO"), any(), any());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void exportarSemRegistrosDevolveApenasCabecalho() throws Exception {
        when(auditoria.buscarParaExportacao(any(), any(), any(), any())).thenReturn(List.of());

        mvc.perform(get("/auditoria/exportar"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Data/Hora;Usuario;Acao;Detalhe;IP")));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void exportarEscapaCampoComPontoEVirgulaEAspas() throws Exception {
        LogAuditoria log1 = new LogAuditoria("admin", "TESTE", "detalhe; com \"aspas\" e virgula");
        log1.setDataHora(LocalDateTime.of(2026, 8, 7, 10, 0));
        when(auditoria.buscarParaExportacao(any(), any(), any(), any())).thenReturn(List.of(log1));

        mvc.perform(get("/auditoria/exportar"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("\"detalhe; com \"\"aspas\"\" e virgula\"")));
    }

    // ---------- Protecao contra CSV/Formula Injection (OWASP) ----------

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void exportarNeutralizaCampoDetalheComecandoComIgual() throws Exception {
        LogAuditoria log1 = new LogAuditoria("admin", "TESTE", "=HYPERLINK(\"http://evil\")");
        log1.setDataHora(LocalDateTime.of(2026, 8, 7, 10, 0));
        when(auditoria.buscarParaExportacao(any(), any(), any(), any())).thenReturn(List.of(log1));

        mvc.perform(get("/auditoria/exportar"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("'=HYPERLINK")));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void exportarNeutralizaCampoDetalheComecandoComMaisMenosArroba() throws Exception {
        LogAuditoria logMais = new LogAuditoria("admin", "TESTE", "+1+1");
        logMais.setDataHora(LocalDateTime.of(2026, 8, 7, 10, 0));
        LogAuditoria logMenos = new LogAuditoria("admin", "TESTE", "-1+1");
        logMenos.setDataHora(LocalDateTime.of(2026, 8, 7, 10, 1));
        LogAuditoria logArroba = new LogAuditoria("admin", "TESTE", "@SUM(1,1)");
        logArroba.setDataHora(LocalDateTime.of(2026, 8, 7, 10, 2));
        when(auditoria.buscarParaExportacao(any(), any(), any(), any()))
                .thenReturn(List.of(logMais, logMenos, logArroba));

        mvc.perform(get("/auditoria/exportar"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("'+1+1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("'-1+1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("'@SUM(1,1)")));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void exportarNeutralizaCampoUsuarioOuIpComecandoComCaracterePerigoso() throws Exception {
        LogAuditoria log1 = new LogAuditoria("=cmd|calc", "TESTE", "detalhe normal", "=1+1");
        log1.setDataHora(LocalDateTime.of(2026, 8, 7, 10, 0));
        when(auditoria.buscarParaExportacao(any(), any(), any(), any())).thenReturn(List.of(log1));

        mvc.perform(get("/auditoria/exportar"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("'=cmd|calc")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("'=1+1")));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void exportarNaoAdicionaApostrofoEmCampoNormalSemCaractereDeFormula() throws Exception {
        LogAuditoria log1 = new LogAuditoria("operador1", "PROCESSO_CADASTRADO", "Processo 01/2026 - Paciente A.B.");
        log1.setDataHora(LocalDateTime.of(2026, 8, 7, 10, 0));
        when(auditoria.buscarParaExportacao(any(), any(), any(), any())).thenReturn(List.of(log1));

        mvc.perform(get("/auditoria/exportar"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        ";operador1;PROCESSO_CADASTRADO;Processo 01/2026 - Paciente A.B.;")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("'operador1"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("'Processo"))));
    }
}

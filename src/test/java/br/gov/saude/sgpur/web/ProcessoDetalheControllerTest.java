package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.*;
import br.gov.saude.sgpur.service.dto.EstadoEtapa;
import br.gov.saude.sgpur.service.dto.PassoWizard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do ProcessoDetalheController: criacao (sempre a partir de uma
 * SolicitacaoOnline - ajuste de 2026-07-27 -, numeracao automatica vs
 * manual, validacoes), a tela de detalhe (gating das abas 1-4 e o
 * sub-rotulo de status), edicao, reabertura e exclusao. O antigo passo
 * "Recebimento" foi fundido em Envio em 2026-08-05 (sempre foi automatico e
 * nunca teve endpoint proprio - ver FluxoProcessoService).
 */
@WebMvcTest(ProcessoDetalheController.class)
class ProcessoDetalheControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private ProcessoService processoService;
    @MockitoBean private FluxoProcessoService fluxoService;
    @MockitoBean private EmailTemplateService emailTemplateService;
    @MockitoBean private MembroUrgenciaRenalService membroService;
    @MockitoBean private AnexoStorageService anexoStorage;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private GeminiService geminiService;
    @MockitoBean private ConflitoEquipeMatcher conflitoEquipeMatcher;
    @MockitoBean private br.gov.saude.sgpur.service.SolicitacaoOnlineService solicitacaoOnlineService;
    @MockitoBean private br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository solicitacaoOnlineRepository;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas duas pro
    // contexto do @WebMvcTest subir - ver ArquivoControllerTest.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private MensagemSolicitacaoService mensagemSolicitacaoService;
    @MockitoBean private br.gov.saude.sgpur.repository.AnexoRepository anexoRepository;
    // detalhe() carrega o processo por findByIdComPareceres (fetch join dos
    // pareceres + membro), para o template poder navegar a colecao ja fora da
    // transacao (open-in-view: false).
    @MockitoBean private br.gov.saude.sgpur.repository.ProcessoRepository processoRepository;
    @MockitoBean private TempoRespostaService tempoRespostaService;
    @MockitoBean private MensagemAvaliadorService mensagemAvaliadorService;
    @MockitoBean private VerificadorNomePaciente verificadorNomePaciente;
    @MockitoBean private br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository membroUrgenciaRenalRepository;
    // Achado 7 do docs/RELATORIO-STATUS-PROCESSO-12-2026-2026-08-11.md: o
    // controller passou a detectar a pausa por status OU
    // ProcessoValidator.temPedidoInformacaoAtivo (mesmo predicado que de fato
    // bloqueia a decisao) - por default (mock sem stub) devolve false, entao
    // os testes que so setam processo.setStatus(SOLICITA_INFORMACAO) continuam
    // funcionando sem precisar estubar isto.
    @MockitoBean private ProcessoValidator processoValidator;
    @MockitoBean private br.gov.saude.sgpur.service.InfoComplementarAvaliadorService infoComplementarAvaliadorService;

    private Processo processo;

    @BeforeEach
    void setUp() {
        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("01/2026");
        processo.setPacienteNome("Maria Silva");
        processo.setSolicitanteEquipe("Equipe A");
        processo.setStatus(StatusProcesso.ENVIADO);
        when(processoService.buscar(1L)).thenReturn(processo);
        // detalhe() usa a consulta com fetch join dos pareceres (+ membro) e
        // inicializa os anexos dentro da propria transacao - ver
        // ProcessoRepository.findByIdComPareceres.
        when(processoRepository.findByIdComPareceres(1L)).thenReturn(Optional.of(processo));
        // confirmarAnonimizacao busca o anexo pelo id e confere a posse, em vez
        // de varrer a colecao LAZY do processo. Default "nao existe"; os testes
        // que precisam de um anexo real sobrescrevem (ver helper anexoPendente).
        when(anexoRepository.findById(org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(Optional.empty());
        when(geminiService.isDisponivel()).thenReturn(false);
        when(emailTemplateService.gerar(any())).thenReturn(List.of());
        when(conflitoEquipeMatcher.mesmaEquipe(any(), any())).thenReturn(false);
        // Padrao "sem passos" causaria IndexOutOfBounds no detalhe() (usa
        // passosWizard.get(size-1)) - cada teste que chama GET /processos/1
        // sobrescreve com uma lista nao-vazia quando precisar.
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(fluxoService.montarPassosWizard(any())).thenReturn(List.of(
            new PassoWizard(1, "Envio", "pane-envio", EstadoEtapa.ATUAL, "")));
        // Gating/subrotulo agora vem de FluxoProcessoService (extraido do
        // controller) - como o service e mockado aqui, o default e "nada
        // liberado" e sem subrotulo; cada teste que precisa de outro cenario
        // sobrescreve com o stub especifico.
        when(fluxoService.calcularGating(any())).thenReturn(
            new FluxoProcessoService.GatingAbas(false, false, false, false));
        when(fluxoService.calcularSubrotuloStatus(any())).thenReturn(null);
        // Todo processo hoje vem do Portal do Solicitante; testes especificos
        // de detalhe sobrescrevem quando precisarem simular um processo
        // legado sem esse vinculo.
        when(fluxoService.veioDoPortal(any())).thenReturn(true);
        // envioFeito (B1, vistoria 2026-08-05): o controller agora chama
        // fluxoService.envioRegistrado(p) para alimentar o model (antes o
        // template recalculava com um criterio proprio). Como o service e
        // mockado aqui, replica a mesma logica real (pareceres nao vazios e
        // TODOS com dataEnvio) em vez de fixar true/false, para nao precisar
        // sobrescrever esse stub em cada teste que monta pareceres.
        when(fluxoService.envioRegistrado(any())).thenAnswer(inv -> {
            Processo p = inv.getArgument(0);
            return !p.getPareceres().isEmpty()
                && p.getPareceres().stream().allMatch(par -> par.getDataEnvio() != null);
        });
        // Versao em lote do card "Respostas dos Avaliadores" (CLAUDE.md,
        // correcao de N+1 de 2026-08-08): como o servico e mockado aqui, o
        // default do Mockito para um metodo que devolve um record e null -
        // sem este stub, detalhe() lanca NPE em resumoConversas.naoLidasPorMembro().
        // Mapas vazios (sem conversa) e o default seguro para os testes que
        // nao mexem com o chat do avaliador.
        when(mensagemAvaliadorService.resumoConversasDoProcesso(anyLong()))
            .thenReturn(new MensagemAvaliadorService.ResumoConversasProcesso(java.util.Map.of(), java.util.Map.of()));
    }

    /** SolicitacaoOnline valida (status ENVIADA, ainda nao triada) para os testes de novo/salvar. */
    private SolicitacaoOnline solicitacaoValida(Long id) {
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setId(id);
        s.setPacienteNome("Maria Silva");
        s.setPacienteRgct("RGCT123");
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf("11144477735");
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setSolicitanteEquipe("Equipe A");
        s.setSolicitanteEmail("equipe@ex.com");
        s.setDataSituacaoEspecial(LocalDate.of(2026, 7, 1));
        s.setJustificativaClinica("Justificativa");
        return s;
    }

    private static MembroUrgenciaRenal membro(Long id, String instituicao, String nome) {
        MembroUrgenciaRenal m = new MembroUrgenciaRenal(instituicao, nome, nome.toLowerCase() + "@ex.com");
        m.setId(id);
        return m;
    }

    private static Parecer parecer(Processo p, MembroUrgenciaRenal m, ResultadoParecer resultado,
                                    LocalDate dataEnvio, OrigemParecer origem) {
        Parecer par = new Parecer(m);
        par.setProcesso(p);
        par.setResultado(resultado);
        par.setDataEnvio(dataEnvio);
        par.setOrigem(origem);
        return par;
    }

    // ----- novo -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void novoSemOrigemSolicitacaoOnlineRedirecionaParaTriagem() throws Exception {
        // Desde 2026-07-27 nao ha mais cadastro manual "do zero" - sem o
        // parametro, redireciona para a fila de triagem em vez de abrir o form.
        mvc.perform(get("/processos/novo"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/solicitacoes-online"))
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("Portal do Solicitante")));

        verify(processoService, never()).isNumeracaoAutomatica(anyInt());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void novoComSolicitacaoJaTriadaRedirecionaParaODetalheDela() throws Exception {
        SolicitacaoOnline s = solicitacaoValida(5L);
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(s);

        mvc.perform(get("/processos/novo").param("origemSolicitacaoOnlineId", "5"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/solicitacoes-online/5"))
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("já foi triada")));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void novoComNumeracaoAutomaticaNaoSugereNumero() throws Exception {
        int ano = Year.now().getValue();
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(ano)).thenReturn(true);

        mvc.perform(get("/processos/novo").param("origemSolicitacaoOnlineId", "5"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"))
            .andExpect(model().attribute("numeracaoAutomatica", true));

        verify(processoService, never()).proximoNumero(anyInt());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void novoComNumeracaoManualSugereOProximoNumero() throws Exception {
        int ano = Year.now().getValue();
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(ano)).thenReturn(false);
        when(processoService.proximoNumero(ano)).thenReturn("05/" + ano);

        mvc.perform(get("/processos/novo").param("origemSolicitacaoOnlineId", "5"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("numeracaoAutomatica", false));

        verify(processoService).proximoNumero(ano);
    }

    // ----- salvar -----

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder formValido() {
        return post("/processos")
            .param("pacienteNome", "Maria Silva")
            .param("pacienteRgct", "RGCT123")
            .param("pacienteDataNascimento", "1985-03-15")
            .param("pacienteCpf", "11144477735")
            .param("pacienteSexo", "MASCULINO")
            .param("solicitanteEquipe", "Equipe A")
            .param("solicitanteEmail", "equipe@ex.com")
            .param("dataSituacaoEspecial", "2026-07-01")
            .param("medicoIds", "1", "2", "3")
            .param("origemSolicitacaoOnlineId", "5")
            .with(csrf());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarSemOrigemSolicitacaoOnlineRedirecionaParaTriagem() throws Exception {
        mvc.perform(post("/processos")
                .param("pacienteNome", "Maria Silva")
                .param("pacienteRgct", "RGCT123")
                .param("solicitanteEquipe", "Equipe A")
                .param("solicitanteEmail", "equipe@ex.com")
                .param("dataSituacaoEspecial", "2026-07-01")
                .param("medicoIds", "1", "2", "3")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/solicitacoes-online"))
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("Portal do Solicitante")));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarSemNumeroEmNumeracaoManualEhRejeitado() throws Exception {
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(false);
        when(membroService.listarAtivos()).thenReturn(List.of());

        mvc.perform(formValido())
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComNumeroEmFormatoInvalidoEhRejeitado() throws Exception {
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(false);
        when(membroService.listarAtivos()).thenReturn(List.of());

        mvc.perform(formValido().param("numero", "abc"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComNumeroDuplicadoEhRejeitado() throws Exception {
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(false);
        when(processoService.numeroJaExiste("01/2026")).thenReturn(true);
        when(membroService.listarAtivos()).thenReturn(List.of());

        mvc.perform(formValido().param("numero", "01/2026"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComQuantidadeErradaDeMedicosEhRejeitado() throws Exception {
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(true);
        when(membroService.listarAtivos()).thenReturn(List.of());

        mvc.perform(post("/processos")
                .param("pacienteNome", "Maria Silva")
                .param("pacienteRgct", "RGCT123")
                .param("solicitanteEquipe", "Equipe A")
                .param("solicitanteEmail", "equipe@ex.com")
                .param("dataSituacaoEspecial", "2026-07-01")
                .param("medicoIds", "1", "2")
                .param("origemSolicitacaoOnlineId", "5")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComCamposObrigatoriosEmBrancoEhRejeitadoPelaBeanValidation() throws Exception {
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(true);
        when(membroService.listarAtivos()).thenReturn(List.of());

        mvc.perform(post("/processos")
                .param("dataSituacaoEspecial", "2026-07-01")
                .param("medicoIds", "1", "2", "3")
                .param("origemSolicitacaoOnlineId", "5")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/form"));

        verify(processoService, never()).cadastrar(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void salvarComDadosValidosCadastraERedireciona() throws Exception {
        when(solicitacaoOnlineService.buscar(5L)).thenReturn(solicitacaoValida(5L));
        when(processoService.isNumeracaoAutomatica(2026)).thenReturn(true);
        Processo salvo = new Processo();
        salvo.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        salvo.setPacienteCpf("11144477735");
        salvo.setPacienteSexo(Sexo.MASCULINO);
        salvo.setId(9L);
        salvo.setNumero("09/2026");
        salvo.setPacienteNome("Maria Silva");
        when(processoService.cadastrar(any(), eq(List.of(1L, 2L, 3L)))).thenReturn(salvo);

        mvc.perform(formValido())
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/9"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("09/2026")));

        verify(auditoria).registrar(eq("PROCESSO_CADASTRADO"), anyString());
        verify(solicitacaoOnlineService).converter(eq(5L), eq(salvo));
    }

    // ----- detalhe -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheDeUmProcessoRecemCriadoNaoLiberaNadaAlemDoEnvio() throws Exception {
        // Processo sem pareceres/anexos: o Envio (passo 1, sempre liberado -
        // ver default do fluxoService mockado acima) e o unico liberado.
        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/detalhe"))
            .andExpect(model().attribute("liberadoEnvio", false))
            .andExpect(model().attribute("liberadoRespostas", false))
            .andExpect(model().attribute("liberadoDecisao", false))
            .andExpect(model().attribute("liberadoFinalizacao", false));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheLiberaEnvioENaoLiberaRespostasAntesDoEnvioSerRegistrado() throws Exception {
        // O controller so repassa o gating calculado por FluxoProcessoService
        // (mockado aqui) - Envio (passo 1) sempre liberado desde 2026-08-05
        // (Recebimento fundido nele, nao existe mais como pre-requisito), mas
        // Respostas (passo 2) so libera depois que o envio for registrado.
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, false, false, false));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("liberadoEnvio", true))
            .andExpect(model().attribute("liberadoRespostas", false));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheMostraAguardandoParecerQuandoAindaNaoHaMaioria() throws Exception {
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        MembroUrgenciaRenal m2 = membro(2L, "HCC", "Bruno");
        MembroUrgenciaRenal m3 = membro(3L, "HSL", "Carla");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m2, null, LocalDate.now(), null));
        processo.addParecer(parecer(processo, m3, null, LocalDate.now(), null));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.empty());
        when(processoService.contarRespondidos(processo)).thenReturn(1L);
        when(fluxoService.calcularSubrotuloStatus(processo)).thenReturn("Aguardando parecer (1/3)");
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(false, false, false, false));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("statusSubrotulo", "Aguardando parecer (1/3)"))
            .andExpect(model().attribute("liberadoDecisao", false));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheLiberaDecisaoQuandoMaioriaFormadaESemAnexoPendente() throws Exception {
        // O gating real (FluxoProcessoService) e mockado aqui; o teste so
        // confere que o controller repassa liberadoDecisao=true corretamente.
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        MembroUrgenciaRenal m2 = membro(2L, "HCC", "Bruno");
        MembroUrgenciaRenal m3 = membro(3L, "HSL", "Carla");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m2, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m3, null, LocalDate.now(), null));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.of(StatusProcesso.DEFERIDO));
        when(processoService.contarRespondidos(processo)).thenReturn(2L);
        when(fluxoService.calcularSubrotuloStatus(processo)).thenReturn(
            "Maioria formada - pronto para decidir (Deferido)");
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, true, true, false));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("statusSubrotulo",
                "Maioria formada - pronto para decidir (Deferido)"))
            .andExpect(model().attribute("liberadoDecisao", true));
    }

    /**
     * Relatorio de clareza (2026-08-05), item 4.7: o placar (favoraveis/nao
     * favoraveis/pendentes + fraseMaioria) e a resposta a unica pergunta que
     * o operador faz ao abrir a aba Respostas - "ja da para decidir?" - entao
     * o botao "Ir à Decisão" sobe para o TOPO do card, ao lado do placar,
     * quando a maioria ja se formou. Antes ele so aparecia num alerta verde
     * no FIM do card, depois da tabela inteira (abaixo da dobra). Renderiza
     * o template de verdade e trava que o botao aparece no placar promovido,
     * nao mais no rodape.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void abaRespostasMostraIrADecisaoNoPlacarPromovidoQuandoMaioriaFormada() throws Exception {
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        MembroUrgenciaRenal m2 = membro(2L, "HCC", "Bruno");
        MembroUrgenciaRenal m3 = membro(3L, "HSL", "Carla");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m2, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m3, null, LocalDate.now(), null));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.of(StatusProcesso.DEFERIDO));
        when(processoService.contarRespondidos(processo)).thenReturn(2L);
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, true, true, false));

        String html = mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String cardBody = html.substring(html.indexOf("id=\"respostas\""),
            html.indexOf("Como funciona a maioria simples"));
        org.assertj.core.api.Assertions.assertThat(cardBody).contains("Ir à Decisão");
        org.assertj.core.api.Assertions.assertThat(cardBody).doesNotContain("Respostas concluídas");
        org.assertj.core.api.Assertions.assertThat(cardBody).doesNotContain("Avançar para Decisão");
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheBloqueiaDecisaoQuandoAguardandoInformacaoComplementar() throws Exception {
        processo.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.empty());
        when(processoService.contarRespondidos(processo)).thenReturn(1L);

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("aguardandoInfo", true))
            .andExpect(model().attribute("liberadoDecisao", false));
    }

    /**
     * Achado A do docs/RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md:
     * com o processo pausado (SOLICITA_INFORMACAO) e maioria simples ja
     * formada por 2 avaliadores comuns (sem o coordenador da CET-RS), o card
     * de Respostas e o alerta "Sugestao automatica" diziam que a decisao
     * estava pronta/Deferido sem nenhuma ressalva sobre a pausa - mesmo com a
     * Decisao de fato bloqueada (ver
     * detalheBloqueiaDecisaoQuandoAguardandoInformacaoComplementar). Renderiza
     * o HTML de verdade (nao so os model attributes) porque o bug era
     * especificamente de TEXTO na tela.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void abaRespostasAvisaQueDecisaoEstaBloqueadaQuandoMaioriaFormadaDurantePausaSemCoordenador() throws Exception {
        processo.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        MembroUrgenciaRenal m2 = membro(2L, "HCC", "Bruno");
        MembroUrgenciaRenal m3 = membro(3L, "HSL", "Carla");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m2, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m3, ResultadoParecer.SOLICITA_INFORMACAO,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.of(StatusProcesso.DEFERIDO));
        when(processoService.contarRespondidos(processo)).thenReturn(3L);
        // temVotoCoordenadorFavoravel nao estubado = false (default do mock):
        // nenhum dos 2 favoraveis e o coordenador.

        String html = mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("pausaBloqueiaDecisao", true))
            .andExpect(model().attribute("fraseMaioria",
                "Maioria formada, mas BLOQUEADA: aguardando informação complementar"))
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
            .contains("BLOQUEADA</strong> enquanto o processo aguarda")
            .doesNotContain("coordenador da CET-RS defere sozinho");
    }

    /**
     * Contraste do teste acima: quando o coordenador da CET-RS ja votou
     * Favoravel, a decisao NAO esta de fato bloqueada pela pausa (excecao
     * documentada em CLAUDE.md) - {@code pausaBloqueiaDecisao} continua
     * {@code false}. O TEXTO, porem, nao deve dizer "Maioria ja formada" -
     * nunca houve maioria nenhuma, foi 1 voto so (Achado 3 do
     * docs/RELATORIO-STATUS-PROCESSO-12-2026-2026-08-11.md, recaida do Achado
     * 3 do relatorio de vistoria de brechas de 2026-08-10, que so corrigiu a
     * timeline lateral, nao este placar). O texto correto reusa
     * RegraDecisao.VOTO_COORDENADOR.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void abaRespostasNaoAvisaBloqueioQuandoCoordenadorJaVotouFavoravelDurantePausa() throws Exception {
        processo.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.of(StatusProcesso.DEFERIDO));
        when(processoService.contarRespondidos(processo)).thenReturn(1L);
        when(processoService.temVotoCoordenadorFavoravel(processo)).thenReturn(true);

        String html = mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("pausaBloqueiaDecisao", false))
            .andExpect(model().attribute("fraseMaioria",
                br.gov.saude.sgpur.service.dto.RegraDecisao.VOTO_COORDENADOR.getRotuloLongo()))
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
            .contains("coordenador da CET-RS defere sozinho")
            .doesNotContain("BLOQUEADA</strong> enquanto o processo aguarda")
            .doesNotContain("Maioria já formada")
            .doesNotContain("Maioria ja formada");
    }

    /**
     * Achado 3 do docs/RELATORIO-STATUS-PROCESSO-12-2026-2026-08-11.md: o
     * mesmo texto errado ("Maioria ja formada") tambem aparecia quando o
     * processo JA ESTA decidido (11/2026 real: DEFERIDO pelo voto unico do
     * coordenador, sem pausa nenhuma) - nao e exclusivo do cenario pausado.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void fraseMaioriaNaoDizMaioriaJaFormadaQuandoDecisaoFoiPeloCoordenadorSemPausa() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        MembroUrgenciaRenal m1 = membro(1L, "CET-RS", "Coordenadora");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.of(StatusProcesso.DEFERIDO));
        when(processoService.contarRespondidos(processo)).thenReturn(1L);
        when(processoService.temVotoCoordenadorFavoravel(processo)).thenReturn(true);

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("fraseMaioria",
                br.gov.saude.sgpur.service.dto.RegraDecisao.VOTO_COORDENADOR.getRotuloLongo()));
    }

    /**
     * Achado 2 do docs/RELATORIO-STATUS-PROCESSO-12-2026-2026-08-11.md: caso
     * real do processo 12/2026 - a pausa acontece ANTES de a maioria se
     * formar (1 favoravel + 1 solicita-informacao + 1 pendente: nem maioria
     * nem todos votaram). Antes desta correcao o placar dizia so "Faltam 1
     * voto", escondendo que cobrar o voto pendente nao desbloqueia nada -
     * quem desbloqueia e o solicitante mandar a informacao e o operador
     * retomar a analise.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void fraseMaioriaMencionaAPausaQuandoAindaNaoHaMaioriaFormada() throws Exception {
        processo.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        MembroUrgenciaRenal m2 = membro(2L, "HCC", "Bruno");
        MembroUrgenciaRenal m3 = membro(3L, "HSL", "Carla");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m2, ResultadoParecer.SOLICITA_INFORMACAO,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m3, null, LocalDate.now(), null));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.empty());
        when(processoService.contarRespondidos(processo)).thenReturn(2L);
        // temVotoCoordenadorFavoravel nao estubado = false (default do mock).

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("pausaBloqueiaDecisao", true))
            .andExpect(model().attribute("fraseMaioria",
                "PAUSADO (aguardando informação complementar) — faltam 1 voto"));
    }

    /**
     * Achado 7 do docs/RELATORIO-STATUS-PROCESSO-12-2026-2026-08-11.md: a
     * pausa tambem precisa ser detectada pelo FATO (ProcessoValidator.
     * temPedidoInformacaoAtivo), nao so pelo status - mesmo predicado em OU
     * que ProcessoValidator.validarPausaDecisao usa para de fato bloquear a
     * decisao. Aqui o status NAO e SOLICITA_INFORMACAO (dessincronizado, o
     * mesmo cenario que ja causou incidente real segundo o javadoc de
     * temPedidoInformacaoAtivo), mas o fato observavel diz que a pausa esta
     * ativa - aguardandoInfo e pausaBloqueiaDecisao devem acompanhar o fato.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void pausaDetectadaPeloFatoMesmoComStatusDessincronizado() throws Exception {
        processo.setStatus(StatusProcesso.ENVIADO);
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.SOLICITA_INFORMACAO,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.empty());
        when(processoService.contarRespondidos(processo)).thenReturn(1L);
        when(processoValidator.temPedidoInformacaoAtivo(processo)).thenReturn(true);

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("aguardandoInfo", true))
            .andExpect(model().attribute("pausaBloqueiaDecisao", true));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheIdentificaPareceresVotadosPeloPortalComoImutaveis() throws Exception {
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        Parecer votadoPeloPortal = parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA);
        votadoPeloPortal.setId(100L);
        processo.addParecer(votadoPeloPortal);
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.empty());

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("pareceresPortal", java.util.Set.of(100L)));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheAvisaSobreMedicoDaMesmaEquipeDoSolicitante() throws Exception {
        MembroUrgenciaRenal m1 = membro(1L, "Equipe A", "Ana");
        processo.addParecer(parecer(processo, m1, null, null, null));
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.empty());
        when(conflitoEquipeMatcher.mesmaEquipe("Equipe A", "Equipe A")).thenReturn(true);

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("medicosMesmaEquipe", List.of("Ana (Equipe A)")));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheExpoeAsSugestoesEContadoresDoServico() throws Exception {
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.empty());
        when(processoService.contarFavoraveis(processo)).thenReturn(2L);
        when(processoService.deferidoPeloCoordenador(processo)).thenReturn(true);

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("favoraveis", 2L))
            .andExpect(model().attribute("deferidoPeloCoordenador", true));
    }

    /**
     * F2 do relatorio de vistoria de brechas (2026-08-10) - Achado 6: o
     * badge "Voto único do Coordenador CET-RS" (fonte unica RegraDecisao)
     * aparece no detalhe quando a regra aplicada e a excecao do
     * coordenador, e NAO aparece num processo decidido por maioria simples
     * comum (nao polui a tela com "maioria simples" em todo processo).
     *
     * <p>Atualizado (pedido do dono do produto): o badge deixou de ficar ao
     * lado do badge de Status, no topo da pagina, e passou a aparecer
     * embaixo do nome do proprio médico, na tabela de Respostas dos
     * Avaliadores - só na linha do parecer que de fato decidiu sozinho
     * ({@code Parecer.eraCoordenadorNoVoto}), não em qualquer linha só por a
     * regra aplicada ser a exceção do coordenador.</p>
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheMostraBadgeDeRegraDecisaoQuandoForDoCoordenadorENaoQuandoForMaioriaSimples() throws Exception {
        br.gov.saude.sgpur.domain.MembroUrgenciaRenal coordenador =
            new br.gov.saude.sgpur.domain.MembroUrgenciaRenal("CET-RS", "Dra. Coordenadora", "coord@ex.com");
        coordenador.setId(99L);
        coordenador.setCoordenador(true);
        br.gov.saude.sgpur.domain.Parecer parecerCoordenador = new br.gov.saude.sgpur.domain.Parecer(coordenador);
        parecerCoordenador.setId(1L);
        parecerCoordenador.setDataEnvio(java.time.LocalDate.now());
        parecerCoordenador.setResultado(br.gov.saude.sgpur.domain.ResultadoParecer.FAVORAVEL);
        parecerCoordenador.setEraCoordenadorNoVoto(true);
        processo.setPareceres(new java.util.ArrayList<>(java.util.List.of(parecerCoordenador)));

        when(processoService.regraAplicada(processo))
            .thenReturn(br.gov.saude.sgpur.service.dto.RegraDecisao.VOTO_COORDENADOR);

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Voto único do Coordenador CET-RS")));

        when(processoService.regraAplicada(processo))
            .thenReturn(br.gov.saude.sgpur.service.dto.RegraDecisao.MAIORIA_SIMPLES);

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Voto único do Coordenador CET-RS"))));
    }

    /**
     * F5 do relatorio de vistoria de brechas (2026-08-10) - Achado 8: o
     * badge "Reaberto Nx" aparece so quando o processo ja foi reaberto pelo
     * menos uma vez - leitura pura de Processo.reaberturasOuZero, sem
     * depender de nenhum servico mockado.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheMostraBadgeDeReaberturasSoQuandoJaHouveReabertura() throws Exception {
        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Reaberto"))));

        processo.setReaberturas(3);

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Reaberto")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("3")));
    }

    /**
     * F4 do relatorio de vistoria de brechas (2026-08-10) - Achado 7: o
     * card Respostas mostra o histórico de pareceres sobrepostos (com a
     * justificativa original preservada) so quando existe algum, sem poluir
     * a tela no caso comum (processo sem nenhuma pausa sobreposta).
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheMostraHistoricoDeParecerSobrepostoQuandoExiste() throws Exception {
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Histórico de pareceres sobrepostos"))));

        MembroUrgenciaRenal membroHist = membro(2L, "HSL", "Bruno");
        Parecer parecerArquivado = new Parecer(membroHist);
        parecerArquivado.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        parecerArquivado.setJustificativa("Justificativa clínica original preservada");
        br.gov.saude.sgpur.domain.HistoricoParecer h =
            br.gov.saude.sgpur.domain.HistoricoParecer.deParecer(parecerArquivado,
                "Retomada da análise após pedido de informação complementar");
        when(processoService.historicoParecer(1L)).thenReturn(List.of(h));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Histórico de pareceres sobrepostos")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Justificativa clínica original preservada")));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheExpoeProcessoVeioDoPortalFalseParaProcessoLegadoSemVinculo() throws Exception {
        // Processo legado (anterior a 2026-07-27) sem SolicitacaoOnline de
        // origem - caso raro hoje, mas veioDoPortal ainda pode retornar false.
        when(fluxoService.veioDoPortal(processo)).thenReturn(false);

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("processoVeioDoPortal", false))
            .andExpect(model().attribute("solicitacaoOnlineOrigemId", (Object) null));

        verify(solicitacaoOnlineRepository, never()).findIdByProcessoGeradoId(anyLong());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheExpoeProcessoVeioDoPortalTrueELinkDaOrigem() throws Exception {
        // fluxoService.veioDoPortal ja e true por padrao no setUp (todo
        // processo hoje vem do Portal do Solicitante).
        when(solicitacaoOnlineRepository.findIdByProcessoGeradoId(1L)).thenReturn(Optional.of(42L));
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, false, false, false));
        when(solicitacaoOnlineService.nomeSolicitante(42L)).thenReturn("Santa Casa - Nefro");

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("processoVeioDoPortal", true))
            .andExpect(model().attribute("solicitacaoOnlineOrigemId", 42L))
            .andExpect(model().attribute("nomeSolicitante", "Santa Casa - Nefro"))
            .andExpect(model().attribute("liberadoEnvio", true))
            // Cabecalho do card de chat mostra o nome real, nao o literal
            // generico "Conversa com o solicitante" (correcao de 2026-08-08).
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Santa Casa - Nefro")));
    }

    // ----- editar / atualizar -----

    @Test
    @WithMockUser(roles = "OPERADOR")
    void editarCarregaOProcessoNoModel() throws Exception {
        mvc.perform(get("/processos/1/editar"))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/editar"))
            .andExpect(model().attribute("processo", processo));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void editarBloqueadoQuandoProcessoEncerrado() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(get("/processos/1/editar"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void atualizarComErroDeValidacaoVoltaParaOFormulario() throws Exception {
        mvc.perform(post("/processos/1/editar").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("processos/editar"));

        verify(processoService, never()).atualizarDados(anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void atualizarBloqueadoQuandoProcessoEncerrado() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(post("/processos/1/editar")
                .param("numero", "01/2026")
                .param("pacienteNome", "Maria Silva")
                .param("pacienteRgct", "RGCT123")
                .param("pacienteDataNascimento", "1985-03-15")
                .param("pacienteCpf", "11144477735")
                .param("pacienteSexo", "MASCULINO")
                .param("solicitanteEquipe", "Equipe A")
                .param("solicitanteEmail", "equipe@ex.com")
                .param("dataSituacaoEspecial", "2026-07-01")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));

        verify(processoService, never()).atualizarDados(anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void atualizarComSucessoRegistraAuditoriaERedireciona() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);

        mvc.perform(post("/processos/1/editar")
                .param("numero", "01/2026")
                .param("pacienteNome", "Maria Silva Atualizada")
                .param("pacienteRgct", "RGCT123")
                .param("pacienteDataNascimento", "1985-03-15")
                .param("pacienteCpf", "11144477735")
                .param("pacienteSexo", "MASCULINO")
                .param("solicitanteEquipe", "Equipe A")
                .param("solicitanteEmail", "equipe@ex.com")
                .param("dataSituacaoEspecial", "2026-07-01")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("msg", "Processo atualizado."));

        verify(processoService).atualizarDados(eq(1L), any());
        verify(auditoria).registrar(eq("PROCESSO_EDITADO"), anyString());
    }

    // ----- reabrir -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void reabrirComSucesso() throws Exception {
        // F3 do relatorio de vistoria de brechas (2026-08-10) - Achado 8:
        // reabrir() agora captura a decisao/regra ANTES de chamar
        // processoService.reabrir(id), para registrar na auditoria qual
        // decisao foi anulada. processoService e mockado aqui, entao
        // regraAplicada precisa de stub explicito (sem ele o Mockito
        // devolve null e o controller lancaria NPE ao formatar o detalhe).
        when(processoService.regraAplicada(processo))
            .thenReturn(br.gov.saude.sgpur.service.dto.RegraDecisao.MAIORIA_SIMPLES);

        mvc.perform(post("/processos/1/reabrir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("reaberto")));

        verify(processoService).reabrir(1L);
        // 3 argumentos agora (com IP) - antes era so acao+detalhe.
        verify(auditoria).registrar(eq("PROCESSO_REABERTO"), anyString(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reabrirComFalhaDeRegraDeNegocioVoltaFlashDeErro() throws Exception {
        doThrow(new IllegalStateException("Processo nao esta encerrado.")).when(processoService).reabrir(1L);

        mvc.perform(post("/processos/1/reabrir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", "Processo nao esta encerrado."));
    }

    // ----- excluir -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void excluirBloqueadoQuandoProcessoEncerrado() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(post("/processos/1/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1"))
            .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));

        verify(processoService, never()).excluir(anyLong());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void excluirComSucessoRemoveAPastaDeAnexos() throws Exception {
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);

        mvc.perform(post("/processos/1/excluir").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("excluído")));

        verify(processoService).excluir(1L);
        verify(anexoStorage).removerPastaProcesso(processo);
    }

    // ----- trava de anonimizacao (documento vindo do Portal do Solicitante) -----

    /**
     * O documento em staging NAO entra na lista de documentos clinicos (a que
     * vai para os avaliadores) e a aba Envio o mostra em bloco proprio,
     * marcado como pendente. Tambem cobre a renderizacao real do template.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void detalheSeparaDocumentoPendenteDeAnonimizacaoDosDocumentosClinicos() throws Exception {
        Anexo pendente = anexoPendente(7L);
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, false, false, false));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("documentosClinicos", org.hamcrest.Matchers.empty()))
            .andExpect(model().attribute("documentosPendentesAnonimizacao",
                org.hamcrest.Matchers.contains(pendente)))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("pendente de anonimizacao")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Confirmo que este documento foi anonimizado")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "/processos/1/documento-clinico/7/confirmar-anonimizacao")));
    }

    // ----- aba Envio: o operador precisa saber o que o botao faz ANTES de clicar -----

    /**
     * "Registrar envio" dispara e-mail de verdade para medicos de verdade
     * (convite ao Portal do Avaliador, {@code RegistroEnvioService
     * .enviarConvitesAvaliadores}), alem de gerar o PDF e mudar o status - e
     * a tela so dizia "confirma o envio e gera o PDF consolidado", sem citar
     * o e-mail nem quem receberia. Este teste renderiza o template de verdade
     * e trava a lista de destinatarios exibida antes do clique, usando o
     * MESMO criterio do servidor: so quem ainda nao votou.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void abaEnvioListaOsMedicosQueVaoReceberOConviteAntesDeRegistrarOEnvio() throws Exception {
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        MembroUrgenciaRenal m2 = membro(2L, "HCC", "Bruno");
        MembroUrgenciaRenal m3 = membro(3L, "HSL", "Carla");
        // dataEnvio nula nos 3: envio ainda NAO registrado, o passo 2 aparece.
        processo.addParecer(parecer(processo, m1, null, null, null));
        processo.addParecer(parecer(processo, m2, null, null, null));
        processo.addParecer(parecer(processo, m3, null, null, null));
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, false, false, false));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "O convite será enviado para")))
            // As acoes do botao agora vivem no modal de confirmacao (Fase 4 do
            // relatorio de clareza, 2026-08-05), nao mais numa <ol> sempre visivel.
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "data-confirm-msg")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Portal do Avaliador por e-mail")))
            // Nome + e-mail de cada destinatario.
            .andExpect(content().string(org.hamcrest.Matchers.containsString("HCPA - Ana")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("ana@ex.com")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("HSL - Carla")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("carla@ex.com")));
    }

    /**
     * "Registrar envio" dispara e-mail real e irreversivel (Fase 4 do
     * relatorio de clareza de 2026-08-05, item 4.3) - precisa do mesmo
     * data-confirm-msg + modal que "Enviar Resposta ao Solicitante" ja tem.
     * O texto das 3 consequencias, antes numa <ol> sempre visivel na pagina,
     * agora vive na mensagem do modal (lida no momento da decisao).
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void registrarEnvioExigeConfirmacaoNoModalComAsConsequenciasDoClique() throws Exception {
        processo.addParecer(parecer(processo, membro(1L, "HCPA", "Ana"), null, null, null));
        processo.addParecer(parecer(processo, membro(2L, "HCC", "Bruno"), null, null, null));
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, false, false, false));

        String html = mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String form = html.substring(html.indexOf("PASSO 2: Registrar envio"),
            html.indexOf("Finalizacao explicita da etapa", html.indexOf("PASSO 2: Registrar envio")));
        org.assertj.core.api.Assertions.assertThat(form).contains("data-confirm-msg");
        org.assertj.core.api.Assertions.assertThat(form).contains("PDF único carimbado");
        org.assertj.core.api.Assertions.assertThat(form).contains("marcar o processo como Enviado");
        org.assertj.core.api.Assertions.assertThat(form).contains("2 médico(s)");
        // A <ol> antiga (3 itens sempre visiveis na pagina) nao existe mais -
        // as consequencias so aparecem no modal, no momento da decisao.
        org.assertj.core.api.Assertions.assertThat(form).doesNotContain("<ol");
    }

    /**
     * Raiz da queixa do relatorio de clareza de 2026-08-05: a cor do
     * sub-passo tinha que refletir o ESTADO real (pendente/concluido), nao a
     * POSICAO na tela - antes, o sub-passo 2 aparecia sempre verde mesmo
     * "Pendente". Renderiza o template de verdade nos dois estados do
     * sub-passo 1 (sem documento e com documento) e confere que a classe
     * subpasso-atual/subpasso-ok segue o estado, nunca fixa por posicao.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void corDoSubPassoDaAbaEnvioSegueOEstadoRealNaoAPosicao() throws Exception {
        processo.addParecer(parecer(processo, membro(1L, "HCPA", "Ana"), null, null, null));
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, false, false, false));

        // Sem documento clinico: sub-passo 1 "atual" (azul), sub-passo 2 "bloqueado" (cinza).
        // Marcadores sao os comentarios HTML literais do proprio template (Thymeleaf
        // preserva <!-- --> comum no HTML de saida) - mais robusto que contar caracteres.
        String semDocumento = mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String secao1SemDoc = semDocumento.substring(
            semDocumento.indexOf("PASSO 1: Documentos"),
            semDocumento.indexOf("PASSO 2: Registrar envio"));
        org.assertj.core.api.Assertions.assertThat(secao1SemDoc).contains("subpasso-atual");
        org.assertj.core.api.Assertions.assertThat(secao1SemDoc).doesNotContain("subpasso-ok");

        // Com documento clinico anexado: sub-passo 1 vira "ok" (verde) - NUNCA
        // fixo por posicao, sempre pelo estado real de documentosClinicos.
        Anexo doc = new Anexo();
        doc.setId(50L);
        doc.setTipo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        doc.setNomeArquivo("exame.pdf");
        doc.setContentType("application/pdf");
        processo.addAnexo(doc);

        String comDocumento = mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String secao1ComDoc = comDocumento.substring(
            comDocumento.indexOf("PASSO 1: Documentos"),
            comDocumento.indexOf("PASSO 2: Registrar envio"));
        org.assertj.core.api.Assertions.assertThat(secao1ComDoc).contains("subpasso-ok");
        org.assertj.core.api.Assertions.assertThat(secao1ComDoc).doesNotContain("subpasso-atual");
    }

    /**
     * Quem ja votou NAO recebe convite de novo ({@code
     * ProcessoService.pareceresPendentesComEmail} filtra por resultado nulo),
     * e um avaliador sem e-mail cadastrado simplesmente fica de fora - as
     * duas coisas precisam estar visiveis antes do clique, senao o operador
     * so descobre pelo flash de aviso depois que o envio ja foi gravado.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void abaEnvioAvisaSobreAvaliadorSemEmailEOmiteQuemJaVotou() throws Exception {
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        MembroUrgenciaRenal semEmail = new MembroUrgenciaRenal("HSL", "Carla", null);
        semEmail.setId(3L);
        processo.addParecer(parecer(processo, m1, ResultadoParecer.FAVORAVEL, null, null));
        processo.addParecer(parecer(processo, semEmail, null, null, null));
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, false, false, false));

        String html = mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String painel = html.substring(html.indexOf("O convite será enviado para"),
            html.indexOf("Registrar envio e enviar convites"));
        // So a Carla (pendente) aparece como destinataria; a Ana ja votou.
        org.assertj.core.api.Assertions.assertThat(painel).contains("HSL - Carla");
        org.assertj.core.api.Assertions.assertThat(painel).doesNotContain("HCPA - Ana");
        org.assertj.core.api.Assertions.assertThat(painel).contains("sem e-mail cadastrado");
        org.assertj.core.api.Assertions.assertThat(painel).contains("Quem já votou não recebe o convite de novo");
    }

    // ----- aba Decisao: textos vs. comportamento real -----

    /**
     * A decisao automatica passou a valer nos DOIS sentidos (2 favoraveis
     * deferem, 2 desfavoraveis indeferem — ver
     * {@code ProcessoService.tentarDecisaoAutomatica}), mas a aba Decisao
     * ainda dizia que so o deferimento era automatico. Este teste renderiza o
     * template de verdade e trava o texto atualizado (inclusive a regra do
     * coordenador e o motivo institucional padrao do indeferimento
     * automatico), que e o que o operador le antes de decidir na mao.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void abaDecisaoExplicaQueOAutomaticoDefereEIndefere() throws Exception {
        MembroUrgenciaRenal m1 = membro(1L, "HCPA", "Ana");
        MembroUrgenciaRenal m2 = membro(2L, "HCC", "Bruno");
        MembroUrgenciaRenal m3 = membro(3L, "HSL", "Carla");
        processo.addParecer(parecer(processo, m1, ResultadoParecer.NAO_FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m2, ResultadoParecer.NAO_FAVORAVEL,
            LocalDate.now(), OrigemParecer.AVALIADOR_SISTEMA));
        processo.addParecer(parecer(processo, m3, null, LocalDate.now(), null));
        when(processoService.contarRespondidos(processo)).thenReturn(2L);
        when(processoService.sugerirDecisao(processo)).thenReturn(Optional.of(StatusProcesso.INDEFERIDO));
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, true, true, false));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            // sugestao automatica: nao pode falar so de deferimento
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "maioria simples de 2 em 3 votos, tanto para deferir quanto para")))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("2 de 3 favoráveis defere o processo"))))
            // formulario manual: quando ainda e necessario
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "favorável do coordenador da CET-RS defere sozinho")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "texto institucional padrão")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "<strong>Cancelado</strong> — nunca é")))
            // Texto obsoleto (regra do "anexo comprobatorio" removida do codigo em
            // 2026-07-29) nao pode mais aparecer - relatorio de 2026-08-05, item 4.4.
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("comprobatório"))));
    }

    // ----- aba Finalizacao: avisos do oficio e data de envio ao SNT -----

    /**
     * Renderiza o template de verdade num processo INDEFERIDO: a aba
     * Finalizacao precisa avisar que o oficio anexado e gerado pelo sistema
     * (item 3 do relatorio de 2026-08) e que salvar as datas regera esse PDF
     * (item 7), senao o operador envia ao solicitante um documento que nunca
     * conferiu, ou fica com tela e anexo mostrando datas diferentes.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void abaFinalizacaoOfereceRascunhoEditavelEExigeOAnexoComoDocumentoOficial() throws Exception {
        processo.setStatus(StatusProcesso.INDEFERIDO);
        processo.setNumeroOficio("0007/2026");
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, true, true, true));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            // O oficio nao e mais gerado/anexado pelo sistema: o operador baixa
            // o rascunho editavel, ajusta no Word e anexa o documento final.
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Baixar rascunho editável (.rtf)")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "/processos/1/oficio-rascunho")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "sempre o arquivo anexado")))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("gerado automaticamente pelo sistema"))))
            // As datas do oficio nao sao mais editaveis: a tela mostra a data
            // que o sistema gravou, sem <input type="date"> (2026-08-04).
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("name=\"dataEmissaoOficio\""))))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("name=\"dataEnvioOficio\""))))
            // numeracao propria do oficio, distinta do numero do processo
            .andExpect(content().string(org.hamcrest.Matchers.containsString("0007/2026")));
    }

    /**
     * Mesma aba num processo DEFERIDO: a data de envio ao SNT e EXIBIDA, mas
     * NAO e mais um campo editavel (2026-08-04) - ela e gravada no momento em
     * que o comprovante e anexado. Um <input type="date"> aqui aceitaria data
     * retroativa, o que e inadmissivel num processo administrativo.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void abaFinalizacaoMostraADataDeEnvioAoSntSemCampoEditavel() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, true, true, true));

        mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Data de envio ao SNT")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "registrada automaticamente ao anexar o comprovante")))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("name=\"dataEnvioSnt\""))));
    }

    /**
     * Relatorio de clareza (2026-08-05), item 4.8: a pendencia "falta anexar
     * o comprovante/oficio antes de enviar" era dita DUAS vezes - um alert
     * amarelo dentro do bloco de Comprovante SNT e outro, com o MESMO texto,
     * dentro do bloco de Resposta ao solicitante - alem do botao ja vir
     * th:disabled na mesma condicao (a trava real). Agora o motivo vive so
     * no title do botao desabilitado. Renderiza o template de verdade num
     * DEFERIDO sem comprovante e confere que a frase aparece uma vez so,
     * como atributo title, e que o botao continua desabilitado (a trava nao
     * mudou, so o texto que a duplicava).
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void abaFinalizacaoNaoRepeteAPendenciaDeAnexoDuasVezes() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO);
        when(fluxoService.calcularGating(processo)).thenReturn(
            new FluxoProcessoService.GatingAbas(true, true, true, true));

        String html = mvc.perform(get("/processos/1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String frase = "Anexe o comprovante de inserção no SNT acima antes de enviar a resposta.";
        int ocorrencias = html.split(java.util.regex.Pattern.quote(frase), -1).length - 1;
        // Desde a correcao S2 (2026-08-05), o motivo aparece 2x: no title do
        // botao desabilitado (nao confiavel em toque/celular) e num paragrafo
        // visivel logo abaixo (.subpasso-regra) - nao mais so no title.
        org.assertj.core.api.Assertions.assertThat(ocorrencias).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(html).contains("title=\"" + frase + "\"");
        // A trava real (o botao desabilitado) continua intacta.
        String finalizacao = html.substring(html.indexOf("id=\"pane-finalizacao\""));
        org.assertj.core.api.Assertions.assertThat(finalizacao).contains("Enviar Resposta ao Solicitante");
        org.assertj.core.api.Assertions.assertThat(finalizacao).contains("disabled");
    }

    /** Anexo em staging (veio do portal, ainda nao revisado) vinculado ao processo. */
    private Anexo anexoPendente(Long id) {
        Anexo a = new Anexo();
        a.setId(id);
        a.setTipo(TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO);
        a.setNomeArquivo("laudo.pdf");
        a.setContentType("application/pdf");
        processo.addAnexo(a); // addAnexo ja seta a.processo (checagem de posse)
        when(anexoRepository.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void confirmarAnonimizacaoPromoveOTipoERegistraAuditoria() throws Exception {
        Anexo pendente = anexoPendente(7L);
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);

        mvc.perform(post("/processos/1/documento-clinico/7/confirmar-anonimizacao")
                .param("confirmo", "true").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/processos/1#envio"))
            .andExpect(flash().attribute("msg", org.hamcrest.Matchers.containsString("Anonimização confirmada")));

        org.assertj.core.api.Assertions.assertThat(pendente.getTipo())
            .isEqualTo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        verify(anexoRepository).save(pendente);
        // O log de auditoria e o registro de que a revisao humana aconteceu:
        // precisa dizer QUEM confirmou e QUAL anexo.
        verify(auditoria).registrar(eq("ANONIMIZACAO_CONFIRMADA"),
            org.mockito.ArgumentMatchers.contains("operador1"));
        verify(auditoria).registrar(eq("ANONIMIZACAO_CONFIRMADA"),
            org.mockito.ArgumentMatchers.contains("laudo.pdf"));
    }

    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void confirmarAnonimizacaoSemMarcarACaixaNaoPromove() throws Exception {
        Anexo pendente = anexoPendente(7L);
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);

        mvc.perform(post("/processos/1/documento-clinico/7/confirmar-anonimizacao").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("Marque a confirmação")));

        org.assertj.core.api.Assertions.assertThat(pendente.getTipo())
            .isEqualTo(TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO);
        verify(anexoRepository, never()).save(any());
        verify(auditoria, never()).registrar(eq("ANONIMIZACAO_CONFIRMADA"), anyString());
    }

    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void confirmarAnonimizacaoBloqueadaQuandoProcessoEncerrado() throws Exception {
        Anexo pendente = anexoPendente(7L);
        when(processoService.edicaoBloqueada(processo)).thenReturn(true);

        mvc.perform(post("/processos/1/documento-clinico/7/confirmar-anonimizacao")
                .param("confirmo", "true").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));

        org.assertj.core.api.Assertions.assertThat(pendente.getTipo())
            .isEqualTo(TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO);
        verify(anexoRepository, never()).save(any());
    }

    /** Anexo de outro processo (ou inexistente) nunca e promovido por ID solto. */
    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void confirmarAnonimizacaoDeAnexoForaDoProcessoFalha() throws Exception {
        anexoPendente(7L);
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);

        mvc.perform(post("/processos/1/documento-clinico/999/confirmar-anonimizacao")
                .param("confirmo", "true").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("não encontrado")));

        verify(anexoRepository, never()).save(any());
    }

    /**
     * Idempotencia/coerencia: um documento que ja e material do avaliador
     * (inclusive os de processos LEGADOS, convertidos antes da trava) nao esta
     * "pendente" e nao passa por esta acao.
     */
    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void confirmarAnonimizacaoDeDocumentoJaLiberadoFalha() throws Exception {
        Anexo legado = new Anexo();
        legado.setId(8L);
        legado.setTipo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        legado.setNomeArquivo("laudo-legado.pdf");
        processo.addAnexo(legado);
        when(anexoRepository.findById(8L)).thenReturn(Optional.of(legado));
        when(processoService.edicaoBloqueada(processo)).thenReturn(false);

        mvc.perform(post("/processos/1/documento-clinico/8/confirmar-anonimizacao")
                .param("confirmo", "true").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("erro", org.hamcrest.Matchers.containsString("não está pendente")));

        verify(anexoRepository, never()).save(any());
    }
}

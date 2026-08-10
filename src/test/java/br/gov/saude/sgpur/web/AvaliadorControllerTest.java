package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.DecisaoFinalService;
import br.gov.saude.sgpur.service.MensagemAvaliadorService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import br.gov.saude.sgpur.service.TempoRespostaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes do AvaliadorController:
 * - Medico so vota no seu proprio processo.
 * - 403 para processo alheio ou parecer ja emitido.
 * - Voto grava origem/dataHoraVoto/votadoPor corretamente.
 */
@WebMvcTest(AvaliadorController.class)
class AvaliadorControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private UsuarioRepository usuarioRepo;
    @MockitoBean private ParecerRepository parecerRepo;
    @MockitoBean private MembroUrgenciaRenalRepository membroRepo;
    @MockitoBean private AnexoRepository anexoRepo;
    @MockitoBean private AnexoStorageService anexoStorage;
    @MockitoBean private ProcessoService processoService;
    @MockitoBean private SolicitacaoOnlineService solicitacaoOnlineService;
    @MockitoBean private DecisaoFinalService decisaoFinalService;
    @MockitoBean private AuditoriaService auditoria;
    @MockitoBean private TempoRespostaService tempoRespostaService;
    @MockitoBean private MensagemAvaliadorService mensagemAvaliadorService;
    // O POST de voto usa um TransactionTemplate proprio (transacoes curtas e
    // independentes - ver AvaliadorController.registrarVoto). Aqui basta o
    // gerenciador mockado: o TransactionTemplate executa o callback normalmente
    // e commit/rollback viram no-ops. A garantia transacional de verdade e
    // coberta por AvaliadorVotoTransacaoIntegrationTest (contexto real + H2).
    @MockitoBean private PlatformTransactionManager txManager;

    private MembroUrgenciaRenal membro;
    private Usuario usuario;
    private Processo processo;
    private Parecer parecer;

    @BeforeEach
    void setUp() {
        when(tempoRespostaService.getPrazoDias()).thenReturn(7);

        membro = new MembroUrgenciaRenal("HCPA", "Veronica Horbe", "veronica@hcpa.edu.br");
        membro.setId(10L);
        // resolverMembro recarrega o membro por MembroUrgenciaRenalRepository.findById
        // (ver AvaliadorController - evita depender do proxy LAZY de Usuario.membro).
        when(membroRepo.findById(10L)).thenReturn(java.util.Optional.of(membro));

        usuario = new Usuario();
        usuario.setUsername("avaliador1");
        usuario.setPerfil(Perfil.AVALIADOR);
        usuario.setMembro(membro);

        processo = new Processo();
        processo.setId(1L);
        processo.setNumero("01/2026");
        processo.setPacienteNome("Maria Rosa Silva");
        processo.setStatus(StatusProcesso.ENVIADO);

        parecer = new Parecer(membro);
        parecer.setId(100L);
        parecer.setProcesso(processo);
        parecer.setDataEnvio(LocalDate.now());
        // resultado null = pendente
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void listaProcessosPendentesSemNomeCompleto() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findPendentesComProcesso(10L))
            .thenReturn(List.of(parecer));
        when(anexoRepo.findByProcessoIdAndTipo(1L, TipoAnexo.SOLICITACAO_AVALIADOR))
            .thenReturn(List.of());

        mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andExpect(view().name("avaliador/lista"))
            // Iniciais M.R.S. devem aparecer no model
            .andExpect(model().attributeExists("iniciaisPorProcesso"))
            // Nome completo NAO deve aparecer na resposta renderizada
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Maria Rosa Silva"))));
    }

    /**
     * F6 do relatorio de vistoria de brechas (2026-08-10) - Achado 10: um
     * processo decidido (maioria simples/excecao do coordenador) ANTES de
     * este avaliador conseguir votar aparece na secao "Processos decididos
     * sem o seu voto", mas SO com numero + iniciais - NUNCA o resultado da
     * decisao (Deferido/Indeferido) nem o nome completo do paciente
     * (imparcialidade, mesma protecao das demais secoes desta tela).
     */
    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void listaMostraProcessosDispensadosSemResultadoNemNomeCompleto() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findPendentesComProcesso(10L)).thenReturn(List.of());
        when(anexoRepo.findByProcessoIdAndTipo(org.mockito.ArgumentMatchers.anyLong(),
            eq(TipoAnexo.SOLICITACAO_AVALIADOR))).thenReturn(List.of());

        Processo processoDecidido = new Processo();
        processoDecidido.setId(2L);
        processoDecidido.setNumero("02/2026");
        processoDecidido.setPacienteNome("Joao Batista Nunes");
        processoDecidido.setStatus(StatusProcesso.DEFERIDO);
        Parecer parecerDispensado = new Parecer(membro);
        parecerDispensado.setId(99L);
        parecerDispensado.setProcesso(processoDecidido);
        // resultado continua null - nunca votou, foi dispensado.

        when(parecerRepo.findDispensadosComProcesso(eq(10L), any()))
            .thenReturn(List.of(parecerDispensado));

        mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("02/2026")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("J.B.N.")))
            // NUNCA o nome completo do paciente do processo dispensado
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Joao Batista Nunes"))))
            // NUNCA o resultado da decisao (o avaliador nao vota nem sabe o desfecho)
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Deferido"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Indeferido"))));
    }

    /**
     * Espelho do teste acima: sem nenhum processo dispensado, a secao inteira
     * some (nao polui a tela no caso comum).
     */
    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void listaNaoMostraSecaoDeDispensadosQuandoNaoHaNenhum() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findPendentesComProcesso(10L)).thenReturn(List.of());

        mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Processos decididos sem o seu voto"))));
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void listaSeparaAtrasadosDosDemaisPendentes() throws Exception {
        // dataEnvio bem no passado + prazo-meta de 7 dias (mock padrao do setUp) => atrasado
        parecer.setDataEnvio(LocalDate.now().minusDays(30));
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findPendentesComProcesso(10L))
            .thenReturn(List.of(parecer));
        when(anexoRepo.findByProcessoIdAndTipo(1L, TipoAnexo.SOLICITACAO_AVALIADOR))
            .thenReturn(List.of());

        mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andExpect(view().name("avaliador/lista"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Atrasados")))
            .andExpect(model().attribute("pareceresAtrasados", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(model().attribute("pareceresDemais", org.hamcrest.Matchers.hasSize(0)))
            // Com TODOS os pendentes atrasados, o card "Demais pendentes" nao pode
            // ser renderizado: com o th:if antigo (or) ele aparecia so com os
            // cabecalhos e nenhuma linha - tabela vazia na cara do avaliador.
            // (o texto tambem aparece num comentario HTML do template, por isso
            // a busca e pelo cabecalho renderizado: >Demais pendentes<)
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString(">Demais pendentes<"))));
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void listaMostraCardDemaisPendentesQuandoHaPendenteDentroDoPrazo() throws Exception {
        parecer.setDataEnvio(LocalDate.now().minusDays(30)); // atrasado

        Processo noPrazo = new Processo();
        noPrazo.setId(2L);
        noPrazo.setNumero("02/2026");
        noPrazo.setPacienteNome("Ana Beatriz Lima");
        noPrazo.setStatus(StatusProcesso.ENVIADO);
        Parecer parecerNoPrazo = new Parecer(membro);
        parecerNoPrazo.setId(101L);
        parecerNoPrazo.setProcesso(noPrazo);
        parecerNoPrazo.setDataEnvio(LocalDate.now());

        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findPendentesComProcesso(10L))
            .thenReturn(List.of(parecer, parecerNoPrazo));
        when(anexoRepo.findByProcessoIdAndTipo(any(Long.class), any())).thenReturn(List.of());

        mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("pareceresAtrasados", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(model().attribute("pareceresDemais", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(">Demais pendentes<")));
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void listaNaoMostraSecaoAtrasadosQuandoNenhumEstaForaDoPrazo() throws Exception {
        // dataEnvio recente (hoje) + prazo-meta de 7 dias => dentro do prazo
        parecer.setDataEnvio(LocalDate.now());
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findPendentesComProcesso(10L))
            .thenReturn(List.of(parecer));
        when(anexoRepo.findByProcessoIdAndTipo(1L, TipoAnexo.SOLICITACAO_AVALIADOR))
            .thenReturn(List.of());

        mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("pareceresAtrasados", org.hamcrest.Matchers.hasSize(0)))
            .andExpect(model().attribute("pareceresDemais", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Atrasados (acima"))));
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void painelExibeContadoresEHistoricoDoMembroLogado() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findPendentesComProcesso(10L))
            .thenReturn(List.of(parecer)); // 1 pendente
        when(anexoRepo.findByProcessoIdAndTipo(any(Long.class), any()))
            .thenReturn(List.of());

        // Contadores do repo (apenas do membro logado)
        when(parecerRepo.countByMembroId(10L)).thenReturn(5L);
        when(parecerRepo.countByMembroIdAndResultadoNotNull(10L)).thenReturn(4L);
        when(parecerRepo.countByMembroIdAndResultado(10L, ResultadoParecer.FAVORAVEL))
            .thenReturn(2L);
        when(parecerRepo.countByMembroIdAndResultado(10L, ResultadoParecer.NAO_FAVORAVEL))
            .thenReturn(1L);
        when(parecerRepo.countByMembroIdAndResultado(10L, ResultadoParecer.SOLICITA_INFORMACAO))
            .thenReturn(1L);

        // Historico: parecer ja votado deste membro
        Processo outro = new Processo();
        outro.setId(7L);
        outro.setNumero("07/2026");
        outro.setPacienteNome("Joao Pedro Alves");
        Parecer votado = new Parecer(membro);
        votado.setId(200L);
        votado.setProcesso(outro);
        votado.setResultado(ResultadoParecer.FAVORAVEL);
        votado.setDataResposta(LocalDate.now());
        when(parecerRepo.findHistoricoComProcesso(10L))
            .thenReturn(List.of(votado));

        mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andExpect(view().name("avaliador/lista"))
            .andExpect(model().attribute("totalAtribuidos", 5L))
            .andExpect(model().attribute("totalPendentes", 1))
            .andExpect(model().attribute("totalAvaliados", 4L))
            .andExpect(model().attribute("favoraveis", 2L))
            .andExpect(model().attribute("naoFavoraveis", 1L))
            .andExpect(model().attribute("solicitaInfo", 1L))
            // Historico e projetado em DTO (nunca a entidade Parecer/Processo completa,
            // que carregaria pacienteNome/solicitanteEquipe/co-avaliadores) - verifica
            // so os campos relevantes expostos pela view.
            .andExpect(model().attribute("historico", org.hamcrest.Matchers.hasSize(1)))
            // Historico usa apenas iniciais — nunca nome completo
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Joao Pedro Alves"))));

        // O historico do portal so consulta o membro logado (10L)
        verify(parecerRepo).findHistoricoComProcesso(10L);
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void contagemDePendentesIgnoraVotadosEProcessosNaoAtivos() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));

        // Pendente valido (processo ENVIADO, sem resultado)
        Parecer pendenteAtivo = new Parecer(membro);
        pendenteAtivo.setProcesso(processo); // status ENVIADO
        pendenteAtivo.setDataEnvio(LocalDate.now());

        // Processo ja decidido — nao conta mesmo sem resultado no parecer
        Processo decidido = new Processo();
        decidido.setId(2L);
        decidido.setStatus(StatusProcesso.DEFERIDO);
        Parecer pendenteInativo = new Parecer(membro);
        pendenteInativo.setProcesso(decidido);
        pendenteInativo.setDataEnvio(LocalDate.now());

        // O repositorio ja filtra resultado nulo + dataEnvio nao nula; o filtro de
        // status (ENVIADO) acontece no controller/advice.
        // Duas consultas distintas fazem esse mesmo papel hoje: findPendentesComProcesso
        // (fetch join, usada por lista() para a PROPRIA pagina - continua carregando
        // entidades, pois o template precisa delas) e a query de count() dedicada
        // (usada por GlobalModelAdvice.pendentesAvaliador(), que gera o atributo
        // "pendentesAvaliador" verificado abaixo - resolvida direto no banco, sem
        // carregar nenhuma entidade Parecer/Processo). Cada uma precisa de seu
        // proprio stub, refletindo o MESMO criterio (so o processo ENVIADO conta).
        when(parecerRepo.findPendentesComProcesso(10L))
            .thenReturn(List.of(pendenteAtivo, pendenteInativo));
        when(parecerRepo.countByMembroIdAndResultadoIsNullAndDataEnvioIsNotNullAndProcessoStatusIn(
                eq(10L), any()))
            .thenReturn(1L);
        when(anexoRepo.findByProcessoIdAndTipo(any(Long.class), any()))
            .thenReturn(List.of());

        // So 1 dos 2 deve ser contado (o do processo ativo)
        mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("pendentesAvaliador", 1L));
    }

    @Test
    @WithMockUser(username = "operador1", roles = "OPERADOR")
    void contagemDePendentesNaoConsultaRepositorioParaNaoAvaliador() throws Exception {
        // Sem ROLE_AVALIADOR o advice deve curto-circuitar: o badge nao e calculado
        // e o repositorio de pareceres NAO e consultado para a contagem.
        // (a propria rota /avaliador rejeita o OPERADOR; o ponto e o advice global,
        // que usa o metodo original - GlobalModelAdvice nunca chama a variante com
        // fetch join, essa e exclusiva de lista())
        mvc.perform(get("/avaliador"));

        verify(parecerRepo, never())
            .findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull(any());
        verify(parecerRepo, never())
            .findPendentesComProcesso(any());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void votarExibeFormularioComIniciaisSemPdf() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));
        when(anexoRepo.findByProcessoIdAndTipo(1L, TipoAnexo.SOLICITACAO_AVALIADOR))
            .thenReturn(List.of());

        mvc.perform(get("/avaliador/1"))
            .andExpect(status().isOk())
            .andExpect(view().name("avaliador/votar"))
            .andExpect(model().attribute("iniciais", "M.R.S."))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Maria Rosa Silva"))));
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void votarExibe403ParaProcessoAlheio() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        // Processo 99 nao tem parecer deste membro
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(99L, 10L)).thenReturn(Optional.empty());

        mvc.perform(get("/avaliador/99"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void votarExibe403QuandoParecerJaEmitido() throws Exception {
        parecer.setResultado(ResultadoParecer.FAVORAVEL); // ja votou
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));

        mvc.perform(get("/avaliador/1"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoGravaCamposDeNaoRepudio() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));
        when(parecerRepo.save(any(Parecer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(processoService.atualizarStatusPorPareceres(1L)).thenReturn(processo);
        when(processoService.tentarDecisaoAutomatica(1L)).thenReturn(processo);
        doNothing().when(auditoria).registrar(any(), any(), any());

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL")
                .param("justificativa", "Clinicamente indicado"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/avaliador"));

        // Verifica que os campos de nao-repudio foram gravados
        verify(parecerRepo).save(argThat(p ->
            p.getResultado() == ResultadoParecer.FAVORAVEL
            && p.getOrigem() == OrigemParecer.AVALIADOR_SISTEMA
            && p.getVotadoPor().equals("avaliador1")
            && p.getDataHoraVoto() != null
            && p.getDataResposta() != null
        ));
        verify(processoService).atualizarStatusPorPareceres(1L);
        verify(auditoria).registrar(eq("PARECER_VOTADO"), any(), any());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoPersisteJustificativa() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));
        when(parecerRepo.save(any(Parecer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(processoService.atualizarStatusPorPareceres(1L)).thenReturn(processo);
        when(processoService.tentarDecisaoAutomatica(1L)).thenReturn(processo);
        doNothing().when(auditoria).registrar(any(), any(), any());

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL")
                .param("justificativa", "  Quadro clinico compativel  "))
            .andExpect(status().is3xxRedirection());

        // Justificativa salva com trim aplicado
        verify(parecerRepo).save(argThat(p ->
            "Quadro clinico compativel".equals(p.getJustificativa())));
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoComArquivoAnexaComoAnexoAvaliador() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));
        when(parecerRepo.save(any(Parecer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(processoService.atualizarStatusPorPareceres(1L)).thenReturn(processo);
        when(processoService.tentarDecisaoAutomatica(1L)).thenReturn(processo);
        Anexo anexo = new Anexo();
        when(anexoStorage.salvar(eq(processo), eq(TipoAnexo.ANEXO_AVALIADOR), any(), any()))
            .thenReturn(anexo);
        doNothing().when(auditoria).registrar(any(), any(), any());

        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "exame.pdf",
            "application/pdf", "conteudo".getBytes());

        mvc.perform(multipart("/avaliador/1/votar")
                .file(arquivo)
                .with(csrf())
                .param("resultado", "FAVORAVEL"))
            .andExpect(status().is3xxRedirection());

        verify(anexoStorage).salvar(eq(processo), eq(TipoAnexo.ANEXO_AVALIADOR), any(), any());
        verify(anexoRepo).save(anexo);
        org.assertj.core.api.Assertions.assertThat(anexo.getParecer()).isSameAs(parecer);
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoSemArquivoNaoTentaAnexar() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));
        when(parecerRepo.save(any(Parecer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(processoService.atualizarStatusPorPareceres(1L)).thenReturn(processo);
        when(processoService.tentarDecisaoAutomatica(1L)).thenReturn(processo);
        doNothing().when(auditoria).registrar(any(), any(), any());

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL"))
            .andExpect(status().is3xxRedirection());

        verifyNoInteractions(anexoStorage);
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoJustificativaVaziaViraNull() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));
        when(parecerRepo.save(any(Parecer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(processoService.atualizarStatusPorPareceres(1L)).thenReturn(processo);
        when(processoService.tentarDecisaoAutomatica(1L)).thenReturn(processo);
        doNothing().when(auditoria).registrar(any(), any(), any());

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL")
                .param("justificativa", "   "))
            .andExpect(status().is3xxRedirection());

        // Justificativa em branco nao deve ser persistida (null)
        verify(parecerRepo).save(argThat(p -> p.getJustificativa() == null));
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoNaoFavoravelSemJustificativaERejeitado() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "NAO_FAVORAVEL"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/avaliador/1"))
            .andExpect(flash().attributeExists("erro"));

        // Nada deve ter sido gravado - a validacao roda ANTES de abrir a TX do voto.
        verify(parecerRepo, never()).save(any());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoNaoFavoravelComJustificativaEmBrancoERejeitado() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "NAO_FAVORAVEL")
                .param("justificativa", "   "))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/avaliador/1"))
            .andExpect(flash().attributeExists("erro"));

        verify(parecerRepo, never()).save(any());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoSolicitaInformacaoSemJustificativaERejeitado() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "SOLICITA_INFORMACAO"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/avaliador/1"))
            .andExpect(flash().attributeExists("erro"));

        verify(parecerRepo, never()).save(any());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoNaoFavoravelComJustificativaEAceito() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));
        when(parecerRepo.save(any(Parecer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(processoService.atualizarStatusPorPareceres(1L)).thenReturn(processo);
        when(processoService.tentarDecisaoAutomatica(1L)).thenReturn(processo);
        doNothing().when(auditoria).registrar(any(), any(), any());

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "NAO_FAVORAVEL")
                .param("justificativa", "Sem criterio clinico de urgencia."))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/avaliador"));

        verify(parecerRepo).save(argThat(p ->
            p.getResultado() == ResultadoParecer.NAO_FAVORAVEL
            && "Sem criterio clinico de urgencia.".equals(p.getJustificativa())));
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoFavoravelSemJustificativaContinuaAceito() throws Exception {
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));
        when(parecerRepo.save(any(Parecer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(processoService.atualizarStatusPorPareceres(1L)).thenReturn(processo);
        when(processoService.tentarDecisaoAutomatica(1L)).thenReturn(processo);
        doNothing().when(auditoria).registrar(any(), any(), any());

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/avaliador"));

        verify(parecerRepo).save(argThat(p ->
            p.getResultado() == ResultadoParecer.FAVORAVEL && p.getJustificativa() == null));
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoExibe403QuandoParecerJaEmitido() throws Exception {
        parecer.setResultado(ResultadoParecer.NAO_FAVORAVEL); // ja votou
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL"))
            .andExpect(status().isForbidden());

        // Nao deve ter salvo nada
        verify(parecerRepo, never()).save(any());
    }

    @Test
    @WithMockUser(username = "avaliador1", roles = "AVALIADOR")
    void registrarVotoExibe403QuandoProcessoNaoEstaEmEnvio() throws Exception {
        processo.setStatus(StatusProcesso.DEFERIDO); // processo ja decidido
        when(usuarioRepo.findByUsername("avaliador1")).thenReturn(Optional.of(usuario));
        when(parecerRepo.findByProcessoIdAndMembroIdComProcesso(1L, 10L)).thenReturn(Optional.of(parecer));

        mvc.perform(post("/avaliador/1/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL"))
            .andExpect(status().isForbidden());

        verify(parecerRepo, never()).save(any());
    }
}

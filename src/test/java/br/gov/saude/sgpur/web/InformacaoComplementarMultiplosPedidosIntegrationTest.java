package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regressao do bug de PRODUCAO relatado no processo 12/2026 (2026-08-11):
 * a tela do Portal do Solicitante mostrava, ao longo do tempo e SEM nenhuma
 * acao do solicitante, dois estados contraditorios da mesma pausa
 * ("Informacao complementar necessaria" x "Informacoes complementares
 * recebidas"), enquanto a tela do OPERADOR contava outra historia ainda
 * ("3 pareceres recebidos", etapa Respostas verde).
 *
 * <p>Estado real de producao que motivou o teste (conferido por SELECT no
 * Postgres da VM): processo 17/"12-2026" com <b>DOIS</b> pareceres
 * SOLICITA_INFORMACAO simultaneos (20:37 e 21:59), um FAVORAVEL (15:49) e um
 * unico anexo INFO_COMPLEMENTAR (22:04). O modelo antigo tratava a pausa como
 * UMA "rodada" com UM instante inicial ({@code max(dataHoraVoto)}), entao um
 * pedido novo apagava retroativamente a resposta ja enviada.</p>
 *
 * <p>Cobre N = 1, 2 e 3 pedidos simultaneos (o total fixo por processo),
 * porque nada no codigo impede que os tres avaliadores pecam informacao ao
 * mesmo tempo.</p>
 *
 * <p>Integracao real (H2, servicos reais, sem {@code @MockitoBean}): a
 * divergencia era entre consultas/colecoes JPA de verdade, algo que um teste
 * com repositorio mockado nunca expressaria.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-info-multi;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.solicitante.habilitado=true",
        "app.anexos.dir=./target/test-anexos-info-multi"
})
class InformacaoComplementarMultiplosPedidosIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UsuarioRepository usuarioRepo;
    @Autowired private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired private ProcessoRepository processoRepo;
    @Autowired private ParecerRepository parecerRepo;
    @Autowired private MembroUrgenciaRenalRepository membroRepo;
    @Autowired private AnexoRepository anexoRepo;
    @Autowired private br.gov.saude.sgpur.repository.HistoricoParecerRepository historicoParecerRepo;
    @Autowired private ProcessoService processoService;
    @Autowired private SolicitacaoOnlineService solicitacaoService;
    @Autowired private br.gov.saude.sgpur.service.FluxoProcessoService fluxoService;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private Long processoId;
    private Long solicitacaoId;
    private final List<Long> parecerIds = new ArrayList<>();

    @BeforeEach
    @Transactional
    void preparar() {
        anexoRepo.deleteAll();
        // FK historico_parecer -> processo: precisa sair antes (ver CLAUDE.md, F4).
        historicoParecerRepo.deleteAll();
        parecerRepo.deleteAll();
        solicitacaoRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();
        parecerIds.clear();

        Usuario dono = usuarioRepo.findByUsername("solicitante-multi-it").orElseGet(() -> {
            Usuario u = new Usuario();
            u.setUsername("solicitante-multi-it");
            u.setSenha("{noop}x");
            u.setNome("Equipe Solicitante Multi IT");
            u.setEmail("solicitante-multi-it@example.com");
            u.setPerfil(Perfil.SOLICITANTE);
            u.setEquipeSolicitante("HCPA");
            return usuarioRepo.save(u);
        });

        Processo p = new Processo();
        p.setNumero("12/2026");
        p.setAno(2026);
        p.setSequencial(12);
        p.setPacienteNome("Ana Silva Paciente");
        p.setPacienteRgct("123123123");
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 8, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        String[][] medicos = { { "HCPA", "Ana Nefro" }, { "ISCMPA", "Bruno Nefro" }, { "HNSC", "Carla Nefro" } };
        for (String[] medico : medicos) {
            MembroUrgenciaRenal m = membroRepo.saveAndFlush(
                    new MembroUrgenciaRenal(medico[0], medico[1],
                            medico[1].replace(" ", ".").toLowerCase() + "@example.com"));
            Parecer par = new Parecer(m);
            par.setProcesso(p);
            par.setDataEnvio(LocalDate.of(2026, 8, 11));
            parecerRepo.saveAndFlush(par);
            parecerIds.add(par.getId());
        }

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(dono);
        s.setPacienteNome("Ana Silva Paciente");
        s.setPacienteRgct("123123123");
        s.setSolicitanteEquipe("HCPA");
        s.setSolicitanteEmail("solicitante-multi-it@example.com");
        s.setDataSituacaoEspecial(LocalDate.of(2026, 8, 1));
        s.setJustificativaClinica("Justificativa clinica de teste.");
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(processoRepo.getReferenceById(processoId));
        solicitacaoRepo.saveAndFlush(s);
        solicitacaoId = s.getId();
    }

    /** Um avaliador pede informacao, com instante de voto explicito. */
    private void pedirInformacao(int indiceParecer, LocalDateTime quando, String justificativa) {
        Parecer par = parecerRepo.findById(parecerIds.get(indiceParecer)).orElseThrow();
        par.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        par.setDataResposta(quando.toLocalDate());
        par.setDataHoraVoto(quando);
        par.setJustificativa(justificativa);
        parecerRepo.saveAndFlush(par);
        processoService.atualizarStatusPorPareceres(processoId);
    }

    private void votar(int indiceParecer, ResultadoParecer resultado, LocalDateTime quando) {
        Parecer par = parecerRepo.findById(parecerIds.get(indiceParecer)).orElseThrow();
        par.setResultado(resultado);
        par.setDataResposta(quando.toLocalDate());
        par.setDataHoraVoto(quando);
        parecerRepo.saveAndFlush(par);
        processoService.atualizarStatusPorPareceres(processoId);
    }

    private void solicitanteEnvia() throws Exception {
        MockMultipartFile arquivo = new MockMultipartFile("arquivos", "exames.pdf",
                MediaType.APPLICATION_PDF_VALUE, "conteudo".getBytes());
        mvc.perform(multipart("/solicitante/" + solicitacaoId + "/informacao-complementar")
                        .file(arquivo).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * Le o estado como o controller le, mas dentro de uma transacao propria:
     * {@code estadoInformacaoComplementar} navega colecoes LAZY do processo
     * ({@code open-in-view: false}).
     */
    private SolicitacaoOnlineService.EstadoInformacaoComplementar estado() {
        return new org.springframework.transaction.support.TransactionTemplate(txManager)
                .execute(tx -> solicitacaoService.estadoInformacaoComplementar(
                        solicitacaoService.buscarParaDetalhe(solicitacaoId)));
    }

    // ---------------------------------------------------------------
    // N = 1 (caso classico, ja funcionava - guarda contra regressao)
    // ---------------------------------------------------------------

    @Test
    @WithMockUser(username = "solicitante-multi-it", roles = "SOLICITANTE")
    void umUnicoPedidoPrecisaEnviarAntesEJaEnviouDepois() throws Exception {
        pedirInformacao(0, LocalDateTime.now().minusHours(2), "Envie o exame X.");

        assertThat(estado().precisaEnviar()).isTrue();
        assertThat(estado().totalPedidos()).isEqualTo(1);
        assertThat(estado().textosPendentes()).containsExactly("Envie o exame X.");

        solicitanteEnvia();

        assertThat(estado().precisaEnviar()).isFalse();
        assertThat(estado().jaEnviouTudo()).isTrue();
    }

    // ---------------------------------------------------------------
    // N = 2 (o caso REAL de producao, processo 12/2026)
    // ---------------------------------------------------------------

    /**
     * O bug exato: o solicitante responde ao 1o pedido; um 2o avaliador pede
     * informacao DEPOIS; a tela do solicitante tem que continuar coerente -
     * volta a pedir acao por causa do pedido NOVO, e nunca "esquece" que o
     * pedido antigo ja foi respondido (o texto do cartao lista so o pendente).
     */
    @Test
    @WithMockUser(username = "solicitante-multi-it", roles = "SOLICITANTE")
    void segundoPedidoDepoisDaRespostaNaoApagaARespostaJaEnviadaERelistaSoOPedidoNovo() throws Exception {
        pedirInformacao(0, LocalDateTime.now().minusHours(3), "Pedido A: envie o exame X.");
        solicitanteEnvia();
        assertThat(estado().jaEnviouTudo()).isTrue();

        // Segundo avaliador pede informacao DEPOIS do envio.
        pedirInformacao(1, LocalDateTime.now(), "Pedido B: envie o laudo Y.");

        var depois = estado();
        assertThat(depois.pausaAtiva()).isTrue();
        assertThat(depois.totalPedidos()).isEqualTo(2);
        assertThat(depois.pedidosPendentes()).isEqualTo(1);
        assertThat(depois.precisaEnviar()).isTrue();
        // So o pedido NOVO e cobrado: o "Pedido A" ja foi atendido.
        assertThat(depois.textosPendentes()).containsExactly("Pedido B: envie o laudo Y.");

        // E a tela reflete exatamente isso, sem contradicao.
        mvc.perform(get("/solicitante/" + solicitacaoId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pedido B: envie o laudo Y.")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Pedido A: envie o exame X."))));
    }

    /**
     * Sequencia real de producao (12/2026): dois pedidos, o envio veio depois
     * dos dois. Nao pode sobrar pendencia, e a tela nao pode oscilar entre
     * dois carregamentos consecutivos sem nenhuma acao do solicitante.
     */
    @Test
    @WithMockUser(username = "solicitante-multi-it", roles = "SOLICITANTE")
    void doisPedidosRespondidosDeUmaVezSoNaoDeixamPendenciaEOEstadoNaoOscila() throws Exception {
        pedirInformacao(0, LocalDateTime.now().minusHours(2), "TESTE");
        votar(2, ResultadoParecer.FAVORAVEL, LocalDateTime.now().minusHours(6));
        pedirInformacao(1, LocalDateTime.now().minusMinutes(5), "Solicito envio de exames.");

        assertThat(estado().pedidosPendentes()).isEqualTo(2);
        assertThat(estado().textosPendentes())
                .containsExactly("TESTE", "Solicito envio de exames.");

        solicitanteEnvia();

        // Tres leituras seguidas, sem nenhuma acao entre elas: mesmo resultado.
        for (int i = 0; i < 3; i++) {
            var e = estado();
            assertThat(e.jaEnviouTudo()).isTrue();
            assertThat(e.precisaEnviar()).isFalse();
        }
        mvc.perform(get("/solicitante/" + solicitacaoId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Informações complementares recebidas")));
    }

    /**
     * A lista ({@code /solicitante}) e o detalhe do MESMO pedido nao podem
     * discordar: antes, o badge ambar "Ação necessária" continuava aceso
     * depois de o solicitante ja ter enviado tudo, enquanto o detalhe dizia
     * "Informacoes complementares recebidas".
     */
    @Test
    @WithMockUser(username = "solicitante-multi-it", roles = "SOLICITANTE")
    void listaEDetalheConcordamDepoisDoEnvio() throws Exception {
        pedirInformacao(0, LocalDateTime.now().minusHours(2), "Pedido A.");
        pedirInformacao(1, LocalDateTime.now().minusHours(1), "Pedido B.");

        mvc.perform(get("/solicitante"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ação necessária")));

        solicitanteEnvia();

        mvc.perform(get("/solicitante"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Ação necessária"))));
        mvc.perform(get("/solicitante/" + solicitacaoId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Informações complementares recebidas")));
    }

    // ---------------------------------------------------------------
    // N = 3 (todos os avaliadores pedem informacao ao mesmo tempo)
    // ---------------------------------------------------------------

    @Test
    @WithMockUser(username = "solicitante-multi-it", roles = "SOLICITANTE")
    void tresPedidosSimultaneosSaoListadosEUmUnicoEnvioResolveTodos() throws Exception {
        pedirInformacao(0, LocalDateTime.now().minusHours(3), "Pedido 1.");
        pedirInformacao(1, LocalDateTime.now().minusHours(2), "Pedido 2.");
        pedirInformacao(2, LocalDateTime.now().minusHours(1), "Pedido 3.");

        var antes = estado();
        assertThat(antes.totalPedidos()).isEqualTo(3);
        assertThat(antes.pedidosPendentes()).isEqualTo(3);
        assertThat(antes.textosPendentes()).containsExactly("Pedido 1.", "Pedido 2.", "Pedido 3.");

        // O cartao lista os TRES pedidos (numerados), nao so um.
        mvc.perform(get("/solicitante/" + solicitacaoId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pedido 1.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pedido 2.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pedido 3.")));

        solicitanteEnvia();
        assertThat(estado().jaEnviouTudo()).isTrue();
    }

    /**
     * Com 3 de 3 em pausa e a analise retomada, os TRES pareceres voltam a ser
     * pendencia limpa e NENHUM caminho pode decidir automaticamente (nao ha
     * maioria nenhuma - os tres votos ainda precisam ser dados).
     */
    @Test
    @WithMockUser(username = "operador-multi-it", roles = "OPERADOR")
    void retomarComTresPedidosReabreOsTresENaoDecideNada() throws Exception {
        pedirInformacao(0, LocalDateTime.now().minusHours(3), "Pedido 1.");
        pedirInformacao(1, LocalDateTime.now().minusHours(2), "Pedido 2.");
        pedirInformacao(2, LocalDateTime.now().minusHours(1), "Pedido 3.");

        mvc.perform(post("/processos/" + processoId + "/retomar-analise").with(csrf()))
                .andExpect(status().is3xxRedirection());

        Processo depois = processoRepo.findById(processoId).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        assertThat(parecerRepo.findAll()).allSatisfy(par -> {
            assertThat(par.getResultado()).isNull();
            assertThat(par.getDataHoraVoto()).isNull();
            assertThat(par.getJustificativa()).isNull();
            // dataEnvio preservada: o processo FOI enviado de fato.
            assertThat(par.getDataEnvio()).isNotNull();
        });

        // Sem pausa e sem pedido em aberto, o Portal volta ao "Em analise".
        var estadoPos = estado();
        assertThat(estadoPos.pausaAtiva()).isFalse();
        assertThat(estadoPos.precisaEnviar()).isFalse();
    }

    // ---------------------------------------------------------------
    // Fonte unica: Portal do Solicitante x resto do sistema
    // ---------------------------------------------------------------

    /**
     * O Portal do Solicitante nao pode mais depender SO do campo derivado
     * {@code Processo.status}: se ele dessincronizar do fato (parecer
     * SOLICITA_INFORMACAO vivo), o operador ve a decisao bloqueada
     * ({@code ProcessoValidator.temPedidoInformacaoAtivo}) enquanto o
     * solicitante via "Em analise" e nem tinha o formulario de envio.
     */
    @Test
    @WithMockUser(username = "solicitante-multi-it", roles = "SOLICITANTE")
    void pausaEDetectadaPeloFATOMesmoComOStatusDoProcessoDessincronizado() throws Exception {
        pedirInformacao(0, LocalDateTime.now().minusHours(1), "Pedido A.");
        // Dessincroniza na marra o campo derivado (cenario dos achados C/D do
        // relatorio "dois votos deferem durante a pausa").
        Processo p = processoRepo.findById(processoId).orElseThrow();
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);

        var e = estado();
        assertThat(e.pausaAtiva()).isTrue();
        assertThat(e.precisaEnviar()).isTrue();
        mvc.perform(get("/solicitante/" + solicitacaoId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pedido A.")));
    }

    /**
     * O operador tambem nao pode ver "todos os pareceres recebidos" quando
     * parte dos pareceres e pedido de informacao (nao veredito): a etapa
     * Respostas nao fica CONCLUIDA enquanto a pausa estiver ativa.
     */
    @Test
    @WithMockUser(username = "operador-multi-it", roles = "OPERADOR")
    void telaDoOperadorNaoTrataPedidoDeInformacaoComoParecerRecebido() throws Exception {
        votar(2, ResultadoParecer.FAVORAVEL, LocalDateTime.now().minusHours(6));
        pedirInformacao(0, LocalDateTime.now().minusHours(2), "Pedido A.");
        pedirInformacao(1, LocalDateTime.now().minusHours(1), "Pedido B.");

        // 3 de 3 pareceres com resultado != null, mas 2 sao pedido de
        // informacao: a etapa Respostas NAO pode ficar concluida (verde).
        var etapas = new org.springframework.transaction.support.TransactionTemplate(txManager)
                .execute(tx -> fluxoService.montarEtapas(
                        processoRepo.findByIdComPareceres(processoId).orElseThrow()));
        var respostas = etapas.stream()
                .filter(e -> e.chave() == br.gov.saude.sgpur.service.dto.EtapaFluxo.Chave.RESPOSTAS)
                .findFirst().orElseThrow();
        assertThat(respostas.isConcluida()).isFalse();
        assertThat(respostas.detalhe()).contains("PAUSADO");

        mvc.perform(get("/processos/" + processoId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("3 pareceres recebidos"))));
        assertThat(anexoRepo.findByProcessoIdOrderByDataUploadAsc(processoId))
                .noneMatch(a -> a.getTipo() == TipoAnexo.INFO_COMPLEMENTAR);
    }
}

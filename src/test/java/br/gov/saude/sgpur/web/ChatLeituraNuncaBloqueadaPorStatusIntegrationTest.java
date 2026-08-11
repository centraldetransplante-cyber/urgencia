package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.MensagemAvaliador.RemetenteMensagemAvaliador;
import br.gov.saude.sgpur.domain.MensagemSolicitacao.RemetenteMensagem;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.MensagemAvaliadorRepository;
import br.gov.saude.sgpur.repository.MensagemSolicitacaoRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.MensagemAvaliadorService;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GUARDA DE REGRESSAO PARA UMA CLASSE INTEIRA DE BUG, nao para um endpoint:
 * <i>"um contador/badge soma uma mensagem nao lida de uma thread que a UI nao
 * deixa mais abrir"</i>.
 *
 * <p>Motivo: em 2026-08-11 esse defeito foi confirmado no canal
 * <b>Avaliador &lt;-&gt; Operador</b> — o badge da navbar do avaliador contava
 * mensagens de qualquer processo, mas {@code GET /avaliador/{processoId}}
 * devolvia 403 depois do voto/da decisao, e nao havia nenhum outro caminho
 * para a conversa (corrigido; ver
 * {@link AvaliadorLeituraProcessoConcluidoIntegrationTest}). O dono do
 * produto pediu explicitamente a garantia de que a MESMA classe de bug nao
 * existe nos demais canais.
 *
 * <p>Esta classe cobre o outro canal, <b>Solicitante &lt;-&gt; Operador</b>
 * ({@code MensagemSolicitacao}), nos dois lados, e o lado OPERADOR do canal do
 * avaliador — todos verificados por HTTP real (contexto Spring + H2 reais, sem
 * mock), nunca por leitura de codigo:
 * <ul>
 *   <li>solicitante: {@code GET /solicitante/{id}} e
 *       {@code GET /solicitante/{id}/mensagens} abrem em <b>todos</b> os
 *       valores de {@link StatusSolicitacaoOnline} — inclusive
 *       {@code CANCELADA} e {@code PROCESSO_EXCLUIDO}, os unicos que restringem
 *       algo (o ENVIO, nunca a leitura);</li>
 *   <li>operador: {@code GET /processos/{id}} e os dois polls de chat
 *       ({@code /mensagens} do solicitante e
 *       {@code /avaliador/{membroId}/mensagens}) continuam abrindo com o
 *       processo ja ENCERRADO — {@code bloqueadoPorEncerrado} nunca foi
 *       aplicado a leitura, e nao deve passar a ser;</li>
 *   <li>a caixa de entrada {@code /processos/mensagens-avaliadores} lista a
 *       thread de um processo encerrado e o link que ela oferece de fato
 *       abre.</li>
 * </ul>
 *
 * <p>Se algum dia alguem adicionar um gate de status a qualquer um desses GETs
 * (leitura), este teste falha — e o badge correspondente voltaria a contar
 * mensagem inalcancavel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-chat-leitura-status;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.solicitante.habilitado=true",
    "app.anexos.dir=./target/test-anexos-chat-leitura-status"
})
class ChatLeituraNuncaBloqueadaPorStatusIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UsuarioRepository usuarioRepo;
    @Autowired private ProcessoRepository processoRepo;
    @Autowired private ParecerRepository parecerRepo;
    @Autowired private MembroUrgenciaRenalRepository membroRepo;
    @Autowired private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired private MensagemSolicitacaoRepository mensagemSolicitacaoRepo;
    @Autowired private MensagemAvaliadorRepository mensagemAvaliadorRepo;
    @Autowired private MensagemSolicitacaoService mensagemSolicitacaoService;
    @Autowired private MensagemAvaliadorService mensagemAvaliadorService;

    private Long processoEncerradoId;
    private Long solicitacaoDoProcessoId;
    private Long solicitacaoSemProcessoId;
    private Long membroId;

    @BeforeEach
    @Transactional
    void preparar() {
        mensagemAvaliadorRepo.deleteAll();
        mensagemSolicitacaoRepo.deleteAll();
        solicitacaoRepo.deleteAll();
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();
        usuarioRepo.findByUsername("solic-guarda-it").ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername("oper-guarda-it").ifPresent(usuarioRepo::delete);

        Usuario solicitante = new Usuario();
        solicitante.setUsername("solic-guarda-it");
        solicitante.setSenha("{noop}x");
        solicitante.setNome("Solicitante Guarda");
        solicitante.setEmail("solic-guarda@example.com");
        solicitante.setPerfil(Perfil.SOLICITANTE);
        solicitante.setEquipeSolicitante("HCPA");
        usuarioRepo.saveAndFlush(solicitante);

        Usuario operador = new Usuario();
        operador.setUsername("oper-guarda-it");
        operador.setSenha("{noop}x");
        operador.setNome("Operador Guarda");
        operador.setEmail("oper-guarda@example.com");
        operador.setPerfil(Perfil.OPERADOR);
        usuarioRepo.saveAndFlush(operador);

        // Processo ENCERRADO (o estado em que a UI mais restringe acao) com
        // solicitacao de origem e um parecer -> os 2 chats existem nele.
        Processo p = new Processo();
        p.setNumero("71/2026");
        p.setAno(2026);
        p.setSequencial(71);
        p.setPacienteNome("Paciente Guarda Chat");
        p.setPacienteRgct("555444333");
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("solic-guarda@example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
        p.setStatus(StatusProcesso.DEFERIDO);
        processoRepo.saveAndFlush(p);
        processoEncerradoId = p.getId();

        MembroUrgenciaRenal membro = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HCPA", "Dr. Guarda Chat", "guarda-chat@example.com"));
        membroId = membro.getId();
        Parecer par = new Parecer(membro);
        par.setProcesso(p);
        par.setDataEnvio(LocalDate.of(2026, 6, 2));
        par.setResultado(ResultadoParecer.FAVORAVEL);
        par.setDataResposta(LocalDate.of(2026, 6, 3));
        parecerRepo.saveAndFlush(par);

        SolicitacaoOnline comProcesso = novaSolicitacao(solicitante, "Paciente Guarda Chat", "555444333");
        comProcesso.setStatus(StatusSolicitacaoOnline.APROVADA);
        comProcesso.setProcessoGerado(p);
        solicitacaoRepo.saveAndFlush(comProcesso);
        solicitacaoDoProcessoId = comProcesso.getId();

        SolicitacaoOnline semProcesso = novaSolicitacao(solicitante, "Paciente Sem Processo", "111000111");
        semProcesso.setStatus(StatusSolicitacaoOnline.ENVIADA);
        solicitacaoRepo.saveAndFlush(semProcesso);
        solicitacaoSemProcessoId = semProcesso.getId();

        // Mensagens NAO LIDAS nos dois canais - exatamente o que os badges somam.
        mensagemSolicitacaoService.enviar(comProcesso, "Mensagem do operador ao solicitante.",
            RemetenteMensagem.OPERADOR, operador.getId());
        mensagemSolicitacaoService.enviar(comProcesso, "Mensagem do solicitante ao operador.",
            RemetenteMensagem.SOLICITANTE, solicitante.getId());
        mensagemAvaliadorService.enviar(p, membro, "Mensagem do avaliador ao operador.",
            RemetenteMensagemAvaliador.AVALIADOR, 4242L);
    }

    private SolicitacaoOnline novaSolicitacao(Usuario dono, String paciente, String rgct) {
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(dono);
        s.setPacienteNome(paciente);
        s.setPacienteRgct(rgct);
        s.setSolicitanteEquipe("HCPA");
        s.setSolicitanteEmail("solic-guarda@example.com");
        s.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
        s.setJustificativaClinica("Justificativa de teste.");
        return s;
    }

    // ------------------------------------------------------------------
    // Canal Solicitante <-> Operador, lado SOLICITANTE
    // ------------------------------------------------------------------

    /**
     * O badge/poll global do solicitante ({@code GET /solicitante/nao-lidas-count})
     * soma mensagens de TODAS as solicitacoes dele, sem filtro de status. Para
     * nao repetir o bug do Portal do Avaliador, a tela que contem o chat
     * precisa abrir em qualquer status - inclusive nos dois unicos que
     * restringem algo hoje ({@code CANCELADA} restringe nada desde a F5;
     * {@code PROCESSO_EXCLUIDO} restringe so o ENVIO).
     */
    @ParameterizedTest
    @EnumSource(StatusSolicitacaoOnline.class)
    @WithMockUser(username = "solic-guarda-it", roles = "SOLICITANTE")
    void solicitanteSempreConsegueABRIRODetalheEOChatEmQualquerStatus(StatusSolicitacaoOnline status) throws Exception {
        trocarStatus(solicitacaoDoProcessoId, status);

        mvc.perform(get("/solicitante/" + solicitacaoDoProcessoId))
            .andExpect(status().isOk());
        mvc.perform(get("/solicitante/" + solicitacaoDoProcessoId + "/mensagens"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mensagens").isArray());
    }

    @ParameterizedTest
    @EnumSource(StatusSolicitacaoOnline.class)
    @WithMockUser(username = "solic-guarda-it", roles = "SOLICITANTE")
    void solicitanteSempreConsegueABRIRODetalheDeSolicitacaoSemProcessoEmQualquerStatus(
            StatusSolicitacaoOnline status) throws Exception {
        trocarStatus(solicitacaoSemProcessoId, status);

        mvc.perform(get("/solicitante/" + solicitacaoSemProcessoId))
            .andExpect(status().isOk());
        mvc.perform(get("/solicitante/" + solicitacaoSemProcessoId + "/mensagens"))
            .andExpect(status().isOk());
    }

    /**
     * Sem {@code @Transactional} de proposito: chamado de dentro da propria
     * classe de teste (auto-invocacao — a anotacao seria um no-op silencioso) e
     * o {@code saveAndFlush} ja commita sozinho. Rele do banco para garantir
     * que o cenario do parametro foi realmente aplicado (senao o teste
     * "passaria" sempre no mesmo status).
     */
    private void trocarStatus(Long solicitacaoId, StatusSolicitacaoOnline status) {
        SolicitacaoOnline s = solicitacaoRepo.findById(solicitacaoId).orElseThrow();
        s.setStatus(status);
        solicitacaoRepo.saveAndFlush(s);
        assertThat(solicitacaoRepo.findById(solicitacaoId).orElseThrow().getStatus()).isEqualTo(status);
    }

    // ------------------------------------------------------------------
    // Canal Solicitante <-> Operador e Avaliador <-> Operador, lado OPERADOR
    // ------------------------------------------------------------------

    /**
     * {@code bloqueadoPorEncerrado} trava as ESCRITAS do processo encerrado
     * (etapas 1-4, upload, exclusao de anexo, lembrete) - nunca a leitura da
     * tela nem os polls de chat. Se isso mudar, os dois badges do operador
     * (mensagens de solicitante e de avaliador) passariam a contar conversa
     * inalcancavel.
     */
    @Test
    @WithMockUser(username = "oper-guarda-it", roles = "OPERADOR")
    void operadorAbreODetalheEOsDoisChatsMesmoComOProcessoJaENCERRADO() throws Exception {
        assertThat(processoRepo.findById(processoEncerradoId).orElseThrow().getStatus().isFinalizado())
            .as("o cenario precisa ser mesmo de processo encerrado")
            .isTrue();

        mvc.perform(get("/processos/" + processoEncerradoId))
            .andExpect(status().isOk());

        // Chat com o solicitante: abre e traz as mensagens.
        mvc.perform(get("/processos/" + processoEncerradoId + "/mensagens"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mensagens").isArray())
            .andExpect(jsonPath("$.mensagens.length()").value(2));

        // Chat com o avaliador: abre e traz a mensagem que o badge esta somando.
        mvc.perform(get("/processos/" + processoEncerradoId + "/avaliador/" + membroId + "/mensagens"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mensagens.length()").value(1))
            // Escrita FECHADA depois de decidido (regra de produto do chat do
            // avaliador, inalterada) - mas a LEITURA acima continua liberada.
            .andExpect(jsonPath("$.podeEnviar").value(false));
    }

    @Test
    @WithMockUser(username = "oper-guarda-it", roles = "OPERADOR")
    void caixaDeEntradaDoOperadorListaThreadDeProcessoEncerradoEOLinkDelaAbre() throws Exception {
        String html = mvc.perform(get("/processos/mensagens-avaliadores"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html)
            .as("a thread do processo encerrado aparece na caixa de entrada")
            .contains("71/2026");
        assertThat(html)
            .as("a caixa de entrada oferece link para a tela do processo")
            .contains("/processos/" + processoEncerradoId);

        // E o link de fato abre (nao e um beco sem saida).
        mvc.perform(get("/processos/" + processoEncerradoId).param("aba", "pane-respostas"))
            .andExpect(status().isOk());
    }
}

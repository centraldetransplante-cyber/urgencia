package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.LogAuditoria;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.MensagemAvaliador;
import br.gov.saude.sgpur.domain.MensagemAvaliador.RemetenteMensagemAvaliador;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.LogAuditoriaRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.MensagemAvaliadorRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.MensagemAvaliadorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, servicos e repositorios
 * REAIS) do chat AVALIADOR &lt;-&gt; OPERADOR
 * (docs/RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md).
 *
 * <p><b>Por que integracao, nao {@code @WebMvcTest} com mock do servico:</b>
 * seguindo a convencao do projeto (ver CLAUDE.md, "Rota que grava algo
 * irreversivel exige teste do caminho de falha SEM mock do servico") - enviar
 * mensagem a um avaliador e irreversivel na pratica assim que ele le, e a
 * verificacao de nome (item mais critico de seguranca desta feature, secao
 * 8.1 do relatorio) so e exercitada de verdade com o
 * {@link br.gov.saude.sgpur.service.VerificadorNomePaciente} real, nao um
 * mock que sempre devolveria o que o teste mandar.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-mensagem-avaliador-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-mensagem-avaliador-it"
})
class MensagemAvaliadorIntegrationTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;
    @Autowired
    private MensagemAvaliadorRepository mensagemRepo;
    @Autowired
    private LogAuditoriaRepository auditoriaRepo;

    private Long processoId;
    private Long membroId;
    private Long outroMembroId;

    @BeforeEach
    @Transactional
    void preparar() {
        mensagemRepo.deleteAll();
        parecerRepo.deleteAll();
        usuarioRepo.findByUsername("operador-msg-it").ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername("avaliador-msg-it").ifPresent(usuarioRepo::delete);
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        Processo p = new Processo();
        p.setNumero("88/2026");
        p.setAno(2026);
        p.setSequencial(88);
        p.setPacienteNome("Mariana da Rosa Martins");
        p.setPacienteRgct("999888777");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("Hospital de Clinicas de Porto Alegre");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        MembroUrgenciaRenal membro = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HCPA", "Ana Avaliadora", "ana.avaliadora@example.com"));
        membroId = membro.getId();
        MembroUrgenciaRenal outroMembro = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("ISCMPA", "Bruno Outro", "bruno.outro@example.com"));
        outroMembroId = outroMembro.getId();

        Parecer par = new Parecer(membro);
        par.setProcesso(p);
        par.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(par);

        Usuario operador = new Usuario();
        operador.setUsername("operador-msg-it");
        operador.setSenha("{noop}x");
        operador.setNome("Operador Teste");
        operador.setEmail("operador@example.com");
        operador.setPerfil(Perfil.OPERADOR);
        usuarioRepo.saveAndFlush(operador);

        Usuario avaliador = new Usuario();
        avaliador.setUsername("avaliador-msg-it");
        avaliador.setSenha("{noop}x");
        avaliador.setNome("Ana Avaliadora");
        avaliador.setEmail("ana.avaliadora@example.com");
        avaliador.setPerfil(Perfil.AVALIADOR);
        avaliador.setMembro(membro);
        usuarioRepo.saveAndFlush(avaliador);
    }

    // ---- Lado OPERADOR ----------------------------------------------------

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void operadorEnviaMensagemLivreEPersisteNoBanco() throws Exception {
        mvc.perform(post("/processos/" + processoId + "/avaliador/" + membroId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", "O PDF deste processo abriu em branco, poderia reenviar?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        List<MensagemAvaliador> mensagens = mensagemRepo.findByProcessoIdAndMembroIdOrderByDataEnvioAsc(processoId, membroId);
        assertThat(mensagens).hasSize(1);
        assertThat(mensagens.get(0).getTexto()).isEqualTo("O PDF deste processo abriu em branco, poderia reenviar?");
        assertThat(mensagens.get(0).getRemetente()).isEqualTo(RemetenteMensagemAvaliador.OPERADOR);
    }

    /**
     * REGRA CENTRAL DE SEGURANCA (secao 8.1 do relatorio): mensagem do
     * operador que cita o nome COMPLETO do paciente e recusada e NUNCA chega
     * ao banco - verificado com o {@code VerificadorNomePaciente} de
     * verdade, sem nenhum mock.
     */
    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void operadorNaoConsegueEnviarMensagemComNomeCompletoDoPaciente() throws Exception {
        mvc.perform(post("/processos/" + processoId + "/avaliador/" + membroId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", "A Mariana Martins pediu para confirmar a data do exame."))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());

        assertThat(mensagemRepo.findByProcessoIdAndMembroIdOrderByDataEnvioAsc(processoId, membroId)).isEmpty();
    }

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void operadorNaoConsegueEnviarMensagemQueCitaEquipeSolicitante() throws Exception {
        // Calibragem de 2026-08-10 (S4, achado A5): 1 token generico da
        // equipe isolado ("clinicas") deixou de bloquear sozinho - a
        // mensagem precisa citar >=2 tokens da equipe (ver
        // VerificadorNomePacienteTest para os casos que passaram a ser
        // LIVRE de proposito).
        mvc.perform(post("/processos/" + processoId + "/avaliador/" + membroId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", "O Hospital de Clinicas de Porto Alegre ligou pedindo prioridade neste caso."))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());

        assertThat(mensagemRepo.findByProcessoIdAndMembroIdOrderByDataEnvioAsc(processoId, membroId)).isEmpty();
    }

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void operadorNaoConsegueEnviarMensagemParaMembroQueNaoEAvaliadorDoProcesso() throws Exception {
        mvc.perform(post("/processos/" + processoId + "/avaliador/" + outroMembroId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", "Mensagem qualquer."))
                .andExpect(status().isBadRequest());

        assertThat(mensagemRepo.findByProcessoIdAndMembroIdOrderByDataEnvioAsc(processoId, outroMembroId)).isEmpty();
    }

    /** Caminho de falha SEM mock: apagar mensagem de outro remetente e recusado pelo servico real, sem 500. */
    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void operadorNaoConsegueApagarMensagemDeOutroOperador() throws Exception {
        var msg = new br.gov.saude.sgpur.domain.MensagemAvaliador();
        msg.setProcesso(processoRepo.findById(processoId).orElseThrow());
        msg.setMembro(membroRepo.findById(membroId).orElseThrow());
        msg.setRemetente(RemetenteMensagemAvaliador.OPERADOR);
        msg.setRemetenteId(999999L); // outro operador, nao o autenticado neste teste
        msg.setTexto("Mensagem de outro operador.");
        msg.setDataEnvio(java.time.LocalDateTime.now());
        msg = mensagemRepo.saveAndFlush(msg);

        mvc.perform(post("/processos/" + processoId + "/avaliador/" + membroId + "/mensagem/" + msg.getId() + "/apagar/ajax")
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());

        MensagemAvaliador relida = mensagemRepo.findById(msg.getId()).orElseThrow();
        assertThat(relida.isDeletada()).isFalse();
        assertThat(relida.getTexto()).isEqualTo("Mensagem de outro operador.");
    }

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void caixaDeEntradaContaMensagensNaoLidasDeAvaliador() throws Exception {
        var msg = new MensagemAvaliador();
        msg.setProcesso(processoRepo.findById(processoId).orElseThrow());
        msg.setMembro(membroRepo.findById(membroId).orElseThrow());
        msg.setRemetente(RemetenteMensagemAvaliador.AVALIADOR);
        msg.setRemetenteId(1L);
        msg.setTexto("Duvida operacional.");
        msg.setDataEnvio(java.time.LocalDateTime.now());
        mensagemRepo.saveAndFlush(msg);

        mvc.perform(get("/processos/mensagens-avaliadores/nao-lidas-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    /**
     * Bug real reportado em producao (2026-08-07): abrir
     * {@code /processos/mensagens-avaliadores} com pelo menos UMA thread
     * quebrava com "Algo deu errado" - o servidor devolvia 200 (nao 500)
     * porque a resposta ja tinha comecado a ser escrita quando o Thymeleaf
     * falhou, entao o status HTTP ficou preso em 200 mesmo com a excecao.
     * Causa raiz (confirmada no log real via SSH, nao suposta): o {@code
     * th:href} do botao "Abrir processo" em
     * {@code mensagens-avaliadores-lista.html} tentava concatenar um
     * fragmento de ancora direto depois do link expression -
     * {@code @{/processos/{id}(id=${t.processoId()})}#respostas} - sintaxe
     * invalida (o attoparser tenta interpretar {@code #respostas} como um
     * objeto utilitario do Thymeleaf, ex. {@code #dates}, e falha o parse).
     * So aparecia com a caixa de entrada NAO VAZIA: o teste anterior
     * ({@code caixaDeEntradaContaMensagensNaoLidasDeAvaliador}) so batia no
     * endpoint JSON de contagem, nunca no HTML da lista em si - por isso a
     * suite passava "verde" com o bug em producao. Corrigido concatenando
     * com {@code +} (sintaxe padrao do Thymeleaf para Link Expression +
     * String literal). Renderiza o HTML de verdade, nao so o status.
     *
     * <p><b>Atualizado em 2026-08-10 (F2/S2, achado A2):</b> o alvo do link
     * deixou de ser a ancora morta {@code #respostas} (nao existia elemento
     * com esse id - o painel real e {@code pane-respostas} - e nenhum JS lia
     * {@code location.hash}, entao o clique caia sempre na aba calculada pelo
     * servidor, quase nunca Respostas) e passou a ser
     * {@code ?aba=pane-respostas}, lido por {@code ProcessoDetalheController
     * .detalhe} para escolher a aba ativa de verdade.</p>
     */
    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void caixaDeEntradaRenderizaListaComLinkParaAAbaRespostas() throws Exception {
        var msg = new MensagemAvaliador();
        msg.setProcesso(processoRepo.findById(processoId).orElseThrow());
        msg.setMembro(membroRepo.findById(membroId).orElseThrow());
        msg.setRemetente(RemetenteMensagemAvaliador.AVALIADOR);
        msg.setRemetenteId(1L);
        msg.setTexto("Duvida operacional.");
        msg.setDataEnvio(java.time.LocalDateTime.now());
        mensagemRepo.saveAndFlush(msg);

        String html = mvc.perform(get("/processos/mensagens-avaliadores"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .doesNotContain("Algo deu errado")
                .contains("/processos/" + processoId + "?aba=pane-respostas");
    }

    // ---- Lado AVALIADOR ----------------------------------------------------

    @Test
    @WithMockUser(username = "avaliador-msg-it", roles = "AVALIADOR")
    void avaliadorEnviaMensagemEPersisteNoBanco() throws Exception {
        mvc.perform(post("/avaliador/" + processoId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", "O PDF nao abriu no meu celular."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        List<MensagemAvaliador> mensagens = mensagemRepo.findByProcessoIdAndMembroIdOrderByDataEnvioAsc(processoId, membroId);
        assertThat(mensagens).hasSize(1);
        assertThat(mensagens.get(0).getRemetente()).isEqualTo(RemetenteMensagemAvaliador.AVALIADOR);
    }

    // ---- Card do chat nasce recolhido/expandido conforme ja existe conversa
    //      (bug real relatado em producao em 2026-08-07: o card nascia SEMPRE
    //      recolhido, escondendo mensagens do operador ja recebidas - ver
    //      CLAUDE.md) -----------------------------------------------------

    @Test
    @WithMockUser(username = "avaliador-msg-it", roles = "AVALIADOR")
    void telaDeVotoDoAvaliadorNascecomChatRecolhidoQuandoAindaNaoHaConversa() throws Exception {
        String html = mvc.perform(get("/avaliador/" + processoId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"chatBodyAvaliador\"");
        // O card-body do chat NAO deve ter a classe "show" (Bootstrap collapse)
        // quando a thread esta vazia - procura o trecho do elemento inteiro e
        // confirma que "show" nao aparece antes de "chatBodyAvaliador".
        int idx = html.indexOf("id=\"chatBodyAvaliador\"");
        String trechoAntes = html.substring(Math.max(0, idx - 200), idx);
        assertThat(trechoAntes)
                .as("Sem nenhuma mensagem na thread, o card do chat deve nascer RECOLHIDO "
                        + "(sem a classe Bootstrap 'show') - a acao primaria da tela e votar.")
                .doesNotContain("collapse show");
    }

    @Test
    @WithMockUser(username = "avaliador-msg-it", roles = "AVALIADOR")
    void telaDeVotoDoAvaliadorNascecomChatEXPANDIDOQuandoJaExisteConversa() throws Exception {
        var msg = new MensagemAvaliador();
        msg.setProcesso(processoRepo.findById(processoId).orElseThrow());
        msg.setMembro(membroRepo.findById(membroId).orElseThrow());
        msg.setRemetente(RemetenteMensagemAvaliador.OPERADOR);
        msg.setRemetenteId(999L);
        msg.setTexto("Poderia revisar o laudo anexado?");
        msg.setDataEnvio(java.time.LocalDateTime.now());
        mensagemRepo.saveAndFlush(msg);

        String html = mvc.perform(get("/avaliador/" + processoId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int idx = html.indexOf("id=\"chatBodyAvaliador\"");
        String trechoAntes = html.substring(Math.max(0, idx - 200), idx);
        assertThat(trechoAntes)
                .as("Ja existindo mensagem na thread (mesmo so do lado do operador), o card do "
                        + "chat deve nascer EXPANDIDO (classe 'show') - bug real corrigido em "
                        + "2026-08-07: o card nascia sempre recolhido, escondendo mensagem ja "
                        + "recebida do operador (CLAUDE.md).")
                .contains("collapse show");
    }

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void telaDeDetalheDoProcessoNascecomThreadDoAvaliadorEXPANDIDAQuandoJaExisteConversa() throws Exception {
        var msg = new MensagemAvaliador();
        msg.setProcesso(processoRepo.findById(processoId).orElseThrow());
        msg.setMembro(membroRepo.findById(membroId).orElseThrow());
        msg.setRemetente(RemetenteMensagemAvaliador.AVALIADOR);
        msg.setRemetenteId(1L);
        msg.setTexto("Duvida operacional sobre o PDF.");
        msg.setDataEnvio(java.time.LocalDateTime.now());
        mensagemRepo.saveAndFlush(msg);

        Long parecerId = parecerRepo.findByProcessoIdAndMembroId(processoId, membroId)
                .orElseThrow().getId();

        String html = mvc.perform(get("/processos/" + processoId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String idAtributo = "id=\"chatAval" + parecerId + "\"";
        assertThat(html).contains(idAtributo);
        int idx = html.indexOf(idAtributo);
        String trechoAntes = html.substring(Math.max(0, idx - 200), idx);
        assertThat(trechoAntes)
                .as("A thread com este avaliador, na tela de detalhe do processo, deve nascer "
                        + "EXPANDIDA quando ja existe conversa - mesma correcao do lado do "
                        + "avaliador, CLAUDE.md 2026-08-07.")
                .contains("show");
    }

    // -------------------------------------------------------------------
    // F2 / S2 (achado A2) - ?aba=... na URL escolhe a aba ativa de verdade
    // -------------------------------------------------------------------

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void detalheComAbaRespostasNaQueryStringAbreNaAbaRespostasDeVerdade() throws Exception {
        String html = mvc.perform(get("/processos/" + processoId + "?aba=pane-respostas"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int idxRespostas = html.indexOf("id=\"pane-respostas\"");
        assertThat(idxRespostas).isPositive();
        String trechoRespostas = html.substring(Math.max(0, idxRespostas - 120), idxRespostas);
        assertThat(trechoRespostas)
                .as("Com ?aba=pane-respostas, o pane Respostas deve nascer ATIVO (classes "
                        + "Bootstrap 'show active'), nao o pane calculado automaticamente.")
                .contains("show active");

        int idxEnvio = html.indexOf("id=\"pane-envio\"");
        assertThat(idxEnvio).isPositive();
        String trechoEnvio = html.substring(Math.max(0, idxEnvio - 120), idxEnvio);
        assertThat(trechoEnvio)
                .as("O pane Envio (o que seria escolhido pelo calculo automatico neste "
                        + "fixture, ja que o envio ainda nao foi registrado) NAO deve estar "
                        + "ativo quando ?aba=pane-respostas foi explicitamente pedido.")
                .doesNotContain("show active");
    }

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void detalheSemParametroAbaUsaOCalculoAutomaticoDeSempre() throws Exception {
        // Fixture: envio ainda NAO foi registrado - o calculo automatico
        // (primeira etapa nao concluida) sempre escolhe "Envio" aqui. Confirma
        // que a introducao do parametro ?aba nao alterou o comportamento
        // padrao (sem o parametro).
        String html = mvc.perform(get("/processos/" + processoId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int idxEnvio = html.indexOf("id=\"pane-envio\"");
        assertThat(idxEnvio).isPositive();
        String trechoEnvio = html.substring(Math.max(0, idxEnvio - 120), idxEnvio);
        assertThat(trechoEnvio).contains("show active");
    }

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void detalheComAbaInvalidaNaQueryStringIgnoraEUsaOCalculoAutomatico() throws Exception {
        // Nunca aceita string arbitraria da query string como paneId - so
        // valores que realmente existem no wizard deste processo.
        String html = mvc.perform(get("/processos/" + processoId + "?aba=<script>alert(1)</script>"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        int idxEnvio = html.indexOf("id=\"pane-envio\"");
        assertThat(idxEnvio).isPositive();
        assertThat(html.substring(Math.max(0, idxEnvio - 120), idxEnvio)).contains("show active");
    }

    // -------------------------------------------------------------------
    // F2 / S1 (achado A1) - so marca como lida quando a conversa e de fato
    // exibida; o poll AJAX (chamado pelo JS so quando a aba Respostas esta
    // realmente ativa) e a UNICA coisa que marca como lida - nunca o GET do
    // detalhe da tela em si, em nenhuma aba.
    // -------------------------------------------------------------------

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void abrirDetalheDoProcessoEmQualquerAbaNuncaMarcaMensagemDoAvaliadorComoLidaSozinho() throws Exception {
        var msg = new MensagemAvaliador();
        msg.setProcesso(processoRepo.findById(processoId).orElseThrow());
        msg.setMembro(membroRepo.findById(membroId).orElseThrow());
        msg.setRemetente(RemetenteMensagemAvaliador.AVALIADOR);
        msg.setRemetenteId(1L);
        msg.setTexto("O PDF nao abriu no meu celular.");
        msg.setDataEnvio(java.time.LocalDateTime.now());
        msg = mensagemRepo.saveAndFlush(msg);
        Long msgId = msg.getId();

        // GET simples (aba automatica = Envio neste fixture).
        mvc.perform(get("/processos/" + processoId)).andExpect(status().isOk());
        assertThat(mensagemRepo.findById(msgId).orElseThrow().isLida())
                .as("Abrir a tela numa aba diferente de Respostas nao pode marcar a "
                        + "mensagem do avaliador como lida.")
                .isFalse();

        // GET explicitamente pedindo a aba Respostas: o SERVIDOR ainda nao
        // marca nada sozinho no render do HTML - so o poll AJAX seguinte
        // (disparado pelo JS, testado abaixo) marca. Confirma que renderizar
        // a pagina, por si so, nunca e o gatilho de "marcar como lida".
        mvc.perform(get("/processos/" + processoId + "?aba=pane-respostas")).andExpect(status().isOk());
        assertThat(mensagemRepo.findById(msgId).orElseThrow().isLida()).isFalse();
    }

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void pollDoChatDoAvaliadorSemMarcarLidaNaoAlteraMensagensNaoLidas() throws Exception {
        var msg = new MensagemAvaliador();
        msg.setProcesso(processoRepo.findById(processoId).orElseThrow());
        msg.setMembro(membroRepo.findById(membroId).orElseThrow());
        msg.setRemetente(RemetenteMensagemAvaliador.AVALIADOR);
        msg.setRemetenteId(1L);
        msg.setTexto("O PDF nao abriu no meu celular.");
        msg.setDataEnvio(java.time.LocalDateTime.now());
        msg = mensagemRepo.saveAndFlush(msg);
        Long msgId = msg.getId();

        // Sem marcarLida=true (default false): e o que o JS agora faz quando
        // a instancia do poll NUNCA chega a ser criada (aba Respostas nao
        // ativa) - mas o teste bate direto no endpoint para provar a defesa
        // em profundidade do SERVIDOR, independente do JS.
        mvc.perform(get("/processos/" + processoId + "/avaliador/" + membroId + "/mensagens"))
                .andExpect(status().isOk());

        assertThat(mensagemRepo.findById(msgId).orElseThrow().isLida()).isFalse();
    }

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void pollDoChatDoAvaliadorComMarcarLidaTrueMarcaAsMensagensComoLidas() throws Exception {
        var msg = new MensagemAvaliador();
        msg.setProcesso(processoRepo.findById(processoId).orElseThrow());
        msg.setMembro(membroRepo.findById(membroId).orElseThrow());
        msg.setRemetente(RemetenteMensagemAvaliador.AVALIADOR);
        msg.setRemetenteId(1L);
        msg.setTexto("O PDF nao abriu no meu celular.");
        msg.setDataEnvio(java.time.LocalDateTime.now());
        msg = mensagemRepo.saveAndFlush(msg);
        Long msgId = msg.getId();

        mvc.perform(get("/processos/" + processoId + "/avaliador/" + membroId + "/mensagens?marcarLida=true"))
                .andExpect(status().isOk());

        assertThat(mensagemRepo.findById(msgId).orElseThrow().isLida())
                .as("marcarLida=true e o que o JS envia quando a instancia do poll e criada "
                        + "de verdade (aba Respostas ativa E a thread expandida) - esse e o "
                        + "unico caminho que deve marcar a mensagem como lida.")
                .isTrue();
    }

    /** Posse: um medico so acessa/escreve na thread de um processo em que ele E avaliador. */
    @Test
    @WithMockUser(username = "avaliador-msg-it", roles = "AVALIADOR")
    void avaliadorNaoConsegueEnviarMensagemParaProcessoQueNaoEleAvalia() throws Exception {
        Processo outro = new Processo();
        outro.setNumero("99/2026");
        outro.setAno(2026);
        outro.setSequencial(99);
        outro.setPacienteNome("Outro Paciente");
        outro.setPacienteRgct("111222333");
        outro.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        outro.setPacienteCpf("11144477735");
        outro.setPacienteSexo(Sexo.MASCULINO);
        outro.setSolicitanteEquipe("ISCMPA");
        outro.setSolicitanteEmail("equipe2@iscmpa.example.com");
        outro.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        outro.setStatus(StatusProcesso.ENVIADO);
        outro = processoRepo.saveAndFlush(outro);

        mvc.perform(get("/avaliador/" + outro.getId() + "/mensagens"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------
    // F1 / S5 - caminho de SUCESSO do soft delete (achado A6 da vistoria
    // 2026-08-10, docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md): as 6
    // asserções existentes na suíte cobriam só a RECUSA de apagar. Nenhum
    // teste, em nenhum dos 2 canais, apagava uma mensagem LEGITIMAMENTE e
    // conferia que a linha sobreviveu com texto=null - exatamente a classe
    // de bug que chegou a producao em 2026-07-28 (texto NOT NULL quebrava
    // o soft delete) e passou pelos 526 testes da epoca.
    // -------------------------------------------------------------------

    @Test
    @WithMockUser(username = "avaliador-msg-it", roles = "AVALIADOR")
    void avaliadorApagaAPropriaMensagemComSucessoSoftDeleteRelidoDoBanco() throws Exception {
        var msg = new MensagemAvaliador();
        msg.setProcesso(processoRepo.findById(processoId).orElseThrow());
        msg.setMembro(membroRepo.findById(membroId).orElseThrow());
        msg.setRemetente(RemetenteMensagemAvaliador.AVALIADOR);
        Usuario avaliadorUsuario = usuarioRepo.findByUsername("avaliador-msg-it").orElseThrow();
        msg.setRemetenteId(avaliadorUsuario.getId());
        msg.setTexto("O laudo nao abriu no meu celular.");
        msg.setDataEnvio(java.time.LocalDateTime.now());
        msg = mensagemRepo.saveAndFlush(msg);
        long totalAntes = mensagemRepo.count();

        mvc.perform(post("/avaliador/" + processoId + "/mensagem/" + msg.getId() + "/apagar/ajax")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        MensagemAvaliador relida = mensagemRepo.findById(msg.getId()).orElseThrow();
        assertThat(relida.isDeletada()).isTrue();
        assertThat(relida.getTexto()).isNull();
        assertThat(relida.getDeletadaEm()).isNotNull();
        assertThat(relida.getRemetenteId()).isEqualTo(avaliadorUsuario.getId());
        assertThat(relida.getRemetente()).isEqualTo(RemetenteMensagemAvaliador.AVALIADOR);
        // A linha NAO sumiu - continua existindo na tabela, so com o texto limpo.
        assertThat(mensagemRepo.count()).isEqualTo(totalAntes);

        // S9 (achado A15): a exclusao aparece na auditoria, sem o texto da
        // mensagem nem o nome do paciente.
        List<LogAuditoria> logs = auditoriaRepo.findAll();
        assertThat(logs).anySatisfy(l -> {
            assertThat(l.getAcao()).isEqualTo("MENSAGEM_AVALIADOR_APAGADA");
            assertThat(l.getDetalhe())
                    .doesNotContain("laudo")
                    .doesNotContain("Mariana");
        });
    }

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void operadorApagaAPropriaMensagemNoCanalDoAvaliadorComSucessoSoftDeleteRelidoDoBanco() throws Exception {
        var msg = new MensagemAvaliador();
        msg.setProcesso(processoRepo.findById(processoId).orElseThrow());
        msg.setMembro(membroRepo.findById(membroId).orElseThrow());
        msg.setRemetente(RemetenteMensagemAvaliador.OPERADOR);
        Usuario operadorUsuario = usuarioRepo.findByUsername("operador-msg-it").orElseThrow();
        msg.setRemetenteId(operadorUsuario.getId());
        msg.setTexto("Ja estamos verificando o anexo.");
        msg.setDataEnvio(java.time.LocalDateTime.now());
        msg = mensagemRepo.saveAndFlush(msg);
        long totalAntes = mensagemRepo.count();

        mvc.perform(post("/processos/" + processoId + "/avaliador/" + membroId + "/mensagem/" + msg.getId() + "/apagar/ajax")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        MensagemAvaliador relida = mensagemRepo.findById(msg.getId()).orElseThrow();
        assertThat(relida.isDeletada()).isTrue();
        assertThat(relida.getTexto()).isNull();
        assertThat(relida.getDeletadaEm()).isNotNull();
        assertThat(relida.getRemetenteId()).isEqualTo(operadorUsuario.getId());
        assertThat(mensagemRepo.count()).isEqualTo(totalAntes);

        List<LogAuditoria> logs = auditoriaRepo.findAll();
        assertThat(logs).anySatisfy(l -> {
            assertThat(l.getAcao()).isEqualTo("MENSAGEM_AVALIADOR_APAGADA");
            assertThat(l.getDetalhe()).doesNotContain("anexo");
        });
    }

    // -------------------------------------------------------------------
    // F1 / S6 - limite de tamanho de mensagem imposto no SERVIDOR (achado
    // A7): o maxlength="2000" do HTML e so UX, trivialmente burlavel via
    // DevTools/curl.
    // -------------------------------------------------------------------

    @Test
    @WithMockUser(username = "avaliador-msg-it", roles = "AVALIADOR")
    void avaliadorNaoConsegueEnviarMensagemAcimaDoLimiteDeTamanho() throws Exception {
        String textoGigante = "a".repeat(MensagemAvaliadorService.TEXTO_MAX_LENGTH + 1);

        mvc.perform(post("/avaliador/" + processoId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", textoGigante))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());

        assertThat(mensagemRepo.findByProcessoIdAndMembroIdOrderByDataEnvioAsc(processoId, membroId)).isEmpty();
    }

    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void operadorNaoConsegueEnviarMensagemAcimaDoLimiteDeTamanhoParaOAvaliador() throws Exception {
        String textoGigante = "b".repeat(MensagemAvaliadorService.TEXTO_MAX_LENGTH + 1);

        mvc.perform(post("/processos/" + processoId + "/avaliador/" + membroId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", textoGigante))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());

        assertThat(mensagemRepo.findByProcessoIdAndMembroIdOrderByDataEnvioAsc(processoId, membroId)).isEmpty();
    }

    // -------------------------------------------------------------------
    // F1 / S3 - a tela de voto ja tem seu proprio poll de chat (5s); sem
    // chatAtivoNestaTela=true, o poll GLOBAL de mensagens do avaliador
    // (layout.html, 20s) tambem rodava ali, duplicando som/toast (achado
    // A3) - e o toast global levava o medico de volta para /avaliador,
    // descartando o voto em preenchimento.
    // -------------------------------------------------------------------

    @Test
    @WithMockUser(username = "avaliador-msg-it", roles = "AVALIADOR")
    void telaDeVotoNaoRendaOPollGlobalDeMensagensDoAvaliador() throws Exception {
        String html = mvc.perform(get("/avaliador/" + processoId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // O poll LOCAL da propria tela (5s, iniciarChatSolicitacao) continua
        // presente - so o poll GLOBAL (20s, chave de sessionStorage abaixo)
        // deve estar ausente.
        assertThat(html).contains("iniciarChatSolicitacao");
        assertThat(html).doesNotContain("saur_nl_avaliador_msg");
    }

    // -------------------------------------------------------------------
    // F4 / S8.2 (achado A10): badge PROPRIO de mensagens do AVALIADOR na
    // navbar (distinto do sino de PARECERES pendentes, GlobalModelAdvice
    // .pendentesAvaliador()) - antes desta fase o avaliador nao tinha
    // NENHUM indicador persistente de mensagem nao lida, so o toast do poll
    // global (que passa e some). Renderiza uma pagina de verdade (nao
    // @WebMvcTest de status) porque o achado e sobre presenca/ausencia de
    // uma classe CSS no HTML final.
    // -------------------------------------------------------------------

    @Test
    @WithMockUser(username = "avaliador-msg-it", roles = "AVALIADOR")
    void navbarDoAvaliadorMostraOBadgeDeMensagensVisivelQuandoHaNaoLida() throws Exception {
        var msg = new MensagemAvaliador();
        msg.setProcesso(processoRepo.findById(processoId).orElseThrow());
        msg.setMembro(membroRepo.findById(membroId).orElseThrow());
        msg.setRemetente(RemetenteMensagemAvaliador.OPERADOR);
        msg.setRemetenteId(999L);
        msg.setTexto("Poderia revisar o laudo anexado?");
        msg.setDataEnvio(java.time.LocalDateTime.now());
        mensagemRepo.saveAndFlush(msg);

        String html = mvc.perform(get("/avaliador"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"navBadgeMsgAvaliadorPortalNaoLida\"");
        int idx = html.indexOf("id=\"navBadgeMsgAvaliadorPortalNaoLida\"");
        // A tag <span ...> inteira, do "<span" ate o proximo ">" fechando a
        // abertura da tag (nao o </span> de fechamento).
        int inicioTag = html.lastIndexOf("<span", idx);
        int fimTag = html.indexOf('>', idx);
        String tag = html.substring(inicioTag, fimTag + 1);
        assertThat(tag)
                .as("Com 1 mensagem nao lida do operador, o badge deve estar visivel (sem "
                        + "'d-none') e mostrar a contagem.")
                .doesNotContain("d-none");
        // O conteudo textual do <span> (entre a tag de abertura e </span>) tem a contagem.
        int fimConteudo = html.indexOf("</span>", fimTag);
        assertThat(html.substring(fimTag + 1, fimConteudo).trim()).isEqualTo("1");
    }

    @Test
    @WithMockUser(username = "avaliador-msg-it", roles = "AVALIADOR")
    void navbarDoAvaliadorEscondeOBadgeDeMensagensQuandoNaoHaNaoLida() throws Exception {
        String html = mvc.perform(get("/avaliador"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"navBadgeMsgAvaliadorPortalNaoLida\"");
        int idx = html.indexOf("id=\"navBadgeMsgAvaliadorPortalNaoLida\"");
        int inicioTag = html.lastIndexOf("<span", idx);
        int fimTag = html.indexOf('>', idx);
        String tag = html.substring(inicioTag, fimTag + 1);
        assertThat(tag)
                .as("Sem nenhuma mensagem nao lida, o badge continua no DOM (sempre "
                        + "renderizado - S8.1/S8.2) mas escondido via 'd-none'.")
                .contains("d-none");
    }
}

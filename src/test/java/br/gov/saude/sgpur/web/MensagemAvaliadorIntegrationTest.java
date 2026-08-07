package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.MensagemAvaliador;
import br.gov.saude.sgpur.domain.MensagemAvaliador.RemetenteMensagemAvaliador;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.MensagemAvaliadorRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
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
        mvc.perform(post("/processos/" + processoId + "/avaliador/" + membroId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", "O Hospital de Clinicas ligou pedindo prioridade neste caso."))
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
     */
    @Test
    @WithMockUser(username = "operador-msg-it", roles = "OPERADOR")
    void caixaDeEntradaRenderizaListaComThreadSemQuebrarNoLinkDeAncora() throws Exception {
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
                .contains("/processos/" + processoId + "#respostas");
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
        outro.setSolicitanteEquipe("ISCMPA");
        outro.setSolicitanteEmail("equipe2@iscmpa.example.com");
        outro.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        outro.setStatus(StatusProcesso.ENVIADO);
        outro = processoRepo.saveAndFlush(outro);

        mvc.perform(get("/avaliador/" + outro.getId() + "/mensagens"))
                .andExpect(status().isForbidden());
    }
}

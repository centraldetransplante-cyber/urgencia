package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.LogAuditoria;
import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.MensagemSolicitacao.RemetenteMensagem;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.LogAuditoriaRepository;
import br.gov.saude.sgpur.repository.MensagemSolicitacaoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, servicos/repositorios
 * REAIS) do chat SOLICITANTE &lt;-&gt; OPERADOR ({@link MensagemSolicitacao}),
 * cobrindo os itens F1 do plano de
 * docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md:
 *
 * <ul>
 *   <li><b>S5 (achado A6):</b> caminho de SUCESSO do soft delete, relido do
 *   banco campo a campo. Todas as asserções pre-existentes na suite so
 *   cobriam a RECUSA de apagar (mensagem de outro remetente) - nenhum teste
 *   apagava uma mensagem legitimamente e conferia que a linha sobreviveu com
 *   {@code texto=null}. E exatamente a classe de bug que chegou a producao em
 *   2026-07-28 ({@code texto NOT NULL} quebrando o soft delete) sem que os
 *   526 testes da epoca notassem.</li>
 *   <li><b>S6 (achado A7):</b> limite de 2000 caracteres imposto no
 *   SERVIDOR, nao so no {@code maxlength} do HTML (trivialmente burlavel).</li>
 *   <li><b>S9 (achado A15):</b> apagar mensagem passa a gerar
 *   {@code MENSAGEM_APAGADA} na auditoria, sem o texto nem o nome completo do
 *   paciente.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-mensagem-solicitacao-chat-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.solicitante.habilitado=true",
        "app.anexos.dir=./target/test-anexos-mensagem-solicitacao-chat-it"
})
class MensagemSolicitacaoChatIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired
    private MensagemSolicitacaoRepository mensagemRepo;
    @Autowired
    private LogAuditoriaRepository auditoriaRepo;

    private Long solicitacaoId;
    private Long solicitanteUsuarioId;
    private Long operadorUsuarioId;

    @BeforeEach
    @Transactional
    void preparar() {
        mensagemRepo.deleteAll();
        solicitacaoRepo.deleteAll();
        usuarioRepo.findByUsername("operador-chat-msol-it").ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername("solicitante-chat-msol-it").ifPresent(usuarioRepo::delete);

        Usuario operador = new Usuario();
        operador.setUsername("operador-chat-msol-it");
        operador.setSenha("{noop}x");
        operador.setNome("Operador Chat Teste");
        operador.setEmail("operador.chat@example.com");
        operador.setPerfil(Perfil.OPERADOR);
        usuarioRepo.saveAndFlush(operador);
        operadorUsuarioId = operador.getId();

        Usuario solicitante = new Usuario();
        solicitante.setUsername("solicitante-chat-msol-it");
        solicitante.setSenha("{noop}x");
        solicitante.setNome("Solicitante Chat Teste");
        solicitante.setEmail("solicitante.chat@example.com");
        solicitante.setPerfil(Perfil.SOLICITANTE);
        solicitante.setEquipeSolicitante("HCPA");
        usuarioRepo.saveAndFlush(solicitante);
        solicitanteUsuarioId = solicitante.getId();

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(solicitante);
        s.setPacienteNome("Paciente Chat Teste");
        s.setPacienteRgct("777777777");
        s.setSolicitanteEquipe("HCPA");
        s.setSolicitanteEmail("solicitante.chat@example.com");
        s.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
        s.setJustificativaClinica("Quadro grave, justificativa de teste.");
        s.setStatus(StatusSolicitacaoOnline.ENVIADA);
        solicitacaoRepo.saveAndFlush(s);
        solicitacaoId = s.getId();
    }

    // -------------------------------------------------------------------
    // S5 - caminho de SUCESSO do soft delete
    // -------------------------------------------------------------------

    @Test
    @WithMockUser(username = "solicitante-chat-msol-it", roles = "SOLICITANTE")
    void solicitanteApagaAPropriaMensagemComSucessoSoftDeleteRelidoDoBanco() throws Exception {
        MensagemSolicitacao msg = new MensagemSolicitacao();
        msg.setSolicitacaoOnline(solicitacaoRepo.findById(solicitacaoId).orElseThrow());
        msg.setRemetente(RemetenteMensagem.SOLICITANTE);
        msg.setRemetenteId(solicitanteUsuarioId);
        msg.setTexto("Gostaria de saber se ja ha novidade.");
        msg.setDataEnvio(LocalDateTime.now());
        msg = mensagemRepo.saveAndFlush(msg);
        long totalAntes = mensagemRepo.count();

        mvc.perform(post("/solicitante/" + solicitacaoId + "/mensagem/" + msg.getId() + "/apagar/ajax")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        MensagemSolicitacao relida = mensagemRepo.findById(msg.getId()).orElseThrow();
        assertThat(relida.isDeletada()).isTrue();
        assertThat(relida.getTexto()).isNull();
        assertThat(relida.getDeletadaEm()).isNotNull();
        assertThat(relida.getRemetenteId()).isEqualTo(solicitanteUsuarioId);
        assertThat(relida.getRemetente()).isEqualTo(RemetenteMensagem.SOLICITANTE);
        // A linha NAO sumiu - continua existindo na tabela, so com o texto limpo.
        assertThat(mensagemRepo.count()).isEqualTo(totalAntes);

        // S9 (achado A15): a exclusao aparece na auditoria, sem o texto nem o
        // nome completo do paciente.
        List<LogAuditoria> logs = auditoriaRepo.findAll();
        assertThat(logs).anySatisfy(l -> {
            assertThat(l.getAcao()).isEqualTo("MENSAGEM_APAGADA");
            assertThat(l.getDetalhe())
                    .doesNotContain("novidade")
                    .doesNotContain("Paciente Chat Teste");
        });
    }

    @Test
    @WithMockUser(username = "operador-chat-msol-it", roles = "OPERADOR")
    void operadorApagaAPropriaMensagemViaTriagemComSucessoSoftDeleteRelidoDoBanco() throws Exception {
        MensagemSolicitacao msg = new MensagemSolicitacao();
        msg.setSolicitacaoOnline(solicitacaoRepo.findById(solicitacaoId).orElseThrow());
        msg.setRemetente(RemetenteMensagem.OPERADOR);
        msg.setRemetenteId(operadorUsuarioId);
        msg.setTexto("Recebemos seu pedido, vamos analisar.");
        msg.setDataEnvio(LocalDateTime.now());
        msg = mensagemRepo.saveAndFlush(msg);
        long totalAntes = mensagemRepo.count();

        mvc.perform(post("/processos/solicitacoes-online/" + solicitacaoId + "/mensagem/" + msg.getId() + "/apagar/ajax")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        MensagemSolicitacao relida = mensagemRepo.findById(msg.getId()).orElseThrow();
        assertThat(relida.isDeletada()).isTrue();
        assertThat(relida.getTexto()).isNull();
        assertThat(relida.getDeletadaEm()).isNotNull();
        assertThat(relida.getRemetenteId()).isEqualTo(operadorUsuarioId);
        assertThat(mensagemRepo.count()).isEqualTo(totalAntes);

        List<LogAuditoria> logs = auditoriaRepo.findAll();
        assertThat(logs).anySatisfy(l -> {
            assertThat(l.getAcao()).isEqualTo("MENSAGEM_APAGADA");
            assertThat(l.getDetalhe()).doesNotContain("analisar");
        });
    }

    // -------------------------------------------------------------------
    // S6 - limite de tamanho de mensagem imposto no SERVIDOR
    // -------------------------------------------------------------------

    @Test
    @WithMockUser(username = "solicitante-chat-msol-it", roles = "SOLICITANTE")
    void solicitanteNaoConsegueEnviarMensagemAcimaDoLimiteDeTamanho() throws Exception {
        String textoGigante = "a".repeat(MensagemSolicitacaoService.TEXTO_MAX_LENGTH + 1);

        mvc.perform(post("/solicitante/" + solicitacaoId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", textoGigante))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());

        assertThat(mensagemRepo.findBySolicitacaoOnlineIdOrderByDataEnvioAsc(solicitacaoId)).isEmpty();
    }

    @Test
    @WithMockUser(username = "operador-chat-msol-it", roles = "OPERADOR")
    void operadorNaoConsegueEnviarMensagemAcimaDoLimiteDeTamanhoViaTriagem() throws Exception {
        String textoGigante = "b".repeat(MensagemSolicitacaoService.TEXTO_MAX_LENGTH + 1);

        mvc.perform(post("/processos/solicitacoes-online/" + solicitacaoId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", textoGigante))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());

        assertThat(mensagemRepo.findBySolicitacaoOnlineIdOrderByDataEnvioAsc(solicitacaoId)).isEmpty();
    }

    /** Mensagem exatamente no limite (2000) continua sendo aceita - so acima do limite e recusado. */
    @Test
    @WithMockUser(username = "solicitante-chat-msol-it", roles = "SOLICITANTE")
    void solicitanteConsegueEnviarMensagemExatamenteNoLimiteDeTamanho() throws Exception {
        String textoNoLimite = "c".repeat(MensagemSolicitacaoService.TEXTO_MAX_LENGTH);

        mvc.perform(post("/solicitante/" + solicitacaoId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", textoNoLimite))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        assertThat(mensagemRepo.findBySolicitacaoOnlineIdOrderByDataEnvioAsc(solicitacaoId)).hasSize(1);
    }
}

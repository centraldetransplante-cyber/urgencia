package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.MensagemSolicitacaoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, servico real, sem
 * {@code @MockitoBean}) da Fase F5 do plano de
 * {@code docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md} (S7.1, resolve o
 * achado A8).
 *
 * <p><b>Decisao de produto confirmada pelo usuario (Q4 do relatorio):</b> em
 * vez de restringir o lado do OPERADOR (que sempre permitiu enviar mensagem,
 * mesmo com a solicitacao/processo ja cancelado), o caminho escolhido foi
 * afrouxar o lado do SOLICITANTE - ele pode continuar enviando mensagem
 * mesmo depois de cancelar o proprio pedido ({@code CANCELADA}).
 * {@code PROCESSO_EXCLUIDO} continua bloqueado (estado mais definitivo -
 * ver javadoc de {@code SolicitanteController.podeEnviarMensagem}).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-solicitante-chat-f5-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.solicitante.habilitado=true",
        "app.anexos.dir=./target/test-anexos-solicitante-chat-f5-it"
})
class SolicitanteChatPodeEnviarSimetriaIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired
    private MensagemSolicitacaoRepository mensagemRepo;

    private Long solicitacaoCanceladaId;
    private Long solicitacaoProcessoExcluidoId;

    @BeforeEach
    @Transactional
    void preparar() {
        mensagemRepo.deleteAll();
        solicitacaoRepo.deleteAll();

        Usuario dono = usuarioRepo.findByUsername("solicitante-f5-it").orElseGet(() -> {
            Usuario u = new Usuario();
            u.setUsername("solicitante-f5-it");
            u.setSenha("{noop}x");
            u.setNome("Solicitante F5 Teste");
            u.setEmail("solicitantef5-it@example.com");
            u.setPerfil(Perfil.SOLICITANTE);
            u.setEquipeSolicitante("HCPA");
            return usuarioRepo.save(u);
        });

        SolicitacaoOnline cancelada = new SolicitacaoOnline();
        cancelada.setUsuarioSolicitante(dono);
        cancelada.setPacienteNome("Paciente Cancelado F5");
        cancelada.setPacienteRgct("111111111");
        cancelada.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        cancelada.setPacienteCpf("11144477735");
        cancelada.setPacienteSexo(Sexo.MASCULINO);
        cancelada.setSolicitanteEquipe("HCPA");
        cancelada.setSolicitanteEmail("solicitantef5-it@example.com");
        cancelada.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
        cancelada.setJustificativaClinica("Justificativa de teste.");
        cancelada.setStatus(StatusSolicitacaoOnline.CANCELADA);
        solicitacaoRepo.saveAndFlush(cancelada);
        solicitacaoCanceladaId = cancelada.getId();

        SolicitacaoOnline processoExcluido = new SolicitacaoOnline();
        processoExcluido.setUsuarioSolicitante(dono);
        processoExcluido.setPacienteNome("Paciente Processo Excluido F5");
        processoExcluido.setPacienteRgct("222222222");
        processoExcluido.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        processoExcluido.setPacienteCpf("11144477735");
        processoExcluido.setPacienteSexo(Sexo.MASCULINO);
        processoExcluido.setSolicitanteEquipe("HCPA");
        processoExcluido.setSolicitanteEmail("solicitantef5-it@example.com");
        processoExcluido.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
        processoExcluido.setJustificativaClinica("Justificativa de teste.");
        processoExcluido.setStatus(StatusSolicitacaoOnline.PROCESSO_EXCLUIDO);
        solicitacaoRepo.saveAndFlush(processoExcluido);
        solicitacaoProcessoExcluidoId = processoExcluido.getId();
    }

    // -------------------------------------------------------------------
    // CANCELADA: solicitante pode continuar enviando mensagem
    // -------------------------------------------------------------------

    @Test
    @WithMockUser(username = "solicitante-f5-it", roles = "SOLICITANTE")
    void pollDevolvePodeEnviarTrueParaSolicitacaoCancelada() throws Exception {
        mvc.perform(get("/solicitante/" + solicitacaoCanceladaId + "/mensagens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.podeEnviar").value(true));
    }

    @Test
    @WithMockUser(username = "solicitante-f5-it", roles = "SOLICITANTE")
    void solicitanteConsegueEnviarMensagemViaAjaxDepoisDeCancelarOProprioPedido() throws Exception {
        mvc.perform(post("/solicitante/" + solicitacaoCanceladaId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", "Cancelei o pedido, mas gostaria de explicar o motivo."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        assertThat(mensagemRepo.findBySolicitacaoOnlineIdOrderByDataEnvioAsc(solicitacaoCanceladaId))
                .hasSize(1)
                .first()
                .satisfies(m -> assertThat(m.getTexto())
                        .isEqualTo("Cancelei o pedido, mas gostaria de explicar o motivo."));
    }

    @Test
    @WithMockUser(username = "solicitante-f5-it", roles = "SOLICITANTE")
    void solicitanteConsegueEnviarMensagemViaEndpointClassicoDepoisDeCancelarOProprioPedido() throws Exception {
        mvc.perform(post("/solicitante/" + solicitacaoCanceladaId + "/mensagem")
                        .with(csrf())
                        .param("texto", "Mensagem pelo formulario classico apos cancelar."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/solicitante/" + solicitacaoCanceladaId))
                .andExpect(flash().attributeCount(0));

        assertThat(mensagemRepo.findBySolicitacaoOnlineIdOrderByDataEnvioAsc(solicitacaoCanceladaId))
                .anySatisfy(m -> assertThat(m.getTexto())
                        .isEqualTo("Mensagem pelo formulario classico apos cancelar."));
    }

    // -------------------------------------------------------------------
    // PROCESSO_EXCLUIDO: continua bloqueado (nao regride)
    // -------------------------------------------------------------------

    @Test
    @WithMockUser(username = "solicitante-f5-it", roles = "SOLICITANTE")
    void pollDevolvePodeEnviarFalseParaSolicitacaoComProcessoExcluido() throws Exception {
        mvc.perform(get("/solicitante/" + solicitacaoProcessoExcluidoId + "/mensagens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.podeEnviar").value(false));
    }

    @Test
    @WithMockUser(username = "solicitante-f5-it", roles = "SOLICITANTE")
    void solicitanteNaoConsegueEnviarMensagemViaAjaxQuandoOProcessoFoiExcluido() throws Exception {
        mvc.perform(post("/solicitante/" + solicitacaoProcessoExcluidoId + "/mensagem/ajax")
                        .with(csrf())
                        .param("texto", "Isto nao deveria ser aceito."))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").exists());

        assertThat(mensagemRepo.findBySolicitacaoOnlineIdOrderByDataEnvioAsc(solicitacaoProcessoExcluidoId)).isEmpty();
    }

    @Test
    @WithMockUser(username = "solicitante-f5-it", roles = "SOLICITANTE")
    void solicitanteNaoConsegueEnviarMensagemViaEndpointClassicoQuandoOProcessoFoiExcluido() throws Exception {
        mvc.perform(post("/solicitante/" + solicitacaoProcessoExcluidoId + "/mensagem")
                        .with(csrf())
                        .param("texto", "Isto tambem nao deveria ser aceito."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/solicitante/" + solicitacaoProcessoExcluidoId))
                .andExpect(flash().attributeExists("erro"));

        assertThat(mensagemRepo.findBySolicitacaoOnlineIdOrderByDataEnvioAsc(solicitacaoProcessoExcluidoId)).isEmpty();
    }
}

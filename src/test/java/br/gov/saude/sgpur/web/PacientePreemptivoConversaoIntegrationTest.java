package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.EmailSenderService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Achado A11 da auditoria de 2026-08-27
 * ({@code docs/RELATORIO-AUDITORIA-FINAL-PACIENTE-PREEMPTIVO-2026-08-27.md}):
 * o plano original (secao 8.6) exigia explicitamente um teste da propagacao
 * do campo {@code preemptivo} na CONVERSAO {@code SolicitacaoOnline} ->
 * {@code Processo}, e nenhum foi escrito - exatamente a familia de bug
 * "campo esquecido no copy" ja documentada 3x no CLAUDE.md (e-mail em
 * {@code UsuarioService.atualizar}, {@code persist} em vez de {@code merge}
 * em {@code MembroController.salvar}, {@code dataVencimento} em
 * {@code ControleUrgenciaService.atualizar}).
 *
 * <p>Teste de INTEGRACAO ponta a ponta (MockMvc + contexto Spring real + H2,
 * sem {@code @MockitoBean} nos servicos envolvidos) cobrindo o fluxo real do
 * operador: {@code GET /processos/novo?origemSolicitacaoOnlineId=X} (que
 * pre-preenche o formulario a partir da {@code SolicitacaoOnline}) seguido de
 * {@code POST /processos} (que persiste o {@code Processo} e converte a
 * solicitacao de origem) - releem do BANCO ao final e conferem o campo, nao
 * so o retorno HTTP.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-preemptivo-conversao;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.solicitante.habilitado=true",
    "app.anexos.dir=./target/test-anexos-preemptivo-conversao"
})
class PacientePreemptivoConversaoIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SolicitacaoOnlineService solicitacaoOnlineService;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoOnlineRepository;
    @Autowired
    private ProcessoRepository processoRepository;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;

    @MockitoBean
    private EmailSenderService emailSenderService;

    private List<Long> medicoIds;

    @BeforeEach
    void preparar() {
        // Ordem importa: solicitacao_online.processo_gerado_id referencia
        // processo(id) - apagar processo primeiro estoura violacao de FK.
        solicitacaoOnlineRepository.deleteAll();
        processoRepository.deleteAll();
        when(emailSenderService.enviar(any(String[].class), any(), anyString(), anyString())).thenReturn(true);
        when(emailSenderService.enviarComAnexo(anyString(), any(), anyString(), anyString(), any(), anyString()))
            .thenReturn(true);

        if (membroRepository.count() < 3) {
            membroRepository.deleteAll();
            for (int i = 1; i <= 3; i++) {
                membroRepository.save(
                    new MembroUrgenciaRenal("HCPA", "Medico Conversao " + i, "medico-conv" + i + "@example.com"));
            }
        }
        medicoIds = membroRepository.findAll().stream().map(MembroUrgenciaRenal::getId).limit(3).toList();
    }

    private Usuario solicitante(String username) {
        return usuarioRepository.findByUsername(username).orElseGet(() -> {
            Usuario u = new Usuario();
            u.setUsername(username);
            u.setNome("Solicitante " + username);
            u.setEmail(username + "@example.com");
            u.setSenha("{noop}irrelevante");
            u.setPerfil(Perfil.SOLICITANTE);
            u.setAtivo(true);
            u.setEquipeSolicitante("HCPA - Nefrologia");
            return usuarioRepository.save(u);
        });
    }

    private SolicitacaoOnline solicitacao(boolean preemptivo, String rgct, Usuario u) {
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setPacienteNome("Paciente Conversao");
        s.setPacienteRgct(rgct);
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf("11144477735");
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setDataSituacaoEspecial(LocalDate.now());
        s.setJustificativaClinica("Justificativa clinica de teste, com detalhe suficiente.");
        s.setPreemptivo(preemptivo);
        return solicitacaoOnlineService.criar(s, u, null);
    }

    /**
     * Solicitacao PREEMPTIVA (sem RGCT): o GET pre-preenche o form com
     * {@code preemptivo=true} (checkbox marcado) e o POST, submetendo esse
     * mesmo valor (como o operador faria sem mexer no checkbox), tem que
     * gerar um {@code Processo} preemptivo de verdade - numero na serie
     * "P-", RGCT nulo, {@code SolicitacaoOnline} de origem marcada como
     * CONVERTIDA.
     */
    @Test
    @WithMockUser(username = "operador-conv", roles = "OPERADOR")
    void conversaoDeSolicitacaoPreemptivaPropagaPreemptivoParaOProcesso() throws Exception {
        Usuario u = solicitante("solicitante-conv-preemptivo");
        SolicitacaoOnline s = solicitacao(true, null, u);
        assertThat(s.isPreemptivo()).isTrue();

        // GET /processos/novo: confere que o form pre-preenchido ja reflete o
        // tipo da solicitacao de origem (nao so o POST manual abaixo) - olha
        // direto o model attribute "processo", nao a marcacao HTML (fragil a
        // qualquer reordenacao de atributo do th:field).
        mvc.perform(get("/processos/novo").param("origemSolicitacaoOnlineId", String.valueOf(s.getId())))
            .andExpect(status().isOk())
            .andExpect(model().attribute("processo",
                org.hamcrest.Matchers.hasProperty("preemptivo", org.hamcrest.Matchers.is(Boolean.TRUE))));

        mvc.perform(post("/processos")
                .param("origemSolicitacaoOnlineId", String.valueOf(s.getId()))
                .param("preemptivo", "true")
                .param("numero", "P-90/2026")
                .param("pacienteNome", "Paciente Conversao")
                .param("pacienteDataNascimento", "1985-03-15")
                .param("pacienteCpf", "11144477735")
                .param("pacienteSexo", "MASCULINO")
                .param("solicitanteEquipe", "HCPA - Nefrologia")
                .param("solicitanteEmail", "solicitante@example.com")
                .param("dataSituacaoEspecial", LocalDate.now().toString())
                .param("medicoIds", medicoIds.get(0).toString(), medicoIds.get(1).toString(), medicoIds.get(2).toString())
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        // Rele do banco (nunca confia so no redirect HTTP) e confere campo a
        // campo - a mesma disciplina que o CLAUDE.md exige para teste de
        // atualizacao, aplicada aqui a uma CONVERSAO.
        Processo salvo = processoRepository.findAll().stream()
            .filter(p -> "P-90/2026".equals(p.getNumero()))
            .findFirst().orElseThrow();
        assertThat(salvo.isPreemptivo()).isTrue();
        assertThat(salvo.getPacienteRgct()).isNull();

        SolicitacaoOnline depois = solicitacaoOnlineRepository.findById(s.getId()).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusSolicitacaoOnline.CONVERTIDA);
        assertThat(depois.getProcessoGerado().getId()).isEqualTo(salvo.getId());
    }

    /**
     * Contraste: solicitacao de URGENCIA RENAL COMUM (com RGCT) precisa gerar
     * um {@code Processo} com {@code preemptivo=false} e numero na serie
     * normal (sem o prefixo "P-") - garante que o teste acima realmente exerce
     * o campo {@code preemptivo}, e nao um default acidental.
     */
    @Test
    @WithMockUser(username = "operador-conv2", roles = "OPERADOR")
    void conversaoDeSolicitacaoComumPropagaPreemptivoFalsoParaOProcesso() throws Exception {
        Usuario u = solicitante("solicitante-conv-comum");
        SolicitacaoOnline s = solicitacao(false, "RGCT-CONV-1", u);
        assertThat(s.isPreemptivo()).isFalse();

        mvc.perform(post("/processos")
                .param("origemSolicitacaoOnlineId", String.valueOf(s.getId()))
                .param("preemptivo", "false")
                .param("numero", "91/2026")
                .param("pacienteNome", "Paciente Conversao Comum")
                .param("pacienteRgct", "RGCT-CONV-1")
                .param("pacienteDataNascimento", "1985-03-15")
                .param("pacienteCpf", "11144477735")
                .param("pacienteSexo", "MASCULINO")
                .param("solicitanteEquipe", "HCPA - Nefrologia")
                .param("solicitanteEmail", "solicitante@example.com")
                .param("dataSituacaoEspecial", LocalDate.now().toString())
                .param("medicoIds", medicoIds.get(0).toString(), medicoIds.get(1).toString(), medicoIds.get(2).toString())
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        Processo salvo = processoRepository.findAll().stream()
            .filter(p -> "91/2026".equals(p.getNumero()))
            .findFirst().orElseThrow();
        assertThat(salvo.isPreemptivo()).isFalse();
        assertThat(salvo.getPacienteRgct()).isEqualTo("RGCT-CONV-1");
    }
}

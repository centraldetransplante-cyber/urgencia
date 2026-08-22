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
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, servicos reais, sem
 * {@code @MockitoBean} de servico) do {@link SolicitanteController} apos a
 * remocao do {@code @Transactional} de nivel de classe (2026-07-29).
 *
 * <p><b>Por que nao da para cobrir isso com {@code @WebMvcTest}:</b> os testes
 * de {@code SolicitanteControllerTest} usam {@code @MockitoBean
 * SolicitacaoOnlineService}/{@code MensagemSolicitacaoService} - como o mock
 * nao e um bean {@code @Transactional} de verdade, o
 * {@code TransactionInterceptor} nunca entra em cena e
 * {@code UnexpectedRollbackException} e literalmente inexprimivel ali (nao
 * existe transacao fisica nenhuma para "envenenar"). So um contexto real, com
 * {@code SolicitacaoOnlineService}/{@code MensagemSolicitacaoService} de
 * verdade (ambos {@code @Transactional} com propagacao REQUIRED), reproduz o
 * bug.</p>
 *
 * <p>Antes da correcao, {@link SolicitanteController} tinha
 * {@code @Transactional} de nivel de CLASSE. Metodos como {@code criar}/
 * {@code cancelar}/{@code apagarMensagemAjax} chamam um servico
 * {@code @Transactional} dentro de um try/catch: quando o servico lanca
 * {@code IllegalStateException}/{@code IllegalArgumentException} de negocio,
 * o Spring marcava a transacao COMPARTILHADA como rollback-only; o catch
 * tratava o erro normalmente e devolvia o flash amigavel, mas o COMMIT no fim
 * do metodo do controller estourava {@code UnexpectedRollbackException} (500
 * cru, mapeado por {@code GlobalExceptionHandler}) em vez do flash esperado -
 * mesma familia de bug ja corrigida em {@code AvaliadorController} (voto do
 * avaliador perdido) e {@code ProcessoDecisaoController.finalizar}. Os testes
 * abaixo forcam exatamente essa falha de pos-processamento e confirmam que o
 * usuario recebe o flash de erro tratado (200/302), nunca 500.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-solicitante-sem-tx;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.solicitante.habilitado=true",
        "app.anexos.dir=./target/test-anexos-solicitante-sem-tx"
})
class SolicitanteControllerSemTransacaoIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired
    private MensagemSolicitacaoRepository mensagemRepo;

    private Long solicitacaoJaTriadaId;

    @BeforeEach
    @Transactional
    void preparar() {
        mensagemRepo.deleteAll();
        solicitacaoRepo.deleteAll();

        // Usuario SOLICITANTE SEM equipe vinculada - estado que a tela
        // /usuarios normalmente impede de existir (UsuarioController valida
        // equipeSolicitante obrigatoria), mas que pode ocorrer em dados
        // legados/migracao. Usado para forcar a IllegalStateException real
        // de SolicitacaoOnlineService.criar (nunca alcancavel via mock).
        Usuario semEquipe = usuarioRepo.findByUsername("solicitante-sem-equipe-tx-it").orElseGet(() -> {
            Usuario u = new Usuario();
            u.setUsername("solicitante-sem-equipe-tx-it");
            u.setSenha("{noop}x");
            u.setNome("Sem Equipe TX IT");
            u.setEmail("semequipetx-it@example.com");
            u.setPerfil(Perfil.SOLICITANTE);
            return usuarioRepo.save(u);
        });
        assertThat(semEquipe.getEquipeSolicitante()).isNull();

        // Usuario SOLICITANTE normal, com equipe - dono da solicitacao ja
        // triada usada para forcar a IllegalStateException real de
        // SolicitacaoOnlineService.cancelar (cancelar so vale para ENVIADA).
        Usuario dono = usuarioRepo.findByUsername("solicitante-tx-it").orElseGet(() -> {
            Usuario u = new Usuario();
            u.setUsername("solicitante-tx-it");
            u.setSenha("{noop}x");
            u.setNome("Equipe TX IT");
            u.setEmail("solicitantetx-it@example.com");
            u.setPerfil(Perfil.SOLICITANTE);
            u.setEquipeSolicitante("HCPA");
            return usuarioRepo.save(u);
        });

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(dono);
        s.setPacienteNome("Paciente Ja Triado TX");
        s.setPacienteRgct("333333333");
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf("11144477735");
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setSolicitanteEquipe("HCPA");
        s.setSolicitanteEmail("solicitantetx-it@example.com");
        s.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
        s.setJustificativaClinica("Justificativa de teste.");
        s.setStatus(StatusSolicitacaoOnline.DEVOLVIDA); // != ENVIADA -> cancelar() rejeita
        solicitacaoRepo.saveAndFlush(s);
        solicitacaoJaTriadaId = s.getId();

        // Mensagem de OUTRO remetente (operador ficticio, id 999) na mesma
        // solicitacao - usada para forcar a IllegalArgumentException real de
        // MensagemSolicitacaoService.apagar ("voce nao pode apagar esta
        // mensagem") quando o dono tenta apagar uma mensagem que nao e dele.
        MensagemSolicitacao msgDeOutro = new MensagemSolicitacao();
        msgDeOutro.setSolicitacaoOnline(s);
        msgDeOutro.setTexto("Mensagem do operador");
        msgDeOutro.setRemetente(MensagemSolicitacao.RemetenteMensagem.OPERADOR);
        msgDeOutro.setRemetenteId(999L);
        msgDeOutro.setDataEnvio(LocalDateTime.now());
        mensagemRepo.saveAndFlush(msgDeOutro);
    }

    /**
     * POST /solicitante/nova com usuario SEM equipe vinculada: o servico
     * lanca {@code IllegalStateException} DENTRO do try/catch de
     * {@link SolicitanteController#criar}. Sem NOT_SUPPORTED, isso
     * estouraria {@code UnexpectedRollbackException} no commit da (extinta)
     * transacao de classe; com a correcao, o controller volta normalmente ao
     * formulario com o flash de erro - HTTP 200, nunca 500.
     */
    @Test
    @WithMockUser(username = "solicitante-sem-equipe-tx-it", roles = "SOLICITANTE")
    void criarComUsuarioSemEquipeDevolveErroDeNegocioSemQuebrar() throws Exception {
        long totalAntes = solicitacaoRepo.count();

        mvc.perform(post("/solicitante/nova")
                        .with(csrf())
                        .param("pacienteNome", "Paciente Sem Equipe")
                        .param("pacienteRgct", "444444444")
                        .param("dataSituacaoEspecial", LocalDate.now().toString())
                        .param("justificativaClinica", "Justificativa de teste, quadro grave."))
                .andExpect(status().isOk())
                .andExpect(view().name("solicitante/nova"))
                .andExpect(model().attribute("erro", "Usuario solicitante sem equipe vinculada. Contate o administrador."));

        // Nada foi persistido (a excecao e lancada antes do save) e o
        // sistema continua saudavel - proxima requisicao nao herda nenhum
        // estado de transacao corrompido.
        assertThat(solicitacaoRepo.count()).isEqualTo(totalAntes);
        mvc.perform(get("/solicitante/nova")).andExpect(status().isOk());
    }

    /**
     * POST /solicitante/{id}/cancelar numa solicitacao DEVOLVIDA - fora das
     * duas janelas de {@code podeCancelar} (ENVIADA, ou CONVERTIDA com processo
     * ainda nao decidido): {@code SolicitacaoOnlineService.cancelar} lanca
     * {@code IllegalStateException} dentro do try/catch de
     * {@link SolicitanteController#cancelar}. Confirma flash de erro (302),
     * nunca 500, e que o status da solicitacao permanece intacto.
     */
    @Test
    @WithMockUser(username = "solicitante-tx-it", roles = "SOLICITANTE")
    void cancelarSolicitacaoJaTriadaDevolveErroDeNegocioSemQuebrar() throws Exception {
        mvc.perform(post("/solicitante/" + solicitacaoJaTriadaId + "/cancelar").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/solicitante"))
                .andExpect(flash().attribute("erro",
                        "Esta solicitacao nao pode mais ser cancelada."));

        SolicitacaoOnline depois = solicitacaoRepo.findById(solicitacaoJaTriadaId).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusSolicitacaoOnline.DEVOLVIDA);

        // Sistema segue saudavel para a proxima requisicao.
        mvc.perform(get("/solicitante")).andExpect(status().isOk());
    }

    /**
     * POST /solicitante/{id}/mensagem/{mensagemId}/apagar/ajax tentando
     * apagar uma mensagem de OUTRO remetente:
     * {@code MensagemSolicitacaoService.apagar} lanca
     * {@code IllegalArgumentException} dentro do try/catch de
     * {@link SolicitanteController#apagarMensagemAjax}. Confirma 400 JSON
     * tratado, nunca 500, e que a mensagem alheia continua intacta (nao
     * apagada).
     */
    @Test
    @WithMockUser(username = "solicitante-tx-it", roles = "SOLICITANTE")
    void apagarMensagemDeOutroRemetenteViaAjaxDevolveErroDeNegocioSemQuebrar() throws Exception {
        MensagemSolicitacao msgDeOutro = mensagemRepo.findBySolicitacaoOnlineIdOrderByDataEnvioAsc(solicitacaoJaTriadaId)
                .stream().findFirst().orElseThrow();

        mvc.perform(post("/solicitante/" + solicitacaoJaTriadaId + "/mensagem/" + msgDeOutro.getId() + "/apagar/ajax")
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString())
                        .contains("Voce nao pode apagar esta mensagem."));

        MensagemSolicitacao aindaIntacta = mensagemRepo.findById(msgDeOutro.getId()).orElseThrow();
        assertThat(aindaIntacta.isDeletada()).isFalse();
        assertThat(aindaIntacta.getTexto()).isEqualTo("Mensagem do operador");

        mvc.perform(get("/solicitante")).andExpect(status().isOk());
    }
}

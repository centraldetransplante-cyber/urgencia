package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.OrigemParecer;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, servicos/repositorios REAIS,
 * sem nenhum {@code @MockitoBean} de servico) das telas GET do Portal do
 * Avaliador, apos a remocao do {@code @Transactional(readOnly = true)} de
 * nivel de classe de {@link AvaliadorController} (2026-07-29).
 *
 * <p><b>Por que nao da para cobrir isso com {@code @WebMvcTest}:</b> os testes
 * daquela classe usam {@code @MockitoBean ParecerRepository}/
 * {@code UsuarioRepository} — as entidades devolvidas sao objetos Java comuns
 * montados a mao no teste (nunca proxies do Hibernate), entao
 * {@code LazyInitializationException} e literalmente inexprimivel ali. So um
 * contexto real, com entidades carregadas de verdade do H2 e a sessao do
 * Hibernate fechada entre a consulta e a navegacao da associacao (como
 * acontece em producao com {@code spring.jpa.open-in-view=false}), consegue
 * reproduzir o bug.</p>
 *
 * <p>Com {@code spring.jpa.open-in-view=false}, sem transacao no controller e
 * qualquer consulta do repositorio SEM fetch join, {@code Parecer.processo}
 * (LAZY) e {@code Usuario.membro} (LAZY) voltariam como proxies vazios: a
 * primeira navegacao fora de uma transacao (ex.: {@code par.getProcesso()
 * .getStatus()} em {@code AvaliadorController.pendenteAtivoParaVoto}) lancaria
 * {@code LazyInitializationException}, que o {@code GlobalExceptionHandler}
 * mapeia para HTTP 500 (fallback generico) — e e exatamente esse status que
 * os testes abaixo verificam NAO acontecer.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-avaliador-portal-lazy;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-avaliador-portal-lazy"
})
class AvaliadorPortalSemTransacaoIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;
    @Autowired
    private AnexoRepository anexoRepo;
    @Autowired
    private AnexoStorageService anexoStorage;

    private Long processoPendenteId;
    private Long anexoPdfId;

    @BeforeEach
    @Transactional
    void preparar() throws Exception {
        anexoRepo.deleteAll();
        parecerRepo.deleteAll();
        usuarioRepo.findByUsername("avaliador-portal-it").ifPresent(usuarioRepo::delete);
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        MembroUrgenciaRenal membro = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HCPA", "Dra. Portal Teste", "portal.teste@example.com"));
        MembroUrgenciaRenal outroMembro = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("ISCMPA", "Dr. Outro Membro", "outro@example.com"));

        // Processo PENDENTE de voto do membro logado (aba "Pendentes" + tela de voto).
        Processo pendente = new Processo();
        pendente.setNumero("50/2026");
        pendente.setAno(2026);
        pendente.setSequencial(50);
        pendente.setPacienteNome("Fulano de Tal Pendente");
        pendente.setPacienteRgct("111111111");
        pendente.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        pendente.setPacienteCpf("11144477735");
        pendente.setPacienteSexo(Sexo.MASCULINO);
        pendente.setSolicitanteEquipe("HCPA");
        pendente.setSolicitanteEmail("equipe@hcpa.example.com");
        pendente.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        pendente.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(pendente);
        processoPendenteId = pendente.getId();

        Parecer parecerPendente = new Parecer(membro);
        parecerPendente.setProcesso(pendente);
        parecerPendente.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parecerPendente);

        Parecer parecerOutroMembro = new Parecer(outroMembro);
        parecerOutroMembro.setProcesso(pendente);
        parecerOutroMembro.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parecerOutroMembro);

        // Anexo real em disco (SOLICITACAO_AVALIADOR) para exercitar baixarPdf().
        var anexo = anexoStorage.salvarBytes(pendente, TipoAnexo.SOLICITACAO_AVALIADOR,
                "Material anonimizado para avaliacao", "processo-50-2026.pdf",
                "application/pdf", "%PDF-1.4 conteudo de teste".getBytes());
        anexoPdfId = anexo.getId();

        // Processo ja DECIDIDO com parecer JA VOTADO pelo membro logado (aba "Historico").
        Processo decidido = new Processo();
        decidido.setNumero("40/2026");
        decidido.setAno(2026);
        decidido.setSequencial(40);
        decidido.setPacienteNome("Ciclano da Silva Decidido");
        decidido.setPacienteRgct("222222222");
        decidido.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        decidido.setPacienteCpf("11144477735");
        decidido.setPacienteSexo(Sexo.MASCULINO);
        decidido.setSolicitanteEquipe("HCPA");
        decidido.setSolicitanteEmail("equipe@hcpa.example.com");
        decidido.setDataSituacaoEspecial(LocalDate.of(2026, 4, 1));
        decidido.setStatus(StatusProcesso.DEFERIDO);
        processoRepo.saveAndFlush(decidido);

        Parecer parecerVotado = new Parecer(membro);
        parecerVotado.setProcesso(decidido);
        parecerVotado.setDataEnvio(LocalDate.of(2026, 4, 2));
        parecerVotado.setDataResposta(LocalDate.of(2026, 4, 3));
        parecerVotado.setResultado(ResultadoParecer.FAVORAVEL);
        parecerVotado.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        parecerRepo.saveAndFlush(parecerVotado);

        Usuario u = new Usuario();
        u.setUsername("avaliador-portal-it");
        u.setSenha("{noop}x");
        u.setNome("Dra. Portal Teste");
        u.setEmail("portal.teste@example.com");
        u.setPerfil(Perfil.AVALIADOR);
        u.setMembro(membro);
        usuarioRepo.saveAndFlush(u);
    }

    /**
     * GET /avaliador navega Parecer.processo (pendentes E historico) e
     * Usuario.membro (resolverMembro) fora de qualquer transacao do
     * controller. Sem os fetch joins/recarregamento por id, isto lancaria
     * LazyInitializationException -> HTTP 500 (GlobalExceptionHandler).
     */
    @Test
    @WithMockUser(username = "avaliador-portal-it", roles = "AVALIADOR")
    void listaRendorizaPendentesEHistoricoSemLazyInitializationException() throws Exception {
        mvc.perform(get("/avaliador"))
                .andExpect(status().isOk())
                .andExpect(view().name("avaliador/lista"))
                // Numero do processo pendente e do historico devem aparecer...
                .andExpect(content().string(containsString("50/2026")))
                .andExpect(content().string(containsString("40/2026")))
                // ...mas NUNCA o nome completo do paciente (so iniciais).
                .andExpect(content().string(not(containsString("Fulano de Tal Pendente"))))
                .andExpect(content().string(not(containsString("Ciclano da Silva Decidido"))));
    }

    /**
     * GET /avaliador/{processoId} navega parecer.getProcesso() (resolvido via
     * resolverParecerPendente) fora de transacao do controller.
     */
    @Test
    @WithMockUser(username = "avaliador-portal-it", roles = "AVALIADOR")
    void votarExibeFormularioSemLazyInitializationException() throws Exception {
        mvc.perform(get("/avaliador/" + processoPendenteId))
                .andExpect(status().isOk())
                .andExpect(view().name("avaliador/votar"))
                .andExpect(model().attribute("numero", "50/2026"))
                .andExpect(model().attribute("iniciais", "F.T.P."));
    }

    /**
     * GET /avaliador/{id}/pdf/{anexoId} navega membro.getId() (resolverMembro)
     * fora de transacao do controller, alem de ler o arquivo do disco gravado
     * de verdade no @BeforeEach.
     */
    @Test
    @WithMockUser(username = "avaliador-portal-it", roles = "AVALIADOR")
    void baixarPdfFuncionaSemLazyInitializationException() throws Exception {
        mvc.perform(get("/avaliador/" + processoPendenteId + "/pdf/" + anexoPdfId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"));
    }

    /**
     * POST /avaliador/{id}/votar - fluxo completo de voto (TX1 do
     * TransactionTemplate ja resolve o parecer com fetch join). Confirma que o
     * registro sobrevive e que a proxima renderizacao de /avaliador (historico
     * atualizado) tambem continua sem LazyInitializationException.
     */
    @Test
    @WithMockUser(username = "avaliador-portal-it", roles = "AVALIADOR")
    void registrarVotoEDepoisListaContinuamSemLazyInitializationException() throws Exception {
        mvc.perform(post("/avaliador/" + processoPendenteId + "/votar")
                        .with(csrf())
                        .param("resultado", "FAVORAVEL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/avaliador"));

        Parecer gravado = parecerRepo
                .findByProcessoIdAndMembroIdComProcesso(processoPendenteId,
                        membroRepo.findAll().stream()
                                .filter(m -> "Dra. Portal Teste".equals(m.getNome()))
                                .findFirst().orElseThrow().getId())
                .orElseThrow();
        assertThat(gravado.getResultado()).isEqualTo(ResultadoParecer.FAVORAVEL);

        mvc.perform(get("/avaliador"))
                .andExpect(status().isOk());
    }
}

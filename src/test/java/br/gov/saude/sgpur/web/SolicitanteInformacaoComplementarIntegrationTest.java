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
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.ProcessoService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO ponta-a-ponta (contexto Spring real, sem mock de
 * service) do novo canal de "informacao complementar" pelo Portal do
 * Solicitante: um avaliador pede mais informacoes (parecer
 * SOLICITA_INFORMACAO), o processo pausa, o SOLICITANTE envia um arquivo
 * direto pelo portal (sem decidir nada), o OPERADOR ve o anexo na tela de
 * detalhe do processo e retoma a analise normalmente.
 *
 * Mesmo molde de {@code SolicitacaoOnlineDetalheIntegrationTest} (contexto
 * completo, H2 real) - necessario porque {@code spring.jpa.open-in-view:
 * false} faz o Thymeleaf renderizar fora da transacao do controller, e so um
 * teste com JPA de verdade pegaria uma LazyInitializationException em
 * {@code p.getAnexos()}/{@code s.getProcessoGerado()}/{@code p.getPareceres()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-info-complementar-portal;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.solicitante.habilitado=true",
        "app.anexos.dir=./target/test-anexos-info-complementar-portal"
})
class SolicitanteInformacaoComplementarIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private SolicitacaoOnlineRepository solicitacaoRepo;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;
    @Autowired
    private AnexoRepository anexoRepo;
    @Autowired
    private ProcessoService processoService;

    private Long solicitacaoId;
    private Long processoId;

    @BeforeEach
    @Transactional
    void preparar() {
        parecerRepo.deleteAll();
        solicitacaoRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        Usuario dono = usuarioRepo.findByUsername("solicitante-info-it").orElseGet(() -> {
            Usuario u = new Usuario();
            u.setUsername("solicitante-info-it");
            u.setSenha("{noop}x");
            u.setNome("Equipe Solicitante Info IT");
            u.setEmail("solicitante-info-it@example.com");
            u.setPerfil(Perfil.SOLICITANTE);
            u.setEquipeSolicitante("HCPA");
            return usuarioRepo.save(u);
        });

        Processo p = new Processo();
        p.setNumero("09/2026");
        p.setAno(2026);
        p.setSequencial(9);
        p.setPacienteNome("Joao das Neves");
        p.setPacienteRgct("999999999");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 3, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        String[][] medicos = { { "HCPA", "Ana Nefro" }, { "ISCMPA", "Bruno Nefro" }, { "CET", "Carla Nefro" } };
        Parecer primeiroParecer = null;
        for (String[] medico : medicos) {
            MembroUrgenciaRenal m = membroRepo.saveAndFlush(
                    new MembroUrgenciaRenal(medico[0], medico[1],
                            medico[1].replace(" ", ".").toLowerCase() + "@example.com"));
            Parecer par = new Parecer(m);
            par.setProcesso(p);
            par.setDataEnvio(LocalDate.of(2026, 3, 2));
            parecerRepo.saveAndFlush(par);
            if (primeiroParecer == null) {
                primeiroParecer = par;
            }
        }

        // Um dos 3 avaliadores pede informacao complementar - dispara a pausa
        // via ProcessoService.atualizarStatusPorPareceres (mesmo metodo que o
        // fluxo real de "salvar pareceres" chama).
        primeiroParecer.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        parecerRepo.saveAndFlush(primeiroParecer);
        processoService.atualizarStatusPorPareceres(processoId);

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setUsuarioSolicitante(dono);
        s.setPacienteNome("Joao das Neves");
        s.setPacienteRgct("999999999");
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf("11144477735");
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setSolicitanteEquipe("HCPA");
        s.setSolicitanteEmail("solicitante-info-it@example.com");
        s.setDataSituacaoEspecial(LocalDate.of(2026, 3, 1));
        s.setJustificativaClinica("Justificativa clinica de teste.");
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(processoRepo.getReferenceById(processoId));
        solicitacaoRepo.saveAndFlush(s);
        solicitacaoId = s.getId();
    }

    @Test
    @WithMockUser(username = "solicitante-info-it", roles = "SOLICITANTE")
    void solicitanteEnviaInformacaoComplementarEOperadorRetomaAAnalise() throws Exception {
        // Processo comeca pausado (SOLICITA_INFORMACAO)
        Processo antes = processoRepo.findById(processoId).orElseThrow();
        assertThat(antes.getStatus()).isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);

        // Solicitante envia o arquivo direto pelo portal - so alimenta o dado,
        // NAO decide nem retoma a analise.
        MockMultipartFile arquivo = new MockMultipartFile("arquivos", "exame-complementar.pdf",
                MediaType.APPLICATION_PDF_VALUE, "conteudo do exame".getBytes());
        mvc.perform(multipart("/solicitante/" + solicitacaoId + "/informacao-complementar")
                .file(arquivo).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Processo depoisDoEnvio = processoRepo.findById(processoId).orElseThrow();
        assertThat(depoisDoEnvio.getStatus()).isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);
        assertThat(anexoRepo.findByProcessoIdOrderByDataUploadAsc(processoId))
                .anyMatch(a -> a.getTipo() == TipoAnexo.INFO_COMPLEMENTAR
                        && a.getDescricao() != null
                        && a.getDescricao().contains("Portal do Solicitante"));

        // Operador ve o anexo na tela de detalhe do processo (anexosInfoComplementar)
        mvc.perform(get("/processos/" + processoId).with(user("operador-it")))
                .andExpect(status().isOk());

        // Operador retoma a analise - processo volta para ENVIADO
        mvc.perform(post("/processos/" + processoId + "/retomar-analise")
                .with(csrf()).with(user("operador-it")))
                .andExpect(status().is3xxRedirection());

        Processo depoisDaRetomada = processoRepo.findById(processoId).orElseThrow();
        assertThat(depoisDaRetomada.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(String username) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user(username).roles("OPERADOR");
    }
}

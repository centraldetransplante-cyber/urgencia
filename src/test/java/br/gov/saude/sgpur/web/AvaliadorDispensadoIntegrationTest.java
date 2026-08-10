package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F6 do relatorio de vistoria de brechas (2026-08-10) - Achado 10: teste de
 * INTEGRACAO real (contexto Spring completo, H2 real, sem mock de servico)
 * do avaliador cujo parecer foi DISPENSADO - o processo foi decidido por
 * maioria simples ANTES de ele conseguir votar.
 *
 * <p><b>Revisao extra de imparcialidade</b> (a tela mais sensivel a
 * vazamento de identidade/resultado do sistema): confirma por HTTP real que
 * a resposta renderizada contem o numero do processo e as iniciais, mas
 * NUNCA o nome completo do paciente, o resultado da decisao (Deferido) nem
 * o nome dos outros 2 avaliadores que decidiram o processo.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-avaliador-dispensado;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-avaliador-dispensado"
})
class AvaliadorDispensadoIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UsuarioRepository usuarioRepo;
    @Autowired private ProcessoRepository processoRepo;
    @Autowired private ParecerRepository parecerRepo;
    @Autowired private MembroUrgenciaRenalRepository membroRepo;

    @MockitoBean private SolicitacaoOnlineRepository solicitacaoOnlineRepo;

    @BeforeEach
    @Transactional
    void preparar() {
        parecerRepo.deleteAll();
        usuarioRepo.findByUsername("dispensado-it").ifPresent(usuarioRepo::delete);
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        when(solicitacaoOnlineRepo.findByProcessoGeradoId(anyLong())).thenReturn(Optional.empty());

        Processo p = new Processo();
        p.setNumero("51/2026");
        p.setAno(2026);
        p.setSequencial(51);
        p.setPacienteNome("Fernanda Costa Almeida");
        p.setPacienteRgct("123123123");
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.DEFERIDO);
        processoRepo.saveAndFlush(p);

        MembroUrgenciaRenal medicoA = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HCPA", "Dra. Primeira Votante", "a-disp@example.com"));
        MembroUrgenciaRenal medicoB = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("ISCMPA", "Dr. Segundo Votante", "b-disp@example.com"));
        MembroUrgenciaRenal medicoC = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HSL", "Dr. Nunca Votou", "c-disp@example.com"));

        Parecer parA = new Parecer(medicoA);
        parA.setProcesso(p);
        parA.setDataEnvio(LocalDate.of(2026, 5, 2));
        parA.setResultado(ResultadoParecer.FAVORAVEL);
        parA.setDataResposta(LocalDate.of(2026, 5, 3));
        parecerRepo.saveAndFlush(parA);

        Parecer parB = new Parecer(medicoB);
        parB.setProcesso(p);
        parB.setDataEnvio(LocalDate.of(2026, 5, 2));
        parB.setResultado(ResultadoParecer.FAVORAVEL);
        parB.setDataResposta(LocalDate.of(2026, 5, 3));
        parecerRepo.saveAndFlush(parB);

        // O 3o medico nunca votou (dispensado pela maioria dos outros dois) -
        // resultado permanece null, mas o processo ja esta Deferido.
        Parecer parC = new Parecer(medicoC);
        parC.setProcesso(p);
        parC.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parC);

        Usuario u = new Usuario();
        u.setUsername("dispensado-it");
        u.setSenha("{noop}x");
        u.setNome("Dr. Nunca Votou");
        u.setEmail("c-disp@example.com");
        u.setPerfil(Perfil.AVALIADOR);
        u.setMembro(medicoC);
        usuarioRepo.saveAndFlush(u);
    }

    @Test
    @WithMockUser(username = "dispensado-it", roles = "AVALIADOR")
    void portalMostraProcessoDispensadoComIniciaisMasSemResultadoNemNomesDeTerceiros() throws Exception {
        String html = mvc.perform(get("/avaliador"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // O processo aparece (numero + iniciais F.C.A.), avisando que foi
        // decidido sem o voto deste avaliador.
        assertThat(html).contains("51/2026");
        assertThat(html).contains("F.C.A.");
        assertThat(html).contains("Processos decididos sem o seu voto");

        // Protecao de imparcialidade - NUNCA:
        assertThat(html)
            .as("nome completo do paciente nunca aparece no Portal do Avaliador")
            .doesNotContain("Fernanda Costa Almeida");
        assertThat(html)
            .as("resultado da decisao nao e revelado a quem nao votou")
            .doesNotContain("Deferido")
            .doesNotContain("DEFERIDO");
        assertThat(html)
            .as("identidade dos outros avaliadores que decidiram nunca aparece")
            .doesNotContain("Primeira Votante")
            .doesNotContain("Segundo Votante");
    }
}

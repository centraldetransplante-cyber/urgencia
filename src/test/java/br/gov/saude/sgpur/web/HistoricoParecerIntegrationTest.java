package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.HistoricoParecer;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.repository.HistoricoParecerRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F4 do relatorio de vistoria de brechas (2026-08-10) - Achado 7: teste de
 * INTEGRACAO real (contexto Spring completo, sem mock de servico/repositorio
 * de dominio - escrita irreversivel) que percorre PAUSA -> RETOMADA ->
 * DECISAO AUTOMATICA e confirma que o rastro do parecer sobreposto
 * (incluindo a justificativa clinica original) sobreviveu ao reset de
 * {@code ProcessoService.retomarAposInformacao}, relendo
 * {@link HistoricoParecer} do banco.
 *
 * <p>Cenario: 2 medicos ja votaram Favoravel (maioria ja formada); o 3o pede
 * informacao complementar com uma justificativa clinica especifica. O
 * operador retoma a analise - como a maioria dos outros 2 ja bastava, a
 * decisao automatica acontece NA HORA (decisao de produto confirmada,
 * ver CLAUDE.md "achado B"), sem esperar o 3o avaliador votar de novo.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-historico-parecer;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-historico-parecer"
})
class HistoricoParecerIntegrationTest {

    private static final String JUSTIFICATIVA_ORIGINAL =
        "Faltou o exame de imagem recente para concluir a avaliação.";

    @Autowired private MockMvc mvc;
    @Autowired private ProcessoRepository processoRepo;
    @Autowired private ParecerRepository parecerRepo;
    @Autowired private MembroUrgenciaRenalRepository membroRepo;
    @Autowired private HistoricoParecerRepository historicoRepo;

    @MockitoBean private SolicitacaoOnlineRepository solicitacaoOnlineRepo;

    private Long processoId;
    private MembroUrgenciaRenal medicoQuePediuInfo;

    @BeforeEach
    @Transactional
    void preparar() {
        historicoRepo.deleteAll();
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        when(solicitacaoOnlineRepo.findByProcessoGeradoId(anyLong())).thenReturn(Optional.empty());

        Processo p = new Processo();
        p.setNumero("41/2026");
        p.setAno(2026);
        p.setSequencial(41);
        p.setPacienteNome("Paciente Historico Teste");
        p.setPacienteRgct("777888999");
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        MembroUrgenciaRenal medicoA = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HCPA", "Medico A", "medicoa-hist@example.com"));
        MembroUrgenciaRenal medicoB = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("ISCMPA", "Medico B", "medicob-hist@example.com"));
        medicoQuePediuInfo = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HSL", "Medico C", "medicoc-hist@example.com"));

        Parecer parA = new Parecer(medicoA);
        parA.setProcesso(p);
        parA.setDataEnvio(LocalDate.of(2026, 6, 2));
        parA.setResultado(ResultadoParecer.FAVORAVEL);
        parA.setDataResposta(LocalDate.of(2026, 6, 3));
        parecerRepo.saveAndFlush(parA);

        Parecer parB = new Parecer(medicoB);
        parB.setProcesso(p);
        parB.setDataEnvio(LocalDate.of(2026, 6, 2));
        parB.setResultado(ResultadoParecer.FAVORAVEL);
        parB.setDataResposta(LocalDate.of(2026, 6, 3));
        parecerRepo.saveAndFlush(parB);

        Parecer parC = new Parecer(medicoQuePediuInfo);
        parC.setProcesso(p);
        parC.setDataEnvio(LocalDate.of(2026, 6, 2));
        parC.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        parC.setDataResposta(LocalDate.of(2026, 6, 4));
        parC.setDataHoraVoto(java.time.LocalDateTime.of(2026, 6, 4, 10, 30));
        parC.setJustificativa(JUSTIFICATIVA_ORIGINAL);
        parC.setVotadoPor("medicoc-hist");
        parecerRepo.saveAndFlush(parC);

        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        processoRepo.saveAndFlush(p);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void retomarAposPausaArquivaAJustificativaOriginalMesmoDepoisDoResetDoParecer() throws Exception {
        // Antes da retomada: nenhum historico ainda.
        assertThat(historicoRepo.findByProcessoIdOrderByArquivadoEmDesc(processoId)).isEmpty();

        mvc.perform(post("/processos/" + processoId + "/retomar-analise").with(csrf()))
            .andReturn();

        // A maioria (2 favoraveis) ja bastava: decisao automatica na hora,
        // sem esperar o 3o avaliador votar de novo (achado B, CLAUDE.md).
        Processo depois = processoRepo.findById(processoId).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);

        // O parecer VIVO foi resetado por completo (pendencia limpa) - o
        // reset em si NAO mudou com esta fase.
        Parecer parecerAtual = parecerRepo.findAll().stream()
            .filter(par -> par.getMembro().getId().equals(medicoQuePediuInfo.getId()))
            .findFirst().orElseThrow();
        assertThat(parecerAtual.getResultado()).isNull();
        assertThat(parecerAtual.getJustificativa()).isNull();
        assertThat(parecerAtual.getDataHoraVoto()).isNull();

        // O RASTRO sobreviveu no historico - inclusive o texto completo da
        // justificativa clinica, que o reset acima destruiu no parecer vivo.
        List<HistoricoParecer> historico = historicoRepo.findByProcessoIdOrderByArquivadoEmDesc(processoId);
        assertThat(historico).hasSize(1);
        HistoricoParecer h = historico.get(0);
        assertThat(h.getProcesso().getId()).isEqualTo(processoId);
        assertThat(h.getMembro().getId()).isEqualTo(medicoQuePediuInfo.getId());
        assertThat(h.getResultado()).isEqualTo(ResultadoParecer.SOLICITA_INFORMACAO);
        assertThat(h.getJustificativa()).isEqualTo(JUSTIFICATIVA_ORIGINAL);
        assertThat(h.getVotadoPor()).isEqualTo("medicoc-hist");
        assertThat(h.getDataHoraVoto()).isEqualTo(java.time.LocalDateTime.of(2026, 6, 4, 10, 30));
        assertThat(h.getArquivadoEm()).isNotNull();
        assertThat(h.getMotivoArquivamento()).contains("Retomada da análise");

        // O ProcessoService.historicoParecer(id) devolve o mesmo dado, e e
        // ele que alimenta o card Respostas e o Relatorio Final.
        assertThat(historicoRepo.findByProcessoIdOrderByArquivadoEmDesc(processoId))
            .extracting(HistoricoParecer::getJustificativa)
            .containsExactly(JUSTIFICATIVA_ORIGINAL);
    }
}

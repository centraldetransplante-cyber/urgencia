package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2) que compara o resultado da
 * nova query de contagem direta
 * ({@code ParecerRepository.
 * countByMembroIdAndResultadoIsNullAndDataEnvioIsNotNullAndProcessoStatusIn})
 * com o resultado que a LOGICA ANTIGA produzia — carregar todos os pareceres
 * do membro (via {@code findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull},
 * ainda existente e usado por outros caminhos) e filtrar em Java pelo status
 * do processo (o mesmo criterio de
 * {@code AvaliadorController.pendenteAtivoParaVoto}: resultado nulo,
 * {@code dataEnvio} preenchida, {@code processo.status.aceitaVotoAvaliador()}
 * — ENVIADO ou SOLICITA_INFORMACAO desde a correcao de 2026-08 do bug em que
 * a pausa causada por UM avaliador escondia o processo dos outros dois, ver
 * docs/RELATORIO-BUG-PAUSA-BLOQUEIA-OUTROS-AVALIADORES-2026-08.md).
 *
 * <p><b>Por que integracao, nao {@code @DataJpaTest} isolado sem contexto
 * completo:</b> segue o padrao ja estabelecido no projeto
 * ({@code @SpringBootTest} + H2 real, ver {@code AvaliadorVotoTransacaoIntegrationTest})
 * para qualquer verificacao que precise refletir o comportamento real do
 * Hibernate contra um banco de verdade — a mesma classe de motivo pela qual
 * {@code GlobalModelAdviceTest} (que so mocka o repositorio) nao seria capaz
 * de flagrar uma divergencia real entre a query nova e a antiga.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-parecer-pendentes-count;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-parecer-pendentes-count"
})
class ParecerRepositoryPendentesCountIntegrationTest {

    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;

    private Long membroAlvoId;
    private Long outroMembroId;

    @BeforeEach
    @Transactional
    void limpar() {
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        MembroUrgenciaRenal alvo = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HCPA", "Dr. Alvo", "alvo@example.com"));
        MembroUrgenciaRenal outro = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("ISCMPA", "Dr. Outro", "outro@example.com"));
        membroAlvoId = alvo.getId();
        outroMembroId = outro.getId();
    }

    private Processo novoProcesso(String numero, int sequencial, StatusProcesso status) {
        Processo p = new Processo();
        p.setNumero(numero);
        p.setAno(2026);
        p.setSequencial(sequencial);
        p.setPacienteNome("Paciente " + numero);
        p.setPacienteRgct("11122233" + sequencial);
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(status);
        return processoRepo.saveAndFlush(p);
    }

    private static final List<StatusProcesso> STATUS_ACEITA_VOTO =
            List.of(StatusProcesso.ENVIADO, StatusProcesso.SOLICITA_INFORMACAO);

    /** Reproduz a LOGICA ANTIGA (carregar entidades + filtrar em Java). */
    private long contarPeloCriterioAntigo(Long membroId) {
        return parecerRepo.findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull(membroId)
                .stream()
                .filter(par -> par.getProcesso().getStatus().aceitaVotoAvaliador())
                .count();
    }

    private void assertNovaQueryIgualALogicaAntiga(Long membroId, long esperado) {
        long novo = parecerRepo.countByMembroIdAndResultadoIsNullAndDataEnvioIsNotNullAndProcessoStatusIn(
                membroId, STATUS_ACEITA_VOTO);
        assertThat(novo).isEqualTo(esperado);
        assertThat(novo).isEqualTo(contarPeloCriterioAntigo(membroId));
    }

    @Test
    @Transactional
    void zeroPendentesQuandoMembroNaoTemNenhumParecer() {
        assertNovaQueryIgualALogicaAntiga(membroAlvoId, 0);
    }

    @Test
    @Transactional
    void contaNPendentesDoMembroAlvo() {
        MembroUrgenciaRenal alvo = membroRepo.getReferenceById(membroAlvoId);

        Processo p1 = novoProcesso("10/2026", 10, StatusProcesso.ENVIADO);
        Processo p2 = novoProcesso("11/2026", 11, StatusProcesso.ENVIADO);
        Processo p3 = novoProcesso("12/2026", 12, StatusProcesso.ENVIADO);

        for (Processo p : List.of(p1, p2, p3)) {
            Parecer par = new Parecer(alvo);
            par.setProcesso(p);
            par.setDataEnvio(LocalDate.of(2026, 5, 2));
            parecerRepo.saveAndFlush(par);
        }

        assertNovaQueryIgualALogicaAntiga(membroAlvoId, 3);
    }

    @Test
    @Transactional
    void pendenteDeOutroAvaliadorNaoConta() {
        MembroUrgenciaRenal alvo = membroRepo.getReferenceById(membroAlvoId);
        MembroUrgenciaRenal outro = membroRepo.getReferenceById(outroMembroId);
        Processo p = novoProcesso("20/2026", 20, StatusProcesso.ENVIADO);

        Parecer parAlvo = new Parecer(alvo);
        parAlvo.setProcesso(p);
        parAlvo.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parAlvo);

        Parecer parOutro = new Parecer(outro);
        parOutro.setProcesso(p);
        parOutro.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parOutro);

        assertNovaQueryIgualALogicaAntiga(membroAlvoId, 1);
        assertNovaQueryIgualALogicaAntiga(outroMembroId, 1);
    }

    @Test
    @Transactional
    void parecerJaRespondidoNaoConta() {
        MembroUrgenciaRenal alvo = membroRepo.getReferenceById(membroAlvoId);
        Processo p = novoProcesso("30/2026", 30, StatusProcesso.ENVIADO);

        Parecer respondido = new Parecer(alvo);
        respondido.setProcesso(p);
        respondido.setDataEnvio(LocalDate.of(2026, 5, 2));
        respondido.setResultado(ResultadoParecer.FAVORAVEL);
        respondido.setDataResposta(LocalDate.of(2026, 5, 3));
        parecerRepo.saveAndFlush(respondido);

        assertNovaQueryIgualALogicaAntiga(membroAlvoId, 0);
    }

    @Test
    @Transactional
    void processoDecididoNaoContaMasProcessoPausadoContinuaContando() {
        MembroUrgenciaRenal alvo = membroRepo.getReferenceById(membroAlvoId);

        // Processo ja decidido: o parecer pendente (sem resultado) nao deve
        // contar mais, mesmo com dataEnvio preenchida - mesmo criterio de
        // AvaliadorController.pendenteAtivoParaVoto (DEFERIDO/INDEFERIDO/
        // CANCELADO nao aceitam voto).
        Processo pDeferido = novoProcesso("40/2026", 40, StatusProcesso.DEFERIDO);
        Parecer parDeferido = new Parecer(alvo);
        parDeferido.setProcesso(pDeferido);
        parDeferido.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parDeferido);

        // Processo PAUSADO por outro avaliador (SOLICITA_INFORMACAO): o
        // parecer pendente deste membro (que nao pediu informacao) CONTINUA
        // contando - bug corrigido em 2026-08, ver
        // docs/RELATORIO-BUG-PAUSA-BLOQUEIA-OUTROS-AVALIADORES-2026-08.md.
        Processo pPausado = novoProcesso("41/2026", 41, StatusProcesso.SOLICITA_INFORMACAO);
        Parecer parPausado = new Parecer(alvo);
        parPausado.setProcesso(pPausado);
        parPausado.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parPausado);

        assertNovaQueryIgualALogicaAntiga(membroAlvoId, 1);
    }

    @Test
    @Transactional
    void misturaDeCenariosContaSomenteOsRealmentePendentesDoMembroAlvo() {
        MembroUrgenciaRenal alvo = membroRepo.getReferenceById(membroAlvoId);
        MembroUrgenciaRenal outro = membroRepo.getReferenceById(outroMembroId);

        // 2 pendentes de verdade do alvo.
        Processo p1 = novoProcesso("50/2026", 50, StatusProcesso.ENVIADO);
        Parecer par1 = new Parecer(alvo);
        par1.setProcesso(p1);
        par1.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(par1);

        Processo p2 = novoProcesso("51/2026", 51, StatusProcesso.ENVIADO);
        Parecer par2 = new Parecer(alvo);
        par2.setProcesso(p2);
        par2.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(par2);

        // Ja respondido pelo alvo - nao conta.
        Processo p3 = novoProcesso("52/2026", 52, StatusProcesso.ENVIADO);
        Parecer par3 = new Parecer(alvo);
        par3.setProcesso(p3);
        par3.setDataEnvio(LocalDate.of(2026, 5, 2));
        par3.setResultado(ResultadoParecer.NAO_FAVORAVEL);
        par3.setDataResposta(LocalDate.of(2026, 5, 3));
        parecerRepo.saveAndFlush(par3);

        // Processo ja decidido - nao conta.
        Processo p4 = novoProcesso("53/2026", 53, StatusProcesso.INDEFERIDO);
        Parecer par4 = new Parecer(alvo);
        par4.setProcesso(p4);
        par4.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(par4);

        // Pendente de OUTRO membro no mesmo processo - nao conta para o alvo.
        Parecer parOutro = new Parecer(outro);
        parOutro.setProcesso(p1);
        parOutro.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(parOutro);

        assertNovaQueryIgualALogicaAntiga(membroAlvoId, 2);
        assertNovaQueryIgualALogicaAntiga(outroMembroId, 1);
    }
}

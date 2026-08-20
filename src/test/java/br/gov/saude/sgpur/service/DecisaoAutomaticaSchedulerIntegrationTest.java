package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.LogAuditoria;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.OrigemParecer;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.LogAuditoriaRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, {@code ProcessoService} REAL
 * com o proxy transacional de verdade) do varredor periodico de decisao
 * automatica.
 *
 * <p><b>Por que precisa ser {@code @SpringBootTest} e nao um teste de unidade
 * com mocks:</b> metade do que este componente promete e comportamento
 * transacional — "a falha de um processo nao pode desfazer a decisao dos
 * outros". Com {@code @MockitoBean ProcessoService} nao existe
 * {@code TransactionInterceptor} nenhum, entao a transacao nunca e marcada
 * como rollback-only e o teste passaria mesmo com o codigo bugado (a mesma
 * classe de bug que perdeu o voto do avaliador — ver
 * {@code AvaliadorVotoTransacaoIntegrationTest}).</p>
 *
 * <p>O agendamento em si nao e exercitado (o {@code @Scheduled} dispararia em
 * momento imprevisivel no meio das assercoes): o atraso inicial e empurrado
 * para bem alem da duracao do teste e {@code varrer()} e chamado
 * explicitamente, de forma deterministica.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-varredura-decisao;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-varredura-decisao",
        // Liga o componente (default e desligado em dev/teste)...
        "app.decisao-automatica.varredura.habilitado=true",
        // ...mas o agendador nunca chega a disparar sozinho durante o teste.
        "app.decisao-automatica.varredura.intervalo-ms=3600000",
        "app.decisao-automatica.varredura.atraso-inicial-ms=3600000"
})
class DecisaoAutomaticaSchedulerIntegrationTest {

    @Autowired
    private DecisaoAutomaticaScheduler scheduler;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;
    @Autowired
    private LogAuditoriaRepository logRepo;

    /**
     * Unico ponto mockado: consultado dentro de {@code ProcessoService.decidir}
     * logo apos gravar a decisao (espelha o status na SolicitacaoOnline de
     * origem). Serve de gatilho deterministico para a falha de UM processo
     * especifico no teste de isolamento.
     */
    @MockitoBean
    private SolicitacaoOnlineRepository solicitacaoOnlineRepo;

    private MembroUrgenciaRenal coordenador;
    private MembroUrgenciaRenal medicoA;
    private MembroUrgenciaRenal medicoB;
    private MembroUrgenciaRenal medicoC;

    /**
     * Sem {@code @Transactional} de proposito: o varredor roda em transacoes
     * proprias e so enxerga o que ja foi COMMITADO. Um setup transacional
     * (rollback ao fim do teste) esconderia os dados dele.
     */
    @BeforeEach
    void preparar() {
        when(solicitacaoOnlineRepo.findByProcessoGeradoId(anyLong())).thenReturn(Optional.empty());
        logRepo.deleteAll();
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        coordenador = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("CET-RS", "Coordenador CET-RS", "coord@example.com"));
        coordenador.setCoordenador(true);
        membroRepo.saveAndFlush(coordenador);
        medicoA = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HCPA", "Medico A", "a@example.com"));
        medicoB = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("ISCMPA", "Medico B", "b@example.com"));
        medicoC = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HSL", "Medico C", "c@example.com"));
    }

    // -------------------------------------------------------------------------
    // Cenario REAL de producao que motivou o varredor
    // -------------------------------------------------------------------------

    /**
     * CENARIO REAL (processo 03/2026, id=6 em producao): status ENVIADO, 3
     * pareceres — 1 FAVORAVEL e 2 NAO_FAVORAVEL, todos {@code AVALIADOR_SISTEMA}
     * (dispensam o anexo de resposta). A maioria para Indeferido estava
     * plenamente formada, mas os votos entraram ANTES de a automacao de
     * indeferimento existir em producao: nenhum evento futuro (voto novo,
     * retomada de analise) iria dispara-la, e o processo ficaria parado para
     * sempre. O varredor precisa fecha-lo sozinho.
     *
     * <p>Os 3 avaliadores sao membros comuns (nenhum e o coordenador CET-RS) —
     * como no caso real. Com o coordenador entre eles o resultado seria o
     * oposto, o que e justamente o teste seguinte.</p>
     */
    @Test
    void decideProcessoComMaioriaQueNuncaReceberiaVotoNovo() {
        Processo p = processo("03/2026", 3, StatusProcesso.ENVIADO);
        votar(p, medicoA, ResultadoParecer.FAVORAVEL);
        votar(p, medicoB, ResultadoParecer.NAO_FAVORAVEL);
        votar(p, medicoC, ResultadoParecer.NAO_FAVORAVEL);

        assertThat(scheduler.varrer()).isEqualTo(1);

        Processo depois = processoRepo.findById(p.getId()).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusProcesso.INDEFERIDO);
        assertThat(depois.getDataDecisao()).isNotNull();
        // Motivo institucional e impessoal (nunca o nome de quem votou contra
        // nem a justificativa clinica crua — o motivo vai para o oficio e para
        // o e-mail da equipe solicitante).
        assertThat(depois.getMotivoIndeferimento())
                .contains("maioria")
                .doesNotContain("Medico B")
                .doesNotContain("Medico C");
    }

    /**
     * O membro que votou FAVORAVEL no cenario acima e um avaliador comum, nao o
     * coordenador — se fosse o coordenador, a regra CET-RS deferiria sozinho.
     * Este teste fixa esse contraste sobre exatamente a mesma contagem de votos
     * (1 favoravel x 2 desfavoraveis), garantindo que o varredor respeita a
     * excecao do coordenador em vez de so contar cabecas.
     */
    @Test
    void coordenadorFavoravelDefereMesmoComDoisDesfavoraveis() {
        Processo p = processo("04/2026", 4, StatusProcesso.ENVIADO);
        votar(p, coordenador, ResultadoParecer.FAVORAVEL);
        votar(p, medicoA, ResultadoParecer.NAO_FAVORAVEL);
        votar(p, medicoB, ResultadoParecer.NAO_FAVORAVEL);
        // Garante que o teste testa o que diz: a flag esta gravada no banco, e
        // e ela (nao a contagem de votos) que muda o resultado em relacao ao
        // cenario anterior.
        assertThat(membroRepo.findById(coordenador.getId()).orElseThrow().isCoordenador()).isTrue();

        scheduler.varrer();

        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.DEFERIDO);
    }

    /** A trilha precisa deixar claro que quem decidiu foi o sistema, sem acao humana. */
    @Test
    void registraAuditoriaPropriaDaVarredura() {
        Processo p = processo("05/2026", 5, StatusProcesso.ENVIADO);
        votar(p, medicoA, ResultadoParecer.NAO_FAVORAVEL);
        votar(p, medicoB, ResultadoParecer.NAO_FAVORAVEL);

        scheduler.varrer();

        List<LogAuditoria> logs = logRepo.findAll().stream()
                .filter(l -> DecisaoAutomaticaScheduler.ACAO_AUDITORIA.equals(l.getAcao()))
                .toList();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getUsuario()).isEqualTo("sistema");
        assertThat(logs.get(0).getDetalhe())
                .contains("05/2026")
                .contains("varredura automatica")
                .contains("sem acao humana");
    }

    // -------------------------------------------------------------------------
    // Conservadorismo: na duvida, nao decide
    // -------------------------------------------------------------------------

    @Test
    void naoDecideSemMaioriaFormada() {
        Processo p = processo("06/2026", 6, StatusProcesso.ENVIADO);
        votar(p, medicoA, ResultadoParecer.NAO_FAVORAVEL);   // 1 de 3 apenas

        assertThat(scheduler.varrer()).isZero();
        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.ENVIADO);
    }

    /**
     * PAUSA respeitada: processo em SOLICITA_INFORMACAO com 2 desfavoraveis
     * NAO pode ser indeferido pelo varredor — o coordenador nao votou favoravel,
     * entao a pausa vale integralmente e a decisao espera a informacao
     * complementar / a retomada pelo operador.
     */
    @Test
    void naoDecideProcessoPausadoSemVotoFavoravelDoCoordenador() {
        Processo p = processo("08/2026", 8, StatusProcesso.SOLICITA_INFORMACAO);
        votar(p, medicoA, ResultadoParecer.NAO_FAVORAVEL);
        votar(p, medicoB, ResultadoParecer.NAO_FAVORAVEL);
        votar(p, coordenador, ResultadoParecer.SOLICITA_INFORMACAO);

        assertThat(scheduler.varrer()).isZero();
        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);
    }

    /**
     * UNICA excecao da pausa, ja vigente no sistema: o voto Favoravel do
     * coordenador CET-RS defere sozinho mesmo com o processo pausado pelo
     * pedido de informacao de outro avaliador. Se a decisao falhou no instante
     * do voto, esse processo trava permanentemente — e por causa deste caso que
     * SOLICITA_INFORMACAO entra na varredura.
     */
    @Test
    void decideProcessoPausadoQuandoCoordenadorVotouFavoravel() {
        Processo p = processo("09/2026", 9, StatusProcesso.SOLICITA_INFORMACAO);
        votar(p, coordenador, ResultadoParecer.FAVORAVEL);
        votar(p, medicoA, ResultadoParecer.SOLICITA_INFORMACAO);

        assertThat(scheduler.varrer()).isEqualTo(1);
        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.DEFERIDO);
    }

    /** Processo ja encerrado nem entra na varredura (nao e redecidido nem auditado). */
    @Test
    void ignoraProcessoJaFinalizado() {
        Processo p = processo("10/2026", 10, StatusProcesso.INDEFERIDO);
        votar(p, medicoA, ResultadoParecer.NAO_FAVORAVEL);
        votar(p, medicoB, ResultadoParecer.NAO_FAVORAVEL);

        assertThat(scheduler.varrer()).isZero();
        Processo depois = processoRepo.findById(p.getId()).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusProcesso.INDEFERIDO);
        assertThat(depois.getDataDecisao()).isNull();   // nao foi tocado
        assertThat(logRepo.findAll()).isEmpty();
    }

    /** Processo ainda nao enviado aos avaliadores nunca e decidido pelo varredor. */
    @Test
    void ignoraProcessoAindaSolicitado() {
        Processo p = processo("11/2026", 11, StatusProcesso.SOLICITADO);
        votar(p, medicoA, ResultadoParecer.NAO_FAVORAVEL);
        votar(p, medicoB, ResultadoParecer.NAO_FAVORAVEL);

        assertThat(scheduler.varrer()).isZero();
        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.SOLICITADO);
    }

    // -------------------------------------------------------------------------
    // Isolamento entre processos
    // -------------------------------------------------------------------------

    /**
     * REGRESSAO DA CLASSE DE BUG QUE MORDEU 3x: a falha ao decidir UM processo
     * (aqui injetada dentro de {@code ProcessoService.decidir}, apos a gravacao
     * da decisao) nao pode abortar a varredura nem contaminar a transacao dos
     * demais. Se {@code varrer()} fosse {@code @Transactional}, o rollback-only
     * marcado pela chamada aninhada estouraria {@code UnexpectedRollbackException}
     * no commit e desfaria as decisoes boas junto.
     *
     * <p>O processo que falha e o de MENOR sequencial (a varredura e ordenada
     * por ano/sequencial), garantindo que ele seja processado ANTES do que deve
     * sobreviver — se o erro abortasse o laco, o segundo nem seria avaliado.</p>
     */
    @Test
    void falhaEmUmProcessoNaoImpedeADecisaoDosOutros() {
        Processo comFalha = processo("12/2026", 12, StatusProcesso.ENVIADO);
        votar(comFalha, medicoA, ResultadoParecer.NAO_FAVORAVEL);
        votar(comFalha, medicoB, ResultadoParecer.NAO_FAVORAVEL);

        Processo saudavel = processo("13/2026", 13, StatusProcesso.ENVIADO);
        votar(saudavel, medicoA, ResultadoParecer.FAVORAVEL);
        votar(saudavel, medicoB, ResultadoParecer.FAVORAVEL);

        when(solicitacaoOnlineRepo.findByProcessoGeradoId(comFalha.getId()))
                .thenThrow(new IllegalStateException("falha simulada no pos-processamento"));
        when(solicitacaoOnlineRepo.findByProcessoGeradoId(saudavel.getId()))
                .thenReturn(Optional.empty());

        assertThat(scheduler.varrer()).isEqualTo(1);

        // O que falhou foi desfeito por inteiro (transacao propria) e continua
        // aberto para o operador — nunca meio-decidido.
        Processo aindaAberto = processoRepo.findById(comFalha.getId()).orElseThrow();
        assertThat(aindaAberto.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        assertThat(aindaAberto.getDataDecisao()).isNull();

        // E o outro foi decidido normalmente, apesar do vizinho ter explodido.
        assertThat(processoRepo.findById(saudavel.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.DEFERIDO);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Processo processo(String numero, int sequencial, StatusProcesso status) {
        Processo p = new Processo();
        p.setNumero(numero);
        p.setAno(2026);
        p.setSequencial(sequencial);
        p.setPacienteNome("Paciente Da Varredura");
        p.setPacienteRgct("999888777");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(status);
        return processoRepo.saveAndFlush(p);
    }

    /** Voto autenticado no Portal do Avaliador (dispensa o anexo de resposta). */
    private Parecer votar(Processo p, MembroUrgenciaRenal membro, ResultadoParecer resultado) {
        Parecer par = new Parecer(membro);
        par.setProcesso(p);
        par.setDataEnvio(LocalDate.of(2026, 5, 2));
        par.setResultado(resultado);
        par.setDataResposta(LocalDate.of(2026, 5, 3));
        par.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        // Snapshot do papel no momento do voto (Parecer.eraCoordenadorNoVoto),
        // mesmo comportamento de AvaliadorController.registrarVoto -- este
        // helper simula um voto atual autenticado, nao um parecer legado.
        par.setEraCoordenadorNoVoto(membro.isCoordenador());
        return parecerRepo.saveAndFlush(par);
    }
}

package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.OrigemParecer;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.HistoricoParecerRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, {@code ProcessoService} e
 * {@code DecisaoAutomaticaScheduler} REAIS) dos achados C e D do
 * {@code docs/RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md}.
 *
 * <p><b>Achado C</b> — reabrir (ADMIN) um processo que foi encerrado ENQUANTO
 * pausado (um parecer {@code SOLICITA_INFORMACAO} ainda ativo) forcava
 * incondicionalmente {@code Processo.status = ENVIADO}, apagando a pausa: a
 * partir dai, 2 favoraveis comuns (evento de voto OU a varredura periodica)
 * deferiam o processo com um pedido de informacao nunca resolvido.</p>
 *
 * <p>Precisa ser {@code @SpringBootTest} (nao teste de unidade com mocks),
 * pelo mesmo motivo documentado em {@code DecisaoAutomaticaSchedulerIntegrationTest}:
 * o comportamento sob teste depende do proxy transacional real de
 * {@code ProcessoService} e do estado persistido/recarregado entre chamadas —
 * um {@code @MockitoBean ProcessoService} nao expressaria a classe de bug.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-reabertura-pausa;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-reabertura-pausa",
        // Liga o varredor para provar que ELE TAMBEM respeita a pausa restaurada.
        "app.decisao-automatica.varredura.habilitado=true",
        "app.decisao-automatica.varredura.intervalo-ms=3600000",
        "app.decisao-automatica.varredura.atraso-inicial-ms=3600000"
})
class ReaberturaMantemPausaAtivaIntegrationTest {

    @Autowired
    private ProcessoService processoService;
    @Autowired
    private DecisaoAutomaticaScheduler scheduler;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;
    @Autowired
    private HistoricoParecerRepository historicoParecerRepo;

    /** Mockado so para nao depender de e-mail/SMTP nesta suite. */
    @MockitoBean
    private SolicitacaoOnlineRepository solicitacaoOnlineRepo;

    private MembroUrgenciaRenal medicoA;
    private MembroUrgenciaRenal medicoB;
    private MembroUrgenciaRenal medicoC;

    @BeforeEach
    void preparar() {
        when(solicitacaoOnlineRepo.findByProcessoGeradoId(anyLong())).thenReturn(Optional.empty());
        // F4 do relatorio de vistoria de brechas (2026-08-10): retomarAposInformacao
        // agora arquiva um HistoricoParecer (FK para processo_id) antes de
        // resetar o parecer - precisa ser limpo ANTES de excluir os
        // processos, senao a FK bloqueia o deleteAll() de processoRepo numa
        // segunda execucao de teste desta classe no mesmo H2.
        historicoParecerRepo.deleteAll();
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        medicoA = membroRepo.saveAndFlush(new MembroUrgenciaRenal("HCPA", "Medico A", "a@example.com"));
        medicoB = membroRepo.saveAndFlush(new MembroUrgenciaRenal("ISCMPA", "Medico B", "b@example.com"));
        medicoC = membroRepo.saveAndFlush(new MembroUrgenciaRenal("HSL", "Medico C", "c@example.com"));
    }

    /**
     * Reabertura simples: processo pausado (parecer B pedindo informacao),
     * encerrado como CANCELADO (caminho que a pausa permite), reaberto pelo
     * ADMIN. O status pos-reabertura precisa ser SOLICITA_INFORMACAO, nao
     * ENVIADO — a pausa continua valendo ate a retomada de verdade.
     */
    @Test
    void reabrirComParecerSolicitaInformacaoAindaAtivoRestauraAPausaEmVezDeEnviado() {
        Processo p = processo("20/2026", 20, StatusProcesso.SOLICITA_INFORMACAO);
        votar(p, medicoA, ResultadoParecer.FAVORAVEL);
        votar(p, medicoB, ResultadoParecer.SOLICITA_INFORMACAO);

        processoService.decidir(p.getId(), StatusProcesso.CANCELADO, null);
        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.CANCELADO);

        processoService.reabrir(p.getId());

        Processo depois = processoRepo.findById(p.getId()).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);
    }

    /**
     * Reabertura SEM nenhum parecer pendente de informacao: comportamento
     * historico preservado (volta para ENVIADO normalmente).
     */
    @Test
    void reabrirSemParecerSolicitaInformacaoContinuaVoltandoParaEnviado() {
        Processo p = processo("22/2026", 22, StatusProcesso.ENVIADO);
        votar(p, medicoA, ResultadoParecer.FAVORAVEL);
        votar(p, medicoB, ResultadoParecer.NAO_FAVORAVEL);

        processoService.decidir(p.getId(), StatusProcesso.CANCELADO, null);
        processoService.reabrir(p.getId());

        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.ENVIADO);
    }

    /**
     * Cenario COMPLETO do achado C: apos a reabertura, um terceiro voto
     * favoravel forma a maioria crua de 2/3 (A e C favoraveis) — mas o
     * parecer da B (SOLICITA_INFORMACAO) nunca foi resolvido (ninguem chamou
     * retomarAposInformacao). A decisao PRECISA continuar bloqueada nos 3
     * caminhos que poderiam formalizar um Deferido: decisao manual, o evento
     * de voto (tentarDecisaoAutomatica) e a varredura periodica.
     */
    @Test
    void decisaoContinuaBloqueadaAposReabrirComMaioriaCruaFormadaEPausaAindaAtiva() {
        Processo p = processo("21/2026", 21, StatusProcesso.SOLICITA_INFORMACAO);
        votar(p, medicoA, ResultadoParecer.FAVORAVEL);
        votar(p, medicoB, ResultadoParecer.SOLICITA_INFORMACAO);

        processoService.decidir(p.getId(), StatusProcesso.CANCELADO, null);
        processoService.reabrir(p.getId());
        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);

        // Terceiro medico vota favoravel: 2 favoraveis "crus" (A e C), mas o
        // parecer de B continua SOLICITA_INFORMACAO.
        votar(p, medicoC, ResultadoParecer.FAVORAVEL);

        // 1) Decisao manual continua rejeitada.
        assertThatThrownBy(() -> processoService.decidir(p.getId(), StatusProcesso.DEFERIDO, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("informacao complementar");

        // 2) O caminho automatico disparado por evento (chamado pelo
        // controller apos cada voto no Portal do Avaliador) tambem nao decide.
        Processo aposEvento = processoService.tentarDecisaoAutomatica(p.getId());
        assertThat(aposEvento.getStatus()).isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);

        // 3) E a varredura periodica tambem respeita a pausa restaurada.
        assertThat(scheduler.varrer()).isZero();
        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);
    }

    /**
     * Mesmo cenario, mas o parecer pendente e resolvido de verdade
     * (retomarAposInformacao) antes do terceiro voto: agora SIM a maioria
     * pode decidir automaticamente — prova que a correcao nao bloqueia o
     * fluxo legitimo pos-retomada, so o caso em que a pausa nunca foi
     * resolvida.
     */
    @Test
    void decisaoProsseguiNormalmenteAposRetomarDeVerdadeAIndoAlemDaReabertura() {
        Processo p = processo("23/2026", 23, StatusProcesso.SOLICITA_INFORMACAO);
        votar(p, medicoA, ResultadoParecer.FAVORAVEL);
        Parecer parecerB = votar(p, medicoB, ResultadoParecer.SOLICITA_INFORMACAO);

        processoService.decidir(p.getId(), StatusProcesso.CANCELADO, null);
        processoService.reabrir(p.getId());

        // Retomada de verdade: reabre o MESMO parecer de B (limpa o
        // resultado, mesmo comportamento de retomarAposInformacao em producao).
        processoService.retomarAposInformacao(p.getId());
        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.ENVIADO);
        assertThat(parecerRepo.findById(parecerB.getId()).orElseThrow().getResultado()).isNull();

        // B vota de novo, agora Favoravel de verdade (mesma linha que o
        // AvaliadorController atualiza no voto real, nao uma linha nova).
        Parecer parecerBRecarregado = parecerRepo.findById(parecerB.getId()).orElseThrow();
        parecerBRecarregado.setResultado(ResultadoParecer.FAVORAVEL);
        parecerBRecarregado.setDataResposta(LocalDate.of(2026, 5, 4));
        parecerBRecarregado.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        parecerRepo.saveAndFlush(parecerBRecarregado);

        Processo decidido = processoService.tentarDecisaoAutomatica(p.getId());
        assertThat(decidido.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
    }

    // -------------------------------------------------------------------------
    // Helpers (mesmo padrao de DecisaoAutomaticaSchedulerIntegrationTest)
    // -------------------------------------------------------------------------

    private Processo processo(String numero, int sequencial, StatusProcesso status) {
        Processo p = new Processo();
        p.setNumero(numero);
        p.setAno(2026);
        p.setSequencial(sequencial);
        p.setPacienteNome("Paciente Da Reabertura");
        p.setPacienteRgct("111222333");
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
        return parecerRepo.saveAndFlush(par);
    }
}

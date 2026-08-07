package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessoServiceTest {

    @Mock
    ProcessoRepository processoRepository;
    @Mock
    MembroUrgenciaRenalRepository membroRepository;
    @Mock
    ParecerRepository parecerRepository;
    @Mock
    SolicitacaoOnlineRepository solicitacaoOnlineRepository;
    @Mock
    EmailTemplateService emailTemplateService;
    @Mock
    EmailSenderService emailSenderService;
    @Mock
    AnexoStorageService anexoStorageService;
    ProcessoService service;

    // Usa o ProcessoValidator real (funcoes puras): as regras de negocio vivem
    // nele, e o servico apenas delega.
    @BeforeEach
    void setUp() {
        service = new ProcessoService(processoRepository, membroRepository, new ProcessoValidator(), parecerRepository,
                solicitacaoOnlineRepository, emailTemplateService, emailSenderService, anexoStorageService);
    }

    private Parecer parecer(ResultadoParecer r) {
        Parecer p = new Parecer(new MembroUrgenciaRenal("HCPA", "Medico", null));
        p.setResultado(r);
        return p;
    }

    /** Parecer de um avaliador marcado como coordenador CET-RS. */
    private Parecer parecerCoordenador(ResultadoParecer r) {
        MembroUrgenciaRenal coordenador = new MembroUrgenciaRenal("CET-RS", "Coordenador", null);
        coordenador.setCoordenador(true);
        Parecer p = new Parecer(coordenador);
        p.setResultado(r);
        // Snapshot do papel no momento do voto (ver Parecer.eraCoordenadorNoVoto) --
        // simula um voto atual, nao um parecer legado sem o dado.
        p.setEraCoordenadorNoVoto(true);
        return p;
    }

    private Processo comPareceres(ResultadoParecer... resultados) {
        Processo p = new Processo();
        long id = 1;
        for (ResultadoParecer r : resultados) {
            Parecer par = parecer(r);
            par.setId(id++);
            p.addParecer(par);
        }
        return p;
    }

    @Test
    void defereComDoisFavoraveis() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, ResultadoParecer.FAVORAVEL, null);
        assertThat(service.contarFavoraveis(p)).isEqualTo(2);
        assertThat(service.sugerirDecisao(p)).contains(StatusProcesso.DEFERIDO);
    }

    @Test
    void indefereQuandoTodosResponderamSemMaioriaFavoravel() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        assertThat(service.sugerirDecisao(p)).contains(StatusProcesso.INDEFERIDO);
    }

    @Test
    void coordenadorFavoravelDefereSozinhoMesmoComOutrosPareceresPendentes() {
        Processo p = new Processo();
        Parecer parCoord = parecerCoordenador(ResultadoParecer.FAVORAVEL);
        parCoord.setId(1L);
        p.addParecer(parCoord);
        // os outros 2 avaliadores ainda nao responderam (resultado == null)
        Parecer par2 = parecer(null);
        par2.setId(2L);
        Parecer par3 = parecer(null);
        par3.setId(3L);
        p.addParecer(par2);
        p.addParecer(par3);

        assertThat(service.temVotoCoordenadorFavoravel(p)).isTrue();
        assertThat(service.favoraveisNecessariosParaDeferir(p)).isEqualTo(1);
        assertThat(service.sugerirDecisao(p)).contains(StatusProcesso.DEFERIDO);
    }

    @Test
    void decidirDeferidoComApenasUmFavoravelDoCoordenador() {
        Processo p = new Processo();
        Parecer parCoord = parecerCoordenador(ResultadoParecer.FAVORAVEL);
        parCoord.setId(10L);
        p.addParecer(parCoord);
        when(processoRepository.findById(30L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        service.decidir(30L, StatusProcesso.DEFERIDO, null);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        assertThat(service.deferidoPeloCoordenador(p)).isTrue();
    }

    @Test
    void edicaoBloqueadaSomenteQuandoProcessoEncerrado() {
        Processo emAndamento = new Processo();
        emAndamento.setStatus(StatusProcesso.ENVIADO);
        assertThat(service.edicaoBloqueada(emAndamento)).isFalse();

        for (StatusProcesso encerrado : java.util.List.of(
                StatusProcesso.DEFERIDO, StatusProcesso.INDEFERIDO, StatusProcesso.CANCELADO)) {
            Processo p = new Processo();
            p.setStatus(encerrado);
            assertThat(service.edicaoBloqueada(p)).isTrue();
        }
    }

    @Test
    void atualizarDadosRejeitaProcessoEncerrado() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        when(processoRepository.findById(50L)).thenReturn(java.util.Optional.of(p));

        assertThatThrownBy(() -> service.atualizarDados(50L, new Processo()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encerrado");
    }

    @Test
    void decidirRejeitaProcessoJaEncerrado() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        when(processoRepository.findById(51L)).thenReturn(java.util.Optional.of(p));

        assertThatThrownBy(() -> service.decidir(51L, StatusProcesso.INDEFERIDO, "motivo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encerrado");
    }

    @Test
    void indeferidoContinuaExigindoDoisDesfavoraveisMesmoComCoordenadorNoProcesso() {
        // O peso especial do coordenador vale so para Deferir; um unico voto
        // desfavoravel do coordenador NAO indefere sozinho - continua exigindo
        // a maioria simples normal (2 de 3 desfavoraveis).
        Processo p = new Processo();
        Parecer coordDesfavoravel = parecerCoordenador(ResultadoParecer.NAO_FAVORAVEL);
        coordDesfavoravel.setId(99L);
        p.addParecer(coordDesfavoravel);
        Parecer par2 = parecer(null);
        par2.setId(100L);
        Parecer par3 = parecer(null);
        par3.setId(101L);
        p.addParecer(par2);
        p.addParecer(par3);
        when(processoRepository.findById(31L)).thenReturn(java.util.Optional.of(p));

        assertThatThrownBy(() -> service.decidir(31L, StatusProcesso.INDEFERIDO, "motivo"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.INDEFERIDO);
    }

    @Test
    void semSugestaoQuandoFaltamRespostas() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, null, null);
        assertThat(service.sugerirDecisao(p)).isEmpty();
    }

    @Test
    void numeracaoManualEm2026EAutomaticaEm2027() {
        assertThat(service.isNumeracaoAutomatica(2026)).isFalse();
        assertThat(service.isNumeracaoAutomatica(2027)).isTrue();
    }

    @Test
    void proximoNumeroFormataSequencialDoAno() {
        when(processoRepository.findMaxSequencialByAno(2027)).thenReturn(4);
        assertThat(service.proximoNumero(2027)).isEqualTo("05/2027");
    }

    @Test
    void proximoNumeroComecaEmUmQuandoAnoVazio() {
        when(processoRepository.findMaxSequencialByAno(2027)).thenReturn(null);
        assertThat(service.proximoNumero(2027)).isEqualTo("01/2027");
    }

    @Test
    void contarRespondidosIgnoraPendentes() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, ResultadoParecer.SEM_RESPOSTA, null);
        assertThat(service.contarRespondidos(p)).isEqualTo(2);
    }

    @Test
    void processoNasceSolicitado() {
        assertThat(new Processo().getStatus()).isEqualTo(StatusProcesso.SOLICITADO);
    }

    @Test
    void registrarEnvioMudaParaEnviado() {
        Processo p = new Processo();
        anexarDocumentoClinicoPdf(p);
        when(processoRepository.findById(1L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.registrarEnvio(1L);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
    }

    /**
     * Bug real corrigido (2026-08-03): reenviar documentos atualizados aos
     * avaliadores enquanto o processo aguarda informacao complementar do
     * solicitante NAO pode tirar o processo da pausa - so
     * retomarAposInformacao() faz isso, depois de o operador confirmar que a
     * informacao chegou. Antes, a condicao era isEmAndamento() (que inclui
     * SOLICITA_INFORMACAO), e o reenvio desfazia a pausa silenciosamente:
     * validarPausaDecisao passava a nao bloquear mais a decisao, e o parecer
     * de quem pediu a informacao ficava travado para sempre.
     */
    @Test
    void registrarEnvioNaoTiraProcessoDaPausaSolicitaInformacao() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        anexarDocumentoClinicoPdf(p);
        when(processoRepository.findById(4L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        service.registrarEnvio(4L);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);
    }

    /**
     * Defesa em profundidade: registrarEnvio() espelha, no servico, a mesma
     * regra ja imposta no controller (documento clinico PDF obrigatorio),
     * para que o metodo nunca marque ENVIADO sem essa garantia, mesmo
     * chamado de outro lugar sem passar pelo controller.
     */
    @Test
    void registrarEnvioRejeitaSemDocumentoClinicoPdf() {
        Processo p = new Processo();
        when(processoRepository.findById(3L)).thenReturn(java.util.Optional.of(p));

        assertThatThrownBy(() -> service.registrarEnvio(3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("documento clinico");
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.ENVIADO);
    }

    private void anexarDocumentoClinicoPdf(Processo p) {
        Anexo doc = new Anexo();
        doc.setTipo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        doc.setContentType("application/pdf");
        p.addAnexo(doc);
    }

    @Test
    void atualizarStatusVaiParaSolicitaInformacaoQuandoMedicoPedeInfo() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.SOLICITA_INFORMACAO, null);
        when(processoRepository.findById(2L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.atualizarStatusPorPareceres(2L);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);
    }

    @Test
    void atualizarStatusRejeitaProcessoJaDecidido() {
        // Guarda contra chamar o metodo sobre processo finalizado: os callers
        // reais (controllers) ja barram antes via edicaoBloqueada, entao chegar
        // aqui com um processo finalizado e erro de programacao, nao um caso a
        // silenciar - deve lancar em vez de fazer no-op.
        Processo p = comPareceres(ResultadoParecer.SOLICITA_INFORMACAO);
        p.setStatus(StatusProcesso.DEFERIDO);
        when(processoRepository.findById(3L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.atualizarStatusPorPareceres(3L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void retomarAposInformacaoVoltaParaEnviadoEReabreParecer() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.SOLICITA_INFORMACAO, null);
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        when(processoRepository.findById(20L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.retomarAposInformacao(20L);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        // o parecer que pediu informacao foi reaberto (resultado limpo)
        assertThat(p.getPareceres().get(1).getResultado()).isNull();
    }

    /**
     * Ao reabrir o parecer que pediu informacao, o reset deve ser COMPLETO:
     * nenhum metadado do voto antigo pode sobreviver (nao-repudio), mas dataEnvio
     * (o processo foi enviado de fato) deve ser preservado.
     */
    @Test
    void retomarAposInformacaoZeraTodosOsMetadadosDoParecerReaberto() {
        Processo p = comPareceres(ResultadoParecer.SOLICITA_INFORMACAO);
        Parecer reaberto = p.getPareceres().get(0);
        java.time.LocalDate envio = java.time.LocalDate.of(2026, 6, 1);
        reaberto.setDataEnvio(envio);
        reaberto.setDataResposta(java.time.LocalDate.of(2026, 6, 10));
        reaberto.setDataHoraVoto(java.time.LocalDateTime.of(2026, 6, 10, 9, 30));
        reaberto.setVotadoPor("avaliador1");
        reaberto.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        reaberto.setJustificativa("Faltam exames laboratoriais.");
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        when(processoRepository.findById(22L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        service.retomarAposInformacao(22L);

        assertThat(reaberto.getResultado()).isNull();
        assertThat(reaberto.getDataResposta()).isNull();
        assertThat(reaberto.getDataHoraVoto()).isNull();
        assertThat(reaberto.getVotadoPor()).isNull();
        assertThat(reaberto.getOrigem()).isNull();
        assertThat(reaberto.getJustificativa()).isNull();
        // dataEnvio NAO e tocada (o processo foi enviado de fato)
        assertThat(reaberto.getDataEnvio()).isEqualTo(envio);
    }

    /**
     * Pareceres ja definitivos (FAVORAVEL/NAO_FAVORAVEL) NAO podem ser tocados
     * na retomada — so os que estavam em SOLICITA_INFORMACAO sao reabertos.
     */
    @Test
    void retomarAposInformacaoNaoTocaPareceresDefinitivos() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.SOLICITA_INFORMACAO, ResultadoParecer.NAO_FAVORAVEL);
        Parecer favoravel = p.getPareceres().get(0);
        favoravel.setVotadoPor("avaliador1");
        favoravel.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        favoravel.setJustificativa("Caso elegivel.");
        favoravel.setDataResposta(java.time.LocalDate.of(2026, 6, 5));
        Parecer naoFavoravel = p.getPareceres().get(2);
        naoFavoravel.setVotadoPor("operador");
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        when(processoRepository.findById(23L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        service.retomarAposInformacao(23L);

        // o do meio foi reaberto
        assertThat(p.getPareceres().get(1).getResultado()).isNull();
        // os definitivos permanecem intactos
        assertThat(favoravel.getResultado()).isEqualTo(ResultadoParecer.FAVORAVEL);
        assertThat(favoravel.getVotadoPor()).isEqualTo("avaliador1");
        assertThat(favoravel.getOrigem()).isEqualTo(OrigemParecer.AVALIADOR_SISTEMA);
        assertThat(favoravel.getJustificativa()).isEqualTo("Caso elegivel.");
        assertThat(favoravel.getDataResposta()).isEqualTo(java.time.LocalDate.of(2026, 6, 5));
        assertThat(naoFavoravel.getResultado()).isEqualTo(ResultadoParecer.NAO_FAVORAVEL);
        assertThat(naoFavoravel.getVotadoPor()).isEqualTo("operador");
    }

    @Test
    void decidirBloqueiaQuandoAguardandoInformacaoComplementar() {
        Processo p = comPareceres(ResultadoParecer.NAO_FAVORAVEL,
                ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.SOLICITA_INFORMACAO);
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        when(processoRepository.findById(21L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.decidir(21L, StatusProcesso.INDEFERIDO, "motivo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("informacao complementar");
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);
    }

    @Test
    void decidirDeferidoExigeNoMinimoDoisFavoraveis() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        when(processoRepository.findById(4L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.decidir(4L, StatusProcesso.DEFERIDO, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void decidirDeferidoComDoisFavoraveis() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        when(processoRepository.findById(5L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.decidir(5L, StatusProcesso.DEFERIDO, null);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void decidirIndeferidoExigeNoMinimoDoisDesfavoraveis() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        when(processoRepository.findById(6L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.decidir(6L, StatusProcesso.INDEFERIDO, "motivo"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.INDEFERIDO);
    }

    @Test
    void decidirIndeferidoComDoisDesfavoraveis() {
        Processo p = comPareceres(ResultadoParecer.NAO_FAVORAVEL,
                ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.FAVORAVEL);
        when(processoRepository.findById(7L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.decidir(7L, StatusProcesso.INDEFERIDO, "motivo");
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.INDEFERIDO);
    }

    @Test
    void decidirIndeferidoExigeMotivo() {
        // Defesa em profundidade: mesmo chamando o service diretamente (sem
        // passar pela pre-validacao do controller), Indeferido sem motivo
        // deve ser rejeitado.
        Processo p = comPareceres(ResultadoParecer.NAO_FAVORAVEL,
                ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.FAVORAVEL);
        when(processoRepository.findById(24L)).thenReturn(java.util.Optional.of(p));
        assertThatThrownBy(() -> service.decidir(24L, StatusProcesso.INDEFERIDO, "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("motivo");
        assertThat(p.getStatus()).isNotEqualTo(StatusProcesso.INDEFERIDO);
    }

    @Test
    void cadastrarExigeExatamenteTresMedicos() {
        Processo p = new Processo();
        assertThatThrownBy(() -> service.cadastrar(p, java.util.List.of(1L, 2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exatamente");
        assertThatThrownBy(() -> service.cadastrar(p, java.util.List.of(1L, 2L, 3L, 4L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.cadastrar(p, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * decidir() nao exige nenhum anexo comprobatorio: o voto direto do
     * avaliador autenticado no portal (AVALIADOR_SISTEMA) e a unica origem de
     * parecer, e o proprio registro autenticado ja e a prova do voto.
     */
    @Test
    void decidirPermitidoQuandoTodosVotosForamPeloPortal() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        // Marca todos como voto direto do portal
        p.getPareceres().forEach(par -> par.setOrigem(OrigemParecer.AVALIADOR_SISTEMA));

        when(processoRepository.findById(99L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        service.decidir(99L, StatusProcesso.DEFERIDO, null);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
    }

    @Test
    void reabrirVoltaParaEnviadoELimpaDecisao() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        when(processoRepository.findById(30L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        service.decidir(30L, StatusProcesso.DEFERIDO, null);
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);

        service.reabrir(30L);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        assertThat(p.getDataDecisao()).isNull();
        assertThat(p.getMotivoIndeferimento()).isNull();
    }

    /**
     * decidir() espelha a decisao na SolicitacaoOnline de origem
     * (DEFERIDO -> APROVADA). reabrir() precisa desfazer esse espelhamento,
     * senao a solicitacao fica presa em APROVADA e o Portal do Solicitante
     * segue mostrando "Aprovada" definitivo para um processo que voltou para
     * analise (o template so consulta processoGerado.status quando o status
     * e CONVERTIDA).
     */
    @Test
    void reabrirDevolveSolicitacaoAprovadaParaConvertida() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        when(processoRepository.findById(31L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        when(solicitacaoOnlineRepository.findByProcessoGeradoId(31L)).thenReturn(java.util.Optional.of(s));

        service.decidir(31L, StatusProcesso.DEFERIDO, null);
        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.APROVADA);

        service.reabrir(31L);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.CONVERTIDA);
    }

    /**
     * CANCELADO espelha como CANCELADA, NAO como REPROVADA: "Reprovada" diria
     * ao solicitante que a equipe analisou e negou o pedido, quando o processo
     * so foi cancelado (as vezes por ele mesmo, pelo portal).
     */
    @Test
    void decidirCanceladoEspelhaSolicitacaoComoCanceladaNaoReprovada() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, null, null);
        when(processoRepository.findById(33L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        when(solicitacaoOnlineRepository.findByProcessoGeradoId(33L)).thenReturn(java.util.Optional.of(s));

        service.decidir(33L, StatusProcesso.CANCELADO, null);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.CANCELADO);
        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.CANCELADA);
    }

    /** Mesma regra pelo lado do indeferimento (REPROVADA -> CONVERTIDA). */
    @Test
    void reabrirDevolveSolicitacaoReprovadaParaConvertida() {
        Processo p = comPareceres(ResultadoParecer.NAO_FAVORAVEL,
                ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.FAVORAVEL);
        when(processoRepository.findById(32L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        when(solicitacaoOnlineRepository.findByProcessoGeradoId(32L)).thenReturn(java.util.Optional.of(s));

        service.decidir(32L, StatusProcesso.INDEFERIDO, "motivo");
        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.REPROVADA);

        service.reabrir(32L);

        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.CONVERTIDA);
    }

    /**
     * Mesma regra pelo lado do cancelamento (CANCELADA -> CONVERTIDA). Sem
     * isso, reabrir um processo que o solicitante cancelou deixava o Portal
     * do Solicitante preso em "Cancelada" durante toda a reanalise, e
     * {@code podeCancelar} (que exige ENVIADA/CONVERTIDA) impedia o
     * solicitante de cancelar de novo um processo que, de fato, esta em
     * andamento outra vez.
     */
    @Test
    void reabrirDevolveSolicitacaoCanceladaParaConvertida() {
        // Comeca em ENVIADO (nao finalizado) - decidir(CANCELADO) e quem leva
        // o processo a CANCELADO; reabrir so faz sentido depois disso.
        Processo p = new Processo();
        p.setStatus(StatusProcesso.ENVIADO);
        when(processoRepository.findById(34L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        when(solicitacaoOnlineRepository.findByProcessoGeradoId(34L)).thenReturn(java.util.Optional.of(s));

        service.decidir(34L, StatusProcesso.CANCELADO, null);
        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.CANCELADA);

        service.reabrir(34L);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.CONVERTIDA);
    }

    /**
     * reabrir() so desfaz o espelhamento de decidir() (APROVADA/REPROVADA/
     * CANCELADA). Status que nada tem a ver com a decisao — aqui
     * PROCESSO_EXCLUIDO — nao podem ser sobrescritos por engano.
     */
    @Test
    void reabrirNaoSobrescreveStatusAlheioDaSolicitacao() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        p.setStatus(StatusProcesso.DEFERIDO);
        when(processoRepository.findById(33L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setStatus(StatusSolicitacaoOnline.PROCESSO_EXCLUIDO);
        when(solicitacaoOnlineRepository.findByProcessoGeradoId(33L)).thenReturn(java.util.Optional.of(s));

        service.reabrir(33L);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.PROCESSO_EXCLUIDO);
        org.mockito.Mockito.verify(solicitacaoOnlineRepository, org.mockito.Mockito.never()).save(any());
    }

    /** Processo que nao veio do Portal: reabrir nao toca em nenhuma solicitacao. */
    @Test
    void reabrirNaoTocaSolicitacaoQuandoProcessoNaoVeioDoPortal() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        p.setStatus(StatusProcesso.DEFERIDO);
        when(processoRepository.findById(34L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        when(solicitacaoOnlineRepository.findByProcessoGeradoId(34L)).thenReturn(java.util.Optional.empty());

        service.reabrir(34L);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        org.mockito.Mockito.verify(solicitacaoOnlineRepository, org.mockito.Mockito.never()).save(any());
    }

    /**
     * Defesa contra mass assignment: o form faz bind da entidade Processo
     * inteira (@ModelAttribute), entao um request malicioso poderia setar
     * status=DEFERIDO (ou outro campo pos-decisao) antes de chamar cadastrar().
     * cadastrar() precisa forcar o estado inicial correto, ignorando o que
     * veio no objeto de entrada.
     */
    @Test
    void cadastrarForcaStatusSolicitadoIgnorandoValorMalicioso() {
        Processo p = new Processo();
        p.setDataSituacaoEspecial(java.time.LocalDate.of(2026, 1, 10));
        // Simula um request malicioso que tenta pular o fluxo de decisao.
        p.setStatus(StatusProcesso.DEFERIDO);
        p.setDataDecisao(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        p.setMotivoIndeferimento("forjado");
        p.setEmailEnviadoSolicitante(true);

        MembroUrgenciaRenal medico = new MembroUrgenciaRenal("HCPA", "Medico", null);
        medico.setId(1L);
        when(membroRepository.findById(1L)).thenReturn(java.util.Optional.of(medico));
        when(membroRepository.findById(2L)).thenReturn(java.util.Optional.of(medico));
        when(membroRepository.findById(3L)).thenReturn(java.util.Optional.of(medico));
        when(processoRepository.save(p)).thenReturn(p);

        Processo salvo = service.cadastrar(p, java.util.List.of(1L, 2L, 3L));

        assertThat(salvo.getStatus()).isEqualTo(StatusProcesso.SOLICITADO);
        assertThat(salvo.getDataDecisao()).isNull();
        assertThat(salvo.getMotivoIndeferimento()).isNull();
        assertThat(salvo.isEmailEnviadoSolicitante()).isFalse();
    }

    /**
     * Bug real: cadastrar() nao limpava o id vindo do bind da entidade inteira.
     * Um request malicioso com id de um processo EXISTENTE fazia o save()
     * cair em merge() em vez de persist(), sobrescrevendo o processo alvo e
     * apagando (orphanRemoval) seus pareceres/anexos reais.
     */
    @Test
    void cadastrarIgnoraIdDeProcessoExistenteVindoDoRequest() {
        Processo p = new Processo();
        p.setId(999L); // simula id de um processo existente injetado no form
        p.setDataSituacaoEspecial(java.time.LocalDate.of(2026, 1, 10));

        MembroUrgenciaRenal medico = new MembroUrgenciaRenal("HCPA", "Medico", null);
        medico.setId(1L);
        when(membroRepository.findById(1L)).thenReturn(java.util.Optional.of(medico));
        when(membroRepository.findById(2L)).thenReturn(java.util.Optional.of(medico));
        when(membroRepository.findById(3L)).thenReturn(java.util.Optional.of(medico));
        when(processoRepository.save(any(Processo.class))).thenAnswer(inv -> inv.getArgument(0));

        Processo salvo = service.cadastrar(p, java.util.List.of(1L, 2L, 3L));

        assertThat(salvo.getId()).isNull();
    }

    /**
     * O coordenador CET-RS defere sozinho e imediatamente ao votar Favoravel,
     * mesmo que o processo esteja pausado (SOLICITA_INFORMACAO) por causa do
     * parecer de outro avaliador comum. A pausa nao se aplica a essa regra.
     */
    @Test
    void coordenadorFavoravelDefereMesmoComProcessoPausadoPorSolicitaInformacao() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        Parecer parInfo = parecer(ResultadoParecer.SOLICITA_INFORMACAO);
        parInfo.setId(1L);
        p.addParecer(parInfo);

        Parecer parCoord = parecerCoordenador(ResultadoParecer.FAVORAVEL);
        parCoord.setId(2L);
        p.addParecer(parCoord);

        Parecer par3 = parecer(null);
        par3.setId(3L);
        p.addParecer(par3);

        when(processoRepository.findById(40L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        Processo resultado = service.tentarDecisaoAutomatica(40L);

        assertThat(resultado.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        assertThat(service.deferidoPeloCoordenador(resultado)).isTrue();
    }

    /**
     * REGRESSAO (confidencialidade): o motivo gerado automaticamente vira
     * documento EXTERNO — e impresso no oficio oficial em PDF (OficioService)
     * e no e-mail a equipe solicitante (EmailTemplateService). A tela de voto
     * promete ao medico que a justificativa fica "registrada internamente"
     * (avaliador/votar.html), entao o motivo NAO pode conter nome de
     * avaliador nem a justificativa verbatim: isso entregaria ao lado
     * avaliado quem votou contra e o texto clinico cru que ele escreveu.
     * As justificativas continuam gravadas no Parecer (uso interno).
     */
    @Test
    void motivoAutomaticoNaoVazaNomeDeAvaliadorNemJustificativa() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        p.getPareceres().get(0).getMembro().setNome("Dr. Favoravel");
        p.getPareceres().get(1).getMembro().setNome("Dra. Ana");
        p.getPareceres().get(1).setJustificativa("Exames nao confirmam a urgencia.");
        p.getPareceres().get(2).getMembro().setNome("Dr. Bruno");
        p.getPareceres().get(2).setJustificativa("Ausencia de indicacao clinica.");

        when(processoRepository.findById(50L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        Processo resultado = service.tentarDecisaoAutomatica(50L);

        assertThat(resultado.getStatus()).isEqualTo(StatusProcesso.INDEFERIDO);
        assertThat(resultado.getMotivoIndeferimento())
                .isNotBlank()
                .doesNotContain("Dra. Ana")
                .doesNotContain("Dr. Bruno")
                .doesNotContain("Dr. Favoravel")
                .doesNotContain("Exames nao confirmam a urgencia.")
                .doesNotContain("Ausencia de indicacao clinica.");
        // As justificativas seguem gravadas no parecer (uso interno).
        assertThat(p.getPareceres().get(1).getJustificativa())
                .isEqualTo("Exames nao confirmam a urgencia.");
    }

    /**
     * Indeferido tambem finaliza automaticamente (decisao de produto
     * confirmada em 2026-07-29): com 2 de 3 pareceres desfavoraveis e sem
     * voto favoravel do coordenador, o processo vira INDEFERIDO sozinho, com
     * um motivo institucional e impessoal (so a contagem da maioria).
     */
    @Test
    void indefereAutomaticamenteComMotivoInstitucionalImpessoal() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);

        when(processoRepository.findById(51L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        Processo resultado = service.tentarDecisaoAutomatica(51L);

        assertThat(resultado.getStatus()).isEqualTo(StatusProcesso.INDEFERIDO);
        assertThat(resultado.getMotivoIndeferimento()).isEqualTo(
                "Indeferido por decisao da maioria dos membros da Urgencia Renal "
                        + "(2 de 3 pareceres desfavoraveis).");
    }

    /**
     * O texto e o mesmo (impessoal) quando os 3 pareceres sao desfavoraveis —
     * so muda a contagem.
     */
    @Test
    void motivoAutomaticoContaTodosOsDesfavoraveis() {
        Processo p = comPareceres(ResultadoParecer.NAO_FAVORAVEL,
                ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        p.getPareceres().forEach(par -> par.setJustificativa("texto clinico interno"));

        when(processoRepository.findById(52L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        Processo resultado = service.tentarDecisaoAutomatica(52L);

        assertThat(resultado.getMotivoIndeferimento()).isEqualTo(
                "Indeferido por decisao da maioria dos membros da Urgencia Renal "
                        + "(3 de 3 pareceres desfavoraveis).");
    }

    /**
     * Mesma regra pelo caminho manual (decidir): validarPausaDecisao nao pode
     * bloquear quando o coordenador votou Favoravel.
     */
    @Test
    void decidirManualPermiteDeferirComCoordenadorMesmoPausado() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        Parecer parInfo = parecer(ResultadoParecer.SOLICITA_INFORMACAO);
        parInfo.setId(1L);
        p.addParecer(parInfo);

        Parecer parCoord = parecerCoordenador(ResultadoParecer.FAVORAVEL);
        parCoord.setId(2L);
        p.addParecer(parCoord);

        when(processoRepository.findById(41L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        service.decidir(41L, StatusProcesso.DEFERIDO, null);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
    }

    /**
     * O coordenador NAO tem peso especial para indeferir (CLAUDE.md): mesmo com
     * voto Favoravel do coordenador registrado, Indeferido continua bloqueado
     * enquanto o processo estiver pausado por SOLICITA_INFORMACAO. Bug real:
     * validarPausaDecisao aplicava o bypass do coordenador tambem a Indeferido.
     */
    @Test
    void decidirNaoPermiteIndeferirPausadoMesmoComCoordenadorFavoravel() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        Parecer parInfo = parecer(ResultadoParecer.SOLICITA_INFORMACAO);
        parInfo.setId(1L);
        p.addParecer(parInfo);

        Parecer parCoord = parecerCoordenador(ResultadoParecer.FAVORAVEL);
        parCoord.setId(2L);
        p.addParecer(parCoord);

        Parecer parDesfav = parecer(ResultadoParecer.NAO_FAVORAVEL);
        parDesfav.setId(3L);
        p.addParecer(parDesfav);

        when(processoRepository.findById(42L)).thenReturn(java.util.Optional.of(p));

        assertThatThrownBy(() -> service.decidir(42L, StatusProcesso.INDEFERIDO, "motivo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("informacao complementar");
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.SOLICITA_INFORMACAO);
    }

    @Test
    void reabrirLancaErroSeProcessoNaoEstiverFinalizado() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, null, null);
        p.setStatus(StatusProcesso.ENVIADO);
        when(processoRepository.findById(31L)).thenReturn(java.util.Optional.of(p));

        assertThatThrownBy(() -> service.reabrir(31L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encerrados");
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
    }

    // Hotfix 2026-07-26: excluir um processo nascido do Portal do Solicitante
    // (SolicitacaoOnline.processoGerado) estourava DataIntegrityViolationException
    // (violacao de FK) porque essa referencia ManyToOne nao tem cascade/orphan
    // removal a partir do Processo. O fix desvincula a SolicitacaoOnline (sem
    // apagar) antes do delete. Hotfix seguinte (mesmo dia): o status ficava
    // preso em CONVERTIDA mesmo sem processo, mostrando mensagem enganosa ao
    // solicitante - agora tambem muda para PROCESSO_EXCLUIDO.
    @Test
    void excluirDesvinculaSolicitacaoOnlineQuandoProcessoVeioDoPortal() {
        org.springframework.security.core.context.SecurityContext context = org.springframework.security.core.context.SecurityContextHolder
                .createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin", "senha",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            Processo p = comPareceres(ResultadoParecer.FAVORAVEL, null, null);
            p.setId(99L);
            p.setStatus(StatusProcesso.SOLICITADO);
            when(processoRepository.findById(99L)).thenReturn(java.util.Optional.of(p));

            SolicitacaoOnline s = new SolicitacaoOnline();
            s.setId(7L);
            s.setProcessoGerado(p);
            s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
            when(solicitacaoOnlineRepository.findByProcessoGeradoId(99L)).thenReturn(java.util.Optional.of(s));

            service.excluir(99L);

            assertThat(s.getProcessoGerado()).isNull();
            assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.PROCESSO_EXCLUIDO);
            org.mockito.Mockito.verify(solicitacaoOnlineRepository).save(s);
            org.mockito.Mockito.verify(processoRepository).delete(p);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void excluirNaoTocaSolicitacaoOnlineQuandoProcessoForTradicional() {
        org.springframework.security.core.context.SecurityContext context = org.springframework.security.core.context.SecurityContextHolder
                .createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin", "senha",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            Processo p = comPareceres(ResultadoParecer.FAVORAVEL, null, null);
            p.setId(100L);
            p.setStatus(StatusProcesso.SOLICITADO);
            when(processoRepository.findById(100L)).thenReturn(java.util.Optional.of(p));
            when(solicitacaoOnlineRepository.findByProcessoGeradoId(100L)).thenReturn(java.util.Optional.empty());

            service.excluir(100L);

            org.mockito.Mockito.verify(solicitacaoOnlineRepository, org.mockito.Mockito.never()).save(any());
            org.mockito.Mockito.verify(processoRepository).delete(p);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    // ----- decidir atualiza status da SolicitacaoOnline de origem -----

    @Test
    void decidirDeferidoAtualizaSolicitacaoParaAprovada() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        p.setId(200L);
        when(processoRepository.findById(200L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        when(solicitacaoOnlineRepository.findByProcessoGeradoId(200L)).thenReturn(java.util.Optional.of(s));

        service.decidir(200L, StatusProcesso.DEFERIDO, null);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.APROVADA);
        org.mockito.Mockito.verify(solicitacaoOnlineRepository).save(s);
    }

    @Test
    void decidirIndeferidoAtualizaSolicitacaoParaReprovada() {
        Processo p = comPareceres(ResultadoParecer.NAO_FAVORAVEL,
                ResultadoParecer.NAO_FAVORAVEL, ResultadoParecer.FAVORAVEL);
        p.setId(201L);
        when(processoRepository.findById(201L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        when(solicitacaoOnlineRepository.findByProcessoGeradoId(201L)).thenReturn(java.util.Optional.of(s));

        service.decidir(201L, StatusProcesso.INDEFERIDO, "motivo");

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.INDEFERIDO);
        assertThat(s.getStatus()).isEqualTo(StatusSolicitacaoOnline.REPROVADA);
        org.mockito.Mockito.verify(solicitacaoOnlineRepository).save(s);
    }

    @Test
    void decidirNaoAtualizaSolicitacaoQuandoProcessoNaoVeioDoPortal() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        p.setId(202L);
        when(processoRepository.findById(202L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);
        when(solicitacaoOnlineRepository.findByProcessoGeradoId(202L)).thenReturn(java.util.Optional.empty());

        service.decidir(202L, StatusProcesso.DEFERIDO, null);

        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        org.mockito.Mockito.verify(solicitacaoOnlineRepository, org.mockito.Mockito.never()).save(any());
    }

    // ----- finalizarResposta -----

    @Test
    void finalizarRespostaEnviaEmailESalvaMensagem() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        p.setId(300L);
        p.setStatus(StatusProcesso.DEFERIDO);
        p.setSolicitanteEmail("solicitante@test.com");

        Anexo comprovante = new Anexo();
        comprovante.setTipo(TipoAnexo.COMPROVANTE_SNT);
        comprovante.setNomeArquivo("comprovante.pdf");
        p.addAnexo(comprovante);

        when(processoRepository.findById(300L)).thenReturn(java.util.Optional.of(p));
        when(processoRepository.save(p)).thenReturn(p);

        var template = new EmailTemplate(
                "deferido", "titulo", "icon",
                "Assunto: Processo DEFERIDO", "Corpo do email de deferimento");
        when(emailTemplateService.emailDeferido(p)).thenReturn(template);

        when(anexoStorageService.buscarUltimoPorTipo(300L, TipoAnexo.COMPROVANTE_SNT))
                .thenReturn(comprovante);
        when(anexoStorageService.resolverArquivo(comprovante))
                .thenReturn(java.nio.file.Paths.get("test.pdf"));
        when(emailSenderService.enviarComAnexo(
                "solicitante@test.com", "Assunto: Processo DEFERIDO", "Corpo do email de deferimento",
                java.nio.file.Paths.get("test.pdf").toFile(), "comprovante.pdf"))
                .thenReturn(true);

        Processo resultado = service.finalizarResposta(300L);

        assertThat(resultado.isEmailEnviadoSolicitante()).isTrue();
        assertThat(resultado.getMensagemResposta()).isEqualTo("Corpo do email de deferimento");
    }

    @Test
    void finalizarRespostaRejeitaProcessoNaoDecidido() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, null, null);
        p.setId(301L);
        p.setStatus(StatusProcesso.ENVIADO);
        when(processoRepository.findById(301L)).thenReturn(java.util.Optional.of(p));

        assertThatThrownBy(() -> service.finalizarResposta(301L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ainda nao foi decidido");
    }

    @Test
    void finalizarRespostaRejeitaProcessoCancelado() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL, null, null);
        p.setId(302L);
        p.setStatus(StatusProcesso.CANCELADO);
        when(processoRepository.findById(302L)).thenReturn(java.util.Optional.of(p));

        assertThatThrownBy(() -> service.finalizarResposta(302L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelado");
    }

    /**
     * LACUNA CRITICA (regra deliberada e comentada no proprio metodo: "se o
     * SMTP falha, nada e gravado - rollback de proposito", ao contrario de
     * {@code RegistroEnvioService.enviarConvitesAvaliadores"). So o caminho
     * feliz tinha teste ({@link #finalizarRespostaEnviaEmailESalvaMensagem()});
     * este cobre a falha do SMTP.
     *
     * <p>O metodo envia o e-mail ANTES de mutar {@code p}/chamar
     * {@code processoRepository.save} - por isso basta o mock devolver
     * {@code false} e conferir que a MESMA instancia de {@code Processo} (nao
     * uma copia) continua com os campos de resposta intocados: se um dia a
     * ordem for invertida (mutar antes de checar o retorno do envio), este
     * teste pega o regressao sem precisar de um {@code @SpringBootTest} - a
     * mutacao em memoria aconteceria no mesmo objeto Java que o teste observa,
     * banco real ou nao.</p>
     */
    @Test
    void finalizarRespostaNaoGravaNadaQuandoEnvioDeEmailFalha() {
        Processo p = comPareceres(ResultadoParecer.FAVORAVEL,
                ResultadoParecer.FAVORAVEL, ResultadoParecer.NAO_FAVORAVEL);
        p.setId(303L);
        p.setStatus(StatusProcesso.DEFERIDO);
        p.setSolicitanteEmail("solicitante@test.com");

        Anexo comprovante = new Anexo();
        comprovante.setTipo(TipoAnexo.COMPROVANTE_SNT);
        comprovante.setNomeArquivo("comprovante.pdf");
        p.addAnexo(comprovante);

        when(processoRepository.findById(303L)).thenReturn(java.util.Optional.of(p));

        var template = new EmailTemplate(
                "deferido", "titulo", "icon",
                "Assunto: Processo DEFERIDO", "Corpo do email de deferimento");
        when(emailTemplateService.emailDeferido(p)).thenReturn(template);

        when(anexoStorageService.buscarUltimoPorTipo(303L, TipoAnexo.COMPROVANTE_SNT))
                .thenReturn(comprovante);
        when(anexoStorageService.resolverArquivo(comprovante))
                .thenReturn(java.nio.file.Paths.get("test.pdf"));
        // SMTP fora do ar: enviarComAnexo devolve false (nao lanca).
        when(emailSenderService.enviarComAnexo(
                "solicitante@test.com", "Assunto: Processo DEFERIDO", "Corpo do email de deferimento",
                java.nio.file.Paths.get("test.pdf").toFile(), "comprovante.pdf"))
                .thenReturn(false);

        assertThatThrownBy(() -> service.finalizarResposta(303L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Falha ao enviar e-mail");

        // Nada gravado: nem o objeto em memoria foi mutado, nem o repositorio
        // foi chamado para persistir a "resposta enviada" que nunca saiu.
        assertThat(p.isEmailEnviadoSolicitante()).isFalse();
        assertThat(p.getMensagemResposta()).isNull();
        org.mockito.Mockito.verify(processoRepository, org.mockito.Mockito.never()).save(any());
    }

    /**
     * reivindicarConviteAvaliador so delega ao UPDATE condicional do
     * repositorio ({@code reivindicarConviteSeElegivel}) e traduz "0 linhas
     * afetadas" / "1 linha afetada" para false/true - a trava de duplicidade
     * de verdade (concorrencia real, banco de verdade) e coberta pelo teste de
     * integracao {@code ConviteAvaliadorDuplicidadeIntegrationTest}, que nao
     * pode mockar o repositorio.
     */
    @Test
    void reivindicarConviteAvaliadorDelegaAoUpdateCondicionalDoRepositorio() {
        when(parecerRepository.reivindicarConviteSeElegivel(
                org.mockito.ArgumentMatchers.eq(10L), any(), any())).thenReturn(1);
        when(parecerRepository.reivindicarConviteSeElegivel(
                org.mockito.ArgumentMatchers.eq(20L), any(), any())).thenReturn(0);

        assertThat(service.reivindicarConviteAvaliador(10L, 5)).isTrue();
        assertThat(service.reivindicarConviteAvaliador(20L, 5)).isFalse();
    }
}

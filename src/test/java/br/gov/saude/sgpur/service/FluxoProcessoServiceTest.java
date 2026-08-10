package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.HistoricoParecerRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.service.dto.EstadoEtapa;
import br.gov.saude.sgpur.service.dto.EtapaFluxo;
import br.gov.saude.sgpur.service.dto.EtapaFluxo.Chave;
import br.gov.saude.sgpur.service.dto.PassoWizard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class FluxoProcessoServiceTest {

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
    @Mock
    HistoricoParecerRepository historicoParecerRepository;

    private FluxoProcessoService fluxo() {
        ProcessoService ps = new ProcessoService(processoRepository, membroRepository, new ProcessoValidator(),
                parecerRepository, solicitacaoOnlineRepository, emailTemplateService, emailSenderService,
                anexoStorageService, historicoParecerRepository);
        // Por padrao nenhum processo veio do Portal do Solicitante nesses
        // testes (comportamento identico ao existente antes da excecao do
        // Portal); testes especificos sobrescrevem esse stub. Note que desde
        // 2026-07-27 isso nao afeta mais o Recebimento (sempre automatico) -
        // so o link "Ver solicitacao original" na tela de detalhe depende disso.
        lenient().when(solicitacaoOnlineRepository.existsByProcessoGeradoId(anyLong())).thenReturn(false);
        return new FluxoProcessoService(ps, solicitacaoOnlineRepository);
    }

    private Processo processoComTresPareceres() {
        Processo p = new Processo();
        for (int i = 0; i < 3; i++) {
            p.addParecer(new Parecer(new MembroUrgenciaRenal("INST" + i, "Medico " + i, null)));
        }
        return p;
    }

    /**
     * Etapa 2 (Envio) concluida: doc clinico PDF anexado + dataEnvio nos 3
     * pareceres.
     */
    private void registrarEnvioCompleto(Processo p) {
        Anexo doc = new Anexo();
        doc.setTipo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        doc.setContentType("application/pdf");
        p.addAnexo(doc);
        p.getPareceres().forEach(par -> par.setDataEnvio(LocalDate.now()));
    }

    /**
     * Etapa 3 (Respostas) concluida por maioria: 2 pareceres com o resultado
     * informado; o 3o fica sem responder.
     */
    private void registrarMaioria(Processo p, ResultadoParecer resultado) {
        long id = 1;
        for (Parecer par : p.getPareceres()) {
            if (par.getId() == null)
                par.setId(id++);
        }
        for (int i = 0; i < 2; i++) {
            Parecer par = p.getPareceres().get(i);
            par.setResultado(resultado);
        }
    }

    /**
     * Monta um processo com as etapas 1-3 completas, pronto para a Decisao
     * (favoravel).
     */
    private Processo processoProntoParaDecisao() {
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.ENVIADO);
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.FAVORAVEL);
        return p;
    }

    @Test
    void envioEhAPrimeiraEtapaEComecaAtualQuandoNaoHaNenhumAnexoOuEnvio() {
        // O Recebimento foi fundido em Envio em 2026-08-05 (era sempre
        // automatico e concluido, sem nenhuma acao real do operador - ver
        // FluxoProcessoService). Hoje Envio e a PRIMEIRA etapa do checklist.
        List<EtapaFluxo> etapas = fluxo().montarEtapas(processoComTresPareceres());
        assertThat(etapas).isNotEmpty();
        assertThat(etapas.get(0).chave()).isEqualTo(Chave.ENVIO);
        assertThat(etapas.get(0).estado()).isEqualTo(EstadoEtapa.ATUAL);
    }

    @Test
    void envioConcluiQuandoTodosPareceresTemDataEnvio() {
        Processo p = processoComTresPareceres();
        p.getPareceres().forEach(par -> par.setDataEnvio(LocalDate.now()));
        EtapaFluxo envio = fluxo().montarEtapas(p).stream()
                .filter(e -> e.chave() == Chave.ENVIO).findFirst().orElseThrow();
        assertThat(envio.estado()).isEqualTo(EstadoEtapa.CONCLUIDA);
    }

    @Test
    void incluiEtapaDeOficioApenasQuandoIndeferido() {
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.INDEFERIDO);
        boolean temOficio = fluxo().montarEtapas(p).stream()
                .anyMatch(e -> e.chave() == Chave.OFICIO);
        assertThat(temOficio).isTrue();

        Processo p2 = processoComTresPareceres();
        p2.setStatus(StatusProcesso.DEFERIDO);
        boolean temOficio2 = fluxo().montarEtapas(p2).stream()
                .anyMatch(e -> e.chave() == Chave.OFICIO);
        assertThat(temOficio2).isFalse();
    }

    @Test
    void resumoPendenciaApontaEtapaAtual() {
        // Envio aos 3 medicos e a primeira etapa do checklist (Recebimento
        // foi fundido nela em 2026-08-05) - fica ATUAL desde o inicio.
        String resumo = fluxo().resumoPendencia(processoComTresPareceres());
        assertThat(resumo).contains("Envio");
    }

    @Test
    void incluiEtapaInformacaoComplementarQuandoSolicitaInformacao() {
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);

        EtapaFluxo info = etapas.stream()
                .filter(e -> e.chave() == Chave.INFO_COMPLEMENTAR).findFirst().orElseThrow();
        assertThat(info.estado()).isNotEqualTo(EstadoEtapa.CONCLUIDA);

        // a decisao fica bloqueada (nunca CONCLUIDA, e nem ATUAL, pois a info pausa o
        // fluxo)
        EtapaFluxo decisao = etapas.stream()
                .filter(e -> e.chave() == Chave.DECISAO).findFirst().orElseThrow();
        assertThat(decisao.estado()).isEqualTo(EstadoEtapa.BLOQUEADA);
    }

    @Test
    void naoIncluiEtapaInformacaoComplementarFora() {
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.ENVIADO);
        boolean tem = fluxo().montarEtapas(p).stream()
                .anyMatch(e -> e.chave() == Chave.INFO_COMPLEMENTAR);
        assertThat(tem).isFalse();
    }

    @Test
    void respostasPodeConcluirComMaioriaSemAguardarTerceiroParecer() {
        // 2 favoraveis + 1 ainda sem responder.
        // Por maioria simples a etapa Respostas ja deve estar CONCLUIDA, sem
        // ficar "Aguardando parecer (2/3)".
        Processo p = processoProntoParaDecisao();
        // terceiro parecer continua sem resposta

        EtapaFluxo respostas = fluxo().montarEtapas(p).stream()
                .filter(e -> e.chave() == Chave.RESPOSTAS).findFirst().orElseThrow();
        assertThat(respostas.estado()).isEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(respostas.detalhe()).doesNotContain("Faltam");
        assertThat(respostas.detalhe()).contains("Maioria formada");

        // e a Decisao final fica como etapa ATUAL (liberada), nao PENDENTE.
        EtapaFluxo decisao = fluxo().montarEtapas(p).stream()
                .filter(e -> e.chave() == Chave.DECISAO).findFirst().orElseThrow();
        assertThat(decisao.estado()).isEqualTo(EstadoEtapa.ATUAL);
    }

    @Test
    void incluiEtapaComprovanteSntApenasQuandoDeferido() {
        Processo def = processoComTresPareceres();
        def.setStatus(StatusProcesso.DEFERIDO);
        boolean temSnt = fluxo().montarEtapas(def).stream()
                .anyMatch(e -> e.chave() == Chave.COMPROVANTE_SNT);
        assertThat(temSnt).isTrue();

        Processo ind = processoComTresPareceres();
        ind.setStatus(StatusProcesso.INDEFERIDO);
        boolean temSnt2 = fluxo().montarEtapas(ind).stream()
                .anyMatch(e -> e.chave() == Chave.COMPROVANTE_SNT);
        assertThat(temSnt2).isFalse();
    }

    @Test
    void deferidoSoConcluiComprovanteSntComAnexo() {
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.DEFERIDO);

        EtapaFluxo sntSem = fluxo().montarEtapas(p).stream()
                .filter(e -> e.chave() == Chave.COMPROVANTE_SNT).findFirst().orElseThrow();
        assertThat(sntSem.estado()).isNotEqualTo(EstadoEtapa.CONCLUIDA);

        Anexo comprovante = new Anexo();
        comprovante.setTipo(TipoAnexo.COMPROVANTE_SNT);
        p.addAnexo(comprovante);

        EtapaFluxo sntCom = fluxo().montarEtapas(p).stream()
                .filter(e -> e.chave() == Chave.COMPROVANTE_SNT).findFirst().orElseThrow();
        assertThat(sntCom.estado()).isEqualTo(EstadoEtapa.CONCLUIDA);
    }

    @Test
    void etapaPosteriorNaoFicaVerdeSeEtapaAnteriorAindaEstaPendente() {
        // Bug real observado em producao: processo Deferido ja tinha a resposta
        // ao solicitante marcada (e-mail + comprovante de envio anexado) ANTES
        // de anexar o comprovante SNT (etapa anterior na timeline). Cada etapa
        // calculava sua propria conclusao isoladamente, entao "Resposta ao
        // solicitante" aparecia CONCLUIDA (verde) mesmo com "Comprovante SNT"
        // (etapa 5, antes dela) ainda pendente - timeline "fora de ordem".
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.DEFERIDO);
        // NAO anexa COMPROVANTE_SNT: etapa "Comprovante SNT" fica pendente/atual.
        p.setEmailEnviadoSolicitante(true);
        Anexo comprovanteEnvio = new Anexo();
        comprovanteEnvio.setTipo(TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE);
        p.addAnexo(comprovanteEnvio);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);

        EtapaFluxo snt = etapas.stream()
                .filter(e -> e.chave() == Chave.COMPROVANTE_SNT).findFirst().orElseThrow();
        assertThat(snt.estado()).isNotEqualTo(EstadoEtapa.CONCLUIDA);

        EtapaFluxo resposta = etapas.stream()
                .filter(e -> e.chave() == Chave.RESPOSTA_SOLICITANTE).findFirst().orElseThrow();
        assertThat(resposta.estado()).isNotEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(resposta.estado()).isEqualTo(EstadoEtapa.BLOQUEADA);
    }

    // ----------------------------------------------------------------
    // Fluxo feliz completo: cada etapa avanca PENDENTE -> ATUAL -> CONCLUIDA
    // em ordem, uma de cada vez, para o caso Deferido.
    // ----------------------------------------------------------------

    @Test
    void fluxoFelizCompletoDeferido() {
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.SOLICITADO);

        // Envio (etapa 1, com o Recebimento fundido nela desde 2026-08-05) ja
        // comeca ATUAL desde o primeiro momento (nao ha mais um passo manual
        // de "completar o recebimento" antes de liberar o Envio).
        List<EtapaFluxo> e0 = fluxo().montarEtapas(p);
        assertThat(estado(e0, Chave.ENVIO)).isEqualTo(EstadoEtapa.ATUAL);
        assertThat(estado(e0, Chave.RESPOSTAS)).isEqualTo(EstadoEtapa.BLOQUEADA);
        assertThat(estado(e0, Chave.DECISAO)).isEqualTo(EstadoEtapa.BLOQUEADA);

        // etapa 2 completa -> etapa 3 fica ATUAL
        registrarEnvioCompleto(p);
        p.setStatus(StatusProcesso.ENVIADO);
        List<EtapaFluxo> e2 = fluxo().montarEtapas(p);
        assertThat(estado(e2, Chave.ENVIO)).isEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(estado(e2, Chave.RESPOSTAS)).isEqualTo(EstadoEtapa.ATUAL);
        assertThat(estado(e2, Chave.DECISAO)).isEqualTo(EstadoEtapa.BLOQUEADA);

        // etapa 3 completa (maioria favoravel) -> Decisao fica ATUAL
        registrarMaioria(p, ResultadoParecer.FAVORAVEL);
        List<EtapaFluxo> e3 = fluxo().montarEtapas(p);
        assertThat(estado(e3, Chave.RESPOSTAS)).isEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(estado(e3, Chave.DECISAO)).isEqualTo(EstadoEtapa.ATUAL);

        // decisao tomada (Deferido) -> Comprovante SNT fica ATUAL, Resposta ao
        // solicitante ainda PENDENTE
        p.setStatus(StatusProcesso.DEFERIDO);
        List<EtapaFluxo> e4 = fluxo().montarEtapas(p);
        assertThat(estado(e4, Chave.DECISAO)).isEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(estado(e4, Chave.COMPROVANTE_SNT)).isEqualTo(EstadoEtapa.ATUAL);
        assertThat(estado(e4, Chave.RESPOSTA_SOLICITANTE)).isEqualTo(EstadoEtapa.BLOQUEADA);

        // comprovante SNT anexado -> Resposta ao solicitante fica ATUAL
        Anexo comprovanteSnt = new Anexo();
        comprovanteSnt.setTipo(TipoAnexo.COMPROVANTE_SNT);
        p.addAnexo(comprovanteSnt);
        List<EtapaFluxo> e5 = fluxo().montarEtapas(p);
        assertThat(estado(e5, Chave.COMPROVANTE_SNT)).isEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(estado(e5, Chave.RESPOSTA_SOLICITANTE)).isEqualTo(EstadoEtapa.ATUAL);

        // e-mail + comprovante de envio -> Resposta ao solicitante CONCLUIDA, e
        // e a UNICA etapa concluida ao final: todas as demais tambem devem
        // estar CONCLUIDA (fim de fila).
        p.setEmailEnviadoSolicitante(true);
        Anexo comprovanteEnvio = new Anexo();
        comprovanteEnvio.setTipo(TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE);
        p.addAnexo(comprovanteEnvio);
        List<EtapaFluxo> eFinal = fluxo().montarEtapas(p);
        assertThat(eFinal).allSatisfy(e -> assertThat(e.estado()).isEqualTo(EstadoEtapa.CONCLUIDA));
        assertThat(fluxo().resumoPendencia(p)).isEqualTo("Processo concluido.");
    }

    // ----------------------------------------------------------------
    // Fluxo feliz completo: caso Indeferido, com o oficio.
    // ----------------------------------------------------------------

    @Test
    void fluxoFelizCompletoIndeferido() {
        Processo p = processoComTresPareceres();
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.NAO_FAVORAVEL);
        p.setStatus(StatusProcesso.INDEFERIDO);

        // decisao concluida, oficio ainda pendente/atual, sem etapa Comprovante SNT
        List<EtapaFluxo> e0 = fluxo().montarEtapas(p);
        assertThat(estado(e0, Chave.DECISAO)).isEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(estado(e0, Chave.OFICIO)).isEqualTo(EstadoEtapa.ATUAL);
        assertThat(e0.stream().anyMatch(e -> e.chave() == Chave.COMPROVANTE_SNT)).isFalse();
        assertThat(estado(e0, Chave.RESPOSTA_SOLICITANTE)).isEqualTo(EstadoEtapa.BLOQUEADA);

        // oficio parcialmente preenchido (so o motivo) -> continua nao concluido
        p.setMotivoIndeferimento("Documentacao incompleta");
        List<EtapaFluxo> e1 = fluxo().montarEtapas(p);
        assertThat(estado(e1, Chave.OFICIO)).isEqualTo(EstadoEtapa.ATUAL);
        assertThat(estado(e1, Chave.RESPOSTA_SOLICITANTE)).isEqualTo(EstadoEtapa.BLOQUEADA);

        // oficio completo (motivo + anexo + data) -> libera Resposta ao solicitante
        Anexo oficio = new Anexo();
        oficio.setTipo(TipoAnexo.OFICIO_INDEFERIMENTO);
        p.addAnexo(oficio);
        p.setDataEmissaoOficio(LocalDate.now());
        List<EtapaFluxo> e2 = fluxo().montarEtapas(p);
        assertThat(estado(e2, Chave.OFICIO)).isEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(estado(e2, Chave.RESPOSTA_SOLICITANTE)).isEqualTo(EstadoEtapa.ATUAL);
    }

    // ----------------------------------------------------------------
    // Casos "fora de ordem": mesma familia do bug original, mas exercitando
    // o oficio de indeferimento em vez do comprovante SNT.
    // ----------------------------------------------------------------

    @Test
    void oficioIndeferimentoCompletoNaoAdiantaRespostaSeDecisaoAindaNaoConcluida() {
        // Oficio com todos os dados presentes, mas o processo ainda NAO esta
        // com status INDEFERIDO (decisao nao tomada) - simulando dados
        // remanescentes de um fluxo anterior/incompleto. A etapa Oficio nem
        // deveria aparecer (so aparece com status INDEFERIDO), e a Decisao
        // final continua nao concluida.
        Processo p = processoComTresPareceres();
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.NAO_FAVORAVEL);
        p.setStatus(StatusProcesso.ENVIADO); // decisao ainda nao tomada
        p.setMotivoIndeferimento("motivo antigo");
        Anexo oficio = new Anexo();
        oficio.setTipo(TipoAnexo.OFICIO_INDEFERIMENTO);
        p.addAnexo(oficio);
        p.setDataEmissaoOficio(LocalDate.now());

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        assertThat(etapas.stream().anyMatch(e -> e.chave() == Chave.OFICIO)).isFalse();
        assertThat(estado(etapas, Chave.DECISAO)).isEqualTo(EstadoEtapa.ATUAL);
    }

    @Test
    void respostaAoSolicitanteMarcadaAntesDoOficioNaoFicaVerdeIndeferido() {
        // Variante do bug original para o ramo Indeferido: e-mail/comprovante
        // ao solicitante ja registrados, mas o oficio de indeferimento (etapa
        // anterior) ainda esta incompleto.
        Processo p = processoComTresPareceres();
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.NAO_FAVORAVEL);
        p.setStatus(StatusProcesso.INDEFERIDO);
        // oficio incompleto: falta o anexo
        p.setMotivoIndeferimento("Documentacao incompleta");
        p.setDataEmissaoOficio(LocalDate.now());
        // mas a resposta ao solicitante ja foi registrada
        p.setEmailEnviadoSolicitante(true);
        Anexo comprovanteEnvio = new Anexo();
        comprovanteEnvio.setTipo(TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE);
        p.addAnexo(comprovanteEnvio);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        assertThat(estado(etapas, Chave.OFICIO)).isEqualTo(EstadoEtapa.ATUAL);
        assertThat(estado(etapas, Chave.RESPOSTA_SOLICITANTE)).isEqualTo(EstadoEtapa.BLOQUEADA);
    }

    // ----------------------------------------------------------------
    // SOLICITA_INFORMACAO pausando um fluxo em andamento e retomando.
    // ----------------------------------------------------------------

    @Test
    void solicitaInformacaoAntesDeQualquerVotoMantemInformacaoComplementarPendente() {
        // Nenhuma maioria formada ainda (so 1 dos 3 respondeu, pedindo info):
        // a etapa "Respostas dos medicos" (anterior na timeline) continua sem
        // concluir - entao "Informacao complementar" fica PENDENTE, nao ATUAL.
        // Isso e esperado (nao e o bug de "etapa fora de ordem"): a timeline so
        // acende a etapa de pausa quando o fluxo realmente "chegou" nela.
        Processo p = processoComTresPareceres();
        registrarEnvioCompleto(p);
        p.setStatus(StatusProcesso.ENVIADO);
        Parecer par0 = p.getPareceres().get(0);
        par0.setId(1L);
        par0.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        assertThat(estado(etapas, Chave.ENVIO)).isEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(estado(etapas, Chave.RESPOSTAS)).isNotEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(estado(etapas, Chave.INFO_COMPLEMENTAR)).isEqualTo(EstadoEtapa.BLOQUEADA);
        assertThat(estado(etapas, Chave.DECISAO)).isEqualTo(EstadoEtapa.BLOQUEADA);
    }

    @Test
    void solicitaInformacaoComMaioriaJaFormadaPausaDecisaoEDepoisRetoma() {
        // Maioria ja formada (2 favoraveis) mas o 3o medico pede informacao
        // complementar em vez de votar: atualizarStatusPorPareceres da
        // prioridade ao pedido de informacao (pausa sempre, mesmo com maioria).
        // Neste caso a etapa "Respostas dos medicos" ESTA concluida (maioria +
        // anexos de todos os 3 pareceres recebidos), entao "Informacao
        // complementar" fica ATUAL (a pausa esta "na frente" do fluxo).
        Processo p = processoComTresPareceres();
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.FAVORAVEL); // pareceres 0 e 1 favoraveis
        Parecer par2 = p.getPareceres().get(2);
        par2.setId(3L);
        par2.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        List<EtapaFluxo> pausado = fluxo().montarEtapas(p);
        assertThat(estado(pausado, Chave.RESPOSTAS)).isEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(estado(pausado, Chave.INFO_COMPLEMENTAR)).isEqualTo(EstadoEtapa.ATUAL);
        // Decisao continua bloqueada mesmo com maioria formada: a pausa vence.
        assertThat(estado(pausado, Chave.DECISAO)).isEqualTo(EstadoEtapa.BLOQUEADA);
        assertThat(fluxo().resumoPendencia(p)).startsWith("Informação complementar");
        // Bug real relatado em producao (processo 09/2026): o texto da etapa
        // "Respostas dos medicos" dizia "pronto para decidir" mesmo com a
        // decisao de fato bloqueada logo abaixo pela pausa - dava a impressao
        // de que o pedido de informacao do 3o avaliador seria ignorado.
        assertThat(detalhe(pausado, Chave.RESPOSTAS))
                .contains("BLOQUEADA")
                .doesNotContain("pronto para decidir");
        assertThat(detalhe(pausado, Chave.DECISAO))
                .contains("BLOQUEADA pela pausa");

        // Retoma: ProcessoService.retomarAposInformacao volta o status para
        // ENVIADO e limpa (reabre) apenas o parecer que pediu informacao.
        ProcessoService ps = new ProcessoService(processoRepository, membroRepository, new ProcessoValidator(),
                parecerRepository, solicitacaoOnlineRepository, emailTemplateService, emailSenderService,
                anexoStorageService, historicoParecerRepository);
        org.mockito.Mockito.when(processoRepository.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.of(p));
        org.mockito.Mockito.when(processoRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
        p.setId(1L);
        Processo retomado = ps.retomarAposInformacao(1L);

        assertThat(retomado.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        assertThat(par2.getResultado()).isNull();
        // os 2 votos favoraveis originais nao sao afetados pela retomada
        assertThat(p.getPareceres().get(0).getResultado()).isEqualTo(ResultadoParecer.FAVORAVEL);

        List<EtapaFluxo> apos = fluxo().montarEtapas(retomado);
        assertThat(apos.stream().anyMatch(e -> e.chave() == Chave.INFO_COMPLEMENTAR)).isFalse();
        // A maioria dos 2 favoraveis originais continua de pe: Respostas
        // permanece concluida e a Decisao fica liberada (ATUAL) imediatamente.
        assertThat(estado(apos, Chave.RESPOSTAS)).isEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(estado(apos, Chave.DECISAO)).isEqualTo(EstadoEtapa.ATUAL);
    }

    @Test
    void solicitaInformacaoComVotoFavoravelDoCoordenadorNaoFicaMarcadoComoBloqueado() {
        // Mesma pausa (3o medico pediu informacao), mas um dos 2 favoraveis e
        // o coordenador da CET-RS: a excecao documentada em CLAUDE.md defere
        // sozinho mesmo com o processo pausado (ProcessoValidator.
        // temVotoCoordenadorFavoravel). Aqui a decisao NAO esta de fato
        // bloqueada, entao o texto continua dizendo "pronto para decidir" -
        // diferente do cenario sem coordenador acima.
        Processo p = processoComTresPareceres();
        registrarEnvioCompleto(p);
        p.getPareceres().get(0).getMembro().setCoordenador(true);
        registrarMaioria(p, ResultadoParecer.FAVORAVEL); // pareceres 0 (coordenador) e 1 favoraveis
        // Snapshot do papel no momento do voto (ver Parecer.eraCoordenadorNoVoto).
        p.getPareceres().get(0).setEraCoordenadorNoVoto(true);
        Parecer par2 = p.getPareceres().get(2);
        par2.setId(3L);
        par2.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        assertThat(detalhe(etapas, Chave.RESPOSTAS))
                .contains("pronto para decidir")
                .doesNotContain("BLOQUEADA");
        assertThat(detalhe(etapas, Chave.DECISAO))
                .doesNotContain("BLOQUEADA pela pausa");
    }

    // ----------------------------------------------------------------
    // Wizard horizontal x timeline vertical: bug de dessincronia.
    // ----------------------------------------------------------------

    @Test
    void wizardBloqueiaDecisaoQuandoSolicitaInformacaoComMaioriaJaFormada() {
        // Mesmo cenario de
        // solicitaInformacaoComMaioriaJaFormadaPausaDecisaoEDepoisRetoma:
        // maioria ja formada (Respostas = CONCLUIDA), mas o 3o medico pediu
        // informacao complementar, pausando o fluxo. A timeline vertical
        // bloqueia "Decisao final" (PENDENTE) - o wizard horizontal precisa
        // concordar e manter o passo 3 BLOQUEADA, nao ATUAL.
        Processo p = processoComTresPareceres();
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.FAVORAVEL);
        Parecer par2 = p.getPareceres().get(2);
        par2.setId(3L);
        par2.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        assertThat(estado(etapas, Chave.DECISAO)).isEqualTo(EstadoEtapa.BLOQUEADA);

        List<PassoWizard> passos = fluxo().montarPassosWizard(p);
        PassoWizard passoDecisao = passos.stream()
                .filter(passo -> passo.numero() == 3).findFirst().orElseThrow();
        assertThat(passoDecisao.estado()).isEqualTo(EstadoEtapa.BLOQUEADA);
    }

    // ----------------------------------------------------------------
    // etapasConcluidas / progresso: mesma logica usada por
    // ProcessoDetalheController.detalhe() (conta estados == CONCLUIDA).
    // ----------------------------------------------------------------

    @Test
    void contagemDeEtapasConcluidasBateComEstadoReal() {
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.DEFERIDO);
        Anexo comprovanteSnt = new Anexo();
        comprovanteSnt.setTipo(TipoAnexo.COMPROVANTE_SNT);
        p.addAnexo(comprovanteSnt);
        // Resposta ao solicitante NAO registrada.

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        long concluidasEsperadas = etapas.stream()
                .filter(e -> e.estado() == EstadoEtapa.CONCLUIDA).count();

        // Envio, Respostas, Decisao, Comprovante SNT = 4 concluidas;
        // Resposta ao solicitante fica ATUAL (nao concluida). Total de etapas = 5
        // (sem a etapa de Informacao complementar, que so aparece em pausa - e
        // sem Recebimento, fundido em Envio desde 2026-08-05).
        assertThat(etapas).hasSize(5);
        assertThat(concluidasEsperadas).isEqualTo(4);
        assertThat(estado(etapas, Chave.RESPOSTA_SOLICITANTE)).isEqualTo(EstadoEtapa.ATUAL);
    }

    // ----------------------------------------------------------------
    // CANCELADO: a etapa "Resposta ao solicitante" nao pode travar o
    // progresso (corrigido em 2026-08-04). Processo cancelado nao gera
    // resposta formal por e-mail - o botao fica desabilitado na tela e
    // ProcessoService.finalizarResposta recusa explicitamente -, entao antes
    // essa etapa era impossivel de concluir e o progresso nunca chegava a
    // 100%.
    // ----------------------------------------------------------------

    @Test
    void canceladoConcluiRespostaAoSolicitanteSemExigirEmailEnviado() {
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.CANCELADO);
        assertThat(p.isEmailEnviadoSolicitante()).isFalse();

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);

        assertThat(estado(etapas, Chave.RESPOSTA_SOLICITANTE)).isEqualTo(EstadoEtapa.CONCLUIDA);
        assertThat(etapas.stream()
            .filter(e -> e.chave() == Chave.RESPOSTA_SOLICITANTE).findFirst().orElseThrow()
            .detalhe()).contains("Cancelamento nao exige envio de resposta formal");
    }

    @Test
    void canceladoChegaA100PorCentoDeProgresso() {
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.CANCELADO);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        long concluidas = etapas.stream()
            .filter(e -> e.estado() == EstadoEtapa.CONCLUIDA).count();

        // Nao ha etapa de oficio nem de comprovante SNT em processo cancelado:
        // sobram Envio, Respostas, Decisao e Resposta ao solicitante - todas
        // concluidas (Recebimento fundido em Envio desde 2026-08-05).
        assertThat(etapas).hasSize(4);
        assertThat(concluidas).isEqualTo(etapas.size());
    }

    @Test
    void canceladoTemOPassoDeFinalizacaoConcluidoNoWizard() {
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.CANCELADO);

        PassoWizard finalizacao = fluxo().montarPassosWizard(p).stream()
            .filter(passo -> passo.numero() == 4).findFirst().orElseThrow();

        assertThat(finalizacao.estado()).isEqualTo(EstadoEtapa.CONCLUIDA);
    }

    @Test
    void deferidoSemEmailContinuaComRespostaAoSolicitantePendente() {
        // Guarda de regressao da correcao acima: a excecao vale SO para
        // CANCELADO, nunca para Deferido/Indeferido (onde a resposta ao
        // solicitante e obrigatoria de verdade).
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.DEFERIDO);
        Anexo comprovanteSnt = new Anexo();
        comprovanteSnt.setTipo(TipoAnexo.COMPROVANTE_SNT);
        p.addAnexo(comprovanteSnt);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);

        assertThat(estado(etapas, Chave.RESPOSTA_SOLICITANTE)).isEqualTo(EstadoEtapa.ATUAL);
    }

    // ----------------------------------------------------------------
    // M2 (vistoria 2026-08-05): CANCELADO antes do envio/pareceres nao pode
    // mostrar uma pendencia acionavel impossivel de resolver (edicao ja
    // travada por edicaoBloqueada nesse status). Os testes de CANCELADO
    // acima so cobrem cancelamento a partir de processoProntoParaDecisao()
    // (etapas 1-3 ja completas), que mascara o bug: so a partir dali a
    // primeira etapa incompleta e "Resposta ao solicitante", ja coberta
    // pela excecao. Estes cobrem cancelamento bem mais cedo no fluxo.
    // ----------------------------------------------------------------

    @Test
    void canceladoAntesDoEnvioNaoTemPendenciaAberta() {
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.CANCELADO);
        // nenhum documento clinico, nenhum envio registrado

        assertThat(fluxo().pendenciaAberta(p)).isEmpty();
        assertThat(fluxo().resumoPendencia(p)).isEqualTo("Processo concluido.");
    }

    @Test
    void canceladoAntesDosPareceresNaoTemPendenciaAberta() {
        Processo p = processoComTresPareceres();
        registrarEnvioCompleto(p);
        p.setStatus(StatusProcesso.CANCELADO);
        // envio feito, mas nenhum parecer respondido ainda

        assertThat(fluxo().pendenciaAberta(p)).isEmpty();
        assertThat(fluxo().resumoPendencia(p)).isEqualTo("Processo concluido.");
    }

    @Test
    void resumoPendenciaApontaComprovanteSntQuandoDeferidoSemComprovante() {
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.DEFERIDO);
        assertThat(fluxo().resumoPendencia(p)).startsWith("Comprovante SNT");
    }

    @Test
    void resumoPendenciaApontaOficioQuandoIndeferidoSemOficio() {
        Processo p = processoComTresPareceres();
        registrarEnvioCompleto(p);
        registrarMaioria(p, ResultadoParecer.NAO_FAVORAVEL);
        p.setStatus(StatusProcesso.INDEFERIDO);
        assertThat(fluxo().resumoPendencia(p)).startsWith("Ofício de indeferimento");
    }

    @Test
    void resumoPendenciaApontaRespostaAoSolicitanteComoUltimaEtapa() {
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.DEFERIDO);
        Anexo comprovanteSnt = new Anexo();
        comprovanteSnt.setTipo(TipoAnexo.COMPROVANTE_SNT);
        p.addAnexo(comprovanteSnt);
        assertThat(fluxo().resumoPendencia(p)).startsWith("Resposta ao solicitante");
    }

    // ----------------------------------------------------------------
    // Envio (com o Recebimento fundido nele em 2026-08-05) e SEMPRE liberado
    // desde 2026-07-27 (todo processo nasce de uma SolicitacaoOnline
    // convertida pelo Portal do Solicitante) - nao ha mais "e-mail original"
    // a anexar nem confirmacao manual/capa gerada pelo operador antes dele;
    // o gating de Envio e independente de veioDoPortal(p) (que so serve hoje
    // para achar o link "Ver solicitacao original" na tela de detalhe - ver
    // ProcessoDetalheController.detalhe).
    // ----------------------------------------------------------------

    @Test
    void envioJaLiberadoMesmoSemNenhumAnexoEIndependenteDeVeioDoPortal() {
        Processo p = processoComTresPareceres();
        p.setId(10L);
        // nenhum anexo e veioDoPortal(p) false (stub default de fluxo()): mesmo assim
        // o Envio (passo 1) ja esta liberado de imediato.
        FluxoProcessoService fluxo = fluxo();
        EtapaFluxo primeiraEtapa = fluxo.montarEtapas(p).get(0);
        assertThat(primeiraEtapa.chave()).isEqualTo(Chave.ENVIO);
        assertThat(primeiraEtapa.estado()).isEqualTo(EstadoEtapa.ATUAL);

        FluxoProcessoService.GatingAbas gating = fluxo.calcularGating(p);
        assertThat(gating.liberadoEnvio()).isTrue();
    }

    @Test
    void veioDoPortalRetornaFalseParaProcessoSemId() {
        Processo p = processoComTresPareceres();
        // processo novo, ainda sem id persistido
        assertThat(fluxo().veioDoPortal(p)).isFalse();
    }

    // ----------------------------------------------------------------
    // F2 do relatorio de vistoria de brechas (2026-08-10) - Achado 3:
    // "Maioria formada"/"regra 2 de 3" em processo decidido por 1 voto so
    // (voto do coordenador). Os textos abaixo passam a consultar
    // ProcessoValidator.regraAplicada (fonte unica) em vez de assumir
    // maioria incondicionalmente.
    // ----------------------------------------------------------------

    @Test
    void processoJaDecididoPorMaioriaNaoDizMaisMaioriaFormadaNemProntoParaDecidir() {
        Processo p = processoProntoParaDecisao();
        p.setStatus(StatusProcesso.DEFERIDO);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        assertThat(detalhe(etapas, Chave.RESPOSTAS))
                .doesNotContain("Maioria formada")
                .doesNotContain("pronto para decidir")
                .contains("ja decidido")
                .contains("Deferido");
        assertThat(detalhe(etapas, Chave.DECISAO))
                .contains("Deferido")
                .contains("maioria simples");
    }

    @Test
    void processoDecididoPeloCoordenadorNaoCitaRegraDeMaioriaEmNenhumaEtapa() {
        // 1 voto so (do coordenador), processo ja Deferido - a etapa
        // "Respostas dos medicos" e a "Decisao final" nao podem mais dizer
        // "Maioria formada"/"regra 2 de 3 favoraveis": foi a excecao do
        // coordenador que decidiu, com 1 voto so.
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.DEFERIDO);
        registrarEnvioCompleto(p);
        p.getPareceres().get(0).getMembro().setCoordenador(true);
        p.getPareceres().get(0).setId(1L);
        p.getPareceres().get(0).setResultado(ResultadoParecer.FAVORAVEL);
        p.getPareceres().get(0).setEraCoordenadorNoVoto(true);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        assertThat(detalhe(etapas, Chave.RESPOSTAS))
                .doesNotContain("Maioria formada")
                .doesNotContain("regra: 2 de 3")
                .contains("Coordenador");
        assertThat(detalhe(etapas, Chave.DECISAO))
                .doesNotContain("regra 2 de 3")
                .contains("Coordenador");
    }

    @Test
    void decisaoFinalAposReaberturaComVotoUnicoDoCoordenadorNaoCitaRegraDeMaioria() {
        // Cenario do Achado 3 (segunda evidencia): processo reaberto volta
        // para ENVIADO (nao finalizado) com 1 unico voto do coordenador
        // registrado - a sugestao automatica nao pode dizer
        // "regra 2 de 3 favoraveis" com 1 voto so.
        Processo p = processoComTresPareceres();
        p.setStatus(StatusProcesso.ENVIADO);
        registrarEnvioCompleto(p);
        p.getPareceres().get(0).getMembro().setCoordenador(true);
        p.getPareceres().get(0).setId(1L);
        p.getPareceres().get(0).setResultado(ResultadoParecer.FAVORAVEL);
        p.getPareceres().get(0).setEraCoordenadorNoVoto(true);

        List<EtapaFluxo> etapas = fluxo().montarEtapas(p);
        assertThat(detalhe(etapas, Chave.DECISAO))
                .doesNotContain("regra 2 de 3 favoraveis")
                .contains("Coordenador da CET-RS");
    }

    private EstadoEtapa estado(List<EtapaFluxo> etapas, EtapaFluxo.Chave chave) {
        return etapas.stream()
                .filter(e -> e.chave() == chave)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Etapa nao encontrada: " + chave))
                .estado();
    }

    private String detalhe(List<EtapaFluxo> etapas, EtapaFluxo.Chave chave) {
        return etapas.stream()
                .filter(e -> e.chave() == chave)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Etapa nao encontrada: " + chave))
                .detalhe();
    }
}

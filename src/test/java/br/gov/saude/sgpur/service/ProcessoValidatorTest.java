package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Suite dedicada de ProcessoValidator - antes so era testado indiretamente
 * via ProcessoServiceTest. Cobre cada regra isolada, incluindo o bug real
 * corrigido em validarPausaDecisao (coordenador nao pode indeferir em pausa).
 */
class ProcessoValidatorTest {

    private final ProcessoValidator validator = new ProcessoValidator();

    private MembroUrgenciaRenal medico(boolean coordenador) {
        MembroUrgenciaRenal m = new MembroUrgenciaRenal("HCPA", "Medico", null);
        m.setCoordenador(coordenador);
        return m;
    }

    private Parecer parecer(ResultadoParecer resultado, boolean coordenador) {
        Parecer p = new Parecer(medico(coordenador));
        p.setResultado(resultado);
        // Snapshot do papel no momento do voto (Parecer.eraCoordenadorNoVoto) --
        // este helper simula um voto atual, entao o snapshot reflete o mesmo
        // papel do membro no momento em que o parecer foi criado.
        p.setEraCoordenadorNoVoto(coordenador);
        return p;
    }

    // ----- edicaoBloqueada -----

    @Test
    void edicaoBloqueadaTrueQuandoFinalizado() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        assertThat(validator.edicaoBloqueada(p)).isTrue();
    }

    @Test
    void edicaoBloqueadaFalseQuandoEmAndamento() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.ENVIADO);
        assertThat(validator.edicaoBloqueada(p)).isFalse();
    }

    // ----- contagens -----

    @Test
    void contagensDeVotos() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));

        assertThat(validator.contarFavoraveis(p)).isEqualTo(2);
        assertThat(validator.contarNaoFavoraveis(p)).isEqualTo(1);
        assertThat(validator.contarRespondidos(p)).isEqualTo(3);
    }

    @Test
    void naoRespondidoNaoContaEmNenhumaCategoria() {
        Processo p = new Processo();
        p.addParecer(parecer(null, false));
        assertThat(validator.contarRespondidos(p)).isZero();
        assertThat(validator.contarFavoraveis(p)).isZero();
    }

    // ----- coordenador -----

    @Test
    void temVotoCoordenadorFavoravelSoContaSeForRealmenteCoordenador() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        assertThat(validator.temVotoCoordenadorFavoravel(p)).isFalse();

        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.temVotoCoordenadorFavoravel(p)).isTrue();
    }

    @Test
    void temVotoCoordenadorFavoravelFalseSeCoordenadorVotouDesfavoravel() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, true));
        assertThat(validator.temVotoCoordenadorFavoravel(p)).isFalse();
    }

    @Test
    void deferidoPeloCoordenadorExigeStatusDeferidoEVotoCoordenador() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));

        p.setStatus(StatusProcesso.ENVIADO);
        assertThat(validator.deferidoPeloCoordenador(p)).isFalse();

        p.setStatus(StatusProcesso.DEFERIDO);
        assertThat(validator.deferidoPeloCoordenador(p)).isTrue();
    }

    @Test
    void favoraveisNecessariosParaDeferirCaiParaUmComCoordenador() {
        Processo p = new Processo();
        assertThat(validator.favoraveisNecessariosParaDeferir(p))
            .isEqualTo(ProcessoService.FAVORAVEIS_PARA_DEFERIR);

        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.favoraveisNecessariosParaDeferir(p)).isEqualTo(1);
    }

    // ----- sugerirDecisao -----

    @Test
    void sugerirDecisaoVazioSemMaioria() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        assertThat(validator.sugerirDecisao(p)).isEmpty();
    }

    @Test
    void sugerirDecisaoDeferidoComMaioriaSimples() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        assertThat(validator.sugerirDecisao(p)).contains(StatusProcesso.DEFERIDO);
    }

    @Test
    void sugerirDecisaoIndeferidoComMaioriaSimples() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        assertThat(validator.sugerirDecisao(p)).contains(StatusProcesso.INDEFERIDO);
    }

    @Test
    void sugerirDecisaoDeferidoComUmUnicoVotoDoCoordenador() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.sugerirDecisao(p)).contains(StatusProcesso.DEFERIDO);
    }

    @Test
    void sugerirDecisaoCoordenadorFavoravelVenceMesmoComDoisDesfavoraveis() {
        // O peso unico do coordenador prevalece mesmo diante de maioria contraria.
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.sugerirDecisao(p)).contains(StatusProcesso.DEFERIDO);
    }

    // ----- validarPausaDecisao (bug real corrigido aqui) -----

    @Test
    void validarPausaDecisaoBloqueiaDeferidoSemCoordenador() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        assertThat(validator.validarPausaDecisao(p, StatusProcesso.DEFERIDO)).isPresent();
    }

    @Test
    void validarPausaDecisaoLiberaDeferidoComCoordenadorFavoravel() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.validarPausaDecisao(p, StatusProcesso.DEFERIDO)).isEmpty();
    }

    @Test
    void validarPausaDecisaoBloqueiaIndeferidoMesmoComCoordenadorFavoravel() {
        // Bug real: o coordenador NAO tem peso especial para indeferir. Mesmo
        // com o coordenador favoravel registrado, Indeferido continua
        // bloqueado enquanto o processo estiver pausado.
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));

        assertThat(validator.validarPausaDecisao(p, StatusProcesso.INDEFERIDO)).isPresent();
    }

    @Test
    void validarPausaDecisaoNaoBloqueiaForaDePausa() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.ENVIADO);
        assertThat(validator.validarPausaDecisao(p, StatusProcesso.DEFERIDO)).isEmpty();
        assertThat(validator.validarPausaDecisao(p, StatusProcesso.INDEFERIDO)).isEmpty();
    }

    // ----- temPedidoInformacaoAtivo / achados C e D (RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA) -----

    @Test
    void temPedidoInformacaoAtivoTrueComParecerSolicitaInformacao() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.SOLICITA_INFORMACAO, false));
        assertThat(validator.temPedidoInformacaoAtivo(p)).isTrue();
    }

    @Test
    void temPedidoInformacaoAtivoFalseSemParecerNesseEstado() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        assertThat(validator.temPedidoInformacaoAtivo(p)).isFalse();
    }

    /**
     * ACHADO C: apos uma reabertura, o status derivado pode voltar para
     * ENVIADO mesmo com um parecer SOLICITA_INFORMACAO ainda ativo (antes da
     * correcao, {@code ProcessoService.reabrir} forcava ENVIADO
     * incondicionalmente). A trava precisa reconhecer o FATO (o parecer
     * ativo), nao so o status.
     */
    @Test
    void validarPausaDecisaoBloqueiaQuandoStatusDessincronizaDoParecerAtivo() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.ENVIADO);
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.SOLICITA_INFORMACAO, false));

        assertThat(validator.validarPausaDecisao(p, StatusProcesso.DEFERIDO)).isPresent();
        assertThat(validator.validarPausaDecisao(p, StatusProcesso.INDEFERIDO)).isPresent();
    }

    /**
     * Mesmo com o status dessincronizado, a excecao do coordenador continua
     * valendo (o voto Favoravel do coordenador defere sozinho mesmo com a
     * pausa ativa por FATO).
     */
    @Test
    void validarPausaDecisaoLiberaDeferidoDoCoordenadorMesmoComStatusDessincronizado() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.ENVIADO);
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        p.addParecer(parecer(ResultadoParecer.SOLICITA_INFORMACAO, false));

        assertThat(validator.validarPausaDecisao(p, StatusProcesso.DEFERIDO)).isEmpty();
        assertThat(validator.validarPausaDecisao(p, StatusProcesso.INDEFERIDO)).isPresent();
    }

    // ----- validarContagemVotos -----

    @Test
    void validarContagemVotosBloqueiaDeferidoSemMaioria() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        assertThat(validator.validarContagemVotos(p, StatusProcesso.DEFERIDO)).isPresent();
    }

    @Test
    void validarContagemVotosLiberaDeferidoComUmVotoDeCoordenador() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.validarContagemVotos(p, StatusProcesso.DEFERIDO)).isEmpty();
    }

    @Test
    void validarContagemVotosBloqueiaIndeferidoSemDoisDesfavoraveis() {
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        assertThat(validator.validarContagemVotos(p, StatusProcesso.INDEFERIDO)).isPresent();
    }

    @Test
    void validarContagemVotosIndeferidoIgnoraVotoDoCoordenador() {
        // O coordenador nao reduz a exigencia de Indeferido (sempre 2, mesmo
        // que ele tenha votado desfavoravel).
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, true));
        assertThat(validator.validarContagemVotos(p, StatusProcesso.INDEFERIDO)).isPresent();
    }

    @Test
    void validarContagemVotosBloqueiaIndeferidoQuandoCoordenadorVotouFavoravel() {
        // Regra: o voto Favoravel do coordenador CET-RS DEFERE sozinho, entao
        // Indeferir fica vedado mesmo que ja existam 2 desfavoraveis (o operador
        // nao pode sobrepor manualmente a prioridade do coordenador).
        Processo p = new Processo();
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));   // coordenador
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        p.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        assertThat(validator.validarContagemVotos(p, StatusProcesso.INDEFERIDO)).isPresent();
        // ... e o mesmo processo continua liberado para Deferir (peso do coordenador).
        assertThat(validator.validarContagemVotos(p, StatusProcesso.DEFERIDO)).isEmpty();
    }

    // ----- validarMotivoIndeferimento -----

    @Test
    void validarMotivoIndeferimentoExigidoSoParaIndeferido() {
        assertThat(validator.validarMotivoIndeferimento(StatusProcesso.INDEFERIDO, null)).isPresent();
        assertThat(validator.validarMotivoIndeferimento(StatusProcesso.INDEFERIDO, "  ")).isPresent();
        assertThat(validator.validarMotivoIndeferimento(StatusProcesso.INDEFERIDO, "motivo")).isEmpty();
        assertThat(validator.validarMotivoIndeferimento(StatusProcesso.DEFERIDO, null)).isEmpty();
    }

    // ----- validarDecisao (encadeamento completo) -----

    @Test
    void validarDecisaoRetornaPrimeiroErroNaOrdemPausaContagemMotivo() {
        // pausado E sem votos suficientes: deve reportar a pausa primeiro.
        Processo p = new Processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        assertThat(validator.validarDecisao(p, StatusProcesso.DEFERIDO, null))
            .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
            .contains("informacao complementar");
    }

    @Test
    void validarDecisaoVazioQuandoTudoOk() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.ENVIADO);
        Parecer p1 = parecer(ResultadoParecer.FAVORAVEL, false);
        Parecer p2 = parecer(ResultadoParecer.FAVORAVEL, false);
        p.addParecer(p1);
        p.addParecer(p2);

        assertThat(validator.validarDecisao(p, StatusProcesso.DEFERIDO, null)).isEmpty();
    }

    // ----- validarRespostaSolicitante -----

    @Test
    void validarRespostaSolicitanteExigeComprovanteSntSeDeferido() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        p.setSolicitanteEmail("test@test.com");
        assertThat(validator.validarRespostaSolicitante(p)).isPresent();

        Anexo comprovante = new Anexo();
        comprovante.setTipo(TipoAnexo.COMPROVANTE_SNT);
        p.addAnexo(comprovante);
        assertThat(validator.validarRespostaSolicitante(p)).isEmpty();
    }

    @Test
    void validarRespostaSolicitanteExigeOficioSeIndeferido() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.INDEFERIDO);
        p.setSolicitanteEmail("test@test.com");
        assertThat(validator.validarRespostaSolicitante(p)).isPresent();

        Anexo oficio = new Anexo();
        oficio.setTipo(TipoAnexo.OFICIO_INDEFERIMENTO);
        p.addAnexo(oficio);
        assertThat(validator.validarRespostaSolicitante(p)).isEmpty();
    }

    @Test
    void validarRespostaSolicitanteVazioForaDeStatusFinal() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.ENVIADO);
        p.setSolicitanteEmail("test@test.com");
        assertThat(validator.validarRespostaSolicitante(p)).isEmpty();
    }

    // ----- validarRegistroEnvio / trava de anonimizacao -----

    private Anexo anexoPdf(TipoAnexo tipo) {
        Anexo a = new Anexo();
        a.setTipo(tipo);
        a.setNomeArquivo("doc.pdf");
        a.setContentType("application/pdf");
        return a;
    }

    @Test
    void validarRegistroEnvioBloqueiaSemNenhumDocumento() {
        Processo p = new Processo();
        assertThat(validator.validarRegistroEnvio(p))
            .hasValueSatisfying(msg -> assertThat(msg).contains("Anexe ao menos um documento clinico"));
    }

    /**
     * TRAVA DE ANONIMIZACAO: documento do portal ainda pendente NAO satisfaz o
     * requisito de envio, e a mensagem tem que ser a especifica (o operador ve
     * um documento anexado na tela - "anexe um documento" o confundiria).
     */
    @Test
    void validarRegistroEnvioBloqueiaComApenasDocumentoPendenteDeAnonimizacao() {
        Processo p = new Processo();
        p.addAnexo(anexoPdf(TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO));

        assertThat(validator.temDocumentoPendenteAnonimizacao(p)).isTrue();
        assertThat(validator.validarRegistroEnvio(p))
            .hasValue(ProcessoValidator.MSG_PENDENTE_ANONIMIZACAO);
    }

    @Test
    void validarRegistroEnvioLiberaAposConfirmacaoDaAnonimizacao() {
        Processo p = new Processo();
        // Mesmo anexo, ja promovido pelo operador (confirmar-anonimizacao)
        p.addAnexo(anexoPdf(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR));

        assertThat(validator.temDocumentoPendenteAnonimizacao(p)).isFalse();
        assertThat(validator.validarRegistroEnvio(p)).isEmpty();
    }

    /**
     * Processo LEGADO (convertido antes da trava): o documento do portal foi
     * gravado com o tipo antigo e continua liberando o envio normalmente.
     */
    @Test
    void validarRegistroEnvioAceitaProcessoLegadoComTipoAntigo() {
        Processo p = new Processo();
        Anexo legado = anexoPdf(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        legado.setDescricao("Documento enviado pelo solicitante no Portal do Solicitante - NAO ANONIMIZADO");
        p.addAnexo(legado);

        assertThat(validator.validarRegistroEnvio(p)).isEmpty();
    }

    // ----- regraAplicada (F2 do relatorio de vistoria de brechas, 2026-08-10) -----
    //
    // Metodo de LEITURA - reusa temVotoCoordenadorFavoravel sem alterar
    // nenhum predicado existente. Cobre os 4 valores do vocabulario fechado
    // RegraDecisao.

    @Test
    void regraAplicadaNaoDecididoQuandoProcessoAindaEmTramitacao() {
        Processo enviado = new Processo();
        enviado.setStatus(StatusProcesso.ENVIADO);
        assertThat(validator.regraAplicada(enviado))
            .isEqualTo(br.gov.saude.sgpur.service.dto.RegraDecisao.NAO_DECIDIDO);

        Processo pausado = new Processo();
        pausado.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        assertThat(validator.regraAplicada(pausado))
            .isEqualTo(br.gov.saude.sgpur.service.dto.RegraDecisao.NAO_DECIDIDO);

        Processo semStatus = new Processo();
        assertThat(validator.regraAplicada(semStatus))
            .isEqualTo(br.gov.saude.sgpur.service.dto.RegraDecisao.NAO_DECIDIDO);
    }

    @Test
    void regraAplicadaCancelamentoQuandoStatusCancelado() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.CANCELADO);
        assertThat(validator.regraAplicada(p))
            .isEqualTo(br.gov.saude.sgpur.service.dto.RegraDecisao.CANCELAMENTO);
    }

    @Test
    void regraAplicadaMaioriaSimplesQuandoDeferidoOuIndeferidoSemCoordenador() {
        Processo deferido = new Processo();
        deferido.setStatus(StatusProcesso.DEFERIDO);
        deferido.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        deferido.addParecer(parecer(ResultadoParecer.FAVORAVEL, false));
        assertThat(validator.regraAplicada(deferido))
            .isEqualTo(br.gov.saude.sgpur.service.dto.RegraDecisao.MAIORIA_SIMPLES);

        Processo indeferido = new Processo();
        indeferido.setStatus(StatusProcesso.INDEFERIDO);
        indeferido.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        indeferido.addParecer(parecer(ResultadoParecer.NAO_FAVORAVEL, false));
        assertThat(validator.regraAplicada(indeferido))
            .isEqualTo(br.gov.saude.sgpur.service.dto.RegraDecisao.MAIORIA_SIMPLES);
    }

    @Test
    void regraAplicadaVotoCoordenadorQuandoDeferidoPeloVotoUnicoDoCoordenador() {
        Processo p = new Processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.regraAplicada(p))
            .isEqualTo(br.gov.saude.sgpur.service.dto.RegraDecisao.VOTO_COORDENADOR);
    }

    @Test
    void regraAplicadaIndeferidoNuncaEhVotoCoordenadorMesmoComParecerLegadoInconsistente() {
        // INDEFERIDO com coordenador favoravel nao e alcancavel pela regra de
        // negocio real (validarContagemVotos veda), mas o metodo de LEITURA
        // precisa continuar coerente mesmo diante de um dado legado
        // inconsistente: so DEFERIDO pode ser VOTO_COORDENADOR.
        Processo p = new Processo();
        p.setStatus(StatusProcesso.INDEFERIDO);
        p.addParecer(parecer(ResultadoParecer.FAVORAVEL, true));
        assertThat(validator.regraAplicada(p))
            .isEqualTo(br.gov.saude.sgpur.service.dto.RegraDecisao.MAIORIA_SIMPLES);
    }
}

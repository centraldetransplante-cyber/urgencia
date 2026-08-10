package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.OrigemParecer;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Testes do RelatorioService: monta o Relatorio Final (sumario + copia dos
 * anexos PDF + pagina informativa para nao-PDF), exercitando de quebra o
 * PdfRelatorioBuilder e o PdfCabecalhoStamper (pacote-privados, so
 * testaveis a partir daqui). FluxoProcessoService e ProcessoService sao
 * mockados porque so a MONTAGEM do PDF importa aqui (o que entra no
 * relatorio e testado nos servicos deles mesmos).
 *
 * <p>{@code gerarCapaProcesso} (capa isolada, usada pelo antigo endpoint
 * manual de Recebimento) foi removido em 2026-07-27 junto com o endpoint -
 * ver {@code ProcessoDetalheController}. Nao confundir com a CAPA do
 * Relatorio Final ({@code PdfRelatorioBuilder.gerarCapa}), reintroduzida em
 * 2026-08-07 e coberta pelo bloco de testes no fim desta classe.
 */
@ExtendWith(MockitoExtension.class)
class RelatorioServiceTest {

    @Mock
    private FluxoProcessoService fluxoService;
    @Mock
    private ProcessoService processoService;

    @TempDir
    Path tempDir;

    private RelatorioService novoService() {
        AnexoStorageService anexoStorage = new AnexoStorageService(null, tempDir.toString());
        return new RelatorioService(fluxoService, processoService, anexoStorage);
    }

    private Processo processoBase(StatusProcesso status) {
        Processo p = new Processo();
        p.setNumero("01/2026");
        p.setPacienteNome("Joao da Silva");
        p.setPacienteRgct("RGCT-1");
        p.setSolicitanteEquipe("Hospital X");
        p.setSolicitanteEmail("equipe@hospital.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 1, 1));
        p.setDataCadastro(LocalDateTime.of(2026, 1, 1, 10, 0));
        p.setStatus(status);
        MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Dr. Teste", null);
        membro.setId(1L);
        Parecer par = new Parecer(membro);
        par.setResultado(ResultadoParecer.FAVORAVEL);
        par.setDataResposta(LocalDate.of(2026, 1, 5));
        p.addParecer(par);
        return p;
    }

    @Test
    void gerarProduzPdfValidoSemAnexos() {
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        byte[] pdf = novoService().gerar(p);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void gerarIncluiIniciaisDoPacienteNoCabecalhoENaoONomeCompleto() throws Exception {
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        byte[] pdf = novoService().gerar(p);

        // Pagina 2, e nao 1: desde 2026-08-07 a pagina 1 e a CAPA, unica
        // pagina do documento que NAO recebe o carimbo institucional (ver
        // PdfRelatorioBuilder.gerarCapa). O carimbo comeca no sumario.
        String sumario = extrairTextoDaPagina(pdf, 2);

        // O cabecalho estampado (topo institucional, repetido em toda pagina
        // carimbada) usa so as iniciais - a capa e o corpo do relatorio
        // mostram o nome completo (documento interno de arquivamento, nao
        // enviado ao avaliador).
        assertThat(sumario).contains("Processo CET-RS 01/2026 - Paciente J.S.");
    }

    @Test
    void gerarAdicionaPaginaDeAvisoQuandoAnexoPdfNaoExisteNoDisco() throws Exception {
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        Anexo anexoFantasma = new Anexo();
        anexoFantasma.setProcesso(p);
        anexoFantasma.setTipo(TipoAnexo.DOCUMENTO_PACIENTE);
        anexoFantasma.setNomeArquivo("laudo.pdf");
        anexoFantasma.setContentType("application/pdf");
        anexoFantasma.setCaminhoArmazenado("01-2026 - Joao da Silva/laudo.pdf");
        anexoFantasma.setDataUpload(LocalDateTime.of(2026, 1, 2, 9, 0));
        p.addAnexo(anexoFantasma);

        byte[] pdf = novoService().gerar(p);

        PdfReader reader = new PdfReader(pdf);
        StringBuilder texto = new StringBuilder();
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            texto.append(new PdfTextExtractor(reader).getTextFromPage(i));
        }
        reader.close();

        assertThat(texto.toString()).contains("Anexo não encontrado");
    }

    @Test
    void gerarMergeiaConteudoRealDeAnexoPdfExistente() throws Exception {
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        Path pastaProcesso = tempDir.resolve("01-2026 - Joao da Silva");
        Files.createDirectories(pastaProcesso);
        Path arquivo = pastaProcesso.resolve("laudo.pdf");
        Files.write(arquivo, pdfMinimoComTexto("MARCA-TEXTO-UNICA"));

        Anexo anexo = new Anexo();
        anexo.setProcesso(p);
        anexo.setTipo(TipoAnexo.DOCUMENTO_PACIENTE);
        anexo.setNomeArquivo("laudo.pdf");
        anexo.setContentType("application/pdf");
        anexo.setCaminhoArmazenado("01-2026 - Joao da Silva/laudo.pdf");
        anexo.setDataUpload(LocalDateTime.of(2026, 1, 2, 9, 0));
        p.addAnexo(anexo);

        byte[] pdf = novoService().gerar(p);

        PdfReader reader = new PdfReader(pdf);
        StringBuilder texto = new StringBuilder();
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            texto.append(new PdfTextExtractor(reader).getTextFromPage(i));
        }
        reader.close();

        assertThat(texto.toString()).contains("MARCA-TEXTO-UNICA");
    }

    @Test
    void gerarAdicionaPaginaInformativaParaAnexoNaoPdf() throws Exception {
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        Anexo anexoImagem = new Anexo();
        anexoImagem.setProcesso(p);
        anexoImagem.setTipo(TipoAnexo.COMPROVANTE_SNT);
        anexoImagem.setNomeArquivo("comprovante.png");
        anexoImagem.setContentType("image/png");
        anexoImagem.setCaminhoArmazenado("01-2026 - Joao da Silva/comprovante.png");
        anexoImagem.setDataUpload(LocalDateTime.of(2026, 1, 3, 9, 0));
        p.addAnexo(anexoImagem);

        byte[] pdf = novoService().gerar(p);

        PdfReader reader = new PdfReader(pdf);
        StringBuilder texto = new StringBuilder();
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            texto.append(new PdfTextExtractor(reader).getTextFromPage(i));
        }
        reader.close();

        assertThat(texto.toString())
            .contains("Anexo (formato não-PDF)")
            .contains("comprovante.png");
    }

    /** Extrai o texto de todas as paginas do PDF, na ordem, concatenado. */
    private static String extrairTexto(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        StringBuilder texto = new StringBuilder();
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            texto.append(new PdfTextExtractor(reader).getTextFromPage(i));
        }
        reader.close();
        return texto.toString();
    }

    /** Extrai o texto de UMA pagina do PDF (base 1). */
    private static String extrairTextoDaPagina(byte[] pdf, int pagina) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        String texto = new PdfTextExtractor(reader).getTextFromPage(pagina);
        reader.close();
        return texto;
    }

    // -----------------------------------------------------------------------
    // Capa (folha de rosto dedicada, reintroduzida em 2026-08-07 a pedido do
    // dono do produto - o R6 do relatorio V2 a tinha eliminado). Os testes
    // abaixo travam os criterios que justificaram aquela remocao, para a capa
    // nova nao recair neles: nao duplicar o sumario, nao repetir o brasao do
    // carimbo e nao quebrar o tamanho A4 corrigido pelo R5.
    // -----------------------------------------------------------------------

    @Test
    void gerarComecaPelaCapaComOsDadosDeIdentificacaoDoProcesso() throws Exception {
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        String capa = extrairTextoDaPagina(novoService().gerar(p), 1);

        assertThat(capa)
            .contains("RELATÓRIO FINAL")
            .contains("Central de Transplantes do Estado do Rio Grande do Sul")
            .contains("01/2026")
            .contains("Joao da Silva")
            .contains("DEFERIDO")
            .contains("Emitido em");
    }

    @Test
    void capaNaoRecebeOCarimboInstitucionalNemNumeroDePagina() throws Exception {
        // Um dos motivos concretos da remocao da capa antiga (R6/§7.7) era
        // ter DOIS brasoes na mesma dupla de paginas: o da propria capa e o
        // do carimbo do PdfCabecalhoStamper, que carimbava tambem a capa.
        // Aqui isso e estrutural: o carimbo comeca na pagina seguinte.
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        byte[] pdf = novoService().gerar(p);

        assertThat(extrairTextoDaPagina(pdf, 1))
            .doesNotContain("Paciente J.S.")
            .doesNotContain("Página 1 de");
        assertThat(extrairTextoDaPagina(pdf, 2))
            .contains("Paciente J.S.")
            .contains("Página 2 de");
    }

    @Test
    void capaNaoRepeteAsTabelasDoSumario() throws Exception {
        // Achado 6.6 do relatorio original: a capa antiga reimprimia uma
        // tabela de dados inteira MAIS a tabela de avaliadores que o sumario,
        // logo depois, repetia por completo. A capa nova mostra 4 dados.
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        byte[] pdf = novoService().gerar(p);
        String capa = extrairTextoDaPagina(pdf, 1);

        assertThat(capa)
            .doesNotContain("Dr. Teste")           // tabela de avaliadores
            .doesNotContain("Equipe solicitante")  // tabela de dados
            .doesNotContain("RGCT")
            .doesNotContain("equipe@hospital.com");
        // ...e o sumario continua trazendo tudo isso.
        assertThat(extrairTextoDaPagina(pdf, 2))
            .contains("Dr. Teste")
            .contains("Equipe solicitante")
            .contains("RGCT-1");
    }

    @Test
    void documentoTemUmUnicoCarimboDeEmissaoEEleFicaNaCapa() throws Exception {
        // Regra A12 do relatorio V2 preservada com a capa de volta: a data de
        // emissao existe UMA vez no documento (saiu da linha de subtitulo do
        // sumario quando a capa passou a traze-la), para as duas paginas nao
        // exibirem horarios diferentes entre si.
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        byte[] pdf = novoService().gerar(p);

        assertThat(extrairTexto(pdf).split("Emitido em", -1)).hasSize(2);
        assertThat(extrairTextoDaPagina(pdf, 1)).contains("Emitido em");
    }

    @Test
    void capaAnunciaRelatorioParcialQuandoOProcessoAindaNaoFoiDecidido() throws Exception {
        // Mesma correcao ja feita na secao "3." do sumario (B4+A7): a capa
        // nunca pode apresentar um status de tramitacao como se fosse o
        // desfecho ("RESULTADO: ENVIADO").
        Processo p = processoBase(StatusProcesso.ENVIADO);
        p.getPareceres().get(0).setResultado(null);
        p.getPareceres().get(0).setDataResposta(null);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(0L);

        String capa = extrairTextoDaPagina(novoService().gerar(p), 1);

        assertThat(capa)
            .contains("RELATÓRIO PARCIAL")
            .contains("SITUAÇÃO")
            .contains("Em andamento")
            .doesNotContain("RELATÓRIO FINAL")
            .doesNotContain("RESULTADO")
            .doesNotContain("ENVIADO");
    }

    @Test
    void capaMostraOResultadoDoIndeferimento() throws Exception {
        Processo p = processoBase(StatusProcesso.INDEFERIDO);
        p.getPareceres().get(0).setResultado(ResultadoParecer.NAO_FAVORAVEL);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(0L);

        assertThat(extrairTextoDaPagina(novoService().gerar(p), 1))
            .contains("RELATÓRIO FINAL")
            .contains("RESULTADO")
            .contains("INDEFERIDO");
    }

    @Test
    void capaMostraOResultadoDoCancelamento() throws Exception {
        // CANCELADO tambem e status final: o documento continua se chamando
        // RELATORIO FINAL e o desfecho aparece (em cinza, sem cor semantica
        // de deferimento/indeferimento).
        Processo p = processoBase(StatusProcesso.CANCELADO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(0L);

        assertThat(extrairTextoDaPagina(novoService().gerar(p), 1))
            .contains("RELATÓRIO FINAL")
            .contains("CANCELADO");
    }

    @Test
    void todasAsPaginasGeradasPeloSistemaContinuamEmA4InclusiveACapa() throws Exception {
        // R5 do relatorio V2: o Relatorio Final nunca tinha sido A4 de
        // verdade (595x897pt). A capa e a UNICA pagina que nao passa pela
        // expansao de 55pt do stamper, entao precisa nascer em A4 cheio - se
        // alguem trocar isso por TAMANHO_PAGINA_SISTEMA, o documento passa a
        // ter paginas de dois tamanhos e este teste falha.
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        PdfReader reader = new PdfReader(novoService().gerar(p));
        try {
            assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                assertThat(reader.getPageSize(i).getWidth())
                    .as("largura da pagina " + i)
                    .isCloseTo(com.lowagie.text.PageSize.A4.getWidth(), within(0.5f));
                assertThat(reader.getPageSize(i).getHeight())
                    .as("altura da pagina " + i)
                    .isCloseTo(com.lowagie.text.PageSize.A4.getHeight(), within(0.5f));
            }
        } finally {
            reader.close();
        }
    }

    // -----------------------------------------------------------------------
    // Frente 1 - correcoes de conteudo (docs/RELATORIO-REFORMULACAO-RELATORIO-FINAL-PDF-2026-08.md)
    // -----------------------------------------------------------------------

    @Test
    void gerarNaoMostraStatusDeTramitacaoComoSeFosseADecisaoQuandoProcessoAindaNaoFoiDecidido() throws Exception {
        // Processo ENVIADO, ainda sem nenhum parecer respondido: a secao "3.
        // Decisao final" nao pode anunciar "Resultado: ENVIADO" (em destaque,
        // CAIXA ALTA) como se ENVIADO fosse uma decisao - a mesma
        // contradicao que a capa do mesmo documento ja evita corretamente.
        Processo p = processoBase(StatusProcesso.ENVIADO);
        p.getPareceres().get(0).setResultado(null);
        p.getPareceres().get(0).setDataResposta(null);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(0L);

        String texto = extrairTexto(novoService().gerar(p));

        // linhaDestaque() sempre imprime o valor em CAIXA ALTA - "ENVIADO" so
        // pode aparecer assim se o bug antigo (status impresso como decisao,
        // sem checar isFinalizado()) tivesse voltado.
        assertThat(texto).doesNotContain("ENVIADO");
        assertThat(texto).contains("Em andamento");
    }

    @Test
    void gerarExplicaAExcecaoDoCoordenadorEmVezDaFraseGenericaDaRegra() throws Exception {
        // Deferido pelo voto isolado do Coordenador da CET-RS (1 favoravel
        // basta - ProcessoValidator.favoraveisNecessariosParaDeferir). O
        // texto fixo "Favoraveis: 1 (regra: 2 de 3 defere o processo)" ao
        // lado de "DEFERIDO" sugeria que a propria regra citada foi violada.
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        Parecer parecerDoCoordenador = p.getPareceres().get(0);
        MembroUrgenciaRenal coordenador = parecerDoCoordenador.getMembro();
        coordenador.setCoordenador(true);
        coordenador.setNome("Dra. Coordenadora");
        // Snapshot do papel no momento do voto: desde a F1 do relatorio de
        // brechas de decisao (2026-08-10) o NOME impresso vem de
        // ProcessoValidator.parecerDoCoordenador (que le
        // Parecer.eraCoordenadorNoVoto), nao mais do cargo AO VIVO do membro
        // -- por isso este fixture precisa fornecer o parecer, e nao so
        // marcar o membro como coordenador.
        parecerDoCoordenador.setEraCoordenadorNoVoto(true);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);
        when(processoService.deferidoPeloCoordenador(any())).thenReturn(true);
        when(processoService.parecerDoCoordenador(any())).thenReturn(Optional.of(parecerDoCoordenador));

        String texto = extrairTexto(novoService().gerar(p));

        assertThat(texto).contains("Coordenador da CET-RS");
        assertThat(texto).contains("Dra. Coordenadora");
        assertThat(texto).contains("exceção regimental");
        assertThat(texto).doesNotContain("regra: 2 de 3 defere o processo");
    }

    @Test
    void gerarMantemAFraseDaRegraNoDeferimentoPorMaioriaNormalSemAExcecaoDoCoordenador() throws Exception {
        // Caminho comum (2 de 3 favoraveis, sem coordenador envolvido) NAO
        // pode regredir: o texto "regra: 2 de 3" continua correto aqui.
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(2L);
        when(processoService.deferidoPeloCoordenador(any())).thenReturn(false);

        String texto = extrairTexto(novoService().gerar(p));

        assertThat(texto).contains("Favoráveis: 2 (regra: 2 de 3 defere o processo)");
        assertThat(texto).doesNotContain("Coordenador da CET-RS");
    }

    @Test
    void gerarMostraRotuloProprioParaParecerImpedidoEmVezDePendente() throws Exception {
        // Impedido (avaliador e o proprio solicitante do processo) e um
        // estado diferente de "sem voto ainda" - nao pode compartilhar o
        // mesmo rotulo "Pendente".
        Processo p = processoBase(StatusProcesso.ENVIADO);
        p.getPareceres().get(0).setResultado(null);
        p.getPareceres().get(0).setDataResposta(null);
        p.getPareceres().get(0).setImpedido(true);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(0L);

        String texto = extrairTexto(novoService().gerar(p));

        assertThat(texto).contains("Impedido");
        assertThat(texto).contains("conflito de interesse");
        assertThat(texto).doesNotContain("Pendente");
    }

    @Test
    void gerarIncluiATrilhaDeAuditoriaDoVotoDoAvaliador() throws Exception {
        // votadoPor/dataHoraVoto/origem substituiram formalmente o antigo
        // anexo comprobatorio do parecer (2026-07-27) - o documento de
        // arquivo precisa carregar essa prova, nao so a dataResposta.
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        Parecer par = p.getPareceres().get(0);
        par.setDataHoraVoto(LocalDateTime.of(2026, 1, 5, 14, 30));
        par.setVotadoPor("avaliador1");
        par.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        String texto = extrairTexto(novoService().gerar(p));

        assertThat(texto).contains("avaliador1");
        assertThat(texto).contains("05/01/2026 14:30");
        assertThat(texto).contains(OrigemParecer.AVALIADOR_SISTEMA.getDescricao());
    }

    @Test
    void gerarIncluiDataDeEnvioAoSntQuandoDeferido() throws Exception {
        // dataEnvioSnt existe em Processo desde 2026-08-04 e e o
        // identificador de protocolo do desfecho DEFERIDO - nao aparecia em
        // lugar nenhum do relatorio. Desde a secao "3." ficar condicional ao
        // status (B4+A7 do relatorio V2), esta linha so aparece em DEFERIDO.
        Processo p = processoBase(StatusProcesso.DEFERIDO);
        p.setDataEnvioSnt(LocalDate.of(2026, 1, 10));
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(1L);

        String texto = extrairTexto(novoService().gerar(p));

        assertThat(texto).contains("Data de envio ao SNT");
        assertThat(texto).contains("10/01/2026");
        assertThat(texto).doesNotContain("Número do ofício");
    }

    @Test
    void gerarIncluiNumeroDoOficioQuandoIndeferido() throws Exception {
        // Espelho do teste acima para o lado INDEFERIDO: numeroOficio so faz
        // sentido nesse status (DecisaoFinalService.atribuirNumeroOficioSeNecessario
        // so atribui para INDEFERIDO) e a secao "3." so mostra essa linha
        // quando aplicavel.
        Processo p = processoBase(StatusProcesso.INDEFERIDO);
        p.getPareceres().get(0).setResultado(ResultadoParecer.NAO_FAVORAVEL);
        p.setNumeroOficio("0142/2026");
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(0L);

        String texto = extrairTexto(novoService().gerar(p));

        assertThat(texto).contains("Número do ofício");
        assertThat(texto).contains("0142/2026");
        assertThat(texto).doesNotContain("Data de envio ao SNT");
    }

    @Test
    void gerarSecaoDecisaoViraSituacaoAtualQuandoProcessoNaoFoiDecidido() throws Exception {
        // B4+A7 do relatorio V2: titulo "3. Decisao final" prometia um
        // desfecho que ainda nao existe - "3. Situacao atual" corrige a
        // promessa, e as linhas exclusivas de DEFERIDO/INDEFERIDO
        // desaparecem em vez de aparecerem todas como "-".
        Processo p = processoBase(StatusProcesso.ENVIADO);
        p.getPareceres().get(0).setResultado(null);
        p.getPareceres().get(0).setDataResposta(null);
        when(fluxoService.montarEtapas(any())).thenReturn(List.of());
        when(processoService.contarFavoraveis(any())).thenReturn(0L);

        String texto = extrairTexto(novoService().gerar(p));

        assertThat(texto).contains("3. Situação atual");
        assertThat(texto).doesNotContain("3. Decisão final");
        assertThat(texto).doesNotContain("Data de envio ao SNT");
        assertThat(texto).doesNotContain("Número do ofício");
        // A capa (pagina 1) tambem nao anuncia desfecho nenhum neste caso -
        // ver capaAnunciaRelatorioParcialQuandoOProcessoAindaNaoFoiDecidido.
    }

    /** PDF minimo valido contendo o texto informado, para simular um anexo real no disco. */
    private static byte[] pdfMinimoComTexto(String texto) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document doc = new com.lowagie.text.Document();
        com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new com.lowagie.text.Paragraph(texto));
        doc.close();
        return out.toByteArray();
    }
}

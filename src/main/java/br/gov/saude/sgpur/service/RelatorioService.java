package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.HistoricoParecer;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.service.dto.EstadoEtapa;
import br.gov.saude.sgpur.service.dto.EtapaFluxo;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decide o conteudo do Relatorio Final do Processo de Urgencia Renal -
 * documento oficial para arquivamento, impressao e auditoria. A construcao
 * visual/binaria do PDF (tabelas, fontes, capa, merge de paginas) fica em
 * {@link PdfRelatorioBuilder}.
 *
 * O relatorio final eh composto por:
 *   1. Capa (folha de rosto dedicada, reintroduzida em 2026-08-07 - ver
 *      {@link PdfRelatorioBuilder#gerarCapa});
 *   2. Sumario executivo (dados do processo, pareceres, decisao, andamento
 *      e relacao de anexos);
 *   3. Copia integral de todos os documentos anexados ao processo (PDFs),
 *      inseridos como paginas apos o sumario;
 *   4. Pagina informativa para anexos nao-PDF;
 *   5. Pagina de fecho institucional, ao final de tudo (ver A11 do relatorio
 *      V2 - antes ficava presa ao fim do sumario, ANTES dos anexos).
 * Todas as paginas recebem cabecalho padrao e numeracao, EXCETO a capa (ver
 * {@code PdfCabecalhoStamper.estampar(byte[], String, String, int)}).
 *
 * <p><b>Acentuacao (R2 do relatorio V2):</b> os textos fixos deste servico e
 * de {@link PdfRelatorioBuilder} sao escritos com acentuacao correta -
 * "ficam de fora dessa regra {@code ResultadoParecer.getDescricao()} e
 * {@code TipoAnexo.getDescricao()} EM SI (nao devem ser acentuados, ver
 * CLAUDE.md e RELATORIO-UI-OPERADOR-SISTEMA-2026-08.md §10), que sao
 * traduzidos localmente por {@link PdfRelatorioBuilder#descricaoResultado}/
 * {@link PdfRelatorioBuilder#descricaoTipoAnexo} so para o texto impresso
 * neste documento.</p>
 */
@Service
public class RelatorioService {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final Color AZUL = PdfRelatorioBuilder.AZUL;
    private static final Color CINZA = PdfRelatorioBuilder.CINZA;

    private final FluxoProcessoService fluxoService;
    private final ProcessoService processoService;
    private final PdfRelatorioBuilder pdfBuilder;

    public RelatorioService(FluxoProcessoService fluxoService,
                            ProcessoService processoService,
                            AnexoStorageService anexoStorage) {
        this.fluxoService = fluxoService;
        this.processoService = processoService;
        this.pdfBuilder = new PdfRelatorioBuilder(anexoStorage);
    }

    /**
     * Gera o Relatorio Final completo: sumario + copia de todos os anexos +
     * cabecalho e numeracao em todas as paginas.
     */
    @Transactional(readOnly = true)
    public byte[] gerar(Processo p) {
        try {
            // 0. Capa (folha de rosto dedicada, reintroduzida em 2026-08-07 a
            // pedido do dono do produto - ver PdfRelatorioBuilder.gerarCapa
            // para o desenho e para por que ele nao repete os problemas da
            // capa antiga, removida pelo R6 do relatorio V2).
            byte[] capa = pdfBuilder.gerarCapa(dadosCapa(p));

            // 1. Gera o sumario executivo (dados + pareceres + decisao + anexos)
            byte[] summary = gerarSummary(p);

            // 2. Coleta os PDFs anexados (exceto RELATORIO_FINAL — evitar ciclo/duplicacao)
            List<Anexo> pdfs = p.getAnexos().stream()
                .filter(a -> a.getTipo() != TipoAnexo.RELATORIO_FINAL)
                .filter(a -> a.getContentType() != null
                    && a.getContentType().toLowerCase().contains("pdf"))
                .sorted(Comparator.comparing(Anexo::getDataUpload))
                .toList();

            // 3. Coleta anexos nao-PDF (para pagina informativa)
            List<Anexo> naoPdf = p.getAnexos().stream()
                .filter(a -> a.getTipo() != TipoAnexo.RELATORIO_FINAL)
                .filter(a -> a.getContentType() == null
                    || !a.getContentType().toLowerCase().contains("pdf"))
                .sorted(Comparator.comparing(Anexo::getDataUpload))
                .toList();

            // 4. Monta o PDF final com todas as paginas (o fecho institucional
            // vai por ultimo, DEPOIS de todos os anexos - ver A11).
            String fecho = "Documento gerado automaticamente pelo " + PdfCabecalhoStamper.NOME_SISTEMA + ".";
            byte[] merged = pdfBuilder.mergeComAnexos(capa, summary, pdfs, naoPdf, fecho);

            // 5. Adiciona cabecalho e numeracao em todas as paginas (mesmo
            // padrao institucional do Relatorio Anual, via PdfCabecalhoStamper)
            // - EXCETO na(s) pagina(s) de capa, que ja tem o brasao e o nome
            // do orgao em tamanho grande no proprio corpo (carimba-la
            // repetiria o brasao na mesma pagina). A contagem de paginas da
            // capa e lida do PDF gerado em vez de assumida como 1: se o
            // desenho da capa um dia crescer, o carimbo continua comecando no
            // sumario, sem carimbar meia capa.
            int paginasCapa;
            try (com.lowagie.text.pdf.PdfReader leitorCapa = new com.lowagie.text.pdf.PdfReader(capa)) {
                paginasCapa = leitorCapa.getNumberOfPages();
            }
            String iniciais = Iniciais.de(p.getPacienteNome());
            return PdfCabecalhoStamper.estampar(merged,
                PdfCabecalhoStamper.NOME_INSTITUICAO + " - "
                    + (p.isPreemptivo() ? "INSERÇÃO EM LISTA DE ESPERA RENAL" : "URGÊNCIA RENAL"),
                "Processo CET-RS " + p.getNumero() + " - Paciente " + iniciais,
                paginasCapa + 1);

        } catch (Exception e) {
            throw new IllegalStateException("Falha ao gerar o relatorio PDF completo", e);
        }
    }

    // -----------------------------------------------------------------------
    // Capa
    // -----------------------------------------------------------------------

    /**
     * Monta os (poucos) dados que a capa exibe. Deliberadamente NAO repete a
     * tabela de dados da secao "1." nem a tabela de avaliadores da secao "2."
     * - repetir o sumario inteiro na folha de rosto foi o principal motivo de
     * a capa antiga ter sido removida pelo R6 do relatorio V2 (achado 6.6).
     *
     * <p>Enquanto o processo nao foi decidido, a capa nunca anuncia o status
     * de tramitacao como se fosse um desfecho ("Resultado: ENVIADO"): o titulo
     * vira RELATORIO PARCIAL e o campo passa a se chamar "Situação", com o
     * texto neutro "Em andamento" - mesma correcao ja aplicada a secao "3." do
     * sumario (B4+A7 do relatorio V2).
     */
    private PdfRelatorioBuilder.DadosCapa dadosCapa(Processo p) {
        boolean finalizado = p.getStatus().isFinalizado();

        String situacao;
        Color corSituacao;
        if (finalizado) {
            situacao = p.getStatus().getDescricao().toUpperCase();
            corSituacao = switch (p.getStatus()) {
                case DEFERIDO -> PdfRelatorioBuilder.VERDE_ESCURO;
                case INDEFERIDO -> PdfRelatorioBuilder.VERMELHO;
                default -> CINZA;
            };
        } else {
            situacao = "Em andamento";
            corSituacao = CINZA;
        }

        return new PdfRelatorioBuilder.DadosCapa(
            finalizado ? "RELATÓRIO FINAL" : "RELATÓRIO PARCIAL",
            finalizado
                ? RotuloProcesso.nomeLongo(p)
                : RotuloProcesso.nomeLongo(p) + " (ainda não decidido)",
            PdfRelatorioBuilder.nvl(p.getNumero()),
            PdfRelatorioBuilder.nvl(p.getPacienteNome()),
            finalizado ? "Resultado" : "Situação",
            situacao,
            corSituacao,
            java.time.LocalDateTime.now().format(DATA_HORA));
    }

    // -----------------------------------------------------------------------
    // Sumario executivo
    // -----------------------------------------------------------------------

    private byte[] gerarSummary(Processo p) throws DocumentException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = pdfBuilder.abrirDocumentoA4(out);

        Font fTitulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, AZUL);
        Font fSub = FontFactory.getFont(FontFactory.HELVETICA, 9, CINZA);
        // R4 do relatorio V2: titulo de secao tipografico (nao mais faixa
        // AZUL chapada com texto branco) - a cor do texto passa a ser o
        // proprio AZUL institucional, ver PdfRelatorioBuilder.secao().
        Font fSecao = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, AZUL);

        // O R6 do relatorio V2 (§7.7) tinha eliminado a capa separada e feito
        // ESTA pagina virar a folha de rosto; em 2026-08-07 o dono do produto
        // pediu a capa de volta (ver PdfRelatorioBuilder.gerarCapa). Este
        // cabecalho compacto continua aqui de proposito: o sumario e a
        // primeira pagina do CORPO do documento, e identificar o orgao no
        // corpo (nao so no carimbo do topo) e o que o Oficio de Indeferimento
        // - a regua de qualidade do sistema - tambem faz. Ele nao repete dado
        // nenhum do processo, so orgao e secretaria.
        pdfBuilder.cabecalhoInstitucionalCompacto(doc);

        // Decisao 3 do relatorio V2 (§7.3): quando o processo AINDA NAO foi
        // decidido, o titulo avisa "RELATORIO PARCIAL" em vez de prometer um
        // desfecho que nao existe - sem bloquear a rota (GET /processos/{id}/
        // relatorio continua liberada em qualquer status; o operador tem uso
        // legitimo de imprimir o andamento parcial). Simetrico a secao "3."
        // virar "Situação atual" no mesmo caso (B4+A7, ja feito no R1b).
        String rotuloDocumento = p.getStatus().isFinalizado()
            ? "RELATÓRIO FINAL - " + RotuloProcesso.tituloPdfCaixaAlta(p)
            : "RELATÓRIO PARCIAL - " + RotuloProcesso.tituloPdfCaixaAlta(p) + " (processo ainda não decidido)";
        Paragraph titulo = new Paragraph(rotuloDocumento, fTitulo);
        titulo.setAlignment(Element.ALIGN_CENTER);
        doc.add(titulo);

        // A12 do relatorio V2 continua valendo com a capa de volta: o
        // documento tem UM UNICO carimbo de emissao (data+hora), e desde
        // 2026-08-07 ele mora na CAPA - por isso saiu daqui, em vez de as duas
        // paginas trazerem a mesma informacao (eventualmente com segundos de
        // diferenca entre si, ja que cada uma chamaria LocalDateTime.now()).
        Paragraph sub = new Paragraph(
            p.identificacao() + "  |  Situação: " + p.getStatus().getDescricao(), fSub);
        sub.setAlignment(Element.ALIGN_CENTER);
        sub.setSpacingAfter(14);
        doc.add(sub);

        pdfBuilder.secao(doc, fSecao, "1. Dados da solicitação");
        PdfPTable t1 = pdfBuilder.tabelaDados();
        pdfBuilder.linha(t1, "Número do processo", p.getNumero());
        pdfBuilder.linha(t1, "Paciente (receptor)", p.getPacienteNome());
        pdfBuilder.linha(t1, "RGCT / SNT", PdfRelatorioBuilder.nvl(p.getPacienteRgct()));
        pdfBuilder.linha(t1, "Data de nascimento",
            p.getPacienteDataNascimento() != null ? p.getPacienteDataNascimento().format(DATA) : "-");
        pdfBuilder.linha(t1, "CPF",
            p.getPacienteCpf() != null ? CpfUtil.formatar(p.getPacienteCpf()) : "-");
        pdfBuilder.linha(t1, "Sexo",
            p.getPacienteSexo() != null ? p.getPacienteSexo().getDescricao() : "-");
        pdfBuilder.linha(t1, "Nome da mãe", PdfRelatorioBuilder.nvl(p.getPacienteNomeMae()));
        pdfBuilder.linha(t1, "Equipe solicitante", p.getSolicitanteEquipe());
        pdfBuilder.linha(t1, "E-mail do solicitante", PdfRelatorioBuilder.nvl(p.getSolicitanteEmail()));
        pdfBuilder.linha(t1, RotuloProcesso.rotuloDataClinica(p),
            p.getDataSituacaoEspecial() != null ? p.getDataSituacaoEspecial().format(DATA) : "-");
        pdfBuilder.linha(t1, "Data de cadastro",
            p.getDataCadastro() != null ? p.getDataCadastro().format(DATA_HORA) : "-");
        pdfBuilder.linha(t1, "Observações", PdfRelatorioBuilder.nvl(p.getObservacoes()));
        // P9 do relatorio V2 (§7.8, extensao do achado 6.7 original): evita a
        // secao "1." ficar orfa no fim de uma pagina com a tabela inteira
        // (curta, sempre 8 linhas fixas) pulando pra proxima - "tentar", nao
        // "forcar": OpenPDF ainda quebra a tabela se ela nao couber em
        // NENHUMA pagina inteira.
        t1.setKeepTogether(true);
        doc.add(t1);

        pdfBuilder.secao(doc, fSecao, "2. Pareceres dos médicos (Urgência Renal)");
        PdfPTable t2 = new PdfPTable(new float[]{3, 2, 2});
        t2.setWidthPercentage(100);
        t2.setSpacingBefore(4);
        pdfBuilder.cabecalho(t2, "Médico", "Parecer", "Data da resposta");
        for (Parecer par : p.getPareceres()) {
            pdfBuilder.celula(t2, par.getMembro().getRotulo(), Element.ALIGN_LEFT, false);
            // Impedido (conflito de interesse - avaliador e o proprio
            // solicitante do processo) e um estado diferente de "sem voto
            // ainda" e nao pode compartilhar o mesmo rotulo: perder essa
            // distincao no documento de arquivo escondia justamente o fato
            // que demonstra que o conflito foi identificado e tratado.
            String textoParecer;
            if (par.isImpedido()) {
                textoParecer = "Impedido - conflito de interesse (solicitante do processo)";
            } else if (par.getResultado() != null) {
                textoParecer = PdfRelatorioBuilder.descricaoResultado(par.getResultado());
            } else {
                // Achado 4 do relatorio de vistoria de brechas (2026-08-10):
                // "Dispensado pela maioria" so faz sentido quando de fato
                // houve maioria - na excecao do coordenador (1 voto so) e no
                // cancelamento, o rotulo generico induzia o leitor a achar
                // que a regra padrao foi usada. Mesma fonte unica de
                // paragrafoRegraDecisao (abaixo), sem duplicar o predicado.
                textoParecer = p.getStatus().isFinalizado()
                    ? processoService.regraAplicada(p).getRotuloDispensado()
                    : "Pendente";
            }
            pdfBuilder.celula(t2, textoParecer, Element.ALIGN_LEFT, false);
            pdfBuilder.celula(t2, par.getDataResposta() != null ? par.getDataResposta().format(DATA) : "-",
                Element.ALIGN_LEFT, false);

            // A5 (resto): "Enviado em" (dataEnvio) fica visivel mesmo antes
            // do voto - e o prazo de resposta do avaliador que o documento
            // de arquivo precisa deixar legivel. "Registrado por" (a trilha
            // de auditoria do voto autenticado, que desde 2026-07-27
            // SUBSTITUI o antigo anexo comprobatorio) so aparece apos o voto.
            StringBuilder linhaInfo = new StringBuilder();
            if (par.getDataEnvio() != null) {
                linhaInfo.append("Enviado em: ").append(par.getDataEnvio().format(DATA));
            }
            if (par.getResultado() != null && par.getDataHoraVoto() != null) {
                String origemTexto = par.getOrigem() != null ? par.getOrigem().getDescricao() : "-";
                if (linhaInfo.length() > 0) {
                    linhaInfo.append("   |   ");
                }
                linhaInfo.append("Registrado por: ").append(PdfRelatorioBuilder.nvl(par.getVotadoPor()))
                    .append(" em ").append(par.getDataHoraVoto().format(DATA_HORA))
                    .append(" (").append(origemTexto).append(")");
            }
            if (linhaInfo.length() > 0) {
                PdfPCell cAud = new PdfPCell(new Phrase(linhaInfo.toString(),
                    FontFactory.getFont(FontFactory.HELVETICA, 8, CINZA)));
                cAud.setColspan(3);
                cAud.setPadding(4);
                cAud.setBorderColor(PdfRelatorioBuilder.CINZA_BORDA);
                t2.addCell(cAud);
            }
            if (par.getJustificativa() != null && !par.getJustificativa().isBlank()) {
                // 9pt preto (era 8pt cinza-claro): a justificativa clinica e
                // o conteudo mais relevante da tabela para auditoria futura,
                // nao devia ser a fonte mais apagada e menor do documento.
                // Mantido o italico so para distinguir visualmente de dado
                // estruturado (medico/parecer/data), sem depender de cor.
                PdfPCell cj = new PdfPCell(new Phrase(
                    "Justificativa: " + par.getJustificativa(),
                    FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, Color.BLACK)));
                cj.setColspan(3);
                cj.setPadding(4);
                cj.setBorderColor(PdfRelatorioBuilder.CINZA_BORDA);
                t2.addCell(cj);
            }
        }
        doc.add(t2);
        // B3 do relatorio V2: a frase que resume a regra de decisao era fixa
        // em "Favoraveis: N (regra: 2 de 3 defere o processo)" mesmo quando o
        // processo foi INDEFERIDO (cita a regra do deferimento ao lado da
        // decisao oposta) ou ainda esta em andamento (enuncia uma regra de
        // deferimento sem nenhuma decisao tomada). O PR #45 ja tinha
        // corrigido o caso do coordenador (voto isolado dele basta - ver
        // ProcessoValidator.favoraveisNecessariosParaDeferir); aqui os demais
        // casos ficam simetricos: DEFERIDO cita a regra de favoraveis,
        // INDEFERIDO cita a regra de desfavoraveis (DESFAVORAVEIS_PARA_INDEFERIR),
        // CANCELADO nao imprime frase de regra (nenhuma foi aplicada) e
        // processo em andamento mostra os dois contadores sem afirmar qual
        // decisao eles produzem.
        Paragraph fav = paragrafoRegraDecisao(p);
        if (fav != null) {
            fav.setSpacingBefore(4);
            doc.add(fav);
        }

        // F4 do relatorio de vistoria de brechas (2026-08-10) - Achado 7:
        // pareceres ARQUIVADOS (pedido de informacao complementar sobreposto
        // por uma retomada da analise) - preserva no documento oficial a
        // justificativa clinica original, que o reset de
        // ProcessoService.retomarAposInformacao apagava sem deixar rastro
        // fora da auditoria (ADMIN-only, texto livre). So aparece quando ha
        // ao menos 1 registro (nao polui o caso comum, sem nenhuma pausa).
        List<HistoricoParecer> historico = processoService.historicoParecer(p.getId());
        if (!historico.isEmpty()) {
            Font fHistTitulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, CINZA);
            Font fHistTexto = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
            Paragraph tituloHist = new Paragraph("Pareceres sobrepostos (histórico):", fHistTitulo);
            tituloHist.setSpacingBefore(6);
            doc.add(tituloHist);
            for (HistoricoParecer h : historico) {
                StringBuilder sb = new StringBuilder(h.getMembro().getRotulo())
                    .append(" pediu informação complementar");
                if (h.getDataHoraVoto() != null) {
                    sb.append(" em ").append(h.getDataHoraVoto().format(DATA_HORA));
                }
                sb.append(" - análise retomada em ").append(h.getArquivadoEm().format(DATA_HORA)).append('.');
                if (h.getJustificativa() != null && !h.getJustificativa().isBlank()) {
                    sb.append(" Justificativa original: ").append(h.getJustificativa());
                }
                Paragraph linhaHist = new Paragraph(sb.toString(), fHistTexto);
                linhaHist.setSpacingBefore(2);
                doc.add(linhaHist);
            }
        }

        // B4+A7 do relatorio V2 (tratados como item unico): a secao "3.
        // Decisao final" era uma lista FIXA de 8 linhas impressa
        // incondicionalmente - metade delas garantidamente "-" conforme o
        // status (numero do oficio/datas do oficio so fazem sentido em
        // INDEFERIDO; data de envio ao SNT so em DEFERIDO). Agora o titulo
        // muda para "3. Situação atual" enquanto o processo nao foi decidido
        // (rotula corretamente a promessa do titulo - ver Decisao 3 do
        // relatorio V2, que rotula "RELATORIO PARCIAL" no cabecalho da rota
        // sem bloquea-la) e cada bloco de linhas so aparece se for aplicavel
        // ao status atual.
        boolean finalizado = p.getStatus().isFinalizado();
        pdfBuilder.secao(doc, fSecao, finalizado ? "3. Decisão final" : "3. Situação atual");
        PdfPTable t3 = pdfBuilder.tabelaDados();
        if (finalizado) {
            // Resultado com destaque equivalente ao da capa (negrito, fonte
            // maior, CAIXA ALTA + cor semantica) - e o dado mais importante
            // do documento. A cor e bonus: "DEFERIDO"/"INDEFERIDO" em caixa
            // alta e negrito ja carrega a informacao mesmo impresso em P&B.
            Color corResultado = switch (p.getStatus()) {
                case DEFERIDO -> PdfRelatorioBuilder.VERDE_ESCURO;
                case INDEFERIDO -> PdfRelatorioBuilder.VERMELHO;
                default -> CINZA;
            };
            pdfBuilder.linhaDestaque(t3, "Resultado", p.getStatus().getDescricao(), corResultado);
            pdfBuilder.linha(t3, "Data da decisão",
                p.getDataDecisao() != null ? p.getDataDecisao().format(DATA_HORA) : "-");
        } else {
            // Processo ainda em tramitacao (nao decidido): antes esta secao
            // imprimia p.getStatus().getDescricao() incondicionalmente, entao
            // um processo ENVIADO chegava a anunciar "Resultado: ENVIADO" em
            // destaque de 13pt, como se ENVIADO fosse a decisao. Sem
            // linhaDestaque (sem CAIXA ALTA/13pt): a informacao nao e uma
            // decisao e nao deve ter o mesmo peso visual de uma.
            pdfBuilder.linha(t3, "Resultado", "Em andamento (processo ainda não decidido)");
        }
        // Achado 8 do relatorio de vistoria de brechas (2026-08-10): antes,
        // nenhuma tela nem documento indicava que o processo ja tinha sido
        // reaberto - o Relatorio Final apresentava a decisao atual como se
        // fosse necessariamente a UNICA ja tomada. So aparece quando ha ao
        // menos 1 reabertura (nao polui o documento no caso comum).
        if (p.getReaberturasOuZero() > 0) {
            pdfBuilder.linha(t3, "Reaberturas",
                p.getReaberturasOuZero() + " vez(es) - a decisão atual pode não ser a primeira.");
        }
        switch (p.getStatus()) {
            case INDEFERIDO -> {
                pdfBuilder.linha(t3, "Motivo do indeferimento", PdfRelatorioBuilder.nvl(p.getMotivoIndeferimento()));
                pdfBuilder.linha(t3, "Número do ofício", PdfRelatorioBuilder.nvl(p.getNumeroOficio()));
                pdfBuilder.linha(t3, "Data de emissão do ofício",
                    p.getDataEmissaoOficio() != null ? p.getDataEmissaoOficio().format(DATA) : "-");
                pdfBuilder.linha(t3, "Data de envio do ofício",
                    p.getDataEnvioOficio() != null ? p.getDataEnvioOficio().format(DATA) : "-");
                pdfBuilder.linha(t3, "E-mail enviado ao solicitante", p.isEmailEnviadoSolicitante() ? "Sim" : "Não");
            }
            case DEFERIDO -> {
                pdfBuilder.linha(t3, "Data de envio ao SNT",
                    p.getDataEnvioSnt() != null ? p.getDataEnvioSnt().format(DATA) : "-");
                pdfBuilder.linha(t3, "E-mail enviado ao solicitante", p.isEmailEnviadoSolicitante() ? "Sim" : "Não");
            }
            default -> {
                // CANCELADO nao exige oficio/comprovante SNT/e-mail formal
                // (ver CLAUDE.md, "Processo Cancelado nao trava mais o
                // progresso"); SOLICITADO/ENVIADO/SOLICITA_INFORMACAO ainda
                // nao tem nenhum desses dados.
            }
        }
        // P9 (mesmo raciocinio de t1): "3." tem no maximo 6 linhas.
        t3.setKeepTogether(true);
        doc.add(t3);

        pdfBuilder.secao(doc, fSecao, "4. Andamento do processo");
        // Coluna "Situacao" ganhou mais espaco (10% -> 18%) para caber o
        // texto por extenso ("Concluida"/"Atual"/"Bloqueada") - R4 do
        // relatorio V2: "[X]"/"[>]"/"[ ]" eram compactos mas exigiam decifrar
        // um codigo visual num documento de arquivo/auditoria.
        PdfPTable t4 = new PdfPTable(new float[]{1.8f, 3.7f, 4.5f});
        t4.setWidthPercentage(100);
        t4.setSpacingBefore(4);
        pdfBuilder.cabecalho(t4, "Situação", "Etapa", "Detalhe");
        for (EtapaFluxo e : fluxoService.montarEtapas(p)) {
            String situacao = switch (e.estado()) {
                case CONCLUIDA -> "Concluída";
                case ATUAL -> "Atual";
                case BLOQUEADA -> "Bloqueada";
            };
            pdfBuilder.celula(t4, situacao, Element.ALIGN_LEFT, e.estado() != EstadoEtapa.BLOQUEADA);
            pdfBuilder.celula(t4, e.titulo(), Element.ALIGN_LEFT, false);
            pdfBuilder.celula(t4, e.detalhe(), Element.ALIGN_LEFT, false);
        }
        doc.add(t4);

        pdfBuilder.secao(doc, fSecao, "5. Relação de anexos");
        if (p.getAnexos().isEmpty()) {
            doc.add(new Paragraph("Nenhum anexo registrado.",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, CINZA)));
        } else {
            // Tipo ganhou mais espaco (33% -> 42%) e Data ganhou mais espaco
            // tambem (16% -> 19%, precisa de mais fôlego a 10pt do que a
            // 9pt): a descricao do tipo costuma ser bem mais longa que o
            // nome do arquivo ou a data, e "dd/mm/aaaa" quebrava em 2 linhas
            // com a coluna estreita demais a 10pt (achado do protótipo do
            // relatorio V2, §6 - risco previsto e confirmado na pratica).
            PdfPTable t5 = new PdfPTable(new float[]{4.2f, 3.3f, 2f});
            t5.setWidthPercentage(100);
            t5.setSpacingBefore(4);
            pdfBuilder.cabecalho(t5, "Tipo", "Arquivo", "Data");
            for (Anexo a : p.getAnexos()) {
                pdfBuilder.celula(t5, PdfRelatorioBuilder.descricaoTipoAnexo(a.getTipo()), Element.ALIGN_LEFT, false);
                pdfBuilder.celula(t5, a.getNomeArquivo(), Element.ALIGN_LEFT, false);
                pdfBuilder.celula(t5, a.getDataUpload() != null ? a.getDataUpload().format(DATA) : "-",
                    Element.ALIGN_LEFT, false);
            }
            doc.add(t5);
        }

        // A11: o fecho institucional NAO fica mais aqui - foi movido para a
        // ultima pagina do documento inteiro (apos todos os anexos
        // importados), ver PdfRelatorioBuilder.mergeComAnexos/gerar() acima.

        doc.close();
        return out.toByteArray();
    }

    /**
     * Frase que resume, em uma linha, a regra de decisao aplicada (ou a
     * situacao atual, se o processo ainda nao foi decidido). Ver B3 no
     * relatorio V2 - antes era um texto fixo (so tratava o caso do
     * coordenador), que citava a regra de DEFERIMENTO mesmo em processos
     * indeferidos ou ainda em andamento.
     *
     * @return {@code null} quando nenhuma frase de regra se aplica
     *         (CANCELADO - nenhuma regra de maioria foi usada)
     */
    private Paragraph paragrafoRegraDecisao(Processo p) {
        Font fReg = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, CINZA);

        // A frase da regra e fixa em "2 de 3" na maioria dos casos, mas isso
        // contradiz a decisao registrada quando o processo foi deferido pela
        // excecao regimental do Coordenador da CET-RS (voto favoravel dele
        // basta, ver ProcessoValidator.favoraveisNecessariosParaDeferir):
        // sem este tratamento, o documento oficial de arquivo chegava a
        // registrar "Favoraveis: 1 (regra: 2 de 3 defere o processo)" ao
        // lado de "DEFERIDO", como se a propria regra citada tivesse sido
        // violada.
        if (processoService.deferidoPeloCoordenador(p)) {
            // O NOME impresso vem do MESMO parecer que a regra usou para
            // deferir: ProcessoValidator.parecerDoCoordenador le o snapshot
            // Parecer.eraCoordenadorNoVoto (o papel no INSTANTE do voto).
            // Antes, esta busca filtrava par.getMembro().isCoordenador() --
            // o cargo AO VIVO -- e o documento oficial creditava a excecao
            // regimental ao medico ERRADO quando o cargo de coordenador
            // mudava de mao depois do voto, ou perdia o nome de quem
            // decidiu (Achado 1 do RELATORIO-VISTORIA-BRECHAS-DECISAO-
            // 2026-08-10.md). A REGRA de decisao ja usava o snapshot e nao
            // foi tocada: so a busca do nome estava fora dessa protecao.
            String nomeCoordenador = processoService.parecerDoCoordenador(p)
                .map(Parecer::getMembro)
                .map(m -> m.getNome())
                // Fallback generico: parecer legado sem snapshot (votado
                // antes de 2026-08-07) ou membro nulo. Comportamento
                // conservador preservado, identico ao anterior.
                .orElse("Coordenador da CET-RS");
            return new Paragraph(
                "Deferido pelo voto do Coordenador da CET-RS (" + nomeCoordenador
                    + "), que defere isoladamente, conforme exceção regimental que dispensa "
                    + "a maioria de " + ProcessoService.FAVORAVEIS_PARA_DEFERIR + " de "
                    + ProcessoService.AVALIADORES_POR_PROCESSO + ". Favoráveis: "
                    + processoService.contarFavoraveis(p) + ".",
                fReg);
        }

        long favoraveis = processoService.contarFavoraveis(p);
        long desfavoraveis = p.getPareceres().stream()
            .filter(par -> par.getResultado() == ResultadoParecer.NAO_FAVORAVEL)
            .count();

        return switch (p.getStatus()) {
            case DEFERIDO -> new Paragraph(
                "Favoráveis: " + favoraveis + " (regra: "
                    + ProcessoService.FAVORAVEIS_PARA_DEFERIR + " de "
                    + ProcessoService.AVALIADORES_POR_PROCESSO + " defere o processo).",
                fReg);
            case INDEFERIDO -> new Paragraph(
                "Desfavoráveis: " + desfavoraveis + " (regra: "
                    + ProcessoService.DESFAVORAVEIS_PARA_INDEFERIR + " de "
                    + ProcessoService.AVALIADORES_POR_PROCESSO + " indefere o processo).",
                fReg);
            case CANCELADO -> null;
            default -> new Paragraph(
                "Favoráveis: " + favoraveis + "   |   Desfavoráveis: " + desfavoraveis
                    + " (decisão exige " + ProcessoService.FAVORAVEIS_PARA_DEFERIR + " de "
                    + ProcessoService.AVALIADORES_POR_PROCESSO
                    + " votos no mesmo sentido para deferir ou indeferir, ou o voto favorável "
                    + "isolado do Coordenador da CET-RS para deferir).",
                fReg);
        };
    }
}

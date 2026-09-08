package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.service.RelatorioAnualService;
import br.gov.saude.sgpur.service.RelatorioAnualService.DadosRelatorioAnual;
import br.gov.saude.sgpur.service.RelatorioAvaliadorService;
import br.gov.saude.sgpur.service.RelatorioAvaliadorService.DadosRelatorioAvaliador;
import br.gov.saude.sgpur.service.RelatorioAvaliadorService.LinhaDetalheAvaliador;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Relatorios gerenciais. Atualmente expoe o Relatorio Geral por ano (PDF),
 * com um seletor que lista apenas os anos que possuem processos.
 */
@Controller
@RequestMapping("/relatorios")
public class RelatorioController {

    private final ProcessoRepository processoRepository;
    private final MembroUrgenciaRenalService membroService;
    private final RelatorioAnualService relatorioAnualService;
    private final RelatorioAvaliadorService relatorioAvaliadorService;

    public RelatorioController(ProcessoRepository processoRepository,
                               MembroUrgenciaRenalService membroService,
                               RelatorioAnualService relatorioAnualService,
                               RelatorioAvaliadorService relatorioAvaliadorService) {
        this.processoRepository = processoRepository;
        this.membroService = membroService;
        this.relatorioAnualService = relatorioAnualService;
        this.relatorioAvaliadorService = relatorioAvaliadorService;
    }

    @GetMapping("/anual")
    public String anual(Model model) {
        List<Integer> anos = processoRepository.findAnosComProcessos();
        model.addAttribute("anos", anos);
        return "relatorios/anual";
    }

    @GetMapping("/anual/{ano}/pdf")
    public ResponseEntity<byte[]> anualPdf(@PathVariable int ano) {
        try {
            List<Processo> processos = agruparPorTipoDepoisSequencial(
                processoRepository.findByAnoComPareceres(ano));
            byte[] pdf = relatorioAnualService.gerar(ano, processos);
            String nome = "relatorio-" + ano + ".pdf";
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nome + "\"")
                .body(pdf);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Erro ao gerar relatorio: " + e.getMessage());
        }
    }

    @GetMapping("/anual/{ano}/csv")
    public ResponseEntity<byte[]> anualCsv(@PathVariable int ano) {
        List<Processo> processos = agruparPorTipoDepoisSequencial(processoRepository.findByAnoComPareceres(ano));
        DadosRelatorioAnual dados = relatorioAnualService.calcularDados(ano, processos);

        StringBuilder csv = new StringBuilder();
        csv.append('﻿'); // BOM UTF-8 - sem ele o Excel no Windows abre acento corrompido.

        csv.append("Resumo do ano ").append(ano).append("\n");
        csv.append("Indicador;Valor\n");
        var r = dados.resumo();
        var tempoAno = dados.tempoAno();
        linhaCsv(csv, "Total de processos", String.valueOf(r.total()));
        linhaCsv(csv, "Solicitados (aguardando envio)", String.valueOf(r.solicitado()));
        linhaCsv(csv, "Em andamento (enviados / em análise)", String.valueOf(r.emAndamento()));
        linhaCsv(csv, "Solicita informação", String.valueOf(r.solicitaInfo()));
        linhaCsv(csv, "Deferidos", String.valueOf(r.deferido()));
        linhaCsv(csv, "Indeferidos", String.valueOf(r.indeferido()));
        linhaCsv(csv, "Cancelados", String.valueOf(r.cancelado()));
        linhaCsv(csv, "Preemptivos (inserção em lista de espera)",
            r.preemptivos() + (r.preemptivos() == 0 ? "" : " (dos quais " + r.preemptivosDeferidos() + " deferido(s))"));
        linhaCsv(csv, "% de deferimento (sobre os decididos)", r.percentDeferimento());
        linhaCsv(csv, "Tempo médio de resposta dos avaliadores",
            br.gov.saude.sgpur.service.TempoRespostaService.formatarDias(tempoAno.mediaGeralDias()));
        linhaCsv(csv, "Pareceres fora do prazo (meta " + tempoAno.prazoDias() + " dias corridos)",
            tempoAno.foraDoPrazo() + " de " + tempoAno.totalAvaliados());

        csv.append("\nTempo de resposta por avaliador\n");
        csv.append("Avaliador;Respondidos;Tempo médio;Fora do prazo\n");
        for (var l : dados.temposPorAvaliador()) {
            csv.append(csvCampo(l.avaliador())).append(';')
                .append(l.respondidos()).append(';')
                .append(csvCampo(l.tempoMedioFormatado())).append(';')
                .append(l.foraDoPrazo()).append('\n');
        }

        csv.append("\nLista de processos do ano ").append(ano).append('\n');
        csv.append("Nº/Ano;Paciente;RGCT;Tipo;Status;Médico 1;Médico 2;Médico 3;Cadastro;Decisão\n");
        for (var l : dados.processos()) {
            csv.append(csvCampo(l.numero())).append(';')
                .append(csvCampo(l.paciente())).append(';')
                .append(csvCampo(l.rgct())).append(';')
                .append(csvCampo(l.tipo())).append(';')
                .append(csvCampo(l.status())).append(';')
                .append(csvCampo(l.medico1())).append(';')
                .append(csvCampo(l.medico2())).append(';')
                .append(csvCampo(l.medico3())).append(';')
                .append(csvCampo(l.cadastro())).append(';')
                .append(csvCampo(l.decisao())).append('\n');
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        String nome = "relatorio-" + ano + ".csv";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nome + "\"")
            .body(bytes);
    }

    @GetMapping("/anual/{ano}/html")
    public String anualHtml(@PathVariable int ano, Model model) {
        List<Processo> processos = agruparPorTipoDepoisSequencial(processoRepository.findByAnoComPareceres(ano));
        DadosRelatorioAnual dados = relatorioAnualService.calcularDados(ano, processos);
        model.addAttribute("dados", dados);
        model.addAttribute("ano", ano);
        model.addAttribute("emitidoEm", java.time.LocalDateTime.now());
        return "relatorios/anual-visualizacao";
    }

    /**
     * Tela seletora do Relatorio Individual do Avaliador: escolhe um ano
     * (dentre os que possuem processos) e um medico avaliador (ativos).
     */
    @GetMapping("/avaliador")
    public String avaliador(Model model) {
        List<Integer> anos = processoRepository.findAnosComProcessos();
        List<MembroUrgenciaRenal> membros = membroService.listarAtivos();
        model.addAttribute("anos", anos);
        model.addAttribute("membros", membros);
        return "relatorios/avaliador";
    }

    @GetMapping("/avaliador/{ano}/{membroId}/pdf")
    public ResponseEntity<byte[]> avaliadorPdf(@PathVariable int ano, @PathVariable Long membroId) {
        MembroUrgenciaRenal membro;
        try {
            membro = membroService.buscar(membroId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Avaliador nao encontrado.");
        }
        try {
            List<Processo> processos = agruparPorTipoDepoisSequencial(
                processoRepository.findByAnoComPareceres(ano));
            byte[] pdf = relatorioAvaliadorService.gerar(ano, membro, processos);
            String nome = "relatorio-avaliador-" + ano + "-" + membroId + ".pdf";
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + nome + "\"")
                .body(pdf);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Erro ao gerar relatorio: " + e.getMessage());
        }
    }

    @GetMapping("/avaliador/{ano}/{membroId}/csv")
    public ResponseEntity<byte[]> avaliadorCsv(@PathVariable int ano, @PathVariable Long membroId) {
        MembroUrgenciaRenal membro;
        try {
            membro = membroService.buscar(membroId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Avaliador nao encontrado.");
        }
        List<Processo> processos = agruparPorTipoDepoisSequencial(processoRepository.findByAnoComPareceres(ano));
        DadosRelatorioAvaliador dados = relatorioAvaliadorService.calcularDados(ano, membro, processos);

        StringBuilder csv = new StringBuilder();
        csv.append('﻿'); // BOM UTF-8 - sem ele o Excel no Windows abre acento corrompido.

        csv.append("Resumo do avaliador ").append(membro.getRotulo()).append(" no ano ").append(ano).append('\n');
        csv.append("Indicador;Valor\n");
        var resumo = dados.resumo();
        linhaCsv(csv, "Processos avaliados (respondidos)", String.valueOf(resumo.totalAvaliados()));
        linhaCsv(csv, "Tempo médio de resposta",
            br.gov.saude.sgpur.service.TempoRespostaService.formatarDias(resumo.mediaGeralDias()));
        linhaCsv(csv, "Respostas fora do prazo (meta " + resumo.prazoDias() + " dias corridos)",
            resumo.foraDoPrazo() + " de " + resumo.totalAvaliados());

        csv.append("\nTempo de resposta por processo\n");
        csv.append("Nº/Ano;Paciente;Parecer;Envio;Resposta;Dias;Prazo\n");
        for (LinhaDetalheAvaliador d : dados.detalhes()) {
            csv.append(csvCampo(d.numero())).append(';')
                .append(csvCampo(d.paciente())).append(';')
                .append(csvCampo(d.parecer())).append(';')
                .append(csvCampo(d.envio())).append(';')
                .append(csvCampo(d.resposta())).append(';')
                .append(d.dias()).append(';')
                .append(d.foraDoPrazo() ? "Fora" : "Dentro").append('\n');
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        String nome = "relatorio-avaliador-" + ano + "-" + membroId + ".csv";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nome + "\"")
            .body(bytes);
    }

    @GetMapping("/avaliador/{ano}/{membroId}/html")
    public String avaliadorHtml(@PathVariable int ano, @PathVariable Long membroId, Model model) {
        MembroUrgenciaRenal membro;
        try {
            membro = membroService.buscar(membroId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Avaliador nao encontrado.");
        }
        List<Processo> processos = agruparPorTipoDepoisSequencial(processoRepository.findByAnoComPareceres(ano));
        DadosRelatorioAvaliador dados = relatorioAvaliadorService.calcularDados(ano, membro, processos);
        model.addAttribute("dados", dados);
        model.addAttribute("ano", ano);
        model.addAttribute("membro", membro);
        model.addAttribute("emitidoEm", java.time.LocalDateTime.now());
        return "relatorios/avaliador-visualizacao";
    }

    /**
     * Monta uma linha "rotulo;valor" do CSV (mesmo padrao de escape/mitigacao
     * de {@link #csvCampo(String)}), usada nas secoes de resumo dos dois
     * relatorios.
     */
    private void linhaCsv(StringBuilder csv, String rotulo, String valor) {
        csv.append(csvCampo(rotulo)).append(';').append(csvCampo(valor)).append('\n');
    }

    /**
     * Escapa um campo para CSV com separador {@code ;} (aspas duplas quando
     * necessario) e neutraliza CSV Formula Injection - mesmo padrao de
     * {@code AuditoriaController.csvCampo}: um valor comecando com
     * {@code =}/{@code +}/{@code -}/{@code @} ganha um apostrofo na frente
     * antes do Excel/LibreOffice poder interpreta-lo como formula.
     */
    private String csvCampo(String valor) {
        if (valor == null) {
            return "";
        }
        String v = valor.replace("\r", " ").replace("\n", " ");
        if (v.startsWith("=") || v.startsWith("+") || v.startsWith("-") || v.startsWith("@")) {
            v = "'" + v;
        }
        if (v.contains(";") || v.contains("\"")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /**
     * Agrupa "urgencia renal comum antes de preemptivo" e, dentro de cada
     * grupo, ordena por {@code sequencial} (achado A8 da auditoria de
     * 2026-08-27). Feito aqui, em Java, e nao no {@code ORDER BY} de
     * {@link ProcessoRepository#findByAnoComPareceres} porque aquela consulta e
     * {@code select distinct} e o PostgreSQL nao aceita expressao
     * ({@code coalesce(preemptivo,false)}) no {@code ORDER BY} de um
     * {@code SELECT DISTINCT} - so coluna crua (ver javadoc da consulta).
     * {@code isPreemptivo()} e null-safe: legado {@code null} conta como
     * urgencia renal comum, igual ao {@code coalesce} que substitui.
     */
    private static List<Processo> agruparPorTipoDepoisSequencial(List<Processo> processos) {
        return processos.stream()
            .sorted(java.util.Comparator
                .comparing(Processo::isPreemptivo)
                .thenComparing(Processo::getSequencial,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
            .toList();
    }
}

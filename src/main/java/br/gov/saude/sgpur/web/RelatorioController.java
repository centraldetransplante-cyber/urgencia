package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.service.RelatorioAnualService;
import br.gov.saude.sgpur.service.RelatorioAvaliadorService;
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

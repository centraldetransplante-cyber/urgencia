package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.service.AuditoriaService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import br.gov.saude.sgpur.domain.LogAuditoria;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/auditoria")
public class AuditoriaController {

    private static final int TAMANHO = 30;

    private final AuditoriaService auditoria;

    public AuditoriaController(AuditoriaService auditoria) {
        this.auditoria = auditoria;
    }

    /**
     * Trilha de auditoria, com filtro por usuario, acao e periodo.
     *
     * <p>Ate 2026-08-04 esta tela nao tinha filtro nenhum, embora seja
     * justamente a que se usa quando ja se sabe o que procurar ("o que o
     * usuario X fez ontem", "quem excluiu este anexo"). A unica navegacao era
     * paginar do mais recente para tras, 30 em 30.</p>
     */
    @GetMapping
    public String listar(@RequestParam(defaultValue = "0") int page,
                         @RequestParam(required = false) String usuario,
                         @RequestParam(required = false) String acao,
                         @RequestParam(required = false)
                         @org.springframework.format.annotation.DateTimeFormat(iso =
                             org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate de,
                         @RequestParam(required = false)
                         @org.springframework.format.annotation.DateTimeFormat(iso =
                             org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate ate,
                         Model model) {
        Page<LogAuditoria> logs = auditoria.buscar(usuario, acao, de, ate,
            PageRequest.of(Math.max(page, 0), TAMANHO));

        model.addAttribute("usuario", usuario);
        model.addAttribute("acao", acao);
        model.addAttribute("de", de);
        model.addAttribute("ate", ate);
        model.addAttribute("acoesDisponiveis", auditoria.acoesDistintas());
        model.addAttribute("totalRegistros", logs.getTotalElements());
        model.addAttribute("temFiltro",
            (usuario != null && !usuario.isBlank()) || (acao != null && !acao.isBlank())
                || de != null || ate != null);

        // Agrupa os registros da pagina por dia, preservando a ordem (mais
        // recente primeiro) que ja vem do repositorio (dataHora desc).
        Map<LocalDate, List<LogAuditoria>> gruposPorDia = new LinkedHashMap<>();
        for (LogAuditoria log : logs.getContent()) {
            LocalDate dia = log.getDataHora().toLocalDate();
            gruposPorDia.computeIfAbsent(dia, d -> new java.util.ArrayList<>()).add(log);
        }

        model.addAttribute("gruposPorDia", gruposPorDia);
        model.addAttribute("paginaAtual", logs.getNumber());
        model.addAttribute("totalPaginas", logs.getTotalPages());
        return "auditoria/lista";
    }

    /**
     * Exporta em CSV os registros de auditoria que respeitam os MESMOS
     * filtros da tela (usuario / acao / periodo) — nunca a base inteira.
     *
     * <p>Exporta exatamente as mesmas 5 colunas que a tela ja exibe
     * (data/hora, usuario, acao, detalhe, ip) — nada alem disso. A trilha de
     * auditoria ja evita nome completo de paciente no campo "detalhe" (usa
     * {@code Iniciais.de(...)}, convencao desde 2026-07-28/2026-08-03) — a
     * exportacao nao introduz nenhum campo novo que pudesse vazar mais dado
     * do que a propria tela.</p>
     *
     * <p>O termo de filtro (usuario/acao/data) NUNCA e registrado em log de
     * auditoria nem em log de aplicacao — mesmo padrao ja documentado no
     * CLAUDE.md para as demais buscas do sistema (Membros, Usuarios,
     * Controle de Urgencias, Solicitacoes online).</p>
     */
    @GetMapping("/exportar")
    public ResponseEntity<byte[]> exportar(@RequestParam(required = false) String usuario,
                                            @RequestParam(required = false) String acao,
                                            @RequestParam(required = false)
                                            @org.springframework.format.annotation.DateTimeFormat(iso =
                                                org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate de,
                                            @RequestParam(required = false)
                                            @org.springframework.format.annotation.DateTimeFormat(iso =
                                                org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate ate) {
        List<LogAuditoria> registros = auditoria.buscarParaExportacao(usuario, acao, de, ate);

        DateTimeFormatter formatoData = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        StringBuilder csv = new StringBuilder();
        // BOM UTF-8: sem ele o Excel no Windows abre acento corrompido.
        csv.append('﻿');
        csv.append("Data/Hora;Usuario;Acao;Detalhe;IP\n");
        for (LogAuditoria log : registros) {
            csv.append(csvCampo(log.getDataHora().format(formatoData))).append(';')
               .append(csvCampo(log.getUsuario())).append(';')
               .append(csvCampo(log.getAcao())).append(';')
               .append(csvCampo(log.getDetalhe())).append(';')
               .append(csvCampo(log.getIp())).append('\n');
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        String nomeArquivo = "auditoria-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nomeArquivo + "\"")
                .body(bytes);
    }

    /**
     * Escapa um campo para CSV com separador ';' (aspas duplas quando
     * necessario) e neutraliza CSV/Formula Injection.
     *
     * <p>Se o valor comecar com {@code =}, {@code +}, {@code -} ou
     * {@code @}, o Excel/LibreOffice pode interpretar o campo como inicio de
     * formula ao abrir o CSV (ex.: {@code =HYPERLINK(...)}), permitindo
     * execucao de codigo/exfiltracao de dados a partir de um campo que
     * deveria ser texto puro (usuario, acao, detalhe, IP). Mitigacao padrao
     * OWASP: prefixar com um apostrofo {@code '} antes de aplicar o escape
     * de {@code ;}/{@code "}/quebra de linha ja existente — o Excel exibe o
     * valor como texto, sem interpretar formula.</p>
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
}

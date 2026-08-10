package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.dto.EtapaFluxo;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Listagem e filtros da home de /processos. */
@Controller
@RequestMapping("/processos")
public class ProcessoListaController {

    private final ProcessoService processoService;
    private final FluxoProcessoService fluxoService;

    public ProcessoListaController(ProcessoService processoService,
                                   FluxoProcessoService fluxoService) {
        this.processoService = processoService;
        this.fluxoService = fluxoService;
    }

    @ModelAttribute("statusValores")
    public StatusProcesso[] statusValores() {
        return StatusProcesso.values();
    }

    /** Valor de {@code ?filtro=} que restringe a lista aos Deferidos sem comprovante SNT. */
    static final String FILTRO_SNT_PENDENTE = "snt-pendente";

    @GetMapping
    @Transactional(readOnly = true)
    public String listar(@RequestParam(required = false) String q,
                         @RequestParam(required = false) StatusProcesso status,
                         @RequestParam(required = false) String filtro,
                         @RequestParam(defaultValue = "0") int page,
                         Model model) {
        boolean sntPendente = FILTRO_SNT_PENDENTE.equals(filtro);
        java.util.List<Processo> processos;
        if (sntPendente) {
            // Lista curta por natureza (pendencias abertas): sem paginacao, para
            // o operador ver de uma vez tudo o que esta travando comunicacao.
            processos = processoService.listarDeferidosSemComprovanteSnt();
            model.addAttribute("paginaAtual", 0);
            model.addAttribute("totalPaginas", 1);
        } else {
            var pagina = processoService.buscar(q, status,
                org.springframework.data.domain.PageRequest.of(Math.max(page, 0), 15));
            processos = pagina.getContent();
            model.addAttribute("paginaAtual", pagina.getNumber());
            model.addAttribute("totalPaginas", pagina.getTotalPages());
        }
        // Evita N+1 no calculo de pendencia por linha logo abaixo (que navega
        // pareceres/membro/anexos processo a processo): carrega as duas
        // colecoes num lote so, dentro da mesma transacao (readOnly acima).
        processoService.inicializarPareceresEAnexos(processos);
        model.addAttribute("processos", processos);
        model.addAttribute("q", q);
        model.addAttribute("statusSelecionado", status);
        model.addAttribute("filtroSntPendente", sntPendente);
        // Resumo de pendencia por processo (id -> etapa atual). Desde
        // 2026-08-05 (item 5.1 do relatorio de clareza) guarda a EtapaFluxo
        // inteira, nao mais uma string ja concatenada: o template mostra so
        // o titulo curto na celula e reserva a frase completa pro title,
        // sem competir por espaco na coluna nem cortar sem reticencias.
        // Processo sem pendencia (Optional vazio) simplesmente nao entra no
        // mapa - o template cai no fallback "Nada pendente".
        java.util.Map<Long, EtapaFluxo> pendencias = new java.util.LinkedHashMap<>();
        for (Processo p : processos) {
            fluxoService.pendenciaAberta(p).ifPresent(e -> pendencias.put(p.getId(), e));
        }
        model.addAttribute("pendencias", pendencias);
        // Deferidos sem comprovante SNT: badge de pendencia na linha + atalho
        // para o filtro. Uma consulta so (ids), nunca navegando getAnexos()
        // processo a processo (N+1).
        java.util.Set<Long> idsSemComprovanteSnt = processoService.idsDeferidosSemComprovanteSnt();
        model.addAttribute("idsSemComprovanteSnt", idsSemComprovanteSnt);
        model.addAttribute("totalSemComprovanteSnt", idsSemComprovanteSnt.size());
        // Qual regra decidiu cada processo (id -> RegraDecisao) - Achado 6 do
        // relatorio de vistoria de brechas (2026-08-10): o badge "Deferido
        // pelo Coordenador da CET-RS" so existia na tela de detalhe. Mesmo
        // padrao do mapa de pendencias acima (calculado uma vez aqui, o
        // template so consome via regrasDecisao.get(p.id)).
        java.util.Map<Long, br.gov.saude.sgpur.service.dto.RegraDecisao> regrasDecisao =
            new java.util.LinkedHashMap<>();
        for (Processo p : processos) {
            regrasDecisao.put(p.getId(), processoService.regraAplicada(p));
        }
        model.addAttribute("regrasDecisao", regrasDecisao);
        return "processos/lista";
    }
}

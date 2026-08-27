package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.service.TempoRespostaService;
import br.gov.saude.sgpur.service.TempoRespostaService.ResumoTempo;
import br.gov.saude.sgpur.service.TempoRespostaService.TempoMembro;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
@RequestMapping("/membros")
public class MembroController {

    private final MembroUrgenciaRenalRepository repo;
    private final ParecerRepository parecerRepo;
    private final TempoRespostaService tempoRespostaService;

    public MembroController(MembroUrgenciaRenalRepository repo, ParecerRepository parecerRepo,
                            TempoRespostaService tempoRespostaService) {
        this.repo = repo;
        this.parecerRepo = parecerRepo;
        this.tempoRespostaService = tempoRespostaService;
    }

    @GetMapping
    public String listar(@RequestParam(required = false) String q, Model model) {
        var membros = repo.buscar(q);
        model.addAttribute("membros", membros);
        model.addAttribute("q", q);
        // Estatisticas por membro:
        // [0]=designados, [1]=avaliados, [2]=favoraveis, [3]=preemptivos avaliados
        Map<Long, long[]> stats = new LinkedHashMap<>();
        for (MembroUrgenciaRenal m : membros) {
            stats.put(m.getId(), new long[]{
                parecerRepo.countByMembroId(m.getId()),
                parecerRepo.countByMembroIdAndResultadoNotNull(m.getId()),
                parecerRepo.countByMembroIdAndResultado(m.getId(), ResultadoParecer.FAVORAVEL),
                parecerRepo.countByMembroIdAndResultadoNotNullAndProcesso_PreemptivoTrue(m.getId())
            });
        }
        model.addAttribute("stats", stats);

        // Tempo de resposta: texto pronto por membro (mantem a view limpa) +
        // flags de "fora do prazo" para o destaque visual.
        ResumoTempo tempo = tempoRespostaService.calcular();
        Map<Long, String> tempoTexto = new LinkedHashMap<>();
        Map<Long, Boolean> tempoForaPrazo = new LinkedHashMap<>();
        for (var e : tempo.porMembro().entrySet()) {
            TempoMembro tm = e.getValue();
            tempoTexto.put(e.getKey(), TempoRespostaService.formatarDias(tm.mediaDias()));
            tempoForaPrazo.put(e.getKey(),
                tm.mediaDias() != null && tm.mediaDias() > tempo.prazoDias());
        }
        model.addAttribute("tempoTexto", tempoTexto);
        model.addAttribute("tempoForaPrazo", tempoForaPrazo);
        model.addAttribute("mediaGeralTexto", TempoRespostaService.formatarDias(tempo.mediaGeralDias()));
        model.addAttribute("totalAvaliadosTempo", tempo.totalAvaliados());
        model.addAttribute("foraDoPrazoTempo", tempo.foraDoPrazo());
        model.addAttribute("prazoDias", tempo.prazoDias());
        return "membros/lista";
    }

    @GetMapping("/novo")
    public String novo(Model model) {
        model.addAttribute("membro", new MembroUrgenciaRenal());
        return "membros/form";
    }

    @GetMapping("/{id}/editar")
    public String editar(@PathVariable Long id, Model model) {
        model.addAttribute("membro", buscarOu404(id));
        return "membros/form";
    }

    /**
     * Busca o membro ou responde 404 (em vez de estourar um
     * {@code NoSuchElementException} cru, que o handler global mapeava para
     * "Erro interno do servidor"). Mesmo padrao do AvaliadorController.
     */
    private MembroUrgenciaRenal buscarOu404(Long id) {
        return repo.findById(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Membro nao encontrado: " + id));
    }

    /**
     * Cria ou atualiza um membro.
     *
     * <p>IMPORTANTE: o objeto vindo do formulario e uma entidade DETACHED e
     * NAO carrega o {@code @Version} (o form nao envia esse campo, de
     * proposito — a versao nao e exposta ao cliente). Salvar esse objeto
     * direto com {@code repo.save(...)} fazia o Spring Data considerar a
     * entidade NOVA (versao {@code null} em atributo de versao nao-primitivo
     * =&gt; {@code isNew() == true}) e chamar {@code persist()} em vez de
     * {@code merge()}, quebrando toda edicao de membro com
     * "Detached entity with generated id ... has an uninitialized version
     * value 'null'". Por isso carregamos a entidade gerenciada por id e
     * copiamos os campos (mesmo padrao de
     * {@code ControleUrgenciaService.atualizar}).</p>
     */
    @PostMapping
    @Transactional
    public String salvar(@Valid @ModelAttribute("membro") MembroUrgenciaRenal form,
                         BindingResult result, RedirectAttributes ra) {
        if (result.hasErrors()) {
            return "membros/form";
        }
        // So pode haver um coordenador CET-RS por vez (as regras de decisao por
        // maioria assumem no maximo um). Ao marcar este membro como coordenador,
        // desmarca automaticamente qualquer outro que ja estivesse marcado.
        // CONCORRENCIA: @Transactional sob READ COMMITTED NAO fecha sozinho a
        // janela de "2 coordenadores" quando as duas requests marcam linhas
        // DIFERENTES sem coordenador previo (nao ha linha compartilhada para o
        // @Version pegar). Por isso adquirimos primeiro um lock pessimista
        // (SELECT ... FOR UPDATE) sobre a tabela, serializando as promocoes:
        // a 2a request so prossegue apos a 1a commitar e entao ja enxerga o
        // coordenador recem-marcado para desmarca-lo.
        if (form.isCoordenador()) {
            repo.lockTodosParaCoordenador();
            repo.findByCoordenadorTrue().stream()
                .filter(outro -> !outro.getId().equals(form.getId()))
                .forEach(outro -> {
                    outro.setCoordenador(false);
                    repo.save(outro);
                });
        }
        // Edicao: carrega a entidade gerenciada (com o @Version correto) e
        // copia os campos do formulario. Cadastro novo: usa o proprio form.
        MembroUrgenciaRenal membro = form.getId() == null ? form : buscarOu404(form.getId());
        membro.setInstituicao(form.getInstituicao());
        membro.setNome(form.getNome());
        membro.setEmail(form.getEmail());
        membro.setAtivo(form.isAtivo());
        membro.setCoordenador(form.isCoordenador());
        repo.save(membro);
        ra.addFlashAttribute("msg", "Membro salvo com sucesso.");
        return "redirect:/membros";
    }

    @PostMapping("/{id}/alternar-ativo")
    public String alternarAtivo(@PathVariable Long id, RedirectAttributes ra) {
        MembroUrgenciaRenal m = buscarOu404(id);
        m.setAtivo(!m.isAtivo());
        repo.save(m);
        ra.addFlashAttribute("msg", "Situacao do membro atualizada.");
        return "redirect:/membros";
    }
}

package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.service.ProcessoValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Arquivo: listagem, apenas leitura, dos processos ENCERRADOS
 * (Deferido/Indeferido/Cancelado). Um "local" separado para consultar e
 * visualizar processos antigos, sem se misturar ao fluxo ativo em /processos.
 * A reabertura de um encerrado e acao exclusiva do ADMIN (ver
 * ProcessoDetalheController.reabrir / SecurityConfig).
 */
@Controller
@RequestMapping("/arquivo")
public class ArquivoController {

    private static final List<StatusProcesso> ENCERRADOS =
        List.of(StatusProcesso.DEFERIDO, StatusProcesso.INDEFERIDO, StatusProcesso.CANCELADO);

    /** Mesmo tamanho de pagina de /processos, para as duas telas se comportarem igual. */
    static final int TAMANHO_PAGINA = 15;

    private final ProcessoRepository processoRepository;
    private final ProcessoValidator processoValidator;

    public ArquivoController(ProcessoRepository processoRepository, ProcessoValidator processoValidator) {
        this.processoRepository = processoRepository;
        this.processoValidator = processoValidator;
    }

    /**
     * Lista paginada dos encerrados, com busca resolvida no banco.
     *
     * <p><b>Por que paginar (auditoria de UI, 2026-08-04).</b> Ate entao esta
     * tela carregava TODOS os processos encerrados em memoria e filtrava a
     * busca em Java, apoiada na premissa de que o conjunto era pequeno. Ela e
     * verdadeira hoje, mas o Arquivo e a UNICA tela do sistema que so cresce -
     * nada nunca sai dela -, enquanto /processos, naturalmente limitada pelo
     * trabalho ativo, ja era paginada em 15. O esforco de paginacao estava na
     * tela que nao precisava dele.</p>
     *
     * <p><b>{@code @Transactional(readOnly = true)}: obrigatorio para a
     * hidratacao de pareceres funcionar (ver comentario mais abaixo).</b>
     * {@code inicializarPareceresComMembro} so consegue "casar" as colecoes
     * recem-carregadas com as MESMAS instancias de {@code Processo} ja
     * devolvidas por {@code buscarEncerrados} se as duas consultas rodarem
     * na MESMA sessao/persistence context do Hibernate - o que so acontece
     * se o metodo inteiro estiver dentro de uma unica transacao. Sem essa
     * anotacao, cada chamada de repositorio abre e fecha sua propria
     * transacao/sessao, e a segunda consulta carrega instancias NOVAS e
     * descartadas, sem nenhum efeito sobre as instancias que
     * {@code regraAplicada} de fato le logo abaixo (mesmo padrao ja usado em
     * {@code ProcessoListaController.listar}).</p>
     */
    @GetMapping
    @Transactional(readOnly = true)
    public String listar(@RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "0") int page,
                         Model model) {
        Page<Processo> pagina = processoRepository.buscarEncerrados(
            q, ENCERRADOS, PageRequest.of(Math.max(page, 0), TAMANHO_PAGINA));

        // Bug real de producao (500 em /arquivo, corrigido em 2026-08-11):
        // buscarEncerrados NAO traz pareceres (fetch join de colecao +
        // paginacao na mesma query faz o Hibernate paginar em memoria - ver
        // javadoc do repositorio), e este metodo nao e @Transactional. Sem
        // hidratar pareceres AQUI, o loop abaixo (regraAplicada, que para
        // status DEFERIDO navega processo.getPareceres() via
        // temVotoCoordenadorFavoravel) lancava LazyInitializationException -
        // 500 cru para QUALQUER processo Deferido na pagina. Mesma correcao
        // ja aplicada em ProcessoListaController.listar.
        if (!pagina.getContent().isEmpty()) {
            processoRepository.inicializarPareceresComMembro(
                pagina.getContent().stream().map(Processo::getId).toList());
        }

        model.addAttribute("processos", pagina.getContent());
        model.addAttribute("q", q);
        model.addAttribute("paginaAtual", pagina.getNumber());
        model.addAttribute("totalPaginas", pagina.getTotalPages());
        model.addAttribute("totalEncerrados", pagina.getTotalElements());
        // Qual regra decidiu cada processo - Achado 6 do relatorio de
        // vistoria de brechas (2026-08-10), mesmo padrao do mapa usado em
        // ProcessoListaController.
        Map<Long, br.gov.saude.sgpur.service.dto.RegraDecisao> regrasDecisao = new LinkedHashMap<>();
        for (Processo p : pagina.getContent()) {
            regrasDecisao.put(p.getId(), processoValidator.regraAplicada(p));
        }
        model.addAttribute("regrasDecisao", regrasDecisao);
        return "arquivo/lista";
    }
}

package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import br.gov.saude.sgpur.service.TempoRespostaService;
import br.gov.saude.sgpur.service.TempoRespostaService.ResumoTempo;
import br.gov.saude.sgpur.service.dto.EtapaFluxo;
import br.gov.saude.sgpur.service.dto.RegraDecisao;
import br.gov.saude.sgpur.web.dto.PainelLinha;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class HomeController {

    private static final Logger log = LoggerFactory.getLogger(HomeController.class);

    private final ProcessoRepository processoRepository;
    private final MembroUrgenciaRenalService membroService;
    private final FluxoProcessoService fluxoService;
    private final TempoRespostaService tempoRespostaService;
    private final ProcessoValidator processoValidator;

    public HomeController(ProcessoRepository processoRepository,
                          MembroUrgenciaRenalService membroService,
                          FluxoProcessoService fluxoService,
                          TempoRespostaService tempoRespostaService,
                          ProcessoValidator processoValidator) {
        this.processoRepository = processoRepository;
        this.membroService = membroService;
        this.fluxoService = fluxoService;
        this.tempoRespostaService = tempoRespostaService;
        this.processoValidator = processoValidator;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    @Transactional(readOnly = true)
    public String dashboard(Model model) {
        // Painel focado no ANO CORRENTE: contadores e planilha refletem apenas os
        // processos deste ano. Carrega uma vez (com pareceres/medicos, fetch join)
        // e agrega em Java, seguindo a convencao do projeto.
        int anoCorrente = java.time.Year.now().getValue();
        model.addAttribute("anoCorrente", anoCorrente);

        List<Processo> processos = processoRepository.findByAnoComPareceres(anoCorrente).stream()
            // Pendencias primeiro: processos EM ANDAMENTO vao para o topo; dentro de
            // cada grupo, "mais recente primeiro" (a query vem em sequencial asc).
            .sorted(java.util.Comparator
                .comparing((Processo p) -> p.getStatus().isEmAndamento() ? 0 : 1)
                .thenComparing(Processo::getSequencial,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
            .toList();
        // findByAnoComPareceres ja traz pareceres+membro por fetch join, mas
        // anexos continua LAZY - o calculo de pendencia abaixo (via
        // FluxoProcessoService.pendenciaAberta) navega p.getAnexos() por
        // processo. Inicializa num lote so (mesma transacao/persistence
        // context), evitando 1 select de anexos por processo do ano inteiro
        // em toda visita ao Painel.
        // Falha isolada (RuntimeException): um UNICO Anexo.tipo fora do enum
        // atual (dado legado/removido do enum, sem validacao pelo
        // ddl-auto: update) faz esta consulta em lote lancar
        // InvalidDataAccessApiUsageException e derrubaria o Painel inteiro
        // (500) por causa de um processo so. Se falhar, cada processo volta a
        // carregar seus proprios anexos sob demanda (lazy) mais adiante -
        // FluxoProcessoService.anexosSeguro protege esse acesso individual,
        // entao so o(s) processo(s) realmente afetado(s) perdem o calculo de
        // pendencia, e o resto do Painel renderiza normalmente.
        if (!processos.isEmpty()) {
            try {
                processoRepository.inicializarAnexos(
                    processos.stream().map(Processo::getId).toList());
            } catch (RuntimeException e) {
                log.warn("Falha ao pre-carregar anexos em lote no Painel (provavel Anexo.tipo "
                    + "com valor fora do enum TipoAnexo atual, em algum processo do ano) - cada "
                    + "processo vai carregar seus proprios anexos sob demanda: {}", e.getMessage());
            }
        }

        long deferidos = 0;
        long indeferidos = 0;
        long cancelados = 0;
        long emAndamento = 0;
        Map<Long, EtapaFluxo> pendencias = new LinkedHashMap<>();
        for (Processo p : processos) {
            switch (p.getStatus()) {
                case DEFERIDO -> deferidos++;
                case INDEFERIDO -> indeferidos++;
                case CANCELADO -> cancelados++;
                default -> { }
            }
            // "Em andamento" agrupa os status nao finais (SOLICITADO, ENVIADO,
            // SOLICITA_INFORMACAO).
            if (p.getStatus().isEmAndamento()) {
                emAndamento++;
            }
            // O que falta por processo reusa o FluxoProcessoService, e vale
            // TAMBEM para processo ja decidido: status final trava a edicao das
            // etapas 1-4, mas oficio/comprovante SNT e a resposta ao solicitante
            // continuam pendentes ate serem feitos. Antes o calculo era feito so
            // para isEmAndamento(), entao esses ficavam sem nenhum "o que falta"
            // no Painel - justamente os que precisam de acao e ninguem ve.
            // pendenciaAberta devolve vazio quando nao ha nada pendente, entao
            // processo realmente concluido continua com a celula limpa. Desde
            // 2026-08-05 devolve a EtapaFluxo inteira (nao so a string ja
            // concatenada), para o template mostrar so o titulo curto na
            // celula e reservar a frase completa (titulo + detalhe) pro title.
            fluxoService.pendenciaAberta(p).ifPresent(e -> pendencias.put(p.getId(), e));
        }

        model.addAttribute("totalProcessos", processos.size());
        model.addAttribute("deferidos", deferidos);
        model.addAttribute("indeferidos", indeferidos);
        model.addAttribute("cancelados", cancelados);
        model.addAttribute("emAndamento", emAndamento);
        model.addAttribute("pendencias", pendencias);
        model.addAttribute("membrosAtivos", membroService.contarAtivos());

        // Pendencia ATIVA de comprovante SNT: processo Deferido cujo comprovante
        // de insercao no SNT nunca foi anexado. Enquanto ele nao existe, a
        // resposta oficial ao solicitante fica BLOQUEADA (ProcessoValidator) —
        // ou seja, o paciente foi deferido e a equipe nao foi comunicada. Antes
        // isso nao entrava em contador nenhum (o painel so olhava processos em
        // andamento) e so aparecia como texto na coluna da lista.
        // Conta TODOS os anos de proposito (nao so o corrente): uma pendencia
        // dessas herdada do ano anterior continua sendo uma comunicacao devida.
        model.addAttribute("deferidosSemComprovanteSnt",
            processoRepository.contarDeferidosSemComprovanteSnt());

        // Planilha do painel: os processos do ano com os 3 medicos e o status
        // de cada parecer (Favoravel / Desfavoravel / Aguardando / ...).
        List<PainelLinha> linhas = processos.stream().map(PainelLinha::de).toList();
        model.addAttribute("linhas", linhas);

        // Qual regra decidiu cada processo - Achado 6 do relatorio de
        // vistoria de brechas (2026-08-10): o badge do voto unico do
        // Coordenador CET-RS so existia na tela de detalhe.
        Map<Long, RegraDecisao> regrasDecisao = new LinkedHashMap<>();
        for (Processo p : processos) {
            regrasDecisao.put(p.getId(), processoValidator.regraAplicada(p));
        }
        model.addAttribute("regrasDecisao", regrasDecisao);

        // Indicador: tempo de resposta medio total dos avaliadores.
        ResumoTempo tempo = tempoRespostaService.calcular();
        model.addAttribute("mediaGeralTempoTexto",
            TempoRespostaService.formatarDias(tempo.mediaGeralDias()));
        model.addAttribute("tempoDentroPrazo",
            tempo.mediaGeralDias() == null || tempo.mediaGeralDias() <= tempo.prazoDias());
        model.addAttribute("prazoDiasTempo", tempo.prazoDias());
        return "dashboard";
    }
}

package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.ConflitoEquipeMatcher;
import br.gov.saude.sgpur.service.EmailTemplateService;
import br.gov.saude.sgpur.service.ExportacaoProcessoService;
import br.gov.saude.sgpur.service.FluxoProcessoService;
import br.gov.saude.sgpur.service.GeminiService;
import br.gov.saude.sgpur.service.Iniciais;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.service.NomePadraoAnexo;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import br.gov.saude.sgpur.service.TempoRespostaService;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.MensagemAvaliador.RemetenteMensagemAvaliador;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import br.gov.saude.sgpur.service.MensagemAvaliadorService;
import br.gov.saude.sgpur.service.VerificadorNomePaciente;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import br.gov.saude.sgpur.service.dto.EstadoEtapa;
import br.gov.saude.sgpur.service.dto.PassoWizard;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.auditoria.Auditavel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.LocalDate;
import java.time.Year;
import java.util.Optional;

/**
 * Criacao, detalhe e edicao/exclusao do processo.
 *
 * <p>Desde 2026-07-27, todo processo nasce OBRIGATORIAMENTE de uma
 * {@code SolicitacaoOnline} convertida pelo Portal do Solicitante - nao ha
 * mais cadastro manual "do zero". O Passo 1 (Recebimento) e sempre
 * automatico (ver {@code FluxoProcessoService}), por isso o antigo endpoint
 * {@code POST /{id}/recebimento} (upload da solicitacao original + geracao
 * da capa do processo) foi removido - nao existe mais nenhum processo real
 * que precise dele.
 *
 * <p><b>Sem @Transactional de nivel de classe (removido em 2026-07-29).</b>
 * Uma transacao aberta pelo controller e compartilhada (propagacao REQUIRED)
 * com cada servico {@code @Transactional} chamado dentro dela: quando um
 * desses servicos lanca dentro de um {@code try/catch} do metodo, o
 * TransactionInterceptor da chamada aninhada marca a transacao inteira como
 * rollback-only. O {@code catch} devolve um flash amigavel, mas o commit no
 * fim do metodo estoura {@code UnexpectedRollbackException} (500 cru) e
 * <b>qualquer escrita anterior do mesmo metodo e perdida em silencio</b> —
 * foi assim que o voto do avaliador se perdeu ({@code AvaliadorController},
 * 2026-07-29). Aqui isso atingia {@link #salvar} (cadastro perdido se a
 * conversao da solicitacao de origem falhasse), {@link #reabrir} e os dois
 * "apagar mensagem".
 *
 * <p>Regra adotada por metodo: quem NAO precisa de sessao aberta (so chama
 * servicos que ja tem transacao propria e le campos escalares) fica <b>sem</b>
 * anotacao — assim cada servico commita/roda o rollback isoladamente; quem
 * precisa navegar colecoes LAZY ({@link #detalhe}, {@link #confirmarAnonimizacao})
 * declara {@code @Transactional} no proprio metodo e nao tem {@code try/catch}
 * em volta de servico transacional.
 */
@Controller
@RequestMapping("/processos")
public class ProcessoDetalheController {

    private final ProcessoService processoService;
    private final FluxoProcessoService fluxoService;
    private final EmailTemplateService emailTemplateService;
    private final MembroUrgenciaRenalService membroService;
    private final AnexoStorageService anexoStorage;
    private final AuditoriaService auditoria;
    private final GeminiService geminiService;
    private final ConflitoEquipeMatcher conflitoEquipeMatcher;
    private final SolicitacaoOnlineService solicitacaoOnlineService;
    private final SolicitacaoOnlineRepository solicitacaoOnlineRepository;
    private final MensagemSolicitacaoService mensagemService;
    private final UsuarioRepository usuarioRepo;
    private final AnexoRepository anexoRepo;
    /**
     * Usado SO por {@link #detalhe} (consulta com fetch join dos pareceres).
     * O restante do controller continua passando por {@link ProcessoService}.
     */
    private final ProcessoRepository processoRepo;
    private final boolean solicitanteHabilitado;
    private final TempoRespostaService tempoRespostaService;
    /** Chat com os avaliadores (docs/RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md), lado operador. */
    private final MensagemAvaliadorService mensagemAvaliadorService;
    private final VerificadorNomePaciente verificadorNomePaciente;
    private final ParecerRepository parecerRepo;
    private final MembroUrgenciaRenalRepository membroRepo;

    public ProcessoDetalheController(ProcessoService processoService,
                                     FluxoProcessoService fluxoService,
                                     EmailTemplateService emailTemplateService,
                                     MembroUrgenciaRenalService membroService,
                                     AnexoStorageService anexoStorage,
                                     AuditoriaService auditoria,
                                     GeminiService geminiService,
                                     ConflitoEquipeMatcher conflitoEquipeMatcher,
                                     SolicitacaoOnlineService solicitacaoOnlineService,
                                     SolicitacaoOnlineRepository solicitacaoOnlineRepository,
                                     MensagemSolicitacaoService mensagemService,
                                     UsuarioRepository usuarioRepo,
                                     AnexoRepository anexoRepo,
                                     ProcessoRepository processoRepo,
                                     @Value("${app.solicitante.habilitado:true}") boolean solicitanteHabilitado,
                                     TempoRespostaService tempoRespostaService,
                                     MensagemAvaliadorService mensagemAvaliadorService,
                                     VerificadorNomePaciente verificadorNomePaciente,
                                     ParecerRepository parecerRepo,
                                     MembroUrgenciaRenalRepository membroRepo) {
        this.processoService = processoService;
        this.fluxoService = fluxoService;
        this.emailTemplateService = emailTemplateService;
        this.membroService = membroService;
        this.anexoStorage = anexoStorage;
        this.auditoria = auditoria;
        this.geminiService = geminiService;
        this.conflitoEquipeMatcher = conflitoEquipeMatcher;
        this.solicitacaoOnlineService = solicitacaoOnlineService;
        this.solicitacaoOnlineRepository = solicitacaoOnlineRepository;
        this.mensagemService = mensagemService;
        this.usuarioRepo = usuarioRepo;
        this.anexoRepo = anexoRepo;
        this.processoRepo = processoRepo;
        this.solicitanteHabilitado = solicitanteHabilitado;
        this.tempoRespostaService = tempoRespostaService;
        this.mensagemAvaliadorService = mensagemAvaliadorService;
        this.verificadorNomePaciente = verificadorNomePaciente;
        this.parecerRepo = parecerRepo;
        this.membroRepo = membroRepo;
    }

    /**
     * Status que o operador pode escolher como DECISAO final na tela de
     * detalhe. So as decisoes reais entram aqui - SOLICITADO/ENVIADO/
     * SOLICITA_INFORMACAO sao estados de andamento, nao decisoes.
     */
    @ModelAttribute("decisaoValores")
    public StatusProcesso[] decisaoValores() {
        return new StatusProcesso[]{
            StatusProcesso.DEFERIDO, StatusProcesso.INDEFERIDO, StatusProcesso.CANCELADO
        };
    }

    @ModelAttribute("tipoAnexoValores")
    public TipoAnexo[] tipoAnexoValores() {
        return TipoAnexo.values();
    }

    /** Controla a exibicao dos botoes de assistencia por IA nas telas (so aparecem se a chave estiver configurada). */
    @ModelAttribute("iaDisponivel")
    public boolean iaDisponivel() {
        return geminiService.isDisponivel();
    }

    // Sem @Transactional: nada aqui navega colecao LAZY - solicitacaoOnlineService
    // .buscar e membroService.listarAtivos devolvem entidades ja carregadas e o
    // template (processos/form.html) so le campos escalares delas.
    @GetMapping("/novo")
    public String novo(@RequestParam(required = false) Long origemSolicitacaoOnlineId, Model model,
                        RedirectAttributes ra) {
        // Desde 2026-07-27, cada processo tem que vir de uma SolicitacaoOnline
        // convertida pelo Portal do Solicitante - nao existe mais cadastro
        // manual "do zero". Kill-switch do proprio Portal: se o modulo estiver
        // desligado, nao ha como triar nenhuma solicitacao, logo nao ha como
        // cadastrar processo NENHUM por aqui (a fila de triagem
        // /processos/solicitacoes-online tambem nao esta registrada nesse
        // caso - mensagem direciona para a lista de processos, nao para ela).
        if (!solicitanteHabilitado) {
            ra.addFlashAttribute("erro",
                "O Portal do Solicitante está desativado. Não é possível cadastrar processos "
                    + "enquanto o módulo estiver desligado.");
            return "redirect:/processos";
        }
        if (origemSolicitacaoOnlineId == null) {
            ra.addFlashAttribute("erro",
                "Todo processo deve ser criado a partir de uma solicitação do Portal do Solicitante.");
            return "redirect:/processos/solicitacoes-online";
        }
        Processo p = new Processo();
        p.setDataSituacaoEspecial(LocalDate.now());
        // Pre-preenche o formulario com os dados que o solicitante ja enviou
        // pelo portal, para o operador nao redigitar tudo. O operador ainda
        // confere os dados, escolhe os 3 avaliadores e digita o numero
        // normalmente - nada do fluxo de cadastro muda por causa disso.
        var s = solicitacaoOnlineService.buscar(origemSolicitacaoOnlineId);
        // Revisar e converter so pode acontecer UMA vez: bloqueia ja aqui (GET,
        // antes de montar o form) se a solicitacao ja foi triada - reforca a
        // mesma checagem feita em salvar() (POST) para quem chega direto por
        // link antigo/aba reaberta/botao voltar do navegador, sem passar pela
        // UI que ja esconde os botoes nesse caso.
        if (s.getStatus() != StatusSolicitacaoOnline.ENVIADA) {
            ra.addFlashAttribute("erro",
                "Esta solicitação já foi triada e não pode ser convertida novamente.");
            return "redirect:/processos/solicitacoes-online/" + origemSolicitacaoOnlineId;
        }
        p.setPacienteNome(s.getPacienteNome());
        p.setPacienteRgct(s.getPacienteRgct());
        p.setSolicitanteEquipe(s.getSolicitanteEquipe());
        p.setSolicitanteEmail(s.getSolicitanteEmail());
        p.setDataSituacaoEspecial(s.getDataSituacaoEspecial());
        p.setObservacoes(s.getJustificativaClinica());
        model.addAttribute("origemSolicitacaoOnlineId", origemSolicitacaoOnlineId);
        int ano = Year.now().getValue();
        boolean automatica = processoService.isNumeracaoAutomatica(ano);
        if (!automatica) {
            p.setNumero(processoService.proximoNumero(ano)); // sugestao editavel
        }
        model.addAttribute("processo", p);
        model.addAttribute("numeracaoAutomatica", automatica);
        model.addAttribute("medicos", membroService.listarAtivos());
        model.addAttribute("totalAvaliadores", ProcessoService.AVALIADORES_POR_PROCESSO);
        return "processos/form";
    }

    /**
     * Cadastra o processo a partir de uma solicitacao do Portal e, so depois,
     * converte a solicitacao de origem.
     *
     * <p><b>Sem @Transactional de proposito.</b> Este metodo faz DUAS escritas
     * em sequencia ({@code processoService.cadastrar} e, dentro de um
     * try/catch, {@code solicitacaoOnlineService.converter}) e o proprio
     * codigo ja declara a intencao: "se falhar aqui, o processo continua
     * valido". Com uma transacao de controller isso era mentira - as duas
     * chamadas compartilhavam a MESMA transacao fisica, a
     * {@code IllegalStateException} lancada por {@code converter} (metodo
     * {@code @Transactional}) marcava tudo como rollback-only, o
     * {@code catch} devolvia o flash de aviso e o commit final estourava
     * {@code UnexpectedRollbackException}, <b>desfazendo o cadastro que o
     * catch dizia ter preservado</b>. Sem anotacao, cada servico roda na sua
     * propria transacao e o comportamento documentado passa a ser o real.
     */
    @PostMapping
    public String salvar(@Valid @ModelAttribute("processo") Processo processo,
                         BindingResult result,
                         @RequestParam(value = "medicoIds", required = false) java.util.List<Long> medicoIds,
                         @RequestParam(required = false) Long origemSolicitacaoOnlineId,
                         Model model, RedirectAttributes ra) {
        // Mesma exigencia de novo() (GET): todo processo tem que vir de uma
        // SolicitacaoOnline convertida pelo Portal do Solicitante. Kill-switch
        // do modulo bloqueia qualquer cadastro por aqui.
        if (!solicitanteHabilitado) {
            ra.addFlashAttribute("erro",
                "O Portal do Solicitante está desativado. Não é possível cadastrar processos "
                    + "enquanto o módulo estiver desligado.");
            return "redirect:/processos";
        }
        if (origemSolicitacaoOnlineId == null) {
            ra.addFlashAttribute("erro",
                "Todo processo deve ser criado a partir de uma solicitação do Portal do Solicitante.");
            return "redirect:/processos/solicitacoes-online";
        }
        // Revisar e converter so pode acontecer UMA vez: se a solicitacao ja
        // foi triada (reenvio do form, duplo clique, aba antiga reaberta),
        // rejeita ANTES de cadastrar o Processo - checar so depois (como era
        // antes) criava um Processo duplicado de verdade e so avisava, sem
        // desfazer nada, porque a excecao chegava tarde demais.
        var origem = solicitacaoOnlineService.buscar(origemSolicitacaoOnlineId);
        if (origem.getStatus() != StatusSolicitacaoOnline.ENVIADA) {
            ra.addFlashAttribute("erro",
                "Esta solicitação já foi triada e não pode ser convertida novamente.");
            return "redirect:/processos/solicitacoes-online/" + origemSolicitacaoOnlineId;
        }
        int ano = processo.getDataSituacaoEspecial() != null
            ? processo.getDataSituacaoEspecial().getYear() : Year.now().getValue();
        boolean automatica = processoService.isNumeracaoAutomatica(ano);

        // Data da situacao especial define o ANO do processo (numeracao NN/AAAA
        // e RelatorioAnualService agrupam por ela) - um erro de digitacao no ano
        // (ex.: 2016 em vez de 2026, comum em datepicker/digitacao manual) e
        // aceito silenciosamente sem essa checagem, classificando o processo no
        // ano errado sem qualquer aviso. Janela ampla (5 anos passado/futuro)
        // porque a "situacao especial" pode legitimamente ser retroativa.
        if (processo.getDataSituacaoEspecial() != null) {
            int anoAtual = Year.now().getValue();
            if (ano < anoAtual - 5 || ano > anoAtual + 5) {
                result.rejectValue("dataSituacaoEspecial", "foraDoIntervalo",
                    "Data de solicitacao da urgencia renal fora do intervalo esperado (verifique o ano digitado).");
            }
        }

        // Numero so e obrigatorio/validado quando a numeracao for manual
        if (!automatica) {
            String numero = processo.getNumero();
            if (numero == null || numero.isBlank()) {
                result.rejectValue("numero", "obrigatorio", "Informe o numero do processo (NN/AAAA).");
            } else if (!numero.matches("\\d{1,3}/\\d{4}")) {
                result.rejectValue("numero", "formato", "Use o formato NN/AAAA (ex.: 01/2026).");
            } else if (processoService.numeroJaExiste(numero)) {
                result.rejectValue("numero", "duplicado",
                    "Ja existe um processo com o numero " + numero + ".");
            }
        }
        if (medicoIds == null || medicoIds.size() != ProcessoService.AVALIADORES_POR_PROCESSO) {
            result.reject("medicos", "Selecione exatamente "
                + ProcessoService.AVALIADORES_POR_PROCESSO + " medicos avaliadores.");
        }
        if (result.hasErrors()) {
            model.addAttribute("numeracaoAutomatica", automatica);
            model.addAttribute("medicos", membroService.listarAtivos());
            model.addAttribute("totalAvaliadores", ProcessoService.AVALIADORES_POR_PROCESSO);
            model.addAttribute("origemSolicitacaoOnlineId", origemSolicitacaoOnlineId);
            return "processos/form";
        }
        Processo salvo = processoService.cadastrar(processo, medicoIds);
        auditoria.registrar("PROCESSO_CADASTRADO",
            "Processo " + salvo.getNumero() + " - " + Iniciais.de(salvo.getPacienteNome()));
        // Fecha o vinculo com a solicitacao online de origem - copia os
        // documentos clinicos anexados pelo solicitante para o processo e
        // marca a solicitacao como CONVERTIDA. Feito DEPOIS do cadastro ja
        // ter tido sucesso; se falhar aqui, o processo continua valido (so a
        // solicitacao de origem fica sem o vinculo automatico, corrigivel
        // manualmente).
        try {
            solicitacaoOnlineService.converter(origemSolicitacaoOnlineId, salvo);
            auditoria.registrar("SOLICITACAO_ONLINE_CONVERTIDA",
                "Solicitacao " + origemSolicitacaoOnlineId + " -> Processo " + salvo.getNumero());
        } catch (IllegalStateException | IllegalArgumentException e) {
            ra.addFlashAttribute("aviso",
                "Processo cadastrado, mas houve falha ao vincular a solicitação online de origem: "
                    + e.getMessage());
        }
        ra.addFlashAttribute("msg", "Processo " + salvo.getNumero() + " cadastrado.");
        return "redirect:/processos/" + salvo.getId();
    }

    /**
     * Tela de detalhe do processo — a mais pesada do sistema.
     *
     * <p><b>@Transactional (leitura-escrita) no proprio metodo</b>, nao herdado
     * de anotacao de classe: esta rota TAMBEM escreve (marca as mensagens do
     * solicitante como lidas desde a correcao de 2026-07-28), por isso nao pode
     * ser {@code readOnly}. E nao ha nenhum {@code try/catch} em volta de
     * servico transacional aqui, entao a transacao unica nao cria o risco de
     * rollback-only silencioso descrito no javadoc da classe.
     *
     * <p><b>Colecoes LAZY:</b> o metodo E o template {@code processos/detalhe.html}
     * navegam as DUAS colecoes do processo ({@code pareceres}, com
     * {@code par.membro.rotulo}/{@code par.membro.email} na aba Respostas, e
     * {@code anexos}, na lista de Anexos), ja fora da transacao no caso do
     * template ({@code open-in-view: false}). Como ambas sao {@code List}
     * (bag), um fetch join duplo na mesma consulta lancaria
     * {@code MultipleBagFetchException}; por isso os pareceres (+ membro) vem
     * por fetch join ({@link ProcessoRepository#findByIdComPareceres}) e os
     * anexos sao inicializados logo abaixo, com um {@code size()} dentro desta
     * mesma transacao. Quando o metodo retorna, tudo o que a view usa ja esta
     * materializado nos objetos do Model.
     */
    @GetMapping("/{id}")
    @Transactional
    public String detalhe(@PathVariable Long id, Model model, Principal principal) {
        Processo p = processoRepo.findByIdComPareceres(id)
            .orElseThrow(() -> new IllegalArgumentException("Processo nao encontrado: " + id));
        // Inicializa a SEGUNDA colecao (bag) dentro desta transacao. Nao use
        // Hibernate.initialize(p.getAnexos()): getAnexos() devolve um
        // Collections.unmodifiableList(...) em volta do PersistentBag, e
        // Hibernate.initialize nao reconhece esse wrapper (viraria no-op
        // silencioso, com LazyInitializationException so na renderizacao).
        // size() delega ao bag e dispara o SELECT de verdade.
        p.getAnexos().size();
        model.addAttribute("processo", p);
        // Evita notificacao duplicada: esta tela ja tem seu proprio poll de chat
        // (chat-solicitacao.js), entao o poll GLOBAL da navbar (layout.html) fica
        // desligado aqui.
        model.addAttribute("chatAtivoNestaTela", true);
        // Iniciais do paciente: exibidas no aviso fixo do chat com cada
        // avaliador (docs/RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md,
        // secao 8.2) - copiar as iniciais prontas e mais facil do que digitar
        // o nome completo por engano.
        model.addAttribute("iniciais", Iniciais.de(p.getPacienteNome()));
        // Nome da pasta que o operador vera ao descompactar o dossie
        // (botao "Baixar processo completo (ZIP)" no card de Atalhos).
        model.addAttribute("nomePastaExportacao", ExportacaoProcessoService.nomePasta(p));
        var etapas = fluxoService.montarEtapas(p);
        model.addAttribute("etapas", etapas);
        long concluidas = etapas.stream().filter(e -> e.estado().name().equals("CONCLUIDA")).count();
        model.addAttribute("etapasConcluidas", concluidas);
        model.addAttribute("etapasTotal", etapas.size());
        model.addAttribute("progresso", etapas.isEmpty() ? 0 : Math.round(concluidas * 100.0 / etapas.size()));
        Optional<StatusProcesso> sugestao = processoService.sugerirDecisao(p);
        model.addAttribute("sugestao", sugestao.orElse(null));
        long favoraveis = processoService.contarFavoraveis(p);
        long naoFavoraveis = processoService.contarNaoFavoraveis(p);
        long pendentesVoto = p.getPareceres().size() - processoService.contarRespondidos(p);
        model.addAttribute("favoraveis", favoraveis);
        model.addAttribute("naoFavoraveis", naoFavoraveis);
        model.addAttribute("pendentesVoto", pendentesVoto);
        // Mensagens de AVALIADOR ainda nao lidas pelo OPERADOR, por membro
        // (chave = Parecer.id, mesma chave usada para os demais mapas desta
        // tela) - alimenta o badge "N nova(s)" no botao de conversa de cada
        // linha da tabela "Respostas dos Avaliadores".
        java.util.Map<Long, Long> naoLidasPorParecer = new java.util.HashMap<>();
        // Idem, mas so "existe alguma mensagem nesta thread" (lida ou nao) -
        // decide se o card de conversa desse avaliador nasce expandido
        // (CLAUDE.md, 2026-08-07): antes ficava sempre recolhido e o operador
        // podia nao perceber que ja havia conversa em andamento.
        java.util.Map<Long, Boolean> existeConversaPorParecer = new java.util.HashMap<>();
        // Versao em lote (2 queries no total, nao ate 6): ver javadoc de
        // MensagemAvaliadorService.resumoConversasDoProcesso (CLAUDE.md,
        // correcao de N+1 de 2026-08-08). Os mapas vem chaveados por membroId;
        // o template espera parecer.id, entao remapeamos aqui, no mesmo loop
        // (sem consulta nova nenhuma dentro dele).
        var resumoConversas = mensagemAvaliadorService.resumoConversasDoProcesso(p.getId());
        for (Parecer par : p.getPareceres()) {
            Long membroId = par.getMembro().getId();
            naoLidasPorParecer.put(par.getId(),
                resumoConversas.naoLidasPorMembro().getOrDefault(membroId, 0L));
            existeConversaPorParecer.put(par.getId(),
                resumoConversas.existeConversaPorMembro().getOrDefault(membroId, false));
        }
        model.addAttribute("naoLidasPorParecer", naoLidasPorParecer);
        model.addAttribute("existeConversaPorParecer", existeConversaPorParecer);
        // Placar de 3 posicoes no card de Respostas: so apresentacao do que a
        // maioria simples ja calcula (ProcessoValidator), nunca reimplementa a
        // regra aqui - se um dia a regra mudar, este texto some sozinho porque
        // deriva dos mesmos numeros usados para decidir.
        //
        // pausaBloqueiaDecisao: MESMO calculo de FluxoProcessoService.montarEtapas
        // (ver PR #47) - "maioria formada" NAO significa "decisao liberada"
        // enquanto o processo estiver em SOLICITA_INFORMACAO, exceto quando o
        // coordenador da CET-RS ja votou favoravel (excecao que defere mesmo
        // pausado). Sem isso, este card dizia "Maioria ja formada" e o alerta
        // "Sugestao automatica: Deferido" sem nenhuma ressalva - achado A do
        // docs/RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md (o PR
        // #47 so corrigiu o texto da timeline lateral, nao este card).
        boolean pausaBloqueiaDecisao = p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO
            && !processoService.temVotoCoordenadorFavoravel(p);
        model.addAttribute("pausaBloqueiaDecisao", pausaBloqueiaDecisao);
        String fraseMaioria;
        if (sugestao.isPresent() && pausaBloqueiaDecisao) {
            fraseMaioria = "Maioria formada, mas BLOQUEADA: aguardando informacao complementar";
        } else if (sugestao.isPresent()) {
            fraseMaioria = "Maioria ja formada";
        } else if (pendentesVoto == 0) {
            fraseMaioria = "Todos os votos recebidos";
        } else {
            fraseMaioria = "Faltam " + pendentesVoto + (pendentesVoto == 1 ? " voto" : " votos");
        }
        model.addAttribute("fraseMaioria", fraseMaioria);
        // Dias aguardando resposta de cada parecer AINDA pendente (para o
        // operador decidir se vale a pena mandar lembrete), reusando o mesmo
        // prazo-meta ja usado no Portal do Avaliador (app.avaliador.prazo-dias).
        int prazoDiasAvaliador = tempoRespostaService.getPrazoDias();
        model.addAttribute("prazoDiasAvaliador", prazoDiasAvaliador);
        java.util.Map<Long, Long> diasAguardandoPorParecer = new java.util.HashMap<>();
        for (Parecer par : p.getPareceres()) {
            if (par.getResultado() == null && par.getDataEnvio() != null) {
                diasAguardandoPorParecer.put(par.getId(),
                    java.time.temporal.ChronoUnit.DAYS.between(par.getDataEnvio(), LocalDate.now()));
            }
        }
        model.addAttribute("diasAguardandoPorParecer", diasAguardandoPorParecer);
        model.addAttribute("deferidoPeloCoordenador", processoService.deferidoPeloCoordenador(p));
        model.addAttribute("emails", emailTemplateService.gerar(p));
        // IDs dos pareceres votados diretamente pelo avaliador autenticado no portal.
        // Esses pareceres sao IMUTAVEIS pelo operador: o campo de resultado fica
        // bloqueado (disabled) e o anexo de resposta nao pode ser excluido nem substituido.
        java.util.Set<Long> pareceresPortal = p.getPareceres().stream()
            .filter(par -> par.getOrigem() == OrigemParecer.AVALIADOR_SISTEMA)
            .map(Parecer::getId)
            .collect(java.util.stream.Collectors.toSet());
        model.addAttribute("pareceresPortal", pareceresPortal);
        // Anexo do tipo SOLICITACAO_AVALIADOR = copia anonimizada para as equipes
        Optional<Anexo> solicitacaoPdf = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.SOLICITACAO_AVALIADOR)
            .findFirst();
        model.addAttribute("solicitacaoPdf", solicitacaoPdf.orElse(null));
        // Todo processo nasce de uma SolicitacaoOnline convertida pelo Portal
        // do Solicitante (desde 2026-07-27) - usado so para o link "Ver
        // solicitacao original" na tela de detalhe. Ver FluxoProcessoService.veioDoPortal.
        boolean processoVeioDoPortal = fluxoService.veioDoPortal(p);
        model.addAttribute("processoVeioDoPortal", processoVeioDoPortal); // Mantem para o link "Ver solicitacao original"
        if (processoVeioDoPortal) {
            // Carrega o ID da solicitacao de origem e o chat. Mesmo que todo processo
            // deva ter uma origem, uma verificacao de nulidade aqui protege contra
            // inconsistencias de dados (ex.: solicitacao de origem deletada).
            Optional<Long> solicitacaoOrigemIdOpt = solicitacaoOnlineRepository.findIdByProcessoGeradoId(p.getId());
            if (solicitacaoOrigemIdOpt.isPresent()) {
                Long solicitacaoOrigemId = solicitacaoOrigemIdOpt.get();
                model.addAttribute("solicitacaoOnlineOrigemId", solicitacaoOrigemId);
                // Nome real de quem enviou, para o CABECALHO do card de chat (as
                // mensagens em si ja usavam o nome real desde o PR #61 - ver
                // MensagemSolicitacaoService.paraChat). Antes de 2026-08-08 o
                // titulo ficava com o literal generico "Conversa com o solicitante".
                model.addAttribute("nomeSolicitante", solicitacaoOnlineService.nomeSolicitante(solicitacaoOrigemId));
                java.util.List<MensagemSolicitacao> mensagens = mensagemService.listarPorSolicitacao(solicitacaoOrigemId);
                model.addAttribute("mensagens", mensagens);
                long msgNaoLidas = mensagens.stream()
                    .filter(m -> !m.isLida() && m.getRemetente() == MensagemSolicitacao.RemetenteMensagem.SOLICITANTE)
                    .count();
                model.addAttribute("msgNaoLidas", msgNaoLidas);
                // Bug corrigido em 2026-07-28: faltava marcar como lidas aqui (unica
                // das 3 telas de chat que nao chamava isso) - o badge/notificacao
                // ficava preso pra sempre pra quem so usa esta tela. Ver CLAUDE.md.
                Usuario operadorLogado = usuarioRepo.findByUsername(principal.getName()).orElse(null);
                if (operadorLogado != null) {
                    mensagemService.marcarComoLidas(solicitacaoOrigemId,
                        MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, operadorLogado.getId());
                }
            } else {
                model.addAttribute("solicitacaoOnlineOrigemId", null);
            }
        } else {
            model.addAttribute("solicitacaoOnlineOrigemId", null);
        }
        // Documentos clinicos anonimizados que serao consolidados no PDF dos avaliadores
        java.util.List<Anexo> documentosClinicos = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR)
            .collect(java.util.stream.Collectors.toList());
        model.addAttribute("documentosClinicos", documentosClinicos);
        // TRAVA DE ANONIMIZACAO: documentos que vieram do Portal do Solicitante e
        // ainda NAO foram revisados. Ficam numa lista separada justamente para a
        // aba Envio deixar obvio que eles NAO serao enviados aos avaliadores
        // enquanto o operador nao confirmar a anonimizacao.
        model.addAttribute("documentosPendentesAnonimizacao",
            p.getAnexos().stream()
                .filter(a -> a.getTipo() == TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO)
                .sorted(java.util.Comparator.comparing(Anexo::getDataUpload))
                .toList());
        // Aviso (nao bloqueia): medicos possivelmente da mesma equipe/instituicao
        // do solicitante (casa sigla x nome por extenso x cidade, ignorando
        // acentos/maiusculas - ver ConflitoEquipeMatcher).
        String equipe = p.getSolicitanteEquipe();
        java.util.List<String> medicosMesmaEquipe = p.getPareceres().stream()
            .map(Parecer::getMembro)
            .filter(m -> conflitoEquipeMatcher.mesmaEquipe(m.getInstituicao(), equipe))
            .map(m -> m.getNome() + " (" + m.getInstituicao() + ")")
            .distinct()
            .collect(java.util.stream.Collectors.toList());
        model.addAttribute("medicosMesmaEquipe", medicosMesmaEquipe);

        // PAUSA: enquanto aguarda informacao complementar do solicitante, a
        // decisao e a finalizacao ficam bloqueadas ate o operador retomar a analise.
        boolean aguardandoInfo = p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO;
        model.addAttribute("aguardandoInfo", aguardandoInfo);
        // Anexos de informacao complementar ja recebidos (via e-mail lancado pelo
        // operador OU enviados diretamente pelo solicitante no Portal do Solicitante).
        model.addAttribute("anexosInfoComplementar",
            p.getAnexos().stream()
                .filter(a -> a.getTipo() == TipoAnexo.INFO_COMPLEMENTAR)
                .sorted(java.util.Comparator.comparing(Anexo::getDataUpload))
                .toList());

        // Anexos da aba Finalizacao
        Optional<Anexo> oficioAnexo = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.OFICIO_INDEFERIMENTO)
            .findFirst();
        model.addAttribute("oficioAnexo", oficioAnexo.orElse(null));
        Optional<Anexo> comprovanteSnT = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.COMPROVANTE_SNT)
            .findFirst();
        model.addAttribute("comprovanteSnT", comprovanteSnT.orElse(null));
        Optional<Anexo> comprovanteEnvioSolicitante = p.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.COMPROVANTE_ENVIO_SOLICITANTE)
            .findFirst();
        model.addAttribute("comprovanteEnvioSolicitante", comprovanteEnvioSolicitante.orElse(null));
        // Gating das abas (passo 1..4): ate qual passo o operador pode
        // navegar/agir. Calculo centralizado em FluxoProcessoService (mesma
        // fonte de verdade do checklist/wizard), fonte unica para nao
        // divergir da timeline.
        var gating = fluxoService.calcularGating(p);
        model.addAttribute("liberadoEnvio", gating.liberadoEnvio());
        model.addAttribute("liberadoRespostas", gating.liberadoRespostas());
        model.addAttribute("liberadoDecisao", gating.liberadoDecisao());
        model.addAttribute("liberadoFinalizacao", gating.liberadoFinalizacao());

        // Wizard horizontal: mesma fonte de verdade da timeline vertical
        // (FluxoProcessoService), para as duas linhas nunca divergirem.
        var passosWizard = fluxoService.montarPassosWizard(p);
        model.addAttribute("passosWizard", passosWizard);

        // envioFeito: usado em varios pontos do template (badge do wizard,
        // avisos, subpassos). Calculado uma unica vez aqui via
        // FluxoProcessoService.envioRegistrado - fonte unica, em vez do
        // template recalcular localmente com um criterio abandonado (so
        // olhar pareceres.get(0), que diverge quando so parte dos pareceres
        // tem dataEnvio - ver javadoc de envioRegistrado).
        model.addAttribute("envioFeito", fluxoService.envioRegistrado(p));
        String abaAtivaPaneId = passosWizard.stream()
            .filter(passo -> passo.estado() != EstadoEtapa.CONCLUIDA)
            .findFirst()
            .map(PassoWizard::paneId)
            .orElse(passosWizard.get(passosWizard.size() - 1).paneId());
        model.addAttribute("abaAtivaPaneId", abaAtivaPaneId);

        // Sub-rotulo dinamico ao lado do status (ex.: "Maioria formada -
        // pronto para decidir"). Calculo centralizado em FluxoProcessoService.
        model.addAttribute("statusSubrotulo", fluxoService.calcularSubrotuloStatus(p));

        // Previa do e-mail de resposta (deferido/indeferido) para exibir
        // na aba Finalizacao antes do envio automatico.
        if (p.getStatus() == StatusProcesso.DEFERIDO) {
            model.addAttribute("emailRespostaPreview", emailTemplateService.emailDeferido(p));
        } else if (p.getStatus() == StatusProcesso.INDEFERIDO) {
            model.addAttribute("emailRespostaPreview", emailTemplateService.emailIndeferido(p));
        }

        return "processos/detalhe";
    }

    // Sem @Transactional: processos/editar.html so exibe campos escalares do
    // processo (numero, nomes, datas) - nenhuma colecao LAZY e navegada.
    @GetMapping("/{id}/editar")
    public String editar(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id;
        }
        model.addAttribute("processo", p);
        return "processos/editar";
    }

    // Sem @Transactional: delega a escrita para processoService.atualizarDados
    // (que ja tem transacao propria) e nao navega colecao LAZY nenhuma.
    @PostMapping("/{id}/editar")
    public String atualizar(@PathVariable Long id,
                            @Valid @ModelAttribute("processo") Processo form,
                            BindingResult result, RedirectAttributes ra) {
        if (result.hasErrors()) {
            return "processos/editar";
        }
        if (bloqueadoPorEncerrado(processoService.buscar(id), ra)) {
            return "redirect:/processos/" + id;
        }
        processoService.atualizarDados(id, form);
        auditoria.registrar("PROCESSO_EDITADO", "Processo id " + id);
        ra.addFlashAttribute("msg", "Processo atualizado.");
        return "redirect:/processos/" + id;
    }

    /**
     * TRAVA DE ANONIMIZACAO (Passo 2 - Envio): promove um documento que veio do
     * Portal do Solicitante ({@code DOCUMENTO_PORTAL_NAO_ANONIMIZADO}, staging,
     * que nunca entra no PDF dos avaliadores) para
     * {@code DOCUMENTO_CLINICO_AVALIADOR}, tornando-o elegivel ao envio.
     *
     * <p>E um dos DOIS caminhos que tiram um documento do bloco de revisao. Este
     * aqui e para o falso-positivo: o operador conferiu e o documento nao
     * identifica o paciente, entao o proprio arquivo do solicitante e liberado.
     * Quando o documento realmente traz o nome do paciente, o caminho e
     * {@link #substituirPorVersaoAnonimizada} (sobe a versao editada e remove o
     * original numa acao so).
     *
     * <p>E o unico caminho de promocao IN-PLACE, e exige a confirmacao explicita do
     * operador ("Confirmo que este documento foi anonimizado") mais o registro
     * em auditoria de QUEM confirmou e QUAL anexo - esse log e o registro de que
     * a revisao humana aconteceu. Sem isso, o documento original do solicitante
     * (com o nome completo do paciente no corpo do laudo) chegaria aos 3 medicos
     * e quebraria a regra de imparcialidade sem deixar rastro.
     *
     * <p>O operador tambem pode ignorar a promocao e simplesmente subir um
     * arquivo ja anonimizado por {@code POST /{id}/documento-clinico}, que
     * continua entrando direto como {@code DOCUMENTO_CLINICO_AVALIADOR}.
     *
     * <p>{@code @Transactional} no proprio metodo (nao herdado de anotacao de
     * classe): a promocao e uma alteracao na entidade {@code Anexo} e o metodo
     * nao tem nenhum {@code try/catch} em volta de servico transacional, entao
     * a transacao unica e segura aqui (ver javadoc da classe).
     */
    @PostMapping("/{id}/documento-clinico/{anexoId}/confirmar-anonimizacao")
    @Transactional
    public String confirmarAnonimizacao(@PathVariable Long id,
                                        @PathVariable Long anexoId,
                                        @RequestParam(required = false, defaultValue = "false") boolean confirmo,
                                        Principal principal,
                                        RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#envio";
        }
        if (!confirmo) {
            ra.addFlashAttribute("erro",
                "Marque a confirmação de que o documento foi anonimizado (nome do paciente removido "
                    + "do corpo) antes de liberá-lo para os avaliadores.");
            return "redirect:/processos/" + id + "#envio";
        }
        // Busca o anexo direto pelo id e confere a POSSE (mesmo padrao
        // anti-IDOR de AvaliadorController.baixarPdf), em vez de varrer a
        // colecao LAZY p.getAnexos() so para achar um item: mesma garantia
        // ("nunca serve um anexo de outro processo") sem depender de a colecao
        // do processo estar inicializada.
        Anexo anexo = anexoRepo.findById(anexoId).orElse(null);
        if (anexo == null || anexo.getProcesso() == null
                || !id.equals(anexo.getProcesso().getId())) {
            ra.addFlashAttribute("erro", "Documento não encontrado neste processo.");
            return "redirect:/processos/" + id + "#envio";
        }
        if (anexo.getTipo() != TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO) {
            ra.addFlashAttribute("erro",
                "Este documento não está pendente de anonimização.");
            return "redirect:/processos/" + id + "#envio";
        }
        String quem = principal != null ? principal.getName() : "desconhecido";
        anexo.setTipo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        anexo.setDescricao("Documento do Portal do Solicitante com anonimizacao CONFIRMADA por "
            + quem + " em " + java.time.LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        anexoRepo.save(anexo);
        auditoria.registrar("ANONIMIZACAO_CONFIRMADA",
            "Processo " + p.getNumero() + " - anexo id " + anexoId + " (" + anexo.getNomeArquivo()
                + ") liberado para os avaliadores por " + quem);
        ra.addFlashAttribute("msg", "Anonimização confirmada: \"" + anexo.getNomeArquivo()
            + "\" agora entra no PDF enviado aos avaliadores.");
        return "redirect:/processos/" + id + "#envio";
    }

    /**
     * TRAVA DE ANONIMIZACAO - caminho da SUBSTITUICAO (Passo Envio): recebe a
     * versao ja anonimizada de um documento que veio do Portal do Solicitante e,
     * na MESMA acao, tira o original (nao anonimizado) do processo.
     *
     * <p>E o caminho para o caso mais comum e mais importante: o documento
     * realmente traz o nome do paciente no corpo, entao "Confirmo que ja esta
     * anonimizado" ({@link #confirmarAnonimizacao}) nao se aplica. Antes deste
     * endpoint o operador tinha que excluir o pendente no bloco vermelho e,
     * separadamente, procurar o formulario generico de documento clinico mais
     * abaixo na tela - duas acoes desconectadas, sem nenhum vinculo entre o
     * arquivo removido e o que entrou no lugar (e com uma janela em que o
     * processo ficava sem documento nenhum).
     *
     * <p><b>Ordem das escritas (importa):</b> salva o novo anexo PRIMEIRO e so
     * remove o original DEPOIS que o novo foi gravado (arquivo em disco +
     * registro no banco) - mesmo racional de
     * {@code ProcessoAnexoController.substituirAnexo}. Se o upload falhar
     * (arquivo vazio, extensao fora da allowlist, disco cheio), nada e removido
     * e o pendente continua no bloco de revisao; o pior caso da falha inversa
     * (remocao falhando depois do upload) deixa os DOIS visiveis na tela, que o
     * operador resolve com o botao de remover - nunca um processo sem documento.
     *
     * <p><b>Exige PDF</b> (mais restritivo que o upload generico de documento
     * clinico, que aceita toda a allowlist do {@code AnexoStorageService}):
     * como esta acao APAGA o original, aceitar um arquivo que a consolidacao
     * ignora silenciosamente ({@code SolicitacaoAvaliadorService} so funde PDF)
     * deixaria o processo sem nenhum documento elegivel ao envio e sem o
     * pendente para reverter.
     *
     * <p><b>O original nao e preservado, de proposito.</b> Ele e uma COPIA de
     * trabalho: o arquivo como o solicitante enviou continua intacto na
     * {@code SolicitacaoOnline} de origem ({@code AnexoSolicitacaoOnline}, ver
     * {@code SolicitacaoOnlineService.converter}), acessivel pelo link "Ver
     * solicitacao original". Manter tambem a copia aqui so espalharia o nome
     * completo do paciente por mais um lugar do processo (inclusive no dossie
     * exportado) sem nenhum ganho. O rastro de que ele existiu e foi
     * substituido, por quem e por qual arquivo, fica em {@code LogAuditoria} -
     * mesma decisao ja tomada em {@link #confirmarAnonimizacao}, que audita a
     * revisao sem guardar copia do PDF.
     *
     * <p><b>Sem {@code @Transactional}</b> (ao contrario de
     * {@link #confirmarAnonimizacao}): o metodo tem {@code try/catch} em volta
     * de {@code anexoStorage.salvar}, servico {@code @Transactional} que lanca
     * {@code IllegalArgumentException} para arquivo vazio/extensao proibida -
     * com transacao de controller esse caminho previsto terminaria em 500
     * ({@code UnexpectedRollbackException}), ver javadoc da classe. Por isso a
     * checagem de posse tambem nao navega nenhuma associacao LAZY: o anexo e
     * localizado por uma consulta que ja filtra processo + tipo.
     */
    @PostMapping("/{id}/documento-clinico/{anexoId}/substituir")
    public String substituirPorVersaoAnonimizada(@PathVariable Long id,
                                                 @PathVariable Long anexoId,
                                                 @RequestParam("arquivo") MultipartFile arquivo,
                                                 Principal principal,
                                                 RedirectAttributes ra) {
        String destino = "redirect:/processos/" + id + "#envio";
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return destino;
        }
        // Posse (anexo e DESTE processo) + tipo (esta mesmo pendente) numa
        // consulta so, sem navegar anexo.getProcesso() (LAZY) - este metodo nao
        // abre transacao. Mesma garantia anti-IDOR de confirmarAnonimizacao.
        Anexo pendente = anexoRepo
            .findByProcessoIdAndTipo(id, TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO)
            .stream()
            .filter(a -> a.getId().equals(anexoId))
            .findFirst()
            .orElse(null);
        if (pendente == null) {
            ra.addFlashAttribute("erro",
                "Documento não encontrado neste processo (ou já não está pendente de anonimização).");
            return destino;
        }
        if (arquivo == null || arquivo.isEmpty()) {
            ra.addFlashAttribute("erro",
                "Escolha o arquivo com a versão anonimizada antes de substituir. "
                    + "O documento pendente foi mantido.");
            return destino;
        }
        if (!"pdf".equals(NomePadraoAnexo.extensao(arquivo.getOriginalFilename()))) {
            ra.addFlashAttribute("erro",
                "A versão anonimizada precisa ser um arquivo PDF (só PDF entra no documento "
                    + "enviado aos avaliadores). O documento pendente foi mantido.");
            return destino;
        }
        String quem = principal != null ? principal.getName() : "desconhecido";
        String hoje = java.time.LocalDate.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String nomeOriginal = pendente.getNomeArquivo();
        Anexo novo;
        try {
            novo = anexoStorage.salvar(p, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR,
                "Versao ANONIMIZADA enviada por " + quem + " em " + hoje
                    + " em substituicao ao documento do Portal do Solicitante",
                arquivo);
        } catch (IllegalArgumentException | java.io.IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar a versão anonimizada: " + e.getMessage()
                + " O documento pendente foi mantido.");
            return destino;
        }
        // So agora o original sai (arquivo em disco + registro). Se esta remocao
        // falhar, os dois ficam visiveis na tela - estado ruim, mas seguro e
        // corrigivel pelo botao de remover; o inverso (apagar antes) poderia
        // deixar o processo sem documento nenhum.
        boolean originalRemovido = true;
        try {
            anexoStorage.excluir(anexoId);
        } catch (RuntimeException e) {
            originalRemovido = false;
        }
        auditoria.registrar("ANONIMIZACAO_SUBSTITUIDA",
            "Processo " + p.getNumero() + " - anexo id " + anexoId + " (" + nomeOriginal
                + ", do Portal do Solicitante) substituido pela versao anonimizada \""
                + novo.getNomeArquivo() + "\" (anexo id " + novo.getId() + ") por " + quem
                + (originalRemovido ? "" : " [FALHA ao remover o original - ainda no processo]"));
        if (originalRemovido) {
            ra.addFlashAttribute("msg",
                "Versão anonimizada anexada e documento original removido do processo. "
                    + "O novo arquivo entra no PDF enviado aos avaliadores.");
        } else {
            ra.addFlashAttribute("aviso",
                "Versão anonimizada anexada, mas o documento original NÃO pôde ser removido "
                    + "automaticamente. Remova-o pelo botão de remover, no bloco de revisão.");
        }
        return destino;
    }

    /**
     * Reabre um processo encerrado (Deferido/Indeferido/Cancelado), voltando-o
     * para ENVIADO. Restrito ao ADMIN (imposto no SecurityConfig por
     * {@code POST /processos/*}/reabrir). O botao so aparece para ADMIN e quando
     * o processo esta finalizado.
     *
     * <p>Sem {@code @Transactional}: o {@code try/catch} em volta de
     * {@code processoService.reabrir} (metodo {@code @Transactional} que lanca
     * {@code IllegalStateException}) so devolve o flash de erro esperado
     * porque nao existe mais transacao de controller para ser marcada como
     * rollback-only - antes, esse caminho terminava em 500 (ver javadoc da
     * classe).
     */
    @PostMapping("/{id}/reabrir")
    public String reabrir(@PathVariable Long id, RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        String numero = p.getNumero();
        try {
            processoService.reabrir(id);
            auditoria.registrar("PROCESSO_REABERTO", "Processo " + numero + " reaberto (voltou para Enviado)");
            ra.addFlashAttribute("msg", "Processo " + numero + " reaberto. Status voltou para Enviado.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/processos/" + id;
    }

    // Exclusao e um caminho unico e incondicional: acao auditada pelo aspect.
    // O detalhe grava o id do processo (o numero nao esta disponivel como
    // argumento do metodo).
    @PostMapping("/{id}/mensagem")
    public String enviarMensagem(@PathVariable Long id, @RequestParam String texto,
                                 Principal principal, RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        Long solicitacaoOrigemId = solicitacaoOnlineRepository.findIdByProcessoGeradoId(p.getId()).orElse(null);
        if (solicitacaoOrigemId == null) {
            ra.addFlashAttribute("erro", "Este processo não possui solicitação de origem vinculada.");
            return "redirect:/processos/" + id;
        }
        if (texto == null || texto.isBlank()) {
            ra.addFlashAttribute("erro", "A mensagem não pode estar em branco.");
            return "redirect:/processos/" + id;
        }
        if (texto.length() > MensagemSolicitacaoService.TEXTO_MAX_LENGTH) {
            ra.addFlashAttribute("erro", "A mensagem excede o limite de "
                + MensagemSolicitacaoService.TEXTO_MAX_LENGTH + " caracteres.");
            return "redirect:/processos/" + id;
        }
        SolicitacaoOnline s = solicitacaoOnlineService.buscar(solicitacaoOrigemId);
        Usuario operador = resolverOperador(principal);
        mensagemService.enviar(s, texto, MensagemSolicitacao.RemetenteMensagem.OPERADOR, operador.getId());
        auditoria.registrar("MENSAGEM_OPERADOR_ENVIADA",
            "Processo " + p.getNumero() + " - resposta do operador " + operador.getUsername());
        return "redirect:/processos/" + id;
    }

    // Sem @Transactional (nos dois "apagar mensagem", classico e AJAX): o
    // try/catch envolve mensagemService.apagar, metodo @Transactional que lanca
    // IllegalArgumentException - com transacao de controller o catch tratava o
    // erro mas o commit seguinte estourava UnexpectedRollbackException (500 no
    // lugar do flash / do JSON 400).
    @PostMapping("/{id}/mensagem/{mensagemId}/apagar")
    public String apagarMensagem(@PathVariable Long id, @PathVariable Long mensagemId,
                                  Principal principal, RedirectAttributes ra) {
        try {
            Usuario operador = resolverOperador(principal);
            mensagemService.apagar(mensagemId, operador.getId(), MensagemSolicitacao.RemetenteMensagem.OPERADOR);
            // Auditoria de exclusao (S9, achado A15): so id do processo/mensagem
            // + quem apagou, NUNCA o texto nem o nome completo do paciente.
            auditoria.registrar("MENSAGEM_APAGADA",
                "Processo " + id + " - mensagem " + mensagemId + " apagada pelo operador " + operador.getUsername());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/processos/" + id;
    }

    /** Polling do chat (AJAX) - equivalente ao usado nas outras 2 telas de chat. */
    @GetMapping("/{id}/mensagens")
    @ResponseBody
    public java.util.Map<String, Object> mensagensJson(@PathVariable Long id, Principal principal) {
        Processo p = processoService.buscar(id);
        Long solicitacaoOrigemId = solicitacaoOnlineRepository.findIdByProcessoGeradoId(p.getId()).orElse(null);
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        if (solicitacaoOrigemId == null) {
            resp.put("mensagens", java.util.List.of());
            resp.put("podeEnviar", false);
            return resp;
        }
        Usuario operador = resolverOperador(principal);
        mensagemService.marcarComoLidas(solicitacaoOrigemId, MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, operador.getId());
        String nomeSolicitante = solicitacaoOnlineService.nomeSolicitante(solicitacaoOrigemId);
        resp.put("mensagens", mensagemService.paraChat(
            solicitacaoOrigemId, MensagemSolicitacao.RemetenteMensagem.OPERADOR, operador.getId(), "Voce", nomeSolicitante));
        resp.put("podeEnviar", true);
        return resp;
    }

    @PostMapping("/{id}/mensagem/ajax")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> enviarMensagemAjax(@PathVariable Long id,
            @RequestParam String texto, Principal principal) {
        Processo p = processoService.buscar(id);
        Long solicitacaoOrigemId = solicitacaoOnlineRepository.findIdByProcessoGeradoId(p.getId()).orElse(null);
        if (solicitacaoOrigemId == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro",
                "Este processo não possui solicitação de origem vinculada."));
        }
        if (texto == null || texto.isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro", "A mensagem não pode estar em branco."));
        }
        if (texto.length() > MensagemSolicitacaoService.TEXTO_MAX_LENGTH) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro", "A mensagem excede o limite de "
                + MensagemSolicitacaoService.TEXTO_MAX_LENGTH + " caracteres."));
        }
        SolicitacaoOnline s = solicitacaoOnlineService.buscar(solicitacaoOrigemId);
        Usuario operador = resolverOperador(principal);
        mensagemService.enviar(s, texto, MensagemSolicitacao.RemetenteMensagem.OPERADOR, operador.getId());
        auditoria.registrar("MENSAGEM_OPERADOR_ENVIADA",
            "Processo " + p.getNumero() + " - resposta do operador " + operador.getUsername());
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    @PostMapping("/{id}/mensagem/{mensagemId}/apagar/ajax")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> apagarMensagemAjax(@PathVariable Long id,
            @PathVariable Long mensagemId, Principal principal) {
        try {
            Usuario operador = resolverOperador(principal);
            mensagemService.apagar(mensagemId, operador.getId(), MensagemSolicitacao.RemetenteMensagem.OPERADOR);
            auditoria.registrar("MENSAGEM_APAGADA",
                "Processo " + id + " - mensagem " + mensagemId + " apagada pelo operador " + operador.getUsername());
            return ResponseEntity.ok(java.util.Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Chat com os avaliadores (docs/RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md)
    // -------------------------------------------------------------------------
    //
    // Lado OPERADOR: uma thread 1:1 por (processo, membro), NUNCA um grupo com
    // os 3 avaliadores (destruiria a independencia dos pareceres). A posse
    // (este membro E avaliador deste processo) e sempre verificada via
    // ParecerRepository.findByProcessoIdAndMembroId - nunca resolve a thread
    // so pelos ids soltos. Conversa aberta ate o PROCESSO ser decidido; depois
    // disso, somente leitura (podeEnviar=false, mesmo mecanismo de
    // chat-solicitacao.js ja usado pelo chat do solicitante).
    //
    // VERIFICACAO DE NOME (secao 8.1 do relatorio): toda mensagem do operador
    // e checada contra o nome do paciente e a equipe solicitante DESTE
    // processo antes de gravar. Decisao de implementacao (o relatorio propos
    // dois niveis, ALERTA com confirmacao e BLOQUEADO): como chat-solicitacao.js
    // nao pode ser modificado (item 12.7 do relatorio - "nao reescrever, nunca
    // bifurcar") e ele nao tem um fluxo de confirmacao de 2 passos, os dois
    // niveis (ALERTA e BLOQUEADO) sao tratados da MESMA forma aqui: a mensagem
    // e recusada com uma explicacao clara, o operador reescreve e reenvia. E
    // mais conservador que a proposta original (nao existe fluxo de "confirmar
    // mesmo assim"), o que e aceitavel dado que o custo de um falso-positivo
    // (sobrenome comum) e so ter que reescrever a frase.

    /** Polling do chat com um avaliador especifico (AJAX). */
    @GetMapping("/{id}/avaliador/{membroId}/mensagens")
    @ResponseBody
    public java.util.Map<String, Object> mensagensAvaliadorJson(@PathVariable Long id, @PathVariable Long membroId,
            Principal principal) {
        Processo p = processoService.buscar(id);
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        if (parecerRepo.findByProcessoIdAndMembroId(id, membroId).isEmpty()) {
            resp.put("mensagens", java.util.List.of());
            resp.put("podeEnviar", false);
            return resp;
        }
        Usuario operador = resolverOperador(principal);
        mensagemAvaliadorService.marcarComoLidas(id, membroId, RemetenteMensagemAvaliador.AVALIADOR, operador.getId());
        String nomeMedico = membroRepo.findById(membroId).map(m -> m.getRotulo()).orElse("Avaliador");
        resp.put("mensagens", mensagemAvaliadorService.paraChat(
            id, membroId, RemetenteMensagemAvaliador.OPERADOR, operador.getId(), "Voce", nomeMedico));
        resp.put("podeEnviar", !p.getStatus().isFinalizado());
        return resp;
    }

    @PostMapping("/{id}/avaliador/{membroId}/mensagem/ajax")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> enviarMensagemAvaliadorAjax(@PathVariable Long id,
            @PathVariable Long membroId, @RequestParam String texto, Principal principal) {
        Processo p = processoService.buscar(id);
        var parecerOpt = parecerRepo.findByProcessoIdAndMembroId(id, membroId);
        if (parecerOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro",
                "Este médico não é avaliador deste processo."));
        }
        if (p.getStatus().isFinalizado()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro",
                "Este processo já foi decidido; a conversa ficou somente leitura."));
        }
        if (texto == null || texto.isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro", "A mensagem não pode estar em branco."));
        }
        if (texto.length() > MensagemAvaliadorService.TEXTO_MAX_LENGTH) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro", "A mensagem excede o limite de "
                + MensagemAvaliadorService.TEXTO_MAX_LENGTH + " caracteres."));
        }
        var verificacao = verificadorNomePaciente.verificar(texto, p.getPacienteNome(), p.getSolicitanteEquipe());
        if (verificacao.nivel() != VerificadorNomePaciente.Nivel.LIVRE) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro",
                "Esta mensagem parece citar o paciente ou a equipe solicitante (contém \""
                    + String.join("\", \"", verificacao.termosEncontrados())
                    + "\"). Refira-se ao paciente apenas pelas iniciais e não cite a equipe solicitante. "
                    + "Reescreva a mensagem e envie novamente."));
        }
        MembroUrgenciaRenal membro = membroRepo.findById(membroId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Avaliador não encontrado."));
        Usuario operador = resolverOperador(principal);
        mensagemAvaliadorService.enviar(p, membro, texto, RemetenteMensagemAvaliador.OPERADOR, operador.getId());
        // Auditoria: SO id/numero do processo + rotulo do medico, NUNCA o texto
        // da mensagem nem o nome do paciente (mesmo padrao ja usado para
        // MENSAGEM_OPERADOR_ENVIADA/PROCESSO_CADASTRADO - ver CLAUDE.md).
        auditoria.registrar("MENSAGEM_OPERADOR_AVALIADOR_ENVIADA",
            "Processo " + p.getNumero() + " - " + membro.getRotulo() + " - operador " + operador.getUsername());
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }

    @PostMapping("/{id}/avaliador/{membroId}/mensagem/{mensagemId}/apagar/ajax")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> apagarMensagemAvaliadorAjax(@PathVariable Long id,
            @PathVariable Long membroId, @PathVariable Long mensagemId, Principal principal) {
        try {
            Usuario operador = resolverOperador(principal);
            mensagemAvaliadorService.apagar(mensagemId, operador.getId(), RemetenteMensagemAvaliador.OPERADOR);
            // Auditoria de exclusao no canal Avaliador<->Operador (S9, achado
            // A15): id do processo/membro/mensagem + quem apagou, NUNCA o
            // texto nem o nome do paciente.
            auditoria.registrar("MENSAGEM_AVALIADOR_APAGADA",
                "Processo " + id + " - avaliador " + membroId + " - mensagem " + mensagemId
                    + " apagada pelo operador " + operador.getUsername());
            return ResponseEntity.ok(java.util.Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("erro", e.getMessage()));
        }
    }

    /**
     * Caixa de entrada de mensagens de avaliadores (F5 do relatorio): lista
     * TODAS as threads (processo, membro) que ja trocaram mensagem, mais
     * recente primeiro, para o operador achar conversas mesmo sem abrir o
     * processo correspondente (sem isso, uma mensagem de um medico so aparece
     * pra quem abrir aquele processo especifico - furo de visibilidade
     * documentado como risco R4 do relatorio).
     *
     * <p>Mostra numero do processo + rotulo do avaliador, NUNCA nome de
     * paciente - e uma lista de trabalho, e nome de paciente numa lista
     * aumenta exposicao sem ganho (mesma linha de raciocinio ja aplicada ao
     * termo de busca das outras telas do operador, que nunca vai para
     * auditoria nem aparece fora do contexto de um processo especifico).</p>
     */
    @GetMapping("/mensagens-avaliadores")
    public String caixaDeEntradaAvaliadores(Model model) {
        model.addAttribute("threads", mensagemAvaliadorService.listarCaixaDeEntradaOperador());
        return "processos/mensagens-avaliadores-lista";
    }

    @GetMapping("/mensagens-avaliadores/nao-lidas-count")
    @ResponseBody
    public java.util.Map<String, Object> mensagensAvaliadoresNaoLidasCount() {
        return java.util.Map.of("total", mensagemAvaliadorService.contarNaoLidasParaOperador());
    }

    @Auditavel(acao = "PROCESSO_EXCLUIDO", detalhe = "'Processo id ' + #args[0]")
    @PostMapping("/{id}/excluir")
    public String excluir(@PathVariable Long id, RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id;
        }
        String numero = p.getNumero();
        processoService.excluir(id);
        anexoStorage.removerPastaProcesso(p);
        ra.addFlashAttribute("msg", "Processo " + numero + " excluído.");
        return "redirect:/processos";
    }

    /**
     * Resolve o {@code Usuario} operador logado a partir do {@code Principal}.
     *
     * <p><b>Sessao orfa (mesmo bug ja corrigido em {@code AvaliadorController.
     * resolverMembro}):</b> se o usuario correspondente ao username gravado na
     * sessao nao existir mais no banco (ex.: um ADMIN trocou o {@code username}
     * desse operador em {@code /usuarios}, ou excluiu a conta, enquanto ele
     * tinha sessao ativa — o Spring Security nao rele o {@code UserDetails} a
     * cada requisicao), lanca {@link SessaoInvalidaException} em vez de
     * {@code ResponseStatusException(UNAUTHORIZED)}. O {@code
     * GlobalExceptionHandler} trata esse tipo invalidando a sessao e
     * redirecionando para {@code /login?erro=sessao-invalida}, em vez do 401
     * cru que o navegador exibia antes (inclusive nos endpoints AJAX/JSON desta
     * classe — o mesmo padrao ja funciona hoje nos endpoints {@code
     * @ResponseBody} do Portal do Avaliador).
     */
    private Usuario resolverOperador(Principal principal) {
        return usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new SessaoInvalidaException(
                "Usuario da sessao (" + principal.getName() + ") nao encontrado no banco."));
    }

    /**
     * Guarda de edicao: se o processo esta encerrado, registra o flash de erro e
     * retorna true (o chamador deve redirecionar sem efetivar a alteracao). So o
     * ADMIN pode reabrir para voltar a alterar.
     */
    private boolean bloqueadoPorEncerrado(Processo p, RedirectAttributes ra) {
        if (processoService.edicaoBloqueada(p)) {
            ra.addFlashAttribute("erro", ProcessoValidator.MSG_ENCERRADO);
            return true;
        }
        return false;
    }
}

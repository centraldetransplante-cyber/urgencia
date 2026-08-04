package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.Optional;

@Service
public class ProcessoService {

    /** A partir deste ano a numeracao passa a ser automatica (2026 e manual). */
    private static final int ANO_NUMERACAO_AUTOMATICA = 2027;

    /** Cada processo e enviado a exatamente 3 medicos avaliadores. */
    public static final int AVALIADORES_POR_PROCESSO = 3;

    /** Quantidade de pareceres favoraveis necessaria para deferir (maioria simples). */
    public static final int FAVORAVEIS_PARA_DEFERIR = 2;

    /** Quantidade de pareceres desfavoraveis necessaria para indeferir (maioria simples). */
    public static final int DESFAVORAVEIS_PARA_INDEFERIR = 2;

    private final ProcessoRepository processoRepository;
    private final MembroUrgenciaRenalRepository membroRepository;
    private final ProcessoValidator validator;
    private final ParecerRepository parecerRepository;
    private final SolicitacaoOnlineRepository solicitacaoOnlineRepository;
    private final EmailTemplateService emailTemplateService;
    private final EmailSenderService emailSenderService;
    private final AnexoStorageService anexoStorage;

    public ProcessoService(ProcessoRepository processoRepository,
                           MembroUrgenciaRenalRepository membroRepository,
                           ProcessoValidator validator,
                           ParecerRepository parecerRepository,
                           SolicitacaoOnlineRepository solicitacaoOnlineRepository,
                           EmailTemplateService emailTemplateService,
                           EmailSenderService emailSenderService,
                           AnexoStorageService anexoStorage) {
        this.processoRepository = processoRepository;
        this.membroRepository = membroRepository;
        this.validator = validator;
        this.parecerRepository = parecerRepository;
        this.solicitacaoOnlineRepository = solicitacaoOnlineRepository;
        this.emailTemplateService = emailTemplateService;
        this.emailSenderService = emailSenderService;
        this.anexoStorage = anexoStorage;
    }

    public org.springframework.data.domain.Page<Processo> buscar(
            String q, StatusProcesso status, org.springframework.data.domain.Pageable pageable) {
        String termo = (q == null || q.isBlank()) ? null : q.trim();
        return processoRepository.buscar(termo, status, pageable);
    }

    public Processo buscar(Long id) {
        return processoRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Processo nao encontrado: " + id));
    }

    /** Numeracao automatica a partir de 2027; nos anos anteriores e manual. */
    public boolean isNumeracaoAutomatica(int ano) {
        return ano >= ANO_NUMERACAO_AUTOMATICA;
    }

    public boolean numeroJaExiste(String numero) {
        return numero != null && processoRepository.findByNumero(numero).isPresent();
    }

    /** Proximo numero NN/AAAA para um ano (quando automatico). */
    public String proximoNumero(int ano) {
        Integer max = processoRepository.findMaxSequencialByAno(ano);
        int seq = (max == null ? 0 : max) + 1;
        return String.format("%02d/%d", seq, ano);
    }

    /**
     * Salva um novo processo. Gera o numero automaticamente quando o ano
     * estiver no regime automatico; caso contrario usa o numero informado.
     * Cria um parecer pendente para cada um dos 3 medicos escolhidos.
     */
    @Transactional
    public Processo cadastrar(Processo processo, List<Long> medicoIds) {
        if (medicoIds == null || medicoIds.size() != AVALIADORES_POR_PROCESSO) {
            throw new IllegalArgumentException(
                "Selecione exatamente " + AVALIADORES_POR_PROCESSO + " medicos avaliadores.");
        }
        // Defesa contra mass assignment: o form faz bind da entidade Processo
        // inteira (@ModelAttribute), entao um request malicioso poderia incluir
        // status=DEFERIDO/INDEFERIDO/CANCELADO e outros campos que so fazem
        // sentido apos a decisao. Um processo novo SEMPRE nasce SOLICITADO, sem
        // decisao registrada. Tambem forca id=null: sem isso, um request com um
        // id de processo EXISTENTE faria o save() abaixo cair em merge() em vez
        // de persist(), sobrescrevendo o processo alvo e apagando seus pareceres
        // e anexos reais (cascade=ALL + orphanRemoval=true), ate mesmo se ja
        // ENCERRADO - sem passar pelo ProcessoValidator.edicaoBloqueada.
        processo.setId(null);
        processo.setStatus(StatusProcesso.SOLICITADO);
        processo.setDataDecisao(null);
        processo.setMotivoIndeferimento(null);
        processo.setEmailEnviadoSolicitante(false);
        int ano = processo.getDataSituacaoEspecial() != null
            ? processo.getDataSituacaoEspecial().getYear()
            : Year.now().getValue();
        processo.setAno(ano);

        if (isNumeracaoAutomatica(ano)) {
            processo.setNumero(proximoNumero(ano));
        }
        processo.setSequencial(extrairSequencial(processo.getNumero(), ano));

        // cria um parecer pendente para cada medico escolhido
        for (Long medicoId : medicoIds) {
            MembroUrgenciaRenal medico = membroRepository.findById(medicoId)
                .orElseThrow(() -> new IllegalArgumentException("Medico nao encontrado: " + medicoId));
            processo.addParecer(new Parecer(medico));
        }
        // proximoNumero() calcula MAX(sequencial)+1 sem lock: dois cadastros
        // simultaneos no mesmo ano podem calcular o mesmo numero. A constraint
        // UNIQUE em Processo.numero ja impede a duplicata no banco, e o
        // GlobalExceptionHandler ja traduz a DataIntegrityViolationException
        // resultante numa mensagem amigavel - nao precisa de tratamento aqui.
        return processoRepository.save(processo);
    }

    @Transactional
    public Processo salvar(Processo processo) {
        return processoRepository.save(processo);
    }

    /**
     * Marca o processo como ENVIADO aos avaliadores (etapa 5 do fluxo).
     * So promove SOLICITADO/ENVIADO para ENVIADO; nunca rebaixa um processo
     * ja decidido, e NUNCA tira o processo da pausa SOLICITA_INFORMACAO.
     *
     * <p><b>Bug real corrigido (2026-08-03):</b> antes, a condicao era
     * {@code p.getStatus().isEmAndamento()}, que inclui SOLICITA_INFORMACAO —
     * reenviar documentos atualizados aos avaliadores enquanto o processo
     * aguardava a informacao complementar do solicitante desfazia a pausa
     * silenciosamente (status virava ENVIADO sem passar por
     * {@code retomarAposInformacao}), com dois efeitos: o parecer de quem
     * pediu a informacao ficava travado para sempre com
     * {@code resultado = SOLICITA_INFORMACAO} (so {@code retomarAposInformacao}
     * o reabre), e {@link ProcessoValidator#validarPausaDecisao} para de
     * bloquear a decisao (ele so olha o status atual) — o operador podia
     * Indeferir com 2 desfavoraveis e um pedido de informacao nunca resolvido,
     * e o {@code DecisaoAutomaticaScheduler} podia auto-decidir sozinho um
     * processo que ainda esperava resposta do solicitante.
     */
    @Transactional
    public Processo registrarEnvio(Long id) {
        Processo p = buscar(id);
        // Defesa em profundidade: mesma checagem ja feita na camada web
        // (comprovante de envio + documento clinico PDF), imposta aqui para
        // que o metodo nunca marque ENVIADO sem essas garantias minimas,
        // mesmo se chamado de outro lugar no futuro (outro controller, job,
        // teste) sem passar pela validacao do controller HTTP.
        validator.validarRegistroEnvio(p)
            .ifPresent(msg -> { throw new IllegalStateException(msg); });
        if (p.getStatus() != StatusProcesso.SOLICITA_INFORMACAO && p.getStatus().isEmAndamento()) {
            p.setStatus(StatusProcesso.ENVIADO);
        }
        return processoRepository.save(p);
    }

    /**
     * Recalcula o status "em andamento" do processo a partir dos pareceres
     * recebidos, SEM tomar a decisao final (que continua manual via decidir()):
     * - se algum medico pediu informacao e o processo ainda nao foi decidido,
     *   o status vai para SOLICITA_INFORMACAO;
     * - caso contrario permanece ENVIADO (ja foi enviado aos medicos).
     * Processo ja finalizado (DEFERIDO/INDEFERIDO/CANCELADO) nao pode ser
     * chamado aqui — o caller deve barrar antes (guarda de edicaoBloqueada);
     * chegar com um processo finalizado e erro de programacao, nao um caso
     * valido a silenciar.
     */
    @Transactional
    public Processo atualizarStatusPorPareceres(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isFinalizado()) {
            throw new IllegalStateException(ProcessoValidator.MSG_ENCERRADO);
        }
        boolean pediuInfo = p.getPareceres().stream()
            .anyMatch(par -> par.getResultado() == ResultadoParecer.SOLICITA_INFORMACAO);
        p.setStatus(pediuInfo ? StatusProcesso.SOLICITA_INFORMACAO : StatusProcesso.ENVIADO);
        return processoRepository.save(p);
    }

    /**
     * Tenta aplicar a decisao automatica por maioria simples (2 de 3), se todas as
     * pre-condicoes estiverem satisfeitas:
     *   - Processo ainda em andamento (nao finalizado) e nao aguardando info;
     *   - Maioria formada (>= 2 favoraveis ou >= 2 desfavoraveis).
     * Se todas as condicoes estiverem ok, chama {@link #decidir} e retorna o
     * processo atualizado. Caso contrario retorna o processo sem alteracao.
     * Deve ser chamado apos {@link #atualizarStatusPorPareceres} e apos
     * {@link #retomarAposInformacao}. Processo ja finalizado e erro de
     * programacao do caller (mesma razao de atualizarStatusPorPareceres).
     */
    @Transactional
    public Processo tentarDecisaoAutomatica(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isFinalizado()) {
            throw new IllegalStateException(ProcessoValidator.MSG_ENCERRADO);
        }
        // O coordenador CET-RS defere sozinho e imediatamente quando vota
        // Favoravel, mesmo que o processo esteja pausado por SOLICITA_INFORMACAO
        // por causa do parecer de outro avaliador. Essa prioridade so vale para
        // esse caminho automatico; sem o voto do coordenador, a pausa continua
        // bloqueando qualquer decisao automatica.
        if (p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO && !temVotoCoordenadorFavoravel(p)) {
            return p;
        }
        Optional<StatusProcesso> sugestao = sugerirDecisao(p);
        if (sugestao.isEmpty()) {
            return p;
        }
        StatusProcesso decisao = sugestao.get();
        // DEFERIDO dispensa motivo. INDEFERIDO tambem finaliza automaticamente
        // (decisao de produto confirmada): como o motivo e obrigatorio para o
        // oficio, geramos um texto consolidando as justificativas dos
        // pareceres desfavoraveis — o operador pode ajusta-lo depois (ADMIN
        // reabre o processo em /processos/{id}/reabrir e redecide pelo mesmo
        // formulario da aba Decisao, que reenvia o motivo).
        String motivoIndeferimento = decisao == StatusProcesso.INDEFERIDO
            ? gerarMotivoIndeferimentoAutomatico(p) : null;
        // Passa pela validacao completa (mesma do caminho manual) — defesa em
        // profundidade: nao grava um estado que decidir() rejeitaria.
        return decidir(id, decisao, motivoIndeferimento);
    }

    /**
     * Gera o texto do motivo de indeferimento quando a decisao automatica
     * finaliza o processo por maioria de pareceres desfavoraveis (sem o
     * coordenador ter votado favoravel).
     *
     * <p><b>O texto e institucional e impessoal de proposito — nao e questao
     * de estilo de redacao, e confidencialidade.</b> {@code motivoIndeferimento}
     * nao fica no sistema: ele e impresso no <i>oficio oficial</i> em PDF
     * ({@code OficioService}) e no corpo do e-mail enviado a <i>equipe
     * solicitante</i> ({@code EmailTemplateService}) — ou seja, sai da
     * instituicao e chega ao lado que esta sendo avaliado. Ja a tela de voto
     * do Portal do Avaliador promete ao medico, com todas as letras, que a
     * justificativa "sera registrada <i>internamente</i> para subsidiar a
     * decisao do processo" ({@code avaliador/votar.html}). Copiar para ca o
     * nome de quem votou contra e o texto clinico cru que ele escreveu
     * quebraria essa promessa e exporia os avaliadores ao solicitante,
     * comprometendo a imparcialidade dos proximos pareceres.</p>
     *
     * <p>As justificativas continuam gravadas em {@link Parecer#getJustificativa()}
     * para uso <b>interno</b> (operador, relatorio final, auditoria) — elas
     * apenas nao sao copiadas para este campo, que vira documento externo. O
     * operador que quiser um motivo mais detalhado pode escreve-lo a mao:
     * ADMIN reabre o processo em {@code /processos/{id}/reabrir} e redecide
     * pelo formulario da aba Decisao, que reenvia o motivo.</p>
     */
    private String gerarMotivoIndeferimentoAutomatico(Processo p) {
        long desfavoraveis = p.getPareceres().stream()
            .filter(par -> par.getResultado() == ResultadoParecer.NAO_FAVORAVEL)
            .count();
        return "Indeferido por decisao da maioria dos membros da Urgencia Renal ("
            + desfavoraveis + " de " + AVALIADORES_POR_PROCESSO
            + " pareceres desfavoraveis).";
    }

    /**
     * Retoma a analise apos a chegada da informacao complementar do solicitante:
     * tira o processo de SOLICITA_INFORMACAO e o devolve para ENVIADO (fluxo de
     * Respostas/Decisao), para que o(s) avaliador(es) concluam o voto. Limpa o
     * voto "Solicita informacao" dos pareceres que o usaram, para que o medico
     * registre o parecer definitivo (favoravel/nao favoravel). Processo ja
     * finalizado e erro de programacao do caller (mesma razao de
     * atualizarStatusPorPareceres).
     */
    @Transactional
    public Processo retomarAposInformacao(Long id) {
        Processo p = buscar(id);
        if (p.getStatus().isFinalizado()) {
            throw new IllegalStateException(ProcessoValidator.MSG_ENCERRADO);
        }
        p.getPareceres().stream()
            .filter(par -> par.getResultado() == ResultadoParecer.SOLICITA_INFORMACAO)
            .forEach(par -> {
                // Reset COMPLETO para pendencia limpa: o parecer volta a ser uma
                // pendencia genuina, sem metadados obsoletos do voto "Solicita
                // informacao" antigo (preserva o nao-repudio do voto definitivo).
                // Mantem dataEnvio (o processo foi enviado de fato).
                par.setResultado(null);
                par.setDataResposta(null);
                par.setDataHoraVoto(null);
                par.setVotadoPor(null);
                par.setOrigem(null);
                par.setJustificativa(null);
            });
        p.setStatus(StatusProcesso.ENVIADO);
        return processoRepository.save(p);
    }

    /** Atualiza apenas os dados descritivos do processo (numero e medicos nao mudam). */
    @Transactional
    public Processo atualizarDados(Long id, Processo form) {
        Processo p = buscar(id);
        // Defesa em profundidade: processo encerrado nao pode ser alterado
        // (o controller ja bloqueia; aqui garante que nenhum caminho escape).
        if (validator.edicaoBloqueada(p)) {
            throw new IllegalStateException(ProcessoValidator.MSG_ENCERRADO);
        }
        p.setPacienteNome(form.getPacienteNome());
        p.setPacienteRgct(form.getPacienteRgct());
        p.setSolicitanteEquipe(form.getSolicitanteEquipe());
        p.setSolicitanteEmail(form.getSolicitanteEmail());
        p.setDataSituacaoEspecial(form.getDataSituacaoEspecial());
        p.setObservacoes(form.getObservacoes());
        return processoRepository.save(p);
    }

    @Transactional
    public void excluir(Long id) {
        // Defesa em profundidade: apenas ADMIN pode excluir (OPERADOR edita,
        // mas nao exclui). O SecurityConfig ja barra a rota; aqui garante que
        // nenhum caminho de codigo escape mesmo se a rota for reconfigurada.
        exigirAdminParaExcluir();
        Processo p = buscar(id);
        // Defesa em profundidade: processo encerrado nao pode ser excluido
        // (o controller ja bloqueia; aqui garante que nenhum caminho escape).
        if (validator.edicaoBloqueada(p)) {
            throw new IllegalStateException(ProcessoValidator.MSG_ENCERRADO);
        }
        // Se o processo foi originado de uma SolicitacaoOnline (Portal do
        // Solicitante), essa linha ainda aponta pra ele via processo_gerado_id
        // (ManyToOne sem cascade/orphanRemoval a partir do Processo). Sem
        // desvincular primeiro, o DELETE do processo estoura violacao de FK.
        // A SolicitacaoOnline em si NAO e apagada - continua no historico do
        // portal, so perde o vinculo com o processo excluido; o status passa
        // a refletir isso via StatusSolicitacaoOnline.PROCESSO_EXCLUIDO, para
        // nao deixar a tela do solicitante presa em "Convertida em processo"
        // sem processo nenhum por tras.
        solicitacaoOnlineRepository.findByProcessoGeradoId(id).ifPresent(s -> {
            s.setProcessoGerado(null);
            s.setStatus(StatusSolicitacaoOnline.PROCESSO_EXCLUIDO);
            solicitacaoOnlineRepository.save(s);
        });
        processoRepository.delete(p);
    }

    /** Lanca AccessDeniedException se o usuario autenticado nao for ADMIN. */
    private void exigirAdminParaExcluir() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) {
            throw new AccessDeniedException("Apenas administradores podem excluir processos.");
        }
    }

    // As consultas de contagem/sugestao/anexos foram centralizadas em
    // ProcessoValidator; o servico expoe os mesmos metodos delegando a ele.

    public long contarFavoraveis(Processo processo) {
        return validator.contarFavoraveis(processo);
    }

    public long contarNaoFavoraveis(Processo processo) {
        return validator.contarNaoFavoraveis(processo);
    }

    public long contarRespondidos(Processo processo) {
        return validator.contarRespondidos(processo);
    }

    /**
     * Localiza o parecer de um processo especifico, garantindo que ele
     * pertence de fato a esse processo (evita um parecerId de outro processo
     * vazar por engano). Encapsula o acesso a {@link ParecerRepository} para
     * que os controllers nao precisem injeta-lo diretamente.
     */
    public Optional<Parecer> buscarParecer(Long processoId, Long parecerId) {
        return parecerRepository.findById(parecerId)
            .filter(par -> par.getProcesso().getId().equals(processoId));
    }

    /**
     * Pareceres pendentes (resultado nulo, envio ja registrado) de um processo
     * especifico - usado pelo lembrete manual de avaliacao pendente (individual
     * e em lote).
     */
    public List<Parecer> pareceresPendentesComEmail(Long processoId) {
        return parecerRepository.findByProcessoIdAndResultadoIsNullAndDataEnvioIsNotNull(processoId);
    }

    /**
     * Reivindica, de forma atomica no banco, o direito de mandar o convite ao
     * Portal do Avaliador para este parecer agora - usado por
     * {@code RegistroEnvioService.enviarConvitesAvaliadores} para nao mandar
     * o mesmo convite 2x quando o operador clica duas vezes (ou dois POSTs
     * concorrentes chegam) em "Registrar envio". {@code true} = pode enviar
     * (o timestamp ja foi gravado, fechando a janela para quem vier logo
     * depois); {@code false} = outra chamada ja reivindicou dentro da janela
     * de {@code janelaMinutos} - quem chamou deve tratar como duplicata (nao
     * como erro) e pular o envio deste avaliador.
     *
     * <p>Precisa de transacao propria porque delega a um {@code @Modifying}
     * de linha unica ({@link ParecerRepository#reivindicarConviteSeElegivel})
     * - sem ela o Spring Data lanca {@code TransactionRequiredException}.
     */
    @Transactional
    public boolean reivindicarConviteAvaliador(Long parecerId, int janelaMinutos) {
        LocalDateTime agora = LocalDateTime.now();
        LocalDateTime limiteJanela = agora.minusMinutes(janelaMinutos);
        return parecerRepository.reivindicarConviteSeElegivel(parecerId, agora, limiteJanela) > 0;
    }

    /** True se o processo esta encerrado e, portanto, com a edicao travada. */
    public boolean edicaoBloqueada(Processo processo) {
        return validator.edicaoBloqueada(processo);
    }

    public Optional<StatusProcesso> sugerirDecisao(Processo processo) {
        return validator.sugerirDecisao(processo);
    }

    public boolean temVotoCoordenadorFavoravel(Processo processo) {
        return validator.temVotoCoordenadorFavoravel(processo);
    }

    public boolean deferidoPeloCoordenador(Processo processo) {
        return validator.deferidoPeloCoordenador(processo);
    }

    public long favoraveisNecessariosParaDeferir(Processo processo) {
        return validator.favoraveisNecessariosParaDeferir(processo);
    }

    public long desfavoraveisNecessariosParaIndeferir() {
        return validator.desfavoraveisNecessariosParaIndeferir();
    }

    /** Registra a decisao final manual do servidor. */
    @Transactional
    public Processo decidir(Long id, StatusProcesso decisao, String motivoIndeferimento) {
        Processo p = buscar(id);
        // Processo ja encerrado nao pode ser redecidido sem antes reabrir (ADMIN).
        // O fluxo de reabertura volta o status para ENVIADO antes de redecidir.
        if (validator.edicaoBloqueada(p)) {
            throw new IllegalStateException(ProcessoValidator.MSG_ENCERRADO);
        }
        // Regras impostas em defesa (decidir() e publico e nao pode confiar apenas
        // na camada web): pausa por informacao complementar, votos suficientes,
        // motivo do indeferimento e anexo de toda resposta recebida. Centralizadas
        // em ProcessoValidator para nao divergirem das mesmas checagens no controller.
        validator.validarDecisao(p, decisao, motivoIndeferimento)
            .ifPresent(msg -> { throw new IllegalStateException(msg); });
        p.setStatus(decisao);
        p.setDataDecisao(LocalDateTime.now());
        if (decisao == StatusProcesso.INDEFERIDO) {
            p.setMotivoIndeferimento(motivoIndeferimento);
        }
        Processo salvo = processoRepository.save(p);
        // Espelha a decisao no status da SolicitacaoOnline de origem, se houver.
        // CANCELADO vira CANCELADA, nao REPROVADA: "Reprovada" diria ao
        // solicitante que a equipe analisou e negou o pedido dele, quando na
        // verdade o processo foi cancelado (as vezes pelo proprio solicitante,
        // via podeCancelar/cancelar no portal).
        solicitacaoOnlineRepository.findByProcessoGeradoId(id).ifPresent(s -> {
            s.setStatus(switch (decisao) {
                case DEFERIDO -> StatusSolicitacaoOnline.APROVADA;
                case CANCELADO -> StatusSolicitacaoOnline.CANCELADA;
                default -> StatusSolicitacaoOnline.REPROVADA;
            });
            solicitacaoOnlineRepository.save(s);
        });
        return salvo;
    }

    /**
     * Finaliza a resposta ao solicitante de forma automatica: envia o e-mail
     * com o template adequado (deferido/indeferido) + o anexo correspondente
     * (COMPROVANTE_SNT / OFICIO_INDEFERIMENTO), salva o texto da mensagem no
     * processo e marca emailEnviadoSolicitante=true. Exige que o documento
     * obrigatorio ja esteja anexado (validado por ProcessoValidator).
     *
     * <p><b>Ordem deliberada: envia o e-mail ANTES do save/commit.</b> Ver o
     * comentario no corpo do metodo — as duas ordens possiveis tem falhas
     * diferentes e a escolhida e a menos grave para este dominio.</p>
     */
    @Transactional
    public Processo finalizarResposta(Long id) {
        Processo p = buscar(id);
        if (!p.getStatus().isFinalizado()) {
            throw new IllegalStateException("Processo ainda nao foi decidido.");
        }
        if (p.getStatus() == StatusProcesso.CANCELADO) {
            throw new IllegalStateException("Processo cancelado nao gera resposta ao solicitante.");
        }
        if (p.isEmailEnviadoSolicitante()) {
            throw new IllegalStateException("Resposta ja foi enviada ao solicitante.");
        }
        validator.validarRespostaSolicitante(p)
            .ifPresent(msg -> { throw new IllegalStateException(msg); });

        if (p.getSolicitanteEmail() == null || p.getSolicitanteEmail().isBlank()) {
            throw new IllegalStateException(
                "Processo sem e-mail do solicitante cadastrado. Impossivel enviar resposta.");
        }

        EmailTemplate template = (p.getStatus() == StatusProcesso.DEFERIDO)
            ? emailTemplateService.emailDeferido(p)
            : emailTemplateService.emailIndeferido(p);

        TipoAnexo tipoAnexo = (p.getStatus() == StatusProcesso.DEFERIDO)
            ? TipoAnexo.COMPROVANTE_SNT
            : TipoAnexo.OFICIO_INDEFERIMENTO;
        Anexo anexo = anexoStorage.buscarUltimoPorTipo(p.getId(), tipoAnexo);

        // TRADE-OFF CONSCIENTE (nao e descuido): o e-mail sai ANTES do save,
        // portanto fora da garantia transacional. Se a transacao falhar depois
        // do envio (lock otimista, erro no commit), o solicitante recebe a
        // resposta mas o processo NAO fica marcado como respondido — o
        // operador reenvia e ele recebe duplicado.
        //
        // A alternativa (disparar em afterCommit via
        // TransactionSynchronizationManager) inverte a falha: o processo seria
        // marcado como respondido e, se o SMTP estivesse fora do ar naquele
        // instante, o e-mail simplesmente nunca sairia — sem ninguem perceber,
        // porque em afterCommit a falha de envio ja nao pode reverter nada
        // (o IllegalStateException abaixo deixaria de proteger).
        //
        // Neste dominio (oficio de saude publica, resposta a uma urgencia
        // renal) o segundo cenario e claramente pior: um processo constando
        // como "respondido" sem que a equipe solicitante tenha recebido o
        // deferimento/indeferimento e uma falha silenciosa com impacto
        // assistencial, enquanto uma resposta duplicada e so um incomodo
        // visivel e trivial de explicar. Mantida, por isso, a ordem atual:
        // se o SMTP falha, nada e gravado (rollback) e o operador tenta de
        // novo; o pior caso possivel e o solicitante ficar sabendo da decisao
        // duas vezes, nunca nenhuma.
        boolean enviado = emailSenderService.enviarComAnexo(
            p.getSolicitanteEmail(),
            template.assunto(),
            template.corpo(),
            anexoStorage.resolverArquivo(anexo).toFile(),
            anexo.getNomeArquivo());

        if (!enviado) {
            throw new IllegalStateException("Falha ao enviar e-mail para o solicitante. Verifique a configuracao de SMTP.");
        }

        p.setMensagemResposta(template.corpo());
        p.setEmailEnviadoSolicitante(true);
        return processoRepository.save(p);
    }

    /**
     * Reabre um processo ENCERRADO (Deferido/Indeferido/Cancelado), voltando-o
     * para {@link StatusProcesso#ENVIADO} para que o operador possa reavaliar e
     * decidir de novo. Limpa a decisao anterior (dataDecisao e motivo de
     * indeferimento); os pareceres sao mantidos como estao. Acao restrita ao
     * ADMIN (imposta no {@code SecurityConfig}). Lanca erro se o processo nao
     * estiver finalizado (nao ha o que reabrir).
     *
     * <p>Tambem desfaz o espelhamento da decisao na {@link SolicitacaoOnline}
     * de origem feito por {@link #decidir} (DEFERIDO -&gt; APROVADA /
     * INDEFERIDO -&gt; REPROVADA): sem isso a solicitacao fica presa em
     * APROVADA/REPROVADA e o Portal do Solicitante continua mostrando um
     * resultado definitivo para um processo que voltou para analise (o
     * template trata esses dois status com badge fixo, sem consultar o
     * processo).</p>
     */
    @Transactional
    public Processo reabrir(Long id) {
        Processo p = buscar(id);
        if (!p.getStatus().isFinalizado()) {
            throw new IllegalStateException("So e possivel reabrir processos encerrados.");
        }
        p.setStatus(StatusProcesso.ENVIADO);
        p.setDataDecisao(null);
        p.setMotivoIndeferimento(null);
        p.setEmailEnviadoSolicitante(false);
        p.setMensagemResposta(null);
        Processo salvo = processoRepository.save(p);
        // Devolve a solicitacao de origem para CONVERTIDA ("convertida em
        // processo, em andamento") — o unico status que o template do
        // solicitante resolve consultando processoGerado.status, refletindo
        // de novo o andamento real. So mexe se ela estiver justamente num dos
        // 3 estados que decidir() grava como espelho de uma decisao final
        // (APROVADA/REPROVADA/CANCELADA — ver o switch em decidir() acima);
        // PROCESSO_EXCLUIDO / DEVOLVIDA / ENVIADA nao tem nada a ver com a
        // decisao e nao podem ser sobrescritos aqui.
        //
        // CANCELADA entrou nessa lista em 2026-07-29 (junto do cancelamento
        // pelo solicitante, decidir(CANCELADO) -> CANCELADA): sem isso, reabrir
        // um processo cancelado deixava a SolicitacaoOnline presa em
        // "Cancelada" durante toda a reanalise, e o solicitante perdia a
        // capacidade de cancelar de novo (podeCancelar exige ENVIADA/CONVERTIDA).
        solicitacaoOnlineRepository.findByProcessoGeradoId(id).ifPresent(s -> {
            if (s.getStatus() == StatusSolicitacaoOnline.APROVADA
                    || s.getStatus() == StatusSolicitacaoOnline.REPROVADA
                    || s.getStatus() == StatusSolicitacaoOnline.CANCELADA) {
                s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
                solicitacaoOnlineRepository.save(s);
            }
        });
        return salvo;
    }

    private int extrairSequencial(String numero, int ano) {
        if (numero == null || numero.isBlank()) {
            return 0;
        }
        String parte = numero.split("/")[0].trim();
        try {
            return Integer.parseInt(parte);
        } catch (NumberFormatException e) {
            // fallback: proximo da sequencia do ano
            Integer max = processoRepository.findMaxSequencialByAno(ano);
            return (max == null ? 0 : max) + 1;
        }
    }
}

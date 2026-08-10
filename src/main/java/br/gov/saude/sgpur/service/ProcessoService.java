package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.Optional;

@Service
public class ProcessoService {

    private static final Logger log = LoggerFactory.getLogger(ProcessoService.class);

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
     *
     * <p><b>Achados C/D do docs/RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-
     * 2026-08.md — analise da janela de corrida (achado D):</b> o pre-filtro
     * abaixo usa {@link ProcessoValidator#temPedidoInformacaoAtivo}, que le a
     * colecao de pareceres do {@code Processo} recem-carregado por
     * {@code buscar(id)} NO INICIO desta transacao — ou seja, sempre o que
     * estiver commitado no banco naquele instante, nao um snapshot antigo.
     * Isso fecha a janela descrita no achado D: mesmo que a TX de
     * {@code atualizarStatusPorPareceres} de um voto B (SOLICITA_INFORMACAO)
     * ainda nao tivesse commitado quando a TX de {@code atualizarStatusPorPareceres}
     * de um voto C leu o status (deixando {@code Processo.status} "atrasado"
     * em ENVIADO), esta chamada a {@code tentarDecisaoAutomatica} (disparada
     * DEPOIS, pelo mesmo evento de voto de C) recarrega os pareceres do zero:
     * se o voto de B ja tiver commitado ate esse momento, o fato aparece e a
     * pausa bloqueia. A UNICA janela residual que sobra e mais estreita ainda
     * que a original: as DUAS transacoes de C (atualizarStatusPorPareceres E
     * tentarDecisaoAutomatica) teriam que terminar de ler ANTES do commit da
     * TX de B — uma corrida de poucos milissegundos entre 4 operacoes de
     * banco, sem nenhuma evidencia de ter ocorrido em producao (relatório,
     * secao 3.1: zero deferimentos com pedido de informacao ativo). Decisao
     * consciente: nao vale o custo/complexidade de lock pessimista ou
     * SERIALIZABLE para uma janela dessa magnitude — documentado aqui como
     * risco residual aceito, nao como pendencia.</p>
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
        //
        // Pausa ativa = status OU fato (existe parecer SOLICITA_INFORMACAO
        // vivo) — ver ProcessoValidator.temPedidoInformacaoAtivo/achado C.
        boolean pausaAtiva = p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO
            || validator.temPedidoInformacaoAtivo(p);
        if (pausaAtiva && !temVotoCoordenadorFavoravel(p)) {
            return p;
        }
        Optional<StatusProcesso> sugestao = sugerirDecisao(p);
        if (sugestao.isEmpty()) {
            return p;
        }
        StatusProcesso decisao = sugestao.get();
        // DEFERIDO dispensa motivo. INDEFERIDO tambem finaliza automaticamente
        // (decisao de produto confirmada): como o motivo e obrigatorio para o
        // oficio, geramos um texto institucional/impessoal (NUNCA o texto das
        // justificativas dos avaliadores - ver javadoc de
        // gerarMotivoIndeferimentoAutomatico, e a confidencialidade que ele
        // protege) — o operador pode ajusta-lo depois (ADMIN reabre o
        // processo em /processos/{id}/reabrir e redecide pelo mesmo
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

    /**
     * Grava a data/hora de agora como o ultimo lembrete manual enviado a este
     * parecer, chamado por {@code ProcessoDecisaoController.lembreteAvaliador}/
     * {@code lembretePendentes} SOMENTE apos a confirmacao de envio do e-mail
     * (nunca antes - se o SMTP falhar, o timestamp nao deve avancar). Precisa
     * de transacao propria pelo mesmo motivo de
     * {@link #reivindicarConviteAvaliador}: delega a um {@code @Modifying} de
     * linha unica, que exige uma transacao aberta.
     */
    @Transactional
    public void registrarLembreteAvaliador(Long parecerId) {
        parecerRepository.registrarUltimoLembrete(parecerId, LocalDateTime.now());
    }

    /**
     * Registra a data em que o documento correspondente entrou no sistema,
     * SEMPRE com a data de hoje (relogio do servidor) e NUNCA com uma data
     * digitada.
     *
     * <p><b>Regra do sistema (2026-08-04, decisao do usuario):</b> a data de
     * um ato registrado por anexo e o MOMENTO DO ANEXO. Antes, a aba
     * Finalizacao tinha campos {@code <input type="date">} para "data de
     * emissao do oficio", "data de envio do oficio" e "data de envio ao SNT" —
     * qualquer um deles aceitava data retroativa (ou futura), o que e
     * inadmissivel num processo administrativo: a data registrada precisa ser
     * a data real do ato, nao a que alguem escolheu digitar. Os campos e o
     * endpoint que os gravava foram removidos; quem chama este metodo sao os
     * proprios pontos de anexo/envio.
     *
     * <p>NAO se aplica a datas que sao FATO DO MUNDO REAL informado por
     * terceiro, e nao registro de um ato do sistema — {@code
     * dataSituacaoEspecial} (quando a situacao de urgencia comecou, informada
     * pela equipe solicitante) continua sendo um campo preenchido a mao, como
     * deve ser.
     *
     * <p>Transacao propria e carregamento por id DENTRO dela: altera a
     * entidade GERENCIADA (dirty check), sem depender de merge de entidade
     * desanexada com colecoes {@code cascade = ALL} nao inicializadas — mesmo
     * motivo documentado em {@code ProcessoAnexoController}.
     */
    @Transactional
    public void registrarDataEmissaoOficio(Long id) {
        Processo p = buscar(id);
        p.setDataEmissaoOficio(LocalDate.now());
        processoRepository.save(p);
    }

    /** Ver {@link #registrarDataEmissaoOficio}: data do anexo do comprovante SNT. */
    @Transactional
    public void registrarDataEnvioSnt(Long id) {
        Processo p = buscar(id);
        p.setDataEnvioSnt(LocalDate.now());
        processoRepository.save(p);
    }

    /**
     * Grava a data/hora de agora como o ultimo lembrete de comprovante SNT
     * enviado para este processo ({@code ComprovanteSntLembreteScheduler}),
     * SOMENTE apos a confirmacao de envio do e-mail. Transacao propria pelo
     * mesmo motivo de {@link #registrarLembreteAvaliador}: delega a um
     * {@code @Modifying} de linha unica.
     */
    @Transactional
    public void registrarLembreteComprovanteSnt(Long processoId) {
        processoRepository.registrarUltimoLembreteSnt(processoId, LocalDateTime.now());
    }

    /** Quantos processos Deferidos ainda nao tem o comprovante SNT anexado. */
    public long contarDeferidosSemComprovanteSnt() {
        return processoRepository.contarDeferidosSemComprovanteSnt();
    }

    /** Ids dos processos Deferidos sem comprovante SNT (badge de pendencia na lista). */
    public java.util.Set<Long> idsDeferidosSemComprovanteSnt() {
        return new java.util.HashSet<>(processoRepository.findIdsDeferidosSemComprovanteSnt());
    }

    /** Processos Deferidos sem comprovante SNT (filtro da lista), mais antigos primeiro. */
    public List<Processo> listarDeferidosSemComprovanteSnt() {
        return processoRepository.findDeferidosSemComprovanteSnt();
    }

    /**
     * Inicializa {@code pareceres}+{@code membro} e {@code anexos} para um
     * lote de processos ja carregados (ex.: a pagina de {@code /processos}),
     * evitando o N+1 de {@code FluxoProcessoService.pendenciaAberta} (que
     * navega as duas colecoes por processo). Precisa rodar DENTRO da mesma
     * transacao/persistence context do chamador - o resultado das duas
     * consultas e descartado de proposito, o efeito util e o Hibernate casar
     * as colecoes recem-carregadas com as MESMAS instancias gerenciadas de
     * {@code Processo} que ja estao em {@code processos} (identity map da
     * sessao), sem precisar substituir a lista original.
     *
     * <p><b>Bug real corrigido (2026-08-08):</b> {@code inicializarAnexos} e
     * uma unica consulta em lote que hidrata os anexos de TODOS os processos
     * recebidos - se um UNICO deles tiver um {@code Anexo.tipo} fora do enum
     * atual (dado legado/removido do enum, sem validacao pelo {@code
     * ddl-auto: update}), a consulta inteira lanca {@code
     * InvalidDataAccessApiUsageException} e derrubava a pagina inteira (500),
     * mesmo com dezenas de outros processos saudaveis na mesma pagina. A
     * falha e isolada aqui: se o pre-carregamento em lote falhar, cada
     * processo volta a carregar suas proprias colecoes sob demanda (lazy) -
     * mais lento (reintroduz o N+1 que este metodo existe para evitar), mas
     * so na hipotese rara de dado invalido, e o proprio acesso lazy por
     * processo ja e protegido individualmente por
     * {@code FluxoProcessoService.anexosSeguro}, entao so o(s) processo(s)
     * realmente afetado(s) perdem o calculo de pendencia - o resto da pagina
     * continua correto.</p>
     */
    @Transactional(readOnly = true)
    public void inicializarPareceresEAnexos(List<Processo> processos) {
        if (processos.isEmpty()) {
            return;
        }
        List<Long> ids = processos.stream().map(Processo::getId).toList();
        processoRepository.inicializarPareceresComMembro(ids);
        try {
            processoRepository.inicializarAnexos(ids);
        } catch (RuntimeException e) {
            log.warn("Falha ao pre-carregar anexos em lote (provavel Anexo.tipo com valor "
                + "fora do enum TipoAnexo atual, em algum processo do lote) - cada processo vai "
                + "carregar seus proprios anexos sob demanda: {}", e.getMessage());
        }
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

    /**
     * Qual regra decidiu (ou decide hoje) este processo - ver javadoc de
     * {@link ProcessoValidator#regraAplicada}. Wrapper fino, mesmo padrao
     * dos demais metodos deste bloco (delega ao validator).
     */
    public br.gov.saude.sgpur.service.dto.RegraDecisao regraAplicada(Processo processo) {
        return validator.regraAplicada(processo);
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
        // "Data de envio do oficio" e o momento em que o oficio de fato saiu
        // para a equipe solicitante - que e AQUI, no envio da resposta com o
        // PDF anexado. Antes era um <input type="date"> na tela, aceitando
        // qualquer data digitada (inclusive retroativa). Ver
        // registrarDataEmissaoOficio para a regra geral.
        if (p.getStatus() == StatusProcesso.INDEFERIDO) {
            p.setDataEnvioOficio(LocalDate.now());
        }
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
     *
     * <p><b>Achado C corrigido (docs/RELATORIO-BUG-DOIS-VOTOS-DEFEREM-DURANTE-
     * PAUSA-2026-08.md):</b> antes, este metodo forcava {@code ENVIADO}
     * incondicionalmente, mesmo que um parecer {@code SOLICITA_INFORMACAO}
     * continuasse vivo — cenario real: um avaliador pede informacao (pausa),
     * o processo e encerrado por um caminho que a pausa permite (Cancelado,
     * ou Deferido pelo coordenador), e o ADMIN reabre depois. A pausa
     * simplesmente deixava de existir para o sistema: dali em diante 2
     * favoraveis comuns (ou a varredura periodica) decidiam o processo com
     * um pedido de informacao nunca resolvido. Agora, logo apos setar
     * ENVIADO, {@link #atualizarStatusPorPareceres} recalcula o status a
     * partir do FATO (mesma fonte de {@link ProcessoValidator
     * #temPedidoInformacaoAtivo}): se ainda existir um parecer
     * {@code SOLICITA_INFORMACAO} nao resolvido, o status correto pos-reabertura
     * e {@code SOLICITA_INFORMACAO} (a pausa continua valendo), nao
     * {@code ENVIADO}. Sem nenhum parecer pendente de informacao, o resultado
     * e o mesmo de antes ({@code ENVIADO}) — nenhuma mudanca de comportamento
     * para o caso comum.</p>
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
        processoRepository.save(p);
        // Recalcula o status a partir dos pareceres de verdade: se ainda
        // houver um SOLICITA_INFORMACAO ativo, volta para a pausa em vez de
        // ficar preso em ENVIADO (achado C, ver javadoc acima).
        Processo salvo = atualizarStatusPorPareceres(id);
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

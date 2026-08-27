package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Regras do modulo experimental "Solicitacao Online" (Portal do
 * Solicitante). Ver docs/PLANO-SOLICITANTE.md para o desenho completo.
 *
 * Deliberadamente NAO manipula {@link Processo}/{@link Parecer} diretamente
 * para criar um pedido - so ao converter, e delegando para
 * {@code ProcessoService.cadastrar} (chamado pelo controller de triagem, nao
 * por este servico), preservando 100% das regras de negocio do processo.
 */
@Service
public class SolicitacaoOnlineService {

    private static final Logger log = LoggerFactory.getLogger(SolicitacaoOnlineService.class);

    /**
     * Formato permissivo de e-mail (mesmo espirito do {@code @Email} do
     * Jakarta Validation, mas validado ANTES do save - ver o comentario em
     * {@link #criar}). So confere a forma "algo@algo.algo", sem tentar cobrir
     * toda a RFC 5322 - suficiente para pegar erro de digitacao obvio.
     */
    private static final java.util.regex.Pattern EMAIL_REGEX =
        java.util.regex.Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final SolicitacaoOnlineRepository repository;
    private final AnexoSolicitacaoOnlineStorageService anexoStorage;
    private final AnexoStorageService anexoStorageProcesso;
    private final UsuarioRepository usuarioRepository;
    private final EmailSenderService emailSenderService;
    private final EmailTemplateService emailTemplateService;
    private final ProcessoService processoService;
    private final AuditoriaService auditoria;
    private final EmailDominioValidator emailDominioValidator;
    private final String baseUrl;

    public SolicitacaoOnlineService(SolicitacaoOnlineRepository repository,
                                    AnexoSolicitacaoOnlineStorageService anexoStorage,
                                    AnexoStorageService anexoStorageProcesso,
                                    UsuarioRepository usuarioRepository,
                                    EmailSenderService emailSenderService,
                                    EmailTemplateService emailTemplateService,
                                    ProcessoService processoService,
                                    AuditoriaService auditoria,
                                    EmailDominioValidator emailDominioValidator,
                                    @Value("${app.base-url:http://localhost:3000}") String baseUrl) {
        this.repository = repository;
        this.anexoStorage = anexoStorage;
        this.anexoStorageProcesso = anexoStorageProcesso;
        this.usuarioRepository = usuarioRepository;
        this.emailSenderService = emailSenderService;
        this.emailTemplateService = emailTemplateService;
        this.processoService = processoService;
        this.auditoria = auditoria;
        this.emailDominioValidator = emailDominioValidator;
        this.baseUrl = baseUrl;
    }

    public SolicitacaoOnline buscar(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Solicitacao online nao encontrada: " + id));
    }

    /**
     * Nome real de quem enviou a solicitacao, para rotular o "outro lado" nos
     * chats do operador (em vez do literal generico "Solicitante" usado ate
     * 2026-08-07). Cai de volta em "Solicitante" se o nome vier nulo/em
     * branco, sem quebrar a tela.
     */
    public String nomeSolicitante(Long solicitacaoOnlineId) {
        return repository.findNomeSolicitanteById(solicitacaoOnlineId)
            .filter(n -> n != null && !n.isBlank())
            .orElse("Solicitante");
    }

    /**
     * Versao de {@link #buscar(Long)} para as TELAS DE DETALHE (portal do
     * solicitante e triagem do operador), que renderizam anexos e o processo
     * gerado. Com {@code open-in-view: false} o template roda fora da
     * transacao, entao as associacoes precisam vir carregadas daqui - senao da
     * {@code LazyInitializationException} e o usuario ve 500.
     */
    public SolicitacaoOnline buscarParaDetalhe(Long id) {
        return repository.findParaDetalhe(id)
            .orElseThrow(() -> new IllegalArgumentException("Solicitacao online nao encontrada: " + id));
    }

    public List<SolicitacaoOnline> listarMinhas(Long usuarioSolicitanteId) {
        return repository.findMinhasParaLista(usuarioSolicitanteId);
    }

    /**
     * Estado da pausa "Solicita informacao" como o Portal do Solicitante
     * precisa ve-la — <b>FONTE UNICA</b>, calculada de uma vez so a partir
     * do MESMO conjunto de dados (pareceres + anexos do processo).
     *
     * <p><b>Bug real que este record corrige</b> (relato do dono do produto
     * em producao, processo 12/2026, 2026-08-11): a tela do solicitante
     * mostrava, ao longo do tempo e <i>sem nenhuma acao dele</i>, dois
     * estados contraditorios ("precisa enviar" x "ja enviou"). A causa era
     * o modelo antigo tratar a pausa como UMA "rodada" global com UM unico
     * instante inicial ({@code max(Parecer.dataHoraVoto)} entre os pedidos),
     * quando na verdade cada avaliador abre um <b>pedido independente</b> —
     * e mais de um pode estar aberto ao mesmo tempo (em 12/2026 eram DOIS;
     * nada no codigo impede os TRES). Com N pedidos, um pedido novo
     * empurrava o inicio da "rodada" para frente e <i>apagava
     * retroativamente</i> a resposta que o solicitante ja tinha enviado:
     * o cartao voltava sozinho de "Informacoes complementares recebidas"
     * para "Informacao complementar necessaria", relistando inclusive o
     * pedido ja respondido.</p>
     *
     * <p>Agora cada pedido e avaliado individualmente: respondido = existe
     * anexo {@link TipoAnexo#INFO_COMPLEMENTAR} enviado DEPOIS daquele
     * pedido especifico. Vale para N de 1 a
     * {@link ProcessoService#AVALIADORES_POR_PROCESSO}.</p>
     *
     * @param pausaAtiva      pausa vigente (status OU fato, ver
     *                        {@code ProcessoValidator.temPedidoInformacaoAtivo})
     * @param totalPedidos    quantos avaliadores estao com pedido em aberto
     * @param textosPendentes justificativas dos pedidos AINDA nao respondidos,
     *                        na ordem dos pareceres (sem os ja respondidos —
     *                        relistar um pedido ja atendido foi exatamente o
     *                        que confundiu o solicitante em producao)
     * @param pedidosPendentes quantos pedidos seguem sem resposta
     */
    public record EstadoInformacaoComplementar(boolean pausaAtiva, int totalPedidos,
            List<String> textosPendentes, int pedidosPendentes) {

        static final EstadoInformacaoComplementar SEM_PAUSA =
            new EstadoInformacaoComplementar(false, 0, List.of(), 0);

        /** O solicitante precisa agir: ha pelo menos um pedido sem resposta. */
        public boolean precisaEnviar() {
            return pausaAtiva && pedidosPendentes > 0;
        }

        /** Pausa vigente, mas TODOS os pedidos abertos ja foram respondidos. */
        public boolean jaEnviouTudo() {
            return pausaAtiva && pedidosPendentes == 0;
        }
    }

    /**
     * Calcula {@link EstadoInformacaoComplementar} — chame UMA vez por
     * requisicao e derive tudo dali (nunca recalcule "precisa enviar" e "ja
     * enviou" separadamente: foi a divergencia entre duas leituras que gerou
     * o bug documentado no record).
     */
    public EstadoInformacaoComplementar estadoInformacaoComplementar(SolicitacaoOnline s) {
        Processo processo = s.getProcessoGerado();
        if (s.getStatus() != StatusSolicitacaoOnline.CONVERTIDA || processo == null) {
            return EstadoInformacaoComplementar.SEM_PAUSA;
        }
        List<Parecer> pedidos = processo.getPareceres().stream()
            .filter(par -> par.getResultado() == ResultadoParecer.SOLICITA_INFORMACAO)
            .toList();
        // Pausa ativa = status OU fato observavel. O resto do sistema
        // (ProcessoValidator.validarPausaDecisao, FluxoProcessoService,
        // card de Respostas do operador) ja usava esse OU desde o Achado 7 do
        // relatorio de status de 2026-08-11; SO o Portal do Solicitante
        // continuava olhando apenas o campo derivado Processo.status — duas
        // fontes de verdade diferentes para a MESMA pergunta, que e como as
        // duas telas passaram a contar historias diferentes.
        boolean pausaAtiva = processo.getStatus() == StatusProcesso.SOLICITA_INFORMACAO
            || !pedidos.isEmpty();
        if (!pausaAtiva) {
            return EstadoInformacaoComplementar.SEM_PAUSA;
        }
        LocalDateTime ultimoEnvio = processo.getAnexos().stream()
            .filter(a -> a.getTipo() == TipoAnexo.INFO_COMPLEMENTAR)
            .map(Anexo::getDataUpload)
            .filter(java.util.Objects::nonNull)
            .max(LocalDateTime::compareTo)
            .orElse(null);
        if (pedidos.isEmpty()) {
            // Status diz SOLICITA_INFORMACAO mas nenhum parecer identifica o
            // pedido (dessincronizacao do campo derivado, ou dado legado). Sem
            // instante de referencia, mantem o comportamento historico global:
            // pendente enquanto nao houver NENHUM envio de informacao
            // complementar. Nunca deixa o solicitante sem caminho de resposta.
            return new EstadoInformacaoComplementar(true, 0, List.of(), ultimoEnvio == null ? 1 : 0);
        }
        List<Parecer> pendentes = pedidos.stream()
            .filter(par -> !pedidoRespondido(par, ultimoEnvio))
            .toList();
        List<String> textos = pendentes.stream()
            .map(Parecer::getJustificativa)
            .filter(j -> j != null && !j.isBlank())
            .map(String::trim)
            .toList();
        return new EstadoInformacaoComplementar(true, pedidos.size(), textos, pendentes.size());
    }

    /**
     * Um pedido especifico foi respondido? So se houver um envio de
     * informacao complementar POSTERIOR a ele.
     *
     * <p>{@code dataHoraVoto} nulo (parecer legado, anterior ao voto
     * autenticado do Portal do Avaliador, ou construido em teste) nao tem
     * instante conhecido: mantem-se o comportamento historico permissivo —
     * qualquer envio ja existente conta como resposta —, para nunca deixar o
     * solicitante preso num pedido que ele nao tem como "responder de
     * novo".</p>
     */
    private boolean pedidoRespondido(Parecer pedido, LocalDateTime ultimoEnvio) {
        if (ultimoEnvio == null) {
            return false;
        }
        LocalDateTime pedidoEm = pedido.getDataHoraVoto();
        return pedidoEm == null || ultimoEnvio.isAfter(pedidoEm);
    }

    /**
     * Verdadeiro quando o pedido ja virou {@link Processo} e esse processo
     * esta pausado aguardando informacao complementar de um avaliador
     * (ver regra "Solicita informacao (PAUSA)" no CLAUDE.md).
     *
     * <p>Delega a {@link #estadoInformacaoComplementar} — nao reimplementar
     * a condicao aqui.</p>
     */
    public boolean precisaInformacaoComplementar(SolicitacaoOnline s) {
        return estadoInformacaoComplementar(s).pausaAtiva();
    }

    /**
     * Verdadeiro se o solicitante ja respondeu a TODOS os pedidos de
     * informacao em aberto (com N pedidos simultaneos, responder um so nao
     * basta). Delega a {@link #estadoInformacaoComplementar}.
     */
    public boolean jaEnviouInformacaoComplementarNestaRodada(SolicitacaoOnline s) {
        return estadoInformacaoComplementar(s).jaEnviouTudo();
    }

    /**
     * Recebe a resposta de informacao complementar enviada pelo SOLICITANTE
     * diretamente no portal, como alternativa ao e-mail externo: um TEXTO
     * digitado, ARQUIVO(S), ou os dois. So grava anexo(s)
     * {@code TipoAnexo.INFO_COMPLEMENTAR} no {@link Processo} - quem decide
     * retomar a analise continua sendo exclusivamente o OPERADOR via
     * {@code ProcessoService.retomarAposInformacao} (este metodo NUNCA muda o
     * status do processo).
     *
     * <p><b>Texto aceito desde 2026-08-11:</b> antes o metodo exigia ao menos
     * um arquivo, o que obrigava o solicitante a produzir um documento so
     * para responder algo que cabe em duas linhas ("o paciente foi
     * transplantado em 05/08", "a creatinina de ontem foi X"). Agora basta
     * <b>texto OU arquivo</b> (nunca os dois obrigatorios). O texto vira um
     * {@code .txt} pelo MESMO pipeline de anexo
     * ({@link AnexoStorageService#salvarTexto}) - nenhum schema novo, e o
     * operador continua vendo tudo no mesmo lugar do card de Respostas.</p>
     *
     * <p><b>O que entra aqui NUNCA chega ao avaliador</b> (pode citar o nome
     * do paciente/equipe). Ver {@code TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR}
     * e {@code InfoComplementarAvaliadorService} para o caminho revisado.</p>
     *
     * Revalida o estado aqui dentro (defesa em profundidade, mesmo padrao de
     * {@code ProcessoService.decidir}): cobre a corrida em que o operador ja
     * retomou a analise entre a tela abrir e o solicitante enviar.
     */
    @Transactional
    public void enviarInformacaoComplementar(SolicitacaoOnline s, String texto, List<MultipartFile> arquivos) {
        // Estado calculado UMA vez: as duas guardas abaixo tem que enxergar
        // exatamente o mesmo cenario (ver EstadoInformacaoComplementar).
        EstadoInformacaoComplementar estado = estadoInformacaoComplementar(s);
        if (!estado.pausaAtiva()) {
            throw new IllegalStateException(
                "Este pedido nao esta aguardando informacao complementar no momento.");
        }
        if (estado.jaEnviouTudo()) {
            throw new IllegalStateException(
                "Voce ja enviou as informacoes complementares para esta solicitacao. "
                + "Aguarde a analise da equipe de Urgencia Renal.");
        }
        boolean algumArquivo = arquivos != null && arquivos.stream().anyMatch(a -> a != null && !a.isEmpty());
        String textoLimpo = (texto == null || texto.isBlank()) ? null : texto.trim();
        if (!algumArquivo && textoLimpo == null) {
            throw new IllegalArgumentException(
                "Escreva a resposta no campo de texto ou anexe pelo menos um arquivo.");
        }
        if (textoLimpo != null) {
            try {
                anexoStorageProcesso.salvarTexto(s.getProcessoGerado(), TipoAnexo.INFO_COMPLEMENTAR,
                    "Resposta em texto enviada pelo solicitante via Portal do Solicitante",
                    "resposta-informacao-complementar-"
                        + LocalDateTime.now().format(java.time.format.DateTimeFormatter
                            .ofPattern("yyyyMMdd-HHmmss")) + ".txt",
                    textoLimpo);
            } catch (IOException e) {
                // Mesmo racional do catch do loop de arquivos abaixo: IOException
                // e checked e nao dispara rollback sozinha.
                throw new IllegalStateException(
                    "Falha ao salvar a resposta em texto: " + e.getMessage(), e);
            }
        }
        if (!algumArquivo) {
            return;
        }
        for (MultipartFile arquivo : arquivos) {
            if (arquivo == null || arquivo.isEmpty()) {
                continue;
            }
            try {
                anexoStorageProcesso.salvar(s.getProcessoGerado(), TipoAnexo.INFO_COMPLEMENTAR,
                    "Resposta com informacoes complementares enviada pelo solicitante via Portal do Solicitante",
                    arquivo);
            } catch (IOException e) {
                // IOException e checked - sem envolver numa RuntimeException, o Spring
                // NAO faz rollback (so reverte @Transactional em RuntimeException/Error
                // por padrao) e os anexos ja salvos neste loop ficariam commitados
                // mesmo com a falha. Mesmo padrao ja usado em criar() (acima).
                throw new IllegalStateException(
                    "Falha ao salvar arquivo enviado: " + e.getMessage(), e);
            }
        }
    }

    public List<SolicitacaoOnline> listarPendentesTriagem() {
        return repository.findByStatusOrderByDataEnvioAsc(StatusSolicitacaoOnline.ENVIADA);
    }

    /**
     * Mesma lista de {@link #listarPendentesTriagem()}, com busca por
     * paciente, RGCT ou equipe solicitante resolvida no banco
     * ({@code SolicitacaoOnlineRepository.buscarPorStatus}). {@code q}
     * nulo/vazio devolve todas as pendentes.
     */
    public List<SolicitacaoOnline> listarPendentesTriagem(String q) {
        return repository.buscarPorStatus(StatusSolicitacaoOnline.ENVIADA, q);
    }

    /** Contagem de pendentes de triagem, para o badge da navbar - evita carregar a lista inteira. */
    public long contarPendentesTriagem() {
        return repository.countByStatus(StatusSolicitacaoOnline.ENVIADA);
    }

    /** Todas as solicitacoes, qualquer status, mais recentes primeiro (aba "Todas" da triagem). */
    public List<SolicitacaoOnline> listarTodas() {
        return repository.findAllByOrderByDataEnvioDesc();
    }

    /**
     * Mesma lista de {@link #listarTodas()}, com a mesma busca de
     * {@link #listarPendentesTriagem(String)}. {@code q} nulo/vazio devolve
     * todas as solicitacoes.
     */
    public List<SolicitacaoOnline> listarTodas(String q) {
        return repository.buscarTodas(q);
    }

    /**
     * Dias corridos desde o envio, com a classe de cor Bootstrap para
     * destacar espera longa na fila de triagem (formatacao pronta aqui, nunca
     * calculada na view - mesmo padrao de {@code TempoRespostaService}).
     * Limiares: acima de 7 dias = alerta (vermelho), acima de 3 = atencao
     * (amarelo), caso contrario neutro.
     */
    public DiasEspera diasEspera(SolicitacaoOnline s) {
        long dias = java.time.Duration.between(s.getDataEnvio(), LocalDateTime.now()).toDays();
        String cssClass = dias > 7 ? "bg-danger" : dias > 3 ? "bg-warning text-dark" : "bg-secondary";
        return new DiasEspera(dias, cssClass);
    }

    /** Dias de espera + classe de cor Bootstrap pronta para o badge (ver {@link #diasEspera}). */
    public record DiasEspera(long dias, String badgeClass) {
    }

    /**
     * Contagem por status de uma lista de solicitacoes ja carregada (ex.: as
     * do proprio solicitante em {@code listarMinhas}) - usado nos cards de
     * resumo do Portal do Solicitante. Calculo puro em memoria, sem query
     * adicional, para nao acoplar a tela a uma nova consulta ao banco.
     */
    public Resumo resumir(List<SolicitacaoOnline> solicitacoes) {
        long aguardandoTriagem = 0;
        long emAnalise = 0;
        long decididas = 0;
        long devolvidas = 0;
        for (SolicitacaoOnline s : solicitacoes) {
            switch (s.getStatus()) {
                case ENVIADA -> aguardandoTriagem++;
                case CONVERTIDA -> {
                    if (s.getProcessoGerado() != null && s.getProcessoGerado().getStatus().isFinalizado()) {
                        decididas++;
                    } else {
                        emAnalise++;
                    }
                }
                case APROVADA, REPROVADA, CANCELADA, PROCESSO_EXCLUIDO -> decididas++;
                case DEVOLVIDA -> devolvidas++;
            }
        }
        return new Resumo(solicitacoes.size(), aguardandoTriagem, emAnalise, decididas, devolvidas);
    }

    /** Resumo por status para os cards de estatistica da tela "Minhas solicitacoes". */
    public record Resumo(long total, long aguardandoTriagem, long emAnalise, long decididas, long devolvidas) {
    }

    /**
     * Cria uma nova solicitacao (status ENVIADA) e anexa os documentos
     * clinicos enviados junto. Equipe/e-mail do solicitante SEMPRE vem do
     * {@code Usuario} logado (nunca do formulario) - evita que o solicitante
     * se declare de outra equipe.
     *
     * <p><b>Nao ha copia campo a campo aqui</b> (ao contrario de
     * {@code UsuarioService.atualizar} / {@code ControleUrgenciaService.atualizar}):
     * a propria entidade e o objeto do {@code @ModelAttribute}, entao os campos
     * do formulario {@code solicitante/nova.html} - {@code pacienteNome},
     * {@code pacienteRgct}, {@code dataSituacaoEspecial},
     * {@code justificativaClinica} - ja chegam preenchidos e sao persistidos
     * sem intermediario. Nao existe, por construcao, o risco de "esquecer de
     * copiar um campo novo do formulario" nesse caminho.
     *
     * <p><b>Sobrescritos DE PROPOSITO</b> logo abaixo (defesa contra mass
     * assignment - nao remover achando que e bug): {@code id} (forcado a null,
     * senao um POST com id sequestraria a solicitacao de outro),
     * {@code usuarioSolicitante} / {@code solicitanteEquipe} /
     * {@code solicitanteEmail} (vem do usuario logado), {@code status}
     * (sempre ENVIADA), {@code processoGerado} e {@code observacoesTriagem}
     * (so a triagem preenche) e {@code dataEnvio} (ordena a fila de triagem;
     * precisa ser o instante real do envio). {@code SolicitacaoOnline} nao tem
     * nenhum metodo de EDICAO de campos apos o envio - o solicitante so pode
     * cancelar enquanto nao triado, e a triagem so muda status/observacoes.
     */
    @Transactional
    public SolicitacaoOnline criar(SolicitacaoOnline solicitacao, Usuario usuarioLogado,
                                   List<MultipartFile> documentos) {
        if (usuarioLogado.getEquipeSolicitante() == null || usuarioLogado.getEquipeSolicitante().isBlank()) {
            throw new IllegalStateException(
                "Usuario solicitante sem equipe vinculada. Contate o administrador.");
        }
        if (solicitacao.getPacienteDataNascimento() == null) {
            throw new IllegalArgumentException("Informe a data de nascimento do paciente.");
        }
        if (solicitacao.getPacienteDataNascimento().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("A data de nascimento do paciente nao pode ser no futuro.");
        }
        if (solicitacao.getPacienteSexo() == null) {
            throw new IllegalArgumentException("Informe o sexo do paciente.");
        }
        String cpfDigits = CpfUtil.normalizar(solicitacao.getPacienteCpf());
        if (cpfDigits.isBlank()) {
            throw new IllegalArgumentException("Informe o CPF do paciente.");
        }
        if (!CpfUtil.valido(cpfDigits)) {
            throw new IllegalArgumentException("CPF do paciente invalido. Confira os digitos informados.");
        }
        solicitacao.setPacienteCpf(cpfDigits);
        // Defesa em profundidade contra duplo-submit (2026-08-27): a causa raiz
        // real era o formulario solicitante/nova.html marcar data-lock-submit
        // sem incluir o script que le esse atributo (lockSubmitScript), entao o
        // botao "Enviar solicitacao" nunca era desabilitado apos o clique - um
        // POST classico (nao-AJAX) com upload de anexos deixa a pagina
        // interativa por varios segundos, o suficiente para um segundo clique
        // disparar um segundo POST identico. Corrigido no template, mas essa
        // checagem cobre tambem reenvio manual (F5, aba antiga reaberta) que
        // nao depende de nenhum estado client-side. Duas solicitacoes
        // genuinamente distintas para o MESMO paciente enviadas pelo MESMO
        // usuario em menos de 15s seriam uma coincidencia extrema, nao um caso
        // de uso real - ver
        // docs/RELATORIO-BUG-DUPLICACAO-E-COBERTURA-BADGE-PREEMPTIVO-2026-08-27.md.
        if (repository.existsByUsuarioSolicitanteIdAndPacienteCpfAndDataEnvioAfter(
                usuarioLogado.getId(), cpfDigits, LocalDateTime.now().minusSeconds(15))) {
            throw new IllegalStateException(
                "Ja recebemos uma solicitacao para este paciente ha poucos segundos. "
                + "Aguarde um instante e confira em \"Minhas solicitacoes\" antes de enviar novamente.");
        }
        // RGCT condicionalmente obrigatorio (paciente preemptivo, 2026-08-27):
        // ainda nao esta na lista de espera do SNT, entao nao tem RGCT - ver
        // javadoc de SolicitacaoOnline.pacienteRgct/Processo.pacienteRgct.
        if (!solicitacao.isPreemptivo()
                && (solicitacao.getPacienteRgct() == null || solicitacao.getPacienteRgct().isBlank())) {
            throw new IllegalArgumentException("Informe o RGCT/SNT do paciente.");
        }
        if (solicitacao.isPreemptivo()) {
            solicitacao.setPacienteRgct(null); // normaliza "" -> null, nunca string vazia no banco
        }
        // Campo opcional (2026-08-21): "" (input vazio submetido pelo navegador) vira
        // null, nunca uma string em branco gravada no banco - mesmo tratamento que
        // qualquer outro campo opcional de texto do sistema (ex. pacienteNomeMae, que
        // ja chega assim do @ModelAttribute sem normalizacao extra por ser TEXT puro;
        // aqui normalizamos porque "" IS blank mas nao e null, e o restante do sistema
        // (CC nos e-mails, exibicao condicional nos templates) testa null/isBlank).
        //
        // Validado EXPLICITAMENTE aqui (IllegalArgumentException, nao so pela anotacao
        // @Email da entidade) de proposito: sem @Valid neste @ModelAttribute, um valor
        // invalido so seria pego pela validacao automatica do Hibernate no momento do
        // repository.save() (jakarta.persistence.validation.mode=AUTO), que lanca
        // ConstraintViolationException - excecao SEM @ExceptionHandler dedicado neste
        // projeto (cai no handler generico de 500), diferente do redirect gracioso com
        // o campo destacado que o catch (IllegalArgumentException) do controller ja
        // devolve para os demais campos. Checando aqui, o erro segue o MESMO caminho
        // amigavel dos outros.
        if (solicitacao.getEmailAdicional() != null && !solicitacao.getEmailAdicional().isBlank()) {
            String emailAdicional = solicitacao.getEmailAdicional().trim();
            if (!EMAIL_REGEX.matcher(emailAdicional).matches()) {
                throw new IllegalArgumentException("E-mail adicional invalido. Confira o endereco informado.");
            }
            // Checagem leve de dominio (achado real de vistoria, 2026-08-24):
            // pega erro de digitacao obvio no dominio (ex. "gmial.com") que o
            // regex acima nao detecta. Fail-open por design (ver javadoc de
            // EmailDominioValidator) - so bloqueia quando o dominio nao
            // resolve de jeito nenhum (nem MX nem A/AAAA), nunca por falha
            // transitoria de rede/DNS do proprio servidor.
            if (!emailDominioValidator.dominioResolvivel(emailAdicional)) {
                throw new EmailDominioInvalidoException(
                    "E-mail adicional invalido: o dominio \"" + emailAdicional.substring(emailAdicional.indexOf('@') + 1)
                    + "\" nao existe. Confira o endereco informado.");
            }
            solicitacao.setEmailAdicional(emailAdicional);
        } else {
            solicitacao.setEmailAdicional(null);
        }
        solicitacao.setId(null);
        solicitacao.setUsuarioSolicitante(usuarioLogado);
        solicitacao.setSolicitanteEquipe(usuarioLogado.getEquipeSolicitante());
        solicitacao.setSolicitanteEmail(usuarioLogado.getEmail());
        solicitacao.setStatus(StatusSolicitacaoOnline.ENVIADA);
        solicitacao.setProcessoGerado(null);
        solicitacao.setObservacoesTriagem(null);
        // Nunca confia no dataEnvio vindo do formulario (o binding do @ModelAttribute
        // poderia receber um valor forjado) - a fila de triagem ordena por esta data,
        // entao ela precisa refletir o momento real do envio.
        solicitacao.setDataEnvio(LocalDateTime.now());
        SolicitacaoOnline salva = repository.save(solicitacao);

        if (documentos != null) {
            for (MultipartFile arquivo : documentos) {
                if (arquivo == null || arquivo.isEmpty()) {
                    continue;
                }
                try {
                    salva.addAnexo(anexoStorage.salvar(salva, arquivo));
                } catch (IOException e) {
                    throw new IllegalStateException("Falha ao salvar documento anexado: " + e.getMessage(), e);
                }
            }
        }
        notificarOperadores(salva);
        return salva;
    }

    /**
     * Avisa ADMIN/OPERADOR ativos (com e-mail cadastrado) que ha uma nova
     * solicitacao aguardando triagem. Best-effort: falha de envio so gera log,
     * nunca impede a solicitacao de ser criada (o pedido do solicitante ja
     * foi salvo com sucesso nesse ponto).
     */
    private void notificarOperadores(SolicitacaoOnline s) {
        List<Usuario> destinatarios = usuarioRepository
            .findByPerfilInAndAtivoTrue(List.of(Perfil.ADMIN, Perfil.OPERADOR));
        String[] emails = destinatarios.stream()
            .map(Usuario::getEmail)
            .filter(e -> e != null && !e.isBlank())
            .distinct()
            .toArray(String[]::new);
        if (emails.length == 0) {
            log.warn("SolicitacaoOnlineService: nenhum ADMIN/OPERADOR com e-mail cadastrado - "
                + "notificacao da solicitacao {} nao enviada.", s.getId());
            return;
        }
        // Bloco de identificacao (destinatario e ADMIN/OPERADOR interno, nunca
        // avaliador - CPF/data de nascimento sao nullable em solicitacao antiga
        // e so entram no e-mail quando ja preenchidos, mesmo criterio de
        // EmailTemplateService.blocoIdentificacaoPaciente).
        StringBuilder identificacao = new StringBuilder();
        identificacao.append("Paciente: ").append(s.getPacienteNome()).append('\n');
        identificacao.append("Tipo: ").append(s.isPreemptivo()
            ? "Preemptivo (inserção em lista de espera)" : "Urgência renal").append('\n');
        if (s.getPacienteRgct() != null && !s.getPacienteRgct().isBlank()) {
            identificacao.append("RGCT/SNT: ").append(s.getPacienteRgct()).append('\n');
        }
        if (s.getPacienteCpf() != null && !s.getPacienteCpf().isBlank()) {
            identificacao.append("CPF: ").append(CpfUtil.formatar(s.getPacienteCpf())).append('\n');
        }
        if (s.getPacienteDataNascimento() != null) {
            identificacao.append("Data de nascimento: ")
                .append(s.getPacienteDataNascimento().format(
                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                .append('\n');
        }
        identificacao.append("Equipe solicitante: ").append(s.getSolicitanteEquipe());

        String corpo = """
            Uma nova solicitacao de urgencia renal foi enviada pelo Portal do Solicitante.

            %s

            Acesse a fila de triagem para revisar e prosseguir com o cadastro do processo:
            %s/processos/solicitacoes-online/%d
            """.formatted(identificacao, baseUrl, s.getId());
        emailSenderService.enviar(emails, null,
            "Nova solicitacao online aguardando triagem - " + s.getPacienteNome(), corpo);
    }

    /**
     * True se o solicitante ainda pode cancelar este pedido. Fonte UNICA da
     * regra: a tela pergunta a este metodo se mostra o botao, e
     * {@link #cancelar(Long, Long)} pergunta a ele antes de efetivar - sem isso
     * as duas condicoes divergem e o botao aparece para um pedido que o servico
     * vai recusar.
     *
     * <p>Duas janelas:
     * <ul>
     *   <li>{@code ENVIADA} - ainda nem foi triada pelo operador;</li>
     *   <li>{@code CONVERTIDA} com o processo gerado <b>ainda nao decidido</b> -
     *       o pedido virou processo mas ninguem bateu o martelo. O caso real e
     *       o paciente ter sido transplantado, ter falecido ou o pedido ter
     *       sido aberto por engano enquanto os 3 medicos ja analisam.</li>
     * </ul>
     *
     * <p>Depois da decisao final (Deferido/Indeferido) NAO cancela mais: o
     * desfecho ja existe, com oficio/comprovante e resposta ao solicitante.
     */
    public boolean podeCancelar(SolicitacaoOnline s) {
        if (s.getStatus() == StatusSolicitacaoOnline.ENVIADA) {
            return true;
        }
        if (s.getStatus() != StatusSolicitacaoOnline.CONVERTIDA || s.getProcessoGerado() == null) {
            return false;
        }
        return !s.getProcessoGerado().getStatus().isFinalizado();
    }

    /**
     * Cancela a propria solicitacao. Verifica posse (o dono precisa ser quem
     * cancela) e a janela de {@link #podeCancelar(SolicitacaoOnline)}.
     *
     * <p>Quando o pedido ja virou processo, o cancelamento e delegado a
     * {@code processoService.decidir(id, CANCELADO, null)} em vez de trocar o
     * status na mao: e o mesmo caminho do cancelamento pelo operador, entao
     * passa pelas mesmas travas (processo encerrado nao redecide) e grava
     * {@code dataDecisao}. {@code decidir} ja espelha CANCELADO como
     * {@code CANCELADA} na solicitacao de origem.
     *
     * @return o id do processo cancelado junto, ou {@code null} se o pedido
     *         ainda nem tinha processo - o chamador usa isso para saber se
     *         precisa avisar os avaliadores.
     */
    @Transactional
    public Long cancelar(Long id, Long usuarioLogadoId) {
        SolicitacaoOnline s = buscar(id);
        if (!s.getUsuarioSolicitante().getId().equals(usuarioLogadoId)) {
            throw new IllegalStateException("Voce so pode cancelar as suas proprias solicitacoes.");
        }
        if (!podeCancelar(s)) {
            // Mensagem especifica so quando o motivo real e "ja foi decidido" -
            // dizer isso de um pedido sem processo confundiria o solicitante.
            boolean processoJaDecidido = s.getProcessoGerado() != null
                && s.getProcessoGerado().getStatus().isFinalizado();
            throw new IllegalStateException(processoJaDecidido
                ? "Este processo ja foi decidido pela equipe e nao pode mais ser cancelado."
                : "Esta solicitacao nao pode mais ser cancelada.");
        }
        if (s.getStatus() == StatusSolicitacaoOnline.ENVIADA) {
            s.setStatus(StatusSolicitacaoOnline.CANCELADA);
            repository.save(s);
            return null;
        }
        Long processoId = s.getProcessoGerado().getId();
        processoService.decidir(processoId, StatusProcesso.CANCELADO, null);
        return processoId;
    }

    /**
     * Avisa por e-mail os avaliadores que ainda NAO votaram que o processo foi
     * cancelado pelo solicitante - sem isso o medico abre o portal, analisa o
     * caso e so descobre no fim que ele nem existe mais.
     *
     * <p>Mesmo contrato do convite automatico
     * ({@code RegistroEnvioService.enviarConvitesAvaliadores}): NAO e
     * {@code @Transactional}, roda DEPOIS de {@link #cancelar(Long, Long)} ter
     * commitado e nunca lanca. O cancelamento e o ato relevante e ja esta
     * gravado; falha de SMTP vira aviso na tela, jamais um rollback que
     * "descancelaria" o processo.
     *
     * @return nomes dos avaliadores que NAO puderam ser avisados (sem e-mail
     *         cadastrado ou falha no envio); vazio quando todos foram avisados.
     */
    public List<String> notificarAvaliadoresCancelamento(Long processoId) {
        Processo p = processoService.buscar(processoId);
        List<String> naoAvisados = new java.util.ArrayList<>();
        for (Parecer parecer : processoService.pareceresPendentesComEmail(processoId)) {
            MembroUrgenciaRenal membro = parecer.getMembro();
            if (membro.getEmail() == null || membro.getEmail().isBlank()) {
                naoAvisados.add(membro.getNome());
                continue;
            }
            EmailTemplate template = emailTemplateService.emailCancelamentoAvaliador(p, membro);
            if (emailSenderService.enviar(membro.getEmail(), template.assunto(), template.corpo())) {
                auditoria.registrar("CANCELAMENTO_AVISO_AVALIADOR_ENVIADO",
                    "Processo " + p.getNumero() + " - " + membro.getNome());
            } else {
                naoAvisados.add(membro.getNome());
                auditoria.registrar("CANCELAMENTO_AVISO_AVALIADOR_FALHA",
                    "Processo " + p.getNumero() + " - " + membro.getNome());
            }
        }
        return naoAvisados;
    }

    /**
     * Devolve a solicitacao ao solicitante para correcao (ex.: dado
     * incompleto, documento faltando), registrando o motivo.
     */
    @Transactional
    public void devolver(Long id, String observacoes) {
        SolicitacaoOnline s = buscar(id);
        if (s.getStatus() != StatusSolicitacaoOnline.ENVIADA) {
            throw new IllegalStateException("Esta solicitacao ja foi triada.");
        }
        s.setStatus(StatusSolicitacaoOnline.DEVOLVIDA);
        s.setObservacoesTriagem(observacoes);
        repository.save(s);
    }

    /**
     * Marca a solicitacao como convertida no processo informado e copia os
     * documentos clinicos anexados para o processo real.
     *
     * <p><b>Trava de anonimizacao:</b> a copia entra como
     * {@code TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO} (staging), NUNCA como
     * {@code DOCUMENTO_CLINICO_AVALIADOR}. O documento do solicitante traz o
     * nome completo do paciente impresso no corpo do laudo; se entrasse direto
     * como material do avaliador, bastaria o operador clicar em "Registrar
     * envio" sem revisar nada para os 3 medicos receberem o nome do paciente,
     * quebrando a regra de imparcialidade (e o estado final ficaria
     * indistinguivel do fluxo correto). O tipo de staging nao entra no PDF
     * consolidado nem satisfaz {@code ProcessoValidator.validarRegistroEnvio}:
     * so vira material do avaliador por confirmacao explicita e auditada do
     * operador (ver {@code ProcessoDetalheController.confirmarAnonimizacao}).
     *
     * <p><b>Processos convertidos ANTES desta trava</b> gravaram o anexo do
     * portal como {@code DOCUMENTO_CLINICO_AVALIADOR}. Nada muda para eles: o
     * tipo antigo continua valido, elegivel ao merge e suficiente para
     * registrar o envio - a trava vale apenas para conversoes novas.
     *
     * Chamado pelo controller de triagem DEPOIS que
     * {@code ProcessoService.cadastrar} ja rodou com sucesso (numero
     * atribuido, 3 avaliadores escolhidos pelo operador) - este metodo nunca
     * cria nem altera o {@code Processo} em si.
     */
    @Transactional
    public void converter(Long id, Processo processoGerado) {
        SolicitacaoOnline s = buscar(id);
        if (s.getStatus() != StatusSolicitacaoOnline.ENVIADA) {
            throw new IllegalStateException("Esta solicitacao ja foi triada.");
        }
        for (AnexoSolicitacaoOnline anexo : s.getAnexos()) {
            try {
                byte[] dados = Files.readAllBytes(anexoStorage.resolverArquivo(anexo));
                anexoStorageProcesso.salvarBytes(processoGerado, TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO,
                    "Documento enviado pelo solicitante no Portal do Solicitante - NAO ANONIMIZADO: "
                        + "revisar e anonimizar o corpo (nome do paciente) e confirmar a anonimizacao "
                        + "na aba Envio antes de enviar aos avaliadores",
                    anexo.getNomeArquivo(), anexo.getContentType(), dados);
            } catch (IOException e) {
                throw new IllegalStateException(
                    "Falha ao copiar documento '" + anexo.getNomeArquivo() + "' para o processo: " + e.getMessage(), e);
            }
        }
        s.setStatus(StatusSolicitacaoOnline.CONVERTIDA);
        s.setProcessoGerado(processoGerado);
        repository.save(s);
    }
}

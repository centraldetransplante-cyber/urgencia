package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.HistoricoParecerRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Encaminhamento, aos avaliadores que pediram informacao complementar, do que
 * o solicitante respondeu — <b>sempre com revisao humana no meio</b>.
 *
 * <h2>Por que este servico existe</h2>
 * Um avaliador que vota {@code SOLICITA_INFORMACAO} nunca conseguia ler a
 * resposta: o material do solicitante entra como
 * {@link TipoAnexo#INFO_COMPLEMENTAR}, que e visivel <b>so ao operador</b>
 * (pode citar o nome do paciente/equipe, o que quebraria a imparcialidade), e
 * o Portal do Avaliador nunca exibiu esse tipo. O medico voltava a votar sem
 * saber o que tinha sido respondido — lacuna diagnosticada e fechada aqui.
 *
 * <h2>A promocao NUNCA e automatica</h2>
 * O operador <b>redige</b> o texto que vai ao avaliador (parafraseando o
 * conteudo bruto), e e <b>esse</b> texto que passa por
 * {@link VerificadorNomePaciente}. Promover o texto bruto do solicitante
 * direto para {@link TipoAnexo#INFO_COMPLEMENTAR_AVALIADOR} tornaria a
 * checagem um teatro: ela existe sobre o texto que sera de fato enviado.
 * Mesmo espirito da trava de anonimizacao de
 * {@link TipoAnexo#DOCUMENTO_PORTAL_NAO_ANONIMIZADO} (material do solicitante
 * so atravessa a barreira com acao explicita e auditada).
 *
 * <h2>Quem ve o material</h2>
 * Apenas quem tem {@code HistoricoParecer} para o processo, ou seja, quem
 * pediu a informacao em algum momento (o {@code Parecer} vivo e resetado por
 * {@code ProcessoService.retomarAposInformacao} e nao serve como registro
 * disso). Um unico encaminhamento atende TODOS os pedidos abertos daquele
 * momento — mesma decisao de produto ja aplicada ao envio do solicitante.
 *
 * <p><b>Nao toca</b> em {@code ProcessoValidator}, em
 * {@code ProcessoService.decidir}/{@code tentarDecisaoAutomatica}/
 * {@code retomarAposInformacao}, nem no significado de
 * {@link TipoAnexo#INFO_COMPLEMENTAR}.</p>
 */
@Service
public class InfoComplementarAvaliadorService {

    private static final Logger log = LoggerFactory.getLogger(InfoComplementarAvaliadorService.class);

    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ProcessoService processoService;
    private final MembroUrgenciaRenalRepository membroRepository;
    private final ParecerRepository parecerRepository;
    private final HistoricoParecerRepository historicoParecerRepository;
    private final AnexoRepository anexoRepository;
    private final AnexoStorageService anexoStorage;
    private final VerificadorNomePaciente verificadorNomePaciente;
    private final EmailTemplateService emailTemplateService;
    private final EmailSenderService emailSenderService;
    private final AuditoriaService auditoria;

    public InfoComplementarAvaliadorService(ProcessoService processoService,
                                            MembroUrgenciaRenalRepository membroRepository,
                                            ParecerRepository parecerRepository,
                                            HistoricoParecerRepository historicoParecerRepository,
                                            AnexoRepository anexoRepository,
                                            AnexoStorageService anexoStorage,
                                            VerificadorNomePaciente verificadorNomePaciente,
                                            EmailTemplateService emailTemplateService,
                                            EmailSenderService emailSenderService,
                                            AuditoriaService auditoria) {
        this.processoService = processoService;
        this.membroRepository = membroRepository;
        this.parecerRepository = parecerRepository;
        this.historicoParecerRepository = historicoParecerRepository;
        this.anexoRepository = anexoRepository;
        this.anexoStorage = anexoStorage;
        this.verificadorNomePaciente = verificadorNomePaciente;
        this.emailTemplateService = emailTemplateService;
        this.emailSenderService = emailSenderService;
        this.auditoria = auditoria;
    }

    /**
     * Mensagem devolvida ao operador quando a checagem de imparcialidade
     * bloqueia o texto. Deliberadamente <b>nao cita os termos encontrados</b>
     * (mesma calibragem de 2026-08-10 aplicada ao chat: citar ensina qual
     * palavra trocar para burlar a checagem, sem nenhum ganho real — o
     * operador ja sabe o nome do paciente e a equipe do processo que esta
     * editando).
     */
    static final String MSG_BLOQUEADO_POR_NOME =
        "Este texto parece citar o paciente ou a equipe solicitante. Refira-se ao paciente apenas "
        + "pelas iniciais e não cite a equipe solicitante. Reescreva e envie novamente.";

    /** Um medico que pediu informacao e segue com parecer pendente (destino do aviso por e-mail). */
    public record DestinatarioAviso(Long membroId, String nome, String email) {}

    /** Resultado do envio dos avisos (best-effort): quantos sairam e quem ficou de fora, e por que. */
    public record AvisoResultado(int enviados, List<String> avisos) {}

    /**
     * Grava o material revisado pelo operador como
     * {@link TipoAnexo#INFO_COMPLEMENTAR_AVALIADOR}.
     *
     * @param texto   texto REDIGIDO pelo operador (obrigatorio) — e este, nunca
     *                o bruto do solicitante, que passa pela checagem de nome
     * @param arquivo arquivo opcional JA anonimizado pelo operador (mesma
     *                responsabilidade humana do upload de documento clinico
     *                anonimizado)
     * @return quantidade de anexos criados (1 ou 2)
     * @throws IllegalArgumentException texto em branco, checagem de nome
     *                                  bloqueada ou falha ao salvar — nada e
     *                                  gravado em nenhum desses casos
     */
    @Transactional
    public int encaminhar(Long processoId, String texto, MultipartFile arquivo) {
        Processo p = processoService.buscar(processoId);
        String textoLimpo = (texto == null || texto.isBlank()) ? null : texto.trim();
        if (textoLimpo == null) {
            throw new IllegalArgumentException(
                "Escreva o texto que será enviado aos avaliadores (não é o texto bruto do solicitante: "
                + "redija o conteúdo sem identificar o paciente nem a equipe).");
        }
        // CHECAGEM DE IMPARCIALIDADE sobre o texto do OPERADOR, antes de
        // qualquer escrita. ALERTA tambem bloqueia aqui (diferente do chat,
        // onde ALERTA ja bloqueava tambem): este material vai para a tela de
        // voto, o ponto mais sensivel do sistema.
        var verificacao = verificadorNomePaciente.verificar(textoLimpo,
            p.getPacienteNome(), p.getSolicitanteEquipe());
        if (verificacao.nivel() != VerificadorNomePaciente.Nivel.LIVRE) {
            throw new IllegalArgumentException(MSG_BLOQUEADO_POR_NOME);
        }

        String carimbo = LocalDateTime.now().format(CARIMBO);
        int criados = 0;
        try {
            anexoStorage.salvarTexto(p, TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR,
                "Resposta à informação complementar, redigida e liberada pelo operador",
                "informacao-complementar-avaliadores-" + carimbo + ".txt", textoLimpo);
            criados++;
            if (arquivo != null && !arquivo.isEmpty()) {
                anexoStorage.salvar(p, TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR,
                    "Documento anonimizado anexado pelo operador junto à resposta à informação complementar",
                    arquivo);
                criados++;
            }
        } catch (IOException e) {
            // Checked: sem envolver numa RuntimeException o Spring nao faria
            // rollback e o .txt ja salvo ficaria commitado sem o arquivo.
            throw new IllegalStateException("Falha ao salvar o material encaminhado: " + e.getMessage(), e);
        }
        return criados;
    }

    /**
     * Quem deve ser avisado do material novo: medicos que pediram informacao
     * neste processo <b>e</b> ainda vao votar (parecer em aberto ou o proprio
     * pedido ainda de pe). Quem ja votou de novo, ou nunca pediu nada, fica
     * de fora.
     */
    @Transactional(readOnly = true)
    public List<DestinatarioAviso> destinatarios(Long processoId) {
        Set<Long> pediram = membrosQuePediramInformacao(processoId);
        if (pediram.isEmpty()) {
            return List.of();
        }
        List<DestinatarioAviso> destinos = new ArrayList<>();
        for (Parecer par : parecerRepository.findByProcessoId(processoId)) {
            MembroUrgenciaRenal m = par.getMembro();
            if (m == null || !pediram.contains(m.getId())) {
                continue;
            }
            // Ainda vai votar: parecer reaberto (resultado nulo, ja enviado) ou
            // pedido ainda de pe (o operador pode encaminhar ANTES de retomar a
            // analise - ver membrosQuePediramInformacao).
            boolean aindaVaiVotar = (par.getResultado() == null && par.getDataEnvio() != null)
                || par.getResultado() == ResultadoParecer.SOLICITA_INFORMACAO;
            if (!aindaVaiVotar) {
                continue;
            }
            destinos.add(new DestinatarioAviso(m.getId(), m.getNome(), m.getEmail()));
        }
        return destinos;
    }

    /**
     * Medicos que pediram informacao complementar neste processo, pelos DOIS
     * registros possiveis — e por isso a condicao mora aqui, num lugar so:
     *
     * <ul>
     *   <li>{@code HistoricoParecer}: o operador ja retomou a analise, entao o
     *   {@code Parecer} vivo foi RESETADO e o unico rastro do pedido esta no
     *   historico;</li>
     *   <li>{@code Parecer} vivo com {@code SOLICITA_INFORMACAO}: o pedido
     *   segue de pe (a analise ainda nao foi retomada).</li>
     * </ul>
     *
     * <p>Considerar so o historico deixaria a funcionalidade inutil na ordem
     * mais natural de trabalho — o operador encaminha o que recebeu e SO
     * DEPOIS retoma a analise; nesse instante nao existe historico nenhum, e
     * o material seria gravado sem nunca chegar a ninguem.</p>
     */
    private Set<Long> membrosQuePediramInformacao(Long processoId) {
        Set<Long> ids = new java.util.HashSet<>(
            historicoParecerRepository.findMembroIdsByProcessoId(processoId));
        for (Parecer par : parecerRepository.findByProcessoId(processoId)) {
            if (par.getResultado() == ResultadoParecer.SOLICITA_INFORMACAO && par.getMembro() != null) {
                ids.add(par.getMembro().getId());
            }
        }
        return ids;
    }

    /**
     * Avisa por e-mail, <b>best-effort</b>, os avaliadores de
     * {@link #destinatarios(Long)}.
     *
     * <p><b>Sempre chamado DEPOIS do commit de {@link #encaminhar}</b> e nunca
     * de dentro daquela transacao (mesmo contrato do convite automatico ao
     * registrar o envio e do aviso de cancelamento): falha de SMTP ou
     * avaliador sem e-mail vira aviso na tela, jamais desfaz o
     * encaminhamento ja gravado — o material continua disponivel no Portal.</p>
     */
    public AvisoResultado avisarAvaliadores(Long processoId) {
        Processo p = processoService.buscar(processoId);
        List<String> avisos = new ArrayList<>();
        int enviados = 0;
        for (DestinatarioAviso d : destinatarios(processoId)) {
            if (d.email() == null || d.email().isBlank()) {
                avisos.add(d.nome() + " (sem e-mail cadastrado)");
                auditoria.registrar("INFO_COMPLEMENTAR_AVISO_NAO_ENVIADO",
                    "Processo " + p.getNumero() + " - " + d.nome() + " - sem e-mail cadastrado");
                continue;
            }
            MembroUrgenciaRenal membro = membroRepository.findById(d.membroId()).orElse(null);
            if (membro == null) {
                avisos.add(d.nome() + " (cadastro do avaliador não encontrado)");
                continue;
            }
            EmailTemplate template = emailTemplateService.emailInfoComplementarDisponivel(p, membro);
            boolean ok;
            try {
                ok = emailSenderService.enviar(d.email(), template.assunto(), template.corpo());
            } catch (RuntimeException e) {
                log.warn("Falha ao avisar avaliador {} sobre informacao complementar do processo {}: {}",
                    d.membroId(), processoId, e.toString());
                ok = false;
            }
            if (ok) {
                enviados++;
                auditoria.registrar("INFO_COMPLEMENTAR_AVISO_ENVIADO",
                    "Processo " + p.getNumero() + " - " + d.nome());
            } else {
                avisos.add(d.nome() + " (falha no envio do e-mail)");
                auditoria.registrar("INFO_COMPLEMENTAR_AVISO_FALHA",
                    "Processo " + p.getNumero() + " - " + d.nome());
            }
        }
        return new AvisoResultado(enviados, avisos);
    }

    /**
     * Este avaliador pode ver o material encaminhado deste processo? So quem
     * pediu a informacao em algum momento — <b>predicado unico</b>, usado
     * tanto pela tela de voto quanto pelo download (nunca duplicar a
     * condicao).
     */
    @Transactional(readOnly = true)
    public boolean membroPodeVerMaterial(Long processoId, Long membroId) {
        return membrosQuePediramInformacao(processoId).contains(membroId);
    }

    /** Material ja encaminhado deste processo, mais antigo primeiro (ordem de leitura). */
    @Transactional(readOnly = true)
    public List<Anexo> materialEncaminhado(Long processoId) {
        return anexoRepository
            .findByProcessoIdAndTipo(processoId, TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR)
            .stream()
            .sorted(java.util.Comparator.comparing(Anexo::getDataUpload,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
            .toList();
    }
}

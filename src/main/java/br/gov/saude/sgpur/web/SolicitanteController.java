package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.AnexoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.RascunhoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusSolicitacaoOnline;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoSolicitacaoOnlineStorageService;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.Iniciais;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import br.gov.saude.sgpur.service.RascunhoSolicitacaoOnlineService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import br.gov.saude.sgpur.service.TempoRespostaService;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.web.dto.SituacaoListaView;
import br.gov.saude.sgpur.web.dto.SituacaoPedidoView;
import br.gov.saude.sgpur.web.dto.SituacaoPedidoView.AnexoDownload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.security.Principal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Portal do Solicitante — modulo experimental e OPCIONAL (ver
 * docs/PLANO-SOLICITANTE.md). Permite que a equipe solicitante envie o
 * pedido de urgencia renal direto pelo sistema, como alternativa ao fluxo
 * por e-mail. O solicitante NAO envia para avaliacao, NAO escolhe os
 * medicos avaliadores e NAO gera o material anonimizado — tudo isso
 * continua exclusivo do OPERADOR, que faz a triagem em
 * {@code /processos/solicitacoes-online} e converte o pedido num
 * {@code Processo} de verdade.
 *
 * Desligado por padrao em producao ({@code app.solicitante.habilitado}) —
 * quando desligado, este controller nem e registrado e as rotas /solicitante/**
 * respondem 404.
 *
 * <p><b>Sem @Transactional de nivel de classe (removido em 2026-07-29).</b> Essa
 * anotacao fazia os metodos POST com try/catch (ex.: {@link #criar},
 * {@link #cancelar}, {@link #enviarInformacaoComplementar},
 * {@link #apagarMensagem}/{@link #apagarMensagemAjax}) compartilharem a MESMA
 * transacao fisica com os servicos {@code @Transactional} chamados dentro do
 * try: uma {@code IllegalStateException}/{@code IllegalArgumentException} de
 * negocio lancada la dentro marcava a transacao inteira como rollback-only, o
 * {@code catch} tratava o erro normalmente e devolvia um flash amigavel, mas o
 * commit no fim do metodo do controller estourava
 * {@code UnexpectedRollbackException} (500 cru em vez do flash esperado) —
 * mesma familia de bug ja corrigida em {@code AvaliadorController} (voto do
 * avaliador perdido) e em {@code ProcessoDecisaoController.finalizar}. Cada
 * metodo agora declara sua propria estrategia de transacao, documentada no
 * javadoc do metodo: leitura pura ({@code readOnly = true}), leitura-escrita
 * simples quando nao ha try/catch em volta de escrita, ou
 * {@code NOT_SUPPORTED} quando ha um unico ponto de escrita relevante dentro
 * de um try/catch (o servico chamado abre sua propria transacao curta e
 * independente). Nenhum acesso LAZY problematico foi encontrado neste
 * controller: {@link SolicitacaoOnline#getUsuarioSolicitante()} e
 * {@link SolicitacaoOnline#getProcessoGerado()} sao {@code LAZY}, mas
 * {@code buscarParaDetalhe}/{@code findMinhasParaLista} ja fazem fetch join
 * de ambos, e os poucos usos de {@code buscar()} (sem fetch join) so leem
 * {@code getId()} de {@code usuarioSolicitante} — seguro mesmo com o proxy
 * fechado (Hibernate nao precisa de sessao aberta so para o identificador).
 */
@Controller
@RequestMapping("/solicitante")
@ConditionalOnProperty(prefix = "app.solicitante", name = "habilitado", havingValue = "true", matchIfMissing = true)
public class SolicitanteController {

    private final UsuarioRepository usuarioRepo;
    private final SolicitacaoOnlineService solicitacaoService;
    private final AuditoriaService auditoria;
    private final AnexoSolicitacaoOnlineRepository anexoRepo;
    private final AnexoSolicitacaoOnlineStorageService anexoStorage;
    private final AnexoStorageService anexoStorageProcesso;
    private final MensagemSolicitacaoService mensagemService;
    private final TempoRespostaService tempoRespostaService;
    private final RascunhoSolicitacaoOnlineService rascunhoService;

    public SolicitanteController(UsuarioRepository usuarioRepo,
                                 SolicitacaoOnlineService solicitacaoService,
                                 AuditoriaService auditoria,
                                 AnexoSolicitacaoOnlineRepository anexoRepo,
                                 AnexoSolicitacaoOnlineStorageService anexoStorage,
                                 AnexoStorageService anexoStorageProcesso,
                                 MensagemSolicitacaoService mensagemService,
                                 TempoRespostaService tempoRespostaService,
                                 RascunhoSolicitacaoOnlineService rascunhoService) {
        this.usuarioRepo = usuarioRepo;
        this.solicitacaoService = solicitacaoService;
        this.auditoria = auditoria;
        this.anexoStorageProcesso = anexoStorageProcesso;
        this.anexoRepo = anexoRepo;
        this.anexoStorage = anexoStorage;
        this.mensagemService = mensagemService;
        this.tempoRespostaService = tempoRespostaService;
        this.rascunhoService = rascunhoService;
    }

    /**
     * Allowlist explicita dos campos que o solicitante pode preencher no
     * formulario de nova solicitacao. Sem isso, o binding de
     * {@code @ModelAttribute("solicitacao") SolicitacaoOnline} aceitaria
     * QUALQUER campo da entidade presente no request (mass assignment) -
     * incluindo {@code id}, {@code status}, {@code usuarioSolicitante},
     * {@code processoGerado} etc, que {@link SolicitacaoOnlineService#criar}
     * ja reseta explicitamente, mas e mais seguro nunca deixar o bind tocar
     * neles em primeiro lugar (defesa em profundidade).
     */
    @InitBinder("solicitacao")
    public void initBinderSolicitacao(WebDataBinder binder) {
        binder.setAllowedFields(
            "pacienteNome", "pacienteRgct", "dataSituacaoEspecial", "justificativaClinica");
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String lista(Principal principal, Model model) {
        Usuario usuario = resolverUsuario(principal);
        List<SolicitacaoOnline> minhas = solicitacaoService.listarMinhas(usuario.getId());
        model.addAttribute("solicitacoes", minhas);
        model.addAttribute("resumo", solicitacaoService.resumir(minhas));
        Map<Long, SolicitacaoOnlineService.DiasEspera> diasEspera = new LinkedHashMap<>();
        Map<Long, Boolean> acaoNecessaria = new LinkedHashMap<>();
        Map<Long, Boolean> mensagensNaoLidas = new LinkedHashMap<>();
        Map<Long, SituacaoListaView> situacoesLista = new LinkedHashMap<>();
        for (SolicitacaoOnline s : minhas) {
            if (s.getStatus() == StatusSolicitacaoOnline.ENVIADA) {
                diasEspera.put(s.getId(), solicitacaoService.diasEspera(s));
            }
            acaoNecessaria.put(s.getId(), solicitacaoService.precisaInformacaoComplementar(s));
            mensagensNaoLidas.put(s.getId(),
                mensagemService.contarNaoLidasSolicitantePorSolicitacao(s.getId(), usuario.getId()) > 0);
            situacoesLista.put(s.getId(), montarSituacaoLista(s));
        }
        long totalAcaoNecessaria = acaoNecessaria.values().stream().filter(Boolean::booleanValue).count();
        model.addAttribute("diasEspera", diasEspera);
        model.addAttribute("acaoNecessaria", acaoNecessaria);
        model.addAttribute("situacoesLista", situacoesLista);
        model.addAttribute("totalAcaoNecessaria", totalAcaoNecessaria);
        model.addAttribute("mensagensNaoLidas", mensagensNaoLidas);
        model.addAttribute("equipe", usuario.getEquipeSolicitante());
        return "solicitante/lista";
    }

    /**
     * Pre-preenche o formulario com o rascunho salvo, se houver (ver
     * {@link RascunhoSolicitacaoOnlineService}) - o solicitante retoma de
     * onde parou em vez de comecar do zero. Sem rascunho, comporta-se como
     * antes (formulario em branco, so a data de hoje pre-selecionada).
     */
    @GetMapping("/nova")
    @Transactional(readOnly = true)
    public String nova(Principal principal, Model model) {
        Usuario usuario = resolverUsuario(principal);
        Optional<RascunhoSolicitacaoOnline> rascunho = rascunhoService.buscarPorUsuario(usuario.getId());
        SolicitacaoOnline s = new SolicitacaoOnline();
        if (rascunho.isPresent()) {
            RascunhoSolicitacaoOnline r = rascunho.get();
            s.setPacienteNome(r.getPacienteNome());
            s.setPacienteRgct(r.getPacienteRgct());
            s.setDataSituacaoEspecial(
                r.getDataSituacaoEspecial() != null ? r.getDataSituacaoEspecial() : LocalDate.now());
            s.setJustificativaClinica(r.getJustificativaClinica());
            model.addAttribute("rascunhoSalvoEm", r.getAtualizadoEm());
        } else {
            s.setDataSituacaoEspecial(LocalDate.now());
        }
        model.addAttribute("solicitacao", s);
        model.addAttribute("equipe", usuario.getEquipeSolicitante());
        model.addAttribute("email", usuario.getEmail());
        return "solicitante/nova";
    }

    /**
     * Salva/atualiza o rascunho do formulario (AJAX, sem recarregar a
     * pagina). Nenhum campo e obrigatorio aqui - o rascunho pode estar
     * parcialmente preenchido; a validacao completa so ocorre no envio final
     * ({@link #criar}). Nao aceita upload de documentos (os arquivos
     * selecionados no &lt;input type="file"&gt; nao sobrevivem a um reload de
     * pagina de qualquer forma - o solicitante os re-seleciona ao voltar).
     */
    @PostMapping("/nova/rascunho")
    @ResponseBody
    @Transactional
    public Map<String, Object> salvarRascunho(
            @RequestParam(value = "pacienteNome", required = false) String pacienteNome,
            @RequestParam(value = "pacienteRgct", required = false) String pacienteRgct,
            @RequestParam(value = "dataSituacaoEspecial", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataSituacaoEspecial,
            @RequestParam(value = "justificativaClinica", required = false) String justificativaClinica,
            Principal principal) {
        Usuario usuario = resolverUsuario(principal);
        RascunhoSolicitacaoOnline r = rascunhoService.salvar(
            usuario.getId(), pacienteNome, pacienteRgct, dataSituacaoEspecial, justificativaClinica);
        return Map.of("ok", true, "salvoEm", r.getAtualizadoEm().toString());
    }

    /** Descarta o rascunho em andamento (o solicitante quer comecar do zero). */
    @PostMapping("/nova/rascunho/apagar")
    @Transactional
    public String apagarRascunho(Principal principal, RedirectAttributes ra) {
        Usuario usuario = resolverUsuario(principal);
        rascunhoService.apagar(usuario.getId());
        ra.addFlashAttribute("msg", "Rascunho descartado.");
        return "redirect:/solicitante/nova";
    }

    /**
     * NOT_SUPPORTED: unica escrita relevante e {@code solicitacaoService.criar(...)},
     * ja {@code @Transactional} no proprio servico. Sem transacao de classe
     * para "envenenar" quando o servico lanca ({@code IllegalArgumentException}/
     * {@code IllegalStateException}), o catch abaixo devolve o formulario com
     * flash de erro normalmente, sem risco de {@code UnexpectedRollbackException}
     * no commit (nao ha commit de controller nenhum a fazer).
     */
    @PostMapping("/nova")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String criar(@ModelAttribute("solicitacao") SolicitacaoOnline solicitacao,
                        @RequestParam(value = "documentos", required = false) List<MultipartFile> documentos,
                        Principal principal, Model model, RedirectAttributes ra) {
        Usuario usuario = resolverUsuario(principal);
        try {
            SolicitacaoOnline salva = solicitacaoService.criar(solicitacao, usuario, documentos);
            auditoria.registrar("SOLICITACAO_ONLINE_ENVIADA",
                "Solicitacao " + salva.getId() + " - " + Iniciais.de(salva.getPacienteNome()));
            // Envio efetivado: o rascunho (se havia) ja virou uma SolicitacaoOnline
            // de verdade, nao faz mais sentido mante-lo. Best-effort: uma falha
            // aqui nao pode desfazer o envio que ja foi commitado acima.
            try {
                rascunhoService.apagar(usuario.getId());
            } catch (RuntimeException e) {
                // Rascunho orfao nao e um problema serio (so reaparece no proximo
                // /solicitante/nova, sem nenhum efeito colateral) - nunca vale a
                // pena arriscar confundir o solicitante com um erro depois que a
                // solicitacao real ja foi enviada com sucesso.
            }
            ra.addFlashAttribute("msg",
                "Solicitação enviada. Aguarde a triagem da equipe de Urgência Renal.");
            return "redirect:/solicitante";
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("equipe", usuario.getEquipeSolicitante());
            model.addAttribute("email", usuario.getEmail());
            model.addAttribute("erro", e.getMessage());
            return "solicitante/nova";
        }
    }

    @GetMapping("/{id}")
    @Transactional
    public String detalhe(@PathVariable Long id, Principal principal, Model model) {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = conferirPosse(solicitacaoService.buscarParaDetalhe(id), usuario);
        model.addAttribute("solicitacao", s);
        // Fonte unica da regra: o botao aparece exatamente quando cancelar()
        // aceitaria (ver SolicitacaoOnlineService.podeCancelar).
        model.addAttribute("podeCancelar", solicitacaoService.podeCancelar(s));
        model.addAttribute("diasEspera", solicitacaoService.diasEspera(s));
        boolean precisaInformacaoComplementar = solicitacaoService.precisaInformacaoComplementar(s);
        boolean jaEnviouInfoComplementar = solicitacaoService.jaEnviouInformacaoComplementarNestaRodada(s);
        model.addAttribute("precisaInformacaoComplementar", precisaInformacaoComplementar);
        model.addAttribute("jaEnviouInfoComplementar", jaEnviouInfoComplementar);
        // Previsao de prazo (so faz sentido enquanto os avaliadores estao de fato
        // analisando - nao antes da triagem, nem pausado em "Solicita informacao"
        // (aguardando o proprio solicitante), nem depois de decidido). Baseada na
        // media historica real dos avaliadores (mesmo indicador de /membros),
        // nao uma promessa de prazo formal - so uma referencia pro solicitante.
        boolean emAnaliseAtiva = s.getProcessoGerado() != null
            && s.getProcessoGerado().getStatus() == StatusProcesso.ENVIADO;
        String previsaoPrazo = emAnaliseAtiva
            ? TempoRespostaService.formatarDias(tempoRespostaService.calcular().mediaGeralDias())
            : null;
        model.addAttribute("previsaoPrazo", previsaoPrazo);
        Anexo comprovanteSnt = null;
        Anexo oficioIndeferimento = null;
        if (s.getProcessoGerado() != null) {
            comprovanteSnt = anexoStorageProcesso.buscarUltimoPorTipo(s.getProcessoGerado().getId(), TipoAnexo.COMPROVANTE_SNT);
            oficioIndeferimento = anexoStorageProcesso.buscarUltimoPorTipo(s.getProcessoGerado().getId(), TipoAnexo.OFICIO_INDEFERIMENTO);
            model.addAttribute("comprovanteSntAnexo", comprovanteSnt);
            model.addAttribute("oficioIndeferimentoAnexo", oficioIndeferimento);
        }
        // Cartao de situacao unico (Fase 6 do relatorio de UI): toda a decisao
        // de status/cor/texto e feita AQUI, uma unica vez - o template so
        // consome situacao.*, nunca recalcula a regra sozinho.
        model.addAttribute("situacao", montarSituacaoPedido(
            s, precisaInformacaoComplementar, jaEnviouInfoComplementar,
            comprovanteSnt, oficioIndeferimento, previsaoPrazo));
        List<MensagemSolicitacao> mensagens = mensagemService.listarPorSolicitacao(id);
        model.addAttribute("mensagens", mensagens);
        long msgNaoLidas = mensagens.stream()
            .filter(m -> !m.isLida() && m.getRemetente() == MensagemSolicitacao.RemetenteMensagem.OPERADOR
                && !m.getRemetenteId().equals(usuario.getId()))
            .count();
        model.addAttribute("msgNaoLidas", msgNaoLidas);
        // Evita notificacao duplicada: esta tela ja tem seu proprio poll de chat
        // (chat-solicitacao.js), entao o poll GLOBAL da navbar (layout.html) fica
        // desligado aqui.
        model.addAttribute("chatAtivoNestaTela", true);
        mensagemService.marcarComoLidas(id, MensagemSolicitacao.RemetenteMensagem.OPERADOR, usuario.getId());
        return "solicitante/detalhe";
    }

    /**
     * Badge de situacao de UMA linha da lista ({@code /solicitante}) — o
     * equivalente enxuto de {@link #montarSituacaoPedido} para a listagem.
     *
     * <p><b>Por que nao le mais {@code StatusSolicitacaoOnline} direto no
     * template</b> (correcao da §4 do relatorio de responsividade/cores de
     * 2026-08-11, decidida pelo dono do produto — opcao "a"): aquele enum
     * trata {@code CONVERTIDA} como estado unico ({@code bg-success} +
     * "Convertida em processo"), entao pedido <b>Indeferido</b>,
     * <b>Deferido</b> e <b>em analise</b> saiam com o MESMO badge verde. Aqui
     * a decisao passa a olhar o {@link Processo} gerado e mostrar o desfecho
     * real. O enum continua intacto porque e compartilhado com a triagem do
     * OPERADOR, onde "Convertida em processo" e a informacao correta.
     *
     * <p>Mesma ordem de prioridade e mesmo vocabulario de
     * {@link #montarSituacaoPedido} (rotulo/tom/icone), de proposito: abrir o
     * pedido nao pode mostrar um rotulo diferente do que a lista mostrou.
     * Tambem cobre os dois formatos historicos de "decidido" (o espelho
     * antigo em {@code APROVADA}/{@code REPROVADA} e o caminho atual,
     * {@code CONVERTIDA} com o processo ja finalizado).
     *
     * <p>Roda dentro da transacao de {@code lista()} (o template renderiza
     * depois do commit, com {@code open-in-view: false}), entao navegar ate
     * {@code s.getProcessoGerado().getStatus()} aqui e mais seguro do que a
     * navegacao equivalente que o template fazia.
     *
     * <p>Nao trata "Acao necessaria" (pausa por informacao complementar): esse
     * badge continua sendo decidido no template pelo mapa {@code acaoNecessaria}
     * e tem precedencia sobre este — quando o solicitante precisa agir, e isso
     * que ele tem que ver, nao "Em analise".
     */
    private SituacaoListaView montarSituacaoLista(SolicitacaoOnline s) {
        Processo proc = s.getProcessoGerado();
        boolean processoFinalizado = proc != null && proc.getStatus().isFinalizado();

        if (s.getStatus() == StatusSolicitacaoOnline.CANCELADA
                || (processoFinalizado && proc.getStatus() == StatusProcesso.CANCELADO)) {
            return new SituacaoListaView("Cancelado", "neutral", "slash-circle-fill");
        }
        if (s.getStatus() == StatusSolicitacaoOnline.APROVADA
                || (processoFinalizado && proc.getStatus() == StatusProcesso.DEFERIDO)) {
            return new SituacaoListaView("Deferido", "ok", "check-circle-fill");
        }
        if (s.getStatus() == StatusSolicitacaoOnline.REPROVADA
                || (processoFinalizado && proc.getStatus() == StatusProcesso.INDEFERIDO)) {
            return new SituacaoListaView("Indeferido", "danger", "x-circle-fill");
        }
        if (s.getStatus() == StatusSolicitacaoOnline.DEVOLVIDA) {
            return new SituacaoListaView("Devolvida", "danger", "arrow-return-left");
        }
        if (s.getStatus() == StatusSolicitacaoOnline.PROCESSO_EXCLUIDO) {
            return new SituacaoListaView("Processo excluído", "danger", "exclamation-triangle-fill");
        }
        if (s.getStatus() == StatusSolicitacaoOnline.CONVERTIDA) {
            return new SituacaoListaView("Em análise", "aguardando", "hourglass-split");
        }
        // ENVIADA (default): ainda nao triada, nenhum processo gerado.
        return new SituacaoListaView("Aguardando triagem", "aguardando", "hourglass-split");
    }

    /**
     * Fonte unica da regra de exibicao do "cartao de situacao" do detalhe do
     * Portal do Solicitante (Fase 6 do relatorio de UI, 2026-08). Antes desta
     * fase a mesma decisao de status vivia reescrita em 8 blocos
     * {@code <alert>} diferentes no template, com vocabulario divergente
     * ("Aprovada"/"Deferido"/"Pedido aprovado!" na mesma tela). Aqui ela e
     * calculada uma unica vez.
     *
     * <p>Prioridade das checagens: primeiro o resultado FINAL do processo
     * (Deferido/Indeferido/Cancelado), porque tanto o espelho antigo direto
     * em {@link StatusSolicitacaoOnline#APROVADA}/{@code REPROVADA} quanto o
     * caminho atual ({@code CONVERTIDA} com {@link Processo#getStatus()} ja
     * finalizado) tem que levar ao MESMO cartao — dados historicos anteriores
     * ao ajuste do espelho de status (ver CLAUDE.md, "decidir espelha
     * CANCELADO") podem estar em qualquer um dos dois formatos. Statuses sem
     * processo (ENVIADA/DEVOLVIDA/CANCELADA/PROCESSO_EXCLUIDO) vem por
     * ultimo, sao mutuamente exclusivos por definicao.
     */
    private SituacaoPedidoView montarSituacaoPedido(SolicitacaoOnline s, boolean precisaInfo,
            boolean jaEnviouInfo, Anexo comprovanteSnt, Anexo oficioIndeferimento, String previsaoPrazo) {
        Processo proc = s.getProcessoGerado();
        String numero = proc != null ? proc.getNumero() : null;
        boolean processoFinalizado = proc != null && proc.getStatus().isFinalizado();

        boolean cancelado = s.getStatus() == StatusSolicitacaoOnline.CANCELADA
            || (processoFinalizado && proc.getStatus() == StatusProcesso.CANCELADO);
        if (cancelado) {
            String mensagem = s.getStatus() == StatusSolicitacaoOnline.CANCELADA
                ? "Você cancelou este pedido antes da triagem."
                : "O processo " + numero + " gerado a partir deste pedido foi cancelado.";
            return new SituacaoPedidoView("Cancelado", "secondary", "slash-circle-fill", "Cancelado", mensagem,
                null, false, false, null, numero);
        }

        boolean deferido = s.getStatus() == StatusSolicitacaoOnline.APROVADA
            || (processoFinalizado && proc.getStatus() == StatusProcesso.DEFERIDO);
        if (deferido) {
            AnexoDownload anexo = comprovanteSnt != null
                ? new AnexoDownload(comprovanteSnt.getId(), "Baixar comprovante de inserção no SNT")
                : null;
            // A mensagem so pode afirmar que a resposta oficial JA foi enviada por
            // e-mail quando isso e verdade nos dois sentidos: o anexo do comprovante
            // SNT existe E ProcessoService.finalizarResposta ja confirmou o envio
            // (Processo.emailEnviadoSolicitante). Antes disso, dizer "foi enviada"
            // contradiz o proprio botao de download (que fica ausente, com o aviso
            // "ainda sendo providenciado" logo abaixo, no template) - bug real
            // achado em QA, corrigido em 2026-08.
            boolean respostaJaEnviada = comprovanteSnt != null
                && proc != null && proc.isEmailEnviadoSolicitante();
            String mensagem = respostaJaEnviada
                ? "Seu pedido (processo " + numero + ") foi analisado e DEFERIDO pela Central "
                    + "de Transplantes do Estado do Rio Grande do Sul. A resposta oficial foi enviada por "
                    + "e-mail à sua equipe (" + s.getSolicitanteEmail() + "), com o comprovante de inserção "
                    + "no Sistema Nacional de Transplantes (SNT) em anexo."
                : "Seu pedido (processo " + numero + ") foi analisado e DEFERIDO pela Central "
                    + "de Transplantes do Estado do Rio Grande do Sul. A equipe está providenciando o envio "
                    + "formal da resposta, com o comprovante de inserção no Sistema Nacional de Transplantes "
                    + "(SNT), à sua equipe (" + s.getSolicitanteEmail() + ").";
            // O corpo BRUTO do e-mail institucional (Processo.mensagemResposta) NAO
            // entra mais aqui. Ele repetia, em prosa de oficio, exatamente o que a
            // "mensagem" acima ja diz em linguagem direta (deferido + e-mail enviado +
            // comprovante em anexo): o cartao exibia a mesma informacao duas vezes
            // seguidas, uma logo abaixo da outra (relato do dono do produto olhando
            // /solicitante/16 em producao, 2026-08). O "detalhe" so existe quando
            // acrescenta algo NOVO - aqui, o que o solicitante precisa (ou nao)
            // fazer a partir de agora; e so faz sentido depois que a resposta saiu
            // de fato (com a resposta pendente, quem tem pendencia e a equipe, e o
            // proprio texto acima ja diz isso).
            String detalhe = respostaJaEnviada
                ? "Não é preciso fazer mais nada por aqui — guarde o comprovante para os seus registros."
                : null;
            return new SituacaoPedidoView("Deferido", "success", "check-circle-fill",
                "Deferido — Urgência renal reconhecida", mensagem, detalhe, false, false, anexo, numero);
        }

        boolean indeferido = s.getStatus() == StatusSolicitacaoOnline.REPROVADA
            || (processoFinalizado && proc.getStatus() == StatusProcesso.INDEFERIDO);
        if (indeferido) {
            AnexoDownload anexo = oficioIndeferimento != null
                ? new AnexoDownload(oficioIndeferimento.getId(), "Baixar ofício de indeferimento")
                : null;
            // Mesmo raciocinio do ramo deferido acima: so afirma que o oficio JA foi
            // enviado por e-mail quando o anexo existe E o envio ja foi confirmado.
            boolean respostaJaEnviada = oficioIndeferimento != null
                && proc != null && proc.isEmailEnviadoSolicitante();
            String mensagem = respostaJaEnviada
                ? "Seu pedido (processo " + numero + ") foi analisado e INDEFERIDO pela Central "
                    + "de Transplantes do Estado do Rio Grande do Sul. O ofício com os detalhes foi enviado "
                    + "por e-mail à sua equipe (" + s.getSolicitanteEmail() + ")."
                : "Seu pedido (processo " + numero + ") foi analisado e INDEFERIDO pela Central "
                    + "de Transplantes do Estado do Rio Grande do Sul. A equipe está providenciando o envio "
                    + "formal do ofício com os detalhes, à sua equipe (" + s.getSolicitanteEmail() + ").";
            String motivo = proc != null ? proc.getMotivoIndeferimento() : null;
            // Com motivo registrado, o detalhe e informacao NOVA (nao aparece na
            // "mensagem" acima) e continua sendo o texto mais util da tela.
            // Sem motivo, o fallback antigo era Processo.mensagemResposta - o corpo
            // bruto do e-mail, que so repetia a mensagem acima (mesmo defeito
            // corrigido no ramo deferido). Passou a apontar para o oficio anexo,
            // que e onde a fundamentacao completa de fato esta.
            String detalhe;
            if (motivo != null && !motivo.isBlank()) {
                detalhe = "Motivo informado: " + motivo;
            } else if (anexo != null) {
                detalhe = "A fundamentação completa da decisão está no ofício, disponível abaixo.";
            } else {
                detalhe = null;
            }
            return new SituacaoPedidoView("Indeferido", "danger", "x-circle-fill",
                "Indeferido — Urgência renal não reconhecida", mensagem, detalhe, false, false, anexo, numero);
        }

        if (s.getStatus() == StatusSolicitacaoOnline.DEVOLVIDA) {
            String mensagem = "Sua solicitação foi devolvida para correção. Esta solicitação não será "
                + "reanalisada — envie uma nova com os ajustes indicados abaixo.";
            return new SituacaoPedidoView("Devolvida", "danger", "arrow-return-left", "Devolvida para correção",
                mensagem, s.getObservacoesTriagem(), false, true, null, null);
        }

        if (s.getStatus() == StatusSolicitacaoOnline.PROCESSO_EXCLUIDO) {
            String mensagem = "Sua solicitação foi removida pela equipe de Urgência Renal (o processo "
                + "gerado foi excluído). Se ainda for necessário, envie uma nova solicitação.";
            return new SituacaoPedidoView("Processo excluído", "danger", "exclamation-triangle-fill",
                "Processo excluído", mensagem, null, false, true, null, null);
        }

        if (s.getStatus() == StatusSolicitacaoOnline.CONVERTIDA) {
            if (precisaInfo && !jaEnviouInfo) {
                String mensagem = "Seu pedido virou o processo " + numero + ", mas um(a) avaliador(a) da "
                    + "Urgência Renal pediu mais informações sobre este pedido. Envie os documentos/dados "
                    + "solicitados abaixo para que a análise possa continuar.";
                return new SituacaoPedidoView("Informação necessária", "warning", "exclamation-triangle-fill",
                    "Informação complementar necessária", mensagem, null, true, false, null, numero);
            }
            if (precisaInfo) {
                // Padronizacao de cores 2026-08-06: "Aguardando" = azul.
                return new SituacaoPedidoView("Aguardando análise", "primary", "check-circle-fill",
                    "Informações complementares recebidas",
                    "Aguardando a análise da equipe de Urgência Renal.", null, false, false, null, numero);
            }
            String mensagem = "Seu pedido virou um processo oficial e está em análise pelos médicos "
                + "avaliadores.";
            String detalhe = previsaoPrazo != null
                ? "Previsão baseada no histórico: normalmente decidido em torno de " + previsaoPrazo
                    + " após o envio para análise (não é um prazo formal, só uma referência)."
                : null;
            return new SituacaoPedidoView("Em análise", "primary", "hourglass-split", "Em análise", mensagem,
                detalhe, false, false, null, numero);
        }

        // ENVIADA (default): ainda nao triada, nenhum processo gerado ainda.
        // Padronizacao de cores 2026-08-06: "Aguardando" = azul (nao amarelo -
        // amarelo fica reservado para "Solicita informacao", ver
        // docs/IDEIA-PADRONIZACAO-CORES-SOLICITA-INFO-AGUARDANDO-2026-08.md).
        // O retorno NUNCA e "so por e-mail": o proprio Portal mostra a situacao e a
        // decisao a qualquer momento (este cartao). O e-mail e um aviso complementar -
        // dizer que a pessoa "sera avisada por e-mail" sugeria, erradamente, que ela
        // dependia da caixa de entrada para saber o resultado (relato do dono do
        // produto, 2026-08-11).
        String mensagem = "Não é necessário fazer nada agora. Você pode acompanhar o andamento por aqui a "
            + "qualquer momento; também avisaremos por e-mail (" + s.getSolicitanteEmail() + ") assim que a "
            + "equipe de Urgência Renal analisar o pedido.";
        return new SituacaoPedidoView("Aguardando triagem", "primary", "hourglass-split", "Aguardando triagem",
            mensagem, null, false, false, null, null);
    }

    /**
     * Contagem global de respostas do operador ainda nao lidas (somando
     * todas as solicitacoes deste solicitante), pra notificacao
     * sonora/toast em QUALQUER tela do portal (layout.html) - a tela de
     * detalhe (/solicitante/{id}) ja tem seu proprio poll especifico e nao
     * usa este endpoint (ver chatAtivoNestaTela).
     */
    @GetMapping("/nao-lidas-count")
    @ResponseBody
    @Transactional(readOnly = true)
    public Map<String, Object> naoLidasCount(Principal principal) {
        Usuario usuario = resolverUsuario(principal);
        return Map.of("total", mensagemService.contarNaoLidasParaSolicitante(usuario.getId()));
    }

    /**
     * Upload direto, pelo solicitante, dos documentos/dados pedidos por um
     * avaliador quando o processo esta pausado (SOLICITA_INFORMACAO). O
     * solicitante SO ENVIA - quem decide retomar a analise continua sendo o
     * OPERADOR (POST /processos/{id}/retomar-analise), nunca este endpoint.
     *
     * <p><b>NOT_SUPPORTED:</b> unica escrita relevante e
     * {@code solicitacaoService.enviarInformacaoComplementar(...)}, ja
     * {@code @Transactional} no servico. A leitura anterior
     * ({@code buscarParaDetalhe}) roda na sua propria transacao independente
     * (fetch join de anexos/processoGerado/usuarioSolicitante, sem depender de
     * sessao aberta neste metodo). Sem isso, uma falha de negocio dentro do
     * servico (ex.: pausa ja retomada pelo operador) marcaria a transacao de
     * classe como rollback-only e o catch abaixo devolveria o flash de erro
     * as custas de um {@code UnexpectedRollbackException} no commit.
     */
    @PostMapping("/{id}/informacao-complementar")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String enviarInformacaoComplementar(@PathVariable Long id,
            @RequestParam(value = "arquivos", required = false) List<MultipartFile> arquivos,
            Principal principal, RedirectAttributes ra) {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = conferirPosse(solicitacaoService.buscarParaDetalhe(id), usuario);
        try {
            solicitacaoService.enviarInformacaoComplementar(s, arquivos);
            auditoria.registrar("INFO_COMPLEMENTAR_RECEBIDA_PORTAL",
                "Solicitacao " + id + " - processo " + s.getProcessoGerado().getNumero());
            ra.addFlashAttribute("msg",
                "Informações enviadas. A equipe de Urgência Renal vai retomar a análise em breve.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/solicitante/" + id;
    }

    /**
     * NOT_SUPPORTED: unica escrita relevante e {@code solicitacaoService.cancelar(...)},
     * ja {@code @Transactional} no proprio servico. {@code resolverPropria}
     * antes do try so le (posse); sem transacao de classe para envenenar, uma
     * {@code IllegalStateException} de negocio (ex.: ja triada) devolve o
     * flash de erro sem risco de {@code UnexpectedRollbackException}.
     *
     * <p>O aviso aos avaliadores ({@code notificarAvaliadoresCancelamento})
     * roda DEPOIS do commit do cancelamento e tem seu proprio {@code catch}:
     * uma falha inesperada ali (nao so o caso ja tratado de "sem e-mail"/SMTP
     * recusado) vira flash {@code aviso}, nunca 500 - o cancelamento em si ja
     * esta gravado e nao pode ser "perdido" por um problema no aviso, que e
     * so cortesia (o operador consegue reenviar depois).
     */
    @PostMapping("/{id}/cancelar")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String cancelar(@PathVariable Long id, Principal principal, RedirectAttributes ra) {
        Usuario usuario = resolverUsuario(principal);
        resolverPropria(id, usuario);
        try {
            Long processoCancelado = solicitacaoService.cancelar(id, usuario.getId());
            auditoria.registrar("SOLICITACAO_ONLINE_CANCELADA", "Solicitacao " + id
                + (processoCancelado != null ? " - processo " + processoCancelado + " cancelado junto" : ""));
            if (processoCancelado == null) {
                ra.addFlashAttribute("msg", "Solicitação cancelada.");
            } else {
                ra.addFlashAttribute("msg",
                    "Solicitação cancelada. O processo foi encerrado e os avaliadores pendentes foram avisados.");
                try {
                    List<String> naoAvisados =
                        solicitacaoService.notificarAvaliadoresCancelamento(processoCancelado);
                    if (!naoAvisados.isEmpty()) {
                        ra.addFlashAttribute("aviso",
                            "Não foi possível avisar por e-mail: " + String.join(", ", naoAvisados)
                                + ". O processo já consta como cancelado e saiu da lista deles no portal.");
                    }
                } catch (RuntimeException e) {
                    ra.addFlashAttribute("aviso",
                        "O cancelamento foi efetivado, mas houve uma falha inesperada ao avisar os avaliadores "
                            + "pendentes. O processo já consta como cancelado.");
                }
            }
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/solicitante";
    }

    /** Sem try/catch em volta da escrita: leitura-escrita simples basta (nada a envenenar). */
    @PostMapping("/{id}/mensagem")
    @Transactional
    public String enviarMensagem(@PathVariable Long id, @RequestParam String texto,
                                 Principal principal, RedirectAttributes ra) {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = conferirPosse(solicitacaoService.buscar(id), usuario);
        if (texto == null || texto.isBlank()) {
            ra.addFlashAttribute("erro", "A mensagem não pode estar em branco.");
            return "redirect:/solicitante/" + id;
        }
        if (texto.length() > MensagemSolicitacaoService.TEXTO_MAX_LENGTH) {
            ra.addFlashAttribute("erro", "A mensagem excede o limite de "
                + MensagemSolicitacaoService.TEXTO_MAX_LENGTH + " caracteres.");
            return "redirect:/solicitante/" + id;
        }
        if (!podeEnviarMensagem(s.getStatus())) {
            ra.addFlashAttribute("erro", "Não é possível enviar mensagem para esta solicitação no estado atual "
                + "(o processo gerado foi excluído).");
            return "redirect:/solicitante/" + id;
        }
        mensagemService.enviar(s, texto, MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, usuario.getId());
        auditoria.registrar("MENSAGEM_SOLICITANTE_ENVIADA",
            "Solicitacao " + id + " - " + Iniciais.de(s.getPacienteNome()));
        return "redirect:/solicitante/" + id;
    }

    /**
     * Fonte unica da regra de "o solicitante pode continuar enviando
     * mensagem para este pedido?" - usada tanto no poll (podeEnviar no JSON)
     * quanto nos dois endpoints de envio (classico e AJAX).
     *
     * <p><b>Simetria com o lado do operador (F5 do plano de vistoria de
     * chat, 2026-08-10, achado A8/decisao Q4 confirmada pelo usuario):</b> o
     * lado do OPERADOR (ProcessoDetalheController/
     * SolicitacaoOnlineTriagemController) sempre permitiu enviar mensagem,
     * mesmo com a solicitacao/processo ja CANCELADA - o operador podia
     * escrever "recebemos, vamos verificar" numa conversa em que o
     * solicitante ficava travado sem poder responder. A correcao escolhida
     * pelo usuario foi o caminho OPOSTO ao sugerido pelo relatorio original
     * (que propunha restringir o operador): afrouxar o lado do solicitante
     * para ficar simetrico ao operador, que ja e permissivo - o solicitante
     * pode continuar conversando com a equipe mesmo depois de cancelar o
     * proprio pedido (ex.: explicar o motivo do cancelamento, confirmar
     * algo, etc.).
     *
     * <p><b>{@code PROCESSO_EXCLUIDO} continua bloqueado</b> - estado
     * distinto e mais definitivo que CANCELADA: e o {@link Processo} gerado
     * a partir desta solicitacao ter sido EXCLUIDO pelo ADMIN
     * ({@code ProcessoService.excluir}), que desvincula a solicitacao do
     * processo (seta {@code processoGerado = null}) e a deixa orfa - a
     * propria mensagem exibida ao solicitante nesse estado
     * ({@link #montarSituacaoPedido}) ja orienta a enviar uma NOVA
     * solicitacao, nao a continuar esta conversa. Nao ha mais processo/
     * equipe ativa do outro lado desta thread especifica.
     */
    private boolean podeEnviarMensagem(StatusSolicitacaoOnline status) {
        return status != StatusSolicitacaoOnline.PROCESSO_EXCLUIDO;
    }

    /**
     * NOT_SUPPORTED: unica escrita relevante e {@code mensagemService.apagar(...)},
     * ja {@code @Transactional} no proprio servico. Sem isso, a
     * {@code IllegalArgumentException} (mensagem de outro autor, por exemplo)
     * envenenaria a transacao de classe e o catch abaixo devolveria o flash de
     * erro as custas de um {@code UnexpectedRollbackException} no commit.
     */
    @PostMapping("/{id}/mensagem/{mensagemId}/apagar")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String apagarMensagem(@PathVariable Long id, @PathVariable Long mensagemId,
                                  Principal principal, RedirectAttributes ra) {
        Usuario usuario = resolverUsuario(principal);
        try {
            mensagemService.apagar(mensagemId, usuario.getId(), MensagemSolicitacao.RemetenteMensagem.SOLICITANTE);
            // Auditoria de exclusao (S9, achado A15): so id da mensagem/solicitacao
            // + quem apagou, NUNCA o texto nem o nome completo do paciente.
            auditoria.registrar("MENSAGEM_APAGADA",
                "Solicitacao " + id + " - mensagem " + mensagemId + " apagada pelo solicitante");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/solicitante/" + id;
    }

    /**
     * Polling do chat (AJAX, chamado a cada poucos segundos pelo
     * chat-solicitacao.js) - devolve as mensagens ja projetadas pra tela e
     * marca como lidas, sem recarregar a pagina inteira.
     */
    @GetMapping("/{id}/mensagens")
    @ResponseBody
    @Transactional
    public Map<String, Object> mensagensJson(@PathVariable Long id, Principal principal) {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = resolverPropria(id, usuario);
        mensagemService.marcarComoLidas(id, MensagemSolicitacao.RemetenteMensagem.OPERADOR, usuario.getId());
        List<MensagemSolicitacaoService.MensagemChatView> mensagens = mensagemService.paraChat(
            id, MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, usuario.getId(), "Voce", "Equipe CET-RS");
        boolean podeEnviar = podeEnviarMensagem(s.getStatus());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("mensagens", mensagens);
        resp.put("podeEnviar", podeEnviar);
        return resp;
    }

    /** Sem try/catch em volta da escrita: leitura-escrita simples basta (nada a envenenar). */
    @PostMapping("/{id}/mensagem/ajax")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> enviarMensagemAjax(@PathVariable Long id, @RequestParam String texto,
                                                                    Principal principal) {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = conferirPosse(solicitacaoService.buscar(id), usuario);
        if (texto == null || texto.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("erro", "A mensagem não pode estar em branco."));
        }
        if (texto.length() > MensagemSolicitacaoService.TEXTO_MAX_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("erro", "A mensagem excede o limite de "
                + MensagemSolicitacaoService.TEXTO_MAX_LENGTH + " caracteres."));
        }
        if (!podeEnviarMensagem(s.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("erro",
                "Não é possível enviar mensagem para esta solicitação no estado atual "
                    + "(o processo gerado foi excluído)."));
        }
        mensagemService.enviar(s, texto, MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, usuario.getId());
        auditoria.registrar("MENSAGEM_SOLICITANTE_ENVIADA",
            "Solicitacao " + id + " - " + Iniciais.de(s.getPacienteNome()));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * NOT_SUPPORTED: unica escrita relevante e {@code mensagemService.apagar(...)},
     * ja {@code @Transactional} no proprio servico. Mesmo motivo de
     * {@link #apagarMensagem}, so que devolvendo JSON/400 em vez de redirect.
     */
    @PostMapping("/{id}/mensagem/{mensagemId}/apagar/ajax")
    @ResponseBody
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ResponseEntity<Map<String, Object>> apagarMensagemAjax(@PathVariable Long id, @PathVariable Long mensagemId,
                                                                    Principal principal) {
        Usuario usuario = resolverUsuario(principal);
        try {
            mensagemService.apagar(mensagemId, usuario.getId(), MensagemSolicitacao.RemetenteMensagem.SOLICITANTE);
            auditoria.registrar("MENSAGEM_APAGADA",
                "Solicitacao " + id + " - mensagem " + mensagemId + " apagada pelo solicitante");
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    /**
     * Download do proprio documento anexado. Busca o anexo PELO ID persistido
     * (nunca aceita caminho vindo do request) e so serve o arquivo se ele
     * pertencer a uma solicitacao do usuario logado - mesmo padrao de posse
     * de {@link #resolverPropria}.
     */
    @GetMapping("/{id}/anexos/{anexoId}")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> baixarAnexo(@PathVariable Long id, @PathVariable Long anexoId,
                                                Principal principal) throws MalformedURLException {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = resolverPropria(id, usuario);
        AnexoSolicitacaoOnline anexo = anexoRepo.findById(anexoId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!anexo.getSolicitacaoOnline().getId().equals(s.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este anexo não pertence a esta solicitação.");
        }
        Path arquivo = anexoStorage.resolverArquivo(anexo);
        Resource resource = new UrlResource(arquivo.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        String contentType = anexo.getContentType() != null
            ? anexo.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + anexo.getNomeArquivo() + "\"")
            .body(resource);
    }

    /**
     * Download do documento final (comprovante SNT ou oficio de indeferimento)
     * do PROCESSO gerado a partir deste pedido. Whitelist explicita de
     * TipoAnexo (nunca serve qualquer anexo do processo por ID - so estes
     * dois, os unicos que a resposta final ao solicitante deve expor) +
     * checagem de posse (o processo tem que ser o gerado por ESTA solicitacao
     * do usuario logado).
     */
    @GetMapping("/{id}/processo-anexo/{anexoId}")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> baixarAnexoProcesso(@PathVariable Long id, @PathVariable Long anexoId,
                                                         Principal principal) throws MalformedURLException {
        Usuario usuario = resolverUsuario(principal);
        SolicitacaoOnline s = resolverPropria(id, usuario);
        if (s.getProcessoGerado() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Anexo anexo;
        try {
            anexo = anexoStorageProcesso.buscar(anexoId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        boolean tipoPermitido = anexo.getTipo() == TipoAnexo.COMPROVANTE_SNT
            || anexo.getTipo() == TipoAnexo.OFICIO_INDEFERIMENTO;
        if (!tipoPermitido || !anexo.getProcesso().getId().equals(s.getProcessoGerado().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este anexo não pertence a este pedido.");
        }
        Path arquivo = anexoStorageProcesso.resolverArquivo(anexo);
        Resource resource = new UrlResource(arquivo.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        String contentType = anexo.getContentType() != null
            ? anexo.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + anexo.getNomeArquivo() + "\"")
            .body(resource);
    }

    /**
     * Resolve o usuario logado pelo username gravado na sessao.
     *
     * <p><b>Sessao orfa (mesmo bug real ja corrigido em
     * {@code AvaliadorController.resolverMembro}):</b> se o usuario
     * correspondente ao username da sessao nao existir mais no banco (ex.:
     * um ADMIN trocou o {@code username} desse solicitante em
     * {@code /usuarios}, ou excluiu a conta, enquanto ele tinha sessao ativa
     * — o Spring Security nao rele o {@code UserDetails} a cada requisicao),
     * lanca {@link SessaoInvalidaException} em vez de
     * {@code ResponseStatusException(UNAUTHORIZED)}. O
     * {@code GlobalExceptionHandler} trata esse tipo invalidando a sessao e
     * redirecionando para {@code /login} com mensagem clara, em vez do 401
     * cru que o navegador exibia antes.</p>
     */
    private Usuario resolverUsuario(Principal principal) {
        return usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new SessaoInvalidaException(
                "Usuario da sessao (" + principal.getName() + ") nao encontrado no banco."));
    }

    /** Garante que a solicitacao pertence ao usuario logado (nunca ve pedido de outra equipe). */
    private SolicitacaoOnline resolverPropria(Long id, Usuario usuario) {
        return conferirPosse(solicitacaoService.buscar(id), usuario);
    }

    /** Mesma checagem de posse, para quando a solicitacao ja foi carregada. */
    private SolicitacaoOnline conferirPosse(SolicitacaoOnline s, Usuario usuario) {
        if (!s.getUsuarioSolicitante().getId().equals(usuario.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Esta solicitação não pertence a você.");
        }
        return s;
    }
}

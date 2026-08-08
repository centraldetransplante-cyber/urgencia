package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.AnexoSolicitacaoOnline;
import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoSolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.AnexoSolicitacaoOnlineStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.Iniciais;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fila de triagem do OPERADOR/ADMIN para os pedidos enviados pelo Portal do
 * Solicitante (modulo experimental, ver docs/PLANO-SOLICITANTE.md).
 *
 * O operador revisa os dados enviados e decide: "converter" (segue para o
 * formulario normal de cadastro de processo, pre-preenchido - ver
 * ProcessoDetalheController.novo) ou "devolver" (pede correcao ao
 * solicitante). O acesso ja e restrito a ADMIN/OPERADOR pela regra
 * "/processos/**" do SecurityConfig, ja que esta rota vive sob /processos.
 *
 * <p><b>Sem {@code @Transactional} de nivel de classe (removido em
 * 2026-07-29).</b> Essa anotacao fazia {@link #apagarMensagem} e
 * {@link #devolver} compartilharem a MESMA transacao fisica com os servicos
 * chamados dentro do try/catch de cada um ({@code mensagemService.apagar}/
 * {@code service.devolver}, ambos {@code @Transactional} com propagacao
 * REQUIRED). Uma falha de negocio esperada nesses metodos (concorrencia,
 * mensagem de outro usuario) marcava a transacao compartilhada como
 * rollback-only: o {@code catch} tratava o erro e devolvia o flash amigavel
 * normalmente, mas o commit no fim do metodo do controller estourava
 * {@code UnexpectedRollbackException} (500 cru) — mesma classe de bug ja
 * corrigida em {@code AvaliadorController.registrarVoto} (voto perdido) e em
 * {@code ProcessoDecisaoController.finalizar}. Aqui nao ha nenhuma escrita
 * critica anterior ao try/catch que pudesse ser perdida (ao contrario do
 * voto do avaliador), entao {@code Propagation.NOT_SUPPORTED} sozinho basta
 * — nao precisou de {@code TransactionTemplate}. Cada metodo abaixo agora
 * declara sua PROPRIA estrategia de transacao, documentada individualmente.
 */
@Controller
@RequestMapping("/processos/solicitacoes-online")
@ConditionalOnProperty(prefix = "app.solicitante", name = "habilitado", havingValue = "true", matchIfMissing = true)
public class SolicitacaoOnlineTriagemController {

    private final SolicitacaoOnlineService service;
    private final AuditoriaService auditoria;
    private final MensagemSolicitacaoService mensagemService;
    private final UsuarioRepository usuarioRepo;
    private final AnexoSolicitacaoOnlineRepository anexoRepo;
    private final AnexoSolicitacaoOnlineStorageService anexoStorage;

    public SolicitacaoOnlineTriagemController(SolicitacaoOnlineService service, AuditoriaService auditoria,
            MensagemSolicitacaoService mensagemService,
            UsuarioRepository usuarioRepo,
            AnexoSolicitacaoOnlineRepository anexoRepo,
            AnexoSolicitacaoOnlineStorageService anexoStorage) {
        this.service = service;
        this.auditoria = auditoria;
        this.mensagemService = mensagemService;
        this.usuarioRepo = usuarioRepo;
        this.anexoRepo = anexoRepo;
        this.anexoStorage = anexoStorage;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String lista(@RequestParam(required = false, defaultValue = "pendentes") String filtro,
                        @RequestParam(required = false) String q, Model model) {
        boolean todas = "todas".equals(filtro);
        List<SolicitacaoOnline> solicitacoes = todas ? service.listarTodas(q) : service.listarPendentesTriagem(q);
        Map<Long, SolicitacaoOnlineService.DiasEspera> diasEspera = new LinkedHashMap<>();
        for (SolicitacaoOnline s : solicitacoes) {
            diasEspera.put(s.getId(), service.diasEspera(s));
        }
        model.addAttribute("solicitacoes", solicitacoes);
        model.addAttribute("diasEspera", diasEspera);
        model.addAttribute("filtro", todas ? "todas" : "pendentes");
        model.addAttribute("q", q);
        Set<Long> idsComMsgNaoLidaSolicitante = mensagemService.idsSolicitacoesComMsgNaoLidaSolicitante();
        model.addAttribute("idsComMsgNaoLidaSolicitante", idsComMsgNaoLidaSolicitante);
        return "processos/solicitacoes-online-lista";
    }

    @GetMapping("/{id}")
    @Transactional
    public String detalhe(@PathVariable Long id, Principal principal, Model model) {
        model.addAttribute("solicitacao", service.buscarParaDetalhe(id));
        List<MensagemSolicitacao> mensagens = mensagemService.listarPorSolicitacao(id);
        model.addAttribute("mensagens", mensagens);
        long msgNaoLidas = mensagens.stream()
            .filter(m -> !m.isLida() && m.getRemetente() == MensagemSolicitacao.RemetenteMensagem.SOLICITANTE)
            .count();
        model.addAttribute("msgNaoLidas", msgNaoLidas);
        // Evita notificacao duplicada: esta tela ja tem seu proprio poll de chat
        // (chat-solicitacao.js), entao o poll GLOBAL da navbar (layout.html) fica
        // desligado aqui - ver "chatAtivoNestaTela" em layout.html.
        model.addAttribute("chatAtivoNestaTela", true);
        Usuario operador = usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mensagemService.marcarComoLidas(id, MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, operador.getId());
        return "processos/solicitacoes-online-detalhe";
    }

    /**
     * Download, pelo OPERADOR/ADMIN em triagem, de um documento anexado a
     * ESTA solicitacao online (nunca aceita caminho vindo do request - busca
     * o anexo pelo ID persistido e confirma que ele pertence de fato a
     * solicitacao {@code id} da URL, mesmo padrao de posse de
     * {@link SolicitanteController#baixarAnexo}, so que aqui quem baixa e o
     * operador, nao o proprio solicitante). Sem essa checagem, um operador
     * poderia adivinhar o ID de um anexo de OUTRA solicitacao e baixa-lo por
     * aqui (IDOR). A rota ja e restrita a ADMIN/OPERADOR pela regra
     * "/processos/**" do SecurityConfig, ja que este controller vive sob
     * /processos.
     */
    @GetMapping("/{id}/anexo/{anexoId}")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> baixarAnexo(@PathVariable Long id, @PathVariable Long anexoId)
            throws MalformedURLException {
        AnexoSolicitacaoOnline anexo = anexoRepo.findById(anexoId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!anexo.getSolicitacaoOnline().getId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este anexo nao pertence a esta solicitacao.");
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
     * Contagem global de mensagens de solicitante ainda nao lidas, pra
     * notificacao sonora/toast em QUALQUER tela do operador (layout.html) -
     * as 3 telas de chat (aqui, /processos/{id}, /solicitante/{id}) ja tem
     * seu proprio poll especifico e nao usam este endpoint (ver
     * chatAtivoNestaTela).
     */
    @GetMapping("/nao-lidas-count")
    @ResponseBody
    // So le (delega para mensagemService.contarNaoLidasOperador, ja
    // @Transactional(readOnly = true) por conta propria); sem a transacao de
    // classe de antes, declarar aqui deixa explicito e evita depender do
    // metodo do service abrir a transacao sozinho.
    @Transactional(readOnly = true)
    public Map<String, Object> naoLidasCount() {
        return Map.of("total", mensagemService.contarNaoLidasOperador());
    }

    // Escrita direta via mensagemService.enviar (@Transactional propria), sem
    // try/catch de risco em volta - nenhuma excecao de negocio esperada aqui
    // que pudesse marcar rollback-only e ser escondida por um catch. Uma
    // transacao simples no metodo do controller basta.
    @PostMapping("/{id}/mensagem")
    @Transactional
    public String enviarMensagem(@PathVariable Long id, @RequestParam String texto,
            Principal principal, RedirectAttributes ra) {
        SolicitacaoOnline s = service.buscar(id);
        Usuario operador = usuarioRepo.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (texto == null || texto.isBlank()) {
            ra.addFlashAttribute("erro", "A mensagem nao pode estar em branco.");
            return "redirect:/processos/solicitacoes-online/" + id;
        }
        mensagemService.enviar(s, texto, MensagemSolicitacao.RemetenteMensagem.OPERADOR, operador.getId());
        auditoria.registrar("MENSAGEM_OPERADOR_ENVIADA",
                "Solicitacao " + id + " - resposta do operador " + operador.getUsername());
        return "redirect:/processos/solicitacoes-online/" + id;
    }

    /**
     * Apaga (soft delete) uma mensagem do proprio operador.
     *
     * <p>{@code Propagation.NOT_SUPPORTED}: suspende qualquer transacao
     * ambiente (nao ha mais transacao de classe desde 2026-07-29, mas o
     * atributo documenta a intencao mesmo assim) para que
     * {@code mensagemService.apagar} rode na SUA PROPRIA transacao,
     * independente deste metodo. {@code IllegalArgumentException} (mensagem
     * de outro usuario, ja apagada, etc.) e um erro de NEGOCIO esperado, nao
     * um bug - sem NOT_SUPPORTED, se este metodo algum dia ganhar uma
     * transacao de classe/metodo cobrindo o try/catch, a falha marcaria essa
     * transacao compartilhada como rollback-only e o commit no fim do
     * metodo estouraria {@code UnexpectedRollbackException} (500 cru) em vez
     * do flash de erro tratado abaixo - mesma familia de bug corrigida em
     * {@code AvaliadorController.registrarVoto}/{@code ProcessoDecisaoController.finalizar}.
     * Nao ha nenhuma escrita anterior neste metodo que precisasse ser
     * atomica com o apagar, entao NOT_SUPPORTED sozinho basta (sem
     * TransactionTemplate).
     */
    @PostMapping("/{id}/mensagem/{mensagemId}/apagar")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String apagarMensagem(@PathVariable Long id, @PathVariable Long mensagemId,
                                  Principal principal, RedirectAttributes ra) {
        Usuario operador = usuarioRepo.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        try {
            mensagemService.apagar(mensagemId, operador.getId(), MensagemSolicitacao.RemetenteMensagem.OPERADOR);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/processos/solicitacoes-online/" + id;
    }

    /**
     * Polling do chat (AJAX) - equivalente ao usado nas outras 2 telas de
     * chat. Sem try/catch, mas ESCREVE (marcarComoLidas antes de montar o
     * JSON) - precisa de transacao leitura-escrita direta no metodo (a
     * classe nao tem mais transacao ambiente desde 2026-07-29).
     */
    @GetMapping("/{id}/mensagens")
    @ResponseBody
    @Transactional
    public Map<String, Object> mensagensJson(@PathVariable Long id, Principal principal) {
        Usuario operador = usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mensagemService.marcarComoLidas(id, MensagemSolicitacao.RemetenteMensagem.SOLICITANTE, operador.getId());
        String nomeSolicitante = service.nomeSolicitante(id);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("mensagens", mensagemService.paraChat(
            id, MensagemSolicitacao.RemetenteMensagem.OPERADOR, operador.getId(), "Voce", nomeSolicitante));
        resp.put("podeEnviar", true);
        return resp;
    }

    // Mesmo raciocinio de enviarMensagem: escrita direta via
    // mensagemService.enviar (@Transactional propria), sem try/catch de
    // risco - transacao simples no metodo basta.
    @PostMapping("/{id}/mensagem/ajax")
    @ResponseBody
    @Transactional
    public org.springframework.http.ResponseEntity<Map<String, Object>> enviarMensagemAjax(@PathVariable Long id,
            @RequestParam String texto, Principal principal) {
        SolicitacaoOnline s = service.buscar(id);
        Usuario operador = usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (texto == null || texto.isBlank()) {
            return org.springframework.http.ResponseEntity.badRequest()
                .body(Map.of("erro", "A mensagem nao pode estar em branco."));
        }
        mensagemService.enviar(s, texto, MensagemSolicitacao.RemetenteMensagem.OPERADOR, operador.getId());
        auditoria.registrar("MENSAGEM_OPERADOR_ENVIADA",
            "Solicitacao " + id + " - resposta do operador " + operador.getUsername());
        return org.springframework.http.ResponseEntity.ok(Map.of("ok", true));
    }

    // Mesmo raciocinio de apagarMensagem (versao classica): NOT_SUPPORTED
    // para que mensagemService.apagar rode em transacao propria e a
    // IllegalArgumentException de negocio (400 tratado abaixo) nunca marque
    // uma transacao ambiente como rollback-only.
    @PostMapping("/{id}/mensagem/{mensagemId}/apagar/ajax")
    @ResponseBody
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public org.springframework.http.ResponseEntity<Map<String, Object>> apagarMensagemAjax(@PathVariable Long id,
            @PathVariable Long mensagemId, Principal principal) {
        Usuario operador = usuarioRepo.findByUsername(principal.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        try {
            mensagemService.apagar(mensagemId, operador.getId(), MensagemSolicitacao.RemetenteMensagem.OPERADOR);
            return org.springframework.http.ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return org.springframework.http.ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    /**
     * Encaminha para o formulario normal de cadastro, pre-preenchido com os dados
     * do pedido. Sem @Transactional: nao acessa banco nenhum, so monta a URL
     * do redirect com o id recebido (a leitura/validacao real acontece
     * depois, em ProcessoDetalheController.novo).
     */
    @GetMapping("/{id}/converter")
    public String converter(@PathVariable Long id) {
        return "redirect:/processos/novo?origemSolicitacaoOnlineId=" + id;
    }

    /**
     * Devolve a solicitacao ao solicitante para correcao.
     *
     * <p>{@code Propagation.NOT_SUPPORTED}: mesma razao de
     * {@link #apagarMensagem} - {@code service.devolver} tem sua propria
     * transacao, e a {@code IllegalStateException} de concorrencia (comentada
     * abaixo, "outro operador ja triou") e um erro de negocio esperado, nao
     * um bug. Sem isso, uma transacao ambiente cobrindo o try/catch marcaria
     * rollback-only nesse cenario e o commit do metodo estouraria
     * {@code UnexpectedRollbackException} em vez do flash de erro tratado.
     * {@code service.buscar(id)} antes do try e so leitura, sem nada que
     * precise ser atomico com o devolver.
     */
    @PostMapping("/{id}/devolver")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String devolver(@PathVariable Long id, @RequestParam String observacoes, RedirectAttributes ra) {
        SolicitacaoOnline s = service.buscar(id);
        try {
            service.devolver(id, observacoes);
            auditoria.registrar("SOLICITACAO_ONLINE_DEVOLVIDA",
                    "Solicitacao " + id + " - " + Iniciais.de(s.getPacienteNome()));
            ra.addFlashAttribute("msg", "Solicitacao devolvida para o solicitante.");
            return "redirect:/processos/solicitacoes-online";
        } catch (IllegalStateException e) {
            // Concorrencia: outro operador ja triou esta solicitacao entre a
            // abertura da tela e o submit do modal "Devolver". Volta para o
            // detalhe (nao para a lista) para nao perder o contexto/motivo
            // digitado e deixar claro o que aconteceu.
            ra.addFlashAttribute("erro", e.getMessage());
            return "redirect:/processos/solicitacoes-online/" + id;
        }
    }
}

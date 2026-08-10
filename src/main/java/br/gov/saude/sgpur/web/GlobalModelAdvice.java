package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.MensagemAvaliadorService;
import br.gov.saude.sgpur.service.MensagemSolicitacaoService;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * Atributos de model disponiveis em TODAS as views.
 *
 * Expoe {@code pendentesAvaliador} (contagem de processos que aguardam o voto
 * do medico logado, usada pelo sino da navbar) e {@code pendentesTriagemOnline}
 * (contagem de solicitacoes do Portal do Solicitante aguardando triagem,
 * mesmo padrao visual, restrito a ADMIN/OPERADOR).
 *
 * IMPARCIALIDADE: o contador de avaliador e apenas um numero — nunca expoe
 * nome de paciente, equipe solicitante ou co-avaliadores. So e calculado
 * quando o usuario tem ROLE_AVALIADOR e possui membro vinculado; para
 * ADMIN/OPERADOR fica em 0 e o badge nao e renderizado.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    private final UsuarioRepository usuarioRepo;
    private final ParecerRepository parecerRepo;
    private final SolicitacaoOnlineService solicitacaoOnlineService;
    /**
     * Opcional de proposito: {@code @Nullable} num construtor com um unico
     * candidato e o idioma oficial do Spring para injecao de dependencia
     * opcional (equivalente ao antigo campo {@code @Autowired(required =
     * false)}, sem precisar de reflection de campo). Continua {@code null}
     * quando o bean nao existe no contexto - a maioria dos {@code @WebMvcTest}
     * que carregam este advice nao mocka {@code MensagemSolicitacaoService}.
     */
    private final MensagemSolicitacaoService mensagemService;
    private final boolean solicitanteHabilitado;
    /** Mesmo padrao de opcional de {@link #mensagemService}: null nos @WebMvcTest que nao mockam o bean. */
    private final MensagemAvaliadorService mensagemAvaliadorService;

    public GlobalModelAdvice(UsuarioRepository usuarioRepo, ParecerRepository parecerRepo,
            SolicitacaoOnlineService solicitacaoOnlineService,
            @Nullable MensagemSolicitacaoService mensagemService,
            @Nullable MensagemAvaliadorService mensagemAvaliadorService,
            @Value("${app.solicitante.habilitado:true}") boolean solicitanteHabilitado) {
        this.mensagemService = mensagemService;
        this.mensagemAvaliadorService = mensagemAvaliadorService;
        this.usuarioRepo = usuarioRepo;
        this.parecerRepo = parecerRepo;
        this.solicitacaoOnlineService = solicitacaoOnlineService;
        this.solicitanteHabilitado = solicitanteHabilitado;
    }

    /**
     * Kill-switch do modulo experimental "Solicitacao Online" (ver
     * docs/PLANO-SOLICITANTE.md), exposto ao layout para esconder os links
     * de navegacao quando desligado (os controllers ja nem sao registrados
     * nesse caso - isto so evita mostrar um link morto).
     */
    @ModelAttribute("solicitanteHabilitado")
    public boolean solicitanteHabilitado() {
        return solicitanteHabilitado;
    }

    // Status que aceitam voto novo do avaliador (ver
    // StatusProcesso.aceitaVotoAvaliador()) - ENVIADO e SOLICITA_INFORMACAO
    // (a pausa causada por um avaliador nao deve esconder o processo da lista
    // de pendencias/badge dos outros dois, que continuam livres para votar).
    private static final List<StatusProcesso> STATUS_ACEITA_VOTO =
        List.of(StatusProcesso.ENVIADO, StatusProcesso.SOLICITA_INFORMACAO);

    // @Transactional(readOnly = true) mantido por seguranca/consistencia com os
    // demais @ModelAttribute deste advice (todos leem via repositorio), mas
    // deixou de ser estritamente NECESSARIO aqui: desde a troca para
    // countByMembroIdAndResultadoIsNullAndDataEnvioIsNotNullAndProcessoStatusIn
    // (query de COUNT direta no banco, sem carregar nenhuma entidade Parecer/
    // Processo), nao sobra navegacao LAZY nenhuma neste metodo — o antigo
    // comentario sobre LazyInitializationException valia para a versao anterior,
    // que carregava as entidades e navegava par.getProcesso() em Java.
    @ModelAttribute("pendentesAvaliador")
    @Transactional(readOnly = true)
    public long pendentesAvaliador() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !temPapelAvaliador(auth)) {
            return 0;
        }

        return usuarioRepo.findByUsername(auth.getName())
                .map(Usuario::getMembro)
                .map(MembroUrgenciaRenal::getId)
                .map(membroId -> parecerRepo.countByMembroIdAndResultadoIsNullAndDataEnvioIsNotNullAndProcessoStatusIn(
                        membroId, STATUS_ACEITA_VOTO))
                .orElse(0L);
    }

    /**
     * Contagem de solicitacoes online aguardando triagem, para o badge do
     * link "Solicitacoes online" na navbar - mesmo padrao visual do sino do
     * avaliador. So calculado para ADMIN/OPERADOR (perfis que acessam a
     * triagem) e quando o modulo esta habilitado; caso contrario fica em 0 e
     * o badge nao e renderizado.
     */
    @ModelAttribute("pendentesTriagemOnline")
    @Transactional(readOnly = true)
    public long pendentesTriagemOnline() {
        if (!solicitanteHabilitado) {
            return 0;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !temPapelOperadorOuAdmin(auth)) {
            return 0;
        }
        return solicitacaoOnlineService.contarPendentesTriagem();
    }

    /**
     * Contagem de mensagens de solicitantes ainda nao lidas por nenhum
     * ADMIN/OPERADOR, para o badge do link "Solicitacoes online" na navbar.
     */
    @ModelAttribute("mensagensNaoLidasOperador")
    @Transactional(readOnly = true)
    public long mensagensNaoLidasOperador() {
        if (mensagemService == null || !solicitanteHabilitado) {
            return 0;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !temPapelOperadorOuAdmin(auth)) {
            return 0;
        }
        return mensagemService.contarNaoLidasOperador();
    }

    /**
     * Contagem de mensagens de AVALIADORES ainda nao lidas por nenhum
     * ADMIN/OPERADOR, para o badge do link "Mensagens dos avaliadores" na
     * navbar (F5 do relatorio de chat). Independente de
     * {@code app.solicitante.habilitado} de proposito - este canal nao tem
     * nada a ver com o Portal do Solicitante.
     */
    @ModelAttribute("mensagensAvaliadorNaoLidasOperador")
    @Transactional(readOnly = true)
    public long mensagensAvaliadorNaoLidasOperador() {
        if (mensagemAvaliadorService == null) {
            return 0;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !temPapelOperadorOuAdmin(auth)) {
            return 0;
        }
        return mensagemAvaliadorService.contarNaoLidasParaOperador();
    }

    /**
     * Contagem de mensagens do OPERADOR ainda nao lidas pelo MEMBRO AVALIADOR
     * logado, somando todos os processos em que ele avalia - badge proprio
     * do avaliador na navbar (F4, S8.2 do
     * docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md, achado A10 - "o AVALIADOR
     * nao tem badge de mensagem nenhum"). NAO confundir com
     * {@link #pendentesAvaliador()}: aquele conta PARECERES pendentes de
     * voto; este conta MENSAGENS de chat nao lidas - dois conceitos
     * diferentes, dois badges diferentes na navbar.
     */
    @ModelAttribute("mensagensNaoLidasAvaliador")
    @Transactional(readOnly = true)
    public long mensagensNaoLidasAvaliador() {
        if (mensagemAvaliadorService == null) {
            return 0;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !temPapelAvaliador(auth)) {
            return 0;
        }
        return usuarioRepo.findByUsername(auth.getName())
                .map(Usuario::getMembro)
                .map(MembroUrgenciaRenal::getId)
                .map(mensagemAvaliadorService::contarNaoLidasParaMembro)
                .orElse(0L);
    }

    /**
     * Para onde o botao "voltar ao inicio" deve apontar para o usuario logado.
     *
     * <p><b>Motivacao (auditoria de UI, 2026-08-04).</b> A pagina de erro tinha
     * um unico botao, fixo em {@code /} - que o {@code SecurityConfig} restringe
     * a ADMIN/OPERADOR. Um AVALIADOR ou SOLICITANTE que caisse em qualquer erro
     * recebia um botao que levava a um 403, renderizando a mesma pagina de erro,
     * com o mesmo botao: <b>dois dos quatro perfis do sistema nao tinham saida
     * da tela de erro</b>.</p>
     */
    @ModelAttribute("inicioDoPerfil")
    public String inicioDoPerfil() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "/login";
        }
        if (temPapelAvaliador(auth)) {
            return "/avaliador";
        }
        if (temPapelSolicitante(auth)) {
            return "/solicitante";
        }
        if (temPapelOperadorOuAdmin(auth)) {
            return "/";
        }
        // Autenticado sem nenhum dos papeis conhecidos: o login e o unico
        // destino garantido (qualquer area do sistema devolveria 403).
        return "/login";
    }

    /**
     * URI da requisicao atual, relativa ao context-path (sempre comecando com
     * "/"), para a navbar destacar a secao em que o operador esta (S13 da
     * vistoria de UI de 2026-08-05).
     *
     * <p>Deliberadamente resolvido aqui, via parametro {@code HttpServletRequest}
     * injetado pelo Spring MVC, em vez de {@code #httpServletRequest} do
     * Thymeleaf: esse objeto de expressao do dialeto Spring depende de um
     * {@code IWebContext} que nao fica disponivel em todo cenario de teste
     * deste projeto (confirmado quebrando ~19 classes de {@code @WebMvcTest}/
     * {@code @SpringBootTest} com "Property or field 'contextPath' cannot be
     * found on null" ao tentar usa-lo direto no template) - um parametro de
     * controller/advice comum e testado pelo proprio MockMvc, sem essa
     * dependencia extra.</p>
     */
    @ModelAttribute("uriAtual")
    public String uriAtual(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return uri;
    }

    /**
     * Densidade visual do design system (ver "Design system - regua de
     * tokens" no CLAUDE.md): {@code "operacional"} para ADMIN/OPERADOR (mais
     * compacto - telas de trabalho de alto volume, ex. lista de processos),
     * {@code "confortavel"} para AVALIADOR/SOLICITANTE e visitante anonimo
     * (espacamento maior - telas de uso ocasional/publico). Lido pelo script
     * inline no inicio do fragment {@code navbar} de {@code layout.html},
     * que aplica o valor como {@code data-densidade} no {@code <html>}.
     */
    @ModelAttribute("densidadeAtual")
    public String densidadeAtual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && temPapelOperadorOuAdmin(auth)) {
            return "operacional";
        }
        return "confortavel";
    }

    private boolean temPapelSolicitante(Authentication auth) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_SOLICITANTE".equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private boolean temPapelOperadorOuAdmin(Authentication auth) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(ga.getAuthority()) || "ROLE_OPERADOR".equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private boolean temPapelAvaliador(Authentication auth) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_AVALIADOR".equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}

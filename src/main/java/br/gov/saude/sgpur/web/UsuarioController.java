package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.service.MembroUrgenciaRenalService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.PasswordResetService;
import br.gov.saude.sgpur.service.UsuarioService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/usuarios")
public class UsuarioController {

    private final UsuarioService service;
    private final AuditoriaService auditoria;
    private final MembroUrgenciaRenalService membroService;
    private final PasswordResetService passwordResetService;

    public UsuarioController(UsuarioService service, AuditoriaService auditoria,
                             MembroUrgenciaRenalService membroService,
                             PasswordResetService passwordResetService) {
        this.service = service;
        this.auditoria = auditoria;
        this.membroService = membroService;
        this.passwordResetService = passwordResetService;
    }

    @ModelAttribute("perfis")
    public Perfil[] perfis() {
        return Perfil.values();
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String listar(@RequestParam(required = false) String q, Model model) {
        model.addAttribute("usuarios", service.listar(q));
        model.addAttribute("q", q);
        return "usuarios/lista";
    }

    @GetMapping("/novo")
    public String novo(Model model) {
        model.addAttribute("usuario", new Usuario());
        model.addAttribute("edicao", false);
        model.addAttribute("membros", membroService.listarAtivos());
        return "usuarios/form";
    }

    @GetMapping("/{id}/editar")
    @Transactional(readOnly = true)
    public String editar(@PathVariable Long id, Model model) {
        model.addAttribute("usuario", service.buscar(id));
        model.addAttribute("edicao", true);
        model.addAttribute("membros", membroService.listarAtivos());
        return "usuarios/form";
    }

    @PostMapping
    public String criar(@Valid @ModelAttribute("usuario") Usuario usuario, BindingResult result,
                        @RequestParam String senha,
                        @RequestParam(required = false) Long membroId,
                        @RequestParam(required = false) String equipeSolicitante,
                        Model model, RedirectAttributes ra, HttpServletRequest request) {
        if (senha == null || senha.isBlank()) {
            result.rejectValue("senha", "obrigatorio", "Informe a senha.");
        }
        if (usuario.getEmail() == null || usuario.getEmail().isBlank()) {
            result.rejectValue("email", "obrigatorio", "Informe o e-mail.");
        }
        if (result.hasErrors()) {
            model.addAttribute("edicao", false);
            model.addAttribute("membros", membroService.listarAtivos());
            return "usuarios/form";
        }
        try {
            service.criar(usuario, senha, membroId, equipeSolicitante);
        } catch (IllegalArgumentException e) {
            model.addAttribute("edicao", false);
            model.addAttribute("membros", membroService.listarAtivos());
            model.addAttribute("erro", e.getMessage());
            return "usuarios/form";
        }
        auditoria.registrar("USUARIO_CRIADO", "Usuario " + usuario.getUsername(), request.getRemoteAddr());
        ra.addFlashAttribute("msg", "Usuario criado.");
        return "redirect:/usuarios";
    }

    @PostMapping("/{id}/editar")
    public String atualizar(@PathVariable Long id, @Valid @ModelAttribute("usuario") Usuario form,
                            BindingResult result,
                            @RequestParam(required = false) String senha,
                            @RequestParam(required = false) Long membroId,
                            @RequestParam(required = false) String equipeSolicitante,
                            Model model, RedirectAttributes ra, HttpServletRequest request) {
        if (form.getEmail() == null || form.getEmail().isBlank()) {
            result.rejectValue("email", "obrigatorio", "Informe o e-mail.");
        }
        if (result.hasErrors()) {
            model.addAttribute("edicao", true);
            model.addAttribute("membros", membroService.listarAtivos());
            return "usuarios/form";
        }
        try {
            service.atualizar(id, form, senha, membroId, equipeSolicitante);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erro", e.getMessage());
            return "redirect:/usuarios/" + id + "/editar";
        }
        auditoria.registrar("USUARIO_EDITADO", "Usuario id " + id, request.getRemoteAddr());
        ra.addFlashAttribute("msg", "Usuario atualizado.");
        return "redirect:/usuarios";
    }

    @PostMapping("/{id}/alternar-ativo")
    public String alternarAtivo(@PathVariable Long id, java.security.Principal principal,
                                RedirectAttributes ra) {
        try {
            service.alternarAtivo(id, principal == null ? null : principal.getName());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
            return "redirect:/usuarios";
        }
        ra.addFlashAttribute("msg", "Situacao do usuario atualizada.");
        return "redirect:/usuarios";
    }

    @PostMapping("/{id}/excluir")
    public String excluir(@PathVariable Long id, java.security.Principal principal,
                          RedirectAttributes ra, HttpServletRequest request) {
        try {
            service.excluir(id, principal == null ? null : principal.getName());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
            return "redirect:/usuarios";
        }
        auditoria.registrar("USUARIO_EXCLUIDO", "Usuario id " + id, request.getRemoteAddr());
        ra.addFlashAttribute("msg", "Usuario excluido.");
        return "redirect:/usuarios";
    }

    /**
     * Tela de troca da PROPRIA senha, disponivel para qualquer usuario logado
     * (ADMIN/OPERADOR/AVALIADOR) - diferente da edicao em /usuarios/{id}/editar,
     * que e exclusiva do ADMIN. A rota /usuarios/minha-senha e liberada para
     * autenticados no SecurityConfig, antes da regra geral /usuarios/** (ADMIN).
     */
    @GetMapping("/minha-senha")
    public String minhaSenha() {
        return "usuarios/minha-senha";
    }

    @PostMapping("/minha-senha")
    public String trocarMinhaSenha(java.security.Principal principal,
                                   @RequestParam String senhaAtual,
                                   @RequestParam String novaSenha,
                                   @RequestParam String confirmacao,
                                   RedirectAttributes ra, HttpServletRequest request) {
        try {
            service.alterarPropriaSenha(principal.getName(), senhaAtual, novaSenha, confirmacao);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("erro", e.getMessage());
            return "redirect:/usuarios/minha-senha";
        }
        auditoria.registrar("SENHA_ALTERADA", "Usuario " + principal.getName(), request.getRemoteAddr());
        ra.addFlashAttribute("msg", "Senha alterada com sucesso.");
        return "redirect:/usuarios/minha-senha";
    }

    @GetMapping("/esqueci-senha")
    public String esqueciSenha() {
        return "usuarios/esqueci-senha";
    }

    /**
     * Sempre exibe a mesma mensagem neutra, exista ou nao o usuario e tenha
     * ou nao e-mail cadastrado - evita que a tela seja usada para descobrir
     * quais logins sao validos (enumeracao de usuarios).
     *
     * <p>Gera o token (transacional, ja comitado quando o metodo do service
     * retorna) e SO DEPOIS tenta enviar o e-mail com o link - nunca dentro da
     * transacao que persiste o token (achado E da vistoria de 2026-08-24, ver
     * javadoc de {@code PasswordResetService}). Falha de SMTP aqui vira so um
     * log de aviso, nunca some da mensagem neutra ao usuario.
     */
    @PostMapping("/esqueci-senha")
    public String redefinirSenha(@RequestParam String username, Model model, HttpServletRequest request) {
        passwordResetService.gerarTokenResetSenha(username)
            .ifPresent(passwordResetService::enviarEmail);
        auditoria.registrar("SENHA_RESET_SOLICITADO", "Usuario " + username, request.getRemoteAddr());
        model.addAttribute("sucesso", true);
        model.addAttribute("msgRedefinicao",
            "Se o login existir e tiver e-mail cadastrado, enviamos um link de "
            + "redefinicao para o e-mail cadastrado, valido por um tempo limitado. "
            + "Caso nao tenha e-mail cadastrado, procure o administrador do sistema.");
        return "usuarios/esqueci-senha";
    }

    /**
     * Formulario de nova senha a partir do link recebido por e-mail. Exibe o
     * mesmo card de erro generico para token invalido/expirado/ja usado - nao
     * revela qual dos tres motivos, so a mensagem varia por UX.
     */
    @GetMapping("/redefinir-senha")
    public String redefinirSenhaForm(@RequestParam String token, Model model) {
        PasswordResetService.EstadoToken estado = passwordResetService.validar(token);
        if (estado != PasswordResetService.EstadoToken.VALIDO) {
            model.addAttribute("erroToken", mensagemErroToken(estado));
            return "usuarios/redefinir-senha";
        }
        model.addAttribute("token", token);
        return "usuarios/redefinir-senha";
    }

    @PostMapping("/redefinir-senha")
    public String redefinirSenhaConfirmar(@RequestParam String token,
                                          @RequestParam String novaSenha,
                                          @RequestParam String confirmacao,
                                          Model model, RedirectAttributes ra, HttpServletRequest request) {
        try {
            passwordResetService.confirmarNovaSenha(token, novaSenha, confirmacao);
        } catch (IllegalArgumentException e) {
            // Token continua invalido/expirado/ja usado, ou a senha nao passou
            // na politica - reexibe o form (com o token, se ele ainda for
            // "abrivel") em vez de redirecionar, para preservar a mensagem.
            PasswordResetService.EstadoToken estado = passwordResetService.validar(token);
            if (estado == PasswordResetService.EstadoToken.VALIDO) {
                model.addAttribute("token", token);
            } else {
                model.addAttribute("erroToken", mensagemErroToken(estado));
            }
            model.addAttribute("erro", e.getMessage());
            return "usuarios/redefinir-senha";
        }
        auditoria.registrar("SENHA_RESET_CONFIRMADO", "Token de reset de senha confirmado",
            request.getRemoteAddr());
        ra.addFlashAttribute("msg", "Senha redefinida com sucesso. Faca login com a nova senha.");
        return "redirect:/login";
    }

    private String mensagemErroToken(PasswordResetService.EstadoToken estado) {
        return switch (estado) {
            case JA_USADO -> "Este link de redefinição já foi utilizado. Solicite um novo.";
            case EXPIRADO -> "Este link de redefinição expirou. Solicite uma nova redefinição de senha.";
            default -> "Link de redefinição inválido. Solicite uma nova redefinição de senha.";
        };
    }
}

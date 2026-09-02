package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.RoboNavegadorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
@RequestMapping("/admin/robo")
public class RoboNavegadorController {

    private final RoboNavegadorService robo;
    private final AuditoriaService auditoria;

    public RoboNavegadorController(RoboNavegadorService robo, AuditoriaService auditoria) {
        this.robo = robo;
        this.auditoria = auditoria;
    }

    @PostMapping("/executar")
    public String executar(Principal principal, HttpServletRequest request, RedirectAttributes ra) {
        String usuario = principal == null ? "desconhecido" : principal.getName();
        if (!robo.iniciar()) {
            auditoria.registrar("ROBO_PRODUCAO_RECUSADO", "Execucao ja em andamento por " + usuario,
                    request.getRemoteAddr());
            ra.addFlashAttribute("aviso", "O robo ja esta em execucao.");
            return "redirect:/usuarios";
        }
        auditoria.registrar("ROBO_PRODUCAO_INICIADO", "Execucao solicitada por " + usuario,
                request.getRemoteAddr());
        ra.addFlashAttribute("msg", "Robo iniciado em modo producao com o login ADMIN.");
        return "redirect:/usuarios";
    }
}

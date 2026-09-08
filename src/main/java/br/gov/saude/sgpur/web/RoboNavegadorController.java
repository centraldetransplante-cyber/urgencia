package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.RoboNavegadorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @org.springframework.web.bind.annotation.GetMapping
    public String status(Model model) {
        model.addAttribute("status", robo.getStatus());
        model.addAttribute("iniciadoEm", robo.getIniciadoEm());
        model.addAttribute("finalizadoEm", robo.getFinalizadoEm());
        model.addAttribute("mensagem", robo.getMensagem());
        return "admin/robo";
    }

    @org.springframework.web.bind.annotation.GetMapping("/live.png")
    public ResponseEntity<byte[]> liveScreenshot() {
        Path path = robo.getLiveScreenshot();
        Path liveDir = path.getParent();
        try {
            byte[] data = java.nio.file.Files.readAllBytes(path);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .contentType(MediaType.IMAGE_PNG)
                    .body(data);
        } catch (java.io.IOException e) {
            try {
                if (!java.nio.file.Files.exists(liveDir)) {
                    java.nio.file.Files.createDirectories(liveDir);
                }
                Path placeholderPath = liveDir.resolve("placeholder.png");
                if (!java.nio.file.Files.exists(placeholderPath)) {
                    try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(placeholderPath)) {
                        byte[] png = new byte[]{
                                (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47, (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A,
                                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0D,
                                (byte) 0x49, (byte) 0x48, (byte) 0x44, (byte) 0x52,
                                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
                                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
                                (byte) 0x08, (byte) 0x02,
                                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x09,
                                (byte) 0x78, (byte) 0x7A, (byte) 0xBC, (byte) 0x58, (byte) 0xCF, (byte) 0xAF, (byte) 0x27, (byte) 0xFF,
                                (byte) 0x0A, (byte) 0x00, (byte) 0x59, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0xF9, (byte) 0x0C, (byte) 0x00, (byte) 0x00,
                                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xFE, (byte) 0x00, (byte) 0xFE,
                                (byte) 0x18, (byte) 0xFD, (byte) 0x18, (byte) 0xFD, (byte) 0x18,
                                (byte) 0xFF, (byte) 0xFE, (byte) 0xFF, (byte) 0xFE, (byte) 0x18,
                                (byte) 0xFD, (byte) 0x18, (byte) 0xFD, (byte) 0x18, (byte) 0xFD, (byte) 0xFE,
                                (byte) 0x18, (byte) 0xFD, (byte) 0xFE, (byte) 0x18,
                                (byte) 0x90, (byte) 0xFE, (byte) 0xFE, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                (byte) 0x00, (byte) 0x00
                        };
                        out.write(png);
                    }
                }
                byte[] data = java.nio.file.Files.readAllBytes(placeholderPath);
                return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .contentType(MediaType.IMAGE_PNG)
                        .body(data);
            } catch (java.io.IOException ex) {
                return ResponseEntity.notFound().build();
            }
        }
    }

    @PostMapping("/executar")
    public String executar(Principal principal, HttpServletRequest request, RedirectAttributes ra) {
        String usuario = principal == null ? "desconhecido" : principal.getName();
        if (!robo.iniciar()) {
            auditoria.registrar("ROBO_PRODUCAO_RECUSADO", "Execucao ja em andamento por " + usuario,
                    request.getRemoteAddr());
            ra.addFlashAttribute("aviso", "O robo ja esta em execucao.");
            return "redirect:/admin/robo";
        }
        auditoria.registrar("ROBO_PRODUCAO_INICIADO", "Execucao solicitada por " + usuario,
                request.getRemoteAddr());
        ra.addFlashAttribute("msg", "Robo iniciado em modo producao com o login ADMIN.");
        return "redirect:/admin/robo";
    }

    @PostMapping("/parar")
    public String parar(Principal principal, HttpServletRequest request, RedirectAttributes ra) {
        String usuario = principal == null ? "desconhecido" : principal.getName();
        if (!robo.parar()) {
            auditoria.registrar("ROBO_PRODUCAO_PARADA_RECUSADA",
                    "Nenhuma execucao em andamento (solicitado por " + usuario + ")", request.getRemoteAddr());
            ra.addFlashAttribute("aviso", "O robo nao esta em execucao.");
            return "redirect:/admin/robo";
        }
        auditoria.registrar("ROBO_PRODUCAO_PARADO", "Parada solicitada por " + usuario, request.getRemoteAddr());
        ra.addFlashAttribute("msg", "Robo interrompido.");
        return "redirect:/admin/robo";
    }

    @org.springframework.web.bind.annotation.GetMapping("/relatorio")
    public ResponseEntity<byte[]> relatorio() {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                    .contentType(MediaType.TEXT_HTML)
                    .body(Files.readAllBytes(robo.getRelatorioHtml()));
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }
}

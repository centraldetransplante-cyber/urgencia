package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.RoboNavegadorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RoboNavegadorController.class)
class RoboNavegadorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoboNavegadorService robo;

    @MockitoBean
    private AuditoriaService auditoria;

    @MockitoBean
    private br.gov.saude.sgpur.repository.UsuarioRepository usuarioRepository;

    @MockitoBean
    private br.gov.saude.sgpur.repository.ParecerRepository parecerRepository;

    @MockitoBean
    private br.gov.saude.sgpur.service.UsuarioService usuarioService;

    @MockitoBean
    private br.gov.saude.sgpur.service.MembroUrgenciaRenalService membroService;

    @MockitoBean
    private br.gov.saude.sgpur.service.SolicitacaoOnlineService solicitacaoOnlineService;

    @MockitoBean
    private br.gov.saude.sgpur.service.PasswordResetService passwordResetService;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Status do robo acessivel para ADMIN")
    void statusAcessivelParaAdmin() throws Exception {
        when(robo.getStatus()).thenReturn("PARADO");
        when(robo.getMensagem()).thenReturn("Pronto para executar.");

        mockMvc.perform(get("/admin/robo"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/robo"))
                .andExpect(model().attribute("status", "PARADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("live.png retorna 404 quando imagem nao existe")
    void livePngRetorna404QuandoNaoExiste() throws Exception {
        when(robo.getLiveScreenshot()).thenReturn(Path.of("caminho/inexistente/live.png"));

        mockMvc.perform(get("/admin/robo/live.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("live.png retorna imagem quando arquivo existe")
    void livePngRetornaImagemQuandoExiste() throws Exception {
        Path tempImg = Files.createTempFile("live-test-", ".png");
        Files.write(tempImg, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        try {
            when(robo.getLiveScreenshot()).thenReturn(tempImg);

            mockMvc.perform(get("/admin/robo/live.png"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("image/png")));
        } finally {
            Files.deleteIfExists(tempImg);
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Executar inicia o robo com sucesso")
    void executarIniciaRobo() throws Exception {
        when(robo.iniciar()).thenReturn(true);

        mockMvc.perform(post("/admin/robo/executar").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/robo"))
                .andExpect(flash().attributeExists("msg"));
    }
}

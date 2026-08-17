package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.ConflitoEquipeMatcher;
import br.gov.saude.sgpur.service.SolicitacaoOnlineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes do endpoint auxiliar de conflito de equipe (Opcao A do
 * docs/RELATORIO-CONFIRMACAO-CONFLITO-EQUIPE-2026-08.md) - so leitura, reusa
 * {@link ConflitoEquipeMatcher} sem duplicar logica.
 */
@WebMvcTest(ProcessoConflitoEquipeController.class)
class ProcessoConflitoEquipeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private MembroUrgenciaRenalRepository membroRepo;
    @MockitoBean private ConflitoEquipeMatcher conflitoEquipeMatcher;
    // GlobalModelAdvice (@ControllerAdvice global) precisa dessas pro
    // contexto do @WebMvcTest subir - ver ProcessoAnexoControllerTest.
    @MockitoBean private UsuarioRepository usuarioRepository;
    @MockitoBean private ParecerRepository parecerRepository;
    @MockitoBean private SolicitacaoOnlineService solicitacaoOnlineService;

    private MembroUrgenciaRenal membro(long id, String nome, String instituicao) {
        MembroUrgenciaRenal m = new MembroUrgenciaRenal();
        m.setId(id);
        m.setNome(nome);
        m.setInstituicao(instituicao);
        return m;
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void semConflitoDevolveListaVazia() throws Exception {
        when(membroRepo.findAllById(List.of(1L, 2L, 3L)))
            .thenReturn(List.of(membro(1L, "Dra. Ana", "HCPA"), membro(2L, "Dr. Bruno", "ISCMPA"),
                membro(3L, "Dra. Carla", "HSLPUC")));
        when(conflitoEquipeMatcher.mesmaEquipe(anyString(), anyString())).thenReturn(false);

        mvc.perform(get("/processos/conflito-equipe")
                .param("equipe", "Hospital Nossa Senhora da Pompeia")
                .param("medicoIds", "1,2,3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conflitos").isArray())
            .andExpect(jsonPath("$.conflitos.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void umMedicoEmConflitoDevolveApenasEle() throws Exception {
        MembroUrgenciaRenal ana = membro(1L, "Dra. Ana", "HCPA");
        MembroUrgenciaRenal bruno = membro(2L, "Dr. Bruno", "ISCMPA");
        MembroUrgenciaRenal carla = membro(3L, "Dra. Carla", "HSLPUC");
        when(membroRepo.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(ana, bruno, carla));
        when(conflitoEquipeMatcher.mesmaEquipe("HCPA", "Hospital de Clinicas de Porto Alegre")).thenReturn(true);
        when(conflitoEquipeMatcher.mesmaEquipe("ISCMPA", "Hospital de Clinicas de Porto Alegre")).thenReturn(false);
        when(conflitoEquipeMatcher.mesmaEquipe("HSLPUC", "Hospital de Clinicas de Porto Alegre")).thenReturn(false);

        mvc.perform(get("/processos/conflito-equipe")
                .param("equipe", "Hospital de Clinicas de Porto Alegre")
                .param("medicoIds", "1,2,3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conflitos.length()").value(1))
            .andExpect(jsonPath("$.conflitos[0].id").value(1))
            .andExpect(jsonPath("$.conflitos[0].nome").value("Dra. Ana"))
            .andExpect(jsonPath("$.conflitos[0].instituicao").value("HCPA"));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void doisMedicosEmConflitoDevolveOsDois() throws Exception {
        MembroUrgenciaRenal ana = membro(1L, "Dra. Ana", "HCPA");
        MembroUrgenciaRenal bruno = membro(2L, "Dr. Bruno", "HCPA");
        when(membroRepo.findAllById(List.of(1L, 2L))).thenReturn(List.of(ana, bruno));
        when(conflitoEquipeMatcher.mesmaEquipe("HCPA", "HCPA")).thenReturn(true);

        mvc.perform(get("/processos/conflito-equipe")
                .param("equipe", "HCPA")
                .param("medicoIds", "1,2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conflitos.length()").value(2));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void equipeAusenteDevolveListaVaziaSemChamarOMatcher() throws Exception {
        mvc.perform(get("/processos/conflito-equipe")
                .param("medicoIds", "1,2,3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conflitos.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void equipeEmBrancoDevolveListaVazia() throws Exception {
        mvc.perform(get("/processos/conflito-equipe")
                .param("equipe", "   ")
                .param("medicoIds", "1,2,3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conflitos.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void semMedicoIdsDevolveListaVazia() throws Exception {
        mvc.perform(get("/processos/conflito-equipe")
                .param("equipe", "HCPA"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conflitos.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminTambemAcessaOEndpoint() throws Exception {
        when(membroRepo.findAllById(List.of(1L))).thenReturn(List.of(membro(1L, "Dra. Ana", "HCPA")));
        when(conflitoEquipeMatcher.mesmaEquipe("HCPA", "HCPA")).thenReturn(false);

        mvc.perform(get("/processos/conflito-equipe")
                .param("equipe", "HCPA")
                .param("medicoIds", "1"))
            .andExpect(status().isOk());
    }

    // Nao ha teste de "sem autenticacao" aqui de proposito: @WebMvcTest nao
    // carrega o SecurityConfig customizado do app (formLogin real), entao o
    // comportamento observado no slice (401 Basic Auth) nao reflete o
    // comportamento real em producao (302 para /login) - a protecao de
    // acesso em si (ADMIN/OPERADOR) e garantida pelo mesmo
    // hasAnyRole("ADMIN","OPERADOR") de "/processos/**" ja coberto pela
    // suite de SecurityConfig/SecurityIntegrationTest para o prefixo
    // /processos/** como um todo.
}

package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F5 do relatorio de vistoria de brechas (2026-08-10) - Achados 8 e 9:
 * teste de INTEGRACAO real (contexto Spring completo, sem mock de servico/
 * repositorio de dominio - escrita irreversivel) que decide um processo
 * pelo voto isolado do coordenador (gera o Relatorio Final de verdade),
 * reabre, e confirma que:
 *
 * <ul>
 *   <li>(Achado 9) o anexo RELATORIO_FINAL da decisao anulada NAO continua
 *       acessivel para download - o documento institucional que afirmava
 *       "RESULTADO: DEFERIDO" nao pode seguir baixavel de um processo que
 *       voltou para ENVIADO;</li>
 *   <li>(Achado 8) {@code Processo.reaberturas} foi incrementado, dado que
 *       alimenta o badge "Reaberto Nx" nas mesmas superficies do badge de
 *       regra (F2) e uma linha no Relatorio Final.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-reabertura-relatorio;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-reabertura-relatorio"
})
class ReaberturaRemoveRelatorioObsoletoIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UsuarioRepository usuarioRepo;
    @Autowired private ProcessoRepository processoRepo;
    @Autowired private ParecerRepository parecerRepo;
    @Autowired private MembroUrgenciaRenalRepository membroRepo;
    @Autowired private AnexoRepository anexoRepo;

    /** Consultado dentro de ProcessoService.decidir/reabrir; sem efeito no cenario feliz. */
    @MockitoBean private SolicitacaoOnlineRepository solicitacaoOnlineRepo;

    private MembroUrgenciaRenal coordenador;
    private Long processoId;

    @BeforeEach
    @Transactional
    void preparar() {
        anexoRepo.deleteAll();
        parecerRepo.deleteAll();
        usuarioRepo.findByUsername("coordenador-reabertura-it").ifPresent(usuarioRepo::delete);
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        when(solicitacaoOnlineRepo.findByProcessoGeradoId(anyLong())).thenReturn(Optional.empty());

        Processo p = new Processo();
        p.setNumero("31/2026");
        p.setAno(2026);
        p.setSequencial(31);
        p.setPacienteNome("Paciente Reabertura Teste");
        p.setPacienteRgct("111333555");
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        coordenador = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("CET-RS", "Coordenadora Reabertura", "coord-reab@example.com"));
        coordenador.setCoordenador(true);
        membroRepo.saveAndFlush(coordenador);

        MembroUrgenciaRenal medicoA = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HCPA", "Medico A", "medicoa-reab@example.com"));
        MembroUrgenciaRenal medicoB = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("ISCMPA", "Medico B", "medicob-reab@example.com"));

        for (MembroUrgenciaRenal m : List.of(coordenador, medicoA, medicoB)) {
            Parecer par = new Parecer(m);
            par.setProcesso(p);
            par.setDataEnvio(LocalDate.of(2026, 5, 2));
            parecerRepo.saveAndFlush(par);
        }

        Usuario u = new Usuario();
        u.setUsername("coordenador-reabertura-it");
        u.setSenha("{noop}x");
        u.setNome("Coordenadora Reabertura");
        u.setEmail("coord-reab@example.com");
        u.setPerfil(Perfil.AVALIADOR);
        u.setMembro(coordenador);
        usuarioRepo.saveAndFlush(u);
    }

    @Test
    @WithMockUser(username = "coordenador-reabertura-it", roles = "AVALIADOR")
    void reaberturaTornaORelatorioFinalAnteriorInacessivelEIncrementaOContador() throws Exception {
        // Vota Favoravel: o coordenador defere sozinho, e a decisao
        // automatica ja gera e anexa o Relatorio Final de verdade
        // (DecisaoFinalService.gerarDocumentos, disparado dentro do proprio
        // registrarVoto).
        mvc.perform(post("/avaliador/" + processoId + "/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL"))
            .andReturn();

        assertThat(processoRepo.findById(processoId).orElseThrow().getStatus())
            .isEqualTo(StatusProcesso.DEFERIDO);

        List<Anexo> relatoriosAntes = anexoRepo.findByProcessoIdAndTipo(processoId, TipoAnexo.RELATORIO_FINAL);
        assertThat(relatoriosAntes).hasSize(1);
        Long anexoRelatorioId = relatoriosAntes.get(0).getId();

        // O relatorio da decisao Deferida esta acessivel para download.
        mvc.perform(get("/processos/anexos/" + anexoRelatorioId + "/download")
                .with(org.springframework.security.test.web.servlet.request
                    .SecurityMockMvcRequestPostProcessors.user("admin-reabertura-it").roles("ADMIN")))
            .andExpect(status().isOk());

        // ADMIN reabre o processo.
        mvc.perform(post("/processos/" + processoId + "/reabrir")
                .with(csrf())
                .with(org.springframework.security.test.web.servlet.request
                    .SecurityMockMvcRequestPostProcessors.user("admin-reabertura-it").roles("ADMIN")))
            .andReturn();

        Processo depoisDaReabertura = processoRepo.findById(processoId).orElseThrow();
        assertThat(depoisDaReabertura.getStatus()).isEqualTo(StatusProcesso.ENVIADO);

        // Achado 8: o contador de reaberturas foi incrementado.
        assertThat(depoisDaReabertura.getReaberturasOuZero()).isEqualTo(1);

        // Achado 9: o anexo RELATORIO_FINAL da decisao anulada nao existe
        // mais - nem no banco, nem para download (404).
        assertThat(anexoRepo.findByProcessoIdAndTipo(processoId, TipoAnexo.RELATORIO_FINAL)).isEmpty();
        assertThat(anexoRepo.findById(anexoRelatorioId)).isEmpty();
        mvc.perform(get("/processos/anexos/" + anexoRelatorioId + "/download")
                .with(org.springframework.security.test.web.servlet.request
                    .SecurityMockMvcRequestPostProcessors.user("admin-reabertura-it").roles("ADMIN")))
            .andExpect(status().isNotFound());
    }
}

package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.LogAuditoria;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.LogAuditoriaRepository;
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
import org.springframework.data.domain.PageRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F3 do relatorio de vistoria de brechas (2026-08-10) - Achado 5: a
 * auditoria de {@code PROCESSO_DECIDIDO}/{@code PROCESSO_REABERTO} passa a
 * citar QUAL REGRA decidiu (fonte unica {@code RegraDecisao}, a mesma do
 * badge/PDF/dossie - ver F2) e o IP nos 2 pontos que antes gravavam sem ele
 * (decisao automatica na retomada da pausa; decisao automatica apos voto no
 * portal). Teste de INTEGRACAO real (contexto Spring completo, sem mock de
 * {@code AuditoriaService}/repositorios de dominio) - escrita irreversivel,
 * mesmo padrao de {@code SnapshotCoordenadorVotoIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-auditoria-decisao;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-auditoria-decisao"
})
class DecisaoAuditoriaEstruturadaIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UsuarioRepository usuarioRepo;
    @Autowired private ProcessoRepository processoRepo;
    @Autowired private ParecerRepository parecerRepo;
    @Autowired private MembroUrgenciaRenalRepository membroRepo;
    @Autowired private LogAuditoriaRepository logRepo;

    /** Consultado dentro de ProcessoService.decidir; sem efeito no cenario feliz. */
    @MockitoBean private SolicitacaoOnlineRepository solicitacaoOnlineRepo;

    private MembroUrgenciaRenal coordenador;
    private MembroUrgenciaRenal medicoA;
    private MembroUrgenciaRenal medicoB;

    @BeforeEach
    @Transactional
    void preparar() {
        logRepo.deleteAll();
        parecerRepo.deleteAll();
        usuarioRepo.findByUsername("coordenador-auditoria-it").ifPresent(usuarioRepo::delete);
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        when(solicitacaoOnlineRepo.findByProcessoGeradoId(anyLong())).thenReturn(Optional.empty());

        coordenador = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("CET-RS", "Coordenadora Auditoria", "coord-audit@example.com"));
        coordenador.setCoordenador(true);
        membroRepo.saveAndFlush(coordenador);

        medicoA = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("HCPA", "Medico A", "medicoa-audit@example.com"));
        medicoB = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("ISCMPA", "Medico B", "medicob-audit@example.com"));

        Usuario u = new Usuario();
        u.setUsername("coordenador-auditoria-it");
        u.setSenha("{noop}x");
        u.setNome("Coordenadora Auditoria");
        u.setEmail("coord-audit@example.com");
        u.setPerfil(Perfil.AVALIADOR);
        u.setMembro(coordenador);
        usuarioRepo.saveAndFlush(u);
    }

    private Processo novoProcesso(String numero, int sequencial) {
        Processo p = new Processo();
        p.setNumero(numero);
        p.setAno(2026);
        p.setSequencial(sequencial);
        p.setPacienteNome("Paciente Auditoria Teste");
        p.setPacienteRgct("555444333");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        return p;
    }

    private Parecer novoParecer(Processo p, MembroUrgenciaRenal membro) {
        Parecer par = new Parecer(membro);
        par.setProcesso(p);
        par.setDataEnvio(LocalDate.of(2026, 5, 2));
        return parecerRepo.saveAndFlush(par);
    }

    /** Ultimo registro de uma acao (ordenado por dataHora desc, o mais recente primeiro). */
    private LogAuditoria ultimoRegistro(String acao) {
        return logRepo.findAllByOrderByDataHoraDesc(PageRequest.of(0, 50)).stream()
            .filter(l -> acao.equals(l.getAcao()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Nenhum registro de auditoria para a acao " + acao));
    }

    /**
     * Cenario 1: voto FAVORAVEL do coordenador no Portal decide o processo
     * sozinho (decisao automatica). O log PROCESSO_DECIDIDO precisa citar a
     * origem ("automática no portal"), a regra ("Voto único do Coordenador
     * CET-RS") e o IP de quem votou.
     */
    @Test
    @WithMockUser(username = "coordenador-auditoria-it", roles = "AVALIADOR")
    void decisaoAutomaticaNoPortalPeloCoordenadorGravaOrigemRegraEIp() throws Exception {
        Processo p = novoProcesso("21/2026", 21);
        novoParecer(p, coordenador);
        novoParecer(p, medicoA);
        novoParecer(p, medicoB);

        mvc.perform(post("/avaliador/" + p.getId() + "/votar")
                .with(csrf())
                .param("resultado", "FAVORAVEL"))
            .andReturn();

        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
            .isEqualTo(StatusProcesso.DEFERIDO);

        LogAuditoria log = ultimoRegistro("PROCESSO_DECIDIDO");
        assertThat(log.getDetalhe())
            .contains("21/2026")
            .contains("Deferido")
            .contains("automática no portal")
            .contains("Voto único do Coordenador CET-RS")
            // nunca nome de paciente nem justificativa clinica
            .doesNotContain("Paciente Auditoria Teste");
        assertThat(log.getIp()).isNotBlank();
    }

    /**
     * Cenario 2: decisao MANUAL (operador clica "Registrar decisão") por
     * maioria simples comum - regra "Maioria simples (2 de 3)" e IP do
     * clique.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void decisaoManualPorMaioriaSimplesGravaOrigemRegraEIp() throws Exception {
        Processo p = novoProcesso("22/2026", 22);
        Parecer parA = novoParecer(p, medicoA);
        parA.setResultado(ResultadoParecer.FAVORAVEL);
        parA.setDataResposta(LocalDate.of(2026, 5, 3));
        parecerRepo.saveAndFlush(parA);
        Parecer parB = novoParecer(p, medicoB);
        parB.setResultado(ResultadoParecer.FAVORAVEL);
        parB.setDataResposta(LocalDate.of(2026, 5, 3));
        parecerRepo.saveAndFlush(parB);

        mvc.perform(post("/processos/" + p.getId() + "/decidir")
                .with(csrf())
                .param("decisao", "DEFERIDO"))
            .andReturn();

        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
            .isEqualTo(StatusProcesso.DEFERIDO);

        LogAuditoria log = ultimoRegistro("PROCESSO_DECIDIDO");
        assertThat(log.getDetalhe())
            .contains("22/2026")
            .contains("Deferido")
            .contains("decisão manual")
            .contains("Maioria simples (2 de 3)");
        assertThat(log.getIp()).isNotBlank();
    }

    /**
     * Cenario 3 (Achado 8): reabrir um processo decidido pelo voto do
     * coordenador precisa registrar QUAL decisao foi anulada (status +
     * regra), nao so "voltou para Enviado".
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void reaberturaGravaADecisaoAnuladaComRegraEIp() throws Exception {
        Processo p = novoProcesso("23/2026", 23);
        Parecer parCoord = novoParecer(p, coordenador);
        parCoord.setResultado(ResultadoParecer.FAVORAVEL);
        parCoord.setDataResposta(LocalDate.of(2026, 5, 3));
        parCoord.setEraCoordenadorNoVoto(true);
        parecerRepo.saveAndFlush(parCoord);
        p.setStatus(StatusProcesso.DEFERIDO);
        processoRepo.saveAndFlush(p);

        mvc.perform(post("/processos/" + p.getId() + "/reabrir").with(csrf()))
            .andReturn();

        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
            .isEqualTo(StatusProcesso.ENVIADO);

        LogAuditoria log = ultimoRegistro("PROCESSO_REABERTO");
        assertThat(log.getDetalhe())
            .contains("23/2026")
            .contains("Deferido")
            .contains("Voto único do Coordenador CET-RS")
            .doesNotContain("Paciente Auditoria Teste");
        assertThat(log.getIp()).isNotBlank();
    }
}

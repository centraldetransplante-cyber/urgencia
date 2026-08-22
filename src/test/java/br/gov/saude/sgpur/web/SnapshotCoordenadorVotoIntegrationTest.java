package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.OrigemParecer;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.domain.Sexo;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, {@code ProcessoService}/
 * {@code ProcessoValidator} REAIS) do Achado 4 da "Vistoria de bugs de
 * 2026-08-03" (CLAUDE.md) -- implementado em 2026-08-07 mediante aprovacao
 * explicita do dono do produto: {@code Parecer.eraCoordenadorNoVoto}
 * congela o papel de coordenador CET-RS no INSTANTE do voto, para o cargo
 * mudar de mao depois nao alterar retroativamente o peso de um voto ja
 * dado.
 *
 * <p><b>Cenario real que motivou a correcao:</b> o coordenador vota
 * Favoravel (defere sozinho, peso unico). ANTES da decisao final ser
 * registrada, outro medico assume o cargo de coordenador (o antigo perde
 * {@code coordenador=true}). Sem o snapshot,
 * {@code ProcessoValidator.temVotoCoordenadorFavoravel} lia o papel "ao
 * vivo" do membro e deixava de considerar aquele voto como decisivo -- o
 * processo, que deveria estar Deferido, ficaria preso esperando os outros 2
 * pareceres. Com o snapshot, o voto continua valendo o que valia quando foi
 * dado.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-snapshot-coordenador;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-snapshot-coordenador",
        // So para o segundo teste chamar scheduler.varrer() manualmente;
        // nunca dispara sozinho (mesmo padrao de DecisaoAutomaticaSchedulerIntegrationTest).
        "app.decisao-automatica.varredura.habilitado=true",
        "app.decisao-automatica.varredura.intervalo-ms=3600000",
        "app.decisao-automatica.varredura.atraso-inicial-ms=3600000"
})
class SnapshotCoordenadorVotoIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarioRepo;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;
    @Autowired
    private br.gov.saude.sgpur.service.ProcessoValidator processoValidator;
    @Autowired
    private br.gov.saude.sgpur.service.DecisaoAutomaticaScheduler scheduler;

    /** Consultado dentro de ProcessoService.decidir; sem efeito no cenario feliz. */
    @MockitoBean
    private SolicitacaoOnlineRepository solicitacaoOnlineRepo;

    private Long processoId;
    private MembroUrgenciaRenal coordenadorOriginal;

    @BeforeEach
    @Transactional
    void preparar() {
        parecerRepo.deleteAll();
        usuarioRepo.findByUsername("coordenador-snapshot-it").ifPresent(usuarioRepo::delete);
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        when(solicitacaoOnlineRepo.findByProcessoGeradoId(anyLong())).thenReturn(Optional.empty());

        Processo p = new Processo();
        p.setNumero("88/2026");
        p.setAno(2026);
        p.setSequencial(88);
        p.setPacienteNome("Paciente Snapshot Coordenador");
        p.setPacienteRgct("321321321");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        coordenadorOriginal = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("CET-RS", "Coordenador Original", "coord-original@example.com"));
        coordenadorOriginal.setCoordenador(true);
        membroRepo.saveAndFlush(coordenadorOriginal);

        MembroUrgenciaRenal medicoA = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HCPA", "Medico A", "medicoa@example.com"));
        MembroUrgenciaRenal medicoB = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("ISCMPA", "Medico B", "medicob@example.com"));

        Parecer pCoordenador = new Parecer(coordenadorOriginal);
        pCoordenador.setProcesso(p);
        pCoordenador.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(pCoordenador);

        Parecer pA = new Parecer(medicoA);
        pA.setProcesso(p);
        pA.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(pA);

        Parecer pB = new Parecer(medicoB);
        pB.setProcesso(p);
        pB.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(pB);

        Usuario u = new Usuario();
        u.setUsername("coordenador-snapshot-it");
        u.setSenha("{noop}x");
        u.setNome("Coordenador Original");
        u.setEmail("coord-original@example.com");
        u.setPerfil(Perfil.AVALIADOR);
        u.setMembro(coordenadorOriginal);
        usuarioRepo.saveAndFlush(u);
    }

    /**
     * O CENARIO do achado: coordenador vota Favoravel (grava o snapshot);
     * depois, ANTES de qualquer decisao final, o cargo de coordenador passa
     * para outro medico. O processo ainda assim precisa ser considerado
     * Deferido pelo voto antigo, porque o snapshot preservou o estado do
     * momento do voto.
     */
    @Test
    @WithMockUser(username = "coordenador-snapshot-it", roles = "AVALIADOR")
    void votoDoCoordenadorContinuaDecisivoMesmoDepoisDeOCargoMudarDeMao() throws Exception {
        mvc.perform(post("/avaliador/" + processoId + "/votar")
                        .with(csrf())
                        .param("resultado", "FAVORAVEL"))
                .andReturn();

        // Confirma que o snapshot foi de fato gravado no momento do voto.
        Parecer votoCoordenador = parecerRepo.findAll().stream()
                .filter(par -> par.getMembro().getId().equals(coordenadorOriginal.getId()))
                .findFirst().orElseThrow();
        assertThat(votoCoordenador.getResultado()).isEqualTo(ResultadoParecer.FAVORAVEL);
        assertThat(votoCoordenador.getEraCoordenadorNoVoto()).isTrue();

        // O peso unico do coordenador ja deferiu o processo na hora do voto
        // (tentarDecisaoAutomatica, disparado dentro do proprio registrarVoto).
        Processo apoisVoto = processoRepo.findById(processoId).orElseThrow();
        assertThat(apoisVoto.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);

        // Depois da decisao, o cargo de coordenador muda de mao -- o antigo
        // coordenador deixa de ser coordenador, outro assume. Rele do banco
        // antes de mutar: a instancia guardada no campo do teste pode estar
        // desatualizada em relacao ao que a requisicao MockMvc (transacao
        // propria) ja commitou.
        MembroUrgenciaRenal antigoRecarregado = membroRepo.findById(coordenadorOriginal.getId())
                .orElseThrow();
        antigoRecarregado.setCoordenador(false);
        membroRepo.saveAndFlush(antigoRecarregado);
        MembroUrgenciaRenal novoCoordenador = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HSL", "Novo Coordenador", "novo-coord@example.com"));
        novoCoordenador.setCoordenador(true);
        membroRepo.saveAndFlush(novoCoordenador);

        // O voto ja gravado continua valendo o que valia quando foi dado: o
        // processo permanece Deferido, e o snapshot do parecer nao muda so
        // porque o cargo de coordenador mudou de mao depois.
        Processo depoisDaTrocaDeCoordenador = processoRepo.findById(processoId).orElseThrow();
        assertThat(depoisDaTrocaDeCoordenador.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        Parecer votoReconferido = parecerRepo.findById(votoCoordenador.getId()).orElseThrow();
        assertThat(votoReconferido.getEraCoordenadorNoVoto()).isTrue();
        assertThat(membroRepo.findById(coordenadorOriginal.getId()).orElseThrow().isCoordenador())
                .isFalse();
    }

    /**
     * Contraste: um parecer ANTIGO, votado ANTES desta mudanca (simulado
     * gravando {@code eraCoordenadorNoVoto=null} diretamente, como um
     * registro legado faria), NAO conta como voto de coordenador -- decisao
     * conservadora documentada no javadoc de
     * {@code Parecer.eraCoordenadorNoVoto}: "nao sabemos, nao conta".
     */
    @Test
    void parecerAntigoSemSnapshotNaoContaComoVotoDeCoordenadorMesmoQueOMembroSejaCoordenadorHoje() {
        Processo p = new Processo();
        p.setNumero("89/2026");
        p.setAno(2026);
        p.setSequencial(89);
        p.setPacienteNome("Paciente Legado");
        p.setPacienteRgct("111222333");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);

        // Parecer "legado": gravado direto no repositorio, sem passar pelo
        // controller, simulando um registro anterior a esta mudanca --
        // eraCoordenadorNoVoto fica null.
        Parecer legado = new Parecer(coordenadorOriginal);
        legado.setProcesso(p);
        legado.setDataEnvio(LocalDate.of(2026, 5, 2));
        legado.setResultado(ResultadoParecer.FAVORAVEL);
        legado.setDataResposta(LocalDate.of(2026, 5, 3));
        legado.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        // eraCoordenadorNoVoto deliberadamente NAO setado (fica null).
        parecerRepo.saveAndFlush(legado);
        p.addParecer(legado);

        assertThat(coordenadorOriginal.isCoordenador()).isTrue();
        assertThat(legado.getEraCoordenadorNoVoto()).isNull();

        // O TESTE DIRETO DA REGRA: mesmo com o membro sendo coordenador HOJE,
        // o voto legado sem snapshot nao conta como voto de coordenador --
        // e ai que o bug do Achado 4 estaria, se o codigo ainda lesse
        // MembroUrgenciaRenal.coordenador "ao vivo".
        assertThat(processoValidator.temVotoCoordenadorFavoravel(p)).isFalse();
        assertThat(processoValidator.favoraveisNecessariosParaDeferir(p))
                .isEqualTo(br.gov.saude.sgpur.service.ProcessoService.FAVORAVEIS_PARA_DEFERIR);

        // Consequencia pratica: com um unico voto (mesmo favoravel), a
        // varredura automatica NAO decide -- precisa da maioria normal.
        Processo depois = processoRepo.findById(p.getId()).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        assertThat(scheduler.varrer()).isZero();
        assertThat(processoRepo.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.ENVIADO);
    }
}

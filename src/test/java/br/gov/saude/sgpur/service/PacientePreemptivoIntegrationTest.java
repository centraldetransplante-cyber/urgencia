package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.OrigemParecer;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.LogAuditoriaRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Testes de INTEGRACAO (contexto Spring real + H2) do paciente PREEMPTIVO
 * (2026-08-27, ver docs/PLANO-PACIENTE-PREEMPTIVO-2026-08-27.md) - regras que
 * envolvem ESCRITA/transacao (RGCT condicional, numeracao em serie separada,
 * troca de tipo, comprovante SNT) exigem servico real + H2 real (convencao
 * do CLAUDE.md: "rota que grava algo irreversivel exige teste do caminho de
 * falha sem mock do servico").
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-preemptivo;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.solicitante.habilitado=true",
    "app.anexos.dir=./target/test-anexos-preemptivo"
})
class PacientePreemptivoIntegrationTest {

    @Autowired
    private ProcessoService processoService;
    @Autowired
    private SolicitacaoOnlineService solicitacaoOnlineService;
    @Autowired
    private ProcessoRepository processoRepository;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private LogAuditoriaRepository logAuditoriaRepository;
    @Autowired
    private AnexoRepository anexoRepository;
    @Autowired
    private ParecerRepository parecerRepository;

    @MockitoBean
    private EmailSenderService emailSenderService;

    private List<Long> medicoIds;

    @BeforeEach
    void preparar() {
        processoRepository.deleteAll();
        when(emailSenderService.enviar(any(String[].class), any(), anyString(), anyString())).thenReturn(true);
        when(emailSenderService.enviarComAnexo(anyString(), any(), anyString(), anyString(), any(), anyString()))
            .thenReturn(true);

        if (membroRepository.count() < 3) {
            membroRepository.deleteAll();
            for (int i = 1; i <= 3; i++) {
                MembroUrgenciaRenal m = new MembroUrgenciaRenal("HCPA", "Medico " + i, "medico" + i + "@example.com");
                membroRepository.save(m);
            }
        }
        medicoIds = membroRepository.findAll().stream().map(MembroUrgenciaRenal::getId).limit(3).toList();
    }

    private Usuario criarSolicitante(String username) {
        return usuarioRepository.findByUsername(username).orElseGet(() -> {
            Usuario u = new Usuario();
            u.setUsername(username);
            u.setNome("Solicitante " + username);
            u.setEmail(username + "@example.com");
            u.setSenha("{noop}irrelevante");
            u.setPerfil(Perfil.SOLICITANTE);
            u.setAtivo(true);
            u.setEquipeSolicitante("HCPA - Nefrologia");
            return usuarioRepository.save(u);
        });
    }

    private SolicitacaoOnline novaSolicitacao(boolean preemptivo, String rgct) {
        SolicitacaoOnline s = new SolicitacaoOnline();
        s.setPacienteNome("Paciente Teste");
        s.setPacienteRgct(rgct);
        s.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        s.setPacienteCpf("11144477735");
        s.setPacienteSexo(Sexo.MASCULINO);
        s.setDataSituacaoEspecial(LocalDate.now());
        s.setJustificativaClinica("Justificativa clinica de teste, com detalhe suficiente.");
        s.setPreemptivo(preemptivo);
        return s;
    }

    private Processo novoProcesso(boolean preemptivo, String rgct, String numero, int ano) {
        Processo p = new Processo();
        p.setNumero(numero);
        p.setAno(ano);
        p.setSequencial(0);
        p.setPacienteNome("Paciente Teste");
        p.setPacienteRgct(rgct);
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA - Nefrologia");
        p.setSolicitanteEmail("solicitante@example.com");
        p.setDataSituacaoEspecial(LocalDate.now());
        p.setPreemptivo(preemptivo);
        return p;
    }

    // ---------------------------------------------------------------
    // 1. RGCT condicional
    // ---------------------------------------------------------------

    @Test
    void solicitacaoPreemptivaSemRgctEAceita() {
        Usuario u = criarSolicitante("solicitante-preemptivo-1");
        SolicitacaoOnline s = novaSolicitacao(true, null);

        SolicitacaoOnline salva = solicitacaoOnlineService.criar(s, u, null);

        assertThat(salva.getId()).isNotNull();
        assertThat(salva.getPacienteRgct()).isNull();
        assertThat(salva.isPreemptivo()).isTrue();
    }

    @Test
    void solicitacaoNaoPreemptivaSemRgctERejeitada() {
        Usuario u = criarSolicitante("solicitante-nao-preemptivo-1");
        SolicitacaoOnline s = novaSolicitacao(false, null);

        assertThatThrownBy(() -> solicitacaoOnlineService.criar(s, u, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("RGCT");
    }

    @Test
    void processoPreemptivoSemRgctAceitaEscritaSemQuebrar() {
        // Mesma classe de bug ja documentada no CLAUDE.md para os outros
        // campos "legados sem Bean Validation" - um processo preemptivo sem
        // RGCT precisa aceitar QUALQUER escrita (decidir/finalizar/anexar)
        // sem ConstraintViolationException.
        Processo p = novoProcesso(true, null, "P-01/2026", 2026);
        p.setStatus(StatusProcesso.SOLICITADO);
        Long id = processoRepository.saveAndFlush(p).getId();

        Processo form = novoProcesso(true, null, "P-01/2026", 2026);
        form.setPacienteNome("Paciente Editado");

        processoService.atualizarDados(id, form);

        Processo doBanco = processoRepository.findById(id).orElseThrow();
        assertThat(doBanco.getPacienteNome()).isEqualTo("Paciente Editado");
        assertThat(doBanco.getPacienteRgct()).isNull();
    }

    // ---------------------------------------------------------------
    // 2. Numeracao em serie separada
    // ---------------------------------------------------------------

    @Test
    void numeracaoPreemptivaESerieSeparadaDaUrgenciaComum() {
        // ano >= 2027: regime de numeracao AUTOMATICA (ProcessoService
        // .isNumeracaoAutomatica) - so nesse regime cadastrar() atribui o
        // numero sozinho a partir de proximoNumero(ano, preemptivo).
        int ano = 2027;
        LocalDate dataNoAno = LocalDate.of(ano, 3, 10);
        Processo urgencia = novoProcesso(false, "RGCT-1", null, ano);
        urgencia.setDataSituacaoEspecial(dataNoAno);
        Processo preemptivo1 = novoProcesso(true, null, null, ano);
        preemptivo1.setDataSituacaoEspecial(dataNoAno);
        Processo preemptivo2 = novoProcesso(true, null, null, ano);
        preemptivo2.setDataSituacaoEspecial(dataNoAno);

        Processo cUrgencia = processoService.cadastrar(urgencia, medicoIds);
        Processo cPreemptivo1 = processoService.cadastrar(preemptivo1, medicoIds);
        Processo cPreemptivo2 = processoService.cadastrar(preemptivo2, medicoIds);

        // A serie de urgencia comum comeca do zero, independente de quantos
        // preemptivos ja existirem no mesmo ano.
        assertThat(cUrgencia.getNumero()).doesNotStartWith("P-");
        assertThat(cPreemptivo1.getNumero()).startsWith("P-");
        assertThat(cPreemptivo2.getNumero()).startsWith("P-");
        assertThat(cPreemptivo1.getSequencial()).isEqualTo(1);
        assertThat(cPreemptivo2.getSequencial()).isEqualTo(2);
        assertThat(cPreemptivo1.getNumero()).isNotEqualTo(cPreemptivo2.getNumero());
    }

    @Test
    void extrairSequencialToleraPrefixoPreemptivo() {
        // numero manual (2026) digitado pelo operador com o prefixo "P-":
        // o sequencial precisa ser lido corretamente (sem o prefixo), nunca
        // cair no fallback silencioso que mistura as duas series.
        Processo p = novoProcesso(true, null, "P-07/2026", 2026);
        p.setSequencial(0); // sera recalculado por cadastrar()

        Processo salvo = processoService.cadastrar(p, medicoIds);

        assertThat(salvo.getSequencial()).isEqualTo(7);
        assertThat(salvo.getNumero()).isEqualTo("P-07/2026");
    }

    @Test
    void proximoNumeroSugereSerieCorretaConformeOTipo() {
        int ano = 2026;
        assertThat(processoService.proximoNumero(ano, false)).doesNotStartWith("P-");
        assertThat(processoService.proximoNumero(ano, true)).startsWith("P-");
    }

    // ---------------------------------------------------------------
    // 3. Comprovante SNT: preemptivo nao bloqueia, urgencia comum continua bloqueando
    // ---------------------------------------------------------------

    @Test
    void deferidoPreemptivoConcluiRespostaSemComprovanteSnt() {
        Processo p = novoProcesso(true, null, "P-02/2026", 2026);
        p.setStatus(StatusProcesso.DEFERIDO);
        p.setDataDecisao(java.time.LocalDateTime.now());
        Long id = processoRepository.saveAndFlush(p).getId();

        Processo respondido = processoService.finalizarResposta(id);

        assertThat(respondido.isEmailEnviadoSolicitante()).isTrue();
    }

    @Test
    void deferidoUrgenciaComumContinuaBloqueadoSemComprovanteSnt() {
        Processo p = novoProcesso(false, "RGCT-X", "05/2026", 2026);
        p.setStatus(StatusProcesso.DEFERIDO);
        p.setDataDecisao(java.time.LocalDateTime.now());
        Long id = processoRepository.saveAndFlush(p).getId();

        assertThatThrownBy(() -> processoService.finalizarResposta(id))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("comprovante");
    }

    // ---------------------------------------------------------------
    // 4. Troca de tipo pos-conversao (antes/depois do envio)
    // ---------------------------------------------------------------

    @Test
    void trocaDeTipoAntesDoEnvioReemiteNumeroEAuditoria() {
        Processo p = novoProcesso(false, "RGCT-A", "05/2026", 2026);
        p.setStatus(StatusProcesso.SOLICITADO);
        Long id = processoRepository.saveAndFlush(p).getId();

        Processo form = novoProcesso(true, null, null, 2026);

        processoService.atualizarDados(id, form);

        Processo doBanco = processoRepository.findById(id).orElseThrow();
        assertThat(doBanco.isPreemptivo()).isTrue();
        assertThat(doBanco.getNumero()).startsWith("P-");
        assertThat(doBanco.getNumero()).isNotEqualTo("05/2026");

        boolean auditado = logAuditoriaRepository.findAllByOrderByDataHoraDesc(PageRequest.of(0, 20))
            .stream().anyMatch(l -> "PROCESSO_TIPO_ALTERADO".equals(l.getAcao()));
        assertThat(auditado).isTrue();
    }

    @Test
    void trocaDeTipoAposEnvioEBloqueada() {
        Processo p = novoProcesso(false, "RGCT-B", "06/2026", 2026);
        p.setStatus(StatusProcesso.ENVIADO);
        Long id = processoRepository.saveAndFlush(p).getId();

        Processo form = novoProcesso(true, null, null, 2026);

        assertThatThrownBy(() -> processoService.atualizarDados(id, form))
            .isInstanceOf(IllegalStateException.class);

        Processo doBanco = processoRepository.findById(id).orElseThrow();
        assertThat(doBanco.isPreemptivo()).isFalse();
        assertThat(doBanco.getNumero()).isEqualTo("06/2026");
    }

    // ---------------------------------------------------------------
    // 7. Helper null-safe (processo legado com preemptivo = NULL)
    // ---------------------------------------------------------------

    @Test
    void processoLegadoComPreemptivoNuloETratadoComoNaoPreemptivo() {
        Processo p = novoProcesso(false, "RGCT-LEGADO", "07/2026", 2026);
        p.setPreemptivo(null);
        Long id = processoRepository.saveAndFlush(p).getId();

        Processo doBanco = processoRepository.findById(id).orElseThrow();
        assertThat(doBanco.getPreemptivo()).isNull();
        assertThat(doBanco.isPreemptivo()).isFalse();

        // Escrita comum continua funcionando sem excecao nenhuma.
        Processo form = novoProcesso(false, "RGCT-LEGADO", "07/2026", 2026);
        form.setPacienteNome("Nome Atualizado");
        processoService.atualizarDados(id, form);
        assertThat(processoRepository.findById(id).orElseThrow().getPacienteNome())
            .isEqualTo("Nome Atualizado");
    }

    // ---------------------------------------------------------------
    // 8. Filtro por TIPO nas listas (/processos, /arquivo)
    // ---------------------------------------------------------------

    @Test
    void buscarFiltraPorTipoIncluindoLegadoNuloComoUrgenciaRenal() {
        processoRepository.saveAndFlush(novoProcesso(false, "RGCT-1", "10/2026", 2026));
        Processo legado = novoProcesso(false, "RGCT-2", "11/2026", 2026);
        legado.setPreemptivo(null); // linha legada = urgencia renal comum
        processoRepository.saveAndFlush(legado);
        Processo preempt = novoProcesso(true, null, "P-01/2026", 2026);
        processoRepository.saveAndFlush(preempt);

        var todos = processoService.buscar(null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 15));
        var soPreempt = processoService.buscar(null, null, "preemptivo",
            org.springframework.data.domain.PageRequest.of(0, 15));
        var soUrgencia = processoService.buscar(null, null, "urgencia",
            org.springframework.data.domain.PageRequest.of(0, 15));

        assertThat(todos.getTotalElements()).isEqualTo(3);
        assertThat(soPreempt.getContent()).extracting(Processo::getNumero)
            .containsExactly("P-01/2026");
        // o legado (preemptivo = NULL) conta como urgencia renal, via coalesce
        assertThat(soUrgencia.getContent()).extracting(Processo::getNumero)
            .containsExactlyInAnyOrder("10/2026", "11/2026");
    }

    // ---------------------------------------------------------------
    // 9. Regressao de decisao (Achado A12 da auditoria de 2026-08-27):
    //    maioria simples 2/3 e a excecao do coordenador CET-RS precisam
    //    funcionar EXATAMENTE igual em processo preemptivo - o tipo do
    //    processo nunca deve vazar para a logica de votacao/decisao
    //    (CLAUDE.md: "nenhuma regra de votacao/decisao muda"). O plano
    //    original (secao 8.6) exigia este teste e ele nunca foi escrito -
    //    ate aqui a regra so tinha sido validada manualmente (Playwright).
    // ---------------------------------------------------------------

    /** Voto autenticado no Portal do Avaliador (mesmo padrao de DecisaoAutomaticaSchedulerIntegrationTest). */
    private Parecer votar(Processo p, MembroUrgenciaRenal membro, ResultadoParecer resultado) {
        Parecer par = new Parecer(membro);
        par.setProcesso(p);
        par.setDataEnvio(LocalDate.now());
        par.setResultado(resultado);
        par.setDataResposta(LocalDate.now());
        par.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        // Snapshot do papel no momento do voto (Parecer.eraCoordenadorNoVoto),
        // mesmo comportamento de AvaliadorController.registrarVoto.
        par.setEraCoordenadorNoVoto(membro.isCoordenador());
        return parecerRepository.saveAndFlush(par);
    }

    @Test
    void maioriaSimplesDeDoisFavoraveisDefereProcessoPreemptivoIgualAoComum() {
        // 3 avaliadores COMUNS (nenhum coordenador) - 2 favoraveis, 1
        // desfavoravel: maioria simples de verdade, sem exceção nenhuma.
        MembroUrgenciaRenal a = membroRepository.save(new MembroUrgenciaRenal("HCPA", "Avaliador Preempt A", "prA@example.com"));
        MembroUrgenciaRenal b = membroRepository.save(new MembroUrgenciaRenal("ISCMPA", "Avaliador Preempt B", "prB@example.com"));
        MembroUrgenciaRenal c = membroRepository.save(new MembroUrgenciaRenal("HSL", "Avaliador Preempt C", "prC@example.com"));

        Processo p = novoProcesso(true, null, "P-20/2026", 2026);
        p.setStatus(StatusProcesso.ENVIADO);
        Long id = processoRepository.saveAndFlush(p).getId();
        Processo salvo = processoRepository.findById(id).orElseThrow();
        votar(salvo, a, ResultadoParecer.FAVORAVEL);
        votar(salvo, b, ResultadoParecer.FAVORAVEL);
        votar(salvo, c, ResultadoParecer.NAO_FAVORAVEL);

        Processo decidido = processoService.decidir(id, StatusProcesso.DEFERIDO, null);

        assertThat(decidido.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        assertThat(decidido.isPreemptivo()).isTrue();
    }

    @Test
    void maioriaSimplesDeDoisDesfavoraveisIndefereProcessoPreemptivoIgualAoComum() {
        MembroUrgenciaRenal a = membroRepository.save(new MembroUrgenciaRenal("HCPA", "Avaliador Preempt D", "prD@example.com"));
        MembroUrgenciaRenal b = membroRepository.save(new MembroUrgenciaRenal("ISCMPA", "Avaliador Preempt E", "prE@example.com"));
        MembroUrgenciaRenal c = membroRepository.save(new MembroUrgenciaRenal("HSL", "Avaliador Preempt F", "prF@example.com"));

        Processo p = novoProcesso(true, null, "P-21/2026", 2026);
        p.setStatus(StatusProcesso.ENVIADO);
        Long id = processoRepository.saveAndFlush(p).getId();
        Processo salvo = processoRepository.findById(id).orElseThrow();
        votar(salvo, a, ResultadoParecer.NAO_FAVORAVEL);
        votar(salvo, b, ResultadoParecer.NAO_FAVORAVEL);
        votar(salvo, c, ResultadoParecer.FAVORAVEL);

        Processo decidido = processoService.decidir(id, StatusProcesso.INDEFERIDO, "Motivo institucional de teste.");

        assertThat(decidido.getStatus()).isEqualTo(StatusProcesso.INDEFERIDO);
        assertThat(decidido.isPreemptivo()).isTrue();
    }

    /**
     * Excecao do coordenador CET-RS: 1 unico voto Favoravel do coordenador
     * defere sozinho, mesmo com 2 desfavoraveis - IDENTICO ao comportamento
     * ja coberto para urgencia renal comum em
     * {@code DecisaoAutomaticaSchedulerIntegrationTest.coordenadorFavoravelDefereMesmoComDoisDesfavoraveis}
     * e {@code ProcessoServiceTest}, agora tambem em processo preemptivo.
     */
    @Test
    void votoUnicoDoCoordenadorDefereProcessoPreemptivoMesmoComDoisDesfavoraveis() {
        MembroUrgenciaRenal coordenador = new MembroUrgenciaRenal("CET-RS", "Coordenador Preempt", "coord-preempt@example.com");
        coordenador.setCoordenador(true);
        coordenador = membroRepository.save(coordenador);
        MembroUrgenciaRenal a = membroRepository.save(new MembroUrgenciaRenal("HCPA", "Avaliador Preempt G", "prG@example.com"));
        MembroUrgenciaRenal b = membroRepository.save(new MembroUrgenciaRenal("ISCMPA", "Avaliador Preempt H", "prH@example.com"));

        Processo p = novoProcesso(true, null, "P-22/2026", 2026);
        p.setStatus(StatusProcesso.ENVIADO);
        Long id = processoRepository.saveAndFlush(p).getId();
        Processo salvo = processoRepository.findById(id).orElseThrow();
        votar(salvo, coordenador, ResultadoParecer.FAVORAVEL);
        votar(salvo, a, ResultadoParecer.NAO_FAVORAVEL);
        votar(salvo, b, ResultadoParecer.NAO_FAVORAVEL);

        Processo decidido = processoService.decidir(id, StatusProcesso.DEFERIDO, null);

        assertThat(decidido.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        assertThat(decidido.isPreemptivo()).isTrue();
        assertThat(processoService.deferidoPeloCoordenador(decidido)).isTrue();
    }

    /**
     * Espelho da regra "Indeferido continua exigindo >=2 SEMPRE - o
     * coordenador nao pesa mais para indeferir e fica VEDADO indeferir
     * manualmente enquanto ele ja votou favoravel" (CLAUDE.md, item 3),
     * agora em processo preemptivo: mesma excecao do teste acima, so que
     * tentando Indeferir - tem que ser rejeitado exatamente como seria num
     * processo de urgencia renal comum.
     */
    @Test
    void indeferirEVedadoEmProcessoPreemptivoQuandoCoordenadorJaVotouFavoravel() {
        MembroUrgenciaRenal coordenador = new MembroUrgenciaRenal("CET-RS", "Coordenador Preempt 2", "coord-preempt2@example.com");
        coordenador.setCoordenador(true);
        coordenador = membroRepository.save(coordenador);
        MembroUrgenciaRenal a = membroRepository.save(new MembroUrgenciaRenal("HCPA", "Avaliador Preempt I", "prI@example.com"));
        MembroUrgenciaRenal b = membroRepository.save(new MembroUrgenciaRenal("ISCMPA", "Avaliador Preempt J", "prJ@example.com"));

        Processo p = novoProcesso(true, null, "P-23/2026", 2026);
        p.setStatus(StatusProcesso.ENVIADO);
        Long id = processoRepository.saveAndFlush(p).getId();
        Processo salvo = processoRepository.findById(id).orElseThrow();
        votar(salvo, coordenador, ResultadoParecer.FAVORAVEL);
        votar(salvo, a, ResultadoParecer.NAO_FAVORAVEL);
        votar(salvo, b, ResultadoParecer.NAO_FAVORAVEL);

        assertThatThrownBy(() -> processoService.decidir(id, StatusProcesso.INDEFERIDO, "Motivo qualquer."))
            .isInstanceOf(IllegalStateException.class);

        assertThat(processoRepository.findById(id).orElseThrow().getStatus()).isEqualTo(StatusProcesso.ENVIADO);
    }
}

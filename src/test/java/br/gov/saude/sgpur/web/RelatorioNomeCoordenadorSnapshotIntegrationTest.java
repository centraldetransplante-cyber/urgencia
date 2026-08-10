package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.OrigemParecer;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Perfil;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Usuario;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.repository.UsuarioRepository;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, {@code ProcessoService}/
 * {@code ProcessoValidator}/{@code RelatorioService} REAIS, PDF gerado de
 * verdade pela rota real {@code GET /processos/{id}/relatorio}) da Fase F1
 * do {@code docs/RELATORIO-VISTORIA-BRECHAS-DECISAO-2026-08-10.md} --
 * Achado 1: o Relatorio Final nomeava o medico ERRADO como coordenador.
 *
 * <p><b>O defeito corrigido:</b> a REGRA de decisao ja usava o snapshot
 * {@code Parecer.eraCoordenadorNoVoto} (implementado em 2026-08-07, coberto
 * por {@code SnapshotCoordenadorVotoIntegrationTest}), mas a busca do NOME
 * a imprimir em {@code RelatorioService.paragrafoRegraDecisao} tinha ficado
 * de fora dessa protecao: filtrava {@code parecer.getMembro()
 * .isCoordenador()} -- o cargo AO VIVO. Se o cargo mudasse de mao depois do
 * voto, o documento oficial creditava a excecao regimental a quem nunca a
 * exerceu, ou perdia o nome de quem decidiu.</p>
 *
 * <p><b>Por que {@code @SpringBootTest} e nao {@code @WebMvcTest}:</b> os
 * cenarios abaixo dependem de voto real gravado no banco (com o snapshot
 * capturado pelo proprio {@code AvaliadorController}), de decisao automatica
 * real e do PDF efetivamente montado -- nada disso e expressavel com o
 * servico mockado. Metade dos cenarios e regressao da REGRA de decisao (nao
 * do texto), justamente para provar que a mudanca de apresentacao nao a
 * alterou.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-relatorio-coordenador;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-relatorio-coordenador"
})
class RelatorioNomeCoordenadorSnapshotIntegrationTest {

    private static final String NOME_COORDENADOR_ORIGINAL = "Ana Coordenadora Original";
    private static final String NOME_MEDICO_QUE_ASSUME = "Bruno Assume Depois";
    private static final String NOME_MEDICO_SEM_VOTO = "Carla Nunca Votou";

    /**
     * Como o documento fica quando NAO ha nome a imprimir: o rotulo generico
     * entra no lugar do nome, produzindo "Coordenador da CET-RS (Coordenador
     * da CET-RS)". Era exatamente o que o codigo antigo imprimia no cenario
     * 3 (cargo em quem nao votou) -- verificado rodando estes testes contra
     * a versao anterior antes da correcao.
     */
    private static final String FALLBACK_GENERICO = "Coordenador da CET-RS (Coordenador da CET-RS)";

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
    private ProcessoValidator processoValidator;
    @Autowired
    private ProcessoService processoService;

    /** Consultado dentro de ProcessoService.decidir; sem efeito nestes cenarios. */
    @MockitoBean
    private SolicitacaoOnlineRepository solicitacaoOnlineRepo;

    private MembroUrgenciaRenal coordenadorOriginal;
    private MembroUrgenciaRenal medicoQueAssume;
    private MembroUrgenciaRenal medicoSemVoto;

    @BeforeEach
    @Transactional
    void preparar() {
        parecerRepo.deleteAll();
        usuarioRepo.findByUsername("coord-original-f1").ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername("medico-assume-f1").ifPresent(usuarioRepo::delete);
        usuarioRepo.findByUsername("medico-sem-voto-f1").ifPresent(usuarioRepo::delete);
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        when(solicitacaoOnlineRepo.findByProcessoGeradoId(anyLong())).thenReturn(Optional.empty());

        coordenadorOriginal = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("CET-RS", NOME_COORDENADOR_ORIGINAL, "coord-original@example.com"));
        coordenadorOriginal.setCoordenador(true);
        coordenadorOriginal = membroRepo.saveAndFlush(coordenadorOriginal);

        medicoQueAssume = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HCPA", NOME_MEDICO_QUE_ASSUME, "assume@example.com"));
        medicoSemVoto = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("ISCMPA", NOME_MEDICO_SEM_VOTO, "sem-voto@example.com"));

        criarUsuarioAvaliador("coord-original-f1", coordenadorOriginal);
        criarUsuarioAvaliador("medico-assume-f1", medicoQueAssume);
        criarUsuarioAvaliador("medico-sem-voto-f1", medicoSemVoto);
    }

    // ------------------------------------------------------------------
    // CENARIO 1 - regressao da REGRA: coordenador defere sozinho, com 1 voto
    // ------------------------------------------------------------------

    @Test
    void cenario1_coordenadorVotandoFavoravelSozinhoDefereOProcessoComUmUnicoVoto() throws Exception {
        Long processoId = criarProcessoEnviadoComOs3Pareceres("11/2026", 11);

        votar(processoId, "coord-original-f1", ResultadoParecer.FAVORAVEL);

        Processo decidido = processoRepo.findById(processoId).orElseThrow();
        assertThat(decidido.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        assertThat(contarFavoraveis(processoId)).isEqualTo(1);
        assertThat(processoValidator.deferidoPeloCoordenador(comPareceres(processoId))).isTrue();
    }

    // ------------------------------------------------------------------
    // CENARIO 2 - o cenario do Achado 1
    // ------------------------------------------------------------------

    /**
     * Medico comum vota Favoravel (sem maioria ainda), coordenador vota
     * Favoravel (defere pela excecao), e SO DEPOIS o cargo de coordenador
     * passa para aquele medico comum -- que tambem votou Favoravel, e por
     * isso era exatamente quem o filtro antigo (papel ao vivo) escolhia por
     * engano.
     */
    @Test
    void cenario2_cargoMudaParaOutroMedicoQueTambemVotouFavoravel_pdfMantemOVotanteOriginal() throws Exception {
        Long processoId = criarProcessoEnviadoComOs3Pareceres("12/2026", 12);

        votar(processoId, "medico-assume-f1", ResultadoParecer.FAVORAVEL);
        votar(processoId, "coord-original-f1", ResultadoParecer.FAVORAVEL);

        assertThat(processoRepo.findById(processoId).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.DEFERIDO);

        passarCargoDeCoordenadorPara(medicoQueAssume);

        String texto = textoDoRelatorioFinal(processoId);
        assertThat(texto).contains("Coordenador da CET-RS (" + NOME_COORDENADOR_ORIGINAL + ")");
        assertThat(texto).doesNotContain("Coordenador da CET-RS (" + NOME_MEDICO_QUE_ASSUME + ")");
        assertThat(texto).doesNotContain(FALLBACK_GENERICO);
    }

    // ------------------------------------------------------------------
    // CENARIO 3 - cargo vai para quem nao votou
    // ------------------------------------------------------------------

    /**
     * Segundo modo de falha do Achado 1: com o cargo em quem nao votou, o
     * filtro antigo nao achava NINGUEM e o documento caia no rotulo
     * generico, deixando de identificar quem decidiu.
     */
    @Test
    void cenario3_cargoMudaParaMedicoQueNaoVotou_pdfContinuaNomeandoOVotanteOriginal() throws Exception {
        Long processoId = criarProcessoEnviadoComOs3Pareceres("13/2026", 13);

        votar(processoId, "coord-original-f1", ResultadoParecer.FAVORAVEL);
        passarCargoDeCoordenadorPara(medicoSemVoto);

        String texto = textoDoRelatorioFinal(processoId);
        assertThat(texto).contains("Coordenador da CET-RS (" + NOME_COORDENADOR_ORIGINAL + ")");
        assertThat(texto).doesNotContain(FALLBACK_GENERICO);
        assertThat(texto).doesNotContain("Coordenador da CET-RS (" + NOME_MEDICO_SEM_VOTO + ")");
    }

    // ------------------------------------------------------------------
    // CENARIO 4 - processo ja decidido nao muda retroativamente
    // ------------------------------------------------------------------

    @Test
    void cenario4_processoJaDecididoNaoEAfetadoPelaTrocaDeCargoNemPelaGeracaoDoPdf() throws Exception {
        Long processoId = criarProcessoEnviadoComOs3Pareceres("14/2026", 14);
        votar(processoId, "coord-original-f1", ResultadoParecer.FAVORAVEL);

        Processo antes = processoRepo.findById(processoId).orElseThrow();
        StatusProcesso statusAntes = antes.getStatus();
        LocalDateTime dataDecisaoAntes = antes.getDataDecisao();
        String motivoAntes = antes.getMotivoIndeferimento();
        boolean deferidoPeloCoordenadorAntes = processoValidator.deferidoPeloCoordenador(comPareceres(processoId));

        assertThat(statusAntes).isEqualTo(StatusProcesso.DEFERIDO);
        assertThat(dataDecisaoAntes).isNotNull();
        assertThat(deferidoPeloCoordenadorAntes).isTrue();

        passarCargoDeCoordenadorPara(medicoQueAssume);
        textoDoRelatorioFinal(processoId); // gera o PDF de verdade

        Processo depois = processoRepo.findById(processoId).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(statusAntes);
        assertThat(depois.getDataDecisao()).isEqualTo(dataDecisaoAntes);
        assertThat(depois.getMotivoIndeferimento()).isEqualTo(motivoAntes);
        assertThat(processoValidator.deferidoPeloCoordenador(comPareceres(processoId)))
                .isEqualTo(deferidoPeloCoordenadorAntes);
    }

    // ------------------------------------------------------------------
    // CENARIO 5 - decisao FUTURA usa o snapshot, nao o cadastro atual
    // ------------------------------------------------------------------

    @Test
    void cenario5a_processoNovoComVotoDoEXCoordenadorNaoDefereSozinho() throws Exception {
        passarCargoDeCoordenadorPara(medicoQueAssume);

        Long processoId = criarProcessoEnviadoComOs3Pareceres("15/2026", 15);
        votar(processoId, "coord-original-f1", ResultadoParecer.FAVORAVEL);

        Processo p = processoRepo.findById(processoId).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        assertThat(processoValidator.temVotoCoordenadorFavoravel(comPareceres(processoId))).isFalse();
        assertThat(processoValidator.favoraveisNecessariosParaDeferir(comPareceres(processoId)))
                .isEqualTo(ProcessoService.FAVORAVEIS_PARA_DEFERIR);
    }

    @Test
    void cenario5b_processoNovoComVotoDoNOVOCoordenadorDefereSozinho() throws Exception {
        passarCargoDeCoordenadorPara(medicoQueAssume);

        Long processoId = criarProcessoEnviadoComOs3Pareceres("16/2026", 16);
        votar(processoId, "medico-assume-f1", ResultadoParecer.FAVORAVEL);

        Processo p = processoRepo.findById(processoId).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        assertThat(contarFavoraveis(processoId)).isEqualTo(1);

        String texto = textoDoRelatorioFinal(processoId);
        assertThat(texto).contains("Coordenador da CET-RS (" + NOME_MEDICO_QUE_ASSUME + ")");
        assertThat(texto).doesNotContain("Coordenador da CET-RS (" + NOME_COORDENADOR_ORIGINAL + ")");
    }

    // ------------------------------------------------------------------
    // CENARIO 6 - parecer legado (snapshot null)
    // ------------------------------------------------------------------

    /**
     * Parecer votado ANTES de 2026-08-07 ({@code eraCoordenadorNoVoto ==
     * null}) nao conta como voto de coordenador -- decisao conservadora ja
     * documentada. Consequencia no documento: a frase da excecao NAO e
     * impressa (o processo caiu na maioria simples comum), entao nenhum
     * medico e creditado indevidamente e o rotulo generico tambem nao
     * aparece. Comportamento identico ao anterior a esta mudanca -- so o
     * NOME dentro da frase da excecao foi alterado, nunca a condicao que
     * decide se ela e impressa ({@code deferidoPeloCoordenador}).
     */
    @Test
    void cenario6_parecerLegadoSemSnapshotNaoContaEODocumentoNaoCreditaCoordenador() throws Exception {
        Long processoId = criarProcessoEnviadoComOs3Pareceres("17/2026", 17);

        // Parecer "legado": gravado direto no repositorio, sem passar pelo
        // controller -- eraCoordenadorNoVoto fica null, como um registro
        // anterior ao snapshot.
        marcarParecerLegadoFavoravel(processoId, coordenadorOriginal);
        // Segundo favoravel, para o processo poder ser deferido pela maioria
        // simples comum (2 de 3) e o relatorio ter uma decisao a descrever.
        votar(processoId, "medico-assume-f1", ResultadoParecer.FAVORAVEL);

        Processo p = comPareceres(processoId);
        assertThat(processoValidator.temVotoCoordenadorFavoravel(p)).isFalse();
        assertThat(processoValidator.parecerDoCoordenador(p)).isEmpty();
        assertThat(processoRepo.findById(processoId).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.DEFERIDO);

        String texto = textoDoRelatorioFinal(processoId);
        assertThat(texto).doesNotContain("Coordenador da CET-RS (");
        assertThat(texto).contains("regra: 2 de 3 defere o processo");
    }

    // ------------------------------------------------------------------
    // CENARIO 7 - regressao do documento na maioria simples comum
    // ------------------------------------------------------------------

    @Test
    void cenario7_deferidoPorMaioriaSimplesComumImprimeAFraseDeMaioriaSemCitarCoordenador() throws Exception {
        Long processoId = criarProcessoEnviadoComOs3Pareceres("18/2026", 18);

        votar(processoId, "medico-assume-f1", ResultadoParecer.FAVORAVEL);
        votar(processoId, "medico-sem-voto-f1", ResultadoParecer.FAVORAVEL);

        assertThat(processoRepo.findById(processoId).orElseThrow().getStatus())
                .isEqualTo(StatusProcesso.DEFERIDO);

        String texto = textoDoRelatorioFinal(processoId);
        assertThat(texto).contains("Favoráveis: 2 (regra: 2 de 3 defere o processo)");
        assertThat(texto).doesNotContain("Coordenador da CET-RS (");
        assertThat(texto).doesNotContain("defere isoladamente");
    }

    // ------------------------------------------------------------------
    // Apoio
    // ------------------------------------------------------------------

    private void criarUsuarioAvaliador(String username, MembroUrgenciaRenal membro) {
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setSenha("{noop}x");
        u.setNome(membro.getNome());
        u.setEmail(membro.getEmail());
        u.setPerfil(Perfil.AVALIADOR);
        u.setMembro(membro);
        usuarioRepo.saveAndFlush(u);
    }

    private Long criarProcessoEnviadoComOs3Pareceres(String numero, int sequencial) {
        Processo p = new Processo();
        p.setNumero(numero);
        p.setAno(2026);
        p.setSequencial(sequencial);
        p.setPacienteNome("Paciente Relatorio Coordenador");
        p.setPacienteRgct("999888777");
        p.setSolicitanteEquipe("HNSC");
        p.setSolicitanteEmail("equipe@hnsc.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.ENVIADO);
        processoRepo.saveAndFlush(p);

        for (MembroUrgenciaRenal m : new MembroUrgenciaRenal[]{
                coordenadorOriginal, medicoQueAssume, medicoSemVoto}) {
            Parecer par = new Parecer(m);
            par.setProcesso(p);
            par.setDataEnvio(LocalDate.of(2026, 5, 2));
            parecerRepo.saveAndFlush(par);
        }
        return p.getId();
    }

    /** Vota de verdade pelo Portal do Avaliador (grava o snapshot no voto). */
    private void votar(Long processoId, String username, ResultadoParecer resultado) throws Exception {
        mvc.perform(post("/avaliador/" + processoId + "/votar")
                        .with(user(username).roles("AVALIADOR"))
                        .with(csrf())
                        .param("resultado", resultado.name())
                        .param("justificativa", "Justificativa de teste."))
                .andReturn();
    }

    /**
     * Troca o cargo de coordenador de mao no cadastro, como o ADMIN faria em
     * {@code /membros} -- o gatilho do Achado 1.
     */
    private void passarCargoDeCoordenadorPara(MembroUrgenciaRenal novo) {
        membroRepo.findAll().forEach(m -> {
            if (m.isCoordenador() && !m.getId().equals(novo.getId())) {
                m.setCoordenador(false);
                membroRepo.saveAndFlush(m);
            }
        });
        MembroUrgenciaRenal recarregado = membroRepo.findById(novo.getId()).orElseThrow();
        recarregado.setCoordenador(true);
        membroRepo.saveAndFlush(recarregado);
    }

    private void marcarParecerLegadoFavoravel(Long processoId, MembroUrgenciaRenal membro) {
        Parecer legado = parecerRepo.findByProcessoIdAndMembroId(processoId, membro.getId())
                .orElseThrow();
        legado.setResultado(ResultadoParecer.FAVORAVEL);
        legado.setDataResposta(LocalDate.of(2026, 5, 3));
        legado.setDataHoraVoto(LocalDateTime.of(2026, 5, 3, 10, 0));
        legado.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        // eraCoordenadorNoVoto deliberadamente NAO setado (fica null).
        parecerRepo.saveAndFlush(legado);
    }

    private long contarFavoraveis(Long processoId) {
        return pareceresDoProcesso(processoId).stream()
                .filter(par -> par.getResultado() == ResultadoParecer.FAVORAVEL)
                .count();
    }

    /**
     * Copia em memoria do processo com os pareceres relidos do banco, para
     * consultar o {@code ProcessoValidator} REAL fora de transacao (o
     * {@code Processo.pareceres} carregado por {@code findById} e LAZY e
     * {@code open-in-view} esta desligado neste projeto).
     */
    private Processo comPareceres(Long processoId) {
        Processo db = processoRepo.findById(processoId).orElseThrow();
        Processo copia = new Processo();
        copia.setStatus(db.getStatus());
        pareceresDoProcesso(processoId).forEach(copia::addParecer);
        return copia;
    }

    private java.util.List<Parecer> pareceresDoProcesso(Long processoId) {
        return java.util.stream.Stream
                .of(coordenadorOriginal, medicoQueAssume, medicoSemVoto)
                .map(m -> parecerRepo.findByProcessoIdAndMembroId(processoId, m.getId()))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Gera o Relatorio Final de VERDADE pela rota real e devolve o texto
     * extraido de todas as paginas, com espacos normalizados (o extrator
     * quebra por linha; a frase da regra pode atravessar duas).
     */
    private String textoDoRelatorioFinal(Long processoId) throws Exception {
        byte[] pdf = mvc.perform(get("/processos/" + processoId + "/relatorio")
                        .with(user("admin-f1").roles("ADMIN")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(pdf).isNotEmpty();
        PdfReader reader = new PdfReader(pdf);
        StringBuilder texto = new StringBuilder();
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            texto.append(new PdfTextExtractor(reader).getTextFromPage(i)).append('\n');
        }
        reader.close();
        return texto.toString().replaceAll("\\s+", " ");
    }
}

package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.LogAuditoria;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.LogAuditoriaRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, servicos/repositorios REAIS,
 * sem nenhum {@code @MockitoBean}) da TRAVA DE ANONIMIZACAO no caminho da
 * SUBSTITUICAO:
 * {@code POST /processos/{id}/documento-clinico/{anexoId}/substituir}.
 *
 * <p><b>Por que precisa ser integracao (convencao do CLAUDE.md para escrita
 * irreversivel):</b> a acao grava um anexo novo em disco/banco E apaga o
 * original com o nome do paciente. Um {@code @WebMvcTest} com
 * {@code @MockitoBean} no {@code AnexoStorageService} nao consegue expressar
 * nada disso - nao ha arquivo, nao ha transacao e a ordem "salva o novo antes
 * de remover o antigo" viraria apenas uma verificacao de ordem de chamadas em
 * mock, que passaria mesmo com o estado final errado. Aqui as assercoes sao
 * sobre o estado REAL depois do POST: o que sobrou no banco e no disco.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-substituicao-anonimizada;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-substituicao-anonimizada"
})
class SubstituicaoDocumentoAnonimizadoIntegrationTest {

    private static final String NOME_PACIENTE = "Paciente Nome Completo Substituicao";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private AnexoRepository anexoRepo;
    @Autowired
    private LogAuditoriaRepository logRepo;
    @Autowired
    private AnexoStorageService anexoStorage;
    @Autowired
    private ProcessoValidator validator;
    @Autowired
    private PlatformTransactionManager txManager;

    private Long processoId;
    private Long pendenteId;
    private Path arquivoPendente;

    private Long processoEncerradoId;
    private Long pendenteDoEncerradoId;

    @BeforeEach
    void preparar() throws Exception {
        logRepo.deleteAll();
        anexoRepo.deleteAll();
        processoRepo.deleteAll();

        Processo p = novoProcesso("91/2026", 91, StatusProcesso.ENVIADO);
        processoId = p.getId();
        Anexo pendente = anexoStorage.salvarBytes(p, TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO,
                "Documento enviado pelo solicitante - NAO ANONIMIZADO",
                "laudo-original.pdf", "application/pdf",
                ("%PDF-1.4 laudo com o nome " + NOME_PACIENTE).getBytes());
        pendenteId = pendente.getId();
        arquivoPendente = anexoStorage.resolverArquivo(pendente);

        Processo encerrado = novoProcesso("92/2026", 92, StatusProcesso.DEFERIDO);
        processoEncerradoId = encerrado.getId();
        pendenteDoEncerradoId = anexoStorage.salvarBytes(encerrado, TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO,
                "Documento do portal", "laudo-encerrado.pdf", "application/pdf",
                "%PDF-1.4 laudo encerrado".getBytes()).getId();
    }

    private Processo novoProcesso(String numero, int sequencial, StatusProcesso status) {
        Processo p = new Processo();
        p.setNumero(numero);
        p.setAno(2026);
        p.setSequencial(sequencial);
        p.setPacienteNome(NOME_PACIENTE);
        p.setPacienteRgct("123123123");
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(status);
        return processoRepo.saveAndFlush(p);
    }

    private List<Anexo> anexosDoTipo(Long idProcesso, TipoAnexo tipo) {
        return anexoRepo.findByProcessoIdAndTipo(idProcesso, tipo);
    }

    private MockMultipartFile pdf(String nome) {
        return new MockMultipartFile("arquivo", nome, "application/pdf",
                "%PDF-1.4 laudo ja anonimizado (paciente P.N.C.S.)".getBytes());
    }

    /**
     * CAMINHO FELIZ: uma unica acao anexa a versao anonimizada COMO documento
     * clinico e tira o original do processo - banco e disco. Antes deste
     * endpoint eram duas acoes desconectadas (excluir no bloco vermelho +
     * subir de novo no formulario generico, mais abaixo na tela).
     */
    @Test
    @WithMockUser(username = "operador-sub", roles = "OPERADOR")
    void substituicaoAnexaVersaoAnonimizadaERemoveOOriginalNumaAcaoSo() throws Exception {
        mvc.perform(multipart("/processos/" + processoId + "/documento-clinico/" + pendenteId + "/substituir")
                        .file(pdf("laudo-anonimizado.pdf"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/processos/" + processoId + "#envio"))
                .andExpect(flash().attribute("msg", containsString("original removido")));

        // O pendente sumiu do banco E do disco (o arquivo trazia o nome completo
        // do paciente - nao ha motivo para manter uma copia de trabalho dele).
        assertThat(anexoRepo.findById(pendenteId)).isEmpty();
        assertThat(Files.exists(arquivoPendente)).isFalse();
        assertThat(anexosDoTipo(processoId, TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO)).isEmpty();

        // E existe exatamente UM documento clinico novo, gravado de verdade.
        List<Anexo> clinicos = anexosDoTipo(processoId, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        assertThat(clinicos).hasSize(1);
        Anexo novo = clinicos.get(0);
        assertThat(novo.getContentType()).contains("application/pdf");
        assertThat(novo.getDescricao()).contains("ANONIMIZADA").contains("operador-sub");
        assertThat(Files.exists(anexoStorage.resolverArquivo(novo))).isTrue();

        // Efeito de negocio que interessa ao operador: o envio deixou de estar
        // bloqueado pela trava de anonimizacao. Dentro de uma transacao porque o
        // validator navega a colecao LAZY de anexos (open-in-view: false).
        new TransactionTemplate(txManager).executeWithoutResult(st -> {
            Processo recarregado = processoRepo.findById(processoId).orElseThrow();
            assertThat(validator.temDocumentoPendenteAnonimizacao(recarregado)).isFalse();
            assertThat(validator.validarRegistroEnvio(recarregado)).isEmpty();
        });
    }

    /**
     * O rastro da substituicao vive so na auditoria (nunca uma copia do PDF
     * original), diz QUEM substituiu e QUAL arquivo - e, como todo log deste
     * sistema, NAO carrega o nome completo do paciente (mesma regra que ja
     * endureceu PROCESSO_CADASTRADO em 2026-07-28 e a exportacao de dossie em
     * 2026-08-03).
     */
    @Test
    @WithMockUser(username = "operador-sub", roles = "OPERADOR")
    void substituicaoRegistraAuditoriaSemNomeDoPaciente() throws Exception {
        mvc.perform(multipart("/processos/" + processoId + "/documento-clinico/" + pendenteId + "/substituir")
                        .file(pdf("laudo-anonimizado.pdf"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        List<LogAuditoria> logs = logRepo.findAll().stream()
                .filter(l -> "ANONIMIZACAO_SUBSTITUIDA".equals(l.getAcao()))
                .toList();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getDetalhe())
                .contains("91/2026")
                .contains("operador-sub")
                .doesNotContain(NOME_PACIENTE);
    }

    /**
     * CAMINHO DE FALHA 1 (o que nao pode deixar o processo inconsistente): um
     * arquivo que NAO e PDF. Ele passaria pela allowlist do
     * {@code AnexoStorageService} (png e permitido) e viraria um documento
     * clinico que a consolidacao ignora silenciosamente - com o original ja
     * apagado, o processo ficaria sem NENHUM documento elegivel ao envio e sem
     * como voltar atras. Rejeitado antes de qualquer escrita.
     */
    @Test
    @WithMockUser(username = "operador-sub", roles = "OPERADOR")
    void substituicaoComArquivoNaoPdfERejeitadaEMantemOOriginal() throws Exception {
        MockMultipartFile png = new MockMultipartFile("arquivo", "laudo.png", "image/png", "PNG".getBytes());

        mvc.perform(multipart("/processos/" + processoId + "/documento-clinico/" + pendenteId + "/substituir")
                        .file(png)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("erro", containsString("PDF")));

        assertThat(anexoRepo.findById(pendenteId)).isPresent();
        assertThat(Files.exists(arquivoPendente)).isTrue();
        // Nenhum anexo "concorrente" criado: a tela nao pode acabar com o
        // pendente E uma substituicao pela metade ao mesmo tempo.
        assertThat(anexosDoTipo(processoId, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR)).isEmpty();
    }

    /** CAMINHO DE FALHA 2: extensao fora da allowlist (nem chega ao storage). */
    @Test
    @WithMockUser(username = "operador-sub", roles = "OPERADOR")
    void substituicaoComExecutavelERejeitadaEMantemOOriginal() throws Exception {
        MockMultipartFile exe = new MockMultipartFile("arquivo", "malware.exe",
                "application/octet-stream", "MZ".getBytes());

        mvc.perform(multipart("/processos/" + processoId + "/documento-clinico/" + pendenteId + "/substituir")
                        .file(exe)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("erro"));

        assertThat(anexoRepo.findById(pendenteId)).isPresent();
        assertThat(anexosDoTipo(processoId, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR)).isEmpty();
    }

    /** CAMINHO DE FALHA 3: submissao sem arquivo nenhum. */
    @Test
    @WithMockUser(username = "operador-sub", roles = "OPERADOR")
    void substituicaoSemArquivoERejeitadaEMantemOOriginal() throws Exception {
        MockMultipartFile vazio = new MockMultipartFile("arquivo", "", "application/pdf", new byte[0]);

        mvc.perform(multipart("/processos/" + processoId + "/documento-clinico/" + pendenteId + "/substituir")
                        .file(vazio)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("erro"));

        assertThat(anexoRepo.findById(pendenteId)).isPresent();
        assertThat(anexosDoTipo(processoId, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR)).isEmpty();
    }

    /** Processo ENCERRADO trava a edicao - inclusive esta acao (etapa de Envio). */
    @Test
    @WithMockUser(username = "operador-sub", roles = "OPERADOR")
    void substituicaoBloqueadaEmProcessoEncerrado() throws Exception {
        mvc.perform(multipart("/processos/" + processoEncerradoId
                        + "/documento-clinico/" + pendenteDoEncerradoId + "/substituir")
                        .file(pdf("laudo-anonimizado.pdf"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("erro", ProcessoValidator.MSG_ENCERRADO));

        assertThat(anexoRepo.findById(pendenteDoEncerradoId)).isPresent();
        assertThat(anexosDoTipo(processoEncerradoId, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR)).isEmpty();
    }

    /**
     * Anti-IDOR: id de anexo de OUTRO processo na URL nunca e substituido/
     * apagado (a consulta de posse ja filtra por processo + tipo).
     */
    @Test
    @WithMockUser(username = "operador-sub", roles = "OPERADOR")
    void substituicaoDeAnexoDeOutroProcessoNaoAlteraNada() throws Exception {
        mvc.perform(multipart("/processos/" + processoId
                        + "/documento-clinico/" + pendenteDoEncerradoId + "/substituir")
                        .file(pdf("laudo-anonimizado.pdf"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("erro", containsString("não encontrado")));

        assertThat(anexoRepo.findById(pendenteDoEncerradoId)).isPresent();
        assertThat(anexosDoTipo(processoId, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR)).isEmpty();
        assertThat(anexoRepo.findById(pendenteId)).isPresent();
    }

    /**
     * Coerencia/idempotencia: um documento que ja e material do avaliador
     * (inclusive os de processos LEGADOS, convertidos antes da trava) nao esta
     * "pendente" e nao passa por esta acao - senao um clique repetido apagaria
     * um documento clinico ja liberado.
     */
    @Test
    @WithMockUser(username = "operador-sub", roles = "OPERADOR")
    void substituicaoDeDocumentoJaLiberadoNaoApagaNada() throws Exception {
        Processo p = processoRepo.findById(processoId).orElseThrow();
        Long clinicoId = anexoStorage.salvarBytes(p, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR,
                "Documento clinico ja anonimizado", "clinico.pdf", "application/pdf",
                "%PDF-1.4 clinico".getBytes()).getId();

        mvc.perform(multipart("/processos/" + processoId + "/documento-clinico/" + clinicoId + "/substituir")
                        .file(pdf("laudo-anonimizado.pdf"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("erro", containsString("não encontrado")));

        assertThat(anexoRepo.findById(clinicoId)).isPresent();
        assertThat(anexosDoTipo(processoId, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR)).hasSize(1);
    }

    /**
     * A area de revisao precisa deixar explicito que NADA dali vai aos
     * avaliadores (o incomodo relatado pelo dono do produto: o arquivo parecia
     * "anexado ao processo") e oferecer a substituicao no proprio item, em vez
     * de mandar o operador excluir e procurar outro formulario na tela.
     */
    @Test
    @WithMockUser(username = "operador-sub", roles = "OPERADOR")
    void telaMostraAAreaDeRevisaoComAAcaoDeSubstituirNoProprioItem() throws Exception {
        mvc.perform(get("/processos/" + processoId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Área de revisão")))
                .andExpect(content().string(containsString("Nenhum destes arquivos vai para os avaliadores")))
                .andExpect(content().string(containsString(
                        "/processos/" + processoId + "/documento-clinico/" + pendenteId + "/substituir")))
                // Os outros dois caminhos continuam disponiveis no mesmo item.
                .andExpect(content().string(containsString(
                        "/processos/" + processoId + "/documento-clinico/" + pendenteId
                                + "/confirmar-anonimizacao")))
                .andExpect(content().string(containsString(
                        "/processos/anexos/" + pendenteId + "/excluir")));
    }
}

package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.service.ExportacaoProcessoService;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Exportar processo completo" (dossie ZIP) — teste de INTEGRACAO real
 * (contexto Spring completo, sem mock de service).
 *
 * O teste precisa ser de integracao (e nao {@code @WebMvcTest}) por causa de
 * {@code spring.jpa.open-in-view: false}: o ZIP e escrito num
 * {@code StreamingResponseBody}, FORA da transacao do controller. Se o servico
 * deixasse qualquer colecao LAZY (pareceres/anexos) para ser tocada durante o
 * streaming, so um teste com JPA de verdade pegaria o
 * {@code LazyInitializationException}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-exportacao;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-exportacao"
})
class ProcessoExportacaoIntegrationTest {

    private static final Path RAIZ_ANEXOS =
        Paths.get("./target/test-anexos-exportacao").toAbsolutePath().normalize();

    @Autowired private MockMvc mvc;
    @Autowired private ProcessoRepository processoRepo;
    @Autowired private ParecerRepository parecerRepo;
    @Autowired private AnexoRepository anexoRepo;
    @Autowired private MembroUrgenciaRenalRepository membroRepo;

    private Long processoId;

    /**
     * Baixa o ZIP aguardando o processamento assincrono terminar. A rota devolve
     * {@code StreamingResponseBody}, entao o MockMvc so tem o corpo COMPLETO
     * depois que o callable de escrita termina - sem esperar, o teste le um ZIP
     * truncado ("Unexpected end of ZLIB input stream").
     */
    private byte[] baixarZip() throws Exception {
        MvcResult res = mvc.perform(get("/processos/" + processoId + "/exportar"))
            .andExpect(status().isOk())
            .andReturn();
        res.getAsyncResult(); // bloqueia ate o ZIP terminar de ser escrito
        return res.getResponse().getContentAsByteArray();
    }

    /** Pasta esperada dentro do ZIP. */
    private static final String PASTA = "Maria Souza da Silva - Processo CET-RS 07-2026";

    @BeforeEach
    @Transactional
    void preparar() throws Exception {
        anexoRepo.deleteAll();
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        Processo p = new Processo();
        p.setNumero("07/2026");
        p.setAno(2026);
        p.setSequencial(7);
        p.setPacienteNome("Maria Souza da Silva");
        p.setPacienteRgct("123456789");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 3, 1));
        p.setStatus(StatusProcesso.DEFERIDO);
        p.setObservacoes("Observacao de teste.");
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        String[][] medicos = {{"HCPA", "Ana Nefro"}, {"ISCMPA", "Bruno Nefro"}, {"CET", "Carla Nefro"}};
        ResultadoParecer[] votos = {ResultadoParecer.FAVORAVEL, ResultadoParecer.FAVORAVEL,
            ResultadoParecer.NAO_FAVORAVEL};
        for (int i = 0; i < medicos.length; i++) {
            MembroUrgenciaRenal m = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal(medicos[i][0], medicos[i][1], "m" + i + "@example.com"));
            Parecer par = new Parecer(m);
            par.setProcesso(p);
            par.setDataEnvio(LocalDate.of(2026, 3, 2));
            par.setDataResposta(LocalDate.of(2026, 3, 4));
            par.setResultado(votos[i]);
            par.setJustificativa("Justificativa do avaliador " + (i + 1));
            parecerRepo.saveAndFlush(par);
        }

        // Dois anexos com arquivo de verdade em disco + um cujo arquivo NAO existe
        Files.createDirectories(RAIZ_ANEXOS.resolve("07-2026 - Maria Souza da Silva"));
        criarAnexo(p, TipoAnexo.DOCUMENTO_PACIENTE, "solicitacao-original.pdf", true);
        criarAnexo(p, TipoAnexo.OUTRO, "capa.pdf", true);
        criarAnexo(p, TipoAnexo.COMPROVANTE_SNT, "comprovante-sumido.pdf", false);
    }

    private void criarAnexo(Processo p, TipoAnexo tipo, String nome, boolean gravarEmDisco)
            throws Exception {
        String relativo = Paths.get("07-2026 - Maria Souza da Silva", nome).toString();
        if (gravarEmDisco) {
            Files.write(RAIZ_ANEXOS.resolve(relativo),
                ("conteudo de " + nome).getBytes(StandardCharsets.UTF_8));
        } else {
            Files.deleteIfExists(RAIZ_ANEXOS.resolve(relativo));
        }
        Anexo a = new Anexo();
        a.setProcesso(p);
        a.setTipo(tipo);
        a.setNomeArquivo(nome);
        // contentType nao-PDF de proposito: o Relatorio Final so mescla PDFs de
        // verdade, e aqui os arquivos sao texto simulado.
        a.setContentType("application/octet-stream");
        a.setTamanhoBytes(10L);
        a.setCaminhoArmazenado(relativo);
        anexoRepo.saveAndFlush(a);
    }

    // ------------------------------------------------------------------

    private static List<String> entradas(byte[] zip) throws Exception {
        List<String> nomes = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                nomes.add(e.getName());
                in.readAllBytes();
            }
        }
        return nomes;
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void operadorBaixaODossieCompletoComUmaPastaUnica() throws Exception {
        mvc.perform(get("/processos/" + processoId + "/exportar"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("filename*=UTF-8''")));

        byte[] zip = baixarZip();
        assertThat(zip).isNotEmpty();
        List<String> nomes = entradas(zip);

        // 1) TODAS as entradas ficam sob a pasta unica -> descompactar cria 1 pasta
        assertThat(nomes).isNotEmpty().allMatch(n -> n.startsWith(PASTA + "/"));

        // 2) Relatorio final + resumo
        assertThat(nomes).contains(
            PASTA + "/" + ExportacaoProcessoService.ARQUIVO_RELATORIO,
            PASTA + "/" + ExportacaoProcessoService.ARQUIVO_RESUMO);

        // 3) Anexos, com nome legivel prefixado pelo tipo
        assertThat(nomes).anyMatch(n -> n.startsWith(PASTA + "/Anexos/")
            && n.contains("solicitacao-original.pdf"));
        assertThat(nomes).anyMatch(n -> n.startsWith(PASTA + "/Anexos/") && n.contains("capa.pdf"));
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void anexoAusenteNoDiscoNaoQuebraAExportacao() throws Exception {
        byte[] zip = baixarZip();
        List<String> nomes = entradas(zip);
        // O anexo sumido nao entra...
        assertThat(nomes).noneMatch(n -> n.contains("comprovante-sumido.pdf"));
        // ...mas vira uma linha no relatorio de problemas, e o resto do ZIP existe.
        assertThat(nomes).contains(
            PASTA + "/Anexos/" + ExportacaoProcessoService.ARQUIVO_PROBLEMAS,
            PASTA + "/" + ExportacaoProcessoService.ARQUIVO_RELATORIO);
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    void resumoTrazDadosDoProcessoPareceresEMovimentacao() throws Exception {
        byte[] zip = baixarZip();
        String resumo = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().endsWith(ExportacaoProcessoService.ARQUIVO_RESUMO)) {
                    resumo = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    break;
                }
                in.readAllBytes();
            }
        }
        assertThat(resumo).isNotNull();
        assertThat(resumo)
            .contains("07/2026")
            .contains("Maria Souza da Silva")   // pacote interno: nome completo
            .contains("HCPA")
            .contains("HCPA - Ana Nefro")
            .contains("Justificativa do avaliador 1")
            .contains("Nao favoravel")
            .contains("Deferido")
            .contains("MOVIMENTACAO (LINHA DO TEMPO)")
            // EtapaFluxo.titulo() e texto de exibicao, acentuado desde a
            // Fase 7 do relatorio de clareza (2026-08-05) - a identidade da
            // etapa passou a ser EtapaFluxo.Chave, nao mais o titulo. O
            // Recebimento foi fundido em Envio no mesmo dia (2026-08-05) -
            // Envio passou a ser a primeira etapa da linha do tempo.
            .contains("Envio aos 3 médicos");
    }

    @Test
    @WithMockUser(roles = "AVALIADOR")
    void avaliadorNaoPodeExportarODossie() throws Exception {
        mvc.perform(get("/processos/" + processoId + "/exportar"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SOLICITANTE")
    void solicitanteNaoPodeExportarODossie() throws Exception {
        mvc.perform(get("/processos/" + processoId + "/exportar"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPodeExportar() throws Exception {
        mvc.perform(get("/processos/" + processoId + "/exportar"))
            .andExpect(status().isOk());
    }

    /**
     * F2 do relatorio de vistoria de brechas (2026-08-10) - achados 2 e 3:
     * um processo deferido pelo VOTO UNICO do Coordenador da CET-RS (1
     * favoravel, excecao regimental) nao pode, em NENHUM documento do
     * dossie (nem {@code Resumo-do-Processo.txt}, nem
     * {@code Relatorio-Final.pdf}), afirmar "regra: 2 de 3" nem "Maioria
     * formada" - contradiria a propria regra que cita, ao lado da decisao
     * que a dispensou. Integracao real (contexto Spring completo, PDF
     * gerado de verdade) - fonte unica RegraDecisao/ProcessoValidator
     * .regraAplicada, consumida tanto pelo resumo quanto pelo PDF.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void dossieDeProcessoDeferidoPeloCoordenadorNaoAfirmaRegraDeMaioriaEmNenhumDocumento() throws Exception {
        Processo p = new Processo();
        p.setNumero("09/2026");
        p.setAno(2026);
        p.setSequencial(9);
        p.setPacienteNome("Pedro Coordenador Teste");
        p.setPacienteRgct("999888777");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 3, 1));
        p.setStatus(StatusProcesso.DEFERIDO);
        processoRepo.saveAndFlush(p);
        Long idCoordenador = p.getId();

        MembroUrgenciaRenal coordenador = membroRepo.saveAndFlush(
            new MembroUrgenciaRenal("CET-RS", "Dra. Coordenadora", "coord@example.com"));
        coordenador.setCoordenador(true);
        membroRepo.saveAndFlush(coordenador);

        Parecer par = new Parecer(coordenador);
        par.setProcesso(p);
        par.setDataEnvio(LocalDate.of(2026, 3, 2));
        par.setDataResposta(LocalDate.of(2026, 3, 3));
        par.setResultado(ResultadoParecer.FAVORAVEL);
        par.setEraCoordenadorNoVoto(true);
        parecerRepo.saveAndFlush(par);

        MvcResult res = mvc.perform(get("/processos/" + idCoordenador + "/exportar"))
            .andExpect(status().isOk())
            .andReturn();
        res.getAsyncResult();
        byte[] zip = res.getResponse().getContentAsByteArray();

        String resumo = null;
        byte[] relatorioPdf = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().endsWith(ExportacaoProcessoService.ARQUIVO_RESUMO)) {
                    resumo = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } else if (e.getName().endsWith(ExportacaoProcessoService.ARQUIVO_RELATORIO)) {
                    relatorioPdf = in.readAllBytes();
                } else {
                    in.readAllBytes();
                }
            }
        }
        assertThat(resumo).isNotNull();
        assertThat(relatorioPdf).isNotNull();

        // 1) Resumo-do-Processo.txt (Achado 2)
        assertThat(resumo)
            .contains("Deferido")
            .doesNotContain("regra: 2 de 3")
            .contains("Coordenador");

        // 2) Relatorio-Final.pdf (Achado 3, secao "4. Andamento do processo")
        PdfReader reader = new PdfReader(relatorioPdf);
        StringBuilder texto = new StringBuilder();
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            texto.append(new PdfTextExtractor(reader).getTextFromPage(i));
        }
        reader.close();
        assertThat(texto.toString())
            .doesNotContain("Maioria formada")
            .doesNotContain("regra 2 de 3 favoraveis")
            .doesNotContain("regra: 2 de 3");
    }
}

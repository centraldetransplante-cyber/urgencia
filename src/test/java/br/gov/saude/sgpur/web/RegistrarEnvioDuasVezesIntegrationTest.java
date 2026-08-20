package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.service.AnexoStorageService;
import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2 real, servicos reais, sem
 * nenhum mock) que reproduz e comprova a correcao do bug reportado em
 * 2026-07-29: registrar o envio de um processo pela SEGUNDA vez (ex.: apos o
 * ADMIN reabrir um processo ja enviado) dava 500 com
 * {@code ObjectDeletedException: deleted instance passed to merge: Anexo#null}.
 *
 * <p>
 * <b>Causa raiz:</b> {@code AnexoStorageService.removerAntigosDoTipo}
 * apagava o anexo antigo via {@code anexoRepository.delete(a)} (marcando a
 * instancia como REMOVIDA na sessao) mas nunca tirava essa mesma instancia da
 * colecao {@code Processo.anexos} em memoria. Como
 * {@code @OneToMany(cascade = CascadeType.ALL)} inclui MERGE, o
 * {@code processoService.salvar(p)} chamado logo depois (dentro da mesma
 * transacao) cascateava merge para a colecao inteira, incluindo aquele anexo
 * ja deletado — Hibernate recusa com {@code ObjectDeletedException}.
 * </p>
 *
 * <p>
 * Por que precisa ser {@code @SpringBootTest} (e nao
 * {@code @WebMvcTest}/mock de servico): o bug so existe com o Hibernate
 * Session/PersistenceContext de verdade fazendo o merge cascade da colecao —
 * com {@code AnexoStorageService} mockado a chamada de
 * {@code removerAntigosDoTipo} nunca toca banco nenhum e o bug e
 * inexprimivel.
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
                "spring.datasource.url=jdbc:h2:mem:sgpur-registrar-envio-2x;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "app.anexos.dir=./target/test-anexos-registrar-envio-2x"
})
class RegistrarEnvioDuasVezesIntegrationTest {

        @Autowired
        private MockMvc mvc;
        @Autowired
        private ProcessoRepository processoRepo;
        @Autowired
        private AnexoRepository anexoRepo;
        @Autowired
        private AnexoStorageService anexoStorage;

        private Long processoId;

        /**
         * Gera um PDF minimo (1 pagina) valido, para simular um documento clinico real.
         */
        private static byte[] pdfValido() {
                try {
                        Document doc = new Document(PageSize.A4);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        PdfWriter.getInstance(doc, out);
                        doc.open();
                        doc.add(new Paragraph("Documento clinico de teste"));
                        doc.close();
                        return out.toByteArray();
                } catch (Exception e) {
                        throw new RuntimeException(e);
                }
        }

        @BeforeEach
        @Transactional
        void preparar() throws Exception {
                anexoRepo.deleteAll();
                processoRepo.deleteAll();

                Processo p = new Processo();
                p.setNumero("55/2026");
                p.setAno(2026);
                p.setSequencial(55);
                p.setPacienteNome("Paciente Teste Reenvio");
                p.setPacienteRgct("456456456");
                p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
                p.setPacienteCpf("11144477735");
                p.setPacienteSexo(Sexo.MASCULINO);
                p.setSolicitanteEquipe("HCPA");
                p.setSolicitanteEmail("equipe@hcpa.example.com");
                p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
                p.setStatus(StatusProcesso.ENVIADO);
                processoRepo.saveAndFlush(p);
                processoId = p.getId();

                // Documento clinico PDF valido, obrigatorio para o 1o (e o 2o) registro de
                // envio.
                anexoStorage.salvar(p, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR, "Exame de teste",
                                new MockMultipartFile("arquivo", "exame.pdf", "application/pdf", pdfValido()));
        }

        /**
         * REGRASSAO DO BUG: registrar o envio, encerrar o processo (simulando uma
         * decisao), reabrir (ADMIN) e registrar o envio de novo. Antes da
         * correcao, o 2o registro estourava 500 (ObjectDeletedException) porque o
         * anexo SOLICITACAO_AVALIADOR do 1o envio ja tinha sido removido da
         * colecao do Processo de forma inconsistente.
         */
        @Test
        @WithMockUser(username = "admin-it", roles = "ADMIN")
        void registrarEnvioDuasVezesAposReabrirNaoQuebra() throws Exception {
                // 1o registro de envio: cria o anexo SOLICITACAO_AVALIADOR #1.
                mvc.perform(post("/processos/" + processoId + "/registrar-envio").with(csrf()))
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                                .is3xxRedirection());

                Processo depoisDoPrimeiroEnvio = processoRepo.findById(processoId).orElseThrow();
                assertThat(depoisDoPrimeiroEnvio.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
                long anexosSolicitacaoAposPrimeiroEnvio = anexoRepo
                                .findByProcessoIdAndTipo(processoId, TipoAnexo.SOLICITACAO_AVALIADOR).size();
                assertThat(anexosSolicitacaoAposPrimeiroEnvio).isEqualTo(1);

                // Simula uma decisao (encerra o processo) para poder reabrir.
                Processo p = processoRepo.findById(processoId).orElseThrow();
                p.setStatus(StatusProcesso.CANCELADO);
                processoRepo.saveAndFlush(p);

                // ADMIN reabre: volta para ENVIADO.
                mvc.perform(post("/processos/" + processoId + "/reabrir").with(csrf()))
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                                .is3xxRedirection());
                assertThat(processoRepo.findById(processoId).orElseThrow().getStatus())
                                .isEqualTo(StatusProcesso.ENVIADO);

                // 2o registro de envio: dispara removerAntigosDoTipo sobre o anexo do
                // 1o envio, na MESMA sessao em que o Processo (com a colecao de
                // anexos ja carregada) e salvo logo em seguida - e exatamente aqui
                // que o ObjectDeletedException acontecia antes da correcao.
                var resultado = mvc.perform(post("/processos/" + processoId + "/registrar-envio").with(csrf()))
                                .andReturn();

                assertThat(resultado.getResponse().getStatus())
                                .as("2o registro de envio nao deve devolver 500 (ver ObjectDeletedException do bug original)")
                                .isEqualTo(302);
                assertThat(resultado.getResponse().getRedirectedUrl()).isEqualTo("/processos/" + processoId + "#envio");
                assertThat(resultado.getFlashMap().get("erro")).isNull();

                // So sobra 1 anexo SOLICITACAO_AVALIADOR (o antigo foi de fato substituido).
                var anexosFinal = anexoRepo.findByProcessoIdAndTipo(processoId, TipoAnexo.SOLICITACAO_AVALIADOR);
                assertThat(anexosFinal).hasSize(1);

                Processo depoisDoSegundoEnvio = processoRepo.findById(processoId).orElseThrow();
                assertThat(depoisDoSegundoEnvio.getStatus()).isEqualTo(StatusProcesso.ENVIADO);
        }

        /**
         * CENARIO DE CONTROLE (substituicao normal, sem falha): confirma que a
         * correcao nao regrediu a garantia "salva o novo antes de remover o
         * antigo" documentada em {@code ProcessoAnexoController.substituirAnexo}
         * - subir o Comprovante SNT duas vezes (ambas validas) deve deixar
         * exatamente 1 anexo desse tipo no final, com o disco tambem refletindo
         * so o arquivo mais recente.
         */
        @Test
        @WithMockUser(username = "admin-it", roles = "ADMIN")
        void substituirComprovanteSntDuasVezesMantemApenasUmAnexo() throws Exception {
                Processo deferido = new Processo();
                deferido.setNumero("56/2026");
                deferido.setAno(2026);
                deferido.setSequencial(56);
                deferido.setPacienteNome("Paciente Teste SNT");
                deferido.setPacienteRgct("789789789");
                deferido.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
                deferido.setPacienteCpf("11144477735");
                deferido.setPacienteSexo(Sexo.MASCULINO);
                deferido.setSolicitanteEquipe("HCPA");
                deferido.setSolicitanteEmail("equipe@hcpa.example.com");
                deferido.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
                deferido.setStatus(StatusProcesso.DEFERIDO);
                processoRepo.saveAndFlush(deferido);
                Long deferidoId = deferido.getId();

                mvc.perform(multipart("/processos/" + deferidoId + "/comprovante-snt")
                                .file(new MockMultipartFile("arquivo", "comprovante1.pdf", "application/pdf",
                                                pdfValido()))
                                .with(csrf()))
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                                .is3xxRedirection());
                assertThat(anexoRepo.findByProcessoIdAndTipo(deferidoId, TipoAnexo.COMPROVANTE_SNT)).hasSize(1);

                var resultadoSegundo = mvc.perform(multipart("/processos/" + deferidoId + "/comprovante-snt")
                                .file(new MockMultipartFile("arquivo", "comprovante2.pdf", "application/pdf",
                                                pdfValido()))
                                .with(csrf()))
                                .andReturn();

                assertThat(resultadoSegundo.getResponse().getStatus()).isEqualTo(302);
                assertThat(resultadoSegundo.getFlashMap().get("erro")).isNull();
                var anexosFinal = anexoRepo.findByProcessoIdAndTipo(deferidoId, TipoAnexo.COMPROVANTE_SNT);
                assertThat(anexosFinal).hasSize(1);
                assertThat(anexosFinal.get(0).getNomeArquivo()).contains("Comprovante SNT");
        }

        /**
         * Confirma que a garantia de atomicidade ("nunca fica sem nenhum anexo
         * daquele tipo se a substituicao falhar no meio do caminho") continua
         * valendo apos a correcao: o 2o upload usa uma extensao fora da allowlist
         * ({@code validarTipoPermitido} recusa ANTES de
         * {@code removerAntigosDoTipo} sequer ser chamado), entao o comprovante
         * original deve sobreviver intacto.
         */
        @Test
        @WithMockUser(username = "admin-it", roles = "ADMIN")
        void falhaAoSubstituirComprovanteSntPreservaOAnexoOriginal() throws Exception {
                Processo deferido = new Processo();
                deferido.setNumero("57/2026");
                deferido.setAno(2026);
                deferido.setSequencial(57);
                deferido.setPacienteNome("Paciente Teste SNT Falha");
                deferido.setPacienteRgct("321321321");
                deferido.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
                deferido.setPacienteCpf("11144477735");
                deferido.setPacienteSexo(Sexo.MASCULINO);
                deferido.setSolicitanteEquipe("HCPA");
                deferido.setSolicitanteEmail("equipe@hcpa.example.com");
                deferido.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
                deferido.setStatus(StatusProcesso.DEFERIDO);
                processoRepo.saveAndFlush(deferido);
                Long deferidoId = deferido.getId();

                mvc.perform(multipart("/processos/" + deferidoId + "/comprovante-snt")
                                .file(new MockMultipartFile("arquivo", "comprovante-original.pdf", "application/pdf",
                                                pdfValido()))
                                .with(csrf()))
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                                .is3xxRedirection());
                var anexosAntes = anexoRepo.findByProcessoIdAndTipo(deferidoId, TipoAnexo.COMPROVANTE_SNT);
                assertThat(anexosAntes).hasSize(1);
                Long idOriginal = anexosAntes.get(0).getId();

                var resultadoFalha = mvc.perform(multipart("/processos/" + deferidoId + "/comprovante-snt")
                                .file(new MockMultipartFile("arquivo", "malware.exe", "application/octet-stream",
                                                "x".getBytes()))
                                .with(csrf()))
                                .andReturn();

                assertThat(resultadoFalha.getFlashMap().get("erro")).isNotNull();
                var anexosDepois = anexoRepo.findByProcessoIdAndTipo(deferidoId, TipoAnexo.COMPROVANTE_SNT);
                assertThat(anexosDepois).hasSize(1);
                assertThat(anexosDepois.get(0).getId()).isEqualTo(idOriginal);
        }
}

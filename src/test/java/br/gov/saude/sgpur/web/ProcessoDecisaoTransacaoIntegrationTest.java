package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.OrigemParecer;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.service.DecisaoFinalService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2, {@code ProcessoService} e
 * {@code ProcessoValidator} REAIS com o proxy transacional de verdade) do
 * endpoint mais critico do sistema: {@code POST /processos/{id}/decidir}.
 *
 * <p><b>Por que o {@code @WebMvcTest} existente
 * ({@code ProcessoDecisaoControllerTest}) nao basta:</b> la {@code
 * ProcessoService} inteiro e mockado, entao nao existe nenhum
 * {@code TransactionInterceptor} de verdade em volta de
 * {@code processoService.decidir(...)} - o teste
 * {@code falhaAoGerarDocumentosNaoImpedeQueADecisaoJaGravadaFiquePendenteDeAvisoNaTela}
 * prova so que o CONTROLLER trata a excecao, nunca que a decisao sobrevive a
 * uma transacao fisica de verdade. A garantia real do sistema hoje e apenas
 * arquitetural (nenhum {@code @Transactional} de classe/metodo compartilha a
 * transacao entre {@code decidir()} e a geracao dos documentos finais - ver o
 * javadoc de {@code ProcessoDecisaoController.decidir}); este teste comprova
 * essa garantia contra o banco real, no mesmo espirito de
 * {@code AvaliadorVotoTransacaoIntegrationTest}.</p>
 *
 * <p>A falha do pos-processamento e injetada de forma deterministica:
 * {@code DecisaoFinalService.gerarDocumentos} (unico ponto mockado) lanca
 * {@code IllegalStateException}, exatamente como acontece de verdade quando a
 * geracao do PDF do oficio/relatorio falha.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-decisao-tx;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-decisao-tx"
})
class ProcessoDecisaoTransacaoIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;

    /**
     * Unico ponto mockado: a geracao dos PDFs finais apos a decisao ja
     * gravada. Serve de gatilho deterministico para a falha do
     * pos-processamento (ver javadoc da classe).
     */
    @MockitoBean
    private DecisaoFinalService decisaoFinalService;

    private Long processoId;

    @BeforeEach
    @Transactional
    void preparar() {
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();

        Processo p = new Processo();
        p.setNumero("88/2026");
        p.setAno(2026);
        p.setSequencial(88);
        p.setPacienteNome("Paciente Teste Decisao");
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

        MembroUrgenciaRenal m1 = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("HCPA", "Ana Um", "ana@example.com"));
        MembroUrgenciaRenal m2 = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("ISCMPA", "Bruno Dois", "bruno@example.com"));
        MembroUrgenciaRenal m3 = membroRepo.saveAndFlush(
                new MembroUrgenciaRenal("CET", "Carla Tres", "carla@example.com"));

        // 2 favoraveis (maioria simples) e o 3o ainda pendente: decisao
        // DEFERIDO e legitima sem depender da excecao do coordenador.
        Parecer p1 = new Parecer(m1);
        p1.setProcesso(p);
        p1.setDataEnvio(LocalDate.of(2026, 5, 2));
        p1.setResultado(ResultadoParecer.FAVORAVEL);
        p1.setDataResposta(LocalDate.of(2026, 5, 3));
        p1.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        parecerRepo.saveAndFlush(p1);

        Parecer p2 = new Parecer(m2);
        p2.setProcesso(p);
        p2.setDataEnvio(LocalDate.of(2026, 5, 2));
        p2.setResultado(ResultadoParecer.FAVORAVEL);
        p2.setDataResposta(LocalDate.of(2026, 5, 3));
        p2.setOrigem(OrigemParecer.AVALIADOR_SISTEMA);
        parecerRepo.saveAndFlush(p2);

        Parecer p3 = new Parecer(m3);
        p3.setProcesso(p);
        p3.setDataEnvio(LocalDate.of(2026, 5, 2));
        parecerRepo.saveAndFlush(p3);
    }

    /**
     * REGRESSAO DO BUG-ALVO: a geracao dos documentos finais (oficio/
     * relatorio) falha, mas a decisao (status + dataDecisao) ja gravada em
     * {@code processoService.decidir} sobrevive no banco, e o operador recebe
     * um flash de erro tratado - nunca um 500.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void decisaoSobreviveQuandoGeracaoDeDocumentosFinaisFalha() throws Exception {
        doThrow(new IllegalStateException("falha simulada ao gerar o PDF"))
                .when(decisaoFinalService).gerarDocumentos(any());

        var resultado = mvc.perform(post("/processos/" + processoId + "/decidir")
                        .with(csrf())
                        .param("decisao", "DEFERIDO"))
                .andReturn();

        // O QUE IMPORTA: a decisao esta no banco, mesmo com o pos-processamento
        // quebrado. Sem a separacao de transacoes (ver javadoc da classe) o
        // rollback-only da chamada aninhada desfaria a decisao junto.
        Processo depois = processoRepo.findById(processoId).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        assertThat(depois.getDataDecisao()).isNotNull();

        // E o operador ve um erro tratado (flash), nunca um 500 cru vindo de
        // UnexpectedRollbackException.
        assertThat(resultado.getResponse().getStatus()).isEqualTo(302);
        assertThat(resultado.getResponse().getRedirectedUrl()).isEqualTo("/processos/" + processoId);
        assertThat(resultado.getFlashMap().get("erro")).isEqualTo("falha simulada ao gerar o PDF");
    }

    /**
     * Controle: sem a falha injetada, o MESMO cenario chega ate o fim, a
     * decisao e gravada e o operador recebe a mensagem de sucesso - garante
     * que o teste acima falha pelo motivo certo (o pos-processamento
     * realmente e alcancado), nao por um setup que nunca chegaria a decidir.
     */
    @Test
    @WithMockUser(roles = "OPERADOR")
    void semFalhaADecisaoEGravadaEDocumentosSaoGerados() throws Exception {
        mvc.perform(post("/processos/" + processoId + "/decidir")
                        .with(csrf())
                        .param("decisao", "DEFERIDO"))
                .andReturn();

        Processo depois = processoRepo.findById(processoId).orElseThrow();
        assertThat(depois.getStatus()).isEqualTo(StatusProcesso.DEFERIDO);
        org.mockito.Mockito.verify(decisaoFinalService).gerarDocumentos(any());
    }
}

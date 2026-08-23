package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.service.DecisaoFinalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2 real, sem mock de servico)
 * da regra de datas da aba Finalizacao, revista em 2026-08-04:
 *
 * <p>
 * <b>A data de um ato registrado por anexo e o MOMENTO DO ANEXO, gravada
 * pelo relogio do servidor - nunca digitada.</b> Ate esta mudanca havia
 * {@code <input type="date">} para "data de emissao do oficio", "data de envio
 * do oficio" e "data de envio ao SNT", e um endpoint
 * {@code POST /{id}/finalizacao} que gravava o que viesse do formulario:
 * aceitava data retroativa (ou futura), o que e inadmissivel num processo
 * administrativo. Os campos e o endpoint foram removidos.
 *
 * <p>
 * <b>Por que {@code @SpringBootTest} e nao {@code @WebMvcTest}:</b> a
 * escrita e irreversivel (grava a data que sai no relatorio final e substitui
 * o anexo enviado a equipe solicitante) e envolve transacao real + gravacao em
 * disco. Com {@code @MockitoBean} do servico, nada disso aconteceria de fato -
 * exatamente a classe de bug que a convencao do CLAUDE.md manda cobrir com
 * servico real. Confere sempre relendo o processo DO BANCO.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-datas-finalizacao-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-datas-finalizacao-it"
})
class DatasFinalizacaoIntegrationTest {

    @Autowired
    private ProcessoAnexoController controller;
    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private AnexoRepository anexoRepo;
    @Autowired
    private DecisaoFinalService decisaoFinalService;
    @Autowired
    private TransactionTemplate tx;

    private Long processoId;

    @BeforeEach
    @Transactional
    void preparar() {
        anexoRepo.deleteAll();
        processoRepo.deleteAll();

        Processo p = new Processo();
        p.setNumero("09/2026");
        p.setAno(2026);
        p.setSequencial(9);
        p.setPacienteNome("Paciente Datas Finalizacao");
        p.setPacienteRgct("555555555");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 5, 1));
        p.setStatus(StatusProcesso.INDEFERIDO);
        p.setMotivoIndeferimento("Ausencia de indicacao clinica.");
        processoRepo.saveAndFlush(p);
        processoId = p.getId();

        // RedirectAttributes/flash e o controller usam o request corrente.
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private Processo relido() {
        return processoRepo.findById(processoId).orElseThrow();
    }

    /**
     * Producao chama {@code gerarDocumentos} de DENTRO da transacao de
     * {@code ProcessoService.decidir}, com a entidade gerenciada. Chamar com um
     * Processo destacado estoura lock otimista (o metodo salva o processo e
     * depois grava anexos sobre a mesma instancia) - por isso o teste usa a
     * mesma condicao da producao em vez de chamar solto.
     */
    private void gerarDocumentosComoNaDecisao() {
        tx.executeWithoutResult(st -> decisaoFinalService.gerarDocumentos(relido()));
    }

    @Test
    void uploadManualDoOficioGravaADataDeEmissaoComOMomentoDoAnexo() {
        Processo p = relido();
        p.setDataEmissaoOficio(LocalDate.of(2020, 1, 1)); // data antiga qualquer
        processoRepo.saveAndFlush(p);

        controller.uploadOficio(processoId, new MockMultipartFile(
                "arquivo", "oficio.pdf", "application/pdf", "%PDF-1.4\nconteudo".getBytes()),
                new RedirectAttributesModelMap());

        // O documento anexado agora e outro: a data de emissao acompanha o
        // arquivo novo, e nao a que estava gravada antes.
        assertThat(relido().getDataEmissaoOficio()).isEqualTo(LocalDate.now());
    }

    @Test
    void anexarComprovanteSntGravaADataDeEnvioAoSntComOMomentoDoAnexo() {
        Processo p = relido();
        p.setStatus(StatusProcesso.DEFERIDO);
        processoRepo.saveAndFlush(p);
        assertThat(relido().getDataEnvioSnt()).isNull();

        controller.uploadComprovanteSnt(processoId, new MockMultipartFile(
                "arquivo", "snt.pdf", "application/pdf", "%PDF-1.4\nconteudo".getBytes()),
                new RedirectAttributesModelMap());

        assertThat(relido().getDataEnvioSnt()).isEqualTo(LocalDate.now());
        assertThat(anexoRepo.findAll().stream()
                .anyMatch(a -> a.getTipo() == TipoAnexo.COMPROVANTE_SNT)).isTrue();
    }

    /**
     * Anexo recusado (extensao fora da allowlist) nao pode adiantar a data: o
     * processo passaria a exibir a data de envio de um comprovante que nunca
     * entrou no sistema.
     */
    @Test
    void anexoRecusadoNaoGravaDataNenhuma() {
        Processo p = relido();
        p.setStatus(StatusProcesso.DEFERIDO);
        processoRepo.saveAndFlush(p);

        controller.uploadComprovanteSnt(processoId, new MockMultipartFile(
                "arquivo", "snt.exe", "application/octet-stream", "conteudo".getBytes()),
                new RedirectAttributesModelMap());

        assertThat(relido().getDataEnvioSnt()).isNull();
        assertThat(anexoRepo.findAll().stream()
                .anyMatch(a -> a.getTipo() == TipoAnexo.COMPROVANTE_SNT)).isFalse();
    }

    /**
     * A decisao NAO anexa mais oficio nenhum ("oficio sera sempre anexado",
     * 2026-08-04): so reserva a numeracao, que o rascunho editavel carrega.
     */
    @Test
    void decisaoReservaONumeroDoOficioMasNaoAnexaOficioNenhum() {
        gerarDocumentosComoNaDecisao();

        assertThat(relido().getNumeroOficio()).isEqualTo("0001/2026");
        assertThat(relido().getDataEmissaoOficio()).isNull();
        assertThat(anexoRepo.findAll().stream()
                .anyMatch(a -> a.getTipo() == TipoAnexo.OFICIO_INDEFERIMENTO)).isFalse();
        // O relatorio final continua sendo gerado normalmente.
        assertThat(anexoRepo.findAll().stream()
                .anyMatch(a -> a.getTipo() == TipoAnexo.RELATORIO_FINAL)).isTrue();
    }

    /**
     * O rascunho editavel e so um download: nao anexa nada ao processo nem
     * move data alguma. O documento de registro continua sendo o que o
     * operador anexa depois.
     */
    @Test
    void baixarORascunhoEditavelNaoAnexaNadaNemMoveData() {
        gerarDocumentosComoNaDecisao();
        int anexosAntes = anexoRepo.findAll().size();

        var resposta = controller.oficioRascunho(processoId);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        String rtf = new String(resposta.getBody(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(rtf).startsWith("{\\rtf1");
        assertThat(rtf).contains("0001/2026"); // numero do oficio
        assertThat(rtf).contains("Ausencia de indicacao clinica."); // motivo do indeferimento
        assertThat(anexoRepo.findAll()).hasSize(anexosAntes);
        assertThat(relido().getDataEmissaoOficio()).isNull();
    }
}

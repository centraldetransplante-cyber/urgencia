package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de INTEGRACAO (H2 real) das consultas novas de "Deferido sem
 * comprovante SNT" — a pendencia que trava a comunicacao oficial ao
 * solicitante. Sao consultas com {@code not exists} sobre {@code Anexo}: nao
 * da para valida-las com mock de repositorio (o mock devolveria o que o teste
 * mandasse), e um erro nelas so apareceria em producao.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-snt-pendente-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-snt-pendente-it"
})
class ComprovanteSntPendenteQueriesIntegrationTest {

    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private AnexoRepository anexoRepo;
    /**
     * O UPDATE de {@code ultimoLembreteSntEm} e {@code @Modifying} e exige
     * transacao: quem a abre e o servico real (mesmo caminho do varredor),
     * nunca o repositorio chamado direto do teste.
     */
    @Autowired
    private ProcessoService processoService;

    @BeforeEach
    @Transactional
    void limpar() {
        anexoRepo.deleteAll();
        processoRepo.deleteAll();
    }

    private Processo processo(int sequencial, StatusProcesso status, LocalDateTime dataDecisao) {
        Processo p = new Processo();
        p.setNumero(String.format("%02d/2026", sequencial));
        p.setAno(2026);
        p.setSequencial(sequencial);
        p.setPacienteNome("Paciente " + sequencial);
        p.setPacienteRgct("1000" + sequencial);
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 4, 1));
        p.setStatus(status);
        p.setDataDecisao(dataDecisao);
        return processoRepo.saveAndFlush(p);
    }

    private void anexarComprovanteSnt(Processo p) {
        Anexo a = new Anexo();
        a.setProcesso(p);
        a.setTipo(TipoAnexo.COMPROVANTE_SNT);
        a.setNomeArquivo("comprovante.pdf");
        a.setCaminhoArmazenado("comprovante.pdf");
        a.setContentType("application/pdf");
        a.setDataUpload(LocalDateTime.now());
        anexoRepo.saveAndFlush(a);
    }

    @Test
    void contaESeleccionaSomenteDeferidosSemComprovante() {
        Processo semComprovante = processo(1, StatusProcesso.DEFERIDO, LocalDateTime.now().minusDays(20));
        Processo comComprovante = processo(2, StatusProcesso.DEFERIDO, LocalDateTime.now().minusDays(20));
        anexarComprovanteSnt(comComprovante);
        // Indeferido nunca entra (nao tem comprovante SNT nenhum, por definicao).
        processo(3, StatusProcesso.INDEFERIDO, LocalDateTime.now().minusDays(20));
        // Ainda em andamento tambem nao.
        processo(4, StatusProcesso.ENVIADO, null);

        assertThat(processoRepo.contarDeferidosSemComprovanteSnt()).isEqualTo(1);
        assertThat(processoRepo.findIdsDeferidosSemComprovanteSnt())
            .containsExactly(semComprovante.getId());
        assertThat(processoRepo.findDeferidosSemComprovanteSnt())
            .extracting(Processo::getId).containsExactly(semComprovante.getId());
    }

    @Test
    void candidatosAoLembreteRespeitamPrazoDesdeADecisaoEUltimoLembrete() {
        LocalDateTime limite = LocalDateTime.now().minusDays(7);

        Processo elegivel = processo(1, StatusProcesso.DEFERIDO, LocalDateTime.now().minusDays(20));
        // decidido ontem: dentro do prazo, ainda nao se cobra
        processo(2, StatusProcesso.DEFERIDO, LocalDateTime.now().minusDays(1));
        // decidido ha muito tempo, mas ja lembrado ontem
        Processo lembradoRecentemente = processo(3, StatusProcesso.DEFERIDO,
            LocalDateTime.now().minusDays(30));
        // Lembrado "ontem": grava agora e recua o timestamp para dentro do prazo.
        processoService.registrarLembreteComprovanteSnt(lembradoRecentemente.getId());
        // decidido ha muito tempo e lembrado ha muito tempo: cobra de novo
        Processo lembradoHaMuito = processo(4, StatusProcesso.DEFERIDO,
            LocalDateTime.now().minusDays(60));
        lembradoHaMuito.setUltimoLembreteSntEm(LocalDateTime.now().minusDays(30));
        processoRepo.saveAndFlush(lembradoHaMuito);
        // ja tem comprovante: nunca e candidato
        Processo comComprovante = processo(5, StatusProcesso.DEFERIDO,
            LocalDateTime.now().minusDays(40));
        anexarComprovanteSnt(comComprovante);

        assertThat(processoRepo.findCandidatosLembreteSnt(limite, limite))
            .extracting(Processo::getId)
            .containsExactlyInAnyOrder(elegivel.getId(), lembradoHaMuito.getId());
    }

    @Test
    void registrarUltimoLembreteSntGravaOTimestampNoBanco() {
        Processo p = processo(1, StatusProcesso.DEFERIDO, LocalDateTime.now().minusDays(10));
        LocalDateTime agora = LocalDateTime.now();

        processoService.registrarLembreteComprovanteSnt(p.getId());

        assertThat(processoRepo.findById(p.getId()).orElseThrow().getUltimoLembreteSntEm())
            .isNotNull()
            .isAfterOrEqualTo(agora);
    }

    @Test
    void numerosDeOficioSaoFiltradosPeloAnoDoProprioOficio() {
        Processo de2026 = processo(1, StatusProcesso.INDEFERIDO, LocalDateTime.now());
        de2026.setNumeroOficio("0007/2026");
        processoRepo.saveAndFlush(de2026);
        Processo de2025 = processo(2, StatusProcesso.INDEFERIDO, LocalDateTime.now());
        de2025.setNumeroOficio("0300/2025");
        processoRepo.saveAndFlush(de2025);
        processo(3, StatusProcesso.DEFERIDO, LocalDateTime.now());   // sem numero de oficio

        assertThat(processoRepo.findNumerosOficioDoAno("2026")).containsExactly("0007/2026");
        assertThat(processoRepo.findNumerosOficioDoAno("2025")).containsExactly("0300/2025");
        assertThat(processoRepo.findNumerosOficioDoAno("2027")).isEmpty();
    }
}

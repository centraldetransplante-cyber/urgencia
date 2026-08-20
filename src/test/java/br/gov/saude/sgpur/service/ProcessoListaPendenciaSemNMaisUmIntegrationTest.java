package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.AnexoRepository;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ParecerRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vistoria M3 (2026-08-05): o calculo de pendencia por linha
 * ({@code FluxoProcessoService.pendenciaAberta}, chamado uma vez por
 * processo em {@code ProcessoListaController}/{@code HomeController}) navega
 * {@code Processo.getPareceres()} e {@code Processo.getAnexos()} — ambos
 * LAZY na consulta paginada {@code ProcessoRepository.buscar}, o que gerava
 * dezenas de SELECTs extras por render (N+1). Este teste sobe H2 real com
 * {@code hibernate.generate_statistics} ligado e conta as consultas de
 * verdade — um mock de repositorio nunca pegaria isto, porque devolveria o
 * que o teste mandasse sem incorrer em nenhuma consulta lazy adicional.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-lista-nmais1-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "app.anexos.dir=./target/test-anexos-lista-nmais1-it"
})
class ProcessoListaPendenciaSemNMaisUmIntegrationTest {

    @Autowired
    private ProcessoRepository processoRepo;
    @Autowired
    private ParecerRepository parecerRepo;
    @Autowired
    private AnexoRepository anexoRepo;
    @Autowired
    private MembroUrgenciaRenalRepository membroRepo;
    @Autowired
    private ProcessoService processoService;
    @Autowired
    private FluxoProcessoService fluxoService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    private Statistics stats;

    @BeforeEach
    @Transactional
    void preparar() {
        anexoRepo.deleteAll();
        parecerRepo.deleteAll();
        processoRepo.deleteAll();
        membroRepo.deleteAll();
        stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
    }

    private Processo processoComPareceresEAnexo(int sequencial, MembroUrgenciaRenal... membros) {
        Processo p = new Processo();
        p.setNumero(String.format("%02d/2026", sequencial));
        p.setAno(2026);
        p.setSequencial(sequencial);
        p.setPacienteNome("Paciente " + sequencial);
        p.setPacienteRgct("2000" + sequencial);
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("HCPA");
        p.setSolicitanteEmail("equipe@hcpa.example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 4, 1));
        p.setStatus(StatusProcesso.SOLICITADO);
        p = processoRepo.saveAndFlush(p);

        for (MembroUrgenciaRenal m : membros) {
            Parecer par = new Parecer(m);
            par.setProcesso(p);
            parecerRepo.saveAndFlush(par);
        }

        Anexo a = new Anexo();
        a.setProcesso(p);
        a.setTipo(TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR);
        a.setNomeArquivo("laudo.pdf");
        a.setCaminhoArmazenado("laudo.pdf");
        a.setContentType("application/pdf");
        a.setDataUpload(LocalDateTime.now());
        anexoRepo.saveAndFlush(a);

        return p;
    }

    @Test
    @Transactional
    void listaPaginadaNaoIncorreEmNMaisUmAoCalcularPendenciaPorLinha() {
        MembroUrgenciaRenal m1 = membroRepo.saveAndFlush(new MembroUrgenciaRenal("HCPA", "Dr. A", "a@x.com"));
        MembroUrgenciaRenal m2 = membroRepo.saveAndFlush(new MembroUrgenciaRenal("ISCMPA", "Dr. B", "b@x.com"));
        MembroUrgenciaRenal m3 = membroRepo.saveAndFlush(new MembroUrgenciaRenal("CET", "Dr. C", "c@x.com"));

        // Volume representativo de uma pagina cheia (15 na tela real) - aqui
        // 10 bastam para expor o N+1 se ele reaparecer.
        int total = 10;
        for (int i = 1; i <= total; i++) {
            processoComPareceresEAnexo(i, m1, m2, m3);
        }

        // Desanexa tudo da persistence context: sem isso, "buscar" devolveria
        // as MESMAS instancias Java ja criadas acima (identity map da
        // sessao), com as colecoes ainda como o ArrayList vazio original -
        // nenhuma consulta seria disparada de qualquer jeito, mascarando o
        // teste. limpar aqui reproduz o cenario real de um controller: uma
        // requisicao nova, persistence context vazia, entidades carregadas do
        // zero.
        entityManager.flush();
        entityManager.clear();

        var pagina = processoService.buscar(null, null, PageRequest.of(0, 15));
        List<Processo> processos = pagina.getContent();
        assertThat(processos).hasSize(total);

        stats.clear();

        // Mesma sequencia dos controllers: inicializa em lote e so entao
        // calcula a pendencia por linha (que navega pareceres/anexos).
        processoService.inicializarPareceresEAnexos(processos);
        for (Processo p : processos) {
            fluxoService.pendenciaAberta(p);
        }

        long queries = stats.getPrepareStatementCount();
        // Sem o fetch em lote seriam, no minimo, ~2 SELECTs extras POR
        // processo (pareceres + anexos) = ~20 so para 10 processos. Com o
        // lote, e um numero fixo e pequeno de consultas (as 2 do lote),
        // independente de quantos processos existam na pagina.
        assertThat(queries)
            .as("consultas SQL disparadas ao inicializar pareceres/anexos + calcular pendencia de %d processos", total)
            .isLessThanOrEqualTo(5);
    }

    @Test
    @Transactional
    void mesmoSemChamarInicializacaoEmLoteOBatchSizeDaEntidadeSeguraOPior() {
        // Defesa em profundidade: Processo.pareceres/anexos ganharam
        // @BatchSize(20) (ver Processo.java) alem da inicializacao explicita
        // em lote de ProcessoService.inicializarPareceresEAnexos. Este teste
        // NAO chama esse metodo de proposito - confirma que, mesmo que um
        // chamador futuro esqueca de inicializar em lote antes de navegar as
        // colecoes, o numero de consultas continua PEQUENO E FIXO (o
        // Hibernate agrupa o carregamento lazy de ate 20 processos numa unica
        // consulta por colecao), nunca proporcional a quantidade de
        // processos da pagina.
        MembroUrgenciaRenal m1 = membroRepo.saveAndFlush(new MembroUrgenciaRenal("HCPA", "Dr. A", "a@x.com"));
        MembroUrgenciaRenal m2 = membroRepo.saveAndFlush(new MembroUrgenciaRenal("ISCMPA", "Dr. B", "b@x.com"));
        MembroUrgenciaRenal m3 = membroRepo.saveAndFlush(new MembroUrgenciaRenal("CET", "Dr. C", "c@x.com"));

        int total = 10;
        for (int i = 1; i <= total; i++) {
            processoComPareceresEAnexo(i, m1, m2, m3);
        }

        entityManager.flush();
        entityManager.clear();

        var pagina = processoService.buscar(null, null, PageRequest.of(0, 15));
        List<Processo> processos = pagina.getContent();

        stats.clear();
        for (Processo p : processos) {
            fluxoService.pendenciaAberta(p);
        }
        long queriesSemInicializacaoExplicita = stats.getPrepareStatementCount();

        assertThat(queriesSemInicializacaoExplicita)
            .as("mesmo sem inicializacao explicita, o @BatchSize mantem o numero de consultas baixo e fixo")
            .isLessThan(total);
    }
}

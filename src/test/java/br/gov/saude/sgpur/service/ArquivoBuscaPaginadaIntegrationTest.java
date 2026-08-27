package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de INTEGRACAO (H2 real) da busca paginada do Arquivo.
 *
 * <p><b>Por que precisa ser integracao.</b> Ate 2026-08-04 o
 * {@code ArquivoController} carregava TODOS os encerrados e filtrava em Java,
 * entao os testes de MockMvc conseguiam validar o filtro. Com a busca movida
 * para JPQL ({@code ProcessoRepository.buscarEncerrados}), um mock de
 * repositorio devolve o que o teste mandar - o filtro em si deixa de ser
 * testavel ali. Um erro no {@code like}, no {@code lower} ou no {@code in} de
 * status so apareceria em producao.</p>
 *
 * <p>O motivo da mudanca esta no javadoc do repositorio: o Arquivo e a unica
 * tela do sistema que so cresce, e era a unica sem paginacao.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sgpur-arquivo-busca-it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.anexos.dir=./target/test-anexos-arquivo-busca-it"
})
class ArquivoBuscaPaginadaIntegrationTest {

    private static final List<StatusProcesso> ENCERRADOS =
        List.of(StatusProcesso.DEFERIDO, StatusProcesso.INDEFERIDO, StatusProcesso.CANCELADO);

    @Autowired
    private ProcessoRepository repo;

    @BeforeEach
    @Transactional
    void limpar() {
        repo.deleteAll();
    }

    private Processo processo(int seq, String paciente, String equipe, StatusProcesso status) {
        Processo p = new Processo();
        p.setNumero(String.format("%02d/2026", seq));
        p.setAno(2026);
        p.setSequencial(seq);
        p.setPacienteNome(paciente);
        p.setPacienteRgct("2000" + seq);
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe(equipe);
        p.setSolicitanteEmail("equipe@example.com");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 4, 1));
        p.setStatus(status);
        return repo.saveAndFlush(p);
    }

    private Page<Processo> buscar(String q, int pagina, int tamanho) {
        return repo.buscarEncerrados(q, ENCERRADOS, false, false, PageRequest.of(pagina, tamanho));
    }

    @Test
    void trazSomenteProcessosEncerrados() {
        processo(1, "Maria Silva", "HCPA", StatusProcesso.DEFERIDO);
        processo(2, "Joao Souza", "HSL", StatusProcesso.INDEFERIDO);
        processo(3, "Ana Lima", "HCC", StatusProcesso.CANCELADO);
        // em andamento: NAO pode aparecer no Arquivo
        processo(4, "Carlos Dias", "HCPA", StatusProcesso.ENVIADO);
        processo(5, "Bruna Reis", "HSL", StatusProcesso.SOLICITADO);

        Page<Processo> pagina = buscar(null, 0, 15);

        assertThat(pagina.getTotalElements()).isEqualTo(3);
        assertThat(pagina.getContent())
            .extracting(Processo::getStatus)
            .doesNotContain(StatusProcesso.ENVIADO, StatusProcesso.SOLICITADO);
    }

    @Test
    void buscaPorNomeDoPacienteIgnorandoMaiusculas() {
        processo(1, "Maria Silva", "HCPA", StatusProcesso.DEFERIDO);
        processo(2, "Joao Souza", "HSL", StatusProcesso.INDEFERIDO);

        assertThat(buscar("maria", 0, 15).getContent())
            .extracting(Processo::getPacienteNome).containsExactly("Maria Silva");
        assertThat(buscar("MARIA", 0, 15).getContent()).hasSize(1);
        // trecho no meio do nome tambem casa (like %termo%)
        assertThat(buscar("silva", 0, 15).getContent()).hasSize(1);
    }

    @Test
    void buscaPorNumeroDoProcesso() {
        processo(1, "Maria Silva", "HCPA", StatusProcesso.DEFERIDO);
        processo(2, "Joao Souza", "HSL", StatusProcesso.INDEFERIDO);

        assertThat(buscar("02/2026", 0, 15).getContent())
            .extracting(Processo::getNumero).containsExactly("02/2026");
    }

    @Test
    void buscaPorEquipeSolicitanteIgnorandoMaiusculas() {
        processo(1, "Maria Silva", "HCPA", StatusProcesso.DEFERIDO);
        processo(2, "Joao Souza", "HSL", StatusProcesso.INDEFERIDO);

        assertThat(buscar("hsl", 0, 15).getContent())
            .extracting(Processo::getSolicitanteEquipe).containsExactly("HSL");
    }

    @Test
    void termoNuloOuVazioNaoFiltra() {
        processo(1, "Maria Silva", "HCPA", StatusProcesso.DEFERIDO);
        processo(2, "Joao Souza", "HSL", StatusProcesso.INDEFERIDO);

        assertThat(buscar(null, 0, 15).getTotalElements()).isEqualTo(2);
        assertThat(buscar("", 0, 15).getTotalElements()).isEqualTo(2);
    }

    @Test
    void termoQueNaoBateComNadaDevolveVazio() {
        processo(1, "Maria Silva", "HCPA", StatusProcesso.DEFERIDO);

        assertThat(buscar("nao existe ninguem assim", 0, 15).getContent()).isEmpty();
    }

    /**
     * O ponto da Fase E: o Arquivo so cresce, entao a consulta tem que trazer
     * uma fatia - nao a tabela inteira - e continuar ordenada do mais recente
     * para o mais antigo ATRAVES das paginas.
     */
    @Test
    void paginaOsResultadosMantendoAOrdemDoMaisRecenteParaOMaisAntigo() {
        for (int i = 1; i <= 25; i++) {
            processo(i, "Paciente " + i, "HCPA", StatusProcesso.DEFERIDO);
        }

        Page<Processo> primeira = buscar(null, 0, 10);
        assertThat(primeira.getContent()).hasSize(10);
        assertThat(primeira.getTotalElements()).isEqualTo(25);
        assertThat(primeira.getTotalPages()).isEqualTo(3);
        // sequencial 25 e o mais recente
        assertThat(primeira.getContent().get(0).getSequencial()).isEqualTo(25);
        assertThat(primeira.getContent().get(9).getSequencial()).isEqualTo(16);

        Page<Processo> segunda = buscar(null, 1, 10);
        assertThat(segunda.getContent().get(0).getSequencial()).isEqualTo(15);

        Page<Processo> ultima = buscar(null, 2, 10);
        assertThat(ultima.getContent()).hasSize(5);
        assertThat(ultima.getContent().get(4).getSequencial()).isEqualTo(1);
    }

    /** A contagem total tem que refletir o FILTRO, nao a tabela toda. */
    @Test
    void totalDeElementosRespeitaOFiltro() {
        for (int i = 1; i <= 12; i++) {
            processo(i, i % 2 == 0 ? "Maria " + i : "Joao " + i, "HCPA", StatusProcesso.DEFERIDO);
        }

        assertThat(buscar("maria", 0, 5).getTotalElements()).isEqualTo(6);
    }
}

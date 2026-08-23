package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import br.gov.saude.sgpur.support.CamposDeFormulario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.beans.PropertyDescriptor;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de INTEGRACAO (contexto Spring real + H2) de
 * {@link ProcessoService#atualizarDados(Long, Processo)}, contra a familia de
 * bugs de <b>escrita descartada em silencio</b> ja documentada no CLAUDE.md
 * ({@code UsuarioService.atualizar} sem {@code email},
 * {@code MembroController.salvar} com persist em vez de merge,
 * {@code ControleUrgenciaService.atualizar} sem {@code dataVencimento}).
 *
 * <p>Antes desta classe, {@code atualizarDados} so tinha teste do BLOQUEIO por
 * processo encerrado - o caminho de sucesso (o operador de fato salvando uma
 * correcao de cadastro) nao tinha nenhum teste, nem com mock. Um campo
 * esquecido no copy faria o operador ver "sucesso" e perder o dado em
 * silencio.
 *
 * <p>Le do banco depois do commit (nao do objeto {@code form} que o teste
 * acabou de montar, nem do {@code p} em memoria de um teste com
 * {@code @Mock ProcessoRepository} - que devolveria a MESMA instancia
 * mutada e mascararia um campo esquecido) e confere campo a campo. O caso
 * {@link #cadaCampoDoFormularioDeEdicaoChegaAoBanco()} deriva a lista de
 * campos do proprio {@code processos/editar.html}, entao um campo novo no
 * formulario sem a copia correspondente no service quebra o teste sozinho.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-processo-update;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.anexos.dir=./target/test-anexos-processo-update"
})
class ProcessoAtualizacaoIntegrationTest {

    private static final String FORM = "templates/processos/editar.html";

    /**
     * Campos do formulario que o service IGNORA de proposito - nao sao bug,
     * sao a regra documentada no javadoc do metodo ("numero e medicos nao
     * mudam"): o numero do processo e fixo (campo {@code readonly} na tela) e
     * so muda por outro caminho (numeracao automatica/manual), nunca por esta
     * edicao de cadastro.
     */
    private static final List<String> IGNORADOS_DE_PROPOSITO = List.of("numero");

    @Autowired
    private ProcessoService service;
    @Autowired
    private ProcessoRepository repo;

    private Long id;

    @BeforeEach
    void preparar() {
        repo.deleteAll();
        Processo p = new Processo();
        p.setNumero("10/2026");
        p.setAno(2026);
        p.setSequencial(10);
        p.setStatus(StatusProcesso.ENVIADO);
        p.setPacienteNome("Paciente Original");
        p.setPacienteRgct("RGCT-ORIG");
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("Equipe Original");
        p.setSolicitanteEmail("original@example.com");
        p.setDataSituacaoEspecial(LocalDate.now().minusDays(10));
        p.setObservacoes("Observacao original");
        id = repo.saveAndFlush(p).getId();
    }

    /**
     * O padrao central contra a familia: altera TODOS os campos editaveis de
     * uma vez, com valores distintos e reconheciveis, salva, RELE do banco e
     * confere cada campo individualmente.
     */
    @Test
    void atualizaTodosOsCamposEditaveisEReleDoBanco() {
        Processo form = new Processo();
        form.setPacienteNome("Paciente Editado");
        form.setPacienteRgct("RGCT-NOVO");
        form.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        form.setPacienteCpf("11144477735");
        form.setPacienteSexo(Sexo.MASCULINO);
        form.setSolicitanteEquipe("Equipe Nova");
        form.setSolicitanteEmail("editado@example.com");
        form.setDataSituacaoEspecial(LocalDate.now().plusDays(5));
        form.setObservacoes("Observacao editada");

        service.atualizarDados(id, form);

        Processo doBanco = repo.findById(id).orElseThrow();
        assertThat(doBanco.getPacienteNome()).isEqualTo("Paciente Editado");
        assertThat(doBanco.getPacienteRgct()).isEqualTo("RGCT-NOVO");
        assertThat(doBanco.getSolicitanteEquipe()).isEqualTo("Equipe Nova");
        assertThat(doBanco.getSolicitanteEmail()).isEqualTo("editado@example.com");
        assertThat(doBanco.getDataSituacaoEspecial()).isEqualTo(LocalDate.now().plusDays(5));
        assertThat(doBanco.getObservacoes()).isEqualTo("Observacao editada");
        // Regra documentada no javadoc do metodo: numero nao muda por aqui.
        assertThat(doBanco.getNumero()).isEqualTo("10/2026");
    }

    /**
     * Guarda automatica: cada {@code th:field} de {@code processos/editar.html}
     * precisa chegar ao banco (ou estar explicitamente na lista de ignorados
     * de proposito). Um campo novo no HTML sem a copia correspondente no
     * service derruba este teste sem intervencao humana.
     */
    @Test
    void cadaCampoDoFormularioDeEdicaoChegaAoBanco() throws Exception {
        List<String> campos = CamposDeFormulario.thFields(FORM);
        assertThat(campos).contains("pacienteNome", "pacienteRgct", "solicitanteEquipe",
            "solicitanteEmail", "dataSituacaoEspecial", "observacoes");

        for (String campo : campos) {
            if (IGNORADOS_DE_PROPOSITO.contains(campo)) {
                continue;
            }
            Processo atual = repo.findById(id).orElseThrow();
            PropertyDescriptor pd = new PropertyDescriptor(campo, Processo.class);
            Object novoValor = CamposDeFormulario.valorDistinto(
                Processo.class, campo, pd.getPropertyType(), pd.getReadMethod().invoke(atual));

            Processo form = copiaEditavel(atual);
            pd.getWriteMethod().invoke(form, novoValor);

            service.atualizarDados(id, form);

            Object gravado = pd.getReadMethod().invoke(repo.findById(id).orElseThrow());
            assertThat(gravado)
                .as("campo '%s' aparece no formulario de edicao de processo mas nao foi gravado por "
                    + "ProcessoService.atualizarDados - escrita descartada em silencio", campo)
                .isEqualTo(novoValor);
        }
    }

    /**
     * <b>Regressao do HOTFIX de 2026-08-22 (producao quebrada):</b> um
     * {@code Processo} LEGADO - criado antes de
     * {@code pacienteDataNascimento}/{@code pacienteCpf}/{@code pacienteSexo}
     * existirem, portanto com os 3 campos NULL, exatamente como os 12
     * processos reais de producao no momento do incidente - precisa
     * continuar aceitando QUALQUER escrita que nao mexa nesses 3 campos, sem
     * lancar {@code ConstraintViolationException}/{@code
     * TransactionSystemException}. Antes da correcao, {@code @NotNull}/
     * {@code @NotBlank} na ENTIDADE faziam o Hibernate validar o
     * {@code Processo} INTEIRO a cada flush - mesmo um metodo que so grava
     * {@code dataEnvioSnt} (sem nenhuma relacao com paciente) quebrava com
     * 500 num processo legado.
     *
     * <p>Por que a suite nao pegou este bug antes do merge que introduziu os
     * 3 campos: todo teste existente cria um {@code Processo} de fixture ja
     * com esses campos preenchidos (ver {@code preparar()} desta propria
     * classe, por exemplo) - nenhum simulava um processo PRE-EXISTENTE sem
     * eles, que e exatamente o cenario real de producao (dado legado, nao
     * dado de teste criado do zero).</p>
     */
    @Test
    void processoLegadoComCamposDePacienteNulosAceitaQualquerOutraEscritaSemQuebrar() {
        Processo legado = new Processo();
        legado.setNumero("11/2026");
        legado.setAno(2026);
        legado.setSequencial(11);
        legado.setStatus(StatusProcesso.DEFERIDO);
        legado.setPacienteNome("Paciente Legado");
        legado.setPacienteRgct("RGCT-LEGADO");
        // Os 3 campos novos ficam NULL de proposito - simula um Processo
        // criado ANTES deles existirem (o cenario real dos 12 processos de
        // producao no incidente).
        legado.setPacienteDataNascimento(null);
        legado.setPacienteCpf(null);
        legado.setPacienteSexo(null);
        legado.setSolicitanteEquipe("Equipe Legada");
        legado.setSolicitanteEmail("legado@example.com");
        legado.setDataSituacaoEspecial(LocalDate.now().minusDays(30));
        Long idLegado = repo.saveAndFlush(legado).getId();

        // Escrita SEM NENHUMA relacao com os 3 campos de paciente - o mesmo
        // tipo de acao que quebrava em producao (finalizar resposta, decidir,
        // reabrir etc.): so grava a data do comprovante SNT.
        service.registrarDataEnvioSnt(idLegado);

        Processo doBanco = repo.findById(idLegado).orElseThrow();
        assertThat(doBanco.getDataEnvioSnt()).isEqualTo(LocalDate.now());
        // Os 3 campos continuam null - a escrita nao inventou dado nenhum.
        assertThat(doBanco.getPacienteDataNascimento()).isNull();
        assertThat(doBanco.getPacienteCpf()).isNull();
        assertThat(doBanco.getPacienteSexo()).isNull();
    }

    /** Cria um objeto "de formulario" com o estado editavel atual do registro. */
    private Processo copiaEditavel(Processo origem) {
        Processo p = new Processo();
        p.setPacienteNome(origem.getPacienteNome());
        p.setPacienteRgct(origem.getPacienteRgct());
        p.setPacienteDataNascimento(LocalDate.of(1985, 3, 15));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe(origem.getSolicitanteEquipe());
        p.setSolicitanteEmail(origem.getSolicitanteEmail());
        p.setDataSituacaoEspecial(origem.getDataSituacaoEspecial());
        p.setObservacoes(origem.getObservacoes());
        return p;
    }
}

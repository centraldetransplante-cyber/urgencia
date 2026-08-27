package br.gov.saude.sgpur.e2e;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.Sexo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.repository.MembroUrgenciaRenalRepository;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproducao visual (Playwright) do bug relatado pelo usuario em producao:
 * "Processo 11, problema de CSS, Status e Medico 1 estao se sobrescrevendo"
 * no Painel (dashboard.html).
 *
 * <p>Ja existe um comentario no proprio dashboard.html (por volta da linha
 * 190-203) documentando um bug QUASE IDENTICO ja corrigido antes (sticky-top
 * do cabecalho colidindo com overflow-y:auto de altura de linha variavel) -
 * o sticky-top ja foi removido, entao este teste busca uma causa NOVA,
 * testando os cenarios mais plausiveis de celula "Status" mais alta que o
 * normal (varios badges empilhados: encerramento + regra de decisao +
 * reaberturas + pendencia) tanto num processo comum quanto num preemptivo,
 * com pelo menos 1 medico com parecer emitido (celula "Medico 1" com badge
 * de resultado) ao lado.
 *
 * <p>So tira screenshots e mede posicoes reais dos elementos (bounding box) -
 * nao "concerta no escuro": a correcao real (se houver) so deve ser aplicada
 * depois de confirmar aqui uma sobreposicao de verdade.
 */
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sgpur-e2e-dashboard-visual;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DashboardStatusMedicoVisualIT extends PlaywrightTestBase {

    @Autowired
    private MembroUrgenciaRenalRepository membroRepository;
    @Autowired
    private ProcessoRepository processoRepository;

    private Processo processoBase(String numero, boolean preemptivo, MembroUrgenciaRenal m1,
                                   MembroUrgenciaRenal m2, MembroUrgenciaRenal m3) {
        int ano = Year.now().getValue();
        Processo p = new Processo();
        p.setNumero((preemptivo ? "P-" : "") + numero + "/" + ano);
        p.setAno(ano);
        p.setSequencial(0);
        p.setPreemptivo(preemptivo);
        p.setPacienteNome("Paciente Teste Dashboard Visual " + numero);
        p.setPacienteRgct(preemptivo ? null : "123456789-" + numero);
        p.setPacienteDataNascimento(LocalDate.of(1980, 1, 1));
        p.setPacienteCpf("11144477735");
        p.setPacienteSexo(Sexo.MASCULINO);
        p.setSolicitanteEquipe("Equipe Hospital de Clinicas de Porto Alegre - Nefrologia Pediatrica");
        p.setSolicitanteEmail("solicitante@example.com");
        p.setDataSituacaoEspecial(LocalDate.now());
        p.setDataCadastro(LocalDateTime.now());

        Parecer par1 = new Parecer(m1);
        par1.setResultado(ResultadoParecer.FAVORAVEL);
        par1.setDataEnvio(LocalDate.now().minusDays(5));
        par1.setDataResposta(LocalDate.now().minusDays(1));
        p.addParecer(par1);

        Parecer par2 = new Parecer(m2);
        par2.setDataEnvio(LocalDate.now().minusDays(5));
        p.addParecer(par2);

        Parecer par3 = new Parecer(m3);
        par3.setDataEnvio(LocalDate.now().minusDays(5));
        p.addParecer(par3);

        return p;
    }

    @Test
    void statusEMedico1NaoSeSobrepoemComVariosBadgesEmpilhados() {
        List<MembroUrgenciaRenal> medicos = membroRepository.findByAtivoTrueOrderByInstituicaoAsc();
        assertThat(medicos).hasSizeGreaterThanOrEqualTo(3);
        MembroUrgenciaRenal m1 = medicos.get(0);
        MembroUrgenciaRenal m2 = medicos.get(1);
        MembroUrgenciaRenal m3 = medicos.get(2);

        // Cenario 1: processo COMUM, indeferido, com o maximo de badges
        // empilhados na celula Status: badgeEncerramento ("Decisao tomada",
        // pois emailEnviadoSolicitante=false) + pendencia (oficio) - sem
        // regra excepcional/reaberturas aqui (indeferido nao passa pelo voto
        // do coordenador).
        Processo indeferido = processoBase("11", false, m1, m2, m3);
        indeferido.setStatus(StatusProcesso.INDEFERIDO);
        indeferido.setDataDecisao(LocalDateTime.now().minusDays(1));
        indeferido.setEmailEnviadoSolicitante(false);
        indeferido.setReaberturas(2);
        indeferido.getPareceres().get(1).setResultado(ResultadoParecer.NAO_FAVORAVEL);
        indeferido.getPareceres().get(1).setDataResposta(LocalDate.now().minusDays(1));
        indeferido.getPareceres().get(2).setResultado(ResultadoParecer.NAO_FAVORAVEL);
        indeferido.getPareceres().get(2).setDataResposta(LocalDate.now().minusDays(1));
        processoRepository.saveAndFlush(indeferido);

        // Cenario 2: processo PREEMPTIVO, deferido pelo voto unico do
        // coordenador (RegraDecisao.VOTO_COORDENADOR - badgeRegraDecisao
        // aparece) + reaberto (badgeReaberturas aparece junto).
        if (!m1.isCoordenador()) {
            m1.setCoordenador(true);
            membroRepository.saveAndFlush(m1);
        }
        Processo preemptivo = processoBase("12", true, m1, m2, m3);
        preemptivo.setStatus(StatusProcesso.DEFERIDO);
        preemptivo.setDataDecisao(LocalDateTime.now().minusHours(2));
        preemptivo.setEmailEnviadoSolicitante(false);
        preemptivo.setReaberturas(1);
        preemptivo.getPareceres().get(0).setEraCoordenadorNoVoto(true);
        processoRepository.saveAndFlush(preemptivo);

        login("admin", "Admin123!");
        assertThat(page.url()).doesNotContain("/login");
        page.navigate("/");
        page.waitForLoadState();

        Locator tabela = page.locator("table.table-hover");
        tabela.first().waitFor();

        List<String> numeros = List.of(indeferido.getNumero(), preemptivo.getNumero());

        // Viewport 1: desktop largo (1440x1080, padrao dos demais testes E2E).
        screenshot("dashboard-status-medico1-cenario-completo-1440x1080");
        conferirSemSobreposicao(numeros);

        // Viewport 2: notebook comum (1366x720) - MENOR altura que o
        // container .dashboard-tabela-scroll (max-height:70vh), forcando o
        // scroll interno a entrar em jogo de verdade (era exatamente essa
        // combinacao - overflow-y:auto + linha de altura variavel - que
        // causou o bug historico do sticky-top ja documentado no proprio
        // dashboard.html). Testa se a causa NOVA relatada pelo usuario
        // depende de uma janela mais baixa/realista de producao.
        page.setViewportSize(1366, 720);
        page.waitForTimeout(300);
        screenshot("dashboard-status-medico1-cenario-completo-1366x720");
        conferirSemSobreposicao(numeros);

        // Viewport 3: notebook pequeno (1024x768), estressa ainda mais o
        // wrap de texto nas celulas (Status/Medico1 mais estreitas).
        page.setViewportSize(1024, 768);
        page.waitForTimeout(300);
        screenshot("dashboard-status-medico1-cenario-completo-1024x768");
        conferirSemSobreposicao(numeros);
    }

    /**
     * Mede a posicao real das celulas Status e Medico1 de cada linha
     * informada e falha explicitamente se elas se sobrepuserem (mesma
     * logica de deteccao usada por um humano olhando a tela: duas celulas
     * da MESMA linha nunca podem ocupar a mesma faixa vertical de forma
     * invertida/cruzada, nem uma invadir horizontalmente a area da outra).
     */
    private void conferirSemSobreposicao(List<String> numerosDeProcesso) {
        for (String numeroProcesso : numerosDeProcesso) {
            Locator linha = page.locator("tr:has(td a:text-is(\"" + numeroProcesso + "\"))");
            linha.first().waitFor();
            Locator celulaStatus = linha.first().locator("td").nth(2);
            Locator celulaMedico1 = linha.first().locator("td").nth(3);

            BoundingBox boxStatus = celulaStatus.boundingBox();
            BoundingBox boxMedico1 = celulaMedico1.boundingBox();
            assertThat(boxStatus).as("bounding box da celula Status do processo " + numeroProcesso).isNotNull();
            assertThat(boxMedico1).as("bounding box da celula Medico 1 do processo " + numeroProcesso).isNotNull();

            // As duas celulas sao da MESMA <tr> - devem estar na MESMA faixa
            // vertical (mesmo "y"/altura), nunca uma sobre a outra deslocada.
            // Overlap horizontal (colunas adjacentes) e ESPERADO tocarem a
            // borda uma da outra; o que indicaria o bug relatado e um "y"
            // (topo) muito diferente entre as duas, ou texto de uma
            // extrapolando para dentro da area x da outra.
            double diferencaTopo = Math.abs(boxStatus.y - boxMedico1.y);
            assertThat(diferencaTopo)
                .as("diferenca de topo (y) entre Status e Medico 1 na mesma linha do processo " + numeroProcesso
                    + " - deveria ser ~0 (mesma <tr>); um valor grande indica selo/badge vazando de posicao")
                .isLessThan(5.0);

            // As celulas nao podem se sobrepor horizontalmente (Status
            // terminando DEPOIS do inicio de Medico 1).
            assertThat(boxStatus.x + boxStatus.width)
                .as("borda direita da celula Status nao deve invadir a celula Medico 1 (processo " + numeroProcesso + ")")
                .isLessThanOrEqualTo(boxMedico1.x + 1.0);
        }
    }
}

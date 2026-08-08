package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do RelatorioAvaliadorService: PDF do relatorio individual do
 * avaliador, filtrando so os pareceres RESPONDIDOS do membro pedido (mesmo
 * padrao do RelatorioAnualServiceTest: parecerRepo=null e seguro pois so
 * TempoRespostaService.calcularDe/detalharDe(List) sao usados).
 */
class RelatorioAvaliadorServiceTest {

    private final RelatorioAvaliadorService service =
        new RelatorioAvaliadorService(new TempoRespostaService(null, 7));

    private MembroUrgenciaRenal membro(Long id) {
        MembroUrgenciaRenal m = new MembroUrgenciaRenal("HCPA", "Dra. Ana", null);
        m.setId(id);
        return m;
    }

    private Processo processo(String numero, MembroUrgenciaRenal membro, ResultadoParecer resultado,
                              LocalDate envio, LocalDate resposta) {
        Processo p = new Processo();
        p.setNumero(numero);
        p.setPacienteNome("Paciente " + numero);
        p.setStatus(StatusProcesso.DEFERIDO);
        Parecer par = new Parecer(membro);
        par.setResultado(resultado);
        par.setDataEnvio(envio);
        par.setDataResposta(resposta);
        p.addParecer(par);
        return p;
    }

    @Test
    void geraPdfNaoVazioSemProcessosAvaliados() {
        MembroUrgenciaRenal m = membro(1L);

        byte[] pdf = service.gerar(2026, m, List.of());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void geraPdfComProcessosDoMembroEIgnoraDeOutrosMembros() throws Exception {
        MembroUrgenciaRenal alvo = membro(1L);
        MembroUrgenciaRenal outro = membro(2L);

        Processo doAlvo = processo("01/2026", alvo, ResultadoParecer.FAVORAVEL,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 4));
        Processo deOutro = processo("02/2026", outro, ResultadoParecer.FAVORAVEL,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2));

        byte[] pdf = service.gerar(2026, alvo, List.of(doAlvo, deOutro));

        PdfReader reader = new PdfReader(pdf);
        StringBuilder texto = new StringBuilder();
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            texto.append(new PdfTextExtractor(reader).getTextFromPage(i));
        }
        reader.close();

        assertThat(texto.toString())
            .contains("HCPA - Dra. Ana")
            .contains("01/2026")
            .doesNotContain("02/2026");
    }

    @Test
    void ignoraPareceresSemVotoOuSemDatasCompletas() {
        MembroUrgenciaRenal m = membro(1L);
        Processo pendente = processo("03/2026", m, null, null, null);

        byte[] pdf = service.gerar(2026, m, List.of(pendente));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void pagina2TemCabecalhoInstitucionalComNomeDoMembro() throws Exception {
        MembroUrgenciaRenal m = membro(1L);

        byte[] pdf = service.gerar(2026, m, List.of());

        PdfReader reader = new PdfReader(pdf);
        assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(2);
        String pagina2 = new PdfTextExtractor(reader).getTextFromPage(2);
        reader.close();

        assertThat(pagina2)
            .contains("Central de Transplantes do Estado do Rio Grande do Sul - URGÊNCIA RENAL")
            .contains("Relatório do Avaliador - HCPA - Dra. Ana - Ano 2026");
    }
}

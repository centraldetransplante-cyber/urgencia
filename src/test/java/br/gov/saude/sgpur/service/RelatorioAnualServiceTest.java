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

class RelatorioAnualServiceTest {

    // parecerRepo=null e seguro aqui: RelatorioAnualService so chama
    // TempoRespostaService.calcularDe(List), que nao usa o repositorio
    // (esse e usado apenas por calcular()/findRespondidosComDatas()).
    private final RelatorioAnualService service =
        new RelatorioAnualService(new TempoRespostaService(null, 7));

    private Processo processo(String numero, int sequencial, StatusProcesso status) {
        Processo p = new Processo();
        p.setNumero(numero);
        p.setAno(2026);
        p.setSequencial(sequencial);
        p.setPacienteNome("Paciente " + sequencial);
        p.setPacienteRgct("RGCT-" + sequencial);
        p.setSolicitanteEquipe("Hospital X");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 3, 1));
        p.setStatus(status);
        Parecer par = new Parecer(new MembroUrgenciaRenal("HCPA", "Dr. " + sequencial, null));
        par.setResultado(ResultadoParecer.FAVORAVEL);
        p.addParecer(par);
        return p;
    }

    @Test
    void geraPdfNaoVazioParaAnoComProcessos() {
        List<Processo> processos = List.of(
            processo("01/2026", 1, StatusProcesso.DEFERIDO),
            processo("02/2026", 2, StatusProcesso.INDEFERIDO),
            processo("03/2026", 3, StatusProcesso.ENVIADO));

        byte[] pdf = service.gerar(2026, processos);

        assertThat(pdf).isNotEmpty();
        // assinatura de arquivo PDF: "%PDF"
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void geraPdfMesmoSemProcessosNoAno() {
        byte[] pdf = service.gerar(2030, List.of());
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void pagina2TemCabecalhoInstitucionalRepetido() throws Exception {
        List<Processo> processos = List.of(
            processo("01/2026", 1, StatusProcesso.DEFERIDO),
            processo("02/2026", 2, StatusProcesso.INDEFERIDO));

        byte[] pdf = service.gerar(2026, processos);

        PdfReader reader = new PdfReader(pdf);
        assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(2);
        String pagina2 = new PdfTextExtractor(reader).getTextFromPage(2);
        assertThat(pagina2)
            .contains("Central de Transplantes do Estado do Rio Grande do Sul - URGÊNCIA RENAL")
            .contains("Relatório Geral de Urgência Renal - Ano 2026");
        reader.close();
    }

    @Test
    void resumoContaTempoDeRespostaDoAno() throws Exception {
        MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Dr. Teste", null);
        membro.setId(1L);
        Parecer par = new Parecer(membro);
        par.setResultado(ResultadoParecer.FAVORAVEL);
        par.setDataEnvio(LocalDate.of(2026, 1, 1));
        par.setDataResposta(LocalDate.of(2026, 1, 4)); // 3 dias

        Processo p = new Processo();
        p.setNumero("01/2026");
        p.setAno(2026);
        p.setSequencial(1);
        p.setPacienteNome("Paciente Teste");
        p.setPacienteRgct("RGCT-1");
        p.setSolicitanteEquipe("Hospital X");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 1, 1));
        p.setStatus(StatusProcesso.DEFERIDO);
        p.addParecer(par);

        byte[] pdf = service.gerar(2026, List.of(p));

        PdfReader reader = new PdfReader(pdf);
        String textoCompleto = new StringBuilder()
            .append(new PdfTextExtractor(reader).getTextFromPage(2))
            .append(reader.getNumberOfPages() >= 3
                ? new PdfTextExtractor(reader).getTextFromPage(3) : "")
            .toString();
        assertThat(textoCompleto)
            .contains("Tempo médio de resposta dos avaliadores")
            .contains("3 dias")
            .contains("Tempo de resposta por avaliador")
            .contains("Dr. Teste");
        reader.close();
    }

    @Test
    void resumoTemLinhaDePreemptivosComAContagemCerta() throws Exception {
        Processo comum = processo("01/2026", 1, StatusProcesso.DEFERIDO);
        Processo preempt1 = processo("P-01/2026", 1, StatusProcesso.DEFERIDO);
        preempt1.setPreemptivo(true);
        Processo preempt2 = processo("P-02/2026", 2, StatusProcesso.ENVIADO);
        preempt2.setPreemptivo(true);

        byte[] pdf = service.gerar(2026, List.of(comum, preempt1, preempt2));

        PdfReader reader = new PdfReader(pdf);
        // Normaliza a quebra de linha que o extrator de PDF insere no meio das
        // celulas da tabela (ex.: "... 1\n deferido(s)").
        String texto = (new PdfTextExtractor(reader).getTextFromPage(1)
                + " " + new PdfTextExtractor(reader).getTextFromPage(2))
            .replaceAll("\\s+", " ");
        assertThat(texto)
            .contains("Preemptivos (inserção em lista de espera)")
            .contains("2 (dos quais 1 deferido(s))")
            .contains("(2 preemptivo(s))"); // capa
        reader.close();
    }
}

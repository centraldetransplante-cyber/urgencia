package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.config.EmailProperties;
import br.gov.saude.sgpur.domain.Processo;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do OficioService - gera o Oficio de Indeferimento em PDF. Como o
 * documento e curto e a "logica" real esta toda no texto embutido (motivo
 * com fallback, data de emissao com fallback, numero/paciente/solicitante),
 * os testes extraem o texto da pagina via PdfTextExtractor (mesmo padrao de
 * RelatorioAnualServiceTest/SolicitacaoAvaliadorServiceTest) em vez de so
 * checar que os bytes nao estao vazios.
 */
class OficioServiceTest {

    private final EmailProperties emailProperties = emailProperties();

    private final OficioService service = new OficioService(emailProperties);

    private static EmailProperties emailProperties() {
        EmailProperties props = new EmailProperties();
        props.setOficioCidade("Porto Alegre");
        props.setAssinatura("Fulana Coordenadora - Divisao de Transplantes / SES-RS");
        return props;
    }

    private Processo processoCompleto() {
        Processo p = new Processo();
        p.setNumero("07/2026");
        p.setPacienteNome("Fulano de Tal");
        p.setSolicitanteEquipe("Hospital de Clinicas");
        p.setMotivoIndeferimento("Ausencia de indicacao clinica para urgencia renal.");
        p.setDataEmissaoOficio(LocalDate.of(2026, 3, 15));
        p.setNumeroOficio("1398/2026");
        return p;
    }

    private String textoDaPagina1(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        } finally {
            reader.close();
        }
    }

    @Test
    void geraPdfNaoVazioComAssinaturaPdf() {
        byte[] pdf = service.gerar(processoCompleto());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void corpoContemNumeroPacienteESolicitante() throws Exception {
        Processo p = processoCompleto();

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto)
            .contains("INDEFERIMENTO")
            .contains(p.identificacao())
            .contains("Processo de Urgência Renal n. 07/2026")
            .contains("Fulano de Tal")
            .contains("solicitante: Hospital de Clinicas")
            .contains("INDEFERIDO");
    }

    @Test
    void corpoContemMotivoInformado() throws Exception {
        Processo p = processoCompleto();

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).contains("Motivo do indeferimento: Ausencia de indicacao clinica para urgencia renal.");
    }

    @Test
    void usaPlaceholderQuandoMotivoNulo() throws Exception {
        Processo p = processoCompleto();
        p.setMotivoIndeferimento(null);

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).contains("Motivo do indeferimento: (motivo não informado)");
    }

    @Test
    void usaPlaceholderQuandoMotivoEmBranco() throws Exception {
        Processo p = processoCompleto();
        p.setMotivoIndeferimento("   ");

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).contains("(motivo não informado)");
    }

    @Test
    void usaDataEmissaoOficioQuandoPreenchida() throws Exception {
        Processo p = processoCompleto();
        p.setDataEmissaoOficio(LocalDate.of(2026, 1, 5));

        String texto = textoDaPagina1(service.gerar(p));

        // 5 de janeiro de 2026, por extenso em pt-BR
        assertThat(texto).contains("5 de janeiro de 2026");
    }

    @Test
    void caiParaDataDeHojeQuandoDataEmissaoOficioNaoPreenchida() throws Exception {
        Processo p = processoCompleto();
        p.setDataEmissaoOficio(null);
        LocalDate hoje = LocalDate.now();

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).contains(String.valueOf(hoje.getYear()));
        assertThat(texto).contains(String.valueOf(hoje.getDayOfMonth()));
    }

    @Test
    void identificacaoOmiteRgctQuandoNaoPreenchido() throws Exception {
        Processo p = processoCompleto();
        p.setPacienteRgct(null);

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).doesNotContain("RGCT");
        assertThat(texto).contains("07/2026 - Fulano de Tal");
    }

    @Test
    void identificacaoIncluiRgctQuandoPreenchido() throws Exception {
        Processo p = processoCompleto();
        p.setPacienteRgct("123456789");

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).contains("RGCT 123456789");
    }

    @Test
    void naoLancaExcecaoComProcessoMinimoSemNomeOuEquipe() {
        // Processo sem paciente/solicitante/motivo preenchidos: OficioService
        // nao valida campos obrigatorios antes de gerar - so usa fallback
        // (motivo) ou embute o que houver (mesmo que "null" no texto). O
        // servico nao deve lancar excecao nesse cenario.
        Processo p = new Processo();
        p.setNumero("01/2026");

        byte[] pdf = service.gerar(p);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void cabecalhoInstitucionalPresente() throws Exception {
        String texto = textoDaPagina1(service.gerar(processoCompleto()));

        assertThat(texto)
            .contains("Central de Transplantes do Estado do Rio Grande do Sul")
            .contains("URGÊNCIA RENAL");
    }

    // ----- placeholders eliminados (cidade e assinatura configuraveis) -----

    @Test
    void naoImprimeMaisOPlaceholderLocalEUsaACidadeConfigurada() throws Exception {
        String texto = textoDaPagina1(service.gerar(processoCompleto()));

        assertThat(texto).doesNotContain("Local,");
        assertThat(texto).contains("Porto Alegre, 15 de março de 2026.");
    }

    @Test
    void usaACidadeConfiguradaQuandoDiferenteDoPadrao() throws Exception {
        EmailProperties props = emailProperties();
        props.setOficioCidade("Santa Maria");

        String texto = textoDaPagina1(new OficioService(props).gerar(processoCompleto()));

        assertThat(texto).contains("Santa Maria, 15 de março de 2026.");
    }

    @Test
    void caiParaPortoAlegreQuandoCidadeConfiguradaEmBranco() throws Exception {
        EmailProperties props = emailProperties();
        props.setOficioCidade("  ");

        String texto = textoDaPagina1(new OficioService(props).gerar(processoCompleto()));

        assertThat(texto).doesNotContain("Local,");
        assertThat(texto).contains("Porto Alegre, 15 de março de 2026.");
    }

    @Test
    void assinaturaVemDaConfiguracaoENaoDoPlaceholderFixo() throws Exception {
        String texto = textoDaPagina1(service.gerar(processoCompleto()));

        assertThat(texto).contains("Fulana Coordenadora - Divisao de Transplantes / SES-RS");
        // O placeholder antigo ("Responsavel - Equipe de Urgencia Renal /
        // Secretaria de Saude", escrito no codigo) nao existe mais.
        assertThat(texto).doesNotContain("Responsavel - Equipe de Urgencia Renal");
    }

    // ----- numeracao propria do oficio -----

    @Test
    void tituloTrazONumeroProprioDoOficio() throws Exception {
        String texto = textoDaPagina1(service.gerar(processoCompleto()));

        assertThat(texto).contains("Ofício nº 1398/2026");
    }

    @Test
    void tituloUsaFallbackQuandoProcessoAntigoNaoTemNumeroDeOficio() throws Exception {
        Processo p = processoCompleto();
        p.setNumeroOficio(null);

        String texto = textoDaPagina1(service.gerar(p));

        assertThat(texto).contains("(número não atribuído)");
    }

    // ----- rascunho editavel (.rtf) -----

    private String rascunho(Processo p) {
        return new String(service.gerarRascunhoRtf(p), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /**
     * Desde 2026-08-04 o oficio e sempre anexado pelo operador; o sistema so
     * oferece este rascunho para editar no Word. Os dados do processo precisam
     * chegar prontos, senao o rascunho nao poupa trabalho nenhum.
     */
    @Test
    void rascunhoTrazNumeroDataPacienteMotivoEAssinatura() {
        String rtf = rascunho(processoCompleto());

        assertThat(rtf).startsWith("{\\rtf1");
        assertThat(rtf).endsWith("}");
        assertThat(rtf).contains("1398/2026");
        assertThat(rtf).contains("07/2026");
        assertThat(rtf).contains("Fulano de Tal");
        assertThat(rtf).contains("Ausencia de indicacao clinica para urgencia renal.");
        assertThat(rtf).contains("Porto Alegre, 15 de mar\\'e7o de 2026.");
        assertThat(rtf).contains("Fulana Coordenadora");
        assertThat(rtf).contains("Hospital de Clinicas");
    }

    /**
     * Acentuacao vira \\'hh (o code page declarado no cabecalho do RTF); sem
     * isso o Word mostra caractere trocado no nome do paciente e no motivo -
     * texto que vai num documento oficial.
     */
    @Test
    void rascunhoEscapaAcentuacaoNoFormatoQueOWordEntende() {
        Processo p = processoCompleto();
        p.setPacienteNome("João Conceição");

        String rtf = rascunho(p);

        assertThat(rtf).contains("Jo\\'e3o Concei\\'e7\\'e3o");
        assertThat(rtf).doesNotContain("João");
    }

    /**
     * Chave e barra invertida sao caracteres de CONTROLE do RTF: um motivo de
     * indeferimento que contenha "{" corromperia o documento inteiro (o Word
     * abre em branco ou com o texto truncado) se entrasse cru.
     */
    @Test
    void rascunhoEscapaCaracteresDeControleDoRtf() {
        Processo p = processoCompleto();
        p.setMotivoIndeferimento("Criterio {A\\B} nao atendido");

        String rtf = rascunho(p);

        assertThat(rtf).contains("Criterio \\{A\\\\B\\} nao atendido");
    }

    @Test
    void rascunhoUsaFallbacksQuandoOProcessoAindaNaoTemNumeroDeOficioNemMotivo() {
        Processo p = processoCompleto();
        p.setNumeroOficio(null);
        p.setMotivoIndeferimento(null);
        p.setDataEmissaoOficio(null);

        String rtf = rascunho(p);

        // Os fallbacks (numero e motivo) sao acentuados desde a correcao de
        // 2026-08-08 - comparar contra o texto RTF CRU exige o mesmo escape
        // \'hh que escaparRtf produz, entao decodifica antes de comparar.
        assertThat(desescapaRtf(rtf)).contains(OficioService.NUMERO_NAO_ATRIBUIDO);
        assertThat(desescapaRtf(rtf)).contains("(motivo não informado)");
        assertThat(rtf).contains(String.valueOf(LocalDate.now().getYear()));
    }

    /**
     * Todos os literais fixos do rascunho (departamento, "Ofício nº",
     * corpo, fecho) precisam sair acentuados - documento oficial que vai ao
     * Word do operador. Corrigido em 2026-08-08 (achado numa simulacao real
     * de QA: o rascunho estava sem nenhum acento, ao contrario do PDF).
     */
    @Test
    void rascunhoTemAcentuacaoCorretaNosTextosFixos() {
        String rtf = desescapaRtf(rascunho(processoCompleto()));

        assertThat(rtf)
            .contains("Departamento de Regulação Estadual")
            .contains("Divisão de Transplantes")
            .contains("Ofício nº 1398/2026")
            .contains("Em referência ao Processo de Urgência Renal n.")
            .contains("comunicamos que, após análise dos pareceres da equipe de Urgência Renal")
            .contains("Permanecemos à disposição para os esclarecimentos que se fizerem necessários.")
            .contains("À equipe solicitante");
    }

    /**
     * Reverte o escape \'hh (ISO-8859-1/cp1252, ver {@link OficioService#escaparRtf})
     * de volta para os caracteres originais, para permitir asserts com
     * acentuacao legivel sobre o texto RTF cru gerado pelo servico.
     */
    private static String desescapaRtf(String rtf) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rtf.length(); i++) {
            if (rtf.charAt(i) == '\\' && i + 3 < rtf.length() && rtf.charAt(i + 1) == '\'') {
                String hex = rtf.substring(i + 2, i + 4);
                out.append((char) Integer.parseInt(hex, 16));
                i += 3;
            } else {
                out.append(rtf.charAt(i));
            }
        }
        return out.toString();
    }
}

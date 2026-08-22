package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.config.EmailProperties;
import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateServiceTest {

    private final EmailTemplateService service = new EmailTemplateService(new EmailProperties());

    /** CPF valido (modulo-11), mesmo usado em CpfUtilTest. */
    private static final String CPF_VALIDO = "11144477735";
    private static final String CPF_FORMATADO = "111.444.777-35";

    private Processo processo() {
        Processo p = new Processo();
        p.setNumero("07/2026");
        p.setPacienteNome("Joao Paciente Secreto");
        p.setPacienteRgct("123456-4360");
        p.setSolicitanteEquipe("Hospital Solicitante");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
        p.setPacienteCpf(CPF_VALIDO);
        p.setPacienteDataNascimento(LocalDate.of(1990, 3, 15));
        p.addParecer(new Parecer(new MembroUrgenciaRenal("HCPA", "Dr. Avaliador", null)));
        return p;
    }

    @Test
    void deferidoGeraEmailDeRespostaAoSolicitante() {
        Processo p = processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        boolean temDeferido = service.gerar(p).stream().anyMatch(e -> e.chave().equals("deferido"));
        assertThat(temDeferido).isTrue();
    }

    @Test
    void emailDeferidoMencionaComprovanteSntEmAnexo() {
        Processo p = processo();
        p.setStatus(StatusProcesso.DEFERIDO);
        EmailTemplate deferido = service.gerar(p).stream()
            .filter(e -> e.chave().equals("deferido")).findFirst().orElseThrow();
        assertThat(deferido.corpo()).contains("Segue em anexo");
        assertThat(deferido.corpo()).contains("Sistema Nacional de Transplantes");
    }

    @Test
    void lembreteDeComprovanteSntEDirigidoAEquipeInternaEExplicaOBloqueio() {
        Processo p = processo();
        p.setId(42L);
        p.setStatus(StatusProcesso.DEFERIDO);

        EmailTemplate lembrete = service.emailLembreteComprovanteSnt(p, 12);

        assertThat(lembrete.chave()).isEqualTo("lembrete-comprovante-snt");
        assertThat(lembrete.assunto()).contains("07/2026");
        // Destinatario e a equipe INTERNA (ADMIN/OPERADOR), nao os avaliadores:
        // aqui o nome completo do paciente e legitimo.
        assertThat(lembrete.corpo()).contains("Joao Paciente Secreto");
        assertThat(lembrete.corpo()).contains("12 dia(s)");
        assertThat(lembrete.corpo()).contains("bloqueada");
        assertThat(lembrete.corpo()).contains("/processos/42#finalizacao");
    }

    @Test
    void emailSolicitaInfoLevaNomeCompletoAoSolicitante() {
        Processo p = processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        EmailTemplate info = service.gerar(p).stream()
            .filter(e -> e.chave().equals("solicita-info")).findFirst().orElseThrow();
        // E-mail dirigido a EQUIPE SOLICITANTE: DEVE conter o nome completo do paciente
        assertThat(info.corpo()).contains("Joao Paciente Secreto");
        assertThat(info.assunto()).contains("Joao Paciente Secreto");
        assertThat(info.corpo()).contains("07/2026");
        // 2026-08-22: bloco de identificacao tambem leva CPF e data de nascimento
        assertThat(info.corpo()).contains("CPF: " + CPF_FORMATADO);
        assertThat(info.corpo()).contains("Data de nascimento: 15/03/1990");
    }

    /**
     * Mesmo bug corrigido no Portal do Solicitante (2026-08): o e-mail dizia
     * apenas que "solicitou informações complementares", sem nunca dizer O QUE
     * foi pedido — quem acompanha so por e-mail ficava sem saber o que enviar.
     * A justificativa do parecer e obrigatoria nesse voto desde 2026-08-03.
     */
    @Test
    void emailSolicitaInfoIncluiOQueOAvaliadorPediuSemCitarOMedico() {
        Processo p = processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        Parecer par = p.getPareceres().get(0);
        par.setResultado(ResultadoParecer.SOLICITA_INFORMACAO);
        par.setJustificativa("Enviar o laudo da biópsia renal e a creatinina dos últimos 30 dias.");

        EmailTemplate info = service.gerar(p).stream()
            .filter(e -> e.chave().equals("solicita-info")).findFirst().orElseThrow();

        assertThat(info.corpo()).contains("O que foi pedido:");
        assertThat(info.corpo())
            .contains("Enviar o laudo da biópsia renal e a creatinina dos últimos 30 dias.");
        // Imparcialidade: conteudo do pedido sim, autoria nunca.
        assertThat(info.corpo()).doesNotContain("Dr. Avaliador");
        assertThat(info.corpo()).doesNotContain("HCPA");
    }

    /** Parecer legado, sem justificativa: o e-mail continua valido, so sem o bloco. */
    @Test
    void emailSolicitaInfoSemJustificativaNaoQuebraNemInventaBloco() {
        Processo p = processo();
        p.setStatus(StatusProcesso.SOLICITA_INFORMACAO);
        p.getPareceres().get(0).setResultado(ResultadoParecer.SOLICITA_INFORMACAO);

        EmailTemplate info = service.gerar(p).stream()
            .filter(e -> e.chave().equals("solicita-info")).findFirst().orElseThrow();

        assertThat(info.corpo()).doesNotContain("O que foi pedido");
        assertThat(info.corpo()).contains("solicitou informações complementares");
    }

    @Test
    void emAnaliseNaoGeraEmailDeResposta() {
        Processo p = processo(); // sem decisao (status nulo) por padrao
        p.getPareceres().forEach(par -> par.setDataEnvio(LocalDate.now()));
        long respostas = service.gerar(p).stream()
            .filter(e -> e.chave().equals("deferido") || e.chave().equals("indeferido")).count();
        assertThat(respostas).isZero();
    }

    @Test
    void lembreteAvaliadorNaoExpoeNomeDoPacienteEAvisaSobreAvaliacaoPendente() {
        Processo p = processo();
        MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Dra. Avaliadora", "avaliadora@example.com");
        EmailTemplate lembrete = service.emailLembreteAvaliador(p, membro);

        // Imparcialidade: nome completo do paciente NUNCA aparece no lembrete ao avaliador
        assertThat(lembrete.corpo()).doesNotContain("Joao Paciente Secreto");
        // 2026-08-22: CPF/data de nascimento sao ainda mais identificadores que o
        // nome - jamais podem chegar a um template de avaliador.
        assertThat(lembrete.corpo()).doesNotContain(CPF_VALIDO);
        assertThat(lembrete.corpo()).doesNotContain(CPF_FORMATADO);
        assertThat(lembrete.corpo()).doesNotContain("15/03/1990");
        // Deve conter o numero do processo, o texto de disponibilidade para avaliacao
        // e o nome do avaliador destinatario
        assertThat(lembrete.corpo()).contains("07/2026");
        assertThat(lembrete.corpo()).contains("permanece disponível para a sua");
        assertThat(lembrete.corpo()).contains("Dra. Avaliadora");
        assertThat(lembrete.assunto()).contains("07/2026");
    }

    // ===================================================================
    // Regras de redacao dos e-mails (2026-08-11)
    //
    // Motivacao: o dono do produto recebeu um e-mail real de deferimento e
    // relatou que a "formatacao esta ridicula" - sem acentuacao, com palavras
    // em CAIXA ALTA no meio da frase ("foi DEFERIDO", "Segue EM ANEXO") e com
    // "Equipe solicitante: X" solta entre dois paragrafos de prosa. Estes
    // testes travam as 3 regras adotadas, para nao recair. Ver o javadoc de
    // EmailTemplateService.
    // ===================================================================

    /** Todos os templates gerados por processo, para varrer de uma vez so. */
    private java.util.List<EmailTemplate> todosOsTemplates() {
        Processo enviado = processo();
        enviado.setStatus(StatusProcesso.ENVIADO);
        enviado.getPareceres().forEach(par -> par.setDataEnvio(LocalDate.now()));

        Processo deferido = processo();
        deferido.setStatus(StatusProcesso.DEFERIDO);

        Processo indeferido = processo();
        indeferido.setStatus(StatusProcesso.INDEFERIDO);
        // Texto acentuado de proposito: e DADO do processo (digitado pelo operador),
        // nao literal do template - a varredura de acentuacao abaixo nao deve
        // acusar o que veio de fora.
        indeferido.setMotivoIndeferimento("Critérios clínicos não atendidos.");

        Processo info = processo();
        info.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        Processo snt = processo();
        snt.setId(42L);
        snt.setStatus(StatusProcesso.DEFERIDO);

        MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Dra. Avaliadora", "a@example.com");

        java.util.List<EmailTemplate> todos = new java.util.ArrayList<>();
        todos.addAll(service.gerar(enviado));
        todos.addAll(service.gerar(deferido));
        todos.addAll(service.gerar(indeferido));
        todos.addAll(service.gerar(info));
        todos.add(service.emailConviteAvaliador(deferido, membro));
        todos.add(service.emailCancelamentoAvaliador(deferido, membro));
        todos.add(service.emailLembreteAvaliador(deferido, membro));
        todos.add(service.emailLembreteComprovanteSnt(snt, 12));
        return todos;
    }

    /**
     * Regra 2: nenhum template pode ter palavra inteiramente em CAIXA ALTA no meio
     * de uma frase. O e-mail e enviado em TEXTO PURO (EmailSenderService usa
     * {@code setText(body, false)}), entao caixa alta nao vira enfase - so parece
     * grito. Siglas legitimas (SNT, CET-RS, PDF...) sao permitidas via allowlist.
     */
    @Test
    void nenhumTemplateUsaCaixaAltaComoEnfaseNoMeioDaFrase() {
        java.util.Set<String> siglasPermitidas = java.util.Set.of(
            "SNT", "CET", "RS", "SAUR", "SES", "PDF", "RGCT", "HCPA", "ISCMPA", "CPF");
        var regex = java.util.regex.Pattern.compile("\\b\\p{Lu}{2,}\\b");

        for (EmailTemplate t : todosOsTemplates()) {
            var m = regex.matcher(t.corpo() + "\n" + t.assunto());
            while (m.find()) {
                assertThat(siglasPermitidas)
                    .as("Palavra em caixa alta '%s' no template '%s' - use caixa normal "
                        + "(o e-mail e texto puro, caixa alta nao e enfase)", m.group(), t.chave())
                    .contains(m.group());
            }
        }
    }

    /**
     * Regra 1: acentuacao. Varre palavras que so existem acentuadas em portugues -
     * se alguma aparecer sem acento, algum literal escapou da correcao.
     */
    @Test
    void nenhumTemplateTemPalavraSemAcentuacao() {
        String[] semAcento = {
            "Urgencia", "urgencia", "disposicao", "Saude", "avaliacao", "informacoes",
            "analise", "decisao", "oficio", "insercao", "solicitacao", "Voce", "Voces",
            "nao ", "ja ", "esta disponivel", "necessarios", "Finalizacao", "Ola,",
            "imparcialidade do julgamento; identificado"
        };
        for (EmailTemplate t : todosOsTemplates()) {
            for (String termo : semAcento) {
                assertThat(t.corpo() + "\n" + t.assunto() + "\n" + t.titulo())
                    .as("Template '%s' contem o termo sem acentuacao '%s'", t.chave(), termo)
                    .doesNotContain(termo);
            }
        }
    }

    /**
     * Regra 3: nos e-mails dirigidos a EQUIPE SOLICITANTE, os dados de
     * identificacao vem num bloco no inicio (antes da prosa), e nao como uma linha
     * de dado solta no meio dos paragrafos - que era exatamente o defeito relatado
     * ("Equipe solicitante: Santa Casa" entre dois paragrafos corridos).
     */
    @Test
    void emailsAoSolicitanteComecamComOBlocoDeIdentificacao() {
        Processo deferido = processo();
        deferido.setStatus(StatusProcesso.DEFERIDO);
        Processo indeferido = processo();
        indeferido.setStatus(StatusProcesso.INDEFERIDO);
        Processo info = processo();
        info.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        for (Processo p : java.util.List.of(deferido, indeferido, info)) {
            EmailTemplate t = service.gerar(p).get(0);
            String corpo = t.corpo();
            int bloco = corpo.indexOf("Processo: 07/2026");
            int paciente = corpo.indexOf("Paciente: Joao Paciente Secreto");
            int cpf = corpo.indexOf("CPF: " + CPF_FORMATADO);
            int nascimento = corpo.indexOf("Data de nascimento: 15/03/1990");
            int equipe = corpo.indexOf("Equipe solicitante: Hospital Solicitante");
            int prosa = corpo.indexOf("Informamos que o processo acima");
            if (prosa < 0) {
                prosa = corpo.indexOf("Durante a análise do processo acima");
            }

            assertThat(bloco).as("bloco de identificacao ausente em '%s'", t.chave()).isNotNegative();
            assertThat(paciente).isGreaterThan(bloco);
            // 2026-08-22: CPF e data de nascimento tambem fazem parte do bloco,
            // entre o paciente e a equipe solicitante.
            assertThat(cpf).as("CPF ausente do bloco de identificacao em '%s'", t.chave()).isGreaterThan(paciente);
            assertThat(nascimento)
                .as("data de nascimento ausente do bloco de identificacao em '%s'", t.chave())
                .isGreaterThan(cpf);
            assertThat(equipe).isGreaterThan(nascimento);
            assertThat(prosa)
                .as("a prosa deve vir DEPOIS do bloco de identificacao em '%s'", t.chave())
                .isGreaterThan(equipe);
        }
    }

    // ===================================================================
    // CPF e data de nascimento do paciente (2026-08-22)
    //
    // Item pedido explicitamente pelo dono do produto: os campos novos de
    // identificacao do paciente (CPF, data de nascimento, sexo, nome da mae)
    // podem melhorar o bloco de identificacao dos e-mails a EQUIPE
    // SOLICITANTE, mas JAMAIS podem chegar a um e-mail de avaliador -
    // imparcialidade e regra absoluta. Sexo/nome da mae ficam de fora do
    // e-mail de proposito (ver javadoc de EmailTemplateService).
    // ===================================================================

    /**
     * Confirma, de uma vez so, que NENHUM template de avaliador (convite
     * individual, convite em lote, lembrete, cancelamento, aviso de
     * informacao complementar disponivel) contem o CPF nem a data de
     * nascimento do paciente - nem formatados, nem crus.
     */
    @Test
    void nenhumTemplateDeAvaliadorContemCpfOuDataDeNascimentoDoPaciente() {
        Processo enviado = processo();
        enviado.setStatus(StatusProcesso.ENVIADO);
        enviado.getPareceres().forEach(par -> par.setDataEnvio(LocalDate.now()));

        MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Dra. Avaliadora", "a@example.com");

        java.util.List<EmailTemplate> templatesDeAvaliador = new java.util.ArrayList<>();
        // emailConvitePortal (chave "convite-portal") so e gerado via gerar(processo ENVIADO)
        templatesDeAvaliador.addAll(service.gerar(enviado).stream()
            .filter(t -> t.chave().equals("convite-portal")).toList());
        templatesDeAvaliador.add(service.emailConviteAvaliador(enviado, membro));
        templatesDeAvaliador.add(service.emailCancelamentoAvaliador(enviado, membro));
        templatesDeAvaliador.add(service.emailLembreteAvaliador(enviado, membro));
        templatesDeAvaliador.add(service.emailInfoComplementarDisponivel(enviado, membro));

        assertThat(templatesDeAvaliador).isNotEmpty();
        for (EmailTemplate t : templatesDeAvaliador) {
            String texto = t.corpo() + "\n" + t.assunto() + "\n" + t.titulo();
            assertThat(texto)
                .as("template de avaliador '%s' NAO pode conter o CPF do paciente", t.chave())
                .doesNotContain(CPF_VALIDO)
                .doesNotContain(CPF_FORMATADO);
            assertThat(texto)
                .as("template de avaliador '%s' NAO pode conter a data de nascimento do paciente", t.chave())
                .doesNotContain("15/03/1990")
                .doesNotContain("1990");
            // Reforco da regra ja existente: nome completo tambem nunca aparece.
            assertThat(texto).doesNotContain("Joao Paciente Secreto");
        }
    }

    /**
     * Os 3 e-mails a EQUIPE SOLICITANTE (Deferido, Indeferido, pedido de
     * informacao complementar) passam a exibir CPF e data de nascimento no
     * bloco de identificacao, quando o processo ja tem esses dados.
     */
    @Test
    void emailsAoSolicitanteExibemCpfEDataDeNascimentoQuandoPreenchidos() {
        Processo deferido = processo();
        deferido.setStatus(StatusProcesso.DEFERIDO);
        Processo indeferido = processo();
        indeferido.setStatus(StatusProcesso.INDEFERIDO);
        Processo info = processo();
        info.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        for (Processo p : java.util.List.of(deferido, indeferido, info)) {
            EmailTemplate t = service.gerar(p).get(0);
            assertThat(t.corpo())
                .as("template '%s' deveria exibir o CPF formatado do paciente", t.chave())
                .contains("CPF: " + CPF_FORMATADO);
            assertThat(t.corpo())
                .as("template '%s' deveria exibir a data de nascimento do paciente", t.chave())
                .contains("Data de nascimento: 15/03/1990");
        }
    }

    /**
     * Processo antigo, anterior a estes 4 campos (nullable no banco - ver
     * docs/RELATORIO-CAMPOS-PACIENTE-SOLICITANTE-2026-08.md, secao 3): o
     * e-mail nao pode quebrar nem exibir "null" - usa o mesmo fallback "-" ja
     * usado em PdfRelatorioBuilder.nvl.
     */
    @Test
    void emailAoSolicitanteUsaTracoComoFallbackQuandoCpfEDataDeNascimentoAindaNaoForamPreenchidos() {
        Processo p = processo();
        p.setPacienteCpf(null);
        p.setPacienteDataNascimento(null);
        p.setStatus(StatusProcesso.DEFERIDO);

        EmailTemplate deferido = service.gerar(p).stream()
            .filter(e -> e.chave().equals("deferido")).findFirst().orElseThrow();

        assertThat(deferido.corpo()).contains("CPF: -");
        assertThat(deferido.corpo()).contains("Data de nascimento: -");
        assertThat(deferido.corpo()).doesNotContain("null");
    }
}

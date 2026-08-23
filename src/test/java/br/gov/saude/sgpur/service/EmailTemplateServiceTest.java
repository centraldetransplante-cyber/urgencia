package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.config.EmailProperties;
import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateServiceTest {

    private final EmailTemplateService service = new EmailTemplateService(new EmailProperties());

    private Processo processo() {
        Processo p = new Processo();
        p.setNumero("07/2026");
        p.setPacienteNome("Joao Paciente Secreto");
        p.setPacienteRgct("123456-4360");
        p.setSolicitanteEquipe("Hospital Solicitante");
        p.setDataSituacaoEspecial(LocalDate.of(2026, 6, 1));
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
            "SNT", "CET", "RS", "SAUR", "SES", "PDF", "RGCT", "HCPA", "ISCMPA");
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
            int equipe = corpo.indexOf("Equipe solicitante: Hospital Solicitante");
            int prosa = corpo.indexOf("Informamos que o processo acima");
            if (prosa < 0) {
                prosa = corpo.indexOf("Durante a análise do processo acima");
            }

            assertThat(bloco).as("bloco de identificacao ausente em '%s'", t.chave()).isNotNegative();
            assertThat(paciente).isGreaterThan(bloco);
            assertThat(equipe).isGreaterThan(paciente);
            assertThat(prosa)
                .as("a prosa deve vir DEPOIS do bloco de identificacao em '%s'", t.chave())
                .isGreaterThan(equipe);
        }
    }

    // ===================================================================
    // Campos novos de identificacao do paciente (2026-08, ver
    // docs/RELATORIO-CAMPOS-PACIENTE-SOLICITANTE-2026-08.md): CPF e data de
    // nascimento no bloco de identificacao dos e-mails a equipe solicitante,
    // e a garantia de que eles NUNCA vazam para o lado do avaliador.
    // ===================================================================

    private Processo processoComCpfEDataNascimento() {
        Processo p = processo();
        p.setPacienteCpf("52998224725"); // CPF valido (modulo-11) usado nos testes de CpfUtil
        p.setPacienteDataNascimento(LocalDate.of(1990, 3, 15));
        return p;
    }

    /**
     * Os 3 e-mails dirigidos a EQUIPE SOLICITANTE (Deferido/Indeferido/Solicita
     * informacao) passam a exibir CPF (formatado) e data de nascimento no bloco
     * de identificacao, quando o processo os tem preenchidos.
     */
    @Test
    void emailsAoSolicitanteIncluemCpfEDataDeNascimentoQuandoPreenchidos() {
        Processo deferido = processoComCpfEDataNascimento();
        deferido.setStatus(StatusProcesso.DEFERIDO);
        Processo indeferido = processoComCpfEDataNascimento();
        indeferido.setStatus(StatusProcesso.INDEFERIDO);
        Processo info = processoComCpfEDataNascimento();
        info.setStatus(StatusProcesso.SOLICITA_INFORMACAO);

        for (Processo p : java.util.List.of(deferido, indeferido, info)) {
            EmailTemplate t = service.gerar(p).get(0);
            assertThat(t.corpo())
                .as("CPF formatado ausente em '%s'", t.chave())
                .contains("CPF: 529.982.247-25");
            assertThat(t.corpo())
                .as("Data de nascimento ausente em '%s'", t.chave())
                .contains("Data de nascimento: 15/03/1990");
            // Continua dentro do bloco de identificacao, antes da prosa
            int paciente = t.corpo().indexOf("Paciente: Joao Paciente Secreto");
            int cpf = t.corpo().indexOf("CPF: 529.982.247-25");
            int nascimento = t.corpo().indexOf("Data de nascimento: 15/03/1990");
            int equipe = t.corpo().indexOf("Equipe solicitante: Hospital Solicitante");
            assertThat(cpf).isGreaterThan(paciente);
            assertThat(nascimento).isGreaterThan(cpf);
            assertThat(equipe).isGreaterThan(nascimento);
        }
    }

    /**
     * Processo antigo (anterior a esta leva de campos): CPF/data de nascimento
     * nulos nao podem virar "null" nem uma linha vazia/quebrada no e-mail - o
     * bloco simplesmente omite as duas linhas, mantendo Processo/Paciente/Equipe
     * como sempre foi.
     */
    @Test
    void emailAoSolicitanteSemCpfNemDataDeNascimentoNaoQuebraNemImprimeNull() {
        Processo p = processo(); // sem CPF/data de nascimento
        p.setStatus(StatusProcesso.DEFERIDO);
        EmailTemplate t = service.gerar(p).get(0);

        assertThat(t.corpo()).doesNotContain("CPF: null");
        assertThat(t.corpo()).doesNotContain("Data de nascimento: null");
        assertThat(t.corpo()).doesNotContain("CPF:");
        assertThat(t.corpo()).doesNotContain("Data de nascimento:");
        assertThat(t.corpo()).contains("Paciente: Joao Paciente Secreto");
        assertThat(t.corpo()).contains("Equipe solicitante: Hospital Solicitante");
    }

    /**
     * Regra inviolavel de imparcialidade: NENHUM e-mail dirigido ao avaliador
     * (convite, lembrete, cancelamento, convite em lote, aviso de informacao
     * complementar disponivel) pode conter CPF nem data de nascimento do
     * paciente, mesmo quando esses campos estao preenchidos no Processo.
     */
    @Test
    void emailsAoAvaliadorNuncaExpoemCpfNemDataDeNascimentoDoPaciente() {
        Processo p = processoComCpfEDataNascimento();
        p.setStatus(StatusProcesso.DEFERIDO);
        MembroUrgenciaRenal membro = new MembroUrgenciaRenal("HCPA", "Dra. Avaliadora", "a@example.com");

        java.util.List<EmailTemplate> emailsAvaliador = java.util.List.of(
            service.emailConviteAvaliador(p, membro),
            service.emailCancelamentoAvaliador(p, membro),
            service.emailLembreteAvaliador(p, membro),
            service.emailInfoComplementarDisponivel(p, membro)
        );

        String cpfFormatado = "529.982.247-25";
        String cpfCru = "52998224725";
        String dataFormatada = "15/03/1990";

        for (EmailTemplate t : emailsAvaliador) {
            assertThat(t.corpo())
                .as("e-mail '%s' ao avaliador vazou o CPF formatado do paciente", t.chave())
                .doesNotContain(cpfFormatado);
            assertThat(t.corpo())
                .as("e-mail '%s' ao avaliador vazou o CPF cru do paciente", t.chave())
                .doesNotContain(cpfCru);
            assertThat(t.corpo())
                .as("e-mail '%s' ao avaliador vazou a data de nascimento do paciente", t.chave())
                .doesNotContain(dataFormatada);
            assertThat(t.corpo())
                .as("e-mail '%s' ao avaliador vazou o nome completo do paciente", t.chave())
                .doesNotContain("Joao Paciente Secreto");
        }
    }

    /**
     * Mesmo teste acima, mas para o template agrupado "convite-portal" (lista
     * todos os avaliadores de uma vez, exibido na aba Envio).
     */
    @Test
    void emailConvitePortalEmLoteNuncaExpoeCpfNemDataDeNascimento() {
        Processo p = processoComCpfEDataNascimento();
        p.setStatus(StatusProcesso.ENVIADO);
        p.getPareceres().forEach(par -> par.setDataEnvio(LocalDate.now()));

        EmailTemplate convitePortal = service.gerar(p).stream()
            .filter(e -> e.chave().equals("convite-portal")).findFirst().orElseThrow();

        assertThat(convitePortal.corpo()).doesNotContain("529.982.247-25");
        assertThat(convitePortal.corpo()).doesNotContain("52998224725");
        assertThat(convitePortal.corpo()).doesNotContain("15/03/1990");
        assertThat(convitePortal.corpo()).doesNotContain("Joao Paciente Secreto");
    }
}

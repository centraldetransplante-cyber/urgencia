package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.config.EmailProperties;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Gera textos de e-mail prontos (copiar/colar) para cada etapa do processo,
 * pre-preenchidos com os dados do processo. No e-mail aos MEDICOS AVALIADORES o
 * nome do paciente e OCULTADO (so iniciais), para preservar a IMPARCIALIDADE do
 * julgamento - os avaliadores decidem sem saber quem e o paciente (convencao da
 * equipe de Urgencia Renal). Os e-mails dirigidos a equipe SOLICITANTE (pedido de
 * informacao complementar, resposta de Deferido/Indeferido) levam o NOME COMPLETO.
 *
 * <p><strong>Convencoes de redacao destes textos (2026-08-11)</strong> - o dono do
 * produto relatou que o e-mail de deferimento chegava com "formatacao ridicula" a
 * equipe solicitante. Tres regras passaram a valer para TODO texto desta classe:</p>
 * <ol>
 *   <li><strong>Acentuacao correta.</strong> Sao documentos institucionais que saem
 *       da Secretaria para fora - diferente de {@code ResultadoParecer.descricao} e
 *       {@code StatusProcesso.descricao}, deliberadamente sem acento por alimentarem
 *       PDF oficial/exportacao (ver CLAUDE.md).</li>
 *   <li><strong>Nunca CAIXA ALTA no meio de frase.</strong> O envio e em TEXTO PURO
 *       ({@code EmailSenderService}, {@code helper.setText(body, false)}) - nao ha
 *       negrito possivel, entao "foi DEFERIDO"/"Segue EM ANEXO" nao viram enfase,
 *       so parecem grito numa correspondencia formal. O destaque real vem da
 *       estrutura (regra 3), nao da capitalizacao.</li>
 *   <li><strong>Bloco de identificacao antes da prosa</strong> nos e-mails a equipe
 *       solicitante (processo / paciente / equipe), em vez de uma linha de dado
 *       solta no meio dos paragrafos.</li>
 * </ol>
 */
@Service
public class EmailTemplateService {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** URL base da aplicacao, usada nos links do Portal do Avaliador. */
    @Value("${app.base-url:http://localhost:3000}")
    private String baseUrl;

    private final EmailProperties emailProperties;

    public EmailTemplateService(EmailProperties emailProperties) {
        this.emailProperties = emailProperties;
    }

    private String assinatura() {
        return emailProperties.getAssinatura();
    }

    private String assunto(String resto) {
        return emailProperties.getPrefixoAssunto() + " - " + resto;
    }

    public List<EmailTemplate> gerar(Processo p) {
        List<EmailTemplate> lista = new ArrayList<>();
        // Template de convite ao portal para processos em andamento (ENVIADO)
        if (p.getStatus() == StatusProcesso.ENVIADO) {
            lista.add(emailConvitePortal(p));
        }
        if (p.getStatus() == StatusProcesso.DEFERIDO) {
            lista.add(emailDeferido(p));
        } else if (p.getStatus() == StatusProcesso.INDEFERIDO) {
            lista.add(emailIndeferido(p));
        } else if (p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO) {
            lista.add(emailSolicitaInfo(p));
        }
        // Filtra templates que so devem ser exibidos apos o envio aos medicos
        // (dataEnvio registrada em ao menos um parecer). Templates com
        // requerEnvio=false sao sempre exibidos.
        boolean envioRealizado = p.getPareceres() != null
            && !p.getPareceres().isEmpty()
            && p.getPareceres().get(0).getDataEnvio() != null;
        return lista.stream()
            .filter(t -> !t.requerEnvio() || envioRealizado)
            .toList();
    }

    /**
     * Convite individual ao avaliador para votar no Portal do Avaliador.
     * Destinado a cada medico separadamente. Contem APENAS iniciais do paciente
     * (imparcialidade) e link de acesso ao portal.
     * Exibido na aba Envio quando o processo esta em ENVIADO.
     */
    public EmailTemplate emailConviteAvaliador(Processo p, MembroUrgenciaRenal membro) {
        String iniciais = Iniciais.de(p.getPacienteNome());
        String idProcesso = p.getNumero() + " - Paciente " + iniciais;
        String portalUrl = baseUrl + "/avaliador";

        String corpo = """
            Prezado(a) %s,

            Você foi designado(a) como avaliador(a) do processo de Urgência Renal
            abaixo e pode registrar seu parecer diretamente no sistema da Secretaria,
            sem necessidade de responder por e-mail.

            Processo: %s
            Data de solicitação da urgência renal: %s

            Para emitir seu parecer, acesse o Portal do Avaliador com suas credenciais:
            %s

            Caso não possua login, entre em contato com a equipe da Secretaria.

            O nome do paciente foi omitido para preservar a imparcialidade do
            julgamento; ele é identificado apenas pelas iniciais.

            Atenciosamente,
            %s
            """.formatted(
                membro.getNome(),
                idProcesso,
                p.getDataSituacaoEspecial() != null ? p.getDataSituacaoEspecial().format(DATA) : "(data)",
                portalUrl,
                assinatura());

        return new EmailTemplate("convite-avaliador",
            "Convite ao Portal do Avaliador - " + membro.getNome(), "person-check",
            assunto("Solicitação de avaliação - Processo " + idProcesso),
            corpo);
    }

    /**
     * Avisa um avaliador que ainda nao votou de que o processo foi CANCELADO
     * pelo solicitante e saiu da fila dele. SEM nome completo do paciente (so
     * iniciais) - a regra de imparcialidade vale mesmo num aviso administrativo.
     */
    public EmailTemplate emailCancelamentoAvaliador(Processo p, MembroUrgenciaRenal membro) {
        String iniciais = Iniciais.de(p.getPacienteNome());
        String idProcesso = p.getNumero() + " CET-RS - Paciente " + iniciais;

        String corpo = """
            Prezado(a) %s,

            O processo abaixo foi cancelado pela equipe solicitante e não aguarda
            mais o seu parecer. Ele já foi retirado da sua lista no Portal do
            Avaliador - nenhuma ação é necessária da sua parte.

            Processo %s

            Pedimos desculpas por eventual análise já iniciada.

            O nome do paciente foi omitido para preservar a imparcialidade do
            julgamento; ele é identificado apenas pelas iniciais.

            Atenciosamente,
            %s
            """.formatted(membro.getNome(), idProcesso, assinatura());

        return new EmailTemplate("cancelamento-avaliador",
            "Aviso de cancelamento - " + membro.getNome(), "slash-circle",
            assunto("Processo cancelado - " + idProcesso),
            corpo);
    }

    /**
     * Lembrete manual de avaliacao pendente, disparado pelo operador para um
     * avaliador especifico que ainda nao registrou parecer. SEM nome completo
     * do paciente (so iniciais), para preservar a imparcialidade do julgamento.
     */
    public EmailTemplate emailLembreteAvaliador(Processo p, MembroUrgenciaRenal membro) {
        String iniciais = Iniciais.de(p.getPacienteNome());
        String idProcesso = p.getNumero() + " CET-RS - Paciente " + iniciais;
        String portalUrl = baseUrl + "/avaliador";

        String corpo = """
            Prezado(a) %s,

            Lembramos que o processo abaixo permanece disponível para a sua avaliação
            e aguarda o seu parecer.

            Processo %s

            Para registrar seu parecer, acesse o Portal do Avaliador com suas credenciais:
            %s

            O nome do paciente foi omitido para preservar a imparcialidade do
            julgamento; ele é identificado apenas pelas iniciais.

            Atenciosamente,
            %s
            """.formatted(membro.getNome(), idProcesso, portalUrl, assinatura());

        return new EmailTemplate("lembrete-avaliador",
            "Lembrete de avaliação pendente - " + membro.getNome(), "bell",
            assunto("Lembrete de avaliação pendente - Processo " + idProcesso),
            corpo);
    }

    /**
     * Lembrete automatico INTERNO (ADMIN/OPERADOR) de um processo DEFERIDO que
     * segue sem o comprovante de insercao no SNT anexado. Enquanto esse anexo
     * nao existe, a resposta oficial ao solicitante fica bloqueada
     * ({@code ProcessoValidator}) — o paciente foi deferido e a equipe
     * solicitante nao foi comunicada.
     *
     * <p>Destinatario e a propria equipe da Secretaria (nao avaliadores), entao
     * o e-mail leva o NOME COMPLETO do paciente: a regra de imparcialidade vale
     * para o material dos avaliadores, nao para a operacao interna.</p>
     *
     * @param diasDesdeDecisao dias corridos desde a decisao, so para o texto.
     */
    public EmailTemplate emailLembreteComprovanteSnt(Processo p, long diasDesdeDecisao) {
        String linkProcesso = baseUrl + "/processos/" + p.getId() + "#finalizacao";

        String corpo = """
            Olá,

            Processo: %s
            Paciente: %s
            Equipe solicitante: %s

            O processo acima foi deferido há %d dia(s) e ainda não tem o comprovante
            de inserção da urgência renal no Sistema Nacional de Transplantes (SNT)
            anexado.

            Enquanto o comprovante não for anexado, a resposta oficial ao
            solicitante permanece bloqueada - ou seja, a equipe que abriu o pedido
            ainda não foi comunicada do deferimento.

            Anexe o comprovante na aba Finalização do processo:
            %s

            Atenciosamente,
            %s
            """.formatted(p.getNumero(), p.getPacienteNome(), p.getSolicitanteEquipe(),
                diasDesdeDecisao, linkProcesso, assinatura());

        return new EmailTemplate("lembrete-comprovante-snt",
            "Lembrete interno: comprovante SNT pendente", "clipboard2-x",
            assunto("Comprovante SNT pendente - Processo " + p.getNumero()),
            corpo);
    }

    /**
     * Template agrupado de convite ao portal (exibido na aba Envio do processo).
     * Lista todos os avaliadores com o link unico do portal.
     * SEM nome completo do paciente (so iniciais).
     */
    private EmailTemplate emailConvitePortal(Processo p) {
        String iniciais = Iniciais.de(p.getPacienteNome());
        String idProcesso = p.getNumero() + " - Paciente " + iniciais;
        String portalUrl = baseUrl + "/avaliador";
        String medicos = p.getPareceres().stream()
            .map(par -> "- " + par.getMembro().getNome() + " (" + par.getMembro().getInstituicao() + ")")
            .collect(Collectors.joining("\n"));

        String corpo = """
            Prezados(as) avaliadores(as),

            Vocês foram designados(as) para avaliar o processo de Urgência Renal abaixo
            e podem registrar o parecer diretamente no sistema (sem responder por e-mail).

            Processo: %s
            Data de solicitação da urgência renal: %s

            Avaliadores designados:
            %s

            Acesse o Portal do Avaliador com suas credenciais:
            %s

            O nome do paciente foi omitido para preservar a imparcialidade do julgamento.

            Atenciosamente,
            %s
            """.formatted(
                idProcesso,
                p.getDataSituacaoEspecial() != null ? p.getDataSituacaoEspecial().format(DATA) : "(data)",
                medicos,
                portalUrl,
                assinatura());

        return new EmailTemplate("convite-portal",
            "Convite ao Portal do Avaliador (votação no sistema)", "person-check",
            assunto("Acesso ao Portal do Avaliador - Processo " + idProcesso),
            corpo,
            true); // so exibir apos dataEnvio registrada
    }

    /**
     * Pedido de informacao complementar ao solicitante: quando um medico
     * avaliador pede mais informacoes, repassa-se o pedido a EQUIPE SOLICITANTE
     * (a que abriu o processo) para que complemente o processo. Texto pronto
     * para copiar/colar. Como o destinatario e o SOLICITANTE (nao os medicos
     * avaliadores), o e-mail PODE e DEVE conter o NOME COMPLETO do paciente.
     */
    private EmailTemplate emailSolicitaInfo(Processo p) {
        String idProcesso = p.getNumero() + " - Paciente " + p.getPacienteNome();

        String corpo = """
            Prezados(as),

            Processo: %s
            Paciente: %s
            Equipe solicitante: %s

            Durante a análise do processo acima, um(a) dos(as) avaliadores(as)
            da Urgência Renal solicitou informações complementares para
            concluir o parecer.

            Solicitamos, por gentileza, o envio das informações e/ou dos documentos
            adicionais necessários à continuidade da análise, respondendo a este
            e-mail. Assim que recebidas, a análise será retomada e o processo
            seguirá para a decisão.

            Atenciosamente,
            %s
            """.formatted(p.getNumero(), p.getPacienteNome(), p.getSolicitanteEquipe(), assinatura());

        return new EmailTemplate("solicita-info",
            "Pedido de informação complementar ao solicitante", "question-circle",
            assunto("Processo " + idProcesso + " - Solicitação de informações complementares"),
            corpo);
    }

    public EmailTemplate emailDeferido(Processo p) {
        String corpo = """
            Prezados(as),

            Processo: %s
            Paciente: %s
            Equipe solicitante: %s

            Informamos que o processo acima foi deferido pela equipe de
            Urgência Renal.

            Segue em anexo o comprovante de inserção da urgência renal no
            Sistema Nacional de Transplantes (SNT).

            Permanecemos à disposição para esclarecimentos.

            Atenciosamente,
            %s
            """.formatted(p.getNumero(), p.getPacienteNome(), p.getSolicitanteEquipe(), assinatura());

        return new EmailTemplate("deferido", "Resposta ao solicitante (Deferido)", "check-circle",
            assunto("Processo " + p.getNumero() + " - Deferido"), corpo);
    }

    public EmailTemplate emailIndeferido(Processo p) {
        String motivo = (p.getMotivoIndeferimento() == null || p.getMotivoIndeferimento().isBlank())
            ? "(informar o motivo do indeferimento)" : p.getMotivoIndeferimento();

        String corpo = """
            Prezados(as),

            Processo: %s
            Paciente: %s
            Equipe solicitante: %s

            Informamos que o processo acima foi indeferido pela equipe de
            Urgência Renal.

            Motivo: %s

            O ofício de indeferimento segue em anexo a este e-mail.

            Permanecemos à disposição para esclarecimentos.

            Atenciosamente,
            %s
            """.formatted(p.getNumero(), p.getPacienteNome(), p.getSolicitanteEquipe(), motivo, assinatura());

        return new EmailTemplate("indeferido", "Resposta ao solicitante (Indeferido)", "x-circle",
            assunto("Processo " + p.getNumero() + " - Indeferido"), corpo);
    }
}

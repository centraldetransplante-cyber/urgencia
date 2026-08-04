package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Anexo;
import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.TipoAnexo;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Etapa 2 do fluxo (Envio): monta e carimba o PDF consolidado enviado aos
 * avaliadores e efetiva o registro do envio. Extraido de
 * {@code ProcessoDecisaoController.registrarEnvio}, que fazia toda essa
 * logica de negocio (leitura de bytes, validacao de PDF, consolidacao,
 * carimbo, persistencia) diretamente no controller HTTP.
 *
 * <p>Preserva o mesmo comportamento observavel de antes: mesma ordem "salva o
 * novo anexo antes de remover o antigo" (evita o processo ficar sem nenhum
 * PDF de solicitacao se o meio do caminho falhar), mesmo tratamento de PDF
 * corrompido/sem senha/sem paginas (fica de fora da consolidacao com aviso,
 * nao bloqueia sozinho salvo se nenhum PDF sobrar valido) e as mesmas
 * mensagens de erro/aviso.
 */
@Service
public class RegistroEnvioService {

    private static final Logger log = LoggerFactory.getLogger(RegistroEnvioService.class);

    /**
     * Janela (minutos) dentro da qual um 2o disparo de convite ao MESMO
     * parecer e tratado como duplicata (duplo-clique/duplo-POST) e pulado,
     * em vez de reenviar o e-mail. Passado esse tempo, e tratado como reenvio
     * legitimo (ex.: processo reaberto e reenviado horas/dias depois) e volta
     * a enviar normalmente. Ver {@code ProcessoService.reivindicarConviteAvaliador}.
     */
    static final int JANELA_DUPLICIDADE_CONVITE_MINUTOS = 5;

    private final ProcessoService processoService;
    private final SolicitacaoAvaliadorService solicitacaoAvaliadorService;
    private final AnexoStorageService anexoStorage;
    private final AuditoriaService auditoria;
    private final EmailTemplateService emailTemplateService;
    private final EmailSenderService emailSenderService;

    public RegistroEnvioService(ProcessoService processoService,
                                SolicitacaoAvaliadorService solicitacaoAvaliadorService,
                                AnexoStorageService anexoStorage,
                                AuditoriaService auditoria,
                                EmailTemplateService emailTemplateService,
                                EmailSenderService emailSenderService) {
        this.processoService = processoService;
        this.solicitacaoAvaliadorService = solicitacaoAvaliadorService;
        this.anexoStorage = anexoStorage;
        this.auditoria = auditoria;
        this.emailTemplateService = emailTemplateService;
        this.emailSenderService = emailSenderService;
    }

    /**
     * Resultado do registro de envio: ou {@code ok=true} com a mensagem de
     * sucesso e a lista de avisos (documentos clinicos que ficaram de fora do
     * PDF consolidado), ou {@code ok=false} com a mensagem de erro (nenhum
     * efeito colateral ocorreu).
     */
    public record RegistroEnvioResultado(boolean ok, String mensagemErro,
                                         String mensagemSucesso, List<String> avisos) {
        public static RegistroEnvioResultado erro(String msg) {
            return new RegistroEnvioResultado(false, msg, null, List.of());
        }

        public static RegistroEnvioResultado sucesso(String msg, List<String> avisos) {
            return new RegistroEnvioResultado(true, null, msg, avisos);
        }
    }

    @Transactional
    public RegistroEnvioResultado registrar(Long processoId) {
        Processo p = processoService.buscar(processoId);
        LocalDate hoje = LocalDate.now();

        // O PDF dos avaliadores e montado SO com os documentos clinicos
        // anonimizados (PDF) anexados pelo operador: funde-os em um unico PDF e
        // carimba, em cada pagina, um cabecalho com nº do processo + INICIAIS do
        // paciente (NUNCA o nome completo - imparcialidade do julgamento). Sem a
        // folha-rosto gerada pelo sistema. A solicitacao ORIGINAL recebida (nome
        // completo) NUNCA entra aqui. Sem nenhum documento clinico PDF nao ha o
        // que enviar: bloqueia o envio.
        List<byte[]> partes = new ArrayList<>();
        List<String> partesNomes = new ArrayList<>();
        List<String> ignorados = new ArrayList<>();
        List<String> pendentesAnonimizacao = new ArrayList<>();
        try {
            for (Anexo doc : p.getAnexos()) {
                // TRAVA DE ANONIMIZACAO: o documento que veio do Portal do
                // Solicitante entra como DOCUMENTO_PORTAL_NAO_ANONIMIZADO e
                // NUNCA pode ser fundido no PDF dos avaliadores - ele traz o
                // nome completo do paciente no corpo do laudo. So depois de o
                // operador confirmar a anonimizacao (virando
                // DOCUMENTO_CLINICO_AVALIADOR) ele entra aqui.
                if (doc.getTipo() == TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO) {
                    pendentesAnonimizacao.add(doc.getNomeArquivo());
                    continue;
                }
                if (doc.getTipo() != TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR) {
                    continue;
                }
                boolean ehPdf = doc.getContentType() != null
                    && doc.getContentType().toLowerCase().contains("application/pdf");
                if (!ehPdf) {
                    ignorados.add(doc.getNomeArquivo());
                    continue;
                }
                partes.add(java.nio.file.Files.readAllBytes(anexoStorage.resolverArquivo(doc)));
                partesNomes.add(doc.getNomeArquivo());
            }
        } catch (IOException e) {
            return RegistroEnvioResultado.erro("Falha ao ler os documentos clinicos: " + e.getMessage());
        }

        if (partes.isEmpty()) {
            // Caso mais comum da trava: o unico documento do processo e o que
            // veio do portal, ainda pendente de revisao. Mensagem especifica -
            // "anexe um documento clinico" confundiria o operador, que ve um
            // documento anexado na tela.
            String detalhe;
            if (!pendentesAnonimizacao.isEmpty()) {
                detalhe = "Confirme a anonimizacao do(s) documento(s) enviado(s) pelo solicitante ("
                    + String.join(", ", pendentesAnonimizacao)
                    + ") antes de registrar o envio aos avaliadores. Enquanto isso nao for feito, "
                    + "eles NAO sao enviados aos medicos.";
            } else if (ignorados.isEmpty()) {
                detalhe = "Anexe ao menos um documento clinico (PDF) antes de registrar o envio.";
            } else {
                detalhe = "Os documentos anexados nao sao PDF e nao podem ser consolidados ("
                    + String.join(", ", ignorados) + "). Anexe ao menos um documento clinico em PDF.";
            }
            return RegistroEnvioResultado.erro(detalhe);
        }
        // Aviso nao bloqueante: ja ha documento anonimizado suficiente para
        // enviar, mas sobrou documento do portal sem revisao - ele fica de fora
        // do PDF (comportamento correto), e o operador precisa saber disso.
        if (!pendentesAnonimizacao.isEmpty()) {
            ignorados.add(String.join(", ", pendentesAnonimizacao)
                + " (pendente de confirmacao de anonimizacao - nao enviado aos avaliadores)");
        }

        // Valida se os PDFs tem paginas antes de consolidar. Um PDF corrompido
        // ou protegido por senha e descartado da consolidacao, mas o nome vai
        // para "ignorados" (mesmo aviso dos anexos nao-PDF) - o operador precisa
        // saber que um documento clinico ficou de fora, em vez de o envio
        // seguir silenciosamente incompleto.
        List<byte[]> validos = new ArrayList<>();
        for (int i = 0; i < partes.size(); i++) {
            byte[] bytes = partes.get(i);
            String nome = partesNomes.get(i);
            try (com.lowagie.text.pdf.PdfReader chk = new com.lowagie.text.pdf.PdfReader(bytes)) {
                if (chk.getNumberOfPages() > 0) {
                    validos.add(bytes);
                } else {
                    ignorados.add(nome + " (PDF sem paginas)");
                }
            } catch (Exception e) {
                ignorados.add(nome + " (PDF corrompido ou protegido por senha)");
            }
        }
        if (validos.isEmpty()) {
            return RegistroEnvioResultado.erro(
                "Nenhum dos documentos clinicos anexados e um PDF valido com paginas. "
                + "Remova-os e anexe novamente os documentos originais.");
        }
        partes = validos;

        // PRIMEIRO: gera o PDF consolidado com cabecalho carimbado, em memoria,
        // e SALVA o novo anexo. SO DEPOIS de o novo anexo estar gravado com
        // sucesso (arquivo em disco + registro no banco) e que o(s) anexo(s)
        // antigo(s) sao removidos - assim, se consolidar/carimbar/salvar
        // falhar em qualquer ponto, o processo NAO fica sem nenhum PDF de
        // solicitacao aos avaliadores (evita perder um anexo bom por causa de
        // uma tentativa de reenvio que falhou no meio do caminho).
        try {
            byte[] consolidado = solicitacaoAvaliadorService.consolidar(partes);
            byte[] pdfSolicitacao = solicitacaoAvaliadorService.carimbarCabecalho(consolidado, p);
            String nomeSolicitacao = SolicitacaoAvaliadorService.nomeArquivoOficial(p);

            Anexo novoAnexo = anexoStorage.salvarBytes(p, TipoAnexo.SOLICITACAO_AVALIADOR,
                "Copia da solicitacao para envio as equipes (documentos clinicos anonimizados com cabecalho; nome completo suprimido)",
                nomeSolicitacao, "application/pdf", pdfSolicitacao);
            anexoStorage.removerAntigosDoTipo(p, TipoAnexo.SOLICITACAO_AVALIADOR, novoAnexo.getId());

            // SO depois de o novo anexo estar seguro, efetiva o envio.
            //
            // So atualiza dataEnvio de quem AINDA NAO respondeu (resultado ==
            // null). Bug real corrigido (2026-08-03): antes, o forEach rodava
            // sobre TODOS os pareceres, inclusive os ja votados - num reenvio
            // (documento atualizado apos um avaliador ja ter dado o parecer),
            // isso sobrescrevia o dataEnvio original com hoje, deixando
            // dataEnvio > dataResposta para quem ja tinha votado. A guarda de
            // "dias < 0" em TempoRespostaService evita excecao/media negativa,
            // mas descarta silenciosamente esse parecer de TODAS as metricas
            // (media geral, media do avaliador, contagem fora do prazo) - o
            // voto simplesmente sumia das estatisticas, sem nenhum aviso.
            p.getPareceres().stream()
                .filter(par -> par.getResultado() == null)
                .forEach(par -> par.setDataEnvio(hoje));
            processoService.salvar(p);
            processoService.registrarEnvio(processoId);

            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - Solicitacao PDF consolidada (cabecalho carimbado) gerada automaticamente");
        } catch (Exception e) {
            log.error("Erro ao registrar envio do processo {}", processoId, e);
            return RegistroEnvioResultado.erro(
                "Nao foi possivel gerar o PDF de envio. Verifique os documentos clinicos "
                + "anexados (devem ser PDFs validos, nao corrompidos e sem senha) e tente novamente.");
        }

        auditoria.registrar("ENVIO_AVALIADORES_REGISTRADO", "Processo " + p.getNumero());
        String msg = "Envio aos avaliadores registrado em "
            + hoje.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".";
        return RegistroEnvioResultado.sucesso(msg, ignorados);
    }

    /**
     * Resultado do disparo automatico dos convites ao Portal do Avaliador:
     * quantos e-mails sairam e a lista de avisos por destinatario que ficou de
     * fora (avaliador sem e-mail cadastrado, falha de SMTP). Nunca representa
     * erro fatal - o envio aos avaliadores ja esta gravado quando isto roda.
     */
    public record ConvitesResultado(int enviados, List<String> avisos) {}

    /**
     * Manda a cada avaliador com parecer pendente o convite para votar no
     * Portal do Avaliador ({@code EmailTemplateService.emailConviteAvaliador} -
     * so iniciais do paciente, nunca o nome completo: imparcialidade).
     *
     * <p><b>Chamar SEMPRE depois de {@link #registrar(Long)} ter retornado
     * {@code ok=true}, e NUNCA de dentro da transacao dele.</b> O envio de
     * e-mail nao pode desfazer o registro do envio: o ato juridico relevante e
     * o envio gravado (com o PDF consolidado e as datas nos pareceres), e o
     * convite e uma cortesia que o operador consegue reenviar a qualquer
     * momento pelo lembrete manual ({@code POST /lembrete-avaliador}). Por isso
     * este metodo nao e {@code @Transactional} e nao propaga excecao: cada
     * falha vira um aviso na tela. Isso e o oposto de
     * {@code ProcessoService.finalizarResposta}, onde a falha de SMTP faz
     * rollback de proposito - lah o e-mail E a entrega ao solicitante; aqui o
     * e-mail e apenas o aviso de que ha trabalho no portal.
     *
     * <p>Usa {@code pareceresPendentesComEmail} (resultado nulo + dataEnvio
     * preenchida), entao num reenvio quem ja votou NAO recebe convite de novo.
     *
     * <p><b>Guarda de duplicidade (2026-08-03):</b> antes de mandar o e-mail
     * de cada parecer, reivindica atomicamente {@code Parecer.conviteEnviadoEm}
     * via {@code ProcessoService.reivindicarConviteAvaliador} - se outra
     * chamada (duplo-clique no botao "Registrar envio", duplo-POST, refresh)
     * ja reivindicou esse MESMO parecer ha menos de
     * {@link #JANELA_DUPLICIDADE_CONVITE_MINUTOS} minutos, este parecer e
     * pulado (auditoria {@code CONVITE_AVALIADOR_IGNORADO_DUPLICADO}, nunca
     * tratado como erro/aviso ao operador). Passada a janela, volta a mandar
     * normalmente - nao interfere no reenvio legitimo apos o ADMIN reabrir o
     * processo (ver {@code RegistrarEnvioDuasVezesIntegrationTest}).
     */
    public ConvitesResultado enviarConvitesAvaliadores(Long processoId) {
        Processo p = processoService.buscar(processoId);
        List<String> avisos = new ArrayList<>();
        int enviados = 0;
        for (Parecer parecer : processoService.pareceresPendentesComEmail(processoId)) {
            MembroUrgenciaRenal membro = parecer.getMembro();
            // TRAVA DE DUPLICIDADE: reivindicacao atomica no banco antes de
            // mandar o e-mail. Se outra execucao (duplo-clique, duplo-POST,
            // dois operadores simultaneos) ja reivindicou este MESMO parecer
            // ha menos de JANELA_DUPLICIDADE_CONVITE_MINUTOS, pula sem tratar
            // como erro/aviso - o convite ja foi (ou esta sendo) enviado.
            if (!processoService.reivindicarConviteAvaliador(parecer.getId(), JANELA_DUPLICIDADE_CONVITE_MINUTOS)) {
                auditoria.registrar("CONVITE_AVALIADOR_IGNORADO_DUPLICADO",
                    "Processo " + p.getNumero() + " - " + membro.getNome()
                    + " - convite ja enviado ha menos de " + JANELA_DUPLICIDADE_CONVITE_MINUTOS + " min, ignorado como duplicata");
                continue;
            }
            if (membro.getEmail() == null || membro.getEmail().isBlank()) {
                avisos.add(membro.getNome() + " (sem e-mail cadastrado)");
                auditoria.registrar("CONVITE_AVALIADOR_NAO_ENVIADO",
                    "Processo " + p.getNumero() + " - " + membro.getNome() + " - sem e-mail cadastrado");
                continue;
            }
            EmailTemplate template = emailTemplateService.emailConviteAvaliador(p, membro);
            if (emailSenderService.enviar(membro.getEmail(), template.assunto(), template.corpo())) {
                enviados++;
                auditoria.registrar("CONVITE_AVALIADOR_ENVIADO",
                    "Processo " + p.getNumero() + " - " + membro.getNome());
            } else {
                avisos.add(membro.getNome() + " (falha no envio do e-mail)");
                auditoria.registrar("CONVITE_AVALIADOR_FALHA",
                    "Processo " + p.getNumero() + " - " + membro.getNome());
            }
        }
        return new ConvitesResultado(enviados, avisos);
    }
}

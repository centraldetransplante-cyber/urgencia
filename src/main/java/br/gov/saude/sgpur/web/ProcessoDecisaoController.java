package br.gov.saude.sgpur.web;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.service.AnexoStorageService;
import br.gov.saude.sgpur.service.AuditoriaService;
import br.gov.saude.sgpur.service.DecisaoFinalService;
import br.gov.saude.sgpur.service.EmailSenderService;
import br.gov.saude.sgpur.service.EmailTemplateService;
import br.gov.saude.sgpur.service.GeminiService;
import br.gov.saude.sgpur.service.ProcessoService;
import br.gov.saude.sgpur.service.ProcessoValidator;
import br.gov.saude.sgpur.service.RegistroEnvioService;
import br.gov.saude.sgpur.service.dto.EmailTemplate;
import br.gov.saude.sgpur.web.dto.AcaoResponse;
import br.gov.saude.sgpur.web.dto.EmailPreviewResponse;
import br.gov.saude.sgpur.web.dto.IaTextoResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Passos 2 a 4 do fluxo: envio aos avaliadores, decisao final e a pausa
 * "Solicita informacao". Desde 2026-07-27 o registro do parecer (resultado +
 * anexo) NAO passa mais por aqui - e feito exclusivamente pelo proprio
 * avaliador autenticado no Portal do Avaliador (AvaliadorController). Este
 * controller so acompanha o resultado e cobre o disparo manual de e-mails
 * (lembretes e textos prontos) e a assistencia por IA ligada a decisao.
 *
 * <p><b>Sem @Transactional de nivel de classe (removido em 2026-07-29).</b>
 * Uma transacao aberta pelo controller e compartilhada (REQUIRED) com todo
 * servico {@code @Transactional} chamado dentro dela; quando um deles lanca
 * dentro de um {@code try/catch} do metodo, a transacao inteira e marcada como
 * rollback-only: o {@code catch} devolve o flash amigavel, mas o commit final
 * estoura {@code UnexpectedRollbackException} (500) e leva junto qualquer
 * escrita anterior do mesmo metodo. Aqui isso atingia principalmente
 * {@link #retomarAnalise} (o anexo de informacao complementar ja salvo era
 * perdido quando {@code retomarAposInformacao} lancava) e
 * {@link #anexarDocumentoClinico} (arquivo de tipo nao permitido devolvia 500
 * em vez da mensagem de negocio).
 *
 * <p>Os metodos que precisam de sessao aberta para navegar colecoes LAZY usam
 * {@link #txTemplate} em blocos curtos e independentes (mesmo padrao ja
 * validado em {@code AvaliadorController.registrarVoto}) ou declaram
 * {@code @Transactional} proprio quando nao ha {@code try/catch} em volta de
 * servico transacional ({@link #sugestaoMotivo}, {@link #revisarEmailIa},
 * {@link #preverEmail}, {@link #enviarEmailPronto}).
 */
@Controller
@RequestMapping("/processos")
public class ProcessoDecisaoController {

    private final ProcessoService processoService;
    private final ProcessoValidator validator;
    private final DecisaoFinalService decisaoFinalService;
    private final RegistroEnvioService registroEnvioService;
    private final EmailTemplateService emailTemplateService;
    private final EmailSenderService emailSenderService;
    private final AnexoStorageService anexoStorage;
    private final AuditoriaService auditoria;
    private final GeminiService geminiService;
    /**
     * Transacoes curtas e independentes para os trechos que precisam de sessao
     * aberta (validacao que navega pareceres/anexos, geracao dos PDFs finais)
     * sem que uma falha nesses trechos possa desfazer a escrita ja commitada
     * pelo servico anterior. Ver o javadoc da classe.
     */
    private final TransactionTemplate txTemplate;

    public ProcessoDecisaoController(ProcessoService processoService,
                                     ProcessoValidator validator,
                                     DecisaoFinalService decisaoFinalService,
                                     RegistroEnvioService registroEnvioService,
                                     EmailTemplateService emailTemplateService,
                                     EmailSenderService emailSenderService,
                                     AnexoStorageService anexoStorage,
                                     AuditoriaService auditoria,
                                     GeminiService geminiService,
                                     PlatformTransactionManager txManager) {
        this.processoService = processoService;
        this.validator = validator;
        this.decisaoFinalService = decisaoFinalService;
        this.registroEnvioService = registroEnvioService;
        this.emailTemplateService = emailTemplateService;
        this.emailSenderService = emailSenderService;
        this.anexoStorage = anexoStorage;
        this.auditoria = auditoria;
        this.geminiService = geminiService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Gera Oficio/Relatorio Final apos uma decisao ja COMMITADA, numa transacao
     * curta e propria, com o processo RECARREGADO dentro dela.
     *
     * <p>Duas razoes para o bloco existir: {@code DecisaoFinalService} navega
     * colecoes LAZY do processo (getAnexos/getPareceres) e precisa de sessao
     * aberta - o processo devolvido pelo servico anterior ja esta desanexado -,
     * e uma falha aqui nao pode desfazer a decisao (por isso transacao
     * separada, e nao a mesma do metodo). Mesmo padrao de
     * {@code AvaliadorController.registrarVoto} (TX 4).
     *
     * @throws IllegalStateException repassada do servico (falha ao gerar PDF),
     *         para o chamador exibir o aviso sem perder a decisao.
     */
    private void gerarDocumentosFinais(Long processoId) {
        txTemplate.executeWithoutResult(status ->
            decisaoFinalService.gerarDocumentos(processoService.buscar(processoId)));
    }

    /**
     * Registra o RECEBIMENTO da informacao complementar do solicitante e RETOMA
     * a analise: o processo volta de SOLICITA_INFORMACAO para ENVIADO (fluxo de
     * Respostas/Decisao). Opcionalmente anexa a resposta recebida.
     *
     * <p><b>Sem @Transactional de proposito</b> (era o pior caso da familia de
     * bug descrita no javadoc da classe): o metodo faz ate 4 escritas em
     * sequencia - anexo da informacao complementar, {@code retomarAposInformacao},
     * {@code tentarDecisaoAutomatica} e os PDFs finais -, cada uma delas em
     * servico {@code @Transactional} e cada uma dentro de um {@code try/catch}.
     * Compartilhando a transacao do controller, a {@code IllegalStateException}
     * de {@code retomarAposInformacao} marcava tudo como rollback-only: o
     * operador via a mensagem de erro tratada, mas <b>o anexo que ele acabara
     * de subir era descartado</b> e o commit final quebrava com
     * {@code UnexpectedRollbackException}. Sem transacao de controller, cada
     * passo commita sozinho e o passo seguinte que falhar nao desfaz o anterior.
     */
    @PostMapping("/{id}/retomar-analise")
    public String retomarAnalise(@PathVariable Long id,
                                 @RequestParam(value = "arquivo", required = false) MultipartFile arquivo,
                                 RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#respostas";
        }
        if (arquivo != null && !arquivo.isEmpty()) {
            try {
                anexoStorage.salvar(p, TipoAnexo.INFO_COMPLEMENTAR,
                    "Resposta com as informações complementares do solicitante", arquivo);
                auditoria.registrar("ANEXO_ADICIONADO",
                    "Processo " + p.getNumero() + " - Informacao complementar (recebida)");
            } catch (IllegalArgumentException | IOException e) {
                ra.addFlashAttribute("erro", "Falha ao anexar a resposta: " + e.getMessage());
                return "redirect:/processos/" + id + "#respostas";
            }
        }
        try {
            processoService.retomarAposInformacao(id);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
            return "redirect:/processos/" + id + "#respostas";
        }
        auditoria.registrar("ANALISE_RETOMADA",
            "Processo " + p.getNumero() + " - pareceres em 'Solicita informacao' reabertos como pendencia limpa");
        // Apos retomar, tenta decisao automatica caso os votos ja formem maioria
        // (pode ocorrer quando so um medico havia pedido info e os demais ja votaram).
        Processo pRetomado;
        try {
            pRetomado = processoService.tentarDecisaoAutomatica(id);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
            return "redirect:/processos/" + id + "#respostas";
        }
        if (pRetomado.getStatus().isFinalizado()) {
            try { gerarDocumentosFinais(id); }
            catch (IllegalStateException e) { ra.addFlashAttribute("erro", e.getMessage()); }
            auditoria.registrar("PROCESSO_DECIDIDO",
                "Processo " + pRetomado.getNumero() + " - decisao automatica: "
                + pRetomado.getStatus().getDescricao());
            ra.addFlashAttribute("msg",
                "Informação complementar recebida. Análise retomada e decisão automática aplicada: "
                + pRetomado.getStatus().getDescricao() + ".");
            return "redirect:/processos/" + id;
        }
        ra.addFlashAttribute("msg",
            "Informação complementar recebida. Análise retomada - registre os pareceres definitivos.");
        return "redirect:/processos/" + id + "#respostas";
    }

    /**
     * Etapa 2 (Envio): registra a data de envio de hoje para os 3 medicos,
     * gera o PDF consolidado (documentos clinicos anonimizados + cabecalho
     * carimbado) e dispara automaticamente o convite ao Portal do Avaliador
     * para cada medico com parecer pendente. Sem documento clinico PDF nao ha o
     * que enviar: bloqueia.
     *
     * <p>O convite roda so depois de {@code registrar} ter commitado e nunca
     * derruba a operacao: avaliador sem e-mail ou falha de SMTP viram flash
     * {@code aviso}, com o envio ja gravado. Reenvio manual continua disponivel
     * no card de Respostas.
     *
     * <p>Sem {@code @Transactional}: {@code registroEnvioService.registrar} e
     * auto-suficiente (transacao propria) e devolve erro/sucesso como valor,
     * sem excecao; o restante do metodo so le campos escalares do processo.
     */
    @PostMapping("/{id}/registrar-envio")
    public String registrarEnvio(@PathVariable Long id,
                                 RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#envio";
        }
        RegistroEnvioService.RegistroEnvioResultado resultado = registroEnvioService.registrar(id);
        if (!resultado.ok()) {
            ra.addFlashAttribute("erro", resultado.mensagemErro());
            return "redirect:/processos/" + id + "#envio";
        }
        // Convite ao Portal do Avaliador: disparado DEPOIS de registrar() ter
        // commitado, nunca dentro da transacao dele - falha de SMTP nao pode
        // desfazer o envio ja gravado (ver javadoc de enviarConvitesAvaliadores).
        RegistroEnvioService.ConvitesResultado convites =
            registroEnvioService.enviarConvitesAvaliadores(id);

        List<String> avisos = new ArrayList<>();
        if (!resultado.avisos().isEmpty()) {
            avisos.add("Estes documentos clínicos ficaram de fora do PDF consolidado: "
                + String.join(", ", resultado.avisos()) + ".");
        }
        if (!convites.avisos().isEmpty()) {
            avisos.add("O convite ao Portal do Avaliador não foi enviado a: "
                + String.join(", ", convites.avisos())
                + ". Use o lembrete manual no card de Respostas depois de corrigir.");
        }
        if (!avisos.isEmpty()) {
            ra.addFlashAttribute("aviso", String.join(" ", avisos));
        }
        String msg = resultado.mensagemSucesso();
        if (convites.enviados() > 0) {
            msg += " Convite ao Portal do Avaliador enviado a " + convites.enviados()
                + (convites.enviados() == 1 ? " avaliador." : " avaliadores.");
        }
        ra.addFlashAttribute("msg", msg);
        return "redirect:/processos/" + id + "#envio";
    }

    /**
     * Anexa um documento clinico ANONIMIZADO (sem nome do paciente) que sera
     * consolidado, junto com a folha-rosto, no PDF unico enviado aos avaliadores
     * no passo 2 (envio). Mantem o operador na aba Envio.
     *
     * <p>Sem {@code @Transactional}: o {@code try/catch} envolve
     * {@code anexoStorage.salvar} (metodo {@code @Transactional} que lanca
     * {@code IllegalArgumentException} para arquivo vazio/extensao proibida) -
     * com transacao de controller esse caminho terminava em 500
     * ({@code UnexpectedRollbackException}) em vez do flash "Falha ao anexar
     * documento clinico".
     */
    @PostMapping("/{id}/documento-clinico")
    public String anexarDocumentoClinico(@PathVariable Long id,
                                         @RequestParam("arquivo") MultipartFile arquivo,
                                         @RequestParam(required = false) String descricao,
                                         RedirectAttributes ra) {
        Processo p = processoService.buscar(id);
        if (bloqueadoPorEncerrado(p, ra)) {
            return "redirect:/processos/" + id + "#envio";
        }
        try {
            String desc = (descricao != null && !descricao.isBlank())
                ? descricao : "Documento clínico anonimizado para os avaliadores";
            anexoStorage.salvar(p, TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR, desc, arquivo);
            auditoria.registrar("ANEXO_ADICIONADO",
                "Processo " + p.getNumero() + " - " + TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR.getDescricao());
            ra.addFlashAttribute("msg", "Documento clínico anexado. Será consolidado no PDF dos avaliadores ao registrar o envio.");
        } catch (IllegalArgumentException | IOException e) {
            ra.addFlashAttribute("erro", "Falha ao anexar documento clínico: " + e.getMessage());
        }
        return "redirect:/processos/" + id + "#envio";
    }

    /**
     * Registra a decisao final (Deferido/Indeferido/Cancelado).
     *
     * <p><b>Sem @Transactional; a leitura que precisa de sessao aberta fica num
     * bloco proprio.</b> As validacoes de negocio navegam colecoes LAZY
     * ({@code ProcessoValidator} le pareceres e anexos), por isso rodam dentro
     * de {@link #txTemplate}; a decisao em si e commitada pela transacao do
     * proprio {@code processoService.decidir}; e a geracao dos PDFs finais -
     * unico trecho com {@code try/catch} - roda em transacao separada
     * ({@link #gerarDocumentosFinais}). Assim uma falha ao gerar oficio/
     * relatorio vira aviso na tela sem nunca desfazer a decisao ja gravada
     * (antes, com a transacao de classe, a decisao ia junto no rollback).
     */
    @PostMapping("/{id}/decidir")
    public String decidir(@PathVariable Long id,
                          @RequestParam StatusProcesso decisao,
                          @RequestParam(required = false) String motivoIndeferimento,
                          HttpServletRequest request,
                          RedirectAttributes ra) {
        // So aceita decisoes reais; estados de andamento nao sao "decisoes".
        if (decisao != StatusProcesso.DEFERIDO
                && decisao != StatusProcesso.INDEFERIDO
                && decisao != StatusProcesso.CANCELADO) {
            ra.addFlashAttribute("erro", "Decisão inválida: escolha Deferido, Indeferido ou Cancelado.");
            return "redirect:/processos/" + id;
        }
        if (decisao == StatusProcesso.INDEFERIDO
                && (motivoIndeferimento == null || motivoIndeferimento.isBlank())) {
            ra.addFlashAttribute("erro", "Indeferimento exige o motivo.");
            return "redirect:/processos/" + id;
        }
        // Regras de negocio centralizadas em ProcessoValidator (mesmas mensagens
        // do servico). A ancora do redirect distingue pausa (#respostas) da
        // contagem de votos (topo), por isso os grupos sao consultados
        // separadamente. Tudo dentro de UMA transacao curta: o validator navega
        // processo.pareceres (LAZY) e nao ha mais transacao de classe.
        Bloqueio bloqueio = txTemplate.execute(status -> {
            Processo atual = processoService.buscar(id);
            if (validator.edicaoBloqueada(atual)) {
                return new Bloqueio(ProcessoValidator.MSG_ENCERRADO, "");
            }
            var pausa = validator.validarPausaDecisao(atual, decisao);
            if (pausa.isPresent()) {
                return new Bloqueio(pausa.get(), "#respostas");
            }
            var votos = validator.validarContagemVotos(atual, decisao);
            if (votos.isPresent()) {
                return new Bloqueio(votos.get(), "");
            }
            return null;
        });
        if (bloqueio != null) {
            ra.addFlashAttribute("erro", bloqueio.mensagem());
            return "redirect:/processos/" + id + bloqueio.ancora();
        }
        Processo p = processoService.decidir(id, decisao, motivoIndeferimento);
        try { gerarDocumentosFinais(id); }
        catch (IllegalStateException e) { ra.addFlashAttribute("erro", e.getMessage()); }
        auditoria.registrar("PROCESSO_DECIDIDO",
            "Processo " + p.getNumero() + " - " + decisao.getDescricao(),
            request.getRemoteAddr());
        ra.addFlashAttribute("msg", "Decisão registrada: " + decisao.getDescricao());
        return "redirect:/processos/" + id;
    }

    /**
     * Finaliza a resposta ao solicitante de forma automatica: envia o e-mail
     * com o template adequado (deferido/indeferido) + o anexo correspondente.
     * Exige que o documento obrigatorio (COMPROVANTE_SNT / OFICIO_INDEFERIMENTO)
     * ja esteja anexado. Nao e bloqueado por processo encerrado — esta etapa
     * (5-6) e justamente a papelada pos-decisao.
     */
    @PostMapping("/{id}/finalizar")
    // Sem transacao NENHUMA: finalizarResposta() e auto-suficiente
    // (@Transactional propria) e so usa campos simples de p (getNumero/
    // getStatus) depois, sem depender de lazy-loading compartilhado com o
    // service. Com a transacao de classe que existia aqui, uma
    // IllegalStateException de negocio dentro de finalizarResposta (ex.: falha
    // de SMTP) marcava a transacao como rollback-only; o catch abaixo
    // interceptava normalmente, mas o commit no fim do metodo falhava com
    // UnexpectedRollbackException (500 cru em vez do flash de erro esperado) -
    // achado rodando o e2e real sem SMTP configurado. A correcao de entao foi
    // @Transactional(NOT_SUPPORTED) para SUSPENDER a transacao de classe;
    // removida a anotacao de classe (2026-07-29) nao ha mais nada para
    // suspender e ela virou no-op, entao saiu junto. O comportamento e o mesmo.
    public String finalizar(@PathVariable Long id, HttpServletRequest request, RedirectAttributes ra) {
        try {
            Processo p = processoService.finalizarResposta(id);
            auditoria.registrar("RESPOSTA_ENVIADA_SOLICITANTE",
                "Processo " + p.getNumero() + " - finalizacao automatica: " + p.getStatus().getDescricao(),
                request.getRemoteAddr());
            ra.addFlashAttribute("msg", "Resposta enviada ao solicitante com sucesso.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/processos/" + id + "#finalizacao";
    }

    /**
     * Sugere, via IA, um texto para o motivo do indeferimento com base nas
     * justificativas dos pareceres desfavoraveis. O operador revisa/edita
     * antes de registrar a decisao - a IA nao decide nada, so redige.
     */
    @PostMapping("/{id}/sugestao-motivo")
    @ResponseBody
    @Transactional(readOnly = true)
    public IaTextoResponse sugestaoMotivo(@PathVariable Long id) {
        if (!geminiService.isDisponivel()) {
            return IaTextoResponse.erro("Assistência por IA não configurada.");
        }
        Processo p;
        try { p = processoService.buscar(id); }
        catch (RuntimeException e) { return IaTextoResponse.erro("Processo não encontrado."); }
        String justificativas = p.getPareceres().stream()
            .filter(par -> par.getResultado() == ResultadoParecer.NAO_FAVORAVEL)
            .map(Parecer::getJustificativa)
            .filter(j -> j != null && !j.isBlank())
            .collect(java.util.stream.Collectors.joining("\n---\n"));
        if (justificativas.isBlank()) {
            return IaTextoResponse.erro("Nenhuma justificativa de parecer desfavorável encontrada para basear a sugestão.");
        }
        String prompt = "Voce e um assistente administrativo de um orgao publico de saude do Brasil. "
            + "Com base nas justificativas tecnicas abaixo, dadas por medicos avaliadores que "
            + "consideraram um pedido de urgencia renal desfavoravel, redija um texto formal, "
            + "objetivo e em portugues do Brasil para o campo \"motivo do indeferimento\" de um "
            + "oficio administrativo. Nao invente informacoes que nao estejam nas justificativas. "
            + "Responda apenas com o texto do motivo, sem introducao nem explicacoes.\n\n"
            + "Justificativas dos avaliadores:\n" + justificativas;
        return geminiService.perguntar(prompt)
            .map(IaTextoResponse::sucesso)
            .orElseGet(() -> IaTextoResponse.erro("Falha ao consultar a IA. Tente novamente."));
    }

    /**
     * Dispara manualmente um lembrete por e-mail a UM avaliador com parecer
     * pendente. Nunca automatico - sempre um clique explicito do operador.
     */
    @PostMapping("/{id}/lembrete-avaliador")
    @ResponseBody
    // Sem @Transactional: nao navega colecao LAZY (Parecer.membro e EAGER e
    // buscarParecer so le o id do processo do proxy, sem inicializa-lo) e as
    // escritas (auditoria, registrarLembreteAvaliador) ja rodam em transacao
    // propria do repositorio/servico.
    public AcaoResponse lembreteAvaliador(@PathVariable Long id, @RequestParam Long parecerId) {
        Processo p;
        try { p = processoService.buscar(id); }
        catch (RuntimeException e) { return AcaoResponse.erro("Processo não encontrado."); }
        if (validator.edicaoBloqueada(p)) {
            return AcaoResponse.erro(ProcessoValidator.MSG_ENCERRADO);
        }
        Parecer parecer = processoService.buscarParecer(id, parecerId).orElse(null);
        if (parecer == null) {
            return AcaoResponse.erro("Parecer não encontrado neste processo.");
        }
        if (parecer.getResultado() != null) {
            return AcaoResponse.erro("Este avaliador já registrou o parecer.");
        }
        MembroUrgenciaRenal membro = parecer.getMembro();
        if (membro.getEmail() == null || membro.getEmail().isBlank()) {
            return AcaoResponse.erro("Avaliador sem e-mail cadastrado: " + membro.getNome() + ".");
        }
        EmailTemplate template = emailTemplateService.emailLembreteAvaliador(p, membro);
        boolean ok = emailSenderService.enviar(membro.getEmail(), template.assunto(), template.corpo());
        if (ok) {
            processoService.registrarLembreteAvaliador(parecer.getId());
            auditoria.registrar("LEMBRETE_AVALIADOR_ENVIADO",
                "Processo " + p.getNumero() + " - " + membro.getNome());
            return AcaoResponse.sucesso("Lembrete enviado para " + membro.getNome() + ".");
        }
        auditoria.registrar("LEMBRETE_AVALIADOR_FALHA",
            "Processo " + p.getNumero() + " - " + membro.getNome());
        return AcaoResponse.erro("Falha ao enviar o e-mail. Verifique a configuração de SMTP.");
    }

    /**
     * Dispara manualmente um lembrete por e-mail a TODOS os avaliadores com
     * parecer pendente neste processo (envio em lote, ainda assim manual).
     */
    @PostMapping("/{id}/lembrete-pendentes")
    @ResponseBody
    // Sem @Transactional, mesmo motivo de lembreteAvaliador: a consulta
    // pareceresPendentesComEmail ja devolve os pareceres com o membro (EAGER)
    // carregado, e a auditoria/registrarLembreteAvaliador gravam em transacao
    // propria.
    public AcaoResponse lembretePendentes(@PathVariable Long id) {
        Processo p;
        try { p = processoService.buscar(id); }
        catch (RuntimeException e) { return AcaoResponse.erro("Processo não encontrado."); }
        if (validator.edicaoBloqueada(p)) {
            return AcaoResponse.erro(ProcessoValidator.MSG_ENCERRADO);
        }
        var pendentes = processoService.pareceresPendentesComEmail(id);
        if (pendentes.isEmpty()) {
            return AcaoResponse.erro("Não há avaliadores com parecer pendente neste processo.");
        }
        int enviados = 0, falhas = 0, semEmail = 0;
        for (Parecer parecer : pendentes) {
            MembroUrgenciaRenal membro = parecer.getMembro();
            if (membro.getEmail() == null || membro.getEmail().isBlank()) {
                semEmail++;
                continue;
            }
            EmailTemplate template = emailTemplateService.emailLembreteAvaliador(p, membro);
            boolean ok = emailSenderService.enviar(membro.getEmail(), template.assunto(), template.corpo());
            if (ok) {
                enviados++;
                processoService.registrarLembreteAvaliador(parecer.getId());
                auditoria.registrar("LEMBRETE_AVALIADOR_ENVIADO",
                    "Processo " + p.getNumero() + " - " + membro.getNome());
            } else {
                falhas++;
                auditoria.registrar("LEMBRETE_AVALIADOR_FALHA",
                    "Processo " + p.getNumero() + " - " + membro.getNome());
            }
        }
        String msg = "Lembretes enviados: " + enviados + ". Falhas: " + falhas + ". Sem e-mail: " + semEmail + ".";
        return enviados > 0 ? AcaoResponse.sucesso(msg) : AcaoResponse.erro(msg);
    }

    /**
     * Revisa/melhora, via IA, o corpo de um texto de e-mail pronto (assunto +
     * corpo) exibido na tela de detalhe. O operador confere antes de copiar.
     *
     * ATENCAO (so relevante com app.gemini.enabled=true, desligado por
     * padrao em producao): os e-mails dirigidos ao SOLICITANTE (Deferido,
     * Indeferido, Solicita informacao) levam o NOME COMPLETO do paciente por
     * regra de negocio - o corpo inteiro, incluindo esse nome, e enviado ao
     * Gemini (API externa) para revisao. Nao ha como mitigar sem quebrar a
     * propria funcao (revisar o texto real que sera enviado com nome
     * completo); e uma troca deliberada, condicionada ao operador ligar o
     * recurso de IA conscientemente.
     */
    @PostMapping("/{id}/email/revisar-ia")
    @ResponseBody
    @Transactional(readOnly = true)
    public IaTextoResponse revisarEmailIa(@PathVariable Long id,
                                          @RequestParam String assunto,
                                          @RequestParam String corpo) {
        if (!geminiService.isDisponivel()) {
            return IaTextoResponse.erro("Assistência por IA não configurada.");
        }
        String prompt = "Voce e um assistente de redacao de um orgao publico de saude do Brasil. "
            + "Revise o e-mail abaixo (assunto e corpo), mantendo o mesmo idioma (portugues do "
            + "Brasil), o mesmo significado e todos os dados/numeros/nomes citados. Apenas melhore "
            + "clareza, formalidade e correcao gramatical - nao adicione nem remova informacoes. "
            + "Responda apenas com o corpo revisado do e-mail (sem repetir o assunto, sem "
            + "introducao, sem comentarios).\n\n"
            + "Assunto: " + assunto + "\n\nCorpo:\n" + corpo;
        return geminiService.perguntar(prompt)
            .map(IaTextoResponse::sucesso)
            .orElseGet(() -> IaTextoResponse.erro("Falha ao consultar a IA. Tente novamente."));
    }

    /**
     * Dispara manualmente, por e-mail, um dos textos prontos exibidos no
     * accordion "Textos de e-mail prontos". O destinatario e resolvido no
     * servidor pela chave do template (nunca confia em endereco vindo do
     * cliente). Para Deferido/Indeferido, bloqueia o envio se o anexo
     * obrigatorio (comprovante SNT / oficio) ainda nao existir.
     */
    @PostMapping("/{id}/email/enviar")
    @ResponseBody
    // @Transactional proprio (sem readOnly: grava auditoria apos o envio):
    // prepararEmailPronto navega processo.pareceres (destinatarios do convite)
    // e processo.anexos (validarRespostaSolicitante), ambos LAZY. Nao ha
    // try/catch em volta de servico transacional aqui, entao a transacao unica
    // nao cria o risco de rollback-only descrito no javadoc da classe.
    @Transactional
    public AcaoResponse enviarEmailPronto(@PathVariable Long id,
                                          @RequestParam String chave,
                                          @RequestParam String assunto,
                                          @RequestParam String corpo) {
        Processo p = processoService.buscar(id);
        EmailPreparado prep = prepararEmailPronto(p, chave, assunto, corpo);
        if (!prep.ok()) {
            return AcaoResponse.erro(prep.erro());
        }
        // Deferido/Indeferido: o texto do template promete "segue em anexo" o
        // comprovante SNT/oficio - envia de fato com o arquivo anexado quando
        // ele existe (prepararEmailPronto ja localizou), em vez do e-mail
        // simples que deixaria o destinatario sem o documento prometido.
        boolean ok = prep.anexo() != null
            ? emailSenderService.enviarComAnexo(prep.to()[0], prep.assunto(), prep.corpo(),
                anexoStorage.resolverArquivo(prep.anexo()).toFile(), prep.anexo().getNomeArquivo())
            : emailSenderService.enviar(prep.to(), null, prep.assunto(), prep.corpo());
        if (ok) {
            auditoria.registrar("EMAIL_ENVIADO",
                "Processo " + p.getNumero() + " - template " + chave + " -> " + prep.destinatarios());
            return AcaoResponse.sucesso("E-mail enviado para " + prep.destinatarios() + ".");
        }
        auditoria.registrar("EMAIL_ENVIO_FALHA",
            "Processo " + p.getNumero() + " - template " + chave + " -> " + prep.destinatarios());
        return AcaoResponse.erro("Falha ao enviar o e-mail. Verifique a configuração de SMTP.");
    }

    /**
     * Pre-visualiza, sem enviar, o(s) e-mail(s) que uma acao dispararia -
     * destinatario(s), assunto e corpo exatos. Alimenta o modal de confirmacao:
     * NENHUM e-mail e enviado sem o operador conferir este conteudo antes.
     * A resolucao de destinatarios e as validacoes de anexo obrigatorio usam a
     * mesma logica do envio real, garantindo que o previsto e o enviado coincidam.
     */
    @PostMapping("/{id}/email/preview")
    @ResponseBody
    @Transactional(readOnly = true)
    public EmailPreviewResponse preverEmail(@PathVariable Long id,
                                            @RequestParam String tipo,
                                            @RequestParam(required = false) String chave,
                                            @RequestParam(required = false) String assunto,
                                            @RequestParam(required = false) String corpo,
                                            @RequestParam(required = false) Long parecerId) {
        Processo p;
        try { p = processoService.buscar(id); }
        catch (RuntimeException e) { return EmailPreviewResponse.erro("Processo não encontrado."); }
        switch (tipo) {
            case "pronto" -> {
                EmailPreparado prep = prepararEmailPronto(p, chave, assunto, corpo);
                if (!prep.ok()) {
                    return EmailPreviewResponse.erro(prep.erro());
                }
                return EmailPreviewResponse.ok(List.of(
                    new EmailPreviewResponse.Mensagem(prep.destinatarios(), prep.assunto(), prep.corpo())));
            }
            case "lembrete-avaliador" -> {
                // parecerId e opcional no request (form pode submeter sem selecao);
                // findById(null) lancaria InvalidDataAccessApiUsageException (500
                // generico) em vez da mensagem amigavel abaixo - checa antes.
                if (parecerId == null) {
                    return EmailPreviewResponse.erro("Parecer não encontrado neste processo.");
                }
                Parecer parecer = processoService.buscarParecer(id, parecerId).orElse(null);
                if (parecer == null) {
                    return EmailPreviewResponse.erro("Parecer não encontrado neste processo.");
                }
                if (parecer.getResultado() != null) {
                    return EmailPreviewResponse.erro("Este avaliador já registrou o parecer.");
                }
                MembroUrgenciaRenal membro = parecer.getMembro();
                if (membro.getEmail() == null || membro.getEmail().isBlank()) {
                    return EmailPreviewResponse.erro("Avaliador sem e-mail cadastrado: " + membro.getNome() + ".");
                }
                EmailTemplate template = emailTemplateService.emailLembreteAvaliador(p, membro);
                return EmailPreviewResponse.ok(List.of(
                    new EmailPreviewResponse.Mensagem(membro.getEmail(), template.assunto(), template.corpo())));
            }
            case "lembrete-pendentes" -> {
                var pendentes = processoService.pareceresPendentesComEmail(id);
                List<EmailPreviewResponse.Mensagem> mensagens = new ArrayList<>();
                for (Parecer parecer : pendentes) {
                    MembroUrgenciaRenal membro = parecer.getMembro();
                    if (membro.getEmail() == null || membro.getEmail().isBlank()) {
                        continue;
                    }
                    EmailTemplate template = emailTemplateService.emailLembreteAvaliador(p, membro);
                    mensagens.add(new EmailPreviewResponse.Mensagem(
                        membro.getEmail(), template.assunto(), template.corpo()));
                }
                if (mensagens.isEmpty()) {
                    return EmailPreviewResponse.erro(
                        "Não há avaliadores com parecer pendente e e-mail cadastrado neste processo.");
                }
                return EmailPreviewResponse.ok(mensagens);
            }
            default -> {
                return EmailPreviewResponse.erro("Tipo de pré-visualização desconhecido: " + tipo);
            }
        }
    }

    /**
     * Guarda de edicao: se o processo esta encerrado, registra o flash de erro e
     * retorna true (o chamador deve redirecionar sem efetivar a alteracao). Usada
     * nas etapas 1 a 4 e nos lembretes; as etapas 5-6 e os e-mails de resposta ao
     * solicitante NAO usam esta guarda (continuam liberados apos a decisao).
     */
    private boolean bloqueadoPorEncerrado(Processo p, RedirectAttributes ra) {
        if (validator.edicaoBloqueada(p)) {
            ra.addFlashAttribute("erro", ProcessoValidator.MSG_ENCERRADO);
            return true;
        }
        return false;
    }

    /**
     * Resolve destinatarios e valida anexos obrigatorios de um texto de e-mail
     * pronto, sem enviar. Fonte unica usada tanto pela pre-visualizacao quanto
     * pelo envio real - assim o que o operador confere e exatamente o que sai.
     */
    private EmailPreparado prepararEmailPronto(Processo p, String chave, String assunto, String corpo) {
        switch (chave) {
            case "convite-avaliador", "convite-portal" -> {
                var emails = p.getPareceres().stream()
                    .map(par -> par.getMembro().getEmail())
                    .filter(e -> e != null && !e.isBlank())
                    .toArray(String[]::new);
                if (emails.length == 0) {
                    return EmailPreparado.erro("Nenhum avaliador deste processo tem e-mail cadastrado.");
                }
                return EmailPreparado.ok(emails, String.join(", ", emails), assunto, corpo);
            }
            case "solicita-info", "deferido", "indeferido" -> {
                String email = p.getSolicitanteEmail();
                if (email == null || email.isBlank()) {
                    return EmailPreparado.erro("Processo sem e-mail do solicitante cadastrado.");
                }
                // Templates "deferido"/"indeferido" so sao oferecidos quando
                // p.getStatus() ja e o correspondente (EmailTemplateService),
                // entao a mesma checagem de ProcessoValidator.validarRespostaSolicitante
                // (usada em ProcessoService.confirmarRespostaSolicitante) vale aqui -
                // fonte unica da regra, sem reimplementar SNT/oficio inline.
                if (("deferido".equals(chave) || "indeferido".equals(chave))) {
                    var bloqueio = validator.validarRespostaSolicitante(p);
                    if (bloqueio.isPresent()) {
                        return EmailPreparado.erro(bloqueio.get().replace(
                            "antes de confirmar a resposta ao solicitante", "antes de enviar este e-mail"));
                    }
                }
                // Os templates "deferido"/"indeferido" (EmailTemplateService) dizem
                // explicitamente ao solicitante "segue em anexo" o comprovante
                // SNT/oficio - o e-mail precisa de fato levar esse anexo, senao o
                // texto promete algo que nao chega. TipoAnexo ja foi confirmado
                // presente pela checagem de validarRespostaSolicitante acima.
                Anexo anexo = "deferido".equals(chave)
                    ? anexoStorage.buscarUltimoPorTipo(p.getId(), TipoAnexo.COMPROVANTE_SNT)
                    : "indeferido".equals(chave)
                        ? anexoStorage.buscarUltimoPorTipo(p.getId(), TipoAnexo.OFICIO_INDEFERIMENTO)
                        : null;
                return EmailPreparado.ok(new String[]{email}, email, assunto, corpo, anexo);
            }
            default -> {
                return EmailPreparado.erro("Tipo de e-mail desconhecido: " + chave);
            }
        }
    }

    /**
     * Motivo pelo qual a decisao foi recusada + ancora do redirect. Devolvido
     * pelo bloco transacional de validacao de {@link #decidir} para que as
     * mensagens de flash sejam gravadas FORA da transacao (RedirectAttributes
     * nao tem nada a ver com o banco, e assim o bloco fica so com leitura).
     */
    private record Bloqueio(String mensagem, String ancora) {}

    /** Resultado interno de {@link #prepararEmailPronto}: pronto para enviar ou erro. */
    private record EmailPreparado(String[] to, String destinatarios, String assunto, String corpo,
                                  Anexo anexo, String erro) {
        static EmailPreparado ok(String[] to, String destinatarios, String assunto, String corpo) {
            return new EmailPreparado(to, destinatarios, assunto, corpo, null, null);
        }
        static EmailPreparado ok(String[] to, String destinatarios, String assunto, String corpo, Anexo anexo) {
            return new EmailPreparado(to, destinatarios, assunto, corpo, anexo, null);
        }
        static EmailPreparado erro(String erro) {
            return new EmailPreparado(null, null, null, null, null, erro);
        }
        boolean ok() {
            return erro == null;
        }
    }
}

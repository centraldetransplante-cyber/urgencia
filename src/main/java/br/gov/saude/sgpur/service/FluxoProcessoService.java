package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.service.dto.EstadoEtapa;
import br.gov.saude.sgpur.service.dto.EtapaFluxo;
import br.gov.saude.sgpur.service.dto.EtapaFluxo.Chave;
import br.gov.saude.sgpur.service.dto.PassoWizard;
import br.gov.saude.sgpur.service.dto.RegraDecisao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Monta, em tempo real, a lista de etapas do processo, sinalizando o que ja
 * foi concluido, qual e a etapa atual e o que ainda falta. Reflete o fluxo:
 * Envio aos 3 medicos -> Respostas -> Decisao -> (Oficio de indeferimento,
 * se reprovado, ou Comprovante SNT, se deferido) -> Resposta ao solicitante.
 *
 * <p><b>Recebimento fundido em Envio (2026-08-05).</b> Ate entao existia uma
 * etapa "Recebimento" propria, primeira do fluxo. Desde 2026-07-27 ela era
 * SEMPRE automatica e concluida (todo {@code Processo} nasce de uma {@code
 * SolicitacaoOnline} convertida pelo Portal do Solicitante) - nao sobrava
 * nenhuma acao real do operador nessa aba, so uma etiqueta sempre-verde
 * antes do Envio. Removida como etapa/aba propria; o link "Ver solicitacao
 * original" que vivia la (ver {@link #veioDoPortal}) migrou para dentro da
 * aba Envio. O fluxo passou de 6 para 5 conceitos no checklist: Envio,
 * Respostas, Decisao, Oficio/Comprovante SNT, Resposta ao solicitante.</p>
 */
@Service
public class FluxoProcessoService {

    private static final Logger log = LoggerFactory.getLogger(FluxoProcessoService.class);

    private final ProcessoService processoService;
    private final SolicitacaoOnlineRepository solicitacaoOnlineRepository;
    private final ProcessoValidator processoValidator;

    public FluxoProcessoService(ProcessoService processoService,
                                 SolicitacaoOnlineRepository solicitacaoOnlineRepository,
                                 ProcessoValidator processoValidator) {
        this.processoService = processoService;
        this.solicitacaoOnlineRepository = solicitacaoOnlineRepository;
        this.processoValidator = processoValidator;
    }

    /**
     * true se o processo foi originado do Portal do Solicitante (convertido a
     * partir de uma {@code SolicitacaoOnline}). Usado para exibir o link "Ver
     * solicitacao original" no card de Envio da tela de detalhe (o
     * Recebimento nao tem mais aba propria - ver javadoc da classe) - nao
     * influencia mais nenhum gating (todo processo nasce do Portal).
     */
    public boolean veioDoPortal(Processo p) {
        return p.getId() != null && solicitacaoOnlineRepository.existsByProcessoGeradoId(p.getId());
    }

    public List<EtapaFluxo> montarEtapas(Processo p) {
        List<EtapaFluxo> etapas = new ArrayList<>();
        boolean anterioresConcluidas = true;
        // Processo ja finalizado (Deferido/Indeferido/Cancelado) nao deve
        // ficar "preso" numa etapa anterior por causa de uma exigencia de
        // anexo criada DEPOIS que o processo foi decidido (ex.: capa
        // automatica so passou a existir em processos recebidos a partir de
        // 2026-07-09; processos antigos ja encerrados nao tem esse anexo e
        // nao devem exibir progresso 0% por isso). Para processos ja
        // encerrados, a cascata de "anteriores concluidas" e ignorada.
        boolean finalizado = p.getStatus() != null && p.getStatus().isFinalizado();

        // 1. Envio aos 3 medicos (data de envio registrada em todos os pareceres).
        //    O Recebimento (que existia como etapa 1 antes de 2026-08-05) foi
        //    fundido aqui: era sempre automatico e concluido (todo Processo
        //    nasce de uma SolicitacaoOnline convertida pelo Portal do
        //    Solicitante), sem nenhuma acao real do operador - so uma
        //    etiqueta sempre-verde antes desta etapa. veioDoPortal(p)
        //    continua existindo so para achar o link "Ver solicitacao
        //    original" na tela de detalhe (agora dentro da aba Envio).
        //    Exige ao menos um documento clinico (PDF) anexado: o PDF dos
        //    avaliadores e montado SO com esses documentos (com cabecalho
        //    carimbado), sem folha-rosto gerada pelo sistema.
        int totalMedicos = p.getPareceres().size();
        long enviadosCount = p.getPareceres().stream().filter(par -> par.getDataEnvio() != null).count();
        boolean temDocClinicoPdf = anexosSeguro(p).stream()
            .anyMatch(a -> a.getTipo() == TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR
                && a.getContentType() != null
                && a.getContentType().toLowerCase().contains("application/pdf"));
        boolean enviado = totalMedicos == ProcessoService.AVALIADORES_POR_PROCESSO
            && enviadosCount == totalMedicos;
        String detEnvio;
        if (totalMedicos != ProcessoService.AVALIADORES_POR_PROCESSO) {
            detEnvio = "Processo deve ter " + ProcessoService.AVALIADORES_POR_PROCESSO
                + " médicos (atual: " + totalMedicos + ").";
        } else if (!temDocClinicoPdf && !enviado) {
            // Acentuacao deliberadamente NAO aplicada aqui (2026-08-11): este
            // literal exato e asserido, fora do escopo deste arquivo, por
            // HomeControllerTest e ProcessoListaControllerTest - acentuar so
            // aqui quebraria esses dois testes sem poder corrigi-los (fora do
            // escopo desta tarefa). Ver Achado 5 do relatorio de status.
            detEnvio = "Anexe o(s) documento(s) clinico(s) (PDF) para gerar o processo dos avaliadores.";
        } else if (!enviado) {
            detEnvio = "Registre o envio aos médicos (faltam " + (totalMedicos - enviadosCount)
                + " de " + totalMedicos + ").";
        } else {
            detEnvio = "Enviado aos " + totalMedicos + " médicos.";
        }
        etapas.add(montar(Chave.ENVIO, "Envio aos 3 médicos", "send-fill", enviado, anterioresConcluidas, detEnvio));
        anterioresConcluidas = finalizado || (anterioresConcluidas && enviado);

        // 2. Respostas dos medicos. Por MAIORIA SIMPLES (2 de 3), assim que ha
        //    2 votos do mesmo tipo a etapa esta pronta: nao e preciso aguardar
        //    o 3o parecer para decidir.
        //
        //    ATENCAO: "maioria formada" (sugerirDecisao) so conta votos - nao
        //    sabe nada sobre a pausa SOLICITA_INFORMACAO. Um processo pode ter
        //    2 favoraveis E, ao mesmo tempo, um 3o parecer pedindo informacao
        //    complementar (pausa ainda ativa, ninguem retomou a analise). Sem
        //    o `pausaBloqueiaDecisao` abaixo, o texto dizia "pronto para
        //    decidir" mesmo com a decisao de fato TRAVADA pela etapa 2b logo
        //    depois (liberadoDecisao=false) - lido por quem nao sabe da regra
        //    do coordenador, parecia que o sistema ia ignorar o pedido de
        //    informacao do 3o avaliador. So NAO esta bloqueada quando o
        //    coordenador CET-RS ja votou favoravel (excecao que defere mesmo
        //    pausado - ver ProcessoValidator.validarPausaDecisao).
        long respondidos = processoService.contarRespondidos(p);
        long favoraveis = processoService.contarFavoraveis(p);
        var sugestaoResp = processoService.sugerirDecisao(p);
        boolean maioria = sugestaoResp.isPresent();
        boolean todasRespondidas = totalMedicos > 0 && respondidos == totalMedicos;
        // Achado 7 (relatorio de status 2026-08-11): pausa ativa = status OU
        // fato (mesmo predicado usado por ProcessoValidator.
        // validarPausaDecisao), nao mais so o campo derivado `status`. Evita
        // que a timeline/wizard destravem uma aba que o proprio `decidir`
        // recusaria, caso o campo `status` algum dia dessincronize do fato
        // (ver ProcessoValidator.temPedidoInformacaoAtivo).
        boolean pausaAtiva = p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO
            || processoValidator.temPedidoInformacaoAtivo(p);
        boolean pausaBloqueiaDecisao = pausaAtiva
            && !processoService.temVotoCoordenadorFavoravel(p);
        boolean decididoResp = p.getStatus().isFinalizado();
        // "Solicita informacao" NAO e um veredito: o parecer volta a ser
        // pendencia limpa em retomarAposInformacao. Mas contarRespondidos()
        // conta qualquer resultado != null, entao um processo com N pedidos de
        // informacao (2 de 3 no caso real do processo 12/2026; nada impede 3 de
        // 3) contava como "todos os pareceres recebidos" e esta etapa ficava
        // CONCLUIDA/verde - a tela do operador dizia "3 pareceres recebidos"
        // com UM voto de verdade, e o wizard liberava visualmente o caminho
        // ate a Decisao que `decidir` recusaria em seguida. Enquanto a pausa
        // estiver ativa, so a MAIORIA de verdade conclui esta etapa. Processo
        // ja decidido (ex.: deferido pelo coordenador durante a pausa) nao
        // regride para "pendente" - mantem o comportamento historico.
        boolean respostasOk = maioria || (todasRespondidas && (!pausaAtiva || decididoResp));
        String detResp;
        if (totalMedicos == 0) {
            detResp = "Aguardando definição dos médicos.";
        } else if (decididoResp) {
            // Achado 3 do relatorio de vistoria de brechas (2026-08-10): o
            // texto antigo continuava dizendo "Maioria formada ... pronto
            // para decidir" mesmo com o processo JA decidido (as vezes por 1
            // voto so, do coordenador) - autocontraditorio, e reaparecia no
            // Relatorio Final (secao "4. Andamento do processo") uma pagina
            // depois de explicar corretamente a excecao do coordenador.
            // Processo decidido nunca mais fica "pronto para decidir": conta
            // a regra que de fato decidiu, via a mesma fonte unica usada
            // pelo Relatorio Final/dossie/auditoria.
            RegraDecisao regra = processoService.regraAplicada(p);
            detResp = "Processo já decidido (" + p.getStatus().getDescricao() + "). "
                + regra.getRotuloLongo();
        } else if (maioria && pausaBloqueiaDecisao) {
            detResp = "Maioria formada (" + sugestaoResp.get().getDescricao()
                + ") mas a decisão está BLOQUEADA: aguardando informação complementar "
                + "de outro avaliador. Favoráveis: " + favoraveis + ".";
        } else if (maioria && processoService.temVotoCoordenadorFavoravel(p)) {
            // Idem: nao e "maioria formada" quando quem decidiu foi o voto
            // isolado do coordenador (1 voto so).
            detResp = "Voto favorável do Coordenador da CET-RS registrado - pronto para decidir "
                + "(" + sugestaoResp.get().getDescricao() + " isoladamente, sem precisar da maioria).";
        } else if (maioria) {
            detResp = "Maioria formada (" + sugestaoResp.get().getDescricao()
                + ") - pronto para decidir. Favoráveis: " + favoraveis + ".";
        } else if (pausaAtiva && todasRespondidas) {
            // Todos os pareceres "chegaram", mas parte deles e pedido de
            // informacao (nao veredito) - dizer "N pareceres recebidos" aqui,
            // como o texto antigo fazia, sugeria que so faltava decidir.
            long pedidosAbertos = p.getPareceres().stream()
                .filter(par -> par.getResultado() == ResultadoParecer.SOLICITA_INFORMACAO)
                .count();
            detResp = "Processo PAUSADO: " + pedidosAbertos + " de " + totalMedicos
                + " avaliadores pediram informação complementar (esses pareceres voltam a ser "
                + "votados depois que a análise for retomada). Favoráveis até agora: "
                + favoraveis + ".";
        } else if (pausaAtiva) {
            // Achado 1 (relatorio de status 2026-08-11): quando a pausa
            // chega ANTES de a maioria se formar, o texto antigo dizia so
            // "Faltam N pareceres" - dando a entender que o 3o voto
            // destravaria a decisao. Nao destrava: quem destrava e o
            // solicitante enviar a informacao e o operador retomar a
            // analise (ver Chave.INFO_COMPLEMENTAR logo abaixo).
            detResp = "Processo PAUSADO aguardando informação complementar. Faltam "
                + (totalMedicos - respondidos) + " de " + totalMedicos
                + " pareceres — mas o voto pendente não libera a decisão sozinho.";
        } else if (!todasRespondidas) {
            detResp = "Faltam " + (totalMedicos - respondidos) + " de " + totalMedicos
                + " pareceres. Favoráveis até agora: " + favoraveis + ".";
        } else {
            detResp = respondidos + " pareceres recebidos. Favoráveis: " + favoraveis + ".";
        }
        // Achado 1 (continuacao): quando a pausa chega ANTES da maioria se
        // formar (!respostasOk), "Respostas dos medicos" nao pode continuar
        // ATUAL ao mesmo tempo que "Informacao complementar" tambem vira
        // ATUAL logo abaixo (forcado) - teriam DUAS etapas "atuais"
        // simultaneas, e pendenciaAberta() (que devolve a PRIMEIRA etapa
        // ATUAL da lista) continuaria escolhendo esta aqui, por vir antes na
        // lista. Rebaixa a exibicao desta etapa para BLOQUEADA nesse caso
        // especifico (sem mudar `respostasOk`/a cascata usada pelas etapas
        // seguintes, que continuam identicas a antes) - a etapa continua
        // legitimamente pendente (nao fica CONCLUIDA/verde), so deixa de
        // "roubar" o holofote de ATUAL da etapa da pausa, que e a acao
        // presente de verdade. Quando a pausa chega DEPOIS da maioria
        // (respostasOk=true), esta etapa fica CONCLUIDA normalmente - nada
        // muda.
        boolean respostasDisplayAnterioresConcluidas = anterioresConcluidas && !(pausaAtiva && !respostasOk);
        etapas.add(montar(Chave.RESPOSTAS, "Respostas dos médicos", "chat-square-text-fill",
            respostasOk, respostasDisplayAnterioresConcluidas, detResp));
        anterioresConcluidas = finalizado || (anterioresConcluidas && respostasOk);

        // 2b. Informacao complementar (apenas enquanto um medico pediu mais dados).
        //     Funciona como uma PAUSA: bloqueia a decisao ate o solicitante
        //     responder e o operador retomar a analise.
        //
        //     Achado 1 (relatorio de status 2026-08-11): esta etapa deve ser
        //     SEMPRE a etapa ATUAL enquanto a pausa estiver ativa - e
        //     literalmente a situacao presente do processo -, mesmo quando a
        //     etapa anterior ("Respostas dos medicos") ainda nao concluiu
        //     (pausa chegou ANTES da maioria se formar). Por isso o `true`
        //     fixo abaixo (nao o `anterioresConcluidas` corrente) so para
        //     ESTA chamada de `montar` - as etapas seguintes (Decisao,
        //     Finalizacao) continuam recebendo `anterioresConcluidas = false`
        //     logo em seguida, entao a pausa continua travando o que vem
        //     depois. Antes desta correcao, a pausa "antes da maioria" ficava
        //     BLOQUEADA (cinza, como se fosse futura) e `pendenciaAberta`
        //     apontava "Respostas dos medicos" - uma pendencia que cobrar o
        //     3o medico nao resolve, porque quem destrava e o
        //     retomarAposInformacao.
        if (pausaAtiva) {
            etapas.add(montar(Chave.INFO_COMPLEMENTAR, "Informação complementar", "question-circle-fill",
                false, true,
                "Aguardando informação complementar do solicitante. Envie o pedido, "
                + "anexe a resposta recebida e retome a análise para liberar a decisão."));
            // bloqueia tudo o que vem depois enquanto nao for retomado
            anterioresConcluidas = false;
        }

        // 3. Decisao final
        boolean decidido = p.getStatus().isFinalizado();
        String detDecisao;
        if (decidido) {
            // Mesma fonte unica do detResp acima: descreve a regra que de
            // fato decidiu, em vez de um "Processo Deferido." generico que
            // nao distingue maioria de excecao do coordenador.
            RegraDecisao regra = processoService.regraAplicada(p);
            detDecisao = "Processo " + p.getStatus().getDescricao() + " - " + regra.getRotuloLongo();
        } else if (pausaBloqueiaDecisao) {
            // Mesmo motivo do detResp acima: maioria formada nao significa
            // decisao liberada enquanto a pausa estiver ativa (exceto pelo
            // voto favoravel do coordenador).
            var sugestao = processoService.sugerirDecisao(p);
            detDecisao = sugestao
                .map(s -> "Sugestão automática: " + s.getDescricao()
                    + " - BLOQUEADA pela pausa (aguardando informação complementar).")
                .orElse("Aguardando informação complementar do solicitante.");
        } else if (processoService.sugerirDecisao(p).isPresent()
                && processoService.temVotoCoordenadorFavoravel(p)) {
            // Achado 3 (segunda evidencia do relatorio de vistoria): apos
            // reabertura, um processo com 1 unico voto (do coordenador) NAO
            // pode dizer "regra 2 de 3 favoraveis" - a sugestao aqui vem da
            // excecao regimental, nao da maioria.
            detDecisao = "Sugestão automática: " + processoService.sugerirDecisao(p).get().getDescricao()
                + " (voto favorável isolado do Coordenador da CET-RS, dispensa a maioria de "
                + ProcessoService.FAVORAVEIS_PARA_DEFERIR + " de "
                + ProcessoService.AVALIADORES_POR_PROCESSO + ").";
        } else {
            var sugestao = processoService.sugerirDecisao(p);
            detDecisao = sugestao
                .map(s -> "Sugestão automática: " + s.getDescricao()
                    + " (regra " + ProcessoService.FAVORAVEIS_PARA_DEFERIR + " de "
                    + ProcessoService.AVALIADORES_POR_PROCESSO + " favoráveis).")
                .orElse("Aguardando pareceres suficientes para decidir.");
        }
        etapas.add(montar(Chave.DECISAO, "Decisão final", "hammer", decidido, anterioresConcluidas, detDecisao));
        anterioresConcluidas = finalizado || (anterioresConcluidas && decidido);

        // 4. Oficio de indeferimento (apenas quando indeferido)
        if (p.getStatus() == StatusProcesso.INDEFERIDO) {
            boolean oficioOk = p.getMotivoIndeferimento() != null && !p.getMotivoIndeferimento().isBlank()
                && temAnexo(p, TipoAnexo.OFICIO_INDEFERIMENTO)
                && p.getDataEmissaoOficio() != null;
            List<String> faltas = new ArrayList<>();
            if (p.getMotivoIndeferimento() == null || p.getMotivoIndeferimento().isBlank())
                faltas.add("motivo da reprova");
            if (!temAnexo(p, TipoAnexo.OFICIO_INDEFERIMENTO)) faltas.add("anexo do ofício");
            if (p.getDataEmissaoOficio() == null) faltas.add("data de emissão");
            String detOficio = oficioOk ? "Ofício de indeferimento completo."
                : "Falta: " + String.join(", ", faltas) + ".";
            etapas.add(montar(Chave.OFICIO, "Ofício de indeferimento", "file-earmark-text-fill",
                oficioOk, anterioresConcluidas, detOficio));
            anterioresConcluidas = anterioresConcluidas && oficioOk;
        }

        // 4b. Comprovante de insercao da urgencia renal no SNT (apenas quando deferido)
        if (p.getStatus() == StatusProcesso.DEFERIDO) {
            boolean comprovanteOk = temAnexo(p, TipoAnexo.COMPROVANTE_SNT);
            etapas.add(montar(Chave.COMPROVANTE_SNT, "Comprovante SNT", "clipboard2-check-fill",
                comprovanteOk, anterioresConcluidas,
                comprovanteOk ? "Comprovante de inserção da urgência renal no SNT anexado."
                              : "Anexe o comprovante de inserção da urgência renal no "
                                + "Sistema Nacional de Transplantes (SNT)."));
            anterioresConcluidas = anterioresConcluidas && comprovanteOk;
        }

        // 5. Resposta ao solicitante — exige o flag de e-mail enviado.
        //    O COMPROVANTE_ENVIO_SOLICITANTE (print manual) deixou de ser
        //    exigido: o proprio sistema envia o e-mail e registra a auditoria.
        //
        //    EXCECAO - CANCELADO (corrigido em 2026-08-04): processo cancelado
        //    NAO gera resposta formal por e-mail. O botao de envio fica
        //    permanentemente desabilitado na tela e
        //    ProcessoService.finalizarResposta recusa explicitamente
        //    ("Processo cancelado nao gera resposta ao solicitante"), entao a
        //    etapa era impossivel de concluir e travava o progresso em <100%
        //    para sempre. O cancelamento ja avisa os avaliadores pendentes por
        //    e-mail e o solicitante ve o resultado no Portal.
        boolean cancelado = p.getStatus() == StatusProcesso.CANCELADO;
        boolean emailMarcado = p.isEmailEnviadoSolicitante();
        boolean respostaOk = emailMarcado || cancelado;
        String detResposta;
        if (cancelado) {
            detResposta = "Cancelamento não exige envio de resposta formal por e-mail.";
        } else if (respostaOk) {
            detResposta = "Resposta enviada ao solicitante.";
        } else {
            detResposta = "Falta marcar o e-mail como enviado.";
        }
        etapas.add(montar(Chave.RESPOSTA_SOLICITANTE, "Resposta ao solicitante", "envelope-check-fill",
            respostaOk, anterioresConcluidas, detResposta));

        return etapas;
    }

    /**
     * Agrupa as etapas de {@link #montarEtapas} nos 4 passos fixos do wizard
     * horizontal da tela de detalhe (Envio, Respostas, Decisão, Finalização -
     * o Recebimento nao tem mais passo/aba propria desde 2026-08-05, ver
     * javadoc da classe), aplicando a MESMA cascata sequencial da timeline
     * vertical (um passo so fica CONCLUIDA se os anteriores tambem
     * estiverem). Garante que as duas linhas do tempo nunca divirjam.
     */
    public java.util.List<PassoWizard> montarPassosWizard(Processo p) {
        List<EtapaFluxo> etapas = montarEtapas(p);

        java.util.List<PassoWizard> passos = new ArrayList<>();
        boolean anteriorConcluido = true;

        anteriorConcluido = adicionarPasso(passos, 1, "1. Envio", "pane-envio",
            etapaConcluida(etapas, Chave.ENVIO), anteriorConcluido,
            "Envio aos avaliadores ainda não foi registrado.", "Envio aos avaliadores");

        anteriorConcluido = adicionarPasso(passos, 2, "2. Respostas", "pane-respostas",
            etapaConcluida(etapas, Chave.RESPOSTAS), anteriorConcluido,
            "Registre o Envio aos avaliadores (passo 1) primeiro.", "Pareceres dos avaliadores");

        // "Informacao complementar" pausa o fluxo (ver montarEtapas) mesmo com
        // "Respostas dos medicos" ja CONCLUIDA (maioria formada antes do pedido
        // de informacao). Sem essa checagem, o wizard destrava o passo 3 nesse
        // caso enquanto a timeline vertical mantem "Decisao final" PENDENTE -
        // as duas linhas do tempo dessincronizam.
        boolean aguardandoInfo = etapas.stream()
            .anyMatch(e -> e.chave() == Chave.INFO_COMPLEMENTAR && !e.isConcluida());
        if (aguardandoInfo) {
            anteriorConcluido = false;
        }

        anteriorConcluido = adicionarPasso(passos, 3, "3. Decisão", "pane-decisao",
            etapaConcluida(etapas, Chave.DECISAO), anteriorConcluido,
            aguardandoInfo
                ? "Aguardando informação complementar do solicitante antes de decidir."
                : "Receba todos os pareceres (passo 2) antes de decidir.",
            "Decisão final");

        // Passo 4 (Finalizacao) agrupa o bloco pos-decisao: Oficio (se
        // indeferido) ou Comprovante SNT (se deferido), mais a Resposta ao
        // solicitante. So concluido se TODAS as etapas desse bloco que
        // existirem para o status atual estiverem CONCLUIDA.
        boolean finalizacaoOk = etapaConcluidaSeExistir(etapas, Chave.OFICIO)
            && etapaConcluidaSeExistir(etapas, Chave.COMPROVANTE_SNT)
            && etapaConcluida(etapas, Chave.RESPOSTA_SOLICITANTE);
        adicionarPasso(passos, 4, "4. Finalização", "pane-finalizacao",
            finalizacaoOk, anteriorConcluido,
            "Registre a decisão (passo 3) primeiro.", "Finalização");

        return passos;
    }

    private boolean adicionarPasso(java.util.List<PassoWizard> passos, int numero, String titulo,
                                    String paneId, boolean concluido, boolean anteriorConcluido,
                                    String tooltipBloqueado, String tooltipLivre) {
        EstadoEtapa estado;
        String tooltip;
        if (concluido && anteriorConcluido) {
            estado = EstadoEtapa.CONCLUIDA;
            tooltip = tooltipLivre;
        } else if (anteriorConcluido) {
            estado = EstadoEtapa.ATUAL;
            tooltip = tooltipLivre;
        } else {
            estado = EstadoEtapa.BLOQUEADA;
            tooltip = tooltipBloqueado;
        }
        passos.add(new PassoWizard(numero, titulo, paneId, estado, tooltip));
        return anteriorConcluido && concluido;
    }

    /** true se a etapa com essa chave existe e esta CONCLUIDA. */
    private boolean etapaConcluida(List<EtapaFluxo> etapas, Chave chave) {
        return etapas.stream().anyMatch(e -> e.chave() == chave && e.isConcluida());
    }

    /** true se a etapa nao existir para o status atual (nao se aplica) OU estiver CONCLUIDA. */
    private boolean etapaConcluidaSeExistir(List<EtapaFluxo> etapas, Chave chave) {
        return etapas.stream().filter(e -> e.chave() == chave).findFirst()
            .map(EtapaFluxo::isConcluida)
            .orElse(true);
    }

    /**
     * Gating das abas do wizard (1..4) na tela de detalhe: ate qual passo o
     * operador pode navegar/agir. Extraido de
     * {@code ProcessoDetalheController.detalhe} (vistoria arquitetural
     * 2026-07-25) para ficar num lugar testavel/reaproveitavel, em vez de
     * calculado inline no controller. Mesma logica de antes, sem mudanca de
     * comportamento (so perdeu o campo {@code liberadoRecebimento} em
     * 2026-08-05, ja que o Recebimento nao tem mais aba propria - era sempre
     * {@code true} e nao influenciava mais nada alem do proprio Envio).
     */
    public record GatingAbas(boolean liberadoEnvio, boolean liberadoRespostas,
                              boolean liberadoDecisao, boolean liberadoFinalizacao) {
    }

    public GatingAbas calcularGating(Processo p) {
        // Envio (passo 1) sempre liberado: todo processo nasce do Portal do
        // Solicitante (ver veioDoPortal/montarEtapas), entao nao ha mais
        // nenhuma etapa/anexo anterior exigido para chegar aqui.
        boolean liberadoEnvio = true;
        boolean envioFeito = envioRegistrado(p);
        long respondidos = processoService.contarRespondidos(p);
        int totalMedicos = p.getPareceres().size();
        boolean todasRespondidas = totalMedicos > 0 && respondidos == totalMedicos;
        // Maioria simples (2 de 3): assim que ha >=2 favoraveis OU >=2 desfavoraveis
        // o resultado ja esta definido e nao e preciso aguardar o 3o parecer.
        boolean maioriaFormada = processoService.sugerirDecisao(p).isPresent();
        // A decisao libera quando: (a) maioria ja formada, OU (b) todas as
        // respostas chegaram.
        boolean respostasOk = maioriaFormada || todasRespondidas;
        boolean decidido = p.getStatus().isFinalizado();
        // PAUSA: enquanto aguarda informacao complementar do solicitante, a
        // decisao e a finalizacao ficam bloqueadas ate o operador retomar a
        // analise. Achado 7 (relatorio de status 2026-08-11): mesmo predicado
        // "status OU fato" ja usado por ProcessoValidator.validarPausaDecisao
        // (via temPedidoInformacaoAtivo) - se um dia o campo `status`
        // dessincronizar do fato real, esta aba nao pode liberar um botao que
        // ProcessoService.decidir recusaria em seguida.
        boolean aguardandoInfo = p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO
            || processoValidator.temPedidoInformacaoAtivo(p);

        boolean liberadoRespostas = liberadoEnvio && envioFeito;
        boolean liberadoDecisao = liberadoRespostas && respostasOk && !aguardandoInfo;
        boolean liberadoFinalizacao = decidido;
        return new GatingAbas(liberadoEnvio, liberadoRespostas, liberadoDecisao, liberadoFinalizacao);
    }

    /**
     * Sub-rotulo dinamico ao lado do status na tela de detalhe (ex.: "Maioria
     * formada - pronto para decidir (Deferido)"). Extraido de
     * {@code ProcessoDetalheController.detalhe} (vistoria arquitetural
     * 2026-07-25). Por MAIORIA SIMPLES (2 de 3), assim que ha 2 votos do mesmo
     * tipo o resultado ja esta definido: nao mostra mais "Aguardando parecer",
     * e sim "pronto para decidir". So mostra "Aguardando parecer (x/total)"
     * quando ainda NAO ha maioria. Retorna {@code null} quando nao ha
     * sub-rotulo a mostrar (mesmo comportamento anterior).
     */
    public String calcularSubrotuloStatus(Processo p) {
        if (p.getStatus() != StatusProcesso.ENVIADO) {
            return null;
        }
        boolean envioFeito = envioRegistrado(p);
        long respondidos = processoService.contarRespondidos(p);
        int totalMedicos = p.getPareceres().size();
        var sugestao = processoService.sugerirDecisao(p);
        boolean maioriaFormada = sugestao.isPresent();
        boolean todasRespondidas = totalMedicos > 0 && respondidos == totalMedicos;
        boolean respostasOk = maioriaFormada || todasRespondidas;

        if (envioFeito && maioriaFormada) {
            return "Maioria formada - pronto para decidir (" + sugestao.get().getDescricao() + ")";
        } else if (envioFeito && totalMedicos > 0 && respondidos < totalMedicos) {
            return "Aguardando parecer (" + respondidos + "/" + totalMedicos + ")";
        } else if (envioFeito && respostasOk) {
            return "Pareceres recebidos - aguardando decisão";
        }
        return null;
    }

    /** Mensagem curta de "o que falta" para o processo (etapa atual pendente). */
    public String resumoPendencia(Processo p) {
        return pendenciaAberta(p)
            .map(e -> e.titulo() + ": " + e.detalhe())
            .orElse("Processo concluído.");
    }

    /**
     * Igual a {@link #resumoPendencia}, mas devolve a ETAPA inteira (nao so a
     * string ja concatenada) e VAZIO quando nao ha etapa pendente nenhuma -
     * para quem precisa distinguir "nada a fazer" de "falta algo" sem
     * comparar a string "Processo concluido.".
     *
     * <p>Existe por causa do Painel: ele so calculava pendencia para processo
     * {@code isEmAndamento()}, entao um Deferido/Indeferido que ainda devia
     * oficio, comprovante SNT ou a resposta ao solicitante aparecia sem nenhum
     * "o que falta" - e, ate a correcao do badge, ainda rotulado "Encerrado".
     * Status final significa apenas que a DECISAO saiu e a edicao das etapas
     * 1-4 travou; a papelada de conclusao continua pendente (bug relatado em
     * producao no processo 04/2026).
     *
     * <p><b>Devolve o {@link EtapaFluxo} inteiro desde 2026-08-05</b> (item
     * 5.1 do relatorio de clareza) para quem exibe a pendencia numa CELULA DE
     * TABELA poder mostrar so {@code titulo()} (curto) e reservar
     * {@code detalhe()} (a frase completa) para o atributo {@code title} -
     * antes a string ja vinha concatenada ("Titulo: frase longa..."), sempre
     * visivel por inteiro, empurrando as demais colunas ou sendo cortada sem
     * reticencias (ver dashboard.html/processos/lista.html).
     */
    public Optional<EtapaFluxo> pendenciaAberta(Processo p) {
        // CANCELADO (corrigido em 2026-08-05): um processo cancelado antes do
        // envio/pareceres tinha sua primeira etapa marcada ATUAL - a edicao ja
        // fica bloqueada por edicaoBloqueada nesse status, entao essa
        // pendencia era impossivel de resolver e travava a barra de progresso
        // abaixo de 100% para sempre. Mesmo raciocinio ja aplicado a
        // respostaOk (montarEtapas) para o cancelamento a partir da decisao.
        if (p.getStatus() == StatusProcesso.CANCELADO) {
            return Optional.empty();
        }
        for (EtapaFluxo e : montarEtapas(p)) {
            if (e.estado() == EstadoEtapa.ATUAL) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    private EtapaFluxo montar(Chave chave, String titulo, String icone, boolean concluida,
                              boolean anterioresConcluidas, String detalhe) {
        // So mostra CONCLUIDA (verde) se as etapas anteriores tambem estiverem
        // concluidas - senao a etapa fica "verde fora de ordem" mesmo com sua
        // propria condicao satisfeita (ex.: resposta ao solicitante marcada
        // antes do comprovante SNT ser anexado). Timeline le como progressao
        // sequencial, entao a cor precisa respeitar essa ordem.
        EstadoEtapa estado;
        if (concluida && anterioresConcluidas) {
            estado = EstadoEtapa.CONCLUIDA;
        } else if (anterioresConcluidas) {
            estado = EstadoEtapa.ATUAL;
        } else {
            estado = EstadoEtapa.BLOQUEADA;
        }
        return new EtapaFluxo(chave, titulo, icone, estado, detalhe);
    }

    private boolean temAnexo(Processo p, TipoAnexo tipo) {
        return anexosSeguro(p).stream().anyMatch(a -> a.getTipo() == tipo);
    }

    /**
     * Acesso defensivo a {@code p.getAnexos()}: um {@code Anexo.tipo} com um
     * valor fora do enum atual (ex.: {@code CAPA_PROCESSO}/{@code
     * SOLICITACAO_RECEBIDA}, removidos do enum no commit {@code 041dc43}, mas
     * que podem sobrar numa linha de banco antigo - {@code ddl-auto: update}
     * nunca valida dado ja gravado) faz o Hibernate lancar excecao ao
     * hidratar a linha, e ISSO NAO E um erro de que o Processo em si esteja
     * quebrado - so aquele anexo especifico virou lixo historico.
     *
     * <p><b>Bug real corrigido (2026-08-08):</b> sem este isolamento, um UNICO
     * anexo com {@code tipo} invalido em QUALQUER processo do ano derrubava o
     * Painel (500) e a lista de {@code /processos} inteiros - a excecao
     * ({@code IllegalArgumentException} cru quando disparada por acesso
     * lazy direto a um getter, ou {@code InvalidDataAccessApiUsageException}
     * quando disparada dentro de um metodo de repositorio, como o
     * pre-carregamento em lote de {@code ProcessoRepository.inicializarAnexos})
     * nao tinha nenhum tratamento amigavel para esse segundo tipo. Aqui,
     * captura ampla de proposito (o tipo exato varia conforme o caminho de
     * acesso que disparou a hidratacao, ver acima) - loga o processo afetado
     * e degrada para "sem anexos" NAQUELE calculo, deixando o restante da
     * pagina (os demais processos, sem dado corrompido) renderizar
     * normalmente em vez de um erro 500 cru.</p>
     */
    private List<Anexo> anexosSeguro(Processo p) {
        try {
            // p.getAnexos() sozinho so devolve a REFERENCIA da colecao lazy -
            // o Hibernate so inicializa (e so lanca a excecao de hidratacao,
            // se houver) no primeiro acesso de verdade. `new ArrayList<>(...)`
            // forca essa inicializacao AQUI DENTRO do try - devolver a
            // colecao sem materializar deixava a excecao escapar para o
            // chamador, fora deste catch (bug corrigido antes mesmo de sair
            // do rascunho: pego pelo teste de integracao real, nao pela
            // leitura do codigo).
            return new ArrayList<>(p.getAnexos());
        } catch (RuntimeException e) {
            log.warn("Processo id {} tem ao menos um anexo com 'tipo' que nao corresponde a "
                + "nenhum valor valido do enum TipoAnexo (dado legado/removido do enum) - "
                + "ignorando os anexos deste processo neste calculo, em vez de quebrar a "
                + "pagina inteira: {}", p.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Envio registrado = existem pareceres E TODOS tem dataEnvio preenchida.
     * Mesmo criterio usado em {@link #montarEtapas} (enviadosCount ==
     * totalMedicos) - fonte unica, para o gating das abas e o sub-rotulo nunca
     * divergirem da timeline (antes usavam apenas {@code pareceres.get(0)}, o
     * que destoava quando so parte dos pareceres tinha data de envio).
     */
    public boolean envioRegistrado(Processo p) {
        return !p.getPareceres().isEmpty()
            && p.getPareceres().stream().allMatch(par -> par.getDataEnvio() != null);
    }
}

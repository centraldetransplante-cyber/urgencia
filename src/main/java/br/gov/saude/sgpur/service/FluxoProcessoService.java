package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.repository.SolicitacaoOnlineRepository;
import br.gov.saude.sgpur.service.dto.EstadoEtapa;
import br.gov.saude.sgpur.service.dto.EtapaFluxo;
import br.gov.saude.sgpur.service.dto.EtapaFluxo.Chave;
import br.gov.saude.sgpur.service.dto.PassoWizard;
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

    public FluxoProcessoService(ProcessoService processoService,
                                 SolicitacaoOnlineRepository solicitacaoOnlineRepository) {
        this.processoService = processoService;
        this.solicitacaoOnlineRepository = solicitacaoOnlineRepository;
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
                + " medicos (atual: " + totalMedicos + ").";
        } else if (!temDocClinicoPdf && !enviado) {
            detEnvio = "Anexe o(s) documento(s) clinico(s) (PDF) para gerar o processo dos avaliadores.";
        } else if (!enviado) {
            detEnvio = "Registre o envio aos medicos (faltam " + (totalMedicos - enviadosCount)
                + " de " + totalMedicos + ").";
        } else {
            detEnvio = "Enviado aos " + totalMedicos + " medicos.";
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
        boolean respostasOk = maioria || todasRespondidas;
        boolean pausaBloqueiaDecisao = p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO
            && !processoService.temVotoCoordenadorFavoravel(p);
        String detResp;
        if (totalMedicos == 0) {
            detResp = "Aguardando definicao dos medicos.";
        } else if (maioria && pausaBloqueiaDecisao) {
            detResp = "Maioria formada (" + sugestaoResp.get().getDescricao()
                + ") mas a decisao esta BLOQUEADA: aguardando informacao complementar "
                + "de outro avaliador. Favoraveis: " + favoraveis + ".";
        } else if (maioria) {
            detResp = "Maioria formada (" + sugestaoResp.get().getDescricao()
                + ") - pronto para decidir. Favoraveis: " + favoraveis + ".";
        } else if (!todasRespondidas) {
            detResp = "Faltam " + (totalMedicos - respondidos) + " de " + totalMedicos
                + " pareceres. Favoraveis ate agora: " + favoraveis + ".";
        } else {
            detResp = respondidos + " pareceres recebidos. Favoraveis: " + favoraveis + ".";
        }
        etapas.add(montar(Chave.RESPOSTAS, "Respostas dos médicos", "chat-square-text-fill",
            respostasOk, anterioresConcluidas, detResp));
        anterioresConcluidas = finalizado || (anterioresConcluidas && respostasOk);

        // 2b. Informacao complementar (apenas enquanto um medico pediu mais dados).
        //     Funciona como uma PAUSA: bloqueia a decisao ate o solicitante
        //     responder e o operador retomar a analise.
        if (p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO) {
            etapas.add(montar(Chave.INFO_COMPLEMENTAR, "Informação complementar", "question-circle-fill",
                false, anterioresConcluidas,
                "Aguardando informacao complementar do solicitante. Envie o pedido, "
                + "anexe a resposta recebida e retome a analise para liberar a decisao."));
            // bloqueia tudo o que vem depois enquanto nao for retomado
            anterioresConcluidas = false;
        }

        // 3. Decisao final
        boolean decidido = p.getStatus().isFinalizado();
        String detDecisao;
        if (decidido) {
            detDecisao = "Processo " + p.getStatus().getDescricao() + ".";
        } else if (pausaBloqueiaDecisao) {
            // Mesmo motivo do detResp acima: maioria formada nao significa
            // decisao liberada enquanto a pausa estiver ativa (exceto pelo
            // voto favoravel do coordenador).
            var sugestao = processoService.sugerirDecisao(p);
            detDecisao = sugestao
                .map(s -> "Sugestao automatica: " + s.getDescricao()
                    + " - BLOQUEADA pela pausa (aguardando informacao complementar).")
                .orElse("Aguardando informacao complementar do solicitante.");
        } else {
            var sugestao = processoService.sugerirDecisao(p);
            detDecisao = sugestao
                .map(s -> "Sugestao automatica: " + s.getDescricao()
                    + " (regra " + ProcessoService.FAVORAVEIS_PARA_DEFERIR + " de "
                    + ProcessoService.AVALIADORES_POR_PROCESSO + " favoraveis).")
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
            if (!temAnexo(p, TipoAnexo.OFICIO_INDEFERIMENTO)) faltas.add("anexo do oficio");
            if (p.getDataEmissaoOficio() == null) faltas.add("data de emissao");
            String detOficio = oficioOk ? "Oficio de indeferimento completo."
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
                comprovanteOk ? "Comprovante de insercao da urgencia renal no SNT anexado."
                              : "Anexe o comprovante de insercao da urgencia renal no "
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
            detResposta = "Cancelamento nao exige envio de resposta formal por e-mail.";
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
            "Envio aos avaliadores ainda nao foi registrado.", "Envio aos avaliadores");

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
                ? "Aguardando informacao complementar do solicitante antes de decidir."
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
            "Registre a decisao (passo 3) primeiro.", "Finalização");

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
        // decisao e a finalizacao ficam bloqueadas ate o operador retomar a analise.
        boolean aguardandoInfo = p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO;

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
            return "Pareceres recebidos - aguardando decisao";
        }
        return null;
    }

    /** Mensagem curta de "o que falta" para o processo (etapa atual pendente). */
    public String resumoPendencia(Processo p) {
        return pendenciaAberta(p)
            .map(e -> e.titulo() + ": " + e.detalhe())
            .orElse("Processo concluido.");
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

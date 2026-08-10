package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.*;
import br.gov.saude.sgpur.service.dto.RegraDecisao;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Centraliza as regras de negocio (contagem de votos e validacoes de decisao)
 * que antes viviam duplicadas entre {@link ProcessoService} e os controllers de
 * {@code /processos}. As funcoes sao puras (operam sobre o {@link Processo} em
 * memoria, sem acesso a banco) e as mensagens de erro sao as mesmas usadas na
 * camada web (flash {@code erro}) e no servico (excecoes) — mudar aqui muda nos
 * dois lugares.
 */
@Service
public class ProcessoValidator {

    /**
     * Mensagem exibida quando o operador tenta alterar um processo ENCERRADO
     * (Deferido/Indeferido/Cancelado). A edicao fica travada; so o ADMIN pode
     * reabrir (POST /processos/{id}/reabrir) para voltar a alterar.
     */
    public static final String MSG_ENCERRADO =
        "Processo encerrado: nenhuma alteracao e permitida. "
        + "Um administrador precisa reabrir o processo para altera-lo.";

    /**
     * True se o processo esta ENCERRADO e, portanto, com a edicao travada.
     * Usado como guarda nos endpoints de alteracao (etapas 1 a 4, upload
     * generico de anexos, exclusao de anexos e lembretes). As etapas 5 e 6
     * (oficio, comprovante SNT, resposta ao solicitante) e a leitura NAO usam
     * esta guarda — elas continuam liberadas apos a decisao.
     */
    public boolean edicaoBloqueada(Processo processo) {
        return processo.getStatus() != null && processo.getStatus().isFinalizado();
    }

    // ----- Contagem de votos (consultas puras) -----

    public long contarFavoraveis(Processo processo) {
        return processo.getPareceres().stream()
            .filter(p -> p.getResultado() == ResultadoParecer.FAVORAVEL)
            .count();
    }

    public long contarNaoFavoraveis(Processo processo) {
        return processo.getPareceres().stream()
            .filter(p -> p.getResultado() == ResultadoParecer.NAO_FAVORAVEL)
            .count();
    }

    public long contarRespondidos(Processo processo) {
        return processo.getPareceres().stream()
            .filter(p -> p.getResultado() != null)
            .count();
    }

    /**
     * True se o coordenador CET-RS votou FAVORAVEL neste processo.
     *
     * <p>Le o SNAPSHOT {@code Parecer.eraCoordenadorNoVoto} (capturado no
     * instante do voto, em {@code AvaliadorController.registrarVoto}), NAO o
     * papel atual do membro ({@code MembroUrgenciaRenal.coordenador}). Isso
     * evita que o cargo de coordenador mudar de mao entre o voto e a decisao
     * final altere retroativamente o peso de um voto ja registrado (Achado 4
     * da "Vistoria de bugs de 2026-08-03" no CLAUDE.md). Parecer ANTIGO sem o
     * snapshot ({@code null}, votado antes desta mudanca) e tratado como
     * "nao sabemos, nao conta" -- decisao conservadora deliberada.</p>
     */
    public boolean temVotoCoordenadorFavoravel(Processo processo) {
        return processo.getPareceres().stream()
            .anyMatch(p -> p.getResultado() == ResultadoParecer.FAVORAVEL
                && Boolean.TRUE.equals(p.getEraCoordenadorNoVoto()));
    }

    /**
     * True se o processo foi deferido pelo voto do coordenador CET-RS
     * (status DEFERIDO + coordenador votou FAVORAVEL).
     */
    public boolean deferidoPeloCoordenador(Processo processo) {
        return processo.getStatus() == StatusProcesso.DEFERIDO
            && temVotoCoordenadorFavoravel(processo);
    }

    /**
     * Qual regra decidiu (ou decide hoje) este processo - vocabulario
     * fechado {@link RegraDecisao}, fonte unica consumida por templates
     * (badge), pelo Relatorio Final, pelo dossie exportado e pela auditoria
     * (F2 do {@code docs/RELATORIO-VISTORIA-BRECHAS-DECISAO-2026-08-10.md},
     * achados 2, 3, 4 e 6).
     *
     * <p><b>Metodo de LEITURA apenas</b> - reusa {@link #temVotoCoordenadorFavoravel}
     * (snapshot, ja usado por {@code sugerirDecisao}/{@code deferidoPeloCoordenador})
     * sem duplicar nem alterar o predicado. Nao influencia nenhuma decisao:
     * so descreve, em texto, uma decisao ja tomada ou a situacao atual.</p>
     */
    public RegraDecisao regraAplicada(Processo processo) {
        StatusProcesso status = processo.getStatus();
        if (status == null || !status.isFinalizado()) {
            return RegraDecisao.NAO_DECIDIDO;
        }
        if (status == StatusProcesso.CANCELADO) {
            return RegraDecisao.CANCELAMENTO;
        }
        // DEFERIDO ou INDEFERIDO daqui pra baixo. So DEFERIDO pode ter sido
        // decidido pelo voto isolado do coordenador (mesma condicao de
        // deferidoPeloCoordenador, sem duplicar temVotoCoordenadorFavoravel -
        // INDEFERIDO com coordenador favoravel nao e alcancavel, ver
        // validarContagemVotos).
        if (status == StatusProcesso.DEFERIDO && temVotoCoordenadorFavoravel(processo)) {
            return RegraDecisao.VOTO_COORDENADOR;
        }
        return RegraDecisao.MAIORIA_SIMPLES;
    }

    /** Quantos pareceres favoraveis sao necessarios para Deferir (considerando coordenador). */
    public long favoraveisNecessariosParaDeferir(Processo processo) {
        return temVotoCoordenadorFavoravel(processo)
            ? 1 : ProcessoService.FAVORAVEIS_PARA_DEFERIR;
    }

    /** Quantos pareceres desfavoraveis sao necessarios para Indeferir. */
    public long desfavoraveisNecessariosParaIndeferir() {
        return ProcessoService.DESFAVORAVEIS_PARA_INDEFERIR;
    }

    /**
     * Sugestao de decisao:
     * - Se o coordenador CET-RS votou FAVORAVEL -> DEFERIDO (peso unico).
     * - Senao, maioria simples (2 de 3): 2+ favoraveis -> DEFERIDO;
     *   2+ desfavoraveis -> INDEFERIDO.
     * - Sem maioria ainda -> Optional vazio.
     */
    public Optional<StatusProcesso> sugerirDecisao(Processo processo) {
        if (temVotoCoordenadorFavoravel(processo)) {
            return Optional.of(StatusProcesso.DEFERIDO);
        }
        long favoraveis = contarFavoraveis(processo);
        long naoFavoraveis = contarNaoFavoraveis(processo);

        if (favoraveis >= ProcessoService.FAVORAVEIS_PARA_DEFERIR) {
            return Optional.of(StatusProcesso.DEFERIDO);
        }
        if (naoFavoraveis >= ProcessoService.DESFAVORAVEIS_PARA_INDEFERIR) {
            return Optional.of(StatusProcesso.INDEFERIDO);
        }
        return Optional.empty();
    }

    // ----- Validacoes de decisao (retornam a mensagem de erro, ou vazio) -----
    //
    // Sao granulares porque a camada web usa cada grupo para escolher a ancora
    // de redirecionamento (pausa -> #respostas; contagem -> topo). O servico
    // encadeia todas em validarDecisao (defesa em profundidade).

    /**
     * True se existe, AGORA, algum parecer deste processo com resultado
     * {@code SOLICITA_INFORMACAO} ainda nao resolvido — o FATO observavel que
     * caracteriza a pausa, independente do campo derivado
     * {@code Processo.status}.
     *
     * <p><b>Por que este metodo existe (docs/RELATORIO-BUG-DOIS-VOTOS-DEFEREM-
     * DURANTE-PAUSA-2026-08.md, achados C e D):</b> a trava da pausa era
     * ancorada so em {@code processo.getStatus() == SOLICITA_INFORMACAO}, um
     * campo derivado que pode dessincronizar do fato real:</p>
     * <ul>
     *   <li><b>Achado C</b> — {@link ProcessoService#reabrir} forcava o status
     *   para {@code ENVIADO} incondicionalmente, mesmo que um parecer
     *   {@code SOLICITA_INFORMACAO} continuasse vivo (processo pausado,
     *   encerrado por um caminho que a pausa permite — Cancelado, ou Deferido
     *   pelo coordenador — e depois reaberto pelo ADMIN). A pausa simplesmente
     *   deixava de existir para o sistema.</li>
     *   <li><b>Achado D</b> — dois votos quase simultaneos (um pedindo
     *   informacao, outro fechando a maioria) podiam intercalar as
     *   transacoes de forma que a checagem de status lesse um valor
     *   "atrasado" em relacao ao parecer ja commitado no banco.</li>
     * </ul>
     * <p>Este metodo sempre le a colecao de pareceres do {@code Processo} que
     * o caller ja tem em maos — quando chamado dentro de {@code decidir}/
     * {@code tentarDecisaoAutomatica} (ambos recarregam o processo via
     * {@code buscar(id)} no INICIO da propria transacao), reflete o estado
     * mais recente commitado no banco naquele instante, o que fecha a maior
     * parte da janela do achado D (ver javadoc de
     * {@link ProcessoService#tentarDecisaoAutomatica} para a analise
     * completa da janela residual).</p>
     */
    public boolean temPedidoInformacaoAtivo(Processo processo) {
        return processo.getPareceres().stream()
            .anyMatch(p -> p.getResultado() == ResultadoParecer.SOLICITA_INFORMACAO);
    }

    /**
     * Bloqueio por PAUSA: aguardando informacao complementar do solicitante.
     * Excecao: o voto Favoravel do coordenador CET-RS defere sozinho e na hora,
     * mesmo com o processo pausado por causa do parecer de outro avaliador —
     * a pausa nao se aplica a essa regra (decisao de produto confirmada).
     * A excecao vale SO para Deferir: o coordenador nao tem peso especial para
     * indeferir (CLAUDE.md), entao Indeferido continua bloqueado pela pausa
     * mesmo com o coordenador favoravel registrado.
     *
     * <p><b>Pausa ativa = status OU fato (desde a correcao dos achados C/D —
     * ver {@link #temPedidoInformacaoAtivo}).</b> Mantido em OU (nao
     * substituido) de proposito: o status continua sendo a fonte principal no
     * caminho normal (voto -&gt; {@code atualizarStatusPorPareceres} -&gt;
     * status sincronizado), e o fato cobre exatamente os casos em que os dois
     * dessincronizam. Checar so o fato tambem funcionaria no caminho normal,
     * mas manter o status explicito documenta a intencao e preserva o
     * comportamento historico ja coberto por teste.</p>
     */
    public Optional<String> validarPausaDecisao(Processo processo, StatusProcesso decisao) {
        boolean bloqueiaDeferido = decisao == StatusProcesso.DEFERIDO
            && !temVotoCoordenadorFavoravel(processo);
        boolean bloqueiaIndeferido = decisao == StatusProcesso.INDEFERIDO;
        boolean pausaAtiva = processo.getStatus() == StatusProcesso.SOLICITA_INFORMACAO
            || temPedidoInformacaoAtivo(processo);
        if (pausaAtiva && (bloqueiaDeferido || bloqueiaIndeferido)) {
            return Optional.of(
                "Processo aguardando informacao complementar do solicitante. "
                + "Registre o recebimento da informacao (retomar analise) antes de decidir.");
        }
        return Optional.empty();
    }

    /** Deferido exige votos favoraveis suficientes; Indeferido exige desfavoraveis suficientes. */
    public Optional<String> validarContagemVotos(Processo processo, StatusProcesso decisao) {
        long minFavoraveis = favoraveisNecessariosParaDeferir(processo);
        if (decisao == StatusProcesso.DEFERIDO && contarFavoraveis(processo) < minFavoraveis) {
            return Optional.of("Deferimento exige no minimo "
                + minFavoraveis + " parecer(es) favoravel(is).");
        }
        if (decisao == StatusProcesso.INDEFERIDO) {
            // O voto Favoravel do coordenador CET-RS defere sozinho (prioridade
            // absoluta em sugerirDecisao). Simetricamente, INDEFERIR fica vedado
            // enquanto o coordenador votou Favoravel — mesmo que ja existam 2
            // desfavoraveis, o resultado do processo e Deferido. Sem esta guarda,
            // o operador conseguiria sobrepor manualmente a regra do coordenador.
            if (temVotoCoordenadorFavoravel(processo)) {
                return Optional.of(
                    "O coordenador da CET-RS votou favoravel: o processo defere sozinho "
                    + "e nao pode ser indeferido.");
            }
            if (contarNaoFavoraveis(processo) < ProcessoService.DESFAVORAVEIS_PARA_INDEFERIR) {
                return Optional.of("Indeferimento exige no minimo "
                    + ProcessoService.DESFAVORAVEIS_PARA_INDEFERIR + " pareceres desfavoraveis.");
            }
        }
        return Optional.empty();
    }

    /** Indeferido exige o motivo preenchido. */
    public Optional<String> validarMotivoIndeferimento(StatusProcesso decisao, String motivoIndeferimento) {
        if (decisao == StatusProcesso.INDEFERIDO
                && (motivoIndeferimento == null || motivoIndeferimento.isBlank())) {
            return Optional.of("Indeferimento exige o motivo.");
        }
        return Optional.empty();
    }

    /**
     * Todas as pre-condicoes da decisao final, na mesma ordem imposta pelo
     * servico: pausa -> contagem de votos -> motivo. Retorna a primeira
     * mensagem de erro encontrada, ou vazio se pode decidir.
     */
    public Optional<String> validarDecisao(Processo processo, StatusProcesso decisao, String motivoIndeferimento) {
        return validarPausaDecisao(processo, decisao)
            .or(() -> validarContagemVotos(processo, decisao))
            .or(() -> validarMotivoIndeferimento(decisao, motivoIndeferimento));
    }

    /**
     * Espelha no servico a mesma checagem que ja existia no controller HTTP
     * antes de registrar o envio (defesa em profundidade): mesmo que
     * {@code ProcessoService.registrarEnvio} venha a ser chamado de outro
     * lugar no futuro (outro controller, job, teste), o processo nunca vira
     * ENVIADO sem essa garantia minima.
     */
    public Optional<String> validarRegistroEnvio(Processo processo) {
        // SO DOCUMENTO_CLINICO_AVALIADOR conta. DOCUMENTO_PORTAL_NAO_ANONIMIZADO
        // (o que veio do Portal do Solicitante, com o nome do paciente no corpo)
        // NAO satisfaz esta regra: enquanto o operador nao confirmar a
        // anonimizacao, o envio fica bloqueado com a mensagem especifica abaixo,
        // em vez de passar silenciosamente. Processos convertidos antes desta
        // trava tem o anexo do portal gravado com o tipo antigo e continuam
        // validos aqui.
        boolean temDocumentoClinicoPdf = processo.getAnexos().stream()
            .anyMatch(a -> a.getTipo() == TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR
                && a.getContentType() != null
                && a.getContentType().toLowerCase().contains("application/pdf"));
        if (!temDocumentoClinicoPdf) {
            return Optional.of(temDocumentoPendenteAnonimizacao(processo)
                ? MSG_PENDENTE_ANONIMIZACAO
                : "Anexe ao menos um documento clinico (PDF) antes de registrar o envio.");
        }
        return Optional.empty();
    }

    /**
     * Mensagem do bloqueio da trava de anonimizacao: o processo so tem
     * documento(s) do Portal do Solicitante ainda nao revisados, que nunca vao
     * aos avaliadores.
     */
    public static final String MSG_PENDENTE_ANONIMIZACAO =
        "Confirme a anonimizacao do(s) documento(s) enviado(s) pelo solicitante "
        + "antes de registrar o envio aos avaliadores (ou anexe um documento clinico "
        + "ja anonimizado em PDF).";

    /** True se o processo tem algum anexo do portal ainda pendente de anonimizacao. */
    public boolean temDocumentoPendenteAnonimizacao(Processo processo) {
        return processo.getAnexos().stream()
            .anyMatch(a -> a.getTipo() == TipoAnexo.DOCUMENTO_PORTAL_NAO_ANONIMIZADO);
    }

    /**
     * Bloqueio da confirmacao da resposta ao solicitante: Deferido exige o
     * comprovante SNT anexado; Indeferido exige o oficio de indeferimento
     * (simetria entre as duas decisoes finais).
     */
    public Optional<String> validarRespostaSolicitante(Processo processo) {
        if (processo.getStatus() == StatusProcesso.DEFERIDO
                && processo.getAnexos().stream().noneMatch(a -> a.getTipo() == TipoAnexo.COMPROVANTE_SNT)) {
            return Optional.of(
                "Anexe o comprovante de insercao no SNT antes de confirmar a resposta ao solicitante.");
        }
        if (processo.getStatus() == StatusProcesso.INDEFERIDO
                && processo.getAnexos().stream().noneMatch(a -> a.getTipo() == TipoAnexo.OFICIO_INDEFERIMENTO)) {
            return Optional.of(
                "Anexe o oficio de indeferimento antes de confirmar a resposta ao solicitante.");
        }
        return Optional.empty();
    }
}

package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.MensagemSolicitacao.RemetenteMensagem;
import br.gov.saude.sgpur.domain.SolicitacaoOnline;
import br.gov.saude.sgpur.repository.MensagemSolicitacaoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

@Service
public class MensagemSolicitacaoService {

    /**
     * Limite de tamanho de mensagem, imposto no SERVIDOR (S6,
     * docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md, achado A7). Os 4 campos de
     * texto de chat ja anunciam {@code maxlength="2000"} no HTML, mas isso e
     * so UX - trivialmente burlavel via DevTools/curl. Sem checagem aqui, uma
     * mensagem de 200 mil caracteres era aceita inteira (coluna TEXT, sem
     * erro de banco), pesando no poll a cada 5s e no render do balao. Cada
     * controller valida ANTES de chamar {@link #enviar}, no mesmo padrao ja
     * usado para o "texto em branco" - nao lanca excecao aqui, para nao
     * exigir try/catch nos 8 call-sites (classicos e AJAX, dos dois canais).
     */
    public static final int TEXTO_MAX_LENGTH = 2000;

    private final MensagemSolicitacaoRepository repository;

    public MensagemSolicitacaoService(MensagemSolicitacaoRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MensagemSolicitacao enviar(SolicitacaoOnline solicitacao, String texto, RemetenteMensagem remetente, Long remetenteId) {
        MensagemSolicitacao msg = new MensagemSolicitacao();
        msg.setSolicitacaoOnline(solicitacao);
        msg.setTexto(texto);
        msg.setRemetente(remetente);
        msg.setRemetenteId(remetenteId);
        msg.setDataEnvio(LocalDateTime.now());
        msg.setLida(false);
        return repository.save(msg);
    }

    @Transactional(readOnly = true)
    public List<MensagemSolicitacao> listarPorSolicitacao(Long solicitacaoOnlineId) {
        return repository.findBySolicitacaoOnlineIdOrderByDataEnvioAsc(solicitacaoOnlineId);
    }

    /**
     * F6 (S10, docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md, achado A13): antes
     * carregava a thread INTEIRA a cada poll de 5s e filtrava em Java, mesmo
     * sem nada para marcar. Agora delega a
     * {@link MensagemSolicitacaoRepository#marcarComoLidasEmLote} - um UPDATE
     * de linha unica no banco, sem trazer nenhuma entidade.
     */
    @Transactional
    public void marcarComoLidas(Long solicitacaoOnlineId, RemetenteMensagem remetente, Long remetenteId) {
        repository.marcarComoLidasEmLote(solicitacaoOnlineId, remetente, remetenteId);
    }

    @Transactional(readOnly = true)
    public long contarNaoLidasOperador() {
        return repository.countByLidaFalseAndRemetente(RemetenteMensagem.SOLICITANTE);
    }

    /** Total de respostas do OPERADOR ainda nao lidas por um solicitante, somando todas as solicitacoes dele. */
    @Transactional(readOnly = true)
    public long contarNaoLidasParaSolicitante(Long usuarioSolicitanteId) {
        return repository.countByRemetenteAndLidaFalseAndSolicitacaoOnlineUsuarioSolicitanteId(
            RemetenteMensagem.OPERADOR, usuarioSolicitanteId);
    }

    @Transactional(readOnly = true)
    public Set<Long> idsSolicitacoesComMsgNaoLidaSolicitante() {
        return repository.findDistinctSolicitacaoOnlineIdsByLidaFalseAndRemetente(RemetenteMensagem.SOLICITANTE);
    }

    @Transactional(readOnly = true)
    public long contarNaoLidasSolicitantePorSolicitacao(Long solicitacaoOnlineId, Long solicitanteUsuarioId) {
        return repository.countBySolicitacaoOnlineIdAndLidaFalseAndRemetenteAndRemetenteIdNot(
            solicitacaoOnlineId, RemetenteMensagem.OPERADOR, solicitanteUsuarioId);
    }

    @Transactional
    public void apagar(Long mensagemId, Long remetenteId, RemetenteMensagem remetente) {
        MensagemSolicitacao msg = repository.findById(mensagemId)
            .orElseThrow(() -> new IllegalArgumentException("Mensagem nao encontrada."));
        if (!msg.getRemetenteId().equals(remetenteId) || msg.getRemetente() != remetente) {
            throw new IllegalArgumentException("Voce nao pode apagar esta mensagem.");
        }
        if (msg.isDeletada()) {
            return;
        }
        msg.setDeletada(true);
        msg.setDeletadaEm(LocalDateTime.now());
        msg.setTexto(null);
        repository.save(msg);
    }

    private static final DateTimeFormatter ISO_CHAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Mensagem projetada pro chat, ja relativa a quem esta vendo (usada pelo
     * polling AJAX das 3 telas de chat - solicitante, triagem e detalhe do
     * processo). {@code deVoce} e {@code podeApagar} sao calculados aqui pra
     * evitar duplicar a logica de "de quem e essa mensagem" em 3 templates.
     */
    public record MensagemChatView(Long id, boolean deVoce, String nomeRemetente, String texto,
                                    String dataEnvioIso, String dataEnvioFormatada,
                                    boolean lida, boolean deletada, boolean podeApagar) {}

    /**
     * @param remetenteAtual   papel de quem esta vendo o chat (OPERADOR nas telas do operador, SOLICITANTE no portal)
     * @param remetenteAtualId id do usuario logado (so importa pra decidir se PODE apagar uma mensagem propria)
     * @param labelEu          rotulo pras mensagens do proprio papel ("Voce")
     * @param labelOutro       rotulo pras mensagens do outro lado ("Solicitante" ou "Equipe CET-RS")
     */
    @Transactional(readOnly = true)
    public List<MensagemChatView> paraChat(Long solicitacaoOnlineId, RemetenteMensagem remetenteAtual,
                                            Long remetenteAtualId, String labelEu, String labelOutro) {
        return listarPorSolicitacao(solicitacaoOnlineId).stream()
            .map(m -> {
                boolean deVoce = m.getRemetente() == remetenteAtual;
                return new MensagemChatView(
                    m.getId(),
                    deVoce,
                    deVoce ? labelEu : labelOutro,
                    m.getTexto(),
                    m.getDataEnvio().format(ISO_CHAT),
                    m.getDataEnvio().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                    m.isLida(),
                    m.isDeletada(),
                    deVoce && m.getRemetenteId().equals(remetenteAtualId));
            })
            .toList();
    }
}

package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.MembroUrgenciaRenal;
import br.gov.saude.sgpur.domain.MensagemAvaliador;
import br.gov.saude.sgpur.domain.MensagemAvaliador.RemetenteMensagemAvaliador;
import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.repository.MensagemAvaliadorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Camada de regra do chat AVALIADOR &lt;-&gt; OPERADOR, por processo (ver
 * docs/RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md). Espelha
 * {@link MensagemSolicitacaoService} de proposito (mesmo padrao ja maduro em
 * producao) - a duplicacao de ~100 linhas e aceita conscientemente (secao
 * 6.1 do relatorio): extrair uma superclasse acoplaria justamente os dois
 * dominios de privacidade que a separacao de tabelas quer manter isolados.
 */
@Service
public class MensagemAvaliadorService {

    private final MensagemAvaliadorRepository repository;

    public MensagemAvaliadorService(MensagemAvaliadorRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MensagemAvaliador enviar(Processo processo, MembroUrgenciaRenal membro, String texto,
                                     RemetenteMensagemAvaliador remetente, Long remetenteId) {
        MensagemAvaliador msg = new MensagemAvaliador();
        msg.setProcesso(processo);
        msg.setMembro(membro);
        msg.setTexto(texto);
        msg.setRemetente(remetente);
        msg.setRemetenteId(remetenteId);
        msg.setDataEnvio(LocalDateTime.now());
        msg.setLida(false);
        return repository.save(msg);
    }

    @Transactional(readOnly = true)
    public List<MensagemAvaliador> listarThread(Long processoId, Long membroId) {
        return repository.findByProcessoIdAndMembroIdOrderByDataEnvioAsc(processoId, membroId);
    }

    @Transactional
    public void marcarComoLidas(Long processoId, Long membroId, RemetenteMensagemAvaliador remetente, Long remetenteId) {
        List<MensagemAvaliador> naoLidas = listarThread(processoId, membroId).stream()
            .filter(m -> !m.isLida() && m.getRemetente() == remetente && !m.getRemetenteId().equals(remetenteId))
            .toList();
        for (MensagemAvaliador m : naoLidas) {
            m.setLida(true);
        }
        if (!naoLidas.isEmpty()) {
            repository.saveAll(naoLidas);
        }
    }

    /** Total de mensagens de AVALIADORES nao lidas por nenhum ADMIN/OPERADOR (caixa compartilhada), somando todos os processos. */
    @Transactional(readOnly = true)
    public long contarNaoLidasParaOperador() {
        return repository.countByLidaFalseAndRemetente(RemetenteMensagemAvaliador.AVALIADOR);
    }

    /** Total de mensagens do OPERADOR ainda nao lidas por um membro especifico, somando todos os processos em que ele avalia. */
    @Transactional(readOnly = true)
    public long contarNaoLidasParaMembro(Long membroId) {
        return repository.countByRemetenteAndLidaFalseAndMembroId(RemetenteMensagemAvaliador.OPERADOR, membroId);
    }

    /** Nao lidas de UMA thread (processo, membro) especifica, do ponto de vista do OPERADOR - badge por linha da tabela "Respostas dos Avaliadores". */
    @Transactional(readOnly = true)
    public long contarNaoLidasPorThreadParaOperador(Long processoId, Long membroId) {
        return repository.countByProcessoIdAndMembroIdAndLidaFalseAndRemetente(
            processoId, membroId, RemetenteMensagemAvaliador.AVALIADOR);
    }

    /**
     * Ja existe alguma mensagem (de qualquer lado, lida ou nao) nesta
     * thread? Decide se o card do chat nasce expandido (ver CLAUDE.md,
     * 2026-08-07): recolhido so quando a conversa esta genuinamente vazia,
     * evitando ocupar espaco a toa num processo sem nenhuma mensagem.
     */
    @Transactional(readOnly = true)
    public boolean existeConversa(Long processoId, Long membroId) {
        return repository.countByProcessoIdAndMembroId(processoId, membroId) > 0;
    }

    @Transactional
    public void apagar(Long mensagemId, Long remetenteId, RemetenteMensagemAvaliador remetente) {
        MensagemAvaliador msg = repository.findById(mensagemId)
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
     * Mensagem projetada pro chat, ja relativa a quem esta vendo - mesmo
     * principio de {@link MensagemSolicitacaoService.MensagemChatView}, mas
     * aqui vira tambem CONTROLE DE SEGURANCA (nao so higiene de codigo): o
     * servidor nunca entrega a entidade inteira ao template, fechando por
     * design o risco de um {@code th:text} futuro vazar dado alem do que a
     * tela realmente usa.
     */
    public record MensagemChatView(Long id, boolean deVoce, String nomeRemetente, String texto,
                                    String dataEnvioIso, String dataEnvioFormatada,
                                    boolean lida, boolean deletada, boolean podeApagar) {}

    /**
     * @param remetenteAtual   papel de quem esta vendo (AVALIADOR no Portal do Avaliador, OPERADOR na tela do processo)
     * @param remetenteAtualId id do usuario logado
     * @param labelEu          rotulo pras mensagens do proprio papel ("Voce")
     * @param labelOutro       rotulo pras mensagens do outro lado ("Equipe CET-RS" no portal, o nome do medico no lado operador)
     */
    @Transactional(readOnly = true)
    public List<MensagemChatView> paraChat(Long processoId, Long membroId, RemetenteMensagemAvaliador remetenteAtual,
                                            Long remetenteAtualId, String labelEu, String labelOutro) {
        return listarThread(processoId, membroId).stream()
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

    /**
     * Resumo por thread (processo, membro) para a caixa de entrada do
     * operador (F5): a mensagem mais recente de cada par + quantas dela
     * ainda estao nao lidas pelo lado OPERADOR. Agrupado em Java (nao
     * paginado nesta leva - mesmo criterio de volume pequeno usado para as
     * buscas de Membros/Usuarios/Controle de Urgencias).
     */
    public record ResumoThread(Long processoId, String numeroProcesso, Long membroId, String membroRotulo,
                                String ultimaMensagemPreview, LocalDateTime ultimaMensagemData, long naoLidas) {}

    @Transactional(readOnly = true)
    public List<ResumoThread> listarCaixaDeEntradaOperador() {
        List<MensagemAvaliador> todas = repository.findAllComProcessoEMembroOrderByDataEnvioDesc();
        Map<String, ResumoThread> porThread = new LinkedHashMap<>();
        Map<String, Long> naoLidasPorThread = new LinkedHashMap<>();
        for (MensagemAvaliador m : todas) {
            String chave = m.getProcesso().getId() + ":" + m.getMembro().getId();
            if (!m.isLida() && m.getRemetente() == RemetenteMensagemAvaliador.AVALIADOR) {
                naoLidasPorThread.merge(chave, 1L, Long::sum);
            }
            if (!porThread.containsKey(chave)) {
                String preview = m.isDeletada() ? "(mensagem apagada)"
                    : (m.getTexto() != null && m.getTexto().length() > 80 ? m.getTexto().substring(0, 80) + "..." : m.getTexto());
                porThread.put(chave, new ResumoThread(
                    m.getProcesso().getId(), m.getProcesso().getNumero(),
                    m.getMembro().getId(), m.getMembro().getRotulo(),
                    preview, m.getDataEnvio(), 0L));
            }
        }
        return porThread.entrySet().stream()
            .map(e -> {
                ResumoThread r = e.getValue();
                long naoLidas = naoLidasPorThread.getOrDefault(e.getKey(), 0L);
                return new ResumoThread(r.processoId(), r.numeroProcesso(), r.membroId(), r.membroRotulo(),
                    r.ultimaMensagemPreview(), r.ultimaMensagemData(), naoLidas);
            })
            .sorted((a, b) -> b.ultimaMensagemData().compareTo(a.ultimaMensagemData()))
            .toList();
    }
}

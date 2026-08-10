package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.MensagemSolicitacao;
import br.gov.saude.sgpur.domain.MensagemSolicitacao.RemetenteMensagem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface MensagemSolicitacaoRepository extends JpaRepository<MensagemSolicitacao, Long> {

    List<MensagemSolicitacao> findBySolicitacaoOnlineIdOrderByDataEnvioAsc(Long solicitacaoOnlineId);

    long countByLidaFalseAndRemetente(RemetenteMensagem remetente);

    long countBySolicitacaoOnlineIdAndLidaFalseAndRemetenteAndRemetenteIdNot(Long solicitacaoOnlineId, RemetenteMensagem remetente, Long remetenteId);

    /** Total de mensagens do OPERADOR ainda nao lidas, somando TODAS as solicitacoes de um mesmo solicitante. */
    long countByRemetenteAndLidaFalseAndSolicitacaoOnlineUsuarioSolicitanteId(RemetenteMensagem remetente, Long usuarioSolicitanteId);

    @Query("SELECT DISTINCT m.solicitacaoOnline.id FROM MensagemSolicitacao m WHERE m.lida = false AND m.remetente = :remetente")
    Set<Long> findDistinctSolicitacaoOnlineIdsByLidaFalseAndRemetente(@Param("remetente") RemetenteMensagem remetente);

    /**
     * F6 (S10, docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md, achado A13):
     * UPDATE em lote que substitui o antigo "carregar a thread inteira em
     * Java e filtrar" de {@code MensagemSolicitacaoService.marcarComoLidas} -
     * mesmo padrao de {@code ParecerRepository.registrarUltimoLembrete} e do
     * gemeo {@code MensagemAvaliadorRepository.marcarComoLidasEmLote}. Marca
     * exatamente as mesmas linhas que o codigo Java marcava: do REMETENTE
     * informado, ainda nao lidas, que NAO sejam do proprio usuario chamando.
     */
    @Modifying
    @Query("UPDATE MensagemSolicitacao m SET m.lida = true "
        + "WHERE m.solicitacaoOnline.id = :solicitacaoOnlineId "
        + "AND m.lida = false AND m.remetente = :remetente AND m.remetenteId <> :remetenteId")
    int marcarComoLidasEmLote(@Param("solicitacaoOnlineId") Long solicitacaoOnlineId,
                               @Param("remetente") RemetenteMensagem remetente,
                               @Param("remetenteId") Long remetenteId);
}

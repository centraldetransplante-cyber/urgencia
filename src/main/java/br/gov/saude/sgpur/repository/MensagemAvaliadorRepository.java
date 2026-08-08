package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.MensagemAvaliador;
import br.gov.saude.sgpur.domain.MensagemAvaliador.RemetenteMensagemAvaliador;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MensagemAvaliadorRepository extends JpaRepository<MensagemAvaliador, Long> {

    /** Thread completa do par (processo, membro), em ordem cronologica. */
    List<MensagemAvaliador> findByProcessoIdAndMembroIdOrderByDataEnvioAsc(Long processoId, Long membroId);

    /**
     * Total de mensagens de AVALIADORES (qualquer processo/membro) ainda nao
     * lidas por nenhum ADMIN/OPERADOR - badge global do lado operador
     * (F5, caixa de entrada / navbar). Caixa compartilhada, mesmo design do
     * badge equivalente de {@code MensagemSolicitacaoRepository}.
     */
    long countByLidaFalseAndRemetente(RemetenteMensagemAvaliador remetente);

    /**
     * Total de mensagens do OPERADOR ainda nao lidas por um membro
     * especifico, somando TODOS os processos em que ele e avaliador -
     * badge global do avaliador (F4, poll de layout.html).
     */
    long countByRemetenteAndLidaFalseAndMembroId(RemetenteMensagemAvaliador remetente, Long membroId);

    /** Nao lidas de UMA thread especifica (processo, membro), pelo lado OPERADOR - badge por linha da tabela de Respostas. */
    long countByProcessoIdAndMembroIdAndLidaFalseAndRemetente(Long processoId, Long membroId, RemetenteMensagemAvaliador remetente);

    /**
     * Existe pelo menos 1 mensagem (de qualquer lado) nesta thread? Usado
     * so para decidir se o card de chat nasce expandido ou recolhido na
     * tela (ver CLAUDE.md, secao de 2026-08-07 "chat do avaliador nasce
     * recolhido") - nao e contagem de nao lidas, e existencia de conversa.
     */
    long countByProcessoIdAndMembroId(Long processoId, Long membroId);

    /**
     * Todas as mensagens, mais recente primeiro, com Processo e Membro
     * carregados via fetch join - base da caixa de entrada do operador (F5),
     * que agrupa em Java por (processoId, membroId) para montar o resumo por
     * thread. Sem paginacao nesta leva (mesmo criterio de volume pequeno ja
     * usado para a busca de Membros/Usuarios/Controle de Urgencias) - se o
     * volume crescer, seguir o padrao de ArquivoController/ProcessoListaController.
     */
    @Query("SELECT m FROM MensagemAvaliador m JOIN FETCH m.processo JOIN FETCH m.membro "
        + "ORDER BY m.dataEnvio DESC")
    List<MensagemAvaliador> findAllComProcessoEMembroOrderByDataEnvioDesc();

    /**
     * Nao lidas (do ponto de vista do OPERADOR) agrupadas por membro, para
     * UM processo inteiro numa unica consulta - evita 1 SELECT por parecer no
     * card "Respostas dos Avaliadores" (sempre 3 pareceres,
     * {@code ProcessoService.AVALIADORES_POR_PROCESSO}, mas nao ha motivo pra
     * manter o N+1 so porque N e baixo; ver CLAUDE.md, correcao de 2026-08-08).
     * Membro sem nenhuma nao lida simplesmente nao aparece no resultado (GROUP
     * BY nao gera linha zerada) - o chamador trata ausencia como zero.
     */
    @Query("SELECT m.membro.id, COUNT(m) FROM MensagemAvaliador m "
        + "WHERE m.processo.id = :processoId AND m.lida = false AND m.remetente = :remetente "
        + "GROUP BY m.membro.id")
    List<Object[]> contarNaoLidasPorMembroAgrupado(@Param("processoId") Long processoId,
                                                    @Param("remetente") RemetenteMensagemAvaliador remetente);

    /**
     * Ids dos membros que ja tem PELO MENOS 1 mensagem (de qualquer lado,
     * lida ou nao) neste processo - versao em lote de
     * {@code countByProcessoIdAndMembroId > 0}, mesma motivacao da consulta
     * acima.
     */
    @Query("SELECT DISTINCT m.membro.id FROM MensagemAvaliador m WHERE m.processo.id = :processoId")
    List<Long> membrosComConversa(@Param("processoId") Long processoId);
}

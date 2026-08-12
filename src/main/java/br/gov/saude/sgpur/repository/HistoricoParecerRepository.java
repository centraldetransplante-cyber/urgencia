package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.HistoricoParecer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repositorio da entidade de staging {@link HistoricoParecer} (append-only -
 * ver o javadoc da entidade para o motivo dela existir).
 */
public interface HistoricoParecerRepository extends JpaRepository<HistoricoParecer, Long> {

    /** Historico de um processo, mais recente primeiro - usado no card Respostas e no Relatorio Final. */
    List<HistoricoParecer> findByProcessoIdOrderByArquivadoEmDesc(Long processoId);

    /**
     * Este medico ja pediu informacao complementar NESTE processo em algum
     * momento? E o predicado de POSSE do material encaminhado pelo operador
     * ({@code TipoAnexo.INFO_COMPLEMENTAR_AVALIADOR}): so quem pediu ve a
     * resposta. Um avaliador que nunca pediu nada nunca enxerga esse
     * material - nem na tela, nem no download.
     *
     * <p>Le o historico (append-only) e nao o {@code Parecer} vivo de
     * proposito: o parecer e RESETADO por
     * {@code ProcessoService.retomarAposInformacao}, entao depois da retomada
     * nao sobra nenhum vestigio do pedido no parecer - so aqui.</p>
     */
    boolean existsByProcessoIdAndMembroId(Long processoId, Long membroId);

    /** Ids dos medicos que pediram informacao complementar neste processo (sem duplicar). */
    @Query("select distinct h.membro.id from HistoricoParecer h where h.processo.id = :processoId")
    List<Long> findMembroIdsByProcessoId(@Param("processoId") Long processoId);
}

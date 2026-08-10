package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.HistoricoParecer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repositorio da entidade de staging {@link HistoricoParecer} (append-only -
 * ver o javadoc da entidade para o motivo dela existir).
 */
public interface HistoricoParecerRepository extends JpaRepository<HistoricoParecer, Long> {

    /** Historico de um processo, mais recente primeiro - usado no card Respostas e no Relatorio Final. */
    List<HistoricoParecer> findByProcessoIdOrderByArquivadoEmDesc(Long processoId);
}

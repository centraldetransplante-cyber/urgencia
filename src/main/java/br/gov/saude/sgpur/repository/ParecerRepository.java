package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.Parecer;
import br.gov.saude.sgpur.domain.ResultadoParecer;
import br.gov.saude.sgpur.domain.StatusProcesso;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ParecerRepository extends JpaRepository<Parecer, Long> {

    /**
     * Pareceres efetivamente respondidos e com as duas datas preenchidas, base
     * para o calculo de tempo de resposta dos avaliadores (dias corridos entre
     * dataEnvio e dataResposta). O membro e EAGER, entao vem carregado (sem N+1).
     */
    @Query("select p from Parecer p where p.resultado is not null "
        + "and p.dataEnvio is not null and p.dataResposta is not null")
    List<Parecer> findRespondidosComDatas();

    /** Total de processos em que o membro foi designado avaliador. */
    long countByMembroId(Long membroId);

    /** Pareceres ja respondidos pelo membro. */
    long countByMembroIdAndResultadoNotNull(Long membroId);

    /** Pareceres do membro com um resultado especifico. */
    long countByMembroIdAndResultado(Long membroId, ResultadoParecer resultado);

    /**
     * Pareceres ja respondidos pelo membro em processos PREEMPTIVOS
     * (insercao em lista de espera renal). {@code PreemptivoTrue} =
     * {@code processo.preemptivo = true} - linhas legadas com {@code null}
     * (urgencia renal comum) ficam de fora, que e o correto.
     */
    long countByMembroIdAndResultadoNotNullAndProcesso_PreemptivoTrue(Long membroId);

    /**
     * Pareceres pendentes do membro (resultado nulo, envio ja registrado).
     * Usado pelo Portal do Avaliador para listar os processos que aguardam voto.
     */
    List<Parecer> findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull(Long membroId);

    /** Localiza o parecer de um membro especifico em um processo especifico. */
    Optional<Parecer> findByProcessoIdAndMembroId(Long processoId, Long membroId);

    /**
     * Todos os pareceres de um processo (os 3 avaliadores), sem filtro de
     * resultado — usado por {@code InfoComplementarAvaliadorService} para
     * saber quem pediu informacao complementar e quem ainda vai votar, sem
     * depender da colecao LAZY {@code Processo.pareceres}.
     */
    List<Parecer> findByProcessoId(Long processoId);

    /**
     * Contagem direta (sem carregar nenhuma entidade) de pareceres "pendentes
     * ativos para voto" de um membro — MESMO criterio de
     * {@link br.gov.saude.sgpur.web.AvaliadorController#pendenteAtivoParaVoto}
     * (resultado nulo, envio ja registrado, processo em algum dos status
     * informados — tipicamente ENVIADO e SOLICITA_INFORMACAO, ver
     * {@link StatusProcesso#aceitaVotoAvaliador()}), so que resolvido pelo
     * banco com um {@code count(...)} em vez de trazer as entidades
     * {@code Parecer} inteiras e filtrar em Java navegando
     * {@code Parecer.processo} (LAZY). Usado por
     * {@link br.gov.saude.sgpur.web.GlobalModelAdvice#pendentesAvaliador()},
     * que roda em TODA requisicao do avaliador (e um {@code @ModelAttribute}
     * de {@code @ControllerAdvice}) — a query anterior era um N+1 por render.
     *
     * <p>Recebe uma colecao de status (nao um unico valor) desde 2026-08:
     * bug real corrigido em que a pausa "Solicita informacao" causada por UM
     * avaliador escondia o processo da lista de pendencias/badge dos OUTROS
     * dois avaliadores, que continuavam livres para votar — ver
     * docs/RELATORIO-BUG-PAUSA-BLOQUEIA-OUTROS-AVALIADORES-2026-08.md.</p>
     */
    long countByMembroIdAndResultadoIsNullAndDataEnvioIsNotNullAndProcessoStatusIn(
        Long membroId, Collection<StatusProcesso> status);

    /**
     * Pareceres pendentes (resultado nulo, envio ja registrado) de um processo
     * especifico. Usado para o lembrete manual de avaliacao pendente.
     */
    List<Parecer> findByProcessoIdAndResultadoIsNullAndDataEnvioIsNotNull(Long processoId);

    // -------------------------------------------------------------------------
    // Variantes com fetch join do Processo, usadas pelo Portal do Avaliador
    // (AvaliadorController) desde a remocao do @Transactional de nivel de classe
    // (2026-07-29). Com spring.jpa.open-in-view=false e sem essa transacao de
    // classe, Parecer.processo (LAZY) vira um proxy inutilizavel assim que a
    // consulta original retorna - qualquer navegacao a getProcesso() fora de uma
    // transacao aberta (ex.: getStatus/getNumero/getPacienteNome) lancaria
    // LazyInitializationException. Estas consultas carregam o Processo na MESMA
    // query (join fetch), entao ele chega como objeto real, nao um proxy - sem
    // precisar de nenhuma transacao adicional no controller. Os metodos originais
    // acima permanecem intocados (mesma assinatura, mesmo comportamento) para nao
    // afetar outros chamadores/testes que dependam deles.
    // -------------------------------------------------------------------------

    /**
     * Mesmo criterio de {@link #findByMembroIdAndResultadoIsNullAndDataEnvioIsNotNull},
     * com o Processo associado carregado via fetch join.
     */
    @Query("select p from Parecer p left join fetch p.processo "
        + "where p.membro.id = :membroId and p.resultado is null and p.dataEnvio is not null")
    List<Parecer> findPendentesComProcesso(@Param("membroId") Long membroId);

    /**
     * Pareceres ja votados pelo membro (resultado != null), do mais recente para
     * o mais antigo, com o Processo associado carregado via fetch join. Usado
     * pelo historico do Portal do Avaliador.
     */
    @Query("select p from Parecer p left join fetch p.processo "
        + "where p.membro.id = :membroId and p.resultado is not null order by p.dataResposta desc")
    List<Parecer> findHistoricoComProcesso(@Param("membroId") Long membroId);

    /**
     * Pareceres NUNCA votados (resultado null) cujo processo ja foi decidido
     * (status finalizado) - o avaliador foi DISPENSADO por maioria simples
     * ou pela excecao do coordenador antes de conseguir votar. Achado 10 do
     * relatorio de vistoria de brechas (2026-08-10): antes o processo
     * simplesmente "evaporava" da tela do avaliador sem explicacao nenhuma -
     * nao aparece mais em pendentes (status deixou de aceitar voto) e nunca
     * apareceu no historico ({@link #findHistoricoComProcesso} exige
     * resultado != null). {@code statusFinal} e passado pelo caller (lista
     * de status finalizados) em vez de fixo aqui, mesmo padrao ja usado em
     * {@code ProcessoRepository.buscarEncerrados}.
     */
    @Query("select p from Parecer p left join fetch p.processo "
        + "where p.membro.id = :membroId and p.resultado is null "
        + "and p.processo.status in :statusFinal order by p.processo.dataDecisao desc")
    List<Parecer> findDispensadosComProcesso(@Param("membroId") Long membroId,
                                              @Param("statusFinal") List<StatusProcesso> statusFinal);

    /**
     * Mesmo criterio de {@link #findByProcessoIdAndMembroId}, com o Processo
     * associado carregado via fetch join - usado quando o chamador precisa
     * navegar parecer.getProcesso() (ex.: checar o status antes de liberar o
     * voto em resolverParecerPendente).
     */
    @Query("select p from Parecer p left join fetch p.processo "
        + "where p.processo.id = :processoId and p.membro.id = :membroId")
    Optional<Parecer> findByProcessoIdAndMembroIdComProcesso(
        @Param("processoId") Long processoId, @Param("membroId") Long membroId);

    /**
     * Reivindica atomicamente o envio do convite ao Portal do Avaliador para
     * UM parecer: so marca {@code conviteEnviadoEm = :agora} se ele nunca foi
     * enviado ({@code IS NULL}) ou se o ultimo envio foi antes de
     * {@code :limiteJanela} (fora da janela de duplo-clique). E um UPDATE
     * condicional de linha unica, nao um "ler no Java, decidir, gravar depois"
     * - fecha de verdade a janela de corrida entre duas execucoes concorrentes
     * de {@code RegistroEnvioService.enviarConvitesAvaliadores} (bug real de
     * producao, 2026-08-03: duplo clique em "Registrar envio" mandou o convite
     * 2x aos 3 avaliadores do processo 05/2026, ~3s de defasagem entre as duas
     * execucoes). Retorna 1 se este chamador "ganhou" a reivindicacao (deve
     * enviar o e-mail agora), 0 se outra execucao ja reivindicou dentro da
     * janela (deve pular como duplicata).
     */
    @Modifying
    @Query("update Parecer p set p.conviteEnviadoEm = :agora where p.id = :parecerId "
        + "and (p.conviteEnviadoEm is null or p.conviteEnviadoEm < :limiteJanela)")
    int reivindicarConviteSeElegivel(@Param("parecerId") Long parecerId,
                                      @Param("agora") LocalDateTime agora,
                                      @Param("limiteJanela") LocalDateTime limiteJanela);

    /**
     * Grava o momento do LEMBRETE manual mais recente para um parecer
     * (`ProcessoDecisaoController.lembreteAvaliador`/`lembretePendentes`),
     * chamado depois que o e-mail ja foi confirmado como enviado. UPDATE de
     * linha unica sem condicao (ao contrario de
     * {@link #reivindicarConviteSeElegivel}: aqui nao ha janela de duplo-clique
     * a fechar, o operador pode legitimamente reenviar o lembrete quantas
     * vezes quiser - o campo so registra "quando foi a ultima vez").
     */
    @Modifying
    @Query("update Parecer p set p.ultimoLembreteEm = :agora where p.id = :parecerId")
    int registrarUltimoLembrete(@Param("parecerId") Long parecerId, @Param("agora") LocalDateTime agora);
}

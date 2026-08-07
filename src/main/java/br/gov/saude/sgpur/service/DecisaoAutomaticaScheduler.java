package br.gov.saude.sgpur.service;

import br.gov.saude.sgpur.domain.Processo;
import br.gov.saude.sgpur.domain.StatusProcesso;
import br.gov.saude.sgpur.repository.ProcessoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

/**
 * Varredor periodico de decisao automatica.
 *
 * <p><b>Por que existe.</b> {@link ProcessoService#tentarDecisaoAutomatica} e
 * orientada a EVENTO: so roda quando um voto e registrado no Portal do
 * Avaliador ou logo apos {@code retomarAposInformacao}. Um processo cuja
 * maioria ja esta formada mas que nao vai receber nenhum evento novo fica
 * parado <b>para sempre</b>, esperando uma decisao manual que deveria ser
 * automatica. Isso nao e so um problema de migracao (processos cujos votos
 * entraram antes de a automacao existir em producao — caso real do processo
 * {@code 03/2026}); e um buraco permanente: quando o ADMIN reabre um processo
 * ja votado ({@code POST /processos/{id}/reabrir}), o status volta para
 * ENVIADO com todos os pareceres presentes e, de novo, nenhum voto novo chega
 * para disparar a automacao.</p>
 *
 * <p><b>O que este componente NAO faz.</b> Ele nao contem regra de decisao
 * nenhuma. Toda a regra (maioria simples, excecao do coordenador CET-RS,
 * motivo institucional impessoal do indeferimento, validacao final via
 * {@code decidir}) continua exclusivamente em
 * {@link ProcessoService#tentarDecisaoAutomatica} / {@link ProcessoValidator}.
 * O varredor apenas descobre <i>quem</i> avaliar e delega. Se um dia a regra
 * mudar, muda la e este componente segue correto.</p>
 *
 * <p><b>Transacoes.</b> {@link #varrer()} NAO e {@code @Transactional} de
 * proposito. Se fosse, toda a varredura rodaria numa transacao unica e a falha
 * de um processo (lock otimista, erro ao gerar PDF) marcaria a transacao
 * compartilhada como rollback-only, estourando {@code UnexpectedRollbackException}
 * no commit e desfazendo as decisoes dos demais — exatamente o bug corrigido em
 * {@code AvaliadorController.registrarVoto}. Aqui cada processo tem a sua
 * decisao commitada numa transacao propria (o {@code @Transactional} do
 * servico, que sem transacao externa abre uma nova) e a geracao dos documentos
 * roda depois, numa terceira transacao curta e independente
 * ({@link TransactionTemplate}), cuja falha ja nao pode desfazer a decisao.</p>
 *
 * <p><b>Conservador por construcao.</b> Na duvida, nao decide: qualquer
 * pre-condicao nao satisfeita faz {@code tentarDecisaoAutomatica} devolver o
 * processo intacto, e o operador continua com a decisao manual.</p>
 *
 * <p>Registrado apenas quando {@code app.decisao-automatica.varredura.habilitado=true}
 * (default: desligado em dev/teste, ligado em producao — ver
 * {@code application-prod.yml}).</p>
 */
@Component
@ConditionalOnProperty(
    prefix = "app.decisao-automatica.varredura", name = "habilitado", havingValue = "true")
public class DecisaoAutomaticaScheduler {

    private static final Logger log = LoggerFactory.getLogger(DecisaoAutomaticaScheduler.class);

    /**
     * Acao de auditoria propria do varredor. NAO reusa {@code PROCESSO_DECIDIDO}
     * (decisao manual/por voto) de proposito: uma decisao tomada pelo sistema,
     * sem nenhuma acao humana no momento, precisa ser distinguivel na trilha —
     * o usuario do log sera "sistema" (nao ha SecurityContext numa thread
     * agendada) e o IP fica nulo.
     */
    static final String ACAO_AUDITORIA = "PROCESSO_DECIDIDO_VARREDURA";

    /**
     * Status varridos. Inclui ENVIADO ("em analise") e tambem
     * SOLICITA_INFORMACAO.
     *
     * <p><b>Por que SOLICITA_INFORMACAO entra.</b> A pausa por informacao
     * complementar bloqueia a decisao — com UMA excecao ja aprovada e vigente
     * no sistema: o voto Favoravel do coordenador CET-RS defere sozinho e
     * imediatamente, <i>mesmo com o processo pausado</i> pelo pedido de outro
     * avaliador ({@code tentarDecisaoAutomatica} e
     * {@code ProcessoValidator.validarPausaDecisao}). Ou seja, existe um caso
     * real em que o deferimento ja e devido e o processo esta em
     * SOLICITA_INFORMACAO; se a decisao falhou no instante do voto (erro de
     * PDF, lock otimista) ou o voto e anterior a automacao, ele trava
     * permanentemente — exatamente o buraco que este varredor fecha. Deixar
     * esse status de fora reintroduziria o bug para a unica situacao em que a
     * pausa nao vale.</p>
     *
     * <p>Nenhuma regra nova e criada: {@link #elegivel} exige explicitamente o
     * voto favoravel do coordenador para sequer considerar um processo pausado,
     * e o proprio servico barra o resto. SOLICITADO fica de fora (processo nem
     * foi enviado aos avaliadores) e os finais, obviamente, tambem.</p>
     */
    static final Set<StatusProcesso> STATUS_VARRIDOS = Set.of(
        StatusProcesso.ENVIADO,
        StatusProcesso.SOLICITA_INFORMACAO);

    private final ProcessoRepository processoRepository;
    private final ProcessoService processoService;
    private final ProcessoValidator validator;
    private final DecisaoFinalService decisaoFinalService;
    private final AuditoriaService auditoria;
    /** Usado SO para a geracao dos documentos: transacao curta e propria. */
    private final TransactionTemplate txTemplate;

    public DecisaoAutomaticaScheduler(ProcessoRepository processoRepository,
                                      ProcessoService processoService,
                                      ProcessoValidator validator,
                                      DecisaoFinalService decisaoFinalService,
                                      AuditoriaService auditoria,
                                      PlatformTransactionManager txManager) {
        this.processoRepository = processoRepository;
        this.processoService = processoService;
        this.validator = validator;
        this.decisaoFinalService = decisaoFinalService;
        this.auditoria = auditoria;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Roda a varredura. Intervalo e atraso inicial configuraveis; o atraso
     * inicial evita concorrer com o boot da aplicacao (bootstrap do admin,
     * validacoes de schema) na primeira execucao.
     *
     * <p>{@code fixedDelay} (nao {@code fixedRate}): o intervalo conta a partir
     * do FIM da execucao anterior, entao duas varreduras nunca se sobrepoem
     * mesmo que uma demore (geracao de PDF de varios processos).</p>
     */
    @Scheduled(
        fixedDelayString = "${app.decisao-automatica.varredura.intervalo-ms}",
        initialDelayString = "${app.decisao-automatica.varredura.atraso-inicial-ms}")
    public void varrerAgendado() {
        varrer();
    }

    /**
     * Avalia todos os candidatos. NAO e {@code @Transactional} (ver javadoc da
     * classe). A falha de um processo e isolada: loga e segue para o proximo.
     *
     * @return quantos processos foram decididos nesta passada.
     */
    public int varrer() {
        List<Processo> candidatos;
        try {
            candidatos = processoRepository.findCandidatosDecisaoAutomatica(STATUS_VARRIDOS);
        } catch (RuntimeException e) {
            // Banco fora do ar / query invalida: a proxima passada tenta de novo.
            log.error("Varredura de decisao automatica: falha ao buscar candidatos", e);
            return 0;
        }

        int decididos = 0;
        for (Processo candidato : candidatos) {
            if (!elegivel(candidato)) {
                continue;
            }
            if (avaliar(candidato.getId(), candidato.getNumero())) {
                decididos++;
            }
        }
        if (decididos > 0) {
            log.info("Varredura de decisao automatica: {} processo(s) decidido(s) de {} candidato(s).",
                decididos, candidatos.size());
        }
        return decididos;
    }

    /**
     * Pre-filtro em memoria (sobre o processo ja com pareceres/membros
     * carregados pelo fetch join). Serve para nao chamar o servico a toa e,
     * principalmente, como <b>defesa em profundidade</b>: um processo pausado
     * so e considerado quando o coordenador CET-RS votou favoravel, sem
     * depender apenas da mesma checagem feita la dentro.
     *
     * <p><b>Pausa ativa = status OU fato</b> (achado C do docs/RELATORIO-BUG-
     * DOIS-VOTOS-DEFEREM-DURANTE-PAUSA-2026-08.md — ver
     * {@link ProcessoValidator#temPedidoInformacaoAtivo}): {@code
     * findCandidatosDecisaoAutomatica} ja filtra por {@link #STATUS_VARRIDOS}
     * (ENVIADO/SOLICITA_INFORMACAO), entao um processo com status
     * dessincronizado do fato (ex.: {@code ENVIADO} com um parecer
     * {@code SOLICITA_INFORMACAO} ainda vivo, cenario que {@link
     * ProcessoService#reabrir} deixava de produzir apos a correcao do achado
     * C) ainda passa por aqui — este pre-filtro precisa reconhecer o mesmo
     * fato que o servico reconhece, coerentemente, senao a varredura vira o
     * elo mais fraco da cadeia.</p>
     */
    private boolean elegivel(Processo p) {
        if (p.getStatus() == null || p.getStatus().isFinalizado()) {
            return false;                       // corrida com uma decisao manual
        }
        boolean pausaAtiva = p.getStatus() == StatusProcesso.SOLICITA_INFORMACAO
            || validator.temPedidoInformacaoAtivo(p);
        if (pausaAtiva && !validator.temVotoCoordenadorFavoravel(p)) {
            return false;                       // pausa vale: nao decide
        }
        return validator.sugerirDecisao(p).isPresent();
    }

    /**
     * Avalia UM processo, isolando qualquer falha dele dos demais.
     *
     * @return true se o processo foi de fato decidido nesta chamada.
     */
    private boolean avaliar(Long id, String numero) {
        Processo decidido;
        try {
            // Transacao propria do servico (nao ha transacao externa aqui):
            // commita a decisao antes de qualquer passo seguinte.
            decidido = processoService.tentarDecisaoAutomatica(id);
        } catch (RuntimeException e) {
            // IllegalStateException (pre-condicao que mudou entre a leitura e
            // agora), ObjectOptimisticLockingFailureException (alguem decidiu
            // no mesmo instante), falha de conexao... nada disso pode abortar a
            // varredura dos outros processos.
            log.warn("Varredura de decisao automatica: processo {} (id={}) nao pode ser decidido: {}",
                numero, id, e.toString());
            return false;
        }
        if (decidido == null || !decidido.getStatus().isFinalizado()) {
            return false;                       // nao havia maioria/pre-condicao: segue manual
        }

        // Auditoria ANTES da geracao dos documentos: a decisao ja esta
        // commitada e a trilha nao pode depender do sucesso de um passo
        // opcional posterior.
        auditoria.registrar(ACAO_AUDITORIA,
            "Processo " + numero + " - " + decidido.getStatus().getDescricao()
            + " pela varredura automatica do sistema (sem acao humana): maioria ja formada "
            + "quando nenhum voto novo dispararia a decisao.");

        try {
            // Oficio/Relatorio Final navegam colecoes LAZY: precisam de sessao
            // aberta e do processo RECARREGADO dentro dela. Transacao curta e
            // INDEPENDENTE — falha aqui nao desfaz a decisao ja commitada nem
            // contamina os proximos processos da varredura.
            txTemplate.executeWithoutResult(status ->
                decisaoFinalService.gerarDocumentos(processoService.buscar(id)));
        } catch (RuntimeException e) {
            log.warn("Varredura de decisao automatica: processo {} decidido como {}, "
                    + "mas falhou ao gerar os documentos finais: {}",
                numero, decidido.getStatus().getDescricao(), e.toString());
        }
        return true;
    }
}

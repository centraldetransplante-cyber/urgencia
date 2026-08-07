package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Parecer de um membro da Urgencia Renal sobre um processo.
 *
 * Regra de negocio: todos os membros ativos avaliam o processo, EXCETO
 * quando o membro for o proprio solicitante daquele processo (conflito de
 * interesse) - nesse caso "impedido = true" e nao ha resultado.
 */
@Entity
@Table(
    name = "parecer",
    uniqueConstraints = @UniqueConstraint(columnNames = {"processo_id", "membro_id"})
)
public class Parecer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "processo_id", nullable = false)
    private Processo processo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "membro_id", nullable = false)
    private MembroUrgenciaRenal membro;

    /** Resultado do parecer; nulo enquanto o membro nao respondeu. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ResultadoParecer resultado;

    /** Membro impedido por ser o solicitante do processo (conflito). */
    @Column(nullable = false)
    private boolean impedido = false;

    @Column(name = "data_envio")
    private LocalDate dataEnvio;

    @Column(name = "data_resposta")
    private LocalDate dataResposta;

    /**
     * Como o voto foi registrado: sempre pelo proprio avaliador autenticado
     * no portal.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private OrigemParecer origem;

    /** Data e hora exatos do voto (preenchido pelo portal do avaliador). */
    @Column(name = "data_hora_voto")
    private LocalDateTime dataHoraVoto;

    /**
     * Quando o convite ao Portal do Avaliador foi disparado pela ultima vez
     * para este parecer. Nulo enquanto nunca foi enviado. Usado apenas para
     * fechar a janela de duplo-clique/duplo-POST de
     * {@code RegistroEnvioService.enviarConvitesAvaliadores} (bug real de
     * producao em 2026-08-03: clique duplo em "Registrar envio" mandou o
     * convite 2x para os 3 avaliadores) - NAO confundir com {@code dataEnvio}
     * (data em que o processo foi enviado para avaliacao, imutavel pelo
     * reenvio de convite) nem com {@code dataHoraVoto} (quando o medico
     * efetivamente votou). Nullable de proposito: coluna nova numa tabela ja
     * populada nasce NULL, que e semanticamente correto ("nunca enviado") e
     * dispensa backfill manual em prod (ao contrario de uma coluna tratada
     * como obrigatoria, ex. @Version - ver CLAUDE.md).
     */
    @Column(name = "convite_enviado_em")
    private LocalDateTime conviteEnviadoEm;

    /**
     * Quando o LEMBRETE manual ao avaliador foi disparado pela ultima vez
     * ({@code ProcessoDecisaoController.lembreteAvaliador}/{@code lembretePendentes}).
     * Nulo enquanto nenhum lembrete foi enviado a este parecer. Distinto de
     * {@code conviteEnviadoEm} (o convite AUTOMATICO disparado uma vez ao
     * registrar o envio) - este campo acompanha os lembretes manuais
     * repetidos que o operador dispara depois, para saber se ja lembrou hoje
     * ou ha 2 semanas sem precisar consultar a auditoria. Nullable de
     * proposito: coluna nova numa tabela ja populada nasce NULL, que e
     * semanticamente correto ("nunca lembrado") e dispensa backfill manual em
     * prod (ao contrario de uma coluna tratada como obrigatoria, ex.
     * @Version - ver CLAUDE.md).
     */
    @Column(name = "ultimo_lembrete_em")
    private LocalDateTime ultimoLembreteEm;

    /**
     * Username de quem registrou o voto (para nao-repudio). Operador que lancou
     * o resultado em nome do medico, ou o proprio medico autenticado.
     */
    @Column(name = "votado_por", length = 120)
    private String votadoPor;

    /**
     * Justificativa / observacoes clinicas que o avaliador digitou ao votar no
     * portal. Material INTERNO do operador para subsidiar a decisao — NUNCA e
     * exibida a outros avaliadores (imparcialidade do julgamento). Nula quando
     * o medico nao escreveu nada.
     */
    @Column(name = "justificativa", columnDefinition = "TEXT")
    private String justificativa;

    /**
     * Controle de concorrencia otimista, igual a Processo.versao: sem isso,
     * dois votos simultaneos no mesmo parecer (ex.: abas duplicadas, clique
     * duplo) se sobrescrevem silenciosamente em vez de disparar
     * OptimisticLockException.
     */
    @Version
    @Column(name = "versao")
    private Long versao;

    /**
     * Snapshot de {@code MembroUrgenciaRenal.coordenador} capturado no
     * INSTANTE em que este voto foi registrado
     * ({@code AvaliadorController.registrarVoto}). Existe porque
     * {@code ProcessoValidator.temVotoCoordenadorFavoravel} lia
     * {@code coordenador} "ao vivo" navegando {@code parecer.getMembro()} --
     * se o cargo de coordenador mudasse de mao DEPOIS do voto (outro medico
     * assume, ou o proprio deixa de ser) mas ANTES da decisao final, o peso
     * do voto antigo mudava retroativamente (Achado 4 da "Vistoria de bugs de
     * 2026-08-03", implementado em 2026-08-07 mediante aprovacao explicita do
     * dono do produto). Com o snapshot, o voto vale o que valia no momento em
     * que foi dado, sempre.
     *
     * <p><b>Nullable de proposito, SEM backfill obrigatorio</b> (mesmo padrao
     * de {@code conviteEnviadoEm}/{@code ultimoLembreteEm}): pareceres
     * ANTIGOS (votados antes desta mudanca) nascem com este campo {@code
     * null}. A leitura trata {@code null} como "nao sabemos, nao conta como
     * voto de coordenador" -- decisao conservadora deliberada: preferimos
     * negar retroativamente o peso especial a um voto antigo (o processo cai
     * de volta na regra padrao de maioria 2 de 3, que ainda pode decidir
     * corretamente com os demais votos) a inferir esse peso de um dado que
     * nunca foi de fato capturado no momento do voto.</p>
     */
    @Column(name = "era_coordenador_no_voto")
    private Boolean eraCoordenadorNoVoto;

    public Parecer() {
    }

    public Parecer(MembroUrgenciaRenal membro) {
        this.membro = membro;
    }

    @Transient
    public boolean isRespondido() {
        return resultado != null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Processo getProcesso() {
        return processo;
    }

    public void setProcesso(Processo processo) {
        this.processo = processo;
    }

    public MembroUrgenciaRenal getMembro() {
        return membro;
    }

    public void setMembro(MembroUrgenciaRenal membro) {
        this.membro = membro;
    }

    public ResultadoParecer getResultado() {
        return resultado;
    }

    public void setResultado(ResultadoParecer resultado) {
        this.resultado = resultado;
    }

    public boolean isImpedido() {
        return impedido;
    }

    public void setImpedido(boolean impedido) {
        this.impedido = impedido;
    }

    public LocalDate getDataEnvio() {
        return dataEnvio;
    }

    public void setDataEnvio(LocalDate dataEnvio) {
        this.dataEnvio = dataEnvio;
    }

    public LocalDate getDataResposta() {
        return dataResposta;
    }

    public void setDataResposta(LocalDate dataResposta) {
        this.dataResposta = dataResposta;
    }

    public OrigemParecer getOrigem() {
        return origem;
    }

    public void setOrigem(OrigemParecer origem) {
        this.origem = origem;
    }

    public LocalDateTime getDataHoraVoto() {
        return dataHoraVoto;
    }

    public void setDataHoraVoto(LocalDateTime dataHoraVoto) {
        this.dataHoraVoto = dataHoraVoto;
    }

    public LocalDateTime getConviteEnviadoEm() {
        return conviteEnviadoEm;
    }

    public void setConviteEnviadoEm(LocalDateTime conviteEnviadoEm) {
        this.conviteEnviadoEm = conviteEnviadoEm;
    }

    public LocalDateTime getUltimoLembreteEm() {
        return ultimoLembreteEm;
    }

    public void setUltimoLembreteEm(LocalDateTime ultimoLembreteEm) {
        this.ultimoLembreteEm = ultimoLembreteEm;
    }

    public String getVotadoPor() {
        return votadoPor;
    }

    public void setVotadoPor(String votadoPor) {
        this.votadoPor = votadoPor;
    }

    public String getJustificativa() {
        return justificativa;
    }

    public void setJustificativa(String justificativa) {
        this.justificativa = justificativa;
    }

    public Long getVersao() {
        return versao;
    }

    public Boolean getEraCoordenadorNoVoto() {
        return eraCoordenadorNoVoto;
    }

    public void setEraCoordenadorNoVoto(Boolean eraCoordenadorNoVoto) {
        this.eraCoordenadorNoVoto = eraCoordenadorNoVoto;
    }
}

package br.gov.saude.sgpur.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Snapshot de um {@link Parecer} ARQUIVADO antes de ser sobreposto/resetado -
 * entidade de STAGING, append-only, no espirito de {@code SolicitacaoOnline}/
 * {@code AnexoSolicitacaoOnline} (nunca se mistura ao ciclo de vida do
 * {@link Parecer} real).
 *
 * <p>Existe para o Achado 7 do
 * {@code docs/RELATORIO-VISTORIA-BRECHAS-DECISAO-2026-08-10.md}: quando um
 * avaliador vota {@code SOLICITA_INFORMACAO} e o operador retoma a analise
 * ({@code ProcessoService.retomarAposInformacao}), o parecer daquele
 * avaliador e resetado por completo ("pendencia limpa", ver o javadoc do
 * metodo) - a JUSTIFICATIVA CLINICA do pedido, que o proprio sistema EXIGE
 * no momento do voto {@code SOLICITA_INFORMACAO}, era destruida sem deixar
 * nenhum rastro fora da auditoria (ADMIN-only, texto livre nao estruturado).
 * </p>
 *
 * <p><b>Decisao de produto ja aprovada (opcao "a" do relatorio): guarda o
 * TEXTO COMPLETO</b> (nao so o fato de que houve uma pausa - opcao "b",
 * mais barata, foi preterida). Gravado ANTES do reset, no mesmo metodo,
 * como um passo ADICIONAL - <b>nao altera em nada o reset em si</b>, que
 * continua identico (preserva o nao-repudio do voto definitivo).</p>
 */
@Entity
@Table(name = "historico_parecer")
public class HistoricoParecer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "processo_id", nullable = false)
    private Processo processo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membro_id", nullable = false)
    private MembroUrgenciaRenal membro;

    /** Resultado do parecer no momento em que foi arquivado (ex.: SOLICITA_INFORMACAO). */
    @Enumerated(EnumType.STRING)
    @Column(name = "resultado", length = 30, nullable = false)
    private ResultadoParecer resultado;

    @Column(name = "data_envio")
    private LocalDate dataEnvio;

    @Column(name = "data_resposta")
    private LocalDate dataResposta;

    @Column(name = "data_hora_voto")
    private LocalDateTime dataHoraVoto;

    @Column(name = "votado_por", length = 120)
    private String votadoPor;

    @Enumerated(EnumType.STRING)
    @Column(name = "origem", length = 30)
    private OrigemParecer origem;

    /**
     * Justificativa clinica completa do pedido - o dado que este historico
     * existe para preservar (ver javadoc da classe).
     */
    @Column(name = "justificativa", columnDefinition = "TEXT")
    private String justificativa;

    /** Momento em que este snapshot foi arquivado (sempre "agora" - nunca editavel depois). */
    @Column(name = "arquivado_em", nullable = false)
    private LocalDateTime arquivadoEm = LocalDateTime.now();

    /**
     * Motivo curto do arquivamento (ex.: "Retomada da análise após pedido de
     * informação complementar") - hoje sempre o mesmo texto, mas em campo
     * proprio (nao hardcoded na leitura) para o dia em que outro motivo de
     * arquivamento existir.
     */
    @Column(name = "motivo_arquivamento", length = 200, nullable = false)
    private String motivoArquivamento;

    public HistoricoParecer() {
    }

    /**
     * Constroi o snapshot a partir do {@link Parecer} ainda NAO resetado -
     * chamar sempre ANTES de zerar os campos do parecer original.
     */
    public static HistoricoParecer deParecer(Parecer par, String motivoArquivamento) {
        HistoricoParecer h = new HistoricoParecer();
        h.processo = par.getProcesso();
        h.membro = par.getMembro();
        h.resultado = par.getResultado();
        h.dataEnvio = par.getDataEnvio();
        h.dataResposta = par.getDataResposta();
        h.dataHoraVoto = par.getDataHoraVoto();
        h.votadoPor = par.getVotadoPor();
        h.origem = par.getOrigem();
        h.justificativa = par.getJustificativa();
        h.motivoArquivamento = motivoArquivamento;
        return h;
    }

    public Long getId() {
        return id;
    }

    public Processo getProcesso() {
        return processo;
    }

    public MembroUrgenciaRenal getMembro() {
        return membro;
    }

    public ResultadoParecer getResultado() {
        return resultado;
    }

    public LocalDate getDataEnvio() {
        return dataEnvio;
    }

    public LocalDate getDataResposta() {
        return dataResposta;
    }

    public LocalDateTime getDataHoraVoto() {
        return dataHoraVoto;
    }

    public String getVotadoPor() {
        return votadoPor;
    }

    public OrigemParecer getOrigem() {
        return origem;
    }

    public String getJustificativa() {
        return justificativa;
    }

    public LocalDateTime getArquivadoEm() {
        return arquivadoEm;
    }

    public String getMotivoArquivamento() {
        return motivoArquivamento;
    }
}

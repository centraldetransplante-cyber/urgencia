package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Controle das urgencias para transplante renal.
 * <p>
 * Cada registro representa um paciente com urgencia ativa que precisa ser
 * renovada periodicamente a cada 30 dias, conforme a regra: se o receptor
 * permanecer ativo na lista unica, faz-se a renovacao sem nova solicitacao.
 * <p>
 * Tabela independente, vinculada opcionalmente ao Processo de origem.
 */
@Entity
@Table(name = "controle_urgencia")
public class ControleUrgencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nome completo do paciente/receptor. */
    @Column(nullable = false, length = 200)
    private String nomePaciente;

    /** Registro RGCT / SNT. */
    @Column(length = 60)
    private String rgct;

    /** Equipe solicitante. */
    @Column(length = 200)
    private String equipe;

    /** Tipo sanguineo (A, B, AB, O). */
    @Column(length = 3)
    private String abo;

    /** Situacao atual: ATIVA, RENOVADA, EXPIRADA, CANCELADA. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SituacaoUrgencia situacao = SituacaoUrgencia.ATIVA;

    /** Data de vencimento da urgencia (data atual + 30 dias no momento da criacao/renovacao). */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @Column(nullable = false)
    private LocalDate dataVencimento;

    /** Data da ultima renovacao (null se nunca foi renovada). */
    private LocalDate dataUltimaRenovacao;

    /**
     * Link opcional para o Processo de origem. Coluna e getter/setter existem
     * no schema, mas hoje NAO ha nenhum vinculo automatico implementado (um
     * deferimento nao preenche este campo sozinho) - fica nulo a menos que o
     * operador o preencha manualmente via formulario, se quiser relacionar
     * este registro a um Processo existente.
     */
    private Long processoId;

    /** Registro ativo (visivel) ou nao. */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("true")
    private boolean ativo = true;

    /** Observacoes gerais. */
    @Column(columnDefinition = "TEXT")
    private String observacoes;

    @Column(nullable = false, updatable = false)
    private LocalDateTime dataCadastro;

    /**
     * Lock otimista. Bug real corrigido (2026-08-03): era a unica entidade
     * "quente" do sistema sem @Version (Processo, Parecer, Usuario,
     * SolicitacaoOnline, MembroUrgenciaRenal e MensagemSolicitacao ja tinham).
     * Cenario: operador A abre o formulario de edicao com a dataVencimento
     * atual carregada; operador B clica "Renovar" (grava RENOVADA + vencimento
     * hoje+30); A salva a edicao que tinha aberto antes e sobrescreve
     * silenciosamente a renovacao com a data antiga, sem nenhum erro - numa
     * tela cuja unica funcao e controlar o prazo de 30 dias. Requer backfill
     * manual em prod apos o deploy (ver CLAUDE.md, pitfall de @Version novo em
     * entidade ja populada): {@code UPDATE controle_urgencia SET versao = 0
     * WHERE versao IS NULL;}
     */
    @Version
    @Column(name = "versao")
    private Long versao;

    @PrePersist
    protected void onCreate() {
        dataCadastro = LocalDateTime.now();
    }

    // -- Construtores

    public ControleUrgencia() {
    }

    public ControleUrgencia(String nomePaciente, String rgct, String equipe, String abo,
                            SituacaoUrgencia situacao, LocalDate dataVencimento) {
        this.nomePaciente = nomePaciente;
        this.rgct = rgct;
        this.equipe = equipe;
        this.abo = abo;
        this.situacao = situacao;
        this.dataVencimento = dataVencimento;
    }

    // -- Getters / Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNomePaciente() {
        return nomePaciente;
    }

    public void setNomePaciente(String nomePaciente) {
        this.nomePaciente = nomePaciente;
    }

    public String getRgct() {
        return rgct;
    }

    public void setRgct(String rgct) {
        this.rgct = rgct;
    }

    public String getEquipe() {
        return equipe;
    }

    public void setEquipe(String equipe) {
        this.equipe = equipe;
    }

    public String getAbo() {
        return abo;
    }

    public void setAbo(String abo) {
        this.abo = abo;
    }

    public SituacaoUrgencia getSituacao() {
        return situacao;
    }

    public void setSituacao(SituacaoUrgencia situacao) {
        this.situacao = situacao;
    }

    public LocalDate getDataVencimento() {
        return dataVencimento;
    }

    public void setDataVencimento(LocalDate dataVencimento) {
        this.dataVencimento = dataVencimento;
    }

    public LocalDate getDataUltimaRenovacao() {
        return dataUltimaRenovacao;
    }

    public void setDataUltimaRenovacao(LocalDate dataUltimaRenovacao) {
        this.dataUltimaRenovacao = dataUltimaRenovacao;
    }

    public Long getProcessoId() {
        return processoId;
    }

    public void setProcessoId(Long processoId) {
        this.processoId = processoId;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public String getObservacoes() {
        return observacoes;
    }

    public void setObservacoes(String observacoes) {
        this.observacoes = observacoes;
    }

    public LocalDateTime getDataCadastro() {
        return dataCadastro;
    }

    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}

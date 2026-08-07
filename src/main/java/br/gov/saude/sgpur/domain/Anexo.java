package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Arquivo anexado a um processo (copia de e-mail, documento do paciente,
 * oficio, etc.). Opcionalmente vinculado a um parecer especifico quando se
 * trata da copia do e-mail de voto de um avaliador.
 *
 * O binario fica em disco; aqui guardamos apenas os metadados e o caminho.
 */
@Entity
@Table(name = "anexo")
public class Anexo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "processo_id", nullable = false)
    private Processo processo;

    /** Preenchido quando o anexo for a copia do e-mail de voto de um avaliador. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parecer_id")
    private Parecer parecer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TipoAnexo tipo;

    /** Nome original do arquivo enviado. */
    @Column(name = "nome_arquivo", nullable = false, length = 255)
    private String nomeArquivo;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "tamanho_bytes")
    private Long tamanhoBytes;

    /** Caminho relativo do arquivo no diretorio de armazenamento. */
    @Column(name = "caminho_armazenado", nullable = false, length = 400)
    private String caminhoArmazenado;

    @Column(length = 255)
    private String descricao;

    @Column(name = "data_upload", nullable = false)
    private LocalDateTime dataUpload = LocalDateTime.now();

    /**
     * Lock otimista. Adicionado em 2026-08-07 -- {@code Anexo} era uma das
     * poucas entidades "quentes" do sistema sem {@code @Version} (Processo,
     * Parecer, Usuario, SolicitacaoOnline, MembroUrgenciaRenal,
     * MensagemSolicitacao e ControleUrgencia ja tinham). Exige backfill
     * manual em producao apos o deploy (ver CLAUDE.md, pitfall de
     * {@code @Version} novo em entidade ja populada):
     * {@code UPDATE anexo SET versao = 0 WHERE versao IS NULL;}
     */
    @Version
    @Column(name = "versao")
    private Long versao;

    public Anexo() {
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

    public Parecer getParecer() {
        return parecer;
    }

    public void setParecer(Parecer parecer) {
        this.parecer = parecer;
    }

    public TipoAnexo getTipo() {
        return tipo;
    }

    public void setTipo(TipoAnexo tipo) {
        this.tipo = tipo;
    }

    public String getNomeArquivo() {
        return nomeArquivo;
    }

    public void setNomeArquivo(String nomeArquivo) {
        this.nomeArquivo = nomeArquivo;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getTamanhoBytes() {
        return tamanhoBytes;
    }

    public void setTamanhoBytes(Long tamanhoBytes) {
        this.tamanhoBytes = tamanhoBytes;
    }

    public String getCaminhoArmazenado() {
        return caminhoArmazenado;
    }

    public void setCaminhoArmazenado(String caminhoArmazenado) {
        this.caminhoArmazenado = caminhoArmazenado;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public LocalDateTime getDataUpload() {
        return dataUpload;
    }

    public void setDataUpload(LocalDateTime dataUpload) {
        this.dataUpload = dataUpload;
    }

    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}

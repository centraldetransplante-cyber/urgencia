package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Documento clinico anexado pelo solicitante ANTES de existir um
 * {@link Processo} (por isso nao usa {@link Anexo}, cujo campo
 * {@code processo} e obrigatorio). Ao converter a {@link SolicitacaoOnline}
 * em processo, cada registro aqui vira um {@code Anexo} real do tipo
 * {@code TipoAnexo.DOCUMENTO_CLINICO_AVALIADOR}.
 */
@Entity
@Table(name = "anexo_solicitacao_online")
public class AnexoSolicitacaoOnline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "solicitacao_online_id", nullable = false)
    private SolicitacaoOnline solicitacaoOnline;

    @Column(name = "nome_arquivo", nullable = false, length = 255)
    private String nomeArquivo;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "tamanho_bytes")
    private Long tamanhoBytes;

    @Column(name = "caminho_armazenado", nullable = false, length = 400)
    private String caminhoArmazenado;

    @Column(name = "data_upload", nullable = false)
    private LocalDateTime dataUpload = LocalDateTime.now();

    /**
     * Lock otimista. Adicionado em 2026-08-07, junto com {@link Anexo}. Exige
     * backfill manual em producao apos o deploy (ver CLAUDE.md, pitfall de
     * {@code @Version} novo em entidade ja populada):
     * {@code UPDATE anexo_solicitacao_online SET versao = 0 WHERE versao IS NULL;}
     */
    @Version
    @Column(name = "versao")
    private Long versao;

    public AnexoSolicitacaoOnline() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public SolicitacaoOnline getSolicitacaoOnline() {
        return solicitacaoOnline;
    }

    public void setSolicitacaoOnline(SolicitacaoOnline solicitacaoOnline) {
        this.solicitacaoOnline = solicitacaoOnline;
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

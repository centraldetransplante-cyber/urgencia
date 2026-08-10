package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Mensagem trocada entre um MEDICO AVALIADOR e a equipe operacional
 * (OPERADOR/ADMIN), sobre um {@link Processo} especifico.
 *
 * <p><b>Por que uma tabela nova, separada de {@link MensagemSolicitacao}</b>
 * (ver docs/RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md, secao 6.2): a
 * FK de {@code MensagemSolicitacao.solicitacaoOnline} e {@code NOT NULL} (uma
 * invariante do canal Solicitante-Operador); a coluna {@code remetente} la
 * tem uma CHECK constraint CONGELADA em producao
 * ({@code mensagem_solicitacao_remetente_check}, 2 valores) que quebraria ao
 * ganhar um 3o valor (ddl-auto: update nao atualiza CHECK); e os contadores
 * de nao-lidas do canal antigo passariam a somar mensagens deste canal sem
 * cada consulta ganhar um filtro extra. Tabela nova = isolamento estrutural
 * entre dois dominios de privacidade diferentes: esta tabela pode conter
 * conversa que cita o paciente (o lado OPERADOR, sob verificacao - ver
 * {@link br.gov.saude.sgpur.service.VerificadorNomePaciente}); a outra
 * NUNCA pode.</p>
 *
 * <p><b>Ancora: (processo, membro), sempre 1:1.</b> Nunca um chat de grupo
 * entre os 3 avaliadores de um mesmo processo - isso anularia a
 * independencia dos pareceres, que e a razao de existir do modelo de
 * maioria simples. O lado "operador" e sempre uma caixa compartilhada
 * (qualquer ADMIN/OPERADOR le e responde), mesmo design do resto do
 * sistema (autorizacao por role, nao por posse).</p>
 */
/**
 * F6 (S10, docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md, achado A13): os dois
 * indices abaixo aceleram exatamente as consultas mais frequentes desta
 * tabela - {@code findByProcessoIdAndMembroIdOrderByDataEnvioAsc} (poll de
 * 5s da thread aberta, agora tambem alvo do UPDATE em lote de
 * {@link br.gov.saude.sgpur.repository.MensagemAvaliadorRepository#marcarComoLidasEmLote})
 * e {@code countByLidaFalseAndRemetente}/variantes (badges). O Postgres NAO
 * cria indice automatico em coluna de FK - ver CLAUDE.md, "ddl-auto: update
 * nao faz backfill/nao atualiza CHECK", mesma familia de pitfall de schema.
 * {@code ddl-auto: update} CRIA indice novo sem drama (ao contrario de CHECK
 * de enum/coluna NOT NULL) - mas confirmar em producao apos o deploy, ver
 * CLAUDE.md secao "F6".
 */
@Entity
@Table(name = "mensagem_avaliador", indexes = {
    @Index(name = "idx_mensagem_avaliador_processo_membro_data",
        columnList = "processo_id, membro_id, data_envio"),
    @Index(name = "idx_mensagem_avaliador_lida_remetente",
        columnList = "lida, remetente")
})
public class MensagemAvaliador {

    /** Enum PROPRIO, completo desde o primeiro deploy (nao reutiliza RemetenteMensagem). */
    public enum RemetenteMensagemAvaliador {
        AVALIADOR, OPERADOR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Ancora da thread: o caso em analise. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "processo_id", nullable = false)
    private Processo processo;

    /** O lado avaliador do par (processo, membro). Nunca Usuario: e MembroUrgenciaRenal que o Parecer referencia. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membro_id", nullable = false)
    private MembroUrgenciaRenal membro;

    @Enumerated(EnumType.STRING)
    @Column(name = "remetente", nullable = false, length = 20)
    private RemetenteMensagemAvaliador remetente;

    /** Id do Usuario que escreveu (nao o do MembroUrgenciaRenal). */
    @Column(name = "remetente_id", nullable = false)
    private Long remetenteId;

    // Nullable de proposito: o soft-delete (MensagemAvaliadorService.apagar)
    // zera o texto - mesma licao do bug real corrigido em MensagemSolicitacao
    // em 2026-07-28 (coluna NOT NULL fazia "apagar mensagem" quebrar em
    // producao). Nao repetir aqui.
    @Column(name = "texto", columnDefinition = "TEXT")
    private String texto;

    @Column(name = "data_envio", nullable = false)
    private LocalDateTime dataEnvio;

    @Column(name = "lida", nullable = false)
    private boolean lida;

    @Column(name = "deletada", nullable = false)
    private boolean deletada;

    @Column(name = "deletada_em")
    private LocalDateTime deletadaEm;

    // Tabela nova -> nasce vazia -> sem necessidade de backfill em producao
    // (ao contrario de Processo.versao/Usuario.versao/MembroUrgenciaRenal.versao).
    @Version
    private Long versao;

    public MensagemAvaliador() {
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

    public RemetenteMensagemAvaliador getRemetente() {
        return remetente;
    }

    public void setRemetente(RemetenteMensagemAvaliador remetente) {
        this.remetente = remetente;
    }

    public Long getRemetenteId() {
        return remetenteId;
    }

    public void setRemetenteId(Long remetenteId) {
        this.remetenteId = remetenteId;
    }

    public String getTexto() {
        return texto;
    }

    public void setTexto(String texto) {
        this.texto = texto;
    }

    public LocalDateTime getDataEnvio() {
        return dataEnvio;
    }

    public void setDataEnvio(LocalDateTime dataEnvio) {
        this.dataEnvio = dataEnvio;
    }

    public boolean isLida() {
        return lida;
    }

    public void setLida(boolean lida) {
        this.lida = lida;
    }

    public boolean isDeletada() {
        return deletada;
    }

    public void setDeletada(boolean deletada) {
        this.deletada = deletada;
    }

    public LocalDateTime getDeletadaEm() {
        return deletadaEm;
    }

    public void setDeletadaEm(LocalDateTime deletadaEm) {
        this.deletadaEm = deletadaEm;
    }

    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}

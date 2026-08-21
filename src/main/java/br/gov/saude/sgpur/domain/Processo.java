package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.BatchSize;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Processo de Urgencia Renal. Corresponde a uma linha da aba "CTEstadual"
 * da planilha original, acrescido dos campos administrativos exigidos no
 * documento de orientacoes (motivo/oficio de indeferimento, datas, anexos).
 */
@Entity
@Table(name = "processo")
public class Processo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Numero do processo no formato NN/AAAA. Ex.: 01/2026. NAO e
     * {@code @NotBlank} aqui (mesmo padrao de {@code Usuario.email}): a
     * obrigatoriedade e CONDICIONAL - manual em 2026 (validada no controller,
     * ProcessoDetalheController.salvar), automatica a partir de 2027
     * (preenchida por ProcessoService.cadastrar via proximoNumero(), DEPOIS
     * do @Valid do controller ja ter rodado). Um @NotBlank aqui bloquearia
     * TODA criacao de processo assim que a numeracao automatica entrar em
     * vigor, ja que o formulario nem renderiza o campo nesse modo.
     */
    @Column(nullable = false, unique = true, length = 12)
    private String numero;

    @Column(nullable = false)
    private Integer ano;

    @Column(nullable = false)
    private Integer sequencial;

    // ----- Receptor (paciente) -----
    @NotBlank
    @Size(max = 200, message = "Nome do paciente muito longo (maximo 200 caracteres).")
    @Column(name = "paciente_nome", nullable = false, length = 200)
    private String pacienteNome;

    /** Texto da mensagem de resposta enviada ao solicitante (preenchido na finalizacao automatica). */
    @Column(name = "mensagem_resposta", columnDefinition = "TEXT")
    private String mensagemResposta;

    /** Registro RGCT / SNT do paciente. Obrigatorio via @NotBlank (validacao de formulario). */
    @NotBlank
    @Size(max = 60, message = "Registro RGCT/SNT muito longo (maximo 60 caracteres).")
    @Column(name = "paciente_rgct", length = 60)
    private String pacienteRgct;

    /**
     * Data de nascimento, CPF (so digitos) e sexo do paciente.
     * {@code @NotNull}/{@code @NotBlank} na Bean Validation, mas
     * DELIBERADAMENTE sem {@code nullable = false} na coluna - mesma lacuna
     * ja existente em {@code pacienteRgct}, agora por decisao consciente de
     * compatibilidade com {@code Processo}/{@code SolicitacaoOnline} ja
     * gravados em producao antes destes campos existirem (ver
     * docs/RELATORIO-CAMPOS-PACIENTE-SOLICITANTE-2026-08.md). Nunca chegam
     * ao avaliador (so ate o Relatorio Final/dossie, do lado do operador).
     */
    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @Column(name = "paciente_data_nascimento")
    private LocalDate pacienteDataNascimento;

    @NotBlank
    @Size(min = 11, max = 11, message = "CPF deve ter 11 digitos.")
    @Column(name = "paciente_cpf", length = 11)
    private String pacienteCpf;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "paciente_sexo", length = 20)
    private Sexo pacienteSexo;

    @Size(max = 200, message = "Nome da mae muito longo (maximo 200 caracteres).")
    @Column(name = "paciente_nome_mae", length = 200)
    private String pacienteNomeMae;

    // ----- Equipe solicitante -----
    @NotBlank
    @Size(max = 200, message = "Nome da equipe solicitante muito longo (maximo 200 caracteres).")
    @Column(name = "solicitante_equipe", nullable = false, length = 200)
    private String solicitanteEquipe;

    @NotBlank
    @Email
    @Size(max = 150, message = "E-mail do solicitante muito longo (maximo 150 caracteres).")
    @Column(name = "solicitante_email", length = 150)
    private String solicitanteEmail;

    /**
     * E-mail ADICIONAL e OPCIONAL do solicitante, so para ESTE processo
     * (2026-08-21) - espelha {@link SolicitacaoOnline#getEmailAdicional()}
     * na conversao ({@code ProcessoDetalheController.novo}/{@code salvar}),
     * mesmo padrao ja usado para {@code pacienteCpf}/{@code pacienteSexo}
     * etc. Quando preenchido, os avisos automaticos por e-mail SOBRE ESTE
     * PROCESSO (resposta final Deferido/Indeferido, pedido de informacao
     * complementar) enviam uma COPIA (CC) para este endereco, alem do
     * {@link #solicitanteEmail} principal - nunca em substituicao a ele. Ver
     * o levantamento completo de quais envios usam esse CC em
     * docs/RELATORIO-EMAIL-ADICIONAL-SOLICITANTE-2026-08.md.
     *
     * <p>Nullable de proposito (campo opcional de verdade), sem backfill
     * necessario em producao - mesmo padrao ja documentado no CLAUDE.md.</p>
     */
    @Email
    @Size(max = 150, message = "E-mail adicional muito longo (maximo 150 caracteres).")
    @Column(name = "email_adicional", length = 150)
    private String emailAdicional;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @Column(name = "data_situacao_especial", nullable = false)
    private LocalDate dataSituacaoEspecial;

    // ----- Situacao / decisao -----
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StatusProcesso status = StatusProcesso.SOLICITADO;

    @Column(name = "email_enviado_solicitante", nullable = false)
    private boolean emailEnviadoSolicitante = false;

    @Column(columnDefinition = "TEXT")
    private String observacoes;

    // ----- Indeferimento (obrigatorio quando INDEFERIDO) -----
    @Column(name = "motivo_indeferimento", columnDefinition = "TEXT")
    private String motivoIndeferimento;

    @Column(name = "data_emissao_oficio")
    private LocalDate dataEmissaoOficio;

    @Column(name = "data_envio_oficio")
    private LocalDate dataEnvioOficio;

    /**
     * Numeracao PROPRIA do oficio de indeferimento, no formato NNNN/AAAA
     * (sequencial anual do setor, reiniciado a cada ano) - independente do
     * numero do processo (NN/AAAA). E o numero pelo qual o documento e
     * rastreado no protocolo da Central de Transplantes.
     *
     * <p>NULLABLE de proposito: so e atribuido no momento em que o sistema
     * gera o oficio ({@code DecisaoFinalService}), ou seja, apenas em
     * processos INDEFERIDOS. Processos antigos (indeferidos antes desta
     * coluna existir) permanecem com {@code null} sem nenhum estado invalido
     * - NAO exige backfill manual em producao, ao contrario de colunas
     * tratadas como obrigatorias (ver CLAUDE.md, "ddl-auto: update nao faz
     * backfill em coluna nova").</p>
     */
    @Column(name = "numero_oficio", length = 12)
    private String numeroOficio;

    // ----- Deferimento / SNT (obrigatorio quando DEFERIDO) -----

    /**
     * Data em que a urgencia renal foi de fato inserida/enviada ao Sistema
     * Nacional de Transplantes (SNT) - informada pelo operador, espelhando o
     * que {@code dataEmissaoOficio}/{@code dataEnvioOficio} ja fazem do lado
     * do indeferimento. Nao se confunde com a data de upload do comprovante
     * no sistema (que e apenas quando alguem subiu o arquivo). Nullable.
     */
    @Column(name = "data_envio_snt")
    private LocalDate dataEnvioSnt;

    /**
     * Momento do ultimo lembrete automatico enviado a equipe (ADMIN/OPERADOR)
     * cobrando o comprovante SNT de um processo Deferido
     * ({@code ComprovanteSntLembreteScheduler}). Nullable: {@code null}
     * significa "nunca lembrado" - valor semanticamente correto, sem backfill.
     */
    @Column(name = "ultimo_lembrete_snt_em")
    private LocalDateTime ultimoLembreteSntEm;

    /**
     * Quantas vezes este processo ja foi reaberto pelo ADMIN
     * ({@code ProcessoService.reabrir}) - Achado 8 do relatorio de vistoria
     * de brechas (2026-08-10): antes, nenhuma tela mostrava que um processo
     * decidido ja tinha sido decidido antes, so a auditoria (ADMIN-only).
     * Alimenta o badge "Reaberto Nx" e uma linha no Relatorio Final.
     *
     * <p>Nullable de proposito: processo que nunca foi reaberto fica com
     * {@code null} (== 0 reaberturas), sem exigir backfill manual em
     * producao (mesmo padrao de {@code ultimoLembreteSntEm}/{@code
     * numeroOficio} - ver CLAUDE.md, "ddl-auto: update nao faz backfill em
     * coluna nova").</p>
     */
    @Column(name = "reaberturas")
    private Integer reaberturas;

    // ----- Auditoria -----
    @Column(name = "data_cadastro", nullable = false)
    private LocalDateTime dataCadastro = LocalDateTime.now();

    @Column(name = "data_decisao")
    private LocalDateTime dataDecisao;

    // @BatchSize: quando a colecao nao veio por fetch join (ex.: a pagina de
    // /processos, que so pagina bem sem fetch join de colecao - ver
    // ProcessoRepository.buscar), o Hibernate busca os pareceres/anexos de
    // ATE 20 processos numa unica query "in (:ids)" em vez de 1 query por
    // processo (N+1). Achado em vistoria de 2026-08-05:
    // FluxoProcessoService.pendenciaAberta(p), chamado por processo da
    // pagina/lista em ProcessoListaController e HomeController, navega
    // p.getPareceres()/p.getAnexos() - sem isso eram dezenas de queries
    // extras por render de /processos e do Painel (/).
    @OneToMany(mappedBy = "processo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 20)
    private List<Parecer> pareceres = new ArrayList<>();

    @OneToMany(mappedBy = "processo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dataUpload ASC")
    @BatchSize(size = 20)
    private List<Anexo> anexos = new ArrayList<>();

    /**
     * Controle de concorrencia otimista: o Hibernate incrementa a cada UPDATE e
     * detecta escritas concorrentes conflitantes (OptimisticLockException) em
     * vez de deixar "o ultimo que salva ganha" silenciosamente.
     */
    @Version
    @Column(name = "versao")
    private Long versao;

    public Processo() {
    }

    /**
     * Identificacao padrao do processo para exibicao em telas, PDFs e nomes
     * de pasta. Formato: "NN/AAAA - Nome do Paciente - RGCT XXXXXXXXX".
     * Quando o RGCT ainda nao foi preenchido, omite a parte do RGCT.
     */
    public String identificacao() {
        StringBuilder sb = new StringBuilder();
        sb.append(numero != null ? numero : "?/?");
        if (pacienteNome != null && !pacienteNome.isBlank()) {
            sb.append(" - ").append(pacienteNome);
        }
        if (pacienteRgct != null && !pacienteRgct.isBlank()) {
            sb.append(" - RGCT ").append(pacienteRgct);
        }
        return sb.toString();
    }

    public void addParecer(Parecer parecer) {
        parecer.setProcesso(this);
        this.pareceres.add(parecer);
    }

    public void addAnexo(Anexo anexo) {
        anexo.setProcesso(this);
        this.anexos.add(anexo);
    }

    /**
     * Tira um anexo especifico da colecao em memoria, SEM reatribuir a
     * referencia da lista (troque a referencia por uma nova {@code List} e
     * quebra o orphan-removal no flush - bug real em producao no upload do
     * Comprovante SNT, 2026-07-28, documentado em
     * {@code ProcessoAnexoController.substituirAnexo}). Este metodo faz um
     * {@code remove()} in-place na mesma instancia de colecao, preservando o
     * wrapper que o Hibernate usa para rastrear a associacao.
     *
     * <p>Uso: quando um anexo ja foi excluido via
     * {@code anexoRepository.delete(...)} na MESMA sessao/transacao, mas o
     * {@code Processo} ainda o referencia nesta colecao - sem tirar essa
     * instancia daqui, um merge cascade posterior (ex.:
     * {@code processoRepository.save(processo)}, {@code cascade = ALL} inclui
     * MERGE) tenta mesclar uma entidade ja marcada como REMOVIDA e o Hibernate
     * recusa com {@code ObjectDeletedException: deleted instance passed to
     * merge} (bug real reportado em 2026-07-29, reproduzido registrando o
     * envio de um processo pela segunda vez apos o ADMIN reabrir).</p>
     */
    public void removerAnexo(Anexo anexo) {
        this.anexos.remove(anexo);
    }

    // ----- getters / setters -----
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNumero() {
        return numero;
    }

    public void setNumero(String numero) {
        this.numero = numero;
    }

    public Integer getAno() {
        return ano;
    }

    public void setAno(Integer ano) {
        this.ano = ano;
    }

    public Integer getSequencial() {
        return sequencial;
    }

    public void setSequencial(Integer sequencial) {
        this.sequencial = sequencial;
    }

    public String getPacienteNome() {
        return pacienteNome;
    }

    public void setPacienteNome(String pacienteNome) {
        this.pacienteNome = pacienteNome;
    }

    public String getPacienteRgct() {
        return pacienteRgct;
    }

    public void setPacienteRgct(String pacienteRgct) {
        this.pacienteRgct = pacienteRgct;
    }

    public LocalDate getPacienteDataNascimento() {
        return pacienteDataNascimento;
    }

    public void setPacienteDataNascimento(LocalDate pacienteDataNascimento) {
        this.pacienteDataNascimento = pacienteDataNascimento;
    }

    public String getPacienteCpf() {
        return pacienteCpf;
    }

    public void setPacienteCpf(String pacienteCpf) {
        this.pacienteCpf = pacienteCpf;
    }

    public Sexo getPacienteSexo() {
        return pacienteSexo;
    }

    public void setPacienteSexo(Sexo pacienteSexo) {
        this.pacienteSexo = pacienteSexo;
    }

    public String getPacienteNomeMae() {
        return pacienteNomeMae;
    }

    public void setPacienteNomeMae(String pacienteNomeMae) {
        this.pacienteNomeMae = pacienteNomeMae;
    }

    public String getSolicitanteEquipe() {
        return solicitanteEquipe;
    }

    public void setSolicitanteEquipe(String solicitanteEquipe) {
        this.solicitanteEquipe = solicitanteEquipe;
    }

    public String getSolicitanteEmail() {
        return solicitanteEmail;
    }

    public void setSolicitanteEmail(String solicitanteEmail) {
        this.solicitanteEmail = solicitanteEmail;
    }

    public String getEmailAdicional() {
        return emailAdicional;
    }

    public void setEmailAdicional(String emailAdicional) {
        this.emailAdicional = emailAdicional;
    }

    public LocalDate getDataSituacaoEspecial() {
        return dataSituacaoEspecial;
    }

    public void setDataSituacaoEspecial(LocalDate dataSituacaoEspecial) {
        this.dataSituacaoEspecial = dataSituacaoEspecial;
    }

    public StatusProcesso getStatus() {
        return status;
    }

    public void setStatus(StatusProcesso status) {
        this.status = status;
    }

    public boolean isEmailEnviadoSolicitante() {
        return emailEnviadoSolicitante;
    }

    public void setEmailEnviadoSolicitante(boolean emailEnviadoSolicitante) {
        this.emailEnviadoSolicitante = emailEnviadoSolicitante;
    }

    public String getObservacoes() {
        return observacoes;
    }

    public void setObservacoes(String observacoes) {
        this.observacoes = observacoes;
    }

    public String getMotivoIndeferimento() {
        return motivoIndeferimento;
    }

    public void setMotivoIndeferimento(String motivoIndeferimento) {
        this.motivoIndeferimento = motivoIndeferimento;
    }

    public String getMensagemResposta() {
        return mensagemResposta;
    }

    public void setMensagemResposta(String mensagemResposta) {
        this.mensagemResposta = mensagemResposta;
    }

    public LocalDate getDataEmissaoOficio() {
        return dataEmissaoOficio;
    }

    public void setDataEmissaoOficio(LocalDate dataEmissaoOficio) {
        this.dataEmissaoOficio = dataEmissaoOficio;
    }

    public LocalDate getDataEnvioOficio() {
        return dataEnvioOficio;
    }

    public void setDataEnvioOficio(LocalDate dataEnvioOficio) {
        this.dataEnvioOficio = dataEnvioOficio;
    }

    public String getNumeroOficio() {
        return numeroOficio;
    }

    public void setNumeroOficio(String numeroOficio) {
        this.numeroOficio = numeroOficio;
    }

    public LocalDate getDataEnvioSnt() {
        return dataEnvioSnt;
    }

    public void setDataEnvioSnt(LocalDate dataEnvioSnt) {
        this.dataEnvioSnt = dataEnvioSnt;
    }

    public LocalDateTime getUltimoLembreteSntEm() {
        return ultimoLembreteSntEm;
    }

    public void setUltimoLembreteSntEm(LocalDateTime ultimoLembreteSntEm) {
        this.ultimoLembreteSntEm = ultimoLembreteSntEm;
    }

    public Integer getReaberturas() {
        return reaberturas;
    }

    public void setReaberturas(Integer reaberturas) {
        this.reaberturas = reaberturas;
    }

    /** {@link #getReaberturas()} tratando {@code null} como 0 - conveniencia para servicos/templates. */
    public int getReaberturasOuZero() {
        return reaberturas == null ? 0 : reaberturas;
    }

    public LocalDateTime getDataCadastro() {
        return dataCadastro;
    }

    public void setDataCadastro(LocalDateTime dataCadastro) {
        this.dataCadastro = dataCadastro;
    }

    public LocalDateTime getDataDecisao() {
        return dataDecisao;
    }

    public void setDataDecisao(LocalDateTime dataDecisao) {
        this.dataDecisao = dataDecisao;
    }

    /**
     * Somente leitura: use {@link #addParecer(Parecer)} para adicionar - a
     * lista e devolvida imutavel para impedir mutacao por fora do vinculo
     * bidirecional (um {@code .add()} direto aqui deixaria o {@code Parecer}
     * sem {@code processo} setado, quebrando a navegacao inversa).
     */
    public List<Parecer> getPareceres() {
        return Collections.unmodifiableList(pareceres);
    }

    public void setPareceres(List<Parecer> pareceres) {
        this.pareceres = pareceres;
    }

    /** Somente leitura: use {@link #addAnexo(Anexo)} para adicionar - mesmo motivo de {@link #getPareceres()}. */
    public List<Anexo> getAnexos() {
        return Collections.unmodifiableList(anexos);
    }

    public void setAnexos(List<Anexo> anexos) {
        this.anexos = anexos;
    }

    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}

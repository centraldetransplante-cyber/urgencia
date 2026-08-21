package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pedido de urgencia renal enviado pelo Portal do Solicitante (modulo
 * experimental), ANTES de virar um {@link Processo} de verdade.
 *
 * Deliberadamente desacoplada de {@code Processo}: nao compartilha a mesma
 * tabela nem o mesmo ciclo de vida. O operador faz a triagem e "converte"
 * este registro num {@code Processo} chamando o fluxo de cadastro normal
 * ({@code ProcessoService.cadastrar}), que continua escolhendo os 3 medicos
 * avaliadores e atribuindo o numero oficial - nada aqui contorna essas
 * regras. Ver docs/PLANO-SOLICITANTE.md.
 */
@Entity
@Table(name = "solicitacao_online")
public class SolicitacaoOnline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Usuario (perfil SOLICITANTE) que enviou o pedido. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_solicitante_id", nullable = false)
    private Usuario usuarioSolicitante;

    @NotBlank
    @Size(max = 200, message = "Nome do paciente muito longo (maximo 200 caracteres).")
    @Column(name = "paciente_nome", nullable = false, length = 200)
    private String pacienteNome;

    @NotBlank
    @Size(max = 60, message = "Registro RGCT/SNT muito longo (maximo 60 caracteres).")
    @Column(name = "paciente_rgct", length = 60)
    private String pacienteRgct;

    /**
     * Data de nascimento, CPF (so digitos) e sexo do paciente.
     * {@code @NotNull}/{@code @NotBlank} na Bean Validation, mas
     * DELIBERADAMENTE sem {@code nullable = false} na coluna - mesma lacuna
     * ja existente em {@code pacienteRgct}, agora por decisao consciente de
     * compatibilidade com solicitacoes ja gravadas em producao antes destes
     * campos existirem (ver
     * docs/RELATORIO-CAMPOS-PACIENTE-SOLICITANTE-2026-08.md). Nunca chegam
     * ao avaliador (so ate o Relatorio Final/dossie, do lado do operador).
     */
    @NotNull
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
     * E-mail ADICIONAL e OPCIONAL (2026-08-21), informado pelo proprio
     * solicitante no formulario de nova solicitacao, so para ESTE pedido -
     * nao substitui {@link #solicitanteEmail} (que vem sempre do
     * {@code Usuario} logado, nunca do formulario - ver
     * {@code SolicitacaoOnlineService.criar}) nem o e-mail de login da conta.
     * Um segundo destinatario (colega, chefia, e-mail pessoal) que passa a
     * receber COPIA (CC) dos avisos automaticos sobre este processo/pedido
     * especifico - ver {@code EmailTemplateService}/{@code ProcessoService
     * .finalizarResposta} e o levantamento completo em
     * docs/RELATORIO-EMAIL-ADICIONAL-SOLICITANTE-2026-08.md sobre quais
     * pontos de envio usam esse CC e quais foram deliberadamente deixados de
     * fora (avisos ao TIME interno - avaliador/operador - nunca usam este
     * campo).
     *
     * <p>Sem {@code @NotBlank}/{@code @NotNull} de proposito (campo
     * opcional de verdade - {@code @Email} sozinho e null-safe, so valida
     * formato quando preenchido) e {@code nullable} na coluna - nao precisa
     * de backfill manual em producao (mesmo padrao ja documentado no
     * CLAUDE.md para outras colunas nullable adicionadas recentemente, ex.
     * {@code Processo.ultimoLembreteSntEm}).</p>
     */
    @Email
    @Size(max = 150, message = "E-mail adicional muito longo (maximo 150 caracteres).")
    @Column(name = "email_adicional", length = 150)
    private String emailAdicional;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @Column(name = "data_situacao_especial", nullable = false)
    private LocalDate dataSituacaoEspecial;

    /**
     * Justificativa clinica do pedido, escrita pelo proprio solicitante.
     * No fluxo por e-mail essa informacao vinha implicita no corpo do
     * e-mail/anexos; aqui precisa de campo proprio.
     */
    @NotBlank
    @Column(name = "justificativa_clinica", columnDefinition = "TEXT", nullable = false)
    private String justificativaClinica;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusSolicitacaoOnline status = StatusSolicitacaoOnline.ENVIADA;

    @Column(name = "data_envio", nullable = false)
    private LocalDateTime dataEnvio = LocalDateTime.now();

    /** Preenchido apenas quando status == CONVERTIDA. Link de rastreabilidade/auditoria. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processo_gerado_id")
    private Processo processoGerado;

    /** Nota do operador ao devolver o pedido para correcao (status == DEVOLVIDA). */
    @Column(name = "observacoes_triagem", columnDefinition = "TEXT")
    private String observacoesTriagem;

    @OneToMany(mappedBy = "solicitacaoOnline", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<AnexoSolicitacaoOnline> anexos = new ArrayList<>();

    @Version
    @Column(name = "versao")
    private Long versao;

    public SolicitacaoOnline() {
    }

    public void addAnexo(AnexoSolicitacaoOnline anexo) {
        anexo.setSolicitacaoOnline(this);
        this.anexos.add(anexo);
    }

    /** Identificacao curta para telas/logs: "Nome do paciente - RGCT XXXXXXXXX". */
    public String identificacao() {
        StringBuilder sb = new StringBuilder(pacienteNome != null ? pacienteNome : "?");
        if (pacienteRgct != null && !pacienteRgct.isBlank()) {
            sb.append(" - RGCT ").append(pacienteRgct);
        }
        return sb.toString();
    }

    // ----- getters / setters -----

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Usuario getUsuarioSolicitante() {
        return usuarioSolicitante;
    }

    public void setUsuarioSolicitante(Usuario usuarioSolicitante) {
        this.usuarioSolicitante = usuarioSolicitante;
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

    public String getJustificativaClinica() {
        return justificativaClinica;
    }

    public void setJustificativaClinica(String justificativaClinica) {
        this.justificativaClinica = justificativaClinica;
    }

    public StatusSolicitacaoOnline getStatus() {
        return status;
    }

    public void setStatus(StatusSolicitacaoOnline status) {
        this.status = status;
    }

    public LocalDateTime getDataEnvio() {
        return dataEnvio;
    }

    public void setDataEnvio(LocalDateTime dataEnvio) {
        this.dataEnvio = dataEnvio;
    }

    public Processo getProcessoGerado() {
        return processoGerado;
    }

    public void setProcessoGerado(Processo processoGerado) {
        this.processoGerado = processoGerado;
    }

    public String getObservacoesTriagem() {
        return observacoesTriagem;
    }

    public void setObservacoesTriagem(String observacoesTriagem) {
        this.observacoesTriagem = observacoesTriagem;
    }

    /**
     * Somente leitura: use {@link #addAnexo(AnexoSolicitacaoOnline)} para
     * adicionar - mesmo motivo de {@code Processo.getAnexos()}: um
     * {@code .add()} direto aqui deixaria o {@code AnexoSolicitacaoOnline}
     * sem {@code solicitacaoOnline} setado, quebrando a navegacao inversa.
     */
    public List<AnexoSolicitacaoOnline> getAnexos() {
        return Collections.unmodifiableList(anexos);
    }

    public void setAnexos(List<AnexoSolicitacaoOnline> anexos) {
        this.anexos = anexos;
    }

    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}

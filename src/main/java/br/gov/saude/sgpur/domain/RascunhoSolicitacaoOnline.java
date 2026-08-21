package br.gov.saude.sgpur.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Rascunho de {@link SolicitacaoOnline}, salvo pelo proprio solicitante
 * enquanto ainda esta preenchendo o formulario de "Nova solicitacao"
 * (Fase 11, item 3 do plano de UI - implementada mediante aval explicito do
 * usuario em 2026-08-04). Entidade de STAGING deliberadamente separada, nao
 * um status novo em {@link SolicitacaoOnline}: os campos dessa entidade sao
 * {@code @NotBlank}/{@code @NotNull} porque protegem o pedido REAL (o que a
 * equipe de Urgencia Renal vai analisar) - relaxar essas anotacoes para
 * acomodar um rascunho incompleto abriria a possibilidade de, por engano ou
 * bug futuro, um pedido incompleto virar uma {@code SolicitacaoOnline} de
 * verdade sem passar pela validacao completa. Aqui, ao contrario, NENHUM
 * campo e obrigatorio (o rascunho pode estar vazio) - so os limites de
 * tamanho ({@code @Size}, que e null-safe: nao rejeita campo em branco) sao
 * mantidos, para nao permitir texto absurdamente longo mesmo num rascunho.
 *
 * <p><b>Nunca aparece para o operador</b>: nao ha nenhuma tela/consulta de
 * triagem que leia esta tabela - o rascunho so vira visivel pela equipe de
 * Urgencia Renal quando o solicitante de fato clica em "Enviar solicitacao"
 * ({@code SolicitanteController#criar}), que cria uma
 * {@link SolicitacaoOnline} de verdade (com todas as validacoes) e apaga o
 * rascunho em seguida.
 *
 * <p><b>Um rascunho por solicitante</b>: {@code usuario_solicitante_id} e
 * {@code unique}. Salvar um novo rascunho sobrescreve o anterior (mesmo
 * padrao de "ultimo estado salvo", nao um historico de rascunhos).
 */
@Entity
@Table(name = "rascunho_solicitacao_online")
public class RascunhoSolicitacaoOnline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_solicitante_id", nullable = false, unique = true)
    private Usuario usuarioSolicitante;

    @Size(max = 200, message = "Nome do paciente muito longo (maximo 200 caracteres).")
    @Column(name = "paciente_nome", length = 200)
    private String pacienteNome;

    @Size(max = 60, message = "Registro RGCT/SNT muito longo (maximo 60 caracteres).")
    @Column(name = "paciente_rgct", length = 60)
    private String pacienteRgct;

    // Rascunho: nenhum @NotNull/@NotBlank nos 4 campos novos de propósito
    // (mesmo racional do restante da classe) - so @Size por seguranca de
    // tamanho de coluna.
    @Column(name = "paciente_data_nascimento")
    private LocalDate pacienteDataNascimento;

    @Size(max = 20, message = "CPF invalido.")
    @Column(name = "paciente_cpf", length = 20)
    private String pacienteCpf;

    @Enumerated(EnumType.STRING)
    @Column(name = "paciente_sexo", length = 20)
    private Sexo pacienteSexo;

    @Size(max = 200, message = "Nome da mae muito longo (maximo 200 caracteres).")
    @Column(name = "paciente_nome_mae", length = 200)
    private String pacienteNomeMae;

    @Column(name = "data_situacao_especial")
    private LocalDate dataSituacaoEspecial;

    /**
     * Espelha {@link SolicitacaoOnline#getEmailAdicional()} (2026-08-21) - o
     * rascunho tambem guarda o e-mail adicional opcional que o solicitante ja
     * tiver digitado, mesmo tratamento dos demais campos desta classe (sem
     * {@code @NotBlank}, so {@code @Size} por seguranca de tamanho de
     * coluna).
     */
    @Size(max = 150, message = "E-mail adicional muito longo (maximo 150 caracteres).")
    @Column(name = "email_adicional", length = 150)
    private String emailAdicional;

    // Nota: a entidade "de verdade" (SolicitacaoOnline.justificativaClinica)
    // tambem nao tem @Size hoje - so @NotBlank, sem limite de tamanho. Sem um
    // valor real para espelhar, usa-se aqui um teto generoso (4000
    // caracteres, bem alem de qualquer justificativa clinica razoavel) so
    // para cumprir o que o javadoc da classe promete ("nenhum campo de texto
    // sem limite, mesmo no rascunho") e evitar um TEXT ilimitado.
    @Size(max = 4000, message = "Justificativa clinica muito longa (maximo 4000 caracteres).")
    @Column(name = "justificativa_clinica", columnDefinition = "TEXT")
    private String justificativaClinica;

    @Column(name = "atualizado_em", nullable = false)
    private LocalDateTime atualizadoEm = LocalDateTime.now();

    @Version
    @Column(name = "versao")
    private Long versao;

    public RascunhoSolicitacaoOnline() {
    }

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

    public LocalDate getDataSituacaoEspecial() {
        return dataSituacaoEspecial;
    }

    public void setDataSituacaoEspecial(LocalDate dataSituacaoEspecial) {
        this.dataSituacaoEspecial = dataSituacaoEspecial;
    }

    public String getEmailAdicional() {
        return emailAdicional;
    }

    public void setEmailAdicional(String emailAdicional) {
        this.emailAdicional = emailAdicional;
    }

    public String getJustificativaClinica() {
        return justificativaClinica;
    }

    public void setJustificativaClinica(String justificativaClinica) {
        this.justificativaClinica = justificativaClinica;
    }

    public LocalDateTime getAtualizadoEm() {
        return atualizadoEm;
    }

    public void setAtualizadoEm(LocalDateTime atualizadoEm) {
        this.atualizadoEm = atualizadoEm;
    }

    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}

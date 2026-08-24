package br.gov.saude.sgpur.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Token de uso unico para o fluxo "esqueci minha senha" via link por e-mail
 * (ver {@code PasswordResetService}), substituindo em 2026-08-24 o fluxo
 * antigo que trocava a senha ativa na hora do pedido (achado D da vistoria
 * de 2026-08-24 - permitia DoS/lockout de qualquer usuario cujo login um
 * atacante conhecesse).
 *
 * <p>A senha ATIVA do usuario so muda quando ele efetivamente abre o link e
 * confirma uma nova senha ({@code PasswordResetService.confirmarNovaSenha}) -
 * ate la, este registro nao afeta o login normal em nada. {@code usado}
 * impede reuso do mesmo link; {@code dataExpiracao} da um TTL curto (ver
 * {@code PasswordResetService.TTL}).
 */
@Entity
@Table(name = "password_reset_token", indexes = {
    @Index(name = "idx_password_reset_token_token", columnList = "token", unique = true)
})
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    /** Token opaco (SecureRandom, nao previsivel) - nunca a senha em si. */
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "data_criacao", nullable = false)
    private Instant dataCriacao;

    @Column(name = "data_expiracao", nullable = false)
    private Instant dataExpiracao;

    /** Marca o token como consumido apos uma troca de senha bem-sucedida - nunca reutilizavel. */
    @Column(nullable = false)
    private boolean usado = false;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public void setUsuario(Usuario usuario) {
        this.usuario = usuario;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Instant getDataCriacao() {
        return dataCriacao;
    }

    public void setDataCriacao(Instant dataCriacao) {
        this.dataCriacao = dataCriacao;
    }

    public Instant getDataExpiracao() {
        return dataExpiracao;
    }

    public void setDataExpiracao(Instant dataExpiracao) {
        this.dataExpiracao = dataExpiracao;
    }

    public boolean isUsado() {
        return usado;
    }

    public void setUsado(boolean usado) {
        this.usado = usado;
    }
}

package br.gov.saude.sgpur.service;

/**
 * Politica de senha unica do sistema (minimo 8 caracteres + maiuscula +
 * minuscula + numero + caractere especial), extraida de
 * {@code UsuarioService} em 2026-08-24 para ser reaproveitada tambem por
 * {@link PasswordResetService} (confirmacao do "esqueci minha senha" via
 * token) sem duplicar as 5 regras em dois lugares. Usada em: criar/editar
 * usuario, trocar a propria senha ({@code UsuarioService}) e confirmar a
 * nova senha do fluxo de token ({@code PasswordResetService}).
 */
public final class SenhaPolicy {

    private SenhaPolicy() {
    }

    /** Lanca IllegalArgumentException com mensagem amigavel na primeira regra violada. */
    public static void validar(String senha) {
        if (senha == null || senha.length() < 8) {
            throw new IllegalArgumentException("A senha deve ter ao menos 8 caracteres.");
        }
        if (!senha.matches(".*[A-Z].*")) {
            throw new IllegalArgumentException("A senha deve conter ao menos uma letra maiuscula.");
        }
        if (!senha.matches(".*[a-z].*")) {
            throw new IllegalArgumentException("A senha deve conter ao menos uma letra minuscula.");
        }
        if (!senha.matches(".*\\d.*")) {
            throw new IllegalArgumentException("A senha deve conter ao menos um numero.");
        }
        if (!senha.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*")) {
            throw new IllegalArgumentException("A senha deve conter ao menos um caractere especial.");
        }
    }
}

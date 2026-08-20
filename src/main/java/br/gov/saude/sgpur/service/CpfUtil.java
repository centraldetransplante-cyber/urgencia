package br.gov.saude.sgpur.service;

/**
 * Utilitário puro de validação/formatação de CPF (mesma família de
 * {@link Iniciais}/{@link ConflitoEquipeMatcher} — sem estado, sem
 * dependência externa). Usado na coleta obrigatória de CPF do paciente no
 * Portal do Solicitante (ver
 * docs/RELATORIO-CAMPOS-PACIENTE-SOLICITANTE-2026-08.md).
 *
 * <p>CPF é sempre ARMAZENADO como 11 dígitos crus (sem máscara) — a
 * formatação {@code 000.000.000-00} é responsabilidade só da camada de
 * apresentação (controller/service, nunca {@code T(...)} em template).</p>
 */
public final class CpfUtil {

    private CpfUtil() {
    }

    /** Remove tudo que não é dígito. Nunca lança, mesmo com entrada nula. */
    public static String normalizar(String cpf) {
        if (cpf == null) {
            return "";
        }
        return cpf.replaceAll("\\D", "");
    }

    /**
     * Valida um CPF (algoritmo módulo-11 de dígito verificador), depois de
     * já normalizado (só dígitos). Rejeita tamanho diferente de 11 e as 10
     * sequências degeneradas (000.000.000-00 .. 999.999.999-99).
     */
    public static boolean valido(String cpfDigits) {
        if (cpfDigits == null || cpfDigits.length() != 11 || !cpfDigits.matches("\\d{11}")) {
            return false;
        }
        if (cpfDigits.chars().distinct().count() == 1) {
            return false;
        }
        int[] d = cpfDigits.chars().map(c -> c - '0').toArray();

        int soma1 = 0;
        for (int i = 0; i < 9; i++) {
            soma1 += d[i] * (10 - i);
        }
        int resto1 = soma1 % 11;
        int dv1 = (resto1 < 2) ? 0 : (11 - resto1);
        if (dv1 != d[9]) {
            return false;
        }

        int soma2 = 0;
        for (int i = 0; i < 10; i++) {
            soma2 += d[i] * (11 - i);
        }
        int resto2 = soma2 % 11;
        int dv2 = (resto2 < 2) ? 0 : (11 - resto2);
        return dv2 == d[10];
    }

    /** Formata 11 dígitos crus como {@code 000.000.000-00}. Null-safe. */
    public static String formatar(String cpfDigits) {
        if (cpfDigits == null || cpfDigits.length() != 11) {
            return cpfDigits;
        }
        return cpfDigits.substring(0, 3) + "." + cpfDigits.substring(3, 6) + "."
                + cpfDigits.substring(6, 9) + "-" + cpfDigits.substring(9, 11);
    }
}

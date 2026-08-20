package br.gov.saude.sgpur.domain;

/**
 * Sexo do paciente, coletado no Portal do Solicitante e propagado ate o
 * {@link Processo}. Binario por decisao de produto (nao ha terceira opcao).
 *
 * <p>NUNCA propagado ao Portal do Avaliador nem a nenhum documento
 * voltado ao avaliador (regra de imparcialidade: o avaliador so ve
 * iniciais do paciente) - ver
 * docs/RELATORIO-CAMPOS-PACIENTE-SOLICITANTE-2026-08.md.
 */
public enum Sexo {
    MASCULINO("Masculino"),
    FEMININO("Feminino");

    private final String descricao;

    Sexo(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }
}

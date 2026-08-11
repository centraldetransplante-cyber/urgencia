package br.gov.saude.sgpur.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Valores literais e estaveis usados na geracao dos textos de e-mail
 * (EmailTemplateService): assinatura padrao e prefixo dos assuntos.
 * Nao inclui os corpos de texto em si (parametrizados com dados dinamicos
 * do processo), apenas o que faz sentido trocar sem recompilar.
 */
@Component
@ConfigurationProperties(prefix = "sgpur.email")
public class EmailProperties {

    /**
     * Assinatura ao pe dos e-mails prontos ({@code EmailTemplateService}) e do
     * Oficio de Indeferimento ({@code OficioService}). ACENTUADA desde 2026-08-11:
     * e texto institucional que chega a equipe solicitante, mesma regra dos corpos
     * de e-mail (ver javadoc de {@code EmailTemplateService}).
     *
     * <p><strong>Atencao em producao:</strong> este e apenas o DEFAULT. Se a VM
     * definir {@code SGPUR_EMAIL_ASSINATURA} em {@code /opt/sgpur/sgpur.env}
     * (arquivo fora do git), o valor de la vence e precisa ser acentuado a mao -
     * nenhum deploy corrige isso sozinho.</p>
     */
    private String assinatura = "Equipe de Urgência Renal - Secretaria de Saúde";

    /** Prefixo do assunto dos e-mails. Mesma ressalva de {@code SGPUR_EMAIL_PREFIXO_ASSUNTO}. */
    private String prefixoAssunto = "Urgência Renal";

    /**
     * Cidade impressa na linha "Cidade, dd de mes de aaaa" do Oficio de
     * Indeferimento ({@code OficioService}). Antes de 2026-08-04 o documento
     * saia com a palavra literal "Local," - placeholder que chegava a equipe
     * solicitante num documento oficial.
     */
    private String oficioCidade = "Porto Alegre";

    public String getAssinatura() {
        return assinatura;
    }

    public void setAssinatura(String assinatura) {
        this.assinatura = assinatura;
    }

    public String getPrefixoAssunto() {
        return prefixoAssunto;
    }

    public void setPrefixoAssunto(String prefixoAssunto) {
        this.prefixoAssunto = prefixoAssunto;
    }

    public String getOficioCidade() {
        return oficioCidade;
    }

    public void setOficioCidade(String oficioCidade) {
        this.oficioCidade = oficioCidade;
    }
}

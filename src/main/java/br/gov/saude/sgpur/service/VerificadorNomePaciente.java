package br.gov.saude.sgpur.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Verificacao DETERMINISTICA (nunca heuristica de "detectar nomes proprios",
 * o que seria inviavel e cheio de falso-positivo) de que uma mensagem do
 * OPERADOR ao avaliador nao cita o nome do paciente nem a equipe
 * solicitante daquele processo especifico.
 *
 * <p>So e possivel porque o canal e "por processo" (ver
 * docs/RELATORIO-CHAT-MEMBROS-OPERADORES-2026-08.md, secao 8.1): o servidor
 * sabe exatamente qual string nao pode aparecer. Reaproveita a mesma tecnica
 * de normalizacao/casamento por palavra inteira ja validada em
 * {@link ConflitoEquipeMatcher} e {@link Iniciais}.</p>
 *
 * <p><b>So se aplica ao lado OPERADOR</b> (nunca ao avaliador escrevendo: ele
 * nao sabe o nome do paciente, entao a checagem so produziria ruido - o
 * risco de imparcialidade e estruturalmente unidirecional).</p>
 */
@Component
public class VerificadorNomePaciente {

    public enum Nivel { LIVRE, ALERTA, BLOQUEADO }

    public record Resultado(Nivel nivel, List<String> termosEncontrados) {
        public boolean bloqueado() {
            return nivel == Nivel.BLOQUEADO;
        }
    }

    private static final Set<String> CONECTIVOS = Set.of("da", "de", "do", "dos", "das", "e");
    /**
     * Palavras genericas ignoradas ao formar os tokens significativos da
     * EQUIPE (nao do nome do paciente - ver {@link #tokensSignificativosNome}).
     *
     * <p><b>Calibragem de 2026-08-10 (S4, docs/RELATORIO-VISTORIA-CHAT-2026-08-10.md,
     * achado A5): deliberadamente NAO inclui "clinicas"/"porto"/"alegre"</b>,
     * embora o relatorio original tenha sugerido essas 3 palavras como
     * exemplo de toponimo/termo generico a excluir. Motivo (achado NAO
     * previsto pelo relatorio, encontrado ao implementar): para a equipe
     * "Hospital de Clinicas de Porto Alegre" (HCPA - a instituicao mais
     * citada como exemplo em todo o codigo/CLAUDE.md), essas 3 palavras SAO
     * os UNICOS tokens significativos que sobram depois de tirar "hospital"/
     * "de" (ja stopwords) - exclui-las tambem tornaria essa equipe
     * ESTRUTURALMENTE IMPOSSIVEL de detectar por este mecanismo, mesmo que
     * uma mensagem cite o nome INTEIRO da instituicao por extenso. Isso seria
     * uma regressao de protecao pior que o falso-positivo que o achado A5
     * queria corrigir. A calibragem adotada resolve os DOIS casos concretos
     * do achado A5 ("... de clinicas nao abriu", "...o alegre") sozinha, via
     * a exigencia de >=2 tokens abaixo ({@link #BLOQUEIO_EQUIPE_MIN_TOKENS}) -
     * um UNICO token generico (clinicas OU alegre, isolado) deixa de
     * bloquear, mas a mencao real a "Hospital de Clinicas de Porto Alegre"
     * (varios tokens juntos) continua detectavel. So os termos abaixo, que
     * NAO sao a unica via de deteccao de nenhuma equipe conhecida do
     * dominio, foram acrescentados a lista.</p>
     */
    private static final Set<String> STOPWORDS_EQUIPE = Set.of(
        "hospital", "de", "da", "do", "dos", "das", "e", "o", "a", "os", "as",
        "em", "no", "na", "sem", "equipe", "servico", "clinica", "unidade",
        "santa", "casa", "geral", "universitario", "federal", "municipal",
        "estadual", "nefrologia", "transplante");

    /**
     * Nome do paciente com ATE ESTE NUMERO de tokens significativos e
     * considerado "curto": basta 1 token encontrado na mensagem para
     * BLOQUEAR direto (em vez do ALERTA usado para nomes normais - ver
     * {@link #verificar}). Sem essa excecao, um nome como "Ana Luz" (2
     * tokens de 3 letras cada) so gerava ALERTA com 1 match e NUNCA gerava
     * BLOQUEADO com os 2 juntos ao mesmo tempo, ao contrario de um nome
     * comum de 3+ tokens onde citar 2 deles already blocks. Analogo, do lado
     * da equipe, a {@link #BLOQUEIO_EQUIPE_MIN_TOKENS_TOTAL_CURTA}.
     */
    private static final int NOME_CURTO_MAX_TOKENS = 2;

    /**
     * Minimo de tokens DISTINTOS da equipe que precisam aparecer na mensagem
     * para bloquear, quando a equipe tem MAIS que
     * {@link #BLOQUEIO_EQUIPE_MIN_TOKENS_TOTAL_CURTA} tokens significativos
     * no total (S4, achado A5: 1 token isolado - "clinicas", "alegre" - e
     * vocabulario clinico/toponimo comum demais para bloquear sozinho).
     */
    private static final int BLOQUEIO_EQUIPE_MIN_TOKENS = 2;

    /**
     * Quando a equipe tem ATE este numero de tokens significativos no TOTAL
     * (ex.: sigla curta como "HNSC", que vira 1 token so), exigir 2 tokens
     * simultaneos seria impossivel de satisfazer e tornaria essa equipe
     * IMPOSSIVEL de detectar - mesma logica simetrica de
     * {@link #NOME_CURTO_MAX_TOKENS}. Nesse caso, 1 token basta (comportamento
     * anterior a esta calibragem, preservado so para equipes curtas).
     */
    private static final int BLOQUEIO_EQUIPE_MIN_TOKENS_TOTAL_CURTA = 1;

    /**
     * Verifica um texto livre contra o nome do paciente e a equipe
     * solicitante de UM processo especifico.
     *
     * <p>Regra de nivel para o NOME: 2+ tokens do nome presentes -> BLOQUEADO;
     * exatamente 1 token presente -> BLOQUEADO tambem se o nome inteiro for
     * "curto" (poucos tokens significativos no total - ver
     * {@link #NOME_CURTO_MAX_TOKENS}), senao ALERTA (pede confirmacao, cobre
     * sobrenome comum sem travar o operador para nomes normais).</p>
     *
     * <p>Regra de nivel para a EQUIPE: BLOQUEADO exige
     * {@link #BLOQUEIO_EQUIPE_MIN_TOKENS} tokens distintos encontrados
     * (evita bloquear por 1 palavra generica/toponimo isolada, achado A5) -
     * EXCETO quando a propria equipe so tem
     * {@link #BLOQUEIO_EQUIPE_MIN_TOKENS_TOTAL_CURTA} token(s) significativo(s)
     * no total, caso em que exigir 2 seria impossivel e 1 basta.</p>
     */
    public Resultado verificar(String texto, String pacienteNome, String solicitanteEquipe) {
        if (texto == null || texto.isBlank()) {
            return new Resultado(Nivel.LIVRE, List.of());
        }
        String textoNormalizado = normalizar(texto);

        List<String> tokensEquipe = tokensSignificativosEquipe(normalizar(solicitanteEquipe));
        List<String> tokensEquipeEncontrados = tokensEncontrados(textoNormalizado, tokensEquipe);
        int minimoEquipe = tokensEquipe.size() <= BLOQUEIO_EQUIPE_MIN_TOKENS_TOTAL_CURTA
            ? BLOQUEIO_EQUIPE_MIN_TOKENS_TOTAL_CURTA : BLOQUEIO_EQUIPE_MIN_TOKENS;
        if (tokensEquipeEncontrados.size() >= minimoEquipe && !tokensEquipeEncontrados.isEmpty()) {
            return new Resultado(Nivel.BLOQUEADO, tokensEquipeEncontrados);
        }

        List<String> tokensNome = tokensSignificativosNome(pacienteNome);
        List<String> tokensNomeEncontrados = tokensEncontrados(textoNormalizado, tokensNome);
        if (tokensNomeEncontrados.size() >= 2) {
            return new Resultado(Nivel.BLOQUEADO, tokensNomeEncontrados);
        }
        if (tokensNomeEncontrados.size() == 1) {
            boolean nomeCurto = tokensNome.size() <= NOME_CURTO_MAX_TOKENS;
            return new Resultado(nomeCurto ? Nivel.BLOQUEADO : Nivel.ALERTA, tokensNomeEncontrados);
        }
        return new Resultado(Nivel.LIVRE, List.of());
    }

    /**
     * Tokens do nome do paciente, sem conectivos e com >=3 caracteres
     * (calibragem de 2026-08-10, S4/achado A4 - antes exigia >=4, o que
     * deixava nomes inteiros curtos como "Ana Luz" sem NENHUM token gerado,
     * portanto sem nenhuma protecao possivel).
     */
    private static List<String> tokensSignificativosNome(String nomeCompleto) {
        if (nomeCompleto == null || nomeCompleto.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String parte : nomeCompleto.trim().split("\\s+")) {
            String semAcento = normalizar(parte);
            if (semAcento.isBlank() || CONECTIVOS.contains(parte.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (semAcento.length() >= 3) {
                tokens.add(semAcento);
            }
        }
        return tokens;
    }

    /** Tokens significativos da equipe solicitante (>=3 chars, sem stopwords), mesmo criterio de ConflitoEquipeMatcher. */
    private static List<String> tokensSignificativosEquipe(String equipeNormalizada) {
        if (equipeNormalizada == null || equipeNormalizada.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String t : equipeNormalizada.split(" ")) {
            if (t.length() >= 3 && !STOPWORDS_EQUIPE.contains(t)) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /** Quais dos tokens aparecem, como PALAVRA INTEIRA (nao substring - "Ana" nao deve casar em "analise"), no texto normalizado. */
    private static List<String> tokensEncontrados(String textoNormalizado, List<String> tokens) {
        List<String> encontrados = new ArrayList<>();
        for (String tok : tokens) {
            if (Pattern.compile("\\b" + Pattern.quote(tok) + "\\b").matcher(textoNormalizado).find()) {
                encontrados.add(tok);
            }
        }
        return encontrados;
    }

    /** Minusculas, sem acento, so [a-z0-9] e espaco simples - mesma normalizacao de ConflitoEquipeMatcher. */
    private static String normalizar(String s) {
        if (s == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return semAcento.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}

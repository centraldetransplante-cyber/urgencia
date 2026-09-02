package saur.robo;

/** Um problema encontrado numa página durante a navegação. */
public record Achado(
        Severidade severidade,
        String categoria,
        String perfil,
        String url,
        String tituloPagina,
        String detalhe,
        String screenshot // caminho relativo ao report/, ou null
) {
    public enum Severidade {
        ALTA,   // quebra de verdade: HTTP erro, JS não roda, CSS não carregou, dropdown morto
        MEDIA,  // degradação visível: warning de console, request de asset falhou, estouro de layout
        BAIXA;  // observação / possível ruído

        public String cor() {
            return switch (this) {
                case ALTA -> "#c62828";
                case MEDIA -> "#f5a623";
                case BAIXA -> "#64748b";
            };
        }
    }

    public static Achado alta(String cat, String perfil, String url, String titulo, String detalhe) {
        return new Achado(Severidade.ALTA, cat, perfil, url, titulo, detalhe, null);
    }

    public static Achado media(String cat, String perfil, String url, String titulo, String detalhe) {
        return new Achado(Severidade.MEDIA, cat, perfil, url, titulo, detalhe, null);
    }

    public static Achado baixa(String cat, String perfil, String url, String titulo, String detalhe) {
        return new Achado(Severidade.BAIXA, cat, perfil, url, titulo, detalhe, null);
    }

    public Achado comScreenshot(String caminhoRelativo) {
        return new Achado(severidade, categoria, perfil, url, tituloPagina, detalhe, caminhoRelativo);
    }
}

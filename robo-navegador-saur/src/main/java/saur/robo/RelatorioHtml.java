package saur.robo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Escreve report/: index.html (2 visões), report.md, junit.xml, findings.json, history.csv. */
final class RelatorioHtml {

    record ResumoPerfil(String perfil, int loginStatus, int paginas) {}

    static void escrever(Path dir, Config cfg, List<ResumoPerfil> resumos,
                         List<Achado> achados, long duracaoMs, List<Achado> anteriores) {
        try {
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));

            // --- diff vs. execução anterior ---
            Set<String> chavesAntes = anteriores.stream().map(RelatorioHtml::chave).collect(Collectors.toCollection(TreeSet::new));
            Set<String> chavesAgora = achados.stream().map(RelatorioHtml::chave).collect(Collectors.toCollection(TreeSet::new));
            Map<String, String> estado = new LinkedHashMap<>();
            for (Achado a : achados) estado.put(chave(a), chavesAntes.contains(chave(a)) ? "PERSISTE" : "NOVO");
            List<Achado> corrigidos = anteriores.stream()
                    .filter(a -> !chavesAgora.contains(chave(a)))
                    .collect(Collectors.toList());

            long altas = cont(achados, Achado.Severidade.ALTA);
            long medias = cont(achados, Achado.Severidade.MEDIA);
            long baixas = cont(achados, Achado.Severidade.BAIXA);
            long novos = estado.values().stream().filter("NOVO"::equals).count();
            int totalPag = resumos.stream().mapToInt(ResumoPerfil::paginas).sum();

            // contagem por categoria e por página
            Map<String, Long> porCategoria = achados.stream()
                    .collect(Collectors.groupingBy(Achado::categoria, TreeMap::new, Collectors.counting()));
            Map<String, List<Achado>> porPagina = achados.stream()
                    .collect(Collectors.groupingBy(Achado::url, LinkedHashMap::new, Collectors.toList()));
            List<Map.Entry<String, List<Achado>>> topPaginas = porPagina.entrySet().stream()
                    .sorted((x, y) -> Integer.compare(peso(y.getValue()), peso(x.getValue())))
                    .limit(8).collect(Collectors.toList());

            // ---------- HTML ----------
            StringBuilder h = new StringBuilder();
            h.append(HEAD_HTML);
            h.append("<h1>Robô navegador SAUR — relatório</h1>");
            h.append("<div class=\"sub\">").append(esc(ts)).append(" · alvo <code>").append(esc(cfg.baseUrl))
             .append("</code> · modo ").append(esc(cfg.modo)).append(" · ").append(duracaoMs / 1000).append("s</div>");

            h.append("<div class=\"cards\">");
            h.append(card(altas, "achados altos", "#c62828"));
            h.append(card(medias, "médios", "#f5a623"));
            h.append(card(baixas, "baixos", "#64748b"));
            h.append(card(novos, "novos vs. última", "#1a4d8f"));
            h.append(card(corrigidos.size(), "corrigidos", "#2d8546"));
            h.append(card(totalPag, "páginas", "#334155"));
            h.append("</div>");

            // perfis
            h.append("<h2>Perfis</h2><table><thead><tr><th>Perfil</th><th>Login</th><th>Páginas</th><th>Achados</th></tr></thead><tbody>");
            for (ResumoPerfil r : resumos) {
                long n = achados.stream().filter(a -> a.perfil().equals(r.perfil())).count();
                String login = switch (r.loginStatus()) { case 1 -> "ok"; case -1 -> "<b style=color:#c62828>FALHOU</b>"; default -> "—"; };
                h.append("<tr><td>").append(esc(r.perfil())).append("</td><td>").append(login)
                 .append("</td><td>").append(r.paginas()).append("</td><td>").append(n).append("</td></tr>");
            }
            h.append("</tbody></table>");

            // categorias
            h.append("<h2>Por categoria</h2><div class=\"chips\">");
            porCategoria.forEach((c, n) -> h.append("<span class=\"chip\">").append(esc(c)).append(" <b>").append(n).append("</b></span>"));
            if (porCategoria.isEmpty()) h.append("<span class=\"chip\">nada</span>");
            h.append("</div>");

            // top páginas
            if (!topPaginas.isEmpty()) {
                h.append("<h2>Páginas mais problemáticas</h2><table><thead><tr><th>Página</th><th>Altos</th><th>Médios</th><th>Baixos</th></tr></thead><tbody>");
                for (var e : topPaginas) {
                    h.append("<tr><td><code>").append(esc(caminho(cfg, e.getKey()))).append("</code></td>")
                     .append("<td>").append(cont(e.getValue(), Achado.Severidade.ALTA)).append("</td>")
                     .append("<td>").append(cont(e.getValue(), Achado.Severidade.MEDIA)).append("</td>")
                     .append("<td>").append(cont(e.getValue(), Achado.Severidade.BAIXA)).append("</td></tr>");
                }
                h.append("</tbody></table>");
            }

            // corrigidos
            if (!corrigidos.isEmpty()) {
                h.append("<h2>Corrigidos desde a última execução (").append(corrigidos.size()).append(")</h2>");
                for (Achado a : corrigidos)
                    h.append("<div class=\"ach fix\"><span class=\"cat\">").append(esc(a.categoria()))
                     .append("</span> <span class=\"url\">").append(esc(caminho(cfg, a.url()))).append("</span><div class=\"det\">")
                     .append(esc(a.detalhe())).append("</div></div>");
            }

            // achados — visão por severidade
            h.append("<h2>Achados — por severidade</h2>");
            if (achados.isEmpty()) h.append("<div class=\"empty\">Nada encontrado nas páginas visitadas. ✅</div>");
            else {
                var porSev = achados.stream().sorted(Comparator.comparing(Achado::severidade))
                        .collect(Collectors.groupingBy(Achado::severidade, LinkedHashMap::new, Collectors.toList()));
                for (var e : porSev.entrySet()) {
                    h.append("<h3>").append(e.getKey()).append(" (").append(e.getValue().size()).append(")</h3>");
                    for (Achado a : e.getValue()) h.append(achHtml(cfg, a, estado.getOrDefault(chave(a), "")));
                }
            }

            // achados — visão por página
            if (!achados.isEmpty()) {
                h.append("<h2>Achados — por página</h2>");
                porPagina.entrySet().stream()
                        .sorted((x, y) -> Integer.compare(peso(y.getValue()), peso(x.getValue())))
                        .forEach(e -> {
                            h.append("<details class=\"pg\"><summary><code>").append(esc(caminho(cfg, e.getKey())))
                             .append("</code> — ").append(e.getValue().size()).append(" achado(s)</summary>");
                            for (Achado a : e.getValue()) h.append(achHtml(cfg, a, estado.getOrDefault(chave(a), "")));
                            h.append("</details>");
                        });
            }

            h.append("<p class=\"sub\">Máquina: <a href=\"findings.json\">findings.json</a> · "
                    + "<a href=\"report.md\">report.md</a> · <a href=\"junit.xml\">junit.xml</a> · histórico em history.csv. "
                    + "Robô só navega (GET) e clica em gatilhos seguros — nunca envia formulário.</p>");
            h.append("</div></body></html>");

            Files.writeString(dir.resolve("index.html"), h.toString(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("findings.json"), json(cfg, resumos, achados, estado, duracaoMs), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("report.md"), markdown(cfg, ts, resumos, achados, estado, corrigidos, porCategoria, duracaoMs), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("junit.xml"), junit(achados), StandardCharsets.UTF_8);
            appendHistory(dir.resolve("history.csv"), ts, cfg.baseUrl, totalPag, altas, medias, baixas, novos, corrigidos.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** achados de uma execução anterior (findings.json), para o diff. [] se não houver. */
    static List<Achado> lerAnteriores(Path findingsJson) {
        List<Achado> out = new ArrayList<>();
        try {
            if (!Files.isRegularFile(findingsJson)) return out;
            String txt = Files.readString(findingsJson);
            int i = txt.indexOf("\"achados\"");
            if (i < 0) return out;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\\{\\s*\"severidade\":\"([^\"]*)\",\"categoria\":\"([^\"]*)\",\"perfil\":\"([^\"]*)\",\"url\":\"((?:[^\"\\\\]|\\\\.)*)\",\"titulo\":\"((?:[^\"\\\\]|\\\\.)*)\",\"detalhe\":\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(txt.substring(i));
            while (m.find()) {
                out.add(new Achado(Achado.Severidade.valueOf(m.group(1)), m.group(2), m.group(3),
                        unesc(m.group(4)), unesc(m.group(5)), unesc(m.group(6)), null));
            }
        } catch (Exception ignore) {}
        return out;
    }

    // ---------- pedaços ----------

    private static final String HEAD_HTML = """
        <!doctype html><html lang="pt-br"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Robô navegador SAUR — relatório</title>
        <style>
          :root{--bg:#f8fafc;--fg:#1e293b;--mut:#64748b;--card:#fff;--bord:#e2e8f0;--azul:#1a4d8f}
          @media(prefers-color-scheme:dark){:root{--bg:#0f172a;--fg:#e2e8f0;--mut:#94a3b8;--card:#1e293b;--bord:#334155;--azul:#5b8fd6}}
          *{box-sizing:border-box}
          body{margin:0;font:15px/1.55 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--fg)}
          .wrap{max-width:1100px;margin:0 auto;padding:2rem 1.25rem 5rem}
          h1{font-size:1.5rem;margin:.2rem 0}h2{margin:2rem 0 .6rem;font-size:1.1rem}h3{margin:1.2rem 0 .4rem;font-size:.95rem;color:var(--mut)}
          .sub{color:var(--mut);margin:.2rem 0 1.4rem}
          .cards{display:flex;flex-wrap:wrap;gap:.7rem;margin-bottom:1rem}
          .card{background:var(--card);border:1px solid var(--bord);border-radius:12px;padding:.8rem 1rem;min-width:120px}
          .card .n{font-size:1.5rem;font-weight:800;line-height:1}
          .card .l{color:var(--mut);font-size:.72rem;text-transform:uppercase;letter-spacing:.04em;margin-top:.3rem}
          table{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--bord);border-radius:12px;overflow:hidden;margin-bottom:1rem}
          th,td{text-align:left;padding:.5rem .75rem;border-bottom:1px solid var(--bord);font-size:.88rem}
          th{background:color-mix(in srgb,var(--card) 92%,var(--fg));font-size:.72rem;text-transform:uppercase;letter-spacing:.04em;color:var(--mut)}
          tr:last-child td{border-bottom:0}
          .chips{display:flex;flex-wrap:wrap;gap:.4rem;margin-bottom:1rem}
          .chip{background:var(--card);border:1px solid var(--bord);border-radius:999px;padding:.15rem .6rem;font-size:.8rem;font-family:ui-monospace,Menlo,Consolas,monospace}
          .ach{background:var(--card);border:1px solid var(--bord);border-left-width:5px;border-radius:10px;padding:.7rem .95rem;margin:.55rem 0}
          .ach .top{display:flex;flex-wrap:wrap;gap:.45rem;align-items:center;font-size:.78rem;color:var(--mut)}
          .pill{font-weight:700;font-size:.68rem;text-transform:uppercase;letter-spacing:.03em;padding:.08rem .45rem;border-radius:999px;color:#fff}
          .tag-novo{background:#1a4d8f;color:#fff;font-size:.65rem;padding:.05rem .4rem;border-radius:999px}
          .tag-persiste{background:var(--bord);color:var(--mut);font-size:.65rem;padding:.05rem .4rem;border-radius:999px}
          .cat{font-family:ui-monospace,Menlo,Consolas,monospace;background:color-mix(in srgb,var(--card) 85%,var(--fg));padding:.03rem .35rem;border-radius:5px}
          .ach .url{margin:.3rem 0;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.8rem;word-break:break-all}
          .ach .det{font-size:.9rem}
          .ach.fix{border-left-color:#2d8546;opacity:.85}
          details.pg{background:var(--card);border:1px solid var(--bord);border-radius:10px;margin:.4rem 0;padding:.2rem .6rem}
          details.pg>summary{cursor:pointer;padding:.4rem 0;font-size:.9rem}
          a{color:var(--azul)}
          .empty{background:var(--card);border:1px solid var(--bord);border-radius:12px;padding:2rem;text-align:center;color:var(--mut)}
          .shot{display:inline-block;margin-top:.45rem;font-size:.78rem}
        </style></head><body><div class="wrap">
        """;

    private static String card(long n, String l, String cor) {
        return "<div class=\"card\"><div class=\"n\" style=\"color:" + cor + "\">" + n + "</div><div class=\"l\">" + esc(l) + "</div></div>";
    }

    private static String achHtml(Config cfg, Achado a, String est) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"ach\" style=\"border-left-color:").append(a.severidade().cor()).append("\">");
        s.append("<div class=\"top\"><span class=\"pill\" style=\"background:").append(a.severidade().cor()).append("\">")
         .append(a.severidade()).append("</span>");
        if ("NOVO".equals(est)) s.append("<span class=\"tag-novo\">NOVO</span>");
        else if ("PERSISTE".equals(est)) s.append("<span class=\"tag-persiste\">persiste</span>");
        s.append("<span class=\"cat\">").append(esc(a.categoria())).append("</span>");
        s.append("<span>perfil ").append(esc(a.perfil())).append("</span>");
        if (a.tituloPagina() != null && !a.tituloPagina().isBlank()) s.append("<span>· ").append(esc(a.tituloPagina())).append("</span>");
        s.append("</div><div class=\"url\">").append(esc(caminho(cfg, a.url()))).append("</div>");
        s.append("<div class=\"det\">").append(esc(a.detalhe())).append("</div>");
        if (a.screenshot() != null) s.append("<a class=\"shot\" href=\"").append(esc(a.screenshot())).append("\">📷 screenshot</a>");
        return s.append("</div>").toString();
    }

    private static String markdown(Config cfg, String ts, List<ResumoPerfil> resumos, List<Achado> achados,
                                   Map<String, String> estado, List<Achado> corrigidos, Map<String, Long> cats, long dur) {
        StringBuilder m = new StringBuilder();
        m.append("# Robô navegador SAUR — ").append(ts).append("\n\n");
        m.append("- alvo: `").append(cfg.baseUrl).append("` · modo ").append(cfg.modo).append(" · ").append(dur / 1000).append("s\n");
        m.append("- ").append(cont(achados, Achado.Severidade.ALTA)).append(" altos · ")
         .append(cont(achados, Achado.Severidade.MEDIA)).append(" médios · ")
         .append(cont(achados, Achado.Severidade.BAIXA)).append(" baixos · ")
         .append(estado.values().stream().filter("NOVO"::equals).count()).append(" novos · ")
         .append(corrigidos.size()).append(" corrigidos\n\n");
        m.append("| perfil | login | páginas | achados |\n|---|---|---|---|\n");
        for (ResumoPerfil r : resumos)
            m.append("| ").append(r.perfil()).append(" | ").append(r.loginStatus() == 1 ? "ok" : r.loginStatus() == -1 ? "FALHOU" : "—")
             .append(" | ").append(r.paginas()).append(" | ")
             .append(achados.stream().filter(a -> a.perfil().equals(r.perfil())).count()).append(" |\n");
        m.append("\ncategorias: ").append(cats.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "))).append("\n\n");
        for (Achado.Severidade sv : Achado.Severidade.values()) {
            List<Achado> lst = achados.stream().filter(a -> a.severidade() == sv).toList();
            if (lst.isEmpty()) continue;
            m.append("## ").append(sv).append(" (").append(lst.size()).append(")\n\n");
            for (Achado a : lst)
                m.append("- ").append("NOVO".equals(estado.getOrDefault(chave(a), "")) ? "**[NOVO]** " : "")
                 .append("`").append(a.categoria()).append("` `").append(caminho(cfg, a.url())).append("` — ")
                 .append(a.detalhe().replace("\n", " ")).append("\n");
            m.append("\n");
        }
        if (!corrigidos.isEmpty()) {
            m.append("## Corrigidos desde a última execução\n\n");
            for (Achado a : corrigidos) m.append("- `").append(a.categoria()).append("` `").append(caminho(cfg, a.url())).append("`\n");
        }
        return m.toString();
    }

    private static String junit(List<Achado> achados) {
        long fail = cont(achados, Achado.Severidade.ALTA);
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<testsuite name=\"robo-navegador-saur\" tests=\"").append(Math.max(1, achados.size()))
         .append("\" failures=\"").append(fail).append("\">\n");
        if (achados.isEmpty()) x.append("  <testcase name=\"varredura\" classname=\"saur\"/>\n");
        for (Achado a : achados) {
            String nm = esc(a.categoria() + " @ " + a.url());
            x.append("  <testcase name=\"").append(nm).append("\" classname=\"").append(esc(a.perfil())).append("\">");
            if (a.severidade() == Achado.Severidade.ALTA)
                x.append("<failure message=\"").append(esc(a.detalhe())).append("\"/>");
            else
                x.append("<system-out>").append(esc(a.severidade() + ": " + a.detalhe())).append("</system-out>");
            x.append("</testcase>\n");
        }
        return x.append("</testsuite>\n").toString();
    }

    private static void appendHistory(Path csv, String ts, String base, int pag, long alt, long med, long bai, long novos, int corr) throws IOException {
        boolean novo = !Files.isRegularFile(csv);
        StringBuilder l = new StringBuilder();
        if (novo) l.append("data;alvo;paginas;altos;medios;baixos;novos;corrigidos\n");
        l.append(ts).append(';').append(base).append(';').append(pag).append(';')
         .append(alt).append(';').append(med).append(';').append(bai).append(';').append(novos).append(';').append(corr).append('\n');
        Files.writeString(csv, l.toString(), StandardCharsets.UTF_8,
                novo ? StandardOpenOption.CREATE : StandardOpenOption.APPEND);
    }

    private static String json(Config cfg, List<ResumoPerfil> resumos, List<Achado> achados, Map<String, String> estado, long dur) {
        StringBuilder j = new StringBuilder();
        j.append("{\n  \"baseUrl\": ").append(q(cfg.baseUrl)).append(",\n  \"duracaoMs\": ").append(dur).append(",\n  \"resumo\": [");
        for (int i = 0; i < resumos.size(); i++) {
            ResumoPerfil r = resumos.get(i);
            j.append(i == 0 ? "" : ", ").append("{\"perfil\":").append(q(r.perfil()))
             .append(",\"loginStatus\":").append(r.loginStatus()).append(",\"paginas\":").append(r.paginas()).append("}");
        }
        j.append("],\n  \"achados\": [\n");
        for (int i = 0; i < achados.size(); i++) {
            Achado a = achados.get(i);
            j.append("    {\"severidade\":").append(q(a.severidade().name()))
             .append(",\"categoria\":").append(q(a.categoria())).append(",\"perfil\":").append(q(a.perfil()))
             .append(",\"url\":").append(q(a.url())).append(",\"titulo\":").append(q(a.tituloPagina()))
             .append(",\"detalhe\":").append(q(a.detalhe()))
             .append(",\"estado\":").append(q(estado.getOrDefault(chave(a), "")))
             .append(",\"screenshot\":").append(a.screenshot() == null ? "null" : q(a.screenshot())).append("}")
             .append(i < achados.size() - 1 ? ",\n" : "\n");
        }
        return j.append("  ]\n}\n").toString();
    }

    // ---------- util ----------

    private static String chave(Achado a) { return a.perfil() + "|" + a.categoria() + "|" + a.url(); }
    private static long cont(List<Achado> l, Achado.Severidade s) { return l.stream().filter(a -> a.severidade() == s).count(); }
    private static int peso(List<Achado> l) {
        return (int) (cont(l, Achado.Severidade.ALTA) * 100 + cont(l, Achado.Severidade.MEDIA) * 10 + cont(l, Achado.Severidade.BAIXA));
    }
    private static String caminho(Config cfg, String u) {
        String s = u != null && u.startsWith(cfg.baseUrl) ? u.substring(cfg.baseUrl.length()) : u;
        return s == null || s.isEmpty() ? "/" : s;
    }
    private static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) switch (c) {
            case '"' -> b.append("\\\"");
            case '\\' -> b.append("\\\\");
            case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r");
            case '\t' -> b.append("\\t");
            default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
        }
        return b.append('"').toString();
    }
    private static String unesc(String s) {
        return s == null ? "" : s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\");
    }
    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private RelatorioHtml() {}
}

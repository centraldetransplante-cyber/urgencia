package saur.robo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Regressão visual sem dependência externa: compara o screenshot de cada página
 * com um baseline salvo (PNG), pixel a pixel, via javax.imageio.
 *
 * <p>1ª execução com {@code regressao-visual=true} cria os baselines (nenhum
 * achado). Nas seguintes, página cujo screenshot difere acima de
 * {@code visual-limite-pct} vira um achado {@code visual} + uma imagem de diff
 * em {@code report/diff/}.
 */
final class RegressaoVisual {

    private final Path baselineDir;
    private final Path diffDir;
    private final double limitePct;
    private final int tolCanal = 24;   // diferença por canal RGB abaixo disso = "igual" (anti-ruído de anti-aliasing)
    int baselinesCriados = 0;

    RegressaoVisual(Path baselineDir, Path diffDir, double limitePct) {
        this.baselineDir = baselineDir;
        this.diffDir = diffDir;
        this.limitePct = limitePct;
    }

    List<Achado> comparar(String perfil, String url, String titulo, byte[] pngAtual, String slug) {
        List<Achado> out = new ArrayList<>();
        if (pngAtual == null || pngAtual.length == 0) return out;
        try {
            Files.createDirectories(baselineDir);
            Path base = baselineDir.resolve(slug + ".png");
            if (!Files.isRegularFile(base)) {
                Files.write(base, pngAtual);
                baselinesCriados++;
                return out;
            }
            BufferedImage b = ImageIO.read(base.toFile());
            BufferedImage a = ImageIO.read(new ByteArrayInputStream(pngAtual));
            if (b == null || a == null) return out;

            if (b.getWidth() != a.getWidth() || b.getHeight() != a.getHeight()) {
                out.add(Achado.media("visual", perfil, url, titulo,
                        "tamanho da página mudou vs baseline: " + b.getWidth() + "x" + b.getHeight()
                        + " → " + a.getWidth() + "x" + a.getHeight()));
                return out;
            }

            int w = a.getWidth(), h = a.getHeight();
            long diff = 0, total = (long) w * h;
            BufferedImage vis = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int pa = a.getRGB(x, y), pb = b.getRGB(x, y);
                    int dr = Math.abs(((pa >> 16) & 0xff) - ((pb >> 16) & 0xff));
                    int dg = Math.abs(((pa >> 8) & 0xff) - ((pb >> 8) & 0xff));
                    int db = Math.abs((pa & 0xff) - (pb & 0xff));
                    if (dr > tolCanal || dg > tolCanal || db > tolCanal) {
                        diff++;
                        vis.setRGB(x, y, 0xE53935); // vermelho onde mudou
                    } else {
                        int g = ((((pb >> 16) & 0xff) + ((pb >> 8) & 0xff) + (pb & 0xff)) / 3);
                        g = 160 + g / 4; // baseline esmaecido de fundo
                        vis.setRGB(x, y, (g << 16) | (g << 8) | g);
                    }
                }
            }
            double pct = 100.0 * diff / total;
            if (pct > limitePct) {
                Files.createDirectories(diffDir);
                Path d = diffDir.resolve(slug + ".png");
                ImageIO.write(vis, "png", d.toFile());
                out.add(Achado.media("visual", perfil, url, titulo,
                        String.format("%.2f%% dos pixels mudaram vs baseline (limite %.2f%%)", pct, limitePct))
                        .comScreenshot("diff/" + d.getFileName()));
            }
        } catch (IOException | RuntimeException e) {
            // regressão visual é best-effort — nunca derruba a varredura
        }
        return out;
    }
}

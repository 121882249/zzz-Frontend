package work.tokenpro.client;

import javax.swing.Icon;
import java.awt.*;
import java.awt.geom.Rectangle2D;

/** CLI icons render at display scale and require no installed image resources. */
final class ApplicationIcon implements Icon {
    private final int size;
    private final boolean codex;
    ApplicationIcon(String name, int size) { this.size = size; this.codex = name.equals("Codex"); }
    public int getIconWidth() { return size; }
    public int getIconHeight() { return size; }
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create(); g.translate(x, y);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (codex) {
            g.drawImage(ApplicationArtwork.COMMAND, 0, 0, size, size, null);
        } else {
            // Preserve the reference's stepped silhouette, four feet and two black eyes.
            // No screenshot background or text is retained.
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            double unit = size * .81 / 16;
            g.translate((size - unit * 16) / 2, (size - unit * 10) / 2); g.scale(unit, unit);
            g.setColor(new Color(216, 123, 89));
            g.fill(new Rectangle2D.Double(2, 0, 12, 8));
            g.fill(new Rectangle2D.Double(0, 4, 16, 2));
            for (int foot : new int[]{3, 5, 10, 12}) g.fill(new Rectangle2D.Double(foot, 8, 1, 2));
            g.setColor(new Color(12, 11, 10));
            g.fill(new Rectangle2D.Double(4, 2, 1, 2)); g.fill(new Rectangle2D.Double(11, 2, 1, 2));
        }
        g.dispose();
    }
}

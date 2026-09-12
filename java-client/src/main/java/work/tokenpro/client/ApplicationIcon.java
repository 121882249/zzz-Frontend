package work.tokenpro.client;

import javax.swing.Icon;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Paints at the target display scale without a low-resolution intermediate image. */
final class ApplicationIcon implements Icon {
    private final int size;
    private final boolean codex;
    private final boolean command;
    private static final BufferedImage CLAUDE_MARK = creamMark();

    ApplicationIcon(String name, int size, boolean command) {
        this.size = size; this.codex = name.equals("Codex"); this.command = command;
    }
    public int getIconWidth() { return size; }
    public int getIconHeight() { return size; }
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create(); g.translate(x, y);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (codex && command) {
            g.drawImage(ApplicationArtwork.COMMAND, 0, 0, size, size, null);
        } else {
            double inset = size * .095, side = size - inset * 2;
            g.setColor(codex ? new Color(250, 250, 251) : new Color(216, 119, 87));
            g.fill(new java.awt.geom.RoundRectangle2D.Double(inset, inset, side, side, size * .25, size * .25));
            int markSize = (int)Math.round(size * .63), offset = (size - markSize) / 2;
            g.drawImage(codex ? ApplicationArtwork.CODEX : CLAUDE_MARK, offset, offset, markSize, markSize, null);
        }
        g.dispose();
    }
    private static BufferedImage creamMark() {
        BufferedImage source = ApplicationArtwork.CLAUDE;
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++)
            result.setRGB(x, y, (source.getRGB(x, y) & 0xff000000) | 0xfff3e6);
        return result;
    }
}

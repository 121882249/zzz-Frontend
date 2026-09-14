package work.tokenpro.client;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Real Swing scroll repaint over a conspicuous owner; no account or API access. */
public final class ModelPickerScrollRegressionTest {
    public static void main(String[] args) throws Exception {
        var catalog = new ArrayList<PricedModel>();
        for (int i = 0; i < 60; i++) catalog.add(new PricedModel("gpt-scroll-fixture-" + i, "openai", "Fixture " + i / 15, 10 + i / 15));
        var flare = new PricedModel("gpt-image-2.5-flare", "openai", "Images", 65);
        var sunburst = new PricedModel("gpt-image-2.5-sunburst", "openai", "Images", 65);
        catalog.add(flare); catalog.add(sunburst);
        Set<String> chosen = Set.of(ModelPickerDialog.id(catalog.getFirst()), ModelPickerDialog.id(flare), ModelPickerDialog.id(sunburst));
        var applied = new AtomicReference<List<PricedModel>>();
        var owner = new AtomicReference<JFrame>();
        var picker = new AtomicReference<ModelPickerDialog>();
        var scroll = new AtomicReference<JScrollPane>();
        Path output = Files.createTempDirectory("tokenpro-picker-scroll-");
        Robot robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame("TokenPro scroll regression fixture");
                frame.getContentPane().setBackground(Color.MAGENTA);
                frame.setSize(900, 760); frame.setLocationRelativeTo(null); frame.setVisible(true);
                owner.set(frame);
                ModelPickerDialog dialog = new ModelPickerDialog(frame, "Codex", catalog, chosen, applied::set);
                dialog.setModal(false); dialog.setVisible(true); dialog.validate(); picker.set(dialog);
                scroll.set(findScroll(dialog));
                require(scroll.get() != null, "missing list");
            });
            robot.waitForIdle();
            int frames = 48;
            for (int i = 0; i < frames; i++) {
                final int frame = i;
                var bounds = new AtomicReference<Rectangle>();
                SwingUtilities.invokeAndWait(() -> {
                    JScrollPane pane = scroll.get(); JViewport viewport = pane.getViewport();
                    JScrollBar bar = pane.getVerticalScrollBar();
                    int max = bar.getMaximum() - bar.getVisibleAmount();
                    int step = frame < frames / 2 ? frame : frames - 1 - frame;
                    bar.setValue(max * step / (frames / 2 - 1));
                    BufferedImage image = new BufferedImage(viewport.getWidth(), viewport.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = image.createGraphics(); viewport.paint(g); g.dispose();
                    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                        require((image.getRGB(x, y) >>> 24) == 255, "transparent list pixel at frame " + frame);
                    bounds.set(new Rectangle(viewport.getLocationOnScreen(), viewport.getSize()));
                });
                // Observe before waiting for idle so a stale owner-surface frame
                // is not concealed by a forced synchronous repaint.
                BufferedImage screen = robot.createScreenCapture(bounds.get());
                int painted = 0;
                for (int y = 0; y < screen.getHeight(); y += 2) for (int x = 0; x < screen.getWidth(); x += 2) {
                    int pixel = screen.getRGB(x, y) & 0xffffff;
                    require(pixel != 0xff00ff, "owner leaked through scroll at frame " + frame);
                    if (pixel != 0) painted++;
                }
                require(painted > 1000, "screen capture unavailable");
                if (i == frames - 1) javax.imageio.ImageIO.write(screen, "png", output.resolve("viewport.png").toFile());
            }
            SwingUtilities.invokeAndWait(() -> {
                int[] alpha = ModelPickerDialog.cosmosBackgroundAlphaPixels(200, 160);
                require(alpha[0] == 0 && alpha[1] == 255, "rounded corner alpha changed");
                findApply(picker.get()).doClick();
            });
            require(applied.get() != null && applied.get().size() == 3, "selection lost during scrolling");
            require(applied.get().stream().allMatch(m -> chosen.contains(ModelPickerDialog.id(m))), "selection changed during scrolling");
            System.out.println("PASS: " + frames + " up/down scroll frames opaque; no owner pixels; rounded corners and three selections preserved. " + output);
        } finally {
            SwingUtilities.invokeAndWait(() -> {if (picker.get() != null) picker.get().dispose(); if (owner.get() != null) owner.get().dispose();});
        }
    }
    static JScrollPane findScroll(Container root) {
        for (Component c : root.getComponents()) {if (c instanceof JScrollPane s) return s; if (c instanceof Container nested) {var found = findScroll(nested); if (found != null) return found;}}
        return null;
    }
    static JButton findApply(Container root) {
        for (Component c : root.getComponents()) {if (c instanceof JButton b && "应用模型".equals(b.getText())) return b; if (c instanceof Container nested) {var found = findApply(nested); if (found != null) return found;}}
        return null;
    }
    static void require(boolean value, String message) {if (!value) throw new AssertionError(message);}
}

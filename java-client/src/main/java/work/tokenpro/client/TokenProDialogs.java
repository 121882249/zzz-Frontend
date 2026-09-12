package work.tokenpro.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/** TokenPro-styled dialogs that render consistently across macOS, Windows, and Linux. */
final class TokenProDialogs {
    private static final Color TEXT = new Color(244, 247, 255);
    private static final Color MUTED = new Color(177, 188, 220);

    private TokenProDialogs() {}

    static boolean confirm(Component owner, String title, String message, String confirmText) {
        return show(owner, title, message, confirmText, true, Tone.WARNING).accepted;
    }

    static void info(Component owner, String title, String message) {
        show(owner, title, message, "知道了", false, Tone.INFO);
    }

    static void warning(Component owner, String title, String message) {
        show(owner, title, message, "知道了", false, Tone.WARNING);
    }

    static void error(Component owner, String title, String message) {
        show(owner, title, message, "确定", false, Tone.ERROR);
    }

    private static DialogResult show(Component owner, String title, String message, String primaryText,
                                     boolean cancellable, Tone tone) {
        Window window = owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = window == null ? new JDialog((Frame) null, true)
            : new JDialog(window, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        dialog.setBackground(new Color(0, 0, 0, 0));

        DialogResult result = new DialogResult();
        JPanel shell = new JPanel(new BorderLayout());
        shell.setOpaque(false);
        shell.setBorder(new EmptyBorder(12, 12, 12, 12));
        DialogSurface surface = new DialogSurface(tone);
        surface.setLayout(new BorderLayout(18, 14));
        surface.setBorder(new EmptyBorder(20, 22, 18, 22));
        shell.add(surface);

        JPanel header = transparent(new BorderLayout());
        JLabel heading = new JLabel(title);
        heading.setFont(font(17, Font.BOLD));
        heading.setForeground(TEXT);
        header.add(heading, BorderLayout.WEST);
        JButton close = iconButton("×");
        close.addActionListener(event -> dialog.dispose());
        header.add(close, BorderLayout.EAST);
        surface.add(header, BorderLayout.NORTH);

        JPanel content = transparent(new BorderLayout(15, 0));
        content.add(new ToneIcon(tone), BorderLayout.WEST);
        JTextArea copy = new JTextArea(message == null ? "" : message);
        copy.setEditable(false);
        copy.setFocusable(false);
        copy.setLineWrap(true);
        copy.setWrapStyleWord(true);
        copy.setOpaque(false);
        copy.setForeground(new Color(226, 232, 252));
        copy.setFont(font(13, Font.PLAIN));
        copy.setBorder(new EmptyBorder(2, 0, 0, 0));
        content.add(copy, BorderLayout.CENTER);
        surface.add(content, BorderLayout.CENTER);

        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        if (cancellable) {
            JButton cancel = new DialogButton("取消", false);
            cancel.addActionListener(event -> dialog.dispose());
            actions.add(cancel);
        }
        JButton primary = new DialogButton(primaryText, true);
        primary.addActionListener(event -> { result.accepted = true; dialog.dispose(); });
        actions.add(primary);
        surface.add(actions, BorderLayout.SOUTH);

        dialog.setContentPane(shell);
        int length = message == null ? 0 : message.length();
        dialog.setSize(540, Math.min(310, Math.max(224, 190 + Math.max(0, length - 56) / 3)));
        dialog.setLocationRelativeTo(owner);
        dialog.getRootPane().setDefaultButton(primary);
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        dialog.getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { dialog.dispose(); }
        });
        dialog.setVisible(true);
        dialog.dispose();
        return result;
    }

    private static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private static JButton iconButton(String text) {
        JButton button = new JButton(text);
        button.setUI(new BasicButtonUI());
        button.setFont(font(20, Font.PLAIN));
        button.setForeground(MUTED);
        button.setBorder(new EmptyBorder(0, 8, 0, 8));
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static Font font(float size, int style) {
        return new Font(Platform.OS_KIND == Platform.OS.MAC ? ".AppleSystemUIFont" : "SansSerif",
            style, Math.round(size));
    }

    private enum Tone { INFO, WARNING, ERROR }
    private static final class DialogResult { boolean accepted; }

    private static final class DialogSurface extends JPanel {
        private final Tone tone;
        DialogSurface(Tone tone) { this.tone = tone; setOpaque(false); }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D shape = new RoundRectangle2D.Double(.5, .5, getWidth() - 1, getHeight() - 1, 24, 24);
            g.setPaint(new GradientPaint(0, 0, new Color(18, 32, 70, 252), getWidth(), getHeight(), new Color(25, 16, 61, 252)));
            g.fill(shape);
            Color accent = tone == Tone.ERROR ? new Color(255, 111, 126, 175)
                : tone == Tone.WARNING ? new Color(244, 197, 102, 175) : new Color(116, 180, 255, 175);
            g.setColor(accent);
            g.draw(shape);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class ToneIcon extends JComponent {
        private final Tone tone;
        ToneIcon(Tone tone) { this.tone = tone; setPreferredSize(new Dimension(42, 42)); }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color color = tone == Tone.ERROR ? new Color(255, 102, 121)
                : tone == Tone.WARNING ? new Color(244, 194, 86) : new Color(105, 176, 255);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 42));
            g.fillOval(1, 1, 40, 40);
            g.setColor(color);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(5, 5, 32, 32);
            g.setFont(font(19, Font.BOLD));
            String symbol = tone == Tone.INFO ? "i" : "!";
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(symbol, (getWidth() - metrics.stringWidth(symbol)) / 2,
                (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
            g.dispose();
        }
    }

    private static final class DialogButton extends JButton {
        private final boolean primary;
        DialogButton(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setUI(new BasicButtonUI());
            setFont(font(12, Font.BOLD));
            setForeground(primary ? Color.WHITE : new Color(211, 220, 247));
            setPreferredSize(new Dimension(primary ? 108 : 88, 38));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean hover = getModel().isRollover();
            if (primary) g.setPaint(new GradientPaint(0, 0,
                hover ? new Color(127, 108, 255) : new Color(105, 88, 247), getWidth(), 0,
                hover ? new Color(74, 166, 255) : new Color(61, 145, 249)));
            else g.setColor(hover ? new Color(52, 68, 118) : new Color(35, 49, 91));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 13, 13);
            g.setColor(primary ? new Color(170, 178, 255, 105) : new Color(137, 158, 220, 90));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 13, 13);
            g.dispose();
            super.paintComponent(graphics);
        }
    }
}

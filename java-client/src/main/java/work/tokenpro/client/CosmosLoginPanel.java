package work.tokenpro.client;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;

final class CosmosLoginPanel extends JPanel {
    private static final Color TEXT = new Color(246, 248, 255);
    private static final Color MUTED = new Color(164, 175, 211);
    private final JButton loginButton = new GradientButton("登录 / 注册");
    private final JLabel feedback = label("登录信息仅加密保存在当前设备", 11, Font.PLAIN, new Color(145, 156, 191));

    CosmosLoginPanel(JTextField email, JPasswordField password, ActionListener loginAction) {
        super(new GridBagLayout());
        setOpaque(false);
        setBorder(new EmptyBorder(34, 48, 40, 48));

        GridBagConstraints hero = new GridBagConstraints();
        hero.gridx = 0; hero.gridy = 0; hero.weightx = .54; hero.weighty = 1;
        hero.fill = GridBagConstraints.BOTH; hero.insets = new Insets(0, 0, 0, 34);
        add(new HeroPanel(), hero);

        GridBagConstraints login = new GridBagConstraints();
        login.gridx = 1; login.gridy = 0; login.weightx = .46; login.weighty = 1;
        login.fill = GridBagConstraints.BOTH;
        JPanel loginWell = new JPanel(new GridBagLayout());
        loginWell.setOpaque(false);
        loginWell.add(loginCard(email, password, loginAction));
        add(loginWell, login);
    }

    private JComponent loginCard(JTextField email, JPasswordField password, ActionListener loginAction) {
        GlassPanel card = new GlassPanel();
        card.setPreferredSize(new Dimension(440, 492));
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(28, 34, 26, 34));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false); top.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        top.add(label("●  服务运行正常", 11, Font.PLAIN, new Color(111, 229, 196)), BorderLayout.WEST);
        top.add(label("简体中文", 11, Font.PLAIN, new Color(151, 160, 190)), BorderLayout.EAST);
        card.add(top); card.add(Box.createVerticalStrut(25));

        JLabel title = label("登录 TokenPro", 31, Font.BOLD, TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT); title.setHorizontalAlignment(SwingConstants.LEFT); title.setPreferredSize(new Dimension(372, 42)); title.setMinimumSize(new Dimension(372, 42)); title.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42)); card.add(title); card.add(Box.createVerticalStrut(5));
        JLabel sub = label("进入你的 AI 模型控制中心", 13, Font.PLAIN, new Color(145, 156, 191));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT); card.add(sub); card.add(Box.createVerticalStrut(22));

        addField(card, "邮箱", email, "请输入邮箱");
        card.add(Box.createVerticalStrut(13));
        addField(card, "密码", password, "请输入密码");
        card.add(Box.createVerticalStrut(20));

        loginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        loginButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        loginButton.setPreferredSize(new Dimension(372, 50));
        loginButton.addActionListener(loginAction);
        card.add(loginButton); card.add(Box.createVerticalStrut(21));

        JLabel divider = label("────────  端到端安全连接  ────────", 10, Font.PLAIN, new Color(102, 112, 148));
        divider.setAlignmentX(Component.CENTER_ALIGNMENT); card.add(divider); card.add(Box.createVerticalStrut(14));
        feedback.setAlignmentX(Component.CENTER_ALIGNMENT); card.add(feedback);
        return card;
    }

    private void addField(JPanel card, String name, JTextField field, String tooltip) {
        JLabel label = label(name, 12, Font.BOLD, new Color(216, 222, 244));
        label.setAlignmentX(Component.LEFT_ALIGNMENT); card.add(label); card.add(Box.createVerticalStrut(7));
        field.setToolTipText(tooltip);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        field.setPreferredSize(new Dimension(372, 48));
        field.setFont(font(14, Font.PLAIN));
        field.setForeground(TEXT); field.setCaretColor(TEXT);
        field.setBackground(new Color(4, 8, 25));
        field.setBorder(new CompoundBorder(new LineBorder(new Color(66, 76, 117), 1, true), new EmptyBorder(0, 16, 0, 16)));
        field.setAlignmentX(Component.LEFT_ALIGNMENT); card.add(field);
    }

    void setLoading(boolean loading, String text) {
        loginButton.setEnabled(!loading);
        loginButton.setText(loading ? "正在登录…" : "登录 / 注册");
        feedback.setText(text == null || text.isBlank() ? "登录信息仅加密保存在当前设备" : text);
        feedback.setForeground(loading ? new Color(150, 168, 255) : new Color(145, 156, 191));
    }

    private static JLabel label(String text, int size, int style, Color color) {
        JLabel label = new JLabel(text); label.setFont(font(size, style)); label.setForeground(color); return label;
    }

    private static Font font(float size, int style) {
        return new Font(Platform.OS_KIND == Platform.OS.MAC ? "PingFang SC" : "SansSerif", style, Math.round(size));
    }

    private static BufferedImage image(String name) {
        try (InputStream input = CosmosLoginPanel.class.getResourceAsStream("/assets/" + name)) {
            return input == null ? null : ImageIO.read(input);
        } catch (Exception ignored) { return null; }
    }

    private static BufferedImage white(BufferedImage source) {
        if (source == null) return null;
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int alpha = source.getRGB(x, y) >>> 24;
            result.setRGB(x, y, (alpha << 24) | 0xFFFFFF);
        }
        return result;
    }

    static final class Backdrop extends JPanel {
        private final BufferedImage background = image("LoginCosmos-v2.png");
        Backdrop() { super(new BorderLayout()); setOpaque(true); setBackground(new Color(2, 5, 16)); }
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (background != null) {
                double scale = Math.max(getWidth() / (double) background.getWidth(), getHeight() / (double) background.getHeight());
                int width = (int) Math.ceil(background.getWidth() * scale), height = (int) Math.ceil(background.getHeight() * scale);
                g.drawImage(background, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
            }
            g.setPaint(new GradientPaint(0, 0, new Color(1, 4, 15, 45), getWidth(), 0, new Color(1, 3, 12, 175)));
            g.fillRect(0, 0, getWidth(), getHeight()); g.dispose();
        }
    }

    private static final class GlassPanel extends JPanel {
        GlassPanel() { setOpaque(false); }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D shape = new RoundRectangle2D.Double(.5, .5, getWidth() - 1, getHeight() - 1, 28, 28);
            g.setPaint(new GradientPaint(0, 0, new Color(20, 28, 61, 232), getWidth(), getHeight(), new Color(7, 11, 30, 225)));
            g.fill(shape); g.setColor(new Color(178, 194, 255, 42)); g.draw(shape); g.dispose(); super.paintComponent(graphics);
        }
    }

    private static final class GradientButton extends JButton {
        GradientButton(String text) { super(text); setFont(font(14, Font.BOLD)); setForeground(Color.WHITE); setFocusPainted(false); setBorderPainted(false); setContentAreaFilled(false); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, isEnabled() ? new Color(108, 92, 255) : new Color(72, 73, 122), getWidth(), 0, isEnabled() ? new Color(62, 155, 255) : new Color(73, 82, 126)));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16); g.dispose(); super.paintComponent(graphics);
        }
    }

    private static final class HeroPanel extends JPanel {
        HeroPanel() {
            super(new BorderLayout()); setOpaque(false);
            JPanel copy = new JPanel(); copy.setOpaque(false); copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            JLabel eyebrow = label("—  CROSS-PLATFORM AI ACCESS", 12, Font.BOLD, new Color(171, 187, 255)); eyebrow.setAlignmentX(Component.LEFT_ALIGNMENT); copy.add(eyebrow); copy.add(Box.createVerticalStrut(27));
            JLabel titleLine = label("连接每一颗", 52, Font.BOLD, TEXT); titleLine.setAlignmentX(Component.LEFT_ALIGNMENT); titleLine.setPreferredSize(new Dimension(620, 72)); titleLine.setMinimumSize(new Dimension(460, 72)); titleLine.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72)); copy.add(titleLine);
            JLabel titleSecondLine = label("AI 星辰", 52, Font.BOLD, TEXT); titleSecondLine.setAlignmentX(Component.LEFT_ALIGNMENT); titleSecondLine.setPreferredSize(new Dimension(620, 72)); titleSecondLine.setMinimumSize(new Dimension(460, 72)); titleSecondLine.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72)); copy.add(titleSecondLine); copy.add(Box.createVerticalStrut(18));
            JLabel lead = label("<html>连接主流与新兴 AI 模型，一个入口，跨平台启航。<br>模型宇宙实时同步，并持续扩展。</html>", 15, Font.PLAIN, MUTED); lead.setAlignmentX(Component.LEFT_ALIGNMENT); copy.add(lead);
            add(copy, BorderLayout.NORTH); add(new VortexCanvas(), BorderLayout.CENTER);
        }
    }

    private static final class VortexCanvas extends JComponent {
        private final BufferedImage vortex = image("ModelUniverseVortex.png");
        private final BufferedImage gpt = white(image("OpenAIBlossomRuntime.png"));
        private final BufferedImage claude = image("ClaudeSparkRuntime.png");
        private final BufferedImage gemini = image("GeminiSparkTransparent.png");
        private final BufferedImage grok = image("GrokMarkTransparent.png");
        private final BufferedImage unknown = white(image("UnknownModelRuntime.png"));
        private double phase;
        private double strip;

        VortexCanvas() {
            setOpaque(false);
            Timer timer = new Timer(40, e -> { phase += .035; strip = (strip + .7) % 500; repaint(); });
            timer.start();
        }

        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int available = Math.max(210, getHeight() - 112);
            int vw = Math.min(getWidth() - 8, 590), vh = Math.min(available, vw / 2);
            int vx = (getWidth() - vw) / 2, vy = Math.max(4, (available - vh) / 2);
            if (vortex != null) {
                g.setComposite(AlphaComposite.SrcOver.derive(.82f));
                g.drawImage(vortex, vx, vy, vw, vh, null); g.setComposite(AlphaComposite.SrcOver);
            }
            drawModel(g, gpt, vx + .22 * vw, vy + .56 * vh, 31, 0);
            drawModel(g, claude, vx + .41 * vw, vy + .34 * vh, 28, 1.2);
            drawModel(g, gemini, vx + .64 * vw, vy + .47 * vh, 25, 2.5);
            drawModel(g, grok, vx + .51 * vw, vy + .69 * vh, 28, 3.8);
            drawModel(g, unknown, vx + .76 * vw, vy + .34 * vh, 21, 5.1);

            int labelY = getHeight() - 101;
            g.setFont(font(10, Font.PLAIN)); g.setColor(new Color(184, 196, 230, 140));
            g.drawString("●  MODEL UNIVERSE  ·  持续扩展", 4, labelY);
            int barY = labelY + 15, barH = 72;
            g.setColor(new Color(8, 14, 35, 125)); g.fillRoundRect(0, barY, getWidth() - 4, barH, 18, 18);
            g.setColor(new Color(178, 195, 255, 35)); g.drawRoundRect(0, barY, getWidth() - 4, barH, 18, 18);
            List<ModelChip> chips = List.of(new ModelChip("GPT", gpt), new ModelChip("Claude", claude), new ModelChip("Gemini", gemini), new ModelChip("Grok", grok), new ModelChip("更多模型持续接入", null));
            int total = chips.stream().mapToInt(ModelChip::width).sum() + chips.size() * 10;
            int x = (int) -strip;
            while (x < getWidth()) { for (ModelChip chip : chips) { drawChip(g, chip, x, barY + 14); x += chip.width() + 10; } }
            if (total < 1) strip = 0;
            g.dispose();
        }

        private void drawModel(Graphics2D g, BufferedImage image, double x, double y, int size, double offset) {
            if (image == null) return;
            int driftX = (int) Math.round(Math.cos(phase + offset) * 2.2), driftY = (int) Math.round(Math.sin(phase + offset) * 3.2);
            g.setComposite(AlphaComposite.SrcOver.derive(offset > 5 ? .52f : .9f));
            g.drawImage(image, (int) x + driftX - size / 2, (int) y + driftY - size / 2, size, size, null);
            g.setComposite(AlphaComposite.SrcOver);
        }

        private void drawChip(Graphics2D g, ModelChip chip, int x, int y) {
            int width = chip.width();
            g.setColor(new Color(9, 15, 38, 210)); g.fillRoundRect(x, y, width, 44, 14, 14);
            g.setColor(new Color(188, 202, 255, 42)); g.drawRoundRect(x, y, width, 44, 14, 14);
            int textX = x + 14;
            if (chip.image() != null) { g.drawImage(chip.image(), x + 10, y + 10, 24, 24, null); textX = x + 42; }
            g.setFont(font(11, Font.BOLD)); g.setColor(new Color(238, 241, 255, 225)); g.drawString(chip.name(), textX, y + 27);
        }

        private record ModelChip(String name, BufferedImage image) {
            int width() { return image == null ? 148 : Math.max(86, 55 + name.length() * 9); }
        }
    }
}

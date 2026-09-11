package work.tokenpro.client;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;

final class CosmosLoginPanel extends JPanel {
    private static final Color TEXT = new Color(246, 248, 255);
    private static final Color MUTED = new Color(164, 175, 211);
    private final JButton loginButton = new GradientButton("登录 / 注册");
    private final UpdateButton updateButton = new UpdateButton("检查更新");
    private final JLabel feedback = label("登录信息仅加密保存在当前设备", 11, Font.PLAIN, new Color(145, 156, 191));

    CosmosLoginPanel(JTextField email, JPasswordField password, ActionListener loginAction, ActionListener updateAction) {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(new EmptyBorder(32, 44, 34, 44));

        updateButton.setPreferredSize(new Dimension(178, 34));
        updateButton.addActionListener(updateAction);
        JPanel pageActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        pageActions.setOpaque(false);
        pageActions.add(updateButton);
        add(pageActions, BorderLayout.NORTH);

        JPanel stage = new JPanel(new GridBagLayout());
        stage.setOpaque(false);

        GridBagConstraints hero = new GridBagConstraints();
        hero.gridx = 0; hero.gridy = 0; hero.weightx = .58; hero.weighty = 1;
        hero.fill = GridBagConstraints.BOTH; hero.insets = new Insets(0, 0, 0, 26);
        stage.add(new HeroPanel(), hero);

        GridBagConstraints login = new GridBagConstraints();
        login.gridx = 1; login.gridy = 0; login.weightx = .42; login.weighty = 1;
        login.fill = GridBagConstraints.BOTH;
        JPanel loginWell = new JPanel(new GridBagLayout());
        loginWell.setOpaque(false);
        loginWell.add(loginCard(email, password, loginAction));
        stage.add(loginWell, login);
        add(stage, BorderLayout.CENTER);
    }

    private JComponent loginCard(JTextField email, JPasswordField password, ActionListener loginAction) {
        GlassPanel card = new GlassPanel();
        card.setPreferredSize(new Dimension(400, 404));
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(20, 24, 20, 24));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false); top.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(label("●  服务运行正常", 11, Font.PLAIN, new Color(111, 229, 196)), BorderLayout.WEST);
        top.add(label("简体中文", 11, Font.PLAIN, new Color(151, 160, 190)), BorderLayout.EAST);
        card.add(top); card.add(Box.createVerticalStrut(16));

        JLabel title = label("登录 TokenPro", 27, Font.BOLD, TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT); title.setHorizontalAlignment(SwingConstants.LEFT); title.setPreferredSize(new Dimension(352, 36)); title.setMinimumSize(new Dimension(352, 36)); title.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36)); card.add(title); card.add(Box.createVerticalStrut(2));
        JLabel sub = label("进入你的 AI 模型控制中心", 12, Font.PLAIN, new Color(145, 156, 191));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT); sub.setMaximumSize(new Dimension(Integer.MAX_VALUE, sub.getPreferredSize().height)); card.add(sub); card.add(Box.createVerticalStrut(14));

        addField(card, "邮箱", email, "请输入邮箱");
        card.add(Box.createVerticalStrut(8));
        addField(card, "密码", password, "请输入密码");
        card.add(Box.createVerticalStrut(14));

        loginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        loginButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        loginButton.setPreferredSize(new Dimension(352, 44));
        loginButton.addActionListener(loginAction);
        email.addActionListener(loginAction);
        password.addActionListener(loginAction);
        card.add(loginButton); card.add(Box.createVerticalStrut(14));

        JLabel divider = label("────────  端到端安全连接  ────────", 10, Font.PLAIN, new Color(102, 112, 148));
        divider.setAlignmentX(Component.LEFT_ALIGNMENT); divider.setHorizontalAlignment(SwingConstants.CENTER); divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, divider.getPreferredSize().height)); card.add(divider); card.add(Box.createVerticalStrut(10));
        feedback.setAlignmentX(Component.LEFT_ALIGNMENT); feedback.setHorizontalAlignment(SwingConstants.CENTER); feedback.setMaximumSize(new Dimension(Integer.MAX_VALUE, feedback.getPreferredSize().height)); card.add(feedback);
        return card;
    }

    private void addField(JPanel card, String name, JTextField field, String tooltip) {
        JLabel label = label(name, 12, Font.BOLD, new Color(216, 222, 244));
        label.setAlignmentX(Component.LEFT_ALIGNMENT); card.add(label); card.add(Box.createVerticalStrut(5));
        field.setToolTipText(tooltip);
        field.setFont(font(14, Font.PLAIN));
        field.setForeground(TEXT); field.setCaretColor(TEXT);
        field.setSelectionColor(new Color(4, 8, 25));
        field.setSelectedTextColor(TEXT);
        field.setOpaque(false);
        field.setBorder(new EmptyBorder(0, 14, 0, 14));
        RoundedInput input = new RoundedInput(field);
        input.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        input.setPreferredSize(new Dimension(352, 40));
        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(input);
    }

    void setLoading(boolean loading, String text) {
        loginButton.setEnabled(!loading);
        loginButton.setText(loading ? "正在登录…" : "登录 / 注册");
        feedback.setText(text == null || text.isBlank() ? "登录信息仅加密保存在当前设备" : text);
        feedback.setForeground(loading ? new Color(150, 168, 255) : new Color(145, 156, 191));
    }

    void setUpdateState(String text, boolean enabled) {
        updateButton.setText(text);
        updateButton.setEnabled(enabled);
        updateButton.setCursor(enabled ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        if (!text.startsWith("正在更新")) updateButton.setProgress(-1);
    }

    void setUpdateProgress(int progress) { updateButton.setProgress(progress); }

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
            RoundRectangle2D shape = new RoundRectangle2D.Double(.5, .5, getWidth() - 1, getHeight() - 1, 24, 24);
            g.setPaint(new GradientPaint(0, 0, new Color(18, 26, 58, 236), getWidth(), getHeight(), new Color(6, 10, 27, 220)));
            g.fill(shape); g.setColor(new Color(178, 194, 255, 42)); g.draw(shape); g.dispose(); super.paintComponent(graphics);
        }
    }

    private static final class RoundedInput extends JPanel {
        RoundedInput(JTextField field) {
            super(new BorderLayout());
            setOpaque(false);
            add(field, BorderLayout.CENTER);
        }

        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(4, 8, 25, 225));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g.setColor(new Color(72, 84, 132));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class GradientButton extends JButton {
        GradientButton(String text) { super(text); setFont(font(14, Font.BOLD)); setForeground(Color.WHITE); setFocusPainted(false); setBorderPainted(false); setContentAreaFilled(false); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, isEnabled() ? new Color(108, 92, 255) : new Color(72, 73, 122), getWidth(), 0, isEnabled() ? new Color(62, 155, 255) : new Color(73, 82, 126)));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14); g.dispose(); super.paintComponent(graphics);
        }
    }

    private static final class UpdateButton extends JButton {
        private int progress = -1;

        UpdateButton(String text) {
            super(text);
            setFont(font(11, Font.BOLD));
            setForeground(new Color(226, 233, 255));
            setFocusPainted(false); setBorderPainted(false); setContentAreaFilled(false); setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        void setProgress(int value) {
            progress = value < 0 ? -1 : Math.min(100, value);
            repaint();
        }

        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth() - 1, height = getHeight() - 1;
            g.setColor(new Color(21, 34, 72, 225));
            g.fillRoundRect(0, 0, width, height, 14, 14);
            if (progress >= 0) {
                Shape oldClip = g.getClip();
                g.clip(new RoundRectangle2D.Double(0, 0, width, height, 14, 14));
                int filled = (int) Math.round(width * progress / 100.0);
                g.setPaint(new GradientPaint(0, 0, new Color(91, 101, 255, 220), width, 0, new Color(44, 178, 213, 220)));
                g.fillRect(0, 0, filled, height);
                g.setClip(oldClip);
            }
            g.setColor(new Color(147, 172, 255, 125));
            g.drawRoundRect(0, 0, width, height, 14, 14);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class HeroPanel extends JPanel {
        HeroPanel() {
            super(new BorderLayout()); setOpaque(false);
            JPanel copy = new JPanel(); copy.setOpaque(false); copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            JLabel eyebrow = label("—  CROSS-PLATFORM AI ACCESS", 12, Font.BOLD, new Color(171, 187, 255)); eyebrow.setAlignmentX(Component.LEFT_ALIGNMENT); copy.add(eyebrow); copy.add(Box.createVerticalStrut(27));
            JComponent titleLine = new StarlightTitle("TokenPro 连接每一颗 AI 星辰"); titleLine.setAlignmentX(Component.LEFT_ALIGNMENT); titleLine.setPreferredSize(new Dimension(650, 72)); titleLine.setMinimumSize(new Dimension(500, 72)); titleLine.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72)); copy.add(titleLine); copy.add(Box.createVerticalStrut(14));
            JLabel lead = label("<html>连接主流与新兴 AI 模型，一个入口，跨平台启航。<br>模型宇宙实时同步，并持续扩展。</html>", 15, Font.PLAIN, MUTED); lead.setAlignmentX(Component.LEFT_ALIGNMENT); copy.add(lead);
            add(copy, BorderLayout.NORTH); add(new VortexCanvas(), BorderLayout.CENTER);
        }
    }

    private static final class StarlightTitle extends JComponent {
        private final String text;
        StarlightTitle(String text) { this.text = text; setOpaque(false); }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            Font titleFont = font(44, Font.BOLD);
            TextLayout layout = new TextLayout(text, titleFont, g.getFontRenderContext());
            while (layout.getAdvance() > getWidth() - 4 && titleFont.getSize2D() > 32f) {
                titleFont = titleFont.deriveFont(titleFont.getSize2D() - 1f);
                layout = new TextLayout(text, titleFont, g.getFontRenderContext());
            }
            Rectangle bounds = layout.getPixelBounds(g.getFontRenderContext(), 0, 0);
            float baseline = (getHeight() - bounds.height) / 2f - bounds.y;
            Shape glyphs = layout.getOutline(AffineTransform.getTranslateInstance(1, baseline));

            g.translate(0, 2);
            g.setColor(new Color(54, 39, 145, 105));
            g.fill(glyphs);
            g.translate(0, -2);
            g.setPaint(new LinearGradientPaint(0, 0, Math.max(1, getWidth()), 0,
                new float[]{0f, .28f, .58f, .80f, 1f},
                new Color[]{new Color(112, 225, 255), new Color(141, 117, 255),
                    new Color(245, 248, 255), new Color(185, 157, 255), new Color(91, 181, 255)}));
            g.fill(glyphs);

            g.setClip(glyphs);
            for (int index = 0; index < 28; index++) {
                int x = Math.floorMod(index * 79 + 17, Math.max(1, getWidth()));
                int y = Math.floorMod(index * 37 + 11, Math.max(1, getHeight()));
                int size = index % 6 == 0 ? 2 : 1;
                g.setColor(new Color(255, 255, 255, index % 6 == 0 ? 215 : 115));
                g.fillOval(x, y, size, size);
            }
            g.dispose();
        }
    }

    private static final class VortexCanvas extends JComponent {
        private final BufferedImage cosmos = image("LoginCosmos-v2.png");
        private final BufferedImage vortex = image("ModelUniverseVortex.png");
        private final BufferedImage gpt = orbitIcon(white(image("OpenAIBlossomRuntime.png")));
        private final BufferedImage claude = orbitIcon(image("ClaudeSparkRuntime.png"));
        private final BufferedImage gemini = orbitIcon(image("GeminiSparkTransparent.png"));
        private final BufferedImage grok = orbitIcon(image("GrokMarkTransparent.png"));
        private final BufferedImage unknown = orbitIcon(white(image("UnknownModelRuntime.png")));
        private double phase;
        private double strip;
        private long lastTick = System.nanoTime();
        private BufferedImage cachedScene;
        private String cachedSceneKey = "";

        VortexCanvas() {
            setOpaque(true);
            setDoubleBuffered(true);
            Timer timer = new Timer(16, e -> {
                long now = System.nanoTime();
                if (!isShowing()) { lastTick = now; return; }
                double elapsed = Math.min((now - lastTick) / 1_000_000_000.0, .25);
                lastTick = now;
                phase = (phase + elapsed * .15) % (Math.PI * 2);
                strip += elapsed * 24;
                repaint();
            });
            timer.setCoalesce(true);
            timer.setInitialDelay(0);
            timer.start();
        }

        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int available = Math.max(210, getHeight() - 112);
            int vw = Math.min(getWidth() - 8, 590), vh = Math.min(available, vw / 2);
            int vx = (getWidth() - vw) / 2, vy = Math.max(4, (available - vh) / 2);
            BufferedImage background = sceneFrame(vx, vy, vw, vh);
            if (background != null) g.drawImage(background, 0, 0, null);
            else { g.setColor(new Color(2, 5, 16)); g.fillRect(0, 0, getWidth(), getHeight()); }
            double centerX = vx + .50 * vw, centerY = vy + .51 * vh;
            double radiusX = .31 * vw, radiusY = .29 * vh;
            BufferedImage[] models = {gpt, claude, gemini, grok, unknown};
            int[] sizes = {34, 31, 29, 31, 25};
            for (int index = 0; index < models.length; index++) {
                double angle = phase + index * Math.PI * 2 / models.length;
                drawModel(g, models[index], centerX + Math.cos(angle) * radiusX,
                    centerY + Math.sin(angle) * radiusY, sizes[index], .94f);
            }

            int labelY = getHeight() - 101;
            g.setFont(font(10, Font.PLAIN)); g.setColor(new Color(184, 196, 230, 140));
            g.drawString("●  MODEL UNIVERSE  ·  持续扩展", 4, labelY);
            int barY = labelY + 15, barH = 72;
            g.setColor(new Color(8, 14, 35, 125)); g.fillRoundRect(0, barY, getWidth() - 4, barH, 18, 18);
            g.setColor(new Color(178, 195, 255, 35)); g.drawRoundRect(0, barY, getWidth() - 4, barH, 18, 18);
            List<ModelChip> chips = List.of(new ModelChip("GPT", gpt), new ModelChip("Claude", claude), new ModelChip("Gemini", gemini), new ModelChip("Grok", grok), new ModelChip("更多模型持续接入", null));
            int total = chips.stream().mapToInt(ModelChip::width).sum() + chips.size() * 10;
            double x = -(strip % total);
            while (x < getWidth()) { for (ModelChip chip : chips) { drawChip(g, chip, x, barY + 14); x += chip.width() + 10; } }
            g.dispose();
        }

        private BufferedImage sceneFrame(int vortexX, int vortexY, int vortexWidth, int vortexHeight) {
            Backdrop backdrop = (Backdrop) SwingUtilities.getAncestorOfClass(Backdrop.class, this);
            if (backdrop == null || getWidth() <= 0 || getHeight() <= 0) return null;
            Point location = SwingUtilities.convertPoint(this, 0, 0, backdrop);
            String key = getWidth() + ":" + getHeight() + ":" + backdrop.getWidth() + ":" + backdrop.getHeight()
                + ":" + location.x + ":" + location.y + ":" + vortexX + ":" + vortexY + ":" + vortexWidth + ":" + vortexHeight;
            if (cachedScene != null && cachedSceneKey.equals(key)) return cachedScene;
            BufferedImage output = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = output.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(new Color(2, 5, 16));
            g.fillRect(0, 0, output.getWidth(), output.getHeight());
            if (cosmos != null) {
                double scale = Math.max(backdrop.getWidth() / (double) cosmos.getWidth(), backdrop.getHeight() / (double) cosmos.getHeight());
                int width = (int) Math.ceil(cosmos.getWidth() * scale), height = (int) Math.ceil(cosmos.getHeight() * scale);
                int x = (backdrop.getWidth() - width) / 2 - location.x, y = (backdrop.getHeight() - height) / 2 - location.y;
                g.drawImage(cosmos, x, y, width, height, null);
            }
            g.setPaint(new GradientPaint(-location.x, 0, new Color(1, 4, 15, 45), backdrop.getWidth() - location.x, 0, new Color(1, 3, 12, 175)));
            g.fillRect(0, 0, output.getWidth(), output.getHeight());
            if (vortex != null) {
                g.setComposite(AlphaComposite.SrcOver.derive(.82f));
                g.drawImage(vortex, vortexX, vortexY, vortexWidth, vortexHeight, null);
            }
            g.dispose();
            cachedScene = output;
            cachedSceneKey = key;
            return output;
        }

        private static BufferedImage orbitIcon(BufferedImage source) {
            if (source == null) return null;
            int size = 96;
            BufferedImage output = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = output.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, size, size, null);
            g.dispose();
            return output;
        }

        private void drawModel(Graphics2D g, BufferedImage image, double x, double y, double size, float opacity) {
            if (image == null) return;
            double scale = size / Math.max(image.getWidth(), image.getHeight());
            AffineTransform transform = new AffineTransform();
            transform.translate(x - image.getWidth() * scale / 2, y - image.getHeight() * scale / 2);
            transform.scale(scale, scale);
            g.setComposite(AlphaComposite.SrcOver.derive(opacity));
            g.drawImage(image, transform, null);
            g.setComposite(AlphaComposite.SrcOver);
        }

        private void drawChip(Graphics2D g, ModelChip chip, double x, int y) {
            Graphics2D chipGraphics = (Graphics2D) g.create();
            chipGraphics.translate(x, y);
            int width = chip.width();
            chipGraphics.setColor(new Color(9, 15, 38, 210)); chipGraphics.fillRoundRect(0, 0, width, 44, 14, 14);
            chipGraphics.setColor(new Color(188, 202, 255, 42)); chipGraphics.drawRoundRect(0, 0, width, 44, 14, 14);
            int textX = 14;
            if (chip.image() != null) { chipGraphics.drawImage(chip.image(), 10, 10, 24, 24, null); textX = 42; }
            chipGraphics.setFont(font(11, Font.BOLD)); chipGraphics.setColor(new Color(238, 241, 255, 225)); chipGraphics.drawString(chip.name(), textX, 27);
            chipGraphics.dispose();
        }

        private record ModelChip(String name, BufferedImage image) {
            int width() { return image == null ? 148 : Math.max(86, 55 + name.length() * 9); }
        }
    }
}

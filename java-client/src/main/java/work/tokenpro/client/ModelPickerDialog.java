package work.tokenpro.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

/** Model selection stays on the dashboard and uses ordinary click-to-toggle controls. */
final class ModelPickerDialog extends JDialog {
    private static final Color TEXT = new Color(242, 245, 255);
    private static final Color MUTED = new Color(145, 154, 185);
    private static final Color PANEL = new Color(8, 14, 35);
    private final List<ModelCheckBox> choices = new ArrayList<>();
    private final List<ModelCheckBox> imageChoices = new ArrayList<>();

    ModelPickerDialog(JFrame owner, String client, List<PricedModel> models,
                      Set<String> selectedIds, Consumer<List<PricedModel>> onApply) {
        super(owner, "选择 " + client + " 模型", true);
        setUndecorated(true);
        setBackground(new Color(7, 11, 29));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(content(client, models, selectedIds, onApply));
        setSize(680, 650);
        setMinimumSize(new Dimension(560, 480));
        applyShape();
        addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent event) { applyShape(); }
        });
        getRootPane().registerKeyboardAction(event -> dispose(),
            KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setLocationRelativeTo(owner);
    }

    private void applyShape() {
        try { setShape(new java.awt.geom.RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 24, 24)); }
        catch (UnsupportedOperationException ignored) {}
    }

    private JComponent content(String client, List<PricedModel> models, Set<String> selectedIds,
                               Consumer<List<PricedModel>> onApply) {
        JPanel root = new CosmosPanel();
        root.setLayout(new BorderLayout());
        root.setBorder(new EmptyBorder(24, 26, 22, 26));

        JPanel header = transparent();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("选择 " + client + " 模型");
        title.setFont(font(23, Font.BOLD));
        title.setForeground(TEXT);
        JLabel detail = new JLabel("按可用分组展示，可直接点选多个模型");
        detail.setFont(font(12, Font.PLAIN));
        detail.setForeground(MUTED);
        header.add(title);
        header.add(Box.createVerticalStrut(6));
        header.add(detail);
        header.add(Box.createVerticalStrut(18));
        root.add(header, BorderLayout.NORTH);

        JPanel groups = transparent();
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        boolean codex = "Codex".equals(client);
        Map<String, List<PricedModel>> grouped = new LinkedHashMap<>();
        for (PricedModel model : models) {
            if (codex && model.isImageGeneration()) continue;
            grouped.computeIfAbsent(groupKey(model), ignored -> new ArrayList<>()).add(model);
        }
        for (List<PricedModel> groupModels : grouped.values()) {
            PricedModel first = groupModels.getFirst();
            JPanel group = new GroupPanel();
            group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
            group.setBorder(new EmptyBorder(14, 16, 12, 16));
            group.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel groupName = new JLabel(first.displayGroupName());
            groupName.setFont(font(13, Font.BOLD));
            groupName.setForeground(new Color(172, 183, 255));
            groupName.setAlignmentX(Component.LEFT_ALIGNMENT);
            group.add(groupName);
            group.add(Box.createVerticalStrut(8));
            for (PricedModel model : groupModels) {
                ModelCheckBox choice = new ModelCheckBox(model);
                choice.setSelected(selectedIds.contains(id(model)));
                choices.add(choice);
                group.add(choice);
            }
            group.setMaximumSize(new Dimension(Integer.MAX_VALUE, group.getPreferredSize().height));
            groups.add(group);
            groups.add(Box.createVerticalStrut(10));
        }
        if (codex) {
            List<PricedModel> imageModels = models.stream().filter(PricedModel::isImageGeneration).toList();
            if (!imageModels.isEmpty()) {
                JPanel imageGroup = new GroupPanel();
                imageGroup.setLayout(new BoxLayout(imageGroup, BoxLayout.Y_AXIS));
                imageGroup.setBorder(new EmptyBorder(14, 16, 12, 16));
                imageGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
                JLabel imageTitle = new JLabel("生图模型（可选一个）");
                imageTitle.setFont(font(13, Font.BOLD));
                imageTitle.setForeground(new Color(105, 220, 194));
                imageTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
                JLabel imageHint = new JLabel("画图时自动使用，无需在 Codex 中来回切换模型");
                imageHint.setFont(font(11, Font.PLAIN));
                imageHint.setForeground(MUTED);
                imageHint.setAlignmentX(Component.LEFT_ALIGNMENT);
                imageGroup.add(imageTitle);
                imageGroup.add(Box.createVerticalStrut(4));
                imageGroup.add(imageHint);
                imageGroup.add(Box.createVerticalStrut(8));
                boolean restored = false;
                for (PricedModel model : imageModels) {
                    ModelCheckBox choice = new ModelCheckBox(model);
                    boolean selected = !restored && selectedIds.contains(id(model));
                    choice.setSelected(selected);
                    restored |= selected;
                    imageChoices.add(choice);
                    choices.add(choice);
                    choice.addActionListener(event -> {
                        if (!choice.isSelected()) return;
                        for (ModelCheckBox other : imageChoices) if (other != choice) other.setSelected(false);
                    });
                    imageGroup.add(choice);
                }
                imageGroup.setMaximumSize(new Dimension(Integer.MAX_VALUE, imageGroup.getPreferredSize().height));
                groups.add(imageGroup);
                groups.add(Box.createVerticalStrut(10));
            }
        }
        if (models.isEmpty()) {
            JLabel empty = new JLabel("当前账户没有可用模型");
            empty.setForeground(MUTED);
            groups.add(empty);
        }
        JScrollPane scroll = new JScrollPane(groups);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        root.add(scroll, BorderLayout.CENTER);

        JPanel footer = transparent(new BorderLayout());
        footer.setBorder(new EmptyBorder(17, 0, 0, 0));
        JLabel count = new JLabel();
        count.setFont(font(12, Font.BOLD));
        count.setForeground(new Color(105, 220, 194));
        Runnable updateCount = () -> {
            long imageCount = selected().stream().filter(PricedModel::isImageGeneration).count();
            long chatCount = selected().size() - imageCount;
            count.setText(codex ? "已选 " + chatCount + " 个对话模型" + (imageCount > 0 ? " · 1 个生图模型" : "") : "已选择 " + selected().size() + " 个模型");
        };
        choices.forEach(choice -> choice.addActionListener(event -> updateCount.run()));
        updateCount.run();
        footer.add(count, BorderLayout.WEST);
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 9, 0));
        JButton cancel = button("取消", false);
        cancel.addActionListener(event -> dispose());
        JButton apply = button("应用并打开 " + client, true);
        apply.addActionListener(event -> {
            List<PricedModel> selected = selected();
            if (selected.isEmpty() || codex && selected.stream().noneMatch(model -> !model.isImageGeneration())) {
                JOptionPane.showMessageDialog(this, codex ? "请至少选择一个可对话模型" : "请至少选择一个模型", "TokenPro", JOptionPane.WARNING_MESSAGE);
                return;
            }
            dispose();
            onApply.accept(selected);
        });
        apply.setEnabled(!models.isEmpty());
        actions.add(cancel);
        actions.add(apply);
        footer.add(actions, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private List<PricedModel> selected() {
        return choices.stream().filter(AbstractButton::isSelected).map(ModelCheckBox::model).toList();
    }

    static String id(PricedModel model) { return model.groupId() + "\u0000" + model.name(); }
    private static String groupKey(PricedModel model) { return model.groupId() + "\u0000" + model.groupName(); }

    private static JPanel transparent() { JPanel panel = new JPanel(); panel.setOpaque(false); return panel; }
    private static JPanel transparent(LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setOpaque(false); return panel; }
    private static Font font(float size, int style) { return new Font(Platform.OS_KIND == Platform.OS.MAC ? ".AppleSystemUIFont" : "SansSerif", style, Math.round(size)); }

    private static JButton button(String text, boolean primary) {
        JButton button = new PickerButton(text, primary);
        button.setFont(font(13, Font.BOLD));
        button.setForeground(Color.WHITE);
        button.setBorder(new EmptyBorder(11, 18, 11, 18));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static final class ModelCheckBox extends JCheckBox {
        private final PricedModel model;
        private boolean hovered;
        ModelCheckBox(PricedModel model) {
            super("");
            this.model = model;
            setFont(font(13, Font.PLAIN));
            setForeground(TEXT);
            setOpaque(false);
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setPreferredSize(new Dimension(520, 44));
            setMinimumSize(new Dimension(240, 44));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent event) { hovered = true; repaint(); }
                public void mouseExited(MouseEvent event) { hovered = false; repaint(); }
            });
        }
        PricedModel model() { return model; }

        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth() - 1, height = getHeight() - 2;
            if (isSelected()) {
                g.setPaint(new GradientPaint(0, 0, new Color(91, 73, 220, 155), width, 0, new Color(44, 130, 229, 105)));
            } else {
                g.setColor(hovered ? new Color(35, 47, 91, 175) : new Color(15, 24, 55, 150));
            }
            g.fillRoundRect(0, 1, width, height, 14, 14);
            g.setColor(isSelected() ? new Color(152, 178, 255, 180) : new Color(151, 169, 226, hovered ? 92 : 42));
            g.drawRoundRect(0, 1, width, height, 14, 14);

            int cx = 19, cy = getHeight() / 2;
            if (isSelected()) {
                g.setPaint(new GradientPaint(cx - 8, cy - 8, new Color(138, 102, 255), cx + 8, cy + 8, new Color(69, 178, 255)));
                g.fillOval(cx - 8, cy - 8, 16, 16);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(cx - 4, cy, cx - 1, cy + 3);
                g.drawLine(cx - 1, cy + 3, cx + 5, cy - 4);
            } else {
                g.setColor(new Color(181, 194, 235, 105));
                g.setStroke(new BasicStroke(1.4f));
                g.drawOval(cx - 8, cy - 8, 16, 16);
            }

            g.setFont(getFont());
            g.setColor(isSelected() ? Color.WHITE : hovered ? new Color(232, 237, 255) : TEXT);
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(model.displayName(), 38, (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
            if (model.isImageGeneration()) {
                String tag = "生图";
                g.setFont(font(10, Font.BOLD));
                FontMetrics tagMetrics = g.getFontMetrics();
                int tagWidth = tagMetrics.stringWidth(tag) + 16;
                int tagX = getWidth() - tagWidth - 13;
                g.setColor(new Color(65, 214, 190, isSelected() ? 56 : 28));
                g.fillRoundRect(tagX, cy - 10, tagWidth, 20, 10, 10);
                g.setColor(new Color(112, 235, 211));
                g.drawString(tag, tagX + 8, cy + (tagMetrics.getAscent() - tagMetrics.getDescent()) / 2);
            }
            g.dispose();
        }
    }

    private static final class CosmosPanel extends JPanel {
        CosmosPanel() { setOpaque(true); setBackground(new Color(7, 11, 29)); }
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setPaint(new GradientPaint(0, 0, new Color(12, 20, 51), getWidth(), getHeight(), new Color(9, 8, 35)));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setPaint(new RadialGradientPaint(getWidth() * .78f, getHeight() * .08f, Math.max(180, getWidth() * .52f),
                new float[]{0f, 1f}, new Color[]{new Color(82, 66, 220, 72), new Color(20, 15, 67, 0)}));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(new Color(198, 218, 255, 105));
            for (int i = 0; i < 28; i++) {
                int x = Math.floorMod(i * 83 + 31, Math.max(1, getWidth()));
                int y = Math.floorMod(i * 47 + 19, Math.max(1, getHeight()));
                int size = i % 7 == 0 ? 2 : 1;
                g.fillOval(x, y, size, size);
            }
            g.setColor(new Color(184, 199, 255, 45));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 24, 24);
            g.dispose();
        }
    }

    private static final class PickerButton extends JButton {
        private final boolean primary;
        PickerButton(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
        }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (primary && isEnabled()) g.setPaint(new GradientPaint(0, 0, new Color(111, 91, 255), getWidth(), 0, new Color(64, 142, 255)));
            else g.setColor(new Color(25, 33, 66));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            g.setColor(new Color(190, 205, 255, 32));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class GroupPanel extends JPanel {
        GroupPanel() { setOpaque(false); }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(13, 23, 54, 196));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
            g.setColor(new Color(187, 201, 255, 54));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            g.dispose();
            super.paintComponent(graphics);
        }
    }
}

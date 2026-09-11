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
    private static final Color MUTED = new Color(180, 190, 220);
    private final List<ModelCheckBox> choices = new ArrayList<>();

    ModelPickerDialog(JFrame owner, String client, List<PricedModel> models,
                      Set<String> selectedIds, Consumer<List<PricedModel>> onApply) {
        super(owner, "选择 " + client + " 模型", true);
        setUndecorated(true);
        setBackground(new Color(15, 26, 58));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(content(client, models, selectedIds, onApply));
        setSize(760, 680);
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
        models = orderedModels(models, client);
        JPanel root = new CosmosPanel();
        root.setLayout(new BorderLayout());
        root.setBorder(new EmptyBorder(24, 26, 22, 26));

        JPanel header = transparent();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JPanel titleLine = transparent(new BorderLayout());
        JLabel title = new JLabel("选择 " + client + " 模型");
        title.setFont(font(23, Font.BOLD));
        title.setForeground(TEXT);
        titleLine.add(title, BorderLayout.WEST);
        titleLine.add(closeControl(), BorderLayout.EAST);
        boolean codex = "Codex".equals(client);
        JLabel detail = new JLabel(codex ? "全局至少选择 1 个模型；生图模型可独立直接生图" : "LLM Model 至少选择 1 个，可同时选择多个");
        detail.setFont(font(12, Font.PLAIN));
        detail.setForeground(MUTED);
        header.add(titleLine);
        header.add(Box.createVerticalStrut(6));
        header.add(detail);
        header.add(Box.createVerticalStrut(18));
        root.add(header, BorderLayout.NORTH);

        JPanel groups = transparent();
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        Map<String, List<PricedModel>> grouped = new LinkedHashMap<>();
        for (PricedModel model : models) {
            if (!supportsClient(model, client) || model.isImageGeneration()) continue;
            grouped.computeIfAbsent(groupKey(model), ignored -> new ArrayList<>()).add(model);
        }
        List<List<PricedModel>> orderedGroups = grouped.values().stream()
            .sorted((left, right) -> compareGroups(left, right, client))
            .toList();
        boolean imageGroupAdded = false;
        for (List<PricedModel> groupModels : orderedGroups) {
            groupModels.sort(ApiClient::compareSelectablePriceDescending);
            PricedModel first = groupModels.getFirst();
            boolean subscription = first.subscription();
            JPanel group = new GroupPanel(subscription);
            group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
            group.setBorder(new EmptyBorder(14, 16, 12, 16));
            group.setAlignmentX(Component.LEFT_ALIGNMENT);
            String groupLabel = first.displayGroupName();
            if (subscription) groupLabel += "   订阅余额 $" + String.format(Locale.US, "%.2f", first.subscriptionRemaining()) + "   " + first.subscriptionExpiryLabel();
            JLabel groupName = new JLabel(groupLabel);
            groupName.setFont(font(13, Font.BOLD));
            groupName.setForeground(subscription ? new Color(218, 181, 92) : new Color(105, 220, 194));
            JPanel heading = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
            heading.setAlignmentX(Component.LEFT_ALIGNMENT);
            heading.add(new BillingBadge(subscription));
            heading.add(groupName);
            group.add(heading);
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
            // The image models remain their own independent group. Their visual
            // position is immediately below the first GPT/OpenAI group only.
            if (codex && !imageGroupAdded && !first.subscription() && isGptGroup(first)) {
                addImageChoices(groups, models, selectedIds);
                imageGroupAdded = true;
            }
        }
        if (codex && !imageGroupAdded) addImageChoices(groups, models, selectedIds);
        if (!codex && choices.stream().noneMatch(AbstractButton::isSelected)) {
            choices.stream().findFirst().ifPresent(choice -> choice.setSelected(true));
        }
        if (codex && choices.stream().noneMatch(AbstractButton::isSelected)) {
            choices.stream().findFirst().ifPresent(choice -> choice.setSelected(true));
        }
        if (choices.isEmpty()) {
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
            count.setText(codex ? "主模型已选 " + chatCount + " 个   生图模型已选 " + imageCount + " 个" : "已选择 " + selected().size() + " 个模型");
        };
        choices.forEach(choice -> choice.addActionListener(event -> updateCount.run()));
        updateCount.run();
        footer.add(count, BorderLayout.WEST);
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 9, 0));
        JButton cancel = button("取消", false);
        cancel.addActionListener(event -> dispose());
        JButton apply = button("应用模型", true);
        apply.addActionListener(event -> {
            List<PricedModel> selected = selected();
            long imageCount = selected.stream().filter(PricedModel::isImageGeneration).count();
            if (selected.isEmpty()) {
                JOptionPane.showMessageDialog(this, codex ? "请全局至少选择 1 个模型；生图模型可自由多选" : "请至少选择一个模型", "TokenPro", JOptionPane.WARNING_MESSAGE);
                return;
            }
            dispose();
            onApply.accept(selected);
        });
        apply.setEnabled(!choices.isEmpty());
        actions.add(cancel);
        actions.add(apply);
        footer.add(actions, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private JComponent closeControl() {
        JPanel controls = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        WindowControlButton close = new WindowControlButton(new Color(255, 95, 86));
        close.addActionListener(event -> dispose());
        controls.add(close);
        return controls;
    }

    private void addImageChoices(JPanel groups, List<PricedModel> models, Set<String> selectedIds) {
        List<PricedModel> imageModels = models.stream()
            .filter(PricedModel::isImageGeneration)
            .sorted(ApiClient::compareSelectablePriceDescending)
            .toList();
        if (imageModels.isEmpty()) return;

        Map<String, List<PricedModel>> grouped = new LinkedHashMap<>();
        for (PricedModel model : imageModels) {
            grouped.computeIfAbsent(groupKey(model), ignored -> new ArrayList<>()).add(model);
        }
        for (List<PricedModel> groupModels : grouped.values()) {
            groupModels.sort(ApiClient::compareSelectablePriceDescending);
            boolean subscription = groupModels.getFirst().subscription();
            JPanel imageGroup = new GroupPanel(subscription);
            imageGroup.setLayout(new BoxLayout(imageGroup, BoxLayout.Y_AXIS));
            imageGroup.setBorder(new EmptyBorder(14, 16, 12, 16));
            imageGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel imageTitle = new JLabel(groupModels.getFirst().displayGroupName());
            imageTitle.setFont(font(13, Font.BOLD));
            imageTitle.setForeground(subscription ? new Color(218, 181, 92) : new Color(105, 220, 194));
            JPanel imageHeading = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
            imageHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
            imageHeading.add(new BillingBadge(subscription));
            imageHeading.add(imageTitle);
            imageGroup.add(imageHeading);
            imageGroup.add(Box.createVerticalStrut(8));
            for (PricedModel model : groupModels) {
                ModelCheckBox choice = new ModelCheckBox(model);
                choice.setSelected(matchesSelectedImage(model, selectedIds));
                choices.add(choice);
                imageGroup.add(choice);
            }
            imageGroup.setMaximumSize(new Dimension(Integer.MAX_VALUE, imageGroup.getPreferredSize().height));
            groups.add(imageGroup);
            groups.add(Box.createVerticalStrut(14));
        }
    }

    private static boolean isGptGroup(PricedModel model) {
        String value = (model.name() + " " + model.platform() + " " + model.groupName()).toLowerCase(Locale.ROOT);
        return value.contains("gpt") || value.contains("openai");
    }

    private List<PricedModel> selected() {
        return choices.stream().filter(AbstractButton::isSelected).map(ModelCheckBox::model).toList();
    }

    static String id(PricedModel model) { return model.groupId() + "\u0000" + model.name(); }
    static String imageNameId(String name) { return "\u0000" + name; }
    static boolean matchesSelectedImage(PricedModel model, Set<String> selectedIds) {
        return selectedIds.contains(id(model)) || selectedIds.contains(imageNameId(model.name()));
    }
    static boolean supportsClient(PricedModel model, String client) {
        return !model.isImageGeneration() || "Codex".equals(client);
    }

    static List<PricedModel> orderedModels(List<PricedModel> models, String client) {
        Map<String, List<PricedModel>> grouped = new LinkedHashMap<>();
        for (PricedModel model : models) {
            if (!supportsClient(model, client) || model.isImageGeneration()) continue;
            grouped.computeIfAbsent(groupKey(model), ignored -> new ArrayList<>()).add(model);
        }
        List<List<PricedModel>> orderedGroups = grouped.values().stream()
            .sorted((left, right) -> compareGroups(left, right, client)).toList();
        List<PricedModel> images = orderedImageModels(models);
        List<PricedModel> result = new ArrayList<>();
        boolean imagesAdded = false;
        for (List<PricedModel> group : orderedGroups) {
            List<PricedModel> sorted = new ArrayList<>(group);
            sorted.sort(ApiClient::compareSelectablePriceDescending);
            result.addAll(sorted);
            PricedModel first = sorted.getFirst();
            if ("Codex".equals(client) && !imagesAdded && !first.subscription() && isGptGroup(first)) {
                result.addAll(images);
                imagesAdded = true;
            }
        }
        if ("Codex".equals(client) && !imagesAdded) result.addAll(images);
        return List.copyOf(result);
    }

    private static List<PricedModel> orderedImageModels(List<PricedModel> models) {
        List<PricedModel> images = models.stream().filter(PricedModel::isImageGeneration)
            .sorted(ApiClient::compareSelectablePriceDescending).toList();
        Map<String, List<PricedModel>> grouped = new LinkedHashMap<>();
        for (PricedModel model : images) grouped.computeIfAbsent(groupKey(model), ignored -> new ArrayList<>()).add(model);
        List<PricedModel> result = new ArrayList<>();
        for (List<PricedModel> group : grouped.values()) {
            group.sort(ApiClient::compareSelectablePriceDescending);
            result.addAll(group);
        }
        return result;
    }

    private static String groupKey(PricedModel model) { return model.groupId() + "\u0000" + model.groupName(); }

    static int compareGroups(List<PricedModel> left, List<PricedModel> right) {
        return compareGroups(left, right, "Codex");
    }

    static int compareGroups(List<PricedModel> left, List<PricedModel> right, String client) {
        PricedModel a = left.getFirst(), b = right.getFirst();
        if (a.subscription() && b.subscription()) {
            int balance = Double.compare(b.subscriptionRemaining(), a.subscriptionRemaining());
            if (balance != 0) return balance;
        }
        int rank = Integer.compare(groupRank(a, client), groupRank(b, client));
        if (rank != 0) return rank;
        return a.displayGroupName().compareToIgnoreCase(b.displayGroupName());
    }

    static int groupRank(PricedModel model) {
        return groupRank(model, "Codex");
    }

    static int groupRank(PricedModel model, String client) {
        String value = (model.name() + " " + model.platform() + " " + model.groupName()).toLowerCase(Locale.ROOT);
        if ("Claude".equals(client)) {
            if (model.subscription()) return 0;
            if (value.contains("claude") || value.contains("anthropic")) return 1;
            if (value.contains("gpt") || value.contains("openai")) return 2;
            if (value.contains("grok") || value.contains("xai")) return 3;
            if (value.contains("gemini") || value.contains("google")) return 4;
            return 5;
        }
        if (model.subscription()) return -1;
        if (value.contains("gpt") || value.contains("openai")) return 0;
        if (value.contains("claude") || value.contains("anthropic")) return 1;
        if (value.contains("grok") || value.contains("xai")) return 2;
        if (value.contains("gemini") || value.contains("google")) return 3;
        return 4;
    }

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
            setPreferredSize(new Dimension(620, 48));
            setMinimumSize(new Dimension(300, 48));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
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
                g.setPaint(new GradientPaint(0, 0, new Color(105, 85, 232, 190), width, 0, new Color(52, 144, 235, 155)));
            } else {
                g.setColor(hovered ? new Color(45, 63, 116, 220) : new Color(25, 40, 80, 205));
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

            String price = model.priceLabel();
            g.setFont(font(11, Font.PLAIN));
            FontMetrics priceMetrics = g.getFontMetrics();
            int priceX = getWidth() - priceMetrics.stringWidth(price) - 15;
            g.setColor(isSelected() ? new Color(225, 235, 255) : new Color(180, 199, 238));
            g.drawString(price, priceX, (getHeight() - priceMetrics.getHeight()) / 2 + priceMetrics.getAscent());

            g.setFont(getFont());
            g.setColor(isSelected() ? Color.WHITE : hovered ? new Color(239, 243, 255) : TEXT);
            FontMetrics metrics = g.getFontMetrics();
            String name = fit(model.displayName(), metrics, Math.max(80, priceX - 50));
            g.drawString(name, 38, (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
            g.dispose();
        }

        private static String fit(String value, FontMetrics metrics, int maxWidth) {
            if (metrics.stringWidth(value) <= maxWidth) return value;
            String suffix = "…";
            int end = value.length();
            while (end > 1 && metrics.stringWidth(value.substring(0, end) + suffix) > maxWidth) end--;
            return value.substring(0, end) + suffix;
        }
    }

    private static final class CosmosPanel extends JPanel {
        CosmosPanel() { setOpaque(true); setBackground(new Color(15, 26, 58)); }
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setPaint(new GradientPaint(0, 0, new Color(24, 43, 89), getWidth(), getHeight(), new Color(24, 24, 76)));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setPaint(new RadialGradientPaint(getWidth() * .78f, getHeight() * .08f, Math.max(180, getWidth() * .52f),
                new float[]{0f, 1f}, new Color[]{new Color(106, 89, 242, 105), new Color(31, 30, 92, 0)}));
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

    private static final class WindowControlButton extends JButton {
        private final Color accent;
        WindowControlButton(Color accent) {
            super("");
            this.accent = accent;
            setToolTipText("关闭");
            getAccessibleContext().setAccessibleName("关闭");
            setPreferredSize(new Dimension(28, 28));
            setFocusPainted(false);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = getModel().isRollover() ? accent.brighter() : accent;
            g.setColor(fill);
            int diameter = 17;
            int x = (getWidth() - diameter) / 2, y = (getHeight() - diameter) / 2;
            g.fillOval(x, y, diameter, diameter);
            g.setColor(new Color(125, 24, 20, 150));
            g.drawOval(x, y, diameter, diameter);
            if (getModel().isRollover()) {
                g.setColor(new Color(90, 20, 18, 220));
                g.setStroke(new BasicStroke(1.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = getWidth() / 2, cy = getHeight() / 2;
                g.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
                g.drawLine(cx + 4, cy - 4, cx - 4, cy + 4);
            }
            g.dispose();
        }
    }

    private static final class GroupPanel extends JPanel {
        private final boolean subscription;
        GroupPanel(boolean subscription) { this.subscription = subscription; setOpaque(false); }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(subscription ? new Color(69, 54, 25, 235) : new Color(19, 67, 61, 232));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
            g.setColor(subscription ? new Color(196, 153, 61, 135) : new Color(79, 190, 163, 125));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class BillingBadge extends JLabel {
        private final boolean subscription;
        BillingBadge(boolean subscription) {
            super(subscription ? "订阅" : "余额");
            this.subscription = subscription;
            setFont(font(10, Font.BOLD));
            setForeground(subscription ? new Color(244, 210, 126) : new Color(151, 229, 211));
            setBorder(new EmptyBorder(3, 8, 3, 8));
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(subscription ? new Color(125, 91, 27, 190) : new Color(31, 105, 91, 175));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g.setColor(subscription ? new Color(221, 174, 70, 150) : new Color(98, 205, 178, 120));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g.dispose();
            super.paintComponent(graphics);
        }
    }
}

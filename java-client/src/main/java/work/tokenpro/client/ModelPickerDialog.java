package work.tokenpro.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

/** Model selection stays on the dashboard and uses ordinary click-to-toggle controls. */
final class ModelPickerDialog extends JDialog {
    private static final Color TEXT = new Color(242, 245, 255);
    private static final Color MUTED = new Color(180, 190, 220);
    private static final Color LIST_BACKGROUND = new Color(24, 32, 78);
    private static final Color SUBSCRIPTION_SURFACE = new Color(69, 54, 25, 235);
    private static final Color SUBSCRIPTION_BORDER = new Color(196, 153, 61, 135);
    private static final Color SUBSCRIPTION_TEXT = new Color(218, 181, 92);
    private static final Color SUBSCRIPTION_BADGE_SURFACE = new Color(125, 91, 27, 190);
    private static final Color SUBSCRIPTION_BADGE_BORDER = new Color(221, 174, 70, 150);
    private static final Color SUBSCRIPTION_BADGE_TEXT = new Color(244, 210, 126);
    private static final Color IMAGE_TEXT = new Color(139, 231, 255);
    private static final Color IMAGE_BADGE_SURFACE = new Color(71, 72, 181, 175);
    private static final Color IMAGE_BADGE_BORDER = new Color(104, 218, 255, 185);
    private final List<ModelCheckBox> choices = new ArrayList<>();

    ModelPickerDialog(JFrame owner, String client, boolean cli, List<PricedModel> models,
                      Set<String> selectedIds, Consumer<List<PricedModel>> onApply) {
        super(owner, "选择 " + client + (cli ? " 命令行" : " 客户端") + " 模型", true);
        setUndecorated(true);
        boolean transparent = false;
        try {
            setBackground(new Color(0, 0, 0, 0));
            transparent = getBackground().getAlpha() == 0;
        } catch (UnsupportedOperationException | IllegalComponentStateException ignored) {}
        if (!transparent) setBackground(new Color(15, 26, 58));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(content(client, cli, models, selectedIds, onApply));
        setSize(760, 680);
        setMinimumSize(new Dimension(560, 480));
        if (!transparent) applyShape();
        getRootPane().registerKeyboardAction(event -> dispose(),
            KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setLocationRelativeTo(owner);
    }

    private void applyShape() {
        try { setShape(new java.awt.geom.RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 24, 24)); }
        catch (UnsupportedOperationException ignored) {}
    }

    private JComponent content(String client, boolean cli, List<PricedModel> models, Set<String> selectedIds,
                               Consumer<List<PricedModel>> onApply) {
        models = orderedModels(models, client, cli);
        JPanel root = new CosmosPanel();
        root.setLayout(new BorderLayout());
        root.setBorder(new EmptyBorder(24, 26, 22, 26));

        JPanel header = transparent();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JPanel titleLine = transparent(new BorderLayout());
        JLabel title = new JLabel("选择 " + client + (cli ? " 命令行" : " 客户端") + " 模型");
        title.setFont(font(23, Font.BOLD));
        title.setForeground(TEXT);
        titleLine.add(title, BorderLayout.WEST);
        boolean codex = "Codex".equals(client);
        boolean supportsImages = codex && !cli;
        JLabel detail = new JLabel(supportsImages ? "文本和生图模型均可多选；每次使用 Codex 中当前选择的模型" : "LLM Model 至少选择 1 个，可同时选择多个");
        detail.setFont(font(12, Font.PLAIN));
        detail.setForeground(MUTED);
        header.add(titleLine);
        header.add(Box.createVerticalStrut(6));
        header.add(detail);
        header.add(Box.createVerticalStrut(18));
        root.add(header, BorderLayout.NORTH);

        JPanel groups = new JPanel();
        groups.setBackground(LIST_BACKGROUND);
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        Map<String, List<PricedModel>> grouped = new LinkedHashMap<>();
        for (PricedModel model : models) {
            if (!supportsClient(model, client, cli) || model.isImageGeneration()) continue;
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
            PlatformStyle platformStyle = platformStyle(first.groupPlatform());
            JPanel group = new GroupPanel(subscription, platformStyle, false);
            group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
            group.setBorder(new EmptyBorder(14, 16, 12, 16));
            group.setAlignmentX(Component.LEFT_ALIGNMENT);
            String groupLabel = first.displayGroupName();
            if (subscription) groupLabel += "   订阅余额 $" + String.format(Locale.US, "%.2f", first.subscriptionRemaining()) + "   " + first.subscriptionExpiryLabel();
            JLabel groupName = new JLabel(groupLabel);
            groupName.setFont(font(13, Font.BOLD));
            groupName.setForeground(subscription ? SUBSCRIPTION_TEXT : platformStyle.text());
            JPanel heading = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
            heading.setAlignmentX(Component.LEFT_ALIGNMENT);
            heading.add(new BillingBadge(subscription, platformStyle, false));
            heading.add(new JLabel(platformIcon(platformStyle)));
            heading.add(groupName);
            lockRowHeight(heading);
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
            // Keep images immediately below the first GPT group, including a
            // GPT subscription group, rather than below every subscription.
            if (supportsImages && !imageGroupAdded && isGptGroup(first)) {
                addImageChoices(groups, models, selectedIds);
                imageGroupAdded = true;
            }
        }
        if (supportsImages && !imageGroupAdded) addImageChoices(groups, models, selectedIds);
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
        // A translucent window must not copy existing screen pixels while
        // scrolling: on macOS that can expose its owner's surface for a frame.
        // Paint the viewport and view independently of the rounded outer shell.
        scroll.setBackground(LIST_BACKGROUND);
        scroll.setOpaque(true);
        scroll.getViewport().setBackground(LIST_BACKGROUND);
        scroll.getViewport().setOpaque(true);
        scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        // Keep the viewport in its own opaque center cell.  Without this
        // wrapper, translucent/undecorated Windows windows can let the view's
        // painted surface appear over the fixed action bar during repaint.
        JPanel scrollArea = new JPanel(new BorderLayout());
        scrollArea.setOpaque(true);
        scrollArea.setBackground(LIST_BACKGROUND);
        scrollArea.add(scroll, BorderLayout.CENTER);
        root.add(scrollArea, BorderLayout.CENTER);

        // The list is scrollable and can be much taller than the dialog.  A
        // transparent footer lets the last selected row show through on
        // macOS when Swing repaints the viewport during a scroll, which also
        // makes the row intercept clicks intended for the action buttons.
        JPanel footer = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(LIST_BACKGROUND);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        footer.setOpaque(true);
        footer.setBorder(new EmptyBorder(17, 0, 0, 0));
        // Reserve a real, non-collapsible footer row.  This keeps the scroll
        // viewport from extending under the buttons when the dialog is resized
        // or when Swing recalculates the BoxLayout view height.
        footer.setPreferredSize(new Dimension(0, 74));
        footer.setMinimumSize(new Dimension(0, 74));
        footer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 74));
        JLabel count = new JLabel();
        count.setFont(font(12, Font.BOLD));
        count.setForeground(new Color(105, 220, 194));
        Runnable updateCount = () -> {
            long imageCount = selected().stream().filter(PricedModel::isImageGeneration).count();
            long chatCount = selected().size() - imageCount;
            count.setText(supportsImages ? "主模型已选 " + chatCount + " 个   生图模型已选 " + imageCount + " 个" : "已选择 " + selected().size() + " 个模型");
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
            if (selected.isEmpty()) {
                TokenProDialogs.warning(this, "请选择模型",
                    "请至少选择 1 款模型。");
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
        // Keep the fixed action bar above the viewport even on translucent
        // Windows repaint paths where child painting can overlap visually.
        root.setComponentZOrder(footer, 0);
        return root;
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
            PricedModel first = groupModels.getFirst();
            boolean subscription = first.subscription();
            PlatformStyle platformStyle = platformStyle(first.groupPlatform());
            boolean dedicatedImageGroup = isDedicatedImageGroup(first);
            JPanel imageGroup = new GroupPanel(subscription, platformStyle, dedicatedImageGroup);
            imageGroup.setLayout(new BoxLayout(imageGroup, BoxLayout.Y_AXIS));
            imageGroup.setBorder(new EmptyBorder(14, 16, 12, 16));
            imageGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel imageTitle = new JLabel(first.displayGroupName());
            imageTitle.setFont(font(13, Font.BOLD));
            imageTitle.setForeground(subscription ? SUBSCRIPTION_TEXT : dedicatedImageGroup ? IMAGE_TEXT : platformStyle.text());
            JPanel imageHeading = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
            imageHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
            imageHeading.add(new BillingBadge(subscription, platformStyle, dedicatedImageGroup));
            imageHeading.add(new JLabel(platformIcon(platformStyle, dedicatedImageGroup)));
            imageHeading.add(imageTitle);
            lockRowHeight(imageHeading);
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

    static boolean isDedicatedImageGroup(PricedModel model) {
        return model.isImageGeneration();
    }

    private List<PricedModel> selected() {
        return choices.stream().filter(AbstractButton::isSelected).map(ModelCheckBox::model).toList();
    }

    static String id(PricedModel model) { return model.groupId() + "\u0000" + model.name(); }
    static boolean matchesSelectedImage(PricedModel model, Set<String> selectedIds) {
        return selectedIds.contains(id(model));
    }
    static boolean supportsClient(PricedModel model, String client) {
        return supportsClient(model, client, false);
    }

    static boolean supportsClient(PricedModel model, String client, boolean cli) {
        return !model.isImageGeneration() || ("Codex".equals(client) && !cli);
    }

    static List<PricedModel> orderedModels(List<PricedModel> models, String client) {
        return orderedModels(models, client, false);
    }

    static List<PricedModel> orderedModels(List<PricedModel> models, String client, boolean cli) {
        Map<String, List<PricedModel>> grouped = new LinkedHashMap<>();
        for (PricedModel model : models) {
            if (!supportsClient(model, client, cli) || model.isImageGeneration()) continue;
            grouped.computeIfAbsent(groupKey(model), ignored -> new ArrayList<>()).add(model);
        }
        List<List<PricedModel>> orderedGroups = grouped.values().stream()
            .sorted((left, right) -> compareGroups(left, right, client)).toList();
        List<PricedModel> images = cli ? List.of() : orderedImageModels(models);
        List<PricedModel> result = new ArrayList<>();
        boolean imagesAdded = false;
        for (List<PricedModel> group : orderedGroups) {
            List<PricedModel> sorted = new ArrayList<>(group);
            sorted.sort(ApiClient::compareSelectablePriceDescending);
            PricedModel first = sorted.getFirst();
            result.addAll(sorted);
            if ("Codex".equals(client) && !imagesAdded && isGptGroup(first)) {
                result.addAll(images);
                imagesAdded = true;
            }
        }
        if ("Codex".equals(client) && !imagesAdded) result.addAll(images);
        return List.copyOf(result);
    }

    private static boolean isGptGroup(PricedModel model) {
        String value = (model.name() + " " + model.platform() + " " + model.groupPlatform() + " " + model.groupName())
            .toLowerCase(Locale.ROOT);
        return value.contains("gpt") || value.contains("openai");
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
    private static void lockRowHeight(JComponent component) {
        int height = component.getPreferredSize().height;
        component.setMinimumSize(new Dimension(0, height));
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }
    private static Font font(float size, int style) { return new Font(Platform.OS_KIND == Platform.OS.MAC ? ".AppleSystemUIFont" : "SansSerif", style, Math.round(size)); }

    private record PlatformStyle(Color surface, Color border, Color text, Color icon, String iconResource) {}

    private static PlatformStyle platformStyle(String rawPlatform) {
        String platform = rawPlatform == null ? "" : rawPlatform.trim().toLowerCase(Locale.ROOT);
        platform = switch (platform) {
            case "gpt", "chatgpt" -> "openai";
            case "claude" -> "anthropic";
            case "google" -> "gemini";
            case "xai" -> "grok";
            default -> platform;
        };
        return switch (platform) {
            case "openai" -> style(34, 197, 94, 134, 239, 172, 52, 211, 153, "OpenAIBlossomRuntime.png");
            case "anthropic" -> style(249, 115, 22, 253, 186, 116, 251, 146, 60, "ClaudeSparkRuntime.png");
            case "antigravity" -> style(168, 85, 247, 216, 180, 254, 192, 132, 252, "UnknownModelRuntime.png");
            case "gemini" -> style(59, 130, 246, 147, 197, 253, 96, 165, 250, "GeminiSparkTransparent.png");
            case "grok" -> style(113, 113, 122, 228, 228, 231, 228, 228, 231, "GrokMarkTransparent.png");
            case "kimi" -> style(236, 72, 153, 249, 168, 212, 244, 114, 182, "UnknownModelRuntime.png");
            case "zhipu" -> style(99, 102, 241, 165, 180, 252, 129, 140, 248, "UnknownModelRuntime.png");
            case "deepseek" -> style(20, 184, 166, 94, 234, 212, 45, 212, 191, "UnknownModelRuntime.png");
            case "minimax" -> style(244, 63, 94, 253, 164, 175, 251, 113, 133, "UnknownModelRuntime.png");
            case "opencode_go" -> style(245, 158, 11, 252, 211, 77, 252, 211, 77, "UnknownModelRuntime.png");
            case "composite" -> style(6, 182, 212, 103, 232, 249, 103, 232, 249, "UnknownModelRuntime.png");
            default -> style(20, 184, 166, 94, 234, 212, 45, 212, 191, "UnknownModelRuntime.png");
        };
    }

    private static PlatformStyle style(int red, int green, int blue,
                                       int textRed, int textGreen, int textBlue,
                                       int iconRed, int iconGreen, int iconBlue,
                                       String iconResource) {
        return new PlatformStyle(
            new Color(red, green, blue, 26),
            new Color(red, green, blue, 92),
            new Color(textRed, textGreen, textBlue),
            new Color(iconRed, iconGreen, iconBlue),
            iconResource
        );
    }

    private static Icon platformIcon(PlatformStyle style) {
        return TokenProFrame.resourceIconContained(style.iconResource(), 15, 15, style.icon());
    }

    private static Icon platformIcon(PlatformStyle style, boolean dedicatedImageGroup) {
        if (!dedicatedImageGroup) return platformIcon(style);
        Icon base = TokenProFrame.resourceIconGradientContained(style.iconResource(), 17, 17,
            new Color(52, 211, 153), new Color(69, 212, 255), new Color(151, 91, 255));
        return base == null ? platformIcon(style) : new ImageGenerationPlatformIcon(base);
    }

    static int[] platformStyleSnapshot(String platform, boolean subscription) {
        PlatformStyle style = platformStyle(platform);
        Color surface = subscription ? SUBSCRIPTION_SURFACE : style.surface();
        Color text = subscription ? SUBSCRIPTION_TEXT : style.text();
        return new int[]{surface.getRGB(), text.getRGB(), style.icon().getRGB()};
    }

    static int[] billingBadgeStyleSnapshot(String platform, boolean subscription) {
        return billingBadgeStyleSnapshot(platform, subscription, false);
    }

    static int[] billingBadgeStyleSnapshot(String platform, boolean subscription, boolean dedicatedImageGroup) {
        PlatformStyle style = platformStyle(platform);
        Color surface = subscription ? SUBSCRIPTION_BADGE_SURFACE : dedicatedImageGroup ? IMAGE_BADGE_SURFACE : alpha(style.surface(), 110);
        Color border = subscription ? SUBSCRIPTION_BADGE_BORDER : dedicatedImageGroup ? IMAGE_BADGE_BORDER : alpha(style.border(), 155);
        Color text = subscription ? SUBSCRIPTION_BADGE_TEXT : dedicatedImageGroup ? IMAGE_TEXT : style.text();
        return new int[]{surface.getRGB(), border.getRGB(), text.getRGB()};
    }

    private static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

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
        CosmosPanel() { setOpaque(false); }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.geom.RoundRectangle2D surface = new java.awt.geom.RoundRectangle2D.Double(
                0, 0, Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1), 24, 24);
            g.clip(surface);
            g.setPaint(new GradientPaint(0, 0, new Color(24, 43, 89), getWidth(), getHeight(), new Color(24, 24, 76)));
            g.fill(surface);
            g.setPaint(new RadialGradientPaint(getWidth() * .78f, getHeight() * .08f, Math.max(180, getWidth() * .52f),
                new float[]{0f, 1f}, new Color[]{new Color(106, 89, 242, 105), new Color(31, 30, 92, 0)}));
            g.fill(surface);
            g.setColor(new Color(198, 218, 255, 105));
            for (int i = 0; i < 28; i++) {
                int x = Math.floorMod(i * 83 + 31, Math.max(1, getWidth()));
                int y = Math.floorMod(i * 47 + 19, Math.max(1, getHeight()));
                int size = i % 7 == 0 ? 2 : 1;
                g.fillOval(x, y, size, size);
            }
            g.setColor(new Color(184, 199, 255, 45));
            g.draw(surface);
            g.dispose();
        }
    }

    static int[] cosmosBackgroundAlphaPixels(int width, int height) {
        CosmosPanel panel = new CosmosPanel();
        panel.setSize(width, height);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        panel.paint(graphics);
        graphics.dispose();
        return new int[]{image.getRGB(0, 0) >>> 24, image.getRGB(width / 2, height / 2) >>> 24};
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
        private final boolean subscription;
        private final PlatformStyle platformStyle;
        private final boolean dedicatedImageGroup;
        GroupPanel(boolean subscription, PlatformStyle platformStyle, boolean dedicatedImageGroup) {
            this.subscription = subscription;
            this.platformStyle = platformStyle;
            this.dedicatedImageGroup = dedicatedImageGroup;
            // The image group lives inside a translucent scroll surface. Mark it
            // opaque so Swing repaints it independently instead of only making
            // its heading visible during a child hover repaint.
            setOpaque(dedicatedImageGroup);
            if (dedicatedImageGroup) setBackground(LIST_BACKGROUND);
        }
        @Override public Dimension getMaximumSize() {
            Dimension preferred = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, preferred.height);
        }
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape surface = new java.awt.geom.RoundRectangle2D.Double(0, 0,
                Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1), 18, 18);
            if (dedicatedImageGroup) {
                // Image models use the same flat list surface as the other
                // groups.  The large gradient card and motif made the scroll
                // content look like a second dialog and visually ran into the
                // fixed footer at the bottom.
                g.setColor(LIST_BACKGROUND);
                g.fillRect(0, 0, getWidth(), getHeight());
            } else {
                g.setColor(subscription ? SUBSCRIPTION_SURFACE : platformStyle.surface());
                g.fill(surface);
                g.setColor(subscription ? SUBSCRIPTION_BORDER : platformStyle.border());
                g.draw(surface);
            }
            g.dispose();
        }

        private static void paintImageMotif(Graphics2D g, int width, int height) {
            int size = Math.min(54, Math.max(30, height / 4));
            int x = Math.max(0, width - size - 42), y = 18;
            g.setColor(new Color(183, 216, 255, 34));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawRoundRect(x, y, size, size - 8, 7, 7);
            g.drawLine(x + 7, y + size - 17, x + 20, y + size - 30);
            g.drawLine(x + 20, y + size - 30, x + 29, y + size - 21);
            g.drawLine(x + 29, y + size - 21, x + size - 7, y + size - 36);
            g.fillOval(x + 10, y + 8, 6, 6);
            g.drawLine(x + size + 16, y + 5, x + size + 16, y + 19);
            g.drawLine(x + size + 9, y + 12, x + size + 23, y + 12);
        }
    }

    private static final class BillingBadge extends JLabel {
        private final boolean subscription;
        private final PlatformStyle platformStyle;
        private final boolean dedicatedImageGroup;
        BillingBadge(boolean subscription, PlatformStyle platformStyle, boolean dedicatedImageGroup) {
            super(subscription ? "订阅" : "余额");
            this.subscription = subscription;
            this.platformStyle = platformStyle;
            this.dedicatedImageGroup = dedicatedImageGroup;
            setFont(font(10, Font.BOLD));
            setForeground(subscription ? SUBSCRIPTION_BADGE_TEXT : dedicatedImageGroup ? IMAGE_TEXT : platformStyle.text());
            setBorder(new EmptyBorder(3, 8, 3, 8));
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(subscription ? SUBSCRIPTION_BADGE_SURFACE : dedicatedImageGroup ? IMAGE_BADGE_SURFACE : alpha(platformStyle.surface(), 110));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g.setColor(subscription ? SUBSCRIPTION_BADGE_BORDER : dedicatedImageGroup ? IMAGE_BADGE_BORDER : alpha(platformStyle.border(), 155));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class ImageGenerationPlatformIcon implements Icon {
        private final Icon base;
        ImageGenerationPlatformIcon(Icon base) { this.base = base; }
        public int getIconWidth() { return base.getIconWidth() + 4; }
        public int getIconHeight() { return Math.max(base.getIconHeight(), 18); }
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            int baseY = y + (getIconHeight() - base.getIconHeight()) / 2;
            base.paintIcon(component, graphics, x, baseY);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = x + base.getIconWidth() + 1, cy = y + 3;
            g.setColor(new Color(184, 126, 255));
            java.awt.geom.Path2D sparkle = new java.awt.geom.Path2D.Double();
            sparkle.moveTo(cx, cy - 3); sparkle.lineTo(cx + 1, cy - 1); sparkle.lineTo(cx + 3, cy);
            sparkle.lineTo(cx + 1, cy + 1); sparkle.lineTo(cx, cy + 3); sparkle.lineTo(cx - 1, cy + 1);
            sparkle.lineTo(cx - 3, cy); sparkle.lineTo(cx - 1, cy - 1); sparkle.closePath();
            g.fill(sparkle);
            g.dispose();
        }
    }
}

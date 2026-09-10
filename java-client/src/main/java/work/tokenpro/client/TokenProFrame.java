package work.tokenpro.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;

final class TokenProFrame extends JFrame {
    record ReleaseInfo(String version, String downloadUrl, String sha256) {}
    private static final Color PURPLE = new Color(102, 82, 240);
    private static final Color CANVAS = new Color(11, 20, 47);
    private static final Color TEXT = new Color(242, 245, 255);
    private static final Color MUTED = new Color(181, 191, 220);
    private final SecureStore store;
    private final ApiClient api = new ApiClient();
    private final CodexConfig codex;
    private final JTextField email = new JTextField();
    private final JPasswordField password = new JPasswordField();
    private final JLabel account = new JLabel("尚未登录");
    private final DefaultListModel<KeyItem> keys = new DefaultListModel<>();
    private final JList<KeyItem> keyList = new JList<>(keys);
    private final JTextField name = new JTextField("TokenPro");
    private final JTextField baseUrl = new JTextField("https://tokenpro.work/v1");
    private final JTextField model = new JTextField();
    private final JPasswordField apiKey = new JPasswordField();
    private final JLabel status = new JLabel("就绪");
    private final JTree claudeModelTree = modelTree(true);
    private final JTree codexModelTree = modelTree(false);
    private final JLabel bridgeStatus = new JLabel("桥接状态：未检测");
    private final CardLayout pages = new CardLayout();
    private final JPanel pageHost = new JPanel(pages);
    private final Map<String, NavButton> navButtons = new LinkedHashMap<>();
    private final JLabel headerTitle = new JLabel("TokenPro");
    private final JLabel headerUser = new JLabel("登录账户");
    private final JLabel accountEmail = new JLabel("登录账户");
    private final JLabel headerBalance = new JLabel("—");
    private final JLabel accountBalance = new JLabel("—");
    private final JLabel homeClaudeStatus = new JLabel("请先选择模型");
    private final JLabel homeCodexStatus = new JLabel("请先选择模型");
    private JButton codexLaunch;
    private JButton claudeLaunch;
    private JButton updateButton;
    private JComponent activeModelMenuOverlay;
    private JComponent dashboardHeader;
    private final CardLayout views = new CardLayout();
    private final JPanel viewHost = new JPanel(views);
    private CosmosLoginPanel loginView;
    private String accessToken;
    private String refreshToken = "";
    private long tokenExpiresAt;
    private Map<String, Object> sessionUser = Map.of();
    private String accountId = "";

    TokenProFrame(SecureStore store) {
        super("TokenPro");
        this.store = store;
        this.codex = new CodexConfig(store);
        URL iconUrl = TokenProFrame.class.getResource("/assets/TokenProCosmosIcon.png");
        if (iconUrl != null) setIconImage(new ImageIcon(iconUrl).getImage());
        if (Platform.OS_KIND == Platform.OS.MAC) {
            getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
        }
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1080, 720));
        setSize(1280, 820);
        setLocationRelativeTo(null);
        setContentPane(content());
        restoreSession();
        javax.swing.Timer updateTimer = new javax.swing.Timer(2500, e -> checkForUpdates(null, true));
        updateTimer.setRepeats(false);
        updateTimer.start();
    }

    private JComponent content() {
        CosmosLoginPanel.Backdrop shell = new CosmosLoginPanel.Backdrop();
        viewHost.setOpaque(false);
        loginView = new CosmosLoginPanel(email, password, e -> authenticate());
        viewHost.add(loginView, "login");
        viewHost.add(dashboard(), "dashboard");
        shell.add(viewHost, BorderLayout.CENTER);
        views.show(viewHost, "login");
        return shell;
    }

    private JComponent dashboard() {
        JPanel root = new DashboardBackdrop(); root.setLayout(new BorderLayout());
        root.add(sidebar(), BorderLayout.WEST);
        JPanel main = transparent(new BorderLayout()); dashboardHeader = header(); main.add(dashboardHeader, BorderLayout.NORTH);
        pageHost.setOpaque(false);
        pageHost.add(scroll(homePanel()), "首页");
        pageHost.add(scroll(connectionPanel()), "Codex 连接");
        pageHost.add(scroll(claudePanel()), "Claude 连接");
        pageHost.add(scroll(accountPanel()), "我的账户");
        pageHost.add(scroll(toolsPanel()), "工具");
        main.add(pageHost, BorderLayout.CENTER);
        root.add(main, BorderLayout.CENTER); showPage("首页");
        return root;
    }

    private JComponent sidebar() {
        JPanel panel = new SidebarPanel(); panel.setLayout(new BorderLayout()); panel.setPreferredSize(new Dimension(226, 650));
        JPanel top = new JPanel(); top.setOpaque(false); top.setBorder(new EmptyBorder(25, 12, 10, 12)); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel brand = new JLabel("TokenPro", resourceIconContained("TokenProCosmosIcon.png", 28, 28, false), SwingConstants.LEFT); brand.setIconTextGap(11); brand.setFont(appFont(17, Font.BOLD)); brand.setForeground(TEXT); brand.setBorder(new EmptyBorder(0, 5, 24, 0)); top.add(brand);
        addNav(top, "首页", "SparklesLucide.png");
        JButton backend = sideAction("后台管理", "WebCog.png"); backend.addActionListener(e -> browse("https://tokenpro.work/admin/dashboard")); top.add(backend); top.add(Box.createVerticalStrut(6));
        JButton docs = sideAction("使用文档", "WebBook.png"); docs.addActionListener(e -> browse("https://tokenpro.work/docs")); top.add(docs); top.add(Box.createVerticalStrut(6));
        panel.add(top, BorderLayout.NORTH);
        JPanel bottom = new JPanel(new BorderLayout()); bottom.setOpaque(false); bottom.setBorder(new EmptyBorder(0, 12, 18, 12)); NavButton accountNav = new NavButton("我的账户"); accountNav.setIcon(resourceIconContained("CircleUserLucide.png", 17, 17, true)); accountNav.setIconTextGap(12); accountNav.addActionListener(e -> openAccount()); navButtons.put("我的账户", accountNav); bottom.add(accountNav); panel.add(bottom, BorderLayout.SOUTH); return panel;
    }

    private void addNav(JPanel parent, String page, String icon) { NavButton button = new NavButton(page); if (!icon.isBlank()) { button.setIcon(resourceIconContained(icon, 17, 17, true)); button.setIconTextGap(12); } button.addActionListener(e -> showPage(page)); navButtons.put(page, button); parent.add(button); parent.add(Box.createVerticalStrut(6)); }
    private JButton sideAction(String text, String icon) { JButton button = new JButton(text); button.setIcon(resourceIconContained(icon, 17, 17, true)); button.setIconTextGap(12); button.setFont(appFont(13, Font.PLAIN)); button.setForeground(new Color(203, 211, 238)); button.setHorizontalAlignment(SwingConstants.LEFT); button.setPreferredSize(new Dimension(200, 44)); button.setMinimumSize(new Dimension(160, 44)); button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44)); button.setBorder(new EmptyBorder(0, 16, 0, 16)); button.setFocusPainted(false); button.setContentAreaFilled(false); return button; }

    private JComponent header() {
        GradientPanel panel = new GradientPanel(); panel.setLayout(new BorderLayout(0, 16)); panel.setBorder(new EmptyBorder(23, 28, 20, 28));
        JPanel title = transparent(new BorderLayout()); headerTitle.setFont(appFont(23, Font.BOLD)); title.add(headerTitle, BorderLayout.WEST);
        JPanel right = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        updateButton = soft("检查更新"); updateButton.setIcon(resourceIconContained("RefreshCwLucide.png", 15, 15, true)); updateButton.addActionListener(e -> checkForUpdates(updateButton)); right.add(updateButton);
        JButton user = soft("登录账户"); user.setIcon(resourceIconContained("CircleUserLucide.png", 17, 17, true)); user.addActionListener(e -> openAccount()); headerUser.addPropertyChangeListener("text", e -> user.setText(headerUser.getText())); right.add(user);
        title.add(right, BorderLayout.EAST); panel.add(title, BorderLayout.NORTH);
        RoundedPanel wallet = new RoundedPanel(20, new Color(22, 38, 78, 228)); wallet.setLayout(new FlowLayout(FlowLayout.LEFT, 14, 10)); wallet.setBorder(new EmptyBorder(0, 7, 0, 7));
        JPanel captions = transparent(); captions.setLayout(new BoxLayout(captions, BoxLayout.Y_AXIS)); JLabel balanceText = new JLabel("钱包余额"); balanceText.setFont(appFont(12, Font.PLAIN)); balanceText.setForeground(MUTED); JLabel rate = new JLabel("充值比例  1￥ = 1$"); rate.setFont(appFont(10, Font.PLAIN)); rate.setForeground(MUTED); captions.add(balanceText); captions.add(rate); wallet.add(captions);
        headerBalance.setFont(appFont(26, Font.BOLD)); wallet.add(headerBalance); JButton refresh = soft(""); refresh.setToolTipText("刷新余额"); refresh.setIcon(resourceIconContained("RefreshCwLucide.png", 15, 15, true)); refresh.addActionListener(e -> refreshAccount()); wallet.add(refresh); JButton recharge = soft("充值"); recharge.setIcon(resourceIconContained("PlusLucide.png", 15, 15, true)); recharge.addActionListener(e -> browse("https://tokenpro.work/purchase")); wallet.add(recharge);
        JPanel row = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0)); row.add(wallet); panel.add(row, BorderLayout.CENTER); return panel;
    }

    private JComponent homePanel() {
        JPanel panel = vertical(); JLabel title = new JLabel("我的客户端"); title.setFont(appFont(15, Font.BOLD)); panel.add(title); panel.add(Box.createVerticalStrut(13));
        panel.add(desktopClientCard("Codex 客户端", "桌面应用 · 独立登录", Platform.applicationInstalled("Codex"), "Codex", () -> chooseModels("Codex"), this::restoreCodex, () -> openApp("Codex"), homeCodexStatus)); panel.add(Box.createVerticalStrut(12));
        panel.add(desktopClientCard("Claude 客户端", "桌面应用 · 独立登录", Platform.applicationInstalled("Claude"), "Claude", () -> chooseModels("Claude"), this::restoreClaude, this::openClaude, homeClaudeStatus)); panel.add(Box.createVerticalStrut(12));
        panel.add(commandClientCard("Codex 命令行", "命令行工具 · Codex CLI", "Codex", "codex", "https://learn.chatgpt.com/docs/codex/cli")); panel.add(Box.createVerticalStrut(12));
        panel.add(commandClientCard("Claude 命令行", "命令行工具 · Claude Code", "Claude", "claude", "https://docs.anthropic.com/en/docs/claude-code/getting-started")); panel.add(Box.createVerticalGlue()); return panel;
    }

    private JComponent desktopClientCard(String title, String subtitle, boolean installed, String iconName, Runnable chooseModel, Runnable restore, Runnable open, JLabel state) {
        RoundedPanel card = card(); card.setLayout(new BorderLayout(18, 0));
        JLabel badge = new JLabel(clientIcon(iconName, 48)); badge.setHorizontalAlignment(SwingConstants.CENTER); badge.setPreferredSize(new Dimension(52, 52)); card.add(badge, BorderLayout.WEST);
        JPanel words = transparent(); words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS)); JPanel nameLine = transparent(new FlowLayout(FlowLayout.LEFT, 10, 0)); nameLine.setAlignmentX(Component.LEFT_ALIGNMENT); nameLine.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24)); JLabel heading = new JLabel(title); heading.setFont(appFont(17, Font.BOLD)); JLabel installedLabel = new JLabel(installed ? "已安装" : "未安装"); installedLabel.setFont(appFont(11, Font.BOLD)); installedLabel.setForeground(installed ? new Color(97, 222, 165) : MUTED); nameLine.add(heading); nameLine.add(installedLabel); JLabel detail = new JLabel(subtitle); detail.setAlignmentX(Component.LEFT_ALIGNMENT); detail.setFont(appFont(11, Font.PLAIN)); detail.setForeground(MUTED); words.add(Box.createVerticalStrut(3)); words.add(nameLine); words.add(Box.createVerticalStrut(6)); words.add(detail); card.add(words, BorderLayout.CENTER);
        JPanel actions = transparent(); actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS)); JPanel buttons = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton menu = soft("模型选择  ▾");
        menu.addActionListener(e -> showModelMenu(menu, chooseModel, restore));
        JButton launch = primary("打开应用"); launch.addActionListener(e -> open.run()); launch.setEnabled(false);
        if (iconName.equals("Codex")) codexLaunch = launch; else claudeLaunch = launch;
        buttons.add(menu); buttons.add(launch); actions.add(buttons); state.setFont(appFont(11, Font.BOLD)); state.setForeground(PURPLE); state.setAlignmentX(Component.RIGHT_ALIGNMENT); actions.add(Box.createVerticalStrut(7)); actions.add(state); card.add(actions, BorderLayout.EAST); return card;
    }

    private void showModelMenu(JButton anchor, Runnable chooseModel, Runnable restore) {
        hideModelMenu();
        JLayeredPane layered = getLayeredPane();
        JPanel overlay = new JPanel(null);
        overlay.setOpaque(false);
        overlay.setBounds(0, 0, layered.getWidth(), layered.getHeight());
        overlay.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { hideModelMenu(); }
        });

        CosmosMenuPanel menu = new CosmosMenuPanel();
        CosmosMenuButton choose = new CosmosMenuButton("选择模型", false);
        choose.addActionListener(event -> { hideModelMenu(); chooseModel.run(); });
        CosmosMenuButton official = new CosmosMenuButton("恢复官方配置", true);
        official.addActionListener(event -> { hideModelMenu(); restore.run(); });
        menu.add(choose);
        menu.add(official);

        int width = 186;
        int height = 84;
        Point point = SwingUtilities.convertPoint(anchor, 0, anchor.getHeight() + 6, layered);
        int x = Math.max(8, Math.min(point.x, layered.getWidth() - width - 8));
        int y = Math.max(8, Math.min(point.y, layered.getHeight() - height - 8));
        menu.setBounds(x, y, width, height);
        overlay.add(menu);
        activeModelMenuOverlay = overlay;
        layered.add(overlay, JLayeredPane.POPUP_LAYER);
        overlay.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close-model-menu");
        overlay.getActionMap().put("close-model-menu", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { hideModelMenu(); }
        });
        overlay.revalidate();
        overlay.repaint();
    }

    private void hideModelMenu() {
        if (activeModelMenuOverlay == null) return;
        Container parent = activeModelMenuOverlay.getParent();
        if (parent != null) {
            parent.remove(activeModelMenuOverlay);
            parent.revalidate();
            parent.repaint();
        }
        activeModelMenuOverlay = null;
    }

    private JComponent commandClientCard(String title, String subtitle, String iconName, String command, String downloadUrl) {
        boolean installed = Platform.commandInstalled(command);
        RoundedPanel card = card(); card.setLayout(new BorderLayout(18, 0));
        JLabel badge = new JLabel(clientIcon(iconName, 48)); badge.setHorizontalAlignment(SwingConstants.CENTER); badge.setPreferredSize(new Dimension(52, 52)); card.add(badge, BorderLayout.WEST);
        JPanel words = transparent(); words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS)); JLabel heading = new JLabel(title); heading.setFont(appFont(17, Font.BOLD)); JLabel detail = new JLabel(subtitle); detail.setFont(appFont(11, Font.PLAIN)); detail.setForeground(MUTED); words.add(Box.createVerticalStrut(3)); words.add(heading); words.add(Box.createVerticalStrut(6)); words.add(detail); card.add(words, BorderLayout.CENTER);
        JPanel buttons = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0)); JButton download = soft(installed ? "已安装" : "去下载"); download.setEnabled(!installed); download.addActionListener(e -> browse(downloadUrl)); JButton terminal = primary("打开 " + (iconName.equals("Codex") ? "Codex 命令行" : "Claude 命令行")); terminal.setEnabled(installed); terminal.addActionListener(e -> openTerminal(command)); buttons.add(download); buttons.add(terminal); card.add(buttons, BorderLayout.EAST); return card;
    }

    private JComponent accountPanel() {
        JPanel panel = vertical();
        JPanel identity = transparent(new BorderLayout(18, 0)); identity.setAlignmentX(Component.LEFT_ALIGNMENT); identity.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        JLabel avatar = new JLabel(resourceIconContained("CircleUserPurple.png", 48, 48, false)); avatar.setHorizontalAlignment(SwingConstants.CENTER); avatar.setPreferredSize(new Dimension(52, 52)); identity.add(avatar, BorderLayout.WEST);
        JPanel identityWords = transparent(); identityWords.setLayout(new BoxLayout(identityWords, BoxLayout.Y_AXIS)); accountEmail.setFont(appFont(14, Font.BOLD)); accountEmail.setAlignmentX(Component.LEFT_ALIGNMENT); JLabel accountType = new JLabel("TokenPro 云端账户"); accountType.setFont(appFont(11, Font.PLAIN)); accountType.setForeground(MUTED); accountType.setAlignmentX(Component.LEFT_ALIGNMENT); identityWords.add(Box.createVerticalStrut(7)); identityWords.add(accountEmail); identityWords.add(Box.createVerticalStrut(5)); identityWords.add(accountType); identity.add(identityWords, BorderLayout.CENTER);
        JButton logout = soft("退出登录"); logout.addActionListener(e -> logout()); identity.add(logout, BorderLayout.EAST); panel.add(identity); panel.add(Box.createVerticalStrut(18));
        JSeparator divider = new JSeparator(); divider.setForeground(new Color(164, 181, 236, 35)); divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1)); divider.setAlignmentX(Component.LEFT_ALIGNMENT); panel.add(divider); panel.add(Box.createVerticalStrut(18));
        JLabel balanceTitle = new JLabel("账户余额");
        balanceTitle.setFont(appFont(12, Font.PLAIN));
        balanceTitle.setForeground(MUTED);
        panel.add(balanceTitle);
        accountBalance.setFont(appFont(32, Font.BOLD));
        accountBalance.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(Box.createVerticalStrut(8));
        panel.add(accountBalance);
        panel.add(Box.createVerticalStrut(18));
        JButton refresh = soft("刷新账户"); refresh.addActionListener(e -> refreshAccount()); refresh.setAlignmentX(Component.LEFT_ALIGNMENT); panel.add(refresh);
        return panel;
    }

    private void openAccount() {
        if (accessToken == null) { showLoginScreen(); return; }
        showPage("我的账户");
    }

    private void authenticate() {
        String emailValue = email.getText().trim();
        char[] secret = password.getPassword();
        if (emailValue.isBlank() || secret.length == 0) {
            Arrays.fill(secret, '\0');
            loginView.setLoading(false, "请输入邮箱和密码");
            return;
        }
        String passwordValue = new String(secret); Arrays.fill(secret, '\0');
        loginView.setLoading(true, "正在安全连接 TokenPro…");
        new SwingWorker<Map<String, Object>, Void>() {
            protected Map<String, Object> doInBackground() throws Exception {
                Map<String, Object> result = api.login(emailValue, passwordValue);
                accessToken = string(result.get("access_token"));
                if (accessToken.isBlank()) throw new IllegalStateException("登录响应缺少 access_token");
                refreshToken = string(result.get("refresh_token"));
                Number expiresIn = result.get("expires_in") instanceof Number number ? number : null;
                tokenExpiresAt = expiresIn == null ? 0 : System.currentTimeMillis() + expiresIn.longValue() * 1000L;
                Map<String, Object> user = result.containsKey("user") ? Json.object(result.get("user")) : api.me(accessToken);
                saveSession(user);
                return user;
            }
            protected void done() {
                try {
                    showAccount(get()); updateCodexStatus(); updateBridgeStatus(); showPage("首页"); showDashboardScreen(); loginView.setLoading(false, null);
                } catch (Exception e) {
                    accessToken = null;
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    loginView.setLoading(false, "登录失败：" + cause.getMessage());
                }
            }
        }.execute();
    }

    private void showLoginScreen() {
        views.show(viewHost, "login");
        loginView.setLoading(false, null);
    }

    private void showDashboardScreen() {
        views.show(viewHost, "dashboard");
    }

    private void logout() {
        try {
            store.delete("java-session.json"); accessToken = null; refreshToken = ""; tokenExpiresAt = 0; sessionUser = Map.of(); accountId = ""; keys.clear();
            account.setText("尚未登录"); headerUser.setText("登录账户"); accountEmail.setText("登录账户"); headerBalance.setText("—"); accountBalance.setText("—");
            homeCodexStatus.setText("请先选择模型"); homeClaudeStatus.setText("请先选择模型"); if (codexLaunch != null) codexLaunch.setEnabled(false); if (claudeLaunch != null) claudeLaunch.setEnabled(false);
            showPage("首页"); showLoginScreen(); status("已退出账户");
        } catch (Exception ex) { error(ex); }
    }

    private JComponent connectionPanel() {
        JPanel panel = vertical();
        panel.add(pageHeading("选择 Codex 模型", "Image Model 必选 1 个；LLM Model 至少选择 1 个，可同时选择多个。"));
        panel.add(Box.createVerticalStrut(15));
        JLabel hint = new JLabel("专用 Key 保存在当前系统账户的 TokenPro 安全目录中，可随时恢复官方配置。"); hint.setForeground(MUTED); panel.add(hint); panel.add(Box.createVerticalStrut(10));
        JButton load = soft("刷新可用分组与模型"); load.setAlignmentX(Component.LEFT_ALIGNMENT); load.addActionListener(e -> loadCodexModels()); panel.add(load);
        JScrollPane modelScroll = modelScroll(codexModelTree, 300); panel.add(modelScroll); panel.add(Box.createVerticalStrut(10));
        JButton apply = primary("应用到 Codex"); apply.addActionListener(e -> applyCodex()); JButton restore = soft("恢复 Codex 官方配置"); restore.addActionListener(e -> restoreCodex());
        JPanel buttons = transparent(new FlowLayout(FlowLayout.LEFT)); buttons.add(apply); buttons.add(restore); panel.add(buttons);
        return panel;
    }

    private void loadCodexModels() {
        if (accessToken == null) { error(new IllegalStateException("请先登录 TokenPro")); return; }
        async("正在加载模型广场…", () -> api.pricedModels(accessToken), models -> {
            int count = populateModelTree(codexModelTree, models);
            status("已加载你的可用分组，共 " + count + " 个模型");
        });
    }

    private void chooseModels(String client) {
        if (accessToken == null || accountId.isBlank()) { error(new IllegalStateException("请先登录 TokenPro")); return; }
        async("正在加载可用分组与模型…", () -> api.pricedModels(accessToken), models -> {
            if (models.isEmpty()) { error(new IllegalStateException("当前账户没有可用模型")); return; }
            Set<String> selected = selectedModelIds(client);
            ModelPickerDialog dialog = new ModelPickerDialog(this, client, models, selected,
                chosen -> { if ("Codex".equals(client)) applyCodex(chosen); else applyClaude(chosen); });
            status("已加载 " + models.size() + " 个可用模型");
            dialog.setVisible(true);
        });
    }

    private void applyCodex() {
        chooseModels("Codex");
    }

    private void applyCodex(List<PricedModel> selected) {
        if (accessToken == null || accountId.isBlank()) { error(new IllegalStateException("请先登录 TokenPro")); return; }
        selected = uniqueModels(selected);
        List<PricedModel> chatModels = selected.stream().filter(model -> !model.isImageGeneration()).toList();
        List<PricedModel> imageModels = selected.stream().filter(PricedModel::isImageGeneration).toList();
        if (chatModels.isEmpty() || imageModels.size() != 1) { error(new IllegalStateException("请选择 1 个 Image Model，并至少选择 1 个 LLM Model")); return; }
        PricedModel imageModel = imageModels.getFirst();
        List<PricedModel> chosen = new ArrayList<>();
        chosen.add(imageModel);
        chosen.addAll(chatModels);
        async("正在使用全局 Key 配置 Codex…", () -> {
            ApiClient.ManagedKey managed = api.globalKey(accessToken);
            codex.apply("https://tokenpro.work/v1", chosen, managed.key());
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("default_model", chatModels.getFirst().name());
            saved.put("models", modelRows(chosen));
            saved.put("image_model", imageModel.name());
            saved.put("key_id", managed.id());
            store.write("codex-selected.json", Json.stringify(saved));
            return chosen;
        }, configured -> {
            homeCodexStatus.setText(chatModels.size() + " LLM + " + imageModel.displayName());
            if (codexLaunch != null) codexLaunch.setEnabled(true);
            status("Codex 已配置 " + configured.size() + " 个模型");
            openApp("Codex");
        });
    }

    private void restoreCodex() {
        try {
            codex.restore(); store.delete("codex-selected.json"); homeCodexStatus.setText("请先选择模型");
            if (codexLaunch != null) codexLaunch.setEnabled(false);
            status("Codex 已恢复官方配置，正在切回应用…");
            openApp("Codex");
        }
        catch (Exception ex) { error(ex); }
    }

    private JComponent toolsPanel() {
        JPanel panel = vertical();
        panel.add(pageHeading("工具", "打开网站、客户端和查看本机运行环境。")); panel.add(Box.createVerticalStrut(18));
        for (var item : List.of(
            new AbstractMap.SimpleEntry<>("打开 TokenPro 网站", (Runnable) () -> browse("https://tokenpro.work")),
            new AbstractMap.SimpleEntry<>("打开充值页面", (Runnable) () -> browse("https://tokenpro.work/purchase")),
            new AbstractMap.SimpleEntry<>("打开 Codex", (Runnable) () -> openApp("Codex")),
            new AbstractMap.SimpleEntry<>("打开 Claude", (Runnable) this::openClaude))) {
            JButton button = new JButton(item.getKey());
            soft(button);
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.addActionListener(e -> item.getValue().run());
            panel.add(button); panel.add(Box.createVerticalStrut(10));
        }
        panel.add(new JLabel("系统：" + Platform.OS_KIND + " · Java " + System.getProperty("java.version")));
        panel.add(new JLabel("配置目录：" + store.root()));
        return panel;
    }

    private JComponent claudePanel() {
        JPanel panel = vertical();
        panel.add(pageHeading("选择 Claude 桌面模型", "按你的可用分组展示模型；可在一个或多个分组下选择模型。")); panel.add(Box.createVerticalStrut(15));
        JLabel hint = new JLabel("共用一把专用 Key，TokenPro 会按模型自动切换分组并维护本地连接。");
        hint.setForeground(Color.GRAY); panel.add(hint); panel.add(Box.createVerticalStrut(10));
        JButton load = new JButton("刷新可用分组与模型");
        soft(load);
        load.setAlignmentX(Component.LEFT_ALIGNMENT); load.addActionListener(e -> loadClaudeModels()); panel.add(load);
        JScrollPane modelScroll = modelScroll(claudeModelTree, 280); panel.add(modelScroll);
        panel.add(Box.createVerticalStrut(8)); panel.add(bridgeStatus);
        JButton apply = new JButton("应用并启动 Claude 桥接"); apply.addActionListener(e -> applyClaude());
        primary(apply);
        JButton check = new JButton("检测桥接"); check.addActionListener(e -> updateBridgeStatus());
        soft(check);
        JButton restore = new JButton("恢复 Claude 官方配置");
        soft(restore);
        restore.addActionListener(e -> restoreClaude());
        JPanel buttons = transparent(new FlowLayout(FlowLayout.LEFT)); buttons.add(apply); buttons.add(check); buttons.add(restore); panel.add(buttons);
        SwingUtilities.invokeLater(this::updateBridgeStatus);
        return panel;
    }

    private void loadClaudeModels() {
        if (accessToken == null) { error(new IllegalStateException("请先登录 TokenPro")); return; }
        async("正在加载模型广场…", () -> api.pricedModels(accessToken), models -> {
            int count = populateModelTree(claudeModelTree, models);
            status("已加载你的可用分组，共 " + count + " 个模型；可按 Ctrl/Cmd 或 Shift 多选");
        });
    }

    private void applyClaude() {
        if (accessToken == null || accountId.isBlank()) { error(new IllegalStateException("请先登录 TokenPro")); return; }
        List<PricedModel> selected = selectedModels(claudeModelTree);
        if (selected.isEmpty()) { error(new IllegalStateException("请至少选择一个模型")); return; }
        applyClaude(selected);
    }

    private void applyClaude(List<PricedModel> selected) {
        if (accessToken == null || accountId.isBlank()) { error(new IllegalStateException("请先登录 TokenPro")); return; }
        selected = uniqueModels(selected);
        if (selected.isEmpty()) { error(new IllegalStateException("请至少选择一个模型")); return; }
        List<PricedModel> chosen = selected;
        async("正在使用全局 Key 配置 Claude…", () -> {
            ApiClient.ManagedKey managed = api.globalKey(accessToken);
            ClaudeBridgeConfig config = ClaudeBridgeConfig.create(accountId, accessToken, managed, chosen);
            config.save(store); ClaudeDesktopConfig.install(store, config); ClaudeBridgeManager.ensureRunning(store); return config;
        }, config -> {
            bridgeStatus.setText("桥接状态：运行中 · " + config.routes().size() + " 个模型 · " + config.baseUrl());
            homeClaudeStatus.setText("已选 " + config.routes().size() + " 个模型");
            if (claudeLaunch != null) claudeLaunch.setEnabled(true);
            status("Claude 已配置 " + config.routes().size() + " 个模型");
            openClaude();
        });
    }

    private void updateBridgeStatus() {
        try { ClaudeBridgeConfig config = ClaudeBridgeConfig.load(store); boolean healthy = ClaudeBridgeManager.healthy(store); bridgeStatus.setText("桥接状态：" + (healthy ? "运行中" : "已配置") + " · " + config.routes().size() + " 个模型"); homeClaudeStatus.setText("已选 " + config.routes().size() + " 个模型"); if (claudeLaunch != null) claudeLaunch.setEnabled(true); }
        catch (Exception e) { bridgeStatus.setText("桥接状态：未配置"); homeClaudeStatus.setText("请先选择模型"); if (claudeLaunch != null) claudeLaunch.setEnabled(false); }
    }

    private void restoreClaude() {
        try {
            ClaudeDesktopConfig.restoreOfficial(store); bridgeStatus.setText("桥接状态：Claude 已恢复官方配置");
            homeClaudeStatus.setText("请先选择模型"); if (claudeLaunch != null) claudeLaunch.setEnabled(false);
            status("Claude 已恢复官方配置，正在切回应用…");
            openApp("Claude");
        }
        catch (Exception ex) { error(ex); }
    }

    private void openClaude() {
        try { ClaudeBridgeConfig.load(store); ClaudeBridgeManager.ensureRunning(store); openApp("Claude"); updateBridgeStatus(); }
        catch (Exception e) { error(e); }
    }

    private void restoreSession() {
        async("正在恢复登录…", () -> {
            Optional<String> raw = store.read("java-session.json");
            if (raw.isEmpty()) return null;
            Map<String, Object> saved = Json.object(Json.parse(raw.get()));
            accessToken = string(saved.get("access_token"));
            refreshToken = string(saved.get("refresh_token"));
            tokenExpiresAt = saved.get("token_expires_at") instanceof Number number ? number.longValue() : 0;
            sessionUser = saved.get("user") instanceof Map<?, ?> ? Json.object(saved.get("user")) : Map.of();
            if (accessToken.isBlank()) return null;
            Map<String, Object> user = api.me(accessToken);
            saveSession(user);
            return user;
        }, value -> {
            if (value == null) { status("就绪"); showLoginScreen(); }
            else { showAccount(value); updateCodexStatus(); updateBridgeStatus(); showPage("首页"); showDashboardScreen(); }
        });
    }

    private void updateCodexStatus() {
        try {
            Optional<String> raw = store.read("codex-selected.json");
            if (raw.isEmpty()) throw new IllegalStateException("未选择");
            Map<String, Object> saved = Json.object(Json.parse(raw.get()));
            if (saved.get("models") instanceof List<?> models && !models.isEmpty()) {
                String imageModel = string(saved.get("image_model"));
                boolean imageEnabled = !imageModel.isBlank();
                int chatCount = imageEnabled ? Math.max(0, models.size() - 1) : models.size();
                homeCodexStatus.setText(chatCount > 0 && imageEnabled
                    ? chatCount + " LLM + " + PricedModel.displayCase(imageModel)
                    : "请重新选择模型");
            }
            else {
                String selected = string(saved.get("model")); if (selected.isBlank()) throw new IllegalStateException("未选择");
                homeCodexStatus.setText(selected);
            }
            if (codexLaunch != null) codexLaunch.setEnabled(true);
        } catch (Exception ignored) { homeCodexStatus.setText("请先选择模型"); if (codexLaunch != null) codexLaunch.setEnabled(false); }
    }

    private Set<String> selectedModelIds(String client) {
        Set<String> ids = new HashSet<>();
        try {
            if ("Claude".equals(client)) {
                for (ClaudeBridgeConfig.Route route : ClaudeBridgeConfig.load(store).routes()) {
                    ids.add(route.groupId() + "\u0000" + route.name());
                }
            } else {
                Optional<String> raw = store.read("codex-selected.json");
                if (raw.isPresent()) {
                    Map<String, Object> root = Json.object(Json.parse(raw.get()));
                    if (root.get("models") instanceof List<?> rows) for (Object value : rows) {
                        Map<String, Object> row = Json.object(value);
                        if (row.get("group_id") instanceof Number groupId) ids.add(groupId.longValue() + "\u0000" + string(row.get("name")));
                    }
                    String imageModel = string(root.get("image_model"));
                    if (!imageModel.isBlank()) ids.add(ModelPickerDialog.imageNameId(imageModel));
                    if (ids.isEmpty() && root.get("group_id") instanceof Number groupId) ids.add(groupId.longValue() + "\u0000" + string(root.get("model")));
                }
            }
        } catch (Exception ignored) {}
        return ids;
    }

    private static List<Map<String, Object>> modelRows(List<PricedModel> models) {
        return models.stream().map(model -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", model.name());
            row.put("platform", model.platform());
            row.put("group_name", model.groupName());
            row.put("group_id", model.groupId());
            return row;
        }).toList();
    }

    private static List<PricedModel> uniqueModels(List<PricedModel> models) {
        Map<String, PricedModel> unique = new LinkedHashMap<>();
        for (PricedModel model : models) unique.putIfAbsent(model.name(), model);
        return List.copyOf(unique.values());
    }

    private void loadKeys() {
        if (accessToken == null) { error(new IllegalStateException("请先登录 TokenPro")); return; }
        async("正在读取 API Key…", () -> api.keys(accessToken), result -> {
            keys.clear();
            Object raw = result.get("items");
            if (raw instanceof List<?> list) for (Object value : list) {
                Map<String, Object> item = Json.object(value);
                if (item.get("id") instanceof Number id) keys.addElement(new KeyItem(id.longValue(), string(item.get("name")), string(item.get("status"))));
            }
            status("已读取 " + keys.size() + " 个 API Key；选择一项即可导入");
        });
    }

    private void importKey(KeyItem item) {
        if (!"active".equals(item.status())) { error(new IllegalStateException("这个 API Key 未启用")); return; }
        async("正在导入 API Key…", () -> api.key(accessToken, item.id()), result -> {
            String key = string(result.get("key"));
            if (key.length() < 8 || key.contains("*") || key.contains("…")) { error(new IllegalStateException("服务器没有返回完整 API Key")); return; }
            name.setText("TokenPro · " + item.name());
            apiKey.setText(key);
            status("已导入 " + item.name() + "，请填写模型 ID 后应用");
        });
    }

    private void showAccount(Map<String, Object> user) {
        sessionUser = new LinkedHashMap<>(user);
        String emailValue = string(user.get("email"));
        Object rawBalance = user.get("balance");
        String balance = rawBalance instanceof Number number ? String.format(Locale.ROOT, "$%.2f", number.doubleValue()) : "—";
        account.setText("已登录：" + emailValue + "    余额：" + balance);
        headerUser.setText(emailValue.isBlank() ? "我的账户" : emailValue);
        accountEmail.setText(emailValue.isBlank() ? "我的账户" : emailValue);
        headerBalance.setText(balance);
        accountBalance.setText(balance);
        accountId = string(user.get("id"));
        password.setText("");
        status("就绪");
    }

    private void saveSession(Map<String, Object> user) throws Exception {
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("access_token", accessToken);
        if (!refreshToken.isBlank()) saved.put("refresh_token", refreshToken);
        if (tokenExpiresAt > 0) saved.put("token_expires_at", tokenExpiresAt);
        saved.put("user", user);
        store.write("java-session.json", Json.stringify(saved));
    }

    private JPanel vertical() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(24, 26, 26, 26));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private JComponent row(String label, JComponent field) {
        JPanel row = transparent(new BorderLayout(12, 6));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        JLabel text = new JLabel(label);
        text.setFont(appFont(12, Font.BOLD));
        text.setPreferredSize(new Dimension(90, 30));
        row.add(text, BorderLayout.WEST); row.add(field, BorderLayout.CENTER);
        return row;
    }

    private void showPage(String page) {
        pages.show(pageHost, page);
        headerTitle.setText(page.equals("首页") ? "TokenPro" : page);
        if (dashboardHeader != null) dashboardHeader.setVisible(!page.equals("我的账户"));
        navButtons.forEach((name, button) -> button.setSelected(name.equals(page)));
    }

    private JScrollPane scroll(JComponent view) {
        JScrollPane scroll = new JScrollPane(view); scroll.setOpaque(false); scroll.setBorder(null); scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16); return scroll;
    }

    private JComponent pageHeading(String title, String subtitle) {
        JPanel panel = transparent(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel heading = new JLabel(title); heading.setFont(appFont(22, Font.BOLD));
        JLabel detail = new JLabel(subtitle); detail.setFont(appFont(12, Font.PLAIN)); detail.setForeground(MUTED);
        panel.add(heading); panel.add(Box.createVerticalStrut(5)); panel.add(detail); return panel;
    }

    private void refreshAccount() {
        if (accessToken == null) { showPage("我的账户"); return; }
        async("正在刷新余额…", () -> api.me(accessToken), this::showAccount);
    }

    private void checkForUpdates(JButton button) {
        checkForUpdates(button, false);
    }

    private void checkForUpdates(JButton button, boolean automatic) {
        if (button != null) { button.setEnabled(false); button.setText("检查中…"); }
        if (!automatic) status("正在检查更新…");
        new SwingWorker<ReleaseInfo, Void>() {
            protected ReleaseInfo doInBackground() throws Exception {
                String payload = "";
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create("https://tokenpro.work/downloads/latest/release.json"))
                        .header("Accept", "application/json").timeout(java.time.Duration.ofSeconds(12)).GET().build();
                    HttpResponse<String> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) payload = response.body();
                } catch (Exception ignored) {}
                if (payload.isBlank()) {
                    try {
                        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repositories/1360196661/releases/latest"))
                            .header("Accept", "application/vnd.github+json").timeout(java.time.Duration.ofSeconds(20)).GET().build();
                        HttpResponse<String> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() == 200) payload = response.body();
                    } catch (Exception ignored) {}
                }
                if (payload.isBlank()) payload = Platform.githubLatestReleaseJson().orElse("");
                return payload.isBlank() ? new ReleaseInfo("", "", "") : releaseForPlatform(payload, Updater.platformKey());
            }
            protected void done() {
                if (button != null) { button.setEnabled(true); button.setText("检查更新"); }
                try {
                    ReleaseInfo release = get();
                    if (release.version().isBlank()) {
                        if (!automatic) {
                            JOptionPane.showMessageDialog(TokenProFrame.this, "暂时无法读取版本信息，请稍后重试。", "检查更新", JOptionPane.WARNING_MESSAGE);
                        }
                    } else if (compareVersions(release.version(), Main.VERSION) > 0) {
                        if (updateButton != null) updateButton.setText("发现 " + release.version());
                        int choice = JOptionPane.showConfirmDialog(TokenProFrame.this, "发现 TokenPro " + release.version() + "，是否立即自动更新？", "发现新版本", JOptionPane.YES_NO_OPTION);
                        if (choice == JOptionPane.YES_OPTION) installUpdate(release);
                    } else if (!automatic) JOptionPane.showMessageDialog(TokenProFrame.this, "当前已是最新版本 " + Main.VERSION, "检查更新", JOptionPane.INFORMATION_MESSAGE);
                    if (!automatic) status("更新检查完成");
                } catch (Exception ex) { if (!automatic) error(ex.getCause() == null ? ex : ex.getCause()); }
            }
        }.execute();
    }

    static ReleaseInfo releaseForPlatform(String payload, String platformKey) {
        Map<String, Object> release = Json.object(Json.parse(payload));
        String version = string(release.get("tag_name")).replaceFirst("^v", "");
        String downloadUrl = "", sha256 = "";
        if (release.get("downloads") instanceof Map<?, ?> rawDownloads) {
            Map<String, Object> downloads = Json.object(rawDownloads);
            if (downloads.get(platformKey) instanceof Map<?, ?> rawDownload) {
                Map<String, Object> download = Json.object(rawDownload);
                downloadUrl = string(download.get("url"));
                sha256 = string(download.get("sha256"));
            }
        }
        if (downloadUrl.isBlank() && release.get("assets") instanceof List<?> assets) {
            String marker = switch (platformKey) {
                case "macos-arm64" -> "macOS-arm64.dmg";
                case "macos-x64" -> "macOS-x64.dmg";
                case "windows-x64" -> "Windows-x64.exe";
                case "linux-x64" -> "Linux-x64.deb";
                default -> "";
            };
            if (!marker.isBlank()) for (Object raw : assets) {
                Map<String, Object> asset = Json.object(raw);
                if (string(asset.get("name")).endsWith(marker)) { downloadUrl = string(asset.get("browser_download_url")); break; }
            }
        }
        return new ReleaseInfo(version, downloadUrl, sha256);
    }

    private void installUpdate(ReleaseInfo release) {
        if (release.downloadUrl().isBlank()) {
            JOptionPane.showMessageDialog(this, "当前系统暂未提供自动更新包。", "自动更新", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (updateButton != null) { updateButton.setEnabled(false); updateButton.setText("正在下载…"); }
        status("正在下载 TokenPro " + release.version() + "…");
        new SwingWorker<Path, Void>() {
            protected Path doInBackground() throws Exception {
                URI uri = URI.create(release.downloadUrl());
                if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalStateException("更新地址不是安全的 HTTPS 链接");
                String suffix = uri.getPath().replaceFirst("^.*(?=\\.)", "");
                Path target = Files.createTempFile("TokenPro-" + release.version() + "-", suffix);
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofMinutes(8)).GET().build();
                HttpResponse<Path> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(request, HttpResponse.BodyHandlers.ofFile(target));
                if (response.statusCode() != 200 || Files.size(target) < 1_000_000) {
                    Files.deleteIfExists(target);
                    throw new IllegalStateException("更新包下载失败（HTTP " + response.statusCode() + "）");
                }
                if (!release.sha256().isBlank() && !release.sha256().equalsIgnoreCase(sha256(target))) {
                    Files.deleteIfExists(target);
                    throw new IllegalStateException("更新包校验失败，已停止安装");
                }
                return target;
            }
            protected void done() {
                try {
                    Path installer = get();
                    status("正在安装 TokenPro " + release.version() + "，程序即将重启…");
                    Updater.install(installer);
                    dispose();
                    System.exit(0);
                } catch (Exception ex) {
                    if (updateButton != null) { updateButton.setEnabled(true); updateButton.setText("重新更新"); }
                    error(ex.getCause() == null ? ex : ex.getCause());
                }
            }
        }.execute();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int compareVersions(String left, String right) {
        int[] a = Arrays.stream(left.split("[^0-9]+" )).filter(s -> !s.isBlank()).mapToInt(Integer::parseInt).toArray();
        int[] b = Arrays.stream(right.split("[^0-9]+" )).filter(s -> !s.isBlank()).mapToInt(Integer::parseInt).toArray();
        for (int i = 0; i < Math.max(a.length, b.length); i++) { int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0; if (x != y) return Integer.compare(x, y); }
        return 0;
    }

    private RoundedPanel card() {
        RoundedPanel panel = new RoundedPanel(22, new Color(23, 40, 82, 222)); panel.setBorder(new EmptyBorder(16, 19, 16, 19));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT); panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 104)); return panel;
    }

    private JButton primary(String text) { JButton button = new ActionButton(text, true); primary(button); return button; }
    private void primary(JButton button) {
        button.setFont(appFont(13, Font.BOLD)); button.setForeground(Color.WHITE); button.setBackground(PURPLE);
        button.setOpaque(false); button.setBorder(new EmptyBorder(10, 17, 10, 17)); button.setFocusPainted(false);
        button.setMaximumSize(button.getPreferredSize());
    }

    private JButton soft(String text) { JButton button = new ActionButton(text, false); soft(button); return button; }
    private void soft(JButton button) {
        button.setFont(appFont(13, Font.BOLD)); button.setForeground(new Color(235, 240, 255)); button.setBackground(new Color(37, 50, 91));
        button.setBorder(new EmptyBorder(9, 15, 9, 15)); button.setFocusPainted(false);
        button.setMaximumSize(button.getPreferredSize());
    }

    private static JPanel transparent() { return transparent(new FlowLayout(FlowLayout.LEFT, 0, 0)); }
    private static JPanel transparent(LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setOpaque(false); return panel; }
    private static Font appFont(float size, int style) { return new Font(Platform.OS_KIND == Platform.OS.MAC ? ".AppleSystemUIFont" : "SansSerif", style, Math.round(size)); }

    private static BufferedImage resourceImage(String name) {
        try (InputStream input = TokenProFrame.class.getResourceAsStream("/assets/" + name)) {
            return input == null ? null : javax.imageio.ImageIO.read(input);
        } catch (Exception ignored) { return null; }
    }

    private static ImageIcon resourceIconContained(String name, int maxWidth, int maxHeight, boolean tintWhite) {
        BufferedImage source = resourceImage(name); if (source == null) return null;
        if (tintWhite) source = tinted(source, new Color(244, 246, 255));
        double scale = Math.min(maxWidth / (double) source.getWidth(), maxHeight / (double) source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale)), height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage oneX = scaled(source, width, height);
        BufferedImage twoX = scaled(source, width * 2, height * 2);
        BufferedImage threeX = scaled(source, width * 3, height * 3);
        return new ImageIcon(new BaseMultiResolutionImage(oneX, twoX, threeX));
    }

    private static BufferedImage scaled(BufferedImage source, int width, int height) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = output.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return output;
    }

    private static BufferedImage tinted(BufferedImage source, Color color) {
        BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int rgb = color.getRGB() & 0x00FFFFFF;
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int alpha = source.getRGB(x, y) >>> 24;
            output.setRGB(x, y, (alpha << 24) | rgb);
        }
        return output;
    }

    private static ImageIcon providerMark(String name, int size) {
        return name.equals("Codex") ? resourceIconContained("OpenAIBlossomRuntime.png", size, size, true) : resourceIconContained("ClaudeSparkRuntime.png", size, size, false);
    }

    private static ImageIcon clientIcon(String name, int size) {
        return resourceIconContained(name.equals("Codex") ? "CodexOriginal.png" : "ClaudeOriginal.png", size, size, false);
    }

    private <T> void async(String running, Callable<T> task, java.util.function.Consumer<T> done) {
        status(running);
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception { return task.call(); }
            protected void done() { try { done.accept(get()); } catch (Exception e) { error(e.getCause() == null ? e : e.getCause()); } }
        }.execute();
    }

    private void browse(String url) {
        if (accessToken == null || accessToken.isBlank()) {
            try { Platform.browse(url); } catch (Exception e) { error(e); }
            return;
        }
        async("正在打开网页…", () -> {
            try { return api.browserLoginUrl(accessToken, url); }
            catch (Exception ignored) { return url; }
        }, target -> {
            try { Platform.browse(target); status("已在系统浏览器打开 TokenPro"); }
            catch (Exception e) { error(e); }
        });
    }
    private void openApp(String app) {
        status("正在打开 " + app + "…");
        new SwingWorker<Boolean, Void>() {
            // Never terminate a running client here. Codex may still be
            // checkpointing the active turn, and restarting it can reopen an
            // older history snapshot. `openApplication` raises the existing
            // single-instance app or starts it when it is not running.
            protected Boolean doInBackground() throws Exception { return Platform.openApplication(app); }
            protected void done() {
                try {
                    if (!get()) throw new IllegalStateException("无法打开 " + app);
                    status(app + " 已打开，当前聊天记录不会被中断");
                } catch (Exception e) { error(e.getCause() == null ? e : e.getCause()); }
            }
        }.execute();
    }
    private void openTerminal(String command) { try { Platform.openTerminalCommand(command); } catch (Exception e) { error(e); } }
    private void status(String value) { status.setText(value); }
    private void error(Throwable error) { status("错误：" + error.getMessage()); JOptionPane.showMessageDialog(this, error.getMessage(), "TokenPro", JOptionPane.ERROR_MESSAGE); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private record KeyItem(long id, String name, String status) { public String toString() { return name + "  [" + status + "]"; } }

    private static JTree modelTree(boolean multiple) {
        JTree tree = new JTree(new DefaultMutableTreeNode("可用分组"));
        tree.setRootVisible(false); tree.setShowsRootHandles(true); tree.setRowHeight(34); tree.setOpaque(true);
        tree.setBackground(new Color(19, 33, 69)); tree.setForeground(TEXT); tree.setCellRenderer(new ModelTreeRenderer());
        tree.getSelectionModel().setSelectionMode(multiple ? TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION : TreeSelectionModel.SINGLE_TREE_SELECTION);
        return tree;
    }

    private static JScrollPane modelScroll(JTree tree, int height) {
        JScrollPane scroll = new JScrollPane(tree); scroll.setPreferredSize(new Dimension(640, height)); scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        scroll.getViewport().setBackground(new Color(19, 33, 69)); scroll.setBorder(BorderFactory.createLineBorder(new Color(156, 176, 235, 82))); return scroll;
    }

    private static int populateModelTree(JTree tree, List<PricedModel> models) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("可用分组");
        Map<Long, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
        int count = 0;
        for (PricedModel item : models) {
            DefaultMutableTreeNode group = groups.computeIfAbsent(item.groupId(), ignored -> {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(new ModelGroupLabel(item.displayGroupName(), item.displayPlatform())); root.add(node); return node;
            });
            group.add(new DefaultMutableTreeNode(item)); count++;
        }
        tree.setModel(new DefaultTreeModel(root));
        if (tree.getRowCount() > 0) tree.expandRow(0);
        return count;
    }

    private static PricedModel selectedModel(JTree tree) {
        TreePath path = tree.getSelectionPath();
        if (path == null) return null;
        Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return value instanceof PricedModel model ? model : null;
    }

    private static List<PricedModel> selectedModels(JTree tree) {
        TreePath[] paths = tree.getSelectionPaths(); if (paths == null) return List.of();
        List<PricedModel> result = new ArrayList<>();
        for (TreePath path : paths) {
            Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (value instanceof PricedModel model) result.add(model);
        }
        return result;
    }

    private record ModelGroupLabel(String name, String platform) {
        public String toString() { return name; }
    }

    private static final class ModelTreeRenderer extends DefaultTreeCellRenderer {
        ModelTreeRenderer() { setOpaque(true); setBorderSelectionColor(null); setBackgroundNonSelectionColor(new Color(19, 33, 69)); setTextNonSelectionColor(new Color(235, 240, 255)); }
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
            JLabel label = (JLabel) super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            Object item = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : value;
            boolean group = item instanceof ModelGroupLabel;
            if (item instanceof PricedModel model) label.setText(model.displayName());
            label.setIcon(null);
            label.setBorder(new EmptyBorder(4, group ? 8 : 14, 4, 10)); label.setFont(appFont(group ? 13 : 12, group ? Font.BOLD : Font.PLAIN));
            label.setBackground(selected ? new Color(76, 67, 148) : new Color(19, 33, 69));
            label.setForeground(group ? new Color(161, 174, 255) : selected ? Color.WHITE : new Color(222, 228, 249));
            return label;
        }
    }

    private static final class CosmosMenuPanel extends JPanel {
        CosmosMenuPanel() {
            setOpaque(false);
            setDoubleBuffered(true);
            setBorder(new EmptyBorder(6, 6, 6, 6));
            setLayout(new GridLayout(2, 1));
        }

        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(20, 34, 70, 250));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.setColor(new Color(144, 164, 235, 58));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.dispose();
        }
    }

    private static final class CosmosMenuButton extends JButton {
        private final boolean restore;

        CosmosMenuButton(String text, boolean restore) {
            super(text);
            this.restore = restore;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setDoubleBuffered(true);
            setFont(appFont(12, Font.BOLD));
            setForeground(restore ? new Color(184, 194, 226) : new Color(241, 244, 255));
            setBorder(new EmptyBorder(0, 14, 0, 14));
            setHorizontalAlignment(SwingConstants.LEFT);
        }

        protected void paintComponent(Graphics graphics) {
            ButtonModel model = getModel();
            if (model.isArmed() || model.isSelected()) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(restore ? new Color(91, 70, 104, 115) : new Color(93, 103, 220, 105));
                g.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 10, 10);
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static final class NavButton extends JButton {
        private boolean selected;
        NavButton(String text) { super(text); setFont(appFont(13, Font.PLAIN)); setForeground(new Color(203, 211, 238)); setHorizontalAlignment(SwingConstants.LEFT); setPreferredSize(new Dimension(200, 44)); setMinimumSize(new Dimension(160, 44)); setMaximumSize(new Dimension(Integer.MAX_VALUE, 44)); setBorder(new EmptyBorder(0, 16, 0, 16)); setFocusPainted(false); setContentAreaFilled(false); }
        public void setSelected(boolean value) { super.setSelected(value); selected = value; setFont(appFont(13, value ? Font.BOLD : Font.PLAIN)); repaint(); }
        protected void paintComponent(Graphics g) { if (selected) { Graphics2D g2 = (Graphics2D) g.create(); g2.setColor(new Color(108, 92, 255, 48)); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12); g2.dispose(); } super.paintComponent(g); }
    }

    private static final class SidebarPanel extends JPanel {
        SidebarPanel() { setOpaque(false); }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create(); g2.setPaint(new GradientPaint(0, 0, new Color(15, 34, 76, 238), getWidth(), getHeight(), new Color(34, 25, 88, 230))); g2.fillRect(0, 0, getWidth(), getHeight()); g2.setColor(new Color(185, 202, 255, 68)); g2.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight()); g2.dispose(); super.paintComponent(g);
        }
    }

    private static final class DashboardBackdrop extends JPanel {
        private final BufferedImage cosmos = resourceImage("LoginCosmos-v2.png");
        DashboardBackdrop() { setOpaque(true); setBackground(CANVAS); }
        protected void paintComponent(Graphics g) {
            super.paintComponent(g); Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (cosmos != null) { double scale = Math.max(getWidth() / (double) cosmos.getWidth(), getHeight() / (double) cosmos.getHeight()); int w = (int) Math.ceil(cosmos.getWidth() * scale), h = (int) Math.ceil(cosmos.getHeight() * scale); g2.setComposite(AlphaComposite.SrcOver.derive(.82f)); g2.drawImage(cosmos, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null); }
            g2.setComposite(AlphaComposite.SrcOver); g2.setPaint(new GradientPaint(0, 0, new Color(24, 52, 108, 48), getWidth(), getHeight(), new Color(49, 31, 112, 72))); g2.fillRect(0, 0, getWidth(), getHeight()); g2.dispose();
        }
    }

    private static class RoundedPanel extends JPanel {
        private final int radius; private final Color fill;
        RoundedPanel(int radius, Color fill) { this.radius = radius; this.fill = fill; setOpaque(false); }
        protected void paintComponent(Graphics g) { Graphics2D g2 = (Graphics2D) g.create(); g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g2.setColor(fill); g2.fill(new RoundRectangle2D.Double(.5, .5, getWidth()-1, getHeight()-1, radius, radius)); g2.setColor(new Color(205,218,255,58)); g2.draw(new RoundRectangle2D.Double(.5, .5, getWidth()-1, getHeight()-1, radius, radius)); g2.dispose(); super.paintComponent(g); }
    }

    private static final class GradientPanel extends JPanel {
        private final BufferedImage cosmos = resourceImage("LoginCosmos-v2.png");
        GradientPanel() { setOpaque(false); }
        protected void paintComponent(Graphics g) { Graphics2D g2 = (Graphics2D) g.create(); if (cosmos != null) { g2.setComposite(AlphaComposite.SrcOver.derive(.58f)); g2.drawImage(cosmos, 0, -cosmos.getHeight() / 4, getWidth(), getHeight() * 2, null); } g2.setComposite(AlphaComposite.SrcOver); g2.setPaint(new GradientPaint(0, 0, new Color(13, 30, 73, 148), getWidth(), getHeight(), new Color(47, 24, 103, 158))); g2.fillRect(0, 0, getWidth(), getHeight()); g2.dispose(); super.paintComponent(g); }
    }

    private static final class ActionButton extends JButton {
        private final boolean prominent;
        ActionButton(String text, boolean prominent) { super(text); this.prominent = prominent; setContentAreaFilled(false); setOpaque(false); setBorderPainted(false); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (prominent && isEnabled()) g.setPaint(new GradientPaint(0, 0, new Color(111, 91, 255), getWidth(), 0, new Color(64, 142, 255)));
            else g.setColor(prominent ? new Color(76, 72, 132, 205) : (isEnabled() ? new Color(39, 53, 96, 235) : new Color(27, 36, 65, 210)));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18); g.setColor(new Color(190, 205, 255, prominent ? 32 : 24)); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            g.setFont(getFont()); g.setColor(isEnabled() ? getForeground() : new Color(137, 145, 177)); FontMetrics fm = g.getFontMetrics(); Icon icon = getIcon(); int textWidth = fm.stringWidth(getText()); int iconWidth = icon == null ? 0 : icon.getIconWidth(); int gap = icon == null || getText().isBlank() ? 0 : getIconTextGap(); int total = iconWidth + gap + textWidth; int x = (getWidth() - total) / 2; if (icon != null) { icon.paintIcon(this, g, x, (getHeight() - icon.getIconHeight()) / 2); x += iconWidth + gap; } g.drawString(getText(), x, (getHeight() - fm.getHeight()) / 2 + fm.getAscent()); g.dispose();
        }
    }
}

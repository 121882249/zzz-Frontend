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
import java.io.OutputStream;
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
import java.util.concurrent.atomic.AtomicBoolean;

final class TokenProFrame extends JFrame {
    record ReleaseInfo(String version, String downloadUrl, String sha256, String incrementalUrl, String incrementalSha256) {
        boolean hasIncrementalUpdate() { return !incrementalUrl.isBlank(); }
        String preferredUrl() { return hasIncrementalUpdate() ? incrementalUrl : downloadUrl; }
        String preferredSha256() { return hasIncrementalUpdate() ? incrementalSha256 : sha256; }
    }
    private static final Color PURPLE = new Color(102, 82, 240);
    private static final Color STATUS_READY = new Color(114, 230, 210);
    private static final Color STATUS_PENDING = new Color(242, 200, 121);
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
    private final GradientTitleLabel headerTitle = new GradientTitleLabel("https://tokenpro.work");
    private final JLabel headerUser = new JLabel("登录账户");
    private final JLabel accountEmail = new JLabel("登录账户");
    private final JLabel headerBalance = new JLabel("—");
    private final JLabel accountBalance = new JLabel("—");
    private final JPanel subscriptionSlot = transparent(new BorderLayout());
    private final JLabel homeClaudeStatus = new ClientStatusLabel("请先选择模型");
    private final JLabel homeCodexStatus = new ClientStatusLabel("请先选择模型");
    private JButton codexLaunch;
    private JButton claudeLaunch;
    private JButton updateButton;
    private JButton refreshAccountButton;
    private final AtomicBoolean updateInProgress = new AtomicBoolean();
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
            getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
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
        shell.add(windowStage(), BorderLayout.CENTER);
        views.show(viewHost, "login");
        return shell;
    }

    private JComponent windowStage() {
        JLayeredPane stage = new JLayeredPane() {
            public void doLayout() {
                viewHost.setBounds(0, 0, getWidth(), getHeight());
            }
        };
        stage.setOpaque(false);
        stage.add(viewHost, JLayeredPane.DEFAULT_LAYER);
        return stage;
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
        JPanel title = transparent(new BorderLayout()); headerTitle.setFont(appFont(23, Font.BOLD)); headerTitle.setIcon(new TechGlobeIcon(30)); headerTitle.setIconTextGap(10); headerTitle.setGradient(true); headerTitle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); headerTitle.setToolTipText("打开 TokenPro 主页"); headerTitle.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent event) { if ("https://tokenpro.work".equals(headerTitle.getText())) browse("https://tokenpro.work"); } }); title.add(headerTitle, BorderLayout.WEST);
        JPanel right = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        updateButton = soft("正在核对版本…"); setUpdateButtonState("checking", "正在核对版本…"); updateButton.addActionListener(e -> checkForUpdates(updateButton)); right.add(updateButton);
        JButton user = soft("登录账户"); user.setIcon(resourceIconContained("CircleUserLucide.png", 17, 17, true)); user.addActionListener(e -> openAccount()); headerUser.addPropertyChangeListener("text", e -> user.setText(headerUser.getText())); right.add(user);
        title.add(right, BorderLayout.EAST); panel.add(title, BorderLayout.NORTH);
        Dimension headerCardSize = new Dimension(430, 64);
        RoundedPanel wallet = new RoundedPanel(20, new Color(22, 38, 78, 228), new Color(75, 190, 151, 145)); wallet.setLayout(new GridBagLayout()); wallet.setBorder(new EmptyBorder(0, 7, 0, 7)); wallet.setPreferredSize(headerCardSize); wallet.setMinimumSize(headerCardSize);
        JPanel walletContent = transparent(new FlowLayout(FlowLayout.CENTER, 14, 0));
        JPanel captions = transparent(); captions.setLayout(new BoxLayout(captions, BoxLayout.Y_AXIS)); JLabel balanceText = new JLabel("钱包余额"); balanceText.setFont(appFont(12, Font.PLAIN)); balanceText.setForeground(MUTED); JLabel rate = new JLabel("充值比例  1￥ = 1$"); rate.setFont(appFont(10, Font.PLAIN)); rate.setForeground(MUTED); captions.add(balanceText); captions.add(rate); walletContent.add(captions);
        headerBalance.setFont(appFont(26, Font.BOLD)); headerBalance.setForeground(new Color(105, 220, 194)); walletContent.add(headerBalance); refreshAccountButton = soft("刷新"); refreshAccountButton.setToolTipText("刷新钱包余额和订阅信息"); refreshAccountButton.setIcon(resourceIconContained("RefreshCwLucide.png", 15, 15, true)); refreshAccountButton.addActionListener(e -> refreshAccount()); walletContent.add(refreshAccountButton); JButton recharge = soft("充值/订阅"); recharge.setIcon(resourceIconContained("PlusLucide.png", 15, 15, true)); recharge.addActionListener(e -> browse("https://tokenpro.work/purchase")); walletContent.add(recharge); wallet.add(walletContent);
        subscriptionSlot.setPreferredSize(headerCardSize); subscriptionSlot.setMinimumSize(headerCardSize);
        JPanel row = transparent(new GridBagLayout());
        GridBagConstraints walletConstraints = new GridBagConstraints(); walletConstraints.gridx = 0; walletConstraints.weightx = 1; walletConstraints.fill = GridBagConstraints.BOTH;
        GridBagConstraints subscriptionConstraints = new GridBagConstraints(); subscriptionConstraints.gridx = 1; subscriptionConstraints.weightx = 1; subscriptionConstraints.fill = GridBagConstraints.BOTH; subscriptionConstraints.insets = new Insets(0, 12, 0, 0);
        row.add(wallet, walletConstraints); row.add(subscriptionSlot, subscriptionConstraints); panel.add(row, BorderLayout.CENTER); return panel;
    }

    private JComponent homePanel() {
        JPanel panel = vertical();
        JPanel heading = transparent(new BorderLayout()); heading.setAlignmentX(Component.LEFT_ALIGNMENT); heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        JLabel title = new JLabel("我的应用"); title.setFont(appFont(15, Font.BOLD)); heading.add(title, BorderLayout.WEST); heading.add(supportedModelBadges(), BorderLayout.EAST); panel.add(heading); panel.add(Box.createVerticalStrut(13));
        panel.add(desktopClientCard("Codex 客户端", "桌面应用 · 独立登录", Platform.applicationInstalled("Codex"), "Codex", () -> chooseModels("Codex"), this::restoreCodex, () -> reconnectApp("Codex"), homeCodexStatus)); panel.add(Box.createVerticalStrut(12));
        panel.add(desktopClientCard("Claude 客户端", "桌面应用 · 独立登录", Platform.applicationInstalled("Claude"), "Claude", () -> chooseModels("Claude"), this::restoreClaude, this::reconnectClaude, homeClaudeStatus)); panel.add(Box.createVerticalStrut(12));
        panel.add(commandClientCard("Codex 命令行", "命令行工具 · Codex CLI", "Codex", "codex", "https://learn.chatgpt.com/docs/codex/cli")); panel.add(Box.createVerticalStrut(12));
        panel.add(commandClientCard("Claude 命令行", "命令行工具 · Claude Code", "Claude", "claude", "https://docs.anthropic.com/en/docs/claude-code/getting-started")); panel.add(Box.createVerticalGlue()); return panel;
    }

    private JComponent supportedModelBadges() {
        JPanel badges = transparent(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        badges.add(new MiniModelBadge(resourceIconContained("OpenAIBlossomRuntime.png", 18, 18, true), new Color(91, 225, 201), "支持 GPT / OpenAI 模型"));
        badges.add(new MiniModelBadge(resourceIconContained("ClaudeSparkRuntime.png", 18, 18, false), new Color(238, 126, 82), "支持 Claude 模型"));
        badges.add(new MiniModelBadge(resourceIconContained("GeminiSparkTransparent.png", 18, 18, false), new Color(107, 145, 255), "支持 Gemini 模型"));
        badges.add(new MiniModelBadge(resourceIconContained("GrokMarkTransparent.png", 18, 18, false), new Color(184, 155, 255), "支持 Grok 模型"));
        badges.add(new MoreModelsBadge());
        return badges;
    }

    private JComponent desktopClientCard(String title, String subtitle, boolean installed, String iconName, Runnable chooseModel, Runnable restore, Runnable open, JLabel state) {
        RoundedPanel card = card(); card.setLayout(new BorderLayout(18, 0));
        JLabel badge = new JLabel(clientIcon(iconName, 48)); badge.setHorizontalAlignment(SwingConstants.CENTER); badge.setPreferredSize(new Dimension(52, 52)); card.add(badge, BorderLayout.WEST);
        JPanel words = transparent(); words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS)); JPanel nameLine = transparent(new FlowLayout(FlowLayout.LEFT, 10, 0)); nameLine.setAlignmentX(Component.LEFT_ALIGNMENT); nameLine.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24)); JLabel heading = new JLabel(title); heading.setFont(appFont(17, Font.BOLD)); JLabel installedLabel = new JLabel(installed ? "已安装" : "未安装"); installedLabel.setFont(appFont(11, Font.BOLD)); installedLabel.setForeground(installed ? new Color(97, 222, 165) : MUTED); nameLine.add(heading); nameLine.add(installedLabel); JLabel detail = new JLabel(subtitle); detail.setAlignmentX(Component.LEFT_ALIGNMENT); detail.setFont(appFont(11, Font.PLAIN)); detail.setForeground(MUTED); words.add(Box.createVerticalStrut(3)); words.add(nameLine); words.add(Box.createVerticalStrut(6)); words.add(detail); card.add(words, BorderLayout.CENTER);
        JPanel actions = transparent(); actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS)); JPanel buttons = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton menu = soft("模型选择  ▾");
        menu.addActionListener(e -> showModelMenu(menu, chooseModel, restore));
        JButton launch = primary("连接 " + iconName + " 客户端"); launch.addActionListener(e -> open.run());
        launch.setEnabled(installed && iconName.equals("Claude"));
        if (iconName.equals("Codex")) codexLaunch = launch; else claudeLaunch = launch;
        buttons.add(menu); buttons.add(launch); actions.add(buttons); state.setFont(appFont(11, Font.BOLD)); state.setAlignmentX(Component.RIGHT_ALIGNMENT); actions.add(Box.createVerticalStrut(7)); actions.add(state); card.add(actions, BorderLayout.EAST); return card;
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
        JPanel buttons = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0)); JButton download = soft(installed ? "已安装" : "去下载"); download.setEnabled(!installed); download.addActionListener(e -> browse(downloadUrl)); JButton terminal = primary("连接 " + (iconName.equals("Codex") ? "Codex 命令行" : "Claude 命令行")); terminal.setEnabled(installed); terminal.addActionListener(e -> openTerminal(command)); buttons.add(download); buttons.add(terminal); card.add(buttons, BorderLayout.EAST); return card;
    }

    private JComponent accountPanel() {
        JPanel panel = vertical();
        JPanel identity = transparent(new BorderLayout(18, 0)); identity.setAlignmentX(Component.LEFT_ALIGNMENT); identity.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
        JLabel avatar = new JLabel(new MarsAvatarIcon(56)); avatar.setHorizontalAlignment(SwingConstants.CENTER); avatar.setPreferredSize(new Dimension(58, 58)); identity.add(avatar, BorderLayout.WEST);
        JPanel identityWords = transparent(); identityWords.setLayout(new BoxLayout(identityWords, BoxLayout.Y_AXIS)); accountEmail.setFont(appFont(14, Font.BOLD)); accountEmail.setAlignmentX(Component.LEFT_ALIGNMENT); JLabel accountType = new JLabel("TokenPro 云端账户"); accountType.setFont(appFont(11, Font.PLAIN)); accountType.setForeground(MUTED); accountType.setAlignmentX(Component.LEFT_ALIGNMENT); identityWords.add(Box.createVerticalStrut(7)); identityWords.add(accountEmail); identityWords.add(Box.createVerticalStrut(5)); identityWords.add(accountType); identity.add(identityWords, BorderLayout.CENTER);
        JButton logout = soft("退出登录"); logout.setIcon(new SignOutIcon(15)); logout.setIconTextGap(8); logout.setPreferredSize(new Dimension(108, 40)); logout.setMaximumSize(logout.getPreferredSize()); logout.addActionListener(e -> logout());
        JPanel logoutSlot = transparent(new GridBagLayout()); logoutSlot.setPreferredSize(new Dimension(118, 68)); logoutSlot.add(logout); identity.add(logoutSlot, BorderLayout.EAST); panel.add(identity); panel.add(Box.createVerticalStrut(18));
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
            account.setText("尚未登录"); headerUser.setText("登录账户"); accountEmail.setText("登录账户"); headerBalance.setText("—"); accountBalance.setText("—"); showSubscriptions(List.of());
            homeCodexStatus.setText("请先选择模型"); homeClaudeStatus.setText("请先选择模型"); if (codexLaunch != null) codexLaunch.setEnabled(false); if (claudeLaunch != null) claudeLaunch.setEnabled(false);
            showPage("首页"); showLoginScreen(); status("已退出账户");
        } catch (Exception ex) { error(ex); }
    }

    private JComponent connectionPanel() {
        JPanel panel = vertical();
        panel.add(pageHeading("选择 Codex 模型", "全局至少选择 1 个模型；生图模型可独立直接生图。"));
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
        selected = uniqueModels(ModelPickerDialog.orderedModels(selected, "Codex"));
        List<PricedModel> chatModels = selected.stream().filter(model -> !model.isImageGeneration()).toList();
        List<PricedModel> imageModels = selected.stream().filter(PricedModel::isImageGeneration).toList();
        if (selected.isEmpty()) { error(new IllegalStateException("请全局至少选择 1 个模型")); return; }
        PricedModel imageModel = imageModels.isEmpty() ? null : imageModels.getFirst();
        List<PricedModel> chosen = selected;
        async("正在使用全局 Key 配置 Codex…", () -> {
            ApiClient.ManagedKey managed = api.globalKey(accessToken);
            codex.apply("https://tokenpro.work/v1", chosen, managed.key(), string(sessionUser.get("email")));
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("default_model", (chatModels.isEmpty() ? imageModel : chatModels.getFirst()).name());
            saved.put("models", modelRows(chosen));
            saved.put("image_model", imageModel == null ? "" : imageModel.name());
            saved.put("image_models", imageModels.stream().map(PricedModel::name).toList());
            saved.put("key_id", managed.id());
            store.write("codex-selected.json", Json.stringify(saved));
            return chosen;
        }, configured -> {
            homeCodexStatus.setText(codexSelectionStatus(chatModels.size(), imageModels.size(), imageModel == null ? "" : imageModel.name()));
            if (codexLaunch != null) codexLaunch.setEnabled(true);
            status("Codex 配置已安全替换，已接入 " + configured.size() + " 个模型");
            reconnectApp("Codex");
        });
    }

    private void restoreCodex() {
        try {
            codex.restore(); store.delete("codex-selected.json"); homeCodexStatus.setText("请先选择模型");
            if (codexLaunch != null) codexLaunch.setEnabled(false);
            status("Codex 已恢复官方配置，正在切回应用…");
            reconnectApp("Codex");
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
        selected = uniqueModels(ModelPickerDialog.orderedModels(selected, "Claude"));
        if (selected.isEmpty()) { error(new IllegalStateException("请至少选择一个模型")); return; }
        List<PricedModel> chosen = selected;
        String accountLabel = string(sessionUser.get("email"));
        async("正在使用全局 Key 配置 Claude…", () -> {
            ApiClient.ManagedKey managed = api.globalKey(accessToken);
            ClaudeBridgeConfig config = ClaudeBridgeConfig.create(accountId, accessToken, managed, chosen);
            config.save(store); ClaudeDesktopConfig.install(store, config, accountLabel); ClaudeBridgeManager.ensureRunning(store); return config;
        }, config -> {
            bridgeStatus.setText("桥接状态：运行中 · " + config.routes().size() + " 个模型 · " + config.baseUrl());
            homeClaudeStatus.setText("已选 " + config.routes().size() + " 个模型");
            if (claudeLaunch != null) claudeLaunch.setEnabled(true);
            status("Claude 已配置 " + config.routes().size() + " 个模型");
            reconnectClaude();
        });
    }

    private void updateBridgeStatus() {
        try { ClaudeBridgeConfig config = ClaudeBridgeConfig.load(store); boolean healthy = ClaudeBridgeManager.healthy(store); bridgeStatus.setText("桥接状态：" + (healthy ? "运行中" : "已配置") + " · " + config.routes().size() + " 个模型"); homeClaudeStatus.setText("已选 " + config.routes().size() + " 个模型"); if (claudeLaunch != null) claudeLaunch.setEnabled(true); }
        catch (Exception e) { bridgeStatus.setText("桥接状态：未配置"); homeClaudeStatus.setText("请先选择模型"); if (claudeLaunch != null) claudeLaunch.setEnabled(Platform.applicationInstalled("Claude")); }
    }

    private void restoreClaude() {
        try {
            ClaudeDesktopConfig.restoreOfficial(store); bridgeStatus.setText("桥接状态：Claude 已恢复官方配置");
            homeClaudeStatus.setText("请先选择模型"); if (claudeLaunch != null) claudeLaunch.setEnabled(false);
            status("Claude 已恢复官方配置，正在切回应用…");
            reconnectApp("Claude");
        }
        catch (Exception ex) { error(ex); }
    }

    private void openClaude() {
        status("正在打开 Claude…");
        String accountLabel = string(sessionUser.get("email"));
        new SwingWorker<ClaudeBridgeConfig, Void>() {
            protected ClaudeBridgeConfig doInBackground() throws Exception {
                try {
                    ClaudeBridgeConfig config = ClaudeBridgeConfig.load(store);
                    ClaudeDesktopConfig.install(store, config, accountLabel);
                    ClaudeBridgeManager.ensureRunning(store);
                    if (!Platform.openClaudeThirdParty()) throw new IllegalStateException("无法打开 Claude");
                    return config;
                } catch (IllegalStateException noConfiguration) {
                    if (noConfiguration.getMessage() != null && noConfiguration.getMessage().contains("请先在 TokenPro 中配置")) {
                        if (!Platform.openApplication("Claude")) throw new IllegalStateException("无法打开 Claude");
                        return null;
                    }
                    throw noConfiguration;
                }
            }
            protected void done() {
                try {
                    ClaudeBridgeConfig config = get();
                    if (config == null) status("Claude 已打开；选择模型后即可使用 TokenPro 连接");
                    else { updateBridgeStatus(); status("Claude 已打开，TokenPro 桥接运行中"); }
                } catch (Exception e) { error(e.getCause() == null ? e : e.getCause()); }
            }
        }.execute();
    }

    private void reconnectClaude() {
        status("正在重新连接 Claude 客户端…");
        String accountLabel = string(sessionUser.get("email"));
        new SwingWorker<ClaudeBridgeConfig, Void>() {
            protected ClaudeBridgeConfig doInBackground() throws Exception {
                ClaudeBridgeConfig config = ClaudeBridgeConfig.load(store);
                ClaudeDesktopConfig.install(store, config, accountLabel);
                ClaudeBridgeManager.stop(store);
                if (!Platform.quitClaudeThirdParty()) throw new IllegalStateException("Claude 客户端未能完全退出");
                ClaudeBridgeManager.ensureRunning(store);
                if (!Platform.openClaudeThirdParty()) throw new IllegalStateException("无法重新连接 Claude 客户端");
                return config;
            }
            protected void done() {
                try {
                    ClaudeBridgeConfig config = get();
                    updateBridgeStatus();
                    status("Claude 客户端已重新连接 · " + config.routes().size() + " 个模型");
                } catch (Exception e) { error(e.getCause() == null ? e : e.getCause()); }
            }
        }.execute();
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
                int imageCount = saved.get("image_models") instanceof List<?> imageModels
                    ? imageModels.size() : imageModel.isBlank() ? 0 : 1;
                int chatCount = Math.max(0, models.size() - imageCount);
                homeCodexStatus.setText(codexSelectionStatus(chatCount, imageCount, imageModel));
            }
            else {
                String selected = string(saved.get("model")); if (selected.isBlank()) throw new IllegalStateException("未选择");
                homeCodexStatus.setText(selected);
            }
            if (codexLaunch != null) codexLaunch.setEnabled(true);
        } catch (Exception ignored) { homeCodexStatus.setText("请先选择模型"); if (codexLaunch != null) codexLaunch.setEnabled(false); }
    }

    private static String codexSelectionStatus(int chatCount, int imageCount, String firstImageModel) {
        if (chatCount > 0 && imageCount > 0) return chatCount + " 个主模型 + " + imageCount + " 个生图模型";
        if (chatCount > 0) return chatCount + " 个主模型";
        if (imageCount > 1) return imageCount + " 个生图模型 · 直接生图";
        if (imageCount == 1) return PricedModel.displayCase(firstImageModel) + " · 直接生图";
        return "请重新选择模型";
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
        String balance = rawBalance instanceof Number number ? String.format(Locale.ROOT, "¥%.2f", number.doubleValue()) : "—";
        account.setText("已登录：" + emailValue + "    余额：" + balance);
        headerUser.setText(emailValue.isBlank() ? "我的账户" : emailValue);
        accountEmail.setText(emailValue.isBlank() ? "我的账户" : emailValue);
        headerBalance.setText(balance);
        accountBalance.setText(balance);
        accountId = string(user.get("id"));
        password.setText("");
        status("就绪");
        try { if (!emailValue.isBlank()) codex.updateActor(emailValue); } catch (Exception ignored) {}
        refreshSubscriptions();
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
        boolean homePage = page.equals("首页");
        headerTitle.setText(homePage ? "https://tokenpro.work" : page);
        headerTitle.setIcon(homePage ? new TechGlobeIcon(30) : null);
        headerTitle.setGradient(homePage);
        headerTitle.setForeground(Color.WHITE);
        headerTitle.setCursor(Cursor.getPredefinedCursor(homePage ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        headerTitle.setToolTipText(homePage ? "打开 TokenPro 主页" : null);
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
        final String token = accessToken;
        if (refreshAccountButton != null) { refreshAccountButton.setEnabled(false); refreshAccountButton.setText("刷新中…"); }
        status("正在刷新钱包余额和订阅…");
        new SwingWorker<Map<String, Object>, Void>() {
            protected Map<String, Object> doInBackground() throws Exception { return api.me(token); }
            protected void done() {
                try {
                    if (!Objects.equals(token, accessToken)) return;
                    showAccount(get());
                    if (refreshAccountButton != null) {
                        refreshAccountButton.setText("已刷新");
                        javax.swing.Timer reset = new javax.swing.Timer(1200, event -> {
                            refreshAccountButton.setText("刷新");
                            refreshAccountButton.setEnabled(true);
                        });
                        reset.setRepeats(false);
                        reset.start();
                    }
                    status("钱包余额和订阅已刷新");
                } catch (Exception exception) {
                    if (refreshAccountButton != null) { refreshAccountButton.setText("重试"); refreshAccountButton.setEnabled(true); }
                    error(exception.getCause() == null ? exception : exception.getCause());
                }
            }
        }.execute();
    }

    private void refreshSubscriptions() {
        if (accessToken == null) { showSubscriptions(List.of()); return; }
        final String token = accessToken;
        new SwingWorker<List<SubscriptionItem>, Void>() {
            protected List<SubscriptionItem> doInBackground() throws Exception {
                Object raw = api.subscriptionSummary(token).get("subscriptions");
                if (!(raw instanceof List<?> rows)) return List.of();
                List<SubscriptionItem> items = new ArrayList<>();
                for (Object value : rows) {
                    Map<String, Object> row = Json.object(value);
                    if (!"active".equalsIgnoreCase(string(row.get("status")))) continue;
                    String group = string(row.get("group_name"));
                    if (group.isBlank()) group = "TokenPro 订阅";
                    items.add(new SubscriptionItem(group, ApiClient.subscriptionRemaining(row), string(row.get("expires_at"))));
                }
                items.sort(Comparator.comparingDouble(SubscriptionItem::remaining).reversed()
                    .thenComparing(SubscriptionItem::name, String.CASE_INSENSITIVE_ORDER));
                return List.copyOf(items);
            }
            protected void done() {
                if (!Objects.equals(token, accessToken)) return;
                try { showSubscriptions(get()); }
                catch (Exception ignored) { showSubscriptions(List.of()); }
            }
        }.execute();
    }

    private void showSubscriptions(List<SubscriptionItem> subscriptions) {
        subscriptionSlot.removeAll();
        if (!subscriptions.isEmpty()) {
            RoundedPanel card = new RoundedPanel(20, new Color(31, 39, 67, 238), new Color(190, 143, 48, 155));
            card.setLayout(new GridBagLayout());
            JPanel content = transparent(new BorderLayout(12, 0));
            JLabel title = new JLabel("订阅 " + subscriptions.size() + " 个");
            title.setFont(appFont(12, Font.BOLD));
            title.setForeground(new Color(226, 190, 105));
            content.add(title, BorderLayout.WEST);
            if (subscriptions.size() == 1) {
                JLabel value = subscriptionLabel(subscriptions.getFirst(), false);
                value.setHorizontalAlignment(SwingConstants.CENTER);
                content.add(value, BorderLayout.CENTER);
            } else {
                JButton picker = new SubscriptionPickerButton(subscriptions.getFirst().displayText() + "  ▾");
                picker.setToolTipText("按订阅余额从高到低排列");
                picker.addActionListener(event -> showSubscriptionMenu(picker, subscriptions));
                content.add(picker, BorderLayout.CENTER);
            }
            GridBagConstraints centered = new GridBagConstraints();
            centered.weightx = 1; centered.fill = GridBagConstraints.HORIZONTAL; centered.insets = new Insets(0, 14, 0, 14);
            card.add(content, centered);
            subscriptionSlot.add(card, BorderLayout.CENTER);
        }
        subscriptionSlot.revalidate();
        subscriptionSlot.repaint();
    }

    private JLabel subscriptionLabel(SubscriptionItem item, boolean listCell) {
        JLabel label = new JLabel(item.displayText());
        label.setFont(appFont(12, Font.BOLD));
        label.setForeground(new Color(241, 218, 161));
        label.setOpaque(false);
        label.setBorder(new EmptyBorder(5, 7, 5, 7));
        return label;
    }

    private void showSubscriptionMenu(JButton anchor, List<SubscriptionItem> subscriptions) {
        hideModelMenu();
        JLayeredPane layered = getLayeredPane();
        JPanel overlay = new JPanel(null);
        overlay.setOpaque(false);
        overlay.setBounds(0, 0, layered.getWidth(), layered.getHeight());
        overlay.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { hideModelMenu(); }
        });
        SubscriptionMenuPanel menu = new SubscriptionMenuPanel(subscriptions.size());
        for (SubscriptionItem item : subscriptions) {
            SubscriptionMenuButton option = new SubscriptionMenuButton(item.displayText());
            option.addActionListener(event -> {
                anchor.setText(item.displayText() + "  ▾");
                hideModelMenu();
            });
            menu.add(option);
        }
        FontMetrics metrics = anchor.getFontMetrics(appFont(11, Font.BOLD));
        int desiredWidth = subscriptions.stream().mapToInt(item -> metrics.stringWidth(item.displayText())).max().orElse(176) + 36;
        int width = Math.min(Math.max(210, desiredWidth), layered.getWidth() - 16);
        int height = subscriptions.size() * 40 + 12;
        Point point = SwingUtilities.convertPoint(anchor, 0, anchor.getHeight() + 6, layered);
        int x = Math.max(8, Math.min(point.x, layered.getWidth() - width - 8));
        int y = Math.max(8, Math.min(point.y, layered.getHeight() - height - 8));
        menu.setBounds(x, y, width, height);
        overlay.add(menu);
        activeModelMenuOverlay = overlay;
        layered.add(overlay, JLayeredPane.POPUP_LAYER);
        overlay.revalidate();
        overlay.repaint();
    }

    private void checkForUpdates(JButton button) {
        checkForUpdates(button, false);
    }

    private void checkForUpdates(JButton button, boolean automatic) {
        if (button != null) { button.setEnabled(false); setUpdateButtonState("checking", "检查中…"); }
        if (!automatic) status("正在检查更新…");
        new SwingWorker<ReleaseInfo, Void>() {
            protected ReleaseInfo doInBackground() throws Exception {
                String payload = "";
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create("https://tokenpro.work/downloads/latest/release-v2.json"))
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
                return payload.isBlank() ? new ReleaseInfo("", "", "", "", "") : releaseForPlatform(payload, Updater.platformKey());
            }
            protected void done() {
                if (button != null) button.setEnabled(true);
                try {
                    ReleaseInfo release = get();
                    if (release.version().isBlank()) {
                        setUpdateButtonState("check", "重新核对版本");
                        if (!automatic) {
                            status("暂时无法读取版本信息，请稍后重试");
                        }
                    } else if (compareVersions(release.version(), Main.VERSION) > 0) {
                        setUpdateButtonState("check", "发现更新 v" + release.version());
                        status("发现新版本 " + release.version());
                        if (!automatic) installUpdate(release);
                    } else {
                        setUpdateButtonState("latest", "已是最新 v" + Main.VERSION);
                        if (!automatic) status("当前已是最新版本 " + Main.VERSION);
                    }
                } catch (Exception ex) {
                    setUpdateButtonState("check", "重新核对版本");
                    if (!automatic) error(ex.getCause() == null ? ex : ex.getCause());
                }
            }
        }.execute();
    }

    private void setUpdateButtonState(String state, String text) {
        if (updateButton == null) return;
        updateButton.putClientProperty("tokenpro.updateState", state);
        updateButton.setText(text);
        boolean latest = "latest".equals(state);
        boolean checking = "checking".equals(state);
        updateButton.setEnabled(!latest && !checking);
        updateButton.setCursor(latest || checking ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        updateButton.setIcon(latest ? null : resourceIconContained("RefreshCwLucide.png", 15, 15, true));
        updateButton.setToolTipText(latest ? "当前已更新至最新版本" : checking ? "正在核对云端版本" : "点击在线更新至最新版本");
        updateButton.repaint();
    }

    static ReleaseInfo releaseForPlatform(String payload, String platformKey) {
        Map<String, Object> release = Json.object(Json.parse(payload));
        String version = string(release.get("tag_name")).replaceFirst("^v", "");
        String downloadUrl = "", sha256 = "", incrementalUrl = "", incrementalSha256 = "";
        if (release.get("incremental") instanceof Map<?, ?> rawIncremental) {
            Map<String, Object> incremental = Json.object(rawIncremental);
            incrementalUrl = string(incremental.get("url"));
            incrementalSha256 = string(incremental.get("sha256"));
        }
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
        if (incrementalUrl.isBlank() && release.get("assets") instanceof List<?> assets) {
            for (Object raw : assets) {
                Map<String, Object> asset = Json.object(raw);
                if (string(asset.get("name")).endsWith("-update.jar")) {
                    incrementalUrl = string(asset.get("browser_download_url"));
                    break;
                }
            }
        }
        return new ReleaseInfo(version, downloadUrl, sha256, incrementalUrl, incrementalSha256);
    }

    private void installUpdate(ReleaseInfo release) {
        if (!updateInProgress.compareAndSet(false, true)) return;
        if (release.preferredUrl().isBlank()) {
            updateInProgress.set(false);
            setUpdateButtonState("check", "重新核对版本");
            status("当前系统暂未提供自动更新包");
            return;
        }
        UpdateProgressDialog progress = new UpdateProgressDialog(this, release.version());
        if (updateButton != null) updateButton.setEnabled(false);
        setUpdateButtonState("checking", "正在更新 0%");
        status("正在下载 TokenPro " + release.version() + "…");
        progress.setVisible(true);
        new SwingWorker<Path, Integer>() {
            protected Path doInBackground() throws Exception {
                URI uri = URI.create(release.preferredUrl());
                if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalStateException("更新地址不是安全的 HTTPS 链接");
                String suffix = uri.getPath().replaceFirst("^.*(?=\\.)", "");
                Path target = Files.createTempFile("TokenPro-" + release.version() + "-", suffix);
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofMinutes(8)).GET().build();
                HttpResponse<InputStream> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    Files.deleteIfExists(target);
                    throw new IllegalStateException("更新包下载失败（HTTP " + response.statusCode() + "）");
                }
                long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                long downloaded = 0;
                try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int read; (read = input.read(buffer)) >= 0;) {
                        if (read == 0) continue;
                        output.write(buffer, 0, read);
                        downloaded += read;
                        if (total > 0) publish((int) Math.min(99, downloaded * 100 / total));
                    }
                }
                long minimumSize = release.hasIncrementalUpdate() ? 50_000 : 1_000_000;
                if (Files.size(target) < minimumSize) {
                    Files.deleteIfExists(target);
                    throw new IllegalStateException("更新包下载不完整");
                }
                publish(100);
                if (!release.preferredSha256().isBlank() && !release.preferredSha256().equalsIgnoreCase(sha256(target))) {
                    Files.deleteIfExists(target);
                    throw new IllegalStateException("更新包校验失败，已停止安装");
                }
                return target;
            }
            protected void process(List<Integer> chunks) {
                int value = chunks.get(chunks.size() - 1);
                progress.updateProgress(value);
                setUpdateButtonState("checking", "正在更新 " + value + "%");
            }
            protected void done() {
                try {
                    Path installer = get();
                    progress.installing();
                    status("正在安装 TokenPro " + release.version() + "，程序即将重启…");
                    if (release.hasIncrementalUpdate()) Updater.installIncremental(installer, release.version());
                    else Updater.install(installer);
                    dispose();
                    System.exit(0);
                } catch (Exception ex) {
                    updateInProgress.set(false);
                    progress.dispose();
                    if (updateButton != null) updateButton.setEnabled(true);
                    setUpdateButtonState("check", "更新失败，点击重试");
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    status("自动更新失败：" + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
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
    private void reconnectApp(String app) {
        status("正在重新连接 " + app + " 客户端…");
        new SwingWorker<Boolean, Void>() {
            protected Boolean doInBackground() throws Exception { return Platform.reconnectApplication(app); }
            protected void done() {
                try {
                    if (!get()) throw new IllegalStateException("无法重新连接 " + app + " 客户端");
                    status(app + " 客户端已重新连接");
                } catch (Exception e) { error(e.getCause() == null ? e : e.getCause()); }
            }
        }.execute();
    }
    private void openTerminal(String command) { try { Platform.openTerminalCommand(command); } catch (Exception e) { error(e); } }
    private void status(String value) { status.setText(value); }
    private void error(Throwable error) { status("错误：" + error.getMessage()); JOptionPane.showMessageDialog(this, error.getMessage(), "TokenPro", JOptionPane.ERROR_MESSAGE); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private record KeyItem(long id, String name, String status) { public String toString() { return name + "  [" + status + "]"; } }
    private record SubscriptionItem(String name, double remaining, String expiresAt) {
        String displayText() { return name + "   订阅余额 $" + String.format(Locale.US, "%.2f", remaining) + "   " + expiryText(); }
        String expiryText() { return PricedModel.expiryLabel(expiresAt); }
        public String toString() { return displayText(); }
    }

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

    private static final class MiniModelBadge extends JLabel {
        private final Color glow;

        MiniModelBadge(Icon icon, Color glow, String tooltip) {
            super(icon, SwingConstants.CENTER);
            this.glow = glow;
            setToolTipText(tooltip);
            setPreferredSize(new Dimension(32, 28));
            setMinimumSize(getPreferredSize());
            setOpaque(false);
        }

        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(2, 2, new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 118), getWidth(), getHeight(), new Color(39, 45, 99, 165)));
            g.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 13, 13);
            g.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 185));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 13, 13);
            g.setColor(new Color(255, 255, 255, 190));
            g.fillOval(getWidth() - 7, 4, 2, 2);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class MoreModelsBadge extends JLabel {
        MoreModelsBadge() {
            super("✦  更多模型", SwingConstants.CENTER);
            setFont(appFont(10, Font.BOLD));
            setForeground(new Color(211, 219, 255));
            setToolTipText("更多模型持续接入");
            setPreferredSize(new Dimension(86, 28));
            setMinimumSize(getPreferredSize());
            setOpaque(false);
        }

        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(79, 96, 182, 150), getWidth(), getHeight(), new Color(91, 61, 153, 155)));
            g.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 14, 14);
            g.setColor(new Color(179, 191, 255, 145));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 14, 14);
            g.dispose();
            super.paintComponent(graphics);
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
            setFont(appFont(11, Font.BOLD));
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

    private static final class SubscriptionMenuPanel extends JPanel {
        SubscriptionMenuPanel(int count) {
            setOpaque(false);
            setBorder(new EmptyBorder(6, 6, 6, 6));
            setLayout(new GridLayout(Math.max(1, count), 1, 0, 2));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(31, 38, 64, 252));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.setColor(new Color(190, 143, 48, 150));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.dispose();
        }
    }

    private static class SubscriptionPickerButton extends JButton {
        SubscriptionPickerButton(String text) {
            super(text);
            setFont(appFont(11, Font.BOLD));
            setForeground(new Color(244, 220, 164));
            setBorder(new EmptyBorder(8, 13, 8, 13));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override protected void paintComponent(Graphics graphics) {
            Font candidate = appFont(11, Font.BOLD);
            int available = Math.max(40, getWidth() - 22);
            while (candidate.getSize2D() > 8f && getFontMetrics(candidate).stringWidth(getText()) > available) {
                candidate = candidate.deriveFont(candidate.getSize2D() - .5f);
            }
            if (!candidate.equals(getFont())) setFont(candidate);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getModel().isRollover() ? new Color(106, 78, 29, 225) : new Color(82, 62, 29, 218));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g.setColor(new Color(202, 155, 64, 155));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class SubscriptionMenuButton extends SubscriptionPickerButton {
        SubscriptionMenuButton(String text) {
            super(text);
            setHorizontalAlignment(SwingConstants.LEFT);
            setBorder(new EmptyBorder(0, 12, 0, 12));
        }
    }

    private static final class NavButton extends JButton {
        private boolean selected;
        NavButton(String text) { super(text); setFont(appFont(13, Font.PLAIN)); setForeground(new Color(203, 211, 238)); setHorizontalAlignment(SwingConstants.LEFT); setPreferredSize(new Dimension(200, 44)); setMinimumSize(new Dimension(160, 44)); setMaximumSize(new Dimension(Integer.MAX_VALUE, 44)); setBorder(new EmptyBorder(0, 16, 0, 16)); setFocusPainted(false); setContentAreaFilled(false); }
        public void setSelected(boolean value) { super.setSelected(value); selected = value; setFont(appFont(13, value ? Font.BOLD : Font.PLAIN)); repaint(); }
        protected void paintComponent(Graphics g) { if (selected) { Graphics2D g2 = (Graphics2D) g.create(); g2.setColor(new Color(108, 92, 255, 48)); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12); g2.dispose(); } super.paintComponent(g); }
    }

    private static final class MarsAvatarIcon implements Icon {
        private final int size;
        MarsAvatarIcon(int size) { this.size = size; }
        public int getIconWidth() { return size; }
        public int getIconHeight() { return size; }
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            double scale = size / 56d;
            g.scale(scale, scale);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            g.setPaint(new RadialGradientPaint(28, 28, 27, new float[]{0f, .72f, 1f},
                    new Color[]{new Color(43, 42, 108), new Color(16, 29, 69), new Color(7, 15, 40)}));
            g.fillOval(1, 1, 54, 54);
            g.setColor(new Color(116, 112, 255, 190));
            g.setStroke(new BasicStroke(1.5f));
            g.drawOval(1, 1, 54, 54);

            g.setColor(new Color(142, 126, 255, 185));
            g.setStroke(new BasicStroke(1.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawArc(7, 20, 42, 22, 197, 142);
            g.setColor(new Color(255, 202, 154, 225));
            g.fillOval(45, 31, 3, 3);

            g.setPaint(new RadialGradientPaint(23, 20, 25, new float[]{0f, .62f, 1f},
                    new Color[]{new Color(255, 174, 112), new Color(224, 91, 67), new Color(120, 43, 55)}));
            g.fillOval(12, 11, 33, 33);
            g.setColor(new Color(255, 190, 135, 180));
            g.setStroke(new BasicStroke(1f));
            g.drawOval(12, 11, 33, 33);

            g.setColor(new Color(140, 51, 57, 150));
            g.fillOval(20, 18, 7, 5);
            g.fillOval(31, 29, 8, 6);
            g.fillOval(18, 33, 5, 4);
            g.setColor(new Color(255, 202, 151, 115));
            g.fillOval(22, 19, 3, 2);
            g.fillOval(33, 30, 4, 2);

            g.setColor(new Color(197, 185, 255, 235));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawArc(6, 20, 43, 22, 17, 145);
            g.fillOval(8, 17, 3, 3);
            g.dispose();
        }
    }

    private static final class SignOutIcon implements Icon {
        private final int size;
        SignOutIcon(int size) { this.size = size; }
        public int getIconWidth() { return size; }
        public int getIconHeight() { return size; }
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(component.isEnabled() ? new Color(235, 240, 255) : new Color(137, 145, 177));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int middle = size / 2;
            g.drawLine(1, 2, 1, size - 3);
            g.drawLine(1, 2, size / 2, 2);
            g.drawLine(1, size - 3, size / 2, size - 3);
            g.drawLine(size / 3, middle, size - 2, middle);
            g.drawLine(size - 5, middle - 3, size - 2, middle);
            g.drawLine(size - 5, middle + 3, size - 2, middle);
            g.dispose();
        }
    }

    private static final class TechGlobeIcon implements Icon {
        private final int size;

        TechGlobeIcon(int size) { this.size = size; }
        public int getIconWidth() { return size; }
        public int getIconHeight() { return size; }

        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float stroke = Math.max(1.2f, size / 18f);
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setPaint(new LinearGradientPaint(2, 2, size - 2, size - 2,
                new float[]{0f, .48f, 1f},
                new Color[]{new Color(101, 233, 211), new Color(74, 159, 255), new Color(178, 101, 255)}));
            int pad = Math.max(2, Math.round(stroke));
            int diameter = size - pad * 2;
            g.drawOval(pad, pad, diameter, diameter);
            g.drawOval(size / 3, pad, size / 3, diameter);
            g.drawArc(pad, size / 4, diameter, size / 2, 0, 360);
            g.drawLine(pad + 2, size / 2, size - pad - 2, size / 2);
            int node = Math.max(3, size / 8);
            g.fillOval(size - pad - node, size / 2 - node / 2, node, node);
            g.fillOval(size / 2 - node / 2, pad - node / 3, node, node);
            g.dispose();
        }
    }

    private static final class GradientTitleLabel extends JLabel {
        private boolean gradient;

        GradientTitleLabel(String text) { super(text); }

        void setGradient(boolean gradient) {
            this.gradient = gradient;
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            if (!gradient) {
                super.paintComponent(graphics);
                return;
            }
            Color foreground = getForeground();
            setForeground(new Color(0, 0, 0, 0));
            super.paintComponent(graphics);
            setForeground(foreground);

            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Insets insets = getInsets();
            Icon icon = getIcon();
            int textX = insets.left + (icon == null ? 0 : icon.getIconWidth() + getIconTextGap());
            FontMetrics metrics = g.getFontMetrics(getFont());
            int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g.setFont(getFont());
            g.setPaint(new LinearGradientPaint(textX, 0, Math.max(textX + 1, getWidth()), 0,
                new float[]{0f, .42f, .72f, 1f},
                new Color[]{new Color(104, 229, 205), new Color(92, 164, 255), new Color(153, 105, 255), new Color(230, 151, 255)}));
            g.drawString(getText(), textX, baseline);
            g.dispose();
        }
    }

    private static final class ClientStatusLabel extends JLabel {
        ClientStatusLabel(String text) { super(); setText(text); }
        public void setText(String text) {
            super.setText(text);
            setForeground(text != null && text.startsWith("请") ? STATUS_PENDING : STATUS_READY);
        }
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
        private final int radius; private final Color fill; private final Color stroke;
        RoundedPanel(int radius, Color fill) { this(radius, fill, new Color(205, 218, 255, 58)); }
        RoundedPanel(int radius, Color fill, Color stroke) { this.radius = radius; this.fill = fill; this.stroke = stroke; setOpaque(false); }
        protected void paintComponent(Graphics g) { Graphics2D g2 = (Graphics2D) g.create(); g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g2.setColor(fill); g2.fill(new RoundRectangle2D.Double(.5, .5, getWidth()-1, getHeight()-1, radius, radius)); g2.setColor(stroke); g2.draw(new RoundRectangle2D.Double(.5, .5, getWidth()-1, getHeight()-1, radius, radius)); g2.dispose(); super.paintComponent(g); }
    }

    private static final class UpdateProgressDialog extends JDialog {
        private final JLabel detail = new JLabel("正在安全下载更新…", SwingConstants.CENTER);
        private final CosmosProgressBar bar = new CosmosProgressBar();

        UpdateProgressDialog(JFrame owner, String version) {
            super(owner, "TokenPro 自动更新", false);
            setUndecorated(true);
            setBackground(new Color(0, 0, 0, 0));
            RoundedPanel panel = new RoundedPanel(24, new Color(17, 31, 68, 248), new Color(115, 104, 255, 145));
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(new EmptyBorder(22, 25, 22, 25));
            JLabel title = new JLabel("正在更新 TokenPro " + version, SwingConstants.CENTER);
            title.setFont(appFont(16, Font.BOLD)); title.setForeground(TEXT); title.setAlignmentX(Component.CENTER_ALIGNMENT);
            detail.setFont(appFont(11, Font.PLAIN)); detail.setForeground(MUTED); detail.setAlignmentX(Component.CENTER_ALIGNMENT);
            bar.setAlignmentX(Component.CENTER_ALIGNMENT); bar.setPreferredSize(new Dimension(370, 14)); bar.setMaximumSize(new Dimension(370, 14));
            panel.add(title); panel.add(Box.createVerticalStrut(10)); panel.add(detail); panel.add(Box.createVerticalStrut(14)); panel.add(bar);
            setContentPane(panel); setSize(430, 142); setLocationRelativeTo(owner); setAlwaysOnTop(true);
        }

        void updateProgress(int value) { bar.setValue(value); detail.setText("正在下载 · " + value + "%"); }
        void installing() { bar.setValue(100); detail.setText("校验完成，正在安装并重启…"); }
    }

    private static final class CosmosProgressBar extends JProgressBar {
        CosmosProgressBar() { super(0, 100); setOpaque(false); setBorderPainted(false); setStringPainted(false); }
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(43, 53, 96, 220)); g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            int width = (int) Math.round(getWidth() * getPercentComplete());
            if (width > 0) {
                g.setPaint(new GradientPaint(0, 0, new Color(104, 229, 205), getWidth(), 0, new Color(153, 105, 255)));
                g.fillRoundRect(0, 0, width, getHeight(), getHeight(), getHeight());
            }
            g.dispose();
        }
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
            String updateState = String.valueOf(getClientProperty("tokenpro.updateState"));
            Color stroke = new Color(190, 205, 255, prominent ? 32 : 24);
            if ("latest".equals(updateState)) {
                g.setPaint(new GradientPaint(0, 0, new Color(47, 117, 104, 205), getWidth(), 0, new Color(63, 145, 119, 205)));
                stroke = new Color(132, 230, 196, 135);
            } else if ("check".equals(updateState)) {
                g.setPaint(new GradientPaint(0, 0, new Color(99, 72, 24, 235), getWidth(), 0, new Color(137, 99, 30, 235)));
                stroke = new Color(242, 200, 121, 160);
            } else if (prominent && isEnabled()) g.setPaint(new GradientPaint(0, 0, new Color(111, 91, 255), getWidth(), 0, new Color(64, 142, 255)));
            else g.setColor(prominent ? new Color(76, 72, 132, 205) : (isEnabled() ? new Color(39, 53, 96, 235) : new Color(27, 36, 65, 210)));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18); g.setColor(stroke); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            g.setFont(getFont()); g.setColor("latest".equals(updateState) ? new Color(218, 248, 239) : (isEnabled() ? getForeground() : new Color(137, 145, 177))); FontMetrics fm = g.getFontMetrics(); Icon icon = getIcon(); int textWidth = fm.stringWidth(getText()); int iconWidth = icon == null ? 0 : icon.getIconWidth(); int gap = icon == null || getText().isBlank() ? 0 : getIconTextGap(); int total = iconWidth + gap + textWidth; int x = (getWidth() - total) / 2; if (icon != null) { icon.paintIcon(this, g, x, (getHeight() - icon.getIconHeight()) / 2); x += iconWidth + gap; } g.drawString(getText(), x, (getHeight() - fm.getHeight()) / 2 + fm.getAscent()); g.dispose();
        }
    }
}

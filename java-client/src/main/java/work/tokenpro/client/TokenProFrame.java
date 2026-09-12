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
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
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
    private static final Color STATUS_OFFICIAL = new Color(121, 181, 255);
    private static final Color STATUS_PENDING = new Color(242, 200, 121);
    private static final Color CANVAS = new Color(11, 20, 47);
    private static final Color TEXT = new Color(242, 245, 255);
    private static final Color MUTED = new Color(181, 191, 220);
    // Lucide icons are embedded so the class-only online updater can ship the
    // selected menu design without forcing users to download a full installer.
    private static final String PRICE_MENU_ICON = "iVBORw0KGgoAAAANSUhEUgAAABgAAAAYCAYAAADgdz34AAAAAXNSR0IArs4c6QAAAERlWElmTU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAGKADAAQAAAABAAAAGAAAAADiNXWtAAAB50lEQVRIDc2VTy4EQRSHhQUrRIIFdpxAcAcRsRDiDuLfMQjnsDFOgAgLxEJE4gIsWIr4F8L3zXS1mtY9BhF+yTf1+r161VWvumoaGv6JxpjHFtwkaI/Cj9RB9jCswGsBy/hHwL51q5eeJXiGMLD2EjiQLMIjhPgL9ib0QE3Z4RJMfIAjOIEZyGoKh7FDuAdzzO2DQjlzOx5AV2Gvj4HuJMdcx8iVS3epzsaEr8oJuWrL6VhlNQaDdgB8PoUrCNrHOIbO4EhsfXuR7xrb3CboD/74BdZUNVea9LcFaxB2wJeItj5jsULuZOzUHgfr5xKzQQc8S+LntGJfffGqeCx/DOHr8+yk2sUyaTb1VBvxS4oGDxlLGPbxMKa6w9LZmnreDffAWB7xHoQMN9i+NzriPfD51xRKNPfJG8IqanXzlNuvqkQTidMNmoYiffYCv8RwhXy4DNcIOoDHP9ZX9sBcx/ASLCveg/XE95S032mcvdqoNNW/7r4l8ix47PNUq0ReL/fgdeNYuSrhdRAvOxOyslx5n6YTMsdcxyhUH5ELsKOz8Sq2rm5eVtM4jHmlu2pzzK3rP8FZuFSTxNrOQzu0wQKEK8G4tnXvhbplHUfALyK8KNuuEhuCwpoTq0teXNtwm7BDOw5/rzdHF5tbXbfP4QAAAABJRU5ErkJggg==";
    private static final String REPAIR_MENU_ICON = "iVBORw0KGgoAAAANSUhEUgAAABgAAAAYCAYAAADgdz34AAAAAXNSR0IArs4c6QAAAERlWElmTU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAGKADAAQAAAABAAAAGAAAAADiNXWtAAABjElEQVRIDc2UvS4FURSFT1CRaHCjHIlEodRpjFbvvoROhER3wyt4BT1Rk1AqEELhDSQSEXR+1nftIzsnk5kxQ3JXsmafM7PP2n8zE8IAoqOcdsUr8c14KdsVW2NVCk/iZwEf26qT4YcJH8guiZl4KxLwTGyMjk4+iwhtmcqU7I3dw+LTGPQc8UNTGJctEifoqUi7qLg2GCIBcjuxbXufORXEoPhe4DvEpQZmzefcLAIRrBE/FufFOxHMfZt611e5ITRm7mmLYubYGfPlTG3EFi27E2lLEGcGufjTohFtqsChUXNakD2x9YPsorhm+z1Z3rR12x+ZLTWI+/InS71D2NRzsudjnK7w7Q/Oi9MSQJANMReZCdWx5uND/F3siqUg82uRAwSJ4r4inqUk80pxxIoy9/fv5eN/dqx7YmVbyjL3FeH3a/yr+ITS8RkW9Zy2NcqcUvfFdKC+563ECfBiATI2Apn6QTfOvK+mC18lFayImfin4tILO2L6TrduC8IRw1oQhEpoFzNh8IOPL+Uji4g/TJCWAAAAAElFTkSuQmCC";
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
    private final JLabel homeCodexCliStatus = new ClientStatusLabel("请先选择模型");
    private final JLabel homeClaudeCliStatus = new ClientStatusLabel("请先选择模型");
    private final SupportCountLabel homeCodexCliSupport = new SupportCountLabel();
    private final SupportCountLabel homeClaudeCliSupport = new SupportCountLabel();
    private volatile boolean codexClientInstalled;
    private volatile boolean claudeClientInstalled;
    private volatile boolean codexCliInstalled;
    private volatile boolean claudeCliInstalled;
    private boolean codexCanConnect;
    private boolean claudeCanConnect;
    private final SupportCountLabel homeCodexSupport = new SupportCountLabel();
    private final SupportCountLabel homeClaudeSupport = new SupportCountLabel();
    private final PremiumModelTicker premiumModelTicker = new PremiumModelTicker();
    private JLabel codexClientInstallLabel;
    private JLabel claudeClientInstallLabel;
    private JLabel codexCliInstallLabel;
    private JLabel claudeCliInstallLabel;
    private JButton codexClientScanButton;
    private JButton claudeClientScanButton;
    private JButton codexCliScanButton;
    private JButton claudeCliScanButton;
    private JButton codexModelMenuButton;
    private JButton claudeModelMenuButton;
    private JButton codexCliModelMenuButton;
    private JButton claudeCliModelMenuButton;
    private JButton codexLaunch;
    private JButton claudeLaunch;
    private JButton codexCliLaunch;
    private JButton claudeCliLaunch;
    private JButton updateButton;
    private JButton refreshAccountButton;
    private volatile ReleaseInfo availableUpdate;
    private final AtomicBoolean updateInProgress = new AtomicBoolean();
    private final AtomicBoolean installationScanInProgress = new AtomicBoolean();
    private final AtomicBoolean accountRefreshInProgress = new AtomicBoolean();
    private final ConnectionGate connectingClients = new ConnectionGate();
    private final Map<String,BrowserOpenGate> browserOpenGates = BrowserOpenGate.independentGates();
    private final List<JButton> guardedWebButtons = new ArrayList<>();
    private final javax.swing.Timer webLinkTimer = new javax.swing.Timer(250, e -> updateWebButtons());
    private long lastAccountRefresh;
    private volatile boolean installationScanCompleted;
    private final Set<String> knownInstallationTargets = new HashSet<>();
    private boolean installationScanFailed;
    private volatile long lastInstallationScanAtNanos;
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
        this(store, true);
    }

    TokenProFrame(SecureStore store, boolean initializeServices) {
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
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { webLinkTimer.stop(); }
        });
        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent event) {
                refreshInstallationState();
                if (initializeServices) refreshAccountSilently();
            }
        });
        if (!initializeServices) return;
        refreshInstallationState();
        restoreSession();
        new SwingWorker<Boolean, Void>() {
            protected Boolean doInBackground() throws Exception {
                // An older release may already have marked official mode after
                // removing the `custom` provider. Repair that upgrade path on
                // startup so the user does not have to switch away and back.
                boolean repairedOfficialHistory = codexOfficialMode() && codex.restore();
                BridgeLifecycle.resumeConfigured(store);
                return repairedOfficialHistory;
            }
            protected void done() {
                try {
                    if (get()) status("Codex 官方配置已自动修复；请重新打开旧对话");
                } catch (Exception e) { status("部分本机连接未恢复，请在相应卡片点击连接重试"); }
            }
        }.execute();
        javax.swing.Timer updateTimer = new javax.swing.Timer(2500, e -> checkForUpdates(null, true));
        updateTimer.setRepeats(false);
        updateTimer.start();
        javax.swing.Timer walletTimer = new javax.swing.Timer(30000, e -> refreshAccountSilently());
        walletTimer.start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { walletTimer.stop(); }
        });
    }

    private JComponent content() {
        CosmosLoginPanel.Backdrop shell = new CosmosLoginPanel.Backdrop();
        viewHost.setOpaque(false);
        loginView = new CosmosLoginPanel(email, password, e -> authenticate(), e -> updateFromButton());
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
        JButton backend = sideAction("后台管理", "WebCog.png"); guardWebButton(backend, "admin"); backend.addActionListener(e -> browse("https://tokenpro.work/admin/dashboard")); top.add(backend); top.add(Box.createVerticalStrut(6));
        JButton docs = sideAction("使用文档", "WebBook.png"); guardWebButton(docs, "docs"); docs.addActionListener(e -> browse("https://tokenpro.work/docs")); top.add(docs); top.add(Box.createVerticalStrut(6));
        panel.add(top, BorderLayout.NORTH);
        JPanel bottom = new JPanel(new BorderLayout()); bottom.setOpaque(false); bottom.setBorder(new EmptyBorder(0, 12, 18, 12)); NavButton accountNav = new NavButton("我的账户"); accountNav.setIcon(resourceIconContained("CircleUserLucide.png", 17, 17, true)); accountNav.setIconTextGap(12); accountNav.addActionListener(e -> openAccount()); navButtons.put("我的账户", accountNav); bottom.add(accountNav); panel.add(bottom, BorderLayout.SOUTH); return panel;
    }

    private void addNav(JPanel parent, String page, String icon) { NavButton button = new NavButton(page); if (!icon.isBlank()) { button.setIcon(resourceIconContained(icon, 17, 17, true)); button.setIconTextGap(12); } button.addActionListener(e -> showPage(page)); navButtons.put(page, button); parent.add(button); parent.add(Box.createVerticalStrut(6)); }
    private JButton sideAction(String text, String icon) { JButton button = new JButton(text); button.setIcon(resourceIconContained(icon, 17, 17, true)); button.setIconTextGap(12); button.setFont(appFont(13, Font.PLAIN)); button.setForeground(new Color(203, 211, 238)); button.setHorizontalAlignment(SwingConstants.LEFT); button.setPreferredSize(new Dimension(200, 44)); button.setMinimumSize(new Dimension(160, 44)); button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44)); button.setBorder(new EmptyBorder(0, 16, 0, 16)); button.setFocusPainted(false); button.setContentAreaFilled(false); return button; }

    private JComponent header() {
        GradientPanel panel = new GradientPanel(); panel.setLayout(new BorderLayout(0, 16)); panel.setBorder(new EmptyBorder(23, 28, 20, 28));
        JPanel title = transparent(new BorderLayout()); headerTitle.setFont(appFont(23, Font.BOLD)); headerTitle.setIcon(new TechGlobeIcon(30)); headerTitle.setIconTextGap(10); headerTitle.setGradient(true); headerTitle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); headerTitle.setToolTipText("打开 TokenPro 主页"); headerTitle.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent event) { if ("https://tokenpro.work".equals(headerTitle.getText())) browse("https://tokenpro.work"); } }); title.add(headerTitle, BorderLayout.WEST);
        JPanel right = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        updateButton = soft("正在核对版本…"); setUpdateButtonState("checking", "正在核对版本…"); updateButton.addActionListener(e -> updateFromButton()); right.add(updateButton);
        JButton user = soft("登录账户"); user.setIcon(resourceIconContained("CircleUserLucide.png", 17, 17, true)); user.addActionListener(e -> openAccount()); headerUser.addPropertyChangeListener("text", e -> user.setText(headerUser.getText())); right.add(user);
        title.add(right, BorderLayout.EAST); panel.add(title, BorderLayout.NORTH);
        Dimension headerCardSize = new Dimension(430, 64);
        RoundedPanel wallet = new RoundedPanel(20, new Color(22, 38, 78, 228), new Color(75, 190, 151, 145)); wallet.setLayout(new GridBagLayout()); wallet.setBorder(new EmptyBorder(0, 7, 0, 7)); wallet.setPreferredSize(headerCardSize); wallet.setMinimumSize(headerCardSize);
        JPanel walletContent = transparent(new FlowLayout(FlowLayout.CENTER, 14, 0));
        JPanel captions = transparent(); captions.setLayout(new BoxLayout(captions, BoxLayout.Y_AXIS)); JLabel balanceText = new JLabel("钱包余额"); balanceText.setFont(appFont(12, Font.PLAIN)); balanceText.setForeground(MUTED); JLabel rate = new JLabel("充值比例  1￥ = 1$"); rate.setFont(appFont(10, Font.PLAIN)); rate.setForeground(MUTED); captions.add(balanceText); captions.add(rate); walletContent.add(captions);
        headerBalance.setFont(appFont(26, Font.BOLD)); headerBalance.setForeground(new Color(105, 220, 194)); walletContent.add(headerBalance); refreshAccountButton = soft("刷新"); refreshAccountButton.setToolTipText("刷新钱包余额和订阅信息"); refreshAccountButton.setIcon(resourceIconContained("RefreshCwLucide.png", 15, 15, true)); refreshAccountButton.addActionListener(e -> refreshAccount()); walletContent.add(refreshAccountButton); JButton recharge = soft("充值/订阅"); guardWebButton(recharge, "purchase"); recharge.setIcon(resourceIconContained("PlusLucide.png", 15, 15, true)); recharge.addActionListener(e -> browse("https://tokenpro.work/purchase")); walletContent.add(recharge); wallet.add(walletContent);
        subscriptionSlot.setPreferredSize(headerCardSize); subscriptionSlot.setMinimumSize(headerCardSize);
        JPanel row = transparent(new GridBagLayout());
        GridBagConstraints walletConstraints = new GridBagConstraints(); walletConstraints.gridx = 0; walletConstraints.weightx = 1; walletConstraints.fill = GridBagConstraints.BOTH;
        GridBagConstraints subscriptionConstraints = new GridBagConstraints(); subscriptionConstraints.gridx = 1; subscriptionConstraints.weightx = 1; subscriptionConstraints.fill = GridBagConstraints.BOTH; subscriptionConstraints.insets = new Insets(0, 12, 0, 0);
        row.add(wallet, walletConstraints); row.add(subscriptionSlot, subscriptionConstraints); panel.add(row, BorderLayout.CENTER); return panel;
    }

    private JComponent homePanel() {
        JPanel panel = vertical();
        JPanel heading = transparent(new BorderLayout()); heading.setAlignmentX(Component.LEFT_ALIGNMENT); heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        JLabel title = new JLabel("我的应用"); title.setFont(appFont(15, Font.BOLD)); heading.add(title, BorderLayout.WEST); JPanel modelShowcase = transparent(new FlowLayout(FlowLayout.RIGHT, 10, 0)); modelShowcase.add(premiumModelTicker); modelShowcase.add(supportedModelBadges()); heading.add(modelShowcase, BorderLayout.EAST); panel.add(heading); panel.add(Box.createVerticalStrut(13));
        panel.add(desktopClientCard("Codex 客户端", "桌面应用 · 独立登录", "Codex", "https://openai.com/codex/", () -> chooseModels("Codex"), this::restoreCodex, () -> reconnectApp("Codex"), homeCodexStatus)); panel.add(Box.createVerticalStrut(12));
        panel.add(desktopClientCard("Claude 客户端", "桌面应用 · 独立登录", "Claude", "https://claude.ai/download", () -> chooseModels("Claude"), this::restoreClaude, this::reconnectClaude, homeClaudeStatus)); panel.add(Box.createVerticalStrut(12));
        panel.add(commandClientCard("Codex 命令行", "命令行工具 · Codex CLI", "Codex", "codex", "https://learn.chatgpt.com/docs/codex/cli")); panel.add(Box.createVerticalStrut(12));
        panel.add(commandClientCard("Claude 命令行", "命令行工具 · Claude Code", "Claude", "claude", "https://docs.anthropic.com/en/docs/claude-code/getting-started")); panel.add(Box.createVerticalGlue()); return panel;
    }

    private JComponent supportedModelBadges() {
        return new OverlappingModelBadges(List.of(
            new MiniModelBadge("GPT", resourceIconContained("OpenAIBlossomRuntime.png", 18, 18, true), new Color(91, 225, 201), 74, "支持 GPT / OpenAI 模型"),
            new MiniModelBadge("Claude", resourceIconContained("ClaudeSparkRuntime.png", 18, 18, false), new Color(238, 126, 82), 74, "支持 Claude 模型"),
            new MiniModelBadge("Gemini", resourceIconContained("GeminiSparkTransparent.png", 18, 18, false), new Color(107, 145, 255), 74, "支持 Gemini 模型"),
            new MiniModelBadge("Grok", resourceIconContained("GrokMarkTransparent.png", 18, 18, false), new Color(184, 155, 255), 74, "支持 Grok 模型")
        ));
    }

    private JComponent desktopClientCard(String title, String subtitle, String iconName, String downloadUrl, Runnable chooseModel, Runnable restore, Runnable open, JLabel state) {
        RoundedPanel card = card(); card.setLayout(new BorderLayout(18, 0));
        JLabel badge = new JLabel(clientIcon(iconName, 48)); badge.setHorizontalAlignment(SwingConstants.CENTER); badge.setPreferredSize(new Dimension(52, 52)); card.add(badge, BorderLayout.WEST);
        JPanel words = transparent(); words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS)); JPanel nameLine = transparent(new FlowLayout(FlowLayout.LEFT, 10, 0)); nameLine.setAlignmentX(Component.LEFT_ALIGNMENT); nameLine.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26)); JLabel heading = new JLabel(title); heading.setFont(appFont(17, Font.BOLD)); JLabel installedLabel = installLabel(); String scanKey = iconName.equals("Codex") ? "codex-client" : "claude-client"; JButton scan = installScanButton(scanKey, iconName + " 客户端"); if (iconName.equals("Codex")) { codexClientInstallLabel = installedLabel; codexClientScanButton = scan; } else { claudeClientInstallLabel = installedLabel; claudeClientScanButton = scan; } nameLine.add(heading); nameLine.add(installedLabel); nameLine.add(scan); JLabel detail = new JLabel(subtitle); detail.setAlignmentX(Component.LEFT_ALIGNMENT); detail.setFont(appFont(11, Font.PLAIN)); detail.setForeground(MUTED); words.add(Box.createVerticalStrut(2)); words.add(nameLine); words.add(Box.createVerticalStrut(5)); words.add(detail); card.add(words, BorderLayout.CENTER);
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(iconName.equals("Codex") ? homeCodexSupport : homeClaudeSupport);
        JButton menu = modelSelectorAnchor();
        menu.addActionListener(e -> {
            if (!desktopClientInstalled(iconName)) {
                state.setText("请先安装应用");
                status("请先安装 " + iconName + " 客户端");
                return;
            }
            showModelMenu(menu, iconName, chooseModel, () -> repairDesktopConversation(iconName, restore, open), restore);
        });
        if (iconName.equals("Codex")) codexModelMenuButton = menu; else claudeModelMenuButton = menu;
        JPanel modelControl = new ModelSelectorControl(menu, state);
        state.setFont(appFont(11, Font.BOLD)); state.setHorizontalAlignment(SwingConstants.LEFT);
        state.setText("正在检测应用…");
        actions.add(modelControl);
        JButton launch = primary("正在检测…");
        launch.setEnabled(false);
        launch.addActionListener(e -> { if (desktopClientInstalled(iconName)) open.run(); else browse(downloadUrl); });
        if (iconName.equals("Codex")) codexLaunch = launch; else claudeLaunch = launch;
        actions.add(launch); card.add(actions, BorderLayout.EAST); return card;
    }

    private void showModelMenu(JButton anchor, String client, Runnable chooseModel, Runnable repair, Runnable restore) {
        showModelMenu(anchor, client, chooseModel, repair, restore, false);
    }

    private void showModelMenu(JButton anchor, String client, Runnable chooseModel, Runnable repair, Runnable restore, boolean cli) {
        hideModelMenu();
        JLayeredPane layered = getLayeredPane();
        JPanel overlay = new JPanel(null);
        overlay.setOpaque(false);
        overlay.setBounds(0, 0, layered.getWidth(), layered.getHeight());
        overlay.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { hideModelMenu(); }
        });

        CosmosMenuPanel menu = new CosmosMenuPanel();
        int itemCount = 0;
        CosmosMenuButton choose = new CosmosMenuButton("选择模型", "embedded:price",
            new Color(241, 244, 255), new Color(93, 103, 220, 105));
        choose.addActionListener(event -> { hideModelMenu(); chooseModel.run(); });
        menu.add(choose);
        itemCount++;
        CosmosMenuButton official = new CosmosMenuButton("恢复官方", "RefreshCwLucide.png",
            STATUS_OFFICIAL, new Color(53, 100, 176, 105));
        official.addActionListener(event -> { hideModelMenu(); restore.run(); });
        menu.add(official);
        itemCount++;
        CosmosMenuButton repairConversation = new CosmosMenuButton("修复对话", "embedded:repair",
            new Color(184, 142, 255), new Color(91, 70, 146, 120));
        repairConversation.addActionListener(event -> { hideModelMenu(); repair.run(); });
        menu.add(repairConversation);
        itemCount++;

        int width = 210;
        int height = 8 + itemCount * 38;
        Component visualAnchor = anchor.getParent() instanceof ModelSelectorControl ? anchor.getParent() : anchor;
        Point point = SwingUtilities.convertPoint(visualAnchor, 0, visualAnchor.getHeight() + 6, layered);
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

    private void repairDesktopConversation(String client, Runnable restore, Runnable reconnect) {
        if (("Codex".equals(client) && !codexTokenProConfigured()) || ("Claude".equals(client) && !claudeTokenProConfigured())) {
            restore.run();
        } else {
            reconnect.run();
        }
    }

    private boolean codexTokenProConfigured() {
        if (codexOfficialMode()) return false;
        try {
            Map<String, Object> saved = Json.object(Json.parse(store.read("codex-selected.json").orElse("{}")));
            if (saved.get("models") instanceof List<?> models && !models.isEmpty()) return true;
            return !string(saved.get("model")).isBlank();
        } catch (Exception ignored) { return false; }
    }

    private boolean claudeTokenProConfigured() {
        try { return store.read(ClaudeBridgeConfig.FILE).isPresent(); }
        catch (Exception ignored) { return false; }
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
        RoundedPanel card = card(); card.setLayout(new BorderLayout(18, 0));
        boolean codexCli = command.equals("codex");
        JLabel badge = new JLabel(clientIcon(iconName, 48));
        badge.setHorizontalAlignment(SwingConstants.CENTER); badge.setPreferredSize(new Dimension(52, 52));
        card.add(badge, BorderLayout.WEST);
        JPanel words = transparent(); words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
        JPanel nameLine = transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        nameLine.setAlignmentX(Component.LEFT_ALIGNMENT); nameLine.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        JLabel heading = new JLabel(title); heading.setFont(appFont(17, Font.BOLD));
        JLabel installedLabel = installLabel();
        JButton scan = installScanButton(command + "-cli", iconName + " 命令行");
        if(codexCli) { codexCliInstallLabel = installedLabel; codexCliScanButton = scan; }
        else { claudeCliInstallLabel = installedLabel; claudeCliScanButton = scan; }
        nameLine.add(heading); nameLine.add(installedLabel); nameLine.add(scan);
        JLabel detail = new JLabel(subtitle + " · 独立配置");
        detail.setFont(appFont(11, Font.PLAIN)); detail.setForeground(MUTED);
        words.add(Box.createVerticalStrut(2)); words.add(nameLine); words.add(Box.createVerticalStrut(5)); words.add(detail);
        card.add(words, BorderLayout.CENTER);
        JPanel buttons = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(codexCli ? homeCodexCliSupport : homeClaudeCliSupport);
        JLabel state = codexCli ? homeCodexCliStatus : homeClaudeCliStatus;
        JButton menu = modelSelectorAnchor();
        menu.setName(command + "-cli-model-menu");
        menu.addActionListener(event -> {
            if(!(codexCli ? codexCliInstalled : claudeCliInstalled)) {
                status("请先安装 " + iconName + " 命令行工具"); return;
            }
            showModelMenu(menu, iconName, () -> chooseModels(iconName, true, false), () -> connectClient(iconName, true), () -> restoreCli(command), true);
        });
        if(codexCli) codexCliModelMenuButton = menu; else claudeCliModelMenuButton = menu;
        JPanel modelControl = new ModelSelectorControl(menu, state);
        state.setFont(appFont(11, Font.BOLD)); state.setHorizontalAlignment(SwingConstants.LEFT);
        buttons.add(modelControl);
        JButton terminal = primary("连接 " + iconName + " 命令行"); terminal.setEnabled(false);
        terminal.addActionListener(event -> {
            if (!knownInstallationTargets.contains(command + "-cli")) return;
            if (codexCli ? codexCliInstalled : claudeCliInstalled) openTerminal(command);
            else browse(downloadUrl);
        });
        if(codexCli) codexCliLaunch = terminal; else claudeCliLaunch = terminal;
        applyCliActionState(terminal, menu, state, iconName, false, false, 0, false);
        buttons.add(terminal); card.add(buttons, BorderLayout.EAST);
        return card;
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
        // Resolve controls before the first dashboard paint, including a still-running installation scan.
        refreshConnectControls();
        views.show(viewHost, "dashboard");
    }

    private void logout() {
        try {
            store.delete("java-session.json"); accessToken = null; refreshToken = ""; tokenExpiresAt = 0; sessionUser = Map.of(); accountId = ""; keys.clear();
            account.setText("尚未登录"); headerUser.setText("登录账户"); accountEmail.setText("登录账户"); headerBalance.setText("—"); accountBalance.setText("—"); showSubscriptions(List.of());
            homeCodexSupport.reset(); homeClaudeSupport.reset(); premiumModelTicker.reset();
            setDesktopCardState("Codex", "请先选择模型", false);
            setDesktopCardState("Claude", "请先选择模型", false);
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
        chooseModels(client, false, false);
    }

    private void chooseModels(String client, boolean cli, boolean launch) {
        if (accessToken == null || accountId.isBlank()) { error(new IllegalStateException("请先登录 TokenPro")); return; }
        String token = accessToken, owner = accountId;
        async("正在加载可用分组与模型…", () -> api.pricedModels(token), models -> {
            if(!Objects.equals(token, accessToken) || !Objects.equals(owner, accountId)) return;
            if (models.isEmpty()) { error(new IllegalStateException("当前账户没有可用模型")); return; }
            updateSupportedModelCounts(models);
            reconcileModelSelections(models, owner);
            Set<String> selected = selectedModelIds(client, cli);
            ModelPickerDialog dialog = new ModelPickerDialog(this, client, models, selected,
                chosen -> {
                    if ("Codex".equals(client)) applyCodex(chosen, cli, launch);
                    else if (cli) applyClaudeCli(chosen, launch);
                    else applyClaude(chosen);
                });
            status("已加载 " + models.size() + " 个可用模型");
            dialog.setVisible(true);
        });
    }

    private void applyCodex() {
        chooseModels("Codex");
    }

    private void applyCodex(List<PricedModel> selected) {
        applyCodex(selected, false, false);
    }

    private void applyCodex(List<PricedModel> selected, boolean cli, boolean launchCli) {
        if (accessToken == null || accountId.isBlank()) { error(new IllegalStateException("请先登录 TokenPro")); return; }
        selected = uniqueModels(ModelPickerDialog.orderedModels(selected, "Codex"));
        List<PricedModel> chatModels = selected.stream().filter(model -> !model.isImageGeneration()).toList();
        List<PricedModel> imageModels = selected.stream().filter(PricedModel::isImageGeneration).toList();
        if (selected.isEmpty()) { error(new IllegalStateException("请全局至少选择 1 个模型")); return; }
        PricedModel imageModel = imageModels.isEmpty() ? null : imageModels.getFirst();
        List<PricedModel> chosen = selected;
        async("正在使用全局 Key 配置 Codex…", () -> {
            ApiClient.ManagedKey managed = api.globalKey(accessToken);
            SecureStore targetStore = cli ? store.cli("codex") : store;
            CodexConfig targetConfig = cli ? new CodexConfig(targetStore, targetStore.root().resolve("home").resolve("config.toml")) : codex;
            targetConfig.apply("https://tokenpro.work/v1", chosen, managed.key(), string(sessionUser.get("email")));
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("default_model", (chatModels.isEmpty() ? imageModel : chatModels.getFirst()).name());
            saved.put("models", modelRows(chosen));
            saved.put("image_model", imageModel == null ? "" : imageModel.name());
            saved.put("image_models", imageModels.stream().map(PricedModel::name).toList());
            saved.put("key_id", managed.id());
            saved.put("account_id", accountId);
            targetStore.write("codex-selected.json", Json.stringify(saved));
            if (cli) CliLauncher.install(store, "codex");
            else store.delete("codex-official-mode.txt");
            return chosen;
        }, configured -> {
            if (!cli) setDesktopCardState("Codex", selectionStatus(chosen.size()), true);
            else updateCommandControls("codex", codexCliInstalled);
            status((cli ? "Codex 命令行独立配置" : "Codex 客户端配置") + "已保存 " + configured.size() + " 个模型；点击连接后加载，尚未验证实际请求通道");
            if (cli && launchCli) launchTerminal("codex");
            else if (!cli) reconnectApp("Codex");
        });
    }

    private void restoreCodex() {
        try {
            boolean running = !ClientReconnect.desktopProcesses("Codex").isEmpty();
            if (running && JOptionPane.showConfirmDialog(this,
                "将终止 Codex 当前请求并重新启动，以加载 OpenAI 官方配置。\n请先保存正在进行的工作；其他应用不会关闭。",
                "恢复官方配置", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            // Keep the last TokenPro selection as a preference only. The live
            // Codex config is official, so no TokenPro key or route remains in use.
            boolean changed = codex.restore();
            store.write("codex-official-mode.txt", "official");
            setDesktopCardState("Codex", officialStatus("Codex"), true);
            status(changed ? "Codex 已切回官方默认 GPT；历史对话保留" : "当前已是官方配置");
            restartOfficialCodex();
        }
        catch (Exception ex) { error(ex); }
    }

    private void restartOfficialCodex() {
        String identity = "codex-desktop";
        if (!connectingClients.begin(identity)) return;
        refreshConnectControls();
        status("正在重新启动 Codex 并加载官方配置…");
        new SwingWorker<Boolean, Void>() {
            protected Boolean doInBackground() throws Exception {
                ClientReconnect.reconnect(() -> {}, () -> {
                    ClientReconnect.stopDesktop("Codex");
                    CodexImageBridge.stop(store);
                }, () -> {
                    if (!Platform.openApplication("Codex")) throw new IllegalStateException("无法启动 Codex");
                });
                ClientReconnect.awaitStarted(store, "Codex", false);
                return true;
            }
            protected void done() {
                finishConnection(identity);
                try {
                    get();
                    status("Codex 已使用 OpenAI 官方配置重新启动；历史对话保留");
                } catch (Exception failure) { error(failure.getCause() == null ? failure : failure.getCause()); }
            }
        }.execute();
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
            if (item.getKey().equals("打开充值页面")) guardWebButton(button, "purchase");
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
            setDesktopCardState("Claude", selectionStatus(config.routes().size()), true);
            status("Claude 已保存 " + config.routes().size() + " 个模型；点击连接加载新列表，当前程序未关闭");
        });
    }

    private void updateBridgeStatus() {
        try { ClaudeBridgeConfig config = ClaudeBridgeConfig.load(store); boolean healthy = ClaudeBridgeManager.healthy(store); bridgeStatus.setText("桥接状态：" + (healthy ? "运行中" : "已配置") + " · " + config.routes().size() + " 个模型"); setDesktopCardState("Claude", selectionStatus(config.routes().size()), true); }
        catch (Exception e) { bridgeStatus.setText("桥接状态：未配置"); setDesktopCardState("Claude", officialStatus("Claude"), true); }
    }

    private void restoreClaude() {
        try {
            boolean configured = store.read(ClaudeBridgeConfig.FILE).isPresent();
            if (configured) ClaudeDesktopConfig.restoreOfficial(store);
            ClaudeBridgeManager.stop(store); store.delete(ClaudeBridgeConfig.FILE);
            bridgeStatus.setText("桥接状态：未配置");
            setDesktopCardState("Claude", officialStatus("Claude"), true);
            status(configured ? "Claude 配置已恢复；重新启动该客户端后生效" : "当前没有需要恢复的 Claude 配置，无需重复恢复");
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
        reconnectApp("Claude");
    }

    private void restoreSession() {
        status("正在恢复登录…");
        new SwingWorker<Map<String, Object>, Void>() {
            protected Map<String, Object> doInBackground() throws Exception {
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
            }
            protected void done() {
                try {
                    Map<String, Object> value = get();
                    if (value == null) { status("就绪"); showLoginScreen(); }
                    else { showAccount(value); updateCodexStatus(); updateBridgeStatus(); showPage("首页"); showDashboardScreen(); }
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    if (ApiClient.isUnauthorized(cause)) try { store.delete("java-session.json"); } catch (Exception ignored) {}
                    accessToken = null; refreshToken = ""; tokenExpiresAt = 0; sessionUser = Map.of(); accountId = "";
                    status("请重新登录"); showLoginScreen();
                    loginView.setLoading(false, ApiClient.isUnauthorized(cause) ? "登录已失效，请重新登录" : "暂时无法恢复登录，可重试或检查更新");
                }
            }
        }.execute();
    }

    private void updateCodexStatus() {
        if (codexOfficialMode()) {
            setDesktopCardState("Codex", officialStatus("Codex"), true);
            return;
        }
        try {
            Optional<String> raw = store.read("codex-selected.json");
            if (raw.isEmpty()) throw new IllegalStateException("未选择");
            Map<String, Object> saved = Json.object(Json.parse(raw.get()));
            if (saved.get("models") instanceof List<?> models && !models.isEmpty()) {
                setDesktopCardState("Codex", selectionStatus(models.size()), true);
            }
            else {
                String selected = string(saved.get("model")); if (selected.isBlank()) throw new IllegalStateException("未选择");
                setDesktopCardState("Codex", selectionStatus(1), true);
            }
        } catch (Exception ignored) { setDesktopCardState("Codex", officialStatus("Codex"), true); }
    }

    private boolean codexOfficialMode() {
        try { return store.read("codex-official-mode.txt").filter("official"::equals).isPresent(); }
        catch (Exception ignored) { return false; }
    }

    private void setDesktopCardState(String client, String installedText, boolean canConnect) {
        if (client.equals("Codex")) codexCanConnect = canConnect; else claudeCanConnect = canConnect;
        boolean installed = desktopClientInstalled(client);
        JLabel state = client.equals("Codex") ? homeCodexStatus : homeClaudeStatus;
        JButton launch = client.equals("Codex") ? codexLaunch : claudeLaunch;
        state.setText(installed ? installedText : "请先安装应用");
        if (launch != null) updateDesktopLaunch(client, installed, canConnect);
    }

    private static JLabel installLabel() {
        JLabel label = new JLabel("检测中…");
        label.setFont(appFont(11, Font.BOLD));
        label.setForeground(MUTED);
        return label;
    }

    private JButton installScanButton(String key, String target) {
        JButton button = new ActionButton("检测中…", false);
        button.setFont(appFont(10, Font.BOLD));
        button.setForeground(new Color(205, 218, 250));
        button.setIcon(resourceIconContained("RefreshCwLucide.png", 11, 11, true));
        button.setIconTextGap(4);
        button.setBorder(new EmptyBorder(4, 8, 4, 8));
        button.setPreferredSize(new Dimension(82, 26));
        button.setMinimumSize(button.getPreferredSize());
        button.setMaximumSize(button.getPreferredSize());
        button.setEnabled(false);
        button.setToolTipText("重新查找 " + target + " 的安装位置");
        button.addActionListener(event -> refreshInstallationTarget(key, target));
        return button;
    }

    private void refreshInstallationTarget(String key, String target) {
        if (!installationScanInProgress.compareAndSet(false, true)) return;
        JButton button = switch (key) {
            case "codex-client" -> codexClientScanButton;
            case "claude-client" -> claudeClientScanButton;
            case "codex-cli" -> codexCliScanButton;
            default -> claudeCliScanButton;
        };
        JLabel label = switch (key) {
            case "codex-client" -> codexClientInstallLabel;
            case "claude-client" -> claudeClientInstallLabel;
            case "codex-cli" -> codexCliInstallLabel;
            default -> claudeCliInstallLabel;
        };
        boolean previousInstalled = switch (key) {
            case "codex-client" -> codexClientInstalled;
            case "claude-client" -> claudeClientInstalled;
            case "codex-cli" -> codexCliInstalled;
            default -> claudeCliInstalled;
        };
        button.setText("检测中…");
        button.setForeground(new Color(168, 180, 213));
        button.setEnabled(false);
        label.setText("检测中…");
        label.setForeground(MUTED);
        status("正在查找 " + target + "…");
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                return switch (key) {
                    case "codex-client" -> Platform.applicationInstalled("Codex");
                    case "claude-client" -> Platform.applicationInstalled("Claude");
                    case "codex-cli" -> Platform.commandInstalled("codex");
                    default -> Platform.commandInstalled("claude");
                };
            }
            @Override protected void done() {
                try {
                    boolean installed = get();
                    knownInstallationTargets.add(key);
                    switch (key) {
                        case "codex-client" -> codexClientInstalled = installed;
                        case "claude-client" -> claudeClientInstalled = installed;
                        case "codex-cli" -> codexCliInstalled = installed;
                        default -> claudeCliInstalled = installed;
                    }
                    updateInstallLabel(label, installed);
                    updateInstallScanButton(button, installed);
                    if (key.endsWith("client")) {
                        String client = key.startsWith("codex") ? "Codex" : "Claude";
                        updateDesktopLaunch(client, installed, client.equals("Codex") ? codexCanConnect : claudeCanConnect);
                        if (client.equals("Codex")) updateCodexStatus(); else updateBridgeStatus();
                    } else updateCommandControls(key.startsWith("codex") ? "codex" : "claude", installed);
                    status(installed ? "已找到 " + target : "暂未找到 " + target);
                } catch (Exception ignored) {
                    updateInstallLabel(label, previousInstalled);
                    updateInstallScanButton(button, previousInstalled);
                    status(target + " 检测失败，请稍后重试");
                } finally { installationScanInProgress.set(false); }
            }
        }.execute();
    }

    private boolean desktopClientInstalled(String client) {
        return client.equals("Codex") ? codexClientInstalled : claudeClientInstalled;
    }

    private void refreshInstallationState() {
        if (installationScanCompleted && codexClientInstalled && claudeClientInstalled && codexCliInstalled && claudeCliInstalled) return;
        long now = System.nanoTime();
        if (installationScanCompleted && now - lastInstallationScanAtNanos < java.util.concurrent.TimeUnit.SECONDS.toNanos(3)) return;
        if (!installationScanInProgress.compareAndSet(false, true)) return;
        lastInstallationScanAtNanos = now;
        installationScanFailed = false;
        setInstallationScanState(true);
        Platform.InstallationSnapshot known = installationScanCompleted
            ? new Platform.InstallationSnapshot(codexClientInstalled, claudeClientInstalled, codexCliInstalled, claudeCliInstalled)
            : null;
        new SwingWorker<Platform.InstallationSnapshot, Void>() {
            @Override protected Platform.InstallationSnapshot doInBackground() { return Platform.installationSnapshot(known); }
            @Override protected void done() {
                try { applyInstallationSnapshot(get()); installationScanCompleted = true; }
                catch (Exception ignored) { installationScanFailed = true; refreshConnectControls(); status("应用安装状态检测失败，请点击查找程序重试"); }
                finally { installationScanInProgress.set(false); setInstallationScanState(false); }
            }
        }.execute();
    }

    private void setInstallationScanState(boolean scanning) {
        if (!scanning) {
            updateInstallScanButton(codexClientScanButton, codexClientInstalled);
            updateInstallScanButton(claudeClientScanButton, claudeClientInstalled);
            updateInstallScanButton(codexCliScanButton, codexCliInstalled);
            updateInstallScanButton(claudeCliScanButton, claudeCliInstalled);
            return;
        }
        JButton[] buttons = {codexClientScanButton, claudeClientScanButton, codexCliScanButton, claudeCliScanButton};
        for (JButton button : buttons) if (button != null) {
            button.setText("检测中…");
            button.setForeground(new Color(168, 180, 213));
            button.setEnabled(false);
        }
    }

    private void applyInstallationSnapshot(Platform.InstallationSnapshot snapshot) {
        knownInstallationTargets.addAll(List.of("codex-client", "claude-client", "codex-cli", "claude-cli"));
        codexClientInstalled = snapshot.codexClient();
        claudeClientInstalled = snapshot.claudeClient();
        codexCliInstalled = snapshot.codexCli();
        claudeCliInstalled = snapshot.claudeCli();
        updateInstallLabel(codexClientInstallLabel, codexClientInstalled);
        updateInstallLabel(claudeClientInstallLabel, claudeClientInstalled);
        updateInstallLabel(codexCliInstallLabel, codexCliInstalled);
        updateInstallLabel(claudeCliInstallLabel, claudeCliInstalled);
        updateInstallScanButton(codexClientScanButton, codexClientInstalled);
        updateInstallScanButton(claudeClientScanButton, claudeClientInstalled);
        updateInstallScanButton(codexCliScanButton, codexCliInstalled);
        updateInstallScanButton(claudeCliScanButton, claudeCliInstalled);
        updateDesktopLaunch("Codex", codexClientInstalled, codexCanConnect);
        updateDesktopLaunch("Claude", claudeClientInstalled, claudeCanConnect);
        updateCommandControls("codex", codexCliInstalled);
        updateCommandControls("claude", claudeCliInstalled);
        updateCodexStatus();
        updateBridgeStatus();
    }

    private static void updateInstallLabel(JLabel label, boolean installed) {
        if (label == null) return;
        label.setText(installed ? "已安装" : "未安装");
        label.setForeground(installed ? new Color(97, 222, 165) : MUTED);
    }

    private static void updateInstallScanButton(JButton button, boolean installed) {
        if (button == null) return;
        button.setText(installed ? "重新检测" : "查找程序");
        button.setForeground(installed ? new Color(205, 218, 250) : STATUS_PENDING);
        button.setEnabled(true);
    }

    private void updateDesktopLaunch(String client, boolean installed, boolean canConnect) {
        JButton launch = client.equals("Codex") ? codexLaunch : claudeLaunch;
        JButton menu = client.equals("Codex") ? codexModelMenuButton : claudeModelMenuButton;
        JLabel state = client.equals("Codex") ? homeCodexStatus : homeClaudeStatus;
        if (launch == null) return;
        if (!knownInstallationTargets.contains(client.toLowerCase(Locale.ROOT) + "-client")) {
            launch.setText("连接 " + client + " 客户端"); launch.setEnabled(false);
            if (menu != null) menu.setEnabled(false);
            state.setText(installationScanFailed ? "检测失败，请重新检测" : "正在检查安装状态…");
            return;
        }
        if (connectingClients.blocked(client.toLowerCase(Locale.ROOT) + "-desktop")) {
            launch.setText("连接中…"); launch.setEnabled(false);
            if (menu != null) menu.setEnabled(false);
            return;
        }
        launch.setText(installed ? "连接 " + client + " 客户端" : "去官方下载");
        launch.setToolTipText(installed ? "重新连接 " + client + " 客户端" : "打开 " + client + " 官方下载页");
        launch.setEnabled(!installed || canConnect);
        if (menu != null) menu.setEnabled(true);
        if (!installed) state.setText("请先安装应用");
    }

    private void updateCommandControls(String command, boolean installed) {
        JButton launch = command.equals("codex") ? codexCliLaunch : claudeCliLaunch;
        JButton menu = command.equals("codex") ? codexCliModelMenuButton : claudeCliModelMenuButton;
        JLabel state = command.equals("codex") ? homeCodexCliStatus : homeClaudeCliStatus;
        boolean known = knownInstallationTargets.contains(command + "-cli");
        applyCliActionState(launch, menu, state, command.equals("codex") ? "Codex" : "Claude",
            known, installed, known ? cliSelectedCount(command) : 0, connectingClients.blocked(command + "-cli"));
        if (!known && installationScanFailed) state.setText("检测失败，请重新检测");
    }

    static void applyCliActionState(JButton launch, JButton menu, JLabel state, String name,
                                    boolean known, boolean installed, int count, boolean connecting) {
        if (launch == null || menu == null) return;
        String connect = "连接 " + name + " 命令行";
        launch.setText(!known ? connect : connecting ? "连接中…" : installed ? connect : "去官方下载");
        launch.setEnabled(known && !connecting && (!installed || count > 0));
        menu.setEnabled(known && installed && !connecting);
        state.setText(!known ? "正在检查安装状态…" : installed ? selectionStatus(count) : "请先安装应用");
    }

    private int cliSelectedCount(String command) {
        try {
            SecureStore selected = store.cli(command);
            if(command.equals("claude")) return ClaudeBridgeConfig.load(selected).routes().size();
            Map<String,Object> data = Json.object(Json.parse(selected.read("codex-selected.json").orElse("{}")));
            return data.get("models") instanceof List<?> rows ? rows.size() : 0;
        } catch(Exception ignored) { return 0; }
    }

    private void copyCliCommand(String command) {
        async("正在准备独立命令行入口…", () -> CliLauncher.install(store, command), file -> {
            String invocation = "'" + file.toString().replace("'", "'\\''") + "'";
            if(Platform.OS_KIND == Platform.OS.WINDOWS) invocation = "& '" + file.toString().replace("'", "''") + "'";
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(invocation), null);
            status("已复制独立启动命令，可在 " + (Platform.OS_KIND == Platform.OS.WINDOWS ? "PowerShell" : "终端") + " 粘贴；不覆盖系统原生命令");
        });
    }

    private void restoreCli(String command) {
        async("正在恢复 " + command + " 命令行配置…", () -> {
            SecureStore cli = store.cli(command);
            boolean changed;
            if(command.equals("codex")) {
                changed = new CodexConfig(cli, cli.root().resolve("home/config.toml")).restore();
                cli.delete("codex-selected.json");
            } else {
                changed = cli.read(ClaudeCliConfig.FILE).isPresent() || cli.read(ClaudeBridgeConfig.FILE).isPresent();
                ClaudeBridgeManager.stop(cli);
                cli.delete(ClaudeCliConfig.FILE); cli.delete(ClaudeBridgeConfig.FILE);
            }
            return changed;
        }, changed -> {
            updateCommandControls(command, command.equals("codex") ? codexCliInstalled : claudeCliInstalled);
            status(changed ? "仅已恢复 " + command + " 命令行配置；桌面客户端与会话记录保持不变"
                : "当前没有需要恢复的 " + command + " 命令行配置，无需重复恢复");
        });
    }

    private static String selectionStatus(int count) { return count > 0 ? "当前：TokenPro · 已选 " + count + " 个模型" : "请先选择模型"; }
    private static String officialStatus(String client) {
        return client.equals("Claude") ? "当前：Claude 官方线路" : "当前：官方 OpenAI 线路";
    }

    private Set<String> selectedModelIds(String client) {
        return selectedModelIds(client, false);
    }

    private Set<String> selectedModelIds(String client, boolean cli) {
        Set<String> ids = new HashSet<>();
        try {
            SecureStore targetStore = cli ? store.cli(client.equals("Claude") ? "claude" : "codex") : store;
            if ("Claude".equals(client)) {
                for (ClaudeBridgeConfig.Route route : ClaudeBridgeConfig.load(targetStore).routes()) {
                    ids.add(route.groupId() + "\u0000" + route.name());
                }
            } else {
                Optional<String> raw = targetStore.read("codex-selected.json");
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
        showBalance(user);
        headerUser.setText(emailValue.isBlank() ? "我的账户" : emailValue);
        accountEmail.setText(emailValue.isBlank() ? "我的账户" : emailValue);
        accountId = string(user.get("id"));
        password.setText("");
        status("就绪");
        try { if (!emailValue.isBlank()) codex.updateActor(emailValue); } catch (Exception ignored) {}
        refreshSupportedModelCounts();
        refreshSubscriptions();
    }

    private void showBalance(Map<String,Object> user) {
        Object rawBalance = user.get("balance");
        String balance = rawBalance instanceof Number number ? String.format(Locale.ROOT, "¥%.2f", number.doubleValue()) : "—";
        account.setText("已登录：" + string(user.get("email")) + "    余额：" + balance);
        headerBalance.setText(balance);
        accountBalance.setText(balance);
    }

    private void refreshAccountSilently() {
        if (accessToken == null || accessToken.isBlank() || System.currentTimeMillis() - lastAccountRefresh < 15000
            || !accountRefreshInProgress.compareAndSet(false, true)) return;
        String token = accessToken;
        lastAccountRefresh = System.currentTimeMillis();
        new SwingWorker<Map<String,Object>,Void>() {
            protected Map<String,Object> doInBackground() throws Exception { return api.me(token); }
            protected void done() {
                try {
                    if (!Objects.equals(token, accessToken)) return;
                    Map<String,Object> user = get();
                    if (!ClaudeBridgeServer.sameIdentifier(accountId, user.get("id"))) return;
                    sessionUser = new LinkedHashMap<>(user);
                    showBalance(user); saveSession(user);
                    String tooltip = "余额自动刷新于 " + java.time.LocalTime.now().withNano(0);
                    headerBalance.setToolTipText(tooltip); accountBalance.setToolTipText(tooltip);
                    if (codexLaunch != null) codexLaunch.setToolTipText(ConnectionEvidence.verified(store)
                        ? "已观察到当前配置的 TokenPro 请求；实际费用请查用量记录"
                        : "尚未观察到当前配置的 TokenPro 请求；仅模型名称不能证明已接入");
                } catch (Exception ignored) {
                    headerBalance.setToolTipText("余额刷新失败，当前显示上次结果，请点击刷新重试");
                    accountBalance.setToolTipText(headerBalance.getToolTipText());
                } finally { accountRefreshInProgress.set(false); }
            }
        }.execute();
    }

    private void refreshSupportedModelCounts() {
        if (accessToken == null || accessToken.isBlank()) {
            homeCodexSupport.reset();
            homeClaudeSupport.reset();
            premiumModelTicker.reset();
            return;
        }
        String token = accessToken;
        new SwingWorker<List<PricedModel>, Void>() {
            protected List<PricedModel> doInBackground() throws Exception { return api.pricedModels(token); }
            protected void done() {
                try {
                    if (Objects.equals(token, accessToken)) {
                        List<PricedModel> models = get();
                        updateSupportedModelCounts(models);
                        reconcileModelSelections(models, accountId);
                    }
                } catch (Exception ignored) {
                    homeCodexSupport.reset();
                    homeClaudeSupport.reset();
                    homeCodexCliSupport.reset();
                    homeClaudeCliSupport.reset();
                    premiumModelTicker.reset();
                }
            }
        }.execute();
    }

    private void reconcileModelSelections(List<PricedModel> models, String owner) {
        try {
            int removed = ModelSelectionReconciler.reconcileAll(store, owner, models);
            updateCodexStatus(); updateBridgeStatus();
            updateCommandControls("codex", codexCliInstalled);
            updateCommandControls("claude", claudeCliInstalled);
            if(removed > 0) status("已移除 " + removed + " 个失效模型选择；现有程序保持打开，点击连接后加载新列表");
        } catch(Exception failure) {
            status("最新模型列表已加载，但旧选择清理未全部完成：" + ErrorMessages.describe(failure));
        }
    }

    private void updateSupportedModelCounts(List<PricedModel> models) {
        long codexCount = models.stream().filter(model -> ModelPickerDialog.supportsClient(model, "Codex")).map(PricedModel::name).distinct().count();
        long claudeCount = models.stream().filter(model -> ModelPickerDialog.supportsClient(model, "Claude")).map(PricedModel::name).distinct().count();
        homeCodexSupport.setCount(codexCount);
        homeClaudeSupport.setCount(claudeCount);
        homeCodexCliSupport.setCount(codexCount);
        homeClaudeCliSupport.setCount(claudeCount);
        premiumModelTicker.setModels(premiumTickerModels(models));
    }

    static List<PricedModel> premiumTickerModels(List<PricedModel> models) {
        List<String> vendors = List.of("GPT", "Claude", "Gemini", "Grok");
        Map<String, Map<String, PricedModel>> grouped = new LinkedHashMap<>();
        for (String vendor : vendors) grouped.put(vendor, new LinkedHashMap<>());
        for (PricedModel model : models) {
            if (model.isImageGeneration()) continue;
            String vendor = tickerVendor(model);
            Map<String, PricedModel> unique = grouped.get(vendor);
            if (unique == null) continue;
            PricedModel current = unique.get(model.name());
            if (current == null || ApiClient.compareModelPriceDescending(model, current) < 0) unique.put(model.name(), model);
        }
        List<PricedModel> result = new ArrayList<>();
        for (String vendor : vendors) {
            List<PricedModel> ranked = new ArrayList<>(grouped.get(vendor).values());
            ranked.sort(ApiClient::compareModelPriceDescending);
            result.addAll(ranked.stream().limit(2).toList());
        }
        return List.copyOf(result);
    }

    private static String tickerVendor(PricedModel model) {
        String value = (model.name() + " " + model.platform() + " " + model.groupName()).toLowerCase(Locale.ROOT);
        if (value.contains("gpt") || value.contains("openai")) return "GPT";
        if (value.contains("claude") || value.contains("anthropic")) return "Claude";
        if (value.contains("gemini") || value.contains("google")) return "Gemini";
        if (value.contains("grok") || value.contains("xai")) return "Grok";
        return "";
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
                    Map<String,Object> user = get();
                    showAccount(user); saveSession(user);
                    lastAccountRefresh = System.currentTimeMillis();
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

    private void updateFromButton() {
        ReleaseInfo cached = availableUpdate;
        if (cached != null && compareVersions(cached.version(), Main.VERSION) > 0) {
            installUpdate(cached);
            return;
        }
        checkForUpdates(updateButton);
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
                        availableUpdate = release;
                        setUpdateButtonState("check", "发现更新 v" + release.version());
                        status("发现新版本 " + release.version());
                        if (!automatic) installUpdate(release);
                    } else {
                        availableUpdate = null;
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
        boolean latest = "latest".equals(state);
        boolean checking = "checking".equals(state);
        if (loginView != null) loginView.setUpdateState(text, !latest && !checking);
        if (updateButton == null) return;
        updateButton.putClientProperty("tokenpro.updateState", state);
        updateButton.setText(text);
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
        if (Platform.OS_KIND == Platform.OS.WINDOWS && !release.hasIncrementalUpdate()) {
            updateInProgress.set(false);
            setUpdateButtonState("check", "重新核对版本");
            status("此版本暂未提供增量更新包，未下载完整安装包，也未更改安装位置");
            return;
        }
        if (release.preferredUrl().isBlank()) {
            updateInProgress.set(false);
            setUpdateButtonState("check", "重新核对版本");
            status("当前系统暂未提供自动更新包");
            return;
        }
        if (updateButton != null) updateButton.setEnabled(false);
        setUpdateButtonState("checking", "正在更新 0%");
        setUpdateProgress(0);
        status("正在下载 TokenPro " + release.version() + "…");
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
                setUpdateButtonState("checking", "正在更新 " + value + "%");
                setUpdateProgress(value);
            }
            protected void done() {
                try {
                    Path installer = get();
                    setUpdateButtonState("checking", "正在完成更新…");
                    setUpdateProgress(100);
                    status("正在完成 TokenPro " + release.version() + " 更新，程序即将重启…");
                    finishUpdate(installer, release);
                } catch (Exception ex) {
                    updateInProgress.set(false);
                    if (updateButton != null) updateButton.setEnabled(true);
                    setUpdateButtonState("check", "更新失败，点击重试");
                    setUpdateProgress(-1);
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    status("自动更新失败：" + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
                }
            }
        }.execute();
    }

    private void finishUpdate(Path installer, ReleaseInfo release) {
        new SwingWorker<Void,Void>() {
            protected Void doInBackground() throws Exception {
                BridgeLifecycle.update(BridgeLifecycle.running(store), () -> {
                    if(release.hasIncrementalUpdate()) Updater.installIncremental(installer, release.version());
                    else Updater.install(installer);
                });
                return null;
            }
            protected void done() {
                try { get(); dispose(); System.exit(0); }
                catch(Exception ex) {
                    updateInProgress.set(false);
                    setUpdateButtonState("check", "更新失败，点击重试");
                    setUpdateProgress(-1);
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    status("更新失败，已尝试恢复原有连接：" + cause.getMessage()
                        + (cause.getSuppressed().length > 0 ? "；部分桥接未恢复，请点击连接重试" : ""));
                }
            }
        }.execute();
    }

    private void setUpdateProgress(int value) {
        if (loginView != null) loginView.setUpdateProgress(value);
        if (updateButton != null) {
            updateButton.putClientProperty("tokenpro.updateProgress", value);
            updateButton.repaint();
        }
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

    private static JButton modelSelectorAnchor() {
        JButton button = new JButton("模型选择                                      ▾");
        button.setFont(appFont(13, Font.BOLD));
        button.setForeground(new Color(244, 247, 255));
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setBorder(new EmptyBorder(0, 10, 0, 10));
        button.setPreferredSize(new Dimension(210, 31));
        button.setMinimumSize(button.getPreferredSize());
        button.setMaximumSize(button.getPreferredSize());
        return button;
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

    private static ImageIcon resourceIconContained(String name, int maxWidth, int maxHeight, Color tint) {
        BufferedImage source = resourceImage(name); if (source == null) return null;
        return scaledIcon(tinted(source, tint), maxWidth, maxHeight);
    }

    private static ImageIcon menuIcon(String name, int maxWidth, int maxHeight, Color tint) {
        if (!name.startsWith("embedded:")) return resourceIconContained(name, maxWidth, maxHeight, tint);
        String encoded = name.endsWith("price") ? PRICE_MENU_ICON : REPAIR_MENU_ICON;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            BufferedImage source = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            return source == null ? null : scaledIcon(tinted(source, tint), maxWidth, maxHeight);
        } catch (Exception ignored) { return null; }
    }

    private static ImageIcon scaledIcon(BufferedImage source, int maxWidth, int maxHeight) {
        double scale = Math.min(maxWidth / (double) source.getWidth(), maxHeight / (double) source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
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
        String entry = BrowserOpenGate.key(url);
        BrowserOpenGate gate = entry == null ? null : browserOpenGates.get(entry);
        boolean guarded = gate != null;
        if (guarded && !gate.begin()) {
            status(gate.pending() ? "该网页正在打开，请勿重复点击" : "请稍后再打开此链接");
            return;
        }
        if (guarded) { updateWebButtons(); webLinkTimer.start(); }
        String token = accessToken;
        status("正在打开网页…");
        new SwingWorker<String,Void>() {
            protected String doInBackground() {
                if (token == null || token.isBlank()) return url;
                try { return api.browserLoginUrl(token, url); }
                catch (Exception ignored) { return url; }
            }
            protected void done() {
                try {
                    if (!Objects.equals(token, accessToken)) throw new IllegalStateException("登录账户已变化，请重新点击网页入口");
                    Platform.browse(get());
                    if (guarded) gate.opened();
                    status("已在系统浏览器打开网页");
                } catch (Exception e) {
                    if (guarded) gate.failed();
                    error(e.getCause() == null ? e : e.getCause());
                } finally { if (guarded) updateWebButtons(); }
            }
        }.execute();
    }

    private void guardWebButton(JButton button, String entry) {
        button.putClientProperty("tokenpro.webGate", browserOpenGates.get(entry));
        guardedWebButtons.add(button);
    }

    private void updateWebButtons() {
        boolean anyBlocked = false;
        for (JButton button : guardedWebButtons) {
            BrowserOpenGate gate = (BrowserOpenGate) button.getClientProperty("tokenpro.webGate");
            applyWebButtonState(button, gate);
            anyBlocked |= !button.isEnabled();
        }
        if (!anyBlocked) webLinkTimer.stop();
    }

    static void applyWebButtonState(JButton button, BrowserOpenGate gate) {
        boolean pending = gate.pending();
        boolean cooling = gate.secondsRemaining() > 0;
        button.setEnabled(!pending && !cooling);
        // Keep the original label in every state: no countdown or animated suffix.
        button.setToolTipText(pending ? "该网页正在打开，请勿重复点击" : cooling ? "请稍后再打开此链接" : "打开网页");
    }
    private void openApp(String app) {
        status("正在打开 " + app + "…");
        new SwingWorker<Boolean, Void>() {
            // Never terminate a running client here. Codex may still be
            // checkpointing the active turn, and restarting it can reopen an
            // older history snapshot. `openApplication` raises the existing
            // single-instance app or starts it when it is not running.
            protected Boolean doInBackground() throws Exception {
                if ("Codex".equals(app)) CodexImageBridge.resumeIfConfigured(store);
                return Platform.openApplication(app);
            }
            protected void done() {
                try {
                    if (!get()) throw new IllegalStateException("无法打开 " + app);
                    status(app + " 已打开，当前聊天记录不会被中断");
                } catch (Exception e) { error(e.getCause() == null ? e : e.getCause()); }
            }
        }.execute();
    }
    private void reconnectApp(String app) {
        connectClient(app, false);
    }
    private void openTerminal(String command) {
        if(cliSelectedCount(command) == 0) chooseModels(command.equals("codex") ? "Codex" : "Claude", true, true);
        else launchTerminal(command);
    }

    private void applyClaudeCli(List<PricedModel> selected, boolean launch) {
        List<PricedModel> chosen = uniqueModels(ModelPickerDialog.orderedModels(selected, "Claude"));
        if (chosen.isEmpty()) { error(new IllegalStateException("请至少选择一个非生图模型")); return; }
        async("正在配置 Claude 命令行模型…", () -> {
            ApiClient.ManagedKey managed = api.globalKey(accessToken);
            SecureStore cliStore = store.cli("claude");
            ClaudeBridgeConfig config = ClaudeBridgeConfig.createCli(accountId, accessToken, managed, chosen);
            config.save(cliStore);
            ClaudeCliConfig.install(cliStore, chosen, config);
            ClaudeBridgeManager.ensureRunning(cliStore);
            CliLauncher.install(store, "claude");
            return chosen.size();
        }, count -> {
            status("Claude 命令行已配置 " + count + " 个模型，内部列表与客户端排序一致");
            updateCommandControls("claude", claudeCliInstalled);
            if (launch) launchTerminal("claude");
        });
    }

    private void launchTerminal(String command) {
        connectClient(command.equals("codex") ? "Codex" : "Claude", true);
    }

    private void connectClient(String app, boolean cli) {
        if (accessToken == null || accessToken.isBlank()) { error(new IllegalStateException("请先登录 TokenPro")); return; }
        String command = app.toLowerCase(Locale.ROOT);
        String identity = command + (cli ? "-cli" : "-desktop");
        if (!connectingClients.begin(identity)) return;
        refreshConnectControls();
        String token = accessToken, owner = accountId, accountLabel = string(sessionUser.get("email"));
        String label = app + (cli ? " 命令行" : " 客户端");
        try {
            boolean running = cli ? !ClientReconnect.cliProcesses(store, command).isEmpty()
                : !ClientReconnect.desktopProcesses(app).isEmpty();
            if (running && JOptionPane.showConfirmDialog(this,
                "将重启 " + label + " 以加载模型和 TokenPro 通道。\n进行中的请求可能中断，请先保存；其他应用不会关闭。",
                "重新连接", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                finishConnection(identity); return;
            }
        } catch (Exception e) { finishConnection(identity); error(e); return; }
        status("正在核验账户并连接 " + label + "…");
        new SwingWorker<Integer,Void>() {
            protected Integer doInBackground() throws Exception {
                SecureStore target = cli ? store.cli(command) : store;
                Map<String,Object> user = api.me(token);
                if (!ClaudeBridgeServer.sameIdentifier(owner, user.get("id"))) throw new IllegalStateException("账户已变化，请重新登录");
                List<PricedModel> catalog = api.pricedModels(token);
                if(catalog.isEmpty()) throw new IllegalStateException("暂未取得可用模型，原配置保留；请稍后重试");
                List<PricedModel> saved = app.equals("Codex") ? savedCodexModels(target)
                    : ClaudeBridgeConfig.load(target).routes().stream()
                        .map(r -> new PricedModel(r.name(), r.platform(), r.groupName(), r.groupId())).toList();
                List<PricedModel> selected = ModelSelectionReconciler.currentModels(saved, catalog, app);
                if(!Objects.equals(token, accessToken)) throw new IllegalStateException("账户已变化，连接已取消");
                ModelSelectionReconciler.reconcile(target, app.equals("Codex") ? "codex-selected.json" : ClaudeBridgeConfig.FILE, app, owner, catalog);
                if (selected.isEmpty()) throw new IllegalStateException("之前选择的模型已不可用，请重新选择模型；原程序未关闭");
                ApiClient.ManagedKey key = api.globalKey(token);
                ClientReconnect.reconnect(() -> {
                    if (app.equals("Codex")) {
                        CodexConfig config = cli ? new CodexConfig(target, target.root().resolve("home/config.toml")) : codex;
                        config.apply("https://tokenpro.work/v1", selected, key.key(), accountLabel);
                    } else {
                        ClaudeBridgeConfig config = cli ? ClaudeBridgeConfig.createCli(owner, token, key, selected)
                            : ClaudeBridgeConfig.create(owner, token, key, selected);
                        config.save(target);
                        if (cli) ClaudeCliConfig.install(target, selected, config);
                        else ClaudeDesktopConfig.install(target, config, accountLabel);
                    }
                    if (cli) CliLauncher.install(store, command);
                }, () -> {
                    if (!Objects.equals(token, accessToken)) throw new IllegalStateException("账户已变化，连接已取消");
                    if (cli) ClientReconnect.stopCli(store, command); else ClientReconnect.stopDesktop(app);
                    // Restart only this profile's helper so an updated JAR is
                    // actually loaded. Do not stop any other client's bridge.
                    if (app.equals("Codex")) CodexImageBridge.stop(target); else ClaudeBridgeManager.stop(target);
                }, () -> {
                    if (app.equals("Codex")) CodexImageBridge.ensureRunning(target); else ClaudeBridgeManager.ensureRunning(target);
                    if (cli) Platform.openTerminalProgram(CliLauncher.install(store, command), List.of(), Map.of());
                    else if (!(app.equals("Claude") ? Platform.openClaudeThirdParty() : Platform.openApplication(app)))
                        throw new IllegalStateException("无法启动 " + app);
                });
                ClientReconnect.awaitStarted(store, app, cli);
                return selected.size();
            }
            protected void done() {
                finishConnection(identity);
                try {
                    int count = get();
                    if (!cli) setDesktopCardState(app, selectionStatus(count), true);
                    else updateCommandControls(command, true);
                    status(label + " 已启动，已配置 " + count + " 个模型；费用以 TokenPro 用量记录为准");
                    if (app.equals("Codex") && !cli) JOptionPane.showMessageDialog(TokenProFrame.this,
                        "已配置 TokenPro 通道并重新启动客户端。\n原先创建于官方通道的旧任务不保证自动切换，请新建对话使用 TokenPro。\n模型名称不代表计费通道；连接按钮提示会显示是否已观察到 TokenPro 请求，费用以用量记录为准。",
                        "连接说明", JOptionPane.INFORMATION_MESSAGE);
                    lastAccountRefresh = 0; refreshAccountSilently();
                } catch (Exception e) { error(e.getCause() == null ? e : e.getCause()); }
            }
        }.execute();
    }

    private void refreshConnectControls() {
        updateDesktopLaunch("Codex", codexClientInstalled, codexCanConnect);
        updateDesktopLaunch("Claude", claudeClientInstalled, claudeCanConnect);
        updateCommandControls("codex", codexCliInstalled);
        updateCommandControls("claude", claudeCliInstalled);
    }

    private void finishConnection(String identity) {
        connectingClients.finish(identity);
        refreshConnectControls();
        javax.swing.Timer cooldown = new javax.swing.Timer((int)ConnectionGate.COOLDOWN_MS + 100, e -> refreshConnectControls());
        cooldown.setRepeats(false); cooldown.start();
    }

    static List<PricedModel> savedCodexModels(SecureStore store) throws Exception {
        Map<String,Object> saved = Json.object(Json.parse(store.read("codex-selected.json").orElse("{}")));
        List<PricedModel> models = new ArrayList<>();
        for (Object raw : ClaudeAdapter.list(saved.get("models"))) {
            Map<String,Object> row = Json.object(raw);
            if (row.get("group_id") instanceof Number group && row.get("name") instanceof String name && !name.isBlank())
                models.add(new PricedModel(name, string(row.get("platform")), string(row.get("group_name")), group.longValue()));
        }
        return uniqueModels(models);
    }
    private void status(String value) { status.setText(value); }
    private void error(Throwable error) { status("错误：" + ErrorMessages.describe(error)); ErrorMessages.show(this, error); }
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
            setLayout(new GridLayout(0, 1, 0, 2));
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

    private static final class PremiumModelTicker extends JComponent {
        private record Item(String name, Color accent) {}
        private static final int WIDTH = 310;
        private static final int HEIGHT = 34;
        private static final int CONTENT_X = 47;
        private final javax.swing.Timer animation;
        private List<Item> items = List.of();
        private double offset;
        private int cycleWidth = 1;

        PremiumModelTicker() {
            setPreferredSize(new Dimension(WIDTH, HEIGHT));
            setMinimumSize(getPreferredSize());
            setOpaque(false);
            setToolTipText("每个厂商价格最高的两款可用模型");
            animation = new javax.swing.Timer(20, event -> {
                if (items.isEmpty() || cycleWidth <= 1) return;
                offset = (offset + 0.55d) % cycleWidth;
                repaint();
            });
        }

        void setModels(List<PricedModel> models) {
            List<Item> next = new ArrayList<>();
            for (PricedModel model : models) next.add(new Item(model.displayName(), vendorColor(tickerVendor(model))));
            items = List.copyOf(next);
            offset = 0;
            setToolTipText(items.isEmpty() ? "登录后展示每个厂商的高阶模型" : items.stream().map(Item::name).collect(java.util.stream.Collectors.joining(" · ")));
            repaint();
        }

        void reset() { setModels(List.of()); }

        @Override public void addNotify() {
            super.addNotify();
            animation.start();
        }

        @Override public void removeNotify() {
            animation.stop();
            super.removeNotify();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(21, 49, 91, 205), getWidth(), 0, new Color(43, 37, 91, 190)));
            g.fillRoundRect(0, 2, getWidth() - 1, getHeight() - 4, 15, 15);
            g.setColor(new Color(111, 209, 213, 90));
            g.drawRoundRect(0, 2, getWidth() - 2, getHeight() - 5, 15, 15);
            g.setFont(appFont(10, Font.BOLD));
            g.setColor(new Color(138, 239, 220));
            g.drawString("精选", 12, 21);
            Shape originalClip = g.getClip();
            g.clipRect(CONTENT_X, 3, getWidth() - CONTENT_X - 5, getHeight() - 6);
            if (items.isEmpty()) {
                g.setFont(appFont(10, Font.PLAIN));
                g.setColor(new Color(184, 196, 225));
                g.drawString("登录后展示高阶模型", CONTENT_X + 7, 21);
            } else {
                Font font = appFont(10, Font.BOLD);
                g.setFont(font);
                FontMetrics metrics = g.getFontMetrics(font);
                List<Integer> widths = items.stream().map(item -> metrics.stringWidth(item.name()) + 27).toList();
                cycleWidth = widths.stream().mapToInt(Integer::intValue).sum() + items.size() * 7 + 28;
                double x = CONTENT_X - offset;
                while (x + cycleWidth < CONTENT_X) x += cycleWidth;
                for (int cycle = 0; cycle < 2 || x < getWidth(); cycle++) {
                    for (int index = 0; index < items.size(); index++) {
                        Item item = items.get(index);
                        int itemWidth = widths.get(index);
                        int drawX = (int) Math.round(x);
                        g.setColor(new Color(item.accent().getRed(), item.accent().getGreen(), item.accent().getBlue(), 38));
                        g.fillRoundRect(drawX, 6, itemWidth, 22, 11, 11);
                        g.setColor(new Color(item.accent().getRed(), item.accent().getGreen(), item.accent().getBlue(), 145));
                        g.fillOval(drawX + 8, 14, 5, 5);
                        g.setColor(new Color(235, 240, 255));
                        g.drawString(item.name(), drawX + 18, 21);
                        x += itemWidth + 7;
                    }
                    x += 28;
                    if (x >= getWidth() && cycle >= 1) break;
                }
            }
            g.setClip(originalClip);
            g.setPaint(new GradientPaint(CONTENT_X, 0, new Color(24, 46, 88, 230), CONTENT_X + 18, 0, new Color(24, 46, 88, 0)));
            g.fillRect(CONTENT_X, 4, 18, getHeight() - 8);
            g.setPaint(new GradientPaint(getWidth() - 20, 0, new Color(40, 39, 89, 0), getWidth() - 4, 0, new Color(40, 39, 89, 225)));
            g.fillRect(getWidth() - 20, 4, 16, getHeight() - 8);
            g.dispose();
        }

        private static Color vendorColor(String vendor) {
            return switch (vendor) {
                case "GPT" -> new Color(91, 225, 201);
                case "Claude" -> new Color(238, 126, 82);
                case "Gemini" -> new Color(107, 145, 255);
                case "Grok" -> new Color(184, 155, 255);
                default -> new Color(170, 185, 225);
            };
        }
    }

    private static final class MiniModelBadge extends JLabel {
        private final Color glow;
        private int occludingCardOffset = -1;
        private int occludingCardWidth;

        MiniModelBadge(String modelName, Icon icon, Color glow, int width, String tooltip) {
            super(modelName, icon, SwingConstants.CENTER);
            this.glow = glow;
            setFont(appFont(10, Font.BOLD));
            setForeground(new Color(244, 247, 255));
            setHorizontalTextPosition(SwingConstants.LEFT);
            setVerticalTextPosition(SwingConstants.CENTER);
            setIconTextGap(6);
            setToolTipText(tooltip);
            setPreferredSize(new Dimension(width, 30));
            setMinimumSize(getPreferredSize());
            setOpaque(false);
        }

        void setOccludingCard(int occludingCardOffset, int occludingCardWidth) {
            this.occludingCardOffset = occludingCardOffset;
            this.occludingCardWidth = occludingCardWidth;
        }

        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            if (occludingCardOffset >= 0) {
                Area visibleArea = new Area(new Rectangle2D.Float(0, 0, getWidth(), getHeight()));
                visibleArea.subtract(new Area(new RoundRectangle2D.Float(
                    occludingCardOffset + 1, 1, occludingCardWidth - 2, getHeight() - 2, 13, 13)));
                g.clip(visibleArea);
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(2, 2, new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 118), getWidth(), getHeight(), new Color(39, 45, 99, 165)));
            g.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 13, 13);
            g.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 185));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 13, 13);
            g.setColor(new Color(255, 255, 255, 190));
            g.fillOval(getWidth() - 7, 4, 2, 2);
            super.paintComponent(g);
            g.dispose();
        }
    }

    private static final class OverlappingModelBadges extends JPanel {
        private static final int CARD_HEIGHT = 30;
        private static final int OVERLAP = 12;

        OverlappingModelBadges(List<MiniModelBadge> cards) {
            super(null);
            setOpaque(false);
            int cardsWidth = 0;
            for (int index = 0; index < cards.size(); index++) {
                cardsWidth += cards.get(index).getPreferredSize().width;
                if (index < cards.size() - 1) cardsWidth -= OVERLAP;
            }
            MoreModelsBadge more = new MoreModelsBadge();
            setPreferredSize(new Dimension(cardsWidth + 8 + more.getPreferredSize().width, 34));
            setMinimumSize(getPreferredSize());
            int x = 0;
            for (int index = 0; index < cards.size(); index++) {
                MiniModelBadge card = cards.get(index);
                int width = card.getPreferredSize().width;
                if (index < cards.size() - 1) {
                    card.setOccludingCard(width - OVERLAP, cards.get(index + 1).getPreferredSize().width);
                } else {
                    card.setOccludingCard(-1, 0);
                }
                card.setBounds(x, 2, width, CARD_HEIGHT);
                add(card);
                setComponentZOrder(card, 0);
                x += width - OVERLAP;
            }
            more.setBounds(cardsWidth + 8, 2, more.getPreferredSize().width, more.getPreferredSize().height);
            add(more);
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

    private static final class SupportCountLabel extends JLabel {
        SupportCountLabel() {
            super("✦ 多款模型可连接", SwingConstants.CENTER);
            setFont(appFont(10, Font.BOLD));
            setForeground(new Color(152, 240, 218));
            setPreferredSize(new Dimension(126, 36));
            setMinimumSize(getPreferredSize());
            setToolTipText("登录后显示当前账户可连接的模型数量");
            setOpaque(false);
        }

        void setCount(long count) {
            setText(count > 0 ? "✦ 支持 " + count + " 款模型" : "✦ 暂无可用模型");
        }

        void reset() { setText("✦ 多款模型可连接"); }

        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(28, 120, 114, 120), getWidth(), getHeight(), new Color(76, 58, 155, 145)));
            g.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 16, 16);
            g.setColor(new Color(103, 222, 195, 150));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 16, 16);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class ModelSelectorControl extends JPanel {
        private final JButton anchor;

        ModelSelectorControl(JButton anchor, JLabel state) {
            super();
            this.anchor = anchor;
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(4, 0, 5, 0));
            setPreferredSize(new Dimension(210, 60));
            setMinimumSize(getPreferredSize());
            setMaximumSize(getPreferredSize());
            anchor.setAlignmentX(Component.LEFT_ALIGNMENT);
            state.setAlignmentX(Component.LEFT_ALIGNMENT);
            state.setBorder(new EmptyBorder(0, 10, 0, 8));
            state.setPreferredSize(new Dimension(210, 20));
            state.setMinimumSize(state.getPreferredSize());
            state.setMaximumSize(state.getPreferredSize());
            add(anchor);
            add(state);
            anchor.getModel().addChangeListener(event -> repaint());
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ButtonModel model = anchor.getModel();
            g.setPaint(new GradientPaint(0, 0,
                model.isRollover() ? new Color(42, 61, 112, 232) : new Color(30, 47, 91, 224),
                getWidth(), getHeight(), new Color(42, 47, 105, 224)));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.setColor(model.isRollover() ? new Color(126, 153, 229, 170) : new Color(102, 128, 198, 120));
            g.drawRoundRect(0, 0, getWidth() - 2, getHeight() - 2, 16, 16);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class CosmosMenuButton extends JButton {
        private final Color hover;

        CosmosMenuButton(String text, String iconName, Color foreground, Color hover) {
            super(text);
            this.hover = hover;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setDoubleBuffered(true);
            setFont(appFont(11, Font.BOLD));
            setForeground(foreground);
            setIcon(menuIcon(iconName, 16, 16, foreground));
            setIconTextGap(10);
            setBorder(new EmptyBorder(0, 14, 0, 14));
            setHorizontalAlignment(SwingConstants.LEFT);
        }

        protected void paintComponent(Graphics graphics) {
            ButtonModel model = getModel();
            if (model.isArmed() || model.isSelected()) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(hover);
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
            setForeground(statusColor(text));
        }

        private static Color statusColor(String text) {
            if (text != null && text.startsWith("当前：") && text.contains("官方")) return STATUS_OFFICIAL;
            return text != null && text.startsWith("当前：TokenPro") ? STATUS_READY : STATUS_PENDING;
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
            Object rawProgress = getClientProperty("tokenpro.updateProgress");
            if (rawProgress instanceof Number number && number.intValue() >= 0) {
                int progress = Math.min(100, number.intValue());
                Shape oldClip = g.getClip();
                g.clip(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 18, 18));
                g.setPaint(new GradientPaint(0, 0, new Color(91, 101, 255, 215), getWidth(), 0, new Color(44, 178, 213, 215)));
                g.fillRect(0, 0, (int) Math.round(getWidth() * progress / 100.0), getHeight());
                g.setClip(oldClip);
                g.setColor(stroke); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            }
            g.setFont(getFont()); g.setColor("latest".equals(updateState) ? new Color(218, 248, 239) : (isEnabled() ? getForeground() : new Color(137, 145, 177))); FontMetrics fm = g.getFontMetrics(); Icon icon = getIcon(); int textWidth = fm.stringWidth(getText()); int iconWidth = icon == null ? 0 : icon.getIconWidth(); int gap = icon == null || getText().isBlank() ? 0 : getIconTextGap(); int total = iconWidth + gap + textWidth; int x = (getWidth() - total) / 2; if (icon != null) { icon.paintIcon(this, g, x, (getHeight() - icon.getIconHeight()) / 2); x += iconWidth + gap; } g.drawString(getText(), x, (getHeight() - fm.getHeight()) / 2 + fm.getAscent()); g.dispose();
        }
    }
}

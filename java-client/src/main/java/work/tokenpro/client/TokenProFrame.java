package work.tokenpro.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;

final class TokenProFrame extends JFrame {
    private static final Color PURPLE = new Color(102, 82, 240);
    private static final Color CANVAS = new Color(251, 251, 253);
    private static final Color SIDEBAR = new Color(244, 244, 248);
    private static final Color MUTED = new Color(104, 106, 116);
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
    private final DefaultListModel<PricedModel> claudeModels = new DefaultListModel<>();
    private final JList<PricedModel> claudeModelList = new JList<>(claudeModels);
    private final JLabel bridgeStatus = new JLabel("桥接状态：未检测");
    private final CardLayout pages = new CardLayout();
    private final JPanel pageHost = new JPanel(pages);
    private final Map<String, NavButton> navButtons = new LinkedHashMap<>();
    private final JLabel headerTitle = new JLabel("TokenPro");
    private final JLabel headerUser = new JLabel("登录账户");
    private final JLabel headerBalance = new JLabel("—");
    private final JLabel accountBalance = new JLabel("—");
    private final JLabel homeClaudeStatus = new JLabel("请先选择模型");
    private String accessToken;
    private String accountId = "";

    TokenProFrame(SecureStore store) {
        super("TokenPro");
        this.store = store;
        this.codex = new CodexConfig(store);
        URL iconUrl = TokenProFrame.class.getResource("/assets/TokenProCosmosIcon.png");
        if (iconUrl != null) setIconImage(new ImageIcon(iconUrl).getImage());
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(940, 650));
        setSize(1040, 720);
        setLocationRelativeTo(null);
        setContentPane(content());
        restoreSession();
    }

    private JComponent content() {
        JPanel root = new JPanel(new BorderLayout()); root.setBackground(CANVAS);
        root.add(sidebar(), BorderLayout.WEST);
        JPanel main = new JPanel(new BorderLayout()); main.setBackground(CANVAS); main.add(header(), BorderLayout.NORTH);
        pageHost.setBackground(CANVAS);
        pageHost.add(scroll(homePanel()), "首页");
        pageHost.add(scroll(connectionPanel()), "Codex 连接");
        pageHost.add(scroll(claudePanel()), "Claude 连接");
        pageHost.add(scroll(accountPanel()), "我的账户");
        pageHost.add(scroll(toolsPanel()), "工具");
        main.add(pageHost, BorderLayout.CENTER);
        status.setBorder(new EmptyBorder(7, 26, 10, 26)); status.setForeground(MUTED); status.setFont(appFont(11, Font.PLAIN));
        main.add(status, BorderLayout.SOUTH); root.add(main, BorderLayout.CENTER); showPage("首页");
        return root;
    }

    private JComponent sidebar() {
        JPanel panel = new JPanel(new BorderLayout()); panel.setBackground(SIDEBAR); panel.setPreferredSize(new Dimension(226, 650));
        JPanel top = new JPanel(); top.setOpaque(false); top.setBorder(new EmptyBorder(25, 12, 10, 12)); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel brand = new JLabel("  ◈  TokenPro"); brand.setFont(appFont(17, Font.BOLD)); brand.setForeground(new Color(30, 30, 36)); brand.setBorder(new EmptyBorder(0, 5, 24, 0)); top.add(brand);
        addNav(top, "首页", "✦");
        JButton backend = sideAction("⚙   后台管理"); backend.addActionListener(e -> browse("https://tokenpro.work/admin/dashboard")); top.add(backend); top.add(Box.createVerticalStrut(6));
        JButton docs = sideAction("▤   使用文档"); docs.addActionListener(e -> browse("https://tokenpro.work/docs")); top.add(docs); top.add(Box.createVerticalStrut(6));
        JButton tools = sideAction("•••   工具"); tools.addActionListener(e -> showPage("工具")); top.add(tools); panel.add(top, BorderLayout.NORTH);
        JPanel bottom = new JPanel(new BorderLayout()); bottom.setOpaque(false); bottom.setBorder(new EmptyBorder(0, 12, 18, 12)); NavButton accountNav = new NavButton("●   我的账户"); accountNav.addActionListener(e -> openAccount()); navButtons.put("我的账户", accountNav); bottom.add(accountNav); panel.add(bottom, BorderLayout.SOUTH); return panel;
    }

    private void addNav(JPanel parent, String page, String icon) { NavButton button = new NavButton(icon + "   " + page); button.addActionListener(e -> showPage(page)); navButtons.put(page, button); parent.add(button); parent.add(Box.createVerticalStrut(6)); }
    private JButton sideAction(String text) { JButton button = new JButton(text); button.setFont(appFont(13, Font.PLAIN)); button.setHorizontalAlignment(SwingConstants.LEFT); button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42)); button.setBorder(new EmptyBorder(0, 16, 0, 16)); button.setFocusPainted(false); button.setContentAreaFilled(false); return button; }

    private JComponent header() {
        GradientPanel panel = new GradientPanel(); panel.setLayout(new BorderLayout(0, 18)); panel.setBorder(new EmptyBorder(24, 28, 21, 28));
        JPanel title = transparent(new BorderLayout()); headerTitle.setFont(appFont(24, Font.BOLD)); title.add(headerTitle, BorderLayout.WEST);
        JButton user = soft("●  登录账户"); user.addActionListener(e -> openAccount()); headerUser.addPropertyChangeListener("text", e -> user.setText("●  " + headerUser.getText())); title.add(user, BorderLayout.EAST); panel.add(title, BorderLayout.NORTH);
        RoundedPanel wallet = new RoundedPanel(16, new Color(255, 255, 255, 125)); wallet.setLayout(new FlowLayout(FlowLayout.LEFT, 14, 11)); wallet.setBorder(new EmptyBorder(0, 7, 0, 7));
        JPanel captions = transparent(); captions.setLayout(new BoxLayout(captions, BoxLayout.Y_AXIS)); JLabel balanceText = new JLabel("钱包余额"); balanceText.setFont(appFont(12, Font.PLAIN)); balanceText.setForeground(MUTED); JLabel rate = new JLabel("充值比例  1￥ = 1$"); rate.setFont(appFont(10, Font.PLAIN)); rate.setForeground(MUTED); captions.add(balanceText); captions.add(rate); wallet.add(captions);
        headerBalance.setFont(appFont(27, Font.BOLD)); wallet.add(headerBalance); JButton refresh = soft("↻"); refresh.addActionListener(e -> refreshAccount()); wallet.add(refresh); JButton recharge = soft("＋ 充值"); recharge.addActionListener(e -> browse("https://tokenpro.work/purchase")); wallet.add(recharge);
        JPanel row = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0)); row.add(wallet); panel.add(row, BorderLayout.CENTER); return panel;
    }

    private JComponent homePanel() {
        JPanel panel = vertical(); JLabel title = new JLabel("我的客户端"); title.setFont(appFont(14, Font.BOLD)); panel.add(title); panel.add(Box.createVerticalStrut(11));
        panel.add(clientCard("C", "Codex 客户端", "桌面应用 · 独立登录", Platform.applicationInstalled("Codex"), "选择模型", () -> showPage("Codex 连接"), () -> openApp("Codex"), null)); panel.add(Box.createVerticalStrut(12));
        panel.add(clientCard("A", "Claude 客户端", "桌面应用 · 本地安全桥接", Platform.applicationInstalled("Claude"), "选择模型", () -> showPage("Claude 连接"), this::openClaude, homeClaudeStatus)); panel.add(Box.createVerticalStrut(12));
        panel.add(clientCard("›_", "Codex 命令行", "命令行工具 · Codex CLI", Platform.commandInstalled("codex"), "配置连接", () -> showPage("Codex 连接"), () -> openApp("Codex"), null)); panel.add(Box.createVerticalStrut(12));
        panel.add(clientCard("›_", "Claude 命令行", "命令行工具 · Claude Code", Platform.commandInstalled("claude"), "配置连接", () -> showPage("Claude 连接"), this::openClaude, null)); panel.add(Box.createVerticalGlue()); return panel;
    }

    private JComponent clientCard(String icon, String title, String subtitle, boolean installed, String setup, Runnable configure, Runnable open, JLabel state) {
        RoundedPanel card = card(); card.setLayout(new BorderLayout(16, 0));
        JLabel badge = new JLabel(icon, SwingConstants.CENTER);
        if (title.startsWith("Codex")) { badge.setText(""); badge.setIcon(OfficialIcons.client("Codex", 52)); }
        else if (title.startsWith("Claude")) { badge.setText(""); badge.setIcon(OfficialIcons.client("Claude", 52)); }
        badge.setFont(appFont(icon.equals("›_") ? 17 : 26, Font.BOLD)); badge.setForeground(PURPLE); badge.setPreferredSize(new Dimension(54, 54)); card.add(badge, BorderLayout.WEST);
        JPanel words = transparent(); words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS)); JLabel heading = new JLabel(title + "   " + (installed ? "已安装" : "未安装")); heading.setFont(appFont(19, Font.BOLD)); JLabel detail = new JLabel(subtitle); detail.setFont(appFont(12, Font.PLAIN)); detail.setForeground(MUTED); words.add(Box.createVerticalStrut(4)); words.add(heading); words.add(Box.createVerticalStrut(7)); words.add(detail); card.add(words, BorderLayout.CENTER);
        JPanel actions = transparent(); actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS)); JPanel buttons = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0)); JButton choose = soft(setup); choose.addActionListener(e -> configure.run()); JButton launch = primary("打开应用"); launch.addActionListener(e -> open.run()); buttons.add(choose); buttons.add(launch); actions.add(buttons); if (state != null) { state.setFont(appFont(11, Font.BOLD)); state.setForeground(PURPLE); state.setAlignmentX(Component.RIGHT_ALIGNMENT); actions.add(Box.createVerticalStrut(7)); actions.add(state); } card.add(actions, BorderLayout.EAST); return card;
    }

    private JComponent accountPanel() {
        JPanel panel = vertical();
        JLabel heading = new JLabel("我的账户");
        heading.setFont(appFont(22, Font.BOLD));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(heading);
        panel.add(Box.createVerticalStrut(24));
        JLabel balanceTitle = new JLabel("账户余额");
        balanceTitle.setFont(appFont(12, Font.PLAIN));
        balanceTitle.setForeground(MUTED);
        panel.add(balanceTitle);
        accountBalance.setFont(appFont(32, Font.BOLD));
        accountBalance.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(Box.createVerticalStrut(8));
        panel.add(accountBalance);
        panel.add(Box.createVerticalStrut(28));
        JButton logout = new JButton("退出账户");
        soft(logout);
        logout.addActionListener(e -> logout());
        logout.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(logout);
        return panel;
    }

    private void openAccount() {
        if (accessToken == null) { showLoginDialog(); return; }
        showPage("我的账户");
    }

    private void showLoginDialog() {
        JPanel form = new JPanel(new GridLayout(0, 1, 6, 6));
        form.add(new JLabel("邮箱")); form.add(email);
        form.add(new JLabel("密码")); form.add(password);
        int choice = JOptionPane.showConfirmDialog(this, form, "登录 TokenPro", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        async("正在登录…", () -> {
            Map<String, Object> result = api.login(email.getText(), new String(password.getPassword()));
            accessToken = string(result.get("access_token"));
            if (accessToken.isBlank()) throw new IllegalStateException("登录响应缺少 access_token");
            store.write("java-session.json", Json.stringify(Map.of("access_token", accessToken)));
            return result.containsKey("user") ? Json.object(result.get("user")) : api.me(accessToken);
        }, user -> { showAccount(user); showPage("我的账户"); });
    }

    private void logout() {
        try {
            store.delete("java-session.json"); accessToken = null; accountId = ""; keys.clear();
            account.setText("尚未登录"); headerUser.setText("登录账户"); headerBalance.setText("—"); accountBalance.setText("—");
            showPage("首页"); status("已退出账户");
        } catch (Exception ex) { error(ex); }
    }

    private JComponent connectionPanel() {
        JPanel panel = vertical();
        panel.add(pageHeading("Codex 连接", "将 TokenPro 接口安全接入 Codex，令牌不会写入 config.toml。"));
        panel.add(Box.createVerticalStrut(18));
        panel.add(row("连接名称", name));
        panel.add(row("接口地址", baseUrl));
        panel.add(row("模型 ID", model));
        panel.add(row("API Key", apiKey));
        JLabel hint = new JLabel("Key 只保存在当前系统用户的 TokenPro 配置目录中，不写入 Codex config.toml。");
        hint.setForeground(Color.GRAY);
        panel.add(hint);
        panel.add(Box.createVerticalStrut(12));
        JButton apply = new JButton("应用到 Codex");
        primary(apply);
        apply.addActionListener(e -> {
            try {
                codex.apply(baseUrl.getText(), model.getText(), new String(apiKey.getPassword()));
                Arrays.fill(apiKey.getPassword(), '\0');
                apiKey.setText("");
                status("Codex 配置已更新，重新打开 Codex 后生效");
            } catch (Exception ex) { error(ex); }
        });
        JButton restore = new JButton("恢复接入前配置");
        soft(restore);
        restore.addActionListener(e -> { try { codex.restore(); status("Codex 原配置已恢复"); } catch (Exception ex) { error(ex); } });
        JPanel buttons = transparent(new FlowLayout(FlowLayout.LEFT));
        buttons.add(apply); buttons.add(restore);
        panel.add(buttons);
        return panel;
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
        panel.add(pageHeading("选择 Claude 桌面模型", "勾选后直接在 Claude 桌面版内切换，无需反复配置。")); panel.add(Box.createVerticalStrut(15));
        JLabel hint = new JLabel("共用一把专用 Key，TokenPro 会按模型自动切换分组并维护本地连接。");
        hint.setForeground(Color.GRAY); panel.add(hint); panel.add(Box.createVerticalStrut(10));
        JButton load = new JButton("加载可用模型");
        soft(load);
        load.setAlignmentX(Component.LEFT_ALIGNMENT); load.addActionListener(e -> loadClaudeModels()); panel.add(load);
        claudeModelList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        claudeModelList.setVisibleRowCount(6); claudeModelList.setFixedCellHeight(46); claudeModelList.setCellRenderer(new ModelRenderer());
        JScrollPane modelScroll = new JScrollPane(claudeModelList); modelScroll.setPreferredSize(new Dimension(640, 230)); modelScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 250)); panel.add(modelScroll);
        panel.add(Box.createVerticalStrut(8)); panel.add(bridgeStatus);
        JButton apply = new JButton("应用并启动 Claude 桥接"); apply.addActionListener(e -> applyClaude());
        primary(apply);
        JButton check = new JButton("检测桥接"); check.addActionListener(e -> updateBridgeStatus());
        soft(check);
        JButton restore = new JButton("恢复 Claude 官方配置");
        soft(restore);
        restore.addActionListener(e -> { try { ClaudeDesktopConfig.restoreOfficial(store); bridgeStatus.setText("桥接状态：Claude 已恢复官方配置"); status("Claude 已恢复官方配置"); } catch (Exception ex) { error(ex); } });
        JPanel buttons = transparent(new FlowLayout(FlowLayout.LEFT)); buttons.add(apply); buttons.add(check); buttons.add(restore); panel.add(buttons);
        SwingUtilities.invokeLater(this::updateBridgeStatus);
        return panel;
    }

    private void loadClaudeModels() {
        if (accessToken == null) { error(new IllegalStateException("请先登录 TokenPro")); return; }
        async("正在加载模型广场…", () -> api.pricedModels(accessToken), models -> {
            claudeModels.clear();
            for (PricedModel item : models) if (!item.name().toLowerCase(Locale.ROOT).contains("image")) claudeModels.addElement(item);
            status("已加载 " + claudeModels.size() + " 个可用模型，可按 Ctrl/Cmd 或 Shift 多选");
        });
    }

    private void applyClaude() {
        if (accessToken == null || accountId.isBlank()) { error(new IllegalStateException("请先登录 TokenPro")); return; }
        List<PricedModel> selected = claudeModelList.getSelectedValuesList();
        if (selected.isEmpty()) { error(new IllegalStateException("请至少选择一个模型")); return; }
        async("正在创建 Claude 专用连接…", () -> {
            ApiClient.ManagedKey managed = api.claudeManagedKey(accessToken, selected.getFirst().groupId());
            ClaudeBridgeConfig config = ClaudeBridgeConfig.create(accountId, accessToken, managed, selected);
            config.save(store); ClaudeDesktopConfig.install(store, config); ClaudeBridgeManager.ensureRunning(store); return config;
        }, config -> { bridgeStatus.setText("桥接状态：运行中 · " + config.routes().size() + " 个模型 · " + config.baseUrl()); homeClaudeStatus.setText("已选 " + config.routes().size() + " 个模型"); status("Claude 桥接已应用，重新打开 Claude 后生效"); });
    }

    private void updateBridgeStatus() {
        try { ClaudeBridgeConfig config = ClaudeBridgeConfig.load(store); boolean healthy = ClaudeBridgeManager.healthy(store); bridgeStatus.setText("桥接状态：" + (healthy ? "运行中" : "已配置") + " · " + config.routes().size() + " 个模型"); homeClaudeStatus.setText("已选 " + config.routes().size() + " 个模型"); }
        catch (Exception e) { bridgeStatus.setText("桥接状态：未配置"); homeClaudeStatus.setText("请先选择模型"); }
    }

    private void openClaude() {
        try { ClaudeBridgeConfig.load(store); ClaudeBridgeManager.ensureRunning(store); openApp("Claude"); updateBridgeStatus(); }
        catch (Exception e) { error(e); }
    }

    private void restoreSession() {
        async("正在恢复登录…", () -> {
            Optional<String> raw = store.read("java-session.json");
            if (raw.isEmpty()) return null;
            accessToken = string(Json.object(Json.parse(raw.get())).get("access_token"));
            return accessToken.isBlank() ? null : api.me(accessToken);
        }, value -> { if (value == null) status("就绪"); else showAccount(value); });
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
        String emailValue = string(user.get("email"));
        Object rawBalance = user.get("balance");
        String balance = rawBalance instanceof Number number ? String.format(Locale.ROOT, "$%.2f", number.doubleValue()) : "—";
        account.setText("已登录：" + emailValue + "    余额：" + balance);
        headerUser.setText(emailValue.isBlank() ? "我的账户" : emailValue);
        headerBalance.setText(balance);
        accountBalance.setText(balance);
        accountId = string(user.get("id"));
        password.setText("");
        status("登录成功");
    }

    private JPanel vertical() {
        JPanel panel = new JPanel();
        panel.setBackground(CANVAS);
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
        navButtons.forEach((name, button) -> button.setSelected(name.equals(page)));
    }

    private JScrollPane scroll(JComponent view) {
        JScrollPane scroll = new JScrollPane(view); scroll.setBorder(null); scroll.getViewport().setBackground(CANVAS);
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

    private RoundedPanel card() {
        RoundedPanel panel = new RoundedPanel(15, Color.WHITE); panel.setBorder(new EmptyBorder(18, 18, 18, 18));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT); panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 112)); return panel;
    }

    private JButton primary(String text) { JButton button = new JButton(text); primary(button); return button; }
    private void primary(JButton button) {
        button.setFont(appFont(13, Font.BOLD)); button.setForeground(Color.WHITE); button.setBackground(PURPLE);
        button.setOpaque(true); button.setBorder(new EmptyBorder(11, 17, 11, 17)); button.setFocusPainted(false);
        button.setMaximumSize(button.getPreferredSize());
    }

    private JButton soft(String text) { JButton button = new JButton(text); soft(button); return button; }
    private void soft(JButton button) {
        button.setFont(appFont(13, Font.BOLD)); button.setForeground(new Color(42, 42, 48)); button.setBackground(new Color(241, 241, 244));
        button.setBorder(new EmptyBorder(10, 15, 10, 15)); button.setFocusPainted(false);
        button.setMaximumSize(button.getPreferredSize());
    }

    private static JPanel transparent() { return transparent(new FlowLayout(FlowLayout.LEFT, 0, 0)); }
    private static JPanel transparent(LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setOpaque(false); return panel; }
    private static Font appFont(float size, int style) { return new Font(Platform.OS_KIND == Platform.OS.MAC ? ".AppleSystemUIFont" : "SansSerif", style, Math.round(size)); }

    private <T> void async(String running, Callable<T> task, java.util.function.Consumer<T> done) {
        status(running);
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception { return task.call(); }
            protected void done() { try { done.accept(get()); } catch (Exception e) { error(e.getCause() == null ? e : e.getCause()); } }
        }.execute();
    }

    private void browse(String url) {
        SwingUtilities.invokeLater(() -> new InAppBrowserDialog(this, url).setVisible(true));
    }
    private void openApp(String app) { try { Platform.openApplication(app); } catch (Exception e) { error(e); } }
    private void status(String value) { status.setText(value); }
    private void error(Throwable error) { status("错误：" + error.getMessage()); JOptionPane.showMessageDialog(this, error.getMessage(), "TokenPro", JOptionPane.ERROR_MESSAGE); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private record KeyItem(long id, String name, String status) { public String toString() { return name + "  [" + status + "]"; } }

    private static final class ModelRenderer extends DefaultListCellRenderer {
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            label.setBorder(new EmptyBorder(5, 12, 5, 12)); label.setFont(appFont(13, selected ? Font.BOLD : Font.PLAIN));
            label.setBackground(selected ? new Color(235, 232, 255) : Color.WHITE); label.setForeground(selected ? PURPLE : new Color(42, 42, 48)); return label;
        }
    }

    private static final class NavButton extends JButton {
        private boolean selected;
        NavButton(String text) { super(text); setFont(appFont(13, Font.PLAIN)); setHorizontalAlignment(SwingConstants.LEFT); setMaximumSize(new Dimension(Integer.MAX_VALUE, 42)); setBorder(new EmptyBorder(0, 16, 0, 16)); setFocusPainted(false); setContentAreaFilled(false); }
        public void setSelected(boolean value) { super.setSelected(value); selected = value; setFont(appFont(13, value ? Font.BOLD : Font.PLAIN)); repaint(); }
        protected void paintComponent(Graphics g) { if (selected) { Graphics2D g2 = (Graphics2D) g.create(); g2.setColor(new Color(102, 82, 240, 25)); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10); g2.dispose(); } super.paintComponent(g); }
    }

    private static class RoundedPanel extends JPanel {
        private final int radius; private final Color fill;
        RoundedPanel(int radius, Color fill) { this.radius = radius; this.fill = fill; setOpaque(false); }
        protected void paintComponent(Graphics g) { Graphics2D g2 = (Graphics2D) g.create(); g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g2.setColor(fill); g2.fill(new RoundRectangle2D.Double(.5, .5, getWidth()-1, getHeight()-1, radius, radius)); g2.setColor(new Color(0,0,0,18)); g2.draw(new RoundRectangle2D.Double(.5, .5, getWidth()-1, getHeight()-1, radius, radius)); g2.dispose(); super.paintComponent(g); }
    }

    private static final class GradientPanel extends JPanel {
        GradientPanel() { setOpaque(false); }
        protected void paintComponent(Graphics g) { Graphics2D g2 = (Graphics2D) g.create(); g2.setPaint(new GradientPaint(0, 0, new Color(232, 238, 255), getWidth(), getHeight(), new Color(244, 235, 255))); g2.fillRect(0, 0, getWidth(), getHeight()); g2.dispose(); super.paintComponent(g); }
    }
}

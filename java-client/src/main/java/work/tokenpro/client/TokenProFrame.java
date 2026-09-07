package work.tokenpro.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;

final class TokenProFrame extends JFrame {
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
    private String accessToken;

    TokenProFrame(SecureStore store) {
        super("TokenPro");
        this.store = store;
        this.codex = new CodexConfig(store);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(760, 540));
        setSize(860, 620);
        setLocationRelativeTo(null);
        setContentPane(content());
        restoreSession();
    }

    private JComponent content() {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(new EmptyBorder(18, 18, 14, 18));
        JLabel title = new JLabel("TokenPro 跨平台客户端");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        root.add(title, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("账户", accountPanel());
        tabs.addTab("Codex 连接", connectionPanel());
        tabs.addTab("工具", toolsPanel());
        root.add(tabs, BorderLayout.CENTER);
        status.setBorder(new EmptyBorder(6, 4, 0, 4));
        root.add(status, BorderLayout.SOUTH);
        return root;
    }

    private JComponent accountPanel() {
        JPanel panel = vertical();
        panel.add(account);
        panel.add(Box.createVerticalStrut(14));
        panel.add(row("邮箱", email));
        panel.add(row("密码", password));
        JButton login = new JButton("登录 TokenPro");
        login.addActionListener(e -> async("正在登录…", () -> {
            Map<String, Object> result = api.login(email.getText(), new String(password.getPassword()));
            accessToken = string(result.get("access_token"));
            if (accessToken.isBlank()) throw new IllegalStateException("登录响应缺少 access_token");
            store.write("java-session.json", Json.stringify(Map.of("access_token", accessToken)));
            return result.containsKey("user") ? Json.object(result.get("user")) : api.me(accessToken);
        }, this::showAccount));
        JButton logout = new JButton("退出本机登录");
        logout.addActionListener(e -> {
            try { store.delete("java-session.json"); accessToken = null; keys.clear(); account.setText("尚未登录"); status("已清除本机登录信息"); }
            catch (Exception ex) { error(ex); }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(login); buttons.add(logout);
        panel.add(buttons);
        panel.add(Box.createVerticalStrut(14));
        JButton loadKeys = new JButton("读取账户 API Key");
        loadKeys.addActionListener(e -> loadKeys());
        panel.add(loadKeys);
        keyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        keyList.setVisibleRowCount(8);
        keyList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && keyList.getSelectedValue() != null) importKey(keyList.getSelectedValue());
        });
        panel.add(new JScrollPane(keyList));
        return panel;
    }

    private JComponent connectionPanel() {
        JPanel panel = vertical();
        panel.add(row("连接名称", name));
        panel.add(row("接口地址", baseUrl));
        panel.add(row("模型 ID", model));
        panel.add(row("API Key", apiKey));
        JLabel hint = new JLabel("Key 只保存在当前系统用户的 TokenPro 配置目录中，不写入 Codex config.toml。");
        hint.setForeground(Color.GRAY);
        panel.add(hint);
        panel.add(Box.createVerticalStrut(12));
        JButton apply = new JButton("应用到 Codex");
        apply.addActionListener(e -> {
            try {
                codex.apply(baseUrl.getText(), model.getText(), new String(apiKey.getPassword()));
                Arrays.fill(apiKey.getPassword(), '\0');
                apiKey.setText("");
                status("Codex 配置已更新，重新打开 Codex 后生效");
            } catch (Exception ex) { error(ex); }
        });
        JButton restore = new JButton("恢复接入前配置");
        restore.addActionListener(e -> { try { codex.restore(); status("Codex 原配置已恢复"); } catch (Exception ex) { error(ex); } });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(apply); buttons.add(restore);
        panel.add(buttons);
        return panel;
    }

    private JComponent toolsPanel() {
        JPanel panel = vertical();
        for (var item : List.of(
            new AbstractMap.SimpleEntry<>("打开 TokenPro 网站", (Runnable) () -> browse("https://tokenpro.work")),
            new AbstractMap.SimpleEntry<>("打开充值页面", (Runnable) () -> browse("https://tokenpro.work/purchase")),
            new AbstractMap.SimpleEntry<>("打开 Codex", (Runnable) () -> openApp("Codex")),
            new AbstractMap.SimpleEntry<>("打开 Claude", (Runnable) () -> openApp("Claude")))) {
            JButton button = new JButton(item.getKey());
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.addActionListener(e -> item.getValue().run());
            panel.add(button); panel.add(Box.createVerticalStrut(10));
        }
        panel.add(new JLabel("系统：" + Platform.OS_KIND + " · Java " + System.getProperty("java.version")));
        panel.add(new JLabel("配置目录：" + store.root()));
        return panel;
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
        String balance = String.valueOf(user.getOrDefault("balance", "—"));
        account.setText("已登录：" + emailValue + "    余额：" + balance);
        password.setText("");
        status("登录成功");
    }

    private JPanel vertical() {
        JPanel panel = new JPanel();
        panel.setBorder(new EmptyBorder(18, 10, 10, 10));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private JComponent row(String label, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(12, 6));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        JLabel text = new JLabel(label);
        text.setPreferredSize(new Dimension(90, 30));
        row.add(text, BorderLayout.WEST); row.add(field, BorderLayout.CENTER);
        return row;
    }

    private <T> void async(String running, Callable<T> task, java.util.function.Consumer<T> done) {
        status(running);
        new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception { return task.call(); }
            protected void done() { try { done.accept(get()); } catch (Exception e) { error(e.getCause() == null ? e : e.getCause()); } }
        }.execute();
    }

    private void browse(String url) { try { Platform.browse(url); } catch (Exception e) { error(e); } }
    private void openApp(String app) { try { Platform.openApplication(app); } catch (Exception e) { error(e); } }
    private void status(String value) { status.setText(value); }
    private void error(Throwable error) { status("错误：" + error.getMessage()); JOptionPane.showMessageDialog(this, error.getMessage(), "TokenPro", JOptionPane.ERROR_MESSAGE); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private record KeyItem(long id, String name, String status) { public String toString() { return name + "  [" + status + "]"; } }
}

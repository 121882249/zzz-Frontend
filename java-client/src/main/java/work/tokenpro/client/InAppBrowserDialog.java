package work.tokenpro.client;

import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebHistory;
import javafx.scene.web.WebView;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.URI;

final class InAppBrowserDialog extends JDialog {
    private final JFXPanel browser = new JFXPanel();
    private final JTextField address = new JTextField();
    private final JButton back = new JButton("返回");
    private final JButton forward = new JButton("前进");
    private final JButton refresh = new JButton("刷新");
    private final JLabel status = new JLabel("正在启动浏览器…");
    private WebEngine engine;

    InAppBrowserDialog(JFrame owner, String initialUrl) {
        super(owner, "TokenPro 内置浏览器", false);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(760, 540));
        setSize(Math.max(820, owner.getWidth() - 70), Math.max(580, owner.getHeight() - 70));
        setLocationRelativeTo(owner);
        setContentPane(content());
        initializeBrowser(normalize(initialUrl));
    }

    private JComponent content() {
        JPanel root = new JPanel(new BorderLayout()); root.setBackground(new Color(7, 10, 24));
        JPanel toolbar = new JPanel(new BorderLayout(8, 0)); toolbar.setBorder(new EmptyBorder(10, 12, 10, 12)); toolbar.setBackground(new Color(14, 19, 43));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); controls.setOpaque(false); controls.add(back); controls.add(forward); controls.add(refresh); toolbar.add(controls, BorderLayout.WEST);
        address.addActionListener(e -> navigate(address.getText())); toolbar.add(address, BorderLayout.CENTER);
        JButton close = new JButton("关闭"); close.addActionListener(e -> dispose()); toolbar.add(close, BorderLayout.EAST); root.add(toolbar, BorderLayout.NORTH);
        root.add(browser, BorderLayout.CENTER);
        status.setBorder(new EmptyBorder(7, 12, 8, 12)); status.setForeground(new Color(189, 198, 228)); root.add(status, BorderLayout.SOUTH);
        back.addActionListener(e -> history(-1)); forward.addActionListener(e -> history(1));
        refresh.addActionListener(e -> javafx.application.Platform.runLater(() -> { if (engine != null) engine.reload(); }));
        updateButtons(-1, 0); return root;
    }

    private void initializeBrowser(String initialUrl) {
        address.setText(initialUrl);
        javafx.application.Platform.runLater(() -> {
            WebView view = new WebView(); engine = view.getEngine(); engine.setJavaScriptEnabled(true);
            engine.locationProperty().addListener((observable, oldValue, value) -> SwingUtilities.invokeLater(() -> address.setText(value)));
            engine.getLoadWorker().stateProperty().addListener((observable, oldValue, value) -> SwingUtilities.invokeLater(() -> {
                if (value == Worker.State.RUNNING) status.setText("正在加载 " + host(engine.getLocation()) + "…");
                else if (value == Worker.State.SUCCEEDED) status.setText("已打开 " + host(engine.getLocation()));
                else if (value == Worker.State.FAILED) status.setText("加载失败：" + safe(engine.getLoadWorker().getException()));
                WebHistory history = engine.getHistory(); updateButtons(history.getCurrentIndex(), history.getEntries().size());
            }));
            browser.setScene(new Scene(view)); engine.load(initialUrl);
        });
    }

    private void navigate(String raw) {
        String value = normalize(raw); address.setText(value); status.setText("正在加载 " + host(value) + "…");
        javafx.application.Platform.runLater(() -> { if (engine != null) engine.load(value); });
    }

    private void history(int delta) {
        javafx.application.Platform.runLater(() -> {
            if (engine == null) return; WebHistory history = engine.getHistory(); int target = history.getCurrentIndex() + delta;
            if (target >= 0 && target < history.getEntries().size()) history.go(delta);
        });
    }

    private void updateButtons(int index, int size) { back.setEnabled(index > 0); forward.setEnabled(index >= 0 && index < size - 1); }
    private static String normalize(String raw) { String value = raw == null ? "" : raw.trim(); if (!value.matches("(?i)^https?://.*")) value = "https://" + value; try { return URI.create(value).toString(); } catch (Exception ignored) { return "https://tokenpro.work"; } }
    private static String host(String raw) { try { String host = URI.create(raw).getHost(); return host == null ? raw : host; } catch (Exception ignored) { return raw; } }
    private static String safe(Throwable error) { return error == null || error.getMessage() == null ? "未知错误" : error.getMessage(); }
}

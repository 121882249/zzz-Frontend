package work.tokenpro.client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

final class InAppBrowserDialog extends JDialog {
    private final JEditorPane browser = new JEditorPane();
    private final JTextField address = new JTextField();
    private final JButton back = new JButton("返回");
    private final JButton forward = new JButton("前进");
    private final JButton refresh = new JButton("刷新");
    private final JLabel status = new JLabel("就绪");
    private final List<URL> history = new ArrayList<>();
    private int historyIndex = -1;

    InAppBrowserDialog(JFrame owner, String initialUrl) {
        super(owner, "TokenPro 内置浏览器", false);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(760, 540));
        setSize(Math.max(820, owner.getWidth() - 70), Math.max(580, owner.getHeight() - 70));
        setLocationRelativeTo(owner);
        setContentPane(content());
        navigate(initialUrl, true);
    }

    private JComponent content() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(new Color(7, 10, 24));

        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setBorder(new EmptyBorder(10, 12, 10, 12));
        toolbar.setBackground(new Color(14, 19, 43));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        controls.setOpaque(false);
        controls.add(back); controls.add(forward); controls.add(refresh);
        toolbar.add(controls, BorderLayout.WEST);
        address.addActionListener(e -> navigate(address.getText(), true));
        toolbar.add(address, BorderLayout.CENTER);
        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        toolbar.add(close, BorderLayout.EAST);
        root.add(toolbar, BorderLayout.NORTH);

        browser.setEditable(false);
        browser.setBackground(Color.WHITE);
        browser.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null) {
                navigate(event.getURL().toString(), true);
            }
        });
        root.add(new JScrollPane(browser), BorderLayout.CENTER);

        status.setBorder(new EmptyBorder(7, 12, 8, 12));
        status.setForeground(new Color(189, 198, 228));
        root.add(status, BorderLayout.SOUTH);

        back.addActionListener(e -> move(-1));
        forward.addActionListener(e -> move(1));
        refresh.addActionListener(e -> {
            if (historyIndex >= 0) load(history.get(historyIndex));
        });
        updateButtons();
        return root;
    }

    private void navigate(String raw, boolean record) {
        try {
            String value = raw == null ? "" : raw.trim();
            if (!value.matches("(?i)^https?://.*")) value = "https://" + value;
            URL url = URI.create(value).toURL();
            if (record) {
                while (history.size() > historyIndex + 1) history.removeLast();
                history.add(url);
                historyIndex = history.size() - 1;
            }
            load(url);
        } catch (Exception error) {
            showError(error);
        }
    }

    private void move(int delta) {
        int target = historyIndex + delta;
        if (target < 0 || target >= history.size()) return;
        historyIndex = target;
        load(history.get(historyIndex));
    }

    private void load(URL url) {
        address.setText(url.toString());
        status.setText("正在加载 " + url.getHost() + "…");
        updateButtons();
        SwingUtilities.invokeLater(() -> {
            try {
                browser.setPage(url);
                status.setText("已打开 " + url.getHost());
            } catch (Exception error) {
                browser.setContentType("text/html");
                browser.setText("<html><body style='font-family:sans-serif;padding:28px'><h2>页面暂时无法显示</h2><p>" + escape(error.getMessage()) + "</p></body></html>");
                status.setText("加载失败");
            }
        });
    }

    private void updateButtons() {
        back.setEnabled(historyIndex > 0);
        forward.setEnabled(historyIndex >= 0 && historyIndex < history.size() - 1);
    }

    private void showError(Exception error) {
        JOptionPane.showMessageDialog(this, error.getMessage(), "TokenPro 内置浏览器", JOptionPane.ERROR_MESSAGE);
    }

    private static String escape(String value) {
        if (value == null) return "未知错误";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

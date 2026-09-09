package work.tokenpro.client;

import javax.swing.*;
import java.awt.*;

public final class Main {
    public static final String VERSION = "1.2.9";
    private Main() {}

    public static void main(String[] args) throws Exception {
        SecureStore store = new SecureStore();
        if ((args.length == 1 && "--claude-token".equals(args[0])) || System.getenv("CLAUDE_HELPER_CONTEXT") != null) {
            ClaudeBridgeManager.ensureRunning(store);
            System.out.print(ClaudeBridgeConfig.load(store).localToken());
            return;
        }
        if (args.length == 1 && "--claude-bridge".equals(args[0])) {
            try (ClaudeBridgeServer ignored = new ClaudeBridgeServer(store)) { Thread.currentThread().join(); }
            return;
        }
        if (args.length == 2 && "--route-token".equals(args[0])) {
            System.out.print(store.credential(args[1]));
            return;
        }
        if (args.length > 0 && "--self-test".equals(args[0])) {
            SelfTest.run();
            return;
        }
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("当前环境无法启动桌面界面");
        System.setProperty("apple.awt.application.name", "TokenPro");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                UIManager.put("Panel.background", new Color(4, 7, 22));
                UIManager.put("Label.foreground", new Color(242, 245, 255));
                UIManager.put("Button.foreground", new Color(225, 231, 252));
                UIManager.put("Button.disabledText", new Color(166, 174, 205));
                UIManager.put("TextField.background", new Color(7, 11, 29));
                UIManager.put("TextField.foreground", new Color(242, 245, 255));
                UIManager.put("PasswordField.background", new Color(7, 11, 29));
                UIManager.put("PasswordField.foreground", new Color(242, 245, 255));
                UIManager.put("List.background", new Color(8, 13, 31));
                UIManager.put("List.foreground", new Color(225, 231, 252));
                new TokenProFrame(store).setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, e.getMessage(), "TokenPro", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}

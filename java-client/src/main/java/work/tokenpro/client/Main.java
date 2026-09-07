package work.tokenpro.client;

import javax.swing.*;
import java.awt.*;

public final class Main {
    public static final String VERSION = "1.0.0";
    private Main() {}

    public static void main(String[] args) throws Exception {
        SecureStore store = new SecureStore();
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
                new TokenProFrame(store).setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, e.getMessage(), "TokenPro", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}

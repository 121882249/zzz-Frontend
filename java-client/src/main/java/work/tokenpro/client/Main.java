package work.tokenpro.client;

import javax.swing.*;
import java.awt.*;

public final class Main {
    public static final String VERSION = "1.2.64";
    private Main() {}

    public static void main(String[] args) throws Exception {
        SecureStore store = new SecureStore();
        if (args.length == 1 && ("--claude-cli-token".equals(args[0]) || "--claude-cli-bridge".equals(args[0]))) {
            SecureStore cliStore = store.cli("claude");
            if ("--claude-cli-token".equals(args[0])) {
                ClaudeBridgeManager.ensureRunning(cliStore);
                System.out.print(ClaudeBridgeConfig.load(cliStore).localToken());
            } else {
                try (ClaudeBridgeServer bridge = new ClaudeBridgeServer(cliStore)) { bridge.awaitClaudeExit(); }
            }
            return;
        }
        if (args.length == 1 && "--codex-cli-image-bridge".equals(args[0])) {
            try (CodexImageBridge bridge = new CodexImageBridge(store.cli("codex"))) { bridge.await(); }
            return;
        }
        if (args.length == 1 && "--codex-image-bridge".equals(args[0])) {
            try (CodexImageBridge bridge = new CodexImageBridge(store)) { bridge.await(); }
            return;
        }
        if (claudeTokenRequest(args, System.getenv().containsKey("CLAUDE_HELPER_CONTEXT"))) {
            ClaudeBridgeManager.ensureRunning(store);
            System.out.print(ClaudeBridgeConfig.load(store).localToken());
            return;
        }
        if (args.length == 1 && "--claude-bridge".equals(args[0])) {
            try (ClaudeBridgeServer bridge = new ClaudeBridgeServer(store)) { bridge.awaitClaudeExit(); }
            return;
        }
        if (args.length == 2 && "--route-token".equals(args[0])) {
            System.out.print(store.credential(args[1]));
            return;
        }
        if (args.length > 0 && ("--codex-cli".equals(args[0]) || "--claude-cli".equals(args[0]))) {
            String client = args[0].equals("--codex-cli") ? "codex" : "claude";
            System.exit(CliLauncher.run(store, client, java.util.Arrays.asList(args).subList(1, args.length)));
            return;
        }
        if(args.length == 1 && ("--prepare-codex-cli".equals(args[0]) || "--prepare-claude-cli".equals(args[0]))) {
            CliLauncher.prepare(store, args[0].equals("--prepare-codex-cli") ? "codex" : "claude");
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
                UIManager.put("OptionPane.background", new Color(4, 7, 22));
                UIManager.put("OptionPane.messageForeground", new Color(242, 245, 255));
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
                ErrorMessages.show(null, e);
            }
        });
    }

    static boolean claudeTokenRequest(String[] args, boolean helperContext) {
        return args.length == 1 && "--claude-token".equals(args[0]) || args.length == 0 && helperContext;
    }
}

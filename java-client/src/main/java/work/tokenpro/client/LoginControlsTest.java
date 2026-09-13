package work.tokenpro.client;

import javax.swing.*;

final class LoginControlsTest {
    static int run() throws Exception {
        java.util.concurrent.atomic.AtomicInteger loginChecks = new java.util.concurrent.atomic.AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            JButton fixed = TokenProFrame.connectionActionButton();
            java.awt.Dimension fixedSize = fixed.getPreferredSize();
            for (String name : new String[]{"Codex", "Claude"}) {
                JButton action = new JButton("旧下载按钮");
                JButton menu = new JButton("选择模型");
                JLabel state = new JLabel("当前：TokenPro·已选 99 款模型");
                TokenProFrame.applyCliActionState(action, menu, state, name, false, false, 99, false);
                check(action.getText().equals("连接") && !action.isEnabled() && !menu.isEnabled()
                    && state.getText().equals("正在检查安装状态…"), "initial render never shows old actions or saved model state");
                TokenProFrame.applyCliActionState(action, menu, state, name, true, false, 99, false);
                check(action.getText().equals("去下载") && action.isEnabled() && !menu.isEnabled(), "missing app uses the same single action");
                TokenProFrame.applyCliActionState(action, menu, state, name, true, true, 0, false);
                check(action.getText().equals("连接") && action.isEnabled() && menu.isEnabled(), "installed app can open official mode without TokenPro model selection");
                TokenProFrame.applyCliActionState(action, menu, state, name, true, true, 2, false);
                check(action.isEnabled() && state.getText().equals("当前：TokenPro·已选 2 款模型"), "resolved selection is applied together");
                TokenProFrame.applyCliActionState(action, menu, state, name, true, true, 2, true);
                check(action.getText().equals("连接中…") && !action.isEnabled() && !menu.isEnabled(), "connection guard remains active");
            }
            TokenProFrame.applyCliActionState(fixed, new JButton(), new JLabel(), "Claude", true, true, 2, true);
            check(fixedSize.equals(fixed.getPreferredSize()) && fixedSize.equals(fixed.getMinimumSize())
                && fixedSize.equals(fixed.getMaximumSize()), "connection label changes never resize or shift the action row");

            JTextField email = new JTextField("saved@example.com");
            JPasswordField password = new JPasswordField();
            CosmosLoginPanel login = new CosmosLoginPanel(email, password, event -> {}, event -> {}, event -> {}, event -> {});
            login.setRestoringSession();
            check(!email.isEnabled() && !password.isEnabled() && !login.registerButton().isEnabled()
                && !login.forgotPasswordButton().isEnabled() && !login.loginButton().isEnabled()
                && login.loginButton().getText().equals("正在登录"), "session restore is visibly busy and does not ask for a password");
            loginChecks.incrementAndGet();
            login.setLoading(false, "登录已失效，请重新输入密码");
            check(email.isEnabled() && password.isEnabled() && login.registerButton().isEnabled()
                && login.forgotPasswordButton().isEnabled() && login.loginButton().isEnabled()
                && login.loginButton().getText().equals("登录"), "failed restore returns the login form to an interactive state");
            loginChecks.incrementAndGet();
        });
        return 11 + loginChecks.get();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

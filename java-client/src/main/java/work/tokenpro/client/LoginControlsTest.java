package work.tokenpro.client;

import javax.swing.*;

final class LoginControlsTest {
    static int run() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String name : new String[]{"Codex", "Claude"}) {
                JButton action = new JButton("旧下载按钮");
                JButton menu = new JButton("选择模型");
                JLabel state = new JLabel("当前：TokenPro 99 个模型");
                TokenProFrame.applyCliActionState(action, menu, state, name, false, false, 99, false);
                check(action.getText().equals("连接 " + name + " 命令行") && !action.isEnabled() && !menu.isEnabled()
                    && state.getText().equals("正在检查安装状态…"), "initial render never shows old actions or saved model state");
                TokenProFrame.applyCliActionState(action, menu, state, name, true, false, 99, false);
                check(action.getText().equals("去官方下载") && action.isEnabled() && !menu.isEnabled(), "missing app uses the same single action");
                TokenProFrame.applyCliActionState(action, menu, state, name, true, true, 0, false);
                check(action.getText().equals("连接 " + name + " 命令行") && !action.isEnabled() && menu.isEnabled(), "installed app waits for model selection");
                TokenProFrame.applyCliActionState(action, menu, state, name, true, true, 2, false);
                check(action.isEnabled() && state.getText().equals("当前：TokenPro 2 个模型"), "resolved selection is applied together");
                TokenProFrame.applyCliActionState(action, menu, state, name, true, true, 2, true);
                check(action.getText().equals("连接中…") && !action.isEnabled() && !menu.isEnabled(), "connection guard remains active");
            }
        });
        return 10;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

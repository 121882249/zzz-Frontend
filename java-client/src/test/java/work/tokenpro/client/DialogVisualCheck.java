package work.tokenpro.client;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Renders real dialogs without connecting accounts, restarting apps, or changing settings. */
public final class DialogVisualCheck {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "build/dialog-qa" : args[0]);
        Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                JFrame owner = new JFrame("Dialog QA");
                try {
                    capture(owner, output, "update", "稍后重启", () -> {
                        String choice = TokenProDialogs.chooseWithSecondary(owner, "更新已准备好",
                            "新版本已准备好，请选择重启时间。", "稍后重启", "立即重启");
                        require("稍后重启".equals(choice), "defer action changed");
                    });
                    capture(owner, output, "update-now", "立即重启", () -> {
                        String choice = TokenProDialogs.chooseWithSecondary(owner, "更新已准备好",
                            "新版本已准备好，请选择重启时间。", "稍后重启", "立即重启");
                        require("立即重启".equals(choice), "restart action changed");
                    });
                    capture(owner, output, "repair", "CCSwitch", () -> TokenProDialogs.choose(owner, "修复历史对话",
                        "选择当前渠道，仅修复未归档的普通对话并重启 Codex，请先保存。", "CCSwitch", "OpenAI"));
                    capture(owner, output, "repair-result", "知道了", () -> TokenProDialogs.info(owner, "修复完成",
                        "当前对话修复成功 17 个，失败 71 个。"));
                    capture(owner, output, "official", "取消", () -> require(!TokenProDialogs.confirm(owner, "切换官方配置",
                        "将切回官方并重启 Codex，当前请求会终止，请先保存。", "切换并重启"), "cancel accepted"));
                    capture(owner, output, "switch", "切换并重启", () -> TokenProDialogs.confirm(owner, "切换 Claude 命令行",
                        "将切换至 TokenPro 并重启 Claude 命令行，当前请求会终止，请先保存。", "切换并重启"));
                    capture(owner, output, "switch-idle", "切换渠道", () -> TokenProDialogs.confirm(owner, "切换 Codex 客户端",
                        "将当前渠道切换至 TokenPro，保留聊天内容。", "切换渠道"));
                    capture(owner, output, "manual-start", "知道了", () -> TokenProDialogs.info(owner, "请手动启动",
                        "配置已完成，请手动启动 Claude 命令行。"));
                    capture(owner, output, "connected", "知道了", () -> TokenProDialogs.info(owner, "连接完成",
                        "TokenPro 配置已启用，客户端已重启。"));
                    capture(owner, output, "warning", "知道了", () -> TokenProDialogs.warning(owner, "请选择模型",
                        "请至少选择 1 款模型。"));
                    capture(owner, output, "error", "确定", () -> TokenProDialogs.error(owner, "操作未完成",
                        "与服务器的安全连接被中断；若网页能打开，请启用系统代理或配置 TokenPro HTTP 代理后重启"));
                    capture(owner, output, "error-long", "确定", () -> TokenProDialogs.error(owner, "操作未完成",
                        "很长的错误说明".repeat(20)));
                    for (String client : List.of("Codex", "Claude")) {
                        ModelPickerDialog picker = new ModelPickerDialog(owner, client, false,
                            List.of(new PricedModel("fixture-model", client.equals("Codex") ? "openai" : "anthropic", "测试分组", 1)),
                            java.util.Set.of(), ignored -> {});
                        try {
                            picker.setModal(false); picker.setVisible(true); picker.validate();
                            for (Component component : descendants(picker)) if (component instanceof JButton b) {
                                Rectangle text = new Rectangle(), icon = new Rectangle();
                                Insets insets = b.getInsets();
                                String rendered = SwingUtilities.layoutCompoundLabel(b, b.getFontMetrics(b.getFont()), b.getText(), b.getIcon(),
                                    b.getVerticalAlignment(), b.getHorizontalAlignment(), b.getVerticalTextPosition(), b.getHorizontalTextPosition(),
                                    new Rectangle(insets.left, insets.top, b.getWidth() - insets.left - insets.right,
                                        b.getHeight() - insets.top - insets.bottom), icon, text, b.getIconTextGap());
                                require(b.getText().equals(rendered), client + " picker button clipped: " + rendered);
                            }
                            BufferedImage image = new BufferedImage(picker.getWidth(), picker.getHeight(), BufferedImage.TYPE_INT_ARGB);
                            Graphics2D graphics = image.createGraphics(); picker.printAll(graphics); graphics.dispose();
                            ImageIO.write(image, "png", output.resolve("picker-" + client + ".png").toFile());
                        } finally { picker.dispose(); }
                    }
                } finally { owner.dispose(); }
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        System.out.println("PASS: 12 dialog scenarios + 2 model pickers, full button labels, update colors, action results; previews: " + output.toAbsolutePath());
    }

    private static void capture(JFrame owner, Path output, String name, String action, Runnable show) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Timer timer = new Timer(120, event -> {
            JDialog dialog = null;
            try {
                dialog = (JDialog) java.util.Arrays.stream(owner.getOwnedWindows())
                    .filter(Window::isShowing).findFirst().orElseThrow();
                require(dialog.getOwner() == owner, "dialog detached from owner");
                List<Component> components = descendants(dialog);
                for (Component component : components) {
                    if (component instanceof JButton button) {
                        int textWidth = button.getFontMetrics(button.getFont()).stringWidth(button.getText());
                        require(textWidth <= button.getWidth() - button.getInsets().left - button.getInsets().right,
                            name + ": button clipped: " + button.getText());
                    }
                    if (component instanceof JTextArea copy) {
                        var end = copy.modelToView2D(copy.getText().length());
                        require(end.getMaxY() <= copy.getHeight(), name + ": body vertically clipped");
                        require(!copy.getText().contains("\n"), name + ": multiline copy");
                    }
                    if (component instanceof JLabel label) require(label.getFontMetrics(label.getFont()).stringWidth(label.getText())
                        <= label.getWidth(), name + ": heading clipped");
                }
                for (int scale : List.of(1, 2)) {
                    BufferedImage image = new BufferedImage(dialog.getWidth() * scale, dialog.getHeight() * scale, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = image.createGraphics();
                    graphics.scale(scale, scale); dialog.printAll(graphics); graphics.dispose();
                    ImageIO.write(image, "png", output.resolve(name + "-" + scale + "x.png").toFile());
                }
                if (name.startsWith("update")) {
                    JButton later = button(components, "稍后重启"), now = button(components, "立即重启");
                    require(background(now).getBlue() - background(later).getBlue() > 80, "defer button must be dark");
                }
                button(components, action).doClick();
            } catch (Throwable error) { failure.set(error); }
            finally { if (dialog != null) dialog.dispose(); }
        });
        timer.setRepeats(false); timer.start();
        try { show.run(); }
        catch (Throwable error) { if (failure.get() == null) failure.set(error); }
        finally { timer.stop(); }
        if (failure.get() != null) throw new AssertionError(name, failure.get());
    }

    private static Color background(JButton button) {
        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); button.paint(graphics); graphics.dispose();
        return new Color(image.getRGB(10, button.getHeight() / 2));
    }
    private static JButton button(List<Component> components, String text) {
        return components.stream().filter(c -> c instanceof JButton b && text.equals(b.getText()))
            .map(c -> (JButton)c).findFirst().orElseThrow();
    }
    private static List<Component> descendants(Container parent) {
        List<Component> all = new ArrayList<>();
        for (Component c : parent.getComponents()) { all.add(c); if (c instanceof Container nested) all.addAll(descendants(nested)); }
        return all;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

package work.tokenpro.client;

import java.io.IOException;
import java.nio.file.*;
import java.net.Proxy;
import java.util.*;

final class ChannelSwitchTest {
    static int run() throws Exception {
        Path root = Files.createTempDirectory("tokenpro-switch-test-");
        SecureStore store = new SecureStore(root.resolve("settings"));
        Path config = root.resolve("profile/config.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "model_provider=\"openai\"\n");
        Path message = root.resolve("profile/messages.jsonl");
        Files.writeString(message, "old message\n");
        ChannelSettingsBackup backup = new ChannelSettingsBackup(store, config);
        Files.writeString(config, "changed\n");
        Files.writeString(message, "new message\n", StandardOpenOption.APPEND);
        store.write("codex-selected.json", "new setting");
        backup.restore();
        require(Files.readString(config).contains("openai"), "settings restored");
        require(Files.readString(message).equals("old message\nnew message\n"), "new messages survive restoration");
        require(store.read("codex-selected.json").isEmpty(), "new settings removed on rollback");
        try (var paths = Files.walk(backup.location())) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                require(!Files.readString(path).contains("old message"), "backup excludes conversation content");
        }
        List<String> steps = new ArrayList<>();
        try {
            CodexChannelSwitch.run(store, config, "custom", List.of("model"), () -> steps.add("stop"), () -> {
                steps.add("write"); Files.writeString(config, "partial"); throw new Exception("injected write failure");
            }, () -> steps.add("start"), () -> steps.add("restartPrevious"));
            throw new AssertionError("failure was hidden");
        } catch (Exception expected) {
            require(steps.equals(List.of("stop", "write")), "failed write never restores or starts a different channel");
            require(Files.readString(config).equals("partial"), "no implicit rollback after write failure");
        }
        steps.clear();
        CodexChannelSwitch.run(store, config, "custom", List.of("model"), () -> steps.add("stop"),
            () -> { steps.add("write"); Files.writeString(config, "complete"); }, () -> steps.add("start"), () -> steps.add("repair"));
        require(steps.equals(List.of("stop", "write", "start", "repair")), "restart precedes background repair");
        steps.clear();
        try {
            CodexChannelSwitch.run(store, config, "custom", List.of("model"), () -> steps.add("stop"),
                () -> { steps.add("write"); Files.writeString(config, "new channel"); },
                () -> { steps.add("start"); throw new IOException("launcher unavailable"); }, () -> steps.add("repair"));
            throw new AssertionError("startup failure was hidden");
        } catch (ManualStartRequiredException expected) {
            require(expected.getMessage().contains("不会回滚"), "manual startup guidance is explicit");
        }
        require(steps.equals(List.of("stop", "write", "start", "repair")), "startup failure still schedules repair without rollback");
        require(Files.readString(config).equals("new channel"), "completed channel settings survive startup failure");
        steps.clear();
        try {
            ChannelSettingsBackup.switchClaude(store, true, () -> steps.add("stop"),
                () -> { steps.add("write"); store.write(ClaudeBridgeConfig.FILE, "new channel"); },
                () -> { steps.add("start"); throw new IOException("terminal unavailable"); }, () -> steps.add("rollback"));
            throw new AssertionError("Claude startup failure was hidden");
        } catch (ManualStartRequiredException expected) {
            require(expected.getMessage().contains("不会回滚"), "Claude manual startup guidance is explicit");
        }
        require(steps.equals(List.of("stop", "write", "start")), "Claude startup failure does not run rollback");
        require(store.read(ClaudeBridgeConfig.FILE).orElse("").equals("new channel"), "Claude channel survives startup failure");
        List<CodexHistorySettings.Setting> settings = List.of(new CodexHistorySettings.Setting("synthetic", "openai", "gpt"));
        require(CodexHistorySettings.decode(CodexHistorySettings.encode(settings)).equals(settings), "association snapshot roundtrip");
        ChannelSwitchCompletedWarningException warning = new ChannelSwitchCompletedWarningException(2, new IOException("stale thread"));
        require(warning.getMessage().contains("渠道配置已生效") && warning.getMessage().contains("不会回滚") && warning.getMessage().contains("2 个旧对话"), "partial history migration is a completed switch warning");
        require(OfficialConnectionCheck.proxy(Map.of("HTTPS_PROXY", "http://127.0.0.1:9999")).type() == Proxy.Type.HTTP, "HTTP proxy recognized");
        require(OfficialConnectionCheck.proxy(Map.of("ALL_PROXY", "socks5://127.0.0.1:9999")).type() == Proxy.Type.SOCKS, "SOCKS proxy recognized");
        try { OfficialConnectionCheck.proxy(Map.of("HTTPS_PROXY", "invalid")); throw new AssertionError("bad proxy accepted"); }
        catch (java.io.IOException expected) {}
        require(ClientReconnect.managedCliMatches("codex", "/bin/codex", List.of("-c", "tokenpro_profile=/scope/catalog.json"), "/scope/catalog.json"), "official CLI profile remains identifiable");
        return 20;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

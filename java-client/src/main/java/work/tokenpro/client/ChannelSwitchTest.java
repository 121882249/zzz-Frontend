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
        String originalConfig = "approval_policy=\"on-request\"\n# openai official baseline\n";
        Files.writeString(config, originalConfig);
        String officialAuth = "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"refresh_token\":\"fixture-refresh\"}}";
        Files.writeString(config.resolveSibling("auth.json"), officialAuth);
        require(CodexChannelState.detect(config).channel().equals("official"), "official state is detected from config and auth together");
        Path message = root.resolve("profile/messages.jsonl");
        Files.writeString(message, "old message\n");
        ChannelSettingsBackup backup = new ChannelSettingsBackup(store, config);
        Files.writeString(config, "changed\n");
        Files.writeString(message, "new message\n", StandardOpenOption.APPEND);
        store.write("codex-selected.json", "new setting");
        backup.restore();
        require(Files.readString(config).equals(originalConfig), "settings restored");
        require(Files.readString(message).equals("old message\nnew message\n"), "new messages survive restoration");
        require(store.read("codex-selected.json").isEmpty(), "new settings removed on rollback");
        try (var paths = Files.walk(backup.location())) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                require(!Files.readString(path).contains("old message"), "backup excludes conversation content");
        }
        List<String> steps = new ArrayList<>();
        try {
            CodexChannelSwitch.run(store, config, CodexChannel.tokenPro(), List.of("model"), () -> steps.add("stop"), () -> {
                steps.add("write"); Files.writeString(config, "partial"); throw new Exception("injected write failure");
            }, () -> steps.add("start"));
            throw new AssertionError("failure was hidden");
        } catch (Exception expected) {
            require(steps.equals(List.of("stop", "write", "stop", "start")), "failed write restores and restarts the previous channel");
            require(Files.readString(config).equals(originalConfig), "partial configuration is rolled back");
            require(Files.readString(config.resolveSibling("auth.json")).equals(officialAuth), "official auth survives rollback");
        }
        steps.clear();
        CodexChannelSwitch.run(store, config, CodexChannel.tokenPro(), List.of("model"), () -> steps.add("stop"),
            () -> { steps.add("write"); writeOpenAi(config, "model"); }, () -> steps.add("start"));
        require(steps.equals(List.of("stop", "write", "start")), "switch stops after the verified restart without history maintenance");
        String activeConfig = Files.readString(config);
        require(CodexChannelState.detect(config).channel().equals("named-provider:custom:tokenpro.work"),
            "TokenPro provider state is detected from its endpoint and API-key auth");
        steps.clear();
        int[] overwritingStarts = {0};
        try {
            CodexChannelSwitch.run(store, config, CodexChannel.tokenPro(), List.of("model"), () -> steps.add("stop"),
                () -> { steps.add("write"); writeOpenAi(config, "replacement-model"); }, () -> {
                    steps.add("start");
                    if (overwritingStarts[0]++ == 0) Files.writeString(config, "model_provider='openai'\n");
                });
            throw new AssertionError("post-start overwrite was hidden");
        } catch (IOException expected) {
            require(expected.getMessage().contains("已恢复原配置"), "post-start overwrite reports a completed rollback");
            require(Files.readString(config).equals(activeConfig), "a route overwritten during startup is detected and rolled back");
            require(Files.readString(config.resolveSibling("models_cache.json")).contains("\"slug\":\"model\""),
                "rollback restores the model cache together with config and auth");
        }
        steps.clear();
        int[] starts = {0};
        try {
            CodexChannelSwitch.run(store, config, CodexChannel.tokenPro(), List.of("model"), () -> steps.add("stop"),
                () -> { steps.add("write"); writeOpenAi(config, "replacement-model"); },
                () -> { steps.add("start"); if (starts[0]++ == 0) throw new IOException("launcher unavailable"); });
            throw new AssertionError("startup failure was hidden");
        } catch (IOException expected) {
            require(expected.getMessage().contains("已恢复原配置"), "startup failure reports completed rollback");
        }
        require(steps.equals(List.of("stop", "write", "start", "stop", "start")), "startup failure rolls back before restarting the prior channel");
        require(Files.readString(config).equals(activeConfig), "startup failure restores the previous complete channel");
        steps.clear();
        try {
            CodexChannelSwitch.run(store, config, CodexChannel.tokenPro(), List.of("model"),
                () -> { steps.add("stop"); throw new IOException("busy"); }, () -> steps.add("write"),
                () -> steps.add("start"));
            throw new AssertionError("stop failure was hidden");
        } catch (IOException expected) {
            require(expected.getMessage().contains("配置未修改") && steps.equals(List.of("stop")),
                "a stop failure never enters the mutation or rollback phases");
        }
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
        require(OfficialConnectionCheck.proxy(Map.of("HTTPS_PROXY", "http://127.0.0.1:9999")).type() == Proxy.Type.HTTP, "HTTP proxy recognized");
        require(OfficialConnectionCheck.proxy(Map.of("ALL_PROXY", "socks5://127.0.0.1:9999")).type() == Proxy.Type.SOCKS, "SOCKS proxy recognized");
        try { OfficialConnectionCheck.proxy(Map.of("HTTPS_PROXY", "invalid")); throw new AssertionError("bad proxy accepted"); }
        catch (java.io.IOException expected) {}
        require(ClientReconnect.managedCliMatches("codex", "/bin/codex", List.of("-c", "tokenpro_profile=/scope/catalog.json"), "/scope/catalog.json"), "official CLI profile remains identifiable");
        return 22;
    }
    private static void writeOpenAi(Path config, String model) throws Exception {
        Files.writeString(config, "# >>> tokenpro-codex\nmodel=\"" + model + "\"\nmodel_provider=\"custom\"\n"
            + "# <<< tokenpro-codex\n# >>> TokenPro managed >>>\n[model_providers.custom]\n"
            + "base_url=\"https://tokenpro.work/v1\"\n# <<< TokenPro managed <<<\n");
        Files.writeString(config.resolveSibling("auth.json"), "{\"auth_mode\":\"apikey\",\"OPENAI_API_KEY\":\"fixture-key\"}");
        Files.writeString(config.resolveSibling("models_cache.json"), "{\"models\":[{\"slug\":\"" + model + "\"}]}");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

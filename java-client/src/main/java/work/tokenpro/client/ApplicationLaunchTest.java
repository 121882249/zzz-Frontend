package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

/** No live apps are opened or closed by these regression tests. */
final class ApplicationLaunchTest {
    static int run() throws Exception {
        int passed = 0;
        String claudeId = "Claude_pzs8sxrjxfjjc!Claude";
        check(Platform.windowsApplicationIdMatches("Claude", claudeId), "registered Claude publisher is accepted"); passed++;
        check(Platform.windowsApplicationIdMatches("Claude", "Claude_otherpublisher!Claude"), "publisher is discovered, not hardcoded"); passed++;
        check(!Platform.windowsApplicationIdMatches("Claude", "ClaudeCode_pzs8sxrjxfjjc!Claude"), "Claude CLI is not desktop"); passed++;
        check(!Platform.windowsApplicationIdMatches("Codex", claudeId), "Claude cannot launch as Codex"); passed++;
        check(!Platform.windowsApplicationIdMatches("Claude", claudeId + " & exit"), "invalid ID cannot reach launcher"); passed++;
        List<String> command = Platform.windowsPackagedLaunchCommand("Claude", claudeId, "C:/Windows");
        check(command.size() == 2 && command.get(0).endsWith("explorer.exe")
            && command.get(1).equals("shell:AppsFolder\\" + claudeId), "registered desktop activation only"); passed++;
        check(Platform.desktopProcessMatches(Platform.OS.WINDOWS, "Claude",
            "C:/Program Files/WindowsApps/Claude_1.0_x64__publisher/app/Claude.exe"), "MSIX desktop process"); passed++;
        check(!Platform.desktopProcessMatches(Platform.OS.WINDOWS, "Claude",
            "C:/Users/Test/AppData/Local/Microsoft/WinGet/Packages/Anthropic.ClaudeCode_source/claude.exe"), "WinGet CLI process excluded"); passed++;
        check(!Platform.desktopProcessMatches(Platform.OS.WINDOWS, "Codex",
            "C:/Users/Test/AppData/Local/OpenAI/Codex/bin/hash/codex.exe"), "bundled Codex CLI process excluded"); passed++;
        check(Platform.desktopProcessMatches(Platform.OS.MAC, "Claude",
            "/Applications/Claude.app/Contents/MacOS/Claude"), "macOS desktop process"); passed++;
        check(!Platform.desktopProcessMatches(Platform.OS.MAC, "Claude", "/Users/test/.local/bin/claude"),
            "macOS CLI does not keep desktop bridge alive"); passed++;
        check(Platform.desktopProcessMatches(Platform.OS.LINUX, "Claude", "/opt/claude/claude"),
            "Linux desktop process"); passed++;
        check(!Platform.desktopProcessMatches(Platform.OS.LINUX, "Claude", "/usr/local/bin/claude"),
            "Linux CLI is not desktop"); passed++;
        Path root = Files.createTempDirectory("tokenpro-desktop-launch-");
        try {
            Map<String, String> env = new HashMap<>();
            for (String key : List.of("LOCALAPPDATA", "APPDATA", "ProgramFiles", "ProgramFiles(x86)", "ProgramData", "PUBLIC"))
                env.put(key, root.resolve(key.replaceAll("[^a-zA-Z]", "")).toString());
            env.put("PATH", "");
            String home = root.resolve("home").toString();
            Path cli = Path.of(env.get("LOCALAPPDATA"), "Microsoft", "WinGet", "Packages",
                "Anthropic.ClaudeCode_test", "claude.exe");
            Files.createDirectories(cli.getParent()); Files.writeString(cli, "fixture");
            check(Platform.commandExecutable(Platform.OS.WINDOWS, home, env, "claude").orElseThrow().equals(cli), "CLI resolver finds CLI"); passed++;
            check(Platform.desktopApplicationPath(Platform.OS.WINDOWS, home, env, "Claude").isEmpty(),
                "CLI-only install never marks desktop as installed"); passed++;
            Path desktop = Path.of(env.get("LOCALAPPDATA"), "AnthropicClaude", "app-1.0", "Claude.exe");
            Files.createDirectories(desktop.getParent()); Files.writeString(desktop, "fixture");
            check(Platform.desktopApplicationPath(Platform.OS.WINDOWS, home, env, "Claude").orElseThrow().equals(desktop),
                "versioned desktop detection and launch use the same path"); passed++;
            check(Platform.commandExecutable(Platform.OS.WINDOWS, home, env, "claude").orElseThrow().equals(cli),
                "desktop installation does not replace CLI path"); passed++;
            Path helper = desktop.getParent().resolve("resources/claude.exe");
            Files.createDirectories(helper.getParent()); Files.writeString(helper, "fixture");
            Files.delete(desktop);
            check(Platform.desktopApplicationPath(Platform.OS.WINDOWS, home, env, "Claude").isEmpty(),
                "bundled helper alone does not mark desktop installed"); passed++;
            SecureStore store = new SecureStore(root.resolve("store"));
            ApiClient.ManagedKey key = new ApiClient.ManagedKey(1, "unused-test");
            PricedModel model = new PricedModel("claude-test", "anthropic", "Claude", 1);
            int desktopPort = ClaudeBridgeConfig.create("1", "unused", key, List.of(model)).port();
            int cliPort = ClaudeBridgeConfig.createCli("1", "unused", key, List.of(model)).port();
            check(Set.of(desktopPort, cliPort, java.net.URI.create(CodexImageBridge.baseUrl(store)).getPort(),
                java.net.URI.create(CodexImageBridge.baseUrl(store.cli("codex"))).getPort()).size() == 4,
                "all four connection ports are distinct"); passed++;
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        return passed;
    }
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}

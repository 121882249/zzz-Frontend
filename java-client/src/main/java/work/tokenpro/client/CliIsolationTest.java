package work.tokenpro.client;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

final class CliIsolationTest {
    private CliIsolationTest() {}
    static int run() throws Exception {
        int passed = 0;
        Path root = Files.createTempDirectory("tokenpro-cli-isolation-");
        try {
            SecureStore desktop = new SecureStore(root);
            SecureStore claude = desktop.cli("claude"), codex = desktop.cli("codex");
            desktop.write("codex-selected.json", "desktop");
            codex.write("codex-selected.json", "cli");
            check(desktop.read("codex-selected.json").orElseThrow().equals("desktop"), "CLI selection preserves desktop selection"); passed++;
            check(claude.isClaudeCli() && !desktop.isClaudeCli() && !codex.isClaudeCli(), "helper scope is explicit"); passed++;
            PricedModel desktopModel = new PricedModel("claude-desktop", "anthropic", "Claude", 1);
            PricedModel cliModel = new PricedModel("gpt-cli", "openai", "GPT", 2);
            ClaudeBridgeConfig desktopConfig = new ClaudeBridgeConfig("account", freePort(), "desktop-token", "test-access", 1,
                "test-key", List.of(ClaudeBridgeConfig.Route.from(desktopModel)));
            ClaudeBridgeConfig cliConfig = new ClaudeBridgeConfig("account", freePort(), "cli-token", "test-access", 1,
                "test-key", List.of(ClaudeBridgeConfig.Route.from(cliModel)));
            desktopConfig.save(desktop);
            String before = desktop.read(ClaudeBridgeConfig.FILE).orElseThrow();
            cliConfig.save(claude);
            check(desktop.read(ClaudeBridgeConfig.FILE).orElseThrow().equals(before), "CLI bridge write preserves desktop bytes"); passed++;
            try (ClaudeBridgeServer desktopServer = new ClaudeBridgeServer(desktop);
                 ClaudeBridgeServer cliServer = new ClaudeBridgeServer(claude)) {
                check(get(cliConfig, "desktop-token", "/health").statusCode() == 401, "CLI rejects desktop credential"); passed++;
                check(get(desktopConfig, "cli-token", "/health").statusCode() == 401, "desktop rejects CLI credential"); passed++;
                check(get(cliConfig, "cli-token", "/v1/models").body().contains(cliConfig.routes().getFirst().alias()), "CLI exports only its selected route"); passed++;
                check(!get(desktopConfig, "desktop-token", "/v1/models").body().contains(cliConfig.routes().getFirst().alias()), "desktop model list does not gain CLI models"); passed++;
                cliServer.close();
                check(get(desktopConfig, "desktop-token", "/health").statusCode() == 200, "stopping CLI bridge leaves desktop running"); passed++;
            }
            // Restore paths are tested without depending on an installed CLI or contacting an API.
            Path desktopPath = root.resolve("desktop-home/config.toml");
            Path cliPath = codex.root().resolve("home/config.toml");
            Files.createDirectories(desktopPath.getParent()); Files.createDirectories(cliPath.getParent());
            Files.writeString(desktopPath, "desktop-config"); Files.writeString(cliPath, "cli-config");
            desktop.write("codex-original.toml", "desktop-original"); codex.write("codex-original.toml", "cli-original");
            new CodexConfig(codex, cliPath).restore();
            check(Files.readString(desktopPath).equals("desktop-config"), "restoring CLI preserves desktop config"); passed++;
            check(Files.readString(cliPath).equals("cli-original"), "CLI restores its own backup"); passed++;
            new CodexConfig(desktop, desktopPath).restore();
            check(Files.readString(cliPath).equals("cli-original"), "restoring desktop preserves CLI config"); passed++;
            check(Files.readString(desktopPath).equals("desktop-original"), "desktop restores its own backup"); passed++;
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        return passed;
    }
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    private static HttpResponse<String> get(ClaudeBridgeConfig config, String token, String path) throws Exception {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            return http.send(HttpRequest.newBuilder(URI.create(config.baseUrl() + path)).timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

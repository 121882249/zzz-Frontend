package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

final class ConnectionRegressionTest {
    static int run() throws Exception {
        int passed = 0;
        java.util.concurrent.atomic.AtomicLong linkClock = new java.util.concurrent.atomic.AtomicLong(1000);
        BrowserOpenGate links = new BrowserOpenGate(linkClock::get);
        for (String url : List.of("https://tokenpro.work/admin/dashboard", "https://tokenpro.work/docs", "https://tokenpro.work/purchase")) {
            check(BrowserOpenGate.protects(url), "protected web entry " + url); passed++;
        }
        check(!BrowserOpenGate.protects("https://tokenpro.work/downloads/latest/release-v2.json") && !BrowserOpenGate.protects("https://other.example/docs"), "updates and unrelated links do not share web cooldown"); passed++;
        check(links.begin(), "first web click is accepted"); passed++;
        for (int i=0;i<100;i++) check(!links.begin(), "rapid or cross-entry clicks never queue a second browser launch"); passed++;
        linkClock.addAndGet(30000);
        check(!links.begin(), "slow background ticket request remains locked after fifteen seconds"); passed++;
        links.opened();
        check(links.secondsRemaining()==15 && !links.begin(), "cooldown starts after the browser open succeeds"); passed++;
        linkClock.addAndGet(14999);
        check(links.secondsRemaining()==1 && !links.begin(), "web entry cannot reopen before exactly fifteen seconds"); passed++;
        linkClock.incrementAndGet();
        check(links.secondsRemaining()==0 && links.begin(), "web entry re-enables at fifteen seconds"); passed++;
        links.failed();
        check(links.begin(), "failed open can be retried immediately"); passed++;
        links.failed();
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1000);
        ConnectionGate gate = new ConnectionGate(now::get);
        for (String client : List.of("codex-desktop", "claude-desktop", "codex-cli", "claude-cli")) {
            check(gate.begin(client), client + " gets an independent connection lock"); passed++;
            for (int i = 0; i < 100; i++) check(!gate.begin(client), "rapid clicks never queue another launch");
            passed++;
            gate.finish(client);
            check(!gate.begin(client), "startup cooldown prevents double launch after callback"); passed++;
            now.addAndGet(ConnectionGate.COOLDOWN_MS + 1);
            check(gate.begin(client), "connection recovers after success, cancellation, or failure"); passed++;
            gate.finish(client);
        }
        List<String> parsed = ClientReconnect.windowsArguments("\"C:\\Native Tools\\codex.exe\" -c \"model_catalog_json=\\\"C:\\\\CLI Home\\\\catalog.json\\\"\"");
        check(parsed.size() == 3 && parsed.get(2).equals("model_catalog_json=\"C:\\\\CLI Home\\\\catalog.json\""), "Windows CLI arguments with spaces and nested TOML quotes"); passed++;
        List<String> events = new ArrayList<>();
        ClientReconnect.reconnect(() -> events.add("prepare"), () -> events.add("stop target"), () -> events.add("start target"));
        check(events.equals(List.of("prepare", "stop target", "start target")), "connect prepares before restarting only the target"); passed++;
        events.clear();
        try { ClientReconnect.reconnect(() -> {throw new java.io.IOException("bad config");}, () -> events.add("stop"), () -> events.add("start")); }
        catch (java.io.IOException expected) { }
        check(events.isEmpty(), "failed preflight does not close any client"); passed++;
        try { ClientReconnect.reconnect(() -> {}, () -> {throw new java.io.IOException("still saving");}, () -> events.add("start")); }
        catch (java.io.IOException expected) { }
        check(events.isEmpty(), "failed shutdown does not create a competing app instance"); passed++;
        for (Platform.OS os : Platform.OS.values()) {
            String codex = switch(os) {
                case WINDOWS -> "C:/Program Files/WindowsApps/OpenAI.Codex_1/app/ChatGPT.exe";
                case MAC -> "/Applications/Codex.app/Contents/MacOS/Codex";
                case LINUX -> "/opt/codex/codex";
            };
            String claude = switch(os) {
                case WINDOWS -> "C:/Program Files/WindowsApps/Claude_1/app/Claude.exe";
                case MAC -> "/Applications/Claude.app/Contents/MacOS/Claude";
                case LINUX -> "/opt/claude/claude";
            };
            check(Platform.desktopProcessMatches(os, "Codex", codex) && !Platform.desktopProcessMatches(os, "Claude", codex), os + " Codex isolation"); passed++;
            check(Platform.desktopProcessMatches(os, "Claude", claude) && !Platform.desktopProcessMatches(os, "Codex", claude), os + " Claude isolation"); passed++;
        }
        String provider = CodexConfig.providerConfiguration("custom", "http://127.0.0.1:23180/v1", "fixture", "test", null);
        check(provider.contains("requires_openai_auth = false") && provider.contains("/v1\""), "API-key route stays on authenticated adapter"); passed++;
        try { CodexConfig.providerConfiguration("openai", "http://127.0.0.1:23180/v1", "fixture", "test", null); throw new AssertionError("reserved provider overwritten"); }
        catch (IllegalArgumentException expected) { passed++; }
        for (Object content : List.of(List.of(), List.of(Map.of("type", "text", "text", "  ")),
            List.of(Map.of("type", "thinking", "thinking", "not a final answer")))) {
            try { ClaudeAdapter.requireResponseContent(Map.of("content",content)); throw new AssertionError("empty buffered response accepted"); }
            catch (IllegalArgumentException expected) { passed++; }
        }
        ClaudeAdapter.requireResponseContent(Map.of("content",List.of(Map.of("type","text","text","OK")))); passed++;
        ClaudeAdapter.requireResponseContent(Map.of("content",List.of(Map.of("type","tool_use","id","fixture")))); passed++;
        ClaudeAdapter.requireResponseContent(Map.of("content",List.of(Map.of("type","server_tool_use","id","fixture")))); passed++;
        ClaudeAdapter.NativeStream nativeStream = new ClaudeAdapter.NativeStream("local-alias", "signature-id");
        Map<String,Object> start = nativeStream.consume(ClaudeAdapter.map("type", "message_start", "message", Map.of("model", "gemini-3.8-flash")));
        check("local-alias".equals(Json.object(start.get("message")).get("model")), "Gemini reply retains client-facing selected model ID"); passed++;
        nativeStream.consume(ClaudeAdapter.map("type", "content_block_delta", "delta", Map.of("type", "text_delta", "text", "OK")));
        nativeStream.consume(Map.of("type", "message_stop"));
        check(nativeStream.completed(), "native streaming finishes after visible content"); passed++;
        try { new ClaudeAdapter.NativeStream("alias", "signature").consume(Map.of("type", "message_stop")); throw new AssertionError("empty native response accepted"); }
        catch (IllegalArgumentException expected) { passed++; }
        try { new ClaudeAdapter.ResponsesStream("alias").consume(ClaudeAdapter.map("type", "response.completed", "response", Map.of("status", "completed", "output", List.of()))); throw new AssertionError("empty Responses accepted"); }
        catch (IllegalArgumentException expected) { passed++; }
        Path root = Files.createTempDirectory("tokenpro-connection-regression-");
        try {
            SecureStore store = new SecureStore(root.resolve("store"));
            Map<String,String> env = Map.of("LOCALAPPDATA", root.resolve("Local").toString(), "APPDATA", root.resolve("Roaming").toString());
            List<Path> libraries = ClaudeDesktopConfig.windowsLibraries(env, root.toString(), "Claude_testpublisher");
            check(libraries.getFirst().equals(root.resolve("Local/Packages/Claude_testpublisher/LocalCache/Local/Claude-3p/configLibrary")), "MSIX's actual LocalCache/Local profile is updated first"); passed++;
            check(libraries.size() == 2, "Store and non-Store profiles remain in sync"); passed++;
            check(ClaudeDesktopConfig.windowsLibraries(env, root.toString(), "../other").size() == 1, "untrusted package path rejected"); passed++;
            List<PricedModel> models = List.of(new PricedModel("claude-sonnet-5", "anthropic", "Claude", 1),
                new PricedModel("gemini-3.8-flash-high", "gemini", "Gemini", 2));
            ClaudeBridgeConfig config = ClaudeBridgeConfig.create("18", "fixture-access", new ApiClient.ManagedKey(1,"fixture-key"), models);
            for (Path library : libraries) {
                ClaudeDesktopConfig.installAt(store, config, "fixture-account", "fixture-helper", library);
                ClaudeDesktopConfig.verifyLibrary(library, config);
                Map<String,Object> meta = Json.object(Json.parse(Files.readString(library.resolve("_meta.json"))));
                Path profilePath = library.resolve(meta.get("appliedId") + ".json");
                Map<String,Object> profile = Json.object(Json.parse(Files.readString(profilePath)));
                check(ClaudeAdapter.list(profile.get("inferenceModels")).size() == models.size(), "saved picker count matches bridge in every install path"); passed++;
                profile.put("inferenceModels", List.of()); Files.writeString(profilePath, Json.stringify(profile));
                try { ClaudeDesktopConfig.verifyLibrary(library, config); throw new AssertionError("stale picker accepted"); }
                catch (IllegalStateException expected) { passed++; }
            }
            for (String client : List.of("codex", "claude")) {
                String marker = ClientReconnect.cliMarker(store, client);
                List<String> args = CliLauncher.arguments(store, client, List.of());
                check(ClientReconnect.managedCliMatches(client, "/native/" + client, args, marker), "managed " + client + " is identifiable"); passed++;
                check(!ClientReconnect.managedCliMatches(client, "/native/" + client, List.of(), marker), "ordinary " + client + " is never closed"); passed++;
                check(!ClientReconnect.managedCliMatches(client, "/native/" + client, args, marker + "-other"), "other " + client + " profile is never closed"); passed++;
            }
            store.write(CodexImageBridge.FILE, Json.stringify(Map.of("revision", "new", "token", "fixture", "key", "unused")));
            check(!ConnectionEvidence.verified(store), "saved configuration is not connection evidence"); passed++;
            ConnectionEvidence.received(store, "old", "gpt-test", 200, "fixture-request");
            check(!ConnectionEvidence.verified(store), "previous account/config request is not evidence for this config"); passed++;
            ConnectionEvidence.received(store, "new", "gpt-test", 401, "fixture-request");
            check(!ConnectionEvidence.verified(store), "authentication error is not a verified connection"); passed++;
            ConnectionEvidence.received(store, "new", "gpt-test", 200, "fixture-request");
            check(ConnectionEvidence.verified(store), "actual current successful upstream request verifies route, not cost"); passed++;
            String image = "gpt-image-2.5-flare";
            store.write("codex-selected.json", Json.stringify(Map.of("models", List.of(Map.of("name", image, "group_id", 65, "platform", "openai", "group_name", "images")))));
            check(TokenProFrame.savedCodexModels(store).getFirst().isImageGeneration(), "old saved image selection is migrated on Connect"); passed++;
            int port;
            try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            SecureStore fixture = new SecureStore(root.resolve("bridge-fixture"));
            var gemini = ClaudeBridgeConfig.Route.from(models.get(1));
            new ClaudeBridgeConfig("18", port, "local-fixture", "expired-ui-session", 1, "upstream-fixture", List.of(gemini)).save(fixture);
            var upstream = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 4);
            var requests = new java.util.concurrent.atomic.AtomicInteger();
            upstream.createContext("/v1/messages", exchange -> {
                requests.incrementAndGet();
                if (!"Bearer upstream-fixture".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1); exchange.close(); return;
                }
                exchange.getRequestBody().readAllBytes();
                byte[] body = Json.stringify(ClaudeAdapter.map("id", "msg_fixture", "type", "message", "role", "assistant", "model", gemini.name(),
                    "content", List.of(Map.of("type", "text", "text", "OK")), "stop_reason", "end_turn", "usage", Map.of("input_tokens", 1, "output_tokens", 1)))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            upstream.start();
            try (ClaudeBridgeServer bridge = new ClaudeBridgeServer(fixture, "http://127.0.0.1:" + upstream.getAddress().getPort());
                 var http = java.net.http.HttpClient.newHttpClient()) {
                String body = Json.stringify(Map.of("model", gemini.alias(), "max_tokens", 32, "messages", List.of(Map.of("role", "user", "content", "fixture"))));
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/v1/messages"))
                    .header("Authorization", "Bearer local-fixture").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
                var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                check(response.statusCode() == 200 && response.body().contains("OK"), "expired UI login does not break an upstream-authorized API-key request"); passed++;
                check(gemini.alias().equals(Json.object(Json.parse(response.body())).get("model")), "buffered Gemini reply uses the selected Claude model ID"); passed++;
                check(requests.get() == 1, "one client request sends exactly one upstream request"); passed++;
                var stale = java.net.http.HttpRequest.newBuilder(request.uri()).header("Authorization", "Bearer local-fixture")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.replace(gemini.alias(), "old-removed-model"))).build();
                check(http.send(stale, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode() == 404 && requests.get() == 1,
                    "stale Claude picker ID is rejected without an unintended model request"); passed++;
            } finally { upstream.stop(0); }
        } finally { try (var paths = Files.walk(root)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
        return passed;
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}

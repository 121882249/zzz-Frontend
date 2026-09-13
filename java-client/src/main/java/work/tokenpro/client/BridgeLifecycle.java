package work.tokenpro.client;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

final class BridgeLifecycle {
    interface Action { void run() throws Exception; }
    record Bridge(String name, Action stop, Action resume) {}
    private BridgeLifecycle() {}

    static List<Bridge> running(SecureStore root) throws Exception {
        List<Bridge> bridges = new ArrayList<>();
        for(SecureStore store : List.of(root, root.cli("claude"))) if(ClaudeBridgeManager.healthy(store))
            bridges.add(new Bridge(store.isClaudeCli() ? "Claude CLI" : "Claude", () -> ClaudeBridgeManager.stop(store), () -> ClaudeBridgeManager.ensureRunning(store)));
        return bridges;
    }

    static void update(List<Bridge> bridges, Action install) throws Exception {
        try {
            for(Bridge bridge : bridges) bridge.stop().run();
            install.run();
        } catch(Exception failure) {
            for(Bridge bridge : bridges) try { bridge.resume().run(); }
            catch(Exception recovery) { failure.addSuppressed(new IllegalStateException(bridge.name() + " 桥接恢复失败", recovery)); }
            throw failure;
        }
    }

    static void resumeConfigured(SecureStore root) throws Exception {
        removeLegacyCodexAdapter(root);
        removeLegacyCodexAdapter(root.cli("codex"));
        List<Bridge> configured = new ArrayList<>();
        for(SecureStore store : List.of(root, root.cli("claude"))) if(store.read(ClaudeBridgeConfig.FILE).isPresent())
            configured.add(new Bridge("Claude", () -> {}, () -> ClaudeBridgeManager.ensureRunning(store)));
        Exception failure = null;
        for(Bridge bridge : configured) try { bridge.resume().run(); }
        catch(Exception e) { if(failure == null) failure = e; else failure.addSuppressed(e); }
        if(failure != null) throw failure;
    }

    /** Stops and forgets the removed Codex loopback adapter after an upgrade. */
    static void removeLegacyCodexAdapter(SecureStore store) {
        String file = "codex-image-bridge.json";
        try {
            Map<String,Object> legacy = Json.object(Json.parse(store.read(file).orElse("{}")));
            String token = Objects.toString(legacy.get("token"), "").trim();
            if (!token.isEmpty()) {
                int port = store.isCodexCli() ? 23182 : 23180;
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/shutdown"))
                    .timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            }
        } catch (Exception ignored) {
            // Cleanup must never prevent the direct client from starting.
        }
        try { store.delete(file); } catch (Exception ignored) {}
        try { store.delete("codex-connection-state.json"); } catch (Exception ignored) {}
    }
}

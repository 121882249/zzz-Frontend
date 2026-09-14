package work.tokenpro.client;

import java.net.*;
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
        String flag = store.isCodexCli() ? "--codex-cli-image-bridge" : "--codex-image-bridge";
        try {
            Map<String,Object> legacy = Json.object(Json.parse(store.read(file).orElse("{}")));
            String token = Objects.toString(legacy.get("token"), "").trim();
            if (!token.isEmpty()) {
                int port = store.isCodexCli() ? 23182 : 23180;
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/shutdown"))
                    .timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
                NetworkProxy.localBuilder().connectTimeout(Duration.ofSeconds(1)).build()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            }
        } catch (Exception ignored) {
            // Fall through to terminating the obsolete helper process itself.
        }
        try {
            stopLegacyCodexAdapterProcesses(flag);
            finishLegacyCodexCleanup(store, legacyCodexAdapterRunning(flag));
        } catch (RuntimeException denied) {
            try { store.write("codex-cleanup-warning.txt", "无法检查旧 Codex 图片辅助进程；请关闭旧版 TokenPro 后重试清理"); }
            catch (Exception ignored) {}
        }
    }

    static void finishLegacyCodexCleanup(SecureStore store, boolean stillRunning) {
        List<String> failures = new ArrayList<>();
        if (stillRunning) {
            failures.add("旧 Codex 图片辅助进程尚未退出");
        } else {
            // A different program occupying the retired port is not the old helper.
            for (String name : List.of("codex-image-bridge.json", "codex-connection-state.json")) {
                try { store.delete(name); } catch (Exception failure) { failures.add("无法删除 " + name); }
            }
        }
        try {
            if (failures.isEmpty()) store.delete("codex-cleanup-warning.txt");
            else store.write("codex-cleanup-warning.txt", String.join("；", failures));
        } catch (Exception ignored) {}
    }

    private static void stopLegacyCodexAdapterProcesses(String flag) {
        for (ProcessHandle process : legacyCodexAdapterProcesses(flag)) {
            process.destroy();
            try { process.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception ignored) {
                if (process.isAlive()) process.destroyForcibly();
                try { process.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception stillAlive) {}
            }
        }
    }

    private static boolean legacyCodexAdapterRunning(String flag) {
        return !legacyCodexAdapterProcesses(flag).isEmpty();
    }

    private static List<ProcessHandle> legacyCodexAdapterProcesses(String flag) {
        return ProcessHandle.allProcesses().filter(process -> process.pid() != ProcessHandle.current().pid())
            .filter(process -> legacyCodexAdapterProcess(process.info().arguments().orElse(new String[0]), flag))
            .toList();
    }

    static boolean legacyCodexAdapterProcess(String[] arguments, String flag) {
        return Arrays.asList(arguments).contains(flag);
    }

    private static boolean loopbackPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
            return true;
        } catch (Exception closed) { return false; }
    }
}

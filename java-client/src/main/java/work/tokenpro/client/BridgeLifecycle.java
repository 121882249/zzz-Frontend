package work.tokenpro.client;

import java.util.*;

final class BridgeLifecycle {
    interface Action { void run() throws Exception; }
    record Bridge(String name, Action stop, Action resume) {}
    private BridgeLifecycle() {}

    static List<Bridge> running(SecureStore root) throws Exception {
        List<Bridge> bridges = new ArrayList<>();
        for(SecureStore store : List.of(root, root.cli("codex"))) if(CodexImageBridge.healthy(store))
            bridges.add(new Bridge(store.isCodexCli() ? "Codex CLI" : "Codex", () -> CodexImageBridge.stop(store), () -> CodexImageBridge.ensureRunning(store)));
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
        List<Bridge> configured = new ArrayList<>();
        for(SecureStore store : List.of(root, root.cli("codex"))) if(store.read(CodexImageBridge.FILE).isPresent())
            configured.add(new Bridge("Codex", () -> {}, () -> CodexImageBridge.ensureRunning(store)));
        for(SecureStore store : List.of(root, root.cli("claude"))) if(store.read(ClaudeBridgeConfig.FILE).isPresent())
            configured.add(new Bridge("Claude", () -> {}, () -> ClaudeBridgeManager.ensureRunning(store)));
        Exception failure = null;
        for(Bridge bridge : configured) try { bridge.resume().run(); }
        catch(Exception e) { if(failure == null) failure = e; else failure.addSuppressed(e); }
        if(failure != null) throw failure;
    }
}

package work.tokenpro.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Explicit user-triggered migration of all Codex threads to the selected active provider. */
final class CodexConversationRepair {
    private static final List<String> SOURCE_KINDS = List.of(
        "cli", "vscode", "exec", "appServer", "subAgent", "subAgentReview", "subAgentCompact",
        "subAgentThreadSpawn", "subAgentOther", "unknown");

    interface Rpc extends AutoCloseable {
        Map<String,Object> call(String method, Map<String,Object> params) throws Exception;
        void close() throws Exception;
    }
    interface Factory { Rpc open() throws Exception; }

    record Result(int discovered, int alreadyTarget, int attempted, int repaired, int failed,
                  List<String> failedThreadIds, String provider, String model) {
        String summary() {
            return "共发现 " + discovered + " 个历史对话；已是 " + provider + " " + alreadyTarget
                + " 个，修复成功 " + repaired + " 个，失败 " + failed + " 个。";
        }
    }

    private CodexConversationRepair() {}

    static Result repair(Path home, String targetProvider, String requestedModel) throws Exception {
        return repair(() -> {
            CodexAppServerRpc delegate = new CodexAppServerRpc(home);
            return new Rpc() {
                public Map<String,Object> call(String method, Map<String,Object> params) throws Exception {
                    return delegate.call(method, params);
                }
                public void close() throws Exception { delegate.close(); }
            };
        }, targetProvider, requestedModel);
    }

    static Result repair(Factory factory, String targetProvider, String requestedModel) throws Exception {
        targetProvider = Objects.requireNonNullElse(targetProvider, "").trim();
        if (targetProvider.isBlank()) throw new IllegalArgumentException("目标 provider 不能为空");
        LinkedHashMap<String,String> providers = listThreads(factory);
        String model = resolveModel(factory, requestedModel);
        if (model.isBlank()) throw new IOException("无法确认当前渠道模型，未修改历史对话");

        int alreadyTarget = 0;
        List<String> targets = new ArrayList<>();
        for (Map.Entry<String,String> entry : providers.entrySet()) {
            if (targetProvider.equals(entry.getValue())) alreadyTarget++;
            else targets.add(entry.getKey());
        }

        List<String> candidates = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Rpc rpc = null;
        try {
            for (String id : targets) {
                if (rpc == null) rpc = factory.open();
                try {
                Map<String,Object> resumed = rpc.call("thread/resume", Map.of(
                    "threadId", id, "modelProvider", targetProvider, "model", model, "excludeTurns", true));
                if (!targetProvider.equals(Objects.toString(resumed.get("modelProvider"), "")))
                    throw new IOException("Codex 未接受目标 provider");
                rpc.call("thread/settings/update", Map.of("threadId", id, "model", model));
                unsubscribe(rpc, id);
                candidates.add(id);
                } catch (Exception failure) {
                    failures.add(id);
                    closeQuietly(rpc);
                    rpc = null;
                }
            }
        } finally {
            closeQuietly(rpc);
        }

        int repaired = 0;
        rpc = null;
        try {
            for (String id : candidates) {
                if (rpc == null) rpc = factory.open();
                try {
                Map<String,Object> actual = rpc.call("thread/resume", Map.of("threadId", id, "excludeTurns", true));
                if (!targetProvider.equals(Objects.toString(actual.get("modelProvider"), ""))
                    || !model.equals(Objects.toString(actual.get("model"), "")))
                    throw new IOException("修复结果未持久保存");
                unsubscribe(rpc, id);
                repaired++;
                } catch (Exception failure) {
                    failures.add(id);
                    closeQuietly(rpc);
                    rpc = null;
                }
            }
        } finally {
            closeQuietly(rpc);
        }
        return new Result(providers.size(), alreadyTarget, targets.size(), repaired, failures.size(),
            List.copyOf(new LinkedHashSet<>(failures)), targetProvider, model);
    }

    private static LinkedHashMap<String,String> listThreads(Factory factory) throws Exception {
        LinkedHashMap<String,String> result = new LinkedHashMap<>();
        for (boolean archived : List.of(false, true)) {
            try (Rpc rpc = factory.open()) {
                String cursor = null;
                Set<String> seenCursors = new HashSet<>();
                do {
                    Map<String,Object> params = new LinkedHashMap<>();
                    params.put("limit", 100);
                    params.put("archived", archived);
                    params.put("modelProviders", List.of());
                    params.put("sourceKinds", SOURCE_KINDS);
                    if (cursor != null) params.put("cursor", cursor);
                    Map<String,Object> page = rpc.call("thread/list", params);
                    for (Object raw : ClaudeAdapter.list(page.get("data"))) {
                        Map<String,Object> row = Json.object(raw);
                        String id = Objects.toString(row.get("id"), "").trim();
                        if (!id.isBlank()) result.putIfAbsent(id, Objects.toString(row.get("modelProvider"), ""));
                    }
                    cursor = page.get("nextCursor") instanceof String value && !value.isBlank() ? value : null;
                    if (cursor != null && !seenCursors.add(cursor))
                        throw new IOException("Codex 返回了重复的历史分页游标，未开始修复");
                } while (cursor != null);
            }
        }
        return result;
    }

    private static String resolveModel(Factory factory, String requested) throws Exception {
        String model = requested == null ? "" : requested.trim();
        if (!model.isBlank()) return model;
        try (Rpc rpc = factory.open()) {
            Map<String,Object> config = Json.object(rpc.call("config/read", Map.of()).get("config"));
            model = Objects.toString(config.get("model"), "").trim();
            if (!model.isBlank()) return model;
            return ClaudeAdapter.list(rpc.call("model/list", Map.of()).get("data")).stream()
                .map(Json::object)
                .filter(row -> Boolean.TRUE.equals(row.get("isDefault")))
                .map(row -> Objects.toString(row.get("model"), Objects.toString(row.get("slug"), "")).trim())
                .filter(value -> !value.isBlank()).findFirst().orElse("");
        }
    }

    private static void unsubscribe(Rpc rpc, String id) {
        try { rpc.call("thread/unsubscribe", Map.of("threadId", id)); }
        catch (Exception ignored) { /* The settings write is already complete. */ }
    }

    private static void closeQuietly(Rpc rpc) {
        if (rpc == null) return;
        try { rpc.close(); }
        catch (Exception ignored) { /* A later verification decides whether the write persisted. */ }
    }
}

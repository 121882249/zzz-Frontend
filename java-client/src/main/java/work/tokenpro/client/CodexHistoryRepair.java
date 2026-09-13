package work.tokenpro.client;

import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Per-profile background settings repair; never sends a user turn or edits chat files. */
final class CodexHistoryRepair {
    interface Session extends AutoCloseable {
        Map<String,Object> call(String method, Map<String,Object> params) throws Exception;
        void close() throws Exception;
    }
    interface Factory { Session open() throws Exception; }
    record Result(int attempted, int repaired, int failed, List<String> issues) {
        String summary() { return "旧对话修复：成功 " + repaired + "，失败 " + failed
            + (issues.isEmpty() ? "" : "；部分检查未完成") + "。渠道配置已保留。"; }
    }
    private static final Map<Path,Job> jobs = new ConcurrentHashMap<>();
    private static Path identity(Path config) { return config.toAbsolutePath().normalize(); }

    static void cancel(Path config) {
        Job old = jobs.remove(identity(config));
        if (old != null) old.cancel();
    }

    static void schedule(SecureStore store, Path config, String provider, List<String> models, Consumer<String> report) {
        cancel(config);
        Job job = new Job(store, identity(config), provider, List.copyOf(models), report);
        jobs.put(job.config, job);
        job.worker = new Thread(job, "codex-history-repair");
        job.worker.setDaemon(true);
        job.worker.start();
    }

    private static final class Job implements Runnable {
        final SecureStore store;
        final Path config;
        final String provider;
        final List<String> models;
        final Consumer<String> report;
        volatile boolean cancelled;
        volatile Thread worker;
        CodexHistorySettings.Rpc active;
        Job(SecureStore store, Path config, String provider, List<String> models, Consumer<String> report) {
            this.store = store; this.config = config; this.provider = provider; this.models = models; this.report = report;
        }
        synchronized void register(CodexHistorySettings.Rpc rpc) throws IOException {
            if (cancelled) { rpc.abort(); throw new IOException("修复已被新的切换替代"); }
            active = rpc;
        }
        synchronized void cancel() {
            cancelled = true;
            if (worker != null) worker.interrupt();
            if (active != null) active.abort();
        }
        public void run() {
            Result result = repair(() -> new CodexHistorySettings.Rpc(config.getParent(), this::register),
                provider, models, () -> cancelled);
            synchronized (this) {
                if (cancelled || jobs.get(config) != this) return;
                try {
                    store.write("codex-history-repair.json", Json.stringify(Map.of("provider", provider,
                        "attempted", result.attempted(), "repaired", result.repaired(), "failed", result.failed(),
                        "issues", result.issues(), "finished_at", java.time.Instant.now().toString())));
                } catch (Exception ignored) { /* Reporting must never alter the active channel. */ }
                report.accept(result.summary());
                jobs.remove(config, this);
            }
        }
    }

    static Result repair(Factory factory, String provider, List<String> models, BooleanSupplier cancelled) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        List<String> issues = new ArrayList<>();
        // Explicitly include archived and non-interactive sources. An empty source filter
        // means interactive-only in Codex, not all sources.
        for (boolean archived : List.of(false, true)) {
            if (cancelled.getAsBoolean()) break;
            try (Session rpc = factory.open()) {
                String cursor = null;
                Set<String> seen = new HashSet<>();
                do {
                    if (cancelled.getAsBoolean()) break;
                    Map<String,Object> params = new LinkedHashMap<>(Map.of("limit", 100, "modelProviders", List.of(),
                        "archived", archived, "sourceKinds", List.of("cli", "vscode", "exec", "appServer",
                            "subAgent", "subAgentReview", "subAgentCompact", "subAgentThreadSpawn", "subAgentOther", "unknown")));
                    if (cursor != null) params.put("cursor", cursor);
                    Map<String,Object> page = rpc.call("thread/list", params);
                    for (Object row : ClaudeAdapter.list(page.get("data"))) {
                        String id = Objects.toString(Json.object(row).get("id"), "");
                        if (!id.isBlank()) ids.add(id);
                    }
                    cursor = (String) page.get("nextCursor");
                    if (cursor != null && !seen.add(cursor)) throw new IOException("重复分页游标");
                } while (cursor != null);
            } catch (Exception failure) { issues.add(archived ? "归档列表读取未完成" : "会话列表读取未完成"); }
        }
        String model = models.isEmpty() ? "" : models.getFirst();
        if (model.isBlank() && !ids.isEmpty() && !cancelled.getAsBoolean()) {
            try (Session rpc = factory.open()) {
                model = Objects.toString(Json.object(rpc.call("config/read", Map.of()).get("config")).get("model"), "");
                if (model.isBlank()) model = ClaudeAdapter.list(rpc.call("model/list", Map.of()).get("data")).stream()
                    .map(Json::object).filter(row -> Boolean.TRUE.equals(row.get("isDefault")))
                    .map(row -> Objects.toString(row.get("model"), "")).findFirst().orElse("");
            } catch (Exception failure) { issues.add("默认模型读取失败"); }
        }
        int attempted = 0, repaired = 0, failed = 0;
        for (String id : ids) {
            if (cancelled.getAsBoolean()) break;
            attempted++;
            try {
                if (model.isBlank()) throw new IOException("默认模型为空");
                try (Session rpc = factory.open()) {
                    rpc.call("thread/resume", Map.of("threadId", id, "modelProvider", provider, "model", model, "excludeTurns", true));
                    if (cancelled.getAsBoolean()) break;
                    rpc.call("thread/settings/update", Map.of("threadId", id, "model", model));
                    rpc.call("thread/unsubscribe", Map.of("threadId", id));
                }
                if (cancelled.getAsBoolean()) break;
                // A fresh app-server must observe persisted settings; an in-memory override is insufficient.
                try (Session rpc = factory.open()) {
                    Map<String,Object> actual = rpc.call("thread/resume", Map.of("threadId", id, "excludeTurns", true));
                    if (!provider.equals(actual.get("modelProvider")) || !model.equals(actual.get("model")))
                        throw new IOException("会话设置未持久保存");
                    rpc.call("thread/unsubscribe", Map.of("threadId", id));
                }
                repaired++;
            } catch (Exception failure) { failed++; }
        }
        return new Result(attempted, repaired, failed, List.copyOf(issues));
    }
}

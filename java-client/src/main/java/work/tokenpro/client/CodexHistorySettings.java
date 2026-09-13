package work.tokenpro.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Changes thread settings through Codex, never rewrites rollouts or its state database. */
final class CodexHistorySettings {
    record Setting(String id, String provider, String model) {
        Map<String,Object> json() { return Map.of("id", id, "provider", provider, "model", model); }
    }

    static List<Setting> capture(Path home) throws Exception {
        try (Rpc rpc = new Rpc(home)) {
            List<String> ids = new ArrayList<>();
            String cursor = null;
            Set<String> seen = new HashSet<>();
            do {
                Map<String,Object> params = new LinkedHashMap<>(Map.of("limit", 100, "modelProviders", List.of("openai", "custom")));
                if (cursor != null) params.put("cursor", cursor);
                Map<String,Object> page = rpc.call("thread/list", params);
                for (Object value : ClaudeAdapter.list(page.get("data"))) {
                    String id = Objects.toString(Json.object(value).get("id"), "");
                    if (!id.isBlank() && !ids.contains(id)) ids.add(id);
                }
                cursor = (String) page.get("nextCursor");
                if (cursor != null && !seen.add(cursor)) throw new IOException("历史对话分页异常，已停止切换");
            } while (cursor != null);
            List<Setting> result = new ArrayList<>();
            for (String id : ids) {
                Map<String,Object> current = rpc.call("thread/resume", Map.of("threadId", id, "excludeTurns", true));
                String provider = Objects.toString(current.get("modelProvider"), "");
                if (Set.of("openai", "custom").contains(provider))
                    result.add(new Setting(id, provider, Objects.toString(current.get("model"), "")));
                rpc.call("thread/unsubscribe", Map.of("threadId", id));
            }
            return result;
        }
    }

    static List<Setting> targets(Path home, List<Setting> before, String provider, List<String> models,
                                 List<Setting> saved) throws Exception {
        String fallback;
        if (!models.isEmpty()) fallback = models.getFirst();
        else try (Rpc rpc = new Rpc(home)) {
            Map<String,Object> config = Json.object(rpc.call("config/read", Map.of()).get("config"));
            fallback = Objects.toString(config.get("model"), "");
            if (fallback.isBlank()) {
                Map<String,Object> page = rpc.call("model/list", Map.of());
                fallback = ClaudeAdapter.list(page.get("data")).stream().map(Json::object)
                    .filter(row -> Boolean.TRUE.equals(row.get("isDefault")))
                    .map(row -> Objects.toString(row.get("model"), "")).findFirst().orElse("");
            }
            if (fallback.isBlank()) throw new IOException("未能确认官方默认模型，已停止切换");
        }
        List<Setting> result = new ArrayList<>();
        for (Setting old : before) {
            Setting preference = saved.stream().filter(s -> s.id.equals(old.id) && s.provider.equals(provider)).findFirst().orElse(old);
            String model = preference.provider.equals(provider) && !preference.model.isBlank()
                && (models.isEmpty() || models.contains(preference.model)) ? preference.model : fallback;
            result.add(new Setting(old.id, provider, model));
        }
        return result;
    }

    static void apply(Path home, List<Setting> settings) throws Exception {
        if (settings.isEmpty()) return;
        try (Rpc rpc = new Rpc(home)) {
            for (Setting setting : settings) {
                Map<String,Object> resumed = rpc.call("thread/resume", Map.of("threadId", setting.id,
                    "modelProvider", setting.provider, "model", setting.model, "excludeTurns", true));
                if (!setting.provider.equals(resumed.get("modelProvider"))) throw new IOException("旧对话服务商未切换，已停止操作");
                // Resume overrides alone are transient. This supported method flushes the
                // current provider/model snapshot without submitting any user turn.
                rpc.call("thread/settings/update", Map.of("threadId", setting.id, "model", setting.model));
                rpc.call("thread/unsubscribe", Map.of("threadId", setting.id));
            }
        }
        // Verify with a new server: an in-memory override is not a successful migration.
        try (Rpc rpc = new Rpc(home)) {
            for (Setting setting : settings) {
                Map<String,Object> actual = rpc.call("thread/resume", Map.of("threadId", setting.id, "excludeTurns", true));
                if (!setting.provider.equals(actual.get("modelProvider")) || !setting.model.equals(actual.get("model")))
                    throw new IOException("旧对话设置未持久保存，已停止切换");
                rpc.call("thread/unsubscribe", Map.of("threadId", setting.id));
            }
        }
    }

    static String encode(List<Setting> settings) { return Json.stringify(settings.stream().map(Setting::json).toList()); }
    static List<Setting> decode(String json) {
        return ClaudeAdapter.list(Json.parse(json)).stream().map(Json::object).map(r -> new Setting(
            Objects.toString(r.get("id")), Objects.toString(r.get("provider")), Objects.toString(r.get("model")))).toList();
    }

    static final class Rpc implements AutoCloseable {
        private final Process process;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final ExecutorService reads = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "codex-settings-rpc"); t.setDaemon(true); return t; });
        private int sequence;
        Rpc(Path home) throws Exception {
            Path executable = Platform.codexExecutable().orElseThrow(() -> new IOException("找不到 Codex，无法安全切换对话设置"));
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "app-server").redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("CODEX_HOME", home.toAbsolutePath().toString());
            builder.directory(home.toFile());
            process = builder.start();
            writer = process.outputWriter(StandardCharsets.UTF_8);
            reader = process.inputReader(StandardCharsets.UTF_8);
            try {
                call("initialize", Map.of("clientInfo", Map.of("name", "tokenpro_channel_settings", "version", "1"),
                    "capabilities", Map.of("experimentalApi", true)));
                send(Map.of("method", "initialized"));
            } catch (Exception failure) { close(); throw failure; }
        }
        private void send(Map<String,Object> message) throws IOException { writer.write(Json.stringify(message)); writer.newLine(); writer.flush(); }
        Map<String,Object> call(String method, Map<String,Object> params) throws Exception {
            int id = ++sequence;
            send(Map.of("id", id, "method", method, "params", params));
            Future<Map<String,Object>> response = reads.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) {
                    Map<String,Object> value = Json.object(Json.parse(line));
                    if (value.containsKey("method") && value.containsKey("id")) {
                        send(Map.of("id", value.get("id"), "error", Map.of("code", -32601, "message", "No tools or approvals during channel migration")));
                        continue;
                    }
                    if (value.get("id") instanceof Number number && number.intValue() == id) {
                        if (value.containsKey("error")) throw new IOException("Codex 不支持或未能完成设置操作（" + method + "），没有发送聊天请求");
                        return value.get("result") == null ? Map.of() : Json.object(value.get("result"));
                    }
                }
                throw new IOException("Codex 设置接口提前退出");
            });
            try { return response.get(30, TimeUnit.SECONDS); }
            catch (Exception e) { response.cancel(true); throw new IOException("对话设置操作失败（" + method + "）", e); }
        }
        public void close() throws IOException {
            try { writer.close(); } catch (IOException ignored) {}
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(3, TimeUnit.SECONDS);
                    }
                    throw new IOException("设置辅助进程未正常退出，已停止切换");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); process.destroy(); throw new IOException("设置操作被中断", e);
            } finally { reads.shutdownNow(); }
        }
    }
}

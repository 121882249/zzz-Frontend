package work.tokenpro.client;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    record Result(int discovered, int visibleDiscovered, int internalDiscovered, int archivedDiscovered,
                  int alreadyTarget, int attempted, int repaired, int visibleRepaired,
                  int internalRepaired, int failed, List<String> failedThreadIds,
                  Map<String,Integer> failureReasons, String provider, String model) {
        String summary() {
            return "修复 " + visibleRepaired + " 个，跳过归档 " + archivedDiscovered
                + " 个、内部任务 " + internalDiscovered + " 个，失败 " + failed + " 个。";
        }
    }

    private static final Pattern PROVIDER_FIELD = Pattern.compile("(\\\"model_provider\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")");
    private record ThreadRecord(String id, String provider, String model, String path,
                                boolean archived, boolean internal) {}
    private record Failure(String id, String reason) {}
    private record MetadataBackup(Path original, Path backup) {}
    private interface MetadataEditor { MetadataBackup update(ThreadRecord thread, String provider) throws Exception; }

    private CodexConversationRepair() {}

    static Result repair(Path home, String targetProvider, String requestedModel) throws Exception {
        Path backupRoot = home.resolve("backups_state/tokenpro-conversation-repair/"
            + System.currentTimeMillis() + "-" + UUID.randomUUID());
        return repair(() -> {
            CodexAppServerRpc delegate = new CodexAppServerRpc(home);
            return new Rpc() {
                public Map<String,Object> call(String method, Map<String,Object> params) throws Exception {
                    return delegate.call(method, params);
                }
                public void close() throws Exception { delegate.close(); }
            };
        }, metadataEditor(home, backupRoot), targetProvider, requestedModel);
    }

    static Result repair(Factory factory, String targetProvider, String requestedModel) throws Exception {
        return repair(factory, (thread, provider) -> null, targetProvider, requestedModel);
    }

    private static Result repair(Factory factory, MetadataEditor editor, String targetProvider,
                                 String requestedModel) throws Exception {
        targetProvider = Objects.requireNonNullElse(targetProvider, "").trim();
        if (targetProvider.isBlank()) throw new IllegalArgumentException("目标 provider 不能为空");
        if (!targetProvider.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("目标 provider 格式无效");
        LinkedHashMap<String,ThreadRecord> providers = listThreads(factory);
        String model = resolveModel(factory, requestedModel);
        if (model.isBlank()) throw new IOException("无法确认当前渠道模型，未修改历史对话");

        List<ThreadRecord> eligible = providers.values().stream()
            .filter(thread -> !thread.archived() && !thread.internal()).toList();
        int alreadyTarget = 0;
        List<ThreadRecord> targets = new ArrayList<>();
        for (ThreadRecord thread : eligible) {
            if (targetProvider.equals(thread.provider()) && model.equals(thread.model())) alreadyTarget++;
            else targets.add(thread);
        }

        List<ThreadRecord> candidates = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        Rpc rpc = null;
        try {
            for (ThreadRecord thread : targets) {
                boolean modelUpdated = false;
                MetadataBackup backup = null;
                try {
                    backup = editor.update(thread, targetProvider);
                    if (rpc == null) rpc = factory.open();
                    Map<String,Object> resumed = rpc.call("thread/resume", Map.of(
                        "threadId", thread.id(), "modelProvider", targetProvider, "model", model, "excludeTurns", true));
                    if (!targetProvider.equals(Objects.toString(resumed.get("modelProvider"), "")))
                        throw new IOException("Codex 未接受目标 provider");
                    rpc.call("thread/settings/update", Map.of("threadId", thread.id(), "model", model));
                    modelUpdated = true;
                    unsubscribe(rpc, thread.id());
                    candidates.add(thread);
                } catch (Exception failure) {
                    if (!modelUpdated && backup != null) {
                        try { restore(backup); }
                        catch (Exception restoreFailure) {
                            failure.addSuppressed(new IOException("恢复对话元数据失败", restoreFailure));
                        }
                    }
                    failures.add(new Failure(thread.id(), failureReason(failure)));
                    closeQuietly(rpc);
                    rpc = null;
                }
            }
        } finally {
            closeQuietly(rpc);
        }

        LinkedHashMap<String,ThreadRecord> verified = listThreads(factory);
        List<ThreadRecord> repaired = new ArrayList<>();
        for (ThreadRecord candidate : candidates) {
            ThreadRecord actual = verified.get(candidate.id());
            if (actual != null && targetProvider.equals(actual.provider()) && model.equals(actual.model())
                && actual.archived() == candidate.archived()) {
                repaired.add(candidate);
            } else {
                failures.add(new Failure(candidate.id(), "修复结果未持久保存"));
            }
        }
        LinkedHashMap<String,Integer> reasons = new LinkedHashMap<>();
        for (Failure failure : failures) reasons.merge(failure.reason(), 1, Integer::sum);
        int visibleDiscovered = eligible.size();
        int internalDiscovered = (int) providers.values().stream()
            .filter(thread -> !thread.archived() && thread.internal()).count();
        int archivedDiscovered = (int) providers.values().stream().filter(ThreadRecord::archived).count();
        int visibleRepaired = (int) repaired.stream().filter(thread -> !thread.internal()).count();
        int internalRepaired = repaired.size() - visibleRepaired;
        return new Result(providers.size(), visibleDiscovered, internalDiscovered, archivedDiscovered,
            alreadyTarget, targets.size(), repaired.size(), visibleRepaired, internalRepaired,
            failures.size(), failures.stream().map(Failure::id).distinct().toList(),
            Collections.unmodifiableMap(reasons), targetProvider, model);
    }

    private static LinkedHashMap<String,ThreadRecord> listThreads(Factory factory) throws Exception {
        LinkedHashMap<String,ThreadRecord> result = new LinkedHashMap<>();
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
                        if (!id.isBlank()) result.putIfAbsent(id, new ThreadRecord(id,
                            Objects.toString(row.get("modelProvider"), ""),
                            Objects.toString(row.get("model"), ""), Objects.toString(row.get("path"), ""),
                            archived, internalThread(row)));
                    }
                    cursor = page.get("nextCursor") instanceof String value && !value.isBlank() ? value : null;
                    if (cursor != null && !seenCursors.add(cursor))
                        throw new IOException("Codex 返回了重复的历史分页游标，未开始修复");
                } while (cursor != null);
            }
        }
        return result;
    }

    private static MetadataEditor metadataEditor(Path home, Path backupRoot) throws IOException {
        Path realHome = home.toRealPath();
        return (thread, provider) -> {
            if (thread.path().isBlank()) throw new IOException("Codex 未返回对话文件路径");
            Path original = Path.of(thread.path()).toRealPath();
            Path sessions = realHome.resolve("sessions");
            Path archived = realHome.resolve("archived_sessions");
            if (!original.startsWith(sessions) && !original.startsWith(archived))
                throw new IOException("对话文件不在 Codex 历史目录中");
            Files.createDirectories(backupRoot);
            Path backup = backupRoot.resolve(thread.id() + ".jsonl");
            Files.copy(original, backup, StandardCopyOption.COPY_ATTRIBUTES);
            replaceProvider(original, provider);
            return new MetadataBackup(original, backup);
        };
    }

    private static void replaceProvider(Path file, String provider) throws Exception {
        Path temporary = Files.createTempFile(file.getParent(), ".tokenpro-provider-", ".tmp");
        try {
            Set<PosixFilePermission> permissions = null;
            try { permissions = Files.getPosixFilePermissions(file); } catch (UnsupportedOperationException ignored) {}
            try (InputStream input = Files.newInputStream(file); OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteArrayOutputStream firstLine = new ByteArrayOutputStream();
                int value;
                boolean newline = false;
                while ((value = input.read()) >= 0) {
                    if (value == '\n') { newline = true; break; }
                    firstLine.write(value);
                }
                byte[] bytes = firstLine.toByteArray();
                boolean carriageReturn = bytes.length > 0 && bytes[bytes.length - 1] == '\r';
                String line = new String(bytes, 0, carriageReturn ? bytes.length - 1 : bytes.length,
                    StandardCharsets.UTF_8);
                Map<String,Object> record = Json.object(Json.parse(line));
                if (!"session_meta".equals(Objects.toString(record.get("type"), "")))
                    throw new IOException("对话文件缺少 session_meta");
                Matcher matcher = PROVIDER_FIELD.matcher(line);
                if (!matcher.find()) throw new IOException("对话文件缺少 model_provider");
                String updated = matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + provider + matcher.group(3)));
                output.write(updated.getBytes(StandardCharsets.UTF_8));
                if (carriageReturn) output.write('\r');
                if (newline) output.write('\n');
                input.transferTo(output);
            }
            if (permissions != null) Files.setPosixFilePermissions(temporary, permissions);
            moveReplace(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restore(MetadataBackup backup) throws IOException {
        Path temporary = Files.createTempFile(backup.original().getParent(), ".tokenpro-restore-", ".tmp");
        try {
            Files.copy(backup.backup(), temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            moveReplace(temporary, backup.original());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean internalThread(Map<String,Object> row) {
        if (row.get("parentThreadId") instanceof String parent && !parent.isBlank()) return true;
        Object source = row.get("source");
        if (source instanceof Map<?,?> map && map.containsKey("subAgent")) return true;
        return Objects.toString(source, "").toLowerCase(Locale.ROOT).contains("subagent");
    }

    private static String failureReason(Exception failure) {
        String message = Objects.toString(failure.getMessage(), "").toLowerCase(Locale.ROOT);
        if (message.contains("is archived") || message.contains("unarchive")) return "归档状态处理失败";
        if (message.contains("provider") && message.contains("not found")) return "渠道配置不存在";
        if (message.contains("permission") || message.contains("denied") || message.contains("权限")) return "目录权限不足";
        if (message.contains("timeout") || message.contains("超时")) return "Codex 响应超时";
        String safe = ErrorMessages.safe(Objects.toString(failure.getMessage(), "未知错误"));
        return safe.isBlank() ? "未知错误" : safe;
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

package work.tokenpro.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/** Minimal isolated Codex app-server client used by integration probes only. */
final class CodexAppServerRpc implements AutoCloseable {
    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final ExecutorService reads = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "codex-probe-rpc");
        thread.setDaemon(true);
        return thread;
    });
    private int sequence;

    CodexAppServerRpc(Path home) throws Exception {
        Path executable = Platform.codexExecutable().orElseThrow(() -> new IOException("找不到 Codex，无法运行隔离探针"));
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "app-server")
            .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("CODEX_HOME", home.toAbsolutePath().toString());
        builder.directory(home.toFile());
        process = builder.start();
        writer = process.outputWriter(StandardCharsets.UTF_8);
        reader = process.inputReader(StandardCharsets.UTF_8);
        try {
            call("initialize", Map.of("clientInfo", Map.of("name", "tokenpro_route_probe", "version", "1"),
                "capabilities", Map.of("experimentalApi", true)));
            send(Map.of("method", "initialized"));
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    private void send(Map<String,Object> message) throws IOException {
        writer.write(Json.stringify(message));
        writer.newLine();
        writer.flush();
    }

    Map<String,Object> call(String method, Map<String,Object> params) throws Exception {
        int id = ++sequence;
        send(Map.of("id", id, "method", method, "params", params));
        Future<Map<String,Object>> response = reads.submit(() -> {
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String,Object> value = Json.object(Json.parse(line));
                if (value.containsKey("method") && value.containsKey("id")) {
                    send(Map.of("id", value.get("id"), "error", Map.of("code", -32601, "message", "Probe does not allow tools or approvals")));
                    continue;
                }
                if (value.get("id") instanceof Number number && number.intValue() == id) {
                    if (value.containsKey("error")) {
                        Map<String,Object> error = Json.object(value.get("error"));
                        String code = Objects.toString(error.get("code"), "");
                        String detail = ErrorMessages.safe(Objects.toString(error.get("message"), ""));
                        throw new IOException("Codex 探针调用失败（" + method + (code.isBlank() ? "" : "，代码 " + code)
                            + (detail.isBlank() ? "" : "：" + detail) + "）");
                    }
                    return value.get("result") == null ? Map.of() : Json.object(value.get("result"));
                }
            }
            throw new IOException("Codex 探针接口提前退出");
        });
        try {
            return response.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception failure) throw failure;
            throw new IOException("Codex 探针调用失败（" + method + "）", error.getCause());
        } catch (TimeoutException error) {
            response.cancel(true);
            abort();
            throw new IOException("Codex 探针调用超时（" + method + "）", error);
        }
    }

    private void abort() {
        process.destroyForcibly();
        reads.shutdownNow();
    }

    public void close() throws IOException {
        try { writer.close(); } catch (IOException ignored) {}
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Codex 探针被中断", error);
        } finally {
            reads.shutdownNow();
        }
    }
}

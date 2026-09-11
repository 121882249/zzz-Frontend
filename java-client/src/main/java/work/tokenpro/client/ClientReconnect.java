package work.tokenpro.client;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit Connect actions only. Model selection must never call this class. */
final class ClientReconnect {
    private ClientReconnect() {}
    interface Action { void run() throws Exception; }

    static void reconnect(Action prepare, Action stop, Action start) throws Exception {
        prepare.run(); // A missing/invalid configuration must not close anything.
        stop.run();
        start.run();
    }

    static List<ProcessHandle> desktopProcesses(String client) {
        if (!Set.of("Codex", "Claude").contains(client)) throw new IllegalArgumentException("未知客户端");
        List<ProcessHandle> matched = ProcessHandle.allProcesses().filter(p ->
            Platform.desktopProcessMatches(Platform.OS_KIND, client, p.info().command().orElse(""))).toList();
        Set<Long> ids = new HashSet<>(); matched.forEach(p -> ids.add(p.pid()));
        return matched.stream().filter(p -> p.parent().map(parent -> !ids.contains(parent.pid())).orElse(true)).toList();
    }

    static void stopDesktop(String client) throws Exception {
        for (ProcessHandle process : desktopProcesses(client)) stop(process, true);
    }

    static String cliMarker(SecureStore root, String client) throws Exception {
        return root.cli(client).root().resolve(client.equals("claude") ? ClaudeCliConfig.FILE : "codex-model-catalog.json")
            .toAbsolutePath().normalize().toString();
    }

    static boolean managedCliMatches(String client, String executable, List<String> arguments, String marker) {
        if (!Set.of("codex", "claude").contains(client)) return false;
        if (Platform.desktopProcessMatches(Platform.OS_KIND, client.equals("codex") ? "Codex" : "Claude", executable)) return false;
        String file;
        try { file = Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT); }
        catch (Exception ignored) { return false; }
        if (!(file.equals(client) || file.equals(client + ".exe") || file.equals("node") || file.equals("node.exe"))) return false;
        String expected = normalized(marker);
        if (client.equals("claude")) {
            for (int i = 0; i + 1 < arguments.size(); i++)
                if (arguments.get(i).equals("--settings") && normalized(arguments.get(i + 1)).equals(expected)) return true;
        } else {
            for (int i = 0; i + 1 < arguments.size(); i++)
                if (Set.of("-c", "--config").contains(arguments.get(i))) {
                    String arg = arguments.get(i + 1);
                    if (arg.startsWith("model_catalog_json=") && normalized(arg.substring(19)).equals(expected)) return true;
                }
        }
        return false;
    }

    private static String normalized(String value) {
        String result = value;
        if (result.startsWith("\"") && result.endsWith("\"")) {
            try { result = Objects.toString(Json.parse(result)); } catch (Exception ignored) { return ""; }
        }
        result = result.replace('\\', '/');
        return Platform.OS_KIND == Platform.OS.WINDOWS ? result.toLowerCase(Locale.ROOT) : result;
    }

    static List<ProcessHandle> cliProcesses(SecureStore root, String client) throws Exception {
        String marker = cliMarker(root, client);
        List<ProcessHandle> found = ProcessHandle.allProcesses().filter(p -> managedCliMatches(client, p.info().command().orElse(""),
            Arrays.asList(p.info().arguments().orElse(new String[0])), marker)).toList();
        if (!found.isEmpty() || Platform.OS_KIND != Platform.OS.WINDOWS) return found;
        // Windows ProcessHandle often omits arguments. Read the native process
        // command line without emitting it to logs, then match the exact profile.
        String powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
            "System32/WindowsPowerShell/v1.0/powershell.exe").toString();
        Process query = new ProcessBuilder(powershell, "-NoProfile", "-NonInteractive", "-Command",
            "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); Get-CimInstance Win32_Process | Where-Object {$_.Name -in @('codex.exe','claude.exe','node.exe')} | Select-Object ProcessId,ExecutablePath,CommandLine | ConvertTo-Json -Compress")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return query.getInputStream().readNBytes(4 * 1024 * 1024); }
            catch (IOException failure) { return new byte[0]; }
        });
        if (!query.waitFor(5, TimeUnit.SECONDS)) { query.destroyForcibly(); reader.cancel(true); return List.of(); }
        byte[] data = reader.get(1, TimeUnit.SECONDS);
        String json = new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (json.isBlank()) return List.of();
        Object parsed = Json.parse(json);
        List<?> rows = parsed instanceof List<?> list ? list : List.of(parsed);
        List<ProcessHandle> matches = new ArrayList<>();
        for (Object raw : rows) {
            Map<String,Object> row = Json.object(raw);
            if (managedCliMatches(client, Objects.toString(row.get("ExecutablePath"), ""),
                windowsArguments(Objects.toString(row.get("CommandLine"), "")), marker)
                && row.get("ProcessId") instanceof Number pid) ProcessHandle.of(pid.longValue()).ifPresent(matches::add);
        }
        return matches;
    }

    static List<String> windowsArguments(String line) {
        List<String> args = new ArrayList<>();
        for (int i = 0; i < line.length();) {
            while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
            if (i == line.length()) break;
            StringBuilder value = new StringBuilder(); boolean quoted = false;
            while (i < line.length() && (quoted || !Character.isWhitespace(line.charAt(i)))) {
                int slashes = 0;
                while (i < line.length() && line.charAt(i) == '\\') { slashes++; i++; }
                if (i < line.length() && line.charAt(i) == '"') {
                    value.append("\\".repeat(slashes / 2));
                    if (slashes % 2 == 1) value.append('"'); else quoted = !quoted;
                    i++;
                } else {
                    value.append("\\".repeat(slashes));
                    if (i < line.length()) value.append(line.charAt(i++));
                }
            }
            args.add(value.toString());
        }
        return args;
    }

    static void awaitStarted(SecureStore store, String app, boolean cli) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (cli ? !cliProcesses(store, app.toLowerCase(Locale.ROOT)).isEmpty() : !desktopProcesses(app).isEmpty()) return;
            Thread.sleep(400);
        }
        throw new IOException("尚未确认 " + app + " 启动完成，请检查新窗口；已忽略重复点击，请勿连续重试");
    }

    static void stopCli(SecureStore root, String client) throws Exception {
        for (ProcessHandle p : cliProcesses(root, client)) stop(p, false);
    }

    private static void stop(ProcessHandle process, boolean desktop) throws Exception {
        if (!process.isAlive()) return;
        var identity = process.info().startInstant();
        if (desktop && Platform.OS_KIND == Platform.OS.WINDOWS) {
            // Ask the exact GUI PID to close normally so its quit handlers flush
            // sessions. No process-name wildcard, /T, or CLI descendants.
            String powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
            new ProcessBuilder(powershell, "-NoProfile", "-NonInteractive", "-Command",
                "$p=Get-Process -Id " + process.pid() + " -ErrorAction SilentlyContinue; if($p){[void]$p.CloseMainWindow()}")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor(3, TimeUnit.SECONDS);
        } else process.destroy();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (process.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
        if (!process.isAlive()) return;
        // Never silently force-kill a client that might still be saving a chat.
        if (identity.equals(process.info().startInstant()))
            throw new IOException("目标程序尚未完成退出，请保存当前任务并退出该程序后再点连接；未关闭其他应用");
    }
}

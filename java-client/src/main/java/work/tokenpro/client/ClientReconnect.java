package work.tokenpro.client;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Restarts only the explicitly confirmed target after configuration preflight succeeds. */
final class ClientReconnect {
    static final int DESKTOP_START_TIMEOUT_SECONDS = 8;
    static final int CLI_START_TIMEOUT_SECONDS = 20;
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
        if (Platform.OS_KIND == Platform.OS.WINDOWS && client.equals("Codex")) {
            boolean nativeAvailable = matched.stream().anyMatch(p -> windowsCodexExecutable(p).equals("codex.exe"))
                || matched.stream().filter(p -> windowsCodexExecutable(p).equals("chatgpt.exe"))
                    .anyMatch(ClientReconnect::windowsCodexSiblingExists);
            matched = matched.stream().filter(p -> manageWindowsCodexProcess(p.info().command().orElse(""), nativeAvailable)).toList();
        }
        Set<Long> ids = new HashSet<>(); matched.forEach(p -> ids.add(p.pid()));
        return matched.stream().filter(p -> p.parent().map(parent -> !ids.contains(parent.pid())).orElse(true)).toList();
    }

    static boolean manageWindowsCodexProcess(String command, boolean nativeCodexAvailable) {
        if (!Platform.desktopProcessMatches(Platform.OS.WINDOWS, "Codex", command)) return false;
        return windowsCodexExecutable(command).equals("codex.exe") || !nativeCodexAvailable;
    }

    private static String windowsCodexExecutable(ProcessHandle process) {
        return windowsCodexExecutable(process.info().command().orElse(""));
    }

    private static String windowsCodexExecutable(String command) {
        String normalized = command.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return (slash < 0 ? normalized : normalized.substring(slash + 1)).toLowerCase(Locale.ROOT);
    }

    private static boolean windowsCodexSiblingExists(ProcessHandle process) {
        try {
            Path executable = Path.of(process.info().command().orElse(""));
            return executable.getParent() != null && Files.isRegularFile(executable.getParent().resolve("Codex.exe"));
        } catch (InvalidPathException ignored) { return false; }
    }

    static void stopDesktop(String client) throws Exception {
        for (ProcessHandle process : desktopProcesses(client)) stop(process, true);
    }

    static void stopForSettings(SecureStore store, String client, boolean cli) throws Exception {
        List<ProcessHandle> targets = cli ? cliProcesses(store, client.toLowerCase(Locale.ROOT)) : desktopProcesses(client);
        for (ProcessHandle process : targets) {
            if (!process.isAlive()) continue;
            if (!cli && Platform.OS_KIND == Platform.OS.WINDOWS) {
                Process request = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "$p=Get-Process -Id " + process.pid() + " -ErrorAction SilentlyContinue; if($p){[void]$p.CloseMainWindow()}")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!request.waitFor(5, TimeUnit.SECONDS)) { request.destroy(); throw new IOException("无法请求客户端正常退出，设置未修改"); }
            } else if (!cli && Platform.OS_KIND == Platform.OS.MAC) {
                String command = process.info().command().orElse("");
                int appEnd = command.indexOf(".app/");
                if (appEnd < 0) throw new IOException("无法确认客户端应用路径，请手动退出后重试");
                String app = command.substring(0, appEnd + 4).replace("\\", "\\\\").replace("\"", "\\\"");
                Process request = new ProcessBuilder("osascript", "-e", "tell application \"" + app + "\" to quit")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!request.waitFor(8, TimeUnit.SECONDS) || request.exitValue() != 0) {
                    request.destroy(); throw new IOException("客户端未同意退出，请手动退出后重试；设置未修改");
                }
            } else process.destroy();
            if (!waitForExit(process, 10)) throw new IOException("客户端尚未正常退出，请手动退出后重试；设置未修改");
        }
        if (!(cli ? cliProcesses(store, client.toLowerCase(Locale.ROOT)) : desktopProcesses(client)).isEmpty())
            throw new IOException("客户端仍在运行，设置未修改");
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
                    if (arg.startsWith("tokenpro_profile=") && normalized(arg.substring(17)).equals(expected)) return true;
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
        int timeout = cli ? CLI_START_TIMEOUT_SECONDS : DESKTOP_START_TIMEOUT_SECONDS;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (cli ? !cliProcesses(store, app.toLowerCase(Locale.ROOT)).isEmpty() : !desktopProcesses(app).isEmpty()) return;
            Thread.sleep(200);
        }
        throw new IOException(timeout + " 秒内未检测到 " + app + " 进程");
    }

    static void stopCli(SecureStore root, String client) throws Exception {
        for (ProcessHandle p : cliProcesses(root, client)) stop(p, false);
    }

    private static void stop(ProcessHandle process, boolean desktop) throws Exception {
        if (!process.isAlive()) return;
        var identity = process.info().startInstant();
        List<ProcessHandle> descendants = process.descendants().toList();
        if (desktop && Platform.OS_KIND == Platform.OS.WINDOWS) {
            // First ask the exact GUI PID to close normally so quit handlers can
            // flush sessions. Some desktop clients hide in the notification area
            // instead of exiting, so the confirmed reconnect action has an exact
            // process-tree fallback below.
            String powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
            new ProcessBuilder(powershell, "-NoProfile", "-NonInteractive", "-Command",
                "$p=Get-Process -Id " + process.pid() + " -ErrorAction SilentlyContinue; if($p){[void]$p.CloseMainWindow()}")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor(3, TimeUnit.SECONDS);
        } else process.destroy();
        if (waitForExit(process, desktop && Platform.OS_KIND == Platform.OS.WINDOWS ? 2 : 4)) return;
        if (!identity.equals(process.info().startInstant())) return;

        // The user has already confirmed that active requests may be stopped.
        // Terminate only the captured target tree; never match by process name and
        // never close the parent terminal application or unrelated client.
        for (int index = descendants.size() - 1; index >= 0; index--)
            if (descendants.get(index).isAlive()) descendants.get(index).destroy();
        process.destroy();
        if (waitForExit(process, 2)) return;
        if (!identity.equals(process.info().startInstant())) return;
        for (int index = descendants.size() - 1; index >= 0; index--)
            if (descendants.get(index).isAlive()) descendants.get(index).destroyForcibly();
        process.destroyForcibly();
        if (!waitForExit(process, 3))
            throw new IOException("目标程序仍在后台占用，无法安全重启；请从任务栏或托盘退出后重试");
    }

    private static boolean waitForExit(ProcessHandle process, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (process.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
        return !process.isAlive();
    }
}

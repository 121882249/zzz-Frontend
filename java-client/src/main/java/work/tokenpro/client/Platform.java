package work.tokenpro.client;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

final class Platform {
    enum OS { WINDOWS, MAC, LINUX }
    record InstallationSnapshot(boolean codexClient, boolean claudeClient, boolean codexCli, boolean claudeCli) {}
    static final OS OS_KIND = detect();
    private Platform() {}

    private static OS detect() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return OS.WINDOWS;
        if (name.contains("mac")) return OS.MAC;
        return OS.LINUX;
    }

    static Path dataDirectory() {
        String home = System.getProperty("user.home");
        return switch (OS_KIND) {
            case WINDOWS -> Path.of(System.getenv().getOrDefault("APPDATA", home), "TokenPro");
            case MAC -> Path.of(home, "Library", "Application Support", "TokenPro");
            case LINUX -> Path.of(System.getenv().getOrDefault("XDG_CONFIG_HOME", Path.of(home, ".config").toString()), "TokenPro");
        };
    }

    static Path codexConfig() {
        String custom = System.getenv("CODEX_HOME");
        Path root = custom == null || custom.isBlank() ? Path.of(System.getProperty("user.home"), ".codex") : Path.of(custom);
        return root.resolve("config.toml");
    }

    static Optional<Path> codexExecutable() {
        return codexExecutable(OS_KIND, System.getProperty("user.home"), System.getenv());
    }

    static Optional<Path> codexExecutable(OS os, String home, Map<String, String> environment) {
        List<Path> candidates = new ArrayList<>(commandCandidates(os, home, environment, "codex"));
        if (os == OS.WINDOWS) {
            Path versionedBin = Path.of(environment.getOrDefault("LOCALAPPDATA", home), "OpenAI", "Codex", "bin");
            candidates.add(versionedBin.resolve("codex.exe"));
            if (Files.isDirectory(versionedBin)) {
                try (Stream<Path> versions = Files.list(versionedBin)) {
                    versions.filter(Files::isDirectory)
                        .sorted(Comparator.comparingLong(Platform::lastModified).reversed())
                        .map(path -> path.resolve("codex.exe"))
                        .forEach(candidates::add);
                } catch (IOException ignored) {}
            }
        }
        candidates.addAll((switch (os) {
            case MAC -> Stream.of(
                Path.of("/Applications", "Codex.app", "Contents", "Resources", "codex"),
                Path.of(home, "Applications", "Codex.app", "Contents", "Resources", "codex"),
                Path.of("/Applications", "ChatGPT.app", "Contents", "Resources", "codex"),
                Path.of(home, "Applications", "ChatGPT.app", "Contents", "Resources", "codex"),
                Path.of("/opt/homebrew/bin/codex"), Path.of("/usr/local/bin/codex"));
            case WINDOWS -> Stream.of(
                Path.of(environment.getOrDefault("LOCALAPPDATA", home), "Programs", "Codex", "resources", "codex.exe"),
                Path.of(environment.getOrDefault("LOCALAPPDATA", home), "Programs", "ChatGPT", "resources", "codex.exe"),
                Path.of(environment.getOrDefault("APPDATA", home), "npm", "codex.cmd"));
            case LINUX -> Stream.of(Path.of("/usr/local/bin/codex"), Path.of("/usr/bin/codex"), Path.of(home, ".local", "bin", "codex"));
        }).toList());
        return candidates.stream().filter(path -> cliCandidateUsable(os, "Codex", path)).findFirst();
    }

    private static long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return Long.MIN_VALUE; }
    }

    static void browse(String url) throws Exception {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        else new ProcessBuilder(OS_KIND == OS.WINDOWS ? new String[]{"cmd", "/c", "start", "", url} : new String[]{"xdg-open", url}).start();
    }

    static boolean openApplication(String name) throws IOException {
        Process process;
        if (OS_KIND == OS.MAC) {
            String target = macApplicationTarget(name);
            process = new ProcessBuilder("open", "-a", target).start();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return false;
                new ProcessBuilder("osascript", "-e", "tell application \"" + target + "\" to activate").start();
                return true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        else if (OS_KIND == OS.WINDOWS) {
            Optional<Path> executable = desktopApplicationPath(OS.WINDOWS, System.getProperty("user.home"), System.getenv(), name);
            if (executable.isPresent() && executable.get().toString().toLowerCase(Locale.ROOT).endsWith(".lnk")) {
                process = new ProcessBuilder(Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "explorer.exe").toString(), executable.get().toString()).start();
            } else {
                process = executable.isPresent()
                    ? new ProcessBuilder(executable.get().toString()).start()
                    : openWindowsPackagedApplication(name);
            }
        }
        else {
            Optional<Path> executable = desktopApplicationPath(OS.LINUX, System.getProperty("user.home"), System.getenv(), name);
            if (executable.isPresent() && executable.get().toString().endsWith(".desktop"))
                process = new ProcessBuilder("gtk-launch", executable.get().toString()).start();
            else if (executable.isPresent()) process = new ProcessBuilder(executable.get().toString()).start();
            else {
                String desktopId = name.equals("Codex") ? "chatgpt" : name.toLowerCase(Locale.ROOT);
                process = new ProcessBuilder("gtk-launch", desktopId).start();
            }
        }
        return process.isAlive() || process.exitValue() == 0;
    }

    static boolean openClaudeThirdParty() throws IOException {
        if (OS_KIND != OS.MAC) return openApplication("Claude");
        if (claudeThirdPartyRunning()) return openApplication("Claude");
        Path application = applicationPath("Claude").orElseThrow(() -> new IOException("没有找到 Claude 应用"));
        Path data = Path.of(System.getProperty("user.home"), "Library", "Application Support", "Claude-3p");
        Process process = new ProcessBuilder("open", "-na", application.toString(), "--args", "--user-data-dir=" + data).start();
        try {
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean claudeThirdPartyRunning() {
        String dataArgument = "--user-data-dir=" + Path.of(System.getProperty("user.home"), "Library", "Application Support", "Claude-3p");
        return ProcessHandle.allProcesses().anyMatch(process -> {
            ProcessHandle.Info info = process.info();
            String command = info.command().orElse("");
            if (!desktopProcessMatches(OS_KIND, "Claude", command)) return false;
            if (OS_KIND != OS.MAC) return true;
            return Stream.of(info.arguments().orElse(new String[0])).anyMatch(dataArgument::equals);
        });
    }

    // Launching an app must never terminate a same-named CLI process.
    // There is deliberately no restart/quit-by-process-name helper here.

    private static String macApplicationTarget(String name) {
        return name.equals("Codex") && !applicationPath("Codex").map(Files::exists).orElse(false)
            && applicationPath("ChatGPT").map(Files::exists).orElse(false) ? "ChatGPT" : name;
    }

    static void openTerminalCommand(String command) throws IOException {
        openTerminalCommand(command, List.of());
    }

    static void openTerminalCommand(String command, List<String> arguments) throws IOException {
        openTerminalCommand(command, arguments, Map.of());
    }

    static void openTerminalCommand(String command, List<String> arguments, Map<String, String> environment) throws IOException {
        openTerminalProgram(resolveCli(command), arguments, environment);
    }

    static Path resolveCli(String command) throws IOException {
        if (!command.matches("[a-zA-Z0-9._-]+")) throw new IllegalArgumentException("命令名称无效");
        Optional<Path> path = commandExecutable(OS_KIND, System.getProperty("user.home"), System.getenv(), command);
        if (path.isPresent()) return path.get().toAbsolutePath();
        if (OS_KIND != OS.WINDOWS) {
            String shell = System.getenv().getOrDefault("SHELL", "/bin/sh");
            if (!Path.of(shell).isAbsolute() || !Files.isExecutable(Path.of(shell))) shell = "/bin/sh";
            for (String line : commandOutput(List.of(shell, "-lc", "command -v -- " + command), 3).lines().toList()) {
                try {
                    Path candidate = Path.of(line.trim());
                    if (candidate.isAbsolute() && cliCandidateUsable(OS_KIND, command.equals("claude") ? "Claude" : "Codex", candidate))
                        return candidate;
                } catch (InvalidPathException ignored) {}
            }
        }
        throw new IOException("没有找到可启动的 " + command + " 命令行程序；WSL 需要在其环境中单独配置");
    }

    static int runCli(String command, List<String> arguments, Map<String, String> environment) throws Exception {
        List<String> invocation = new ArrayList<>();
        invocation.add(resolveCli(command).toString()); invocation.addAll(arguments);
        ProcessBuilder process;
        if(OS_KIND == OS.WINDOWS && invocation.getFirst().toLowerCase(Locale.ROOT).matches(".*\\.(cmd|bat)$")) {
            StringBuilder script = new StringBuilder("$cliArgs=@(");
            for(int i=0;i<arguments.size();i++) { if(i>0) script.append(','); script.append("$env:TOKENPRO_CLI_ARG_").append(i); }
            script.append("); & $env:TOKENPRO_CLI_EXECUTABLE @cliArgs; exit $LASTEXITCODE");
            String encoded = java.util.Base64.getEncoder().encodeToString(script.toString().getBytes(StandardCharsets.UTF_16LE));
            process = new ProcessBuilder(windowsSystemExecutable("WindowsPowerShell\\v1.0\\powershell.exe"),
                "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded).inheritIO();
            process.environment().put("TOKENPRO_CLI_EXECUTABLE", invocation.getFirst());
            for(int i=0;i<arguments.size();i++) process.environment().put("TOKENPRO_CLI_ARG_"+i, arguments.get(i));
        } else process = new ProcessBuilder(invocation).inheritIO();
        applyCliEnvironment(process, environment);
        return process.start().waitFor();
    }

    static void openTerminalProgram(Path executable, List<String> arguments, Map<String, String> environment) throws IOException {
        if (!executable.isAbsolute()) throw new IllegalArgumentException("启动路径必须是完整路径");
        if (OS_KIND == OS.WINDOWS) {
            // Encoded PowerShell reads individual arguments from child-only variables.
            // No user path or API credential is interpolated into shell source.
            StringBuilder script = new StringBuilder("$cliArgs=@(");
            for (int i=0; i<arguments.size(); i++) {
                if(i>0) script.append(',');
                script.append("$env:TOKENPRO_CLI_ARG_").append(i);
            }
            script.append("); & $env:TOKENPRO_CLI_EXECUTABLE @cliArgs");
            String encoded = java.util.Base64.getEncoder().encodeToString(script.toString().getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder launcher = new ProcessBuilder("cmd", "/d", "/c", "start", "\"\"",
                windowsSystemExecutable("WindowsPowerShell\\v1.0\\powershell.exe"),
                "-NoProfile", "-NoExit", "-EncodedCommand", encoded);
            applyCliEnvironment(launcher, environment);
            launcher.environment().put("TOKENPRO_CLI_EXECUTABLE", executable.toString());
            for(int i=0; i<arguments.size(); i++) launcher.environment().put("TOKENPRO_CLI_ARG_"+i, arguments.get(i));
            launcher.start();
        } else {
            String command = posixCliCommand(executable.toString(), arguments, environment);
            if(OS_KIND == OS.MAC) new ProcessBuilder("osascript", "-e", "tell application \"Terminal\" to do script \""
                + command.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").start();
            else {
                String terminal = Stream.of("x-terminal-emulator", "gnome-terminal", "konsole", "xterm")
                    .filter(Platform::commandInstalled).findFirst().orElseThrow(() -> new IOException("没有找到可用终端"));
                new ProcessBuilder(terminal, terminal.equals("gnome-terminal") ? "--" : "-e",
                    "bash", "-lc", command + "; exec bash").start();
            }
        }
    }

    static String posixCliCommand(String executable, List<String> arguments, Map<String, String> environment) {
        StringBuilder command = new StringBuilder("env ");
        if(environment.containsKey("CLAUDE_CONFIG_DIR"))
            for(String name : claudeInheritedOverrides()) command.append("-u ").append(name).append(' ');
        for(var entry : environment.entrySet()) {
            if(!entry.getKey().matches("[A-Z_]+")) throw new IllegalArgumentException("环境变量名称无效");
            command.append(shellQuote(entry.getKey()+"="+entry.getValue())).append(' ');
        }
        command.append(shellQuote(executable));
        for(String argument : arguments) command.append(' ').append(shellQuote(argument));
        return command.toString();
    }

    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static void applyCliEnvironment(ProcessBuilder process, Map<String,String> environment) {
        if(environment.containsKey("CLAUDE_CONFIG_DIR")) claudeInheritedOverrides().forEach(process.environment()::remove);
        process.environment().remove("CLAUDE_HELPER_CONTEXT");
        process.environment().putAll(environment);
    }

    private static List<String> claudeInheritedOverrides() {
        return List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "ANTHROPIC_MODEL",
            "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY",
            "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL");
    }

    static boolean claudeCliRunning() {
        Optional<Path> executable = commandExecutable(OS_KIND, System.getProperty("user.home"), System.getenv(), "claude");
        return ProcessHandle.allProcesses().anyMatch(process -> {
            String command = process.info().command().orElse("");
            if (executable.isPresent() && sameExecutable(executable.get(), command)) return true;
            return Stream.of(process.info().arguments().orElse(new String[0]))
                .anyMatch(argument -> argument.contains("@anthropic-ai") && argument.contains("claude-code"));
        });
    }

    static boolean sameExecutable(Path expected, String actual) {
        if(actual == null || actual.isBlank()) return false;
        try { return Files.isSameFile(expected, Path.of(actual)); }
        catch(IOException | InvalidPathException ignored) { return expected.toAbsolutePath().toString().equals(actual); }
    }

    static boolean applicationInstalled(String name) {
        String home = System.getProperty("user.home");
        if (name.equals("Codex") && codexDesktopStatePresent(home)) return true;
        if (filesystemApplicationInstalled(OS_KIND, home, System.getenv(), name)) return true;
        return switch (OS_KIND) {
            case MAC -> macApplicationRegistered(name);
            case WINDOWS -> windowsPackagedApplicationInstalled(name);
            case LINUX -> applicationEvidenceMatches(name, linuxApplicationEvidence());
        };
    }

    static InstallationSnapshot installationSnapshot() {
        return installationSnapshot(null);
    }

    static InstallationSnapshot installationSnapshot(InstallationSnapshot known) {
        String home = System.getProperty("user.home");
        Map<String, String> environment = System.getenv();
        boolean codexClient = known != null && known.codexClient()
            || codexDesktopStatePresent(home)
            || filesystemApplicationInstalled(OS_KIND, home, environment, "Codex");
        boolean claudeClient = known != null && known.claudeClient()
            || filesystemApplicationInstalled(OS_KIND, home, environment, "Claude");
        if (OS_KIND == OS.MAC) {
            if (!codexClient) codexClient = macApplicationRegistered("Codex");
            if (!claudeClient) claudeClient = macApplicationRegistered("Claude");
        }
        if (OS_KIND == OS.WINDOWS) {
            if (!codexClient) codexClient = windowsPackagedApplicationInstalled("Codex");
            if (!claudeClient) claudeClient = windowsPackagedApplicationInstalled("Claude");
        }
        if (!codexClient || !claudeClient) {
            String evidence = OS_KIND == OS.LINUX ? linuxApplicationEvidence() : "";
            if (!codexClient) codexClient = applicationEvidenceMatches("Codex", evidence);
            if (!claudeClient) claudeClient = applicationEvidenceMatches("Claude", evidence);
        }
        boolean codexCli = known != null && known.codexCli() || commandInstalled("codex");
        boolean claudeCli = known != null && known.claudeCli() || commandInstalled("claude");
        return new InstallationSnapshot(codexClient, claudeClient, codexCli, claudeCli);
    }

    static boolean codexDesktopStatePresent(String home) {
        // config.toml and the directory itself are shared with Codex CLI and can
        // also be created by TokenPro. This UI state file is desktop-specific.
        return Files.isRegularFile(Path.of(home, ".codex", ".codex-global-state.json"));
    }

    static boolean filesystemApplicationInstalled(OS os, String home, Map<String, String> environment, String name) {
        return desktopApplicationPath(os, home, environment, name).isPresent();
    }

    static Optional<Path> desktopApplicationPath(OS os, String home, Map<String, String> environment, String name) {
        Optional<Path> direct = applicationCandidates(os, home, environment, name).stream()
            .filter(path -> desktopCandidateUsable(os, path)).findFirst();
        if (direct.isPresent() || os != OS.WINDOWS) return direct;
        List<Path> matches = new ArrayList<>();
        for (Path root : windowsVersionedInstallRoots(home, environment, name)) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> paths = Files.walk(root, 5)) {
                paths.filter(path -> desktopCandidateUsable(os, path))
                    .filter(path -> {
                        String file = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (file.endsWith(".lnk")) return name.equals("Codex")
                            ? file.equals("codex.lnk") || file.equals("chatgpt.lnk") : file.equals("claude.lnk");
                        return desktopProcessMatches(OS.WINDOWS, name, path.toString());
                    }).forEach(matches::add);
            } catch (IOException ignored) {}
        }
        return matches.stream().sorted(Comparator.comparingLong(Platform::lastModified).reversed()).findFirst();
    }

    private static boolean desktopCandidateUsable(OS os, Path path) {
        if (cliInstallPath(path.toString())) return false;
        if (os == OS.MAC) return Files.isDirectory(path) && path.toString().endsWith(".app");
        if (!Files.isRegularFile(path)) return false;
        String file = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (os == OS.WINDOWS) return file.endsWith(".exe") || file.endsWith(".lnk");
        return file.endsWith(".desktop") || Files.isExecutable(path);
    }

    private static boolean cliInstallPath(String value) {
        String path = value.replace('\\', '/').toLowerCase(Locale.ROOT);
        return path.contains("/npm/") || path.contains("/.claude/") || path.contains("/.local/bin/")
            || path.contains("/resources/") || path.contains("/openai/codex/bin/")
            || path.contains("claudecode") || path.contains("claude-code")
            || path.contains("/winget/packages/openai.codex_");
    }

    static boolean desktopProcessMatches(OS os, String name, String value) {
        if (value == null || value.isBlank() || cliInstallPath(value)) return false;
        String path = value.replace('\\', '/').toLowerCase(Locale.ROOT);
        String lower = name.toLowerCase(Locale.ROOT);
        if (os == OS.MAC) return path.endsWith("/" + lower + ".app/contents/macos/" + lower);
        if (os == OS.WINDOWS) {
            if (name.equals("Claude")) return path.endsWith("/claude.exe")
                && (path.contains("/windowsapps/claude_") || path.contains("/claude/")
                    || path.contains("/anthropicclaude/") || path.contains("/anthropic.claude_"));
            return (path.endsWith("/codex.exe") || path.endsWith("/chatgpt.exe"))
                && (path.contains("/windowsapps/openai.codex_") || path.contains("/windowsapps/openai.chatgpt-desktop_")
                    || path.contains("/codex/") || path.contains("/chatgpt/"));
        }
        return path.endsWith("/" + lower + ".appimage")
            || path.endsWith("/opt/" + lower + "/" + lower);
    }

    static List<Path> windowsVersionedInstallRoots(String home, Map<String, String> environment, String name) {
        String local = environment.getOrDefault("LOCALAPPDATA", home);
        String roaming = environment.getOrDefault("APPDATA", home);
        String programFiles = environment.getOrDefault("ProgramFiles", "C:\\Program Files");
        String programFilesX86 = environment.getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)");
        String programData = environment.getOrDefault("ProgramData", "C:\\ProgramData");
        String publicHome = environment.getOrDefault("PUBLIC", "C:\\Users\\Public");
        List<Path> shared = List.of(
            Path.of(local, "Microsoft", "WinGet", "Packages"),
            Path.of(home, "scoop", "apps"),
            Path.of(programData, "chocolatey", "lib"),
            Path.of(roaming, "Microsoft", "Windows", "Start Menu", "Programs"),
            Path.of(programData, "Microsoft", "Windows", "Start Menu", "Programs"),
            Path.of(home, "Desktop"), Path.of(publicHome, "Desktop"));
        List<Path> roots = new ArrayList<>();
        if (name.equals("Codex")) roots.addAll(List.of(
            Path.of(local, "Codex"), Path.of(local, "ChatGPT"), Path.of(local, "OpenAI", "ChatGPT"),
            Path.of(local, "Programs", "Codex"), Path.of(local, "Programs", "ChatGPT"),
            Path.of(programFiles, "Codex"), Path.of(programFiles, "ChatGPT"), Path.of(programFiles, "OpenAI"),
            Path.of(programFilesX86, "Codex"), Path.of(programFilesX86, "ChatGPT"), Path.of(programFilesX86, "OpenAI")));
        else roots.addAll(List.of(
            Path.of(local, "Claude"), Path.of(local, "AnthropicClaude"), Path.of(local, "Anthropic", "Claude"),
            Path.of(local, "Programs", "Claude"), Path.of(programFiles, "Claude"), Path.of(programFiles, "Anthropic"),
            Path.of(programFilesX86, "Claude"), Path.of(programFilesX86, "Anthropic")));
        roots.addAll(shared);
        return List.copyOf(roots);
    }

    static List<Path> applicationCandidates(OS os, String home, Map<String, String> environment, String name) {
        String local = environment.getOrDefault("LOCALAPPDATA", home);
        String roaming = environment.getOrDefault("APPDATA", home);
        String programFiles = environment.getOrDefault("ProgramFiles", "C:\\Program Files");
        String programFilesX86 = environment.getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)");
        String programData = environment.getOrDefault("ProgramData", "C:\\ProgramData");
        if (os == OS.MAC) {
            List<Path> paths = new ArrayList<>(List.of(
                Path.of("/Applications", name + ".app"), Path.of(home, "Applications", name + ".app")));
            if (name.equals("Codex")) {
                paths.add(Path.of("/Applications", "ChatGPT.app"));
                paths.add(Path.of(home, "Applications", "ChatGPT.app"));
            }
            return List.copyOf(paths);
        }
        if (os == OS.WINDOWS) {
            List<Path> paths = new ArrayList<>();
            List<String> folders = name.equals("Codex") ? List.of("Codex", "ChatGPT", "OpenAI\\ChatGPT")
                : List.of("Claude", "AnthropicClaude", "Anthropic\\Claude");
            List<String> executables = name.equals("Codex") ? List.of("Codex.exe", "ChatGPT.exe") : List.of("Claude.exe");
            for (String root : List.of(local + "\\Programs", local, programFiles, programFilesX86)) {
                for (String folder : folders) for (String executable : executables) paths.add(Path.of(root, folder, executable));
            }
            // Store apps use their registered AUMID; a command alias can belong to a CLI.
            List<String> shortcutNames = name.equals("Codex") ? List.of("Codex.lnk", "ChatGPT.lnk") : List.of("Claude.lnk");
            for (String shortcut : shortcutNames) {
                paths.add(Path.of(roaming, "Microsoft", "Windows", "Start Menu", "Programs", shortcut));
                paths.add(Path.of(programData, "Microsoft", "Windows", "Start Menu", "Programs", shortcut));
                paths.add(Path.of(home, "Desktop", shortcut));
                paths.add(Path.of(environment.getOrDefault("PUBLIC", "C:\\Users\\Public"), "Desktop", shortcut));
            }
            if (name.equals("Codex")) {
                paths.add(Path.of(roaming, "Microsoft", "Windows", "Start Menu", "Programs", "OpenAI", "ChatGPT.lnk"));
                paths.add(Path.of(programData, "Microsoft", "Windows", "Start Menu", "Programs", "OpenAI", "ChatGPT.lnk"));
            } else {
                paths.add(Path.of(roaming, "Microsoft", "Windows", "Start Menu", "Programs", "Anthropic", "Claude.lnk"));
                paths.add(Path.of(programData, "Microsoft", "Windows", "Start Menu", "Programs", "Anthropic", "Claude.lnk"));
            }
            return List.copyOf(paths);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        List<Path> paths = new ArrayList<>(List.of(
            Path.of("/opt", lower, lower), Path.of("/opt", name, lower),
            Path.of(home, "Applications", name + ".AppImage"),
            Path.of("/usr/share/applications", lower + ".desktop"),
            Path.of(home, ".local", "share", "applications", lower + ".desktop")));
        if (name.equals("Codex")) {
            paths.add(Path.of("/opt", "chatgpt", "chatgpt"));
            paths.add(Path.of("/opt", "ChatGPT", "chatgpt"));
            paths.add(Path.of("/usr/local/bin", "chatgpt"));
            paths.add(Path.of("/usr/bin", "chatgpt"));
            paths.add(Path.of("/usr/share/applications", "chatgpt.desktop"));
            paths.add(Path.of(home, ".local", "share", "applications", "chatgpt.desktop"));
        }
        return List.copyOf(paths);
    }

    static boolean applicationEvidenceMatches(String name, String evidence) {
        String normalized = evidence == null ? "" : evidence.toLowerCase(Locale.ROOT);
        return name.equals("Codex")
            ? normalized.contains("codex") || normalized.contains("chatgpt") || normalized.contains("openai.chat")
            : normalized.contains("claude") || normalized.contains("anthropic");
    }

    static List<String> windowsPackageNames(String name) {
        return name.equals("Codex")
            ? List.of("OpenAI.Codex", "OpenAI.ChatGPT-Desktop")
            : List.of("Claude");
    }

    static List<String> windowsApplicationIds(String name) {
        return name.equals("Codex")
            ? List.of("OpenAI.Codex_2p2nqsd0c76g0!App", "OpenAI.ChatGPT-Desktop_2p2nqsd0c76g0!App")
            : List.of();
    }

    static boolean windowsApplicationIdMatches(String name, String id) {
        if (id == null) return false;
        return name.equals("Codex")
            ? id.matches("(?i)^OpenAI\\.(Codex|ChatGPT-Desktop)_[a-z0-9]+!App$")
            : name.equals("Claude") && id.matches("(?i)^Claude_[a-z0-9]+!Claude$");
    }

    static Optional<String> registeredWindowsApplicationId(String name) {
        String packages = windowsPackageNames(name).stream().map(value -> "'" + value + "'")
            .collect(java.util.stream.Collectors.joining(","));
        String script = "$ErrorActionPreference='SilentlyContinue';"
            + "Get-StartApps | Where-Object {$_.AppID -match '^(Claude_|OpenAI\\.)'} | ForEach-Object {$_.AppID};"
            + "foreach($name in @(" + packages + ")){Get-AppxPackage -Name $name | ForEach-Object {"
            + "$pkg=$_; $manifest=Get-AppxPackageManifest -Package $pkg.PackageFullName;"
            + "$manifest.Package.Applications.Application | ForEach-Object {$pkg.PackageFamilyName+'!'+$_.Id}}}";
        String output = commandOutput(List.of(windowsSystemExecutable("WindowsPowerShell\\v1.0\\powershell.exe"),
            "-NoProfile", "-NonInteractive", "-Command", script), 10);
        return output.lines().map(String::trim).filter(id -> windowsApplicationIdMatches(name, id)).findFirst();
    }

    static List<String> windowsPackagedLaunchCommand(String name, String id, String systemRoot) {
        if (!windowsApplicationIdMatches(name, id)) throw new IllegalArgumentException("应用启动 ID 不匹配");
        return List.of(Path.of(systemRoot, "explorer.exe").toString(), "shell:AppsFolder\\" + id);
    }

    private static Process openWindowsPackagedApplication(String name) throws IOException {
        String id = registeredWindowsApplicationId(name)
            .orElseThrow(() -> new IOException("没有找到 " + name + " 客户端的有效启动入口，请确认已安装桌面应用并重新检测"));
        return new ProcessBuilder(windowsPackagedLaunchCommand(name, id,
            System.getenv().getOrDefault("SystemRoot", "C:\\Windows"))).start();
    }

    private static boolean windowsPackagedApplicationInstalled(String name) {
        return registeredWindowsApplicationId(name).isPresent();
    }

    private static boolean windowsProtocolRegistered(String name) {
        List<String> protocols = name.equals("Codex") ? List.of("codex", "chatgpt") : List.of("claude");
        String reg = windowsSystemExecutable("reg.exe");
        for (String protocol : protocols) {
            if (silentCommandSucceeded(List.of(reg, "query", "HKCR\\" + protocol), 2)) return true;
            if (silentCommandSucceeded(List.of(reg, "query", "HKCU\\Software\\Classes\\" + protocol), 2)) return true;
        }
        return false;
    }

    private static boolean windowsPackageRepositoryRegistered(String name) {
        String reg = windowsSystemExecutable("reg.exe");
        String repository = "HKCU\\Software\\Classes\\Local Settings\\Software\\Microsoft\\Windows\\CurrentVersion\\AppModel\\Repository\\Packages";
        for (String packageName : windowsPackageNames(name)) {
            if (silentCommandSucceeded(List.of(reg, "query", repository, "/f", packageName + "_", "/k", "/s"), 4)) return true;
        }
        return false;
    }

    private static boolean windowsPackagedProcessRunning(String name) {
        List<String> markers = name.equals("Codex")
            ? List.of("\\windowsapps\\openai.codex_", "\\windowsapps\\openai.chatgpt-desktop_")
            : List.of("\\windowsapps\\claude_");
        return ProcessHandle.allProcesses().anyMatch(process -> {
            String command = process.info().command().orElse("").replace('/', '\\').toLowerCase(Locale.ROOT);
            return markers.stream().anyMatch(command::contains);
        });
    }

    private static String windowsSystemExecutable(String relativePath) {
        Path candidate = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", relativePath);
        return Files.isRegularFile(candidate) ? candidate.toString() : Path.of(relativePath).getFileName().toString();
    }

    private static boolean macApplicationRegistered(String name) {
        if (commandSucceeded(List.of("open", "-Ra", name), 3)) return true;
        return name.equals("Codex") && commandSucceeded(List.of("open", "-Ra", "ChatGPT"), 3);
    }

    private static String windowsApplicationEvidence() {
        String script = "$ErrorActionPreference='SilentlyContinue';"
            + "$pattern='(?i)codex|chatgpt|openai|claude|anthropic';"
            + "Get-AppxPackage | Where-Object { \"$($_.Name)|$($_.PackageFamilyName)|$($_.InstallLocation)\" -match $pattern } | ForEach-Object { \"$($_.Name)|$($_.PackageFamilyName)|$($_.InstallLocation)\" };"
            + "Get-StartApps | Where-Object { \"$($_.Name)|$($_.AppID)\" -match $pattern } | ForEach-Object { \"$($_.Name)|$($_.AppID)\" };"
            + "$roots=@('HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*','HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*','HKLM:\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*');"
            + "Get-ItemProperty $roots | Where-Object { \"$($_.DisplayName)|$($_.InstallLocation)|$($_.DisplayIcon)\" -match $pattern } | ForEach-Object { \"$($_.DisplayName)|$($_.InstallLocation)|$($_.DisplayIcon)\" };"
            + "Get-ChildItem 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\App Paths','HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\App Paths' | Where-Object { $_.PSChildName -match $pattern } | ForEach-Object { $_.PSChildName };"
            + "$shortcuts=@(\"$env:APPDATA\\Microsoft\\Windows\\Start Menu\\Programs\",\"$env:ProgramData\\Microsoft\\Windows\\Start Menu\\Programs\",\"$env:USERPROFILE\\Desktop\",\"$env:PUBLIC\\Desktop\");"
            + "Get-ChildItem $shortcuts -Filter *.lnk -Recurse | Where-Object { $_.Name -match $pattern } | ForEach-Object { $_.FullName }";
        return commandOutput(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), 10);
    }

    private static String linuxApplicationEvidence() {
        return commandOutput(List.of("sh", "-lc", "(flatpak list --app --columns=application,name 2>/dev/null || true; snap list 2>/dev/null || true) | grep -Ei 'codex|chatgpt|openai|claude|anthropic' || true"), 5);
    }

    private static Optional<Path> applicationPath(String name) {
        String home = System.getProperty("user.home");
        return Stream.of(Path.of("/Applications", name + ".app"), Path.of(home, "Applications", name + ".app")).filter(Files::exists).findFirst();
    }

    static boolean commandInstalled(String command) {
        if (!command.matches("[a-zA-Z0-9._-]+")) return false;
        if (nativeCommandInstalled(command)) return true;
        return OS_KIND == OS.WINDOWS
            && commandSucceeded(List.of("wsl.exe", "-e", "sh", "-lc", "command -v -- " + command), 4);
    }

    private static boolean nativeCommandInstalled(String command) {
        String home = System.getProperty("user.home");
        Map<String, String> environment = System.getenv();
        if (commandCandidateInstalled(OS_KIND, home, environment, command)) return true;
        String loginShell = environment.getOrDefault("SHELL", "/bin/sh");
        if (!Path.of(loginShell).isAbsolute() || !Files.isExecutable(Path.of(loginShell))) loginShell = "/bin/sh";
        List<String> lookup = OS_KIND == OS.WINDOWS
            ? List.of("where.exe", command)
            : List.of(loginShell, "-lc", "command -v -- " + command);
        return !commandOutput(lookup, 3).isBlank();
    }

    static boolean commandCandidateInstalled(OS os, String home, Map<String, String> environment, String command) {
        return commandExecutable(os, home, environment, command).isPresent();
    }

    static Optional<Path> commandExecutable(OS os, String home, Map<String, String> environment, String command) {
        if (command.equals("codex")) return codexExecutable(os, home, environment);
        return commandCandidates(os, home, environment, command).stream()
            .filter(path -> cliCandidateUsable(os, command.equals("claude") ? "Claude" : command, path)).findFirst();
    }

    private static boolean cliCandidateUsable(OS os, String client, Path path) {
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if ((client.equals("Codex") || client.equals("Claude")) && (desktopProcessMatches(os, client, normalized)
            || normalized.contains("/microsoft/windowsapps/"))) return false;
        return os == OS.WINDOWS ? Files.isRegularFile(path) : Files.isExecutable(path);
    }

    static List<Path> commandCandidates(OS os, String home, Map<String, String> environment, String command) {
        String pathValue = environment.entrySet().stream()
            .filter(entry -> os == OS.WINDOWS ? entry.getKey().equalsIgnoreCase("PATH") : entry.getKey().equals("PATH"))
            .map(Map.Entry::getValue).findFirst().orElse("");
        String separator = os == OS.WINDOWS ? ";" : ":";
        List<String> suffixes = os == OS.WINDOWS ? List.of(".exe", ".cmd", ".bat", "") : List.of("");
        List<Path> candidates = new ArrayList<>();
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(separator))) {
            if (entry.isBlank()) continue;
            entry = entry.trim();
            if (entry.startsWith("\"") && entry.endsWith("\"") && entry.length() >= 2) entry = entry.substring(1, entry.length() - 1);
            if (entry.isBlank()) continue;
            try {
                for (String suffix : suffixes) candidates.add(Path.of(entry).resolve(command + suffix));
            } catch (InvalidPathException ignored) {}
        }
        if (os == OS.WINDOWS) {
            String roaming = environment.getOrDefault("APPDATA", home);
            String local = environment.getOrDefault("LOCALAPPDATA", home);
            String packagePrefix = command.equals("claude") ? "Anthropic.ClaudeCode_"
                : command.equals("codex") ? "OpenAI.Codex_" : "";
            Path packages = Path.of(local, "Microsoft", "WinGet", "Packages");
            if (!packagePrefix.isEmpty() && Files.isDirectory(packages)) {
                try (Stream<Path> entries = Files.list(packages)) {
                    entries.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(packagePrefix.toLowerCase(Locale.ROOT)))
                        .sorted(Comparator.comparingLong(Platform::lastModified).reversed())
                        .map(path -> path.resolve(command + ".exe")).forEach(candidates::add);
                } catch (IOException ignored) {}
            }
            for (String suffix : suffixes) {
                candidates.add(Path.of(roaming, "npm", command + suffix));
                candidates.add(Path.of(home, ".local", "bin", command + suffix));
                candidates.add(Path.of(home, ".claude", "local", command + suffix));
                candidates.add(Path.of(home, ".bun", "bin", command + suffix));
                candidates.add(Path.of(local, "pnpm", command + suffix));
                candidates.add(Path.of(local, "Microsoft", "WindowsApps", command + suffix));
                candidates.add(Path.of(local, "Microsoft", "WinGet", "Links", command + suffix));
                candidates.add(Path.of(home, "scoop", "shims", command + suffix));
                candidates.add(Path.of(environment.getOrDefault("ProgramData", "C:\\ProgramData"), "chocolatey", "bin", command + suffix));
                candidates.add(Path.of(environment.getOrDefault("ProgramFiles", "C:\\Program Files"), "nodejs", command + suffix));
            }
        } else {
            for (String root : List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", Path.of(home, ".local", "bin").toString(), Path.of(home, ".npm", "bin").toString(), Path.of(home, ".npm-global", "bin").toString(), Path.of(home, ".local", "share", "pnpm").toString(), Path.of(home, ".bun", "bin").toString(), Path.of(home, ".claude", "local").toString())) {
                candidates.add(Path.of(root, command));
            }
        }
        return List.copyOf(candidates);
    }

    private static String commandOutput(List<String> command, int timeoutSeconds) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) return "";
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean commandSucceeded(List<String> command, int timeoutSeconds) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean silentCommandSucceeded(List<String> command, int timeoutSeconds) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    static Optional<String> githubLatestReleaseJson() {
        String home = System.getProperty("user.home");
        Stream<Path> candidates = OS_KIND == OS.WINDOWS
            ? Stream.of(Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "GitHub CLI", "gh.exe"), Path.of(home, "scoop", "shims", "gh.exe"))
            : Stream.of(Path.of("/opt/homebrew/bin/gh"), Path.of("/usr/local/bin/gh"), Path.of("/usr/bin/gh"));
        Optional<Path> gh = candidates.filter(Files::isExecutable).findFirst();
        if (gh.isEmpty()) return Optional.empty();
        try {
            Process process = new ProcessBuilder(gh.get().toString(), "api", "repositories/1360196661/releases/latest").redirectErrorStream(true).start();
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) { process.destroyForcibly(); return Optional.empty(); }
            return Optional.of(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception ignored) { return Optional.empty(); }
    }

    static void privateFile(Path path) {
        try {
            if (OS_KIND != OS.WINDOWS) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {}
    }
}

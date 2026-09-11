package work.tokenpro.client;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
        String home = System.getProperty("user.home");
        Stream<Path> candidates = switch (OS_KIND) {
            case MAC -> Stream.of(
                Path.of("/Applications", "Codex.app", "Contents", "Resources", "codex"),
                Path.of(home, "Applications", "Codex.app", "Contents", "Resources", "codex"),
                Path.of("/Applications", "ChatGPT.app", "Contents", "Resources", "codex"),
                Path.of(home, "Applications", "ChatGPT.app", "Contents", "Resources", "codex"),
                Path.of("/opt/homebrew/bin/codex"), Path.of("/usr/local/bin/codex"));
            case WINDOWS -> Stream.of(
                Path.of(System.getenv().getOrDefault("LOCALAPPDATA", home), "Programs", "Codex", "resources", "codex.exe"),
                Path.of(System.getenv().getOrDefault("LOCALAPPDATA", home), "Programs", "ChatGPT", "resources", "codex.exe"),
                Path.of(System.getenv().getOrDefault("APPDATA", home), "npm", "codex.cmd"));
            case LINUX -> Stream.of(Path.of("/usr/local/bin/codex"), Path.of("/usr/bin/codex"), Path.of(home, ".local", "bin", "codex"));
        };
        return candidates.filter(Files::isRegularFile).findFirst();
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
            Optional<Path> executable = applicationCandidates(OS.WINDOWS, System.getProperty("user.home"), System.getenv(), name)
                .stream().filter(Files::exists).findFirst();
            if (executable.isPresent() && executable.get().toString().toLowerCase(Locale.ROOT).endsWith(".lnk")) {
                process = new ProcessBuilder("cmd", "/c", "start", "", executable.get().toString()).start();
            } else {
                process = executable.isPresent()
                    ? new ProcessBuilder(executable.get().toString()).start()
                    : new ProcessBuilder("cmd", "/c", "start", "", name.equals("Codex") ? "ChatGPT" : name).start();
            }
        }
        else {
            Optional<Path> executable = applicationCandidates(OS.LINUX, System.getProperty("user.home"), System.getenv(), name)
                .stream().filter(Files::isExecutable).findFirst();
            if (executable.isPresent()) process = new ProcessBuilder(executable.get().toString()).start();
            else {
                String desktopId = name.equals("Codex") ? "chatgpt" : name.toLowerCase(Locale.ROOT);
                process = new ProcessBuilder("gtk-launch", desktopId).start();
            }
        }
        return process.isAlive() || process.exitValue() == 0;
    }

    static boolean openClaudeThirdParty() throws IOException {
        if (OS_KIND != OS.MAC) return openApplication("Claude");
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
            String command = info.command().orElse("").toLowerCase(Locale.ROOT);
            if (!command.contains("claude")) return false;
            if (OS_KIND != OS.MAC) return true;
            return Stream.of(info.arguments().orElse(new String[0])).anyMatch(dataArgument::equals);
        });
    }

    static boolean restartApplication(String name) throws Exception {
        if (OS_KIND == OS.MAC) {
            String target = macApplicationTarget(name);
            Process quit = new ProcessBuilder("osascript", "-e", "tell application \"" + target + "\" to quit").start();
            if (!quit.waitFor(5, TimeUnit.SECONDS) || quit.exitValue() != 0) return false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (applicationRunning(target) && System.nanoTime() < deadline) Thread.sleep(200);
            if (applicationRunning(target)) return false;
            Process open = new ProcessBuilder("open", "-a", target).start();
            return open.waitFor(5, TimeUnit.SECONDS) && open.exitValue() == 0;
        }
        if (OS_KIND == OS.WINDOWS) {
            if (name.equals("Codex")) {
                new ProcessBuilder("taskkill", "/IM", "Codex.exe", "/F").start().waitFor(5, TimeUnit.SECONDS);
                new ProcessBuilder("taskkill", "/IM", "ChatGPT.exe", "/F").start().waitFor(5, TimeUnit.SECONDS);
            } else new ProcessBuilder("taskkill", "/IM", name + ".exe", "/F").start().waitFor(5, TimeUnit.SECONDS);
            Thread.sleep(350);
            return openApplication(name);
        }
        new ProcessBuilder("pkill", "-x", name.toLowerCase(Locale.ROOT)).start().waitFor(3, TimeUnit.SECONDS);
        if (name.equals("Codex")) new ProcessBuilder("pkill", "-x", "chatgpt").start().waitFor(3, TimeUnit.SECONDS);
        Thread.sleep(350);
        return openApplication(name);
    }

    static boolean reconnectApplication(String name) throws Exception {
        if (OS_KIND == OS.MAC && !applicationRunning(macApplicationTarget(name))) return openApplication(name);
        return restartApplication(name);
    }

    static boolean quitClaudeThirdParty() throws Exception {
        if (!claudeThirdPartyRunning()) return true;
        Process quit;
        if (OS_KIND == OS.MAC) quit = new ProcessBuilder("osascript", "-e", "tell application \"Claude\" to quit").start();
        else if (OS_KIND == OS.WINDOWS) quit = new ProcessBuilder("taskkill", "/IM", "Claude.exe", "/F").start();
        else quit = new ProcessBuilder("pkill", "-x", "claude").start();
        if (!quit.waitFor(5, TimeUnit.SECONDS) || (quit.exitValue() != 0 && claudeThirdPartyRunning())) return false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (claudeThirdPartyRunning() && System.nanoTime() < deadline) Thread.sleep(200);
        return !claudeThirdPartyRunning();
    }

    private static boolean applicationRunning(String name) throws Exception {
        Process process = new ProcessBuilder("pgrep", "-x", name).start();
        return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
    }

    private static String macApplicationTarget(String name) {
        return name.equals("Codex") && !applicationPath("Codex").map(Files::exists).orElse(false)
            && applicationPath("ChatGPT").map(Files::exists).orElse(false) ? "ChatGPT" : name;
    }

    static void openTerminalCommand(String command) throws IOException {
        if (!command.matches("[a-zA-Z0-9._-]+")) throw new IllegalArgumentException("命令名称无效");
        if (OS_KIND == OS.MAC) {
            new ProcessBuilder("osascript", "-e", "tell application \"Terminal\" to do script \"" + command + "\"").start();
        } else if (OS_KIND == OS.WINDOWS) {
            if (nativeCommandInstalled(command)) new ProcessBuilder("cmd", "/c", "start", "", "cmd", "/k", command).start();
            else new ProcessBuilder("cmd", "/c", "start", "", "wsl.exe", "-e", "sh", "-lc", command).start();
        } else {
            String terminal = Stream.of("x-terminal-emulator", "gnome-terminal", "konsole", "xterm")
                .filter(Platform::commandInstalled).findFirst().orElseThrow(() -> new IOException("没有找到可用终端"));
            if (terminal.equals("gnome-terminal")) new ProcessBuilder(terminal, "--", "bash", "-lc", command + "; exec bash").start();
            else if (terminal.equals("konsole")) new ProcessBuilder(terminal, "-e", "bash", "-lc", command + "; exec bash").start();
            else new ProcessBuilder(terminal, "-e", command).start();
        }
    }

    static boolean applicationInstalled(String name) {
        String home = System.getProperty("user.home");
        if (name.equals("Codex") && codexDesktopStatePresent(home)) return true;
        if (filesystemApplicationInstalled(OS_KIND, home, System.getenv(), name)) return true;
        return switch (OS_KIND) {
            case MAC -> macApplicationRegistered(name);
            case WINDOWS -> applicationEvidenceMatches(name, windowsApplicationEvidence());
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
        if (!codexClient || !claudeClient) {
            String evidence = OS_KIND == OS.WINDOWS ? windowsApplicationEvidence()
                : OS_KIND == OS.LINUX ? linuxApplicationEvidence() : "";
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
        if (applicationCandidates(os, home, environment, name).stream().anyMatch(Files::exists)) return true;
        if (os != OS.WINDOWS) return false;
        // Store/MSIX applications live below the protected WindowsApps directory.
        // Do not try to crawl that directory: query the current user's package
        // registration directly, using the official package identities instead.
        if (OS_KIND == OS.WINDOWS && windowsPackagedApplicationInstalled(name)) return true;
        List<String> executableNames = name.equals("Codex") ? List.of("codex.exe", "chatgpt.exe") : List.of("claude.exe");
        for (Path root : windowsVersionedInstallRoots(home, environment, name)) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> paths = Files.walk(root, 5)) {
                if (paths.filter(Files::isRegularFile).anyMatch(path -> {
                    String file = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (executableNames.contains(file)) return true;
                    if (!file.endsWith(".lnk")) return false;
                    return name.equals("Codex") ? file.contains("codex") || file.contains("chatgpt") : file.contains("claude");
                })) return true;
            } catch (Exception ignored) {}
        }
        return false;
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
            for (String executable : executables) paths.add(Path.of(local, "Microsoft", "WindowsApps", executable));
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
            Path.of("/usr/local/bin", lower), Path.of("/usr/bin", lower),
            Path.of(home, ".local", "bin", lower),
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

    private static boolean windowsPackagedApplicationInstalled(String name) {
        if (windowsPackagedProcessRunning(name)) return true;
        if (windowsProtocolRegistered(name)) return true;
        if (windowsPackageRepositoryRegistered(name)) return true;

        String packages = windowsPackageNames(name).stream()
            .map(value -> "'" + value.replace("'", "''") + "'")
            .collect(java.util.stream.Collectors.joining(","));
        String startApps = name.equals("Codex")
            ? "$ids=@('OpenAI.Codex_2p2nqsd0c76g0!App','OpenAI.ChatGPT-Desktop_2p2nqsd0c76g0!App');"
                + "if(@(Get-StartApps | Where-Object {$ids -contains $_.AppID}).Count -gt 0){exit 0};"
            : "if(@(Get-StartApps | Where-Object {$_.AppID -match '(?i)^Claude_.*!Claude$'}).Count -gt 0){exit 0};";
        String script = "$ErrorActionPreference='SilentlyContinue';"
            + "$packages=@(" + packages + ");"
            + "foreach($package in $packages){"
            + "if(@(Get-AppxPackage -Name $package -ErrorAction SilentlyContinue).Count -gt 0){exit 0}"
            + "};" + startApps + "exit 1";
        // Use the system executable explicitly because GUI applications do not
        // always inherit the same PATH as an interactive Windows terminal.
        return silentCommandSucceeded(List.of(windowsSystemExecutable("WindowsPowerShell\\v1.0\\powershell.exe"),
            "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script), 15);
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
        return commandCandidates(os, home, environment, command).stream()
            .anyMatch(path -> os == OS.WINDOWS ? Files.isRegularFile(path) : Files.isExecutable(path));
    }

    static List<Path> commandCandidates(OS os, String home, Map<String, String> environment, String command) {
        String pathValue = environment.getOrDefault("PATH", "");
        String separator = os == OS.WINDOWS ? ";" : ":";
        List<String> suffixes = os == OS.WINDOWS ? List.of(".exe", ".cmd", ".bat", "") : List.of("");
        List<Path> candidates = new ArrayList<>();
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(separator))) {
            if (entry.isBlank()) continue;
            for (String suffix : suffixes) candidates.add(Path.of(entry).resolve(command + suffix));
        }
        if (os == OS.WINDOWS) {
            String roaming = environment.getOrDefault("APPDATA", home);
            String local = environment.getOrDefault("LOCALAPPDATA", home);
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

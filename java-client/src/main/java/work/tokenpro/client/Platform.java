package work.tokenpro.client;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

final class Platform {
    enum OS { WINDOWS, MAC, LINUX }
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
        else if (OS_KIND == OS.WINDOWS) process = new ProcessBuilder("cmd", "/c", "start", "", name).start();
        else process = new ProcessBuilder(name.toLowerCase(Locale.ROOT)).start();
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
            new ProcessBuilder("taskkill", "/IM", name + ".exe", "/F").start().waitFor(5, TimeUnit.SECONDS);
            Thread.sleep(350);
            return openApplication(name);
        }
        new ProcessBuilder("pkill", "-x", name.toLowerCase(Locale.ROOT)).start().waitFor(3, TimeUnit.SECONDS);
        Thread.sleep(350);
        return openApplication(name);
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
            new ProcessBuilder("cmd", "/c", "start", "", "cmd", "/k", command).start();
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
        if (OS_KIND == OS.MAC) {
            boolean installed = applicationPath(name).map(Files::exists).orElse(false);
            return installed || (name.equals("Codex") && applicationPath("ChatGPT").map(Files::exists).orElse(false));
        }
        if (OS_KIND == OS.WINDOWS) {
            String local = System.getenv().getOrDefault("LOCALAPPDATA", home);
            return Stream.of(Path.of(local, "Programs", name, name + ".exe"), Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), name, name + ".exe")).anyMatch(Files::exists);
        }
        return commandInstalled(name.toLowerCase(Locale.ROOT));
    }

    private static Optional<Path> applicationPath(String name) {
        String home = System.getProperty("user.home");
        return Stream.of(Path.of("/Applications", name + ".app"), Path.of(home, "Applications", name + ".app")).filter(Files::exists).findFirst();
    }

    static boolean commandInstalled(String command) {
        String path = System.getenv().getOrDefault("PATH", "");
        String executable = OS_KIND == OS.WINDOWS ? command + ".exe" : command;
        return Stream.of(path.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))).map(Path::of).map(dir -> dir.resolve(executable)).anyMatch(Files::isExecutable);
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

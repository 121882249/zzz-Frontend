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

    static void browse(String url) throws Exception {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        else new ProcessBuilder(OS_KIND == OS.WINDOWS ? new String[]{"cmd", "/c", "start", "", url} : new String[]{"xdg-open", url}).start();
    }

    static boolean openApplication(String name) throws IOException {
        Process process;
        if (OS_KIND == OS.MAC) {
            String target = name.equals("Codex") && !applicationPath("Codex").map(Files::exists).orElse(false) && applicationPath("ChatGPT").map(Files::exists).orElse(false) ? "ChatGPT" : name;
            process = new ProcessBuilder("open", "-a", target).start();
        }
        else if (OS_KIND == OS.WINDOWS) process = new ProcessBuilder("cmd", "/c", "start", "", name).start();
        else process = new ProcessBuilder(name.toLowerCase(Locale.ROOT)).start();
        return process.isAlive() || process.exitValue() == 0;
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
            Process process = new ProcessBuilder(gh.get().toString(), "api", "repos/121882249/TokenPro-Frontend/releases/latest").redirectErrorStream(true).start();
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

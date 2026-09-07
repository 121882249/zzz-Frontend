package work.tokenpro.client;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;

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
        if (OS_KIND == OS.MAC) process = new ProcessBuilder("open", "-a", name).start();
        else if (OS_KIND == OS.WINDOWS) process = new ProcessBuilder("cmd", "/c", "start", "", name).start();
        else process = new ProcessBuilder(name.toLowerCase(Locale.ROOT)).start();
        return process.isAlive() || process.exitValue() == 0;
    }

    static void privateFile(Path path) {
        try {
            if (OS_KIND != OS.WINDOWS) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {}
    }
}

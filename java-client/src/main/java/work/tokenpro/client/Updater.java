package work.tokenpro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

final class Updater {
    private Updater() {}

    static String platformKey() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        return switch (Platform.OS_KIND) {
            case MAC -> arm ? "macos-arm64" : "macos-x64";
            case WINDOWS -> arm ? "windows-arm64" : "windows-x64";
            case LINUX -> arm ? "linux-arm64" : "linux-x64";
        };
    }

    static void install(Path installer) throws Exception {
        long pid = ProcessHandle.current().pid();
        String command = ProcessHandle.current().info().command().orElse("");
        switch (Platform.OS_KIND) {
            case MAC -> installMac(installer, pid, macApplication(command));
            case WINDOWS -> installWindows(installer, pid, command);
            case LINUX -> installLinux(installer, pid);
        }
    }

    static Path macApplication(String command) {
        int end = command.indexOf(".app/");
        if (end >= 0) return Path.of(command.substring(0, end + 4));
        Path installed = Path.of("/Applications/TokenPro.app");
        if (Files.isDirectory(installed)) return installed;
        throw new IllegalStateException("无法确定 TokenPro.app 的安装位置");
    }

    private static void installMac(Path installer, long pid, Path target) throws Exception {
        String body = """
            #!/bin/sh
            set -eu
            pid="$1"
            image="$2"
            target="$3"
            while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
            mount=$(/usr/bin/hdiutil attach "$image" -nobrowse -readonly | tail -1 | awk -F '\\t' '{print $NF}')
            source="$mount/TokenPro.app"
            backup="${target}.update-backup"
            cleanup() { /usr/bin/hdiutil detach "$mount" >/dev/null 2>&1 || true; }
            trap cleanup EXIT
            if [ ! -d "$source" ]; then exit 1; fi
            rm -rf "$backup"
            if [ -d "$target" ]; then mv "$target" "$backup"; fi
            if /usr/bin/ditto "$source" "$target"; then
              rm -rf "$backup"
            else
              rm -rf "$target"
              if [ -d "$backup" ]; then mv "$backup" "$target"; fi
              exit 1
            fi
            cleanup
            trap - EXIT
            /usr/bin/open "$target"
            rm -f "$image" "$0"
            """;
        Path script = script("tokenpro-update-", ".sh", body);
        new ProcessBuilder("/bin/sh", script.toString(), Long.toString(pid), installer.toString(), target.toString()).start();
    }

    private static void installWindows(Path installer, long pid, String command) throws Exception {
        String body = """
            @echo off
            setlocal
            :wait
            tasklist /FI "PID eq %~1" 2>NUL | find "%~1" >NUL
            if not errorlevel 1 (timeout /T 1 /NOBREAK >NUL & goto wait)
            start /wait "" "%~2" /quiet
            if not "%~3"=="" start "" "%~3"
            del /Q "%~2" >NUL 2>&1
            del /Q "%~f0" >NUL 2>&1
            """;
        Path script = script("tokenpro-update-", ".cmd", body);
        new ProcessBuilder("cmd", "/c", "start", "", "/b", script.toString(), Long.toString(pid), installer.toString(), command).start();
    }

    private static void installLinux(Path installer, long pid) throws Exception {
        String body = """
            #!/bin/sh
            set -eu
            pid="$1"
            package="$2"
            while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
            pkexec env DEBIAN_FRONTEND=noninteractive dpkg -i "$package"
            nohup /opt/tokenpro/bin/TokenPro >/dev/null 2>&1 &
            rm -f "$package" "$0"
            """;
        Path script = script("tokenpro-update-", ".sh", body);
        new ProcessBuilder("/bin/sh", script.toString(), Long.toString(pid), installer.toString()).start();
    }

    private static Path script(String prefix, String suffix, String body) throws IOException {
        Path path = Files.createTempFile(prefix, suffix);
        Files.writeString(path, body, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        path.toFile().setExecutable(true, true);
        return path;
    }
}

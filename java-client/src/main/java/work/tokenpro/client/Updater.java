package work.tokenpro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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

    static void installIncremental(Path update, String version) throws Exception {
        long pid = ProcessHandle.current().pid();
        String command = ProcessHandle.current().info().command().orElse("");
        Path target = applicationJar(command);
        Path merged = mergeUpdate(target, update);
        Files.deleteIfExists(update);
        switch (Platform.OS_KIND) {
            case MAC -> installMacIncremental(merged, pid, target, macApplication(command), version);
            case WINDOWS -> installWindowsIncremental(merged, pid, target, command);
            case LINUX -> installLinuxIncremental(merged, pid, target, command);
        }
    }

    static Path mergeUpdate(Path current, Path update) throws Exception {
        if (!Files.isRegularFile(current)) throw new IllegalStateException("找不到当前 TokenPro 核心文件");
        Path merged = Files.createTempFile("TokenPro-merged-", ".jar");
        Set<String> written = new HashSet<>();
        try (ZipFile base = new ZipFile(current.toFile()); ZipFile patch = new ZipFile(update.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(merged, StandardOpenOption.TRUNCATE_EXISTING))) {
            if (patch.getEntry("work/tokenpro/client/Main.class") == null) throw new IllegalStateException("增量更新包不完整");
            Enumeration<? extends ZipEntry> patchEntries = patch.entries();
            while (patchEntries.hasMoreElements()) {
                String name = patchEntries.nextElement().getName();
                boolean allowedDirectory = name.endsWith("/") && "work/tokenpro/client/".startsWith(name);
                boolean allowedClass = name.startsWith("work/tokenpro/client/") && name.endsWith(".class");
                if (!allowedDirectory && !allowedClass) {
                    throw new IllegalStateException("增量更新包包含无效文件");
                }
            }
            Enumeration<? extends ZipEntry> baseEntries = base.entries();
            while (baseEntries.hasMoreElements()) {
                ZipEntry oldEntry = baseEntries.nextElement();
                ZipEntry replacement = patch.getEntry(oldEntry.getName());
                copyEntry(replacement == null ? base : patch, replacement == null ? oldEntry : replacement, output);
                written.add(oldEntry.getName());
            }
            patchEntries = patch.entries();
            while (patchEntries.hasMoreElements()) {
                ZipEntry entry = patchEntries.nextElement();
                if (written.add(entry.getName())) copyEntry(patch, entry, output);
            }
        } catch (Exception ex) {
            Files.deleteIfExists(merged);
            throw ex;
        }
        if (Files.size(merged) < 1_000_000) {
            Files.deleteIfExists(merged);
            throw new IllegalStateException("合并后的核心文件不完整");
        }
        return merged;
    }

    private static void copyEntry(ZipFile source, ZipEntry entry, ZipOutputStream output) throws IOException {
        ZipEntry copy = new ZipEntry(entry.getName());
        copy.setTime(entry.getTime());
        output.putNextEntry(copy);
        if (!entry.isDirectory()) source.getInputStream(entry).transferTo(output);
        output.closeEntry();
    }

    private static Path applicationJar(String command) {
        if (Platform.OS_KIND == Platform.OS.MAC) return macApplication(command).resolve("Contents/app/TokenPro.jar");
        Path launcher = command.isBlank() ? Path.of("") : Path.of(command).toAbsolutePath();
        Path launcherDirectory = launcher.getParent() == null ? Path.of("") : launcher.getParent();
        Path windows = launcherDirectory.resolve("app/TokenPro.jar");
        if (Files.isRegularFile(windows)) return windows;
        Path root = launcherDirectory.getParent() == null ? Path.of("") : launcherDirectory.getParent();
        Path linux = root.resolve("lib/app/TokenPro.jar");
        if (Files.isRegularFile(linux)) return linux;
        String javaCommand = System.getProperty("sun.java.command", "").split("\\s+", 2)[0];
        Path direct = Path.of(javaCommand).toAbsolutePath();
        if (Files.isRegularFile(direct)) return direct;
        throw new IllegalStateException("无法确定 TokenPro 核心文件位置");
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
        startUpdater(new ProcessBuilder("/bin/sh", script.toString(), Long.toString(pid), installer.toString(), target.toString()));
    }

    private static void installMacIncremental(Path source, long pid, Path target, Path application, String version) throws Exception {
        Path script = script("tokenpro-update-", ".sh", macIncrementalScript());
        startUpdater(new ProcessBuilder("/bin/sh", script.toString(), Long.toString(pid), source.toString(), target.toString(), application.toString(), version));
    }

    static String macIncrementalScript() {
        return """
            #!/bin/sh
            set -eu
            pid="$1"
            source="$2"
            target="$3"
            application="$4"
            version="$5"
            lock="${TMPDIR:-/tmp}/tokenpro-application-update.lock"
            if ! mkdir "$lock" 2>/dev/null; then
              owner=$(cat "$lock/pid" 2>/dev/null || true)
              if [ -n "$owner" ] && kill -0 "$owner" 2>/dev/null; then exit 0; fi
              rm -rf "$lock"
              mkdir "$lock"
            fi
            echo $$ > "$lock/pid"
            work_dir=$(mktemp -d "${TMPDIR:-/tmp}/tokenpro-update-work.XXXXXX")
            trap 'rm -rf "$lock" "$work_dir"' EXIT
            while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
            /usr/bin/pkill -f "$application/Contents/MacOS/TokenPro" 2>/dev/null || true
            attempts=0
            while /usr/bin/pgrep -f "$application/Contents/MacOS/TokenPro" >/dev/null 2>&1 && [ "$attempts" -lt 25 ]; do
              sleep 0.2
              attempts=$((attempts + 1))
            done
            helper="$work_dir/apply-update.sh"
            cat > "$helper" <<'TOKENPRO_HELPER'
            #!/bin/sh
            set -eu
            source="$1"
            target="$2"
            application="$3"
            version="$4"
            backup_dir="$5"
            plist="$application/Contents/Info.plist"
            backup="$backup_dir/TokenPro.jar"
            plist_backup="$backup_dir/Info.plist"
            cp "$target" "$backup"
            cp "$plist" "$plist_backup"
            rollback() {
              cp "$backup" "$target" 2>/dev/null || true
              cp "$plist_backup" "$plist" 2>/dev/null || true
              /usr/bin/codesign --force --deep --sign - "$application" >/dev/null 2>&1 || true
            }
            trap rollback EXIT HUP INT TERM
            cp "$source" "$target.next"
            chmod 0644 "$target.next"
            mv -f "$target.next" "$target"
            /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $version" "$plist" || true
            /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $version" "$plist" || true
            /usr/bin/xattr -cr "$application" 2>/dev/null || true
            /usr/bin/codesign --force --deep --sign - "$application"
            trap - EXIT HUP INT TERM
            TOKENPRO_HELPER
            chmod 0700 "$helper"
            backup_dir="$work_dir/backup"
            mkdir "$backup_dir"
            if ! "$helper" "$source" "$target" "$application" "$version" "$backup_dir"; then
              rm -rf "$backup_dir"
              mkdir "$backup_dir"
              /usr/bin/osascript - "$helper" "$source" "$target" "$application" "$version" "$backup_dir" <<'TOKENPRO_APPLESCRIPT'
            on run argv
              set commandText to quoted form of item 1 of argv
              repeat with argumentIndex from 2 to count of argv
                set commandText to commandText & space & quoted form of item argumentIndex of argv
              end repeat
              do shell script commandText with administrator privileges
            end run
            TOKENPRO_APPLESCRIPT
            fi
            sleep 1
            /usr/bin/open -n "$application"
            rm -f "$source"
            rm -f "$0"
            """;
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
        startUpdater(new ProcessBuilder("cmd", "/c", "start", "", "/b", script.toString(), Long.toString(pid), installer.toString(), command));
    }

    private static void installWindowsIncremental(Path source, long pid, Path target, String command) throws Exception {
        String body = """
            @echo off
            setlocal
            :wait
            tasklist /FI "PID eq %~1" 2>NUL | find "%~1" >NUL
            if not errorlevel 1 (timeout /T 1 /NOBREAK >NUL & goto wait)
            copy /Y "%~2" "%~3" >NUL
            if errorlevel 1 exit /b 1
            start "" "%~4"
            del /Q "%~2" >NUL 2>&1
            del /Q "%~f0" >NUL 2>&1
            """;
        Path script = script("tokenpro-update-", ".cmd", body);
        startUpdater(new ProcessBuilder("cmd", "/c", "start", "", "/b", script.toString(), Long.toString(pid), source.toString(), target.toString(), command));
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
        startUpdater(new ProcessBuilder("/bin/sh", script.toString(), Long.toString(pid), installer.toString()));
    }

    private static void installLinuxIncremental(Path source, long pid, Path target, String command) throws Exception {
        String body = """
            #!/bin/sh
            set -eu
            pid="$1"
            source="$2"
            target="$3"
            command="$4"
            while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
            pkexec install -m 0644 "$source" "$target"
            nohup "$command" >/dev/null 2>&1 &
            rm -f "$source" "$0"
            """;
        Path script = script("tokenpro-update-", ".sh", body);
        startUpdater(new ProcessBuilder("/bin/sh", script.toString(), Long.toString(pid), source.toString(), target.toString(), command));
    }

    private static void startUpdater(ProcessBuilder process) throws IOException {
        Path logDirectory = Path.of(System.getProperty("user.home"), ".tokenpro", "logs");
        try {
            Files.createDirectories(logDirectory);
            process.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logDirectory.resolve("updater.log").toFile()));
        } catch (Exception ignored) {
            process.redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        process.start();
    }

    private static Path script(String prefix, String suffix, String body) throws IOException {
        Path path = Files.createTempFile(prefix, suffix);
        Files.writeString(path, body, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        path.toFile().setExecutable(true, true);
        return path;
    }
}

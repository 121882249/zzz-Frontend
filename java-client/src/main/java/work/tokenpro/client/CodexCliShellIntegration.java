package work.tokenpro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Makes an interactive {@code codex} command use TokenPro without changing PATH. */
final class CodexCliShellIntegration {
    private static final String START = "# >>> TokenPro Codex CLI >>>";
    private static final String END = "# <<< TokenPro Codex CLI <<<";

    private CodexCliShellIntegration() {}

    static List<Path> install(SecureStore store, Path officialCodex) throws Exception {
        Path launcher = CliLauncher.install(store, "codex");
        if (sameFile(launcher, officialCodex))
            throw new IOException("无法将 TokenPro 启动器设为默认命令：没有找到独立的官方 codex 命令");
        return install(Platform.OS_KIND, Path.of(System.getProperty("user.home")), System.getenv("SHELL"), launcher, officialCodex);
    }

    static List<Path> install(Platform.OS os, Path home, String shell, Path launcher, Path officialCodex) throws IOException {
        if (!launcher.isAbsolute() || !officialCodex.isAbsolute()) throw new IOException("命令行启动路径必须是绝对路径");
        List<Path> targets = startupFiles(os, home, shell);
        String content = os == Platform.OS.WINDOWS ? powerShellBlock(launcher, officialCodex)
            : shell != null && shell.toLowerCase(Locale.ROOT).contains("fish") ? fishBlock(launcher, officialCodex)
            : posixBlock(launcher, officialCodex);
        for (Path target : targets) writeBlock(target, content);
        return targets;
    }

    static List<Path> startupFiles(Platform.OS os, Path home, String shell) {
        if (os == Platform.OS.WINDOWS) return List.of(
            home.resolve("Documents/WindowsPowerShell/Microsoft.PowerShell_profile.ps1"),
            home.resolve("Documents/PowerShell/Microsoft.PowerShell_profile.ps1"));
        String normalized = shell == null ? "" : shell.toLowerCase(Locale.ROOT);
        if (normalized.contains("fish")) return List.of(home.resolve(".config/fish/config.fish"));
        if (normalized.contains("bash")) return os == Platform.OS.MAC
            ? List.of(home.resolve(".bashrc"), home.resolve(".bash_profile"))
            : List.of(home.resolve(".bashrc"));
        // GUI apps do not always receive SHELL from the desktop session. Use each
        // platform's normal default rather than guessing from the PATH.
        return List.of(home.resolve(os == Platform.OS.MAC ? ".zshrc" : ".bashrc"));
    }

    static String posixBlock(Path launcher, Path officialCodex) {
        return START + "\n"
            + "# TokenPro-managed default. Run codex-official for the original CLI.\n"
            + "codex() {\n  " + posixQuote(launcher.toString()) + " \"$@\"\n}\n"
            + "codex-official() {\n  " + posixQuote(officialCodex.toString()) + " \"$@\"\n}\n"
            + END + "\n";
    }

    static String fishBlock(Path launcher, Path officialCodex) {
        return START + "\n"
            + "# TokenPro-managed default. Run codex-official for the original CLI.\n"
            + "function codex\n  command " + posixQuote(launcher.toString()) + " $argv\nend\n"
            + "function codex-official\n  command " + posixQuote(officialCodex.toString()) + " $argv\nend\n"
            + END + "\n";
    }

    static String powerShellBlock(Path launcher, Path officialCodex) {
        return START + "\r\n"
            + "# TokenPro-managed default. Run codex-official for the original CLI.\r\n"
            + "function global:codex { & " + powerShellQuote(launcher.toString()) + " @args }\r\n"
            + "function global:codex-official { & " + powerShellQuote(officialCodex.toString()) + " @args }\r\n"
            + END + "\r\n";
    }

    static String replaceBlock(String original, String replacement) throws IOException {
        int start = original.indexOf(START);
        int end = original.indexOf(END);
        if (start < 0 && end >= 0 || start >= 0 && end < start || (start >= 0 && original.indexOf(START, start + START.length()) >= 0)
            || (end >= 0 && original.indexOf(END, end + END.length()) >= 0))
            throw new IOException("检测到损坏的 TokenPro Codex 启动标记，请先手动整理启动文件");
        if (start < 0) return original + (original.isEmpty() || original.endsWith("\n") ? "" : "\n") + replacement;
        int after = end + END.length();
        if (after < original.length() && original.charAt(after) == '\r') after++;
        if (after < original.length() && original.charAt(after) == '\n') after++;
        return original.substring(0, start) + replacement + original.substring(after);
    }

    private static void writeBlock(Path file, String replacement) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        String original = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        String updated = replaceBlock(original, replacement);
        if (!updated.equals(original)) Files.writeString(file, updated, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static boolean sameFile(Path first, Path second) {
        try { return Files.isSameFile(first, second); }
        catch (IOException ignored) { return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize()); }
    }
    private static String posixQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static String powerShellQuote(String value) { return "'" + value.replace("'", "''") + "'"; }
}

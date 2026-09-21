package work.tokenpro.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** Installs the TokenPro image Skill beside Codex's user-managed Skills. */
final class TokenProImageSkill {
    private static final String NAME = "tokenpro-imagegen";
    private static final String DISABLED = "SKILL.md.disabled-by-tokenpro";

    private TokenProImageSkill() {}

    static void install(Path configPath) throws IOException {
        Path root = configPath.getParent().resolve("skills").resolve(NAME);
        Files.createDirectories(root.resolve("agents"));
        Files.createDirectories(root.resolve("scripts"));
        String scriptPath = root.resolve("scripts/tokenpro-imagegen").toAbsolutePath().toString();
        String skill = readResource("/tokenpro-imagegen/SKILL.md").replace("{{TOKENPRO_IMAGEGEN_SCRIPT}}", scriptPath);
        Files.writeString(root.resolve("SKILL.md"), skill, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        writeResource(root.resolve("agents/openai.yaml"), "/tokenpro-imagegen/agents/openai.yaml");
        String launcher = ProcessHandle.current().info().command().orElse("");
        if (launcher.isBlank()) throw new IOException("无法定位 TokenPro 启动程序，未安装 tokenpro-imagegen");
        String shell = readResource("/tokenpro-imagegen/scripts/tokenpro-imagegen").replace("{{TOKENPRO_EXECUTABLE}}", shellQuote(launcher));
        Files.writeString(root.resolve("scripts/tokenpro-imagegen"), shell, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.setPosixFilePermissions(root.resolve("scripts/tokenpro-imagegen"), PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) {}
        String command = launcher.endsWith(".exe") || launcher.endsWith(".cmd") ? launcher : launcher;
        String windows = readResource("/tokenpro-imagegen/scripts/tokenpro-imagegen.cmd").replace("{{TOKENPRO_EXECUTABLE}}", command);
        Files.writeString(root.resolve("scripts/tokenpro-imagegen.cmd"), windows, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.deleteIfExists(root.resolve(DISABLED));
    }

    static void deactivate(Path configPath) throws IOException {
        Path root = configPath.getParent().resolve("skills").resolve(NAME);
        Path active = root.resolve("SKILL.md");
        if (Files.exists(active, LinkOption.NOFOLLOW_LINKS))
            Files.move(active, root.resolve(DISABLED), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeResource(Path target, String resource) throws IOException {
        Files.writeString(target, readResource(resource), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream input = TokenProImageSkill.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("TokenPro 内置资源缺失：" + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}

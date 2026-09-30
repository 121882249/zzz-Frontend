package work.tokenpro.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.URISyntaxException;
import java.util.List;

/** Installs the TokenPro image Skill beside Codex's user-managed Skills. */
final class TokenProImageSkill {
    private static final String NAME = "tokenpro-imagegen";
    /** Codex resolves a plain "draw an image" request through this conventional skill name first. */
    private static final String COMPATIBILITY_NAME = "imagegen";
    /** Codex ships a higher-priority system imagegen Skill that must not win in TokenPro mode. */
    private static final String SYSTEM_IMAGE_SKILL = ".system/imagegen";
    private static final String SYSTEM_DISABLED = "SKILL.md.disabled-by-tokenpro";
    private static final String DISABLED = "SKILL.md.disabled-by-tokenpro";
    private static final String PREVIOUS = "SKILL.md.before-tokenpro";
    private static final String SKILL_FALLBACK = """
        ---
        name: tokenpro-imagegen
        description: Generate or edit raster images through TokenPro when the TokenPro channel is connected. Use this for requests to draw, create, generate, render, illustrate, redesign, redraw, transform, or edit an image. The skill uses the TokenPro-provided local CLI and preserves the selected global-key route.
        ---

        # TokenPro Image Generation

        When TokenPro is connected, this Skill is the only image-generation path.
        Invoke the bundled local command below directly for every image request.
        Do not call Codex's built-in `image_gen`/`image_generation` tool, do not
        call a hosted image generator, and do not fall back to another image
        interface. The command calls TokenPro's `/v1/images/generations` endpoint
        with the existing global Key and the currently selected TokenPro route
        as the image driver. If the active Codex model is a dedicated GPT
        image-group route, that route is preserved and used directly.

        ## Generate

        ```bash
        \"{{TOKENPRO_IMAGEGEN_SCRIPT}}\" generate \\
          --prompt \"<complete image prompt>\" \\
          --out \"/absolute/path/to/output.png\"
        ```

        Optional arguments are `--model`, `--size`, `--quality`, and `--background`.
        If `--model` is omitted, the command uses the saved TokenPro image
        selection (`image_model` plus its `group_id`) when one exists; otherwise
        it falls back to the active Codex text route. This keeps ordinary Codex
        text on its text group while direct Skill image requests can use the
        dedicated GPT image group.

        Always use an absolute output path and return the generated file exactly
        once using an absolute Markdown image path. Do not call `view_image` for
        the same file or send a second Markdown image link. Do not print, copy,
        or request the global Key.
        """;
    private static final String AGENT_FALLBACK = """
        interface:
          display_name: \"TokenPro ImageGen\"
          short_description: \"Generate images through the TokenPro Skill CLI\"
          default_prompt: \"Use $tokenpro-imagegen for image generation and editing through TokenPro.\"
        policy:
          allow_implicit_invocation: true
        """;
    private static final String SHELL_FALLBACK = """
        #!/bin/sh
        set -eu
        exec {{TOKENPRO_EXECUTABLE}} --tokenpro-imagegen \"$@\"
        """;
    private static final String WINDOWS_FALLBACK = """
        @echo off
        \"{{TOKENPRO_EXECUTABLE}}\" --tokenpro-imagegen %*
        """;

    private TokenProImageSkill() {}

    static void install(Path configPath) throws IOException {
        String launcher = launcherCommand();
        disableSystemImageSkill(configPath);
        installAt(configPath, NAME, launcher);
        // Keep the named plugin for explicit invocation, and also expose the
        // conventional imagegen name used by Codex's implicit image requests.
        installAt(configPath, COMPATIBILITY_NAME, launcher);
    }

    /** Return a complete command, not only the JVM executable. */
    private static String launcherCommand() throws IOException {
        String java = javaExecutable();
        try {
            Path location = Path.of(TokenProImageSkill.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize();
            if (Files.isRegularFile(location) && location.toString().toLowerCase().endsWith(".jar")) {
                if (Platform.OS_KIND == Platform.OS.WINDOWS)
                    return "\"" + java + "\" -jar \"" + location + "\"";
                return shellQuote(java) + " -jar " + shellQuote(location.toString());
            }
        } catch (URISyntaxException | NullPointerException ignored) { }
        throw new IOException("无法定位 TokenPro.jar，未安装 tokenpro-imagegen");
    }

    private static String javaExecutable() throws IOException {
        String executable = Platform.OS_KIND == Platform.OS.WINDOWS ? "java.exe" : "java";
        Path bundled = Path.of(System.getProperty("java.home", ""), "bin", executable);
        if (Files.isExecutable(bundled)) return bundled.toAbsolutePath().normalize().toString();
        String process = ProcessHandle.current().info().command().orElse("");
        if (!process.isBlank() && (process.endsWith("/java") || process.endsWith("\\java.exe"))) return process;
        throw new IOException("无法定位 Java 运行时，未安装 tokenpro-imagegen");
    }

    private static void installAt(Path configPath, String name, String launcher) throws IOException {
        Path root = configPath.getParent().resolve("skills").resolve(name);
        Files.createDirectories(root);
        preserveExistingCompatibilitySkill(root, name);
        Files.createDirectories(root.resolve("agents"));
        Files.createDirectories(root.resolve("scripts"));
        String scriptPath = root.resolve("scripts/tokenpro-imagegen").toAbsolutePath().toString();
        String skill = readResource("/tokenpro-imagegen/SKILL.md")
            .replace("name: tokenpro-imagegen", "name: " + name)
            .replace("{{TOKENPRO_IMAGEGEN_SCRIPT}}", scriptPath);
        Files.writeString(root.resolve("SKILL.md"), skill, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        String agent = readResource("/tokenpro-imagegen/agents/openai.yaml")
            .replace("$tokenpro-imagegen", "$" + name);
        Files.writeString(root.resolve("agents/openai.yaml"), agent, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        String shell = readResource("/tokenpro-imagegen/scripts/tokenpro-imagegen").replace("{{TOKENPRO_EXECUTABLE}}", launcher);
        Files.writeString(root.resolve("scripts/tokenpro-imagegen"), shell, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.setPosixFilePermissions(root.resolve("scripts/tokenpro-imagegen"), PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) {}
        String command = launcher;
        String windows = readResource("/tokenpro-imagegen/scripts/tokenpro-imagegen.cmd").replace("{{TOKENPRO_EXECUTABLE}}", command);
        Files.writeString(root.resolve("scripts/tokenpro-imagegen.cmd"), windows, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.deleteIfExists(root.resolve(DISABLED));
    }

    static void deactivate(Path configPath) throws IOException {
        deactivateAt(configPath, NAME, true);
        // Official Codex must use its native image tool. Keep any previous
        // compatibility skill disabled so it cannot silently take precedence.
        deactivateAt(configPath, COMPATIBILITY_NAME, false);
        restoreSystemImageSkill(configPath);
    }

    private static void disableSystemImageSkill(Path configPath) throws IOException {
        Path root = configPath.getParent().resolve("skills").resolve(SYSTEM_IMAGE_SKILL);
        Path active = root.resolve("SKILL.md");
        Path disabled = root.resolve(SYSTEM_DISABLED);
        if (Files.exists(active, LinkOption.NOFOLLOW_LINKS) && !Files.exists(disabled, LinkOption.NOFOLLOW_LINKS))
            Files.move(active, disabled, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void restoreSystemImageSkill(Path configPath) throws IOException {
        Path root = configPath.getParent().resolve("skills").resolve(SYSTEM_IMAGE_SKILL);
        Path active = root.resolve("SKILL.md");
        Path disabled = root.resolve(SYSTEM_DISABLED);
        if (Files.exists(disabled, LinkOption.NOFOLLOW_LINKS) && !Files.exists(active, LinkOption.NOFOLLOW_LINKS))
            Files.move(disabled, active, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deactivateAt(Path configPath, String name, boolean restorePrevious) throws IOException {
        Path root = configPath.getParent().resolve("skills").resolve(name);
        Path active = root.resolve("SKILL.md");
        if (Files.exists(active, LinkOption.NOFOLLOW_LINKS))
            Files.move(active, root.resolve(DISABLED), StandardCopyOption.REPLACE_EXISTING);
        Path previous = root.resolve(PREVIOUS);
        if (restorePrevious && Files.exists(previous, LinkOption.NOFOLLOW_LINKS) && !Files.exists(active, LinkOption.NOFOLLOW_LINKS))
            Files.move(previous, active, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void preserveExistingCompatibilitySkill(Path root, String name) throws IOException {
        if (!COMPATIBILITY_NAME.equals(name)) return;
        Path active = root.resolve("SKILL.md");
        if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS)) return;
        String current = Files.readString(active);
        if (current.contains("TokenPro Image Generation") || current.contains("name: tokenpro-imagegen")) return;
        Files.move(active, root.resolve(PREVIOUS), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream input = TokenProImageSkill.class.getResourceAsStream(resource)) {
            if (input == null) return fallbackResource(resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String fallbackResource(String resource) throws IOException {
        return switch (resource) {
            case "/tokenpro-imagegen/SKILL.md" -> SKILL_FALLBACK;
            case "/tokenpro-imagegen/agents/openai.yaml" -> AGENT_FALLBACK;
            case "/tokenpro-imagegen/scripts/tokenpro-imagegen" -> SHELL_FALLBACK;
            case "/tokenpro-imagegen/scripts/tokenpro-imagegen.cmd" -> WINDOWS_FALLBACK;
            default -> throw new IOException("TokenPro 内置资源缺失：" + resource);
        };
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}

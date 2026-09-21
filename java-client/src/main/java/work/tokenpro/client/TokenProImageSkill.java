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
    private static final String SKILL_FALLBACK = """
        ---
        name: tokenpro-imagegen
        description: Generate or edit raster images through TokenPro when the TokenPro channel is connected. Use this for requests to draw, create, generate, render, illustrate, redesign, redraw, transform, or edit an image. The skill uses the TokenPro-provided local CLI and preserves the selected global-key route.
        ---

        # TokenPro Image Generation

        When TokenPro is connected, use the bundled local command below for image
        generation. Codex stays on the built-in `openai` provider and the command
        calls the TokenPro `/v1/images/generations` endpoint with the existing
        global Key and selected image-group model route.

        ## Generate

        ```bash
        \"{{TOKENPRO_IMAGEGEN_SCRIPT}}\" generate \\
          --prompt \"<complete image prompt>\" \\
          --out \"/absolute/path/to/output.png\"
        ```

        Optional arguments are `--model`, `--size`, `--quality`, and `--background`.
        If `--model` is omitted, the command uses the first selected image model
        from the TokenPro Codex catalog and otherwise falls back to `gpt-image-2`.

        Always use an absolute output path and return the generated file exactly
        once using an absolute Markdown image path. Do not call `view_image` for
        the same file or send a second Markdown image link. Do not print, copy,
        or request the global Key.
        """;
    private static final String AGENT_FALLBACK = """
        interface:
          display_name: \"TokenPro ImageGen\"
          short_description: \"Generate images through TokenPro while Codex stays on openai\"
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

package work.tokenpro.client;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Explicit channel-state allowlist. No session, rollout or chat database is copied. */
final class ChannelSettingsBackup {
    private final SecureStore backup;
    private final Map<Path, Optional<String>> values = new LinkedHashMap<>();
    private final Set<Path> trackedRoots = new LinkedHashSet<>();
    ChannelSettingsBackup(SecureStore store, Path config) throws Exception {
        this(store, codexPaths(store, config));
        trackedRoots.add(store.root().resolve("codex-models").toAbsolutePath().normalize());
        CodexChannelState.Detected detected = CodexChannelState.detect(config);
        Map<String,Object> meta = new LinkedHashMap<>();
        meta.put("schema_version", 1);
        meta.put("created_at", Instant.now().toString());
        meta.put("config", config.toAbsolutePath().toString());
        meta.put("channel", detected.channel());
        meta.put("model", detected.model());
        meta.put("model_provider", detected.modelProvider());
        meta.put("openai_base_url", detected.openAiBaseUrl());
        meta.put("auth_mode", detected.authMode());
        meta.put("marker_owners", detected.markerOwners().stream().sorted().toList());
        backup.write("meta.json", Json.stringify(meta));
    }
    private static List<Path> codexPaths(SecureStore store, Path config) throws IOException {
        List<Path> paths = new ArrayList<>();
        paths.addAll(List.of(config, CodexChannelState.auth(config), CodexChannelState.modelsCache(config)));
        for (String name : List.of("codex-original.toml", "codex-selected.json", "codex-model-catalog.json",
                "codex-official-mode.txt", "codex-last-switch-config.toml")) paths.add(store.root().resolve(name));
        Path catalogs = store.root().resolve("codex-models");
        if (Files.isDirectory(catalogs, LinkOption.NOFOLLOW_LINKS)) try (var files = Files.list(catalogs)) {
            paths.addAll(files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList());
        }
        return paths;
    }
    static List<Path> claudePaths(SecureStore store, boolean cli) throws Exception {
        List<Path> paths = new ArrayList<>();
        for (String name : List.of(ClaudeBridgeConfig.FILE, ClaudeCliConfig.FILE, "claude-desktop-state.json")) paths.add(store.root().resolve(name));
        if (!cli) for (Path library : ClaudeDesktopConfig.libraries()) {
            paths.add(library.getParent().resolve("claude_desktop_config.json"));
            paths.add(library.resolve("_meta.json"));
            if (Files.isDirectory(library)) try (var files = Files.list(library)) {
                files.filter(p -> p.getFileName().toString().matches("[a-fA-F0-9-]{36}\\.json")).forEach(paths::add);
            }
        }
        return paths;
    }
    ChannelSettingsBackup(SecureStore store, List<Path> paths) throws Exception {
        backup = new SecureStore(store.root().resolve("channel-backups").resolve(UUID.randomUUID().toString()));
        Platform.privateDirectory(backup.root());
        Path catalogs = store.root().resolve("codex-models").toAbsolutePath().normalize();
        if (paths.stream().map(path -> path.toAbsolutePath().normalize()).anyMatch(path -> path.startsWith(catalogs)))
            trackedRoots.add(catalogs);
        List<Map<String,Object>> manifest = new ArrayList<>();
        int index = 0;
        for (Path path : paths) {
            if (Files.isSymbolicLink(path)) throw new IOException("设置文件为符号链接，已停止切换：" + path.getFileName());
            Optional<String> content = Files.exists(path) ? Optional.of(Files.readString(path)) : Optional.empty();
            values.put(path, content);
            String file = "setting-" + index++ + ".txt";
            if (content.isPresent()) backup.write(file, content.get());
            manifest.add(Map.of("path", path.toAbsolutePath().toString(), "file", file, "existed", content.isPresent()));
        }
        backup.write("manifest.json", Json.stringify(manifest));
    }
    void history(List<CodexHistorySettings.Setting> settings) throws Exception {
        backup.write("thread-settings.json", CodexHistorySettings.encode(settings));
    }
    void restore() throws Exception {
        for (Path root : trackedRoots) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var files = Files.list(root)) {
                for (Path path : files.filter(item -> Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)).toList())
                    if (!values.containsKey(path)) Files.deleteIfExists(path);
            }
        }
        for (var entry : values.entrySet()) {
            Path path = entry.getKey();
            if (entry.getValue().isPresent()) {
                CodexChannelState.writeAtomic(path.toAbsolutePath(), entry.getValue().get());
            } else Files.deleteIfExists(path);
        }
    }
    Path location() { return backup.root(); }

    static void switchClaude(SecureStore store, boolean cli, ClientReconnect.Action stop,
                             ClientReconnect.Action write, ClientReconnect.Action start,
                             ClientReconnect.Action restartPrevious) throws Exception {
        stop.run();
        ChannelSettingsBackup backup = null;
        boolean applied = false;
        try {
            backup = new ChannelSettingsBackup(store, claudePaths(store, cli));
            write.run();
            applied = true;
            start.run();
        } catch (Exception failure) {
            if (applied) throw new ManualStartRequiredException(cli ? "Claude 命令行" : "Claude", failure);
            try {
                stop.run();
                if (backup != null) backup.restore();
                restartPrevious.run();
            } catch (Exception rollback) {
                failure.addSuppressed(rollback);
                throw new IOException("切换失败，自动恢复未完成；设置备份位于 " + (backup == null ? "未建立" : backup.location()), failure);
            }
            throw new IOException("切换失败：" + failure.getMessage() + "；已恢复原设置并重新打开原渠道", failure);
        }
    }
}

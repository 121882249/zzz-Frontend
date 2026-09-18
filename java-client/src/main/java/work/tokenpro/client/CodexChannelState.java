package work.tokenpro.client;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Owns the on-disk auth and model-cache parts of a Codex channel switch. */
final class CodexChannelState {
    static final String OFFICIAL_AUTH_FILE = "codex-official-auth.json";
    record Detected(String channel, String model, String modelProvider, String openAiBaseUrl,
                    String providerBaseUrl, String authMode, Set<String> markerOwners) {}

    private CodexChannelState() {}

    static Path auth(Path config) { return config.resolveSibling("auth.json"); }
    static Path modelsCache(Path config) { return config.resolveSibling("models_cache.json"); }

    static void captureOfficialAuth(SecureStore store, Path config) throws IOException {
        Path auth = auth(config);
        if (!Files.isRegularFile(auth, LinkOption.NOFOLLOW_LINKS)) return;
        String raw = Files.readString(auth, StandardCharsets.UTF_8);
        if (validOfficialAuth(raw)) store.write(OFFICIAL_AUTH_FILE, raw);
    }

    static void useApiKey(SecureStore store, Path config, String key) throws IOException {
        String value = key == null ? "" : key.trim();
        if (value.length() < 8 || value.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("Codex API Key 格式不正确");
        captureOfficialAuth(store, config);
        Map<String,Object> auth = new LinkedHashMap<>();
        auth.put("auth_mode", "apikey");
        auth.put("OPENAI_API_KEY", value);
        writeAtomic(CodexChannelState.auth(config), Json.stringify(auth));
    }

    static void restoreOfficialAuth(SecureStore store, Path config) throws IOException {
        Path auth = auth(config);
        if (Files.isRegularFile(auth, LinkOption.NOFOLLOW_LINKS)) {
            String current = Files.readString(auth, StandardCharsets.UTF_8);
            if (validOfficialAuth(current)) {
                store.write(OFFICIAL_AUTH_FILE, current);
                return;
            }
        }
        Optional<String> saved = store.read(OFFICIAL_AUTH_FILE);
        if (saved.isEmpty() || !validOfficialAuth(saved.get()))
            throw new IOException("没有可恢复的 Codex 官方登录态，请先在 Codex 中重新登录官方账号");
        writeAtomic(auth, saved.get());
    }

    static boolean validOfficialAuth(String raw) {
        try {
            Map<String,Object> auth = Json.object(Json.parse(raw));
            if (!"chatgpt".equals(auth.get("auth_mode"))) return false;
            Map<String,Object> tokens = Json.object(auth.get("tokens"));
            return !Objects.toString(tokens.get("refresh_token"), "").isBlank();
        } catch (RuntimeException ignored) { return false; }
    }

    static void writeChannelCache(Path config, Path catalog) throws IOException {
        Map<String,Object> catalogRoot = Json.object(Json.parse(Files.readString(catalog, StandardCharsets.UTF_8)));
        Object models = catalogRoot.get("models");
        if (!(models instanceof List<?> list) || list.isEmpty()) throw new IOException("TokenPro 模型目录为空");

        Path cache = modelsCache(config);
        Map<String,Object> result = new LinkedHashMap<>();
        if (Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS)) {
            try { result.putAll(Json.object(Json.parse(Files.readString(cache, StandardCharsets.UTF_8)))); }
            catch (RuntimeException ignored) { /* Rebuild a malformed cache from the active catalog. */ }
        }
        result.put("fetched_at", Instant.now().toString());
        result.put("models", list);
        writeAtomic(cache, Json.stringify(result));
    }

    static void markOfficialCacheStale(Path config) throws IOException {
        Path cache = modelsCache(config);
        if (!Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Map<String,Object> value = new LinkedHashMap<>(Json.object(Json.parse(Files.readString(cache, StandardCharsets.UTF_8))));
            value.put("fetched_at", "1970-01-01T00:00:00Z");
            writeAtomic(cache, Json.stringify(value));
        } catch (RuntimeException ignored) {
            // Codex owns the official catalog and will replace an unreadable cache on launch.
        }
    }

    static Detected detect(Path config) {
        String raw = "";
        try { if (Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) raw = Files.readString(config, StandardCharsets.UTF_8); }
        catch (IOException ignored) { }
        String mode = authMode(config);
        String provider = "", override = "", model = "", providerUrl = "";
        Set<String> markers = Set.of();
        try {
            provider = CodexSwitchConfig.rootValue(raw, "model_provider").orElse("");
            override = CodexSwitchConfig.rootValue(raw, "openai_base_url").orElse("");
            model = CodexSwitchConfig.rootValue(raw, "model").orElse("");
            providerUrl = CodexSwitchConfig.providerValue(raw, provider, "base_url").orElse("");
            markers = CodexSwitchConfig.markerOwners(raw);
        } catch (RuntimeException ignored) { return new Detected("unknown", "", "", "", "", mode, Set.of()); }
        String channel;
        if (provider.isBlank() && override.isBlank() && "chatgpt".equals(mode)) channel = "official";
        else if ("openai".equals(provider) && !override.isBlank()) channel = "override-openai:" + host(override);
        else if (!provider.isBlank() && !providerUrl.isBlank()) channel = "named-provider:" + provider + ":" + host(providerUrl);
        else channel = "unknown";
        return new Detected(channel, model, provider, override, providerUrl, mode, markers);
    }

    static void verify(Path config, CodexChannel target) throws IOException {
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Codex 配置未写入");
        Detected detected = detect(config);

        List<String> problems = new ArrayList<>();
        if (target.officialChannel()) {
            if (!detected.modelProvider().isBlank()) problems.add("官方渠道仍存在 model_provider=" + detected.modelProvider());
            if (!detected.openAiBaseUrl().isBlank()) problems.add("官方渠道仍存在 openai_base_url");
        } else {
            if (!target.modelProvider().equals(detected.modelProvider()))
                problems.add("model_provider 应为 " + target.modelProvider() + "，实际为 " + label(detected.modelProvider()));
            if (!sameUrl(target.baseUrl(), detected.providerBaseUrl()))
                problems.add("活动服务商 base_url 与目标渠道不一致");
            if (!detected.markerOwners().contains(target.markerOwner()))
                problems.add(target.name() + " 渠道标记已被其他程序覆盖");
        }
        if (!target.authMode().equals(detected.authMode()))
            problems.add("auth_mode 应为 " + target.authMode() + "，实际为 " + label(detected.authMode()));
        if (target.cacheStrategy() == CodexChannel.CacheStrategy.CHANNEL && !validChannelCache(config))
            problems.add("活动渠道的 models_cache.json 缺失或为空");
        if (!problems.isEmpty()) throw new IOException("Codex 渠道写后校验失败（配置可能被其他程序覆盖）：" + String.join("；", problems));
    }

    private static boolean validChannelCache(Path config) {
        Path cache = modelsCache(config);
        if (!Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            Object models = Json.object(Json.parse(Files.readString(cache, StandardCharsets.UTF_8))).get("models");
            return models instanceof List<?> list && !list.isEmpty();
        } catch (Exception ignored) { return false; }
    }

    static String authMode(Path config) {
        Path auth = auth(config);
        if (!Files.isRegularFile(auth, LinkOption.NOFOLLOW_LINKS)) return "";
        try { return Objects.toString(Json.object(Json.parse(Files.readString(auth, StandardCharsets.UTF_8))).get("auth_mode"), ""); }
        catch (Exception ignored) { return ""; }
    }

    private static String label(String value) { return value == null || value.isBlank() ? "<缺失>" : value; }

    private static String host(String url) {
        try { return Objects.toString(java.net.URI.create(url).getHost(), url); }
        catch (IllegalArgumentException ignored) { return url; }
    }

    private static boolean sameUrl(String expected, String actual) {
        return expected != null && actual != null
            && expected.replaceAll("/+$", "").equalsIgnoreCase(actual.replaceAll("/+$", ""));
    }

    static void writeAtomic(Path target, String value) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), ".tokenpro-switch-", ".tmp");
        try {
            Files.writeString(temp, value, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel file = FileChannel.open(temp, StandardOpenOption.WRITE)) { file.force(true); }
            Platform.privateFile(temp);
            try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            Platform.privateFile(target);
        } finally { Files.deleteIfExists(temp); }
    }
}

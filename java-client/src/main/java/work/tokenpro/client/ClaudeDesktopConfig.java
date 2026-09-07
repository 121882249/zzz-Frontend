package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

final class ClaudeDesktopConfig {
    private static final String STATE_FILE = "claude-desktop-state.json";
    private ClaudeDesktopConfig() {}

    static void install(SecureStore store, ClaudeBridgeConfig bridge) throws Exception {
        Path library = library();
        Files.createDirectories(library);
        Map<String, Object> meta = readMeta(library);
        Map<String, Object> state = store.read(STATE_FILE).map(Json::parse).map(Json::object).orElseGet(LinkedHashMap::new);
        String official = validId(state.get("official_profile_id"));
        String tokenPro = validId(state.get("tokenpro_profile_id"));
        if (official == null) official = UUID.randomUUID().toString();
        if (tokenPro == null) tokenPro = UUID.randomUUID().toString();
        ensureEntry(meta, official, "Claude 官方配置");
        ensureEntry(meta, tokenPro, "TokenPro");
        writeJson(library.resolve(official + ".json"), Map.of());
        List<Map<String, Object>> models = bridge.routes().stream().map(route -> Map.<String, Object>of("name", route.alias(), "labelOverride", route.name())).toList();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("deploymentDisplayName", "TokenPro"); profile.put("endUserAttribution", false);
        profile.put("inferenceProvider", "gateway"); profile.put("inferenceGatewayBaseUrl", bridge.baseUrl());
        profile.put("inferenceGatewayAuthScheme", "bearer"); profile.put("inferenceCredentialKind", "helper-script");
        profile.put("inferenceCredentialHelper", RuntimeCommand.helperExecutable(store));
        profile.put("inferenceCredentialHelperTtlSec", 30); profile.put("inferenceCredentialHelperTimeoutSec", 10);
        profile.put("inferenceCredentialHelperSilentRefreshEnabled", true); profile.put("modelDiscoveryEnabled", false); profile.put("inferenceModels", models);
        writeJson(library.resolve(tokenPro + ".json"), profile);
        meta.put("appliedId", tokenPro); meta.remove("hybridPointer"); writeJson(library.resolve("_meta.json"), meta);
        store.write(STATE_FILE, Json.stringify(Map.of("official_profile_id", official, "tokenpro_profile_id", tokenPro)));
    }

    static void restoreOfficial(SecureStore store) throws Exception {
        Map<String, Object> state = store.read(STATE_FILE).map(Json::parse).map(Json::object).orElseGet(LinkedHashMap::new);
        String official = validId(state.get("official_profile_id"));
        if (official == null) official = UUID.randomUUID().toString();
        Path library = library(); Files.createDirectories(library);
        Map<String, Object> meta = readMeta(library); ensureEntry(meta, official, "Claude 官方配置");
        writeJson(library.resolve(official + ".json"), Map.of()); meta.put("appliedId", official); meta.remove("hybridPointer"); writeJson(library.resolve("_meta.json"), meta);
    }

    static Path library() {
        String home = System.getProperty("user.home");
        return switch (Platform.OS_KIND) {
            case MAC -> Path.of(home, "Library", "Application Support", "Claude-3p", "configLibrary");
            case WINDOWS -> Path.of(System.getenv().getOrDefault("APPDATA", home), "Claude-3p", "configLibrary");
            case LINUX -> Path.of(System.getenv().getOrDefault("XDG_CONFIG_HOME", Path.of(home, ".config").toString()), "Claude-3p", "configLibrary");
        };
    }

    private static Map<String, Object> readMeta(Path library) throws Exception {
        Path path = library.resolve("_meta.json");
        if (!Files.exists(path)) { Map<String, Object> root = new LinkedHashMap<>(); root.put("appliedId", ""); root.put("entries", new ArrayList<>()); return root; }
        Map<String, Object> root = Json.object(Json.parse(Files.readString(path)));
        if (!(root.get("entries") instanceof List<?>) || !(root.get("appliedId") instanceof String)) throw new IllegalStateException("Claude 桌面配置格式不兼容，未进行修改");
        return new LinkedHashMap<>(root);
    }

    @SuppressWarnings("unchecked") private static void ensureEntry(Map<String, Object> meta, String id, String name) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object raw : (List<?>) meta.getOrDefault("entries", List.of())) entries.add(new LinkedHashMap<>(Json.object(raw)));
        Map<String, Object> match = entries.stream().filter(row -> id.equals(row.get("id"))).findFirst().orElse(null);
        if (match == null) entries.add(new LinkedHashMap<>(Map.of("id", id, "name", name))); else match.put("name", name);
        meta.put("entries", entries);
    }

    private static String validId(Object raw) { try { return UUID.fromString(String.valueOf(raw)).toString(); } catch (Exception e) { return null; } }
    private static void writeJson(Path path, Object value) throws Exception { Files.writeString(path, Json.stringify(value), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); Platform.privateFile(path); }
}

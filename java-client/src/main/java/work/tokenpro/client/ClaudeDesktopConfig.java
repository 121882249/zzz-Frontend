package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

final class ClaudeDesktopConfig {
    private static final String STATE_FILE = "claude-desktop-state.json";
    private ClaudeDesktopConfig() {}

    static void install(SecureStore store, ClaudeBridgeConfig bridge, String accountLabel) throws Exception {
        String helper = RuntimeCommand.helperExecutable(store);
        for (Path library : libraries()) installAt(store, bridge, accountLabel, helper, library);
    }

    static void installAt(SecureStore store, ClaudeBridgeConfig bridge, String accountLabel, String helper, Path library) throws Exception {
        Files.createDirectories(library);
        ensureThirdPartyMode(library.getParent());
        Map<String, Object> meta = readMeta(library);
        Map<String, Object> state = store.read(STATE_FILE).map(Json::parse).map(Json::object).orElseGet(LinkedHashMap::new);
        String official = validId(state.get("official_profile_id"));
        String tokenPro = validId(state.get("tokenpro_profile_id"));
        if (official == null) official = UUID.randomUUID().toString();
        if (tokenPro == null) tokenPro = UUID.randomUUID().toString();
        ensureEntry(meta, official, "Claude 官方配置");
        ensureEntry(meta, tokenPro, "TokenPro");
        writeJson(library.resolve(official + ".json"), Map.of());
        List<Map<String, Object>> models = bridge.routes().stream().map(route -> Map.<String, Object>of("name", route.alias(), "labelOverride", PricedModel.displayCase(route.name()))).toList();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("deploymentDisplayName", deploymentDisplayName(accountLabel)); profile.put("endUserAttribution", false);
        profile.put("inferenceProvider", "gateway"); profile.put("inferenceGatewayBaseUrl", bridge.baseUrl());
        profile.put("inferenceGatewayAuthScheme", "bearer"); profile.put("inferenceCredentialKind", "helper-script");
        profile.put("inferenceCredentialHelper", helper);
        profile.put("inferenceCredentialHelperTtlSec", 30); profile.put("inferenceCredentialHelperTimeoutSec", 10);
        profile.put("inferenceCredentialHelperSilentRefreshEnabled", true); profile.put("modelDiscoveryEnabled", false); profile.put("inferenceModels", models);
        writeJson(library.resolve(tokenPro + ".json"), profile);
        meta.put("appliedId", tokenPro); meta.remove("hybridPointer"); writeJson(library.resolve("_meta.json"), meta);
        store.write(STATE_FILE, Json.stringify(Map.of("official_profile_id", official, "tokenpro_profile_id", tokenPro)));
        verifyLibrary(library, bridge);
    }

    static void verifyLibrary(Path library, ClaudeBridgeConfig bridge) throws Exception {
        String id = validId(readMeta(library).get("appliedId"));
        if (id == null) throw new IllegalStateException("Claude 未应用 TokenPro 配置");
        Map<String,Object> profile = Json.object(Json.parse(Files.readString(library.resolve(id + ".json"))));
        List<String> actual = ClaudeAdapter.list(profile.get("inferenceModels")).stream()
            .map(Json::object).map(row -> Objects.toString(row.get("name"), "")).toList();
        if (!actual.equals(bridge.routes().stream().map(ClaudeBridgeConfig.Route::alias).toList())
            || !bridge.baseUrl().equals(profile.get("inferenceGatewayBaseUrl")))
            throw new IllegalStateException("Claude 模型列表与桥接配置不一致，请重新连接");
    }

    static void restoreOfficial(SecureStore store) throws Exception {
        Map<String, Object> state = store.read(STATE_FILE).map(Json::parse).map(Json::object).orElseGet(LinkedHashMap::new);
        String official = validId(state.get("official_profile_id"));
        if (official == null) official = UUID.randomUUID().toString();
        for (Path library : libraries()) {
            Files.createDirectories(library);
            Map<String, Object> meta = readMeta(library); ensureEntry(meta, official, "Claude 官方配置");
            writeJson(library.resolve(official + ".json"), Map.of()); meta.put("appliedId", official); meta.remove("hybridPointer"); writeJson(library.resolve("_meta.json"), meta);
        }
    }

    static Path library() {
        String home = System.getProperty("user.home");
        return switch (Platform.OS_KIND) {
            case MAC -> Path.of(home, "Library", "Application Support", "Claude-3p", "configLibrary");
            case WINDOWS -> Path.of(System.getenv().getOrDefault("APPDATA", home), "Claude-3p", "configLibrary");
            case LINUX -> Path.of(System.getenv().getOrDefault("XDG_CONFIG_HOME", Path.of(home, ".config").toString()), "Claude-3p", "configLibrary");
        };
    }

    static List<Path> libraries() {
        if (Platform.OS_KIND != Platform.OS.WINDOWS) return List.of(library());
        String family = Platform.registeredWindowsApplicationId("Claude").map(id -> id.split("!", 2)[0]).orElse("");
        return windowsLibraries(System.getenv(), System.getProperty("user.home"), family);
    }

    static List<Path> windowsLibraries(Map<String,String> environment, String home, String family) {
        Path local = Path.of(environment.getOrDefault("LOCALAPPDATA", home));
        LinkedHashSet<Path> targets = new LinkedHashSet<>();
        // Store builds use LocalCache/Local, not APPDATA/Claude-3p. Updating
        // only the latter leaves a stale picker even after a full restart.
        if (family.matches("Claude_[A-Za-z0-9]+")) {
            Path cache = local.resolve("Packages").resolve(family).resolve("LocalCache");
            targets.add(cache.resolve("Local/Claude-3p/configLibrary"));
            Path virtualRoaming = cache.resolve("Roaming/Claude-3p/configLibrary");
            if (Files.isDirectory(virtualRoaming)) targets.add(virtualRoaming);
        }
        Path unpackaged = local.resolve("Claude-3p/configLibrary");
        if (Files.isDirectory(unpackaged)) targets.add(unpackaged);
        targets.add(Path.of(environment.getOrDefault("APPDATA", home), "Claude-3p", "configLibrary"));
        return List.copyOf(targets);
    }

    private static void ensureThirdPartyMode(Path root) throws Exception {
        Files.createDirectories(root);
        Path path = root.resolve("claude_desktop_config.json");
        Map<String, Object> config = Files.exists(path)
            ? new LinkedHashMap<>(Json.object(Json.parse(Files.readString(path))))
            : new LinkedHashMap<>();
        config.put("deploymentMode", "3p");
        writeJson(path, config);
    }

    static String deploymentDisplayName(String accountLabel) {
        return accountLabel == null || accountLabel.isBlank() ? "用户账户" : accountLabel.trim();
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

package work.tokenpro.client;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

record ClaudeBridgeConfig(String accountId, int port, String localToken, String accessToken,
                          long keyId, String key, List<Route> routes, List<Route> compatibilityRoutes) {
    static final String FILE = "claude-bridge.json";
    static final String HISTORY_FILE = "claude-route-history.json";
    private static final int MAX_COMPATIBILITY_ROUTES = 64;

    ClaudeBridgeConfig(String accountId, int port, String localToken, String accessToken,
                       long keyId, String key, List<Route> routes) {
        this(accountId, port, localToken, accessToken, keyId, key, routes, List.of());
    }

    ClaudeBridgeConfig {
        routes = List.copyOf(routes);
        compatibilityRoutes = List.copyOf(compatibilityRoutes);
    }

    record Route(String name, String platform, String groupName, long groupId, String alias) {
        boolean usesResponses() { return "openai".equalsIgnoreCase(platform); }
        static Route from(PricedModel model) {
            String id = "anthropic".equalsIgnoreCase(model.platform())
                && model.name().matches("claude-(?:opus|sonnet|haiku|fable|mythos)-[0-9][a-zA-Z0-9.-]*")
                ? model.name() : alias(model);
            return new Route(model.name(), model.platform(), model.groupName(), model.groupId(), id);
        }
        String legacyAlias() { return alias(new PricedModel(name, platform, groupName, groupId)); }
        String signatureId() { return legacyAlias(); }
        private static String alias(PricedModel model) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest((model.groupId() + "::" + model.name()).getBytes(StandardCharsets.UTF_8));
                StringBuilder value = new StringBuilder("claude-tokenpro-");
                for (int i = 0; i < 12; i++) value.append(String.format("%02x", hash[i]));
                return value.toString();
            } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        }
    }

    static ClaudeBridgeConfig create(String accountId, String accessToken, ApiClient.ManagedKey key, List<PricedModel> models) {
        if (models.isEmpty()) throw new IllegalArgumentException("请至少选择一个 Claude 模型");
        List<Route> routes = routesFor(models);
        return new ClaudeBridgeConfig(accountId, 23179, randomToken(), accessToken, key.id(), key.key(), routes);
    }

    static ClaudeBridgeConfig createWithHistory(SecureStore store, String accountId, String accessToken,
                                                 ApiClient.ManagedKey key, List<PricedModel> models) throws Exception {
        return withHistory(store, create(accountId, accessToken, key, models));
    }

    static ClaudeBridgeConfig createCli(String accountId, String accessToken, ApiClient.ManagedKey key, List<PricedModel> models) {
        ClaudeBridgeConfig config = create(accountId, accessToken, key, ModelPickerDialog.orderedModels(models, "Claude"));
        return new ClaudeBridgeConfig(config.accountId(), 23181, config.localToken(), config.accessToken(),
            config.keyId(), config.key(), config.routes(), config.compatibilityRoutes());
    }

    static ClaudeBridgeConfig createCliWithHistory(SecureStore store, String accountId, String accessToken,
                                                    ApiClient.ManagedKey key, List<PricedModel> models) throws Exception {
        return withHistory(store, createCli(accountId, accessToken, key, models));
    }

    static List<Route> routesFor(List<PricedModel> models) {
        Map<String, Long> counts = models.stream().collect(java.util.stream.Collectors.groupingBy(
            model -> model.name().toLowerCase(Locale.ROOT), java.util.stream.Collectors.counting()));
        return models.stream().map(Route::from).map(route -> counts.get(route.name().toLowerCase(Locale.ROOT)) > 1
            ? new Route(route.name(), route.platform(), route.groupName(), route.groupId(), route.legacyAlias()) : route).toList();
    }

    static ClaudeBridgeConfig load(SecureStore store) throws Exception {
        String raw = store.read(FILE).orElseThrow(() -> new IllegalStateException("请先在 TokenPro 中配置 Claude 连接"));
        Map<String, Object> root = Json.object(Json.parse(raw));
        List<Route> routes = routeRows(root.get("routes"));
        List<Route> compatibility = routeRows(root.get("compatibility_routes"));
        ClaudeBridgeConfig result = new ClaudeBridgeConfig(text(root.get("account_id")), (int) number(root.get("port")), text(root.get("local_token")),
            text(root.get("access_token")), number(root.get("key_id")), text(root.get("key")), routes, compatibility);
        if (result.port <= 1024 || result.localToken.isBlank() || result.key.isBlank() || result.routes.isEmpty()) throw new IllegalStateException("Claude 桥接配置无效，请重新应用");
        return result;
    }

    void save(SecureStore store) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("account_id", accountId); root.put("port", port); root.put("local_token", localToken);
        root.put("access_token", accessToken); root.put("key_id", keyId); root.put("key", key);
        root.put("routes", routeDocuments(routes));
        root.put("compatibility_routes", routeDocuments(compatibilityRoutes));
        store.write(FILE, Json.stringify(root));
        List<Route> history = new ArrayList<>(routes);
        history.addAll(compatibilityRoutes);
        store.write(HISTORY_FILE, Json.stringify(Map.of("account_id", accountId, "routes", routeDocuments(history))));
    }

    String baseUrl() { return "http://127.0.0.1:" + port; }
    Route route(String alias) {
        return java.util.stream.Stream.concat(routes.stream(), compatibilityRoutes.stream())
            .filter(route -> route.alias.equals(alias) || route.legacyAlias().equals(alias)).findFirst().orElse(null);
    }

    private static ClaudeBridgeConfig withHistory(SecureStore store, ClaudeBridgeConfig current) throws Exception {
        Map<String,Object> history;
        try { history = store.read(HISTORY_FILE).map(Json::parse).map(Json::object).orElseGet(Map::of); }
        catch (RuntimeException invalidHistory) { return current; }
        if (!current.accountId().equals(text(history.get("account_id")))) return current;
        LinkedHashMap<String,Route> retained = new LinkedHashMap<>();
        List<Route> previous;
        try { previous = routeRows(history.get("routes")); }
        catch (RuntimeException invalidHistory) { return current; }
        for (Route route : previous) {
            boolean active = current.routes().stream().anyMatch(item -> item.alias().equals(route.alias())
                || item.legacyAlias().equals(route.alias()) || item.alias().equals(route.legacyAlias()));
            if (!active) retained.putIfAbsent(route.alias(), route);
            if (retained.size() == MAX_COMPATIBILITY_ROUTES) break;
        }
        return new ClaudeBridgeConfig(current.accountId(), current.port(), current.localToken(), current.accessToken(),
            current.keyId(), current.key(), current.routes(), List.copyOf(retained.values()));
    }

    private static List<Route> routeRows(Object value) {
        List<Route> result = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list) {
            Map<String, Object> row = Json.object(item);
            result.add(new Route(text(row.get("name")), text(row.get("platform")), text(row.get("group_name")),
                number(row.get("group_id")), text(row.get("alias"))));
        }
        return List.copyOf(result);
    }

    private static List<Map<String,Object>> routeDocuments(List<Route> values) {
        return values.stream().map(route -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", route.name); row.put("platform", route.platform); row.put("group_name", route.groupName);
            row.put("group_id", route.groupId); row.put("alias", route.alias); return row;
        }).toList();
    }
    private static String randomToken() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static long number(Object value) { if (value instanceof Number number) return number.longValue(); throw new IllegalArgumentException("Claude 配置缺少数字字段"); }
}

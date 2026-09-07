package work.tokenpro.client;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

record ClaudeBridgeConfig(String accountId, int port, String localToken, String accessToken,
                          long keyId, String key, List<Route> routes) {
    static final String FILE = "claude-bridge.json";

    record Route(String name, String platform, String groupName, long groupId, String alias) {
        boolean usesResponses() { return "openai".equalsIgnoreCase(platform); }
        static Route from(PricedModel model) { return new Route(model.name(), model.platform(), model.groupName(), model.groupId(), alias(model)); }
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
        List<Route> routes = models.stream().map(Route::from).toList();
        return new ClaudeBridgeConfig(accountId, 23179, randomToken(), accessToken, key.id(), key.key(), routes);
    }

    static ClaudeBridgeConfig load(SecureStore store) throws Exception {
        String raw = store.read(FILE).orElseThrow(() -> new IllegalStateException("请先在 TokenPro 中配置 Claude 连接"));
        Map<String, Object> root = Json.object(Json.parse(raw));
        List<Route> routes = new ArrayList<>();
        if (root.get("routes") instanceof List<?> list) for (Object item : list) {
            Map<String, Object> row = Json.object(item);
            routes.add(new Route(text(row.get("name")), text(row.get("platform")), text(row.get("group_name")), number(row.get("group_id")), text(row.get("alias"))));
        }
        ClaudeBridgeConfig result = new ClaudeBridgeConfig(text(root.get("account_id")), (int) number(root.get("port")), text(root.get("local_token")),
            text(root.get("access_token")), number(root.get("key_id")), text(root.get("key")), List.copyOf(routes));
        if (result.port <= 1024 || result.localToken.isBlank() || result.key.isBlank() || result.routes.isEmpty()) throw new IllegalStateException("Claude 桥接配置无效，请重新应用");
        return result;
    }

    void save(SecureStore store) throws Exception {
        List<Map<String, Object>> routeRows = routes.stream().map(route -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", route.name); row.put("platform", route.platform); row.put("group_name", route.groupName);
            row.put("group_id", route.groupId); row.put("alias", route.alias); return row;
        }).toList();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("account_id", accountId); root.put("port", port); root.put("local_token", localToken);
        root.put("access_token", accessToken); root.put("key_id", keyId); root.put("key", key); root.put("routes", routeRows);
        store.write(FILE, Json.stringify(root));
    }

    String baseUrl() { return "http://127.0.0.1:" + port; }
    Route route(String alias) { return routes.stream().filter(route -> route.alias.equals(alias)).findFirst().orElse(null); }
    private static String randomToken() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static long number(Object value) { if (value instanceof Number number) return number.longValue(); throw new IllegalArgumentException("Claude 配置缺少数字字段"); }
}

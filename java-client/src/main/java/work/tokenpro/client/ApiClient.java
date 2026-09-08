package work.tokenpro.client;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

final class ApiClient {
    private static final String BASE = "https://tokenpro.work/api/v1";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();

    Map<String, Object> login(String email, String password) throws Exception {
        if (!email.contains("@") || email.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("请输入有效邮箱");
        if (password.isEmpty()) throw new IllegalArgumentException("请输入密码");
        return request("/auth/login", "POST", Map.of("email", email.trim().toLowerCase(Locale.ROOT), "password", password), null);
    }

    Map<String, Object> me(String token) throws Exception { return request("/auth/me", "GET", null, token); }

    Map<String, Object> keys(String token) throws Exception { return request("/keys?page=1&page_size=100", "GET", null, token); }

    Map<String, Object> key(String token, long id) throws Exception { return request("/keys/" + id, "GET", null, token); }

    List<Map<String, Object>> availableGroups(String token) throws Exception {
        Object value = requestValue("/groups/available", "GET", null, token);
        if (!(value instanceof List<?> rows)) throw new IllegalStateException("可用分组格式不兼容");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) result.add(Json.object(row));
        return result;
    }

    List<PricedModel> pricedModels(String token) throws Exception {
        Map<String, Object> plaza = request("/model-plaza", "GET", null, token);
        Map<Long, Map<String, Object>> available = new HashMap<>();
        for (Map<String, Object> group : availableGroups(token)) {
            Long id = integer(group.get("id"));
            if (id != null) available.put(id, group);
        }
        List<PricedModel> result = new ArrayList<>();
        Object rawGroups = plaza.get("groups");
        if (!(rawGroups instanceof List<?> groups)) throw new IllegalStateException("模型广场格式不兼容");
        for (Object rawGroup : groups) {
            Map<String, Object> group = Json.object(rawGroup);
            Long groupId = integer(group.get("id"));
            if (groupId == null || !available.containsKey(groupId) || !(group.get("models") instanceof List<?> models)) continue;
            Map<String, Object> allowed = available.get(groupId);
            String groupName = text(allowed.getOrDefault("name", group.getOrDefault("name", "TokenPro")));
            String groupPlatform = text(group.getOrDefault("platform", "other"));
            for (Object rawModel : models) {
                Map<String, Object> model = Json.object(rawModel);
                String modelName = text(model.get("name"));
                if (modelName.isBlank() || model.get("pricing") == null) continue;
                result.add(new PricedModel(modelName, text(model.getOrDefault("platform", groupPlatform)), groupName, groupId));
            }
        }
        result.sort(Comparator.comparing(PricedModel::platform).thenComparing(PricedModel::groupName).thenComparing(PricedModel::name));
        return result;
    }

    ManagedKey claudeManagedKey(String token, long initialGroupId) throws Exception {
        return managedKey(token, "TokenPro · Claude", initialGroupId);
    }

    ManagedKey codexManagedKey(String token, long initialGroupId) throws Exception {
        return managedKey(token, "TokenPro · Codex", initialGroupId);
    }

    private ManagedKey managedKey(String token, String keyName, long initialGroupId) throws Exception {
        for (int page = 1; page <= 100; page++) {
            Map<String, Object> result = request("/keys?page=" + page + "&page_size=100", "GET", null, token);
            List<?> items = result.get("items") instanceof List<?> list ? list : List.of();
            for (Object raw : items) {
                Map<String, Object> item = Json.object(raw);
                if (!keyName.equals(text(item.get("name")))) continue;
                if (!"active".equals(text(item.get("status")))) throw new IllegalStateException(keyName + " 专用 Key 已停用");
                Long id = integer(item.get("id"));
                if (id == null) throw new IllegalStateException(keyName + " 专用 Key 缺少编号");
                switchManagedGroup(token, id, initialGroupId, keyName);
                String key = text(key(token, id).get("key"));
                validateKey(key);
                return new ManagedKey(id, key);
            }
            if (items.size() < 100) break;
        }
        Map<String, Object> created = request("/keys", "POST", Map.of("name", keyName, "group_id", initialGroupId), token);
        Long id = integer(created.get("id"));
        String key = text(created.get("key"));
        if (id == null) throw new IllegalStateException("TokenPro 未返回专用 Key 编号");
        validateKey(key);
        return new ManagedKey(id, key);
    }

    void switchClaudeGroup(String token, long keyId, long groupId) throws Exception {
        switchManagedGroup(token, keyId, groupId, "TokenPro · Claude");
    }

    private void switchManagedGroup(String token, long keyId, long groupId, String keyName) throws Exception {
        boolean allowed = availableGroups(token).stream().anyMatch(group -> Objects.equals(integer(group.get("id")), groupId));
        if (!allowed) throw new IllegalStateException("该模型分组当前不可用");
        Map<String, Object> before = key(token, keyId);
        if (!keyName.equals(text(before.get("name"))) || !"active".equals(text(before.get("status")))) {
            throw new IllegalStateException(keyName + " 专用 Key 不可用");
        }
        Long current = integer(before.get("group_id"));
        if (current == null && before.get("group") instanceof Map<?, ?> group) current = integer(Json.object(group).get("id"));
        if (!Objects.equals(current, groupId)) request("/keys/" + keyId, "PUT", Map.of("group_id", groupId), token);
        Map<String, Object> confirmed = key(token, keyId);
        Long confirmedGroup = integer(confirmed.get("group_id"));
        if (confirmedGroup == null && confirmed.get("group") instanceof Map<?, ?> group) confirmedGroup = integer(Json.object(group).get("id"));
        if (!Objects.equals(confirmedGroup, groupId)) throw new IllegalStateException("分组切换未被服务器确认");
    }

    private Map<String, Object> request(String path, String method, Object body, String token) throws Exception {
        return Json.object(requestValue(path, method, body, token));
    }

    private Object requestValue(String path, String method, Object body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json").header("Accept-Language", "zh-CN").header("X-User-UI-Request", "1");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("TokenPro 服务返回 HTTP " + response.statusCode());
        Map<String, Object> json = Json.object(Json.parse(response.body()));
        Object code = json.get("code");
        if (code instanceof Number number && number.intValue() != 0 && number.intValue() != 200) {
            throw new IllegalStateException(String.valueOf(json.getOrDefault("message", "TokenPro 未接受此操作")));
        }
        Object data = json.containsKey("code") ? json.get("data") : json;
        return data == null ? Map.of() : data;
    }

    private static Long integer(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static void validateKey(String key) {
        if (key.length() < 8 || key.contains("*") || key.contains("…") || key.contains("...") || key.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("服务器没有返回完整的专用 Key");
        }
    }

    record ManagedKey(long id, String key) {}
}

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

    private Map<String, Object> request(String path, String method, Object body, String token) throws Exception {
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
        return Json.object(data == null ? Map.of() : data);
    }
}

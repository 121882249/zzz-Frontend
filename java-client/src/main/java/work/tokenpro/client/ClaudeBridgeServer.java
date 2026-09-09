package work.tokenpro.client;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;

final class ClaudeBridgeServer implements AutoCloseable {
    private static final int MAX_BODY = 32 * 1024 * 1024;
    private final SecureStore store;
    private final HttpServer server;
    private final HttpClient upstream = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ApiClient api = new ApiClient();

    ClaudeBridgeServer(SecureStore store) throws Exception {
        this.store = store; ClaudeBridgeConfig config = ClaudeBridgeConfig.load(store);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.port()), 32);
        server.createContext("/", this::handle); server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        boolean streaming = false; String secret = "";
        try {
            ClaudeBridgeConfig config = ClaudeBridgeConfig.load(store); secret = config.key();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization"); String apiKey = exchange.getRequestHeaders().getFirst("x-api-key");
            if (!("Bearer " + config.localToken()).equals(authorization) && !config.localToken().equals(apiKey)) { error(exchange, 401, "authentication_error", "本地连接凭据已失效，请从 TokenPro 重新应用 Claude 连接"); return; }
            if (exchange.getRequestHeaders().getFirst("Origin") != null) { error(exchange, 403, "permission_error", "Browser requests are not allowed"); return; }
            String method = exchange.getRequestMethod(); String path = exchange.getRequestURI().getPath();
            if (method.equals("GET") && path.equals("/health")) { json(exchange, 200, Map.of("service", "tokenpro-claude-bridge-v1")); return; }
            if (method.equals("GET") && path.equals("/v1/models")) {
                List<Map<String, Object>> models = config.routes().stream().map(route -> Map.<String, Object>of("id", route.alias(), "type", "model", "display_name", route.name(), "created_at", "2026-01-01T00:00:00Z")).toList();
                json(exchange, 200, Map.of("data", models, "has_more", false)); return;
            }
            if (!method.equals("POST") || !(path.equals("/v1/messages") || path.equals("/v1/messages/count_tokens"))) { error(exchange, 404, "not_found_error", "Unsupported endpoint"); return; }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1); if (body.length > MAX_BODY) { error(exchange, 413, "invalid_request_error", "请求内容过大"); return; }
            Map<String, Object> input = Json.object(Json.parse(new String(body, StandardCharsets.UTF_8))); String alias = ClaudeAdapter.text(input.get("model"));
            ClaudeBridgeConfig.Route route = config.route(alias); if (route == null) { error(exchange, 404, "not_found_error", "该模型未在 TokenPro 中选择，请更新模型列表"); return; }
            if (path.endsWith("count_tokens")) { json(exchange, 200, Map.of("input_tokens", ClaudeAdapter.estimateTokens(input), "tokenpro_estimated", true)); return; }
            ClaudeBridgeConfig latest = ClaudeBridgeConfig.load(store);
            if (latest.keyId() != config.keyId() || latest.route(alias) == null) throw new IllegalStateException("模型配置已变化，请重试");
            verifyAccountAndKey(latest);
            Map<String, Object> prepared = ClaudeAdapter.prepareHistory(input, route); prepared.put("model", route.name());
            Map<String, Object> payload = route.usesResponses() ? ClaudeAdapter.responsesRequest(prepared) : prepared;
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://tokenpro.work" + (route.usesResponses() ? "/v1/responses" : "/v1/messages")))
                .timeout(Duration.ofSeconds(90)).header("Authorization", "Bearer " + latest.key()).header("Content-Type", "application/json")
                .header("anthropic-version", Optional.ofNullable(exchange.getRequestHeaders().getFirst("anthropic-version")).orElse("2023-06-01"))
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload)));
            if (!route.usesResponses() && exchange.getRequestHeaders().getFirst("anthropic-beta") != null) request.header("anthropic-beta", exchange.getRequestHeaders().getFirst("anthropic-beta"));
            if (!Boolean.TRUE.equals(input.get("stream"))) handleBuffered(exchange, request.build(), route); else { streaming = true; handleStream(exchange, request.build(), route); }
        } catch (Exception e) { String message = Optional.ofNullable(e.getMessage()).orElse("Claude 桥接请求失败"); if (!secret.isBlank()) message = message.replace(secret, "[redacted]"); if (!streaming) error(exchange, 502, "api_error", message); }
        finally { exchange.close(); }
    }

    private void verifyAccountAndKey(ClaudeBridgeConfig config) throws Exception {
        Map<String, Object> user = api.me(config.accessToken());
        if (!config.accountId().equals(String.valueOf(user.get("id")))) throw new IllegalStateException("TokenPro 登录账户已变化，请重新应用 Claude 连接");
        ApiClient.ManagedKey key = api.globalKey(config.accessToken());
        if (key.id() != config.keyId()) throw new IllegalStateException("TokenPro 全局 Key 已变化，请重新应用 Claude 连接");
    }

    private void handleBuffered(HttpExchange exchange, HttpRequest request, ClaudeBridgeConfig.Route route) throws Exception {
        HttpResponse<String> response = upstream.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) { upstreamError(exchange, response.statusCode(), response.body()); return; }
        Map<String, Object> value = Json.object(Json.parse(response.body()));
        json(exchange, 200, route.usesResponses() ? ClaudeAdapter.responsesResponse(value, route.name()) : ClaudeAdapter.tagNativeResponse(value, route.alias()));
    }

    private void handleStream(HttpExchange exchange, HttpRequest request, ClaudeBridgeConfig.Route route) throws IOException {
        boolean started = false; OutputStream output = null;
        try {
            HttpResponse<InputStream> response = upstream.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) { upstreamError(exchange, response.statusCode(), new String(response.body().readNBytes(65536), StandardCharsets.UTF_8)); return; }
            String contentType = response.headers().firstValue("content-type").orElse(""); if (!contentType.contains("text/event-stream")) throw new IllegalStateException("上游未返回流式响应");
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8"); exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.sendResponseHeaders(200, 0); started = true;
            output = exchange.getResponseBody(); BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            ClaudeAdapter.ResponsesStream adapter = new ClaudeAdapter.ResponsesStream(route.name()); StringBuilder data = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (!data.isEmpty() && !data.toString().equals("[DONE]")) {
                    Map<String, Object> event = Json.object(Json.parse(data.toString()));
                    if (route.usesResponses()) for (Map<String, Object> item : adapter.consume(event)) sendEvent(output, item);
                    else {
                        if (event.get("content_block") instanceof Map<?, ?>) event.put("content_block", ClaudeAdapter.tagBlock(Json.object(event.get("content_block")), route.alias()));
                        if (event.get("delta") instanceof Map<?, ?> deltaRaw) { Map<String, Object> delta = new LinkedHashMap<>(Json.object(deltaRaw)); if ("signature_delta".equals(delta.get("type")) && delta.get("signature") instanceof String signature) delta.put("signature", ClaudeAdapter.prefix(route.alias()) + signature); event.put("delta", delta); }
                        sendEvent(output, event);
                    }
                }
                data.setLength(0);
            } else if (line.startsWith("data:")) { if (!data.isEmpty()) data.append('\n'); data.append(line.substring(5).trim()); }
            }
            if (route.usesResponses() && !adapter.completed()) sendEvent(output, Map.of("type", "error", "error", Map.of("type", "api_error", "message", "上游连接提前结束")));
        } catch (Exception e) {
            String message = Optional.ofNullable(e.getMessage()).orElse("流式请求失败");
            if (started && output != null) sendEvent(output, Map.of("type", "error", "error", Map.of("type", "api_error", "message", message)));
            else error(exchange, 502, "api_error", message);
        }
    }

    private static void sendEvent(OutputStream output, Map<String, Object> event) throws IOException {
        String value = "event: " + event.getOrDefault("type", "message") + "\ndata: " + Json.stringify(event) + "\n\n"; output.write(value.getBytes(StandardCharsets.UTF_8)); output.flush();
    }
    private static void upstreamError(HttpExchange exchange, int status, String body) throws IOException { error(exchange, status >= 400 && status < 600 ? status : 502, "api_error", "TokenPro 上游返回 HTTP " + status); }
    private static void json(HttpExchange exchange, int status, Object value) throws IOException { byte[] bytes = Json.stringify(value).getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
    private static void error(HttpExchange exchange, int status, String type, String message) throws IOException { json(exchange, status, Map.of("type", "error", "error", Map.of("type", type, "message", message))); }
    public void close() { server.stop(0); }
}

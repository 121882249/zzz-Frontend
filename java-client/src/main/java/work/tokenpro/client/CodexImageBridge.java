package work.tokenpro.client;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated loopback-only Responses adapter owned by the desktop client. */
final class CodexImageBridge implements AutoCloseable {
    static final String FILE = "codex-image-bridge.json";
    static final String BASE = "http://127.0.0.1:23180/v1";
    private static final String SERVICE = "tokenpro-codex-images-v1";
    private final SecureStore store;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();

    CodexImageBridge(SecureStore store) throws Exception {
        this(store, 23180);
    }
    CodexImageBridge(SecureStore store, int port) throws Exception {
        this.store = store;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        server.createContext("/", this::handle); server.setExecutor(workers); server.start();
    }
    int port() { return server.getAddress().getPort(); }

    static String configure(SecureStore store, String upstream, String key) throws Exception {
        if (!"https://tokenpro.work/v1".equals(upstream)) throw new IllegalArgumentException("生图显示连接仅支持 TokenPro 官方接口");
        String token = "";
        try { token = Objects.toString(load(store).get("token"), ""); } catch (Exception ignored) { }
        if (token.isBlank()) { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        store.write(FILE, Json.stringify(Map.of("token", token, "key", key)));
        ensureRunning(store);
        return token;
    }
    private static Map<String, Object> load(SecureStore store) throws Exception {
        return Json.object(Json.parse(store.read(FILE).orElseThrow(() -> new IOException("请重新应用 Codex 生图模型"))));
    }
    static boolean healthy(SecureStore store) {
        try {
            String token = Objects.toString(load(store).get("token"), "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE+"/health")).timeout(Duration.ofSeconds(1)).header("Authorization", "Bearer "+token).build();
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build().send(request, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().equals(SERVICE);
        } catch (Exception e) { return false; }
    }
    static synchronized void ensureRunning(SecureStore store) throws Exception {
        if (healthy(store)) return;
        Process process = new ProcessBuilder(RuntimeCommand.withArgs("--codex-image-bridge")).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process.getOutputStream().close();
        for (int i=0; i<30; i++) { Thread.sleep(100); if (healthy(store)) return; if (!process.isAlive()) break; }
        throw new IOException("本机生图连接未启动，请检查端口 23180 是否被占用");
    }
    static void resumeIfConfigured(SecureStore store) throws Exception {
        if (store.read(FILE).isPresent()) ensureRunning(store);
    }
    static void stop(SecureStore store) throws Exception {
        if (!healthy(store)) return;
        String token = Objects.toString(load(store).get("token"), "");
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(BASE+"/shutdown")).timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
    }
    private void handle(HttpExchange exchange) throws IOException {
        boolean started = false;
        try {
            Map<String, Object> config = load(store);
            String token = Objects.toString(config.get("token"), "");
            if (token.isBlank() || !("Bearer "+token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) { reply(exchange, 401, "Local authorization required"); return; }
            if (exchange.getRequestHeaders().containsKey("Origin")) { reply(exchange, 403, "Browser requests are not allowed"); return; }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (method.equals("GET") && path.equals("/v1/health")) { reply(exchange, 200, SERVICE); return; }
            if (method.equals("POST") && path.equals("/v1/shutdown")) { reply(exchange, 200, "stopping"); Thread.ofVirtual().start(this::close); return; }
            if (!(method.equals("POST") && Set.of("/v1/responses", "/v1/responses/compact").contains(path)) && !(method.equals("GET") && path.equals("/v1/models"))) { reply(exchange, 404, "Unsupported endpoint"); return; }
            if (exchange.getRequestURI().getRawQuery() != null) { reply(exchange, 400, "Unexpected query"); return; }
            byte[] body = exchange.getRequestBody().readNBytes(32*1024*1024+1);
            if (body.length > 32*1024*1024) { reply(exchange, 413, "Request too large"); return; }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://tokenpro.work"+path)).timeout(Duration.ofMinutes(10));
            Set<String> skip = Set.of("authorization", "host", "content-length", "connection", "transfer-encoding", "upgrade", "expect", "cookie", "accept-encoding");
            exchange.getRequestHeaders().forEach((name, values) -> { if (!skip.contains(name.toLowerCase(Locale.ROOT))) for (String value : values) request.header(name, value); });
            request.header("Authorization", "Bearer "+Objects.toString(config.get("key"), ""));
            request.header("Accept-Encoding", "identity");
            request.method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                String type = response.headers().firstValue("Content-Type").orElse("application/json");
                exchange.getResponseHeaders().set("Content-Type", type);
                response.headers().firstValue("x-request-id").ifPresent(v -> exchange.getResponseHeaders().set("x-request-id", v));
                CodexImageResponse adapter = new CodexImageResponse(store.root().resolve("generated-images"));
                if (response.statusCode() == 200 && type.contains("text/event-stream")) {
                    exchange.sendResponseHeaders(200, 0); started = true;
                    adapter.stream(input, exchange.getResponseBody());
                } else {
                    byte[] data = input.readNBytes(64*1024*1024+1);
                    if (data.length > 64*1024*1024) throw new IOException("Response too large");
                    if (response.statusCode() == 200 && path.equals("/v1/responses") && type.contains("json")) data = Json.stringify(adapter.transform(Json.object(Json.parse(new String(data, StandardCharsets.UTF_8))))).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(response.statusCode(), data.length); started = true; exchange.getResponseBody().write(data);
                }
            }
        } catch (Exception e) {
            // Never echo credentials, prompts, or upstream exception URLs.
            if (!started) reply(exchange, 502, "Codex 本机生图连接失败，请重试或重新应用模型");
            else { try { exchange.getResponseBody().write("data: {\"type\":\"error\",\"message\":\"本机图片处理未完成，请重试\"}\n\n".getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) {} }
        } finally { exchange.close(); }
    }
    private static void reply(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    void await() throws InterruptedException { stopped.await(); }
    public void close() { server.stop(0); workers.shutdownNow(); client.close(); stopped.countDown(); }
}

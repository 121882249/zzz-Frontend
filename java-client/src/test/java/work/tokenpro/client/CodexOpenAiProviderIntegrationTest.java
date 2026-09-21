package work.tokenpro.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Proves the installed Codex keeps TokenPro's global key and group-qualified model on its native-v2 provider. */
public final class CodexOpenAiProviderIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tokenpro-openai-provider-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch requestReceived = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        PricedModel selected = new PricedModel("gpt-5.6-luna", "openai", "fixture", 41);
        String routedModel = CodexConfig.routedModelId(selected);
        server.createContext("/v1/responses", exchange -> {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }
                Map<String,Object> body = Json.object(Json.parse(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                require(routedModel.equals(body.get("model")), "Codex changed the group-qualified model slug");
                require("Bearer fixture-global-key".equals(exchange.getRequestHeaders().getFirst("Authorization")),
                    "Codex did not send the configured global key");
                requestReceived.countDown();
                Map<String,Object> item = Map.of("type", "message", "id", "msg_fixture", "role", "assistant",
                    "status", "completed", "content", List.of(Map.of("type", "output_text", "text", "OK", "annotations", List.of())));
                String events = "event: response.output_item.done\ndata: "
                    + Json.stringify(Map.of("type", "response.output_item.done", "output_index", 0, "item", item))
                    + "\n\nevent: response.completed\ndata: "
                    + Json.stringify(Map.of("type", "response.completed", "response", Map.of("id", "resp_fixture",
                        "status", "completed", "output", List.of(item), "usage", Map.of("input_tokens", 1, "output_tokens", 1))))
                    + "\n\n";
                reply(exchange, events);
            } catch (Throwable error) {
                failure.set(error);
                requestReceived.countDown();
                exchange.close();
            }
        });
        server.start();
        try {
            Path home = root.resolve("home");
            new CodexConfig(new SecureStore(root.resolve("store")), home.resolve("config.toml")).apply(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                List.of(selected), "fixture-global-key", "fixture@example.test");
            String config = Files.readString(home.resolve("config.toml"));
            require(config.contains("model_provider = \"custom\""), "TokenPro did not select the custom provider");
            require(config.contains("[model_providers.custom]"), "TokenPro custom provider is missing");
            try (var rpc = new CodexAppServerRpc(home)) {
                Map<String,Object> started = rpc.call("thread/start", Map.of(
                    "cwd", root.toString(), "approvalPolicy", "never", "sandbox", "read-only"));
                String threadId = Objects.toString(Json.object(started.get("thread")).get("id"));
                rpc.call("turn/start", Map.of("threadId", threadId,
                    "input", List.of(Map.of("type", "text", "text", "route probe"))));
                require(requestReceived.await(20, TimeUnit.SECONDS), "installed Codex never called the fixture endpoint");
                if (failure.get() != null) throw new AssertionError("native-v2 provider probe failed", failure.get());
            }
            System.out.println("Installed Codex preserved TokenPro global key and tp-g group route on model_provider=custom.");
        } finally {
            server.stop(0);
        }
    }

    private static void reply(HttpExchange exchange, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

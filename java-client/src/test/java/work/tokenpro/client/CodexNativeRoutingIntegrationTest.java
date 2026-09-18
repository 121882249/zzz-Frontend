package work.tokenpro.client;

import com.sun.net.httpserver.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs the installed Codex against loopback only; no user credentials or conversations. */
public final class CodexNativeRoutingIntegrationTest {
    public static void main(String[] args) throws Exception {
        boolean imageOnly = args.length > 0 && "image-only".equals(args[0]);
        Path root = Files.createTempDirectory("tokenpro-native-route-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch images = new CountDownLatch(1);
        CountDownLatch text = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> imageTurn = new AtomicReference<>();
        var pngBuffer = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", pngBuffer);
        byte[] png = pngBuffer.toByteArray();
        String imageRoute = CodexConfig.routedModelId(new PricedModel("gpt-image-2.5-flare", "openai", "Images", 65));
        String chatRoute = imageOnly ? imageRoute : CodexConfig.routedModelId(new PricedModel("gpt-6-astra", "openai", "Chat", 16));
        server.createContext("/v1/responses", exchange -> {
            try {
                Map<String,Object> body = Json.object(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                require(chatRoute.equals(body.get("model")), "text model lost its group-qualified slug");
                require("Bearer fixture-key".equals(exchange.getRequestHeaders().getFirst("Authorization")), "missing configured Bearer");
                require(exchange.getRequestHeaders().getFirst("x-tokenpro-image-mode") == null, "provider must not enable native mode for every group");
                imageTurn.set(Objects.toString(Json.object(body.get("client_metadata")).get("turn_id")));
                require(exchange.getRequestHeaders().getFirst("x-tokenpro-group-id") == null, "text and image groups must not share a static group header");
                text.countDown();
                int n = requests.incrementAndGet();
                if (n > 1) {
                    List<?> outputs = ClaudeAdapter.list(body.get("input")).stream().map(Json::object)
                        .filter(item -> "function_call_output".equals(item.get("type"))).toList();
                    require(!outputs.isEmpty(), "image result never returned to its conversation");
                    require(outputs.stream().map(Json::object).anyMatch(output -> "call_image_fixture".equals(output.get("call_id"))),
                        "image output attached to a different tool call");
                    returned.countDown();
                }
                if (n == 1) {
                    System.out.println("Advertised tools: " + ClaudeAdapter.list(body.get("tools")).stream().map(Json::object)
                        .map(tool -> Map.of("type", Objects.toString(tool.get("type"), ""), "name", Objects.toString(tool.get("name"), ""))).toList());
                }
                Map<String,Object> item = n == 1
                    ? Map.of("type", "function_call", "id", "fc_fixture", "call_id", "call_image_fixture", "name", "imagegen",
                        "namespace", "image_gen", "arguments", "{\"prompt\":\"A blue square, test fixture\"}")
                    : Map.of("type", "message", "id", "msg_fixture", "role", "assistant", "status", "completed",
                        "content", List.of(Map.of("type", "output_text", "text", "Fixture completed", "annotations", List.of())));
                String event = "event: response.output_item.done\ndata: " + Json.stringify(Map.of("type", "response.output_item.done", "output_index", 0, "item", item))
                    + "\n\nevent: response.completed\ndata: " + Json.stringify(Map.of("type", "response.completed", "response", Map.of("id", "resp_fixture_" + n,
                        "status", "completed", "output", List.of(item), "usage", Map.of("input_tokens", 1, "output_tokens", 1)))) + "\n\n";
                reply(exchange, "text/event-stream", event);
            } catch (Throwable e) { failure.set(e); exchange.close(); text.countDown(); images.countDown(); }
        });
        server.createContext("/v1/images/generations", exchange -> {
            try {
                Map<String,Object> body = Json.object(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                require("gpt-image-2".equals(body.get("model")), "unexpected native image model");
                require(imageTurn.get().equals(exchange.getRequestHeaders().getFirst("x-codex-image-turn-id")), "native image request lost exact turn");
                require(exchange.getRequestHeaders().getFirst("x-tokenpro-image-route") == null, "static image route survived");
                require("Bearer fixture-key".equals(exchange.getRequestHeaders().getFirst("Authorization")), "native image request lost Bearer");
                reply(exchange, "application/json", Json.stringify(Map.of("created", 1, "data",
                    List.of(Map.of("b64_json", Base64.getEncoder().encodeToString(png))))));
            } catch (Throwable e) { failure.set(e); exchange.close(); }
            finally { images.countDown(); }
        });
        server.start();
        try {
            Path fallbackHome = root.resolve("fallback-home");
            Files.createDirectories(fallbackHome);
            Map<String,Object> fallback = CodexConfig.fallbackTemplate();
            fallback.put("slug", chatRoute);
            Path fallbackCatalog = fallbackHome.resolve("catalog.json");
            Files.writeString(fallbackCatalog, Json.stringify(Map.of("models", List.of(fallback))));
            Files.writeString(fallbackHome.resolve("config.toml"), "model=" + Json.stringify(chatRoute)
                + "\nmodel_provider=\"custom\"\nmodel_catalog_json=" + Json.stringify(fallbackCatalog.toString()) + "\n"
                + "[model_providers.custom]\nname=\"fixture\"\nbase_url=\"http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1\"\nwire_api=\"responses\"\nrequires_openai_auth=false\nexperimental_bearer_token=\"fixture-key\"\nsupports_websockets=false\n");
            try (var rpc = new CodexAppServerRpc(fallbackHome)) {
                var catalog = rpc.call("model/list", Map.of());
                require(ClaudeAdapter.list(catalog.get("data")).stream().map(Json::object).anyMatch(row -> chatRoute.equals(row.get("model"))),
                    "offline fallback catalog is not accepted by installed Codex");
            }
            Path home = root.resolve("home");
            new CodexConfig(new SecureStore(root.resolve("store")), home.resolve("config.toml")).apply(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                imageOnly ? List.of(new PricedModel("gpt-image-2.5-flare", "openai", "Images", 65)) : List.of(new PricedModel("gpt-6-astra", "openai", "Chat", 16), new PricedModel("gpt-image-2.5-flare", "openai", "Images", 65)),
                "fixture-key", "fixture@example.com");
            try (var rpc = new CodexAppServerRpc(home)) {
                var result = rpc.call("thread/start", Map.of("cwd", root.toString(), "approvalPolicy", "never", "sandbox", "read-only"));
                String id = Objects.toString(Json.object(result.get("thread")).get("id"));
                rpc.call("turn/start", Map.of("threadId", id, "input", List.of(Map.of("type", "text", "text", "Generate the test image"))));
                require(text.await(20, TimeUnit.SECONDS), "Codex never called Responses");
                require(images.await(30, TimeUnit.SECONDS), "Codex never called Images");
                require(returned.await(20, TimeUnit.SECONDS), "image result never returned to Responses");
                if (failure.get() != null) throw new AssertionError("native routing failed", failure.get());
            }
            try (var paths = Files.walk(home.resolve("generated_images"))) {
                Path output = paths.filter(path -> path.getFileName().toString().equals("call_image_fixture.png")).findFirst().orElseThrow();
                require(Arrays.equals(png, Files.readAllBytes(output)), "returned image bytes were lost or changed");
            }
            System.out.println("Real Codex " + (imageOnly ? "image-only" : "mixed") + " selection preserved native image delivery and original call_id (fixture upstream).");
        } finally { server.stop(0); }
    }
    private static void reply(HttpExchange exchange, String type, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}

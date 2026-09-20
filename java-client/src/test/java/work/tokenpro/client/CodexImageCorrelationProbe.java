package work.tokenpro.client;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Captures the exact non-secret HTTP correlation surface used by installed Codex. */
public final class CodexImageCorrelationProbe {
    static final List<Map<String,Object>> captures = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("codex-image-correlation-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        Map<String,byte[]> images = new HashMap<>();
        for (String marker : List.of("ALPHA", "BETA")) {
            var png = new java.io.ByteArrayOutputStream();
            var bitmap = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
            bitmap.setRGB(0, 0, marker.equals("ALPHA") ? 0xff0000 : 0x0000ff);
            javax.imageio.ImageIO.write(bitmap, "png", png);
            images.put(marker, png.toByteArray());
        }
        ConcurrentMap<String,String> turns = new ConcurrentHashMap<>();
        ConcurrentMap<String,String> selectedModels = new ConcurrentHashMap<>();
        var errors = new ConcurrentLinkedQueue<Throwable>();
        CountDownLatch returned = new CountDownLatch(2);
        ConcurrentMap<String,Integer> responseCounts = new ConcurrentHashMap<>();
        CountDownLatch imageRequests = new CountDownLatch(2);

        server.createContext("/v1/responses", exchange -> {
            try {
                Map<String,Object> body = body(exchange);
                capture("responses", exchange, body);
                String marker = marker(body);
                String turn = Objects.toString(Json.object(body.get("client_metadata")).get("turn_id"));
                turns.put(turn, marker);
                selectedModels.put(marker, Objects.toString(body.get("model")));
                int count = responseCounts.merge(marker, 1, Integer::sum);
                if (count == 2) {
                    var outputs = ClaudeAdapter.list(body.get("input")).stream().map(Json::object)
                        .filter(i -> "function_call_output".equals(i.get("type"))).toList();
                    if (outputs.size() != 1 || !("call_" + marker).equals(outputs.getFirst().get("call_id"))) throw new AssertionError("crossed call_id");
                    String returnedImage = Objects.toString(Json.object(ClaudeAdapter.list(outputs.getFirst().get("output")).getFirst()).get("image_url"));
                    if (!returnedImage.endsWith(Base64.getEncoder().encodeToString(images.get(marker)))) throw new AssertionError("crossed image bytes");
                    returned.countDown();
                }
                String callId = "call_" + marker;
                Map<String,Object> item = count == 1
                    ? Map.of("type", "function_call", "id", "fc_" + marker, "call_id", callId,
                        "name", "imagegen", "namespace", "image_gen",
                        "arguments", Json.stringify(Map.of("prompt", "IDENTICAL_PROMPT")))
                    : Map.of("type", "message", "id", "msg_" + marker, "role", "assistant", "status", "completed",
                        "content", List.of(Map.of("type", "output_text", "text", "done " + marker, "annotations", List.of())));
                String event = "event: response.output_item.done\ndata: " + Json.stringify(Map.of("type", "response.output_item.done", "output_index", 0, "item", item))
                    + "\n\nevent: response.completed\ndata: " + Json.stringify(Map.of("type", "response.completed", "response", Map.of(
                        "id", "resp_" + marker + "_" + count, "status", "completed", "output", List.of(item),
                        "usage", Map.of("input_tokens", 1, "output_tokens", 1)))) + "\n\n";
                reply(exchange, "text/event-stream", event);
            } catch (Throwable e) { errors.add(e); exchange.close(); }
        });
        server.createContext("/v1/images/generations", exchange -> {
            try {
                var request = body(exchange);
                capture("images", exchange, request);
                if (!"IDENTICAL_PROMPT".equals(request.get("prompt"))) throw new AssertionError("prompt differs");
                String marker = turns.get(exchange.getRequestHeaders().getFirst("X-Codex-Image-Turn-Id"));
                if (marker == null) throw new AssertionError("no exact turn match");
                imageRequests.countDown();
                if (!imageRequests.await(15, TimeUnit.SECONDS)) throw new AssertionError("not concurrent");
                if (marker.equals("ALPHA")) Thread.sleep(100);
                reply(exchange, "application/json", Json.stringify(Map.of("created", 1, "data",
                    List.of(Map.of("b64_json", Base64.getEncoder().encodeToString(images.get(marker)))))));
            } catch (Throwable e) { errors.add(e); exchange.close(); }
        });
        server.start();
        try {
            var selections = List.of(image("gpt-image-2.5-flare", 65), image("gpt-image-2.5-sunburst", 66));
            Path home = root.resolve("home");
            new CodexConfig(new SecureStore(root.resolve("store")), home.resolve("config.toml")).apply(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                selections,
                "fixture-key", "fixture@example.com");
            try (var rpc = new CodexAppServerRpc(home)) {
                List<String> threads = new ArrayList<>();
                for (String marker : List.of("ALPHA", "BETA")) {
                    String model = CodexConfig.routedModelId(selections.get(marker.equals("ALPHA") ? 0 : 1));
                    var started = rpc.call("thread/start", Map.of("cwd", root.toString(), "approvalPolicy", "never", "sandbox", "read-only", "model", model));
                    String thread = Objects.toString(Json.object(started.get("thread")).get("id"));
                    threads.add(thread);
                    rpc.call("turn/start", Map.of("threadId", thread, "input", List.of(Map.of("type", "text", "text", marker))));
                }
                if (!imageRequests.await(30, TimeUnit.SECONDS)) throw new AssertionError("missing image requests");
                if (!returned.await(20, TimeUnit.SECONDS)) throw new AssertionError("missing image results: " + errors);
            }
            if (!errors.isEmpty()) throw new AssertionError(errors.toString());
            if (selectedModels.values().stream().distinct().count() != 2) throw new AssertionError("not different models");
            try (var files = Files.walk(home.resolve("generated_images"))) {
                var paths = files.filter(Files::isRegularFile).toList();
                for (String marker : images.keySet()) {
                    var path = paths.stream().filter(p -> p.getFileName().toString().equals("call_" + marker + ".png")).findFirst().orElseThrow();
                    if (!Arrays.equals(images.get(marker), Files.readAllBytes(path))) throw new AssertionError("saved bytes mismatch");
                }
            }
            Path report = root.resolve("captures.json");
            Files.writeString(report, Json.stringify(captures));
            System.out.println("REPORT=" + report);
            System.out.println("PASS: distinct models/groups; identical prompts; concurrent reversed replies; exact turn/call/image bytes. " + selectedModels);
        } finally { server.stop(0); executor.shutdownNow(); }
    }

    private static PricedModel image(String name, long groupId) {
        return new PricedModel(name, "openai", "openai", "Images", groupId,
            "image", null, null, List.of(), false, 0d, "", "生图");
    }

    static String marker(Map<String,Object> body) {
        String all = Json.stringify(body);
        if (all.contains("ALPHA")) return "ALPHA";
        if (all.contains("BETA")) return "BETA";
        for (Object raw : ClaudeAdapter.list(body.get("input"))) {
            String call = Objects.toString(Json.object(raw).get("call_id"), "");
            if (call.endsWith("ALPHA")) return "ALPHA";
            if (call.endsWith("BETA")) return "BETA";
        }
        return "UNKNOWN";
    }

    static Map<String,Object> body(HttpExchange exchange) throws Exception {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return bytes.length == 0 ? Map.of() : Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
    }

    static void capture(String endpoint, HttpExchange exchange, Map<String,Object> body) {
        Map<String,Object> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.equals("authorization") && !lower.equals("cookie") && !lower.contains("key")) headers.put(name, values);
        });
        if (endpoint.equals("responses")) body = Map.of("model", body.get("model"), "client_metadata", body.get("client_metadata"));
        captures.add(Map.of("endpoint", endpoint, "headers", headers, "body", body));
    }

    static void reply(HttpExchange exchange, String type, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

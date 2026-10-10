package work.tokenpro.client;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

final class TokenProImageCliTest {
    private TokenProImageCliTest() {}

    static int run() throws Exception {
        Path root = Files.createTempDirectory("tokenpro-imagegen-test-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> expectedRoute = new AtomicReference<>("tp-g57-gpt-5.6-sol");
        AtomicReference<String> expectedBodyModel = new AtomicReference<>("gpt-image-2");
        AtomicReference<String> expectedPrompt = new AtomicReference<>("a fixture cat");
        AtomicReference<String> rejection = new AtomicReference<>("");
        byte[] expected = new byte[]{1, 2, 3, 4};
        server.createContext("/v1/images/generations", exchange -> {
            try {
                require("Bearer fixture-global-key".equals(exchange.getRequestHeaders().getFirst("Authorization")), "global Key is missing");
                require("native-v1".equals(exchange.getRequestHeaders().getFirst("x-tokenpro-image-mode")), "plugin image mode is missing");
                require(expectedRoute.get().equals(exchange.getRequestHeaders().getFirst("x-tokenpro-image-route")), "plugin must use the selected image route");
                Map<String,Object> body = Json.object(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                require(expectedBodyModel.get().equals(body.get("model")), "plugin must use the selected image model");
                require(expectedPrompt.get().equals(body.get("prompt")), "prompt was changed");
                if (!rejection.get().isBlank()) {
                    byte[] response = rejection.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, response.length);
                    exchange.getResponseBody().write(response);
                    return;
                }
                byte[] response = Json.stringify(Map.of("created", 1, "data", List.of(Map.of("b64_json", Base64.getEncoder().encodeToString(expected))))).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable error) { failure.set(error); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            Path config = root.resolve("config.toml");
            Path catalog = root.resolve("catalog.json");
            Files.writeString(catalog, Json.stringify(Map.of("models", List.of(Map.of("slug", "tp-g57-gpt-5.6-sol", "display_name", "GPT Text")))));
            Files.writeString(config, "model = \"tp-g57-gpt-5.6-sol\"\nmodel_provider = \"openai\"\nopenai_base_url = \"http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1\"\nmodel_catalog_json = \"" + catalog + "\"\n");
            Files.writeString(config.resolveSibling("auth.json"), "{\"auth_mode\":\"apikey\",\"OPENAI_API_KEY\":\"fixture-global-key\"}");
            Path output = root.resolve("output.png");
            TokenProImageCli.generateForTest(new String[]{"generate", "--prompt", "a fixture cat", "--out", output.toString()}, config);
            require(Arrays.equals(expected, Files.readAllBytes(output)), "generated image bytes were not saved");
            if (failure.get() != null) throw new AssertionError("image endpoint assertion failed", failure.get());

            Files.writeString(config.resolveSibling("codex-selected.json"), Json.stringify(Map.of(
                "image_model", "gpt-image-2.5-sunburst",
                "models", List.of(Map.of("name", "gpt-image-2.5-sunburst", "group_id", 65)))));
            expectedRoute.set("tp-g65-gpt-image-2.5-sunburst");
            expectedBodyModel.set("gpt-image-2.5-sunburst");
            expectedPrompt.set("a dedicated image group fixture");
            Path dedicatedOutput = root.resolve("dedicated-output.png");
            TokenProImageCli.generateForTest(new String[]{"generate", "--prompt", expectedPrompt.get(), "--out", dedicatedOutput.toString()}, config);
            require(Arrays.equals(expected, Files.readAllBytes(dedicatedOutput)), "dedicated image group bytes were not saved");
            if (failure.get() != null) throw new AssertionError("dedicated image endpoint assertion failed", failure.get());
            require("gpt-image-2".equals(TokenProImageCli.imageRequestModel("tp-g57-gpt-5.6-sol")),
                "text group must use the native TokenPro image driver");
            require("gpt-image-2.5-sunburst".equals(TokenProImageCli.imageRequestModel("tp-g65-gpt-image-2.5-sunburst")),
                "dedicated image group must preserve its selected public model");
            rejection.set("{\"error\":{\"code\":\"group_id_required\",\"message\":\"全局 Key 请求必须携带有效的分组 ID\"}}");
            expectedPrompt.set("a rejected fixture");
            try {
                TokenProImageCli.generateForTest(new String[]{"generate", "--prompt", "a rejected fixture", "--out", root.resolve("rejected.png").toString()}, config);
                throw new AssertionError("image API rejection was ignored");
            } catch (java.io.IOException expectedError) {
                require(expectedError.getMessage().contains("HTTP 400")
                    && expectedError.getMessage().contains("全局 Key 请求必须携带有效的分组 ID"),
                    "image API error detail is surfaced for global-key routing failures");
            }
            return 6;
        } finally { server.stop(0); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

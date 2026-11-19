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
        AtomicReference<String> expectedRoute = new AtomicReference<>("tp-g16-gpt-6-sol");
        AtomicReference<String> expectedBodyModel = new AtomicReference<>("gpt-image-2");
        AtomicReference<String> expectedPrompt = new AtomicReference<>("a fixture cat");
        AtomicReference<String> rejection = new AtomicReference<>("");
        byte[] expected = new byte[]{1, 2, 3, 4};
        server.createContext("/v1/images/generations", exchange -> {
            try {
                require("Bearer fixture-global-key".equals(exchange.getRequestHeaders().getFirst("Authorization")), "global Key is missing");
                require("native-v1".equals(exchange.getRequestHeaders().getFirst("x-tokenpro-image-mode")), "plugin image mode is missing");
                require(expectedRoute.get().equals(exchange.getRequestHeaders().getFirst("x-tokenpro-image-route")),
                    "plugin must route through the active Codex group");
                require(exchange.getRequestHeaders().getFirst("x-tokenpro-group-id") == null
                    && exchange.getRequestHeaders().getFirst("x-tokenpro-image-model") == null,
                    "text-route image requests keep the native-v1 header set");
                Map<String,Object> body = Json.object(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                require(body.get("model") instanceof String model && !model.isBlank(), "body model must not be empty");
                require(expectedBodyModel.get().equals(body.get("model")), "plugin must use the image model for the active route");
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
            writeConfig(config, "tp-g16-gpt-6-sol", server.getAddress().getPort());
            Files.writeString(config.resolveSibling("auth.json"), "{\"auth_mode\":\"apikey\",\"OPENAI_API_KEY\":\"fixture-global-key\"}");
            Path output = root.resolve("output.png");
            TokenProImageCli.generateForTest(new String[]{"generate", "--prompt", "a fixture cat", "--out", output.toString()}, config);
            require(Arrays.equals(expected, Files.readAllBytes(output)), "generated image bytes were not saved");
            if (failure.get() != null) throw new AssertionError("image endpoint assertion failed", failure.get());

            // (a) A saved image-group selection must not pull a text route into that image group.
            Files.writeString(config.resolveSibling("codex-selected.json"), Json.stringify(Map.of(
                "image_model", "gpt-image-2.6-sunburst",
                "group_id", 65,
                "models", List.of(
                    Map.of("name", "gpt-6-sol", "group_id", 16),
                    Map.of("name", "gpt-image-2.6-sunburst", "group_id", 65)))));
            expectedPrompt.set("a text group with a saved image selection");
            Path savedSelectionOutput = root.resolve("saved-selection-output.png");
            TokenProImageCli.generateForTest(new String[]{"generate", "--prompt", expectedPrompt.get(), "--out", savedSelectionOutput.toString()}, config);
            require(Arrays.equals(expected, Files.readAllBytes(savedSelectionOutput)), "text-group image bytes were not saved");
            if (failure.get() != null) throw new AssertionError("saved image selection overrode the active text route", failure.get());

            // (b) The active model itself is a dedicated image-group route.
            writeConfig(config, "tp-g65-gpt-image-2.6-sunburst", server.getAddress().getPort());
            expectedRoute.set("tp-g65-gpt-image-2.6-sunburst");
            expectedBodyModel.set("gpt-image-2.6-sunburst");
            expectedPrompt.set("a dedicated image group fixture");
            Path dedicatedOutput = root.resolve("dedicated-output.png");
            TokenProImageCli.generateForTest(new String[]{"generate", "--prompt", expectedPrompt.get(), "--out", dedicatedOutput.toString()}, config);
            require(Arrays.equals(expected, Files.readAllBytes(dedicatedOutput)), "dedicated image group bytes were not saved");
            if (failure.get() != null) throw new AssertionError("dedicated image endpoint assertion failed", failure.get());

            writeConfig(config, "tp-g65-gpt-image-2", server.getAddress().getPort());
            expectedRoute.set("tp-g65-gpt-image-2");
            expectedBodyModel.set("gpt-image-2");
            expectedPrompt.set("the dedicated gpt-image-2 route");
            Path image2Output = root.resolve("image2-output.png");
            TokenProImageCli.generateForTest(new String[]{"generate", "--prompt", expectedPrompt.get(), "--out", image2Output.toString()}, config);
            require(Arrays.equals(expected, Files.readAllBytes(image2Output)), "gpt-image-2 group bytes were not saved");
            if (failure.get() != null) throw new AssertionError("gpt-image-2 route assertion failed", failure.get());

            require("gpt-image-2".equals(TokenProImageCli.imageRequestModel("tp-g16-gpt-6-sol")),
                "text group must use the native TokenPro image driver");
            require("gpt-image-2".equals(TokenProImageCli.imageRequestModel("tp-g57-gpt-5.6-sol")),
                "another text group must use the native TokenPro image driver");
            require("gpt-image-2.6-sunburst".equals(TokenProImageCli.imageRequestModel("tp-g65-gpt-image-2.6-sunburst")),
                "dedicated image group must preserve its selected public model");
            require("gpt-image-2".equals(TokenProImageCli.imageRequestModel("tp-g65-gpt-image-2")),
                "a gpt-image-2 route keeps that image model in the body");
            require(!TokenProImageCli.imageRequestModel("").isBlank()
                && !TokenProImageCli.imageRequestModel("not-a-route").isBlank(),
                "body model is never empty");

            // Rejection still surfaces while the active model remains a text route.
            writeConfig(config, "tp-g16-gpt-6-sol", server.getAddress().getPort());
            expectedRoute.set("tp-g16-gpt-6-sol");
            expectedBodyModel.set("gpt-image-2");
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
            return 10;
        } finally { server.stop(0); }
    }

    private static void writeConfig(Path config, String model, int port) throws Exception {
        Path catalog = config.getParent().resolve("catalog.json");
        Files.writeString(catalog, Json.stringify(Map.of("models", List.of(Map.of("slug", model, "display_name", "GPT")))));
        Files.writeString(config, "model = \"" + model + "\"\nmodel_provider = \"openai\"\nopenai_base_url = \"http://127.0.0.1:" + port
            + "/v1\"\nmodel_catalog_json = \"" + catalog + "\"\n");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

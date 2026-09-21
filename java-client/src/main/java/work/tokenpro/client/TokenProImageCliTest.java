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
        byte[] expected = new byte[]{1, 2, 3, 4};
        server.createContext("/v1/images/generations", exchange -> {
            try {
                require("Bearer fixture-global-key".equals(exchange.getRequestHeaders().getFirst("Authorization")), "global Key is missing");
                require(exchange.getRequestHeaders().getFirst("x-tokenpro-image-mode") == null, "plugin must not impersonate a native turn");
                Map<String,Object> body = Json.object(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                require("tp-g65-gpt-image-2.5-flare".equals(body.get("model")), "image group route was not selected");
                require("a fixture cat".equals(body.get("prompt")), "prompt was changed");
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
            Files.writeString(catalog, Json.stringify(Map.of("models", List.of(Map.of("slug", "tp-g65-gpt-image-2.5-flare", "display_name", "GPT Image")))));
            Files.writeString(config, "model_provider = \"openai\"\nopenai_base_url = \"http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1\"\nmodel_catalog_json = \"" + catalog + "\"\n");
            Files.writeString(config.resolveSibling("auth.json"), "{\"auth_mode\":\"apikey\",\"OPENAI_API_KEY\":\"fixture-global-key\"}");
            Path output = root.resolve("output.png");
            TokenProImageCli.generateForTest(new String[]{"generate", "--prompt", "a fixture cat", "--out", output.toString()}, config);
            require(Arrays.equals(expected, Files.readAllBytes(output)), "generated image bytes were not saved");
            if (failure.get() != null) throw new AssertionError("image endpoint assertion failed", failure.get());
            return 5;
        } finally { server.stop(0); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

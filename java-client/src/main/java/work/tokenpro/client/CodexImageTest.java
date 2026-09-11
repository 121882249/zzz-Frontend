package work.tokenpro.client;

import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.util.*;

final class CodexImageTest {
    static int run() throws Exception {
        Path root = Files.createTempDirectory("tokenpro-image-test-");
        try {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", jpeg);
            String encoded = Base64.getEncoder().encodeToString(jpeg.toByteArray());
            String imageEvent = Json.stringify(Map.of("type", "response.output_item.done", "item", Map.of("type", "image_generation_call", "status", "completed", "result", encoded)));
            String placeholder = "![小猫](data:image/png;base64,...) end";
            String expected = null;
            for (int split=0; split<=placeholder.length(); split++) {
                CodexImageResponse adapter = new CodexImageResponse(root.resolve("images with spaces"));
                String stream = "data: "+imageEvent+"\n\n";
                for (String part : List.of(placeholder.substring(0, split), placeholder.substring(split))) {
                    stream += "data: "+Json.stringify(Map.of("type", "response.output_text.delta", "item_id", "m1", "output_index", 1, "content_index", 0, "delta", part))+"\n\n: ping\n\n";
                }
                stream += "data: "+Json.stringify(Map.of("type", "response.output_text.done", "text", placeholder))+"\n\ndata: [DONE]";
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                adapter.stream(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), output);
                StringBuilder deltas = new StringBuilder();
                String done = "";
                for (String line : output.toString(StandardCharsets.UTF_8).split("\n")) {
                    if (!line.startsWith("data: ") || line.equals("data: [DONE]")) continue;
                    Map<String,Object> event = Json.object(Json.parse(line.substring(6)));
                    if ("response.output_text.delta".equals(event.get("type"))) deltas.append(event.get("delta"));
                    if ("response.output_text.done".equals(event.get("type"))) done = (String)event.get("text");
                }
                if (deltas.toString().contains("base64,...") || !deltas.toString().contains(".jpg>") || !deltas.toString().endsWith(" end") || !done.equals(deltas.toString())) throw new AssertionError("image stream split "+split);
                expected = done;
            }
            try (var paths = Files.list(root.resolve("images with spaces"))) {
                List<Path> saved = paths.toList();
                if (saved.size()!=1 || !Arrays.equals(jpeg.toByteArray(), Files.readAllBytes(saved.getFirst()))) throw new AssertionError("image bytes/deduplication");
            }
            CodexImageResponse adapter = new CodexImageResponse(root.resolve("images with spaces"));
            adapter.transform(Json.object(Json.parse(imageEvent)));
            String valid = "![cat](https://example.com/cat.jpg)";
            if (!adapter.rewrite(valid).equals(valid) || !new CodexImageResponse(root).rewrite(placeholder).equals(placeholder)) throw new AssertionError("unrelated/missing image rewriting");
            Map<String,Object> terminal = Json.object(Json.parse(Json.stringify(Map.of("type", "response.completed", "response", Map.of("output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", placeholder)))))))));
            adapter.transform(terminal);
            if (Json.stringify(terminal).contains("base64,...") || expected == null) throw new AssertionError("terminal response repair");
            SecureStore store = new SecureStore(root.resolve("store"));
            store.write(CodexImageBridge.FILE, "{\"token\":\"test-local-token\",\"key\":\"unused-upstream-token\"}");
            try (CodexImageBridge bridge = new CodexImageBridge(store, 0); HttpClient client = HttpClient.newHttpClient()) {
                String url = "http://127.0.0.1:"+bridge.port()+"/v1/health";
                if (client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).statusCode()!=401) throw new AssertionError("local auth");
                if (client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer test-local-token").header("Origin", "https://example.com").build(), HttpResponse.BodyHandlers.ofString()).statusCode()!=403) throw new AssertionError("browser origin");
                if (client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer test-local-token").build(), HttpResponse.BodyHandlers.ofString()).statusCode()!=200) throw new AssertionError("bridge health");
            }
            return 7;
        } finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
}

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
            String linked = adapter.rewrite("画好了，一只橘猫测试图。");
            if (!linked.startsWith("![生成图片](<") || !linked.contains(".jpg>)\n\n画好了")) throw new AssertionError("missing generated image fallback link");
            Map<String,Object> terminal = Json.object(Json.parse(Json.stringify(Map.of("type", "response.completed", "response", Map.of("output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", placeholder)))))))));
            adapter.transform(terminal);
            if (Json.stringify(terminal).contains("base64,...") || expected == null) throw new AssertionError("terminal response repair");
            SecureStore store = new SecureStore(root.resolve("store"));
            store.write(CodexImageBridge.FILE, Json.stringify(Map.of(
                "token", "test-local-token", "key", "unused-upstream-token",
                "routes", List.of(
                    Map.of("name", "gpt-5.6-sol", "group_id", 16),
                    Map.of("name", "gpt-image-2.5-sunburst", "group_id", 65)))));
            try (CodexImageBridge bridge = new CodexImageBridge(store, 0); HttpClient client = HttpClient.newHttpClient()) {
                String url = "http://127.0.0.1:"+bridge.port()+"/v1/health";
                if (client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).statusCode()!=401) throw new AssertionError("local auth");
                if (client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer test-local-token").header("Origin", "https://example.com").build(), HttpResponse.BodyHandlers.ofString()).statusCode()!=403) throw new AssertionError("browser origin");
                if (client.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer test-local-token").build(), HttpResponse.BodyHandlers.ofString()).statusCode()!=200) throw new AssertionError("bridge health");
            }
            var headersSent = new java.util.concurrent.CountDownLatch(1);
            var releaseUpstream = new java.util.concurrent.CountDownLatch(1);
            var upstream = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
            var imageRequests = new java.util.concurrent.ConcurrentHashMap<String,String>();
            upstream.createContext("/v1/responses",exchange -> {
                try {
                    imageRequests.put("/v1/responses:group", Objects.toString(exchange.getRequestHeaders().getFirst("x-tokenpro-group-id"), ""));
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                    exchange.sendResponseHeaders(200,0);
                    exchange.getResponseBody().write(": still processing\n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush(); headersSent.countDown();
                    releaseUpstream.await(10,java.util.concurrent.TimeUnit.SECONDS);
                } catch(Exception ignored) {} finally { exchange.close(); }
            });
            for (String endpoint : List.of("/v1/images/generations", "/v1/images/edits")) {
                upstream.createContext(endpoint, exchange -> {
                    try {
                        imageRequests.put(endpoint + ":authorization", Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), ""));
                        imageRequests.put(endpoint + ":preferred", Objects.toString(exchange.getRequestHeaders().getFirst("x-tokenpro-image-model"), ""));
                        imageRequests.put(endpoint + ":group", Objects.toString(exchange.getRequestHeaders().getFirst("x-tokenpro-group-id"), ""));
                        imageRequests.put(endpoint + ":body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        byte[] responseBody = ("{\"endpoint\":\"" + endpoint + "\"}").getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, responseBody.length);
                        exchange.getResponseBody().write(responseBody);
                    } finally { exchange.close(); }
                });
            }
            upstream.start();
            try (CodexImageBridge bridge = new CodexImageBridge(store,0,"http://127.0.0.1:"+upstream.getAddress().getPort());
                 HttpClient http=HttpClient.newHttpClient()) {
				var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+bridge.port()+"/v1/responses"))
					.header("Authorization","Bearer test-local-token").header("x-tokenpro-group-id","999")
					.POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"gpt-5.6-sol\"}"))
					.timeout(java.time.Duration.ofSeconds(5)).build();
				var response=http.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
				if(!headersSent.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("stalled upstream did not start");
                for (String endpoint : List.of("/v1/images/generations", "/v1/images/edits")) {
                    String requestBody = endpoint.endsWith("edits") ? "multipart fixture" : "{\"model\":\"gpt-image-2\",\"prompt\":\"draw\"}";
                    var imageRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+bridge.port()+endpoint))
                        .header("Authorization","Bearer test-local-token")
                        .header("x-tokenpro-image-model","gpt-image-2.5-sunburst")
                        .header("x-tokenpro-group-id","999")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
                    var imageResponse = http.send(imageRequest,HttpResponse.BodyHandlers.ofString());
                    if(imageResponse.statusCode()!=200 || !imageResponse.body().contains(endpoint))throw new AssertionError("image endpoint passthrough "+endpoint);
                    if(!"Bearer unused-upstream-token".equals(imageRequests.get(endpoint+":authorization")))throw new AssertionError("image endpoint upstream auth "+endpoint);
                    if(!"gpt-image-2.5-sunburst".equals(imageRequests.get(endpoint+":preferred")))throw new AssertionError("image endpoint preferred model "+endpoint);
                    if(!"65".equals(imageRequests.get(endpoint+":group")))throw new AssertionError("image endpoint selected group "+endpoint);
                    if(!requestBody.equals(imageRequests.get(endpoint+":body")))throw new AssertionError("image endpoint body "+endpoint);
                }
                if(!"16".equals(imageRequests.get("/v1/responses:group")))throw new AssertionError("responses selected group");
                try(var body=response.get(5,java.util.concurrent.TimeUnit.SECONDS).body()) {
                    long before=System.nanoTime(); bridge.close(); bridge.close(); bridge.await();
                    if(System.nanoTime()-before>java.util.concurrent.TimeUnit.SECONDS.toNanos(2))throw new AssertionError("bridge shutdown waited on unfinished stream");
                }
            } finally {releaseUpstream.countDown();upstream.stop(0);}
            return 17;
        } finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
}

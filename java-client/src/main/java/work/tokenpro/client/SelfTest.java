package work.tokenpro.client;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

final class SelfTest {
    static void run() throws Exception {
        int passed = 0;
        Map<String, Object> value = Json.object(Json.parse("{\"name\":\"TokenPro\",\"items\":[1,true,null],\"n\":12}"));
        check("TokenPro".equals(value.get("name")), "JSON string"); passed++;
        check(value.get("items") instanceof List<?> list && list.size() == 3, "JSON array"); passed++;
        check(Json.stringify(value).contains("\"TokenPro\""), "JSON writer"); passed++;
        String sample = "before\n# >>> TokenPro managed >>>\nmanaged\n# <<< TokenPro managed <<<\nafter\n";
        check(CodexConfig.stripManaged(sample).equals("before\nafter\n"), "managed config removal"); passed++;
        String config = "model = \"old\"\nmodel_provider = \"openai\"\n[features]\napps = true\n";
        check(CodexConfig.stripRootOverrides(config).equals("[features]\napps = true\n"), "root override removal"); passed++;
        String liveConfig = "[features]\napps = false\n[new_setting]\nenabled = true\n";
        String restoredConfig = CodexConfig.restoreRootOverrides(liveConfig, config);
        check(restoredConfig.startsWith("model = \"old\"\nmodel_provider = \"openai\"\n"), "official root model settings restored"); passed++;
        check(restoredConfig.contains("apps = false") && restoredConfig.contains("[new_setting]"), "restore preserves newer Codex settings"); passed++;
        check("https://tokenpro.work/v1".equals(CodexConfig.providerBaseUrl("https://tokenpro.work/v1")), "Codex provider keeps v1 route"); passed++;
        check(Platform.dataDirectory().endsWith("TokenPro"), "platform data directory"); passed++;
        PricedModel priced = new PricedModel("gpt-test", "openai", "GPT", 16);
        check("GPT-Test".equals(priced.displayName()), "GPT model display name"); passed++;
        check("GPT-Image-2.5-Sunburst".equals(new PricedModel("gpt-image-2.5-sunburst", "openai", "gpt models", 17).displayName()), "GPT acronym and model title case"); passed++;
        check("GPT\u2060-5.6-Terra".equals(new PricedModel("gpt-5.6-terra", "openai", "gpt models", 17).codexDisplayName()), "Codex keeps GPT vendor label"); passed++;
        check("GPT\u2060-Image-2.5-Sunburst".equals(new PricedModel("gpt-image-2.5-sunburst", "openai", "gpt models", 17).codexDisplayName()), "Codex keeps GPT image vendor label"); passed++;
        check("Claude-3.7-Sonnet".equals(new PricedModel("claude-3.7-sonnet", "anthropic", "claude models", 17).displayName()), "model title case"); passed++;
        check("Claude-Opus-4.8".equals(new PricedModel("claude-opus-4-8", "anthropic", "claude", 17).displayName()), "numeric model version punctuation"); passed++;
        check("Claude-Fable-5.1".equals(new PricedModel("claude-fable-5-1", "anthropic", "claude", 17).displayName()), "minor model version punctuation"); passed++;
        check("Claude Models".equals(new PricedModel("claude", "anthropic", "claude models", 17).displayGroupName()), "vendor group title case"); passed++;
        check("Gemini".equals(new PricedModel("Gemini", "google", "Google", 17).displayName()), "non-GPT model display name"); passed++;
        check(new PricedModel("gpt-image-2.5-sunburst", "openai", "GPT", 17).isImageGeneration(), "image model classification"); passed++;
        PricedModel movedImage = new PricedModel("gpt-image-2.5-sunburst", "openai", "New image group", 99);
        check(ModelPickerDialog.matchesSelectedImage(movedImage, Set.of(ModelPickerDialog.imageNameId(movedImage.name()))), "image selection follows model across groups"); passed++;
        check(!new PricedModel("gpt-5.6-sol", "openai", "GPT", 17).isImageGeneration(), "chat model classification"); passed++;
        check(CodexConfig.inferredReasoningEfforts(priced).equals(List.of("low", "medium", "high", "xhigh", "max")), "GPT five reasoning levels"); passed++;
        check(CodexConfig.inferredReasoningEfforts(new PricedModel("gemini-3-pro", "google", "Google", 17)).equals(List.of("low", "medium", "high", "xhigh", "max")), "generic models expose five reasoning levels"); passed++;
        check(CodexConfig.inferredReasoningEfforts(new PricedModel("claude-sonnet-5", "anthropic", "Claude", 17)).equals(List.of("low", "medium", "high", "xhigh", "max")), "Claude models expose five reasoning levels"); passed++;
        Map<String, Object> shortNativeProfile = new LinkedHashMap<>();
        shortNativeProfile.put("supported_reasoning_levels", List.of(Map.of("effort", "low", "description", "native low"), Map.of("effort", "high", "description", "native high")));
        shortNativeProfile.put("default_reasoning_level", "high");
        CodexConfig.applyReasoningProfile(shortNativeProfile, priced);
        check(((List<?>) shortNativeProfile.get("supported_reasoning_levels")).size() == 5 && "high".equals(shortNativeProfile.get("default_reasoning_level")), "short native profile expands to five levels"); passed++;
        check(CodexConfig.inferredReasoningEfforts(new PricedModel("gpt-image-2.5", "openai", "GPT", 17)).isEmpty(), "image model omits reasoning"); passed++;
        check("GPT⁠-Image-2.5-Sunburst".equals(CodexConfig.catalogDisplayName(new PricedModel("gpt-image-2.5-sunburst", "openai", "GPT", 17))), "catalog omits model category"); passed++;
        check("Claude-Sonnet-5".equals(CodexConfig.catalogDisplayName(new PricedModel("claude-sonnet-5", "anthropic", "Claude", 17))), "LLM catalog uses model name only"); passed++;
        Map<String, Object> customModel = new LinkedHashMap<>(Map.of("use_responses_lite", true));
        CodexConfig.disableResponsesLite(customModel);
        check(Boolean.FALSE.equals(customModel.get("use_responses_lite")), "custom provider disables Responses Lite"); passed++;
        check(ApiClient.compareNaturalDescending("gpt-5.10", "gpt-5.9") < 0, "model versions sort descending"); passed++;
        check(ApiClient.compareModelVersionDescending("claude-fable-5-1", "claude-opus-5") < 0, "minor version sorts above major version"); passed++;
        check(ApiClient.compareModelVersionDescending("claude-opus-4-8", "claude-opus-4-7") < 0, "decimal model versions sort descending"); passed++;
        PricedModel premium = new PricedModel("claude-fable-5-1", "anthropic", "Claude", 60, "token", 0.00005);
        PricedModel standard = new PricedModel("claude-opus-5", "anthropic", "Claude", 60, "token", 0.000025);
        PricedModel image = new PricedModel("gpt-image-2", "openai", "GPT", 60, "image", null);
        check(ApiClient.compareModelPriceDescending(premium, standard) < 0, "models sort by output price descending"); passed++;
        check(ApiClient.compareModelPriceDescending(standard, image) < 0, "token models sort before non-token models"); passed++;
        PricedModel inputPriced = new PricedModel("gpt-5.6-sol", "openai", "GPT", 60, "token", 0.000004, 0.00003, List.of());
        PricedModel lowerInputPriced = new PricedModel("gpt-5.6-terra", "openai", "GPT", 60, "token", 0.000002, 0.00005, List.of());
        check(ApiClient.compareSelectablePriceDescending(inputPriced, lowerInputPriced) < 0, "picker models sort by displayed input price descending"); passed++;
        check(Math.abs(ApiClient.discountedInputPrice(0.000005, "token", 0.28) - 0.0000014) < 1e-12, "LLM picker price applies effective group discount"); passed++;
        check(Math.abs(ApiClient.discountedInputPrice(0.000005, "image", 0.28) - 0.000005) < 1e-12, "image picker price remains unchanged"); passed++;
        check("Input ¥4.00/M".equals(inputPriced.priceLabel()), "LLM input price label"); passed++;
        PricedModel imagePriced = new PricedModel("gpt-image-2.5", "openai", "Image", 60, "image", null, null,
            List.of(new PricedModel.ImagePrice("1K", 0.03), new PricedModel.ImagePrice("2K", 0.05), new PricedModel.ImagePrice("4K", 0.10)));
        check("1K ¥0.03/IMG · 2K ¥0.05/IMG · 4K ¥0.10/IMG".equals(imagePriced.priceLabel()), "image resolution prices"); passed++;
        PricedModel cheaperImage = new PricedModel("gpt-image-2", "openai", "Image", 60, "image", null, null,
            List.of(new PricedModel.ImagePrice("1K", 0.01), new PricedModel.ImagePrice("2K", 0.02)));
        check(ApiClient.compareSelectablePriceDescending(imagePriced, cheaperImage) < 0, "image models sort by displayed per-image price descending"); passed++;
        check(ModelPickerDialog.groupRank(new PricedModel("gpt-5.6", "openai", "GPT", 1))
            < ModelPickerDialog.groupRank(new PricedModel("claude-5", "anthropic", "Claude", 2)), "GPT groups precede Claude"); passed++;
        check(ModelPickerDialog.groupRank(new PricedModel("gemini-3", "google", "Gemini", 3))
            < ModelPickerDialog.groupRank(new PricedModel("mistral-large", "mistral", "Mistral", 4)), "other groups follow Gemini"); passed++;
        check(ModuleLayer.boot().findModule("jdk.crypto.ec").isPresent(), "packaged runtime supports ECDSA TLS certificates"); passed++;
        String releasePayload = "{\"tag_name\":\"v1.2.28\",\"downloads\":{\"windows-x64\":{\"url\":\"https://tokenpro.work/downloads/latest/TokenPro-Windows-x64.exe\",\"sha256\":\"abc\"}}}";
        TokenProFrame.ReleaseInfo release = TokenProFrame.releaseForPlatform(releasePayload, "windows-x64");
        check("1.2.28".equals(release.version()) && release.downloadUrl().endsWith(".exe") && "abc".equals(release.sha256()), "automatic update manifest"); passed++;
        check(Updater.platformKey().startsWith(Platform.OS_KIND == Platform.OS.MAC ? "macos-" : Platform.OS_KIND == Platform.OS.WINDOWS ? "windows-" : "linux-"), "automatic update platform mapping"); passed++;
        ClaudeBridgeConfig.Route route = ClaudeBridgeConfig.Route.from(priced);
        check(route.alias().matches("claude-tokenpro-[0-9a-f]{24}"), "Claude alias"); passed++;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", route.alias()); request.put("max_tokens", 100); request.put("stream", false);
        request.put("messages", List.of(Map.of("role", "developer", "content", "rules"), Map.of("role", "user", "content", "hello")));
        Map<String, Object> prepared = ClaudeAdapter.prepareHistory(request, route); prepared.put("model", priced.name());
        Map<String, Object> converted = ClaudeAdapter.responsesRequest(prepared);
        check(((List<?>) converted.get("input")).stream().map(Json::object).anyMatch(item -> "developer".equals(item.get("role"))), "developer role preserved"); passed++;
        check(ClaudeAdapter.estimateTokens(request) > 0, "token estimate"); passed++;
        Map<String, Object> nativeResponse = new LinkedHashMap<>();
        nativeResponse.put("content", List.of(Map.of("type", "thinking", "thinking", "x", "signature", "secret-signature")));
        String tagged = String.valueOf(Json.object(((List<?>) ClaudeAdapter.tagNativeResponse(nativeResponse, route.alias()).get("content")).getFirst()).get("signature"));
        check(tagged.startsWith(ClaudeAdapter.prefix(route.alias())), "thinking signature tagging"); passed++;
        Map<String, Object> upstream = new LinkedHashMap<>(); upstream.put("id", "resp_1"); upstream.put("status", "completed");
        upstream.put("output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", "ok")))));
        upstream.put("usage", Map.of("input_tokens", 8, "output_tokens", 2, "input_tokens_details", Map.of("cached_tokens", 3)));
        check("end_turn".equals(ClaudeAdapter.responsesResponse(upstream, "gpt-test").get("stop_reason")), "Responses conversion"); passed++;
        Path temporary = Files.createTempDirectory("tokenpro-bridge-test-");
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            int port = socket.getLocalPort(); socket.close();
            SecureStore testStore = new SecureStore(temporary);
            ClaudeBridgeConfig testConfig = new ClaudeBridgeConfig("1", port, "local-test-token", "account-test-token", 7, "sk-test-secret", List.of(route));
            testConfig.save(testStore);
            try (ClaudeBridgeServer ignored = new ClaudeBridgeServer(testStore)) {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                URI health = URI.create("http://127.0.0.1:" + port + "/health");
                HttpResponse<String> unauthorized = http.send(HttpRequest.newBuilder(health).GET().build(), HttpResponse.BodyHandlers.ofString());
                check(unauthorized.statusCode() == 401, "bridge rejects missing auth"); passed++;
                HttpResponse<String> healthy = http.send(HttpRequest.newBuilder(health).header("Authorization", "Bearer local-test-token").GET().build(), HttpResponse.BodyHandlers.ofString());
                check(healthy.statusCode() == 200 && healthy.body().contains("tokenpro-claude-bridge-v1"), "bridge health"); passed++;
                HttpRequest count = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/messages/count_tokens")).header("x-api-key", "local-test-token").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(request))).build();
                HttpResponse<String> counted = http.send(count, HttpResponse.BodyHandlers.ofString());
                check(counted.statusCode() == 200 && Boolean.TRUE.equals(Json.object(Json.parse(counted.body())).get("tokenpro_estimated")), "bridge token count"); passed++;
                HttpRequest browser = HttpRequest.newBuilder(health).header("Authorization", "Bearer local-test-token").header("Origin", "https://example.com").GET().build();
                check(http.send(browser, HttpResponse.BodyHandlers.ofString()).statusCode() == 403, "bridge rejects browser origin"); passed++;
            }
        } finally {
            try (var files = Files.walk(temporary)) { files.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} }); }
        }
        System.out.println("TokenPro Java self-test: " + passed + " checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}

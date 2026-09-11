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
        check(value.get("n") instanceof Long && ((Long) value.get("n")) == 12L, "JSON integer remains integral"); passed++;
        check(Json.object(Json.parse("{\"max_tokens\":8192}")).get("max_tokens") instanceof Long, "Claude integer fields remain integral"); passed++;
        check(ClaudeBridgeServer.sameIdentifier("18.0", 18L) && !ClaudeBridgeServer.sameIdentifier("18.0", 19L), "legacy decimal account IDs remain compatible"); passed++;
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
        String managedActor = "# >>> TokenPro managed >>>\n[model_providers.custom]\nname = \"Codex\"\nhttp_headers = { \"x-openai-actor-authorization\" = \"Codex\", \"x-tokenpro-image-model\" = \"gpt-image\" }\n# <<< TokenPro managed <<<\n";
        String emailActor = CodexConfig.withActor(managedActor, "user@example.com");
        check(emailActor.contains("name = \"user@example.com\"") && emailActor.contains("\"x-openai-actor-authorization\" = \"user@example.com\""), "existing Codex actor migrates to account email"); passed++;
        check(Platform.dataDirectory().endsWith("TokenPro"), "platform data directory"); passed++;
        Map<String, String> windowsEnvironment = Map.of(
            "LOCALAPPDATA", "C:\\Users\\Test\\AppData\\Local",
            "APPDATA", "C:\\Users\\Test\\AppData\\Roaming",
            "ProgramFiles", "C:\\Program Files",
            "ProgramFiles(x86)", "C:\\Program Files (x86)",
            "ProgramData", "C:\\ProgramData",
            "PATH", "C:\\Tools;C:\\Windows\\System32");
        check(pathsContain(Platform.applicationCandidates(Platform.OS.MAC, "/Users/test", Map.of(), "Codex"), "ChatGPT.app"), "macOS desktop app candidates"); passed++;
        check(pathsContain(Platform.applicationCandidates(Platform.OS.WINDOWS, "C:\\Users\\Test", windowsEnvironment, "Codex"), "Programs/ChatGPT/ChatGPT.exe"), "Windows Codex desktop candidates"); passed++;
        check(pathsContain(Platform.applicationCandidates(Platform.OS.WINDOWS, "C:\\Users\\Test", windowsEnvironment, "Claude"), "AnthropicClaude/Claude.exe"), "Windows Claude desktop candidates"); passed++;
        check(pathsContain(Platform.windowsVersionedInstallRoots("C:\\Users\\Test", windowsEnvironment, "Claude"), "AnthropicClaude"), "Windows versioned Claude install root"); passed++;
        check(pathsContain(Platform.applicationCandidates(Platform.OS.LINUX, "/home/test", Map.of(), "Claude"), ".local/share/applications/claude.desktop"), "Linux desktop app candidates"); passed++;
        check(pathsContain(Platform.applicationCandidates(Platform.OS.LINUX, "/home/test", Map.of(), "Codex"), "/opt/chatgpt/chatgpt"), "Linux ChatGPT Codex desktop candidate"); passed++;
        check(pathsContain(Platform.commandCandidates(Platform.OS.MAC, "/Users/test", Map.of("PATH", ""), "claude"), ".claude/local/claude"), "macOS Claude CLI candidates"); passed++;
        check(pathsContain(Platform.commandCandidates(Platform.OS.WINDOWS, "C:\\Users\\Test", windowsEnvironment, "codex"), "npm/codex.cmd"), "Windows Codex CLI candidates"); passed++;
        check(pathsContain(Platform.commandCandidates(Platform.OS.LINUX, "/home/test", Map.of("PATH", ""), "codex"), ".local/bin/codex"), "Linux Codex CLI candidates"); passed++;
        check(Platform.applicationEvidenceMatches("Codex", "OpenAI.ChatGPT_2026.9_x64"), "Windows Store ChatGPT evidence"); passed++;
        check(Platform.applicationEvidenceMatches("Claude", "AnthropicClaude | C:\\Apps\\Claude.exe"), "Windows Claude registry evidence"); passed++;
        check(!Platform.applicationEvidenceMatches("Claude", "OpenAI.ChatGPT"), "desktop evidence does not cross vendors"); passed++;
        Platform.InstallationSnapshot knownInstalled = new Platform.InstallationSnapshot(true, true, true, true);
        check(knownInstalled.equals(Platform.installationSnapshot(knownInstalled)), "installed application state is cached within a run"); passed++;
        Path installFixture = Files.createTempDirectory("tokenpro-install-detection-");
        try {
            String fixtureHome = installFixture.resolve("home").toString();
            Map<String, String> fixtureEnvironment = new HashMap<>();
            fixtureEnvironment.put("PATH", "");
            fixtureEnvironment.put("LOCALAPPDATA", installFixture.resolve("local").toString());
            fixtureEnvironment.put("APPDATA", installFixture.resolve("roaming").toString());
            fixtureEnvironment.put("ProgramFiles", installFixture.resolve("program-files").toString());
            fixtureEnvironment.put("ProgramFiles(x86)", installFixture.resolve("program-files-x86").toString());
            fixtureEnvironment.put("ProgramData", installFixture.resolve("program-data").toString());
            for (String client : List.of("Codex", "Claude")) {
                Path candidate = fixtureApplicationCandidate(Platform.applicationCandidates(Platform.OS_KIND, fixtureHome, fixtureEnvironment, client), installFixture);
                if (candidate.toString().endsWith(".app")) Files.createDirectories(candidate);
                else { Files.createDirectories(candidate.getParent()); Files.writeString(candidate, "fixture"); }
                check(Platform.filesystemApplicationInstalled(Platform.OS_KIND, fixtureHome, fixtureEnvironment, client), Platform.OS_KIND + " " + client + " desktop fixture detection"); passed++;
            }
            for (String command : List.of("codex", "claude")) {
                Path candidate = fixtureCommandCandidate(Platform.commandCandidates(Platform.OS_KIND, fixtureHome, fixtureEnvironment, command), installFixture);
                Files.createDirectories(candidate.getParent()); Files.writeString(candidate, "fixture"); candidate.toFile().setExecutable(true);
                check(Platform.commandCandidateInstalled(Platform.OS_KIND, fixtureHome, fixtureEnvironment, command), Platform.OS_KIND + " " + command + " CLI fixture detection"); passed++;
            }
        } finally {
            try (var paths = Files.walk(installFixture)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} }); }
        }
        check("user@example.com".equals(ClaudeDesktopConfig.deploymentDisplayName(" user@example.com "))
            && "用户账户".equals(ClaudeDesktopConfig.deploymentDisplayName("")), "Claude account display name"); passed++;
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
        List<PricedModel> tickerCandidates = List.of(
            new PricedModel("gpt-top", "openai", "GPT", 1, "token", 30d),
            new PricedModel("gpt-second", "openai", "GPT", 1, "token", 20d),
            new PricedModel("gpt-third", "openai", "GPT", 1, "token", 10d),
            new PricedModel("claude-top", "anthropic", "Claude", 2, "token", 40d),
            new PricedModel("claude-second", "anthropic", "Claude", 2, "token", 15d),
            new PricedModel("gemini-top", "google", "Gemini", 3, "token", 18d),
            new PricedModel("gemini-second", "google", "Gemini", 3, "token", 12d),
            new PricedModel("grok-top", "xai", "Grok", 4, "token", 22d),
            new PricedModel("grok-second", "xai", "Grok", 4, "token", 11d),
            image);
        List<String> tickerNames = TokenProFrame.premiumTickerModels(tickerCandidates).stream().map(PricedModel::name).toList();
        check(tickerNames.equals(List.of("gpt-top", "gpt-second", "claude-top", "claude-second", "gemini-top", "gemini-second", "grok-top", "grok-second")), "premium ticker keeps two highest-priced models per vendor"); passed++;
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
        Map<String, Object> subscription = Map.of("monthly_limit_usd", 100d, "monthly_used_usd", 37.5d);
        check(Math.abs(ApiClient.subscriptionRemaining(subscription) - 62.5d) < 1e-9, "subscription remaining balance"); passed++;
        PricedModel richSubscription = new PricedModel("claude-sub", "anthropic", "Gold", 8, "token", 1d, 2d, List.of(), true, 80d);
        PricedModel lowSubscription = new PricedModel("gpt-sub", "openai", "Silver", 9, "token", 1d, 2d, List.of(), true, 20d);
        check(ModelPickerDialog.compareGroups(List.of(richSubscription), List.of(lowSubscription)) < 0, "subscription groups sort by remaining balance"); passed++;
        check(ModelPickerDialog.compareGroups(List.of(lowSubscription), List.of(priced)) < 0, "subscription group precedes regular LLM groups"); passed++;
        PricedModel claudeGroup = new PricedModel("claude-sonnet-5", "anthropic", "Claude", 11);
        PricedModel gptGroup = new PricedModel("gpt-5.6-sol", "openai", "GPT", 12);
        check(ModelPickerDialog.compareGroups(List.of(lowSubscription), List.of(claudeGroup), "Claude") < 0, "Claude client puts subscriptions first"); passed++;
        check(ModelPickerDialog.compareGroups(List.of(claudeGroup), List.of(gptGroup), "Claude") < 0, "Claude client puts Claude before GPT"); passed++;
        List<PricedModel> scattered = List.of(
            new PricedModel("gpt-5.6-terra", "openai", "GPT B", 22),
            new PricedModel("gemini-3", "gemini", "Gemini", 24),
            new PricedModel("claude-sonnet-5", "anthropic", "Claude", 21),
            new PricedModel("gpt-5.6-sol", "openai", "GPT A", 23),
            new PricedModel("grok-4.5", "grok", "Grok", 25));
        List<String> claudeOrdered = ModelPickerDialog.orderedModels(scattered, "Claude").stream().map(PricedModel::name).toList();
        check(claudeOrdered.equals(List.of("claude-sonnet-5", "gpt-5.6-sol", "gpt-5.6-terra", "grok-4.5", "gemini-3")), "Claude export keeps vendors together in picker order"); passed++;
        check(!ModelPickerDialog.supportsClient(imagePriced, "Claude") && ModelPickerDialog.supportsClient(imagePriced, "Codex"), "Claude filters image models"); passed++;
        PricedModel datedSubscription = new PricedModel("gpt-sub", "openai", "Monthly", 10, "token", 1d, 2d, List.of(), true, 30d, "2026-10-31T08:00:00Z");
        check(datedSubscription.subscriptionExpiryLabel().matches("到期 \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"), "subscription expiry label includes minutes"); passed++;
        check(ModelPickerDialog.groupRank(new PricedModel("gpt-5.6", "openai", "GPT", 1))
            < ModelPickerDialog.groupRank(new PricedModel("claude-5", "anthropic", "Claude", 2)), "GPT groups precede Claude"); passed++;
        check(ModelPickerDialog.groupRank(new PricedModel("gemini-3", "google", "Gemini", 3))
            < ModelPickerDialog.groupRank(new PricedModel("mistral-large", "mistral", "Mistral", 4)), "other groups follow Gemini"); passed++;
        check(ModuleLayer.boot().findModule("jdk.crypto.ec").isPresent(), "packaged runtime supports ECDSA TLS certificates"); passed++;
        String releasePayload = "{\"tag_name\":\"v1.2.39\",\"incremental\":{\"url\":\"https://tokenpro.work/downloads/latest/TokenPro-update.jar\",\"sha256\":\"update-sha\"},\"downloads\":{\"windows-x64\":{\"url\":\"https://tokenpro.work/downloads/latest/TokenPro-Windows-x64.exe\",\"sha256\":\"abc\"}}}";
        TokenProFrame.ReleaseInfo release = TokenProFrame.releaseForPlatform(releasePayload, "windows-x64");
        check("1.2.39".equals(release.version()) && release.downloadUrl().endsWith(".exe") && "abc".equals(release.sha256()) && release.hasIncrementalUpdate() && release.preferredUrl().endsWith(".jar") && "update-sha".equals(release.preferredSha256()), "incremental update manifest"); passed++;
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
                HttpRequest shutdown = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/shutdown")).header("Authorization", "Bearer local-test-token").POST(HttpRequest.BodyPublishers.noBody()).build();
                check(http.send(shutdown, HttpResponse.BodyHandlers.ofString()).statusCode() == 200, "bridge accepts authenticated shutdown"); passed++;
            }
        } finally {
            try (var files = Files.walk(temporary)) { files.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} }); }
        }
        System.out.println("TokenPro Java self-test: " + passed + " checks passed");
    }
    private static boolean pathsContain(List<Path> paths, String suffix) {
        String normalizedSuffix = suffix.replace('\\', '/').toLowerCase(Locale.ROOT);
        return paths.stream().map(Path::toString).map(value -> value.replace('\\', '/').toLowerCase(Locale.ROOT)).anyMatch(value -> value.contains(normalizedSuffix));
    }
    private static Path fixtureApplicationCandidate(List<Path> paths, Path fixtureRoot) {
        return paths.stream().filter(path -> path.normalize().startsWith(fixtureRoot.normalize()))
            .filter(path -> Platform.OS_KIND != Platform.OS.LINUX || path.toString().endsWith(".desktop") || path.toString().endsWith(".AppImage"))
            .findFirst().orElseThrow();
    }
    private static Path fixtureCommandCandidate(List<Path> paths, Path fixtureRoot) {
        return paths.stream().filter(path -> path.normalize().startsWith(fixtureRoot.normalize())).findFirst().orElseThrow();
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}

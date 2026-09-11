package work.tokenpro.client;

import java.util.*;

final class ModelCapabilitiesTest {
    static int run() {
        int passed = 0;
        PricedModel opus = new PricedModel("claude-opus-5", "anthropic", "Group A", 10);
        PricedModel opusOther = new PricedModel("claude-opus-5", "anthropic", "Group B", 11);
        ApiClient.ManagedKey key = new ApiClient.ManagedKey(1, "unused");
        ClaudeBridgeConfig single = ClaudeBridgeConfig.create("1", "unused", key, List.of(opus));
        ClaudeBridgeConfig.Route route = single.routes().getFirst();
        check(route.alias().equals("claude-opus-5"), "native Claude model keeps native effort/fast recognition"); passed++;
        check(single.route(route.legacyAlias()) != null, "old chat aliases remain accepted without closing client"); passed++;
        check(route.signatureId().equals(route.legacyAlias()), "signature identity remains group-bound"); passed++;
        ClaudeBridgeConfig duplicate = ClaudeBridgeConfig.create("1", "unused", key, List.of(opus, opusOther));
        check(duplicate.routes().stream().map(ClaudeBridgeConfig.Route::alias).distinct().count() == 2,
            "duplicate model across groups keeps distinct routing IDs"); passed++;
        check(duplicate.route("claude-opus-5") == null, "ambiguous bare model cannot silently pick a group"); passed++;
        check(duplicate.routes().stream().map(ClaudeBridgeConfig.Route::signatureId).distinct().count() == 2,
            "thinking signatures never leak across groups"); passed++;
        Map<String, Object> cli = ClaudeCliConfig.settings(List.of(opus, opusOther), "http://127.0.0.1:23181", "helper");
        List<?> options = (List<?>) Json.object(cli.get("modelPicker")).get("options");
        check(options.stream().allMatch(option -> duplicate.route(String.valueOf(Json.object(option).get("model"))) != null),
            "CLI picker and bridge export exactly the same IDs"); passed++;
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("model", "gpt-6-astra");
        source.put("messages", List.of(Map.of("role", "user", "content", "test")));
        source.put("output_config", Map.of("effort", "max"));
        source.put("speed", "fast");
        String before = Json.stringify(source);
        Map<String, Object> responses = ClaudeAdapter.responsesRequest(source);
        check("max".equals(Json.object(responses.get("reasoning")).get("effort")), "Max is not silently reduced to xhigh"); passed++;
        check("priority".equals(responses.get("service_tier")), "lightning request maps to Responses priority"); passed++;
        check(!responses.containsKey("speed"), "Anthropic speed field does not leak to Responses schema"); passed++;
        check(Json.stringify(source).equals(before), "adapter does not mutate client request"); passed++;
        source.put("service_tier", "ultrafast");
        check("ultrafast".equals(ClaudeAdapter.responsesRequest(source).get("service_tier")),
            "explicit service tier is preserved"); passed++;
        source.remove("speed"); source.remove("service_tier");
        check(!ClaudeAdapter.responsesRequest(source).containsKey("service_tier"), "normal requests do not enable paid fast mode"); passed++;
        source.put("speed", "fast");
        Map<String, Object> nativeRequest = ClaudeAdapter.prepareHistory(source, route);
        check(nativeRequest.get("output_config").equals(source.get("output_config")) && "fast".equals(nativeRequest.get("speed")),
            "Claude-native effort and speed survive history adapter"); passed++;
        Map<String, Object> tagged = ClaudeAdapter.tagNativeResponse(Map.of("content", List.of(
            Map.of("type", "thinking", "thinking", "test", "signature", "opaque"))), route.signatureId());
        Map<String, Object> history = Map.of("messages", List.of(Map.of("role", "assistant", "content", tagged.get("content"))));
        Map<String, Object> prepared = ClaudeAdapter.prepareHistory(history, route);
        List<?> content = (List<?>) Json.object(((List<?>) prepared.get("messages")).getFirst()).get("content");
        check("opaque".equals(Json.object(content.getFirst()).get("signature")), "migrated native alias retains existing thinking signatures"); passed++;
        return passed;
    }
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}
